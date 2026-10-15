// Whole-document executable reference. All edits share IntegratedModel.dispatch.
import {IntegratedModel,nativeTypes,equal,validRange,semantic} from './integrated-model.mjs';
import {createHash} from 'node:crypto';
import {StructuralModel} from './structural-model.mjs';
import {immutableCopy,mapRange} from './request-contract.mjs';
import * as T from './document-tree.mjs';
const {clone,err,flows,getFlow,putFlow,locate,index,leaves,project,raw,isText,isTable,pointAt,pointOffset,mapPoint,validatePoint,validateDocument,leafRanges,patchAttrs,textUnits,paragraph,normalize,spliceFlow,splitBlock,nearestItem,liftItem,indentItem,splitItem,cloneFresh,cellFlow,table,parseTSV}=T;
export const COMMANDS=Object.freeze(['typeText','replaceSelection','deleteSelection','backspace','deleteForward','deleteCodePoints','enter','softBreak','moveCaret','setBlockType','patchNode','patchCell','wrapQuote','unwrapQuote','wrapList','unwrapList','indent','outdent','moveNode','deleteNode','insertInlineAtom','insertBlockObject','insertTable','insertRow','deleteRow','insertColumn','deleteColumn','deleteTable','tableTab','clearCells','copy','cut','pasteText','pasteRich','pasteTSV','selectFlow','selectCells','selectNode','selectBlocks','registerAnchor','moveAnchor','unregisterAnchor','capturePaste','applyPaste','cancelPaste','putResource','removeResource','addAnnotation','removeAnnotation','cancelCommand','focusLost']);
const sessionOnly=new Set(['moveCaret','selectFlow','selectCells','selectNode','selectBlocks','registerAnchor','moveAnchor','unregisterAnchor','capturePaste','cancelPaste','cancelCommand','copy']);
const immediateWhileComposing=new Set(['registerAnchor','moveAnchor','unregisterAnchor','capturePaste','cancelPaste','cancelCommand']);
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const captureRange=(range,units,affinity='after')=>{const a=Math.min(...range),b=Math.max(...range);return {ids:units.slice(a,b).map(u=>u.tokenId),left:a?units[a-1].tokenId:null,right:b<units.length?units[b].tokenId:null,collapsed:a===b,affinity};};
const resolveRange=(c,units)=>{const ranks=new Map(units.map((u,i)=>[u.tokenId,i]));if(c.collapsed){const left=c.left===null?-1:ranks.get(c.left),right=c.right===null?units.length:ranks.get(c.right);if(left===undefined&&right===undefined)err('TargetDeleted');const p=c.affinity==='before'?(left===undefined?right:left+1):(right===undefined?left+1:right);return [p,p];}const p=c.ids.map(id=>ranks.get(id));if(p.some(x=>x===undefined))err('TargetDeleted');if(p.some((x,i)=>i&&x!==p[i-1]+1))err('TargetFragmented');return [p[0],p.at(-1)+1];};
const admittedError=e=>/^(Target|ReadOnly|FlowDeleted|NodeDeleted|NoPrevious|NoNext|LastTable|Nothing|Selection|Invalid|Unsupported|Nested|Admission|TableAdmission)/.test(e.message);

