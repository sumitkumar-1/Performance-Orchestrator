package com.example.perforchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.perforchestrator.application.*;
import com.example.perforchestrator.domain.Model.Threshold;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;

class MonitoringSetsTest {
  @TempDir Path directory;
  HikariDataSource db;
  Catalog catalog=mock(Catalog.class);
  ConnectionConfig connections=new ConnectionConfig(new ConnectionConfig.Data(Map.of(),Map.of(),Map.of(),Map.of()));
  @BeforeEach void setup(){
    when(catalog.mode()).thenReturn("real");when(catalog.boundEnvironment()).thenReturn("sandbox");
    open();Flyway.configure().dataSource(db).load().migrate();
  }
  void open(){db=new HikariDataSource();db.setJdbcUrl("jdbc:h2:file:"+directory.resolve("sets"));db.setUsername("sa");db.setPassword("");}
  MonitoringSets sets(){return new MonitoringSets(new JdbcTemplate(db),catalog,connections);}
  MonitoringSets.Definition definition(){return new MonitoringSets.Definition("SMTP traffic",List.of(
      new RealPreparation.Metric("smtp","rate","sum(rate({namespace=\"{{namespace}}\"}[1m]))","Processed rate","smtp-ns","metric",null)),
      List.of(new Threshold("rate",100,true)));}
  @AfterEach void close(){db.close();}
  @Test void persistsAcrossRestartAndScopesSetsToEnvironment(){
    var saved=sets().save(null,null,definition());db.close();open();
    assertThat(sets().list()).containsExactly(saved);
    when(catalog.boundEnvironment()).thenReturn("perf");
    assertThat(sets().list()).isEmpty();
    assertThatThrownBy(()->sets().save(saved.id(),saved.revision(),definition())).hasMessageContaining("changed");
    assertThatThrownBy(()->sets().delete(saved.id(),saved.revision())).hasMessageContaining("changed");
    when(catalog.boundEnvironment()).thenReturn("sandbox");
    var updated=sets().save(saved.id(),saved.revision(),definition());
    assertThat(updated.revision()).isEqualTo(2);
    assertThatThrownBy(()->sets().save(saved.id(),saved.revision(),definition())).hasMessageContaining("changed");
    assertThatThrownBy(()->sets().delete(saved.id(),saved.revision())).hasMessageContaining("changed");
    sets().delete(updated.id(),updated.revision());assertThat(sets().list()).isEmpty();
  }
  @Test void validatesPanelsCredentialReferencesAndRulesWithoutResolvingSecrets(){
    assertThatThrownBy(()->sets().save(null,null,new MonitoringSets.Definition("empty",List.of(),List.of())))
        .hasMessageContaining("between 1 and 20");
    var panel=definition().panels().getFirst();
    assertThatThrownBy(()->sets().save(null,null,new MonitoringSets.Definition("duplicate",List.of(panel,panel),List.of())))
        .hasMessageContaining("unique");
    assertThatThrownBy(()->sets().save(null,null,new MonitoringSets.Definition("bad rule",List.of(panel),List.of(new Threshold("unknown",1,true)))))
        .hasMessageContaining("metric panel");
    var invalid=new RealPreparation.Metric("smtp","rate","query","title","ns","metric","unknown-secret");
    assertThatThrownBy(()->sets().save(null,null,new MonitoringSets.Definition("bad ref",List.of(invalid),List.of())))
        .hasMessageContaining("configured credential");
    assertThat(sets().list()).isEmpty();
  }
}
