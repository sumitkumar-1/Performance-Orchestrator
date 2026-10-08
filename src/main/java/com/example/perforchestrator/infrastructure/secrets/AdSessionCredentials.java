package com.example.perforchestrator.infrastructure.secrets;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.web.context.request.*;

/** Process-only encrypted AD credentials. Run handles never extend browser-session lifetime. */
public final class AdSessionCredentials {

  private static final String ATTRIBUTE = AdSessionCredentials.class.getName();
  private static final SecretKey KEY = key();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<String, Handle> RUNS = new ConcurrentHashMap<>();
  private static final ThreadLocal<Handle> ACTIVE = new ThreadLocal<>();

  private AdSessionCredentials() {}

  private static SecretKey key() {
    try {
      final var generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (final Exception error) {
      throw new IllegalStateException("Cannot initialize AD session encryption", error);
    }
  }

  public static void remember(
    final HttpSession session,
    final String username,
    final String password,
    final Instant expiry
  ) {
    forget(session);
    final byte[] plain = (username + "\n" + password).getBytes(StandardCharsets.UTF_8);
    final byte[] nonce = new byte[12];
    RANDOM.nextBytes(nonce);
    try {
      final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(128, nonce));
      session.setAttribute(ATTRIBUTE, new Handle(cipher.doFinal(plain), nonce, expiry));
    } catch (final Exception error) {
      throw new IllegalStateException("Cannot retain AD session credentials", error);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  public static void forget(final HttpSession session) {
    if (session != null) session.removeAttribute(ATTRIBUTE);
  }

  private static Handle current() {
    if (ACTIVE.get() != null) return ACTIVE.get();
    final var attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof final ServletRequestAttributes servlet)) return null;
    final var session = servlet.getRequest().getSession(false);
    return session == null ? null : (Handle) session.getAttribute(ATTRIBUTE);
  }

  public static void attach(final String runId) {
    final var handle = current();
    if (handle != null && handle.valid()) RUNS.put(runId, handle);
  }

  public static void detach(final String runId) {
    RUNS.remove(runId);
  }

  public static AutoCloseable scope(final String runId) {
    final var previous = ACTIVE.get();
    final var handle = RUNS.get(runId);
    if (handle != null) ACTIVE.set(handle);
    return () -> {
      if (previous == null) ACTIVE.remove();
      else ACTIVE.set(previous);
    };
  }

  /** Caller must close the result to erase the temporary plaintext bytes. */
  public static Plaintext open() {
    final var handle = current();
    return handle == null ? null : handle.open();
  }

  public static final class Plaintext implements AutoCloseable {

    private final byte[] bytes;

    private Plaintext(final byte[] bytes) {
      this.bytes = bytes;
    }

    public synchronized byte[] response(final boolean password) {
      int separator = 0;
      while (separator < bytes.length && bytes[separator] != '\n') separator++;
      final int start = password ? separator + 1 : 0;
      final int end = password ? bytes.length : separator;
      final byte[] line = Arrays.copyOfRange(bytes, start, end + 1);
      line[line.length - 1] = '\n';
      return line;
    }

    public String redact(final String output) {
      final String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\n", 2);
      return parts.length == 2 ? output.replace(parts[1], "[REDACTED]") : output;
    }

    @Override
    public synchronized void close() {
      Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }

  private static final class Handle implements HttpSessionBindingListener {

    private final byte[] ciphertext;
    private final byte[] nonce;
    private final Instant expiry;
    private boolean destroyed;

    private Handle(final byte[] ciphertext, final byte[] nonce, final Instant expiry) {
      this.ciphertext = ciphertext;
      this.nonce = nonce;
      this.expiry = expiry;
    }

    private synchronized boolean valid() {
      if (!Instant.now().isBefore(expiry)) destroy();
      return !destroyed;
    }

    private synchronized Plaintext open() {
      if (!valid()) return null;
      try {
        final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(128, nonce));
        return new Plaintext(cipher.doFinal(ciphertext));
      } catch (final Exception error) {
        destroy();
        return null;
      }
    }

    private synchronized void destroy() {
      destroyed = true;
      Arrays.fill(ciphertext, (byte) 0);
      RUNS.values().removeIf((final var value) -> value == this);
    }

    @Override
    public void valueUnbound(final HttpSessionBindingEvent event) {
      destroy();
    }

    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }
}
