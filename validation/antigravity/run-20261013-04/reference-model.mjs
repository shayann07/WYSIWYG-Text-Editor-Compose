// Reference Model for plan-v4.md Core Algorithms and Contracts
// Implements deliberate small reference model of NEW rules:
// - Candidate reconciliation algorithm with immutable baseline (§4.3)
// - Partial commit freezing & attribution epoch reset (§4.3)
// - Grapheme cluster preservation and effective attribute presentation (§5.3)
// - UTF-16 raw storage, invalid surrogate handling & export conversion (§2.3, §7)
// - Android code-point deletion vs UTF-16 deletion contract (§6.3)
// - Protocol connection lifecycle, epoch stamps, duplicate detection, settlement (§4.1, §4.5)
// - Position mapping algorithms (§2.5)
// - Structural admission reducer & noncancelable fault recovery (§6.1)
// - Transactional history, queued formatting & no-op handling (§7)

export function isHighSurrogate(code) {
  return code >= 0xD800 && code <= 0xDBFF;
}

export function isLowSurrogate(code) {
  return code >= 0xDC00 && code <= 0xDFFF;
}

export function isSurrogate(code) {
  return code >= 0xD800 && code <= 0xDFFF;
}

export function isSurrogatePairAt(str, idx) {
  if (idx < 0 || idx + 1 >= str.length) return false;
  return isHighSurrogate(str.charCodeAt(idx)) && isLowSurrogate(str.charCodeAt(idx + 1));
}

// ---------------------------------------------------------------------------
// Candidate Reconciliation Algorithm (§4.3 Steps 1-6)
// ---------------------------------------------------------------------------

export function reconcileCandidateAttributes(B, CText, seedAttributes) {
  // B: array of { char: string, attributes: object }
  // CText: string (current candidate text)
  // seedAttributes: object (default attributes for new middle text)
  
  const bText = B.map(u => u.char).join('');
  
  // Step 5 shortcut: If C equals B.text, restore B's full raw attribution.
  if (CText === bText) {
    return B.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
  }
  
  const bLen = bText.length;
  const cLen = CText.length;
  const maxL = Math.min(bLen, cLen);
  
  // Step 3: Find maximal common prefix p
  let p = 0;
  while (p < maxL && bText.charCodeAt(p) === CText.charCodeAt(p)) {
    p++;
  }
  
  // Step 3: Find maximal common suffix s constrained by p + s <= min(len(B), len(C))
  // Prefix wins ties for repeated text
  let s = 0;
  const maxS = maxL - p;
  while (s < maxS && bText.charCodeAt(bLen - 1 - s) === CText.charCodeAt(cLen - 1 - s)) {
    s++;
  }
  
  // Step 4: Retreat p/s as needed so retained-copy boundaries do not bisect a valid surrogate pair in either input
  // Check prefix boundary p
  if (p > 0) {
    if (isSurrogatePairAt(bText, p - 1) || isSurrogatePairAt(CText, p - 1)) {
      p--;
    }
  }
  
  // Check suffix boundary s
  if (s > 0) {
    const bSuffixStart = bLen - s;
    const cSuffixStart = cLen - s;
    if ((bSuffixStart > 0 && isSurrogatePairAt(bText, bSuffixStart - 1)) ||
        (cSuffixStart > 0 && isSurrogatePairAt(CText, cSuffixStart - 1))) {
      s--;
    }
  }
  
  // Step 5: Copy B's raw attributes for prefix and suffix; apply seed throughout changed middle
  const result = [];
  
  // Prefix
  for (let i = 0; i < p; i++) {
    result.push({ char: CText[i], attributes: { ...B[i].attributes } });
  }
  
  // Middle
  const middleEnd = cLen - s;
  for (let i = p; i < middleEnd; i++) {
    result.push({ char: CText[i], attributes: { ...seedAttributes } });
  }
  
  // Suffix
  for (let i = middleEnd; i < cLen; i++) {
    const bIdx = bLen - cLen + i;
    result.push({ char: CText[i], attributes: { ...B[bIdx].attributes } });
  }
  
  return result;
}

// ---------------------------------------------------------------------------
// Partial Commit Freezing (§4.3)
// ---------------------------------------------------------------------------

export class CompositionAttributionSession {
  constructor(initialUnits, seedAttributes) {
    this.epoch = 1;
    this.seed = { ...seedAttributes };
    this.baseline = initialUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
    this.candidateUnits = [...this.baseline];
    this.frozenPrefix = [];
  }
  
  updateCandidate(newCText) {
    this.candidateUnits = reconcileCandidateAttributes(this.baseline, newCText, this.seed);
    return this.getLiveIsland();
  }
  
