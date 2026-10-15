import assert from 'node:assert/strict';
import {Schema} from './runtime/node_modules/prosemirror-model/dist/index.js';
import {EditorState} from './runtime/node_modules/prosemirror-state/dist/index.js';

// Model-level test: these replacements represent opaque native candidate updates.
// This does not simulate a browser, native keyboard, or Compose adapter.
const schema=new Schema({nodes:{doc:{content:'paragraph+'},paragraph:{content:'text*'},text:{}},marks:{bold:{},italic:{}}});
const bold=schema.marks.bold.create(),italic=schema.marks.italic.create();
const original=schema.node('doc',null,[schema.node('paragraph',null,[schema.text('a',[bold]),schema.text('b',[italic])])]);

function lcpLcs(oldText,newText){
  let prefix=0;
  while(prefix<Math.min(oldText.length,newText.length)&&oldText[prefix]===newText[prefix])prefix++;
  let suffix=0;
  while(suffix<Math.min(oldText.length,newText.length)-prefix&&oldText[oldText.length-1-suffix]===newText[newText.length-1-suffix])suffix++;
  return{prefix,suffix};
}
function candidateFrom(state,text){
  const old=state.doc.textContent,{prefix,suffix}=lcpLcs(old,text);
  if(prefix===old.length&&prefix===text.length)return state;
  const tr=state.tr.setStoredMarks([bold]);
  tr.insertText(text.slice(prefix,text.length-suffix),1+prefix,1+old.length-suffix);
  return state.apply(tr);
}
const start=EditorState.create({schema,doc:original});
const direct=candidateFrom(start,'ab');
const detour=candidateFrom(candidateFrom(start,'x'),'ab');
assert.deepEqual(direct.doc.toJSON(),original.toJSON());
assert.notDeepEqual(detour.doc.toJSON(),direct.doc.toJSON());
assert.equal(detour.doc.textContent,direct.doc.textContent);

// Counterexample to snapshot-only restoration: an explicit user format operation
// during the candidate cycle must not be erased when the text returns.
const explicitlyFormatted=start.apply(start.tr.addMark(1,3,bold).removeMark(1,3,schema.marks.italic));
assert.notDeepEqual(original.toJSON(),explicitlyFormatted.doc.toJSON());

console.log(JSON.stringify({
  runtime:process.version,
  baseline:'ProseMirror state operations under an explicit incremental common-prefix/suffix reconciliation policy',
  direct:direct.doc.toJSON(),detour:detour.doc.toJSON(),
  result:'Equal final text and initial state, unequal final marks after a candidate detour.',
  snapshotOnlyRepair:'Restores this fixture, but erases a later explicit formatting change unless separately accounted for.',
  limitation:'No claim about ProseMirror browser composition handling; the reconciliation policy is the tested baseline.'
},null,2));
