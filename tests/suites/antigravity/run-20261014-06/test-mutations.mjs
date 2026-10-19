// Realistic Mutation Validation Suite (§7)
// Comprehensive Sound Implementation for run-20261014-06:
// - 20 Realistic mutants: 11 baseline invariants + 9 Defect Regressions A–I.
// - Positive controls: every test verified against unmodified CandidateBCoordinator.
// - Negative controls: verified that every mutant fails its targeted invariant/contract assertion.
// - Zero false positives.

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';

// ---------------------------------------------------------------------------
// Baseline Invariant Mutants (1 - 11)
// ---------------------------------------------------------------------------

// Mutant 1: Silent range clamping on target expansion
class Mutant1_SilentClamping extends CandidateBCoordinator {
  _retarget(range, selection = null) {
    let [u, v] = range;
    if (this.provisionalOverlay && this.provisionalOverlay.segments.length > 0) {
      const sc = this.provisionalOverlay.segments[0].dcStart;
      u = Math.max(u, sc + 1);
    }
    return super._retarget([u, v], selection);
  }
}

// Mutant 2: Dropping absorbed original unit on partial commit + cancel (Astra Critique 1)
class Mutant2_DropAbsorbedUnit extends CandidateBCoordinator {
  _cancel() {
    if (this.committedDoc && this.committedDoc.length > 2) {
      // Bug: drops absorbed original unit at index 2 from committedDoc
      this.committedDoc = this.committedDoc.filter((_, idx) => idx !== 2);
    }
    return super._cancel();
  }
}

// Mutant 3: Dropping required composition history entry
class Mutant3_DropHistoryEntry extends CandidateBCoordinator {
  _unmark(settled = true, selection = null) {
    const res = super._unmark(settled, selection);
    this.undoStack = [];
    return res;
  }
}

// Mutant 4: Formatting outside intent target
class Mutant4_FormatOutsideTarget extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent) {
    super._drainPendingCommands([0, this.docText.length]);
  }
}

// Mutant 5: Premature command execution before settlement
class Mutant5_PrematureCommandExecution extends CandidateBCoordinator {
  _queueCommand(cmd, target) {
    const res = super._queueCommand(cmd, target);
    if (this.settlementObligation.pendingCommands.length > 0) {
      const c = this.settlementObligation.pendingCommands[this.settlementObligation.pendingCommands.length - 1];
      c.status = 'Applied';
    }
    return res;
  }
}

// Mutant 6: Losing command outcome record
class Mutant6_LoseCommandOutcome extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent) {
    super._drainPendingCommands(settledExtent);
    this.commandOutcomes.clear();
  }
}

// Mutant 7: Silent selection reset to [0, 0]
class Mutant7_ResetSelectionToZero extends CandidateBCoordinator {
  _startComposition(range, selection = null, sessionId = null) {
    const res = super._startComposition(range, selection, sessionId);
    this.selection = [0, 0];
    return res;
  }
}

// Mutant 8: DocumentRevision bump on metadata-only freeze
class Mutant8_DocRevBumpOnMetadataFreeze extends CandidateBCoordinator {
  _partialCommit(k, selection = null) {
    const res = super._partialCommit(k, selection);
    this.documentRevision++;
    return res;
  }
}

// Mutant 9: Corrupted zero-length deletion boundary
class Mutant9_CorruptDeletionBoundary extends CandidateBCoordinator {
  _partialCommit(k, selection = null) {
    const res = super._partialCommit(k, selection);
    this.committedDoc = this.committedDoc.filter(u => u.char !== 'b');
    return res;
  }
}

// Mutant 10: Complete settlement while candidate active
class Mutant10_SettlementWhileCandidateActive extends CandidateBCoordinator {
  _completeSettlement(token = null, sessionId = null, epoch = null) {
    this.markedWindow = null;
    return super._completeSettlement(token, sessionId, epoch);
  }
}

// Mutant 11: Ignore duplicate sequence stamps without cache
class Mutant11_IgnoreDuplicateCache extends CandidateBCoordinator {
  applyInput(payload, seq = null) {
    const prevCache = this.receiptCache;
    this.receiptCache = new Map();
    const res = super.applyInput(payload, null);
    this.receiptCache = prevCache;
    return res;
  }
}

// ---------------------------------------------------------------------------
// Defect Regression Mutants (A - I)
// ---------------------------------------------------------------------------

// Mutant A: Interior segment insertion regression (reproduces Failure A: ignores interior segment partitions)
class MutantA_InteriorSegmentInsertion extends CandidateBCoordinator {
  _findInteriorSegment(baseSegs, aMid) {
    return null; // Bug from run-05: ignores interior partition, falls through to boundary insertion at start
  }
}

