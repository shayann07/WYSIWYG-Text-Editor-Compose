// Executable Regressions Suite for Baseline Run-05 Candidate B Coordinator
// Reproduces failures A through I against UNCHANGED run-20261014-05/candidate-b-coordinator.mjs

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from '../run-20261014-05/candidate-b-coordinator.mjs';

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES A-I ON RUN-05 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// A. Interior insertion goes to the wrong position
try {
  console.log("--- [Test A] Interior insertion goes to the wrong position ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'XYZ' });
  const retargetRes = c.applyInput({ type: 'retarget', range: [2, 2] });
  const preeditRes = c.applyInput({ type: 'preedit', text: 'Q' });
  
  const actualText = c.docText;
  const actualMarked = c.markedRange;
  const expectedText = "aXQYZc";
  const expectedMarked = [2, 3];
  
  console.log(`Trace: startComposition([1,2)) -> preedit("XYZ") -> retarget([2,2)) -> preedit("Q")`);
  console.log(`Actual text: "${actualText}", markedRange: [${actualMarked}]`);
  console.log(`Expected text: "${expectedText}", markedRange: [${expectedMarked}]`);
  
  assert.equal(actualText, expectedText, `Interior insertion placed at wrong position: "${actualText}" !== "${expectedText}"`);
  console.log("[PASS] Test A");
} catch (e) {
  console.log(`[CONFIRMED FAILURE A]: ${e.message}\n`);
  failures.push({ test: 'A', error: e.message });
}

// B. Implicit preedit ignores noncollapsed selection
try {
  console.log("--- [Test B] Implicit preedit ignores noncollapsed selection ---");
  const c = new CandidateBCoordinator("Hello", null, [1, 4]); // noncollapsed selection [1, 4)
  const preeditRes = c.applyInput({ type: 'preedit', text: 'X' }); // without startComposition
  
  const actualText = c.docText;
  const expectedText = "HXo";
  
  console.log(`Trace: Initial "Hello", selection [1,4) -> preedit("X") without startComposition`);
  console.log(`Actual text: "${actualText}"`);
  console.log(`Expected text: "${expectedText}"`);
  
  assert.equal(actualText, expectedText, `Implicit preedit ignored noncollapsed selection: "${actualText}" !== "${expectedText}"`);
  console.log("[PASS] Test B");
} catch (e) {
  console.log(`[CONFIRMED FAILURE B]: ${e.message}\n`);
  failures.push({ test: 'B', error: e.message });
}

// C. Settlement corrupts history snapshots
try {
  console.log("--- [Test C] Settlement corrupts history snapshots ---");
  const c = new CandidateBCoordinator("cat");
  c.applyInput({ type: 'startComposition', range: [0, 3] });
  c.applyInput({ type: 'preedit', text: 'cats' });
  c.applyInput({ type: 'unmark', settled: true });
  
  const actualDocText = c.docText;
  const historyEntry = c.undoStack[c.undoStack.length - 1];
  const historyAfterText = historyEntry ? historyEntry.afterText : null;
  const historyAfterAttrsCount = historyEntry ? historyEntry.afterAttrs.length : 0;
  
  console.log(`Trace: Initial "cat" -> startComposition([0,3)) -> preedit("cats") -> unmark(settled:true)`);
  console.log(`Actual docText: "${actualDocText}"`);
  console.log(`History afterText: "${historyAfterText}", afterAttrs count: ${historyAfterAttrsCount}`);
  console.log(`Expected docText: "cats", history afterText: "cats", afterAttrs count: 4`);
  
  assert.equal(actualDocText, "cats");
  assert.equal(historyAfterText, "cats", `History snapshot corrupted during settlement materialization: "${historyAfterText}" !== "cats"`);
  assert.equal(historyAfterAttrsCount, 4, `History afterAttrs length corrupted: ${historyAfterAttrsCount} !== 4`);
  console.log("[PASS] Test C");
} catch (e) {
  console.log(`[CONFIRMED FAILURE C]: ${e.message}\n`);
  failures.push({ test: 'C', error: e.message });
}

// D. Grapheme formatting fails when only a combining mark is inserted
try {
  console.log("--- [Test D] Grapheme formatting fails when only a combining mark is inserted ---");
  const c = new CandidateBCoordinator("a");
  c.applyInput({ type: 'startComposition', range: [1, 1] }); // Caret after 'a'
  c.applyInput({ type: 'preedit', text: '\u0301' }); // Inserting acute accent combining mark
  c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
  c.applyInput({ type: 'unmark', settled: true });
  
  const attrs = c.docAttributes;
  const isBaseBold = attrs[0]?.bold === true;
  const isAccentBold = attrs[1]?.bold === true;
  
  console.log(`Trace: Initial "a" -> startComposition([1,1)) -> preedit("\\u0301") -> queueCommand(Bold) -> unmark(settled:true)`);
  console.log(`Resulting text length: ${c.docText.length}, attrs[0] (base 'a') bold: ${isBaseBold}, attrs[1] (accent) bold: ${isAccentBold}`);
  console.log(`Expected: both base 'a' (index 0) and accent (index 1) are bold`);
  
  assert.equal(isBaseBold, true, `Base character 'a' was not formatted by outward grapheme closure`);
  assert.equal(isAccentBold, true, `Accent mark was not formatted`);
  console.log("[PASS] Test D");
} catch (e) {
  console.log(`[CONFIRMED FAILURE D]: ${e.message}\n`);
  failures.push({ test: 'D', error: e.message });
}

// E. Cancellation silently loses unrelated command tickets
try {
  console.log("--- [Test E] Cancellation silently loses unrelated command tickets ---");
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
  
  console.log(`Trace: Initial "Hello" -> startComposition([1,2)) -> preedit("X") -> queueCommand(TrackedRange [0,1)) -> cancel`);
  console.log(`Ticket ${ticketId} in pendingCommands: ${inPending}, in commandOutcomes: ${inOutcomes}`);
  console.log(`Expected: ticket MUST be preserved in pendingCommands OR have an explicit terminal outcome`);
  
  assert.ok(inPending || inOutcomes, `Ticket ${ticketId} was silently discarded and appears in neither pendingCommands nor commandOutcomes!`);
  console.log("[PASS] Test E");
} catch (e) {
  console.log(`[CONFIRMED FAILURE E]: ${e.message}\n`);
  failures.push({ test: 'E', error: e.message });
}

// F. Full partial commit strands commands
try {
  console.log("--- [Test F] Full partial commit strands commands ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget' }
  });
  const pcRes = c.applyInput({ type: 'partialCommit', length: 1 }); // Full partial commit of length 1 (current candidate len is 1)
  
  const stateAfterPC = c.compositionState;
  const historySessionAfterPC = c.historySession;
  const pendingCount = c.settlementObligation.pendingCommands.length;
  
  const unmarkRes = c.applyInput({ type: 'unmark', settled: true });
  
  console.log(`Trace: Initial "Hello" -> startComposition([1,2)) -> preedit("X") -> queueCommand(CompositionTarget) -> partialCommit(1)`);
  console.log(`State after partialCommit: ${stateAfterPC}, historySession: ${historySessionAfterPC}, pendingCommands: ${pendingCount}`);
  console.log(`Subsequent unmark response:`, unmarkRes);
  
  assert.notEqual(unmarkRes.status, 'Rejected', `Subsequent unmark was rejected with: ${unmarkRes.reason}; commands stranded!`);
  console.log("[PASS] Test F");
} catch (e) {
  console.log(`[CONFIRMED FAILURE F]: ${e.message}\n`);
  failures.push({ test: 'F', error: e.message });
}

