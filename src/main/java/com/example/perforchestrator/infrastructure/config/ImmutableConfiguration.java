package com.example.perforchestrator.infrastructure.config;

import java.util.*;

/** Defensive, insertion-order-preserving copies for JSON/YAML configuration records. */
public final class ImmutableConfiguration {

  private ImmutableConfiguration() {}

  public static <T> List<T> list(final List<T> value) {
    return value == null ? null : Collections.unmodifiableList(new ArrayList<>(value));
  }

  public static <K, V> Map<K, V> map(final Map<K, V> value) {
    return value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  public static Map<String, Object> values(final Map<String, Object> value) {
    if (value == null) return null;
    final Map<String, Object> copy = new LinkedHashMap<>();
    value.forEach((final var key, final var item) -> copy.put(key, freeze(item)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object freeze(final Object value) {
    if (value instanceof final Map<?, ?> mapping) {
      final Map<Object, Object> copy = new LinkedHashMap<>();
      mapping.forEach((final var key, final var item) -> copy.put(key, freeze(item)));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof final List<?> items) {
      final List<Object> copy = new ArrayList<>();
      items.forEach((final var item) -> copy.add(freeze(item)));
      return Collections.unmodifiableList(copy);
    }
    return value;
  }
}
