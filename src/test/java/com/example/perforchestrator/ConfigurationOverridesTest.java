package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationOverridesTest {
  @TempDir Path directory;

  @Test
  void savedFieldsSurviveWhileUntouchedDefaultsUpgradeAndResetRemovesOverrides() throws Exception {
    var factory = new RuntimeConfigurationTest();
    var initial = new RuntimeConfiguration(factory.catalog(), new ConnectionConfig(""), directory.resolve("settings.json").toString());
    initial.update(factory.edited(initial.current(), "Dashboard name"));
    var changed = factory.catalog();
    ObjectNode defaults = Json.MAPPER.valueToTree(changed.data());
    ((ObjectNode) defaults.path("environments").path("sandbox"))
        .put("dashboardUrl", "https://new.example.invalid/dashboard").put("displayName", "New default name");
    changed.replace(Json.read(Json.write(defaults), Catalog.Data.class));
    var restarted = new RuntimeConfiguration(changed, new ConnectionConfig(""), directory.resolve("settings.json").toString());
    assertThat(changed.environment("sandbox").displayName()).isEqualTo("Dashboard name");
    assertThat(changed.environment("sandbox").dashboardUrl()).isEqualTo("https://new.example.invalid/dashboard");
    assertThat(Files.readString(directory.resolve("settings.json"))).contains("schemaVersion").doesNotContain("dashboardUrl");
    restarted.update(restarted.startup().configuration());
    assertThat(restarted.startup().runtimeOverride()).isFalse();
    assertThat(changed.environment("sandbox").displayName()).isEqualTo("New default name");
    var again = new RuntimeConfiguration(factory.catalog(), new ConnectionConfig(""), directory.resolve("settings.json").toString());
    assertThat(again.startup().runtimeOverride()).isFalse();
  }

  @Test
  void removalsNullsArraysAndNewDefaultsRemainDistinct() throws Exception {
    var before = Json.MAPPER.readTree("{\"catalog\":{\"remove\":1,\"nullable\":2,\"list\":[1]},\"connections\":{}}");
    var after = Json.MAPPER.readTree("{\"catalog\":{\"nullable\":null,\"list\":[2]},\"connections\":{}}");
    var changes = ConfigurationOverrides.diff(before, after);
    ((ObjectNode) before.get("catalog")).put("newDefault", 3);
    var applied = ConfigurationOverrides.apply(before, changes);
    assertThat(applied.path("catalog").has("remove")).isFalse();
    assertThat(applied.path("catalog").get("nullable").isNull()).isTrue();
    assertThat(applied.path("catalog").path("list").get(0).asInt()).isEqualTo(2);
    assertThat(applied.path("catalog").path("newDefault").asInt()).isEqualTo(3);
  }

  @Test
  void removalDoesNotResurrectAnEntryRemovedFromNewDefaults() throws Exception {
    var before = Json.MAPPER.readTree("{\"catalog\":{\"old\":{\"field\":1}},\"connections\":{}}");
    var after = Json.MAPPER.readTree("{\"catalog\":{\"old\":{}},\"connections\":{}}");
    var newer = Json.MAPPER.readTree("{\"catalog\":{},\"connections\":{}}");
    assertThat(ConfigurationOverrides.apply(newer, ConfigurationOverrides.diff(before, after))).isEqualTo(newer);
  }

  @Test
  void legacyExportLoadsAndMigratesOnNextSave() throws Exception {
    var factory = new RuntimeConfigurationTest();
    var old = new RuntimeConfiguration(factory.catalog(), new ConnectionConfig(""), directory.resolve("unused.json").toString());
    Path path = directory.resolve("legacy.json");
    Files.writeString(path, Json.write(factory.edited(old.current(), "Imported name")));
    var loaded = new RuntimeConfiguration(factory.catalog(), new ConnectionConfig(""), path.toString());
    assertThat(loaded.current().catalog().environments().get("sandbox").displayName()).isEqualTo("Imported name");
    loaded.update(loaded.current());
    assertThat(Json.MAPPER.readTree(Files.readString(path)).path("schemaVersion").asInt()).isEqualTo(2);
  }
}
