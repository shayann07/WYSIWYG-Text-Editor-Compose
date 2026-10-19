/** Bounded portable-value/actual-authority trial beside the V8 baseline. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {Authority, text} from './ordered-structure-model.mjs';
import {DurableAuthority} from './durable-authority.mjs';
import {encodePortableState, decodePortableState, MAX_CHECKPOINT_BYTES, PORTABLE_CHECKPOINT_FORMAT} from './checkpoint-value-codec.mjs';

const option = key => process.argv.find(arg => arg.startsWith('--' + key + '='))?.slice(key.length + 3);
const output = path.resolve(option('output')), root = path.resolve(import.meta.dirname, '../../..');
fs.mkdirSync(output, {recursive: false});
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const files = ['portable-checkpoint.mjs', 'checkpoint-value-codec.mjs', 'checkpoint_reference.py', 'durable-authority.mjs', 'ordered-structure-model.mjs'];
const fixturePath = path.join(root, 'validation/urdu/u0/fixtures.json');
const report = {status: 'RUNNING', startedAt: new Date().toISOString(), checks: [], processes: [],
  sources: Object.fromEntries(files.map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))])),
  fixtureSha256: sha(fs.readFileSync(fixturePath)), scope: 'Private source-bound portable checkpoint experiment; no production schema, authentication, GC, power-loss or native-input qualification'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const rawUnits = value => value.split('').map(char => char.charCodeAt(0));
function reference(bytes, name, expectedFailure = null) {
  const input = path.join(output, name + '.checkpoint.json'), destination = path.join(output, name + '.python.json');
  fs.writeFileSync(input, bytes);
  const command = [option('python') ?? 'python', path.join(import.meta.dirname, 'checkpoint_reference.py'), '--input', input, '--output', destination];
  const startedAt = new Date().toISOString(), child = spawnSync(command[0], command.slice(1), {cwd: root, windowsHide: true, timeout: 20000, encoding: 'utf8'});
  report.processes.push({command, startedAt, finishedAt: new Date().toISOString(), exitCode: child.status, signal: child.signal}); save();
  fs.writeFileSync(path.join(output, name + '-reference.log'), (child.stdout ?? '') + (child.stderr ?? ''));
  assert.ifError(child.error); assert.equal(child.signal, null);
  if (expectedFailure) {
    assert.equal(child.status, 1); assert.match(child.stderr, expectedFailure); assert(!fs.existsSync(destination));
    assert.deepEqual(fs.readFileSync(input), bytes);
    return {rejected: true, expectedError: String(expectedFailure)};
  }
  assert.equal(child.status, 0, child.stderr);
  assert.deepEqual(decodePortableState(fs.readFileSync(destination)), decodePortableState(bytes));
  return {bytes: fs.readFileSync(destination), receipt: JSON.parse(child.stdout)};
}
let serial = 0;
function accept(store, actor, extra) {
  const snapshot = store.snapshot(), epoch = snapshot.epoch;
  const result = store.accept({actor, requestId: epoch ? `${actor}:e${epoch}:${++serial}` : `${actor}:${++serial}`,
    epoch, basisFrontier: snapshot.durableFrontier, ...extra});
  return result;
}
function corruptPayload(store, projection, expected) {
  const original = store.db.prepare('SELECT * FROM tooling_checkpoint').get();
  const bytes = encodePortableState(projection);
  store.db.prepare('UPDATE tooling_checkpoint SET payload=?,payload_hash=?').run(bytes, sha(bytes));
  assert.throws(() => store.load(), expected);
  assert.deepEqual(Buffer.from(store.db.prepare('SELECT payload FROM tooling_checkpoint').get().payload), bytes);
  store.db.prepare('UPDATE tooling_checkpoint SET payload=?,payload_hash=?').run(original.payload, original.payload_hash);
}
save();
try {
  const fixtures = JSON.parse(fs.readFileSync(fixturePath)).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id));
  assert.equal(fixtures.length, 4);
  const values = {present: undefined, negativeZero: -0, literal: ['undefined'], scalar: [null, false, true, 1.5],
    specimens: fixtures.map(f => ({id: f.id, text: f.text, codes: f.utf16CodeUnits}))};
  Object.defineProperty(values, '__proto__', {value: {literal: true}, enumerable: true});
  const encoded = encodePortableState(values), decoded = decodePortableState(encoded);
  assert.deepEqual(decoded, values); assert(!Object.hasOwn(decoded, 'absent')); assert(Object.hasOwn(decoded, 'present'));
  assert.equal(Object.getPrototypeOf(decoded), Object.prototype); assert.equal({}.literal, undefined);
  assert.deepEqual(encodePortableState(decoded), encoded);
  for (const specimen of decoded.specimens) assert.deepEqual(rawUnits(specimen.text), specimen.codes);
  const scalarReference = reference(encoded, 'raw-value-golden');
  assert.equal(scalarReference.receipt.undefinedValues, 1);
  check('Tagged values and independent Python transport preserve field presence, signed zero, literal tags and raw UTF-16',
    {fixtures: fixtures.map(f => f.id), sourceObjectPrototypeUnchanged: true, reference: scalarReference.receipt});

  const control = new Authority();
  control.submit('A', 'A:move', {kind: 'move', node: 'tail', parent: 'item', right: null}); control.undo('A', 'A:undo');
  const jsonControl = JSON.parse(JSON.stringify(control, (_, value) => value instanceof Map ? {mapEntries: [...value]} : value),
    (_, value) => value && Object.keys(value).length === 1 && Array.isArray(value.mapEntries) ? new Map(value.mapEntries) : value);
  Object.setPrototypeOf(jsonControl, Authority.prototype);
  const peerMove = {kind: 'move', node: 'tail', parent: 'body', right: 'list'};
  control.submit('B', 'B:move', peerMove); jsonControl.submit('B', 'B:move', peerMove);
  assert.equal(control.redo('A', 'A:redo').outcome, 'RedoConflict'); assert.equal(jsonControl.redo('A', 'A:redo').outcome, 'Applied');
  check('The naive JSON serialization control still drops an ownership guard and overwrites a peer relocation',
    {controlKind: 'Serialization control, not implementation mutation', originalRedo: 'RedoConflict', jsonRedo: 'Applied'});

  const snapshots = [];
  for (const codec of ['v8', 'portable']) {
    serial = 0; // Identical authored request identities in the two codec lanes.
    const store = new DurableAuthority(path.join(output, codec + '-move.sqlite'), {checkpointCodec: codec});
    try {
      assert.equal(accept(store, 'A', {kind: 'submit', operation: {kind: 'move', node: 'tail', parent: 'item', right: null}}).outcome, 'Applied');
      assert.equal(accept(store, 'A', {kind: 'undo'}).outcome, 'Applied');
      assert.equal(store.compact().outcome, 'Compacted');
      const authority = store.load().authority;
      assert(Object.hasOwn(authority.history[0].redo, 'expectedOwner') && authority.history[0].redo.expectedOwner === undefined);
      if (codec === 'portable') {
        const checkpoint = store.db.prepare('SELECT * FROM tooling_checkpoint').get();
        assert(!Object.hasOwn(JSON.parse(checkpoint.compatibility), 'v8'));
        const converted = reference(Buffer.from(checkpoint.payload), 'actual-authority');
        assert(converted.receipt.undefinedValues >= 1);
        store.db.prepare('UPDATE tooling_checkpoint SET payload=?,payload_hash=?').run(converted.bytes, sha(converted.bytes));
        assert(Object.hasOwn(store.load().authority.history[0].redo, 'expectedOwner'));
        report.authorityReference = converted.receipt;
      }
      assert.equal(accept(store, 'B', {kind: 'submit', operation: peerMove}).outcome, 'Applied');
      const before = store.snapshot();
      assert.equal(accept(store, 'A', {kind: 'redo'}).outcome, 'RedoConflict');
      assert.deepEqual(store.snapshot(), before); snapshots.push(before);
    } finally {store.close();}
  }
  assert.deepEqual(snapshots[0], snapshots[1]);
  check('V8 and Python-reserialized portable checkpoints retain identical authority/history state and refuse conflicting Redo',
    {semanticStateHashes: snapshots.map(s => s.stateHash), fieldPresencePreserved: true, runtimeTagAbsentFromPortableCompatibility: true});

  const guard = new DurableAuthority(path.join(output, 'portable-move.sqlite'), {checkpointCodec: 'portable'});
  try {
    const original = guard.db.prepare('SELECT * FROM tooling_checkpoint').get();
    const projection = decodePortableState(original.payload);
    const missing = structuredClone(projection); delete missing.history[0].redo.expectedOwner;
    corruptPayload(guard, missing, /semantic\/history\/retention drift/);
    const duplicate = structuredClone(projection); duplicate.moveOwners = [['tail', 'test-owner'], ['tail', 'test-owner']];
    corruptPayload(guard, duplicate, /Duplicate checkpoint map key/);
    corruptPayload(guard, {...projection, retiredProducers: ['same-producer', 'same-producer']}, /Duplicate checkpoint producer/);
    corruptPayload(guard, {...projection, unknownFutureField: true}, /Unsupported checkpoint state fields/);
    const wrong = new DurableAuthority(path.join(output, 'portable-move.sqlite'));
    try {assert.throws(() => wrong.load(), /compatibility mismatch/);} finally {wrong.close();}
    assert.deepEqual(guard.snapshot(), snapshots[1]);
    check('Corrupt ownership, duplicate map keys, unknown state fields and codec mismatch reject before adoption',
      {controlKind: 'Input corruption controls, not implementation mutants', checksumsRecomputedForMalformedStates: true, checkpointRetained: true});
  } finally {guard.close();}

  const wire = node => Buffer.from(JSON.stringify([PORTABLE_CHECKPOINT_FORMAT, node]));
  const malformed = [[Buffer.from([0xff]), /utf-8/], [Buffer.from('['), /Expecting value/], [wire(['future', []]), /Unknown value tag/],
    [wire(['value', {}]), /Invalid scalar/], [wire(['object', [['x', ['undefined']], ['x', ['value', 1]]]]), /duplicate record key/],
    [Buffer.from(JSON.stringify(['future-version', ['undefined']])), /Unsupported checkpoint version/],
    [Buffer.alloc(MAX_CHECKPOINT_BYTES + 1), /Checkpoint byte limit/]];
  let deep = ['value', 1]; for (let i = 0; i < 130; i++) deep = ['array', [deep]];
  malformed.push([wire(deep), /traversal limit/]);
  for (const [index, [bad, reason]] of malformed.entries()) {
    assert.throws(() => decodePortableState(bad)); reference(bad, 'malformed-' + index, reason);
  }
  const cycle = {}; cycle.self = cycle;
  const hiddenArray = []; Object.defineProperty(hiddenArray, 'hidden', {value: undefined});
  for (const bad of [cycle, new Map(), new Set(), 1n, Infinity, new Array(2), hiddenArray, {get value() {throw Error('Getter must not execute');}}])
    assert.throws(() => encodePortableState(bad), /Unsupported|unsupported|Nonfinite|Sparse|Accessor|plain records/);
  check('Unsupported grammar, malformed UTF-8, oversized/deep input and runtime-only values fail closed',
    {controlKind: 'Malformed-input controls', independentlyRejectedByBothDecoders: malformed.length, byteLimit: MAX_CHECKPOINT_BYTES, depthLimit: 128});

  for (const fixture of fixtures) {
    const filename = path.join(output, fixture.id + '.sqlite');
    let store = new DurableAuthority(filename, {checkpointCodec: 'portable'}), before;
    try {
      before = store.snapshot();
      assert.equal(accept(store, 'A', {kind: 'submit', operation: {kind: 'insert', node: 'header-p', right: null, text: fixture.text, attrs: {bold: true}}}).outcome, 'Applied');
      assert.equal(store.compact().outcome, 'Compacted');
    } finally {store.close();}
    store = new DurableAuthority(filename, {checkpointCodec: 'portable'});
    try {
      const inserted = store.snapshot(); assert.deepEqual(rawUnits(text(inserted.bundle, 'header')), rawUnits(text(before.bundle, 'header') + fixture.text));
      assert.equal(accept(store, 'A', {kind: 'undo'}).outcome, 'Applied'); assert.deepEqual(store.snapshot().bundle, before.bundle);
      assert.equal(accept(store, 'A', {kind: 'redo'}).outcome, 'Applied'); assert.deepEqual(store.snapshot().bundle, inserted.bundle);
    } finally {store.close();}
  }
  check('All four malformed U0 specimens survive actual portable SQLite reopen and exact attributed Undo/Redo', {fixtures: fixtures.map(f => f.id)});
  report.status = 'PORTABLE_CHECKPOINT_EXPERIMENT_PASS_GATE_OPEN';
  console.log('PASS portable checkpoint experiment: independent Python transport, retained guard, actual authority/SQLite/raw UTF-16; C0/S0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  if (files.some(name => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== report.sources[name]) || sha(fs.readFileSync(fixturePath)) !== report.fixtureSha256) {
    report.status = 'FAIL'; report.error = 'Source/fixture changed during experiment'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
