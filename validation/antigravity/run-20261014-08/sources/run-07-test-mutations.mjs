// Comprehensive Mutation Validation Suite (§5, §7)
// Rigorous Sound Subclass Mutation Testing for run-20261014-07:
// - 27 Mutants:
//   * 9 Repaired Contract Mutants (Findings A–F + Transaction Failure + History Selection)
//   * 7 Retained Defect Mutants (Interior splicing, implicit preedit, grapheme closure, islands, etc.)
//   * 11 Baseline Invariant Mutants
// - Positive controls: every test verified against unmodified CandidateBCoordinator.
// - Negative controls: verified that every mutant is caught by strict assertion.
// - Zero false positives, zero unrelated crashes.

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';

// =============================================================================
// Repaired Contract Mutants (Findings A through F + Extensions)
// =============================================================================

// Mutant A_TurnoverDiscard: Discarding provisional text on connection replacement (Finding A)
class Mutant_DiscardProvisionalOnTurnover extends CandidateBCoordinator {
  _openNewConnection(injectStage = null) {
    // Bug: clears overlay without flushing to committedDoc (reverting text)
    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.compositionState = 'Idle';
    this.epoch++;
    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this.epoch };
  }
}

// Mutant B_DropPartialCommitUndo: Dropping partial-commit history on turnover (Finding B)
class Mutant_DropPartialCommitHistory extends CandidateBCoordinator {
  _openNewConnection(injectStage = null) {
    this._flushOverlayToCommitted(injectStage);
    // Bug: clears historySession without pushing to undoStack
    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.historySession = null;
    this.compositionState = 'Idle';
    this.epoch++;
    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this.epoch };
  }
}

// Mutant C_EmptyMarkedExtentAsFinalTarget: Using empty marked extent instead of session target (Finding C)
class Mutant_EmptyMarkedExtentAsFinalTarget extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    // Bug: ignores sessionCommittedRanges, uses only settledExtent [eStart, eEnd]
    this.sessionCommittedRanges = [];
    super._drainPendingCommands(settledExtent, injectStage);
  }
}

// Mutant D1_ValidateSelectionPreEdit: Validating resulting selection against pre-edit length (Finding D)
class Mutant_ValidateSelectionPreEdit extends CandidateBCoordinator {
  _computeProposedPostLength(payload) {
    // Bug: returns pre-edit length instead of proposed post-edit length
    return { valid: true, proposedLen: this.docText.length };
  }
}

// Mutant D2_AcceptInvalidPostEditSelection: Accepting invalid post-edit selection (Finding D)
class Mutant_AcceptInvalidPostEditSelection extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    // Bug: bypasses post-edit selection validation
    const origSel = payload.selection;
    if (payload.selection && payload.text === '') {
      payload.selection = null; // silently bypasses validation
    }
    const res = super.applyInput(payload, seq);
    if (origSel) this.selection = [...origSel];
    return res;
  }
}

// Mutant E_LeaveUnrelatedCommandsPending: Leaving unrelated commands permanently pending (Finding E)
class Mutant_LeaveUnrelatedCommandsPending extends CandidateBCoordinator {
  _cancel(injectStage = null) {
    // Bug: leaves unrelated commands in pendingCommands without draining
    const prev = this.settlementObligation.pendingCommands.filter(c => c.target?.type === 'TrackedRange');
    const res = super._cancel(injectStage);
    this.settlementObligation.pendingCommands = prev;
    return res;
  }
}

// Mutant F_IgnoreCallbackEpoch: Ignoring callback epoch (Finding F)
class Mutant_IgnoreCallbackEpoch extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    // Bug: deletes epoch from payload, ignoring admission check
    const p = { ...payload };
    delete p.epoch;
    return super.applyInput(p, seq);
  }
}

// Mutant Fail_PublishPartialState: Publishing partial state after injected failure (§4.3)
class Mutant_PublishPartialStateOnFailure extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    if (payload.injectFailureAt) {
      // Bug: leaks partial mutation without atomic rollback
      this.committedDoc.push({ tokenId: 'leaked', char: '!', attrs: {} });
      return { status: 'Rejected', reason: `InjectedFailure:${payload.injectFailureAt}` };
    }
    return super.applyInput(payload, seq);
  }
}

