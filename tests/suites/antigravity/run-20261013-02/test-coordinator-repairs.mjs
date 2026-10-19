import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

console.log("=== Running Comprehensive Verification of Repaired Protocol Coordinator ===");

// ---------------------------------------------------------------------------
// 1. Settlement Bypass & Preedit Retention Fix (Defect A)
// ---------------------------------------------------------------------------
console.log("1. Verifying Settlement Bypass & Preedit Retention (Defect A)...");
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 's1' });
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' });

  // First command -> Deferred, enters AwaitingSettlement
  const r1 = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  assert.equal(r1.status, 'Deferred');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.pendingCommands.length, 1);

  // Second command while AwaitingSettlement -> MUST BE DEFERRED ALSO!
  const r2 = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { italic: true } });
  assert.equal(r2.status, 'Deferred');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.pendingCommands.length, 2);
  assert.equal(coord.pendingCommands[0].cmd.style.bold, true);
  assert.equal(coord.pendingCommands[1].cmd.style.italic, true); // FIFO order preserved!

  // Further preedit arrives while AwaitingSettlement:
  // Must NOT revert state to 'Composing', and must NOT drop pending settlement request!
  const preedit2 = coord.receiveCallback(1, { type: 'preedit', text: 'nihaoma' });
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.pendingCommands.length, 2);

  // Third command while still AwaitingSettlement:
  const r3 = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { underline: true } });
  assert.equal(r3.status, 'Deferred');
  assert.equal(coord.pendingCommands.length, 3);

  // Now native commit arrives without any special finish flag:
  const commitRes = coord.receiveCallback(1, { type: 'commit', text: '你好吗' });
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.pendingCommands.length, 0); // All 3 commands drained in FIFO order!

  // Check attributes on committed text:
  // "你好吗" (indices 0..3) should have received bold, italic, and underline!
  for (let i = 0; i < 3; i++) {
    assert.equal(coord.docAttributes[i].bold, true);
    assert.equal(coord.docAttributes[i].italic, true);
    assert.equal(coord.docAttributes[i].underline, true);
  }
  // Suffix "llo" (indices 3..6) must NOT have received these formats!
  for (let i = 3; i < coord.docText.length; i++) {
    assert.equal(coord.docAttributes[i].bold, undefined);
    assert.equal(coord.docAttributes[i].italic, undefined);
    assert.equal(coord.docAttributes[i].underline, undefined);
  }

  // History stack must have 4 groups: 1 Composition + 3 Formattings
  assert.equal(coord.undoStack.length, 4);
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[1].group, 'Formatting');
  assert.equal(coord.undoStack[2].group, 'Formatting');
  assert.equal(coord.undoStack[3].group, 'Formatting');

  console.log("   -> PASS: FIFO queueing maintained across Composing and AwaitingSettlement; preedit did not lose settlement.");
}

// ---------------------------------------------------------------------------
// 2. Precise CompositionTarget Extent & Edge Cases (Defect B)
// ---------------------------------------------------------------------------
console.log("2. Verifying CompositionTarget Extent Across All Edge Cases (Defect B)...");

// Case 2.1: Standard composition with replacement and surrounding attributes
{
  // Initial doc: "A" (red), "B" (italic), "C" (underline)
  const initialAttrs = [
    { color: 'red' },
    { italic: true },
    { underline: true }
  ];
  const coord = new ProtocolCoordinator("ABC", initialAttrs);
  coord.openNewConnection();

  // Compose only "B" at [1, 2)
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's2' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "AXC");

  // Queue Bold targeting this composition
  const r1 = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  assert.equal(r1.status, 'Deferred');

  // Commit "Y"
  coord.receiveCallback(1, { type: 'commit', text: 'Y' });
  assert.equal(coord.docText, "AYC");

  // Surrounding attributes MUST BE UNTOUCHED:
  assert.deepEqual(coord.docAttributes[0], { color: 'red' });
  assert.equal(coord.docAttributes[1].bold, true); // only index 1 has bold!
  assert.deepEqual(coord.docAttributes[2], { underline: true });

  console.log("   -> PASS: Case 2.1 Surrounding differently attributed text preserved, bold applied strictly to [1, 2).");
}

// Case 2.2: Candidate growth
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 }); // compose 'e'
  coord.receiveCallback(1, { type: 'preedit', text: 'WORLD' }); // len 5 -> text "HWORLDllo"
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  coord.receiveCallback(1, { type: 'commit', text: 'COMPLETED' }); // len 9 -> text "HCOMPLETEDllo"
  assert.equal(coord.docText, "HCOMPLETEDllo");

  // Bold must be strictly on [1, 10):
  assert.equal(coord.docAttributes[0]?.bold, undefined); // 'H'
  for (let i = 1; i < 10; i++) {
    assert.equal(coord.docAttributes[i].bold, true, `Unit ${i} should be bold`);
  }
  for (let i = 10; i < coord.docText.length; i++) {
    assert.equal(coord.docAttributes[i]?.bold, undefined, `Unit ${i} should not be bold`);
  }
  console.log("   -> PASS: Case 2.2 Candidate growth correctly expands extent to [1, 10).");
}

