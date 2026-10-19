// Verification of Repaired Candidate B Coordinator against Contract Violations A-F & Regressions
// Exhaustively verifies:
// 1. Finding A: Connection replacement preserves accepted visible text and finalizes composition history
// 2. Finding B: Connection replacement retains undo after partial commit (exact before/after text, attrs, selection)
// 3. Finding C: Full partial commit formats committed units, not typing intent; separate formatting undo group
// 4. Finding D: Selection validation against post-edit length (growth, shrinkage, reversed selections, empty results)
// 5. Finding E: Cancellation drains surviving unrelated TrackedRange commands immediately with explicit outcome
// 6. Finding F: Connection epoch admission on all native callbacks; receipt cache eviction & sequence reuse
// 7. Retained Regression Suite A–I (interior insertion, noncollapsed implicit preedit, atomic snapshots, etc.)
// 8. Block 8: Real 6-stage transactional failure injection asserting complete state snapshot equality on rejections
// 9. Block 9: Executable undo/redo across compositions, formatting, partial commits, turnover, and branch invalidation
// 10. Block 10: Pinned Unicode extended grapheme cluster conformance fixtures & segmenter verification

import assert from 'node:assert/strict';
import { CandidateBCoordinator, expandToGraphemeBoundaries } from './candidate-b-coordinator.mjs';
import { IndependentOracle, oracleExpandToGraphemeBoundaries } from './independent-oracle.mjs';

