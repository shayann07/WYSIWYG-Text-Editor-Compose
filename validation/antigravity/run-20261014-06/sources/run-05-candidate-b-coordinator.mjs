// Candidate B Coordinator: Pure Segment-Mapped Overlay & Committed Storage Engine (§1, §2, §4)
// Implements the v5.3 composition architecture with the completed semantic gap resolutions:
// - Separated committed storage (committedDoc: Dc) and provisionalOverlay (piecewise OverlaySegments)
// - Native markedWindow independent of overlay footprint
// - Segment-aware partial commit with explicit cut across zero-length deletion segments (Gap 4.4)
// - Preserves absorbed original units on partial commit + cancel (Gap 4.3)
// - Replacement rollback ownership cleanly partitioned (Gap 4.5)
// - Outward grapheme closure and intent-based command formatting (Gap 4.1)
// - Monotonic settlement tokens, strict invalidation, and distinct cancellation (Gap 4.7)

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
    if (!this.provisionalOverlay || this.provisionalOverlay.segments.length === 0) {
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
  // Normalized Event Dispatcher
  // -------------------------------------------------------------------------
  applyInput(payload, seq = null) {
    if (seq !== null) {
      if (this.receiptCache.has(seq)) {
        return { status: 'Duplicate', cachedReceipt: this.receiptCache.get(seq) };
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
        result = this._partialCommit(payload.length || payload.committedLength, payload.selection);
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
      segments.push({ dcStart: u, dcEnd: u, units: [] });
    } else {
      for (let i = u; i < v; i++) {
        segments.push({
          dcStart: i,
          dcEnd: i + 1,
          units: [{ ...this.committedDoc[i], attrs: { ...this.committedDoc[i].attrs } }]
        });
      }
    }

    this.provisionalOverlay = { blockId: 'root', segments };
    this.markedWindow = { relStart: 0, relEnd: v - u };
    
    const seed = (u < this.committedDoc.length) ? { ...this.committedDoc[u].attrs } : {};
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

  _preedit(text, range = null, selection = null) {
    if (this.compositionState === 'Idle') {
      const pos = this.selection[0];
      this._startComposition([pos, pos]);
    } else if (this.compositionState === 'AwaitingSettlement') {
      // Resume composition, invalidating settlement token
      this.compositionState = 'Composing';
      this.settlementObligation.activeFence = null;
      this.settlementEvidence = null;
    }

    if (range && (!this.markedRange || range[0] !== this.markedRange[0] || range[1] !== this.markedRange[1])) {
      this._retarget(range);
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

    // Replace changed middle slice [relStart_0 + p, relEnd_0 - s) in baseSegments
    const relStart0 = epoch.baseWindow.relStart;
    const relEnd0 = epoch.baseWindow.relEnd;
    const aMid = relStart0 + p;
    const bMid = relEnd0 - s;

    // Find touched segment range in baseSegments
    const newSegments = [];
    let curVis = 0;
    let segIdx = 0;
    let firstTouched = -1;
    let lastTouched = -1;
    let leftUnits = [];
    let rightUnits = [];

    // Map baseSegments to find boundary
    for (let i = 0; i < baseSegs.length; i++) {
      const seg = baseSegs[i];
      const segStart = curVis;
      const segEnd = curVis + seg.units.length;
      curVis = segEnd;

      if (aMid === bMid && midUnits.length > 0 && segEnd === aMid && i + 1 < baseSegs.length) {
        // Pure insertion at segment boundary
      }
      
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

    if (firstTouched === -1) {
      // Pure insertion before or after or at boundary
      let insIdx = 0;
      curVis = 0;
      while (insIdx < baseSegs.length && curVis + baseSegs[insIdx].units.length <= aMid) {
        curVis += baseSegs[insIdx].units.length;
        insIdx++;
      }
      for (let i = 0; i < insIdx; i++) newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      const dcPos = (insIdx < baseSegs.length) ? baseSegs[insIdx].dcStart : baseSegs[baseSegs.length - 1].dcEnd;
      newSegments.push({ dcStart: dcPos, dcEnd: dcPos, units: midUnits });
      for (let i = insIdx; i < baseSegs.length; i++) newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
    } else {
      for (let i = 0; i < firstTouched; i++) {
        newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      }
      const dcStart = baseSegs[firstTouched].dcStart;
      const dcEnd = baseSegs[lastTouched].dcEnd;
      newSegments.push({
        dcStart,
        dcEnd,
        units: [...leftUnits, ...midUnits, ...rightUnits]
      });
      for (let i = lastTouched + 1; i < baseSegs.length; i++) {
        newSegments.push(JSON.parse(JSON.stringify(baseSegs[i])));
      }
    }

    this.provisionalOverlay.segments = newSegments;
    this.markedWindow = { relStart: relStart0, relEnd: relStart0 + text.length };
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      const sc = newSegments[0].dcStart;
      last[0] = sc + relStart0;
      last[1] = sc + relStart0 + text.length;
    }
    if (selection) this.selection = [...selection];
    else {
      const sc = newSegments[0].dcStart;
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
      // If pCut <= segStart while candidate remains active, it belongs to remaining overlay!
      if (segLen === 0) {
        const totalOverlayUnits = segs.flatMap(s => s.units).length;
        const hasRemainingUnitsAfter = pCut < totalOverlayUnits;
        if (segStart < pCut || (segStart === pCut && !hasRemainingUnitsAfter)) {
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
    const hasVisibleRemaining = finalRemSegments.some(s => s.units.length > 0);
    if (finalRemSegments.length === 0 || !hasVisibleRemaining) {
      this.provisionalOverlay = null;
      this.markedWindow = null;
      this.attributionEpoch = null;
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
          afterAttrs: cloneAttrs(curAttrs)
        });
        this.redoStack = [];
      }
      this.historySession = null;
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

    if (selection) this.selection = [...selection];
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

  _unmark(settled = true, selection = null) {
    if (!this.markedWindow) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    const finalExtent = [...this.markedRange];
    this.markedWindow = null;
    if (selection) this.selection = [...selection];
    else this.selection = [finalExtent[1], finalExtent[1]];

    if (settled) {
      // Flush all provisional overlay segments into Dc
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
      this.provisionalOverlay = null;
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
    if (token && activeFence && token !== activeFence.token) {
      return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
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
    this.provisionalOverlay = null;
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

  _flushOverlayToCommitted() {
    if (!this.provisionalOverlay) return;
    const segs = this.provisionalOverlay.segments;
    if (segs.length === 0) return;
    const sc = segs[0].dcStart;
    const ec = segs[segs.length - 1].dcEnd;
    const flatUnits = segs.flatMap(s => s.units);

    this.committedDoc = [
      ...this.committedDoc.slice(0, sc),
      ...flatUnits,
      ...this.committedDoc.slice(ec)
    ];
  }

  _commit(text = null, range = null, selection = null) {
    if (text !== null) {
      this._preedit(text, range, selection);
    }
    return this._unmark(true, selection);
  }

  _cancel() {
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

    // Cancel queued commands targeting ActiveComposition
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        cmdRec.status = 'Cancelled';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
      }
    }
    this.settlementObligation.pendingCommands = [];
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
    // Apply excision directly to visible coordinates by flushing Dc
    this._flushOverlayToCommitted();
    this.provisionalOverlay = null;
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

  _queueCommand(cmd, target) {
    const ticketId = this.nextTicketId();
    const record = {
      ticketId,
      cmd,
      target,
      status: 'Pending',
      sessionId: this.activeSessionId,
      epoch: this.epoch
    };
    this.settlementObligation.pendingCommands.push(record);
    return { status: 'Accepted', ticketId };
  }

  _drainPendingCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];

    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.cmd.type === 'Format') {
        const patch = cmdRec.cmd.patch;

        if (eStart === eEnd) {
          this.typingIntent = { ...this.typingIntent, ...patch };
          cmdRec.status = 'Applied';
          this.commandOutcomes.set(cmdRec.ticketId, { status: 'Applied', typingIntentUpdated: true });
          continue;
        }

        // Gap 4.1: If multiple disjoint islands were marked/retargeted, format the constituent
        // islands individually with outward grapheme closure, NOT their convex hull!
        const islands = (this.sessionIslands && this.sessionIslands.length > 1)
          ? this.sessionIslands
          : [[eStart, eEnd]];

        const beforeAttrs = this.docAttributes;
        let anyChanged = false;
        const appliedRanges = [];

        for (const [iStart, iEnd] of islands) {
          if (iStart === iEnd) continue;
          let formatStart = iStart;
          let formatEnd = iEnd;
          while (formatEnd < this.committedDoc.length && /[\u0300-\u036f]/.test(this.committedDoc[formatEnd].char)) {
            formatEnd++;
          }
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
            range: appliedRanges[0],
            ranges: appliedRanges
          });
          this.redoStack = [];
        }

        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, {
          status: 'Applied',
          range: appliedRanges[0],
          ranges: appliedRanges
        });
      }
    }
    this.sessionIslands = [];
    this.settlementObligation.pendingCommands = [];
  }
}
