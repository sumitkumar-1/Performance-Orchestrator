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

  @AfterEach
  void clearContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void connectionChangesInvalidatePortalTokensEvenIfConfigurationIsLaterRestored() {
    var config = portalConfig();
    var tokens = tokens(config, now);
    var request = request();
    tokens.useToken("organization", "private-token", 60, request.getSession());
    config.replace(config.data());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  @Test
  void suppliedTokenStaysOutOfPublicStatus() {
    var tokens = tokens(portalConfig(), now);
    var request = request();
    var result = tokens.useToken("organization", "private-token", 60, request.getSession());
    verifyNoInteractions(http);
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer private-token");
    assertThat(Json.write(result))
        .contains("TOKEN_PROVIDED")
        .doesNotContain("private-token", "alice");
    assertThat(Json.write(tokens.statuses(request.getSession()))).doesNotContain("private-token");
    tokens.signOut("organization", request.getSession());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  @Test
  void isolatesBrowserSessionsAndExpiresAtServerDeadline() {
    var tokens = tokens(portalConfig(), now);
    var first = request();
    tokens.useToken("organization", "private-token", 60, first.getSession());
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
  void rejectsInvalidTokenAndDoesNotRetainOldAuthentication() {
    var tokens = tokens(portalConfig(), now);
    var session = request().getSession();
    tokens.useToken("organization", "private-token", 60, session);
    assertThatThrownBy(() -> tokens.useToken("organization", "bad token", 60, session))
        .hasMessageContaining("valid bearer");
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
    verifyNoInteractions(http);
  }

  @Test
  void resolvesConfiguredNamespaceSecretAndEvictsRejectedPortalToken() {
    var config = portalConfig();
    // Production resolver and token service use the same server-side session.
    var tokens = new SecretServerTokens(config, http);
    var request = request();
    tokens.useToken("organization", "private-token", 60, request.getSession());
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
  void adExchangeTracksVerifiedUsernameAndServerExpiryWithoutStoringPassword() {
    var config=config(new ConnectionConfig.SecretServer("https://vault.invalid/SecretServer/api/v1",null,
        "interactive","https://vault.invalid/SecretServer/oauth2/token",null));
    var tokens=tokens(config,now);var req=request();
    when(http.tokenForm(any(),eq("DOMAIN\\alice"),eq("private-password"))).thenReturn(response(200,
        "{\"access_token\":\"issued-token\",\"token_type\":\"Bearer\",\"expires_in\":120}"));
    var status=tokens.login("organization","DOMAIN\\alice","private-password",req.getSession());
    assertThat(status).containsEntry("state","AUTHENTICATED").containsEntry("username","DOMAIN\\alice")
        .containsEntry("expiresAt",now.plusSeconds(120).toString());
    assertThat(SecretServerTokens.currentActor()).isEqualTo("DOMAIN\\alice");
    assertThat(Json.write(status)).doesNotContain("private-password","issued-token");
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer issued-token");
    tokens.signOut("organization",req.getSession());
    assertThat(SecretServerTokens.currentActor()).isEqualTo("local-developer");
    when(http.tokenForm(any(),any(),any())).thenReturn(response(401,"private upstream payload"));
    assertThatThrownBy(()->tokens.login("organization","alice","wrong",req.getSession()))
        .hasMessageContaining("HTTP 401").hasMessageNotContaining("private upstream payload");
    assertThat(tokens.status("organization",req.getSession())).containsEntry("state","SIGN_IN_REQUIRED");
  }

  @Test
  void adLoginRejectsUnboundedExpiryAndCrossHostTokenEndpoints() {
    var config=config(new ConnectionConfig.SecretServer("https://vault.invalid/api/v1",null,"interactive","https://vault.invalid/oauth2/token",null));
    var tokens=tokens(config,now);var req=request();
    when(http.tokenForm(any(),any(),any())).thenReturn(response(200,"{\"access_token\":\"token\"}"));
    assertThatThrownBy(()->tokens.login("organization","alice","password",req.getSession())).hasMessageContaining("expires_in");
    assertThat(tokens.status("organization",req.getSession())).containsEntry("state","SIGN_IN_REQUIRED");
    assertThatThrownBy(()->config(new ConnectionConfig.SecretServer("https://vault.invalid/api/v1",null,"interactive","https://other.invalid/token",null))).isInstanceOf(RuntimeException.class);
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
        .hasMessageContaining("no stored token");
    assertThatThrownBy(
            () -> tokens.useToken("organization", "private-token", 60, new MockHttpSession()))
        .hasMessageContaining("Select token");
  }
}
