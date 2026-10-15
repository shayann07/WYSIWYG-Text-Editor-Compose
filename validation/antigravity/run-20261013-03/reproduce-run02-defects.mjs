import assert from 'node:assert/strict';
import { ProtocolCoordinator } from '../run-20261013-02/protocol-coordinator.mjs';

console.log("=== Reproducing Coordinator Defects Against ORIGINAL run-20261013-02 Harness ===");

// ===========================================================================
// Defect A: Settlement loses composition identity and history integrity
// ===========================================================================
console.log("\n--- Testing Defect A: Settlement loses composition identity and history integrity ---");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // 1. Start composition with sessionId "s"
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's' });
  
  // 2. Preedit "X"
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "HXllo");
  
  // 3. Queue Bold targeting CompositionTarget("s")
  const cmdRes = coord.dispatchAppCommand({
    type: 'format',
    target: { type: 'CompositionTarget', sessionId: 's' },
    style: { bold: true }
  });
  console.log("Command dispatch result:", cmdRes);
  assert.equal(cmdRes.status, 'Deferred');
  
  // 4. unmark with settled: false
  const unmark1 = coord.receiveCallback(1, { type: 'unmark', settled: false });
  console.log("First unmark result (settled: false):", unmark1);
  console.log("State after first unmark:", {
    compositionState: coord.compositionState,
    activeSessionId: coord.activeSessionId,
    undoStackLen: coord.undoStack.length,
    lastSettledExtent: coord.lastSettledExtent,
    lastSettledSessionId: coord.lastSettledSessionId,
    pendingCommandsLen: coord.pendingCommands.length
  });
  
  // 5. unmark with settled: true
  const unmark2 = coord.receiveCallback(1, { type: 'unmark', settled: true });
  console.log("Second unmark result (settled: true):", unmark2);
  console.log("State after second unmark:", {
    compositionState: coord.compositionState,
    activeSessionId: coord.activeSessionId,
    undoStackLen: coord.undoStack.length,
    lastSettledExtent: coord.lastSettledExtent,
    lastSettledSessionId: coord.lastSettledSessionId,
    pendingCommandsLen: coord.pendingCommands.length
  });
  
  const boldCount = coord.docAttributes.filter(a => a && a.bold).length;
  console.log("Doc text:", coord.docText);
  console.log("Bold attribute count:", boldCount);
  
  // OBSERVED DEFECTS:
  // 1. First unmark cleared activeSessionId (became null) prematurely, and pushed a composition undo entry prematurely
  const defectA1 = (coord.activeSessionId === null) && (coord.undoStack.length >= 1);
  // 2. Second unmark changed remembered extent to [0, 0]
  const defectA2 = (coord.lastSettledExtent[0] === 0 && coord.lastSettledExtent[1] === 0);
  // 3. Two composition history entries exist!
  const compHistoryEntries = coord.undoStack.filter(e => e.group === 'Composition').length;
  const defectA3 = (compHistoryEntries === 2);
  // 4. Bold was never applied!
  const defectA4 = (boldCount === 0);
  
  if (defectA1 && defectA2 && defectA3 && defectA4) {
    console.log("CONFIRMED DEFECT A: All 4 aspects of Defect A reproduced perfectly!");
    console.log("  - First unmark prematurely cleared activeSessionId and created history");
    console.log("  - Second unmark reset extent to [0, 0]");
    console.log("  - Two composition history entries were created in undoStack");
    console.log("  - Bold was never applied to 'X' (or anywhere) due to [0, 0] extent!");
  } else {
    console.log("Defect A did not reproduce exactly as described. Diagnostics:", {
      defectA1, defectA2, defectA3, defectA4, compHistoryEntries, boldCount, extent: coord.lastSettledExtent
    });
  }
}

