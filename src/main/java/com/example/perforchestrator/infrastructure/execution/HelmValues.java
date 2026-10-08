package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Helm values permit null (to remove defaults), unlike the simulation overlay format. */
public final class HelmValues {

  private HelmValues() {}

  public static Map<String, Object> parse(final String text) {
    if (text == null || text.isBlank()) return new LinkedHashMap<>();
    if (text.length() > 65536) throw Problem.invalid("values", "Values are limited to 64 KiB");
    final var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(30);
    options.setNestingDepthLimit(30);
    try {
      final var value = new Yaml(new SafeConstructor(options)).load(text);
      if (!(value instanceof final Map<?, ?> map)) throw new IllegalArgumentException();
      return normalize(map);
    } catch (final Exception e) {
      throw Problem.invalid(
        "values",
        "Values must be a YAML mapping without duplicate keys or unsupported types"
      );
    }
  }

  private static Map<String, Object> normalize(final Map<?, ?> map) {
    final Map<String, Object> result = new LinkedHashMap<>();
    for (final var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof final String key)) throw new IllegalArgumentException();
      result.put(key, normalizeValue(entry.getValue()));
    }
    return result;
  }

  private static Object normalizeValue(final Object v) {
    if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number) return v;
    if (v instanceof final Map<?, ?> m) return normalize(m);
    if (v instanceof final List<?> l) return l.stream().map(HelmValues::normalizeValue).toList();
    throw new IllegalArgumentException();
  }

  public static Map<String, Object> diff(
    final Map<String, Object> before,
    final Map<String, Object> after
  ) {
    final Map<String, Object> changes = new TreeMap<>();
    final Set<String> keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (final String key : keys)
      if (
        !Objects.equals(before.get(key), after.get(key)) ||
        before.containsKey(key) != after.containsKey(key)
      ) {
        final Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before.containsKey(key) ? before.get(key) : "(absent)");
        change.put("after", after.containsKey(key) ? after.get(key) : "(deleted)");
        changes.put(key, change);
      }
    return changes;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> merge(
    final Map<String, Object> base,
    final Map<String, Object> overlay
  ) {
    final var result = new LinkedHashMap<>(base);
    overlay.forEach((final var key, final var value) ->
      result.put(
        key,
        value instanceof final Map<?, ?> map && result.get(key) instanceof final Map<?, ?> old
          ? merge((Map<String, Object>) old, (Map<String, Object>) map)
          : value
      )
    );
    return result;
  }
}
