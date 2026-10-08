package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Durable administrator overrides; requests see one complete configuration revision. */
@Component
public class RuntimeConfiguration {

  public record Document(String revision, Catalog.Data catalog, ConnectionConfig.Data connections) {
    public Document {
      if (catalog != null && connections != null) {
        final var migrated = MonitoringConfiguration.migrate(catalog, connections);
        catalog = migrated.catalog();
        connections = migrated.connections();
      }
    }
  }

  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final Path file;
  private final ConfigurationAccess access;
  private String revision;
  private final Document startup;
  private boolean runtimeOverride;

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties("mode")
  public record SavedOverrides(
    int schemaVersion,
    String environment,
    java.util.List<ConfigurationOverrides.Change> overrides
  ) {}

  @org.springframework.beans.factory.annotation.Autowired
  public RuntimeConfiguration(
    final Catalog catalog,
    final ConnectionConfig connections,
    final @Value("${orchestrator.configuration-file:data/configuration.json}") String file,
    final ConfigurationAccess access
  ) throws IOException {
    this.access = access;
    this.catalog = catalog;
    this.connections = connections;
    this.file = Path.of(file).toAbsolutePath().normalize();
    this.startup = new Document(null, catalog.data(), connections.data());
    validate(this.startup);
    catalog.installValidated(startup.catalog());
    if (!startup.connections().equals(connections.data())) connections.installValidated(
      startup.connections()
    );
    if (Files.exists(this.file)) {
      final String content = ConfigurationResources.read(this.file.toString());
      final var tree = Json.MAPPER.readTree(content);
      final Document saved;
      if (tree.has("schemaVersion")) {
        final SavedOverrides stored = Json.read(content, SavedOverrides.class);
        if (
          stored.schemaVersion() != 2 ||
          !catalog.boundEnvironment().equals(stored.environment()) ||
          stored.overrides() == null
        ) throw new IllegalArgumentException(
          "Runtime overrides belong to a different environment or version"
        );
        saved = Json.read(
          Json.write(
            ConfigurationOverrides.apply(Json.MAPPER.valueToTree(startup), stored.overrides())
          ),
          Document.class
        );
        runtimeOverride = !stored.overrides().isEmpty();
      } else {
        // Preserve legacy whole-document imports. The next save converts them to field edits.
        saved = Json.read(content, Document.class);
        runtimeOverride = true;
      }
      try {
        validate(saved);
      } catch (final Problem error) {
        throw new IllegalStateException(
          "Saved runtime configuration failed validation at " +
            error.field() +
            ". Source file: " +
            this.file +
            ". Startup configuration; target environment: " +
            (catalog.boundEnvironment().isBlank() ? "unbound" : catalog.boundEnvironment()) +
            ". Check orchestrator.configuration-file and the process working directory. " +
            error.getMessage()
        );
      }
      catalog.installValidated(saved.catalog());
      connections.installValidated(saved.connections());
    }
    revision = java.util.UUID.randomUUID().toString();
  }

  public RuntimeConfiguration(
    final Catalog catalog,
    final ConnectionConfig connections,
    final String file
  ) throws IOException {
    this(catalog, connections, file, new ConfigurationAccess());
  }

