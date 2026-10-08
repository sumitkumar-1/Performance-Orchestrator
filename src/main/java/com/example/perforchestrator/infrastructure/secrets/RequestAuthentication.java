package com.example.perforchestrator.infrastructure.secrets;

import com.example.perforchestrator.domain.Problem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Request-only input: never attach this object to a session, configuration or log. */
public record RequestAuthentication(String username, String password, String token) {
  @Override
  public String toString() {
    return "[REDACTED]";
  }

  public static String authorization(
    final String mode,
    final String credentialRef,
    final RequestAuthentication input,
    final CredentialResolver resolver
  ) {
    if ("secret-server".equals(mode)) {
      if (
        input != null &&
        (input.username() != null || input.password() != null || input.token() != null)
      ) throw Problem.invalid(
        "authentication",
        "This connection uses its configured credential reference"
      );
      final var secret = resolver.resolve(credentialRef);
      if (!secret.token()) throw Problem.invalid(
        "credentialRef",
        "This connection requires a token credential reference"
      );
      return bearer(secret.password());
    }
    if (input == null) throw Problem.invalid(
      "authentication",
      "Enter credentials for this operation"
    );
    if ("token".equals(mode)) {
      if (input.username() != null || input.password() != null) throw Problem.invalid(
        "authentication",
        "Supply only a token"
      );
      return bearer(input.token());
    }
    throw Problem.invalid(
      "authentication",
      "AD authentication has been removed; supply an access token"
    );
  }

  public static String basic(final String username, final String password) {
    if (
      username == null ||
      username.isBlank() ||
      username.length() > 512 ||
      username.contains(":") ||
      password == null ||
      password.isEmpty() ||
      password.length() > 4096
    ) throw Problem.invalid(
      "authentication",
      "Valid username and password are required; username cannot contain a colon"
    );
    return (
      "Basic " +
      Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8)
      )
    );
  }

  public static String bearer(final String token) {
    if (
      token == null ||
      token.isBlank() ||
      token.length() > 65536 ||
      !token.matches("[A-Za-z0-9._~+/=-]+")
    ) throw Problem.invalid(
      "authentication",
      "Supply a valid bearer token without the Bearer prefix"
    );
    return "Bearer " + token;
  }
}
