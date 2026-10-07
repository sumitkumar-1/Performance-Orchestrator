package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.application.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RealWorkflowTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  HikariDataSource db;Store store;TransactionTemplate tx;RealRuns worker;
  Catalog catalog;ConnectionConfig connections;HelmExecution helm;LokiMeasurements metrics;ArtifactoryImages images;RealPreparation preparation;ExecutionSettings settings;
  @BeforeEach void setup(){
    db=new HikariDataSource();db.setJdbcUrl("jdbc:h2:mem:real-"+UUID.randomUUID());db.setUsername("sa");db.setPassword("");
    Flyway.configure().dataSource(db).load().migrate();store=new Store(new JdbcTemplate(db));tx=new TransactionTemplate(new DataSourceTransactionManager(db));
    catalog=mock(Catalog.class);when(catalog.mode()).thenReturn("real");when(catalog.hash()).thenReturn("catalog-hash");
    connections=new ConnectionConfig(new ConnectionConfig.Data(Map.of(),Map.of(),Map.of(),Map.of()));
    helm=mock(HelmExecution.class);when(helm.baseline(any(),any(),any())).thenReturn("ABSENT");when(helm.stop(any(),any(),any())).thenReturn(true);
    metrics=mock(LokiMeasurements.class);when(metrics.collect(any(),any())).thenReturn(Map.of());images=mock(ArtifactoryImages.class);preparation=mock(RealPreparation.class);
    settings=new ExecutionSettings(new MockEnvironment().withProperty("orchestrator.execution.enabled","true").withProperty("orchestrator.execution.workspace", directory.toString()));worker=worker();
  }
  RealRuns worker(){return new RealRuns(store,catalog,connections,preparation,helm,metrics,images,tx,settings);}
  @AfterEach void cleanup(){db.close();}
  Plan plan(){
    var image=new Image("service:load","","repo/load","v1","sha256:"+"a".repeat(64));when(images.resolve(any(),any(),any(),any())).thenReturn(image);
    var prepared=new PreparedService("load",Action.DEPLOY,"load-ns","load-release",image,"ABSENT","a".repeat(40),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of());
    var profile=new Profile("Real test","sandbox",List.of(),new Load("real-yaml",0,0,0,1,""),600,List.of(),SimulationCase.SUCCESS);
    var p=new Plan(UUID.randomUUID().toString(),"",Instant.now().toString(),Instant.now().plusSeconds(900).toString(),"test",null,0,profile,"catalog-hash","cluster","load-ns","commit",
        Map.of("target",Map.of("context","sandbox","server","https://cluster.invalid"),"loadService","load","charts",Map.of("load","ckp/helm/load"),"metrics",List.of(),"connectionHash",Json.hash(Json.write(connections.data()))),List.of(prepared),List.of(),false);
    p=new Plan(p.id(),PlanningService.checksum(p),p.createdAt(),p.expiresAt(),p.actor(),null,0,p.profile(),p.catalogHash(),p.clusterIdentity(),p.loadNamespace(),p.scenarioRevision(),p.effectiveLoadConfiguration(),p.services(),p.warnings(),false);store.plan(p);return p;
  }
  @Test void queuesOnceExecutesAndCleansOwnedLoadWithoutSyntheticPass(){
    var plan=plan();var run=worker.enqueue("request-key",plan.id());assertThat(worker.enqueue("request-key",plan.id()).id()).isEqualTo(run.id());
    worker.tick();worker.tick();worker.tick();worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.RUNNING_LOAD);
    verify(helm).apply(eq(run.id()),any(),any(),eq("ckp/helm/load"),eq(true));
    var current=store.run(run.id());
    store.update(new Run(current.id(),current.planId(),current.environment(),current.state(),current.verdict(),current.createdAt(),current.updatedAt(),current.startedAt(),Instant.now().minusSeconds(5).toString(),null,current.message(),current.cleanupOutcome(),current.metrics(),current.desiredOutcome(),current.loadOperationId()));
    worker.tick();worker.tick();worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.SUCCEEDED);assertThat(store.run(run.id()).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    verify(helm).stop(eq(run.id()),any(),any());assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();verify(metrics).detach(run.id());
  }
  @Test void deploymentPublishesServiceProgressBeforeHelmAndRecordsCompletion(){
    var original=plan();
    var tree=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(original);
    tree.put("id",UUID.randomUUID().toString());
    var services=tree.putArray("services");
    for(String name:List.of("receiver","router","load")) {
      var service=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(original.services().getFirst());
      service.put("serviceId",name).put("releaseName",name+"-release");services.add(service);
      ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("effectiveLoadConfiguration").path("charts")).put(name,"ckp/helm/"+name);
    }
    var prepared=Json.read(Json.write(tree),Plan.class);tree.put("checksum",PlanningService.checksum(prepared));
    prepared=Json.read(Json.write(tree),Plan.class);store.plan(prepared);
    var run=worker.enqueue("progress-key",prepared.id());
    var order=new ArrayList<String>();
    doAnswer(call->{
      PreparedService service=call.getArgument(2);order.add(service.serviceId());
      assertThat(store.run(run.id()).message()).startsWith("Deploying ").contains(service.serviceId(),"image version "+service.image().version(),service.namespace(),service.releaseName());
      assertThat(store.events(run.id(),0)).last().satisfies(event->assertThat(event.message()).isEqualTo(store.run(run.id()).message()));
      return null;
    }).when(helm).apply(eq(run.id()),any(),any(),any(),anyBoolean());
    worker.tick();worker.tick();worker.tick();worker.tick();
    assertThat(order).containsExactly("receiver","router","load");
    assertThat(store.events(run.id(),0)).extracting(Event::message).anyMatch(message->message.contains("Service 1/2: receiver") && message.contains("ready in"))
        .anyMatch(message->message.contains("Service 2/2: router") && message.contains("ready in"))
        .anyMatch(message->message.contains("Load generator: load") && message.contains("installed in"));
  }

  @Test void cancellationAndRestartNeverRedeployAndCleanupFailureKeepsReservation(){
    var plan=plan();var run=worker.enqueue("cancel-key",plan.id());
    var cancelled=new RunService(store,null,catalog).cancel(run.id());worker.tick();worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);verify(helm,never()).apply(any(),any(),any(),any(),anyBoolean());
    var second=worker.enqueue("restart-key",plan.id());
    when(helm.stop(eq(second.id()),any(),any())).thenThrow(com.example.perforchestrator.domain.Problem.conflict("ownership differs"));
    var restarted=worker();restarted.tick();
    assertThat(store.run(second.id()).state()).isEqualTo(State.NEEDS_ATTENTION);assertThat(store.environmentAvailable("cluster/sandbox")).isFalse();
    when(helm.stop(eq(second.id()),any(),any())).thenReturn(true);restarted.recover(second.id());assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
  }
  @Test void cancellationDuringCleanupFinishesBeforeReleasingReservation(){
    var plan=plan();var run=worker.enqueue("cleanup-race",plan.id());
    worker.tick();when(helm.baseline(any(),any(),any())).thenReturn("CHANGED");worker.tick();
    when(helm.stop(eq(run.id()),any(),any())).thenAnswer(call->{new RunService(store,null,catalog).cancel(run.id());return true;});
    worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CANCELLED);
    assertThat(store.environmentAvailable("cluster/sandbox")).isTrue();
  }
  @Test void baselineDriftFailsBeforeDeployment(){
    var plan=plan();var run=worker.enqueue("drift-key",plan.id());worker.tick();when(helm.baseline(any(),any(),any())).thenReturn("CHANGED");worker.tick();
    assertThat(store.run(run.id()).state()).isEqualTo(State.CLEANING_UP);verify(helm,never()).apply(any(),any(),any(),any(),anyBoolean());
  }
  @Test void parallelPreparationPreservesOrderAndSessionContext() throws Exception {
    var source=mock(SparseProjects.class);
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    when(catalog.environment("sandbox")).thenReturn(new Catalog.Environment("Sandbox","cluster",null,null,null,null,null,null));
    when(helm.target()).thenReturn(Map.of("context","sandbox","server","https://cluster.invalid"));
    var bothStarted=new java.util.concurrent.CountDownLatch(2);
    var requestAttributes=new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest());
    var requests=new ArrayList<RealPreparation.Deployment>();
    for(String id:List.of("first","second","load")) {
      var destination=new Catalog.Destination("ns",id,List.of("ckp/chart/values.yaml"));
      when(catalog.service(id)).thenReturn(new Catalog.Service("projects/"+id,List.of("ignored"),Map.of(),null,List.of(),destination,
          new Catalog.ContainerImage("registry","dev","team",id),new Catalog.SourceProject("stash","SP",id,"main","ckp/chart"),Map.of()));
      requests.add(new RealPreparation.Deployment(id,"main","v1",null,""));
    }
    when(source.checkout(any(),any())).thenAnswer(call->{
      assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(requestAttributes);
      Catalog.SourceProject project=call.getArgument(0);
      if(!project.repository().equals("load")){bothStarted.countDown();assertThat(bothStarted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
      return new SparseProjects.Checkout("a".repeat(40),Map.of("ckp/chart/Chart.yaml",Base64.getEncoder().encodeToString("version: 1.0.0\n".getBytes()),
          "ckp/chart/values.yaml",Base64.getEncoder().encodeToString("rate: 1\n".getBytes())));
    });
    when(images.resolve(any(),any(),any(),any())).thenReturn(new Image("service:test","","repo/test","v1","sha256:"+"a".repeat(64)));
    var planner=new RealPreparation(catalog,source,helm,images,store,settings,connections);
    var request=new RealPreparation.Request("Parallel",requests.subList(0,2),requests.get(2),0,10,600,List.of(),List.of());
    var progress=new ReviewProgress("review",request);
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(requestAttributes);
    try {
      var plan=planner.prepare(request,progress::step);
      assertThat(plan.services()).extracting(PreparedService::serviceId).containsExactly("first","second","load");
      assertThat(Json.write(progress.snapshot())).contains("Ready").doesNotContain("Queued");
      assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(requestAttributes);
    } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
  }

  @Test void preparationSnapshotsCkpCommitDigestAndEditableValues() throws Exception {
    var destination=new Catalog.Destination("load-ns","load-release",List.of("ckp/helm/load/values.yaml"));
    var service=new Catalog.Service("projects/load",List.of(),Map.of(),null,List.of(),destination,
        new Catalog.ContainerImage("registry","dev","team","load"),
        new Catalog.SourceProject("stash","SP","load","main","ckp/helm/load"),Map.of());
    when(catalog.service("load")).thenReturn(service);when(catalog.boundEnvironment()).thenReturn("sandbox");
    when(catalog.environment("sandbox")).thenReturn(new Catalog.Environment("Sandbox","cluster",null,null,null,null,null,null));
    when(helm.target()).thenReturn(Map.of("context","sandbox","server","https://cluster.invalid"));
    var source=mock(SparseProjects.class);
    var files=Map.of("ckp/helm/load/Chart.yaml",Base64.getEncoder().encodeToString("apiVersion: v2\nname: load\nversion: __REPLACEAPPVERSION__\nappVersion: __REPLACEAPPVERSION__\n".getBytes()),
        "ckp/helm/load/values.yaml",Base64.getEncoder().encodeToString("rate: 10\noptional: null\n".getBytes()));
    when(source.checkout(any(),eq("main"))).thenReturn(new SparseProjects.Checkout("a".repeat(40),files));
    when(images.resolve(eq("load"),eq("service:load"),isNull(),eq("v1"))).thenReturn(new Image("service:load","","repo/load","v1","sha256:"+"a".repeat(64)));
    var planner=new RealPreparation(catalog,source,helm,images,store,settings,connections);
    doAnswer(call -> {
      java.nio.file.Path folder=call.getArgument(0);
      java.nio.file.Files.createDirectories(folder.resolve("ckp/helm/load/charts"));
      java.nio.file.Files.write(folder.resolve("ckp/helm/load/charts/common.tgz"),new byte[]{1,2,3});
      java.nio.file.Files.writeString(folder.resolve("ckp/helm/load/Chart.lock"),"lock snapshot");
      return null;
    }).when(helm).prepareDependencies(any(),any());
    doAnswer(call -> {
      java.nio.file.Path folder=call.getArgument(0);
      var metadata=HelmValues.parse(java.nio.file.Files.readString(folder.resolve("ckp/helm/load/Chart.yaml")));
      assertThat(metadata).containsEntry("appVersion","v1").containsEntry("version","0.0.0-build.v1");
      assertThat(folder.resolve("ckp/helm/load/charts/common.tgz")).exists();
      return null;
    }).when(helm).validate(any(),any(),any(),any(),any());
    var request=new RealPreparation.Request("Prepared",List.of(),new RealPreparation.Deployment("load","main","v1",null,"rate: 20\n"),0,10,600,List.of(),List.of());
    var discovered=Json.MAPPER.valueToTree(planner.profiles("load","main"));
    assertThat(discovered.path("valuesFiles").has("ckp/helm/load/values.yaml")).isTrue();
    assertThat(discovered.path("valuesFiles").has("ckp/helm/load/Chart.yaml")).isFalse();
    var legacy=Json.read("{\"serviceId\":\"load\",\"revision\":\"main\",\"imageVersion\":\"v1\",\"valuesFiles\":[],\"overlay\":\"\"}",RealPreparation.Deployment.class);
    assertThat(legacy.valuesEdits()).isNull();
    assertThat(legacy.gitReference()).isNull();
    var named=new RealPreparation.Deployment("load","a".repeat(40),"v1",List.of(),"",Map.of(),"refs/heads/master");
    var namedRestored=Json.read(Json.write(named),RealPreparation.Deployment.class);
    assertThat(namedRestored.gitReference()).isEqualTo("refs/heads/master");
    assertThat(namedRestored.revision()).isEqualTo("a".repeat(40));
    var plan=planner.prepare(request);var prepared=plan.services().getFirst();
    assertThat(plan.simulated()).isFalse();assertThat(prepared.sourceRevision()).isEqualTo("a".repeat(40));
    assertThat(prepared.effectiveValues()).containsEntry("rate",20).containsEntry("optional",null);
    assertThat(prepared.effectiveValues().get("imageTag")).isEqualTo("v1");
    assertThat(prepared.effectiveValues()).containsEntry("targetPlatform","ckp").containsEntry("clusterSubdomain","cluster")
        .containsEntry("tags",Map.of("moc",false));
    assertThat((Map<String,Object>)prepared.effectiveValues().get("global"))
        .containsEntry("targetPlatform","ckp").containsEntry("clusterSubdomain","cluster").containsEntry("deploymentSuffix","").containsEntry("imageTag","v1");
    String preparedChart=prepared.preparedFiles().get("ckp/helm/load/Chart.yaml");
    assertThat(new String(Base64.getDecoder().decode(preparedChart))).doesNotContain("__REPLACEAPPVERSION__");
    assertThat(prepared.originalHashes().get("ckp/helm/load/Chart.yaml")).isEqualTo(Json.hash(files.get("ckp/helm/load/Chart.yaml")));
    assertThat(prepared.preparedHashes().get("ckp/helm/load/Chart.yaml")).isEqualTo(Json.hash(preparedChart))
        .isNotEqualTo(prepared.originalHashes().get("ckp/helm/load/Chart.yaml"));
    assertThat(store.plan(plan.id()).services().getFirst().preparedFiles()).isEqualTo(prepared.preparedFiles());
    assertThat(prepared.preparedFiles()).containsKeys("ckp/helm/load/charts/common.tgz","ckp/helm/load/Chart.lock");
    assertThat(prepared.originalHashes()).doesNotContainKey("ckp/helm/load/charts/common.tgz");
    assertThat(plan.checksum()).isEqualTo(PlanningService.checksum(store.plan(plan.id())));
    var editedRequest=new RealPreparation.Request("Edited values",List.of(),
        new RealPreparation.Deployment("load","main","v1",List.of("ckp/helm/load/values.yaml"),"",
          Map.of("ckp/helm/load/values.yaml","editedRate: 30\ntargetPlatform: other\nclusterSubdomain: custom.domain\ntags:\n  moc: true\n  other: false\nglobal:\n  targetPlatform: other\n  deploymentSuffix: '-test'\n  customValue: kept\n")),0,10,600,List.of(),List.of());
    var edited=planner.prepare(editedRequest).services().getFirst().effectiveValues();
    assertThat(edited).containsEntry("editedRate",30).doesNotContainKeys("rate","optional");
    assertThat(edited).containsEntry("targetPlatform","ckp").containsEntry("clusterSubdomain","custom.domain")
        .containsEntry("tags",Map.of("moc",true,"other",false));
    assertThat((Map<String,Object>)edited.get("global")).containsEntry("targetPlatform","ckp")
        .containsEntry("deploymentSuffix","-test").containsEntry("customValue","kept").containsEntry("clusterSubdomain","cluster");
    var restored=Json.read(Json.write(editedRequest),RealPreparation.Request.class);
    assertThat(restored.loadGenerator().valuesEdits()).isEqualTo(editedRequest.loadGenerator().valuesEdits());
    var invalidEdits=new RealPreparation.Request("Invalid file",List.of(),
        new RealPreparation.Deployment("load","main","v1",List.of("ckp/helm/load/values.yaml"),"",
          Map.of("ckp/unselected.yaml","rate: 1")),0,10,600,List.of(),List.of());
    assertThatThrownBy(()->planner.prepare(invalidEdits)).hasMessageContaining("selected values files");
    when(helm.baseline(any(),any(),any())).thenReturn("EXISTS");
    assertThatThrownBy(()->planner.prepare(request)).hasMessageContaining("already exists");
    clearInvocations(source);
    when(helm.baseline(any(),any(),any())).thenThrow(new com.example.perforchestrator.domain.Problem(502,"LOOKUP_FAILED","execution","Cluster login expired"));
    assertThatThrownBy(()->planner.prepare(request)).hasMessageContaining("Service load, namespace load-ns, release load-release, context sandbox")
        .hasMessageContaining("Cluster login expired");
    verifyNoInteractions(source);
  }

}
