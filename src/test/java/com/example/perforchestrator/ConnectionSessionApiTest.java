package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.api.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
  controllers = ConnectionSessionController.class,
  properties = "spring.config.location=classpath:application-test.yaml"
)
@Import({
  LocalSecurity.class,
  com.example.perforchestrator.infrastructure.config.ConfigurationAccess.class,
  ConnectionSessionApiTest.Config.class,
})
class ConnectionSessionApiTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  ConnectionSessions sessions;

  @TestConfiguration
  static class Config {

    @Bean
    ConnectionSessions sessions() {
      final var config = new ConnectionConfig(
        new ConnectionConfig.Data(
          Map.of(
            "office",
            new ConnectionConfig.Artifactory("https://registry.invalid/api/docker", null, "token")
          ),
          Map.of(),
          Map.of(),
          Map.of()
        )
      );
      return new ConnectionSessions(config, mock(CredentialResolver.class));
    }
  }

  private final String endpoint = "/api/v1/connection-auth/artifactory/office";
  private final String input =
    "{\"authentication\":{\"token\":\"private\"},\"lifetimeSeconds\":1800}";

  /**
   * <b>Scenario:</b> Requires CSRF And Same Origin
   * <pre>
   * GIVEN ... connection-session mutation endpoints
   * WHEN ... requests omit CSRF protection or use a different origin
   * THEN ... the requests are rejected
   * </pre>
   */
  @Test
  @DisplayName("Requires Csrf And Same Origin")
  void requiresCsrfAndSameOrigin() throws Exception {
    mvc
      .perform(
        post(endpoint).header("Host", "localhost").contentType("application/json").content(input)
      )
      .andExpect(status().isForbidden());
    mvc.perform(delete(endpoint).header("Host", "localhost")).andExpect(status().isForbidden());
    mvc
      .perform(
        post(endpoint)
          .header("Host", "localhost")
          .header("Origin", "https://untrusted.invalid")
          .with(csrf())
          .contentType("application/json")
          .content(input)
      )
      .andExpect(status().isForbidden());
  }

  /**
   * <b>Scenario:</b> Rotates Session Returns Uncached Status And Signs Out
   * <pre>
   * GIVEN ... a browser establishing a connection session
   * WHEN ... sign-in, status retrieval, and sign-out are performed
   * THEN ... the session ID rotates, status is uncached, and credentials are cleared
   * </pre>
   */
  @Test
  @DisplayName("Rotates Session Returns Uncached Status And Signs Out")
  void rotatesSessionReturnsUncachedStatusAndSignsOut() throws Exception {
    final var session = new MockHttpSession();
    final var oldId = session.getId();

    mvc
      .perform(
        post(endpoint)
          .header("Host", "localhost")
          .session(session)
          .with(csrf())
          .contentType("application/json")
          .content(input)
      )
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.state").value("CREDENTIALS_AVAILABLE"))
      .andExpect(
        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private")))
      );
    assertThat(session.getId()).isNotEqualTo(oldId);
    mvc
      .perform(delete(endpoint).header("Host", "localhost").session(session).with(csrf()))
      .andExpect(status().isOk());
    assertThat(sessions.status("artifactory", "office", session).get("state")).isEqualTo(
      "SIGN_IN_REQUIRED"
    );
  }
}
