package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.infrastructure.registry.ReadOnlyHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
  properties = {
    "spring.config.location=classpath:application-test.yaml",
    "orchestrator.mode=real",
    "spring.datasource.url=jdbc:h2:mem:vault-diagnostics;DB_CLOSE_DELAY=-1",
    "orchestrator.configuration-file=target/test-vault-diagnostics/${random.uuid}.json",
    "orchestrator.connection-defaults.bitbucket.office-stash.authMode=secret-server",
    "orchestrator.connection-defaults.bitbucket.office-stash.credentialRef=bitbucket-reader",
    "orchestrator.connection-defaults.artifactory.office.authMode=secret-server",
    "orchestrator.connection-defaults.artifactory.office.credentialRef=registry-reader",
  }
)
@AutoConfigureMockMvc
class VaultDiagnosticsIntegrationTest {

  @Autowired
  MockMvc mvc;

  @MockitoBean
  ReadOnlyHttp http;

  private ReadOnlyHttp.Response response(final int status, final String body) {
    return new ReadOnlyHttp.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  private MockHttpSession signIn() throws Exception {
    final var session = new MockHttpSession();
    mvc
      .perform(
        post("/api/v1/secret-auth/office-vault/token")
          .header("Host", "localhost")
          .session(session)
          .with(csrf())
          .contentType("application/json")
          .content("{\"token\":\"vault-access-token\",\"expiresInSeconds\":900}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.state").value("TOKEN_PROVIDED"));
    return session;
  }

  private org.springframework.test.web.servlet.ResultActions branches(final MockHttpSession session)
    throws Exception {
    return mvc.perform(
      post("/api/v1/service-projects/ps-spoolers-ps-load-gen/references/query")
        .header("Host", "localhost")
        .session(session)
        .with(csrf())
        .contentType("application/json")
        .content("{\"kind\":\"branches\",\"start\":0}")
    );
  }

  /**
   * <b>Scenario:</b> One Vault Sign In Supplies Tokens To Both Diagnostic Clients
   * <pre>
   * GIVEN ... one vault session and token references for Bitbucket and Artifactory
   * WHEN ... both diagnostic clients fetch remote data
   * THEN ... each client resolves its token through the same vault session
   * </pre>
   */
  @Test
  @DisplayName("One Vault Sign In Supplies Tokens To Both Diagnostic Clients")
  void oneVaultSignInSuppliesTokensToBothDiagnosticClients() throws Exception {
    when(http.get(any(), any(), any())).thenAnswer((final var call) -> {
      final URI uri = call.getArgument(0);
      final String auth = call.getArgument(1);
      if (uri.getHost().equals("vault.example.invalid")) {
        assertThat(auth).isEqualTo("Bearer vault-access-token");
        return response(
          200,
          "{\"items\":[{\"slug\":\"token\",\"itemValue\":\"downstream-token\"}]}"
        );
      }
      assertThat(auth).isEqualTo("Bearer downstream-token");
      return uri.getHost().equals("stash.example.invalid")
        ? response(
            200,
            "{\"values\":[{\"id\":\"refs/heads/main\",\"displayId\":\"main\"}],\"isLastPage\":true}"
          )
        : response(200, "{\"tags\":[\"v1\"]}");
    });
    final var session = signIn();
    // Reading settings/status must not discard the token from the rotated session.
    mvc
      .perform(get("/api/v1/secret-auth").header("Host", "localhost").session(session))
      .andExpect(jsonPath("$.office-vault.state").value("TOKEN_PROVIDED"));
    branches(session)
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.values[0].displayName").value("main"));
    mvc
      .perform(
        post(
          "/api/v1/registry-sources/service:ps-spoolers-ps-load-gen/services/ps-spoolers-ps-load-gen/images/query"
        )
          .header("Host", "localhost")
          .session(session)
          .with(csrf())
          .contentType("application/json")
          .content("{\"limit\":50,\"cursor\":\"\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.versions[0]").value("v1"));
    branches(session).andExpect(status().isOk());
  }

  /**
   * <b>Scenario:</b> Rejected Vault Token Is Distinct From Missing Browser Session
   * <pre>
   * GIVEN ... a rejected vault token and a separate request without a session
   * WHEN ... diagnostic requests attempt credential resolution
   * THEN ... errors distinguish rejected authorization from missing sign-in
   * </pre>
   */
  @Test
  @DisplayName("Rejected Vault Token Is Distinct From Missing Browser Session")
  void rejectedVaultTokenIsDistinctFromMissingBrowserSession() throws Exception {
    final var session = signIn();
    when(http.get(any(), any(), any())).thenReturn(response(401, "private upstream details"));
    branches(session)
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.code").value("SECRET_TOKEN_REJECTED"))
      .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HTTP 401")));
    verify(http, times(1)).get(any(), eq("Bearer vault-access-token"), eq("application/json"));
    clearInvocations(http);
    branches(session)
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.code").value("SECRET_SIGN_IN_REQUIRED"))
      .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("office-vault")));
    verifyNoInteractions(http);
  }
}
