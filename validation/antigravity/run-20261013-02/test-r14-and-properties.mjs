import assert from 'node:assert/strict';
import {
  serializeCanonicalDocument,
  deserializeCanonicalDocument,
  exportValidUnicode,
  isSurrogatePairAt
} from './reference-model.mjs';
import { ProtocolCoordinator } from './protocol-coordinator.mjs';

/**
 * Independent Reference Oracle for Candidate Reconciliation (§4.3).
 * Computes expected attributes directly from baseline B and candidate string C
 * without invoking the coordinator or session classes.
 */
function independentReconciliationOracle(B, CText, seedAttributes) {
  const bText = B.map(u => u.char).join('');
  if (CText === bText) {
    return B.map(u => ({ char: u.char, attributes: { ...u.attributes } }));
  }

  const bLen = bText.length;
  const cLen = CText.length;
  const maxL = Math.min(bLen, cLen);

  // Common prefix
  let p = 0;
  while (p < maxL && bText.charCodeAt(p) === CText.charCodeAt(p)) {
    p++;
  }

  // Common suffix constrained by p + s <= min(bLen, cLen)
  let s = 0;
  const maxS = maxL - p;
  while (s < maxS && bText.charCodeAt(bLen - 1 - s) === CText.charCodeAt(cLen - 1 - s)) {
    s++;
  }

  // Surrogate pair retreat
  if (p > 0 && (isSurrogatePairAt(bText, p - 1) || isSurrogatePairAt(CText, p - 1))) {
    p--;
  }
  if (s > 0) {
    const bSuffix = bLen - s;
    const cSuffix = cLen - s;
    if ((bSuffix > 0 && isSurrogatePairAt(bText, bSuffix - 1)) ||
        (cSuffix > 0 && isSurrogatePairAt(CText, cSuffix - 1))) {
      s--;
    }
  }

  const expected = [];
  for (let i = 0; i < p; i++) {
    expected.push({ char: CText[i], attributes: { ...B[i].attributes } });
  }
  const midEnd = cLen - s;
  for (let i = p; i < midEnd; i++) {
    expected.push({ char: CText[i], attributes: { ...seedAttributes } });
  }
  for (let i = midEnd; i < cLen; i++) {
    const bIdx = bLen - cLen + i;
    expected.push({ char: CText[i], attributes: { ...B[bIdx].attributes } });
  }
  return expected;
}

