package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RealExecutionContractsTest {
  @TempDir Path temp;
  ExecutionSettings settings(){return new ExecutionSettings(new MockEnvironment().withProperty("orchestrator.execution.enabled","true")
      .withProperty("orchestrator.execution.workspace",temp.toString()).withProperty("orchestrator.execution.kube-context","sandbox")
      .withProperty("orchestrator.execution.expected-api-server","https://cluster.invalid"));}
  @Test void commandRunnerUsesArgumentArraysAndEnforcesTimeout() {
    var runner=new CommandRunner();
    var result=runner.run(List.of("/usr/bin/printf","%s","$(touch should-not-exist); a b"),temp,Map.of(),Duration.ofSeconds(2));
    assertThat(result.output()).isEqualTo("$(touch should-not-exist); a b");assertThat(temp.resolve("should-not-exist")).doesNotExist();
    assertThatThrownBy(()->runner.run(List.of("/bin/sleep","2"),temp,Map.of(),Duration.ofMillis(30))).hasMessageContaining("timed out");
  }
  @Test void discoversCloneUrlAndChecksOutOnlyCkpWithTokenOffCommandLine() throws Exception {
    var config=new ConnectionConfig(new ConnectionConfig.Data(Map.of(),Map.of(),Map.of(),Map.of(),
        Map.of("stash",new ConnectionConfig.Bitbucket("https://stash.invalid/rest/api",null,"token"))));
    var sessions=new ConnectionSessions(config,ref->{throw new AssertionError();});
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
    sessions.remember("bitbucket","stash",new RequestAuthentication(null,null,"private-token"),300,request.getSession());
    var http=mock(ReadOnlyHttp.class);
    when(http.get(any(),any(),any())).thenReturn(new ReadOnlyHttp.Response(200,Map.of(),
        "{\"links\":{\"clone\":[{\"name\":\"http\",\"href\":\"https://stash.invalid/scm/SP/receiver.git\"}]}}".getBytes(StandardCharsets.UTF_8)));
    var commands=mock(CommandRunner.class);List<List<String>> calls=new ArrayList<>();
    when(commands.require(any(),any(),any(),any(),any())).thenAnswer(call->{
      List<String> args=call.getArgument(0);Path root=call.getArgument(1);Map<String,String> env=call.getArgument(2);calls.add(args);
      assertThat(args.toString()).doesNotContain("private-token");assertThat(env.get("GIT_CONFIG_VALUE_0")).isEqualTo("Authorization: Bearer private-token");
      if(args.contains("rev-parse"))return "a".repeat(40)+"\n";
      if(args.contains("checkout")){Files.createDirectories(root.resolve("ckp/helm/service"));Files.writeString(root.resolve("ckp/helm/service/Chart.yaml"),"apiVersion: v2\nname: service\nversion: 1.0.0\n");}
      return "";
    });
    try {
      var source=new Catalog.SourceProject("stash","SP","receiver","main","ckp/helm/service");
      var snapshot=new SparseProjects(commands,settings(),config,sessions,http).checkout(source,"main");
      assertThat(snapshot.commit()).isEqualTo("a".repeat(40));assertThat(snapshot.files()).containsOnlyKeys("ckp/helm/service/Chart.yaml");
      assertThat(calls).anySatisfy(args->assertThat(args).containsSubsequence("sparse-checkout","set","--no-cone","/ckp/"));
      assertThat(calls).anySatisfy(args->assertThat(args).contains("--depth=1","--filter=blob:none"));
      assertThat(Files.list(temp).toList()).isEmpty();
    }finally{sessions.close();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
  }
  @Test void refusesWrongClusterAndUnownedLoadCleanup() {
    var runner=mock(CommandRunner.class);var helm=new HelmExecution(runner,settings());
    when(runner.require(any(),any(),any(),any(),any())).thenReturn("https://wrong.invalid");
    assertThatThrownBy(helm::target).hasMessageContaining("differs");
    when(runner.require(any(),any(),any(),any(),any())).thenAnswer(call->{
      List<String> args=call.getArgument(0);
      if(args.contains("view"))return "https://cluster.invalid";
      if(args.contains("version"))return "v3.17.3";
      if(args.contains("list"))return "[{\"name\":\"load\"}]";
      if(args.contains("status"))return "{\"info\":{\"description\":\"someone-else\"}}";
      throw new AssertionError("Unexpected mutation: "+args);
    });
    var service=new com.example.perforchestrator.domain.Model.PreparedService("load",Model.Action.DEPLOY,"ns","load",null,"ABSENT","commit",Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of());
    assertThatThrownBy(()->helm.stop("run",helm.target(),service)).hasMessageContaining("ownership");
  }
  @Test void helmLintUsesTargetNamespaceAndReportsSafeTemplateLocation() throws Exception {
    Path chart=temp.resolve("ckp/helm/service");Files.createDirectories(chart.resolve("templates"));
    Files.writeString(chart.resolve("templates/deployment.yaml"),"placeholder");
    var runner=mock(CommandRunner.class);
    when(runner.run(any(),any(),any(),any())).thenReturn(new CommandRunner.Result(1,
        "[ERROR] templates/: template: service/templates/deployment.yaml:12:5: executing at <.Values.credentials.password>: nil pointer evaluating interface {}.password SECRET-VALUE"));
    var helm=new HelmExecution(runner,settings());
    assertThatThrownBy(()->helm.validate(temp,"ckp/helm/service","target-ns","release","sha256:test"))
        .hasMessageContaining("missing value").hasMessageContaining("ckp/helm/service/templates/deployment.yaml:12")
        .hasMessageNotContaining("SECRET-VALUE").hasMessageNotContaining("credentials.password");
    verify(runner).run(argThat(args->args.contains("lint") && args.contains("--namespace") && args.contains("target-ns")),eq(temp),any(),any());
    verifyNoMoreInteractions(runner);
  }
  @Test void helmDiagnosticsDoNotAssumeDependenciesOrExposeRawOutput(){
    var missing=HelmDiagnostics.failure("lint",1,"[ERROR] Chart.yaml: chart metadata is missing these dependencies: private-name",temp,"ckp/chart");
    assertThat(missing.getMessage()).contains("Missing packaged chart dependencies").doesNotContain("private-name");
    var schema=HelmDiagnostics.failure("lint",1,"values don't meet the specifications of the schema(s): token=private",temp,"ckp/chart");
    assertThat(schema.getMessage()).contains("JSON schema").doesNotContain("token=private");
    var unknown=HelmDiagnostics.failure("lint",1,"arbitrary rendered secret private",temp,"ckp/chart");
    assertThat(unknown.getMessage()).contains("Run helm lint locally").doesNotContain("private","Missing packaged chart dependencies");
  }
  @Test void snapshotRejectsTraversalAndHelmValuesRetainNullOverrides() throws Exception {
    assertThatThrownBy(()->SparseProjects.materialize(temp,Map.of("ckp/../../escape","eA=="))).isInstanceOf(Problem.class);
    assertThat(HelmValues.parse("replicaCount: 2\noptional: null\n")).containsEntry("optional",null);
    assertThatThrownBy(()->HelmValues.parse("a: 1\na: 2\n")).hasMessageContaining("duplicate");
  }

  @Test void dependencyWarningDoesNotHideTheTemplateFailure() throws Exception {
    Files.createDirectories(temp.resolve("ckp/chart/templates"));
    Files.writeString(temp.resolve("ckp/chart/templates/service.yaml"),"placeholder");
    String warning="[WARNING] /private/Chart.yaml: chart directory is missing these dependencies: secret-chart\n";
    String helper="[ERROR] templates/: template: example/templates/service.yaml:1:3: executing at <include secret-helper .>: error calling include: template: no template secret-helper associated with template gotpl\n";
    var problem=HelmDiagnostics.failure("lint",1,helper+warning,temp,"ckp/chart");
    assertThat(problem.getMessage()).contains("referenced Helm helper template is unavailable", "also reported missing chart dependencies", "ckp/chart/templates/service.yaml:1")
        .doesNotContain("secret-chart", "secret-helper", "/private/");
    var missingValue=HelmDiagnostics.failure("lint",1,warning+"[ERROR] templates/: nil pointer evaluating interface {}.secret",temp,"ckp/chart");
    assertThat(missingValue.getMessage()).contains("missing value", "also reported missing chart dependencies").doesNotContain("{}.secret");
  }

  @Test void dependencyPreparationUsesLockAndSnapshotsDownloadedCharts() throws Exception {
    Path chart=temp.resolve("ckp/chart");Files.createDirectories(chart);
    Files.writeString(chart.resolve("Chart.yaml"),"apiVersion: v2\nname: app\nversion: 1.0.0\ndependencies:\n- name: common\n  version: 1.0.0\n  repository: https://charts.invalid\n");
    var runner=mock(CommandRunner.class);
    when(runner.run(any(),any(),any(),any())).thenAnswer(call->{
      Files.createDirectories(chart.resolve("charts"));
      Files.write(chart.resolve("charts/common-1.0.0.tgz"),new byte[]{1,2,3});
      Files.writeString(chart.resolve("Chart.lock"),"generated lock");
      return new CommandRunner.Result(0,"");
    });
    var helm=new HelmExecution(runner,settings());
    helm.prepareDependencies(temp,"ckp/chart");
    verify(runner).run(eq(List.of("helm","dependency","update",chart.toString())),eq(temp),any(),any());
    var files=SparseProjects.snapshot(temp);
    assertThat(files).containsKeys("ckp/chart/Chart.lock","ckp/chart/charts/common-1.0.0.tgz");
    helm.prepareDependencies(temp,"ckp/chart");
    verify(runner).run(eq(List.of("helm","dependency","build",chart.toString())),eq(temp),any(),any());
    when(runner.run(any(),any(),any(),any())).thenReturn(new CommandRunner.Result(1,"SECRET token"));
    assertThatThrownBy(()->helm.prepareDependencies(temp,"ckp/chart"))
        .hasMessageContaining("dependency build failed").hasMessageNotContaining("SECRET token");
    verify(runner,times(1)).run(eq(List.of("helm","dependency","update",chart.toString())),eq(temp),any(),any());
  }

  @Test void dependencyPreparationSkipsEmptyChartsAndRejectsLocalPathEscape() throws Exception {
    Path chart=temp.resolve("ckp/chart");Files.createDirectories(chart);
    var runner=mock(CommandRunner.class);var helm=new HelmExecution(runner,settings());
    Files.writeString(chart.resolve("Chart.yaml"),"name: app\nversion: 1.0.0\n");
    helm.prepareDependencies(temp,"ckp/chart");
    Files.writeString(chart.resolve("Chart.yaml"),"dependencies:\n- name: common\n  repository: file://../../outside\n");
    assertThatThrownBy(()->helm.prepareDependencies(temp,"ckp/chart")).hasMessageContaining("inside the CKP snapshot");
    verifyNoInteractions(runner);
  }

  @Test void registersRequiredAliasesBeforeDownloadingDependencies() throws Exception {
    var env=new MockEnvironment().withProperty("orchestrator.execution.workspace",temp.toString())
        .withProperty("orchestrator.execution.helm-repositories.helm-release-virtual","https://registry.invalid/artifactory/helm-release-virtual")
        .withProperty("orchestrator.execution.helm-repositories.helm-dev-virtual","https://registry.invalid/artifactory/helm-dev-virtual");
    var settings=new ExecutionSettings(env);
    Path chart=temp.resolve("ckp/chart");Files.createDirectories(chart);
    Files.writeString(chart.resolve("Chart.yaml"),"apiVersion: v2\nname: app\nversion: 1.0.0\ndependencies:\n"
        +"- name: common\n  version: 1.0.0\n  repository: '@helm-release-virtual'\n"
        +"- name: other\n  version: 1.0.0\n  repository: alias:helm-release-virtual\n");
    var runner=mock(CommandRunner.class);
    when(runner.run(any(),any(),any(),any())).thenReturn(new CommandRunner.Result(0,""));
    new HelmExecution(runner,settings).prepareDependencies(temp,"ckp/chart");
    var ordered=inOrder(runner);
    var expectedEnvironment=org.mockito.ArgumentCaptor.forClass(Map.class);
    ordered.verify(runner).run(eq(List.of("helm","repo","add","helm-release-virtual","https://registry.invalid/artifactory/helm-release-virtual","--force-update")),eq(temp),expectedEnvironment.capture(),any());
    ordered.verify(runner).run(eq(List.of("helm","dependency","update",chart.toString())),eq(temp),eq(expectedEnvironment.getValue()),any());
    ordered.verifyNoMoreInteractions();
    assertThat(expectedEnvironment.getValue().get("HELM_REPOSITORY_CONFIG")).isEqualTo(temp.resolve(".helm-repositories/repositories.yaml").toString());
    assertThat(SparseProjects.snapshot(temp)).containsOnlyKeys("ckp/chart/Chart.yaml");
    when(runner.run(any(),any(),any(),any())).thenReturn(new CommandRunner.Result(1,"private-token"));
    clearInvocations(runner);
    assertThatThrownBy(()->new HelmExecution(runner,settings).prepareDependencies(temp,"ckp/chart"))
        .hasMessageContaining("registration failed for helm-release-virtual").hasMessageNotContaining("private-token");
    verify(runner,times(1)).run(any(),any(),any(),any());
  }

  @Test void missingAliasGivesConfigurationKeyAndRepositoryUrlsCannotContainCredentials() throws Exception {
    Path chart=temp.resolve("ckp/chart");Files.createDirectories(chart);
    Files.writeString(chart.resolve("Chart.yaml"),"dependencies:\n- name: common\n  repository: '@helm-release-virtual'\n");
    var runner=mock(CommandRunner.class);
    assertThatThrownBy(()->new HelmExecution(runner,settings()).prepareDependencies(temp,"ckp/chart"))
        .hasMessageContaining("orchestrator.execution.helm-repositories.helm-release-virtual");
    verifyNoInteractions(runner);
    assertThatThrownBy(()->new ExecutionSettings(new MockEnvironment()
        .withProperty("orchestrator.execution.helm-repositories.common","https://user:secret@registry.invalid/charts")))
        .hasMessageContaining("without credentials").hasMessageNotContaining("user:secret");
  }

  @Test void clusterLookupDiagnosticsExplainAuthenticationAndPermissionsWithoutRawOutput() {
    var runner=new CommandRunner(){@Override public Result run(List<String> args,Path directory,Map<String,String> env,Duration timeout){
      return new Result(1,"Error: secrets is forbidden: User private-person cannot list resource secrets. TOKEN=private");
    }};
    assertThatThrownBy(()->runner.require(List.of("helm","list"),temp,Map.of(),Duration.ofSeconds(1),"Helm release lookup"))
        .hasMessageContaining("RBAC").hasMessageNotContaining("private-person").hasMessageNotContaining("TOKEN=private");
    assertThat(ClusterDiagnostics.failure("Helm release lookup",1,"Unauthorized private-token").getMessage())
        .contains("Renew oc/kubectl login","Secret Server login does not authenticate Helm").doesNotContain("private-token");
    assertThat(ClusterDiagnostics.failure("Helm release lookup",1,"x509: certificate signed by unknown authority").getMessage()).contains("TLS verification failed");
    assertThat(ClusterDiagnostics.failure("Helm release lookup",1,"unknown flag: --all private-token").getMessage())
        .contains("Helm version rejected a command option").doesNotContain("private-token");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"v3.17.3+ge4da497","v4.3.0+gbec5b06"})
  void releaseLookupPreservesAllStatusesAcrossHelmVersions(String version) {
    var runner=mock(CommandRunner.class);
    when(runner.require(any(),any(),any(),any(),any())).thenAnswer(call->{
      List<String> args=call.getArgument(0);
      if(args.contains("view"))return "https://cluster.invalid";
      if(args.contains("version"))return version;
      if(args.contains("list")) {
        assertThat(args.contains("--all")).isEqualTo(version.startsWith("v3."));
        assertThat(args).containsSubsequence("--namespace","ns","--filter","^load$","--output","json");
        return "[{\"name\":\"load\",\"status\":\"pending-install\"}]";
      }
      if(args.contains("status"))return "{\"info\":{\"status\":\"pending-install\"}}";
      throw new AssertionError("Unexpected command");
    });
    var helm=new HelmExecution(runner,settings());var target=Map.<String,Object>of("context","sandbox","server","https://cluster.invalid");
    assertThat(helm.baseline(target,"ns","load")).isNotEqualTo("ABSENT");
    assertThat(helm.baseline(target,"ns","load")).isNotEqualTo("ABSENT");
    verify(runner,times(1)).require(eq(List.of("helm","version","--template","{{.Version}}")),any(),any(),any(),any());
  }

  @Test void helmRepositoryTokenUsesStdinAndPrivateTemporaryConfig() throws Exception {
    var settings=new ExecutionSettings(new MockEnvironment().withProperty("orchestrator.execution.workspace",temp.toString())
        .withProperty("orchestrator.execution.helm-repositories.release","https://registry.invalid/charts")
        .withProperty("orchestrator.execution.helm-repository-connection","office")
        .withProperty("orchestrator.execution.helm-repository-username","first.last@domain.net"));
    var config=new ConnectionConfig(new ConnectionConfig.Data(Map.of("office",new ConnectionConfig.Artifactory("https://registry.invalid/artifactory",null,"token")),Map.of(),Map.of(),Map.of()));
    var sessions=new ConnectionSessions(config,ref->{throw new AssertionError("Unexpected secret resolution");});
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
    sessions.remember("artifactory","office",new RequestAuthentication(null,null,"private-token"),300,request.getSession());
    try {
    var auth=new HelmRepositoryAuth(config,sessions);
    assertThat(HelmRepositoryAuth.shortUsername("DOMAIN\\first.last")).isEqualTo("first.last");
    Path chart=temp.resolve("ckp/chart");Files.createDirectories(chart);
    Files.writeString(chart.resolve("Chart.yaml"),"dependencies:\n- name: common\n  repository: '@release'\n");
    var runner=mock(CommandRunner.class);
    when(runner.runWithInput(any(),any(),any(),any(),any())).thenAnswer(call->{
      List<String> args=call.getArgument(0);byte[] stdin=call.getArgument(4);
      assertThat(args).containsSubsequence("--username","first.last","--password-stdin").contains("--force-update");
      assertThat(args.toString()).doesNotContain("private-token","@domain.net");
      assertThat(new String(stdin,StandardCharsets.UTF_8)).isEqualTo("private-token\n");
      assertThat(Files.getPosixFilePermissions(temp.resolve(".helm-repositories")))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      Files.writeString(temp.resolve(".helm-repositories/repositories.yaml"),"private-token");
      return new CommandRunner.Result(0,"");
    });
    when(runner.run(any(),any(),any(),any())).thenReturn(new CommandRunner.Result(1,"download failed private-token"));
    assertThatThrownBy(()->new HelmExecution(runner,settings,auth).prepareDependencies(temp,"ckp/chart"))
        .hasMessageContaining("dependency update failed").hasMessageNotContaining("private-token");
    assertThat(temp.resolve(".helm-repositories")).doesNotExist();
    assertThat(SparseProjects.snapshot(temp)).containsOnlyKeys("ckp/chart/Chart.yaml");
    assertThatThrownBy(()->auth.resolve(settings,"https://unrelated.invalid/charts")).hasMessageContaining("host and port");
    } finally { sessions.close();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
  }
}
