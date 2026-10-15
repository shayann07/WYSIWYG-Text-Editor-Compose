// Executable Test Suite: Hand-Calculated Fixtures Verification (§3, §4, §5)
// Evaluates IndependentOracle, Candidate A, and Candidate B against identical pre-calculated fixtures.

import assert from 'node:assert/strict';
import { FIXTURES } from './fixtures.mjs';
import { IndependentOracle } from './independent-oracle.mjs';
import { CandidateACoordinator } from './candidate-a-coordinator.mjs';
import { CandidateBCoordinator } from './candidate-b-coordinator.mjs';

function runFixtureOnCandidate(CandidateClass, fixture) {
  const instance = new CandidateClass(fixture.initialText, fixture.initialAttrs);
  let queuedTicket = null;

  for (const step of fixture.steps) {
    if (step.action === 'startComposition') {
      instance.applyInput({ type: 'startComposition', range: step.range });
    } else if (step.action === 'preedit') {
      instance.applyInput({ type: 'preedit', text: step.text, range: step.range });
    } else if (step.action === 'targetExpansion') {
      instance.applyInput({ type: 'targetExpansion', range: step.range });
    } else if (step.action === 'partialCommit') {
      instance.applyInput({ type: 'partialCommit', length: step.length });
    } else if (step.action === 'unmark') {
      instance.applyInput({ type: 'unmark', settled: step.settled });
    } else if (step.action === 'completeSettlement') {
      let tok = step.token;
      if (step.useActiveToken) {
        tok = instance.activeSettlementToken ? instance.activeSettlementToken.token : null;
      }
      const res = instance.applyInput({ type: 'completeSettlement', token: tok });
      if (step.shouldReject) {
        assert.equal(res.status, 'Rejected');
        if (step.expectedReason) assert.equal(res.reason, step.expectedReason);
      }
    } else if (step.action === 'cancel') {
      instance.applyInput({ type: 'cancel' });
    } else if (step.action === 'queueCommand') {
      const res = instance.applyInput({ type: 'queueCommand', cmd: step.cmd, target: step.target });
      queuedTicket = res.ticketId;
    }
  }

  // Verify expectations
  const exp = fixture.expected;
  if (exp.finalText !== undefined) {
    assert.equal(instance.docText, exp.finalText, `Text mismatch on ${fixture.name}: actual "${instance.docText}" vs expected "${exp.finalText}"`);
  }

  if (exp.boldIndices) {
    const attrs = instance.docAttributes;
    for (const idx of exp.boldIndices) {
      assert.ok(attrs[idx] && attrs[idx].bold, `Expected bold at index ${idx} on ${fixture.name}`);
    }
  }
  if (exp.nonBoldIndices) {
    const attrs = instance.docAttributes;
    for (const idx of exp.nonBoldIndices) {
      assert.ok(!attrs[idx] || !attrs[idx].bold, `Expected non-bold at index ${idx} on ${fixture.name}`);
    }
  }
  if (exp.italicIndices) {
    const attrs = instance.docAttributes;
    for (const idx of exp.italicIndices) {
      assert.ok(attrs[idx] && attrs[idx].italic, `Expected italic at index ${idx} on ${fixture.name}`);
    }
  }
  if (exp.undoGroupCount !== undefined) {
    assert.equal(instance.undoStack.length, exp.undoGroupCount, `Undo stack length mismatch on ${fixture.name}: actual ${instance.undoStack.length} vs expected ${exp.undoGroupCount}`);
  }
  if (exp.typingIntent) {
    for (const k of Object.keys(exp.typingIntent)) {
      assert.equal(instance.typingIntent[k], exp.typingIntent[k], `typingIntent mismatch on ${fixture.name}`);
    }
  }
  if (exp.commandOutcome && queuedTicket) {
    const outcome = instance.commandOutcomes.get(queuedTicket);
    assert.ok(outcome, `Missing command outcome for ${queuedTicket}`);
    assert.equal(outcome.status, exp.commandOutcome.status);
    if (exp.commandOutcome.reason) {
      assert.equal(outcome.reason, exp.commandOutcome.reason);
    }
  }
}

export function runAllFixtures() {
  console.log('=== Running Hand-Calculated Regression Fixtures (§4, §5) ===');
  const results = {
    IndependentOracle: { passed: 0, failed: 0, errors: [] },
    CandidateA: { passed: 0, failed: 0, errors: [] },
    CandidateB: { passed: 0, failed: 0, errors: [] }
  };

  for (const [key, fixture] of Object.entries(FIXTURES)) {
    // Test Independent Oracle
    try {
      runFixtureOnCandidate(IndependentOracle, fixture);
      results.IndependentOracle.passed++;
      console.log(`[PASS] Oracle: ${fixture.name}`);
    } catch (e) {
      results.IndependentOracle.failed++;
      results.IndependentOracle.errors.push({ fixture: key, error: e.message });
      console.log(`[FAIL] Oracle: ${fixture.name} -> ${e.message}`);
    }

    // Test Candidate A
    try {
      runFixtureOnCandidate(CandidateACoordinator, fixture);
      results.CandidateA.passed++;
      console.log(`[PASS] Candidate A: ${fixture.name}`);
    } catch (e) {
      results.CandidateA.failed++;
      results.CandidateA.errors.push({ fixture: key, error: e.message });
      console.log(`[FAIL] Candidate A: ${fixture.name} -> ${e.message}`);
    }

    // Test Candidate B
    try {
      runFixtureOnCandidate(CandidateBCoordinator, fixture);
      results.CandidateB.passed++;
      console.log(`[PASS] Candidate B: ${fixture.name}`);
    } catch (e) {
      results.CandidateB.failed++;
      results.CandidateB.errors.push({ fixture: key, error: e.message });
      console.log(`[FAIL] Candidate B: ${fixture.name} -> ${e.message}`);
    }
  }

  console.log('\n--- Fixture Verification Summary ---');
  console.log(`Independent Oracle: ${results.IndependentOracle.passed} passed, ${results.IndependentOracle.failed} failed`);
  console.log(`Candidate A: ${results.CandidateA.passed} passed, ${results.CandidateA.failed} failed`);
  console.log(`Candidate B: ${results.CandidateB.passed} passed, ${results.CandidateB.failed} failed`);
  return results;
}

if (process.argv[1].endsWith('test-fixtures.mjs')) {
  const r = runAllFixtures();
  if (r.CandidateB.failed > 0) process.exit(1);
}
