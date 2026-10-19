import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

console.log("=== Running Comprehensive Verification of Repaired Protocol Coordinator (run-20261013-03) ===");

// ---------------------------------------------------------------------------
// 1. Defect A: Normalized Completion Protocol & Settlement Integrity
// ---------------------------------------------------------------------------
console.log("\n1. Verifying Defect A: Normalized Completion Protocol & Settlement Integrity...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // Start composition with sessionId "s"
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "HXllo");
  
  // Queue Bold targeting CompositionTarget("s")
  const qCmd = coord.dispatchAppCommand({
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 's' },
    style: { bold: true }
  });
  assert.equal(qCmd.status, 'Deferred');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.pendingCommands.length, 1);
  
  // Step 4: unmark with settled: false (Nonterminal unmark)
  const u1 = coord.receiveCallback(1, { type: 'unmark', settled: false });
  assert.equal(u1.status, 'Accepted');
  assert.equal(u1.receipt.type, 'Unmarked');
  assert.equal(u1.receipt.settled, false);
  
  // Invariants after nonterminal unmark:
  // - markedRange is cleared (transient underline removed)
  assert.equal(coord.markedRange, null);
  // - activeSessionId is RETAINED (not prematurely erased!)
  assert.equal(coord.activeSessionId, 's');
  // - compositionBeforeSnapshot is RETAINED
  assert.ok(coord.compositionBeforeSnapshot !== null);
  // - History is DEFERRED (zero undoStack entries created!)
  assert.equal(coord.undoStack.length, 0);
  // - State remains AwaitingSettlement
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  // - Pending command remains queued
  assert.equal(coord.pendingCommands.length, 1);
  
  // Step 5: Repeating unmark when already unmarked MUST be rejected explicitly!
  const uRepeat = coord.receiveCallback(1, { type: 'unmark', settled: true });
  assert.equal(uRepeat.status, 'Accepted');
  assert.equal(uRepeat.receipt.status, 'Rejected');
  assert.equal(uRepeat.receipt.reason, 'AlreadyUnmarked');
  // State is NOT corrupted:
  assert.equal(coord.activeSessionId, 's');
  assert.equal(coord.undoStack.length, 0);
  assert.equal(coord.lastSettledExtent[0], 1);
  assert.equal(coord.lastSettledExtent[1], 2);
  
  // Step 6: Normalized completion event arrives: completeSettlement
  const settleRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's' });
  assert.equal(settleRes.status, 'Accepted');
  assert.equal(settleRes.receipt.type, 'SettlementCompleted');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.activeSessionId, null);
  
  // Invariants after terminal settlement:
  // - Exactly ONE Composition history group created:
  assert.equal(coord.undoStack.length, 2); // 1 Composition + 1 Formatting
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[0].beforeText, 'Hello');
  assert.equal(coord.undoStack[0].afterText, 'HXllo');
  assert.deepEqual(coord.undoStack[0].sessionExtent, [1, 2]);
  
  // - Pending format command was drained and applied to exact extent [1, 2) ('X'):
  assert.equal(coord.undoStack[1].group, 'Formatting');
  assert.deepEqual(coord.undoStack[1].range, [1, 2]);
  assert.equal(coord.docAttributes[1].bold, true); // 'X' is bold!
  assert.equal(coord.docAttributes[0].bold, undefined); // 'H' is not bold!
  assert.equal(coord.docAttributes[2].bold, undefined); // 'l' is not bold!
  
  // Symmetrical undo/redo verification
  assert.equal(coord.undo(), true); // Revert formatting
  assert.equal(coord.docAttributes[1].bold, undefined);
  assert.equal(coord.docText, "HXllo");
  
  assert.equal(coord.undo(), true); // Revert composition
  assert.equal(coord.docText, "Hello");
  
  assert.equal(coord.redo(), true); // Redo composition
  assert.equal(coord.docText, "HXllo");
  assert.equal(coord.redo(), true); // Redo formatting
  assert.equal(coord.docAttributes[1].bold, true);
  
  console.log("   -> PASS: Defect A resolved. Nonterminal unmark defers history; duplicate unmark rejected; completeSettlement applies Bold to exact extent [1, 2).");
}

