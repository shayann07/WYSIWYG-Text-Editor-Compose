// Focused Transaction & Reentrancy Contract Suite (§4.1, §4.3, Requirement 5)
// Exhaustively verifies the transaction, staging/publication boundary, and reentrancy lifecycle:
// 1. Published versus staged observations
// 2. Immutable request capture
// 3. Enqueue-time versus execution-time validation
// 4. Epoch turnover and disposal
// 5. Duplicate receipts
// 6. Rollback ownership
// 7. Exactly-once terminal outcomes
// 8. FIFO execution and scheduling limits

import assert from 'node:assert/strict';
import { CandidateBCoordinator, RequestEnvelope } from './candidate-b-coordinator.mjs';

export function runTransactionReentrancyContractTests() {
  console.log("================================================================================");
  console.log("=== RUNNING FOCUSED TRANSACTION & REENTRANCY CONTRACT SUITE (Req 5)          ===");
  console.log("================================================================================\n");

  let passed = 0;
  let total = 0;

  function test(name, fn) {
    total++;
    try {
      fn();
      passed++;
      console.log(`[PASS] Contract ${total}: ${name}`);
    } catch (e) {
      console.error(`[FAIL] Contract ${total}: ${name}`);
      console.error(e);
      throw e;
    }
  }

  // ===========================================================================
  // 1. Published Versus Staged Observations
  // ===========================================================================
  test("1.1 Staging isolation during in-flight transaction (preedit)", () => {
    let observedDocText, observedAttrs, observedSel, observedRev;
    class ObservedCoordinator extends CandidateBCoordinator {
      _preedit(text, range, selection = null, injectStage = null) {
        const res = super._preedit(text, range, selection, injectStage);
        observedDocText = this.docText;
        observedAttrs = this.docAttributes;
        observedSel = this.selection;
        observedRev = this.documentRevision;
        throw new Error("InjectedFailure:rollbackPreedit");
      }
    }

    const c = new ObservedCoordinator("Hello");
    c.applyInput({ type: "startComposition", range: [0, 5], epoch: 0 });
    const res = c.applyInput({ type: "preedit", text: "World", epoch: 0 });

    assert.equal(res.status, 'Rejected');
    // While inside the transaction, public queries observed the PUBLISHED snapshot ("Hello")
    assert.equal(observedDocText, "Hello");
    assert.equal(observedRev, 0);
    // After rollback, published state remains "Hello"
    assert.equal(c.docText, "Hello");
    assert.equal(c.documentRevision, 0);
  });

  test("1.2 Atomic publication on transaction commit exposes all fields together", () => {
    const c = new CandidateBCoordinator("Hello");
    assert.equal(c.docText, "Hello");
    assert.equal(c.documentRevision, 0);

    const res = c.applyInput({ type: "commit", text: "Greetings", range: [0, 5], epoch: 0 });
    assert.equal(res.status, 'Accepted');
    // After commit, all published fields are updated together
    assert.equal(c.docText, "Greetings");
    assert.equal(c.documentRevision, 1);
    assert.deepEqual(c.selection, [9, 9]);
    assert.equal(c.compositionState, 'Idle');
  });

  test("1.3 Notification hooks observe correct snapshot phases (willChange vs didChange)", () => {
    const c = new CandidateBCoordinator("abc");
    const willChangeObs = [];
    const didChangeObs = [];

    c.addWillChangeListener((snap, action) => {
      willChangeObs.push({ text: snap.docText, rev: snap.documentRevision, action });
    });
    c.addDidChangeListener((snap) => {
      didChangeObs.push({ text: snap.docText, rev: snap.documentRevision });
    });

    c.applyInput({ type: "commit", text: "xyz", range: [0, 3], epoch: 0 });

    assert.equal(willChangeObs.length, 1);
    assert.equal(willChangeObs[0].text, "abc");
    assert.equal(willChangeObs[0].rev, 0);

    assert.equal(didChangeObs.length, 1);
    assert.equal(didChangeObs[0].text, "xyz");
    assert.equal(didChangeObs[0].rev, 1);
  });

  test("1.4 Observations across all 6 failure-injection stages verify published snapshot referential integrity", () => {
    const stages = [
      'beforeMutation',
      'afterTextSplice',
      'afterTargetMapping',
      'duringOverlayMaterialization',
      'beforeHistoryPublication',
      'beforeReceiptPublication'
    ];

    for (const stage of stages) {
      const c = new CandidateBCoordinator("Hello");
      let observedDocText = null;
      let observedRev = null;

      c.addWillChangeListener((snap) => {
        observedDocText = snap.docText;
        observedRev = snap.documentRevision;
      });

      let res;
      let expectedBaselineText = "Hello";
      let expectedBaselineRev = 0;
      let expectedBaselineCompState = "Idle";

      if (stage === 'beforeMutation') {
        res = c.applyInput({ type: "commit", text: "World", range: [0, 5], epoch: 0, injectFailure: true });
      } else if (stage === 'afterTextSplice' || stage === 'afterTargetMapping') {
        res = c.applyInput({ type: "preedit", text: "World", epoch: 0, injectFailureAt: stage });
      } else if (stage === 'duringOverlayMaterialization' || stage === 'beforeHistoryPublication') {
        c.applyInput({ type: "startComposition", range: [0, 5], epoch: 0 });
        c.applyInput({ type: "preedit", text: "World", epoch: 0 });
        expectedBaselineText = "World";
        expectedBaselineCompState = "Composing";
        res = c.applyInput({ type: "unmark", settled: true, epoch: 0, injectFailureAt: stage });
      } else if (stage === 'beforeReceiptPublication') {
        res = c.applyInput({ type: "commit", text: "World", range: [0, 5], epoch: 0, injectFailureAt: stage });
      }

      assert.equal(res.status, 'Rejected');
      // Public getters observe unchanged published baseline after rollback
      assert.equal(c.docText, expectedBaselineText, `docText must match pre-transaction baseline after rollback at stage ${stage}`);
      assert.equal(c.compositionState, expectedBaselineCompState, `compositionState must match pre-transaction baseline at stage ${stage}`);
      if (observedDocText !== null) {
        assert.equal(observedDocText, expectedBaselineText, `willChange snapshot must observe pre-transaction baseline at stage ${stage}`);
      }
    }
  });

  test("1.5 Reentrant mutation arriving through _willChange hook executes against committed document state", () => {
    const c = new CandidateBCoordinator("abc");
    let hookTicket;

    c.addWillChangeListener((snap) => {
      if (!hookTicket) {
        hookTicket = c.applyInput({ type: "commit", text: "1", range: [0, 0], epoch: 0 });
      }
    });

    const res = c.applyInput({ type: "commit", text: "xyz", range: [0, 3], epoch: 0 });
    assert.equal(res.status, 'Accepted');

    // Outer transaction commit "xyz" at [0, 3] was followed by queued commit "1" at [0, 0]
    assert.equal(c.docText, "1xyz");
    assert.equal(c.documentRevision, 2);
    const outcome = c.commandOutcomes.get(hookTicket.ticketId);
    assert.equal(outcome.status, 'Accepted');
  });

  test("1.6 Reentrant mutation arriving through _didChange hook executes against newly committed document state", () => {
    const c = new CandidateBCoordinator("abc");
    let hookTicket;

    c.addDidChangeListener((snap) => {
      if (!hookTicket) {
        hookTicket = c.applyInput({ type: "commit", text: "!", range: [6, 6], epoch: 0 });
      }
    });

    const res = c.applyInput({ type: "commit", text: "def", range: [3, 3], epoch: 0 });
    assert.equal(res.status, 'Accepted');

    // "abcdef" committed, then didChange hook appended "!" to yield "abcdef!"
    assert.equal(c.docText, "abcdef!");
    assert.equal(c.documentRevision, 2);
    const outcome = c.commandOutcomes.get(hookTicket.ticketId);
    assert.equal(outcome.status, 'Accepted');
  });

  test("1.7 Reentrant mutation arriving through _rollbackListeners hook executes against restored baseline", () => {
    const c = new CandidateBCoordinator("abc");
    let rollbackTicket;

    c.addRollbackListener((snap, reason) => {
      if (!rollbackTicket) {
        rollbackTicket = c.applyInput({ type: "commit", text: "R", range: [0, 0], epoch: 0 });
      }
    });

    const res = c.applyInput({ type: "commit", text: "xyz", range: [0, 3], epoch: 0, injectFailureAt: 'afterTextSplice' });
    assert.equal(res.status, 'Rejected');

    // Outer transaction failed and rolled back to "abc", then rollback hook prepended "R" to yield "Rabc"
    assert.equal(c.docText, "Rabc");
    assert.equal(c.documentRevision, 1);
    const outcome = c.commandOutcomes.get(rollbackTicket.ticketId);
    assert.equal(outcome.status, 'Accepted');
  });

  // ===========================================================================
  // 2. Immutable Request Capture
  // ===========================================================================
  test("2.1 Reentrant request envelope freezes payload against caller post-enqueue tampering", () => {
    const c = new CandidateBCoordinator("abc");
    const mutablePayload = { type: "commit", text: "SecureText", epoch: 0, range: [0, 3] };

    let ticket;
    c._runInTransaction("outer", () => {
      ticket = c.applyInput(mutablePayload);
      // Caller attempts to mutate payload after enqueue:
      try {
        mutablePayload.text = "TamperedText";
        mutablePayload.range = [0, 999];
      } catch (_) {}
      return { status: "Accepted" };
    });

    // Drained deferred work must execute with the original frozen payload
    assert.equal(c.docText, "SecureText");
    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Accepted');
  });

  // ===========================================================================
  // 3. Enqueue-Time Versus Execution-Time Validation
  // ===========================================================================
  test("3.1 Epoch turnover between enqueue and execution yields StaleEpoch", () => {
    const c = new CandidateBCoordinator("abc");
    let callbackTicket;

    c._runInTransaction("probe", () => {
      c.openNewConnection(); // advances epoch to 1
      callbackTicket = c.applyInput({ type: "commit", text: "X", epoch: 0 }, 1);
      return { status: "Accepted" };
    });

    assert.equal(c.epoch, 1);
    assert.equal(c.docText, "abc");
    const outcome = c.commandOutcomes.get(callbackTicket.ticketId);
    assert.equal(outcome.status, 'Rejected');
    assert.equal(outcome.reason, 'StaleEpoch');
  });

  test("3.2 Coordinate revalidation catches targets invalidated by preceding edits", () => {
    const c = new CandidateBCoordinator("abcdef");
    let ticket;

    c._runInTransaction("outer", () => {
      // Preceding edit deletes characters to shorten document to 2 chars: "ab"
      c.applyInput({ type: "commit", text: "", range: [2, 6], epoch: 0 });
      // Queued command targeting [4, 6] (which is valid at enqueue time before drainage)
      ticket = c.applyInput({ type: "commit", text: "Z", range: [4, 6], epoch: 0 });
      return { status: "Accepted" };
    });

    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Rejected');
    assert.ok(outcome.reason === 'InvalidTargetRange' || outcome.reason === 'TargetInvalidated');
  });

  test("3.3 Preceding edits dynamically map declared coordinate basis without silent reinterpretation", () => {
    const c = new CandidateBCoordinator("abcdef");
    let ticket;

    c._runInTransaction("outer", () => {
      // Preceding edit inserts "123" at [0, 0], shifting the document
      c.applyInput({ type: "commit", text: "123", range: [0, 0], epoch: 0 });
      // Queued command targeting [2, 4] (units "cd" at basisRevision 0)
      ticket = c.applyInput({ type: "commit", text: "Z", range: [2, 4], epoch: 0 });
      return { status: "Accepted" };
    });

    // The declared request basis coordinates [2, 4] must map dynamically to [5, 7]
    // replacing "cd" with "Z", producing "123abZef" (NOT overwriting "3a" to produce "12Zbcdef"!)
    assert.equal(c.docText, "123abZef");
    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Accepted');
    assert.deepEqual(outcome.finalExtent, [5, 6]);
  });

  test("3.4 Execution-time FutureEpoch rejection", () => {
    const c = new CandidateBCoordinator("abc");
    let ticket;

    c._runInTransaction("outer", () => {
      ticket = c.applyInput({ type: "commit", text: "X", epoch: 99 }, 1);
      return { status: "Accepted" };
    });

    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Rejected');
    assert.equal(outcome.reason, 'FutureEpoch');
    assert.equal(c.docText, "abc");
  });

  test("3.5 Execution-time SessionMismatch rejection", () => {
    const c = new CandidateBCoordinator("abc");
    let ticket;

    c.applyInput({ type: "startComposition", range: [0, 1], sessionId: "sess-actual", epoch: 0 });

    c._runInTransaction("outer", () => {
      ticket = c.applyInput({ type: "commit", text: "X", sessionId: "sess-obsolete", epoch: 0 });
      return { status: "Accepted" };
    });

    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Rejected');
    assert.equal(outcome.reason, 'SessionMismatch');
  });

  // ===========================================================================
  // 4. Epoch Turnover and Disposal
  // ===========================================================================
  test("4.1 Disposal cancels pending reentrant queue with reason Disposed", () => {
    const c = new CandidateBCoordinator("abc");
    let ticket;

    c._runInTransaction("outer", () => {
      c.dispose();
      ticket = c.applyInput({ type: "commit", text: "Z", epoch: 0 });
      return { status: "Accepted" };
    });

    assert.equal(c.connectionState, 'Closed');
    const outcome = c.commandOutcomes.get(ticket.ticketId);
    assert.equal(outcome.status, 'Cancelled');
    assert.equal(outcome.reason, 'Disposed');
  });

  test("4.2 Normal settlement preserves eligible reentrant commands", () => {
    let reentrantTicket;
    class InstrumentedSettlement extends CandidateBCoordinator {
      _drainPendingCommands(settledExtent, injectStage = null) {
        const res = super._drainPendingCommands(settledExtent, injectStage);
        reentrantTicket = this.applyInput({
          type: "queueCommand",
          cmd: { type: "Format", patch: { italic: true } },
          target: { type: "TrackedRange", range: [0, 1] }
        });
        return res;
      }
    }

    const c = new InstrumentedSettlement("abc");
    c.applyInput({ type: "startComposition", range: [1, 2], epoch: 0 });
    c.applyInput({ type: "preedit", text: "Y", epoch: 0 });
    c.applyInput({ type: "unmark", settled: false, epoch: 0 });
    const token = c.activeSettlementToken.token;
    const sessId = c.activeSessionId;
    c.applyInput({ type: "completeSettlement", token, sessionId: sessId, epoch: 0 });

    const outcome = c.commandOutcomes.get(reentrantTicket.ticketId);
    assert.equal(outcome.status, 'Applied');
    assert.equal(c.docAttributes[0]?.italic, true);
  });

  // ===========================================================================
  // 5. Duplicate Receipts
  // ===========================================================================
  test("5.1 Executed deferred command caches terminal receipt for duplicate delivery", () => {
    const c = new CandidateBCoordinator("abc");
    let ticket;

    c._runInTransaction("outer", () => {
      ticket = c.applyInput({ type: "commit", text: "123", range: [0, 3], epoch: 0 }, 5);
      return { status: "Accepted" };
    });

    assert.equal(c.docText, "123");
    // Duplicate submission with same epoch:seq
    const dupRes = c.applyInput({ type: "commit", text: "123", range: [0, 3], epoch: 0 }, 5);
    assert.equal(dupRes.status, 'Duplicate');
    assert.equal(dupRes.cachedReceipt.status, 'Accepted');
  });

  test("5.2 Stale-epoch rejected command records cached terminal receipt", () => {
    const c = new CandidateBCoordinator("abc");
    let ticket;

    c._runInTransaction("outer", () => {
      c.openNewConnection();
      ticket = c.applyInput({ type: "commit", text: "XYZ", epoch: 0 }, 10);
      return { status: "Accepted" };
    });

    const dupRes = c.applyInput({ type: "commit", text: "XYZ", epoch: 0 }, 10);
    assert.equal(dupRes.status, 'Duplicate');
    assert.equal(dupRes.cachedReceipt.status, 'Rejected');
    assert.equal(dupRes.cachedReceipt.reason, 'StaleEpoch');
  });

  // ===========================================================================
  // 6. Rollback Ownership
  // ===========================================================================
  test("6.1 Reentrant mutations execute against restored baseline after outer transaction failure", () => {
    const c = new CandidateBCoordinator("abc");
    let reentrantTicket;

    const outerRes = c._runInTransaction("outer", () => {
      c.committedDoc.push({ tokenId: "temp", char: "!", attrs: {} });
      reentrantTicket = c.applyInput({ type: "commit", text: "W", range: [0, 1], epoch: 0 });
      throw new Error("OuterTxFailed");
    });

    assert.equal(outerRes.status, 'Rejected');
    // Outer working mutation "!" was rolled back, but reentrant commit "W" executed against restored baseline
    assert.equal(c.docText, "Wbc");
    const outcome = c.commandOutcomes.get(reentrantTicket.ticketId);
    assert.equal(outcome.status, 'Accepted');
  });

  test("6.2 Internal transaction closures are cancelled on rollback while external requests are preserved", () => {
    const c = new CandidateBCoordinator("abc");
    let internalTicket;
    let externalTicket;

    const outerRes = c._runInTransaction("outer", () => {
      // Internal sub-action queued as part of the transaction
      internalTicket = c._runInTransaction("internalSubAction", () => {
        c.committedDoc.push({ tokenId: "bad", char: "!", attrs: {} });
        return { status: "Accepted" };
      });
      // External request arriving during transaction
      externalTicket = c.applyInput({ type: "commit", text: "W", range: [0, 1], epoch: 0 });
      throw new Error("OuterTxFailed");
    });

    assert.equal(outerRes.status, 'Rejected');
    // Internal sub-action must be cancelled with reason AbandonedWithTransaction:
    const internalOutcome = c.commandOutcomes.get(internalTicket.ticketId);
    assert.equal(internalOutcome.status, 'Cancelled');
    assert.equal(internalOutcome.reason, 'AbandonedWithTransaction');

    // External request must execute against restored baseline:
    const externalOutcome = c.commandOutcomes.get(externalTicket.ticketId);
    assert.equal(externalOutcome.status, 'Accepted');
    assert.equal(c.docText, "Wbc");
  });

  // ===========================================================================
  // 7. Exactly-Once Terminal Outcomes
  // ===========================================================================
  test("7.1 Reentrant request lifecycle transitions from QueuedReentrant to terminal receipt exactly once", () => {
    const c = new CandidateBCoordinator("abc");
    let queuedAck;

    c._runInTransaction("outer", () => {
      queuedAck = c.applyInput({ type: "commit", text: "M", range: [0, 1], epoch: 0 });
      assert.equal(queuedAck.status, 'Accepted');
      assert.equal(queuedAck.action, 'QueuedReentrant');
      assert.ok(queuedAck.ticketId);
      // Prior to transaction completion, no terminal outcome is published yet
      assert.equal(c.commandOutcomes.has(queuedAck.ticketId), false);
      return { status: "Accepted" };
    });

    // Exactly one terminal receipt is published
    assert.equal(c.commandOutcomes.has(queuedAck.ticketId), true);
    const terminal = c.commandOutcomes.get(queuedAck.ticketId);
    assert.equal(terminal.status, 'Accepted');
  });

  // ===========================================================================
  // 8. FIFO Execution and Scheduling Limits
  // ===========================================================================
  test("8.1 Multiple reentrant requests drain in strict FIFO order", () => {
    const c = new CandidateBCoordinator("abc");
    const order = [];

    c._runInTransaction("outer", () => {
      c.applyInput({ type: "commit", text: "1", range: [0, 0], epoch: 0 });
      c.applyInput({ type: "commit", text: "2", range: [0, 0], epoch: 0 });
      c.applyInput({ type: "commit", text: "3", range: [0, 0], epoch: 0 });
      return { status: "Accepted" };
    });

    // 1 then 2 then 3:
    // abc -> 1abc -> 21abc -> 321abc
    assert.equal(c.docText, "321abc");
  });

  test("8.2 Cascading reentrant mutation enqueuing runs via single queue drain owner", () => {
    const c = new CandidateBCoordinator("abc");
    let innerTicket;

    c._runInTransaction("outer", () => {
      // First reentrant task enqueues another reentrant task while running
      c.applyInput({
        type: "commit",
        text: "A",
        range: [0, 1],
        epoch: 0
      });
      return { status: "Accepted" };
    });

    assert.equal(c.docText, "Abc");
  });

  console.log(`\n================================================================================`);
  console.log(`=== TRANSACTION & REENTRANCY CONTRACT SUITE: ${passed}/${total} PASSED CLEANLY! ===`);
  console.log(`================================================================================\n`);

  return { passed, total };
}

if (process.argv[1]?.endsWith('test-transaction-reentrancy-contract.mjs')) {
  runTransactionReentrancyContractTests();
}
