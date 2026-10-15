// Small executable contracts, not OS adapters. Host provenance is an explicit input.
export class AdmissionModel {
  constructor(text='hello',selection=[text.length,text.length]){
    this.published={text,selection:[...selection],revision:0};this.epoch=1;this.generation=1;
    this.queue=[];this.registry=new Map();this.receipts=[];this.ticket=0;this.active=null;this.hostFence=true;
  }
  capture(input){
    const p=structuredClone(input),ticket=++this.ticket;
    if(p.kind==='abort'||p.kind==='selection'){this.queue.push({p,ticket});return ticket;}
    const stamp=p.stamp??{};
    if(!stamp.verified)return this.reject(ticket,'AmbiguousHostProvenance');
    let session=null;
    if(stamp.token){
      session=this.registry.get(stamp.token);
      if(!session){session={token:stamp.token,initiator:ticket,status:'Reserved',epoch:stamp.epoch,generation:stamp.generation};this.registry.set(stamp.token,session);}
    }
    // A fresh replacement cannot read a future queued selection. It either names
    // a published basis or waits for its explicit afterTicket dependency to publish.
    this.queue.push({p,ticket,session,initial:session?.initiator===ticket,
      basis:p.afterTicket?null:structuredClone(this.published)});
    return ticket;
  }
  reject(ticket,reason){for(const r of this.registry.values())if(r.initiator===ticket&&r.status==='Reserved')r.status='Failed';this.receipts.push({ticket,status:'Rejected',reason});return ticket;}
  drain(){while(this.queue.length)this.step();return this.published;}
  step(){
    const q=this.queue.shift(),{p,ticket}=q;if(!q)return;
    if(p.kind==='abort'){
      if(this.active){this.published={...this.published,text:this.active.before.text,selection:[...this.active.before.selection]};this.registry.get(this.active.token).status='Terminated';this.active=null;}
      this.generation++;this.hostFence=false;
    }else if(p.kind==='selection'){
      if(this.active)return this.reject(ticket,'SettlementRequired');
      if(p.range.some(x=>x<0||x>this.published.text.length))return this.reject(ticket,'InvalidSelection');
      this.published={...this.published,selection:[...p.range]};
    }else{
      const s=p.stamp;
      if(s.epoch!==this.epoch)return this.reject(ticket,'StaleEpoch');
      if(s.generation!==this.generation)return this.reject(ticket,'StaleGeneration');
      if(!this.hostFence&&!s.fenceVerified)return this.reject(ticket,'HostFenceRequired');
      const r=q.session;
      if(r?.status==='Failed')return this.reject(ticket,'InitiatingSessionFailed');
      if(r?.status==='Terminated')return this.reject(ticket,'StaleCompositionSession');
      if(p.fail){if(r&&q.initial)r.status='Failed';return this.reject(ticket,'InjectedFailure');}
      let basis=q.basis;
      if(p.afterTicket){
        const receipt=this.receipts.find(x=>x.ticket===p.afterTicket);
        if(receipt?.status!=='Accepted')return this.reject(ticket,'BasisDependencyFailed');
        // This fixture requires an immediately current dependency; production maps
        // its retained snapshot's exact identities if intervening edits occurred.
        if(receipt.revision!==this.published.revision)return this.reject(ticket,'BasisNotCurrent');
        basis=structuredClone(this.published);
      }
      let range;
      if(r&&!q.initial){if(this.active?.token!==r.token)return this.reject(ticket,'StaleCompositionSession');range=this.active.mark??this.active.unmarked;}
      else{
        if(basis?.revision!==this.published.revision)return this.reject(ticket,'BasisNeedsIdentityMapping');
        range=p.range??basis.selection;
      }
      const [a,b]=[Math.min(...range),Math.max(...range)];
      if(a<0||b>this.published.text.length)return this.reject(ticket,'InvalidRange');
      if(r&&q.initial){r.status='Active';this.active={token:r.token,before:structuredClone(this.published),mark:[a,b]};}
      const text=this.published.text.slice(0,a)+p.text+this.published.text.slice(b),end=a+p.text.length;
      this.published={...this.published,text,selection:[end,end]};
      if(this.active)this.active.mark=[a,end];
      if(p.kind==='commit'&&this.active){this.registry.get(this.active.token).status='Terminated';this.active=null;}
      if(s.fenceVerified)this.hostFence=true;
    }
    this.published.revision++;
    this.receipts.push({ticket,status:'Accepted',revision:this.published.revision});
  }
}

// Settlement-local LIFO shadow stack. Tombstones are one-use action slots,
// never durable history entries. No speculative prior-history reservations.
export class HistoryModel {
  constructor(value='',history=[]){this.value=value;this.history=structuredClone(history);this.redo=[];this.queue=[];this.serial=100;this.outcomes=[];}
  start(){this.before=this.value;this.accepted=this.value;this.live=this.value;this.undoSawDirty=false;}
  preedit(value){this.live=value;}
  partial(value){this.accepted=value;}
  enqueueUndo(){this.undoSawDirty ||= this.live!==this.before||this.accepted!==this.before;this.queue.push({kind:'undo',id:++this.serial});}
  enqueueFormat(transform,{cancelOnAbort=true}={}){this.queue.push({kind:'format',id:++this.serial,transform,cancelOnAbort});}
  push(before,after,kind){const e={id:++this.serial,before,after,kind};this.history.push(e);this.redo=[];this.value=after;return e;}
  settle(kind,final=this.live,{failAfter=-1,wrongTop=false}={}){
    const saved={value:this.value,history:structuredClone(this.history),redo:structuredClone(this.redo),serial:this.serial,outcomes:structuredClone(this.outcomes)};
    try{
      this.value=kind==='cancel'?this.accepted:final;
      const slots=this.history.map(e=>({entry:e.id}));
      if(this.value!==this.before){const e=this.push(this.before,this.value,'Composition');slots.push({entry:e.id});}
      else if(this.undoSawDirty)slots.push({tombstone:'CompositionAlreadyReverted'});
      let i=0;
      for(const q of this.queue){
        if(q.kind==='format'){
          const before=this.value,after=kind==='cancel'&&q.cancelOnAbort?before:q.transform(before);
          if(after!==before){const e=this.push(before,after,'Formatting');slots.push({entry:e.id});}
          else slots.push({tombstone:'CommandWithoutHistory'});
          this.outcomes.push({id:q.id,status:after===before?'NoChange':'Applied'});
        }else{
          const slot=slots.pop();
          if(!slot)this.outcomes.push({id:q.id,status:'NothingToUndo'});
          else if(slot.tombstone)this.outcomes.push({id:q.id,status:slot.tombstone});
          else{
            if(wrongTop||this.history.at(-1)?.id!==slot.entry)throw Error('HistoryOrderViolation');
            const e=this.history.pop();this.value=e.before;this.redo.push(e);this.outcomes.push({id:q.id,status:'Applied',entry:e.id});
          }
        }
        if(i++===failAfter)throw Error('InjectedSettlementFailure');
      }
      this.queue=[];return this.value;
    }catch(e){Object.assign(this,saved);throw e;}
  }
}

export function rankWorkBound(textHeight,containerHeights,fanout,mapLookupCost){
  // An accounting model, not a wall-clock benchmark.
  return fanout*(textHeight+containerHeights.reduce((a,b)=>a+b,0)+containerHeights.length)*mapLookupCost;
}
