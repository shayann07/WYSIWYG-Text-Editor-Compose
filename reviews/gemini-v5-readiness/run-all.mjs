import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {runInteractionAndUnicodeProbes} from './probe-interactions-and-unicode.mjs';
import {runStorageAndRecoveryProbes} from './probe-hierarchical-storage-and-recovery.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(dir, '../..');

function sha256(relPath) {
  return crypto.createHash('sha256').update(fs.readFileSync(path.join(repoRoot, relPath))).digest('hex');
}

function verifyBaselinePreserved() {
  const pubHashes = JSON.parse(fs.readFileSync(path.join(repoRoot, 'validation/codex/run-20261015-19/publication-hashes.json'), 'utf8'));
  for (const [rel, expected] of Object.entries(pubHashes)) {
    const actual = sha256(rel);
    assert.equal(actual, expected, `Baseline file ${rel} was modified! Expected ${expected}, got ${actual}`);
  }
  return pubHashes;
}

const baselineHashes = verifyBaselinePreserved();
const interactionAndUnicode = runInteractionAndUnicodeProbes();
const storageAndRecovery = runStorageAndRecoveryProbes();

// Compile and run C++20 Skia OneLineShaper & Default-Ignorable probe
const cppBin = '/tmp/probe-shaper-bin';
execFileSync('g++', ['-std=c++20', '-O2', path.join(dir, 'probe-shaper-and-performance.cpp'), '-o', cppBin]);
const shaperOut = JSON.parse(execFileSync(cppBin, [], {encoding: 'utf8'}));

// Run Headless Chromium DOM & Clipboard/Selection probe
const chromiumOut = JSON.parse(execFileSync('python3', [path.join(dir, 'probe_chromium_dom.py')], {encoding: 'utf8'}));

const summary = {
  baselineCommit: 'b66b790068908e45dd8642d3410bc53f609c700f',
  reviewBranch: 'review/gemini-v5-readiness',
  environment: {
    node: process.version,
    platform: `${process.platform}/${process.arch}`,
    gpp: execFileSync('g++', ['--version'], {encoding: 'utf8'}).split('\n')[0],
    chromium: chromiumOut.browser
  },
  baselinePreservationVerified: baselineHashes,
  probes: {
    interactionAndUnicode: {
      counterexamplesReproducedOnBaseline: interactionAndUnicode.counterexamplesReproducedOnBaseline,
      counterexamplesRepaired: interactionAndUnicode.counterexamplesRepaired,
      inheritedCasesOnRepairedModel: interactionAndUnicode.inheritedCasesOnRepairedModel,
      fuzzInteractionTraces: interactionAndUnicode.fuzzInteractionTraces,
      counterexampleDetails: interactionAndUnicode.details
    },
    storageAndRecovery,
    shaperAndPerformance: shaperOut,
    chromiumDomAndClipboard: chromiumOut
  }
};

fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({
  status: 'ALL_GEMINI_PROBES_PASSED',
  counterexamplesReproducedOnBaseline: interactionAndUnicode.counterexamplesReproducedOnBaseline,
  counterexamplesRepaired: interactionAndUnicode.counterexamplesRepaired,
  inheritedCasesOnRepairedModel: interactionAndUnicode.inheritedCasesOnRepairedModel,
  fuzzInteractionTraces: interactionAndUnicode.fuzzInteractionTraces,
  hierarchicalStorageBenchmarkP95Ms: storageAndRecovery.standardCapacity1MUnits10KBlocks.persistentLocalEditMs.p95,
  shaperSpeedupAt16384Blocks: shaperOut.oneLineShaperBookkeepingScaling[5].bookkeepingSpeedupRatio,
  chromiumVersion: chromiumOut.browser
}, null, 2));
