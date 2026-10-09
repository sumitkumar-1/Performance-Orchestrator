package com.example.perforchestrator.api;

import com.example.perforchestrator.application.EnvironmentCleanup;
import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.diagnostics.DiagnosticLog;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution/environment-cleanup")
public class EnvironmentCleanupController {

  private static final String PREVIEW = "environmentCleanupPreview";
  private final EnvironmentCleanup cleanup;
  private final DiagnosticLog diagnostics;

  public EnvironmentCleanupController(
    final EnvironmentCleanup cleanup,
    final DiagnosticLog diagnostics
  ) {
    this.cleanup = cleanup;
    this.diagnostics = diagnostics;
  }

  @PostMapping("/preview")
  public Object preview(
    final @RequestBody EnvironmentCleanup.Selection selection,
    final HttpSession session,
    final @RequestHeader(value = "X-Diagnostic-ID", required = false) String trace
  ) {
    session.removeAttribute(PREVIEW);
    try (final var scope = diagnostics.scope(trace)) {
      final var preview = cleanup.preview(selection);
      session.setAttribute(PREVIEW, preview);
      return preview;
    }
  }

  public record Confirmation(String previewId) {}

  @PostMapping
  public Object execute(
    final @RequestBody Confirmation confirmation,
    final HttpSession session,
    final @RequestHeader(value = "X-Diagnostic-ID", required = false) String trace
  ) {
    final EnvironmentCleanup.Preview preview;
    synchronized (session) {
      if (
        !(session.getAttribute(PREVIEW) instanceof final EnvironmentCleanup.Preview saved) ||
        confirmation == null ||
        !saved.id().equals(confirmation.previewId())
      ) throw Problem.conflict("Preview and confirm cleanup in this session first");
      preview = saved;
      session.removeAttribute(PREVIEW);
    }
    try (final var scope = diagnostics.scope(trace)) {
      return Map.of("removed", cleanup.execute(preview));
    }
  }
}
