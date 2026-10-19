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

export class CandidateBCoordinator {
  constructor(initialText = "Hello", initialAttrs = null, initialSelection = [0, 0]) {
    this.epoch = 0;
    this.connectionState = 'Active';
    this.sequenceCounter = 0;
    this.receiptCache = new Map();
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
    this.selection = [...initialSelection];
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.documentRevision = 0;
    this.sessionRevision = 0;
    this.compositionState = 'Idle'; // Idle | Composing | AwaitingSettlement
    this.undoStack = [];
    this.redoStack = [];
    this.commandOutcomes = new Map();
    this.typingIntent = {};
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
      epoch: this.epoch,
      connectionState: this.connectionState,
      sequenceCounter: this.sequenceCounter,
      receiptCache: new Map(Array.from(this.receiptCache.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))])),
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
      selection: [...this.selection],
      sessionIslands: JSON.parse(JSON.stringify(this.sessionIslands)),
      sessionCommittedRanges: JSON.parse(JSON.stringify(this.sessionCommittedRanges)),
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision,
      compositionState: this.compositionState,
      undoStack: JSON.parse(JSON.stringify(this.undoStack)),
      redoStack: JSON.parse(JSON.stringify(this.redoStack)),
      commandOutcomes: new Map(Array.from(this.commandOutcomes.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))])),
      typingIntent: { ...this.typingIntent }
    };
  }

  _restoreState(snap) {
    this.epoch = snap.epoch;
    this.connectionState = snap.connectionState;
    this.sequenceCounter = snap.sequenceCounter;
    this.receiptCache = snap.receiptCache;
    this.ticketCounter = snap.ticketCounter;
    this.sessionCounter = snap.sessionCounter;
    this.settlementSeqCounter = snap.settlementSeqCounter;
    this.committedDoc = snap.committedDoc;
    this.provisionalOverlay = snap.provisionalOverlay;
    this.markedWindow = snap.markedWindow;
    this.attributionEpoch = snap.attributionEpoch;
    this.historySession = snap.historySession;
    this.settlementObligation = snap.settlementObligation;
    this.settlementEvidence = snap.settlementEvidence;
    this.selection = snap.selection;
    this.sessionIslands = snap.sessionIslands;
    this.sessionCommittedRanges = snap.sessionCommittedRanges;
    this.documentRevision = snap.documentRevision;
    this.sessionRevision = snap.sessionRevision;
    this.compositionState = snap.compositionState;
    this.undoStack = snap.undoStack;
    this.redoStack = snap.redoStack;
    this.commandOutcomes = snap.commandOutcomes;
    this.typingIntent = snap.typingIntent;
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

  get docText() {
    return this.getVisibleUnits().map(u => u.char).join('');
  }

  get docAttributes() {
    return this.getVisibleUnits().map(u => ({ ...u.attrs }));
  }

  get markedRange() {
    if (!this.provisionalOverlay || !this.markedWindow) return null;
    if (!this.provisionalOverlay.segments || this.provisionalOverlay.segments.length === 0) return null;
    const sc = this.provisionalOverlay.segments[0].dcStart;
    return [sc + this.markedWindow.relStart, sc + this.markedWindow.relEnd];
  }

  get activeSessionId() {
    return this.historySession ? this.historySession.sessionId : null;
  }

  get activeSettlementToken() {
    return this.settlementObligation.activeFence ? { token: this.settlementObligation.activeFence.token } : null;
  }

  // Computes the exact bounding extent of all content touched by this composition session
  _computeSessionExtent() {
    const ranges = [];
    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const r of this.sessionCommittedRanges) {
        if (r[0] < r[1]) ranges.push([...r]);
      }
    }
    if (this.markedRange && this.markedRange[0] < this.markedRange[1]) {
      ranges.push([...this.markedRange]);
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
    const curLen = this.docText.length;
    let range;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { valid: false };
      }
      range = [Math.min(payload.range[0], payload.range[1]), Math.max(payload.range[0], payload.range[1])];
    } else {
      if (this.markedRange) {
        range = [...this.markedRange];
      } else {
        const sel = this.selection || [0, 0];
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
  applyInput(payload, seq = null) {
    if (seq !== null) {
      if (this.receiptCache.has(seq)) {
        return { status: 'Duplicate', cachedReceipt: this.receiptCache.get(seq) };
      }
    }

    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }

    // Connection Epoch Admission (Finding F): validate connection identity on all native callbacks
    if (payload.epoch !== undefined && payload.epoch !== null) {
      if (payload.epoch < this.epoch) {
        return { status: 'Rejected', reason: 'StaleEpoch' };
      }
      if (payload.epoch > this.epoch) {
        return { status: 'Rejected', reason: 'FutureEpoch' };
      }
    }

    if (payload.injectFailure && !payload.injectFailureAt) {
      return { status: 'Rejected', reason: 'InjectedFailure' };
    }

    // Upfront validation of input parameters before mutation
    const curLen = this.docText.length;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { status: 'Rejected', reason: 'InvalidTargetRange' };
      }
    }

    const prop = this._computeProposedPostLength(payload);
    if (!prop.valid) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    // Selection validation against proposed post-edit document length (Finding D)
    if (payload.selection !== undefined && payload.selection !== null) {
      if (!this._validateSelection(payload.selection, prop.proposedLen)) {
        return { status: 'Rejected', reason: 'InvalidSelection' };
      }
    }

    if (payload.type === 'partialCommit') {
      const k = payload.length !== undefined ? payload.length : payload.committedLength;
      if (!Number.isInteger(k) || k < 0) {
        return { status: 'Rejected', reason: 'InvalidPartialCommitCount' };
      }
    }

    // Atomic transaction state backup for staged failure injection (Section 4.3)
    const preTxSnapshot = this._cloneState();
    const prevDocText = this.docText;
    const prevDocAttrs = this.docAttributes;
    this._inApplyInput = true;
    let result;

    try {
      switch (payload.type) {
        case 'startComposition':
          result = this._startComposition(prop.range, payload.selection, payload.sessionId, payload.injectFailureAt);
          break;
        case 'preedit':
          result = this._preedit(payload.text, prop.range, payload.selection, payload.injectFailureAt);
          break;
        case 'partialCommit':
          result = this._partialCommit(payload.length !== undefined ? payload.length : payload.committedLength, payload.selection, payload.injectFailureAt);
          break;
        case 'targetExpansion':
        case 'retarget':
          result = this._retarget(prop.range, payload.selection, payload.injectFailureAt);
          break;
        case 'unmark':
          result = this._unmark(payload.settled !== false, payload.selection, payload.injectFailureAt);
          break;
        case 'completeSettlement':
          result = this._completeSettlement(payload.token, payload.sessionId, payload.epoch, payload.injectFailureAt);
          break;
        case 'commit':
          result = this._commit(payload.text, prop.range, payload.selection, payload.injectFailureAt);
          break;
        case 'cancel':
        case 'cancelUncommitted':
        case 'applicationAbort':
          result = this._cancel(payload.injectFailureAt);
          break;
        case 'nativeRemoval':
          result = this._nativeRemoval(prop.range, payload.replacementText, payload.selection, payload.injectFailureAt);
          break;
        case 'queueCommand':
          result = this._queueCommand(payload.cmd, payload.target, payload.injectFailureAt);
          break;
        case 'onFocusLost':
          result = this._onFocusLost(payload.strategy, payload.injectFailureAt);
          break;
        case 'openNewConnection':
          result = this._openNewConnection(payload.injectFailureAt);
          break;
        case 'dispose':
          result = this._dispose(payload.injectFailureAt);
          break;
        case 'undo':
          result = this.undo();
          break;
        case 'redo':
          result = this.redo();
          break;
        case 'setSelection':
          if (payload.selection && this._validateSelection(payload.selection, this.docText.length)) {
            this.selection = [...payload.selection];
            result = { type: 'SelectionChanged', status: 'Accepted', selection: [...this.selection] };
          } else {
            result = { status: 'Rejected', reason: 'InvalidSelection' };
          }
          break;
        default:
          result = { status: 'Rejected', reason: `UnknownEvent:${payload.type}` };
      }

      // Staged failure injection check before receipt publication
      if (payload.injectFailureAt === 'beforeReceiptPublication') {
        throw new Error('InjectedFailure:beforeReceiptPublication');
      }

      // Rejection atomicity: if the operation is rejected, restore pre-transaction state
      if (result && result.status === 'Rejected') {
        this._restoreState(preTxSnapshot);
        return result;
      }

      // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
      const newDocText = this.docText;
      const newDocAttrs = this.docAttributes;
      if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
        this.documentRevision++;
      }

      // Branch invalidation: editing operations clear redo stack
      if (payload.type !== 'undo' && payload.type !== 'redo' && result.status === 'Accepted') {
        if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
          this.redoStack = [];
        }
      }

      // Synchronize receipts with actual published document revision and selection
      if (result && result.status !== 'Rejected') {
        if (result.documentRevision !== undefined) {
          result.documentRevision = this.documentRevision;
        }
        if (result.selection !== undefined) {
          result.selection = [...this.selection];
        }
      }

      if (seq !== null && result && result.status !== 'Rejected') {
        this.receiptCache.set(seq, result);
      }
      return result;

    } catch (e) {
      // Atomic transaction rollback upon failure injection or unhandled error
      this._restoreState(preTxSnapshot);
      return { status: 'Rejected', reason: e.message || 'TransactionFailure' };
    } finally {
      this._inApplyInput = false;
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
          attrs: cloneAttrs(this.docAttributes),
          selection: [...this.selection]
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
      markedRange: [...this.markedRange],
      selection: [...this.selection],
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
      if (!this.markedRange || (range && (range[0] !== this.markedRange[0] || range[1] !== this.markedRange[1]))) {
        const retargetRes = this._retarget(range, selection, injectStage);
        if (retargetRes.status === 'Rejected') {
          return retargetRes;
        }
      }
    } else if (this.compositionState === 'Composing') {
      if (range && (!this.markedRange || range[0] !== this.markedRange[0] || range[1] !== this.markedRange[1])) {
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
        tokenId: `prov:${this.activeSessionId}:e${epoch.epochId}:m${j}`,
        char: midText[j],
        attrs: { ...epoch.seed },
        originSessionId: this.activeSessionId
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
      markedRange: [...this.markedRange],
      selection: [...this.selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _retarget(range, selection = null, injectStage = null) {
    const [u, v] = range;
    if (u < 0 || v > this.docText.length || u > v) {
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
      const seed = (u >= 0 && u < this.committedDoc.length) ? { ...this.committedDoc[u].attrs } : {};
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
            attrs: cloneAttrs(this.docAttributes),
            selection: [...this.selection]
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
        markedRange: [...this.markedRange],
        selection: [...this.selection],
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
    const seed = (u < this.committedDoc.length) ? { ...this.committedDoc[u].attrs } : {};

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
      markedRange: [...this.markedRange],
      selection: [...this.selection],
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
      markedRange: this.markedRange ? [...this.markedRange] : null,
      selection: [...this.selection],
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
    const finalExtent = [...this.markedRange];
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
      const curText = this.docText;
      const curAttrs = this.docAttributes;
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: bSel,
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterSelection: [...this.selection],
          extent: finalExtent,
          range: finalExtent,
          selection: bSel
        });
        this.redoStack = [];
      }

      this._drainPendingCommands(finalExtent, injectStage);
      this.historySession = null;
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
        sessionId: this.activeSessionId,
        epoch: this.epoch
      };
      this.settlementEvidence = {
        token: tok,
        sessionId: this.activeSessionId,
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
    if (sessionId && this.activeSessionId && sessionId !== this.activeSessionId) {
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
    const curText = this.docText;
    const curAttrs = this.docAttributes;
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterSelection: [...this.selection],
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
    const cancelPoint = (this.provisionalOverlay && this.provisionalOverlay.segments && this.provisionalOverlay.segments.length > 0)
      ? this.provisionalOverlay.segments[0].dcStart
      : 0;

    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.attributionEpoch = null;
    this.compositionState = 'Idle';

    const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
    if (snap) {
      this.selection = [...snap.selection];
    }
    const curText = this.docText;
    const curAttrs = this.docAttributes;
    const netRollbackDelta = curText.length - prevDocLen;

    const sessionExtent = this._computeSessionExtent();
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [0, 0];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterSelection: [...this.selection],
        extent: sessionExtent,
        range: sessionExtent,
        selection: bSel
      });
      this.redoStack = [];
    }

    // Cancel CompositionTarget commands
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        cmdRec.status = 'Cancelled';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
      } else if (cmdRec.target && cmdRec.target.type === 'TrackedRange' && netRollbackDelta !== 0) {
        const r = cmdRec.target.range;
        if (r && r.length === 2) {
          if (r[0] >= cancelPoint) {
            r[0] = Math.max(0, r[0] + netRollbackDelta);
            r[1] = Math.max(r[0], r[1] + netRollbackDelta);
          } else if (r[1] > cancelPoint) {
            r[1] = Math.max(r[0], r[1] + netRollbackDelta);
          }
        }
      }
    }

    // Surviving unrelated commands MUST be drained and executed immediately (Finding E)
    const surviving = this.settlementObligation.pendingCommands.filter(
      cmd => cmd.status === 'Pending'
    );
    this.settlementObligation.pendingCommands = [];

    for (const cmdRec of surviving) {
      if (cmdRec.cmd.type === 'Format') {
        const patch = cmdRec.cmd.patch;
        let targetRanges = [];
        if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
          const r = cmdRec.target.range;
          if (r && r.length === 2 && r[0] <= r[1] && r[1] <= curText.length) {
            targetRanges.push([r[0], r[1]]);
          } else {
            cmdRec.status = 'Rejected';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
            continue;
          }
        } else if (cmdRec.target && cmdRec.target.type === 'SelectionTarget') {
          targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
        }

        const beforeAttrs = cloneAttrs(this.docAttributes);
        let anyChanged = false;
        const appliedRanges = [];

        for (const [tStart, tEnd] of targetRanges) {
          if (tStart === tEnd) continue;
          const [formatStart, formatEnd] = expandToGraphemeBoundaries(curText, [tStart, tEnd]);
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
          this.undoStack.push({
            group: 'Formatting',
            beforeText: this.docText,
            beforeAttrs,
            beforeSelection: [...this.selection],
            afterText: this.docText,
            afterAttrs: cloneAttrs(this.docAttributes),
            afterSelection: [...this.selection],
            range: appliedRanges[0] || [0, 0],
            ranges: appliedRanges,
            patch: cmdRec.cmd.patch,
            selection: [...this.selection]
          });
          this.redoStack = [];
        }

        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          range: appliedRanges[0] || [0, 0],
          ranges: appliedRanges,
          patch: cmdRec.cmd.patch
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
    } else if (this.compositionState === 'Composing' && this.markedRange) {
      [rStart, rEnd] = this.markedRange;
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

      const curText = this.docText;
      const curAttrs = this.docAttributes;
      const extent = [rStart, rStart + replacementText.length];

      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterSelection: [...this.selection],
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
        selection: [...this.selection]
      };
    } else {
      const beforeText = this.docText;
      const beforeAttrs = cloneAttrs(this.docAttributes);
      const beforeSelection = [...this.selection];

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

      const afterText = this.docText;
      const afterAttrs = cloneAttrs(this.docAttributes);
      const afterSelection = [...this.selection];
      const extent = [rStart, rStart + replacementText.length];

      if (afterText !== beforeText || !areAttrsEqual(afterAttrs, beforeAttrs)) {
        this.undoStack.push({
          group: 'NativeRemoval',
          beforeText,
          beforeAttrs,
          beforeSelection,
          afterText,
          afterAttrs,
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
        selection: [...this.selection]
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
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const sessionExtent = this._computeSessionExtent();
      this._flushOverlayToCommitted(injectStage);
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this.docText;
      const curAttrs = this.docAttributes;
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterSelection: [...this.selection],
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
    this.compositionState = 'Idle';
    this.connectionState = 'Active';
    this.epoch++;
    this.receiptCache.clear(); // Evict receipt cache on connection replacement (Finding F)
    this.sessionRevision++;

    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'StaleEpoch' });
    }
    this.settlementObligation.pendingCommands = [];
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;

    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this.epoch };
  }

  _dispose(injectStage = null) {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const sessionExtent = this._computeSessionExtent();
      this._flushOverlayToCommitted(injectStage);
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this.docText;
      const curAttrs = this.docAttributes;
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterSelection: [...this.selection],
          extent: sessionExtent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }
    }

    this.historySession = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.compositionState = 'Idle';
    this.connectionState = 'Closed';
    this.receiptCache.clear();

    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this.settlementObligation.pendingCommands = [];
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;
    this.sessionRevision++;

    return { type: 'Disposed', status: 'Accepted' };
  }

  _queueCommand(cmd, target, injectStage = null) {
    if (injectStage === 'duringCommandPreparation') {
      throw new Error('InjectedFailure:duringCommandPreparation');
    }
    const ticketId = this.nextTicketId();
    const record = {
      ticketId,
      cmd,
      target: JSON.parse(JSON.stringify(target)),
      status: 'Pending',
      sessionId: this.activeSessionId,
      epoch: this.epoch
    };
    this.settlementObligation.pendingCommands.push(record);
    return { status: 'Accepted', ticketId };
  }

  _drainPendingCommands(settledExtent, injectStage = null) {
    if (injectStage === 'duringCommandPreparation') {
      throw new Error('InjectedFailure:duringCommandPreparation');
    }

    const [eStart, eEnd] = settledExtent || [0, 0];
    const fullDocText = this.docText;

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

    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.status !== 'Pending') continue;

      if (cmdRec.cmd.type === 'Format') {
        const patch = cmdRec.cmd.patch;
        let targetRanges = [];

        if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
          const r = cmdRec.target.range;
          if (r && r.length === 2) {
            targetRanges.push([Math.max(0, r[0]), Math.min(fullDocText.length, r[1])]);
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
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Applied', typingIntentUpdated: true });
            continue;
          }
          targetRanges = mergedSessionRanges.length > 0 ? mergedSessionRanges : [[eStart, eEnd]];
        } else if (cmdRec.target && cmdRec.target.type === 'SelectionTarget') {
          targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
        } else {
          targetRanges = [[eStart, eEnd]];
        }

        const beforeAttrs = cloneAttrs(this.docAttributes);
        let anyChanged = false;
        const appliedRanges = [];

        for (const [tStart, tEnd] of targetRanges) {
          if (tStart === tEnd) continue;
          const [formatStart, formatEnd] = expandToGraphemeBoundaries(fullDocText, [tStart, tEnd]);
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
          this.undoStack.push({
            group: 'Formatting',
            beforeText: this.docText,
            beforeAttrs,
            beforeSelection: [...this.selection],
            afterText: this.docText,
            afterAttrs: cloneAttrs(this.docAttributes),
            afterSelection: [...this.selection],
            range: appliedRanges[0] || [0, 0],
            ranges: appliedRanges,
            patch: cmdRec.cmd.patch,
            selection: [...this.selection]
          });
          this.redoStack = [];
        }

        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          range: appliedRanges[0] || [0, 0],
          ranges: appliedRanges,
          patch: cmdRec.cmd.patch
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
  undo() {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this.docText;
      const curAttrs = this.docAttributes;
      const modified = snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs));

      if (modified) {
        const sessionExtent = this._computeSessionExtent();
        this._flushOverlayToCommitted();
        const bSel = snap.selection ? [...snap.selection] : [0, 0];
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: bSel,
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterSelection: [...this.selection],
          extent: sessionExtent,
          range: sessionExtent,
          selection: bSel
        });
        this.redoStack = [];
        this._drainPendingCommands(sessionExtent);
      } else {
        this.provisionalOverlay = null;
        if (snap) this.selection = [...snap.selection];
      }
      this.historySession = null;
      this.markedWindow = null;
      this.attributionEpoch = null;
      this.sessionIslands = [];
      this.sessionCommittedRanges = [];
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
      this.compositionState = 'Idle';
    }

    if (this.undoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToUndo' };
    }

    const prevDocText = this.docText;
    const prevDocAttrs = this.docAttributes;
    const entry = this.undoStack.pop();

    const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
    const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this.selection];

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

    const curText = this.docText;
    const curAttrs = this.docAttributes;
    if (!this._inApplyInput && (curText !== prevDocText || !areAttrsEqual(curAttrs, prevDocAttrs))) {
      this.documentRevision++;
    }
    this.sessionRevision++;

    return {
      status: 'Accepted',
      action: 'Undo',
      group: entry.group,
      docText: this.docText,
      selection: [...this.selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  redo() {
    if (this.redoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToRedo' };
    }
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this.docText;
      const curAttrs = this.docAttributes;
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

    const prevDocText = this.docText;
    const prevDocAttrs = this.docAttributes;
    const entry = this.redoStack.pop();

    const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
    const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this.selection];

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

    const curText = this.docText;
    const curAttrs = this.docAttributes;
    if (!this._inApplyInput && (curText !== prevDocText || !areAttrsEqual(curAttrs, prevDocAttrs))) {
      this.documentRevision++;
    }
    this.sessionRevision++;

    return {
      status: 'Accepted',
      action: 'Redo',
      group: entry.group,
      docText: this.docText,
      selection: [...this.selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }
}