// Case 2.3: Candidate shrinkage
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 4 }); // compose "ell" (len 3)
  coord.receiveCallback(1, { type: 'preedit', text: 'AB' }); // len 2
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  coord.receiveCallback(1, { type: 'commit', text: 'Z' }); // len 1 -> text "HZo"
  assert.equal(coord.docText, "HZo");

  // Bold must be strictly on [1, 2) ('Z'):
  assert.equal(coord.docAttributes[0]?.bold, undefined); // 'H'
  assert.equal(coord.docAttributes[1].bold, true); // 'Z'
  assert.equal(coord.docAttributes[2]?.bold, undefined); // 'o'
  console.log("   -> PASS: Case 2.3 Candidate shrinkage correctly narrows extent to [1, 2).");
}

// Case 2.4: Empty final candidate (cancellation / deletion)
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  
  // Commit empty text ""
  const revBefore = coord.documentRevision;
  coord.receiveCallback(1, { type: 'commit', text: '' });
  assert.equal(coord.docText, "Hllo");

  // Extent was [1, 1) (empty range): formatting empty range produces NO history entry and NO additional doc revision bump!
  // The undo stack should only have 1 entry (the Composition that deleted 'e'), NO formatting group!
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].group, 'Composition');
  console.log("   -> PASS: Case 2.4 Empty candidate produces empty extent and no-op format without spurious history.");
}

// Case 2.5: Partial commits
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'ABCD' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  
  // Partial commit first 2 units "AB"
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 2 });
  
  // Final commit remaining "CD"
  coord.receiveCallback(1, { type: 'commit', text: 'CD' });
  assert.equal(coord.docText, "HABCDllo");

  // Total session committed extent: [1, 1 + 2 + 2) = [1, 5) ("ABCD")
  assert.equal(coord.docAttributes[0]?.bold, undefined); // 'H'
  for (let i = 1; i < 5; i++) {
    assert.equal(coord.docAttributes[i].bold, true, `Unit ${i} should be bold`);
  }
  for (let i = 5; i < coord.docText.length; i++) {
    assert.equal(coord.docAttributes[i]?.bold, undefined, `Unit ${i} should not be bold`);
  }
  console.log("   -> PASS: Case 2.5 Partial commit session extent [1, 5) correctly formatted.");
}

// Case 2.6: Target expansion
{
  const coord = new ProtocolCoordinator("ABCDEF");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 2, end: 4 }); // "CD"
  coord.receiveCallback(1, { type: 'preedit', text: 'XX' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  
  // IME expands target to [1, 5)
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 1, newEnd: 5 });
  coord.receiveCallback(1, { type: 'commit', text: 'YYYY' });
  assert.equal(coord.docText, "AYYYYF");

  // Expanded extent [1, 5) receives bold:
  assert.equal(coord.docAttributes[0]?.bold, undefined);
  for (let i = 1; i < 5; i++) {
    assert.equal(coord.docAttributes[i].bold, true);
  }
  assert.equal(coord.docAttributes[5]?.bold, undefined);
  console.log("   -> PASS: Case 2.6 Target expansion correctly expands format target.");
}

// ---------------------------------------------------------------------------
// 3. Unmark Contract & Settlement Handling (Defect C)
// ---------------------------------------------------------------------------
console.log("3. Verifying Unmark Preserves Text, Clears State & Handles Settlement (Defect C)...");
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'PREEDIT' });
  assert.equal(coord.docText, "HPREEDITllo");

  // Queue Bold targeting composition
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  assert.equal(coord.compositionState, 'AwaitingSettlement');

  // Now send unmark (default settled: true)
  const unmarkRes = coord.receiveCallback(1, { type: 'unmark' });
  assert.equal(unmarkRes.status, 'Accepted');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.markedRange, null);
  assert.equal(coord.activeSessionId, null);
  assert.equal(coord.docText, "HPREEDITllo"); // Accepted text preserved!

  // Pending Bold executed on the accepted preedit extent [1, 8):
  assert.equal(coord.docAttributes[0]?.bold, undefined);
  for (let i = 1; i < 8; i++) {
    assert.equal(coord.docAttributes[i].bold, true);
  }
  assert.equal(coord.docAttributes[8]?.bold, undefined);

  // Composition undo group exists:
  assert.equal(coord.undoStack.length, 2);
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[1].group, 'Formatting');

  // Undo formatting:
  coord.undo();
  assert.equal(coord.docText, "HPREEDITllo");
  assert.equal(coord.docAttributes[1].bold, undefined);

  // Undo composition:
  coord.undo();
  assert.equal(coord.docText, "Hello");

  // Redo composition:
  coord.redo();
  assert.equal(coord.docText, "HPREEDITllo");

  // Redo formatting:
  coord.redo();
  assert.equal(coord.docText, "HPREEDITllo");
  assert.equal(coord.docAttributes[1].bold, true);

  console.log("   -> PASS: Unmark preserves accepted text, creates history group, clears transient state, and supports symmetrical undo/redo.");
}