export class DocumentModel extends IntegratedModel{
  constructor(document,selection=[0,0],options={}){
    const doc=document.type==='document'?clone(document):T.makeDocument(document);validateDocument(doc);super(doc.children[0],selection,options);
    this.docBase=doc;this.activeFlow=doc.children[0].id;this.selectionKind={type:'text'};this.anchors=new Map();this.pastes=new Map();this.clipboard=null;this.typingIntent={mode:'inherit',side:'left'};this.annotationView=clone(doc.props.annotations??[]);this.cellPrelude=null;this.lastTyping=null;
    this.commandLimits={anchors:4096,pastes:128,cells:4096,nodes:10000,annotations:1024,resources:1024,...options.commandLimits};this.now=options.now??(()=>0);this._publish();
  }
  _doc(committed=false){
    if(!this.docBase)return this.kernel.tree();
    const base=!committed&&this.cellPrelude?this.cellPrelude.base:this.docBase,doc=clone(base);
    putFlow(doc,this.activeFlow,clone(committed?this.kernel.committedTree():this.kernel.tree()));
    if(!committed)doc.props.annotations=clone(this.annotationView??doc.props.annotations??[]);return doc;
  }
  _semanticCurrent(){return this.docBase?this._doc():super._semanticCurrent();}
  _semanticCommitted(){return this.docBase?this._doc(true):super._semanticCommitted();}
  _bundleSemantic(b){return b.document??b.tree;}
  _bundle(){return this.docBase?{tree:clone(this.kernel.tree()),document:this._doc(),activeFlow:this.activeFlow,selection:this.kernel.selection,selectionKind:clone(this.selectionKind),typingIntent:clone(this.typingIntent),anchors:clone(this.anchors)}:super._bundle();}
  _save(){const s=super._save();s.documentState=clone({docBase:this.docBase,activeFlow:this.activeFlow,selectionKind:this.selectionKind,anchors:this.anchors,pastes:this.pastes,clipboard:this.clipboard,typingIntent:this.typingIntent,annotationView:this.annotationView,cellPrelude:this.cellPrelude,lastTyping:this.lastTyping,explicitSelectionBarrier:this.explicitSelectionBarrier,historySelectionRestore:this.historySelectionRestore});return s;}
  clipboardSnapshot(id=null){if(id!=null&&id!==this.clipboard?.id)return immutableCopy({status:'ClipboardExpired'});return immutableCopy(this.clipboard);}
  _restore(s){if(s.documentState)Object.assign(this,s.documentState);super._restore(s);}
  _makeBasis(flowId,doc,selection,frame=this){const f=getFlow(doc,flowId),ranges=leafRanges(f),nodes={};for(const [id,{node,parent}]of index(f)){const ls=leaves(node),first=ranges.find(r=>r.id===ls[0].id),last=ranges.find(r=>r.id===ls.at(-1).id);nodes[id]={parent,children:node.children?.map(n=>n.id),start:first.start,end:last.end,text:isText(node)};}const cellTargets=flowId===frame.activeFlow&&frame.selectionKind?.type==='cells'?this._cells(locate(doc,frame.selectionKind.tableId).node,frame.selectionKind).map(cell=>({cellId:cell.id,flowId:cell.flow.id,contentHash:createHash('sha256').update(semantic(cell.flow)).digest('hex'),units:project(cell.flow).map(u=>({tokenId:u.tokenId}))})):null;return immutableCopy({cellTargets,flowId,units:project(f).map(u=>({tokenId:u.tokenId})),nodes,selection,selectionKind:flowId===frame.activeFlow?frame.selectionKind:{type:'text'},selectionGeneration:frame.selectionGeneration,documentRevision:frame.documentRevision,sessionRevision:frame.sessionRevision,epoch:frame.epoch,generation:frame.generation});}
  _compactBasis(){return this.docBase?this._makeBasis(this.activeFlow,this._doc(),this.kernel.selection):super._compactBasis();}
  _publish(basis=null){
    super._publish(basis);if(!this.docBase)return;
    const document=this._doc(),committedDocument=this._doc(true);validateDocument(document);validateDocument(committedDocument);
    this.published=immutableCopy({...this.published,document,committedDocument,activeFlow:this.activeFlow,selectionKind:this.selectionKind,typingIntent:this.typingIntent,anchors:[...this.anchors],pasteLeases:[...this.pastes.keys()]});
    this.publishedBasis=basis??this._makeBasis(this.activeFlow,document,this.kernel.selection);
  }
  _requestBasis(p){
    const id=p.flowId??p.point?.flowId??this.published.activeFlow;if(nativeTypes.has(p.type)&&id!==this.published.activeFlow)err('FlowMismatch');if(!this.docBase||id===this.published.activeFlow)return this.publishedBasis;
    return this._makeBasis(id,this.published.document,p.range??[0,0],this.published);
  }
  _requestContext(p){if(!this.docBase)return null;const id=p.flowId??p.point?.flowId??this.published.activeFlow;return {typingIntent:clone(id===this.published.activeFlow?this.published.typingIntent:{mode:'inherit',side:'left'})};}
  _checkCapacity(q){super._checkCapacity(q);if(Buffer.byteLength(JSON.stringify([...this._queued(),q].map(r=>({payload:r.payload,context:r.context})))+JSON.stringify(this.pending),'utf8')>this.retention.pendingBytes)err('AdmissionCapacityExceeded');}
  _allocate(q){let counter=0;const used=new Set();const doc=this._doc();for(const {flow}of flows(doc).values()){for(const id of index(flow).keys())used.add(id);for(const u of project(flow)){used.add(u.tokenId);if(u.nodeId)used.add(u.nodeId);}}const allocate=kind=>{let id;do{id=`new:${q.ticket}:${++counter}:${kind}`;}while(used.has(id));used.add(id);return id;};allocate.maxCells=this.commandLimits.cells;return allocate;}
  _mapAnnotations(oldDoc,newDoc){
    const existing=new Map((oldDoc.props.annotations??[]).map(a=>[a.id,a]));
    newDoc.props.annotations=(newDoc.props.annotations??[]).map(a=>{const old=existing.get(a.id);if(!old||!same(old.anchor,a.anchor)||!same(old.focus,a.focus))return a;const left=mapPoint(old.anchor,oldDoc,newDoc,'before'),right=mapPoint(old.focus,oldDoc,newDoc,'after');let lost=false;
      if(old.anchor.flowId===old.focus.flowId){const f=getFlow(oldDoc,old.anchor.flowId),lo=pointOffset(f,old.anchor),hi=pointOffset(f,old.focus),ids=project(f).slice(Math.min(lo,hi),Math.max(lo,hi)).map(u=>u.tokenId),surviving=new Set([...flows(newDoc).values()].flatMap(({flow})=>project(flow).map(u=>u.tokenId)));lost=ids.length>0&&ids.every(id=>!surviving.has(id));}
      return {...a,anchor:left.point,focus:right.point,orphaned:!!a.orphaned||lost||left.deleted&&right.deleted};});
  }
  _mapAnchors(oldDoc,newDoc,restore=null){
    for(const [id,a]of this.anchors){const saved=restore?.get(id);if(saved&&saved.generation===a.generation){validatePoint(newDoc,saved.point);this.anchors.set(id,clone(saved));}
      else{const mapped=mapPoint(a.point,oldDoc,newDoc,a.association);this.anchors.set(id,{...a,point:mapped.point,deleted:!!a.deleted||mapped.deleted});}}
  }
  _install(doc,{flowId=this.activeFlow,selection=null,kind={type:'text'},intent=this.typingIntent,restoreAnchors=null,mapAnnotations=true,checkProtected=true}={}){
    const before=this._doc();if(checkProtected)this._protect(before,doc);if(mapAnnotations)this._mapAnnotations(before,doc);
    if(!flows(doc).has(flowId)){const oldPoint=pointAt(getFlow(before,this.activeFlow),Math.min(...this.kernel.selection));const mapped=mapPoint(oldPoint,before,doc,'after');flowId=mapped.point.flowId;selection=[pointOffset(getFlow(doc,flowId),mapped.point),pointOffset(getFlow(doc,flowId),mapped.point)];kind={type:'text'};}
    if(selection==null){if(flowId===this.activeFlow){const old=getFlow(before,flowId),now=getFlow(doc,flowId);selection=this.kernel.selection.map(x=>T.gapMap(project(old),project(now),x,'after').offset);}else selection=[0,0];}
    if(kind?.type==='cells'){const t=locate(doc,kind.tableId);try{if(!isTable(t?.node))err('TargetTableDeleted');this._cells(t.node,kind);}catch{kind={type:'text'};}}
    if(kind?.type==='node'){
      const e=locate(doc,kind.nodeId)??T.locateAtom(doc,kind.nodeId);
      if(!e)kind={type:'text'};
      else{const rs=leafRanges(e.flow),r=e.inline?null:rs.find(x=>x.id===e.node.id);if(e.inline){const start=pointOffset(e.flow,{kind:'text',blockId:e.block.id,offset:e.offset});flowId=e.flowId;selection=[start,start+1];}else if(r){flowId=e.flowId;selection=[r.start,r.end];}else kind={type:'text'};}
    }
    if(kind?.type==='blocks'){
      const a=locate(doc,kind.first),b=locate(doc,kind.last);if(!a||!b||a.flowId!==b.flowId)kind={type:'text'};
    }
    validateDocument(doc);if(!validRange(selection,project(getFlow(doc,flowId)).length))err('InvalidSelection');
    this._mapAnchors(before,doc,restoreAnchors);const changedFlow=flowId!==this.activeFlow;
    this.docBase=clone(doc);this.activeFlow=flowId;this.annotationView=clone(doc.props.annotations??[]);this.cellPrelude=null;this.kernel=new StructuralModel(getFlow(doc,flowId),selection);this.selectionKind=clone(kind);this.typingIntent=clone(intent);
    // Connection identity is decided from the FINAL published scope, not from
    // transient scopes visited by commands/Undo inside one atomic settlement.
  }
  _adopt(b){if(!this.docBase||!b.document)return super._adopt(b);this._install(clone(b.document),{flowId:b.activeFlow,selection:b.selection,kind:b.selectionKind,intent:b.typingIntent,restoreAnchors:b.anchors,mapAnnotations:false,checkProtected:false});}
  _restoreHistory(b){this._adopt(b);this.historySelectionRestore=true;}
  _execute(q){this.explicitSelectionBarrier=false;this.historySelectionRestore=false;super._execute(q);}
  _protect(before,after){
    const newUnits=new Map([...flows(after).values()].flatMap(({flow})=>project(flow).map(u=>[u.tokenId,u])));
    for(const {flow}of flows(before).values()){for(const u of project(flow))if(u.locked&&(!newUnits.has(u.tokenId)||!equal(u,newUnits.get(u.tokenId))))err('ReadOnlyContent');for(const [id,e]of index(flow))if(e.node.props?.locked){const now=locate(after,id);if(!now||now.parent!==e.parent||now.flowId!==flow.id||!equal(now.node,e.node))err('ReadOnlyContent');}}
  }
  _beforePublication(saved,q){
    if(!this.docBase)return;const previous=saved.published,scopeChanged=this.activeFlow!==previous.activeFlow;
    const selectionChanged=!same({flow:previous.activeFlow,range:previous.selection,kind:previous.selectionKind},{flow:this.activeFlow,range:this.kernel.selection,kind:this.selectionKind});
    const kindChanged=!same(previous.selectionKind,this.selectionKind);
    this.selectionGeneration=saved.fields.selectionGeneration+((this.explicitSelectionBarrier||scopeChanged||kindChanged||this.historySelectionRestore&&selectionChanged)?1:0);
    if(scopeChanged){this.epoch=Math.max(this.epoch,saved.fields.epoch+1);this.generation=1;this.hostFence=false;}
    else if(this.explicitSelectionBarrier){this.generation=Math.max(this.generation,saved.fields.generation+1);this.hostFence=false;}
  }
  _start(q,target){const selected=this._basis(q).selectionKind;const cellSelection=q.payload.range==null&&selected?.type==='cells'?clone(selected):null,seed=this._seed(getFlow(this._doc(),this.activeFlow),target.range);super._start(q,target);this.active.flowId=this.activeFlow;this.active.cellSelection=cellSelection;this.active.explicitLinkAtStart=!!this.typingIntent.explicitLink;this.kernel.attributionEpoch.seed=clone(seed);this.kernel.seed=clone(seed);this.kernel._publishWorkingState();}
  _clearCellFlows(doc,selection,allocate){const tableEntry=locate(doc,selection.tableId);if(!isTable(tableEntry?.node))err('TargetTableDeleted');const cells=this._cells(tableEntry.node,selection);for(const cell of cells){const old=cell.flow,head=leaves(old).find(isText);cell.flow={...old,children:[head?{id:head.id,type:'paragraph',props:{},units:[],emptyBlockAttributes:clone(head.units.find(u=>u.kind==='text'||u.kind==null)?.attrs??head.emptyBlockAttributes??{})}:paragraph(allocate)]};}return cells;}
  _call(p){
    if(!this.docBase)return super._call(p);
    const before=this._doc(),beforeCommitted=this._doc(true),savedActive=this.active;
    if(['preedit','nativeRemoval'].includes(p.type)&&this.active?.cellSelection&&!this.cellPrelude){const base=clone(this.docBase);this._clearCellFlows(base,this.active.cellSelection,this._allocate(this.executing));this.cellPrelude={base,accepted:false};this.selectionKind={type:'text'};}
    if(['preedit','nativeRemoval'].includes(p.type))this.selectionKind={type:'text'};
    const r=super._call(p);
    if(p.type==='preedit'&&this.active?.explicitLinkAtStart&&this.kernel.provisionalOverlay){let afterSpace=false;const prefix=`prov:${this.active.engineId}:e${this.kernel.attributionEpoch.epochId}:m`;
      for(const seg of this.kernel.provisionalOverlay.segments)for(const u of seg.units){if(u.originSessionId===this.active.engineId&&/\s/u.test(u.char))afterSpace=true;if(afterSpace&&u.tokenId.startsWith(prefix))delete u.attrs.link;}
      this.kernel._publishWorkingState();
    }
    if(p.type==='partialCommit'&&p.length>0&&this.cellPrelude){this.cellPrelude.accepted=true;this.docBase=clone(this.cellPrelude.base);}
    if(p.type==='cancel')this.cellPrelude=null;
    const after=this._doc();this._protect(before,after);this._mapAnnotations(before,after);this.annotationView=clone(after.props.annotations);
    if(p.type==='cancel'&&savedActive&&equal(savedActive.before.document,this._doc(true))){this.annotationView=clone(savedActive.before.document.props.annotations);this.selectionKind=clone(savedActive.before.selectionKind);this.typingIntent=clone(savedActive.before.typingIntent);after.props.annotations=clone(this.annotationView);this._mapAnchors(before,after,savedActive.before.anchors);}
    else this._mapAnchors(before,after);
    if(p.type==='partialCommit'){const accepted=this._doc(true);this._mapAnnotations(beforeCommitted,accepted);this.docBase.props.annotations=clone(accepted.props.annotations);}
    if(p.type==='partialCommit'&&p.length>0)this._endAcceptedLinkIntent();
    return r;
  }
  _endAcceptedLinkIntent(){if(this.active?.explicitLinkAtStart&&this.kernel.committedDoc.some(u=>u.originSessionId===this.active.engineId&&/\s/u.test(u.char))){this.typingIntent=clone(this.typingIntent);this.typingIntent.explicitLink=false;if(this.typingIntent.base)delete this.typingIntent.base.link;if(this.typingIntent.patch)delete this.typingIntent.patch.link;}}
  _acceptVisible(){if(this.cellPrelude){this.docBase=clone(this.cellPrelude.base);this.cellPrelude=null;}super._acceptVisible();if(this.docBase){this.docBase.props.annotations=clone(this.annotationView);this._endAcceptedLinkIntent();}}
  _nativeSelection(selection){super._nativeSelection(selection);if(this.docBase&&selection!=null)this.selectionKind={type:'text'};}
  _target(q){const b=this._basis(q);if(this.docBase&&b.flowId!==this.activeFlow)err('FlowMismatch');return super._target(q);}
  _pointFromBasis(p,b){
    if(!['text','gap'].includes(p.kind))err('InvalidPoint');
    if(p.kind==='gap'){const parent=b.nodes[p.parentId];if(!parent?.children||!Number.isInteger(p.index)||p.index<0||p.index>parent.children.length)err('InvalidGap');return p.index<parent.children.length?b.nodes[parent.children[p.index]].start:parent.end;}
    const block=b.nodes[p.blockId];if(!block?.text||!Number.isSafeInteger(p.offset)||p.offset<0||p.offset>block.end-block.start)err('InvalidPoint');return block.start+p.offset;
  }
  _resolvePoint(q,p){const b=this._basis(q),f=getFlow(this._doc(),p.flowId);if(b.flowId!==p.flowId)err('FlowMismatch');const offset=this._pointFromBasis(p,b),mapped=mapRange([offset,offset],b.units,project(f),p.association??'after')[0];if(p.kind==='gap'&&this.documentRevision===b.documentRevision){validatePoint(this._doc(),p);const {association,...point}=p;return clone(point);}return pointAt(f,mapped,p.association??'after');}
  _cells(tableNode,selection){const rows=tableNode.props.rows;const positions=new Map(rows.flatMap((r,i)=>r.cells.map((cell,j)=>[cell.id,{i,j}]))),a=positions.get(selection.anchorCell),b=positions.get(selection.focusCell);if(!a||!b)err('TargetCellDeleted');const out=[];for(let i=Math.min(a.i,b.i);i<=Math.max(a.i,b.i);i++)for(let j=Math.min(a.j,b.j);j<=Math.max(a.j,b.j);j++)out.push(rows[i].cells[j]);return out;}
  _validatePatch(p){if(!p||typeof p!=='object'||Array.isArray(p))return false;return Object.entries(p).every(([k,v])=>v===null||(['boolean','string'].includes(typeof v)||typeof v==='number'&&Number.isFinite(v))||k==='link'&&typeof v.id==='string'&&/^(https?:|mailto:)/i.test(v.url)||k==='code'&&typeof v.id==='string');}
  _importResources(data,doc,allocate,resources={}){const map=new Map();for(const [id,value]of Object.entries(resources)){if(!value||typeof value!=='object'||/^(javascript:|vbscript:|data:text\/html)/i.test(value.uri??''))err('InvalidResource');const fresh=allocate('resource');map.set(id,fresh);doc.props.resources[fresh]=clone(value);}const walk=v=>{if(v&&typeof v==='object'){if(v.resourceId&&map.has(v.resourceId))v.resourceId=map.get(v.resourceId);Object.values(v).forEach(walk);}};walk(data);return data;}
  _sanitize(data){let removedUnsafeLinks=0,droppedAttributes=0;const allowed=new Set(['bold','italic','underline','strike','color','font','fontSize','language','direction','link','code']);const walk=v=>{if(v&&typeof v==='object'){if(v.tokenId&&typeof v.char==='string')for(const key of Object.keys(v.attrs??{})){if(!allowed.has(key)){delete v.attrs[key];droppedAttributes++;}else if(key==='link'&&v.attrs.link!==false&&!/^(https?:|mailto:)/i.test(v.attrs.link?.url??'')){delete v.attrs.link;removedUnsafeLinks++;}}Object.values(v).forEach(walk);}};walk(data);return {removedUnsafeLinks,droppedAttributes};}
  _seed(flow,range,intent=this.typingIntent){
    const [a,b]=range,tape=project(flow),owner=pointAt(flow,a),block=index(flow).get(owner.blockId).node;let attrs;
    if(a!==b)attrs=tape.slice(a,b).find(u=>u.kind==='text'||u.kind==null)?.attrs??block.emptyBlockAttributes??{};
    else if(intent.mode==='explicit')attrs=patchAttrs(intent.base,intent.patch);
    else{const side=owner.offset===0?'right':intent.side??'left',left=tape[a-1],right=tape[a];attrs=(side==='left'?left:right)?.kind==='boundary'?{}:(side==='left'?left:right)?.attrs??(side==='left'?right:left)?.attrs??block.emptyBlockAttributes??{};}
    attrs=clone(attrs);const l=tape[a-1]?.attrs,r=tape[a]?.attrs;
    if(!intent.explicitLink&&!(l?.link?.id&&l.link.id===r?.link?.id))delete attrs.link;
    if(!intent.explicitCode&&!(l?.code?.id&&l.code.id===r?.code?.id))delete attrs.code;
    return attrs;
  }
  _format(q,ids){
    const p=q.payload,flowId=q.targetFlow??this.active?.flowId??this.activeFlow,doc=this._doc();if(!flows(doc).has(flowId))return {status:'Cancelled',reason:'TargetFlowDeleted'};
    const target=p.target==='composition'?ids:q.targetIds;
    if(!q.cellTargets&&(q.typingOnly||p.target==='composition'&&!target?.length)){if(flowId!==this.activeFlow)return {status:'Cancelled',reason:'TargetFlowChanged',historySlot:false};const f=getFlow(doc,flowId),base=p.clearAll?{}:this._seed(f,this.kernel.selection);this.typingIntent={mode:'explicit',base,patch:clone(p.patch),explicitLink:p.patch.link!=null,explicitCode:p.patch.code!=null};return {status:'Applied',historySlot:false};}
    const targets=q.cellTargets??[{flowId,ids:target??[]}];if(targets.some(t=>!flows(doc).has(t.flowId)))return {status:'Cancelled',reason:'TargetFlowDeleted'};let found=false;
    for(const t of targets){const f=getFlow(doc,t.flowId),wanted=new Set(t.ids);
      for(const leaf of leaves(f))if(leaf.units){if(q.cellTargets&&!leaf.units.length){leaf.emptyBlockAttributes=p.clearAll?{}:patchAttrs(leaf.emptyBlockAttributes,p.patch);found=true;}let start=0;while(start<leaf.units.length){if(leaf.units[start].kind==='inlineAtom'){if(wanted.has(leaf.units[start].tokenId)){leaf.units[start].attrs=p.clearAll?{}:patchAttrs(leaf.units[start].attrs,p.patch);found=true;}start++;continue;}let end=start;while(end<leaf.units.length&&leaf.units[end].kind!=='inlineAtom')end++;const run=leaf.units.slice(start,end),text=raw(run);for(const g of new Intl.Segmenter('en',{granularity:'grapheme'}).segment(text)){const units=run.slice(g.index,g.index+g.segment.length);if(units.some(u=>wanted.has(u.tokenId))){found=true;for(const u of units)u.attrs=p.clearAll?{}:patchAttrs(u.attrs,p.patch);}}start=end;}}
    }
    if(!found)return {status:'Cancelled',reason:'TargetDeleted'};const before=this._bundle();if(equal(before.document,doc))return {status:'NoChange'};this._install(doc,{kind:this.selectionKind});const entry=this._historyPush(before,this._bundle(),'Formatting');return {status:'Applied',entry};
  }
  _historyPush(before,after,kind){
    if(!before.document)return super._historyPush(before,after,kind);
    const p=this.executing?.payload??{},typing=kind==='Typing'||kind==='Composition'&&this.active?.key===null&&p.type==='commit';
    if(typing){kind='Typing';const time=p.timeMs??this.now(),seed=JSON.stringify(after.typingIntent),sameFlow=before.activeFlow===after.activeFlow;
      if(this.lastTyping&&this.history.at(-1)?.id===this.lastTyping.id&&sameFlow&&before.selection[0]===before.selection[1]&&before.selection[0]===this.lastTyping.caret&&this.lastTyping.flowId===after.activeFlow&&time>=this.lastTyping.time&&time-this.lastTyping.time<=750&&this.lastTyping.seed===seed&&equal(this.history.at(-1).after.document,before.document)){
        this.history.at(-1).after=clone(after);this.redo=[];this.lastTyping={...this.lastTyping,time,caret:after.selection[1]};return this.lastTyping.id;
      }
      const id=super._historyPush(before,after,kind);this.lastTyping={id,time,caret:after.selection[1],flowId:after.activeFlow,seed};return id;
    }
    this.lastTyping=null;return super._historyPush(before,after,kind);
  }
  _captureCommand(q){
    const p=q.payload,b=this._basis(q),doc=this._doc(),flowId=p.flowId??b.flowId??this.activeFlow;
    const command={ticket:q.ticket,payload:p,targetFlow:flowId,intent:clone(q.context?.typingIntent??{mode:'inherit',side:'left'})};
    if(p.association!=null&&!['before','after'].includes(p.association)||p.affinity!=null&&!['before','after'].includes(p.affinity))err('InvalidAssociation');if(p.point&&!['text','gap'].includes(p.point.kind))err('InvalidPoint');
    if(p.point)command.point=this._resolvePoint(q,{...p.point,association:p.association??p.point.association??'after'});
    if(b.flowId===flowId){
      const range=p.range??b.selection;if(!validRange(range,b.units.length))err('InvalidTargetRange');command.captured=captureRange(range,b.units,p.affinity??'after');if(p.command==='moveCaret')command.directional=range.map(pos=>captureRange([pos,pos],b.units,p.affinity??'after'));
    }
    if(p.command==='capturePaste')command.lease={flowId,captured:clone(command.captured),selectionGeneration:b.selectionGeneration,intent:clone(command.intent),cellSelection:b.selectionKind?.type==='cells'?clone(b.selectionKind):null,cellTargets:b.cellTargets?.map(t=>({cellId:t.cellId,flowId:t.flowId,contentHash:t.contentHash}))};
    if(p.command==='typeText'||p.command==='replaceSelection'||p.command.startsWith('paste')||['deleteSelection','copy','cut'].includes(p.command))command.cellSelection=b.selectionKind?.type==='cells'?clone(b.selectionKind):null;
    command.keepFocus=b.selectionGeneration!==this.selectionGeneration&&!sessionOnly.has(p.command);
    return command;
  }
  _commandRange(q,flow){if(q.payload.anchorHandle){const a=this.anchors.get(q.payload.anchorHandle);if(!a||a.deleted||a.point.flowId!==flow.id)err('TargetDeleted');const pos=pointOffset(flow,a.point);return [pos,pos];}return q.captured?resolveRange(q.captured,project(flow)):flow.id===this.activeFlow?this.kernel.selection:[0,0];}
  _checkPendingCommand(q){super._checkPendingCommand(q);const size=Buffer.byteLength(JSON.stringify([...this.pending,q]),'utf8');if(size>this.retention.pendingBytes)err('AdmissionCapacityExceeded');}
  _executePending(q,ids){
    if(q.payload.type==='format')return this._format(q,ids);
    // Per-command known rejection is atomic without rolling back earlier commands.
    const before=this._save();try{return this._executeCommand(q);}catch(e){if(!admittedError(e))throw e;this._restore(before);return {status:'Cancelled',reason:e.message,historySlot:!sessionOnly.has(q.payload.command)};}
  }
  _reduce(q){
    if(!this.docBase)return super._reduce(q);
    const p=q.payload;
    if(p.type==='command'){
      if(!COMMANDS.includes(p.command))err('UnknownCommand');const command=this._captureCommand(q);if(p.command!=='typeText')this.lastTyping=null;
      if(this.active&&!immediateWhileComposing.has(p.command)&&p.command!=='focusLost'){
        this._checkPendingCommand(command);this.pending.push(command);this.outcomes.set(q.ticket,{status:'Pending'});return {commandStatus:'Queued'};
      }
      const outcome=this._executeCommand(command);this._terminalOutcome(q.ticket,outcome);const {status,...detail}=outcome;return {commandStatus:status,...detail};
    }
    if(p.type==='format'){
      if(!this._validatePatch(p.patch))err('InvalidPatch');this.lastTyping=null;
      const b=this._basis(q),range=p.range??b.selection,command={ticket:q.ticket,payload:p,targetFlow:p.flowId??b.flowId,typingOnly:p.target!=='composition'&&range[0]===range[1]};
      if(p.target!=='composition'&&p.range==null&&b.cellTargets)command.cellTargets=b.cellTargets.map(t=>({flowId:t.flowId,ids:t.units.filter(u=>!u.tokenId.startsWith('boundary:')).map(u=>u.tokenId)}));
      if(p.target!=='composition'){if(!validRange(range,b.units.length))err('InvalidTargetRange');command.targetIds=b.units.slice(Math.min(...range),Math.max(...range)).filter(u=>u.kind!=='boundary').map(u=>u.tokenId);}else if(!this.active)err('NoActiveSession');
      if(this.active){this._checkPendingCommand(command);this.pending.push(command);this.outcomes.set(q.ticket,{status:'Pending'});return {commandStatus:'Queued'};}
      const out=this._format(command,[]);this._terminalOutcome(q.ticket,out);const {status,...detail}=out;return {commandStatus:status,...detail};
    }
    if(p.type==='selection'){this.explicitSelectionBarrier=true;this.lastTyping=null;this.typingIntent={mode:'inherit',side:p.side??'left'};this.selectionKind={type:'text'};}
    if(nativeTypes.has(p.type)&&p.stamp.token!=null)this.lastTyping=null;
    const result=super._reduce(q);if(p.type==='dispose'){this.anchors.clear();this.pastes.clear();this.clipboard=null;}validateDocument(this._doc());return result;
  }

