import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {driver, grid} from '../../validation/codex/run-20261015-19/test-document.mjs';
import {textBlock as p, container as c, project, raw, leaves} from '../../validation/codex/run-20261015-19/structural-model.mjs';
import {makeDocument, flows, getFlow, validateDocument} from '../../validation/codex/run-20261015-19/document-tree.mjs';
import {RepairedDocumentModel} from './repaired-document-model.mjs';

// ============================================================================
// PART 1: Persistent Hierarchical B+ Index with 2-Component Monoid Measure
//         (contentUtf16Length, leafBlockCount) + Persistent AVL TokenMap/ParentMap
// ============================================================================

const avlHeight = n => n?.h ?? 0;
function avlNode(k, v, l, r, stats) {
  stats.mapAlloc++;
  return Object.freeze({k, v, l, r, h: 1 + Math.max(avlHeight(l), avlHeight(r))});
}
function avlBalance(k, v, l, r, stats) {
  if (avlHeight(l) > avlHeight(r) + 1) {
    if (avlHeight(l.l) >= avlHeight(l.r)) return avlNode(l.k, l.v, l.l, avlNode(k, v, l.r, r, stats), stats);
    const m = l.r;
    return avlNode(m.k, m.v, avlNode(l.k, l.v, l.l, m.l, stats), avlNode(k, v, m.r, r, stats), stats);
  }
  if (avlHeight(r) > avlHeight(l) + 1) {
    if (avlHeight(r.r) >= avlHeight(r.l)) return avlNode(r.k, r.v, avlNode(k, v, l, r.l, stats), r.r, stats);
    const m = r.l;
    return avlNode(m.k, m.v, avlNode(k, v, l, m.l, stats), avlNode(r.k, r.v, m.r, r.r, stats), stats);
  }
  return avlNode(k, v, l, r, stats);
}
function avlPut(n, k, v, stats) {
  stats.mapVisits++;
  if (!n) return avlNode(k, v, null, null, stats);
  if (k === n.k) return avlNode(k, v, n.l, n.r, stats);
  return k < n.k
    ? avlBalance(n.k, n.v, avlPut(n.l, k, v, stats), n.r, stats)
    : avlBalance(n.k, n.v, n.l, avlPut(n.r, k, v, stats), stats);
}
function avlDel(n, k, stats) {
  stats.mapVisits++;
  if (!n) return null;
  if (k < n.k) return avlBalance(n.k, n.v, avlDel(n.l, k, stats), n.r, stats);
  if (k > n.k) return avlBalance(n.k, n.v, n.l, avlDel(n.r, k, stats), stats);
  if (!n.l) return n.r;
  if (!n.r) return n.l;
  let m = n.r;
  while (m.l) m = m.l;
  return avlBalance(m.k, m.v, n.l, avlDel(n.r, m.k, stats), stats);
}
function avlGet(n, k, stats) {
  while (n) {
    stats.mapVisits++;
    if (k === n.k) return n.v;
    n = k < n.k ? n.l : n.r;
  }
  return null;
}
function avlFloor(n, k, stats) {
  let found = null;
  while (n) {
    stats.mapVisits++;
    if (n.k <= k) {
      found = n;
      n = n.r;
    } else {
      n = n.l;
    }
  }
  return found;
}

const makeStats = () => ({docVisits: 0, docAlloc: 0, mapVisits: 0, mapAlloc: 0});

export class HierarchicalVersionedIndex {
  constructor(fanout = 16) {
    this.B = fanout;
    this.serial = 0;
  }

  piece(baseTokenId, length, text) {
    return Object.freeze({baseTokenId, length, text});
  }

  // Build a persistent text-block leaf with a B+ piece tree
  buildTextBlock(id, type, pieces, ctx) {
    const partition = items => {
      if (!items.length) return [[]];
      const count = Math.ceil(items.length / this.B);
      const out = [];
      let off = 0;
      for (let i = 0; i < count; i++) {
        const sz = Math.ceil((items.length - off) / (count - i));
        out.push(items.slice(off, off + sz));
        off += sz;
      }
      return out;
    };
    const makeSeqNode = (isLeaf, children) => {
      const seqId = `seq:${++this.serial}`;
      const contentLen = children.reduce((s, ch) => s + ch.length, 0);
      const node = Object.freeze({id: seqId, isLeaf, children: Object.freeze([...children]), length: contentLen});
      ctx.stats.docAlloc++;
      children.forEach((ch, idx) => {
        if (isLeaf) {
          ctx.tokens = avlPut(ctx.tokens, ch.baseTokenId, Object.freeze({piece: ch, seqLeaf: node, indexInLeaf: idx}), ctx.stats);
        } else {
          ctx.parents = avlPut(ctx.parents, ch.id, Object.freeze({parent: node, index: idx, kind: 'seq'}), ctx.stats);
        }
      });
      return node;
    };
    let level = partition(pieces).map(g => makeSeqNode(true, g));
    while (level.length > 1) {
      level = partition(level).map(g => makeSeqNode(false, g));
    }
    const textRoot = level[0];
    const blockNode = Object.freeze({
      id,
      type,
      isBlockLeaf: true,
      textRoot,
      contentLen: textRoot.length,
      leafCount: 1
    });
    ctx.stats.docAlloc++;
    ctx.parents = avlPut(ctx.parents, textRoot.id, Object.freeze({parent: blockNode, index: 0, kind: 'blockRoot'}), ctx.stats);
    ctx.leaves = avlPut(ctx.leaves, id, blockNode, ctx.stats);
    return blockNode;
  }

