import assert from 'node:assert/strict';
import {IntegratedModel,semantic} from './integrated-model.mjs';
import {textBlock as p,container as c,project,raw,leaves,index,clone,validateTree} from './structural-model.mjs';
const flow=(...children)=>c('f','flow',children),li=(id,...children)=>c(id,'item',children),list=(id,...children)=>c(id,'list',children);
function fixture(){const a=p('a','ab');a.units[1].attrs={italic:true};return flow(list('L',li('i1',a),li('i2',p('b','cd'),list('nested',li('ni',p('t','TAIL'))))));}
function driver(root=fixture(),selection=[1,4],C=IntegratedModel){
  const scheduled=[],m=new C(root,selection,{schedule:fn=>scheduled.push(fn)});let seq=0;
  const n=(type,more={},options={})=>m.dispatch({type,...more,stamp:{epoch:m.snapshot().epoch,generation:m.snapshot().generation,sequence:++seq,token:'A',verified:true,fenceVerified:true,...more.stamp}},options);
  const accepted=r=>{assert.equal(r.status,'Accepted',JSON.stringify(r));return r;};
  const send=(type,more={})=>accepted(n(type,more));
  const app=(type,more={})=>accepted(m.dispatch({type,...more}));
  const drain=()=>{m.flush();while(scheduled.length)scheduled.shift()();};
  return {m,n,send,app,drain,scheduled};
}
function check(m){
  const s=m.snapshot();validateTree(s.tree);validateTree(s.committedTree);assert.equal(raw(project(s.tree)),s.text);
  assert.ok(s.selection.every(x=>Number.isInteger(x)&&x>=0&&x<=s.text.length));
  if(!s.active&&s.history.length)assert.equal(semantic(s.history.at(-1).after.tree),semantic(s.tree),'history-current root continuity');
  for(let i=1;i<s.history.length;i++)assert.equal(semantic(s.history[i-1].after.tree),semantic(s.history[i].before.tree),'history adjacency');
  if(!s.active){let next=s.tree;for(let i=s.redo.length-1;i>=0;i--){assert.equal(semantic(s.redo[i].before.tree),semantic(next),'redo continuity');next=s.redo[i].after.tree;}}
  assert.equal(new Set(s.units.map(u=>u.tokenId)).size,s.units.length,'unit identities must be unique');
}

