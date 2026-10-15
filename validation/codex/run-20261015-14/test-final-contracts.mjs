import assert from 'node:assert/strict';
import {CandidateBCoordinator as B} from './candidate-b-coordinator.mjs';
import {admitCompositionRange} from './composition-admission.mjs';

const format={type:'Format',patch:{bold:true}};
function observe(c) {return {text:c.docText,attrs:c.docAttributes,selection:c.selection,rev:c.documentRevision,session:c.sessionRevision,undo:structuredClone(c.undoStack),redo:structuredClone(c.redoStack),pending:structuredClone(c.settlementObligation.pendingCommands),outcomes:[...c.commandOutcomes],marked:c.markedRange};}
function composed(C=B) {const c=new C('abc');c.applyInput({type:'startComposition',range:[1,2]});c.applyInput({type:'preedit',text:'X'});return c;}
function evidence(c) {return {adapterQualification:'QualifiedSyncSettlement',epoch:c.epoch,sessionId:c.activeSessionId,generation:c.sessionRevision,extent:[1,2],recordId:'record-1'};}

export function publicationContract(C=B) {
  class Probe extends C {_preedit(...a){const r=super._preedit(...a);if(this.inspect){this.observed={text:this.docText,selection:this.selection,rev:this.documentRevision,session:this.sessionRevision};if(this.fail)throw Error('ReviewFailure');}return r;}}
  const c=composed(Probe);c.inspect=true;c.fail=true;const before=observe(c);
  assert.equal(c.applyInput({type:'preedit',text:'YY'}).status,'Rejected');
  assert.deepEqual(c.observed,{text:before.text,selection:before.selection,rev:before.rev,session:before.session});assert.deepEqual(observe(c),before);
  c.fail=false;const r=c.applyInput({type:'preedit',text:'YY'});assert.equal(r.status,'Accepted');assert.equal(c.observed.text,before.text);assert.equal(c.docText,'aYYc');assert.equal(c.sessionRevision,before.session+1);
  class MetadataProbe extends C {_startComposition(...a){const r=super._startComposition(...a);this.observedSession=this.activeSessionId;throw Error('MetadataRollback');}}
  const meta=new MetadataProbe('abc');assert.equal(meta.applyInput({type:'startComposition',range:[1,2]}).status,'Rejected');assert.equal(meta.observedSession,null);assert.equal(meta.activeSessionId,null);
}
export function sessionContract(C=B) {
  const c=new C('abc');
  for(const action of [()=>c.setSelection([1,1]),()=>c.dispatchCommand(format,{type:'SelectionTarget'}),()=>c.dispatchCommand(format,{type:'SelectionTarget'}),()=>c.setSelection([1,1])]){
    const s=c.sessionRevision,d=c.documentRevision;const r=action();assert.ok(['Accepted','Applied'].includes(r.status));assert.equal(c.sessionRevision,s+1);assert.equal(c.documentRevision,d);assert.equal(r.sessionRevision,c.sessionRevision);
  }
  const s=c.sessionRevision;assert.equal(c.setSelection([99,99]).status,'Rejected');assert.equal(c.sessionRevision,s);
  c.applyInput({type:'commit',text:'Z',range:[0,0],epoch:0},10);const after=c.sessionRevision;
  assert.equal(c.applyInput({type:'commit',text:'Z',range:[0,0],epoch:0},10).status,'Duplicate');assert.equal(c.sessionRevision,after);
  const composing=composed(C),old=evidence(composing);composing.setSelection([0,0]);const before=observe(composing);const rejected=composing.undo(true,old);
  assert.equal(rejected.reason,'ObsoleteSettlementEvidence');assert.deepEqual(observe(composing),before);
  const waiting=composed(C);waiting.undo();assert.ok(waiting.activeSettlementToken);waiting.applyInput({type:'partialCommit',length:0});assert.equal(waiting.activeSettlementToken,null);
}
export function atomicSettlementContract(C=B) {
  class Failure extends C {_drainPendingCommands(...a){super._drainPendingCommands(...a);if(this.failDrain)throw Error('FailureAfterCommandDrain');}}
  const c=composed(Failure),ticket=c.dispatchCommand(format,{type:'CompositionTarget'});const before=observe(c);let did=0,rollback=0;
  c.addDidChangeListener(()=>did++);c.addRollbackListener(()=>rollback++);c.failDrain=true;
  const failed=c.applyInput({type:'commit',text:'Y',epoch:0},40);assert.equal(failed.status,'Rejected');assert.deepEqual(observe(c),before);assert.equal(did,0);assert.equal(rollback,1);assert.equal(c.receiptCache.has('0:40'),false);
  c.failDrain=false;const committed=c.applyInput({type:'commit',text:'Y',epoch:0},40);
  assert.equal(committed.status,'Accepted');assert.equal(did,1);assert.equal(c.docText,'aYc');assert.equal(c.docAttributes[1].bold,true);assert.equal(c.sessionRevision,before.session+1);assert.equal(c.documentRevision,before.rev+1);
  assert.deepEqual(c.undoStack.map(g=>g.group),['Composition','Formatting']);assert.equal(c.commandOutcomes.get(ticket.ticketId).status,'Applied');
  c.undo();assert.equal(c.docText,'aYc');assert.equal(c.docAttributes[1].bold,undefined);c.undo();assert.equal(c.docText,'abc');
  // Multiple logical history operations still produce one callback publication.
  const d=composed(C);d.dispatchCommand(format,{type:'CompositionTarget'});d.undo();let publications=0;d.addDidChangeListener(()=>publications++);const rev=d.sessionRevision;
  d.applyInput({type:'commit',text:'Y'});assert.equal(publications,1);assert.equal(d.sessionRevision,rev+1);assert.equal(d.docText,'aYc');assert.equal(d.docAttributes[1].bold,undefined);assert.equal(d.redoStack.length,1);
}
export function rejectedEvidenceContract(C=B) {
  let count=0;
  for(const change of [e=>null,e=>({...e,adapterQualification:'Unknown'}),e=>({...e,epoch:99}),e=>({...e,sessionId:'wrong'}),e=>({...e,generation:e.generation-1}),e=>({...e,extent:[NaN,2]}),e=>({...e,extent:[1.5,2]}),e=>({...e,recordId:''})]){
    const c=composed(C),before=observe(c);const r=c.undo(true,change(evidence(c)));assert.equal(r.status,'Rejected');assert.deepEqual(observe(c),before);assert.equal(c.settlementObligation.pendingCommands.length,0);count++;
  }
  const c=composed(C);assert.equal(c.undo().action,'QueuedBehindSettlement');assert.equal(c.settlementObligation.pendingCommands.length,1);
  return count;
}

