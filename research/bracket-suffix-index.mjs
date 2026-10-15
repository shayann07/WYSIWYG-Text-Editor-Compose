// Research only. Typed-bracket stack semantics: a closer finds the nearest
// matching opener and discards it and everything above it. Other closers do
// nothing. This is the stack component of BD16, not the complete UBA. An
// overflow invalidates ALL pairs of the containing isolating run sequence;
// callers must also discard pairs preceding the supplied incoming stack.
export const opens = '([{';
export const closes = ')]}';

export function directScan(text, incoming = [], start = 0, cap = 63) {
  const stack = incoming.map(x => ({ ...x }));
  const externalPairs = [];
  let inspected = 0;
  for (let i = start; i < text.length; i++) {
    inspected++;
    const o = opens.indexOf(text[i]);
    if (o >= 0) {
      if (stack.length === cap) return { abortedAt: i, stack: null, externalPairs: [], inspected };
      stack.push({ type: o, id: i });
      continue;
    }
    const c = closes.indexOf(text[i]);
    if (c < 0) continue;
    for (let j = stack.length - 1; j >= 0; j--) {
      if (stack[j].type !== c) continue;
      if (stack[j].id < 0) externalPairs.push([stack[j].id, i]);
      stack.length = j;
      break;
    }
  }
  return { abortedAt: null, stack, externalPairs, inspected };
}

export class SuffixIndex {
  constructor(text, cap = 63) {
    if (!/^[\x00-\x7f]*$/.test(text)) throw new Error('prototype accepts ASCII only');
    if (!Number.isInteger(cap) || cap < 1 || cap > 63) throw new Error('capacity must be in 1..63');
    this.text = [...text];
    this.n = text.length;
    this.cap = cap;
    // J_t(i): first type-t closer unmatched by the empty-stack suffix scan.
    this.j = Array.from({ length: opens.length }, () => new Int32Array(this.n + 1).fill(this.n));
    // H_k(i): first position attaining depth k in the unbounded empty scan.
    // Depth cap+1 is needed to identify the first rejected opener.
    // A row for depth k is allocated only if some suffix actually reaches k.
    // Prepending one opener can increase the maximum reached depth by only one.
    this.h = [null];
    this.finalHead = new Int32Array(this.n + 1).fill(-1);
    this.tail = new Int32Array(this.n).fill(-1);
    this.rowWrites = 0;
    for (let i = this.n - 1; i >= 0; i--) this.rebuildRow(i);
  }

  rebuildRow(i) {
    this.rowWrites++;
    const o = opens.indexOf(this.text[i]);
    const c = closes.indexOf(this.text[i]);
    if (o < 0) {
      for (let t = 0; t < opens.length; t++) this.j[t][i] = t === c ? i : this.j[t][i + 1];
      for (let k = 1; k < this.h.length; k++) this.h[k][i] = this.h[k][i + 1];
      this.finalHead[i] = this.finalHead[i + 1];
      return;
    }
    const match = this.j[o][i + 1];
    const rest = match === this.n ? this.n : match + 1;
    for (let t = 0; t < opens.length; t++) {
      const first = this.j[t][i + 1];
      this.j[t][i] = t !== o && first < match ? first : this.j[t][rest];
    }
    if (this.h.length === 1) this.h.push(new Int32Array(this.n + 1).fill(this.n));
    const nextDepth = this.h.length;
    if (nextDepth <= this.cap + 1 && this.h[nextDepth - 1][i + 1] < match)
      this.h.push(new Int32Array(this.n + 1).fill(this.n));
    this.h[1][i] = i;
    for (let k = 2; k < this.h.length; k++) {
      const first = this.h[k - 1][i + 1];
      this.h[k][i] = first < match ? first : this.h[k][rest];
    }
    if (match === this.n) {
      this.finalHead[i] = i;
      this.tail[i] = this.finalHead[i + 1];
    } else {
      this.finalHead[i] = this.finalHead[rest];
    }
  }

  // Deliberately charge all maintenance. A static index does not make arbitrary
  // text edits cheap: this implementation recomputes the prefix through at.
  substitute(at, char) {
    if (!Number.isInteger(at) || at < 0 || at >= this.n || char.length !== 1 || char.charCodeAt(0) > 127)
      throw new Error('invalid fixed-length substitution');
    this.text[at] = char;
    this.rowWrites = 0;
    for (let i = at; i >= 0; i--) this.rebuildRow(i);
    return this.rowWrites;
  }

  apply(incoming = [], start = 0) {
    if (incoming.length > this.cap) throw new Error('incoming stack exceeds capacity');
    if (!Number.isInteger(start) || start < 0 || start > this.n) throw new Error('invalid suffix start');
    if (incoming.some(x => !Number.isInteger(x.type) || x.type < 0 || x.type >= opens.length || !Number.isInteger(x.id) || x.id >= 0))
      throw new Error('incoming entries need valid types and negative external identities');
    const stack = incoming.map(x => ({ ...x }));
    const externalPairs = [];
    let at = start;
    let probes = 0;
    let jumps = 0;
    while (true) {
      let match = this.n;
      let matchingSlot = -1;
      // The last same-type opener is the one consumed. Visiting bottom-to-top
      // and accepting equality selects that slot without copying type maps.
      for (let j = 0; j < stack.length; j++) {
        probes++;
        const p = this.j[stack[j].type][at];
        if (p <= match) { match = p; matchingSlot = j; }
      }
      probes++;
      const overflow = this.height(this.cap - stack.length + 1, at);
      if (overflow < match) return { abortedAt: overflow, stack: null, externalPairs: [], probes, jumps };
      if (match === this.n) {
        for (let p = this.finalHead[at]; p !== -1; p = this.tail[p]) {
          probes++;
          stack.push({ type: opens.indexOf(this.text[p]), id: p });
        }
        return { abortedAt: null, stack, externalPairs, probes, jumps };
      }
      externalPairs.push([stack[matchingSlot].id, match]);
      stack.length = matchingSlot;
      at = match + 1;
      jumps++;
    }
  }

  get allocatedBytes() {
    return [...this.j, ...this.h.slice(1), this.finalHead, this.tail].reduce((n, a) => n + a.byteLength, 0);
  }
  height(k, start) { return this.h[k]?.[start] ?? this.n; }
}
