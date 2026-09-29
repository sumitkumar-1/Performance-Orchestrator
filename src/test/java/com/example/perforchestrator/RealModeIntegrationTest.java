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
      "orchestrator.configuration-file=target/test-real-mode/configuration.json",
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
  void realModeHasNoMockBeansOrSeedDataAndRefusesExecution() throws Exception {
    assertThat(catalog.mode()).isEqualTo("real");
    assertThat(catalog.data().services()).isEmpty();
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
  void realDiscoveryUsesConfiguredHttpAdapterAndModeCannotChangeViaSettings() throws Exception {
    var original = configuration.current();
    var mappings = new RegistryContractTest().config().data();
    try {
      configuration.update(
          new RuntimeConfiguration.Document(original.revision(), original.catalog(), mappings));
      when(credentials.resolve("read"))
          .thenReturn(new CredentialResolver.Secret("user", "password"));
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
      verify(http).get(any(), startsWith("Basic "), eq("application/json"));
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
