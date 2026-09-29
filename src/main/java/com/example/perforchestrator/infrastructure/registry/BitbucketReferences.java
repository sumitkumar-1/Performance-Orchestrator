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

  public BitbucketReferences(Catalog catalog, ConnectionConfig config, CredentialResolver credentials, ReadOnlyHttp http) {
    this(catalog, config, credentials, http, new ConnectionSessions(config, credentials));
  }
  @org.springframework.beans.factory.annotation.Autowired
  public BitbucketReferences(Catalog catalog, ConnectionConfig config, CredentialResolver credentials, ReadOnlyHttp http, ConnectionSessions sessions) {
    this.sessions = sessions;
    this.catalog = catalog; this.config = config; this.credentials = credentials; this.http = http;
  }

  public record Reference(String id, String displayName, String latestCommit) {}
  public record Page(List<Reference> values, Integer nextStart) {}

  public Page list(String serviceId, String kind, int start, RequestAuthentication authentication) {
    if (!Set.of("branches", "tags").contains(kind) || start < 0)
      throw Problem.invalid("query", "Choose branches or tags and a non-negative page offset");
    var service = catalog.data().services().get(serviceId);
    if (service == null || service.sourceProject() == null) throw Problem.invalid("service", "Configure the service's Bitbucket project first");
    var source = service.sourceProject();
    var connection = config.data().bitbucket().get(source.connectionRef());
    if (connection == null) throw Problem.invalid("connection", "Unknown Bitbucket connection");
    var uri = URI.create(connection.apiBaseUrl().replaceAll("/$", "") + "/1.0/projects/"
        + source.projectKey() + "/repos/" + source.repository() + "/" + kind + "?limit=50&start=" + start);
    var response = http.get(uri, sessions.authorization("bitbucket", source.connectionRef(), authentication), "application/json");
    if (response.status() == 401) sessions.rejected("bitbucket", source.connectionRef());
    ReadOnlyHttp.requireSuccess(response, "bitbucket");
    try {
      var body = Json.MAPPER.readTree(response.body());
      var values = body.path("values");
      if (!values.isArray() || values.size() > 50 || !body.path("isLastPage").isBoolean()) throw new IllegalArgumentException();
      List<Reference> refs = new ArrayList<>();
      for (var item : values) {
        if (!item.path("id").isTextual() || !item.path("displayId").isTextual()) throw new IllegalArgumentException();
        refs.add(new Reference(item.path("id").asText(), item.path("displayId").asText(), item.path("latestCommit").asText("")));
      }
      Integer next = body.path("isLastPage").asBoolean() ? null : body.path("nextPageStart").asInt(-1);
      if (next != null && next <= start) throw new IllegalArgumentException();
      return new Page(List.copyOf(refs), next);
    } catch (Exception e) { throw new Problem(502, "BITBUCKET_SCHEMA", "bitbucket", "Unexpected Bitbucket references response"); }
  }
}
