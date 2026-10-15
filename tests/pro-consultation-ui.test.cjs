const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');

const webRoot = path.join(__dirname, '../app/src/main/assets/web');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
const tick = () => new Promise(resolve => setTimeout(resolve, 10));
const codexModel = {
  id:'codex-model', model:'codex-model', displayName:'Codex model', isDefault:true,
  supportedReasoningEfforts:[{reasoningEffort:'medium'}, {reasoningEffort:'high'}],
  serviceTiers:[{id:'priority', name:'Fast'}]
};
const profile = (key, email = 'me@example.test') => ({
  account:{type:'chatgpt', email}, accounts:[{key, email, active:true}]
});

function setup(overrides = {}, options = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8'), {
    url:'https://appassets.androidplatform.net/index.html', runScripts:'outside-only'
  });
  opened.push(dom);
  const w = dom.window, calls = [];
  const snapshot = {
    ready:true, busy:false, proBusy:false, permissions:'workspace-write', approvalMode:'allow-all',
    status:'연결됨', threadId:'thread-a', cwd:'/test/project',
    workspace:{selected:true, key:'project-a', name:'Project A', available:true},
    models:[codexModel], messages:[], sessions:[], ...profile('profile-a'), ...options.snapshot
  };
  w.matchMedia = () => ({matches:false, addEventListener(){}});
  w.HTMLDialogElement.prototype.showModal = function(){this.setAttribute('open', '');};
  w.HTMLDialogElement.prototype.close = function(){this.removeAttribute('open');this.dispatchEvent(new w.Event('close'));};
  w.confirm = () => true;
  for (const [key, value] of Object.entries(options.storage || {})) w.localStorage.setItem(key, value);
  w.Native = {
    locale:() => JSON.stringify({choice:'ko', systemLanguage:'ko'}),
    textSize:() => JSON.stringify({percent:100}),
    postMessage(raw) {
      const message = JSON.parse(raw); calls.push(message);
      queueMicrotask(async () => {
        try {
          const result = overrides[message.action] ? await overrides[message.action](message)
            : message.action === 'state' ? snapshot
              : message.action === 'rpc' ? {data:[], marketplaces:[]} : {};
          w.mobileCodexEvent('response', {id:message.id, result});
        } catch (error) { w.mobileCodexEvent('response', {id:message.id, error:error.message}); }
      });
    }
  };
  for (const file of ['translations.js', 'locale.js', 'ui-core.js', 'app.js'])
    w.eval(fs.readFileSync(path.join(webRoot, file), 'utf8'));
  return {w, calls, snapshot};
}

function type(w, text) {
  const field = w.document.getElementById('prompt');
  field.value = text; field.dispatchEvent(new w.Event('input')); return field;
}
function submit(w) {
  w.document.getElementById('composer').dispatchEvent(new w.Event('submit', {cancelable:true}));
}
function key(w, name) {
  w.document.activeElement.dispatchEvent(new w.KeyboardEvent('keydown', {key:name, bubbles:true, cancelable:true}));
}
function selectConsultation(w) {
  w.document.getElementById('add-attachment').click();
  w.document.getElementById('composer-consult-pro').click();
}
function chip(w) { return w.document.querySelector('.pro-consultation-chip'); }
function chatCalls(calls) { return calls.filter(call => /^chat\.(send|steer|pro\.)/.test(call.action)); }
function storageOf(w) { return Object.fromEntries(Object.keys(w.localStorage).map(key => [key, w.localStorage.getItem(key)])); }

test('retired Chat and Pro controls never return through saved consultation preferences', async () => {
  const {w,calls}=setup();await tick();const d=w.document;
  for(const id of ['mode-chat','mode-codex','composer-consult-pro','chat-account-login']) assert.equal(d.getElementById(id),null);
  const scope=Object.keys(w.localStorage).find(k=>k.includes('draft'));
  w.localStorage.setItem((scope||'draft')+':context:consult-pro:"profile-a"','true');
  d.querySelector('[data-settings-tab=account]').click();await tick();
  type(w,'Codex only');submit(w);await tick();
  assert.equal(chatCalls(calls).at(-1).args.consultPro,undefined);
  assert.equal(calls.some(c=>c.action.startsWith('chat.pro.')||c.action==='ui.chat.open'||c.action==='ui.chatLogin'),false);
});

