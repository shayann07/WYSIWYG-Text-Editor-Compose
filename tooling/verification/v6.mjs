// Execute immutable historical witnesses with their recorded path relocation.
// No source rewriting, expected-value substitution or sealed-output mutation.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {registerHooks, syncBuiltinESMExports} from 'node:module';

const root = path.resolve(import.meta.dirname, '../..');
// Sealed results were recorded on one runtime; ICU-dependent segmentation is environment-specific elsewhere.
const sealed = name => JSON.parse(fs.readFileSync(path.join(root, 'tests/reviews/v6-finalization-20261019', name), 'utf8'));
const corpus = sealed('inherited-results.json').results.find(r => r.name === 'unicodeCorpus')?.detail ?? {};
const recorded = {node: sealed('results.json').runtime, icu: corpus.icu, unicode: corpus.runtimeUnicode};
const actual = {node: process.version, icu: process.versions.icu, unicode: process.versions.unicode};
const drift = Object.keys(recorded).filter(key => recorded[key] && recorded[key] !== actual[key]);
if (drift.length) {
  console.warn(`Runtime differs from sealed evidence (${drift.map(key => `${key} ${actual[key]}, sealed ${recorded[key]}`).join('; ')}). ` +
    'Treat ICU-dependent output as environment-specific, not as a regression; .nvmrc names the evidence runtime.');
}
function mapped(value) {
  if (value instanceof URL) value = fileURLToPath(value);
  if (typeof value !== 'string') return value;
  const absolute = path.resolve(value);
  for (const [old, target] of [
    ['tests/validation', 'tests/suites'], ['validation', 'tests/suites'],
    ['tests/docs', 'docs'], ['tests/research', 'tests/archive/research'],
  ]) {
    const prefix = path.join(root, old);
    if (absolute === prefix || absolute.startsWith(prefix + path.sep)) {
      return path.join(root, target) + absolute.slice(prefix.length);
    }
  }
  return value;
}
for (const key of ['readFileSync', 'existsSync', 'statSync']) {
  const operation = fs[key];
  fs[key] = (value, ...args) => operation(mapped(value), ...args);
}
syncBuiltinESMExports();
registerHooks({resolve(specifier, context, next) {
  if (specifier.startsWith('file:')) return next(pathToFileURL(mapped(fileURLToPath(specifier))).href, context);
  if (specifier.startsWith('.') && context.parentURL?.startsWith('file:')) {
    const target = fileURLToPath(new URL(specifier, context.parentURL));
    return next(pathToFileURL(mapped(target)).href, context);
  }
  return next(specifier, context);
}});
await import(pathToFileURL(path.join(root, 'tests/reviews/v6-finalization-20261019/verify.mjs')).href);
