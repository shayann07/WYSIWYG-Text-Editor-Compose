import assert from 'node:assert/strict';
import { BlockIndex, referencePairs } from './bracket-block-index.mjs';
let seed = 0x716bb45;
const random = n => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; };
const alphabet = '()[]{}x';
const word = n => Array.from({ length: n }, () => alphabet[random(alphabet.length)]).join('');
let pairChecks = 0;
let edits = 0;
function check(index, text, all = true) {
  assert.equal(index.text(), text);
  assert.equal(index.length, text.length);
  for (const block of index.blocks) {
    assert.ok(block.text.length > 0 && block.text.length <= index.blockSize);
    if (index.blocks.length > 1) assert.ok(block.text.length >= Math.floor(index.blockSize / 2));
  }
  const expected = referencePairs(text, index.cap);
  const positions = all ? Array.from({ length: text.length }, (_, i) => i) : [0, Math.floor(text.length / 2), text.length - 1];
  for (const p of positions) {
    if (p < 0 || p >= text.length) continue;
    const actual = index.pairAt(p);
    assert.deepEqual(actual.pair, expected.pairs.get(p) ?? null, JSON.stringify({ text, p, cap: index.cap }));
    assert.equal(actual.abortedAt, expected.abortedAt);
    pairChecks++;
  }
}
for (let trial = 0; trial < 80; trial++) {
  let text = word(random(100));
  const index = new BlockIndex(text, [4, 8, 16, 32][random(4)], [1, 2, 4, 8, 63][random(5)]);
  check(index, text);
  for (let step = 0; step < 80; step++) {
    const a = random(text.length + 1);
    const b = a + random(Math.min(15, text.length - a) + 1);
    const inserted = word(random(10));
    index.replace(a, b, inserted);
    text = text.slice(0, a) + inserted + text.slice(b);
    check(index, text);
    edits++;
  }
}
for (const text of ['()', '(]', '([)]', '[)(]'.repeat(100), '()' + '('.repeat(64), '('.repeat(63) + ')'.repeat(63)]) {
  for (const blockSize of [2, 3, 8, 63]) check(new BlockIndex(text, blockSize), text);
}
// Repeated insertions just before a block's end must not produce a chain of
// tiny remainder blocks and invalidate the advertised block-count bound.
const fragmentation = new BlockIndex('x'.repeat(128), 16);
for (let i = 0; i < 1000; i++) {
  const p = fragmentation.blocks[0].text.length - 1;
  fragmentation.replace(p, p, 'x');
  assert.ok(fragmentation.blocks.length <= fragmentation.length / 8);
  for (const block of fragmentation.blocks) assert.ok(block.text.length >= 8 && block.text.length <= 16);
}
const scaling = [];
for (const n of [1024, 4096, 16384, 65536]) {
  let text = '[)(]'.repeat(n / 4);
  const blockSize = Math.ceil(Math.sqrt(n));
  const index = new BlockIndex(text, blockSize);
  check(index, text, false);
  const mutations = [];
  for (const p of [0, n / 2, n - 1]) {
    const previous = text[p];
    const cost = index.replace(p, p + 1, 'x');
    text = text.slice(0, p) + 'x' + text.slice(p + 1);
    check(index, text, false);
    const query = index.pairAt(Math.min(3, text.length - 1));
    mutations.push({ at: p, ...cost, query });
    index.replace(p, p + 1, previous);
    text = text.slice(0, p) + previous + text.slice(p + 1);
  }
  scaling.push({ n, blockSize, allocatedBytes: index.allocatedBytes, referenceScannedCharacters: referencePairs(text).scannedCharacters, mutations });
}
console.log(JSON.stringify({ pairChecks, edits, fragmentationInsertions: 1000, scaling, scope: 'ASCII BD16 stack/pair-at-position component only; direct reference, not ICU or full Unicode conformance. Depth-adaptive per-block tables, array block container, no N0/IME/layout integration.' }, null, 2));
