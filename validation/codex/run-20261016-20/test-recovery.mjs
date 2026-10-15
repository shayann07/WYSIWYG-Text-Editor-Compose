import assert from 'node:assert/strict';
import {JournalModel,materializeRecovery,acceptRecoveredDraft,encodeFrame,resumeJournal} from './recovery-model.mjs';
import {serializeCanonicalDocument1 as encodeDoc,deserializeCanonicalDocument1 as decodeDoc} from './canonical-codec.mjs';
import {driver,grid} from './test-document.mjs';
import {DocumentModel} from './document-model.mjs';
import {textBlock as p,container as c} from './structural-model.mjs';
import {equal} from './integrated-model.mjs';
import {getFlow,project,raw} from './document-tree.mjs';
const body=(...n)=>c('body','flow',n);
export function recoveryTests(){let cases=0;
 const d=driver(grid(),[0,0],DocumentModel),j=new JournalModel();j.append(d.m.snapshot());j.sync();j.checkpointAt();d.cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});d.n('preedit',{text:'XY'});j.append(d.m.snapshot());j.sync();const recovered=materializeRecovery(j.recover());assert.ok(equal(recovered.model.snapshot().document,grid()));assert.ok(recovered.draft);assert.equal(recovered.model.snapshot().history.length,0);acceptRecoveredDraft(recovered.model,recovered.draft);assert.equal(raw(project(getFlow(recovered.model.snapshot().document,'f11'))),'');assert.equal(recovered.model.dispatch({type:'undo'}).status,'Accepted');assert.ok(equal(recovered.model.snapshot().document,grid()));cases++;
 const revision=d.m.snapshot().documentRevision;d.n('partialCommit',{length:1});assert.equal(d.m.snapshot().documentRevision,revision);j.append(d.m.snapshot());j.sync();let rec=materializeRecovery(j.recover());assert.equal(raw(project(getFlow(rec.model.snapshot().document,'f00'))),'X');assert.equal(raw(project(getFlow(rec.model.snapshot().document,'f11'))),'');assert.ok(rec.draft);cases++;
 d.n('commit',{text:'Y'});j.append(d.m.snapshot());j.sync();const terminal=j.recover();assert.equal(terminal.state.isComposing,false);assert.equal(materializeRecovery(terminal).draft,null);cases++;
 // Every byte cut in a new frame must recover the prior complete state.
 const small=driver(body(p('p','a')),[0,1],DocumentModel),base=new JournalModel();base.append(small.m.snapshot());base.sync();const prefix=Buffer.from(base.durable);small.n('preedit',{text:'X'});base.append(small.m.snapshot());const suffix=base.volatile.subarray(prefix.length);let tears=0;for(let cut=0;cut<suffix.length;cut++){const x=new JournalModel();x.durable=Buffer.concat([prefix,suffix.subarray(0,cut)]);assert.equal(x.recover().sequence,'1');tears++;}cases++;
 // Unflushed bytes and failed sync never acknowledge a newer state.
 const failed=new JournalModel();failed.append(small.m.snapshot());failed.sync();const old=failed.durableAck;small.n('commit',{text:'X'});failed.append(small.m.snapshot());assert.equal(failed.sync({fail:true}).durableAck,String(old));assert.equal(failed.recover().state.isComposing,true);failed.sync();assert.equal(failed.recover().state.isComposing,false);cases++;
 for(const stage of ['beforeFlush','beforeReplace','afterReplace','complete']){const x=new JournalModel();x.append(small.m.snapshot());x.sync();x.checkpointAt(stage);assert.equal(x.recover().state.isComposing,false);assert.equal(x.recover().sequence,'1');cases++;}
 // Corruption/interior gaps cannot be skipped to reach a later valid record.
 const t=driver(body(p('p','a')),[0,0],DocumentModel),x=new JournalModel();x.append(t.m.snapshot());x.sync();const n1=x.durable.length;t.n('commit',{text:'X',stamp:{token:null}});x.append(t.m.snapshot());x.sync();const n2=x.durable.length;t.n('commit',{text:'Y',stamp:{token:null}});x.append(t.m.snapshot());x.sync();x.durable[n1+12]^=1;assert.equal(x.recover().sequence,'1');assert.ok(x.recover().diagnostics.length);cases++;
 const gap=new JournalModel();gap.durable=Buffer.concat([x.durable.subarray(0,n1),x.durable.subarray(n2)]);assert.equal(gap.recover().sequence,'1');assert.equal(gap.recover().diagnostics[0].reason,'BrokenSequenceOrChain');cases++;
 const doc=body(p('p','\ud800\u0301😀'));doc.children[0].units[1].attrs={italic:true};const cm=new DocumentModel(doc);assert.ok(equal(decodeDoc(encodeDoc(cm.snapshot().document)),cm.snapshot().document));cases++;
 const z=driver(body(p('p','')),[0,0],DocumentModel),zj=new JournalModel();z.n('commit',{text:'X'});zj.append(z.m.snapshot());zj.sync();const zz=materializeRecovery(zj.recover()).model;assert.equal(zz.dispatch({type:'commit',text:'Y',stamp:{epoch:1,generation:1,sequence:1,token:null,verified:true,fenceVerified:true}}).status,'Accepted');assert.equal(zz.snapshot().text,'XY');assert.equal(new Set(zz.snapshot().units.map(u=>u.tokenId)).size,2);cases++;
 // An overlong length field rejects before allocating its claimed payload.
 const huge=Buffer.from(prefix);huge.writeUInt32LE(0xffffffff,4);const broken=new JournalModel();broken.durable=huge;assert.throws(()=>broken.recover(),/NoRecoverableRecord/);cases++;
 // An unrelated autosave/checkpoint must not discard an unresolved recovery draft.
 const live=driver(grid(),[0,0],DocumentModel),pending=new JournalModel();live.cmd('selectCells',{tableId:'t',anchorCell:'c00',focusCell:'c11'});live.n('preedit',{text:'Z'});pending.append(live.m.snapshot());pending.sync();const first=pending.recover(),materialized=materializeRecovery(first),resumed=resumeJournal(first);resumed.append(materialized.model.snapshot());resumed.sync();resumed.checkpointAt();const second=materializeRecovery(resumed.recover());assert.ok(second.draft);assert.ok(equal(second.model.snapshot().document,grid()));acceptRecoveredDraft(second.model,second.draft);assert.equal(raw(project(getFlow(second.model.snapshot().document,'f11'))),'');cases++;
 // Draft dismissal becomes durable only with a subsequent synced record.
 const decisions=resumeJournal(resumed.recover());decisions.pendingDrafts=[];decisions.append(second.model.snapshot());decisions.sync({fail:true});assert.ok(materializeRecovery(decisions.recover()).draft);decisions.sync();assert.equal(materializeRecovery(decisions.recover()).draft,null);cases++;
 const poisoned=new JournalModel();poisoned.append(live.m.snapshot(),{tearAt:4});assert.throws(()=>poisoned.append(live.m.snapshot()),/NeedsRecovery/);cases++;
 return {cases,tornByteCuts:tears,scope:'Byte framing and simulated persistence boundaries, no physical fsync/rename/power-loss qualification'};
}
