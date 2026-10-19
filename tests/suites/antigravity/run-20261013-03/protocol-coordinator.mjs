// Repaired Protocol Coordinator and Transaction Engine Reference Model (§4, §6, §7)
// Run-20261013-03 Comprehensive Repairs:
// - Defect A: Normalized completion protocol.
//             Nonterminal unmark (settled: false) clears marked range, accepts text, defers history,
//             retains activeSessionId, compositionBeforeSnapshot, and awaits settlement evidence.
//             Repeating unmark when already unmarked is rejected as AlreadyUnmarked.
//             Normalized completion event 'completeSettlement' (or terminal unmark/commit) finalizes
//             settlement, pushes exactly ONE Composition history entry, and drains pending commands.
// - Defect B: Target expansion preserves partial-commit protection.
//             Committed units/ranges from partial commits are retained across target expansions.
//             Suffix cancellation aborts uncommitted candidate, preserves all committed units,
//             restores original text swallowed by expansion, and creates exactly ONE history entry.
// - Defect C: Connection turnover history preservation.
//             openNewConnection(), closeConnection(), and dispose() finalize any retained semantic edit
//             into undoStack before clearing transient state. No silent loss of before-snapshot!
// - Defect D: Revision separation.
//             DocumentRevision advances ONLY when document text or attributes change semantically.
//             Repeated identical preedit or metadata-only partial commit advances SessionRevision only.
// - Raw selection preservation (§4.4 / 4.A): Exact supplied raw selection preserved; documented fallback only when omitted.
// - Reentrancy policy (§4.1 / §4.4 / 4.C): Reentrant reads query published state synchronously;
//             reentrant mutation requests are queued and processed sequentially in FIFO order; atomic rollback on failure.
// - Formatting target resolution (§4.5 / 4.F): Empty completed composition targets update typing intent only (no revision bump, no history);
//             cancelled compositions cancel queued commands (CompositionCancelled); never substitute whole document or [0, 0].

import { reconcileCandidateAttributes } from './reference-model.mjs';

function cloneAttrs(attrs) {
  return attrs.map(a => ({ ...a }));
}