  buildBlockObject(id, objectTokenId, ctx, tableCells = null) {
    const objNode = Object.freeze({
      id,
      type: 'object',
      objectTokenId,
      isBlockLeaf: true,
      contentLen: 1,
      leafCount: 1,
      tableCells: tableCells ? Object.freeze([...tableCells]) : null
    });
    ctx.stats.docAlloc++;
    ctx.tokens = avlPut(ctx.tokens, objectTokenId, Object.freeze({piece: {baseTokenId: objectTokenId, length: 1}, blockObject: objNode}), ctx.stats);
    ctx.leaves = avlPut(ctx.leaves, id, objNode, ctx.stats);
    return objNode;
  }

  // Build a container (flow, list, item, quote) with B+ child-sequence tree aggregating (contentLen, leafCount)
  buildContainer(id, type, children, ctx) {
    const partition = items => {
      const count = Math.max(1, Math.ceil(items.length / this.B));
      const out = [];
      let off = 0;
      for (let i = 0; i < count; i++) {
        const sz = Math.ceil((items.length - off) / (count - i));
        out.push(items.slice(off, off + sz));
        off += sz;
      }
      return out;
    };
    const makeChildSeqNode = (isLeaf, kids) => {
      const seqId = `cseq:${++this.serial}`;
      const contentLen = kids.reduce((s, k) => s + k.contentLen, 0);
      const leafCount = kids.reduce((s, k) => s + k.leafCount, 0);
      const node = Object.freeze({id: seqId, isChildSeq: true, isLeaf, children: Object.freeze([...kids]), contentLen, leafCount});
      ctx.stats.docAlloc++;
      kids.forEach((k, idx) => {
        ctx.parents = avlPut(ctx.parents, k.id, Object.freeze({parent: node, index: idx, kind: 'childSeq'}), ctx.stats);
      });
      return node;
    };
    let level = partition(children).map(g => makeChildSeqNode(true, g));
    while (level.length > 1) {
      level = partition(level).map(g => makeChildSeqNode(false, g));
    }
    const childSeqRoot = level[0];
    const containerNode = Object.freeze({
      id,
      type,
      isContainer: true,
      childSeqRoot,
      contentLen: childSeqRoot.contentLen,
      leafCount: childSeqRoot.leafCount
    });
    ctx.stats.docAlloc++;
    ctx.parents = avlPut(ctx.parents, childSeqRoot.id, Object.freeze({parent: containerNode, index: 0, kind: 'containerRoot'}), ctx.stats);
    return containerNode;
  }

  // Compute the exact flow-projected start offset and preceding leaf count of any leaf block by climbing ParentMap
  leafProjectedPosition(version, leafNode, stats = makeStats(), bugSingleMeasureOnly = false) {
    let cur = leafNode;
    let precedingContent = 0;
    let precedingLeaves = 0;
    stats.docVisits++;
    while (cur.type !== 'flow') {
      const link = avlGet(version.parents, cur.id, stats);
      if (!link) return null; // Not attached to any live flow in this version
      const p = link.parent;
      if (link.kind === 'childSeq') {
        for (let i = 0; i < link.index; i++) {
          precedingContent += p.children[i].contentLen;
          precedingLeaves += p.children[i].leafCount;
        }
      }
      cur = p;
      stats.docVisits++;
    }
    // Each preceding leaf in the same flow is followed by 1 BoundaryUnit (\n) before our leaf!
    const projectedOffset = bugSingleMeasureOnly ? precedingContent : precedingContent + precedingLeaves;
    return {flowId: cur.id, projectedOffset, precedingLeaves, stats};
  }

  // Resolve any token (text unit, inlineAtom, blockObject, or boundary:${rightLeafId}) to (flowId, offset)
  rank(version, query, {bugSingleMeasureOnly = false, bugIgnoreFirstLeafBoundary = false} = {}) {
    const stats = makeStats();
    if (typeof query === 'object' && query.kind === 'boundary') {
      const rightLeaf = avlGet(version.leaves, query.rightLeafId, stats);
      if (!rightLeaf) return {flowId: null, rank: null, stats};
      const pos = this.leafProjectedPosition(version, rightLeaf, stats, bugSingleMeasureOnly);
      if (!pos) return {flowId: null, rank: null, stats};
      // F08A_2: If rightLeaf became the first leaf of its flow (precedingLeaves === 0), boundary:${rightLeafId} no longer exists!
      if (!bugIgnoreFirstLeafBoundary && pos.precedingLeaves === 0) {
        return {flowId: null, rank: null, stats};
      }
      return {flowId: pos.flowId, rank: pos.projectedOffset - 1, stats};
    }

    const token = typeof query === 'number' ? query : query.tokenId;
    const entryNode = avlFloor(version.tokens, token, stats);
    if (!entryNode) return {flowId: null, rank: null, stats};
    const e = entryNode.v;
    if (token >= e.piece.baseTokenId + e.piece.length) return {flowId: null, rank: null, stats};

    if (e.blockObject) {
      const pos = this.leafProjectedPosition(version, e.blockObject, stats, bugSingleMeasureOnly);
      if (!pos) return {flowId: null, rank: null, stats};
      return {flowId: pos.flowId, rank: pos.projectedOffset, stats};
    }

    // Text piece inside a text block's B+ sequence
    let internalOffset = token - e.piece.baseTokenId;
    for (let i = 0; i < e.indexInLeaf; i++) {
      internalOffset += e.seqLeaf.children[i].length;
    }
    let cur = e.seqLeaf;
    stats.docVisits++;
    while (!cur.isBlockLeaf) {
      const link = avlGet(version.parents, cur.id, stats);
      if (!link) return {flowId: null, rank: null, stats};
      if (link.kind === 'seq') {
        for (let i = 0; i < link.index; i++) internalOffset += link.parent.children[i].length;
      }
      cur = link.parent;
      stats.docVisits++;
    }
    const blockPos = this.leafProjectedPosition(version, cur, stats, bugSingleMeasureOnly);
    if (!blockPos) return {flowId: null, rank: null, stats};
    return {flowId: blockPos.flowId, rank: blockPos.projectedOffset + internalOffset, stats};
  }

