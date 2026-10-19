const fs = require('fs');
const path = require('path');

const srcPath = path.join(__dirname, 'sources', 'run-11-candidate-b-coordinator.mjs');
let code = fs.readFileSync(srcPath, 'utf8');

// 1. Add RequestEnvelope before CandidateBCoordinator
const envelopeDef = `// Immutable Request Envelope (§4.1, Probe 1)
export class RequestEnvelope {
  constructor({
    ticketId,
    origin = 'external',
    epoch = 0,
    seq = null,
    sessionId = null,
    basisRevision = 0,
    basisCoordinate = null,
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
    this.payload = payload ? Object.freeze(JSON.parse(JSON.stringify(payload))) : null;
    this.fn = fn;
    Object.freeze(this);
  }
}
`;

code = code.replace('export class CandidateBCoordinator {', envelopeDef + '\nexport class CandidateBCoordinator {');

// 2. In constructor, initialize working state, listeners, and published snapshot
const constructorTarget = `    this._reentrantMutationQueue = [];
    this._inTransaction = false;
  }`;

const constructorReplacement = `    this._reentrantMutationQueue = [];
    this._inTransaction = false;
    this._isDrainingQueue = false;

    // Working fields for Staging / Publication boundary (§4.1, §4.3, Probe 2)
    this._epoch = 0;
    this._connectionState = 'Active';
    this._selection = [...initialSelection];
    this._documentRevision = 0;
    this._sessionRevision = 0;
    this._compositionState = 'Idle';

    this._willChangeListeners = [];
    this._didChangeListeners = [];
    this._rollbackListeners = [];

    // Coherent Published Snapshot
    this._publishedState = {
      docText: initialText,
      docAttributes: initialAttrs ? cloneAttrs(initialAttrs) : Array(initialText.length).fill({}),
      selection: [...initialSelection],
      markedRange: null,
      compositionState: 'Idle',
      documentRevision: 0,
      sessionRevision: 0,
      epoch: 0,
      connectionState: 'Active',
    };
  }`;

code = code.replace(constructorTarget, constructorReplacement);

// 3. Update _cloneState and _restoreState
const cloneTarget = `  // Deep clone of coordinator state for atomic rollback on failure injection
  _cloneState() {
    return {
      epoch: this.epoch,
      connectionState: this.connectionState,
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
      selection: [...this.selection],
      sessionIslands: JSON.parse(JSON.stringify(this.sessionIslands)),
      sessionCommittedRanges: JSON.parse(JSON.stringify(this.sessionCommittedRanges)),
      documentRevision: this.documentRevision,
      sessionRevision: this.sessionRevision,
      compositionState: this.compositionState,
      undoStack: JSON.parse(JSON.stringify(this.undoStack)),
      redoStack: JSON.parse(JSON.stringify(this.redoStack)),
      commandOutcomes: new Map(Array.from(this.commandOutcomes.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))])),
      typingIntent: { ...this.typingIntent },
      consumedSettlementEvidence: new Set(this.consumedSettlementEvidence),
      _reentrantMutationQueue: this._reentrantMutationQueue.map(item => ({ ...item })),
      _inTransaction: this._inTransaction
    };
  }

  _restoreState(snap) {
    this.epoch = snap.epoch;
    this.connectionState = snap.connectionState;
    this.sequenceCounter = snap.sequenceCounter;
    this.receiptCache = snap.receiptCache;
    this.epochHighWaterMarks = snap.epochHighWaterMarks;
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
    this.consumedSettlementEvidence = snap.consumedSettlementEvidence ? new Set(snap.consumedSettlementEvidence) : new Set();
    // Preserve pending reentrant mutations enqueued during transaction so they execute against restored baseline (§4.1, §4.3)
    if (!this._reentrantMutationQueue || this._reentrantMutationQueue.length === 0) {
      this._reentrantMutationQueue = snap._reentrantMutationQueue ? snap._reentrantMutationQueue.map(item => ({ ...item })) : [];
    }
    this._inTransaction = snap._inTransaction;
  }`;

