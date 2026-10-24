import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL, fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';

const [root, output, input] = process.argv.slice(2);
const cases = JSON.parse(fs.readFileSync(input, 'utf8'));
fs.mkdirSync(output, {recursive: true});
const tiny = path.join(output, 'owned-parent-proof');
for (const dir of ['a', 'b', 'node_modules/owned-conditional']) fs.mkdirSync(path.join(tiny, dir), {recursive: true});
for (const dir of ['a', 'b']) fs.writeFileSync(path.join(tiny, dir, 'leaf.mjs'), 'throw new Error("resolution-only fixture must not execute");');
fs.writeFileSync(path.join(tiny, 'node_modules/owned-conditional/package.json'), JSON.stringify({type:'module',exports:{import:'./esm.mjs',require:'./require.cjs'}}));
for (const name of ['esm.mjs', 'require.cjs']) fs.writeFileSync(path.join(tiny, 'node_modules/owned-conditional', name), 'throw new Error("must not evaluate");');
const parentA = pathToFileURL(path.join(tiny, 'a', 'parent.mjs')).href;
const parentB = pathToFileURL(path.join(tiny, 'b', 'parent.mjs')).href;
assert.equal(import.meta.resolve('./leaf.mjs', parentA), pathToFileURL(path.join(tiny, 'a', 'leaf.mjs')).href);
assert.equal(import.meta.resolve('./leaf.mjs', parentB), pathToFileURL(path.join(tiny, 'b', 'leaf.mjs')).href);
assert.equal(import.meta.resolve('owned-conditional', parentA), pathToFileURL(path.join(tiny, 'node_modules/owned-conditional/esm.mjs')).href);
assert.equal(pathToFileURL(createRequire(parentA).resolve('owned-conditional')).href, pathToFileURL(path.join(tiny, 'node_modules/owned-conditional/require.cjs')).href);
const missingAlias = cases.find(c => c.specifier.includes('validation/') && c.expected.startsWith('file:'));
assert.ok(missingAlias);
const literal = import.meta.resolve(missingAlias.specifier, missingAlias.parentURL);
assert.notEqual(literal, missingAlias.expected);
assert.equal(fs.existsSync(fileURLToPath(literal)), false);

process.argv.push('--output=' + path.join(output, 'v6-unmodified-results.json'));
await import(pathToFileURL(path.join(root, 'tooling/verification/v6.mjs')).href);
const result = JSON.parse(fs.readFileSync(path.join(output, 'v6-unmodified-results.json'), 'utf8'));
assert.equal(result.status, 'PASS');
assert.equal(result.results.length, 16);
const observed = [];
for (const item of cases) {
  const actual = import.meta.resolve(item.specifier, item.parentURL);
  observed.push({...item, actual});
  assert.equal(actual, item.expected, `${item.importer}: ${item.specifier}`);
}
const traces = fs.readFileSync(process.env.COMPOSEDOC_LOADER_TRACE, 'utf8').trim().split('\n').map(JSON.parse);
for (const item of observed) {
  assert.ok(traces.some(t => t.parentURL === item.parentURL && t.url === item.actual && t.conditions.includes('import')),
    `No actual production ESM hook event for ${item.importer}: ${item.specifier}`);
}
fs.writeFileSync(path.join(output, 'resolution.json'), JSON.stringify({status:'PASS', runtime:process.version,
  expectedParentMechanism:'import.meta.resolve(specifier,parentURL) with pinned Node experimental flag',
  parentAwareTinyProof:true, conditionalImportVsRequireProof:true, unchangedV6Groups:16,
  cases:observed, actualHookEvents:traces.length,
  scope:'Executed unmodified V6; actual parent-aware ESM resolution for inspection-only roots, no run20 evaluation'},null,2));
