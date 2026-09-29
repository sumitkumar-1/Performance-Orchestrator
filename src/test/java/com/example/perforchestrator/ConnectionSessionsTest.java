package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.secrets.*;
import com.example.perforchestrator.infrastructure.config.Json;
import java.time.*;
import java.util.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class ConnectionSessionsTest {
  final ConnectionConfig config = new ConnectionConfig(new ConnectionConfig.Data(
      Map.of("one", new ConnectionConfig.Artifactory("https://registry.invalid/api/docker", null, "ad"),
          "two", new ConnectionConfig.Artifactory("https://registry2.invalid/api/docker", null, "ad")),
      Map.of(), Map.of(), Map.of(), Map.of("one", new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api", null, "token"))));
  final Clock clock = mock(Clock.class);
  final Instant now = Instant.parse("2026-09-29T15:00:00Z");
  ConnectionSessions sessions;
  MockHttpServletRequest request;
  @BeforeEach void setup() {
    when(clock.instant()).thenReturn(now);
    sessions = new ConnectionSessions(config, mock(CredentialResolver.class), clock);
    request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
  @AfterEach void cleanup() { sessions.close(); RequestContextHolder.resetRequestAttributes(); }
  Object retainedEntry() {
    return request.getSession().getAttribute(Collections.list(request.getSession().getAttributeNames()).getFirst());
  }
  byte[] encrypted(Object entry) throws Exception {
    Field field = entry.getClass().getDeclaredField("encrypted"); field.setAccessible(true); return (byte[]) field.get(entry);
  }
  void login() { sessions.remember("artifactory", "one", new RequestAuthentication("reader", "private-password", null), 60, request.getSession()); }

  @Test void credentialsAreReusedOnlyWithinTheSameBrowserAndConnection() throws Exception {
    login();
    assertThat(sessions.authorization("artifactory", "one", null)).isEqualTo(RequestAuthentication.basic("reader", "private-password"));
    assertThat(sessions.authorization("artifactory", "one", null)).isEqualTo(RequestAuthentication.basic("reader", "private-password"));
    var entry = retainedEntry();
    assertThat(entry).isNotInstanceOf(java.io.Serializable.class);
    assertThat(entry.toString()).isEqualTo("[REDACTED connection session]");
    assertThat(new String(encrypted(entry), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("private-password", "reader");
    assertThat(Json.write(sessions.status("artifactory", "one", request.getSession()))).doesNotContain("private-password", "reader", "Basic");
    assertThatThrownBy(() -> sessions.authorization("artifactory", "two", null)).hasMessageContaining("Sign in");
    assertThatThrownBy(() -> sessions.authorization("bitbucket", "one", null)).hasMessageContaining("Sign in");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    assertThatThrownBy(() -> sessions.authorization("artifactory", "one", null)).hasMessageContaining("Sign in");
  }
  @Test void expiryActivelyWipesTheRetainedCiphertextEvenWithoutAnotherRequest() throws Exception {
    login(); var bytes = encrypted(retainedEntry());
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    sessions.purgeExpired();
    assertThat(bytes).containsOnly((byte) 0);
    assertThatThrownBy(() -> sessions.authorization("artifactory", "one", null)).hasMessageContaining("Sign in");
  }
  @Test void logoutSessionInvalidationAndConfigChangeDestroyCredentials() throws Exception {
    login(); var bytes = encrypted(retainedEntry());
    sessions.forget("artifactory", "one", request.getSession()); assertThat(bytes).containsOnly((byte) 0);
    login(); bytes = encrypted(retainedEntry()); request.getSession().invalidate(); assertThat(bytes).containsOnly((byte) 0);
    login(); bytes = encrypted(retainedEntry()); config.replace(config.data()); sessions.purgeExpired(); assertThat(bytes).containsOnly((byte) 0);
    assertThatThrownBy(() -> sessions.authorization("artifactory", "one", null)).hasMessageContaining("Sign in");
  }
  @Test void tokenSessionsExpireAndInvalidReauthenticationCannotKeepOldCredentials() {
    sessions.remember("bitbucket", "one", new RequestAuthentication(null, null, "access-token"), 60, request.getSession());
    assertThat(sessions.authorization("bitbucket", "one", null)).isEqualTo("Bearer access-token");
    assertThatThrownBy(() -> sessions.remember("bitbucket", "one", new RequestAuthentication(null, null, "bad\r\nheader"), 60, request.getSession())).hasMessageContaining("valid bearer");
    assertThatThrownBy(() -> sessions.authorization("bitbucket", "one", null)).hasMessageContaining("Sign in");
  }
}
