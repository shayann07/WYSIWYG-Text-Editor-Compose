// Candidate B Coordinator: Pure Segment-Mapped Overlay & Committed Storage Engine (§1, §2, §4)
// Resolved & Repaired Architecture (run-20261014-06):
// - Separated committed storage (committedDoc: Dc) and provisionalOverlay (piecewise OverlaySegments)
// - Native markedWindow independent of overlay footprint
// - Segment-aware partial commit with explicit cut across zero-length deletion segments (Gap 4.4)
// - Preserves absorbed original units on partial commit + cancel (Gap 4.3)
// - Replacement rollback ownership cleanly partitioned across nested replacements (Gap 4.5)
// - Outward Unicode grapheme cluster closure via Intl.Segmenter (Gap 4.1, Defect D)
// - Interior insertion inside multi-unit segments with exact rollback ownership (Defect A)
// - Implicit preedit respects noncollapsed/reversed selection bounds (Defect B)
// - Atomic overlay materialization to eliminate history snapshot corruption (Defect C)
// - Unrelated command ticket conservation on composition cancellation (Defect E)
// - Full partial commit preserves active composition and queued commands (Defect F)
// - Upfront validation rejecting invalid target ranges atomically (Defect G)
// - Dynamic island and command target mapping across preceding edits (Defect H)
// - Dedicated TrackedRange target resolution and session mismatch rejection (Defect I)

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
    if (payload.injectFailure) {
      return { status: 'Rejected', reason: 'InjectedFailure' };
    }

    // Upfront validation of input parameters before mutation
    const curLen = this.docText.length;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { status: 'Rejected', reason: 'InvalidTargetRange' };
      }
    }
    if (payload.selection !== undefined && payload.selection !== null) {
      if (!this._validateSelection(payload.selection, curLen)) {
        return { status: 'Rejected', reason: 'InvalidSelection' };
      }
    }
    if (payload.type === 'partialCommit') {
      const k = payload.length !== undefined ? payload.length : payload.committedLength;
      if (!Number.isInteger(k) || k < 0) {
        return { status: 'Rejected', reason: 'InvalidPartialCommitCount' };
      }
    }

    const prevDocText = this.docText;
    const prevDocAttrs = this.docAttributes;
    let result;

    switch (payload.type) {
      case 'startComposition':
        result = this._startComposition(payload.range, payload.selection, payload.sessionId);
        break;
      case 'preedit':
        result = this._preedit(payload.text, payload.range, payload.selection);
        break;
      case 'partialCommit':
        result = this._partialCommit(payload.length !== undefined ? payload.length : payload.committedLength, payload.selection);
        break;
      case 'targetExpansion':
      case 'retarget':
        result = this._retarget(payload.range || [payload.newStart, payload.newEnd], payload.selection);
        break;
      case 'unmark':
        result = this._unmark(payload.settled !== false, payload.selection);
        break;
      case 'completeSettlement':
        result = this._completeSettlement(payload.token, payload.sessionId, payload.epoch);
        break;
      case 'commit':
        result = this._commit(payload.text, payload.range, payload.selection);
        break;
      case 'cancel':
      case 'cancelUncommitted':
      case 'applicationAbort':
        result = this._cancel();
        break;
      case 'nativeRemoval':
        result = this._nativeRemoval(payload.range, payload.replacementText);
        break;
      case 'queueCommand':
        result = this._queueCommand(payload.cmd, payload.target);
        break;
      case 'onFocusLost':
        result = this._onFocusLost(payload.strategy);
        break;
      case 'openNewConnection':
        result = this._openNewConnection();
        break;
      case 'dispose':
        result = this._dispose();
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

    // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
    const newDocText = this.docText;
    const newDocAttrs = this.docAttributes;
    if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
      this.documentRevision++;
    }

    if (seq !== null && result.status !== 'Rejected') {
      this.receiptCache.set(seq, result);
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Event Implementations
  // -------------------------------------------------------------------------
  _startComposition(range, selection = null, sessionId = null) {
    if (this.compositionState !== 'Idle') {
      return { status: 'Rejected', reason: 'CompositionAlreadyActive' };
    }
    const [u, v] = range;
    const sId = sessionId || `session-${++this.sessionCounter}`;

    // Convert committed range [u, v) into initial 1-unit segments
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

    this.historySession = {
      sessionId: sId,
      preSessionDocSnapshot: {
        text: this.docText,
        attrs: this.docAttributes,
        selection: [...this.selection]
      }
    };

    this.compositionState = 'Composing';
    this.sessionIslands = [[u, v]];
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

      // Interior insertion strictly inside a multi-unit segment: segStart < aMid < segEnd
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

  _preedit(text, range = null, selection = null) {
    // If Idle, start composition. Defect B: respect noncollapsed selection bounds!
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
      // Resume composition, invalidating settlement token
      this.compositionState = 'Composing';
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
    }

    // Defect G: Validate and propagate retarget rejections atomically
    if (range && (!this.markedRange || range[0] !== this.markedRange[0] || range[1] !== this.markedRange[1])) {
      const retargetRes = this._retarget(range, selection);
      if (retargetRes.status === 'Rejected') {
        return retargetRes;
      }
    }

    // Invalidate pending settlement fence
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;

    const epoch = this.attributionEpoch;
    const B = epoch.B_epoch;
    const baseSegs = epoch.baseSegments;

    // Compute maximal common prefix and suffix against B_epoch
    let p = 0;
    while (p < text.length && p < B.length && text[p] === B[p].char) {
      p++;
    }
    let s = 0;
    while (s < (text.length - p) && s < (B.length - p) && text[text.length - 1 - s] === B[B.length - 1 - s].char) {
      s++;
    }

    // Changed middle units
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

    // Changed middle slice [relStart_0 + p, relEnd_0 - s) in baseSegments
    const relStart0 = epoch.baseWindow.relStart;
    const relEnd0 = epoch.baseWindow.relEnd;
    const aMid = relStart0 + p;
    const bMid = relEnd0 - s;

    // Defect A: Distinguish interior insertion from boundary insertion!
    const newSegments = [];
    let curVis = 0;
    let firstTouched = -1;
    let lastTouched = -1;
    let leftUnits = [];
    let rightUnits = [];

    if (aMid === bMid) {
      // Insertion (zero-width replacement)
      const found = this._findInteriorSegment(baseSegs, aMid);
      if (found) {
        firstTouched = found.index;
        lastTouched = found.index;
        leftUnits = found.leftUnits;
        rightUnits = found.rightUnits;
      }
    } else {
      // Range replacement (aMid < bMid)
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
      // Pure insertion before, after, or at segment boundary
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
      // Splice into or across touched segment(s)
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

    this.provisionalOverlay.segments = newSegments;
    this.markedWindow = { relStart: relStart0, relEnd: relStart0 + text.length };

    // Defect H: Map disjoint islands and command targets across this edit
    const sc = newSegments[0].dcStart;
    const editVisibleStart = sc + aMid;
    const editVisibleEnd = sc + bMid;
    const delta = text.length - (bMid - aMid);

    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      // Map other islands before last
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

    // Map pending TrackedRange command targets
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

  _retarget(range, selection = null) {
    const [u, v] = range;
    if (u < 0 || v > this.docText.length || u > v) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    if (!this.provisionalOverlay) {
      return this._startComposition(range, selection);
    }

    const segs = this.provisionalOverlay.segments;
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;
    const L = segs.flatMap(s => s.units).length;

    // Absorb newly covered units on left
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

  _partialCommit(k, selection = null) {
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

    // Partition segments at pCut
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

      // Gap 4.4 Rule: A zero-length deletion segment (segLen == 0) belongs to committed
      // iff cut strictly passes deletion point (segStart < pCut) OR the marked candidate
      // is fully committed (pCut === this.markedWindow.relEnd) and the deletion was at or before the candidate boundary.
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
        // Cut splits strictly inside this segment
        const commUnits = seg.units.slice(0, pCut - segStart);
        const remUnits = seg.units.slice(pCut - segStart);
        consumedSegments.push({
          dcStart: seg.dcStart,
          dcEnd: seg.dcEnd,
          units: commUnits
        });
        dCut = seg.dcEnd;
        splitSegmentTail = remUnits;
      } else {
        remainingSegments.push(seg);
      }
    }

    const P_commit = consumedSegments.flatMap(s => s.units);

    // Splice P_commit into committedDoc replacing [sc, dCut)
    const newCommittedDoc = [
      ...this.committedDoc.slice(0, sc),
      ...P_commit,
      ...this.committedDoc.slice(dCut)
    ];
    this.committedDoc = newCommittedDoc;

    // Construct remaining overlay segments in updated Dc coordinates
    const delta = P_commit.length - (dCut - sc);
    const newSc = sc + P_commit.length;
    const finalRemSegments = [];

    if (splitSegmentTail && splitSegmentTail.length > 0) {
      finalRemSegments.push({
        dcStart: newSc,
        dcEnd: newSc,
        units: splitSegmentTail
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

    // Defect F: A full partial commit (remLen === 0) preserves Composing state and queued commands!
    if (finalRemSegments.length === 0 || remLen === 0) {
      // Empty active composition remains in Composing state
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
      this.sessionIslands = [[newSc, newSc]];
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
      this.sessionIslands = [[newSc, newSc + remLen]];
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

  // Defect C: Atomic materialization of overlay to committed storage
  _flushOverlayToCommitted() {
    if (!this.provisionalOverlay) return;
    const segs = this.provisionalOverlay.segments;
    if (!segs || segs.length === 0) {
      this.provisionalOverlay = null;
      return;
    }
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;
    const flatUnits = segs.flatMap(s => s.units);

    this.committedDoc = [
      ...this.committedDoc.slice(0, sc),
      ...flatUnits,
      ...this.committedDoc.slice(ec)
    ];
    // Atomically nullify overlay so getters cannot see stale overlay on updated committedDoc!
    this.provisionalOverlay = null;
  }

  _unmark(settled = true, selection = null) {
    if (!this.markedWindow) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    const finalExtent = [...this.markedRange];
    this.markedWindow = null;
    if (selection) this.selection = [...selection];
    else this.selection = [finalExtent[1], finalExtent[1]];

    if (settled) {
      // Defect C: Flush atomically
      this._flushOverlayToCommitted();
      this.compositionState = 'Idle';

      const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
      const curText = this.docText;
      const curAttrs = this.docAttributes;
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          extent: finalExtent
        });
        this.redoStack = [];
      }

      this._drainPendingCommands(finalExtent);
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
      // Nonterminal unmark
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

  _completeSettlement(token = null, sessionId = null, epoch = null) {
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
    this._flushOverlayToCommitted();
    this.compositionState = 'Idle';

    const snap = this.historySession ? this.historySession.preSessionDocSnapshot : null;
    const curText = this.docText;
    const curAttrs = this.docAttributes;
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        extent: finalExtent
      });
      this.redoStack = [];
    }

    this._drainPendingCommands(finalExtent);
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

  _commit(text = null, range = null, selection = null) {
    if (text !== null) {
      this._preedit(text, range, selection);
    }
    return this._unmark(true, selection);
  }

  _cancel() {
    const prevDocLen = this.docText.length;
    const cancelPoint = (this.provisionalOverlay && this.provisionalOverlay.segments && this.provisionalOverlay.segments.length > 0)
      ? this.provisionalOverlay.segments[0].dcStart
      : 0;

    // Cancellation discards provisionalOverlay completely, revealing Dc!
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

    // If partialCommit occurred, Dc has net committed differences relative to pre-session snapshot
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs)
      });
      this.redoStack = [];
    }

    // Defect E: Cancel ONLY commands targeting CompositionTarget; preserve unrelated commands!
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        cmdRec.status = 'Cancelled';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
      } else if (cmdRec.target && cmdRec.target.type === 'TrackedRange' && netRollbackDelta !== 0) {
        // Remap unrelated TrackedRange targets across net rollback delta
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
    // Retain unrelated pending commands in pendingCommands!
    this.settlementObligation.pendingCommands = this.settlementObligation.pendingCommands.filter(
      cmd => cmd.status === 'Pending'
    );

    this.historySession = null;
    this.settlementObligation.activeFence = null;
    this.settlementEvidence = null;
    this.sessionIslands = [];
    this.sessionRevision++;

    return {
      type: 'Cancelled',
      status: 'Accepted',
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _nativeRemoval(range, replacementText = "") {
    const [rStart, rEnd] = range || (this.markedRange ? this.markedRange : [0, 0]);
    // Apply excision directly to visible coordinates by flushing Dc atomically
    this._flushOverlayToCommitted();
    this.markedWindow = null;
    this.compositionState = 'Idle';

    const newDoc = [
      ...this.committedDoc.slice(0, rStart),
      ...replacementText.split('').map((c, i) => ({
        tokenId: `rem:${i}`,
        char: c,
        attrs: (rStart < this.committedDoc.length) ? { ...this.committedDoc[rStart].attrs } : {},
        originSessionId: null
      })),
      ...this.committedDoc.slice(rEnd)
    ];
    this.committedDoc = newDoc;
    this.historySession = null;
    this.sessionRevision++;

    return {
      type: 'NativeRemovalApplied',
      status: 'Accepted',
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _onFocusLost(strategy = 'cancel') {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      if (strategy === 'settle') {
        const res = this._unmark(true);
        return { type: 'FocusLost', status: res.status, action: 'Settled' };
      } else {
        const res = this._cancel();
        return { type: 'FocusLost', status: res.status, action: 'Cancelled' };
      }
    }
    return { type: 'FocusLost', status: 'Accepted', action: 'None' };
  }

  _openNewConnection() {
    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.attributionEpoch = null;
    this.historySession = null;
    this.sessionIslands = [];
    this.compositionState = 'Idle';
    this.connectionState = 'Active';
    this.epoch++;
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

  _dispose() {
    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.attributionEpoch = null;
    this.historySession = null;
    this.sessionIslands = [];
    this.compositionState = 'Idle';
    this.connectionState = 'Closed';
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

  _queueCommand(cmd, target) {
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

  _drainPendingCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];
    const fullDocText = this.docText;

    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.status !== 'Pending') continue;

      if (cmdRec.cmd.type === 'Format') {
        const patch = cmdRec.cmd.patch;

        // Defect I: Resolve command's OWN target type and identity
        let targetRanges = [];

        if (cmdRec.target && cmdRec.target.type === 'TrackedRange') {
          // TrackedRange specifies its own range
          const r = cmdRec.target.range;
          if (r && r.length === 2) {
            targetRanges.push([Math.max(0, r[0]), Math.min(fullDocText.length, r[1])]);
          }
        } else if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
          // Check for session mismatch if explicit sessionId provided
          if (cmdRec.target.sessionId && this.historySession && cmdRec.target.sessionId !== this.historySession.sessionId) {
            cmdRec.status = 'Rejected';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Rejected', reason: 'TargetSessionMismatch' });
            continue;
          }
          if (eStart === eEnd) {
            this.typingIntent = { ...this.typingIntent, ...patch };
            cmdRec.status = 'Applied';
            this.commandOutcomes.set(cmdRec.ticketId, { status: 'Applied', typingIntentUpdated: true });
            continue;
          }
          // Defect H: Use constituent session islands (mapped through edits)
          targetRanges = (this.sessionIslands && this.sessionIslands.length > 1)
            ? this.sessionIslands
            : [[eStart, eEnd]];
        } else if (cmdRec.target && cmdRec.target.type === 'SelectionTarget') {
          targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
        } else {
          // Default to settledExtent
          targetRanges = [[eStart, eEnd]];
        }

        const beforeAttrs = this.docAttributes;
        let anyChanged = false;
        const appliedRanges = [];

        for (const [tStart, tEnd] of targetRanges) {
          if (tStart === tEnd) continue;
          // Defect D: Canonical outward grapheme cluster expansion
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
            beforeAttrs,
            afterAttrs: this.docAttributes,
            range: appliedRanges[0] || [0, 0],
            ranges: appliedRanges
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
    this.settlementObligation.pendingCommands = this.settlementObligation.pendingCommands.filter(
      c => c.status === 'Pending'
    );
  }
}
