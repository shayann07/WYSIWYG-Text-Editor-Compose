// Repaired Protocol Coordinator and Transaction Engine Reference Model (§4, §6, §7)
// Repairs applied:
// - Defect A: Enforces FIFO command queuing in BOTH Composing and AwaitingSettlement (no settlement bypass).
//             Preedit callbacks received while AwaitingSettlement maintain settlement request and do not drop status.
// - Defect B: Precise CompositionTarget(sessionId) extent calculation for all edge cases:
//             Standard replacement, candidate growth/shrinkage, empty final candidates, partial commits, and target expansion.
//             Never defaults/falls back to the whole document!
// - Defect C: Unmark preserves accepted text, clears transient state, finalizes composition history group,
//             and respects adapter settlement contract before draining pending commands.
// - Commit without a custom "finish" flag transitions to Idle and settles naturally.
// - Explicit and implicit composition start correctly initializes from selection (never blind [0, 0]).
// - Explicit replacement ranges & noncollapsed selections preserved.
// - Partial commit freezing, cancellation, connection replacement, focus loss, and disposal.
// - Pending commands carry epoch/session and expire on stale connection.
// - Symmetrical undo/redo after every termination path.
// - Monotonic sequence stamps, duplicate cache with DuplicateReceiptExpired, and reentrancy protection.
// - DocumentRevision (content/attributes only) vs SessionRevision (all events) separation.
// - Full integration of candidate reconciliation algorithm (§4.3) with immutable baseline B.