// ---------------------------------------------------------------------------
// 2. Defect B: Target Expansion Preserves Partial-Commit Protection
// ---------------------------------------------------------------------------
console.log("\n2. Verifying Defect B: Target Expansion Preserves Partial-Commit Protection...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // 1. Start composition in "Hello" at [1, 2)
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sB' });
  
  // 2. Preedit "XY", producing "HXYllo"
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.equal(coord.docText, "HXYllo");
  
  // 3. Partial commit of one unit, freezing "X"
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  assert.equal(coord.committedUnits.length, 1);
  assert.equal(coord.committedUnits[0].char, 'X');
  assert.deepEqual(coord.committedExtent, [1, 2]);
  assert.deepEqual(coord.markedRange, [2, 3]); // remaining candidate is "Y"
  
  // 4. Target expansion to [2, 4) covering "Yl"
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 2, newEnd: 4 });
  assert.deepEqual(coord.markedRange, [2, 4]);
  // Committed units MUST BE PRESERVED across target expansion!
  assert.equal(coord.committedUnits.length, 1);
  assert.deepEqual(coord.committedExtent, [1, 2]);
  
  // 5. Cancel the remaining composition
  const cRes = coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(cRes.status, 'Accepted');
  assert.equal(cRes.receipt.type, 'Cancelled');
  
  // Required behavior:
  // - Frozen 'X' is retained!
  // - Swallowed 'l' from surplus is restored!
  // - Document becomes "HXllo"!
  assert.equal(coord.docText, "HXllo");
  // - Exactly ONE composition history entry exists representing "Hello" -> "HXllo"!
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[0].beforeText, 'Hello');
  assert.equal(coord.undoStack[0].afterText, 'HXllo');
  assert.deepEqual(coord.undoStack[0].sessionExtent, [1, 2]);
  
  // Symmetrical undo/redo
  assert.equal(coord.undo(), true);
  assert.equal(coord.docText, "Hello");
  assert.equal(coord.redo(), true);
  assert.equal(coord.docText, "HXllo");
  
  console.log("   -> PASS: Defect B resolved. Frozen 'X' survives cancellation after target expansion; doc text is 'HXllo' with exact undo.");
}

// ---------------------------------------------------------------------------
// 3. Defect C: Connection Turnover Finalizes Unfinished Composition
// ---------------------------------------------------------------------------
console.log("\n3. Verifying Defect C: Connection Turnover Finalizes Unfinished Composition...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sC' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "HXllo");
  
  // Connection replacement
  const newEpoch = coord.openNewConnection();
  assert.equal(newEpoch, 2);
  
  // Required behavior:
  // - 'HXllo' remains accepted in document
  assert.equal(coord.docText, "HXllo");
  // - Exactly ONE history entry exists in undoStack (no silent loss of before-snapshot!)
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[0].beforeText, 'Hello');
  assert.equal(coord.undoStack[0].afterText, 'HXllo');
  
  // Symmetrical undo/redo
  assert.equal(coord.undo(), true);
  assert.equal(coord.docText, "Hello");
  assert.equal(coord.redo(), true);
  assert.equal(coord.docText, "HXllo");
  
  // Also verify closeConnection preserves unfinished composition
  const coordClose = new ProtocolCoordinator("World", null, [0, 0]);
  coordClose.openNewConnection();
  coordClose.receiveCallback(1, { type: 'startComposition', start: 0, end: 0, sessionId: 'sClose' });
  coordClose.receiveCallback(1, { type: 'preedit', text: 'New' });
  assert.equal(coordClose.docText, "NewWorld");
  coordClose.closeConnection();
  assert.equal(coordClose.docText, "NewWorld");
  assert.equal(coordClose.undoStack.length, 1);
  assert.equal(coordClose.undoStack[0].beforeText, "World");
  assert.equal(coordClose.undoStack[0].afterText, "NewWorld");
  
  console.log("   -> PASS: Defect C resolved. Connection turnover finalizes unfinished composition into undoStack; zero loss of before-snapshot.");
}

// ---------------------------------------------------------------------------
// 4. Defect D: Revision Separation (DocumentRevision vs SessionRevision)
// ---------------------------------------------------------------------------
console.log("\n4. Verifying Defect D: Revision Separation...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sD' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  const docRev1 = coord.documentRevision;
  const sessRev1 = coord.sessionRevision;
  assert.equal(docRev1, 1);
  
  // Send identical preedit "X" again
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  const docRev2 = coord.documentRevision;
  const sessRev2 = coord.sessionRevision;
  
  // DocumentRevision MUST NOT ADVANCE!
  assert.equal(docRev2, docRev1, "DocumentRevision should not advance on identical preedit");
  // SessionRevision MUST ADVANCE:
  assert.ok(sessRev2 > sessRev1, "SessionRevision must monotonically advance");
  
  // Metadata-only partial commit (length 0)
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 0 });
  assert.equal(coord.documentRevision, docRev1, "DocumentRevision should not advance on metadata-only partial commit");
  assert.ok(coord.sessionRevision > sessRev2, "SessionRevision must advance on metadata-only partial commit");
  
  console.log("   -> PASS: Defect D resolved. DocumentRevision advances only on semantic changes; SessionRevision tracks session events.");
}

