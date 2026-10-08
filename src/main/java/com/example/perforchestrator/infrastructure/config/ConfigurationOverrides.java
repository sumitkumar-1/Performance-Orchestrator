package com.example.perforchestrator.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Field-level edits. Arrays are replaced as units; removals and explicit nulls are distinct. */
public final class ConfigurationOverrides {

  private ConfigurationOverrides() {}

  public record Change(String operation, List<String> path, JsonNode value) {}

  public static List<Change> diff(final JsonNode baseline, final JsonNode effective) {
    final List<Change> changes = new ArrayList<>();
    compare(baseline, effective, List.of(), changes);
    return changes;
  }

  private static void compare(
    final JsonNode before,
    final JsonNode after,
    final List<String> path,
    final List<Change> changes
  ) {
    if (Objects.equals(before, after)) return;
    if (before != null && before.isObject() && after.isObject()) {
      final Set<String> keys = new TreeSet<>();
      before.fieldNames().forEachRemaining(keys::add);
      after.fieldNames().forEachRemaining(keys::add);
      for (final String key : keys) {
        final List<String> child = new ArrayList<>(path);
        child.add(key);
        if (!after.has(key)) changes.add(new Change("remove", child, null));
        else compare(before.get(key), after.get(key), child, changes);
      }
    } else changes.add(new Change("set", path, after.deepCopy()));
  }

  public static JsonNode apply(final JsonNode baseline, final List<Change> changes) {
    final JsonNode result = baseline.deepCopy();
    changesLoop: for (final Change change : changes) {
      final var path = change.path();
      if (
        path == null ||
        path.size() < 2 ||
        path.size() > 64 ||
        !Set.of("catalog", "connections").contains(path.get(0))
      ) throw new IllegalArgumentException("Invalid configuration override path");
      ObjectNode parent = (ObjectNode) result;
      for (final String key : path.subList(0, path.size() - 1)) {
        JsonNode child = parent.get(key);
        if (child == null && "remove".equals(change.operation())) continue changesLoop;
        if (child == null) child = parent.putObject(key);
        if (!child.isObject()) throw new IllegalArgumentException(
          "Override conflicts with startup configuration"
        );
        parent = (ObjectNode) child;
      }
      final String key = path.get(path.size() - 1);
      if ("remove".equals(change.operation())) parent.remove(key);
      else if ("set".equals(change.operation()) && change.value() != null) parent.set(
        key,
        change.value().deepCopy()
      );
      else throw new IllegalArgumentException("Invalid configuration override operation");
    }
    return result;
  }
}
