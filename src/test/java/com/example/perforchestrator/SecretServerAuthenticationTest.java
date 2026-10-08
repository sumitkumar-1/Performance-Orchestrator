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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class SecretServerAuthenticationTest {

  @TempDir
  Path temp;

  final ReadOnlyHttp http = mock(ReadOnlyHttp.class);
  final Instant now = Instant.parse("2026-09-17T12:00:00Z");

  ConnectionConfig config(final ConnectionConfig.SecretServer server) {
    return new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(),
        Map.of("organization", server),
        Map.of(
          "namespace-a",
          new ConnectionConfig.Credential(
            "delinea",
            null,
            null,
            "organization",
            "12345",
            "username",
            "password"
          )
        ),
        Map.of()
      )
    );
  }

  ConnectionConfig portalConfig() {
    return config(
      new ConnectionConfig.SecretServer(
        "https://vault.invalid/SecretServer/api/v1",
        null,
        "portal",
        "https://vault.invalid/SecretServer/oauth2/token",
        null
      )
    );
  }

  ReadOnlyHttp.Response response(final int status, final String body) {
    return new ReadOnlyHttp.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  SecretServerTokens tokens(final ConnectionConfig config, final Instant instant) {
    return new SecretServerTokens(
      config,
      http,
      (final var key) -> null,
      Clock.fixed(instant, ZoneOffset.UTC)
    );
  }

  MockHttpServletRequest request() {
    final var request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  @AfterEach
  void clearContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  /**
   * <b>Scenario:</b> Connection Changes Invalidate Portal Tokens Even If Configuration Is Later Restored
   * <pre>
   * GIVEN ... a supplied vault token associated with connection configuration
   * WHEN ... the configuration changes and is later restored
   * THEN ... the old token remains invalidated
   * </pre>
   */
  @Test
  @DisplayName(
    "Connection Changes Invalidate Portal Tokens Even If Configuration Is Later Restored"
  )
  void connectionChangesInvalidatePortalTokensEvenIfConfigurationIsLaterRestored() {
    final var config = portalConfig();
    final var tokens = tokens(config, now);
    final var request = request();
    tokens.useToken("organization", "private-token", 60, request.getSession());
    config.replace(config.data());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  /**
   * <b>Scenario:</b> Supplied Token Stays Out Of Public Status
   * <pre>
   * GIVEN ... a browser session with a supplied vault token
   * WHEN ... public authentication status is serialized
   * THEN ... the token value is absent
   * </pre>
   */
  @Test
  @DisplayName("Supplied Token Stays Out Of Public Status")
  void suppliedTokenStaysOutOfPublicStatus() {
    final var tokens = tokens(portalConfig(), now);
    final var request = request();
    final var result = tokens.useToken("organization", "private-token", 60, request.getSession());
    verifyNoInteractions(http);
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer private-token");
    assertThat(Json.write(result))
      .contains("TOKEN_PROVIDED")
      .doesNotContain("private-token", "alice");
    assertThat(Json.write(tokens.statuses(request.getSession()))).doesNotContain("private-token");
    tokens.signOut("organization", request.getSession());
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
  }

  /**
   * <b>Scenario:</b> Isolates Browser Sessions And Expires At Server Deadline
   * <pre>
   * GIVEN ... a vault token stored in one browser session
   * WHEN ... another session requests authorization and the server expiry passes
   * THEN ... sessions remain isolated and expired authorization is unavailable
   * </pre>
   */
  @Test
  @DisplayName("Isolates Browser Sessions And Expires At Server Deadline")
  void isolatesBrowserSessionsAndExpiresAtServerDeadline() {
    final var tokens = tokens(portalConfig(), now);
    final var first = request();
    tokens.useToken("organization", "private-token", 60, first.getSession());
    request();
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
    assertThatThrownBy(() ->
      tokens(portalConfig(), now.plusSeconds(60)).authorization("organization")
    ).hasMessageContaining("Sign in");
    assertThat(tokens.status("organization", first.getSession())).containsEntry(
      "state",
      "SIGN_IN_REQUIRED"
    );
  }

  /**
   * <b>Scenario:</b> Rejects Invalid Token And Does Not Retain Old Authentication
   * <pre>
   * GIVEN ... an authenticated session receiving an invalid replacement token
   * WHEN ... the replacement is submitted
   * THEN ... validation rejects it and clears the old authentication
   * </pre>
   */
  @Test
  @DisplayName("Rejects Invalid Token And Does Not Retain Old Authentication")
  void rejectsInvalidTokenAndDoesNotRetainOldAuthentication() {
    final var tokens = tokens(portalConfig(), now);
    final var session = request().getSession();
    tokens.useToken("organization", "private-token", 60, session);
    assertThatThrownBy(() ->
      tokens.useToken("organization", "bad token", 60, session)
    ).hasMessageContaining("valid bearer");
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining("Sign in");
    verifyNoInteractions(http);
  }

  /**
   * <b>Scenario:</b> Resolves Configured Namespace Secret And Evicts Rejected Portal Token
   * <pre>
   * GIVEN ... a namespace secret reference and a signed-in vault session
   * WHEN ... secret resolution succeeds and a later request receives HTTP 401
   * THEN ... configured fields are resolved and the rejected token is evicted
   * </pre>
   */
  @Test
  @DisplayName("Resolves Configured Namespace Secret And Evicts Rejected Portal Token")
  void resolvesConfiguredNamespaceSecretAndEvictsRejectedPortalToken() {
    final var config = portalConfig();
    // Production resolver and token service use the same server-side session.
    final var tokens = new SecretServerTokens(config, http);
    final var request = request();
    tokens.useToken("organization", "private-token", 60, request.getSession());
    when(http.get(any(), any(), any())).thenReturn(
      response(
        200,
        "{\"items\":[{\"slug\":\"username\",\"itemValue\":\"reader\"},{\"slug\":\"password\",\"itemValue\":\"" +
          " trailing \"}]}"
      )
    );
    final var resolver = new ConfiguredCredentials(config, http, tokens);
    assertThat(resolver.resolve("namespace-a").password()).isEqualTo(" trailing ");
    verify(http).get(
      URI.create("https://vault.invalid/SecretServer/api/v1/secrets/12345"),
      "Bearer private-token",
      "application/json"
    );
    when(http.get(any(), any(), any())).thenReturn(response(401, "private error"));
    assertThatThrownBy(() -> resolver.resolve("namespace-a")).hasMessageContaining("Sign in");
    assertThat(tokens.status("organization", request.getSession())).containsEntry(
      "state",
      "SIGN_IN_REQUIRED"
    );
  }

  /**
   * <b>Scenario:</b> Ad Exchange Tracks Verified Username And Server Expiry Without Storing Password
   * <pre>
   * GIVEN ... a vault token endpoint returning an expiring token for AD credentials
   * WHEN ... AD sign-in succeeds, signs out, and then fails
   * THEN ... status tracks the verified actor and expiry without exposing credentials, and failed login requires sign-in
   * </pre>
   */
  @Test
  @DisplayName("Ad Exchange Tracks Verified Username And Server Expiry Without Storing Password")
  void adExchangeTracksVerifiedUsernameAndServerExpiryWithoutStoringPassword() {
    final var config = config(
      new ConnectionConfig.SecretServer(
        "https://vault.invalid/SecretServer/api/v1",
        null,
        "interactive",
        "https://vault.invalid/SecretServer/oauth2/token",
        null
      )
    );
    final var tokens = tokens(config, now);
    final var req = request();
    when(http.tokenForm(any(), eq("DOMAIN\\alice"), eq("private-password"))).thenReturn(
      response(
        200,
        "{\"access_token\":\"issued-token\",\"token_type\":\"Bearer\",\"expires_in\":120}"
      )
    );
    final var status = tokens.login(
      "organization",
      "DOMAIN\\alice",
      "private-password",
      req.getSession()
    );
    assertThat(status)
      .containsEntry("state", "AUTHENTICATED")
      .containsEntry("username", "DOMAIN\\alice")
      .containsEntry("expiresAt", now.plusSeconds(120).toString());
    assertThat(SecretServerTokens.currentActor()).isEqualTo("DOMAIN\\alice");
    assertThat(Json.write(status)).doesNotContain("private-password", "issued-token");
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer issued-token");
    tokens.signOut("organization", req.getSession());
    assertThat(SecretServerTokens.currentActor()).isEqualTo("local-developer");
    when(http.tokenForm(any(), any(), any())).thenReturn(response(401, "private upstream payload"));
    assertThatThrownBy(() -> tokens.login("organization", "alice", "wrong", req.getSession()))
      .hasMessageContaining("HTTP 401")
      .hasMessageNotContaining("private upstream payload");
    assertThat(tokens.status("organization", req.getSession())).containsEntry(
      "state",
      "SIGN_IN_REQUIRED"
    );
  }

  /**
   * <b>Scenario:</b> Ad Login Rejects Unbounded Expiry And Cross Host Token Endpoints
   * <pre>
   * GIVEN ... an AD token response without bounded expiry or a cross-host token endpoint
   * WHEN ... AD sign-in is attempted
   * THEN ... unsafe token lifetime and endpoint configuration are rejected
   * </pre>
   */
  @Test
  @DisplayName("Ad Login Rejects Unbounded Expiry And Cross Host Token Endpoints")
  void adLoginRejectsUnboundedExpiryAndCrossHostTokenEndpoints() {
    final var config = config(
      new ConnectionConfig.SecretServer(
        "https://vault.invalid/api/v1",
        null,
        "interactive",
        "https://vault.invalid/oauth2/token",
        null
      )
    );
    final var tokens = tokens(config, now);
    final var req = request();
    when(http.tokenForm(any(), any(), any())).thenReturn(
      response(200, "{\"access_token\":\"token\"}")
    );
    assertThatThrownBy(() ->
      tokens.login("organization", "alice", "password", req.getSession())
    ).hasMessageContaining("expires_in");
    assertThat(tokens.status("organization", req.getSession())).containsEntry(
      "state",
      "SIGN_IN_REQUIRED"
    );
    assertThatThrownBy(() ->
      config(
        new ConnectionConfig.SecretServer(
          "https://vault.invalid/api/v1",
          null,
          "interactive",
          "https://other.invalid/token",
          null
        )
      )
    ).isInstanceOf(RuntimeException.class);
  }

  /**
   * <b>Scenario:</b> Reads Rotating Mounted Token And Rejects Ambiguous Modes
   * <pre>
   * GIVEN ... a mounted vault token file and authentication mode configuration
   * WHEN ... the file rotates or conflicting modes are supplied
   * THEN ... authorization reads the new token and ambiguous configuration is rejected
   * </pre>
   */
  @Test
  @DisplayName("Reads Rotating Mounted Token And Rejects Ambiguous Modes")
  void readsRotatingMountedTokenAndRejectsAmbiguousModes() throws Exception {
    final Path file = temp.resolve("token");
    Files.writeString(file, "first-token\n");
    final var config = config(
      new ConnectionConfig.SecretServer(
        "https://vault.invalid/api/v1",
        null,
        "file",
        null,
        file.toString()
      )
    );
    final var tokens = tokens(config, now);
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer first-token");
    Files.writeString(file, "second-token\n");
    assertThat(tokens.authorization("organization")).isEqualTo("Bearer second-token");
    Files.writeString(file, "x".repeat(65537));
    assertThatThrownBy(() -> tokens.authorization("organization")).hasMessageContaining(
      "unavailable"
    );
    assertThatThrownBy(() ->
      config(
        new ConnectionConfig.SecretServer(
          "https://vault.invalid/api/v1",
          "GLOBAL_TOKEN",
          "portal",
          "https://vault.invalid/token",
          null
        )
      )
    ).hasMessageContaining("no stored token");
    assertThatThrownBy(() ->
      tokens.useToken("organization", "private-token", 60, new MockHttpSession())
    ).hasMessageContaining("Select token");
  }
}
