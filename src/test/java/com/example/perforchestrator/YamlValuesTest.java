package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.config.YamlValues;
import java.util.*;
import org.junit.jupiter.api.Test;

class YamlValuesTest {
  @Test
  void mapsMergeListsReplaceAndExplicitDeletion() {
    var base = YamlValues.parse("nested: {a: 1, b: 2}\nlist: [a, b]\nremoved: yes");
    var result =
        YamlValues.merge(
            base, YamlValues.parse("nested: {a: 3}\nlist: [c]\nremoved: {$delete: true}"));
    assertThat(result)
        .containsEntry("nested", Map.of("a", 3, "b", 2))
        .containsEntry("list", List.of("c"))
        .doesNotContainKey("removed");
    assertThat(base).containsKey("removed");
  }

  @Test
  void rejectsAmbiguousOrUnsafeYaml() {
    for (String yaml :
        List.of(
            "a: 1\na: 2",
            "a: null",
            "a: !!java.net.URL ['https://example.invalid']",
            "a: &x {b: 1}\nc: *x",
            "a: &x scalar\nb: *x",
            "[a, b]",
            "x: " + "a".repeat(65537)))
      assertThatThrownBy(() -> YamlValues.parse(yaml)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void cannotDeleteManagedParent() {
    assertThatThrownBy(
            () ->
                YamlValues.allowed(
                    YamlValues.parse("resources: {$delete: true}"),
                    Set.of("resources.requests.cpu"),
                    ""))
        .hasMessageContaining("managed");
  }
}
