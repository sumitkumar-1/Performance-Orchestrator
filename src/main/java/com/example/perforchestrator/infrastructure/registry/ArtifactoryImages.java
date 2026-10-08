package com.example.perforchestrator.infrastructure.registry;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.secrets.ConnectionSessions;
import com.example.perforchestrator.infrastructure.secrets.CredentialResolver;
import com.example.perforchestrator.infrastructure.secrets.RequestAuthentication;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ArtifactoryImages {

  public record Page(
    List<String> versions,
    String nextCursor,
    String discoveredAt,
    String repository
  ) {}

  private final ConnectionConfig config;
  private final ReadOnlyHttp http;
  private final CredentialResolver credentials;
  private final Catalog catalog;
  private final ConnectionSessions sessions;

  public ArtifactoryImages(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final CredentialResolver credentials
  ) {
    this(config, http, credentials, null);
  }

  public ArtifactoryImages(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final CredentialResolver credentials,
    final Catalog catalog
  ) {
    this(config, http, credentials, catalog, new ConnectionSessions(config, credentials));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ArtifactoryImages(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final CredentialResolver credentials,
    final Catalog catalog,
    final ConnectionSessions sessions
  ) {
    this.sessions = sessions;
    this.catalog = catalog;
    this.config = config;
    this.http = http;
    this.credentials = credentials;
  }

  private record Target(String api, String repository, String authorization) {
    @Override
    public String toString() {
      return "[REDACTED target]";
    }
  }

  public Map<String, ConnectionConfig.Source> sources() {
    final var sources = new TreeMap<>(config.data().imageSources());
    if (catalog != null) catalog
      .data()
      .services()
      .forEach((final var id, final var service) -> {
        final var image = service.containerImage();
        if (image != null) {
          final var connection = config.data().artifactory().get(image.connectionRef());
          if (connection != null) sources.put(
            "service:" + id,
            new ConnectionConfig.Source(
              id,
              image.connectionRef(),
              "docker-" + image.repoStage(),
              Map.of(id, image.teamId() + "/" + image.imageName()),
              false,
              URI.create(connection.apiBaseUrl()).getAuthority() + "/{repositoryKey}/{image}"
            )
          );
        }
      });
    return Collections.unmodifiableMap(sources);
  }

  private Target target(
    final String service,
    final String source,
    final String username,
    final RequestAuthentication input
  ) {
    final var mapping = sources().get(source);
    if (mapping == null) throw Problem.invalid("source", "Unknown configured Artifactory source");
    if (
      mapping.usernameRequired() &&
      (username == null || !username.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}"))
    ) throw Problem.invalid("username", "Supply a valid artifact-owner username");
    if (
      !mapping.usernameRequired() && username != null && !username.isBlank()
    ) throw Problem.invalid("username", "This source does not accept a username");
    final String registered = mapping.imagePaths().get(service);
    if (registered == null) throw Problem.invalid(
      "service",
      "Service is not mapped in this Artifactory source"
    );
    final String image = ConnectionConfig.safePath(
      registered.replace("{username}", username == null ? "" : username)
    );
    final var connection = config.data().artifactory().get(mapping.connectionRef());
    final String authorization = sessions.authorization(
      "artifactory",
      mapping.connectionRef(),
      input
    );
    final String repository = mapping
      .pullRepositoryTemplate()
      .replace("{image}", image)
      .replace("{username}", username == null ? "" : username)
      .replace("{repositoryKey}", mapping.repositoryKey());
    if (
      repository.contains("{") || repository.contains("@") || repository.contains("..")
    ) throw Problem.invalid("repository", "Invalid pull repository template");
    return new Target(
      connection.apiBaseUrl().replaceAll("/$", "") + "/" + mapping.repositoryKey() + "/v2/" + image,
      repository,
      authorization
    );
  }

  public Page discover(
    final String service,
    final String source,
    final String username,
    final String cursor,
    final int limit
  ) {
    return discover(service, source, username, cursor, limit, null);
  }

  public Page discover(
    final String service,
    final String source,
    final String username,
    final String cursor,
    final int limit,
    final RequestAuthentication input
  ) {
    if (
      limit < 1 ||
      limit > 100 ||
      (cursor != null && !cursor.isBlank() && !cursor.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}"))
    ) throw Problem.invalid("cursor", "Invalid registry pagination");
    final Target target = target(service, source, username, input);
    final String query =
      "?n=" +
      limit +
      (cursor == null || cursor.isBlank()
        ? ""
        : "&last=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    final var response = http.get(
      URI.create(target.api() + "/tags/list" + query),
      target.authorization(),
      "application/json"
    );
    if (response.status() == 401) sessions.rejected(
      "artifactory",
      sources().get(source).connectionRef()
    );
    ReadOnlyHttp.requireSuccess(response, "imageSource");
    try {
      final var tags = Json.MAPPER.readTree(response.body()).get("tags");
      final List<String> versions = new ArrayList<>();
      if (tags != null && !tags.isNull()) {
        if (!tags.isArray()) throw new IllegalArgumentException();
        for (final var tag : tags) {
          if (
            !tag.isTextual() || !tag.asText().matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}")
          ) throw new IllegalArgumentException();
          versions.add(tag.asText());
        }
      }
      if (versions.size() > limit) throw new IllegalArgumentException();
      // Never follow server-supplied URLs; a cursor can only address the same registered
      // repository.
      final String next = versions.size() == limit ? versions.getLast() : "";
      return new Page(
        List.copyOf(versions),
        next,
        java.time.Instant.now().toString(),
        target.repository()
      );
    } catch (final Exception e) {
      throw new Problem(
        502,
        "REGISTRY_SCHEMA",
        "imageSource",
        "Unexpected or oversized Docker tags response"
      );
    }
  }

  public Image resolve(
    final String service,
    final String source,
    final String username,
    final String tag
  ) {
    return resolve(service, source, username, tag, null);
  }

  public Image resolve(
    final String service,
    final String source,
    final String username,
    final String tag,
    final RequestAuthentication input
  ) {
    if (
      tag == null || !tag.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}") || tag.equals("latest")
    ) throw Problem.invalid("version", "Select a concrete image tag; latest is not supported");
    final Target target = target(service, source, username, input);
    final var response = http.get(
      URI.create(target.api() + "/manifests/" + tag),
      target.authorization(),
      "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json," +
        " application/vnd.docker.distribution.manifest.list.v2+json," +
        " application/vnd.docker.distribution.manifest.v2+json"
    );
    if (response.status() == 401) sessions.rejected(
      "artifactory",
      sources().get(source).connectionRef()
    );
    ReadOnlyHttp.requireSuccess(response, "version");
    final String digest = response
      .header("Docker-Content-Digest")
      .orElseThrow(() ->
        new Problem(
          502,
          "DIGEST_MISSING",
          "version",
          "Registry did not provide an immutable digest"
        )
      );
    try {
      final String computed =
        "sha256:" +
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
      if (!digest.equals(computed)) throw new IllegalArgumentException();
      final var manifest = Json.MAPPER.readTree(response.body());
      if (manifest.path("schemaVersion").asInt() != 2) throw new IllegalArgumentException();
    } catch (final Exception e) {
      throw new Problem(
        502,
        "DIGEST_INVALID",
        "version",
        "Manifest digest or schema verification failed"
      );
    }
    return new Image(source, username == null ? "" : username, target.repository(), tag, digest);
  }
}
