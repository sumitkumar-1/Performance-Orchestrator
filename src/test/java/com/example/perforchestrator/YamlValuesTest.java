package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.YamlValues;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class YamlValuesTest {

  /**
   * <b>Scenario:</b> Maps Merge Lists Replace And Explicit Deletion
   * <pre>
   * GIVEN ... base YAML containing nested maps, a list, and a removable key
   * WHEN ... an overlay updates the map, replaces the list, and deletes the key
   * THEN ... the merged values reflect those changes without modifying the base
   * </pre>
   */
  @Test
  @DisplayName("Maps Merge Lists Replace And Explicit Deletion")
  void mapsMergeListsReplaceAndExplicitDeletion() {
    final var base = YamlValues.parse("nested: {a: 1, b: 2}\nlist: [a, b]\nremoved: yes");
    final var result = YamlValues.merge(
      base,
      YamlValues.parse("nested: {a: 3}\nlist: [c]\nremoved: {$delete: true}")
    );
    assertThat(result)
      .containsEntry("nested", Map.of("a", 3, "b", 2))
      .containsEntry("list", List.of("c"))
      .doesNotContainKey("removed");
    assertThat(base).containsKey("removed");
  }

  /**
   * <b>Scenario:</b> Rejects Ambiguous Or Unsafe YAML
   * <pre>
   * GIVEN ... YAML containing duplicate keys, nulls, object tags, aliases, an invalid root, or oversized content
   * WHEN ... the YAML is parsed
   * THEN ... each unsafe or unsupported input is rejected
   * </pre>
   */
  @Test
  @DisplayName("Rejects Ambiguous Or Unsafe Yaml")
  void rejectsAmbiguousOrUnsafeYaml() {
    for (final String yaml : List.of(
      "a: 1\na: 2",
      "a: null",
      "a: !!java.net.URL ['https://example.invalid']",
      "a: &x {b: 1}\nc: *x",
      "a: &x scalar\nb: *x",
      "[a, b]",
      "x: " + "a".repeat(65537)
    ))
      assertThatThrownBy(() -> YamlValues.parse(yaml)).isInstanceOf(RuntimeException.class);
  }
}
