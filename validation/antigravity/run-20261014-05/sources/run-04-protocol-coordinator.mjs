// Repaired Protocol Coordinator and Transaction Engine Reference Model (§4, §6, §7)
// Run-20261013-04 Comprehensive Specification Repairs & Falsification Hardening:
// - Native-Range Proposal Fidelity (§1, §4.4, I08):
//     Native target expansion / replacement ranges [newStart, newEnd) are admitted with exact fidelity.
//     Never silently clamped to committedExtent[1].
//     Previously committed text is not permanently immutable; explicit native operations can encompass it.
//     Invariant I08 guarantees suffix cancellation does not erase committed prefix; explicit native edits are admitted.
// - Revision Contradiction Resolution (§2, §4.4):
//     DocumentRevision advances iff actual document semantics (text, raw attributes, structure) change.
//     Metadata-only partialCommit does NOT advance DocumentRevision (SessionRevision advances).
//     Computed strictly from before/after semantic state comparison.
// - Settlement Token & Ordering Contract (§5, §4.4):
//     requestSettlement() generates a monotonic ordered settlement token.
//     New candidate input (preedit, expansion, partialCommit) invalidates/supersedes pending settlement token.
//     completeSettlement rejects if candidate still active, session/epoch mismatch, obsolete token, or unknown target.
//     Never resolves unknown target to [0, 0] or reuses obsolete extent.
// - Distinct Cancellation Semantics (§6, §4.4):
//     Separates nativeRemoval (explicit range/text), endCompositionPreservingText, and applicationAbort.
// - Queued Unrelated Command Mapping (§6, §4.5):
//     Range targets of queued commands are dynamically mapped across candidate expansions and contractions.
// - Deterministic IDs (§4):
//     All ticket IDs, session IDs, and settlement tokens use deterministic monotonic counters (no Date.now/random).

import { reconcileCandidateAttributes } from './reference-model.mjs';

export function cloneAttrs(attrs) {
  return attrs.map(a => ({ ...a }));
}

export function areAttrsEqual(a1, a2) {
  if (!a1 || !a2) return a1 === a2;
  if (a1.length !== a2.length) return false;
  for (let i = 0; i < a1.length; i++) {
    const keys1 = Object.keys(a1[i]);
    const keys2 = Object.keys(a2[i]);
    if (keys1.length !== keys2.length) return false;
    for (const k of keys1) {
      if (a1[i][k] !== a2[i][k]) return false;
    }
  }
  return true;
}

export class ProtocolCoordinator {
  constructor(initialText = "Hello", initialAttrs = null, initialSelection = [0, 0]) {
    this.epoch = 0;
    this.connectionState = 'Detached'; // Detached | Opening | Active | Draining | Closed | Faulted
    this.sequenceCounter = 0;
    this.receiptCache = new Map(); // stampKey -> receipt
    this.evictedReceipts = new Set(); // stampKey
    this.maxCacheSize = 50;
    
    // Deterministic ID generators
    this.ticketCounter = 0;
    this.sessionCounter = 0;
    this.settlementSeqCounter = 0;
    
    // Document state
    this.documentRevision = 0;
    this.sessionRevision = 0;
    this.docText = initialText;
    this.docAttributes = initialAttrs 
      ? cloneAttrs(initialAttrs)
      : new Array(initialText.length).fill(null).map(() => ({}));
    this.selection = [...initialSelection]; // [anchor, focus]
    this.typingIntent = {};
    
    // Composition state (§4.2, §4.4)
    this.compositionState = 'Idle'; // Idle | Composing | AwaitingSettlement | Faulted
    this.activeSessionId = null;
    this.sessionEpoch = 0;
    this.attributionEpoch = 0;
    this.sessionOriginStart = 0; // live document offset where composition initially started
    this.markedRange = null; // [start, end] in live document coordinates (transient candidate)
    this.baselineUnits = null; // immutable baseline B: array of { char, attributes }
    this.seedAttributes = {}; // default attributes for new candidate text
    this.committedUnits = []; // accumulated frozen units from partial commits: array of { char, attributes }
    this.committedExtent = null; // cumulative [start, end] of committed units in live doc coordinates
    this.historicalCommittedUnits = [];
    this.historicalCommittedExtent = null;
    this.sessionOriginEnd = 0;
    this.compositionBeforeSnapshot = null; // pinned snapshot before ANY composition changes
    
    // Settlement contract state (§5)
    this.activeSettlementToken = null; // { token, sessionId, epoch, seq }
    this.settlementEvidence = null; // { sessionId, extent, epoch, token }
    this.lastSettledExtent = null; // [start, end] resulting from last completed composition
    this.lastSettledSessionId = null; // sessionId of last completed composition
    
    // Surplus tracking for target expansion
    this.expansionOriginalSurplus = "";
    this.expansionOriginalSurplusAttrs = [];
    this.expansionPrefixSurplus = "";
    this.expansionPrefixSurplusAttrs = [];
    this.expansionSuffixSurplus = "";
    this.expansionSuffixSurplusAttrs = [];

    // Queued application commands (§4.5, §6)
    this.pendingCommands = []; // array of { ticketId, cmd, epoch, sessionId, initialCandidateOffset }
    this.commandOutcomes = new Map(); // ticketId -> { status, reason, range, style, ... }
    this.watchdogTriggered = false;
    this.watchdogLog = [];
    
    // History (§7)
    this.undoStack = [];
    this.redoStack = [];
    
    // Reentrancy handling
    this.isProcessingCallback = false;
    this.reentrantMutationQueue = [];
    
    // Watchdog
    this.watchdogTriggered = false;
    this.watchdogLog = [];
  }

  nextTicketId(prefix = 'ticket') {
    return `${prefix}-${++this.ticketCounter}`;
  }

  nextSessionId(prefix = 'session') {
    return `${prefix}-${++this.sessionCounter}`;
  }

  nextSettlementToken() {
    return `settle-tok-${++this.settlementSeqCounter}`;
  }
  
