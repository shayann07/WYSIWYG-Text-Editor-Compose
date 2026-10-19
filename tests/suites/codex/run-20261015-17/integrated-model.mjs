// Integrated EXECUTABLE SPECIFICATION. Full copies/arrays, synthetic host stamps.
// One public owner publishes admission + structural state + engine-owned history.
import {StructuralModel,clone,project,raw,leaves,validateTree} from './structural-model.mjs';
import {immutableCopy,mapRange,mapRequest} from './request-contract.mjs';

const nativeTypes=new Set(['startComposition','preedit','retarget','partialCommit','unmark','completeSettlement','commit','cancel','nativeRemoval']);
const fail=reason=>{throw Error(reason);};
const keyOf=s=>JSON.stringify([s.epoch,s.generation,s.token]);
const wireOf=s=>JSON.stringify([s.epoch,s.generation,s.sequence]);
function semanticNode(n){
  const out={id:n.id,type:n.type,props:n.props??{}};
  if(n.children)out.children=n.children.map(semanticNode);
  else if(n.units){out.emptyBlockAttributes=n.emptyBlockAttributes??{};out.units=n.units.map(({tokenId,originSessionId,...u})=>({...u,kind:u.kind??'text'}));}
  else Object.assign(out,n);
  return out;
}
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
export const semantic=root=>JSON.stringify(canonical(semanticNode(root)));
const equal=(a,b)=>semantic(a)===semantic(b);
const validRange=(r,n)=>Array.isArray(r)&&r.length===2&&r.every(Number.isInteger)&&r.every(x=>x>=0&&x<=n);
const bundle=k=>({tree:clone(k.tree()),selection:[...k.selection]});