// ---------------------------------------------------------------------------
// 5. Audit Item 4.A: Raw Selection Preservation
// ---------------------------------------------------------------------------
console.log("\n5. Verifying Audit Item 4.A: Raw Selection Preservation...");
{
  const coord = new ProtocolCoordinator("Hello", null, [0, 0]);
  coord.openNewConnection();
  
  // Provide explicit non-collapsed raw selection [1, 4] with preedit
  coord.receiveCallback(1, {
    type: 'startComposition',
    start: 0,
    end: 2,
    selection: [1, 4]
  });
  assert.deepEqual(coord.selection, [1, 4]);
  
  coord.receiveCallback(1, {
    type: 'preedit',
    text: 'XYZ',
    selection: [2, 5]
  });
  assert.deepEqual(coord.selection, [2, 5]);
  
  console.log("   -> PASS: Raw selection preserved verbatim from native callback payload.");
}

// ---------------------------------------------------------------------------
// 6. Audit Item 4.F: Formatting Targets Resolution
// ---------------------------------------------------------------------------
console.log("\n6. Verifying Audit Item 4.F: Formatting Target Resolution...");
{
  // 6.1 Empty completed composition target updates typing intent only
  const coord = new ProtocolCoordinator("Hello", null, [2, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 2, end: 2, sessionId: 'sEmpty' });
  
  const qCmd = coord.dispatchAppCommand({
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 'sEmpty' },
    style: { bold: true }
  });
  assert.equal(qCmd.status, 'Deferred');
  
  // Commit empty string
  coord.receiveCallback(1, { type: 'commit', text: '' });
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.docText, "Hello");
  assert.equal(coord.documentRevision, 0); // No revision bump
  assert.equal(coord.undoStack.length, 0); // 0 Composition entries (no-op edit), 0 Formatting entries (empty range)
  assert.equal(coord.typingIntent.bold, true); // Typing intent updated!
  
  // 6.2 Cancelled composition target cancels queued command
  const coordCancel = new ProtocolCoordinator("Hello", null, [1, 2]);
  coordCancel.openNewConnection();
  coordCancel.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sCancel' });
  coordCancel.receiveCallback(1, { type: 'preedit', text: 'Z' });
  
  const qCancel = coordCancel.dispatchAppCommand({
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 'sCancel' },
    style: { underline: true }
  });
  assert.equal(qCancel.status, 'Deferred');
  
  coordCancel.receiveCallback(1, { type: 'cancel' });
  assert.equal(coordCancel.pendingCommands.length, 0); // Command cancelled and purged
  assert.equal(coordCancel.undoStack.length, 0); // No undo entry on pure cancel
  assert.equal(coordCancel.typingIntent.underline, undefined); // Typing intent not modified
  
  console.log("   -> PASS: Empty completed target updates typing intent; cancelled composition cancels command without spurious history.");
}

// ---------------------------------------------------------------------------
// 7. Reentrancy Handling (Audit 4.C)
// ---------------------------------------------------------------------------
console.log("\n7. Verifying Reentrancy Handling (Audit 4.C)...");
{
  const coord = new ProtocolCoordinator("Hello", null, [0, 0]);
  coord.openNewConnection();
  
  // Synchronous platform read during idle
  assert.equal(coord.docText, "Hello");
  assert.deepEqual(coord.selection, [0, 0]);
  
  // Test reentrant mutation queueing
  coord.isProcessingCallback = true;
  const reentrantRes = coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 1, sessionId: 'reentrant' });
  assert.equal(reentrantRes.status, 'QueuedReentrant');
  assert.equal(coord.reentrantMutationQueue.length, 1);
  coord.isProcessingCallback = false;
  
  console.log("   -> PASS: Synchronous reads allowed; reentrant mutations safely enqueued.");
}

