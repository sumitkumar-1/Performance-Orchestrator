package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class RegistryContractTest {
  ConnectionConfig config() {
    return new ConnectionConfig(
        new ConnectionConfig.Data(
            Map.of(
                "jfrog",
                new ConnectionConfig.Artifactory(
                    "https://registry.example.invalid/artifactory/api/docker", "read")),
            Map.of(
                "ss",
                new ConnectionConfig.SecretServer(
                    "https://secrets.example.invalid/api/v1", "DELINEA_TOKEN")),
            Map.of(
                "read",
                new ConnectionConfig.Credential(
                    "delinea", null, null, "ss", "12", "username", "password")),
            Map.of(
                "dev",
                new ConnectionConfig.Source(
                    "Dev",
                    "jfrog",
                    "docker-dev",
                    Map.of("auth-service", "{username}/auth-service"),
                    true,
                    "registry.example.invalid/{repositoryKey}/{image}"))));
  }

  ReadOnlyHttp.Response response(int status, String body) {
    return new ReadOnlyHttp.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void delineaResolvesConfiguredFieldsWithoutReturningSecrets() {
    ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    when(http.get(any(), any(), any()))
        .thenReturn(
            response(
                200,
                "{\"items\":[{\"slug\":\"username\",\"itemValue\":\"reader\"},{\"slug\":\"password\",\"itemValue\":\"sensitive\"}]}"));
    var credentials = new ConfiguredCredentials(config(), http, key -> "server-token");
    var result = credentials.resolve("read");
    assertThat(result.username()).isEqualTo("reader");
    assertThat(result.password()).isEqualTo("sensitive");
    assertThat(result.toString()).isEqualTo("[REDACTED]");
    verify(http)
        .get(
            URI.create("https://secrets.example.invalid/api/v1/secrets/12"),
            "Bearer server-token",
            "application/json");
    assertThat(Json.write(config().publicView())).doesNotContain("server-token", "sensitive");
  }

  @Test
  void paginatesOnlyRegisteredSourceAndResolvesDigest() {
    ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    CredentialResolver credentials = ref -> new CredentialResolver.Secret("reader", "password");
    var images = new ArtifactoryImages(config(), http, credentials);
    when(http.get(any(), any(), any()))
        .thenReturn(response(200, "{\"tags\":[\"build-1\",\"build-2\"]}"));
    var page = images.discover("auth-service", "dev", "alice", "", 2);
    assertThat(page.nextCursor()).isEqualTo("build-2");
    assertThat(page.repository()).contains("alice/auth-service");
    verify(http)
        .get(
            eq(
                URI.create(
                    "https://registry.example.invalid/artifactory/api/docker/docker-dev/v2/alice/auth-service/tags/list?n=2")),
            startsWith("Basic "),
            eq("application/json"));
    String manifest = "{\"schemaVersion\":2}";
    when(http.get(any(), any(), any()))
        .thenReturn(
            new ReadOnlyHttp.Response(
                200,
                Map.of("Docker-Content-Digest", List.of("sha256:" + Json.hash(manifest))),
                manifest.getBytes(StandardCharsets.UTF_8)));
    assertThat(images.resolve("auth-service", "dev", "alice", "build-1").digest())
        .isEqualTo("sha256:" + Json.hash(manifest));
  }

  @Test
  void denialAndMaliciousInputsAreRedacted() {
    ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    var images =
        new ArtifactoryImages(
            config(), http, ref -> new CredentialResolver.Secret("reader", "secret-password"));
    when(http.get(any(), any(), any())).thenReturn(response(403, "secret-password backend detail"));
    assertThatThrownBy(() -> images.discover("auth-service", "dev", "alice", "", 50))
        .hasMessageContaining("denied")
        .hasMessageNotContaining("secret-password");
    assertThatThrownBy(() -> images.discover("auth-service", "dev", "../admin", "", 50))
        .hasMessageContaining("username");
    assertThatThrownBy(() -> images.discover("unknown", "dev", "alice", "", 50))
        .hasMessageContaining("not mapped");
  }

  @Test
  void missingSecretFieldsDoNotLeakPayload() {
    ReadOnlyHttp http = mock(ReadOnlyHttp.class);
    when(http.get(any(), any(), any()))
        .thenReturn(response(200, "{\"password\":\"must-not-leak\"}"));
    var credentials = new ConfiguredCredentials(config(), http, k -> "token");
    assertThatThrownBy(() -> credentials.resolve("read"))
        .hasMessageContaining("configured")
        .hasMessageNotContaining("must-not-leak");
  }

  @Test
  void rejectsPlaintextAndEmbeddedCredentials() {
    assertThatThrownBy(() -> ConnectionConfig.base("http://host/api"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConnectionConfig.base("https://user:password@host/api"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
