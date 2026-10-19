const fs = require('fs');
const path = require('path');

const srcPath = path.join(__dirname, 'sources', 'run-11-candidate-b-coordinator.mjs');
let code = fs.readFileSync(srcPath, 'utf8');

// 1. Add RequestEnvelope class before CandidateBCoordinator
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
    payload = null
  }) {
    this.ticketId = ticketId;
    this.origin = origin;
    this.epoch = epoch;
    this.seq = seq;
    this.sessionId = sessionId;
    this.basisRevision = basisRevision;
    this.basisCoordinate = basisCoordinate ? Object.freeze([...basisCoordinate]) : null;
    this.payload = payload ? Object.freeze(JSON.parse(JSON.stringify(payload))) : null;
    Object.freeze(this);
  }
}
`;

code = code.replace('export class CandidateBCoordinator {', envelopeDef + '\nexport class CandidateBCoordinator {');

// 2. In constructor, add fields
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

// 3. Update _cloneState and _restoreState to track _publishedState and working fields
const cloneStateTarget = `  _cloneState() {
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

const cloneStateReplacement = `  _cloneState() {
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

code = code.replace(cloneStateTarget, cloneStateReplacement);

// 4. Update projections and add working getters / publication methods
const projectionsTarget = `  get docText() {
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

const projectionsReplacement = `  // Staged Working Projections
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

  // Coherent Published Snapshot Getters (§4.1, §4.3, Probe 2)
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

code = code.replace(projectionsTarget, projectionsReplacement);

fs.writeFileSync(path.join(__dirname, 'candidate-b-coordinator.mjs'), code, 'utf8');
console.log('Saved candidate-b-coordinator.mjs phase 1');
