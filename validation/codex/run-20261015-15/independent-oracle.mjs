// Independent Observational Oracle (§5)
// Tracks visible attributed text, committed state, rollback obligations,
// marked range, selection, command ledger, and history independent of candidate representations.
// Repaired in run-20261014-07 to resolve contract violations A through F and validation gaps §4.1–§4.5.
//
// Section 4.2 Independent Unicode Expectations:
// Does NOT import Candidate B's expandToGraphemeBoundaries helper.
// Implements an independent boundary calculation qualified against Unicode Standard Annex #29
// (UAX #29, Unicode 16.0 standard via Node.js v24 V8 Internationalization).

import assert from 'node:assert/strict';

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

// Independent Oracle Grapheme Boundary Analysis (UAX #29 conforming)
export function oracleExpandToGraphemeBoundaries(text, [start, end]) {
  if (text.length === 0) return [0, 0];
  const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
  let expandedStart = start;
  let expandedEnd = end;

  for (const seg of segmenter.segment(text)) {
    const s0 = seg.index;
    const s1 = s0 + seg.segment.length;

    if (start === end) {
      if (start > s0 && start < s1) {
        expandedStart = Math.min(expandedStart, s0);
        expandedEnd = Math.max(expandedEnd, s1);
      }
    } else {
      if (start < s1 && end > s0) {
        expandedStart = Math.min(expandedStart, s0);
        expandedEnd = Math.max(expandedEnd, s1);
      }
    }
  }
  return [expandedStart, expandedEnd];
}

export class IndependentOracle {
  constructor(initialText = "Hello", initialAttrs = null, initialSel = [0, 0]) {
    this.tokenCounter = 0;
    this.ticketCounter = 0;
    this.settlementSeqCounter = 0;
    
    // Tape of CommittedToken | ProvisionalRun | DeletionMarker
    this.tape = [];
    for (let i = 0; i < initialText.length; i++) {
      this.tape.push({
        type: 'CommittedToken',
        id: `init:${i}`,
        char: initialText[i],
        attrs: initialAttrs ? { ...initialAttrs[i] } : {},
        originSessionId: null
      });
    }
    
    this.selection = [...initialSel];
    this.markedSpan = null;
    
    this.activeSessionId = null;
    this.activeEpoch = 0;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.activeSeed = {};
    
    this.docRev = 0;
    this.sessRev = 0;
    this.undoStack = [];
    this.redoStack = [];
    
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    this.commandLedger = new Map();
    this.commandOutcomes = new Map();
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.typingIntent = {};
    this.connectionState = 'Active';
    this.receiptCache = new Map();
    this.epochHighWaterMarks = new Map();
    this.consumedSettlementEvidence = new Set();
    this._reentrantMutationQueue = [];
    this._inTransaction = false;
  }

  get documentRevision() {
    return this.docRev;
  }

  set documentRevision(val) {
    this.docRev = val;
  }

  get sessionRevision() {
    return this.sessRev;
  }

  set sessionRevision(val) {
    this.sessRev = val;
  }

  clone() {
    const o = new IndependentOracle("", null, [0, 0]);
    o.tokenCounter = this.tokenCounter;
    o.ticketCounter = this.ticketCounter;
    o.settlementSeqCounter = this.settlementSeqCounter;
    o.tape = JSON.parse(JSON.stringify(this.tape));
    o.selection = [...this.selection];
    o.markedSpan = this.markedSpan ? [...this.markedSpan] : null;
    o.sessionIslands = this.sessionIslands ? JSON.parse(JSON.stringify(this.sessionIslands)) : [];
    o.sessionCommittedRanges = this.sessionCommittedRanges ? JSON.parse(JSON.stringify(this.sessionCommittedRanges)) : [];
    o.activeSessionId = this.activeSessionId;
    o.activeEpoch = this.activeEpoch;
    o.sessionSnapshot = this.sessionSnapshot ? JSON.parse(JSON.stringify(this.sessionSnapshot)) : null;
    o.epochSnapshot = this.epochSnapshot ? JSON.parse(JSON.stringify(this.epochSnapshot)) : null;
    o.activeSeed = { ...this.activeSeed };
    o.docRev = this.docRev;
    o.sessRev = this.sessRev;
    o.undoStack = JSON.parse(JSON.stringify(this.undoStack));
    o.redoStack = JSON.parse(JSON.stringify(this.redoStack));
    o.activeSettlementToken = this.activeSettlementToken ? { ...this.activeSettlementToken } : null;
    o.activeSettlementFence = this.activeSettlementFence ? { ...this.activeSettlementFence } : null;
    o.settlementEvidence = this.settlementEvidence ? { ...this.settlementEvidence } : null;
    o.commandLedger = new Map(JSON.parse(JSON.stringify(Array.from(this.commandLedger.entries()))));
    o.commandOutcomes = new Map(JSON.parse(JSON.stringify(Array.from(this.commandOutcomes.entries()))));
    o.typingIntent = { ...this.typingIntent };
    o.connectionState = this.connectionState;
    o.receiptCache = new Map(JSON.parse(JSON.stringify(Array.from(this.receiptCache.entries()))));
    o.epochHighWaterMarks = new Map(this.epochHighWaterMarks);
    o.consumedSettlementEvidence = new Set(this.consumedSettlementEvidence);
    o._reentrantMutationQueue = this._reentrantMutationQueue.map(item => ({ ...item }));
    o._inTransaction = this._inTransaction;
    return o;
  }

  _restoreFromClone(o) {
    this.tokenCounter = o.tokenCounter;
    this.ticketCounter = o.ticketCounter;
    this.settlementSeqCounter = o.settlementSeqCounter;
    this.tape = JSON.parse(JSON.stringify(o.tape));
    this.selection = [...o.selection];
    this.markedSpan = o.markedSpan ? [...o.markedSpan] : null;
    this.sessionIslands = o.sessionIslands ? JSON.parse(JSON.stringify(o.sessionIslands)) : [];
    this.sessionCommittedRanges = o.sessionCommittedRanges ? JSON.parse(JSON.stringify(o.sessionCommittedRanges)) : [];
    this.activeSessionId = o.activeSessionId;
    this.activeEpoch = o.activeEpoch;
    this.sessionSnapshot = o.sessionSnapshot ? JSON.parse(JSON.stringify(o.sessionSnapshot)) : null;
    this.epochSnapshot = o.epochSnapshot ? JSON.parse(JSON.stringify(o.epochSnapshot)) : null;
    this.activeSeed = { ...o.activeSeed };
    this.docRev = o.docRev;
    this.sessRev = o.sessRev;
    this.undoStack = JSON.parse(JSON.stringify(o.undoStack));
    this.redoStack = JSON.parse(JSON.stringify(o.redoStack));
    this.activeSettlementToken = o.activeSettlementToken ? { ...o.activeSettlementToken } : null;
    this.activeSettlementFence = o.activeSettlementFence ? { ...o.activeSettlementFence } : null;
    this.settlementEvidence = o.settlementEvidence ? { ...o.settlementEvidence } : null;
    this.commandLedger = new Map(JSON.parse(JSON.stringify(Array.from(o.commandLedger.entries()))));
    this.commandOutcomes = new Map(JSON.parse(JSON.stringify(Array.from(o.commandOutcomes.entries()))));
    this.typingIntent = { ...o.typingIntent };
    this.connectionState = o.connectionState;
    this.receiptCache = new Map(JSON.parse(JSON.stringify(Array.from(o.receiptCache.entries()))));
    this.epochHighWaterMarks = new Map(o.epochHighWaterMarks);
    this.consumedSettlementEvidence = o.consumedSettlementEvidence ? new Set(o.consumedSettlementEvidence) : new Set();
    // Preserve pending reentrant mutations enqueued during transaction so they execute against restored baseline (§4.1, §4.3)
    if (!this._reentrantMutationQueue || this._reentrantMutationQueue.length === 0) {
      this._reentrantMutationQueue = o._reentrantMutationQueue ? o._reentrantMutationQueue.map(item => ({ ...item })) : [];
    }
    this._inTransaction = o._inTransaction;
  }

