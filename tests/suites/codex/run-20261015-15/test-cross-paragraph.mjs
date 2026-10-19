import assert from 'node:assert/strict';
import {FlowCompositionModel as Flow,admitFlowComposition} from './flow-composition-model.mjs';
import {CandidateBCoordinator as Old} from '../run-20261015-14/candidate-b-coordinator.mjs';

// Flat expected tape made from fixture input, not candidate state or methods.
function reference(blocks) {
  const tape=[];
  const records=blocks.map(b=>({id:b.id,type:b.type??'paragraph',properties:structuredClone(b.properties??{}),parentId:b.parentId??'body',emptyBlockAttributes:structuredClone(b.emptyBlockAttributes??{})}));
  blocks.forEach((b,j)=>{if(j)tape.push({boundary:records[j],char:'\n'});for(let i=0;i<b.text.length;i++)tape.push({char:b.text[i],attrs:structuredClone(b.textAttrs?.[i]??{})});});
  return {head:records[0],tape};
}
function decode(ref,tape=ref.tape) {
  const out=[];let current={...ref.head,text:'',textAttrs:[]};
  for(const t of tape)if(t.boundary){out.push(current);current={...t.boundary,text:'',textAttrs:[]};}else{current.text+=t.char;current.textAttrs.push(t.attrs??{});}
  out.push(current);return out;
}
const fixture=()=>[{id:'p1',type:'heading',properties:{level:2,align:'left'},text:'ab',textAttrs:[{bold:true},{italic:true}]},{id:'p2',properties:{align:'right'},text:'cd',textAttrs:[{underline:true},{color:'blue'}]}];
function apply(c,p) {const r=c.applyInput(p);assert.notEqual(r.status,'Rejected',JSON.stringify(r));assert.ok(c.selection.every(x=>Number.isInteger(x)&&x>=0&&x<=c.docText.length),'published selection is within the flow');return r;}
function start(c,range){apply(c,{type:'startComposition',range});}
function visible(c,blocks){assert.deepEqual(c.materialize(),blocks);assert.equal(c.docText,blocks.map(b=>b.text).join('\n'));}
function replacement(ref,a,b,text,attrs={}) {return [...ref.tape.slice(0,a),...text.split('').map(char=>({char,attrs:{...attrs}})),...ref.tape.slice(b)];}

