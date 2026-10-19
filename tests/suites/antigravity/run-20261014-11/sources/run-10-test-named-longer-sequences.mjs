// Named Longer Sequences Suite (§4, Requirement 4)
// Executes 8 long, multi-step named sequences verifying contracts beyond short model checker bounds:
// 1. Sequence 1: Repeated Undo/Redo Preimage Stability (12 Cycles on Empty and Populated Documents)
// 2. Sequence 2: Whole-Document Replacement and Deletion Cycles
// 3. Sequence 3: Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
// 4. Sequence 4: Disposal Teardown Content & History Preservation across Lifecycle States
// 5. Sequence 5: Undo and Redo During Active Composition (Settlement & Redo Preservation)
// 6. Sequence 6: Resumed Preedit Unified Target Resolution (Omitted vs Explicit Ranges)
// 7. Sequence 7: Dynamic Selection Bounds & Reversed Selections on Growth and Shrinkage
// 8. Sequence 8: Interleaved Typing, Formatting, Connection Turnover, and Branch Invalidation

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';
import { IndependentOracle } from './independent-oracle.mjs';

export function runNamedLongerSequencesTest() {
  console.log("================================================================================");
  console.log("=== EXECUTABLE NAMED LONGER SEQUENCES SUITE (Requirement 4)                  ===");
  console.log("=== Verifying Candidate B Coordinator & Independent Oracle on Long Traces   ===");
  console.log("================================================================================\n");

  let passedSequences = 0;
  const totalSequences = 8;

  // ---------------------------------------------------------------------------
  // Sequence 1: Repeated Undo/Redo Preimage Stability (12 Cycles)
  // ---------------------------------------------------------------------------
  {
    console.log("--- [Sequence 1] Repeated Undo/Redo Preimage Stability (12 Cycles) ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // Empty document insertion & 12 cycles
      const c1 = new Impl("", null, [0, 0]);
      c1.applyInput({ type: 'commit', text: 'x', selection: [1, 1] });
      assert.equal(c1.docText, 'x');

      for (let i = 0; i < 12; i++) {
        const u = c1.applyInput({ type: 'undo' });
        assert.equal(u.status, 'Accepted');
        assert.equal(c1.docText, '', `Cycle ${i} empty doc undo failed`);
        assert.deepEqual(c1.selection, [0, 0]);
        assert.equal(c1.redoStack.length, 1);
        assert.equal(c1.redoStack[0].beforeText, '');
        assert.equal(c1.redoStack[0].afterText, 'x');

        const r = c1.applyInput({ type: 'redo' });
        assert.equal(r.status, 'Accepted');
        assert.equal(c1.docText, 'x', `Cycle ${i} empty doc redo failed`);
        assert.deepEqual(c1.selection, [1, 1]);
        assert.equal(c1.undoStack.length, 1);
        assert.equal(c1.undoStack[0].beforeText, '');
        assert.equal(c1.undoStack[0].afterText, 'x');
      }

      // Populated document with attributes & 12 cycles
      const c2 = new Impl("The quick brown fox", null, [4, 9]);
      c2.applyInput({ type: 'commit', text: 'swift', range: [4, 9], selection: [9, 9] });
      assert.equal(c2.docText, 'The swift brown fox');

      for (let i = 0; i < 12; i++) {
        c2.applyInput({ type: 'undo' });
        assert.equal(c2.docText, 'The quick brown fox');
        assert.deepEqual(c2.selection, [4, 9]);
        assert.equal(c2.redoStack[0].beforeText, 'The quick brown fox');
        assert.equal(c2.redoStack[0].afterText, 'The swift brown fox');
        assert.deepEqual(c2.redoStack[0].beforeSelection, [4, 9]);
        assert.deepEqual(c2.redoStack[0].afterSelection, [9, 9]);

        c2.applyInput({ type: 'redo' });
        assert.equal(c2.docText, 'The swift brown fox');
        assert.deepEqual(c2.selection, [9, 9]);
        assert.equal(c2.undoStack[0].beforeText, 'The quick brown fox');
        assert.equal(c2.undoStack[0].afterText, 'The swift brown fox');
        assert.deepEqual(c2.undoStack[0].beforeSelection, [4, 9]);
        assert.deepEqual(c2.undoStack[0].afterSelection, [9, 9]);
      }
    }
    console.log("[PASS] Sequence 1 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 2: Whole-Document Replacement and Deletion Cycles
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 2] Whole-Document Replacement and Deletion Cycles ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("Alpha Beta Gamma", null, [0, 16]);
      // Whole document deletion
      c.applyInput({ type: 'commit', text: '', range: [0, 16], selection: [0, 0] });
      assert.equal(c.docText, '');
      assert.deepEqual(c.selection, [0, 0]);

      // Undo deletion
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Alpha Beta Gamma');
      assert.deepEqual(c.selection, [0, 16]);

      // Redo deletion
      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, '');

      // Undo deletion again
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Alpha Beta Gamma');

      // Whole document replacement
      c.applyInput({ type: 'commit', text: 'Delta Epsilon', range: [0, 16], selection: [13, 13] });
      assert.equal(c.docText, 'Delta Epsilon');
      assert.deepEqual(c.selection, [13, 13]);

      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Alpha Beta Gamma');
      assert.deepEqual(c.selection, [0, 16]);

      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, 'Delta Epsilon');
      assert.deepEqual(c.selection, [13, 13]);
    }
    console.log("[PASS] Sequence 2 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 3: Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 3] Native Removal across Lifecycle States ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Idle nativeRemoval
      const c1 = new Impl("abcde", null, [1, 4]);
      const r1 = c1.applyInput({ type: 'nativeRemoval', range: [1, 4], replacementText: 'X', selection: [2, 2] });
      assert.equal(r1.status, 'Accepted');
      assert.equal(c1.docText, 'aXe');
      assert.deepEqual(c1.selection, [2, 2]);
      assert.equal(c1.undoStack.length, 1);
      assert.equal(c1.undoStack[0].beforeText, 'abcde');
      assert.equal(c1.undoStack[0].afterText, 'aXe');
      c1.applyInput({ type: 'undo' });
      assert.equal(c1.docText, 'abcde');
      c1.applyInput({ type: 'redo' });
      assert.equal(c1.docText, 'aXe');

      // 2. Composing nativeRemoval
      const c2 = new Impl("Hello", null, [1, 2]);
      c2.applyInput({ type: 'startComposition', range: [1, 2] });
      c2.applyInput({ type: 'preedit', text: 'XYZ' });
      assert.equal(c2.docText, 'HXYZllo');
      const r2 = c2.applyInput({ type: 'nativeRemoval', range: [1, 4], replacementText: 'Q', selection: [2, 2] });
      assert.equal(r2.status, 'Accepted');
      assert.equal(c2.docText, 'HQllo');
      assert.equal(c2.undoStack.length, 1);
      assert.equal(c2.undoStack[0].beforeText, 'Hello', 'Preimage must preserve pre-session snapshot Hello');
      assert.equal(c2.undoStack[0].afterText, 'HQllo');
      c2.applyInput({ type: 'undo' });
      assert.equal(c2.docText, 'Hello');
      c2.applyInput({ type: 'redo' });
      assert.equal(c2.docText, 'HQllo');

      // 3. AwaitingSettlement nativeRemoval
      const c3 = new Impl("Hello", null, [1, 2]);
      c3.applyInput({ type: 'startComposition', range: [1, 2] });
      c3.applyInput({ type: 'preedit', text: 'X' });
      c3.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      const r3 = c3.applyInput({ type: 'nativeRemoval', range: [1, 2], replacementText: '', selection: [1, 1] });
      assert.equal(r3.status, 'Accepted');
      assert.equal(c3.docText, 'Hllo');
      assert.equal(c3.undoStack.length, 1);
      assert.equal(c3.undoStack[0].beforeText, 'Hello');
      assert.equal(c3.undoStack[0].afterText, 'Hllo');
      c3.applyInput({ type: 'undo' });
      assert.equal(c3.docText, 'Hello');
      c3.applyInput({ type: 'redo' });
      assert.equal(c3.docText, 'Hllo');
    }
    console.log("[PASS] Sequence 3 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 4: Disposal Teardown Content & History Preservation
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 4] Disposal Teardown Content & History Preservation ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Disposal during ordinary preedit preserves visible text
      const c1 = new Impl("Hello", null, [1, 2]);
      c1.applyInput({ type: 'startComposition', range: [1, 2] });
      c1.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c1.docText, 'HXllo');
      const d1 = c1.applyInput({ type: 'dispose' });
      assert.equal(d1.status, 'Accepted');
      assert.equal(c1.docText, 'HXllo', 'Disposal must preserve accepted visible text');
      assert.equal(c1.connectionState, 'Closed');
      assert.equal(c1.undoStack.length, 1, 'Disposal must finalize 1 history entry');
      assert.equal(c1.undoStack[0].beforeText, 'Hello');
      assert.equal(c1.undoStack[0].afterText, 'HXllo');

      // Subsequent input rejected on closed connection
      const postD = c1.applyInput({ type: 'commit', text: 'Z' });
      assert.equal(postD.status, 'Rejected');
      assert.equal(postD.reason, 'ConnectionClosed');

      // 2. Disposal after partial commit preserves text & history
      const c2 = new Impl("Hello", null, [1, 2]);
      c2.applyInput({ type: 'startComposition', range: [1, 2] });
      c2.applyInput({ type: 'preedit', text: 'XY' });
      c2.applyInput({ type: 'partialCommit', length: 1 });
      c2.applyInput({ type: 'preedit', text: 'Z' });
      assert.equal(c2.docText, 'HXZllo');
      const d2 = c2.applyInput({ type: 'dispose' });
      assert.equal(d2.status, 'Accepted');
      assert.equal(c2.docText, 'HXZllo');
      assert.equal(c2.undoStack.length, 1);
      assert.equal(c2.undoStack[0].beforeText, 'Hello');
      assert.equal(c2.undoStack[0].afterText, 'HXZllo');

      // 3. Disposal after nonterminal unmark preserves text & history
      const c3 = new Impl("Hello", null, [1, 2]);
      c3.applyInput({ type: 'startComposition', range: [1, 2] });
      c3.applyInput({ type: 'preedit', text: 'X' });
      c3.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      assert.equal(c3.docText, 'HXllo');
      const d3 = c3.applyInput({ type: 'dispose' });
      assert.equal(d3.status, 'Accepted');
      assert.equal(c3.docText, 'HXllo');
      assert.equal(c3.undoStack.length, 1);
      assert.equal(c3.undoStack[0].beforeText, 'Hello');
      assert.equal(c3.undoStack[0].afterText, 'HXllo');
    }
    console.log("[PASS] Sequence 4 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 5: Undo and Redo During Active Composition
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 5] Undo and Redo During Active Composition ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("Hello");
      c.applyInput({ type: 'commit', text: ' Beautiful', range: [5, 5] });
      assert.equal(c.docText, 'Hello Beautiful');
      c.applyInput({ type: 'commit', text: ' World', range: [15, 15] });
      assert.equal(c.docText, 'Hello Beautiful World');

      // Active composition modifying text
      c.applyInput({ type: 'startComposition', range: [16, 21] });
      c.applyInput({ type: 'preedit', text: 'Earth' });
      assert.equal(c.docText, 'Hello Beautiful Earth');

      // Undo during active composition
      const u1 = c.applyInput({ type: 'undo', syncBoundary: true, settlementEvidence: { extent: [16, 21] } });
      assert.equal(u1.status, 'Accepted');
      assert.equal(c.docText, 'Hello Beautiful World', 'Undo must revert composition to pre-session Hello Beautiful World');
      assert.equal(c.redoStack.length, 1, 'Active composition must be preserved on redoStack');
      assert.equal(c.redoStack[0].afterText, 'Hello Beautiful Earth');

      // Redo restores active composition
      const r1 = c.applyInput({ type: 'redo' });
      assert.equal(r1.status, 'Accepted');
      assert.equal(c.docText, 'Hello Beautiful Earth');

      // Stepwise undos back to start
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Hello Beautiful World');
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Hello Beautiful');
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Hello');

      // Empty undo stack rejects
      const uEmpty = c.applyInput({ type: 'undo' });
      assert.equal(uEmpty.status, 'Rejected');
      assert.equal(uEmpty.reason, 'NothingToUndo');

      // Stepwise redos back to peak
      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, 'Hello Beautiful');
      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, 'Hello Beautiful World');
      c.applyInput({ type: 'redo' });
      assert.equal(c.docText, 'Hello Beautiful Earth');

      // Empty redo stack rejects
      const rEmpty = c.applyInput({ type: 'redo' });
      assert.equal(rEmpty.status, 'Rejected');
      assert.equal(rEmpty.reason, 'NothingToRedo');
    }
    console.log("[PASS] Sequence 5 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 6: Resumed Preedit Unified Target Resolution
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 6] Resumed Preedit Unified Target Resolution ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("Hello", null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c.docText, 'HXllo');

      // Nonterminal unmark leaving selection at [2, 2]
      c.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });

      // Omitted range preedit resumes targeting selection [2, 2]
      const rOmitted = c.applyInput({ type: 'preedit', text: 'Y' });
      assert.equal(rOmitted.status, 'Accepted');
      assert.equal(c.docText, 'HXYllo', 'Omitted range must target selection [2, 2] producing HXYllo');

      // Explicit range preedit targets exact requested range [1, 3]
      const rExplicit = c.applyInput({ type: 'preedit', text: 'Z', range: [1, 3] });
      assert.equal(rExplicit.status, 'Accepted');
      assert.equal(c.docText, 'HZllo', 'Explicit range must target [1, 3] producing HZllo');

      // Terminal unmark
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docText, 'HZllo');

      // Undo restores pre-session Hello
      c.applyInput({ type: 'undo' });
      assert.equal(c.docText, 'Hello');
    }
    console.log("[PASS] Sequence 6 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 7: Dynamic Selection Bounds & Reversed Selections
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 7] Dynamic Selection Bounds & Reversed Selections ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("a", null, [0, 0]);

      // Growth edit: insertion expands length from 1 to 10; selection [10, 10] is valid
      const rGrowth = c.applyInput({ type: 'commit', text: '123456789', range: [1, 1], selection: [10, 10] });
      assert.equal(rGrowth.status, 'Accepted');
      assert.equal(c.docText, 'a123456789');
      assert.deepEqual(c.selection, [10, 10]);

      // Reversed selection [focus < anchor] valid on growth
      const rRev = c.applyInput({ type: 'commit', text: 'B', range: [0, 0], selection: [8, 2] });
      assert.equal(rRev.status, 'Accepted');
      assert.deepEqual(c.selection, [8, 2]);

      // Shrinkage edit: deletion shrinks length from 11 to 2; selection [5, 5] is INVALID and rejected upfront
      const rShrinkFail = c.applyInput({ type: 'commit', text: '', range: [2, 11], selection: [5, 5] });
      assert.equal(rShrinkFail.status, 'Rejected');
      assert.equal(rShrinkFail.reason, 'InvalidSelection');
      assert.equal(c.docText, 'Ba123456789', 'Document must remain unchanged on rejected shrinkage selection');

      // Valid shrinkage selection [2, 2] accepted
      const rShrinkPass = c.applyInput({ type: 'commit', text: '', range: [2, 11], selection: [2, 2] });
      assert.equal(rShrinkPass.status, 'Accepted');
      assert.equal(c.docText, 'Ba');
      assert.deepEqual(c.selection, [2, 2]);
    }
    console.log("[PASS] Sequence 7 verified");
    passedSequences++;
  }

  // ---------------------------------------------------------------------------
  // Sequence 8: Interleaved Typing, Formatting, Connection Turnover, and Invalidation
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Sequence 8] Interleaved Typing, Formatting, Turnover, & Invalidation ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("Start");
      c.applyInput({ type: 'commit', text: ' More', range: [5, 5] });
      assert.equal(c.docText, 'Start More');

      // Start composition, add text, queue formatting command
      c.applyInput({ type: 'startComposition', range: [6, 10] }); // "More"
      c.applyInput({ type: 'preedit', text: 'Text' }); // "Start Text"
      const q = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { italic: true } }, target: { type: 'CompositionTarget' } });
      assert.equal(q.status, 'Accepted');

      // Unmark settles candidate, drains formatting command, pushes history
      c.applyInput({ type: 'unmark', settled: true });
      assert.equal(c.docText, 'Start Text');
      assert.equal(c.docAttributes[6]?.italic, true);
      assert.equal(c.commandOutcomes.get(q.ticketId)?.status, 'Applied');

      // Connection turnover
      const rConn = c.applyInput({ type: 'openNewConnection' });
      assert.equal(rConn.status, 'Accepted');
      assert.equal((c.epoch !== undefined ? c.epoch : c.activeEpoch), 1);

      // Undo 1: Undoes formatting group
      const u1 = c.applyInput({ type: 'undo' });
      assert.equal(u1.status, 'Accepted');
      assert.equal(c.docText, 'Start Text');
      assert.equal(c.docAttributes[6]?.italic !== true, true);

      // Undo 2: Undoes composition group
      const u2 = c.applyInput({ type: 'undo' });
      assert.equal(u2.status, 'Accepted');
      assert.equal(c.docText, 'Start More');
      assert.equal(c.redoStack.length, 2);

      // New typing after undo invalidates redo branch
      c.applyInput({ type: 'commit', text: ' Branch2', range: [c.docText.length, c.docText.length] });
      assert.equal(c.redoStack.length, 0, 'New typing must clear redoStack');

      // Redo now rejected
      const rInvalid = c.applyInput({ type: 'redo' });
      assert.equal(rInvalid.status, 'Rejected');
      assert.equal(rInvalid.reason, 'NothingToRedo');
    }
    console.log("[PASS] Sequence 8 verified");
    passedSequences++;
  }

  console.log("================================================================================");
  console.log(`=== ALL ${passedSequences}/${totalSequences} NAMED LONGER SEQUENCES PASSED CLEANLY!              ===`);
  console.log("================================================================================\n");

  return { passed: passedSequences, total: totalSequences };
}

if (process.argv[1]?.endsWith('test-named-longer-sequences.mjs')) {
  runNamedLongerSequencesTest();
}
