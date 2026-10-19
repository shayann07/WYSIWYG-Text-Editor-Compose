// Integrated EXECUTABLE SPECIFICATION. Full copies/arrays, synthetic host stamps.
// One public owner publishes admission + structural state + engine-owned history.
import {StructuralModel,clone,project,raw,leaves,validateTree} from './structural-model.mjs';
import {immutableCopy,mapRange,mapRequest} from './request-contract.mjs';
import {createHash} from 'node:crypto';

export const nativeTypes=new Set(['startComposition','preedit','retarget','partialCommit','unmark','completeSettlement','commit','cancel','nativeRemoval']);
const sessionTypes=new Set(['startComposition','preedit','retarget','partialCommit','unmark','completeSettlement','cancel']);
const fail=reason=>{throw Error(reason);};
const keyOf=s=>JSON.stringify([s.epoch,s.generation,s.sessionOrdinal]);
const wireOf=s=>JSON.stringify([s.epoch,s.generation,s.sequence]);
const contextOf=s=>JSON.stringify([s.epoch,s.generation]);
const charge=v=>Buffer.byteLength(JSON.stringify(v)??'','utf8');
export const DEFAULT_RETENTION=Object.freeze({receiptEntries:256,basisEntries:32,basisBytes:8*1024*1024,pinnedBasisBytes:8*1024*1024,pendingRequests:256,pendingBytes:8*1024*1024,outcomeEntries:256,historyEntries:1000,historyBytes:64*1024*1024});
function semanticNode(n){
  const out={id:n.id,type:n.type,props:n.props??{}};
  if(n.children)out.children=n.children.map(semanticNode);
  else if(n.units){out.emptyBlockAttributes=n.emptyBlockAttributes??{};out.units=n.units.map(({tokenId,originSessionId,...u})=>({...u,kind:u.kind??'text'}));}
  else Object.assign(out,n);
  if(n.type==='object'&&n.props?.kind==='table'&&Array.isArray(n.props.rows))out.props={...n.props,rows:n.props.rows.map(r=>({...r,cells:r.cells.map(c=>({...c,flow:semanticNode(c.flow)}))}))};
  return out;
}
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
export const semantic=root=>JSON.stringify(canonical(semanticNode(root)));
export const equal=(a,b)=>semantic(a)===semantic(b);
export const validRange=(r,n)=>Array.isArray(r)&&r.length===2&&Object.hasOwn(r,0)&&Object.hasOwn(r,1)&&r.every(Number.isSafeInteger)&&r.every(x=>x>=0&&x<=n);
const bundle=k=>({tree:clone(k.tree()),selection:[...k.selection]});