const cloneReplacement = `  addWillChangeListener(fn) { this._willChangeListeners.push(fn); }
  addDidChangeListener(fn) { this._didChangeListeners.push(fn); }
  addRollbackListener(fn) { this._rollbackListeners.push(fn); }

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
      _publishedState: {
        docText: this._publishedState.docText,
        docAttributes: cloneAttrs(this._publishedState.docAttributes),
        selection: [...this._publishedState.selection],
        markedRange: this._publishedState.markedRange ? [...this._publishedState.markedRange] : null,
        compositionState: this._publishedState.compositionState,
        documentRevision: this._publishedState.documentRevision,
        sessionRevision: this._publishedState.sessionRevision,
        epoch: this._publishedState.epoch,
        connectionState: this._publishedState.connectionState,
      }
    };
  }

  _restoreState(snap) {
    this._epoch = snap._epoch;
    this._connectionState = snap._connectionState;
    this.sequenceCounter = snap.sequenceCounter;
    this.receiptCache = snap.receiptCache;
    this.epochHighWaterMarks = snap.epochHighWaterMarks;
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
    this._publishedState = snap._publishedState;
    // Preserve pending reentrant mutations enqueued during transaction so they execute against restored baseline (§4.1, §4.3)
    if (!this._reentrantMutationQueue || this._reentrantMutationQueue.length === 0) {
      this._reentrantMutationQueue = snap._reentrantMutationQueue ? [...snap._reentrantMutationQueue] : [];
    }
    this._inTransaction = snap._inTransaction;
  }`;

code = code.replace(cloneTarget, cloneReplacement);

// 4. Update projections to expose published snapshot externally while retaining working projections internally
const projTarget = `  get docText() {
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
  }`;

const projReplacement = `  // Staged Working Projections
  _getWorkingVisibleUnits() {
    return this.getVisibleUnits();
  }

  _getWorkingDocText() {
    return this._getWorkingVisibleUnits().map(u => u.char).join('');
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
      docText: this._getWorkingDocText(),
      docAttributes: cloneAttrs(this._getWorkingDocAttributes()),
      selection: [...this._selection],
      markedRange: this._getWorkingMarkedRange(),
      compositionState: this._compositionState,
      documentRevision: this._documentRevision,
      sessionRevision: this._sessionRevision,
      epoch: this._epoch,
      connectionState: this._connectionState,
    };
  }

  // Coherent Published Snapshot Getters & Setters (§4.1, §4.3, Probe 2)
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
    if (!this._inTransaction) {
      this._publishedState.selection = [...this._selection];
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
    if (!this._inTransaction) {
      this._publishedState.compositionState = val;
    }
  }

  get documentRevision() {
    return this._publishedState.documentRevision;
  }

  set documentRevision(val) {
    this._documentRevision = val;
    if (!this._inTransaction) {
      this._publishedState.documentRevision = val;
    }
  }

  get sessionRevision() {
    return this._publishedState.sessionRevision;
  }

  set sessionRevision(val) {
    this._sessionRevision = val;
    if (!this._inTransaction) {
      this._publishedState.sessionRevision = val;
    }
  }

  get epoch() {
    return this._publishedState.epoch;
  }

  set epoch(val) {
    this._epoch = val;
    if (!this._inTransaction) {
      this._publishedState.epoch = val;
    }
  }

  get connectionState() {
    return this._publishedState.connectionState;
  }

  set connectionState(val) {
    this._connectionState = val;
    if (!this._inTransaction) {
      this._publishedState.connectionState = val;
    }
  }`;

code = code.replace(projTarget, projReplacement);

// 5. Update _resolveTargetDescriptor to use _getWorkingLength and _selection
code = code.replace(`  _resolveTargetDescriptor(payload) {
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
        const sel = this.selection || [0, 0];`,
`  _resolveTargetDescriptor(payload) {
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
        const sel = this._selection || [0, 0];`);

