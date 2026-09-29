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
  public record Document(
      String revision, Catalog.Data catalog, ConnectionConfig.Data connections) {}

  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final Path file;
  private final ConfigurationAccess access;
  private String revision;
  private final Document startup;
  private boolean runtimeOverride;

  @org.springframework.beans.factory.annotation.Autowired
  public RuntimeConfiguration(
      Catalog catalog,
      ConnectionConfig connections,
      @Value("${orchestrator.configuration-file:data/configuration.json}") String file,
      ConfigurationAccess access)
      throws IOException {
    this.access = access;
    this.catalog = catalog;
    this.connections = connections;
    this.file = Path.of(file).toAbsolutePath();
    this.startup = new Document(null, catalog.data(), connections.data());
    if (Files.exists(this.file)) {
      Document saved = Json.read(ConfigurationResources.read(this.file.toString()), Document.class);
      validate(saved);
      catalog.installValidated(saved.catalog());
      connections.installValidated(saved.connections());
      runtimeOverride = true;
    }
    revision = java.util.UUID.randomUUID().toString();
  }

  public RuntimeConfiguration(Catalog catalog, ConnectionConfig connections, String file)
      throws IOException {
    this(catalog, connections, file, new ConfigurationAccess());
  }

  private void validate(Document next) {
    if (next == null || next.catalog() == null || next.connections() == null)
      throw Problem.invalid("configuration", "Catalog and connections are required");
    catalog.validate(next.catalog());
    try {
      new ConnectionConfig(next.connections());
    } catch (RuntimeException e) {
      throw Problem.invalid(
          "connections",
          "Invalid connections: check URLs, authentication modes and credential/source references");
    }
  }

  public Document current() {
    try (var scope = access.read()) {
      return new Document(revision, catalog.data(), connections.data());
    }
  }

  public record StartupView(Document configuration, boolean runtimeOverride) {}

  public StartupView startup() {
    try (var scope = access.read()) {
      return new StartupView(new Document(revision, startup.catalog(), startup.connections()),
          runtimeOverride);
    }
  }

  public Document update(Document next) {
    try (var scope = access.write()) {
      return updateLocked(next);
    }
  }

  private Document updateLocked(Document next) {
    Path temporary = null;
    try {
      if (next == null || !revision.equals(next.revision()))
        throw Problem.conflict("Configuration changed; reload it before saving your edits");
      validate(next);
      String newRevision = java.util.UUID.randomUUID().toString();
      Document document = new Document(newRevision, next.catalog(), next.connections());
      String json = Json.write(document);
      if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
        throw Problem.invalid("configuration", "Configuration exceeds 256 KiB");
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".configuration-", ".json");
      Files.writeString(temporary, json);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      catalog.installValidated(next.catalog());
      if (!Json.write(connections.data()).equals(Json.write(next.connections())))
        connections.installValidated(next.connections());
      revision = newRevision;
      runtimeOverride = true;
      return document;
    } catch (IOException error) {
      throw new Problem(
          503,
          "CONFIGURATION_SAVE_FAILED",
          "configuration",
          "Cannot persist configuration; the previous configuration remains active");
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }
}
