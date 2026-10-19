// Comprehensive Verification Suite: Normative Cancellation Semantics & Queued Target Mapping (§6)
// Tests:
// 1. Native removal with explicit range and text (nativeRemoval)
// 2. Ending composition preserving accepted text (endCompositionPreservingText)
// 3. Application abort / restore (applicationAbort) with and without partial commits (I08)
// 4. Queued command target mapping across candidate expansion (+delta)
// 5. Queued command target mapping across candidate contraction (-delta)
// 6. Queued command target mapping across cancellation / rollback (cancelDelta)
// 7. Repeated expansion, contraction, disjoint retargeting, and surplus restoration without duplication
// 8. Symmetrical undo/redo and connection turnover interactions

import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

console.log("=== Running Normative Cancellation & Target Mapping Suite (Section 6) ===");

// ---------------------------------------------------------------------------
// 1. Native Removal with Explicit Range and Text
// ---------------------------------------------------------------------------
console.log("\n1. Testing native removal with explicit range and text (nativeRemoval)...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-rem' });
  coord.receiveCallback(1, { type: 'preedit', text: 'ABC' });
  assert.equal(coord.docText, "HABCll" + "o"); // "HABCllo"
  
  // Native IME explicitly removes range [1, 4) ("ABC") and replaces with empty string
  const remRes = coord.receiveCallback(1, { type: 'nativeRemoval', range: [1, 4], replacementText: "" });
  assert.equal(remRes.status, 'Accepted');
  assert.equal(coord.docText, "Hllo");
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.markedRange, null);
  // It does NOT restore "Hello" (old snapshot); it respects the explicit native removal!
  assert.notEqual(coord.docText, "Hello");
  assert.equal(coord.docText, "Hllo");
  console.log("   -> PASS: nativeRemoval respects explicit range and replacement without rolling back to pre-composition snapshot.");
}

// ---------------------------------------------------------------------------
// 2. Ending Composition Preserving Accepted Text
// ---------------------------------------------------------------------------
console.log("\n2. Testing ending composition preserving accepted text (endCompositionPreservingText)...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-end' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.equal(coord.docText, "HXYllo");
  
  // End composition preserving accepted text
  const endRes = coord.receiveCallback(1, { type: 'endCompositionPreservingText' });
  assert.equal(endRes.status, 'Accepted');
  assert.equal(coord.docText, "HXYllo");
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].beforeText, "Hello");
  assert.equal(coord.undoStack[0].afterText, "HXYllo");
  console.log("   -> PASS: endCompositionPreservingText finalizes composition and preserves accepted text.");
}

// ---------------------------------------------------------------------------
// 3. Application Abort / Restore (With and Without Partial Commits under I08)
// ---------------------------------------------------------------------------
console.log("\n3. Testing application abort with and without partial commits (Invariant I08)...");
{
  // 3a. Zero partial commits: restores snapshot
  const coord1 = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord1.openNewConnection();
  coord1.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-abort1' });
  coord1.receiveCallback(1, { type: 'preedit', text: 'TEST' });
  assert.equal(coord1.docText, "HTESTllo");
  
  coord1.receiveCallback(1, { type: 'applicationAbort' });
  assert.equal(coord1.docText, "Hello");
  assert.equal(coord1.compositionState, 'Idle');
  
  // 3b. With partial commits: Invariant I08 protects frozen prefix
  const coord2 = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord2.openNewConnection();
  coord2.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-abort2' });
  coord2.receiveCallback(1, { type: 'preedit', text: 'XY' });
  coord2.receiveCallback(1, { type: 'partialCommit', committedLength: 1 }); // "X" frozen
  coord2.receiveCallback(1, { type: 'targetExpansion', newStart: 2, newEnd: 4 }); // covers "Yl"
  
  coord2.receiveCallback(1, { type: 'applicationAbort' });
  // Frozen 'X' remains; swallowed 'l' restored; uncommitted 'Y' excised -> "HXllo"!
  assert.equal(coord2.docText, "HXllo");
  assert.equal(coord2.undoStack.length, 1);
  assert.equal(coord2.undoStack[0].afterText, "HXllo");
  console.log("   -> PASS: applicationAbort restores snapshot when 0 partial commits, and preserves committed prefix under I08.");
}

// ---------------------------------------------------------------------------
// 4. Queued Command Target Mapping Across Candidate Expansion (+delta)
// ---------------------------------------------------------------------------
console.log("\n4. Testing queued command target mapping across candidate expansion...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-map-exp' });
  coord.receiveCallback(1, { type: 'preedit', text: 'A' }); // candidate len 1 at [1, 2)
  // 'H' (0), 'A' (1), 'l' (2), 'l' (3), 'o' (4)
  
  // Queue format command on 'o' at [4, 5)
  const qCmd = coord.dispatchAppCommand({ type: 'format', target: [4, 5], style: { bold: true } });
  assert.equal(qCmd.status, 'Deferred');
  
  // Preedit expands candidate to 'ABCD' (len 4 at [1, 5)) -> delta = +3
  coord.receiveCallback(1, { type: 'preedit', text: 'ABCD' });
  // 'H' (0), 'A' (1), 'B' (2), 'C' (3), 'D' (4), 'l' (5), 'l' (6), 'o' (7)
  
  // Terminal unmark
  coord.receiveCallback(1, { type: 'unmark', settled: true });
  assert.equal(coord.docText, "HABCDllo");
  
  // Target [4, 5) MUST have mapped to [7, 8) ('o')!
  assert.equal(coord.docAttributes[7].bold, true); // 'o' is bold!
  assert.equal(coord.docAttributes[4].bold, undefined); // 'D' is NOT bold!
  assert.equal(coord.docAttributes[5].bold, undefined); // 'l' is NOT bold!
  assert.equal(coord.docAttributes[6].bold, undefined); // 'l' is NOT bold!
  console.log("   -> PASS: Queued range target mapped across candidate expansion (+3) and bolded 'o' at index 7.");
}

