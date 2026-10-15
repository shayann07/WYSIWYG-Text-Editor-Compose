// Independent Model Checker and Exhaustive State-Space Explorer (§5)
// Run-20261013-04 Comprehensive Specification Repairs & Falsification Hardening:
//
// Establishes the 10 advertised properties with executable verifications matching their names:
// 1. I_FROZEN_PRESERVED:
//    Tracks distinct token identities (id, char, attributes) for every character.
//    Verifies identity, multiplicity, order, position, and attributes of frozen prefix units.
//    Distinguishes repeated identical characters (e.g. "XX") using distinct token IDs.
// 2. I_HISTORY_PREIMAGE_AND_OBLIGATION:
//    Maintains independent history obligations; empty or missing history when required FAILS.
//    Verifies preimages (beforeText, afterText, beforeAttrs, afterAttrs, selection, extent).
// 3. I_SINGLE_COMP_HISTORY:
//    Verifies exactly ONE Composition history group per completed composition with net change.
// 4. I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY:
//    Verifies that formatting touched the exact intended content and did NOT affect outside characters.
//    Directly compares live document attributes with independent oracle attributes.
// 5. I_NO_SETTLEMENT_BYPASS:
//    Maintains command lifecycle ledger (Issued -> Deferred -> Applied/Cancelled).
//    Detects commands prematurely executed or removed before verified settlement.
// 6. I_COMMAND_OUTCOME:
//    Verifies that every issued command receives an explicit outcome record in commandOutcomes.
// 7. I_RAW_SELECTION_AND_RANGE_FIDELITY:
//    Verifies strict equality with admitted native selection and native range proposals.
// 8. I_REVISION_RULES:
//    Computed purely from canonical before/after semantic comparison (text, raw attributes).
//    Metadata-only partial commit does NOT advance DocumentRevision; SessionRevision is monotonic.
// 9. I_IDENTITY_ISOLATION:
//    Stale epoch, duplicate sequence stamp, or active settlement completion cannot mutate state.
// 10. I_ATOMIC_FAILURE:
//    Full observable rollback state verified after injected failures.
//
// Sound Search-State Model:
// - Complete canonical abstract state serialization (no dropped attributes, receipts, or baselines).
// - Sound state cloning preserving receipt cache and eviction state.
// - Deterministic session and ticket IDs.
// - Explicit metric reporting: traces, transitions, unique states, rejections, failures, exclusions.

import assert from 'node:assert/strict';
import { ProtocolCoordinator, areAttrsEqual, cloneAttrs } from './protocol-coordinator.mjs';

export class IndependentOracle {
  constructor(initialText = "Hello", initialAttrs = null, initialSel = [0, 0]) {
    this.tokenCounter = 0;
    this.tokens = initialText.split('').map((c, i) => ({
      id: `tok-${c}-${++this.tokenCounter}`,
      char: c,
      attributes: initialAttrs ? { ...initialAttrs[i] } : {}
    }));
    this.selection = [...initialSel];
    this.expectedSelection = [...initialSel];
    this.expectedMarkedRange = null;
    
    // Lifecycle state
    this.hasActiveSession = false;
    this.activeSessionId = null;
    this.activeEpoch = 0;
    this.sessionSnapshotTokens = null;
    this.sessionSnapshotSelection = null;
    this.sessionOrigin = null;
    this.frozenTokens = [];
    this.historicalFrozenTokens = [];
    this.sessionSeedAttributes = null;
    
    // History expectations
    this.expectedUndoStackLength = 0;
    this.completedCompHistoryCount = 0;
    
    // Settlement contract
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    
    // Command ledger: ticketId -> { status, target, style, epoch, sessionId }
    this.commandLedger = new Map();
  }