// 6. Update applyInput and _runInTransaction
const applyInputAndTxTarget = `  applyInput(payload, seq = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }

    // Connection Callback Epoch Admission (Finding F & Defect 3):
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
      if (payload.epoch < this.epoch) {
        return { status: 'Rejected', reason: 'StaleEpoch' };
      }
      if (payload.epoch > this.epoch) {
        return { status: 'Rejected', reason: 'FutureEpoch' };
      }
    } else if (payload.isNativeCallback) {
      return { status: 'Rejected', reason: 'MissingConnectionEpoch' };
    }

    const callbackEpoch = (payload.epoch !== undefined && payload.epoch !== null) ? payload.epoch : this.epoch;
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

    const actionResult = this._runInTransaction(payload.type, () => {
      let result;
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
        case 'dispatchCommand':
        case 'dispatchAppCommand':
          result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
          break;
        case 'queueCommand':
          if (this.compositionState === 'Idle') {
            result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
          } else {
            result = this._queueCommand(payload.cmd, payload.target, payload.injectFailureAt);
          }
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
          result = this._internalUndo(payload.syncBoundary, payload.settlementEvidence);
          break;
        case 'redo':
          result = this._internalRedo();
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
          result = { status: 'Rejected', reason: \`UnknownEvent:\${payload.type}\` };
      }
      return result;
    }, payload.injectFailureAt);

    if (seq !== null && actionResult && actionResult.status !== 'Rejected') {
      const stampKey = \`\${callbackEpoch}:\${seq}\`;
      this.receiptCache.set(stampKey, actionResult);
      const curHwm = this.epochHighWaterMarks.get(callbackEpoch) || 0;
      if (seq > curHwm) {
        this.epochHighWaterMarks.set(callbackEpoch, seq);
      }
    }
    return actionResult;
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
    const preTxSnapshot = this._cloneState();
    const prevDocText = this.docText;
    const prevDocAttrs = cloneAttrs(this.docAttributes);

    try {
      const result = fn();

      if (injectFailureAt === 'beforeReceiptPublication') {
        throw new Error('InjectedFailure:beforeReceiptPublication');
      }

      if (result && result.status === 'Rejected') {
        this._restoreState(preTxSnapshot);
        if (result.ticketId) {
          this.commandOutcomes.set(result.ticketId, { status: 'Rejected', reason: result.reason });
        }
        return result;
      }

      // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
      const newDocText = this.docText;
      const newDocAttrs = this.docAttributes;
      if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
        this.documentRevision++;
      }

      // Branch invalidation: editing operations clear redo stack at the operation level
      // when the edit occurs, preserving redo entries from subsequent queued Undos.

      // Synchronize receipts with actual published document revision and selection
      if (result && result.status !== 'Rejected') {
        if (result.documentRevision !== undefined) {
          result.documentRevision = this.documentRevision;
        }
        if (result.sessionRevision !== undefined) {
          result.sessionRevision = this.sessionRevision;
        }
        if (result.selection !== undefined) {
          result.selection = [...this.selection];
        }
      }

      return result;
    } catch (e) {
      this._restoreState(preTxSnapshot);
      return { status: 'Rejected', reason: e.message || 'TransactionFailure' };
    } finally {
      this._inTransaction = false;
      while (this._reentrantMutationQueue.length > 0) {
        const nextMut = this._reentrantMutationQueue.shift();
        const mRes = this._runInTransaction(nextMut.actionName, nextMut.fn);
        this.commandOutcomes.set(nextMut.ticketId, mRes);
      }
    }
  }`;

