// Byte-accurate fault model. No real fsync/rename/power-loss qualification.
import {createHash} from 'node:crypto';
import {serializeCanonicalDocument1 as encodeDoc,deserializeCanonicalDocument1 as decodeDoc} from './canonical-codec.mjs';
import {DocumentModel} from './document-model.mjs';
import {clone,getFlow,flows,project,pointAt,pointOffset} from './document-tree.mjs';
import {equal} from './integrated-model.mjs';
const H=x=>createHash('sha256').update(x).digest('hex'),ZERO='0'.repeat(64),MAX=256*1024*1024;
const json=v=>Buffer.from(JSON.stringify(v),'utf8');
function pack(value){const payload=json(value);if(payload.length>MAX)throw Error('FrameCapacityExceeded');const head=Buffer.alloc(8);head.write('EV51');head.writeUInt32LE(payload.length,4);return Buffer.concat([head,payload,Buffer.from(H(payload),'hex'),Buffer.from('END5')]);}
function unpack(bytes,offset){if(bytes.length-offset<8)return null;if(bytes.subarray(offset,offset+4).toString()!=='EV51')throw Error('BadMagic');const n=bytes.readUInt32LE(offset+4);if(n>MAX)throw Error('FrameCapacityExceeded');const size=n+44;if(bytes.length-offset<size)return null;const payload=bytes.subarray(offset+8,offset+8+n),digest=bytes.subarray(offset+8+n,offset+40+n).toString('hex');if(digest!==H(payload)||bytes.subarray(offset+40+n,offset+size).toString()!=='END5')throw Error('ChecksumOrTrailer');return {value:JSON.parse(payload),size,hash:digest};}
const count=v=>{if(!/^(0|[1-9][0-9]*)$/.test(v))throw Error('InvalidSequence');const n=BigInt(v);if(n>0xffffffffffffffffn)throw Error('SequenceOverflow');return n;};
function state(snapshot,drafts=[]){return {drafts:clone(drafts),documentRevision:String(snapshot.documentRevision),sessionRevision:String(snapshot.sessionRevision),activeFlow:snapshot.activeFlow,selection:clone(snapshot.selection),selectionKind:clone(snapshot.selectionKind),typingIntent:clone(snapshot.typingIntent),isComposing:!!snapshot.active,committed:encodeDoc(snapshot.committedDocument),visible:encodeDoc(snapshot.document)};}
function validateState(s){count(s.documentRevision);count(s.sessionRevision);if(typeof s.isComposing!=='boolean'||!Array.isArray(s.drafts??[]))throw Error('InvalidState');for(const draft of s.drafts??[]){if(typeof draft.id!=='string')throw Error('InvalidDraft');decodeDoc(draft.committed);decodeDoc(draft.visible);}decodeDoc(s.committed);decodeDoc(s.visible);if(!s.isComposing&&!equal(decodeDoc(s.committed),decodeDoc(s.visible)))throw Error('UnmarkedStateDivergence');}
export class JournalModel{
 constructor(){this.volatile=Buffer.alloc(0);this.durable=Buffer.alloc(0);this.checkpoint=null;this.temporary=null;this.sequence=0n;this.hash=ZERO;this.latest=null;this.durableAck=0n;this.poisoned=false;this.pendingDrafts=[];}
 append(snapshot,{tearAt=null}={}){if(this.poisoned)throw Error('NeedsRecovery');const s=state(snapshot,this.pendingDrafts);validateState(s);count(String(this.sequence+1n));const record={format:'editor-journal/1',sequence:String(this.sequence+1n),previousHash:this.hash,state:s};const bytes=pack(record);this.volatile=Buffer.concat([this.volatile,tearAt==null?bytes:bytes.subarray(0,tearAt)]);if(tearAt!=null){this.poisoned=true;return {status:'Torn',bytes:bytes.length};}this.sequence++;this.hash=H(json(record));this.latest=s;return {status:'Appended',sequence:String(this.sequence),bytes:bytes.length};}
 sync({fail=false}={}){if(fail)return {status:'Failed',durableAck:String(this.durableAck)};this.durable=Buffer.from(this.volatile);this.durableAck=this.sequence;return {status:'Durable',durableAck:String(this.durableAck)};}
 checkpointAt(stage='complete'){if(this.sequence!==this.durableAck||!this.latest)throw Error('CheckpointNeedsDurableState');const cp={format:'editor-checkpoint/1',sequence:String(this.sequence),lastHash:this.hash,state:this.latest};this.temporary=pack(cp);if(stage==='beforeFlush'||stage==='beforeReplace')return;this.checkpoint=Buffer.from(this.temporary);this.temporary=null;if(stage==='afterReplace')return;this.durable=Buffer.alloc(0);this.volatile=Buffer.alloc(0);}
 recover(){let seq=0n,hash=ZERO,latest=null;const diagnostics=[];
  if(this.checkpoint){const cp=unpack(this.checkpoint,0);if(!cp||cp.size!==this.checkpoint.length||cp.value.format!=='editor-checkpoint/1')throw Error('CorruptCheckpoint');validateState(cp.value.state);seq=count(cp.value.sequence);hash=cp.value.lastHash;if(!/^[0-9a-f]{64}$/.test(hash))throw Error('CorruptCheckpointHash');latest=cp.value.state;}
  const base=seq;let off=0;
  while(off<this.durable.length){let f;try{f=unpack(this.durable,off);if(!f){diagnostics.push({offset:off,reason:'IncompleteTail'});break;}const v=f.value;if(v.format!=='editor-journal/1')throw Error('WrongFormat');const n=count(v.sequence);validateState(v.state);if(n<=base){off+=f.size;continue;}if(n!==seq+1n||v.previousHash!==hash)throw Error('BrokenSequenceOrChain');seq=n;hash=f.hash;latest=v.state;off+=f.size;}catch(e){diagnostics.push({offset:off,reason:e.message});break;}}
  if(!latest)throw Error('NoRecoverableRecord');return {sequence:String(seq),hash,state:clone(latest),diagnostics,validBytes:off};
 }
}
// Restore committed content by default; retain the entire provisional draft separately.
// No old native session, queue, anchor handle or ordinary undo stack is resurrected.
export function materializeRecovery(recovered){const s=recovered.state,dc=decodeDoc(s.committed),dv=decodeDoc(s.visible),m=new DocumentModel(dc);let ns=`recovery:${recovered.sequence}:`;const ids=[...flows(dc).values()].flatMap(({flow})=>project(flow).map(u=>u.tokenId));while(ids.some(id=>id.includes(ns)))ns='next:'+ns;m.idNamespace=ns;
 if(!s.isComposing&&flows(dc).has(s.activeFlow)&&Array.isArray(s.selection)&&s.selection.length===2&&s.selection.every(x=>Number.isSafeInteger(x)&&x>=0&&x<=project(getFlow(dc,s.activeFlow)).length))m._install(dc,{flowId:s.activeFlow,selection:s.selection,kind:s.selectionKind,intent:s.typingIntent,mapAnnotations:false,checkProtected:false});
 m.documentRevision=Number(s.documentRevision)+(s.isComposing&&!equal(dc,dv)?1:0);m.sessionRevision=Number(s.sessionRevision)+(s.isComposing?1:0);if(!Number.isSafeInteger(m.documentRevision)||!Number.isSafeInteger(m.sessionRevision))throw Error('ReferenceCounterCapacity');m._publish();const created=s.isComposing&&!equal(dc,dv)?{id:H(json(s)),committed:s.committed,visible:s.visible,flowId:s.activeFlow,selection:s.selection,selectionKind:s.selectionKind,typingIntent:s.typingIntent}:null;
 const drafts=clone(s.drafts??[]);if(created&&!drafts.some(d=>d.id===created.id))drafts.push(created);
 const record=drafts.at(-1),draft=record?{...record,committed:decodeDoc(record.committed),visible:decodeDoc(record.visible)}:null;
 return {model:m,draft,pendingDrafts:drafts};
}
// Recovery manager operation, executed only after the user explicitly chooses the draft.
export function acceptRecoveredDraft(m,draft){const result=m.dispatch({type:'recoverDraft',draft});if(result.status!=='Accepted')throw Error(result.reason??'RecoveryRejected');return result;}

export {pack as encodeFrame,unpack as decodeFrame};

// Resume with the last valid state as a checkpoint; retain recovery drafts across later saves.
export function resumeJournal(recovered){const j=new JournalModel();j.sequence=count(recovered.sequence);j.durableAck=j.sequence;j.hash=recovered.hash;j.latest=clone(recovered.state);j.checkpoint=pack({format:'editor-checkpoint/1',sequence:recovered.sequence,lastHash:j.hash,state:j.latest});j.pendingDrafts=materializeRecovery(recovered).pendingDrafts;return j;}
