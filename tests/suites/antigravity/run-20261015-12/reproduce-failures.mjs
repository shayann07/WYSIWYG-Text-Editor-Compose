// Executable Regressions Suite for Baseline Run-11 Candidate B Coordinator
// Reproduces Contract Violations & Probes 1 through 4 against UNCHANGED run-20261014-11/candidate-b-coordinator.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './sources/run-11-candidate-b-coordinator.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("================================================================================");
console.log("=== EXECUTABLE REGRESSION SUITE: REPRODUCING FAILURES 1-4 ON RUN-11 BASELINE ===");
console.log("================================================================================\n");

const failures = [];

// =============================================================================
// Failure 1: Deferred callbacks bypass connection-epoch validation
// =============================================================================
try {
  console.log("--- [Failure 1] Deferred Callbacks Bypass Connection-Epoch Validation ---");
  const c = new CandidateBCoordinator("abc");
  let callbackTicket;

  c._runInTransaction("probe", () => {
    c.openNewConnection();
    callbackTicket = c.applyInput(
      { type: "commit", text: "X", epoch: 0 },
      1
    );
    return { status: "Accepted" };
  });

  const outcome = c.commandOutcomes.get(callbackTicket.ticketId);
  console.log(`Document text after drainage: "${c.docText}"`);
  console.log(`Epoch after turnover: ${c.epoch}`);
  console.log(`Callback ticket outcome:`, outcome);

  // Expected behavior:
  // - The old callback (epoch 0) MUST NOT mutate the new connection (epoch 1)!
  // - Document text must remain "abc"
  // - Ticket outcome must report an explicit stale-epoch terminal outcome
  assert.equal(c.docText, "abc", "Old epoch-0 callback must not mutate epoch-1 connection!");
  assert.equal(outcome?.status, 'Rejected', "Ticket outcome must report Rejected for stale epoch!");
  assert.equal(outcome?.reason, 'StaleEpoch', "Rejection reason must be StaleEpoch!");
  console.log("[PASS] Failure 1");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 1]: ${e.message}\n`);
  failures.push({
    test: '1',
    name: 'Deferred callbacks bypass connection-epoch validation',
    description: 'applyInput validates admission before queueing, but the queue stores a closure that executes the already-resolved mutation without repeating admission checks against the execution state, allowing an epoch-0 callback to execute against epoch-1 connection',
    error: e.message
  });
}

// =============================================================================
// Failure 2: Public reads expose unpublished state
// =============================================================================
try {
  console.log("--- [Failure 2] Public Reads Expose Unpublished State ---");
  let observedInsideDocText = null;
  let observedInsideSelection = null;
  let observedInsideRevision = null;

  class InstrumentedCandidateB extends CandidateBCoordinator {
    _preedit(text, range, selection = null, injectStage = null) {
      const res = super._preedit(text, range, selection, injectStage);
      // Synchronous probe reading public properties while inside the transaction
      observedInsideDocText = this.docText;
      observedInsideSelection = this.selection ? [...this.selection] : null;
      observedInsideRevision = this.documentRevision;
      throw new Error("InjectedFailure:probeExposeUnpublishedState");
    }
  }

  const c = new InstrumentedCandidateB("abc");
  c.applyInput({ type: "startComposition", range: [1, 2], epoch: 0 });
  c.applyInput({ type: "preedit", text: "X", epoch: 0 });

  console.log(`Observed inside transaction: docText="${observedInsideDocText}", selection=${JSON.stringify(observedInsideSelection)}, revision=${observedInsideRevision}`);
  console.log(`Observed after transaction rollback: docText="${c.docText}"`);

  // Expected behavior:
  // External synchronous reads during an active transaction must observe the CURRENTLY PUBLISHED snapshot,
  // NOT the intermediate/staged working state!
  assert.equal(
    observedInsideDocText,
    "abc",
    `Public docText query during transaction must observe published snapshot "abc", not staged "${observedInsideDocText}"!`
  );
  console.log("[PASS] Failure 2");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 2]: ${e.message}\n`);
  failures.push({
    test: '2',
    name: 'Public reads expose unpublished state',
    description: 'Public getters (docText, selection, documentRevision) query in-progress working state directly rather than maintaining a coherent published snapshot boundary, exposing intermediate state before rollback',
    error: e.message
  });
}

