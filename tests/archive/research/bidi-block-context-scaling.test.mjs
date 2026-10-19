import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import bidiFactory from './runtime/node_modules/bidi-js/dist/bidi.mjs';
import { BidiBlockContext, directDirections } from './bidi-block-context.mjs';

const bidi = bidiFactory();
const nativeText = s => s.replaceAll('L', 'a').replaceAll('R', '\u05d0');
const time = f => { const t = performance.now(); const result = f(); return { result, ms: performance.now() - t }; };
const stats = samples => {
  const sorted = [...samples].sort((a, b) => a - b);
  return { median: sorted[Math.floor(sorted.length / 2)], p95: sorted[Math.ceil(sorted.length * 0.95) - 1], samples: sorted.length };
};
const withoutDirections = ({ directions, ...cost }) => cost;
let checks = 0;
function queries(index, text, expected = directDirections(text)) {
  return [0, Math.floor(text.length / 2), Math.max(0, text.length - 48)].map(start => {
    const end = Math.min(text.length, start + 48);
    const actual = index.directionsIn(start, end);
    assert.deepEqual(actual.directions, expected.slice(start, end));
    checks += end - start;
    return { start, end, ...withoutDirections(actual) };
  });
}
const rows = [];
for (const groups of [256, 1024, 4096, 16384, 65536]) {
  const suffix = '(R)'.repeat(groups);
  const original = 'L' + suffix;
  const changed = 'R' + suffix;
  const blockSize = Math.ceil(Math.sqrt(original.length));
  const built = time(() => new BidiBlockContext(original, blockSize));
  const index = built.result;
  const before = directDirections(original);
  const after = directDirections(changed);
  const changedDirections = after.reduce((n, x, i) => n + (x !== before[i]), 0);
  assert.equal(changedDirections, 2 * groups + 1);
  const edit = index.replace(0, 1, 'R');
  const queryCosts = queries(index, changed, after);
  const variants = [nativeText(original), nativeText(changed)];
  for (let warmup = 0; warmup < 5; warmup++) {
    index.replace(0, 1, warmup % 2 ? 'R' : 'L');
    index.directionsIn(original.length - 48, original.length);
    bidi.getEmbeddingLevels(variants[warmup % 2], 'ltr');
  }
  const candidateTimes = [];
  const baselineTimes = [];
  const trials = 21;
  for (let i = 0; i < trials; i++) {
    const variant = i % 2;
    const candidate = time(() => {
      index.replace(0, 1, variant ? 'R' : 'L');
      return index.directionsIn(original.length - 48, original.length).directions;
    });
    const baseline = time(() => bidi.getEmbeddingLevels(variants[variant], 'ltr').levels.slice(original.length - 48));
    assert.deepEqual(candidate.result, Array.from(baseline.result, x => x & 1));
    candidateTimes.push(candidate.ms);
    baselineTimes.push(baseline.ms);
    checks += 48;
  }
  rows.push({ length: original.length, blockSize, changedDirections, buildMs: built.ms,
    allocatedTypedBytes: index.allocatedBytes, edit, queryCosts,
    editAndLastWindowMs: stats(candidateTimes), bidiJsWholeParagraphAndSliceMs: stats(baselineTimes) });
}

const adversaries = [];
for (const text of [
  'L' + '[)(R]'.repeat(4096),
  'L' + ('('.repeat(63) + 'R' + ')'.repeat(63) + '.').repeat(128),
  'L' + '(R)'.repeat(8192) + '('.repeat(64),
]) {
  const blockSize = Math.ceil(Math.sqrt(text.length));
  const index = new BidiBlockContext(text, blockSize);
  let current = text;
  const operations = [];
  const perform = (at, count, inserted) => {
    const cost = index.replace(at, at + count, inserted);
    current = current.slice(0, at) + inserted + current.slice(at + count);
    operations.push({ at, count, inserted, cost, windows: queries(index, current) });
  };
  perform(0, 0, ']');
  perform(Math.floor(current.length / 2), 1, '([R]');
  perform(current.length - 1, 1, '');
  adversaries.push({ initialLength: text.length, blockSize, allocatedTypedBytes: index.allocatedBytes, operations });
}
console.log(JSON.stringify({ checks, rows, adversaries,
  timingLimits: 'Single Windows Node process, 5 warmups and 21 samples per size; includes candidate replacement and window query, baseline full UBA and slice. Native variants prebuilt. Candidate accepts classified tokens and has an existing index, baseline classifies full Unicode text. Build time and typed-array memory reported separately. This is not an editor/device benchmark or a comparison of equivalent complete Unicode capabilities.' }, null, 2));