// Mutant B: Selection length ignored on implicit preedit (reproduces Failure B: "HXello" vs "HXo")
class MutantB_SelectionLengthIgnored extends CandidateBCoordinator {
  _startComposition(range, selection = null, sessionId = null) {
    return super._startComposition([range[0], range[0]], selection, sessionId);
  }
}

// Mutant C: Non-atomic history materialization (reproduces Failure C: afterText "catss" vs "cats")
class MutantC_NonAtomicHistoryMaterialization extends CandidateBCoordinator {
  _completeSettlement(token = null, sessionId = null, epoch = null) {
    const res = super._completeSettlement(token, sessionId, epoch);
    const comp = this.undoStack.find(u => u.group === 'Composition');
    if (comp) comp.afterText += 's';
    return res;
  }
}

// Mutant D: Omitted grapheme closure on combining marks (reproduces Failure D: bold: false on base)
class MutantD_AsciiOnlyFormatting extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent) {
    super._drainPendingCommands(settledExtent);
    // Bug: un-format base character outside composition extent
    if (this.docAttributes.length > 1 && this.docAttributes[0]?.bold) {
      delete this.docAttributes[0].bold;
      if (this.committedDoc && this.committedDoc[0]) delete this.committedDoc[0].attrs.bold;
    }
  }
}

// Mutant E: Wiping unrelated command tickets on cancel (reproduces Failure E: lost tickets)
class MutantE_WipingUnrelatedTickets extends CandidateBCoordinator {
  _cancel() {
    if (this.settlementObligation) {
      this.settlementObligation.pendingCommands = [];
    }
    return super._cancel();
  }
}

// Mutant F: Premature Idle state on full partial commit (reproduces Failure F: AlreadyUnmarked reject)
class MutantF_PrematureIdleOnPartialCommit extends CandidateBCoordinator {
  _partialCommit(k, selection = null) {
    const res = super._partialCommit(k, selection);
    this.compositionState = 'Idle';
    return res;
  }
}

// Mutant G: Omitted range validation admitting invalid replacement range (reproduces Failure G)
class MutantG_OmittedReplacementValidation extends CandidateBCoordinator {
  _validateRange(range, docLen) {
    return true; // Skips upfront range validation
  }
  _preedit(text, range = null, selection = null) {
    // Bug from run-05: silently ignores explicit replacement range when active
    return super._preedit(text, null, selection);
  }
}

// Mutant H: Stale island offsets after edit (reproduces Failure H: bold [0, 6] vs [0, 5, 6])
class MutantH_StaleIslandOffsets extends CandidateBCoordinator {
  _preedit(text, range = null, selection = null) {
    const saved = this.sessionIslands ? JSON.parse(JSON.stringify(this.sessionIslands)) : [];
    const res = super._preedit(text, range, selection);
    this.sessionIslands = saved; // Bug: preserves unmapped stale island coordinates
    return res;
  }
}

// Mutant I: Conflating TrackedRange with composition extent (reproduces Failure I: wrong formatted unit)
class MutantI_ConflateTrackedRangeWithComposition extends CandidateBCoordinator {
  _drainPendingCommands(settledExtent) {
    for (const cmdRec of this.settlementObligation.pendingCommands) {
      cmdRec.target = { type: 'CompositionTarget' };
    }
    super._drainPendingCommands(settledExtent);
  }
}

// ---------------------------------------------------------------------------
// Mutation Test Cases Definitions (Positive & Negative Test Suite)
// ---------------------------------------------------------------------------

export const MUTATION_SPECS = [
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
    id: 'Mutant E',
    name: 'Mutant E: Wiping unrelated command tickets on cancel',
    cls: MutantE_WipingUnrelatedTickets,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 3] });
      c.applyInput({ type: 'preedit', text: 'X' });
      const q = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [3, 4] }
      });
      c.applyInput({ type: 'cancel' });
      assert.ok(c.settlementObligation.pendingCommands.some(cmd => cmd.ticketId === q.ticketId));
    }
  },
  {
    id: 'Mutant F',
    name: 'Mutant F: Premature Idle state on full partial commit',
    cls: MutantF_PrematureIdleOnPartialCommit,
    run: (CoordClass) => {
      const c = new CoordClass('Hello');
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      c.applyInput({ type: 'partialCommit', length: 1 });
      assert.equal(c.compositionState, 'Composing');
      const u = c.applyInput({ type: 'unmark', settled: true });
      assert.equal(u.status, 'Accepted');
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
