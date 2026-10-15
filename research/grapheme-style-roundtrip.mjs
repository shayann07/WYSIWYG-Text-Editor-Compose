import assert from 'node:assert/strict';

// Explicit baseline: plan-v1's destructive first-scalar normalization rule.
// This is not a claim that a released Compose widget uses that rule.
const segmenter = new Intl.Segmenter('und', { granularity: 'grapheme' });
const clone = x => structuredClone(x);
const textOf = x => x.map(a => a.text).join('');
function clusters(atoms) {
  const text = textOf(atoms);
  const result = [];
  let scalar = 0;
  for (const { segment } of segmenter.segment(text)) {
    const count = [...segment].length;
    result.push([scalar, scalar + count]);
    scalar += count;
  }
  assert.equal(scalar, atoms.length);
  return result;
}
function effective(atoms) {
  const out = [];
  for (const [lo, hi] of clusters(atoms)) {
    out.push({ text: textOf(atoms.slice(lo, hi)), style: atoms[lo].style });
  }
  return out;
}
function destructiveNormalize(atoms) {
  const out = clone(atoms);
  for (const [lo, hi] of clusters(atoms)) {
    for (let i = lo; i < hi; i++) out[i].style = out[lo].style;
  }
  return out;
}
const original = [
  { id: 'woman', text: '\u{1F469}', style: 'bold' },
  { id: 'laptop', text: '\u{1F4BB}', style: 'italic' },
];
const joined = [original[0], {id: 'joiner', text: '\u200d', style: 'bold'}, original[1]];
assert.equal(clusters(joined).length, 1);
const destructiveAfterJoin = destructiveNormalize(joined);
const destructiveAfterDelete = destructiveNormalize(destructiveAfterJoin.filter(x => x.id !== 'joiner'));
const retainedAfterDelete = joined.filter(x => x.id !== 'joiner');
assert.deepEqual(retainedAfterDelete, original);
assert.notDeepEqual(destructiveAfterDelete, original);
assert.deepEqual(effective(joined), effective(destructiveAfterJoin));
assert.equal(destructiveAfterDelete[1].style, 'bold');
assert.equal(retainedAfterDelete[1].style, 'italic');

let cases = 0;
for (const left of ['plain', 'bold', 'italic', 'bold-italic']) {
  for (const right of ['plain', 'bold', 'italic', 'bold-italic']) {
    const input = [{...original[0], style:left}, {...original[1], style:right}];
    const during = [input[0], {...joined[1], style:left}, input[1]];
    assert.deepEqual(during.filter(x => x.id !== 'joiner'), input);
    assert.deepEqual(effective(during), effective(destructiveNormalize(during)));
    cases++;
  }
}
console.log(JSON.stringify({
  runtime: process.version,
  unicode: process.versions.unicode,
  baseline: 'plan-v1 destructive first-scalar normalization',
  baselineFailure: {before:original, after:destructiveAfterDelete},
  retainedAttributesResult: retainedAfterDelete,
  roundTripCases: cases,
  verdict: 'Behavioral improvement over this baseline; novelty not established.',
}, null, 2));
