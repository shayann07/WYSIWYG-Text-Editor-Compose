import assert from 'node:assert/strict';
import {IntegratedModel} from './integrated-model.mjs';
import {IntegratedModel as Old} from '../run-20261015-17/integrated-model.mjs';
import {StructuralModel as OldWorker} from '../run-20261015-17/structural-model.mjs';
import {textBlock as p,container as c,index,leaves} from './structural-model.mjs';
import {textLocation} from './awt-contract-model.mjs';
const flow=(...nodes)=>c('f','flow',nodes),li=(id,...nodes)=>c(id,'item',nodes),list=(...nodes)=>c('L','list',nodes);
export function driver(root=flow(p('p','abc')),selection=[1,2],C=IntegratedModel,options={}){
  const scheduled=[],m=new C(root,selection,{schedule:fn=>scheduled.push(fn),...options});let sequence=0,ordinal=0;const ordinals=new Map();
  const n=(type,extra={},defer=false)=>{
    const stamp={epoch:m.snapshot().epoch,generation:m.snapshot().generation,sequence:++sequence,token:'A',verified:true,fenceVerified:true,...extra.stamp};
    if(stamp.token!=null&&stamp.sessionOrdinal==null){const k=JSON.stringify([stamp.epoch,stamp.generation,stamp.token]);if(!ordinals.has(k))ordinals.set(k,++ordinal);stamp.sessionOrdinal=ordinals.get(k);}
    return m.dispatch({type,...extra,stamp},{defer});
  };
  return {m,n,drain:()=>{m.flush();while(scheduled.length)scheduled.shift()();}};
}
const ok=r=>assert.equal(r.status,'Accepted',JSON.stringify(r));

export const repairCases={
  terminalSelections(C=IntegratedModel){
    for(const event of ['commit','unmark','completeSettlement']){
      const {m,n}=driver(undefined,undefined,C);ok(n('preedit',{text:'X'}));if(event==='completeSettlement')ok(n('unmark',{settled:false}));
      const evidence=m.snapshot().active.fence,before=m.snapshot();const bad=n(event,{selection:[999,999],evidence});assert.equal(bad.reason,'InvalidSelection');assert.deepEqual(m.snapshot(),before);
      ok(n(event,{selection:[3,0],evidence}));assert.deepEqual(m.snapshot().selection,[3,0]);assert.deepEqual(m.snapshot().history.at(-1).after.selection,[3,0]);
      ok(m.dispatch({type:'undo'}));assert.deepEqual(m.snapshot().selection,[1,2]);ok(m.dispatch({type:'redo'}));assert.deepEqual(m.snapshot().selection,[3,0]);
    }
  },
  repeatedListJoins(C=IntegratedModel){
    const root=flow(list(li('i1',p('a','A')),li('i2',p('b','B'),p('c','C')),li('i3',p('d','D'),p('t','T'))));const {m,n}=driver(root,[1,7],C);
    ok(n('startComposition'));ok(n('preedit',{text:'B\nC\nD'}));assert.equal(index(m.snapshot().tree).get('c').parent,'i1');
    ok(n('retarget',{range:[4,5]}));ok(n('preedit',{text:''}));assert.equal(m.snapshot().text,'AB\nCD\nT');assert.equal(index(m.snapshot().tree).get('t').parent,'i1');assert.deepEqual(m.snapshot().tree.children[0].children.map(x=>x.id),['i1']);
    ok(n('unmark'));ok(m.dispatch({type:'undo'}));assert.deepEqual(m.snapshot().tree,root);ok(m.dispatch({type:'redo'}));assert.equal(index(m.snapshot().tree).get('t').parent,'i1');
  },
  malformedRange(C=IntegratedModel){
    const {m,n}=driver(flow(p('p','abcd')),[0,0],C),before=m.snapshot();assert.equal(n('commit',{text:'X',range:[0,1,2]}).reason,'InvalidTargetRange');assert.deepEqual(m.snapshot(),before);
  },
  domainCompatibility(C=IntegratedModel){
    const code=p('b','cd');code.type='code';const {m,n}=driver(flow(p('a','ab'),code),[0,1],C);ok(n('preedit',{text:'X'}));const before=m.snapshot();assert.equal(n('retarget',{range:[0,4]}).reason,'ProtectedStructuralBoundary');assert.deepEqual(m.snapshot(),before);
  }
};

