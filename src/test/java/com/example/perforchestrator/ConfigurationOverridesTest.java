package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationOverridesTest {

  @TempDir
  Path directory;

  /**
   * <b>Scenario:</b> Saved Fields Survive While Untouched Defaults Upgrade And Reset Removes Overrides
   * <pre>
   * GIVEN ... saved dashboard overrides and newer startup defaults
   * WHEN ... configuration restarts and is then reset to startup settings
   * THEN ... edited fields survive restart, untouched defaults upgrade, and reset removes overrides
   * </pre>
   */
  @Test
  @DisplayName("Saved Fields Survive While Untouched Defaults Upgrade And Reset Removes Overrides")
  void savedFieldsSurviveWhileUntouchedDefaultsUpgradeAndResetRemovesOverrides() throws Exception {
    final var factory = new RuntimeConfigurationTest();
    final var initial = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      directory.resolve("settings.json").toString()
    );
    initial.update(factory.edited(initial.current(), "Dashboard name"));
    final var changed = factory.catalog();
    final ObjectNode defaults = Json.MAPPER.valueToTree(changed.data());
    ((ObjectNode) defaults.path("environments").path("sandbox"))
      .put("clusterIdentity", "new-cluster")
      .put("displayName", "New default name");
    changed.replace(Json.read(Json.write(defaults), Catalog.Data.class));
    final var restarted = new RuntimeConfiguration(
      changed,
      new ConnectionConfig(""),
      directory.resolve("settings.json").toString()
    );
    assertThat(changed.environment("sandbox").displayName()).isEqualTo("Dashboard name");
    assertThat(changed.environment("sandbox").clusterIdentity()).isEqualTo("new-cluster");
    assertThat(Files.readString(directory.resolve("settings.json")))
      .contains("schemaVersion")
      .doesNotContain("clusterIdentity");
    restarted.update(restarted.startup().configuration());
    assertThat(restarted.startup().runtimeOverride()).isFalse();
    assertThat(changed.environment("sandbox").displayName()).isEqualTo("New default name");
    final var again = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      directory.resolve("settings.json").toString()
    );
    assertThat(again.startup().runtimeOverride()).isFalse();
  }

  /**
   * <b>Scenario:</b> Removals Nulls Arrays And New Defaults Remain Distinct
   * <pre>
   * GIVEN ... configuration edits containing deletion, explicit null, and array replacement
   * WHEN ... the edits are applied over defaults containing a new field
   * THEN ... each edit keeps its meaning and the new default is retained
   * </pre>
   */
  @Test
  @DisplayName("Removals Nulls Arrays And New Defaults Remain Distinct")
  void removalsNullsArraysAndNewDefaultsRemainDistinct() throws Exception {
    final var before = Json.MAPPER.readTree(
      "{\"catalog\":{\"remove\":1,\"nullable\":2,\"list\":[1]},\"connections\":{}}"
    );
    final var after = Json.MAPPER.readTree(
      "{\"catalog\":{\"nullable\":null,\"list\":[2]},\"connections\":{}}"
    );
    final var changes = ConfigurationOverrides.diff(before, after);
    ((ObjectNode) before.get("catalog")).put("newDefault", 3);
    final var applied = ConfigurationOverrides.apply(before, changes);
    assertThat(applied.path("catalog").has("remove")).isFalse();
    assertThat(applied.path("catalog").get("nullable").isNull()).isTrue();
    assertThat(applied.path("catalog").path("list").get(0).asInt()).isEqualTo(2);
    assertThat(applied.path("catalog").path("newDefault").asInt()).isEqualTo(3);
  }

  /**
   * <b>Scenario:</b> Removal Does Not Resurrect An Entry Removed From New Defaults
   * <pre>
   * GIVEN ... a saved field deletion whose parent was removed from newer defaults
   * WHEN ... the override is applied to those defaults
   * THEN ... the removed parent is not recreated
   * </pre>
   */
  @Test
  @DisplayName("Removal Does Not Resurrect An Entry Removed From New Defaults")
  void removalDoesNotResurrectAnEntryRemovedFromNewDefaults() throws Exception {
    final var before = Json.MAPPER.readTree(
      "{\"catalog\":{\"old\":{\"field\":1}},\"connections\":{}}"
    );
    final var after = Json.MAPPER.readTree("{\"catalog\":{\"old\":{}},\"connections\":{}}");
    final var newer = Json.MAPPER.readTree("{\"catalog\":{},\"connections\":{}}");
    assertThat(
      ConfigurationOverrides.apply(newer, ConfigurationOverrides.diff(before, after))
    ).isEqualTo(newer);
  }

  /**
   * <b>Scenario:</b> Legacy Export Loads And Migrates On Next Save
   * <pre>
   * GIVEN ... a legacy full-configuration export
   * WHEN ... the export is loaded and saved
   * THEN ... its settings are restored and saved in the current override schema
   * </pre>
   */
  @Test
  @DisplayName("Legacy Export Loads And Migrates On Next Save")
  void legacyExportLoadsAndMigratesOnNextSave() throws Exception {
    final var factory = new RuntimeConfigurationTest();
    final var old = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      directory.resolve("unused.json").toString()
    );
    final Path path = directory.resolve("legacy.json");
    Files.writeString(path, Json.write(factory.edited(old.current(), "Imported name")));
    final var loaded = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      path.toString()
    );
    assertThat(loaded.current().catalog().environments().get("sandbox").displayName()).isEqualTo(
      "Imported name"
    );
    loaded.update(loaded.current());
    assertThat(
      Json.MAPPER.readTree(Files.readString(path)).path("schemaVersion").asInt()
    ).isEqualTo(2);
  }

  /**
   * <b>Scenario:</b> Restore saved production overrides from an existing installation
   * <pre>
   * GIVEN ... saved dashboard edits whose envelope contains the retired real-mode marker
   * WHEN ... configuration is loaded and saved after upgrading
   * THEN ... the edits survive and the retired marker is no longer exported
   * </pre>
   */
  @Test
  @DisplayName("Legacy production overrides survive the single-workflow upgrade")
  void legacyProductionOverridesSurviveUpgrade() throws Exception {
    final var factory = new RuntimeConfigurationTest();
    final Path file = directory.resolve("settings.json");
    final var initial = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      file.toString()
    );
    initial.update(factory.edited(initial.current(), "Retained dashboard name"));
    final ObjectNode legacy = (ObjectNode) Json.MAPPER.readTree(Files.readString(file));
    legacy.put("mode", "real");
    Files.writeString(file, Json.write(legacy));
    final var restarted = new RuntimeConfiguration(
      factory.catalog(),
      new ConnectionConfig(""),
      file.toString()
    );
    assertThat(restarted.current().catalog().environments().get("sandbox").displayName()).isEqualTo(
      "Retained dashboard name"
    );
    restarted.update(restarted.current());
    assertThat(Json.MAPPER.readTree(Files.readString(file)).has("mode")).isFalse();
  }
}
