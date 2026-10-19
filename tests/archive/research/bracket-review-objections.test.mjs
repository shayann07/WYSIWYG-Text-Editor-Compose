import assert from 'node:assert/strict';
import { directScan } from './bracket-suffix-index.mjs';
import { BidiBlockContext, directDirections } from './bidi-block-context.mjs';

// Test the review's particular alternating-closer example, not a theorem about
// all possible compact summaries. There are no newly pushed local entries.
let stabilizationChecks = 0;
for (let cap = 1; cap <= 6; cap++) {
  function visit(stack) {
    const prefix = directScan(')]'.repeat(cap), stack, 0, cap);
    const longer = directScan(')]'.repeat(cap + 100), stack, 0, cap);
    assert.equal(prefix.abortedAt, null);
    assert.equal(longer.abortedAt, null);
    assert.deepEqual(prefix.stack, longer.stack);
    assert.deepEqual(prefix.externalPairs, longer.externalPairs);
    stabilizationChecks++;
    if (stack.length < cap) for (let type = 0; type < 3; type++) visit([...stack, { type, id: -stack.length - 1 }]);
  }
  visit([]);
}
const gap = '.'.repeat(100000);
const cases = [
  { name: 'late overflow invalidates early pair', before: 'R(R)' + gap + 'L', at: 100005, remove: 0, insert: '('.repeat(64), end: 4, expectedBefore: [1,1,1,1], expectedAfter: [1,1,1,0] },
  { name: 'distant interior strong resolves early opener', before: 'R(' + gap + 'L)', at: 100002, remove: 1, insert: 'R', end: 2, expectedBefore: [1,0], expectedAfter: [1,1] },
];
const results = [];
for (const fixture of cases) {
  const index = new BidiBlockContext(fixture.before, 256);
  const before = index.directionsIn(0, fixture.end);
  assert.deepEqual(before.directions, fixture.expectedBefore);
  assert.deepEqual(before.directions, directDirections(fixture.before).slice(0, fixture.end));
  const cost = index.replace(fixture.at, fixture.at + fixture.remove, fixture.insert);
  const afterText = fixture.before.slice(0, fixture.at) + fixture.insert + fixture.before.slice(fixture.at + fixture.remove);
  const after = index.directionsIn(0, fixture.end);
  assert.deepEqual(after.directions, fixture.expectedAfter);
  assert.deepEqual(after.directions, directDirections(afterText).slice(0, fixture.end));
  assert.equal(after.blockVisits, index.index.blocks.length);
  results.push({ name: fixture.name, beforeLength: fixture.before.length, editAt: fixture.at, cost, before, after });
}
console.log(JSON.stringify({ stabilizationChecks, results, conclusion: 'Confirms global semantic dependencies and this implementation\'s block traversal. Refutes the specific claim that alternating closers require arbitrarily many reachable restarts at fixed incoming depth. Proves neither a general compact summary nor a query-time scan lower bound.' }, null, 2));
