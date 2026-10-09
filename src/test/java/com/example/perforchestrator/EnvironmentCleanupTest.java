package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.api.EnvironmentCleanupController;
import com.example.perforchestrator.application.EnvironmentCleanup;
import com.example.perforchestrator.infrastructure.config.Catalog;
import com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;

class EnvironmentCleanupTest {

  private final Catalog catalog = mock(Catalog.class);
  private final HelmExecution helm = mock(HelmExecution.class);
  private final Store store = mock(Store.class);
  private final Map<String, Object> target = Map.of(
    "context",
    "perf3-nvan",
    "server",
    "https://cluster.invalid"
  );
  private EnvironmentCleanup cleanup;

  @BeforeEach
  void givenConfiguredEnvironment() {
    when(catalog.selectedEnvironment("perf3")).thenReturn("perf3");
    when(catalog.environment("perf3")).thenReturn(
      new Catalog.Environment("Performance", "cluster", null)
    );
    when(catalog.hash()).thenReturn("catalog-hash");
    for (final String id : List.of("service", "load")) {
      when(catalog.service(id)).thenReturn(
        new Catalog.Service(
          "projects/" + id,
          Map.of(),
          new Catalog.Destination("ns", id, List.of("ckp/values.yaml"))
        )
      );
      when(helm.baseline(target, "ns", id)).thenReturn("existing-" + id);
    }
    when(helm.target("perf3")).thenReturn(target);
    when(store.environmentAvailable("cluster/perf3")).thenReturn(true);
    cleanup = new EnvironmentCleanup(
      catalog,
      helm,
      new ExecutionSettings(
        new MockEnvironment().withProperty("orchestrator.execution.enabled", "true")
      ),
      store
    );
  }

  private EnvironmentCleanup.Preview preview() {
    return cleanup.preview(new EnvironmentCleanup.Selection("perf3", List.of("service", "load")));
  }

  /**
   * <b>Scenario:</b> Explicit cleanup includes existing selected releases only
   * <pre>
   * GIVEN ... selected service and load releases that already exist
   * WHEN ... the user previews and confirms cleanup
   * THEN ... preview is read-only and cleanup removes exactly that set in reverse order
   * </pre>
   */
  @Test
  @DisplayName(
    "Given selected existing releases, when confirmed, then clean only the previewed destinations"
  )
  void confirmedPreviewCleansOnlySelectedReleases() {
    final var preview = preview();
    verify(helm, never()).cleanupApprovedRelease(any(), any(), any(), any());
    assertThat(cleanup.execute(preview)).containsExactly("ns/load", "ns/service");
    final var order = inOrder(helm);
    order.verify(helm).cleanupApprovedRelease(target, "ns", "load", "existing-load");
    order.verify(helm).cleanupApprovedRelease(target, "ns", "service", "existing-service");
  }

  /**
   * <b>Scenario:</b> Release drift or an active run invalidates cleanup
   * <pre>
   * GIVEN ... a confirmed preview
   * WHEN ... any selected release changes or a run reserves the environment
   * THEN ... no release is uninstalled and a fresh review is required
   * </pre>
   */
  @Test
  @DisplayName(
    "Given changed releases or an active run, when cleaning, then refuse before any uninstall"
  )
  void driftAndActiveRunsBlockCleanup() {
    final var preview = preview();
    when(helm.baseline(target, "ns", "load")).thenReturn("changed");
    assertThatThrownBy(() -> cleanup.execute(preview)).hasMessageContaining("Release changed");
    verify(helm, never()).cleanupApprovedRelease(any(), any(), any(), any());
    when(store.environmentAvailable("cluster/perf3")).thenReturn(false);
    assertThatThrownBy(() -> cleanup.execute(preview)).hasMessageContaining("reserved");
    assertThatThrownBy(this::preview).hasMessageContaining("reserved");
  }

  /**
   * <b>Scenario:</b> Expired previews and changed configuration do not authorize deletion
   * <pre>
   * GIVEN ... a preview older than five minutes or changed catalog configuration
   * WHEN ... cleanup is requested
   * THEN ... cleanup is rejected without running uninstall
   * </pre>
   */
  @Test
  @DisplayName("Given an expired or stale preview, when cleaning, then require a fresh preview")
  void stalePreviewCannotDelete() {
    final var preview = preview();
    final var expired = new EnvironmentCleanup.Preview(
      preview.id(),
      preview.environment(),
      preview.scope(),
      preview.catalogHash(),
      preview.target(),
      Instant.now().minusSeconds(1),
      preview.releases()
    );
    assertThatThrownBy(() -> cleanup.execute(expired)).hasMessageContaining("expired");
    when(catalog.hash()).thenReturn("changed");
    assertThatThrownBy(() -> cleanup.execute(preview)).hasMessageContaining(
      "Configuration changed"
    );
    verify(helm, never()).cleanupApprovedRelease(any(), any(), any(), any());
  }

  /**
   * <b>Scenario:</b> Failed uninstall stops cleanup without hiding partial results
   * <pre>
   * GIVEN ... a load was removed and the next service uninstall fails
   * WHEN ... cleanup reports the failure
   * THEN ... it identifies the remaining release and does not claim preparation started
   * </pre>
   */
  @Test
  @DisplayName(
    "Given partial cleanup failure, when reporting it, then identify the failed release and prior removals"
  )
  void failureReportsPartialCleanup() {
    final var preview = preview();
    doThrow(new IllegalStateException("sensitive detail"))
      .when(helm)
      .cleanupApprovedRelease(target, "ns", "service", "existing-service");
    assertThatThrownBy(() -> cleanup.execute(preview))
      .hasMessageContaining("ns/service", "1 earlier release(s)", "preparation has not started")
      .hasMessageNotContaining("sensitive detail");
    verify(store).audit("ENVIRONMENT_RELEASE_CLEANED", "perf3/ns/load");
  }

  /**
   * <b>Scenario:</b> Confirmation belongs to one browser session and is consumed once
   * <pre>
   * GIVEN ... a preview stored in the owner's browser session
   * WHEN ... another session or a repeated confirmation attempts cleanup
   * THEN ... neither can reuse the preview to delete releases
   * </pre>
   */
  @Test
  @DisplayName("Given session-bound confirmation, when reused elsewhere or twice, then reject it")
  void confirmationIsSessionBoundAndSingleUse() {
    final var controller = new EnvironmentCleanupController(cleanup, mock(DiagnosticLog.class));
    final var owner = new MockHttpSession();
    final var preview = (EnvironmentCleanup.Preview) controller.preview(
      new EnvironmentCleanup.Selection("perf3", List.of("service", "load")),
      owner,
      null
    );
    final var confirmation = new EnvironmentCleanupController.Confirmation(preview.id());
    assertThatThrownBy(() ->
      controller.execute(confirmation, new MockHttpSession(), null)
    ).hasMessageContaining("this session");
    controller.execute(confirmation, owner, null);
    assertThatThrownBy(() -> controller.execute(confirmation, owner, null)).hasMessageContaining(
      "this session"
    );
    verify(helm, times(1)).cleanupApprovedRelease(target, "ns", "service", "existing-service");
  }
}