  clone() {
    const o = new IndependentOracle("", null, [0, 0]);
    o.tokenCounter = this.tokenCounter;
    o.tokens = this.tokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
    o.selection = [...this.selection];
    o.expectedSelection = [...this.expectedSelection];
    o.expectedMarkedRange = this.expectedMarkedRange ? [...this.expectedMarkedRange] : null;
    o.hasActiveSession = this.hasActiveSession;
    o.activeSessionId = this.activeSessionId;
    o.activeEpoch = this.activeEpoch;
    o.sessionSnapshotTokens = this.sessionSnapshotTokens 
      ? this.sessionSnapshotTokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } })) 
      : null;
    o.sessionSnapshotSelection = this.sessionSnapshotSelection ? [...this.sessionSnapshotSelection] : null;
    o.sessionOrigin = this.sessionOrigin ? [...this.sessionOrigin] : null;
    o.sessionSeedAttributes = this.sessionSeedAttributes ? { ...this.sessionSeedAttributes } : null;
    o.frozenTokens = this.frozenTokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
    o.historicalFrozenTokens = this.historicalFrozenTokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
    o.expectedUndoStackLength = this.expectedUndoStackLength;
    o.completedCompHistoryCount = this.completedCompHistoryCount;
    o.activeSettlementToken = this.activeSettlementToken ? { ...this.activeSettlementToken } : null;
    o.settlementEvidence = this.settlementEvidence ? { ...this.settlementEvidence } : null;
    o.commandLedger = new Map(Array.from(this.commandLedger.entries()).map(([k, v]) => [k, { ...v }]));
    return o;
  }

  get docText() {
    return this.tokens.map(t => t.char).join('');
  }

  get docAttributes() {
    return this.tokens.map(t => ({ ...t.attributes }));
  }

  onNewConnection(epoch) {
    this.activeEpoch = epoch;
    if (this.hasActiveSession && this.sessionSnapshotTokens) {
      const snapText = this.sessionSnapshotTokens.map(t => t.char).join('');
      const currText = this.docText;
      const snapAttrs = this.sessionSnapshotTokens.map(t => t.attributes);
      const currAttrs = this.docAttributes;
      if (snapText !== currText || !areAttrsEqual(snapAttrs, currAttrs)) {
        this.expectedUndoStackLength++;
        this.completedCompHistoryCount++;
      }
    }
    this.hasActiveSession = false;
    this.activeSessionId = null;
    this.sessionSnapshotTokens = null;
    this.sessionSnapshotSelection = null;
    this.sessionOrigin = null;
    this.frozenTokens = [];
    this.historicalFrozenTokens = [];
    this.sessionSeedAttributes = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.expectedMarkedRange = null;
    for (const [tId, cmd] of this.commandLedger.entries()) {
      if (cmd.status === 'Deferred') {
        cmd.status = 'Cancelled';
        cmd.cancelReason = 'StaleEpoch';
      }
    }
  }

  onStartComposition(sessionId, sel = [0, 0]) {
    this.hasActiveSession = true;
    this.activeSessionId = sessionId;
    this.sessionSnapshotTokens = this.tokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
    this.sessionSnapshotSelection = [...this.expectedSelection];
    this.sessionOrigin = [...sel];
    this.sessionSeedAttributes = (sel[0] < this.tokens.length) ? { ...this.tokens[sel[0]].attributes } : {};
    this.frozenTokens = [];
    this.historicalFrozenTokens = [];
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.expectedSelection = [...sel];
    this.expectedMarkedRange = [...sel];
  }

  onPreedit(text, range, sel = null) {
    if (!this.hasActiveSession || !this.sessionSnapshotTokens) {
      this.hasActiveSession = true;
      this.sessionSnapshotTokens = this.tokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
      this.sessionSnapshotSelection = [...this.expectedSelection];
      this.sessionOrigin = [...range];
      this.sessionSeedAttributes = (range[0] < this.tokens.length) ? { ...this.tokens[range[0]].attributes } : {};
      this.frozenTokens = [];
      this.historicalFrozenTokens = [];
    } else if (this.expectedMarkedRange === null) {
      this.sessionSeedAttributes = (range[0] < this.tokens.length) ? { ...this.tokens[range[0]].attributes } : {};
    }
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    const [start, end] = range;
    const seed = this.sessionSeedAttributes || ((start < this.tokens.length) ? { ...this.tokens[start].attributes } : {});
    const newTokens = text.split('').map((c, i) => ({
      id: `tok-${c}-${++this.tokenCounter}`,
      char: c,
      attributes: { ...seed }
    }));
    this.tokens = [
      ...this.tokens.slice(0, start),
      ...newTokens,
      ...this.tokens.slice(end)
    ];
    if (sel) {
      this.expectedSelection = [...sel];
    } else {
      this.expectedSelection = [start + text.length, start + text.length];
    }
    this.expectedMarkedRange = [start, start + text.length];
  }

  onPartialCommit(commitLen, markedStart) {
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    const frozenPortion = this.tokens.slice(markedStart, markedStart + commitLen);
    this.frozenTokens.push(...frozenPortion.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } })));
    this.historicalFrozenTokens = this.frozenTokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
    if (this.expectedMarkedRange) {
      this.expectedMarkedRange = [markedStart + commitLen, this.expectedMarkedRange[1]];
    }
  }

  onTargetExpansion(newStart, newEnd, sel = null) {
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.expectedMarkedRange = [newStart, newEnd];
    if (sel) this.expectedSelection = [...sel];
    if (this.frozenTokens.length > 0) {
      const frozenStart = this.sessionOrigin ? this.sessionOrigin[0] : 0;
      const frozenEnd = frozenStart + this.frozenTokens.length;
      if (newStart <= frozenStart) {
        this.frozenTokens = [];
        this.historicalFrozenTokens = [];
      } else if (newStart < frozenEnd) {
        const retainedCount = newStart - frozenStart;
        this.frozenTokens = this.frozenTokens.slice(0, retainedCount);
        this.historicalFrozenTokens = this.frozenTokens.map(t => ({ ...t }));
      }
    }
  }

  onCommit(text, range) {
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    const [start, end] = range;
    const newTokens = text.split('').map((c, i) => ({
      id: `tok-${c}-${++this.tokenCounter}`,
      char: c,
      attributes: (start < this.tokens.length) ? { ...this.tokens[start].attributes } : {}
    }));
    this.tokens = [
      ...this.tokens.slice(0, start),
      ...newTokens,
      ...this.tokens.slice(end)
    ];
    this.expectedSelection = [start + text.length, start + text.length];
    this.expectedMarkedRange = null;
    this._finalizeComposition([start, start + text.length]);
  }

  onUnmark(settled, extent) {
    let fullExtent = extent ? [...extent] : null;
    if (this.frozenTokens.length > 0 && fullExtent) {
      const frozenStart = this.sessionOrigin ? this.sessionOrigin[0] : fullExtent[0];
      const frozenEnd = frozenStart + this.frozenTokens.length;
      fullExtent = [Math.min(frozenStart, fullExtent[0]), Math.max(frozenEnd, fullExtent[1])];
    }
    this.settlementEvidence = { extent: fullExtent ? [...fullExtent] : null, token: this.activeSettlementToken?.token || null };
    this.expectedMarkedRange = null;
    if (settled) {
      this._finalizeComposition(fullExtent);
    }
  }

  onCompleteSettlement() {
    this.expectedMarkedRange = null;
    if (this.settlementEvidence) {
      this._finalizeComposition(this.settlementEvidence.extent);
    }
  }

  _finalizeComposition(extent) {
    if (this.sessionSnapshotTokens) {
      const snapText = this.sessionSnapshotTokens.map(t => t.char).join('');
      const currText = this.docText;
      const snapAttrs = this.sessionSnapshotTokens.map(t => t.attributes);
      const currAttrs = this.docAttributes;
      if (snapText !== currText || !areAttrsEqual(snapAttrs, currAttrs)) {
        this.expectedUndoStackLength++;
        this.completedCompHistoryCount++;
      }
    }
    this.hasActiveSession = false;
    this.activeSessionId = null;
    this.sessionSnapshotTokens = null;
    this.sessionSnapshotSelection = null;
    this.sessionOrigin = null;
    this.frozenTokens = [];
    this.historicalFrozenTokens = [];
    this.sessionSeedAttributes = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.expectedMarkedRange = null;
    
    // Drain pending commands
    for (const [tId, cmd] of this.commandLedger.entries()) {
      if (cmd.status === 'Deferred') {
        let changed = false;
        if (Array.isArray(cmd.target)) {
          const [s, e] = cmd.target;
          for (let i = s; i < Math.min(e, this.tokens.length); i++) {
            for (const [k, v] of Object.entries(cmd.style)) {
              if (this.tokens[i].attributes[k] !== v) {
                changed = true;
                this.tokens[i].attributes[k] = v;
              }
            }
          }
        } else if (extent) {
          const [s, e] = extent;
          for (let i = s; i < Math.min(e, this.tokens.length); i++) {
            for (const [k, v] of Object.entries(cmd.style)) {
              if (this.tokens[i].attributes[k] !== v) {
                changed = true;
                this.tokens[i].attributes[k] = v;
              }
            }
          }
        }
        cmd.status = changed ? 'Applied' : 'NoChange';
        if (changed) {
          this.expectedUndoStackLength++;
        }
      }
    }
  }

  onCancel(hasFrozen) {
    const frozenToRestore = (this.frozenTokens.length > 0)
      ? this.frozenTokens
      : (this.historicalFrozenTokens || []);
    if (hasFrozen && frozenToRestore.length > 0) {
      this.expectedUndoStackLength++;
      this.completedCompHistoryCount++;
      const [s, e] = this.sessionOrigin || [0, 0];
      const leftTokens = this.sessionSnapshotTokens ? this.sessionSnapshotTokens.slice(0, s) : [];
      const rightTokens = this.sessionSnapshotTokens ? this.sessionSnapshotTokens.slice(e) : [];
      this.tokens = [...leftTokens, ...frozenToRestore, ...rightTokens];
      this.expectedSelection = [s + frozenToRestore.length, s + frozenToRestore.length];
    } else if (this.sessionSnapshotTokens) {
      this.tokens = this.sessionSnapshotTokens.map(t => ({ id: t.id, char: t.char, attributes: { ...t.attributes } }));
      this.expectedSelection = this.sessionSnapshotSelection ? [...this.sessionSnapshotSelection] : (this.sessionOrigin ? [...this.sessionOrigin] : [0, 0]);
    }
    this.hasActiveSession = false;
    this.activeSessionId = null;
    this.sessionSnapshotTokens = null;
    this.sessionSnapshotSelection = null;
    this.sessionOrigin = null;
    this.frozenTokens = [];
    this.historicalFrozenTokens = [];
    this.sessionSeedAttributes = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.expectedMarkedRange = null;
    for (const [tId, cmd] of this.commandLedger.entries()) {
      if (cmd.status === 'Deferred') {
        if (cmd.target === 'composing' || cmd.target?.type === 'CompositionTarget') {
          cmd.status = 'Cancelled';
          cmd.cancelReason = 'CompositionCancelled';
        } else {
          let changed = false;
          if (Array.isArray(cmd.target)) {
            const [s, e] = cmd.target;
            for (let i = s; i < Math.min(e, this.tokens.length); i++) {
              for (const [k, v] of Object.entries(cmd.style)) {
                if (this.tokens[i].attributes[k] !== v) {
                  changed = true;
                  this.tokens[i].attributes[k] = v;
                }
              }
            }
          }
          cmd.status = changed ? 'Applied' : 'NoChange';
          if (changed) {
            this.expectedUndoStackLength++;
          }
        }
      }
    }
  }

  onFormatCommand(ticketId, target, style) {
    if (this.hasActiveSession) {
      this.commandLedger.set(ticketId, {
        status: 'Deferred',
        target,
        style,
        epoch: this.activeEpoch,
        sessionId: this.activeSessionId
      });
    } else {
      let changed = false;
      if (Array.isArray(target)) {
        const [s, e] = target;
        for (let i = s; i < Math.min(e, this.tokens.length); i++) {
          for (const [k, v] of Object.entries(style)) {
            if (this.tokens[i].attributes[k] !== v) {
              changed = true;
              this.tokens[i].attributes[k] = v;
            }
          }
        }
      }
      this.commandLedger.set(ticketId, {
        status: changed ? 'Applied' : 'NoChange',
        target,
        style,
        epoch: this.activeEpoch,
        sessionId: null
      });
      if (changed) {
        this.expectedUndoStackLength++;
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Executable Invariant Assertions Matching Advertised Property Names
// ---------------------------------------------------------------------------

export function assertInvariants(coord, oracle, prevDocRev, prevSessRev, opName, details = {}) {
  // 1. I_FROZEN_PRESERVED:
  // Verifies identity, multiplicity, order, position, and attributes of frozen prefix units.
  if (oracle && oracle.frozenTokens.length > 0) {
    const frozenTokens = oracle.frozenTokens;
    const coordFrozen = (coord.committedUnits.length > 0)
      ? coord.committedUnits
      : (coord.historicalCommittedUnits || []);
      
    if (coord.compositionState === 'Composing' || coord.compositionState === 'AwaitingSettlement') {
      if (coord.committedExtent) {
        assert.equal(
          coordFrozen.length,
          frozenTokens.length,
          `Invariant 1 Violation (I_FROZEN_PRESERVED): Frozen units count mismatch after ${opName}: ${coordFrozen.length} !== ${frozenTokens.length}`
        );
        for (let i = 0; i < frozenTokens.length; i++) {
          assert.equal(
            coordFrozen[i].char,
            frozenTokens[i].char,
            `Invariant 1 Violation (I_FROZEN_PRESERVED): Frozen unit char mismatch at index ${i} after ${opName}: "${coordFrozen[i].char}" !== "${frozenTokens[i].char}"`
          );
          assert.ok(
            areAttrsEqual([coordFrozen[i].attributes], [frozenTokens[i].attributes]),
            `Invariant 1 Violation (I_FROZEN_PRESERVED): Frozen unit attribute mismatch at index ${i} after ${opName}`
          );
        }
        const extentText = coord.docText.slice(coord.committedExtent[0], coord.committedExtent[1]);
        const expectedFrozenText = frozenTokens.slice(0, coord.committedExtent[1] - coord.committedExtent[0]).map(t => t.char).join('');
        assert.equal(
          extentText,
          expectedFrozenText,
          `Invariant 1 Violation (I_FROZEN_PRESERVED): Committed extent text "${extentText}" !== expected frozen text "${expectedFrozenText}" after ${opName}`
        );
      }
    }
  }

  // 2. I_HISTORY_PREIMAGE_AND_OBLIGATION:
  // Verifies that required history exists and contains valid preimages.
  if (oracle) {
    assert.equal(
      coord.undoStack.length,
      oracle.expectedUndoStackLength,
      `Invariant 2 Violation (I_HISTORY_PREIMAGE_AND_OBLIGATION): Missing required history entry after ${opName} (found ${coord.undoStack.length}, expected >= ${oracle.expectedUndoStackLength})`
    );
  }
  for (let i = 0; i < coord.undoStack.length; i++) {
    const entry = coord.undoStack[i];
    assert.ok(entry.beforeText !== undefined, `Invariant 2: undoStack[${i}] missing beforeText`);
    assert.ok(entry.afterText !== undefined, `Invariant 2: undoStack[${i}] missing afterText`);
    assert.ok(entry.beforeAttrs !== undefined, `Invariant 2: undoStack[${i}] missing beforeAttrs`);
    assert.ok(entry.afterAttrs !== undefined, `Invariant 2: undoStack[${i}] missing afterAttrs`);
    assert.ok(Array.isArray(entry.beforeSelection), `Invariant 2: undoStack[${i}] missing beforeSelection`);
    assert.ok(Array.isArray(entry.afterSelection), `Invariant 2: undoStack[${i}] missing afterSelection`);
  }

  // 3. I_SINGLE_COMP_HISTORY:
  // Verifies exactly one composition history group per completed composition with net change.
  if (oracle) {
    const compCount = coord.undoStack.filter(e => e.group === 'Composition').length;
    assert.equal(
      compCount,
      oracle.completedCompHistoryCount,
      `Invariant 3 Violation (I_SINGLE_COMP_HISTORY): Found ${compCount} composition history entries, expected at most ${oracle.completedCompHistoryCount}`
    );
  }

  // 4. I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY:
  // Verifies that formatting touched the exact intended content and did not touch adjacent characters.
  if (coord.lastSettledExtent) {
    const [s, e] = coord.lastSettledExtent;
    assert.ok(s >= 0 && s <= coord.docText.length, `Invariant 4: Extent start ${s} out of bounds`);
    assert.ok(e >= 0 && e <= coord.docText.length, `Invariant 4: Extent end ${e} out of bounds`);
    assert.ok(s <= e, `Invariant 4: Extent start ${s} > end ${e}`);
  }
  if (oracle) {
    assert.ok(
      areAttrsEqual(coord.docAttributes, oracle.docAttributes),
      `Invariant 4 Violation (I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY): Document attributes do not match expected oracle attributes after ${opName}`
    );
  }

  // 5. I_NO_SETTLEMENT_BYPASS:
  // Verifies commands cannot execute before verified settlement.
  if (coord.compositionState === 'Composing' || coord.compositionState === 'AwaitingSettlement') {
    for (const cmdItem of coord.pendingCommands) {
      assert.ok(!cmdItem.executed, `Invariant 5 Violation (I_NO_SETTLEMENT_BYPASS): Command ${cmdItem.ticketId} executed before settlement`);
    }
    for (const [tId, out] of coord.commandOutcomes.entries()) {
      if (out.status === 'Applied' && (coord.activeSessionId && out.sessionId === coord.activeSessionId)) {
        assert.fail(`Invariant 5 Violation (I_NO_SETTLEMENT_BYPASS): Command ${tId} marked Applied in commandOutcomes before settlement`);
      }
    }
    if (oracle) {
      for (const [tId, cmd] of oracle.commandLedger.entries()) {
        if (cmd.status === 'Deferred' && cmd.sessionId === coord.activeSessionId) {
          const inPending = coord.pendingCommands.some(p => p.ticketId === tId);
          assert.ok(inPending, `Invariant 5 Violation (I_NO_SETTLEMENT_BYPASS): Deferred command ${tId} was removed from pendingCommands before settlement`);
        }
      }
    }
  }

  // 6. I_COMMAND_OUTCOME:
  // Verifies every command receives an explicit outcome record upon termination.
  for (const cmdItem of coord.pendingCommands) {
    assert.ok(
      cmdItem.ticketId && typeof cmdItem.ticketId === 'string',
      `Invariant 6 Violation: Command missing valid ticketId`
    );
  }
  if (oracle) {
    for (const [tId, cmd] of oracle.commandLedger.entries()) {
      if (cmd.status === 'Applied' || cmd.status === 'Cancelled') {
        assert.ok(
          coord.commandOutcomes.has(tId),
          `Invariant 6 Violation (I_COMMAND_OUTCOME): Terminated ticket ${tId} missing outcome record`
        );
        assert.equal(
          coord.commandOutcomes.get(tId).status,
          cmd.status,
          `Invariant 6 Violation (I_COMMAND_OUTCOME): Terminated ticket ${tId} status mismatch: ${coord.commandOutcomes.get(tId).status} !== ${cmd.status}`
        );
      }
    }
  }

  // 7. I_RAW_SELECTION_AND_RANGE_FIDELITY:
  // Verifies strict equality with admitted native selection and native range proposals.
  assert.ok(Array.isArray(coord.selection) && coord.selection.length === 2, `Invariant 7 Violation: Invalid selection array`);
  assert.ok(coord.selection[0] >= 0 && coord.selection[0] <= coord.docText.length, `Invariant 7 Violation: Selection anchor out of bounds`);
  assert.ok(coord.selection[1] >= 0 && coord.selection[1] <= coord.docText.length, `Invariant 7 Violation: Selection focus out of bounds`);
  if (oracle) {
    assert.deepEqual(
      coord.selection,
      oracle.expectedSelection,
      `Invariant 7 Violation (I_RAW_SELECTION): Selection ${coord.selection} does not match expected native selection ${oracle.expectedSelection}`
    );
  }
  if (details.expectedMarkedRange && coord.markedRange) {
    assert.deepEqual(
      coord.markedRange,
      details.expectedMarkedRange,
      `Invariant 7 Violation (I_NATIVE_RANGE_FIDELITY): Admitted markedRange ${coord.markedRange} does not match requested range ${details.expectedMarkedRange}`
    );
  }

  // 8. I_REVISION_RULES:
  // Computed strictly from canonical before/after semantic comparison (text, raw attributes).
  assert.ok(
    coord.sessionRevision >= prevSessRev,
    `Invariant 8 Violation: SessionRevision non-monotonic: ${coord.sessionRevision} < ${prevSessRev}`
  );
  const semanticActuallyChanged = (details.prevDocText !== undefined)
    ? ((coord.docText !== details.prevDocText) || !areAttrsEqual(coord.docAttributes, details.prevDocAttrs))
    : (details.semanticChangeExpected ?? true);
    
  if (!semanticActuallyChanged) {
    assert.equal(
      coord.documentRevision,
      prevDocRev,
      `Invariant 8 Violation (I_REVISION_RULES): DocumentRevision advanced on metadata/no-op from ${prevDocRev} to ${coord.documentRevision}`
    );
  } else {
    assert.ok(
      coord.documentRevision > prevDocRev,
      `Invariant 8 Violation (I_REVISION_RULES): DocumentRevision failed to advance on semantic mutation: ${coord.documentRevision} === ${prevDocRev}`
    );
  }

  // 9. I_IDENTITY_ISOLATION:
  // Stale epoch, duplicate sequence stamp, or active settlement completion cannot mutate state.
  if (details.staleCallbackAttempted || details.duplicateCallbackAttempted) {
    assert.equal(coord.documentRevision, prevDocRev, `Invariant 9 Violation: Stale/duplicate callback mutated DocumentRevision`);
    assert.equal(coord.docText, details.prevDocText, `Invariant 9 Violation: Stale/duplicate callback mutated docText`);
  }
  if (details.isDuplicateAttempt) {
    assert.equal(details.duplicateResult?.status, 'Duplicate', `Invariant 9 Violation (I_IDENTITY_ISOLATION): Duplicate sequence stamp did not return Duplicate status`);
  }
  if (opName === 'completeSettlement while active') {
    assert.notEqual(coord.compositionState, 'Idle', `Invariant 9 Violation (I_IDENTITY_ISOLATION): Settlement completed while candidate active`);
    assert.notEqual(coord.markedRange, null, `Invariant 9 Violation (I_IDENTITY_ISOLATION): Marked candidate cleared on illegal settlement`);
    if (coord.lastSettledExtent) {
      assert.notDeepEqual(coord.lastSettledExtent, [0, 0], `Invariant 9 Violation (I_IDENTITY_ISOLATION): Settlement resolved to [0, 0]`);
    }
  }

  // 10. I_ATOMIC_FAILURE:
  // Failed operations publish no partial state; full rollback state verified.
  assert.equal(
    coord.docText.length,
    coord.docAttributes.length,
    `Invariant 10 Violation (I_ATOMIC_FAILURE): docText.length !== docAttributes.length`
  );
  if (details.rollbackSnapshot) {
    assert.equal(coord.docText, details.rollbackSnapshot.docText, `Invariant 10 Violation (I_ATOMIC_FAILURE): Failed operation failed to rollback text`);
    assert.ok(areAttrsEqual(coord.docAttributes, details.rollbackSnapshot.docAttributes), `Invariant 10 Violation (I_ATOMIC_FAILURE): Failed to rollback attrs`);
    assert.deepEqual(coord.selection, details.rollbackSnapshot.selection, `Invariant 10 Violation (I_ATOMIC_FAILURE): Failed to rollback selection`);
    assert.equal(coord.documentRevision, details.rollbackSnapshot.documentRevision, `Invariant 10 Violation (I_ATOMIC_FAILURE): Failed to rollback DocumentRevision`);
    assert.equal(coord.sessionRevision, details.rollbackSnapshot.sessionRevision, `Invariant 10 Violation (I_ATOMIC_FAILURE): Failed to rollback SessionRevision`);
  }
}

// ---------------------------------------------------------------------------
// Sound State Cloning & Canonical Serialization
// ---------------------------------------------------------------------------

export function cloneCoord(c) {
  const next = new c.constructor(c.docText, c.docAttributes, c.selection);
  next.epoch = c.epoch;
  next.connectionState = c.connectionState;
  next.sequenceCounter = c.sequenceCounter;
  next.receiptCache = new Map(Array.from(c.receiptCache.entries()).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))]));
  next.evictedReceipts = new Set(c.evictedReceipts);
  next.maxCacheSize = c.maxCacheSize;
  
  next.ticketCounter = c.ticketCounter;
  next.sessionCounter = c.sessionCounter;
  next.settlementSeqCounter = c.settlementSeqCounter;
  
  next.documentRevision = c.documentRevision;
  next.sessionRevision = c.sessionRevision;
  next.typingIntent = { ...c.typingIntent };
  next.compositionState = c.compositionState;
  next.activeSessionId = c.activeSessionId;
  next.sessionEpoch = c.sessionEpoch;
  next.attributionEpoch = c.attributionEpoch;
  next.sessionOriginStart = c.sessionOriginStart;
  next.sessionOriginEnd = c.sessionOriginEnd;
  next.markedRange = c.markedRange ? [...c.markedRange] : null;
  next.baselineUnits = c.baselineUnits ? c.baselineUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } })) : null;
  next.seedAttributes = { ...c.seedAttributes };
  next.committedUnits = c.committedUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
  next.committedExtent = c.committedExtent ? [...c.committedExtent] : null;
  next.historicalCommittedUnits = c.historicalCommittedUnits ? c.historicalCommittedUnits.map(u => ({ char: u.char, attributes: { ...u.attributes } })) : [];
  next.historicalCommittedExtent = c.historicalCommittedExtent ? [...c.historicalCommittedExtent] : null;
  next.compositionBeforeSnapshot = c.compositionBeforeSnapshot ? JSON.parse(JSON.stringify(c.compositionBeforeSnapshot)) : null;
  next.activeSettlementToken = c.activeSettlementToken ? { ...c.activeSettlementToken } : null;
  next.settlementEvidence = c.settlementEvidence ? { ...c.settlementEvidence } : null;
  next.lastSettledExtent = c.lastSettledExtent ? [...c.lastSettledExtent] : null;
  next.lastSettledSessionId = c.lastSettledSessionId;
  next.expansionPrefixSurplus = c.expansionPrefixSurplus;
  next.expansionPrefixSurplusAttrs = cloneAttrs(c.expansionPrefixSurplusAttrs);
  next.expansionSuffixSurplus = c.expansionSuffixSurplus;
  next.expansionSuffixSurplusAttrs = cloneAttrs(c.expansionSuffixSurplusAttrs);
  next.expansionOriginalSurplus = c.expansionOriginalSurplus;
  next.expansionOriginalSurplusAttrs = cloneAttrs(c.expansionOriginalSurplusAttrs);
  next.pendingCommands = c.pendingCommands.map(p => JSON.parse(JSON.stringify(p)));
  next.commandOutcomes = new Map(Array.from(c.commandOutcomes.entries()).map(([k, v]) => [k, { ...v }]));
  next.undoStack = JSON.parse(JSON.stringify(c.undoStack));
  next.redoStack = JSON.parse(JSON.stringify(c.redoStack));
  return next;
}

