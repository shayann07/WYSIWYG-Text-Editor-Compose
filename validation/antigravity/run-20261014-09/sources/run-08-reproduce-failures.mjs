// Executable Regressions Suite for Baseline Run-07 Candidate B Coordinator
// Reproduces Contract Violations & Defect Failures A through D against UNCHANGED run-20261014-07/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './sources/run-07-candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES A-D ON RUN-07 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Finding A: Repeated undo/redo corrupts an empty-document history preimage
// Trace:
// - Initial document ""
// - commit("x")
// - undo
// - redo
// - undo
// Expected sequence: ["x", "", "x", ""]
// =============================================================================
try {
  console.log("--- [Test A] Repeated undo/redo corrupts an empty-document history preimage ---");
  const c = new CandidateBCoordinator("", null, [0, 0]);
  const observedSeq = [];

  c.applyInput({ type: 'commit', text: 'x' });
  observedSeq.push(c.docText);

  c.applyInput({ type: 'undo' });
  observedSeq.push(c.docText);

  c.applyInput({ type: 'redo' });
  observedSeq.push(c.docText);

  c.applyInput({ type: 'undo' });
  observedSeq.push(c.docText);

  console.log(`Trace: Initial "", commit("x"), undo, redo, undo`);
  console.log(`Observed document sequence: ${JSON.stringify(observedSeq)}`);
  console.log(`Expected document sequence: ["x", "", "x", ""]`);

  assert.deepEqual(observedSeq, ["x", "", "x", ""], `Repeated undo/redo corrupted empty-document history preimage!`);
  console.log("[PASS] Test A");
} catch (e) {
  console.log(`[CONFIRMED FAILURE A]: ${e.message}\n`);
  failures.push({
    test: 'A',
    description: 'Repeated undo/redo corrupts an empty-document history preimage',
    error: e.message
  });
}

// =============================================================================
// Finding B: Native removal creates edits with no undo history
// Trace:
// - Initial "abc", selection [1, 2)
// - nativeRemoval(range: [1, 2), replacementText: "")
// - Document becomes "ac"
// - undo
// Expected: Accepted undo restoring "abc"
// =============================================================================
try {
  console.log("--- [Test B] Native removal creates edits with no undo history ---");
  const c = new CandidateBCoordinator("abc", null, [1, 2]);
  c.applyInput({ type: 'nativeRemoval', range: [1, 2], replacementText: '' });
  
  assert.equal(c.docText, "ac", "Document must be 'ac' after native removal");
  const undoCount = c.undoStack.length;
  const uRes = c.applyInput({ type: 'undo' });

  console.log(`Trace: Initial "abc", selection [1, 2), nativeRemoval([1, 2), "") -> undo()`);
  console.log(`Observed undo result:`, uRes, `undoStack count before undo: ${undoCount}`);
  console.log(`Expected undo status: "Accepted", docText restored to "abc"`);

  assert.equal(uRes.status, 'Accepted', `Native removal created edit with no undo history! ${JSON.stringify(uRes)}`);
  assert.equal(c.docText, 'abc', `Undo failed to restore "abc"!`);
  console.log("[PASS] Test B");
} catch (e) {
  console.log(`[CONFIRMED FAILURE B]: ${e.message}\n`);
  failures.push({
    test: 'B',
    description: 'Native removal creates edits with no undo history',
    error: e.message
  });
}

// =============================================================================
// Finding C: Disposal discards accepted provisional content
// Trace:
// - Initial "Hello"
// - startComposition([1, 2))
// - preedit("X") -> "HXllo"
// - dispose()
// Expected: docText remains "HXllo", exactly 1 Composition history group finalized
// =============================================================================
try {
  console.log("--- [Test C] Disposal discards accepted provisional content ---");
  const c = new CandidateBCoordinator("Hello", null, [1, 2]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  assert.equal(c.docText, "HXllo");

  const dRes = c.applyInput({ type: 'dispose' });
  const actualText = c.docText;
  const undoCount = c.undoStack.length;

  console.log(`Trace: Initial "Hello", startComposition([1, 2)), preedit("X") -> dispose()`);
  console.log(`Observed docText: "${actualText}", undoStack length: ${undoCount}`);
  console.log(`Expected docText: "HXllo", undoStack length: 1`);

  assert.equal(actualText, 'HXllo', `Disposal discarded accepted provisional content! "${actualText}" !== "HXllo"`);
  assert.equal(undoCount, 1, `Disposal failed to finalize composition history group!`);
  console.log("[PASS] Test C");
} catch (e) {
  console.log(`[CONFIRMED FAILURE C]: ${e.message}\n`);
  failures.push({
    test: 'C',
    description: 'Disposal discards accepted provisional content',
    error: e.message
  });
}

// =============================================================================
// Finding D: Resumed preedit uses a different target from proposed-state validation
// Trace:
// - Initial "Hello"
// - startComposition([1, 2))
// - preedit("X") -> "HXllo"
// - unmark(settled: false), leaving selection [2, 2)
// - preedit("Y") without an explicit range
// Expected: "HXYllo" (inserting at selection [2, 2))
// =============================================================================
try {
  console.log("--- [Test D] Resumed preedit uses different target from proposed-state validation ---");
  const c = new CandidateBCoordinator("Hello", null, [1, 2]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  assert.equal(c.docText, "HXllo");

  c.applyInput({ type: 'unmark', settled: false, selection: [2, 2] });
  c.applyInput({ type: 'preedit', text: 'Y' }); // omitted range
  const actualText = c.docText;

  console.log(`Trace: compose [1,2), preedit("X"), unmark(settled:false, sel:[2,2)), preedit("Y" without range)`);
  console.log(`Observed docText: "${actualText}"`);
  console.log(`Expected docText: "HXYllo"`);

  assert.equal(actualText, 'HXYllo', `Resumed preedit used stale attribution window instead of selection target! "${actualText}" !== "HXYllo"`);
  console.log("[PASS] Test D");
} catch (e) {
  console.log(`[CONFIRMED FAILURE D]: ${e.message}\n`);
  failures.push({
    test: 'D',
    description: 'Resumed preedit uses a different target from proposed-state validation',
    error: e.message
  });
}

// Save reproduced failures to raw-reproduced-failures.json
const failureReportPath = path.join(__dirname, 'raw-reproduced-failures.json');
fs.writeFileSync(failureReportPath, JSON.stringify(failures, null, 2), 'utf8');

console.log("================================================================================");
console.log(`=== REPRODUCTION SUMMARY: ${failures.length}/4 FAILURES REPRODUCED ON RUN-07 BASELINE ===`);
console.log(`=== Raw log written to ${failureReportPath} ===`);
console.log("================================================================================\n");