// Case 3.2: Unmark with explicit settled: false (adapter requires separate settlement fence)
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'XYZ' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });

  // Unmark with settled: false
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  assert.equal(coord.markedRange, null); // Transient markedRange cleared!
  assert.equal(coord.compositionState, 'AwaitingSettlement'); // Still awaiting settlement fence!
  assert.equal(coord.pendingCommands.length, 1); // Command still queued!

  // Explicit completion fence arrives via commit:
  coord.receiveCallback(1, { type: 'commit', text: '' }); // empty commit completes settlement
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.pendingCommands.length, 0); // Drained!

  console.log("   -> PASS: Unmark with settled: false waits for platform settlement fence.");
}

// ---------------------------------------------------------------------------
// 4. Additional Protocol Audit Scenarios
// ---------------------------------------------------------------------------
console.log("4. Verifying Additional Protocol Audit Scenarios...");

// Audit 4.1: Commit without custom "finish" flag
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'abc' });
  // Standard IME commitText without "finish" property:
  coord.receiveCallback(1, { type: 'commit', text: 'ABC' });
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.markedRange, null);
  assert.equal(coord.docText, "ABCllo");
  console.log("   -> PASS: Commit without finish flag transitions to Idle.");
}

// Audit 4.2: Explicit vs implicit composition start with non-collapsed selection
{
  // Document "0123456789" with selection [3, 7) ("3456")
  const coord = new ProtocolCoordinator("0123456789", null, [3, 7]);
  coord.openNewConnection();
  
  // Implicit preedit arrives without start/end:
  coord.receiveCallback(1, { type: 'preedit', text: 'REPLACEMENT' });
  // Must replace [3, 7), NOT [0, 0]!
  assert.equal(coord.docText, "012REPLACEMENT789");
  assert.deepEqual(coord.markedRange, [3, 3 + 'REPLACEMENT'.length]);

  // Commit replacement:
  coord.receiveCallback(1, { type: 'commit', text: 'REPLACEMENT' });
  assert.equal(coord.docText, "012REPLACEMENT789");

  // Undo restores exact original text and selection [3, 7):
  coord.undo();
  assert.equal(coord.docText, "0123456789");
  assert.deepEqual(coord.selection, [3, 7]);
  console.log("   -> PASS: Implicit preedit uses active non-collapsed selection [3, 7), never [0, 0].");
}

// Audit 4.3: Connection replacement & pending commands from previous epoch
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection(); // epoch 1
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'foo' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  assert.equal(coord.pendingCommands.length, 1);

  // New connection opened (e.g. blur/refocus or window change)
  const epoch2 = coord.openNewConnection(); // epoch 2
  assert.equal(epoch2, 2);
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.pendingCommands.length, 0); // Previous command expired!

  // Late callback from Epoch 1 rejected:
  const staleRes = coord.receiveCallback(1, { type: 'commit', text: 'stale' });
  assert.equal(staleRes.status, 'StaleEpoch');
  console.log("   -> PASS: Connection replacement expires pending commands and rejects stale epoch callbacks.");
}

// Audit 4.4: Composition cancellation
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'temporary' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  
  // Native IME cancels composition (e.g. Escape or focus lost)
  coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(coord.docText, "Hello"); // Restored to pre-composition snapshot!
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.markedRange, null);
  assert.equal(coord.pendingCommands.length, 0); // Cancelled!
  assert.equal(coord.undoStack.length, 0); // No phantom undo entry!
  console.log("   -> PASS: Cancellation restores baseline, cancels commands, and leaves no phantom undo entry.");
}

// Audit 4.5: Reentrancy protection
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.isProcessingCallback = true;
  let reentrantCaught = false;
  try {
    coord.receiveCallback(1, { type: 'preedit', text: 'reentrant' });
  } catch (err) {
    if (err.message.includes("Reentrant callback execution detected")) {
      reentrantCaught = true;
    }
  }
  coord.isProcessingCallback = false;
  assert.equal(reentrantCaught, true);
  console.log("   -> PASS: Reentrancy guard actively prevents recursive callback modification.");
}

