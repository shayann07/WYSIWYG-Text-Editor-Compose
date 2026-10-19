// Executable Regressions Suite for Baseline Run-09 Candidate B Coordinator
// Reproduces Contract Violations & Defect Failures 1 through 3 against UNCHANGED run-20261014-09/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './sources/run-09-candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES 1-3 ON RUN-09 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Defect 1: Rollback mapping silently expands a target over restored text
// =============================================================================
try {
  console.log("--- [Defect 1] Rollback Target Silently Expands Over Restored Text ---");
  const c = new CandidateBCoordinator("abcde");
  c.applyInput({ type: "startComposition", range: [2, 3] }); // Selects "c"
  c.applyInput({ type: "preedit", text: "" }); // Document becomes "abde"
  const q = c.applyInput({
    type: "queueCommand",
    cmd: { type: "Format", patch: { bold: true } },
    target: { type: "TrackedRange", range: [1, 3] } // Targets visible "bd"
  });
  c.applyInput({ type: "cancel" }); // Restores "c" to yield "abcde"

  const outcome = c.commandOutcomes.get(q.ticketId);
  console.log(`Document text: "${c.docText}"`);
  console.log(`Command outcome:`, outcome);
  console.log(`Attributes:`, c.docAttributes.map((a, i) => `${c.docText[i]}:${a.bold ? 'bold' : 'plain'}`).join(', '));

  // The assertion defining correct behavior: 'c' at index 2 must NOT be bold!
  // And outcome range must NOT be [1, 4] covering the restored intervening text.
  assert.equal(c.docText, "abcde");
  assert.equal(c.docAttributes[2]?.bold, undefined, "Restored character 'c' at index 2 must NOT be bold!");
  assert.notDeepEqual(outcome?.range, [1, 4], "Outcome range must not silently expand to [1, 4]!");
  console.log("[PASS] Defect 1");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 1]: ${e.message}\n`);
  failures.push({
    test: '1',
    name: 'Rollback mapping silently expands a target over restored text',
    description: 'mapTrackedRangeOnCancel returns min-to-max interval over surviving tokens, silently including restored intervening text',
    error: e.message
  });
}

// =============================================================================
// Defect 2: Advertised public dispatcher bypasses transaction accounting
// =============================================================================
try {
  console.log("--- [Defect 2] Advertised Public Dispatcher Bypasses Transaction Accounting ---");
  const c = new CandidateBCoordinator("abc");
  const res = c.dispatchCommand(
    { type: "Format", patch: { bold: true } },
    { type: "TrackedRange", range: [0, 1] }
  );
  console.log(`Dispatch result:`, res);
  console.log(`Document text: "${c.docText}", attrs[0]:`, c.docAttributes[0]);
  console.log(`documentRevision: ${c.documentRevision}, expected: 1`);

  assert.equal(c.docAttributes[0]?.bold, true, "Character 'a' must be bold!");
  assert.equal(c.documentRevision, 1, "documentRevision must advance from 0 to 1 on direct dispatchCommand formatting!");
  console.log("[PASS] Defect 2");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 2]: ${e.message}\n`);
  failures.push({
    test: '2',
    name: 'Advertised public dispatcher bypasses transaction accounting',
    description: 'Direct call to dispatchCommand mutates document and creates history without entering transaction owner or advancing documentRevision',
    error: e.message
  });
}

// =============================================================================
// Defect 3: Undo ordering specified by bypassing the settlement barrier
// =============================================================================
try {
  console.log("--- [Defect 3] Undo Ordering Bypasses Settlement Barrier ---");
  const c = new CandidateBCoordinator("abc");
  c.applyInput({ type: "startComposition", range: [1, 2] });
  c.applyInput({ type: "preedit", text: "X" });
  c.applyInput({
    type: "queueCommand",
    cmd: { type: "Format", patch: { bold: true } },
    target: { type: "CompositionTarget" }
  });
  c.undo(); // Called while active composition is in progress without native completion

  console.log(`Coordinator state after undo: ${c.compositionState}`);
  console.log(`activeFence:`, c.settlementObligation.activeFence);
  console.log(`settlementEvidence:`, c.settlementEvidence);

  // In run-09 baseline, coordinator unilaterally becomes Idle without settlement evidence
  assert.notEqual(
    c.compositionState,
    'Idle',
    "Coordinator must NOT unilaterally transition to Idle on application undo without native completion!"
  );

  const resCommit = c.applyInput({ type: "commit", text: "Y" });
  console.log(`Commit result:`, resCommit.status, `DocText: "${c.docText}"`);

  // In run-09, commit("Y") produced "aXYc" as a fresh insertion because state was Idle
  assert.notEqual(
    c.docText,
    "aXYc",
    "Subsequent commit must not produce aXYc via uncoordinated state turnover!"
  );

  console.log("[PASS] Defect 3");
} catch (e) {
  console.log(`[CONFIRMED DEFECT 3]: ${e.message}\n`);
  failures.push({
    test: '3',
    name: 'Undo ordering specified by bypassing the settlement barrier',
    description: 'undo() flushes overlay and transitions to Idle while activeFence and settlementEvidence are null, allowing desynchronized subsequent commits',
    error: e.message
  });
}

// Write out JSON artifact
const outPath = path.join(__dirname, 'raw-reproduced-failures.json');
fs.writeFileSync(outPath, JSON.stringify(failures, null, 2), 'utf-8');
console.log(`================================================================================`);
console.log(`=== REPRODUCTION COMPLETE: ${failures.length}/3 DEFECTS CONFIRMED AGAINST RUN-09 ===`);
console.log(`================================================================================`);
