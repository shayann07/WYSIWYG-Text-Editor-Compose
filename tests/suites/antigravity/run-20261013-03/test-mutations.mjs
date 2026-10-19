import assert from 'node:assert/strict';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

console.log("=== Running Comprehensive Mutation Verification (run-20261013-03) ===");
console.log("Deliberately injecting defects via coordinator subclasses to prove tests and invariant checkers catch them.\n");

let caughtCount = 0;
const totalMutations = 16;

// ---------------------------------------------------------------------------
// Mutant 1: Intermediate baseline reuse
// Corrupts baseline units with intermediate candidate units instead of immutable baseline B.
// ---------------------------------------------------------------------------
console.log("1. Testing Mutant 1: Intermediate baseline reuse...");
class MutantIntermediateBaselineCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'preedit') {
      // Defect: Overwrites baselineUnits with intermediate candidate
      if (this.markedRange && this.markedRange[1] > this.markedRange[0]) {
        this.baselineUnits = [{ char: 'X', attributes: { color: 'red' } }];
      }
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantIntermediateBaselineCoordinator("ABC", [{ bold: true }, { color: 'blue' }, { italic: true }]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 3, sessionId: 'm1' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XYZ' });
  coord.receiveCallback(1, { type: 'preedit', text: 'ABC' });
  // Invariant: original styling { bold: true } must be restored from immutable baseline
  assert.equal(coord.docAttributes[0].bold, true);
  console.log("   FAILED: Mutant 1 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 1 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 2: Immediate formatting during composition/settlement
// Executes formatting immediately instead of deferring while composition is active.
// ---------------------------------------------------------------------------
console.log("2. Testing Mutant 2: Immediate formatting during settlement...");
class MutantImmediateFormatCoordinator extends ProtocolCoordinator {
  dispatchAppCommand(cmd) {
    if (cmd.type === 'format') {
      // Defect: executes immediately bypassing deferral
      return this.executeFormatCommand(cmd);
    }
    return super.dispatchAppCommand(cmd);
  }
}
try {
  const coord = new MutantImmediateFormatCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 'm2' });
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' });
  const res = coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  // Invariant 5: command cannot execute before verified settlement
  assert.equal(res.status, 'Deferred');
  console.log("   FAILED: Mutant 2 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 2 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 3: Whole-document CompositionTarget
// Applies formatting to whole document [0, docText.length] instead of composition extent.
// ---------------------------------------------------------------------------
console.log("3. Testing Mutant 3: Whole-document CompositionTarget...");
class MutantWholeDocFormatCoordinator extends ProtocolCoordinator {
  executeFormatCommand(cmd, resolvedRange = null) {
    if (cmd.target === 'composing' || cmd.target?.type === 'CompositionTarget') {
      // Defect: forces formatting across whole document
      return super.executeFormatCommand(cmd, [0, this.docText.length]);
    }
    return super.executeFormatCommand(cmd, resolvedRange);
  }
}
try {
  const coord = new MutantWholeDocFormatCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'm3' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.dispatchAppCommand({ type: 'format', target: { type: 'CompositionTarget', sessionId: 'm3' }, style: { bold: true } });
  coord.receiveCallback(1, { type: 'commit', text: 'Y' });
  // Invariant 4: index 0 is outside composition extent and must NOT be bold
  assert.equal(coord.docAttributes[0].bold, undefined);
  console.log("   FAILED: Mutant 3 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 3 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 4: Ignored unmark
// Ignores unmark callback payload, leaving coordinator stuck in Composing.
// ---------------------------------------------------------------------------
console.log("4. Testing Mutant 4: Ignored unmark...");
class MutantIgnoredUnmarkCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'unmark') {
      // Defect: ignores unmark
      return { status: 'Ignored', settled: false };
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantIgnoredUnmarkCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 'm4' });
  coord.receiveCallback(1, { type: 'preedit', text: 'abc' });
  coord.receiveCallback(1, { type: 'unmark', settled: true });
  // Invariant: unmark with settled: true must transition state to Idle
  assert.equal(coord.compositionState, 'Idle');
  console.log("   FAILED: Mutant 4 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 4 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 5: Out of bounds slice boundary crash on empty candidate
// Crashes when empty candidate text arrives.
// ---------------------------------------------------------------------------
console.log("5. Testing Mutant 5: Out of bounds slice boundary handling...");
class MutantSliceCrashCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'preedit' && (!payload.text || payload.text.length === 0)) {
      // Defect: crashes attempting to slice empty candidate
      throw new RangeError("Mutant crash: empty candidate text not supported");
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantSliceCrashCoordinator("Hello", null, [2, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 2, end: 2, sessionId: 'm5' });
  const res = coord.receiveCallback(1, { type: 'preedit', text: '' });
  assert.equal(res.status, 'Accepted');
  console.log("   FAILED: Mutant 5 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 5 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 6: Cancellation erasing frozen prefix after partial commit
// Drops committedUnits during cancel instead of preserving them.
// ---------------------------------------------------------------------------
console.log("6. Testing Mutant 6: Cancellation erasing frozen prefix after partial commit...");
class MutantCancelErasesFrozenCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'cancel') {
      // Defect: wipes committed units on cancel
      this.committedUnits = [];
      this.committedExtent = null;
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantCancelErasesFrozenCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 'm6' });
  coord.receiveCallback(1, { type: 'preedit', text: 'nihao' });
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 2 });
  coord.receiveCallback(1, { type: 'cancel' });
  // Invariant 1: frozen units ('ni') must be preserved
  assert.equal(coord.docText.startsWith('ni'), true);
  console.log("   FAILED: Mutant 6 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 6 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 7: Direct commit extent defaulting to 0 instead of selection
// Ignores selection on direct commit and defaults extent to [0, len].
// ---------------------------------------------------------------------------
console.log("7. Testing Mutant 7: Direct commit extent defaulting to [0, len]...");
class MutantCommitExtentZeroCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    const res = super.applyCallbackPayload(payload);
    if (payload.type === 'commit' && res.type === 'Committed') {
      // Defect: forces finalExtent to start at 0
      res.finalExtent = [0, (payload.text || "").length];
    }
    return res;
  }
}
try {
  const coord = new MutantCommitExtentZeroCoordinator("Hello World", null, [6, 11]);
  coord.openNewConnection();
  const res = coord.receiveCallback(1, { type: 'commit', text: 'Compose' });
  // Invariant 4: commit must replace selection [6, 11], yielding extent [6, 13]
  assert.deepEqual(res.receipt.finalExtent, [6, 13]);
  console.log("   FAILED: Mutant 7 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 7 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 8: Focus loss failing to cancel pending composition commands
// Leaves pending commands queued on focus loss.
// ---------------------------------------------------------------------------
console.log("8. Testing Mutant 8: Focus loss failing to cancel pending commands...");
class MutantFocusLossLeakCoordinator extends ProtocolCoordinator {
  onFocusLost(strategy = 'settle') {
    // Defect: completely ignores pending commands and leaves them queued
    return { status: 'FocusLostIgnored' };
  }
}
try {
  const coord = new MutantFocusLossLeakCoordinator("Hello");
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 'm8' });
  coord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  coord.onFocusLost('settle');
  // Invariant 6: pending commands targeting composition must be cancelled
  assert.equal(coord.pendingCommands.length, 0);
  console.log("   FAILED: Mutant 8 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 8 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 9: Premature unmark settlement & session loss (Defect A)
// Nonterminal unmark immediately finalizes composition and clears activeSessionId.
// ---------------------------------------------------------------------------
console.log("9. Testing Mutant 9: Premature unmark settlement & session loss (Defect A)...");
class MutantPrematureSettlementCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'unmark' && payload.settled === false) {
      // Defect A: clears session and pushes undo entry prematurely
      this.activeSessionId = null;
      this.compositionState = 'Idle';
      this.undoStack.push({ group: 'Composition' });
      return { settled: false, status: 'PrematurelySettled' };
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantPrematureSettlementCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.dispatchAppCommand({ type: 'format', target: { type: 'CompositionTarget', sessionId: 's' }, style: { bold: true } });
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  // Specification: session must remain intact and history must be deferred
  assert.equal(coord.compositionState, 'AwaitingSettlement');
  assert.equal(coord.activeSessionId, 's');
  assert.equal(coord.undoStack.length, 0);
  console.log("   FAILED: Mutant 9 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 9 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 10: Target expansion wiping committed units (Defect B)
// Target expansion replaces candidate start before committed extent, wiping committed units.
// ---------------------------------------------------------------------------
console.log("10. Testing Mutant 10: Target expansion wiping committed units (Defect B)...");
class MutantExpansionWipesCommittedCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'targetExpansion') {
      // Defect B: does not protect committed units and clears them
      this.committedUnits = [];
      this.committedExtent = null;
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantExpansionWipesCommittedCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sB' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  coord.receiveCallback(1, { type: 'targetExpansion', newStart: 2, newEnd: 4 });
  coord.receiveCallback(1, { type: 'cancel' });
  // Invariant 1 & Defect B: 'X' must survive cancel, yielding 'HXllo'
  assert.equal(coord.docText, "HXllo");
  assert.equal(coord.undoStack.length, 1);
  console.log("   FAILED: Mutant 10 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 10 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 11: Connection replacement dropping history (Defect C)
// openNewConnection clears snapshot without recording undo group.
// ---------------------------------------------------------------------------
console.log("11. Testing Mutant 11: Connection replacement dropping history (Defect C)...");
class MutantConnectionReplacementDropsHistoryCoordinator extends ProtocolCoordinator {
  _finalizeUnfinishedCompositionOnTurnover() {
    // Defect C: does not push undo group on turnover
  }
}
try {
  const coord = new MutantConnectionReplacementDropsHistoryCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sC' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.openNewConnection();
  // Invariant 2 & Defect C: undoStack must have 1 entry preserving composition edit
  assert.equal(coord.undoStack.length, 1);
  console.log("   FAILED: Mutant 11 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 11 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 12: Repeated identical preedit bumping DocumentRevision (Defect D)
// Preedit unconditionally increments documentRevision.
// ---------------------------------------------------------------------------
console.log("12. Testing Mutant 12: Repeated identical preedit bumping DocumentRevision (Defect D)...");
class MutantIdenticalPreeditBumpsDocRevCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    const res = super.applyCallbackPayload(payload);
    if (payload.type === 'preedit') {
      // Defect D: unconditionally increments documentRevision
      this.documentRevision++;
    }
    return res;
  }
}
try {
  const coord = new MutantIdenticalPreeditBumpsDocRevCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sD' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  const rev1 = coord.documentRevision;
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  // Invariant 8: DocumentRevision must NOT advance on identical preedit
  assert.equal(coord.documentRevision, rev1);
  console.log("   FAILED: Mutant 12 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 12 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 13: Accepting repeated unmark and resetting extent to [0, 0]
// Accepts second unmark in AwaitingSettlement and resets lastSettledExtent.
// ---------------------------------------------------------------------------
console.log("13. Testing Mutant 13: Accepting repeated unmark and resetting extent...");
class MutantAcceptRepeatedUnmarkCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'unmark' && this.compositionState === 'AwaitingSettlement' && this.markedRange === null) {
      // Defect: Accepts repeated unmark and resets extent
      this.lastSettledExtent = [0, 0];
      return { status: 'Accepted', settled: true };
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantAcceptRepeatedUnmarkCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's13' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  const res = coord.receiveCallback(1, { type: 'unmark', settled: true });
  // Specification: repeated unmark must be rejected with AlreadyUnmarked
  assert.equal(res.receipt.status, 'Rejected');
  assert.equal(res.receipt.reason, 'AlreadyUnmarked');
  assert.deepEqual(coord.lastSettledExtent, [1, 2]);
  console.log("   FAILED: Mutant 13 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 13 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 14: Overwriting raw selection with candidate end
// Overwrites raw selection with candidate end instead of preserving client selection.
// ---------------------------------------------------------------------------
console.log("14. Testing Mutant 14: Overwriting raw selection...");
class MutantRawSelectionOverwrittenCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    const res = super.applyCallbackPayload(payload);
    if (payload.type === 'startComposition') {
      // Defect: forces selection to end of candidate [2, 2]
      this.selection = [2, 2];
    }
    return res;
  }
}
try {
  const coord = new MutantRawSelectionOverwrittenCoordinator("Hello", null, [0, 0]);
  coord.openNewConnection();
  coord.receiveCallback(1, {
    type: 'startComposition',
    start: 0,
    end: 2,
    selection: [1, 4]
  });
  // Invariant 7: selection must preserve raw selection [1, 4]
  assert.deepEqual(coord.selection, [1, 4]);
  console.log("   FAILED: Mutant 14 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 14 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 15: Crash on preedit arrival after nonterminal unmark
// Fails when preedit arrives while markedRange is null in AwaitingSettlement.
// ---------------------------------------------------------------------------
console.log("15. Testing Mutant 15: Preedit crash after nonterminal unmark...");
class MutantPreeditCrashCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'preedit' && this.compositionState === 'AwaitingSettlement' && this.markedRange === null) {
      // Defect: crashes attempting to unpack null markedRange
      throw new TypeError("Cannot read properties of null (reading 'markedRange')");
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantPreeditCrashCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's15' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  coord.receiveCallback(1, { type: 'unmark', settled: false });
  const res = coord.receiveCallback(1, { type: 'preedit', text: 'W' });
  // Specification: preedit after nonterminal unmark must be accepted safely
  assert.equal(res.status, 'Accepted');
  console.log("   FAILED: Mutant 15 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 15 caught!", e.message);
}

// ---------------------------------------------------------------------------
// Mutant 16: Trapping unrelated commands on cancel
// Drops unrelated pending commands without executing them.
// ---------------------------------------------------------------------------
console.log("16. Testing Mutant 16: Trapping unrelated commands on cancel...");
class MutantTrappedUnrelatedCommandsCoordinator extends ProtocolCoordinator {
  applyCallbackPayload(payload) {
    if (payload.type === 'cancel') {
      // Defect: cancels all commands including unrelated ones
      for (const item of this.pendingCommands) {
        this.commandOutcomes.set(item.ticketId, { status: 'Cancelled', reason: 'CompositionCancelled', ticketId: item.ticketId });
      }
      this.pendingCommands = [];
    }
    return super.applyCallbackPayload(payload);
  }
}
try {
  const coord = new MutantTrappedUnrelatedCommandsCoordinator("Hello World", null, [0, 0]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 0, end: 5, sessionId: 's16' });
  coord.receiveCallback(1, { type: 'preedit', text: 'Hi' });
  const qCmd = coord.dispatchAppCommand({ type: 'format', target: [6, 11], style: { italic: true } });
  coord.receiveCallback(1, { type: 'cancel' });
  const outcome = coord.commandOutcomes.get(qCmd.ticketId);
  // Invariant 6: Unrelated command must be executed on cancel
  assert.equal(outcome.status, 'Applied');
  console.log("   FAILED: Mutant 16 was not caught!");
} catch (e) {
  caughtCount++;
  console.log("   -> CONFIRMED: Mutant 16 caught!", e.message);
}

console.log(`\n=== ALL ${caughtCount}/${totalMutations} MUTANTS SUCCESSFULLY CAUGHT BY TEST SUITE ===`);
assert.equal(caughtCount, totalMutations, `All ${totalMutations} mutants must be caught!`);
