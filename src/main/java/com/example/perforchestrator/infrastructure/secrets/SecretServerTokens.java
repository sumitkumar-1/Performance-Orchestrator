package com.example.perforchestrator.infrastructure.secrets;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.Json;
import com.example.perforchestrator.infrastructure.registry.*;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

/**
 * Tokens stay server-side, scoped to a browser session or an explicit configured global provider.
 */
@Component
public class SecretServerTokens {
  private static final String PREFIX = SecretServerTokens.class.getName() + ".";
  private final ConnectionConfig config;
  private final ReadOnlyHttp http;
  private final Function<String, String> environment;
  private final Clock clock;

  @Autowired
  public SecretServerTokens(ConnectionConfig config, ReadOnlyHttp http) {
    this(config, http, System::getenv, Clock.systemUTC());
  }

  public SecretServerTokens(
      ConnectionConfig config,
      ReadOnlyHttp http,
      Function<String, String> environment,
      Clock clock) {
    this.config = config;
    this.http = http;
    this.environment = environment;
    this.clock = clock;
  }

  private ConnectionConfig.SecretServer server(String id) {
    var server = config.data().secretServers().get(id);
    if (server == null) throw Problem.missing("Secret Server connection");
    return server;
  }

  public Map<String, Object> signIn(
      String id, String username, String password, HttpSession session) {
    var server = server(id);
    if (!server.mode().equals("portal"))
      throw Problem.invalid(
          "connection", "This connection uses administrator-provided authentication");
    if (username == null
        || username.isBlank()
        || username.length() > 512
        || password == null
        || password.isEmpty()
        || password.length() > 4096)
      throw Problem.invalid(
          "credentials", "Username and password are required and must fit the configured limits");
    // Reauthentication never retains a previous token after a failed exchange.
    session.removeAttribute(PREFIX + id);
    String form =
        "grant_type=password&username=" + encode(username.trim()) + "&password=" + encode(password);
    var response = http.tokenForm(URI.create(server.tokenUrl()), form);
    if (response.status() < 200 || response.status() >= 300)
      throw new Problem(
          response.status() == 400 || response.status() == 401 || response.status() == 403
              ? 401
              : 502,
          "SECRET_AUTH_FAILED",
          "credentials",
          "Secret Server authentication failed; verify credentials and connection configuration");
    try {
      var body = Json.MAPPER.readTree(response.body());
      String value = body.path("access_token").asText("");
      String type = body.path("token_type").asText("Bearer");
      long seconds = body.path("expires_in").asLong(0);
      if (!type.equalsIgnoreCase("Bearer") || !validToken(value) || seconds <= 0)
        throw new IllegalArgumentException();
      Instant expires = clock.instant().plusSeconds(seconds);
      session.setAttribute(PREFIX + id, new Token(value, expires, config.generation()));
      return status(id, session);
    } catch (Exception error) {
      throw new Problem(
          502,
          "SECRET_TOKEN_SCHEMA",
          "connection",
          "Token response must contain a Bearer access_token and positive expires_in");
    }
  }

  public Map<String, Object> status(String id, HttpSession session) {
    var server = server(id);
    if (!server.mode().equals("portal"))
      return Map.of("mode", server.mode(), "state", "ADMINISTRATOR_PROVIDED");
    Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
    if (token != null
        && (token.generation != config.generation() || !clock.instant().isBefore(token.expires))) {
      session.removeAttribute(PREFIX + id);
      token = null;
    }
    return token == null
        ? Map.of("mode", "portal", "state", "SIGN_IN_REQUIRED")
        : Map.of("mode", "portal", "state", "AUTHENTICATED", "expiresAt", token.expires.toString());
  }

  public Map<String, Object> statuses(HttpSession session) {
    Map<String, Object> result = new TreeMap<>();
    config.data().secretServers().keySet().forEach(id -> result.put(id, status(id, session)));
    return result;
  }

  public void signOut(String id, HttpSession session) {
    server(id);
    if (session != null) session.removeAttribute(PREFIX + id);
  }

  public String authorization(String id) {
    var server = server(id);
    String value;
    switch (server.mode()) {
      case "portal" -> {
        HttpSession session = currentSession();
        status(id, session); // Evict expired token before use.
        Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
        if (token == null) throw signInRequired();
        value = token.value;
      }
      case "file" -> {
        try (var input = Files.newInputStream(Path.of(server.bearerTokenFile()))) {
          byte[] bytes = input.readNBytes(65537);
          if (bytes.length > 65536) throw new IllegalArgumentException();
          value = new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (Exception error) {
          throw unavailable();
        }
      }
      default -> value = environment.apply(server.bearerTokenEnvironmentVariable());
    }
    if (!validToken(value)) throw unavailable();
    return "Bearer " + value;
  }

  public void rejected(String id) {
    if (server(id).mode().equals("portal")) {
      signOut(id, currentSession());
      throw signInRequired();
    }
  }

  private static HttpSession currentSession() {
    var attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof ServletRequestAttributes servlet
        ? servlet.getRequest().getSession(false)
        : null;
  }

  private static boolean validToken(String value) {
    return value != null && value.length() <= 65536 && value.matches("[A-Za-z0-9._~+/=-]+");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Problem signInRequired() {
    return new Problem(
        401,
        "SECRET_SIGN_IN_REQUIRED",
        "secretServer",
        "Sign in to Secret Server in Connections & catalog, then retry");
  }

  private static Problem unavailable() {
    return new Problem(
        503,
        "CREDENTIAL_UNAVAILABLE",
        "secretServer",
        "Configured server-side token is unavailable or invalid");
  }

  private static final class Token {
    final String value;
    final Instant expires;

    final long generation;

    Token(String value, Instant expires, long generation) {
      this.generation = generation;
      this.value = value;
      this.expires = expires;
    }

    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }
}
