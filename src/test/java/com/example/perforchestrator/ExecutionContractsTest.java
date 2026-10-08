package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ExecutionContractsTest {

  @TempDir
  Path temp;

  ExecutionSettings settings() {
    return new ExecutionSettings(
      new MockEnvironment()
        .withProperty("orchestrator.execution.enabled", "true")
        .withProperty("orchestrator.execution.workspace", temp.toString())
        .withProperty("orchestrator.execution.kube-context", "sandbox")
        .withProperty("orchestrator.execution.expected-api-server", "https://cluster.invalid")
    );
  }

  /**
   * <b>Scenario:</b> Given Targets When Selecting Environment Then Pin Context And Reject Unknown Targets
   * <pre>
   * GIVEN ... sandbox and perf targets with configured cluster contexts
   * WHEN ... execution settings select a target environment
   * THEN ... commands pin the selected context and invalid targets or server identities are rejected
   * </pre>
   */
  @Test
  @DisplayName(
    "Given multiple environment targets, when selecting a cluster, then commands use its context without changing kubeconfig"
  )
  void givenTargetsWhenSelectingEnvironmentThenPinContextAndRejectUnknownTargets() {
    final var environment = new MockEnvironment()
      .withProperty("orchestrator.execution.enabled", "true")
      .withProperty("orchestrator.execution.workspace", temp.toString())
      .withProperty("orchestrator.execution.targets.sandbox.kube-context", "sandbox-nvan")
      .withProperty(
        "orchestrator.execution.targets.sandbox.expected-api-server",
        "https://sandbox.invalid"
      )
      .withProperty("orchestrator.execution.targets.perf.kube-context", "perf-nvan")
      .withProperty(
        "orchestrator.execution.targets.perf.expected-api-server",
        "https://perf.invalid"
      );
    final var commands = mock(CommandRunner.class);
    when(
      commands.require(anyList(), any(), anyMap(), any(), eq("Cluster identity check"))
    ).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      assertThat(args).doesNotContain("use-context");
      return args.contains("perf-nvan") ? "https://perf.invalid" : "https://sandbox.invalid";
    });
    final var helm = new HelmExecution(commands, new ExecutionSettings(environment));
    final var sandbox = helm.target("sandbox");
    final var perf = helm.target("perf");
    assertThat(sandbox)
      .containsEntry("context", "sandbox-nvan")
      .containsEntry("server", "https://sandbox.invalid");
    assertThat(perf)
      .containsEntry("context", "perf-nvan")
      .containsEntry("server", "https://perf.invalid");
    assertThatThrownBy(() -> helm.target("unknown")).hasMessageContaining(
      "Configure an execution target"
    );
    when(
      commands.require(anyList(), any(), anyMap(), any(), eq("Cluster identity check"))
    ).thenReturn("https://unexpected.invalid");
    assertThatThrownBy(() -> helm.target("perf")).hasMessageContaining("differs");
  }

  /**
   * <b>Scenario:</b> Command Runner Uses Argument Arrays And Enforces Timeout
   * <pre>
   * GIVEN ... a command containing shell metacharacters and a long-running command
   * WHEN ... the command runner executes each with a timeout
   * THEN ... arguments stay literal and the long-running command times out
   * </pre>
   */
  @Test
  @DisplayName("Command Runner Uses Argument Arrays And Enforces Timeout")
  void commandRunnerUsesArgumentArraysAndEnforcesTimeout() {
    final var runner = new CommandRunner();
    final var result = runner.run(
      List.of("/usr/bin/printf", "%s", "$(touch should-not-exist); a b"),
      temp,
      Map.of(),
      Duration.ofSeconds(2)
    );
    assertThat(result.output()).isEqualTo("$(touch should-not-exist); a b");
    assertThat(temp.resolve("should-not-exist")).doesNotExist();
    assertThatThrownBy(() ->
      runner.run(List.of("/bin/sleep", "2"), temp, Map.of(), Duration.ofMillis(30))
    ).hasMessageContaining("timed out");
  }

  /**
   * <b>Scenario:</b> Execution Timeouts Are Configurable And Validate Default Against Maximum
   * <pre>
   * GIVEN ... configurable Helm and overall run timeouts
   * WHEN ... timeout defaults and maximums are validated
   * THEN ... valid values are accepted and inconsistent limits are rejected
   * </pre>
   */
  @Test
  @DisplayName("Execution Timeouts Are Configurable And Validate Default Against Maximum")
  void executionTimeoutsAreConfigurableAndValidateDefaultAgainstMaximum() {
    final var env = new MockEnvironment()
      .withProperty("orchestrator.execution.command-timeout-seconds", "600")
      .withProperty("orchestrator.execution.default-run-duration-seconds", "7200")
      .withProperty("orchestrator.execution.max-run-duration-seconds", "259200");
    final var configured = new ExecutionSettings(env);
    assertThat(configured.timeoutSeconds).isEqualTo(600);
    assertThat(configured.defaultRunDurationSeconds).isEqualTo(7200);
    assertThat(configured.maxRunDurationSeconds).isEqualTo(259200);
    env.setProperty("orchestrator.execution.max-run-duration-seconds", "3600");
    assertThatThrownBy(() -> new ExecutionSettings(env)).hasMessageContaining(
      "default run duration"
    );
  }

  /**
   * <b>Scenario:</b> Discovers Clone Url And Checks Out Only CKP With Token Off Command Line
   * <pre>
   * GIVEN ... a Bitbucket project exposing an HTTPS clone URL
   * WHEN ... the selected revision is checked out
   * THEN ... only CKP content is requested and the token stays off command arguments
   * </pre>
   */
  @Test
  @DisplayName("Discovers Clone Url And Checks Out Only Ckp With Token Off Command Line")
  void discoversCloneUrlAndChecksOutOnlyCkpWithTokenOffCommandLine() throws Exception {
    final var config = new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(
          "stash",
          new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api", null, "token")
        )
      )
    );
    final var sessions = new ConnectionSessions(config, (final var ref) -> {
      throw new AssertionError();
    });
    final var request = new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
      new org.springframework.web.context.request.ServletRequestAttributes(request)
    );
    sessions.remember(
      "bitbucket",
      "stash",
      new RequestAuthentication(null, null, "private-token"),
      300,
      request.getSession()
    );
    final var http = mock(ReadOnlyHttp.class);
    when(http.get(any(), any(), any())).thenReturn(
      new ReadOnlyHttp.Response(
        200,
        Map.of(),
        "{\"links\":{\"clone\":[{\"name\":\"http\",\"href\":\"https://stash.invalid/scm/SP/receiver.git\"}]}}".getBytes(
          StandardCharsets.UTF_8
        )
      )
    );
    final var commands = mock(CommandRunner.class);
    final List<List<String>> calls = new ArrayList<>();
    when(commands.require(any(), any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      final Path root = call.getArgument(1);
      final Map<String, String> env = call.getArgument(2);
      calls.add(args);
      assertThat(args.toString()).doesNotContain("private-token");
      assertThat(env.get("GIT_CONFIG_VALUE_0")).isEqualTo("Authorization: Bearer private-token");
      if (args.contains("rev-parse")) return "a".repeat(40) + "\n";
      if (args.contains("checkout")) {
        Files.createDirectories(root.resolve("ckp/helm/service"));
        Files.writeString(
          root.resolve("ckp/helm/service/Chart.yaml"),
          "apiVersion: v2\nname: service\nversion: 1.0.0\n"
        );
      }
      return "";
    });
    try {
      final var source = new Catalog.SourceProject(
        "stash",
        "SP",
        "receiver",
        "main",
        "ckp/helm/service"
      );
      final var snapshot = new SparseProjects(
        commands,
        settings(),
        config,
        sessions,
        http
      ).checkout(source, "main");
      assertThat(snapshot.commit()).isEqualTo("a".repeat(40));
      assertThat(snapshot.files()).containsOnlyKeys("ckp/helm/service/Chart.yaml");
      assertThat(calls).anySatisfy((final var args) ->
        assertThat(args).containsSubsequence("sparse-checkout", "set", "--no-cone", "/ckp/")
      );
      assertThat(calls).anySatisfy((final var args) ->
        assertThat(args).contains("--depth=1", "--filter=blob:none")
      );
      assertThat(Files.list(temp).toList()).isEmpty();
    } finally {
      sessions.close();
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }

  /**
   * <b>Scenario:</b> Refuses Wrong Cluster And Unowned Load Cleanup
   * <pre>
   * GIVEN ... a cluster identity mismatch or a load release owned by another run
   * WHEN ... cluster validation or load cleanup is attempted
   * THEN ... execution refuses the wrong cluster and does not remove an unowned release
   * </pre>
   */
  @Test
  @DisplayName("Refuses Wrong Cluster And Unowned Load Cleanup")
  void refusesWrongClusterAndUnownedLoadCleanup() {
    final var runner = mock(CommandRunner.class);
    final var helm = new HelmExecution(runner, settings());
    when(runner.require(any(), any(), any(), any(), any())).thenReturn("https://wrong.invalid");
    assertThatThrownBy(helm::target).hasMessageContaining("differs");
    when(runner.require(any(), any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      if (args.contains("view")) return "https://cluster.invalid";
      if (args.contains("version")) return "v3.17.3";
      if (args.contains("list")) return "[{\"name\":\"load\"}]";
      if (args.contains("status")) return "{\"info\":{\"description\":\"someone-else\"}}";
      throw new AssertionError("Unexpected mutation: " + args);
    });
    final var service = new com.example.perforchestrator.domain.Model.PreparedService(
      "load",
      Model.Action.DEPLOY,
      "ns",
      "load",
      null,
      "ABSENT",
      "commit",
      Map.of(),
      Map.of(),
      Map.of(),
      Map.of(),
      Map.of(),
      List.of()
    );
    assertThatThrownBy(() -> helm.stop("run", helm.target(), service)).hasMessageContaining(
      "ownership"
    );
  }

  /**
   * <b>Scenario:</b> Helm Lint Uses Target Namespace And Reports Safe Template Location
   * <pre>
   * GIVEN ... a chart whose Helm lint response identifies a template failure
   * WHEN ... lint runs for the target namespace
   * THEN ... the command includes the namespace and the error reports a safe template location
   * </pre>
   */
  @Test
  @DisplayName("Helm Lint Uses Target Namespace And Reports Safe Template Location")
  void helmLintUsesTargetNamespaceAndReportsSafeTemplateLocation() throws Exception {
    final Path chart = temp.resolve("ckp/helm/service");
    Files.createDirectories(chart.resolve("templates"));
    Files.writeString(chart.resolve("templates/deployment.yaml"), "placeholder");
    final var runner = mock(CommandRunner.class);
    when(runner.run(any(), any(), any(), any())).thenReturn(
      new CommandRunner.Result(
        1,
        "[ERROR] templates/: template: service/templates/deployment.yaml:12:5: executing at <.Values.credentials.password>: nil pointer evaluating interface {}.password SECRET-VALUE"
      )
    );
    final var helm = new HelmExecution(runner, settings());
    assertThatThrownBy(() ->
      helm.validate(temp, "ckp/helm/service", "target-ns", "release", "sha256:test")
    )
      .hasMessageContaining("missing value")
      .hasMessageContaining("ckp/helm/service/templates/deployment.yaml:12")
      .hasMessageNotContaining("SECRET-VALUE")
      .hasMessageNotContaining("credentials.password");
    verify(runner).run(
      argThat(
        (final var args) ->
          args.contains("lint") && args.contains("--namespace") && args.contains("target-ns")
      ),
      eq(temp),
      any(),
      any()
    );
    verifyNoMoreInteractions(runner);
  }

  /**
   * <b>Scenario:</b> Rendered Image Must Use Exact Selected Name And Tag
   * <pre>
   * GIVEN ... a selected release or hashed development image version
   * WHEN ... rendered chart images are validated
   * THEN ... only the exact selected image name and tag are accepted
   * </pre>
   */
  @org.junit.jupiter.params.ParameterizedTest
  @DisplayName("Rendered Image Must Use Exact Selected Name And Tag")
  @org.junit.jupiter.params.provider.ValueSource(
    strings = { "1.16.0", "1.11.0.260811-12-4309-05-791d17a0a4bf" }
  )
  void renderedImageMustUseExactSelectedNameAndTag(final String version) {
    final var runner = mock(CommandRunner.class);
    final var helm = new HelmExecution(runner, settings());
    final String expected = "receiver:" + version;
    when(runner.run(any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      return new CommandRunner.Result(
        0,
        args.contains("template")
          ? "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n  - name: receiver\n    image: registry.example:5000/team/" +
              expected +
              "\n"
          : "ok"
      );
    });
    assertThatCode(() ->
      helm.validate(temp, "ckp/chart", "ns", "release", expected)
    ).doesNotThrowAnyException();
    for (final String wrong : List.of(
      "receiver:" + version + "-other",
      "sidecar:" + version,
      "receiver:" + version + "@sha256:abc"
    )) {
      doReturn(
        new CommandRunner.Result(
          0,
          "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n  - name: receiver\n    image: registry.example/team/" +
            wrong +
            "\n"
        )
      )
        .when(runner)
        .run(any(), any(), any(), any());
      assertThatThrownBy(() ->
        helm.validate(temp, "ckp/chart", "ns", "release", expected)
      ).hasMessageContaining("exact version");
    }
  }

  /**
   * <b>Scenario:</b> Helm Diagnostics Do Not Assume Dependencies Or Expose Raw Output
   * <pre>
   * GIVEN ... Helm failures with potentially sensitive output
   * WHEN ... safe diagnostics classify the failures
   * THEN ... messages avoid unsupported dependency assumptions and omit raw output
   * </pre>
   */
  @Test
  @DisplayName("Helm Diagnostics Do Not Assume Dependencies Or Expose Raw Output")
  void helmDiagnosticsDoNotAssumeDependenciesOrExposeRawOutput() {
    final var missing = HelmDiagnostics.failure(
      "lint",
      1,
      "[ERROR] Chart.yaml: chart metadata is missing these dependencies: private-name",
      temp,
      "ckp/chart"
    );
    assertThat(missing.getMessage())
      .contains("Missing packaged chart dependencies")
      .doesNotContain("private-name");
    final var schema = HelmDiagnostics.failure(
      "lint",
      1,
      "values don't meet the specifications of the schema(s): token=private",
      temp,
      "ckp/chart"
    );
    assertThat(schema.getMessage()).contains("JSON schema").doesNotContain("token=private");
    final var unknown = HelmDiagnostics.failure(
      "lint",
      1,
      "arbitrary rendered secret private",
      temp,
      "ckp/chart"
    );
    assertThat(unknown.getMessage())
      .contains("Run helm lint locally")
      .doesNotContain("private", "Missing packaged chart dependencies");
  }

  /**
   * <b>Scenario:</b> Snapshot Rejects Traversal And Helm Values Retain Null Overrides
   * <pre>
   * GIVEN ... a chart snapshot request and values containing null overrides
   * WHEN ... paths are checked and effective values are serialized
   * THEN ... path traversal is rejected and null overrides are retained
   * </pre>
   */
  @Test
  @DisplayName("Snapshot Rejects Traversal And Helm Values Retain Null Overrides")
  void snapshotRejectsTraversalAndHelmValuesRetainNullOverrides() throws Exception {
    assertThatThrownBy(() ->
      SparseProjects.materialize(temp, Map.of("ckp/../../escape", "eA=="))
    ).isInstanceOf(Problem.class);
    assertThat(HelmValues.parse("replicaCount: 2\noptional: null\n")).containsEntry(
      "optional",
      null
    );
    assertThatThrownBy(() -> HelmValues.parse("a: 1\na: 2\n")).hasMessageContaining("duplicate");
  }

  /**
   * <b>Scenario:</b> Dependency Warning Does Not Hide The Template Failure
   * <pre>
   * GIVEN ... Helm output containing both a dependency warning and a template error
   * WHEN ... lint diagnostics are produced
   * THEN ... the template failure remains visible
   * </pre>
   */
  @Test
  @DisplayName("Dependency Warning Does Not Hide The Template Failure")
  void dependencyWarningDoesNotHideTheTemplateFailure() throws Exception {
    Files.createDirectories(temp.resolve("ckp/chart/templates"));
    Files.writeString(temp.resolve("ckp/chart/templates/service.yaml"), "placeholder");
    final String warning =
      "[WARNING] /private/Chart.yaml: chart directory is missing these dependencies: secret-chart\n";
    final String helper =
      "[ERROR] templates/: template: example/templates/service.yaml:1:3: executing at <include secret-helper .>: error calling include: template: no template secret-helper associated with template gotpl\n";
    final var problem = HelmDiagnostics.failure("lint", 1, helper + warning, temp, "ckp/chart");
    assertThat(problem.getMessage())
      .contains(
        "referenced Helm helper template is unavailable",
        "also reported missing chart dependencies",
        "ckp/chart/templates/service.yaml:1"
      )
      .doesNotContain("secret-chart", "secret-helper", "/private/");
    final var missingValue = HelmDiagnostics.failure(
      "lint",
      1,
      warning + "[ERROR] templates/: nil pointer evaluating interface {}.secret",
      temp,
      "ckp/chart"
    );
    assertThat(missingValue.getMessage())
      .contains("missing value", "also reported missing chart dependencies")
      .doesNotContain("{}.secret");
  }

  /**
   * <b>Scenario:</b> Dependency Preparation Uses Lock And Snapshots Downloaded Charts
   * <pre>
   * GIVEN ... a chart with dependencies and an optional lock file
   * WHEN ... dependencies are prepared and the chart is snapshotted
   * THEN ... the appropriate build or update command runs and downloaded charts enter the snapshot
   * </pre>
   */
  @Test
  @DisplayName("Dependency Preparation Uses Lock And Snapshots Downloaded Charts")
  void dependencyPreparationUsesLockAndSnapshotsDownloadedCharts() throws Exception {
    final Path chart = temp.resolve("ckp/chart");
    Files.createDirectories(chart);
    Files.writeString(
      chart.resolve("Chart.yaml"),
      "apiVersion: v2\nname: app\nversion: 1.0.0\ndependencies:\n- name: common\n  version: 1.0.0\n  repository: https://charts.invalid\n"
    );
    final var runner = mock(CommandRunner.class);
    when(runner.run(any(), any(), any(), any())).thenAnswer((final var call) -> {
      Files.createDirectories(chart.resolve("charts"));
      Files.write(chart.resolve("charts/common-1.0.0.tgz"), new byte[] { 1, 2, 3 });
      Files.writeString(chart.resolve("Chart.lock"), "generated lock");
      return new CommandRunner.Result(0, "");
    });
    final var helm = new HelmExecution(runner, settings());
    helm.prepareDependencies(temp, "ckp/chart");
    verify(runner).run(
      eq(List.of("helm", "dependency", "update", chart.toString())),
      eq(temp),
      any(),
      any()
    );
    final var files = SparseProjects.snapshot(temp);
    assertThat(files).containsKeys("ckp/chart/Chart.lock", "ckp/chart/charts/common-1.0.0.tgz");
    helm.prepareDependencies(temp, "ckp/chart");
    verify(runner).run(
      eq(List.of("helm", "dependency", "build", chart.toString())),
      eq(temp),
      any(),
      any()
    );
    when(runner.run(any(), any(), any(), any())).thenReturn(
      new CommandRunner.Result(1, "SECRET token")
    );
    assertThatThrownBy(() -> helm.prepareDependencies(temp, "ckp/chart"))
      .hasMessageContaining("dependency build failed")
      .hasMessageNotContaining("SECRET token");
    verify(runner, times(1)).run(
      eq(List.of("helm", "dependency", "update", chart.toString())),
      eq(temp),
      any(),
      any()
    );
  }

  /**
   * <b>Scenario:</b> Dependency Preparation Skips Empty Charts And Rejects Local Path Escape
   * <pre>
   * GIVEN ... charts with no dependencies or an escaping local dependency path
   * WHEN ... dependency preparation is requested
   * THEN ... empty dependency work is skipped and local path escape is rejected
   * </pre>
   */
  @Test
  @DisplayName("Dependency Preparation Skips Empty Charts And Rejects Local Path Escape")
  void dependencyPreparationSkipsEmptyChartsAndRejectsLocalPathEscape() throws Exception {
    final Path chart = temp.resolve("ckp/chart");
    Files.createDirectories(chart);
    final var runner = mock(CommandRunner.class);
    final var helm = new HelmExecution(runner, settings());
    Files.writeString(chart.resolve("Chart.yaml"), "name: app\nversion: 1.0.0\n");
    helm.prepareDependencies(temp, "ckp/chart");
    Files.writeString(
      chart.resolve("Chart.yaml"),
      "dependencies:\n- name: common\n  repository: file://../../outside\n"
    );
    assertThatThrownBy(() -> helm.prepareDependencies(temp, "ckp/chart")).hasMessageContaining(
      "inside the CKP snapshot"
    );
    verifyNoInteractions(runner);
  }

  /**
   * <b>Scenario:</b> Registers Required Aliases Before Downloading Dependencies
   * <pre>
   * GIVEN ... chart dependencies referencing configured Helm repository aliases
   * WHEN ... dependencies are prepared
   * THEN ... required aliases are registered before dependency download
   * </pre>
   */
  @Test
  @DisplayName("Registers Required Aliases Before Downloading Dependencies")
  void registersRequiredAliasesBeforeDownloadingDependencies() throws Exception {
    final var env = new MockEnvironment()
      .withProperty("orchestrator.execution.workspace", temp.toString())
      .withProperty(
        "orchestrator.execution.helm-repositories.helm-release-virtual",
        "https://registry.invalid/artifactory/helm-release-virtual"
      )
      .withProperty(
        "orchestrator.execution.helm-repositories.helm-dev-virtual",
        "https://registry.invalid/artifactory/helm-dev-virtual"
      );
    final var settings = new ExecutionSettings(env);
    final Path chart = temp.resolve("ckp/chart");
    Files.createDirectories(chart);
    Files.writeString(
      chart.resolve("Chart.yaml"),
      "apiVersion: v2\nname: app\nversion: 1.0.0\ndependencies:\n" +
        "- name: common\n  version: 1.0.0\n  repository: '@helm-release-virtual'\n" +
        "- name: other\n  version: 1.0.0\n  repository: alias:helm-release-virtual\n"
    );
    final var runner = mock(CommandRunner.class);
    when(runner.run(any(), any(), any(), any())).thenReturn(new CommandRunner.Result(0, ""));
    new HelmExecution(runner, settings).prepareDependencies(temp, "ckp/chart");
    final var ordered = inOrder(runner);
    final var expectedEnvironment = org.mockito.ArgumentCaptor.forClass(Map.class);
    ordered
      .verify(runner)
      .run(
        eq(
          List.of(
            "helm",
            "repo",
            "add",
            "helm-release-virtual",
            "https://registry.invalid/artifactory/helm-release-virtual",
            "--force-update"
          )
        ),
        eq(temp),
        expectedEnvironment.capture(),
        any()
      );
    ordered
      .verify(runner)
      .run(
        eq(List.of("helm", "dependency", "update", chart.toString())),
        eq(temp),
        eq(expectedEnvironment.getValue()),
        any()
      );
    ordered.verifyNoMoreInteractions();
    assertThat(expectedEnvironment.getValue().get("HELM_REPOSITORY_CONFIG")).isEqualTo(
      temp.resolve(".helm-repositories/repositories.yaml").toString()
    );
    assertThat(SparseProjects.snapshot(temp)).containsOnlyKeys("ckp/chart/Chart.yaml");
    when(runner.run(any(), any(), any(), any())).thenReturn(
      new CommandRunner.Result(1, "private-token")
    );
    clearInvocations(runner);
    assertThatThrownBy(() ->
      new HelmExecution(runner, settings).prepareDependencies(temp, "ckp/chart")
    )
      .hasMessageContaining("registration failed for helm-release-virtual")
      .hasMessageNotContaining("private-token");
    verify(runner, times(1)).run(any(), any(), any(), any());
  }

  /**
   * <b>Scenario:</b> Missing Alias Gives Configuration Key And Repository Urls Cannot Contain Credentials
   * <pre>
   * GIVEN ... a missing repository alias or a URL containing credentials
   * WHEN ... repository configuration is resolved
   * THEN ... the missing configuration key is identified and credential-bearing URLs are rejected
   * </pre>
   */
  @Test
  @DisplayName(
    "Missing Alias Gives Configuration Key And Repository Urls Cannot Contain Credentials"
  )
  void missingAliasGivesConfigurationKeyAndRepositoryUrlsCannotContainCredentials()
    throws Exception {
    final Path chart = temp.resolve("ckp/chart");
    Files.createDirectories(chart);
    Files.writeString(
      chart.resolve("Chart.yaml"),
      "dependencies:\n- name: common\n  repository: '@helm-release-virtual'\n"
    );
    final var runner = mock(CommandRunner.class);
    assertThatThrownBy(() ->
      new HelmExecution(runner, settings()).prepareDependencies(temp, "ckp/chart")
    ).hasMessageContaining("orchestrator.execution.helm-repositories.helm-release-virtual");
    verifyNoInteractions(runner);
    assertThatThrownBy(() ->
      new ExecutionSettings(
        new MockEnvironment().withProperty(
          "orchestrator.execution.helm-repositories.common",
          "https://user:secret@registry.invalid/charts"
        )
      )
    )
      .hasMessageContaining("without credentials")
      .hasMessageNotContaining("user:secret");
  }

  /**
   * <b>Scenario:</b> Cluster Lookup Diagnostics Explain Authentication And Permissions Without Raw Output
   * <pre>
   * GIVEN ... cluster lookup failures caused by authentication or permissions
   * WHEN ... diagnostics classify those failures
   * THEN ... actionable guidance is returned without raw command output
   * </pre>
   */
  @Test
  @DisplayName(
    "Cluster Lookup Diagnostics Explain Authentication And Permissions Without Raw Output"
  )
  void clusterLookupDiagnosticsExplainAuthenticationAndPermissionsWithoutRawOutput() {
    final var runner = new CommandRunner() {
      @Override
      public Result run(
        final List<String> args,
        final Path directory,
        final Map<String, String> env,
        final Duration timeout
      ) {
        return new Result(
          1,
          "Error: secrets is forbidden: User private-person cannot list resource secrets. TOKEN=private"
        );
      }
    };
    assertThatThrownBy(() ->
      runner.require(
        List.of("helm", "list"),
        temp,
        Map.of(),
        Duration.ofSeconds(1),
        "Helm release lookup"
      )
    )
      .hasMessageContaining("RBAC")
      .hasMessageNotContaining("private-person")
      .hasMessageNotContaining("TOKEN=private");
    assertThat(
      ClusterDiagnostics.failure(
        "Helm release lookup",
        1,
        "Unauthorized private-token"
      ).getMessage()
    )
      .contains("Renew oc/kubectl login", "Secret Server login does not authenticate Helm")
      .doesNotContain("private-token");
    assertThat(
      ClusterDiagnostics.failure(
        "Helm release lookup",
        1,
        "x509: certificate signed by unknown authority"
      ).getMessage()
    ).contains("TLS verification failed");
    assertThat(
      ClusterDiagnostics.failure(
        "Helm release lookup",
        1,
        "unknown flag: --all private-token"
      ).getMessage()
    )
      .contains("Helm version rejected a command option")
      .doesNotContain("private-token");
  }

  /**
   * <b>Scenario:</b> Release Lookup Preserves All Statuses Across Helm Versions
   * <pre>
   * GIVEN ... a supported Helm version and releases in different statuses
   * WHEN ... release lookup is performed
   * THEN ... the version-appropriate flags preserve all release statuses
   * </pre>
   */
  @org.junit.jupiter.params.ParameterizedTest
  @DisplayName("Release Lookup Preserves All Statuses Across Helm Versions")
  @org.junit.jupiter.params.provider.ValueSource(
    strings = { "v3.17.3+ge4da497", "v4.3.0+gbec5b06" }
  )
  void releaseLookupPreservesAllStatusesAcrossHelmVersions(final String version) {
    final var runner = mock(CommandRunner.class);
    when(runner.require(any(), any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      if (args.contains("view")) return "https://cluster.invalid";
      if (args.contains("version")) return version;
      if (args.contains("list")) {
        assertThat(args.contains("--all")).isEqualTo(version.startsWith("v3."));
        assertThat(args).containsSubsequence(
          "--namespace",
          "ns",
          "--filter",
          "^load$",
          "--output",
          "json"
        );
        return "[{\"name\":\"load\",\"status\":\"pending-install\"}]";
      }
      if (args.contains("status")) return "{\"info\":{\"status\":\"pending-install\"}}";
      throw new AssertionError("Unexpected command");
    });
    final var helm = new HelmExecution(runner, settings());
    final var target = Map.<String, Object>of(
      "context",
      "sandbox",
      "server",
      "https://cluster.invalid"
    );
    assertThat(helm.baseline(target, "ns", "load")).isNotEqualTo("ABSENT");
    assertThat(helm.baseline(target, "ns", "load")).isNotEqualTo("ABSENT");
    verify(runner, times(1)).require(
      eq(List.of("helm", "version", "--template", "{{.Version}}")),
      any(),
      any(),
      any(),
      any()
    );
  }

  /**
   * <b>Scenario:</b> Release Baseline Ignores Live Resources But Detects Stored Release Changes
   * <pre>
   * GIVEN ... a prepared baseline for an existing Helm release
   * WHEN ... live resources or stored release metadata change
   * THEN ... live resource changes do not invalidate the baseline but stored release changes do
   * </pre>
   */
  @Test
  @DisplayName("Release Baseline Ignores Live Resources But Detects Stored Release Changes")
  void releaseBaselineIgnoresLiveResourcesButDetectsStoredReleaseChanges() {
    final var runner = mock(CommandRunner.class);
    final var info = new LinkedHashMap<String, Object>(
      Map.of(
        "status",
        "deployed",
        "description",
        "Upgrade complete",
        "last_deployed",
        "2026-09-30T12:00:00Z"
      )
    );
    final var release = new LinkedHashMap<String, Object>(
      Map.of(
        "name",
        "receiver",
        "namespace",
        "ns",
        "version",
        3,
        "info",
        info,
        "manifest",
        "original manifest",
        "config",
        Map.of("replicas", 2),
        "chart",
        Map.of("metadata", Map.of("version", "1.0.0"))
      )
    );
    when(runner.require(any(), any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      if (args.contains("view")) return "https://cluster.invalid";
      if (args.contains("version")) return "v4.3.0";
      if (args.contains("list")) return "[{\"name\":\"receiver\"}]";
      if (args.contains("status")) return Json.write(release);
      throw new AssertionError("Unexpected command: " + args);
    });
    final var helm = new HelmExecution(runner, settings());
    final var target = Map.<String, Object>of(
      "context",
      "sandbox",
      "server",
      "https://cluster.invalid"
    );
    final String baseline = helm.baseline(target, "ns", "receiver");
    info.put(
      "resources",
      Map.of(
        "v1/Pod",
        List.of(
          Map.of("metadata", Map.of("resourceVersion", "100"), "status", Map.of("phase", "Pending"))
        )
      )
    );
    assertThat(helm.baseline(target, "ns", "receiver")).isEqualTo(baseline);
    info.put(
      "resources",
      Map.of(
        "v1/Pod",
        List.of(
          Map.of("metadata", Map.of("resourceVersion", "101"), "status", Map.of("phase", "Running"))
        )
      )
    );
    assertThat(helm.baseline(target, "ns", "receiver")).isEqualTo(baseline);
    for (final var change : Map.<String, Object>of(
      "version",
      4,
      "manifest",
      "changed manifest",
      "config",
      Map.of("replicas", 3),
      "chart",
      Map.of("metadata", Map.of("version", "2.0.0"))
    ).entrySet()) {
      final Object original = release.put(change.getKey(), change.getValue());
      assertThat(helm.baseline(target, "ns", "receiver"))
        .as(change.getKey())
        .isNotEqualTo(baseline);
      release.put(change.getKey(), original);
    }
    info.put("status", "pending-upgrade");
    assertThat(helm.baseline(target, "ns", "receiver")).isNotEqualTo(baseline);
    info.put("status", "deployed");
    info.put("last_deployed", "2026-09-30T13:00:00Z");
    assertThat(helm.baseline(target, "ns", "receiver")).isNotEqualTo(baseline);
  }

  /**
   * <b>Scenario:</b> Deployment Uses Prepared Chart And Dependencies And Reports Readiness Failure
   * <pre>
   * GIVEN ... a prepared service or load-generator chart with dependencies
   * WHEN ... deployment runs and Helm reports a readiness failure
   * THEN ... the prepared chart and values are used and the failure is reported
   * </pre>
   */
  @org.junit.jupiter.params.ParameterizedTest
  @DisplayName("Deployment Uses Prepared Chart And Dependencies And Reports Readiness Failure")
  @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
  void deploymentUsesPreparedChartAndDependenciesAndReportsReadinessFailure(final boolean load) {
    final var runner = mock(CommandRunner.class);
    when(runner.require(any(), any(), any(), any(), any())).thenReturn("https://cluster.invalid");
    final String chart = "ckp/helm/service";
    final var files = Map.of(
      chart + "/Chart.yaml",
      Base64.getEncoder().encodeToString(
        "version: 1.2.3\nappVersion: 1.2.3\n".getBytes(StandardCharsets.UTF_8)
      ),
      chart + "/charts/common.tgz",
      Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })
    );
    final var service = new Model.PreparedService(
      "service",
      Model.Action.DEPLOY,
      "ns",
      "release",
      null,
      "ABSENT",
      "commit",
      Map.of(),
      files,
      Map.of(),
      Map.of(),
      Map.of(),
      List.of()
    );
    when(runner.run(any(), any(), any(), any())).thenAnswer((final var call) -> {
      final List<String> args = call.getArgument(0);
      final Path folder = call.getArgument(1);
      assertThat(Files.readString(folder.resolve(chart + "/Chart.yaml")))
        .contains("version: 1.2.3")
        .doesNotContain("__REPLACEAPPVERSION__");
      assertThat(Files.readAllBytes(folder.resolve(chart + "/charts/common.tgz"))).containsExactly(
        1,
        2,
        3
      );
      assertThat(args).contains(load ? "install" : "upgrade");
      assertThat(args.contains("--wait")).isEqualTo(!load);
      return new CommandRunner.Result(
        1,
        "UPGRADE FAILED: context deadline exceeded password=private-token"
      );
    });
    assertThatThrownBy(() ->
      new HelmExecution(runner, settings()).apply(
        "run",
        Map.of("context", "sandbox", "server", "https://cluster.invalid"),
        service,
        chart,
        load
      )
    )
      .hasMessageContaining("Service service, namespace ns, release release, context sandbox")
      .hasMessageContaining("exceeded its deadline")
      .hasMessageNotContaining("private-token");
  }

  /**
   * <b>Scenario:</b> Deployment Diagnostics Classify Failures Without Returning Raw Values
   * <pre>
   * GIVEN ... Helm deployment failures containing sensitive values
   * WHEN ... deployment diagnostics classify the failures
   * THEN ... the error category is reported without returning raw values
   * </pre>
   */
  @Test
  @DisplayName("Deployment Diagnostics Classify Failures Without Returning Raw Values")
  void deploymentDiagnosticsClassifyFailuresWithoutReturningRawValues() {
    for (final var sample : Map.of(
      "forbidden",
      "permissions",
      "another operation is in progress",
      "pending",
      "invalid ownership metadata",
      "ownership",
      "failed pre-install hook",
      "hook",
      "cannot patch immutable field",
      "immutable",
      "no matches for kind",
      "CRD",
      "admission webhook denied",
      "admission",
      "unknown failure",
      "unclassified"
    ).entrySet()) {
      assertThat(
        DeploymentDiagnostics.failure(
          "Deployment",
          1,
          sample.getKey() + " private-token"
        ).getMessage()
      )
        .contains(sample.getValue())
        .doesNotContain("private-token");
    }
  }

  /**
   * <b>Scenario:</b> Helm Repository Token Uses Stdin And Private Temporary Config
   * <pre>
   * GIVEN ... a private Helm repository with token credentials
   * WHEN ... the repository is registered
   * THEN ... the token is supplied through stdin and repository configuration is private and temporary
   * </pre>
   */
  @Test
  @DisplayName("Helm Repository Token Uses Stdin And Private Temporary Config")
  void helmRepositoryTokenUsesStdinAndPrivateTemporaryConfig() throws Exception {
    final var settings = new ExecutionSettings(
      new MockEnvironment()
        .withProperty("orchestrator.execution.workspace", temp.toString())
        .withProperty(
          "orchestrator.execution.helm-repositories.release",
          "https://registry.invalid/charts"
        )
        .withProperty("orchestrator.execution.helm-repository-connection", "office")
        .withProperty("orchestrator.execution.helm-repository-username", "first.last@domain.net")
    );
    final var config = new ConnectionConfig(
      new ConnectionConfig.Data(
        Map.of(
          "office",
          new ConnectionConfig.Artifactory("https://registry.invalid/artifactory", null, "token")
        ),
        Map.of(),
        Map.of(),
        Map.of()
      )
    );
    final var sessions = new ConnectionSessions(config, (final var ref) -> {
      throw new AssertionError("Unexpected secret resolution");
    });
    final var request = new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
      new org.springframework.web.context.request.ServletRequestAttributes(request)
    );
    sessions.remember(
      "artifactory",
      "office",
      new RequestAuthentication(null, null, "private-token"),
      300,
      request.getSession()
    );
    try {
      final var auth = new HelmRepositoryAuth(config, sessions);
      assertThat(HelmRepositoryAuth.shortUsername("DOMAIN\\first.last")).isEqualTo("first.last");
      final Path chart = temp.resolve("ckp/chart");
      Files.createDirectories(chart);
      Files.writeString(
        chart.resolve("Chart.yaml"),
        "dependencies:\n- name: common\n  repository: '@release'\n"
      );
      final var runner = mock(CommandRunner.class);
      when(runner.runWithInput(any(), any(), any(), any(), any())).thenAnswer((final var call) -> {
        final List<String> args = call.getArgument(0);
        final byte[] stdin = call.getArgument(4);
        assertThat(args)
          .containsSubsequence("--username", "first.last", "--password-stdin")
          .contains("--force-update");
        assertThat(args.toString()).doesNotContain("private-token", "@domain.net");
        assertThat(new String(stdin, StandardCharsets.UTF_8)).isEqualTo("private-token\n");
        assertThat(Files.getPosixFilePermissions(temp.resolve(".helm-repositories"))).isEqualTo(
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        );
        Files.writeString(temp.resolve(".helm-repositories/repositories.yaml"), "private-token");
        return new CommandRunner.Result(0, "");
      });
      when(runner.run(any(), any(), any(), any())).thenReturn(
        new CommandRunner.Result(1, "download failed private-token")
      );
      assertThatThrownBy(() ->
        new HelmExecution(runner, settings, auth).prepareDependencies(temp, "ckp/chart")
      )
        .hasMessageContaining("dependency update failed")
        .hasMessageNotContaining("private-token");
      assertThat(temp.resolve(".helm-repositories")).doesNotExist();
      assertThat(SparseProjects.snapshot(temp)).containsOnlyKeys("ckp/chart/Chart.yaml");
      assertThatThrownBy(() ->
        auth.resolve(settings, "https://unrelated.invalid/charts")
      ).hasMessageContaining("host and port");
    } finally {
      sessions.close();
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }
}