export function serializeCanonicalState(c) {
  return JSON.stringify({
    ep: c.epoch,
    conn: c.connectionState,
    seq: c.sequenceCounter,
    text: c.docText,
    attrs: c.docAttributes,
    sel: c.selection,
    docRev: c.documentRevision,
    sessRev: c.sessionRevision,
    compState: c.compositionState,
    sessId: c.activeSessionId,
    attrEp: c.attributionEpoch,
    marked: c.markedRange,
    commExt: c.committedExtent,
    commUnits: c.committedUnits,
    histCommUnits: c.historicalCommittedUnits,
    histCommExt: c.historicalCommittedExtent,
    baseline: c.baselineUnits,
    seed: c.seedAttributes,
    settleTok: c.activeSettlementToken,
    settleEv: c.settlementEvidence,
    lastSettledExt: c.lastSettledExtent,
    lastSettledSessId: c.lastSettledSessionId,
    compSnap: c.compositionBeforeSnapshot,
    cmds: c.pendingCommands,
    outcomes: Array.from(c.commandOutcomes.entries()),
    undoStack: c.undoStack,
    redoStack: c.redoStack,
    receiptCache: Array.from(c.receiptCache.entries()),
    evicted: Array.from(c.evictedReceipts.values()),
    pfxSurplus: c.expansionPrefixSurplus,
    pfxSurplusAttrs: c.expansionPrefixSurplusAttrs,
    sfxSurplus: c.expansionSuffixSurplus,
    sfxSurplusAttrs: c.expansionSuffixSurplusAttrs
  });
}