const applyInputAndTxReplacement = `  _dispatchPayload(payload) {
    const prop = this._computeProposedPostLength(payload);
    let result;
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
      case 'dispatchCommand':
      case 'dispatchAppCommand':
        result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
        break;
      case 'queueCommand':
        if (this._compositionState === 'Idle') {
          result = this._internalDispatchCommand(payload.cmd, payload.target, payload.injectFailureAt);
        } else {
          result = this._queueCommand(payload.cmd, payload.target, payload.injectFailureAt);
        }
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
        result = this._internalUndo(payload.syncBoundary, payload.settlementEvidence);
        break;
      case 'redo':
        result = this._internalRedo();
        break;
      case 'setSelection':
        if (payload.selection && this._validateSelection(payload.selection, this._getWorkingLength())) {
          this.selection = [...payload.selection];
          result = { type: 'SelectionChanged', status: 'Accepted', selection: [...this.selection] };
        } else {
          result = { status: 'Rejected', reason: 'InvalidSelection' };
        }
        break;
      default:
        result = { status: 'Rejected', reason: \`UnknownEvent:\${payload.type}\` };
    }
    return result;
  }

  applyInput(payload, seq = null) {
    if (this._connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }

    // Freeze deep clone of payload immediately to prevent caller mutation after enqueue (§4.1, Probe 1)
    const frozenPayload = Object.freeze(JSON.parse(JSON.stringify(payload)));

    // Connection Callback Epoch Admission (Finding F & Defect 3):
    // Epoch validation MUST strictly precede duplicate lookup!
    const isNativeCallback = [
      'startComposition', 'preedit', 'commit', 'partialCommit',
      'unmark', 'completeSettlement', 'cancel', 'cancelUncommitted',
      'applicationAbort', 'targetExpansion', 'retarget', 'nativeRemoval', 'onFocusLost'
    ].includes(frozenPayload.type);

    if (frozenPayload.epoch !== undefined && frozenPayload.epoch !== null) {
      if (typeof frozenPayload.epoch !== 'number' || !Number.isInteger(frozenPayload.epoch) || frozenPayload.epoch < 0) {
        return { status: 'Rejected', reason: 'MalformedConnectionEpoch' };
      }
      if (frozenPayload.epoch < this._epoch) {
        return { status: 'Rejected', reason: 'StaleEpoch' };
      }
      if (frozenPayload.epoch > this._epoch) {
        return { status: 'Rejected', reason: 'FutureEpoch' };
      }
    } else if (frozenPayload.isNativeCallback) {
      return { status: 'Rejected', reason: 'MissingConnectionEpoch' };
    }

    const callbackEpoch = (frozenPayload.epoch !== undefined && frozenPayload.epoch !== null) ? frozenPayload.epoch : this._epoch;
    if (seq !== null) {
      if (typeof seq !== 'number' || !Number.isInteger(seq) || seq <= 0) {
        return { status: 'Rejected', reason: 'MalformedSequenceStamp' };
      }
      const stampKey = \`\${callbackEpoch}:\${seq}\`;
      if (this.receiptCache.has(stampKey)) {
        return { status: 'Duplicate', cachedReceipt: this.receiptCache.get(stampKey) };
      }
      const hwm = this.epochHighWaterMarks.get(callbackEpoch) || 0;
      if (seq <= hwm && !this.receiptCache.has(stampKey)) {
        return { status: 'DuplicateReceiptExpired' };
      }
    }

    if (frozenPayload.injectFailure && !frozenPayload.injectFailureAt) {
      return { status: 'Rejected', reason: 'InjectedFailure' };
    }

    // Upfront validation of input parameters before mutation
    const curLen = this._getWorkingLength();
    if (frozenPayload.range !== undefined && frozenPayload.range !== null) {
      if (!this._validateRange(frozenPayload.range, curLen)) {
        return { status: 'Rejected', reason: 'InvalidTargetRange' };
      }
    }

    const prop = this._computeProposedPostLength(frozenPayload);
    if (!prop.valid) {
      return { status: 'Rejected', reason: 'InvalidTargetRange' };
    }

    // Selection validation against proposed post-edit document length (Finding D)
    if (frozenPayload.selection !== undefined && frozenPayload.selection !== null) {
      if (!this._validateSelection(frozenPayload.selection, prop.proposedLen)) {
        return { status: 'Rejected', reason: 'InvalidSelection' };
      }
    }

    if (frozenPayload.type === 'partialCommit') {
      const k = frozenPayload.length !== undefined ? frozenPayload.length : frozenPayload.committedLength;
      if (!Number.isInteger(k) || k < 0) {
        return { status: 'Rejected', reason: 'InvalidPartialCommitCount' };
      }
    }

    // If currently inside an active transaction, capture as immutable RequestEnvelope (§4.1, Probe 1)
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'external',
        epoch: callbackEpoch,
        seq,
        sessionId: frozenPayload.sessionId || null,
        basisRevision: this._documentRevision,
        basisCoordinate: frozenPayload.range ? [...frozenPayload.range] : (frozenPayload.selection ? [...frozenPayload.selection] : null),
        payload: frozenPayload
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }

    const actionResult = this._runInTransaction(frozenPayload.type, () => {
      return this._dispatchPayload(frozenPayload);
    }, frozenPayload.injectFailureAt);

    if (seq !== null && actionResult && actionResult.status !== 'Rejected') {
      const stampKey = \`\${callbackEpoch}:\${seq}\`;
      this.receiptCache.set(stampKey, actionResult);
      const curHwm = this.epochHighWaterMarks.get(callbackEpoch) || 0;
      if (seq > curHwm) {
        this.epochHighWaterMarks.set(callbackEpoch, seq);
      }
    }
    return actionResult;
  }

  // Centralized Transaction Runner (§4.3, Defect 2, Probe 2)
  _runInTransaction(actionName, fn, injectFailureAt = null) {
    if (this._connectionState === 'Closed' && actionName !== 'dispose') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'reentrant',
        epoch: this._epoch,
        seq: null,
        sessionId: this.activeSessionId,
        basisRevision: this._documentRevision,
        payload: { type: actionName, injectFailureAt },
        fn
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
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

      if (injectFailureAt === 'beforeReceiptPublication') {
        throw new Error('InjectedFailure:beforeReceiptPublication');
      }

      if (result && result.status === 'Rejected') {
        this._restoreState(preTxSnapshot);
        if (result.ticketId) {
          this.commandOutcomes.set(result.ticketId, { status: 'Rejected', reason: result.reason });
        }
        for (const listener of this._rollbackListeners) {
          try { listener(this._publishedState, result.reason); } catch (_) {}
        }
        return result;
      }

      // Check DocumentRevision biconditional: advances iff semantic text/attrs changed
      const newDocText = this._getWorkingDocText();
      const newDocAttrs = this._getWorkingDocAttributes();
      if (newDocText !== prevDocText || !areAttrsEqual(newDocAttrs, prevDocAttrs)) {
        this._documentRevision++;
      }

      // Staging / Publication Boundary Commit (§4.1, §4.3, Probe 2)
      this._publishWorkingState();

      // Synchronize receipts with actual published document revision and selection
      if (result && result.status !== 'Rejected') {
        if (result.documentRevision !== undefined) {
          result.documentRevision = this.documentRevision;
        }
        if (result.sessionRevision !== undefined) {
          result.sessionRevision = this.sessionRevision;
        }
        if (result.selection !== undefined) {
          result.selection = [...this.selection];
        }
      }

      for (const listener of this._didChangeListeners) {
        try { listener(this._publishedState); } catch (_) {}
      }

      return result;
    } catch (e) {
      this._restoreState(preTxSnapshot);
      for (const listener of this._rollbackListeners) {
        try { listener(this._publishedState, e.message); } catch (_) {}
      }
      return { status: 'Rejected', reason: e.message || 'TransactionFailure' };
    } finally {
      this._inTransaction = false;
      this._drainReentrantQueue();
    }
  }

  // Single Queue-Drain Owner with Bounded Scheduling (§4.1, Probe 1, Probe 3)
  _drainReentrantQueue() {
    if (this._isDrainingQueue) return;
    this._isDrainingQueue = true;

    const schedulingBudget = 1000;
    let budget = 0;

    try {
      while (this._reentrantMutationQueue.length > 0) {
        if (++budget > schedulingBudget) {
          console.warn('Reentrant scheduling budget exhausted (1000 iterations); preserving remaining requests in FIFO order without dropping input');
          break;
        }

        const envelope = this._reentrantMutationQueue.shift();

        // 1. Connection Closed check
        if (this._connectionState === 'Closed' && envelope.payload?.type !== 'dispose') {
          const term = { status: 'Rejected', reason: 'ConnectionClosed' };
          this.commandOutcomes.set(envelope.ticketId, term);
          continue;
        }

        // 2. Epoch validation against CURRENT execution epoch (§4.1, Probe 1)
        if (envelope.epoch !== undefined && envelope.epoch !== null) {
          if (envelope.epoch < this._epoch) {
            const term = { status: 'Rejected', reason: 'StaleEpoch' };
            this.commandOutcomes.set(envelope.ticketId, term);
            if (envelope.seq !== null) {
              this.receiptCache.set(\`\${envelope.epoch}:\${envelope.seq}\`, term);
            }
            continue;
          }
          if (envelope.epoch > this._epoch) {
            const term = { status: 'Rejected', reason: 'FutureEpoch' };
            this.commandOutcomes.set(envelope.ticketId, term);
            continue;
          }
        }

        // 3. Duplicate check against updated receiptCache
        if (envelope.seq !== null) {
          const stampKey = \`\${envelope.epoch}:\${envelope.seq}\`;
          if (this.receiptCache.has(stampKey)) {
            const term = { status: 'Duplicate', cachedReceipt: this.receiptCache.get(stampKey) };
            this.commandOutcomes.set(envelope.ticketId, term);
            continue;
          }
        }

        // 4. Session applicability check
        if (envelope.sessionId && this.activeSessionId && envelope.sessionId !== this.activeSessionId) {
          const term = { status: 'Rejected', reason: 'SessionMismatch' };
          this.commandOutcomes.set(envelope.ticketId, term);
          continue;
        }

        // 5. Target / coordinate revalidation
        if (envelope.payload && envelope.payload.range !== undefined && envelope.payload.range !== null) {
          const curLen = this._getWorkingLength();
          if (!this._validateRange(envelope.payload.range, curLen)) {
            const term = { status: 'Rejected', reason: 'InvalidTargetRange' };
            this.commandOutcomes.set(envelope.ticketId, term);
            continue;
          }
        }

        // 6. Execute deferred work
        let execResult;
        if (envelope.fn) {
          execResult = this._runInTransaction(envelope.payload?.type || 'deferred', envelope.fn, envelope.payload?.injectFailureAt);
        } else if (envelope.payload) {
          execResult = this._runInTransaction(envelope.payload.type, () => {
            return this._dispatchPayload(envelope.payload);
          }, envelope.payload.injectFailureAt);
        }

        // 7. Publish terminal receipt exactly once (§4.1)
        this.commandOutcomes.set(envelope.ticketId, execResult);
        if (envelope.seq !== null && execResult && execResult.status !== 'Rejected') {
          const stampKey = \`\${envelope.epoch}:\${envelope.seq}\`;
          this.receiptCache.set(stampKey, execResult);
          const curHwm = this.epochHighWaterMarks.get(envelope.epoch) || 0;
          if (envelope.seq > curHwm) {
            this.epochHighWaterMarks.set(envelope.epoch, envelope.seq);
          }
        }
      }
    } finally {
      this._isDrainingQueue = false;
    }
  }`;

