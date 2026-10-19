import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import bidiFactory from './runtime/node_modules/bidi-js/dist/bidi.mjs';
import { BidiContextTree } from './bidi-context-tree.mjs';
const bidi = bidiFactory();
const reference = (s,b) => [...bidi.getEmbeddingLevels(s,b?'rtl':'ltr').levels];
let comparisons=0, fixtures=0, edits=0;
function check(s,b,tree=new BidiContextTree(s,b)) {
  const expected=reference(s,b), actual=tree.levels();
  assert.deepEqual(actual,expected,JSON.stringify({s,b,expected,actual}));
  comparisons += s.length; fixtures++; return tree;
}
// Exhaust short strings over strongs, one bracket kind, and neutrals.
const alphabet=['a','\u05d0','.','(',')'];
let frontier=[''];
for(let length=0;length<=6;length++) {
  for(const s of frontier) for(const b of [0,1]) check(s,b);
  if(length<6) frontier=frontier.flatMap(s=>alphabet.map(c=>s+c));
}
let seed=0xb1d1cafe;
function random(n) { seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;return(seed>>>0)%n; }
const alphabet2=['a','\u05d0',' ','.','(',')','[',']','{','}'];
for(let trial=0;trial<300;trial++) {
  const n=1+random(300), b=random(2);
  let s=Array.from({length:n},()=>alphabet2[random(alphabet2.length)]).join('');
  const tree=check(s,b);
  for(let edit=0;edit<30;edit++) {
    const candidates=[...s].map((c,i)=>/[a\u05d0 .]/u.test(c)?i:-1).filter(i=>i>=0);
    if(!candidates.length) break;
    const i=candidates[random(candidates.length)], c=alphabet2[random(4)];
    tree.replace(i,c); s=s.slice(0,i)+c+s.slice(i+1); edits++;
    check(s,b,tree);
  }
}
for(const depth of [1,2,62,63,64,65,126]) {
  for(const b of [0,1]) {
    check('a'+ '('.repeat(depth)+'\u05d0'+')'.repeat(depth),b);
    check('(\u05d0)'+ '['.repeat(depth)+'a'+']'.repeat(depth),b);
    check('a'+ '[('.repeat(depth)+'\u05d0'+')]'.repeat(depth),b);
  }
}
const n=100000;
const old='a'+'(\u05d0)'.repeat(n);
let t=performance.now(); const tree=new BidiContextTree(old,0); const buildMs=performance.now()-t;
const next='\u05d0'+old.slice(1);
t=performance.now();const expected=reference(next,0);const baselineMs=performance.now()-t;
tree.repairs=tree.visits=0;
t=performance.now();tree.replace(0,'\u05d0');const updateMs=performance.now()-t;
const updatedNodes=tree.repairs;
for(const i of [0,1,2,3,300,150001,old.length-1]) assert.equal(tree.levelAt(i),expected[i]);
const queryVisits=tree.visits;
const oldLevels=reference(old,0);
const changed=expected.filter((v,i)=>v!==oldLevels[i]).length;
assert.equal(changed,2*n+1);
console.log(JSON.stringify({
  runtime:process.version, oracle:'bidi-js@1.1.0 (declares Unicode 13)',
  scope:'L/R/ON/WS, ASCII brackets, one run sequence, fixed base direction',
  fixtures,comparisons,edits,
  adversary:{characters:old.length, changedLevels:changed,updatedNodes,queryVisits,
    buildMs,baselineMs,updateMs, note:'Single local timing sample, not a production latency claim.'},
  unsupported:['weak types','NSM','explicit embeddings/isolates','insertion/deletion','incremental bracket topology','glyph layout'],
},null,2));
