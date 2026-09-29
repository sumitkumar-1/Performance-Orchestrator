package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.registry.ReadOnlyHttp;
import com.example.perforchestrator.infrastructure.secrets.ConnectionSessions;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SparseProjects {
  public record Checkout(String commit, Map<String,String> files) {}
  private final CommandRunner commands;
  private final ExecutionSettings settings;
  private final ConnectionConfig connections;
  private final ConnectionSessions sessions;
  private final ReadOnlyHttp http;
  public SparseProjects(CommandRunner commands, ExecutionSettings settings, ConnectionConfig connections, ConnectionSessions sessions, ReadOnlyHttp http) {
    this.commands=commands; this.settings=settings; this.connections=connections; this.sessions=sessions; this.http=http;
  }
  public Checkout checkout(Catalog.SourceProject source, String revision) {
    if (source == null) throw Problem.invalid("sourceProject", "Configure the service Bitbucket project");
    var connection = connections.data().bitbucket().get(source.connectionRef());
    if (connection == null) throw Problem.invalid("sourceProject", "Unknown Bitbucket connection");
    String authorization = sessions.authorization("bitbucket", source.connectionRef(), null);
    String clone = source.cloneUrl();
    if (clone == null || clone.isBlank()) {
      ConnectionConfig.safePath(source.projectKey()); ConnectionConfig.safePath(source.repository());
      var response = http.get(URI.create(connection.apiBaseUrl().replaceAll("/$", "") + "/1.0/projects/"
          + source.projectKey() + "/repos/" + source.repository()), authorization, "application/json");
      if (response.status() == 401) sessions.rejected("bitbucket", source.connectionRef());
      ReadOnlyHttp.requireSuccess(response, "bitbucket");
      try {
        for (var link : Json.MAPPER.readTree(response.body()).path("links").path("clone"))
          if (link.path("href").asText().startsWith("https://")) { clone=link.path("href").asText(); break; }
      } catch (Exception e) { throw Problem.invalid("sourceProject", "Bitbucket repository response has no usable HTTPS clone URL"); }
    }
    if (clone == null || clone.isBlank()) throw Problem.invalid("sourceProject.cloneUrl", "No HTTPS clone URL returned; configure an explicit clone URL");
    URI url = ConnectionConfig.base(clone);
    if (!Objects.equals(url.getRawAuthority(), URI.create(connection.apiBaseUrl()).getRawAuthority()))
      throw Problem.invalid("sourceProject.cloneUrl", "Clone URL must use the configured Bitbucket HTTPS authority");
    if (revision == null || !revision.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,249}") || revision.contains(".."))
      throw Problem.invalid("revision", "Choose a Git branch, tag or commit without revision expressions");
    // No credentials in command arguments, repository URLs, disk config, or log output.
    var env = new HashMap<String,String>();
    env.put("GIT_TRACE", "0"); env.put("GIT_TRACE_CURL", "0"); env.put("GIT_CURL_VERBOSE", "0"); env.put("GIT_TRACE2", "0");
    env.put("GIT_TERMINAL_PROMPT", "0"); env.put("GIT_CONFIG_NOSYSTEM", "1");
    env.put("GIT_CONFIG_GLOBAL", "/dev/null"); env.put("GIT_CONFIG_COUNT", "4");
    env.put("GIT_CONFIG_KEY_0", "http.extraHeader"); env.put("GIT_CONFIG_VALUE_0", "Authorization: " + authorization);
    env.put("GIT_CONFIG_KEY_1", "http.followRedirects"); env.put("GIT_CONFIG_VALUE_1", "false");
    env.put("GIT_CONFIG_KEY_2", "credential.helper"); env.put("GIT_CONFIG_VALUE_2", "");
    env.put("GIT_CONFIG_KEY_3", "core.hooksPath"); env.put("GIT_CONFIG_VALUE_3", "/dev/null");
    Path root = null;
    try {
      Files.createDirectories(settings.workspace);
      root = Files.createTempDirectory(settings.workspace, "checkout-");
      git(root, env, "init", ".");
      git(root, env, "remote", "add", "origin", url.toString());
      git(root, env, "sparse-checkout", "set", "--no-cone", "/ckp/");
      git(root, env, "fetch", "--depth=1", "--filter=blob:none", "--no-tags", "origin", revision);
      String commit = git(root, env, "rev-parse", "--verify", "FETCH_HEAD^{commit}").strip();
      if (!commit.matches("[a-f0-9]{40,64}")) throw Problem.invalid("revision", "Git did not return a commit identity");
      git(root, env, "checkout", "--detach", commit);
      Map<String,String> files = new TreeMap<>(); long size = 0;
      try (var paths = Files.walk(root.resolve("ckp"))) {
        for (Path path : paths.toList()) {
          if (Files.isSymbolicLink(path)) throw Problem.invalid("source", "Symlinks are not supported in CKP charts");
          if (!Files.isRegularFile(path)) continue;
          size += Files.size(path);
          if (size > 16 * 1024 * 1024 || files.size() >= 2000) throw Problem.invalid("source", "CKP snapshot exceeds 16 MiB or 2000 files");
          files.put(root.relativize(path).toString().replace('\\','/'), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
      }
      return new Checkout(commit, Map.copyOf(files));
    } catch (java.io.IOException e) { throw Problem.invalid("source", "Unable to read the checked-out CKP directory"); }
    finally { env.clear(); if (root != null) delete(root); }
  }
  private String git(Path root, Map<String,String> env, String... args) {
    var list = new ArrayList<String>(); list.add("git"); list.addAll(List.of(args));
    return commands.require(list, root, env, Duration.ofSeconds(settings.timeoutSeconds), "Sparse Git checkout");
  }
  public static void delete(Path root) {
    try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    catch (java.io.IOException ignored) { /* Workspace is bounded per preparation; operator may remove abandoned folders. */ }
  }
  public static Path materialize(Path root, Map<String,String> files) throws java.io.IOException {
    Files.createDirectories(root);
    for (var entry : files.entrySet()) {
      ConnectionConfig.safePath(entry.getKey());
      if (!entry.getKey().startsWith("ckp/")) throw Problem.invalid("source", "Only CKP files may be materialized");
      Path path = root.resolve(entry.getKey()).normalize();
      if (!path.startsWith(root)) throw Problem.invalid("source", "Invalid snapshot path");
      Files.createDirectories(path.getParent()); Files.write(path, Base64.getDecoder().decode(entry.getValue()));
    }
    return root;
  }
}
