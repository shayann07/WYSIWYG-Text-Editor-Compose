// Executable Regressions Suite for Baseline Run-06 Candidate B Coordinator
// Reproduces Contract Violations & Defect Failures A through F against UNCHANGED run-20261014-06/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from '../run-20261014-06/candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES A-F ON RUN-06 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Finding A: Connection replacement discards accepted text
// Contract Requirement: Under the preserve-visible-content turnover policy,
// replacing an active connection retains latest accepted visible text and
// finalizes exactly one composition history group before teardown.
// =============================================================================
try {
  console.log("--- [Test A] Connection replacement discards accepted text ---");
  const c = new CandidateBCoordinator("Hello", null, [1, 2]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' }); // visible: "HXllo"
  assert.equal(c.docText, "HXllo");

  const rConn = c.applyInput({ type: 'openNewConnection' });
  const actualText = c.docText;
  const undoCount = c.undoStack.length;
  console.log(`Trace: Initial "Hello", compose [1,2), preedit("X") -> openNewConnection()`);
  console.log(`Observed text: "${actualText}", undoStack length: ${undoCount}`);
  console.log(`Expected text: "HXllo", undoStack length: 1`);

  assert.equal(actualText, "HXllo", `Connection replacement discarded accepted text! "${actualText}" !== "HXllo"`);
  assert.equal(undoCount, 1, `Connection replacement failed to finalize composition history!`);
  console.log("[PASS] Test A");
} catch (e) {
  console.log(`[CONFIRMED FAILURE A]: ${e.message}\n`);
  failures.push({
    test: 'A',
    description: 'Connection replacement discards accepted text',
    error: e.message
  });
}

// =============================================================================
// Finding B: Connection replacement loses undo after a partial commit
// Contract Requirement: Retain latest accepted visible text and exactly one
// composition history group with exact before/after text, raw attrs, and selection.
// =============================================================================
try {
  console.log("--- [Test B] Connection replacement loses undo after a partial commit ---");
  const c = new CandidateBCoordinator("Hello", null, [1, 2]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  c.applyInput({ type: 'partialCommit', length: 1 }); // "X" committed
  c.applyInput({ type: 'preedit', text: 'Y' }); // "Y" in overlay
  
  const textBeforeConn = c.docText;
  const rConn = c.applyInput({ type: 'openNewConnection' });
  const actualText = c.docText;
  const undoCount = c.undoStack.length;
  const historyEntry = c.undoStack[0];

  console.log(`Trace: compose [1,2), preedit("X"), partialCommit(1), preedit("Y") -> openNewConnection()`);
  console.log(`Observed text: "${actualText}", undoStack length: ${undoCount}`);
  console.log(`Expected text: "${textBeforeConn}", undoStack length: 1, beforeText: "Hello", afterText: "${textBeforeConn}"`);

  assert.equal(actualText, textBeforeConn, `Visible text changed on connection replacement!`);
  assert.equal(undoCount, 1, `UndoStack empty after connection replacement with partial commit!`);
  assert.equal(historyEntry?.beforeText, "Hello");
  assert.equal(historyEntry?.afterText, textBeforeConn);
  console.log("[PASS] Test B");
} catch (e) {
  console.log(`[CONFIRMED FAILURE B]: ${e.message}\n`);
  failures.push({
    test: 'B',
    description: 'Connection replacement loses undo after a partial commit',
    error: e.message
  });
}

// =============================================================================
// Finding C: Full partial commit converts range formatting into typing intent
// Contract Requirement: Committed text still belongs to captured composition
// target. Formatting applies to committed text, surrounding text unchanged,
// and formatting undo entry is separate from composition history.
// =============================================================================
try {
  console.log("--- [Test C] Full partial commit converts range formatting into typing intent ---");
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

  const isXBold = c.docAttributes[1]?.bold === true;
  const isHBold = c.docAttributes[0]?.bold === true;
  const outcome = c.commandOutcomes.get(qRes.ticketId);
  const formattingUndo = c.undoStack.find(u => u.group === 'Formatting');

  console.log(`Trace: compose [1,2), preedit("X"), queueCommand(Bold), partialCommit(1), unmark(settled:true)`);
  console.log(`Observed: X bold=${isXBold}, H bold=${isHBold}, typingIntent.bold=${c.typingIntent.bold}, outcome:`, outcome);
  console.log(`Expected: X bold=true, H bold=false, typingIntent.bold=undefined, formattingUndo exists`);

  assert.equal(isXBold, true, `Committed unit 'X' was NOT formatted bold; remained plain!`);
  assert.equal(isHBold, false, `Surrounding character 'H' was modified!`);
  assert.equal(c.typingIntent.bold, undefined, `Command was incorrectly converted to typingIntent!`);
  assert.ok(formattingUndo, `Separate Formatting undo group was not recorded!`);
  console.log("[PASS] Test C");
} catch (e) {
  console.log(`[CONFIRMED FAILURE C]: ${e.message}\n`);
  failures.push({
    test: 'C',
    description: 'Full partial commit converts range formatting into typing intent',
    error: e.message
  });
}

// =============================================================================
// Finding D: Selection validation uses the wrong document version
// Contract Requirement: Resulting selection must be validated against proposed
// post-edit document length. Growth must accept valid post-edit selection;
// shrinkage must reject out-of-bounds post-edit selection.
// =============================================================================
try {
  console.log("--- [Test D] Selection validation uses the wrong document version ---");
  // Growth test: "a" [0,1) -> preedit("XYZ", selection:[3,3))
  const cGrowth = new CandidateBCoordinator("a", null, [0, 1]);
  const rGrowth = cGrowth.applyInput({ type: 'preedit', text: 'XYZ', selection: [3, 3] });
  console.log(`Growth trace: Initial "a" [0,1) -> preedit("XYZ", selection:[3,3)) -> status: ${rGrowth.status}, reason: ${rGrowth.reason}`);
  assert.equal(rGrowth.status, 'Accepted', `Valid growth selection [3,3] rejected as InvalidSelection against pre-edit length!`);
  assert.equal(cGrowth.docText, "XYZ");
  assert.deepEqual(cGrowth.selection, [3, 3]);

  // Shrinkage test: "a" [0,1) -> preedit("", selection:[1,1))
  const cShrink = new CandidateBCoordinator("a", null, [0, 1]);
  const rShrink = cShrink.applyInput({ type: 'preedit', text: '', selection: [1, 1] });
  console.log(`Shrinkage trace: Initial "a" [0,1) -> preedit("", selection:[1,1)) -> status: ${rShrink.status}, reason: ${rShrink.reason}`);
  assert.equal(rShrink.status, 'Rejected', `Out-of-bounds shrinkage selection [1,1] accepted on empty document!`);
  assert.equal(rShrink.reason, 'InvalidSelection');
  console.log("[PASS] Test D");
} catch (e) {
  console.log(`[CONFIRMED FAILURE D]: ${e.message}\n`);
  failures.push({
    test: 'D',
    description: 'Selection validation uses the wrong document version',
    error: e.message
  });
}

// =============================================================================
// Finding E: Unrelated commands remain stranded after cancellation
// Contract Requirement: Plan §4.5 requires unrelated commands to be mapped,
// drained, and executed after composition cancellation.
// =============================================================================
try {
  console.log("--- [Test E] Unrelated commands remain stranded after cancellation ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  const qRes = c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'TrackedRange', range: [0, 1] } // targets 'H'
  });
  const ticketId = qRes.ticketId;
  c.applyInput({ type: 'cancel' });

  const pendingCount = c.settlementObligation.pendingCommands.length;
  const outcome = c.commandOutcomes.get(ticketId);
  const isHBold = c.docAttributes[0]?.bold === true;

  console.log(`Trace: compose [1,2), preedit("X"), queueCommand(TrackedRange [0,1)), cancel()`);
  console.log(`Observed: pendingCommands count=${pendingCount}, outcome:`, outcome, `H bold=${isHBold}`);
  console.log(`Expected: pendingCommands count=0, outcome.status='Applied', H bold=true`);

  assert.equal(pendingCount, 0, `Unrelated command was left stranded in pendingCommands!`);
  assert.equal(outcome?.status, 'Applied', `Unrelated command ticket did not receive terminal Applied outcome!`);
  assert.equal(isHBold, true, `Unrelated target 'H' at [0,1) was not formatted bold!`);
  console.log("[PASS] Test E");
} catch (e) {
  console.log(`[CONFIRMED FAILURE E]: ${e.message}\n`);
  failures.push({
    test: 'E',
    description: 'Unrelated commands remain stranded after cancellation',
    error: e.message
  });
}

// =============================================================================
// Finding F: Old-epoch preedit is accepted
// Contract Requirement: Native callbacks carrying connection identity must
// validate epoch admission against active connection epoch.
// =============================================================================
try {
  console.log("--- [Test F] Old-epoch preedit is accepted ---");
  const c = new CandidateBCoordinator("Hello");
  c.applyInput({ type: 'openNewConnection' }); // epoch becomes 1
  assert.equal(c.epoch, 1);

  const staleRes = c.applyInput({ type: 'preedit', text: 'STALE', epoch: 0 });
  console.log(`Trace: openNewConnection() [epoch 1] -> preedit("STALE", epoch:0) -> status: ${staleRes.status}, reason: ${staleRes.reason}`);
  console.log(`Observed docText: "${c.docText}"`);

  assert.equal(staleRes.status, 'Rejected', `Stale epoch 0 callback was Accepted into epoch 1!`);
  assert.equal(staleRes.reason, 'StaleEpoch');
  assert.equal(c.docText, "Hello", `Document was mutated by stale epoch callback!`);
  console.log("[PASS] Test F");
} catch (e) {
  console.log(`[CONFIRMED FAILURE F]: ${e.message}\n`);
  failures.push({
    test: 'F',
    description: 'Old-epoch preedit is accepted',
    error: e.message
  });
}

console.log("================================================================================");
console.log(`=== REPRODUCTION COMPLETE: ${failures.length} / 6 CONFIRMED RUN-06 FAILURES ===`);
console.log("================================================================================");

const rawResults = {
  timestamp: new Date().toISOString(),
  target: 'validation/antigravity/run-20261014-06/candidate-b-coordinator.mjs',
  totalTested: 6,
  failuresCount: failures.length,
  allFailuresConfirmed: failures.length === 6,
  failures
};

fs.writeFileSync(
  path.join(__dirname, 'raw-reproduced-failures.json'),
  JSON.stringify(rawResults, null, 2),
  'utf-8'
);
console.log(`Raw reproduced failure results saved to: ${path.join(__dirname, 'raw-reproduced-failures.json')}`);
