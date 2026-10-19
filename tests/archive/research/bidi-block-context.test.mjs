import assert from 'node:assert/strict';
import { BidiBlockContext, directDirections } from './bidi-block-context.mjs';
let seed = 0x277ef01;
const random = n => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; };
let checks = 0;
let edits = 0;
let windowChecks = 0;
function check(index, text) {
  assert.equal(index.index.text(), text);
  for (const e of [0, 1]) {
    const expected = directDirections(text, e);
    for (let p = 0; p < text.length; p++) {
      const actual = index.directionAt(p, e);
      assert.equal(actual.direction, expected[p], JSON.stringify({ text, e, p, actual, expected }));
      checks++;
    }
    if (text.length > 0) {
      const a = random(text.length);
      const b = a + 1 + random(text.length - a);
      assert.deepEqual(index.directionsIn(a, b, e).directions, expected.slice(a, b), JSON.stringify({ text, a, b, e }));
      assert.deepEqual(index.directionsIn(0, text.length, e).directions, expected);
      windowChecks += 2;
    }
  }
}
const fixtures = ['L(R)R', 'R(L)L', '(R)', '(L)', 'L[)(R].', 'L.(R)', 'R.(L)', 'L(.[R].)R', '([)]', 'L(R)' + '('.repeat(64)];
for (const text of fixtures) for (const size of [2, 3, 4, 8, 32]) check(new BidiBlockContext(text, size), text);
const alphabet = 'LR.()[]{}';
const word = n => Array.from({ length: n }, () => alphabet[random(alphabet.length)]).join('');
for (let trial = 0; trial < 100; trial++) {
  let text = word(random(60) + 1);
  const index = new BidiBlockContext(text, [2, 3, 4, 8, 16][random(5)]);
  check(index, text);
  for (let step = 0; step < 30; step++) {
    const a = random(text.length + 1);
    const b = a + random(Math.min(8, text.length - a) + 1);
    const insertion = word(random(6));
    index.replace(a, b, insertion);
    text = text.slice(0, a) + insertion + text.slice(b);
    check(index, text);
    edits++;
  }
}
console.log(JSON.stringify({ checks, windowChecks, edits, scope: 'Classified L/R/neutral plus ASCII brackets, single isolating run, N0-N2 directions only. Direct model oracle.' }, null, 2));
