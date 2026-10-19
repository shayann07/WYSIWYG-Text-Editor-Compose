// Verification of Repaired Candidate B Coordinator against Regressions A through I

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';

export function runRepairedRegressionsTest() {
  console.log("================================================================================");
  console.log("=== VERIFYING REPAIRED CANDIDATE B COORDINATOR AGAINST FAILURES A-I          ===");
  console.log("================================================================================\n");

  let passedCount = 0;

// Test A: Interior insertion goes to the correct position
{
  console.log("--- [Test A] Interior insertion goes to the correct position ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: 'startComposition', range: [1, 2] }); // "b"
  c.applyInput({ type: 'preedit', text: 'XYZ' }); // "aXYZc"
  c.applyInput({ type: 'retarget', range: [2, 2] }); // caret between X and Y
  c.applyInput({ type: 'preedit', text: 'Q' }); // insert Q
  
  const actualText = c.docText;
  const actualMarked = c.markedRange;
  console.log(`Result: "${actualText}", markedRange: [${actualMarked}]`);
  assert.equal(actualText, "aXQYZc");
  assert.deepEqual(actualMarked, [2, 3]);

  // Rollback ownership test: if cancelled, original 'b' restored!
  const cClone = new CandidateBCoordinator("abc");
  cClone.applyInput({ type: 'startComposition', range: [1, 2] });
  cClone.applyInput({ type: 'preedit', text: 'XYZ' });
  cClone.applyInput({ type: 'retarget', range: [2, 2] });
  cClone.applyInput({ type: 'preedit', text: 'Q' });
  cClone.applyInput({ type: 'cancel' });
  assert.equal(cClone.docText, "abc", "Cancellation did not restore exact committed text!");

  console.log("[PASS] Test A verified");
  passedCount++;
}

// Test B: Implicit preedit respects noncollapsed selection
{
  console.log("\n--- [Test B] Implicit preedit respects noncollapsed selection ---");
  // Forward selection
  const c1 = new CandidateBCoordinator("Hello", null, [1, 4]);
  c1.applyInput({ type: 'preedit', text: 'X' });
  assert.equal(c1.docText, "HXo", `Forward selection replacement failed: "${c1.docText}"`);

  // Reversed selection
  const c2 = new CandidateBCoordinator("Hello", null, [4, 1]);
  c2.applyInput({ type: 'preedit', text: 'X' });
  assert.equal(c2.docText, "HXo", `Reversed selection replacement failed: "${c2.docText}"`);

  // Direct commit in Idle
  const c3 = new CandidateBCoordinator("Hello", null, [1, 4]);
  c3.applyInput({ type: 'commit', text: 'X' });
  assert.equal(c3.docText, "HXo", `Direct commit with selection replacement failed: "${c3.docText}"`);

  // Resumed preedit
  const c4 = new CandidateBCoordinator("Hello", null, [1, 4]);
  c4.applyInput({ type: 'preedit', text: 'X' });
  c4.applyInput({ type: 'unmark', settled: false }); // AwaitingSettlement
  c4.applyInput({ type: 'preedit', text: 'XY' }); // Resumed
  assert.equal(c4.docText, "HXYo", `Resumed preedit failed: "${c4.docText}"`);

  console.log("[PASS] Test B verified");
  passedCount++;
}

// Test C: Settlement history snapshots are atomic and uncorrupted
{
  console.log("\n--- [Test C] Settlement history snapshots are atomic and uncorrupted ---");
  const c = new CandidateBCoordinator("cat");
  c.applyInput({ type: 'startComposition', range: [0, 3] });
  c.applyInput({ type: 'preedit', text: 'cats' });
  c.applyInput({ type: 'unmark', settled: true });
  
  assert.equal(c.docText, "cats");
  const hist = c.undoStack[c.undoStack.length - 1];
  assert.ok(hist, "Missing history entry");
  assert.equal(hist.beforeText, "cat");
  assert.equal(hist.afterText, "cats");
  assert.equal(hist.afterAttrs.length, 4, `Corrupted history afterAttrs count: ${hist.afterAttrs.length}`);

  // Test growth, shrinkage, deletion, partial commit, completeSettlement, nativeRemoval
  // Growth:
  const cGrow = new CandidateBCoordinator("a");
  cGrow.applyInput({ type: 'startComposition', range: [0, 1] });
  cGrow.applyInput({ type: 'preedit', text: 'abc' });
  cGrow.applyInput({ type: 'unmark', settled: true });
  assert.equal(cGrow.docText, "abc");
  assert.equal(cGrow.undoStack[cGrow.undoStack.length - 1].afterText, "abc");

  // Shrinkage:
  const cShrink = new CandidateBCoordinator("abcd");
  cShrink.applyInput({ type: 'startComposition', range: [0, 4] });
  cShrink.applyInput({ type: 'preedit', text: 'a' });
  cShrink.applyInput({ type: 'unmark', settled: true });
  assert.equal(cShrink.docText, "a");
  assert.equal(cShrink.undoStack[cShrink.undoStack.length - 1].afterText, "a");

  // Deletion:
  const cDel = new CandidateBCoordinator("abcd");
  cDel.applyInput({ type: 'startComposition', range: [1, 3] });
  cDel.applyInput({ type: 'preedit', text: '' });
  cDel.applyInput({ type: 'unmark', settled: true });
  assert.equal(cDel.docText, "ad");
  assert.equal(cDel.undoStack[cDel.undoStack.length - 1].afterText, "ad");

  // CompleteSettlement:
  const cSettle = new CandidateBCoordinator("cat");
  cSettle.applyInput({ type: 'startComposition', range: [0, 3] });
  cSettle.applyInput({ type: 'preedit', text: 'cats' });
  const unRes = cSettle.applyInput({ type: 'unmark', settled: false });
  cSettle.applyInput({ type: 'completeSettlement', token: unRes.token });
  assert.equal(cSettle.docText, "cats");
  assert.equal(cSettle.undoStack[cSettle.undoStack.length - 1].afterText, "cats");

  // NativeRemoval:
  const cRem = new CandidateBCoordinator("cat");
  cRem.applyInput({ type: 'startComposition', range: [1, 2] });
  cRem.applyInput({ type: 'preedit', text: 'XYZ' });
  cRem.applyInput({ type: 'nativeRemoval', range: [1, 4] });
  assert.equal(cRem.docText, "ct");

  console.log("[PASS] Test C verified");
  passedCount++;
}

// Test D: Grapheme formatting applies outward closure across complex Unicode boundaries
{
  console.log("\n--- [Test D] Grapheme formatting applies outward closure ---");
  // Pre-existing base + newly inserted combining mark
  const c = new CandidateBCoordinator("a");
  c.applyInput({ type: 'startComposition', range: [1, 1] });
  c.applyInput({ type: 'preedit', text: '\u0301' });
  c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
  c.applyInput({ type: 'unmark', settled: true });
  
  assert.equal(c.docAttributes[0]?.bold, true, "Base character 'a' was not formatted bold!");
  assert.equal(c.docAttributes[1]?.bold, true, "Combining mark '\\u0301' was not formatted bold!");

  // ZWJ Emoji sequence
  const emoji = "\u{1F468}\u200D\u{1F469}\u200D\u{1F467}\u200D\u{1F466}"; // Family
  const cEmoji = new CandidateBCoordinator(emoji);
  cEmoji.applyInput({ type: 'startComposition', range: [0, 2] }); // first codepoint
  cEmoji.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { italic: true } }, target: { type: 'CompositionTarget' } });
  cEmoji.applyInput({ type: 'unmark', settled: true });
  for (let i = 0; i < cEmoji.docText.length; i++) {
    assert.equal(cEmoji.docAttributes[i]?.italic, true, `Unit ${i} of ZWJ emoji was not italic!`);
  }

  // Regional Indicator Flag: 🇺🇸
  const flag = "\u{1F1FA}\u{1F1F8}";
  const cFlag = new CandidateBCoordinator(flag);
  cFlag.applyInput({ type: 'startComposition', range: [0, 2] });
  cFlag.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { underline: true } }, target: { type: 'CompositionTarget' } });
  cFlag.applyInput({ type: 'unmark', settled: true });
  for (let i = 0; i < cFlag.docText.length; i++) {
    assert.equal(cFlag.docAttributes[i]?.underline, true, `Unit ${i} of flag was not underlined!`);
  }

  console.log("[PASS] Test D verified");
  passedCount++;
}