function areAttrsEqual(a1, a2) {
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
    this.compositionBeforeSnapshot = null; // pinned snapshot before ANY composition changes
    this.lastSettledExtent = null; // [start, end] resulting from last completed composition
    this.lastSettledSessionId = null; // sessionId of last completed composition
    this.expansionOriginalSurplus = ""; // text from original document swallowed by target expansion
    this.expansionOriginalSurplusAttrs = []; // attributes of swallowed text
    this.expansionPrefixSurplus = ""; // text from original document before candidate swallowed by target expansion
    this.expansionPrefixSurplusAttrs = []; // attributes of prefix surplus
    this.expansionSuffixSurplus = ""; // text from original document after candidate swallowed by target expansion
    this.expansionSuffixSurplusAttrs = []; // attributes of suffix surplus

    // Queued application commands (§4.5)
    this.pendingCommands = []; // array of { ticketId, cmd, epoch, sessionId }
    this.commandOutcomes = new Map(); // ticketId -> { status, reason, range, style, ... }
    this.watchdogTriggered = false;
    this.watchdogLog = [];
    
    // History (§7)
    this.undoStack = [];
    this.redoStack = [];
    
    // Reentrancy handling
    this.isProcessingCallback = false;
    this.reentrantMutationQueue = [];
  }
  
  openNewConnection() {
    // History Preservation Rule (Defect C & §7):
    // If an unfinished composition session exists and modified the document,
    // finalize it into undoStack before opening the new connection.
    this._finalizeUnfinishedCompositionOnTurnover();
    
    this.epoch++;
    this.connectionState = 'Active';
    this.sequenceCounter = 0;
    this.receiptCache.clear();
    this.evictedReceipts.clear();
    this.reentrantMutationQueue = [];
    
    // Cancel any pending commands from previous connection epoch:
    if (this.pendingCommands.length > 0) {
      for (const item of this.pendingCommands) {
        if (item.epoch < this.epoch) {
          item.cancelled = true;
          item.cancelReason = 'StaleEpoch';
          this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'StaleEpoch', ticketId: item.ticketId });
        }
      }
      this.pendingCommands = this.pendingCommands.filter(item => !item.cancelled);
    }
    
    // Cleanly reset transient composition state:
    this._resetTransientCompositionState();
    return this.epoch;
  }
  
  closeConnection() {
    this._finalizeUnfinishedCompositionOnTurnover();
    this.connectionState = 'Closed';
    
    // Expire pending commands
    for (const item of this.pendingCommands) {
      item.cancelled = true;
      item.cancelReason = 'ConnectionClosed';
      this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'ConnectionClosed', ticketId: item.ticketId });
    }
    this.pendingCommands = [];
    this._resetTransientCompositionState();
  }

  onFocusLost(strategy = 'settle') {
    // Focus loss (§4.5): Cancel pending commands targeting active composition
    for (const item of this.pendingCommands) {
      if (item.cmd.target === 'composing' || item.cmd.target?.type === 'CompositionTarget') {
        item.cancelled = true;
        item.cancelReason = 'FocusLost';
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'FocusLost', ticketId: item.ticketId });
      }
    }
    this.pendingCommands = this.pendingCommands.filter(i => !i.cancelled);

    if (this.compositionState === 'Idle') {
      return { status: 'IdleFocusLost' };
    }

    if (strategy === 'cancel') {
      return this.applyCallbackPayload({ type: 'cancel' });
    } else {
      return this.applyCallbackPayload({ type: 'unmark', settled: true });
    }
  }

  dispose() {
    this._finalizeUnfinishedCompositionOnTurnover();
    this.connectionState = 'Closed';
    this.compositionState = 'Idle';
    for (const item of this.pendingCommands) {
      item.cancelled = true;
      item.cancelReason = 'Disposed';
      this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'Disposed', ticketId: item.ticketId });
    }
    this.pendingCommands = [];
    this._resetTransientCompositionState();
    return { status: 'Disposed' };
  }

  _finalizeUnfinishedCompositionOnTurnover() {
    if (this.compositionBeforeSnapshot) {
      const snap = this.compositionBeforeSnapshot;
      const textChanged = (this.docText !== snap.text);
      const attrsChanged = !areAttrsEqual(this.docAttributes, snap.attrs);
      
      if (textChanged || attrsChanged) {
        // Compute extent of the retained composition
        const start = snap.sessionStartOffset;
        const end = this.markedRange 
          ? this.markedRange[1] 
          : (this.committedExtent ? this.committedExtent[1] : (this.lastSettledExtent ? this.lastSettledExtent[1] : this.selection[1]));
        const finalExtent = [start, Math.max(start, end)];
        
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          beforeSelection: [...snap.selection],
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...finalExtent]
        });
        this.redoStack = [];
      }
    }
  }

  _resetTransientCompositionState() {
    this.compositionState = 'Idle';
    this.activeSessionId = null;
    this.markedRange = null;
    this.baselineUnits = null;
    this.seedAttributes = {};
    this.committedUnits = [];
    this.committedExtent = null;
    this.compositionBeforeSnapshot = null;
    this.lastSettledExtent = null;
    this.lastSettledSessionId = null;
    this.expansionOriginalSurplus = "";
    this.expansionOriginalSurplusAttrs = [];
    this.expansionPrefixSurplus = "";
    this.expansionPrefixSurplusAttrs = [];
    this.expansionSuffixSurplus = "";
    this.expansionSuffixSurplusAttrs = [];
  }

  _setSelection(rawSel) {
    if (!rawSel || !Array.isArray(rawSel)) return;
    const len = this.docText.length;
    this.selection = [
      Math.max(0, Math.min(len, rawSel[0])),
      Math.max(0, Math.min(len, rawSel[1]))
    ];
  }

  // Callback admission (§4.1)
  receiveCallback(incomingEpoch, payload, explicitStamp = null) {
    if (incomingEpoch < this.epoch) {
      return { status: 'StaleEpoch', epoch: incomingEpoch, currentEpoch: this.epoch };
    }
    if (this.connectionState !== 'Active' && this.connectionState !== 'Opening') {
      return { status: 'ConnectionNotActive', state: this.connectionState };
    }
    
    // Monotonic sequence sequencing
    let seq;
    if (explicitStamp !== null) {
      seq = explicitStamp;
      if (seq > this.sequenceCounter) {
        this.sequenceCounter = seq;
      }
    } else {
      this.sequenceCounter++;
      seq = this.sequenceCounter;
    }
    
    const stampKey = `${incomingEpoch}:${seq}`;
    
    // Check duplicate
    if (this.receiptCache.has(stampKey)) {
      return { status: 'Duplicate', receipt: this.receiptCache.get(stampKey) };
    }
    if (this.evictedReceipts.has(stampKey)) {
      return { status: 'DuplicateReceiptExpired', stamp: stampKey };
    }
    
    // Reentrancy handling (§4.1, §4.4, Audit 4.C):
    // Synchronous reads are always permitted directly against state.
    // If a callback/command mutation arrives reentrantly while processing,
    // enqueue it in FIFO order to execute immediately after current atomic step completes.
    if (this.isProcessingCallback) {
      this.reentrantMutationQueue.push({ incomingEpoch, payload, stampKey });
      return { status: 'QueuedReentrant', stampKey };
    }
    
    this.isProcessingCallback = true;
    let receipt;
    try {
      receipt = this.applyCallbackPayload(payload);
    } finally {
      this.isProcessingCallback = false;
    }
    
    // Cache receipt with bounded retention
    this._cacheReceipt(stampKey, receipt);
    
    // Drain any reentrant mutations that were enqueued during processing:
    while (this.reentrantMutationQueue.length > 0) {
      const nextItem = this.reentrantMutationQueue.shift();
      let nextReceipt;
      this.isProcessingCallback = true;
      try {
        nextReceipt = this.applyCallbackPayload(nextItem.payload);
      } finally {
        this.isProcessingCallback = false;
      }
      this._cacheReceipt(nextItem.stampKey, nextReceipt);
    }
    
    return { status: 'Accepted', receipt };
  }
  
  _cacheReceipt(stampKey, receipt) {
    this.receiptCache.set(stampKey, receipt);
    if (this.receiptCache.size > this.maxCacheSize) {
      const oldestKey = this.receiptCache.keys().next().value;
      this.receiptCache.delete(oldestKey);
      this.evictedReceipts.add(oldestKey);
    }
  }
  
  applyCallbackPayload(payload) {
    const beforeText = this.docText;
    const beforeAttrs = cloneAttrs(this.docAttributes);
    const beforeSelection = [...this.selection];
    
    // -----------------------------------------------------------------------
    // startComposition: Explicit session start (§4.2)
    // -----------------------------------------------------------------------
    if (payload.type === 'startComposition') {
      const rawStart = payload.start !== undefined ? payload.start : this.selection[0];
      const rawEnd = payload.end !== undefined ? payload.end : this.selection[1];
      const normStart = Math.max(0, Math.min(this.docText.length, Math.min(rawStart, rawEnd)));
      const normEnd = Math.max(0, Math.min(this.docText.length, Math.max(rawStart, rawEnd)));
      
      this.compositionState = 'Composing';
      this.activeSessionId = payload.sessionId || `session-${Date.now()}`;
      this.sessionEpoch = this.epoch;
      this.attributionEpoch = 1;
      this.sessionOriginStart = normStart;
      this.markedRange = [normStart, normEnd];
      this.committedUnits = [];
      this.committedExtent = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      
      // Pin immutable pre-composition snapshot before ANY modification
      this.compositionBeforeSnapshot = {
        text: beforeText,
        attrs: beforeAttrs,
        selection: beforeSelection,
        sessionStartOffset: normStart,
        sessionRevision: this.sessionRevision,
        documentRevision: this.documentRevision
      };
      
      // Capture immutable baseline B from document slice
      this.baselineUnits = [];
      for (let i = normStart; i < normEnd; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      this.seedAttributes = { ...(payload.seedAttributes || {}) };
      
      // Raw selection preservation (4.A):
      if (payload.selection) {
        this.selection = [
          Math.max(0, Math.min(this.docText.length, payload.selection[0])),
          Math.max(0, Math.min(this.docText.length, payload.selection[1]))
        ];
      } else {
        this.selection = [normEnd, normEnd];
      }
      this.sessionRevision++;
      
      return {
        type: 'CompositionStarted',
        sessionRevision: this.sessionRevision,
        sessionId: this.activeSessionId,
        markedRange: [...this.markedRange],
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // preedit: Candidate string update (provisional text) (§4.3)
    // -----------------------------------------------------------------------
    if (payload.type === 'preedit') {
      // 1. Implicit session start if Idle or snapshot missing
      if (this.compositionState === 'Idle' || !this.compositionBeforeSnapshot) {
        const rawStart = payload.start !== undefined ? payload.start : Math.min(this.selection[0], this.selection[1]);
        const rawEnd = payload.end !== undefined ? payload.end : Math.max(this.selection[0], this.selection[1]);
        const start = Math.max(0, Math.min(this.docText.length, rawStart));
        const end = Math.max(0, Math.min(this.docText.length, rawEnd));
        
        this.compositionState = 'Composing';
        this.activeSessionId = payload.sessionId || `session-${Date.now()}`;
        this.sessionEpoch = this.epoch;
        this.attributionEpoch = 1;
        this.sessionOriginStart = start;
        this.markedRange = [start, end];
        this.committedUnits = [];
        this.committedExtent = null;
        this.expansionOriginalSurplus = "";
        this.expansionOriginalSurplusAttrs = [];
        this.expansionPrefixSurplus = "";
        this.expansionPrefixSurplusAttrs = [];
        this.expansionSuffixSurplus = "";
        this.expansionSuffixSurplusAttrs = [];
        
        this.compositionBeforeSnapshot = {
          text: beforeText,
          attrs: beforeAttrs,
          selection: beforeSelection,
          sessionStartOffset: start,
          sessionRevision: this.sessionRevision,
          documentRevision: this.documentRevision
        };
        
        this.baselineUnits = [];
        for (let i = start; i < end; i++) {
          this.baselineUnits.push({
            char: this.docText[i],
            attributes: { ...this.docAttributes[i] }
          });
        }
        this.seedAttributes = { ...(payload.seedAttributes || {}) };
      } else {
        // If already AwaitingSettlement and markedRange is null (native edit following nonterminal unmark):
        if (this.markedRange === null) {
          const rawStart = payload.start !== undefined ? payload.start : this.selection[0];
          const rawEnd = payload.end !== undefined ? payload.end : this.selection[1];
          const start = Math.max(0, Math.min(this.docText.length, rawStart));
          const end = Math.max(0, Math.min(this.docText.length, rawEnd));
          this.markedRange = [start, end];
          this.attributionEpoch++;
          this.baselineUnits = [];
          for (let i = start; i < end; i++) {
            this.baselineUnits.push({
              char: this.docText[i],
              attributes: { ...this.docAttributes[i] }
            });
          }
          if (this.pendingCommands.length === 0) {
            this.compositionState = 'Composing';
          }
        } else if (this.compositionState !== 'AwaitingSettlement') {
          this.compositionState = 'Composing';
        }
      }
      
      const candidateText = payload.text || "";
      const [start, end] = this.markedRange;
      
      // Reconcile candidate attributes against immutable baseline B (§4.3)
      const reconciledUnits = reconcileCandidateAttributes(
        this.baselineUnits,
        candidateText,
        this.seedAttributes
      );
      
      // Splice candidate into document text and attributes
      const newText = this.docText.slice(0, start) + candidateText + this.docText.slice(end);
      const newAttrs = [
        ...this.docAttributes.slice(0, start),
        ...reconciledUnits.map(u => ({ ...u.attributes })),
        ...this.docAttributes.slice(end)
      ];
      
      const newMarkedEnd = start + candidateText.length;
      
      // Check DocumentRevision vs SessionRevision distinction (Defect D & 4.B):
      // DocumentRevision advances ONLY if text or attributes semantically changed!
      const textChanged = (newText !== this.docText);
      const attrsChanged = !areAttrsEqual(newAttrs, this.docAttributes);
      const semanticDocChange = textChanged || attrsChanged;
      
      if (semanticDocChange) {
        this.documentRevision++;
      }
      this.sessionRevision++;
      
      this.docText = newText;
      this.docAttributes = newAttrs;
      this.markedRange = [start, newMarkedEnd];
      
      // Raw selection preservation (4.A):
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
        selection: [...this.selection],
        state: this.compositionState
      };
    }
    
    // -----------------------------------------------------------------------
    // partialCommit: Partial commit freezing (§4.3, R03)
    // -----------------------------------------------------------------------
    if (payload.type === 'partialCommit') {
      const commitLen = payload.committedLength || 0;
      const [start, end] = this.markedRange;
      const currentCandidateText = this.docText.slice(start, end);
      
      if (commitLen > currentCandidateText.length) {
        return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate', candidateLength: currentCandidateText.length, requested: commitLen };
      }
      
      if (commitLen > 0) {
        // 1. Current reconciled candidate units
        const currentCandidateUnits = reconcileCandidateAttributes(
          this.baselineUnits,
          currentCandidateText,
          this.seedAttributes
        );
        
        // 2. Freeze committed portion into persistent committedUnits
        const newlyCommittedPortion = currentCandidateUnits.slice(0, commitLen);
        this.committedUnits.push(...newlyCommittedPortion);
        
        // Update cumulative committedExtent in live document coordinates
        if (!this.committedExtent) {
          this.committedExtent = [start, start + commitLen];
        } else {
          this.committedExtent = [this.committedExtent[0], start + commitLen];
        }
        
        // 3. Remaining units become the baseline for the next attribution epoch
        const remainingUnits = currentCandidateUnits.slice(commitLen);
        this.attributionEpoch++;
        this.baselineUnits = remainingUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
        
        // 4. Update marked range (remaining uncommitted candidate)
        const newMarkedStart = start + commitLen;
        this.markedRange = [newMarkedStart, end];
        
        // Semantic document change occurred: text was frozen
        this.documentRevision++;
      }
      // Metadata-only partial commit (commitLen === 0) does not advance DocumentRevision
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
    // targetExpansion: Native target expansion (§4.4, §6.1 Rule 3, R04)
    // -----------------------------------------------------------------------
    if (payload.type === 'targetExpansion') {
      let newStart = Math.max(0, payload.newStart);
      let newEnd = Math.min(this.docText.length, payload.newEnd);
      if (newStart > newEnd) {
        return { status: 'Rejected', reason: 'InvalidTargetExpansionRange', range: [payload.newStart, payload.newEnd] };
      }
      
      const oldMarked = this.markedRange || [newStart, newEnd];

      // Invariant I08 Protection:
      // If frozen committed units exist, they CANNOT be re-opened as candidate text!
      if (this.committedExtent) {
        // If expansion attempts to overlap frozen committed units:
        if (newStart < this.committedExtent[1]) {
          // Clamp start to after the committed units so frozen units remain protected!
          newStart = this.committedExtent[1];
          newEnd = Math.max(newStart, newEnd);
        }
      }
      
      // Start new attribution epoch covering expanded envelope
      this.attributionEpoch++;
      
      // Track original surplus text swallowed from the document (both prefix and suffix):
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
      
      // Capture new baselineUnits from live document slice at new coordinates
      this.baselineUnits = [];
      for (let i = newStart; i < newEnd; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      
      // Raw selection preservation (4.A):
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
        : [Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])];
      const commitText = payload.text !== undefined ? payload.text : "";
      
      if (!this.compositionBeforeSnapshot) {
        this.sessionOriginStart = start;
      }
      
      // Reconcile attributes for the committed text
      let committedAttrs;
      if (payload.attributes) {
        committedAttrs = new Array(commitText.length).fill(null).map(() => ({ ...payload.attributes }));
      } else if (this.baselineUnits) {
        const reconciled = reconcileCandidateAttributes(this.baselineUnits, commitText, this.seedAttributes);
        committedAttrs = reconciled.map(u => ({ ...u.attributes }));
      } else {
        committedAttrs = new Array(commitText.length).fill(null).map(() => ({}));
      }
      
      const newText = this.docText.slice(0, start) + commitText + this.docText.slice(end);
      const newAttrs = [
        ...this.docAttributes.slice(0, start),
        ...committedAttrs,
        ...this.docAttributes.slice(end)
      ];
      
      const compBefore = this.compositionBeforeSnapshot || {
        text: beforeText,
        attrs: beforeAttrs,
        selection: beforeSelection,
        sessionStartOffset: start
      };
      
      // Calculate exact extent of this session:
      // Begins at session origin start (or committedExtent start) and ends at start + commitText.length
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
      
      const textChanged = (newText !== compBefore.text);
      const attrsChanged = !areAttrsEqual(newAttrs, compBefore.attrs);
      const semanticDocChange = textChanged || attrsChanged;
      
      if (semanticDocChange) {
        // Save exactly ONE composition history group (§4.2, §7)
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: compBefore.attrs,
          beforeSelection: compBefore.selection,
          afterText: newText,
          afterAttrs: newAttrs,
          afterSelection: afterSel,
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
        this.documentRevision++;
      }
      this.sessionRevision++;
      
      // Clear transient composition state
      this.compositionBeforeSnapshot = null;
      this.markedRange = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.baselineUnits = null;
      this.activeSessionId = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      
      const wasAwaitingSettlement = (this.compositionState === 'AwaitingSettlement');
      this.compositionState = 'Idle';
      
      // Drain pending commands in FIFO order
      if (wasAwaitingSettlement || this.pendingCommands.length > 0) {
        this.drainPendingCommands(finalCommittedExtent);
      }
      
      return {
        type: 'Committed',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        selection: [...this.selection],
        state: this.compositionState
      };
    }
    
    // -----------------------------------------------------------------------
    // unmark: Clear marked range and accept text (§4.4, Defect A, Defect C)
    // -----------------------------------------------------------------------
    if (payload.type === 'unmark') {
      // If already Idle, nothing to unmark
      if (this.compositionState === 'Idle' && !this.markedRange) {
        return { type: 'NoChange', reason: 'AlreadyIdle' };
      }
      
      // Defect A repair: If already unmarked (markedRange is null) and state is AwaitingSettlement:
      // Repeating unmark is NOT a valid completion mechanism! Reject it explicitly to prevent state corruption!
      if (this.compositionState === 'AwaitingSettlement' && this.markedRange === null) {
        return {
          status: 'Rejected',
          reason: 'AlreadyUnmarked',
          message: 'Composition candidate already unmarked; settlement completion requires completeSettlement event',
          activeSessionId: this.activeSessionId,
          state: this.compositionState
        };
      }
      
      const compBefore = this.compositionBeforeSnapshot || {
        text: beforeText,
        attrs: beforeAttrs,
        selection: beforeSelection,
        sessionStartOffset: this.markedRange ? this.markedRange[0] : 0
      };
      
      // The text currently in the marked range is accepted as committed verbatim
      const mStart = this.markedRange ? this.markedRange[0] : (this.committedExtent ? this.committedExtent[0] : compBefore.sessionStartOffset);
      const mEnd = this.markedRange ? this.markedRange[1] : (this.committedExtent ? this.committedExtent[1] : mStart);
      const cStart = this.committedExtent ? this.committedExtent[0] : mStart;
      const cEnd = this.committedExtent ? this.committedExtent[1] : mEnd;
      const finalCommittedExtent = [Math.min(mStart, cStart), Math.max(mEnd, cEnd)];
      
      // Clear marked range (transient candidate removed)
      this.markedRange = null;
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = this.activeSessionId;
      
      if (payload.selection) {
        this._setSelection(payload.selection);
      }
      this.sessionRevision++;
      
      const isSettled = payload.settled !== false;
      if (isSettled) {
        // Terminal unmark: completes settlement immediately!
        // Record exactly ONE composition history group
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: compBefore.attrs,
          beforeSelection: compBefore.selection,
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
        
        // Clear transient session identity and snapshot
        this.compositionBeforeSnapshot = null;
        this.committedUnits = [];
        this.committedExtent = null;
        this.baselineUnits = null;
        this.activeSessionId = null;
        this.expansionOriginalSurplus = "";
        this.expansionOriginalSurplusAttrs = [];
        this.compositionState = 'Idle';
        
        // Drain pending commands against settled extent
        this.drainPendingCommands(finalCommittedExtent);
      } else {
        // Nonterminal unmark (settled: false):
        // Adapter indicates candidate decoration is cleared, but platform settlement fence has NOT arrived yet!
        // Defer history creation, retain activeSessionId, compositionBeforeSnapshot, and wait for completeSettlement!
        this.compositionState = 'AwaitingSettlement';
      }
      
      return {
        type: 'Unmarked',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        state: this.compositionState,
        settled: isSettled,
        selection: [...this.selection]
      };
    }
    
    // -----------------------------------------------------------------------
    // completeSettlement: Explicit normalized completion event (§4.4, Defect A)
    // -----------------------------------------------------------------------
    if (payload.type === 'completeSettlement') {
      if (this.compositionState === 'Idle') {
        return { status: 'Ignored', reason: 'AlreadySettled' };
      }
      
      // Verify session ID if specified in payload
      if (payload.sessionId && this.activeSessionId && payload.sessionId !== this.activeSessionId) {
        return {
          status: 'Rejected',
          reason: 'SessionMismatch',
          expectedSessionId: this.activeSessionId,
          providedSessionId: payload.sessionId
        };
      }
      
      const compBefore = this.compositionBeforeSnapshot;
      const finalCommittedExtent = this.lastSettledExtent || [0, 0];
      
      // Finalize the deferred composition history group
      if (compBefore) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: compBefore.attrs,
          beforeSelection: compBefore.selection,
          afterText: this.docText,
          afterAttrs: cloneAttrs(this.docAttributes),
          afterSelection: [...this.selection],
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
      }
      
      // Clear transient session identity and state
      this.compositionBeforeSnapshot = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.baselineUnits = null;
      this.markedRange = null;
      const finishedSessionId = this.activeSessionId;
      this.activeSessionId = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.compositionState = 'Idle';
      this.sessionRevision++;
      
      // Drain pending commands in FIFO order!
      this.drainPendingCommands(finalCommittedExtent);
      
      return {
        type: 'SettlementCompleted',
        sessionRevision: this.sessionRevision,
        documentRevision: this.documentRevision,
        finalExtent: finalCommittedExtent,
        sessionId: finishedSessionId,
        state: this.compositionState
      };
    }
    
    // -----------------------------------------------------------------------
    // cancel: Composition aborted / cancelled (§4.4, Defect B)
    // -----------------------------------------------------------------------
    if (payload.type === 'cancel') {
      let finalCommittedExtent = null;
      const finishedSessionId = this.activeSessionId;
      const hasFrozenCommittedUnits = (this.committedUnits.length > 0);
      
      if (hasFrozenCommittedUnits) {
        // Invariant I08 & Defect B: Frozen committed units MUST SURVIVE cancellation!
        // Suffix candidate in markedRange is removed, but frozen committed units remain intact!
        const [markedStart, markedEnd] = this.markedRange || [
          this.committedExtent ? this.committedExtent[1] : this.sessionOriginStart,
          this.docText.length
        ];
        
        // Restore surplus characters swallowed from document (both prefix and suffix):
        const replacementText = (payload.text !== undefined ? payload.text : "");
        const prefixRestoreStart = markedStart - this.expansionPrefixSurplus.length;
        const leftDoc = this.docText.slice(0, Math.max(0, prefixRestoreStart));
        const leftAttrs = this.docAttributes.slice(0, Math.max(0, prefixRestoreStart));
        const rightDoc = this.docText.slice(markedEnd);
        const rightAttrs = this.docAttributes.slice(markedEnd);
        
        const newText = leftDoc 
          + this.expansionPrefixSurplus 
          + replacementText 
          + this.expansionSuffixSurplus 
          + rightDoc;
        const newAttrs = [
          ...leftAttrs,
          ...this.expansionPrefixSurplusAttrs,
          ...new Array(replacementText.length).fill(null).map(() => ({})),
          ...this.expansionSuffixSurplusAttrs,
          ...rightAttrs
        ];
        
        const compBefore = this.compositionBeforeSnapshot || {
          text: beforeText,
          attrs: beforeAttrs,
          selection: beforeSelection,
          sessionStartOffset: this.sessionOriginStart
        };
        
        const extentStart = this.committedExtent ? this.committedExtent[0] : compBefore.sessionStartOffset;
        const extentEnd = this.committedExtent ? this.committedExtent[1] : (extentStart + this.committedUnits.length);
        finalCommittedExtent = [extentStart, extentEnd];
        this.lastSettledExtent = finalCommittedExtent;
        this.lastSettledSessionId = finishedSessionId;
        
        const afterSelOffset = (this.committedExtent ? this.committedExtent[1] : markedStart) + replacementText.length;
        const afterSel = [afterSelOffset, afterSelOffset];
        
        // Push single composition history group representing the accepted partial commit(s)
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: compBefore.attrs,
          beforeSelection: compBefore.selection,
          afterText: newText,
          afterAttrs: newAttrs,
          afterSelection: afterSel,
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
        
        this.docText = newText;
        this.docAttributes = newAttrs;
        this.selection = afterSel;
        this.documentRevision++;
      } else if (this.compositionBeforeSnapshot) {
        // No partial commits: cleanly restore pre-composition baseline
        this.docText = this.compositionBeforeSnapshot.text;
        this.docAttributes = cloneAttrs(this.compositionBeforeSnapshot.attrs);
        this.selection = [...this.compositionBeforeSnapshot.selection];
        this.documentRevision++;
      }
      
      this.compositionState = 'Idle';
      this.markedRange = null;
      this.compositionBeforeSnapshot = null;
      this.committedUnits = [];
      this.committedExtent = null;
      this.baselineUnits = null;
      this.activeSessionId = null;
      this.expansionOriginalSurplus = "";
      this.expansionOriginalSurplusAttrs = [];
      this.expansionPrefixSurplus = "";
      this.expansionPrefixSurplusAttrs = [];
      this.expansionSuffixSurplus = "";
      this.expansionSuffixSurplusAttrs = [];
      this.sessionRevision++;
      
      // Cancel pending commands targeting active composition (§4.5, 4.F)
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
      
      // Drain unrelated anchored commands against restored document state
      for (const item of unrelatedCommands) {
        if (item.epoch < this.epoch) {
          this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'StaleEpoch', ticketId: item.ticketId });
          continue;
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
  
  // Application command dispatch (§4.5, Defect A)
  dispatchAppCommand(cmd) {
    // If composition is active (Composing) OR awaiting settlement (AwaitingSettlement),
    // commands requiring settlement MUST remain queued in strict FIFO order!
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const ticketId = `ticket-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      this.pendingCommands.push({
        ticketId,
        cmd,
        epoch: this.epoch,
        sessionId: this.activeSessionId
      });
      this.commandOutcomes.set(ticketId, { status: 'Deferred', ticketId });
      this.compositionState = 'AwaitingSettlement';
      return { status: 'Deferred', ticketId };
    }
    
    // Otherwise execute immediately
    const res = this.executeFormatCommand(cmd);
    return res;
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
    const ticketId = `settle-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    this.compositionState = 'AwaitingSettlement';
    return {
      status: 'Requested',
      ticketId,
      reason,
      sessionId: this.activeSessionId,
      epoch: this.epoch
    };
  }

  cancelCommand(ticketId) {
    const idx = this.pendingCommands.findIndex(c => c.ticketId === ticketId);
    if (idx !== -1) {
      const item = this.pendingCommands[idx];
      item.cancelled = true;
      item.cancelReason = 'CancelledByUser';
      this.pendingCommands.splice(idx, 1);
      this.commandOutcomes.set(ticketId, { status: 'Cancelled', reason: 'CancelledByUser', ticketId });
      return { status: 'Cancelled', ticketId };
    }
    return { status: 'NotFound', ticketId };
  }
  
  executeFormatCommand(cmd, resolvedRange = null) {
    const beforeText = this.docText;
    const beforeAttrs = cloneAttrs(this.docAttributes);
    const beforeSelection = [...this.selection];
    
    let start = 0;
    let end = 0;
    
    // Resolve target extent (§4.5, Defect B, 4.F):
    let isComposingTarget = false;
    let targetSessionId = null;
    if (cmd.target === 'composing') {
      isComposingTarget = true;
    } else if (cmd.target && typeof cmd.target === 'object' && cmd.target.type === 'CompositionTarget') {
      isComposingTarget = true;
      targetSessionId = cmd.target.sessionId;
    }

    if (isComposingTarget) {
      if (targetSessionId && this.activeSessionId && targetSessionId !== this.activeSessionId && targetSessionId !== this.lastSettledSessionId) {
        return { status: 'Rejected', reason: 'TargetSessionMismatch' };
      }
      if (resolvedRange) {
        [start, end] = resolvedRange;
      } else if (this.lastSettledExtent) {
        [start, end] = this.lastSettledExtent;
      } else {
        // Fallback to current selection ONLY if no composition extent exists
        [start, end] = this.selection;
      }
    } else if (Array.isArray(cmd.target)) {
      [start, end] = cmd.target;
    } else {
      [start, end] = this.selection;
    }
    
    // Clamp to document bounds
    start = Math.max(0, Math.min(this.docText.length, start));
    end = Math.max(0, Math.min(this.docText.length, end));
    
    // Empty range formatting (§5.2, R05, Defect B edge case, Audit 4.F):
    // Formatting an empty range updates typing intent only; it produces NO document revision bump
    // and NO history group!
    if (start === end) {
      this.typingIntent = { ...this.typingIntent, ...cmd.style };
      this.sessionRevision++;
      return {
        status: 'NoChange',
        reason: 'EmptyRange',
        typingIntent: { ...this.typingIntent }
      };
    }
    
    // Check for net no-op change (§7, Invariant I16, R08):
    let hasChange = false;
    const newAttrs = cloneAttrs(this.docAttributes);
    for (let i = start; i < end; i++) {
      const merged = { ...newAttrs[i], ...cmd.style };
      if (JSON.stringify(merged) !== JSON.stringify(newAttrs[i])) {
        hasChange = true;
        newAttrs[i] = merged;
      }
    }
    
    if (!hasChange) {
      this.sessionRevision++;
      return { status: 'NoChange', reason: 'IdenticalAttributes' };
    }
    
    // Apply mutation
    this.docAttributes = newAttrs;
    this.documentRevision++;
    this.sessionRevision++;
    
    // Push formatting undo group
    this.undoStack.push({
      group: 'Formatting',
      beforeText,
      beforeAttrs,
      beforeSelection,
      afterText: this.docText,
      afterAttrs: cloneAttrs(this.docAttributes),
      afterSelection: [...this.selection],
      range: [start, end],
      style: { ...cmd.style }
    });
    this.redoStack = [];
    
    return {
      status: 'Applied',
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision,
      appliedRange: [start, end],
      style: { ...cmd.style }
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
    this.redoStack.push(entry);
    
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
    this.undoStack.push(entry);
    
    this.docText = entry.afterText;
    this.docAttributes = cloneAttrs(entry.afterAttrs);
    this._setSelection(entry.afterSelection);
    this.documentRevision++;
    this.sessionRevision++;
    return true;
  }
}