  _executeCommand(q){
    const p=q.payload,op=p.command,allocate=this._allocate(q),before=this._bundle(),doc=this._doc();let flow=getFlow(doc,q.targetFlow),range=this._commandRange(q,flow),nextFlow=this.activeFlow,selection=null,kind=clone(this.selectionKind),intent=clone((q.keepFocus||q.targetFlow!==this.activeFlow)&&q.intent?q.intent:this.typingIntent),result={status:'Applied'},historyKind=op,forceNoHistory=false;
    if(op==='pasteRich'&&['units','blocks','cells'].filter(k=>p[k]!=null).length!==1)err('InvalidRichFragment');
    if(['typeText','replaceSelection','deleteSelection','backspace','deleteForward','enter','softBreak','insertInlineAtom','insertBlockObject','insertTable','pasteText','pasteRich','copy','cut'].includes(op))range=T.userRange(flow,range,p.affinity??'after');
    const select=(id,pos,selKind={type:'text'})=>{nextFlow=id;selection=[pos,pos];kind=selKind;};
    const seed=()=>this._seed(flow,range,intent);
    const replace=(text,units=null)=>{const a=range[0],attrs=seed();let inserted=units??textUnits(text,allocate,attrs);if(intent.explicitLink){let exited=false;for(const u of inserted){if(/\s/u.test(u.char))exited=true;if(exited)delete u.attrs.link;}if(exited){intent.explicitLink=false;if(intent.patch)delete intent.patch.link;if(intent.base)delete intent.base.link;}}
      const next=spliceFlow(flow,a,range[1],inserted,attrs);putFlow(doc,flow.id,next);flow=next;range=[a+inserted.length,a+inserted.length];if(q.targetFlow===this.activeFlow)select(flow.id,range[0]);return inserted;};
    const at=()=>{const point=pointAt(flow,range[0]);return {point,node:index(flow).get(point.blockId).node};};
    const clearSelectedCells=()=>{const cells=this._clearCellFlows(doc,q.cellSelection??this.selectionKind,allocate);flow=cells[0].flow;range=[0,0];select(flow.id,0);return cells;};
    const mutateNode=()=>{const entry=locate(doc,p.nodeId)??T.locateAtom(doc,p.nodeId);if(!entry)err('NodeDeleted');return entry;};
    const gap=p.anchorHandle?this.anchors.get(p.anchorHandle)?.point:q.point;
    let enteredGap=false;
    if(gap?.kind==='gap'&&['typeText','replaceSelection','enter','softBreak','pasteText','pasteRich','insertInlineAtom'].includes(op)){
      const parent=index(flow).get(gap.parentId)?.node;if(!parent?.children)err('InvalidGap');const node=paragraph(allocate);parent.children.splice(gap.index,0,parent.type==='list'?{id:allocate('item'),type:'item',props:{},children:[node]}:node);range=[pointOffset(flow,{kind:'text',flowId:flow.id,blockId:node.id,offset:0}),pointOffset(flow,{kind:'text',flowId:flow.id,blockId:node.id,offset:0})];select(flow.id,range[0]);enteredGap=true;
    }

    if(op==='registerAnchor'){
      if(this.anchors.size>=this.commandLimits.anchors)err('AdmissionCapacityExceeded');const point=q.point??pointAt(flow,range[0]),id=`anchor:${q.ticket}`;validatePoint(doc,point);this.anchors.set(id,{id,point:clone(point),association:p.association??'after',generation:0,deleted:false});return {status:'Applied',anchorId:id,historySlot:false};
    }
    if(op==='moveAnchor'){const a=this.anchors.get(p.anchorId);if(!a)err('InvalidAnchor');const point=q.point??pointAt(flow,range[0]);validatePoint(doc,point);this.anchors.set(a.id,{...a,point:clone(point),association:p.association??a.association,generation:a.generation+1,deleted:false});return {status:'Applied',historySlot:false};}
    if(op==='unregisterAnchor'){if(!this.anchors.delete(p.anchorId))err('InvalidAnchor');return {status:'Applied',historySlot:false};}
    if(op==='capturePaste'){
      if(this.pastes.size>=this.commandLimits.pastes)err('AdmissionCapacityExceeded');const id=`paste:${q.ticket}`;if(Buffer.byteLength(JSON.stringify([...this.pastes.values(),q.lease]),'utf8')>this.retention.pendingBytes)err('AdmissionCapacityExceeded');this.pastes.set(id,clone(q.lease));return {status:'Applied',pasteId:id,historySlot:false};
    }
    if(op==='cancelPaste'){if(!this.pastes.delete(p.pasteId))err('InvalidPasteLease');return {status:'Applied',historySlot:false};}
    if(op==='applyPaste'){
      const lease=this.pastes.get(p.pasteId);if(!lease)err('InvalidPasteLease');this.pastes.delete(p.pasteId);if(!flows(doc).has(lease.flowId))return {status:'Cancelled',reason:'TargetDeleted',historySlot:false};
      try{if(lease.cellSelection){const t=locate(doc,lease.cellSelection.tableId);if(!isTable(t?.node))err('TargetDeleted');const cells=this._cells(t.node,lease.cellSelection);if(!same(cells.map(c=>c.id),lease.cellTargets.map(c=>c.cellId)))err('TargetFragmented');if(cells.some((c,i)=>createHash('sha256').update(semantic(c.flow)).digest('hex')!==lease.cellTargets[i].contentHash))err('TargetChanged');}else resolveRange(lease.captured,project(getFlow(doc,lease.flowId)));return this._executeCommand({...q,payload:{...p,command:p.blocks||p.cells||p.units?'pasteRich':p.tsv?'pasteTSV':'pasteText',flowId:lease.flowId},targetFlow:lease.flowId,captured:clone(lease.captured),cellSelection:lease.cellSelection,intent:lease.intent,keepFocus:this.selectionGeneration!==lease.selectionGeneration});}catch(e){if(/^Target/.test(e.message))return {status:'Cancelled',reason:'TargetDeleted',historySlot:false};throw e;}
    }
    if(op==='cancelCommand'){const k=this.pending.findIndex(x=>x.ticket===p.ticket);if(k<0||this.pending[k].cancelReason)err('InvalidCommandTicket');const old=this.pending[k];this.pending[k]={ticket:old.ticket,payload:{type:old.payload.type,command:old.payload.command},cancelReason:'UserCancelled',outcomeEmitted:true,historySlot:!old.typingOnly&&!sessionOnly.has(old.payload.command)};this._terminalOutcome(old.ticket,{status:'Cancelled',reason:'UserCancelled'});return {status:'Applied',historySlot:false};}
    if(op==='focusLost'){if(!p.qualified||p.epoch!==this.epoch||p.sessionId!==(this.active?.engineId??null))err('InvalidFocusEvidence');if(this.active){for(const command of this.pending)if(command.payload.type==='format'&&command.payload.target==='composition'&&!command.cancelReason)command.cancelReason='FocusLost';if(p.policy==='cancel')this._finish('cancel',{type:'abort'});else if(p.policy==='preserve')this._finish('commit',{type:'focusLost'});else err('InvalidFocusPolicy');}this.generation++;this.hostFence=false;return {status:'Applied',historySlot:false};}

    if(op==='copy'||op==='cut'){
      if(!q.cellSelection&&range[0]===range[1])return {status:'NoChange',historySlot:false};
      let fragment;
      if(q.cellSelection){const tableNode=locate(doc,q.cellSelection.tableId).node,selected=this._cells(tableNode,q.cellSelection),wanted=new Set(selected.map(c=>c.id)),cells=tableNode.props.rows.map(r=>r.cells.filter(c=>wanted.has(c.id)).map(x=>clone(x))).filter(r=>r.length);fragment={type:'cells',cells};if(op==='cut')clearSelectedCells();}
      else {const units=clone(project(flow).slice(range[0],range[1]));fragment={type:units.some(u=>u.kind==='boundary'||u.kind==='blockAtom')?'rich':'inline',blocks:T.closedSlice(flow,range[0],range[1]),units,text:raw(units)};}
      const refs=new Set();const find=v=>{if(v&&typeof v==='object'){if(v.resourceId)refs.add(v.resourceId);Object.values(v).forEach(find);}};find(fragment);fragment.resources=Object.fromEntries([...refs].map(id=>[id,clone(doc.props.resources[id])]));fragment.id=`clipboard:${q.ticket}`;this.clipboard=immutableCopy(fragment);
      if(Buffer.byteLength(JSON.stringify(this.clipboard),'utf8')>this.retention.pendingBytes)err('AdmissionCapacityExceeded');result.clipboardId=`clipboard:${q.ticket}`;
      if(op==='copy')return {...result,historySlot:false};if(!q.cellSelection)replace('');
    }
    else if(op==='selectFlow'){flow=getFlow(doc,p.toFlow);select(flow.id,p.offset??0);intent={mode:'inherit',side:'left'};forceNoHistory=true;}
    else if(op==='selectCells'){const t=locate(doc,p.tableId);if(!isTable(t?.node))err('InvalidTable');kind={type:'cells',tableId:p.tableId,anchorCell:p.anchorCell,focusCell:p.focusCell};intent={mode:'inherit',side:'right'};const cells=this._cells(t.node,kind);nextFlow=cells[0].flow.id;selection=[0,project(cells[0].flow).length];forceNoHistory=true;}
    else if(op==='selectNode'){const e=mutateNode(),start=e.inline?pointOffset(e.flow,{kind:'text',blockId:e.block.id,offset:e.offset}):null,r=e.inline?{start,end:start+1}:leafRanges(e.flow).find(x=>x.id===p.nodeId);if(!r)err('InvalidNodeSelection');nextFlow=e.flowId;selection=[r.start,r.end];kind={type:'node',nodeId:p.nodeId};forceNoHistory=true;}
    else if(op==='selectBlocks'){const a=locate(doc,p.first),b=locate(doc,p.last);if(!a||!b||a.flowId!==b.flowId)err('InvalidBlockSelection');const rs=leafRanges(a.flow),start=rs.find(x=>x.id===leaves(a.node)[0].id).start,end=rs.find(x=>x.id===leaves(b.node).at(-1).id).end;nextFlow=a.flowId;selection=p.backwards?[end,start]:[start,end];kind={type:'blocks',first:p.first,last:p.last};forceNoHistory=true;}
    else if(op==='clearCells'){clearSelectedCells();}
    else if(op==='typeText'||op==='replaceSelection'||op==='deleteSelection'){
      if(typeof p.text!=='string'&&op!=='deleteSelection')err('InvalidText');if(q.cellSelection)clearSelectedCells();replace(op==='deleteSelection'?'':p.text);historyKind=op==='typeText'?'Typing':op;
    }
    else if(op==='softBreak'){if(range[0]!==range[1])replace('');replace('\n');}
    else if(op==='backspace'&&gap?.kind==='gap'&&gap.index===0&&index(flow).get(gap.parentId)?.node.type==='item'){
      const first=leaves(index(flow).get(gap.parentId).node)[0].id;liftItem(flow,gap.parentId,allocate);putFlow(doc,flow.id,flow);select(flow.id,leafRanges(flow).find(r=>r.id===first).start);
    }
    else if(op==='backspace'||op==='deleteForward'){
      if(range[0]!==range[1])replace('');else{const {point,node}=at(),pos=point.offset;
        if(op==='backspace'&&pos===0){const item=nearestItem(flow,node.id),ix=index(flow),parent=ix.get(ix.get(node.id).parent).node;
          if(item&&parent.id===item&&parent.children[0].id===node.id){liftItem(flow,item,allocate);putFlow(doc,flow.id,flow);select(flow.id,pointOffset(flow,{...point,offset:0}));}
          else if(parent.type==='quote'&&leaves(parent)[0].id===node.id){const outer=ix.get(ix.get(parent.id).parent).node,k=outer.children.findIndex(n=>n.id===parent.id);outer.children.splice(k,1,...parent.children);select(flow.id,pointOffset(flow,point));}
          else{const rs=leafRanges(flow),i=rs.findIndex(x=>x.id===node.id);if(i===0)return {status:'NoChange'};if(!isText(rs[i-1].node))err('SelectionRequired');if((node.type==='code')!==(rs[i-1].node.type==='code')||ix.get(rs[i-1].id).parent!==ix.get(node.id).parent)return {status:'NoChange',reason:'StructuralBoundary'};range=[range[0]-1,range[0]];replace('');}}
        else if(op==='deleteForward'&&pos===node.units.length){const rs=leafRanges(flow),i=rs.findIndex(x=>x.id===node.id),ix=index(flow);if(i===rs.length-1)return {status:'NoChange'};if(!isText(rs[i+1].node))err('SelectionRequired');const leftParent=ix.get(ix.get(node.id).parent),rightParent=ix.get(ix.get(rs[i+1].id).parent),siblings=leftParent.node.type==='item'&&rightParent.node.type==='item'&&leftParent.parent===rightParent.parent;if((node.type==='code')!==(rs[i+1].node.type==='code')||leftParent.node.id!==rightParent.node.id&&!siblings)return {status:'NoChange',reason:'StructuralBoundary'};range=[range[0],range[0]+1];replace('');}
        else{const stops=T.unitStops(node.units);const start=leafRanges(flow).find(x=>x.id===node.id).start;range=op==='backspace'?[start+stops.filter(x=>x<pos).at(-1),range[0]]:[range[0],start+stops.find(x=>x>pos)];replace('');}}
    }
    else if(op==='deleteCodePoints'){
      if(!Number.isSafeInteger(p.before)||!Number.isSafeInteger(p.after)||p.before<0||p.after<0)err('InvalidCodePointCount');const text=raw(project(flow));let a=range[0],b=range[1];
      for(let i=0;i<p.before&&a>0;i++){const u=text.charCodeAt(--a);if(u>=0xdc00&&u<=0xdfff){if(a===0||text.charCodeAt(a-1)<0xd800||text.charCodeAt(a-1)>0xdbff)err('InvalidSurrogateTraversal');a--;}else if(u>=0xd800&&u<=0xdbff)err('InvalidSurrogateTraversal');}
      for(let i=0;i<p.after&&b<text.length;i++){const u=text.charCodeAt(b++);if(u>=0xd800&&u<=0xdbff){if(b===text.length||text.charCodeAt(b)<0xdc00||text.charCodeAt(b)>0xdfff)err('InvalidSurrogateTraversal');b++;}else if(u>=0xdc00&&u<=0xdfff)err('InvalidSurrogateTraversal');}
      const original=[...range],afterUnits=project(flow);flow=spliceFlow(flow,original[1],b,[],seed());putFlow(doc,flow.id,flow);flow=spliceFlow(flow,a,original[0],[],seed());putFlow(doc,flow.id,flow);range=[a,a+(original[1]-original[0])];if(flow.id===this.activeFlow){nextFlow=flow.id;selection=range;kind={type:'text'};}
    }
    else if(op==='enter'){
      if(enteredGap){putFlow(doc,flow.id,flow);}else{
      const enterAttrs=seed();delete enterAttrs.link;delete enterAttrs.code;if(range[0]!==range[1])replace('');const {point,node}=at(),itemId=nearestItem(flow,node.id),ix=index(flow),parent=ix.get(ix.get(node.id).parent).node;
      if(node.type==='code')replace('\n');
      else if(itemId){const item=index(flow).get(itemId).node;if(item.children.length===1&&item.children[0].id===node.id&&node.units.length===0){liftItem(flow,itemId,allocate);select(flow.id,pointOffset(flow,point));}
        else{const out=splitItem(flow,node.id,point.offset,allocate);const right=index(flow).get(out.rightId).node;right.emptyBlockAttributes=seed();delete right.emptyBlockAttributes.link;delete right.emptyBlockAttributes.code;select(flow.id,pointOffset(flow,{kind:'text',flowId:flow.id,blockId:out.rightId,offset:0}));}}
      else if(parent.type==='quote'&&node.units.length===0){const outer=ix.get(ix.get(parent.id).parent).node,k=outer.children.findIndex(x=>x.id===parent.id),n=parent.children.findIndex(x=>x.id===node.id);const tail=parent.children.splice(n+1);parent.children.splice(n,1);const insert=[node];if(tail.length)insert.push({...clone(parent),id:allocate('quote'),children:tail});outer.children.splice(k+1,0,...insert);normalize(flow,allocate);select(flow.id,pointOffset(flow,point));}
      else{const rightId=splitBlock(flow,node.id,point.offset,allocate,node.type==='heading'&&point.offset===node.units.length?{rightType:'paragraph',rightProps:{}}:{}),right=index(flow).get(rightId).node;right.emptyBlockAttributes=seed();delete right.emptyBlockAttributes.link;delete right.emptyBlockAttributes.code;select(flow.id,pointOffset(flow,{kind:'text',flowId:flow.id,blockId:rightId,offset:0}));}
      putFlow(doc,flow.id,flow);if(node.type!=='code')intent={mode:'explicit',base:enterAttrs,patch:{},explicitLink:false,explicitCode:false};
      }
    }
    else if(op==='moveCaret'){
      if(p.unit!=null&&!['word','grapheme'].includes(p.unit))err('InvalidNavigationUnit');
      const directional=q.directional?.map(c=>resolveRange(c,project(flow))[0])??range,focus=directional[1],point=pointAt(flow,focus),r=leafRanges(flow).find(x=>x.id===point.blockId);let pos=focus;
      if(p.direction==='blockStart')pos=r.start;else if(p.direction==='blockEnd')pos=r.end;else if(['left','right'].includes(p.direction)){
        const stops=leafRanges(flow).filter(r=>isText(r.node)).flatMap(r=>T.unitStops(r.node.units,p.unit??'grapheme').map(i=>r.start+i));
        if(!p.extend&&directional[0]!==directional[1])pos=p.direction==='left'?Math.min(...directional):Math.max(...directional);
        else pos=p.direction==='left'?(stops.filter(x=>x<focus).at(-1)??focus):(stops.find(x=>x>focus)??focus);
      }else err('UnsupportedVisualNavigationWithoutLayout');nextFlow=flow.id;selection=p.extend?[directional[0],pos]:[pos,pos];kind={type:'text'};forceNoHistory=true;intent={mode:'inherit',side:p.direction==='right'?'right':'left'};
    }
    else if(op==='setBlockType'){
      if(!['paragraph','heading','code'].includes(p.blockType))err('InvalidBlockType');const targets=p.blockIds??leafRanges(flow).filter(x=>x.end>=range[0]&&x.start<=range[1]&&isText(x.node)).map(x=>x.id);
      for(const id of targets){const e=locate(doc,id);if(!isText(e?.node))err('InvalidBlockType');if(p.blockType==='code'&&e.node.units.some(u=>u.kind==='inlineAtom'))err('InvalidCodeAtom');e.node.type=p.blockType;delete e.node.props.level;delete e.node.props.language;if(p.blockType==='heading'){if(!Number.isInteger(p.level)||p.level<1||p.level>6)err('InvalidHeadingLevel');e.node.props.level=p.level;}if(p.blockType==='code'&&p.language)e.node.props.language=p.language;}
    }
    else if(op==='patchNode'){const e=mutateNode();if(!p.patch||typeof p.patch!=='object')err('InvalidPatch');if(Object.hasOwn(p.patch,'rows')||Object.hasOwn(p.patch,'kind'))err('InvalidStructuralPropertyPatch');e.node.props={...e.node.props,...clone(p.patch)};}
    else if(op==='patchCell'){const t=locate(doc,p.tableId);if(!isTable(t?.node)||!p.patch||typeof p.patch!=='object')err('InvalidCellPatch');const cell=t.node.props.rows.flatMap(r=>r.cells).find(c=>c.id===p.cellId);if(!cell)err('TargetCellDeleted');cell.props={...cell.props,...clone(p.patch)};}
    else if(op==='wrapQuote'||op==='wrapList'){
      const ids=p.blockIds??[at().node.id],entries=ids.map(id=>locate(doc,id));if(entries.some(e=>!e)||entries.some(e=>e.flowId!==entries[0].flowId||e.parent!==entries[0].parent))err('InvalidSiblingSelection');const parent=index(entries[0].flow).get(entries[0].parent).node;if(parent.type==='list')err('InvalidParent');const positions=ids.map(id=>parent.children.findIndex(n=>n.id===id)).sort((a,b)=>a-b);if(positions.some((x,i)=>i&&x!==positions[i-1]+1))err('InvalidSiblingSelection');const chosen=parent.children.splice(positions[0],ids.length),wrapped=op==='wrapQuote'?{id:allocate('quote'),type:'quote',props:{},children:chosen}:{id:allocate('list'),type:'list',props:{kind:p.kind??'bullet',...(p.kind==='ordered'?{start:p.start??1}:{})},children:chosen.map(n=>({id:allocate('item'),type:'item',props:{},children:[n]}))};parent.children.splice(positions[0],0,wrapped);result.nodeId=wrapped.id;
    }
    else if(op==='unwrapQuote'||op==='unwrapList'){
      const e=mutateNode();if(e.node.type!==(op==='unwrapQuote'?'quote':'list'))err('InvalidContainer');const parent=index(e.flow).get(e.parent).node,k=parent.children.findIndex(n=>n.id===e.node.id);parent.children.splice(k,1,...(op==='unwrapQuote'?e.node.children:e.node.children.flatMap(i=>i.children)));
    }
    else if(op==='indent'||op==='outdent'){const itemId=p.nodeId??nearestItem(flow,at().node.id);if(!itemId)err('InvalidListItem');if(op==='indent')indentItem(flow,itemId,allocate);else liftItem(flow,itemId,allocate);putFlow(doc,flow.id,flow);}
    else if(op==='moveNode'){
      const e=mutateNode(),dest=locate(doc,p.parentId);if(e.inline){if(!isText(dest?.node)||dest.node.type==='code'||!Number.isInteger(p.index)||p.index<0||p.index>dest.node.units.length)err('InvalidInlineMove');const moved=e.block.units.splice(e.offset,1)[0];dest.node.units.splice(p.index-(e.block===dest.node&&e.offset<p.index?1:0),0,moved);}else{if(!dest?.node.children||!e.parent||e.node.id===p.parentId||index(e.node).has(p.parentId)||(dest.node.type==='list')!==(e.node.type==='item'))err('InvalidMove');if(flows(doc).get(dest.flowId).owner&&leaves(e.node).some(isTable))err('NestedTablesUnsupported');if(!Number.isInteger(p.index)||p.index<0||p.index>dest.node.children.length)err('InvalidMove');const from=index(e.flow).get(e.parent).node,old=from.children.findIndex(n=>n.id===e.node.id);from.children.splice(old,1);dest.node.children.splice(p.index-(from===dest.node&&old<p.index?1:0),0,e.node);putFlow(doc,e.flowId,normalize(e.flow,allocate));}
    }
    else if(op==='deleteNode'||op==='deleteTable'){
      const e=mutateNode();if(op==='deleteTable'&&!isTable(e.node))err('InvalidTable');if(e.inline)e.block.units.splice(e.offset,1);else{const parent=index(e.flow).get(e.parent)?.node;if(!parent)err('InvalidDeleteRoot');parent.children.splice(parent.children.findIndex(n=>n.id===e.node.id),1);putFlow(doc,e.flowId,normalize(e.flow,allocate));}
    }
    else if(op==='insertInlineAtom'){const unit={kind:'inlineAtom',tokenId:allocate('unit'),nodeId:allocate('atom'),char:'\ufffc',attrs:seed(),props:clone(p.props??{}),originSessionId:null};replace('',[unit]);result.nodeId=unit.nodeId;}
    else if(op==='insertBlockObject'||op==='insertTable'){
      if(op==='insertBlockObject'&&(p.kind==='table'||p.props?.kind==='table'))err('InvalidObjectKind');
      if(op==='insertTable'&&flows(doc).get(flow.id).owner)err('NestedTablesUnsupported');if(range[0]!==range[1])replace('');const {point,node}=at(),rightId=splitBlock(flow,node.id,point.offset,allocate,{rightType:'paragraph',rightProps:{}}),ix=index(flow),parent=ix.get(ix.get(node.id).parent).node,obj=op==='insertTable'?table(allocate,p.rows,p.columns):{id:allocate('object'),type:'object',props:{kind:p.kind??'image',...clone(p.props??{})}};parent.children.splice(parent.children.findIndex(n=>n.id===rightId),0,obj);putFlow(doc,flow.id,flow);result.nodeId=obj.id;
      if(isTable(obj)){const cell=obj.props.rows[0].cells[0];select(cell.flow.id,0);result.firstCellFlow=cell.flow.id;}else select(flow.id,pointOffset(flow,{kind:'text',flowId:flow.id,blockId:rightId,offset:0}));
    }
    else if(['insertRow','deleteRow','insertColumn','deleteColumn'].includes(op)){
      const e=mutateNode();if(!isTable(e.node))err('InvalidTable');const rows=e.node.props.rows,cols=rows[0].cells.length,limit=op.endsWith('Row')?rows.length:cols;if(!Number.isInteger(p.index)||p.index<0||p.index>(op.startsWith('insert')?limit:limit-1))err('InvalidTableIndex');
      if(op==='insertRow'){if((rows.length+1)*cols>this.commandLimits.cells)err('TableAdmissionLimit');rows.splice(p.index,0,{id:allocate('row'),cells:Array.from({length:cols},()=>({id:allocate('cell'),flow:cellFlow(allocate)}))});}
      if(op==='deleteRow'){if(rows.length===1)err('LastTableRow');rows.splice(p.index,1);}
      if(op==='insertColumn'){if(rows.length*(cols+1)>this.commandLimits.cells)err('TableAdmissionLimit');for(const row of rows)row.cells.splice(p.index,0,{id:allocate('cell'),flow:cellFlow(allocate)});}
      if(op==='deleteColumn'){if(cols===1)err('LastTableColumn');for(const row of rows)row.cells.splice(p.index,1);}
    }
    else if(op==='tableTab'){
      const owner=flows(doc).get(this.activeFlow).owner;if(!owner)err('InvalidCellContext');const t=locate(doc,owner.table),rows=t.node.props.rows,cols=rows[0].cells.length,n=owner.row*cols+owner.col+(p.reverse?-1:1);
      if(n<0){const e=index(t.flow).get(t.node.id),parent=index(t.flow).get(e.parent).node,k=parent.children.findIndex(n=>n.id===t.node.id);let dest=parent.children[k-1];if(!isText(dest)){dest=paragraph(allocate);parent.children.splice(k,0,dest);}select(t.flowId,pointOffset(t.flow,{kind:'text',flowId:t.flowId,blockId:dest.id,offset:dest.units.length}));}
      else{if(n===rows.length*cols){if((rows.length+1)*cols>this.commandLimits.cells)err('TableAdmissionLimit');rows.push({id:allocate('row'),cells:Array.from({length:cols},()=>({id:allocate('cell'),flow:cellFlow(allocate)}))});}select(rows[Math.floor(n/cols)].cells[n%cols].flow.id,0);}
    }
    else if(op==='pasteText'){
      if(q.cellSelection)clearSelectedCells();if(at().node.type==='code')replace(p.text);else{
        if(typeof p.text!=='string')err('InvalidText');const lines=p.text.replace(/\r\n?/g,'\n').split('\n');replace(lines[0]);for(let i=1;i<lines.length;i++){intent={...intent,explicitLink:false,explicitCode:false};if(intent.base){delete intent.base.link;delete intent.base.code;}if(intent.patch){delete intent.patch.link;delete intent.patch.code;}const point=pointAt(flow,range[0]),right=splitBlock(flow,point.blockId,point.offset,allocate,{rightType:'paragraph',rightProps:{}});range=[pointOffset(flow,{kind:'text',flowId:flow.id,blockId:right,offset:0}),pointOffset(flow,{kind:'text',flowId:flow.id,blockId:right,offset:0})];replace(lines[i]);}
      }
    }
    else if(op==='pasteRich'&&p.units){
      if(q.cellSelection)clearSelectedCells();
      if(!Array.isArray(p.units)||p.units.some(u=>!['text','inlineAtom',undefined].includes(u.kind)||typeof u.char!=='string'||u.char.length!==1))err('InvalidRichFragment');const temp=cloneFresh({id:'fragment',type:'paragraph',props:{},units:p.units},allocate);this._importResources(temp,doc,allocate,p.resources);result.importReport=this._sanitize(temp);replace('',temp.units);
    }
    else if(op==='pasteRich'&&!p.cells){
      if(!Array.isArray(p.blocks)||!p.blocks.length)err('InvalidRichFragment');if(q.cellSelection)clearSelectedCells();if(range[0]!==range[1])replace('');const point=pointAt(flow,range[0]),right=splitBlock(flow,point.blockId,point.offset,allocate,{rightType:'paragraph',rightProps:{}}),ix=index(flow),parent=ix.get(ix.get(right).parent).node,links=new Map(),codes=new Map(),blocks=p.blocks.map(n=>cloneFresh(n,allocate,links,codes));this._importResources(blocks,doc,allocate,p.resources);result.importReport=this._sanitize(blocks);parent.children.splice(parent.children.findIndex(n=>n.id===right),0,...blocks);const left=ix.get(point.blockId).node,tail=ix.get(right).node;if(!left.units.length)parent.children.splice(parent.children.findIndex(n=>n.id===left.id),1);let landing=tail,offset=0;if(!tail.units.length){const last=leaves(blocks.at(-1)).at(-1);if(isText(last)){parent.children.splice(parent.children.findIndex(n=>n.id===tail.id),1);landing=last;offset=last.units.length;}}putFlow(doc,flow.id,flow);select(flow.id,pointOffset(flow,{kind:'text',flowId:flow.id,blockId:landing.id,offset}));
    }
    else if(op==='pasteTSV'||op==='pasteRich'&&p.cells){
      const owner=flows(doc).get(this.activeFlow).owner,selection=q.cellSelection??(this.selectionKind.type==='cells'?this.selectionKind:null);const t=locate(doc,selection?.tableId??owner?.table);if(!isTable(t?.node))err('InvalidCellContext');let row=owner?.row??0,col=owner?.col??0;if(selection){const cells=this._cells(t.node,selection);const f=flows(doc).get(cells[0].flow.id).owner;row=f.row;col=f.col;this._clearCellFlows(doc,selection,allocate);}
      const values=p.cells?this._importResources(clone(p.cells),doc,allocate,p.resources):parseTSV(p.text);if(p.cells)result.importReport=this._sanitize(values);if(!Array.isArray(values)||!values.length||values.some(r=>!Array.isArray(r)||!r.length))err('InvalidCellFragment');const width=Math.max(...values.map(r=>r.length)),rows=t.node.props.rows,targetCols=Math.max(rows[0].cells.length,col+width),targetRows=Math.max(rows.length,row+values.length);if(targetCols*targetRows>this.commandLimits.cells)err('TableAdmissionLimit');
      for(const r of rows)while(r.cells.length<targetCols)r.cells.push({id:allocate('cell'),flow:cellFlow(allocate)});while(rows.length<targetRows)rows.push({id:allocate('row'),cells:Array.from({length:targetCols},()=>({id:allocate('cell'),flow:cellFlow(allocate)}))});
      const links=new Map(),codes=new Map();for(let i=0;i<values.length;i++)for(let j=0;j<values[i].length;j++){const cell=rows[row+i].cells[col+j],f=cell.flow,head=leaves(f).find(isText),attrs=head.units.find(u=>u.kind==='text'||u.kind==null)?.attrs??head.emptyBlockAttributes??{};if(p.cells){const source=values[i][j];if(!source.flow?.children)err('InvalidCellFragment');f.children=source.flow.children.map(n=>cloneFresh(n,allocate,links,codes));cell.props=clone(source.props??{});}else f.children=[{id:head.id,type:'paragraph',props:{},units:textUnits(values[i][j],allocate,attrs),emptyBlockAttributes:clone(attrs)}];}select(rows[row].cells[col].flow.id,0);
    }
    else if(op==='putResource'){if(!p.resourceId||!p.resource||typeof p.resource!=='object'||/^(javascript:|vbscript:|data:text\/html)/i.test(p.resource.uri??''))err('InvalidResource');doc.props.resources[p.resourceId]=clone(p.resource);}
    else if(op==='removeResource'){if(!Object.hasOwn(doc.props.resources,p.resourceId))err('InvalidResource');const used=v=>v&&typeof v==='object'&&(v.resourceId===p.resourceId||Object.values(v).some(used));if(used(doc.children))err('InvalidResourceInUse');delete doc.props.resources[p.resourceId];}
    else if(op==='addAnnotation'){const a=pointAt(flow,range[0]),b=pointAt(flow,range[1]),id=allocate('annotation');doc.props.annotations.push({id,anchor:a,focus:b,props:clone(p.props??{}),orphaned:false});result.annotationId=id;}
    else if(op==='removeAnnotation'){const i=doc.props.annotations.findIndex(a=>a.id===p.annotationId);if(i<0)err('InvalidAnnotation');doc.props.annotations.splice(i,1);}
    else err('UnknownCommand');

    if(p.failAt==='afterCommandMutation')throw Error('InjectedCommandFailure');
    for(const {flow:f}of flows(doc).values())normalize(f,allocate);
    if(Object.keys(doc.props.resources).length>this.commandLimits.resources||doc.props.annotations.length>this.commandLimits.annotations||[...flows(doc).values()].reduce((n,{flow})=>n+index(flow).size,0)>this.commandLimits.nodes)err('AdmissionCapacityExceeded');
    if(op==='pasteRich')try{validateDocument(doc);}catch{err('InvalidRichFragment');}
    const changed=!equal(before.document,doc);
    if(!changed)doc.children=clone(before.document.children); // Semantic no-op preserves content identities.
    if(q.keepFocus){nextFlow=this.activeFlow;selection=null;kind=this.selectionKind;}if(q.keepFocus||q.targetFlow!==this.activeFlow&&nextFlow===this.activeFlow)intent=this.typingIntent;
    if(['backspace','deleteForward','deleteSelection','cut'].includes(op)&&!q.keepFocus&&q.targetFlow===this.activeFlow)intent={mode:'inherit',side:op==='deleteForward'?'right':'left'};
    this._install(doc,{flowId:nextFlow,selection,kind,intent});
    if(!changed&&op==='tableTab')forceNoHistory=true;
    if(forceNoHistory)this.explicitSelectionBarrier=true;
    if(changed){const entry=this._historyPush(before,this._bundle(),historyKind);return {...result,entry};}
    return {...result,status:forceNoHistory?'Applied':'NoChange',historySlot:!forceNoHistory};
  }
}
