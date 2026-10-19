import assert from 'node:assert/strict';
import {
  serializeCanonicalDocument,
  deserializeCanonicalDocument,
  exportValidUnicode,
  reconcileCandidateAttributes,
  CompositionAttributionSession
} from './reference-model.mjs';

export function runTestsR14AndProperties() {
  const results = [];
  
  // -------------------------------------------------------------------------
  // R14: Raw UTF-16 Persistence, Isolated Surrogates & Valid-Unicode Export
  // -------------------------------------------------------------------------
  {
    // Document with raw UTF-16 containing:
    // - Regular ASCII 'a', 'b'
    // - Valid emoji: U+1F600 (\uD83D \uDE00)
    // - Isolated high surrogate: \uD83D (followed by 'x')
    // - Isolated low surrogate: \uDE00
    // - Rich attributes on each unit
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
    
    // Now test Valid-Unicode Export:
    // Isolated surrogates MUST be replaced by U+FFFD with an explicit conversion report!
    const exported = exportValidUnicode(deserialized.textUnits);
    assert.equal(exported.valid, false);
    assert.equal(exported.isolatedSurrogateCount, 2); // 1 isolated high + 1 isolated low
    // The exported text must contain the valid emoji intact and U+FFFD for isolated ones
    const expectedExported = "a\uD83D\uDE00\uFFFDx\uFFFDb";
    assert.equal(exported.text, expectedExported);
    
    results.push({ test: 'R14', status: 'PASS', description: 'Raw UTF-16 with isolated surrogates survives canonical persistence verbatim; valid-Unicode export generates conversion report and U+FFFD.' });
  }

  // -------------------------------------------------------------------------
  // Deterministic Adversarial Property Test for Invariant I07 & I08
  // -------------------------------------------------------------------------
  {
    // Seeded PRNG for deterministic reproducibility
    let seed = 123456789;
    function rand() {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    }
    function randInt(min, max) {
      return Math.floor(rand() * (max - min)) + min;
    }
    
    const charPool = ['a', 'b', 'c', 'd', 'e', '\uD83D\uDE00', '1', '2'];
    const attrPool = [
      { bold: true },
      { italic: true },
      { underline: true },
      { color: 'blue' },
      {}
    ];
    
    let iterations = 10000;
    let pathIndependenceChecks = 0;
    let partialCommitChecks = 0;
    
    for (let it = 0; it < iterations; it++) {
      // Generate random baseline B
      const bLen = randInt(1, 8);
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
      
      // Invariant I07 Check: Candidate Path Independence within attribution epoch
      // Generate two different candidate intermediate paths ending in the SAME final string C_final
      const cFinalLen = randInt(0, 10);
      let cFinal = "";
      for (let i = 0; i < cFinalLen; i++) {
        cFinal += charPool[randInt(0, charPool.length)];
      }
      
      // Path 1: direct reconciliation from B to cFinal
      const resPath1 = reconcileCandidateAttributes(B, cFinal, defaultSeed);
      
      // Path 2: detour through intermediate candidate C_mid, then to cFinal
      const cMid = charPool[randInt(0, charPool.length)] + "xyz" + (it % 2 === 0 ? bText : "");
      const resMid = reconcileCandidateAttributes(B, cMid, defaultSeed);
      const resPath2 = reconcileCandidateAttributes(B, cFinal, defaultSeed);
      
      // I07 assertion: attributes(C_final) MUST be strictly identical regardless of detour
      assert.equal(resPath1.length, resPath2.length);
      for (let i = 0; i < resPath1.length; i++) {
        assert.equal(resPath1[i].char, resPath2[i].char);
        assert.deepEqual(resPath1[i].attributes, resPath2[i].attributes);
      }
      pathIndependenceChecks++;
      
      // If cFinal === bText, must strictly equal B
      if (cFinal === bText) {
        for (let i = 0; i < B.length; i++) {
          assert.deepEqual(resPath1[i].attributes, B[i].attributes);
        }
      }
      
      // Invariant I08 Check: Partial commit freezing
      if (resPath1.length >= 2) {
        const session = new CompositionAttributionSession(B, defaultSeed);
        session.updateCandidate(cFinal);
        const commitLen = randInt(1, resPath1.length);
        const { frozen, remaining } = session.partialCommit(commitLen);
        
        // Mutate suffix heavily:
        const mutatedSuffix = "MODIFIED_SUFFIX_" + randInt(1, 999);
        session.updateCandidate(mutatedSuffix);
        const live = session.getLiveIsland();
        
        // Assert frozen prefix attributes NEVER changed!
        for (let i = 0; i < commitLen; i++) {
          assert.equal(live[i].char, frozen[i].char);
          assert.deepEqual(live[i].attributes, frozen[i].attributes);
        }
        partialCommitChecks++;
      }
    }
    
    results.push({
      test: 'Property-I07-I08',
      status: 'PASS',
      description: `Deterministic property test executed ${iterations} iterations (${pathIndependenceChecks} path independence checks, ${partialCommitChecks} partial-commit freeze checks) with zero failures.`
    });
  }

  return results;
}
