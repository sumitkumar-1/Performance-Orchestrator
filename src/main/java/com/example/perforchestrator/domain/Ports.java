package com.example.perforchestrator.domain;

import static com.example.perforchestrator.domain.Model.*;

import java.util.List;
import java.util.Map;

public final class Ports {

  private Ports() {}

  /** Complete in-memory image catalogs; paginated registries expose their own page contract. */
  public interface ImageDiscovery {
    List<Image> discover(final String service, final String source, final String username);
  }

  /** Resolves one selected version to an immutable image identity. */
  public interface ImageResolver {
    Image resolve(final String service, final Build build);
  }

  public interface DeploymentGateway {
    String baseline(final String cluster, final String namespace, final String service);

    void deploy(final String runId, final Plan plan, final PreparedService service);

    boolean ready(final Plan plan, final PreparedService service);
  }

  public interface LoadGeneratorGateway {
    String start(final String runId, final Plan plan);

    void stop(final String runId);

    boolean stopped(final String runId);

    Map<String, Double> collect(final String runId, final Plan plan);
  }

  public interface ArtifactStore {
    void write(final String runId, final String name, final String content);

    String read(final String runId, final String name);
  }
}
