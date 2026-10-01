package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.domain.Model.Threshold;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.registry.ConnectionConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reusable, environment-scoped monitoring definitions; stores credential references only. */
@Service
public class MonitoringSets {
  public record Definition(String name,List<RealPreparation.Metric> panels,List<Threshold> thresholds) {}
  public record Saved(String id,int revision,Definition definition) {}
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final ConnectionConfig connections;
  public MonitoringSets(JdbcTemplate db,Catalog catalog,ConnectionConfig connections) {
    this.db=db;this.catalog=catalog;this.connections=connections;
  }
  public List<Saved> list() {
    return db.query("SELECT id,revision,body FROM monitoring_sets WHERE environment=? ORDER BY id",
        (r,n)->new Saved(r.getString(1),r.getInt(2),Json.read(r.getString(3),Definition.class)),catalog.boundEnvironment());
  }
  public Saved save(String id,Integer revision,Definition definition) {
    if(!catalog.mode().equals("real") || definition==null || definition.name()==null || definition.name().isBlank() || definition.name().length()>100)
      throw Problem.invalid("monitoring","A monitoring set name is required (at most 100 characters)");
    if(definition.panels()==null || definition.panels().isEmpty() || definition.panels().size()>20)
      throw Problem.invalid("monitoring","Save between 1 and 20 panels");
    Set<String> names=new HashSet<>();Set<String> metrics=new HashSet<>();
    for(var panel:definition.panels()) {
      RunMonitoring.validate(panel,catalog,connections);
      if(!names.add(panel.name()))throw Problem.invalid("monitoring","Panel IDs must be unique");
      if(!panel.logs())metrics.add(panel.name());
    }
    var thresholds=definition.thresholds()==null?List.<Threshold>of():definition.thresholds();
    if(thresholds.size()>20)throw Problem.invalid("thresholds","Use at most 20 rules");
    for(var rule:thresholds)if(rule==null || !metrics.contains(rule.metric()) || !Double.isFinite(rule.maximum()))
      throw Problem.invalid("thresholds","Rules must reference a metric panel and a finite maximum");
    var normalized=new Definition(definition.name().strip(),List.copyOf(definition.panels()),List.copyOf(thresholds));
    String body=Json.write(normalized);
    if(id==null) {
      id=UUID.randomUUID().toString();
      db.update("INSERT INTO monitoring_sets (id,revision,environment,body) VALUES (?,?,?,?)",id,1,catalog.boundEnvironment(),body);
      return new Saved(id,1,normalized);
    }
    if(revision==null || db.update("UPDATE monitoring_sets SET revision=revision+1,body=? WHERE id=? AND revision=? AND environment=?",
        body,id,revision,catalog.boundEnvironment())!=1)throw Problem.conflict("Monitoring set changed; reload the saved sets before updating");
    return new Saved(id,revision+1,normalized);
  }
  public void delete(String id,int revision) {
    if(db.update("DELETE FROM monitoring_sets WHERE id=? AND revision=? AND environment=?",id,revision,catalog.boundEnvironment())!=1)
      throw Problem.conflict("Monitoring set changed; reload the saved sets before deleting");
  }
}