  // Path-copy local text insertion inside a text block in a hierarchical version
  insertInTextBlock(version, blockId, offsetInBlock, newPiece) {
    const stats = makeStats();
    const ctx = {tokens: version.tokens, parents: version.parents, leaves: version.leaves, stats};
    const oldBlock = avlGet(ctx.leaves, blockId, stats);
    if (!oldBlock) throw Error('BlockNotFound');

    const retireSeq = n => {
      ctx.parents = avlDel(ctx.parents, n.id, stats);
      if (n.isLeaf) {
        for (const p of n.children) ctx.tokens = avlDel(ctx.tokens, p.baseTokenId, stats);
      }
    };
    const makeSeqNode = (isLeaf, children) => {
      const seqId = `seq:${++this.serial}`;
      const contentLen = children.reduce((s, ch) => s + ch.length, 0);
      const node = Object.freeze({id: seqId, isLeaf, children: Object.freeze([...children]), length: contentLen});
      stats.docAlloc++;
      children.forEach((ch, idx) => {
        if (isLeaf) {
          ctx.tokens = avlPut(ctx.tokens, ch.baseTokenId, Object.freeze({piece: ch, seqLeaf: node, indexInLeaf: idx}), stats);
        } else {
          ctx.parents = avlPut(ctx.parents, ch.id, Object.freeze({parent: node, index: idx, kind: 'seq'}), stats);
        }
      });
      return node;
    };
    const visitSeq = (n, pos) => {
      stats.docVisits++;
      const children = [...n.children];
      retireSeq(n);
      if (n.isLeaf) {
        let acc = 0, i = 0;
        while (i < children.length && acc + children[i].length <= pos) {
          acc += children[i].length;
          i++;
        }
        if (i < children.length && pos > acc) {
          const old = children[i], k = pos - acc;
          children.splice(
            i,
            1,
            this.piece(old.baseTokenId, k, old.text.slice(0, k)),
            newPiece,
            this.piece(old.baseTokenId + k, old.length - k, old.text.slice(k))
          );
        } else {
          children.splice(i, 0, newPiece);
        }
      } else {
        let acc = 0, i = 0;
        while (i < children.length - 1 && acc + children[i].length <= pos) {
          acc += children[i].length;
          i++;
        }
        children.splice(i, 1, ...visitSeq(children[i], pos - acc));
      }
      if (children.length <= this.B) return [makeSeqNode(n.isLeaf, children)];
      const mid = Math.floor(children.length / 2);
      return [makeSeqNode(n.isLeaf, children.slice(0, mid)), makeSeqNode(n.isLeaf, children.slice(mid))];
    };

    const roots = visitSeq(oldBlock.textRoot, offsetInBlock);
    const newTextRoot = roots.length === 1 ? roots[0] : makeSeqNode(false, roots);
    const newBlock = Object.freeze({
      ...oldBlock,
      textRoot: newTextRoot,
      contentLen: newTextRoot.length
    });
    stats.docAlloc++;
    ctx.parents = avlPut(ctx.parents, newTextRoot.id, Object.freeze({parent: newBlock, index: 0, kind: 'blockRoot'}), stats);
    ctx.leaves = avlPut(ctx.leaves, blockId, newBlock, stats);

    // Path-copy ancestor child-sequence and container nodes up to the Flow root
    let oldChild = oldBlock;
    let newChild = newBlock;
    while (oldChild.type !== 'flow') {
      const link = avlGet(version.parents, oldChild.id, stats);
      if (!link) break;
      const oldParent = link.parent;
      ctx.parents = avlDel(ctx.parents, oldChild.id, stats);
      if (link.kind === 'childSeq') {
        const kids = [...oldParent.children];
        kids[link.index] = newChild;
        const seqId = `cseq:${++this.serial}`;
        const newParent = Object.freeze({
          ...oldParent,
          id: seqId,
          children: Object.freeze(kids),
          contentLen: kids.reduce((s, k) => s + k.contentLen, 0),
          leafCount: kids.reduce((s, k) => s + k.leafCount, 0)
        });
        stats.docAlloc++;
        kids.forEach((k, idx) => {
          ctx.parents = avlPut(ctx.parents, k.id, Object.freeze({parent: newParent, index: idx, kind: 'childSeq'}), stats);
        });
        oldChild = oldParent;
        newChild = newParent;
      } else if (link.kind === 'containerRoot') {
        const newContainer = Object.freeze({
          ...oldParent,
          childSeqRoot: newChild,
          contentLen: newChild.contentLen,
          leafCount: newChild.leafCount
        });
        stats.docAlloc++;
        ctx.parents = avlPut(ctx.parents, newChild.id, Object.freeze({parent: newContainer, index: 0, kind: 'containerRoot'}), stats);
        oldChild = oldParent;
        newChild = newContainer;
      }
    }

    return Object.freeze({
      flowRoot: newChild,
      tokens: ctx.tokens,
      parents: ctx.parents,
      leaves: ctx.leaves,
      work: Object.freeze({...stats})
    });
  }
}

// ============================================================================
// PART 2: Canonical `editor-document/1` UTF-16LE Serializer & Journal Recovery
// ============================================================================

export function encodeUtf16LEBase64(str) {
  const buf = Buffer.alloc(str.length * 2);
  for (let i = 0; i < str.length; i++) {
    buf.writeUInt16LE(str.charCodeAt(i), i * 2);
  }
  return {unitCount: str.length, utf16leBase64: buf.toString('base64')};
}

export function decodeUtf16LEBase64({unitCount, utf16leBase64}) {
  if (!Number.isSafeInteger(unitCount) || unitCount < 0 || typeof utf16leBase64 !== 'string') {
    throw Error('InvalidCanonicalTextChunk');
  }
  const buf = Buffer.from(utf16leBase64, 'base64');
  if (buf.length !== unitCount * 2) throw Error('Utf16LengthMismatch');
  const chars = new Array(unitCount);
  for (let i = 0; i < unitCount; i++) {
    chars[i] = String.fromCharCode(buf.readUInt16LE(i * 2));
  }
  return chars.join('');
}