// Test E: Cancellation preserves unrelated command tickets
{
  console.log("\n--- [Test E] Cancellation preserves unrelated command tickets ---");
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

  const inPending = c.settlementObligation.pendingCommands.some(cmd => cmd.ticketId === ticketId);
  const inOutcomes = c.commandOutcomes.has(ticketId);
  assert.ok(inPending, `Unrelated ticket ${ticketId} was lost on composition cancellation!`);
  assert.equal(inOutcomes, false, `Unrelated ticket should remain Pending, not received terminal outcome!`);

  console.log("[PASS] Test E verified");
  passedCount++;
}

// Test F: Full partial commit preserves active composition and queued commands
{
  console.log("\n--- [Test F] Full partial commit preserves active composition and queued commands ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget' }
  });
  const pcRes = c.applyInput({ type: 'partialCommit', length: 1 });
  assert.equal(pcRes.status, 'Accepted');
  assert.equal(c.compositionState, 'Composing', `State after full partial commit should be Composing, got ${c.compositionState}`);
  assert.equal(c.settlementObligation.pendingCommands.length, 1, `Pending command was stranded or lost!`);

  // Subsequent unmark succeeds and drains command!
  const unmarkRes = c.applyInput({ type: 'unmark', settled: true });
  assert.equal(unmarkRes.status, 'Accepted');
  assert.equal(c.compositionState, 'Idle');
  assert.equal(c.settlementObligation.pendingCommands.length, 0);

  console.log("[PASS] Test F verified");
  passedCount++;
}

