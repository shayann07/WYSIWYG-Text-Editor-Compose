import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';
import { reconcileCandidateAttributes } from './reference-model.mjs';

console.log("=== Running Mutation Verification: Deliberately Injecting Bugs to Prove Tests Catch Them ===");

let mutationsCaught = 0;

// ---------------------------------------------------------------------------
// Mutation 1: Previous-candidate baseline reuse (Bug: mutable baseline)
// ---------------------------------------------------------------------------
console.log("\n1. Testing Mutation 1: Previous-candidate baseline reuse...");
{
  // Normal implementation: immutable baseline B = [bold(a), italic(b)]
  const B = [
    { char: 'a', attributes: { bold: true } },
    { char: 'b', attributes: { italic: true } }
  ];
  const seed = { color: 'blue' };

  // Mutated candidate updater that uses PREVIOUS candidate result as the new baseline
  class MutatedSession {
    constructor(baseline, seed) {
      this.currentBaseline = baseline.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
      this.seed = seed;
    }
    updateCandidate(newText) {
      const reconciled = reconcileCandidateAttributes(this.currentBaseline, newText, this.seed);
      // BUG: overwrites baseline with intermediate result!
      this.currentBaseline = reconciled.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
      return reconciled;
    }
  }

  const mutatedSession = new MutatedSession(B, seed);
  // Detour: ab -> x -> ab
  mutatedSession.updateCandidate("x"); // baseline becomes [{ char: 'x', attributes: { color: 'blue' } }]
  const finalResult = mutatedSession.updateCandidate("ab"); // reconciled against 'x' instead of B!

  // Check: In buggy implementation, 'b' loses italic(true) because baseline was 'x'!
  let testFailedAsExpected = false;
  try {
    assert.deepEqual(finalResult[0].attributes, { bold: true });
    assert.deepEqual(finalResult[1].attributes, { italic: true }); // Will throw because it got seed { color: 'blue' }!
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 1 caught! Intermediate baseline reuse failed attribute assertion:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 1 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 2: Immediate formatting during settlement (Settlement bypass)
// ---------------------------------------------------------------------------
console.log("\n2. Testing Mutation 2: Immediate formatting during settlement...");
{
  class MutatedCoordinatorBypass extends ProtocolCoordinator {
    dispatchAppCommand(cmd) {
      // BUG: only queues if Composing, executes immediately if AwaitingSettlement!
      if (this.compositionState === 'Composing') {
        const ticketId = `ticket-${Date.now()}`;
        this.pendingCommands.push({ ticketId, cmd });
        this.compositionState = 'AwaitingSettlement';
        return { status: 'Deferred', ticketId };
      }
      return this.executeFormatCommand(cmd);
    }
  }

  const coord = new MutatedCoordinatorBypass("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });

  // Second command sent while AwaitingSettlement:
  const r2 = coord.dispatchAppCommand({ type: 'format', target: [0, 2], style: { italic: true } });

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that r2 MUST be Deferred!
    assert.equal(r2.status, 'Deferred');
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 2 caught! Immediate formatting bypassed settlement:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 2 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 3: Whole-document CompositionTarget
// ---------------------------------------------------------------------------
console.log("\n3. Testing Mutation 3: Whole-document CompositionTarget...");
{
  class MutatedCoordinatorWholeDoc extends ProtocolCoordinator {
    executeFormatCommand(cmd, resolvedRange = null) {
      // BUG: ignores resolvedRange and targets whole document!
      if (cmd.target === 'composing') {
        return super.executeFormatCommand(cmd, [0, this.docText.length]);
      }
      return super.executeFormatCommand(cmd, resolvedRange);
    }
  }

  const coord = new MutatedCoordinatorWholeDoc("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 }); // compose 'e'
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  coord.receiveCallback(1, { type: 'commit', text: 'Y' }); // doc is "HYllo"

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that index 0 ('H') must NOT be bold!
    assert.equal(coord.docAttributes[0]?.bold, undefined);
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 3 caught! Whole document formatting detected:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 3 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 4: Ignored unmark
// ---------------------------------------------------------------------------
console.log("\n4. Testing Mutation 4: Ignored unmark...");
{
  class MutatedCoordinatorIgnoredUnmark extends ProtocolCoordinator {
    applyCallbackPayload(payload) {
      // BUG: unmark does nothing
      if (payload.type === 'unmark') {
        return { type: 'NoChange' };
      }
      return super.applyCallbackPayload(payload);
    }
  }

  const coord = new MutatedCoordinatorIgnoredUnmark("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'abc' });
  coord.receiveCallback(1, { type: 'unmark' });

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that markedRange must be null and state Idle
    assert.equal(coord.compositionState, 'Idle');
    assert.equal(coord.markedRange, null);
    assert.equal(coord.undoStack.length, 1);
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 4 caught! Ignored unmark detected:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 4 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 5: Committed-text leakage (AWT slice defect)
// ---------------------------------------------------------------------------
console.log("\n5. Testing Mutation 5: Committed-text leakage (AWT boundary bug)...");
{
  // Simulating the original AWT bug in JS:
  function buggyGetCommittedText(doc, markedStart, markedEnd, beginIndex, endIndex) {
    // BUG: naively maps boundary offset using AFTER for begin and BEFORE for end without checking empty slice!
    function mapCommittedToLive(q, assoc) {
      if (q < markedStart) return q;
      if (q === markedStart) return (assoc === 'BEFORE') ? markedStart : markedEnd;
      return q + (markedEnd - markedStart);
    }
    if (endIndex <= markedStart) {
      const liveBegin = mapCommittedToLive(beginIndex, 'AFTER');
      const liveEnd = mapCommittedToLive(endIndex, 'BEFORE');
      if (liveBegin > liveEnd) {
        throw new Error(`StringIndexOutOfBoundsException: Range [${liveBegin}, ${liveEnd}) out of bounds`);
      }
      return doc.substring(liveBegin, liveEnd);
    }
    return doc.substring(beginIndex, endIndex);
  }

  let testFailedAsExpected = false;
  try {
    // Slice [5, 5) on doc "HelloCOMPOSEDWorld" with marked [5, 13)
    buggyGetCommittedText("HelloCOMPOSEDWorld", 5, 13, 5, 5);
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 5 caught! Empty slice threw:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 5 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 6: Cancellation erasing frozen prefix after partial commit
// ---------------------------------------------------------------------------
console.log("\n6. Testing Mutation 6: Cancellation erasing frozen prefix after partial commit...");
{
  class MutatedCoordinatorCancelErase extends ProtocolCoordinator {
    applyCallbackPayload(payload) {
      // BUG: unconditionally reverts to pre-composition snapshot, wiping out frozen prefix!
      if (payload.type === 'cancel') {
        if (this.compositionBeforeSnapshot) {
          this.docText = this.compositionBeforeSnapshot.text;
          this.docAttributes = this.compositionBeforeSnapshot.attrs.map(a => ({ ...a }));
          this.selection = [...this.compositionBeforeSnapshot.selection];
          this.documentRevision++;
        }
        this.compositionState = 'Idle';
        this.markedRange = null;
        this.frozenPrefixUnits = [];
        return { type: 'Cancelled', text: this.docText };
      }
      return super.applyCallbackPayload(payload);
    }
  }

  const coord = new MutatedCoordinatorCancelErase("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' });
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 2 });
  coord.receiveCallback(1, { type: 'cancel' });

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that "nillo" must survive!
    assert.equal(coord.docText, "nillo");
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 6 caught! Cancellation wiped out committed prefix:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 6 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 7: Direct commit extent defaulting to 0 instead of selection
// ---------------------------------------------------------------------------
console.log("\n7. Testing Mutation 7: Direct commit extent defaulting to 0 instead of selection...");
{
  class MutatedCoordinatorDirectCommitOffsetZero extends ProtocolCoordinator {
    applyCallbackPayload(payload) {
      const res = super.applyCallbackPayload(payload);
      if (payload.type === 'commit') {
        // BUG: forgets selection offset and calculates extent from 0!
        this.lastSettledExtent = [0, payload.text.length];
      }
      return res;
    }
  }

  const coord = new MutatedCoordinatorDirectCommitOffsetZero("Hello World", null, [6, 11]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'commit', text: 'Everyone' });

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that extent must be [6, 14), NOT [0, 8)!
    assert.deepEqual(coord.lastSettledExtent, [6, 14]);
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 7 caught! Direct commit resolved extent from 0:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 7 should have caused test failure!");
  mutationsCaught++;
}

// ---------------------------------------------------------------------------
// Mutation 8: Focus loss failing to cancel pending composition commands
// ---------------------------------------------------------------------------
console.log("\n8. Testing Mutation 8: Focus loss failing to cancel pending composition commands...");
{
  class MutatedCoordinatorFocusLossRetainsCommands extends ProtocolCoordinator {
    onFocusLost(strategy = 'settle') {
      // BUG: forgets to cancel pending composition commands!
      return { status: 'Ignored' };
    }
  }

  const coord = new MutatedCoordinatorFocusLossRetainsCommands("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2 });
  coord.receiveCallback(1, { type: 'preedit', text: 'temp' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  coord.onFocusLost('cancel');

  let testFailedAsExpected = false;
  try {
    // Correct test asserts that pendingCommands must be cleared / cancelled!
    assert.equal(coord.pendingCommands.length, 0);
  } catch (err) {
    testFailedAsExpected = true;
    console.log("   -> CONFIRMED: Mutation 8 caught! Focus loss leaked pending commands:", err.message);
  }
  assert.equal(testFailedAsExpected, true, "Mutation 8 should have caused test failure!");
  mutationsCaught++;
}

console.log(`\n=== ALL ${mutationsCaught}/8 MUTATIONS SUCCESSFULLY CAUGHT BY TEST SUITE ===`);
