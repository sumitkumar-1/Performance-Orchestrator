package com.example.perforchestrator.infrastructure.config;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

/** Coordinates whole operations; independent of HTTP and configuration storage. */
@Component
public final class ConfigurationAccess {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  public Scope read() {
    return acquire(lock.readLock());
  }

  public Scope write() {
    return acquire(lock.writeLock());
  }

  private Scope acquire(final Lock selected) {
    selected.lock();
    return selected::unlock;
  }
}
