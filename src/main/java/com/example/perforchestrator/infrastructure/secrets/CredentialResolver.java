package com.example.perforchestrator.infrastructure.secrets;

/** Never serialize credentials. Resolve on demand so externally rotated values can take effect. */
public interface CredentialResolver {
  Secret resolve(String reference);

  final class Secret {
    private final String username, password;
    private final boolean token;

    public Secret(String username, String password) {
      this(username, password, false);
    }

    public Secret(String username, String password, boolean token) {
      this.username = username;
      this.password = password;
      this.token = token;
    }

    public boolean token() { return token; }

    public String username() {
      return username;
    }

    public String password() {
      return password;
    }

    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }
}
