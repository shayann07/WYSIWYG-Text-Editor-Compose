import assert from 'node:assert/strict';
import {CandidateBCoordinator as B} from './candidate-b-coordinator.mjs';

export const cases = {
  invalidSelection(C) {
    const c=new C('abc'); let q;
    c._runInTransaction('probe',()=>{q=c.applyInput({type:'commit',text:'X',range:[0,0],selection:[999,999],epoch:0});return {status:'Accepted'};});
    assert.equal(c.commandOutcomes.get(q.ticketId).reason,'InvalidSelection');
    assert.equal(c.docText,'abc'); assert.deepEqual(c.selection,[0,0]);
  },
  publishedBasis(C) {
    class Probe extends C {_preedit(...a){const r=super._preedit(...a);if(!this.once){this.once=true;assert.equal(this.docText,'abc');this.q=this.applyInput({type:'nativeRemoval',range:[1,2],replacementText:'Q',epoch:0});}return r;}}
    const c=new Probe('abc');c.applyInput({type:'startComposition',range:[0,0]});c.applyInput({type:'preedit',text:'X'});
    assert.equal(c.docText,'XaQc'); assert.equal(c.commandOutcomes.get(c.q.ticketId).status,'Accepted');
  },
  capturedCommand(C) {
    const c=new C('abc'),cmd={type:'Format',patch:{bold:true}},target={type:'TrackedRange',range:[0,1]};
    c._runInTransaction('probe',()=>{c.dispatchCommand(cmd,target);target.range=[2,3];cmd.patch.bold=false;return {status:'Accepted'};});
    assert.deepEqual(c.docAttributes,[{bold:true},{},{}]);
  },
  immutablePublication(C) {
    const c=new C('abc');let old, attempts=0;
    c.addDidChangeListener(s=>{old ??= s;for(const f of [()=>{s.docText='BAD'},()=>{s.selection[0]=999},()=>{s.docAttributes[0].bold=true}]){attempts++;try{f()}catch{}}});
    c.applyInput({type:'commit',text:'X',range:[0,0]});assert.equal(c.docText,'Xabc');assert.deepEqual(c.selection,[1,1]);assert.deepEqual(c.docAttributes,[{},{},{},{}]);
    c.applyInput({type:'commit',text:'Y',range:[0,0]});assert.equal(old.docText,'Xabc');assert.equal(attempts,6);
  },
  duplicateInNotification(C) {
    const c=new C('abc');let q;
    c.addDidChangeListener(()=>{if(!q)q=c.applyInput({type:'commit',text:'X',range:[0,0],epoch:0},1);});
    c.applyInput({type:'commit',text:'X',range:[0,0],epoch:0},1);
    assert.equal(c.docText,'Xabc');assert.equal(c.commandOutcomes.get(q.ticketId).status,'Duplicate');assert.equal(c.undoStack.length,1);
  },
  duplicateReplacementInNotification(C) {
    const c=new C('abc');let q;
    c.addWillChangeListener(()=>{if(!q)q=c.applyInput({type:'commit',text:'X',range:[0,3],epoch:0},1);});
    c.applyInput({type:'commit',text:'X',range:[0,3],epoch:0},1);
    assert.equal(c.docText,'X');assert.equal(c.commandOutcomes.get(q.ticketId).status,'Duplicate');
  },
  externalCommandSurvivesRollback(C) {
    const c=new C('abc');let q;
    c._runInTransaction('probe',()=>{q=c.dispatchCommand({type:'Format',patch:{bold:true}},{type:'TrackedRange',range:[0,1]});throw Error('rollback');});
    assert.equal(c.commandOutcomes.get(q.ticketId).status,'Applied');assert.equal(c.docAttributes[0].bold,true);
  },
  postEditSelection(C) {
    const c=new C('abc');let q;
    c._runInTransaction('probe',()=>{c.applyInput({type:'commit',text:'Z',range:[0,0]});q=c.applyInput({type:'commit',text:'X',range:[1,2],selection:[2,2]});return {status:'Accepted'};});
    assert.equal(c.docText,'ZaXc');assert.deepEqual(c.selection,[3,3]);assert.equal(c.commandOutcomes.get(q.ticketId).status,'Accepted');
  },
  fragmentedNativeRange(C) {
    const c=new C('abc');let q;
    c._runInTransaction('probe',()=>{c.applyInput({type:'commit',text:'X',range:[1,1]});q=c.applyInput({type:'commit',text:'Q',range:[0,2]});return {status:'Accepted'};});
    assert.equal(c.docText,'aXbc');assert.equal(c.commandOutcomes.get(q.ticketId).reason,'TargetFragmented');
  },
  selectionAfterDeferredShift(C) {
    const c=new C('abc');
    c._runInTransaction('probe',()=>{c.applyInput({type:'commit',text:'XYZ',range:[0,0]});c.setSelection([1,2]);return {status:'Accepted'};});
    assert.deepEqual(c.selection,[4,5]);
  },
  closedQueue(C) {
    const c=new C('abc');let q;c._runInTransaction('probe',()=>{c.dispose();q=c.dispatchCommand({type:'Format',patch:{bold:true}},{type:'TrackedRange',range:[0,1]});return {status:'Accepted'};});
    assert.equal(c.commandOutcomes.get(q.ticketId).reason,'Disposed');assert.equal(c.docText,'abc');
  },
};