export const cases={
  queuedStructuralContinuation(C=IntegratedModel){
    const src=fixture(),{m,n,drain}=driver(src,[1,4],C);
    const t1=n('preedit',{text:'n'},{defer:true});const t2=n('preedit',{text:'ni'},{defer:true});const t3=n('commit',{text:'你'},{defer:true});
    assert.equal(m.snapshot().text,'ab\ncd\nTAIL');assert.equal(t1.status,'Deferred');drain();
    assert.equal(m.snapshot().text,'a你d\nTAIL');assert.equal(m.receipt(t2.ticket).status,'Accepted');assert.equal(m.receipt(t3.ticket).status,'Accepted');
    assert.equal(m.snapshot().history.length,1);assert.equal(index(m.snapshot().tree).get('nested').parent,'i1');
    m.dispatch({type:'undo'});assert.deepEqual(m.snapshot().tree,src);m.dispatch({type:'redo'});assert.equal(m.snapshot().text,'a你d\nTAIL');check(m);
  },
  failedInitiator(C=IntegratedModel){
    const {m,n,drain}=driver(fixture(),[1,4],C),before=m.snapshot();
    const a=n('preedit',{text:'X',selection:[999,999]},{defer:true}),b=n('commit',{text:'Y'},{defer:true});drain();
    assert.equal(m.receipt(a.ticket).status,'Rejected');assert.equal(m.receipt(b.ticket).reason,'InitiatingSessionFailed');assert.deepEqual(m.snapshot(),before);
  },
  partialCancelLedger(C=IntegratedModel){
    const src=fixture(),{m,send,app}=driver(src,[1,4],C);
    send('preedit',{text:'XY'});send('partialCommit',{length:1});
    const fmt=app('format',{target:'composition',patch:{bold:true}}),u1=app('undo'),u2=app('undo');
    send('cancel');assert.deepEqual(m.snapshot().tree,src);
    const out=new Map(m.snapshot().outcomes);assert.equal(out.get(fmt.ticket).reason,'CompositionCancelled');assert.equal(out.get(u1.ticket).reason,'CommandWithoutHistory');assert.equal(out.get(u2.ticket).status,'Applied');
    assert.equal(m.snapshot().redo.length,1);app('redo');assert.equal(m.snapshot().text,'aXd\nTAIL');check(m);
  },
  changedTerminalCommit(C=IntegratedModel){
    const src=fixture(),{m,send,app}=driver(src,[1,4],C);
    send('startComposition');app('undo');send('commit',{text:'X'});
    assert.deepEqual(m.snapshot().tree,src);assert.equal(m.snapshot().redo.length,1);check(m);
  },
  cancelDoesNotDoubleUndo(C=IntegratedModel){
    const {m,send,app}=driver(flow(p('p','')),[0,0],C);
    send('commit',{text:'hello ',stamp:{token:'base'}});send('preedit',{text:'world'});app('undo');send('cancel');
    assert.equal(m.snapshot().text,'hello ');assert.equal(m.snapshot().history.length,1);assert.equal(m.snapshot().redo.length,0);check(m);
  },
  atomicSettlementRollback(C=IntegratedModel){
    const {m,send,n,app}=driver(fixture(),[1,4],C);
    send('preedit',{text:'XY'});app('format',{target:'composition',patch:{bold:true}});app('undo');
    const before=m.snapshot();const observations=[];m.did.push(s=>observations.push(s.text));
    const r=n('commit',{text:'Z',failAt:'afterCommand:0'});assert.equal(r.status,'Rejected');assert.deepEqual(m.snapshot(),before);assert.deepEqual(observations,[]);
    send('commit',{text:'Z'});assert.equal(m.snapshot().text,'aZd\nTAIL');assert.equal(m.snapshot().history.length,1);assert.equal(m.snapshot().redo.length,1);check(m);
  },
  reentryPublishedBasis(C=IntegratedModel){
    const src=fixture(),{m,n,app,drain}=driver(src,[1,4],C);let queued=false,seen,ft;
    m.will.push((s,payload)=>{if(!queued&&payload.type==='preedit'){queued=true;seen=m.snapshot();ft=m.dispatch({type:'format',range:[6,10],patch:{bold:true}});n('preedit',{text:'YZ'});}});
    n('preedit',{text:'X'});drain();assert.deepEqual(seen.tree,src);assert.equal(m.snapshot().text,'aYZd\nTAIL');
    n('commit',{text:'Z'});const tail=index(m.snapshot().tree).get('t').node;assert.ok(tail.units.every(u=>u.attrs.bold===true));
    assert.ok(index(m.snapshot().tree).get('a').node.units.every(u=>u.attrs.bold!==true));assert.equal(new Map(m.snapshot().outcomes).get(ft.ticket).status,'Applied');check(m);
  },
  failurePreservesReentrantRequests(C=IntegratedModel){
    const {m,n,send,drain}=driver(fixture(),[1,4],C);let once=false,follow;
    m.will.push((s,p)=>{if(!once&&p.failAt){once=true;follow=n('preedit',{text:'later'});}});
    const r=n('preedit',{text:'X',failAt:'beforePublication'});drain();assert.equal(r.status,'Rejected');assert.equal(m.receipt(follow.ticket).reason,'InitiatingSessionFailed');
    send('commit',{text:'fresh',stamp:{token:'B'}});assert.equal(m.snapshot().text,'afreshd\nTAIL');check(m);
  },
  barrierAndAfterTicket(C=IntegratedModel){
    const {m,n,drain}=driver(flow(p('p','hello world')),[0,5],C);
    n('preedit',{text:'n'},{defer:true});n('preedit',{text:'ni'},{defer:true});m.dispatch({type:'abort'},{defer:true});
    const sel=m.dispatch({type:'selection',range:[11,11]},{defer:true});
    const late=n('commit',{text:'你'},{defer:true});n('commit',{text:'!',afterTicket:sel.ticket,stamp:{generation:3,token:null}},{defer:true});drain();
    assert.equal(m.receipt(late.ticket).reason,'StaleGeneration');assert.equal(m.snapshot().text,'hello world!');check(m);
  },
  postEditSelectionMapping(C=IntegratedModel){
    const {m,n,drain}=driver(flow(p('p','abcd')),[0,0],C);
    n('commit',{text:'X',range:[0,0],stamp:{token:'first'}},{defer:true});
    const t=n('commit',{text:'Y',range:[2,3],selection:[3,3],stamp:{token:'second'}},{defer:true});drain();
    assert.equal(m.receipt(t.ticket).status,'Accepted');assert.equal(m.snapshot().text,'XabYd');assert.deepEqual(m.snapshot().selection,[4,4]);check(m);
  },
  noOpAndIdentityReuse(C=IntegratedModel){
    const {m,send,app}=driver(flow(p('p','abc')),[1,2],C);
    send('startComposition');const revision=m.snapshot().documentRevision;send('preedit',{text:'b'});assert.equal(m.snapshot().documentRevision,revision);
    send('commit',{text:'X'});app('selection',{range:[3,3]});send('commit',{text:'Y',stamp:{token:'B'}});
    assert.equal(m.snapshot().text,'aXcY');check(m);app('undo');assert.equal(m.snapshot().text,'aXc');app('undo');assert.equal(m.snapshot().text,'abc');check(m);
  },
  unmarkResumeAndFence(C=IntegratedModel){
    const {m,send,n,app}=driver(fixture(),[1,4],C);send('preedit',{text:'XY'});app('undo');send('unmark',{settled:false});
    const evidence=m.snapshot().active.fence;send('preedit',{text:'Z'});const rev=m.snapshot().documentRevision;
    assert.equal(n('completeSettlement',{evidence}).reason,'CandidateStillActive');assert.equal(m.snapshot().documentRevision,rev);
    send('unmark',{settled:false});assert.equal(n('completeSettlement',{evidence}).reason,'ObsoleteSettlementToken');
    send('completeSettlement',{evidence:m.snapshot().active.fence});assert.deepEqual(m.snapshot().tree,fixture());check(m);
  },
  connectionTurnover(C=IntegratedModel){
    const {m,send,n,app}=driver(fixture(),[1,4],C);send('preedit',{text:'X'});const f=app('format',{target:'composition',patch:{bold:true}}),u=app('undo');app('openNewConnection');
    assert.equal(m.snapshot().text,'aXd\nTAIL');assert.equal(new Map(m.snapshot().outcomes).get(f.ticket).reason,'StaleEpoch');assert.equal(new Map(m.snapshot().outcomes).get(u.ticket).reason,'StaleEpoch');
    assert.equal(n('commit',{text:'wrong',stamp:{epoch:1,token:'A'}}).reason,'StaleEpoch');check(m);
  },
  duplicateAndImmutablePublication(C=IntegratedModel){
    const {m}=driver(fixture(),[1,4],C);const input={type:'commit',text:'X',stamp:{epoch:1,generation:1,sequence:1,token:'A',verified:true,fenceVerified:true}};let result,immutable=false;
    m.did.push(()=>{result=m.dispatch(input);try{m.snapshot().tree.children.pop();}catch(e){immutable=e instanceof TypeError;}});
    m.dispatch(input);assert.equal(result.status,'Duplicate');assert.equal(immutable,true);assert.equal(m.snapshot().history.length,1);assert.equal(m.snapshot().sessionRevision,1);check(m);
  },
  unsupportedSeamAndCancelledCommands(C=IntegratedModel){
    const src=flow(p('a','A'),{id:'o',type:'object',props:{kind:'image'}},p('b','B')),{m,send,n,app}=driver(src,[1,2],C);
    send('startComposition');app('undo');const before=m.snapshot();assert.equal(n('preedit',{text:''}).status,'Rejected');assert.deepEqual(m.snapshot(),before);
    send('cancel');assert.equal(new Map(m.snapshot().outcomes).size,1);assert.deepEqual(m.snapshot().tree,src);check(m);
  },
  codeConversionAndHistory(C=IntegratedModel){
    const code=p('code','A');code.type='code';code.props={language:'kotlin'};const para=p('b','B');para.units.push({tokenId:'atom',kind:'inlineAtom',char:'\ufffc',nodeId:'atom',attrs:{italic:true},originSessionId:null});
    const src=flow(code,para),{m,send,app}=driver(src,[1,2],C);send('preedit',{text:''});send('commit',{text:''});assert.equal(m.snapshot().tree.children[0].type,'paragraph');app('undo');assert.deepEqual(m.snapshot().tree,src);app('redo');check(m);
  },
  backwardIslands(C=IntegratedModel){
    const {m,send,app}=driver(flow(p('p','0123456789')),[6,8],C);send('preedit',{text:'CD'});send('retarget',{range:[1,3]});send('preedit',{text:'AB'});send('partialCommit',{length:2});assert.equal(m.snapshot().text,'0AB345CD89');app('undo');send('cancel');assert.equal(m.snapshot().text,'0123456789');app('redo');assert.equal(m.snapshot().text,'0AB3456789');check(m);
  },
  semanticNoOpAfterRetarget(C=IntegratedModel){
    const {m,send}=driver(flow(p('p','abcd')),[1,2],C);send('preedit',{text:'XY'});send('retarget',{range:[0,4]});const before=m.snapshot();send('preedit',{text:'aXYc'});assert.equal(m.snapshot().documentRevision,before.documentRevision);assert.equal(m.snapshot().sessionRevision,before.sessionRevision+1);check(m);
  },
  failedTerminalReentrantContinuation(C=IntegratedModel){
    const {m,send,n,app,drain}=driver(fixture(),[1,4],C);send('preedit',{text:'XY'});const f=app('format',{target:'composition',patch:{bold:true}});let once=false,continuation;
    m.will.push((s,p)=>{if(p.failAt&&!once){once=true;continuation=n('preedit',{text:'Z'});}});
    const r=n('commit',{text:'Q',failAt:'afterCommand:0'});drain();assert.equal(r.status,'Rejected');assert.equal(m.receipt(continuation.ticket).status,'Accepted');assert.equal(m.snapshot().text,'aZd\nTAIL');assert.equal(new Map(m.snapshot().outcomes).get(f.ticket).status,'Pending');send('commit',{text:'W'});assert.equal(new Map(m.snapshot().outcomes).get(f.ticket).status,'Applied');assert.equal(m.snapshot().history.length,2);check(m);
  },
  exactNativeRemoval(C=IntegratedModel){
    const {m,send,app}=driver(flow(p('p','abcd')),[1,2],C);send('preedit',{text:'XY'});send('retarget',{range:[0,4]});send('nativeRemoval',{range:[1,3],text:''});assert.equal(m.snapshot().text,'acd');assert.equal(m.snapshot().history.length,1);app('undo');assert.equal(m.snapshot().text,'abcd');check(m);
  },
  newSessionRebasesHierarchy(C=IntegratedModel){
    const src=fixture(),{m,send,app}=driver(src,[1,4],C);send('commit',{text:'X'});app('selection',{range:[4,8]});send('commit',{text:'NEXT',stamp:{token:'B'}});assert.equal(m.snapshot().text,'aXd\nNEXT');assert.equal(index(m.snapshot().tree).get('nested').parent,'i1');check(m);app('undo');assert.equal(m.snapshot().text,'aXd\nTAIL');app('undo');assert.deepEqual(m.snapshot().tree,src);
  },
  observerIsolation(C=IntegratedModel){
    const {m,send}=driver(fixture(),[1,4],C);m.will.push(()=>{throw Error('ObserverFailure');});send('commit',{text:'X'});assert.equal(m.snapshot().text,'aXd\nTAIL');check(m);
  },
  hostFence(C=IntegratedModel){
    const {m,n,app}=driver(flow(p('p','abc')),[0,0],C);app('abort');const before=m.snapshot();const r=n('commit',{text:'X',stamp:{token:null,fenceVerified:false}});assert.equal(r.reason,'HostFenceRequired');assert.deepEqual(m.snapshot(),before);
  },
  fragmentedBasis(C=IntegratedModel){
    const {m,n,drain}=driver(flow(p('p','abcd')),[0,0],C);n('commit',{range:[2,2],text:'X',stamp:{token:'first'}},{defer:true});const second=n('commit',{range:[1,3],text:'Y',stamp:{token:'second'}},{defer:true});drain();assert.equal(m.snapshot().text,'abXcd');assert.equal(m.receipt(second.ticket).reason,'TargetFragmented');check(m);
  },
  payloadCapture(C=IntegratedModel){
    const {m,send,drain}=driver(fixture(),[1,4],C);send('preedit',{text:'X'});const payload={type:'format',target:'composition',patch:{bold:true}},q=m.dispatch(payload,{defer:true});payload.patch.bold=false;drain();send('commit',{text:'Y'});assert.equal(new Map(m.snapshot().outcomes).get(q.ticket).status,'Applied');assert.equal(m.snapshot().tree.children[0].children[0].children[0].units[1].attrs.bold,true);check(m);
  },
  graphemeFormatUndo(C=IntegratedModel){
    const block=p('p','👩💻');block.units[0].attrs={bold:true};block.units[1].attrs={bold:true};block.units[2].attrs={italic:true};block.units[3].attrs={italic:true};const src=flow(block),{m,send,app}=driver(src,[2,2],C);
    send('preedit',{text:'\u200d'});app('format',{target:'composition',patch:{bold:true}});send('commit',{text:'\u200d'});assert.ok(m.snapshot().tree.children[0].units.every(u=>u.attrs.bold));app('undo');assert.equal(m.snapshot().tree.children[0].units.at(-1).attrs.bold,undefined);app('undo');assert.deepEqual(m.snapshot().tree,src);check(m);
  },
  retainedObjectsAndFreshGeneration(C=IntegratedModel){
    const block=p('p','AB');block.units.splice(1,0,{kind:'inlineAtom',tokenId:'obj',nodeId:'obj',char:'\ufffc',attrs:{italic:true},props:{name:'mention'},originSessionId:null});const src=flow(block),{m,send,app}=driver(src,[0,3],C);
    send('preedit',{text:'X'});send('preedit',{text:'A\ufffcB'});app('undo');send('cancel');assert.deepEqual(m.snapshot().tree,src);assert.equal(m.snapshot().history.length,0);
    app('openNewConnection');send('commit',{text:'Y',stamp:{token:'A'}});assert.equal(m.snapshot().text,'Y');app('undo');assert.deepEqual(m.snapshot().tree,src);check(m);
  },
  oneSettlementPublication(C=IntegratedModel){
    const {m,send,app}=driver(fixture(),[1,4],C);send('preedit',{text:'X'});app('format',{target:'composition',patch:{bold:true}});app('undo');const before=m.snapshot(),observed=[];m.did.push(s=>observed.push(s));send('commit',{text:'X'});
    assert.equal(observed.length,1);assert.equal(m.snapshot().documentRevision,before.documentRevision);assert.equal(m.snapshot().sessionRevision,before.sessionRevision+1);assert.equal(m.snapshot().history.length,1);assert.equal(m.snapshot().redo.length,1);assert.equal(m.snapshot().tree.children[0].children[0].children[0].units[1].attrs.bold,undefined);check(m);
  },
  turnoverRollback(C=IntegratedModel){
    const src=fixture(),{m,send,app}=driver(src,[1,4],C);send('preedit',{text:'XY'});app('undo');const before=m.snapshot(),result=m.dispatch({type:'openNewConnection',failAt:'beforePublication'});assert.equal(result.status,'Rejected');assert.deepEqual(m.snapshot(),before);send('commit',{text:'Z'});assert.deepEqual(m.snapshot().tree,src);check(m);
  },
  oldDuplicateAfterEpoch(C=IntegratedModel){
    const {m,app}=driver(fixture(),[1,4],C),input={type:'commit',text:'X',stamp:{epoch:1,generation:1,sequence:1,token:'A',verified:true,fenceVerified:true}};m.dispatch(input);app('openNewConnection');const before=m.snapshot();assert.equal(m.dispatch(input).reason,'StaleEpoch');assert.deepEqual(m.snapshot(),before);
  },
  disposalAndForeignFlow(C=IntegratedModel){
    const {m,send,n,app}=driver(fixture(),[1,4],C);const before=m.snapshot();assert.equal(n('preedit',{text:'wrong',flowId:'cell',stamp:{token:'foreign'}}).reason,'FlowMismatch');assert.deepEqual(m.snapshot(),before);send('preedit',{text:'X'});const q=app('undo');app('dispose');assert.equal(m.snapshot().text,'aXd\nTAIL');assert.equal(m.snapshot().history.length,1);assert.equal(new Map(m.snapshot().outcomes).get(q.ticket).reason,'Disposed');assert.equal(n('commit',{text:'Y',stamp:{token:'new'}}).reason,'ConnectionClosed');check(m);
  },
  unrelatedFormatOnCancellation(C=IntegratedModel){
    const {m,send,app}=driver(fixture(),[1,4],C);send('preedit',{text:'XY'});const q=app('format',{range:[5,9],patch:{bold:true}});send('cancel');assert.equal(m.snapshot().text,'ab\ncd\nTAIL');assert.ok(index(m.snapshot().tree).get('t').node.units.every(u=>u.attrs.bold));assert.equal(new Map(m.snapshot().outcomes).get(q.ticket).status,'Applied');app('undo');assert.deepEqual(m.snapshot().tree,fixture());check(m);
  },
  repeatedStartRetargets(C=IntegratedModel){
    const {m,send}=driver(flow(p('p','abcd')),[0,1],C);send('startComposition');send('startComposition',{range:[2,3]});assert.deepEqual(m.snapshot().markedRange,[2,3]);send('preedit',{text:'X'});assert.equal(m.snapshot().text,'abXd');check(m);
  },
  explicitPreeditCannotBypassAdmission(C=IntegratedModel){
    const block=p('p','AB');block.units.splice(1,0,{kind:'inlineAtom',tokenId:'obj',nodeId:'obj',char:'\ufffc',attrs:{},originSessionId:null});const {m,send,n}=driver(flow(block),[0,1],C);send('preedit',{text:'X'});const before=m.snapshot();const result=n('preedit',{range:[0,3],text:'Q'});assert.equal(result.reason,'ProtectedStructuralBoundary');assert.deepEqual(m.snapshot(),before);
  },
  retargetRetainedSelectedAtom(C=IntegratedModel){
    const block=p('p','AB');block.units.splice(1,0,{kind:'inlineAtom',tokenId:'obj',nodeId:'obj',char:'\ufffc',attrs:{},originSessionId:null});const src=flow(block),{m,send}=driver(src,[0,3],C);send('startComposition');send('retarget',{range:[1,2]});send('preedit',{text:'\ufffc'});assert.deepEqual(m.snapshot().tree,src);send('cancel');assert.deepEqual(m.snapshot().tree,src);check(m);
  },
};

