const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require('jsdom');
const root=path.join(__dirname,'../app/src/main/assets/web');
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('participants show only their actual approval modes and independent running states',async t=>{
 const dom=new JSDOM(fs.readFileSync(path.join(root,'index.html'),'utf8'),{runScripts:'outside-only'});
 t.after(()=>dom.window.close());const w=dom.window,d=w.document,calls=[];
 const state={approvalMode:'auto-review',agents:[
  {id:'claude',provider:'claude',name:'Claude',model:'m',mode:'write',approvalMode:'ask'},
  {id:'gemini',provider:'agy',name:'Gemini',model:'g',mode:'write',approvalMode:'ask'}],
  agentProviders:[{id:'claude',state:'ready',nativeAutoReview:true,models:[{id:'m'}]},
   {id:'agy',state:'ready',models:[{id:'g'}]}],
  agentRun:{active:['codex','claude','gemini'],states:{codex:'responding',claude:'working',gemini:'waiting'},queue:[]}};
 w.eval(fs.readFileSync(path.join(root,'agents-ui.js'),'utf8'));
 const ui=w.MobileCodexAgents.init({getState:()=>state,call:async(action,args)=>{calls.push({action,args});return {};},
  getCharacters:()=>({packs:[]}),closeFilePanel:()=>{},usageGauge:()=>d.createElement('div')});
 d.querySelector('#agents-toggle').click();
 for(const id of ['codex','claude','gemini'])d.querySelector('.agent-card[data-agent='+id+'] .agent-row').click();
 const options=label=>[...d.querySelector('select[aria-label="'+label+'"]').options].map(o=>o.value);
 assert.deepEqual(options('Claude 승인 방식'),['ask','auto-review','allow-all']);
 assert.deepEqual(options('Gemini 승인 방식'),['ask','allow-all']);
 assert.deepEqual(options('Codex 승인 방식'),['ask','auto-review','allow-all']);
 assert.equal(d.querySelector('[data-agent=claude].agent-card').dataset.status,'working');
 assert.equal(d.querySelector('[data-agent=gemini].agent-card').dataset.status,'waiting');
 const approval=d.querySelector('select[aria-label="Claude 승인 방식"]');
 approval.value='auto-review';approval.dispatchEvent(new w.Event('change'));await tick();
 assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1))),{action:'chat.agents.set',args:{agents:[{id:'claude',approvalMode:'auto-review'}]}});
 state.agents[0].approvalMode='auto-review';
 state.agentProviders[0].autoReviewUnavailableModels=['m'];ui.render();
 assert.deepEqual(options('Claude 승인 방식'),['','ask','allow-all']);
 assert.equal(d.querySelector('select[aria-label="Claude 승인 방식"]').value,'');
 assert.equal(state.agents[0].approvalMode,'auto-review','do not silently save a manual fallback');
 state.agents[1].mode='read';ui.render();
 assert.equal(d.querySelector('select[aria-label="Gemini 승인 방식"]').disabled,true);
});