// Mutant Undo_CorruptSelection: Corrupting undo selection / attributes (§4.4)
class Mutant_CorruptUndoSelectionAttrs extends CandidateBCoordinator {
  _unmark(settled = true, selection = null, injectStage = null) {
    const res = super._unmark(settled, selection, injectStage);
    const entry = this.undoStack[this.undoStack.length - 1];
    if (entry) {
      entry.selection = [0, 0]; // Bug: corrupts recorded selection
    }
    return res;
  }
}

// =============================================================================
// Retained Defect Mutants (From run-06 A through I)
// =============================================================================

class MutantA_InteriorSegmentInsertion extends CandidateBCoordinator {
  _findInteriorSegment(baseSegs, aMid) {
    return null;
  }
}

class MutantB_SelectionLengthIgnored extends CandidateBCoordinator {
  _startComposition(range, selection = null, sessionId = null) {
    return super._startComposition([range[0], range[0]], selection, sessionId);
  }
}

class MutantC_NonAtomicHistoryMaterialization extends CandidateBCoordinator {
  _completeSettlement(token = null, sessionId = null, epoch = null, injectStage = null) {
    const res = super._completeSettlement(token, sessionId, epoch, injectStage);
    const comp = this.undoStack.find(u => u.group === 'Composition');
    if (comp) comp.afterText += 's';
    return res;
  }
}

class MutantD_AsciiOnlyFormatting extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    super._drainPendingCommands(settledExtent, injectStage);
    if (this.docAttributes.length > 1 && this.docAttributes[0]?.bold) {
      delete this.docAttributes[0].bold;
      if (this.committedDoc && this.committedDoc[0]) delete this.committedDoc[0].attrs.bold;
    }
  }
}

class MutantG_OmittedReplacementValidation extends CandidateBCoordinator {
  _validateRange(range, docLen) {
    return true;
  }
  _preedit(text, range = null, selection = null, injectStage = null) {
    return super._preedit(text, null, selection, injectStage);
  }
}

class MutantH_StaleIslandOffsets extends CandidateBCoordinator {
  _preedit(text, range = null, selection = null, injectStage = null) {
    const saved = this.sessionIslands ? JSON.parse(JSON.stringify(this.sessionIslands)) : [];
    const res = super._preedit(text, range, selection, injectStage);
    this.sessionIslands = saved;
    return res;
  }
}

class MutantI_ConflateTrackedRangeWithComposition extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      cmdRec.target = { type: 'CompositionTarget' };
    }
    super._drainPendingCommands(settledExtent, injectStage);
  }
}

// =============================================================================
// Baseline Invariant Mutants (1 through 11)
// =============================================================================

class Mutant1_SilentClamping extends CandidateBCoordinator {
  _retarget(range, selection = null, injectStage = null) {
    let [u, v] = range;
    if (this.provisionalOverlay && this.provisionalOverlay.segments.length > 0) {
      const sc = this.provisionalOverlay.segments[0].dcStart;
      u = Math.max(u, sc + 1);
    }
    return super._retarget([u, v], selection, injectStage);
  }
}

class Mutant2_DropAbsorbedUnit extends CandidateBCoordinator {
  _cancel(injectStage = null) {
    if (this.committedDoc && this.committedDoc.length > 2) {
      this.committedDoc = this.committedDoc.filter((_, idx) => idx !== 2);
    }
    return super._cancel(injectStage);
  }
}

class Mutant3_DropHistoryEntry extends CandidateBCoordinator {
  _unmark(settled = true, selection = null, injectStage = null) {
    const res = super._unmark(settled, selection, injectStage);
    this.undoStack = [];
    return res;
  }
}

class Mutant4_FormatOutsideTarget extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    super._drainPendingCommands([0, this.docText.length], injectStage);
  }
}

class Mutant5_PrematureCommandExecution extends CandidateBCoordinator {
  _queueCommand(cmd, target, injectStage = null) {
    const res = super._queueCommand(cmd, target, injectStage);
    if (this.settlementObligation.pendingCommands.length > 0) {
      const c = this.settlementObligation.pendingCommands[this.settlementObligation.pendingCommands.length - 1];
      c.status = 'Applied';
    }
    return res;
  }
}