export class IntegratedModel {
  constructor(root,selection=[0,0],{schedule=fn=>setImmediate(fn),retention={}}={}){
    this.kernel=new StructuralModel(root,selection);
    this.epoch=1;this.generation=1;this.selectionGeneration=0;this.hostFence=true;this.closed=false;
    this.documentRevision=0;this.sessionRevision=0;this.active=null;
    this.registry=new Map();this.queue=[];this.receipts=new Map();this.wires=new Map();this.lastSequences=new Map();
    this.history=[];this.redo=[];this.pending=[];this.outcomes=new Map();this.historySerial=0;this.fenceSerial=0;this.ticketSerial=0;
    this.retention={...DEFAULT_RETENTION,...retention};for(const value of Object.values(this.retention))if(!Number.isSafeInteger(value)||value<1)fail('InvalidRetentionLimit');
    this.bases=new Map();this.sequenceWatermarks=new Map();this.sessionWatermarks=new Map();this.deliveryPins=new Set();this.executing=null;this.historyEvicted=0;
    this.busy=false;this.scheduled=false;this.schedule=schedule;this.will=[];this.did=[];
    this._publish();
  }
  snapshot(){return this.published;}
  _bundle(){return bundle(this.kernel);}
  _semanticCurrent(){return this.kernel.tree();}
  _semanticCommitted(){return this.kernel.committedTree();}
  _bundleSemantic(b){return b.tree;}
  _requestBasis(){return this.publishedBasis;}
  _requestContext(){return null;}
  _validatePatch(p){return p&&Object.values(p).every(v=>v===null||['boolean','string','number'].includes(typeof v));}
  _executePending(q,ids){return this._format(q,ids);}
  _restoreHistory(b){this._adopt(b);}
  _beforePublication(saved,q){}
  receipt(ticket){return this.receipts.get(ticket)??immutableCopy({status:this.queue.some(q=>q.ticket===ticket)||this.executing?.ticket===ticket?'Deferred':'ReceiptExpired',ticket});}
  _compactBasis(){return immutableCopy({units:this.kernel.snapshot().units.map(u=>({tokenId:u.tokenId})),selection:this.kernel.selection,selectionGeneration:this.selectionGeneration,documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,epoch:this.epoch,generation:this.generation});}
  _queued(){return [...this.queue,...(this.executing?[this.executing]:[])];}
  _dependencyPins(){return new Set(this._queued().map(q=>q.payload.afterTicket).filter(t=>t!=null));}
  _checkCapacity(q){
    const requests=[...this._queued(),q];
    if(requests.length+this.pending.length>this.retention.pendingRequests)fail('AdmissionCapacityExceeded');
    if(requests.reduce((n,r)=>n+charge(r.payload),0)+this.pending.reduce((n,r)=>n+charge(r.payload)+charge(r.targetIds??[]),0)>this.retention.pendingBytes)fail('AdmissionCapacityExceeded');
    const bases=new Set(requests.flatMap(r=>[r.basis,r.dependencyBasis??this.bases.get(r.payload.afterTicket)]).filter(Boolean));
    if([...bases].reduce((n,b)=>n+charge(b),0)>this.retention.pinnedBasisBytes)fail('BasisCapacityExceeded');
  }
  _retainBasis(ticket,basis){
    const refs=new Set(this._queued().flatMap(q=>[q.basis,q.dependencyBasis??(q.payload.afterTicket===ticket?basis:this.bases.get(q.payload.afterTicket))]).filter(Boolean));
    if([...refs].reduce((n,b)=>n+charge(b),0)>this.retention.pinnedBasisBytes)fail('BasisCapacityExceeded');
    this.bases.set(ticket,basis);
  }
  _checkPendingCommand(command){
    const pending=[...this.pending,command];
    if(pending.length>this.retention.outcomeEntries||pending.length+this.queue.length>this.retention.pendingRequests||pending.reduce((n,q)=>n+charge(q.payload)+charge(q.targetIds??[]),0)+this.queue.reduce((n,q)=>n+charge(q.payload),0)>this.retention.pendingBytes)fail('AdmissionCapacityExceeded');
  }
  _terminalOutcome(ticket,outcome){this.outcomes.delete(ticket);this.outcomes.set(ticket,outcome);}
  _trimHistory(){
    const cost=()=>this.history.reduce((n,e)=>n+charge(e),0)+this.redo.reduce((n,e)=>n+charge(e),0);
    while(this.history.length+this.redo.length>this.retention.historyEntries||cost()>this.retention.historyBytes){if(this.history.length)this.history.shift();else if(this.redo.length)this.redo.shift();else break;this.historyEvicted++;}
    const complete=[...this.outcomes].filter(([,v])=>v.status!=='Pending');
    for(const [ticket]of complete.slice(0,Math.max(0,complete.length-this.retention.outcomeEntries)))this.outcomes.delete(ticket);
  }
  _trimRetention(){
    const dependencyPins=this._dependencyPins(),inflight=new Set(this._queued().map(q=>q.ticket));
    const evictable=[...this.receipts.keys()].filter(t=>!dependencyPins.has(t)&&!this.deliveryPins.has(t));
    for(const ticket of evictable.slice(0,Math.max(0,evictable.length-this.retention.receiptEntries)))this.receipts.delete(ticket);
    for(const [key,w]of this.wires)if(!this.receipts.has(w.ticket)&&!inflight.has(w.ticket))this.wires.delete(key);
    const free=[...this.bases].filter(([t])=>!dependencyPins.has(t));let bytes=free.reduce((n,[,b])=>n+charge(b),0),count=free.length;
    for(const [t,b]of free){if(count<=this.retention.basisEntries&&bytes<=this.retention.basisBytes)break;this.bases.delete(t);bytes-=charge(b);count--;}
    const reserved=new Set(this._queued().map(q=>q.reservation));
    for(const [key,r]of this.registry)if(!reserved.has(key)&&key!==this.active?.key)this.registry.delete(key);
    const contexts=new Set([JSON.stringify([this.epoch,this.generation]),JSON.stringify([this.published.epoch,this.published.generation]),...this._queued().filter(q=>q.payload.stamp).map(q=>contextOf(q.payload.stamp))]);
    for(const map of [this.sequenceWatermarks,this.sessionWatermarks,this.lastSequences])for(const key of map.keys())if(!contexts.has(key))map.delete(key);
  }
  retentionStats(){
    const pins=this._dependencyPins(),pinnedBases=new Set(this._queued().flatMap(q=>[q.basis,q.dependencyBasis??this.bases.get(q.payload.afterTicket)]).filter(Boolean));return {receipts:this.receipts.size,bases:this.bases.size,basisBytes:[...this.bases.values()].reduce((n,b)=>n+charge(b),0),pinnedBasisBytes:[...pinnedBases].reduce((n,b)=>n+charge(b),0),pinnedTickets:pins.size,wires:this.wires.size,registry:this.registry.size,sequenceContexts:this.sequenceWatermarks.size,sessionContexts:this.sessionWatermarks.size,history:this.history.length,redo:this.redo.length,historyBytes:[...this.history,...this.redo].reduce((n,e)=>n+charge(e),0),outcomes:this.outcomes.size,historyEvicted:this.historyEvicted,queued:this.queue.length};
  }
  _publish(basis=null){
    this.published=immutableCopy({tree:this.kernel.tree(),committedTree:this.kernel.committedTree(),units:this.kernel.snapshot().units,
      text:this.kernel.docText,selection:this.kernel.selection,markedRange:this.kernel.markedRange,
      epoch:this.epoch,generation:this.generation,selectionGeneration:this.selectionGeneration,hostFence:this.hostFence,closed:this.closed,
      documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,
      active:this.active?{key:this.active.key,engineId:this.active.engineId,fence:this.active.fence,unmarkedExtent:this.active.unmarkedExtent}:null,
      history:this.history,redo:this.redo,outcomes:[...this.outcomes],pending:this.pending.map(q=>({ticket:q.ticket,type:q.payload.type}))});
    this.publishedBasis=basis??immutableCopy({units:this.published.units.map(u=>({tokenId:u.tokenId})),selection:this.published.selection,selectionGeneration:this.published.selectionGeneration,documentRevision:this.published.documentRevision,sessionRevision:this.published.sessionRevision,epoch:this.published.epoch,generation:this.published.generation});
  }
  // All requests, including reentrant application commands, use this admission.
  dispatch(input,{defer=false}={}){
    const payload=immutableCopy(input),s=payload.stamp;
    const ticket=++this.ticketSerial;
    if(nativeTypes.has(payload.type)){
      if(!s||!s.verified||![s.epoch,s.generation,s.sequence].every(Number.isSafeInteger)||s.epoch<1||s.generation<1||s.sequence<0||s.token==null&&(s.sessionOrdinal!=null||sessionTypes.has(payload.type))||s.token!=null&&(!Number.isSafeInteger(s.sessionOrdinal)||s.sessionOrdinal<1||typeof s.token!=='string'||s.token.length>128))return this._reject(ticket,'AmbiguousHostProvenance');
      const captureEpoch=this.published.epoch,captureGeneration=this.published.generation;
      if(s.epoch<captureEpoch||s.epoch===captureEpoch&&s.generation<captureGeneration)return this._reject(ticket,s.epoch!==captureEpoch?'StaleEpoch':'StaleGeneration');
      if((s.epoch!==captureEpoch||s.generation!==captureGeneration)&&(payload.afterTicket==null||!this.queue.some(q=>q.ticket===payload.afterTicket)&&this.executing?.ticket!==payload.afterTicket))return this._reject(ticket,'FutureIdentityNeedsDependency');
      const wire=wireOf(s),existing=this.wires.get(wire),fingerprint=createHash('sha256').update(JSON.stringify(canonical(payload))).digest('hex');
      if(existing){
        if(existing.fingerprint!==fingerprint)return this._reject(ticket,'SequenceCollision');
        const prior=this.receipts.get(existing.ticket);
        return prior?immutableCopy({status:'Duplicate',ticket:existing.ticket,previous:prior}):immutableCopy({status:'Deferred',ticket:existing.ticket});
      }
      if(s.sequence<=(this.sequenceWatermarks.get(contextOf(s))??-1))return immutableCopy({status:'DuplicateReceiptExpired',ticket});
      this.sequenceWatermarks.set(contextOf(s),s.sequence);
      this.wires.set(wire,{ticket,fingerprint});
    }
    let reservation=null,initial=false;
    if(nativeTypes.has(payload.type)&&s.token!=null){
      reservation=keyOf(s);
      if(!this.registry.has(reservation)){
        if(s.sessionOrdinal<=(this.sessionWatermarks.get(contextOf(s))??0))return this._reject(ticket,'StaleCompositionSession');
        this.sessionWatermarks.set(contextOf(s),s.sessionOrdinal);
        initial=true;this.registry.set(reservation,{status:'Reserved',initiator:ticket,engineId:`session:${ticket}`,token:s.token});
      }else if(this.registry.get(reservation).token!==s.token){return this._reject(ticket,'SessionTokenCollision');
      }
    }
    let q;
    try{
      q={ticket,payload,basis:payload.afterTicket==null?this._requestBasis(payload):null,context:immutableCopy(this._requestContext(payload)),reservation,initial};
      if(payload.afterTicket!=null){
        if(!Number.isSafeInteger(payload.afterTicket)||payload.afterTicket<1||payload.afterTicket>=ticket)fail('InvalidDependency');
        const prior=this.receipts.get(payload.afterTicket);
        if(prior){if(prior.status!=='Accepted')fail('BasisDependencyFailed');q.dependencyBasis=this.bases.get(payload.afterTicket);if(!q.dependencyBasis)fail('BasisExpired');}
        else if(!this.queue.some(x=>x.ticket===payload.afterTicket)&&this.executing?.ticket!==payload.afterTicket)fail('BasisExpired');
      }
      this._checkCapacity(q);
    }catch(e){if(initial)this.registry.get(reservation).status='Failed';return this._reject(ticket,e.message);}
    this.queue.push(q);
    this.deliveryPins.add(ticket);
    if(!defer&&!this.busy)this.flush();
    const result=this.receipt(ticket);this.deliveryPins.delete(ticket);this._trimRetention();return result;
  }
  flush(budget=64){
    if(this.busy)return;
    let n=0;
    while(this.queue.length&&n++<budget)this._execute(this.queue.shift());
    if(this.queue.length&&!this.scheduled){this.scheduled=true;this.schedule(()=>{this.scheduled=false;this.flush();});}
  }
  _reject(ticket,reason){const receipt=immutableCopy({ticket,status:'Rejected',reason});this.receipts.set(ticket,receipt);this._trimRetention();return receipt;}
  _save(){
    return {kernel:this.kernel,kernelState:this.kernel._cloneState(),fields:clone({epoch:this.epoch,generation:this.generation,selectionGeneration:this.selectionGeneration,hostFence:this.hostFence,closed:this.closed,
      documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,active:this.active,history:this.history,redo:this.redo,pending:this.pending,outcomes:this.outcomes,
      historySerial:this.historySerial,fenceSerial:this.fenceSerial,lastSequences:this.lastSequences,historyEvicted:this.historyEvicted}),registry:clone(this.registry),published:this.published,publishedBasis:this.publishedBasis,semanticBefore:this._semanticCurrent()};
  }
  _restore(saved){
    const newReservations=[...this.registry].filter(([key])=>!saved.registry.has(key));
    this.kernel=saved.kernel;this.kernel._restoreState(saved.kernelState);Object.assign(this,saved.fields);
    this.registry=new Map([...saved.registry,...newReservations]);this.published=saved.published;this.publishedBasis=saved.publishedBasis;
  }
  _execute(q){
    this.busy=true;this.executing=q;const saved=this._save();
    try{
      for(const fn of this.will)try{fn(this.published,q.payload);}catch{} // Notifications have no admission authority.
      const native=nativeTypes.has(q.payload.type);
      if(this.closed&&q.payload.type!=='dispose')fail('ConnectionClosed');
      if(native)this._validateStamp(q);
      if(q.payload.selection!=null&&!validRange(q.payload.selection,Number.MAX_SAFE_INTEGER))fail('InvalidSelection');
      if(q.payload.failAt==='beforeMutation')fail('Injected:beforeMutation');
      const result=this._reduce(q)??{};
      this._beforePublication(saved,q);
      if(q.payload.failAt==='beforePublication')fail('Injected:beforePublication');
      validateTree(this.kernel.tree());
      if(raw(project(this.kernel.tree()))!==this.kernel.docText)fail('ProjectionMismatch');
      this.documentRevision=saved.fields.documentRevision+(equal(saved.semanticBefore,this._semanticCurrent())?0:1);
      this.sessionRevision=saved.fields.sessionRevision+1;
      if(native){const s=q.payload.stamp;this.lastSequences.set(JSON.stringify([s.epoch,s.generation]),s.sequence);}
      this._trimHistory();
      // Worker history/receipts are diagnostics, never an independent retention owner.
      this.kernel.undoStack=[];this.kernel.redoStack=[];this.kernel._editHistory=[];this.kernel.receiptCache.clear();
      const basis=this._compactBasis();
      this._retainBasis(q.ticket,basis);
      this._publish(basis);
      const receipt=immutableCopy({ticket:q.ticket,status:'Accepted',documentRevision:this.documentRevision,sessionRevision:this.sessionRevision,...result,historyEvicted:this.historyEvicted});
      this.receipts.set(q.ticket,receipt); // Receipt BEFORE reentrant did-change input.
      for(const fn of this.did)try{fn(this.published,q.payload);}catch{} // Observer failure cannot roll back accepted input.
    }catch(e){
      this._restore(saved);
      this.bases.delete(q.ticket);
      if(q.initial&&q.reservation)this.registry.get(q.reservation).status='Failed';
      this._reject(q.ticket,e.message);
    }finally{this.busy=false;this.executing=null;this._trimRetention();}
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
    const compatible=b=>{if(q.payload.stamp&&(b.epoch!==q.payload.stamp.epoch||b.generation!==q.payload.stamp.generation))fail('BasisIdentityMismatch');return b;};
    if(q.dependencyBasis)return compatible(q.dependencyBasis);
    const r=this.receipts.get(q.payload.afterTicket);
    if(r?.status!=='Accepted')fail('BasisDependencyFailed');
    const b=this.bases.get(q.payload.afterTicket);if(!b)fail('BasisExpired');return compatible(b);
  }
  _target(q){
    const p=q.payload,b=this._basis(q),current=this.kernel.snapshot().units;
    if(p.range!=null){
      if(!validRange(p.range,b.units.length))fail('InvalidTargetRange');
      const old=[Math.min(...p.range),Math.max(...p.range)];
      const mapped=mapRequest({...p,range:old,...(p.type==='nativeRemoval'?{replacementText:p.text??''}:{})},b.units,current);
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
  _nativeSelection(selection){
    if(selection==null)return;
    if(!validRange(selection,this.kernel.docText.length))fail('InvalidSelection');
    this._call({type:'setSelection',selection});
  }
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
    const before=this._bundle();
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
    const e=this.history.pop();this._restoreHistory(e.before);this.redo.push(e);
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
    const before=this._bundle(),after={tree,selection:this.kernel.selection};
    if(equal(before.tree,tree))return {status:'NoChange'};
    this._adopt(after);const entry=this._historyPush(before,after,'Formatting');return {status:'Applied',entry};
  }
  _drainLedger(active,cancelled,compositionIds,request){
    const slots=this.history.map(e=>({entry:e.id}));
    const final=this._bundle();
    if(!equal(this._bundleSemantic(active.before),this._bundleSemantic(final)))slots.push({entry:this._historyPush(active.before,final,'Composition')});
    else if(active.undoSawDirty)slots.push({credit:'CompositionAlreadyReverted'});
    let count=0;
    for(const q of this.pending){
      let outcome;
      if(q.payload.type!=='undo'){
        outcome=q.cancelReason?{status:'Cancelled',reason:q.cancelReason,historySlot:q.historySlot}:cancelled&&q.payload.target==='composition'?{status:'Cancelled',reason:'CompositionCancelled'}:this._executePending(q,compositionIds);
        if(outcome.historySlot!==false)slots.push(outcome.entry?{entry:outcome.entry}:{credit:'CommandWithoutHistory'});
      }else{
        const slot=slots.pop();
        if(!slot)outcome={status:'Rejected',reason:'NothingToUndo'};
        else if(slot.credit)outcome={status:'NoChange',reason:slot.credit};
        else{this._popExpected(slot.entry);outcome={status:'Applied',entry:slot.entry};}
      }
      if(!q.outcomeEmitted)this._terminalOutcome(q.ticket,outcome);
      if(request.failAt===`afterCommand:${count++}`)fail(`Injected:${request.failAt}`);
    }
    this.pending=[];
  }
  _finish(kind,request,ids=this._semanticTargets()){
    const active=clone(this.active);
    if(!active)fail('NoActiveSession');
    if(kind==='cancel')this._call({type:'cancel'});else this._acceptVisible();
    if(nativeTypes.has(request.type))this._nativeSelection(request.selection);
    this._adopt(this._bundle()); // REBASE provenance to the accepted canonical tree.
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
        if(p.type==='startComposition'){this._nativeSelection(target.selection);return;}
        if(p.type==='retarget')this._call({type:'retarget',...target});
        else if(p.type==='nativeRemoval'){
          this._call({type:'nativeRemoval',...target,replacementText:p.text??''});
          // Kernel nativeRemoval accepts visible effects; its private history is
          // ignored. Only this owner's ledger becomes authoritative.
          this._finish('commit',{...p,selection:target.selection});return;
        }else if(p.text!=null)this._call({type:'preedit',...target,text:p.text});
        this.active.fence=null;this.active.unmarkedExtent=null;
        if(p.type==='commit')this._finish('commit',{...p,selection:target.selection});
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
        if(!p.evidence||JSON.stringify(canonical(p.evidence))!==JSON.stringify(canonical(this.active.fence)))fail('ObsoleteSettlementToken');
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
        if(!this._validatePatch(p.patch))fail('InvalidPatch');
        if(p.target!=='composition'){
          const b=this._basis(q),r=p.range??b.selection;
          if(!validRange(r,b.units.length))fail('InvalidTargetRange');
          command.targetIds=b.units.slice(Math.min(...r),Math.max(...r)).filter(u=>u.kind!=='boundary').map(u=>u.tokenId);
        }else if(!this.active)fail('NoActiveSession');
      }
      if(this.active){
        this._checkPendingCommand(command);
        if(p.type==='undo')this.active.undoSawDirty ||= !equal(this._bundleSemantic(this.active.before),this._semanticCurrent())||!equal(this._bundleSemantic(this.active.before),this._semanticCommitted());
        // Target IDs are captured; pending application commands need no historical
        // receipt snapshot or coordinate basis after admission.
        this.pending.push({ticket:command.ticket,payload:command.payload,targetIds:command.targetIds});this.outcomes.set(q.ticket,{status:'Pending'});return {commandStatus:'Queued'};
      }
      if(p.type==='undo'){
        if(!this.history.length)fail('NothingToUndo');this._popExpected(this.history.at(-1).id);this._terminalOutcome(q.ticket,{status:'Applied'});
      }else this._terminalOutcome(q.ticket,this._format(command,[]));
      return;
    }
    if(p.type==='redo'){
      if(this.active)fail('SettlementRequired');
      if(!this.redo.length)fail('NothingToRedo');
      const e=this.redo.pop();this._restoreHistory(e.after);this.history.push(e);return;
    }
    if(p.type==='openNewConnection'||p.type==='dispose'){
      if(this.active){
        const a=clone(this.active);this._acceptVisible();const after=this._bundle();
        if(!equal(this._bundleSemantic(a.before),this._bundleSemantic(after)))this._historyPush(a.before,after,'Composition');
        if(a.key)this.registry.get(a.key).status='Terminated';this.active=null;this._adopt(after);
      }
      for(const c of this.pending)if(!c.outcomeEmitted)this._terminalOutcome(c.ticket,{status:'Cancelled',reason:p.type==='dispose'?'Disposed':'StaleEpoch'});
      this.pending=[];this.epoch++;this.generation=1;this.hostFence=false;this.closed=p.type==='dispose';return;
    }
    fail('UnknownRequest');
  }
}
