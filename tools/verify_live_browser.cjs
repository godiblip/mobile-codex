/* UI/bridge fixtures only. Native WebRTC/audio requires Android tests and a real device. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {createReviewServer}=require('./ui_review_server.cjs');
async function fixture(){
  const fx=window.__liveFx={calls:[],peers:[],state:null,deny:false,hold:false};
  const emit=window.mobileCodexEvent;
  window.mobileCodexEvent=(name,data)=>{if(name==='state')fx.state=data;if(name==='response'&&data?.result?.messages)fx.state=data.result;return emit(name,data);};
  const native=window.Native.postMessage.bind(window.Native);
  window.Native.postMessage=async raw=>{
    const request=JSON.parse(raw),{action,args,id}=request;
    if(!action.startsWith('live.'))return native(raw);
    fx.calls.push(request);
    const reply=(result={},error)=>emit('response',{id,result,error});
    if(action==='live.voices')return reply({voices:{v1:['cove','sol'],v3:['juniper','maple','spruce','ember','vale','breeze','arbor','sol','cove','aube','bubbie','haetsal','hanul','himari','leher','miko','neer','porto','rindo','rio','rivage','selva','sonna','tinta','viola'],defaultV1:'cove'}});
    if(action==='live.microphone'){
      if(fx.deny)return reply(null,'Microphone permission denied');
      if(fx.hold){fx.releaseMicrophone=()=>reply();return;}
      return reply();
    }
    if(action==='live.stop'){
      const peer=fx.peers.find(peer=>peer.key===args.key);
      if(peer)peer.pc.connectionState='closed';return reply();
    }
    if(action==='live.status')return reply(fx.peers.find(p=>p.pc.connectionState!=='closed')?.state || {key:''});
    if(action==='live.mute'){
      const peer=fx.peers.find(peer=>peer.key===args.key);
      if(!peer)return reply(null,'Voice ended');
      peer.state.muted=args.muted;emit('live.audio',peer.state);return reply(peer.state);
    }
    if(action==='live.text')return reply();
    if(action==='live.start'){
      const state={key:args.key,threadId:fx.state.threadId,phase:'connected',connected:true,muted:false,input:.2,output:.4};
      fx.peers.push({pc:{connectionState:'connected'},state,key:args.key});
      reply(state);emit('live.audio',state);
    }
  };
  window.Native.postMessage(JSON.stringify({id:'live-fixture-state',action:'state',args:{}}));
}
async function main(){
 const server=createReviewServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true});
 const out=path.join(__dirname,'../artifacts/live-browser');fs.mkdirSync(out,{recursive:true});
 try{
  for(const [width,theme] of [[320,'light'],[390,'light'],[390,'dark'],[1280,'light']]){
   const captureId=width+(theme==='dark'?'-dark':'');
   const context=await browser.newContext({viewport:{width,height:844}});const page=await context.newPage();const errors=[];
   await page.addInitScript(()=>{
     window.RTCPeerConnection=class {constructor(){throw Error('WebView must not own a peer');}};
     navigator.mediaDevices.getUserMedia=()=>{throw Error('WebView must not capture audio');};
     window.Audio=class {constructor(){throw Error('WebView must not play audio');}};
   });
   page.on('pageerror',e=>errors.push(e.message));
   await page.goto('http://127.0.0.1:'+server.address().port+'/app?language=ko&theme='+theme);
   await page.waitForFunction(()=>window.mobileCodexEvent&&window.Native&&document.getElementById('live-start'));
   await page.evaluate(fixture);await page.waitForFunction(()=>window.__liveFx.state);
   await page.locator('#live-start').click();
   try { await page.waitForFunction(()=>document.querySelector('#live-status').textContent==='듣고 있어요'); }
   catch(error) { console.log(await page.evaluate(()=>({status:document.querySelector('#live-status').textContent,calls:window.__liveFx.calls.map(c=>c.action),peers:window.__liveFx.peers.map(p=>p.pc.connectionState)})),errors); throw error; }
   assert.equal(await page.locator('#live-dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);
   assert.equal(await page.locator('#live-orb').evaluate(el=>el.getBoundingClientRect().width>100),true);
   // Android owns background microphone eligibility. This verifies the web side
   // keeps the existing peer/session across hide/show and still accepts events.
   const hiddenKey=await page.evaluate(()=>window.__liveFx.calls.find(c=>c.action==='live.start').args.key);
   await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
   await page.evaluate(()=>{const key=window.__liveFx.peers[0].key;window.mobileCodexEvent('live.event',{key,method:'thread/realtime/transcript/done',params:{role:'assistant',text:'background reply'}});});
   await page.waitForFunction(()=>document.querySelector('#live-caption-assistant').textContent==='background reply');
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.filter(c=>c.action==='live.stop').length),0);
   assert.equal(await page.evaluate(()=>window.__liveFx.peers[0].pc.connectionState),'connected');
   await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));});
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.find(c=>c.action==='live.start').args.key),hiddenKey);
   await page.evaluate(()=>window.mobileCodexEvent('live.interrupted',{key:'stale-call'}));
   assert.equal(await page.locator('#live-status').textContent(),'듣고 있어요');
   await page.screenshot({path:path.join(out,`live-${captureId}.png`)});
   await page.locator('#live-mute').click();assert.equal(await page.locator('#live-mute').getAttribute('aria-pressed'),'true');
   await page.locator('#live-text').fill('read this');await page.locator('#live-text-form').evaluate(form=>form.requestSubmit());
   await page.waitForFunction(()=>window.__liveFx.calls.some(c=>c.action==='live.text'&&c.args.text==='read this'));
   await page.evaluate(()=>{const key=window.__liveFx.calls.find(c=>c.action==='live.start').args.key;window.mobileCodexEvent('live.event',{key,method:'thread/realtime/transcript/delta',params:{role:'assistant',delta:'<script>safe text</script>'}});});
   assert.equal(await page.locator('#live-caption-assistant').textContent(),'background reply<script>safe text</script>');
   await page.locator('#live-settings-open').click();
   assert.equal(await page.locator('#live-voice').isVisible(),false);
   assert.equal(await page.locator('#live-voice-picker').evaluate(el=>el.open),false);
   await page.screenshot({path:path.join(out,`settings-collapsed-${captureId}.png`)});
   await page.locator('#live-voice-toggle').click();
   assert.equal(await page.locator('.live-voice-choice').count(),25);
   assert.deepEqual(await page.locator('.live-voice-group').allTextContents(),['한국어','미국 영어','독일어','기타']);
   assert.equal(await page.locator('.live-voice-choice').filter({has:page.locator('input[value="haetsal"]')}).textContent(),'햇살');
   assert.equal(await page.locator('#live-voice-list').evaluate(el=>el.scrollHeight>el.clientHeight&&el.scrollWidth<=el.clientWidth+1),true);
   await page.locator('.live-voice-choice').filter({has:page.locator('input[value="hanul"]')}).click();
   await page.locator('input[name="live-voice-choice"][value="hanul"]').press('ArrowRight');
   assert.equal(await page.locator('#live-voice').inputValue(),'haetsal');
   await page.locator('input[name="live-voice-choice"][value="haetsal"]').press('ArrowLeft');
   assert.equal(await page.locator('#live-voice-name').textContent(),'한울');
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.filter(c=>c.action==='live.start').length),1,'Choosing a voice waits for Apply');
   await page.locator('#live-language').selectOption('ko');
   await page.waitForTimeout(200);
   await page.screenshot({path:path.join(out,`settings-${captureId}.png`)});
   await page.locator('#live-settings-apply').click();await page.waitForFunction(()=>window.__liveFx.calls.filter(c=>c.action==='live.start').length===2&&document.querySelector('#live-status').textContent==='듣고 있어요');
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.filter(c=>c.action==='live.start')[1].args.voice),'hanul');
   await page.locator('#live-settings-open').click();
   assert.equal(await page.locator('#live-voice-name').textContent(),'한울');
   assert.equal(await page.locator('#live-voice-picker').evaluate(el=>el.open),false);
   await page.locator('#live-settings-back').click();
   // Notification/focus-loss events also close a hidden call and its audio.
   await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));const key=window.__liveFx.peers.at(-1).key;window.mobileCodexEvent('live.interrupted',{key});});
   await page.waitForFunction(()=>window.__liveFx.peers.every(peer=>peer.pc.connectionState==='closed'));
   await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));});
   await page.locator('#live-end').click();await page.waitForFunction(()=>!document.querySelector('#live-dialog').open);
   assert.equal(await page.evaluate(()=>window.__liveFx.peers.every(peer=>peer.pc.connectionState==='closed')),true);
   // Permission denial and cancellation while permission is pending never open a backend call.
   await page.evaluate(()=>window.__liveFx.deny=true);await page.locator('#live-start').click();
   await page.waitForFunction(()=>document.querySelector('#live-status').textContent.includes('denied'));
   await page.locator('#live-end').click();
   await page.evaluate(()=>{window.__liveFx.deny=false;window.__liveFx.hold=true;});await page.locator('#live-start').click();
   await page.waitForFunction(()=>window.__liveFx.releaseMicrophone);await page.locator('#live-end').click();await page.evaluate(()=>window.__liveFx.releaseMicrophone());
   await page.waitForFunction(()=>!document.querySelector('#live-start').disabled);
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.filter(c=>c.action==='live.start').length),2);
   // A recreated page restores the already-running native call without starting another one.
   await page.evaluate(()=>{
     const fx=window.__liveFx,state={key:'restored-native-call',phase:'connected',connected:true,muted:true,
       threadId:fx.state.threadId,input:0,output:.3,userCaption:'saved user',assistantCaption:'saved reply'};
     fx.peers.push({key:state.key,pc:{connectionState:'connected'},state});
     fx.state.live={key:state.key};window.mobileCodexEvent('state',fx.state);
   });
   await page.waitForFunction(()=>document.querySelector('#live-caption-assistant').textContent==='saved reply');
   assert.equal(await page.locator('#live-mute').getAttribute('aria-pressed'),'true');
   assert.equal(await page.evaluate(()=>window.__liveFx.calls.filter(c=>c.action==='live.start').length),2);
   await page.locator('#live-end').click();
   assert.deepEqual(errors,[]);await context.close();console.log(`Live native bridge/UI ${width}px ${theme} passed`);
  }
 }finally{await browser.close();await new Promise(r=>server.close(r));}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