import { reconcileCandidateAttributes } from './reference-model.mjs';

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
      ? initialAttrs.map(a => ({ ...a }))
      : new Array(initialText.length).fill(null).map(() => ({}));
    this.selection = [...initialSelection]; // [anchor, focus]
    
    // Composition state (§4.2)
    this.compositionState = 'Idle'; // Idle | Composing | AwaitingSettlement | Faulted
    this.activeSessionId = null;
    this.sessionEpoch = 0;
    this.attributionEpoch = 0;
    this.sessionStartOffset = 0;
    this.markedRange = null; // [start, end] in live document coordinates
    this.baselineUnits = null; // immutable baseline B: array of { char, attributes }
    this.seedAttributes = {}; // default attributes for new candidate text
    this.frozenPrefixUnits = []; // accumulated frozen units from partial commits
    this.compositionBeforeSnapshot = null; // pinned snapshot before provisional edits
    this.lastSettledExtent = null; // [start, end] resulting from last completed composition
    this.lastSettledSessionId = null; // sessionId of last completed composition
    
    // Queued application commands (§4.5)
    this.pendingCommands = []; // array of { ticketId, cmd, epoch, sessionId }
    this.watchdogTriggered = false;
    this.watchdogLog = [];
    
    // History (§7)
    this.undoStack = [];
    this.redoStack = [];
    
    // Reentrancy guard
    this.isProcessingCallback = false;
  }
  
  openNewConnection() {
    this.epoch++;
    this.connectionState = 'Active';
    this.sequenceCounter = 0;
    this.receiptCache.clear();
    this.evictedReceipts.clear();
    
    // Cancel any pending commands from previous connection epoch:
    if (this.pendingCommands.length > 0) {
      for (const item of this.pendingCommands) {
        if (item.epoch < this.epoch) {
          item.cancelled = true;
          item.cancelReason = 'StaleEpoch';
        }
      }
      this.pendingCommands = this.pendingCommands.filter(item => !item.cancelled);
    }
    
    // Reset dangling composition state from previous connection:
    this.compositionState = 'Idle';
    this.compositionBeforeSnapshot = null;
    this.markedRange = null;
    this.activeSessionId = null;
    this.baselineUnits = null;
    this.frozenPrefixUnits = [];
    this.lastSettledExtent = null;
    this.lastSettledSessionId = null;
    return this.epoch;
  }
  
  closeConnection() {
    this.connectionState = 'Closed';
    // Expire pending commands
    for (const item of this.pendingCommands) {
      item.cancelled = true;
      item.cancelReason = 'ConnectionClosed';
    }
    this.pendingCommands = [];
    if (this.compositionState !== 'Idle') {
      this.compositionState = 'Idle';
      this.markedRange = null;
      this.compositionBeforeSnapshot = null;
      this.frozenPrefixUnits = [];
      this.baselineUnits = null;
      this.activeSessionId = null;
    }
  }

  onFocusLost(strategy = 'settle') {
    // Focus loss (§4.5): Cancel pending commands targeting active composition
    for (const item of this.pendingCommands) {
      if (item.cmd.target === 'composing' || item.cmd.target?.type === 'CompositionTarget') {
        item.cancelled = true;
        item.cancelReason = 'FocusLost';
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
    this.connectionState = 'Closed';
    this.compositionState = 'Idle';
    for (const item of this.pendingCommands) {
      item.cancelled = true;
      item.cancelReason = 'Disposed';
    }
    this.pendingCommands = [];
    this.markedRange = null;
    this.compositionBeforeSnapshot = null;
    this.frozenPrefixUnits = [];
    this.baselineUnits = null;
    this.activeSessionId = null;
    return { status: 'Disposed' };
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
    
    // Reentrancy guard
    if (this.isProcessingCallback) {
      throw new Error("Reentrant callback execution detected in protocol coordinator");
    }
    this.isProcessingCallback = true;
    
    let receipt;
    try {
      receipt = this.applyCallbackPayload(payload);
    } finally {
      this.isProcessingCallback = false;
    }
    
    // Cache receipt with bounded retention
    this.receiptCache.set(stampKey, receipt);
    if (this.receiptCache.size > this.maxCacheSize) {
      const oldestKey = this.receiptCache.keys().next().value;
      this.receiptCache.delete(oldestKey);
      this.evictedReceipts.add(oldestKey);
    }
    
    return { status: 'Accepted', receipt };
  }
  
  applyCallbackPayload(payload) {
    this.sessionRevision++;
    const beforeText = this.docText;
    const beforeAttrs = this.docAttributes.map(a => ({ ...a }));
    const beforeSelection = [...this.selection];
    
    // -----------------------------------------------------------------------
    // startComposition: Explicit session start
    // -----------------------------------------------------------------------
    if (payload.type === 'startComposition') {
      const start = payload.start !== undefined ? payload.start : this.selection[0];
      const end = payload.end !== undefined ? payload.end : this.selection[1];
      
      this.compositionState = 'Composing';
      this.activeSessionId = payload.sessionId || `session-${Date.now()}`;
      this.sessionEpoch = this.epoch;
      this.attributionEpoch = 1;
      this.sessionStartOffset = Math.min(start, end);
      this.markedRange = [Math.min(start, end), Math.max(start, end)];
      
      // Pin immutable pre-composition snapshot before ANY modification
      this.compositionBeforeSnapshot = {
        text: beforeText,
        attrs: beforeAttrs,
        selection: beforeSelection,
        sessionStartOffset: this.sessionStartOffset,
        sessionRevision: this.sessionRevision,
        documentRevision: this.documentRevision
      };
      
      // Capture immutable baseline B from document slice
      this.baselineUnits = [];
      for (let i = this.markedRange[0]; i < this.markedRange[1]; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      this.seedAttributes = { ...(payload.seedAttributes || {}) };
      this.frozenPrefixUnits = [];
      
      return {
        type: 'CompositionStarted',
        sessionRevision: this.sessionRevision,
        sessionId: this.activeSessionId,
        markedRange: [...this.markedRange]
      };
    }
    
    // -----------------------------------------------------------------------
    // preedit: Candidate string update (provisional text)
    // -----------------------------------------------------------------------
    if (payload.type === 'preedit') {
      // 1. If no active session, implicitly initialize session at current selection or explicit range
      if (this.compositionState === 'Idle' || !this.compositionBeforeSnapshot) {
        const start = payload.start !== undefined ? payload.start : Math.min(this.selection[0], this.selection[1]);
        const end = payload.end !== undefined ? payload.end : Math.max(this.selection[0], this.selection[1]);
        
        this.compositionState = 'Composing';
        this.activeSessionId = payload.sessionId || `session-${Date.now()}`;
        this.sessionEpoch = this.epoch;
        this.attributionEpoch = 1;
        this.sessionStartOffset = start;
        this.markedRange = [start, end];
        
        this.compositionBeforeSnapshot = {
          text: beforeText,
          attrs: beforeAttrs,
          selection: beforeSelection,
          sessionStartOffset: this.sessionStartOffset,
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
        this.frozenPrefixUnits = [];
      } else {
        // Fix Defect A: If already AwaitingSettlement, KEEP AwaitingSettlement!
        // Do NOT revert to 'Composing', and do not lose the settlement request!
        if (this.compositionState !== 'AwaitingSettlement') {
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
      this.docText = newText;
      this.docAttributes = newAttrs;
      this.markedRange = [start, newMarkedEnd];
      this.selection = [newMarkedEnd, newMarkedEnd];
      this.documentRevision++;
      
      return {
        type: 'PreeditUpdated',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        markedRange: [...this.markedRange],
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
        throw new Error("Committed length exceeds current candidate length");
      }
      
      // 1. Current reconciled candidate units
      const currentCandidateUnits = reconcileCandidateAttributes(
        this.baselineUnits,
        currentCandidateText,
        this.seedAttributes
      );
      
      // 2. Freeze committed portion
      const committedPortion = currentCandidateUnits.slice(0, commitLen);
      this.frozenPrefixUnits.push(...committedPortion);
      
      // 3. Remaining units become the baseline for the next attribution epoch
      const remainingUnits = currentCandidateUnits.slice(commitLen);
      this.attributionEpoch++;
      this.baselineUnits = remainingUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
      
      // 4. Update marked range (remaining uncommitted candidate)
      const newMarkedStart = start + commitLen;
      this.markedRange = [newMarkedStart, end];
      this.documentRevision++;
      
      return {
        type: 'PartialCommitted',
        documentRevision: this.documentRevision,
        attributionEpoch: this.attributionEpoch,
        frozenCount: this.frozenPrefixUnits.length,
        markedRange: [...this.markedRange]
      };
    }
    
    // -----------------------------------------------------------------------
    // targetExpansion: Native target expansion (§4.4, §6.1 Rule 3, R04)
    // -----------------------------------------------------------------------
    if (payload.type === 'targetExpansion') {
      const newStart = payload.newStart;
      const newEnd = payload.newEnd;
      
      // Start new attribution epoch covering expanded envelope
      this.attributionEpoch++;
      this.markedRange = [newStart, newEnd];
      this.sessionStartOffset = newStart;
      
      this.baselineUnits = [];
      for (let i = newStart; i < newEnd; i++) {
        this.baselineUnits.push({
          char: this.docText[i],
          attributes: { ...this.docAttributes[i] }
        });
      }
      this.frozenPrefixUnits = [];
      
      return {
        type: 'TargetExpanded',
        attributionEpoch: this.attributionEpoch,
        markedRange: [...this.markedRange]
      };
    }
    
    // -----------------------------------------------------------------------
    // commit: Final text commitment
    // -----------------------------------------------------------------------
    if (payload.type === 'commit') {
      const [start, end] = this.markedRange 
        ? this.markedRange 
        : [Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])];
      const commitText = payload.text !== undefined ? payload.text : "";
      
      // If no prior composition snapshot existed, initialize sessionStartOffset to start
      if (!this.compositionBeforeSnapshot) {
        this.sessionStartOffset = start;
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
      
      // Calculate exact committed extent for this session (§4.5):
      // Extent begins at sessionStartOffset and ends at:
      // sessionStartOffset + frozenPrefixLength + commitText.length
      const totalSessionCommittedLen = this.frozenPrefixUnits.length + commitText.length;
      const sessionStart = this.sessionStartOffset;
      const finalCommittedExtent = [sessionStart, sessionStart + totalSessionCommittedLen];
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = this.activeSessionId || payload.sessionId || null;
      
      // Save composition history group (§4.2, §7)
      this.undoStack.push({
        group: 'Composition',
        beforeText: compBefore.text,
        beforeAttrs: compBefore.attrs,
        beforeSelection: compBefore.selection,
        afterText: newText,
        afterAttrs: newAttrs,
        afterSelection: [start + commitText.length, start + commitText.length],
        sessionExtent: [...finalCommittedExtent]
      });
      this.redoStack = [];
      
      this.docText = newText;
      this.docAttributes = newAttrs;
      this.selection = [start + commitText.length, start + commitText.length];
      this.documentRevision++;
      
      // Clear transient composition state
      this.compositionBeforeSnapshot = null;
      this.markedRange = null;
      this.frozenPrefixUnits = [];
      this.baselineUnits = null;
      const finishedSessionId = this.activeSessionId;
      this.activeSessionId = null;
      
      // Commit without a custom "finish" flag transitions naturally to Idle
      const wasAwaitingSettlement = (this.compositionState === 'AwaitingSettlement');
      this.compositionState = 'Idle';
      
      // If there were pending commands queued during composition, drain them now!
      if (wasAwaitingSettlement || this.pendingCommands.length > 0) {
        this.drainPendingCommands(finalCommittedExtent);
      }
      
      return {
        type: 'Committed',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        state: this.compositionState
      };
    }
    
    // -----------------------------------------------------------------------
    // unmark: Clear marked range and accept text (§4.4, Defect C)
    // -----------------------------------------------------------------------
    if (payload.type === 'unmark') {
      if (this.compositionState === 'Idle' && !this.markedRange) {
        return { type: 'NoChange', reason: 'AlreadyIdle' };
      }
      
      const compBefore = this.compositionBeforeSnapshot || {
        text: beforeText,
        attrs: beforeAttrs,
        selection: beforeSelection,
        sessionStartOffset: this.markedRange ? this.markedRange[0] : 0
      };
      
      // The text currently in the marked range is accepted as committed verbatim!
      const finalCommittedExtent = this.markedRange
        ? [compBefore.sessionStartOffset, this.markedRange[1]]
        : [compBefore.sessionStartOffset, compBefore.sessionStartOffset];
      this.lastSettledExtent = finalCommittedExtent;
      this.lastSettledSessionId = this.activeSessionId;
      
      // Record composition history group from pre-composition snapshot
      this.undoStack.push({
        group: 'Composition',
        beforeText: compBefore.text,
        beforeAttrs: compBefore.attrs,
        beforeSelection: compBefore.selection,
        afterText: this.docText,
        afterAttrs: this.docAttributes.map(a => ({ ...a })),
        afterSelection: [...this.selection],
        sessionExtent: [...finalCommittedExtent]
      });
      this.redoStack = [];
      
      // Clear transient composition state
      this.markedRange = null;
      this.compositionBeforeSnapshot = null;
      this.frozenPrefixUnits = [];
      this.baselineUnits = null;
      const finishedSessionId = this.activeSessionId;
      this.activeSessionId = null;
      
      // Check adapter settlement contract:
      // If payload explicitly specifies { settled: false }, pending commands wait for an explicit settlement fence.
      // Otherwise, unmark completes settlement naturally!
      const isSettled = payload.settled !== false;
      if (isSettled) {
        this.compositionState = 'Idle';
        this.drainPendingCommands(finalCommittedExtent);
      } else {
        this.compositionState = 'AwaitingSettlement';
      }
      
      return {
        type: 'Unmarked',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        state: this.compositionState,
        settled: isSettled
      };
    }
    
    // -----------------------------------------------------------------------
    // cancel: Composition aborted / cancelled (§4.4)
    // -----------------------------------------------------------------------
    if (payload.type === 'cancel') {
      let finalCommittedExtent = null;
      const finishedSessionId = this.activeSessionId;
      
      if (this.frozenPrefixUnits.length > 0) {
        // Invariant I08: Frozen prefix MUST survive cancellation!
        // Suffix candidate in markedRange is removed/replaced, but frozen prefix remains committed.
        const [markedStart, markedEnd] = this.markedRange || [this.sessionStartOffset, this.docText.length];
        const replacementText = payload.text !== undefined ? payload.text : "";
        
        const newText = this.docText.slice(0, markedStart) + replacementText + this.docText.slice(markedEnd);
        const newAttrs = [
          ...this.docAttributes.slice(0, markedStart),
          ...new Array(replacementText.length).fill(null).map(() => ({})),
          ...this.docAttributes.slice(markedEnd)
        ];
        
        const compBefore = this.compositionBeforeSnapshot || {
          text: beforeText,
          attrs: beforeAttrs,
          selection: beforeSelection,
          sessionStartOffset: this.sessionStartOffset
        };
        
        finalCommittedExtent = [this.sessionStartOffset, this.sessionStartOffset + this.frozenPrefixUnits.length + replacementText.length];
        this.lastSettledExtent = finalCommittedExtent;
        this.lastSettledSessionId = finishedSessionId;
        
        // Push single composition history group representing the accepted partial commit(s)
        this.undoStack.push({
          group: 'Composition',
          beforeText: compBefore.text,
          beforeAttrs: compBefore.attrs,
          beforeSelection: compBefore.selection,
          afterText: newText,
          afterAttrs: newAttrs,
          afterSelection: [markedStart + replacementText.length, markedStart + replacementText.length],
          sessionExtent: [...finalCommittedExtent]
        });
        this.redoStack = [];
        
        this.docText = newText;
        this.docAttributes = newAttrs;
        this.selection = [markedStart + replacementText.length, markedStart + replacementText.length];
        this.documentRevision++;
      } else if (this.compositionBeforeSnapshot) {
        // No partial commits: cleanly restore pre-composition baseline
        this.docText = this.compositionBeforeSnapshot.text;
        this.docAttributes = this.compositionBeforeSnapshot.attrs.map(a => ({ ...a }));
        this.selection = [...this.compositionBeforeSnapshot.selection];
        this.documentRevision++;
      }
      
      this.compositionState = 'Idle';
      this.markedRange = null;
      this.compositionBeforeSnapshot = null;
      this.frozenPrefixUnits = [];
      this.baselineUnits = null;
      this.activeSessionId = null;
      
      // Cancel pending commands targeting active composition
      for (const item of this.pendingCommands) {
        if (item.cmd.target === 'composing' || item.cmd.target?.type === 'CompositionTarget') {
          item.cancelled = true;
          item.cancelReason = 'CompositionCancelled';
        }
      }
      this.pendingCommands = this.pendingCommands.filter(i => !i.cancelled);
      
      return {
        type: 'Cancelled',
        documentRevision: this.documentRevision,
        sessionRevision: this.sessionRevision,
        text: this.docText,
        finalExtent: finalCommittedExtent,
        state: this.compositionState
      };
    }
    
    return { type: 'NoChange' };
  }
  
  // Application command dispatch (§4.5, Defect A)
  dispatchAppCommand(cmd) {
    // If composition is active (Composing) OR already AwaitingSettlement,
    // commands requiring settlement MUST remain queued in strict FIFO order!
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const ticketId = `ticket-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      this.pendingCommands.push({
        ticketId,
        cmd,
        epoch: this.epoch,
        sessionId: this.activeSessionId
      });
      this.compositionState = 'AwaitingSettlement';
      return { status: 'Deferred', ticketId };
    }
    
    // Otherwise execute immediately
    return this.executeFormatCommand(cmd);
  }
  
  drainPendingCommands(resolvedCompositionExtent = null) {
    while (this.pendingCommands.length > 0) {
      const item = this.pendingCommands.shift();
      if (item.cancelled) continue;
      if (item.epoch < this.epoch) {
        // Expire command from stale connection epoch
        continue;
      }
      this.executeFormatCommand(item.cmd, resolvedCompositionExtent);
    }
  }
  
  executeFormatCommand(cmd, resolvedRange = null) {
    const beforeText = this.docText;
    const beforeAttrs = this.docAttributes.map(a => ({ ...a }));
    const beforeSelection = [...this.selection];
    
    let start = 0;
    let end = 0;
    
    // Resolve target extent (§4.5, Defect B):
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
        // Fallback to current selection if no composition extent available
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
    
    // Empty range formatting (§5.2, R05, Defect B edge case):
    // Formatting an empty range updates typing intent only; it produces NO document revision bump
    // and NO history group!
    if (start === end) {
      this.sessionRevision++;
      return {
        status: 'NoChange',
        reason: 'EmptyRange',
        typingIntent: { ...cmd.style }
      };
    }
    
    // Check for net no-op change (§7, Invariant I16, R08):
    let hasChange = false;
    const newAttrs = this.docAttributes.map(a => ({ ...a }));
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
    
    // Save distinct Formatting history group (§4.5, §7, R05)
    this.undoStack.push({
      group: 'Formatting',
      beforeText,
      beforeAttrs,
      beforeSelection,
      afterText: this.docText,
      afterAttrs: newAttrs,
      afterSelection: [start, end],
      range: [start, end]
    });
    this.redoStack = [];
    
    return { status: 'Applied', revision: this.documentRevision, formattedRange: [start, end] };
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
    if (this.undoStack.length === 0) return null;
    const entry = this.undoStack.pop();
    this.redoStack.push(entry);
    
    this.docText = entry.beforeText;
    this.docAttributes = entry.beforeAttrs.map(a => ({ ...a }));
    this.selection = entry.beforeSelection ? [...entry.beforeSelection] : [0, 0];
    this.documentRevision++;
    this.sessionRevision++;
    
    if (entry.group === 'Composition') {
      this.compositionState = 'Idle';
      this.markedRange = null;
      this.compositionBeforeSnapshot = null;
      this.frozenPrefixUnits = [];
      this.baselineUnits = null;
    }
    
    return entry;
  }
  
  redo() {
    if (this.redoStack.length === 0) return null;
    const entry = this.redoStack.pop();
    this.undoStack.push(entry);
    
    this.docText = entry.afterText;
    this.docAttributes = entry.afterAttrs.map(a => ({ ...a }));
    this.selection = entry.afterSelection ? [...entry.afterSelection] : [0, 0];
    this.documentRevision++;
    this.sessionRevision++;
    return entry;
  }
}
