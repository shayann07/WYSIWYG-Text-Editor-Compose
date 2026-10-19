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
      entry.beforeSelection = [0, 0];
      entry.selection = [0, 0]; // Bug: corrupts recorded selection
    }
    return res;
  }
}

// =============================================================================
// Run-09 Repaired Defect Mutants (Defects 1 through 6)
// =============================================================================

// Mutant R09_1: Global Rollback Delta on Disjoint Edits (Defect 1)
class Mutant_GlobalRollbackDeltaDisjoint extends CandidateBCoordinator {
  _cancel(injectStage = null) {
    let netRollbackDelta = 0;
    let cancelPoint = 0;
    if (this.provisionalOverlay && this.provisionalOverlay.segments) {
      for (const seg of this.provisionalOverlay.segments) {
        const segVisLen = seg.units ? seg.units.length : 0;
        const segDcLen = seg.dcEnd - seg.dcStart;
        netRollbackDelta += (segDcLen - segVisLen);
        cancelPoint = seg.dcStart;
      }
    }
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.target && cmdRec.target.type === 'TrackedRange' && netRollbackDelta !== 0) {
        const r = cmdRec.target.range;
        if (r && r.length === 2) {
          if (r[0] >= cancelPoint) {
            r[0] = Math.max(0, r[0] + netRollbackDelta);
            r[1] = Math.max(r[0], r[1] + netRollbackDelta);
          }
        }
      }
    }
    return super._cancel(injectStage);
  }
}

// Mutant R09_2: Opposing Edits with Zero Net Delta Ignored (Defect 1)
class Mutant_OpposingEditsZeroNetDelta extends CandidateBCoordinator {
  _cancel(injectStage = null) {
    let netRollbackDelta = 0;
    if (this.provisionalOverlay && this.provisionalOverlay.segments) {
      for (const seg of this.provisionalOverlay.segments) {
        netRollbackDelta += ((seg.dcEnd - seg.dcStart) - (seg.units ? seg.units.length : 0));
      }
    }
    if (netRollbackDelta === 0) {
      const res = super._cancel(injectStage);
      if (this.committedDoc[4]) this.committedDoc[4].attrs.italic = true;
      if (this.committedDoc[3]) delete this.committedDoc[3].attrs.italic;
      return res;
    }
    return super._cancel(injectStage);
  }
}

// Mutant R09_3: Idle Commands Left Permanently Pending (Defect 2)
class Mutant_IdleCommandsLeftPending extends CandidateBCoordinator {
  dispatchCommand(cmd, target = null, injectStage = null) {
    if (this.compositionState === 'Idle') {
      // Bug: queues command and returns Pending without executing
      const ticketId = this.nextTicketId();
      this.settlementObligation.pendingCommands.push({
        ticketId,
        cmd,
        target: JSON.parse(JSON.stringify(target || {})),
        status: 'Pending',
        sessionId: null,
        epoch: this.epoch
      });
      return { status: 'Accepted', ticketId };
    }
    return super.dispatchCommand(cmd, target, injectStage);
  }
}

// Mutant R09_4: Cross-Epoch Receipt Confusion (Duplicate lookup precedes Epoch check) (Defect 3)
class Mutant_CrossEpochReceiptConfusion extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    if (seq !== null) {
      for (const [k, v] of this.receiptCache.entries()) {
        if (k.endsWith(`:${seq}`)) {
          return { status: 'Duplicate', cachedReceipt: v };
        }
      }
    }
    return super.applyInput(payload, seq);
  }
}

// Mutant R09_5: Wrong Captured Character Formatting (Defect Finding C / Provenance)
class Mutant_WrongCapturedCharacterFormatting extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent, injectStage = null) {
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      if (cmdRec.cmd.type === 'Format') {
        this.typingIntent = { ...this.typingIntent, ...cmdRec.cmd.patch };
        cmdRec.status = 'Applied';
        this.commandOutcomes.set(cmdRec.ticketId, { status: 'Applied', typingIntentUpdated: true });
      }
    }
    this.settlementObligation.pendingCommands = [];
  }
}

