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

  private static boolean interactive(String mode) { return mode.equals("token") || mode.equals("interactive"); }

  public Map<String,Object> login(String id, String username, String password, HttpSession session) {
    var server=server(id);
    if(!server.mode().equals("interactive"))throw Problem.invalid("secretServer","Enable AD or token authentication for this vault first");
    if(username==null || username.isBlank() || username.length()>100 || username.chars().anyMatch(Character::isISOControl)
        || password==null || password.isEmpty() || password.length()>8192)throw Problem.invalid("credentials","Enter an AD username and password");
    session.removeAttribute(PREFIX+id);
    var response=http.tokenForm(java.net.URI.create(server.tokenUrl()),username.trim(),password);
    if(response.status()<200 || response.status()>=300)throw new Problem(401,"SECRET_AD_LOGIN_FAILED","secretServer",
        "Secret Server AD sign-in failed (HTTP "+response.status()+"). Check credentials and whether this vault permits the password grant. You can also provide a token.");
    try {
      var body=com.example.perforchestrator.infrastructure.config.Json.MAPPER.readTree(response.body());
      String value=body.path("access_token").asText();long expiry=body.path("expires_in").asLong(0);
      if(!validToken(value) || expiry<=0 || !body.path("token_type").asText("Bearer").equalsIgnoreCase("Bearer"))throw new IllegalArgumentException();
      session.setAttribute(PREFIX+id,new Token(value,clock.instant().plusSeconds(Math.min(expiry,28800)),config.generation(),username.trim(),config,clock));
      return status(id,session);
    } catch(Exception error) {throw new Problem(502,"SECRET_TOKEN_SCHEMA","secretServer","Token response must include a Bearer access_token and positive expires_in; no session was created");}
  }

  public static String currentActor() {
    var session=currentSession(); if(session==null)return "local-developer";
    var names=session.getAttributeNames();
    while(names.hasMoreElements()) {
      String name=names.nextElement(); Object entry=session.getAttribute(name);
      if(name.startsWith(PREFIX) && entry instanceof Token token && token.generation==token.config.generation()
          && token.clock.instant().isBefore(token.expires) && token.username!=null)return token.username;
    }
    return "local-developer";
  }

  public Map<String, Object> useToken(String id, String value, long expiresInSeconds, HttpSession session) {
    if (!interactive(server(id).mode())) throw Problem.invalid("connection", "Select token authentication first");
    session.removeAttribute(PREFIX + id);
    RequestAuthentication.bearer(value);
    if (expiresInSeconds < 1 || expiresInSeconds > 28800)
      throw Problem.invalid("expiresInSeconds", "Provide remaining token lifetime, at most 8 hours");
    session.setAttribute(PREFIX + id, new Token(value, clock.instant().plusSeconds(expiresInSeconds), config.generation(), null, config, clock));
    return status(id, session);
  }

  public Map<String, Object> status(String id, HttpSession session) {
    var server = server(id);
    if (!interactive(server.mode()))
      return Map.of("mode", server.mode(), "state", "ADMINISTRATOR_PROVIDED");
    Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
    if (token != null
        && (token.generation != config.generation() || !clock.instant().isBefore(token.expires))) {
      session.removeAttribute(PREFIX + id);
      token = null;
    }
    return token == null
        ? Map.of("mode", server.mode(), "state", "SIGN_IN_REQUIRED")
        : Map.of("mode", server.mode(), "state", token.username == null ? "TOKEN_PROVIDED" : "AUTHENTICATED", "expiresAt", token.expires.toString(), "username", token.username == null ? "" : token.username);
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
    return authorization(id, ref -> { throw unavailable(); });
  }

  public String authorization(String id, Function<String, CredentialResolver.Secret> resolver) {
    var server = server(id);
    String value;
    switch (server.mode()) {
      case "secret-server" -> {
        var credential = resolver.apply(server.credentialRef());
        if (!credential.token()) throw unavailable();
        value = credential.password();
      }
      case "token", "interactive" -> {
        HttpSession session = currentSession();
        status(id, session); // Evict expired token before use.
        Token token = session == null ? null : (Token) session.getAttribute(PREFIX + id);
        if (token == null) throw signInRequired(id);
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
    if (interactive(server(id).mode())) {
      signOut(id, currentSession());
      throw new Problem(401, "SECRET_TOKEN_REJECTED", "secretServer",
          "Secret Server connection '" + id + "' rejected the supplied Bearer token (HTTP 401) while reading a secret. "
              + "The local token was cleared. Sign in with a valid REST API access token for this vault; "
              + "check token expiry and the configured vault URL. Bitbucket/Artifactory has not been contacted.");
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

  private static Problem signInRequired(String id) {
    return new Problem(
        401,
        "SECRET_SIGN_IN_REQUIRED",
        "secretServer",
        "No active token for Secret Server connection '" + id + "' in this browser session. "
            + "Sign in to this connection in Connections & catalog, then retry. "
            + "Tokens expire and are cleared on restart, sign-out or connection configuration changes; use the same browser and host.");
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

    final String username; final ConnectionConfig config; final Clock clock;

    Token(String value, Instant expires, long generation, String username, ConnectionConfig config, Clock clock) {
      this.username=username; this.config=config; this.clock=clock;
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
