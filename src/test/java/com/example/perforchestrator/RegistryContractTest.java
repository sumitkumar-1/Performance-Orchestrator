package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistryContractTest {

  ConnectionConfig config() {
    return new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(
          "jfrog",
          new ConnectionConfig.Artifactory(
            "https://registry.example.invalid/artifactory/api/docker",
            "read"
          )
        ),
        Map.of(
          "ss",
          new ConnectionConfig.SecretServer(
            "https://secrets.example.invalid/api/v1",
            "DELINEA_TOKEN"
          )
        ),
        Map.of(
          "read",
          new ConnectionConfig.Credential("delinea", null, null, "ss", "12", "username", "password")
        ),
        Map.of(
          "dev",
          new ConnectionConfig.Source(
            "Dev",
            "jfrog",
            "docker-dev",
            Map.of("auth-service", "{username}/auth-service"),
            true,
            "registry.example.invalid/{repositoryKey}/{image}"
          )
        )
      )
    );
  }

  ReadOnlyHttp.Response response(final int status, final String body) {
    return new ReadOnlyHttp.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * <b>Scenario:</b> Delinea Resolves Configured Fields Without Returning Secrets
   * <pre>
   * GIVEN ... Delinea credentials with configured secret field slugs
   * WHEN ... the secret is resolved
   * THEN ... the configured fields supply credentials without exposing secret values in public output
   * </pre>
   */
  @Test
  @DisplayName("Delinea Resolves Configured Fields Without Returning Secrets")
  void delineaResolvesConfiguredFieldsWithoutReturningSecrets() {
    final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    when(http.get(any(), any(), any())).thenReturn(
      response(
        200,
        "{\"items\":[{\"slug\":\"username\",\"itemValue\":\"reader\"},{\"slug\":\"password\",\"itemValue\":\"sensitive\"}]}"
      )
    );
    final var credentials = new ConfiguredCredentials(
      config(),
      http,
      (final var key) -> "server-token"
    );
    final var result = credentials.resolve("read");
    assertThat(result.username()).isEqualTo("reader");
    assertThat(result.password()).isEqualTo("sensitive");
    assertThat(result.toString()).isEqualTo("[REDACTED]");
    verify(http).get(
      URI.create("https://secrets.example.invalid/api/v1/secrets/12"),
      "Bearer server-token",
      "application/json"
    );
    assertThat(Json.write(config().publicView())).doesNotContain("server-token", "sensitive");
  }

  /**
   * <b>Scenario:</b> Paginates Only Registered Source And Resolves Digest
   * <pre>
   * GIVEN ... a registered registry source with paginated tags
   * WHEN ... versions are listed and an image is resolved
   * THEN ... pagination stays within the registered source and the digest is resolved
   * </pre>
   */
  @Test
  @DisplayName("Paginates Only Registered Source And Resolves Digest")
  void paginatesOnlyRegisteredSourceAndResolvesDigest() {
    final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    final CredentialResolver credentials = (final var ref) ->
      new CredentialResolver.Secret(null, "registry-token", true);
    final var images = new ArtifactoryImages(config(), http, credentials);
    when(http.get(any(), any(), any())).thenReturn(
      response(200, "{\"tags\":[\"build-1\",\"build-2\"]}")
    );
    final var page = images.discover("auth-service", "dev", "alice", "", 2);
    assertThat(page.nextCursor()).isEqualTo("build-2");
    assertThat(page.repository()).contains("alice/auth-service");
    verify(http).get(
      eq(
        URI.create(
          "https://registry.example.invalid/artifactory/api/docker/docker-dev/v2/alice/auth-service/tags/list?n=2"
        )
      ),
      eq("Bearer registry-token"),
      eq("application/json")
    );
    final String manifest = "{\"schemaVersion\":2}";
    when(http.get(any(), any(), any())).thenReturn(
      new ReadOnlyHttp.Response(
        200,
        Map.of("Docker-Content-Digest", List.of("sha256:" + Json.hash(manifest))),
        manifest.getBytes(StandardCharsets.UTF_8)
      )
    );
    assertThat(images.resolve("auth-service", "dev", "alice", "build-1").digest()).isEqualTo(
      "sha256:" + Json.hash(manifest)
    );
  }

  /**
   * <b>Scenario:</b> Denial And Malicious Inputs Are Redacted
   * <pre>
   * GIVEN ... registry denial responses and malicious discovery inputs
   * WHEN ... discovery is attempted
   * THEN ... errors reject unsafe input and redact sensitive response content
   * </pre>
   */
  @Test
  @DisplayName("Denial And Malicious Inputs Are Redacted")
  void denialAndMaliciousInputsAreRedacted() {
    final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    final var images = new ArtifactoryImages(config(), http, (final var ref) ->
      new CredentialResolver.Secret(null, "secret-token", true)
    );
    when(http.get(any(), any(), any())).thenReturn(response(403, "secret-password backend detail"));
    assertThatThrownBy(() -> images.discover("auth-service", "dev", "alice", "", 50))
      .hasMessageContaining("denied")
      .hasMessageNotContaining("secret-password");
    assertThatThrownBy(() ->
      images.discover("auth-service", "dev", "../admin", "", 50)
    ).hasMessageContaining("username");
    assertThatThrownBy(() ->
      images.discover("unknown", "dev", "alice", "", 50)
    ).hasMessageContaining("not mapped");
  }

  /**
   * <b>Scenario:</b> Missing Secret Fields Do Not Leak Payload
   * <pre>
   * GIVEN ... a vault response missing configured credential fields
   * WHEN ... credentials are resolved
   * THEN ... resolution fails without exposing the secret payload
   * </pre>
   */
  @Test
  @DisplayName("Missing Secret Fields Do Not Leak Payload")
  void missingSecretFieldsDoNotLeakPayload() {
    final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    when(http.get(any(), any(), any())).thenReturn(
      response(200, "{\"password\":\"must-not-leak\"}")
    );
    final var credentials = new ConfiguredCredentials(config(), http, (final var k) -> "token");
    assertThatThrownBy(() -> credentials.resolve("read"))
      .hasMessageContaining("configured")
      .hasMessageNotContaining("must-not-leak");
  }

  /**
   * <b>Scenario:</b> Rejects Plaintext And Embedded Credentials
   * <pre>
   * GIVEN ... connection URLs using plaintext HTTP or embedded credentials
   * WHEN ... connection configuration is validated
   * THEN ... unsafe URLs are rejected
   * </pre>
   */
  @Test
  @DisplayName("Rejects Plaintext And Embedded Credentials")
  void rejectsPlaintextAndEmbeddedCredentials() {
    assertThatThrownBy(() -> ConnectionConfig.base("http://host/api")).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> ConnectionConfig.base("https://user:password@host/api")).isInstanceOf(
      IllegalArgumentException.class
    );
  }
}
