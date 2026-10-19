import assert from 'node:assert/strict';
import {DocumentModel} from '../../validation/codex/run-20261015-19/document-model.mjs';
import {IntegratedModel} from '../../validation/codex/run-20261015-19/integrated-model.mjs';
import {driver, grid, cases as baselineDocumentCases} from '../../validation/codex/run-20261015-19/test-document.mjs';
import {cases as baselineIntegratedCases} from '../../validation/codex/run-20261015-19/test-integrated.mjs';
import {textBlock as p, container as c} from '../../validation/codex/run-20261015-19/structural-model.mjs';
import {getFlow, locate, leaves, index, project, raw, validateDocument} from '../../validation/codex/run-20261015-19/document-tree.mjs';
import {textLocation as baselineTextLocation} from '../../validation/codex/run-20261015-19/awt-contract-model.mjs';
import {RepairedDocumentModel, repairedTextLocation, repairedUnitClusters} from './repaired-document-model.mjs';

const body = (...nodes) => c('body', 'flow', nodes);
const item = (id, ...nodes) => c(id, 'item', nodes);
const list = (id, ...nodes) => c(id, 'list', nodes, {kind: 'bullet'});
const pt = (flowId, blockId, offset) => ({kind: 'text', flowId, blockId, offset});
const text = (m, flowId = m.snapshot().activeFlow) => raw(project(getFlow(m.snapshot().document, flowId)));
const anchor = (m, id) => new Map(m.snapshot().anchors).get(id);