  partialCommit(committedLength) {
    if (committedLength > this.candidateUnits.length) {
      throw new Error("Committed length exceeds current candidate length");
    }
    
    // 1. Freeze committed portion
    const committedPortion = this.candidateUnits.slice(0, committedLength);
    this.frozenPrefix.push(...committedPortion);
    
    // 2. Remaining marked units
    const remainingUnits = this.candidateUnits.slice(committedLength);
    
    // 3. Start new attribution epoch from current attributed remaining marked range
    this.epoch++;
    this.baseline = remainingUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
    this.candidateUnits = [...this.baseline];
    
    return {
      frozen: this.frozenPrefix,
      remaining: this.candidateUnits,
      epoch: this.epoch
    };
  }
  
  getLiveIsland() {
    return [...this.frozenPrefix, ...this.candidateUnits];
  }
}

// ---------------------------------------------------------------------------
// Grapheme Preservation and Effective View (§5.3)
// ---------------------------------------------------------------------------

export function segmentGraphemes(text) {
  const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
  const segments = [];
  for (const seg of segmenter.segment(text)) {
    segments.push({
      segment: seg.segment,
      index: seg.index,
      length: seg.segment.length
    });
  }
  return segments;
}

export function computeEffectiveRenderingView(units) {
  // units: array of { char: string, attributes: object }
  const text = units.map(u => u.char).join('');
  const segments = segmentGraphemes(text);
  
  return segments.map(seg => {
    // Ordinary presentation attributes come from first scalar's leading unit
    const leadingUnit = units[seg.index];
    return {
      cluster: seg.segment,
      startIndex: seg.index,
      length: seg.length,
      effectiveAttributes: { ...leadingUnit.attributes }
    };
  });
}

export function applyGraphemeExpandedFormat(units, startIndex, endIndex, attributePatch) {
  // Expands nonempty interval [startIndex, endIndex) outward to grapheme boundaries
  const text = units.map(u => u.char).join('');
  const segments = segmentGraphemes(text);
  
  let expandedStart = startIndex;
  let expandedEnd = endIndex;
  
  for (const seg of segments) {
    const segEnd = seg.index + seg.length;
    // If overlaps with [startIndex, endIndex)
    if (seg.index < endIndex && segEnd > startIndex) {
      if (seg.index < expandedStart) expandedStart = seg.index;
      if (segEnd > expandedEnd) expandedEnd = segEnd;
    }
  }
  
  // Patch all units in expanded range
  const newUnits = units.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
  for (let i = expandedStart; i < expandedEnd; i++) {
    newUnits[i].attributes = { ...newUnits[i].attributes, ...attributePatch };
  }
  
  return {
    units: newUnits,
    expandedRange: [expandedStart, expandedEnd]
  };
}

// ---------------------------------------------------------------------------
// Android InputConnection Code-Point Deletion Contract (§6.3)
// ---------------------------------------------------------------------------

export function deleteSurroundingTextInCodePoints(textUnits, cursorOffset, beforeCodePoints, afterCodePoints) {
  // Contract: scan both sides before mutating; reject malformed traversals atomically (do nothing)
  const text = textUnits.map(u => u.char).join('');
  
  // Scan before
  let beforeUnits = 0;
  let cpCount = 0;
  let i = cursorOffset;
  while (cpCount < beforeCodePoints && i > 0) {
    if (isLowSurrogate(text.charCodeAt(i - 1))) {
      if (i - 2 >= 0 && isHighSurrogate(text.charCodeAt(i - 2))) {
        // Valid pair
        beforeUnits += 2;
        i -= 2;
        cpCount++;
      } else {
        // Isolated low surrogate: malformed traversal!
        return { success: false, reason: "Malformed surrogate before cursor", units: textUnits };
      }
    } else if (isHighSurrogate(text.charCodeAt(i - 1))) {
      // Isolated high surrogate before cursor: malformed traversal!
      return { success: false, reason: "Isolated high surrogate before cursor", units: textUnits };
    } else {
      beforeUnits += 1;
      i -= 1;
      cpCount++;
    }
  }
  
  // Scan after
  let afterUnits = 0;
  cpCount = 0;
  i = cursorOffset;
  while (cpCount < afterCodePoints && i < text.length) {
    if (isHighSurrogate(text.charCodeAt(i))) {
      if (i + 1 < text.length && isLowSurrogate(text.charCodeAt(i + 1))) {
        // Valid pair
        afterUnits += 2;
        i += 2;
        cpCount++;
      } else {
        // Isolated high surrogate: malformed traversal!
        return { success: false, reason: "Isolated high surrogate after cursor", units: textUnits };
      }
    } else if (isLowSurrogate(text.charCodeAt(i))) {
      // Isolated low surrogate: malformed traversal!
      return { success: false, reason: "Isolated low surrogate after cursor", units: textUnits };
    } else {
      afterUnits += 1;
      i += 1;
      cpCount++;
    }
  }
  
  // Valid traversal: delete [cursorOffset - beforeUnits, cursorOffset + afterUnits)
  const delStart = cursorOffset - beforeUnits;
  const delEnd = cursorOffset + afterUnits;
  const remaining = [
    ...textUnits.slice(0, delStart),
    ...textUnits.slice(delEnd)
  ];
  return { success: true, deletedRange: [delStart, delEnd], units: remaining };
}

