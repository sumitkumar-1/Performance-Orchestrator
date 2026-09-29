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
      if(args.contains("list"))return "[{\"name\":\"load\"}]";
      if(args.contains("status"))return "{\"info\":{\"description\":\"someone-else\"}}";
      throw new AssertionError("Unexpected mutation: "+args);
    });
    var service=new com.example.perforchestrator.domain.Model.PreparedService("load",Model.Action.DEPLOY,"ns","load",null,"ABSENT","commit",Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of());
    assertThatThrownBy(()->helm.stop("run",helm.target(),service)).hasMessageContaining("ownership");
  }
  @Test void snapshotRejectsTraversalAndHelmValuesRetainNullOverrides() throws Exception {
    assertThatThrownBy(()->SparseProjects.materialize(temp,Map.of("ckp/../../escape","eA=="))).isInstanceOf(Problem.class);
    assertThat(HelmValues.parse("replicaCount: 2\noptional: null\n")).containsEntry("optional",null);
    assertThatThrownBy(()->HelmValues.parse("a: 1\na: 2\n")).hasMessageContaining("duplicate");
  }
}
