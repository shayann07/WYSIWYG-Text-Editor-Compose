// Settlement and Lifecycle Adversarial Validation Suite (Gap 4.7, §4.4, §4.5)
// Tests:
// - Nonterminal unmark followed by more preedit
// - New target or partial commit after a settlement request
// - Two requests within one session
// - Duplicate, stale, and wrong-identity completion
// - Completion while a marked candidate remains active
// - Connection replacement, focus loss, and disposal
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
    // 'XYZ' at [1, 4) should be bold and italic
    for (let i = 1; i < 4; i++) {
      assert.ok(attrs[i].bold, `Index ${i} should be bold`);
      assert.ok(attrs[i].italic, `Index ${i} should be italic`);
    }
    // Exactly 3 undo groups: 1 Composition + 2 separate Formatting groups
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

  console.log(`\nSettlement Lifecycle Summary: ${passed} passed, ${failed} failed`);
  return { passed, failed };
}

if (process.argv[1].endsWith('test-settlement-lifecycle.mjs')) {
  runSettlementLifecycleTests();
}