export function structuralAdmissionContract(admit=admitCompositionRange) {
  const blocks=[{kind:'text',blockId:'p1',blockOffset:0,start:0,end:2},{kind:'blockSeparator',start:2,end:3},{kind:'text',blockId:'p2',blockOffset:0,start:3,end:5}];
  const before=structuredClone(blocks);
  assert.equal(admit(blocks,[1,4]).reason,'CrossBlockComposition');assert.equal(admit(blocks,[2,3]).reason,'CrossBlockComposition');assert.deepEqual(blocks,before);
  assert.deepEqual(admit(blocks,[0,2]),{status:'Accepted',blockId:'p1',range:[0,2]});assert.deepEqual(admit(blocks,[3,5]),{status:'Accepted',blockId:'p2',range:[0,2]});
  assert.equal(admit(blocks,[3,5],'before','p1').reason,'CompositionBlockMismatch');
  assert.equal(admit(blocks,[3,3],'before','p1').reason,'CompositionBlockMismatch');
  assert.equal(admit([{kind:'text',blockId:'code',blockOffset:0,start:0,end:3}],[0,3]).status,'Accepted'); // raw LF is text; metadata is authoritative
  assert.equal(admit([{kind:'atom',start:0,end:1}],[0,1]).reason,'ProtectedCompositionBoundary');
  assert.equal(admit([{kind:'text',blockId:'empty',blockOffset:0,start:0,end:0}],[0,0]).status,'Accepted');
  // Explicit structural deletion is an independent, already committed application
  // command. Its documented result is one paragraph "ad"; IME starts afterward.
  const preDeletion={paragraphs:[{id:'p1',text:'ab'},{id:'p2',text:'cd'}],selection:[1,4]};
  const preparationHistory={group:'DeleteSelection',before:preDeletion,after:{paragraphs:[{id:'p1',text:'ad'}],selection:[1,1]}};
  const c=new B('ad',null,[1,1]);c.applyInput({type:'startComposition',range:[1,1]});c.applyInput({type:'preedit',text:'X'});c.applyInput({type:'cancel'});assert.equal(c.docText,'ad');assert.equal(c.undoStack.length,0);assert.deepEqual(preparationHistory.before,preDeletion);
  c.applyInput({type:'commit',text:'Y',range:[1,1]});c.undo();assert.equal(c.docText,'ad');assert.equal(preparationHistory.group,'DeleteSelection'); // root-history owner restores the earlier structural root on a second Undo
  return {projectionCases:9,preparationPolicyExamples:2,scope:'classifier and declared fixture; not a hierarchical reducer implementation'};
}
export function runFinalContractTests() {
  publicationContract();sessionContract();atomicSettlementContract();const evidenceCases=rejectedEvidenceContract();const structural=structuralAdmissionContract();return {contracts:5,evidenceCases,structural};
}
export function runFinalContractMutations() {
  class OldSession extends B {_runInTransaction(...args){const s=this._sessionRevision;const r=super._runInTransaction(...args);if(args[0]==='setSelection')this.sessionRevision=s;return r;}}
  class DirtyReads extends B {get docText(){return this._getWorkingDocText();}}
  class FallbackOnRejection extends B {undo(sync,e){const r=super.undo(sync,e);if(sync&&r.status==='Rejected')super.undo();return r;}}
  const specs=[[publicationContract,DirtyReads],[sessionContract,OldSession],[rejectedEvidenceContract,FallbackOnRejection]];
  for(const [check,M] of specs){check(B);assert.throws(()=>check(M),e=>e.code==='ERR_ASSERTION');}
  structuralAdmissionContract();assert.throws(()=>structuralAdmissionContract(()=>({status:'Accepted'})),e=>e.code==='ERR_ASSERTION');
  return {controls:4,assertionKills:4};
}
