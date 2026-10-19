// Specification witnesses only. Does not import or modify production Kotlin code.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {DocumentModel} from '../../validation/codex/run-20261016-20/document-model.mjs';
import {driver,grid as documentGrid} from '../../validation/codex/run-20261016-20/test-document.mjs';
import {textBlock as p,container as c} from '../../validation/codex/run-20261016-20/structural-model.mjs';
import * as Y from '../../validation/integration/c0/node_modules/yjs/dist/yjs.mjs';

const dir=import.meta.dirname,root=path.resolve(dir,'../..'),results=[];
const flow=t=>c('body','flow',[p('p',t)]);
const committed=m=>m.kernel.committedDoc.map(u=>u.char).join('');
const json=v=>JSON.stringify(v);
function test(name,fn){try{const evidence=fn();results.push({name,status:'PASS',evidence});console.log('PASS',name);}catch(e){results.push({name,status:'FAIL',error:e.stack});console.error('FAIL',name,e.message);}}

// Narrow reviewed correction in an executable reference boundary, not a production fix.
export class V6Reference extends DocumentModel {
  _call(payload){
    if(payload.type!=='partialCommit'||payload.length!==0)return super._call(payload);
    const k=this.kernel;
    const r=k._runInTransaction('v6-zero-partial',()=>{
      if(!k.markedWindow||!k.provisionalOverlay)return {status:'Rejected',reason:'NoActiveMarkedRange'};
      if(payload.selection){
        const n=k._getWorkingVisibleUnits().length;
        if(!Array.isArray(payload.selection)||payload.selection.length!==2||payload.selection.some(x=>!Number.isInteger(x)||x<0||x>n))return {status:'Rejected',reason:'InvalidSelection'};
        k.selection=[...payload.selection];
      }
      // No cut, rebase, attribution-epoch reset, history, anchor or cell-prelude acceptance.
      k.sessionRevision++;
      return {status:'Accepted',type:'PartialCommitted',frozenCount:0};
    });
    if(r.status==='Rejected')throw Error(r.reason);
    return r;
  }
}