export function namedTests(){const results=[];for(const [name,fn]of Object.entries(cases)){try{fn();results.push({name,status:'PASS'});}catch(e){results.push({name,status:'FAIL',error:e.stack});}}return {passed:results.every(r=>r.status==='PASS'),cases:results};}

export function generatedIntegration(){let count=0;
  const shapes=[flow(p('a','ab'),p('b','cd')),fixture(),flow(p('a','ab'),c('q','quote',[p('b','cd')]))];
  for(const src of shapes){const source=raw(project(src));for(let a=0;a<source.length;a++)for(let b=a+1;b<=source.length;b++)for(const end of ['commit','cancel','partial']){
    const {m,send,app}=driver(src,[b,a]);send('preedit',{text:'XY'});app('format',{target:'composition',patch:{bold:true}});app('undo');
    if(end==='partial')send('partialCommit',{length:1});send(end==='commit'?'commit':'cancel',end==='commit'?{text:'Z'}:{});
    assert.equal(m.snapshot().text,end==='commit'?source.slice(0,a)+'Z'+source.slice(b):end==='partial'?source.slice(0,a)+'X'+source.slice(b):source);
    check(m);if(end!=='cancel'){app('undo');assert.deepEqual(m.snapshot().tree,src);app('redo');check(m);}else assert.deepEqual(m.snapshot().tree,src);count++;
  }}return {cases:count,oracle:'fixture roots + independent raw splice; all events through owner dispatcher'};
}