// Test G: Invalid explicit replacement is rejected atomically with zero mutation
{
  console.log("\n--- [Test G] Invalid explicit replacement is rejected atomically with zero mutation ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  const docRevBefore = c.documentRevision;
  const preeditRes = c.applyInput({ type: 'preedit', text: 'X', range: [-1, 2] });

  assert.equal(preeditRes.status, 'Rejected');
  assert.equal(preeditRes.reason, 'InvalidTargetRange');
  assert.equal(c.docText, "Hello");
  assert.equal(c.documentRevision, docRevBefore);

  // Test invalid partial commit counts
  const pcRes1 = c.applyInput({ type: 'partialCommit', length: -1 });
  assert.equal(pcRes1.status, 'Rejected');
  const pcRes2 = c.applyInput({ type: 'partialCommit', length: 999 });
  assert.equal(pcRes2.status, 'Rejected');

  // Test invalid start/end/reversed ranges
  const ret1 = c.applyInput({ type: 'retarget', range: [3, 2] }); // reversed
  assert.equal(ret1.status, 'Rejected');
  const ret2 = c.applyInput({ type: 'retarget', range: [0, 999] }); // out of bounds
  assert.equal(ret2.status, 'Rejected');

  console.log("[PASS] Test G verified");
  passedCount++;
}

// Test H: Disjoint command targets mapped through preceding edits
{
  console.log("\n--- [Test H] Disjoint command targets mapped through preceding edits ---");
  const c = new CandidateBCoordinator("aa BB cc");
  c.applyInput({ type: 'startComposition', range: [6, 8] }); // "cc"
  c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget' }
  });
  c.applyInput({ type: 'retarget', range: [0, 2] }); // "aa"
  c.applyInput({ type: 'preedit', text: 'X' }); // "aa" -> "X", doc becomes "X BB cc"
  c.applyInput({ type: 'unmark', settled: true });

  const finalText = c.docText;
  assert.equal(finalText, "X BB cc");
  const boldIndices = [];
  c.docAttributes.forEach((attrs, idx) => {
    if (attrs.bold) boldIndices.push(idx);
  });
  console.log(`Bold indices: [${boldIndices.join(', ')}]`);
  assert.deepEqual(boldIndices, [0, 5, 6]);

  console.log("[PASS] Test H verified");
  passedCount++;
}

// Test I: TrackedRange commands resolve own target and reject session mismatches
{
  console.log("\n--- [Test I] TrackedRange commands resolve own target and reject session mismatches ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] }); // "e"
  c.applyInput({ type: 'preedit', text: 'X' });
  const qRes = c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'TrackedRange', range: [0, 1] } // targets 'H'
  });
  c.applyInput({ type: 'unmark', settled: true });

  assert.equal(c.docAttributes[0]?.bold, true, "Index 0 'H' must be bold");
  assert.equal(c.docAttributes[1]?.bold !== true, true, "Index 1 'X' must NOT be bold");

  // Mismatched session target rejection
  const c2 = new CandidateBCoordinator("Hello");
  c2.applyInput({ type: 'startComposition', range: [1, 2] });
  const q2 = c2.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget', sessionId: 'wrong-session-999' }
  });
  c2.applyInput({ type: 'unmark', settled: true });
  assert.equal(c2.commandOutcomes.get(q2.ticketId)?.status, 'Rejected');
  assert.equal(c2.commandOutcomes.get(q2.ticketId)?.reason, 'TargetSessionMismatch');

  console.log("[PASS] Test I verified");
  passedCount++;
}

  console.log("\n================================================================================");
  console.log(`=== ALL ${passedCount} / 9 REPAIRED REGRESSION TESTS PASSED CLEANLY!         ===`);
  console.log("================================================================================");
  assert.equal(passedCount, 9, "All 9 regression tests must pass");
  return { passed: passedCount, total: 9 };
}

if (process.argv[1]?.endsWith('test-repaired-regressions.mjs')) {
  runRepairedRegressionsTest();
}