// G. Invalid explicit replacement is accepted at a different range
try {
  console.log("--- [Test G] Invalid explicit replacement is accepted at a different range ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  const preeditRes = c.applyInput({ type: 'preedit', text: 'X', range: [-1, 2] }); // Invalid range [-1, 2)
  
  console.log(`Trace: Initial "Hello" -> startComposition([1,2)) -> preedit("X", range:[-1,2))`);
  console.log(`Response status: ${preeditRes.status}, reason: ${preeditRes.reason}`);
  console.log(`Document text after invalid preedit: "${c.docText}"`);
  
  assert.equal(preeditRes.status, 'Rejected', `Invalid explicit replacement was Accepted instead of Rejected!`);
  assert.equal(c.docText, "Hello", `Document was mutated despite invalid replacement range!`);
  console.log("[PASS] Test G");
} catch (e) {
  console.log(`[CONFIRMED FAILURE G]: ${e.message}\n`);
  failures.push({ test: 'G', error: e.message });
}

// H. Disjoint command targets are not mapped through edits
try {
  console.log("--- [Test H] Disjoint command targets are not mapped through edits ---");
  const c = new CandidateBCoordinator("aa BB cc"); // length 8
  c.applyInput({ type: 'startComposition', range: [6, 8] }); // covering "cc"
  c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget' }
  });
  c.applyInput({ type: 'retarget', range: [0, 2] }); // covering "aa"
  c.applyInput({ type: 'preedit', text: 'X' }); // "aa" replaced by "X", doc shrinks by 1! Visible: "X BB cc"
  c.applyInput({ type: 'unmark', settled: true });
  
  const finalText = c.docText;
  const boldIndices = [];
  c.docAttributes.forEach((attrs, idx) => {
    if (attrs.bold) boldIndices.push(idx);
  });
  
  console.log(`Trace: "aa BB cc" -> compose([6,8)) -> queueCommand(Bold) -> retarget([0,2)) -> preedit("X") -> unmark(settled:true)`);
  console.log(`Final text: "${finalText}"`);
  console.log(`Actual bold indices: [${boldIndices.join(', ')}]`);
  console.log(`Expected bold indices: [0, 5, 6] ("X" at 0, "cc" shifted from [6,8) to [5,7))`);
  
  assert.deepEqual(boldIndices, [0, 5, 6], `Disjoint command target island was not shifted through preceding edit! [${boldIndices}] !== [0, 5, 6]`);
  console.log("[PASS] Test H");
} catch (e) {
  console.log(`[CONFIRMED FAILURE H]: ${e.message}\n`);
  failures.push({ test: 'H', error: e.message });
}