export function serializeCanonicalDocument1(doc) {
  validateDocument(doc);
  const encodeNode = n => {
    const out = {id: n.id, type: n.type, props: structuredClone(n.props ?? {})};
    if (n.emptyBlockAttributes) out.emptyBlockAttributes = structuredClone(n.emptyBlockAttributes);
    if (n.units) {
      out.textChunk = encodeUtf16LEBase64(n.units.map(u => u.char).join(''));
      out.unitMeta = n.units.map(u => ({
        kind: u.kind ?? 'text',
        tokenId: u.tokenId,
        ...(u.nodeId ? {nodeId: u.nodeId} : {}),
        ...(u.locked ? {locked: true} : {}),
        ...(u.props ? {props: structuredClone(u.props)} : {}),
        attrs: structuredClone(u.attrs ?? {})
      }));
    }
    if (n.children) out.children = n.children.map(encodeNode);
    if (n.type === 'object' && n.props?.kind === 'table') {
      out.props.rows = n.props.rows.map(r => ({
        id: r.id,
        cells: r.cells.map(cell => ({
          id: cell.id,
          props: structuredClone(cell.props ?? {}),
          flow: encodeNode(cell.flow)
        }))
      }));
    }
    return out;
  };
  const payload = {
    format: 'editor-document/1',
    endianness: 'LE',
    id: doc.id,
    resources: structuredClone(doc.props?.resources ?? {}),
    annotations: structuredClone(doc.props?.annotations ?? []),
    body: encodeNode(doc.children[0])
  };
  return JSON.stringify(payload);
}

export function deserializeCanonicalDocument1(jsonStr) {
  const data = JSON.parse(jsonStr);
  if (data.format !== 'editor-document/1' || data.endianness !== 'LE') {
    throw Error('UnsupportedCanonicalFormat');
  }
  const decodeNode = n => {
    const out = {id: n.id, type: n.type, props: structuredClone(n.props ?? {})};
    if (n.emptyBlockAttributes) out.emptyBlockAttributes = structuredClone(n.emptyBlockAttributes);
    if (n.textChunk) {
      const str = decodeUtf16LEBase64(n.textChunk);
      if (!Array.isArray(n.unitMeta) || n.unitMeta.length !== str.length) {
        throw Error('CanonicalUnitMetadataMismatch');
      }
      out.units = n.unitMeta.map((m, i) => ({
        kind: m.kind ?? 'text',
        tokenId: m.tokenId,
        ...(m.nodeId ? {nodeId: m.nodeId} : {}),
        ...(m.locked ? {locked: true} : {}),
        ...(m.props ? {props: structuredClone(m.props)} : {}),
        char: str[i],
        attrs: structuredClone(m.attrs ?? {}),
        originSessionId: null
      }));
    }
    if (n.children) out.children = n.children.map(decodeNode);
    if (n.type === 'object' && n.props?.kind === 'table') {
      out.props.rows = n.props.rows.map(r => ({
        id: r.id,
        cells: r.cells.map(cell => ({
          id: cell.id,
          ...(cell.props && Object.keys(cell.props).length ? {props: structuredClone(cell.props)} : {}),
          flow: decodeNode(cell.flow)
        }))
      }));
    }
    return out;
  };
  const doc = {
    id: data.id,
    type: 'document',
    props: {
      resources: structuredClone(data.resources ?? {}),
      annotations: structuredClone(data.annotations ?? [])
    },
    children: [decodeNode(data.body)]
  };
  validateDocument(doc);
  return doc;
}

// Framed binary journal & checkpoint manager for crash-recovery testing
export class DurableRecoveryJournal {
  constructor() {
    this.checkpointBytes = null;
    this.checkpointTmpBytes = null;
    this.parentDirSynced = false;
    this.journalBytes = Buffer.alloc(0);
    this.lastHash = '0'.repeat(64);
    this.txSerial = 0;
    this.acknowledgedRevision = 0;
    this.pendingAckCallbacks = [];
  }

  encodeJournalFrame({txId, beforeRev, afterRev, prevHash, committedDoc, visibleDoc, isComposing}) {
    const bodyJson = JSON.stringify({
      txId,
      beforeRev,
      afterRev,
      prevHash,
      isComposing: !!isComposing,
      committedCanonical: serializeCanonicalDocument1(committedDoc),
      visibleCanonical: isComposing ? serializeCanonicalDocument1(visibleDoc) : null
    });
    const bodyBuf = Buffer.from(bodyJson, 'utf8');
    const checksum = createHash('sha256').update(bodyBuf).digest('hex');
    const frameHash = createHash('sha256').update(`${prevHash}:${checksum}`).digest('hex');
    const header = Buffer.alloc(16 + 64 + 64);
    header.write('EDJ1', 0, 4, 'ascii');
    header.writeUInt32LE(bodyBuf.length, 4);
    header.writeUInt32LE(beforeRev, 8);
    header.writeUInt32LE(afterRev, 12);
    header.write(checksum, 16, 64, 'ascii');
    header.write(frameHash, 80, 64, 'ascii');
    const trailer = Buffer.from('END1', 'ascii');
    return {frame: Buffer.concat([header, bodyBuf, trailer]), frameHash};
  }

