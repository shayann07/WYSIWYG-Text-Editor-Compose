import { BlockIndex, referencePairs } from './bracket-block-index.mjs';
import { opens, closes } from './bracket-suffix-index.mjs';

// Input is CLASSIFIED text: L/R are strong-type tokens, dot is neutral, and
// ()[]{} are paired brackets. One isolating run at a fixed embedding parity.
// This deliberately does not classify arbitrary Unicode or implement W1-W7.
const ID = 2;
const NONE = 3;
const strong = c => c === 'L' ? 0 : c === 'R' ? 1 : -1;
const literal = [Object.freeze({ value: 0 }), Object.freeze({ value: 1 })];
const apply = (f, ref) => f === ID ? ref : literal[f];
const compose = (later, earlier) => later === ID ? earlier : later;
function value(ref) {
  let p = ref;
  while (p.value === undefined) {
    if (!p.link) throw new Error('unresolved context escaped the query');
    p = p.link;
  }
  while (ref !== p && ref.value === undefined) {
    const next = ref.link;
    ref.link = p;
    ref = next;
  }
  return p.value;
}

class ContextTable {
  constructor(block) {
    this.block = block;
    this.n = block.text.length;
    this.firstStrong = -1;
    this.lastStrong = -1;
    this.prefix = [new Int32Array(this.n + 1), new Int32Array(this.n + 1)];
    for (let i = 0; i < this.n; i++) {
      const s = strong(block.text[i]);
      if (s >= 0) { if (this.firstStrong < 0) this.firstStrong = s; this.lastStrong = s; }
      for (let e = 0; e < 2; e++) this.prefix[e][i + 1] = this.prefix[e][i] + (s === e ? 1 : 0);
    }
    const jump = new Int32Array(this.n + 1).fill(this.n);
    const first = [new Uint8Array(this.n + 1).fill(NONE), new Uint8Array(this.n + 1).fill(NONE)];
    const out = [new Uint8Array(this.n + 1).fill(ID), new Uint8Array(this.n + 1).fill(ID)];
    for (let i = 0; i < this.n; i++) {
      const o = opens.indexOf(block.text[i]);
      const s = strong(block.text[i]);
      const close = o < 0 ? this.n : block.index.j[o][i + 1];
      jump[i] = close < this.n ? close + 1 : i + 1;
      for (let e = 0; e < 2; e++) {
        if (s >= 0) { first[e][i] = s; out[e][i] = s; }
        else if (close < this.n) {
          const hasE = this.count(e, i + 1, close) > 0;
          const hasO = this.count(1 - e, i + 1, close) > 0;
          first[e][i] = hasE ? e : hasO ? ID : NONE;
          out[e][i] = hasE ? e : ID;
        }
      }
    }
    this.jump = [jump];
    this.first = [first];
    this.out = [out];
    for (let level = 1; 2 ** level <= this.n; level++) {
      const previousJump = this.jump[level - 1];
      const nextJump = new Int32Array(this.n + 1);
      const nextFirst = [new Uint8Array(this.n + 1), new Uint8Array(this.n + 1)];
      const nextOut = [new Uint8Array(this.n + 1), new Uint8Array(this.n + 1)];
      for (let i = 0; i <= this.n; i++) {
        const m = previousJump[i];
        nextJump[i] = previousJump[m];
        for (let e = 0; e < 2; e++) {
          const f = this.first[level - 1][e];
          const c = this.out[level - 1][e];
          nextFirst[e][i] = f[i] !== NONE ? f[i] : f[m] === NONE ? NONE : compose(f[m], c[i]);
          nextOut[e][i] = compose(c[m], c[i]);
        }
      }
      this.jump.push(nextJump);
      this.first.push(nextFirst);
      this.out.push(nextOut);
    }
  }
  count(e, start, end) { return this.prefix[e][end] - this.prefix[e][start]; }
  range(start, end, e, counters) {
    let at = start;
    let first = NONE;
    let out = ID;
    while (at < end) {
      for (let level = this.jump.length - 1; level >= 0 && at < end; level--) {
        counters.rangeProbes++;
        if (this.jump[level][at] > end) continue;
        const f = this.first[level][e][at];
        if (first === NONE && f !== NONE) first = compose(f, out);
        out = compose(this.out[level][e][at], out);
        at = this.jump[level][at];
      }
      if (at < end) {
        // The full-suffix match is beyond this fragment's end. Its opener is
        // unmatched within the fragment; descend into its contents neutrally.
        at++;
      }
    }
    return { first, out };
  }
  get allocatedBytes() {
    return [...this.prefix, ...this.jump, ...this.first.flat(), ...this.out.flat()].reduce((sum, a) => sum + a.byteLength, 0);
  }
}