class Mutant6_LoseCommandOutcome extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    super._drainPendingCommands(settledExtent, injectStage);
    this.commandOutcomes.clear();
  }
}

class Mutant7_ResetSelectionToZero extends CandidateBCoordinator {
  _startComposition(range, selection = null, sessionId = null, injectStage = null) {
    const res = super._startComposition(range, selection, sessionId, injectStage);
    this.selection = [0, 0];
    return res;
  }
}

class Mutant8_DocRevBumpOnMetadataFreeze extends CandidateBCoordinator {
  _partialCommit(k, selection = null, injectStage = null) {
    const res = super._partialCommit(k, selection, injectStage);
    this.documentRevision++;
    return res;
  }
}

class Mutant9_CorruptDeletionBoundary extends CandidateBCoordinator {
  _partialCommit(k, selection = null, injectStage = null) {
    const res = super._partialCommit(k, selection, injectStage);
    this.committedDoc = this.committedDoc.filter(u => u.char !== 'b');
    return res;
  }
}

class Mutant10_SettlementWhileCandidateActive extends CandidateBCoordinator {
  _completeSettlement(token = null, sessionId = null, epoch = null, injectStage = null) {
    this.markedWindow = null;
    return super._completeSettlement(token, sessionId, epoch, injectStage);
  }
}

class Mutant11_IgnoreDuplicateCache extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    const prevCache = this.receiptCache;
    this.receiptCache = new Map();
    const res = super.applyInput(payload, null);
    this.receiptCache = prevCache;
    return res;
  }
}

// =============================================================================
// Mutation Test Cases Definitions (Positive & Negative Test Suite)
// =============================================================================

