import { el, input, labeled, select } from "./dom.js";

export async function realFlow(api, catalog, navigate) {
  const settings = await api("/real/execution");
  const root = el("section", { class: "card" });
  if (!settings.enabled) {
    root.append(el("h2", {}, "Enable real execution"), el("p", {}, "Set orchestrator.execution.enabled, kube-context and expected-api-server in application.yaml or Helm values. Install Git, Helm 3 and kubectl where the application runs, then restart."));
    return root;
  }
  root.append(el("h2", {}, "Prepare a real performance run"), el("p", { class: "muted" }, `Cluster context: ${settings.kubeContext} · ${settings.expectedApiServer}`),
    el("p", {}, "Select service deployments and one load generator. Values YAML controls traffic and destinations. Preparation fetches only ckp, pins the Git commit and image, and validates charts; execution starts only after you review and confirm."));
  const ids = Object.keys(catalog.services).filter(id => catalog.services[id].sourceProject && catalog.services[id].containerImage);
  if (!ids.length) { root.append(el("p", {}, "Configure Git, container image and Helm deployment settings under Services first.")); return root; }
  const name = input("Performance test"), warmup = input("0", "number", { min: 0 }), duration = input("60", "number", { min: 1 }), deadline = input("900", "number", { min: 60, max: 28800 });
  const rows = el("div"), selections = [], error = el("p", { role: "alert", class: "error" });
  const preview = el("div"), submit = el("button", { type: "button", class: "primary" }, "Prepare plan");
  let plan = null, preparing = false, savedId = null, savedRevision = null;
  function invalidate() { plan = null; preview.replaceChildren(); }
  function deployment(load, initial) {
    const service = select(ids.map(id=>[id,id]), initial || ids[0]);
    const revision=input(""), version=input(""), values=el("select", { multiple: true, size: 3 }), overlay=el("textarea", { rows: 8, spellcheck: "false" });
    const state=el("p", { role: "status", class: "muted" });
    const fetchValues=el("button", { type:"button" }, "Load CKP values files");
    const useValues=el("button", { type:"button" }, "Copy selected YAML into editor");
    let files={};
    const imageChoices=el("datalist",{id:"image-"+crypto.randomUUID()}),gitChoices=el("datalist",{id:"git-"+crypto.randomUUID()});
    version.setAttribute("list",imageChoices.id);revision.setAttribute("list",gitChoices.id);
    const refType=select([["tags","Tags"],["branches","Branches"]],"tags");
    const getImages=el("button",{type:"button"},"Fetch image versions");
    const getRefs=el("button",{type:"button"},"Fetch Git references");
    let imageCursor="",gitStart=0;
    getImages.addEventListener("click",async()=>{
      getImages.disabled=true;
      try {
        const result=await api(`/registry-sources/${encodeURIComponent("service:"+service.value)}/services/${encodeURIComponent(service.value)}/images/query`,
          {method:"POST",body:{limit:50,cursor:imageCursor,username:null,authentication:null}});
        if(!imageCursor)imageChoices.replaceChildren();
        imageChoices.append(...result.versions.map(tag=>el("option",{value:tag},tag)));
        imageCursor=result.nextCursor || "";getImages.textContent=imageCursor?"Fetch more image versions":"Refresh image versions";
        state.textContent="Image versions loaded. Choose or enter a version in the image field.";
      }catch(reason){state.textContent=reason.message;}finally{getImages.disabled=false;}
    });
    getRefs.addEventListener("click",async()=>{
      getRefs.disabled=true;
      try {
        const result=await api(`/service-projects/${encodeURIComponent(service.value)}/references/query`,{method:"POST",body:{kind:refType.value,start:gitStart,authentication:null}});
        if(gitStart===0)gitChoices.replaceChildren();
        gitChoices.append(...result.values.map(ref=>el("option",{value:ref.id},ref.displayName)));
        gitStart=result.nextStart ?? 0;getRefs.textContent=gitStart?"Fetch more Git references":"Refresh Git references";
        state.textContent="Git references loaded. Choose or enter a revision in the Git field.";
      }catch(reason){state.textContent=reason.message;}finally{getRefs.disabled=false;}
    });
    refType.addEventListener("change",()=>{gitStart=0;gitChoices.replaceChildren();getRefs.textContent="Fetch Git references";});
    const card=el("article",{class:"card spacer"},el("h3",{},load?"Load generator":"Service deployment"),
      el("div",{class:"form-grid"},labeled("Service",service),labeled("Git tag / branch / commit",revision),labeled("Container image version",version)),
      imageChoices,gitChoices,el("div",{class:"card-actions"},getImages,labeled("Git reference type",refType),getRefs),
      labeled("Values files (select one or more)",values),el("div",{class:"card-actions"},fetchValues,useValues),
      labeled("Values overlay YAML",overlay),state);
    const loadDefaults=()=>{
      imageCursor="";gitStart=0;imageChoices.replaceChildren();gitChoices.replaceChildren();getImages.textContent="Fetch image versions";getRefs.textContent="Fetch Git references";
      const config=catalog.services[service.value]; revision.value=config.sourceProject.revision; version.value="";overlay.value="";files={};
      const dest=config.deploymentByEnvironment?.[catalog.environment] || config.deploymentDefaults;
      values.replaceChildren(...(dest?.valuesFiles || []).map(path=>el("option",{value:path,selected:true},path)));
      state.textContent="Enter a discovered image tag. Namespace and release come from Services.";invalidate();
    };
    service.addEventListener("change",loadDefaults);loadDefaults();
    fetchValues.addEventListener("click",async()=>{
      fetchValues.disabled=true;
      try {
        const result=await api(`/real/services/${encodeURIComponent(service.value)}/values`,{method:"POST",body:{revision:revision.value}});
        files=result.valuesFiles;const selected=new Set([...values.selectedOptions].map(o=>o.value));
        values.replaceChildren(...Object.keys(files).map(path=>el("option",{value:path,selected:selected.has(path)},path)));
        // Pin the source used to display/edit the profile, even if the branch moves later.
        revision.value=result.commit;state.textContent=`CKP files loaded at commit ${result.commit}.`;invalidate();
      } catch(reason){state.textContent=reason.message;}finally{fetchValues.disabled=false;}
    });
    useValues.addEventListener("click",()=>{
      const paths=[...values.selectedOptions].map(o=>o.value);
      if(paths.length!==1 || !Object.hasOwn(files,paths[0])){state.textContent="Load files, then select one file to copy into the editor.";return;}
      overlay.value=files[paths[0]];invalidate();
    });
    const row={card,set(data) {
      service.value=data.serviceId;loadDefaults();revision.value=data.revision;version.value=data.imageVersion;overlay.value=data.overlay || "";
      if(data.valuesFiles?.length)values.replaceChildren(...data.valuesFiles.map(path=>el("option",{value:path,selected:true},path)));
    },read:()=>({serviceId:service.value,revision:revision.value,imageVersion:version.value,
      valuesFiles:[...values.selectedOptions].map(o=>o.value),overlay:overlay.value})};
    if(!load)card.append(el("button",{type:"button",onclick:()=>{selections.splice(selections.indexOf(row),1);card.remove();invalidate();}},"Remove deployment"));
    card.addEventListener("input",invalidate);card.addEventListener("change",invalidate);return row;
  }
  const load=deployment(true,ids.find(id=>id.includes("load-gen")));
  const add=el("button",{type:"button",onclick:()=>{const row=deployment(false);selections.push(row);rows.append(row.card);invalidate();}},"Add service deployment");
  const metrics=el("textarea",{rows:5,placeholder:'Optional JSON: [{"serviceId":"service-id","name":"request_count","query":"sum(count_over_time({...}[{{durationSeconds}}s]))"}]'});
  const thresholds=el("textarea",{rows:3,placeholder:'Optional JSON: [{"metric":"error_rate","maximum":0.01,"required":true}]'});
  root.append(el("div",{class:"form-grid"},labeled("Run name",name),labeled("Warmup seconds",warmup),labeled("Measurement seconds",duration),labeled("Maximum run seconds (including deployment)",deadline)),
    rows,add,load.card,el("details",{class:"spacer"},el("summary",{},"Optional LogQL measurements and thresholds"),
      el("p",{class:"muted"},"Use instant LogQL metric queries returning one aggregated series. Available placeholders: {{namespace}}, {{clusterEnv}}, {{durationSeconds}}. No configured queries means an inconclusive verdict. request_count must be positive for a performance PASS."),labeled("Measurements",metrics),labeled("Maximum thresholds",thresholds)),
    el("p",{class:"muted"},"Do not put credentials in chart values. Use existing Kubernetes Secret references. The load release must not already exist; it will be uninstalled on completion or cancellation. Services remain deployed."),error,submit,preview);
  for(const field of [name,warmup,duration,deadline,metrics,thresholds])field.addEventListener("input",invalidate);
  const readProfile=()=>({name:name.value,services:selections.map(row=>row.read()),loadGenerator:load.read(),
    warmupSeconds:Number(warmup.value),measurementSeconds:Number(duration.value),maxRunDurationSeconds:Number(deadline.value),
    metrics:metrics.value.trim()?JSON.parse(metrics.value):[],thresholds:thresholds.value.trim()?JSON.parse(thresholds.value):[]});
  const savedProfiles=await api("/real/profiles");
  const savedSelect=select([["","New profile"],...savedProfiles.map(p=>[p.id,p.profile.name])],"");
  savedSelect.addEventListener("change",()=>{
    const saved=savedProfiles.find(p=>p.id===savedSelect.value);savedId=saved?.id || null;savedRevision=saved?.revision || null;invalidate();
    if(!saved)return;
    const p=saved.profile;name.value=p.name;warmup.value=p.warmupSeconds;duration.value=p.measurementSeconds;deadline.value=p.maxRunDurationSeconds;
    rows.replaceChildren();selections.length=0;
    for(const item of p.services){const row=deployment(false,item.serviceId);row.set(item);selections.push(row);rows.append(row.card);}
    load.set(p.loadGenerator);metrics.value=p.metrics?.length?JSON.stringify(p.metrics,null,2):"";thresholds.value=p.thresholds?.length?JSON.stringify(p.thresholds,null,2):"";
  });
  const save=el("button",{type:"button"},"Save profile");
  save.addEventListener("click",async()=>{
    save.disabled=true;
    try {
      const result=await api("/real/profiles",{method:"POST",body:{id:savedId,revision:savedRevision,profile:readProfile()}});
      savedId=result.id;savedRevision=result.revision;
      const index=savedProfiles.findIndex(p=>p.id===result.id);if(index<0)savedProfiles.push(result);else savedProfiles[index]=result;
      savedSelect.replaceChildren(el("option",{value:""},"New profile"),...savedProfiles.map(p=>el("option",{value:p.id},p.profile.name)));savedSelect.value=result.id;
      error.textContent="Profile saved. Prepare a new plan before running.";
    }catch(reason){error.textContent=reason.message;}finally{save.disabled=false;}
  });
  root.prepend(el("div",{class:"form-grid"},labeled("Saved profile",savedSelect)),save);
  submit.addEventListener("click",async()=>{
    if(preparing)return;preparing=true;submit.disabled=true;error.textContent="";invalidate();
    const controls=[...root.querySelectorAll("input,select,textarea,button")];controls.forEach(n=>n.disabled=true);
    try {
      plan=await api("/real/plans",{method:"POST",body:readProfile()});
      const confirmed=el("input",{type:"checkbox"});const run=el("button",{type:"button",class:"primary"},"Start real run");
      preview.append(el("h3",{},"Review prepared plan"),el("ul",{},plan.warnings.map(text=>el("li",{},text))),
        el("pre",{},JSON.stringify(plan.services.map(s=>({service:s.serviceId,commit:s.sourceRevision,image:s.image.version,digest:s.image.digest,namespace:s.namespace,release:s.releaseName,changes:s.changes})),null,2)),
        labeled("I reviewed the services, values and target cluster; execute this plan.",confirmed),run);
      const key=crypto.randomUUID();
      run.addEventListener("click",async()=>{
        if(!plan || !confirmed.checked){error.textContent="Review and confirm the prepared plan first.";return;}
        run.disabled=true;
        try {const result=await api("/real/runs",{method:"POST",headers:{"Idempotency-Key":key},body:{planId:plan.id}});navigate(`#run/${result.id}`);}
        catch(reason){error.textContent=reason.message;run.disabled=false;}
      });
    }catch(reason){error.textContent=reason.message;}
    finally{preparing=false;controls.forEach(n=>n.disabled=false);}
  });
  return root;
}
