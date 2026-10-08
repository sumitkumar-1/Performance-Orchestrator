package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigurationTest {

  @TempDir
  Path directory;

  Catalog catalog() throws Exception {
    return new Catalog("classpath:fixtures/catalog.yaml", "127.0.0.1");
  }

  ConnectionConfig connections() throws Exception {
    return new ConnectionConfig("");
  }

  RuntimeConfiguration.Document edited(
    final RuntimeConfiguration.Document original,
    final String name
  ) {
    final ObjectNode root = Json.MAPPER.valueToTree(original);
    ((ObjectNode) root.path("catalog").path("environments").path("sandbox")).put(
      "displayName",
      name
    );
    return Json.read(Json.write(root), RuntimeConfiguration.Document.class);
  }

  /**
   * <b>Scenario:</b> Persists Updates Restores On Restart And Rejects Stale Editors
   * <pre>
   * GIVEN ... runtime configuration with a persistent overrides file
   * WHEN ... an update is saved, the configuration restarts, and a stale edit is submitted
   * THEN ... the saved update is restored and the stale edit is rejected
   * </pre>
   */
  @Test
  @DisplayName("Persists Updates Restores On Restart And Rejects Stale Editors")
  void persistsUpdatesRestoresOnRestartAndRejectsStaleEditors() throws Exception {
    final var catalog = catalog();
    final var config = new RuntimeConfiguration(
      catalog,
      connections(),
      directory.resolve("config.json").toString()
    );
    final var initial = config.current();
    assertThat(config.startup().runtimeOverride()).isFalse();
    final var saved = config.update(edited(initial, "Updated sandbox"));
    assertThat(catalog.environment("sandbox").displayName()).isEqualTo("Updated sandbox");
    assertThat(config.startup().runtimeOverride()).isTrue();
    assertThat(config.startup().configuration().catalog()).isEqualTo(initial.catalog());
    assertThat(catalog.hash()).isEqualTo(Json.hash(Json.write(saved.catalog())));
    assertThatThrownBy(() -> config.update(edited(initial, "Stale edit"))).hasMessageContaining(
      "reload"
    );
    final var restoredCatalog = catalog();
    final var restored = new RuntimeConfiguration(
      restoredCatalog,
      connections(),
      directory.resolve("config.json").toString()
    );
    assertThat(restoredCatalog.environment("sandbox").displayName()).isEqualTo("Updated sandbox");
    assertThat(restored.current().revision()).isNotEqualTo(initial.revision());
    assertThat(restored.startup().configuration().catalog()).isEqualTo(initial.catalog());
    // A portable export uses the destination revision, never the source's stale revision.
    final var exported = Json.read(Json.write(saved), RuntimeConfiguration.Document.class);
    final var imported = restored.update(
      new RuntimeConfiguration.Document(
        restored.current().revision(),
        exported.catalog(),
        exported.connections()
      )
    );
    assertThat(imported.catalog()).isEqualTo(saved.catalog());
  }

  /**
   * <b>Scenario:</b> Invalid Catalog And Connections Leave Memory And Disk Unchanged
   * <pre>
   * GIVEN ... valid runtime configuration in memory and on disk
   * WHEN ... invalid catalog or connection updates are submitted
   * THEN ... validation fails without changing the active or saved configuration
   * </pre>
   */
  @Test
  @DisplayName("Invalid Catalog And Connections Leave Memory And Disk Unchanged")
  void invalidCatalogAndConnectionsLeaveMemoryAndDiskUnchanged() throws Exception {
    final var config = new RuntimeConfiguration(
      catalog(),
      connections(),
      directory.resolve("config.json").toString()
    );
    final var saved = config.update(edited(config.current(), "Saved"));
    final String disk = Files.readString(directory.resolve("config.json"));
    ObjectNode invalid = Json.MAPPER.valueToTree(saved);
    ((ObjectNode) invalid.path("catalog").path("services").path("auth-service")).put(
      "projectPath",
      "../escape"
    );
    final var badCatalog = Json.read(Json.write(invalid), RuntimeConfiguration.Document.class);
    assertThatThrownBy(() -> config.update(badCatalog)).hasMessageContaining("Invalid catalog");
    invalid = Json.MAPPER.valueToTree(saved);
    ((ObjectNode) invalid.path("connections").path("artifactory"))
      .putObject("bad")
      .put("apiBaseUrl", "http://untrusted.invalid")
      .put("credentialRef", "unknown");
    final var badConnection = Json.read(Json.write(invalid), RuntimeConfiguration.Document.class);
    assertThatThrownBy(() -> config.update(badConnection)).hasMessageContaining(
      "Invalid connections"
    );
    assertThat(config.current()).isEqualTo(saved);
    assertThat(Files.readString(directory.resolve("config.json"))).isEqualTo(disk);
  }

  /**
   * <b>Scenario:</b> Startup Diagnostic Identifies The Actual Saved File Without Changing It
   * <pre>
   * GIVEN ... a saved configuration file missing required catalog data
   * WHEN ... runtime configuration starts from that file
   * THEN ... the error identifies the source file and startup mode without modifying the file
   * </pre>
   */
  @Test
  @DisplayName("Startup Diagnostic Identifies The Actual Saved File Without Changing It")
  void startupDiagnosticIdentifiesTheActualSavedFileWithoutChangingIt() throws Exception {
    final Path savedFile = directory.resolve("imported-settings.json");
    final var initial = new RuntimeConfiguration(
      catalog(),
      connections(),
      directory.resolve("unused.json").toString()
    );
    final ObjectNode document = Json.MAPPER.valueToTree(initial.current());
    ((ObjectNode) document.path("catalog")).remove("environments");
    final String content = Json.write(document);
    Files.writeString(savedFile, content);
    assertThatThrownBy(() ->
      new RuntimeConfiguration(catalog(), connections(), savedFile.toString())
    )
      .hasMessageContaining("Source file: " + savedFile.toAbsolutePath())
      .hasMessageContaining("Startup configuration")
      .hasMessageContaining("catalog");
    assertThat(Files.readString(savedFile)).isEqualTo(content);
  }

  /**
   * <b>Scenario:</b> Persistence failure does not publish configuration
   * <pre>
   * GIVEN ... an unwritable configuration destination
   * WHEN ... a configuration update is attempted
   * THEN ... failed persistence leaves the prior configuration active
   * </pre>
   */
  @Test
  @DisplayName("Persistence failure does not publish configuration")
  void persistenceFailureDoesNotPublish() throws Exception {
    final var catalog = catalog();
    final Path parentFile = directory.resolve("not-directory");
    Files.writeString(parentFile, "blocking file");
    final var config = new RuntimeConfiguration(
      catalog,
      connections(),
      parentFile.resolve("config.json").toString()
    );
    final var old = config.current();
    assertThatThrownBy(() -> config.update(edited(old, "Should not apply"))).hasMessageContaining(
      "previous configuration remains active"
    );
    assertThat(config.current()).isEqualTo(old);
  }
}
