package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class SecretServerAuthenticationTest {
  @TempDir Path temp;
  final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
  final Instant now = Instant.parse("2026-09-17T12:00:00Z");

  ConnectionConfig config(ConnectionConfig.SecretServer server) {
    return new ConnectionConfig(
        new ConnectionConfig.Data(
            Map.of(),
            Map.of("organization", server),
            Map.of(
                "namespace-a",
                new ConnectionConfig.Credential(
                    "delinea", null, null, "organization", "12345", "username", "password")),
            Map.of()));
  }

  ConnectionConfig portalConfig() {
    return config(
        new ConnectionConfig.SecretServer(
            "https://vault.invalid/SecretServer/api/v1",
            null,
            "portal",
            "https://vault.invalid/SecretServer/oauth2/token",
            null));
  }

  ReadOnlyHttp.Response response(int status, String body) {
    return new ReadOnlyHttp.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  SecretServerTokens tokens(ConnectionConfig config, Instant instant) {
    return new SecretServerTokens(config, http, key -> null, Clock.fixed(instant, ZoneOffset.UTC));
  }

  MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  void successfulToken() {
    when(http.tokenForm(any(), any()))
        .thenReturn(
            response(
                200,
                "{\"access_token\":\"private-token\",\"token_type\":\"Bearer\",\"expires_in\":60}"));
  }

  @AfterEach
  void clearContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void connectionChangesInvalidatePortalTokensEvenIfConfigurationIsLaterRestored() {
    successfulToken();
    var config = portalConfig();
    var tokens = tokens(config, now);
    var request = request();
    tokens.signIn("organization", "alice", "password", request.getSession());
    config.replace(config.data());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  @Test
  void exchangesPasswordWithoutTrimmingAndKeepsTokensOutOfPublicStatus() {
    successfulToken();
    var tokens = tokens(portalConfig(), now);
    var request = request();
    var result = tokens.signIn("organization", " alice ", " p&+= ", request.getSession());
    verify(http)
        .tokenForm(
            URI.create("https://vault.invalid/SecretServer/oauth2/token"),
            "grant_type=password&username=alice&password=+p%26%2B%3D+");
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer private-token");
    assertThat(Json.write(result))
        .contains("AUTHENTICATED")
        .doesNotContain("private-token", "alice");
    assertThat(Json.write(tokens.statuses(request.getSession()))).doesNotContain("private-token");
    tokens.signOut("organization", request.getSession());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  @Test
  void isolatesBrowserSessionsAndExpiresAtServerDeadline() {
    successfulToken();
    var tokens = tokens(portalConfig(), now);
    var first = request();
    tokens.signIn("organization", "alice", "password", first.getSession());
    request();
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
    assertThatThrownBy(
            () -> tokens(portalConfig(), now.plusSeconds(60)).authorization("organization"))
        .hasMessageContaining("Sign in");
    assertThat(tokens.status("organization", first.getSession()))
        .containsEntry("state", "SIGN_IN_REQUIRED");
  }

  @Test
  void rejectsInvalidTokenResponsesAndDoesNotRetainOldAuthentication() {
    successfulToken();
    var tokens = tokens(portalConfig(), now);
    var session = request().getSession();
    tokens.signIn("organization", "alice", "password", session);
    when(http.tokenForm(any(), any())).thenReturn(response(400, "private-password secret-body"));
    assertThatThrownBy(() -> tokens.signIn("organization", "alice", "password", session))
        .hasMessageNotContaining("private-password")
        .hasMessageNotContaining("secret-body");
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
    for (String body :
        List.of(
            "{\"access_token\":\"private-token\"}",
            "{\"access_token\":\"private-token\",\"expires_in\":-1}",
            "{\"access_token\":\"private-token\",\"expires_in\":60,\"token_type\":\"Basic\"}")) {
      when(http.tokenForm(any(), any())).thenReturn(response(200, body));
      assertThatThrownBy(() -> tokens.signIn("organization", "alice", "password", session))
          .hasMessageContaining("Token response")
          .hasMessageNotContaining("private-token");
    }
  }

  @Test
  void resolvesConfiguredNamespaceSecretAndEvictsRejectedPortalToken() {
    successfulToken();
    var config = portalConfig();
    // Production resolver and token service use the same server-side session.
    var tokens = new SecretServerTokens(config, http);
    var request = request();
    tokens.signIn("organization", "alice", "password", request.getSession());
    when(http.get(any(), any(), any()))
        .thenReturn(
            response(
                200,
                "{\"items\":[{\"slug\":\"username\",\"itemValue\":\"reader\"},{\"slug\":\"password\",\"itemValue\":\""
                    + " trailing \"}]}"));
    var resolver = new ConfiguredCredentials(config, http, tokens);
    assertThat(resolver.resolve("namespace-a").password()).isEqualTo(" trailing ");
    verify(http)
        .get(
            URI.create("https://vault.invalid/SecretServer/api/v1/secrets/12345"),
            "Bearer private-token",
            "application/json");
    when(http.get(any(), any(), any())).thenReturn(response(401, "private error"));
    assertThatThrownBy(() -> resolver.resolve("namespace-a")).hasMessageContaining("Sign in");
    assertThat(tokens.status("organization", request.getSession()))
        .containsEntry("state", "SIGN_IN_REQUIRED");
  }

  @Test
  void readsRotatingMountedTokenAndRejectsAmbiguousModes() throws Exception {
    Path file = temp.resolve("token");
    Files.writeString(file, "first-token\n");
    var config =
        config(
            new ConnectionConfig.SecretServer(
                "https://vault.invalid/api/v1", null, "file", null, file.toString()));
    var tokens = tokens(config, now);
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer first-token");
    Files.writeString(file, "second-token\n");
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer second-token");
    Files.writeString(file, "x".repeat(65537));
    assertThatThrownBy(() -> tokens.authorization("organization"))
        .hasMessageContaining("unavailable");
    assertThatThrownBy(
            () ->
                config(
                    new ConnectionConfig.SecretServer(
                        "https://vault.invalid/api/v1",
                        "GLOBAL_TOKEN",
                        "portal",
                        "https://vault.invalid/token",
                        null)))
        .hasMessageContaining("cannot fall back");
    assertThatThrownBy(
            () -> tokens.signIn("organization", "alice", "password", new MockHttpSession()))
        .hasMessageContaining("administrator-provided");
  }
}
