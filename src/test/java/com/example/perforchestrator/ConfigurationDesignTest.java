package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.api.ConfigurationRequestFilter;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class ConfigurationDesignTest {

  @TempDir
  Path directory;

  /**
   * <b>Scenario:</b> Snapshots Cannot Be Changed Without Validation And Persistence
   * <pre>
   * GIVEN ... published catalog and connection snapshots
   * WHEN ... callers try to mutate their nested collections
   * THEN ... mutation is rejected and the catalog hash is unchanged
   * </pre>
   */
  @Test
  @DisplayName("Snapshots Cannot Be Changed Without Validation And Persistence")
  void snapshotsCannotBeChangedWithoutValidationAndPersistence() throws Exception {
    final var catalog = new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1");
    final String hash = catalog.hash();
    assertThatThrownBy(() -> catalog.data().services().clear()).isInstanceOf(
      UnsupportedOperationException.class
    );
    assertThatThrownBy(() ->
      catalog.environment("sandbox").serviceNamespaces().clear()
    ).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() ->
      catalog.service("auth-service").installationBindings().sourceSelectors().clear()
    ).isInstanceOf(UnsupportedOperationException.class);
    final var connections = new RegistryContractTest().config();
    assertThatThrownBy(() ->
      connections.data().imageSources().get("dev").imagePaths().clear()
    ).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> connections.data().credentials().clear()).isInstanceOf(
      UnsupportedOperationException.class
    );
    assertThat(catalog.hash()).isEqualTo(hash);
  }

  /**
   * <b>Scenario:</b> Defensively Copies Caller Owned Collections Including Nested Scenario Values
   * <pre>
   * GIVEN ... configuration constructed from caller-owned nested collections
   * WHEN ... the caller later mutates those collections
   * THEN ... configuration retains its original values and exposes immutable collections
   * </pre>
   */
  @Test
  @DisplayName("Defensively Copies Caller Owned Collections Including Nested Scenario Values")
  void defensivelyCopiesCallerOwnedCollectionsIncludingNestedScenarioValues() {
    final var nested = new LinkedHashMap<String, Object>();
    nested.put("rate", 10);
    final List<Object> steps = new ArrayList<>(List.of(nested));
    final Map<String, Object> defaults = new LinkedHashMap<>(Map.of("steps", steps));
    final var scenario = new Catalog.Scenario("scenario", "1", new ArrayList<>(), defaults);
    nested.put("rate", 99);
    steps.clear();
    defaults.clear();
    assertThat(Json.write(scenario.defaults())).isEqualTo("{\"steps\":[{\"rate\":10}]}");
    final List<?> frozenSteps = (List<?>) scenario.defaults().get("steps");
    assertThatThrownBy(frozenSteps::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Map<?, ?>) frozenSteps.getFirst()).clear()).isInstanceOf(
      UnsupportedOperationException.class
    );
    final Map<String, String> paths = new HashMap<>(Map.of("service", "team/service"));
    final var source = new ConnectionConfig.Source(
      "Dev",
      "server",
      "repo",
      paths,
      false,
      "registry/{image}"
    );
    paths.clear();
    assertThat(source.imagePaths()).containsEntry("service", "team/service");
  }

  /**
   * <b>Scenario:</b> Configuration Put Under Context Path Acquires Write Scope Without Upgrade Deadlock
   * <pre>
   * GIVEN ... a configuration endpoint hosted under a context path
   * WHEN ... a PUT request updates configuration through the request filter
   * THEN ... the write completes within the deadline without lock-upgrade deadlock
   * </pre>
   */
  @Test
  @DisplayName("Configuration Put Under Context Path Acquires Write Scope Without Upgrade Deadlock")
  void configurationPutUnderContextPathAcquiresWriteScopeWithoutUpgradeDeadlock() throws Exception {
    final var access = new ConfigurationAccess();
    final var catalog = new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1");
    final var config = new RuntimeConfiguration(
      catalog,
      new ConnectionConfig(""),
      directory.resolve("config.json").toString(),
      access
    );
    final var filter = new ConfigurationRequestFilter(access);
    // A daemon prevents a locking regression from hanging the test process after its deadline.
    final var executor = Executors.newSingleThreadExecutor((final var task) -> {
      final Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
    try {
      final var completed = executor.submit(() -> {
        final var request = new MockHttpServletRequest("PUT", "/orchestrator/api/v1/configuration");
        request.setContextPath("/orchestrator");
        filter.doFilter(request, new MockHttpServletResponse(), (final var req, final var res) ->
          config.update(config.current())
        );
        return true;
      });
      assertThat(completed.get(3, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * <b>Scenario:</b> Exception In Request Releases Scope For Subsequent Configuration Updates
   * <pre>
   * GIVEN ... a request holding a configuration read scope
   * WHEN ... the request throws and a subsequent writer acquires access
   * THEN ... the scope is released and the writer completes
   * </pre>
   */
  @Test
  @DisplayName("Exception In Request Releases Scope For Subsequent Configuration Updates")
  void exceptionInRequestReleasesScopeForSubsequentConfigurationUpdates() throws Exception {
    final var access = new ConfigurationAccess();
    final var filter = new ConfigurationRequestFilter(access);
    assertThatThrownBy(() ->
      filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/services"),
        new MockHttpServletResponse(),
        (final var req, final var res) -> {
          throw new jakarta.servlet.ServletException("test");
        }
      )
    ).isInstanceOf(jakarta.servlet.ServletException.class);
    final var executor = Executors.newSingleThreadExecutor((final var task) -> {
      final Thread thread = new Thread(task);
      thread.setDaemon(true);
      return thread;
    });
    try {
      assertThat(
        executor
          .submit(() -> {
            try (var scope = access.write()) {
              return true;
            }
          })
          .get(3, TimeUnit.SECONDS)
      ).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }
}