export class IntegratedModel {
  constructor(root,selection=[0,0],{schedule=fn=>setImmediate(fn)}={}){
    this.kernel=new StructuralModel(root,selection);
    this.epoch=1;this.generation=1;this.selectionGeneration=0;this.hostFence=true;this.closed=false;
    this.documentRevision=0;this.sessionRevision=0;this.active=null;
    this.registry=new Map();this.queue=[];this.receipts=new Map();this.wires=new Map();this.lastSequences=new Map();
    this.history=[];this.redo=[];this.pending=[];this.outcomes=new Map();this.historySerial=0;this.fenceSerial=0;this.ticketSerial=0;
    this.busy=false;this.scheduled=false;this.schedule=schedule;this.will=[];this.did=[];
    this._publish();
  }
  snapshot(){return this.published;}
  receipt(ticket){return this.receipts.get(ticket)??immutableCopy({status:'Deferred',ticket});}
  _publish(){
    this.published=immutableCopy({tree:this.kernel.tree(),committedTree:this.kernel.committedTree(),units:this.kernel.snapshot().units,
      text:this.kernel.docText,selection:this.kernel.selection,markedRange:this.kernel.markedRange,
      epoch:this.epoch,generation:this.generation,selectionGeneration:this.selectionGeneration,hostFence:this.hostFence,closed:this.closed,
      documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,
      active:this.active?{key:this.active.key,engineId:this.active.engineId,fence:this.active.fence,unmarkedExtent:this.active.unmarkedExtent}:null,
      history:this.history,redo:this.redo,outcomes:[...this.outcomes],pending:this.pending.map(q=>({ticket:q.ticket,type:q.payload.type}))});
  }
  // All requests, including reentrant application commands, use this admission.
  dispatch(input,{defer=false}={}){
    const payload=immutableCopy(input),s=payload.stamp;
    const ticket=++this.ticketSerial;
    if(nativeTypes.has(payload.type)){
      if(!s||!s.verified||![s.epoch,s.generation,s.sequence].every(Number.isInteger)||s.sequence<0)return this._reject(ticket,'AmbiguousHostProvenance');
      if(s.epoch<this.epoch||s.epoch===this.epoch&&s.generation<this.generation)return this._reject(ticket,s.epoch!==this.epoch?'StaleEpoch':'StaleGeneration');
      const wire=wireOf(s),existing=this.wires.get(wire),fingerprint=JSON.stringify(payload);
      if(existing){
        if(existing.fingerprint!==fingerprint)return this._reject(ticket,'SequenceCollision');
        const prior=this.receipts.get(existing.ticket);
        return prior?immutableCopy({status:'Duplicate',ticket:existing.ticket,previous:prior}):immutableCopy({status:'Deferred',ticket:existing.ticket});
      }
      this.wires.set(wire,{ticket,fingerprint});
    }
    let reservation=null,initial=false;
    if(nativeTypes.has(payload.type)&&s.token!=null){
      reservation=keyOf(s);
      if(!this.registry.has(reservation)){
        initial=true;this.registry.set(reservation,{status:'Reserved',initiator:ticket,engineId:`session:${ticket}`});
      }
    }
    const q={ticket,payload,basis:this.published,reservation,initial};
    this.queue.push(q);
    if(!defer&&!this.busy)this.flush();
    return this.receipt(ticket);
  }
  flush(budget=64){
    if(this.busy)return;
    let n=0;
    while(this.queue.length&&n++<budget)this._execute(this.queue.shift());
    if(this.queue.length&&!this.scheduled){this.scheduled=true;this.schedule(()=>{this.scheduled=false;this.flush();});}
  }
  _reject(ticket,reason){const receipt=immutableCopy({ticket,status:'Rejected',reason});this.receipts.set(ticket,receipt);return receipt;}
  _save(){
    return {kernel:this.kernel,kernelState:this.kernel._cloneState(),fields:clone({epoch:this.epoch,generation:this.generation,selectionGeneration:this.selectionGeneration,hostFence:this.hostFence,closed:this.closed,
      documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,active:this.active,history:this.history,redo:this.redo,pending:this.pending,outcomes:this.outcomes,
      historySerial:this.historySerial,fenceSerial:this.fenceSerial,lastSequences:this.lastSequences}),registry:clone(this.registry),published:this.published};
  }
  _restore(saved){
    const newReservations=[...this.registry].filter(([key])=>!saved.registry.has(key));
    this.kernel=saved.kernel;this.kernel._restoreState(saved.kernelState);Object.assign(this,saved.fields);
    this.registry=new Map([...saved.registry,...newReservations]);this.published=saved.published;
  }
  _execute(q){
    this.busy=true;const saved=this._save();
    try{
      for(const fn of this.will)try{fn(this.published,q.payload);}catch{} // Notifications have no admission authority.
      const native=nativeTypes.has(q.payload.type);
      if(this.closed&&q.payload.type!=='dispose')fail('ConnectionClosed');
      if(native)this._validateStamp(q);
      if(q.payload.failAt==='beforeMutation')fail('Injected:beforeMutation');
      const result=this._reduce(q)??{};
      if(q.payload.failAt==='beforePublication')fail('Injected:beforePublication');
      validateTree(this.kernel.tree());
      if(raw(project(this.kernel.tree()))!==this.kernel.docText)fail('ProjectionMismatch');
      this.documentRevision=saved.fields.documentRevision+(equal(saved.published.tree,this.kernel.tree())?0:1);
      this.sessionRevision=saved.fields.sessionRevision+1;
      if(native){const s=q.payload.stamp;this.lastSequences.set(JSON.stringify([s.epoch,s.generation]),s.sequence);}
      this._publish();
      const receipt=immutableCopy({ticket:q.ticket,status:'Accepted',documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,...result,snapshot:this.published});
      this.receipts.set(q.ticket,receipt); // Receipt BEFORE reentrant did-change input.
      for(const fn of this.did)try{fn(this.published,q.payload);}catch{} // Observer failure cannot roll back accepted input.
    }catch(e){
      this._restore(saved);
      if(q.initial&&q.reservation)this.registry.get(q.reservation).status='Failed';
      this._reject(q.ticket,e.message);
    }finally{this.busy=false;}
  }
  _validateStamp(q){
    const s=q.payload.stamp;
    if(s.epoch!==this.epoch)fail('StaleEpoch');
    if(s.generation!==this.generation)fail('StaleGeneration');
    if(!this.hostFence&&!s.fenceVerified)fail('HostFenceRequired');
    const previous=this.lastSequences.get(JSON.stringify([s.epoch,s.generation]));
    if(previous!=null&&s.sequence<=previous)fail('OutOfOrderSequence');
    if(q.reservation){
      const r=this.registry.get(q.reservation);
      if(r.status==='Failed')fail('InitiatingSessionFailed');
      if(r.status==='Terminated')fail('StaleCompositionSession');
      if(!q.initial&&this.active?.key!==q.reservation)fail('StaleCompositionSession');
    }
    if(s.fenceVerified)this.hostFence=true;
  }
  _basis(q){
    if(q.payload.afterTicket==null)return q.basis;
    const r=this.receipts.get(q.payload.afterTicket);
    if(r?.status!=='Accepted')fail('BasisDependencyFailed');
    return r.snapshot;
  }
  _target(q){
    const p=q.payload,b=this._basis(q),current=this.kernel.snapshot().units;
    if(p.range){
      const old=[Math.min(...p.range),Math.max(...p.range)];
      const mapped=mapRequest({...p,range:old},b.units,current);
      return {range:mapped.range,selection:mapped.selection};
    }
    if(q.reservation&&!q.initial){
      const range=this.kernel.markedRange??this.active.unmarkedExtent;
      if(!range)fail('NoSessionTarget');return {range:[...range],selection:p.selection};
    }
    if(b.selectionGeneration!==this.selectionGeneration)fail('TargetRelocated');
    const range=mapRange(b.selection,b.units,current,p.affinity);
    return {range:[Math.min(...range),Math.max(...range)],selection:p.selection};
  }
  _call(payload){const result=this.kernel.applyInput(payload);if(result.status==='Rejected')fail(result.reason);return result;}
  _retargetChanged(target,force=false){
    const old=this.kernel.markedRange??this.active.unmarkedExtent;
    if(force||!old||old[0]!==target.range[0]||old[1]!==target.range[1]){
      // Explicit ranges on preedit/commit are ALSO retargets. They must pass the
      // same protected-structure classifier as a named retarget callback.
      this._call({type:'retarget',range:target.range});
      this.active.fence=null;this.active.unmarkedExtent=null;
    }
  }
  _start(q,target){
    if(this.active)fail('AnotherCompositionActive');
    const engineId=q.reservation?this.registry.get(q.reservation).engineId:`session:${q.ticket}`;
    const before=bundle(this.kernel);
    this._call({type:'startComposition',range:target.range,sessionId:engineId});
    this.active={key:q.reservation,engineId,before,undoSawDirty:false,unmarkedExtent:null,fence:null};
    if(q.reservation)this.registry.get(q.reservation).status='Active';
  }
  _semanticTargets(){
    const units=this.kernel.snapshot().units,ranges=[...(this.kernel.sessionIslands??[]),...(this.kernel.sessionCommittedRanges??[])];
    return [...new Set(ranges.flatMap(([a,b])=>units.slice(a,b).filter(u=>u.kind!=='boundary').map(u=>u.tokenId)))];
  }
  _acceptVisible(){
    const r=this.kernel._runInTransaction('integrated-accept',()=>{this.kernel._flushOverlayToCommitted();this.kernel.markedWindow=null;return {status:'Accepted'};});
    if(r.status==='Rejected')fail(r.reason);
  }
  _adopt(b){this.kernel=new StructuralModel(b.tree,b.selection);}
  _historyPush(before,after,kind){
    const entry={id:++this.historySerial,kind,before:clone(before),after:clone(after)};
    this.history.push(entry);this.redo=[];return entry.id;
  }
  _popExpected(id){
    if(this.history.at(-1)?.id!==id)fail('HistoryOrderViolation');
    const e=this.history.pop();this._adopt(e.before);this.redo.push(e);
  }
  _format(q,compositionIds){
    const p=q.payload;
    const ids=p.target==='composition'?compositionIds:q.targetIds;
    const want=new Set(ids??[]),tree=clone(this.kernel.tree());let anySurvived=false;
    for(const leaf of leaves(tree))if(leaf.units){
      const text=raw(leaf.units),ranges=[...new Intl.Segmenter('en',{granularity:'grapheme'}).segment(text)];
      for(const g of ranges){const units=leaf.units.slice(g.index,g.index+g.segment.length);
        if(!units.some(u=>want.has(u.tokenId)))continue;
        anySurvived=true;
        for(const u of units)for(const [k,v]of Object.entries(p.patch??{})){u.attrs??={};if(v===null)delete u.attrs[k];else u.attrs[k]=v;}
      }
    }
    if(!anySurvived)return {status:'Cancelled',reason:'TargetDeleted'};
    const before=bundle(this.kernel),after={tree,selection:this.kernel.selection};
    if(equal(before.tree,tree))return {status:'NoChange'};
    this._adopt(after);const entry=this._historyPush(before,after,'Formatting');return {status:'Applied',entry};
  }
  _drainLedger(active,cancelled,compositionIds,request){
    const slots=this.history.map(e=>({entry:e.id}));
    const final=bundle(this.kernel);
    if(!equal(active.before.tree,final.tree))slots.push({entry:this._historyPush(active.before,final,'Composition')});
    else if(active.undoSawDirty)slots.push({credit:'CompositionAlreadyReverted'});
    let count=0;
    for(const q of this.pending){
      let outcome;
      if(q.payload.type==='format'){
        outcome=cancelled&&q.payload.target==='composition'?{status:'Cancelled',reason:'CompositionCancelled'}:this._format(q,compositionIds);
        slots.push(outcome.entry?{entry:outcome.entry}:{credit:'CommandWithoutHistory'});
      }else{
        const slot=slots.pop();
        if(!slot)outcome={status:'Rejected',reason:'NothingToUndo'};
        else if(slot.credit)outcome={status:'NoChange',reason:slot.credit};
        else{this._popExpected(slot.entry);outcome={status:'Applied',entry:slot.entry};}
      }
      this.outcomes.set(q.ticket,outcome);
      if(request.failAt===`afterCommand:${count++}`)fail(`Injected:${request.failAt}`);
    }
    this.pending=[];
  }
  _finish(kind,request,ids=this._semanticTargets()){
    const active=clone(this.active);
    if(!active)fail('NoActiveSession');
    if(kind==='cancel')this._call({type:'cancel'});else this._acceptVisible();
    this._adopt(bundle(this.kernel)); // REBASE provenance to the accepted canonical tree.
    if(request.failAt==='afterNative')fail('Injected:afterNative');
    this._drainLedger(active,kind==='cancel',ids,request);
    if(active.key)this.registry.get(active.key).status='Terminated';
    this.active=null;
  }
  _reduce(q){
    const p=q.payload;
    if(nativeTypes.has(p.type)){
      if(p.flowId!=null&&p.flowId!==this.kernel.source.id)fail('FlowMismatch');
      if(['startComposition','preedit','commit','retarget','nativeRemoval'].includes(p.type)){
        const target=this._target(q);
        if(!this.active)this._start(q,target);
        else {
          if(q.initial||!q.reservation)fail('AnotherCompositionActive');
          if(p.type!=='retarget')this._retargetChanged(target,p.type==='startComposition');
        }
        if(p.type==='startComposition')return;
        if(p.type==='retarget')this._call({type:'retarget',...target});
        else if(p.type==='nativeRemoval'){
          this._call({type:'nativeRemoval',...target,replacementText:p.text??''});
          // Kernel nativeRemoval accepts visible effects; its private history is
          // ignored. Only this owner's ledger becomes authoritative.
          this._finish('commit',p);return;
        }else if(p.text!=null)this._call({type:'preedit',...target,text:p.text});
        this.active.fence=null;this.active.unmarkedExtent=null;
        if(p.type==='commit')this._finish('commit',p);
        return;
      }
      if(!this.active||q.reservation!==this.active.key)fail('StaleCompositionSession');
      if(p.type==='partialCommit'){
        this._call({type:'partialCommit',length:p.length,selection:p.selection});this.active.fence=null;return;
      }
      if(p.type==='unmark'){
        if(p.settled!==false){this._finish('commit',p);return;}
        const extent=this.kernel.markedRange;this._call({type:'unmark',settled:false,selection:p.selection});
        this.active.unmarkedExtent=extent;
        this.active.fence={id:++this.fenceSerial,epoch:this.epoch,generation:this.generation,key:this.active.key};return;
      }
      if(p.type==='completeSettlement'){
        if(this.kernel.markedRange!==null)fail('CandidateStillActive');
        if(!p.evidence||JSON.stringify(p.evidence)!==JSON.stringify(this.active.fence))fail('ObsoleteSettlementToken');
        this._finish('commit',p);return;
      }
      if(p.type==='cancel'){this._finish('cancel',p);return;}
    }
    if(p.type==='abort'){
      if(this.active)this._finish('cancel',p);
      this.generation++;this.hostFence=false;return;
    }
    if(p.type==='selection'){
      if(this.active)fail('SettlementRequired');
      const b=this._basis(q),range=mapRange(p.range,b.units,this.kernel.snapshot().units,'after');
      if(!validRange(range,this.kernel.docText.length))fail('InvalidSelection');
      this._call({type:'setSelection',selection:range});this.selectionGeneration++;this.generation++;this.hostFence=false;return;
    }
    if(p.type==='format'||p.type==='undo'){
      const command={...q};
      if(p.type==='format'){
        if(!p.patch||Object.values(p.patch).some(v=>v!==null&&!['boolean','string','number'].includes(typeof v)))fail('InvalidPatch');
        if(p.target!=='composition'){
          const b=this._basis(q),r=p.range??b.selection;
          if(!validRange(r,b.units.length))fail('InvalidTargetRange');
          command.targetIds=b.units.slice(Math.min(...r),Math.max(...r)).filter(u=>u.kind!=='boundary').map(u=>u.tokenId);
        }else if(!this.active)fail('NoActiveSession');
      }
      if(this.active){
        if(p.type==='undo')this.active.undoSawDirty ||= !equal(this.active.before.tree,this.kernel.tree())||!equal(this.active.before.tree,this.kernel.committedTree());
        this.pending.push(command);this.outcomes.set(q.ticket,{status:'Pending'});return {commandStatus:'Queued'};
      }
      if(p.type==='undo'){
        if(!this.history.length)fail('NothingToUndo');this._popExpected(this.history.at(-1).id);this.outcomes.set(q.ticket,{status:'Applied'});
      }else this.outcomes.set(q.ticket,this._format(command,[]));
      return;
    }
    if(p.type==='redo'){
      if(this.active)fail('SettlementRequired');
      if(!this.redo.length)fail('NothingToRedo');
      const e=this.redo.pop();this._adopt(e.after);this.history.push(e);return;
    }
    if(p.type==='openNewConnection'||p.type==='dispose'){
      if(this.active){
        const a=clone(this.active);this._acceptVisible();const after=bundle(this.kernel);
        if(!equal(a.before.tree,after.tree))this._historyPush(a.before,after,'Composition');
        if(a.key)this.registry.get(a.key).status='Terminated';this.active=null;this._adopt(after);
      }
      for(const c of this.pending)this.outcomes.set(c.ticket,{status:'Cancelled',reason:p.type==='dispose'?'Disposed':'StaleEpoch'});
      this.pending=[];this.epoch++;this.generation=1;this.hostFence=false;this.closed=p.type==='dispose';return;
    }
    fail('UnknownRequest');
  }
}
