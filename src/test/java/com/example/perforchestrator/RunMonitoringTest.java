package com.example.perforchestrator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.*;
import com.example.perforchestrator.infrastructure.persistence.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.net.*;

class RunMonitoringTest {
  Store store=mock(Store.class);JdbcTemplate db=mock(JdbcTemplate.class);Catalog catalog=mock(Catalog.class);
  ConnectionConfig connections=mock(ConnectionConfig.class);CredentialResolver credentials=mock(CredentialResolver.class);ReadOnlyHttp http=mock(ReadOnlyHttp.class);
  Plan plan;Run run;RunMonitoring monitoring;
  @BeforeEach void setup(){
    run=Json.read("{\"planId\":\"plan\",\"environment\":\"sandbox\",\"startedAt\":\"2026-09-30T10:00:00Z\"}",Run.class);
    when(store.run("run")).thenReturn(run);
    var service=new Catalog.Service("project",List.of(),Map.of(),null,List.of(),new Catalog.Destination("deployment-ns","release",List.of()),null,null,Map.of("sandbox","service-secret"));
    when(catalog.service("service")).thenReturn(service);
    var env=Json.read("{\"displayName\":\"Sandbox\",\"clusterIdentity\":\"cluster\",\"monitoring\":{\"connectionRef\":\"lower\"}}",Catalog.Environment.class);
    when(catalog.environment("sandbox")).thenReturn(env);
    // Only metadata is needed here; use a direct data record to avoid reading Spring YAML as a connection document.
    var credential=new ConnectionConfig.Credential("environment",null,null,null,null,null,null,null,"TOKEN");
    var data=new ConnectionConfig.Data(Map.of(),Map.of(),Map.of("service-secret",credential,"other-secret",credential),Map.of(),Map.of(),Map.of("lower",new ConnectionConfig.Loki("https://logs.invalid/loki/api/v1")));
    when(connections.data()).thenReturn(data);
    when(credentials.resolve("service-secret")).thenReturn(new CredentialResolver.Secret(null,"service-token",true));
    when(credentials.resolve("other-secret")).thenReturn(new CredentialResolver.Secret("reader","password"));
    monitoring=new RunMonitoring(store,db,catalog,connections,credentials,http);
  }
  void panels(RealPreparation.Metric... panels){
    plan=Json.read(Json.write(Map.of("actor","alice","profile",Map.of("targetEnvironment","sandbox"),
      "effectiveLoadConfiguration",Map.of("metrics",List.of(panels),"loadService","load"),
      "services",List.of(Map.of("serviceId","load","effectiveValues",Map.of("generator",Map.of("runTIme","PT72H")))))),Plan.class);
    when(store.plan("plan")).thenReturn(plan);
  }
  @Test void usesPanelNamespaceAndMappedSecretForEachQuery(){
    panels(new RealPreparation.Metric("service","rate","sum(rate({namespace=\"{{namespace}}\"}[1m]))","Rate","grafana-ns","metric",null),
        new RealPreparation.Metric("service","logs","{namespace=\"{{namespace}}\"}","Logs","other-ns","logs","other-secret"));
    when(http.get(any(),any(),any())).thenReturn(new ReadOnlyHttp.Response(200,Map.of(),"{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}".getBytes(StandardCharsets.UTF_8)));
    monitoring.query("run",0,"2026-09-30T10:00:00Z","2026-09-30T10:15:00Z");
    verify(http).get(argThat(uri->URLDecoder.decode(uri.toString(),StandardCharsets.UTF_8).contains("namespace=\"grafana-ns\"")),eq("Bearer service-token"),eq("application/json"));
    monitoring.query("run",1,"2026-09-30T10:00:00Z","2026-09-30T10:15:00Z");
    verify(http).get(argThat(uri->URLDecoder.decode(uri.toString(),StandardCharsets.UTF_8).contains("namespace=\"other-ns\"")),eq(RequestAuthentication.basic("reader","password")),eq("application/json"));
    verify(credentials).resolve("other-secret");
  }
  @Test void rejectsOversizedWindowsBeforeResolvingCredentials(){
    panels(new RealPreparation.Metric("service","logs","{namespace=\"{{namespace}}\"}"));
    assertThatThrownBy(()->monitoring.query("run",0,"2026-09-01T00:00:00Z","2026-09-30T00:00:00Z")).hasMessageContaining("7 days");
    verifyNoInteractions(credentials,http);
  }
  @Test void dashboardEditsPersistWithoutChangingPreparedVerdictQueries(){
    panels(new RealPreparation.Metric("service","count","sum(count_over_time({namespace=\"{{namespace}}\"}[1m]))"));
    var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:monitor-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
    jdbc.execute("CREATE TABLE run_monitoring (run_id VARCHAR(64) PRIMARY KEY,body CLOB NOT NULL)");
    monitoring=new RunMonitoring(store,jdbc,catalog,connections,credentials,http);
    var replacement=new RealPreparation.Metric("service","logs","{namespace=\"{{namespace}}\"}","New panel","custom-ns","logs","other-secret");
    monitoring.save("run",List.of(replacement));
    assertThat(monitoring.panels("run")).containsExactly(replacement);
    assertThat(Json.write(plan.effectiveLoadConfiguration())).contains("count_over_time").doesNotContain("custom-ns");
    verify(store).audit("MONITORING_UPDATED","run");verifyNoInteractions(http,credentials);
  }
  @Test void loadYamlDurationControlsOnlyMonitoringWindow(){
    panels();
    var result=Json.MAPPER.valueToTree(monitoring.view("run"));assertThat(result.path("windowSeconds").asLong()).isEqualTo(259200);
    verifyNoInteractions(http,credentials);
  }
}