export function reproduceBaseline(){const evidence=[];for(const [name,fn]of Object.entries(repairCases)){let e;try{fn(Old);}catch(x){e=x;}assert.equal(e?.code,'ERR_ASSERTION',name);evidence.push({name,reproduced:true,message:e.message});}return evidence;}
export function regressionTests(){for(const fn of Object.values(repairCases))fn();let cases=4;
  for(const range of [[],[0],[0,1,2],'01',{},[NaN,1],[Infinity,1],[.5,1],[-1,1],[0,99],new Array(2)]){
    const {m,n}=driver(),before=m.snapshot();assert.equal(n('commit',{text:'X',range}).reason,'InvalidTargetRange');assert.deepEqual(m.snapshot(),before);cases++;
  }
  for(const selection of [[],[0,1,2],[NaN,1],new Array(2)]){
    const {m,n}=driver();ok(n('preedit',{text:'X'}));const before=m.snapshot();assert.equal(n('unmark',{selection}).reason,'InvalidSelection');assert.deepEqual(m.snapshot(),before);cases++;
  }
  // Same callback, different source frames: post-replacement selection maps once.
  {const {m,n,drain}=driver(flow(p('p','abcd')),[0,0]);n('commit',{range:[0,0],text:'X',stamp:{token:'one'}},true);const q=n('nativeRemoval',{range:[2,3],text:'YZ',selection:[4,4],stamp:{token:'two'}},true);drain();ok(m.receipt(q.ticket));assert.equal(m.snapshot().text,'XabYZd');assert.deepEqual(m.snapshot().selection,[5,5]);cases++;}
  // A whole target in another class/container is still a domain crossing.
  for(const quoted of [false,true]){
    const b=p('b','cd');if(!quoted)b.type='code';const root=flow(p('a','ab'),quoted?c('q','quote',[b]):b),{m,n}=driver(root,[0,1]);ok(n('preedit',{text:'X'}));assert.equal(n('retarget',{range:[3,4]}).reason,'ProtectedStructuralBoundary');cases++;
  }
  // Initial user-selected code/prose boundary remains admitted, including retarget.
  {const b=p('b','cd');b.type='code';const {m,n}=driver(flow(p('a','ab'),b),[0,5]);ok(n('startComposition'));ok(n('retarget',{range:[3,5]}));ok(n('preedit',{text:'cd'}));ok(n('cancel'));assert.equal(m.snapshot().text,'ab\ncd');cases++;}
  // Terminal selection precedes the queued history batch, never an unvalidated afterthought.
  {const {m,n}=driver();ok(n('preedit',{text:'X'}));ok(m.dispatch({type:'format',target:'composition',patch:{bold:true}}));ok(m.dispatch({type:'undo'}));const before=m.snapshot();assert.equal(n('commit',{selection:[99,99]}).reason,'InvalidSelection');assert.deepEqual(m.snapshot(),before);ok(n('commit',{selection:[0,0]}));assert.deepEqual(m.snapshot().selection,[0,0]);assert.deepEqual(m.snapshot().history.at(-1).after.selection,[0,0]);cases++;}
  // Redo has an explicit settlement-required policy; cancellation preserves its branch.
  {const {m,n}=driver(flow(p('p','')),[0,0]);ok(n('commit',{text:'X',stamp:{token:null}}));ok(m.dispatch({type:'undo'}));ok(n('preedit',{text:'Y'}));const before=m.snapshot();assert.equal(m.dispatch({type:'redo'}).reason,'SettlementRequired');assert.deepEqual(m.snapshot(),before);ok(n('cancel'));ok(m.dispatch({type:'redo'}));assert.equal(m.snapshot().text,'X');cases++;}
  {const {m,n}=driver(),before=m.snapshot();assert.equal(n('preedit',{text:'X',stamp:{token:null}}).reason,'AmbiguousHostProvenance');assert.deepEqual(m.snapshot(),before);cases++;}
  return {cases};
}

