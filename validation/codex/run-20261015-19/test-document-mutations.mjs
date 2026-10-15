import assert from 'node:assert/strict';
import {DocumentModel} from './document-model.mjs';
import {driver,grid} from './test-document.mjs';
import {getFlow,project,raw,clone} from './document-tree.mjs';
const text=(m,id)=>raw(project(getFlow(m.snapshot().document,id)));
const point=(offset)=>({kind:'text',flowId:'body',blockId:'before',offset});
const tests={
 anchorGeneration(C){const {m,cmd,app}=driver(grid(),[0,0],C);const id=cmd('registerAnchor',{point:point(1)}).anchorId;cmd('typeText',{text:'X'});cmd('moveAnchor',{anchorId:id,point:point(4)});app('undo');assert.equal(new Map(m.snapshot().anchors).get(id).point.offset,3);},
 committedIsolation(C){const {m,cmd,n}=driver(grid(),[0,0],C);cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});const before=m.snapshot().document;n('preedit',{text:'XY'});assert.deepEqual(m.snapshot().committedDocument,before);},
 cellAcceptance(C){const {m,cmd,n}=driver(grid(),[0,0],C);cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});n('preedit',{text:'X'});n('commit',{text:'X'});assert.equal(text(m,'f11'),'');},
 globalRevision(C){const {m,cmd}=driver(grid(),[0,0],C);cmd('selectFlow',{toFlow:'f00'});const rev=m.snapshot().documentRevision;cmd('patchCell',{tableId:'t',cellId:'c11',patch:{background:'blue'}});assert.equal(m.snapshot().documentRevision,rev+1);},
 finalScope(C){const {m,cmd,native}=driver(grid(),[0,0],C);const old=m.snapshot().epoch;cmd('selectFlow',{toFlow:'f00'});assert.equal(m.snapshot().epoch,old+1);assert.equal(native('commit',{text:'wrong',stamp:{epoch:old,token:null}}).reason,'StaleEpoch');},
 cancelledCredit(C){const {m,cmd,n,app}=driver(grid(),[0,0],C);n('preedit',{text:'X'});const f=app('format',{target:'composition',patch:{bold:true}});const u=app('undo');cmd('cancelCommand',{ticket:f.ticket});n('commit',{text:'X'});assert.equal(new Map(m.snapshot().outcomes).get(u.ticket).reason,'CommandWithoutHistory');assert.equal(text(m,'body'),'Xbefore\n\ufffc\nafter');},
 inlineMove(C){const {m,cmd}=driver(grid(),[0,0],C);cmd('selectFlow',{toFlow:'f00',offset:1});const id=cmd('insertInlineAtom').nodeId;cmd('moveNode',{nodeId:id,parentId:'p01',index:1});assert.equal(text(m,'f00'),'A');assert.equal(text(m,'f01'),'B\ufffc');},
 historyDocument(C){const {m,cmd,app}=driver(grid(),[0,0],C);const before=m.snapshot().document;cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});cmd('clearCells');app('undo');assert.deepEqual(m.snapshot().document,before);},
};
class RestoreRelocatedAnchor extends DocumentModel{_mapAnchors(a,b,restore){if(restore){restore=clone(restore);for(const [id,v]of restore)if(this.anchors.has(id))v.generation=this.anchors.get(id).generation;}super._mapAnchors(a,b,restore);}}
class ExposeProvisionalAsCommitted extends DocumentModel{_doc(){return super._doc(false);}}
class LoseSecondaryCells extends DocumentModel{_acceptVisible(){this.cellPrelude=null;super._acceptVisible();}}
class ActiveFlowRevisionOnly extends DocumentModel{_semanticCurrent(){return this.kernel.tree();}}
class KeepOldScopeEpoch extends DocumentModel{_beforePublication(s,q){super._beforePublication(s,q);this.epoch=s.fields.epoch;}}
class RemoveCancelledCredit extends DocumentModel{_executeCommand(q){const r=super._executeCommand(q);if(q.payload.command==='cancelCommand')this.pending=this.pending.filter(x=>x.ticket!==q.payload.ticket);return r;}}
class SkipInlineMove extends DocumentModel{_executeCommand(q){if(q.payload.command==='moveNode')return {status:'NoChange',historySlot:false};return super._executeCommand(q);}}
class FlowOnlyHistory extends DocumentModel{_restoreHistory(b){if(!b.document)return super._restoreHistory(b);const bad=clone(b);bad.document=this._doc();super._restoreHistory(bad);}}
export function documentMutations(){const specs=[['anchorGeneration',RestoreRelocatedAnchor],['committedIsolation',ExposeProvisionalAsCommitted],['cellAcceptance',LoseSecondaryCells],['globalRevision',ActiveFlowRevisionOnly],['finalScope',KeepOldScopeEpoch],['cancelledCredit',RemoveCancelledCredit],['inlineMove',SkipInlineMove],['historyDocument',FlowOnlyHistory]],evidence=[];
for(const [name,C]of specs){tests[name](DocumentModel);let error;try{tests[name](C);}catch(e){error=e;}assert.equal(error?.code,'ERR_ASSERTION',`${name}: must fail a behavioral assertion, not crash`);evidence.push({name,mutant:C.name,message:error.message});}return {controls:specs.length,killed:specs.length,evidence};}
