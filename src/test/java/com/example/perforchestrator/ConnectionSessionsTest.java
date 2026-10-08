package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class ConnectionSessionsTest {

  final ConnectionConfig config = new ConnectionConfig(
    new ConnectionConfig.Data(
      Map.of(
        "one",
        new ConnectionConfig.Artifactory("https://registry.invalid/api/docker", null, "token"),
        "two",
        new ConnectionConfig.Artifactory("https://registry2.invalid/api/docker", null, "token")
      ),
      Map.of(),
      Map.of(),
      Map.of(),
      Map.of("one", new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api", null, "token"))
    )
  );
  final Clock clock = mock(Clock.class);
  final Instant now = Instant.parse("2026-09-29T15:00:00Z");
  ConnectionSessions sessions;
  MockHttpServletRequest request;

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(now);
    sessions = new ConnectionSessions(config, mock(CredentialResolver.class), clock);
    request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void cleanup() {
    sessions.close();
    RequestContextHolder.resetRequestAttributes();
  }

  Object retainedEntry() {
    return request
      .getSession()
      .getAttribute(Collections.list(request.getSession().getAttributeNames()).getFirst());
  }

  byte[] encrypted(final Object entry) throws Exception {
    final Field field = entry.getClass().getDeclaredField("encrypted");
    field.setAccessible(true);
    return (byte[]) field.get(entry);
  }

  void login() {
    sessions.remember(
      "artifactory",
      "one",
      new RequestAuthentication(null, null, "private-token"),
      60,
      request.getSession()
    );
  }

  /**
   * <b>Scenario:</b> Credentials Are Reused Only Within The Same Browser And Connection
   * <pre>
   * GIVEN ... credentials retained for one browser and connection
   * WHEN ... authorization is requested for matching and different scopes
   * THEN ... credentials are reused only within the owning browser and connection
   * </pre>
   */
  @Test
  @DisplayName("Credentials Are Reused Only Within The Same Browser And Connection")
  void credentialsAreReusedOnlyWithinTheSameBrowserAndConnection() throws Exception {
    login();
    assertThat(sessions.authorization("artifactory", "one", null)).isEqualTo(
      "Bearer private-token"
    );
    assertThat(sessions.authorization("artifactory", "one", null)).isEqualTo(
      "Bearer private-token"
    );
    final var entry = retainedEntry();
    assertThat(entry).isNotInstanceOf(java.io.Serializable.class);
    assertThat(entry.toString()).isEqualTo("[REDACTED connection session]");
    assertThat(
      new String(encrypted(entry), java.nio.charset.StandardCharsets.UTF_8)
    ).doesNotContain("private-token", "reader");
    assertThat(
      Json.write(sessions.status("artifactory", "one", request.getSession()))
    ).doesNotContain("private-token", "reader", "Basic");
    assertThatThrownBy(() ->
      sessions.authorization("artifactory", "two", null)
    ).hasMessageContaining("Sign in");
    assertThatThrownBy(() -> sessions.authorization("bitbucket", "one", null)).hasMessageContaining(
      "Sign in"
    );
    RequestContextHolder.setRequestAttributes(
      new ServletRequestAttributes(new MockHttpServletRequest())
    );
    assertThatThrownBy(() ->
      sessions.authorization("artifactory", "one", null)
    ).hasMessageContaining("Sign in");
  }

  /**
   * <b>Scenario:</b> Expiry Actively Wipes The Retained Ciphertext Even Without Another Request
   * <pre>
   * GIVEN ... encrypted session credentials reaching their expiry
   * WHEN ... expiry cleanup runs without another browser request
   * THEN ... the retained ciphertext is wiped
   * </pre>
   */
  @Test
  @DisplayName("Expiry Actively Wipes The Retained Ciphertext Even Without Another Request")
  void expiryActivelyWipesTheRetainedCiphertextEvenWithoutAnotherRequest() throws Exception {
    login();
    final var bytes = encrypted(retainedEntry());
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    sessions.purgeExpired();
    assertThat(bytes).containsOnly((byte) 0);
    assertThatThrownBy(() ->
      sessions.authorization("artifactory", "one", null)
    ).hasMessageContaining("Sign in");
  }

  /**
   * <b>Scenario:</b> Logout Session Invalidation And Config Change Destroy Credentials
   * <pre>
   * GIVEN ... a connection session retaining encrypted credentials
   * WHEN ... logout, session invalidation, or connection changes occur
   * THEN ... retained credentials are destroyed
   * </pre>
   */
  @Test
  @DisplayName("Logout Session Invalidation And Config Change Destroy Credentials")
  void logoutSessionInvalidationAndConfigChangeDestroyCredentials() throws Exception {
    login();
    var bytes = encrypted(retainedEntry());
    sessions.forget("artifactory", "one", request.getSession());
    assertThat(bytes).containsOnly((byte) 0);
    login();
    bytes = encrypted(retainedEntry());
    request.getSession().invalidate();
    assertThat(bytes).containsOnly((byte) 0);
    login();
    bytes = encrypted(retainedEntry());
    config.replace(config.data());
    sessions.purgeExpired();
    assertThat(bytes).containsOnly((byte) 0);
    assertThatThrownBy(() ->
      sessions.authorization("artifactory", "one", null)
    ).hasMessageContaining("Sign in");
  }

  /**
   * <b>Scenario:</b> Token Sessions Expire And Invalid Reauthentication Cannot Keep Old Credentials
   * <pre>
   * GIVEN ... an existing connection token session
   * WHEN ... the token expires or invalid reauthentication is attempted
   * THEN ... expired and previously retained credentials are unavailable
   * </pre>
   */
  @Test
  @DisplayName("Token Sessions Expire And Invalid Reauthentication Cannot Keep Old Credentials")
  void tokenSessionsExpireAndInvalidReauthenticationCannotKeepOldCredentials() {
    sessions.remember(
      "bitbucket",
      "one",
      new RequestAuthentication(null, null, "access-token"),
      60,
      request.getSession()
    );
    assertThat(sessions.authorization("bitbucket", "one", null)).isEqualTo("Bearer access-token");
    assertThatThrownBy(() ->
      sessions.remember(
        "bitbucket",
        "one",
        new RequestAuthentication(null, null, "bad\r\nheader"),
        60,
        request.getSession()
      )
    ).hasMessageContaining("valid bearer");
    assertThatThrownBy(() -> sessions.authorization("bitbucket", "one", null)).hasMessageContaining(
      "Sign in"
    );
  }
}
