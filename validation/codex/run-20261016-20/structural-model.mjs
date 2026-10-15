// Specification model only. Arrays/full-tree copies deliberately expose semantics.
// Not a native adapter, production index, or claimed performance implementation.
import {CandidateBCoordinator} from './candidate-b-coordinator.mjs';
import {immutableCopy} from './request-contract.mjs';
export const clone = structuredClone;
export const textBlock=(id,text,props={})=>({id,type:'paragraph',props,units:text.split('').map((char,i)=>({tokenId:`${id}:${i}`,char,kind:'text',attrs:{},originSessionId:null}))});
export const container=(id,type,children,props={})=>({id,type,children,props});
const textTypes=new Set(['paragraph','heading','code']);
export const leaves=root=>root.children?root.children.flatMap(leaves):[root];
export function index(root,map=new Map(),parent=null){map.set(root.id,{node:root,parent});for(const c of root.children??[])index(c,map,root.id);return map;}
export function validateTree(root){
  const seen=new Set();
  function visit(n){
    if(seen.has(n.id))throw Error('DuplicateNode');seen.add(n.id);
    if(n.children){
      if(!n.children.length)throw Error('EmptyContainer');
      if(n.type==='list'&&n.children.some(c=>c.type!=='item'))throw Error('ListGrammar');
      if(n.type!=='list'&&n.children.some(c=>c.type==='item'))throw Error('OrphanItem');
      n.children.forEach(visit);
    }else if(textTypes.has(n.type)){
      if(!Array.isArray(n.units)||n.units.some(u=>u.kind==='blockAtom'))throw Error('TextGrammar');
      for(const u of n.units)if(u.kind==='inlineAtom'){if(seen.has(u.nodeId))throw Error('DuplicateAtom');seen.add(u.nodeId);}
    }else if(n.type!=='object')throw Error('UnknownNode');
  }
  visit(root);if(!leaves(root).some(n=>textTypes.has(n.type)))throw Error('NoEditableBlock');return true;
}
export function project(root){
  const out=[];leaves(root).forEach((n,j)=>{
    if(j)out.push({kind:'boundary',char:'\n',attrs:{},tokenId:`boundary:${n.id}`,rightId:n.id,originSessionId:null});
    if(n.type==='object')out.push({kind:'blockAtom',char:'\ufffc',attrs:{},tokenId:`object:${n.id}`,nodeId:n.id,originSessionId:null});
    else out.push(...clone(n.units));
  });return out;
}
export const raw=units=>units.map(u=>u.char).join('');

