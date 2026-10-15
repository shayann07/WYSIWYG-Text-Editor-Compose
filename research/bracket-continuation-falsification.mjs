import assert from 'node:assert/strict';

// Research hypothesis, not a Unicode implementation. Only three ASCII bracket
// types, no maximum-depth rule, no directional resolution. Absolute identities
// are retained. There is intentionally no uncharged suffix cache/preprocessing.
const openings = '([{', closings = ')]}';
const typeOfOpen = c => openings.indexOf(c);
const typeOfClose = c => closings.indexOf(c);
const empty = () => ({stack: [], first: [Infinity, Infinity, Infinity]});
const newCounts = () => ({rangeVisits: 0, combines: 0, contextJumps: 0});

function reference(text, incoming = [], start = 0) {
  const result = {stack: incoming.map(x => ({...x})), first: [Infinity, Infinity, Infinity]};
  for (let p = start; p < text.length; p++) {
    const a = typeOfOpen(text[p]), b = typeOfClose(text[p]);
    if (a >= 0) result.stack.push({type: a, position: p});
    else if (b >= 0) {
      const j = result.stack.findLastIndex(x => x.type === b);
      if (j >= 0) result.stack.length = j;
      else result.first[b] = Math.min(result.first[b], p);
    }
  }
  return result;
}

class ContinuationTree {
  constructor(text) {
    this.text = [...text]; this.counts = newCounts();
    const build = (lo, hi) => {
      const n = {lo, hi};
      if (hi - lo === 1) n.summary = this.leaf(lo);
      else {
        const mid = (lo + hi) >>> 1;
        n.left = build(lo, mid); n.right = build(mid, hi);
        n.summary = this.combine(n.left.summary, n.right, n.right.lo);
      }
      return n;
    };
    this.root = build(0, text.length);
  }
  leaf(i) {
    const s = empty(), a = typeOfOpen(this.text[i]), b = typeOfClose(this.text[i]);
    if (a >= 0) s.stack.push({type: a, position: i});
    else if (b >= 0) s.first[b] = i;
    return s;
  }
  suffix(node, start) {
    this.counts.rangeVisits++;
    if (start >= node.hi) return empty();
    if (start <= node.lo) return node.summary;
    if (start >= node.right.lo) return this.suffix(node.right, start);
    return this.combine(this.suffix(node.left, start), node.right, node.right.lo);
  }
  // Given an incoming stack, only an escaping closer can consume an incoming
  // opener. At such a closer all internally pushed openers are discarded.
  // Continue after that closer with the remaining incoming prefix.
  // Each invocation jumps at most incoming.length times. The question is the
  // total cost of recursively constructing the suffix summaries it requests.
  execute(node, start, incoming) {
    const stack = incoming.map(x => ({...x})), first = [Infinity, Infinity, Infinity];
    while (start < node.hi) {
      const s = this.suffix(node, start);
      let p = Infinity, type = -1;
      for (const x of stack) if (s.first[x.type] < p) { p = s.first[x.type]; type = x.type; }
      for (let t = 0; t < 3; t++) if (s.first[t] < p) first[t] = Math.min(first[t], s.first[t]);
      if (p === Infinity) return {stack: stack.concat(s.stack), first};
      this.counts.contextJumps++;
      const j = stack.findLastIndex(x => x.type === type);
      assert(j >= 0);
      stack.length = j;
      start = p + 1;
    }
    return {stack, first};
  }
  combine(a, right, start) {
    this.counts.combines++;
    const b = this.execute(right, start, a.stack);
    return {stack: b.stack, first: a.first.map((p, t) => Math.min(p, b.first[t]))};
  }
  substitute(position, c) {
    this.text[position] = c;
    const walk = node => {
      if (node.hi - node.lo === 1) { node.summary = this.leaf(position); return; }
      walk(position < node.right.lo ? node.left : node.right);
      node.summary = this.combine(node.left.summary, node.right, node.right.lo);
    };
    walk(this.root);
  }
  resetCounts() { this.counts = newCounts(); }
}

let seed = 0xD36BA915;
function random(n) { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) % n; }
const alphabet = '([{}])x';
let checks = 0;
for (let trial = 0; trial < 300; trial++) {
  let text = Array.from({length: 1 + random(90)}, () => alphabet[random(alphabet.length)]).join('');
  const tree = new ContinuationTree(text);
  assert.deepEqual(tree.root.summary, reference(text)); checks++;
  for (let step = 0; step < 20; step++) {
    const pos = random(text.length), c = alphabet[random(alphabet.length)];
    text = text.slice(0, pos) + c + text.slice(pos + 1); tree.substitute(pos, c);
    assert.deepEqual(tree.root.summary, reference(text)); checks++;
    const incoming = Array.from({length: random(8)}, (_, i) => ({type: random(3), position: -1-i}));
    const start = random(text.length + 1);
    assert.deepEqual(tree.execute(tree.root, start, incoming), reference(text, incoming, start)); checks++;
  }
}

const results = [];
for (const repetitions of [16, 64, 256, 1024, 4096]) {
  const text = '[)(]'.repeat(repetitions), tree = new ContinuationTree(text);
  const build = {...tree.counts};
  tree.resetCounts();
  const incoming = [{type: 0, position: -1}];
  const actual = tree.execute(tree.root, 0, incoming);
  assert.deepEqual(actual, reference(text, incoming));
  const query = {...tree.counts};
  tree.resetCounts();
  tree.substitute(0, '(');
  assert.deepEqual(tree.root.summary, reference('(' + text.slice(1)));
  const mutation = {...tree.counts};
  results.push({characters: text.length, build, query, mutation});
}
assert(results.at(-1).query.rangeVisits > results[0].query.rangeVisits * 128);
console.log(JSON.stringify({checks, results,
  conclusion: 'Bounded external-context jumps do not bound total work: recursive suffix-summary construction hides linear traversal.',
  limitation: 'Restricted bracket scanner only; no UAX #9 depth limit, bidi resolution, layout, or native IME.'
}, null, 2));