export function ledgerExploration(){let count=0;
  const seqs=[[]];for(let depth=1;depth<=3;depth++)for(const seq of seqs.filter(x=>x.length===depth-1))for(const e of ['undo','bold','noop'])seqs.push([...seq,e]);
  for(const seq of seqs)for(const variant of ['dirty','partial','cleanThenCommit'])for(const terminal of ['commit','cancel']){
    const src=fixture(),{m,send,app}=driver(src,[1,4]);send('startComposition');if(variant!=='cleanThenCommit')send('preedit',{text:'XY'});if(variant==='partial')send('partialCommit',{length:1});
    for(const e of seq)if(e==='undo')app('undo');else app('format',{target:'composition',patch:e==='bold'?{bold:true}:{}});
    send(terminal,terminal==='commit'?{text:'Z'}:{});check(m);
    assert.equal(m.snapshot().pending.length,0);assert.equal(m.snapshot().outcomes.length,seq.length);count++;
  }return {cases:count,maxQueuedCommands:3};
}

export function scheduling(){const {m,n,drain,scheduled}=driver(flow(p('p','')),[0,0]);
  for(let i=0;i<130;i++)n('commit',{text:'x',stamp:{token:`t${i}`}},{defer:true});
  m.flush();assert.equal(m.snapshot().sessionRevision,64);assert.equal(scheduled.length,1);drain();assert.equal(m.snapshot().sessionRevision,130);assert.equal(m.snapshot().text.length,130);check(m);return {requests:130,firstBudget:64};
}

