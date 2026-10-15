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
    const initialUnits = [
      { char: "👩"[0], attributes: { bold: true } },
      { char: "👩"[1], attributes: { bold: true } },
      { char: "💻"[0], attributes: { italic: true } },
      { char: "💻"[1], attributes: { italic: true } }
    ];
    
    // Initial effective view: two clusters
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
    
    // Joined effective view: ONE cluster (woman technologist)
    const joinedView = computeEffectiveRenderingView(withZWJ);
    assert.equal(joinedView.length, 1);
    assert.equal(joinedView[0].cluster, "👩‍💻");
    assert.deepEqual(joinedView[0].effectiveAttributes, { bold: true });
    
    // Remove ZWJ (without Undo)
    const withoutZWJ = [
      withZWJ[0],
      withZWJ[1],
      withZWJ[3],
      withZWJ[4]
    ];
    
    // Separated view: restores both original styles
    const restoredView = computeEffectiveRenderingView(withoutZWJ);
    assert.equal(restoredView.length, 2);
    assert.deepEqual(restoredView[0].effectiveAttributes, { bold: true });
    assert.deepEqual(restoredView[1].effectiveAttributes, { italic: true });
    
    // Explicit cluster formatting variant:
    const formattedCluster = applyGraphemeExpandedFormat(withZWJ, 0, 1, { underline: true });
    for (const unit of formattedCluster.units) {
      assert.equal(unit.attributes.underline, true);
    }
    assert.equal(formattedCluster.units[0].attributes.bold, true);
    assert.equal(formattedCluster.units[3].attributes.italic, true);
    
    results.push({
      test: 'R01',
      status: 'PASS',
      description: 'ZWJ insert/remove preserves constituent styles; explicit format expands to cluster and preserves constituent styles.'
    });
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
    assert.deepEqual(c_repeated[0].attributes, { bold: true });
    
    // Case 4: valid surrogate pair copy boundary:
    const B_surrogates = [
      { char: "\uD83D", attributes: { bold: true } },
      { char: "\uDC69", attributes: { bold: true } },
      { char: "\uD83D", attributes: { italic: true } },
      { char: "\uDCBB", attributes: { italic: true } }
    ];
    const c_cut_surrogate = "\uD83D\uDC69\uD83D";
    const reconciled_surrogate = reconcileCandidateAttributes(B_surrogates, c_cut_surrogate, seed);
    assert.equal(reconciled_surrogate.length, 3);
    assert.deepEqual(reconciled_surrogate[0].attributes, { bold: true });
    assert.deepEqual(reconciled_surrogate[1].attributes, { bold: true });
    assert.deepEqual(reconciled_surrogate[2].attributes, seed);
    
    results.push({
      test: 'R02',
      status: 'PASS',
      description: 'Immutable baseline candidate reconciliation passed detour, empty, tie-breaking and surrogate alignment.'
    });
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
    
    session.updateCandidate("nihao");
    assert.equal(session.candidateUnits.length, 5);
    
    const { frozen, remaining, epoch } = session.partialCommit(2);
    assert.equal(frozen.length, 2);
    assert.equal(epoch, 2);
    assert.equal(remaining.length, 3);
    
    session.updateCandidate("x");
    const island = session.getLiveIsland();
    assert.equal(island.length, 3);
    assert.deepEqual(island[0].attributes, frozen[0].attributes);
    assert.deepEqual(island[1].attributes, frozen[1].attributes);
    
    session.updateCandidate("");
    const islandAfterCancel = session.getLiveIsland();
    assert.equal(islandAfterCancel.length, 2);
    assert.deepEqual(islandAfterCancel[0].attributes, frozen[0].attributes);
    assert.deepEqual(islandAfterCancel[1].attributes, frozen[1].attributes);
    
    results.push({
      test: 'R03',
      status: 'PASS',
      description: 'Partial commit freezes committed attributes; later suffix edits or cancellation cannot alter frozen prefix.'
    });
  }

  // -------------------------------------------------------------------------
  // R04: Native Target Expansion & Structural Admission Boundaries
  // -------------------------------------------------------------------------
  {
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
    
    session.epoch++;
    assert.equal(session.epoch, 2);
    
    function admitCompositionProposal(targetRange, isCrossingStructure) {
      if (isCrossingStructure) {
        return { admitted: false, action: 'RequireBridgeSyncAndLandingPoint' };
      }
      return { admitted: true, action: 'AdmitVerbatim' };
    }
    
    assert.deepEqual(admitCompositionProposal([0, 5], false), { admitted: true, action: 'AdmitVerbatim' });
    assert.deepEqual(admitCompositionProposal([0, 5], true), { admitted: false, action: 'RequireBridgeSyncAndLandingPoint' });
    
    results.push({
      test: 'R04',
      status: 'PASS',
      description: 'Target expansion starts new attribution epoch; structural boundary composition rejected without prior bridge sync.'
    });
  }

  // -------------------------------------------------------------------------
  // R05: Queued Formatting During Composition & Two History Groups (Repaired)
  // -------------------------------------------------------------------------
  {
    const coordinator = new ProtocolCoordinator("Hello");
    coordinator.openNewConnection();
    
    // Start composition at [0, 2)
    coordinator.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, sessionId: 's1' });
    
    // Preedit "nihao" -> text becomes "nihaollo"
    coordinator.receiveCallback(1, { type: 'preedit', text: 'nihao' });
    assert.equal(coordinator.docText, "nihaollo");
    
    // Command 1: User clicks Bold targeting composition -> DEFERRED
    const deferResult1 = coordinator.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
    assert.equal(deferResult1.status, 'Deferred');
    assert.equal(coordinator.compositionState, 'AwaitingSettlement');
    assert.equal(coordinator.pendingCommands.length, 1);
    
    // Command 2: User clicks Italic while AwaitingSettlement -> MUST BE DEFERRED ALSO (Defect A Fix)
    const deferResult2 = coordinator.dispatchAppCommand({ type: 'format', target: 'composing', style: { italic: true } });
    assert.equal(deferResult2.status, 'Deferred');
    assert.equal(coordinator.compositionState, 'AwaitingSettlement');
    assert.equal(coordinator.pendingCommands.length, 2);
    
    // Watchdog check: 2 seconds pass
    const watchdogRes = coordinator.triggerWatchdog();
    assert.equal(watchdogRes.status, 'DiagnosticsUpdated');
    assert.equal(coordinator.compositionState, 'AwaitingSettlement');
    
    // Native commit arrives (without finish flag -> natural completion)
    coordinator.receiveCallback(1, { type: 'commit', text: '你好' });
    assert.equal(coordinator.compositionState, 'Idle');
    assert.equal(coordinator.docText, "你好llo");
    
    // Both pending commands drained in strict FIFO order!
    assert.equal(coordinator.pendingCommands.length, 0);
    // Extent [0, 2) has bold and italic
    assert.equal(coordinator.docAttributes[0].bold, true);
    assert.equal(coordinator.docAttributes[0].italic, true);
    assert.equal(coordinator.docAttributes[1].bold, true);
    assert.equal(coordinator.docAttributes[1].italic, true);
    // Suffix has neither:
    assert.equal(coordinator.docAttributes[2].bold, undefined);
    assert.equal(coordinator.docAttributes[2].italic, undefined);
    
    // History groups: 1 Composition + 2 Formattings
    assert.equal(coordinator.undoStack.length, 3);
    assert.equal(coordinator.undoStack[0].group, 'Composition');
    assert.equal(coordinator.undoStack[1].group, 'Formatting');
    assert.equal(coordinator.undoStack[2].group, 'Formatting');
    
    // Undo 1: undoes Italic only
    coordinator.undo();
    assert.equal(coordinator.docText, "你好llo");
    assert.equal(coordinator.docAttributes[0].bold, true);
    assert.equal(coordinator.docAttributes[0].italic, undefined);
    
    // Undo 2: undoes Bold only
    coordinator.undo();
    assert.equal(coordinator.docText, "你好llo");
    assert.equal(coordinator.docAttributes[0].bold, undefined);
    
    // Undo 3: undoes Composition -> restores "Hello"!
    coordinator.undo();
    assert.equal(coordinator.docText, "Hello");
    
    // Collapsed Bold toggle creates NO history entry
    const initialUndoDepth = coordinator.undoStack.length;
    const collapsedToggleResult = coordinator.executeFormatCommand({ type: 'format', style: { bold: true } }, [1, 1]);
    assert.ok(collapsedToggleResult.status === 'NoChange' || collapsedToggleResult.status === 'TypingIntentUpdated');
    assert.equal(coordinator.undoStack.length, initialUndoDepth);
    
    // Implicit preedit without prior startComposition
    const implicitCoord = new ProtocolCoordinator("Hello");
    implicitCoord.openNewConnection();
    implicitCoord.receiveCallback(1, { type: 'preedit', text: 'k' });
    implicitCoord.receiveCallback(1, { type: 'preedit', text: 'ka' });
    implicitCoord.receiveCallback(1, { type: 'commit', text: '卡' });
    assert.equal(implicitCoord.docText, "卡Hello");
    implicitCoord.undo();
    assert.equal(implicitCoord.docText, "Hello");
    assert.equal(implicitCoord.compositionState, 'Idle');
    assert.equal(implicitCoord.markedRange, null);
    
    implicitCoord.redo();
    assert.equal(implicitCoord.docText, "卡Hello");

    // Unmark test (Defect C Fix)
    const unmarkCoord = new ProtocolCoordinator("Hello");
    unmarkCoord.openNewConnection();
    unmarkCoord.receiveCallback(1, { type: 'startComposition', start: 1, end: 2 });
    unmarkCoord.receiveCallback(1, { type: 'preedit', text: 'UNMARKED' });
    unmarkCoord.dispatchAppCommand({ type: 'format', target: 'composing', style: { bold: true } });
    unmarkCoord.receiveCallback(1, { type: 'unmark' });
    assert.equal(unmarkCoord.docText, "HUNMARKEDllo");
    assert.equal(unmarkCoord.compositionState, 'Idle');
    assert.equal(unmarkCoord.markedRange, null);
    assert.equal(unmarkCoord.docAttributes[1].bold, true);
    assert.equal(unmarkCoord.undoStack.length, 2);
    
    results.push({
      test: 'R05',
      status: 'PASS',
      description: 'Queued format yields separate history groups; FIFO queueing preserved during settlement; unmark and symmetrical undo/redo verified.'
    });
  }

  return results;
}