export function runTestsR14AndProperties() {
  const results = [];
  
  // -------------------------------------------------------------------------
  // R14: Raw UTF-16 Persistence, Isolated Surrogates & Valid-Unicode Export
  // -------------------------------------------------------------------------
  {
    const originalUnits = [
      { char: 'a', attributes: { bold: true } },
      { char: '\uD83D', attributes: { color: 'red' } },
      { char: '\uDE00', attributes: { color: 'red' } },
      { char: '\uD83D', attributes: { warning: 'isolated-high' } }, // isolated high surrogate!
      { char: 'x', attributes: { italic: true } },
      { char: '\uDE00', attributes: { warning: 'isolated-low' } },  // isolated low surrogate!
      { char: 'b', attributes: {} }
    ];
    
    const doc = {
      id: "doc-test-1234",
      textUnits: originalUnits
    };
    
    // Serialize to canonical format
    const serialized = serializeCanonicalDocument(doc);
    assert.equal(serialized.format, "editor-document/1");
    assert.equal(serialized.unitCount, 7);
    assert.ok(typeof serialized.base64TextLE === 'string');
    
    // Deserialize
    const deserialized = deserializeCanonicalDocument(serialized);
    assert.equal(deserialized.id, doc.id);
    assert.equal(deserialized.textUnits.length, 7);
    
    // Verify EXACT unit-by-unit equality, including isolated surrogates and raw attributes!
    for (let i = 0; i < doc.textUnits.length; i++) {
      assert.equal(deserialized.textUnits[i].char.charCodeAt(0), doc.textUnits[i].char.charCodeAt(0));
      assert.deepEqual(deserialized.textUnits[i].attributes, doc.textUnits[i].attributes);
    }
    
    // Valid-Unicode Export:
    const exported = exportValidUnicode(deserialized.textUnits);
    assert.equal(exported.valid, false);
    assert.equal(exported.isolatedSurrogateCount, 2);
    const expectedExported = "a\uD83D\uDE00\uFFFDx\uFFFDb";
    assert.equal(exported.text, expectedExported);
    
    results.push({
      test: 'R14',
      status: 'PASS',
      description: 'Raw UTF-16 with isolated surrogates survives canonical persistence verbatim; valid-Unicode export generates conversion report and U+FFFD (note: serialization round-trip does not establish crash durability or journal correctness).'
    });
  }

  // -------------------------------------------------------------------------
  // Deterministic Adversarial Stateful Property Test (Invariant I07 & I08)
  // -------------------------------------------------------------------------
  {
    let seed = 987654321;
    function rand() {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    }
    function randInt(min, max) {
      return Math.floor(rand() * (max - min)) + min;
    }
    
    const charPool = ['a', 'b', 'c', 'd', '\uD83D\uDE00', '1', '2'];
    const attrPool = [
      { bold: true },
      { italic: true },
      { underline: true },
      { color: 'blue' },
      {}
    ];
    
    let iterations = 10000;
    let pathIndependenceChecks = 0;
    let oracleAgreementChecks = 0;
    let partialCommitFreezeChecks = 0;
    let epochResetDivergenceChecks = 0;
    
    for (let it = 0; it < iterations; it++) {
      // 1. Generate random baseline B
      const bLen = randInt(1, 6);
      const B = [];
      for (let i = 0; i < bLen; i++) {
        const ch = charPool[randInt(0, charPool.length)];
        const at = attrPool[randInt(0, attrPool.length)];
        for (let k = 0; k < ch.length; k++) {
          B.push({ char: ch[k], attributes: { ...at } });
        }
      }
      
      const bText = B.map(u => u.char).join('');
      const defaultSeed = { seedMark: 'seed-' + it };
      
      // Target final candidate string
      const cFinalLen = randInt(0, 8);
      let cFinal = "";
      for (let i = 0; i < cFinalLen; i++) {
        cFinal += charPool[randInt(0, charPool.length)];
      }

      // Initialize TWO independent coordinator instances
      const coord1 = new ProtocolCoordinator(bText, B.map(u => u.attributes), [0, bText.length]);
      const coord2 = new ProtocolCoordinator(bText, B.map(u => u.attributes), [0, bText.length]);
      coord1.openNewConnection();
      coord2.openNewConnection();
      
      coord1.receiveCallback(1, { type: 'startComposition', start: 0, end: bText.length, seedAttributes: defaultSeed });
      coord2.receiveCallback(1, { type: 'startComposition', start: 0, end: bText.length, seedAttributes: defaultSeed });

      // Path 1 Detour: 1 to 3 real intermediate candidate edits
      const numEdits1 = randInt(1, 4);
      for (let e = 0; e < numEdits1; e++) {
        const midText = charPool[randInt(0, charPool.length)] + "p1_" + e + (it % 2 === 0 ? bText : "");
        coord1.receiveCallback(1, { type: 'preedit', text: midText });
      }
      // Finally reach cFinal on Path 1:
      coord1.receiveCallback(1, { type: 'preedit', text: cFinal });

      // Path 2 Detour: DIFFERENT intermediate candidate edits
      const numEdits2 = randInt(1, 4);
      for (let e = 0; e < numEdits2; e++) {
        const midText = "diff_p2_" + charPool[randInt(0, charPool.length)] + "_" + e;
        coord2.receiveCallback(1, { type: 'preedit', text: midText });
      }
      // Finally reach cFinal on Path 2:
      coord2.receiveCallback(1, { type: 'preedit', text: cFinal });

      // Invariant I07 Assertion:
      // Both stateful coordinators must yield IDENTICAL document text and attributes!
      assert.equal(coord1.docText, coord2.docText);
      assert.equal(coord1.docAttributes.length, coord2.docAttributes.length);
      for (let i = 0; i < coord1.docAttributes.length; i++) {
        assert.deepEqual(coord1.docAttributes[i], coord2.docAttributes[i]);
      }
      pathIndependenceChecks++;

      // Compare against Independent Reference Oracle:
      const oracleExpected = independentReconciliationOracle(B, cFinal, defaultSeed);
      assert.equal(coord1.docAttributes.length, oracleExpected.length);
      for (let i = 0; i < oracleExpected.length; i++) {
        assert.equal(coord1.docText[i], oracleExpected[i].char);
        assert.deepEqual(coord1.docAttributes[i], oracleExpected[i].attributes);
      }
      oracleAgreementChecks++;

      // Invariant I08 Assertion: Partial commit freezing
      if (cFinal.length >= 2) {
        const commitLen = randInt(1, cFinal.length);
        coord1.receiveCallback(1, { type: 'partialCommit', committedLength: commitLen });
        
        // Capture frozen prefix attributes
        const frozenAttrs = coord1.docAttributes.slice(0, commitLen).map(a => ({ ...a }));
        
        // Heavily mutate suffix
        const mutatedSuffix = "SUFFIX_MUTATION_" + randInt(1, 999);
        coord1.receiveCallback(1, { type: 'preedit', text: mutatedSuffix });
        
        // Assert frozen prefix attributes NEVER changed!
        for (let i = 0; i < commitLen; i++) {
          assert.deepEqual(coord1.docAttributes[i], frozenAttrs[i]);
        }

        // Even after cancellation of remaining preedit (empty text):
        coord1.receiveCallback(1, { type: 'preedit', text: "" });
        for (let i = 0; i < commitLen; i++) {
          assert.deepEqual(coord1.docAttributes[i], frozenAttrs[i]);
        }
        partialCommitFreezeChecks++;
      }
    }

    // -----------------------------------------------------------------------
    // Separately test Epoch-Reset Cases where equality is NOT promised
    // -----------------------------------------------------------------------
    {
      const bEpoch = [
        { char: 'a', attributes: { bold: true } },
        { char: 'b', attributes: { italic: true } }
      ];
      const seedAttr = { color: 'green' };

      // Session A: Experiences partial commit (epoch 2)
      const coordA = new ProtocolCoordinator("ab", bEpoch.map(u => u.attributes), [0, 2]);
      coordA.openNewConnection();
      coordA.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, seedAttributes: seedAttr });
      coordA.receiveCallback(1, { type: 'preedit', text: 'ay' });
      // Partial commit 'a' -> attribution epoch becomes 2, baseline for suffix becomes 'y' with seed attributes
      coordA.receiveCallback(1, { type: 'partialCommit', committedLength: 1 });
      // Now candidate suffix becomes 'b' -> live island is 'ab'
      coordA.receiveCallback(1, { type: 'preedit', text: 'b' });

      // Session B: Stays in epoch 1, directly types 'ab'
      const coordB = new ProtocolCoordinator("ab", bEpoch.map(u => u.attributes), [0, 2]);
      coordB.openNewConnection();
      coordB.receiveCallback(1, { type: 'startComposition', start: 0, end: 2, seedAttributes: seedAttr });
      coordB.receiveCallback(1, { type: 'preedit', text: 'ab' });

      // Both have text "ab", but attributes MUST DIVERGE:
      // Session B (epoch 1): 'ab' restores B.text full attributes -> 'b' has italic: true
      assert.equal(coordB.docAttributes[1].italic, true);

      // Session A (epoch 2): remaining suffix baseline was 'y' with seedAttr ({ color: 'green' })!
      // 'b' in epoch 2 has no common match with 'y', so 'b' receives green, NOT italic!
      assert.equal(coordA.docAttributes[1].color, 'green');
      assert.equal(coordA.docAttributes[1].italic, undefined);

      epochResetDivergenceChecks++;
    }

    results.push({
      test: 'Property-I07-I08',
      status: 'PASS',
      description: `Stateful property test executed ${iterations} iterations across independent coordinator instances (${pathIndependenceChecks} path independence checks, ${oracleAgreementChecks} independent oracle comparisons, ${partialCommitFreezeChecks} partial-commit freeze checks, ${epochResetDivergenceChecks} epoch-reset divergence verifications).`
    });
  }

  return results;
}