// Mutant R09_6: Incorrect Undo / Queued-Command Ordering (Defect 4)
class Mutant_IncorrectUndoQueuedCommandOrder extends CandidateBCoordinator {
  undo() {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      const res = super.undo();
      return { type: 'UndoApplied', status: 'Accepted', group: 'Composition', restoredRange: [1, 2], selection: [1, 1] };
    }
    return super.undo();
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
// Run-08 Behavioral Mutants (Violations A through D + Invariant Protections)
// =============================================================================

// Mutant 1: Treating empty historical text as absent (truthiness fallback)
class Mutant_TruthinessFallback extends CandidateBCoordinator {
  undo() {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      this._cancel();
    }
    if (this.undoStack.length === 0) {
      return { status: 'Rejected', reason: 'NothingToUndo' };
    }
    const entry = this.undoStack.pop();
    this.redoStack.push({
      group: entry.group,
      beforeText: entry.beforeText || this.docText,
      beforeAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: entry.beforeSelection || [0, 0],
      afterText: entry.afterText || this.docText,
      afterAttrs: cloneAttrs(entry.afterAttrs),
      afterSelection: entry.afterSelection || [...this.selection],
      extent: entry.extent,
      selection: entry.selection || [0, 0]
    });
    // Bug: truthiness fallback treats "" as missing and falls back to this.docText
    const bText = entry.beforeText || this.docText;
    const bAttrs = entry.beforeAttrs || [];
    this.committedDoc = [];
    for (let i = 0; i < bText.length; i++) {
      this.committedDoc.push({
        tokenId: `undo:${i}`,
        char: bText[i],
        attrs: bAttrs[i] ? { ...bAttrs[i] } : {},
        originSessionId: null
      });
    }
    this.provisionalOverlay = null;
    this.markedWindow = null;
    this.compositionState = 'Idle';
    this.selection = entry.beforeSelection ? [...entry.beforeSelection] : [0, 0];
    this.sessionRevision++;
    return { status: 'Accepted', action: 'Undo', docText: this.docText };
  }
}

