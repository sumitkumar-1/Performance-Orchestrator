package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.api.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConnectionSessionController.class)
@Import({LocalSecurity.class, com.example.perforchestrator.infrastructure.config.ConfigurationAccess.class, ConnectionSessionApiTest.Config.class})
class ConnectionSessionApiTest {
  @Autowired MockMvc mvc;
  @Autowired ConnectionSessions sessions;
  @TestConfiguration static class Config {
    @Bean ConnectionSessions sessions() {
      var config = new ConnectionConfig(new ConnectionConfig.Data(
          Map.of("office", new ConnectionConfig.Artifactory("https://registry.invalid/api/docker", null, "ad")),
          Map.of(), Map.of(), Map.of()));
      return new ConnectionSessions(config, mock(CredentialResolver.class));
    }
  }
  private final String endpoint = "/api/v1/connection-auth/artifactory/office";
  private final String input = "{\"authentication\":{\"username\":\"alice\",\"password\":\"private\"},\"lifetimeSeconds\":1800}";

  @Test void requiresCsrfAndSameOrigin() throws Exception {
    mvc.perform(post(endpoint).header("Host", "localhost").contentType("application/json").content(input)).andExpect(status().isForbidden());
    mvc.perform(delete(endpoint).header("Host", "localhost")).andExpect(status().isForbidden());
    mvc.perform(post(endpoint).header("Host", "localhost").header("Origin", "https://untrusted.invalid").with(csrf()).contentType("application/json").content(input)).andExpect(status().isForbidden());

  }

  @Test void rotatesSessionReturnsUncachedStatusAndSignsOut() throws Exception {
    var session = new MockHttpSession();
    var oldId = session.getId();

    mvc.perform(post(endpoint).header("Host", "localhost").session(session).with(csrf()).contentType("application/json").content(input))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("CREDENTIALS_AVAILABLE"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    assertThat(session.getId()).isNotEqualTo(oldId);
    mvc.perform(delete(endpoint).header("Host", "localhost").session(session).with(csrf())).andExpect(status().isOk());
    assertThat(sessions.status("artifactory", "office", session).get("state")).isEqualTo("SIGN_IN_REQUIRED");
  }
}
