package com.example.perforchestrator.infrastructure.registry;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Administrator-owned connections. Requests select registered destinations; credentials are
 * accepted only by portal authentication.
 */
@Component
public class ConnectionConfig {

  public record Artifactory(String apiBaseUrl, String credentialRef, String authMode) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Artifactory {
      if ("ad".equals(authMode)) authMode = "token";
    }

    public Artifactory(final String apiBaseUrl, final String credentialRef) {
      this(apiBaseUrl, credentialRef, "secret-server");
    }

    public String mode() {
      return authMode == null ? "secret-server" : authMode;
    }
  }

  public record Loki(String apiBaseUrl) {}

  public record Bitbucket(String apiBaseUrl, String credentialRef, String authMode) {
    public Bitbucket {
      if ("ad".equals(authMode)) authMode = "token";
    }

    public String mode() {
      return authMode == null ? "secret-server" : authMode;
    }
  }

  public record SecretServer(
    String apiBaseUrl,
    String bearerTokenEnvironmentVariable,
    String authMode,
    String tokenUrl,
    String bearerTokenFile,
    String credentialRef
  ) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SecretServer {
      if ("portal".equals(authMode)) {
        authMode = "token";
        tokenUrl = null;
      }
    }

    public SecretServer(
      final String apiBaseUrl,
      final String bearerTokenEnvironmentVariable,
      final String authMode,
      final String tokenUrl,
      final String bearerTokenFile
    ) {
      this(apiBaseUrl, bearerTokenEnvironmentVariable, authMode, tokenUrl, bearerTokenFile, null);
    }

    public SecretServer(final String apiBaseUrl, final String bearerTokenEnvironmentVariable) {
      this(apiBaseUrl, bearerTokenEnvironmentVariable, "environment", null, null);
    }

    public String mode() {
      return authMode == null ? "environment" : authMode;
    }
  }

  public record Credential(
    String provider,
    String usernameEnvironmentVariable,
    String passwordEnvironmentVariable,
    String secretServerRef,
    String secretId,
    String usernameFieldSlug,
    String passwordFieldSlug,
    String tokenFieldSlug,
    String tokenEnvironmentVariable
  ) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Credential {
    }

    public Credential(
      final String provider,
      final String usernameEnvironmentVariable,
      final String passwordEnvironmentVariable,
      final String secretServerRef,
      final String secretId,
      final String usernameFieldSlug,
      final String passwordFieldSlug
    ) {
      this(
        provider,
        usernameEnvironmentVariable,
        passwordEnvironmentVariable,
        secretServerRef,
        secretId,
        usernameFieldSlug,
        passwordFieldSlug,
        null,
        null
      );
    }

    public boolean token() {
      return tokenFieldSlug != null || tokenEnvironmentVariable != null;
    }
  }

  public record Source(
    String displayName,
    String connectionRef,
    String repositoryKey,
    Map<String, String> imagePaths,
    boolean usernameRequired,
    String pullRepositoryTemplate
  ) {
    public Source {
      imagePaths = ImmutableConfiguration.map(imagePaths);
    }
  }

  public record Data(
    Map<String, Artifactory> artifactory,
    Map<String, SecretServer> secretServers,
    Map<String, Credential> credentials,
    Map<String, Source> imageSources,
    Map<String, Bitbucket> bitbucket,
    Map<String, Loki> loki
  ) {
    public Data(
      final Map<String, Artifactory> artifactory,
      final Map<String, SecretServer> secretServers,
      final Map<String, Credential> credentials,
      final Map<String, Source> imageSources,
      final Map<String, Bitbucket> bitbucket
    ) {
      this(artifactory, secretServers, credentials, imageSources, bitbucket, Map.of());
    }

    public Data(
      final Map<String, Artifactory> artifactory,
      final Map<String, SecretServer> secretServers,
      final Map<String, Credential> credentials,
      final Map<String, Source> imageSources
    ) {
      this(artifactory, secretServers, credentials, imageSources, Map.of());
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Data {
      loki = ImmutableConfiguration.map(loki == null ? Map.of() : loki);
      bitbucket = ImmutableConfiguration.map(bitbucket == null ? Map.of() : bitbucket);
      artifactory = ImmutableConfiguration.map(artifactory);
      secretServers = ImmutableConfiguration.map(secretServers);
      credentials = ImmutableConfiguration.map(credentials);
      imageSources = ImmutableConfiguration.map(imageSources);
    }
  }

  private record Snapshot(Data data, long generation) {}

  private volatile Snapshot snapshot;

  @org.springframework.beans.factory.annotation.Autowired
  public ConnectionConfig(
    final @Value("${orchestrator.connections:}") String file,
    final org.springframework.core.env.Environment environment
  ) throws IOException {
    this(file.isBlank() ? defaults(environment) : readFile(file));
  }

  public ConnectionConfig(final String file) throws IOException {
    this(file.isBlank() ? new Data(Map.of(), Map.of(), Map.of(), Map.of()) : readFile(file));
  }

  private static Data defaults(final org.springframework.core.env.Environment environment) {
    final var data = org.springframework.boot.context.properties.bind.Binder.get(environment)
      .bind("orchestrator.connection-defaults", Data.class)
      .orElseGet(() -> new Data(Map.of(), Map.of(), Map.of(), Map.of()));
    // Spring omits empty YAML maps; external JSON/YAML still requires all four maps.
    return new Data(
      data.artifactory() == null ? Map.of() : data.artifactory(),
      data.secretServers() == null ? Map.of() : data.secretServers(),
      data.credentials() == null ? Map.of() : data.credentials(),
      data.imageSources() == null ? Map.of() : data.imageSources(),
      data.bitbucket(),
      data.loki()
    );
  }

  private static Data readFile(final String file) throws IOException {
    return Json.read(Json.write(YamlValues.parse(ConfigurationResources.read(file))), Data.class);
  }

  public ConnectionConfig(final Data data) {
    this.snapshot = new Snapshot(data, 0);
    if (
      data.artifactory() == null ||
      data.secretServers() == null ||
      data.credentials() == null ||
      data.imageSources() == null
    ) throw new IllegalArgumentException("All connection maps are required");
    data
      .artifactory()
      .values()
      .forEach((final var c) -> {
        base(c.apiBaseUrl());
        validateAuth(data, c.mode(), c.credentialRef());
      });
    data.loki().forEach((final var id, final var connection) -> {
      if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException(
        "Invalid Loki connection ID"
      );
      base(connection.apiBaseUrl());
    });
    data
      .bitbucket()
      .values()
      .forEach((final var c) -> {
        base(c.apiBaseUrl());
        validateAuth(data, c.mode(), c.credentialRef());
      });
    data
      .secretServers()
      .values()
      .forEach((final var c) -> {
        base(c.apiBaseUrl());
        if (
          !"secret-server".equals(c.mode()) && c.credentialRef() != null
        ) throw new IllegalArgumentException(
          "Credential reference is only used by Secret Server authentication"
        );
        switch (c.mode()) {
          case "interactive" -> {
            if (
              c.tokenUrl() == null ||
              c.bearerTokenFile() != null ||
              c.bearerTokenEnvironmentVariable() != null
            ) throw new IllegalArgumentException(
              "AD or token mode requires an OAuth token URL only"
            );
            final var tokenEndpoint = base(c.tokenUrl());
            if (
              !tokenEndpoint.getRawAuthority().equals(base(c.apiBaseUrl()).getRawAuthority())
            ) throw new IllegalArgumentException("Token URL must use the Secret Server authority");
          }
          case "token" -> {
            if (
              c.tokenUrl() != null ||
              c.bearerTokenFile() != null ||
              c.bearerTokenEnvironmentVariable() != null
            ) throw new IllegalArgumentException(
              "Interactive token mode accepts no stored token configuration"
            );
          }
          case "secret-server" -> {
            if (
              !data.credentials().containsKey(c.credentialRef()) ||
              c.bearerTokenFile() != null ||
              c.bearerTokenEnvironmentVariable() != null
            ) throw new IllegalArgumentException("Invalid bootstrap credential reference");
            if (
              !data.credentials().get(c.credentialRef()).token()
            ) throw new IllegalArgumentException(
              "Secret Server bootstrap requires a token credential"
            );
            if (c.tokenUrl() != null) throw new IllegalArgumentException(
              "A token credential does not use an OAuth endpoint"
            );
          }
          case "environment" -> {
            envName(c.bearerTokenEnvironmentVariable());
            if (
              c.tokenUrl() != null || c.bearerTokenFile() != null
            ) throw new IllegalArgumentException("Ambiguous Secret Server authentication");
          }
          case "file" -> {
            if (
              c.bearerTokenFile() == null ||
              !Path.of(c.bearerTokenFile()).isAbsolute() ||
              c.bearerTokenEnvironmentVariable() != null ||
              c.tokenUrl() != null
            ) throw new IllegalArgumentException(
              "File authentication requires an absolute token file only"
            );
          }
          default -> throw new IllegalArgumentException(
            "Unsupported Secret Server authentication mode"
          );
        }
      });
    data
      .credentials()
      .values()
      .forEach((final var c) -> {
        if ("environment".equals(c.provider())) {
          if (c.token()) {
            envName(c.tokenEnvironmentVariable());
            if (
              c.usernameEnvironmentVariable() != null ||
              c.passwordEnvironmentVariable() != null ||
              c.tokenFieldSlug() != null
            ) throw new IllegalArgumentException("Ambiguous token credential");
          } else {
            envName(c.usernameEnvironmentVariable());
            envName(c.passwordEnvironmentVariable());
          }
        } else if ("delinea".equals(c.provider())) {
          if (
            !data.secretServers().containsKey(c.secretServerRef()) ||
            c.secretId() == null ||
            !c.secretId().matches("[0-9]{1,12}") ||
            (c.token()
              ? c.tokenFieldSlug() == null ||
                c.tokenFieldSlug().isBlank() ||
                c.usernameFieldSlug() != null ||
                c.passwordFieldSlug() != null ||
                c.tokenEnvironmentVariable() != null
              : c.usernameFieldSlug() == null || c.passwordFieldSlug() == null)
          ) throw new IllegalArgumentException("Invalid Delinea credential mapping");
        } else throw new IllegalArgumentException("Unsupported credential provider");
      });
    data
      .secretServers()
      .keySet()
      .forEach((final var id) -> validateVaultChain(data, id, new HashSet<>()));
    data.imageSources().forEach((final var id, final var s) -> {
      if (
        !id.matches("[a-zA-Z0-9_-]{1,80}") ||
        !data.artifactory().containsKey(s.connectionRef()) ||
        !s.repositoryKey().matches("[a-zA-Z0-9_.-]{1,100}") ||
        s.imagePaths() == null
      ) throw new IllegalArgumentException("Invalid image source mapping");
      s.imagePaths()
        .values()
        .forEach((final var path) -> safePath(path.replace("{username}", "fixture-user")));
      if (
        s.pullRepositoryTemplate() == null || s.pullRepositoryTemplate().contains("://")
      ) throw new IllegalArgumentException(
        "Configure a Docker pull repository template, not a URL"
      );
    });
  }

  private static void validateAuth(final Data data, final String mode, final String reference) {
    if (!Set.of("token", "secret-server").contains(mode)) throw new IllegalArgumentException(
      "Unsupported authentication mode"
    );
    if ("secret-server".equals(mode)) {
      if (!data.credentials().containsKey(reference)) throw new IllegalArgumentException(
        "Unknown credential reference"
      );
    } else if (reference != null) throw new IllegalArgumentException(
      "Interactive authentication cannot use a stored credential reference"
    );
  }

  private static void validateVaultChain(
    final Data data,
    final String id,
    final Set<String> visited
  ) {
    if (!visited.add(id)) throw new IllegalArgumentException(
      "Secret Server authentication cannot depend on itself or form a cycle"
    );
    final var server = data.secretServers().get(id);
    if ("secret-server".equals(server.mode())) {
      final var credential = data.credentials().get(server.credentialRef());
      if ("delinea".equals(credential.provider())) validateVaultChain(
        data,
        credential.secretServerRef(),
        visited
      );
    }
  }

  public void replace(final Data next) {
    new ConnectionConfig(next); // Validate the complete graph before publication.
    installValidated(next);
  }

  public synchronized void installValidated(final Data next) {
    snapshot = new Snapshot(next, snapshot.generation() + 1);
  }

  public long generation() {
    return snapshot.generation();
  }

  public Data data() {
    return snapshot.data();
  }

  public static URI base(final String value) {
    final URI uri = URI.create(value);
    if (
      !"https".equals(uri.getScheme()) ||
      uri.getHost() == null ||
      uri.getUserInfo() != null ||
      uri.getQuery() != null ||
      uri.getFragment() != null ||
      uri.getPath().contains("..")
    ) throw new IllegalArgumentException(
      "Connections require fixed HTTPS URLs without embedded credentials"
    );
    return uri;
  }

  private static void envName(final String name) {
    if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,100}")) throw new IllegalArgumentException(
      "Invalid environment secret reference"
    );
  }

  public static String safePath(final String value) {
    if (
      value == null ||
      !value.matches("[a-zA-Z0-9][a-zA-Z0-9_./-]{0,250}") ||
      Arrays.stream(value.split("/", -1)).anyMatch(
        (final var s) -> s.isEmpty() || s.equals(".") || s.equals("..")
      )
    ) throw Problem.invalid("repository", "Invalid registered repository path");
    return value;
  }

  public Map<String, Object> publicView() {
    final Map<String, Object> connections = new TreeMap<>();
    data()
      .artifactory()
      .forEach((final var id, final var c) ->
        connections.put(
          id,
          Map.of(
            "apiBaseUrl",
            c.apiBaseUrl(),
            "credentialRef",
            Objects.toString(c.credentialRef(), ""),
            "authMode",
            c.mode(),
            "capability",
            "read-only Artifactory Docker V2"
          )
        )
      );
    return Map.of(
      "artifactory",
      connections,
      "bitbucket",
      data().bitbucket(),
      "loki",
      data().loki(),
      "secretServers",
      data().secretServers().keySet(),
      "credentialReferences",
      data().credentials().keySet(),
      "imageSources",
      data().imageSources()
    );
  }
}