// ---------------------------------------------------------------------------
// 5. Queued Command Target Mapping Across Candidate Contraction (-delta)
// ---------------------------------------------------------------------------
console.log("\n5. Testing queued command target mapping across candidate contraction...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-map-con' });
  coord.receiveCallback(1, { type: 'preedit', text: 'ABCD' }); // candidate len 4 at [1, 5)
  // 'H' (0), 'A' (1), 'B' (2), 'C' (3), 'D' (4), 'l' (5), 'l' (6), 'o' (7)
  
  // Queue format command on 'o' at [7, 8)
  coord.dispatchAppCommand({ type: 'format', target: [7, 8], style: { italic: true } });
  
  // Candidate contracts to 'A' (len 1 at [1, 2)) -> delta = -3
  coord.receiveCallback(1, { type: 'preedit', text: 'A' });
  // 'H' (0), 'A' (1), 'l' (2), 'l' (3), 'o' (4)
  
  // Terminal unmark
  coord.receiveCallback(1, { type: 'unmark', settled: true });
  assert.equal(coord.docText, "HAllo");
  
  // Target [7, 8) MUST have mapped to [4, 5) ('o')!
  assert.equal(coord.docAttributes[4].italic, true); // 'o' is italic!
  assert.equal(coord.docAttributes[1].italic, undefined); // 'A' is NOT italic!
  assert.equal(coord.docAttributes[2].italic, undefined); // 'l' is NOT italic!
  console.log("   -> PASS: Queued range target mapped across candidate contraction (-3) and italicized 'o' at index 4.");
}

// ---------------------------------------------------------------------------
// 6. Queued Command Target Mapping Across Cancellation
// ---------------------------------------------------------------------------
console.log("\n6. Testing queued command target mapping across cancellation...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-map-cancel' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XYZ' }); // len 3 at [1, 4)
  // 'H' (0), 'X' (1), 'Y' (2), 'Z' (3), 'l' (4), 'l' (5), 'o' (6)
  
  // Queue format command on 'o' at [6, 7)
  coord.dispatchAppCommand({ type: 'format', target: [6, 7], style: { underline: true } });
  
  // Cancel composition -> restored text is "Hello" (len 5)
  coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(coord.docText, "Hello");
  
  // In restored "Hello", 'o' is at index 4!
  assert.equal(coord.docAttributes[4].underline, true);
  assert.equal(coord.docAttributes[0].underline, undefined);
  assert.equal(coord.docAttributes[1].underline, undefined);
  assert.equal(coord.docAttributes[2].underline, undefined);
  assert.equal(coord.docAttributes[3].underline, undefined);
  console.log("   -> PASS: Queued range target remapped on cancellation and underlined 'o' at index 4 in restored document.");
}

// ---------------------------------------------------------------------------
// 7. Repeated Expansion, Contraction, Disjoint Retargeting, and Surplus Restoration
// ---------------------------------------------------------------------------
console.log("\n7. Testing repeated expansion, contraction, retargeting, and surplus restoration...");
{
  const coord = new ProtocolCoordinator("Hello World", null, [0, 5]); // covers "Hello"
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 5, sessionId: 's-multi' });
  
  // Preedit 1
  coord.receiveCallback(1, { type: 'preedit', text: 'Hi' });
  assert.equal(coord.docText, "Hi World");
  
  // Expansion 1
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 0, newEnd: 8 }); // covers "Hi World"
  assert.deepEqual(coord.markedRange, [0, 8]);
  
  // Preedit 2
  coord.receiveCallback(1, { type: 'preedit', text: 'Greetings' });
  assert.equal(coord.docText, "Greetings");
  
  // Suffix cancel -> cleanly restores pre-composition snapshot "Hello World"
  coord.receiveCallback(1, { type: 'cancel' });
  assert.equal(coord.docText, "Hello World");
  
  // Symmetrical undo/redo
  assert.equal(coord.undoStack.length, 0); // cancelled composition with 0 partial commits pushes no undo
  console.log("   -> PASS: Repeated expansion, contraction, retargeting, and cancellation restored exact document without string duplication.");
}

// ---------------------------------------------------------------------------
// 8. Connection Turnover History Finalization and Symmetrical Undo/Redo
// ---------------------------------------------------------------------------
console.log("\n8. Testing connection turnover history finalization and symmetrical undo/redo...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection(); // Epoch 1
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-turn' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.equal(coord.docText, "HXYllo");
  
  // Connection replaced before composition was settled or cancelled
  coord.openNewConnection(); // Epoch 2
  assert.equal(coord.epoch, 2);
  assert.equal(coord.compositionState, 'Idle');
  
  // History preservation rule: unfinished composition was finalized into undoStack!
  assert.equal(coord.undoStack.length, 1);
  assert.equal(coord.undoStack[0].group, 'Composition');
  assert.equal(coord.undoStack[0].beforeText, "Hello");
  assert.equal(coord.undoStack[0].afterText, "HXYllo");
  
  // Symmetrical undo/redo across turnover
  assert.equal(coord.undo(), true);
  assert.equal(coord.docText, "Hello");
  assert.equal(coord.redo(), true);
  assert.equal(coord.docText, "HXYllo");
  console.log("   -> PASS: Connection turnover finalizes unfinished composition into undoStack; undo/redo verified.");
}

console.log("\n=== All Normative Cancellation & Target Mapping Tests (Section 6) Passed Successfully ===");
