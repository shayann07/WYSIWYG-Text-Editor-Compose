import { SuffixIndex, opens, closes } from './bracket-suffix-index.mjs';

// Editor-facing experiment: variable-length replacement and pair-at-position.
// Fixed target block size is explicit; choose sqrt(n) for the scaling fixture.
// This is not a full bidi index or a production balanced sequence container.
export class BlockIndex {
  constructor(text, blockSize = 256, cap = 63) {
    if (!Number.isInteger(blockSize) || blockSize < 2) throw new Error('invalid block size');
    this.blockSize = blockSize;
    this.cap = cap;
    this.blocks = [];
    this.length = text.length;
    this.rebuiltCharacters = 0;
    this.makeBlocks(text, this.blocks);
  }
  makeBlocks(text, out) {
    const count = Math.ceil(text.length / this.blockSize);
    // Distribute the remainder. Emitting a full block followed by a tiny one
    // permits repeated insertions to create arbitrarily many tiny blocks.
    const base = count === 0 ? 0 : Math.floor(text.length / count);
    const extra = count === 0 ? 0 : text.length % count;
    let p = 0;
    for (let i = 0; i < count; i++) {
      const length = base + (i < extra ? 1 : 0);
      const part = text.slice(p, p + length);
      out.push({ text: part, index: new SuffixIndex(part, this.cap) });
      this.rebuiltCharacters += part.length;
      p += length;
    }
  }
  text() { return this.blocks.map(b => b.text).join(''); }
  locate(position) {
    let offset = 0;
    for (let i = 0; i < this.blocks.length; i++) {
      const end = offset + this.blocks[i].text.length;
      if (position < end || (position === end && i === this.blocks.length - 1))
        return { block: i, within: position - offset, offset };
      offset = end;
    }
    return { block: this.blocks.length, within: 0, offset };
  }
  replace(start, end, text) {
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || start > end || end > this.length)
      throw new Error('invalid replacement range');
    if (!/^[\x00-\x7f]*$/.test(text)) throw new Error('prototype accepts ASCII only');
    this.rebuiltCharacters = 0;
    const a = this.locate(start);
    const b = this.locate(end);
    let lo = a.block;
    let hi = Math.min(b.block + 1, this.blocks.length);
    let replacement = (this.blocks[a.block]?.text.slice(0, a.within) ?? '') + text +
      (this.blocks[b.block]?.text.slice(b.within) ?? '');
    // Absorb at most one neighbor on each side to bound fragmentation.
    const minimum = Math.floor(this.blockSize / 2);
    if (lo > 0 && replacement.length < minimum) {
      replacement = this.blocks[--lo].text + replacement;
    }
    if (hi < this.blocks.length && replacement.length < minimum) {
      replacement += this.blocks[hi++].text;
    }
    const added = [];
    this.makeBlocks(replacement, added);
    this.blocks.splice(lo, hi - lo, ...added);
    this.length += text.length - (end - start);
    return { rebuiltCharacters: this.rebuiltCharacters, blocks: this.blocks.length };
  }
  pairAt(position) {
    if (!Number.isInteger(position) || position < 0 || position >= this.length) throw new Error('invalid query');
    let stack = [];
    let answer = null;
    let offset = 0;
    let tableProbes = 0;
    let scannedCharacters = 0;
    let blockVisits = 0;
    for (const block of this.blocks) {
      blockVisits++;
      if (offset <= position && position < offset + block.text.length) {
        for (let local = 0; local < block.text.length; local++) {
          scannedCharacters++;
          const id = offset + local;
          const o = opens.indexOf(block.text[local]);
          const c = closes.indexOf(block.text[local]);
          if (o >= 0) {
            if (stack.length === this.cap)
              return { pair: null, abortedAt: id, tableProbes, scannedCharacters, blockVisits };
            stack.push({ type: o, id });
          } else if (c >= 0) {
            for (let j = stack.length - 1; j >= 0; j--) if (stack[j].type === c) {
              if (stack[j].id === position || id === position) answer = [stack[j].id, id];
              stack.length = j;
              break;
            }
          }
        }
      } else {
        const previous = stack;
        const incoming = previous.map((x, i) => ({ type: x.type, id: -i - 1 }));
        const result = block.index.apply(incoming);
        tableProbes += result.probes;
        if (result.abortedAt !== null)
          return { pair: null, abortedAt: offset + result.abortedAt, tableProbes, scannedCharacters, blockVisits };
        for (const [external, close] of result.externalPairs) {
          const open = previous[-external - 1].id;
          if (open === position) answer = [open, offset + close];
        }
        stack = result.stack.map(x => x.id < 0 ? previous[-x.id - 1] : { type: x.type, id: offset + x.id });
      }
      offset += block.text.length;
    }
    return { pair: answer, abortedAt: null, tableProbes, scannedCharacters, blockVisits };
  }
  get allocatedBytes() { return this.blocks.reduce((n, b) => n + b.index.allocatedBytes, 0); }
}

export function referencePairs(text, cap = 63) {
  const stack = [];
  const pairs = new Map();
  let scannedCharacters = 0;
  for (let i = 0; i < text.length; i++) {
    scannedCharacters++;
    const o = opens.indexOf(text[i]);
    const c = closes.indexOf(text[i]);
    if (o >= 0) {
      if (stack.length === cap) return { pairs: new Map(), abortedAt: i, scannedCharacters };
      stack.push({ type: o, position: i });
    } else if (c >= 0) {
      let j = stack.length - 1;
      while (j >= 0 && stack[j].type !== c) j--;
      if (j >= 0) {
        const pair = [stack[j].position, i];
        pairs.set(pair[0], pair);
        pairs.set(pair[1], pair);
        stack.length = j;
      }
    }
  }
  return { pairs, abortedAt: null, scannedCharacters };
}
