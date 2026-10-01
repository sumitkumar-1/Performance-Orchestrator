package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import com.example.perforchestrator.infrastructure.diagnostics.*;
import com.example.perforchestrator.infrastructure.execution.*;
import com.example.perforchestrator.infrastructure.config.Json;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.*;

class DiagnosticLogTest {
  @TempDir Path directory;
  HikariDataSource db;
  DiagnosticLog log;
  @BeforeEach void setup(){open();Flyway.configure().dataSource(db).load().migrate();}
  void open(){db=new HikariDataSource();db.setJdbcUrl("jdbc:h2:file:"+directory.resolve("diagnostics"));db.setUsername("sa");db.setPassword("");log=new DiagnosticLog(new JdbcTemplate(db));}
  @AfterEach void close(){db.close();}
  @Test void persistsPreparationAndSeparatesRunActivityAcrossRestart() throws Exception {
    String preparation=log.create(null);
    try(var scope=log.scope(preparation)){
      new CommandRunner().run(List.of("/usr/bin/printf","%s","PRIVATE_OUTPUT"),directory,Map.of("TOKEN","PRIVATE_ENV"),Duration.ofSeconds(2));
    }
    log.plan(preparation,"plan");String a=log.create(preparation),b=log.create(preparation);log.run(a,"run-a");log.run(b,"run-b");
    try(var scope=log.scope(a)){DiagnosticLog.begin("HTTP",SafeDiagnostics.endpoint("GET",URI.create("https://host.invalid/loki/api/v1/query?query=%7Bpassword%3D%22PRIVATE_QUERY%22%7D"))).finish("HTTP 403");}
    try(var scope=log.scope(b)){DiagnosticLog.begin("COMMAND","helm version").finish("EXIT 0");}
    DiagnosticLog.begin("COMMAND","outside scope").finish("EXIT 0");
    db.close();open();
    assertThat(log.forRun("run-a")).isEqualTo(a);assertThat(log.forPlan("plan")).isEqualTo(preparation);
    String first=Json.write(log.operations(a));String second=Json.write(log.operations(b));
    assertThat(first).contains("HTTP 403","EXIT 0","durationMs").doesNotContain("PRIVATE_OUTPUT","PRIVATE_ENV","PRIVATE_QUERY","outside scope","helm version");
    assertThat(second).contains("helm version").doesNotContain("HTTP 403","outside scope");
  }
  @Test void nestedScopesRestoreAndDoNotPropagateToUnrelatedThreads() throws Exception {
    String a=log.create(null),b=log.create(null);
    try(var scope=log.scope(a)){
      try(var inner=log.scope(b)){DiagnosticLog.begin("COMMAND","inner").finish("EXIT 0");}
      var thread=new Thread(()->DiagnosticLog.begin("COMMAND","unrelated").finish("EXIT 0"));thread.start();thread.join();
      DiagnosticLog.begin("COMMAND","outer").finish("EXIT 0");
    }
    assertThat(Json.write(log.operations(a))).contains("outer").doesNotContain("inner","unrelated");
    assertThat(Json.write(log.operations(b))).contains("inner").doesNotContain("outer");
  }
  @Test void disabledDiagnosticsDoNotCreateTracesOrRecordActivity(){
    var jdbc=new JdbcTemplate(db);var disabled=new DiagnosticLog(jdbc,false);
    assertThat(disabled.enabled()).isFalse();assertThat(disabled.create(null)).isNull();
    try(var scope=disabled.scope(null)){DiagnosticLog.begin("COMMAND","helm version").finish("EXIT 0");}
    disabled.plan(null,"plan");disabled.run(null,"run");
    assertThat(disabled.recent()).isEmpty();assertThat(disabled.operations("any")).isEqualTo(List.of());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic_traces",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic_operations",Integer.class)).isZero();
  }
  @Test void operationalDetailsRemainVisibleWhileCredentialValuesAreMasked(){
    String command=SafeDiagnostics.command(List.of("helm","repo","add","private-alias","https://u:PRIVATE@host.invalid", "--password","PRIVATE","--username","first.last","--set=token=PRIVATE"));
    assertThat(command).contains("helm repo add","--username first.last","private-alias","[redacted]").doesNotContain("PRIVATE","u:");
    assertThat(SafeDiagnostics.command(List.of("helm","--kube-context","sandbox-nvan","list","--namespace","sng-smtp-receiver","--output","json")))
        .isEqualTo("helm --kube-context sandbox-nvan list --namespace sng-smtp-receiver --output json");
    assertThat(SafeDiagnostics.command(List.of("git","-c","http.extraHeader=Authorization: Bearer PRIVATE","fetch","origin","master")))
        .contains("fetch origin master").doesNotContain("PRIVATE");
    assertThat(SafeDiagnostics.command(List.of("helm","--header","Cookie: session=PRIVATE; csrf=PRIVATE","--password=PRIVATE")))
        .doesNotContain("PRIVATE");
    String endpoint=SafeDiagnostics.endpoint("GET",URI.create("https://u:PRIVATE@host.invalid/SecretServer/api/v1/secrets/20000?token=PRIVATE&start=10"));
    assertThat(endpoint).contains("host.invalid/SecretServer/api/v1/secrets/20000", "start=10").doesNotContain("PRIVATE","u:");
    assertThat(ClusterDiagnostics.pluginHint("getting credentials: exec: executable /private/bin/kubelogin not found PRIVATE_TOKEN"))
        .contains("kubelogin","PATH").doesNotContain("/private/","PRIVATE_TOKEN");
    assertThat(ClusterDiagnostics.pluginHint("exec: executable helper failed with exit code 1 PRIVATE_TOKEN"))
        .contains("helper exited with code 1").doesNotContain("PRIVATE_TOKEN");
  }
}