// Audit 4.6: Candidate reconciliation integrated into coordinator
{
  // Document with bold 'a' and italic 'b'
  const initialAttrs = [{ bold: true }, { italic: true }];
  const coord = new ProtocolCoordinator("ab", initialAttrs, [0, 2]);
  coord.openNewConnection();
  
  // Detour ab -> x -> ab through actual coordinator!
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'x' });
  assert.equal(coord.docText, "x");
  
  coord.receiveCallback(1, { type: 'preedit', text: 'ab' });
  assert.equal(coord.docText, "ab");
  // Attributes must have been preserved through candidate reconciliation!
  assert.equal(coord.docAttributes[0].bold, true);
  assert.equal(coord.docAttributes[1].italic, true);
  console.log("   -> PASS: Candidate reconciliation (§4.3) fully integrated into coordinator preedit loop.");
}

// Audit 4.7: Partial commit preservation across cancellation (Invariant I08 & R03)
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 }); // compose "He"
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' }); // text "nihaollo"
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 2 }); // "ni" committed!
  
  // Now native IME cancels remaining candidate (e.g. Escape key)
  const cancelRes = coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(cancelRes.status, 'Accepted');
  // Frozen prefix "ni" MUST SURVIVE: remaining "hao" removed, doc becomes "nillo"!
  assert.equal(coord.docText, "nillo");
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.markedRange, null);
  assert.deepEqual(coord.lastSettledExtent, [0, 2]);
  
  // A composition history group was created representing the partial commit:
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].group, 'Composition');
  
  // Undo restores pre-composition "Hello":
  coord.undo();
  assert.equal(coord.docText, "Hello");
  
  // Redo restores "nillo":
  coord.redo();
  assert.equal(coord.docText, "nillo");
  console.log("   -> PASS: Partial commit prefix survives cancellation and supports symmetrical undo/redo.");
}

// Audit 4.8: Direct commit in Idle state uses active selection extent (not offset 0)
{
  const coord = new ProtocolCoordinator("Hello World", null, [6, 11]);
  coord.openNewConnection();
  
  // Direct commit in Idle state:
  const commitRes = coord.receiveCallback(1, { type: 'commit', text: 'Everyone' });
  assert.equal(commitRes.status, 'Accepted');
  assert.equal(coord.docText, "Hello Everyone");
  // Extent must be [6, 14), NOT [0, 8)!
  assert.deepEqual(coord.lastSettledExtent, [6, 14]);
  
  // Undo restores "Hello World" with selection [6, 11):
  coord.undo();
  assert.equal(coord.docText, "Hello World");
  assert.deepEqual(coord.selection, [6, 11]);
  console.log("   -> PASS: Direct commit in Idle state computes exact extent from selection [6, 14).");
}

// Audit 4.9: CompositionTarget(sessionId) matching and session mismatch rejection
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'session-alpha' });
  coord.receiveCallback(1, { type: 'preedit', text: 'TARGETED' });
  
  // Command with matching explicit CompositionTarget(sessionId)
  const cmdMatching = {
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 'session-alpha' },
    style: { bold: true }
  };
  const rMatching = coord.dispatchAppCommand(cmdMatching);
  assert.equal(rMatching.status, 'Deferred');
  
  // Command with mismatched sessionId executed directly
  const cmdMismatch = {
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 'session-omega' },
    style: { italic: true }
  };
  const rMismatch = coord.executeFormatCommand(cmdMismatch);
  assert.equal(rMismatch.status, 'Rejected');
  assert.equal(rMismatch.reason, 'TargetSessionMismatch');
  
  // Settle session-alpha
  coord.receiveCallback(1, { type: 'commit', text: 'TARGETED' });
  assert.equal(coord.docText, "HTARGETEDllo");
  // Matching bold command applied to [1, 9):
  assert.equal(coord.docAttributes[1].bold, true);
  console.log("   -> PASS: CompositionTarget(sessionId) validates session ID and rejects mismatches.");
}

// Audit 4.10: Focus loss and disposal lifecycle
{
  const coord = new ProtocolCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'blur' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  assert.equal(coord.pendingCommands.length, 1);
  
  // Focus lost with cancel strategy:
  const focusRes = coord.onFocusLost('cancel');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.pendingCommands.length, 0); // Cancelled with FocusLost
  assert.equal(coord.docText, "Hello");
  
  // Disposal:
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { italic: true } });
  const dispRes = coord.dispose();
  assert.equal(dispRes.status, 'Disposed');
  assert.equal(coord.connectionState, 'Closed');
  assert.equal(coord.pendingCommands.length, 0);
  console.log("   -> PASS: Focus loss and disposal expire pending commands with explicit reasons.");
}

console.log("\n=== ALL COORDINATOR REPAIR VERIFICATIONS PASSED ===");