export class BidiBlockContext {
  constructor(text, blockSize = 64) {
    this.validate(text);
    this.index = new BlockIndex(text, blockSize);
    this.refresh();
  }
  validate(text) { if (!/^[LR.()[\]{}]*$/.test(text)) throw new Error('only classified L/R/neutral/bracket tokens supported'); }
  refresh() {
    let constructedCharacters = 0;
    for (const block of this.index.blocks) if (!block.context) {
      block.context = new ContextTable(block);
      constructedCharacters += block.text.length;
    }
    return constructedCharacters;
  }
  replace(start, end, text) {
    this.validate(text);
    const cost = this.index.replace(start, end, text);
    return { ...cost, contextCharacters: this.refresh() };
  }
  directionAt(position, embedding = 0) {
    const { directions, ...rest } = this.directionsIn(position, position + 1, embedding);
    return { direction: directions[0], ...rest };
  }
  directionsIn(start, end, embedding = 0) {
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || start >= end || end > this.index.length) throw new Error('invalid nonempty query window');
    if (embedding !== 0 && embedding !== 1) throw new Error('embedding parity required');
    const e = embedding;
    const counters = { blockVisits: 0, fallbackBlockVisits: 0, scannedCharacters: 0, tableProbes: 0, rangeProbes: 0, contextCells: 0 };
    let offset = 0;
    let stack = [];
    let current = literal[e];
    const counts = [0, 0];
    let left = null;
    const targets = new Array(end - start).fill(null);
    let afterTarget = false;
    let nextDefinite = false;
    const nextCandidates = [];
    const emit = cell => {
      if (afterTarget && !nextDefinite) {
        nextCandidates.push(cell);
        if (cell.active === true) nextDefinite = true;
      }
    };
    const create = (type, id, before, atCounts) => {
      const cell = { before, active: undefined, link: null };
      counters.contextCells++;
      return { type, id, cell, counts: [...atCounts] };
    };
    const closeAt = j => {
      const entry = stack[j];
      for (let k = stack.length - 1; k > j; k--) {
        stack[k].cell.active = false;
        stack[k].cell.link = stack[k].cell.before;
      }
      const hasE = counts[e] > entry.counts[e];
      const hasO = counts[1 - e] > entry.counts[1 - e];
      entry.cell.active = hasE || hasO;
      entry.cell.link = hasE ? literal[e] : entry.cell.before;
      stack.length = j;
      current = entry.cell;
      return entry.cell;
    };
    const overflow = () => ({ directions: this.rawDirectionsIn(start, end, e, counters), overflow: true, ...counters });
    for (const block of this.index.blocks) {
      counters.blockVisits++;
      const table = block.context;
      if (offset < end && start < offset + block.text.length) {
        for (let p = 0; p < block.text.length; p++) {
          counters.scannedCharacters++;
          const id = offset + p;
          if (id === start) left = current;
          const inside = start <= id && id < end;
          const s = strong(block.text[p]);
          const o = opens.indexOf(block.text[p]);
          const c = closes.indexOf(block.text[p]);
          if (s >= 0) {
            current = literal[s];
            counts[s]++;
            if (inside) targets[id - start] = s;
            else emit({ active: true, link: current });
          } else if (o >= 0) {
            if (stack.length === 63) return overflow();
            const entry = create(o, id, current, counts);
            stack.push(entry);
            current = entry.cell;
            if (inside) targets[id - start] = entry.cell;
            else emit(entry.cell);
          } else if (c >= 0) {
            const j = stack.findLastIndex(x => x.type === c);
            if (j >= 0) {
              const cell = closeAt(j);
              if (inside) targets[id - start] = cell;
              else if (cell.active) emit(cell);
            }
          }
          if (id === end - 1) afterTarget = true;
        }
      } else {
        // Until an incoming opener is consumed, the internal evolution equals
        // an empty scan above that incoming stack. The summaries can therefore
        // skip whole regions, preserving symbolic context dependencies.
        let at = 0;
        const baseCounts = [...counts];
        while (true) {
          let match = block.text.length;
          let slot = -1;
          for (let j = 0; j < stack.length; j++) {
            counters.tableProbes++;
            const p = block.index.j[stack[j].type][at];
            if (p <= match) { match = p; slot = j; }
          }
          counters.tableProbes++;
          if (block.index.height(64 - stack.length, at) < match) return overflow();
          if (match === block.text.length) break;
          const summary = table.range(at, match, e, counters);
          if (summary.first !== NONE) emit({ active: true, link: apply(summary.first, current) });
          for (let d = 0; d < 2; d++) counts[d] = baseCounts[d] + table.prefix[d][match];
          const cell = closeAt(slot);
          if (cell.active) emit(cell);
          at = match + 1;
        }
        for (let p = block.index.finalHead[at]; p !== -1; p = block.index.tail[p]) {
          const summary = table.range(at, p, e, counters);
          if (summary.first !== NONE) emit({ active: true, link: apply(summary.first, current) });
          current = apply(summary.out, current);
          const localCounts = [0, 1].map(d => baseCounts[d] + table.prefix[d][p]);
          const entry = create(opens.indexOf(block.text[p]), offset + p, current, localCounts);
          stack.push(entry);
          current = entry.cell;
          emit(entry.cell);
          at = p + 1;
        }
        const summary = table.range(at, block.text.length, e, counters);
        if (summary.first !== NONE) emit({ active: true, link: apply(summary.first, current) });
        current = apply(summary.out, current);
        for (let d = 0; d < 2; d++) counts[d] = baseCounts[d] + table.prefix[d][block.text.length];
      }
      offset += block.text.length;
    }
    if (!afterTarget) throw new Error('query outside text');
    for (const entry of stack) { entry.cell.active = false; entry.cell.link = entry.cell.before; }
    const right = nextCandidates.find(x => x.active === true);
    const types = targets.map(x => typeof x === 'number' ? x : x?.active ? value(x) : -1);
    this.resolveNeutrals(types, value(left), right ? value(right) : e, e);
    return { directions: types, overflow: false, ...counters };
  }
  resolveNeutrals(types, left, right, e) {
    let before = left;
    for (let i = 0; i < types.length;) {
      if (types[i] >= 0) { before = types[i++]; continue; }
      const start = i;
      while (i < types.length && types[i] < 0) i++;
      const after = i < types.length ? types[i] : right;
      for (let p = start; p < i; p++) types[p] = before === after ? before : e;
    }
  }
  rawDirectionsIn(start, end, embedding, counters) {
    let before = embedding;
    let after = embedding;
    const types = new Array(end - start).fill(-1);
    let offset = 0;
    let foundAfter = false;
    for (const block of this.index.blocks) {
      counters.fallbackBlockVisits++;
      if (offset + block.text.length <= start) {
        if (block.context.lastStrong >= 0) before = block.context.lastStrong;
      } else if (offset < end) {
        const a = Math.max(start - offset, 0);
        const b = Math.min(end - offset, block.text.length);
        for (let p = a; p < b; p++) { counters.scannedCharacters++; types[offset + p - start] = strong(block.text[p]); }
        if (offset <= start) for (let p = a - 1; p >= 0; p--) { counters.scannedCharacters++; if (strong(block.text[p]) >= 0) { before = strong(block.text[p]); break; } }
        if (offset + block.text.length >= end) for (let p = b; p < block.text.length; p++) { counters.scannedCharacters++; if (strong(block.text[p]) >= 0) { after = strong(block.text[p]); foundAfter = true; break; } }
      } else if (!foundAfter) {
        if (block.context.firstStrong >= 0) { after = block.context.firstStrong; foundAfter = true; }
      }
      offset += block.text.length;
    }
    this.resolveNeutrals(types, before, after, embedding);
    return types;
  }
  get allocatedBytes() { return this.index.allocatedBytes + this.index.blocks.reduce((n, b) => n + b.context.allocatedBytes, 0); }
}

export function directDirections(text, embedding = 0) {
  const types = [...text].map(strong);
  const pairResult = referencePairs(text);
  const pairs = [...pairResult.pairs.entries()].filter(([key, pair]) => key === pair[0]).map(([, pair]) => pair).sort((a, b) => a[0] - b[0]);
  for (const [open, close] of pairs) {
    let hasE = false;
    let hasO = false;
    for (let i = open + 1; i < close; i++) {
      if (types[i] === embedding) hasE = true;
      if (types[i] === 1 - embedding) hasO = true;
    }
    if (hasE) types[open] = types[close] = embedding;
    else if (hasO) {
      let previous = embedding;
      for (let i = open - 1; i >= 0; i--) if (types[i] >= 0) { previous = types[i]; break; }
      types[open] = types[close] = previous;
    }
  }
  for (let i = 0; i < types.length;) {
    if (types[i] >= 0) { i++; continue; }
    const start = i;
    while (i < types.length && types[i] < 0) i++;
    const before = start ? types[start - 1] : embedding;
    const after = i < types.length ? types[i] : embedding;
    for (let p = start; p < i; p++) types[p] = before === after ? before : embedding;
  }
  return types;
}