// Boundary records are provenance, NEVER executable close/open-container commands.
// Kept block heads use the original ancestor paths; deleted heads are pruned.
// Only direct sibling list items merge. Other surviving ancestry is retained.
export function reconstruct(source, units, seed = {}, fault = null) {
  const src = index(source);
  const order = leaves(source);
  const leafIndex = new Map(order.map((l, idx) => [l.id, idx]));
  // Track which source leaf each original unit belonged to
  const unitLeafOwner = new Map();
  for (const l of order) {
    if (l.type === 'object') {
      unitLeafOwner.set(`object:${l.id}`, l.id);
    } else {
      for (const u of l.units ?? []) unitLeafOwner.set(u.tokenId, l.id);
    }
  }

  const first = order[0];
  const groups = [{id: first.id, nextBoundaryId: null, units: []}];
  for (const u of units) {
    if (u.kind === 'boundary') {
      groups.at(-1).nextBoundaryId = u.rightId;
      groups.push({id: u.rightId, nextBoundaryId: null, units: []});
    } else {
      groups.at(-1).units.push(clone(u));
    }
  }

  // F04: If a group's provisional head `g.id` is a block object (`old.type === 'object'`),
  // check whether the block object itself was removed AND its trailing boundary in `source` was also removed.
  // - If the block object's trailing boundary survived (or the object was the last leaf and no later leaf was merged into `g`),
  //   then ONLY the block object's content was replaced while retaining its surrounding boundaries (§4.3 line 258): convert `g.id` to a Paragraph at `g.id`.
  // - If the block object's trailing boundary was deleted (merging `g` with a following leaf in `source`), the deleted block object
  //   cannot absorb the following leaf or destroy its identity/ancestry: advance `g.id` to the surviving merged leaf!
  for (const g of groups) {
    const old = src.get(g.id)?.node;
    if (old?.type === 'object') {
      const hasOwnObject = g.units.some(u => u.kind === 'blockAtom' && u.nodeId === g.id);
      if (!hasOwnObject) {
        const startIdx = leafIndex.get(g.id);
        const endIdx = g.nextBoundaryId != null ? leafIndex.get(g.nextBoundaryId) : order.length;
        // If endIdx > startIdx + 1, at least one trailing boundary after `g.id` was deleted into this group!
        if (endIdx > startIdx + 1) {
          const objUnits = g.units.filter(u => u.kind === 'blockAtom');
          if (objUnits.length === 1 && src.has(objUnits[0].nodeId)) {
            g.id = objUnits[0].nodeId;
          } else {
            // Find the surviving leaf owner among retained units, or the last merged leaf in [startIdx + 1, endIdx - 1]
            const retainedOwner = g.units.map(u => unitLeafOwner.get(u.tokenId)).find(id => id && id !== g.id);
            g.id = retainedOwner ?? order[endIdx - 1].id;
          }
        }
      }
    }
  }

  const textTypes = new Set(['paragraph', 'heading', 'code']);
  const records = new Map();
  for (const g of groups) {
    if (records.has(g.id) || !src.has(g.id)) throw Error('InvalidBoundaryProvenance');
    const old = src.get(g.id).node;
    const objects = g.units.filter(u => u.kind === 'blockAtom');
    if (objects.length) {
      if (g.units.length !== 1 || objects[0].nodeId !== g.id) throw Error('UnrepresentableObjectSeam');
      records.set(g.id, clone(old));
    } else {
      records.set(g.id, {
        id: g.id,
        type: old.type === 'code' && g.units.some(u => u.kind === 'inlineAtom')
          ? 'paragraph'
          : textTypes.has(old.type) ? old.type : 'paragraph',
        props: clone(
          old.type === 'code' && g.units.some(u => u.kind === 'inlineAtom')
            ? {}
            : textTypes.has(old.type) ? old.props : {}
        ),
        units: g.units,
        ...(old.emptyBlockAttributes ? {emptyBlockAttributes: clone(old.emptyBlockAttributes)} : {}),
        ...(!g.units.length && old.units?.length ? {emptyBlockAttributes: clone(seed)} : {})
      });
    }
  }

  const work = clone(source);
  let survivor = groups[0].id;
  for (const leaf of order) {
    if (records.has(leaf.id)) {
      survivor = leaf.id;
      continue;
    }
    const wi = index(work), left = wi.get(survivor), right = wi.get(leaf.id), lp = left?.parent, rp = right?.parent;
    if (lp !== rp && wi.get(lp)?.node.type === 'item' && wi.get(rp)?.node.type === 'item' && wi.get(lp).parent === wi.get(rp).parent) {
      const dest = wi.get(lp)?.node, from = wi.get(rp)?.node;
      if (dest && from) {
        const tail = from.children.filter(n => n.id !== leaf.id);
        dest.children.push(...tail);
        from.children = [];
      }
    }
  }
  function prune(n) {
    if (!n.children) return records.get(n.id) ?? null;
    const kids = n.children.map(prune).filter(Boolean);
    return kids.length ? {...n, children: kids} : null;
  }
  const result = prune(work);
  if (!result) throw Error('MissingLandingBlock');
  if (fault === 'reorder') {
    for (const n of index(result).values()) {
      if (n.node.children?.length > 1) {
        n.node.children.reverse();
        break;
      }
    }
  }
  validateTree(result);
  if (raw(project(result)) !== raw(units)) throw Error('ProjectionMismatch');
  return result;
}