export function awtTests(){
  const last=[{lineOrder:0,x:10,y:20,width:50,height:12,direction:'ltr'},{lineOrder:1,x:25,y:40,width:30,height:12,direction:'ltr'},{lineOrder:1,x:75,y:40,width:20,height:12,direction:'ltr'}];let cases=0;
  for(const offset of [null,{charIndex:999,trailing:false},{charIndex:-500,trailing:true}]){assert.deepEqual(textLocation({selectionLines:last,offset}),{x:25,y:40,width:0,height:12});cases++;}
  assert.deepEqual(textLocation({selectionLines:last.map(l=>({...l,direction:'rtl'}))}),{x:95,y:40,width:0,height:12});cases++;
  assert.deepEqual(textLocation({selectionLines:[{lineOrder:0,x:50,y:20,width:14,height:50,orientation:'vertical'},{lineOrder:1,x:30,y:35,width:14,height:40,orientation:'vertical'}]}),{x:30,y:35,width:14,height:0});cases++;
  assert.deepEqual(textLocation({offset:{charIndex:400},collapsedCaret:{x:12,y:30,lineExtent:16}}),{x:12,y:30,width:0,height:16});cases++;
  let received;const hit={charIndex:1,trailing:true};assert.deepEqual(textLocation({markedLength:3,offset:hit,composedCaret:h=>{received=h;return {x:75,y:90,lineExtent:18};}}),{x:75,y:90,width:0,height:18});assert.equal(received,hit);cases++;
  assert.throws(()=>textLocation({markedLength:3,offset:{charIndex:4},composedCaret:()=>{throw Error('must not query');}}),/InvalidComposedHit/);cases++;
  return {cases,scope:'layout-coordinate contract with synthetic frames, not a native AWT host'};
}

