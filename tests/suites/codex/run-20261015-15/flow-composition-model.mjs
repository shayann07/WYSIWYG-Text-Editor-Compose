import {CandidateBCoordinator} from './candidate-b-coordinator.mjs';
import {immutableCopy} from './request-contract.mjs';

const blockRecord=b=>({id:b.id,type:b.type??'paragraph',properties:structuredClone(b.properties??{}),parentId:b.parentId??'body',emptyBlockAttributes:structuredClone(b.emptyBlockAttributes??{})});
const compatible=(a,b)=>a.parentId===b.parentId &&
  ((['paragraph','heading'].includes(a.type)&&['paragraph','heading'].includes(b.type)) || (a.type==='code'&&b.type==='code'));

// Independent of a platform host. Wraps the existing composition coordinator
// with a TAGGED flow tape and a deterministic hierarchical materialization.
// Array operations here are reference-model work, not production cost claims.
export class FlowCompositionModel extends CandidateBCoordinator {
  constructor(blocks,selection=[0,0],flowId='body') {
    if(!blocks.length||new Set(blocks.map(b=>b.id)).size!==blocks.length)throw Error('Invalid blocks');
    const records=blocks.map(blockRecord),units=[];
    for(let b=0;b<blocks.length;b++) {
      if(b)units.push({tokenId:`${flowId}:boundary:${blocks[b].id}`,kind:'boundary',char:'\n',attrs:{},originSessionId:null,rightBlock:records[b],joinable:compatible(records[b-1],records[b])});
      const block=blocks[b];
      for(let i=0;i<block.text.length;i++)units.push({tokenId:`${flowId}:${block.id}:${i}`,kind:'text',char:block.text[i],attrs:structuredClone(block.textAttrs?.[i]??{}),originSessionId:null});
    }
    super(units.map(u=>u.char).join(''),units.map(u=>u.attrs),selection);
    this.flowId=flowId;this.head=immutableCopy(records[0]);this.committedDoc=structuredClone(units);this._publishWorkingState();
  }
  _dispatchPayload(payload) {
    if(payload.flowId!=null&&payload.flowId!==this.flowId)return {status:'Rejected',reason:'FlowMismatch'};
    if(['startComposition','preedit','retarget','targetExpansion','commit','nativeRemoval'].includes(payload.type)) {
      const resolved=this._resolveTargetDescriptor(payload);
      if(!resolved.valid)return {status:'Rejected',reason:'InvalidTargetRange'};
      const [a,b]=resolved.range,units=this._getWorkingVisibleUnits();
      if(units.slice(a,b).some(u=>u.kind==='atom'||u.kind==='boundary'&&!u.joinable))
        return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
      if(this.historySession?.compositionDomain && this.historySession.compositionDomain!==this._domainAt(a))
        return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
    }
    return super._dispatchPayload(payload);
  }
  _ownerAt(offset) {
    let owner=this.head;
    this._getWorkingVisibleUnits().forEach((u,i)=>{if(i<offset&&u.kind==='boundary')owner=u.rightBlock;});
    return owner;
  }
  _domainAt(offset) {
    const owner=this._ownerAt(offset);
    return JSON.stringify([this.flowId,owner.parentId,owner.type==='code'?'code':'prose']);
  }
  _freezeSeed(range) {
    if(!this.attributionEpoch)return;
    const units=this._getWorkingVisibleUnits(),[a,b]=range;
    const text=units.slice(a,b).find(u=>u.kind!=='boundary') ?? units[a-1] ?? units[b];
    this.attributionEpoch.seed=structuredClone(text?.kind==='boundary'||!text?this._ownerAt(a).emptyBlockAttributes:text.attrs);
  }
  _startComposition(range,...args) {
    const result=super._startComposition(range,...args);
    if(result.status!=='Rejected'){this.historySession.compositionDomain=this._domainAt(range[0]);this._freezeSeed(range);if(this.provisionalOverlay)this.provisionalOverlay.flowId=this.flowId;}
    return result;
  }
  _retarget(range,...args) {
    const result=super._retarget(range,...args);
    if(result.status!=='Rejected'){this.historySession.compositionDomain??=this._domainAt(range[0]);this._freezeSeed(range);if(this.provisionalOverlay)this.provisionalOverlay.flowId=this.flowId;}
    return result;
  }
  materialize(units=this.snapshot().units) {
    const blocks=[];let current={...structuredClone(this.head),text:'',textAttrs:[]};
    for(const unit of units) {
      if(unit.kind==='boundary') {
        blocks.push(current);current={...structuredClone(unit.rightBlock),text:'',textAttrs:[]};
      }else {current.text+=unit.char;current.textAttrs.push(structuredClone(unit.attrs));}
    }
    blocks.push(current);
    if(new Set(blocks.map(b=>b.id)).size!==blocks.length)throw Error('Duplicate visible block ID');
    return blocks;
  }
  structuralSnapshot() {
    return immutableCopy({flowId:this.flowId,blocks:this.materialize(),selection:this.selection,markedRange:this.markedRange,documentRevision:this.documentRevision,sessionRevision:this.sessionRevision});
  }
  committedBlocks() {return this.materialize(this.committedDoc);}
}

// Tagged projection admission for a connected text scope. Ordinary paragraph
// boundaries are accepted; protected atoms/cells/incompatible ancestry are not
// silently reinterpreted as text. The adapter, not plaintext, supplies tags.
export function admitFlowComposition(segments,range) {
  const length=segments.at(-1)?.end??0;
  if(!Array.isArray(range)||range.length!==2||!range.every(Number.isInteger)||range[0]<0||range[1]<range[0]||range[1]>length)return {status:'Rejected',reason:'InvalidTargetRange'};
  const touched=segments.filter(s=>s.start<range[1]&&s.end>range[0]);
  if(touched.some(s=>s.kind==='atom'||s.kind==='cellBoundary'||s.kind==='blockSeparator'&&s.joinable!==true))return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
  return {status:'Accepted',range:[...range],blockIds:[...new Set(touched.map(s=>s.blockId).filter(Boolean))]};
}
