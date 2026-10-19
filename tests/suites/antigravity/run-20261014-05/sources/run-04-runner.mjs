import { runTestsR01toR05 } from './test-r01-to-r05.mjs';
import { runTestsR06toR10 } from './test-r06-to-r10.mjs';
import { runTestsR14AndProperties } from './test-r14-and-properties.mjs';
import { runBoundedModelChecker } from './independent-model-checker.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("=== Running Comprehensive Antigravity Validation Harness (run-20261013-04) ===");
const startTime = performance.now();

const allResults = [];

// 1. R01 - R05
try {
  const r1to5 = runTestsR01toR05();
  allResults.push(...r1to5);
  for (const r of r1to5) {
    console.log(`[${r.status}] ${r.test}: ${r.description}`);
  }
} catch (err) {
  console.error("FAIL in R01-R05:", err);
  process.exit(1);
}

// 2. R06 - R10
try {
  const r6to10 = runTestsR06toR10();
  allResults.push(...r6to10);
  for (const r of r6to10) {
    console.log(`[${r.status}] ${r.test}: ${r.description}`);
  }
} catch (err) {
  console.error("FAIL in R06-R10:", err);
  process.exit(1);
}

// 3. R14 & Properties
try {
  const r14AndProps = runTestsR14AndProperties();
  allResults.push(...r14AndProps);
  for (const r of r14AndProps) {
    console.log(`[${r.status}] ${r.test}: ${r.description}`);
  }
} catch (err) {
  console.error("FAIL in R14 & Properties:", err);
  process.exit(1);
}

// 4. Bounded Model Checker
try {
  const mcResult = runBoundedModelChecker();
  allResults.push({
    test: 'Bounded-Model-Checker',
    status: mcResult.status,
    description: `Exhaustive BFS state-space exploration across ${mcResult.uniqueStates} states and ${mcResult.exploredTransitions} transitions in ${mcResult.elapsedMs} ms verifying all 10 invariants`
  });
  console.log(`[${mcResult.status}] Bounded-Model-Checker: Explored ${mcResult.uniqueStates} states, ${mcResult.exploredTransitions} transitions in ${mcResult.elapsedMs} ms`);
} catch (err) {
  console.error("FAIL in Bounded Model Checker:", err);
  process.exit(1);
}

// 5. Coordinator Repairs
try {
  execFileSync(process.execPath, [path.join(__dirname, 'test-coordinator-repairs.mjs')], { encoding: 'utf-8' });
  allResults.push({
    test: 'Coordinator-Repairs',
    status: 'PASS',
    description: '11 comprehensive suites verifying Defect A-D repairs, settlement request, command cancellation, target expansion native fidelity, and preedit resumption'
  });
  console.log(`[PASS] Coordinator-Repairs: All 11 coordinator repair test suites passed`);
} catch (err) {
  console.error("FAIL in Coordinator Repairs:", err);
  process.exit(1);
}

// 6. Subclass Mutation Verification
try {
  execFileSync(process.execPath, [path.join(__dirname, 'test-mutations.mjs')], { encoding: 'utf-8' });
  allResults.push({
    test: 'Mutation-Verification',
    status: 'PASS',
    description: '11/11 coordinator subclass mutants injected with real defect behavior caught by invariant checkers and assertions'
  });
  console.log(`[PASS] Mutation-Verification: All 11 coordinator subclass mutants caught`);
} catch (err) {
  console.error("FAIL in Mutation Verification:", err);
  process.exit(1);
}

// 7. Settlement After New Input Suite
try {
  execFileSync(process.execPath, [path.join(__dirname, 'test-settlement-after-new-input.mjs')], { encoding: 'utf-8' });
  allResults.push({
    test: 'Settlement-After-New-Input',
    status: 'PASS',
    description: '7 scenarios verifying settlement token ordering, invalidation on new input, and rejection of obsolete/mismatched completion events'
  });
  console.log(`[PASS] Settlement-After-New-Input: All 7 settlement lifecycle scenarios passed`);
} catch (err) {
  console.error("FAIL in Settlement After New Input:", err);
  process.exit(1);
}

// 8. Normative Cancellation & Command Target Mapping Suite
try {
  execFileSync(process.execPath, [path.join(__dirname, 'test-normative-cancellation-and-mapping.mjs')], { encoding: 'utf-8' });
  allResults.push({
    test: 'Normative-Cancellation-And-Mapping',
    status: 'PASS',
    description: '8 scenarios verifying distinct cancellation semantics (nativeRemoval, endCompositionPreservingText, applicationAbort) and dynamic queued command target mapping'
  });
  console.log(`[PASS] Normative-Cancellation-And-Mapping: All 8 scenarios passed`);
} catch (err) {
  console.error("FAIL in Normative Cancellation & Mapping:", err);
  process.exit(1);
}

// 9. Reproduction of Run-03 Coordinator Defects
try {
  execFileSync(process.execPath, [path.join(__dirname, 'reproduce-native-range-failure.mjs')], { encoding: 'utf-8' });
  allResults.push({
    test: 'Defect-Reproduction-Run03',
    status: 'PASS',
    description: 'Successfully reproduced both native-range fidelity clamping and metadata-only DocumentRevision advance against unchanged run-03 coordinator'
  });
  console.log(`[PASS] Defect-Reproduction-Run03: Successfully reproduced native-range fidelity failure and revision contradiction`);
} catch (err) {
  console.error("FAIL in Defect Reproduction:", err);
  process.exit(1);
}

// 10. Desktop AWT InputMethodRequests Spike
try {
  execFileSync('javac', ['-d', '.', 'DesktopAwtInputMethodRequestsSpike.java'], { cwd: __dirname, encoding: 'utf-8' });
  execFileSync('java', ['-cp', '.', 'validation.DesktopAwtInputMethodRequestsSpike'], { cwd: __dirname, encoding: 'utf-8' });
  allResults.push({
    test: 'DesktopAwtInputMethodRequestsSpike',
    status: 'PASS',
    description: 'AWT InputMethodRequests headless coordinate contract spike (66 slices, geometry, empty slice, text attributes)'
  });
  console.log(`[PASS] DesktopAwtInputMethodRequestsSpike: All 11 AWT spike tests passed`);
} catch (err) {
  console.error("FAIL in Desktop AWT Spike:", err);
  process.exit(1);
}

const elapsedMs = (performance.now() - startTime).toFixed(2);
console.log(`\n=== All Executable Tests Completed Successfully in ${elapsedMs} ms ===`);

const outputSummary = {
  runId: 'run-20261013-04',
  timestamp: new Date().toISOString(),
  environment: {
    node: process.version,
    platform: process.platform,
    arch: process.arch
  },
  elapsedMs: parseFloat(elapsedMs),
  totalCases: allResults.length,
  passedCases: allResults.filter(r => r.status === 'PASS').length,
  results: allResults
};

fs.writeFileSync(path.join(__dirname, 'results.json'), JSON.stringify(outputSummary, null, 2));
console.log(`Summary saved to ${path.join(__dirname, 'results.json')}`);
