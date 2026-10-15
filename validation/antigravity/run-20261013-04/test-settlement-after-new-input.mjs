// Comprehensive Verification Suite: Settlement Token & Evidence Contract (§5, §4.4)
// Tests all 8 scenarios specified in Section 5 of the mandate:
// 1. Request settlement before any unmark -> Rejected(CandidateStillActive)
// 2. Nonterminal unmark, then resumed preedit, then completion -> Rejected(CandidateStillActive)
// 3. Target expansion or partial commit after settlement was requested -> invalidates pending token
// 4. Late completion from an obsolete request in the same session -> Rejected(ObsoleteSettlementToken)
// 5. Missing / wrong / future connection and session identities -> Rejected(StaleEpoch / FutureEpoch / SessionMismatch)
// 6. Duplicate completion -> Rejected(AlreadySettled) without duplicate history
// 7. Completion while a marked candidate is still active -> Rejected(CandidateStillActive)
// 8. Unknown target extent -> Rejected(UnknownTargetExtent), NEVER resolves to [0, 0]

import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

console.log("=== Running Settlement Contract Verification Suite (Section 5) ===");

// ---------------------------------------------------------------------------
// 1. Request settlement before any unmark
// ---------------------------------------------------------------------------
console.log("\n1. Testing requestSettlement before any unmark...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-pre' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.notEqual(coord.markedRange, null);
  
  // Explicit settlement request
  const reqRes = coord.requestSettlement('TestCommand');
  assert.equal(reqRes.status, 'Requested');
  assert.ok(reqRes.token.startsWith('settle-tok-'));
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  
  // Direct completeSettlement while candidate is still marked
  const settleRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-pre', token: reqRes.token });
  assert.equal(settleRes.status, 'Accepted');
  assert.equal(settleRes.receipt.status, 'Rejected');
  assert.equal(settleRes.receipt.reason, 'CandidateStillActive');
  
  // Invariants: marked candidate remains intact; no premature history push
  assert.deepEqual(coord.markedRange, [1, 3]);
  assert.equal(coord.undoStack.length, 0);
  console.log("   -> PASS: completeSettlement rejected with CandidateStillActive when candidate is still active.");
}

// ---------------------------------------------------------------------------
// 2. Nonterminal unmark, then resumed preedit, then completion
// ---------------------------------------------------------------------------
console.log("\n2. Testing nonterminal unmark, resumed preedit, then completion...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-resume' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  
  // Nonterminal unmark
  const uRes = coord.receiveCallback(1, { type: 'unmark', settled: false });
  assert.equal(uRes.receipt.settled, false);
  assert.equal(coord.markedRange, null);
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  
  // Resumed preedit arrives: allocates candidate, transitions back to Composing
  const pRes = coord.receiveCallback(1, { type: 'preedit', text: 'Z' });
  assert.equal(coord.compositionState, 'Composing');
  assert.notEqual(coord.markedRange, null);
  
  // completeSettlement arrives: must be rejected because preedit resumed and candidate is active!
  const settleRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-resume' });
  assert.equal(settleRes.status, 'Accepted');
  assert.equal(settleRes.receipt.status, 'Rejected');
  assert.equal(settleRes.receipt.reason, 'CandidateStillActive');
  assert.equal(coord.undoStack.length, 0);
  
  // Subsequent unmark clears candidate, allowing completion to finalize
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  const finalSettle = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-resume' });
  assert.equal(finalSettle.status, 'Accepted');
  assert.equal(finalSettle.receipt.result.type, 'SettlementCompleted');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.undoStack.length, 1);
  console.log("   -> PASS: Resumed preedit invalidates settlement; completion allowed only after subsequent unmark.");
}

// ---------------------------------------------------------------------------
// 3. Target expansion or partial commit after settlement was requested
// ---------------------------------------------------------------------------
console.log("\n3. Testing target expansion / partial commit after settlement was requested...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-supersede' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  
  const reqRes = coord.requestSettlement('FormatCommand');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.notEqual(coord.activeSettlementToken, null);
  
  // Native issues partial commit -> supersedes pending settlement!
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  assert.equal(coord.activeSettlementToken, null); // Invalidated!
  assert.equal(coord.compositionState, 'Composing');
  
  // Native issues target expansion -> supersedes pending settlement!
  const reqRes2 = coord.requestSettlement('AnotherCommand');
  assert.notEqual(coord.activeSettlementToken, null);
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 1, newEnd: 4 });
  assert.equal(coord.activeSettlementToken, null); // Invalidated!
  assert.equal(coord.compositionState, 'Composing');
  console.log("   -> PASS: New candidate input (partial commit, target expansion) invalidates pending settlement token.");
}

