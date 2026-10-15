// Candidate A Coordinator: v4 Live-Document Model (§4, §6, §7)
// Corrected against the frozen observable editing contracts:
// - Single authoritative live document buffer (docText, docAttributes)
// - Transient markedRange and immutable baselineUnits
// - Frozen committedUnits and committedExtent
// - Surplus tracking for target expansion
// - Outward grapheme closure and intent-based command formatting (Gap 4.1)
// - Monotonic settlement tokens, strict invalidation, and distinct cancellation (Gap 4.7)

import { reconcileCandidateAttributes } from './sources/run-04-reference-model.mjs';

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

export class CandidateACoordinator {
  constructor(initialText = "Hello", initialAttrs = null, initialSelection = [0, 0]) {
    this.epoch = 0;
    this.connectionState = 'Active';
    this.sequenceCounter = 0;
    this.receiptCache = new Map();
    this.ticketCounter = 0;
    this.sessionCounter = 0;
    this.settlementSeqCounter = 0;

    // Single Authoritative Live Document Buffer
    this.docText = initialText;
    this.docAttributes = initialAttrs
      ? cloneAttrs(initialAttrs)
      : new Array(initialText.length).fill(null).map(() => ({}));
    this.selection = [...initialSelection];
    this.documentRevision = 0;
    this.sessionRevision = 0;

    // Composition state
    this.compositionState = 'Idle'; // Idle | Composing | AwaitingSettlement
    this.activeSessionId = null;
    this.sessionEpoch = 0;
    this.attributionEpoch = 0;
    this.sessionOriginStart = 0;
    this.sessionOriginEnd = 0;
    this.markedRange = null; // [start, end]
    this.baselineUnits = null;
    this.seedAttributes = {};
    this.committedUnits = [];
    this.committedExtent = null;
    this.historicalCommittedUnits = [];
    this.historicalCommittedExtent = null;
    this.compositionBeforeSnapshot = null;

    // Tracked original absorbed spans and deletion obligations
    this.absorbedOriginalUnits = []; // units absorbed from document during expansion
    this.deletionObligations = []; // { atVisibleOffset, deletedUnits: [...] }

    // Settlement state
    this.activeSettlementToken = null;
    this.settlementEvidence = null;

    // History and queues
    this.undoStack = [];
    this.redoStack = [];
    this.pendingCommands = [];
    this.commandOutcomes = new Map();
    this.sessionIslands = [];
    this.typingIntent = {};
  }

  nextTicketId(prefix = 'ticket') {
    return `${prefix}-${++this.ticketCounter}`;
  }

  nextSettlementToken() {
    return `settle-tok-${++this.settlementSeqCounter}`;
  }

