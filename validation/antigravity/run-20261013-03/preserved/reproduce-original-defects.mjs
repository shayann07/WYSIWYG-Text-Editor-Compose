import assert from 'node:assert/strict';
import { ProtocolCoordinator } from '../run-20261013-01/protocol-coordinator.mjs';

console.log("=== Reproducing Coordinator Defects Against ORIGINAL run-20261013-01 Harness ===");

// ---------------------------------------------------------------------------
// Defect A: Settlement bypass
// ---------------------------------------------------------------------------
console.log("\n--- Testing Defect A: Settlement bypass ---");
{
  const coordinator = new ProtocolCoordinator();
  coordinator.openNewConnection();
  coordinator.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 's1' });
  coordinator.receiveCallback(1, { type: 'preedit', text: 'nihao' });

  // First format command enters AwaitingSettlement and is deferred
  const r1 = coordinator.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  console.log("Command 1 dispatch result:", r1);
  console.log("State after Command 1:", coordinator.compositionState);

  // Second format command
  const r2 = coordinator.dispatchAppCommand({ type: 'format', target: [0, 2], style: { italic: true } });
  console.log("Command 2 dispatch result:", r2);
  console.log("State after Command 2:", coordinator.compositionState);
  console.log("Pending commands queue length:", coordinator.pendingCommands.length);

  // OBSERVED DEFECT:
  // r2 was NOT deferred; it executed immediately while AwaitingSettlement!
  if (r2.status === 'Applied') {
    console.log("CONFIRMED DEFECT A: Command 2 bypassed settlement and executed immediately while state was AwaitingSettlement!");
  } else {
    console.log("Defect A did not reproduce as expected.");
  }

  // Also check preedit callback resetting AwaitingSettlement to Composing:
  coordinator.receiveCallback(1, { type: 'preedit', text: 'nihaoma' });
  console.log("State after subsequent preedit:", coordinator.compositionState);
  if (coordinator.compositionState === 'Composing') {
    console.log("CONFIRMED DEFECT A.2: Subsequent preedit callback reverted state from AwaitingSettlement back to Composing, losing settlement status!");
  }
}

// ---------------------------------------------------------------------------
// Defect B: Wrong CompositionTarget extent
// ---------------------------------------------------------------------------
console.log("\n--- Testing Defect B: Wrong CompositionTarget extent ---");
{
  const coordinator = new ProtocolCoordinator();
  coordinator.openNewConnection();
  // Initial document "Hello" (length 5)
  // Compose only [1, 2) ("e")
  coordinator.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's2' });
  coordinator.receiveCallback(1, { type: 'preedit', text: 'X' });
  console.log("Text during preedit:", coordinator.docText); // "HXllo"

  // Queue Bold targeting this composition
  const r1 = coordinator.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
  console.log("Format command queued:", r1);

  // Commit "Y" with finish: true
  coordinator.receiveCallback(1, { type: 'commit', text: 'Y', finish: true });
  console.log("Text after commit:", coordinator.docText); // "HYllo"

  const boldIndices = [];
  for (let i = 0; i < coordinator.docText.length; i++) {
    if (coordinator.docAttributes[i]?.bold) {
      boldIndices.push(i);
    }
  }
  console.log("Indices with bold attribute:", boldIndices);

  // OBSERVED DEFECT:
  // Expected: only index 1 ('Y') should be bold.
  // Observed: indices 0, 1, 2, 3, 4 ('HYllo' everywhere) are bold!
  if (boldIndices.length === coordinator.docText.length && boldIndices.includes(0)) {
    console.log("CONFIRMED DEFECT B: Entire document [0, 5) received Bold instead of only the committed composition extent [1, 2)!");
  } else {
    console.log("Defect B did not reproduce as expected.");
  }
}

// ---------------------------------------------------------------------------
// Defect C: Unmark does nothing
// ---------------------------------------------------------------------------
console.log("\n--- Testing Defect C: Unmark does nothing ---");
{
  const coordinator = new ProtocolCoordinator();
  coordinator.openNewConnection();
  coordinator.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 's3' });
  coordinator.receiveCallback(1, { type: 'preedit', text: 'abc' });
  console.log("Preedit state:", {
    state: coordinator.compositionState,
    markedRange: coordinator.markedRange,
    undoStackLen: coordinator.undoStack.length
  });

  const unmarkRes = coordinator.receiveCallback(1, { type: 'unmark' });
  console.log("Unmark callback result:", unmarkRes);
  console.log("Post-unmark coordinator state:", {
    state: coordinator.compositionState,
    markedRange: coordinator.markedRange,
    undoStackLen: coordinator.undoStack.length
  });

  // OBSERVED DEFECT:
  // State is still 'Composing', markedRange is still active [0, 3], no undo group created!
  if (coordinator.compositionState === 'Composing' && coordinator.markedRange !== null && coordinator.undoStack.length === 0) {
    console.log("CONFIRMED DEFECT C: Unmark callback did nothing! State remains Composing, markedRange remains active, and no composition undo group exists.");
  } else {
    console.log("Defect C did not reproduce as expected.");
  }
}