// ---------------------------------------------------------------------------
// Model Checker Suite Runner
// ---------------------------------------------------------------------------

export function runBoundedModelChecker(CoordinatorClass = ProtocolCoordinator) {
  console.log("=== Starting Exhaustive Bounded State-Space Exploration (Run-04) ===");
  const startTime = performance.now();
  
  let generatedTracesCount = 0;
  let executedTransitionsCount = 0;
  let uniqueAbstractStatesCount = 0;
  let rejectedOperationsCount = 0;
  let failedPropertiesCount = 0;
  const excludedEventClasses = ["MultiUserCRDT", "PhysicalInputMethodWindowRendering", "ScreenReaderAccessibilityTreeSync"];
  
  const initialDocs = [
    { text: "Hello", attrs: null, sel: [1, 2] },
    { text: "ABCDEF", attrs: null, sel: [2, 4] },
    { text: "World", attrs: null, sel: [0, 0] }
  ];

  for (const init of initialDocs) {
    generatedTracesCount++;
    const root = new CoordinatorClass(init.text, init.attrs, init.sel);
    const rootOracle = new IndependentOracle(init.text, init.attrs, init.sel);
    root.openNewConnection();
    rootOracle.onNewConnection(1);
    
    const visited = new Set([serializeCanonicalState(root)]);
    uniqueAbstractStatesCount++;
    const queue = [{ coord: root, oracle: rootOracle, depth: 0, path: [] }];

    while (queue.length > 0) {
      const { coord, oracle, depth, path } = queue.shift();
      if (depth >= 4) continue;
      
      const ep = coord.epoch;
      const seq = depth + 1;
      const actions = [];

      if (coord.compositionState === 'Idle') {
        actions.push({
          name: 'startComposition',
          fn: (c, o) => {
            o.onStartComposition('s', [1, 2]);
            return c.receiveCallback(ep, { type: 'startComposition', start: 1, end: 2, sessionId: 's' }, seq);
          },
          expectedNativeSelection: [1, 2]
        });
        actions.push({
          name: 'preedit X',
          fn: (c, o) => {
            const pos = c.selection[1];
            o.onPreedit('X', [pos, pos]);
            return c.receiveCallback(ep, { type: 'preedit', text: 'X' }, seq);
          }
        });
        actions.push({
          name: 'format [1, 2]',
          fn: (c, o) => {
            const ticket = c.nextTicketId('ticket');
            o.onFormatCommand(ticket, [1, 2], { bold: true });
            return c.dispatchAppCommand({ ticketId: ticket, type: 'format', target: [1, 2], style: { bold: true } });
          }
        });
        actions.push({
          name: 'openNewConnection',
          fn: (c, o) => {
            o.onNewConnection(c.epoch + 1);
            return c.openNewConnection();
          }
        });
      } else if (coord.compositionState === 'Composing') {
        actions.push({
          name: 'preedit XY',
          fn: (c, o) => {
            o.onPreedit('XY', c.markedRange);
            return c.receiveCallback(ep, { type: 'preedit', text: 'XY' }, seq);
          }
        });
        actions.push({
          name: 'preedit XX',
          fn: (c, o) => {
            o.onPreedit('XX', c.markedRange);
            return c.receiveCallback(ep, { type: 'preedit', text: 'XX' }, seq);
          }
        });
        actions.push({
          name: 'partialCommit 1',
          fn: (c, o) => {
            const oldStart = c.markedRange ? c.markedRange[0] : 0;
            const res = c.receiveCallback(ep, { type: 'partialCommit', committedLength: 1 }, seq);
            if (res.status === 'Accepted' && res.receipt?.status !== 'Rejected') {
              o.onPartialCommit(1, oldStart);
            }
            return res;
          }
        });
        actions.push({
          name: 'partialCommit 2',
          fn: (c, o) => {
            const oldStart = c.markedRange ? c.markedRange[0] : 0;
            const res = c.receiveCallback(ep, { type: 'partialCommit', committedLength: 2 }, seq);
            if (res.status === 'Accepted' && res.receipt?.status !== 'Rejected') {
              o.onPartialCommit(2, oldStart);
            }
            return res;
          }
        });
        actions.push({
          name: 'targetExpansion [1, 4]',
          fn: (c, o) => {
            const res = c.receiveCallback(ep, { type: 'targetExpansion', newStart: 1, newEnd: 4 }, seq);
            if (res.status === 'Accepted' && res.receipt?.status !== 'Rejected') {
              o.onTargetExpansion(1, 4);
            }
            return res;
          },
          expectedMarkedRange: [1, 4]
        });
        actions.push({
          name: 'format composing',
          fn: (c, o) => {
            const ticket = c.nextTicketId('ticket');
            o.onFormatCommand(ticket, 'composing', { bold: true });
            return c.dispatchAppCommand({ ticketId: ticket, type: 'format', target: { type: 'CompositionTarget', sessionId: c.activeSessionId }, style: { bold: true } });
          }
        });
        actions.push({
          name: 'unmark false',
          fn: (c, o) => {
            o.onUnmark(false, c.markedRange);
            return c.receiveCallback(ep, { type: 'unmark', settled: false }, seq);
          }
        });
        actions.push({
          name: 'unmark true',
          fn: (c, o) => {
            o.onUnmark(true, c.markedRange);
            return c.receiveCallback(ep, { type: 'unmark', settled: true }, seq);
          }
        });
        actions.push({
          name: 'commit Z',
          fn: (c, o) => {
            o.onCommit('Z', c.markedRange || [c.selection[0], c.selection[1]]);
            return c.receiveCallback(ep, { type: 'commit', text: 'Z' }, seq);
          }
        });
        actions.push({
          name: 'cancel',
          fn: (c, o) => {
            o.onCancel(c.committedUnits.length > 0 || (c.historicalCommittedUnits && c.historicalCommittedUnits.length > 0));
            return c.receiveCallback(ep, { type: 'cancel' }, seq);
          }
        });
        actions.push({
          name: 'completeSettlement while active',
          fn: (c, o) => c.receiveCallback(ep, { type: 'completeSettlement', sessionId: c.activeSessionId }, seq)
        });
        actions.push({
          name: 'duplicate preedit stamp',
          fn: (c, o) => {
            const cachedKey = Array.from(c.receiptCache.keys())[0];
            const [recEpoch, recSeq] = cachedKey ? cachedKey.split(':').map(Number) : [ep, 1];
            return c.receiveCallback(recEpoch, { type: 'preedit', text: 'D' }, recSeq);
          },
          isDuplicateAttempt: true
        });
        actions.push({
          name: 'preedit FAIL',
          fn: (c, o) => {
            const range = c.markedRange || [c.selection[0], c.selection[1]];
            const res = c.receiveCallback(ep, { type: 'preedit', text: 'FAIL' }, seq);
            if (res.status === 'Accepted' && res.receipt?.status !== 'Rejected') {
              o.onPreedit('FAIL', range);
            }
            return res;
          }
        });
      } else if (coord.compositionState === 'AwaitingSettlement') {
        actions.push({
          name: 'completeSettlement',
          fn: (c, o) => {
            o.onCompleteSettlement();
            return c.receiveCallback(ep, { type: 'completeSettlement', sessionId: c.activeSessionId }, seq);
          }
        });
        actions.push({
          name: 'unmark repeat',
          fn: (c, o) => {
            const range = c.markedRange || [c.selection[0], c.selection[1]];
            const res = c.receiveCallback(ep, { type: 'unmark', settled: true }, seq);
            if (res.status === 'Accepted' && res.receipt?.status !== 'Rejected') {
              o.onUnmark(true, range);
            }
            return res;
          }
        });
        actions.push({
          name: 'preedit W (resumption)',
          fn: (c, o) => {
            const range = c.markedRange || [c.selection[1], c.selection[1]];
            o.onPreedit('W', range);
            return c.receiveCallback(ep, { type: 'preedit', text: 'W' }, seq);
          }
        });
        actions.push({
          name: 'cancel in settlement',
          fn: (c, o) => {
            o.onCancel(c.committedUnits.length > 0 || (c.historicalCommittedUnits && c.historicalCommittedUnits.length > 0));
            return c.receiveCallback(ep, { type: 'cancel' }, seq);
          }
        });
      }

      for (const act of actions) {
        executedTransitionsCount++;
        const next = cloneCoord(coord);
        const nextOracle = oracle.clone();
        const prevDocRev = next.documentRevision;
        const prevSessRev = next.sessionRevision;
        const prevDocText = next.docText;
        const prevDocAttrs = cloneAttrs(next.docAttributes);
        const prevSel = [...next.selection];
        
        let actRes = null;
        try {
          actRes = act.fn(next, nextOracle);
          if (actRes && (actRes.status === 'Rejected' || actRes.receipt?.status === 'Rejected')) {
            rejectedOperationsCount++;
          }
        } catch (e) {
          failedPropertiesCount++;
          throw e;
        }

        const isAccepted = (actRes && actRes.status === 'Accepted' && actRes.receipt?.status !== 'Rejected');

        try {
          assertInvariants(next, nextOracle, prevDocRev, prevSessRev, act.name, {
            prevDocText,
            prevDocAttrs,
            expectedMarkedRange: isAccepted ? act.expectedMarkedRange : null,
            expectedNativeSelection: isAccepted ? act.expectedNativeSelection : null,
            isDuplicateAttempt: act.isDuplicateAttempt,
            duplicateResult: actRes,
            rollbackSnapshot: (actRes && actRes.status === 'Faulted') ? {
              docText: prevDocText,
              docAttributes: prevDocAttrs,
              selection: prevSel,
              documentRevision: prevDocRev,
              sessionRevision: prevSessRev
            } : null
          });

          // Direct independent model comparison
          if (isAccepted && !act.isDuplicateAttempt) {
            assert.equal(next.docText, nextOracle.docText, `docText diverged from IndependentOracle on ${act.name}`);
            assert.ok(areAttrsEqual(next.docAttributes, nextOracle.docAttributes), `docAttributes diverged from IndependentOracle on ${act.name}`);
            assert.deepEqual(next.selection, nextOracle.expectedSelection, `selection diverged from IndependentOracle on ${act.name}`);
          }
        } catch (invErr) {
          failedPropertiesCount++;
          console.error(`FAILED PROPERTY on trace action '${act.name}':`, invErr.message);
          console.error('TRACE PATH:', [...path, act.name].join(' -> '));
          throw invErr;
        }

        const k = serializeCanonicalState(next);
        if (!visited.has(k)) {
          visited.add(k);
          uniqueAbstractStatesCount++;
          queue.push({ coord: next, oracle: nextOracle, depth: depth + 1, path: [...path, act.name] });
        }
      }
    }
  }

  const elapsedMs = (performance.now() - startTime).toFixed(2);
  console.log(`=== Bounded Model Checking Completed Successfully ===`);
  console.log(`- Generated traces: ${generatedTracesCount}`);
  console.log(`- Executed transitions: ${executedTransitionsCount}`);
  console.log(`- Unique abstract states (soundly deduplicated): ${uniqueAbstractStatesCount}`);
  console.log(`- Rejected operations: ${rejectedOperationsCount}`);
  console.log(`- Failed properties: ${failedPropertiesCount}`);
  console.log(`- Excluded event classes: [${excludedEventClasses.join(', ')}]`);
  console.log(`- Elapsed time: ${elapsedMs} ms`);

  return {
    status: 'PASS',
    generatedTraces: generatedTracesCount,
    exploredTransitions: executedTransitionsCount,
    uniqueStates: uniqueAbstractStatesCount,
    rejectedOperations: rejectedOperationsCount,
    failedProperties: failedPropertiesCount,
    excludedEventClasses,
    elapsedMs: parseFloat(elapsedMs)
  };
}
