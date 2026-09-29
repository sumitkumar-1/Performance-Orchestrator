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

@WebMvcTest(controllers = SecretAuthenticationController.class, properties = "spring.config.location=classpath:application-test.yaml")
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
  void passwordLoginEndpointIsRemoved() throws Exception {
    mvc.perform(post("/api/v1/secret-auth/organization").header("Host", "localhost").with(csrf())
        .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"private\"}"))
        .andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(tokens);
  }

  @Test
  void tokenInputRequiresCsrfAndReturnsOnlyStatus() throws Exception {
    String endpoint = "/api/v1/secret-auth/organization/token";
    String input = "{\"token\":\"private-token\",\"expiresInSeconds\":900}";
    mvc.perform(post(endpoint).header("Host", "localhost").contentType("application/json").content(input))
        .andExpect(status().isForbidden());
    when(tokens.useToken(eq("organization"), eq("private-token"), eq(900L), any()))
        .thenReturn(Map.of("state", "TOKEN_PROVIDED"));
    mvc.perform(post(endpoint).header("Host", "localhost").with(csrf()).contentType("application/json").content(input))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("TOKEN_PROVIDED"))
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  @Test
  void rotatesSessionAndReturnsOnlyStatusThenClearsConnection() throws Exception {
    var session = new MockHttpSession();
    String previousId = session.getId();
    when(tokens.useToken(eq("organization"), eq("private-token"), eq(900L), any()))
        .thenReturn(Map.of("mode", "token", "state", "TOKEN_PROVIDED"));
    mvc.perform(
            post("/api/v1/secret-auth/organization/token")
                .header("Host", "localhost")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"token\":\"private-token\",\"expiresInSeconds\":900}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("TOKEN_PROVIDED"))
        .andExpect(jsonPath("$.access_token").doesNotExist());
    assertThat(session.getId()).isNotEqualTo(previousId);
    mvc.perform(
            delete("/api/v1/secret-auth/organization")
                .header("Host", "localhost")
                .session(session)
                .with(csrf()))
        .andExpect(status().isOk());
    verify(tokens).signOut("organization", session);
    assertThat(new SecretAuthenticationController.TokenInput("private-token", 900).toString())
        .isEqualTo("[REDACTED]");
  }

  @Test
  void exampleConfigurationLoadsWithExplicitAuthenticationModes() throws Exception {
    var config = new ConnectionConfig("docs/integration/examples/connections.yaml");
    assertThat(config.data().secretServers().get("organization").mode()).isEqualTo("token");
    assertThat(config.data().secretServers().get("provisioned").mode()).isEqualTo("file");
    assertThat(config.data().credentials().get("logs-service-b").secretId()).isEqualTo("12346");
  }
}
