// Executable Regressions Suite for Baseline Run-10 Candidate B Coordinator
// Reproduces Contract Violations & Failures 1 through 4 against UNCHANGED run-20261014-10/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './sources/run-10-candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES 1-4 ON RUN-10 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Failure 1: Settlement erases the Redo entry created by queued Undo
// =============================================================================
try {
  console.log("--- [Failure 1] Settlement Erases Redo Entry Created by Queued Undo ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: "startComposition", range: [1, 2] });
  c.applyInput({ type: "preedit", text: "X" });
  const uTick = c.undo(); // queued behind settlement
  const cRes = c.applyInput({ type: "commit", text: "Y" });

  console.log(`Document text after commit: "${c.docText}"`);
  console.log(`Undo ticket outcome:`, c.commandOutcomes.get(uTick.ticketId));
  console.log(`undoStack length: ${c.undoStack.length}, redoStack length: ${c.redoStack.length}`);

  const redoRes = c.redo();
  console.log(`redo() outcome:`, redoRes);

  // Correct behavior expected:
  // - Document became "abc" (undo executed)
  // - redoStack must NOT be empty!
  // - redo() must restore "aYc"
  assert.equal(c.docText, "abc");
  assert.equal(c.commandOutcomes.get(uTick.ticketId)?.status, 'Applied');
  assert.ok(c.redoStack.length > 0, "redoStack must retain the Redo entry created by queued Undo!");
  assert.equal(redoRes.status, 'Accepted', "redo() must be Accepted, not Rejected(NothingToRedo)!");
  assert.equal(c.docText, "aYc", "Redo must restore 'aYc'!");
  console.log("[PASS] Failure 1");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 1]: ${e.message}\n`);
  failures.push({
    test: '1',
    name: 'Settlement erases the Redo entry created by queued Undo',
    description: '_runInTransaction clears redoStack based on the outer action name and net document change, erasing valid Redo branches created by queued Undos inside the transaction',
    error: e.message
  });
}

// =============================================================================
// Failure 2: Queued commands lose surviving targets after an earlier queued Undo
// =============================================================================
try {
  console.log("--- [Failure 2] Queued Commands Lose Surviving Targets After Queued Undo ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: "startComposition", range: [1, 2] });
  c.applyInput({ type: "preedit", text: "XYZ" }); // "aXYZc"
  const uTick = c.undo(); // Queued undo
  const qBold = c.applyInput({
    type: "queueCommand",
    cmd: { type: "Format", patch: { bold: true } },
    target: { type: "TrackedRange", range: [4, 5] } // Original "c"
  });
  c.applyInput({ type: "commit", text: "XYZ" });

  const boldOutcome = c.commandOutcomes.get(qBold.ticketId);
  console.log(`Document text after settlement & drainage: "${c.docText}"`);
  console.log(`Bold command outcome:`, boldOutcome);
  console.log(`Attributes:`, c.docAttributes.map((a, i) => `${c.docText[i]}:${a.bold ? 'bold' : 'plain'}`).join(', '));

  // Correct behavior expected:
  // - Undo restores "abc"
  // - Original 'c' survived, moving from index 4 to index 2
  // - Target should map to [2, 3), making only 'c' bold
  assert.equal(c.docText, "abc");
  assert.notEqual(boldOutcome?.status, 'Rejected', "Bold command must NOT be rejected with TargetInvalidated!");
  assert.equal(c.docAttributes[2]?.bold, true, "Character 'c' at index 2 must be bold!");
  assert.equal(c.docAttributes[0]?.bold, undefined, "Character 'a' must remain plain!");
  assert.equal(c.docAttributes[1]?.bold, undefined, "Character 'b' must remain plain!");
  console.log("[PASS] Failure 2");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 2]: ${e.message}\n`);
  failures.push({
    test: '2',
    name: 'Queued commands lose surviving targets after an earlier queued Undo',
    description: 'Command drainage does not transform remaining queued targets across earlier queued Undos using token identities, causing surviving targets to be invalidated by stale coordinates',
    error: e.message
  });
}

// =============================================================================
// Failure 3: The synchronous settlement exception accepts invalid evidence
// =============================================================================
try {
  console.log("--- [Failure 3] Synchronous Settlement Exception Accepts Invalid Evidence ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: "startComposition", range: [1, 2] });
  c.applyInput({ type: "preedit", text: "X" });

  // Present invalid settlement evidence (epoch 999, wrong sessionId, unqualified caller)
  const res = c.undo(true, { extent: [1, 2], epoch: 999, sessionId: "wrong" });
  console.log(`undo(true, invalidEvidence) result:`, res);
  console.log(`Coordinator state: ${c.compositionState}, docText: "${c.docText}"`);

  // Correct behavior expected:
  // Invalid settlement evidence MUST be rejected! Composition must NOT be synchronously settled or undone!
  assert.notEqual(res.status, 'Accepted', "undo with invalid settlement evidence must NOT be accepted!");
  assert.notEqual(c.compositionState, 'Idle', "Coordinator must NOT transition to Idle on invalid settlement evidence!");
  console.log("[PASS] Failure 3");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 3]: ${e.message}\n`);
  failures.push({
    test: '3',
    name: 'The synchronous settlement exception accepts invalid evidence',
    description: 'Implementation checked only truthiness of syncBoundary and extent, accepting invalid epochs, wrong sessions, and unqualified callers',
    error: e.message
  });
}

// =============================================================================
// Failure 4: Resolve reentrancy policy drift
// =============================================================================
try {
  console.log("--- [Failure 4] Resolve Reentrancy Policy Drift ---");
  const c = new CandidateBCoordinator("abc");
  let reentrantResult = null;
  c._runInTransaction('outer', () => {
    // External reentrant mutation attempted during outer transaction
    reentrantResult = c.applyInput({ type: "commit", text: "Z" });
  });

  console.log(`Reentrant mutation result:`, reentrantResult);

  // Under Section 4.1's normative contract, reentrant mutations must queue FIFO after current transaction,
  // NOT reject with ReentrantMutation.
  assert.notEqual(
    reentrantResult?.reason,
    'ReentrantMutation',
    "Reentrant mutations must queue FIFO after active transaction completes, not reject with ReentrantMutation!"
  );
  console.log("[PASS] Failure 4");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 4]: ${e.message}\n`);
  failures.push({
    test: '4',
    name: 'Resolve reentrancy policy drift',
    description: 'Transaction wrapper rejects reentrant mutations with ReentrantMutation instead of queueing them in FIFO order to process after the active transaction completes as specified in Section 4.1',
    error: e.message
  });
}

// Write out JSON artifact
const outPath = path.join(__dirname, 'raw-reproduced-failures.json');
fs.writeFileSync(outPath, JSON.stringify(failures, null, 2), 'utf-8');
console.log(`================================================================================`);
console.log(`=== REPRODUCTION COMPLETE: ${failures.length}/4 FAILURES CONFIRMED AGAINST RUN-10 ===`);
console.log(`================================================================================`);