export const MUTATION_SPECS = [
  // --- New Repaired Contract Mutants (Findings A-F + Staged Failure + History) ---
  {
    id: 'Mutant Finding A',
    name: 'Finding A: Discarding provisional text on connection replacement',
    cls: Mutant_DiscardProvisionalOnTurnover,
    run: (CoordClass) => {
      const c = new CoordClass("Hello", null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'openNewConnection' });
      assert.equal(c.docText, "HXllo");
    }
  },
  {
    id: 'Mutant Finding B',
    name: 'Finding B: Dropping partial-commit history on connection replacement',
    cls: Mutant_DropPartialCommitHistory,
    run: (CoordClass) => {
      const c = new CoordClass("Hello", null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'partialCommit', length: 1 });
      c.applyInput({ type: 'preedit', text: 'Y' });
      c.applyInput({ type: 'openNewConnection' });
      assert.equal(c.undoStack.length, 1);
      assert.equal(c.undoStack[0].beforeText, 'Hello');
      assert.equal(c.undoStack[0].afterText, 'HXYllo');
    }
  },
  {
    id: 'Mutant Finding C',
    name: 'Finding C: Using empty marked extent as final composition target',
    cls: Mutant_EmptyMarkedExtentAsFinalTarget,
    run: (CoordClass) => {
      const c = new CoordClass("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'partialCommit', length: 1 });
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docAttributes[1]?.bold, true);
      assert.equal(c.typingIntent.bold, undefined);
    }
  },
  {
    id: 'Mutant Finding D1',
    name: 'Finding D1: Validating resulting selection against pre-edit length',
    cls: Mutant_ValidateSelectionPreEdit,
    run: (CoordClass) => {
      const c = new CoordClass("a", null, [0, 1]);
      const res = c.applyInput({ type: 'preedit', text: 'XYZ', selection: [3, 3] });
      assert.equal(res.status, 'Accepted');
      assert.deepEqual(c.selection, [3, 3]);
    }
  },
  {
    id: 'Mutant Finding D2',
    name: 'Finding D2: Accepting invalid post-edit selection on shrinkage',
    cls: Mutant_AcceptInvalidPostEditSelection,
    run: (CoordClass) => {
      const c = new CoordClass("a", null, [0, 1]);
      const res = c.applyInput({ type: 'preedit', text: '', selection: [1, 1] });
      assert.equal(res.status, 'Rejected');
      assert.equal(res.reason, 'InvalidSelection');
      assert.equal(c.docText, 'a');
    }
  },
  {
    id: 'Mutant Finding E',
    name: 'Finding E: Leaving unrelated commands permanently pending',
    cls: Mutant_LeaveUnrelatedCommandsPending,
    run: (CoordClass) => {
      const c = new CoordClass("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      const q = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'TrackedRange', range: [0, 1] } });
      c.applyInput({ type: 'cancel' });
      assert.equal(c.settlementObligation.pendingCommands.length, 0);
      assert.equal(c.docAttributes[0]?.bold, true);
      assert.equal(c.commandOutcomes.get(q.ticketId)?.status, 'Applied');
    }
  },
  {
    id: 'Mutant Finding F',
    name: 'Finding F: Ignoring callback epoch admission',
    cls: Mutant_IgnoreCallbackEpoch,
    run: (CoordClass) => {
      const c = new CoordClass("Hello");
      c.applyInput({ type: 'openNewConnection' }); // epoch 1
      const res = c.applyInput({ type: 'preedit', text: 'STALE', epoch: 0 });
      assert.equal(res.status, 'Rejected');
      assert.equal(res.reason, 'StaleEpoch');
      assert.equal(c.docText, 'Hello');
    }
  },
  {
    id: 'Mutant Stage Fail',
    name: 'Staged Failure: Publishing partial state after injected failure',
    cls: Mutant_PublishPartialStateOnFailure,
    run: (CoordClass) => {
      const c = new CoordClass("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      const res = c.applyInput({ type: 'preedit', text: 'XYZ', injectFailureAt: 'afterTextSplice' });
      assert.equal(res.status, 'Rejected');
      assert.equal(c.docText, 'Hello');
      assert.equal(c.committedDoc.length, 5);
    }
  },
  {
    id: 'Mutant Undo Selection',
    name: 'History Verification: Corrupting recorded undo selection',
    cls: Mutant_CorruptUndoSelectionAttrs,
    run: (CoordClass) => {
      const c = new CoordClass("Hello", null, [1, 4]);
      c.applyInput({ type: 'startComposition', range: [1, 4] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'unmark', settled: true });
      const uRes = c.applyInput({ type: 'undo' });
      assert.equal(uRes.status, 'Accepted');
      assert.deepEqual(c.selection, [1, 4]);
    }
  },

  // --- Retained Defect Mutants (A - I) ---
  {
    id: 'Mutant A',
    name: 'Mutant A: Interior segment insertion bug',
    cls: MutantA_InteriorSegmentInsertion,
    run: (CoordClass) => {
      const c = new CoordClass('abc');
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      c.applyInput({ type: 'retarget', range: [2, 2] });
      c.applyInput({ type: 'preedit', text: 'Q' });
      assert.equal(c.docText, 'aXQYZc');
      assert.deepEqual(c.markedRange, [2, 3]);
    }
  },
  {
    id: 'Mutant B',
    name: 'Mutant B: Selection length ignored on implicit preedit',
    cls: MutantB_SelectionLengthIgnored,
    run: (CoordClass) => {
      const c = new CoordClass('Hello', null, [1, 4]);
      c.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c.docText, 'HXo');
    }
  },
  {
    id: 'Mutant C',
    name: 'Mutant C: Non-atomic history materialization',
    cls: MutantC_NonAtomicHistoryMaterialization,
    run: (CoordClass) => {
      const c = new CoordClass('cat');
      c.applyInput({ type: 'startComposition', range: [0, 3] });
      c.applyInput({ type: 'preedit', text: 'cats' });
      const u = c.applyInput({ type: 'unmark', settled: false });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'completeSettlement', token: u.token });
      const comp = c.undoStack.find(u => u.group === 'Composition');
      assert.ok(comp);
      assert.equal(comp.afterText, 'cats');
    }
  },
  {
    id: 'Mutant D',
    name: 'Mutant D: Omitted grapheme closure on combining marks',
    cls: MutantD_AsciiOnlyFormatting,
    run: (CoordClass) => {
      const c = new CoordClass('a');
      c.applyInput({ type: 'startComposition', range: [1, 1] });
      c.applyInput({ type: 'preedit', text: '\u0301' });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docAttributes[0]?.bold, true);
      assert.equal(c.docAttributes[1]?.bold, true);
    }
  },
  {
    id: 'Mutant G',
    name: 'Mutant G: Invalid explicit replacement accepted at different range',
    cls: MutantG_OmittedReplacementValidation,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      const res = c.applyInput({ type: 'preedit', text: 'X', range: [-1, 2] });
      assert.equal(res.status, 'Rejected');
      assert.equal(c.docText, 'Hello');
    }
  },
  {
    id: 'Mutant H',
    name: 'Mutant H: Stale island offsets after edit',
    cls: MutantH_StaleIslandOffsets,
    run: (CoordClass) => {
      const c = new CoordClass('aa BB cc');
      c.applyInput({ type: 'startComposition', range: [6, 8] });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'retarget', range: [0, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'unmark', settled: true });
      const boldIndices = [];
      c.docAttributes.forEach((a, i) => { if (a.bold) boldIndices.push(i); });
      assert.deepEqual(boldIndices, [0, 5, 6]);
    }
  },
  {
    id: 'Mutant I',
    name: 'Mutant I: Conflating TrackedRange with composition extent',
    cls: MutantI_ConflateTrackedRangeWithComposition,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'TrackedRange', range: [0, 1] } });
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docAttributes[0]?.bold, true);
      assert.ok(!c.docAttributes[1]?.bold);
    }
  },

  // --- Baseline Invariant Mutants (1 - 11) ---
  {
    id: 'Mutant 1',
    name: 'Mutant 1: Silent range clamping on target expansion',
    cls: Mutant1_SilentClamping,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'partialCommit', length: 1 });
      const res = c.applyInput({ type: 'targetExpansion', range: [1, 4] });
      assert.deepEqual(res.markedRange, [1, 4]);
    }
  },
  {
    id: 'Mutant 2',
    name: 'Mutant 2: Dropping absorbed original unit on partial commit + cancel',
    cls: Mutant2_DropAbsorbedUnit,
    run: (CoordClass) => {
      const c = new CoordClass('abcd');
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      c.applyInput({ type: 'targetExpansion', range: [1, 5] });
      c.applyInput({ type: 'partialCommit', length: 1 });
      c.applyInput({ type: 'cancel' });
      assert.equal(c.docText, 'aXcd');
    }
  },
  {
    id: 'Mutant 3',
    name: 'Mutant 3: Dropping required composition history entry',
    cls: Mutant3_DropHistoryEntry,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'preedit', text: 'Z' });
      c.applyInput({ type: 'unmark', settled: true });
      assert.ok(c.undoStack.length >= 1);
    }
  },
  {
    id: 'Mutant 4',
    name: 'Mutant 4: Formatting outside intent target',
    cls: Mutant4_FormatOutsideTarget,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'unmark', settled: true });
      assert.ok(!c.docAttributes[0].bold);
    }
  },
  {
    id: 'Mutant 5',
    name: 'Mutant 5: Premature command execution before settlement',
    cls: Mutant5_PrematureCommandExecution,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      const p = c.settlementObligation.pendingCommands[0];
      assert.equal(p.status, 'Pending');
    }
  },
  {
    id: 'Mutant 6',
    name: 'Mutant 6: Losing command outcome record',
    cls: Mutant6_LoseCommandOutcome,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      const q = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c.applyInput({ type: 'unmark', settled: true });
      assert.ok(c.commandOutcomes.has(q.ticketId));
    }
  },
  {
    id: 'Mutant 7',
    name: 'Mutant 7: Silent selection reset to [0, 0]',
    cls: Mutant7_ResetSelectionToZero,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3], selection: [1, 3] });
      assert.deepEqual(c.selection, [1, 3]);
    }
  },
  {
    id: 'Mutant 8',
    name: 'Mutant 8: DocumentRevision bump on metadata-only freeze',
    cls: Mutant8_DocRevBumpOnMetadataFreeze,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'preedit', text: 'XY' });
      const r1 = c.documentRevision;
      c.applyInput({ type: 'partialCommit', length: 1 });
      const r2 = c.documentRevision;
      assert.equal(r1, r2);
    }
  },
  {
    id: 'Mutant 9',
    name: 'Mutant 9: Corrupted zero-length deletion boundary',
    cls: Mutant9_CorruptDeletionBoundary,
    run: (CoordClass) => {
      const c = new CoordClass('abc');
      c.applyInput({ type: 'startComposition', range: [0, 3] });
      c.applyInput({ type: 'preedit', text: 'ac' });
      c.applyInput({ type: 'partialCommit', length: 1 });
      c.applyInput({ type: 'cancel' });
      assert.equal(c.docText, 'abc');
    }
  },
  {
    id: 'Mutant 10',
    name: 'Mutant 10: Complete settlement while candidate active',
    cls: Mutant10_SettlementWhileCandidateActive,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'preedit', text: 'Z' });
      c.settlementEvidence = { extent: [1, 2], token: 'tok' };
      const res = c.applyInput({ type: 'completeSettlement' });
      assert.equal(res.reason, 'CandidateStillActive');
    }
  },
  {
    id: 'Mutant 11',
    name: 'Mutant 11: Ignore duplicate sequence stamps without cache',
    cls: Mutant11_IgnoreDuplicateCache,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'preedit', text: 'X' }, 100);
      const dupRes = c.applyInput({ type: 'preedit', text: 'X' }, 100);
      assert.equal(dupRes.status, 'Duplicate');
    }
  }
];