test('models and reasoning show and send exact choices instead of unnamed defaults', async () => {
  const {w,calls}=setup({},{snapshot:{models:[{...codexModel,defaultReasoningEffort:'high'}]}});await tick();const d=w.document;
  assert.equal(d.getElementById('model').value,'codex-model');assert.equal(d.getElementById('effort').value,'high');
  assert.doesNotMatch(d.getElementById('model').textContent,/기본/);assert.doesNotMatch(d.getElementById('effort').textContent,/기본/);
  type(w,'Explicit settings');submit(w);await tick();
  assert.equal(chatCalls(calls).at(-1).args.model,'codex-model');assert.equal(chatCalls(calls).at(-1).args.effort,'high');
});

test('missing model or reasoning data is visible and voice has no send reminder', async () => {
  const {w,calls}=setup({},{snapshot:{models:[]}});await tick();const d=w.document;
  assert.equal(d.getElementById('model').value,'');assert.match(d.getElementById('model').textContent,/조회 필요/);
  assert.equal(d.getElementById('effort').disabled,true);assert.match(d.getElementById('effort').textContent,/정보 없음/);
  assert.doesNotMatch(d.getElementById('dictation-panel')?.textContent||'',/확인.*보내/);
  assert.equal(d.getElementById('voice-input').title,'음성 입력');
  type(w,'Keep draft until models are known');submit(w);await tick();
  assert.equal(chatCalls(calls).length,0);assert.equal(d.getElementById('prompt').value,'Keep draft until models are known');
});

test('model refresh preserves an explicit missing selection without silently sending another model', async () => {
  const {w,calls,snapshot}=setup();await tick();const d=w.document;
  d.getElementById('model').dispatchEvent(new w.Event('change'));await tick();
  w.mobileCodexEvent('state',{...snapshot,models:[{id:'replacement',isDefault:true}]});
  assert.equal(d.getElementById('model').value,'codex-model');assert.match(d.getElementById('model-list').textContent,/목록 확인 필요/);
  type(w,'Preserve my choice');submit(w);await tick();assert.equal(chatCalls(calls).length,0);
  w.mobileCodexEvent('state',snapshot);submit(w);await tick();assert.equal(chatCalls(calls).at(-1).args.model,'codex-model');
});

test('consultation answers have a safe reply card, optional prompt details and reply-only copy', async () => {
  const copied = [];
  const {w, snapshot} = setup(); await tick();
  Object.defineProperty(w.navigator, 'clipboard', {value:{writeText:async text => {copied.push(text);}}});
  const answer = {
    id:'consult', kind:'proConsultation', role:'assistant', status:'completed',
    text:'이 구현의 경계를 확인했습니다. <img src=x onerror=alert(1)>', prompt:'비공개 검토 질문 <script>window.hacked=true</script>', createdAt:Date.UTC(2026,8,29,12,34)
  };
  w.mobileCodexEvent('state', {...snapshot, messages:[answer]});
  const d = w.document, card = d.querySelector('[data-id="consult"]');
  assert.match(card.textContent, /Pro 검토 답변/); assert.match(card.textContent, /구현의 경계/);
  assert.equal(card.querySelectorAll('script, img:not(.chat-character)').length, 0);
  const details = card.querySelector('details'); assert.ok(details); assert.equal(details.open, false);
  assert.match(details.textContent, /비공개 검토 질문/);
  card.querySelector('.msg-copy-btn').click(); await tick(); assert.deepEqual(copied, [answer.text]);
  w.mobileCodexEvent('state', {...snapshot, messages:[{...answer, prompt:''}]});
  assert.equal(d.querySelector('[data-id="consult"]'), card, 'The same message keeps its identity');
  assert.equal(card.querySelector('details'), null, 'Removing prompt metadata removes stale details');
});

test('legacy Pro labels remain readable beside the new consultation answer card', async () => {
  const {w, snapshot} = setup(); await tick();
  w.mobileCodexEvent('state', {...snapshot, messages:[
    {id:'legacy-user', role:'user', text:'기존 검토', backend:'chatgpt-web', source:'ChatGPT Pro', displayModel:'GPT-6-Pro', status:'uncertain'},
    {id:'legacy-answer', role:'assistant', text:'기존 답변', backend:'chatgpt-web', source:'ChatGPT Pro', status:'completed'},
    {id:'new-answer', kind:'proConsultation', role:'assistant', text:'새 검토 답변', status:'completed'}
  ]});
  const d = w.document;
  assert.match(d.querySelector('[data-id="legacy-user"]').textContent, /GPT-6-Pro/);
  assert.match(d.querySelector('[data-id="legacy-user"]').textContent, /다시 보내기 전에/);
  assert.match(d.querySelector('[data-id="legacy-answer"]').textContent, /ChatGPT Pro/);
  assert.match(d.querySelector('[data-id="new-answer"]').textContent, /Pro 검토 답변/);
});
