package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.api.ConfigurationRequestFilter;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class ConfigurationDesignTest {
  @TempDir Path directory;

  @Test
  void snapshotsCannotBeChangedWithoutValidationAndPersistence() throws Exception {
    var catalog = new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1");
    String hash = catalog.hash();
    assertThatThrownBy(() -> catalog.data().services().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.environment("sandbox").serviceNamespaces().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> catalog.service("auth-service").installationBindings().sourceSelectors().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    var connections = new RegistryContractTest().config();
    assertThatThrownBy(() -> connections.data().imageSources().get("dev").imagePaths().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> connections.data().credentials().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(catalog.hash()).isEqualTo(hash);
  }

  @Test
  void defensivelyCopiesCallerOwnedCollectionsIncludingNestedScenarioValues() {
    var nested = new LinkedHashMap<String, Object>();
    nested.put("rate", 10);
    List<Object> steps = new ArrayList<>(List.of(nested));
    Map<String, Object> defaults = new LinkedHashMap<>(Map.of("steps", steps));
    var scenario = new Catalog.Scenario("scenario", "1", new ArrayList<>(), defaults);
    nested.put("rate", 99);
    steps.clear();
    defaults.clear();
    assertThat(Json.write(scenario.defaults())).isEqualTo("{\"steps\":[{\"rate\":10}]}");
    List<?> frozenSteps = (List<?>) scenario.defaults().get("steps");
    assertThatThrownBy(frozenSteps::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Map<?, ?>) frozenSteps.getFirst()).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    Map<String, String> paths = new HashMap<>(Map.of("service", "team/service"));
    var source =
        new ConnectionConfig.Source("Dev", "server", "repo", paths, false, "registry/{image}");
    paths.clear();
    assertThat(source.imagePaths()).containsEntry("service", "team/service");
  }

  @Test
  void configurationPutUnderContextPathAcquiresWriteScopeWithoutUpgradeDeadlock() throws Exception {
    var access = new ConfigurationAccess();
    var catalog = new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1");
    var config =
        new RuntimeConfiguration(
            catalog, new ConnectionConfig(""), directory.resolve("config.json").toString(), access);
    var filter = new ConfigurationRequestFilter(access);
    // A daemon prevents a locking regression from hanging the test process after its deadline.
    var executor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task);
              thread.setDaemon(true);
              return thread;
            });
    try {
      var completed =
          executor.submit(
              () -> {
                var request =
                    new MockHttpServletRequest("PUT", "/orchestrator/api/v1/configuration");
                request.setContextPath("/orchestrator");
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> config.update(config.current()));
                return true;
              });
      assertThat(completed.get(3, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void exceptionInRequestReleasesScopeForSubsequentConfigurationUpdates() throws Exception {
    var access = new ConfigurationAccess();
    var filter = new ConfigurationRequestFilter(access);
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/v1/services"),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      throw new jakarta.servlet.ServletException("test");
                    }))
        .isInstanceOf(jakarta.servlet.ServletException.class);
    var executor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task);
              thread.setDaemon(true);
              return thread;
            });
    try {
      assertThat(
              executor
                  .submit(
                      () -> {
                        try (var scope = access.write()) {
                          return true;
                        }
                      })
                  .get(3, TimeUnit.SECONDS))
          .isTrue();
    } finally {
      executor.shutdownNow();
    }
  }
}
