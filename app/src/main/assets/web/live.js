/* Audio stays on the authenticated Codex WebRTC call; only SDP crosses Native. */
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
    let voice = localStorage.getItem('live-voice') || '', language = localStorage.getItem('live-language') || 'auto';
    const active = () => !!current;
    const status = text => { $('live-status').textContent = t(text); };
    function update() {
      const state = getState();
      $('live-task').hidden = !state.busy || !state.turnId;
      $('live-start').disabled = loading || !!current || !!state.busy || !!state.proBusy || !state.account?.type;
      $('live-mute').disabled = !current?.stream;
      $('live-text-send').disabled = !current?.connected || !!current?.sending || !$('live-text').value.trim();
      $('live-mute').setAttribute('aria-pressed', String(!!current?.muted));
      $('live-mute').setAttribute('aria-label', t(current?.muted ? '마이크 켜기' : '마이크 끄기'));
      $('live-mute').classList.toggle('muted', !!current?.muted);
      $('live-retry').hidden = !!current;
      if (current?.localThread && state.threadId !== current.localThread) end();
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
      clearTimeout(session.timeout); clearTimeout(session.disconnectTimer); cancelAnimationFrame(session.frame);
      session.stream?.getTracks().forEach(track => track.stop());
      session.pc?.close(); session.audio?.pause(); if (session.audio) session.audio.srcObject = null;
      session.context?.close().catch(() => {});
      $('live-orb').style.transform = '';
    }
    async function end({hide = true, message = ''} = {}) {
      const previous = current; current = null;
      if (previous) {
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
    function meter(context, stream) {
      const source = context.createMediaStreamSource(stream), analyser = context.createAnalyser();
      analyser.fftSize = 256; source.connect(analyser);
      return {analyser, samples:new Float32Array(analyser.fftSize)};
    }
    function amplitude(meter) {
      if (!meter) return 0;
      meter.analyser.getFloatTimeDomainData(meter.samples);
      return Math.min(1, Math.sqrt(meter.samples.reduce((sum, x) => sum + x*x, 0) / meter.samples.length) * 5);
    }
    function animate(session) {
      if (current !== session) return;
      const input = session.muted ? 0 : amplitude(session.inputMeter), output = amplitude(session.outputMeter);
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
        if (!navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) throw new Error(t('이 Android WebView는 실시간 음성을 지원하지 않습니다.'));
        const stream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true, noiseSuppression:true, autoGainControl:true}, video:false});
        if (current !== session) { stream.getTracks().forEach(track => track.stop()); return; }
        session.stream = stream;
        session.context = new AudioContext(); await session.context.resume();
        if (current !== session) { release(session); return; }
        session.inputMeter = meter(session.context, stream);
        const pc = session.pc = new RTCPeerConnection();
        const audio = session.audio = new Audio(); audio.autoplay = true;
        pc.ontrack = event => {
          if (current !== session) return;
          const remote = event.streams[0] || new MediaStream([event.track]);
          audio.srcObject = remote; session.outputMeter = meter(session.context, remote);
          audio.play().catch(error => fail(session, error));
        };
        pc.onconnectionstatechange = () => {
          if (current !== session) return;
          if (pc.connectionState === 'connected') {
            session.connected = true; clearTimeout(session.timeout); clearTimeout(session.disconnectTimer); session.disconnectTimer = null;
            status('듣고 있어요'); update();
          } else if (pc.connectionState === 'failed') fail(session, new Error(t('음성 연결이 끊겼습니다.')));
          else if (pc.connectionState === 'disconnected') session.disconnectTimer ||= setTimeout(() => fail(session, new Error(t('음성 연결이 끊겼습니다.'))), 8000);
        };
        stream.getTracks().forEach(track => { pc.addTrack(track, stream); track.onended = () => fail(session, new Error(t('마이크 연결이 종료되었습니다.'))); });
        const channel = session.channel = pc.createDataChannel('oai-events');
        channel.onmessage = event => {
          if (current !== session) return;
          try { const data = JSON.parse(event.data); if (data.type === 'error') fail(session, new Error(data.error?.message || data.message || t('음성 연결 오류'))); } catch { /* Non-JSON transport data is ignored. */ }
        };
        await pc.setLocalDescription(await pc.createOffer());
        if (current !== session) return;
        session.timeout = setTimeout(() => fail(session, new Error(t('음성 연결 시간이 초과되었습니다.'))), 45000);
        animate(session);
        const result = await call('live.start', {...options, key:session.key, sdp:pc.localDescription.sdp, voice, language, expectedThreadId, workspaceKey});
        if (current !== session) { await call('live.stop', {key:session.key}); return; }
        session.localThread = result.threadId;
      } catch (error) { fail(session, error); }
      finally { loading = false; update(); }
    }
    async function event(name, data) {
      if (name === 'live.interrupted') { await end({hide:false, message:'음성 대화가 중단되었습니다.'}); return; }
      if (name !== 'live.event') return;
      const session = current;
      if (!session || session.key !== data.key) return;
      const params = data.params || {};
      if (data.method === 'thread/realtime/sdp') {
        try { await session.pc.setRemoteDescription({type:'answer', sdp:params.sdp}); } catch (error) { fail(session, error); }
      } else if (data.method === 'thread/realtime/error') fail(session, new Error(params.message));
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
    $('live-mute').addEventListener('click', () => {
      if (!current?.stream) return;
      current.muted = !current.muted; current.stream.getAudioTracks().forEach(track => track.enabled = !current.muted);
      status(current.muted ? '마이크 꺼짐' : '듣고 있어요'); update();
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
    $('live-dialog').addEventListener('close', () => { if (current) end(); });
    document.addEventListener('visibilitychange', () => { if (document.hidden && current) end({hide:false, message:'음성 대화가 중단되었습니다.'}); });
    return {active, update, event, end};
  }
  if (typeof module !== 'undefined') module.exports = {voicesFrom, voiceName, voiceGroups};
  if (typeof window !== 'undefined') window.MobileCodexLive = {create, voicesFrom};
})();
