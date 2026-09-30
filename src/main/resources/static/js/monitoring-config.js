import {el,input,labeled,select} from './dom.js';

export function monitoringConfig(catalog, credentialRefs, onChange = () => {}) {
  let panels=[], dialog;
  const list=el('div'), node=el('section',{class:'card'},el('div',{class:'dialog-heading'},el('h2',{},'Monitoring'),
    el('button',{type:'button',onclick:()=>edit()},'Add panel')),el('p',{class:'muted'},'Each panel uses a service’s environment credential by default. Override the Loki namespace or select another configured credential when needed.'),list);
  function render(){list.replaceChildren(...(panels.length?panels.map((p,i)=>el('article',{class:'run-selection'},el('div',{},el('strong',{},p.title||p.name),el('p',{class:'muted'},`${p.serviceId} · ${p.kind||'metric'} · ${p.namespace||'service namespace'} · ${p.credentialRef||'service credential'}`)),el('div',{class:'deployment-actions'},el('button',{type:'button',onclick:()=>edit(i)},'Edit'),el('button',{type:'button',onclick:()=>{panels.splice(i,1);render();onChange();}},'Remove')))):[el('p',{class:'muted'},'No monitoring panels configured.')]));}
  function edit(index){
    const old=panels[index], ids=Object.keys(catalog.services);
    const service=select(ids.map(id=>[id,id]),old?.serviceId||ids[0]);
    const title=input(old?.title||old?.name||''), name=input(old?.name||'panel_'+(panels.length+1));
    const kind=select([['metric','Metric / rate'],['logs','Logs']],old?.kind||'metric');
    const ns=input(old?.namespace||''), ref=select([['','Use service credential'],...credentialRefs.map(id=>[id,id])],old?.credentialRef||'');
    const query=el('textarea',{rows:6,spellcheck:'false',placeholder:'{namespace="{{namespace}}"}'});query.value=old?.query||'';
    const hint=el('p',{class:'muted'}), error=el('p',{role:'alert',class:'error',hidden:true});
    function defaults(){const s=catalog.services[service.value];const dest=s?.deploymentByEnvironment?.[catalog.environment]||s?.deploymentDefaults;ns.value=dest?.namespace||'';hint.textContent=`Default credential: ${s?.monitoringCredentials?.[catalog.environment]||'not configured'}`;}
    defaults();if(old?.namespace)ns.value=old.namespace;
    service.addEventListener('change',defaults);
    const form=el('form',{},el('h2',{},old?'Edit monitoring panel':'Add monitoring panel'),error,
      el('div',{class:'form-grid'},labeled('Title',title),labeled('Service',service),labeled('Display',kind),labeled('Loki namespace',ns),labeled('Credential reference',ref),labeled('Metric / panel ID',name)),hint,
      labeled('LogQL',query),el('p',{class:'muted'},'Use {{namespace}}, {{clusterEnv}} and {{durationSeconds}} placeholders. For final thresholds, use a metric query returning one aggregated value. Use request_count for the generated count.'),
      el('div',{class:'dialog-actions'},el('button',{type:'button',onclick:()=>dialog.close()},'Cancel'),el('button',{type:'submit',class:'primary'},'Save panel')));
    title.required=query.required=ns.required=true;name.required=true;name.pattern='[a-z][a-z0-9_]{0,63}';
    form.addEventListener('submit',event=>{event.preventDefault();if(panels.some((p,i)=>i!==index&&p.name===name.value)){error.hidden=false;error.textContent='Choose a unique panel ID.';error.scrollIntoView({block:'center'});return;}
      const panel={serviceId:service.value,title:title.value,name:name.value,namespace:ns.value,credentialRef:ref.value||null,kind:kind.value,query:query.value};
      if(old)panels[index]=panel;else panels.push(panel);render();onChange();dialog.close();});
    dialog=el('dialog',{class:'deployment-dialog run-service-dialog','aria-label':'Monitoring panel'},form);const current=dialog;dialog.addEventListener('close',()=>current.remove());document.body.append(dialog);dialog.showModal();title.focus();
  }
  render();return {node,read:()=>structuredClone(panels),set:value=>{panels=structuredClone(value||[]);render();},dispose:()=>{dialog?.close();dialog?.remove();}};
}
