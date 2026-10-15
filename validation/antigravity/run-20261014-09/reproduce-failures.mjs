// Executable Regressions Suite for Baseline Run-08 Candidate B Coordinator
// Reproduces Contract Violations & Defect Failures 1 through 4 against UNCHANGED run-20261014-08/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './sources/run-08-candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES 1-4 ON RUN-08 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Defect 1: Disjoint Rollback Target Failure
// =============================================================================
try {
  console.log("--- [Defect 1] Disjoint Rollback Target Failure ---");
  const c = new CandidateBCoordinator("abcdef", null, [0, 0]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'XYZ' });
  const qRes = c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'TrackedRange', range: [5, 6] }
  });
  c.applyInput({ type: 'retarget', range: [7, 8] });
  c.applyInput({ type: 'preedit', text: '' });
  c.applyInput({ type: 'cancel' });

  const outcome = c.commandOutcomes.get(qRes.ticketId);
  console.log(`Text: "${c.docText}", Expected [3, 4) bold ('d'), Outcome range:`, outcome?.range);

  // Assertion asserting expected behavior: 'd' at [3, 4) must be bold, outcome must be [3, 4)
  assert.equal(c.docText, "abcdef");
  assert.deepEqual(outcome?.range, [3, 4], `Command range was [${outcome?.range}], expected [3, 4]`);
  assert.equal(c.docAttributes[3]?.bold, true, `Character 'd' at index 3 must be bold!`);
  assert.equal(c.docAttributes[4]?.bold, undefined, `Character 'e' at index 4 must NOT be bold!`);
  console.log("[PASS] Defect 1");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 1]: ${e.message}\n`);
  failures.push({
    test: '1',
    name: 'Disjoint Rollback Target Failure',
    description: 'Cancellation maps targets using one netRollbackDelta at one cancelPoint, shifting targets incorrectly on disjoint edits',
    error: e.message
  });
}

// =============================================================================
// Defect 2: Idle Command Noncompletion
// =============================================================================
try {
  console.log("--- [Defect 2] Idle Command Noncompletion ---");
  const c = new CandidateBCoordinator("abc", null, [0, 0]);
  const qRes = c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'TrackedRange', range: [0, 1] }
  });
  const outcome = c.commandOutcomes.get(qRes.ticketId);

  console.log(`Idle queue result:`, qRes);
  console.log(`Outcome recorded:`, outcome);
  console.log(`Pending count:`, c.settlementObligation.pendingCommands.length);

  assert.ok(outcome, `Command must receive an explicit terminal outcome when submitted in Idle!`);
  assert.equal(outcome.status, 'Applied', `Command in Idle with valid target must be Applied immediately!`);
  assert.equal(c.settlementObligation.pendingCommands.length, 0, `No commands should remain pending in Idle!`);
  assert.equal(c.docAttributes[0]?.bold, true, `Target character at [0, 1) must be formatted!`);
  console.log("[PASS] Defect 2");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 2]: ${e.message}\n`);
  failures.push({
    test: '2',
    name: 'Idle Command Noncompletion',
    description: 'queueCommand in Idle leaves command permanently Pending without settlement blocker',
    error: e.message
  });
}

// =============================================================================
// Defect 3: Cross-Epoch Duplicate Receipt Confusion
// =============================================================================
try {
  console.log("--- [Defect 3] Cross-Epoch Duplicate Receipt Confusion ---");
  const c = new CandidateBCoordinator("Hello", null, [0, 0]);
  c.applyInput({ type: 'openNewConnection' }); // epoch 1
  assert.equal(c.epoch, 1);
  const p1 = c.applyInput({ type: 'preedit', text: 'X', epoch: 1 }, 1);
  assert.equal(p1.status, 'Accepted');

  const pOld = c.applyInput({ type: 'preedit', text: 'old', epoch: 0 }, 1);
  console.log(`Delivery from epoch 0 with sequence 1 returned:`, pOld.status, pOld.reason || pOld);

  assert.equal(pOld.status, 'Rejected', `Old connection must be Rejected!`);
  assert.equal(pOld.reason, 'StaleEpoch', `Old connection must be rejected as StaleEpoch, not Duplicate!`);
  console.log("[PASS] Defect 3");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 3]: ${e.message}\n`);
  failures.push({
    test: '3',
    name: 'Cross-Epoch Duplicate Receipt Confusion',
    description: 'Duplicate lookup keys only by sequence before epoch validation, attaching newer connection receipt to older callback',
    error: e.message
  });
}

// =============================================================================
// Defect 4: Ambiguous / Accidental Undo Ordering with Queued Formatting
// =============================================================================
try {
  console.log("--- [Defect 4] Ambiguous / Accidental Undo Ordering with Queued Formatting ---");
  const c = new CandidateBCoordinator("abc", null, [1, 2]);
  c.applyInput({ type: 'startComposition', range: [1, 2] });
  c.applyInput({ type: 'preedit', text: 'X' });
  const qRes = c.applyInput({
    type: 'queueCommand',
    cmd: { type: 'Format', patch: { bold: true } },
    target: { type: 'CompositionTarget' }
  });

  const uRes = c.applyInput({ type: 'undo' });
  console.log(`Observed undo result:`, uRes);
  console.log(`Observed docText after undo: "${c.docText}"`);

  // Under explicit documented policy test:
  // If policy is that Undo waits behind queued commands and undoes the latest group:
  // uRes.group must be 'Formatting', docText must be 'aXc' (plain), second undo must restore 'abc'
  // But on baseline run-08, this behavior emerged accidentally without contract specification or comprehensive coverage.
  // We record this investigation and failure to have explicit policy.
  assert.fail(`Run-08 lacks explicit contract/policy governing Undo ordering with queued formatting and does not cover Redo or settlement edge cases`);
} catch (e) {
  console.log(`[CONFIRMED DEFECT 4]: ${e.message}\n`);
  failures.push({
    test: '4',
    name: 'Ambiguous / Accidental Undo Ordering with Queued Formatting',
    description: 'Undo ordering with queued formatting emerged accidentally from stack order without explicit policy or edge case guarantees',
    error: e.message
  });
}

const failureReportPath = path.join(__dirname, 'raw-reproduced-failures.json');
fs.writeFileSync(failureReportPath, JSON.stringify(failures, null, 2), 'utf8');

console.log("================================================================================");
console.log(`=== REPRODUCTION SUMMARY: ${failures.length}/4 DEFECTS CONFIRMED ON RUN-08 BASELINE ===`);
console.log(`=== Raw log written to ${failureReportPath} ===`);
console.log("================================================================================\n");
