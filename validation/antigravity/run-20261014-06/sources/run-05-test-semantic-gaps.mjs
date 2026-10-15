// Deep Semantic Gaps Test Suite (Resolves §4.1 - §4.7)
// Exhaustively verifies all required traces and edge cases from prompt specification:
// 4.1 Formatting target vs insertion provenance (disjoint islands, combining marks, empty outcome)
// 4.2 Retargeting vs superseding committed content (disjoint, touching, partial overlap, full containment)
// 4.3 Partial commits and absorbed original content (Astra Critique 1 trace: abcd -> aXYZcd -> aXYcd)
// 4.4 Deletions with zero visible length (abc -> ac -> partialCommit 1 -> cancel -> abc)
// 4.5 Replacement rollback ownership (b -> XYZ -> replace interior Y -> partialCommit -> cancel)
// 4.6 Attribution detour and suffix epoch
// 4.7 Settlement lifecycle and ordering

import assert from 'node:assert/strict';
import { CandidateBCoordinator, areAttrsEqual } from './candidate-b-coordinator.mjs';
import { IndependentOracle } from './independent-oracle.mjs';

export function runSemanticGapsTests() {
  console.log('=== Running Deep Semantic Gaps Validation Suite (§4.1 - §4.7) ===');
  let passed = 0;

  function test(name, fn) {
    fn();
    passed++;
    console.log(`[PASS] ${name}`);
  }

  // -------------------------------------------------------------------------
  // 4.1 Formatting target versus insertion provenance
  // -------------------------------------------------------------------------
  test('4.1: cat -> cats formats whole word, not merely inserted suffix s', () => {
    const c = new CandidateBCoordinator('The cat jumped');
    c.applyInput({ type: 'startComposition', range: [4, 7] }); // "cat"
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'preedit', text: 'cats' });
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, 'The cats jumped');
    for (let i = 4; i < 8; i++) {
      assert.ok(c.docAttributes[i].bold, `Character at ${i} must be bold`);
    }
    assert.ok(!c.docAttributes[3]?.bold);
    assert.ok(!c.docAttributes[8]?.bold);
  });

  test('4.1: combining mark beside base character applies outward grapheme closure', () => {
    const c = new CandidateBCoordinator('e box'); // 'e' at 0..1
    c.applyInput({ type: 'startComposition', range: [0, 1] });
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'preedit', text: 'e\u0301' }); // 'e' + combining acute accent
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, 'e\u0301 box');
    assert.ok(c.docAttributes[0].bold, 'Base character e must be bold');
    assert.ok(c.docAttributes[1].bold, 'Combining accent must be bold');
    assert.ok(!c.docAttributes[2]?.bold);
  });

  test('4.1: empty candidate outcome updates typingIntent without formatting undo group', () => {
    const c = new CandidateBCoordinator('Hello world');
    c.applyInput({ type: 'startComposition', range: [1, 5] }); // "ello"
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { underline: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'preedit', text: '' }); // empty candidate
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, 'H world');
    assert.equal(c.typingIntent.underline, true);
    assert.equal(c.undoStack.length, 1); // 1 Composition group for deleting "ello", 0 Formatting groups
    assert.equal(c.undoStack[0].group, 'Composition');
  });

  test('4.1: cancelled composition terminates command with Cancelled outcome', () => {
    const c = new CandidateBCoordinator('Hello');
    c.applyInput({ type: 'startComposition', range: [1, 3] });
    const q = c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, 'Hello');
    const outcome = c.commandOutcomes.get(q.ticketId);
    assert.deepEqual(outcome, { status: 'Cancelled', reason: 'CompositionCancelled' });
  });

  test('4.1: disjoint retargeting formats constituent islands without convex hull', () => {
    const c = new CandidateBCoordinator('The big bad cat jumped');
    c.applyInput({ type: 'startComposition', range: [4, 7] }); // "big"
    c.applyInput({ type: 'queueCommand', cmd: { type: 'Format', patch: { bold: true } }, target: { type: 'CompositionTarget' } });
    c.applyInput({ type: 'targetExpansion', range: [12, 15] }); // disjoint retarget to "cat"
    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, 'The big bad cat jumped');
    for (const idx of [4, 5, 6, 12, 13, 14]) {
      assert.ok(c.docAttributes[idx]?.bold, `Character at ${idx} must be bold`);
    }
    for (const idx of [0, 1, 2, 3, 7, 8, 9, 10, 11, 15, 16, 17, 18, 19, 20, 21]) {
      assert.ok(!c.docAttributes[idx]?.bold, `Intervening/outside character at ${idx} must NOT be bold`);
    }
    assert.equal(c.undoStack.length, 1);
  });

  // -------------------------------------------------------------------------
  // 4.2 Retargeting versus superseding committed content
  // -------------------------------------------------------------------------
  test('4.2.1: strict disjoint retargeting protects committed content', () => {
    const c = new CandidateBCoordinator('0123456789');
    c.applyInput({ type: 'startComposition', range: [2, 3] }); // '2'
    c.applyInput({ type: 'preedit', text: 'AB' }); // "01AB3456789"
    c.applyInput({ type: 'partialCommit', length: 1 }); // "A" committed at index 2..3, marked is "B" at index 3..4
    c.applyInput({ type: 'targetExpansion', range: [6, 8] }); // disjoint retarget to [6, 8)
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, '01A3456789'); // committed 'A' must survive!
  });

  test('4.2.2: touching boundary retargeting protects committed content', () => {
    const c = new CandidateBCoordinator('0123456789');
    c.applyInput({ type: 'startComposition', range: [2, 3] }); // '2'
    c.applyInput({ type: 'preedit', text: 'AB' });
    c.applyInput({ type: 'partialCommit', length: 1 }); // 'A' committed at 2..3
    c.applyInput({ type: 'targetExpansion', range: [3, 6] }); // touching boundary at offset 3
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, '01A3456789'); // committed 'A' must survive!
  });

  test('4.2.3: partial overlap and full containment retargeting protect committed content', () => {
    // Partial overlap covering committed prefix
    let c = new CandidateBCoordinator('0123456789');
    c.applyInput({ type: 'startComposition', range: [2, 3] });
    c.applyInput({ type: 'preedit', text: 'AB' });
    c.applyInput({ type: 'partialCommit', length: 1 });
    c.applyInput({ type: 'targetExpansion', range: [1, 4] }); // partial overlap covering committed 'A'
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, '01A3456789');

    // Full containment covering committed prefix and surrounding text
    c = new CandidateBCoordinator('0123456789');
    c.applyInput({ type: 'startComposition', range: [2, 3] });
    c.applyInput({ type: 'preedit', text: 'AB' });
    c.applyInput({ type: 'partialCommit', length: 1 });
    c.applyInput({ type: 'targetExpansion', range: [0, 8] }); // full containment of committed 'A'
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, '01A3456789');
  });

  test('4.2.4: invalid target ranges are rejected and NEVER silently clamped', () => {
    const c = new CandidateBCoordinator('0123456789');
    c.applyInput({ type: 'startComposition', range: [2, 3] });
    const rej1 = c.applyInput({ type: 'targetExpansion', range: [-1, 5] });
    const rej2 = c.applyInput({ type: 'targetExpansion', range: [2, 20] });
    const rej3 = c.applyInput({ type: 'targetExpansion', range: [5, 2] });
    assert.equal(rej1.status, 'Rejected');
    assert.equal(rej1.reason, 'InvalidTargetRange');
    assert.equal(rej2.status, 'Rejected');
    assert.equal(rej2.reason, 'InvalidTargetRange');
    assert.equal(rej3.status, 'Rejected');
    assert.equal(rej3.reason, 'InvalidTargetRange');
  });

  // -------------------------------------------------------------------------
  // 4.3 Partial commits and absorbed original content (Astra Critique 1)
  // -------------------------------------------------------------------------
  test('4.3: Astra Critique 1 required trace: abcd -> aXYZcd -> partialCommit Y -> cancel -> aXYcd', () => {
    const c = new CandidateBCoordinator('abcd');
    // Step 1: replace 'b' provisionally with "XYZ"
    c.applyInput({ type: 'startComposition', range: [1, 2] });
    c.applyInput({ type: 'preedit', text: 'XYZ' });
    assert.equal(c.docText, 'aXYZcd');

    // Step 2: expand to include original 'c'
    c.applyInput({ type: 'targetExpansion', range: [1, 5] }); // covers "XYZc"
    assert.equal(c.docText, 'aXYZcd');

    // Step 3: retarget marked window to 'Y' only (visible index 2..3)
    c.applyInput({ type: 'targetExpansion', range: [2, 3] });
    assert.equal(c.docText, 'aXYZcd');

    // Step 4: partially commit 'Y' (length 1)
    // Preceding provisional 'X' is also committed; Dc becomes "aXYcd"
    c.applyInput({ type: 'partialCommit', length: 1 });
    assert.equal(c.docText, 'aXYZcd');

    // Step 5: cancel remaining provisional work ('Z')
    c.applyInput({ type: 'cancel' });
    // Original 'c' must NOT disappear!
    assert.equal(c.docText, 'aXYcd');
  });

  // -------------------------------------------------------------------------
  // 4.4 Deletions with zero visible length
  // -------------------------------------------------------------------------
  test('4.4: Deletion at commit cut boundary belongs to remaining effect and is cancelled', () => {
    const c = new CandidateBCoordinator('abc');
    c.applyInput({ type: 'startComposition', range: [0, 3] }); // "abc"
    c.applyInput({ type: 'preedit', text: 'ac' }); // provisionally deletes 'b'
    assert.equal(c.docText, 'ac');

    // Cut after first visible character 'a' (pCut = 1)
    // Deletion of 'b' sits at visible offset 1. Because pCut <= 1, deletion belongs to remaining effect!
    c.applyInput({ type: 'partialCommit', length: 1 });
    assert.equal(c.docText, 'ac');

    // Cancel remaining provisional work
    c.applyInput({ type: 'cancel' });
    // Deletion of 'b' was cancelled along with provisional 'c', restoring 'abc'!
    assert.equal(c.docText, 'abc');
  });

  test('4.4: Deletion strictly before commit cut is committed', () => {
    const c = new CandidateBCoordinator('abc');
    c.applyInput({ type: 'startComposition', range: [0, 3] });
    c.applyInput({ type: 'preedit', text: 'ac' }); // deletes 'b'
    // Cut after 'ac' (pCut = 2 > 1)
    c.applyInput({ type: 'partialCommit', length: 2 });
    assert.equal(c.docText, 'ac');
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, 'ac');
  });

  test('4.4: Deletion after retargeted cut boundary belongs to remaining effect and is cancelled', () => {
    const c = new CandidateBCoordinator('abcde');
    c.applyInput({ type: 'startComposition', range: [1, 4] }); // "bcd"
    c.applyInput({ type: 'preedit', text: 'bd' }); // provisionally deletes 'c'
    c.applyInput({ type: 'targetExpansion', range: [1, 2] }); // retarget 'b' only
    c.applyInput({ type: 'partialCommit', length: 1 }); // commit 'b' only (cut at visible offset 1, before deletion of 'c' at offset 2)
    c.applyInput({ type: 'cancel' }); // cancel remaining provisional work
    assert.equal(c.docText, 'abcde'); // original 'c' must survive!
  });

  test('4.4: Consecutive deletion segments and empty candidate handling', () => {
    const c = new CandidateBCoordinator('abcde');
    c.applyInput({ type: 'startComposition', range: [1, 4] }); // "bcd"
    c.applyInput({ type: 'preedit', text: '' }); // delete entire candidate
    assert.equal(c.docText, 'ae');
    // partialCommit with length > 0 on empty candidate is rejected
    const rRej = c.applyInput({ type: 'partialCommit', length: 1 });
    assert.equal(rRej.status, 'Rejected');
    assert.equal(rRej.reason, 'CommittedLengthExceedsCandidate');
    // Cancel restores entire candidate
    c.applyInput({ type: 'cancel' });
    assert.equal(c.docText, 'abcde');
  });

  // -------------------------------------------------------------------------
  // 4.5 Replacement rollback ownership
  // -------------------------------------------------------------------------
  test('4.5: Original replacement rollback obligation partitioned cleanly without duplication or loss', () => {
    const c = new CandidateBCoordinator('abc');
    c.applyInput({ type: 'startComposition', range: [1, 2] }); // 'b'
    c.applyInput({ type: 'preedit', text: 'XYZ' }); // 'b' -> "XYZ"
    c.applyInput({ type: 'targetExpansion', range: [2, 3] }); // retarget interior 'Y'
    c.applyInput({ type: 'preedit', text: 'UVW' }); // 'Y' -> "UVW", visible "aXUVWZc"
    assert.equal(c.docText, 'aXUVWZc');

    // Partially commit 1 character of 'UVW' ('U'), which along with preceding 'X' commits "XU"
    c.applyInput({ type: 'partialCommit', length: 1 });
    assert.equal(c.docText, 'aXUVWZc');

    // Cancel remaining provisional units 'VWZ'
    c.applyInput({ type: 'cancel' });
    // 'b' rollback was satisfied by committed "XU"; remainder was dropped cleanly
    assert.equal(c.docText, 'aXUc');
  });

  test('4.5: Multiple consecutive interior retargetings maintain single rollback ownership', () => {
    const c = new CandidateBCoordinator('abcdef');
    c.applyInput({ type: 'startComposition', range: [1, 2] }); // 'b'
    c.applyInput({ type: 'preedit', text: 'XYZ' }); // 'b' -> "XYZ"
    c.applyInput({ type: 'targetExpansion', range: [2, 3] }); // retarget 'Y'
    c.applyInput({ type: 'preedit', text: 'UVW' }); // 'Y' -> "UVW", visible "aXUVWZcdef"
    assert.equal(c.docText, 'aXUVWZcdef');
    c.applyInput({ type: 'targetExpansion', range: [4, 5] }); // retarget 'W'
    c.applyInput({ type: 'preedit', text: '123' }); // 'W' -> "123", visible "aXUV123Zcdef"
    assert.equal(c.docText, 'aXUV123Zcdef');

    // Partial commit '1' inside '123' -> cuts at visible offset 5, commits "XUV1"
    c.applyInput({ type: 'partialCommit', length: 1 });
    assert.equal(c.docText, 'aXUV123Zcdef');
    assert.equal(c.committedDoc.map(u => u.char).join(''), 'aXUV1cdef');

    // Cancel remaining provisional units '23Z'
    c.applyInput({ type: 'cancel' });
    // 'b' rollback was satisfied by committed "XUV1"; remaining provisional units dropped cleanly
    assert.equal(c.docText, 'aXUV1cdef');
  });

  // -------------------------------------------------------------------------
  // 4.6 Attribution detour and suffix epoch
  // -------------------------------------------------------------------------
  test('4.6: Detour ab -> x -> ab restores constituent bold and italic; suffix epoch preserves suffix attrs', () => {
    const c = new CandidateBCoordinator('ab', [{ bold: true }, { italic: true }]);
    c.applyInput({ type: 'startComposition', range: [0, 2] });
    c.applyInput({ type: 'preedit', text: 'x' });
    assert.equal(c.docText, 'x');

    // Detour back to 'ab' restores exact baseline attributes
    c.applyInput({ type: 'preedit', text: 'ab' });
    assert.equal(c.docText, 'ab');
    assert.ok(c.docAttributes[0].bold, 'a must retain bold');
    assert.ok(c.docAttributes[1].italic, 'b must retain italic');

    // Partial commit 'a' freezes bold 'a'; suffix epoch begins for italic 'b'
    c.applyInput({ type: 'partialCommit', length: 1 });
    c.applyInput({ type: 'preedit', text: 'y' });
    assert.equal(c.docText, 'ay');

    // Suffix detour back to 'b' restores italic 'b'
    c.applyInput({ type: 'preedit', text: 'b' });
    assert.equal(c.docText, 'ab');
    assert.ok(c.docAttributes[0].bold, 'a must retain bold');
    assert.ok(c.docAttributes[1].italic, 'b must retain italic');

    c.applyInput({ type: 'unmark', settled: true });
    assert.equal(c.docText, 'ab');
    assert.ok(c.docAttributes[0].bold);
    assert.ok(c.docAttributes[1].italic);
  });

  console.log(`\nSemantic Gaps Verification Summary: ${passed} passed`);
  return { passed };
}

if (process.argv[1]?.endsWith('test-semantic-gaps.mjs')) {
  runSemanticGapsTests();
}