export function integratedMutations(){
  class CapturedContinuation extends IntegratedModel{_target(q){if(q.reservation&&!q.initial)return {range:q.basis.selection};return super._target(q);}}
  class DoubleUndo extends IntegratedModel{_drainLedger(a,...rest){a.undoSawDirty=false;return super._drainLedger(a,...rest);}}
  class LostRollbackHistory extends IntegratedModel{_restore(s){const history=this.history;super._restore(s);this.history=history;this._publish();}}
  class ReusedUnitNamespace extends IntegratedModel{_start(q,target){if(q.reservation)this.registry.get(q.reservation).engineId='same';return super._start(q,target);}}
  class GuessedFence extends IntegratedModel{_validateStamp(q){this.hostFence=true;return super._validateStamp(q);}}
  class ObserverVeto extends IntegratedModel{_reduce(q){if(this.will.length)failObserver();return super._reduce(q);}}
  class BypassStructuralGuard extends IntegratedModel{_retargetChanged(){}}
  class IgnoreRepeatedStart extends IntegratedModel{_reduce(q){if(q.payload.type==='startComposition'&&this.active)return;return super._reduce(q);}}
  function failObserver(){throw Error('ObserverFailure');}
  const pairs=[['queuedStructuralContinuation',CapturedContinuation],['cancelDoesNotDoubleUndo',DoubleUndo],['atomicSettlementRollback',LostRollbackHistory],['noOpAndIdentityReuse',ReusedUnitNamespace],['hostFence',GuessedFence],['observerIsolation',ObserverVeto],['explicitPreeditCannotBypassAdmission',BypassStructuralGuard],['repeatedStartRetargets',IgnoreRepeatedStart]];
  const evidence=[];
  for(const [name,Mutant]of pairs){cases[name](IntegratedModel);let error;try{cases[name](Mutant);}catch(e){error=e;}assert.equal(error?.code,'ERR_ASSERTION',`${name}: mutant must fail a behavioral assertion, not crash`);evidence.push({case:name,mutant:Mutant.name,message:error.message});}
  return {controls:pairs.length,killed:pairs.length,evidence};
}

