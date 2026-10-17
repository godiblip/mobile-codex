/* The native foreground service owns audio; this page only displays and controls it. */
(() => {
  'use strict';
  function voicesFrom(result) {
    const catalog = result?.voices || {};
    const supported = Array.isArray(catalog.v3) && catalog.v3.length ? catalog.v3 : catalog.v1 || [];
    const ids = [...new Set(supported.filter(v => typeof v === 'string' && /^[a-z0-9_-]+$/i.test(v)))];
    return {ids, defaultVoice: ids.includes(catalog.defaultV1) ? catalog.defaultV1 : ids[0] || ''};
  }
  function voiceName(id) {
    return {hanul:'한울', haetsal:'햇살'}[id] || id[0].toUpperCase() + id.slice(1);
  }
  function voiceGroups(ids) {
    // Only classify voices whose language group is confirmed in the ChatGPT picker.
    const known = [
      ['한국어', ['hanul', 'haetsal']],
      ['미국 영어', ['sol', 'ember', 'maple', 'bubbie', 'breeze', 'spruce', 'juniper', 'cove']],
      ['독일어', ['miko']],
    ];
    const classified = new Set(known.flatMap(([, values]) => values));
    return [...known.map(([label, values]) => ({label, ids:values.filter(id => ids.includes(id))})),
      {label:'기타', ids:ids.filter(id => !classified.has(id))}].filter(group => group.ids.length);
  }
  function create({call, getState, getOptions, show, close, t, errorText, changed}) {
    const $ = id => document.getElementById(id);
    let current = null, voices = [], loading = false, settings = false;
    let dismissedKey = '', restoringKey = '';
    let voice = localStorage.getItem('live-voice') || '', language = localStorage.getItem('live-language') || 'auto';
    const active = () => !!current;
    const status = text => { $('live-status').textContent = t(text); };
    function update() {
      const state = getState();
      $('live-task').hidden = !state.busy || !state.turnId;
      $('live-start').disabled = loading || !!current || !!state.busy || !!state.proBusy || !state.account?.type;
      $('live-mute').disabled = !current?.connected;
      $('live-text-send').disabled = !current?.connected || !!current?.sending || !$('live-text').value.trim();
      $('live-mute').setAttribute('aria-pressed', String(!!current?.muted));
      $('live-mute').setAttribute('aria-label', t(current?.muted ? '마이크 켜기' : '마이크 끄기'));
      $('live-mute').classList.toggle('muted', !!current?.muted);
      $('live-retry').hidden = !!current;
      if (current?.localThread && state.threadId !== current.localThread) end();
      if (!current && !loading && state.live?.key && state.live.key !== dismissedKey && state.live.key !== restoringKey) {
        restoringKey = state.live.key;
        call('live.status').then(applyNativeState).catch(() => {});
      }
    }
    function setSettings(value) {
      settings = value; $('live-settings-panel').hidden = !value; $('live-conversation').hidden = value;
      if (!value) $('live-voice-picker').open = false;
      $('live-settings-open').hidden = value; $('live-settings-back').hidden = !value;
      $('live-title').textContent = t(value ? '음성 설정' : 'Codex Live');
    }
    function syncVoiceChoice() {
      $('live-voice-name').textContent = $('live-voice').selectedOptions[0]?.textContent || '';
      $('live-voice-list').querySelectorAll('input').forEach(input => { input.checked = input.value === $('live-voice').value; });
    }
    async function loadVoices() {
      const catalog = voicesFrom(await call('live.voices'));
      if (!catalog.ids.length) throw new Error(t('사용 가능한 음성이 없습니다.'));
      voices = catalog.ids;
      if (!voices.includes(voice)) voice = catalog.defaultVoice;
      $('live-voice').replaceChildren(...voices.map(id => new Option(voiceName(id), id)));
      $('live-voice').value = voice;
      $('live-voice-list').replaceChildren(...voiceGroups(voices).flatMap(group => {
        const heading = document.createElement('div');
        heading.className = 'live-voice-group'; heading.textContent = t(group.label);
        return [heading, ...group.ids.map(id => {
        const label = document.createElement('label'), input = document.createElement('input'), name = document.createElement('span');
        label.className = 'live-voice-choice'; input.type = 'radio'; input.name = 'live-voice-choice'; input.value = id;
        name.textContent = voiceName(id);
        input.addEventListener('change', () => { if (input.checked) { $('live-voice').value = id; syncVoiceChoice(); } });
        label.append(input, name); return label;
        })];
      }));
      syncVoiceChoice();
      $('live-language').value = ['auto','ko','en'].includes(language) ? language : 'auto';
    }
    function release(session) {
      cancelAnimationFrame(session.frame);
      $('live-orb').style.transform = '';
    }
    async function end({hide = true, message = ''} = {}) {
      const previous = current; current = null;
      if (previous) {
        dismissedKey = previous.key;
        release(previous);
        // A stale stop is keyed and cannot terminate a replacement session.
        await call('live.stop', {key:previous.key}).catch(() => {});
      }
      if (current) return;
      if (hide) close('live-dialog');
      else { status(message || '음성 대화가 종료되었습니다.'); $('live-retry').hidden = false; }
      update(); changed();
    }
    function fail(session, error) {
      if (current !== session) return;
      end({hide:false, message:errorText(error, t('음성 연결에 실패했습니다. 다시 연결해 주세요.'))});
    }
    function applyNativeState(data) {
      if (!data?.key || data.key === dismissedKey || (current && current.key !== data.key)) return;
      if (data.phase === 'ended') {
        if (current?.key === data.key) end({hide:false, message:data.error || '음성 대화가 종료되었습니다.'});
        return;
      }
      if (!current) {
        if (getState().live?.key !== data.key) return;
        current = {key:data.key, scale:1, velocity:0}; show('live-dialog'); setSettings(false);
        if (!document.hidden) animate(current);
      }
      current.connected = !!data.connected; current.muted = !!data.muted;
      current.input = Number(data.input) || 0; current.output = Number(data.output) || 0;
      if (data.threadId) current.localThread = data.threadId;
      if (data.userCaption !== undefined) $('live-caption-user').textContent = data.userCaption;
      if (data.assistantCaption !== undefined) $('live-caption-assistant').textContent = data.assistantCaption;
      status(current.connected ? (current.muted ? '마이크 꺼짐' : '듣고 있어요') : '연결 중…');
      update(); changed();
    }
    function animate(session) {
      if (current !== session) return;
      const input = session.muted ? 0 : session.input || 0, output = session.output || 0;
      const target = 1 + Math.max(input, output) * .2;
      session.velocity = (session.velocity + (target - session.scale) * .24) * .66;
      session.scale += session.velocity;
      $('live-orb').style.transform = `scale(${session.scale})`;
      $('live-orb').dataset.speaker = output > .05 ? 'assistant' : input > .05 ? 'user' : 'idle';
      session.frame = requestAnimationFrame(() => animate(session));
    }
    async function start() {
      if (current || loading) return;
      loading = true; setSettings(false); show('live-dialog'); status('연결 중…');
      $('live-caption-user').textContent = ''; $('live-caption-assistant').textContent = '';
      const session = {key:crypto.randomUUID(), scale:1, velocity:0, muted:false, connected:false};
      current = session; update(); changed();
      const state = getState(), expectedThreadId = state.threadId || '', workspaceKey = state.workspace?.key || '', options = getOptions();
      try {
        await loadVoices(); if (current !== session) return;
        await call('live.microphone', {key:session.key}); if (current !== session) return;
        animate(session);
        const result = await call('live.start', {...options, key:session.key, voice, language, expectedThreadId, workspaceKey});
        if (current !== session) { await call('live.stop', {key:session.key}); return; }
        session.localThread = result.threadId; applyNativeState(result);
      } catch (error) { fail(session, error); }
      finally { loading = false; update(); }
    }
    async function event(name, data) {
      if (name === 'live.audio') { applyNativeState(data); return; }
      if (name === 'live.interrupted') {
        if (!data?.key || data.key === current?.key) await end({hide:false, message:'음성 대화가 중단되었습니다.'});
        return;
      }
      if (name !== 'live.event') return;
      const session = current;
      if (!session || session.key !== data.key) return;
      const params = data.params || {};
      if (data.method === 'thread/realtime/error') fail(session, new Error(params.message));
      else if (data.method === 'thread/realtime/closed') end({hide:false, message:'음성 대화가 종료되었습니다.'});
      else if (data.method === 'thread/realtime/transcript/delta' || data.method === 'thread/realtime/transcript/done') {
        const role = params.role === 'user' ? 'user' : 'assistant', element = $('live-caption-' + role);
        element.textContent = (data.method.endsWith('/done') ? params.text || '' : element.textContent + (params.delta || '')).slice(-1600);
      }
    }
    // Avoid moving the compact composer button between pointer-down and click.
    $('live-start').addEventListener('pointerdown', e => e.preventDefault());
    $('live-start').addEventListener('click', start);
    $('live-retry').addEventListener('click', start);
    $('live-end').addEventListener('click', () => end());
    $('live-task-stop').addEventListener('click', () => call('chat.stop').catch(error => status(errorText(error))));
    $('live-mute').addEventListener('click', async () => {
      const session = current; if (!session?.connected) return;
      try { applyNativeState(await call('live.mute', {key:session.key, muted:!session.muted})); }
      catch (error) { if (current === session) status(errorText(error)); }
    });
    $('live-text').addEventListener('input', update);
    $('live-text-form').addEventListener('submit', async e => {
      e.preventDefault(); const session = current, text = $('live-text').value.trim();
      if (!session?.connected || session.sending || !text) return;
      session.sending = true;
      $('live-text-send').disabled = true;
      try { await call('live.text', {key:session.key, text}); if ($('live-text').value.trim() === text) $('live-text').value = ''; }
      catch (error) { status(errorText(error)); }
      finally { session.sending = false; update(); }
    });
    $('live-settings-open').addEventListener('click', () => setSettings(true));
    $('live-settings-back').addEventListener('click', () => setSettings(false));
    $('live-voice').addEventListener('change', syncVoiceChoice);
    $('live-voice-picker').addEventListener('toggle', () => {
      if ($('live-voice-picker').open) $('live-voice-list').querySelector('input:checked')?.closest('label').scrollIntoView({block:'nearest'});
    });
    $('live-settings-apply').addEventListener('click', async () => {
      const nextVoice = $('live-voice').value, nextLanguage = $('live-language').value;
      if (!voices.includes(nextVoice)) return;
      const restart = current && (nextVoice !== voice || nextLanguage !== language);
      voice = nextVoice; language = nextLanguage;
      localStorage.setItem('live-voice', voice); localStorage.setItem('live-language', language);
      if (restart) { await end({hide:false}); await start(); } else setSettings(false);
    });
    $('live-dialog').addEventListener('close', () => { if (current && !$('live-dialog').open) end(); });
    // Hiding this page has no effect on native capture, playback, or connection timers.
    document.addEventListener('visibilitychange', () => {
      if (!current) return;
      cancelAnimationFrame(current.frame);
      if (!document.hidden) animate(current);
    });
    return {active, update, event, end};
  }
  if (typeof module !== 'undefined') module.exports = {voicesFrom, voiceName, voiceGroups};
  if (typeof window !== 'undefined') window.MobileCodexLive = {create, voicesFrom};
})();
