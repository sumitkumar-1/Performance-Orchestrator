package com.example.perforchestrator;

import static com.example.perforchestrator.domain.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.application.PlanningService;
import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.SimulationImages;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

class PreparationValidationTest {

  @TempDir
  Path root;

  ObjectNode tree;

  @BeforeEach
  void copy() throws Exception {
    final Path source = Path.of("src/main/resources/mocks");
    try (var paths = Files.walk(source)) {
      for (final Path file : paths.toList()) {
        final Path destination = root.resolve(source.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(destination);
        else Files.copy(file, destination);
      }
    }
    tree = (ObjectNode) Json.MAPPER.readTree(Files.readString(root.resolve("catalog.yaml")));
  }

  PlanningService planner() throws Exception {
    Files.writeString(root.resolve("catalog.yaml"), Json.write(tree));
    final var catalog = new Catalog(
      root.resolve("catalog.yaml").toString(),
      "simulation",
      "127.0.0.1"
    );
    final var deployment = mock(Ports.DeploymentGateway.class);
    when(deployment.baseline(any(), any(), any())).thenReturn("not-deployed");
    return new PlanningService(
      catalog,
      new SimulationImages(catalog),
      deployment,
      mock(Store.class)
    );
  }

  Profile profile() {
    return new Profile(
      "Preparation",
      "sandbox",
      List.of(new Selection("auth-service", Action.DEPLOY, new Build("dev", "", "build-103"), "")),
      new Load("smoke", 10, 100, 0, 1, ""),
      60,
      List.of(),
      SimulationCase.SUCCESS
    );
  }

  /**
   * <b>Scenario:</b> Missing Or Unexpected Chart Token Blocks Planning
   * <pre>
   * GIVEN ... a chart with missing or unexpected replacement tokens
   * WHEN ... a run plan is prepared
   * THEN ... planning rejects invalid chart tokens
   * </pre>
   */
  @Test
  @DisplayName("Missing Or Unexpected Chart Token Blocks Planning")
  void missingOrUnexpectedChartTokenBlocksPlanning() throws Exception {
    Files.writeString(
      root.resolve("projects/auth-service/ckp/Chart.yaml"),
      "appVersion: missing\n"
    );
    final var planner = planner();
    assertThatThrownBy(() -> planner.create(null, null, profile())).hasMessageContaining(
      "occurrence"
    );
    Files.writeString(
      root.resolve("projects/auth-service/ckp/Chart.yaml"),
      "appVersion: REPLACE_VERSION\nother: REPLACE_VERSION\n"
    );
    assertThatThrownBy(() -> planner.create(null, null, profile())).hasMessageContaining(
      "Unregistered"
    );
  }

  /**
   * <b>Scenario:</b> Project Traversal Is Rejected
   * <pre>
   * GIVEN ... a service project path escaping the configured root
   * WHEN ... project content is requested
   * THEN ... path traversal is rejected
   * </pre>
   */
  @Test
  @DisplayName("Project Traversal Is Rejected")
  void projectTraversalIsRejected() throws Exception {
    ((ObjectNode) tree.path("services").path("auth-service")).put("projectPath", "..");
    assertThatThrownBy(this::planner).hasMessageContaining("Invalid catalog");
  }

  /**
   * <b>Scenario:</b> Run Order Takes Precedence Over Legacy Dependencies
   * <pre>
   * GIVEN ... services selected in a run order different from legacy dependencies
   * WHEN ... the run is planned
   * THEN ... deployment order follows the selected run order
   * </pre>
   */
  @Test
  @DisplayName("Run Order Takes Precedence Over Legacy Dependencies")
  void runOrderTakesPrecedenceOverLegacyDependencies() throws Exception {
    ((ObjectNode) tree.path("services").path("auth-service"))
      .putArray("dependencies")
      .add("auth-service")
      .add("key-service")
      .add("removed-service");
    final var planner = planner();
    final var original = profile();
    for (final var ids : List.of(
      List.of("auth-service", "key-service"),
      List.of("key-service", "auth-service")
    )) {
      final var ordered = new Profile(
        original.name(),
        original.targetEnvironment(),
        ids
          .stream()
          .map((final var id) ->
            new Selection(id, Action.DEPLOY, new Build("dev", "", "build-103"), "")
          )
          .toList(),
        original.loadGenerator(),
        original.maxRunDurationSeconds(),
        original.thresholds(),
        original.simulationCase()
      );
      assertThat(planner.create(null, null, ordered).services())
        .extracting(PreparedService::serviceId)
        .containsExactlyElementsOf(ids);
    }
    // Legacy references must not implicitly add services to a run either.
    assertThat(planner.create(null, null, original).services())
      .extracting(PreparedService::serviceId)
      .containsExactly("auth-service");
  }

  /**
   * <b>Scenario:</b> Unavailable Source Mapping Fails Closed
   * <pre>
   * GIVEN ... a selected image source without an available mapping
   * WHEN ... a run is prepared
   * THEN ... planning fails instead of choosing another image source
   * </pre>
   */
  @Test
  @DisplayName("Unavailable Source Mapping Fails Closed")
  void unavailableSourceMappingFailsClosed() throws Exception {
    (
      (ObjectNode) tree
        .path("services")
        .path("auth-service")
        .path("installationBindings")
        .path("sourceSelectors")
    ).remove("dev");
    final var planner = planner();
    assertThatThrownBy(() -> planner.create(null, null, profile())).hasMessageContaining("mapping");
  }

  /**
   * <b>Scenario:</b> Unallowed Namespace Blocks Planning
   * <pre>
   * GIVEN ... a service targeting a namespace outside the environment allowlist
   * WHEN ... a run is prepared
   * THEN ... planning rejects the namespace
   * </pre>
   */
  @Test
  @DisplayName("Unallowed Namespace Blocks Planning")
  void unallowedNamespaceBlocksPlanning() throws Exception {
    (
      (ObjectNode) tree
        .path("services")
        .path("auth-service")
        .path("deploymentByEnvironment")
        .path("sandbox")
    ).put("namespace", "not-allowed");
    assertThatThrownBy(this::planner).hasMessageContaining("Invalid catalog");
  }

  /**
   * <b>Scenario:</b> Mismatched Catalog Or Shared Startup Fails Closed
   * <pre>
   * GIVEN ... a catalog mode mismatch or unsafe shared startup configuration
   * WHEN ... the catalog is initialized
   * THEN ... startup validation rejects the configuration
   * </pre>
   */
  @Test
  @DisplayName("Mismatched Catalog Or Shared Startup Fails Closed")
  void mismatchedCatalogOrSharedStartupFailsClosed() {
    assertThatThrownBy(() ->
      new Catalog("src/main/resources/mocks/catalog.yaml", "real", "127.0.0.1")
    ).hasMessageContaining("Invalid catalog");
    assertThatThrownBy(() ->
      new Catalog("src/main/resources/mocks/catalog.yaml", "simulation", "0.0.0.0")
    ).hasMessageContaining("Only loopback");
  }
}
