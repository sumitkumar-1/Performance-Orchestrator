package com.example.perforchestrator.infrastructure.secrets;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.registry.*;
import jakarta.servlet.http.HttpSession;
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
  public SecretServerTokens(final ConnectionConfig config, final ReadOnlyHttp http) {
    this(config, http, System::getenv, Clock.systemUTC());
  }

  public SecretServerTokens(
    final ConnectionConfig config,
    final ReadOnlyHttp http,
    final Function<String, String> environment,
    final Clock clock
  ) {
    this.config = config;
    this.http = http;
    this.environment = environment;
    this.clock = clock;
  }

  private ConnectionConfig.SecretServer server(final String id) {
    final var server = config.data().secretServers().get(id);
    if (server == null) throw Problem.missing("Secret Server connection");
    return server;
  }

  private static boolean interactive(final String mode) {
    return mode.equals("token") || mode.equals("interactive");
  }

  public Map<String, Object> login(
    final String id,
    final String username,
    final String password,
    final HttpSession session
  ) {
    final var server = server(id);
    if (!server.mode().equals("interactive")) throw Problem.invalid(
      "secretServer",
      "Enable AD or token authentication for this vault first"
    );
    if (
      username == null ||
      username.isBlank() ||
      username.length() > 100 ||
      username.chars().anyMatch(Character::isISOControl) ||
      password == null ||
      password.isEmpty() ||
      password.length() > 8192 ||
      password.contains("\n") ||
      password.contains("\r")
    ) throw Problem.invalid("credentials", "Enter an AD username and password");
    AdSessionCredentials.forget(session);
    session.removeAttribute(PREFIX + id);
    final var response = http.tokenForm(
      java.net.URI.create(server.tokenUrl()),
      username.trim(),
      password
    );
    if (response.status() < 200 || response.status() >= 300) throw new Problem(
      401,
      "SECRET_AD_LOGIN_FAILED",
      "secretServer",
      "Secret Server AD sign-in failed (HTTP " +
        response.status() +
        "). Check credentials and whether this vault permits the password grant. You can also provide a token."
    );
    try {
      final var body = com.example.perforchestrator.infrastructure.config.Json.MAPPER.readTree(
        response.body()
      );
      final String value = body.path("access_token").asText();
      final long expiry = body.path("expires_in").asLong(0);
      if (
        !validToken(value) ||
        expiry <= 0 ||
        !body.path("token_type").asText("Bearer").equalsIgnoreCase("Bearer")
      ) throw new IllegalArgumentException();
      session.setAttribute(
        PREFIX + id,
        new Token(
          value,
          clock.instant().plusSeconds(Math.min(expiry, 28800)),
          config.generation(),
          username.trim(),
          config,
          clock
        )
      );
      AdSessionCredentials.remember(
        session,
        username.trim(),
        password,
        clock.instant().plusSeconds(Math.min(expiry, 28800))
      );
      return status(id, session);
    } catch (final Exception error) {
      throw new Problem(
        502,
        "SECRET_TOKEN_SCHEMA",
        "secretServer",
        "Token response must include a Bearer access_token and positive expires_in; no session was created"
      );
    }
  }

  public static String currentActor() {
    final var session = currentSession();
    if (session == null) return "local-developer";
    final var names = session.getAttributeNames();
    while (names.hasMoreElements()) {
      final String name = names.nextElement();
      final Object entry = session.getAttribute(name);
      if (
        name.startsWith(PREFIX) &&
        entry instanceof final Token token &&
        token.generation == token.config.generation() &&
        token.clock.instant().isBefore(token.expires) &&
        token.username != null
      ) return token.username;
    }
    return "local-developer";
  }

  public Map<String, Object> useToken(
    final String id,
    final String value,
    final long expiresInSeconds,
    final HttpSession session
  ) {
    if (!interactive(server(id).mode())) throw Problem.invalid(
      "connection",
      "Select token authentication first"
    );
    AdSessionCredentials.forget(session);
    session.removeAttribute(PREFIX + id);
    RequestAuthentication.bearer(value);
    if (expiresInSeconds < 1 || expiresInSeconds > 28800) throw Problem.invalid(
      "expiresInSeconds",
      "Provide remaining token lifetime, at most 8 hours"
    );
    session.setAttribute(
      PREFIX + id,
      new Token(
        value,
        clock.instant().plusSeconds(expiresInSeconds),
        config.generation(),
        null,
        config,
        clock
      )
    );
    return status(id, session);
  }

  public Map<String, Object> status(final String id, final HttpSession session) {
    final var server = server(id);
    if (!interactive(server.mode())) return Map.of(
      "mode",
      server.mode(),
      "state",
      "ADMINISTRATOR_PROVIDED"
    );
    Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
    if (
      token != null &&
      (token.generation != config.generation() || !clock.instant().isBefore(token.expires))
    ) {
      session.removeAttribute(PREFIX + id);
      AdSessionCredentials.forget(session);
      token = null;
    }
    return token == null
      ? Map.of("mode", server.mode(), "state", "SIGN_IN_REQUIRED")
      : Map.of(
          "mode",
          server.mode(),
          "state",
          token.username == null ? "TOKEN_PROVIDED" : "AUTHENTICATED",
          "expiresAt",
          token.expires.toString(),
          "username",
          token.username == null ? "" : token.username
        );
  }

  public Map<String, Object> statuses(final HttpSession session) {
    final Map<String, Object> result = new TreeMap<>();
    config
      .data()
      .secretServers()
      .keySet()
      .forEach((final var id) -> result.put(id, status(id, session)));
    return result;
  }

  public void signOut(final String id, final HttpSession session) {
    server(id);
    AdSessionCredentials.forget(session);
    if (session != null) session.removeAttribute(PREFIX + id);
  }

  public String authorization(final String id) {
    return authorization(id, (final var ref) -> {
      throw unavailable();
    });
  }

  public String authorization(
    final String id,
    final Function<String, CredentialResolver.Secret> resolver
  ) {
    final var server = server(id);
    final String value;
    switch (server.mode()) {
      case "secret-server" -> {
        final var credential = resolver.apply(server.credentialRef());
        if (!credential.token()) throw unavailable();
        value = credential.password();
      }
      case "token", "interactive" -> {
        final HttpSession session = currentSession();
        status(id, session); // Evict expired token before use.
        final Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
        if (token == null) throw signInRequired(id);
        value = token.value;
      }
      case "file" -> {
        try (var input = Files.newInputStream(Path.of(server.bearerTokenFile()))) {
          final byte[] bytes = input.readNBytes(65537);
          if (bytes.length > 65536) throw new IllegalArgumentException();
          value = new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (final Exception error) {
          throw unavailable();
        }
      }
      default -> value = environment.apply(server.bearerTokenEnvironmentVariable());
    }
    if (!validToken(value)) throw unavailable();
    return "Bearer " + value;
  }

  public void rejected(final String id) {
    if (interactive(server(id).mode())) {
      signOut(id, currentSession());
      throw new Problem(
        401,
        "SECRET_TOKEN_REJECTED",
        "secretServer",
        "Secret Server connection '" +
          id +
          "' rejected the supplied Bearer token (HTTP 401) while reading a secret. " +
          "The local token was cleared. Sign in with a valid REST API access token for this vault; " +
          "check token expiry and the configured vault URL. Bitbucket/Artifactory has not been contacted."
      );
    }
  }

  private static HttpSession currentSession() {
    final var attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof final ServletRequestAttributes servlet
      ? servlet.getRequest().getSession(false)
      : null;
  }

  private static boolean validToken(final String value) {
    return value != null && value.length() <= 65536 && value.matches("[A-Za-z0-9._~+/=-]+");
  }

  private static Problem signInRequired(final String id) {
    return new Problem(
      401,
      "SECRET_SIGN_IN_REQUIRED",
      "secretServer",
      "No active token for Secret Server connection '" +
        id +
        "' in this browser session. " +
        "Sign in to this connection in Connections & catalog, then retry. " +
        "Tokens expire and are cleared on restart, sign-out or connection configuration changes; use the same browser and host."
    );
  }

  private static Problem unavailable() {
    return new Problem(
      503,
      "CREDENTIAL_UNAVAILABLE",
      "secretServer",
      "Configured server-side token is unavailable or invalid"
    );
  }

  private static final class Token {

    final String value;
    final Instant expires;

    final long generation;

    final String username;
    final ConnectionConfig config;
    final Clock clock;

    Token(
      final String value,
      final Instant expires,
      final long generation,
      final String username,
      final ConnectionConfig config,
      final Clock clock
    ) {
      this.username = username;
      this.config = config;
      this.clock = clock;
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
