package com.example.perforchestrator.infrastructure.registry;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read-only Bitbucket Server/Data Center discovery. Git checkout is a separate integration. */
@Component
public class BitbucketReferences {

  private final Catalog catalog;
  private final ConnectionConfig config;
  private final CredentialResolver credentials;
  private final ReadOnlyHttp http;
  private final ConnectionSessions sessions;

  public BitbucketReferences(
    final Catalog catalog,
    final ConnectionConfig config,
    final CredentialResolver credentials,
    final ReadOnlyHttp http
  ) {
    this(catalog, config, credentials, http, new ConnectionSessions(config, credentials));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BitbucketReferences(
    final Catalog catalog,
    final ConnectionConfig config,
    final CredentialResolver credentials,
    final ReadOnlyHttp http,
    final ConnectionSessions sessions
  ) {
    this.sessions = sessions;
    this.catalog = catalog;
    this.config = config;
    this.credentials = credentials;
    this.http = http;
  }

  public record Reference(String id, String displayName, String latestCommit) {}

  public record Page(List<Reference> values, Integer nextStart) {}

  public Page list(
    final String serviceId,
    final String kind,
    final int start,
    final RequestAuthentication authentication
  ) {
    if (!Set.of("branches", "tags").contains(kind) || start < 0) throw Problem.invalid(
      "query",
      "Choose branches or tags and a non-negative page offset"
    );
    final var service = catalog.data().services().get(serviceId);
    if (service == null || service.sourceProject() == null) throw Problem.invalid(
      "service",
      "Configure the service's Bitbucket project first"
    );
    final var source = service.sourceProject();
    final var connection = config.data().bitbucket().get(source.connectionRef());
    if (connection == null) throw Problem.invalid("connection", "Unknown Bitbucket connection");
    final var uri = URI.create(
      connection.apiBaseUrl().replaceAll("/$", "") +
        "/1.0/projects/" +
        source.projectKey() +
        "/repos/" +
        source.repository() +
        "/" +
        kind +
        "?limit=50&start=" +
        start
    );
    final var response = http.get(
      uri,
      sessions.authorization("bitbucket", source.connectionRef(), authentication),
      "application/json"
    );
    if (response.status() == 401) sessions.rejected("bitbucket", source.connectionRef());
    ReadOnlyHttp.requireSuccess(response, "bitbucket");
    try {
      final var body = Json.MAPPER.readTree(response.body());
      final var values = body.path("values");
      if (
        !values.isArray() || values.size() > 50 || !body.path("isLastPage").isBoolean()
      ) throw new IllegalArgumentException();
      final List<Reference> refs = new ArrayList<>();
      for (final var item : values) {
        if (
          !item.path("id").isTextual() || !item.path("displayId").isTextual()
        ) throw new IllegalArgumentException();
        refs.add(
          new Reference(
            item.path("id").asText(),
            item.path("displayId").asText(),
            item.path("latestCommit").asText("")
          )
        );
      }
      final Integer next = body.path("isLastPage").asBoolean()
        ? null
        : body.path("nextPageStart").asInt(-1);
      if (next != null && next <= start) throw new IllegalArgumentException();
      return new Page(List.copyOf(refs), next);
    } catch (final Exception e) {
      throw new Problem(
        502,
        "BITBUCKET_SCHEMA",
        "bitbucket",
        "Unexpected Bitbucket references response"
      );
    }
  }
}