  applyInput(payload, seq = null) {
    if (seq !== null) {
      if (this.receiptCache.has(seq)) {
        return { status: 'Duplicate', cachedReceipt: this.receiptCache.get(seq) };
      }
    }

    const prevDocText = this.docText;
    const prevDocAttrs = cloneAttrs(this.docAttributes);
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

  _startComposition(range, selection = null, sessionId = null) {
    if (this.compositionState !== 'Idle') {
      return { status: 'Rejected', reason: 'CompositionAlreadyActive' };
    }
    const [u, v] = range;
    this.activeSessionId = sessionId || `session-${++this.sessionCounter}`;
    this.sessionEpoch = 1;
    this.attributionEpoch = 1;
    this.sessionOriginStart = u;
    this.sessionOriginEnd = v;
    this.markedRange = [u, v];

    const currentSlice = this.docText.slice(u, v);
    this.baselineUnits = currentSlice.split('').map((c, i) => ({
      char: c,
      attributes: { ...(this.docAttributes[u + i] || {}) }
    }));
    this.seedAttributes = (u < this.docAttributes.length) ? { ...this.docAttributes[u] } : {};

    this.compositionBeforeSnapshot = {
      text: this.docText,
      attrs: cloneAttrs(this.docAttributes),
      selection: [...this.selection],
      originStart: u,
      originEnd: v
    };

    this.compositionState = 'Composing';
    this.sessionIslands = [[u, v]];
    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    this.sessionRevision++;

    return {
      type: 'CompositionStarted',
      status: 'Accepted',
      sessionId: this.activeSessionId,
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
      this.compositionState = 'Composing';
      if (!this.markedRange && this.settlementEvidence) {
        this.markedRange = [...this.settlementEvidence.extent];
      }
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
    }

    if (range && (!this.markedRange || range[0] !== this.markedRange[0] || range[1] !== this.markedRange[1])) {
      this._retarget(range);
    }

    if (!this.markedRange) {
      const pos = this.selection[0];
      this.markedRange = [pos, pos];
    }

    this.activeSettlementToken = null;
    this.settlementEvidence = null;

    const [u, v] = this.markedRange;
    const oldCandidateText = this.docText.slice(u, v);

    // Track deletion obligations if candidate shrank using prefix/suffix diffing
    if (text.length < oldCandidateText.length) {
      let p = 0;
      while (p < text.length && p < oldCandidateText.length && text[p] === oldCandidateText[p]) p++;
      let s = 0;
      while (s < (text.length - p) && s < (oldCandidateText.length - p) && text[text.length - 1 - s] === oldCandidateText[oldCandidateText.length - 1 - s]) s++;
      const oldMid = oldCandidateText.slice(p, oldCandidateText.length - s);
      const delLen = oldMid.length - (text.length - p - s);
      if (delLen > 0) {
        const deletedChars = oldMid.slice(text.length - p - s);
        const delStart = u + p + (text.length - p - s);
        const deletedAttrs = this.docAttributes.slice(delStart, delStart + delLen);
        this.deletionObligations.push({
          atVisibleOffset: delStart,
          deletedUnits: deletedChars.split('').map((c, i) => ({ char: c, attributes: { ...(deletedAttrs[i] || {}) } }))
        });
      }
    }

    // Reconcile candidate attributes against baselineUnits
    const reconciledUnits = reconcileCandidateAttributes(this.baselineUnits, text, this.seedAttributes);
    const newCandidateText = reconciledUnits.map(u => u.char).join('');
    const newCandidateAttrs = reconciledUnits.map(u => ({ ...u.attributes }));

    const newDocText = this.docText.slice(0, u) + newCandidateText + this.docText.slice(v);
    const newDocAttrs = [
      ...this.docAttributes.slice(0, u),
      ...newCandidateAttrs,
      ...this.docAttributes.slice(v)
    ];

    this.docText = newDocText;
    this.docAttributes = newDocAttrs;
    this.markedRange = [u, u + newCandidateText.length];

    if (selection) this.selection = [...selection];
    else this.selection = [u + newCandidateText.length, u + newCandidateText.length];
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

    if (!this.markedRange) {
      return this._startComposition(range, selection);
    }

    const [oldStart, oldEnd] = this.markedRange;
    // Absorb newly encompassed text
    if (v > oldEnd) {
      const absorbedSlice = this.docText.slice(oldEnd, v);
      const absorbedAttrs = this.docAttributes.slice(oldEnd, v);
      for (let i = 0; i < absorbedSlice.length; i++) {
        this.absorbedOriginalUnits.push({
          docOffset: oldEnd + i,
          char: absorbedSlice[i],
          attributes: { ...absorbedAttrs[i] }
        });
      }
    }

    this.markedRange = [u, v];
    const newBaselineText = this.docText.slice(u, v);
    this.baselineUnits = newBaselineText.split('').map((c, i) => ({
      char: c,
      attributes: { ...(this.docAttributes[u + i] || {}) }
    }));
    this.attributionEpoch++;

    if (!this.sessionIslands) this.sessionIslands = [];
    const lastIsland = this.sessionIslands[this.sessionIslands.length - 1];
    if (!lastIsland || lastIsland[0] !== u || lastIsland[1] !== v) {
      this.sessionIslands.push([u, v]);
    }

    if (selection) this.selection = [...selection];
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
    if (!this.markedRange) {
      return { status: 'Rejected', reason: 'NoActiveMarkedRange' };
    }
    const [start, end] = this.markedRange;
    const currentLen = end - start;
    if (k > currentLen) {
      return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate' };
    }

    this.activeSettlementToken = null;
    this.settlementEvidence = null;

    const cutPos = start + k;
    const newlyCommittedPortion = [];
    for (let i = start; i < cutPos; i++) {
      newlyCommittedPortion.push({
        char: this.docText[i],
        attributes: { ...(this.docAttributes[i] || {}) }
      });
    }
    this.committedUnits.push(...newlyCommittedPortion);

    if (!this.committedExtent) {
      this.committedExtent = [start, cutPos];
    } else {
      this.committedExtent = [this.committedExtent[0], cutPos];
    }
    this.historicalCommittedUnits = this.committedUnits.map(u => ({ ...u }));
    this.historicalCommittedExtent = [...this.committedExtent];

    // Filter deletion obligations:
    // Gap 4.4: Deletion belongs to committed iff cutPos > atVisibleOffset
    this.deletionObligations = this.deletionObligations.filter(d => cutPos <= d.atVisibleOffset);

    // Baseline resets to remaining candidate
    const remainingText = this.docText.slice(cutPos, end);
    this.baselineUnits = remainingText.split('').map((c, i) => ({
      char: c,
      attributes: { ...(this.docAttributes[cutPos + i] || {}) }
    }));
    this.attributionEpoch++;
    this.markedRange = [cutPos, end];

    if (selection) this.selection = [...selection];
    this.sessionRevision++;

    return {
      type: 'PartialCommitted',
      status: 'Accepted',
      frozenCount: this.committedUnits.length,
      markedRange: [...this.markedRange],
      selection: [...this.selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }

  _unmark(settled = true, selection = null) {
    if (!this.markedRange) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    const [start, end] = this.markedRange;
    const finalExtent = this.committedExtent
      ? [Math.min(this.committedExtent[0], start), Math.max(this.committedExtent[1], end)]
      : [start, end];

    this.markedRange = null;
    if (selection) this.selection = [...selection];

    if (settled) {
      this.compositionState = 'Idle';
      const snap = this.compositionBeforeSnapshot;
      if (snap && (this.docText !== snap.text || !areAttrsEqual(this.docAttributes, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          extent: finalExtent
        });
        this.redoStack = [];
      }

      this._drainPendingCommands(finalExtent);
      this._resetSessionState();
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
      this.activeSettlementToken = { token: tok, sessionId: this.activeSessionId };
      this.settlementEvidence = { token: tok, sessionId: this.activeSessionId, extent: finalExtent };
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
    if (this.markedRange !== null) {
      return { status: 'Rejected', reason: 'CandidateStillActive' };
    }
    if (epoch !== null && epoch !== this.epoch) {
      return { status: 'Rejected', reason: 'StaleEpoch' };
    }
    if (sessionId && this.activeSessionId && sessionId !== this.activeSessionId) {
      return { status: 'Rejected', reason: 'SessionMismatch' };
    }
    if (token && this.activeSettlementToken && token !== this.activeSettlementToken.token) {
      return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
    }
    if (!this.settlementEvidence) {
      return { status: 'Rejected', reason: 'UnknownTargetExtent' };
    }

    const finalExtent = [...this.settlementEvidence.extent];
    this.compositionState = 'Idle';

    const snap = this.compositionBeforeSnapshot;
    if (snap && (this.docText !== snap.text || !areAttrsEqual(this.docAttributes, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: this.docText,
        afterAttrs: cloneAttrs(this.docAttributes),
        extent: finalExtent
      });
      this.redoStack = [];
    }

    this._drainPendingCommands(finalExtent);
    this._resetSessionState();
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
    let restoredText, restoredAttrs;
    const snap = this.compositionBeforeSnapshot;

    if (this.committedUnits.length === 0) {
      // No partial commit: restore snapshot verbatim
      if (snap) {
        restoredText = snap.text;
        restoredAttrs = cloneAttrs(snap.attrs);
      } else {
        restoredText = this.docText;
        restoredAttrs = cloneAttrs(this.docAttributes);
      }
    } else {
      // Partial commit occurred: committed units survive!
      const originStart = snap ? snap.originStart : this.sessionOriginStart;
      const originEnd = snap ? snap.originEnd : this.sessionOriginEnd;
      const committedText = this.committedUnits.map(u => u.char).join('');
      const committedAttrs = this.committedUnits.map(u => ({ ...u.attributes }));

      // Also restore any absorbed original units that were never overwritten or committed!
      let absorbedSuffixText = "";
      let absorbedSuffixAttrs = [];
      for (const abs of this.absorbedOriginalUnits) {
        absorbedSuffixText += abs.char;
        absorbedSuffixAttrs.push({ ...abs.attributes });
      }

      // Also restore any remaining deletion obligations (Gap 4.4)
      let restoredDeletionsText = "";
      let restoredDeletionsAttrs = [];
      for (const del of this.deletionObligations) {
        for (const u of del.deletedUnits) {
          restoredDeletionsText += u.char;
          restoredDeletionsAttrs.push({ ...u.attributes });
        }
      }

      if (snap) {
        const leftText = snap.text.slice(0, originStart);
        const leftAttrs = snap.attrs.slice(0, originStart);
        const rightText = snap.text.slice(originEnd);
        const rightAttrs = snap.attrs.slice(originEnd);

        // Stitch together with absorbed text
        if (absorbedSuffixText.length > 0 && rightText.startsWith(absorbedSuffixText)) {
          restoredText = leftText + committedText + rightText;
          restoredAttrs = [...cloneAttrs(leftAttrs), ...committedAttrs, ...cloneAttrs(rightAttrs)];
        } else {
          restoredText = leftText + committedText + absorbedSuffixText + restoredDeletionsText + rightText;
          restoredAttrs = [
            ...cloneAttrs(leftAttrs),
            ...committedAttrs,
            ...absorbedSuffixAttrs,
            ...restoredDeletionsAttrs,
            ...cloneAttrs(rightAttrs)
          ];
        }
      } else {
        restoredText = this.docText;
        restoredAttrs = cloneAttrs(this.docAttributes);
      }
    }

    this.docText = restoredText;
    this.docAttributes = restoredAttrs;

    if (snap && (this.docText !== snap.text || !areAttrsEqual(this.docAttributes, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: this.docText,
        afterAttrs: cloneAttrs(this.docAttributes)
      });
      this.redoStack = [];
    }

    for (const cmdRec of this.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'CompositionTarget') {
        cmdRec.status = 'Cancelled';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
      }
    }
    this.pendingCommands = [];
    this._resetSessionState();
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
    const newText = this.docText.slice(0, rStart) + replacementText + this.docText.slice(rEnd);
    const newAttrs = [
      ...this.docAttributes.slice(0, rStart),
      ...new Array(replacementText.length).fill(null).map(() => ({})),
      ...this.docAttributes.slice(rEnd)
    ];
    this.docText = newText;
    this.docAttributes = newAttrs;
    this._resetSessionState();
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
    this.pendingCommands.push({
      ticketId,
      cmd,
      target,
      status: 'Pending',
      sessionId: this.activeSessionId,
      epoch: this.epoch
    });
    return { status: 'Accepted', ticketId };
  }

  _drainPendingCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];

    for (const cmdRec of this.pendingCommands) {
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

        const beforeAttrs = cloneAttrs(this.docAttributes);
        let anyChanged = false;
        const appliedRanges = [];

        for (const [iStart, iEnd] of islands) {
          if (iStart === iEnd) continue;
          let formatStart = iStart;
          let formatEnd = iEnd;
          while (formatEnd < this.docText.length && /[\u0300-\u036f]/.test(this.docText[formatEnd])) {
            formatEnd++;
          }
          appliedRanges.push([formatStart, formatEnd]);

          for (let i = formatStart; i < formatEnd; i++) {
            if (i < this.docAttributes.length) {
              for (const k of Object.keys(patch)) {
                if (this.docAttributes[i][k] !== patch[k]) {
                  this.docAttributes[i][k] = patch[k];
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
            afterAttrs: cloneAttrs(this.docAttributes),
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
    this.pendingCommands = [];
  }

  _resetSessionState() {
    this.compositionState = 'Idle';
    this.activeSessionId = null;
    this.markedRange = null;
    this.committedUnits = [];
    this.committedExtent = null;
    this.baselineUnits = null;
    this.absorbedOriginalUnits = [];
    this.deletionObligations = [];
    this.compositionBeforeSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.sessionIslands = [];
  }
}
