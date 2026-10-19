import { deepFreeze, immutableCopy, mapRequest } from './request-contract.mjs';
// Candidate B Coordinator: Pure Segment-Mapped Overlay & Committed Storage Engine (§1, §2, §4)
// Resolved & Qualified Architecture (run-20261014-07):
// - Separated committed storage (committedDoc: Dc) and provisionalOverlay (piecewise OverlaySegments)
// - Native markedWindow independent of overlay footprint
// - Segment-aware partial commit with explicit cut across zero-length deletion segments (Gap 4.4)
// - Preserves absorbed original units on partial commit + cancel (Gap 4.3)
// - Replacement rollback ownership cleanly partitioned across nested replacements (Gap 4.5)
// - Outward Unicode grapheme cluster closure via Intl.Segmenter (Gap 4.1, Defect D)
// - Interior insertion inside multi-unit segments with exact rollback ownership (Defect A)
// - Implicit preedit respects noncollapsed/reversed selection bounds (Defect B)
// - Atomic overlay materialization to eliminate history snapshot corruption (Defect C)
// - Unrelated command ticket conservation and immediate drainage on cancellation (Defect E, Finding E)
// - Full partial commit preserves active composition and queued commands; applies to committed target (Defect F, Finding C)
// - Upfront validation rejecting invalid target ranges atomically (Defect G)
// - Dynamic island and command target mapping across preceding edits (Defect H)
// - Dedicated TrackedRange target resolution and session mismatch rejection (Defect I)
// - Preserves visible content and composition history on connection turnover (Finding A, B)
// - Selection validation against proposed post-edit document length (Finding D)
// - Connection epoch admission on all native callbacks (Finding F)
// - Receipt cache eviction on connection replacement and sequence reuse across epochs (Finding F)
// - Receipts publish actual post-reducer document revisions and selections
// - Real failure injection across 6 transaction stages with atomic pre-transaction state rollback
// - Executable undo and redo implementation with branch invalidation

export function cloneAttrs(attrs) {
  if (!attrs) return null;
  return attrs.map(a => ({ ...a }));
}

export function areAttrsEqual(a1, a2) {
  if (!a1 || !a2) return a1 === a2;
  if (a1.length !== a2.length) return false;
  for (let i = 0; i < a1.length; i++) {
    const k1 = Object.keys(a1[i]);
    const k2 = Object.keys(a2[i]);
    if (k1.length !== k2.length) return false;
    for (const k of k1) {
      if (a1[i][k] !== a2[i][k]) return false;
    }
  }
  return true;
}

// Canonical Unicode Extended Grapheme Cluster Outward Closure
export function expandToGraphemeBoundaries(text, [start, end]) {
  if (text.length === 0) return [0, 0];
  const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
  let expandedStart = start;
  let expandedEnd = end;

  for (const segment of segmenter.segment(text)) {
    const segStart = segment.index;
    const segEnd = segStart + segment.segment.length;

    // Check overlap with [start, end)
    if (start === end) {
      // Collapsed caret strictly inside a cluster expands to the whole cluster
      if (start > segStart && start < segEnd) {
        expandedStart = Math.min(expandedStart, segStart);
        expandedEnd = Math.max(expandedEnd, segEnd);
      }
    } else {
      // Non-empty range intersecting segment
      if (start < segEnd && end > segStart) {
        expandedStart = Math.min(expandedStart, segStart);
        expandedEnd = Math.max(expandedEnd, segEnd);
      }
    }
  }
  return [expandedStart, expandedEnd];
}

// Transaction-Owned Exact Segment-Mapped Target Mapping on Rollback (§4.3, Defect 1)
export function mapVisiblePointToCommittedOnCancel(p, overlaySegments, committedDocLen) {
  if (!overlaySegments || overlaySegments.length === 0) {
    return { type: 'Committed', dcPos: p };
  }

  let curVis = 0;
  let curDc = 0;

  for (const seg of overlaySegments) {
    const unchLen = seg.dcStart - curDc;
    if (p < curVis + unchLen) {
      const dcPos = curDc + (p - curVis);
      return { type: 'Committed', dcPos };
    }
    curVis += unchLen;
    curDc = seg.dcStart;

    const segVisLen = seg.units ? seg.units.length : 0;
    const segDcLen = seg.dcEnd - seg.dcStart;

    if (p <= curVis + segVisLen) {
      const offsetInSeg = p - curVis;
      return {
        type: 'SegmentSpan',
        seg,
        offsetInSeg,
        segVisLen,
        segDcLen,
        dcStart: seg.dcStart,
        dcEnd: seg.dcEnd,
        isAtStart: offsetInSeg === 0,
        isAtEnd: offsetInSeg === segVisLen
      };
    }
    curVis += segVisLen;
    curDc = seg.dcEnd;
  }

  const remainingVis = p - curVis;
  const dcPos = curDc + remainingVis;
  return { type: 'Committed', dcPos };
}

export function mapTrackedRangeOnCancel(range, overlaySegments, committedDoc) {
  if (!range || range.length !== 2) return { status: 'Rejected', reason: 'InvalidTargetRange' };
  const committedDocLen = committedDoc.length;

  let visibleLen = committedDocLen;
  if (overlaySegments) {
    for (const seg of overlaySegments) {
      visibleLen += (seg.units ? seg.units.length : 0) - (seg.dcEnd - seg.dcStart);
    }
  }

  const [u, v] = [Math.min(range[0], range[1]), Math.max(range[0], range[1])];
  if (u < 0 || v > visibleLen || u > v) {
    return { status: 'Rejected', reason: 'TargetInvalidated' };
  }

  if (u === v) {
    let curVis = 0;
    let curDc = 0;
    let dcPos = null;

    if (overlaySegments) {
      for (const seg of overlaySegments) {
        const unchLen = seg.dcStart - curDc;
        if (u <= curVis + unchLen) {
          dcPos = curDc + (u - curVis);
          break;
        }
        curVis += unchLen;
        curDc = seg.dcStart;

        const segVisLen = seg.units ? seg.units.length : 0;
        if (u <= curVis + segVisLen) {
          dcPos = seg.dcStart;
          break;
        }
        curVis += segVisLen;
        curDc = seg.dcEnd;
      }
    }
    if (dcPos === null) {
      dcPos = curDc + (u - curVis);
    }
    dcPos = Math.max(0, Math.min(committedDocLen, dcPos));
    return { status: 'Applied', range: [dcPos, dcPos], ranges: [[dcPos, dcPos]] };
  }

  const visUnits = [];
  let curDc = 0;
  if (overlaySegments && overlaySegments.length > 0) {
    for (const seg of overlaySegments) {
      while (curDc < seg.dcStart) {
        visUnits.push(committedDoc[curDc]);
        curDc++;
      }
      if (seg.units) {
        for (const unit of seg.units) {
          visUnits.push(unit);
        }
      }
      curDc = seg.dcEnd;
    }
  }
  while (curDc < committedDocLen) {
    visUnits.push(committedDoc[curDc]);
    curDc++;
  }

  const coveredVisUnits = visUnits.slice(u, v);
  const survivingIndices = [];
  for (const cu of coveredVisUnits) {
    if (!cu || !cu.tokenId) continue;
    const idx = committedDoc.findIndex(du => du.tokenId === cu.tokenId);
    if (idx !== -1) {
      survivingIndices.push(idx);
    }
  }

  if (survivingIndices.length === 0) {
    return { status: 'Rejected', reason: 'TargetDeleted' };
  }

  survivingIndices.sort((a, b) => a - b);

  // Group surviving token positions into maximal contiguous intervals
  const runs = [];
  let currentRun = [survivingIndices[0], survivingIndices[0] + 1];
  for (let i = 1; i < survivingIndices.length; i++) {
    const idx = survivingIndices[i];
    if (idx === currentRun[1]) {
      currentRun[1] = idx + 1;
    } else {
      runs.push([...currentRun]);
      currentRun = [idx, idx + 1];
    }
  }
  runs.push([...currentRun]);

  const primaryRange = [...runs[0]];
  return {
    status: 'Applied',
    range: primaryRange,
    ranges: runs,
    isFragmented: runs.length > 1
  };
}

// Immutable Request Envelope (§4.1, Probe 1)
export class RequestEnvelope {
  constructor({
    ticketId,
    origin = "external",
    epoch = 0,
    seq = null,
    sessionId = null,
    basisRevision = 0,
    basisCoordinate = null,
    basisTokenIds = null,
    payload = null,
    fn = null
  }) {
    this.ticketId = ticketId;
    this.origin = origin;
    this.epoch = epoch;
    this.seq = seq;
    this.sessionId = sessionId;
    this.basisRevision = basisRevision;
    this.basisCoordinate = basisCoordinate ? Object.freeze([...basisCoordinate]) : null;
    this.basisTokenIds = basisTokenIds ? Object.freeze([...basisTokenIds]) : null;
    this.payload = payload ? Object.freeze(JSON.parse(JSON.stringify(payload))) : null;
    this.fn = fn;
    Object.freeze(this);
  }
}
export class CandidateBCoordinator {
  constructor(initialText = "Hello", initialAttrs = null, initialSelection = [0, 0]) {
    this._epoch = 0;
    this._connectionState = 'Active';
    this.sequenceCounter = 0;
    this.receiptCache = new Map();
    this.epochHighWaterMarks = new Map();
    this.ticketCounter = 0;
    this.sessionCounter = 0;
    this.settlementSeqCounter = 0;

    // Component 1: Committed Document Storage (D_c)
    this.committedDoc = [];
    for (let i = 0; i < initialText.length; i++) {
      this.committedDoc.push({
        tokenId: `init:${i}`,
        char: initialText[i],
        attrs: initialAttrs ? { ...initialAttrs[i] } : {},
        originSessionId: null
      });
    }

    // Component 2: Provisional Overlay (piecewise segments)
    // null when Idle
    // { blockId: 'root', segments: [ { dcStart, dcEnd, units: [AttributedUnit] } ] }
    this.provisionalOverlay = null;

    // Component 3: Marked Window inside flattened overlay units
    // null when Idle or Settling
    // { relStart, relEnd }
    this.markedWindow = null;

    // Component 4: Attribution Epoch
    // { epochId, baseSegments, baseWindow, B_epoch, C_raw, seed }
    this.attributionEpoch = null;

    // Component 5: History Session
    // { sessionId, preSessionDocSnapshot }
    this.historySession = null;

    // Component 6: Settlement Obligation
    // { activeFence: { token, sessionId, epoch }, pendingCommands: [] }
    this.settlementObligation = {
      activeFence: null,
      pendingCommands: []
    };
    this.settlementEvidence = null;

    // Editor metadata & projection state
    this._selection = [...initialSelection];
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this._documentRevision = 0;
    this._sessionRevision = 0;
    this._compositionState = 'Idle'; // Idle | Composing | AwaitingSettlement
    this.undoStack = [];
    this.redoStack = [];
    this.commandOutcomes = new Map();
    this.typingIntent = {};
    this.consumedSettlementEvidence = new Set();
    this._reentrantMutationQueue = [];
    this._inTransaction = false;
    this._isDrainingQueue = false;

    this._willChangeListeners = [];
    this._didChangeListeners = [];
    this._rollbackListeners = [];
    this._editHistory = [];

    // Coherent Published Snapshot
    this._publishedState = {
      units: immutableCopy(this.committedDoc),
      sessionId: null, settlementToken: null, typingIntent: {},
      docText: initialText,
      docAttributes: initialAttrs ? cloneAttrs(initialAttrs) : Array(initialText.length).fill({}),
      selection: [...initialSelection],
      markedRange: null,
      compositionState: "Idle",
      documentRevision: 0,
      sessionRevision: 0,
      epoch: 0,
      connectionState: "Active"
    };
    deepFreeze(this._publishedState);
    this._continuationScheduled = false;
    this.schedulingBudget = 64;
    this.scheduleContinuation = fn => setTimeout(fn, 0);
  }

  nextTicketId(prefix = 'ticket') {
    return `${prefix}-${++this.ticketCounter}`;
  }

  nextSettlementToken() {
    return `settle-tok-${++this.settlementSeqCounter}`;
  }