export function retentionTests(){let cases=0;
  const limits={receiptEntries:2,basisEntries:1,historyEntries:3,outcomeEntries:2};
  // Full editor snapshots/history roots never live in the receipt or basis caches.
  {const {m,n}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:limits});let first;
    for(let i=0;i<24;i++){const r=n('commit',{text:'x',stamp:{token:null}});ok(r);first??=r.ticket;}
    const stats=m.retentionStats();assert.ok(stats.receipts<=2);assert.ok(stats.bases<=1);assert.equal(stats.history,3);assert.ok(stats.historyEvicted>0);assert.equal(stats.registry,0);assert.equal(stats.sequenceContexts,1);
    for(const r of m.receipts.values())assert.equal('snapshot' in r,false);
    for(const b of m.bases.values()){assert.equal('tree' in b,false);assert.equal('history' in b,false);assert.ok(b.units.every(u=>Object.keys(u).join()==='tokenId'));}
    const before=m.snapshot();const expired=m.dispatch({type:'commit',text:'x',stamp:{epoch:1,generation:1,sequence:1,token:null,verified:true,fenceVerified:true}});assert.equal(expired.status,'DuplicateReceiptExpired');assert.deepEqual(m.snapshot(),before);assert.equal(m.receipt(first).status,'ReceiptExpired');cases++;
  }
  // AfterTicket registration pins the compact producer basis across cache/history eviction.
  for(const producerPending of [false,true]){
    const {m,n,drain}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:limits});const first=n('commit',{text:'X',stamp:{token:null}},producerPending);
    for(let i=0;i<12;i++)n('commit',{range:[0,0],text:'z',stamp:{token:null}},true);
    const dep=n('commit',{afterTicket:first.ticket,range:[1,1],text:'Y',stamp:{token:null}},true);drain();ok(m.receipt(dep.ticket));assert.equal(m.snapshot().text,'z'.repeat(12)+'XY');assert.ok(m.retentionStats().bases<=1);assert.equal(m.retentionStats().pinnedTickets,0);assert.equal(m.snapshot().history.length,3);cases++;
  }
  {const {m,n}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:limits});const first=n('commit',{text:'a',stamp:{token:null}});for(let i=0;i<6;i++)ok(n('commit',{text:'b',stamp:{token:null}}));const before=m.snapshot();assert.equal(n('commit',{afterTicket:first.ticket,text:'X',stamp:{token:null}}).reason,'BasisExpired');assert.deepEqual(m.snapshot(),before);cases++;}
  // An ordinal watermark rejects a retired session even with a fresh wire sequence.
  {const {m,n}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:limits});ok(n('commit',{text:'A'}));for(let i=0;i<8;i++)ok(n('commit',{text:'x',stamp:{token:null}}));const before=m.snapshot();assert.equal(n('preedit',{text:'late'}).reason,'StaleCompositionSession');assert.deepEqual(m.snapshot(),before);cases++;}
  // Failed producer and independent reentrant work keep exact outcomes, then release pins.
  {const {m,n,drain}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:limits});const a=n('commit',{text:'X',failAt:'beforePublication'},true),b=n('commit',{text:'Y',afterTicket:a.ticket,stamp:{token:null}},true);drain();assert.equal(m.receipt(b.ticket).reason,'BasisDependencyFailed');assert.equal(m.snapshot().text,'');assert.equal(m.retentionStats().pinnedTickets,0);assert.ok(!m.bases.has(a.ticket));cases++;}
  {const {m,n,drain}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:{...limits,pendingRequests:3}});for(let i=0;i<3;i++)assert.equal(n('commit',{text:'X',stamp:{token:null}},true).status,'Deferred');assert.equal(n('commit',{text:'Y',stamp:{token:null}},true).reason,'AdmissionCapacityExceeded');drain();assert.equal(m.snapshot().text,'XXX');cases++;}
  {const {m,n}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:{...limits,pendingBytes:1000}});const before=m.snapshot();assert.equal(n('commit',{text:'x'.repeat(2000),stamp:{token:null}}).reason,'AdmissionCapacityExceeded');assert.deepEqual(m.snapshot(),before);assert.ok([...m.wires.values()].every(w=>w.fingerprint.length===64));cases++;}
  // Producing an oversized pinned basis refuses the producer atomically.
  {const {m,n,drain}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:{...limits,pinnedBasisBytes:512}});const a=n('commit',{text:'X'.repeat(100),stamp:{token:null}},true),b=n('commit',{afterTicket:a.ticket,text:'Y',stamp:{token:null}},true);drain();assert.equal(m.receipt(a.ticket).reason,'BasisCapacityExceeded');assert.equal(m.receipt(b.ticket).reason,'BasisDependencyFailed');assert.equal(m.snapshot().text,'');cases++;}
  // Retention trim cannot remove groups while a settlement ledger still references them.
  {const {m,n}=driver(flow(p('p','')),[0,0],IntegratedModel,{retention:{...limits,historyEntries:1}});ok(n('commit',{text:'a',stamp:{token:null}}));ok(n('preedit',{text:'b'}));ok(m.dispatch({type:'format',target:'composition',patch:{bold:true}}));ok(m.dispatch({type:'undo'}));ok(n('commit',{text:'b'}));assert.equal(m.snapshot().text,'ab');assert.ok(m.snapshot().history.length+m.snapshot().redo.length<=1);cases++;}
  // Check expanded identity-target storage, not only the small command payload.
  {const {m,n}=driver(flow(p('p','a'.repeat(100))),[0,1],IntegratedModel,{retention:{...limits,pendingBytes:1500}});ok(n('preedit',{text:'X'}));ok(m.dispatch({type:'format',range:[0,100],patch:{bold:true}}));const before=m.snapshot();assert.equal(m.dispatch({type:'format',range:[0,100],patch:{italic:true}}).reason,'AdmissionCapacityExceeded');assert.deepEqual(m.snapshot(),before);cases++;}
  // Long lived session: no callback-sized growth in the worker's private history/cache.
  {const {m,n}=driver(flow(p('p','abc')),[1,2],IntegratedModel,{retention:limits});for(let i=0;i<100;i++)ok(n('preedit',{text:i%2?'X':'Y'}));assert.equal(m.kernel._editHistory.length,0);assert.equal(m.kernel.undoStack.length,0);assert.equal(m.kernel.receiptCache.size,0);assert.equal(m.retentionStats().registry,1);ok(n('cancel'));assert.equal(m.retentionStats().registry,0);cases++;}
  {const {m,n}=driver(flow(p('p','abc')),[0,0]);const old=n('commit',{text:'X',stamp:{token:null}});ok(m.dispatch({type:'selection',range:[4,4]}));const before=m.snapshot();assert.equal(n('commit',{afterTicket:old.ticket,range:[0,0],text:'Y',stamp:{token:null}}).reason,'BasisIdentityMismatch');assert.deepEqual(m.snapshot(),before);cases++;}
  return {cases,limits:'deterministic serialized-byte/count charges; not JVM or native heap measurements'};
}

