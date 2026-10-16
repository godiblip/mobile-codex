const test = require('node:test');
const assert = require('node:assert/strict');
const {voicesFrom, voiceName, voiceGroups} = require('../app/src/main/assets/web/live.js');

test('Live offers the v3 voices and does not merge a different protocol catalog', () => {
  assert.deepEqual(voicesFrom({voices:{v1:['cove','sol'],v3:['cove','hanul','haetsal'],defaultV1:'cove'}}), {
    ids:['cove','hanul','haetsal'],defaultVoice:'cove'
  });
});

test('older runtimes without v3 retain their supported voice choices', () => {
  assert.deepEqual(voicesFrom({voices:{v1:['cove','sol'],defaultV1:'cove'}}), {
    ids:['cove','sol'],defaultVoice:'cove'
  });
});

test('an omitted optional v3 list may be serialized as an empty array', () => {
  assert.deepEqual(voicesFrom({voices:{v1:['cove','sol'],v3:[],defaultV1:'cove'}}), {
    ids:['cove','sol'],defaultVoice:'cove'
  });
});

test('new voices retain filtering, deduplication and a valid default', () => {
  assert.deepEqual(voicesFrom({voices:{v3:['hanul','haetsal','hanul','<script>',null],defaultV1:'cove'}}), {
    ids:['hanul','haetsal'],defaultVoice:'hanul'
  });
});

test('voice groups only contain available voices and preserve unclassified additions', () => {
  assert.deepEqual(voiceGroups(['cove', 'haetsal', 'hanul', 'miko', 'arbor', 'future_voice']), [
    {label:'한국어', ids:['hanul', 'haetsal']},
    {label:'미국 영어', ids:['cove']},
    {label:'독일어', ids:['miko']},
    {label:'기타', ids:['arbor', 'future_voice']},
  ]);
  assert.deepEqual(voiceGroups(['cove']), [{label:'미국 영어', ids:['cove']}]);
  assert.deepEqual(voiceGroups([]), []);
  assert.equal(voiceName('hanul'), '한울');
  assert.equal(voiceName('haetsal'), '햇살');
  assert.equal(voiceName('cove'), 'Cove');
});