// I. TrackedRange commands ignore their own target
try {
  console.log("--- [Test I] TrackedRange commands ignore their own target ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] }); // compose "e"
  c.applyInput({ type: 'preedit', text: 'X' });
  c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'TrackedRange', range: [0, 1] } // Explicitly targets "H" at [0, 1)
  });
  c.applyInput({ type: 'unmark', settled: true });
  
  const isIndex0Bold = c.docAttributes[0]?.bold === true;
  const isIndex1Bold = c.docAttributes[1]?.bold === true;
  
  console.log(`Trace: Initial "Hello" -> compose([1,2)) -> preedit("X") -> queueCommand(TrackedRange [0,1)) -> unmark(settled:true)`);
  console.log(`Index 0 ('H') bold: ${isIndex0Bold}, Index 1 ('X') bold: ${isIndex1Bold}`);
  console.log(`Expected: Index 0 ('H') is bold, Index 1 ('X') is NOT bold`);
  
  assert.equal(isIndex0Bold, true, `TrackedRange [0, 1) target 'H' was not formatted bold!`);
  assert.equal(isIndex1Bold, false, `Composition extent [1, 2) was incorrectly formatted instead of TrackedRange target!`);
  console.log("[PASS] Test I");
} catch (e) {
  console.log(`[CONFIRMED FAILURE I]: ${e.message}\n`);
  failures.push({ test: 'I', error: e.message });
}

console.log("\n================================================================================");
console.log(`=== REPRODUCTION COMPLETE: ${failures.length} / 9 CONFIRMED FAILURES ===`);
console.log("================================================================================");
failures.forEach(f => {
  console.log(`Failure ${f.test}: ${f.error}`);
});