function multi(C=DocumentModel){const d=driver(flow('0123456789'),[1,3],C);d.n('preedit',{text:'AB'});d.n('retarget',{range:[4,5]});d.n('preedit',{text:'XY'});return d;}
test('B2 reproduce unchanged frozen-model zero-commit defect',()=>{
 const {m,n}=multi();n('partialCommit',{length:0});assert.equal(committed(m),'0AB3456789');n('cancel');assert.equal(m.snapshot().text,'0AB3456789');
 return {baselineDefect:'zero accepted preceding AB island',expectedWithoutBug:'0123456789',actual:m.snapshot().text};
});
test('B2 corrected zero commit preserves islands, baseline, selection and rollback',()=>{
 const {m,n,native}=multi(V6Reference);const before=m.snapshot(),overlay=json(m.kernel.provisionalOverlay),epoch=json(m.kernel.attributionEpoch),mark=json(m.kernel.markedWindow);
 n('partialCommit',{length:0});assert.equal(committed(m),'0123456789');assert.equal(json(m.kernel.provisionalOverlay),overlay);assert.equal(json(m.kernel.attributionEpoch),epoch);assert.equal(json(m.kernel.markedWindow),mark);assert.deepEqual(m.snapshot().selection,before.selection);assert.equal(m.snapshot().documentRevision,before.documentRevision);assert.ok(m.snapshot().sessionRevision>before.sessionRevision);
 const b=m.snapshot();assert.equal(native('partialCommit',{length:0,selection:[999,999]}).status,'Rejected');assert.deepEqual(m.snapshot(),b);
 n('preedit',{text:'Z'});assert.equal(m.snapshot().text,'0AB3Z56789');n('cancel');assert.equal(m.snapshot().text,'0123456789');assert.equal(m.snapshot().history.length,0);
 return {markPreserved:[3,5],afterCancel:m.snapshot().text};
});
test('B2 positive cut still accepts earlier islands and bridge exactly',()=>{
 const {m,n}=multi(V6Reference);n('partialCommit',{length:1});assert.equal(committed(m),'0AB3X56789');assert.deepEqual(m.kernel.markedWindow,{relStart:0,relEnd:1});n('cancel');assert.equal(m.snapshot().text,'0AB3X56789');return {afterCancel:m.snapshot().text};
});
test('B2 zero commit does not accept rectangular cell-clearing prelude',()=>{
 const {m,n,cmd}=driver(documentGrid(),[0,0],V6Reference);cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});const before=m.snapshot().committedDocument;n('preedit',{text:'XY'});n('partialCommit',{length:0});assert.deepEqual(m.snapshot().committedDocument,before);n('cancel');assert.deepEqual(m.snapshot().committedDocument,before);return {cellPreludeAccepted:false};
});
test('B1 explicit abort cancels composition-target format; terminal accept applies it',()=>{
 const rows=[];
 for(const ending of ['cancel','commit']){
  const {m,n,cmd,app}=driver(flow(''),[0,0],V6Reference);cmd('typeText',{text:'Hello '});n('preedit',{text:'ab'});n('partialCommit',{length:1});const f=app('format',{target:'composition',patch:{bold:true}});n(ending);
  const out=new Map(m.snapshot().outcomes).get(f.ticket);assert.equal(out.status,ending==='cancel'?'Cancelled':'Applied');assert.equal(m.snapshot().text,ending==='cancel'?'Hello a':'Hello ab');
  if(ending==='cancel')assert.equal(m.snapshot().history.length,2);else assert.equal(m.snapshot().history.length,3);
  rows.push({ending,text:m.snapshot().text,formatOutcome:out.status,groups:m.snapshot().history.length});
 }return rows;
});
test('F01 FIFO Undo/Format/Undo consumes attempted action, no leaked slot',()=>{
 const rows=[];
 for(const ending of ['cancel','commit']){
  const {m,n,app,cmd}=driver(flow(''),[0,0],V6Reference);cmd('typeText',{text:'Hello '});n('preedit',{text:'ab'});n('partialCommit',{length:1});app('undo');const f=app('format',{target:'composition',patch:{bold:true}}),u=app('undo');n(ending);
  assert.equal(m.snapshot().text,'Hello ');assert.equal(new Map(m.snapshot().outcomes).get(u.ticket).reason,'CommandWithoutHistory');assert.equal(m.pending.length,0);assert.equal(m.snapshot().redo.length,1);
  app('undo');assert.equal(m.snapshot().text,'');app('redo');assert.equal(m.snapshot().text,'Hello ');app('redo');assert.equal(m.snapshot().text,ending==='cancel'?'Hello a':'Hello ab');
  rows.push({ending,queuedFormat:new Map(m.snapshot().outcomes).get(f.ticket).reason,nextIndependentUndoWorked:true});
 }return rows;
});
test('F03 explicit reconversion acceptance supersedes old committed footprint before abort',()=>{
 const rows=[];
 for(const accept of [false,true]){
  const {m,n,app}=driver(flow('abcd'),[1,3],V6Reference);const original=m.snapshot().document;n('preedit',{text:'XYZ'});n('partialCommit',{length:2});n('retarget',{range:[1,4]});n('preedit',{text:'PQ'});if(accept)n('partialCommit',{length:1});const before=committed(m);n('cancel');assert.equal(m.snapshot().text,before);assert.equal(before,accept?'aPd':'aXYd');app('undo');assert.deepEqual(m.snapshot().document,original);rows.push({acceptedSecondPrefix:accept,committedBeforeAbort:before,abortDoesNotDeleteAcceptedText:true});
 }return rows;
});