// ===========================================================================
// Defect B: Target expansion destroys partial-commit protection
// ===========================================================================
console.log("\n--- Testing Defect B: Target expansion destroys partial-commit protection ---");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // 1. Start composition in "Hello" at [1, 2)
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sB' });
  
  // 2. Preedit "XY", producing "HXYllo"
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.equal(coord.docText, "HXYllo");
  console.log("Doc text after preedit XY:", coord.docText);
  
  // 3. Partial commit of one unit, freezing "X"
  const pcRes = coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  console.log("Partial commit result:", pcRes);
  assert.equal(coord.frozenPrefixUnits.length, 1);
  assert.equal(coord.frozenPrefixUnits[0].char, 'X');
  console.log("Frozen units count:", coord.frozenPrefixUnits.length);
  
  // 4. Expand the remaining target to [2, 4), which excludes frozen "X" (located at [1, 2))
  // Live doc is "HXYllo":
  // index 0: 'H'
  // index 1: 'X' (frozen committed unit)
  // index 2: 'Y' (remaining candidate)
  // index 3: 'l' (suffix)
  // index 4: 'l'
  // index 5: 'o'
  // Expansion to [2, 4) covers "Yl"
  const expRes = coord.receiveCallback(1, { type: 'targetExpansion', newStart: 2, newEnd: 4 });
  console.log("Target expansion result:", expRes);
  console.log("Frozen units count after expansion:", coord.frozenPrefixUnits.length);
  
  // 5. Cancel the remaining composition
  const cancelRes = coord.receiveCallback(1, { type: 'cancel' });
  console.log("Cancel result:", cancelRes);
  console.log("Doc text after cancel:", coord.docText);
  console.log("Undo stack length after cancel:", coord.undoStack.length);
  
  // OBSERVED DEFECT:
  // Target expansion wiped frozenPrefixUnits to [], so cancel fell back to restoring
  // compositionBeforeSnapshot ("Hello"), completely erasing the committed "X"!
  // And undoStack has 0 entries!
  const defectB = (coord.docText === "Hello") && (coord.undoStack.length === 0);
  if (defectB) {
    console.log("CONFIRMED DEFECT B: Target expansion destroyed partial-commit protection!");
    console.log("  - Doc returned to 'Hello' (frozen 'X' disappeared!)");
    console.log("  - Undo stack has 0 entries (committed text lost from history!)");
  } else {
    console.log("Defect B did not reproduce as expected. Current text:", coord.docText);
  }
}

// ===========================================================================
// Defect C: Connection replacement drops composition history
// ===========================================================================
console.log("\n--- Testing Defect C: Connection replacement drops composition history ---");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // 1. Start composition in "Hello" at [1, 2)
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sC' });
  
  // 2. Preedit "X", producing "HXllo"
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  assert.equal(coord.docText, "HXllo");
  console.log("Doc text during preedit:", coord.docText);
  console.log("Pre-composition snapshot text:", coord.compositionBeforeSnapshot.text);
  
  // 3. Open a new connection
  const newEpoch = coord.openNewConnection();
  console.log("New connection epoch opened:", newEpoch);
  console.log("Doc text after new connection:", coord.docText);
  console.log("Undo stack length after new connection:", coord.undoStack.length);
  console.log("Composition before snapshot after new connection:", coord.compositionBeforeSnapshot);
  
  // OBSERVED DEFECT:
  // "HXllo" remains in docText, but compositionBeforeSnapshot was discarded (null),
  // and undoStack is empty (0 entries)! The text was accepted without attributed history!
  const defectC = (coord.docText === "HXllo") && (coord.undoStack.length === 0) && (coord.compositionBeforeSnapshot === null);
  if (defectC) {
    console.log("CONFIRMED DEFECT C: Connection replacement dropped composition history!");
    console.log("  - 'HXllo' remains in document text");
    console.log("  - compositionBeforeSnapshot was discarded");
    console.log("  - undoStack has 0 entries (no undo entry exists for accepted text!)");
  } else {
    console.log("Defect C did not reproduce as expected.");
  }
}

// ===========================================================================
// Defect D: Repeated identical preedit advancing DocumentRevision
// ===========================================================================
console.log("\n--- Testing Defect D: Repeated identical preedit advancing DocumentRevision ---");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 'sD' });
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  const revAfterFirst = coord.documentRevision;
  const sessRevAfterFirst = coord.sessionRevision;
  
  // Send identical preedit "X" again
  coord.receiveCallback(1, { type: 'preedit', text: 'X' });
  const revAfterSecond = coord.documentRevision;
  const sessRevAfterSecond = coord.sessionRevision;
  
  console.log("Revisions after first preedit:", { docRev: revAfterFirst, sessionRev: sessRevAfterFirst });
  console.log("Revisions after second identical preedit:", { docRev: revAfterSecond, sessionRev: sessRevAfterSecond });
  
  const defectD = (revAfterSecond > revAfterFirst);
  if (defectD) {
    console.log("CONFIRMED DEFECT D: Repeated identical preedit advanced DocumentRevision from", revAfterFirst, "to", revAfterSecond, "despite no semantic document change!");
  } else {
    console.log("Defect D did not reproduce as expected.");
  }
}
