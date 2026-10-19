import assert from 'node:assert/strict';
import {StructuralModel as M,textBlock as p,container as c,project,raw,index,leaves,validateTree,clone} from './structural-model.mjs';
import {AdmissionModel,HistoryModel,rankWorkBound} from './protocol-model.mjs';
const root=(...xs)=>c('f','flow',xs),item=(id,...xs)=>c(id,'item',xs),list=(id,...xs)=>c(id,'list',xs);
const apply=(m,payload)=>{const r=m.applyInput(payload);assert.notEqual(r.status,'Rejected',JSON.stringify(r));validateTree(m.tree());assert.equal(raw(project(m.tree())),m.docText);return r;};
const txt=n=>n.units?.map(u=>u.char).join('');
const start=(m,a,b)=>apply(m,{type:'startComposition',range:[a,b]});
const edit=(m,text)=>apply(m,{type:'preedit',text});
const cancel=m=>apply(m,{type:'cancel'});
const commit=m=>apply(m,{type:'unmark',settled:true});
function atomFixture(){const a=p('p','AB');a.units.splice(1,0,{kind:'inlineAtom',tokenId:'a',nodeId:'a',char:'\ufffc',attrs:{italic:true},payload:{label:'mention'},originSessionId:null});return root(a);}

export function structuralTests(){let named=0;
  // Exact structure, IDs, props and attributes survive unchanged and detour candidates.
  {const src=atomFixture(),m=new M(src,[0,3]);start(m,0,3);edit(m,'A\ufffcB');assert.deepEqual(m.tree(),src);edit(m,'X');assert.equal(index(m.tree()).get('p').node.units.length,1);edit(m,'A\ufffcB');assert.deepEqual(m.tree(),src);edit(m,'A\ufffcC');apply(m,{type:'partialCommit',length:2});cancel(m);assert.deepEqual(m.tree(),src);assert.equal(m.undoStack.length,0);named++;}
  {const src=atomFixture(),m=new M(src,[0,3]);start(m,0,3);edit(m,'XY');apply(m,{type:'partialCommit',length:1});cancel(m);assert.equal(m.docText,'X');assert.equal(m.undoStack.length,1);m.undo();assert.deepEqual(m.tree(),src);m.redo();assert.equal(m.docText,'X');named++;}
  // Both endpoints in the same item never delete the surviving item.
  {const src=root(list('L',item('li',p('a','ab'),p('b','cd')))),m=new M(src);start(m,1,4);edit(m,'X');commit(m);assert.deepEqual(leaves(m.tree()).map(n=>[n.id,txt(n)]),[['a','aXd']]);assert.ok(index(m.tree()).has('li'));m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Ordinary sibling list replacement also transfers trailing blocks, without losing IDs.
  {const src=root(list('L',item('i1',p('a','Buy ')),item('i2',p('b','call mom'),list('sub',item('si',p('tail','today')))))),m=new M(src);start(m,4,9);edit(m,'and');commit(m);const t=m.tree(),ix=index(t);assert.equal(txt(ix.get('a').node),'Buy and mom');assert.equal(ix.get('sub').parent,'i1');assert.ok(!ix.has('i2'));assert.equal(txt(ix.get('tail').node),'today');m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Ancestor endpoints keep trailing text in its existing position, before later items.
  {const src=root(list('L',item('outer',p('A','A'),list('nested',item('Bitem',p('B','B'),p('tail','TAIL')),item('Citem',p('C','C')))))),m=new M(src);start(m,1,3);edit(m,'');commit(m);assert.deepEqual(leaves(m.tree()).map(n=>n.id),['A','tail','C']);assert.equal(index(m.tree()).get('tail').parent,'Bitem');m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Descendant -> ancestor, inside -> outside, and outside -> inside lists.
  for(const src of [root(list('L',item('outer',p('a','A'),list('n',item('inner',p('b','B'))),p('z','Z')))),root(list('L',item('i',p('a','A'))),p('z','Z')),root(p('a','A'),list('L',item('i',p('z','Z'),list('n',item('ni',p('t','T'))))))]){
    const tape=project(src),end=tape.findIndex(u=>u.tokenId==='z:0'),begin=tape.findIndex(u=>u.tokenId===(index(src).has('b')?'b:0':'a:0'))+1,m=new M(src);start(m,begin,end+1);edit(m,'X');commit(m);assert.equal(m.docText,raw(tape).slice(0,begin)+'X'+raw(tape).slice(end+1));m.undo();assert.deepEqual(m.tree(),src);named++;
  }
  // Container boundaries are provenance, not a stack of close/open instructions.
  {const src=root(p('a','A'),c('q','quote',[p('b','B')]),p('z','Z')),m=new M(src);start(m,1,2);edit(m,'');commit(m);assert.deepEqual(leaves(m.tree()).map(n=>[n.id,txt(n)]),[['a','AB'],['z','Z']]);assert.ok(!index(m.tree()).has('q'));m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Headless list items may retain a nested list followed by an ordinary block.
  {const src=root(p('a','Intro '),list('L',item('li',p('b','item'),list('n',item('ni',p('c','sub'))),p('t','trailing')))),m=new M(src);start(m,6,11);edit(m,'');commit(m);assert.deepEqual(leaves(m.tree()).map(n=>n.id),['a','c','t']);assert.equal(index(m.tree()).get('n').parent,'li');assert.equal(index(m.tree()).get('t').parent,'li');m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Selected block objects (including a table payload) are atomic, undoable removals.
  {const src=root(p('a','A'),{id:'table',type:'object',props:{kind:'table',cells:[{id:'cell',text:'keep'}]}},p('b','B')),m=new M(src);start(m,1,4);edit(m,'X');commit(m);assert.equal(m.docText,'AXB');assert.ok(!index(m.tree()).has('table'));m.undo();assert.deepEqual(m.tree(),src);named++;}
  {const src=root(p('a','A'),{id:'object',type:'object',props:{kind:'image'}},p('b','B')),m=new M(src);start(m,2,3);edit(m,'X');commit(m);assert.equal(m.docText,'A\nX\nB');assert.equal(index(m.tree()).get('object').node.type,'paragraph');m.undo();assert.deepEqual(m.tree(),src);named++;}
  {const code=p('code','A');code.type='code';code.props={language:'kotlin'};const atom=atomFixture().children[0],src=root(code,atom),m=new M(src);start(m,1,2);edit(m,'');commit(m);assert.equal(m.tree().children[0].type,'paragraph');assert.equal(m.tree().children[0].units.find(u=>u.kind==='inlineAtom').nodeId,'a');m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Unsupported retained-object seam rewrites fail atomically, not by flattening a table.
  {const src=root(p('a','A'),{id:'o',type:'object',props:{kind:'image'}},p('b','B')),m=new M(src);start(m,1,2);const before=m.snapshot();assert.equal(m.applyInput({type:'preedit',text:''}).status,'Rejected');assert.deepEqual(m.snapshot(),before);assert.deepEqual(m.tree(),src);named++;}
  // Whole flow deletion preserves a landing paragraph and typing attributes.
  {const src=atomFixture();src.children[0].units[0].attrs={bold:true};const m=new M(src);start(m,0,3);edit(m,'');assert.deepEqual(m.tree().children[0].emptyBlockAttributes,{bold:true});cancel(m);assert.deepEqual(m.tree(),src);named++;}
  // Nonterminal unmark retains the committed preimage; cancel still restores it.
  {const src=root(p('a','ab'),p('b','cd')),m=new M(src);start(m,1,4);edit(m,'XY');apply(m,{type:'unmark',settled:false});assert.deepEqual(m.committedTree(),src);cancel(m);assert.deepEqual(m.tree(),src);named++;}
  // Exact native removal operates on the visible projection, not a Dc range.
  {const m=new M(root(p('p','abcd')));start(m,1,2);edit(m,'XY');apply(m,{type:'retarget',range:[0,4]});apply(m,{type:'nativeRemoval',range:[1,3],replacementText:''});assert.equal(m.docText,'acd');assert.equal(m.undoStack.length,1);m.undo();assert.equal(m.docText,'abcd');named++;}
  // Connection replacement preserves text/history but explicitly cancels old commands.
  {const m=new M(root(p('p','a')));start(m,1,1);edit(m,'X');const q=m.dispatchCommand({type:'Format',patch:{bold:true}},{type:'CompositionTarget'});m.openNewConnection();assert.equal(m.docText,'aX');assert.equal(m.undoStack.length,1);assert.equal(m.commandOutcomes.get(q.ticketId).reason,'StaleEpoch');named++;}
  // Code/prose selection replacement: explicit selection permits left block type to win.
  {const code=p('b','cd');code.type='code';const src=root(p('a','ab'),code),m=new M(src);start(m,1,4);edit(m,'Q');commit(m);assert.equal(m.tree().children[0].type,'paragraph');m.undo();assert.deepEqual(m.tree(),src);named++;}
  // Unselected object expansion and foreign cell flow are rejected, without mutation.
  {const src=atomFixture(),m=new M(src);start(m,0,1);const before=m.snapshot();assert.equal(m.applyInput({type:'retarget',range:[0,3]}).reason,'ProtectedStructuralBoundary');assert.deepEqual(m.snapshot(),before);assert.equal(m.applyInput({type:'preedit',text:'X',flowId:'cell'}).reason,'FlowMismatch');named++;}
  return {named};
}

export function overlayTests(){let cases=0;
  // T5: d is part of the replacement. It must NOT survive as a duplicated suffix.
  for(const partial of [false,true]){const m=new M(root(p('p','abcd')));start(m,1,3);edit(m,'XYZ');apply(m,{type:'retarget',range:[2,5]});edit(m,'PQ');assert.equal(m.docText,'aXPQ');if(partial)apply(m,{type:'partialCommit',length:1});cancel(m);assert.equal(m.docText,partial?'aXP':'abcd');cases++;}
  // Backward disjoint retarget retains a trailing provisional island after full partial commit.
  for(const k of [0,1,2]){const m=new M(root(p('p','0123456789')));start(m,6,8);edit(m,'CD');apply(m,{type:'retarget',range:[1,3]});edit(m,'AB');assert.equal(m.docText,'0AB345CD89');apply(m,{type:'partialCommit',length:k});assert.deepEqual(m.markedRange,[1+k,3]);cancel(m);assert.equal(m.docText,['0123456789','0A3456789','0AB3456789'][k]);cases++;}
  // Atomic absorbed segments survive candidate detours and cancellation after a retained prefix.
  {const m=new M(root(p('p','abcd')));start(m,1,2);edit(m,'XY');apply(m,{type:'retarget',range:[0,4]});edit(m,'aW');apply(m,{type:'partialCommit',length:1});cancel(m);assert.equal(m.docText,'abcd');cases++;}
  return {cases};
}

export function generatedStructuralTests(){let cases=0;
  // Independent expected plaintext splice; exact original tree is an independent
  // fixture preimage. No candidate tree compiler supplies expected answers.
  const shapes=[root(p('a','ab'),p('b','cd')),root(list('L',item('i',p('a','ab')),item('j',p('b','cd')))),root(p('a','ab'),c('q','quote',[p('b','cd')])),root(list('L',item('i',p('a','ab'),list('n',item('ni',p('b','cd'))))),p('z','ef'))];
  for(const src of shapes){const tape=project(src),s=raw(tape);for(let a=0;a<=s.length;a++)for(let b=a;b<=s.length;b++)for(const mode of ['cancel','commit','partial']){
    const m=new M(src,[b,a]);start(m,a,b);edit(m,'XY');assert.equal(m.docText,s.slice(0,a)+'XY'+s.slice(b));
    if(mode==='partial'){apply(m,{type:'partialCommit',length:1});cancel(m);assert.equal(m.docText,s.slice(0,a)+'X'+s.slice(b));}
    else if(mode==='commit'){commit(m);assert.equal(m.docText,s.slice(0,a)+'XY'+s.slice(b));}
    else {cancel(m);assert.deepEqual(m.tree(),src);}
    if(mode!=='cancel'){m.undo();assert.deepEqual(m.tree(),src);m.redo();assert.equal(m.docText,s.slice(0,a)+(mode==='partial'?'X':'XY')+s.slice(b));}
    cases++;
  }}return {cases,oracle:'independent raw splice, exact fixture-root restoration, schema checks'};
}

export function admissionTests(){let cases=0;const stamp=(token='A',generation=1,extra={})=>({epoch:1,generation,token,verified:true,...extra});
  {const m=new AdmissionModel();m.capture({kind:'preedit',text:'n',stamp:stamp()});m.capture({kind:'preedit',text:'ni',stamp:stamp()});m.capture({kind:'commit',text:'你',stamp:stamp()});m.drain();assert.equal(m.published.text,'hello你');cases++;}
  {const m=new AdmissionModel();m.capture({kind:'preedit',text:'n',stamp:stamp(),fail:true});m.capture({kind:'commit',text:'你',stamp:stamp()});m.drain();assert.equal(m.published.text,'hello');assert.equal(m.receipts.at(-1).reason,'InitiatingSessionFailed');cases++;}
  {const m=new AdmissionModel('hello world',[0,5]);m.capture({kind:'preedit',text:'n',stamp:stamp()});m.capture({kind:'preedit',text:'ni',stamp:stamp()});m.capture({kind:'abort'});const s=m.capture({kind:'selection',range:[11,11]});m.capture({kind:'commit',text:'你',stamp:stamp()});m.capture({kind:'commit',text:'!',afterTicket:s,stamp:stamp(null,2,{fenceVerified:true})});m.drain();assert.equal(m.published.text,'hello world!');assert.equal(m.receipts.at(-2).reason,'StaleGeneration');cases++;}
  {const m=new AdmissionModel();m.capture({kind:'abort'});m.capture({kind:'commit',text:'?',stamp:stamp(null,2)});m.drain();assert.equal(m.receipts.at(-1).reason,'HostFenceRequired');assert.equal(m.published.text,'hello');cases++;}
  {const m=new AdmissionModel();m.capture({kind:'commit',text:'?',stamp:{...stamp(),verified:false}});assert.equal(m.receipts[0].reason,'AmbiguousHostProvenance');assert.equal(m.queue.length,0);cases++;}
  {const m=new AdmissionModel();m.capture({kind:'selection',range:[0,0]});m.capture({kind:'commit',text:'X',stamp:stamp(null)});m.drain();assert.equal(m.receipts.at(-1).reason,'BasisNeedsIdentityMapping');assert.equal(m.published.text,'hello');cases++;}
  return {cases,limits:'synthetic stamps; alternate basis identity mapping remains in prior model; no host provenance claim'};
}

const h=()=>new HistoryModel('hello',[{id:1,before:'',after:'hello',kind:'Typing'}]);
export function historyTests(){let cases=0;
  for(const terminal of ['commit','cancel']){const m=h();m.start();m.preedit('hello world');m.enqueueUndo();m.settle(terminal);assert.equal(m.value,'hello');assert.equal(m.history.length,1);assert.equal(m.redo.length,terminal==='commit'?1:0);cases++;}
  {const m=h();m.start();m.enqueueUndo();m.settle('commit','world');assert.equal(m.value,'hello');assert.equal(m.history.at(-1).id,1);assert.equal(m.redo.at(-1).after,'world');cases++;}
  {const m=h();m.start();m.preedit('hello ab');m.partial('hello a');m.enqueueFormat(x=>x+'[bold]');m.enqueueUndo();m.enqueueUndo();m.enqueueUndo();m.settle('cancel');assert.equal(m.value,'');assert.equal(m.history.length,0);assert.deepEqual(m.redo.map(e=>e.kind),['Composition','Typing']);cases++;}
  {const m=h();m.start();m.preedit('hello!');m.enqueueUndo();m.enqueueFormat(x=>x+'x',{cancelOnAbort:false});m.enqueueUndo();m.enqueueUndo();m.settle('commit');assert.equal(m.value,'');assert.deepEqual(m.redo.map(e=>e.kind),['Formatting','Typing']);cases++;}
  {const m=h();m.start();m.preedit('world');m.enqueueUndo();m.enqueueUndo();const before=JSON.stringify([m.value,m.history,m.redo,m.outcomes]);assert.throws(()=>m.settle('commit',m.live,{failAfter:0}),/Injected/);assert.equal(JSON.stringify([m.value,m.history,m.redo,m.outcomes]),before);m.settle('commit');assert.equal(m.value,'');cases++;}
  {const m=h();m.start();m.preedit('world');m.enqueueUndo();assert.throws(()=>m.settle('commit',m.live,{wrongTop:true}),/HistoryOrderViolation/);assert.equal(m.value,'hello');cases++;}
  // A clean session does not manufacture a cancelled-composition credit.
  {const m=h();m.start();m.enqueueUndo();m.settle('cancel');assert.equal(m.value,'');cases++;}
  return {cases};
}

export function mutations(){
  // Actual behavior changes, tested against the same control assertions.
  class DropProvenance extends M{_preedit(...args){const r=super._preedit(...args);for(const seg of this.provisionalOverlay?.segments??[])for(const u of seg.units)if(u.kind==='inlineAtom')u.kind='text';return r;}}
  const checkAtom=C=>{const src=atomFixture(),m=new C(src);start(m,0,3);edit(m,'A\ufffcB');assert.deepEqual(m.tree(),src);};
  class FlushUnmark extends M{_unmark(settled,...args){const r=super._unmark(settled,...args);if(!settled)this._flushOverlayToCommitted();return r;}}
  const checkUnmark=C=>{const src=root(p('a','ab'),p('b','cd')),m=new C(src);start(m,1,4);edit(m,'XY');apply(m,{type:'unmark',settled:false});assert.deepEqual(m.committedTree(),src);};
  class LoseTrailingIsland extends M{_partialCommit(...args){const r=super._partialCommit(...args);if(this.markedWindow?.relEnd===0)this.provisionalOverlay.segments=[{dcStart:3,dcEnd:3,units:[]}];return r;}}
  const checkIsland=C=>{const m=new C(root(p('p','0123456789')));start(m,6,8);edit(m,'CD');apply(m,{type:'retarget',range:[1,3]});edit(m,'AB');apply(m,{type:'partialCommit',length:2});assert.equal(m.docText,'0AB345CD89');};
  let killed=0;for(const [check,Mutant]of [[checkAtom,DropProvenance],[checkUnmark,FlushUnmark],[checkIsland,LoseTrailingIsland]]){check(M);assert.throws(()=>check(Mutant),e=>e.code==='ERR_ASSERTION');killed++;}
  class GuessProvenance extends AdmissionModel{capture(p){return super.capture({...p,stamp:{...p.stamp,verified:true}});}}
  const checkProvenance=C=>{const m=new C();m.capture({kind:'commit',text:'?',stamp:{epoch:1,generation:1,token:null,verified:false}});assert.equal(m.queue.length,0);assert.equal(m.receipts[0].reason,'AmbiguousHostProvenance');};
  checkProvenance(AdmissionModel);assert.throws(()=>checkProvenance(GuessProvenance),e=>e.code==='ERR_ASSERTION');killed++;
  class DoubleUndo extends HistoryModel{settle(...args){this.undoSawDirty=false;return super.settle(...args);}}
  const checkCancel=C=>{const m=new C('hello',[{id:1,before:'',after:'hello',kind:'Typing'}]);m.start();m.preedit('world');m.enqueueUndo();m.settle('cancel');assert.equal(m.value,'hello');};
  checkCancel(HistoryModel);assert.throws(()=>checkCancel(DoubleUndo),e=>e.code==='ERR_ASSERTION');killed++;
  class IgnoreHistoryGuard extends HistoryModel{settle(kind,final,options={}){return super.settle(kind,final,{...options,wrongTop:false});}}
  const checkGuard=C=>{const m=new C('hello',[{id:1,before:'',after:'hello',kind:'Typing'}]);m.start();m.preedit('world');m.enqueueUndo();assert.throws(()=>m.settle('commit','world',{wrongTop:true}),/HistoryOrderViolation/);};
  checkGuard(HistoryModel);assert.throws(()=>checkGuard(IgnoreHistoryGuard),e=>e.code==='ERR_ASSERTION');killed++;
  assert.ok(rankWorkBound(2,[2,2,2],32,10)>rankWorkBound(2,[2],32,10));
  return {passingControls:killed,killed};
}

export function historyExploration(){
  let cases=0;const sequences=[[]];for(let len=1;len<=4;len++)for(const prev of sequences.filter(x=>x.length===len-1))for(const e of ['undo','format','noop'])sequences.push([...prev,e]);
  for(const seq of sequences)for(const state of ['clean','dirty','partial'])for(const terminal of ['commit','cancel']){
    const m=h();m.start();if(state!=='clean')m.preedit('world');if(state==='partial')m.partial('hellow');
    for(const e of seq)if(e==='undo')m.enqueueUndo();else m.enqueueFormat(e==='format'?x=>x+'*':x=>x);
    m.settle(terminal);
    assert.equal(m.history.at(-1)?.after??'',m.value,'history root equals current root');
    for(let i=1;i<m.history.length;i++)assert.equal(m.history[i-1].after,m.history[i].before,'history continuity');
    let value=m.value;for(let i=m.redo.length-1;i>=0;i--){assert.equal(m.redo[i].before,value,'redo continuity');value=m.redo[i].after;}
    assert.equal(m.outcomes.length,seq.length,'exactly one outcome per queued command');cases++;
  }
  return {cases,maxQueuedCommands:4,assertions:'history root/chain, redo chain, ticket outcomes; not a complete state-space proof'};
}
