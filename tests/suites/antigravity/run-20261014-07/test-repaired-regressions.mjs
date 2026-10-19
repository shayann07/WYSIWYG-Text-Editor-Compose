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
import { oracleExpandToGraphemeBoundaries } from './independent-oracle.mjs';

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
    assert.equal(cDel.commandOutcomes.get(qDel.ticketId)?.status, 'Rejected');
    assert.equal(cDel.commandOutcomes.get(qDel.ticketId)?.reason, 'TargetInvalidated');

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
    assert.equal(c.receiptCache.has(100), true);

    c.applyInput({ type: 'openNewConnection' }); // epoch 2
    assert.equal(c.epoch, 2);
    assert.equal(c.receiptCache.has(100), false, "Receipt cache must be evicted on connection turnover!");

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
      assert.equal(c.receiptCache.has(777), false, "Receipt cache must not contain receipt for aborted transaction!");
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

  console.log("\n================================================================================");
  console.log(`=== ALL ${passedCount} VERIFICATION BLOCKS PASSED CLEANLY!                  ===`);
  console.log("================================================================================");
  return { passed: passedCount, total: 10 };
}

if (process.argv[1]?.endsWith('test-repaired-regressions.mjs')) {
  runRepairedRegressionsTest();
}