export function retentionSoak(){
  const {m,n}=driver(flow(p('p','a')),[0,1],IntegratedModel,{retention:{receiptEntries:4,basisEntries:2,historyEntries:3,outcomeEntries:2}}),samples=[];
  for(let i=0;i<400;i++){ok(n('commit',{range:[0,1],text:i%2?'X':'Y',stamp:{token:null}}));if(i%100===99)samples.push(m.retentionStats());}
  for(const s of samples){assert.ok(s.receipts<=4&&s.bases<=2&&s.history<=3&&s.registry===0&&s.sequenceContexts===1&&s.sessionContexts<=1);}
  return {edits:400,samples,scope:'engine-owned record counts at fixed document size; not allocator/GC proof'};
}

export function structuralChains(){let cases=0;
  for(let count=2;count<=6;count++)for(const terminal of ['commit','cancel']){
    const root=flow(list(...Array.from({length:count},(_,i)=>li('i'+i,p('a'+i,String.fromCharCode(65+i)),p('b'+i,String.fromCharCode(97+i))))));
    const {m,n}=driver(root,[0,4*count-1]);ok(n('startComposition'));
    for(let i=1;i<count;i++){
      const pos=m.snapshot().units.findIndex(u=>u.tokenId==='boundary:a'+i);ok(n('retarget',{range:[pos,pos+1]}));ok(n('preedit',{text:''}));
      const ix=index(m.snapshot().tree);for(let j=0;j<=i;j++)assert.equal(ix.get('b'+j).parent,'i0');
    }
    ok(n(terminal==='commit'?'unmark':'cancel'));
    if(terminal==='cancel')assert.deepEqual(m.snapshot().tree,root);
    else {assert.deepEqual(m.snapshot().tree.children[0].children.map(x=>x.id),['i0']);ok(m.dispatch({type:'undo'}));assert.deepEqual(m.snapshot().tree,root);ok(m.dispatch({type:'redo'}));assert.deepEqual(m.snapshot().tree.children[0].children.map(x=>x.id),['i0']);}
    cases++;
  }
  return {cases,maxItems:6};
}

export function midStageBasis(){
  class Reentry extends IntegratedModel{_call(p){const r=super._call(p);if(p.type==='preedit'&&!this.once){this.once=true;this.mid=this.dispatch({type:'format',range:[6,10],patch:{bold:true}});}return r;}}
  const root=flow(p('a','ab'),p('b','cd'),p('t','TAIL')),{m,n,drain}=driver(root,[1,4],Reentry);ok(n('preedit',{text:'X'}));drain();ok(m.receipt(m.mid.ticket));ok(n('unmark'));assert.ok(index(m.snapshot().tree).get('t').node.units.every(u=>u.attrs.bold));
  return {cases:1,scope:'reentrant capture after private structural mutation, before outer publication'};
}