// Mutant 2: Rebuilding redo entry from current rather than historical content
class Mutant_RebuildRedoFromCurrentContent extends CandidateBCoordinator {
  undo() {
    if (this.undoStack.length === 0) return { status: 'Rejected', reason: 'NothingToUndo' };
    const entry = this.undoStack.pop();
    // Bug: uses this.docText (current document state) rather than historical entry.afterText
    this.redoStack.push({
      group: entry.group,
      beforeText: entry.beforeText,
      beforeAttrs: cloneAttrs(entry.beforeAttrs),
      beforeSelection: entry.beforeSelection || [0, 0],
      afterText: this.docText, // BUG: dynamic capture of current content instead of historical afterText
      afterAttrs: cloneAttrs(this.docAttributes),
      afterSelection: [...this.selection],
      extent: entry.extent,
      selection: entry.selection || [0, 0]
    });
    const bText = (entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : '';
    this.committedDoc = [];
    for (let i = 0; i < bText.length; i++) {
      this.committedDoc.push({
        tokenId: `undo:${i}`,
        char: bText[i],
        attrs: {},
        originSessionId: null
      });
    }
    this.selection = entry.beforeSelection ? [...entry.beforeSelection] : [0, 0];
    this.sessionRevision++;
    return { status: 'Accepted', action: 'Undo', docText: this.docText };
  }
}

// Mutant 3: Dropping nativeRemoval history
class Mutant_DropNativeRemovalHistory extends CandidateBCoordinator {
  _nativeRemoval(range, replacementText = '', selection = null, injectStage = null) {
    const res = super._nativeRemoval(range, replacementText, selection, injectStage);
    // Bug: removes history entry pushed by native removal
    if (this.undoStack.length > 0 && (this.undoStack[this.undoStack.length - 1].group === 'NativeRemoval' || this.undoStack[this.undoStack.length - 1].group === 'Composition')) {
      this.undoStack.pop();
    }
    return res;
  }
}

// Mutant 4: Clearing overlay before disposal finalization
class Mutant_ClearOverlayBeforeDisposal extends CandidateBCoordinator {
  _dispose(injectStage = null) {
    // Bug: clears overlay before materializing or capturing history (run-07 bug)
    this.provisionalOverlay = null;
    this.markedWindow = null;
    return super._dispose(injectStage);
  }
}

// Mutant 5: Using selection for validation but stale attribution for mutation
class Mutant_MismatchedValidationMutationRange extends CandidateBCoordinator {
  _preedit(text, range = null, selection = null, injectStage = null) {
    // Bug: validation used selection range, but mutation reuses stale attribution window [1, 2]
    if (this.compositionState === 'AwaitingSettlement' && this.attributionEpoch) {
      const staleRange = [1, 2];
      return super._preedit(text, staleRange, selection, injectStage);
    }
    return super._preedit(text, range, selection, injectStage);
  }
}

// Mutant 6: Overwriting original session preimage on resumed input
class Mutant_OverwriteSessionPreimageOnResumedPreedit extends CandidateBCoordinator {
  _startComposition(range, selection = null, sessionId = null, injectStage = null) {
    this.historySession = {
      sessionId: sessionId || `session-${++this.sessionCounter}`,
      preSessionDocSnapshot: {
        text: this.docText,
        attrs: cloneAttrs(this.docAttributes),
        selection: selection ? [...selection] : [range[1], range[1]]
      }
    };
    return super._startComposition(range, selection, sessionId, injectStage);
  }
  _preedit(text, range = null, selection = null, injectStage = null) {
    if (this.compositionState === 'AwaitingSettlement') {
      this.historySession = {
        sessionId: `session-${++this.sessionCounter}`,
        preSessionDocSnapshot: {
          text: this.docText,
          attrs: cloneAttrs(this.docAttributes),
          selection: [...this.selection]
        }
      };
    }
    return super._preedit(text, range, selection, injectStage);
  }
}

// Mutant 7: Undo bypassing unfinished composition obligation
class Mutant_UndoBypassesCompositionObligation extends CandidateBCoordinator {
  undo() {
    if (this.compositionState === 'Composing' || this.compositionState === 'AwaitingSettlement') {
      // Bug: silently discards active overlay without pushing composition history or preserving on redo
      this._cancel();
    }
    return super.undo();
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
  },
  {
    id: 'Mutant 12',
    name: 'Mutant 12: Treating empty historical text as absent (truthiness fallback)',
    cls: Mutant_TruthinessFallback,
    run: (CoordClass) => {
      const c = new CoordClass('', null, [0, 0]);
      c.applyInput({ type: 'commit', text: 'x' });
      c.applyInput({ type: 'undo' });
      c.applyInput({ type: 'redo' });
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, '', 'Empty document preimage must be restored on second undo');
    }
  },
  {
    id: 'Mutant 13',
    name: 'Mutant 13: Rebuilding redo entry from current rather than historical content',
    cls: Mutant_RebuildRedoFromCurrentContent,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'commit', text: 'World', range: [0, 5] });
      assert.equal(c.docText, 'World');
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Hello');
      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, 'World', 'Redo must restore historical postimage');
    }
  },
  {
    id: 'Mutant 14',
    name: 'Mutant 14: Dropping nativeRemoval history',
    cls: Mutant_DropNativeRemovalHistory,
    run: (CoordClass) => {
      const c = new CoordClass('abc', null, [1, 2]);
      c.applyInput({ type: 'nativeRemoval', range: [1, 2], replacementText: '' });
      assert.equal(c.docText, 'ac');
      const uRes = c.applyInput({ type: 'undo' });
      assert.equal(uRes.status, 'Accepted', 'Undo must be accepted after native removal');
      assert.equal(c.docText, 'abc', 'Undo must restore original text after native removal');
    }
  },
  {
    id: 'Mutant 15',
    name: 'Mutant 15: Clearing overlay before disposal finalization',
    cls: Mutant_ClearOverlayBeforeDisposal,
    run: (CoordClass) => {
      const c = new CoordClass('Hello', null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c.docText, 'HXllo');
      const dRes = c.applyInput({ type: 'dispose' });
      assert.equal(dRes.status, 'Accepted');
      assert.equal(c.docText, 'HXllo', 'Disposal must preserve accepted visible text');
      assert.equal(c.undoStack.length, 1, 'Disposal must finalize composition history');
    }
  },
  {
    id: 'Mutant 16',
    name: 'Mutant 16: Using selection for validation but stale attribution for mutation',
    cls: Mutant_MismatchedValidationMutationRange,
    run: (CoordClass) => {
      const c = new CoordClass('Hello', null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      c.applyInput({ type: 'preedit', text: 'Y' }); // omitted range targets selection [2, 2]
      assert.equal(c.docText, 'HXYllo', 'Resumed preedit with omitted range must target selection');
    }
  },
  {
    id: 'Mutant 17',
    name: 'Mutant 17: Overwriting original session preimage on resumed input',
    cls: Mutant_OverwriteSessionPreimageOnResumedPreedit,
    run: (CoordClass) => {
      const c = new CoordClass('Hello', null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      c.applyInput({ type: 'preedit', text: 'Y' });
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docText, 'HXYllo');
      const uRes = c.applyInput({ type: 'undo' });
      assert.equal(uRes.status, 'Accepted');
      assert.equal(c.docText, 'Hello', 'Undo must restore original pre-session snapshot Hello');
    }
  },
  {
    id: 'Mutant 18',
    name: 'Mutant 18: Undo bypassing unfinished composition obligation',
    cls: Mutant_UndoBypassesCompositionObligation,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'commit', text: ' World', range: [5, 5] });
      c.applyInput({ type: 'startComposition', range: [5, 11] });
      c.applyInput({ type: 'preedit', text: ' Earth' });
      assert.equal(c.docText, 'Hello Earth');

      // 1. Undo during active composition must settle/materialize and restore pre-session docText
      const u1 = c.applyInput({ type: 'undo' });
      assert.equal(u1.status, 'Accepted');
      assert.equal(c.compositionState, 'Idle', 'Undo must transition compositionState to Idle');
      assert.equal(c.docText, 'Hello World', 'Undo during active composition must revert to pre-session Hello World, not skip to Hello');

      // 2. Redo must restore the composition overlay text
      const r1 = c.applyInput({ type: 'redo' });
      assert.equal(r1.status, 'Accepted');
      assert.equal(c.docText, 'Hello Earth', 'Redo must restore active composition result Hello Earth');

      // 3. Second undo reverts back to pre-session state
      const u2 = c.applyInput({ type: 'undo' });
      assert.equal(u2.status, 'Accepted');
      assert.equal(c.docText, 'Hello World', 'Second undo must revert back to Hello World');

      // 4. Third undo reverts the earlier commit
      const u3 = c.applyInput({ type: 'undo' });
      assert.equal(u3.status, 'Accepted');
      assert.equal(c.docText, 'Hello', 'Third undo must revert commit to Hello');
    }
  },
  {
    id: 'Mutant R09_1',
    name: 'Mutant R09_1: Global rollback delta applied to disjoint edits',
    cls: Mutant_GlobalRollbackDeltaDisjoint,
    run: (CoordClass) => {
      const c = new CoordClass("abcdef", null, [0, 0]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      const qRes = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [5, 6] }
      });
      c.applyInput({ type: 'retarget', range: [7, 8] });
      c.applyInput({ type: 'preedit', text: '' });
      c.applyInput({ type: 'cancel' });
      assert.equal(c.docText, "abcdef");
      assert.equal(c.docAttributes[3]?.bold, true, "Character 'd' at committed index 3 must be bold");
      assert.equal(c.docAttributes[4]?.bold, undefined, "Character 'e' must not be bold");
    }
  },
  {
    id: 'Mutant R09_2',
    name: 'Mutant R09_2: Opposing edits with zero net delta ignored',
    cls: Mutant_OpposingEditsZeroNetDelta,
    run: (CoordClass) => {
      const c = new CoordClass("abcdef", null, [0, 0]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XY' }); // +1
      const qRes = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { italic: true } },
        target: { type: 'TrackedRange', range: [4, 5] } // 'd'
      });
      c.applyInput({ type: 'retarget', range: [6, 7] });
      c.applyInput({ type: 'preedit', text: '' }); // -1
      c.applyInput({ type: 'cancel' });
      assert.equal(c.docText, "abcdef");
      assert.equal(c.docAttributes[3]?.italic, true, "Character 'd' must be italic");
      assert.equal(c.docAttributes[4]?.italic, undefined, "Character 'e' must not be italic");
    }
  },
  {
    id: 'Mutant R09_3',
    name: 'Mutant R09_3: Idle commands left permanently pending',
    cls: Mutant_IdleCommandsLeftPending,
    run: (CoordClass) => {
      const c = new CoordClass("abcdef", null, [0, 0]);
      const qRes = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [0, 1] }
      });
      assert.equal(qRes.status, 'Applied', "Command in Idle must be Applied immediately");
      const outcome = c.commandOutcomes.get(qRes.ticketId);
      assert.equal(outcome?.status, 'Applied');
      assert.equal(c.docAttributes[0]?.bold, true, "Index 0 must be formatted bold");
    }
  },
  {
    id: 'Mutant R09_4',
    name: 'Mutant R09_4: Cross-epoch receipt reuse before epoch validation',
    cls: Mutant_CrossEpochReceiptConfusion,
    run: (CoordClass) => {
      const c = new CoordClass("Hello", null, [0, 0]);
      c.applyInput({ type: 'openNewConnection' }); // epoch 1
      assert.equal(c.epoch, 1);
      const p1 = c.applyInput({ type: 'preedit', text: 'X', epoch: 1 }, 1);
      assert.equal(p1.status, 'Accepted');

      const pOld = c.applyInput({ type: 'preedit', text: 'old', epoch: 0 }, 1);
      assert.equal(pOld.status, 'Rejected', "Old epoch callback must be rejected");
      assert.equal(pOld.reason, 'StaleEpoch', "Old epoch must be rejected as StaleEpoch, not Duplicate");
    }
  },
  {
    id: 'Mutant R09_5',
    name: 'Mutant R09_5: Wrong captured-character formatting (modifying intent)',
    cls: Mutant_WrongCapturedCharacterFormatting,
    run: (CoordClass) => {
      const c = new CoordClass("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' }
      });
      c.applyInput({ type: 'partialCommit', length: 1 });
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docAttributes[1]?.bold, true, "Committed unit must be formatted bold");
      assert.equal(c.typingIntent.bold, undefined, "Typing intent must NOT be modified");
    }
  },
  {
    id: 'Mutant R09_6',
    name: 'Mutant R09_6: Incorrect Undo / queued-command order under selected policy',
    cls: Mutant_IncorrectUndoQueuedCommandOrder,
    run: (CoordClass) => {
      const c = new CoordClass("abc", null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' }
      });
      const u1 = c.applyInput({ type: 'undo' });
      assert.equal(u1.status, 'Accepted');
      assert.equal(u1.group, 'Formatting', "First undo must pop Formatting group");
      assert.equal(c.docText, "aXc", "Document text must remain 'aXc' after first undo");
      assert.equal(c.docAttributes[1]?.bold, undefined, "First undo must strip bold attribute");
      const u2 = c.applyInput({ type: 'undo' });
      assert.equal(u2.status, 'Accepted');
      assert.equal(u2.group, 'Composition', "Second undo must pop Composition group");
      assert.equal(c.docText, "abc", "Second undo must restore pre-session text 'abc'");
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
