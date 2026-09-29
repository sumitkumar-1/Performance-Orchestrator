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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
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

  @Test
  void bitbucketReferenceQueryUsesConfiguredCredentialsAndRequiresCsrf() throws Exception {
    String endpoint = "/api/v1/service-projects/ps-spoolers-ps-load-gen/references/query";
    mvc.perform(post(endpoint).header("Host", "localhost").contentType("application/json")
        .content("{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"bitbucket-token\"}}"))
        .andExpect(status().isForbidden());
    when(credentials.resolve("bitbucket-reader")).thenReturn(new CredentialResolver.Secret("reader", "private-password"));
    when(http.get(any(), any(), any())).thenReturn(new ReadOnlyHttp.Response(200, Map.of(),
        "{\"values\":[{\"id\":\"refs/tags/v1\",\"displayId\":\"v1\"}],\"isLastPage\":true}".getBytes(StandardCharsets.UTF_8)));
    mvc.perform(post(endpoint).header("Host", "localhost").with(csrf()).contentType("application/json")
        .content("{\"kind\":\"tags\",\"start\":0,\"authentication\":{\"token\":\"bitbucket-token\"}}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.values[0].displayName").value("v1"))
        .andExpect(jsonPath("$.authentication").doesNotExist());
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
