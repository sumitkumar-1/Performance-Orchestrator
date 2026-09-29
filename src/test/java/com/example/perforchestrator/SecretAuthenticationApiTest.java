package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.api.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.example.perforchestrator.infrastructure.secrets.SecretServerTokens;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SecretAuthenticationController.class)
@Import({
  LocalSecurity.class,
  com.example.perforchestrator.infrastructure.config.ConfigurationAccess.class
})
class SecretAuthenticationApiTest {
  @Autowired MockMvc mvc;
  @MockitoBean SecretServerTokens tokens;

  @Test
  void protectsAuthenticationMutationsWithCsrfAndSameOrigin() throws Exception {
    mvc.perform(
            post("/api/v1/secret-auth/organization")
                .header("Host", "localhost")
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/secret-auth/organization").header("Host", "localhost"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/secret-auth/organization")
                .header("Host", "localhost")
                .header("Origin", "https://untrusted.invalid")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(tokens);
  }

  @Test
  void rotatesSessionAndReturnsOnlyStatusThenClearsConnection() throws Exception {
    var session = new MockHttpSession();
    String previousId = session.getId();
    when(tokens.signIn(eq("organization"), eq("alice"), eq(" password "), any()))
        .thenReturn(Map.of("mode", "portal", "state", "AUTHENTICATED"));
    mvc.perform(
            post("/api/v1/secret-auth/organization")
                .header("Host", "localhost")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\" password \"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("AUTHENTICATED"))
        .andExpect(jsonPath("$.access_token").doesNotExist());
    assertThat(session.getId()).isNotEqualTo(previousId);
    mvc.perform(
            delete("/api/v1/secret-auth/organization")
                .header("Host", "localhost")
                .session(session)
                .with(csrf()))
        .andExpect(status().isOk());
    verify(tokens).signOut("organization", session);
    assertThat(new SecretAuthenticationController.Credentials("alice", "password").toString())
        .isEqualTo("[REDACTED]");
  }

  @Test
  void exampleConfigurationLoadsWithExplicitAuthenticationModes() throws Exception {
    var config = new ConnectionConfig("src/main/resources/config/examples/connections.yaml");
    assertThat(config.data().secretServers().get("organization").mode()).isEqualTo("portal");
    assertThat(config.data().secretServers().get("provisioned").mode()).isEqualTo("file");
    assertThat(config.data().credentials().get("logs-service-b").secretId()).isEqualTo("12346");
  }
}
