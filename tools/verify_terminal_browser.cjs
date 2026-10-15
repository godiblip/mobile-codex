/* UI transcript + real child stdin/stdout. No provider credentials or OAuth. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const {createReviewServer}=require('./ui_review_server.cjs');
const out=path.join(__dirname,'../artifacts/terminal-browser');
async function main(){
 fs.mkdirSync(out,{recursive:true});
 const server=createReviewServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true});const results=[];
 try{for(const width of [320,390,1280]){
  const context=await browser.newContext({viewport:{width,height:900}}),page=await context.newPage();
  const errors=[],calls=[];page.on('pageerror',e=>errors.push(e.message));
  let child,sequence=0,state={id:'',revision:0,running:false,stopping:false,output:'',exitCode:null};
  const emit=()=>page.evaluate(s=>window.mobileCodexEvent('terminal.state',s),{...state}).catch(()=>{});
  const script=`let step=0;process.stdout.write('Enter authentication code:\\n');require('node:readline').createInterface({input:process.stdin}).on('line',line=>{if(step===0){if(line!=='fixture-private-code')process.exit(21);step++;process.stdout.write('Code accepted. Press Enter:\\n');}else if(line===''){process.stdout.write('LOGIN_FIXTURE_COMPLETE\\n',()=>process.exit(0));}else process.exit(22);});`;
  await page.exposeFunction('terminalFixture',async m=>{
   calls.push({action:m.action,id:m.args?.id});
   if(m.action==='terminal.state')return {...state};
   if(m.action==='terminal.run'){
    assert.equal(state.running,false);state={id:'fixture-'+(++sequence),revision:state.revision+1,running:true,stopping:false,output:'',exitCode:null};
    child=spawn(process.execPath,['-e',script],{stdio:['pipe','pipe','pipe'],windowsHide:true});
    child.stdout.on('data',chunk=>{state.output+=chunk.toString();state.revision++;emit();});
    child.on('exit',code=>{state.running=false;state.stopping=false;state.exitCode=code??-1;state.revision++;emit();});
    return {...state};
   }
   if(m.args.id!==state.id||!state.running)throw new Error('입력을 받을 터미널이 종료되거나 변경되었습니다.');
   if(m.action==='terminal.input'){child.stdin.write(m.args.text.replace(/[\r\n]+$/,'')+'\n');return {ok:true};}
   if(m.action==='terminal.stop'){state.stopping=true;state.revision++;child.kill();return {...state};}
   throw new Error('Unexpected terminal action');
  });
  try{
   await page.goto('http://127.0.0.1:'+server.address().port+'/app?language=ko');
   await page.evaluate(()=>{const original=window.Native.postMessage.bind(window.Native);window.Native.postMessage=raw=>{const m=JSON.parse(raw);if(!m.action.startsWith('terminal.'))return original(raw);window.terminalFixture(m).then(result=>window.mobileCodexEvent('response',{id:m.id,result}),error=>window.mobileCodexEvent('response',{id:m.id,error:error.message}));};});
   const open=async()=>{if(width<760)await page.locator('.topbar .sidebar-toggle').click();await page.locator('#show-tools').click();await page.locator('#show-terminal').click();};
   await open();await page.locator('#terminal-command').fill('fixture login');await page.locator('#terminal-submit').click();
   await page.waitForFunction(()=>document.querySelector('#terminal-output').textContent.includes('Enter authentication code'));
   assert.equal(await page.locator('#terminal-submit').textContent(),'입력 전송');
   assert.equal(await page.locator('#terminal-command').getAttribute('type'),'password');
   const id=state.id;await page.locator('#terminal-command').fill('fixture-private-code');await page.locator('#terminal-submit').click();
   await page.waitForFunction(()=>document.querySelector('#terminal-output').textContent.includes('Code accepted'));
   assert.equal(calls.filter(c=>c.action==='terminal.run').length,1);assert.equal(calls.find(c=>c.action==='terminal.input').id,id);
   assert.ok(!(await page.locator('#terminal-output').textContent()).includes('fixture-private-code'));
   await page.screenshot({path:path.join(out,'input-'+width+'.png')});
   const box=await page.locator('#terminal-form').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width+1);
   assert.equal(await page.locator('#terminal-form').evaluate(el=>el.scrollWidth>el.clientWidth+1),false);
   await page.locator('#terminal-dialog [data-close]').click();await open();
   assert.equal(state.id,id);assert.ok((await page.locator('#terminal-output').textContent()).includes('Code accepted'));
   await page.locator('#terminal-command').fill('');await page.locator('#terminal-submit').click();
   await page.waitForFunction(()=>document.querySelector('#terminal-output').textContent.includes('LOGIN_FIXTURE_COMPLETE')&&document.querySelector('#terminal-submit').textContent==='실행');
   assert.equal(state.exitCode,0);assert.equal(calls.filter(c=>c.action==='terminal.input').length,2);
   await page.locator('#terminal-command').fill('fixture login again');await page.locator('#terminal-submit').click();
   await page.waitForFunction(()=>document.querySelector('#terminal-output').textContent.includes('Enter authentication code'));
   await page.locator('#terminal-stop').click();await page.waitForFunction(()=>document.querySelector('#terminal-submit').textContent==='실행');
   assert.equal(state.running,false);assert.equal(await page.locator('#terminal-stop').isDisabled(),true);
   assert.equal(await page.locator('#mode-chat, #mode-codex, #composer-consult-pro, [data-settings-tab=sync]').count(),0);
   assert.deepEqual(errors,[]);results.push({width,inputs:2,stop:true,reopen:true,overflow:false});
  }finally{child?.kill();await context.close();}
 }
 console.log(JSON.stringify(results,null,2));
 }finally{await browser.close();await new Promise(r=>server.close(r));}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
