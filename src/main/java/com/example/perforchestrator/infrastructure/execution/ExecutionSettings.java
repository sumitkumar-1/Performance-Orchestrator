package com.example.perforchestrator.infrastructure.execution;

import java.nio.file.*;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Startup-only cluster/process boundary. Service destinations remain in the runtime catalog. */
@Component
public class ExecutionSettings {
  public final boolean enabled;
  public final String context, expectedServer;
  public final Path workspace;
  public final int timeoutSeconds;
  public final int defaultRunDurationSeconds, maxRunDurationSeconds;
  public final Map<String,String> helmRepositories;
  public final String helmRepositoryConnection,helmRepositoryUsername;
  public ExecutionSettings(Environment env) {
    enabled = env.getProperty("orchestrator.execution.enabled", Boolean.class, false);
    context = env.getProperty("orchestrator.execution.kube-context", "");
    expectedServer = env.getProperty("orchestrator.execution.expected-api-server", "");
    workspace = Path.of(env.getProperty("orchestrator.execution.workspace", "data/real/workspaces")).toAbsolutePath().normalize();
    timeoutSeconds = env.getProperty("orchestrator.execution.command-timeout-seconds", Integer.class, 300);
    if (timeoutSeconds < 10 || timeoutSeconds > 1800) throw new IllegalArgumentException("Execution command timeout must be 10–1800 seconds");
    maxRunDurationSeconds = env.getProperty("orchestrator.execution.max-run-duration-seconds", Integer.class, 28800);
    defaultRunDurationSeconds = env.getProperty("orchestrator.execution.default-run-duration-seconds", Integer.class, 900);
    if (maxRunDurationSeconds < 60 || defaultRunDurationSeconds < 60 || defaultRunDurationSeconds > maxRunDurationSeconds)
      throw new IllegalArgumentException("Execution default run duration must be at least 60 seconds and no greater than max-run-duration-seconds");
    helmRepositoryConnection=env.getProperty("orchestrator.execution.helm-repository-connection","").strip();
    helmRepositoryUsername=env.getProperty("orchestrator.execution.helm-repository-username","").strip();
    helmRepositories=Map.copyOf(Binder.get(env).bind("orchestrator.execution.helm-repositories",
        Bindable.mapOf(String.class,String.class)).orElse(Map.of()));
    helmRepositories.forEach((name,url)->{
      try {
        URI uri=URI.create(url);
        if(!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}") || !"https".equals(uri.getScheme())
            || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
          throw new IllegalArgumentException();
      } catch(IllegalArgumentException e) {
        throw new IllegalArgumentException("Helm repository mappings require valid alias names and HTTPS URLs without credentials, query strings or fragments");
      }
    });
  }
}