export function repeatedSessions(){
  const {m,send,n,app}=driver(fixture(),[1,4]);let state=0x13579bd,sessions=0,injected=0,stale=0;
  const rand=n=>{state^=state<<13;state^=state>>>17;state^=state<<5;return (state>>>0)%n;};
  for(let i=0;i<80;i++){
    const length=m.snapshot().text.length,a=rand(length+1),b=a+rand(length-a+1);app('selection',{range:i%2?[b,a]:[a,b]});
    const before=m.snapshot(),char=String.fromCharCode(0x500+i),token=`long:${i}`;
    send('preedit',{text:char+char,stamp:{token}});const f=app('format',{target:'composition',patch:{bold:true}});app('undo');
    const mode=rand(3);if(mode===2)send('partialCommit',{length:1,stamp:{token}});
    const end=mode===0?'commit':'cancel',payload=end==='commit'?{text:char,stamp:{token}}:{stamp:{token}};
    if(i%8===0){const snapshot=m.snapshot(),failure=n(end,{...payload,failAt:'afterCommand:0'});assert.equal(failure.status,'Rejected');assert.deepEqual(m.snapshot(),snapshot);injected++;}
    send(end,payload);
    assert.equal(m.snapshot().text,mode===1?before.text:before.text.slice(0,a)+char+before.text.slice(b));check(m);
    assert.notEqual(new Map(m.snapshot().outcomes).get(f.ticket).status,'Pending');
    const saved=m.snapshot(),late=n('commit',{text:'late',stamp:{token}});assert.equal(late.reason,'StaleCompositionSession');assert.deepEqual(m.snapshot(),saved);stale++;
    if(mode!==1&&i%3===0){app('undo');assert.deepEqual(m.snapshot().tree,before.tree);app('redo');check(m);}
    sessions++;
  }
  return {sessions,seed:'0x13579bd',failureRetries:injected,staleDeliveries:stale};
}
