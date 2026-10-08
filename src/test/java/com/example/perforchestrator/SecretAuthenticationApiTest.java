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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
  controllers = SecretAuthenticationController.class,
  properties = "spring.config.location=classpath:application-test.yaml"
)
@Import({
  LocalSecurity.class,
  com.example.perforchestrator.infrastructure.config.ConfigurationAccess.class,
})
class SecretAuthenticationApiTest {

  @Autowired
  MockMvc mvc;

  @MockitoBean
  SecretServerTokens tokens;

  /**
   * <b>Scenario:</b> Protects Authentication Mutations With CSRF And Same Origin
   * <pre>
   * GIVEN ... Secret Server authentication mutation endpoints
   * WHEN ... requests lack CSRF protection or come from another origin
   * THEN ... the requests are forbidden
   * </pre>
   */
  @Test
  @DisplayName("Protects Authentication Mutations With Csrf And Same Origin")
  void protectsAuthenticationMutationsWithCsrfAndSameOrigin() throws Exception {
    mvc
      .perform(
        post("/api/v1/secret-auth/organization")
          .header("Host", "localhost")
          .contentType("application/json")
          .content("{\"username\":\"alice\",\"password\":\"password\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(delete("/api/v1/secret-auth/organization").header("Host", "localhost"))
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/secret-auth/organization")
          .header("Host", "localhost")
          .header("Origin", "https://untrusted.invalid")
          .with(csrf())
          .contentType("application/json")
          .content("{\"username\":\"alice\",\"password\":\"password\"}")
      )
      .andExpect(status().isForbidden());
    verifyNoInteractions(tokens);
  }

  /**
   * <b>Scenario:</b> Password Login Endpoint Is Removed
   * <pre>
   * GIVEN ... the removed legacy password-login endpoint
   * WHEN ... a request targets that endpoint
   * THEN ... the endpoint is unavailable
   * </pre>
   */
  @Test
  @DisplayName("Password Login Endpoint Is Removed")
  void passwordLoginEndpointIsRemoved() throws Exception {
    mvc
      .perform(
        post("/api/v1/secret-auth/organization")
          .header("Host", "localhost")
          .with(csrf())
          .contentType("application/json")
          .content("{\"username\":\"alice\",\"password\":\"private\"}")
      )
      .andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(tokens);
  }

  /**
   * <b>Scenario:</b> Token Input Requires CSRF And Returns Only Status
   * <pre>
   * GIVEN ... a Secret Server token sign-in request
   * WHEN ... the request is made with and without CSRF protection
   * THEN ... only protected requests are accepted and responses contain status rather than the token
   * </pre>
   */
  @Test
  @DisplayName("Token Input Requires Csrf And Returns Only Status")
  void tokenInputRequiresCsrfAndReturnsOnlyStatus() throws Exception {
    final String endpoint = "/api/v1/secret-auth/organization/token";
    final String input = "{\"token\":\"private-token\",\"expiresInSeconds\":900}";
    mvc
      .perform(
        post(endpoint).header("Host", "localhost").contentType("application/json").content(input)
      )
      .andExpect(status().isForbidden());
    when(tokens.useToken(eq("organization"), eq("private-token"), eq(900L), any())).thenReturn(
      Map.of("state", "TOKEN_PROVIDED")
    );
    mvc
      .perform(
        post(endpoint)
          .header("Host", "localhost")
          .with(csrf())
          .contentType("application/json")
          .content(input)
      )
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.state").value("TOKEN_PROVIDED"))
      .andExpect(jsonPath("$.token").doesNotExist());
  }

  /**
   * <b>Scenario:</b> Rotates Session And Returns Only Status Then Clears Connection
   * <pre>
   * GIVEN ... a browser signing in to Secret Server with a token
   * WHEN ... sign-in, status retrieval, and sign-out are performed
   * THEN ... the session rotates, responses exclude the token, and sign-out clears authentication
   * </pre>
   */
  @Test
  @DisplayName("Rotates Session And Returns Only Status Then Clears Connection")
  void rotatesSessionAndReturnsOnlyStatusThenClearsConnection() throws Exception {
    final var session = new MockHttpSession();
    final String previousId = session.getId();
    when(tokens.useToken(eq("organization"), eq("private-token"), eq(900L), any())).thenReturn(
      Map.of("mode", "token", "state", "TOKEN_PROVIDED")
    );
    mvc
      .perform(
        post("/api/v1/secret-auth/organization/token")
          .header("Host", "localhost")
          .session(session)
          .with(csrf())
          .contentType("application/json")
          .content("{\"token\":\"private-token\",\"expiresInSeconds\":900}")
      )
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.state").value("TOKEN_PROVIDED"))
      .andExpect(jsonPath("$.access_token").doesNotExist());
    assertThat(session.getId()).isNotEqualTo(previousId);
    mvc
      .perform(
        delete("/api/v1/secret-auth/organization")
          .header("Host", "localhost")
          .session(session)
          .with(csrf())
      )
      .andExpect(status().isOk());
    verify(tokens).signOut("organization", session);
    assertThat(
      new SecretAuthenticationController.TokenInput("private-token", 900).toString()
    ).isEqualTo("[REDACTED]");
  }

  /**
   * <b>Scenario:</b> Ad Login Requires CSRF And Does Not Return Password Or Token
   * <pre>
   * GIVEN ... a Secret Server AD sign-in request
   * WHEN ... the request is submitted with and without CSRF protection
   * THEN ... unprotected requests fail and successful responses expose neither password nor token
   * </pre>
   */
  @Test
  @DisplayName("Ad Login Requires Csrf And Does Not Return Password Or Token")
  void adLoginRequiresCsrfAndDoesNotReturnPasswordOrToken() throws Exception {
    final String body = "{\"username\":\"alice\",\"password\":\"private\"}";
    mvc
      .perform(
        post("/api/v1/secret-auth/organization/ad")
          .header("Host", "localhost")
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isForbidden());
    verifyNoInteractions(tokens);
    when(tokens.login(eq("organization"), eq("alice"), eq("private"), any())).thenReturn(
      Map.of("state", "AUTHENTICATED", "username", "alice")
    );
    mvc
      .perform(
        post("/api/v1/secret-auth/organization/ad")
          .header("Host", "localhost")
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.username").value("alice"))
      .andExpect(jsonPath("$.password").doesNotExist())
      .andExpect(jsonPath("$.access_token").doesNotExist());
  }

  /**
   * <b>Scenario:</b> Example Configuration Loads With Explicit Authentication Modes
   * <pre>
   * GIVEN ... the documented connection configuration example
   * WHEN ... the example is loaded
   * THEN ... connection authentication modes are explicitly configured and valid
   * </pre>
   */
  @Test
  @DisplayName("Example Configuration Loads With Explicit Authentication Modes")
  void exampleConfigurationLoadsWithExplicitAuthenticationModes() throws Exception {
    final var config = new ConnectionConfig("docs/integration/examples/connections.yaml");
    assertThat(config.data().secretServers().get("organization").mode()).isEqualTo("token");
    assertThat(config.data().secretServers().get("provisioned").mode()).isEqualTo("file");
    assertThat(config.data().credentials().get("logs-service-b").secretId()).isEqualTo("12346");
  }
}