  // Centralized Transaction Runner (§4.3, Defect 2)
  _runInTransaction(actionName, fn, injectFailureAt = null) {
    if (this.connectionState === 'Closed' && actionName !== 'dispose') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      this._reentrantMutationQueue.push({
        ticketId,
        actionName,
        fn
      });
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    this._inTransaction = true;
    const preTxSnapshot = this.clone();
    const prevDocText = this.getVisibleText();
    const prevDocAttrs = cloneAttrs(this.getVisibleAttrs());

    try {
      const result = fn();

      if (injectFailureAt === 'beforeReceiptPublication') {
        throw new Error('InjectedFailure:beforeReceiptPublication');
      }

      if (result && result.status === 'Rejected') {
        this._restoreFromClone(preTxSnapshot);
        if (result.ticketId) {
          this.commandOutcomes.set(result.ticketId, { status: 'Rejected', reason: result.reason });
        }
        return result;
      }

      // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
      const newDocText = this.getVisibleText();
      const newDocAttrs = this.getVisibleAttrs();
      if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
        this.docRev++;
      }

      // Branch invalidation: editing operations clear redo stack at the operation level
      // when the edit occurs, preserving redo entries from subsequent queued Undos.

      // Synchronize receipts with actual published document revision and selection
      if (result && result.status !== 'Rejected') {
        if (result.documentRevision !== undefined) {
          result.documentRevision = this.docRev;
        }
        if (result.sessionRevision !== undefined) {
          result.sessionRevision = this.sessRev;
        }
        if (result.selection !== undefined) {
          result.selection = [...this.selection];
        }
      }

      return result;
    } catch (e) {
      this._restoreFromClone(preTxSnapshot);
      return { status: 'Rejected', reason: e.message || 'TransactionFailure' };
    } finally {
      this._inTransaction = false;
      while (this._reentrantMutationQueue.length > 0) {
        const nextMut = this._reentrantMutationQueue.shift();
        const mRes = this._runInTransaction(nextMut.actionName, nextMut.fn);
        this.commandOutcomes.set(nextMut.ticketId, mRes);
      }
    }
  }

  get docText() {
    return this.getVisibleText();
  }

  get epoch() {
    return this.activeEpoch;
  }

  get docAttributes() {
    return this.getVisibleAttrs();
  }

  get compositionState() {
    if (this.connectionState === 'Closed') return 'Closed';
    if (this.activeSettlementFence !== null) return 'AwaitingSettlement';
    if (this.activeSessionId !== null) return 'Composing';
    return 'Idle';
  }

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

  // Computes the exact bounding extent of all content touched by this composition session
  _computeSessionExtent() {
    const ranges = [];
    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const r of this.sessionCommittedRanges) {
        if (r[0] < r[1]) ranges.push([...r]);
      }
    }
    if (this.markedSpan && this.markedSpan[0] < this.markedSpan[1]) {
      ranges.push([...this.markedSpan]);
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

  // Transaction-Owned Unified Target Resolution (Repair D)
  _resolveTargetDescriptor(payload) {
    const curLen = this.getVisibleText().length;
    let range;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { valid: false };
      }
      range = [Math.min(payload.range[0], payload.range[1]), Math.max(payload.range[0], payload.range[1])];
    } else {
      if (this.activeSessionId && this.markedSpan) {
        range = [...this.markedSpan];
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

  _computeProposedPostLength(payload) {
    return this._resolveTargetDescriptor(payload);
  }

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

  applyInput(payload, seq = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }

    // Connection epoch admission (Finding F & Defect 3):
    // Epoch validation MUST strictly precede duplicate lookup!
    const isNativeCallback = [
      'startComposition', 'preedit', 'commit', 'partialCommit',
      'unmark', 'completeSettlement', 'cancel', 'cancelUncommitted',
      'applicationAbort', 'targetExpansion', 'retarget', 'nativeRemoval', 'onFocusLost'
    ].includes(payload.type);

    if (payload.epoch !== undefined && payload.epoch !== null) {
      if (typeof payload.epoch !== 'number' || !Number.isInteger(payload.epoch) || payload.epoch < 0) {
        return { status: 'Rejected', reason: 'MalformedConnectionEpoch' };
      }
      if (payload.epoch < this.activeEpoch) {
        return { status: 'Rejected', reason: 'StaleEpoch' };
      }
      if (payload.epoch > this.activeEpoch) {
        return { status: 'Rejected', reason: 'FutureEpoch' };
      }
    } else if (payload.isNativeCallback) {
      return { status: 'Rejected', reason: 'MissingConnectionEpoch' };
    }

    const callbackEpoch = (payload.epoch !== undefined && payload.epoch !== null) ? payload.epoch : this.activeEpoch;
    if (seq !== null) {
      if (typeof seq !== 'number' || !Number.isInteger(seq) || seq <= 0) {
        return { status: 'Rejected', reason: 'MalformedSequenceStamp' };
      }
      const stampKey = `${callbackEpoch}:${seq}`;
      if (this.receiptCache.has(stampKey)) {
        return { status: 'Duplicate', cachedReceipt: this.receiptCache.get(stampKey) };
      }
      const hwm = this.epochHighWaterMarks.get(callbackEpoch) || 0;
      if (seq <= hwm && !this.receiptCache.has(stampKey)) {
        return { status: 'DuplicateReceiptExpired' };
      }
    }

    if (payload.injectFailure) {
      return { status: 'Rejected', reason: 'InjectedFailure' };
    }

    const prop = this._resolveTargetDescriptor(payload);
    if (!prop.valid) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    // Selection validation against post-edit length (Finding D)
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

    const actionResult = this._runInTransaction(payload.type, () => {
      let result;
      switch (payload.type) {
        case 'startComposition':
          result = this.startComposition(prop.range, payload.selection, payload.sessionId);
          break;
        case 'preedit':
          result = this.preedit(payload.text, prop.range, payload.selection);
          break;
        case 'partialCommit':
          result = this.partialCommit(payload.length !== undefined ? payload.length : payload.committedLength, payload.selection);
          break;
        case 'targetExpansion':
        case 'retarget':
          result = this.retarget(prop.range, payload.selection);
          break;
        case 'unmark':
          result = this.unmark(payload.settled !== false, payload.selection);
          break;
        case 'completeSettlement':
          result = this.completeSettlement(payload.token, payload.sessionId, payload.epoch);
          break;
        case 'commit':
          if (payload.text !== undefined) {
            const preRes = this.preedit(payload.text, prop.range, payload.selection);
            if (preRes.status === 'Rejected') return preRes;
          }
          result = this.unmark(true, payload.selection);
          break;
        case 'cancel':
        case 'cancelUncommitted':
        case 'applicationAbort':
          result = this.cancel();
          break;
        case 'nativeRemoval':
          result = this._internalNativeRemoval(prop.range, payload.replacementText, payload.selection);
          break;
        case 'dispatchCommand':
        case 'dispatchAppCommand':
          result = this._internalDispatchCommand(payload.cmd, payload.target);
          break;
        case 'queueCommand':
          if (this.compositionState === 'Idle') {
            result = this._internalDispatchCommand(payload.cmd, payload.target);
          } else {
            result = { status: 'Accepted', ticketId: this.queueCommand(payload.cmd, payload.target) };
          }
          break;
        case 'onFocusLost':
          result = this._internalOnFocusLost(payload.strategy);
          break;
        case 'openNewConnection':
          result = this._internalOpenNewConnection();
          break;
        case 'dispose':
          result = this._internalDispose();
          break;
        case 'undo':
          result = this._internalUndo(payload.syncBoundary, payload.settlementEvidence);
          break;
        case 'redo':
          result = this._internalRedo();
          break;
        case 'setSelection':
          if (payload.selection && this._validateSelection(payload.selection, this.getVisibleText().length)) {
            this.selection = [...payload.selection];
            result = { type: 'SelectionChanged', status: 'Accepted', selection: [...this.selection] };
          } else {
            result = { status: 'Rejected', reason: 'InvalidSelection' };
          }
          break;
        default:
          result = { status: 'Rejected', reason: `Unknown:${payload.type}` };
      }
      return result;
    }, payload.injectFailureAt);

    if (seq !== null && actionResult && actionResult.status !== 'Rejected') {
      const stampKey = `${callbackEpoch}:${seq}`;
      this.receiptCache.set(stampKey, actionResult);
      const curHwm = this.epochHighWaterMarks.get(callbackEpoch) || 0;
      if (seq > curHwm) {
        this.epochHighWaterMarks.set(callbackEpoch, seq);
      }
    }
    return actionResult;
  }

  getVisibleText() {
    return this.getVisibleUnits().map(u => u.char).join('');
  }

  getVisibleAttrs() {
    return this.getVisibleUnits().map(u => ({ ...u.attrs }));
  }

  getVisibleUnits() {
    const units = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        units.push({ id: entry.id, char: entry.char, attrs: { ...entry.attrs }, originSessionId: entry.originSessionId });
      } else if (entry.type === 'ProvisionalRun') {
        for (const u of entry.units) {
          units.push({ id: u.id, char: u.char, attrs: { ...u.attrs }, originSessionId: u.originSessionId });
        }
      }
    }
    return units;
  }

  getCommittedText() {
    return this.getCommittedUnits().map(u => u.char).join('');
  }

  getCommittedAttrs() {
    return this.getCommittedUnits().map(u => ({ ...u.attrs }));
  }

  getCommittedUnits() {
    const units = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        units.push({ id: entry.id, char: entry.char, attrs: { ...entry.attrs }, originSessionId: entry.originSessionId });
      } else if (entry.type === 'ProvisionalRun' || entry.type === 'DeletionMarker') {
        for (const r of entry.replacedCommitted) {
          units.push({ id: r.id, char: r.char, attrs: { ...r.attrs }, originSessionId: r.originSessionId });
        }
      }
    }
    return units;
  }

  nextTicketId(prefix = 'ticket') {
    return `${prefix}-${++this.ticketCounter}`;
  }

  nextSettlementToken() {
    return `settle-tok-${++this.settlementSeqCounter}`;
  }

  startComposition(range, selection = null, sessionId = null) {
    if (this.activeSessionId) {
      return { status: 'Rejected', reason: 'CompositionAlreadyActive' };
    }
    const [u, v] = range;
    this.sessionSnapshot = {
      text: this.getVisibleText(),
      attrs: this.getVisibleAttrs(),
      tape: JSON.parse(JSON.stringify(this.tape)),
      selection: [...this.selection],
      originRange: [u, v]
    };

    this.activeSessionId = sessionId || `session-${Date.now()}`;
    this.sessionIslands = [[u, v]];
    this.sessionCommittedRanges = [];
    this.markedSpan = [u, v];
    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    
    const visibleUnits = this.getVisibleUnits();
    const B_epoch = visibleUnits.slice(u, v);
    this.activeSeed = (u < visibleUnits.length) ? { ...visibleUnits[u].attrs } : {};
    
    if (v > u) {
      const beforeTokens = this.tape.slice(0, u);
      const composingTokens = this.tape.slice(u, v);
      const afterTokens = this.tape.slice(v);
      
      this.tape = [
        ...beforeTokens,
        {
          type: 'ProvisionalRun',
          id: `run:${u}`,
          units: composingTokens.flatMap(e => (e.type === 'CommittedToken' ? [e] : (e.type === 'ProvisionalRun' ? e.units : []))),
          replacedCommitted: composingTokens.flatMap(e => (e.type === 'CommittedToken' ? [e] : (e.type === 'ProvisionalRun' ? e.replacedCommitted : []))),
          originSessionId: this.activeSessionId
        },
        ...afterTokens
      ];
    }

    this.epochSnapshot = {
      tape: JSON.parse(JSON.stringify(this.tape)),
      markedSpan: [u, v],
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      seed: { ...this.activeSeed }
    };
    
    this.sessRev++;
    return { status: 'Accepted', markedSpan: [u, v] };
  }

  preedit(text, range = null, selection = null) {
    if (!this.activeSessionId) {
      let compRange;
      if (range) {
        compRange = range;
      } else {
        const sel = this.selection || [0, 0];
        const selStart = Math.min(sel[0], sel[1]);
        const selEnd = Math.max(sel[0], sel[1]);
        compRange = [selStart, selEnd];
      }
      const startRes = this.startComposition(compRange, selection);
      if (startRes.status === 'Rejected') return startRes;
    }
    
    if (range && (!this.markedSpan || range[0] !== this.markedSpan[0] || range[1] !== this.markedSpan[1])) {
      const retargetRes = this.retarget(range, selection);
      if (retargetRes.status === 'Rejected') return retargetRes;
    }
    
    if (!this.markedSpan) {
      const targetSpan = [this.selection[0], this.selection[0]];
      const retargetRes = this.retarget(targetSpan, selection);
      if (retargetRes.status === 'Rejected') return retargetRes;
    }

    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    
    const [baseStart, baseEnd] = this.epochSnapshot.markedSpan;
    const B = this.epochSnapshot.B_epoch;
    let p = 0;
    while (p < text.length && p < B.length && text[p] === B[p].char) {
      p++;
    }
    let s = 0;
    while (s < (text.length - p) && s < (B.length - p) && text[text.length - 1 - s] === B[B.length - 1 - s].char) {
      s++;
    }
    
    this.tape = JSON.parse(JSON.stringify(this.epochSnapshot.tape));
    
    const replaceStart = baseStart + p;
    const replaceEnd = baseEnd - s;
    
    const midText = text.slice(p, text.length - s);
    const midUnits = [];
    for (let j = 0; j < midText.length; j++) {
      midUnits.push({
        id: `prov:${this.activeSessionId}:m${j}`,
        char: midText[j],
        attrs: { ...this.activeSeed },
        originSessionId: this.activeSessionId
      });
    }

    const finalTape = [];
    let cur = 0;
    const replacedTokens = [];

    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const isStrictlyBefore = (len > 0) ? (eEnd <= replaceStart) : (entry.atVisibleOffset < replaceStart);
      if (isStrictlyBefore) {
        finalTape.push(entry);
      } else if (eStart < replaceStart && entry.type === 'ProvisionalRun') {
        const leftLen = replaceStart - eStart;
        finalTape.push({
          type: 'ProvisionalRun',
          id: entry.id + ':l',
          units: entry.units.slice(0, leftLen),
          replacedCommitted: entry.replacedCommitted ? entry.replacedCommitted.slice(0, leftLen) : [],
          originSessionId: entry.originSessionId
        });
      }
    }

    cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const overlaps = (len > 0)
        ? (Math.max(eStart, replaceStart) < Math.min(eEnd, replaceEnd))
        : (replaceStart <= eStart && eStart <= replaceEnd);

      if (overlaps) {
        if (entry.type === 'CommittedToken') {
          replacedTokens.push({ ...entry });
        } else if (entry.type === 'ProvisionalRun') {
          if (eStart >= replaceStart && eEnd <= replaceEnd) {
            if (entry.replacedCommitted) {
              replacedTokens.push(...entry.replacedCommitted);
            }
          } else {
            const oStart = Math.max(0, replaceStart - eStart);
            const oEnd = Math.min(len, replaceEnd - eStart);
            if (entry.replacedCommitted) {
              replacedTokens.push(...entry.replacedCommitted.slice(oStart, oEnd));
            }
          }
        } else if (entry.type === 'DeletionMarker') {
          if (eStart >= replaceStart) {
            replacedTokens.push(...entry.replacedCommitted);
          }
        }
      }
    }

    if (midUnits.length > 0) {
      finalTape.push({
        type: 'ProvisionalRun',
        id: `run:${replaceStart}`,
        units: midUnits,
        replacedCommitted: replacedTokens,
        originSessionId: this.activeSessionId
      });
    } else if (replaceEnd > replaceStart || replacedTokens.length > 0) {
      finalTape.push({
        type: 'DeletionMarker',
        id: `del:${replaceStart}`,
        atVisibleOffset: replaceStart,
        replacedCommitted: replacedTokens,
        originSessionId: this.activeSessionId
      });
    }

    cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const isStrictlyAfter = (len > 0) ? (eStart >= replaceEnd) : (entry.atVisibleOffset > replaceEnd);
      if (isStrictlyAfter) {
        finalTape.push(entry);
      } else if (eEnd > replaceEnd && entry.type === 'ProvisionalRun') {
        const rightOffset = replaceEnd - eStart;
        finalTape.push({
          type: 'ProvisionalRun',
          id: entry.id + ':r',
          units: entry.units.slice(rightOffset),
          replacedCommitted: entry.replacedCommitted ? entry.replacedCommitted.slice(rightOffset) : [],
          originSessionId: entry.originSessionId
        });
      }
    }

    this.tape = finalTape;
    this.markedSpan = [baseStart, baseStart + text.length];

    const delta = text.length - (replaceEnd - replaceStart);
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      for (let i = 0; i < this.sessionIslands.length - 1; i++) {
        const isl = this.sessionIslands[i];
        if (isl[0] >= replaceEnd) {
          isl[0] += delta;
          isl[1] += delta;
        } else if (isl[1] > replaceStart) {
          isl[1] = Math.max(isl[0], isl[1] + delta);
        }
      }
      last[0] = baseStart;
      last[1] = baseStart + text.length;
    }

    if (this.sessionCommittedRanges && this.sessionCommittedRanges.length > 0) {
      for (const r of this.sessionCommittedRanges) {
        if (r[0] >= replaceEnd) {
          r[0] += delta;
          r[1] += delta;
        } else if (r[1] > replaceStart) {
          r[1] = Math.max(r[0], r[1] + delta);
        }
      }
    }

    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.target && rec.target.type === 'TrackedRange') {
        const r = rec.target.range;
        if (r[0] >= replaceEnd) {
          r[0] += delta;
          r[1] += delta;
        } else if (r[1] > replaceStart) {
          r[1] = Math.max(r[0], r[1] + delta);
        }
      }
    }

    if (selection) this.selection = [...selection];
    else this.selection = [baseStart + text.length, baseStart + text.length];
    this.sessRev++;

    return {
      status: 'Accepted',
      markedSpan: [...this.markedSpan],
      selection: [...this.selection]
    };
  }

  retarget(range, selection = null) {
    const [u, v] = range;
    const curLen = this.getVisibleText().length;
    if (u < 0 || v > curLen || u > v) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    if (!this.activeSessionId) {
      return this.startComposition(range, selection);
    }

    let cur = 0;
    const newTape = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        const tokStart = cur;
        const tokEnd = cur + 1;
        cur = tokEnd;
        if (tokStart >= u && tokEnd <= v) {
          newTape.push({
            type: 'ProvisionalRun',
            id: `run:${tokStart}`,
            units: [{
              id: entry.id,
              char: entry.char,
              attrs: { ...entry.attrs },
              originSessionId: entry.originSessionId
            }],
            replacedCommitted: [{
              id: entry.id,
              char: entry.char,
              attrs: { ...entry.attrs },
              originSessionId: entry.originSessionId
            }],
            originSessionId: this.activeSessionId
          });
        } else {
          newTape.push(entry);
        }
      } else if (entry.type === 'ProvisionalRun') {
        cur += entry.units.length;
        newTape.push(entry);
      } else if (entry.type === 'DeletionMarker') {
        newTape.push(entry);
      }
    }
    this.tape = newTape;

    this.markedSpan = [u, v];
    const visibleUnits = this.getVisibleUnits();
    const B_epoch = visibleUnits.slice(u, v);
    this.activeSeed = (u < visibleUnits.length) ? { ...visibleUnits[u].attrs } : {};

    this.epochSnapshot = {
      tape: JSON.parse(JSON.stringify(this.tape)),
      markedSpan: [u, v],
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      seed: { ...this.activeSeed }
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
    this.sessRev++;

    return { status: 'Accepted', markedSpan: [u, v] };
  }

  partialCommit(k, selection = null) {
    if (!this.markedSpan) {
      return { status: 'Rejected', reason: 'NoActiveMarkedRange' };
    }
    const [mStart, mEnd] = this.markedSpan;
    const curCandidateLen = mEnd - mStart;
    if (k > curCandidateLen) {
      return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate' };
    }

    const cutPoint = mStart + k;
    const newTape = [];
    let cur = 0;
    let committedChars = 0;

    const totalProvisionalUnits = this.tape
      .filter(e => e.type === 'ProvisionalRun')
      .reduce((sum, e) => sum + e.units.length, 0);

    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        newTape.push(entry);
        cur += 1;
      } else if (entry.type === 'ProvisionalRun') {
        const rLen = entry.units.length;
        const eStart = cur;
        const eEnd = cur + rLen;
        cur = eEnd;

        if (eEnd <= cutPoint) {
          for (const u of entry.units) {
            newTape.push({
              type: 'CommittedToken',
              id: u.id,
              char: u.char,
              attrs: { ...u.attrs },
              originSessionId: entry.originSessionId
            });
            committedChars++;
          }
        } else if (eStart < cutPoint) {
          const splitOffset = cutPoint - eStart;
          for (let i = 0; i < splitOffset; i++) {
            const u = entry.units[i];
            newTape.push({
              type: 'CommittedToken',
              id: u.id,
              char: u.char,
              attrs: { ...u.attrs },
              originSessionId: entry.originSessionId
            });
            committedChars++;
          }
          newTape.push({
            type: 'ProvisionalRun',
            id: entry.id + ':rem',
            units: entry.units.slice(splitOffset),
            // A committed prefix accepts this replacement operation's whole old
            // footprint. Only an unchanged original run has one-to-one residual
            // rollback ownership. This is an independently represented tape rule.
            replacedCommitted: entry.replacedCommitted && entry.units.length===entry.replacedCommitted.length && entry.units.every((u,i)=>u.id===entry.replacedCommitted[i].id)
              ? entry.replacedCommitted.slice(splitOffset) : [],
            originSessionId: entry.originSessionId
          });
        } else {
          newTape.push(entry);
        }
      } else if (entry.type === 'DeletionMarker') {
        const hasRemainingUnitsAfter = (committedChars < totalProvisionalUnits);
        if (entry.atVisibleOffset < cutPoint || (entry.atVisibleOffset === cutPoint && k > 0 && !hasRemainingUnitsAfter)) {
          // Deletion marker committed: discarded permanently
        } else {
          newTape.push(entry);
        }
      }
    }

    this.tape = newTape;
    const remLen = curCandidateLen - k;
    this.markedSpan = [cutPoint, cutPoint + remLen];

    // Track cumulative committed ranges
    if (committedChars > 0) {
      if (!this.sessionCommittedRanges) this.sessionCommittedRanges = [];
      // Original committed tape units between disjoint islands are carried
      // through but do not become candidate formatting targets.
      for(const island of (this.sessionIslands?.length?this.sessionIslands:[[mStart,mEnd]])) {
        const a=Math.max(0,island[0]),b=Math.min(cutPoint,island[1]);
        if(a<b)this.sessionCommittedRanges.push([a,b]);
      }
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

    const visibleUnits = this.getVisibleUnits();
    const B_epoch = visibleUnits.slice(this.markedSpan[0], this.markedSpan[1]);
    this.epochSnapshot = {
      tape: JSON.parse(JSON.stringify(this.tape)),
      markedSpan: [...this.markedSpan],
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      seed: { ...this.activeSeed }
    };
    // Preserve and map disjoint session islands across partial commits
    if (!this.sessionIslands || this.sessionIslands.length === 0) {
      this.sessionIslands = [[cutPoint, cutPoint + remLen]];
    } else {
      const updated = [];
      let found = false;
      for (const [u, v] of this.sessionIslands) {
        if (v <= mStart || u >= mEnd) {
          updated.push([u, v]);
        } else {
          updated.push([cutPoint, cutPoint + remLen]);
          found = true;
        }
      }
      if (!found) updated.push([cutPoint, cutPoint + remLen]);
      this.sessionIslands = updated;
    }

    if (selection) this.selection = [...selection];
    else this.selection = [cutPoint + remLen, cutPoint + remLen];
    this.sessRev++;

    return {
      status: 'Accepted',
      frozenCount: k,
      markedSpan: [...this.markedSpan]
    };
  }

  unmark(settled = true, selection = null) {
    if (!this.markedSpan) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    const finalSpan = [...this.markedSpan];
    this.markedSpan = null;
    if (selection) this.selection = [...selection];
    else this.selection = [finalSpan[1], finalSpan[1]];

    if (settled) {
      const visUnits = this.getVisibleUnits();
      this.tape = visUnits.map(u => ({
        type: 'CommittedToken',
        id: u.id,
        char: u.char,
        attrs: { ...u.attrs },
        originSessionId: u.originSessionId
      }));

      const snap = this.sessionSnapshot;
      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        const bSel = snap.selection ? [...snap.selection] : [finalSpan[0], finalSpan[0]];
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
          beforeSelection: bSel,
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterTape: JSON.parse(JSON.stringify(this.tape)),
          afterSelection: [...this.selection],
          extent: finalSpan,
          range: finalSpan,
          selection: bSel
        });
        this.redoStack = [];
      }

      this.drainCommands(finalSpan);
      this.activeSessionId = null;
      this.sessionSnapshot = null;
      this.epochSnapshot = null;
      this.activeSettlementFence = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.sessRev++;

      return { type: 'Unmarked', settled: true, status: 'Accepted', finalExtent: finalSpan };
    } else {
      const tok = this.nextSettlementToken();
      this.activeSettlementToken = { token: tok, sessionId: this.activeSessionId, epoch: this.activeEpoch };
      this.activeSettlementFence = { token: tok, sessionId: this.activeSessionId, epoch: this.activeEpoch };
      this.settlementEvidence = { token: tok, sessionId: this.activeSessionId, extent: finalSpan };
      this.sessRev++;

      return { type: 'Unmarked', settled: false, status: 'Accepted', token: tok, finalExtent: finalSpan };
    }
  }

  completeSettlement(token = null, sessionId = null, epoch = null) {
    if (!this.activeSessionId && !this.activeSettlementFence) {
      return { status: 'Rejected', reason: 'NoActiveSettlement' };
    }
    if (this.markedSpan !== null) {
      return { status: 'Rejected', reason: 'CandidateStillActive' };
    }
    if (epoch !== null && epoch !== this.activeEpoch) {
      return { status: 'Rejected', reason: 'StaleEpoch' };
    }
    if (sessionId && this.activeSessionId && sessionId !== this.activeSessionId) {
      return { status: 'Rejected', reason: 'SessionMismatch' };
    }
    if (this.activeSettlementFence) {
      if (!token || token !== this.activeSettlementFence.token) {
        return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
      }
    }
    if (!this.settlementEvidence) {
      return { status: 'Rejected', reason: 'UnknownTargetExtent' };
    }

    const finalExtent = [...this.settlementEvidence.extent];
    const visUnits = this.getVisibleUnits();
    this.tape = visUnits.map(u => ({
      type: 'CommittedToken',
      id: u.id,
      char: u.char,
      attrs: { ...u.attrs },
      originSessionId: u.originSessionId
    }));

    const snap = this.sessionSnapshot;
    const curText = this.getVisibleText();
    const curAttrs = this.getVisibleAttrs();
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterTape: JSON.parse(JSON.stringify(this.tape)),
        afterSelection: [...this.selection],
        extent: finalExtent,
        range: finalExtent,
        selection: bSel
      });
      this.redoStack = [];
    }

    this.drainCommands(finalExtent);
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    this.sessRev++;

    return { type: 'SettlementCompleted', status: 'Accepted', finalExtent };
  }

  cancel() {
    const acceptedSelection = [...this.selection];
    const prevText = this.getVisibleText();
    let cancelPoint = (this.markedSpan) ? this.markedSpan[0] : 0;
    let curVis = 0;
    for (const entry of this.tape) {
      if (entry.type === 'ProvisionalRun' || entry.type === 'DeletionMarker') {
        cancelPoint = curVis;
        break;
      }
      curVis += (entry.type === 'CommittedToken' ? 1 : 0);
    }

    const newTape = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        newTape.push(entry);
      } else if (entry.type === 'ProvisionalRun' || entry.type === 'DeletionMarker') {
        for (const r of entry.replacedCommitted) {
          newTape.push({
            type: 'CommittedToken',
            id: r.id,
            char: r.char,
            attrs: { ...r.attrs },
            originSessionId: r.originSessionId
          });
        }
      }
    }

    const prevVisUnits = this.getVisibleUnits();
    const prevAttrs = this.getVisibleAttrs();
    this.tape = newTape;
    const curText = this.getVisibleText();
    const curAttrs = this.getVisibleAttrs();
    
    if (curText !== prevText || !areAttrsEqual(curAttrs, prevAttrs)) {
      this.docRev++;
    }
    
    const snap = this.sessionSnapshot;
    if (snap) {
      this.selection = [...snap.selection];
    }
    // Cancellation after accepted prefix edits cannot restore stale offsets.
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs,snap.attrs))) {
      const acceptedEnds=this.sessionCommittedRanges.map(r=>r[1]);
      if(acceptedEnds.length){const p=Math.max(0,Math.min(curText.length,Math.max(...acceptedEnds)));this.selection=[p,p];}
      else this.selection=acceptedSelection.map(p=>Math.max(0,Math.min(curText.length,p)));
    }
    const sessionExtent = this._computeSessionExtent();
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      const bSel = snap.selection ? [...snap.selection] : [0, 0];
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
        beforeSelection: bSel,
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        afterTape: JSON.parse(JSON.stringify(this.tape)),
        afterSelection: [...this.selection],
        extent: sessionExtent,
        range: sessionExtent,
        selection: bSel
      });
      this.redoStack = [];
    }
    
    // Cancel commands targeting active composition and map TrackedRanges using token identities
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.target && record.target.type === 'CompositionTarget') {
          record.status = 'Cancelled';
          this.commandOutcomes.set(ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
        } else if (record.target && record.target.type === 'TrackedRange') {
          const r = record.target.range;
          if (!r || r.length !== 2) {
            record.status = 'Rejected';
            this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
            continue;
          }
          const [u, v] = [Math.min(r[0], r[1]), Math.max(r[0], r[1])];
          if (u === v) {
            let dcPos = null;
            if (u < prevVisUnits.length) {
              const uId = prevVisUnits[u].id;
              const idx = newTape.findIndex(t => t.id === uId);
              if (idx !== -1) {
                dcPos = idx;
              }
            }
            if (dcPos === null) {
              const snapSel = this.sessionSnapshot ? this.sessionSnapshot.selection : null;
              dcPos = (snapSel && snapSel.length === 2) ? snapSel[0] : 0;
            }
            dcPos = Math.max(0, Math.min(newTape.length, dcPos));
            record.target.range = [dcPos, dcPos];
            record.target.ranges = [[dcPos, dcPos]];
          } else {
            const coveredUnits = prevVisUnits.slice(u, v);
            const survivingInTape = coveredUnits.filter(cu => newTape.some(t => t.id === cu.id));
            if (survivingInTape.length === 0) {
              record.status = 'Rejected';
              this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetDeleted' });
            } else {
              const survivingIndices = survivingInTape.map(cu => newTape.findIndex(t => t.id === cu.id)).sort((a, b) => a - b);
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
              record.target.range = [...runs[0]];
              record.target.ranges = runs;
            }
          }
        }
      }
    }

    // Surviving unrelated commands and queued Undo operations MUST be drained and executed immediately (Finding E)
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.type === 'Undo') {
          if (this.undoStack.length === 0) {
            record.status = 'Rejected';
            this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'NothingToUndo' });
            continue;
          }

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
            for (let i = 0; i < this.tape.length && i < entry.beforeAttrs.length; i++) {
              this.tape[i].attrs = { ...entry.beforeAttrs[i] };
            }
          } else {
            const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
            const bAttrs = entry.beforeAttrs || [];
            this.tape = [];
            for (let i = 0; i < bText.length; i++) {
              this.tape.push({
                type: 'CommittedToken',
                id: `undo:${i}`,
                char: bText[i],
                attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
                originSessionId: null
              });
            }
          }
          this.selection = [...bSel];
          record.status = 'Applied';
          this.commandOutcomes.set(ticketId, {
            status: 'Applied',
            action: 'Undo',
            group: entry.group
          });
          this._remapPendingCommandTargets(this.tape);
          continue;
        }

        if (record.cmd && record.cmd.type === 'Format') {
          const curDocText = this.getVisibleText();
          const patch = record.cmd.patch || {};
          let targetRanges = [];
          if (record.target && record.target.type === 'TrackedRange') {
            if (record.target.ranges && record.target.ranges.length > 0) {
              for (const rg of record.target.ranges) {
                const [r0, r1] = [Math.min(rg[0], rg[1]), Math.max(rg[0], rg[1])];
                if (r0 >= 0 && r1 <= curDocText.length) {
                  targetRanges.push([r0, r1]);
                }
              }
            } else {
              const r = record.target.range;
              if (r && r.length === 2) {
                const [r0, r1] = [Math.min(r[0], r[1]), Math.max(r[0], r[1])];
                if (r0 >= 0 && r1 <= curDocText.length) {
                  targetRanges.push([r0, r1]);
                } else {
                  record.status = 'Rejected';
                  this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
                  continue;
                }
              } else {
                record.status = 'Rejected';
                this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
                continue;
              }
            }
            if (targetRanges.length === 0) {
              record.status = 'Rejected';
              this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
              continue;
            }
          } else if (record.target && record.target.type === 'SelectionTarget') {
            targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
          }

          // Collapsed range toggle typing intent (§4.5, Defect 2)
          if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
            this.typingIntent = { ...this.typingIntent, ...patch };
            record.status = 'Applied';
            this.commandOutcomes.set(ticketId, {
              status: 'Applied',
              typingIntentUpdated: true,
              patch: { ...patch },
              range: [...targetRanges[0]],
              ranges: [[...targetRanges[0]]]
            });
            continue;
          }

          const beforeAttrs = cloneAttrs(this.getVisibleAttrs());
          let anyChanged = false;
          const appliedRanges = [];

          for (const [tStart, tEnd] of targetRanges) {
            if (tStart === tEnd) continue;
            const [fStart, fEnd] = oracleExpandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
            appliedRanges.push([fStart, fEnd]);

            for (let i = fStart; i < fEnd; i++) {
              if (i < this.tape.length) {
                for (const k of Object.keys(patch)) {
                  if (this.tape[i].attrs[k] !== patch[k]) {
                    this.tape[i].attrs[k] = patch[k];
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
              beforeSelection: [...this.selection],
              afterText: this.getVisibleText(),
              afterAttrs: cloneAttrs(this.getVisibleAttrs()),
              afterSelection: [...this.selection],
              range: outcomeRange,
              ranges: appliedRanges,
              patch: { ...patch },
              selection: [...this.selection]
            });
            this.redoStack = [];
          }

          record.status = 'Applied';
          this.commandOutcomes.set(ticketId, {
            status: 'Applied',
            range: outcomeRange,
            ranges: appliedRanges,
            patch: record.cmd.patch,
            noOp: !anyChanged
          });
        }
      }
    }
    
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    this.sessRev++;
    return { status: 'Accepted' };
  }

  onFocusLost(strategy = 'cancel') {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('onFocusLost', () => {
      return this._internalOnFocusLost(strategy);
    });
  }

  _internalOnFocusLost(strategy = 'cancel') {
    if (this.activeSessionId) {
      if (strategy === 'settle') {
        const res = this.unmark(true);
        return { type: 'FocusLost', status: res.status, action: 'Settled' };
      } else {
        const res = this.cancel();
        return { type: 'FocusLost', status: res.status, action: 'Cancelled' };
      }
    }
    return { type: 'FocusLost', status: 'Accepted', action: 'None' };
  }

  openNewConnection() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('openNewConnection', () => {
      return this._internalOpenNewConnection();
    });
  }

  _internalOpenNewConnection() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this.activeSessionId !== null || this.activeSettlementFence !== null) {
      const sessionExtent = this._computeSessionExtent();
      const visUnits = this.getVisibleUnits();
      this.tape = visUnits.map(u => ({
        type: 'CommittedToken',
        id: u.id,
        char: u.char,
        attrs: { ...u.attrs },
        originSessionId: u.originSessionId
      }));

      const snap = this.sessionSnapshot;
      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterTape: JSON.parse(JSON.stringify(this.tape)),
          afterSelection: [...this.selection],
          extent: sessionExtent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }
    }

    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.connectionState = 'Active';
    this.activeEpoch++;
    this.receiptCache.clear(); // Evict receipt cache (Finding F)
    this.sessRev++;

    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.status === 'Pending') {
        rec.status = 'Cancelled';
        this.commandOutcomes.set(tId, { status: 'Cancelled', reason: 'StaleEpoch' });
      }
    }
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this.activeEpoch };
  }

  dispose() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('dispose', () => {
      return this._internalDispose();
    });
  }

  _internalDispose() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this.activeSessionId !== null || this.activeSettlementFence !== null) {
      const snap = this.sessionSnapshot;
      const sessionExtent = this._computeSessionExtent();
      const visUnits = this.getVisibleUnits();
      this.tape = visUnits.map(u => ({
        type: 'CommittedToken',
        id: u.id,
        char: u.char,
        attrs: { ...u.attrs },
        originSessionId: u.originSessionId
      }));
      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterTape: JSON.parse(JSON.stringify(this.tape)),
          afterSelection: [...this.selection],
          extent: sessionExtent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }
    }

    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
    this.connectionState = 'Closed';
    this.receiptCache.clear();
    this.sessRev++;

    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.status === 'Pending') {
        rec.status = 'Cancelled';
        this.commandOutcomes.set(tId, { status: 'Cancelled', reason: 'Disposed' });
      }
    }
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    for (const rMut of this._reentrantMutationQueue) {
      this.commandOutcomes.set(rMut.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this._reentrantMutationQueue = [];
    return { type: 'Disposed', status: 'Accepted' };
  }

  setSelection(selection) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('setSelection', () => {
      if (selection && this._validateSelection(selection, this.getVisibleText().length)) {
        this.selection = [...selection];
        return { type: 'SelectionChanged', status: 'Accepted', selection: [...this.selection] };
      } else {
        return { status: 'Rejected', reason: 'InvalidSelection' };
      }
    });
  }

  nativeRemoval(range, replacementText = '', selection = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('nativeRemoval', () => {
      return this._internalNativeRemoval(range, replacementText, selection);
    });
  }

  _internalNativeRemoval(range, replacementText = '', selection = null) {
    let rStart, rEnd;
    const curLen = this.getVisibleText().length;
    if (range) {
      rStart = Math.min(range[0], range[1]);
      rEnd = Math.max(range[0], range[1]);
    } else {
      const sel = this.selection || [0, 0];
      rStart = Math.min(sel[0], sel[1]);
      rEnd = Math.max(sel[0], sel[1]);
    }

    if (rStart < 0 || rEnd > curLen || rStart > rEnd) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    if (this.activeSessionId !== null) {
      const snap = this.sessionSnapshot;
      const visUnits = this.getVisibleUnits();
      const committedUnits = visUnits.map(u => ({
        type: 'CommittedToken',
        id: u.id,
        char: u.char,
        attrs: { ...u.attrs },
        originSessionId: null
      }));

      const repTokens = [];
      for (let i = 0; i < replacementText.length; i++) {
        repTokens.push({
          type: 'CommittedToken',
          id: `rem:${this.settlementSeqCounter}:${i}`,
          char: replacementText[i],
          attrs: (rStart < committedUnits.length) ? { ...committedUnits[rStart].attrs } : {},
          originSessionId: null
        });
      }

      this.tape = [
        ...committedUnits.slice(0, rStart),
        ...repTokens,
        ...committedUnits.slice(rEnd)
      ];

      if (selection) {
        this.selection = [...selection];
      } else {
        this.selection = [rStart + replacementText.length, rStart + replacementText.length];
      }

      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      const extent = [rStart, rStart + replacementText.length];

      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : JSON.parse(JSON.stringify(committedUnits)),
          beforeSelection: snap.selection ? [...snap.selection] : [0, 0],
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          afterTape: JSON.parse(JSON.stringify(this.tape)),
          afterSelection: [...this.selection],
          extent,
          selection: snap.selection ? [...snap.selection] : [0, 0]
        });
        this.redoStack = [];
      }

      this.drainCommands(extent);
      this.markedSpan = null;
      this.activeSessionId = null;
      this.sessionSnapshot = null;
      this.epochSnapshot = null;
      this.activeSettlementToken = null;
      this.activeSettlementFence = null;
      this.settlementEvidence = null;
      this.sessRev++;

      return {
        type: 'NativeRemovalApplied',
        status: 'Accepted',
        selection: [...this.selection]
      };
    } else {
      const beforeText = this.getVisibleText();
      const beforeAttrs = cloneAttrs(this.getVisibleAttrs());
      const beforeSelection = [...this.selection];
      const beforeTape = JSON.parse(JSON.stringify(this.tape));

      const repTokens = [];
      for (let i = 0; i < replacementText.length; i++) {
        repTokens.push({
          type: 'CommittedToken',
          id: `rem:${this.settlementSeqCounter}:${i}`,
          char: replacementText[i],
          attrs: (rStart < this.tape.length) ? { ...this.tape[rStart].attrs } : {},
          originSessionId: null
        });
      }

      this.tape = [
        ...this.tape.slice(0, rStart),
        ...repTokens,
        ...this.tape.slice(rEnd)
      ];

      if (selection) {
        this.selection = [...selection];
      } else {
        this.selection = [rStart + replacementText.length, rStart + replacementText.length];
      }

      const afterText = this.getVisibleText();
      const afterAttrs = cloneAttrs(this.getVisibleAttrs());
      const extent = [rStart, rStart + replacementText.length];

      if (afterText !== beforeText || !areAttrsEqual(afterAttrs, beforeAttrs)) {
        this.undoStack.push({
          group: 'NativeRemoval',
          beforeText,
          beforeAttrs,
          beforeTape: JSON.parse(JSON.stringify(beforeTape)),
          beforeSelection,
          afterText,
          afterAttrs,
          afterTape: JSON.parse(JSON.stringify(this.tape)),
          afterSelection: [...this.selection],
          extent,
          selection: [...beforeSelection]
        });
        this.redoStack = [];
      }

      this.sessRev++;
      return {
        type: 'NativeRemovalApplied',
        status: 'Accepted',
        selection: [...this.selection]
      };
    }
  }

  // Application Command Dispatcher (§4.5, Defect 2)
  dispatchCommand(cmd, target = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (!cmd || typeof cmd !== 'object' || !cmd.type) {
      return { status: 'Rejected', reason: 'InvalidCommand' };
    }
    if (cmd.type !== 'Format') {
      return { status: 'Rejected', reason: `UnknownCommandType:${cmd.type}` };
    }
    if (target && target.type && !['SelectionTarget', 'TrackedRange', 'CompositionTarget'].includes(target.type)) {
      return { status: 'Rejected', reason: 'InvalidTarget' };
    }

    return this._runInTransaction('dispatchCommand', () => {
      return this._internalDispatchCommand(cmd, target);
    });
  }

  _internalDispatchCommand(cmd, target = null) {
    if (!cmd || typeof cmd !== 'object' || !cmd.type) {
      return { status: 'Rejected', reason: 'InvalidCommand' };
    }
    if (cmd.type !== 'Format') {
      return { status: 'Rejected', reason: `UnknownCommandType:${cmd.type}` };
    }

    if (this.activeSessionId !== null || this.activeSettlementFence !== null) {
      const ticketId = this.queueCommand(cmd, target);
      return { status: 'Accepted', ticketId };
    }

    // In Idle: execute immediately
    const ticketId = this.nextTicketId();
    if (cmd.type === 'Format') {
      const patch = cmd.patch || {};
      let targetRanges = [];
      const curDocText = this.getVisibleText();

      if (!target || target.type === 'SelectionTarget') {
        const s = this.selection || [0, 0];
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

      if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
        this.typingIntent = { ...this.typingIntent, ...patch };
        const outcome = { status: 'Applied', typingIntentUpdated: true, patch };
        this.commandOutcomes.set(ticketId, outcome);
        return { status: 'Applied', ticketId, typingIntentUpdated: true };
      }

      const beforeAttrs = cloneAttrs(this.getVisibleAttrs());
      let anyChanged = false;
      const appliedRanges = [];

      for (const [tStart, tEnd] of targetRanges) {
        if (tStart === tEnd) continue;
        const [formatStart, formatEnd] = oracleExpandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
        appliedRanges.push([formatStart, formatEnd]);

        for (let i = formatStart; i < formatEnd; i++) {
          if (i < this.tape.length) {
            for (const k of Object.keys(patch)) {
              if (this.tape[i].attrs[k] !== patch[k]) {
                this.tape[i].attrs[k] = patch[k];
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
          beforeSelection: [...this.selection],
          afterText: this.getVisibleText(),
          afterAttrs: cloneAttrs(this.getVisibleAttrs()),
          afterSelection: [...this.selection],
          range: outcomeRange,
          ranges: appliedRanges,
          patch,
          selection: [...this.selection]
        });
        this.redoStack = [];
      }

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

    return { status: 'Rejected', ticketId, reason: `UnknownCommandType:${cmd.type}` };
  }

  queueCommand(cmd, target) {
    const ticketId = this.nextTicketId();
    let tokenIds = null;
    if (target && target.type === 'TrackedRange' && target.range) {
      const [u, v] = [Math.min(target.range[0], target.range[1]), Math.max(target.range[0], target.range[1])];
      const vis = this.getVisibleUnits();
      tokenIds = vis.slice(u, v).map(tok => tok.id);
    }
    this.commandLedger.set(ticketId, {
      ticketId,
      status: 'Pending',
      cmd,
      target: JSON.parse(JSON.stringify(target)),
      tokenIds,
      sessionId: this.activeSessionId,
      epoch: this.activeEpoch
    });
    return ticketId;
  }

  _remapPendingCommandTargets(currentTape) {
    const flatUnits = [];
    for (const item of currentTape) {
      if (item.type === 'ProvisionalRun') {
        for (const u of item.units) flatUnits.push(u);
      } else {
        flatUnits.push(item);
      }
    }
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status !== 'Pending') continue;
      if (record.target && record.target.type === 'TrackedRange') {
        if (!record.tokenIds || record.tokenIds.length === 0) continue;
        const surviving = [];
        for (const tId of record.tokenIds) {
          if (!tId) continue;
          const idx = flatUnits.findIndex(tok => tok.id === tId);
          if (idx !== -1 && !surviving.includes(idx)) surviving.push(idx);
        }
        if (surviving.length === 0) {
          record.status = 'Rejected';
          this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
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
        record.target.range = [...runs[0]];
        record.target.ranges = runs.map(r => [...r]);
        record.target.isFragmented = runs.length > 1;
      } else if (record.target && record.target.type === 'CompositionTarget') {
        const lastPoppedWasComposition = this.redoStack.some(r => r.group === 'Composition');
        const compSessionId = record.target.sessionId || this.activeSessionId;
        let hasCompUnits = false;
        if (compSessionId) {
          hasCompUnits = flatUnits.some(tok => tok.originSessionId === compSessionId);
        }
        if (lastPoppedWasComposition && !hasCompUnits) {
          record.status = 'Rejected';
          this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
        }
      }
    }
  }

  drainCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];

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
      if (mergedSessionRanges.length === 0) mergedSessionRanges.push([...r]);
      else {
        const last = mergedSessionRanges[mergedSessionRanges.length - 1];
        if (r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
        else mergedSessionRanges.push([...r]);
      }
    }
    this._remapPendingCommandTargets(this.tape);
    
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status !== 'Pending') continue;

      if (record.type === 'Undo') {
        if (this.undoStack.length === 0) {
          record.status = 'Rejected';
          this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'NothingToUndo' });
          continue;
        }

        const entry = this.undoStack.pop();
        const bSel = entry.beforeSelection ? [...entry.beforeSelection] : (entry.selection ? [...entry.selection] : [0, 0]);
        const aSel = entry.afterSelection ? [...entry.afterSelection] : [...this.selection];

        this.redoStack.push({
          group: entry.group,
          beforeText: entry.beforeText,
          beforeAttrs: cloneAttrs(entry.beforeAttrs),
          beforeTape: entry.beforeTape ? JSON.parse(JSON.stringify(entry.beforeTape)) : null,
          beforeSelection: bSel,
          afterText: entry.afterText,
          afterAttrs: cloneAttrs(entry.afterAttrs),
          afterTape: entry.afterTape ? JSON.parse(JSON.stringify(entry.afterTape)) : null,
          afterSelection: aSel,
          extent: entry.extent ? [...entry.extent] : null,
          range: entry.range ? [...entry.range] : null,
          ranges: entry.ranges ? JSON.parse(JSON.stringify(entry.ranges)) : null,
          patch: entry.patch ? { ...entry.patch } : null,
          selection: bSel
        });

        if (entry.group === 'Formatting' && entry.beforeAttrs) {
          for (let i = 0; i < this.tape.length && i < entry.beforeAttrs.length; i++) {
            this.tape[i].attrs = { ...entry.beforeAttrs[i] };
          }
        } else if (entry.beforeTape) {
          this.tape = JSON.parse(JSON.stringify(entry.beforeTape));
        } else {
          const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
          const bAttrs = entry.beforeAttrs || [];
          this.tape = [];
          for (let i = 0; i < bText.length; i++) {
            this.tape.push({
              type: 'CommittedToken',
              id: `undo:${i}`,
              char: bText[i],
              attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
              originSessionId: null
            });
          }
        }
        this.selection = [...bSel];
        record.status = 'Applied';
        this.commandOutcomes.set(ticketId, {
          status: 'Applied',
          action: 'Undo',
          group: entry.group
        });
        this._remapPendingCommandTargets(this.tape);
        continue;
      }

      if (record.cmd && record.cmd.type === 'Format') {
        const curDocText = this.getVisibleText();
        const patch = record.cmd.patch || {};
        
        let targetRanges = [];
        if (record.target && record.target.type === 'TrackedRange') {
          if (record.target.ranges && record.target.ranges.length > 0) {
            for (const rg of record.target.ranges) {
              const [r0, r1] = [Math.min(rg[0], rg[1]), Math.max(rg[0], rg[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              }
            }
          } else {
            const r = record.target.range;
            if (r && r.length === 2) {
              const [r0, r1] = [Math.min(r[0], r[1]), Math.max(r[0], r[1])];
              if (r0 >= 0 && r1 <= curDocText.length) {
                targetRanges.push([r0, r1]);
              } else {
                record.status = 'Rejected';
                this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
                continue;
              }
            } else {
              record.status = 'Rejected';
              this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'InvalidTargetRange' });
              continue;
            }
          }
          if (targetRanges.length === 0) {
            record.status = 'Rejected';
            this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetInvalidated' });
            continue;
          }
        } else if (record.target && record.target.type === 'CompositionTarget') {
            if (record.target.sessionId && this.sessionSnapshot && record.target.sessionId !== this.activeSessionId) {
              record.status = 'Rejected';
              this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetSessionMismatch' });
              continue;
            }

            const totalSessionUnits = mergedSessionRanges.reduce((sum, r) => sum + (r[1] - r[0]), 0);
            if (totalSessionUnits === 0) {
              this.typingIntent = { ...this.typingIntent, ...patch };
              record.status = 'Applied';
              this.commandOutcomes.set(ticketId, {
                status: 'Applied',
                typingIntentUpdated: true,
                patch: { ...patch },
                range: [eStart, eStart],
                ranges: [[eStart, eStart]]
              });
              continue;
            }
            targetRanges = mergedSessionRanges.length > 0 ? mergedSessionRanges : [[eStart, eEnd]];
          } else if (record.target && record.target.type === 'SelectionTarget') {
            targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
          } else {
            targetRanges = [[eStart, eEnd]];
          }

          // Collapsed range toggle typing intent (§4.5, Defect 2)
          if (targetRanges.length === 1 && targetRanges[0][0] === targetRanges[0][1]) {
            this.typingIntent = { ...this.typingIntent, ...patch };
            record.status = 'Applied';
            this.commandOutcomes.set(ticketId, {
              status: 'Applied',
              typingIntentUpdated: true,
              patch: { ...patch },
              range: [...targetRanges[0]],
              ranges: [[...targetRanges[0]]]
            });
            continue;
          }

          const beforeAttrs = cloneAttrs(this.getVisibleAttrs());
          let anyChanged = false;
          const appliedRanges = [];

          for (const [tStart, tEnd] of targetRanges) {
            if (tStart === tEnd) continue;
            const [fStart, fEnd] = oracleExpandToGraphemeBoundaries(curDocText, [tStart, tEnd]);
            appliedRanges.push([fStart, fEnd]);

            for (let i = fStart; i < fEnd; i++) {
              if (i < this.tape.length) {
                for (const k of Object.keys(patch)) {
                  if (this.tape[i].attrs[k] !== patch[k]) {
                    this.tape[i].attrs[k] = patch[k];
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
              beforeSelection: [...this.selection],
              afterText: this.getVisibleText(),
              afterAttrs: cloneAttrs(this.getVisibleAttrs()),
              afterSelection: [...this.selection],
              range: outcomeRange,
              ranges: appliedRanges,
              patch: { ...patch },
              selection: [...this.selection]
            });
            this.redoStack = [];
          }

          record.status = 'Applied';
          this.commandOutcomes.set(ticketId, {
            status: 'Applied',
            range: outcomeRange,
            ranges: appliedRanges,
            patch: record.cmd.patch,
            noOp: !anyChanged
          });
        }
      }
    this.sessionIslands = [];
    this.sessionCommittedRanges = [];
  }

  undo(syncBoundary = false, settlementEvidence = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('undo', () => {
      return this._internalUndo(syncBoundary, settlementEvidence);
    });
  }

  redo() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('redo', () => {
      return this._internalRedo();
    });
  }

  _internalUndo(syncBoundary = false, settlementEvidence = null) {
    if (this.activeSessionId !== null || this.activeSettlementFence !== null) {
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
        if (settlementEvidence.epoch !== this.activeEpoch) {
          return { status: 'Rejected', reason: 'EpochMismatch' };
        }
        if (!settlementEvidence.sessionId || typeof settlementEvidence.sessionId !== 'string') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (this.activeSessionId && settlementEvidence.sessionId !== this.activeSessionId) {
          return { status: 'Rejected', reason: 'SessionMismatch' };
        }
        if (settlementEvidence.generation === undefined || typeof settlementEvidence.generation !== 'number') {
          return { status: 'Rejected', reason: 'MalformedSettlementEvidence' };
        }
        if (settlementEvidence.generation !== this.sessRev) {
          return { status: 'Rejected', reason: 'ObsoleteSettlementEvidence' };
        }
        const ext = settlementEvidence.extent;
        if (!ext || !Array.isArray(ext) || ext.length !== 2 || typeof ext[0] !== 'number' || typeof ext[1] !== 'number' || ext[0] < 0 || ext[1] > this.getVisibleText().length || ext[0] > ext[1]) {
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
        const visUnits = this.getVisibleUnits();
        this.tape = visUnits.map(u => ({
          type: 'CommittedToken',
          id: u.id,
          char: u.char,
          attrs: { ...u.attrs },
          originSessionId: u.originSessionId
        }));
        const snap = this.sessionSnapshot;
        const curText = this.getVisibleText();
        const curAttrs = this.getVisibleAttrs();
        if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
          const bSel = snap.selection ? [...snap.selection] : [finalExtent[0], finalExtent[0]];
          this.undoStack.push({
            group: 'Composition',
            beforeText: snap.text,
            beforeAttrs: cloneAttrs(snap.attrs),
            beforeTape: snap && snap.tape ? JSON.parse(JSON.stringify(snap.tape)) : null,
            beforeSelection: bSel,
            afterText: curText,
            afterAttrs: cloneAttrs(curAttrs),
            afterTape: JSON.parse(JSON.stringify(this.tape)),
            afterSelection: [...this.selection],
            extent: finalExtent,
            range: finalExtent,
            selection: bSel
          });
          this.redoStack = [];
        }
        this.drainCommands(finalExtent);
        this.markedSpan = null;
        this.activeSessionId = null;
        this.sessionSnapshot = null;
        this.epochSnapshot = null;
        this.sessionIslands = [];
        this.sessionCommittedRanges = [];
        this.activeSettlementToken = null;
        this.activeSettlementFence = null;
        this.settlementEvidence = null;
      } else {
        if (this.activeSessionId !== null && this.activeSettlementFence === null) {
          const tok = this.nextSettlementToken();
          this.activeSettlementToken = { token: tok, sessionId: this.activeSessionId, epoch: this.activeEpoch };
          this.activeSettlementFence = { token: tok, sessionId: this.activeSessionId, epoch: this.activeEpoch };
          this.settlementEvidence = null;
        }
        const ticketId = this.nextTicketId('undo');
        const undoRecord = {
          type: 'Undo',
          ticketId,
          status: 'Pending',
          sessionId: this.activeSessionId,
          epoch: this.activeEpoch
        };
        this.commandLedger.set(ticketId, undoRecord);
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
      for (let i = 0; i < this.tape.length && i < entry.beforeAttrs.length; i++) {
        this.tape[i].attrs = { ...entry.beforeAttrs[i] };
      }
    } else {
      const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
      const bAttrs = entry.beforeAttrs || [];
      this.tape = [];
      for (let i = 0; i < bText.length; i++) {
        this.tape.push({
          type: 'CommittedToken',
          id: `undo:${i}`,
          char: bText[i],
          attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
          originSessionId: null
        });
      }
    }

    this.markedSpan = null;
    this.activeSessionId = null;
    this.selection = [...bSel];
    this.sessRev++;

    return {
      status: 'Accepted',
      action: 'Undo',
      group: entry.group,
      docText: this.getVisibleText(),
      selection: [...this.selection],
      documentRevision: this.docRev,
      sessionRevision: this.sessRev
    };
  }

  _internalRedo() {
    if (this.redoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToRedo' };
    }
    if (this.activeSessionId !== null) {
      const snap = this.sessionSnapshot;
      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      const modified = snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs));
      if (modified) {
        this.redoStack = [];
        return { status: 'Rejected', reason: 'NothingToRedo' };
      }
      const newTape = [];
      for (const entry of this.tape) {
        if (entry.type === 'CommittedToken') {
          newTape.push(entry);
        } else if (entry.type === 'ProvisionalRun' || entry.type === 'DeletionMarker') {
          for (const r of entry.replacedCommitted) {
            newTape.push({
              type: 'CommittedToken',
              id: r.id,
              char: r.char,
              attrs: { ...r.attrs },
              originSessionId: r.originSessionId
            });
          }
        }
      }
      this.tape = newTape;
      if (snap) this.selection = [...snap.selection];
      this.markedSpan = null;
      this.activeSessionId = null;
      this.sessionSnapshot = null;
      this.epochSnapshot = null;
      this.sessionIslands = [];
      this.sessionCommittedRanges = [];
      this.activeSettlementToken = null;
      this.activeSettlementFence = null;
      this.settlementEvidence = null;
    }

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
      for (let i = 0; i < this.tape.length && i < entry.afterAttrs.length; i++) {
        this.tape[i].attrs = { ...entry.afterAttrs[i] };
      }
    } else if (entry.afterTape) {
      this.tape = JSON.parse(JSON.stringify(entry.afterTape));
    } else {
      const aText = (entry.afterText !== undefined && entry.afterText !== null) ? entry.afterText : '';
      const aAttrs = entry.afterAttrs || [];
      this.tape = [];
      for (let i = 0; i < aText.length; i++) {
        this.tape.push({
          type: 'CommittedToken',
          id: `redo:${i}`,
          char: aText[i],
          attrs: aAttrs[i] ? { ...aAttrs[i] } : {},
          originSessionId: null
        });
      }
    }

    this.markedSpan = null;
    this.activeSessionId = null;
    this.selection = [...aSel];
    this.sessRev++;

    return {
      status: 'Accepted',
      action: 'Redo',
      group: entry.group,
      docText: this.getVisibleText(),
      selection: [...this.selection],
      documentRevision: this.docRev,
      sessionRevision: this.sessRev
    };
  }
}