const units=s=>Array.from({length:s.length},(_,i)=>s.charCodeAt(i));
test('F05 actual Yjs binary string loss and reversible numeric-unit transport',()=>{
 const a=new Y.Doc(),b=new Y.Doc();a.getText('text').insert(0,'ا\ud83dب');Y.applyUpdate(b,Y.encodeStateAsUpdate(a));assert.notDeepEqual(units(a.getText('text').toString()),units(b.getText('text').toString()));
 const x=new Y.Doc(),y=new Y.Doc(),all=Array.from({length:65536},(_,i)=>i);x.getArray('u16').insert(0,all);Y.applyUpdate(y,Y.encodeStateAsUpdate(x));assert.deepEqual(y.getArray('u16').toArray(),all);
 const z=new Y.Doc();Y.applyUpdate(z,Y.encodeStateAsUpdate(y));assert.deepEqual(z.getArray('u16').toArray(),all);
 return {version:JSON.parse(fs.readFileSync(path.join(root,'validation/integration/c0/node_modules/yjs/package.json'))).version,stringSource:units(a.getText('text').toString()),stringPeer:units(b.getText('text').toString()),numericRoundtripUnits:all.length,scope:'JS Yjs transport only; no Yrs/Automerge FFI qualification'};
});
test('F06 actual nested Yjs Undo risk and flat boundary witness preserving peer units',()=>{
 const a=new Y.Doc(),b=new Y.Doc(),local={},roots=a.getArray('blocks');const left=new Y.Map();left.set('id','left');left.set('text',new Y.Text('سلام دنیا'));roots.insert(0,[left]);Y.applyUpdate(b,Y.encodeStateAsUpdate(a));
 const um=new Y.UndoManager(roots,{trackedOrigins:new Set([local]),captureTimeout:0});
 a.transact(()=>{left.get('text').delete(5,4);const right=new Y.Map();right.set('id','right');right.set('text',new Y.Text('دنیا'));roots.insert(1,[right]);},local);Y.applyUpdate(b,Y.encodeStateAsUpdate(a));b.getArray('blocks').get(1).get('text').insert(4,' پیاری');Y.applyUpdate(a,Y.encodeStateAsUpdate(b));um.undo();Y.applyUpdate(b,Y.encodeStateAsUpdate(a));
 const nested=roots.toArray().map(x=>x.get('text').toString()).join('|');
 const x=new Y.Doc(),y=new Y.Doc(),seq=x.getArray('flow'),origin={};seq.insert(0,units('سلام دنیا').map((u,i)=>({id:'t'+i,u})));Y.applyUpdate(y,Y.encodeStateAsUpdate(x));const undo=new Y.UndoManager(seq,{trackedOrigins:new Set([origin]),captureTimeout:0});
 x.transact(()=>seq.insert(5,[{kind:'boundary',id:'right'}]),origin);Y.applyUpdate(y,Y.encodeStateAsUpdate(x));y.getArray('flow').insert(y.getArray('flow').length,units(' پیاری').map((u,i)=>({id:'peer'+i,u})));Y.applyUpdate(x,Y.encodeStateAsUpdate(y));undo.undo();Y.applyUpdate(y,Y.encodeStateAsUpdate(x));const flat=String.fromCharCode(...seq.toArray().filter(x=>'u'in x).map(x=>x.u));assert.equal(flat,'سلام دنیا پیاری');assert.deepEqual(seq.toArray(),y.getArray('flow').toArray());
 return {nestedVisibleAfterUndo:nested,nestedPeerTextSurvives:nested.includes('پیاری'),flatAfterUndo:flat,scope:'One split/peer-insert witness, not arbitrary structural undo proof; hidden CRDT content is not necessarily permanently erased'};
});