export async function runPublicBoundaryTests(C=B) {
  for(const fn of Object.values(cases)) fn(C);
  // A real scheduler continuation, no subsequent public mutation to trigger it.
  const c=new C('a');c.schedulingBudget=8;const tickets=[];
  c._runInTransaction('probe',()=>{for(let i=0;i<41;i++)tickets.push(c.applyInput({type:'commit',text:String(i%10),range:[0,0]}));return {status:'Accepted'};});
  await new Promise((resolve,reject)=>{let n=0;const poll=()=>{if(tickets.every(t=>c.commandOutcomes.has(t.ticketId)))resolve();else if(n++>100)reject(Error('Continuation did not finish'));else setTimeout(poll,2);};poll();});
  assert.equal(c.docText,Array.from({length:41},(_,i)=>String(i%10)).reverse().join('')+'a');
  assert.equal(new Set(tickets.map(t=>t.ticketId)).size,41);
  // Generated immediate/deferred equivalence under an unchanged outer snapshot.
  // Includes raw UTF-16, malformed fields, growth/shrinkage and reversed selection.
  let generated=0;
  for(const initial of ['','abc','a\ud800b','a\u0301']) for(const text of ['','X','\udc00','😀']) for(const valid of [false,true]) {
    const p={type:'commit',text,range:[0,initial.length],selection:valid?[text.length,0]:[text.length+1,0]};
    const immediate=new C(initial),deferred=new C(initial);const r=immediate.applyInput(p);let q;
    deferred._runInTransaction('probe',()=>{q=deferred.applyInput(p);return {status:'Accepted'};});const d=deferred.commandOutcomes.get(q.ticketId);
    assert.equal(d.status,r.status);assert.equal(d.reason,r.reason);assert.equal(deferred.docText,immediate.docText);assert.deepEqual(deferred.selection,immediate.selection);assert.deepEqual(deferred.docAttributes,immediate.docAttributes);generated++;
  }
  for(const p of [{type:'commit',text:7},{type:'partialCommit',length:-1},{type:'commit',range:[-1,1],text:'X'},{type:'setSelection',selection:[NaN,0]}]) {
    const c=new C('abc');let q;c._runInTransaction('probe',()=>{q=c.applyInput(p);return {status:'Accepted'};});assert.equal(c.commandOutcomes.get(q.ticketId).status,'Rejected');assert.equal(c.docText,'abc');generated++;
  }
  return {named:Object.keys(cases).length,scheduledRequests:41,generated};
}

// Real method overrides restore the defective behavior. Controls use identical assertions.
export async function runBoundaryMutations() {
  class MissingValidation extends B {_executeRequest(p,seq){if(p.selection?.[0]===999)return this._runInTransaction(p.type,()=>this._dispatchPayload(p));return super._executeRequest(p,seq);}}
  class MutablePublicCommand extends B {dispatchCommand(cmd,target){return this._runInTransaction('dispatchCommand',()=>this._internalDispatchCommand(cmd,target));}}
  class MutablePublication extends B {_publishWorkingState(){super._publishWorkingState();this._publishedState=structuredClone(this._publishedState);}}
  class StagedCapture extends B {applyInput(p,seq){if(this._inTransaction)this._publishedState={...this._publishedState,units:this._getWorkingVisibleUnits()};return super.applyInput(p,seq);}}
  const mutants=[[MissingValidation,'invalidSelection'],[MutablePublicCommand,'capturedCommand'],[MutablePublication,'immutablePublication'],[StagedCapture,'publishedBasis']];
  for(const [M,key] of mutants){cases[key](B);assert.throws(()=>cases[key](M),e=>e.code==='ERR_ASSERTION',key+' must kill behavior, not crash');}
  return {controls:mutants.length,assertionKills:mutants.length};
}
