package com.example.perforchestrator.application;

import com.example.perforchestrator.domain.*;
import com.example.perforchestrator.domain.Model.*;
import com.example.perforchestrator.infrastructure.config.*;
import com.example.perforchestrator.infrastructure.execution.HelmExecution;
import com.example.perforchestrator.infrastructure.persistence.Store;
import com.example.perforchestrator.infrastructure.registry.*;
import com.example.perforchestrator.infrastructure.secrets.SecretServerTokens;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdditionalLoads {
  private final Store store;
  private final RealPreparation preparation;
  private final Catalog catalog;
  private final ConnectionConfig connections;
  private final ArtifactoryImages images;
  private final HelmExecution helm;
  private final TransactionTemplate tx;

  public AdditionalLoads(Store store,RealPreparation preparation,Catalog catalog,ConnectionConfig connections,
      ArtifactoryImages images,HelmExecution helm,TransactionTemplate tx) {
    this.store=store;this.preparation=preparation;this.catalog=catalog;this.connections=connections;
    this.images=images;this.helm=helm;this.tx=tx;
  }

  public List<AdditionalLoad> list(String runId) { store.run(runId);return store.additionalLoads(runId); }

  private Plan activePlan(String runId) {
    preparation.enabled();
    var run=store.run(runId);var plan=store.plan(run.planId());
    if(plan.simulated() || run.state()!=State.RUNNING_LOAD)throw Problem.conflict("Additional load can be added only while the real run's baseline load is running");
    var now=Instant.now();
    if(now.isAfter(Instant.parse(run.createdAt()).plusSeconds(plan.profile().maxRunDurationSeconds()))
        || !now.isBefore(Instant.parse(run.measurementStartedAt()).plusSeconds(plan.profile().loadGenerator().measurementSeconds())))
      throw Problem.conflict("The run's measurement window has ended; start a new run");
    return plan;
  }

  @SuppressWarnings("unchecked") private Map<String,Object> target(Plan plan) {
    return (Map<String,Object>)plan.effectiveLoadConfiguration().get("target");
  }

  public AdditionalLoad prepare(String runId,RealPreparation.Deployment selection) {
    var plan=activePlan(runId);
    if(!plan.catalogHash().equals(catalog.hash()) || !Objects.equals(plan.effectiveLoadConfiguration().get("connectionHash"),Json.hash(Json.write(connections.data()))))
      throw Problem.conflict("Configuration changed since this run was prepared; start a new run with the updated configuration");
    if(list(runId).size()>=20)throw Problem.conflict("At most 20 additional load reviews are supported per run");
    String id=UUID.randomUUID().toString();
    // Full UUID keeps retries/repeated service selections independent; max Helm name length is 53.
    String release="load-"+id;
    var prepared=preparation.prepareAdditional(selection,target(plan),release);
    String now=Instant.now().toString();
    var load=new AdditionalLoad(id,runId,SecretServerTokens.currentActor(),now,now,Instant.now().plusSeconds(900).toString(),
        catalog.hash(),Json.hash(Json.write(connections.data())),"REVIEWED","Ready to install; no traffic started",prepared.chart(),prepared.service());
    return tx.execute(status->{store.lock();activePlan(runId);
      if(store.additionalLoads(runId).size()>=20)throw Problem.conflict("Additional load review limit reached");
      store.insertAdditionalLoad(load);store.audit("ADDITIONAL_LOAD_REVIEWED",id);return load;});
  }

  public AdditionalLoad enqueue(String runId,String id) {
    var load=owned(runId,id);
    // A repeated submit returns the original operation, never a second installation.
    if(!load.state().equals("REVIEWED"))return load;
    activePlan(runId);
    if(Instant.now().isAfter(Instant.parse(load.expiresAt())) || !load.catalogHash().equals(catalog.hash())
        || !load.connectionHash().equals(Json.hash(Json.write(connections.data()))))throw Problem.conflict("Additional load review expired or configuration changed; review again");
    var image=load.service().image();
    if(!images.resolve(load.service().serviceId(),image.sourceRef(),null,image.version()).digest().equals(image.digest()))
      throw Problem.conflict("Image tag changed since review; review again");
    return tx.execute(status->{store.lock();var current=owned(runId,id);
      if(!current.state().equals("REVIEWED"))return current;
      activePlan(runId);var queued=current.transition("QUEUED","Queued for a separate load installation");
      store.updateAdditionalLoad(queued);store.audit("ADDITIONAL_LOAD_QUEUED",id);return queued;});
  }

  private AdditionalLoad owned(String runId,String id) {
    var load=store.additionalLoad(id);
    if(!load.runId().equals(runId))throw Problem.missing("Additional load");
    return load;
  }

  /** Called only by the serialized real-run worker, so install and cleanup cannot overlap. */
  public void advance(Run run,Plan plan) {
    var next=list(run.id()).stream().filter(load->load.state().equals("QUEUED")).findFirst();
    if(next.isEmpty())return;
    var load=next.get();
    // Cancellation may arrive while release lookup is in flight. Recheck immediately before install.
    try {
      if(!"ABSENT".equals(helm.baseline(target(plan),load.service().namespace(),load.service().releaseName()))) {
        store.updateAdditionalLoad(load.transition("REJECTED","Release already exists; existing load was not changed"));return;
      }
    } catch(RuntimeException error) {
      store.updateAdditionalLoad(load.transition("REJECTED","Could not verify the new release is absent; no installation attempted. "+safeMessage(error)));return;
    }
    try { activePlan(run.id()); } catch(Problem ended) { return; }
    store.updateAdditionalLoad(load.transition("INSTALLING","Installing image "+load.service().image().version()));
    try {
      helm.apply(load.id(),target(plan),load.service(),load.chart(),true);
      store.updateAdditionalLoad(load.transition("RUNNING","Installed alongside baseline; generation is controlled by selected YAML"));
    } catch(RuntimeException error) {
      String detail=safeMessage(error);
      store.updateAdditionalLoad(load.transition("FAILED",detail));
      // A failed Helm install may have created resources. Remove only this extra release.
      try {
        if(!helm.stop(load.id(),target(plan),load.service()))throw Problem.conflict("Additional load cleanup could not be confirmed");
        store.updateAdditionalLoad(load.transition("FAILED_STOPPED",detail+" Partial additional load cleaned up; baseline remains running."));
      } catch(RuntimeException cleanupError) {
        store.updateAdditionalLoad(load.transition("CLEANUP_FAILED","Additional load failed and cleanup is unconfirmed. Baseline remains running; cleanup will be retried when the parent ends. "+safeMessage(cleanupError)));
      }
    }
  }

  private String safeMessage(RuntimeException error) {
    String detail=error instanceof Problem?error.getMessage():"Inspect command activity for details";
    return detail.length()>1400?detail.substring(0,1400):detail;
  }

  /** Also used after restart and manual recovery; ownership information is persisted before Helm runs. */
  public void cleanup(Run run,Plan plan) {
    RuntimeException failure=null;
    for(var load:list(run.id())) {
      try {
        if(Set.of("STOPPED","NOT_STARTED","FAILED_STOPPED","REJECTED").contains(load.state()))continue;
        if(Set.of("REVIEWED","QUEUED").contains(load.state())) {
          store.updateAdditionalLoad(load.transition("NOT_STARTED","Parent run ended before this load was installed"));continue;
        }
        if(!helm.stop(load.id(),target(plan),load.service()))throw Problem.conflict("Cannot confirm additional load cleanup: "+load.service().releaseName());
        store.updateAdditionalLoad(load.transition("STOPPED","Owned additional load uninstalled"));
      } catch(RuntimeException error){
        store.updateAdditionalLoad(load.transition("CLEANUP_FAILED","Cleanup unconfirmed; verify release ownership and cluster access"));
        if(failure==null)failure=error;else failure.addSuppressed(error);
      }
    }
    if(failure!=null)throw failure;
  }
}