function grid(cells,rows,cols){const slots=Array.from({length:rows},()=>Array(cols).fill(null)),ids=new Set(),flows=new Set();for(const c of cells){assert.ok(!ids.has(c.id)&&!flows.has(c.flow),'Duplicate canonical identity');ids.add(c.id);flows.add(c.flow);for(let r=c.r;r<c.r+c.rs;r++)for(let col=c.c;col<c.c+c.cs;col++){assert.ok(r<rows&&col<cols&&r>=0&&col>=0&&!slots[r][col],'Invalid span');slots[r][col]=c;}}assert.ok(slots.flat().every(Boolean));return slots;}
function closure(slots,box){let a=[...box],old;do{old=[...a];for(let r=old[0];r<old[2];r++)for(let col=old[1];col<old[3];col++){const c=slots[r][col];a=[Math.min(a[0],c.r),Math.min(a[1],c.c),Math.max(a[2],c.r+c.rs),Math.max(a[3],c.c+c.cs)];}}while(json(old)!==json(a));const unique=new Map();for(let r=a[0];r<a[2];r++)for(let c=a[1];c<a[3];c++){const cell=slots[r][c];unique.set(cell.id,cell);}return {box:a,cells:[...unique.values()].sort((x,y)=>x.r-y.r||x.c-y.c)};}
test('F02/B8 span closure deduplicates coverage without hiding duplicate flow ownership',()=>{
 const cells=[{id:'A',flow:'fa',r:0,c:0,rs:2,cs:1},{id:'B',flow:'fb',r:0,c:1,rs:1,cs:2},{id:'C',flow:'fc',r:1,c:1,rs:1,cs:1},{id:'D',flow:'fd',r:1,c:2,rs:1,cs:1}],g=grid(cells,2,3);const x=closure(g,[1,0,2,2]);assert.deepEqual(x.box,[0,0,2,3]);assert.deepEqual(x.cells.map(c=>c.id),['A','B','C','D']);assert.equal(x.cells[0].flow,'fa');assert.throws(()=>grid([...cells.slice(0,3),{...cells[3],flow:'fa'}],2,3));
 let tested=0;for(let t=0;t<2;t++)for(let l=0;l<3;l++)for(let b=t+1;b<=2;b++)for(let r=l+1;r<=3;r++){const q=closure(g,[t,l,b,r]);assert.deepEqual(closure(g,q.box).box,q.box);assert.equal(new Set(q.cells.map(c=>c.flow)).size,q.cells.length);tested++;}
 return {rectangles:tested,closure:x.box,distinctCells:x.cells.map(c=>c.id)};
});
test('F02 merge relocates node anchors before retiring empty flow containers',()=>{
 const before={cells:[{id:'a',flow:'fa',blocks:[{id:'p1',text:'A'}]},{id:'b',flow:'fb',blocks:[{id:'p2',text:'اردو'}]}],anchors:[{id:'comment',node:'p2',flow:'fb',offset:2,deleted:false},{id:'container',cell:'b',flow:'fb',deleted:false}]},after=structuredClone(before);
 after.cells[0].blocks.push(...after.cells[1].blocks);after.cells.splice(1,1);
 for(const a of after.anchors){if(a.node==='p2')a.flow='fa';else if(a.flow==='fb'){a.deleted=true;a.orphanReason='ContainerRetired';}}
 assert.equal(after.anchors[0].deleted,false);assert.equal(after.anchors[0].flow,'fa');assert.equal(after.anchors[1].deleted,true);const undo=structuredClone(before);assert.deepEqual(undo,before);return {nodeAnchor:after.anchors[0],containerAnchor:after.anchors[1],scope:'Small semantic mapping witness; not implemented rich-table engine'};
});
test('F07 old marker vote and per-item intrinsic widths are unstable',()=>{
 const vote=(a,b)=>a>b?24:b>a?28:18;assert.deepEqual([vote(5,4),vote(5,5),vote(5,6)],[24,18,28]);const oldLeft=w=>500-Math.min(w,464);assert.notEqual(oldLeft(80),oldLeft(1500));
 // Proposed explicit item marker style and parent-derived full lane do not read inline text.
 const policy={markerSize:24,laneLeft:36,laneWidth:428,gap:8,gutter:40};for(const body of ['AB','AB😀CD','long '.repeat(100)]){const measuredMarker={size:policy.markerSize,left:policy.laneLeft-policy.gap-12};assert.equal(measuredMarker.size,24);assert.equal(measuredMarker.left,16);}
 return {oldSizeSequence:[24,18,28],newSize:24,scope:'Policy arithmetic, not a measured renderer or optical baseline test'};
});
test('F04 document-wide causal staging preserves local frontier until explicit settlement',()=>{
 const state={frontier:0,received:[],applied:[],composing:true,local:[],capacity:2};const receive=packet=>{if(state.received.length===state.capacity)return 'Backpressure';state.received.push(packet);return 'ReceivedNotApplied';};
 assert.equal(receive({id:2,depends:[1]}),'ReceivedNotApplied');assert.equal(receive({id:1,depends:[]}),'ReceivedNotApplied');assert.equal(receive({id:3,depends:[2]}),'Backpressure');state.local.push('accepted-prefix');assert.equal(state.frontier,0);assert.equal(state.applied.length,0);
 state.composing=false;while(state.received.length){const i=state.received.findIndex(p=>p.depends.every(d=>state.applied.includes(d)));assert.ok(i>=0);const [p]=state.received.splice(i,1);state.applied.push(p.id);state.frontier++;}assert.deepEqual(state.applied,[1,2]);return {applied:state.applied,scope:'Queue/frontier policy only, not a CRDT/browser implementation'};
});
test('F09 recovery quota refuses admission without FIFO eviction and preserves durable-base pins',()=>{
 const store={limit:64*1024*1024,used:1024*1024,drafts:[],pins:new Map([['base',0]])};function add(id,size){if(store.drafts.length>=16||store.used+size>store.limit)return 'CapacityExceeded';store.drafts.push({id,base:'base',size});store.used+=size;store.pins.set('base',store.pins.get('base')+1);return 'Stored';}
 for(let i=0;i<10;i++)assert.equal(add('d'+i,200000),'Stored');const saved=json(store.drafts);assert.equal(add('oversize',64*1024*1024),'CapacityExceeded');assert.equal(json(store.drafts),saved);assert.equal(store.pins.get('base'),10);return {drafts:store.drafts.length,accountedDiskBytes:store.used,metadataBytes:Buffer.byteLength(json(store.drafts)),scope:'Bounded accounting model, not process/power-loss or heap proof'};
});
test('B6 geometric inverse is a relation, strict bijection is invalid for coincident stops',()=>{
 const stops=[{offset:3,affinity:'upstream',x:20},{offset:3,affinity:'downstream',x:20}],hit={offset:3,affinity:'downstream'};assert.notDeepEqual(hit,{offset:3,affinity:'upstream'});assert.ok(stops.some(s=>s.offset===hit.offset&&s.affinity===hit.affinity));return {scope:'Counterexample to universal unique inverse; real glyph/caret qualification remains Q0'};
});
test('B3 Android contract normalization differs from strict internal range admission',()=>{
 const normalize=(start,end,n)=>{const a=Math.max(0,Math.min(n,start)),b=Math.max(0,Math.min(n,end));return {range:[Math.min(a,b),Math.max(a,b)],operation:a===b?'finishPreservingText':'markExisting'};};
 assert.deepEqual(normalize(-1,5,3),{range:[0,3],operation:'markExisting'});assert.deepEqual(normalize(5,1,3).range,[1,3]);assert.equal(normalize(-2,-1,3).operation,'finishPreservingText');
 const strict=(a,b,n)=>a>=0&&b>=a&&b<=n;assert.equal(strict(-1,5,3),false);assert.equal(strict(...normalize(-1,5,3).range,3),true);
 return {source:'Android InputConnection.setComposingRegion documented clipping and order normalization',scope:'Normalizer contract model, not Android device execution'};
});

const hash=p=>createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={status:results.every(r=>r.status==='PASS')?'PASS':'FAIL',runtime:process.version,platform:process.platform,results,sourceHashes:{'verify.mjs':hash(import.meta.filename),'frozenDocumentModel':hash(path.join(root,'validation/codex/run-20261016-20/document-model.mjs')),'yjsPackage':hash(path.join(root,'validation/integration/c0/node_modules/yjs/package.json')),'yjsDist':hash(path.join(root,'validation/integration/c0/node_modules/yjs/dist/yjs.mjs'))},limits:['Specification witnesses only; no production edits','Synthetic input, no native IME/browser/AT/PDF execution','Grid/recovery/geometry-policy witnesses are bounded models, not production implementations']};
const output=process.argv.find(x=>x.startsWith('--output='))?.slice(9);
if(output){fs.writeFileSync(path.resolve(output),JSON.stringify(report,null,2)+'\n',{flag:'wx'});}
else if(!fs.existsSync(path.join(root,'docs/plans/v6/v6-freeze-record.json'))){fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(report,null,2)+'\n');}
else console.log('Sealed evidence preserved; use --output=<new-file> to save this execution.');
if(report.status!=='PASS')process.exitCode=1;