export function runMutationsTest() {
  console.log(`=== Running Mutation Verification Suite (${MUTATION_SPECS.length} Mutants) ===`);

  let positivePassed = 0;
  let mutantsCaught = 0;
  let cleanKills = 0;
  let unrelatedCrashes = 0;

  for (const spec of MUTATION_SPECS) {
    // 1. Positive Control: unmodified CandidateBCoordinator MUST PASS
    try {
      spec.run(CandidateBCoordinator);
      positivePassed++;
    } catch (posErr) {
      console.error(`[POSITIVE CONTROL FAILED] ${spec.name} failed on unmodified Candidate B:`, posErr.message);
      throw new Error(`Positive control failed on ${spec.id}: ${posErr.message}`);
    }

    // 2. Negative Control: Mutant Class MUST FAIL and be caught
    let caught = false;
    let failureMsg = '';
    let isAssertion = false;
    try {
      spec.run(spec.cls);
    } catch (mutErr) {
      caught = true;
      failureMsg = mutErr.message;
      isAssertion = (mutErr.name === 'AssertionError' || mutErr.code === 'ERR_ASSERTION');
      if (isAssertion) {
        cleanKills++;
      } else {
        unrelatedCrashes++;
        console.warn(`[UNHANDLED CRASH] Mutant ${spec.id} threw non-assertion error (${mutErr.name}): ${mutErr.message}`);
      }
    }

    if (caught) {
      mutantsCaught++;
      console.log(`[CAUGHT] ${spec.name} -> caught by invariant / assertion: ${failureMsg.slice(0, 80)}`);
    } else {
      console.error(`[MISSED] ${spec.name} -> ESCAPED DETECTION!`);
    }
  }

  console.log(`\nMutation Verification Summary:`);
  console.log(`- Positive controls: ${positivePassed}/${MUTATION_SPECS.length} passed cleanly`);
  console.log(`- Mutants caught: ${mutantsCaught}/${MUTATION_SPECS.length} caught cleanly (${cleanKills} clean assertion kills, ${unrelatedCrashes} unrelated crashes)`);

  assert.equal(positivePassed, MUTATION_SPECS.length, 'All positive controls must pass');
  assert.equal(mutantsCaught, MUTATION_SPECS.length, 'All mutants must be caught');
  assert.equal(cleanKills, MUTATION_SPECS.length, 'All mutants must be caught by assertions, not unhandled crashes');
  assert.equal(unrelatedCrashes, 0, 'No unrelated unhandled crashes allowed');

  return { positivePassed, mutantsCaught, cleanKills, unrelatedCrashes, total: MUTATION_SPECS.length };
}

if (process.argv[1]?.endsWith('test-mutations.mjs')) {
  runMutationsTest();
}
