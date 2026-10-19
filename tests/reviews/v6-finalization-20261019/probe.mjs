import {driver} from '../../validation/codex/run-20261016-20/test-document.mjs';
import {textBlock as p,container as c} from '../../validation/codex/run-20261016-20/structural-model.mjs';
const flow=t=>c('body','flow',[p('p',t)]);
const brief=m=>({text:m.snapshot().text,committed:m.kernel.committedDoc.map(u=>u.char).join(''),marked:m.kernel.markedWindow,history:m.snapshot().history.map(h=>h.kind),redo:m.snapshot().redo.map(h=>h.kind),outcomes:m.snapshot().outcomes});
{
 const {m,n}=driver(flow('0123456789'),[1,3]);n('preedit',{text:'AB'});n('retarget',{range:[4,5]});n('preedit',{text:'XY'});console.log('B2 before',JSON.stringify(brief(m)));n('partialCommit',{length:0});console.log('B2 after',JSON.stringify(brief(m)));n('cancel');console.log('B2 cancel',JSON.stringify(brief(m)));
}
for(const ending of ['cancel','commit']){
 const {m,n,app,cmd}=driver(flow(''),[0,0]);cmd('typeText',{text:'Hello '});n('preedit',{text:'ab'});n('partialCommit',{length:1});app('undo');app('format',{target:'composition',patch:{bold:true}});app('undo');n(ending);console.log('F1 '+ending,JSON.stringify(brief(m)));
}
{
 const {m,n,app}=driver(flow('abcd'),[1,3]);n('preedit',{text:'XYZ'});n('partialCommit',{length:2});n('retarget',{range:[1,4]});n('preedit',{text:'PQ'});n('partialCommit',{length:1});console.log('F3 before cancel',JSON.stringify(brief(m)));n('cancel');console.log('F3 after cancel',JSON.stringify(brief(m)));app('undo');console.log('F3 undo',JSON.stringify(brief(m)));
}