// ---------------------------------------------------------------------------
// 8. Verifying Native Edits Following Nonterminal Unmark
// ---------------------------------------------------------------------------
console.log("\n8. Verifying Native Edits Following Nonterminal Unmark...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's8' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "HXllo");
  
  // Nonterminal unmark sets markedRange to null and state to AwaitingSettlement
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  assert.equal(coord.markedRange, null);
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  
  // A subsequent preedit arrives before settlement completion:
  const preRes = coord.receiveCallback(1, { type: 'preedit', text: 'Y' });
  assert.equal(preRes.status, 'Accepted');
  assert.equal(coord.docText, "HXYllo");
  assert.deepEqual(coord.markedRange, [2, 3]);
  assert.equal(coord.compositionState, 'Composing');
  
  // Now terminal commit
  coord.receiveCallback(1, { type: 'commit', text: 'Z' });
  assert.equal(coord.docText, "HXZllo");
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.undoStack.length, 1);
  
  console.log("   -> PASS: Native edit following nonterminal unmark safely resumes composition without crashing.");
}

// ---------------------------------------------------------------------------
// 9. Verifying Target Expansion Before / Overlapping Committed Content
// ---------------------------------------------------------------------------
console.log("\n9. Verifying Target Expansion Before / Overlapping Committed Content...");
{
  const coord = new ProtocolCoordinator("Hello World", null, [6, 11]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 6, end: 11, sessionId: 's9' });
  coord.receiveCallback(1, { type: 'preedit', text: 'AB' });
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  assert.equal(coord.docText, "Hello AB");
  assert.deepEqual(coord.committedExtent, [6, 7]); // 'A' frozen at [6, 7)
  
  // Expansion attempts to expand before into already-committed units: [0, 8)
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 0, newEnd: 8 });
  // Under Invariant I08, frozen committed 'A' is protected; candidate start is clamped to committedExtent[1] = 7:
  assert.deepEqual(coord.markedRange, [7, 8]);
  
  // Cancel composition:
  coord.receiveCallback(1, { type: 'cancel' });
  // Frozen 'A' at [6, 7) survives, original 'Hello ' intact, candidate 'B' removed:
  assert.equal(coord.docText, "Hello A");
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].beforeText, "Hello World");
  assert.equal(coord.undoStack[0].afterText, "Hello A");
  
  // Symmetrical undo/redo
  assert.equal(coord.undo(), true);
  assert.equal(coord.docText, "Hello World");
  assert.equal(coord.redo(), true);
  assert.equal(coord.docText, "Hello A");

  console.log("   -> PASS: Target expansion preserves frozen committed units; cancellation restores prefix and suffix surplus without data loss.");
}

// ---------------------------------------------------------------------------
// 10. Verifying Unrelated Commands Drained on Composition Cancellation
// ---------------------------------------------------------------------------
console.log("\n10. Verifying Unrelated Commands Drained on Cancellation...");
{
  const coord = new ProtocolCoordinator("Hello World", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's10' });
  
  // Queue unrelated command targeting [6, 11) ('World')
  const qUnrelated = coord.dispatchAppCommand({ type: 'format', target: [6, 11], style: { bold: true } });
  assert.equal(qUnrelated.status, 'Deferred');
  assert.equal(coord.pendingCommands.length, 1);
  
  // Cancel composition
  coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(coord.compositionState, 'Idle');
  // Pending commands drained:
  assert.equal(coord.pendingCommands.length, 0);
  // 'World' was formatted!
  assert.equal(coord.docAttributes[6].bold, true);
  assert.equal(coord.docAttributes[1].bold, undefined);
  assert.equal(coord.commandOutcomes.get(qUnrelated.ticketId).status, 'Applied');

  console.log("   -> PASS: Unrelated commands drained and applied on cancellation; zero trapped commands.");
}

// ---------------------------------------------------------------------------
// 11. Verifying Settlement Request and Command Cancellation APIs
// ---------------------------------------------------------------------------
console.log("\n11. Verifying Settlement Request and Command Cancellation APIs...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // requestSettlement when Idle
  assert.equal(coord.requestSettlement('idle').status, 'AlreadyIdle');
  
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's11' });
  const sReq = coord.requestSettlement('UserCommand');
  assert.equal(sReq.status, 'Requested');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  
  // Queue format and explicitly cancel it by ticketId
  const qCmd = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { italic: true } });
  assert.equal(coord.pendingCommands.length, 1);
  
  const cRes = coord.cancelCommand(qCmd.ticketId);
  assert.equal(cRes.status, 'Cancelled');
  assert.equal(coord.pendingCommands.length, 0);
  assert.equal(coord.commandOutcomes.get(qCmd.ticketId).status, 'Cancelled');
  assert.equal(coord.commandOutcomes.get(qCmd.ticketId).reason, 'CancelledByUser');

  console.log("   -> PASS: Settlement request and command cancellation APIs operate deterministically with explicit outcome records.");
}

console.log("\n=== ALL COORDINATOR REPAIR TESTS PASSED SUCCESSFULLY ===");
