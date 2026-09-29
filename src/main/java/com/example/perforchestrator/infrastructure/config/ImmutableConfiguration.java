package com.example.perforchestrator.infrastructure.config;

import java.util.*;

/** Defensive, insertion-order-preserving copies for JSON/YAML configuration records. */
public final class ImmutableConfiguration {
  private ImmutableConfiguration() {}

  public static <T> List<T> list(List<T> value) {
    return value == null ? null : Collections.unmodifiableList(new ArrayList<>(value));
  }

  public static <K, V> Map<K, V> map(Map<K, V> value) {
    return value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  public static Map<String, Object> values(Map<String, Object> value) {
    if (value == null) return null;
    Map<String, Object> copy = new LinkedHashMap<>();
    value.forEach((key, item) -> copy.put(key, freeze(item)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object freeze(Object value) {
    if (value instanceof Map<?, ?> mapping) {
      Map<Object, Object> copy = new LinkedHashMap<>();
      mapping.forEach((key, item) -> copy.put(key, freeze(item)));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> items) {
      List<Object> copy = new ArrayList<>();
      items.forEach(item -> copy.add(freeze(item)));
      return Collections.unmodifiableList(copy);
    }
    return value;
  }
}
