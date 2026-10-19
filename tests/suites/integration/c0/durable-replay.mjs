/** Actual SQLite transactions/process exits + actual JVM owner receipt. Not native IME qualification. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import {DurableAuthority, canonical} from './durable-authority.mjs';
import {Authority, text, locations} from './ordered-structure-model.mjs';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';

const option = key => process.argv.find(a => a.startsWith('--' + key + '='))?.slice(key.length + 3);
const output = path.resolve(option('output')), build = path.resolve(option('build'));
const root = path.resolve(import.meta.dirname, '../../..');
fs.mkdirSync(output, {recursive: false});
const sha = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const sources = ['durable-replay.mjs', 'durable-worker.mjs', 'durable-authority.mjs', 'ordered-structure-model.mjs', 'jvm-owner-client.mjs'];
const report = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: [], processes: [],
  scope: 'Private local SQLite/process-fault protocol with actual JVM accepted transaction; no authenticated service, native IME, production schema or power-loss qualification',
  sources: Object.fromEntries(sources.map(name => [name, sha(path.join(import.meta.dirname, name))])), buildIdentitySha256: sha(path.join(build, 'build-identity.json'))};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const database = path.join(output, 'authority.sqlite');
let serial = 0, client;
const owner = new JvmOwnerClient(build, path.join(output, 'owner'), root);
function worker(event, inject = null) {
  const prefix = String(++serial).padStart(3, '0'), filename = path.join(output, prefix + '-event.json');
  fs.writeFileSync(filename, canonical(event));
  const command = [process.execPath, path.join(import.meta.dirname, 'durable-worker.mjs'), '--database=' + database, '--event=' + filename];
  if (inject) command.push('--inject=' + inject);
  const startedAt = new Date().toISOString(), result = spawnSync(command[0], command.slice(1), {cwd: root, encoding: 'utf8', windowsHide: true, timeout: 15000, maxBuffer: 8 * 1024 * 1024});
  fs.writeFileSync(path.join(output, prefix + '-stdout.log'), result.stdout ?? ''); fs.writeFileSync(path.join(output, prefix + '-stderr.log'), result.stderr ?? '');
  report.processes.push({command, startedAt, finishedAt: new Date().toISOString(), exitCode: result.status, signal: result.signal, injection: inject}); save();
  assert.ifError(result.error); assert.equal(result.signal, null);
  assert.equal(result.status, inject === 'before-commit' ? 83 : inject === 'after-commit' ? 84 : 0, result.stderr);
  if (inject) {assert.equal(result.stdout, ''); return null;}
  return JSON.parse(result.stdout);
}
function stored() {const store = new DurableAuthority(database); try {return store.snapshot();} finally {store.close();}}
function openClient() {
  const db = new DatabaseSync(path.join(output, 'client.sqlite'));
  db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS outbox (request_id TEXT PRIMARY KEY, event TEXT NOT NULL, state TEXT NOT NULL); CREATE TABLE IF NOT EXISTS local_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
  return db;
}
function prepare(event) {client.prepare('INSERT INTO outbox VALUES(?,?,?)').run(event.requestId, canonical(event), 'PREPARED_RECOVERY_INTENT');}
save();
try {
  const ready = await owner.ready; report.ownerCommand = owner.command;
  client = openClient(); report.sqliteVersion = client.prepare('SELECT sqlite_version() AS version').get().version;
  const initial = new Authority(), originalText = text(initial.bundle, 'header');
  let frame = (await owner.request({command: 'reset', text: originalText})).frame;
  await owner.request({command: 'external-history'});
  const event = {kind: 'submit', actor: 'A', requestId: 'A:durable-insert',
    operation: {kind: 'insert', node: 'header-p', right: 'header-p:3', text: 'کتاب', attrs: {bold: true, italic: true}}};
  prepare(event);
  // The intent is durable before publication. It is not yet a saved/synchronized acknowledgement.
  assert.equal(client.prepare('SELECT state FROM outbox').get().state, 'PREPARED_RECOVERY_INTENT');
  frame = (await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: [{kind: 'replace', start: 3, end: 3, text: 'کتاب', style: 3}], selection: [7, 7]})).frame;
  assert.equal(frame.committed, originalText.slice(0, 3) + 'کتاب' + originalText.slice(3));
  client.exec('BEGIN IMMEDIATE');
  client.prepare('INSERT INTO local_state VALUES(?,?)').run('projection', canonical(frame));
  client.prepare('UPDATE outbox SET state=? WHERE request_id=?').run('LOCAL_DURABLE_PENDING_SYNC', event.requestId);
  client.exec('COMMIT'); client.close(); client = openClient();
  assert.deepEqual(JSON.parse(client.prepare('SELECT value FROM local_state WHERE key=?').get('projection').value), frame);
  worker(event, 'before-commit');
  const beforeCommit = stored(); assert.equal(beforeCommit.durableFrontier, 0); assert.equal(text(beforeCommit.bundle, 'header'), originalText);
  assert.equal(client.prepare('SELECT state FROM outbox WHERE request_id=?').get(event.requestId).state, 'LOCAL_DURABLE_PENDING_SYNC');
  worker(event, 'after-commit');
  const afterCommit = stored(); assert.equal(afterCommit.durableFrontier, 1); assert.equal(afterCommit.history.length, 1);
  assert.equal(text(afterCommit.bundle, 'header'), frame.committed);
  assert.equal(client.prepare('SELECT state FROM outbox WHERE request_id=?').get(event.requestId).state, 'LOCAL_DURABLE_PENDING_SYNC');
  const retry = worker(event); assert.equal(retry.result.outcome, 'Duplicate'); assert.equal(retry.result.acknowledged, true);
  assert.equal(retry.result.durableFrontier, 1); assert.deepEqual(retry.snapshot, afterCommit);
  const serverUnits = locations(retry.snapshot.bundle).get('header-p').node.units;
  assert.deepEqual(serverUnits.map(u => [u.char.charCodeAt(0), (u.attrs.bold ? 1 : 0) | (u.attrs.italic ? 2 : 0)]), frame.committedUnits.map(u => [u[1], u[3]]));
  client.exec('BEGIN IMMEDIATE');
  client.prepare('INSERT INTO local_state VALUES(?,?)').run('acknowledgedFrontier', '1');
  client.prepare('UPDATE outbox SET state=? WHERE request_id=?').run('ACKNOWLEDGED', event.requestId); client.exec('COMMIT');
  check('Actual accepted owner text and raw attributes survive local reopen and both server process-fault windows',
    {beforeCommitExit: 83, afterCommitBeforeAckExit: 84, durableFrontier: 1, duplicateDidNotReapply: true, ownerRevision: frame.revision, savedAndSyncedStatesDistinct: true});

  const beforeUndo = structuredClone(frame);
  assert.deepEqual((await owner.request({command: 'undo'})).historyRequests, [false]);
  const undoEvent = {kind: 'undo', actor: 'A', requestId: 'A:durable-undo'};
  worker(undoEvent, 'after-commit');
  const undone = stored(); assert.equal(text(undone.bundle, 'header'), originalText);
  assert.equal(worker(undoEvent).result.outcome, 'Duplicate'); assert.deepEqual(stored(), undone);
  let response = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: [{kind: 'replace', start: 3, end: 7, text: '', style: 0}], selection: [3, 3]});
  assert.equal(response.status, 'Applied'); frame = response.frame; assert.equal(frame.committed, originalText);
  assert.deepEqual((await owner.request({command: 'redo'})).historyRequests, [true]);
  const redo = worker({kind: 'redo', actor: 'A', requestId: 'A:durable-redo'}); assert.equal(redo.result.outcome, 'Applied');
  response = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: [{kind: 'replace', start: 3, end: 3, text: 'کتاب', style: 3}], selection: [7, 7]});
  assert.equal(response.status, 'Applied'); frame = response.frame;
  assert.deepEqual(frame.committedUnits.map(u => u.slice(1)), beforeUndo.committedUnits.map(u => u.slice(1)));
  assert.deepEqual(redo.snapshot.bundle, afterCommit.bundle);
  check('Selective history and redo preimages replay after restart; lost Undo acknowledgement is idempotent',
    {undoAcknowledgementLost: true, duplicateUndoFrontier: undone.durableFrontier, redoExactRawAttributes: true, localRootUndoBypassed: true});

  const group = 'B:durable-gesture';
  assert.equal(worker({kind: 'begin', actor: 'B', requestId: 'B:begin', groupId: group, flow: 'footer', selection: null}).result.outcome, 'Opened');
  for (const [index, content] of ['ک', 'تاب'].entries()) assert.equal(worker({kind: 'submit', actor: 'B', requestId: 'B:part:' + index, groupId: group,
    operation: {kind: 'insert', node: 'footer-p', right: null, text: content}}).result.outcome, 'Applied');
  worker({kind: 'close', actor: 'B', requestId: 'B:close', groupId: group, selection: null});
  assert(text(stored().bundle, 'footer').endsWith('کتاب'));
  worker({kind: 'undo', actor: 'B', requestId: 'B:group-undo'});
  assert.equal(text(stored().bundle, 'footer'), text(initial.bundle, 'footer'));
  worker({kind: 'redo', actor: 'B', requestId: 'B:group-redo'});
  assert(text(stored().bundle, 'footer').endsWith('کتاب'));
  check('Gesture identity and one-group selective Undo/Redo persist across separate service processes', {acceptedParts: 2, replayedAtEveryRequest: true});

  const fixtureFile = path.join(root, 'validation/urdu/u0/fixtures.json');
  report.urduFixtureSha256 = sha(fixtureFile);
  const malformed = JSON.parse(fs.readFileSync(fixtureFile, 'utf8')).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id));
  assert.equal(malformed.length, 4);
  for (const fixture of malformed) {
    const rawEvent = {kind: 'submit', actor: 'B', requestId: 'B:raw:' + fixture.id,
      operation: {kind: 'insert', node: 'header-p', right: null, text: fixture.text, attrs: {bold: true}}};
    worker(rawEvent, 'after-commit'); const rawRetry = worker(rawEvent);
    assert.equal(rawRetry.result.outcome, 'Duplicate');
    const start = frame.committedUnits.length;
    const result = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
      edits: [{kind: 'replace', start, end: start, text: fixture.text, style: 1}], selection: [start + fixture.utf16Length, start + fixture.utf16Length]});
    assert.equal(result.status, 'Applied'); frame = result.frame;
    const expected = locations(rawRetry.snapshot.bundle).get('header-p').node.units;
    assert.deepEqual(frame.committedUnits.map(u => u[1]), expected.map(u => u.char.charCodeAt(0)));
    assert.deepEqual(frame.committed.split('').map(c => c.charCodeAt(0)), expected.map(u => u.char.charCodeAt(0)));
    assert.deepEqual(frame.committedUnits.map(u => u[3]), expected.map(u => (u.attrs.bold ? 1 : 0) | (u.attrs.italic ? 2 : 0)));
  }
  check('All four malformed U0 specimens survive SQLite commit/ack loss, replay and JVM JSON transport without unit or attribute substitution',
    {fixtures: malformed.map(f => f.id), rawUTF16AndStringFieldsAgree: true, duplicatedAfterCommit: true});

  worker({kind: 'role', actor: 'service', requestId: 'service:revoke-A', target: 'A', role: 'viewer'});
  const frontier = stored().durableFrontier;
  assert.equal(worker(event).result.outcome, 'RejectedPermission');
  const denied = {kind: 'submit', actor: 'A', requestId: 'A:denied-draft', operation: {kind: 'insert', node: 'header-p', right: null, text: 'محفوظ مسودہ'}};
  prepare(denied); assert.equal(worker(denied).result.outcome, 'RejectedPermission');
  client.close(); client = openClient();
  assert.equal(client.prepare('SELECT state FROM outbox WHERE request_id=?').get(denied.requestId).state, 'PREPARED_RECOVERY_INTENT');
  assert.deepEqual(JSON.parse(client.prepare('SELECT event FROM outbox WHERE request_id=?').get(denied.requestId).event), denied);
  assert.equal(stored().durableFrontier, frontier);
  check('Replayed revocation precedes duplicate lookup and denied user work stays in the local recovery outbox', {frontierUnchanged: frontier, retainedUnsentIntent: true, realAuthentication: false});
  report.status = 'DURABLE_PROCESS_REPLAY_PASS_GATE_OPEN'; report.c0Qualified = false;
  report.unresolved = ['Native callback/storage atomicity and power-loss/fsync qualification', 'Production persistent IDs/schema/compaction and authenticated service',
    'Prepared-but-unconfirmed owner intents require explicit recovery admission, not automatic synchronized acknowledgement'];
  console.log('PASS C0 durable process replay: SQLite commit faults, lost acknowledgements, actual owner attributes, selective history and retained denied work; C0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  try {client?.close();} catch (error) {report.status = 'FAIL'; report.error = 'Client SQLite close failed: ' + error.message; process.exitCode = 1;}
  report.ownerTermination = await owner.close();
  if (report.ownerTermination.failedToClose) {report.status = 'FAIL'; report.error = 'Owned JVM did not close'; process.exitCode = 1;}
  if (Object.entries(report.sources).some(([name, expected]) => sha(path.join(import.meta.dirname, name)) !== expected)) {report.status = 'FAIL'; report.error = 'Durable experiment sources changed'; process.exitCode = 1;}
  report.finishedAt = new Date().toISOString(); save();
}