code = code.replace(applyInputAndTxTarget, applyInputAndTxReplacement);

// 7. In _completeSettlement, REMOVE spurious clearing of _reentrantMutationQueue (Probe 3)
const settlementDisposedLoop = `    for (const rMut of this._reentrantMutationQueue) {
      this.commandOutcomes.set(rMut.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this._reentrantMutationQueue = [];`;

code = code.replace(settlementDisposedLoop, '// Eligible reentrant commands are preserved across settlement (Probe 3)');

// 8. In _dispose, cancel pending reentrant mutations as Disposed
const disposeTarget = `    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this.settlementObligation.pendingCommands = [];`;

const disposeReplacement = `    for (const cmd of this.settlementObligation.pendingCommands) {
      cmd.status = 'Cancelled';
      this.commandOutcomes.set(cmd.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this.settlementObligation.pendingCommands = [];
    for (const env of this._reentrantMutationQueue) {
      this.commandOutcomes.set(env.ticketId, { status: 'Cancelled', reason: 'Disposed' });
    }
    this._reentrantMutationQueue = [];`;

code = code.replace(disposeTarget, disposeReplacement);

// 9. Update internal methods in CandidateBCoordinator to read working text where needed
// In _retarget:
code = code.replace(`    const [u, v] = range;
    if (u < 0 || v > this.docText.length || u > v) {`,
`    const [u, v] = range;
    if (u < 0 || v > this._getWorkingLength() || u > v) {`);

