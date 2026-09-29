package com.example.perforchestrator.infrastructure.secrets;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

/** Browser-session credentials encrypted with a process-only key. Nothing is serialized to disk. */
@Component
public final class ConnectionSessions {
  private static final String PREFIX = ConnectionSessions.class.getName() + ".";
  private final ConnectionConfig config;
  private final CredentialResolver resolver;
  private final Clock clock;
  private final SecretKey key;
  private final Map<UUID, Entry> live = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public ConnectionSessions(ConnectionConfig config, CredentialResolver resolver) {
    this(config, resolver, Clock.systemUTC());
  }
  public ConnectionSessions(ConnectionConfig config, CredentialResolver resolver, Clock clock) {
    this.config = config; this.resolver = resolver; this.clock = clock;
    try { var generator = KeyGenerator.getInstance("AES"); generator.init(256); key = generator.generateKey(); }
    catch (Exception e) { throw new IllegalStateException("Cannot initialize session encryption"); }
  }
  private record Settings(String mode, String credentialRef) {}
  private Settings settings(String kind, String id) {
    if ("artifactory".equals(kind)) {
      var c = config.data().artifactory().get(id);
      if (c != null) return new Settings(c.mode(), c.credentialRef());
    } else if ("bitbucket".equals(kind)) {
      var c = config.data().bitbucket().get(id);
      if (c != null) return new Settings(c.mode(), c.credentialRef());
    }
    throw Problem.invalid("connection", "Unknown configured connection");
  }
  private String attribute(String kind, String id) { return PREFIX + kind + "." + id; }
  private Entry entry(String kind, String id, HttpSession session) {
    if (session == null) return null;
    var entry = (Entry) session.getAttribute(attribute(kind, id));
    if (entry != null && !entry.valid()) { session.removeAttribute(attribute(kind, id)); entry = null; }
    return entry;
  }
  public Map<String, Object> status(String kind, String id, HttpSession session) {
    var settings = settings(kind, id);
    var entry = entry(kind, id, session);
    if (settings.mode().equals("secret-server")) return Map.of("state", "REFERENCE", "mode", settings.mode());
    return entry == null ? Map.of("state", "SIGN_IN_REQUIRED", "mode", settings.mode())
        : Map.of("state", "CREDENTIALS_AVAILABLE", "mode", settings.mode(), "expiresAt", entry.expires.toString());
  }
  public Map<String, Object> remember(String kind, String id, RequestAuthentication input, int lifetimeSeconds, HttpSession session) {
    var settings = settings(kind, id);
    forget(kind, id, session);
    if (settings.mode().equals("secret-server")) throw Problem.invalid("authentication", "This connection resolves its configured secret reference");
    if (lifetimeSeconds < 60 || lifetimeSeconds > 28800) throw Problem.invalid("lifetimeSeconds", "Session duration must be between 1 minute and 8 hours");
    String authorization = RequestAuthentication.authorization(settings.mode(), settings.credentialRef(), input, resolver);
    byte[] plain = authorization.getBytes(StandardCharsets.UTF_8);
    byte[] nonce = new byte[12]; random.nextBytes(nonce);
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      var entry = new Entry(cipher.doFinal(plain), nonce, clock.instant().plusSeconds(lifetimeSeconds), config.generation());
      live.put(entry.id, entry);
      session.setAttribute(attribute(kind, id), entry);
      return status(kind, id, session);
    } catch (Exception e) { throw new IllegalStateException("Cannot retain connection credentials"); }
    finally { Arrays.fill(plain, (byte) 0); }
  }
  public void forget(String kind, String id, HttpSession session) {
    settings(kind, id);
    if (session != null) session.removeAttribute(attribute(kind, id));
  }
  public String authorization(String kind, String id, RequestAuthentication oneRequestInput) {
    var settings = settings(kind, id);
    // Retain the request-only API for older clients; it never populates a session.
    if (settings.mode().equals("secret-server") || oneRequestInput != null)
      return RequestAuthentication.authorization(settings.mode(), settings.credentialRef(), oneRequestInput, resolver);
    var entry = entry(kind, id, currentSession());
    if (entry == null) throw new Problem(401, "CONNECTION_SIGN_IN_REQUIRED", "authentication", "Sign in to this connection, then retry");
    return entry.authorization();
  }
  public void rejected(String kind, String id) { forget(kind, id, currentSession()); }
  private HttpSession currentSession() {
    var attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof ServletRequestAttributes request ? request.getRequest().getSession(false) : null;
  }
  @Scheduled(fixedDelay = 10000)
  public void purgeExpired() { live.values().forEach(entry -> { if (!entry.valid()) entry.destroy(); }); }
  @PreDestroy public void close() { live.values().forEach(Entry::destroy); }

  private final class Entry implements HttpSessionBindingListener {
    final UUID id = UUID.randomUUID();
    final Instant expires;
    final long generation;
    final byte[] encrypted, nonce;
    boolean destroyed;
    Entry(byte[] encrypted, byte[] nonce, Instant expires, long generation) {
      this.encrypted = encrypted; this.nonce = nonce; this.expires = expires; this.generation = generation;
    }
    synchronized boolean valid() { return !destroyed && generation == config.generation() && clock.instant().isBefore(expires); }
    synchronized String authorization() {
      if (!valid()) { destroy(); throw new Problem(401, "CONNECTION_SIGN_IN_REQUIRED", "authentication", "Connection session expired; sign in again"); }
      byte[] plain = null;
      try {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        plain = cipher.doFinal(encrypted);
        return new String(plain, StandardCharsets.UTF_8);
      } catch (Exception e) { destroy(); throw new IllegalStateException("Connection session is unavailable"); }
      finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    synchronized void destroy() { destroyed = true; Arrays.fill(encrypted, (byte) 0); Arrays.fill(nonce, (byte) 0); live.remove(id); }
    @Override public void valueUnbound(HttpSessionBindingEvent event) { destroy(); }
    @Override public String toString() { return "[REDACTED connection session]"; }
  }
}
