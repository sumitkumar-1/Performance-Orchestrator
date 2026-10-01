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
      "orchestrator.worker-enabled=true"
    })
@AutoConfigureMockMvc
class RealModeIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired RuntimeConfiguration configuration;
  @Autowired WorkflowWorker worker;
  @Autowired Store store;
  @Autowired ApplicationContext context;
  @MockitoBean ReadOnlyHttp http;
  @MockitoBean CredentialResolver credentials;

  @Test
  void configurationEndpointPopulatesEveryVisibleSectionFromPackagedDefaults() throws Exception {
    mvc.perform(get("/api/v1/configuration/startup").header("Host", "localhost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configuration.catalog.environments").isNotEmpty())
        .andExpect(jsonPath("$.configuration.catalog.environments.sandbox.limits").doesNotExist())
        .andExpect(jsonPath("$.configuration.catalog.environments.sandbox.allowedActions").doesNotExist())
        .andExpect(jsonPath("$.configuration.catalog.environments.sandbox.dashboardUrl").doesNotExist())
        .andExpect(jsonPath("$.configuration.catalog.services").isNotEmpty())
        .andExpect(jsonPath("$.configuration.catalog.scenarios").isNotEmpty())
        .andExpect(jsonPath("$.configuration.connections.artifactory").isNotEmpty())
        .andExpect(jsonPath("$.configuration.connections.secretServers").isNotEmpty())
        .andExpect(jsonPath("$.configuration.connections.credentials").isNotEmpty())
        .andExpect(jsonPath("$.configuration.connections.bitbucket").isNotEmpty())
        .andExpect(jsonPath("$.configuration.catalog.services.ps-spoolers-ps-load-gen.containerImage.teamId").value("ps-spoolers"));
  }

  @Test
  void legacyRealEnvironmentFieldsAreRemovedWithoutLosingMonitoring() {
    var original = configuration.current().catalog();
    var tree = Json.MAPPER.valueToTree(original);
    var env = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("environments").path("sandbox");
    env.put("dashboardUrl", "https://example.invalid/dashboard");
    env.putArray("allowedActions").add("deploy");
    env.putObject("limits").put("maxRunDurationSeconds", 60);
    var restored = Json.read(Json.write(tree), Catalog.Data.class);
    assertThat(restored.environments().get("sandbox").monitoring())
        .isEqualTo(original.environments().get("sandbox").monitoring());
    var exported = Json.MAPPER.valueToTree(restored).path("environments").path("sandbox");
    assertThat(exported.has("limits")).isFalse();
    assertThat(exported.has("allowedActions")).isFalse();
    assertThat(exported.has("dashboardUrl")).isFalse();
  }

  @Test
  void realModeHasNoMockBeansOrSeedDataAndRefusesExecution() throws Exception {
    assertThat(catalog.mode()).isEqualTo("real");
    assertThat(catalog.data().services()).containsKey("ps-spoolers-ps-load-gen");
    assertThat(store.profiles()).isEmpty();
    assertThat(context.getBeansOfType(SimulationImages.class)).isEmpty();
    assertThat(
            context.getBeansOfType(
                com.example.perforchestrator.infrastructure.ckp.SimulationDeployment.class))
        .isEmpty();
    assertThat(
            context.getBeansOfType(
                com.example.perforchestrator.infrastructure.loadgen.SimulationLoadGenerator.class))
        .isEmpty();
    assertThat(context.getBeansOfType(SeedData.class)).isEmpty();
    mvc.perform(get("/api/v1/session").header("Host", "localhost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("real"))
        .andExpect(jsonPath("$.capabilities.execution").value(false));
    mvc.perform(
            post("/api/v1/plans")
                .header("Host", "localhost")
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("REAL_EXECUTION_UNAVAILABLE"));
    mvc.perform(
            post("/api/v1/runs")
                .header("Host", "localhost")
                .header("Idempotency-Key", "real-test-123")
                .with(csrf())
                .contentType("application/json")
                .content("{\"planId\":\"any\"}"))
        .andExpect(status().isNotImplemented());
    worker.tick();
    assertThat(store.runs()).isEmpty();
    verifyNoInteractions(http);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/real/plans", "/api/v1/real/runs", "/api/v1/real/profiles", "/api/v1/real/monitoring-sets",
      "/api/v1/real/services/ps-spoolers-ps-load-gen/values", "/api/v1/real/runs/example/recover", "/api/v1/real/runs/example/monitoring/query"})
  void realMutationsRequireCsrf(String endpoint) throws Exception {
    mvc.perform(post(endpoint).header("Host", "localhost").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(http, credentials);
  }

  @Test
  void realExecutionCannotRunUntilExplicitlyEnabled() throws Exception {
    mvc.perform(post("/api/v1/real/plans").header("Host", "localhost").with(csrf())
        .contentType("application/json").content("{\"warmupSeconds\":0,\"measurementSeconds\":60,\"maxRunDurationSeconds\":900}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REAL_EXECUTION_DISABLED"));
    verifyNoInteractions(http, credentials);
  }

  @Test
  void runtimeCannotSwitchInstanceEnvironmentOrCluster() {
    var original = configuration.current();
    assertThat(catalog.boundEnvironment()).isEqualTo("sandbox");
    assertThat(catalog.data().environments()).containsOnlyKeys("sandbox");
    com.fasterxml.jackson.databind.node.ObjectNode altered = Json.MAPPER.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("catalog").path("environments").path("sandbox"))
        .put("clusterIdentity", "different-cluster");
    assertThatThrownBy(() -> configuration.update(Json.read(Json.write(altered), RuntimeConfiguration.Document.class)))
        .hasMessageContaining("Invalid catalog");
    assertThat(configuration.current()).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(strings = {"token", "secret-server"})
  void bitbucketReferenceQueryUsesConfiguredCredentialsAndRequiresCsrf(String mode) throws Exception {
    var original = configuration.current();
    // Set the authentication contract explicitly: editable application defaults are not test fixtures.
    com.fasterxml.jackson.databind.node.ObjectNode connections = Json.MAPPER.valueToTree(original.connections());
    ((com.fasterxml.jackson.databind.node.ObjectNode) connections.get("bitbucket"))
        .set("test-stash", Json.MAPPER.valueToTree(new ConnectionConfig.Bitbucket(
            "https://stash.test.invalid/rest/api", mode.equals("secret-server") ? "test-stash-token" : null, mode)));
    ((com.fasterxml.jackson.databind.node.ObjectNode) connections.get("credentials"))
        .set("test-stash-token", Json.MAPPER.valueToTree(new ConnectionConfig.Credential(
            "environment", null, null, null, null, null, null, null, "TEST_STASH_TOKEN")));
    com.fasterxml.jackson.databind.node.ObjectNode catalogData = Json.MAPPER.valueToTree(original.catalog());
    ((com.fasterxml.jackson.databind.node.ObjectNode) catalogData.path("services").path("ps-spoolers-ps-load-gen"))
        .set("sourceProject", Json.MAPPER.valueToTree(new Catalog.SourceProject(
            "test-stash", "TEST", "load-generator", "v1", "ckp/helm/load-generator")));
    try {
      configuration.update(new RuntimeConfiguration.Document(original.revision(),
          Json.read(Json.write(catalogData), Catalog.Data.class),
          Json.read(Json.write(connections), ConnectionConfig.Data.class)));
      String endpoint = "/api/v1/service-projects/ps-spoolers-ps-load-gen/references/query";
      String body = mode.equals("token")
          ? "{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"bitbucket-token\"}}"
          : "{\"kind\":\"tags\",\"start\":0}";
      mvc.perform(post(endpoint).header("Host", "localhost").contentType("application/json").content(body))
          .andExpect(status().isForbidden());
      verifyNoInteractions(http, credentials);
      when(credentials.resolve("test-stash-token"))
          .thenReturn(new CredentialResolver.Secret(null, "bitbucket-token", true));
      when(http.get(any(), any(), any())).thenReturn(new ReadOnlyHttp.Response(200, Map.of(),
          "{\"values\":[{\"id\":\"refs/tags/v1\",\"displayId\":\"v1\"}],\"isLastPage\":true}".getBytes(StandardCharsets.UTF_8)));
      mvc.perform(post(endpoint).header("Host", "localhost").with(csrf()).contentType("application/json").content(body))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.values[0].displayName").value("v1"))
          .andExpect(jsonPath("$.authentication").doesNotExist());
      verify(http).get(java.net.URI.create(
          "https://stash.test.invalid/rest/api/1.0/projects/TEST/repos/load-generator/tags?limit=50&start=0"),
          "Bearer bitbucket-token", "application/json");
      if (mode.equals("secret-server")) {
        verify(credentials).resolve("test-stash-token");
        mvc.perform(post(endpoint).header("Host", "localhost").with(csrf()).contentType("application/json")
            .content("{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"unexpected-token\"}}"))
            .andExpect(status().isUnprocessableEntity());
        verifyNoMoreInteractions(http, credentials);
      } else {
        verifyNoInteractions(credentials);
      }
    } finally {
      configuration.update(new RuntimeConfiguration.Document(
          configuration.current().revision(), original.catalog(), original.connections()));
    }
  }

  @Test
  void realDiscoveryUsesConfiguredHttpAdapterAndModeCannotChangeViaSettings() throws Exception {
    var original = configuration.current();
    com.fasterxml.jackson.databind.node.ObjectNode merged = Json.MAPPER.valueToTree(original.connections());
    com.fasterxml.jackson.databind.node.ObjectNode extra = Json.MAPPER.valueToTree(new RegistryContractTest().config().data());
    extra.fields().forEachRemaining(entry -> ((com.fasterxml.jackson.databind.node.ObjectNode) merged.get(entry.getKey()))
        .setAll((com.fasterxml.jackson.databind.node.ObjectNode) entry.getValue()));
    var mappings = Json.read(Json.write(merged), ConnectionConfig.Data.class);
    try {
      configuration.update(
          new RuntimeConfiguration.Document(original.revision(), original.catalog(), mappings));
      when(credentials.resolve("read"))
          .thenReturn(new CredentialResolver.Secret(null, "registry-token", true));
      when(http.get(any(), any(), any()))
          .thenReturn(
              new ReadOnlyHttp.Response(
                  200,
                  Map.of(),
                  "{\"tags\":[\"actual-build-7\"]}".getBytes(StandardCharsets.UTF_8)));
      mvc.perform(
              get("/api/v1/registry-sources/dev/services/auth-service/images")
                  .header("Host", "localhost")
                  .param("username", "alice"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.versions[0]").value("actual-build-7"));
      verify(http).get(any(), eq("Bearer registry-token"), eq("application/json"));
      var mockCatalog =
          new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1").data();
      assertThatThrownBy(
              () ->
                  configuration.update(
                      new RuntimeConfiguration.Document(
                          configuration.current().revision(), mockCatalog, mappings)))
          .hasMessageContaining("Invalid catalog");
    } finally {
      configuration.update(
          new RuntimeConfiguration.Document(
              configuration.current().revision(), original.catalog(), original.connections()));
    }
  }
}