  openNewConnection() {
    // History Preservation Rule (Defect C & §7):
    if (this.compositionBeforeSnapshot) {
      const compBefore = this.compositionBeforeSnapshot;
      const textChanged = (this.docText !== compBefore.text);
      const attrsChanged = !areAttrsEqual(this.docAttributes, compBefore.attrs);
      if (textChanged || attrsChanged) {
        const extent = this.committedExtent || this.markedRange || [0, this.docText.length];
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: cloneAttrs(compBefore.attrs),
          beforeSelection: [...compBefore.selection],
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...extent]
        });
        this.redoStack = [];
      }
    }

    // Cancel pending commands from prior connection
    for (const item of this.pendingCommands) {
      if (!this.commandOutcomes.has(item.ticketId) || this.commandOutcomes.get(item.ticketId).status === 'Deferred') {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'StaleEpoch', ticketId: item.ticketId });
      }
    }
    this.pendingCommands = [];

    this.epoch++;
    this.connectionState = 'Active';
    this.sequenceCounter = 0;
    this.compositionState = 'Idle';
    this.activeSessionId = null;
    this.markedRange = null;
    this.baselineUnits = null;
    this.committedUnits = [];
    this.committedExtent = null;
    this.historicalCommittedUnits = [];
    this.historicalCommittedExtent = null;
    this.sessionOriginEnd = 0;
    this.compositionBeforeSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.lastSettledExtent = null;
    this.lastSettledSessionId = null;
    this.expansionPrefixSurplus = "";
    this.expansionPrefixSurplusAttrs = [];
    this.expansionSuffixSurplus = "";
    this.expansionSuffixSurplusAttrs = [];
    this.expansionOriginalSurplus = "";
    this.expansionOriginalSurplusAttrs = [];
    this.sessionRevision++;
    return this.epoch;
  }
  
  closeConnection() {
    if (this.compositionBeforeSnapshot) {
      const compBefore = this.compositionBeforeSnapshot;
      const textChanged = (this.docText !== compBefore.text);
      const attrsChanged = !areAttrsEqual(this.docAttributes, compBefore.attrs);
      if (textChanged || attrsChanged) {
        const extent = this.committedExtent || this.markedRange || [0, this.docText.length];
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: cloneAttrs(compBefore.attrs),
          beforeSelection: [...compBefore.selection],
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...extent]
        });
        this.redoStack = [];
      }
    }
    for (const item of this.pendingCommands) {
      if (!this.commandOutcomes.has(item.ticketId) || this.commandOutcomes.get(item.ticketId).status === 'Deferred') {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'ConnectionClosed', ticketId: item.ticketId });
      }
    }
    this.pendingCommands = [];
    this.connectionState = 'Closed';
    this.compositionState = 'Idle';
    this.activeSessionId = null;
    this.markedRange = null;
    this.baselineUnits = null;
    this.committedUnits = [];
    this.committedExtent = null;
    this.historicalCommittedUnits = [];
    this.historicalCommittedExtent = null;
    this.sessionOriginEnd = 0;
    this.compositionBeforeSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.sessionRevision++;
    return { epoch: this.epoch, state: this.connectionState, sessionRevision: this.sessionRevision };
  }
  
  onFocusLost(strategy = 'settle') {
    if (this.compositionState === 'Idle') {
      return { status: 'Ignored', reason: 'AlreadyIdle' };
    }
    for (const item of this.pendingCommands) {
      if (!this.commandOutcomes.has(item.ticketId) || this.commandOutcomes.get(item.ticketId).status === 'Deferred') {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'FocusLost', ticketId: item.ticketId });
      }
    }
    this.pendingCommands = [];
    
    if (strategy === 'settle') {
      return this.receiveCallback(this.epoch, { type: 'unmark', settled: true });
    } else {
      return this.receiveCallback(this.epoch, { type: 'cancel' });
    }
  }
  
  dispose() {
    if (this.compositionBeforeSnapshot) {
      const compBefore = this.compositionBeforeSnapshot;
      const textChanged = (this.docText !== compBefore.text);
      const attrsChanged = !areAttrsEqual(this.docAttributes, compBefore.attrs);
      if (textChanged || attrsChanged) {
        const extent = this.committedExtent || this.markedRange || [0, this.docText.length];
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: cloneAttrs(compBefore.attrs),
          beforeSelection: [...compBefore.selection],
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...extent]
        });
        this.redoStack = [];
      }
    }
    for (const item of this.pendingCommands) {
      if (!this.commandOutcomes.has(item.ticketId) || this.commandOutcomes.get(item.ticketId).status === 'Deferred') {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'Disposed', ticketId: item.ticketId });
      }
    }
    this.pendingCommands = [];
    this.connectionState = 'Closed';
    this.compositionState = 'Idle';
    this.activeSessionId = null;
    this.markedRange = null;
    this.baselineUnits = null;
    this.committedUnits = [];
    this.committedExtent = null;
    this.historicalCommittedUnits = [];
    this.historicalCommittedExtent = null;
    this.sessionOriginEnd = 0;
    this.compositionBeforeSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.sessionRevision++;
    return { epoch: this.epoch, state: this.connectionState, sessionRevision: this.sessionRevision };
  }

  _recordReceipt(stampKey, receipt) {
    if (this.receiptCache.size >= this.maxCacheSize) {
      const oldestKey = this.receiptCache.keys().next().value;
      this.receiptCache.delete(oldestKey);
      this.evictedReceipts.add(oldestKey);
    }
    this.receiptCache.set(stampKey, receipt);
  }

  _setSelection(sel) {
    if (!Array.isArray(sel) || sel.length !== 2) return;
    const a = Math.max(0, Math.min(this.docText.length, sel[0]));
    const f = Math.max(0, Math.min(this.docText.length, sel[1]));
    this.selection = [a, f];
  }

  _mapQueuedCommandTargets(candidateOffset, oldCandidateLen, newCandidateLen) {
    const delta = newCandidateLen - oldCandidateLen;
    if (delta === 0) return;
    for (const item of this.pendingCommands) {
      if (Array.isArray(item.cmd.target)) {
        const [tStart, tEnd] = item.cmd.target;
        if (tStart >= candidateOffset + oldCandidateLen) {
          item.cmd.target = [tStart + delta, tEnd + delta];
        }
      }
    }
  }

  receiveCallback(connectionEpoch, payload, sequenceStamp = null) {
    if (connectionEpoch < this.epoch) {
      return {
        status: 'StaleEpoch',
        epoch: connectionEpoch,
        currentEpoch: this.epoch,
        incomingEpoch: connectionEpoch,
        reason: 'Callback from superseded connection epoch rejected'
      };
    }
    if (connectionEpoch > this.epoch) {
      return {
        status: 'FutureEpoch',
        epoch: connectionEpoch,
        currentEpoch: this.epoch,
        incomingEpoch: connectionEpoch,
        reason: 'Callback from future unestablished connection epoch rejected'
      };
    }
    if (this.connectionState !== 'Active') {
      return {
        status: 'Rejected',
        reason: `Connection is ${this.connectionState}`
      };
    }
    
    const stampKey = sequenceStamp !== null ? `${connectionEpoch}:${sequenceStamp}` : null;
    if (stampKey) {
      if (this.receiptCache.has(stampKey)) {
        return {
          status: 'Duplicate',
          receipt: this.receiptCache.get(stampKey),
          cached: true
        };
      }
      if (this.evictedReceipts.has(stampKey)) {
        return {
          status: 'DuplicateReceiptExpired',
          reason: 'Duplicate sequence stamp received but receipt has been evicted from bounded cache'
        };
      }
    }
    
    // Reentrancy handling
    if (this.isProcessingCallback) {
      this.reentrantMutationQueue.push({ connectionEpoch, payload, sequenceStamp });
      return { status: 'QueuedReentrant', reason: 'Reentrant mutation queued for sequential FIFO processing' };
    }
    
    this.isProcessingCallback = true;
    
    // Capture snapshot for atomic rollback on failure
    const rollbackSnapshot = {
      docText: this.docText,
      docAttributes: cloneAttrs(this.docAttributes),
      selection: [...this.selection],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision,
      compositionState: this.compositionState,
      markedRange: this.markedRange ? [...this.markedRange] : null,
      activeSessionId: this.activeSessionId,
      attributionEpoch: this.attributionEpoch,
      committedUnits: this.committedUnits.map(u => ({ ...u })),
      committedExtent: this.committedExtent ? [...this.committedExtent] : null,
      historicalCommittedUnits: this.historicalCommittedUnits ? this.historicalCommittedUnits.map(u => ({ ...u })) : [],
      historicalCommittedExtent: this.historicalCommittedExtent ? [...this.historicalCommittedExtent] : null,
      sessionOriginEnd: this.sessionOriginEnd,
      compositionBeforeSnapshot: this.compositionBeforeSnapshot ? JSON.parse(JSON.stringify(this.compositionBeforeSnapshot)) : null,
      activeSettlementToken: this.activeSettlementToken ? { ...this.activeSettlementToken } : null,
      settlementEvidence: this.settlementEvidence ? { ...this.settlementEvidence } : null,
      undoLength: this.undoStack.length
    };
    
    try {
      this.sequenceCounter++;
      const result = this.applyCallbackPayload(payload);
      const receipt = {
        epoch: this.epoch,
        sequence: sequenceStamp !== null ? sequenceStamp : this.sequenceCounter,
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        ...result,
        result
      };
      
      if (stampKey) {
        this._recordReceipt(stampKey, receipt);
      }
      
      return {
        status: 'Accepted',
        receipt
      };
    } catch (err) {
      // Rollback to clean pre-mutation state
      this.docText = rollbackSnapshot.docText;
      this.docAttributes = cloneAttrs(rollbackSnapshot.docAttributes);
      this.selection = [...rollbackSnapshot.selection];
      this.documentRevision = rollbackSnapshot.documentRevision;
      this.sessionRevision = rollbackSnapshot.sessionRevision;
      this.compositionState = rollbackSnapshot.compositionState;
      this.markedRange = rollbackSnapshot.markedRange;
      this.activeSessionId = rollbackSnapshot.activeSessionId;
      this.attributionEpoch = rollbackSnapshot.attributionEpoch;
      this.committedUnits = rollbackSnapshot.committedUnits;
      this.committedExtent = rollbackSnapshot.committedExtent;
      this.historicalCommittedUnits = rollbackSnapshot.historicalCommittedUnits;
      this.historicalCommittedExtent = rollbackSnapshot.historicalCommittedExtent;
      this.sessionOriginEnd = rollbackSnapshot.sessionOriginEnd;
      this.compositionBeforeSnapshot = rollbackSnapshot.compositionBeforeSnapshot;
      this.activeSettlementToken = rollbackSnapshot.activeSettlementToken;
      this.settlementEvidence = rollbackSnapshot.settlementEvidence;
      this.undoStack = this.undoStack.slice(0, rollbackSnapshot.undoLength);
      
      return {
        status: 'Faulted',
        reason: err.message,
        error: err
      };
    } finally {
      this.isProcessingCallback = false;
      while (this.reentrantMutationQueue.length > 0) {
        const next = this.reentrantMutationQueue.shift();
        this.receiveCallback(next.connectionEpoch, next.payload, next.sequenceStamp);
      }
    }
  }
  
  applyCallbackPayload(payload) {
    const prevDocText = this.docText;
    const prevDocAttrs = cloneAttrs(this.docAttributes);
    
    // -----------------------------------------------------------------------
    // startComposition (§4.2, §4.4)
    // -----------------------------------------------------------------------
    if (payload.type === 'startComposition') {
      const start = Math.max(0, Math.min(this.docText.length, payload.start ?? this.selection[0]));
      const end = Math.max(start, Math.min(this.docText.length, payload.end ?? this.selection[1]));
      
      this.compositionState = 'Composing';
      this.activeSessionId = payload.sessionId || this.nextSessionId('session');
      this.sessionOriginStart = start;
      this.sessionOriginEnd = end;
      this.markedRange = [start, end];
      this.attributionEpoch = 1;
      this.committedUnits = [];
      this.committedExtent = null;
      this.historicalCommittedUnits = [];
      this.historicalCommittedExtent = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      
      this.compositionBeforeSnapshot = {
        text: this.docText,
        attrs: cloneAttrs(this.docAttributes),
        selection: [...this.selection],
        originStart: start,
        originEnd: end
      };
      
      this.baselineUnits = [];
      for (let i = start; i < end; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      this.seedAttributes = payload.seedAttributes 
        ? { ...payload.seedAttributes } 
        : ((start < this.docAttributes.length) ? { ...this.docAttributes[start] } : {});
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      } else {
        this._setSelection([start, end]);
      }
      this.sessionRevision++;
      
      return {
        type: 'CompositionStarted',
        sessionId: this.activeSessionId,
        markedRange: [...this.markedRange],
        selection: [...this.selection],
        sessionRevision: this.sessionRevision
      };
    }
    
    // -----------------------------------------------------------------------
    // preedit (§4.3, §4.4)
    // -----------------------------------------------------------------------
    if (payload.type === 'preedit') {
      // Resumption check after nonterminal unmark
      if (this.markedRange === null) {
        const insertPos = this.selection[1];
        if (!this.activeSessionId) {
          this.activeSessionId = payload.sessionId || this.nextSessionId('session');
          this.compositionBeforeSnapshot = {
            text: this.docText,
            attrs: cloneAttrs(this.docAttributes),
            selection: [...this.selection],
            originStart: insertPos,
            originEnd: insertPos
          };
          this.sessionOriginStart = insertPos;
          this.sessionOriginEnd = insertPos;
        }
        this.markedRange = [insertPos, insertPos];
        this.baselineUnits = [];
        this.seedAttributes = (insertPos < this.docAttributes.length) ? { ...this.docAttributes[insertPos] } : {};
      }
      
      // Invalidate any pending settlement token because new input arrived! (§5)
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.compositionState = 'Composing';
      
      const candidateText = payload.text || "";
      const [start, end] = this.markedRange;
      const oldCandidateLen = end - start;
      const newCandidateLen = candidateText.length;
      
      const candidateUnits = reconcileCandidateAttributes(
        this.baselineUnits,
        candidateText,
        this.seedAttributes
      );
      
      const newText = this.docText.slice(0, start) + candidateText + this.docText.slice(end);
      const newAttrs = [
        ...this.docAttributes.slice(0, start),
        ...candidateUnits.map(u => ({ ...u.attributes })),
        ...this.docAttributes.slice(end)
      ];
      
      const newMarkedEnd = start + candidateText.length;
      
      // Map queued range targets across candidate text length changes (§6)
      this._mapQueuedCommandTargets(start, oldCandidateLen, newCandidateLen);
      
      // DocumentRevision advances iff document semantics changed (§2)
      const textChanged = (newText !== prevDocText);
      const attrsChanged = !areAttrsEqual(newAttrs, prevDocAttrs);
      if (textChanged || attrsChanged) {
        this.documentRevision++;
      }
      this.sessionRevision++;
      
      this.docText = newText;
      this.docAttributes = newAttrs;
      this.markedRange = [start, newMarkedEnd];
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      } else {
        this._setSelection([newMarkedEnd, newMarkedEnd]);
      }
      
      return {
        type: 'PreeditUpdated',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        markedRange: [...this.markedRange],
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // partialCommit (§4.3, R03)
    // -----------------------------------------------------------------------
    if (payload.type === 'partialCommit') {
      const commitLen = payload.committedLength || 0;
      if (!this.markedRange) {
        return { status: 'Rejected', reason: 'NoActiveMarkedRange' };
      }
      const [start, end] = this.markedRange;
      const currentCandidateText = this.docText.slice(start, end);
      
      if (commitLen > currentCandidateText.length) {
        return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate', candidateLength: currentCandidateText.length, requested: commitLen };
      }
      
      // Invalidate any pending settlement token because candidate state changed! (§5)
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.compositionState = 'Composing';
      
      if (commitLen > 0) {
        const currentCandidateUnits = reconcileCandidateAttributes(
          this.baselineUnits,
          currentCandidateText,
          this.seedAttributes
        );
        
        const newlyCommittedPortion = currentCandidateUnits.slice(0, commitLen);
        this.committedUnits.push(...newlyCommittedPortion);
        
        if (!this.committedExtent) {
          this.committedExtent = [start, start + commitLen];
        } else {
          this.committedExtent = [this.committedExtent[0], start + commitLen];
        }
        this.historicalCommittedUnits = this.committedUnits.map(u => ({ ...u }));
        this.historicalCommittedExtent = [...this.committedExtent];
        
        const remainingUnits = currentCandidateUnits.slice(commitLen);
        this.attributionEpoch++;
        this.baselineUnits = remainingUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
        
        const newMarkedStart = start + commitLen;
        this.markedRange = [newMarkedStart, end];
        
        // Metadata-only freeze does NOT advance DocumentRevision (§2)!
        // Text and attributes are identical before and after partialCommit.
      }
      this.sessionRevision++;
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      }
      
      return {
        type: 'PartialCommitted',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        attributionEpoch: this.attributionEpoch,
        frozenCount: this.committedUnits.length,
        markedRange: [...this.markedRange],
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // targetExpansion (§4.4, §6.1, I08, §1)
    // -----------------------------------------------------------------------
    if (payload.type === 'targetExpansion') {
      const reqStart = payload.newStart;
      const reqEnd = payload.newEnd;
      
      if (reqStart === undefined || reqEnd === undefined || reqStart < 0 || reqEnd > this.docText.length || reqStart > reqEnd) {
        return { status: 'Rejected', reason: 'InvalidTargetExpansionRange', range: [reqStart, reqEnd] };
      }
      
      // Invalidate any pending settlement token because candidate envelope changed! (§5)
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.compositionState = 'Composing';
      
      const newStart = reqStart;
      const newEnd = reqEnd;
      const oldMarked = this.markedRange || [newStart, newEnd];

      // Exact Native-Range Fidelity:
      // We NEVER silently clamp newStart to committedExtent[1]!
      // If expansion encompasses previously committed units:
      if (this.committedExtent) {
        if (newStart <= this.committedExtent[0]) {
          // Entire committed range is encompassed in the new candidate envelope
          this.committedUnits = [];
          this.committedExtent = null;
          this.historicalCommittedUnits = [];
          this.historicalCommittedExtent = null;
        } else if (newStart < this.committedExtent[1]) {
          // Partial overlap: prefix before newStart remains committed; suffix is re-opened into candidate
          const retainedCount = newStart - this.committedExtent[0];
          this.committedUnits = this.committedUnits.slice(0, retainedCount);
          this.committedExtent = [this.committedExtent[0], newStart];
          this.historicalCommittedUnits = this.committedUnits.map(u => ({ ...u }));
          this.historicalCommittedExtent = [...this.committedExtent];
        }
      }
      
      this.attributionEpoch++;
      
      // Surplus tracking for characters swallowed from surrounding text
      if (newStart < oldMarked[0]) {
        const pSurplus = this.docText.slice(newStart, oldMarked[0]);
        const pAttrs = cloneAttrs(this.docAttributes.slice(newStart, oldMarked[0]));
        this.expansionPrefixSurplus = pSurplus + this.expansionPrefixSurplus;
        this.expansionPrefixSurplusAttrs = [...pAttrs, ...this.expansionPrefixSurplusAttrs];
      }
      if (newEnd > oldMarked[1]) {
        const sSurplus = this.docText.slice(oldMarked[1], newEnd);
        const sAttrs = cloneAttrs(this.docAttributes.slice(oldMarked[1], newEnd));
        this.expansionSuffixSurplus = this.expansionSuffixSurplus + sSurplus;
        this.expansionSuffixSurplusAttrs = [...this.expansionSuffixSurplusAttrs, ...sAttrs];
        this.expansionOriginalSurplus = this.expansionSuffixSurplus;
        this.expansionOriginalSurplusAttrs = this.expansionSuffixSurplusAttrs;
      }
      
      this.markedRange = [newStart, newEnd];
      
      // Capture new baseline units from live document slice at requested coordinates
      this.baselineUnits = [];
      for (let i = newStart; i < newEnd; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      }
      this.sessionRevision++;
      
      return {
        type: 'TargetExpanded',
        attributionEpoch: this.attributionEpoch,
        sessionRevision: this.sessionRevision,
        markedRange: [...this.markedRange],
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // commit: Final text commitment (§4.4)
    // -----------------------------------------------------------------------
    if (payload.type === 'commit') {
      const [start, end] = this.markedRange 
        ? this.markedRange 
        : [this.selection[0], this.selection[1]];
      const commitText = payload.text !== undefined ? payload.text : this.docText.slice(start, end);
      
      const compBefore = this.compositionBeforeSnapshot || {
        text: this.docText,
        attrs: cloneAttrs(this.docAttributes),
        selection: [...this.selection],
        originStart: start
      };
      
      const newText = this.docText.slice(0, start) + commitText + this.docText.slice(end);
      const seedAttr = (start < this.docAttributes.length) ? { ...this.docAttributes[start] } : {};
      const newAttrs = [
        ...this.docAttributes.slice(0, start),
        ...new Array(commitText.length).fill(null).map(() => ({ ...seedAttr })),
        ...this.docAttributes.slice(end)
      ];
      
      const mStart = start;
      const mEnd = start + commitText.length;
      const cStart = this.committedExtent ? this.committedExtent[0] : mStart;
      const cEnd = this.committedExtent ? this.committedExtent[1] : mEnd;
      const finalCommittedExtent = [Math.min(mStart, cStart), Math.max(mEnd, cEnd)];
      
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = this.activeSessionId || payload.sessionId || null;
      
      this.docText = newText;
      this.docAttributes = newAttrs;
      if (payload.selection) {
        this._setSelection(payload.selection);
      } else {
        this._setSelection([finalCommittedExtent[1], finalCommittedExtent[1]]);
      }
      const afterSel = [...this.selection];
      
      const docTextChanged = (newText !== prevDocText);
      const docAttrsChanged = !areAttrsEqual(newAttrs, prevDocAttrs);
      if (docTextChanged || docAttrsChanged) {
        this.documentRevision++;
      }

      const netCompositionChanged = (newText !== compBefore.text) || !areAttrsEqual(newAttrs, compBefore.attrs);
      if (netCompositionChanged) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: cloneAttrs(compBefore.attrs),
          beforeSelection: [...compBefore.selection],
          afterText: newText,
          afterAttrs: cloneAttrs(newAttrs),
          afterSelection: afterSel,
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
      }
      this.sessionRevision++;
      
      this.compositionBeforeSnapshot = null;
      this.markedRange = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.baselineUnits = null;
      this.activeSessionId = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      
      this.compositionState = 'Idle';
      this.drainPendingCommands(finalCommittedExtent);
      
      return {
        type: 'Committed',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // unmark (§4.4, Defect A)
    // -----------------------------------------------------------------------
    if (payload.type === 'unmark') {
      if (this.markedRange === null) {
        return {
          status: 'Rejected',
          reason: 'AlreadyUnmarked',
          message: 'Composition candidate already unmarked; settlement completion requires completeSettlement event'
        };
      }
      
      const [start, end] = this.markedRange;
      const finalCommittedExtent = this.committedExtent 
        ? [Math.min(this.committedExtent[0], start), Math.max(this.committedExtent[1], end)]
        : [start, end];
        
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = this.activeSessionId;
      
      // Capture concrete settlement evidence
      this.settlementEvidence = {
        sessionId: this.activeSessionId,
        extent: [...finalCommittedExtent],
        epoch: this.epoch,
        token: this.activeSettlementToken ? this.activeSettlementToken.token : null
      };
      
      this.markedRange = null;
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      }
      this.sessionRevision++;
      
      if (payload.settled !== false) {
        // Terminal unmark
        const compBefore = this.compositionBeforeSnapshot;
        if (compBefore) {
          const textChanged = (this.docText !== compBefore.text);
          const attrsChanged = !areAttrsEqual(this.docAttributes, compBefore.attrs);
          if (textChanged || attrsChanged) {
            this.undoStack.push({
              group: 'Composition',
              beforeText: compBefore.text,
              beforeAttrs: cloneAttrs(compBefore.attrs),
              beforeSelection: [...compBefore.selection],
              afterText: this.docText,
              afterAttrs: cloneAttrs(this.docAttributes),
              afterSelection: [...this.selection],
              sessionExtent: [...finalCommittedExtent]
            });
            this.redoStack = [];
          }
        }
        
        this.compositionBeforeSnapshot = null;
        this.committedUnits = [];
        this.committedExtent = null;
        this.baselineUnits = null;
        this.activeSessionId = null;
        this.activeSettlementToken = null;
        this.settlementEvidence = null;
        this.expansionOriginalSurplus = "";
        this.expansionOriginalSurplusAttrs = [];
        this.expansionPrefixSurplus = "";
        this.expansionPrefixSurplusAttrs = [];
        this.expansionSuffixSurplus = "";
        this.expansionSuffixSurplusAttrs = [];
        this.compositionState = 'Idle';
        
        this.drainPendingCommands(finalCommittedExtent);
        
        return {
          type: 'Unmarked',
          settled: true,
          documentRevision: this.documentRevision,
          sessionRevision: this.sessionRevision,
          finalExtent: finalCommittedExtent,
          selection: [...this.selection]
        };
      } else {
        // Nonterminal unmark
        this.compositionState = 'AwaitingSettlement';
        return {
          type: 'Unmarked',
          settled: false,
          documentRevision: this.documentRevision,
          sessionRevision: this.sessionRevision,
          finalExtent: finalCommittedExtent,
          selection: [...this.selection]
        };
      }
    }
    
    // -----------------------------------------------------------------------
    // completeSettlement (§4.4, §5)
    // -----------------------------------------------------------------------
    if (payload.type === 'completeSettlement') {
      if (this.compositionState === 'Idle') {
        return { status: 'Rejected', reason: 'AlreadySettled' };
      }
      
      // Connection epoch validation
      if (payload.epoch !== undefined && payload.epoch !== this.epoch) {
        return { status: 'Rejected', reason: 'StaleEpoch', expectedEpoch: this.epoch, providedEpoch: payload.epoch };
      }
      
      // Session ID validation
      if (payload.sessionId && this.activeSessionId && payload.sessionId !== this.activeSessionId) {
        return {
          status: 'Rejected',
          reason: 'SessionMismatch',
          expectedSessionId: this.activeSessionId,
          providedSessionId: payload.sessionId
        };
      }
      
      // Candidate still active check (§5)
      if (this.markedRange !== null) {
        return {
          status: 'Rejected',
          reason: 'CandidateStillActive',
          message: 'Cannot complete settlement while marked candidate range is still active without unmark'
        };
      }
      
      // Settlement token validation (§5)
      if (payload.token) {
        const expectedToken = this.settlementEvidence ? this.settlementEvidence.token : (this.activeSettlementToken ? this.activeSettlementToken.token : null);
        if (expectedToken && payload.token !== expectedToken) {
          return {
            status: 'Rejected',
            reason: 'ObsoleteSettlementToken',
            expectedToken,
            providedToken: payload.token
          };
        }
      }
      
      // Extent evidence check - NEVER resolve to [0, 0]! (§5)
      if (!this.settlementEvidence || !this.settlementEvidence.extent) {
        return {
          status: 'Rejected',
          reason: 'UnknownTargetExtent',
          message: 'Cannot finalize settlement without valid tracked settlement evidence'
        };
      }
      
      const compBefore = this.compositionBeforeSnapshot;
      const finalCommittedExtent = [...this.settlementEvidence.extent];
      
      // Finalize deferred composition history group
      if (compBefore) {
        const textChanged = (this.docText !== compBefore.text);
        const attrsChanged = !areAttrsEqual(this.docAttributes, compBefore.attrs);
        if (textChanged || attrsChanged) {
          this.undoStack.push({
            group: 'Composition',
            beforeText: compBefore.text,
            beforeAttrs: cloneAttrs(compBefore.attrs),
            beforeSelection: [...compBefore.selection],
            afterText: this.docText,
            afterAttrs: cloneAttrs(this.docAttributes),
            afterSelection: [...this.selection],
            sessionExtent: [...finalCommittedExtent]
          });
          this.redoStack = [];
        }
      }
      
      this.compositionBeforeSnapshot = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.baselineUnits = null;
      this.markedRange = null;
      this.activeSessionId = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      this.compositionState = 'Idle';
      this.sessionRevision++;
      
      this.drainPendingCommands(finalCommittedExtent);
      
      return {
        type: 'SettlementCompleted',
        sessionRevision: this.sessionRevision,
        documentRevision: this.documentRevision,
        finalExtent: finalCommittedExtent,
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // Distinct Cancellation Semantics (§6):
    // 1. nativeRemoval (explicit native range and replacement text)
    // 2. endCompositionPreservingText (accepts live text and finishes)
    // 3. cancel / applicationAbort (application-level abort / restore)
    // -----------------------------------------------------------------------
    if (payload.type === 'nativeRemoval') {
      const rStart = payload.range ? payload.range[0] : (this.markedRange ? this.markedRange[0] : 0);
      const rEnd = payload.range ? payload.range[1] : (this.markedRange ? this.markedRange[1] : 0);
      const replacementText = payload.replacementText || "";
      
      const newText = this.docText.slice(0, rStart) + replacementText + this.docText.slice(rEnd);
      const seedAttr = (rStart < this.docAttributes.length) ? { ...this.docAttributes[rStart] } : {};
      const newAttrs = [
        ...this.docAttributes.slice(0, rStart),
        ...new Array(replacementText.length).fill(null).map(() => ({ ...seedAttr })),
        ...this.docAttributes.slice(rEnd)
      ];
      
      const textChanged = (newText !== prevDocText);
      const attrsChanged = !areAttrsEqual(newAttrs, prevDocAttrs);
      if (textChanged || attrsChanged) {
        this.documentRevision++;
      }
      this.sessionRevision++;
      this.docText = newText;
      this.docAttributes = newAttrs;
      this.markedRange = null;
      this.compositionState = 'Idle';
      this.activeSessionId = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      
      return {
        type: 'NativeRemovalApplied',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText
      };
    }

    if (payload.type === 'endCompositionPreservingText') {
      return this.applyCallbackPayload({ type: 'unmark', settled: true, selection: payload.selection });
    }
    
    // Application-level cancel / abort
    if (payload.type === 'cancel' || payload.type === 'applicationAbort') {
      let finalCommittedExtent = null;
      const hasCommitted = (this.historicalCommittedUnits && this.historicalCommittedUnits.length > 0)
        || (this.committedUnits && this.committedUnits.length > 0);
        
      if (!hasCommitted) {
        // Simple cancellation with zero partial commits: restore snapshot verbatim
        if (this.compositionBeforeSnapshot) {
          const compBefore = this.compositionBeforeSnapshot;
          const textChanged = (compBefore.text !== this.docText);
          const attrsChanged = !areAttrsEqual(compBefore.attrs, this.docAttributes);
          if (textChanged || attrsChanged) {
            this.documentRevision++;
          }
          this.docText = compBefore.text;
          this.docAttributes = cloneAttrs(compBefore.attrs);
          this._setSelection(compBefore.selection);
        }
      } else {
        // Invariant I08 Overlap Protection:
        // Frozen committed units remain in document!
        // Reconstructed cleanly from pre-composition snapshot + frozen committed units without coordinate drift.
        const compBefore = this.compositionBeforeSnapshot;
        const originStart = compBefore ? (compBefore.originStart ?? this.sessionOriginStart) : this.sessionOriginStart;
        const originEnd = (compBefore && compBefore.originEnd !== undefined) ? compBefore.originEnd : (this.sessionOriginEnd || originStart);
        const committedUnits = (this.historicalCommittedUnits && this.historicalCommittedUnits.length > 0)
          ? this.historicalCommittedUnits
          : this.committedUnits;
          
        const committedText = committedUnits.map(u => u.char).join('');
        const committedAttrs = committedUnits.map(u => ({ ...u.attributes }));
        
        let restoredText, restoredAttrs;
        if (compBefore) {
          const leftText = compBefore.text.slice(0, originStart);
          const leftAttrs = compBefore.attrs.slice(0, originStart);
          const rightText = compBefore.text.slice(originEnd);
          const rightAttrs = compBefore.attrs.slice(originEnd);
          
          restoredText = leftText + committedText + rightText;
          restoredAttrs = [...cloneAttrs(leftAttrs), ...committedAttrs, ...cloneAttrs(rightAttrs)];
          finalCommittedExtent = [originStart, originStart + committedText.length];
        } else {
          const fallbackPos = this.committedExtent ? this.committedExtent[1] : this.sessionOriginStart;
          const [markedStart, markedEnd] = this.markedRange || [fallbackPos, fallbackPos];
          restoredText = this.docText.slice(0, markedStart) + this.docText.slice(markedEnd);
          restoredAttrs = [...this.docAttributes.slice(0, markedStart), ...this.docAttributes.slice(markedEnd)];
          finalCommittedExtent = this.committedExtent ? [...this.committedExtent] : [markedStart, markedStart];
        }
        
        const textChanged = (restoredText !== this.docText);
        const attrsChanged = !areAttrsEqual(restoredAttrs, this.docAttributes);
        if (textChanged || attrsChanged) {
          this.documentRevision++;
        }
        
        this.docText = restoredText;
        this.docAttributes = restoredAttrs;
        finalCommittedExtent = finalCommittedExtent || (this.committedExtent ? [...this.committedExtent] : null);
        const selPos = finalCommittedExtent ? finalCommittedExtent[1] : this.docText.length;
        this._setSelection([selPos, selPos]);
        
        if (compBefore && finalCommittedExtent) {
          this.undoStack.push({
            group: 'Composition',
            beforeText: compBefore.text,
            beforeAttrs: cloneAttrs(compBefore.attrs),
            beforeSelection: [...compBefore.selection],
            afterText: this.docText,
            afterAttrs: cloneAttrs(this.docAttributes),
            afterSelection: [...this.selection],
            sessionExtent: [...finalCommittedExtent]
          });
          this.redoStack = [];
        }
      }
      
      this.compositionState = 'Idle';
      this.markedRange = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.historicalCommittedUnits = [];
      this.historicalCommittedExtent = null;
      this.baselineUnits = null;
      this.activeSessionId = null;
      this.activeSettlementToken = null;
      this.settlementEvidence = null;
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      this.compositionBeforeSnapshot = null;
      this.sessionRevision++;
      
      // Cancel pending commands targeting composition; drain unrelated commands
      const compCommands = [];
      const unrelatedCommands = [];
      for (const item of this.pendingCommands) {
        if (item.cmd.target === 'composing' || item.cmd.target?.type === 'CompositionTarget') {
          item.cancelled = true;
          item.cancelReason = 'CompositionCancelled';
          this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled', ticketId: item.ticketId });
          compCommands.push(item);
        } else {
          unrelatedCommands.push(item);
        }
      }
      this.pendingCommands = [];
      
      const cancelDelta = this.docText.length - prevDocText.length;
      for (const item of unrelatedCommands) {
        if (item.epoch < this.epoch) {
          this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'StaleEpoch', ticketId: item.ticketId });
          continue;
        }
        if (cancelDelta !== 0 && Array.isArray(item.cmd.target)) {
          const [s, e] = item.cmd.target;
          item.cmd.target = [Math.max(0, s + cancelDelta), Math.max(0, e + cancelDelta)];
        }
        const outcome = this.executeFormatCommand(item.cmd);
        this.commandOutcomes.set(item.ticketId, outcome);
      }
      
      return {
        type: 'Cancelled',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        selection: [...this.selection],
        state: this.compositionState
      };
    }
    
    return { type: 'NoChange' };
  }
  
  dispatchAppCommand(cmd) {
    const ticketId = cmd.ticketId || this.nextTicketId('ticket');
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      this.pendingCommands.push({
        ticketId,
        cmd: { ...cmd, target: Array.isArray(cmd.target) ? [...cmd.target] : cmd.target },
        epoch: this.epoch,
        sessionId: this.activeSessionId
      });
      this.commandOutcomes.set(ticketId, { status: 'Deferred', ticketId });
      this.compositionState = 'AwaitingSettlement';
      return { status: 'Deferred', ticketId };
    }
    
    const outcome = this.executeFormatCommand(cmd);
    outcome.ticketId = ticketId;
    this.commandOutcomes.set(ticketId, outcome);
    return outcome;
  }
  
  drainPendingCommands(resolvedCompositionExtent = null) {
    while (this.pendingCommands.length > 0) {
      const item = this.pendingCommands.shift();
      if (item.cancelled) continue;
      if (item.epoch < this.epoch) {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'StaleEpoch', ticketId: item.ticketId });
        continue;
      }
      const outcome = this.executeFormatCommand(item.cmd, resolvedCompositionExtent);
      this.commandOutcomes.set(item.ticketId, outcome);
    }
  }

  requestSettlement(reason = 'UserCommand') {
    if (this.compositionState === 'Idle') {
      return { status: 'AlreadyIdle' };
    }
    const token = this.nextSettlementToken();
    this.activeSettlementToken = {
      token,
      sessionId: this.activeSessionId,
      epoch: this.epoch,
      seq: this.settlementSeqCounter
    };
    this.compositionState = 'AwaitingSettlement';
    return {
      status: 'Requested',
      token,
      ticketId: token,
      reason,
      sessionId: this.activeSessionId,
      epoch: this.epoch
    };
  }

  cancelCommand(ticketId) {
    const idx = this.pendingCommands.findIndex(c => c.ticketId === ticketId);
    if (idx !== -1) {
      const item = this.pendingCommands[idx];
      this.pendingCommands.splice(idx, 1);
      item.cancelled = true;
      item.cancelReason = 'CancelledByUser';
      this.commandOutcomes.set(ticketId, { status: 'Cancelled', reason: 'CancelledByUser', ticketId });
      return { status: 'Cancelled', ticketId };
    }
    return { status: 'NotFound', ticketId };
  }

  executeFormatCommand(cmd, resolvedCompositionExtent = null) {
    let targetRange = null;
    
    if (cmd.target === 'composing' || cmd.target?.type === 'CompositionTarget') {
      if (resolvedCompositionExtent) {
        targetRange = resolvedCompositionExtent;
      } else if (this.lastSettledExtent) {
        targetRange = this.lastSettledExtent;
      } else {
        return { status: 'NoTarget', reason: 'No resolved composition extent available for format target' };
      }
    } else if (Array.isArray(cmd.target)) {
      targetRange = cmd.target;
    } else {
      targetRange = [this.selection[0], this.selection[1]];
    }
    
    const [start, end] = targetRange;
    if (start === end) {
      this.typingIntent = { ...this.typingIntent, ...(cmd.style || {}) };
      this.sessionRevision++;
      return { status: 'TypingIntentUpdated', typingIntent: this.typingIntent, sessionRevision: this.sessionRevision };
    }
    
    const validStart = Math.max(0, Math.min(this.docText.length, start));
    const validEnd = Math.max(validStart, Math.min(this.docText.length, end));
    
    const beforeAttrs = cloneAttrs(this.docAttributes);
    let changed = false;
    for (let i = validStart; i < validEnd; i++) {
      for (const [k, v] of Object.entries(cmd.style || {})) {
        if (this.docAttributes[i][k] !== v) {
          this.docAttributes[i][k] = v;
          changed = true;
        }
      }
    }
    
    if (!changed) {
      this.sessionRevision++;
      return { status: 'NoChange', reason: 'NoOp', range: [validStart, validEnd] };
    }
    
    this.documentRevision++;
    this.sessionRevision++;
    
    this.undoStack.push({
      group: 'Formatting',
      range: [validStart, validEnd],
      style: cmd.style,
      beforeText: this.docText,
      afterText: this.docText,
      beforeAttrs: beforeAttrs,
      afterAttrs: cloneAttrs(this.docAttributes),
      beforeSelection: [...this.selection],
      afterSelection: [...this.selection]
    });
    this.redoStack = [];
    
    return {
      status: 'Applied',
      range: [validStart, validEnd],
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision
    };
  }
  
  triggerWatchdog() {
    if (this.compositionState === 'AwaitingSettlement') {
      this.watchdogTriggered = true;
      this.watchdogLog.push({
        time: Date.now(),
        message: "Watchdog: 2s elapsed waiting for settlement. Diagnostics updated. NO synthetic commit executed."
      });
      return { status: 'DiagnosticsUpdated', pendingCount: this.pendingCommands.length };
    }
    return { status: 'NotAwaiting' };
  }
  
  undo() {
    if (this.undoStack.length === 0) return false;
    const entry = this.undoStack.pop();
    this.redoStack.push({
      group: entry.group,
      range: entry.range,
      style: entry.style,
      beforeText: entry.afterText,
      afterText: entry.beforeText,
      beforeAttrs: cloneAttrs(entry.afterAttrs),
      afterAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: [...entry.afterSelection],
      afterSelection: [...entry.beforeSelection]
    });
    
    this.docText = entry.beforeText;
    this.docAttributes = cloneAttrs(entry.beforeAttrs);
    this._setSelection(entry.beforeSelection);
    this.documentRevision++;
    this.sessionRevision++;
    return true;
  }
  
  redo() {
    if (this.redoStack.length === 0) return false;
    const entry = this.redoStack.pop();
    this.undoStack.push({
      group: entry.group,
      range: entry.range,
      style: entry.style,
      beforeText: entry.afterText,
      afterText: entry.beforeText,
      beforeAttrs: cloneAttrs(entry.afterAttrs),
      afterAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: [...entry.afterSelection],
      afterSelection: [...entry.beforeSelection]
    });
    
    this.docText = entry.beforeText;
    this.docAttributes = cloneAttrs(entry.beforeAttrs);
    this._setSelection(entry.beforeSelection);
    this.documentRevision++;
    this.sessionRevision++;
    return true;
  }
}
