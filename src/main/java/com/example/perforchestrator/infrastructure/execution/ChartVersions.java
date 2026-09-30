package com.example.perforchestrator.infrastructure.execution;

import com.example.perforchestrator.domain.Problem;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Applies build-time metadata only to the selected chart's prepared snapshot. */
public final class ChartVersions {
  private static final String PLACEHOLDER = "__REPLACEAPPVERSION__";
  private static final String NUMBER = "(?:0|[1-9][0-9]*)";
  private static final String CORE = NUMBER + "\\." + NUMBER + "\\." + NUMBER;
  private static final String IDENTIFIER = "(?:" + NUMBER + "|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
  private static final Pattern SEMVER = Pattern.compile(CORE + "(?:-" + IDENTIFIER + "(?:\\." + IDENTIFIER
      + ")*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
  private static final Pattern DATED_BUILD = Pattern.compile("^(" + CORE + ")\\.(.+)$");

  private ChartVersions() {}

  public static Map<String,String> prepare(Map<String,String> files, String chart, String imageVersion) {
    var prepared = new TreeMap<>(files);
    files.forEach((path, encoded) -> {
      if (!path.startsWith(chart + "/") || !path.endsWith("/Chart.yaml")) return;
      String text = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      if (!text.contains(PLACEHOLDER)) return;
      var metadata = HelmValues.parse(text);
      boolean changed = false;
      if (PLACEHOLDER.equals(metadata.get("version"))) {
        metadata.put("version", chartVersion(imageVersion));
        changed = true;
      }
      if (PLACEHOLDER.equals(metadata.get("appVersion"))) {
        metadata.put("appVersion", imageVersion);
        changed = true;
      }
      if (changed) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        prepared.put(path, Base64.getEncoder().encodeToString(new Yaml(options).dump(metadata).getBytes(StandardCharsets.UTF_8)));
      }
    });
    return Map.copyOf(prepared);
  }

  public static String chartVersion(String imageVersion) {
    if (imageVersion == null || !imageVersion.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))
      throw Problem.invalid("imageVersion", "Select a valid image tag for chart version replacement");
    String version = imageVersion.startsWith("v") ? imageVersion.substring(1) : imageVersion;
    if (SEMVER.matcher(version).matches()) return version;
    var dated = DATED_BUILD.matcher(version);
    if (dated.matches()) return dated.group(1) + "-build." + buildIdentifier(dated.group(2));
    return "0.0.0-build." + buildIdentifier(imageVersion);
  }

  private static String buildIdentifier(String tag) {
    String identifier = tag.replaceAll("[._]", "-");
    return identifier.matches("0[0-9]+") ? "tag-" + identifier : identifier;
  }
}