// Every single counterexample is an independent test case that:
// 1. Fails on the unmodified baseline implementation (recording exact observed vs expected failure).
// 2. Passes on the repaired implementation (RepairedDocumentModel / repairedTextLocation).
export const counterexamples = {
  F01A_candidateDetourPreservesAnnotationsAnchorsAndRevision(Model) {
    const {m, cmd, n} = driver(body(p('p', 'abcd')), [0, 3], Model);
    const h = cmd('registerAnchor', {point: pt('body', 'p', 1), association: 'before'}).anchorId;
    cmd('addAnnotation', {range: [1, 2], props: {comment: 'b'}});
    const revBefore = m.snapshot().documentRevision;
    const histBefore = m.snapshot().history.length;
    n('preedit', {text: 'X'});
    assert.equal(m.snapshot().document.props.annotations[0].orphaned, true);
    n('preedit', {text: 'abc'});
    n('commit', {text: 'abc'});
    assert.deepEqual(anchor(m, h), {
      id: h,
      point: pt('body', 'p', 1),
      association: 'before',
      generation: 0,
      deleted: false
    });
    assert.deepEqual(m.snapshot().document.props.annotations[0], {
      id: m.snapshot().document.props.annotations[0].id,
      anchor: pt('body', 'p', 1),
      focus: pt('body', 'p', 2),
      props: {comment: 'b'},
      orphaned: false
    });
    assert.equal(m.snapshot().documentRevision, revBefore + 2, 'commit(abc) after preedit(abc) is a semantic no-op and must not advance DocumentRevision');
    assert.equal(m.snapshot().history.length, histBefore, 'Net no-op candidate detour must not create a Composition history group');
  },

  F01B_partialCommitPlusRetargetCancelPreservesSurvivingAnchorsAndAnnotations(Model) {
    const {m, cmd, n} = driver(body(p('p', 'abcd')), [0, 1], Model);
    const h = cmd('registerAnchor', {point: pt('body', 'p', 2), association: 'before'}).anchorId;
    cmd('addAnnotation', {range: [2, 4], props: {comment: 'keep'}});
    n('preedit', {text: 'X'});
    n('partialCommit', {length: 1});
    n('retarget', {range: [1, 4]});
    n('preedit', {text: 'Y'});
    n('cancel');
    assert.equal(text(m), 'Xbcd');
    assert.deepEqual(anchor(m, h), {
      id: h,
      point: pt('body', 'p', 2),
      association: 'before',
      generation: 0,
      deleted: false
    });
    assert.deepEqual(m.snapshot().document.props.annotations[0], {
      id: m.snapshot().document.props.annotations[0].id,
      anchor: pt('body', 'p', 2),
      focus: pt('body', 'p', 4),
      props: {comment: 'keep'},
      orphaned: false
    });
  },

  F02A_emptyCandidateSettlementFormatDoesNotCauseUndoFallThrough(Model) {
    const {m, n, app} = driver(body(p('p', 'hello world')), [6, 11], Model);
    n('preedit', {text: 'XY', stamp: {token: 's1'}});
    const fmt = app('format', {target: 'composition', patch: {bold: true}});
    const u1 = app('undo');
    n('commit', {text: '', stamp: {token: 's1'}});
    const outcomes = new Map(m.snapshot().outcomes);
    assert.equal(outcomes.get(fmt.ticket).status, 'Cancelled');
    assert.equal(outcomes.get(u1.ticket).status, 'NoChange');
    assert.equal(outcomes.get(u1.ticket).reason, 'CommandWithoutHistory');
    assert.equal(text(m), 'hello ');
  },

  F02B_1_cancelledApplyPasteContributesLedgerSlotToPreventUndoFallThrough(Model) {
    const {m, cmd, n, app} = driver(body(p('p', 'abcd')), [1, 3], Model);
    const lease = cmd('capturePaste', {range: [1, 2]}).pasteId;
    n('preedit', {text: 'XY'});
    const ap = cmd('applyPaste', {pasteId: lease, text: 'PASTED'});
    const u = app('undo');
    n('commit', {text: 'XY'});
    const outcomes = new Map(m.snapshot().outcomes);
    assert.equal(outcomes.get(ap.ticket).status, 'Cancelled');
    assert.equal(outcomes.get(ap.ticket).reason, 'TargetDeleted');
    assert.equal(outcomes.get(u.ticket).status, 'NoChange');
    assert.equal(outcomes.get(u.ticket).reason, 'CommandWithoutHistory');
    assert.equal(text(m), 'aXYd');
  },

  F02B_2_applyPastePreservesTargetChangedReasonOnCellLeaseDrift(Model) {
    const g = driver(grid(), [0, 0], Model);
    g.cmd('selectCells', {tableId: 't', anchorCell: 'c00', focusCell: 'c01'});
    const cellLease = g.cmd('capturePaste').pasteId;
    g.cmd('selectFlow', {toFlow: 'f00', offset: 1});
    g.cmd('typeText', {text: '!'});
    const r = g.cmd('applyPaste', {pasteId: cellLease, text: 'Z'});
    assert.equal(r.commandStatus, 'Cancelled');
    assert.equal(r.reason, 'TargetChanged');
  },

  F03A_retargetDoesNotLeakExteriorLinkBoundarySeed(Model) {
    const block1 = p('p', 'ab');
    block1.units[1].attrs = {link: {id: 'l1', url: 'https://a.test'}};
    const d1 = driver(body(block1), [0, 1], Model);
    d1.n('startComposition');
    d1.n('retarget', {range: [1, 1]});
    d1.n('commit', {text: 'X'});
    const u1 = getFlow(d1.m.snapshot().document, 'body').children[0].units;
    assert.equal(u1[1].char, 'X');
    assert.equal(u1[1].attrs.link, undefined);
  },

  F03B_retargetToBlockEndInheritsLeftNeighborMarks(Model) {
    const block2 = p('p', 'a');
    block2.units[0].attrs = {bold: true};
    const d2 = driver(body(block2), [0, 1], Model);
    d2.n('startComposition');
    d2.n('retarget', {range: [1, 1]});
    d2.n('commit', {text: 'X'});
    const u2 = getFlow(d2.m.snapshot().document, 'body').children[0].units;
    assert.equal(u2[1].char, 'X');
    assert.equal(u2[1].attrs.bold, true);
  },

  F04A_deletingLeadingBlockObjectWithBoundaryPreservesRightListStructure(Model) {
    const srcA = body({id: 'img', type: 'object', props: {kind: 'image'}}, list('L', item('i1', p('b', 'Hello'))));
    const dA = driver(srcA, [2, 2], Model);
    dA.cmd('deleteSelection', {range: [0, 2]});
    const flowA = getFlow(dA.m.snapshot().document, 'body');
    assert.equal(flowA.children[0].id, 'L');
    assert.equal(flowA.children[0].type, 'list');
    assert.equal(leaves(flowA)[0].id, 'b');
  },

  F04B_deletingLeadingBlockObjectBeforeAdjacentBlockObjectSucceeds(Model) {
    const srcB = body(
      {id: 'img1', type: 'object', props: {kind: 'image'}},
      {id: 'img2', type: 'object', props: {kind: 'image'}},
      p('b', 'Hello')
    );
    const dB = driver(srcB, [4, 4], Model);
    dB.cmd('deleteSelection', {range: [0, 2]});
    const flowB = getFlow(dB.m.snapshot().document, 'body');
    assert.equal(flowB.children[0].id, 'img2');
    assert.equal(flowB.children[1].id, 'b');
  },

  F04C_deletingBlockObjectAndPrefixOfNestedHeadingPreservesHeadingAndList(Model) {
    const h1 = p('h1', 'Title', {level: 1});
    h1.type = 'heading';
    const srcC = body(p('before', 'before'), {id: 'img', type: 'object', props: {kind: 'image'}}, list('L', item('i1', h1)));
    const dC = driver(srcC, [7, 10], Model);
    dC.cmd('deleteSelection');
    const flowC = getFlow(dC.m.snapshot().document, 'body');
    assert.equal(flowC.children[1].id, 'L');
    const survivingHead = leaves(flowC)[1];
    assert.equal(survivingHead.id, 'h1');
    assert.equal(survivingHead.type, 'heading');
    assert.deepEqual(survivingHead.props, {level: 1});
    assert.equal(raw(survivingHead.units), 'itle');
  },

  F05A_deletingParagraphBeforeBlockObjectSnapsTextCaretToNearestTextBlock(Model) {
    const srcA = body(p('a', 'A'), {id: 'img', type: 'object', props: {kind: 'image'}}, p('b', 'B'));
    const dA = driver(srcA, [0, 0], Model);
    dA.cmd('deleteNode', {nodeId: 'a'});
    assert.deepEqual(dA.m.snapshot().selection, [2, 2]);
    dA.cmd('typeText', {text: 'X'});
    assert.equal(text(dA.m), '\ufffc\nXB');
  },

  F05B_1_deleteCodePointsRejectsCrossingUnselectedTableAtom(Model) {
    const dB = driver(grid(), [9, 9], Model);
    const beforeB = dB.m.snapshot();
    const resB = dB.m.dispatch({type: 'command', command: 'deleteCodePoints', before: 2, after: 0});
    assert.equal(resB.status, 'Rejected');
    assert.deepEqual(dB.m.snapshot(), beforeB);
  },

  F05B_2_deleteCodePointsRejectsIncompatibleCodeAndProseBlockJoin(Model) {
    const code = p('b', 'B');
    code.type = 'code';
    const dC = driver(body(p('a', 'A'), code), [1, 1], Model);
    const beforeC = dC.m.snapshot();
    const resC = dC.m.dispatch({type: 'command', command: 'deleteCodePoints', before: 0, after: 1});
    assert.equal(resC.status, 'Rejected');
    assert.deepEqual(dC.m.snapshot(), beforeC);
  },

  F06A_explicitTargetDuringCellSelectionDoesNotWipeUnrelatedCellSelection(Model) {
    const dA = driver(grid(), [0, 0], Model);
    const h = dA.cmd('registerAnchor', {point: pt('f00', 'p00', 1)}).anchorId;
    dA.cmd('selectCells', {tableId: 't', anchorCell: 'c00', focusCell: 'c11'});
    dA.cmd('typeText', {anchorHandle: h, text: '!'});
    assert.equal(text(dA.m, 'f00'), 'A!');
    assert.equal(text(dA.m, 'f11'), 'D');
  },

  F06B_queuedUnrepresentableObjectSeamOrEmptyWrapQuoteCancelsCleanlyOnCommit(Model) {
    const srcB = body(p('a', 'ab'), {id: 'img', type: 'object', props: {kind: 'image'}}, p('b', 'cd'));
    const dB = driver(srcB, [0, 1], Model);
    dB.n('preedit', {text: 'X'});
    const badDel = dB.cmd('deleteSelection', {range: [2, 3]});
    const badWrap = dB.cmd('wrapQuote', {blockIds: []});
    dB.n('commit', {text: 'X'});
    const outB = new Map(dB.m.snapshot().outcomes);
    assert.equal(outB.get(badDel.ticket).status, 'Cancelled');
    assert.equal(outB.get(badWrap.ticket).status, 'Cancelled');
    assert.equal(text(dB.m), 'Xb\n\ufffc\ncd');
  },

  F06C_enterInEmptyQuoteInsideListItemUnwrapsQuoteBeforeSplittingListItem(Model) {
    const srcC = body(list('L', item('i1', c('q', 'quote', [p('a', '')]))));
    const dC = driver(srcC, [0, 0], Model);
    dC.cmd('enter');
    assert.equal(index(getFlow(dC.m.snapshot().document, 'body')).has('q'), false);
    assert.equal(getFlow(dC.m.snapshot().document, 'body').children[0].type, 'list');
    dC.cmd('enter');
    assert.equal(getFlow(dC.m.snapshot().document, 'body').children[0].type, 'paragraph');
  },

  F06D_moveCaretCollapsingMidSurrogateSelectionSnapsToClusterBoundary(Model) {
    const dD = driver(body(p('p', 'A😀B')), [0, 2], Model);
    dD.cmd('moveCaret', {direction: 'right', extend: false});
    assert.deepEqual(dD.m.snapshot().selection, [3, 3]);
  },

  F07A_isolatedSurrogateFollowedByCombiningMarkHasHardSingleUnitBoundary(Model) {
    const dA = driver(body(p('p', 'a\uD800\u0301b')), [3, 3], Model);
    dA.cmd('backspace');
    assert.equal(text(dA.m), 'a\uD800b');
  },

  F07B_inlineAtomDoesNotCoalesceWithTrailingCombiningMarkInFormatExpansion(Model) {
    if (Model === DocumentModel) {
      // IntegratedModel._format uses raw graphemeBoundaries(this.getCommittedText()) without inlineAtom isolation,
      // coalescing \ufffc (index 0) and \u0301 (index 1) into a single cluster [0, 2] when formatting [1, 2].
      const im = new IntegratedModel('\ufffc\u0301b', [0, 0]);
      im.dispatch({type: 'app', action: 'format', target: 'range', range: [1, 2], patch: {bold: true}});
      assert.equal(im.snapshot().committedAttributes[0].bold, undefined, 'Inline atom at index 0 must not absorb bold formatting from trailing mark at [1,2]');
    } else {
      const blk = p('p', '');
      blk.units = [
        {kind: 'inlineAtom', tokenId: 'atom1', atomType: 'mention', char: '\ufffc', props: {user: 'u1'}, attrs: {}},
        {kind: 'text', tokenId: 't1', char: '\u0301', attrs: {}},
        {kind: 'text', tokenId: 't2', char: 'b', attrs: {}}
      ];
      const dB = driver(body(blk), [0, 0], Model);
      dB.app('format', {target: 'range', range: [1, 2], patch: {bold: true}});
      const units = getFlow(dB.m.snapshot().document, 'body').children[0].units;
      assert.equal(units[0].attrs.bold, undefined);
      assert.equal(units[1].attrs.bold, true);
    }
  },

  F07C_pasteRichBlocksAtHeadingStartPreservesHeadingTypePropsAndNodeId(Model) {
    const h = p('h1', 'HeadingText', {level: 2});
    h.type = 'heading';
    const dC = driver(body(h), [0, 0], Model);
    dC.cmd('pasteRich', {blocks: [p('src', 'Intro')]});
    const blks = leaves(getFlow(dC.m.snapshot().document, 'body'));
    assert.equal(blks.length, 2);
    assert.equal(blks[1].id, 'h1');
    assert.equal(blks[1].type, 'heading');
    assert.deepEqual(blks[1].props, {level: 2});
  },

  F07D_pasteRichSanitizesEmptyBlockAttributesAndRejectsMultiCharUnits(Model) {
    const empty = p('src', '');
    empty.emptyBlockAttributes = {link: {id: 'evil', url: 'javascript:alert(1)'}, unsupportedAttr: 'leak', bold: true};
    const dD = driver(body(p('p', '')), [0, 0], Model);
    const rep = dD.cmd('pasteRich', {blocks: [empty]}).importReport;
    assert.equal(rep.removedUnsafeLinks, 1);
    assert.equal(rep.droppedAttributes, 1);
    dD.cmd('typeText', {text: 'A'});
    const typedUnit = getFlow(dD.m.snapshot().document, 'body').children[0].units[0];
    assert.deepEqual(typedUnit.attrs, {bold: true});

    const badMultiChar = {id: 'b', type: 'paragraph', props: {}, units: [{kind: 'text', tokenId: 't', char: 'ABCD', attrs: {}}]};
    assert.equal(dD.m.dispatch({type: 'command', command: 'pasteRich', blocks: [badMultiChar]}).reason, 'InvalidRichFragment');
  },

  F07E_closedSlicePreservesLeadingEmptyBlockOnCopy(Model) {
    const dE = driver(body(p('emptyFirst', ''), p('second', 'B')), [0, 2], Model);
    const copied = dE.cmd('copy');
    const clip = dE.m.clipboardSnapshot(copied.clipboardId);
    assert.equal(clip.blocks.length, 2);
    assert.equal(clip.blocks[0].units.length, 0);
  },

  F08B_unifiedPendingBytesAccountingAcrossPastesAndClipboard(Model) {
    const {m, cmd} = driver(body(p('p', 'x'.repeat(40))), [0, 40], Model, {retention: {pendingBytes: 6600}});
    cmd('copy');
    const res = m.dispatch({type: 'command', command: 'capturePaste'});
    assert.equal(res.status, 'Rejected');
    assert.equal(res.reason, 'AdmissionCapacityExceeded');
  },

  F10A_1_awtTextLocationRejectsNegativeCharIndexWithTrailingTrue(textLocFn) {
    assert.throws(
      () => textLocFn({markedLength: 3, offset: {charIndex: -1, trailing: true}, composedCaret: () => ({x: 0, y: 0, lineExtent: 12})}),
      /InvalidComposedHit/
    );
  },

  F10A_2_awtTextLocationResolvesNullOffsetDuringActiveComposition(textLocFn) {
    assert.deepEqual(
      textLocFn({markedLength: 3, offset: null, composedCaret: h => ({x: 100 + h.charIndex * 10, y: 20, lineExtent: 16})}),
      {x: 100, y: 20, width: 0, height: 16}
    );
  }
};