export function repairMutations(){
  class IgnoreSelection extends IntegratedModel{_nativeSelection(){}}
  class OldStructure extends IntegratedModel{constructor(root,selection,opts){super(root,selection,opts);this.kernel=new OldWorker(root,selection);this._publish();}}
  class CoerceRange extends IntegratedModel{_target(q){if(Array.isArray(q.payload.range))q={...q,payload:{...q.payload,range:[Math.min(...q.payload.range),Math.max(...q.payload.range)]}};return super._target(q);}}
  class NoEviction extends IntegratedModel{_trimRetention(){}}
  class RootReceipt extends IntegratedModel{_execute(q){super._execute(q);const r=this.receipts.get(q.ticket);if(r?.status==='Accepted')this.receipts.set(q.ticket,{...r,snapshot:this.snapshot()});}}
  class LostPin extends IntegratedModel{_dependencyPins(){return new Set();}}
  class ForgotSession extends IntegratedModel{dispatch(...a){this.sessionWatermarks.clear();return super.dispatch(...a);}}
  class ReplayExpired extends IntegratedModel{dispatch(...a){this.sequenceWatermarks.clear();this.lastSequences.clear();return super.dispatch(...a);}}
  const bounded=C=>{const {m,n}=driver(flow(p('p','')),[0,0],C,{retention:{receiptEntries:2,basisEntries:1,historyEntries:3}});for(let i=0;i<12;i++)ok(n('commit',{text:'x',stamp:{token:null}}));assert.ok(m.retentionStats().receipts<=2);assert.ok(m.retentionStats().bases<=1);assert.ok([...m.receipts.values()].every(r=>!('snapshot'in r)));};
  const pin=C=>{const {m,n,drain}=driver(flow(p('p','')),[0,0],C,{retention:{receiptEntries:1,basisEntries:1}});const a=n('commit',{text:'X',stamp:{token:null}},true);for(let i=0;i<4;i++)n('commit',{text:'z',stamp:{token:null}},true);const b=n('commit',{text:'Y',afterTicket:a.ticket,range:[1,1],stamp:{token:null}},true);drain();ok(m.receipt(b.ticket));};
  const session=C=>{const {m,n}=driver(flow(p('p','')),[0,0],C);ok(n('commit',{text:'A'}));assert.equal(n('preedit',{text:'late'}).reason,'StaleCompositionSession');};
  const expired=C=>{const {m,n}=driver(flow(p('p','')),[0,0],C,{retention:{receiptEntries:1}});for(let i=0;i<4;i++)ok(n('commit',{text:'x',stamp:{token:null}}));assert.equal(m.dispatch({type:'commit',text:'x',stamp:{epoch:1,generation:1,sequence:1,token:null,verified:true,fenceVerified:true}}).status,'DuplicateReceiptExpired');};
  const specs=[['terminal selection',repairCases.terminalSelections,IgnoreSelection],['working ancestry',repairCases.repeatedListJoins,OldStructure],['range shape',repairCases.malformedRange,CoerceRange],['domain class',repairCases.domainCompatibility,OldStructure],['bounded caches',bounded,NoEviction],['root-free receipts',bounded,RootReceipt],['dependency pin',pin,LostPin],['retired session',session,ForgotSession],['expired wire',expired,ReplayExpired]];
  const evidence=[];for(const [name,check,Mutant]of specs){check(IntegratedModel);let error;try{check(Mutant);}catch(e){error=e;}assert.equal(error?.code,'ERR_ASSERTION',name);evidence.push({name,mutant:Mutant.name,message:error.message});}
  return {controls:specs.length,killed:specs.length,evidence};
}
