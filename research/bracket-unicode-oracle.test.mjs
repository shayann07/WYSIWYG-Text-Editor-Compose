import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { BlockIndex, referencePairs } from './bracket-block-index.mjs';

let seed = 0x2473ac9;
const random = n => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; };
const fixtures = [];
function exhaustive(text, left) {
  fixtures.push(text);
  if (left) for (const ch of '()[]') exhaustive(text + ch, left - 1);
}
exhaustive('', 5);
const alphabet = '()[]{}x';
for (let i = 0; i < 600; i++) fixtures.push(Array.from({ length: random(220) }, () => alphabet[random(7)]).join(''));
fixtures.push('()' + '('.repeat(64), '('.repeat(64), '('.repeat(63) + ')'.repeat(63), '[)(]'.repeat(200));
const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java.exe` : 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/java.exe';
const classes = fileURLToPath(new URL('./unicode-oracle/classes', import.meta.url));
const run = spawnSync(java, ['-cp', classes, 'PairOracle'], { input: fixtures.join('\n') + '\n', encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
assert.equal(run.status, 0, run.stderr || String(run.error));
const outputs = run.stdout.trimEnd().split(/\r?\n/).map(line => JSON.parse(line));
assert.equal(outputs.length, fixtures.length);
let fixtureChecks = 0;
let positionChecks = 0;
const overflowDifferences = [];
for (let i = 0; i < fixtures.length; i++) {
  const text = fixtures[i];
  const current = referencePairs(text);
  const index = new BlockIndex(text, 16);
  const oldPairs = outputs[i];
  if (current.abortedAt !== null) {
    for (let p = 0; p < text.length; p++) {
      const found = index.pairAt(p);
      assert.equal(found.pair, null);
      assert.equal(found.abortedAt, current.abortedAt);
      positionChecks++;
    }
    if (oldPairs.length) overflowDifferences.push({ fixture: i, length: text.length, overflowAt: current.abortedAt, oldPairs, currentPairs: [] });
    continue;
  }
  const expected = new Map();
  for (const pair of oldPairs) { expected.set(pair[0], pair); expected.set(pair[1], pair); }
  for (let p = 0; p < text.length; p++) {
    assert.deepEqual(index.pairAt(p).pair, expected.get(p) ?? null, JSON.stringify({ text, p }));
    positionChecks++;
  }
  fixtureChecks++;
}
assert.equal(overflowDifferences.length, 1);
assert.deepEqual(overflowDifferences[0].oldPairs, [[0, 1]]);
console.log(JSON.stringify({ oracle: 'Unicode-hosted BidiPBAReference.java, 2017, ReadMe declares Unicode 8.0. Unmodified upstream sources.', fixtureChecks, positionChecks, overflowDifferences, scope: 'ASCII pair locations only. Does not validate modern full UBA or N0, Unicode data, shaping, or editing integration.' }, null, 2));
