package com.example.perforchestrator.api;

import com.example.perforchestrator.infrastructure.secrets.SecretServerTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/secret-auth")
public class SecretAuthenticationController {
  private final SecretServerTokens tokens;

  public SecretAuthenticationController(SecretServerTokens tokens) {
    this.tokens = tokens;
  }

  public record Credentials(String username, String password) {
    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }

  @GetMapping
  public Object status(HttpServletRequest request) {
    return tokens.statuses(request.getSession(false));
  }

  @PostMapping("/{connection}")
  public Object signIn(
      @PathVariable String connection,
      @RequestBody Credentials credentials,
      HttpServletRequest request) {
    var result =
        tokens.signIn(
            connection, credentials.username(), credentials.password(), request.getSession());
    request.changeSessionId();
    return result;
  }

  public record TokenInput(String token, long expiresInSeconds) {
    @Override public String toString() { return "[REDACTED]"; }
  }

  @PostMapping("/{connection}/token")
  public Object useToken(@PathVariable String connection, @RequestBody TokenInput input, HttpServletRequest request) {
    var result = tokens.useToken(connection, input.token(), input.expiresInSeconds(), request.getSession());
    request.changeSessionId();
    return result;
  }

  @DeleteMapping("/{connection}")
  public Object signOut(@PathVariable String connection, HttpServletRequest request) {
    tokens.signOut(connection, request.getSession(false));
    return Map.of("cleared", true);
  }
}