export function runRepairedRegressionsTest() {
  console.log("================================================================================");
  console.log("=== VERIFYING REPAIRED CANDIDATE B COORDINATOR AGAINST FAILURES A-F & DEFECTS ===");
  console.log("================================================================================\n");

  let passedCount = 0;

  // ---------------------------------------------------------------------------
  // Finding A: Connection replacement preserves accepted visible text
  // ---------------------------------------------------------------------------
  {
    console.log("--- [Finding A] Connection replacement preserves accepted text & finalizes history ---");
    const c = new CandidateBCoordinator("Hello", null, [1, 2]);
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' }); // visible: "HXllo"
    assert.equal(c.docText, "HXllo");

    const rConn = c.applyInput({ type: 'openNewConnection' });
    assert.equal(rConn.status, 'Accepted');
    assert.equal(c.docText, "HXllo", "Visible text must be preserved on connection turnover!");
    assert.equal(c.undoStack.length, 1, "Exactly 1 composition history group must be pushed!");
    assert.equal(c.undoStack[0].group, 'Composition');
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXllo');
    assert.equal(c.compositionState, 'Idle');
    assert.equal(c.epoch, 1);
    console.log("[PASS] Finding A verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Finding B: Connection replacement retains undo after partial commit
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Finding B] Connection replacement retains undo after partial commit ---");
    const c = new CandidateBCoordinator("Hello", null, [1, 2]);
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    c.applyInput({ type: 'partialCommit', length: 1 });
    c.applyInput({ type: 'preedit', text: 'Y' });
    assert.equal(c.docText, "HXYllo");

    const rConn = c.applyInput({ type: 'openNewConnection' });
    assert.equal(rConn.status, 'Accepted');
    assert.equal(c.docText, "HXYllo");
    assert.equal(c.undoStack.length, 1, "UndoStack must retain exactly 1 composition history group!");
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYllo');
    console.log("[PASS] Finding B verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Finding C: Full partial commit formats committed target
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Finding C] Full partial commit formats committed units, not typing intent ---");
    const c = new CandidateBCoordinator("Hello");
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    const qRes = c.applyInput({
      type: 'queueCommand',
      cmd: { type: 'Format', patch: { bold: true } },
      target: { type: 'CompositionTarget' }
    });
    c.applyInput({ type: 'partialCommit', length: 1 }); // "X" committed; candidate empty
    c.applyInput({ type: 'unmark', settled: true });

    assert.equal(c.docText, "HXllo");
    assert.equal(c.docAttributes[1]?.bold, true, "Committed unit 'X' must be bold!");
    assert.equal(c.docAttributes[0]?.bold !== true, true, "Surrounding 'H' must remain plain!");
    assert.equal(c.docAttributes[2]?.bold !== true, true, "Surrounding 'l' must remain plain!");
    assert.equal(c.typingIntent.bold, undefined, "Typing intent must NOT be modified!");
    
    // Exactly 2 separate undo groups: 1 Composition + 1 Formatting
    assert.equal(c.undoStack.length, 2);
    assert.equal(c.undoStack[0].group, 'Composition');
    assert.equal(c.undoStack[1].group, 'Formatting');
    assert.equal(c.commandOutcomes.get(qRes.ticketId)?.status, 'Applied');

    // Test multiple partial commits
    const cMulti = new CandidateBCoordinator("Hello");
    cMulti.applyInput({ type: 'startComposition', range: [1, 2] });
    cMulti.applyInput({ type: 'preedit', text: 'ABC' });
    cMulti.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { italic: true } }, target: { type: 'CompositionTarget' } });
    cMulti.applyInput({ type: 'partialCommit', length: 1 }); // 'A' committed
    cMulti.applyInput({ type: 'partialCommit', length: 1 }); // 'B' committed
    cMulti.applyInput({ type: 'unmark', settled: true }); // 'C' settled
    assert.equal(cMulti.docText, 'HABCllo');
    for (let i = 1; i <= 3; i++) {
      assert.equal(cMulti.docAttributes[i]?.italic, true, `Unit at ${i} must be italic`);
    }

    // Test genuinely empty candidate outcome
    const cEmpty = new CandidateBCoordinator("Hello");
    cEmpty.applyInput({ type: 'startComposition', range: [1, 2] });
    cEmpty.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    cEmpty.applyInput({ type: 'preedit', text: '' });
    cEmpty.applyInput({ type: 'unmark', settled: true });
    assert.equal(cEmpty.docText, 'Hllo');
    assert.equal(cEmpty.typingIntent.bold, true, "Genuinely empty result must update typingIntent");

    console.log("[PASS] Finding C verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Finding D: Selection validation uses post-edit length
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Finding D] Selection validation uses post-edit document length ---");
    // Growth: "a" [0,1) -> preedit("XYZ", selection:[3,3)) -> Accepted
    const cGrowth = new CandidateBCoordinator("a", null, [0, 1]);
    const rGrowth = cGrowth.applyInput({ type: 'preedit', text: 'XYZ', selection: [3, 3] });
    assert.equal(rGrowth.status, 'Accepted');
    assert.equal(cGrowth.docText, "XYZ");
    assert.deepEqual(cGrowth.selection, [3, 3]);

    // Shrinkage: "a" [0,1) -> preedit("", selection:[1,1)) -> Rejected
    const cShrink = new CandidateBCoordinator("a", null, [0, 1]);
    const rShrink = cShrink.applyInput({ type: 'preedit', text: '', selection: [1, 1] });
    assert.equal(rShrink.status, 'Rejected');
    assert.equal(rShrink.reason, 'InvalidSelection');
    assert.equal(cShrink.docText, "a");
    assert.deepEqual(cShrink.selection, [0, 1]);

    // Reversed selection: "a" [0,1) -> preedit("XYZ", selection:[3, 1]) -> Accepted
    const cRev = new CandidateBCoordinator("a", null, [0, 1]);
    const rRev = cRev.applyInput({ type: 'preedit', text: 'XYZ', selection: [3, 1] });
    assert.equal(rRev.status, 'Accepted');
    assert.deepEqual(cRev.selection, [3, 1]);

    // Direct commit in Idle: "a" [0,1) -> commit("XYZ", selection:[3, 3]) -> Accepted
    const cCommit = new CandidateBCoordinator("a", null, [0, 1]);
    const rCommit = cCommit.applyInput({ type: 'commit', text: 'XYZ', selection: [3, 3] });
    assert.equal(rCommit.status, 'Accepted');
    assert.equal(cCommit.docText, "XYZ");
    assert.deepEqual(cCommit.selection, [3, 3]);

    console.log("[PASS] Finding D verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Finding E: Cancellation drains surviving unrelated commands
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Finding E] Cancellation drains surviving unrelated TrackedRange commands ---");
    const c = new CandidateBCoordinator("Hello");
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    const qRes = c.applyInput({
      type: 'queueCommand',
      cmd: { type: 'Format', patch: { bold: true } },
      target: { type: 'TrackedRange', range: [0, 1] }
    });
    const ticketId = qRes.ticketId;
    c.applyInput({ type: 'cancel' });

    assert.equal(c.compositionState, 'Idle');
    assert.equal(c.settlementObligation.pendingCommands.length, 0);
    assert.equal(c.docAttributes[0]?.bold, true, "'H' at [0,1) must be formatted bold!");
    assert.equal(c.commandOutcomes.get(ticketId)?.status, 'Applied');

    // Deleted target handling on cancellation
    const cDel = new CandidateBCoordinator("Hello");
    cDel.applyInput({ type: 'startComposition', range: [1, 4] }); // "ell"
    cDel.applyInput({ type: 'preedit', text: 'X' });
    const qDel = cDel.applyInput({
      type: 'queueCommand',
      cmd: { type: 'Format', patch: { italic: true } },
      target: { type: 'TrackedRange', range: [99, 100] } // Out of bounds
    });
    cDel.applyInput({ type: 'cancel' });
    // Invalid at admission: reject before allocating a ticket (unified request contract).
    assert.equal(qDel.status, 'Rejected');
    assert.equal(qDel.reason, 'InvalidTargetRange');
    assert.equal(qDel.ticketId, undefined);

    console.log("[PASS] Finding E verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Finding F: Connection epoch admission on all native callbacks
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Finding F] Connection epoch admission and receipt cache eviction ---");
    const c = new CandidateBCoordinator("Hello");
    c.applyInput({ type: 'openNewConnection' }); // epoch becomes 1
    assert.equal(c.epoch, 1);

    // Stale epoch 0 callback rejected
    const rStale = c.applyInput({ type: 'preedit', text: 'STALE', epoch: 0 });
    assert.equal(rStale.status, 'Rejected');
    assert.equal(rStale.reason, 'StaleEpoch');
    assert.equal(c.docText, "Hello");

    // Future epoch 2 callback rejected
    const rFuture = c.applyInput({ type: 'preedit', text: 'FUTURE', epoch: 2 });
    assert.equal(rFuture.status, 'Rejected');
    assert.equal(rFuture.reason, 'FutureEpoch');
    assert.equal(c.docText, "Hello");

    // Valid epoch 1 callback accepted
    const rValid = c.applyInput({ type: 'preedit', text: 'X', epoch: 1 });
    assert.equal(rValid.status, 'Accepted');

    // Receipt cache eviction and sequence reuse across epochs
    c.applyInput({ type: 'unmark', settled: true, epoch: 1 }, 100);
    assert.equal(c.receiptCache.has('1:100'), true);

    c.applyInput({ type: 'openNewConnection' }); // epoch 2
    assert.equal(c.epoch, 2);
    assert.equal(c.receiptCache.has('1:100'), false, "Receipt cache must be evicted on connection turnover!");

    // Sequence 100 can be reused cleanly in epoch 2!
    const rReuse = c.applyInput({ type: 'preedit', text: 'Y', epoch: 2 }, 100);
    assert.equal(rReuse.status, 'Accepted');
    assert.notEqual(rReuse.status, 'Duplicate');

    console.log("[PASS] Finding F verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Retained Defect Regressions A through I
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- Retained Regressions: Interior Segment Insertion ---");
    const cA = new CandidateBCoordinator("abc");
    cA.applyInput({ type: 'startComposition', range: [1, 2] });
    cA.applyInput({ type: 'preedit', text: 'XYZ' });
    cA.applyInput({ type: 'retarget', range: [2, 2] });
    cA.applyInput({ type: 'preedit', text: 'Q' });
    assert.equal(cA.docText, "aXQYZc");
    assert.deepEqual(cA.markedRange, [2, 3]);

    console.log("--- Retained Regressions: Noncollapsed Selection ---");
    const cB = new CandidateBCoordinator("Hello", null, [1, 4]);
    cB.applyInput({ type: 'preedit', text: 'X' });
    assert.equal(cB.docText, "HXo");

    console.log("--- Retained Regressions: Outward Grapheme Closure ---");
    const cD = new CandidateBCoordinator("a");
    cD.applyInput({ type: 'startComposition', range: [1, 1] });
    cD.applyInput({ type: 'preedit', text: '\u0301' });
    cD.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    cD.applyInput({ type: 'unmark', settled: true });
    assert.equal(cD.docAttributes[0]?.bold, true);
    assert.equal(cD.docAttributes[1]?.bold, true);

    console.log("--- Retained Regressions: Disjoint Island Mapping ---");
    const cH = new CandidateBCoordinator("aa BB cc");
    cH.applyInput({ type: 'startComposition', range: [6, 8] });
    cH.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    cH.applyInput({ type: 'retarget', range: [0, 2] });
    cH.applyInput({ type: 'preedit', text: 'X' });
    cH.applyInput({ type: 'unmark', settled: true });
    assert.equal(cH.docText, "X BB cc");
    const boldIndices = [];
    cH.docAttributes.forEach((attrs, idx) => { if (attrs.bold) boldIndices.push(idx); });
    assert.deepEqual(boldIndices, [0, 5, 6]);

    console.log("--- Retained Regressions: TrackedRange Target Isolation ---");
    const cI = new CandidateBCoordinator("Hello");
    cI.applyInput({ type: 'startComposition', range: [1, 2] });
    cI.applyInput({ type: 'preedit', text: 'X' });
    cI.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'TrackedRange', range: [0, 1] } });
    cI.applyInput({ type: 'unmark', settled: true });
    assert.equal(cI.docAttributes[0]?.bold, true);
    assert.equal(cI.docAttributes[1]?.bold !== true, true);

    console.log("[PASS] Retained regressions verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 8: Real 6-Stage Transactional Failure Injection & State Rollback
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 8] 6-Stage Transactional Failure Injection & Atomic Rollback ---");
    
    // Stage 1: afterTextSplice
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      const snap = c._cloneState();
      const r = c.applyInput({ type: 'preedit', text: 'XYZ', injectFailureAt: 'afterTextSplice' });
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('afterTextSplice'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on afterTextSplice failure!");
      assert.equal(c.docText, "Hello");
    }

    // Stage 2: afterTargetMapping
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      const snap = c._cloneState();
      const r = c.applyInput({ type: 'preedit', text: 'XYZ', injectFailureAt: 'afterTargetMapping' });
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('afterTargetMapping'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on afterTargetMapping failure!");
      assert.equal(c.docText, "Hello");
    }

    // Stage 3: duringOverlayMaterialization
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      const snap = c._cloneState();
      const r = c.applyInput({ type: 'unmark', settled: true, injectFailureAt: 'duringOverlayMaterialization' });
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('duringOverlayMaterialization'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on duringOverlayMaterialization failure!");
      assert.equal(c.docText, "HXYZllo");
      assert.equal(c.compositionState, 'Composing');
    }

    // Stage 4: beforeHistoryPublication
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      const snap = c._cloneState();
      const r = c.applyInput({ type: 'unmark', settled: true, injectFailureAt: 'beforeHistoryPublication' });
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('beforeHistoryPublication'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on beforeHistoryPublication failure!");
      assert.equal(c.undoStack.length, 0);
      assert.equal(c.compositionState, 'Composing');
    }

    // Stage 5: duringCommandPreparation
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'XYZ' });
      const snap = c._cloneState();
      const r = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' },
        injectFailureAt: 'duringCommandPreparation'
      });
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('duringCommandPreparation'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on duringCommandPreparation failure!");
      assert.equal(c.settlementObligation.pendingCommands.length, 0);
    }

    // Stage 6: beforeReceiptPublication
    {
      const c = new CandidateBCoordinator("Hello");
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      const snap = c._cloneState();
      const r = c.applyInput({ type: 'preedit', text: 'XYZ', injectFailureAt: 'beforeReceiptPublication' }, 777);
      assert.equal(r.status, 'Rejected');
      assert.ok(r.reason.includes('beforeReceiptPublication'));
      assert.deepEqual(c._cloneState(), snap, "State must match pre-transaction snapshot on beforeReceiptPublication failure!");
      assert.equal(c.receiptCache.has('0:777'), false, "Receipt cache must not contain receipt for aborted transaction!");
    }

    console.log("[PASS] Block 8: All 6 transactional failure injection stages verified with atomic rollback");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 9: Executable Undo/Redo across Operations with Branch Invalidation
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 9] Executable Undo/Redo across Operations & Branch Invalidation ---");
    const c = new CandidateBCoordinator("Hello");

    // Empty stacks rejection
    const rEmptyUndo = c.applyInput({ type: 'undo' });
    assert.equal(rEmptyUndo.status, 'Rejected');
    assert.equal(rEmptyUndo.reason, 'NothingToUndo');

    const rEmptyRedo = c.applyInput({ type: 'redo' });
    assert.equal(rEmptyRedo.status, 'Rejected');
    assert.equal(rEmptyRedo.reason, 'NothingToRedo');

    // 1. Composition insertion & settlement
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, "HXYZllo");
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].group, 'Composition');

    // 2. Formatting command settlement
    c.applyInput({ type: 'startComposition', range: [1, 4] });
    c.applyInput({
      type: 'queueCommand',
      cmd: { type: 'Format', patch: { bold: true } },
      target: { type: 'CompositionTarget' }
    });
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, "HXYZllo");
    assert.equal(c.docAttributes[1]?.bold, true);
    assert.equal(c.docAttributes[2]?.bold, true);
    assert.equal(c.docAttributes[3]?.bold, true);
    assert.equal(c.undoStack.length, 2);
    assert.equal(c.undoStack[1].group, 'Formatting');

    // 3. Undo formatting
    const rUndoFmt = c.applyInput({ type: 'undo' });
    assert.equal(rUndoFmt.status, 'Accepted');
    assert.equal(c.docText, "HXYZllo");
    assert.equal(c.docAttributes[1]?.bold !== true, true, "Bold must be reverted on formatting undo!");
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.redoStack.length, 1);

    // 4. Redo formatting
    const rRedoFmt = c.applyInput({ type: 'redo' });
    assert.equal(rRedoFmt.status, 'Accepted');
    assert.equal(c.docText, "HXYZllo");
    assert.equal(c.docAttributes[1]?.bold, true, "Bold must be restored on formatting redo!");
    assert.equal(c.undoStack.length, 2);
    assert.equal(c.redoStack.length, 0);

    // 5. Undo formatting then Undo composition
    c.applyInput({ type: 'undo' }); // reverts formatting
    const rUndoComp = c.applyInput({ type: 'undo' }); // reverts composition
    assert.equal(rUndoComp.status, 'Accepted');
    assert.equal(c.docText, "Hello", "Composition undo must revert document text to 'Hello'!");
    assert.equal(c.undoStack.length, 0);
    assert.equal(c.redoStack.length, 2);

    // 6. Redo composition
    const rRedoComp = c.applyInput({ type: 'redo' });
    assert.equal(rRedoComp.status, 'Accepted');
    assert.equal(c.docText, "HXYZllo", "Composition redo must restore text to 'HXYZllo'!");
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.redoStack.length, 1);

    // 7. Branch invalidation: new edit clears redoStack
    c.applyInput({ type: 'preedit', text: 'W', range: [0, 0] });
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, "WHXYZllo");
    assert.equal(c.redoStack.length, 0, "Redo stack must be invalidated and cleared upon new edit!");

    const rDeadRedo = c.applyInput({ type: 'redo' });
    assert.equal(rDeadRedo.status, 'Rejected');
    assert.equal(rDeadRedo.reason, 'NothingToRedo');

    // 8. Partial commit undo/redo
    const cPart = new CandidateBCoordinator("Hello");
    cPart.applyInput({ type: 'startComposition', range: [1, 2] });
    cPart.applyInput({ type: 'preedit', text: 'ABC' });
    cPart.applyInput({ type: 'partialCommit', length: 1 }); // 'A' committed
    cPart.applyInput({ type: 'unmark', settled: true });
    assert.equal(cPart.docText, "HABCllo");
    assert.equal(cPart.undoStack.length, 1);
    assert.equal(cPart.undoStack[0].beforeText, "Hello");
    assert.equal(cPart.undoStack[0].afterText, "HABCllo");

    cPart.applyInput({ type: 'undo' });
    assert.equal(cPart.docText, "Hello", "Undo after partial commit must cleanly restore initial text!");
    cPart.applyInput({ type: 'redo' });
    assert.equal(cPart.docText, "HABCllo", "Redo after partial commit must cleanly restore composite text!");

    // 9. Connection turnover undo
    const cTurn = new CandidateBCoordinator("Hello");
    cTurn.applyInput({ type: 'startComposition', range: [1, 2] });
    cTurn.applyInput({ type: 'preedit', text: 'Z' });
    cTurn.applyInput({ type: 'openNewConnection' }); // turnover preserves visible text & pushes history
    assert.equal(cTurn.docText, "HZllo");
    assert.equal(cTurn.undoStack.length, 1);
    cTurn.applyInput({ type: 'undo' });
    assert.equal(cTurn.docText, "Hello", "Undo after connection turnover must restore pre-session text!");

    console.log("[PASS] Block 9: Executable undo/redo across compositions, formatting, partial commits, turnover, and branch invalidation verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 10: Unicode Extended Grapheme Cluster Conformance & Segmenter Qualification
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 10] Pinned Unicode Extended Grapheme Cluster Conformance ---");

    // Pinned Unicode fixtures
    const unicodeCases = [
      {
        name: "Combining mark (e + acute accent)",
        text: "e\u0301", // 2 code units, 1 cluster
        len: 2,
        probes: [
          { in: [0, 1], exp: [0, 2] },
          { in: [1, 2], exp: [0, 2] },
          { in: [1, 1], exp: [0, 2] }
        ]
      },
      {
        name: "Family emoji ZWJ sequence (man + ZWJ + woman + ZWJ + girl)",
        text: "\u{1F468}\u200D\u{1F469}\u200D\u{1F467}", // 8 code units, 1 cluster
        len: 8,
        probes: [
          { in: [2, 3], exp: [0, 8] },
          { in: [3, 5], exp: [0, 8] },
          { in: [4, 4], exp: [0, 8] }
        ]
      },
      {
        name: "Regional indicator US flag (pair of regional indicators)",
        text: "\u{1F1FA}\u{1F1F8}", // 4 code units, 1 cluster
        len: 4,
        probes: [
          { in: [0, 2], exp: [0, 4] },
          { in: [2, 4], exp: [0, 4] },
          { in: [2, 2], exp: [0, 4] }
        ]
      },
      {
        name: "CRLF sequence",
        text: "a\r\nb", // 4 code units, 3 clusters: 'a', '\r\n', 'b'
        len: 4,
        probes: [
          { in: [1, 2], exp: [1, 3] }, // inside \r
          { in: [2, 3], exp: [1, 3] }, // inside \n
          { in: [2, 2], exp: [1, 3] }  // between \r and \n
        ]
      },
      {
        name: "Devanagari conjunct (ksha = ka + virama + ssa)",
        text: "\u0915\u094D\u0937", // 3 code units, 1 cluster
        len: 3,
        probes: [
          { in: [0, 1], exp: [0, 3] },
          { in: [1, 2], exp: [0, 3] },
          { in: [1, 1], exp: [0, 3] }
        ]
      }
    ];

    for (const uc of unicodeCases) {
      assert.equal(uc.text.length, uc.len, `Unexpected code unit length for fixture ${uc.name}`);
      for (const probe of uc.probes) {
        const coordBound = expandToGraphemeBoundaries(uc.text, probe.in);
        const oracleBound = oracleExpandToGraphemeBoundaries(uc.text, probe.in);
        assert.deepEqual(coordBound, probe.exp, `Coordinator grapheme expansion failed on ${uc.name} probe [${probe.in}]: got [${coordBound}], expected [${probe.exp}]`);
        assert.deepEqual(oracleBound, probe.exp, `Oracle grapheme expansion failed on ${uc.name} probe [${probe.in}]: got [${oracleBound}], expected [${probe.exp}]`);
        assert.deepEqual(coordBound, oracleBound, `Coordinator and Oracle boundary mismatch on ${uc.name} probe [${probe.in}]`);
      }
    }

    // Formatting closure over combining mark inside coordinator
    const cU = new CandidateBCoordinator("cafe\u0301");
    cU.applyInput({ type: 'startComposition', range: [3, 4] }); // targeted 'e' only, without mark
    cU.applyInput({
      type: 'queueCommand',
      cmd: { type: 'Format', patch: { italic: true } },
      target: { type: 'CompositionTarget' }
    });
    cU.applyInput({ type: 'unmark', settled: true });
    assert.equal(cU.docAttributes[3]?.italic, true, "Base char 'e' must be italic");
    assert.equal(cU.docAttributes[4]?.italic, true, "Combining mark '\\u0301' must expand to italic via grapheme closure");

    console.log("[PASS] Block 10: Unicode extended grapheme cluster fixtures, coordinator/oracle boundary agreement, and formatting closure verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 11: Repeated Undo/Redo Cycles & Empty-Document Preimages (Finding A)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 11] Repeated Undo/Redo Cycles & Empty Preimages ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Empty document sequence ["x", "", "x", ""] with exact selections
      const c = new Impl("", null, [0, 0]);
      c.applyInput({ type: 'commit', text: 'x', selection: [1, 1] });
      assert.equal(c.docText, 'x');
      assert.deepEqual(c.selection, [1, 1]);

      const u1 = c.applyInput({ type: 'undo' });
      assert.equal(u1.status, 'Accepted');
      assert.equal(c.docText, '', 'First undo must restore empty string');
      assert.deepEqual(c.selection, [0, 0], 'First undo must restore empty document selection');

      const r1 = c.applyInput({ type: 'redo' });
      assert.equal(r1.status, 'Accepted');
      assert.equal(c.docText, 'x', 'First redo must restore "x"');
      assert.deepEqual(c.selection, [1, 1]);

      const u2 = c.applyInput({ type: 'undo' });
      assert.equal(u2.status, 'Accepted');
      assert.equal(c.docText, '', 'Second undo must restore empty string');
      assert.deepEqual(c.selection, [0, 0]);

      // 2. Whole document deletion
      const c2 = new Impl("abc", null, [0, 3]);
      c2.applyInput({ type: 'commit', text: '', range: [0, 3], selection: [0, 0] });
      assert.equal(c2.docText, '');
      assert.deepEqual(c2.selection, [0, 0]);

      c2.applyInput({ type: 'undo' });
      assert.equal(c2.docText, 'abc');
      assert.deepEqual(c2.selection, [0, 3]);

      c2.applyInput({ type: 'redo' });
      assert.equal(c2.docText, '');
      assert.deepEqual(c2.selection, [0, 0]);

      c2.applyInput({ type: 'undo' });
      assert.equal(c2.docText, 'abc');
      assert.deepEqual(c2.selection, [0, 3]);

      // 3. Repeated 12-cycle stability with immutable history record preservation
      const c3 = new Impl("initial", null, [0, 7]);
      c3.applyInput({ type: 'commit', text: 'modified', range: [0, 7], selection: [8, 8] });
      for (let cycle = 0; cycle < 12; cycle++) {
        c3.applyInput({ type: 'undo' });
        assert.equal(c3.docText, 'initial', `Cycle ${cycle} undo text failed`);
        assert.deepEqual(c3.selection, [0, 7], `Cycle ${cycle} undo selection failed`);
        assert.equal(c3.redoStack.length, 1, `Cycle ${cycle} redoStack length failed`);
        assert.equal(c3.redoStack[0].beforeText, 'initial');
        assert.equal(c3.redoStack[0].afterText, 'modified');
        assert.deepEqual(c3.redoStack[0].beforeSelection, [0, 7]);
        assert.deepEqual(c3.redoStack[0].afterSelection, [8, 8]);

        c3.applyInput({ type: 'redo' });
        assert.equal(c3.docText, 'modified', `Cycle ${cycle} redo text failed`);
        assert.deepEqual(c3.selection, [8, 8], `Cycle ${cycle} redo selection failed`);
        assert.equal(c3.undoStack.length, 1, `Cycle ${cycle} undoStack length failed`);
        assert.equal(c3.undoStack[0].beforeText, 'initial');
        assert.equal(c3.undoStack[0].afterText, 'modified');
        assert.deepEqual(c3.undoStack[0].beforeSelection, [0, 7]);
        assert.deepEqual(c3.undoStack[0].afterSelection, [8, 8]);
      }

      // 4. Formatting and composition distinct undo boundaries
      const c4 = new Impl("Hello");
      c4.applyInput({ type: 'startComposition', range: [1, 2] });
      c4.applyInput({ type: 'preedit', text: 'X' });
      c4.applyInput({ type: 'unmark', settled: true });
      assert.equal(c4.docText, 'HXllo');
      // Format
      c4.applyInput({ type: 'startComposition', range: [1, 2] });
      c4.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c4.applyInput({ type: 'unmark', settled: true });
      assert.equal(c4.undoStack.length, 2);
      assert.equal(c4.undoStack[0].group, 'Composition');
      assert.equal(c4.undoStack[1].group, 'Formatting');
      c4.applyInput({ type: 'undo' }); // undo formatting only
      assert.equal(c4.docText, 'HXllo');
      assert.equal(c4.docAttributes[1]?.bold !== true, true);
      c4.applyInput({ type: 'undo' }); // undo composition
      assert.equal(c4.docText, 'Hello');

      // 5. Branch invalidation after undo
      const c5 = new Impl("start");
      c5.applyInput({ type: 'commit', text: ' edit1', range: [5, 5] });
      c5.applyInput({ type: 'undo' });
      assert.equal(c5.redoStack.length, 1);
      c5.applyInput({ type: 'commit', text: ' edit2', range: [5, 5] });
      assert.equal(c5.redoStack.length, 0, 'New editing after undo must clear redo stack');

      // 6. Undo during active composition flushes & preserves overlay on redoStack
      const c6 = new Impl("Hello");
      c6.applyInput({ type: 'commit', text: ' World', range: [5, 5] });
      c6.applyInput({ type: 'startComposition', range: [5, 11] });
      c6.applyInput({ type: 'preedit', text: ' Earth' });
      assert.equal(c6.docText, 'Hello Earth');

      // Undo during active composition with synchronous adapter boundary restores pre-session docText
      const uActive = c6.applyInput({
        type: 'undo',
        syncBoundary: true,
        settlementEvidence: {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [5, 11],
          epoch: c6.epoch !== undefined ? c6.epoch : c6.activeEpoch,
          sessionId: c6.activeSessionId,
          generation: c6.sessionRevision !== undefined ? c6.sessionRevision : c6.sessRev,
          recordId: 'rec-sync-c6'
        }
      });
      assert.equal(uActive.status, 'Accepted');
      if (c6.compositionState) assert.equal(c6.compositionState, 'Idle');
      const marked = (c6.markedRange !== undefined) ? c6.markedRange : c6.markedSpan;
      assert.equal(marked, null);
      assert.equal(c6.docText, 'Hello World', 'Undo during active composition must revert to pre-session state');
      assert.equal(c6.redoStack.length, 1, 'Active composition must be preserved on redoStack');
      assert.equal(c6.redoStack[0].group, 'Composition');
      assert.equal(c6.redoStack[0].afterText, 'Hello Earth');

      // Redo restores active composition overlay
      const rActive = c6.applyInput({ type: 'redo' });
      assert.equal(rActive.status, 'Accepted');
      assert.equal(c6.docText, 'Hello Earth');

      // Stepwise undos back to base
      c6.applyInput({ type: 'undo' });
      assert.equal(c6.docText, 'Hello World');
      c6.applyInput({ type: 'undo' });
      assert.equal(c6.docText, 'Hello');

      // Stepwise redos forward to peak
      c6.applyInput({ type: 'redo' });
      assert.equal(c6.docText, 'Hello World');
      c6.applyInput({ type: 'redo' });
      assert.equal(c6.docText, 'Hello Earth');
    }
    console.log("[PASS] Block 11: Repeated undo/redo cycles, empty preimages, distinct boundaries, branch invalidation, and active composition undo verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 12: Native Removal across Lifecycle States (Finding B)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 12] Native Removal across Lifecycle States ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Idle native removal: tracked in undo history
      const c1 = new Impl("abc", null, [1, 2]);
      const r1 = c1.applyInput({ type: 'nativeRemoval', range: [1, 2], replacementText: '' });
      assert.equal(r1.status, 'Accepted');
      assert.equal(c1.docText, 'ac');
      assert.equal(c1.undoStack.length, 1);
      assert.equal(c1.undoStack[0].group, 'NativeRemoval');
      const uRes = c1.applyInput({ type: 'undo' });
      assert.equal(uRes.status, 'Accepted');
      assert.equal(c1.docText, 'abc', 'Undo must restore original text after native removal');
      const redoRes = c1.applyInput({ type: 'redo' });
      assert.equal(redoRes.status, 'Accepted');
      assert.equal(c1.docText, 'ac', 'Redo must re-apply native removal');

      // 2. Composing native removal: finalizes composition and preserves session snapshot
      const c2 = new Impl("Hello", null, [1, 2]);
      c2.applyInput({ type: 'startComposition', range: [1, 2] });
      c2.applyInput({ type: 'preedit', text: 'XYZ' });
      assert.equal(c2.docText, 'HXYZllo');
      const r2 = c2.applyInput({ type: 'nativeRemoval', range: [1, 4], replacementText: 'Q' });
      assert.equal(r2.status, 'Accepted');
      assert.equal(c2.docText, 'HQllo');
      assert.equal(c2.compositionState || 'Idle', 'Idle');
      assert.equal(c2.undoStack.length, 1);
      assert.equal(c2.undoStack[0].group, 'Composition');
      assert.equal(c2.undoStack[0].beforeText, 'Hello');
      assert.equal(c2.undoStack[0].afterText, 'HQllo');

      // 3. AwaitingSettlement native removal: cleans active fence and finalizes history
      const c3 = new Impl("Hello", null, [1, 2]);
      c3.applyInput({ type: 'startComposition', range: [1, 2] });
      c3.applyInput({ type: 'preedit', text: 'X' });
      const rUnmark = c3.applyInput({ type: 'unmark', settled: false });
      assert.equal(rUnmark.status, 'Accepted');
      const r3 = c3.applyInput({ type: 'nativeRemoval', range: [1, 2], replacementText: 'Z' });
      assert.equal(r3.status, 'Accepted');
      assert.equal(c3.docText, 'HZllo');
      assert.equal(c3.activeSettlementToken, null);
      assert.equal(c3.undoStack.length, 1);
      assert.equal(c3.undoStack[0].beforeText, 'Hello');
      assert.equal(c3.undoStack[0].afterText, 'HZllo');

      // 4. Whole document removal
      const c4 = new Impl("Everything");
      c4.applyInput({ type: 'nativeRemoval', range: [0, 10], replacementText: '' });
      assert.equal(c4.docText, '');
      c4.applyInput({ type: 'undo' });
      assert.equal(c4.docText, 'Everything');
    }
    console.log("[PASS] Block 12: Native removal verified across Idle, Composing, and AwaitingSettlement with full undo/redo");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 13: Disposal Preservation across Lifecycle States (Finding C)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 13] Disposal Preservation across Lifecycle States ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Before any mutation: closes cleanly
      const c1 = new Impl("Hello");
      const d1 = c1.applyInput({ type: 'dispose' });
      assert.equal(d1.status, 'Accepted');
      assert.equal(c1.connectionState, 'Closed');
      assert.equal(c1.docText, 'Hello');
      assert.equal(c1.undoStack.length, 0);

      // 2. During ordinary preedit: materializes overlay and records history
      const c2 = new Impl("Hello", null, [1, 2]);
      c2.applyInput({ type: 'startComposition', range: [1, 2] });
      c2.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c2.docText, 'HXllo');
      const d2 = c2.applyInput({ type: 'dispose' });
      assert.equal(d2.status, 'Accepted');
      assert.equal(c2.connectionState, 'Closed');
      assert.equal(c2.docText, 'HXllo', 'Disposal must preserve accepted visible text');
      assert.equal(c2.undoStack.length, 1);
      assert.equal(c2.undoStack[0].beforeText, 'Hello');
      assert.equal(c2.undoStack[0].afterText, 'HXllo');

      // 3. After partial commit: materializes remaining overlay
      const c3 = new Impl("Hello", null, [1, 2]);
      c3.applyInput({ type: 'startComposition', range: [1, 2] });
      c3.applyInput({ type: 'preedit', text: 'X' });
      c3.applyInput({ type: 'partialCommit', length: 1 });
      c3.applyInput({ type: 'preedit', text: 'Y' });
      assert.equal(c3.docText, 'HXYllo');
      const d3 = c3.applyInput({ type: 'dispose' });
      assert.equal(d3.status, 'Accepted');
      assert.equal(c3.docText, 'HXYllo');
      assert.equal(c3.undoStack.length, 1);
      assert.equal(c3.undoStack[0].beforeText, 'Hello');
      assert.equal(c3.undoStack[0].afterText, 'HXYllo');

      // 4. After nonterminal unmark: materializes content and clears active fence
      const c4 = new Impl("Hello", null, [1, 2]);
      c4.applyInput({ type: 'startComposition', range: [1, 2] });
      c4.applyInput({ type: 'preedit', text: 'X' });
      c4.applyInput({ type: 'unmark', settled: false });
      const d4 = c4.applyInput({ type: 'dispose' });
      assert.equal(d4.status, 'Accepted');
      assert.equal(c4.docText, 'HXllo');
      assert.equal(c4.activeSettlementToken, null);

      // 5. Queued commands cancelled on disposal
      const c5 = new Impl("Hello");
      c5.applyInput({ type: 'startComposition', range: [1, 2] });
      c5.applyInput({ type: 'preedit', text: 'X' });
      const qRes = c5.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
      c5.applyInput({ type: 'dispose' });
      assert.equal(c5.commandOutcomes.get(qRes.ticketId).status, 'Cancelled');
      assert.equal(c5.commandOutcomes.get(qRes.ticketId).reason, 'Disposed');

      // 6. Repeated dispose rejected
      const c6 = new Impl("Hello");
      c6.applyInput({ type: 'dispose' });
      const dAgain = c6.applyInput({ type: 'dispose' });
      assert.equal(dAgain.status, 'Rejected');
      assert.equal(dAgain.reason, 'ConnectionClosed');
    }
    console.log("[PASS] Block 13: Disposal preservation verified across all lifecycle states, partial commits, and command cancellation");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 14: Resumed Preedit Unified Target Resolution (Finding D)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 14] Resumed Preedit Unified Target Resolution ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Omitted range after nonterminal unmark targets selection
      const c1 = new Impl("Hello", null, [1, 2]);
      c1.applyInput({ type: 'startComposition', range: [1, 2] });
      c1.applyInput({ type: 'preedit', text: 'X' });
      assert.equal(c1.docText, 'HXllo');
      c1.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      // Omitted range should target selection [2, 2], inserting 'Y' -> "HXYllo"
      const rPre = c1.applyInput({ type: 'preedit', text: 'Y' });
      assert.equal(rPre.status, 'Accepted');
      assert.equal(c1.docText, 'HXYllo', 'Omitted range after nonterminal unmark must target selection');

      // 2. Explicit range after nonterminal unmark targets specified range
      const c2 = new Impl("Hello", null, [1, 2]);
      c2.applyInput({ type: 'startComposition', range: [1, 2] });
      c2.applyInput({ type: 'preedit', text: 'X' });
      c2.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
      // Explicit range [1, 2] replaces 'X' with 'Y' -> "HYllo"
      const rPre2 = c2.applyInput({ type: 'preedit', text: 'Y', range: [1, 2] });
      assert.equal(rPre2.status, 'Accepted');
      assert.equal(c2.docText, 'HYllo', 'Explicit range must override selection fallback');

      // 3. Selection validation on growth and shrinkage after nonterminal unmark
      const c3 = new Impl("Hello", null, [1, 2]);
      c3.applyInput({ type: 'startComposition', range: [1, 2] });
      c3.applyInput({ type: 'preedit', text: 'X' });
      c3.applyInput({ type: 'unmark', settled: false, selection: [2, 2] }); // docText: "HXllo" (len 5)
      // Growth edit: preedit "YYYY" at selection [2, 2] -> proposed length: 5 - 0 + 4 = 9. Selection [9, 9] should be valid!
      const rGrow = c3.applyInput({ type: 'preedit', text: 'YYYY', selection: [9, 9] });
      assert.equal(rGrow.status, 'Accepted');
      assert.equal(c3.docText, 'HXYYYYllo');
      assert.deepEqual(c3.selection, [9, 9]);

      // Shrinkage edit: selection out of bounds for proposed length must be rejected
      const rShrinkReject = c3.applyInput({ type: 'preedit', text: '', selection: [9, 9] }); // deletes "YYYY", len becomes 5
      assert.equal(rShrinkReject.status, 'Rejected');
      assert.equal(rShrinkReject.reason, 'InvalidSelection');

      // 4. Reversed selections normalized
      const c4 = new Impl("Hello");
      c4.applyInput({ type: 'startComposition', range: [1, 3], selection: [3, 1] });
      assert.deepEqual(c4.selection, [3, 1]);
    }
    console.log("[PASS] Block 14: Resumed preedit unified target resolution, omitted vs explicit ranges, and post-edit selection bounds verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 15: Disjoint Rollback Target Mapping across Opposing / Zero-Delta Edits (Defect 1)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 15] Disjoint Rollback Target Mapping across Opposing / Zero-Delta Edits ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Asymmetric net delta: XYZ at [1, 2) (+2) and delete 'f' at [7, 8) (-1)
      const c1 = new Impl("abcdef", null, [0, 0]);
      c1.applyInput({ type: 'startComposition', range: [1, 2] });
      c1.applyInput({ type: 'preedit', text: 'XYZ' }); // "aXYZcdef"
      const qRes1 = c1.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [5, 6] } // 'd' in visible text
      });
      c1.applyInput({ type: 'retarget', range: [7, 8] });
      c1.applyInput({ type: 'preedit', text: '' }); // deletes 'f' -> "aXYZcde"
      const cancelRes1 = c1.applyInput({ type: 'cancel' });
      assert.equal(cancelRes1.status, 'Accepted');
      assert.equal(c1.docText, "abcdef");
      const outcome1 = c1.commandOutcomes.get(qRes1.ticketId);
      assert.equal(outcome1?.status, 'Applied');
      assert.deepEqual(outcome1?.range, [3, 4], "Target 'd' must map to committed index [3, 4)!");
      assert.equal(c1.docAttributes[3]?.bold, true, "'d' must be bold!");
      assert.equal(c1.docAttributes[4]?.bold, undefined, "'e' must remain plain!");

      // 2. Opposing edits with netRollbackDelta === 0:
      // 'abcdef': expand 'b' at [1, 2) to 'XY' (+1), shrink 'e' at [5, 6) to '' (-1).
      // Net delta = +1 - 1 = 0!
      const cZero = new Impl("abcdef", null, [0, 0]);
      cZero.applyInput({ type: 'startComposition', range: [1, 2] });
      cZero.applyInput({ type: 'preedit', text: 'XY' }); // "aXYcdef"
      const qResZero = cZero.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { italic: true } },
        target: { type: 'TrackedRange', range: [4, 5] } // 'd' at visible index 4
      });
      cZero.applyInput({ type: 'retarget', range: [6, 7] }); // 'e' at visible index 6
      cZero.applyInput({ type: 'preedit', text: '' }); // delete 'e' -> "aXYcdf"
      cZero.applyInput({ type: 'cancel' });
      assert.equal(cZero.docText, "abcdef");
      const outcomeZero = cZero.commandOutcomes.get(qResZero.ticketId);
      assert.equal(outcomeZero?.status, 'Applied');
      assert.deepEqual(outcomeZero?.range, [3, 4], "Opposing edits with zero net delta must map 'd' to [3, 4)!");
      assert.equal(cZero.docAttributes[3]?.italic, true, "Character 'd' at index 3 must be italic!");
      assert.equal(cZero.docAttributes[4]?.italic, undefined, "Character 'e' at index 4 must NOT be italic!");

      // 3. Rollback target within discarded provisional units -> TargetDeleted
      const cDiscard = new Impl("abcdef");
      cDiscard.applyInput({ type: 'startComposition', range: [1, 2] });
      cDiscard.applyInput({ type: 'preedit', text: 'XYZ' });
      const qDiscard = cDiscard.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { underline: true } },
        target: { type: 'TrackedRange', range: [2, 3] } // 'Y' in provisional 'XYZ'
      });
      cDiscard.applyInput({ type: 'cancel' });
      assert.equal(cDiscard.docText, "abcdef");
      assert.equal(cDiscard.commandOutcomes.get(qDiscard.ticketId)?.status, 'Rejected');
      assert.equal(cDiscard.commandOutcomes.get(qDiscard.ticketId)?.reason, 'TargetDeleted');

      // 4. Repeated identical characters with distinct attribute identities
      const initAttrs = [
        { id: 'u0' }, { id: 'u1' }, { id: 'u2' }, { id: 'u3' }, { id: 'u4' }, { id: 'u5' }
      ];
      const cIdent = new Impl("aaaaaa", initAttrs, [0, 0]);
      cIdent.applyInput({ type: 'startComposition', range: [1, 3] }); // 'aa' [1, 3) (ids u1, u2)
      cIdent.applyInput({ type: 'preedit', text: 'XYZ' }); // 'aXYZa aa'
      cIdent.applyInput({ type: 'retarget', range: [5, 6] }); // 'a' (id u4)
      cIdent.applyInput({ type: 'preedit', text: '' }); // deleted
      const qIdent = cIdent.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [4, 5] } // 'a' (id u3)
      });
      cIdent.applyInput({ type: 'cancel' });
      assert.equal(cIdent.docText, "aaaaaa");
      const outIdent = cIdent.commandOutcomes.get(qIdent.ticketId);
      assert.equal(outIdent?.status, 'Applied');
      assert.deepEqual(outIdent?.range, [3, 4], "Target with id u3 must map back to committed index [3, 4)!");
      assert.equal(cIdent.docAttributes[3]?.bold, true, "Only character at index 3 (id u3) must be bold!");
      assert.equal(cIdent.docAttributes[0]?.bold, undefined);
      assert.equal(cIdent.docAttributes[1]?.bold, undefined);
      assert.equal(cIdent.docAttributes[2]?.bold, undefined);
      assert.equal(cIdent.docAttributes[4]?.bold, undefined);
      assert.equal(cIdent.docAttributes[5]?.bold, undefined);

      // 5. 3+ disjoint provisional islands with queued commands between them
      const cIslands = new Impl("abcdefghij", null, [0, 0]);
      cIslands.applyInput({ type: 'startComposition', range: [1, 2] }); // 'b'
      cIslands.applyInput({ type: 'preedit', text: 'B1B2' });
      cIslands.applyInput({ type: 'retarget', range: [7, 8] }); // 'e'
      cIslands.applyInput({ type: 'preedit', text: 'E1E2' });
      cIslands.applyInput({ type: 'retarget', range: [13, 14] }); // 'h'
      cIslands.applyInput({ type: 'preedit', text: 'H1H2' });
      const qD = cIslands.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [6, 7] } // 'd'
      });
      const qF = cIslands.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { italic: true } },
        target: { type: 'TrackedRange', range: [11, 12] } // 'f'
      });
      cIslands.applyInput({ type: 'cancel' });
      assert.equal(cIslands.docText, "abcdefghij");
      const outD = cIslands.commandOutcomes.get(qD.ticketId);
      assert.equal(outD?.status, 'Applied');
      assert.deepEqual(outD?.range, [3, 4]);
      assert.equal(cIslands.docAttributes[3]?.bold, true);
      const outF = cIslands.commandOutcomes.get(qF.ticketId);
      assert.equal(outF?.status, 'Applied');
      assert.deepEqual(outF?.range, [5, 6]);
      assert.equal(cIslands.docAttributes[5]?.italic, true);

      // 6. Changes purely before and purely after captured ranges
      const cAround = new Impl("abcdefghij", null, [0, 0]);
      const qEF = cAround.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [4, 6] }
      });
      cAround.applyInput({ type: 'startComposition', range: [1, 2] });
      cAround.applyInput({ type: 'preedit', text: 'XYZ' });
      cAround.applyInput({ type: 'retarget', range: [10, 11] });
      cAround.applyInput({ type: 'preedit', text: 'W' });
      cAround.applyInput({ type: 'cancel' });
      assert.equal(cAround.docText, "abcdefghij");
      const outEF = cAround.commandOutcomes.get(qEF.ticketId);
      assert.equal(outEF?.status, 'Applied');
      assert.deepEqual(outEF?.range, [4, 6]);
      assert.equal(cAround.docAttributes[4]?.bold, true);
      assert.equal(cAround.docAttributes[5]?.bold, true);

      // 7. Partial commit followed by cancellation with multiple segments
      const cPartialCancel = new Impl("abcdef", null, [0, 0]);
      cPartialCancel.applyInput({ type: 'startComposition', range: [1, 2] });
      cPartialCancel.applyInput({ type: 'preedit', text: 'XYZ' });
      cPartialCancel.applyInput({ type: 'partialCommit', length: 2 }); // 'XY' committed
      const qCommitted = cPartialCancel.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [1, 3] }
      });
      const qProvisional = cPartialCancel.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { italic: true } },
        target: { type: 'TrackedRange', range: [3, 4] }
      });
      const qAfter = cPartialCancel.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { underline: true } },
        target: { type: 'TrackedRange', range: [5, 6] }
      });
      cPartialCancel.applyInput({ type: 'cancel' });
      assert.equal(cPartialCancel.docText, "aXYcdef");
      assert.equal(cPartialCancel.commandOutcomes.get(qCommitted.ticketId)?.status, 'Applied');
      assert.deepEqual(cPartialCancel.commandOutcomes.get(qCommitted.ticketId)?.range, [1, 3]);
      assert.equal(cPartialCancel.commandOutcomes.get(qProvisional.ticketId)?.status, 'Rejected');
      assert.equal(cPartialCancel.commandOutcomes.get(qProvisional.ticketId)?.reason, 'TargetDeleted');
      assert.equal(cPartialCancel.commandOutcomes.get(qAfter.ticketId)?.status, 'Applied');
      assert.deepEqual(cPartialCancel.commandOutcomes.get(qAfter.ticketId)?.range, [4, 5]);
    }
    console.log("[PASS] Block 15: Disjoint rollback target mapping across opposing/zero-delta edits, repeated identical chars, 3+ islands, and partial commits verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 16: Idle Command Execution and Terminal Outcomes (Defect 2)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 16] Idle Command Execution and Terminal Outcomes ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("abcdef", null, [0, 0]);
      
      // 1. Non-collapsed formatting command in Idle executes immediately and pushes Formatting undo group
      const q1 = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [1, 3] }
      });
      assert.equal(q1.status, 'Applied');
      assert.ok(q1.ticketId);
      const outcome1 = c.commandOutcomes.get(q1.ticketId);
      assert.equal(outcome1?.status, 'Applied');
      assert.deepEqual(outcome1?.range, [1, 3]);
      assert.equal(c.docAttributes[1]?.bold, true);
      assert.equal(c.docAttributes[2]?.bold, true);
      assert.equal(c.docAttributes[0]?.bold, undefined);
      assert.equal(c.docAttributes[3]?.bold, undefined);
      assert.equal(c.undoStack.length, 1);
      assert.equal(c.undoStack[0].group, 'Formatting');

      // 2. Collapsed target in Idle updates typingIntent
      const qCollapsed = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { italic: true } },
        target: { type: 'TrackedRange', range: [3, 3] }
      });
      assert.equal(qCollapsed.status, 'Applied');
      assert.equal(qCollapsed.typingIntentUpdated, true);
      assert.equal(c.typingIntent.italic, true);

      // 3. SelectionTarget in Idle with selection [4, 6]
      c.applyInput({ type: 'setSelection', selection: [4, 6] });
      const qSel = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { underline: true } },
        target: { type: 'SelectionTarget' }
      });
      assert.equal(qSel.status, 'Applied');
      assert.equal(c.docAttributes[4]?.underline, true);
      assert.equal(c.docAttributes[5]?.underline, true);

      // 4. Invalid target in Idle rejected with terminal outcome
      const qInv = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [10, 20] }
      });
      assert.equal(qInv.status, 'Rejected');
      assert.equal(qInv.reason, 'InvalidTargetRange');
      if (c instanceof CandidateBCoordinator) assert.equal(qInv.ticketId, undefined);
      else assert.equal(c.commandOutcomes.get(qInv.ticketId)?.status, 'Rejected');

      // 5. CompositionTarget in Idle rejected with NoActiveComposition
      const qComp = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' }
      });
      assert.equal(qComp.status, 'Rejected');
      assert.equal(qComp.reason, 'NoActiveComposition');
      assert.equal(c.commandOutcomes.get(qComp.ticketId)?.status, 'Rejected');

      // 6. Commands after disposal rejected as ConnectionClosed
      const cDisp = new Impl("abc");
      cDisp.applyInput({ type: 'dispose' });
      const qAfterDisp = cDisp.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [0, 1] }
      });
      assert.equal(qAfterDisp.status, 'Rejected');
      assert.equal(qAfterDisp.reason, 'ConnectionClosed');

      // 7. No-op formatting does NOT bump documentRevision or create redundant undo group
      const cNoop = new Impl("abc", [{ bold: true }, { bold: true }, {}]);
      const prevRev = cNoop.documentRevision;
      const prevUndoLen = cNoop.undoStack.length;
      const qNoop = cNoop.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [0, 2] }
      });
      assert.equal(qNoop.status, 'Applied');
      const outNoop = cNoop.commandOutcomes.get(qNoop.ticketId);
      assert.equal(outNoop?.status, 'Applied');
      assert.equal(outNoop?.noOp, true);
      assert.equal(cNoop.documentRevision, prevRev);
      assert.equal(cNoop.undoStack.length, prevUndoLen);

      // 8. Multiple conflicting formatting commands execute sequentially
      const cConflict = new Impl("abc");
      cConflict.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'TrackedRange', range: [0, 2] }
      });
      cConflict.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: false } },
        target: { type: 'TrackedRange', range: [1, 3] }
      });
      assert.equal(cConflict.docAttributes[0]?.bold, true);
      assert.equal(cConflict.docAttributes[1]?.bold, false);
      assert.equal(cConflict.docAttributes[2]?.bold, false);

      // 9. Zero pending commands remain in Idle
      const pendingCount = (c.settlementObligation ? c.settlementObligation.pendingCommands.length : Array.from(c.commandLedger.values()).filter(x => x.status === 'Pending').length);
      assert.equal(pendingCount, 0, "No commands may remain pending in Idle!");
    }
    console.log("[PASS] Block 16: Idle command execution, terminal outcomes, disposal rejection, no-op formatting, and typing intent verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 17: Cross-Epoch Duplicate Isolation and High-Water Mark Expiration (Defect 3)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 17] Cross-Epoch Duplicate Isolation & High-Water Mark Expiration ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("Hello");
      c.applyInput({ type: 'openNewConnection' }); // epoch 1
      assert.equal(c.epoch, 1);

      // Step 1: Sequence 1 in Epoch 1 is accepted and receipt cached
      const r1 = c.applyInput({ type: 'preedit', text: 'X', epoch: 1 }, 1);
      assert.equal(r1.status, 'Accepted');
      assert.equal(c.receiptCache.has('1:1'), true);

      // Step 2: Sequence 1 from Epoch 0 arrives -> MUST be Rejected as StaleEpoch, NOT Duplicate
      const rOld = c.applyInput({ type: 'preedit', text: 'OLD', epoch: 0 }, 1);
      assert.equal(rOld.status, 'Rejected', "Old epoch callback must be rejected");
      assert.equal(rOld.reason, 'StaleEpoch', "Must be rejected as StaleEpoch, not Duplicate");
      assert.equal(c.docText, "XHello");

      // Step 3: Sequence 1 from Future Epoch 2 arrives -> MUST be Rejected as FutureEpoch
      const rFuture = c.applyInput({ type: 'preedit', text: 'FUTURE', epoch: 2 }, 1);
      assert.equal(rFuture.status, 'Rejected');
      assert.equal(rFuture.reason, 'FutureEpoch');

      // Step 4: Sequence 1 in current Epoch 1 arrives again -> MUST return Duplicate with cached receipt
      const rDup = c.applyInput({ type: 'preedit', text: 'X', epoch: 1 }, 1);
      assert.equal(rDup.status, 'Duplicate');
      assert.ok(rDup.cachedReceipt);

      // Step 5: High-water mark advances
      c.applyInput({ type: 'unmark', settled: true, epoch: 1 }, 5);
      // Evict sequence 2 manually from receiptCache to test DuplicateReceiptExpired
      c.receiptCache.delete('1:2');
      const rExp = c.applyInput({ type: 'preedit', text: 'LOST', epoch: 1 }, 2);
      assert.equal(rExp.status, 'DuplicateReceiptExpired');

      // Step 6: Turnover to Epoch 2 evicts cache and allows sequence 1 reuse
      c.applyInput({ type: 'openNewConnection' }); // epoch 2
      assert.equal(c.epoch, 2);
      assert.equal(c.receiptCache.has('1:1'), false, "Receipt cache must be evicted on connection replacement");

      const rReused = c.applyInput({ type: 'preedit', text: 'Y', epoch: 2 }, 1);
      assert.equal(rReused.status, 'Accepted');
      assert.notEqual(rReused.status, 'Duplicate');
      assert.equal(c.receiptCache.has('2:1'), true);

      // Step 7: Dedicated applyNativeCallback boundary validation
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'A' }, null, 1).reason, 'MissingOrMalformedConnectionEpoch');
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'A' }, -1, 1).reason, 'MissingOrMalformedConnectionEpoch');
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'A' }, 2, null).reason, 'MissingOrMalformedSequenceStamp');
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'A' }, 2, 0).reason, 'MissingOrMalformedSequenceStamp');
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'A' }, 2, -1).reason, 'MissingOrMalformedSequenceStamp');
      const okNative = c.applyNativeCallback({ type: 'preedit', text: 'Z' }, 2, 2);
      assert.equal(okNative.status, 'Accepted');
      assert.equal(c.receiptCache.has('2:2'), true);

      // Step 8: Closed coordinator rejects native callbacks
      c.applyInput({ type: 'dispose' });
      assert.equal(c.applyNativeCallback({ type: 'preedit', text: 'Z' }, 2, 3).reason, 'ConnectionClosed');
    }
    console.log("[PASS] Block 17: Cross-epoch duplicate isolation, admission precedence, high-water expiration, and applyNativeCallback boundary verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 18: Explicit Ordered Queue Settlement Policy on Undo/Redo (Defect 4)
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 18] Explicit Ordered Queue Settlement Policy on Undo/Redo ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      const c = new Impl("abc", null, [1, 2]);
      c.applyInput({ type: 'startComposition', range: [1, 2] });
      c.applyInput({ type: 'preedit', text: 'X' }); // visible: "aXc", preedit replaces 'b'
      const qRes = c.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' }
      });
      assert.equal(qRes.status, 'Accepted');
      assert.ok(qRes.ticketId);

      // First undo: Undoes the formatting group -> composed text 'aXc' remains intact, but bold is removed!
      const u1 = c.applyInput({
        type: 'undo',
        syncBoundary: true,
        settlementEvidence: {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: c.epoch !== undefined ? c.epoch : c.activeEpoch,
          sessionId: c.activeSessionId,
          generation: c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev,
          recordId: 'rec-sync-u1-b18'
        }
      });
      assert.equal(u1.status, 'Accepted');
      assert.equal(u1.group, 'Formatting', "First undo must pop the Formatting group");
      assert.equal(c.docText, "aXc", "Document text must retain composed text 'aXc'");
      assert.equal(c.docAttributes[1]?.bold, undefined, "Composed character 'X' must have bold removed by first undo");
      assert.equal(c.commandOutcomes.get(qRes.ticketId)?.status, 'Applied', "Queued command received terminal Applied outcome");

      // Second undo: Undoes the composition group -> reverts to pre-session "abc"
      const u2 = c.applyInput({ type: 'undo' });
      assert.equal(u2.status, 'Accepted');
      assert.equal(u2.group, 'Composition', "Second undo must pop the Composition group");
      assert.equal(c.docText, "abc", "Second undo must revert to pre-session text 'abc'");
      assert.equal(c.docAttributes[1]?.bold, undefined);

      // Redo 1: Restores composition group -> docText becomes 'aXc' (plain)
      const r1 = c.applyInput({ type: 'redo' });
      assert.equal(r1.status, 'Accepted');
      assert.equal(r1.group, 'Composition');
      assert.equal(c.docText, "aXc");
      assert.equal(c.docAttributes[1]?.bold, undefined);

      // Redo 2: Restores formatting group -> 'X' becomes bold!
      const r2 = c.applyInput({ type: 'redo' });
      assert.equal(r2.status, 'Accepted');
      assert.equal(r2.group, 'Formatting');
      assert.equal(c.docText, "aXc");
      assert.equal(c.docAttributes[1]?.bold, true, "Redo must restore bold formatting on 'X'");

      // 6. Undo during active composition with prior committed history
      const cPrior = new Impl("Hello");
      cPrior.applyInput({ type: 'commit', text: ' World', range: [5, 5] });
      assert.equal(cPrior.docText, 'Hello World');
      assert.equal(cPrior.undoStack.length, 1);
      cPrior.applyInput({ type: 'startComposition', range: [0, 2] });
      assert.equal(cPrior.compositionState, 'Composing');
      const uPrior = cPrior.applyInput({
        type: 'undo',
        syncBoundary: true,
        settlementEvidence: {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [0, 2],
          epoch: cPrior.epoch !== undefined ? cPrior.epoch : cPrior.activeEpoch,
          sessionId: cPrior.activeSessionId,
          generation: cPrior.sessionRevision !== undefined ? cPrior.sessionRevision : cPrior.sessRev,
          recordId: 'rec-sync-prior-b18'
        }
      });
      assert.equal(uPrior.status, 'Accepted');
      assert.equal(cPrior.compositionState, 'Idle');
      assert.equal(cPrior.docText, 'Hello');

      // 7. Undo after nonterminal unmark with queued command
      const cNonterm = new Impl("Hello");
      cNonterm.applyInput({ type: 'startComposition', range: [1, 2] });
      cNonterm.applyInput({ type: 'preedit', text: 'X' });
      cNonterm.applyInput({
        type: 'queueCommand',
        cmd: { type: 'Format', patch: { bold: true } },
        target: { type: 'CompositionTarget' }
      });
      cNonterm.applyInput({ type: 'unmark', settled: false }); // AwaitingSettlement
      assert.equal(cNonterm.compositionState, 'AwaitingSettlement');
      const uNt1 = cNonterm.applyInput({
        type: 'undo',
        syncBoundary: true,
        settlementEvidence: {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: cNonterm.epoch !== undefined ? cNonterm.epoch : cNonterm.activeEpoch,
          sessionId: cNonterm.activeSessionId,
          generation: cNonterm.sessionRevision !== undefined ? cNonterm.sessionRevision : cNonterm.sessRev,
          recordId: 'rec-sync-nt1-b18'
        }
      });
      assert.equal(uNt1.status, 'Accepted');
      assert.equal(uNt1.group, 'Formatting');
      assert.equal(cNonterm.docText, 'HXllo');
      assert.equal(cNonterm.docAttributes[1]?.bold, undefined);
      const uNt2 = cNonterm.applyInput({ type: 'undo' });
      assert.equal(uNt2.status, 'Accepted');
      assert.equal(uNt2.group, 'Composition');
      assert.equal(cNonterm.docText, 'Hello');
    }
    console.log("[PASS] Block 18: Explicit Ordered Queue Settlement Policy, Undo popping order, symmetric Redo, prior history undo, and nonterminal unmark verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 19: Defect 1 - Rollback Target Disjoint Interval Preservation
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 19] Defect 1: Rollback Target Disjoint Interval Preservation ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Exact reproduction scenario: "abcde" -> select "c", preedit "", queue Format on [1, 3] ("bd"), cancel
      const c1 = new Impl("abcde");
      c1.applyInput({ type: "startComposition", range: [2, 3] }); // Selects "c"
      c1.applyInput({ type: "preedit", text: "" }); // Document becomes "abde"
      const q1 = c1.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [1, 3] } // Targets visible "bd"
      });
      c1.applyInput({ type: "cancel" }); // Restores "c" to yield "abcde"

      assert.equal(c1.docText, "abcde");
      // CRITICAL CONTRACT: Restored 'c' at index 2 must NOT be bold!
      assert.equal(c1.docAttributes[1]?.bold, true, "Surviving character 'b' at index 1 must be bold");
      assert.equal(c1.docAttributes[2]?.bold, undefined, "Restored character 'c' at index 2 must NOT be bold!");
      assert.equal(c1.docAttributes[3]?.bold, true, "Surviving character 'd' at index 3 must be bold");

      const outcome1 = c1.commandOutcomes.get(q1.ticketId);
      assert.equal(outcome1.status, "Applied");
      assert.deepEqual(outcome1.ranges, [[1, 2], [3, 4]], "Disjoint surviving intervals [[1, 2], [3, 4]] must be recorded");
      assert.deepEqual(outcome1.range, [1, 2], "Primary range must be [1, 2]");

      // 2. Repeated identical characters by tokenId
      const c2 = new Impl("aaaaa");
      c2.applyInput({ type: "startComposition", range: [2, 3] }); // 'a' at 2
      c2.applyInput({ type: "preedit", text: "" }); // "aaaa"
      const q2 = c2.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { italic: true } },
        target: { type: "TrackedRange", range: [1, 3] } // indices 1 and 2 in "aaaa"
      });
      c2.applyInput({ type: "cancel" }); // restores "aaaaa"
      assert.equal(c2.docText, "aaaaa");
      assert.equal(c2.docAttributes[1]?.italic, true);
      assert.equal(c2.docAttributes[2]?.italic, undefined, "Intervening restored duplicate 'a' must NOT be formatted");
      assert.equal(c2.docAttributes[3]?.italic, true);

      // 3. Opposing length changes before cancel
      const c3 = new Impl("0123456789");
      c3.applyInput({ type: "startComposition", range: [4, 6] }); // "45"
      c3.applyInput({ type: "preedit", text: "XYZW" }); // growth
      c3.applyInput({ type: "preedit", text: "" }); // shrinkage to empty
      const q3 = c3.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { underline: true } },
        target: { type: "TrackedRange", range: [3, 5] } // "3" (committed index 3) and "6" (committed index 6)
      });
      c3.applyInput({ type: "cancel" });
      assert.equal(c3.docText, "0123456789");
      assert.equal(c3.docAttributes[3]?.underline, true);
      assert.equal(c3.docAttributes[4]?.underline, undefined, "Restored '4' must NOT be underlined");
      assert.equal(c3.docAttributes[5]?.underline, undefined, "Restored '5' must NOT be underlined");
      assert.equal(c3.docAttributes[6]?.underline, true);

      // 4. Multiple surviving tokens with disjoint intervals
      const c4 = new Impl("abcdef");
      c4.applyInput({ type: "startComposition", range: [3, 4] }); // selects "d"
      c4.applyInput({ type: "preedit", text: "" }); // visible becomes "abcef"
      const q4 = c4.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [1, 4] } // covers visible "bce"
      });
      c4.applyInput({ type: "cancel" }); // restores "d" to yield "abcdef"
      assert.equal(c4.docText, "abcdef");
      assert.equal(c4.docAttributes[1]?.bold, true, "'b' must be bold");
      assert.equal(c4.docAttributes[2]?.bold, true, "'c' must be bold");
      assert.equal(c4.docAttributes[3]?.bold, undefined, "Restored 'd' must NOT be bold");
      assert.equal(c4.docAttributes[4]?.bold, true, "'e' must be bold");
      assert.deepEqual(c4.commandOutcomes.get(q4.ticketId)?.ranges, [[1, 3], [4, 5]]);

      // 5. Grapheme boundary expansion runs independently on each disjoint interval
      const c5 = new Impl("a\u0300ce\u0301");
      c5.applyInput({ type: "startComposition", range: [2, 3] });
      c5.applyInput({ type: "preedit", text: "" });
      const q5 = c5.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [1, 3] }
      });
      c5.applyInput({ type: "cancel" });
      assert.equal(c5.docText, "a\u0300ce\u0301");
      assert.equal(c5.docAttributes[2]?.bold, undefined, "Intervening 'c' must NOT be bridged or bolded by grapheme expansion");
      assert.equal(c5.docAttributes[0]?.bold, true, "'a' expanded outward cluster must be bold");
      assert.equal(c5.docAttributes[1]?.bold, true, "grave mark must be bold");
      assert.equal(c5.docAttributes[3]?.bold, true, "'e' must be bold");
      assert.equal(c5.docAttributes[4]?.bold, true, "acute mark must be bold");

      // 6. Reversed selection in TrackedRange target handles bounds symmetrically
      const c6 = new Impl("abcde");
      c6.applyInput({ type: "startComposition", range: [2, 3] });
      c6.applyInput({ type: "preedit", text: "" }); // "abde"
      const q6 = c6.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [3, 1] } // reversed range [3, 1] covering visible "bd"
      });
      c6.applyInput({ type: "cancel" }); // restores "abcde"
      assert.equal(c6.docText, "abcde");
      assert.equal(c6.docAttributes[1]?.bold, true);
      assert.equal(c6.docAttributes[2]?.bold, undefined, "Restored intervening 'c' must NOT be bold on reversed range");
      assert.equal(c6.docAttributes[3]?.bold, true);
      assert.deepEqual(c6.commandOutcomes.get(q6.ticketId)?.ranges, [[1, 2], [3, 4]]);

      // 7. Collapsed TrackedRange target on cancel updates typing intent without expanding
      const c7 = new Impl("abcde");
      c7.applyInput({ type: "startComposition", range: [2, 3] });
      c7.applyInput({ type: "preedit", text: "" });
      const q7 = c7.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [2, 2] } // collapsed target
      });
      c7.applyInput({ type: "cancel" });
      assert.equal(c7.docText, "abcde");
      const outcome7 = c7.commandOutcomes.get(q7.ticketId);
      assert.equal(outcome7.status, "Applied");
      assert.equal(outcome7.typingIntentUpdated, true);

      // 8. Fully discarded target (all covered tokens deleted without surviving tokens) rejects TargetDeleted
      const c8 = new Impl("abc");
      c8.applyInput({ type: "startComposition", range: [1, 2] }); // selects "b"
      c8.applyInput({ type: "preedit", text: "XYZ" }); // "aXYZc"
      const q8 = c8.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [1, 4] } // covers exclusively provisional "XYZ"
      });
      c8.applyInput({ type: "cancel" }); // cancels provisional "XYZ", restores "abc"
      assert.equal(c8.docText, "abc");
      const outcome8 = c8.commandOutcomes.get(q8.ticketId);
      assert.equal(outcome8.status, "Rejected");
      assert.equal(outcome8.reason, "TargetDeleted");

      // 9. Partial commit followed by cancel preserves surviving pieces and excludes restored text
      const c9 = new Impl("abcdef");
      c9.applyInput({ type: "startComposition", range: [2, 4] }); // selects "cd"
      c9.applyInput({ type: "preedit", text: "XYZ" }); // "abXYZef"
      const q9 = c9.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [1, 5] } // covers 'b' (1), 'X' (2), 'Y' (3), 'Z' (4)
      });
      c9.applyInput({ type: "cancel" }); // cancels provisional "XYZ", restores "cd" to yield "abcdef"
      assert.equal(c9.docText, "abcdef");
      assert.equal(c9.docAttributes[1]?.bold, true, "Surviving 'b' at 1 must be bold");
      assert.equal(c9.docAttributes[2]?.bold, undefined, "Restored 'c' at 2 must NOT be bold");
      assert.equal(c9.docAttributes[3]?.bold, undefined, "Restored 'd' at 3 must NOT be bold");
      assert.equal(c9.docAttributes[4]?.bold, undefined, "'e' at 4 was outside range [1, 5] of 'abXYZef'");
      const outcome9 = c9.commandOutcomes.get(q9.ticketId);
      assert.equal(outcome9.status, "Applied");
      assert.deepEqual(outcome9.ranges, [[1, 2]]);
    }
    console.log("[PASS] Block 19: Defect 1 - Rollback Target Disjoint Interval Preservation verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 20: Defect 2 - Centralized Transaction Runner & Dispatcher Accounting
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 20] Defect 2: Centralized Transaction Runner & Dispatcher Accounting ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Direct dispatchCommand advances documentRevision on semantic change
      const c1 = new Impl("abc");
      assert.equal(c1.documentRevision, 0);
      const res1 = c1.dispatchCommand(
        { type: "Format", patch: { bold: true } },
        { type: "TrackedRange", range: [0, 1] }
      );
      assert.equal(res1.status, "Applied");
      assert.equal(c1.docAttributes[0]?.bold, true);
      assert.equal(c1.documentRevision, 1, "documentRevision must advance from 0 to 1 on direct dispatchCommand formatting!");

      // 2. Direct dispatchCommand with no-op does NOT advance documentRevision
      const res1NoOp = c1.dispatchCommand(
        { type: "Format", patch: { bold: true } },
        { type: "TrackedRange", range: [0, 1] }
      );
      assert.equal(res1NoOp.status, "Applied");
      assert.equal(res1NoOp.noOp, true);
      assert.equal(c1.documentRevision, 1, "documentRevision must NOT advance on no-op dispatchCommand!");

      // 3. Malformed command upfront validation: no ticket created, ticketCounter NOT leaked
      const c2 = new Impl("abc");
      const resMalformed1 = c2.dispatchCommand(null);
      assert.equal(resMalformed1.status, "Rejected");
      assert.equal(resMalformed1.reason, "InvalidCommand");
      assert.equal(resMalformed1.ticketId, undefined, "No ticketId on malformed command rejection");
      assert.equal(c2.commandOutcomes.size, 0, "No unaccounted tickets in commandOutcomes");

      const resMalformed2 = c2.dispatchCommand({ type: "UnknownType" });
      assert.equal(resMalformed2.status, "Rejected");
      assert.equal(resMalformed2.reason, "UnknownCommandType:UnknownType");
      assert.equal(resMalformed2.ticketId, undefined);

      const resMalformed3 = c2.dispatchCommand({ type: "Format", patch: { bold: true } }, { type: "BogusTarget" });
      assert.equal(resMalformed3.status, "Rejected");
      assert.equal(resMalformed3.reason, "InvalidTarget");
      assert.equal(resMalformed3.ticketId, undefined);

      // 4. Direct undo() and redo() advance documentRevision
      const c3 = new Impl("abc");
      c3.dispatchCommand({ type: "Format", patch: { bold: true } }, { type: "TrackedRange", range: [0, 1] });
      assert.equal(c3.documentRevision, 1);
      const resUndo = c3.undo();
      assert.equal(resUndo.status, "Accepted");
      assert.equal(c3.documentRevision, 2, "Direct undo must advance documentRevision!");
      assert.equal(c3.docAttributes[0]?.bold, undefined);

      const resRedo = c3.redo();
      assert.equal(resRedo.status, "Accepted");
      assert.equal(c3.documentRevision, 3, "Direct redo must advance documentRevision!");
      assert.equal(c3.docAttributes[0]?.bold, true);

      // 5. Reentrant mutation FIFO queueing (§4.1 contract)
      const c4 = new Impl("abc");
      let reentrantRes = null;
      c4._runInTransaction('outerTx', () => {
        reentrantRes = c4.dispatchCommand(
          { type: "Format", patch: { bold: true } },
          { type: "TrackedRange", range: [0, 1] }
        );
      });
      assert.equal(reentrantRes.status, "Accepted");
      assert.equal(reentrantRes.action, "QueuedReentrant");
      assert.ok(reentrantRes.ticketId);
      assert.equal(c4.commandOutcomes.get(reentrantRes.ticketId)?.status, "Applied");
      assert.equal(c4.docAttributes[0]?.bold, true);

      // 6. Direct public methods execute transactionally with validation and accounting
      const c5 = new Impl("abc");
      const selRes = c5.setSelection([1, 2]);
      assert.equal(selRes.status, "Accepted");
      assert.deepEqual(c5.selection, [1, 2]);

      const selInv = c5.setSelection([-1, 10]);
      assert.equal(selInv.status, "Rejected");
      assert.equal(selInv.reason, "InvalidSelection");

      const connRes = c5.openNewConnection();
      assert.equal(connRes.status, "Accepted");
      assert.equal(connRes.epoch, 1);

      const remRes = c5.nativeRemoval([0, 1], "Z");
      assert.equal(remRes.status, "Accepted");
      assert.equal(c5.docText, "Zbc");
      assert.equal(c5.undoStack.length > 0, true, "nativeRemoval must record undo history");

      const dispRes = c5.dispose();
      assert.equal(dispRes.status, "Accepted");

      // 7. Closed coordinator rejects all direct public entry points without side effects
      const prevRevBeforeClosed = c5.documentRevision;
      assert.equal(c5.setSelection([0, 1]).reason, "ConnectionClosed");
      assert.equal(c5.openNewConnection().reason, "ConnectionClosed");
      assert.equal(c5.dispose().reason, "ConnectionClosed");
      assert.equal(c5.dispatchCommand({ type: "Format", patch: { bold: true } }).reason, "ConnectionClosed");
      assert.equal(c5.undo().reason, "ConnectionClosed");
      assert.equal(c5.redo().reason, "ConnectionClosed");
      assert.equal(c5.nativeRemoval([0, 1]).reason, "ConnectionClosed");
      assert.equal(c5.onFocusLost().reason, "ConnectionClosed");
      assert.equal(c5.documentRevision, prevRevBeforeClosed, "Closed coordinator must NOT advance revisions");
      assert.equal(c5.commandOutcomes.size, 0, "Closed rejections must NOT leak command outcomes");
    }
    console.log("[PASS] Block 20: Defect 2 - Centralized Transaction Runner & Dispatcher Accounting verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 21: Defect 3 - Settlement Barrier & Ordered Undo Queueing
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 21] Defect 3: Settlement Barrier & Ordered Undo Queueing ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Asynchronous platform: undo during composition queues behind settlement barrier
      const c1 = new Impl("abc");
      c1.applyInput({ type: "startComposition", range: [1, 2] }); // Selects "b"
      c1.applyInput({ type: "preedit", text: "X" }); // "aXc"
      c1.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "CompositionTarget" }
      });

      // Calling undo() during active composition without sync boundary
      const uRes = c1.undo();
      assert.equal(uRes.status, "Accepted");
      assert.equal(uRes.type, "UndoQueued");
      assert.equal(uRes.action, "QueuedBehindSettlement");
      assert.equal(c1.compositionState, "AwaitingSettlement", "Coordinator must transition to AwaitingSettlement, NOT Idle!");
      assert.ok(c1.settlementObligation?.activeFence || c1.activeSettlementFence, "Active settlement fence must be set!");
      assert.equal(c1.settlementEvidence, null, "Coordinator must NOT fabricate speculative settlementEvidence when queuing undo!");

      // Subsequent commit("Y") arrives from native IME
      const resCommit = c1.applyInput({ type: "commit", text: "Y" });
      assert.equal(resCommit.status, "Accepted");
      // Must NOT produce "aXYc"! Composed text was "Y", then formatting applied (bold), then queued undo popped formatting
      assert.equal(c1.docText, "aYc", "Subsequent commit must NOT produce aXYc via state turnover!");
      assert.equal(c1.docAttributes[1]?.bold, undefined, "Queued undo popped Formatting group, leaving 'Y' plain");
      assert.equal(c1.compositionState, "Idle");

      // Next undo pops the composition group, reverting to pre-session "abc"
      const uNext = c1.undo();
      assert.equal(uNext.status, "Accepted");
      assert.equal(c1.docText, "abc", "Second undo reverts composition to pre-session text 'abc'");

      // 2. Cancellation of active composition with queued undo pops pre-session history
      const c2 = new Impl("Hello");
      c2.applyInput({ type: "commit", text: " World", range: [5, 5] });
      assert.equal(c2.docText, "Hello World");
      assert.equal(c2.undoStack.length, 1);

      c2.applyInput({ type: "startComposition", range: [5, 11] });
      c2.applyInput({ type: "preedit", text: " Earth" });
      assert.equal(c2.docText, "Hello Earth");

      // Undo queues in AwaitingSettlement
      const uRes2 = c2.undo();
      assert.equal(uRes2.status, "Accepted");
      assert.equal(c2.compositionState, "AwaitingSettlement");
      assert.equal(c2.settlementEvidence, null, "settlementEvidence must be null while queued");

      // Native cancel arrives: uncommitted " Earth" is discarded, and queued undo pops pre-session " World"
      c2.applyInput({ type: "cancel" });
      assert.equal(c2.docText, "Hello", "Cancel must discard preedit and execute queued undo to revert pre-session commit!");
      assert.equal(c2.compositionState, "Idle");

      // 3. Synchronous adapter boundary completes settlement immediately
      const c3 = new Impl("abc");
      c3.applyInput({ type: "startComposition", range: [1, 2] });
      c3.applyInput({ type: "preedit", text: "X" });
      const uSync = c3.undo(true, {
        adapterQualification: 'QualifiedSyncSettlement',
        extent: [1, 2],
        epoch: c3.epoch !== undefined ? c3.epoch : c3.activeEpoch,
        sessionId: c3.activeSessionId,
        generation: c3.sessionRevision !== undefined ? c3.sessionRevision : c3.sessRev,
        recordId: 'rec-sync-c3-b21'
      });
      assert.equal(uSync.status, "Accepted");
      assert.equal(c3.compositionState, "Idle", "Qualified sync boundary must transition immediately to Idle");
      assert.equal(c3.docText, "abc", "Synchronous undo immediately reverts composition");
      assert.equal(c3.redoStack.length, 1, "Active composition preserved on redoStack");
      assert.equal(c3.redoStack[0].afterText, "aXc");

      // 4. Two-step settlement lifecycle: unmark creates fence, completeSettlement causally drains queued formatting and undo
      const c4 = new Impl("abc");
      c4.applyInput({ type: "startComposition", range: [1, 2] });
      c4.applyInput({ type: "preedit", text: "X" });
      const q4 = c4.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "CompositionTarget" }
      });
      const uRes4 = c4.undo(); // Queued behind settlement barrier
      assert.equal(uRes4.status, "Accepted");
      assert.equal(c4.settlementEvidence, null, "settlementEvidence must be null while queued");

      const uFence4 = c4.applyInput({ type: "unmark", settled: false });
      assert.equal(uFence4.status, "Accepted");
      assert.equal(uFence4.settled, false);
      assert.ok(uFence4.token);

      // Wrong token rejected
      const badTokRes = c4.applyInput({ type: "completeSettlement", token: "invalid-token-123" });
      assert.equal(badTokRes.status, "Rejected");
      assert.equal(badTokRes.reason, "ObsoleteSettlementToken");

      // Valid token completes settlement and causally drains Format then Undo in FIFO order
      const compRes4 = c4.applyInput({ type: "completeSettlement", token: uFence4.token });
      assert.equal(compRes4.status, "Accepted");
      assert.equal(c4.compositionState, "Idle");
      assert.equal(c4.docText, "aXc");
      assert.equal(c4.docAttributes[1]?.bold, undefined, "Causal drainage: Format applied bold, then queued Undo popped bold");
      assert.equal(c4.commandOutcomes.get(q4.ticketId)?.status, "Applied");
    }
    console.log("[PASS] Block 21: Defect 3 - Settlement Barrier & Ordered Undo Queueing verified");
    passedCount++;
  }

  // ---------------------------------------------------------------------------
  // Block 22: Run-11 Contract Violations 1–4 & Identity Tracking Verification
  // ---------------------------------------------------------------------------
  {
    console.log("\n--- [Block 22] Run-11 Contract Violations 1–4 & Identity Tracking ---");
    for (const Impl of [CandidateBCoordinator, IndependentOracle]) {
      // 1. Failure 1: Settlement preserves Redo entry created by queued Undo across all settlement pathways
      {
        // 1A. via commit
        {
          const c = new Impl("abc");
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "X" });
          const uTick = c.undo();
          const cRes = c.applyInput({ type: "commit", text: "Y" });
          assert.equal(c.docText, "abc");
          assert.equal(c.commandOutcomes.get(uTick.ticketId)?.status, 'Applied');
          assert.ok(c.redoStack.length > 0, "redoStack must retain Redo entry created by queued Undo");
          const redoRes = c.redo();
          assert.equal(redoRes.status, 'Accepted');
          assert.equal(c.docText, "aYc", "Redo must restore 'aYc'");
        }

        // 1B. via terminal unmark
        {
          const c = new Impl("abc");
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "X" });
          const uTick = c.undo();
          c.applyInput({ type: "unmark", settled: true });
          assert.equal(c.docText, "abc");
          assert.ok(c.redoStack.length > 0, "redoStack must retain Redo entry after unmark settlement");
          const rRes = c.redo();
          assert.equal(rRes.status, 'Accepted');
          assert.equal(c.docText, "aXc", "Redo must restore 'aXc'");
        }

        // 1C. via completeSettlement
        {
          const c = new Impl("abc");
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "X" });
          const uTick = c.undo();
          const uRes = c.applyInput({ type: "unmark", settled: false });
          c.applyInput({ type: "completeSettlement", token: uRes.token });
          assert.equal(c.docText, "abc");
          assert.ok(c.redoStack.length > 0, "redoStack must retain Redo entry after completeSettlement");
          const rRes = c.redo();
          assert.equal(rRes.status, 'Accepted');
          assert.equal(c.docText, "aXc", "Redo must restore 'aXc'");
        }

        // 1D. via cancellation with prior edit popped by queued undo
        {
          const c = new Impl("abc");
          c.applyInput({ type: "commit", text: "d", range: [3, 3] }); // abcd
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "X" });
          const uTick = c.undo();
          c.applyInput({ type: "cancel" });
          assert.equal(c.docText, "abc", "Cancelled composition discards uncommitted, queued undo pops prior commit");
          assert.equal(c.commandOutcomes.get(uTick.ticketId)?.status, 'Applied');
          assert.ok(c.redoStack.length > 0);
          const rRes = c.redo();
          assert.equal(rRes.status, 'Accepted');
          assert.equal(c.docText, "abcd");
        }

        // 1E. Multiple queued Undos and repeated Undo/Redo cycles
        {
          const c = new Impl("abc");
          c.applyInput({ type: "commit", text: "d", range: [3, 3] }); // abcd
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "X" }); // aXcd
          const u1 = c.undo();
          const u2 = c.undo();
          c.applyInput({ type: "commit", text: "X" });
          assert.equal(c.docText, "abc");
          assert.equal(c.commandOutcomes.get(u1.ticketId)?.status, 'Applied');
          assert.equal(c.commandOutcomes.get(u2.ticketId)?.status, 'Applied');
          assert.equal(c.redoStack.length, 2);

          // Repeated cycles
          c.redo();
          assert.equal(c.docText, "abcd");
          c.redo();
          assert.equal(c.docText, "aXcd");
          c.undo();
          assert.equal(c.docText, "abcd");
          c.undo();
          assert.equal(c.docText, "abc");
        }
      }

      // 2. Failure 2: Queued commands retain surviving targets across queued Undo
      {
        // 2A. Basic surviving target remapping across intermediate Undo
        {
          const c = new Impl("abc");
          c.applyInput({ type: "startComposition", range: [1, 2] });
          c.applyInput({ type: "preedit", text: "XYZ" });
          const uTick = c.undo();
          const qBold = c.applyInput({
            type: "queueCommand",
            cmd: { type: "Format", patch: { bold: true } },
            target: { type: "TrackedRange", range: [4, 5] } // original 'c'
          });
          c.applyInput({ type: "commit", text: "XYZ" });
          const boldOutcome = c.commandOutcomes.get(qBold.ticketId);
          assert.equal(c.docText, "abc");
          assert.notEqual(boldOutcome?.status, 'Rejected');
          assert.equal(c.docAttributes[2]?.bold, true, "Surviving character 'c' must be bold at index 2");
          assert.equal(c.docAttributes[0]?.bold, undefined);
          assert.equal(c.docAttributes[1]?.bold, undefined);
        }

        // 2B. Growing document across intermediate Undo in queue
        {
          const c = new Impl("0123456789");
          c.applyInput({ type: "startComposition", range: [0, 8] });
          c.applyInput({ type: "preedit", text: "X" }); // 'X89' (length 3)
          const uTick = c.undo();
          const qF = c.applyInput({
            type: "queueCommand",
            cmd: { type: "Format", patch: { bold: true } },
            target: { type: "TrackedRange", range: [2, 3] } // '9' (survives and remaps to index 9 in restored document)
          });
          c.applyInput({ type: "commit", text: "X" });
          const out = c.commandOutcomes.get(qF.ticketId);
          assert.equal(c.docText, "0123456789");
          assert.equal(out?.status, 'Applied', "Target must not be invalidated by stale pre-undo text length");
          assert.equal(out?.range[0], 9);
          assert.equal(c.docAttributes[9]?.bold, true);
        }
      }

      // 3. Failure 3: Settlement evidence qualification exhaustively checked
      {
        const c = new Impl("abc");
        c.applyInput({ type: "startComposition", range: [1, 2] });
        c.applyInput({ type: "preedit", text: "X" });

        // A. Missing qualification object
        const r1 = c.undo(true, null);
        assert.equal(r1.status, 'Rejected');
        assert.equal(r1.reason, 'MalformedSettlementEvidence');

        // B. Unqualified caller
        const r2 = c.undo(true, { extent: [1, 2], adapterQualification: 'UnqualifiedDesktopAwt' });
        assert.equal(r2.status, 'Rejected');
        assert.equal(r2.reason, 'UnqualifiedAdapter');

        // C. Mismatched epoch
        const r3 = c.undo(true, {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: 999,
          sessionId: c.activeSessionId,
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev),
          recordId: 'rec-test-bad-epoch'
        });
        assert.equal(r3.status, 'Rejected');
        assert.equal(r3.reason, 'EpochMismatch');

        // D. Mismatched session
        const r4 = c.undo(true, {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch),
          sessionId: 'wrong-session-xyz',
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev),
          recordId: 'rec-test-bad-session'
        });
        assert.equal(r4.status, 'Rejected');
        assert.equal(r4.reason, 'SessionMismatch');

        // E. Obsolete generation
        const r5 = c.undo(true, {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch),
          sessionId: c.activeSessionId,
          generation: 9999,
          recordId: 'rec-test-bad-gen'
        });
        assert.equal(r5.status, 'Rejected');
        assert.equal(r5.reason, 'ObsoleteSettlementEvidence');

        // F. Missing mandatory fields rejected as MalformedSettlementEvidence
        const rNoEpoch = c.undo(true, { adapterQualification: 'QualifiedSyncSettlement', extent: [1, 2], sessionId: c.activeSessionId, generation: 1, recordId: 'nonce-1' });
        assert.equal(rNoEpoch.status, 'Rejected');
        assert.equal(rNoEpoch.reason, 'MalformedSettlementEvidence');

        const rNoSession = c.undo(true, { adapterQualification: 'QualifiedSyncSettlement', extent: [1, 2], epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch), generation: 1, recordId: 'nonce-2' });
        assert.equal(rNoSession.status, 'Rejected');
        assert.equal(rNoSession.reason, 'MalformedSettlementEvidence');

        const rNoGen = c.undo(true, { adapterQualification: 'QualifiedSyncSettlement', extent: [1, 2], epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch), sessionId: c.activeSessionId, recordId: 'nonce-3' });
        assert.equal(rNoGen.status, 'Rejected');
        assert.equal(rNoGen.reason, 'MalformedSettlementEvidence');

        // G. Out of bounds extent
        const r6 = c.undo(true, {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [-1, 100],
          epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch),
          sessionId: c.activeSessionId,
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev),
          recordId: 'rec-test-bad-ext'
        });
        assert.equal(r6.status, 'Rejected');
        assert.equal(r6.reason, 'MalformedSettlementEvidence');

        // H. Valid qualified evidence succeeds
        const qualEvidence = {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch),
          sessionId: c.activeSessionId,
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev),
          recordId: 'rec-test-valid-nonce-1'
        };
        const rValid = c.undo(true, qualEvidence);
        assert.equal(rValid.status, 'Accepted');
        assert.equal(c.compositionState, 'Idle');

        // I. Nonce reuse rejection
        c.applyInput({ type: "startComposition", range: [1, 2] });
        c.applyInput({ type: "preedit", text: "Z" });
        const qualReuse = {
          ...qualEvidence,
          sessionId: c.activeSessionId,
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev)
        };
        const rReuse = c.undo(true, qualReuse);
        assert.equal(rReuse.status, 'Rejected');
        assert.equal(rReuse.reason, 'ReusedSettlementEvidence');

        // J. Intervening input invalidates evidence
        const evidenceBeforeIntervention = {
          adapterQualification: 'QualifiedSyncSettlement',
          extent: [1, 2],
          epoch: (c.epoch !== undefined ? c.epoch : c.activeEpoch),
          sessionId: c.activeSessionId,
          generation: (c.sessionRevision !== undefined ? c.sessionRevision : c.sessRev),
          recordId: 'rec-test-intervene-unique'
        };
        c.applyInput({ type: "preedit", text: "ZW" }); // advances sessionRevision
        const rIntervene = c.undo(true, evidenceBeforeIntervention);
        assert.equal(rIntervene.status, 'Rejected');
        assert.equal(rIntervene.reason, 'ObsoleteSettlementEvidence');
      }

      // 4. Failure 4: Reentrancy FIFO queueing, rollback isolation, and closure behavior
      {
        // 4A. Standard outer transaction completion drains reentrant queue
        {
          const c = new Impl("abc");
          let reentrantResult = null;
          c._runInTransaction('outer', () => {
            reentrantResult = c.applyInput({ type: "commit", text: "Z" });
          });
          assert.equal(reentrantResult?.status, 'Accepted');
          assert.equal(reentrantResult?.action, 'QueuedReentrant');
          assert.ok(reentrantResult?.ticketId);
          assert.equal(c.commandOutcomes.get(reentrantResult.ticketId)?.status, 'Accepted');
          assert.equal(c.docText, "Zabc");
        }

        // 4B. Rollback isolation: reentrant mutation executes against cleanly restored baseline after outer transaction failure
        {
          const c = new Impl("Hello");
          let reentrantResult = null;
          const txRes = c._runInTransaction('failingTx', () => {
            reentrantResult = c.applyInput({ type: "commit", text: " World", range: [5, 5] });
            throw new Error("Staged Outer Failure");
          });
          assert.equal(txRes.status, 'Rejected');
          assert.equal(txRes.reason, 'Staged Outer Failure');
          assert.equal(reentrantResult?.status, 'Accepted');
          assert.equal(c.docText, "Hello World", "Reentrant mutation must execute against restored baseline");
          assert.equal(c.commandOutcomes.get(reentrantResult.ticketId)?.status, 'Accepted');
        }

        // 4C. Reentrancy on closed connection rejected with ConnectionClosed
        {
          const c = new Impl("Hello");
          c.dispose();
          const res = c.applyInput({ type: "commit", text: " World" });
          assert.equal(res.status, 'Rejected');
          assert.equal(res.reason, 'ConnectionClosed');
        }
      }
    }
    console.log("[PASS] Block 22: Run-11 Contract Violations 1–4 & Exact Content-Identity Remapping verified");
    passedCount++;
  }

  console.log("\n================================================================================");
  console.log(`=== ALL ${passedCount} VERIFICATION BLOCKS PASSED CLEANLY!                  ===`);
  console.log("================================================================================");
  return { passed: passedCount, total: 22 };
}

if (process.argv[1]?.endsWith('test-repaired-regressions.mjs')) {
  runRepairedRegressionsTest();
}