  // Deep clone of coordinator state for atomic rollback on failure injection
  _cloneState() {
    return {
      _epoch: this._epoch,
      _connectionState: this._connectionState,
      sequenceCounter: this.sequenceCounter,
      receiptCache: new Map(Array.from(this.receiptCache.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))])),
      epochHighWaterMarks: new Map(this.epochHighWaterMarks),
      ticketCounter: this.ticketCounter,
      sessionCounter: this.sessionCounter,
      settlementSeqCounter: this.settlementSeqCounter,
      committedDoc: JSON.parse(JSON.stringify(this.committedDoc)),
      provisionalOverlay: this.provisionalOverlay ? JSON.parse(JSON.stringify(this.provisionalOverlay)) : null,
      markedWindow: this.markedWindow ? { ...this.markedWindow } : null,
      attributionEpoch: this.attributionEpoch ? JSON.parse(JSON.stringify(this.attributionEpoch)) : null,
      historySession: this.historySession ? JSON.parse(JSON.stringify(this.historySession)) : null,
      settlementObligation: JSON.parse(JSON.stringify(this.settlementObligation)),
      settlementEvidence: this.settlementEvidence ? JSON.parse(JSON.stringify(this.settlementEvidence)) : null,
      _selection: [...this._selection],
      sessionIslands: JSON.parse(JSON.stringify(this.sessionIslands)),
      sessionCommittedRanges: JSON.parse(JSON.stringify(this.sessionCommittedRanges)),
      _documentRevision: this._documentRevision,
      _sessionRevision: this._sessionRevision,
      _compositionState: this._compositionState,
      undoStack: JSON.parse(JSON.stringify(this.undoStack)),
      redoStack: JSON.parse(JSON.stringify(this.redoStack)),
      commandOutcomes: new Map(Array.from(this.commandOutcomes.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))])),
      typingIntent: { ...this.typingIntent },
      consumedSettlementEvidence: new Set(this.consumedSettlementEvidence),
      _reentrantMutationQueue: [...this._reentrantMutationQueue],
      _inTransaction: this._inTransaction,
      _editHistory: JSON.parse(JSON.stringify(this._editHistory)),
      _publishedState: {
        units: immutableCopy(this._publishedState.units),
        sessionId: this._publishedState.sessionId, settlementToken: this._publishedState.settlementToken, typingIntent: immutableCopy(this._publishedState.typingIntent),
        docText: this._publishedState.docText,
        docAttributes: cloneAttrs(this._publishedState.docAttributes),
        selection: [...this._publishedState.selection],
        markedRange: this._publishedState.markedRange ? [...this._publishedState.markedRange] : null,
        compositionState: this._publishedState.compositionState,
        documentRevision: this._publishedState.documentRevision,
        sessionRevision: this._publishedState.sessionRevision,
        epoch: this._publishedState.epoch,
        connectionState: this._publishedState.connectionState
      }
    };
  }

  _restoreState(snap) {
    this._epoch = snap._epoch;
    this._connectionState = snap._connectionState;
    this.sequenceCounter = snap.sequenceCounter;
    this.receiptCache = snap.receiptCache;
    this.epochHighWaterMarks = snap.epochHighWaterMarks;
    this.ticketCounter = Math.max(this.ticketCounter, snap.ticketCounter);
    this.sessionCounter = snap.sessionCounter;
    this.settlementSeqCounter = snap.settlementSeqCounter;
    this.committedDoc = snap.committedDoc;
    this.provisionalOverlay = snap.provisionalOverlay;
    this.markedWindow = snap.markedWindow;
    this.attributionEpoch = snap.attributionEpoch;
    this.historySession = snap.historySession;
    this.settlementObligation = snap.settlementObligation;
    this.settlementEvidence = snap.settlementEvidence;
    this._selection = snap._selection;
    this.sessionIslands = snap.sessionIslands;
    this.sessionCommittedRanges = snap.sessionCommittedRanges;
    this._documentRevision = snap._documentRevision;
    this._sessionRevision = snap._sessionRevision;
    this._compositionState = snap._compositionState;
    this.undoStack = snap.undoStack;
    this.redoStack = snap.redoStack;
    this.commandOutcomes = snap.commandOutcomes;
    this.typingIntent = snap.typingIntent;
    this.consumedSettlementEvidence = snap.consumedSettlementEvidence ? new Set(snap.consumedSettlementEvidence) : new Set();
    this._publishedState = deepFreeze(snap._publishedState);
    this._editHistory = snap._editHistory ? JSON.parse(JSON.stringify(snap._editHistory)) : [];
    // Preserve pending reentrant mutations enqueued during transaction so they execute against restored baseline (§4.1, §4.3)
    if (!this._reentrantMutationQueue || this._reentrantMutationQueue.length === 0) {
      this._reentrantMutationQueue = snap._reentrantMutationQueue ? [...snap._reentrantMutationQueue] : [];
    }
    this._inTransaction = snap._inTransaction;
  }

  // -------------------------------------------------------------------------
  // Projections: Visible Document (D_v) and Marked Range
  // -------------------------------------------------------------------------
  getVisibleUnits() {
    if (!this.provisionalOverlay || !this.provisionalOverlay.segments || this.provisionalOverlay.segments.length === 0) {
      return this.committedDoc.map(u => ({ ...u, attrs: { ...u.attrs } }));
    }
    const segs = this.provisionalOverlay.segments;
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;

    const prefix = this.committedDoc.slice(0, sc);
    const overlayUnits = [];
    for (const seg of segs) {
      for (const u of seg.units) {
        overlayUnits.push({ ...u, attrs: { ...u.attrs } });
      }
    }
    const suffix = this.committedDoc.slice(ec);
    return [...prefix, ...overlayUnits, ...suffix];
  }

  addWillChangeListener(fn) { this._willChangeListeners.push(fn); }
  addDidChangeListener(fn) { this._didChangeListeners.push(fn); }
  addRollbackListener(fn) { this._rollbackListeners.push(fn); }

  _getWorkingVisibleUnits() {
    return this.getVisibleUnits();
  }

  _getWorkingDocText() {
    return this._getWorkingVisibleUnits().map(u => u.char).join("");
  }

  _getWorkingDocAttributes() {
    return this._getWorkingVisibleUnits().map(u => ({ ...u.attrs }));
  }

  _getWorkingLength() {
    return this._getWorkingVisibleUnits().length;
  }

  _getWorkingMarkedRange() {
    if (!this.provisionalOverlay || !this.markedWindow) return null;
    if (!this.provisionalOverlay.segments || this.provisionalOverlay.segments.length === 0) return null;
    const sc = this.provisionalOverlay.segments[0].dcStart;
    return [sc + this.markedWindow.relStart, sc + this.markedWindow.relEnd];
  }

  _publishWorkingState() {
    this._publishedState = {
      units: immutableCopy(this._getWorkingVisibleUnits()),
      sessionId: this._getWorkingSessionId(), settlementToken: this.settlementObligation.activeFence?.token ?? null, typingIntent: immutableCopy(this.typingIntent),
      docText: this._getWorkingDocText(),
      docAttributes: cloneAttrs(this._getWorkingDocAttributes()),
      selection: [...this._selection],
      markedRange: this._getWorkingMarkedRange(),
      compositionState: this._compositionState,
      documentRevision: this._documentRevision,
      sessionRevision: this._sessionRevision,
      epoch: this._epoch,
      connectionState: this._connectionState
    };
    deepFreeze(this._publishedState);
  }

  // Exact Edit Diff Computation for Coordinate Basis Mapping (§4.1, Probe 1)
  _computeDiff(oldText, newText) {
    let start = 0;
    while (start < oldText.length && start < newText.length && oldText[start] === newText[start]) {
      start++;
    }
    let oldEnd = oldText.length;
    let newEnd = newText.length;
    while (oldEnd > start && newEnd > start && oldText[oldEnd - 1] === newText[newEnd - 1]) {
      oldEnd--;
      newEnd--;
    }
    return {
      start,
      oldEnd,
      oldLen: oldEnd - start,
      newLen: newEnd - start,
      delta: newText.length - oldText.length
    };
  }

  _mapRangeAcrossDiff(range, diff) {
    if (!range || range.length !== 2) return null;
    const [u, v] = [Math.min(range[0], range[1]), Math.max(range[0], range[1])];
    if (v <= diff.start) {
      return [u, v];
    }
    if (u >= diff.oldEnd) {
      return [u + diff.delta, v + diff.delta];
    }
    if (u === v) {
      return [diff.start + diff.newLen, diff.start + diff.newLen];
    }
    if (diff.oldLen === 0 && u === diff.start) {
      return [u + diff.delta, v + diff.delta];
    }
    const newU = Math.max(diff.start, Math.min(u, diff.start + diff.newLen));
    const newV = Math.max(diff.start, Math.min(v + diff.delta, diff.start + diff.newLen + (v - diff.oldEnd)));
    return [newU, newV];
  }

  // Public Getters reading coherent published snapshot (§4.1, §4.3, Probe 2)
  get docText() {
    return this._publishedState.docText;
  }

  get docAttributes() {
    return this._publishedState.docAttributes.map(a => ({ ...a }));
  }

  get selection() {
    return [...this._publishedState.selection];
  }

  set selection(newSel) {
    this._selection = newSel ? [...newSel] : [0, 0];
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, selection: [...this._selection] });
    }
  }

  get markedRange() {
    return this._publishedState.markedRange ? [...this._publishedState.markedRange] : null;
  }

  get compositionState() {
    return this._publishedState.compositionState;
  }

  set compositionState(val) {
    this._compositionState = val;
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, compositionState: val });
    }
  }

  get documentRevision() {
    return this._publishedState.documentRevision;
  }

  set documentRevision(val) {
    this._documentRevision = val;
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, documentRevision: val });
    }
  }

  get sessionRevision() {
    return this._publishedState.sessionRevision;
  }

  set sessionRevision(val) {
    this._sessionRevision = val;
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, sessionRevision: val });
    }
  }

  get epoch() {
    return this._publishedState.epoch;
  }

  set epoch(val) {
    this._epoch = val;
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, epoch: val });
    }
  }

  get connectionState() {
    return this._publishedState.connectionState;
  }

  set connectionState(val) {
    this._connectionState = val;
    if (!this._inTransaction && this._publishedState) {
      this._publishedState = deepFreeze({ ...this._publishedState, connectionState: val });
    }
  }

  _getWorkingSessionId() { return this.historySession ? this.historySession.sessionId : null; }
  get activeSessionId() { return this._publishedState.sessionId; }
  snapshot() { return this._publishedState; }

  get activeSettlementToken() {
    return this._publishedState.settlementToken ? { token: this._publishedState.settlementToken } : null;
  }

  // Computes the exact bounding extent of all content touched by this composition session
  _computeSessionExtent() {
    const ranges = [];
    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const r of this.sessionCommittedRanges) {
        if (r[0] < r[1]) ranges.push([...r]);
      }
    }
    const wmr = this._getWorkingMarkedRange();
    if (wmr && wmr[0] < wmr[1]) {
      ranges.push([...wmr]);
    }
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      for (const isl of this.sessionIslands) {
        if (isl[0] < isl[1]) ranges.push([...isl]);
      }
    }
    if (ranges.length === 0) {
      const p = this.selection ? this.selection[0] : 0;
      return [p, p];
    }
    let minStart = ranges[0][0];
    let maxEnd = ranges[0][1];
    for (let i = 1; i < ranges.length; i++) {
      minStart = Math.min(minStart, ranges[i][0]);
      maxEnd = Math.max(maxEnd, ranges[i][1]);
    }
    return [minStart, maxEnd];
  }

  // -------------------------------------------------------------------------
  // Upfront Validation Helper
  // -------------------------------------------------------------------------
  _validateRange(range, docLen) {
    if (!range || !Array.isArray(range) || range.length !== 2) return false;
    const [u, v] = range;
    if (!Number.isInteger(u) || !Number.isInteger(v)) return false;
    if (u < 0 || v < 0 || u > v || v > docLen) return false;
    return true;
  }

  _validateSelection(sel, docLen) {
    if (!sel || !Array.isArray(sel) || sel.length !== 2) return false;
    const [s0, s1] = sel;
    if (!Number.isInteger(s0) || !Number.isInteger(s1)) return false;
    if (s0 < 0 || s1 < 0 || s0 > docLen || s1 > docLen) return false;
    return true;
  }

  // Transaction-Owned Unified Target Resolution (Repair D)
  _resolveTargetDescriptor(payload) {
    const curLen = this._getWorkingLength();
    let range;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { valid: false };
      }
      range = [Math.min(payload.range[0], payload.range[1]), Math.max(payload.range[0], payload.range[1])];
    } else {
      const workingMarked = this._getWorkingMarkedRange();
      if (workingMarked) {
        range = [...workingMarked];
      } else {
        const sel = this._selection || [0, 0];
        const s0 = Math.max(0, Math.min(sel[0], curLen));
        const s1 = Math.max(0, Math.min(sel[1], curLen));
        range = [Math.min(s0, s1), Math.max(s0, s1)];
      }
    }

    const oldSpan = range[1] - range[0];
    let repLen = 0;
    if (payload.type === 'preedit') {
      repLen = payload.text ? payload.text.length : 0;
    } else if (payload.type === 'commit') {
      repLen = (payload.text !== undefined && payload.text !== null) ? payload.text.length : oldSpan;
    } else if (payload.type === 'nativeRemoval') {
      repLen = payload.replacementText ? payload.replacementText.length : 0;
    } else {
      repLen = oldSpan;
    }

    const proposedLen = curLen - oldSpan + repLen;
    return {
      valid: true,
      range,
      oldSpan,
      repLen,
      proposedLen
    };
  }

  // Computes the proposed post-edit document length before any mutation occurs (Finding D)
  _computeProposedPostLength(payload) {
    return this._resolveTargetDescriptor(payload);
  }

  // -------------------------------------------------------------------------
  // Normalized Event Dispatcher
  // -------------------------------------------------------------------------
  // Dedicated Native IME Admission Boundary (§4.2, Defect 3)
  applyNativeCallback(callback, epoch = null, seq = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (!callback || typeof callback !== 'object' || !callback.type) {
      return { status: 'Rejected', reason: 'MalformedCallback' };
    }
    if (epoch === null || epoch === undefined || typeof epoch !== 'number' || !Number.isInteger(epoch) || epoch < 0) {
      return { status: 'Rejected', reason: 'MissingOrMalformedConnectionEpoch' };
    }
    if (seq === null || seq === undefined || typeof seq !== 'number' || !Number.isInteger(seq) || seq <= 0) {
      return { status: 'Rejected', reason: 'MissingOrMalformedSequenceStamp' };
    }
    return this.applyInput({ ...callback, epoch, isNativeCallback: true }, seq);
  }

  _dispatchPayload(payload) {
    const prop = this._computeProposedPostLength(payload);
    let result;
    switch (payload.type) {
      case "startComposition":
        result = this._startComposition(prop.range, payload.selection, payload.sessionId, payload.injectFailureAt);
        break;
      case "preedit":
        result = this._preedit(payload.text, prop.range, payload.selection, payload.injectFailureAt);
        break;
      case "partialCommit":
        result = this._partialCommit(payload.length !== undefined ? payload.length : payload.committedLength, payload.selection, payload.injectFailureAt);
        break;
      case "targetExpansion":
      case "retarget":
        result = this._retarget(prop.range, payload.selection, payload.injectFailureAt);
        break;
      case "unmark":
        result = this._unmark(payload.settled !== false, payload.selection, payload.injectFailureAt);
        break;
      case "completeSettlement":
        result = this._completeSettlement(payload.token, payload.sessionId, payload.epoch, payload.injectFailureAt);
        break;
      case "commit":
        result = this._commit(payload.text, prop.range, payload.selection, payload.injectFailureAt);
        break;
      case "cancel":
      case "cancelUncommitted":
      case "applicationAbort":
        result = this._cancel(payload.injectFailureAt);
        break;
      case "nativeRemoval":
        result = this._nativeRemoval(prop.range, payload.replacementText, payload.selection, payload.injectFailureAt);
        break;
      case "dispatchCommand":
      case "dispatchAppCommand":
        result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
        break;
      case "queueCommand":
        if (this._compositionState === "Idle") {
          result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
        } else {
          result = this._queueCommand(payload.cmd, payload.target, payload.injectFailureAt);
        }
        break;
      case "onFocusLost":
        result = this._onFocusLost(payload.strategy, payload.injectFailureAt);
        break;
      case "openNewConnection":
        result = this._openNewConnection(payload.injectFailureAt);
        break;
      case "dispose":
        result = this._dispose(payload.injectFailureAt);
        break;
      case "undo":
        result = this._internalUndo(payload.syncBoundary, payload.settlementEvidence);
        break;
      case "redo":
        result = this._internalRedo();
        break;
      case "setSelection":
        if (payload.selection && this._validateSelection(payload.selection, this._getWorkingLength())) {
          this.selection = [...payload.selection];
          result = { type: "SelectionChanged", status: "Accepted", selection: [...this.selection] };
        } else {
          result = { status: "Rejected", reason: "InvalidSelection" };
        }
        break;
      default:
        result = { status: "Rejected", reason: "UnknownEvent:" + payload.type };
    }
    return result;
  }

  applyInput(payload, seq = null) {
    if (this._connectionState === 'Closed') return {status:'Rejected', reason:'ConnectionClosed'};
    if (!payload || typeof payload !== 'object' || typeof payload.type !== 'string') return {status:'Rejected',reason:'MalformedCallback'};
    let captured;
    try { captured = immutableCopy(payload); } catch (_) { return {status:'Rejected',reason:'MalformedCallback'}; }
    if (this._inTransaction || (this._reentrantMutationQueue.length && !this._isDrainingQueue)) {
      const basis = this._publishedState;
      const p = structuredClone(captured);
      // Omitted native replacement ranges refer to the published marked range
      // or selection at admission, never to the later drain-time caret.
      if (!p.range && ['preedit','commit','nativeRemoval','startComposition','retarget','targetExpansion'].includes(p.type)) {
        p.range = [...(basis.markedRange ?? [...basis.selection].sort((a,b)=>a-b))];
      }
      const ticketId = this.nextTicketId('reentrant');
      this._reentrantMutationQueue.push(deepFreeze({
        ticketId, origin:'external', epoch:p.epoch ?? basis.epoch, seq,
        sessionId:p.sessionId ?? null, basisRevision:basis.documentRevision,
        basisUnits:basis.units, payload:immutableCopy(p)
      }));
      if (!this._inTransaction) this._scheduleDrain();
      return {status:'Accepted',action:'QueuedReentrant',ticketId};
    }
    return this._executeRequest(captured, seq);
  }

  _identityResult(payload, seq = null) {
    if (this._connectionState === 'Closed') return {status:'Rejected',reason:'ConnectionClosed'};
    const ep = payload.epoch ?? this._epoch;
    const reject = reason => ({status:'Rejected',reason});
    if (!Number.isSafeInteger(ep) || ep < 0) return reject('MalformedConnectionEpoch');
    if (payload.isNativeCallback && payload.epoch == null) return reject('MissingConnectionEpoch');
    if (ep < this._epoch) return reject('StaleEpoch');
    if (ep > this._epoch) return reject('FutureEpoch');
    if (seq !== null && (!Number.isSafeInteger(seq) || seq <= 0)) return reject('MalformedSequenceStamp');
    const stamp = `${ep}:${seq}`;
    if (seq !== null && this.receiptCache.has(stamp)) return {status:'Duplicate',cachedReceipt:this.receiptCache.get(stamp)};
    if (seq !== null && seq <= (this.epochHighWaterMarks.get(ep) ?? 0)) return {status:'DuplicateReceiptExpired'};
    if (payload.sessionId && payload.type !== 'startComposition' && payload.sessionId !== this._getWorkingSessionId()) return reject('SessionMismatch');
    return null;
  }

  _executeRequest(payload, seq = null) {
    const identity = this._identityResult(payload,seq);
    if (identity) return identity;
    const reject = reason => ({status:'Rejected',reason});
    const ep = payload.epoch ?? this._epoch;
    const stamp = `${ep}:${seq}`;
    for (const key of ['text','replacementText']) if (payload[key] != null && typeof payload[key] !== 'string') return reject('MalformedText');
    if (payload.range != null && !this._validateRange(payload.range,this._getWorkingLength())) return reject('InvalidTargetRange');
    const prop = this._computeProposedPostLength(payload);
    if (!prop.valid) return reject('InvalidTargetRange');
    if (payload.selection != null && !this._validateSelection(payload.selection,prop.proposedLen)) return reject('InvalidSelection');
    if (payload.type === 'partialCommit' && (!Number.isInteger(payload.length ?? payload.committedLength) || (payload.length ?? payload.committedLength) < 0)) return reject('InvalidPartialCommitCount');
    if (['dispatchCommand','dispatchAppCommand','queueCommand'].includes(payload.type)) {
      if (!payload.cmd || typeof payload.cmd.type !== 'string') return reject('InvalidCommand');
      if (payload.cmd.type !== 'Format') return reject('UnknownCommandType:' + payload.cmd.type);
      if (payload.cmd.patch != null && (typeof payload.cmd.patch !== 'object' || Array.isArray(payload.cmd.patch))) return reject('InvalidPatch');
      if (payload.target && !['TrackedRange','SelectionTarget','CompositionTarget'].includes(payload.target.type)) return reject('InvalidTarget');
      if (payload.target?.type === 'TrackedRange' && !this._validateSelection(payload.target.range,this._getWorkingLength())) return reject('InvalidTargetRange');
    }
    if (payload.injectFailure && !payload.injectFailureAt) return reject('InjectedFailure');
    return this._runInTransaction(payload.type, () => {
      if (['preedit','partialCommit','retarget','targetExpansion'].includes(payload.type)) {
        this.settlementObligation.activeFence = null;
        this.settlementEvidence = null;
      }
      return this._dispatchPayload(payload);
    }, payload.injectFailureAt, result => {
      // Receipt is published before observers can deliver the same callback.
      if (seq !== null && result.status !== 'Rejected') {
        this.receiptCache.set(stamp,immutableCopy(result));
        this.epochHighWaterMarks.set(ep,Math.max(seq,this.epochHighWaterMarks.get(ep) ?? 0));
      }
    });
  }

  _scheduleDrain() {
    if (this._continuationScheduled || !this._reentrantMutationQueue.length) return;
    this._continuationScheduled = true;
    this.scheduleContinuation(() => { this._continuationScheduled = false; this._drainReentrantQueue(); });
  }

  // Centralized Transaction Runner (§4.3, Defect 2, Probe 2)
  _runInTransaction(actionName, fn, injectFailureAt = null, publishReceipt = null) {
    if (this._connectionState === "Closed" && actionName !== "dispose") {
      return { status: "Rejected", reason: "ConnectionClosed" };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId("reentrant");
      const envelope = new RequestEnvelope({
        ticketId,
        origin: "reentrant",
        epoch: this._epoch,
        seq: null,
        sessionId: this._getWorkingSessionId(),
        basisRevision: this._documentRevision,
        payload: { type: actionName, injectFailureAt },
        fn
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: "Accepted", action: "QueuedReentrant", ticketId };
    }
    this._inTransaction = true;
    const preTxSnapshot = this._cloneState();
    const prevDocText = this._getWorkingDocText();
    const prevDocAttrs = cloneAttrs(this._getWorkingDocAttributes());

    // Notification hook: will-change (observers see currently published snapshot)
    for (const listener of this._willChangeListeners) {
      try {
        listener(this._publishedState, actionName);
      } catch (_) {}
    }

    try {
      const result = fn();

      if (injectFailureAt === "beforeReceiptPublication") {
        throw new Error("InjectedFailure:beforeReceiptPublication");
      }

      if (result && result.status === "Rejected") {
        this._restoreState(preTxSnapshot);
        if (result.ticketId) {
          this.commandOutcomes.set(result.ticketId, { status: "Rejected", reason: result.reason });
        }
        // Rollback isolation: abandon internal closures originating from this failed transaction,
        // but preserve independent external requests (§4.1, Probe 3)
        const preservedQueue = [];
        for (const env of this._reentrantMutationQueue) {
          if (env.origin === "external" || env.origin === "application") {
            preservedQueue.push(env);
          } else {
            const term = { status: "Cancelled", reason: "AbandonedWithTransaction" };
            this.commandOutcomes.set(env.ticketId, term);
          }
        }
        this._reentrantMutationQueue = preservedQueue;
        for (const listener of this._rollbackListeners) {
          try { listener(this._publishedState, result.reason); } catch (_) {}
        }
        return result;
      }

      // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
      const newDocText = this._getWorkingDocText();
      const newDocAttrs = this._getWorkingDocAttributes();
      if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
        const prevRev = this._documentRevision;
        this._documentRevision++;
        const diff = this._computeDiff(prevDocText, newDocText);
        this._editHistory.push({
          fromRevision: prevRev,
          toRevision: this._documentRevision,
          diff
        });
      }

      // One accepted execution is one published session event, including no-ops.
      // Helper-local increments never define the public revision or double-count
      // queued history operations inside the same atomic settlement callback.
      this._sessionRevision = preTxSnapshot._sessionRevision + 1;
      // Staging / Publication Boundary Commit (§4.1, §4.3, Probe 2)
      this._publishWorkingState();

      // Every successful execution receipt carries the coherent published pair.
      if (result && result.status !== "Rejected") {
        result.documentRevision = this.documentRevision;
        result.sessionRevision = this.sessionRevision;
        if (result.documentRevision !== undefined) {
          result.documentRevision = this.documentRevision;
        }
        if (result.sessionRevision !== undefined) {
          result.sessionRevision = this.sessionRevision;
        }
        if (result.selection !== undefined) {
          result.selection = [...this.selection];
        }
        if (result.epoch !== undefined) {
          result.epoch = this.epoch;
        }
      }

      if (publishReceipt) publishReceipt(result);
      for (const listener of this._didChangeListeners) {
        try { listener(this._publishedState); } catch (_) {}
      }

      return result;
    } catch (e) {
      this._restoreState(preTxSnapshot);
      // Rollback isolation: abandon internal closures originating from this failed transaction,
      // but preserve independent external requests (§4.1, Probe 3)
      const preservedQueue = [];
      for (const env of this._reentrantMutationQueue) {
        if (env.origin === "external" || env.origin === "application") {
          preservedQueue.push(env);
        } else {
          const term = { status: "Cancelled", reason: "AbandonedWithTransaction" };
          this.commandOutcomes.set(env.ticketId, term);
        }
      }
      this._reentrantMutationQueue = preservedQueue;
      for (const listener of this._rollbackListeners) {
        try { listener(this._publishedState, e.message); } catch (_) {}
      }
      return { status: "Rejected", reason: e.message || "TransactionFailure" };
    } finally {
      this._inTransaction = false;
      this._drainReentrantQueue();
    }
  }

  // Single Queue-Drain Owner with Bounded Scheduling (§4.1, Probe 1, Probe 3)
  _drainReentrantQueue() {
    if (this._isDrainingQueue || this._inTransaction) return;
    this._isDrainingQueue = true;
    let processed = 0;
    try {
      while (this._reentrantMutationQueue.length && processed++ < this.schedulingBudget) {
        const envelope = this._reentrantMutationQueue.shift();
        let result;
        if (this._connectionState === 'Closed') result = {status:'Cancelled',reason:'Disposed'};
        else if (envelope.epoch !== this._epoch) result = {status:'Rejected',reason:envelope.epoch < this._epoch ? 'StaleEpoch':'FutureEpoch'};
        else if (envelope.fn) {
          // Private test/transaction-owned work only. No public API captures closures.
          result = this._runInTransaction(envelope.payload.type,envelope.fn,envelope.payload.injectFailureAt);
        } else {
          try {
            const request = {...envelope.payload,epoch:envelope.epoch};
            const identity = this._identityResult(request,envelope.seq);
            if (identity) result = identity;
            else {
              const mapped = mapRequest(request,envelope.basisUnits,this._getWorkingVisibleUnits());
              result = this._executeRequest(mapped,envelope.seq);
            }
          } catch (e) { result = {status:'Rejected',reason:e.message}; }
        }
        this.commandOutcomes.set(envelope.ticketId,immutableCopy(result));
      }
    } finally {
      this._isDrainingQueue = false;
      this._scheduleDrain();
    }
  }
  // -------------------------------------------------------------------------
  // Event Implementations
  // -------------------------------------------------------------------------
  _startComposition(range, selection = null, sessionId = null, injectStage = null) {
    if (this.compositionState !== 'Idle') {
      return { status: 'Rejected', reason: 'CompositionAlreadyActive' };
    }
    const [u, v] = range;
    const sId = sessionId || `session-${++this.sessionCounter}`;

    const segments = [];
    if (u === v) {
      segments.push({ dcStart: Math.max(0, u), dcEnd: Math.max(0, u), units: [] });
    } else {
      const startIdx = Math.max(0, u);
      const endIdx = Math.min(this.committedDoc.length, v);
      for (let i = startIdx; i < endIdx; i++) {
        segments.push({
          dcStart: i,
          dcEnd: i + 1,
          units: [{ ...this.committedDoc[i], attrs: { ...this.committedDoc[i].attrs } }]
        });
      }
    }
    if (segments.length === 0) {
      segments.push({ dcStart: Math.max(0, u), dcEnd: Math.max(0, u), units: [] });
    }

    this.provisionalOverlay = { blockId: 'root', segments };
    this.markedWindow = { relStart: 0, relEnd: Math.max(0, v - u) };

    const seed = (u >= 0 && u < this.committedDoc.length) ? { ...this.committedDoc[u].attrs } : {};
    const flatUnits = segments.flatMap(s => s.units);

    this.attributionEpoch = {
      epochId: 1,
      baseSegments: JSON.parse(JSON.stringify(segments)),
      baseWindow: { ...this.markedWindow },
      B_epoch: JSON.parse(JSON.stringify(flatUnits)),
      C_raw: flatUnits.map(u => u.char).join(''),
      seed
    };

    if (!this.historySession) {
      this.historySession = {
        sessionId: sId,
        preSessionDocSnapshot: {
          text: this.docText,
          attrs: cloneAttrs(this._getWorkingDocAttributes()),
          units: JSON.parse(JSON.stringify(this.committedDoc)),
          selection: [...this._selection]
        }
      };
    }

    this.compositionState = 'Composing';
    this.sessionIslands = [[u, v]];
    this.sessionCommittedRanges = [];
    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    this.sessionRevision++;

    return {
      type: 'CompositionStarted',
      status: 'Accepted',
      sessionId: sId,
      markedRange: [...this._getWorkingMarkedRange()],
      selection: [...this._selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _findInteriorSegment(baseSegs, aMid) {
    let curVis = 0;
    for (let i = 0; i < baseSegs.length; i++) {
      const seg = baseSegs[i];
      const segStart = curVis;
      const segEnd = curVis + seg.units.length;
      curVis = segEnd;

      if (segStart < aMid && aMid < segEnd) {
        return {
          index: i,
          leftUnits: seg.units.slice(0, aMid - segStart),
          rightUnits: seg.units.slice(aMid - segStart)
        };
      }
    }
    return null;
  }

  _preedit(text, range = null, selection = null, injectStage = null) {
    if (this.compositionState === 'Idle') {
      let compRange;
      if (range) {
        compRange = range;
      } else {
        const sel = this.selection || [0, 0];
        const selStart = Math.min(sel[0], sel[1]);
        const selEnd = Math.max(sel[0], sel[1]);
        compRange = [selStart, selEnd];
      }
      const startRes = this._startComposition(compRange, selection);
      if (startRes.status === 'Rejected') return startRes;
    } else if (this.compositionState === 'AwaitingSettlement') {
      this.compositionState = 'Composing';
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
      const curWmr = this._getWorkingMarkedRange();
      if (!curWmr || (range && (range[0] !== curWmr[0] || range[1] !== curWmr[1]))) {
        const retargetRes = this._retarget(range, selection, injectStage);
        if (retargetRes.status === 'Rejected') {
          return retargetRes;
        }
      }
    } else if (this.compositionState === 'Composing') {
      const curWmr = this._getWorkingMarkedRange();
      if (range && (!curWmr || range[0] !== curWmr[0] || range[1] !== curWmr[1])) {
        const retargetRes = this._retarget(range, selection, injectStage);
        if (retargetRes.status === 'Rejected') {
          return retargetRes;
        }
      }
    }

    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;

    const epoch = this.attributionEpoch;
    const B = epoch.B_epoch;
    const baseSegs = epoch.baseSegments;

    let p = 0;
    while (p < text.length && p < B.length && text[p] === B[p].char) {
      p++;
    }
    let s = 0;
    while (s < (text.length - p) && s < (B.length - p) && text[text.length - 1 - s] === B[B.length - 1 - s].char) {
      s++;
    }

    const midText = text.slice(p, text.length - s);
    const midUnits = [];
    for (let j = 0; j < midText.length; j++) {
      midUnits.push({
        tokenId: `prov:${this._getWorkingSessionId()}:e${epoch.epochId}:m${j}`,
        char: midText[j],
        attrs: { ...epoch.seed },
        originSessionId: this._getWorkingSessionId()
      });
    }

    const relStart0 = epoch.baseWindow.relStart;
    const relEnd0 = epoch.baseWindow.relEnd;
    const aMid = relStart0 + p;
    const bMid = relEnd0 - s;

    const newSegments = [];
    let curVis = 0;
    let firstTouched = -1;
    let lastTouched = -1;
    let leftUnits = [];
    let rightUnits = [];

    if (aMid === bMid) {
      const found = this._findInteriorSegment(baseSegs, aMid);
      if (found) {
        firstTouched = found.index;
        lastTouched = found.index;
        leftUnits = found.leftUnits;
        rightUnits = found.rightUnits;
      }
    } else {
      for (let i = 0; i < baseSegs.length; i++) {
        const seg = baseSegs[i];
        const segStart = curVis;
        const segEnd = curVis + seg.units.length;
        curVis = segEnd;

        const touches = (seg.units.length === 0)
          ? (aMid <= segStart && segStart <= bMid)
          : (Math.max(segStart, aMid) < Math.min(segEnd, bMid));

        if (touches) {
          if (firstTouched === -1) {
            firstTouched = i;
            leftUnits = seg.units.slice(0, Math.max(0, aMid - segStart));
          }
          lastTouched = i;
          rightUnits = seg.units.slice(Math.max(0, bMid - segStart));
        }
      }
    }

    if (firstTouched === -1) {
      let insIdx = 0;
      curVis = 0;
      while (insIdx < baseSegs.length && curVis + baseSegs[insIdx].units.length <= aMid) {
        curVis += baseSegs[insIdx].units.length;
        insIdx++;
      }
      for (let i = 0; i < insIdx; i++) newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      const dcPos = (insIdx < baseSegs.length)
        ? baseSegs[insIdx].dcStart
        : (baseSegs.length > 0 ? baseSegs[baseSegs.length - 1].dcEnd : 0);
      newSegments.push({ dcStart: dcPos, dcEnd: dcPos, units: midUnits });
      for (let i = insIdx; i < baseSegs.length; i++) newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
    } else {
      for (let i = 0; i < firstTouched; i++) {
        newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      }

      const dcStartMid = leftUnits.length > 0 ? baseSegs[firstTouched].dcEnd : baseSegs[firstTouched].dcStart;
      const dcEndMid = baseSegs[lastTouched].dcEnd;

      if (leftUnits.length > 0) {
        newSegments.push({
          dcStart: baseSegs[firstTouched].dcStart,
          dcEnd: baseSegs[firstTouched].dcEnd,
          units: leftUnits
        });
      }

      if (midUnits.length > 0 || dcStartMid < dcEndMid) {
        newSegments.push({
          dcStart: dcStartMid,
          dcEnd: dcEndMid,
          units: midUnits
        });
      }

      if (rightUnits.length > 0) {
        newSegments.push({
          dcStart: baseSegs[lastTouched].dcEnd,
          dcEnd: baseSegs[lastTouched].dcEnd,
          units: rightUnits
        });
      }

      for (let i = lastTouched + 1; i < baseSegs.length; i++) {
        newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      }
    }

    if (newSegments.length === 0) {
      const fallbackDc = (firstTouched >= 0 && firstTouched < baseSegs.length)
        ? baseSegs[firstTouched].dcStart
        : (baseSegs.length > 0 ? baseSegs[0].dcStart : 0);
      newSegments.push({ dcStart: fallbackDc, dcEnd: fallbackDc, units: [] });
    }

    // Failure injection: after text splice
    if (injectStage === 'afterTextSplice') {
      throw new Error('InjectedFailure:afterTextSplice');
    }

    this.provisionalOverlay.segments = newSegments;
    this.markedWindow = { relStart: relStart0, relEnd: relStart0 + text.length };

    const sc = newSegments[0].dcStart;
    const editVisibleStart = sc + aMid;
    const editVisibleEnd = sc + bMid;
    const delta = text.length - (bMid - aMid);

    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      for (let i = 0; i < this.sessionIslands.length - 1; i++) {
        const isl = this.sessionIslands[i];
        if (isl[0] >= editVisibleEnd) {
          isl[0] += delta;
          isl[1] += delta;
        } else if (isl[1] > editVisibleStart) {
          isl[1] = Math.max(isl[0], isl[1] + delta);
        }
      }
      last[0] = sc + relStart0;
      last[1] = sc + relStart0 + text.length;
    }

    // Map sessionCommittedRanges across edits
    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const r of this.sessionCommittedRanges) {
        if (r[0] >= editVisibleEnd) {
          r[0] += delta;
          r[1] += delta;
        } else if (r[1] > editVisibleStart) {
          r[1] = Math.max(r[0], r[1] + delta);
        }
      }
    }

    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
        const r = cmdRec.target.range;
        if (r[0] >= editVisibleEnd) {
          r[0] += delta;
          r[1] += delta;
        } else if (r[1] > editVisibleStart) {
          r[1] = Math.max(r[0], r[1] + delta);
        }
      }
    }

    // Failure injection: after target mapping
    if (injectStage === 'afterTargetMapping') {
      throw new Error('InjectedFailure:afterTargetMapping');
    }

    if (selection) this.selection = [...selection];
    else {
      this.selection = [sc + relStart0 + text.length, sc + relStart0 + text.length];
    }
    this.sessionRevision++;

    return {
      type: 'PreeditUpdated',
      status: 'Accepted',
      markedRange: [...this._getWorkingMarkedRange()],
      selection: [...this._selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _retarget(range, selection = null, injectStage = null) {
    const [u, v] = range;
    if (u < 0 || v > this._getWorkingLength() || u > v) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    if (!this.provisionalOverlay) {
      const segs = [];
      if (u === v) {
        segs.push({ dcStart: Math.max(0, u), dcEnd: Math.max(0, u), units: [] });
      } else {
        const startIdx = Math.max(0, u);
        const endIdx = Math.min(this.committedDoc.length, v);
        for (let i = startIdx; i < endIdx; i++) {
          segs.push({
            dcStart: i,
            dcEnd: i + 1,
            units: [{ ...this.committedDoc[i], attrs: { ...this.committedDoc[i].attrs } }]
          });
        }
      }
      this.provisionalOverlay = { blockId: 'root', segments: segs };
      this.markedWindow = { relStart: 0, relEnd: Math.max(0, v - u) };
      const seed = (u >= 0 && u < this._getWorkingLength()) ? { ...this._getWorkingDocAttributes()[u] } : {};
      const flatUnits = segs.flatMap(s => s.units);
      this.attributionEpoch = {
        epochId: (this.attributionEpoch ? this.attributionEpoch.epochId + 1 : 1),
        baseSegments: JSON.parse(JSON.stringify(segs)),
        baseWindow: { ...this.markedWindow },
        B_epoch: JSON.parse(JSON.stringify(flatUnits)),
        C_raw: flatUnits.map(unit => unit.char).join(''),
        seed
      };
      if (!this.historySession) {
        this.historySession = {
          sessionId: `session-${++this.sessionCounter}`,
          preSessionDocSnapshot: {
            text: this.docText,
            attrs: cloneAttrs(this._getWorkingDocAttributes()),
            units: JSON.parse(JSON.stringify(this.committedDoc)),
            selection: [...this._selection]
          }
        };
      }
      this.compositionState = 'Composing';
      this.sessionIslands = [[u, v]];
      if (selection) this.selection = [...selection];
      else this.selection = [v, v];
      this.sessionRevision++;
      return {
        type: 'TargetExpanded',
        status: 'Accepted',
        markedRange: this._getWorkingMarkedRange() ? [...this._getWorkingMarkedRange()] : null,
        selection: [...this._selection],
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision
      };
    }

    const segs = this.provisionalOverlay.segments;
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;
    const L = segs.flatMap(s => s.units).length;

    const newSegs = [];
    if (u < sc) {
      for (let i = u; i < sc; i++) {
        newSegs.push({
          dcStart: i,
          dcEnd: i + 1,
          units: [{ ...this.committedDoc[i], attrs: { ...this.committedDoc[i].attrs } }]
        });
      }
    }
    for (const s of segs) newSegs.push(JSON.parse(JSON.stringify(s)));
    if (v > sc + L) {
      const neededEnd = ec + (v - (sc + L));
      for (let i = ec; i < neededEnd; i++) {
        if (i < this.committedDoc.length) {
          newSegs.push({
            dcStart: i,
            dcEnd: i + 1,
            units: [{ ...this.committedDoc[i], attrs: { ...this.committedDoc[i].attrs } }]
          });
        }
      }
    }

    this.provisionalOverlay.segments = newSegs;
    const newSc = newSegs[0].dcStart;
    this.markedWindow = { relStart: u - newSc, relEnd: v - newSc };
    this.compositionState = 'Composing';
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;

    const flatUnits = newSegs.flatMap(s => s.units);
    const B_epoch = flatUnits.slice(this.markedWindow.relStart, this.markedWindow.relEnd);
    const seed = (u >= 0 && u < this._getWorkingLength()) ? { ...this._getWorkingDocAttributes()[u] } : {};

    this.attributionEpoch = {
      epochId: this.attributionEpoch ? this.attributionEpoch.epochId + 1 : 1,
      baseSegments: JSON.parse(JSON.stringify(newSegs)),
      baseWindow: { ...this.markedWindow },
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      C_raw: B_epoch.map(u => u.char).join(''),
      seed
    };

    if (!this.sessionIslands) this.sessionIslands = [];
    const lastIsland = this.sessionIslands[this.sessionIslands.length - 1];
    if (lastIsland) {
      const isDisjoint = (u >= lastIsland[1] || v <= lastIsland[0]);
      if (isDisjoint) {
        this.sessionIslands.push([u, v]);
      } else {
        lastIsland[0] = Math.min(lastIsland[0], u);
        lastIsland[1] = Math.max(lastIsland[1], v);
      }
    } else {
      this.sessionIslands.push([u, v]);
    }

    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    this.sessionRevision++;

    return {
      type: 'TargetExpanded',
      status: 'Accepted',
      markedRange: [...this._getWorkingMarkedRange()],
      selection: [...this._selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _partialCommit(k, selection = null, injectStage = null) {
    if (!this.markedWindow || !this.provisionalOverlay) {
      return { status: 'Rejected', reason: 'NoActiveMarkedRange' };
    }
    const currentLen = this.markedWindow.relEnd - this.markedWindow.relStart;
    if (k > currentLen) {
      return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate' };
    }

    const pCut = this.markedWindow.relStart + k;
    const segs = this.provisionalOverlay.segments;
    const sc = segs[0].dcStart;
    const mStart = sc + this.markedWindow.relStart;
    const mEnd = sc + this.markedWindow.relEnd;
    const cutPoint = mStart + k;

    let curVis = 0;
    const consumedSegments = [];
    const remainingSegments = [];
    let splitSegmentTail = null;
    let dCut = sc;

    for (const seg of segs) {
      const segLen = seg.units.length;
      const segStart = curVis;
      const segEnd = curVis + segLen;
      curVis = segEnd;

      if (segLen === 0) {
        const totalOverlayUnits = segs.flatMap(s => s.units).length;
        const hasRemainingUnitsAfter = pCut < totalOverlayUnits;
        if (segStart < pCut || (segStart === pCut && k > 0 && !hasRemainingUnitsAfter)) {
          consumedSegments.push(seg);
          dCut = seg.dcEnd;
        } else {
          remainingSegments.push(seg);
        }
        continue;
      }

      if (segEnd <= pCut) {
        consumedSegments.push(seg);
        dCut = seg.dcEnd;
      } else if (segStart < pCut) {
        const commUnits = seg.units.slice(0, pCut - segStart);
        const remUnits = seg.units.slice(pCut - segStart);
        const origSpan = seg.dcEnd - seg.dcStart;
        const consumedSpan = Math.min(origSpan, commUnits.length);
        const segCut = seg.dcStart + consumedSpan;
        consumedSegments.push({
          dcStart: seg.dcStart,
          dcEnd: segCut,
          units: commUnits
        });
        dCut = segCut;
        splitSegmentTail = {
          units: remUnits,
          origDcEnd: seg.dcEnd
        };
      } else {
        remainingSegments.push(seg);
      }
    }

    const P_commit = consumedSegments.flatMap(s => s.units);

    // Splice P_commit into committedDoc
    const newCommittedDoc = [
      ...this.committedDoc.slice(0, sc),
      ...P_commit,
      ...this.committedDoc.slice(dCut)
    ];
    this.committedDoc = newCommittedDoc;

    const delta = P_commit.length - (dCut - sc);
    const newSc = sc + P_commit.length;

    // Track cumulative committed ranges for this composition session (Finding C)
    if (P_commit.length > 0) {
      if (!this.sessionCommittedRanges) this.sessionCommittedRanges = [];
      this.sessionCommittedRanges.push([mStart, mStart + P_commit.length]);
      this.sessionCommittedRanges.sort((a, b) => a[0] - b[0]);
      const merged = [];
      for (const r of this.sessionCommittedRanges) {
        if (merged.length === 0) merged.push([...r]);
        else {
          const last = merged[merged.length - 1];
          if (r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
          else merged.push([...r]);
        }
      }
      this.sessionCommittedRanges = merged;
    }

    const finalRemSegments = [];

    if (splitSegmentTail && splitSegmentTail.units && splitSegmentTail.units.length > 0) {
      finalRemSegments.push({
        dcStart: newSc,
        dcEnd: splitSegmentTail.origDcEnd + delta,
        units: splitSegmentTail.units
      });
    }

    for (const seg of remainingSegments) {
      finalRemSegments.push({
        dcStart: seg.dcStart + delta,
        dcEnd: seg.dcEnd + delta,
        units: JSON.parse(JSON.stringify(seg.units))
      });
    }

    const remLen = Math.max(0, currentLen - k);

    if (finalRemSegments.length === 0 || remLen === 0) {
      this.provisionalOverlay = {
        blockId: 'root',
        segments: finalRemSegments.length > 0 ? finalRemSegments : [{ dcStart: newSc, dcEnd: newSc, units: [] }]
      };
      this.markedWindow = { relStart: 0, relEnd: 0 };
      this.compositionState = 'Composing';
      this.attributionEpoch = {
        epochId: this.attributionEpoch.epochId + 1,
        baseSegments: JSON.parse(JSON.stringify(this.provisionalOverlay.segments)),
        baseWindow: { ...this.markedWindow },
        B_epoch: [],
        C_raw: '',
        seed: this.attributionEpoch.seed
      };
    } else {
      this.provisionalOverlay.segments = finalRemSegments;
      this.markedWindow = { relStart: 0, relEnd: remLen };

      const flatUnits = finalRemSegments.flatMap(s => s.units);
      const B_epoch = flatUnits.slice(0, remLen);
      this.attributionEpoch = {
        epochId: this.attributionEpoch.epochId + 1,
        baseSegments: JSON.parse(JSON.stringify(finalRemSegments)),
        baseWindow: { ...this.markedWindow },
        B_epoch: JSON.parse(JSON.stringify(B_epoch)),
        C_raw: B_epoch.map(u => u.char).join(''),
        seed: this.attributionEpoch.seed
      };
    }

    // Preserve and map disjoint session islands across partial commits
    if (!this.sessionIslands || this.sessionIslands.length === 0) {
      this.sessionIslands = [[cutPoint, cutPoint + remLen]];
    } else {
      const updatedIslands = [];
      let foundActive = false;
      for (const [u, v] of this.sessionIslands) {
        if (v <= mStart || u >= mEnd) {
          updatedIslands.push([u, v]);
        } else {
          updatedIslands.push([cutPoint, cutPoint + remLen]);
          foundActive = true;
        }
      }
      if (!foundActive) {
        updatedIslands.push([cutPoint, cutPoint + remLen]);
      }
      this.sessionIslands = updatedIslands;
    }

    if (selection) this.selection = [...selection];
    else {
      const curSc = this.provisionalOverlay.segments[0].dcStart;
      this.selection = [curSc + this.markedWindow.relEnd, curSc + this.markedWindow.relEnd];
    }
    this.sessionRevision++;

    return {
      type: 'PartialCommitted',
      status: 'Accepted',
      frozenCount: k,
      markedRange: this._getWorkingMarkedRange() ? [...this._getWorkingMarkedRange()] : null,
      selection: [...this._selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _flushOverlayToCommitted(injectStage = null) {
    if (!this.provisionalOverlay) return;
    const segs = this.provisionalOverlay.segments;
    if (!segs || segs.length === 0) {
      this.provisionalOverlay = null;
      return;
    }
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;
    const flatUnits = segs.flatMap(s => s.units);

    // Staged failure injection: during overlay materialization
    if (injectStage === 'duringOverlayMaterialization') {
      throw new Error('InjectedFailure:duringOverlayMaterialization');
    }

    this.committedDoc = [
      ...this.committedDoc.slice(0, sc),
      ...flatUnits,
      ...this.committedDoc.slice(ec)
    ];
    this.provisionalOverlay = null;
  }

  _unmark(settled = true, selection = null, injectStage = null) {
    if (!this.markedWindow) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    const finalExtent = [...this._getWorkingMarkedRange()];
    this.markedWindow = null;
    if (selection) this.selection = [...selection];
    else this.selection = [finalExtent[1], finalExtent[1]];

    if (settled) {
      this._flushOverlayToCommitted(injectStage);
      this.compositionState = 'Idle';

      if (injectStage === 'beforeHistoryPublication') {
        throw new Error('InjectedFailure:beforeHistoryPublication');
      }

      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this._getWorkingDocText();
      const curAttrs = this._getWorkingDocAttributes();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeUnits: snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
          beforeSelection: bSel,
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
          afterSelection: [...this._selection],
          extent: finalExtent,
          range: finalExtent,
          selection: bSel
        });
        this.redoStack = [];
      }

      this._drainPendingCommands(finalExtent, injectStage);
      this.historySession = null;
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
      this.sessionRevision++;

      return {
        type: 'Unmarked',
        settled: true,
        status: 'Accepted',
        finalExtent,
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision
      };
    } else {
      this.compositionState = 'AwaitingSettlement';
      const tok = this.nextSettlementToken();
      this.settlementObligation.activeFence = {
        token: tok,
        sessionId: this._getWorkingSessionId(),
        epoch: this.epoch
      };
      this.settlementEvidence = {
        token: tok,
        sessionId: this._getWorkingSessionId(),
        extent: finalExtent
      };
      this.sessionRevision++;

      return {
        type: 'Unmarked',
        settled: false,
        status: 'Accepted',
        token: tok,
        finalExtent,
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision
      };
    }
  }

  _completeSettlement(token = null, sessionId = null, epoch = null, injectStage = null) {
    if (this.compositionState === 'Idle') {
      return { status: 'Rejected', reason: 'NoActiveSettlement' };
    }
    if (this.markedWindow !== null) {
      return { status: 'Rejected', reason: 'CandidateStillActive' };
    }
    if (epoch !== null && epoch !== this.epoch) {
      return { status: 'Rejected', reason: 'StaleEpoch' };
    }
    if (sessionId && this._getWorkingSessionId() && sessionId !== this._getWorkingSessionId()) {
      return { status: 'Rejected', reason: 'SessionMismatch' };
    }
    const activeFence = this.settlementObligation.activeFence;
    if (activeFence) {
      if (!token || token !== activeFence.token) {
        return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
      }
    }
    if (!this.settlementEvidence) {
      return { status: 'Rejected', reason: 'UnknownTargetExtent' };
    }

    const finalExtent = [...this.settlementEvidence.extent];
    this._flushOverlayToCommitted(injectStage);
    this.compositionState = 'Idle';

    if (injectStage === 'beforeHistoryPublication') {
      throw new Error('InjectedFailure:beforeHistoryPublication');
    }

    const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
    const curText = this._getWorkingDocText();
    const curAttrs = this._getWorkingDocAttributes();
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeUnits: snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
        afterSelection: [...this._selection],
        extent: finalExtent,
        range: finalExtent,
        selection: bSel
      });
      this.redoStack = [];
    }

    this._drainPendingCommands(finalExtent, injectStage);
    this.historySession = null;
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;
    // Eligible reentrant commands are preserved across normal settlement (§4.1, Probe 3)
    this.sessionRevision++;

    return {
      type: 'SettlementCompleted',
      status: 'Accepted',
      finalExtent,
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _commit(text = null, range = null, selection = null, injectStage = null) {
    if (text !== null) {
      const pRes = this._preedit(text, range, selection, injectStage);
      if (pRes.status === 'Rejected') return pRes;
    }
    return this._unmark(true, selection, injectStage);
  }

  _cancel(injectStage = null) {
    const prevDocLen = this.docText.length;
    const savedSegments = (this.provisionalOverlay && this.provisionalOverlay.segments)
      ? JSON.parse(JSON.stringify(this.provisionalOverlay.segments))
      : [];

    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.attributionEpoch = null;
    this.compositionState = 'Idle';

    const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
    if (snap) {
      this.selection = [...snap.selection];
    }
    const curText = this._getWorkingDocText();
    const curAttrs = this._getWorkingDocAttributes();

    const sessionExtent = this._computeSessionExtent();
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [0, 0];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeUnits: snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
        afterSelection: [...this._selection],
        extent: sessionExtent,
        range: sessionExtent,
        selection: bSel
      });
      this.redoStack = [];
    }

    // Cancel CompositionTarget commands and map TrackedRange commands using exact segment mapping
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        cmdRec.status = 'Cancelled';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
      } else if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
        const r = cmdRec.target.range;
        const mapRes = mapTrackedRangeOnCancel(r, savedSegments, this.committedDoc);
        if (mapRes.status === 'Applied') {
          cmdRec.target.range = [...mapRes.range];
          cmdRec.target.ranges = mapRes.ranges.map(rg => [...rg]);
        } else {
          cmdRec.status = 'Rejected';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: mapRes.reason || 'TargetInvalidated' });
        }
      }
    }

    // Surviving unrelated commands and queued Undo operations MUST be drained and executed immediately
    const surviving = this.settlementObligation.pendingCommands.filter(
      cmd => cmd.status === 'Pending'
    );
    this.settlementObligation.pendingCommands = [];

    for (const cmdRec of surviving) {
      if (cmdRec.type === 'Undo') {
        if (this.undoStack.length === 0) {
          cmdRec.status = 'Rejected';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'NothingToUndo' });
          continue;
        }

        const entry = this.undoStack.pop();
        const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
        const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this._selection];

        this.redoStack.push({
          group: entry.group,
          beforeText: entry.beforeText,
          beforeAttrs: cloneAttrs(entry.beforeAttrs),
          beforeUnits: entry.beforeUnits ? JSON.parse(JSON.stringify(entry.beforeUnits)) : null,
          beforeSelection: bSel,
          afterText: entry.afterText,
          afterAttrs: cloneAttrs(entry.afterAttrs),
          afterUnits: entry.afterUnits ? JSON.parse(JSON.stringify(entry.afterUnits)) : null,
          afterSelection: aSel,
          extent: entry.extent ? [...entry.extent] : null,
          range: entry.range ? [...entry.range] : null,
          ranges: entry.ranges ? JSON.parse(JSON.stringify(entry.ranges)) : null,
          patch: entry.patch ? { ...entry.patch } : null,
          selection: bSel
        });

        if (entry.group === 'Formatting' && entry.beforeAttrs) {
          for (let i = 0; i < this.committedDoc.length && i < entry.beforeAttrs.length; i++) {
            this.committedDoc[i].attrs = { ...entry.beforeAttrs[i] };
          }
        } else if (entry.beforeUnits) {
          this.committedDoc = JSON.parse(JSON.stringify(entry.beforeUnits));
        } else {
          const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
          const bAttrs = entry.beforeAttrs || [];
          this.committedDoc = [];
          for (let i = 0; i < bText.length; i++) {
            this.committedDoc.push({
              tokenId: `undo:${i}`,
              char: bText[i],
              attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
              originSessionId: null
            });
          }
        }
        this.selection = [...bSel];
        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          action: 'Undo',
          group: entry.group
        });
        this._remapPendingCommandTargets(this.committedDoc);
        continue;
      }

      if (cmdRec.cmd && cmdRec.cmd.type === 'Format') {
        const curDocText = this._getDrainageDocText();
        const patch = cmdRec.cmd.patch || {};
        let targetRanges = [];
        if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
          if (cmdRec.target.ranges && cmdRec.target.ranges.length > 0) {
            for (const rg of cmdRec.target.ranges) {
              const [r0, r1] = [Math.min(rg[0], rg[1]), Math.max(rg[0], rg[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              }
            }
          } else {
            const r = cmdRec.target.range;
            if (r && r.length === 2) {
              const [r0, r1] = [Math.min(r[0], r[1]), Math.max(r[0], r[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              } else {
                cmdRec.status = 'Rejected';
                this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
                continue;
              }
            } else {
              cmdRec.status = 'Rejected';
              this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
              continue;
            }
          }
          if (targetRanges.length === 0) {
            cmdRec.status = 'Rejected';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
            continue;
          }
        } else if (cmdRec.target && cmdRec.target.type === 'SelectionTarget') {
          targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
        }

        // Collapsed range toggle typing intent (§4.5, Defect 2)
        if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
          this.typingIntent = { ...this.typingIntent, ...patch };
          cmdRec.status = 'Applied';
          this.commandOutcomes.set(cmdRec.ticketId, {
            status: 'Applied',
            typingIntentUpdated: true,
            patch: { ...patch },
            range: [...targetRanges[0]],
            ranges: [[...targetRanges[0]]]
          });
          continue;
        }

        const beforeAttrs = cloneAttrs(this._getWorkingDocAttributes());
        let anyChanged = false;
        const appliedRanges = [];

        for (const [tStart, tEnd] of targetRanges) {
          if (tStart === tEnd) continue;
          const [formatStart, formatEnd] = expandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
          appliedRanges.push([formatStart, formatEnd]);

          for (let i = formatStart; i < formatEnd; i++) {
            if (i < this.committedDoc.length) {
              for (const k of Object.keys(patch)) {
                if (this.committedDoc[i].attrs[k] !== patch[k]) {
                  this.committedDoc[i].attrs[k] = patch[k];
                  anyChanged = true;
                }
              }
            }
          }
        }

        const outcomeRange = appliedRanges[0] || (targetRanges[0] ? [...targetRanges[0]] : null);
        if (anyChanged) {
          this.undoStack.push({
            group: 'Formatting',
            beforeText: curDocText,
            beforeAttrs,
            beforeSelection: [...this._selection],
            afterText: this._getWorkingDocText(),
            afterAttrs: cloneAttrs(this._getWorkingDocAttributes()),
            afterSelection: [...this._selection],
            range: outcomeRange,
            ranges: appliedRanges,
            patch: cmdRec.cmd.patch,
            selection: [...this._selection]
          });
          this.redoStack = [];
        }

        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          range: outcomeRange,
          ranges: appliedRanges,
          patch: cmdRec.cmd.patch,
          noOp: !anyChanged
        });
      }
    }

    this.historySession = null;
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.sessionRevision++;

    return {
      type: 'Cancelled',
      status: 'Accepted',
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _nativeRemoval(range, replacementText = "", selection = null, injectStage = null) {
    const curLen = this.docText.length;
    let rStart, rEnd;
    if (range) {
      rStart = Math.min(range[0], range[1]);
      rEnd = Math.max(range[0], range[1]);
    } else if (this.compositionState === 'Composing' && this._getWorkingMarkedRange()) {
      [rStart, rEnd] = this._getWorkingMarkedRange();
    } else {
      const sel = this.selection || [0, 0];
      rStart = Math.min(sel[0], sel[1]);
      rEnd = Math.max(sel[0], sel[1]);
    }

    if (rStart < 0 || rEnd > curLen || rStart > rEnd) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      this._flushOverlayToCommitted(injectStage);
      this.markedWindow = null;
      this.attributionEpoch = null;
      this.compositionState = 'Idle';

      const beforeDoc = this.committedDoc;
      const newDoc = [
        ...beforeDoc.slice(0, rStart),
        ...replacementText.split('').map((c, i) => ({
          tokenId: `rem:${this.sequenceCounter}:${i}`,
          char: c,
          attrs: (rStart < beforeDoc.length) ? { ...beforeDoc[rStart].attrs } : {},
          originSessionId: null
        })),
        ...beforeDoc.slice(rEnd)
      ];
      this.committedDoc = newDoc;

      if (selection) {
        this.selection = [...selection];
      } else {
        this.selection = [rStart + replacementText.length, rStart + replacementText.length];
      }

      const curText = this._getWorkingDocText();
      const curAttrs = this._getWorkingDocAttributes();
      const extent = [rStart, rStart + replacementText.length];

      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeUnits: snap.units ? JSON.parse(JSON.stringify(snap.units)) : JSON.parse(JSON.stringify(beforeDoc)),
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
          afterSelection: [...this._selection],
          extent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }

      this._drainPendingCommands(extent, injectStage);
      this.historySession = null;
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
      this.sessionRevision++;
      return {
        type: 'NativeRemovalApplied',
        status: 'Accepted',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        selection: [...this._selection]
      };
    } else {
      const beforeText = this._getWorkingDocText();
      const beforeAttrs = cloneAttrs(this._getWorkingDocAttributes());
      const beforeSelection = [...this._selection];

      const beforeDoc = this.committedDoc;
      const newDoc = [
        ...beforeDoc.slice(0, rStart),
        ...replacementText.split('').map((c, i) => ({
          tokenId: `rem:${this.sequenceCounter}:${i}`,
          char: c,
          attrs: (rStart < beforeDoc.length) ? { ...beforeDoc[rStart].attrs } : {},
          originSessionId: null
        })),
        ...beforeDoc.slice(rEnd)
      ];
      this.committedDoc = newDoc;

      if (selection) {
        this.selection = [...selection];
      } else {
        this.selection = [rStart + replacementText.length, rStart + replacementText.length];
      }

      const afterText = this._getWorkingDocText();
      const afterAttrs = cloneAttrs(this._getWorkingDocAttributes());
      const afterSelection = [...this._selection];
      const extent = [rStart, rStart + replacementText.length];

      if (afterText !== beforeText || !areAttrsEqual(afterAttrs, beforeAttrs)) {
        this.undoStack.push({
          group: 'NativeRemoval',
          beforeText,
          beforeAttrs,
          beforeUnits: JSON.parse(JSON.stringify(beforeDoc)),
          beforeSelection,
          afterText,
          afterAttrs,
          afterUnits: JSON.parse(JSON.stringify(newDoc)),
          afterSelection,
          extent,
          selection: [...beforeSelection]
        });
        this.redoStack = [];
      }

      this.sessionRevision++;
      return {
        type: 'NativeRemovalApplied',
        status: 'Accepted',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        selection: [...this._selection]
      };
    }
  }

  _onFocusLost(strategy = 'cancel', injectStage = null) {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      if (strategy === 'settle') {
        const res = this._unmark(true, null, injectStage);
        return { type: 'FocusLost', status: res.status, action: 'Settled' };
      } else {
        const res = this._cancel(injectStage);
        return { type: 'FocusLost', status: res.status, action: 'Cancelled' };
      }
    }
    return { type: 'FocusLost', status: 'Accepted', action: 'None' };
  }

  // Connection Replacement turnover policy (Findings A & B)
  // Preserves accepted visible content and finalizes composition history
  _openNewConnection(injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const sessionExtent = this._computeSessionExtent();
      this._flushOverlayToCommitted(injectStage);
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this._getWorkingDocText();
      const curAttrs = this._getWorkingDocAttributes();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeUnits: snap && snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
          afterSelection: [...this._selection],
          extent: sessionExtent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }
    }

    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.attributionEpoch = null;
    this.historySession = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this._compositionState = 'Idle';
    this._connectionState = 'Active';
    this._epoch++;
    this.receiptCache.clear(); // Evict receipt cache on connection replacement (Finding F)
    this._sessionRevision++;

    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'StaleEpoch' });
    }
    this.settlementObligation.pendingCommands = [];
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;

    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this._epoch };
  }

  _dispose(injectStage = null) {
    if (this._connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._compositionState === 'Composing' || this._compositionState === 'AwaitingSettlement') {
      const sessionExtent = this._computeSessionExtent();
      this._flushOverlayToCommitted(injectStage);
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this._getWorkingDocText();
      const curAttrs = this._getWorkingDocAttributes();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeUnits: snap && snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
          afterSelection: [...this._selection],
          extent: sessionExtent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }
    }

    this.historySession = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this._compositionState = 'Idle';
    this._connectionState = 'Closed';
    this.receiptCache.clear();

    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this.settlementObligation.pendingCommands = [];
    for (const env of this._reentrantMutationQueue) {
      this.commandOutcomes.set(env.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this._reentrantMutationQueue = [];
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;
    this._sessionRevision++;

    return { type: 'Disposed', status: 'Accepted' };
  }

  // Public Transactional Mutation Methods (§4.3, Defect 2)
  setSelection(selection) { return this.applyInput({type:'setSelection',selection}); }
  openNewConnection(injectFailureAt = null) { return this.applyInput({type:'openNewConnection',injectFailureAt}); }
  dispose(injectFailureAt = null) { return this.applyInput({type:'dispose',injectFailureAt}); }
  nativeRemoval(range,replacementText = '',selection = null,injectFailureAt = null) {
    return this.applyInput({type:'nativeRemoval',range,replacementText,selection,injectFailureAt});
  }
  onFocusLost(strategy = 'cancel',injectFailureAt = null) { return this.applyInput({type:'onFocusLost',strategy,injectFailureAt}); }
  dispatchCommand(cmd,target = null,injectFailureAt = null) { return this.applyInput({type:'dispatchCommand',cmd,target,injectFailureAt}); }

  _internalDispatchCommand(cmd, target = null, injectStage = null) {
    if (injectStage === 'duringCommandPreparation') {
      throw new Error('InjectedFailure:duringCommandPreparation');
    }
    if (!cmd || typeof cmd !== 'object' || !cmd.type) {
      return { status: 'Rejected', reason: 'InvalidCommand' };
    }
    if (cmd.type !== 'Format') {
      return { status: 'Rejected', reason: `UnknownCommandType:${cmd.type}` };
    }

    // In Composing or AwaitingSettlement: defer to pending queue with ticket
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      return this._queueCommand(cmd, target, injectStage);
    }

    // In Idle: execute immediately
    const ticketId = this.nextTicketId();
    const patch = cmd.patch || {};
    let targetRanges = [];
    const curDocText = this._getWorkingDocText();

    if (!target || target.type === 'SelectionTarget') {
      const s = this._selection || [0, 0];
      targetRanges = [[Math.min(s[0], s[1]), Math.max(s[0], s[1])]];
    } else if (target.type === 'TrackedRange') {
      const r = target.range;
      if (!this._validateRange(r, curDocText.length)) {
        this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
        return { status: 'Rejected', ticketId, reason: 'InvalidTargetRange' };
      }
      targetRanges = [[Math.min(r[0], r[1]), Math.max(r[0], r[1])]];
    } else if (target.type === 'CompositionTarget') {
      this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'NoActiveComposition' });
      return { status: 'Rejected', ticketId, reason: 'NoActiveComposition' };
    } else {
      this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'InvalidTarget' });
      return { status: 'Rejected', ticketId, reason: 'InvalidTarget' };
    }

    // Collapsed range toggle typing intent
    if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
      this.typingIntent = { ...this.typingIntent, ...patch };
      const outcome = { status: 'Applied', typingIntentUpdated: true, patch };
      this.commandOutcomes.set(ticketId, outcome);
      return { status: 'Applied', ticketId, typingIntentUpdated: true };
    }

    const beforeAttrs = cloneAttrs(this._getWorkingDocAttributes());
    let anyChanged = false;
    const appliedRanges = [];

    for (const [tStart, tEnd] of targetRanges) {
      if (tStart === tEnd) continue;
      const [formatStart, formatEnd] = expandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
      appliedRanges.push([formatStart, formatEnd]);

      for (let i = formatStart; i < formatEnd; i++) {
        if (i < this.committedDoc.length) {
          for (const k of Object.keys(patch)) {
            if (this.committedDoc[i].attrs[k] !== patch[k]) {
              this.committedDoc[i].attrs[k] = patch[k];
              anyChanged = true;
            }
          }
        }
      }
    }

    if (anyChanged) {
      const outcomeRange = appliedRanges[0] || (targetRanges[0] ? [...targetRanges[0]] : null);
      this.undoStack.push({
        group: 'Formatting',
        beforeText: curDocText,
        beforeAttrs,
        beforeSelection: [...this._selection],
        afterText: this._getWorkingDocText(),
        afterAttrs: cloneAttrs(this._getWorkingDocAttributes()),
        afterSelection: [...this._selection],
        range: outcomeRange,
        ranges: appliedRanges,
        patch,
        selection: [...this._selection]
      });
      this.redoStack = [];
    }

    const outcomeRange = appliedRanges[0] || (targetRanges[0] ? [...targetRanges[0]] : null);
    const outcome = {
      status: 'Applied',
      range: outcomeRange,
      ranges: appliedRanges,
      patch,
      noOp: !anyChanged
    };
    this.commandOutcomes.set(ticketId, outcome);
    return {
      status: 'Applied',
      ticketId,
      range: outcomeRange,
      ranges: appliedRanges,
      noOp: !anyChanged
    };
  }

  _queueCommand(cmd, target, injectStage = null) {
    if (injectStage === 'duringCommandPreparation') {
      throw new Error('InjectedFailure:duringCommandPreparation');
    }
    if (this.compositionState === 'Idle') {
      return this._internalDispatchCommand(cmd, target, injectStage);
    }
    const ticketId = this.nextTicketId();
    let tokenIds = null;
    if (target && target.type === 'TrackedRange' && target.range) {
      const [u, v] = [Math.min(target.range[0], target.range[1]), Math.max(target.range[0], target.range[1])];
      const vis = this.getVisibleUnits();
      tokenIds = vis.slice(u, v).map(unit => unit.tokenId);
    }
    const record = {
      ticketId,
      cmd,
      target: JSON.parse(JSON.stringify(target)),
      tokenIds,
      status: 'Pending',
      sessionId: this._getWorkingSessionId(),
      epoch: this.epoch
    };
    this.settlementObligation.pendingCommands.push(record);
    return { status: 'Accepted', ticketId };
  }

  // Remap pending command targets dynamically across intermediate edits and Undos (§4.5)
  _remapPendingCommandTargets(currentUnits) {
    const curLen = currentUnits.length;
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.status !== 'Pending') continue;
      if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
        if (!cmdRec.tokenIds || cmdRec.tokenIds.length === 0) continue;
        const surviving = [];
        for (const tId of cmdRec.tokenIds) {
          if (!tId) continue;
          const idx = currentUnits.findIndex(u => u.tokenId === tId);
          if (idx !== -1 && !surviving.includes(idx)) surviving.push(idx);
        }
        if (surviving.length === 0) {
          cmdRec.status = 'Rejected';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
          continue;
        }
        surviving.sort((a, b) => a - b);
        const runs = [];
        let curRun = [surviving[0], surviving[0] + 1];
        for (let i = 1; i < surviving.length; i++) {
          const idx = surviving[i];
          if (idx === curRun[1]) {
            curRun[1] = idx + 1;
          } else {
            runs.push([...curRun]);
            curRun = [idx, idx + 1];
          }
        }
        runs.push([...curRun]);
        cmdRec.target.range = [...runs[0]];
        cmdRec.target.ranges = runs.map(r => [...r]);
        cmdRec.target.isFragmented = runs.length > 1;
      } else if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        const lastPoppedWasComposition = this.redoStack.some(r => r.group === 'Composition');
        const compSessionId = cmdRec.target.sessionId || (this.historySession ? this.historySession.sessionId : null);
        let hasCompUnits = false;
        if (compSessionId) {
          hasCompUnits = currentUnits.some(u => u.originSessionId === compSessionId);
        }
        if (lastPoppedWasComposition && !hasCompUnits) {
          cmdRec.status = 'Rejected';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
        }
      }
    }
  }

  _getDrainageDocText() {
    return this._getWorkingDocText();
  }

  _drainPendingCommands(settledExtent, injectStage = null) {
    if (injectStage === 'duringCommandPreparation') {
      throw new Error('InjectedFailure:duringCommandPreparation');
    }

    const [eStart, eEnd] = settledExtent || [0, 0];

    // Track composition session target extent across constituent islands, committed units, and settled candidate (Finding C, Gap 4.1)
    let cumulativeSessionRanges = [];
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      for (const isl of this.sessionIslands) {
        if (isl[0] < isl[1]) cumulativeSessionRanges.push([...isl]);
      }
    }
    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const cr of this.sessionCommittedRanges) {
        if (cr[0] < cr[1]) cumulativeSessionRanges.push([...cr]);
      }
    }
    if (eStart < eEnd) {
      cumulativeSessionRanges.push([eStart, eEnd]);
    }
    cumulativeSessionRanges.sort((a, b) => a[0] - b[0]);
    const mergedSessionRanges = [];
    for (const r of cumulativeSessionRanges) {
      if (mergedSessionRanges.length === 0) {
        mergedSessionRanges.push([...r]);
      } else {
        const last = mergedSessionRanges[mergedSessionRanges.length - 1];
        if (r[0] <= last[1]) {
          last[1] = Math.max(last[1], r[1]);
        } else {
          mergedSessionRanges.push([...r]);
        }
      }
    }
    this._remapPendingCommandTargets(this.committedDoc);

    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.status !== 'Pending') continue;

      if (cmdRec.type === 'Undo') {
        if (this.undoStack.length === 0) {
          cmdRec.status = 'Rejected';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'NothingToUndo' });
          continue;
        }

        const entry = this.undoStack.pop();
        const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
        const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this._selection];

        this.redoStack.push({
          group: entry.group,
          beforeText: entry.beforeText,
          beforeAttrs: cloneAttrs(entry.beforeAttrs),
          beforeUnits: entry.beforeUnits ? JSON.parse(JSON.stringify(entry.beforeUnits)) : null,
          beforeSelection: bSel,
          afterText: entry.afterText,
          afterAttrs: cloneAttrs(entry.afterAttrs),
          afterUnits: entry.afterUnits ? JSON.parse(JSON.stringify(entry.afterUnits)) : null,
          afterSelection: aSel,
          extent: entry.extent ? [...entry.extent] : null,
          range: entry.range ? [...entry.range] : null,
          ranges: entry.ranges ? JSON.parse(JSON.stringify(entry.ranges)) : null,
          patch: entry.patch ? { ...entry.patch } : null,
          selection: bSel
        });

        if (entry.group === 'Formatting' && entry.beforeAttrs) {
          for (let i = 0; i < this.committedDoc.length && i < entry.beforeAttrs.length; i++) {
            this.committedDoc[i].attrs = { ...entry.beforeAttrs[i] };
          }
        } else if (entry.beforeUnits) {
          this.committedDoc = JSON.parse(JSON.stringify(entry.beforeUnits));
        } else {
          const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
          const bAttrs = entry.beforeAttrs || [];
          this.committedDoc = [];
          for (let i = 0; i < bText.length; i++) {
            this.committedDoc.push({
              tokenId: `undo:${i}`,
              char: bText[i],
              attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
              originSessionId: null
            });
          }
        }
        this.selection = [...bSel];
        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          action: 'Undo',
          group: entry.group
        });
        this._remapPendingCommandTargets(this.committedDoc);
        continue;
      }

      if (cmdRec.cmd && cmdRec.cmd.type === 'Format') {
        const curDocText = this._getDrainageDocText();
        const patch = cmdRec.cmd.patch || {};
        let targetRanges = [];

        if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
          if (cmdRec.target.ranges && cmdRec.target.ranges.length > 0) {
            for (const rg of cmdRec.target.ranges) {
              const [r0, r1] = [Math.min(rg[0], rg[1]), Math.max(rg[0], rg[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              }
            }
          } else {
            const r = cmdRec.target.range;
            if (r && r.length === 2) {
              const [r0, r1] = [Math.min(r[0], r[1]), Math.max(r[0], r[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              } else {
                cmdRec.status = 'Rejected';
                this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
                continue;
              }
            } else {
              cmdRec.status = 'Rejected';
              this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
              continue;
            }
          }
          if (targetRanges.length === 0) {
            cmdRec.status = 'Rejected';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
            continue;
          }
        } else if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
          if (cmdRec.target.sessionId && this.historySession && cmdRec.target.sessionId !== this.historySession.sessionId) {
            cmdRec.status = 'Rejected';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetSessionMismatch' });
            continue;
          }

          const totalSessionUnits = mergedSessionRanges.reduce((sum, r) => sum + (r[1] - r[0]), 0);
          if (totalSessionUnits === 0) {
            // Genuinely empty candidate result: update typing intent
            this.typingIntent = { ...this.typingIntent, ...patch };
            cmdRec.status = 'Applied';
            this.commandOutcomes.set(cmdRec.ticketId, {
              status: 'Applied',
              typingIntentUpdated: true,
              patch: { ...patch },
              range: [eStart, eStart],
              ranges: [[eStart, eStart]]
            });
            continue;
          }
          targetRanges = mergedSessionRanges.length > 0 ? mergedSessionRanges : [[eStart, eEnd]];
        } else if (cmdRec.target && cmdRec.target.type === 'SelectionTarget') {
          targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
        } else {
          targetRanges = [[eStart, eEnd]];
        }

        // Collapsed range toggle typing intent (§4.5, Defect 2)
        if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
          this.typingIntent = { ...this.typingIntent, ...patch };
          cmdRec.status = 'Applied';
          this.commandOutcomes.set(cmdRec.ticketId, {
            status: 'Applied',
            typingIntentUpdated: true,
            patch: { ...patch },
            range: [...targetRanges[0]],
            ranges: [[...targetRanges[0]]]
          });
          continue;
        }

        const beforeAttrs = cloneAttrs(this._getWorkingDocAttributes());
        let anyChanged = false;
        const appliedRanges = [];

        for (const [tStart, tEnd] of targetRanges) {
          if (tStart === tEnd) continue;
          const [formatStart, formatEnd] = expandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
          appliedRanges.push([formatStart, formatEnd]);

          for (let i = formatStart; i < formatEnd; i++) {
            if (i < this.committedDoc.length) {
              for (const k of Object.keys(patch)) {
                if (this.committedDoc[i].attrs[k] !== patch[k]) {
                  this.committedDoc[i].attrs[k] = patch[k];
                  anyChanged = true;
                }
              }
            }
          }
        }

        const outcomeRange = appliedRanges[0] || (targetRanges[0] ? [...targetRanges[0]] : null);
        if (anyChanged) {
          this.undoStack.push({
            group: 'Formatting',
            beforeText: curDocText,
            beforeAttrs,
            beforeSelection: [...this._selection],
            afterText: this._getWorkingDocText(),
            afterAttrs: cloneAttrs(this._getWorkingDocAttributes()),
            afterSelection: [...this._selection],
            range: outcomeRange,
            ranges: appliedRanges,
            patch: cmdRec.cmd.patch,
            selection: [...this._selection]
          });
          this.redoStack = [];
        }

        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          range: outcomeRange,
          ranges: appliedRanges,
          patch: cmdRec.cmd.patch,
          noOp: !anyChanged
        });
      }
    }

    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.settlementObligation.pendingCommands = this.settlementObligation.pendingCommands.filter(
      c => c.status === 'Pending'
    );
  }

  // -------------------------------------------------------------------------
  // Executable Undo / Redo (Section 4.4)
  // -------------------------------------------------------------------------
  // -------------------------------------------------------------------------
  // Executable Undo / Redo (Section 4.4, Defect 3)
  // -------------------------------------------------------------------------
  undo(syncBoundary = false,settlementEvidence = null) { return this.applyInput({type:'undo',syncBoundary,settlementEvidence}); }
  redo() { return this.applyInput({type:'redo'}); }

  _internalUndo(syncBoundary = false, settlementEvidence = null) {
    // If composition is active: do NOT unilaterally settle or force Idle!
    // Application undo without qualified synchronous completion boundary queues behind settlement request.
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      if (syncBoundary) {
        if (!settlementEvidence || typeof settlementEvidence !== 'object') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (settlementEvidence.adapterQualification !== 'QualifiedSyncSettlement') {
          return { status: 'Rejected', reason: 'UnqualifiedAdapter' };
        }
        if (settlementEvidence.epoch === undefined || typeof settlementEvidence.epoch !== 'number') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (settlementEvidence.epoch !== this.epoch) {
          return { status: 'Rejected', reason: 'EpochMismatch' };
        }
        if (!settlementEvidence.sessionId || typeof settlementEvidence.sessionId !== 'string') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (this._getWorkingSessionId() && settlementEvidence.sessionId !== this._getWorkingSessionId()) {
          return { status: 'Rejected', reason: 'SessionMismatch' };
        }
        if (settlementEvidence.generation === undefined || typeof settlementEvidence.generation !== 'number') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (settlementEvidence.generation !== this.sessionRevision) {
          return { status: 'Rejected', reason: 'ObsoleteSettlementEvidence' };
        }
        const ext = settlementEvidence.extent;
        if (!ext || !Array.isArray(ext) || ext.length !== 2 || !Number.isSafeInteger(ext[0]) || !Number.isSafeInteger(ext[1]) || ext[0] < 0 || ext[1] > this.docText.length || ext[0] > ext[1]) {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (!settlementEvidence.recordId || typeof settlementEvidence.recordId !== 'string' || settlementEvidence.recordId.trim() === '') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (this.consumedSettlementEvidence.has(settlementEvidence.recordId)) {
          return { status: 'Rejected', reason: 'ReusedSettlementEvidence' };
        }

        this.consumedSettlementEvidence.add(settlementEvidence.recordId);
        const finalExtent = [...settlementEvidence.extent];
        this._flushOverlayToCommitted();
        const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
        const curText = this._getWorkingDocText();
        const curAttrs = this._getWorkingDocAttributes();
        if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
          const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
          this.undoStack.push({
            group: 'Composition',
            beforeText: snap.text,
            beforeAttrs: cloneAttrs(snap.attrs),
            beforeUnits: snap.units ? JSON.parse(JSON.stringify(snap.units)) : null,
            beforeSelection: bSel,
            afterText: curText,
            afterAttrs: cloneAttrs(curAttrs),
            afterUnits: JSON.parse(JSON.stringify(this.committedDoc)),
            afterSelection: [...this._selection],
            extent: finalExtent,
            range: finalExtent,
            selection: bSel
          });
          this.redoStack = [];
        }
        this._drainPendingCommands(finalExtent);
        this.historySession = null;
        this.markedWindow = null;
        this.attributionEpoch = null;
        this.sessionIslands = [];
        this.sessionCommittedRanges = [];
        this.settlementObligation.activeFence = null;
        this.settlementEvidence = null;
        this.compositionState = 'Idle';
      } else {
        // Asynchronous platform: queue behind settlement obligation
        if (this.compositionState === 'Composing') {
          this.compositionState = 'AwaitingSettlement';
          const tok = this.nextSettlementToken();
          this.settlementObligation.activeFence = {
            token: tok,
            sessionId: this._getWorkingSessionId(),
            epoch: this.epoch
          };
          this.settlementEvidence = null;
        }

        const ticketId = this.nextTicketId('undo');
        const undoRecord = {
          type: 'Undo',
          ticketId,
          status: 'Pending',
          sessionId: this._getWorkingSessionId(),
          epoch: this.epoch
        };
        this.settlementObligation.pendingCommands.push(undoRecord);

        return {
          type: 'UndoQueued',
          status: 'Accepted',
          action: 'QueuedBehindSettlement',
          ticketId,
          state: this.compositionState
        };
      }
    }

    if (this.undoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToUndo' };
    }

    const entry = this.undoStack.pop();
    const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
    const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this._selection];

    this.redoStack.push({
      group: entry.group,
      beforeText: entry.beforeText,
      beforeAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: bSel,
      afterText: entry.afterText,
      afterAttrs: cloneAttrs(entry.afterAttrs),
      afterSelection: aSel,
      extent: entry.extent ? [...entry.extent] : null,
      range: entry.range ? [...entry.range] : null,
      ranges: entry.ranges ? JSON.parse(JSON.stringify(entry.ranges)) : null,
      patch: entry.patch ? { ...entry.patch } : null,
      selection: bSel
    });

    if (entry.group === 'Formatting' && entry.beforeAttrs) {
      for (let i = 0; i < this.committedDoc.length && i < entry.beforeAttrs.length; i++) {
        this.committedDoc[i].attrs = { ...entry.beforeAttrs[i] };
      }
    } else {
      const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
      const bAttrs = entry.beforeAttrs || [];
      this.committedDoc = [];
      for (let i = 0; i < bText.length; i++) {
        this.committedDoc.push({
          tokenId: `undo:${i}`,
          char: bText[i],
          attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
          originSessionId: null
        });
      }
    }

    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.compositionState = 'Idle';
    this.selection = [...bSel];

    return {
      status: 'Accepted',
      action: 'Undo',
      group: entry.group,
      docText: this._getWorkingDocText(),
      selection: [...this._selection]
    };
  }

  _internalRedo() {
    if (this.redoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToRedo' };
    }
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this._getWorkingDocText();
      const curAttrs = this._getWorkingDocAttributes();
      const modified = snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs));
      if (modified) {
        this.redoStack = [];
        return { status: 'Rejected', reason: 'NothingToRedo' };
      }
      this.provisionalOverlay = null;
      this.markedWindow = null;
      this.attributionEpoch = null;
      this.historySession = null;
      this.sessionIslands = [];
      this.sessionCommittedRanges = [];
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
      this.compositionState = 'Idle';
    }

    const entry = this.redoStack.pop();
    const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
    const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this._selection];

    this.undoStack.push({
      group: entry.group,
      beforeText: entry.beforeText,
      beforeAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: bSel,
      afterText: entry.afterText,
      afterAttrs: cloneAttrs(entry.afterAttrs),
      afterSelection: aSel,
      extent: entry.extent ? [...entry.extent] : null,
      range: entry.range ? [...entry.range] : null,
      ranges: entry.ranges ? JSON.parse(JSON.stringify(entry.ranges)) : null,
      patch: entry.patch ? { ...entry.patch } : null,
      selection: bSel
    });

    if (entry.group === 'Formatting' && entry.afterAttrs) {
      for (let i = 0; i < this.committedDoc.length && i < entry.afterAttrs.length; i++) {
        this.committedDoc[i].attrs = { ...entry.afterAttrs[i] };
      }
    } else if (entry.afterUnits) {
      this.committedDoc = JSON.parse(JSON.stringify(entry.afterUnits));
    } else {
      const aText = (entry.afterText !== undefined && entry.afterText !== null) ? entry.afterText : '';
      const aAttrs = entry.afterAttrs || [];
      this.committedDoc = [];
      for (let i = 0; i < aText.length; i++) {
        this.committedDoc.push({
          tokenId: `redo:${i}`,
          char: aText[i],
          attrs: aAttrs[i] ? { ...aAttrs[i] } : {},
          originSessionId: null
        });
      }
    }

    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.compositionState = 'Idle';
    this.selection = [...aSel];

    return {
      status: 'Accepted',
      action: 'Redo',
      group: entry.group,
      docText: this._getWorkingDocText(),
      selection: [...this._selection]
    };
  }
}
