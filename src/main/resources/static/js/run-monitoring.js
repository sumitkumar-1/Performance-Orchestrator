import {el,input,labeled} from './dom.js';
import {monitoringConfig} from './monitoring-config.js';

export async function runMonitoring(api,id,catalog){
  const [config,connections]=await Promise.all([api(`/real/runs/${id}/monitoring`),api('/connections')]);
  const root=el('div',{class:'run-builder'}),error=el('p',{role:'alert',class:'error run-error',hidden:true}),results=el('div',{class:'run-builder'});
  let closed=false,timer,busy=false,revision=0,dirty=false;
  const local=date=>{const d=new Date(date);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,19);};
  const start=input(local(config.start),'datetime-local',{step:1}),end=input(local(new Date(new Date(config.start).getTime()+config.windowSeconds*1000)),'datetime-local',{step:1});
  const live=el('input',{type:'checkbox',checked:true});
  const state=el('p',{role:'status',class:'muted'});
  const editor=monitoringConfig({...catalog,environment:config.environment},connections.credentialReferences,()=>{dirty=true;revision++;clearTimeout(timer);state.textContent='Save panel changes before querying. Live refresh paused.';},{api});editor.set(config.panels);
  const save=el('button',{type:'button',onclick:async()=>{save.disabled=true;error.hidden=true;try{await api(`/real/runs/${id}/monitoring`,{method:'PUT',body:{panels:editor.read()}});dirty=false;revision++;await refresh();}catch(reason){fail(reason);}finally{save.disabled=false;}}},'Save monitoring panels');
  function fail(reason){error.hidden=false;error.textContent=reason.message;error.scrollIntoView({block:'center'});}
  function display(panel,data){
    const card=el('section',{class:'card'},el('h2',{},panel.title||panel.name),el('p',{class:'muted'},`${data.namespace} · ${data.credentialRef}`));
    if(!data.result.length){card.append(el('p',{},'No data in this time range.'));return card;}
    if(data.resultType==='streams'){
      const lines=data.result.flatMap(s=>s.values.map(([time,line])=>({time,line,labels:JSON.stringify(s.stream)}))).sort((a,b)=>Number(BigInt(b.time)-BigInt(a.time))).slice(0,500);
      const logs=el('div',{class:'monitor-logs'});for(const line of lines)logs.append(el('div',{},el('time',{},new Date(Number(BigInt(line.time)/1000000n)).toLocaleString()),el('pre',{},line.line)));
      card.append(el('p',{class:'muted'},'Up to 500 latest log entries in this range.'),logs);
    }else{
      for(const series of data.result.slice(0,20)){
        const points=series.values.filter(([,v])=>Number.isFinite(Number(v))).map(([t,v])=>[Number(t),Number(v)]);
        card.append(el('p',{class:'muted'},JSON.stringify(series.metric)));
        if(!points.length){card.append(el('p',{},'No numeric samples.'));continue;}
        const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 800 160');svg.setAttribute('role','img');svg.setAttribute('aria-label',`${panel.title||panel.name}: ${points.length} samples, latest ${points.at(-1)[1]}`);svg.classList.add('monitor-chart');
        const min=Math.min(...points.map(p=>p[1])),max=Math.max(...points.map(p=>p[1]));
        const path=document.createElementNS(svg.namespaceURI,'polyline');path.setAttribute('points',points.map(([t,v])=>`${10+(t-points[0][0])/Math.max(1,points.at(-1)[0]-points[0][0])*780},${150-(v-min)/Math.max(0.000001,max-min)*140}`).join(' '));path.setAttribute('fill','none');path.setAttribute('stroke','#176d56');path.setAttribute('stroke-width','2');svg.append(path);
        card.append(svg,el('p',{},`Latest ${points.at(-1)[1]} · Min ${min} · Max ${max} · ${new Date(points[0][0]*1000).toLocaleTimeString()} – ${new Date(points.at(-1)[0]*1000).toLocaleTimeString()}`));
        card.append(el('details',{},el('summary',{},'Sample values'),el('pre',{class:'monitor-logs'},points.map(([t,v])=>`${new Date(t*1000).toISOString()}  ${v}`).join('\n'))));
      }
      if(data.result.length>20)card.append(el('p',{},'Showing the first 20 series. Aggregate or narrow the query for fewer series.'));
    }return card;
  }
  async function refresh(){
    clearTimeout(timer);if(closed||busy||dirty)return;busy=true;const ticket=revision;error.hidden=true;
    try{
      const from=new Date(start.value),fixedEnd=new Date(end.value),to=live.checked?new Date(Math.min(Date.now(),fixedEnd.getTime())):fixedEnd;
      if(!Number.isFinite(from.getTime())||!Number.isFinite(to.getTime())||to<=from)throw new Error('Choose an end time later than the start time.');
      const panels=editor.read(),cards=[];
      // Sequential requests keep load bounded; one failed panel never hides the others.
      for(let i=0;i<panels.length;i++){
        if(closed||ticket!==revision)return;
        try{cards.push(display(panels[i],await api(`/real/runs/${id}/monitoring/query`,{method:'POST',body:{panel:i,start:from.toISOString(),end:to.toISOString()}})));}
        catch(reason){cards.push(el('section',{class:'card'},el('h2',{},panels[i].title||panels[i].name),el('p',{role:'alert',class:'error'},reason.message)));}
      }
      if(!closed&&ticket===revision){results.replaceChildren(...cards);state.textContent=`Updated ${new Date().toLocaleTimeString()}. ${live.checked && Date.now()<new Date(end.value).getTime()?'Refreshes every 15 seconds, up to the selected end time.':'Auto-refresh paused. Use Refresh now or extend the end time.'}`;}
    }catch(reason){if(!closed)fail(reason);}finally{busy=false;if(!closed&&!dirty) { if(ticket!==revision)timer=setTimeout(refresh,0);else if(live.checked && Date.now()<new Date(end.value).getTime())timer=setTimeout(refresh,15000); }}
  }
  for(const field of [start,end,live])field.addEventListener('change',()=>{revision++;clearTimeout(timer);refresh();});
  root.append(error,el('section',{class:'card'},el('h2',{},'Run monitoring'),el('p',{class:'muted'},`Run prepared by ${config.actor}. Times are local. The default range uses generator.runTime/runTIme when present, otherwise 15 minutes (maximum 7 days).`),
    el('div',{class:'form-grid'},labeled('Start',start),labeled('End',end)),labeled('Live refresh',live),el('button',{type:'button',onclick:refresh},'Refresh now'),state),editor.node,save,
    el('p',{class:'muted'},'Live panel edits affect this dashboard only. The prepared run’s final verdict queries remain unchanged. Credentials are resolved from your current Secret Server session.'),results);
  root.dispose=()=>{closed=true;revision++;clearTimeout(timer);editor.dispose();};refresh();return root;
}