  appendSnapshot(snapshot, {coalesce = false, failWriteAfterBytes = null} = {}) {
    const txId = ++this.txSerial;
    const beforeRev = this.acknowledgedRevision;
    const afterRev = snapshot.documentRevision;
    const isComposing = snapshot.active !== null;
    const {frame, frameHash} = this.encodeJournalFrame({
      txId,
      beforeRev,
      afterRev,
      prevHash: this.lastHash,
      committedDoc: snapshot.committedDocument,
      visibleDoc: snapshot.document,
      isComposing
    });
    if (failWriteAfterBytes !== null) {
      // Simulate torn write at exact byte boundary; do NOT advance acknowledgedRevision or fire ack!
      this.journalBytes = Buffer.concat([this.journalBytes, frame.subarray(0, failWriteAfterBytes)]);
      return {status: 'TornWriteSimulated', acknowledgedRevision: this.acknowledgedRevision};
    }
    this.journalBytes = Buffer.concat([this.journalBytes, frame]);
    this.lastHash = frameHash;
    if (!coalesce) {
      this.acknowledgedRevision = afterRev;
      for (const cb of this.pendingAckCallbacks.splice(0)) cb({status: 'DurableAck', revision: afterRev});
    }
    return {status: 'Appended', txId, frameHash, acknowledgedRevision: this.acknowledgedRevision};
  }

  writeCheckpoint(snapshot, {crashBeforeRename = false, crashBeforeJournalTruncate = false} = {}) {
    const bodyJson = JSON.stringify({
      format: 'editor-checkpoint/1',
      baseRev: snapshot.documentRevision,
      lastHash: this.lastHash,
      committedCanonical: serializeCanonicalDocument1(snapshot.committedDocument),
      visibleCanonical: snapshot.active ? serializeCanonicalDocument1(snapshot.document) : null,
      isComposing: snapshot.active !== null
    });
    const bodyBuf = Buffer.from(bodyJson, 'utf8');
    const checksum = createHash('sha256').update(bodyBuf).digest('hex');
    const header = Buffer.alloc(12 + 64);
    header.write('EDC1', 0, 4, 'ascii');
    header.writeUInt32LE(bodyBuf.length, 4);
    header.writeUInt32LE(snapshot.documentRevision, 8);
    header.write(checksum, 12, 64, 'ascii');
    const trailer = Buffer.from('ENDC', 'ascii');
    this.checkpointTmpBytes = Buffer.concat([header, bodyBuf, trailer]);
    if (crashBeforeRename) {
      return {status: 'CrashedBeforeRename'};
    }
    // Atomic rename checkpoint.tmp -> checkpoint.dat + parentDir fsync
    this.checkpointBytes = this.checkpointTmpBytes;
    this.checkpointTmpBytes = null;
    this.parentDirSynced = true;
    this.acknowledgedRevision = Math.max(this.acknowledgedRevision, snapshot.documentRevision);
    if (crashBeforeJournalTruncate) {
      return {status: 'CrashedBeforeJournalTruncate'};
    }
    this.journalBytes = Buffer.alloc(0);
    return {status: 'Checkpointed', baseRev: snapshot.documentRevision};
  }

  recover({naiveDvOnlyRecovery = false} = {}) {
    let currentRev = 0;
    let currentHash = '0'.repeat(64);
    let committedDoc = null;
    let visibleDoc = null;
    let isComposing = false;
    const diagnostics = [];

    // 1. Ignore any unrenamed checkpoint.tmp
    if (this.checkpointTmpBytes) {
      diagnostics.push({type: 'IgnoredOrphanCheckpointTmp', bytes: this.checkpointTmpBytes.length});
    }

    // 2. Load checkpoint.dat if valid
    if (this.checkpointBytes) {
      const buf = this.checkpointBytes;
      if (buf.length >= 80 && buf.subarray(0, 4).toString('ascii') === 'EDC1') {
        const len = buf.readUInt32LE(4);
        const checksum = buf.subarray(12, 76).toString('ascii');
        if (buf.length === 76 + len + 4 && buf.subarray(76 + len, 80 + len).toString('ascii') === 'ENDC') {
          const bodyBuf = buf.subarray(76, 76 + len);
          const actualSum = createHash('sha256').update(bodyBuf).digest('hex');
          if (actualSum === checksum) {
            const parsed = JSON.parse(bodyBuf.toString('utf8'));
            currentRev = parsed.baseRev;
            currentHash = parsed.lastHash;
            committedDoc = deserializeCanonicalDocument1(parsed.committedCanonical);
            visibleDoc = parsed.visibleCanonical ? deserializeCanonicalDocument1(parsed.visibleCanonical) : committedDoc;
            isComposing = !!parsed.isComposing;
          } else {
            throw Error('CorruptCheckpointChecksum');
          }
        }
      }
    }

    // 3. Scan journal frames sequentially
    let offset = 0;
    const jBuf = this.journalBytes;
    while (offset < jBuf.length) {
      if (offset + 144 > jBuf.length) {
        diagnostics.push({type: 'JournalTailTruncated', offset, discardedBytes: jBuf.length - offset, reason: 'IncompleteHeader'});
        break;
      }
      const magic = jBuf.subarray(offset, offset + 4).toString('ascii');
      if (magic !== 'EDJ1') {
        diagnostics.push({type: 'JournalTailTruncated', offset, discardedBytes: jBuf.length - offset, reason: 'InvalidFrameMagic'});
        break;
      }
      const bodyLen = jBuf.readUInt32LE(offset + 4);
      const afterRev = jBuf.readUInt32LE(offset + 12);
      const expectedChecksum = jBuf.subarray(offset + 16, offset + 80).toString('ascii');
      const expectedFrameHash = jBuf.subarray(offset + 80, offset + 144).toString('ascii');
      const totalFrameLen = 144 + bodyLen + 4;
      if (offset + totalFrameLen > jBuf.length) {
        diagnostics.push({type: 'JournalTailTruncated', offset, discardedBytes: jBuf.length - offset, reason: 'TornPayload'});
        break;
      }
      const trailer = jBuf.subarray(offset + 144 + bodyLen, offset + totalFrameLen).toString('ascii');
      const bodyBuf = jBuf.subarray(offset + 144, offset + 144 + bodyLen);
      const actualChecksum = createHash('sha256').update(bodyBuf).digest('hex');
      if (trailer !== 'END1' || actualChecksum !== expectedChecksum) {
        diagnostics.push({type: 'JournalTailTruncated', offset, discardedBytes: jBuf.length - offset, reason: 'ChecksumOrTrailerMismatch'});
        break;
      }
      const parsed = JSON.parse(bodyBuf.toString('utf8'));
      // Idempotent skip of already-checkpointed or duplicate frames
      if (afterRev <= currentRev) {
        offset += totalFrameLen;
        continue;
      }
      // Enforce strict hash/revision continuity (no interior gap replay!)
      if (parsed.beforeRev !== currentRev || parsed.prevHash !== currentHash) {
        diagnostics.push({type: 'JournalTailTruncated', offset, discardedBytes: jBuf.length - offset, reason: 'BrokenChainOrInteriorGap'});
        break;
      }
      committedDoc = deserializeCanonicalDocument1(parsed.committedCanonical);
      visibleDoc = parsed.visibleCanonical ? deserializeCanonicalDocument1(parsed.visibleCanonical) : committedDoc;
      isComposing = !!parsed.isComposing;
      currentRev = afterRev;
      currentHash = expectedFrameHash;
      offset += totalFrameLen;
    }

    // 4. Construct recovered RepairedDocumentModel
    if (naiveDvOnlyRecovery) {
      const m = new RepairedDocumentModel(visibleDoc);
      return {model: m, recoveredRevision: currentRev, diagnostics, hadProvisionalOverlay: isComposing};
    }

    // Repaired contract: restore committedDoc (Dc) first, and if active composition (Dv != Dc) was present,
    // push one undoable RecoveredComposition history group so neither secondary cells in Dc nor preedit text in Dv is lost!
    const m = new RepairedDocumentModel(committedDoc);
    if (isComposing && JSON.stringify(committedDoc) !== JSON.stringify(visibleDoc)) {
      const beforeBundle = m._bundle();
      m._install(structuredClone(visibleDoc), {flowId: 'body', selection: [0, 0], kind: {type: 'text'}});
      m._historyPush(beforeBundle, m._bundle(), 'RecoveredComposition');
      m._publish();
    }
    return {model: m, recoveredRevision: currentRev, diagnostics, hadProvisionalOverlay: isComposing};
  }
}

