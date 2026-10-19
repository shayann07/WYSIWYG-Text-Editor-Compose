import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import { SuffixIndex, directScan, opens } from './bracket-suffix-index.mjs';

let seed = 0x621abd3;
const random = n => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; };
const alphabet = '()[]{}x';
let checks = 0;
let summaryChecks = 0;
const observation = r => ({ abortedAt: r.abortedAt, stack: r.stack, externalPairs: r.externalPairs });
function compare(index, incoming, start) {
  const actual = index.apply(incoming, start);
  const expected = directScan(index.text, incoming, start, index.cap);
  assert.deepEqual(observation(actual), observation(expected),
    JSON.stringify({ text: index.text.join(''), incoming, start, cap: index.cap }));
  assert.ok(actual.jumps <= incoming.length);
  checks++;
}

function exhaustive(prefix, remaining) {
  const index = new SuffixIndex(prefix, 4);
  for (const incoming of [[], [{ type: 0, id: -1 }], [{ type: 1, id: -1 }, { type: 0, id: -2 }]]) {
    for (let start = 0; start <= prefix.length; start++) compare(index, incoming, start);
  }
  if (remaining > 0) for (const c of '()[]') exhaustive(prefix + c, remaining - 1);
}
exhaustive('', 5);

// Current BD16 discards earlier pairs when a later opener overflows the stack.
const overflowAfterMatch = new SuffixIndex(')' + '('.repeat(64));
const overflowObservation = overflowAfterMatch.apply([{ type: 0, id: -1 }]);
assert.equal(overflowObservation.abortedAt, 64);
assert.deepEqual(overflowObservation.externalPairs, []);
compare(overflowAfterMatch, [{ type: 0, id: -1 }], 0);
assert.throws(() => new SuffixIndex('😀'), /ASCII/);

// Inspect each suffix directly with an UNBOUNDED stack. J and H summarize that
// scan even though apply() enforces a bounded stack. This independently checks
// the table meanings, rather than only the queries that happen to use them.
for (let trial = 0; trial < 100; trial++) {
  const n = random(65);
  const text = Array.from({ length: n }, () => alphabet[random(alphabet.length)]).join('');
  const index = new SuffixIndex(text, 8);
  for (let start = 0; start <= n; start++) {
    const stack = [];
    const escape = [n, n, n];
    const height = new Array(10).fill(n);
    for (let p = start; p < n; p++) {
      const o = opens.indexOf(text[p]);
      const c = ')]}'.indexOf(text[p]);
      if (o >= 0) {
        stack.push({ type: o, id: p });
        if (stack.length < height.length && height[stack.length] === n) height[stack.length] = p;
      } else if (c >= 0) {
        const match = stack.findLastIndex(x => x.type === c);
        if (match < 0) { if (escape[c] === n) escape[c] = p; }
        else stack.length = match;
      }
    }
    for (let t = 0; t < 3; t++) { assert.equal(index.j[t][start], escape[t]); summaryChecks++; }
    for (let k = 1; k < 10; k++) { assert.equal(index.height(k, start), height[k]); summaryChecks++; }
    const actual = [];
    for (let p = index.finalHead[start]; p >= 0; p = index.tail[p]) actual.push({ type: opens.indexOf(text[p]), id: p });
    assert.deepEqual(actual, stack);
    summaryChecks++;
  }
}

for (let trial = 0; trial < 500; trial++) {
  const n = random(160) + 1;
  const text = Array.from({ length: n }, () => alphabet[random(alphabet.length)]).join('');
  const cap = [1, 2, 3, 4, 8, 16, 63][random(7)];
  const index = new SuffixIndex(text, cap);
  for (let step = 0; step < 40; step++) {
    if (step % 4 === 0) index.substitute(random(n), alphabet[random(alphabet.length)]);
    const incoming = Array.from({ length: random(cap + 1) }, (_, i) => ({ type: random(opens.length), id: -i - 1 }));
    compare(index, incoming, random(n + 1));
  }
}

for (const cap of [1, 2, 3, 62, 63]) {
  for (const depth of [0, 1, cap - 1, cap, cap + 1, cap + 2]) {
    const text = '('.repeat(depth) + ')'.repeat(depth) + '[)(]';
    const index = new SuffixIndex(text, cap);
    for (let d = 0; d <= cap; d++) {
      const incoming = Array.from({ length: d }, (_, i) => ({ type: i % 3, id: -i - 1 }));
      for (let start = 0; start <= text.length; start++) compare(index, incoming, start);
    }
  }
}

const adversaries = [];
for (const n of [64, 256, 1024, 4096, 16384, 65536]) {
  const text = '[)(]'.repeat(n / 4);
  const begin = performance.now();
  const index = new SuffixIndex(text);
  const buildMs = performance.now() - begin;
  const incoming = [{ type: 0, id: -1 }];
  const before = index.apply(incoming);
  compare(index, incoming, 0);
  const editRows = [];
  for (const p of [0, n / 2, n - 1]) {
    const old = index.text[p];
    editRows.push({ at: p, rebuiltRows: index.substitute(p, 'x') });
    compare(index, incoming, 0);
    index.substitute(p, old);
  }
  adversaries.push({ n, buildMs, allocatedBytes: index.allocatedBytes, queryProbes: before.probes, queryJumps: before.jumps, baselineCharacters: directScan(text, incoming).inspected, editRows });
}
console.log(JSON.stringify({ checks, summaryChecks, adversaries, scope: 'Typed bracket transfer, ASCII paired brackets, final stack/external pairs/overflow only. Not full UBA, N0, shaping, layout or editor integration. Fixed-length substitution maintenance can be linear.' }, null, 2));
