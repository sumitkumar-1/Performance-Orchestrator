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
    try(var scope=log.scope(a)){DiagnosticLog.begin("HTTP",SafeDiagnostics.endpoint("GET",URI.create("https://host.invalid/loki/api/v1/query?query=PRIVATE_QUERY"))).finish("HTTP 403");}
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
  @Test void allowlistDoesNotRetainSecretsEvenWhenTheyResembleFlagsOrUrls(){
    String command=SafeDiagnostics.command(List.of("helm","repo","add","private-alias","https://u:PRIVATE@host.invalid", "--password","PRIVATE","--username","PRIVATE","--set=token=PRIVATE"));
    assertThat(command).contains("helm repo add","--username","[redacted]").doesNotContain("PRIVATE","private-alias","u:");
    String endpoint=SafeDiagnostics.endpoint("GET",URI.create("https://u:PRIVATE@host.invalid/SecretServer/api/v1/secrets/PRIVATE?token=PRIVATE"));
    assertThat(endpoint).contains("host.invalid/SecretServer/api/v1/secrets/[redacted]").doesNotContain("PRIVATE","u:");
    assertThat(ClusterDiagnostics.pluginHint("getting credentials: exec: executable /private/bin/kubelogin not found PRIVATE_TOKEN"))
        .contains("kubelogin","PATH").doesNotContain("/private/","PRIVATE_TOKEN");
    assertThat(ClusterDiagnostics.pluginHint("exec: executable helper failed with exit code 1 PRIVATE_TOKEN"))
        .contains("helper exited with code 1").doesNotContain("PRIVATE_TOKEN");
  }
}