// In _internalDispatchCommand:
code = code.replace(`    const curDocText = this.docText;
    const curLen = curDocText.length;
    let targetRanges = [];

    if (!target || target.type === 'SelectionTarget') {
      const s = this.selection || [0, 0];`,
`    const curDocText = this._getWorkingDocText();
    const curLen = curDocText.length;
    let targetRanges = [];

    if (!target || target.type === 'SelectionTarget') {
      const s = this._selection || [0, 0];`);

// In _undo and _redo:
code = code.replace(`  _internalUndo(syncBoundary = false, settlementEvidence = null) {`,
`  undo(syncBoundary = false, settlementEvidence = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'application',
        epoch: this._epoch,
        seq: null,
        sessionId: this.activeSessionId,
        basisRevision: this._documentRevision,
        payload: { type: 'undo', syncBoundary, settlementEvidence }
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    return this._runInTransaction('undo', () => {
      return this._internalUndo(syncBoundary, settlementEvidence);
    });
  }

  _internalUndo(syncBoundary = false, settlementEvidence = null) {`);

code = code.replace(`  _internalRedo() {`,
`  redo() {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'application',
        epoch: this._epoch,
        seq: null,
        sessionId: null,
        basisRevision: this._documentRevision,
        payload: { type: 'redo' }
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    return this._runInTransaction('redo', () => {
      return this._internalRedo();
    });
  }

  _internalRedo() {`);

