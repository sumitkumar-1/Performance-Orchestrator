package com.example.perforchestrator.domain;

import static com.example.perforchestrator.domain.Model.*;

import java.util.List;
import java.util.Map;

public final class Ports {
  private Ports() {}

  /** Complete in-memory image catalogs; paginated registries expose their own page contract. */
  public interface ImageDiscovery {
    List<Image> discover(String service, String source, String username);
  }

  /** Resolves one selected version to an immutable image identity. */
  public interface ImageResolver {
    Image resolve(String service, Build build);
  }

  public interface DeploymentGateway {
    String baseline(String cluster, String namespace, String service);

    void deploy(String runId, Plan plan, PreparedService service);

    boolean ready(Plan plan, PreparedService service);
  }

  public interface LoadGeneratorGateway {
    String start(String runId, Plan plan);

    void stop(String runId);

    boolean stopped(String runId);

    Map<String, Double> collect(String runId, Plan plan);
  }

  public interface ArtifactStore {
    void write(String runId, String name, String content);

    String read(String runId, String name);
  }
}
