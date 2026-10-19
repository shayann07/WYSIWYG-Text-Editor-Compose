/** Actual owned service-process contention with explicit pre-COMMIT pipe barriers. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import {spawn, spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {DurableAuthority, canonical} from './durable-authority.mjs';
import {text, locations} from './ordered-structure-model.mjs';

const option = key => process.argv.find(arg => arg.startsWith('--' + key + '='))?.slice(key.length + 3);
const output = path.resolve(option('output')), root = path.resolve(import.meta.dirname, '../../..');
fs.mkdirSync(output, {recursive: false});
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const sourceNames = ['writer-contention.mjs', 'durable-authority.mjs', 'durable-worker.mjs', 'ordered-structure-model.mjs', 'checkpoint-value-codec.mjs'];
const fixtureBytes = fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json'));
const fixtures = JSON.parse(fixtureBytes).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id));
assert.equal(fixtures.length, 4);
const report = {status: 'RUNNING', startedAt: new Date().toISOString(), checks: [], processes: [], node: process.version,
  sources: Object.fromEntries(sourceNames.map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))])),
  fixtures: fixtures.map(f => f.id), fixtureSha256: sha(fixtureBytes),
  scope: 'Bounded actual service processes and SQLite contention; explicit barrier/process-exit controls, not throughput, power-loss, native input, authentication or production storage qualification'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
let serial = 0;
const held = new Set();
const laneStates = [];
function command(database, codec, event = null, hold = false) {
  const prefix = String(++serial).padStart(3, '0'), args = [path.join(import.meta.dirname, 'durable-worker.mjs'), '--database=' + database, '--checkpoint-codec=' + codec];
  if (event) {
    const encoded = canonical(event), file = path.join(output, 'intent-' + sha(encoded) + '.json');
    if (fs.existsSync(file)) assert.equal(fs.readFileSync(file, 'utf8'), encoded);
    else fs.writeFileSync(file, encoded, {flag: 'wx'});
    args.push('--event=' + file);
  }
  else args.push('--compact');
  if (hold) args.push('--hold-before-commit');
  return {prefix, args};
}
function worker(database, codec, event = null) {
  const {prefix, args} = command(database, codec, event), startedAt = new Date().toISOString();
  const child = spawnSync(process.execPath, args, {cwd: root, windowsHide: true, timeout: 15000, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024});
  fs.writeFileSync(path.join(output, prefix + '-stdout.log'), child.stdout ?? ''); fs.writeFileSync(path.join(output, prefix + '-stderr.log'), child.stderr ?? '');
  report.processes.push({command: [process.execPath, ...args], startedAt, finishedAt: new Date().toISOString(), exitCode: child.status, signal: child.signal}); save();
  assert.ifError(child.error); assert.equal(child.status, 0, child.stderr); assert.equal(child.signal, null);
  return JSON.parse(child.stdout);
}
async function hold(database, codec, event = null) {
  const {prefix, args} = command(database, codec, event, true), startedAt = new Date().toISOString();
  const child = spawn(process.execPath, args, {cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});
  let stdout = '', stderr = '', readyResolve, readyReject, doneResolve;
  const ready = new Promise((resolve, reject) => {readyResolve = resolve; readyReject = reject;});
  const done = new Promise(resolve => {doneResolve = resolve;});
  const lineReader = readline.createInterface({input: child.stdout});
  lineReader.on('line', line => {stdout += line + '\n'; try {const value = JSON.parse(line); if (value.status === 'TRANSACTION_HELD') readyResolve(value);} catch (error) {readyReject(error);}});
  child.stderr.on('data', bytes => {stderr += bytes;});
  child.on('error', readyReject);
  const entry = {child, done, release: signal => {child.stdin.end(signal);}}; held.add(entry);
  child.on('close', (code, signal) => {
    lineReader.close(); held.delete(entry);
    fs.writeFileSync(path.join(output, prefix + '-stdout.log'), stdout); fs.writeFileSync(path.join(output, prefix + '-stderr.log'), stderr);
    report.processes.push({command: [process.execPath, ...args], pid: child.pid, startedAt, finishedAt: new Date().toISOString(), exitCode: code, signal, barrierControl: true}); save();
    readyReject(new Error('Holder exited before handshake: ' + code + ': ' + stderr)); doneResolve({code, signal, stdout, stderr});
  });
  let timeout;
  try {
    const acquired = await Promise.race([ready, new Promise((_, reject) => {timeout = setTimeout(() => reject(Error('Owned holder handshake timeout')), 10000);})]);
    assert.equal(acquired.pid, child.pid); return entry;
  } finally {clearTimeout(timeout);}
}
async function finish(entry, commit) {
  entry.release(commit ? 'C' : 'X');
  let timeout;
  try {
    const result = await Promise.race([entry.done, new Promise((_, reject) => {timeout = setTimeout(() => reject(Error('Owned holder exit timeout')), 10000);})]);
    assert.equal(result.signal, null); assert.equal(result.code, commit ? 0 : 83, result.stderr);
    return commit ? JSON.parse(result.stdout.trim().split('\n').at(-1)) : result;
  } finally {clearTimeout(timeout);}
}
function snapshot(database, codec) {
  const store = new DurableAuthority(database, {checkpointCodec: codec});
  try {return store.snapshot();} finally {store.close();}
}
function event(database, codec, actor, id, extra) {
  const state = snapshot(database, codec);
  return {actor, requestId: `${actor}:e${state.epoch}:${id}`, epoch: state.epoch, basisFrontier: state.durableFrontier, ...extra};
}
const insert = (text, attrs) => ({kind: 'submit', operation: {kind: 'insert', node: 'header-p', right: null, text, attrs}});
const raw = value => value.split('').map(char => char.charCodeAt(0));
function busy(reply, original, before) {
  assert.equal(reply.result.outcome, 'RetryableBusy'); assert.equal(reply.result.acknowledged, false);
  if (original) assert.deepEqual(reply.result.retainedIntent, original);
  else assert(!Object.hasOwn(reply.result, 'retainedIntent'));
  assert.deepEqual(reply.snapshot, before);
}
function seed(database, codec) {
  const result = worker(database, codec, {actor: 'A', requestId: 'A:seed', kind: 'submit', operation: {kind: 'insert', node: 'footer-p', right: null, text: 'ابتدا'}});
  assert.equal(result.result.outcome, 'Applied'); assert.equal(worker(database, codec).result.outcome, 'Compacted');
}
save();
try {
  for (const codec of ['v8', 'portable']) {
    const database = path.join(output, codec + '-commit.sqlite'); seed(database, codec);
    const before = snapshot(database, codec), a = event(database, codec, 'A', 'held-a', insert('کتاب', {italic: true})),
      b = event(database, codec, 'B', 'pending-b', insert(fixtures[0].text, {bold: true}));
    const holder = await hold(database, codec, a);
    busy(worker(database, codec, b), b, before); busy(worker(database, codec), null, before);
    assert.deepEqual(snapshot(database, codec), before);
    const committed = await finish(holder, true); assert.equal(committed.result.outcome, 'Applied');
    const peer = worker(database, codec, b); assert.equal(peer.result.outcome, 'Applied');
    const units = locations(peer.snapshot.bundle).get('header-p').node.units;
    const aIds = new Set(locations(committed.snapshot.bundle).get('header-p').node.units.map(unit => unit.tokenId));
    const peerUnits = units.filter(unit => !aIds.has(unit.tokenId));
    assert.equal(peerUnits.length, fixtures[0].utf16CodeUnits.length);
    assert(peerUnits.every(unit => unit.attrs.bold === true));
    assert.equal(worker(database, codec, b).result.outcome, 'Duplicate');
    const undone = worker(database, codec, event(database, codec, 'A', 'undo', {kind: 'undo'}));
    assert.equal(undone.result.outcome, 'Applied');
    assert.deepEqual(locations(undone.snapshot.bundle).get('header-p').node.units.filter(unit => peerUnits.some(peer => peer.tokenId === unit.tokenId)), peerUnits);
    assert.deepEqual(raw(text(undone.snapshot.bundle, 'header')), raw(text(before.bundle, 'header') + fixtures[0].text));
    const redone = worker(database, codec, event(database, codec, 'A', 'redo', {kind: 'redo'}));
    assert.deepEqual(redone.snapshot.bundle, peer.snapshot.bundle);
    const duplicate = worker(database, codec, a); assert.equal(duplicate.result.outcome, 'Duplicate'); assert.deepEqual(duplicate.snapshot, redone.snapshot);
    check(codec + ': held writer and compactor contention retain exact pending work; retry/duplicate and selective history preserve peers',
      {fixture: fixtures[0].id, typedBusy: true, peerRawAttributesUnchanged: true, compactorDidNotRotateEpoch: true});

    const crashDb = path.join(output, codec + '-abort.sqlite'); seed(crashDb, codec);
    const crashBefore = snapshot(crashDb, codec), abort = event(crashDb, codec, 'A', 'uncommitted', insert(fixtures[1].text, {italic: true})),
      waiting = event(crashDb, codec, 'B', 'waiting', insert(fixtures[2].text, {bold: true}));
    const doomed = await hold(crashDb, codec, abort);
    busy(worker(crashDb, codec, waiting), waiting, crashBefore); await finish(doomed, false);
    assert.deepEqual(snapshot(crashDb, codec), crashBefore);
    const first = worker(crashDb, codec, waiting); assert.equal(first.result.outcome, 'Applied');
    const second = worker(crashDb, codec, abort); assert.equal(second.result.outcome, 'Applied');
    assert.deepEqual(raw(text(second.snapshot.bundle, 'header')), raw(text(crashBefore.bundle, 'header') + fixtures[2].text + fixtures[1].text));
    const retainedUnits = locations(second.snapshot.bundle).get('header-p').node.units;
    assert(retainedUnits.slice(-fixtures[1].utf16CodeUnits.length).every(unit => unit.attrs.italic === true));
    const replay = worker(crashDb, codec, abort); assert.equal(replay.result.outcome, 'Duplicate'); assert.deepEqual(replay.snapshot, second.snapshot);
    check(codec + ': process exit 83 rolls back the held writer and both retained requests later apply exactly once',
      {control: 'Explicit process-fault barrier, not source mutant', fixtures: [fixtures[1].id, fixtures[2].id]});

    const compactionBefore = snapshot(database, codec), pending = event(database, codec, 'B', 'old-epoch', insert(fixtures[3].text, {italic: true}));
    const compactor = await hold(database, codec);
    busy(worker(database, codec, pending), pending, compactionBefore);
    const compacted = await finish(compactor, true); assert.equal(compacted.result.outcome, 'Compacted');
    const expired = worker(database, codec, pending);
    assert.equal(expired.result.outcome, 'ExpiredEpochRecoveryRequired'); assert.equal(expired.result.acknowledged, false); assert.deepEqual(expired.result.retainedIntent, pending);
    assert.deepEqual(expired.snapshot, compacted.snapshot);
    const recovery = event(database, codec, 'B', 'explicit-recovery', insert(fixtures[3].text, {italic: true}));
    const recovered = worker(database, codec, recovery); assert.equal(recovered.result.outcome, 'Applied');
    assert.deepEqual(raw(text(recovered.snapshot.bundle, 'header')), raw(text(compacted.snapshot.bundle, 'header') + fixtures[3].text));
    check(codec + ': writer blocked by compaction retains its old epoch and requires a separate explicit recovery action',
      {fixture: fixtures[3].id, oldIntentUnmodified: true, silentlyRetagged: false});
    laneStates.push({postCommit: redone.snapshot, postAbort: second.snapshot, postRecovery: recovered.snapshot});
    const readOnly = new DurableAuthority(database, {checkpointCodec: codec});
    try {
      const unchanged = readOnly.snapshot(); readOnly.db.exec('PRAGMA query_only=ON');
      assert.throws(() => readOnly.accept(event(database, codec, 'A', 'must-not-acknowledge', insert('خطا', {}))), error => error.errcode === 8);
      assert.deepEqual(readOnly.snapshot(), unchanged);
    } finally {readOnly.close();}
    check(codec + ': a non-BUSY write failure propagates without acknowledgement or state change',
      {control: 'Connection-local read-only failure control, not an implementation mutant', sqliteCode: 8});
  }
  assert.deepEqual(laneStates[0], laneStates[1]);
  check('V8 and portable lanes finish with identical canonical/history/frontier state in all three contention scenarios',
    {snapshotsCompared: 3, semanticHashIncluded: true});
  report.status = 'WRITER_CONTENTION_EXPERIMENT_PASS_GATE_OPEN';
  console.log('PASS C0 writer contention: actual service-process barriers, retained retry/duplicate/history, precommit rollback and epoch recovery; C0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  for (const entry of [...held]) {
    try {await finish(entry, false);} catch {entry.child.kill(); await entry.done; report.status = 'FAIL'; report.cleanupForced = true; process.exitCode = 1;}
  }
  if (sourceNames.some(name => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== report.sources[name]) ||
      sha(fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json'))) !== report.fixtureSha256) {
    report.status = 'FAIL'; report.error = 'Source/fixture changed during trial'; process.exitCode = 1;
  }
  report.retainedIntentFiles = fs.readdirSync(output).filter(name => name.startsWith('intent-')).length;
  report.heldProcessesRemaining = held.size; report.finishedAt = new Date().toISOString(); save();
}
