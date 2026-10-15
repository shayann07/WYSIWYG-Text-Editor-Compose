import { runTestsR01toR05 } from './test-r01-to-r05.mjs';
import { runTestsR06toR10 } from './test-r06-to-r10.mjs';
import { runTestsR14AndProperties } from './test-r14-and-properties.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

console.log("=== Running Repaired Antigravity Independent Executable Specification Tests (run-20261013-02) ===");
const startTime = performance.now();

const allResults = [];

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

const elapsedMs = (performance.now() - startTime).toFixed(2);
console.log(`\n=== All Executable Tests Completed Successfully in ${elapsedMs} ms ===`);

const outputSummary = {
  runId: 'run-20261013-02',
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
