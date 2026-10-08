package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.infrastructure.execution.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class HelmRenderValidationTest {

  @TempDir
  Path directory;

  /**
   * <b>Scenario:</b> Helm warnings do not corrupt rendered manifests
   * <pre>
   * GIVEN ... a Helm process emitting valid YAML and a warning on stderr
   * WHEN ... the application renders and validates the chart
   * THEN ... the selected image is accepted and warning text is excluded from YAML
   * </pre>
   */
  @Test
  @DisplayName(
    "Given valid manifests and stderr warnings, when reviewing a chart, then validation succeeds"
  )
  void warningsDoNotCorruptRenderedYaml() throws Exception {
    final Path helper = directory.resolve("helm");
    Files.writeString(
      helper,
      """
      #!/bin/sh
      if [ "$1" = lint ]; then exit 0; fi
      printf 'warning: [not yaml\n' >&2
      printf 'spec:\n  containers:\n    - image: registry/service:1.0.0\n'
      """
    );
    assertThat(helper.toFile().setExecutable(true)).isTrue();
    final var runner = new CommandRunner() {
      @Override
      public Result run(
        final List<String> args,
        final Path folder,
        final Map<String, String> environment,
        final Duration timeout
      ) {
        final var local = new ArrayList<>(args);
        local.set(0, helper.toString());
        return super.run(local, folder, environment, timeout);
      }
    };
    final var helm = new HelmExecution(runner, new ExecutionSettings(new MockEnvironment()));
    assertThatCode(() ->
      helm.validate(directory, "chart", "ns", "release", "service:1.0.0")
    ).doesNotThrowAnyException();
  }

  /**
   * <b>Scenario:</b> Duplicate keys produce a useful secret-safe parsing error
   * <pre>
   * GIVEN ... Helm exits successfully but renders duplicate mapping keys with sensitive values
   * WHEN ... the application parses the manifests
   * THEN ... the error identifies the category and position without exposing those values
   * </pre>
   */
  @Test
  @DisplayName(
    "Given duplicate YAML keys, when validating a render, then report its location without manifest contents"
  )
  void duplicateKeysReportLocationWithoutValues() {
    final var runner = mock(CommandRunner.class);
    when(runner.run(any(), any(), any(), any())).thenReturn(
      new CommandRunner.Result(0, ""),
      new CommandRunner.Result(
        0,
        "data:\n  sensitive-key: first-secret\n  sensitive-key: second-secret\n"
      )
    );
    final var helm = new HelmExecution(runner, new ExecutionSettings(new MockEnvironment()));
    assertThatThrownBy(() -> helm.validate(directory, "chart", "ns", "release", "service:1.0.0"))
      .hasMessageContaining("duplicate mapping key")
      .hasMessageContaining("rendered line 3, column 3")
      .hasMessageNotContaining("sensitive-key")
      .hasMessageNotContaining("first-secret")
      .hasMessageNotContaining("second-secret");
  }

  /**
   * <b>Scenario:</b> Failed rendering retains stderr for existing Helm error classification
   * <pre>
   * GIVEN ... Helm reports a template failure only on stderr
   * WHEN ... the command exits unsuccessfully
   * THEN ... the command result retains the error for the secret-safe diagnostic classifier
   * </pre>
   */
  @Test
  @DisplayName("Given a failed render, when Helm writes stderr, then failure diagnostics retain it")
  void failedRenderRetainsStderr() throws Exception {
    final Path helper = directory.resolve("helm");
    Files.writeString(helper, "#!/bin/sh\nprintf 'template failed' >&2\nexit 1\n");
    assertThat(helper.toFile().setExecutable(true)).isTrue();
    final var result = new CommandRunner().run(
      List.of(helper.toString(), "template"),
      directory,
      Map.of(),
      Duration.ofSeconds(5)
    );
    assertThat(result.exit()).isEqualTo(1);
    assertThat(result.output()).contains("template failed");
  }
}
