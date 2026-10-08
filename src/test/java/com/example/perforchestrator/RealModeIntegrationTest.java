package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
  properties = {
    "spring.config.location=classpath:application-test.yaml",
    "orchestrator.mode=real",
    "spring.datasource.url=jdbc:h2:mem:real-mode;DB_CLOSE_DELAY=-1",
    "orchestrator.configuration-file=target/test-real-mode/${random.uuid}.json",
    "orchestrator.worker-enabled=true",
  }
)
@AutoConfigureMockMvc
class RealModeIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  Catalog catalog;

  @Autowired
  RuntimeConfiguration configuration;

  @Autowired
  WorkflowWorker worker;

  @Autowired
  Store store;

  @Autowired
  ApplicationContext context;

  @MockitoBean
  ReadOnlyHttp http;

  @MockitoBean
  CredentialResolver credentials;

  /**
   * <b>Scenario:</b> Configuration Endpoint Populates Every Visible Section From Packaged Defaults
   * <pre>
   * GIVEN ... the application running with packaged real-mode defaults
   * WHEN ... the configuration endpoint is requested
   * THEN ... every visible catalog and connection section is populated
   * </pre>
   */
  @Test
  @DisplayName("Configuration Endpoint Populates Every Visible Section From Packaged Defaults")
  void configurationEndpointPopulatesEveryVisibleSectionFromPackagedDefaults() throws Exception {
    mvc
      .perform(get("/api/v1/configuration/startup").header("Host", "localhost"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.configuration.catalog.environments").isNotEmpty())
      .andExpect(jsonPath("$.configuration.catalog.environments.sandbox.limits").doesNotExist())
      .andExpect(
        jsonPath("$.configuration.catalog.environments.sandbox.allowedActions").doesNotExist()
      )
      .andExpect(
        jsonPath("$.configuration.catalog.environments.sandbox.dashboardUrl").doesNotExist()
      )
      .andExpect(jsonPath("$.configuration.catalog.services").isNotEmpty())
      .andExpect(jsonPath("$.configuration.catalog.scenarios").isNotEmpty())
      .andExpect(jsonPath("$.configuration.connections.artifactory").isNotEmpty())
      .andExpect(jsonPath("$.configuration.connections.secretServers").isNotEmpty())
      .andExpect(jsonPath("$.configuration.connections.credentials").isNotEmpty())
      .andExpect(jsonPath("$.configuration.connections.bitbucket").isNotEmpty())
      .andExpect(
        jsonPath(
          "$.configuration.catalog.services.ps-spoolers-ps-load-gen.containerImage.teamId"
        ).value("ps-spoolers")
      );
  }

  /**
   * <b>Scenario:</b> Legacy Real Environment Fields Are Removed Without Losing Monitoring
   * <pre>
   * GIVEN ... a real environment containing legacy fields and monitoring settings
   * WHEN ... the environment configuration is normalized
   * THEN ... obsolete fields are removed and monitoring remains available
   * </pre>
   */
  @Test
  @DisplayName("Legacy Real Environment Fields Are Removed Without Losing Monitoring")
  void legacyRealEnvironmentFieldsAreRemovedWithoutLosingMonitoring() {
    final var original = configuration.current().catalog();
    final var tree = Json.MAPPER.valueToTree(original);
    final var env = (com.fasterxml.jackson.databind.node.ObjectNode) tree
      .path("environments")
      .path("sandbox");
    env.put("dashboardUrl", "https://example.invalid/dashboard");
    env.putArray("allowedActions").add("deploy");
    env.putObject("limits").put("maxRunDurationSeconds", 60);
    final var restored = Json.read(Json.write(tree), Catalog.Data.class);
    assertThat(restored.environments().get("sandbox").monitoring()).isEqualTo(
      original.environments().get("sandbox").monitoring()
    );
    final var exported = Json.MAPPER.valueToTree(restored).path("environments").path("sandbox");
    assertThat(exported.has("limits")).isFalse();
    assertThat(exported.has("allowedActions")).isFalse();
    assertThat(exported.has("dashboardUrl")).isFalse();
  }

  /**
   * <b>Scenario:</b> Real Mode Has No Mock Beans Or Seed Data And Refuses Execution
   * <pre>
   * GIVEN ... real mode with execution disabled
   * WHEN ... application beans, stored data, and execution endpoints are inspected
   * THEN ... mock adapters and seed data are absent and execution is refused
   * </pre>
   */
  @Test
  @DisplayName("Real Mode Has No Mock Beans Or Seed Data And Refuses Execution")
  void realModeHasNoMockBeansOrSeedDataAndRefusesExecution() throws Exception {
    assertThat(catalog.mode()).isEqualTo("real");
    assertThat(catalog.data().services()).containsKey("ps-spoolers-ps-load-gen");
    assertThat(store.profiles()).isEmpty();
    assertThat(context.getBeansOfType(SimulationImages.class)).isEmpty();
    assertThat(
      context.getBeansOfType(
        com.example.perforchestrator.infrastructure.ckp.SimulationDeployment.class
      )
    ).isEmpty();
    assertThat(
      context.getBeansOfType(
        com.example.perforchestrator.infrastructure.loadgen.SimulationLoadGenerator.class
      )
    ).isEmpty();
    assertThat(context.getBeansOfType(SeedData.class)).isEmpty();
    mvc
      .perform(get("/api/v1/session").header("Host", "localhost"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.mode").value("real"))
      .andExpect(jsonPath("$.capabilities.execution").value(false));
    mvc
      .perform(
        post("/api/v1/plans")
          .header("Host", "localhost")
          .with(csrf())
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isNotImplemented())
      .andExpect(jsonPath("$.code").value("REAL_EXECUTION_UNAVAILABLE"));
    mvc
      .perform(
        post("/api/v1/runs")
          .header("Host", "localhost")
          .header("Idempotency-Key", "real-test-123")
          .with(csrf())
          .contentType("application/json")
          .content("{\"planId\":\"any\"}")
      )
      .andExpect(status().isNotImplemented());
    worker.tick();
    assertThat(store.runs()).isEmpty();
    verifyNoInteractions(http);
  }

  /**
   * <b>Scenario:</b> Real Mutations Require CSRF
   * <pre>
   * GIVEN ... a real-mode mutation endpoint without a CSRF token
   * WHEN ... a mutation request is submitted
   * THEN ... the request is forbidden
   * </pre>
   */
  @ParameterizedTest
  @DisplayName("Real Mutations Require CSRF")
  @ValueSource(
    strings = {
      "/api/v1/real/plans",
      "/api/v1/real/runs",
      "/api/v1/real/profiles",
      "/api/v1/real/monitoring-sets",
      "/api/v1/real/diagnostics",
      "/api/v1/real/services/ps-spoolers-ps-load-gen/values",
      "/api/v1/real/runs/example/recover",
      "/api/v1/real/runs/example/monitoring/query",
    }
  )
  void realMutationsRequireCsrf(final String endpoint) throws Exception {
    mvc
      .perform(
        post(endpoint).header("Host", "localhost").contentType("application/json").content("{}")
      )
      .andExpect(status().isForbidden());
    verifyNoInteractions(http, credentials);
  }

  /**
   * <b>Scenario:</b> Real Execution Cannot Run Until Explicitly Enabled
   * <pre>
   * GIVEN ... real execution has not been enabled
   * WHEN ... a real run is requested
   * THEN ... execution is rejected with an enablement error
   * </pre>
   */
  @Test
  @DisplayName("Real Execution Cannot Run Until Explicitly Enabled")
  void realExecutionCannotRunUntilExplicitlyEnabled() throws Exception {
    mvc
      .perform(
        post("/api/v1/real/plans")
          .header("Host", "localhost")
          .with(csrf())
          .contentType("application/json")
          .content("{\"warmupSeconds\":0,\"measurementSeconds\":60,\"maxRunDurationSeconds\":900}")
      )
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REAL_EXECUTION_DISABLED"));
    verifyNoInteractions(http, credentials);
  }

  /**
   * <b>Scenario:</b> Review Progress Is Available Only In The Owning Session
   * <pre>
   * GIVEN ... review progress associated with one browser session
   * WHEN ... the owner and another session request that progress
   * THEN ... only the owner can access it
   * </pre>
   */
  @Test
  @DisplayName("Review Progress Is Available Only In The Owning Session")
  void reviewProgressIsAvailableOnlyInTheOwningSession() throws Exception {
    final var session = new org.springframework.mock.web.MockHttpSession();
    mvc
      .perform(
        post("/api/v1/real/plans")
          .session(session)
          .header("Host", "localhost")
          .header("X-Review-ID", "review-one")
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"services\":[],\"warmupSeconds\":0,\"measurementSeconds\":60,\"maxRunDurationSeconds\":900}"
          )
      )
      .andExpect(status().isConflict());
    mvc
      .perform(
        get("/api/v1/real/preparations/review-one").session(session).header("Host", "localhost")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.state").value("FAILED"));
    mvc
      .perform(get("/api/v1/real/preparations/review-one").header("Host", "localhost"))
      .andExpect(status().isNotFound());
  }

  /**
   * <b>Scenario:</b> Given Multiple Environments When Catalog Changes Then Default Remains And Other Environments Are Retained
   * <pre>
   * GIVEN ... a catalog containing multiple environments and a default target
   * WHEN ... catalog changes are validated
   * THEN ... other environments remain selectable and removal of the default is rejected
   * </pre>
   */
  @Test
  @DisplayName(
    "Given Multiple Environments When Catalog Changes Then Default Remains And Other Environments Are Retained"
  )
  void givenMultipleEnvironmentsWhenCatalogChangesThenDefaultRemainsAndOtherEnvironmentsAreRetained() {
    final var original = configuration.current();
    assertThat(catalog.boundEnvironment()).isEqualTo("sandbox");
    assertThat(catalog.data().environments()).containsKeys("sandbox", "dev", "perf");
    final com.fasterxml.jackson.databind.node.ObjectNode altered = Json.MAPPER.valueToTree(
      original
    );
    (
      (com.fasterxml.jackson.databind.node.ObjectNode) altered
        .path("catalog")
        .path("environments")
        .path("sandbox")
    ).put("clusterIdentity", "different-cluster");
    final var updated = configuration.update(
      Json.read(Json.write(altered), RuntimeConfiguration.Document.class)
    );
    assertThat(updated.catalog().environments().get("sandbox").clusterIdentity()).isEqualTo(
      "different-cluster"
    );
    (
      (com.fasterxml.jackson.databind.node.ObjectNode) altered.path("catalog").path("environments")
    ).remove("sandbox");
    altered.put("revision", updated.revision());
    assertThatThrownBy(() ->
      configuration.update(Json.read(Json.write(altered), RuntimeConfiguration.Document.class))
    ).hasMessageContaining("default environment");
    configuration.update(
      new RuntimeConfiguration.Document(
        updated.revision(),
        original.catalog(),
        original.connections()
      )
    );
  }

  /**
   * <b>Scenario:</b> Bitbucket Reference Query Uses Configured Credentials And Requires CSRF
   * <pre>
   * GIVEN ... Bitbucket configured for a session token or Secret Server reference
   * WHEN ... reference queries are submitted with and without CSRF protection
   * THEN ... protected queries use configured credentials and unprotected requests are rejected
   * </pre>
   */
  @ParameterizedTest
  @DisplayName("Bitbucket Reference Query Uses Configured Credentials And Requires CSRF")
  @ValueSource(strings = { "token", "secret-server" })
  void bitbucketReferenceQueryUsesConfiguredCredentialsAndRequiresCsrf(final String mode)
    throws Exception {
    final var original = configuration.current();
    // Set the authentication contract explicitly: editable application defaults are not test fixtures.
    final com.fasterxml.jackson.databind.node.ObjectNode connections = Json.MAPPER.valueToTree(
      original.connections()
    );
    ((com.fasterxml.jackson.databind.node.ObjectNode) connections.get("bitbucket")).set(
      "test-stash",
      Json.MAPPER.valueToTree(
        new ConnectionConfig.Bitbucket(
          "https://stash.test.invalid/rest/api",
          mode.equals("secret-server") ? "test-stash-token" : null,
          mode
        )
      )
    );
    ((com.fasterxml.jackson.databind.node.ObjectNode) connections.get("credentials")).set(
      "test-stash-token",
      Json.MAPPER.valueToTree(
        new ConnectionConfig.Credential(
          "environment",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "TEST_STASH_TOKEN"
        )
      )
    );
    final com.fasterxml.jackson.databind.node.ObjectNode catalogData = Json.MAPPER.valueToTree(
      original.catalog()
    );
    (
      (com.fasterxml.jackson.databind.node.ObjectNode) catalogData
        .path("services")
        .path("ps-spoolers-ps-load-gen")
    ).set(
      "sourceProject",
      Json.MAPPER.valueToTree(
        new Catalog.SourceProject(
          "test-stash",
          "TEST",
          "load-generator",
          "v1",
          "ckp/helm/load-generator"
        )
      )
    );
    try {
      configuration.update(
        new RuntimeConfiguration.Document(
          original.revision(),
          Json.read(Json.write(catalogData), Catalog.Data.class),
          Json.read(Json.write(connections), ConnectionConfig.Data.class)
        )
      );
      final String endpoint = "/api/v1/service-projects/ps-spoolers-ps-load-gen/references/query";
      final String body = mode.equals("token")
        ? "{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"bitbucket-token\"}}"
        : "{\"kind\":\"tags\",\"start\":0}";
      mvc
        .perform(
          post(endpoint).header("Host", "localhost").contentType("application/json").content(body)
        )
        .andExpect(status().isForbidden());
      verifyNoInteractions(http, credentials);
      when(credentials.resolve("test-stash-token")).thenReturn(
        new CredentialResolver.Secret(null, "bitbucket-token", true)
      );
      when(http.get(any(), any(), any())).thenReturn(
        new ReadOnlyHttp.Response(
          200,
          Map.of(),
          "{\"values\":[{\"id\":\"refs/tags/v1\",\"displayId\":\"v1\"}],\"isLastPage\":true}".getBytes(
            StandardCharsets.UTF_8
          )
        )
      );
      mvc
        .perform(
          post(endpoint)
            .header("Host", "localhost")
            .with(csrf())
            .contentType("application/json")
            .content(body)
        )
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.values[0].displayName").value("v1"))
        .andExpect(jsonPath("$.authentication").doesNotExist());
      verify(http).get(
        java.net.URI.create(
          "https://stash.test.invalid/rest/api/1.0/projects/TEST/repos/load-generator/tags?limit=50&start=0"
        ),
        "Bearer bitbucket-token",
        "application/json"
      );
      if (mode.equals("secret-server")) {
        verify(credentials).resolve("test-stash-token");
        mvc
          .perform(
            post(endpoint)
              .header("Host", "localhost")
              .with(csrf())
              .contentType("application/json")
              .content(
                "{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"unexpected-token\"}}"
              )
          )
          .andExpect(status().isUnprocessableEntity());
        verifyNoMoreInteractions(http, credentials);
      } else {
        verifyNoInteractions(credentials);
      }
    } finally {
      configuration.update(
        new RuntimeConfiguration.Document(
          configuration.current().revision(),
          original.catalog(),
          original.connections()
        )
      );
    }
  }

  /**
   * <b>Scenario:</b> Real Discovery Uses Configured Http Adapter And Mode Cannot Change Via Settings
   * <pre>
   * GIVEN ... real-mode discovery with configured HTTP connections
   * WHEN ... versions are queried and a runtime mode change is attempted
   * THEN ... discovery uses the HTTP adapter and the mode change is rejected
   * </pre>
   */
  @Test
  @DisplayName("Real Discovery Uses Configured Http Adapter And Mode Cannot Change Via Settings")
  void realDiscoveryUsesConfiguredHttpAdapterAndModeCannotChangeViaSettings() throws Exception {
    final var original = configuration.current();
    final com.fasterxml.jackson.databind.node.ObjectNode merged = Json.MAPPER.valueToTree(
      original.connections()
    );
    final com.fasterxml.jackson.databind.node.ObjectNode extra = Json.MAPPER.valueToTree(
      new RegistryContractTest().config().data()
    );
    extra
      .fields()
      .forEachRemaining((final var entry) ->
        ((com.fasterxml.jackson.databind.node.ObjectNode) merged.get(entry.getKey())).setAll(
          (com.fasterxml.jackson.databind.node.ObjectNode) entry.getValue()
        )
      );
    final var mappings = Json.read(Json.write(merged), ConnectionConfig.Data.class);
    try {
      configuration.update(
        new RuntimeConfiguration.Document(original.revision(), original.catalog(), mappings)
      );
      when(credentials.resolve("read")).thenReturn(
        new CredentialResolver.Secret(null, "registry-token", true)
      );
      when(http.get(any(), any(), any())).thenReturn(
        new ReadOnlyHttp.Response(
          200,
          Map.of(),
          "{\"tags\":[\"actual-build-7\"]}".getBytes(StandardCharsets.UTF_8)
        )
      );
      mvc
        .perform(
          get("/api/v1/registry-sources/dev/services/auth-service/images")
            .header("Host", "localhost")
            .param("username", "alice")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0]").value("actual-build-7"));
      verify(http).get(any(), eq("Bearer registry-token"), eq("application/json"));
      final var mockCatalog = new Catalog(
        "classpath:mocks/catalog.yaml",
        "simulation",
        "127.0.0.1"
      ).data();
      assertThatThrownBy(() ->
        configuration.update(
          new RuntimeConfiguration.Document(
            configuration.current().revision(),
            mockCatalog,
            mappings
          )
        )
      ).hasMessageContaining("Invalid catalog");
    } finally {
      configuration.update(
        new RuntimeConfiguration.Document(
          configuration.current().revision(),
          original.catalog(),
          original.connections()
        )
      );
    }
  }
}
