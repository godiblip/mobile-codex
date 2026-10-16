/* Real browser WebRTC/audio with a local peer and explicit Native fixtures, not a model test. */
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
      if(peer){peer.pc.close();await peer.audio.close();}return reply();
    }
    if(action==='live.text')return reply();
    if(action==='live.start'){
      const pc=new RTCPeerConnection(),audio=new AudioContext(),oscillator=audio.createOscillator(),destination=audio.createMediaStreamDestination();
      const gain=audio.createGain();gain.gain.value=.03;oscillator.connect(gain);gain.connect(destination);oscillator.start();
      pc.addTrack(destination.stream.getAudioTracks()[0],destination.stream);
      fx.peers.push({pc,audio,key:args.key});
      await pc.setRemoteDescription({type:'offer',sdp:args.sdp});await pc.setLocalDescription(await pc.createAnswer());
      if(pc.iceGatheringState!=='complete')await new Promise(resolve=>pc.addEventListener('icegatheringstatechange',()=>{if(pc.iceGatheringState==='complete')resolve();}));
      reply({key:args.key,threadId:fx.state.threadId});
      emit('live.event',{key:args.key,method:'thread/realtime/sdp',params:{sdp:pc.localDescription.sdp}});
    }
  };
  window.Native.postMessage(JSON.stringify({id:'live-fixture-state',action:'state',args:{}}));
}
async function main(){
 const server=createReviewServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,args:['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream','--autoplay-policy=no-user-gesture-required']});
 const out=path.join(__dirname,'../artifacts/live-browser');fs.mkdirSync(out,{recursive:true});
 try{
  for(const [width,theme] of [[320,'light'],[390,'light'],[390,'dark'],[1280,'light']]){
   const captureId=width+(theme==='dark'?'-dark':'');
   const context=await browser.newContext({viewport:{width,height:844},permissions:['microphone']});const page=await context.newPage();const errors=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.goto('http://127.0.0.1:'+server.address().port+'/app?language=ko&theme='+theme);
   await page.waitForFunction(()=>window.mobileCodexEvent&&window.Native&&document.getElementById('live-start'));
   await page.evaluate(fixture);await page.waitForFunction(()=>window.__liveFx.state);
   await page.locator('#live-start').click();
   try { await page.waitForFunction(()=>document.querySelector('#live-status').textContent==='듣고 있어요'); }
   catch(error) { console.log(await page.evaluate(()=>({status:document.querySelector('#live-status').textContent,calls:window.__liveFx.calls.map(c=>c.action),peers:window.__liveFx.peers.map(p=>p.pc.connectionState)})),errors); throw error; }
   assert.equal(await page.locator('#live-dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);
   assert.equal(await page.locator('#live-orb').evaluate(el=>el.getBoundingClientRect().width>100),true);
   await page.screenshot({path:path.join(out,`live-${captureId}.png`)});
   await page.locator('#live-mute').click();assert.equal(await page.locator('#live-mute').getAttribute('aria-pressed'),'true');
   await page.locator('#live-text').fill('read this');await page.locator('#live-text-form').evaluate(form=>form.requestSubmit());
   await page.waitForFunction(()=>window.__liveFx.calls.some(c=>c.action==='live.text'&&c.args.text==='read this'));
   await page.evaluate(()=>{const key=window.__liveFx.calls.find(c=>c.action==='live.start').args.key;window.mobileCodexEvent('live.event',{key,method:'thread/realtime/transcript/delta',params:{role:'assistant',delta:'<script>safe text</script>'}});});
   assert.equal(await page.locator('#live-caption-assistant').textContent(),'<script>safe text</script>');
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
   assert.deepEqual(errors,[]);await context.close();console.log(`Live WebRTC/UI ${width}px ${theme} passed`);
  }
 }finally{await browser.close();await new Promise(r=>server.close(r));}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
