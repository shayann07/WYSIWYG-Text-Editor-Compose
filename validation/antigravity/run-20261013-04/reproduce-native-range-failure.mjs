// Reproduction of Native-Range Fidelity Failure and Revision Contradiction
// Against the unchanged run-03 protocol coordinator (§1, §2)
//
// 1. Native-Range Fidelity Failure:
//    - Initial text: "Hello", selection [1, 2)
//    - Start composition at [1, 2)
//    - Preedit "XY": document becomes "HXYllo"
//    - Partial commit of 1 unit: "X" committed
//    - Native targetExpansion requests [1, 4)
//    - Coordinator returns Accepted but silently changes markedRange to [2, 4)
//    - Preedit "Q" produces "HXQlo"
//    Applying the requested replacement [1, 4) to "HXYllo" would produce "HQlo"!
//
// 2. Revision Contradiction:
//    - Before partialCommit(1): DocumentRevision = 1
//    - After partialCommit(1): DocumentRevision = 2
//    - Text ("HXYllo") and raw attributes are identical!

import assert from 'node:assert/strict';
import { ProtocolCoordinator } from '../run-20261013-03/protocol-coordinator.mjs';

console.log("=== Reproducing Codex Findings against Unchanged Run-03 Coordinator ===");

// ---------------------------------------------------------------------------
// Reproduction 1: Native-Range Fidelity Failure
// ---------------------------------------------------------------------------
console.log("\n1. Reproducing Native-Range Fidelity Failure...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  
  // Start composition
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-repro' });
  
  // Preedit "XY" -> docText becomes "HXYllo"
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  assert.equal(coord.docText, "HXYllo");
  assert.deepEqual(coord.markedRange, [1, 3]);
  console.log("   Initial preedit 'XY': docText =", JSON.stringify(coord.docText), "markedRange =", coord.markedRange);
  
  // Partial commit of 1 unit -> "X" is committed
  const pRes = coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  assert.equal(pRes.status, 'Accepted');
  assert.deepEqual(coord.markedRange, [2, 3]);
  assert.deepEqual(coord.committedExtent, [1, 2]);
  console.log("   Partial commit 1 unit: committedExtent =", coord.committedExtent, "markedRange =", coord.markedRange);
  
  // Native targetExpansion requests [1, 4)
  console.log("   Native requests targetExpansion [1, 4)...");
  const expRes = coord.receiveCallback(1, { type: 'targetExpansion', newStart: 1, newEnd: 4 });
  
  console.log("   Run-03 Coordinator Response status:", expRes.status);
  console.log("   Run-03 Coordinator markedRange after expansion:", coord.markedRange);
  
  // DEFECT OBSERVED:
  // The coordinator reported status 'Accepted', but silently clamped markedRange to [2, 4)!
  const silentlyAltered = (coord.markedRange[0] !== 1 || coord.markedRange[1] !== 4);
  console.log("   Did coordinator silently alter markedRange?", silentlyAltered);
  assert.ok(silentlyAltered, "Expected run-03 coordinator to silently alter markedRange to [2, 4)");
  assert.deepEqual(coord.markedRange, [2, 4]);
  
  // Preedit "Q" produces "HXQlo" instead of "HQlo"
  coord.receiveCallback(1, { type: 'preedit', text: 'Q' });
  console.log("   Preedit 'Q' produced docText:", JSON.stringify(coord.docText));
  assert.equal(coord.docText, "HXQlo");
  
  // Mathematical comparison with requested replacement:
  // Applying replacement [1, 4) with "Q" to "HXYllo":
  // slice(0, 1) + "Q" + slice(4) = "H" + "Q" + "lo" = "HQlo"
  const expectedTextIfFidelityPreserved = "HXYllo".slice(0, 1) + "Q" + "HXYllo".slice(4);
  console.log("   Expected text if native range [1, 4) was honored:", JSON.stringify(expectedTextIfFidelityPreserved));
  assert.equal(expectedTextIfFidelityPreserved, "HQlo");
  assert.notEqual(coord.docText, expectedTextIfFidelityPreserved);
  
  console.log("   -> CONFIRMED DEFECT 1: Native-Range Fidelity Failure reproduced!");
}

// ---------------------------------------------------------------------------
// Reproduction 2: Revision Contradiction on Metadata-Only Partial Commit
// ---------------------------------------------------------------------------
console.log("\n2. Reproducing Revision Contradiction...");
{
  const coord = new ProtocolCoordinator("Hello", null, [1, 2]);
  coord.openNewConnection();
  coord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2, sessionId: 's-rev' });
  coord.receiveCallback(1, { type: 'preedit', text: 'XY' });
  
  const revBefore = coord.documentRevision;
  const textBefore = coord.docText;
  const attrsBefore = JSON.stringify(coord.docAttributes);
  
  // Partial commit of 1 unit
  coord.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
  
  const revAfter = coord.documentRevision;
  const textAfter = coord.docText;
  const attrsAfter = JSON.stringify(coord.docAttributes);
  
  console.log(`   DocumentRevision before: ${revBefore}, after: ${revAfter}`);
  console.log(`   Text before: ${JSON.stringify(textBefore)}, after: ${JSON.stringify(textAfter)} (identical: ${textBefore === textAfter})`);
  console.log(`   Attributes identical: ${attrsBefore === attrsAfter}`);
  
  // DEFECT OBSERVED:
  // Text and attributes did not change at all, but DocumentRevision advanced from 1 to 2!
  assert.equal(textBefore, textAfter);
  assert.equal(attrsBefore, attrsAfter);
  assert.equal(revBefore, 1);
  assert.equal(revAfter, 2);
  
  console.log("   -> CONFIRMED DEFECT 2: Revision Contradiction reproduced (DocumentRevision advanced without semantic document change)!");
}

console.log("\n=== All Target Defects Reproduced Successfully against Run-03 Baseline ===");