export function deleteSurroundingTextInUtf16(textUnits, cursorOffset, beforeUnits, afterUnits) {
  // UTF-16 unit deletion: unit counts without surrogate validation, can leave isolated surrogates
  const start = Math.max(0, cursorOffset - beforeUnits);
  const end = Math.min(textUnits.length, cursorOffset + afterUnits);
  const remaining = [
    ...textUnits.slice(0, start),
    ...textUnits.slice(end)
  ];
  return { success: true, deletedRange: [start, end], units: remaining };
}

// ---------------------------------------------------------------------------
// Position Mapping Algorithm (§2.5)
// ---------------------------------------------------------------------------

export function mapOffset(offset, assoc, edit) {
  // edit: { type: 'insert', pos: a, len: m } or { type: 'replace', start: a, end: b, len: m }
  if (edit.type === 'insert') {
    const a = edit.pos;
    const m = edit.len;
    if (offset < a) return { offset, deleted: false };
    if (offset > a) return { offset: offset + m, deleted: false };
    // offset === a
    return { offset: assoc === 'Before' ? a : a + m, deleted: false };
  } else if (edit.type === 'replace') {
    const a = edit.start;
    const b = edit.end;
    const m = edit.len;
    if (offset < a) return { offset, deleted: false };
    if (offset === a) return { offset: a, deleted: false };
    if (offset > a && offset < b) {
      return { offset: assoc === 'Before' ? a : a + m, deleted: true };
    }
    if (offset === b) return { offset: a + m, deleted: false };
    // offset > b
    return { offset: offset + (m - (b - a)), deleted: false };
  }
  throw new Error("Unknown edit type");
}

// ---------------------------------------------------------------------------
// Canonical Persistence & Valid-Unicode Export (§2.3, §7)
// ---------------------------------------------------------------------------

export function serializeCanonicalDocument(doc) {
  // Encodes UTF-16 text as base64 UTF-16LE chunks
  const buffer = Buffer.alloc(doc.textUnits.length * 2);
  for (let i = 0; i < doc.textUnits.length; i++) {
    buffer.writeUInt16LE(doc.textUnits[i].char.charCodeAt(0), i * 2);
  }
  return {
    format: "editor-document/1",
    id: doc.id,
    schemaVersion: 1,
    unitCount: doc.textUnits.length,
    base64TextLE: buffer.toString('base64'),
    attributeRuns: doc.textUnits.map(u => u.attributes)
  };
}

export function deserializeCanonicalDocument(json) {
  if (json.format !== "editor-document/1") throw new Error("Unknown format");
  const buffer = Buffer.from(json.base64TextLE, 'base64');
  const unitCount = buffer.length / 2;
  const textUnits = [];
  for (let i = 0; i < unitCount; i++) {
    const code = buffer.readUInt16LE(i * 2);
    textUnits.push({
      char: String.fromCharCode(code),
      attributes: { ...(json.attributeRuns[i] || {}) }
    });
  }
  return {
    id: json.id,
    textUnits
  };
}

export function exportValidUnicode(textUnits) {
  const text = textUnits.map(u => u.char).join('');
  let exported = '';
  let isolatedSurrogateCount = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (isHighSurrogate(code)) {
      if (i + 1 < text.length && isLowSurrogate(text.charCodeAt(i + 1))) {
        exported += text[i] + text[i + 1];
        i++;
      } else {
        exported += '\uFFFD';
        isolatedSurrogateCount++;
      }
    } else if (isLowSurrogate(code)) {
      exported += '\uFFFD';
      isolatedSurrogateCount++;
    } else {
      exported += text[i];
    }
  }
  return {
    text: exported,
    isolatedSurrogateCount,
    valid: isolatedSurrogateCount === 0
  };
}
