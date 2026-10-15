// Settlement and Lifecycle Adversarial Validation Suite (Gap 4.7, §4.4, §4.5, Findings A & B)
// Tests:
// - Nonterminal unmark followed by more preedit
// - New target or partial commit after a settlement request
// - Two requests within one session
// - Duplicate, stale, and wrong-identity completion
// - Completion while a marked candidate remains active
// - Connection replacement preserving accepted visible text and undo history (Composing & AwaitingSettlement, with/without partial commits)
// - Disposal closing connection and preserving committed history
// - Focus transitions according to declared cancel/settle policies
// - Commands queued before and after a request

import assert from 'node:assert/strict';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';
import { IndependentOracle } from './independent-oracle.mjs';

export function runSettlementLifecycleTests() {
  console.log('=== Running Settlement and Lifecycle Validation Suite ===');
  let passed = 0;
  let failed = 0;

  function test(name, fn) {
    try {
      fn();
      passed++;
      console.log(`[PASS] ${name}`);
    } catch (e) {
      failed++;
      console.log(`[FAIL] ${name} -> ${e.message}`);
      throw e;
    }
  }

  // 1. Nonterminal unmark followed by preedit resumption
  test('Nonterminal unmark followed by preedit resumption invalidates fence', () => {
    const c = new CandidateBCoordinator('Hello world');
    c.applyInput({ type: 'startComposition', range: [1, 5] }); // "ello"
    c.applyInput({ type: 'preedit', text: 'i' }); // "Hi world"
    const r1 = c.applyInput({ type: 'unmark', settled: false });
    assert.equal(r1.status, 'Accepted');
    assert.equal(r1.settled, false);
    assert.ok(r1.token);
    assert.equal(c.compositionState, 'AwaitingSettlement');

    // New preedit arrives
    const r2 = c.applyInput({ type: 'preedit', text: 'ey' });
    assert.equal(r2.status, 'Accepted');
    assert.equal(c.compositionState, 'Composing');
    assert.equal(c.activeSettlementToken, null);

    // Old token should be rejected
    const r3 = c.applyInput({ type: 'completeSettlement', token: r1.token });
    assert.equal(r3.status, 'Rejected');
    assert.equal(r3.reason, 'CandidateStillActive');
  });

  // 2. Reject settlement completion while candidate active
  test('Reject completeSettlement while candidate active', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'XY' });
    const res = c.applyInput({ type: 'completeSettlement' });
    assert.equal(res.status, 'Rejected');
    assert.equal(res.reason, 'CandidateStillActive');
  });

  // 3. Stale token rejection after multiple unmarks
  test('Stale token rejected after second unmark generates new fence', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'A' });
    const u1 = c.applyInput({ type: 'unmark', settled: false });
    const tok1 = u1.token;

    // Resumed edit
    c.applyInput({ type: 'preedit', text: 'AB' });
    const u2 = c.applyInput({ type: 'unmark', settled: false });
    const tok2 = u2.token;
    assert.notEqual(tok1, tok2);

    // Attempt completion with tok1
    const rStale = c.applyInput({ type: 'completeSettlement', token: tok1 });
    assert.equal(rStale.status, 'Rejected');
    assert.equal(rStale.reason, 'ObsoleteSettlementToken');

    // Valid completion with tok2
    const rValid = c.applyInput({ type: 'completeSettlement', token: tok2 });
    assert.equal(rValid.status, 'Accepted');
    assert.equal(c.compositionState, 'Idle');
  });

  // 4. Session ID mismatch rejection
  test('Session mismatch rejected on completeSettlement', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2], sessionId: 'sess-alpha' });
    c.applyInput({ type: 'preedit', text: 'Z' });
    const u = c.applyInput({ type: 'unmark', settled: false });
    
    const rMismatch = c.applyInput({ type: 'completeSettlement', token: u.token, sessionId: 'sess-beta' });
    assert.equal(rMismatch.status, 'Rejected');
    assert.equal(rMismatch.reason, 'SessionMismatch');
  });

  // 5. Stale epoch rejection on completeSettlement
  test('Stale epoch rejected on completeSettlement', () => {
    const c = new CandidateBCoordinator('Hello');
    c.epoch = 2;
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'Z' });
    const u = c.applyInput({ type: 'unmark', settled: false });

    const rStaleEpoch = c.applyInput({ type: 'completeSettlement', token: u.token, epoch: 1 });
    assert.equal(rStaleEpoch.status, 'Rejected');
    assert.equal(rStaleEpoch.reason, 'StaleEpoch');
  });

  // 6. FIFO queue execution on settlement
  test('FIFO commands drain against concrete extent and record separate formatting undo group', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { italic: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    const u = c.applyInput({ type: 'unmark', settled: false });
    c.applyInput({ type: 'completeSettlement', token: u.token });

    assert.equal(c.docText, 'HXYZlo');
    const attrs = c.docAttributes;
    for (let i = 1; i < 4; i++) {
      assert.ok(attrs[i].bold, `Index ${i} should be bold`);
      assert.ok(attrs[i].italic, `Index ${i} should be italic`);
    }
    assert.equal(c.undoStack.length, 3);
    assert.equal(c.undoStack[0].group, 'Composition');
    assert.equal(c.undoStack[1].group, 'Formatting');
    assert.equal(c.undoStack[2].group, 'Formatting');
  });

  // 7. Duplicate unmark in AwaitingSettlement rejected as AlreadyUnmarked
  test('Duplicate unmark rejected as AlreadyUnmarked without state drift', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'X' });
    c.applyInput({ type: 'unmark', settled: false });
    
    const rDup = c.applyInput({ type: 'unmark', settled: false });
    assert.equal(rDup.status, 'Rejected');
    assert.equal(rDup.reason, 'AlreadyUnmarked');
  });

  // 8. Omitted completion token during active fence rejected
  test('Omitted completion token during active fence rejected as ObsoleteSettlementToken', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'Z' });
    c.applyInput({ type: 'unmark', settled: false });
    const rNoTok = c.applyInput({ type: 'completeSettlement' });
    assert.equal(rNoTok.status, 'Rejected');
    assert.equal(rNoTok.reason, 'ObsoleteSettlementToken');
  });

  // 9. Calling completeSettlement while Idle is rejected
  test('Calling completeSettlement while Idle rejected as NoActiveSettlement', () => {
    const c = new CandidateBCoordinator('Hello');
    const rIdle = c.applyInput({ type: 'completeSettlement' });
    assert.equal(rIdle.status, 'Rejected');
    assert.equal(rIdle.reason, 'NoActiveSettlement');
  });

  // 10. Focus loss with cancel strategy discards uncommitted composition
  test('Focus loss with cancel strategy cleanly rolls back composition', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    const rFocus = c.applyInput({ type: 'onFocusLost', strategy: 'cancel' });
    assert.equal(rFocus.status, 'Accepted');
    assert.equal(rFocus.action, 'Cancelled');
    assert.equal(c.compositionState, 'Idle');
    assert.equal(c.docText, 'Hello');
  });

  // 11. Focus loss with settle strategy commits composition
  test('Focus loss with settle strategy settles composition', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    const rFocus = c.applyInput({ type: 'onFocusLost', strategy: 'settle' });
    assert.equal(rFocus.status, 'Accepted');
    assert.equal(rFocus.action, 'Settled');
    assert.equal(c.compositionState, 'Idle');
    assert.equal(c.docText, 'HXYZlo');
  });

  // 12. Repaired: openNewConnection preserves accepted visible text and finalizes history (Finding A)
  test('openNewConnection preserves accepted visible text and records history during Composing', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    const q = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    
    // Connection turnover occurs
    const rConn = c.applyInput({ type: 'openNewConnection' });
    assert.equal(rConn.status, 'Accepted');
    assert.equal(c.epoch, 1);
    assert.equal(c.compositionState, 'Idle');
    // Normative requirement: visible text "HXYZlo" is preserved, NOT reverted to "Hello"!
    assert.equal(c.docText, 'HXYZlo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].group, 'Composition');
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYZlo');
    assert.equal(c.commandOutcomes.get(q.ticketId)?.status, 'Cancelled');
  });

  // 13. openNewConnection preserves accepted visible text and history during AwaitingSettlement
  test('openNewConnection preserves accepted visible text and records history during AwaitingSettlement', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    c.applyInput({ type: 'unmark', settled: false }); // AwaitingSettlement
    assert.equal(c.compositionState, 'AwaitingSettlement');

    const rConn = c.applyInput({ type: 'openNewConnection' });
    assert.equal(rConn.status, 'Accepted');
    assert.equal(c.epoch, 1);
    assert.equal(c.compositionState, 'Idle');
    assert.equal(c.docText, 'HXYZlo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYZlo');
  });

  // 14. openNewConnection retains undo after partial commit (Finding B)
  test('openNewConnection retains undo after partial commit', () => {
    const c = new CandidateBCoordinator('Hello', null, [1, 2]);
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    c.applyInput({ type: 'partialCommit', length: 1 });
    c.applyInput({ type: 'preedit', text: 'Y' });
    assert.equal(c.docText, 'HXYllo');

    const rConn = c.applyInput({ type: 'openNewConnection' });
    assert.equal(rConn.status, 'Accepted');
    assert.equal(c.docText, 'HXYllo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].group, 'Composition');
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYllo');
  });

  // 15. dispose closes coordinator and records visible history under preserve-visible-content teardown
  test('dispose closes coordinator, rejects subsequent inputs, and records partial commit history', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    c.applyInput({ type: 'partialCommit', length: 1 }); // "X" committed
    c.applyInput({ type: 'preedit', text: 'Y' }); // "Y" provisional

    const rDisp = c.applyInput({ type: 'dispose' });
    assert.equal(rDisp.status, 'Accepted');
    assert.equal(c.connectionState, 'Closed');
    // Accepted visible text "HXYllo" preserved under preserve-visible-content policy
    assert.equal(c.docText, 'HXYllo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYllo');

    const rAfter = c.applyInput({ type: 'preedit', text: 'Z' });
    assert.equal(rAfter.status, 'Rejected');
    assert.equal(rAfter.reason, 'ConnectionClosed');
  });

  // 16. dispose before any mutation
  test('dispose before any mutation closes coordinator without history', () => {
    const c = new CandidateBCoordinator('Hello');
    const rDisp = c.applyInput({ type: 'dispose' });
    assert.equal(rDisp.status, 'Accepted');
    assert.equal(c.connectionState, 'Closed');
    assert.equal(c.docText, 'Hello');
    assert.equal(c.undoStack.length, 0);
  });

  // 17. dispose during ordinary preedit
  test('dispose during ordinary preedit preserves visible text and records history', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    assert.equal(c.docText, 'HXYZllo');

    const rDisp = c.applyInput({ type: 'dispose' });
    assert.equal(rDisp.status, 'Accepted');
    assert.equal(c.connectionState, 'Closed');
    assert.equal(c.docText, 'HXYZllo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXYZllo');
  });

  // 18. dispose after nonterminal unmark
  test('dispose after nonterminal unmark preserves visible text and invalidates active fence', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    const rUnmark = c.applyInput({ type: 'unmark', settled: false });
    assert.equal(c.compositionState, 'AwaitingSettlement');

    const rDisp = c.applyInput({ type: 'dispose' });
    assert.equal(rDisp.status, 'Accepted');
    assert.equal(c.connectionState, 'Closed');
    assert.equal(c.docText, 'HXllo');
    assert.equal(c.undoStack.length, 1);
    assert.equal(c.undoStack[0].beforeText, 'Hello');
    assert.equal(c.undoStack[0].afterText, 'HXllo');
    assert.equal(c.activeSettlementToken, null);
  });

  // 19. dispose with queued commands cancels pending commands
  test('dispose cancels queued pending commands', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });
    const qRes = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    assert.equal(qRes.status, 'Accepted');

    c.applyInput({ type: 'dispose' });
    assert.equal(c.commandOutcomes.get(qRes.ticketId).status, 'Cancelled');
    assert.equal(c.commandOutcomes.get(qRes.ticketId).reason, 'Disposed');
  });

  // 20. dispose during staged failure rolls back cleanly
  test('dispose during staged failure rolls back state atomically', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'X' });

    const r = c.applyInput({ type: 'dispose', injectFailureAt: 'duringOverlayMaterialization' });
    assert.equal(r.status, 'Rejected');
    assert.ok(r.reason.includes('duringOverlayMaterialization'));

    // State rolled back
    assert.equal(c.connectionState, 'Active');
    assert.equal(c.compositionState, 'Composing');
    assert.equal(c.docText, 'HXllo');
  });

  // 21. repeated dispose
  test('repeated dispose is rejected as ConnectionClosed', () => {
    const c = new CandidateBCoordinator('Hello');
    const r1 = c.applyInput({ type: 'dispose' });
    assert.equal(r1.status, 'Accepted');
    const r2 = c.applyInput({ type: 'dispose' });
    assert.equal(r2.status, 'Rejected');
    assert.equal(r2.reason, 'ConnectionClosed');
  });

  console.log(`\nSettlement Lifecycle Summary: ${passed} passed, ${failed} failed`);
  return { passed, failed };
}

if (process.argv[1]?.endsWith('test-settlement-lifecycle.mjs')) {
  runSettlementLifecycleTests();
}
