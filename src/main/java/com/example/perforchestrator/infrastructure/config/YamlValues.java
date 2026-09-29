package com.example.perforchestrator.infrastructure.config;

import com.example.perforchestrator.domain.Problem;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlValues {
  private YamlValues() {}

  public static Map<String, Object> parse(String yaml) {
    if (yaml == null || yaml.isBlank()) return new TreeMap<>();
    if (yaml.length() > 65536) throw Problem.invalid("overlay", "YAML is limited to 64 KiB");
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(20);
    options.setCodePointLimit(65536);
    try {
      var parser = new Yaml(new SafeConstructor(options));
      for (var event : parser.parse(new java.io.StringReader(yaml))) {
        if (event instanceof org.yaml.snakeyaml.events.AliasEvent)
          throw Problem.invalid("overlay", "YAML aliases are not supported");
      }
      Object result = parser.load(yaml);
      if (!(result instanceof Map<?, ?> map))
        throw Problem.invalid("overlay", "YAML must be a mapping");
      return normalize(map);
    } catch (Problem e) {
      throw e;
    } catch (RuntimeException e) {
      throw Problem.invalid(
          "overlay", "Invalid YAML: duplicate keys, aliases, unsafe tags, or invalid syntax");
    }
  }

  private static Map<String, Object> normalize(Map<?, ?> map) {
    Map<String, Object> result = new TreeMap<>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key))
        throw Problem.invalid("overlay", "Keys must be strings");
      result.put(key, normalizeValue(entry.getValue()));
    }
    return result;
  }

  private static Object normalizeValue(Object value) {
    if (value == null)
      throw Problem.invalid(
          "overlay", "Null is not supported; use {$delete: true} to delete a field");
    if (value instanceof Map<?, ?> map) return normalize(map);
    if (value instanceof List<?> list)
      return list.stream().map(YamlValues::normalizeValue).toList();
    if (value instanceof String || value instanceof Boolean || value instanceof Number)
      return value;
    throw Problem.invalid("overlay", "Only JSON-compatible YAML values are supported");
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
    Map<String, Object> result = new TreeMap<>(base);
    for (var entry : overlay.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> map && map.containsKey("$delete")) {
        if (!map.equals(Map.of("$delete", true)))
          throw Problem.invalid("overlay", "Deletion must be exactly {$delete: true}");
        result.remove(entry.getKey());
      } else if (value instanceof Map<?, ?> map) {
        Map<String, Object> old =
            result.get(entry.getKey()) instanceof Map<?, ?> previous
                ? (Map<String, Object>) previous
                : Map.of();
        result.put(entry.getKey(), merge(old, (Map<String, Object>) map));
      } else result.put(entry.getKey(), value);
    }
    return result;
  }

  public static void allowed(Map<String, Object> map, Set<String> paths, String prefix) {
    for (var entry : map.entrySet()) {
      String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      if (entry.getValue() instanceof Map<?, ?> child && !child.containsKey("$delete")) {
        @SuppressWarnings("unchecked")
        var children = (Map<String, Object>) child;
        allowed(children, paths, path);
      } else if (!paths.contains(path))
        throw Problem.invalid(
            "overlay." + path, "Field is managed or not an approved override path");
    }
  }

  public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> out = new TreeMap<>();
    var keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (String key : keys)
      if (!Objects.equals(before.get(key), after.get(key)))
        out.put(
            key,
            Map.of(
                "before",
                before.getOrDefault(key, "(absent)"),
                "after",
                after.getOrDefault(key, "(deleted)")));
    return out;
  }
}
