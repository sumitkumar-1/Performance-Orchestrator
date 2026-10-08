package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;

import com.example.perforchestrator.infrastructure.execution.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChartVersionsTest {

  private static String encode(final String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(final String text) {
    return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
  }

  /**
   * <b>Scenario:</b> Replaces Metadata In Selected Chart And Unpacked Dependencies Without Changing Source Or Other Files
   * <pre>
   * GIVEN ... chart metadata placeholders in a selected chart and unpacked dependencies
   * WHEN ... versions are replaced in the prepared chart
   * THEN ... metadata is updated without modifying source content or unrelated files
   * </pre>
   */
  @Test
  @DisplayName(
    "Replaces Metadata In Selected Chart And Unpacked Dependencies Without Changing Source Or Other Files"
  )
  void replacesMetadataInSelectedChartAndUnpackedDependenciesWithoutChangingSourceOrOtherFiles() {
    final String chart = "ckp/helm/service";
    final String yaml =
      "apiVersion: v2\nname: service\nversion: '__REPLACEAPPVERSION__'\nappVersion: __REPLACEAPPVERSION__\n";
    final var files = Map.of(
      chart + "/Chart.yaml",
      encode(yaml),
      chart + "/charts/child/Chart.yaml",
      encode(yaml),
      chart + "/values.yaml",
      encode("value: __REPLACEAPPVERSION__\n"),
      "ckp/helm/other/Chart.yaml",
      encode(yaml)
    );
    final String tag = "1.11.0.260811-12-4309-05-791d17a0a4bf";
    final var prepared = ChartVersions.prepare(files, chart, tag);
    for (final String path : List.of(chart + "/Chart.yaml", chart + "/charts/child/Chart.yaml")) {
      assertThat(HelmValues.parse(decode(prepared.get(path))))
        .containsEntry("version", "1.11.0-build.260811-12-4309-05-791d17a0a4bf")
        .containsEntry("appVersion", tag)
        .containsEntry("name", "service");
      assertThat(decode(files.get(path))).isEqualTo(yaml);
    }
    assertThat(prepared.get(chart + "/values.yaml")).isEqualTo(files.get(chart + "/values.yaml"));
    assertThat(prepared.get("ckp/helm/other/Chart.yaml")).isEqualTo(
      files.get("ckp/helm/other/Chart.yaml")
    );
  }

  /**
   * <b>Scenario:</b> Preserves Explicit Versions And Quotes Numeric Looking App Versions
   * <pre>
   * GIVEN ... explicit chart versions and numeric-looking application versions
   * WHEN ... chart metadata is prepared
   * THEN ... explicit versions remain and application versions are quoted as strings
   * </pre>
   */
  @Test
  @DisplayName("Preserves Explicit Versions And Quotes Numeric Looking App Versions")
  void preservesExplicitVersionsAndQuotesNumericLookingAppVersions() {
    final String path = "ckp/chart/Chart.yaml";
    final var fixed = Map.of(path, encode("version: 2.3.4\nappVersion: pinned\n"));
    assertThat(ChartVersions.prepare(fixed, "ckp/chart", "1.11.0")).isEqualTo(fixed);
    final var prepared = ChartVersions.prepare(
      Map.of(path, encode("version: 2.3.4\nappVersion: __REPLACEAPPVERSION__\n")),
      "ckp/chart",
      "12345"
    );
    assertThat(HelmValues.parse(decode(prepared.get(path))))
      .containsEntry("version", "2.3.4")
      .containsEntry("appVersion", "12345");
    assertThat(ChartVersions.chartVersion("1.11.0")).isEqualTo("1.11.0");
    assertThat(ChartVersions.chartVersion("v1.11.0-rc.1")).isEqualTo("1.11.0-rc.1");
    assertThat(ChartVersions.chartVersion("791d17a0a4bf")).isEqualTo("0.0.0-build.791d17a0a4bf");
    assertThat(ChartVersions.chartVersion("001")).isEqualTo("0.0.0-build.tag-001");
    assertThat(ChartVersions.chartVersion("1.11.0.001")).isEqualTo("1.11.0-build.tag-001");
    assertThatThrownBy(() -> ChartVersions.chartVersion("bad\nvalue")).hasMessageContaining(
      "valid image tag"
    );
  }
}
