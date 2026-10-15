'use strict';
// Normalization follows CodePet's chat-events.js (MIT; see CODEPET-LICENSE),
// extended for session ids, streamed AGY steps, and failures with partial text.
function normalize(provider, e) {
  const out=[];
  const id=e.session_id||e.conversation_id||e.result?.conversation_id;
  if(id)out.push({kind:'session',id});
  if(provider==='claude') {
    const inner=e.event;
    if(e.type==='stream_event'&&inner?.type==='content_block_delta'&&inner.delta?.type==='text_delta')out.push({kind:'delta',text:inner.delta.text||''});
    if(e.type==='stream_event'&&inner?.content_block?.type==='tool_use')out.push({kind:'status',status:'working'});
    if(e.type==='result')out.push(e.is_error||e.subtype!=='success'?{kind:'error',message:e.result||e.errors?.join('\n')||e.subtype}:{kind:'final',text:e.result||'',sessionId:e.session_id});
  } else if(provider==='agy') {
    const s=e.step_update;
    if(e.event==='step_update'&&s?.step_type==='agent_response'&&s.text_delta)out.push({kind:'delta',text:s.text_delta});
    if(e.event==='step_update'&&s?.step_type==='tool')out.push({kind:'status',status:'working'});
    if(e.event==='result')out.push(e.result?.status==='SUCCESS'?{kind:'final',text:e.result.response||'',sessionId:e.result.conversation_id}:{kind:'error',message:e.result?.error||e.result?.status||'Antigravity 응답 오류'});
  }
  return out;
}
module.exports={normalize};