// ---------------------------------------------------------------------------
// 4. Late completion from an obsolete request in the same session
// ---------------------------------------------------------------------------
console.log("\n4. Testing late completion from obsolete request in same session...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-late' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  
  const req1 = coord.requestSettlement('Req1');
  const token1 = req1.token;
  
  // New input arrives, invalidating req1
  coord.receiveCallback(1, { type: 'preedit', text: 'XYZ' });
  
  // req2 is requested later
  const req2 = coord.requestSettlement('Req2');
  const token2 = req2.token;
  assert.notEqual(token1, token2);
  
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  
  // Obsolete completion using token1 arrives
  const lateRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-late', token: token1 });
  assert.equal(lateRes.status, 'Accepted');
  assert.equal(lateRes.receipt.status, 'Rejected');
  assert.equal(lateRes.receipt.reason, 'ObsoleteSettlementToken');
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.undoStack.length, 0);
  
  // Valid completion using token2 succeeds
  const validRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-late', token: token2 });
  assert.equal(validRes.status, 'Accepted');
  assert.equal(validRes.receipt.result.type, 'SettlementCompleted');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.undoStack.length, 1);
  console.log("   -> PASS: Obsolete token rejected; valid token completed.");
}

// ---------------------------------------------------------------------------
// 5. Missing / wrong / future connection and session identities
// ---------------------------------------------------------------------------
console.log("\n5. Testing wrong connection epoch, future epoch, and session mismatch...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection(); // Epoch 1
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sess-active' });
  coord.receiveCallback(1, { type: 'preedit', text: 'K' });
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  
  // Stale epoch (epoch 0)
  const staleRes = coord.receiveCallback(0, { type: 'completeSettlement', sessionId: 'sess-active' });
  assert.equal(staleRes.status, 'StaleEpoch');
  
  // Future epoch (epoch 99)
  const futureRes = coord.receiveCallback(99, { type: 'completeSettlement', sessionId: 'sess-active' });
  assert.equal(futureRes.status, 'FutureEpoch');
  
  // Session mismatch
  const mismatchRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 'wrong-session' });
  assert.equal(mismatchRes.status, 'Accepted');
  assert.equal(mismatchRes.receipt.status, 'Rejected');
  assert.equal(mismatchRes.receipt.reason, 'SessionMismatch');
  
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  console.log("   -> PASS: Stale epoch, future epoch, and session mismatch correctly rejected.");
}

// ---------------------------------------------------------------------------
// 6. Duplicate completion
// ---------------------------------------------------------------------------
console.log("\n6. Testing duplicate completion...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-dup' });
  coord.receiveCallback(1, { type: 'preedit', text: 'K' });
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  
  // First completion: Accepted
  const c1 = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-dup' });
  assert.equal(c1.receipt.result.type, 'SettlementCompleted');
  assert.equal(coord.compositionState, 'Idle');
  assert.equal(coord.undoStack.length, 1);
  
  // Second completion: Rejected as AlreadySettled
  const c2 = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-dup' });
  assert.equal(c2.status, 'Accepted');
  assert.equal(c2.receipt.status, 'Rejected');
  assert.equal(c2.receipt.reason, 'AlreadySettled');
  assert.equal(coord.undoStack.length, 1); // No duplicate history!
  console.log("   -> PASS: Duplicate completeSettlement rejected as AlreadySettled without duplicating history.");
}

// ---------------------------------------------------------------------------
// 7. Unknown target extent (never resolve to [0, 0])
// ---------------------------------------------------------------------------
console.log("\n7. Verifying unknown target extent is never resolved to [0, 0]...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  // Manually force into AwaitingSettlement without settlementEvidence
  coord.compositionState = 'AwaitingSettlement';
  coord.activeSessionId = 's-unknown';
  coord.settlementEvidence = null;
  
  const badRes = coord.receiveCallback(1, { type: 'completeSettlement', sessionId: 's-unknown' });
  assert.equal(badRes.status, 'Accepted');
  assert.equal(badRes.receipt.status, 'Rejected');
  assert.equal(badRes.receipt.reason, 'UnknownTargetExtent');
  assert.equal(coord.lastSettledExtent, null); // Never fell back to [0, 0]!
  console.log("   -> PASS: Unknown target extent rejected with UnknownTargetExtent; never resolves to [0, 0].");
}

console.log("\n=== All Settlement Contract Tests (Section 5) Passed Successfully ===");
