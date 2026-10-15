const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');

const root = path.join(__dirname, '../app/src/main/assets/web');
const scopes = ['cloud-platform', 'userinfo.email', 'userinfo.profile', 'cclog', 'experimentsandconfigs', 'aicode']
  .map(scope => 'https://www.googleapis.com/auth/' + scope).concat('openid');
const url = 'https://accounts.google.com/o/oauth2/auth?' + new URLSearchParams({
  client_id:'fixture', redirect_uri:'https://antigravity.google/oauth-callback',
  response_type:'code', scope:scopes.join(' '), state:'fixture-state', code_challenge:'fixture-challenge'
});
const cut = url.indexOf('userinfo.profile') + 'userinf'.length;
const tick = () => new Promise(resolve => setImmediate(resolve));

async function setup(t, provider = 'agy') {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), {runScripts:'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, calls = [];
  w.HTMLDialogElement.prototype.showModal = function() { this.open = true; };
  w.eval(fs.readFileSync(path.join(root, 'agents-ui.js'), 'utf8'));
  const ui = w.MobileCodexAgents.init({
    getState:() => ({agents:[], agentProviders:[{id:provider, state:'login_required'}]}),
    call:async (action, args) => { calls.push({action, args}); return {}; },
    toast:message => assert.equal(message, '로그인 주소를 복사했습니다.'),
    getCharacters:() => [], usageGauge:() => w.document.createElement('div')
  });
  w.document.getElementById('account-' + provider + '-login').click();
  await tick();
  return {w, calls, output:text => ui.event('agents.setup', {provider, kind:'output', text}),
    buttons:() => [...w.document.querySelectorAll('#agent-login-links button')]};
}

for (const separator of ['---', '─'.repeat(500)]) {
  test('complete delimited login URL survives blank lines and indentation: ' + separator.slice(0, 3), async t => {
    const fx = await setup(t);
    fx.output(separator + '\r\n\r\n  ' + url.slice(0, cut));
    assert.equal(fx.buttons().length, 0, 'do not offer the incomplete first chunk');
    fx.output('\r\n\r\n  ' + url.slice(cut) + '\r\n');
    assert.equal(fx.buttons().length, 0, 'wait for the closing separator');
    fx.output(separator + '\r\nPaste the code below.\r\n');
    assert.equal(fx.buttons().length, 2);
    fx.buttons()[0].click(); fx.buttons()[1].click(); await tick();
    const opened = fx.calls.find(c => c.action === 'ui.providerLoginBrowser').args.url;
    const copied = fx.calls.find(c => c.action === 'ui.copyCode').args.code;
    assert.equal(opened, url); assert.equal(copied, url);
    assert.deepEqual(new URL(opened).searchParams.get('scope').split(' '), scopes);
  });
}

test('repeated separators do not become URL content', async t => {
  const fx = await setup(t);
  fx.output('---\n---\n\n' + url.slice(0, cut) + '\n\n' + url.slice(cut) + '\n---\n');
  fx.buttons()[1].click(); await tick();
  assert.equal(fx.calls.find(c => c.action === 'ui.copyCode').args.code, url);
});

test('unframed Claude login links keep their existing copy behavior', async t => {
  const fx = await setup(t, 'claude');
  const claude = 'https://claude.ai/oauth/authorize?client_id=fixture&state=fixture-state';
  fx.output('Open\n' + claude.replace('&state=', '\n&state=') + '\nPaste the code.\n');
  fx.buttons()[1].click(); await tick();
  assert.equal(fx.calls.find(c => c.action === 'ui.copyCode').args.code, claude);
});
