package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.Problem;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlValues {

  private YamlValues() {}

  public static Map<String, Object> parse(final String yaml) {
    if (yaml == null || yaml.isBlank()) return new TreeMap<>();
    if (yaml.length() > 65536) throw Problem.invalid("overlay", "YAML is limited to 64 KiB");
    final var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(20);
    options.setCodePointLimit(65536);
    try {
      final var parser = new Yaml(new SafeConstructor(options));
      for (final var event : parser.parse(new java.io.StringReader(yaml))) {
        if (event instanceof org.yaml.snakeyaml.events.AliasEvent) throw Problem.invalid(
          "overlay",
          "YAML aliases are not supported"
        );
      }
      final Object result = parser.load(yaml);
      if (!(result instanceof final Map<?, ?> map)) throw Problem.invalid(
        "overlay",
        "YAML must be a mapping"
      );
      return normalize(map);
    } catch (final Problem e) {
      throw e;
    } catch (final RuntimeException e) {
      throw Problem.invalid(
        "overlay",
        "Invalid YAML: duplicate keys, aliases, unsafe tags, or invalid syntax"
      );
    }
  }

  private static Map<String, Object> normalize(final Map<?, ?> map) {
    final Map<String, Object> result = new TreeMap<>();
    for (final var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof final String key)) throw Problem.invalid(
        "overlay",
        "Keys must be strings"
      );
      result.put(key, normalizeValue(entry.getValue()));
    }
    return result;
  }

  private static Object normalizeValue(final Object value) {
    if (value == null) throw Problem.invalid(
      "overlay",
      "Null is not supported; use {$delete: true} to delete a field"
    );
    if (value instanceof final Map<?, ?> map) return normalize(map);
    if (value instanceof final List<?> list) return list
      .stream()
      .map(YamlValues::normalizeValue)
      .toList();
    if (
      value instanceof String || value instanceof Boolean || value instanceof Number
    ) return value;
    throw Problem.invalid("overlay", "Only JSON-compatible YAML values are supported");
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> merge(
    final Map<String, Object> base,
    final Map<String, Object> overlay
  ) {
    final Map<String, Object> result = new TreeMap<>(base);
    for (final var entry : overlay.entrySet()) {
      final Object value = entry.getValue();
      if (value instanceof final Map<?, ?> map && map.containsKey("$delete")) {
        if (!map.equals(Map.of("$delete", true))) throw Problem.invalid(
          "overlay",
          "Deletion must be exactly {$delete: true}"
        );
        result.remove(entry.getKey());
      } else if (value instanceof final Map<?, ?> map) {
        final Map<String, Object> old =
          result.get(entry.getKey()) instanceof final Map<?, ?> previous
            ? (Map<String, Object>) previous
            : Map.of();
        result.put(entry.getKey(), merge(old, (Map<String, Object>) map));
      } else result.put(entry.getKey(), value);
    }
    return result;
  }

  public static void allowed(
    final Map<String, Object> map,
    final Set<String> paths,
    final String prefix
  ) {
    for (final var entry : map.entrySet()) {
      final String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      if (entry.getValue() instanceof final Map<?, ?> child && !child.containsKey("$delete")) {
        final @SuppressWarnings("unchecked") var children = (Map<String, Object>) child;
        allowed(children, paths, path);
      } else if (!paths.contains(path)) throw Problem.invalid(
        "overlay." + path,
        "Field is managed or not an approved override path"
      );
    }
  }

  public static Map<String, Object> diff(
    final Map<String, Object> before,
    final Map<String, Object> after
  ) {
    final Map<String, Object> out = new TreeMap<>();
    final var keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (final String key : keys)
      if (!Objects.equals(before.get(key), after.get(key))) out.put(
        key,
        Map.of(
          "before",
          before.getOrDefault(key, "(absent)"),
          "after",
          after.getOrDefault(key, "(deleted)")
        )
      );
    return out;
  }
}