export function cancelAndDetour(C=Flow) {
  const blocks=fixture(),ref=reference(blocks),c=new C(blocks,[4,1]);start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});
  visible(c,decode(ref,replacement(ref,1,4,'XY',{italic:true})));assert.deepEqual(c.committedBlocks(),decode(ref));
  apply(c,{type:'preedit',text:'b\nc'});visible(c,decode(ref)); // separator ID and heading/paragraph properties return
  apply(c,{type:'preedit',text:'Q'});apply(c,{type:'cancel'});visible(c,decode(ref));assert.deepEqual(c.selection,[4,1]);assert.equal(c.undoStack.length,0);
}
export function commitUndo(C=Flow) {
  const blocks=fixture(),ref=reference(blocks),c=new C(blocks,[1,4]);start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});apply(c,{type:'commit',text:'Z'});
  const joined=decode(ref,replacement(ref,1,4,'Z',{italic:true}));visible(c,joined);assert.deepEqual(c.undoStack.map(x=>x.group),['Composition']);
  for(let i=0;i<5;i++){c.undo();visible(c,decode(ref));assert.deepEqual(c.selection,[1,4]);c.redo();visible(c,joined);}
}
export function partialCancel(C=Flow) {
  const blocks=fixture(),ref=reference(blocks),c=new C(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});const rev=c.documentRevision;apply(c,{type:'partialCommit',length:1});assert.equal(c.documentRevision,rev);apply(c,{type:'cancel'});
  const joined=decode(ref,replacement(ref,1,4,'X',{italic:true}));visible(c,joined);assert.equal(c.undoStack.length,1);c.undo();visible(c,decode(ref));c.redo();visible(c,joined);
}
export function runCrossParagraphTests() {
  cancelAndDetour();commitUndo();partialCancel();let named=3;
  // Retained boundary, prefix frozen before/after it, suffix rollback: structure stays exact.
  for(const k of [0,1,2]) {
    const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:'b\nX'});apply(c,{type:'partialCommit',length:k});apply(c,{type:'cancel'});visible(c,decode(ref));named++;
  }
  // Newly supplied LF is a soft break; a retained tagged LF is a paragraph boundary.
  {const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:'X\nY'});assert.equal(c.materialize().length,1);apply(c,{type:'unmark',settled:true});visible(c,decode(ref,replacement(ref,1,4,'X\nY',{italic:true})));c.undo();visible(c,decode(ref));named++;}
  // Formatting across an unchanged boundary edits characters, never the boundary or block props.
  {const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[0,5]);const q=c.dispatchCommand({type:'Format',patch:{bold:true}},{type:'CompositionTarget'});apply(c,{type:'unmark',settled:true});assert.equal(c.commandOutcomes.get(q.ticketId).status,'Applied');assert.equal(c.materialize().length,2);assert.deepEqual(c.committedDoc.find(u=>u.kind==='boundary').attrs,{});for(const b of c.materialize())for(const a of b.textAttrs)assert.equal(a.bold,true);c.undo();visible(c,decode(ref));named++;}
  // Retarget from one paragraph into the next; undo restores both complete nodes.
  {const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[1,2]);apply(c,{type:'preedit',text:'X'});apply(c,{type:'retarget',range:[1,4]});apply(c,{type:'preedit',text:'Q'});apply(c,{type:'unmark',settled:true});assert.equal(c.docText,'aQd');assert.equal(c.materialize().length,1);c.undo();visible(c,decode(ref));named++;}
  // Three paragraphs, including an empty middle paragraph and exact full-document deletion.
  {const blocks=[{id:'a',text:'ab'},{id:'empty',text:''},{id:'z',text:'cd'}],ref=reference(blocks),c=new Flow(blocks);start(c,[0,6]);apply(c,{type:'preedit',text:''});assert.equal(c.materialize().length,1);assert.equal(c.materialize()[0].id,'a');apply(c,{type:'unmark',settled:true});c.undo();visible(c,decode(ref));named++;}
  // Undo queued during cross-paragraph conversion restores the tree and keeps redo.
  {const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});c.undo();apply(c,{type:'commit',text:'Q'});visible(c,decode(ref));assert.equal(c.redoStack.length,1);c.redo();assert.equal(c.materialize().length,1);named++;}
  // Connection teardown preserves accepted visible structure in exactly one history group.
  for(const operation of ['openNewConnection','dispose']){const c=new Flow(fixture());start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});apply(c,{type:operation});assert.equal(c.materialize().length,1);assert.equal(c.undoStack.length,1);assert.equal(c.undoStack[0].beforeUnits.filter(u=>u.kind==='boundary').length,1);named++;}
  // Raw UTF-16 is retained across structure, not normalized or scalar-indexed.
  {const blocks=[{id:'a',text:'😀a'},{id:'b',text:'b\ud800'}],ref=reference(blocks),c=new Flow(blocks);start(c,[2,5]);apply(c,{type:'preedit',text:'\udc00'});apply(c,{type:'unmark',settled:true});assert.equal(c.docText,'😀\udc00\ud800');c.undo();visible(c,decode(ref));named++;}
  // Incompatible ancestry is a different protected structural operation, not a paragraph limit.
  {const c=new Flow([{id:'a',text:'a',parentId:'listItem1'},{id:'b',text:'b',parentId:'listItem2'}]);const before=c.structuralSnapshot();const r=c.applyInput({type:'startComposition',range:[0,3]});assert.equal(r.reason,'ProtectedStructuralBoundary');assert.deepEqual(c.structuralSnapshot(),before);named++;}
  {const c=new Flow([{id:'a',text:'a',parentId:'quote1'},{id:'b',text:'b',parentId:'quote1'}]);start(c,[0,3]);apply(c,{type:'preedit',text:'Q'});apply(c,{type:'commit',text:'Q'});assert.equal(c.docText,'Q');assert.equal(c.materialize()[0].parentId,'quote1');c.undo();assert.equal(c.materialize().length,2);named++;}
  {const c=new Flow([{id:'a',text:'a',parentId:'listItem1'},{id:'b',text:'b',parentId:'listItem2'}]);start(c,[0,1]);const before=c.structuralSnapshot();assert.equal(c.applyInput({type:'retarget',range:[2,3]}).reason,'ProtectedStructuralBoundary');assert.deepEqual(c.structuralSnapshot(),before);assert.equal(c.applyInput({type:'preedit',text:'Z',flowId:'otherCell'}).reason,'FlowMismatch');named++;}
  // Explicit retargeting may cover a previously accepted prefix; cancellation
  // rolls back only the new proposal, not that already committed text/tree.
  {const c=new Flow(fixture());start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});apply(c,{type:'partialCommit',length:1});apply(c,{type:'retarget',range:[0,4]});apply(c,{type:'preedit',text:'Q'});apply(c,{type:'cancel'});assert.equal(c.docText,'aXd');assert.equal(c.materialize().length,1);c.undo();assert.equal(c.materialize().length,2);named++;}
  // Browser/native initial deletion is provisional within the SAME session.
  {const blocks=fixture(),ref=reference(blocks),c=new Flow(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:''});assert.equal(c.docText,'ad');assert.equal(c.undoStack.length,0);apply(c,{type:'preedit',text:'Z'});apply(c,{type:'cancel'});visible(c,decode(ref));assert.equal(c.undoStack.length,0);named++;}
  {const c=new Flow([{id:'a',text:'',emptyBlockAttributes:{bold:true}},{id:'b',text:'',emptyBlockAttributes:{italic:true}}]);start(c,[0,1]);apply(c,{type:'preedit',text:'Q'});apply(c,{type:'unmark',settled:true});assert.equal(c.materialize().length,1);assert.deepEqual(c.materialize()[0].textAttrs,[{bold:true}]);c.undo();assert.equal(c.materialize()[1].emptyBlockAttributes.italic,true);named++;}
  // Disjoint composition islands must not style untouched middle paragraphs.
  {const blocks=[{id:'a',text:'ab'},{id:'m',text:'middle'},{id:'z',text:'cd'}],c=new Flow(blocks);start(c,[1,2]);apply(c,{type:'preedit',text:'X'});apply(c,{type:'retarget',range:[10,11]});apply(c,{type:'preedit',text:'Y'});c.dispatchCommand({type:'Format',patch:{bold:true}},{type:'CompositionTarget'});apply(c,{type:'unmark',settled:true});assert.equal(c.docText,'aX\nmiddle\nYd');assert.ok(c.materialize()[1].textAttrs.every(a=>a.bold!==true));assert.equal(c.materialize()[0].textAttrs[1].bold,true);assert.equal(c.materialize()[2].textAttrs[0].bold,true);named++;}
  {const blocks=[{id:'a',text:'ab'},{id:'m',text:'middle'},{id:'z',text:'cd'}],c=new Flow(blocks);start(c,[1,2]);apply(c,{type:'preedit',text:'X'});apply(c,{type:'retarget',range:[10,11]});apply(c,{type:'preedit',text:'YZ'});apply(c,{type:'partialCommit',length:1});c.dispatchCommand({type:'Format',patch:{bold:true}},{type:'CompositionTarget'});apply(c,{type:'unmark',settled:true});assert.equal(c.docText,'aX\nmiddle\nYZd');assert.ok(c.materialize()[1].textAttrs.every(a=>a.bold!==true));assert.equal(c.materialize()[0].textAttrs[1].bold,true);assert.equal(c.materialize()[2].textAttrs[1].bold,true);named++;}
  // Revision comparison includes structural tags even if the projection is equal.
  {const c=new Flow([{id:'a',text:'a'},{id:'b',text:'b'}]);const before=c.docText,revision=c.documentRevision;c._runInTransaction('structure-only-probe',()=>{delete c.committedDoc[1].kind;return {status:'Accepted'};});assert.equal(c.docText,before);assert.equal(c.documentRevision,revision+1);assert.equal(c.materialize().length,1);named++;}
  // No partial text/tree or outcome publication on a failed settlement.
  {class Fault extends Flow {_drainPendingCommands(...a){super._drainPendingCommands(...a);if(this.fail)throw Error('AfterTreeHistory');}}const blocks=fixture(),ref=reference(blocks),c=new Fault(blocks);start(c,[1,4]);apply(c,{type:'preedit',text:'XY'});const before=c.structuralSnapshot();c.fail=true;assert.equal(c.applyInput({type:'commit',text:'Q'}).status,'Rejected');assert.deepEqual(c.structuralSnapshot(),before);assert.equal(c.undoStack.length,0);c.fail=false;apply(c,{type:'commit',text:'Q'});c.undo();visible(c,decode(ref));named++;}
  const segments=[{kind:'text',start:0,end:2,blockId:'a'},{kind:'blockSeparator',start:2,end:3,joinable:true},{kind:'text',start:3,end:5,blockId:'b'}];assert.equal(admitFlowComposition(segments,[1,4]).status,'Accepted');named++;
  // Generated differential cases: fresh replacements cannot match baseline text,
  // so expected partial-commit ownership is derived independently by array splice.
  let generated=0;
  for(const lengths of [[1,1],[2,3],[0,2],[2,0],[1,0,2],[3,2,1]]) {
    const blocks=lengths.map((n,i)=>({id:'p'+i,text:String.fromCharCode(97+i).repeat(n),properties:{align:i%2?'right':'left'}})),ref=reference(blocks);
    for(let a=0;a<ref.tape.length;a++)for(let b=a+1;b<=ref.tape.length;b++){
      if(!ref.tape.slice(a,b).some(t=>t.boundary))continue;
      for(const mode of ['cancel','commit','partial']){
        const c=new Flow(blocks,[b,a]);start(c,[a,b]);apply(c,{type:'preedit',text:'ЖΩ'});
        if(mode==='partial'){apply(c,{type:'partialCommit',length:1});apply(c,{type:'cancel'});visible(c,decode(ref,replacement(ref,a,b,'Ж')));}
        else if(mode==='cancel'){apply(c,{type:'cancel'});visible(c,decode(ref));}
        else {apply(c,{type:'commit',text:'Ω'});visible(c,decode(ref,replacement(ref,a,b,'Ω')));}
        if(mode!=='cancel'){assert.equal(c.undoStack.length,1);c.undo();visible(c,decode(ref));assert.deepEqual(c.selection,[b,a]);}
        generated++;
      }
    }
  }
  return {named,generated,scope:'tagged-flow composition and materialized block IDs/properties/attributes; no platform host'};
}
export function runCrossParagraphMutations() {
  class TextOnlyUndo extends Flow {_internalUndo(...a){return Old.prototype._internalUndo.call(this,...a);}}
  class PartialOriginalFootprint extends Flow {_partialCommit(...a){return Old.prototype._partialCommit.call(this,...a);}}
  class BoundaryLost extends Flow {_preedit(...a){const r=super._preedit(...a);for(const s of this.provisionalOverlay?.segments??[])for(const u of s.units)if(u.kind==='boundary')delete u.kind;return r;}}
  const specs=[[commitUndo,TextOnlyUndo],[partialCancel,PartialOriginalFootprint],[cancelAndDetour,BoundaryLost]];
  for(const [check,M]of specs){check(Flow);assert.throws(()=>check(M),e=>e.code==='ERR_ASSERTION');}
  return {controls:specs.length,assertionKills:specs.length};
}
