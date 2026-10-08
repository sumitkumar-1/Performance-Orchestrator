package com.example.perforchestrator.domain;

import static com.example.perforchestrator.domain.Model.*;

import java.util.List;
import java.util.Map;

public final class Ports {

  private Ports() {}

  public interface ArtifactStore {
    void write(final String runId, final String name, final String content);

    String read(final String runId, final String name);
  }
}
