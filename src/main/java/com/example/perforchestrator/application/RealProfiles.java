package com.example.perforchestrator.application;
import com.example.perforchestrator.domain.Problem;
import com.example.perforchestrator.infrastructure.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RealProfiles {
  public record Saved(String id,int revision,RealPreparation.Request profile){}
  private final JdbcTemplate db;private final Catalog catalog;
  public RealProfiles(JdbcTemplate db,Catalog catalog){this.db=db;this.catalog=catalog;}
  public List<Saved> list(){return db.query("SELECT id,revision,body FROM real_profiles WHERE environment=? ORDER BY id",(r,n)->new Saved(r.getString(1),r.getInt(2),Json.read(r.getString(3),RealPreparation.Request.class)),catalog.boundEnvironment());}
  public Saved save(String id,Integer revision,RealPreparation.Request profile){
    if(!catalog.mode().equals("real") || profile==null || profile.name()==null || profile.name().isBlank() || profile.name().length()>100)throw Problem.invalid("profile","A real profile name is required");
    if(profile.services()==null || profile.loadGenerator()==null)throw Problem.invalid("profile","Services and a load-generator selection are required");
    String body=Json.write(profile);if(body.length()>512*1024)throw Problem.invalid("profile","Profile exceeds 512 KiB");
    if(id==null){id=UUID.randomUUID().toString();db.update("INSERT INTO real_profiles VALUES (?,?,?,?)",id,1,catalog.boundEnvironment(),body);return new Saved(id,1,profile);}
    if(revision==null || db.update("UPDATE real_profiles SET revision=revision+1,body=? WHERE id=? AND revision=? AND environment=?",body,id,revision,catalog.boundEnvironment())!=1)throw Problem.conflict("Profile changed; reload before saving");
    return new Saved(id,revision+1,profile);
  }
}
