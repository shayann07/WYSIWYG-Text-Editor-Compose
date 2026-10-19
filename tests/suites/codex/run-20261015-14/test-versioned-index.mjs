import assert from 'node:assert/strict';
import {VersionedPieceIndex as Index,memoryEstimate} from './versioned-piece-index.mjs';
export function runVersionedIndexTests() {
  const index=new Index(4);let next=1000;
  let v=index.build([index.piece(100,5)]);
  const initial=v;
  v=index.insert(v,2,index.piece(200,1));
  assert.equal(index.rank(v,200).rank,2);assert.equal(index.rank(v,102).rank,3);
  assert.equal(index.rank(initial,102).rank,2);index.audit(v);
  let expected=[100,101,200,102,103,104];const saved=[];let seed=93451;
  const rand=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
  const verify=(version,list)=>{index.audit(version);const pieces=index.flatten(version);const actual=pieces.flatMap(p=>Array.from({length:p.length},(_,i)=>p.base+i));assert.deepEqual(actual,list);for(let i=0;i<list.length;i++)assert.equal(index.rank(version,list[i]).rank,i);assert.equal(index.rank(version,99999999).rank,null);};
  for(let step=0;step<1200;step++) {
    if(step%40===0)saved.push([v,[...expected]]);
    if(!expected.length || (expected.length<180 && rand(3)!==0)) {
      const pos=rand(expected.length+1),len=1+rand(6),base=next;next+=len;
      v=index.insert(v,pos,index.piece(base,len,step+1));expected.splice(pos,0,...Array.from({length:len},(_,i)=>base+i));
    }else {const lo=rand(expected.length),hi=lo+1+rand(expected.length-lo);v=index.remove(v,lo,hi);expected.splice(lo,hi-lo);}
    verify(v,expected);
    if(step%50===0){const [old,list]=saved[rand(saved.length)];verify(old,list);}
  }
  for(const [old,list] of saved)verify(old,list);
  // Root restoration requires swapping the complete version bundle.
  v=initial;verify(v,[100,101,102,103,104]);v=index.remove(v,0,5);verify(v,[]);v=index.insert(v,0,index.piece(next++,1));index.audit(v);
  // Operation counters separate lookup from edit maintenance; no full rebuild.
  const scaling=[];
  for(const count of [64,1024,16384]) {
    const m=new Index(16),old=m.build(Array.from({length:count},(_,i)=>m.piece(i*2,1)));
    const updated=m.insert(old,0,m.piece(count*2+10,1));const audit=m.audit(updated);
    assert.equal(m.rank(updated,(count-1)*2).rank,count);assert.equal(m.rank(old,(count-1)*2).rank,count-1);
    assert.ok(updated.work.locatorWrites<=32,'local leaf locator writes');
    assert.ok(updated.work.parentWrites<=32*(audit.height+1),'parent updates bounded by touched levels');
    scaling.push({pieces:count,...audit,edit:updated.work,lookup:m.rank(updated,(count-1)*2).work});
  }
  const estimate=memoryEstimate({units:1_000_000,pieces:1000,docNodes:100,parentEntries:99});
  assert.equal(estimate.parts.textBytes,2_000_000);
  return {randomEdits:1200,historicalVersions:saved.length,scaling,memoryEstimate:estimate,scope:'validation data structure, no Kotlin allocator measurements'};
}
if(process.argv[1]?.endsWith('test-versioned-index.mjs'))console.log(JSON.stringify(runVersionedIndexTests(),null,2));