export function runStorageAndRecoveryProbes() {
  // --------------------------------------------------------------------------
  // Test 1 (F08A_1): 2-Component Monoid Measure (contentUtf16Length, leafBlockCount)
  //                  vs Single-Measure in Nested Hierarchy + Table Cell Isolation
  // --------------------------------------------------------------------------
  const idx = new HierarchicalVersionedIndex(4);
  const ctx = {tokens: null, parents: null, leaves: null, stats: makeStats()};
  // Flow structure:
  // - p1 ("abc", len 3) [projected 0..3]
  // - boundary:p2 [projected 3]
  // - list L -> item i1 -> quote q1 -> [p2 ("de", len 2) [projected 4..6], boundary:p3 [6], p3 ("f", len 1) [projected 7..8]]
  // - boundary:tbl [projected 8]
  // - table tbl (\ufffc, len 1, with cell flow cellF00 containing "CELL_TEXT" len 9) [projected 9..10]
  // - boundary:p4 [projected 10]
  // - p4 ("gh", len 2) [projected 11..13]
  const p1 = idx.buildTextBlock('p1', 'paragraph', [idx.piece(100, 3, 'abc')], ctx);
  const p2 = idx.buildTextBlock('p2', 'paragraph', [idx.piece(200, 2, 'de')], ctx);
  const p3 = idx.buildTextBlock('p3', 'paragraph', [idx.piece(300, 1, 'f')], ctx);
  const q1 = idx.buildContainer('q1', 'quote', [p2, p3], ctx);
  const i1 = idx.buildContainer('i1', 'item', [q1], ctx);
  const L = idx.buildContainer('L', 'list', [i1], ctx);

  const cellP00 = idx.buildTextBlock('p00', 'paragraph', [idx.piece(900, 9, 'CELL_TEXT')], ctx);
  const cellF00 = idx.buildContainer('f00', 'flow', [cellP00], ctx);
  const tbl = idx.buildBlockObject('tbl', 500, ctx, [cellF00]);

  const p4 = idx.buildTextBlock('p4', 'paragraph', [idx.piece(400, 2, 'gh')], ctx);
  const bodyFlow = idx.buildContainer('body', 'flow', [p1, L, tbl, p4], ctx);
  const v0 = Object.freeze({flowRoot: bodyFlow, tokens: ctx.tokens, parents: ctx.parents, leaves: ctx.leaves});

  // Verify single-measure bug vs 2-component monoid measure on token 400 ('g' in p4):
  // Preceding leaves before p4 are: p1 (len 3), p2 (len 2), p3 (len 1), tbl (len 1) -> content sum = 7, leafCount = 4.
  // True projected offset of 'g' (token 400) in 'body' is 7 + 4 = 11!
  // Single-measure (without leafCount) gives 7 (missing the 4 inter-leaf BoundaryUnits)!
  const buggyRankP4 = idx.rank(v0, 400, {bugSingleMeasureOnly: true});
  const trueRankP4 = idx.rank(v0, 400);
  assert.equal(buggyRankP4.rank, 7, 'Single-measure without leafBlockCount undercounts by 4 BoundaryUnits');
  assert.equal(trueRankP4.flowId, 'body');
  assert.equal(trueRankP4.rank, 11, '2-component monoid measure (contentLen, leafCount) gives exact projected offset 11');

  // Verify table cell isolation: token 904 (inside cellF00) resolves to flowId: 'f00', rank: 4, and never leaks into 'body'
  const cellTokenRank = idx.rank(v0, 904);
  assert.equal(cellTokenRank.flowId, 'f00');
  assert.equal(cellTokenRank.rank, 4);

  // --------------------------------------------------------------------------
  // Test 2 (F08A_2): BoundaryUnit rank when preceding leaf is deleted
  // --------------------------------------------------------------------------
  // In v0, boundary:p2 has precedingLeaves === 1 (p1 precedes p2), so its rank is 4 - 1 = 3.
  assert.equal(idx.rank(v0, {kind: 'boundary', rightLeafId: 'p2'}).rank, 3);
  // If p1 is removed so L -> i1 -> q1 -> p2 is now the first leaf of 'body' (precedingLeaves === 0):
  const ctxNoP1 = {tokens: v0.tokens, parents: v0.parents, leaves: v0.leaves, stats: makeStats()};
  const bodyNoP1 = idx.buildContainer('body', 'flow', [L, tbl, p4], ctxNoP1);
  const vNoP1 = Object.freeze({flowRoot: bodyNoP1, tokens: ctxNoP1.tokens, parents: ctxNoP1.parents, leaves: ctxNoP1.leaves});
  // Naive lookup that only checks whether p2 is in ParentMap returns rank -1!
  assert.equal(idx.rank(vNoP1, {kind: 'boundary', rightLeafId: 'p2'}, {bugIgnoreFirstLeafBoundary: true}).rank, -1);
  // Repaired lookup checks precedingLeaves >= 1 and returns rank: null (TargetDeleted)!
  assert.equal(idx.rank(vNoP1, {kind: 'boundary', rightLeafId: 'p2'}).rank, null);

  // --------------------------------------------------------------------------
  // Test 3 (Area 3 & Area 5): Standard Capacity Fixture (1,000,000 UTF-16 units
  //                           across 10,000 blocks) + Depth Scaling d in {1,4,8,16,32}
  // --------------------------------------------------------------------------
  const buildStart = performance.now();
  const capCtx = {tokens: null, parents: null, leaves: null, stats: makeStats()};
  const capIdx = new HierarchicalVersionedIndex(32);
  const blocks10k = [];
  const sampleText = 'x'.repeat(100);
  for (let b = 0; b < 10000; b++) {
    blocks10k.push(capIdx.buildTextBlock(`b${b}`, 'paragraph', [capIdx.piece((b + 1) * 1000, 100, sampleText)], capCtx));
  }
  const capFlow = capIdx.buildContainer('body', 'flow', blocks10k, capCtx);
  let capVersion = Object.freeze({flowRoot: capFlow, tokens: capCtx.tokens, parents: capCtx.parents, leaves: capCtx.leaves});
  const buildMs = performance.now() - buildStart;
  assert.equal(capVersion.flowRoot.contentLen, 1_000_000);
  assert.equal(capVersion.flowRoot.leafCount, 10_000);

  // Measure 100 local edits across the 1,000,000-unit / 10,000-block persistent hierarchical index
  const editLatenciesMs = [];
  const editWorkSamples = [];
  for (let i = 0; i < 100; i++) {
    const targetBlockIdx = (i * 97) % 10000;
    const t0 = performance.now();
    capVersion = capIdx.insertInTextBlock(capVersion, `b${targetBlockIdx}`, 50, capIdx.piece(20_000_000 + i * 10, 1, 'Z'));
    editLatenciesMs.push(performance.now() - t0);
    if (i < 5) editWorkSamples.push(capVersion.work);
  }
  editLatenciesMs.sort((a, b) => a - b);
  const p50EditMs = editLatenciesMs[50];
  const p95EditMs = editLatenciesMs[95];
  const p99EditMs = editLatenciesMs[99];

  // Compare with full-copy clone cost of a 10,000-block tree (as used in JS reference model)
  const rawBlocks10k = c('body', 'flow', Array.from({length: 10000}, (_, i) => ({id: `b${i}`, type: 'paragraph', props: {}, units: [{tokenId: `u${i}`, char: 'a', kind: 'text', attrs: {}}]})));
  const fullCopyTimes = [];
  for (let i = 0; i < 10; i++) {
    const t0 = performance.now();
    structuredClone(rawBlocks10k);
    fullCopyTimes.push(performance.now() - t0);
  }
  fullCopyTimes.sort((a, b) => a - b);

  // Measure depth scaling d in {1, 4, 8, 16, 32}
  const depthScaling = [];
  for (const depth of [1, 4, 8, 16, 32]) {
    const dIdx = new HierarchicalVersionedIndex(16);
    const dCtx = {tokens: null, parents: null, leaves: null, stats: makeStats()};
    let curNode = dIdx.buildTextBlock('deepLeaf', 'paragraph', [dIdx.piece(1000, 10, '0123456789')], dCtx);
    for (let d = 0; d < depth; d++) {
      const sibling = dIdx.buildTextBlock(`sib:${d}`, 'paragraph', [dIdx.piece((d + 2) * 1000, 5, 'abcde')], dCtx);
      curNode = dIdx.buildContainer(`quote:${d}`, 'quote', [sibling, curNode], dCtx);
    }
    const rootFlow = dIdx.buildContainer('body', 'flow', [curNode], dCtx);
    const dVer = Object.freeze({flowRoot: rootFlow, tokens: dCtx.tokens, parents: dCtx.parents, leaves: dCtx.leaves});
    const lookup = dIdx.rank(dVer, 1005);
    const edit = dIdx.insertInTextBlock(dVer, 'deepLeaf', 5, dIdx.piece(999000, 1, 'X'));
    assert.equal(lookup.rank, depth * (5 + 1) + 5);
    depthScaling.push({
      containerDepth: depth,
      lookupRank: lookup.rank,
      lookupDocVisits: lookup.stats.docVisits,
      lookupMapVisits: lookup.stats.mapVisits,
      editDocAlloc: edit.work.docAlloc,
      editMapAlloc: edit.work.mapAlloc
    });
  }

  // --------------------------------------------------------------------------
  // Test 4 (F09): Canonical `editor-document/1` Round-Trip (Isolated Surrogates,
  //               Internal-Grapheme Attribution, Tables, Annotations, Resources)
  //               & Crash-Recovery State Machine (Multi-Cell Composition + Torn Writes)
  // --------------------------------------------------------------------------
  const {m, cmd, n, app} = driver(grid(), [0, 0], RepairedDocumentModel);
  // Insert WOMAN + ZWJ + LAPTOP with mixed internal-grapheme attributes + isolated surrogate \uD800
  cmd('selectFlow', {toFlow: 'body', offset: 0});
  const docCopy = structuredClone(m.snapshot().document); const block0 = getFlow(docCopy, 'body').children[0];
  block0.units = [
    {kind: 'text', tokenId: 'w:0', char: '\uD83D', attrs: {bold: true}, originSessionId: null},
    {kind: 'text', tokenId: 'w:1', char: '\uDC69', attrs: {bold: true}, originSessionId: null},
    {kind: 'text', tokenId: 'z:0', char: '\u200D', attrs: {bold: true}, originSessionId: null},
    {kind: 'text', tokenId: 'l:0', char: '\uD83D', attrs: {italic: true}, originSessionId: null},
    {kind: 'text', tokenId: 'l:1', char: '\uDCBB', attrs: {italic: true}, originSessionId: null},
    {kind: 'text', tokenId: 'iso:0', char: '\uD800', attrs: {underline: true}, originSessionId: null}
  ];
  m._install(docCopy, {flowId: 'body', selection: [0, 0]});
  m._publish();
  cmd('putResource', {resourceId: 'res1', resource: {uri: 'https://example.test/img.png', mime: 'image/png'}});
  cmd('addAnnotation', {flowId: 'body', range: [0, 5], props: {note: 'grapheme-note'}});

  const serialized = serializeCanonicalDocument1(m.snapshot().document);
  const roundTripped = deserializeCanonicalDocument1(serialized);
  assert.deepEqual(roundTripped, m.snapshot().document, 'editor-document/1 must preserve exact isolated surrogates and internal-grapheme attribution');

  // Crash during active multi-cell composition (selectCells c00..c11 -> preedit("n"))
  const journal = new DurableRecoveryJournal();
  journal.appendSnapshot(m.snapshot());
  journal.writeCheckpoint(m.snapshot());

  cmd('selectCells', {tableId: 't', anchorCell: 'c00', focusCell: 'c11'});
  n('preedit', {text: 'n'});
  // At this moment: in Dv, f00 is "n" and f01/f10/f11 are ""! In Dc, f00..f11 are still "A","B","C","D"!
  journal.appendSnapshot(m.snapshot());
  // Also simulate a subsequent torn write (crash mid-frame at 40 bytes)
  journal.appendSnapshot(m.snapshot(), {failWriteAfterBytes: 40});

  // 4A: Show that naive Dv-only recovery permanently loses "B", "C", "D" with no Undo!
  const naiveRec = journal.recover({naiveDvOnlyRecovery: true});
  assert.equal(raw(project(getFlow(naiveRec.model.snapshot().document, 'f11'))), '');
  assert.equal(naiveRec.model.snapshot().history.length, 0, 'Naive Dv-only recovery has 0 Undo entries, permanently losing secondary cells');

  // 4B: Show that repaired dual-(Dc, Dv) recovery preserves Dv as an undoable RecoveredComposition group over Dc!
  const safeRec = journal.recover({naiveDvOnlyRecovery: false});
  assert.equal(safeRec.diagnostics.length, 1);
  assert.equal(safeRec.diagnostics[0].reason, 'IncompleteHeader');
  assert.equal(raw(project(getFlow(safeRec.model.snapshot().document, 'f00'))), 'n');
  assert.equal(safeRec.model.snapshot().history.length, 1);
  assert.equal(safeRec.model.snapshot().history[0].kind, 'RecoveredComposition');
  // One Undo restores all 4 cells ("A", "B", "C", "D") from Dc!
  safeRec.model.dispatch({type: 'undo'});
  assert.equal(raw(project(getFlow(safeRec.model.snapshot().document, 'f00'))), 'A');
  assert.equal(raw(project(getFlow(safeRec.model.snapshot().document, 'f01'))), 'B');
  assert.equal(raw(project(getFlow(safeRec.model.snapshot().document, 'f10'))), 'C');
  assert.equal(raw(project(getFlow(safeRec.model.snapshot().document, 'f11'))), 'D');

  return {
    monoidMeasureVerification: {
      singleMeasureBugRank: buggyRankP4.rank,
      twoComponentTrueRank: trueRankP4.rank,
      cellIsolationRank: cellTokenRank
    },
    boundaryFirstLeafDeletionVerification: {
      naiveBugRank: -1,
      repairedRank: null
    },
    standardCapacity1MUnits10KBlocks: {
      totalUnits: 1_000_000,
      totalBlocks: 10_000,
      buildMs: Number(buildMs.toFixed(2)),
      persistentLocalEditMs: {
        p50: Number(p50EditMs.toFixed(4)),
        p95: Number(p95EditMs.toFixed(4)),
        p99: Number(p99EditMs.toFixed(4))
      },
      fullCopy10KBlocksCloneMedianMs: Number(fullCopyTimes[5].toFixed(2)),
      sampleEditWork: editWorkSamples[0]
    },
    depthScaling,
    canonicalAndRecoveryVerification: {
      canonicalUtf16LeRoundTripPassed: true,
      tornWriteDetectedAndTruncated: safeRec.diagnostics[0],
      naiveDvRecoveryLostSecondaryCells: true,
      dualDcDvRecoveryRestoredAllCellsOnUndo: true
    }
  };
}

if (process.argv[1]?.endsWith('probe-hierarchical-storage-and-recovery.mjs')) {
  console.log(JSON.stringify(runStorageAndRecoveryProbes(), null, 2));
}
