package com.example.perforchestrator.application;

import static com.example.perforchestrator.domain.Model.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {

  private final Store store;

  public RunService(final Store store) {
    this.store = store;
  }

  @Transactional
  public Run cancel(final String id) {
    store.lock();
    final Run run = store.run(id);
    if (run.state().terminal() || run.state() == State.CANCEL_REQUESTED) return run;
    final Run next = new Run(
      run.id(),
      run.planId(),
      run.environment(),
      State.CANCEL_REQUESTED,
      run.verdict(),
      run.createdAt(),
      Instant.now().toString(),
      run.startedAt(),
      run.measurementStartedAt(),
      run.measurementEndedAt(),
      "Cancellation requested; owned load must stop before completion",
      run.cleanupOutcome(),
      run.metrics(),
      "CANCELLED",
      run.loadOperationId()
    );
    store.update(next);
    store.audit("CANCEL_REQUESTED", id);
    return next;
  }
}