export class StructuralModel extends CandidateBCoordinator {
  constructor(root,selection=[0,0]){
    validateTree(root);const units=project(root);super(raw(units),units.map(u=>u.attrs),selection);
    this.source=clone(root);this.committedDoc=units;this._publishWorkingState();
  }
  tree(){return this.snapshot().structuralTree;}
  committedTree(){return reconstruct(this.source,this.committedDoc,this.seed??{});}
  _cloneState(){return {...super._cloneState(),_publishedState:this._publishedState,structuralSeed:clone(this.seed),structuralOwner:this.activeOwner,admittedStructuralIds:clone(this.admittedStructuralIds),admittedOwnerIds:clone(this.admittedOwnerIds)};}
  _restoreState(s){super._restoreState(s);this.seed=clone(s.structuralSeed);this.activeOwner=s.structuralOwner;this.admittedStructuralIds=clone(s.admittedStructuralIds);this.admittedOwnerIds=clone(s.admittedOwnerIds);}
  _rangeOwners(units,a,b){
    let owner=leaves(this.source)[0].id;const ids=new Set();
    for(let i=0;i<=units.length;i++){
      if(i===a&&a===b||i>=a&&i<b)ids.add(owner);
      if(units[i]?.kind==='boundary'){if(i>=a&&i<b)ids.add(units[i].rightId);owner=units[i].rightId;}
    }
    return ids;
  }
  _publishWorkingState(){
    super._publishWorkingState();
    if(this.source)this._publishedState=immutableCopy({...this._publishedState,structuralTree:reconstruct(this.source,this._publishedState.units,this.seed??{})});
  }
  _semanticStructureKey(){
    if(!this.source)return super._semanticStructureKey();
    return JSON.stringify(reconstruct(this.source,this._getWorkingVisibleUnits(),this.seed??{}));
  }
  _dispatchPayload(p){
    if(p.flowId!=null&&p.flowId!==this.source.id)return {status:'Rejected',reason:'FlowMismatch'};
    const r=this._resolveTargetDescriptor(p);
    if(['startComposition','preedit','retarget','targetExpansion','commit','nativeRemoval'].includes(p.type)&&r.valid){
      const [a,b]=r.range,units=this._getWorkingVisibleUnits();
      if(units.slice(a,b).some(u=>u.locked))return {status:'Rejected',reason:'ReadOnlyContent'};
      // Initial selected replacements can remove objects/ancestry. Later retarget
      // cannot acquire a previously unselected object or cross an ancestry fence.
      if(['retarget','targetExpansion'].includes(p.type)&&this.historySession){
        const admitted=this.admittedStructuralIds??new Set();
        if(units.slice(a,b).some(u=>(u.kind==='inlineAtom'||u.kind==='blockAtom')&&!admitted.has(u.tokenId)))return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
        const boundaries=units.slice(a,b).filter(u=>u.kind==='boundary'),m=index(this.tree()),source=index(this.source);
        const anchor=m.get(this.activeOwner)??source.get(this.activeOwner),joinClass=n=>n?.type==='code'?'code':['paragraph','heading'].includes(n?.type)?'prose':'object';
        const allowed=id=>this.admittedOwnerIds?.has(id)||(m.get(id)?.parent===anchor?.parent&&joinClass(m.get(id)?.node)===joinClass(anchor?.node));
        if([...this._rangeOwners(units,a,b)].some(id=>!allowed(id)))return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
        if(boundaries.some(u=>!admitted.has(u.tokenId)&&(m.get(u.rightId)?.parent!==anchor?.parent||joinClass(m.get(u.rightId)?.node)!==joinClass(anchor?.node))))return {status:'Rejected',reason:'ProtectedStructuralBoundary'};
      }
      if(!this.historySession){
        this.admittedStructuralIds=new Set(units.slice(a,b).filter(u=>['inlineAtom','blockAtom','boundary'].includes(u.kind)).map(u=>u.tokenId));
        this.admittedOwnerIds=this._rangeOwners(units,a,b);
        this.seed=clone(units.slice(a,b).find(u=>u.kind==='text'||u.kind==null)?.attrs??{});
        this.activeOwner=leaves(this.source)[0].id;
        for(let i=0;i<a;i++)if(units[i]?.kind==='boundary')this.activeOwner=units[i].rightId;
      }
    }
    return super._dispatchPayload(p);
  }
}
