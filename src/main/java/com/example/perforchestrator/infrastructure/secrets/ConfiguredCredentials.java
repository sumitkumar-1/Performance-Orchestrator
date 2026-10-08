package com.example.perforchestrator.infrastructure.secrets;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.*;
import java.net.URI;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredCredentials implements CredentialResolver {

  private final ConnectionConfig config;
  private final ReadOnlyHttp http;
  private final Function<String, String> environment;
  private final SecretServerTokens tokens;

  @Autowired
  public ConfiguredCredentials(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final SecretServerTokens tokens
  ) {
    this.config = config;
    this.http = http;
    this.environment = System::getenv;
    this.tokens = tokens;
  }

  public ConfiguredCredentials(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final Function<String, String> environment
  ) {
    this.config = config;
    this.http = http;
    this.environment = environment;
    this.tokens = new SecretServerTokens(config, http, environment, java.time.Clock.systemUTC());
  }

  public Secret resolve(final String reference) {
    final var credential = config.data().credentials().get(reference);
    if (credential == null) throw Problem.invalid("credentialRef", "Unknown credential reference");
    if ("environment".equals(credential.provider())) return credential.token()
      ? new Secret(null, env(credential.tokenEnvironmentVariable()), true)
      : new Secret(
          env(credential.usernameEnvironmentVariable()),
          env(credential.passwordEnvironmentVariable())
        );
    final var server = config.data().secretServers().get(credential.secretServerRef());
    final URI uri = URI.create(
      server.apiBaseUrl().replaceAll("/$", "") + "/secrets/" + credential.secretId()
    );
    final var response = http.get(
      uri,
      tokens.authorization(credential.secretServerRef(), this::resolve),
      "application/json"
    );
    if (response.status() == 401) tokens.rejected(credential.secretServerRef());
    ReadOnlyHttp.requireSuccess(response, "secretServer");
    try {
      final var body = Json.MAPPER.readTree(response.body());
      String username = null,
        password = null;
      for (final var item : body.path("items")) {
        if (item.path("slug").asText().equals(credential.usernameFieldSlug())) username = item
          .path("itemValue")
          .asText(null);
        if (
          item
            .path("slug")
            .asText()
            .equals(
              credential.token() ? credential.tokenFieldSlug() : credential.passwordFieldSlug()
            )
        ) password = item.path("itemValue").asText(null);
      }
      if (
        (!credential.token() && (username == null || username.isBlank())) ||
        password == null ||
        password.isBlank()
      ) throw new IllegalArgumentException();
      return new Secret(username, password, credential.token());
    } catch (final Exception e) {
      throw new Problem(
        502,
        "SECRET_SCHEMA",
        "credentialRef",
        "Secret response lacks the configured credential fields; verify API version and" +
          " field slugs"
      );
    }
  }

  private String env(final String name) {
    final String value = environment.apply(name);
    if (value == null || value.isBlank()) throw new Problem(
      503,
      "CREDENTIAL_UNAVAILABLE",
      "credentialRef",
      "Required server-side credential environment variable is missing"
    );
    return value;
  }
}
