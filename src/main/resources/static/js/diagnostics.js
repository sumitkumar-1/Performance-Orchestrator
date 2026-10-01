import {el,select,labeled} from './dom.js';

export function diagnosticsPanel(api,{runId}={}) {
  let trace=null,closed=false,timer,busy=false;
  const rows=el('div',{class:'diagnostic-rows'}),error=el('p',{class:'error',role:'alert',hidden:true});
  const picker=select([['','Select a preparation attempt']],''),status=el('p',{class:'muted',role:'status'});
  const node=el('section',{class:'card diagnostic-panel'},el('h2',{},'Command & API activity'),
    el('p',{class:'muted'},'Safe operation summaries, newest first. Credentials, argument values, HTTP bodies and raw output are excluded. Helm/Git internal network calls are represented by their commands. Up to 500 recent operations shown; 5,000 recorded per attempt.'),
    runId?null:labeled('Recent attempts',picker),el('button',{type:'button',onclick:()=>refresh()},'Refresh activity'),status,error,rows);
  async function attempts(){if(runId)return;try{const list=await api('/real/diagnostics');if(closed)return;picker.replaceChildren(el('option',{value:''},'Select a preparation attempt'),...list.map(t=>el('option',{value:t.id},`${new Date(t.createdAt).toLocaleString()} · ${t.runId?'Run submission':t.planId?'Prepared':'Preparation attempt'} · ${t.id.slice(0,8)}`)));picker.value=trace||'';}catch(reason){fail(reason);}}
  function fail(reason){if(!closed){error.hidden=false;error.textContent=reason.message;}}
  async function refresh(){clearTimeout(timer);if(closed||busy)return;if(!runId&&!trace){status.textContent='Activity appears here when you review a run. Previous attempts remain available above.';return;}busy=true;
    try{const selected=trace;const data=await api(runId?`/real/runs/${encodeURIComponent(runId)}/diagnostics`:`/real/diagnostics/${encodeURIComponent(trace)}`);
      if(closed||selected!==trace)return;error.hidden=true;
      rows.replaceChildren(...data.map(op=>el('article',{class:'run-selection'},el('div',{},el('time',{},new Date(op.startedAt).toLocaleTimeString()),el('pre',{class:'diagnostic-command'},op.summary)),
        el('div',{},el('strong',{},op.outcome||'RUNNING'),el('p',{class:'muted'},op.durationMs==null?'In progress':`${op.durationMs} ms`)))));
      status.textContent=data.length?`${data.length} operations · refreshes every 2 seconds.`:'No recorded operations yet. Older runs created before this update have no diagnostic history.';
    }catch(reason){fail(reason);}finally{busy=false;if(!closed)timer=setTimeout(refresh,2000);}}
  picker.addEventListener('change',()=>{trace=picker.value||null;rows.replaceChildren();refresh();});
  attempts();refresh();
  return {node,start:async()=>{const result=await api('/real/diagnostics',{method:'POST'});if(closed)return result.id;trace=result.id;await attempts();refresh();return trace;},dispose:()=>{closed=true;clearTimeout(timer);}};
}