// In dispatchCommand:
code = code.replace(`  dispatchCommand(cmd, target = null, injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (!cmd || typeof cmd !== 'object' || !cmd.type) {
      return { status: 'Rejected', reason: 'InvalidCommand' };
    }
    if (cmd.type !== 'Format') {
      return { status: 'Rejected', reason: \`UnknownCommandType:\${cmd.type}\` };
    }
    if (target && target.type && !['SelectionTarget', 'TrackedRange', 'CompositionTarget'].includes(target.type)) {
      return { status: 'Rejected', reason: \`UnknownTargetType:\${target.type}\` };
    }
    return this._runInTransaction('dispatchCommand', () => {
      return this._internalDispatchCommand(cmd, target, injectStage);
    }, injectStage);
  }`,
`  dispatchCommand(cmd, target = null, injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (!cmd || typeof cmd !== 'object' || !cmd.type) {
      return { status: 'Rejected', reason: 'InvalidCommand' };
    }
    if (cmd.type !== 'Format') {
      return { status: 'Rejected', reason: \`UnknownCommandType:\${cmd.type}\` };
    }
    if (target && target.type && !['SelectionTarget', 'TrackedRange', 'CompositionTarget'].includes(target.type)) {
      return { status: 'Rejected', reason: \`UnknownTargetType:\${target.type}\` };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'application',
        epoch: this._epoch,
        seq: null,
        sessionId: target && target.type === 'CompositionTarget' ? target.sessionId : null,
        basisRevision: this._documentRevision,
        basisCoordinate: target && target.range ? [...target.range] : null,
        payload: { type: 'dispatchCommand', cmd, target, injectStage }
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    return this._runInTransaction('dispatchCommand', () => {
      return this._internalDispatchCommand(cmd, target, injectStage);
    }, injectStage);
  }`);

// In openNewConnection:
code = code.replace(`  openNewConnection(injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('openNewConnection', () => {
      return this._openNewConnection(injectStage);
    }, injectStage);
  }`,
`  openNewConnection(injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'application',
        epoch: this._epoch,
        seq: null,
        sessionId: null,
        basisRevision: this._documentRevision,
        payload: { type: 'openNewConnection', injectStage }
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    return this._runInTransaction('openNewConnection', () => {
      return this._openNewConnection(injectStage);
    }, injectStage);
  }`);

// In dispose:
code = code.replace(`  dispose(injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    return this._runInTransaction('dispose', () => {
      return this._dispose(injectStage);
    }, injectStage);
  }`,
`  dispose(injectStage = null) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (this._inTransaction) {
      const ticketId = this.nextTicketId('reentrant');
      const envelope = new RequestEnvelope({
        ticketId,
        origin: 'application',
        epoch: this._epoch,
        seq: null,
        sessionId: null,
        basisRevision: this._documentRevision,
        payload: { type: 'dispose', injectStage }
      });
      this._reentrantMutationQueue.push(envelope);
      return { status: 'Accepted', action: 'QueuedReentrant', ticketId };
    }
    return this._runInTransaction('dispose', () => {
      return this._dispose(injectStage);
    }, injectStage);
  }`);

// Clean up duplicate method definitions that were at the bottom
code = code.replace(`  undo(syncBoundary = false, settlementEvidence = null) {
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
  }`, '');

fs.writeFileSync(path.join(__dirname, 'candidate-b-coordinator.mjs'), code, 'utf8');
console.log('Successfully written candidate-b-coordinator.mjs');
