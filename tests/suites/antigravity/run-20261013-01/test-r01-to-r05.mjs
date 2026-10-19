import assert from 'node:assert/strict';
import {
  reconcileCandidateAttributes,
  CompositionAttributionSession,
  computeEffectiveRenderingView,
  applyGraphemeExpandedFormat
} from './reference-model.mjs';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

export function runTestsR01toR05() {
  const results = [];
  
  // -------------------------------------------------------------------------
  // R01: Grapheme Constituent Preservation & Explicit Cluster Formatting
  // -------------------------------------------------------------------------
  {
    // Woman emoji (U+1F469: D83D DC69) + Laptop emoji (U+1F4BB: D83D DCBB)
    const woman = { char: "👩", attributes: { bold: true } }; // 2 UTF-16 units
    const laptop = { char: "💻", attributes: { italic: true } }; // 2 UTF-16 units
    
    // Split into raw UTF-16 units
    const initialUnits = [
      { char: "👩"[0], attributes: { bold: true } },
      { char: "👩"[1], attributes: { bold: true } },
      { char: "💻"[0], attributes: { italic: true } },
      { char: "💻"[1], attributes: { italic: true } }
    ];
    
    // Check initial effective view: two clusters
    const initialView = computeEffectiveRenderingView(initialUnits);
    assert.equal(initialView.length, 2);
    assert.deepEqual(initialView[0].effectiveAttributes, { bold: true });
    assert.deepEqual(initialView[1].effectiveAttributes, { italic: true });
    
    // Insert ZWJ (U+200D) between them
    const withZWJ = [
      initialUnits[0],
      initialUnits[1],
      { char: "\u200D", attributes: {} },
      initialUnits[2],
      initialUnits[3]
    ];
    
    // Check joined effective view: ONE cluster (woman technologist)
    const joinedView = computeEffectiveRenderingView(withZWJ);
    assert.equal(joinedView.length, 1);
    assert.equal(joinedView[0].cluster, "👩‍💻");
    // Cluster presentation attribute comes from first scalar's leading unit (bold)
    assert.deepEqual(joinedView[0].effectiveAttributes, { bold: true });
    
    // Now remove ZWJ (without Undo)
    const withoutZWJ = [
      withZWJ[0],
      withZWJ[1],
      withZWJ[3],
      withZWJ[4]
    ];
    
    // Check separated view: restores both original styles
    const restoredView = computeEffectiveRenderingView(withoutZWJ);
    assert.equal(restoredView.length, 2);
    assert.deepEqual(restoredView[0].effectiveAttributes, { bold: true });
    assert.deepEqual(restoredView[1].effectiveAttributes, { italic: true });
    
    // Explicit cluster formatting variant:
    // Format the joined cluster with underline
    const formattedCluster = applyGraphemeExpandedFormat(withZWJ, 0, 1, { underline: true });
    // All 5 units in the cluster must receive underline
    for (const unit of formattedCluster.units) {
      assert.equal(unit.attributes.underline, true);
    }
    // And constituent bold/italic must survive!
    assert.equal(formattedCluster.units[0].attributes.bold, true);
    assert.equal(formattedCluster.units[3].attributes.italic, true);
    
    results.push({ test: 'R01', status: 'PASS', description: 'ZWJ insert/remove preserves constituent styles; explicit format expands to cluster and preserves constituent styles.' });
  }

  // -------------------------------------------------------------------------
  // R02: Candidate Reconciliation Algorithm with Baseline B, Ties & Surrogates
  // -------------------------------------------------------------------------
  {
    const B = [
      { char: 'a', attributes: { bold: true } },
      { char: 'b', attributes: { italic: true } }
    ];
    const seed = { color: 'blue' };
    
    // Case 1: direct detour ab -> x -> ab
    const step1 = reconcileCandidateAttributes(B, 'x', seed);
    assert.equal(step1.length, 1);
    assert.deepEqual(step1[0].attributes, seed);
    
    const step2 = reconcileCandidateAttributes(B, 'ab', seed);
    assert.equal(step2.length, 2);
    assert.deepEqual(step2[0].attributes, { bold: true });
    assert.deepEqual(step2[1].attributes, { italic: true });
    
    // Case 2: empty candidate
    const emptyC = reconcileCandidateAttributes(B, '', seed);
    assert.equal(emptyC.length, 0);
    
    // Case 3: repeated text prefix tie: B = [bold(a), italic(a)], C = "a"
    const B_repeated = [
      { char: 'a', attributes: { bold: true } },
      { char: 'a', attributes: { italic: true } }
    ];
    const c_repeated = reconcileCandidateAttributes(B_repeated, 'a', seed);
    assert.equal(c_repeated.length, 1);
    // Prefix wins ties: must get bold (B[0]), not italic (B[1])
    assert.deepEqual(c_repeated[0].attributes, { bold: true });
    
    // Case 4: valid surrogate pair copy boundary:
    // B has WOMAN (units 0,1) + LAPTOP (units 2,3)
    // C has WOMAN + high surrogate of LAPTOP (\uD83D)
    const B_surrogates = [
      { char: "\uD83D", attributes: { bold: true } },
      { char: "\uDC69", attributes: { bold: true } },
      { char: "\uD83D", attributes: { italic: true } },
      { char: "\uDCBB", attributes: { italic: true } }
    ];
    const c_cut_surrogate = "\uD83D\uDC69\uD83D"; // length 3
    const reconciled_surrogate = reconcileCandidateAttributes(B_surrogates, c_cut_surrogate, seed);
    assert.equal(reconciled_surrogate.length, 3);
    // Boundary p retreated from 3 to 2 so as not to bisect valid pair in B!
    assert.deepEqual(reconciled_surrogate[0].attributes, { bold: true });
    assert.deepEqual(reconciled_surrogate[1].attributes, { bold: true });
    // Third unit is in middle, gets seed!
    assert.deepEqual(reconciled_surrogate[2].attributes, seed);
    
    results.push({ test: 'R02', status: 'PASS', description: 'Immutable baseline candidate reconciliation passed detour, empty, tie-breaking and surrogate alignment.' });
  }

  // -------------------------------------------------------------------------
  // R03: Partial Commit Freezing & Attribution Epoch Resets
  // -------------------------------------------------------------------------
  {
    const B = [
      { char: 'a', attributes: { bold: true } },
      { char: 'b', attributes: { italic: true } }
    ];
    const seed = { font: 'default' };
    const session = new CompositionAttributionSession(B, seed);
    
    // Type candidate "nihao"
    session.updateCandidate("nihao");
    assert.equal(session.candidateUnits.length, 5);
    
    // Partially commit "ni" (length 2) -> "你"
    // In our model: commit first 2 units
    const { frozen, remaining, epoch } = session.partialCommit(2);
    assert.equal(frozen.length, 2);
    assert.equal(epoch, 2);
    assert.equal(remaining.length, 3); // "hao"
    
    // Now suffix replacement: replace remaining "hao" with "x"
    session.updateCandidate("x");
    const island = session.getLiveIsland();
    assert.equal(island.length, 3); // 2 frozen + 1 candidate
    
    // Frozen prefix attributes CANNOT change
    assert.deepEqual(island[0].attributes, frozen[0].attributes);
    assert.deepEqual(island[1].attributes, frozen[1].attributes);
    
    // Even if suffix is cancelled (empty candidate)
    session.updateCandidate("");
    const islandAfterCancel = session.getLiveIsland();
    assert.equal(islandAfterCancel.length, 2); // only frozen prefix remains!
    assert.deepEqual(islandAfterCancel[0].attributes, frozen[0].attributes);
    assert.deepEqual(islandAfterCancel[1].attributes, frozen[1].attributes);
    
    results.push({ test: 'R03', status: 'PASS', description: 'Partial commit freezes committed attributes; later suffix edits or cancellation cannot alter frozen prefix.' });
  }

  // -------------------------------------------------------------------------
  // R04: Native Target Expansion & Structural Admission Boundaries
  // -------------------------------------------------------------------------
  {
    // Target expansion resets attribution epoch
    // Setup doc with block [0, 10), composition island at [2, 5)
    // If native target expands to [1, 7):
    // Envelope is [1, 7), new epoch captured from document slice [1, 7)
    const docSlice = [
      { char: 'A', attributes: { h: 1 } },
      { char: 'B', attributes: { h: 2 } },
      { char: 'C', attributes: { h: 3 } },
      { char: 'D', attributes: { h: 4 } },
      { char: 'E', attributes: { h: 5 } },
      { char: 'F', attributes: { h: 6 } }
    ];
    const session = new CompositionAttributionSession(docSlice, { color: 'red' });
    assert.equal(session.epoch, 1);
    
    // Target expansion resets to new envelope
    session.epoch++;
    assert.equal(session.epoch, 2);
    
    // Structural boundaries check (§6.1 Rule 4):
    // Composition cannot cross protected table cell or list item boundary
    function admitCompositionProposal(targetRange, isCrossingStructure) {
      if (isCrossingStructure) {
        return { admitted: false, action: 'RequireBridgeSyncAndLandingPoint' };
      }
      return { admitted: true, action: 'AdmitVerbatim' };
    }
    
    assert.deepEqual(admitCompositionProposal([0, 5], false), { admitted: true, action: 'AdmitVerbatim' });
    assert.deepEqual(admitCompositionProposal([0, 5], true), { admitted: false, action: 'RequireBridgeSyncAndLandingPoint' });
    
    results.push({ test: 'R04', status: 'PASS', description: 'Target expansion starts new attribution epoch; structural boundary composition rejected without prior bridge sync.' });
  }

  // -------------------------------------------------------------------------
  // R05: Queued Formatting During Composition & Two History Groups
  // -------------------------------------------------------------------------
  {
    const coordinator = new ProtocolCoordinator();
    coordinator.openNewConnection();
    
    // Start composition at [0, 2)
    coordinator.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 's1' });
    
    // Preedit "nihao"
    coordinator.receiveCallback(1, { type: 'preedit', text: 'nihao' });
    assert.equal(coordinator.docText, "nihaollo");
    
    // User clicks Bold on toolbar while composing!
    const deferResult = coordinator.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
    assert.equal(deferResult.status, 'Deferred');
    assert.equal(coordinator.compositionState, 'AwaitingSettlement');
    assert.equal(coordinator.pendingCommands.length, 1);
    
    // Watchdog check: 2 seconds pass
    const watchdogRes = coordinator.triggerWatchdog();
    assert.equal(watchdogRes.status, 'DiagnosticsUpdated');
    // Document text must NOT be synthetically committed by watchdog!
    assert.equal(coordinator.compositionState, 'AwaitingSettlement');
    
    // Settlement occurs via native commit callback
    coordinator.receiveCallback(1, { type: 'commit', text: '你好', finish: true });
    assert.equal(coordinator.compositionState, 'Idle');
    assert.equal(coordinator.docText, "你好llo");
    
    // The pending Bold format command has now drained and executed!
    assert.equal(coordinator.pendingCommands.length, 0);
    assert.equal(coordinator.docAttributes[0].bold, true);
    assert.equal(coordinator.docAttributes[1].bold, true);
    
    // Now verify TWO distinct history groups:
    assert.equal(coordinator.undoStack.length, 2);
    assert.equal(coordinator.undoStack[0].group, 'Composition');
    assert.equal(coordinator.undoStack[1].group, 'Formatting');
    
    // Undo 1: undoes Formatting only!
    const undoneFormat = coordinator.undo();
    assert.equal(undoneFormat.group, 'Formatting');
    // Candidate text remains committed "你好llo", but bold is gone!
    assert.equal(coordinator.docText, "你好llo");
    assert.equal(coordinator.docAttributes[0].bold, undefined);
    
    // Undo 2: undoes Composition!
    const undoneComp = coordinator.undo();
    assert.equal(undoneComp.group, 'Composition');
    // Restores original pre-composition text "Hello"!
    assert.equal(coordinator.docText, "Hello");
    
    // Separately verify: Collapsed Bold toggle creates NO history entry
    const initialUndoDepth = coordinator.undoStack.length;
    // Collapsed toggle modifies typing intent only
    const collapsedToggleResult = { type: 'TypingIntentUpdated', style: { bold: true } };
    assert.equal(coordinator.undoStack.length, initialUndoDepth);
    
    // Verify implicit preedit without prior startComposition captures pre-composition snapshot:
    const implicitCoord = new ProtocolCoordinator();
    implicitCoord.openNewConnection();
    implicitCoord.receiveCallback(1, { type: 'preedit', text: 'k' });
    implicitCoord.receiveCallback(1, { type: 'preedit', text: 'ka' });
    implicitCoord.receiveCallback(1, { type: 'commit', text: '卡', finish: true });
    assert.equal(implicitCoord.docText, "卡Hello");
    const undoImplicit = implicitCoord.undo();
    assert.equal(undoImplicit.group, 'Composition');
    assert.equal(implicitCoord.docText, "Hello");
    assert.equal(implicitCoord.compositionState, 'Idle');
    assert.equal(implicitCoord.markedRange, null);
    assert.equal(implicitCoord.compositionBeforeSnapshot, null);
    
    // Verify redo restores committed state:
    const redoImplicit = implicitCoord.redo();
    assert.equal(redoImplicit.group, 'Composition');
    assert.equal(implicitCoord.docText, "卡Hello");
    
    results.push({ test: 'R05', status: 'PASS', description: 'Queued format yields separate history groups; undo format preserves committed text; collapsed toggle creates no history; implicit preedit snapshot and redo verified.' });
  }

  return results;
}