// =============================================================================
// Failure 3: Ordinary settlement incorrectly cancels reentrant commands as Disposed
// =============================================================================
try {
  console.log("--- [Failure 3] Ordinary Settlement Incorrectly Cancels Reentrant Commands as Disposed ---");
  let reentrantBoldTicket = null;

  class InstrumentedSettlementCandidateB extends CandidateBCoordinator {
    _drainPendingCommands(settledExtent, injectStage = null) {
      const res = super._drainPendingCommands(settledExtent, injectStage);
      // Reentrantly dispatch Bold for TrackedRange([0, 1))
      reentrantBoldTicket = this.applyInput({
        type: "queueCommand",
        cmd: { type: "Format", patch: { bold: true } },
        target: { type: "TrackedRange", range: [0, 1] }
      });
      return res;
    }
  }

  const c = new InstrumentedSettlementCandidateB("abc");
  c.applyInput({ type: "startComposition", range: [1, 2], epoch: 0 });
  c.applyInput({ type: "preedit", text: "X", epoch: 0 });
  c.applyInput({ type: "unmark", settled: false, epoch: 0 });
  const token = c.activeSettlementToken ? c.activeSettlementToken.token : null;
  const sessId = c.activeSessionId;
  const settleRes = c.applyInput({ type: "completeSettlement", token, sessionId: sessId, epoch: 0 });

  const outcome = c.commandOutcomes.get(reentrantBoldTicket?.ticketId);
  console.log(`Settlement result:`, settleRes);
  console.log(`Connection state: ${c.connectionState}`);
  console.log(`Reentrant bold ticket outcome:`, outcome);
  console.log(`Character 'a' attributes:`, c.docAttributes[0]);

  // Expected behavior:
  // Normal settlement must preserve and execute eligible reentrant requests.
  // Connection is Active, so ticket must NOT be Cancelled with Disposed!
  // Character 'a' at index 0 must be formatted bold.
  assert.notEqual(outcome?.status, 'Cancelled', "Normal settlement must not cancel reentrant commands as Disposed!");
  assert.equal(c.docAttributes[0]?.bold, true, "Character 'a' must be bold after reentrant command execution!");
  console.log("[PASS] Failure 3");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 3]: ${e.message}\n`);
  failures.push({
    test: '3',
    name: 'Ordinary settlement incorrectly cancels reentrant commands as Disposed',
    description: '_completeSettlement explicitly clears _reentrantMutationQueue and labels tickets Disposed even when connection remains Active, discarding eligible reentrant commands',
    error: e.message
  });
}

// =============================================================================
// Failure 4: Identity-Index Specification & Memory Accounting Discrepancies
// =============================================================================
try {
  console.log("--- [Failure 4] Identity-Index Specification & Memory Accounting Discrepancies ---");
  // Piece scalar fields: bufferId (4), startOffset (4), length (4), baseTokenId (8), runLength (4) = 24 bytes
  const scalarBytes = 4 + 4 + 4 + 8 + 4;
  assert.equal(scalarBytes, 24, "Piece scalar fields must sum to 24 bytes");

  // In 64-bit JVM with compressed OOPs:
  // Object header: 12 bytes
  // Fields: 24 bytes
  // Alignment: 8-byte boundary -> 40 bytes minimum per Piece instance on heap.
  const jvmHeaderBytes = 12;
  const rawPieceBytes = jvmHeaderBytes + scalarBytes;
  const alignedPieceBytes = Math.ceil(rawPieceBytes / 8) * 8;
  assert.equal(alignedPieceBytes, 40, "Piece heap object must be at least 40 bytes on 64-bit JVM");

  // For 1,000,000 pieces:
  // Just the Piece objects alone require: 1,000,000 * 40 bytes = 40,000,000 bytes (~38.15 MiB).
  // This directly disproves the claimed ~16 MiB total for 1,000,000 pieces!
  const pieceObjectsBytes = 1000000 * alignedPieceBytes;
  const claimedTotalBytes = 16 * 1024 * 1024; // 16 MiB

  console.log(`Calculated Piece scalar bytes: ${scalarBytes}`);
  console.log(`Calculated minimum Piece object bytes on JVM: ${alignedPieceBytes}`);
  console.log(`Calculated 1M Piece objects footprint: ${(pieceObjectsBytes / (1024*1024)).toFixed(2)} MiB vs claimed ~16 MiB total`);

  // Asserting that the claimed 16 MiB can support 1M pieces with tree nodes and secondary index fails:
  assert.ok(
    pieceObjectsBytes < claimedTotalBytes,
    `Claimed ~16 MiB total for 1M pieces is unsupported: Piece objects alone require ${(pieceObjectsBytes / (1024*1024)).toFixed(2)} MiB before tree nodes or secondary TokenIndex!`
  );
  console.log("[PASS] Failure 4");
} catch (e) {
  console.log(`[CONFIRMED FAILURE 4]: ${e.message}\n`);
  failures.push({
    test: '4',
    name: 'Identity-index specification and memory accounting discrepancies',
    description: 'The claimed ~16 MiB total for 1M pieces is mathematically unsupported: Piece contains 24 bytes of scalar fields and consumes >= 40 bytes per object on 64-bit JVM (~38.15 MiB for objects alone before tree nodes, array references, or secondary TokenIndex), and conflates the 64 MiB history budget with total engine heap',
    error: e.message
  });
}

// Write out raw-reproduced-failures.json
const outPath = path.join(__dirname, 'raw-reproduced-failures.json');
fs.writeFileSync(outPath, JSON.stringify(failures, null, 2), 'utf-8');
console.log(`================================================================================`);
console.log(`=== REPRODUCTION COMPLETE: ${failures.length}/4 FAILURES CONFIRMED AGAINST RUN-11 ===`);
console.log(`================================================================================`);