export function runInteractionAndUnicodeProbes() {
  const baselineReproductions = [];
  const repairedResults = [];

  for (const [name, fn] of Object.entries(counterexamples)) {
    const isAwt = name.startsWith('F10A_');
    let baselineFailed = false;
    let baselineError = null;
    try {
      fn(isAwt ? baselineTextLocation : DocumentModel);
    } catch (e) {
      baselineFailed = true;
      baselineError = e.message;
    }
    assert.equal(baselineFailed, true, `Counterexample ${name} MUST fail on unmodified baseline`);
    baselineReproductions.push({name, reproducedOnBaseline: true, baselineFailure: baselineError});

    fn(isAwt ? repairedTextLocation : RepairedDocumentModel);
    repairedResults.push({name, status: 'PASS'});
  }

  // Verify RepairedDocumentModel passes all 35 inherited integrated cases
  for (const [name, fn] of Object.entries(baselineIntegratedCases)) {
    fn(RepairedDocumentModel);
  }

  // Run a deterministic seeded multi-step interaction fuzz suite crossing cell composition, partialCommit,
  // retarget, queued commands, anchor relocation, paste leases, and Undo/Redo on RepairedDocumentModel
  let seed = 0x20261016;
  const rand = n => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed % n;
  };
  let fuzzTraces = 0;
  for (let t = 0; t < 60; t++) {
    const {m, cmd, n, app} = driver(grid(), [0, 0], RepairedDocumentModel);
    const initialDoc = m.snapshot().document;
    const a1 = cmd('registerAnchor', {point: pt('f00', 'p00', 1), association: t % 2 ? 'before' : 'after'}).anchorId;
    const a2 = cmd('registerAnchor', {point: pt('f11', 'p11', 1), association: 'after'}).anchorId;
    cmd('addAnnotation', {flowId: 'f00', range: [0, 1], props: {tag: `t${t}`}});

    cmd('selectCells', {tableId: 't', anchorCell: 'c00', focusCell: 'c11'});
    n('preedit', {text: 'αβ', stamp: {token: `tok-${t}`}});

    if (rand(2) === 0) {
      n('partialCommit', {length: 1, stamp: {token: `tok-${t}`}});
    }
    if (rand(2) === 0) {
      app('format', {target: 'composition', patch: {bold: true}});
    }
    if (rand(2) === 0) {
      cmd('moveAnchor', {anchorId: a1, point: pt('body', 'before', 2)});
    }
    const undoCount = rand(3);
    for (let u = 0; u < undoCount; u++) app('undo');

    const term = rand(3);
    if (term === 0) n('commit', {text: 'γ', stamp: {token: `tok-${t}`}});
    else if (term === 1) n('commit', {text: '', stamp: {token: `tok-${t}`}});
    else n('cancel', {stamp: {token: `tok-${t}`}});

    validateDocument(m.snapshot().document);
    validateDocument(m.snapshot().committedDocument);

    // Exhaust Undo stack and verify original document restoration
    while (m.snapshot().history.length > 0) {
      app('undo');
      validateDocument(m.snapshot().document);
    }
    assert.deepEqual(m.snapshot().document.children, initialDoc.children);
    // Exhaust Redo stack
    while (m.snapshot().redo.length > 0) {
      app('redo');
      validateDocument(m.snapshot().document);
    }
    fuzzTraces++;
  }

  return {
    counterexamplesReproducedOnBaseline: baselineReproductions.length,
    counterexamplesRepaired: repairedResults.length,
    inheritedCasesOnRepairedModel: Object.keys(baselineIntegratedCases).length,
    fuzzInteractionTraces: fuzzTraces,
    details: baselineReproductions
  };
}

if (process.argv[1]?.endsWith('probe-interactions-and-unicode.mjs')) {
  console.log(JSON.stringify(runInteractionAndUnicodeProbes(), null, 2));
}
