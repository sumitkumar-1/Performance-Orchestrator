package com.example.perforchestrator.infrastructure.execution;

import java.nio.file.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Startup-only cluster/process boundary. Service destinations remain in the runtime catalog. */
@Component
public class ExecutionSettings {
  public final boolean enabled;
  public final String context, expectedServer;
  public final Path workspace;
  public final int timeoutSeconds;
  public ExecutionSettings(Environment env) {
    enabled = env.getProperty("orchestrator.execution.enabled", Boolean.class, false);
    context = env.getProperty("orchestrator.execution.kube-context", "");
    expectedServer = env.getProperty("orchestrator.execution.expected-api-server", "");
    workspace = Path.of(env.getProperty("orchestrator.execution.workspace", "data/real/workspaces")).toAbsolutePath().normalize();
    timeoutSeconds = env.getProperty("orchestrator.execution.command-timeout-seconds", Integer.class, 300);
    if (timeoutSeconds < 10 || timeoutSeconds > 1800) throw new IllegalArgumentException("Execution command timeout must be 10–1800 seconds");
  }
}
