package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.nio.file.*;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reports fixed diagnostic categories and existing source-file locations, never Helm output text. */
public final class HelmDiagnostics {

  private HelmDiagnostics() {}

  public static Problem failure(
    final String operation,
    final int exit,
    final String output,
    final Path folder,
    final String chart
  ) {
    final String text = output == null ? "" : output;
    final String errors = errorDiagnostics(text);
    final String lower = errors.toLowerCase(Locale.ROOT);
    String reason;
    if (missingDependencies(lower)) reason =
      "Missing packaged chart dependencies after dependency preparation. Check Chart.yaml dependency declarations and whether the expected library chart was downloaded into charts/.";
    else if (lower.contains("no template") && lower.contains("associated with template")) reason =
      "A referenced Helm helper template is unavailable. Check the chart's _helpers.tpl files and shared/library chart dependencies in charts/.";
    else if (
      lower.contains("schema") && (lower.contains("values") || lower.contains("validation"))
    ) reason =
      "Values do not satisfy the chart's JSON schema. Check required fields, types and permitted formats.";
    else if (
      lower.contains("nil pointer") ||
      lower.contains("can't evaluate field") ||
      lower.contains("cannot evaluate field")
    ) reason =
      "A template references a missing value or an unexpected value type. Check selected values files and required organization-specific --set values.";
    else if (lower.contains("required") || lower.contains("execution error")) reason =
      "The chart rejected a required value or a template validation rule. Compare the selected YAML with the values and --set arguments used in a successful deployment.";
    else if (
      lower.contains("yaml parse") ||
      lower.contains("unable to parse yaml") ||
      lower.contains("error converting yaml") ||
      lower.contains("did not find expected")
    ) reason =
      "Chart rendering produced invalid YAML. Inspect the indicated template and its input values.";
    else if (
      lower.contains("parse error") ||
      lower.contains("unexpected eof") ||
      (lower.contains("function") && lower.contains("not defined"))
    ) reason =
      "Helm could not parse a template. Check template syntax, helper definitions and the Helm version used by the application.";
    else if (
      lower.contains("chart.yaml") &&
      (lower.contains("missing") || lower.contains("version") || lower.contains("required"))
    ) reason = "Helm rejected chart metadata. Check Chart.yaml in the selected revision.";
    else reason =
      "Helm validation failed. Run helm lint locally with the same Git revision, namespace, values files and overrides to inspect the full diagnostic.";
    if (!missingDependencies(lower) && missingDependencies(text.toLowerCase(Locale.ROOT))) reason +=
      " Helm also reported missing chart dependencies; these may explain missing shared helpers. Check dependency declarations and the resolved charts/ contents.";
    final String location = location(errors, folder, chart);
    return new Problem(
      422,
      "HELM_" + operation.toUpperCase(Locale.ROOT) + "_FAILED",
      "execution",
      "Helm " +
        operation +
        " failed (exit " +
        exit +
        ")" +
        (location.isEmpty() ? "" : " at " + location) +
        ". " +
        reason +
        " Raw output is not included because template errors can contain secret values."
    );
  }

  private static boolean missingDependencies(final String text) {
    return (
      text.contains("missing in charts/") ||
      text.contains("missing these dependencies") ||
      text.contains("found in chart.yaml, but missing")
    );
  }

  /** Lint emits warnings and errors together; a dependency warning must not hide a template error. */
  private static String errorDiagnostics(final String text) {
    final StringBuilder errors = new StringBuilder();
    boolean collecting = false;
    for (final String line : text.split("\\R")) {
      final String trimmed = line.stripLeading();
      if (trimmed.startsWith("[ERROR]")) collecting = true;
      else if (
        trimmed.startsWith("[WARNING]") || trimmed.startsWith("[INFO]") || trimmed.startsWith("==>")
      ) collecting = false;
      if (collecting) errors.append(line).append('\n');
    }
    return errors.isEmpty() ? text : errors.toString();
  }

  private static String location(final String text, final Path folder, final String chart) {
    final Path root = folder.resolve(chart);
    try (var paths = Files.walk(root)) {
      for (final Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        final String relative = root.relativize(path).toString().replace('\\', '/');
        if (!relative.matches("[A-Za-z0-9_./-]{1,250}")) continue;
        final var match = Pattern.compile(
          Pattern.quote(relative) + ":([0-9]{1,8})(?::[0-9]+)?"
        ).matcher(text);
        if (match.find()) return chart + "/" + relative + ":" + match.group(1);
      }
    } catch (final java.io.IOException ignored) {
      /* Source location is optional. */
    }
    return "";
  }
}
