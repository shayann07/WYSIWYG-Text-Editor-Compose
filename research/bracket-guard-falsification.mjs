import assert from 'node:assert/strict';

// Candidate guard, NOT a proposed complete Unicode algorithm:
// cache each fragment's empty-stack execution. Reuse it under an incoming
// stack only if no escaping closer can match that stack and capacity is safe.
// Counterexample checks the hoped-for bound on fragment re-execution.
const open='([{', close=')]}';
function scan(text, incoming=[]) {
  const stack=[...incoming], escaped=new Set(), pairs=[];
  let peak=stack.length;
  for(let i=0;i<text.length;i++) {
    const c=text[i];
    if(open.includes(c)) { stack.push({type:c,position:i}); peak=Math.max(peak,stack.length); }
    else if(close.includes(c)) {
      const wanted=open[close.indexOf(c)];
      const j=stack.findLastIndex(x=>x.type===wanted);
      if(j<0) escaped.add(wanted);
      else {pairs.push([stack[j].position,i]);stack.length=j;}
    }
  }
  return {stack,escaped,peak,pairs};
}
function tree(text,start=0) {
  const result={text,start,summary:scan(text)};
  if(text.length>1) {
    const mid=text.length>>>1;
    result.left=tree(text.slice(0,mid),start);
    result.right=tree(text.slice(mid),start+mid);
  }
  return result;
}
function execute(root,incoming,counters) {
  counters.nodes++;
  const s=root.summary;
  if(!incoming.some(x=>s.escaped.has(x.type)) && incoming.length+s.peak<=63) {
    counters.reused++;
    return incoming.concat(s.stack.map(x=>({...x,position:x.position+root.start})));
  }
  if(root.left) return execute(root.right,execute(root.left,incoming,counters),counters);
  counters.leaves++;
  // Keep absolute identities in the single-character slow path.
  const c=root.text, stack=[...incoming];
  if(open.includes(c)) stack.push({type:c,position:root.start});
  else {
    const j=stack.findLastIndex(x=>x.type===open[close.indexOf(c)]);
    if(j>=0) stack.length=j;
  }
  return stack;
}
const results=[];
for(const repetitions of [16,64,256,1024,4096]) {
  const text='[)(]'.repeat(repetitions), incoming=[{type:'(',position:-1}];
  const oracle=scan(text,incoming);
  const counters={nodes:0,reused:0,leaves:0};
  const actual=execute(tree(text),incoming,counters);
  assert.deepEqual(actual,oracle.stack);
  assert.equal(actual.length,1);
  assert.equal(actual[0].position,text.length-2);
  assert.equal(counters.leaves,repetitions);
  results.push({characters:text.length,...counters});
}
console.log(JSON.stringify({
  hypothesis:'Escaping-closer/capacity guards bound repair by the initial stack depth.',
  counterexample:'[)(] repeated, entered with one unmatched opening parenthesis.',
  initialStackDepth:1, results,
  verdict:'False: unmatched context alternates between executions and does not permanently converge. Repair remains linear in this fixture.',
  limitation:'This disproves this guard-based bound only, not every possible incremental bracket algorithm.'
},null,2));
