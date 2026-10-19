// Private M1/S0 pairing/target compiler. No production ID allocation, persistence or native fence decision.
import {canonicalBasis} from './jvm-owner-client.mjs';
import {randomUUID} from 'node:crypto';

const validString = value => typeof value === 'string' && value.length > 0;
const domains = new Set(['text-unit', 'paragraph-boundary']);
const key = identity => JSON.stringify([identity.domain, identity.flow, identity.id]);
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const refusal = (outcome, reason) => ({outcome, reason});

export class NativeIdentityPairing {
  #document; #flow; #owner = null; #basis = null; #entries = []; #byKey = new Map(); #claims = new Map(); #payload = [];
  #generation = randomUUID();
  constructor({documentId, flow}) {
    if (!validString(documentId) || !validString(flow)) throw Error('Explicit document/flow context required');
    this.#document = documentId; this.#flow = flow;
  }
  snapshot() {
    return structuredClone({documentId: this.#document, flow: this.#flow, ownerIncarnation: this.#owner, pairingGeneration: this.#generation,
      basis: this.#basis, entries: this.#entries, claims: [...this.#claims], payload: this.#payload});
  }
  bind(projection, frame) {
    try {
      if (projection?.outcome !== 'SupportedParagraphProjection' || projection.documentId !== this.#document || projection.flow !== this.#flow || !Array.isArray(projection.units))
        return refusal('RejectedPairing', 'Unsupported projection/flow');
      const basis = canonicalBasis(frame);
      if (!validString(basis.ownerIncarnation) || (this.#owner !== null && basis.ownerIncarnation !== this.#owner))
        return refusal('RejectedOwnerIncarnation', 'Pairing belongs to another endpoint');
      if (this.#basis && (basis.basisRevision < this.#basis.basisRevision || basis.epoch < this.#basis.epoch || BigInt(basis.basisIdentityGeneration) < BigInt(this.#basis.basisIdentityGeneration)))
        return refusal('RejectedStalePairing', 'Pairing cannot regress to an older owner frame');
      const ids = basis.basisUnitIds, rows = frame.committedUnits, projected = projection.units;
      if (!Array.isArray(rows) || ids.length !== projected.length || rows.length !== projected.length || frame.committed !== projection.text)
        return refusal('RejectedPairing', 'Committed payload length/text mismatch');
      if (ids.some(id => id.length > 19 || !/^[1-9][0-9]*$/.test(id) || BigInt(id) > 9223372036854775807n) || new Set(ids).size !== ids.length)
        return refusal('RejectedPairing', 'Invalid/duplicate native identity');
      const entries = [], byKey = new Map(), claims = new Map(this.#claims), canonicalKeys = new Set(), payload = [];
      for (const [index, unit] of projected.entries()) {
        const identity = unit.identity;
        if (!identity || !domains.has(identity.domain) || identity.flow !== this.#flow || !validString(identity.id) ||
            identity.domain !== (unit.kind === 0 ? 'text-unit' : unit.kind === 1 ? 'paragraph-boundary' : null) ||
            (unit.kind === 0 && identity.id !== unit.tokenId))
          return refusal('RejectedPairing', 'Invalid typed canonical identity');
        const canonicalKey = key(identity);
        if (canonicalKeys.has(canonicalKey)) return refusal('RejectedPairing', 'Duplicate typed canonical identity');
        canonicalKeys.add(canonicalKey);
        const row = rows[index], expected = [unit.char?.charCodeAt(0), unit.kind, unit.style, unit.paragraphStyle];
        if (typeof unit.char !== 'string' || unit.char.length !== 1 || !Array.isArray(row) || row.length !== 5 || !equal(row.slice(1), expected) ||
            (Number.isSafeInteger(row[0]) && String(row[0]) !== ids[index]))
          return refusal('RejectedPairing', 'Committed payload/identity mismatch');
        if (claims.has(ids[index]) && claims.get(ids[index]) !== canonicalKey)
          return refusal('RejectedIdentityReassignment', 'A previously paired native ID cannot become another canonical identity');
        const previous = this.#byKey.get(canonicalKey);
        if (previous && previous.nativeId !== ids[index])
          return refusal('RejectedIdentityReassignment', 'Retained canonical identity changed its native ID; explicit resync/lifecycle admission required');
        claims.set(ids[index], canonicalKey);
        const entry = {identity: {...identity}, nativeId: ids[index], index};
        entries.push(entry); byKey.set(canonicalKey, entry); payload.push(row.slice(1));
      }
      // Only publish the fully validated pairing. Failed staging cannot alter old claims.
      this.#owner = basis.ownerIncarnation; this.#basis = structuredClone(basis);
      this.#entries = entries; this.#byKey = byKey; this.#claims = claims; this.#payload = payload;
      return {outcome: 'Paired', units: entries.length};
    } catch (error) {return refusal('RejectedPairing', error.message);}
  }
  reference(domain, id) {
    const identity = {domain, flow: this.#flow, id};
    if (this.#owner === null || !this.#byKey.has(key(identity))) throw Error('Identity is not currently paired');
    return Object.freeze({documentId: this.#document, ownerIncarnation: this.#owner, pairingGeneration: this.#generation, ...identity});
  }
  #ready(frame) {
    if (!frame || !Array.isArray(frame.committedUnits) || frame.committedUnits.some(row => !Array.isArray(row) || row.length !== 5) ||
        !Object.hasOwn(frame, 'marked')) return refusal('RejectedFrame', 'Missing committed owner frame');
    if (frame.marked !== null) return refusal('DeferredComposition', 'Provisional content is not a canonical identity target');
    if (frame.waiting) return refusal('DeferredOwnerWork', 'Owner work has not settled');
    let basis;
    try {basis = canonicalBasis(frame);} catch (error) {return refusal('RejectedFrame', error.message);}
    if (this.#owner === null || basis.ownerIncarnation !== this.#owner) return refusal('RejectedOwnerIncarnation', 'Owner endpoint changed');
    if (!equal(basis, this.#basis) || !equal(frame.committedUnits.map(row => row.slice(1)), this.#payload))
      return refusal('RebindRequired', 'The supplied frame is not the paired committed basis');
    return {outcome: 'Ready', basis};
  }
  compileDeletion(references, frame) {
    const ready = this.#ready(frame); if (ready.outcome !== 'Ready') return ready;
    const basis = ready.basis;
    if (!Array.isArray(references)) return refusal('RejectedReference', 'Target identities must be an array');
    if (!references.length) return refusal('NoChange', 'No target identities');
    const indices = [], selected = new Set();
    for (const ref of references) {
      if (!ref || ref.documentId !== this.#document || ref.flow !== this.#flow || ref.ownerIncarnation !== this.#owner || ref.pairingGeneration !== this.#generation)
        return refusal('RejectedContext', 'Document/flow/endpoint reference mismatch');
      if (ref.domain !== 'text-unit') return refusal('UnsupportedStructuralTarget', 'Paragraph boundaries require structural commands');
      const canonicalKey = key(ref), entry = this.#byKey.get(canonicalKey);
      if (!entry) return refusal('RejectedTargetMissing', 'Canonical identity no longer exists in this pairing');
      if (selected.has(entry.index)) return refusal('RejectedDuplicateTarget', 'Duplicate target identity');
      selected.add(entry.index); indices.push(entry.index);
    }
    if (!Array.isArray(frame.selection) || frame.selection.length !== 2 || frame.selection.some(index => !Number.isSafeInteger(index) || index < 0 || index > this.#entries.length))
      return refusal('RejectedFrame', 'Invalid committed selection');
    indices.sort((a, b) => a - b);
    const ranges = [];
    for (const index of indices) {
      const last = ranges.at(-1);
      if (last?.end === index) last.end++; else ranges.push({start: index, end: index + 1});
    }
    const selection = frame.selection.map(position => position - indices.filter(index => index < position).length);
    return {outcome: 'Compiled', command: {command: 'canonical-batch', ...basis,
      edits: ranges.reverse().map(range => ({kind: 'replace', ...range, text: '', style: 0})), selection}};
  }
  compileTransition(projection, frame) {
    const ready = this.#ready(frame); if (ready.outcome !== 'Ready') return ready;
    if (projection?.outcome !== 'SupportedParagraphProjection' || projection.documentId !== this.#document || projection.flow !== this.#flow || !Array.isArray(projection.units))
      return refusal('RejectedProjection', 'Unsupported document/flow projection');
    const next = [], nextKeys = new Set();
    for (const unit of projection.units) {
      const identity = unit.identity;
      if (!identity || !domains.has(identity.domain) || identity.flow !== this.#flow || !validString(identity.id) ||
          identity.domain !== (unit.kind === 0 ? 'text-unit' : unit.kind === 1 ? 'paragraph-boundary' : null) ||
          (unit.kind === 0 && identity.id !== unit.tokenId) || typeof unit.char !== 'string' || unit.char.length !== 1 ||
          !Number.isInteger(unit.style) || unit.style < 0 || unit.style > 3 || !Number.isInteger(unit.paragraphStyle) || unit.paragraphStyle < 0 || unit.paragraphStyle > 3 ||
          (unit.kind === 0 && unit.paragraphStyle !== 0) || (unit.kind === 1 && (unit.char !== '\n' || unit.style !== 0)))
        return refusal('RejectedProjection', 'Invalid typed projection unit');
      const canonicalKey = key(identity), payload = [unit.char.charCodeAt(0), unit.kind, unit.style, unit.paragraphStyle];
      if (nextKeys.has(canonicalKey)) return refusal('RejectedProjection', 'Duplicate typed projection identity');
      const previous = this.#byKey.get(canonicalKey);
      if (previous && (this.#payload[previous.index][0] !== payload[0] || this.#payload[previous.index][1] !== payload[1]))
        return refusal('RejectedIdentityMutation', 'Retained canonical identity changed its character/kind');
      nextKeys.add(canonicalKey); next.push({key: canonicalKey, payload});
    }
    if (projection.text !== next.map(entry => String.fromCharCode(entry.payload[0])).join(''))
      return refusal('RejectedProjection', 'Projection text/payload mismatch');
    const work = this.#entries.map((entry, i) => ({key: key(entry.identity), payload: [...this.#payload[i]]})), edits = [];
    // Reference compiler over bounded arrays. Production indexing/scaling is not selected here.
    for (let end = work.length; end > 0;) {
      if (nextKeys.has(work[end - 1].key)) {end--; continue;}
      let start = end - 1; while (start > 0 && !nextKeys.has(work[start - 1].key)) start--;
      edits.push({kind: 'replace', start, end, text: '', style: 0}); work.splice(start, end - start); end = start;
    }
    for (let i = 0; i < next.length;) {
      const index = work.findIndex(entry => entry.key === next[i].key);
      if (index < 0) {
        const wanted = next[i].payload;
        let count = 1;
        while (i + count < next.length && !work.some(entry => entry.key === next[i + count].key) && equal(next[i + count].payload.slice(1), wanted.slice(1))) count++;
        const inserted = next.slice(i, i + count).map(entry => ({key: entry.key, payload: [...entry.payload]}));
        edits.push({kind: 'replace', start: i, end: i, text: inserted.map(entry => String.fromCharCode(entry.payload[0])).join(''),
          style: wanted[1] === 1 ? wanted[3] : wanted[2], paragraphBreaks: wanted[1] === 1});
        work.splice(i, 0, ...inserted); i += count; continue;
      }
      if (index !== i) {
        if (index < i) return refusal('RejectedProjection', 'Inconsistent identity permutation');
        let count = 1; while (index + count < work.length && i + count < next.length && work[index + count].key === next[i + count].key) count++;
        edits.push({kind: 'move', start: index, end: index + count, destination: i});
        work.splice(i, 0, ...work.splice(index, count));
      }
      i++;
    }
    for (let i = 0; i < next.length;) {
      if (equal(work[i].payload, next[i].payload)) {i++; continue;}
      const flags = next[i].payload[1] === 1 ? next[i].payload[3] : next[i].payload[2];
      let end = i + 1; while (end < next.length && (next[end].payload[1] === 1 ? next[end].payload[3] : next[end].payload[2]) === flags) end++;
      edits.push({kind: 'attributes', start: i, end, style: flags});
      for (let at = i; at < end; at++) work[at].payload = [work[at].payload[0], work[at].payload[1], work[at].payload[1] === 1 ? 0 : flags, work[at].payload[1] === 1 ? flags : 0];
      i = end;
    }
    if (!equal(work, next)) return refusal('RejectedProjection', 'Transition did not preserve the requested identity/payload sequence');
    if (!Array.isArray(frame.selection) || frame.selection.length !== 2 || frame.selection.some(i => !Number.isSafeInteger(i) || i < 0 || i > this.#entries.length))
      return refusal('RejectedFrame', 'Invalid committed selection');
    const positions = new Map(next.map((entry, index) => [entry.key, index]));
    const mapPoint = point => {
      const right = this.#entries[point], left = this.#entries[point - 1];
      if (right && positions.has(key(right.identity))) return positions.get(key(right.identity));
      if (left && positions.has(key(left.identity))) return positions.get(key(left.identity)) + 1;
      for (let right = point; right < this.#entries.length; right++) {const k = key(this.#entries[right].identity); if (positions.has(k)) return positions.get(k);}
      for (let left = point - 1; left >= 0; left--) {const k = key(this.#entries[left].identity); if (positions.has(k)) return positions.get(k) + 1;}
      return Math.min(point, next.length);
    };
    if (!edits.length) return refusal('NoChange', 'Projection identities and payload are unchanged');
    return {outcome: 'Compiled', command: {command: 'canonical-batch', ...ready.basis, edits, selection: frame.selection.map(mapPoint)}};
  }
}
