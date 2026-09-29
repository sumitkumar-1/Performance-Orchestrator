package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigurationTest {
  @TempDir Path directory;

  Catalog catalog() throws Exception {
    return new Catalog("classpath:mocks/catalog.yaml", "simulation", "127.0.0.1");
  }

  ConnectionConfig connections() throws Exception {
    return new ConnectionConfig("");
  }

  RuntimeConfiguration.Document edited(RuntimeConfiguration.Document original, String name) {
    ObjectNode root = Json.MAPPER.valueToTree(original);
    ((ObjectNode) root.path("catalog").path("environments").path("sandbox"))
        .put("displayName", name);
    return Json.read(Json.write(root), RuntimeConfiguration.Document.class);
  }

  @Test
  void persistsUpdatesRestoresOnRestartAndRejectsStaleEditors() throws Exception {
    var catalog = catalog();
    var config =
        new RuntimeConfiguration(
            catalog, connections(), directory.resolve("config.json").toString());
    var initial = config.current();
    var saved = config.update(edited(initial, "Updated sandbox"));
    assertThat(catalog.environment("sandbox").displayName()).isEqualTo("Updated sandbox");
    assertThat(catalog.hash()).isEqualTo(Json.hash(Json.write(saved.catalog())));
    assertThatThrownBy(() -> config.update(edited(initial, "Stale edit")))
        .hasMessageContaining("reload");
    var restoredCatalog = catalog();
    var restored =
        new RuntimeConfiguration(
            restoredCatalog, connections(), directory.resolve("config.json").toString());
    assertThat(restoredCatalog.environment("sandbox").displayName()).isEqualTo("Updated sandbox");
    assertThat(restored.current().revision()).isNotEqualTo(initial.revision());
  }

  @Test
  void invalidCatalogAndConnectionsLeaveMemoryAndDiskUnchanged() throws Exception {
    var config =
        new RuntimeConfiguration(
            catalog(), connections(), directory.resolve("config.json").toString());
    var saved = config.update(edited(config.current(), "Saved"));
    String disk = Files.readString(directory.resolve("config.json"));
    ObjectNode invalid = Json.MAPPER.valueToTree(saved);
    ((ObjectNode) invalid.path("catalog").path("services").path("auth-service"))
        .put("projectPath", "../escape");
    var badCatalog = Json.read(Json.write(invalid), RuntimeConfiguration.Document.class);
    assertThatThrownBy(() -> config.update(badCatalog)).hasMessageContaining("Invalid catalog");
    invalid = Json.MAPPER.valueToTree(saved);
    ((ObjectNode) invalid.path("connections").path("artifactory"))
        .putObject("bad")
        .put("apiBaseUrl", "http://untrusted.invalid")
        .put("credentialRef", "unknown");
    var badConnection = Json.read(Json.write(invalid), RuntimeConfiguration.Document.class);
    assertThatThrownBy(() -> config.update(badConnection))
        .hasMessageContaining("Invalid connections");
    assertThat(config.current()).isEqualTo(saved);
    assertThat(Files.readString(directory.resolve("config.json"))).isEqualTo(disk);
  }

  @Test
  void persistenceFailureDoesNotPublishAndPackagedProjectsAreReadable() throws Exception {
    var catalog = catalog();
    assertThat(catalog.read(catalog.service("auth-service"), "ckp/Chart.yaml"))
        .contains("REPLACE_VERSION");
    Path parentFile = directory.resolve("not-directory");
    Files.writeString(parentFile, "blocking file");
    var config =
        new RuntimeConfiguration(
            catalog, connections(), parentFile.resolve("config.json").toString());
    var old = config.current();
    assertThatThrownBy(() -> config.update(edited(old, "Should not apply")))
        .hasMessageContaining("previous configuration remains active");
    assertThat(config.current()).isEqualTo(old);
  }
}
