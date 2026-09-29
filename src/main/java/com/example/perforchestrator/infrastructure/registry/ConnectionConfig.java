package com.example.perforchestrator.infrastructure.registry;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Administrator-owned connections. Requests select registered destinations; credentials are
 * accepted only by portal authentication.
 */
@Component
public class ConnectionConfig {
  public record Artifactory(String apiBaseUrl, String credentialRef, String authMode) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding public Artifactory {}
    public Artifactory(String apiBaseUrl, String credentialRef) { this(apiBaseUrl, credentialRef, "secret-server"); }
    public String mode() { return authMode == null ? "secret-server" : authMode; }
  }
  public record Bitbucket(String apiBaseUrl, String credentialRef, String authMode) {
    public String mode() { return authMode == null ? "secret-server" : authMode; }
  }

  public record SecretServer(
      String apiBaseUrl,
      String bearerTokenEnvironmentVariable,
      String authMode,
      String tokenUrl,
      String bearerTokenFile,
      String credentialRef) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SecretServer {}

    public SecretServer(String apiBaseUrl, String bearerTokenEnvironmentVariable, String authMode, String tokenUrl, String bearerTokenFile) {
      this(apiBaseUrl, bearerTokenEnvironmentVariable, authMode, tokenUrl, bearerTokenFile, null);
    }
    public SecretServer(String apiBaseUrl, String bearerTokenEnvironmentVariable) {
      this(apiBaseUrl, bearerTokenEnvironmentVariable, "environment", null, null);
    }

    public String mode() {
      return authMode == null ? "environment" : authMode;
    }
  }

  public record Credential(
      String provider,
      String usernameEnvironmentVariable,
      String passwordEnvironmentVariable,
      String secretServerRef,
      String secretId,
      String usernameFieldSlug,
      String passwordFieldSlug,
      String tokenFieldSlug,
      String tokenEnvironmentVariable) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding public Credential {}
    public Credential(String provider, String usernameEnvironmentVariable, String passwordEnvironmentVariable,
        String secretServerRef, String secretId, String usernameFieldSlug, String passwordFieldSlug) {
      this(provider, usernameEnvironmentVariable, passwordEnvironmentVariable, secretServerRef, secretId, usernameFieldSlug, passwordFieldSlug, null, null);
    }
    public boolean token() { return tokenFieldSlug != null || tokenEnvironmentVariable != null; }
  }

  public record Source(
      String displayName,
      String connectionRef,
      String repositoryKey,
      Map<String, String> imagePaths,
      boolean usernameRequired,
      String pullRepositoryTemplate) {
    public Source {
      imagePaths = ImmutableConfiguration.map(imagePaths);
    }
  }

  public record Data(
      Map<String, Artifactory> artifactory,
      Map<String, SecretServer> secretServers,
      Map<String, Credential> credentials,
      Map<String, Source> imageSources,
      Map<String, Bitbucket> bitbucket) {
    public Data(Map<String, Artifactory> artifactory, Map<String, SecretServer> secretServers,
        Map<String, Credential> credentials, Map<String, Source> imageSources) {
      this(artifactory, secretServers, credentials, imageSources, Map.of());
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Data {
      bitbucket = ImmutableConfiguration.map(bitbucket == null ? Map.of() : bitbucket);
      artifactory = ImmutableConfiguration.map(artifactory);
      secretServers = ImmutableConfiguration.map(secretServers);
      credentials = ImmutableConfiguration.map(credentials);
      imageSources = ImmutableConfiguration.map(imageSources);
    }
  }

  private record Snapshot(Data data, long generation) {}

  private volatile Snapshot snapshot;

  @org.springframework.beans.factory.annotation.Autowired
  public ConnectionConfig(
      @Value("${orchestrator.connections:}") String file,
      org.springframework.core.env.Environment environment) throws IOException {
    this(file.isBlank()
        ? defaults(environment)
        : readFile(file));
  }

  public ConnectionConfig(String file) throws IOException {
    this(
        file.isBlank()
            ? new Data(Map.of(), Map.of(), Map.of(), Map.of())
            : readFile(file));
  }

  private static Data defaults(org.springframework.core.env.Environment environment) {
    var data = org.springframework.boot.context.properties.bind.Binder.get(environment)
        .bind("orchestrator.connection-defaults", Data.class)
        .orElseGet(() -> new Data(Map.of(), Map.of(), Map.of(), Map.of()));
    // Spring omits empty YAML maps; external JSON/YAML still requires all four maps.
    return new Data(data.artifactory() == null ? Map.of() : data.artifactory(),
        data.secretServers() == null ? Map.of() : data.secretServers(),
        data.credentials() == null ? Map.of() : data.credentials(),
        data.imageSources() == null ? Map.of() : data.imageSources(), data.bitbucket());
  }

  private static Data readFile(String file) throws IOException {
    return Json.read(Json.write(YamlValues.parse(ConfigurationResources.read(file))), Data.class);
  }

  public ConnectionConfig(Data data) {
    this.snapshot = new Snapshot(data, 0);
    if (data.artifactory() == null
        || data.secretServers() == null
        || data.credentials() == null
        || data.imageSources() == null)
      throw new IllegalArgumentException("All connection maps are required");
    data.artifactory()
        .values()
        .forEach(
            c -> {
              base(c.apiBaseUrl());
              validateAuth(data, c.mode(), c.credentialRef());
            });
    data.bitbucket().values().forEach(c -> { base(c.apiBaseUrl()); validateAuth(data, c.mode(), c.credentialRef()); });
    data.secretServers()
        .values()
        .forEach(
            c -> {
              base(c.apiBaseUrl());
              if (!"secret-server".equals(c.mode()) && c.credentialRef() != null)
                throw new IllegalArgumentException("Credential reference is only used by Secret Server authentication");
              switch (c.mode()) {
                case "token" -> {
                  if (c.tokenUrl() != null || c.bearerTokenFile() != null || c.bearerTokenEnvironmentVariable() != null)
                    throw new IllegalArgumentException("Interactive token mode accepts no stored token configuration");
                }
                case "secret-server" -> {
                  if (!data.credentials().containsKey(c.credentialRef()) || c.bearerTokenFile() != null || c.bearerTokenEnvironmentVariable() != null)
                    throw new IllegalArgumentException("Invalid bootstrap credential reference");
                  if (!data.credentials().get(c.credentialRef()).token()) base(c.tokenUrl());
                  else if (c.tokenUrl() != null) throw new IllegalArgumentException("A token credential does not use an OAuth endpoint");
                }
                case "environment" -> {
                  envName(c.bearerTokenEnvironmentVariable());
                  if (c.tokenUrl() != null || c.bearerTokenFile() != null)
                    throw new IllegalArgumentException("Ambiguous Secret Server authentication");
                }
                case "portal" -> {
                  base(c.tokenUrl());
                  if (c.bearerTokenEnvironmentVariable() != null || c.bearerTokenFile() != null)
                    throw new IllegalArgumentException(
                        "Portal authentication cannot fall back to a global token");
                }
                case "file" -> {
                  if (c.bearerTokenFile() == null
                      || !Path.of(c.bearerTokenFile()).isAbsolute()
                      || c.bearerTokenEnvironmentVariable() != null
                      || c.tokenUrl() != null)
                    throw new IllegalArgumentException(
                        "File authentication requires an absolute token file only");
                }
                default ->
                    throw new IllegalArgumentException(
                        "Unsupported Secret Server authentication mode");
              }
            });
    data.credentials()
        .values()
        .forEach(
            c -> {
              if ("environment".equals(c.provider())) {
                if (c.token()) {
                  envName(c.tokenEnvironmentVariable());
                  if (c.usernameEnvironmentVariable() != null || c.passwordEnvironmentVariable() != null || c.tokenFieldSlug() != null)
                    throw new IllegalArgumentException("Ambiguous token credential");
                } else { envName(c.usernameEnvironmentVariable()); envName(c.passwordEnvironmentVariable()); }
              } else if ("delinea".equals(c.provider())) {
                if (!data.secretServers().containsKey(c.secretServerRef())
                    || c.secretId() == null
                    || !c.secretId().matches("[0-9]{1,12}")
                    || (c.token() ? c.tokenFieldSlug() == null || c.tokenFieldSlug().isBlank()
                        || c.usernameFieldSlug() != null || c.passwordFieldSlug() != null || c.tokenEnvironmentVariable() != null
                        : c.usernameFieldSlug() == null || c.passwordFieldSlug() == null))
                  throw new IllegalArgumentException("Invalid Delinea credential mapping");
              } else throw new IllegalArgumentException("Unsupported credential provider");
            });
    data.secretServers().keySet().forEach(id -> validateVaultChain(data, id, new HashSet<>()));
    data.imageSources()
        .forEach(
            (id, s) -> {
              if (!id.matches("[a-zA-Z0-9_-]{1,80}")
                  || !data.artifactory().containsKey(s.connectionRef())
                  || !s.repositoryKey().matches("[a-zA-Z0-9_.-]{1,100}")
                  || s.imagePaths() == null)
                throw new IllegalArgumentException("Invalid image source mapping");
              s.imagePaths()
                  .values()
                  .forEach(path -> safePath(path.replace("{username}", "fixture-user")));
              if (s.pullRepositoryTemplate() == null || s.pullRepositoryTemplate().contains("://"))
                throw new IllegalArgumentException(
                    "Configure a Docker pull repository template, not a URL");
            });
  }

  private static void validateAuth(Data data, String mode, String reference) {
    if (!Set.of("ad", "token", "secret-server").contains(mode)) throw new IllegalArgumentException("Unsupported authentication mode");
    if ("secret-server".equals(mode)) {
      if (!data.credentials().containsKey(reference)) throw new IllegalArgumentException("Unknown credential reference");
    } else if (reference != null) throw new IllegalArgumentException("Interactive authentication cannot use a stored credential reference");
  }

  private static void validateVaultChain(Data data, String id, Set<String> visited) {
    if (!visited.add(id)) throw new IllegalArgumentException("Secret Server authentication cannot depend on itself or form a cycle");
    var server = data.secretServers().get(id);
    if ("secret-server".equals(server.mode())) {
      var credential = data.credentials().get(server.credentialRef());
      if ("delinea".equals(credential.provider())) validateVaultChain(data, credential.secretServerRef(), visited);
    }
  }

  public void replace(Data next) {
    new ConnectionConfig(next); // Validate the complete graph before publication.
    installValidated(next);
  }

  public synchronized void installValidated(Data next) {
    snapshot = new Snapshot(next, snapshot.generation() + 1);
  }

  public long generation() {
    return snapshot.generation();
  }

  public Data data() {
    return snapshot.data();
  }

  public static URI base(String value) {
    URI uri = URI.create(value);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath().contains(".."))
      throw new IllegalArgumentException(
          "Connections require fixed HTTPS URLs without embedded credentials");
    return uri;
  }

  private static void envName(String name) {
    if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,100}"))
      throw new IllegalArgumentException("Invalid environment secret reference");
  }

  public static String safePath(String value) {
    if (value == null
        || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_./-]{0,250}")
        || Arrays.stream(value.split("/", -1))
            .anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
      throw Problem.invalid("repository", "Invalid registered repository path");
    return value;
  }

  public Map<String, Object> publicView() {
    Map<String, Object> connections = new TreeMap<>();
    data()
        .artifactory()
        .forEach(
            (id, c) ->
                connections.put(
                    id,
                    Map.of(
                        "apiBaseUrl",
                        c.apiBaseUrl(),
                        "credentialRef",
                        Objects.toString(c.credentialRef(), ""),
                        "authMode", c.mode(),
                        "capability",
                        "read-only Artifactory Docker V2")));
    return Map.of(
        "artifactory",
        connections,
        "bitbucket", data().bitbucket(),
        "secretServers",
        data().secretServers().keySet(),
        "credentialReferences",
        data().credentials().keySet(),
        "imageSources",
        data().imageSources());
  }
}
