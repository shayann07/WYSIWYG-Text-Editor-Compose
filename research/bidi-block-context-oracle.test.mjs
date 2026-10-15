import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { BidiBlockContext } from './bidi-block-context.mjs';
import { referencePairs } from './bracket-block-index.mjs';

let seed = 0x43ce90;
const random = n => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; };
const fixtures = [];
function exhaustive(text, remaining) {
  fixtures.push(text);
  if (remaining) for (const c of 'LR.()') exhaustive(text + c, remaining - 1);
}
exhaustive('', 5);
const alphabet = 'LR.()[]{}';
const word = n => Array.from({ length: n }, () => alphabet[random(alphabet.length)]).join('');
for (let i = 0; i < 800; i++) fixtures.push(word(random(220)));
for (let i = 0; i < 50; i++) {
  let text = word(80);
  for (let step = 0; step < 30; step++) {
    const a = random(text.length + 1);
    const b = a + random(Math.min(8, text.length - a) + 1);
    text = text.slice(0, a) + word(random(8)) + text.slice(b);
    fixtures.push(text);
  }
}
fixtures.push('L' + '(R)'.repeat(200), 'R' + '(R)'.repeat(200), '('.repeat(63) + 'R' + ')'.repeat(63), 'L(R)' + '('.repeat(64));
const requests = fixtures.flatMap(text => [0, 1].map(e => ({ text, e })));
const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java.exe` : 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/java.exe';
const classes = fileURLToPath(new URL('./unicode-oracle/classes', import.meta.url));
const run = spawnSync(java, ['-cp', classes, 'DirectionOracle'], {
  input: requests.map(({ text, e }) => `${e}\t${text}`).join('\n') + '\n',
  encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
});
assert.equal(run.status, 0, run.stderr || String(run.error));
const outputs = run.stdout.trimEnd().split(/\r?\n/).map(line => JSON.parse(line));
assert.equal(outputs.length, requests.length);
let checkedRequests = 0;
let windowChecks = 0;
let directionChecks = 0;
let overflowRequestsExcluded = 0;
for (let i = 0; i < requests.length; i++) {
  const { text, e } = requests[i];
  // Unicode 8's overflow behavior differs from the current BD16 discard rule.
  // Never count a skipped/version-different fixture as oracle agreement.
  if (referencePairs(text).abortedAt !== null) { overflowRequestsExcluded++; continue; }
  const expected = outputs[i].map(x => x & 1);
  for (const size of [2, 8, 31]) {
    const index = new BidiBlockContext(text, size);
    if (text.length) {
      const a = random(text.length);
      const b = a + 1 + random(text.length - a);
      for (const [start, end] of [[0, text.length], [a, b], [a, a + 1]]) {
        const found = index.directionsIn(start, end, e);
        assert.equal(found.overflow, false);
        assert.deepEqual(found.directions, expected.slice(start, end), JSON.stringify({ text, e, size, start, end }));
        windowChecks++;
        directionChecks += end - start;
      }
    }
  }
  checkedRequests++;
}
console.log(JSON.stringify({ oracle: 'Unmodified Unicode-hosted Java reference, Unicode 8.0; classified-token input', checkedRequests, windowChecks, directionChecks, overflowRequestsExcluded, scope: 'L/R/ON, ASCII brackets, one isolating run, paragraph parity 0 and 1. Checks level parity, not all UBA levels or layout.' }, null, 2));