  private void validate(final Document next) {
    if (next == null || next.catalog() == null || next.connections() == null) throw Problem.invalid(
      "configuration",
      "Catalog and connections are required"
    );
    catalog.validate(next.catalog());
    try {
      new ConnectionConfig(next.connections());
      next
        .catalog()
        .services()
        .values()
        .forEach((final var service) -> {
          service.monitoringCredentials().forEach((final var environment, final var reference) -> {
            if (
              environment == null ||
              !environment.matches("[a-zA-Z0-9_-]{1,80}") ||
              !next.connections().credentials().containsKey(reference)
            ) throw new IllegalArgumentException("Unknown service monitoring credential reference");
          });
          final var image = service.containerImage();
          if (image != null) {
            if (
              !next.connections().artifactory().containsKey(image.connectionRef())
            ) throw new IllegalArgumentException("Unknown image connection");
            for (final String part : new String[] {
              image.repoStage(),
              image.teamId(),
              image.imageName(),
            })
              if (
                part == null || !part.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
              ) throw new IllegalArgumentException("Invalid image path component");
          }
          final var source = service.sourceProject();
          if (source != null) {
            if (
              !next.connections().bitbucket().containsKey(source.connectionRef())
            ) throw new IllegalArgumentException("Unknown Bitbucket connection");
            for (final String part : new String[] { source.projectKey(), source.repository() })
              if (
                part == null || !part.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
              ) throw new IllegalArgumentException("Invalid Bitbucket project/repository");
            if (
              source.revision() == null ||
              source.revision().isBlank() ||
              source.revision().length() > 250
            ) throw new IllegalArgumentException("Missing source revision");
            ConnectionConfig.safePath(source.chartPath());
          }
        });
      next
        .catalog()
        .environments()
        .values()
        .forEach((final var env) -> {
          if (
            env.monitoring() != null &&
            env.monitoring().connectionRef() != null &&
            !next.connections().loki().containsKey(env.monitoring().connectionRef())
          ) throw new IllegalArgumentException("Unknown Loki connection reference");
          if (env.monitoring() != null && env.monitoring().namespaceCredentials() != null) env
            .monitoring()
            .namespaceCredentials()
            .values()
            .forEach((final var refs) -> {
              for (final String ref : java.util.List.of(
                java.util.Objects.toString(refs.logsCredentialRef(), ""),
                java.util.Objects.toString(refs.metricsCredentialRef(), "")
              ))
                if (
                  !ref.isBlank() && !next.connections().credentials().containsKey(ref)
                ) throw new IllegalArgumentException("Unknown monitoring credential reference");
            });
        });
    } catch (final RuntimeException e) {
      throw Problem.invalid(
        "connections",
        "Invalid connections: check URLs, authentication modes and credential/source references"
      );
    }
  }

  public Document current() {
    try (var scope = access.read()) {
      return new Document(revision, catalog.data(), connections.data());
    }
  }

  public record StartupView(Document configuration, boolean runtimeOverride, String environment) {}

  public StartupView startup() {
    try (var scope = access.read()) {
      return new StartupView(
        new Document(revision, startup.catalog(), startup.connections()),
        runtimeOverride,
        catalog.boundEnvironment()
      );
    }
  }

  public Document update(final Document next) {
    try (var scope = access.write()) {
      return updateLocked(next);
    }
  }

  private Document updateLocked(final Document next) {
    Path temporary = null;
    try {
      if (next == null || !revision.equals(next.revision())) throw Problem.conflict(
        "Configuration changed; reload it before saving your edits"
      );
      validate(next);
      final String newRevision = java.util.UUID.randomUUID().toString();
      final Document document = new Document(newRevision, next.catalog(), next.connections());
      final var changes = ConfigurationOverrides.diff(
        Json.MAPPER.valueToTree(startup),
        Json.MAPPER.valueToTree(new Document(null, next.catalog(), next.connections()))
      );
      final String json = Json.write(new SavedOverrides(2, catalog.boundEnvironment(), changes));
      if (
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144
      ) throw Problem.invalid("configuration", "Configuration exceeds 256 KiB");
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".configuration-", ".json");
      Files.writeString(temporary, json);
      Files.move(
        temporary,
        file,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      );
      catalog.installValidated(next.catalog());
      if (
        !Json.write(connections.data()).equals(Json.write(next.connections()))
      ) connections.installValidated(next.connections());
      revision = newRevision;
      runtimeOverride = !changes.isEmpty();
      return document;
    } catch (final IOException error) {
      throw new Problem(
        503,
        "CONFIGURATION_SAVE_FAILED",
        "configuration",
        "Cannot persist configuration; the previous configuration remains active"
      );
    } finally {
      if (temporary != null) try {
        Files.deleteIfExists(temporary);
      } catch (final IOException ignored) {}
    }
  }
}
