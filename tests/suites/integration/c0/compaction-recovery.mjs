/** Private tooling checkpoint + expired-offline recovery protocol. Not production storage. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {DurableAuthority, canonical} from './durable-authority.mjs';
import {Authority, Replica, text, locations} from './ordered-structure-model.mjs';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';
import {compileParagraphResynchronization} from './native-paragraph-projection.mjs';

const option = key => process.argv.find(a => a.startsWith('--' + key + '='))?.slice(key.length + 3);
const checkpointCodec = option('checkpoint-codec') ?? 'v8';
const output = path.resolve(option('output')), build = path.resolve(option('build')), root = path.resolve(import.meta.dirname, '../../..');
fs.mkdirSync(output, {recursive: false});
const sha = value => createHash('sha256').update(value).digest('hex');
const sources = ['compaction-recovery.mjs', 'durable-authority.mjs', 'checkpoint-value-codec.mjs', 'durable-worker.mjs', 'ordered-structure-model.mjs', 'jvm-owner-client.mjs', 'native-paragraph-projection.mjs'];
const report = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: [], processes: [],
  sources: Object.fromEntries(sources.map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))])),
  buildIdentitySha256: sha(fs.readFileSync(path.join(build, 'build-identity.json'))),
  checkpointCodec, scope: 'SQLite process-fault compaction and actual JVM offline-frame preservation; explicit source-bound tooling codec, trusted role stub and synthetic native fence only'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
let serial = 0, owner, local;
function worker(database, event = null, {compact = false, inject = null} = {}) {
  const number = String(++serial).padStart(3, '0');
  const command = [process.execPath, path.join(import.meta.dirname, 'durable-worker.mjs'), '--database=' + database, '--checkpoint-codec=' + checkpointCodec];
  if (compact) command.push('--compact');
  else {const file = path.join(output, number + '-event.json'); fs.writeFileSync(file, canonical(event)); command.push('--event=' + file);}
  if (inject) command.push('--inject=' + inject);
  const startedAt = new Date().toISOString(), child = spawnSync(command[0], command.slice(1), {cwd: root, windowsHide: true,
    encoding: 'utf8', timeout: 15000, maxBuffer: 8 * 1024 * 1024});
  fs.writeFileSync(path.join(output, number + '-stdout.log'), child.stdout ?? ''); fs.writeFileSync(path.join(output, number + '-stderr.log'), child.stderr ?? '');
  report.processes.push({command, startedAt, finishedAt: new Date().toISOString(), exitCode: child.status, signal: child.signal, injection: inject}); save();
  assert.ifError(child.error); assert.equal(child.signal, null);
  assert.equal(child.status, inject === 'before-commit' ? 83 : inject === 'after-commit' ? 84 : 0, child.stderr);
  if (inject) {assert.equal(child.stdout, ''); return null;}
  return JSON.parse(child.stdout);
}
function inspect(database, body) {const store = new DurableAuthority(database, {checkpointCodec}); try {return body(store);} finally {store.close();}}
const snapshot = database => inspect(database, store => store.snapshot());
function event(database, actor, id, extra) {
  const state = snapshot(database), epoch = state.epoch;
  return {actor, requestId: epoch ? `${actor}:e${epoch}:${id}` : `${actor}:${id}`, epoch, basisFrontier: state.durableFrontier, ...extra};
}
function apply(database, actor, id, operation, groupId = null, extra = {}) {
  const request = event(database, actor, id, {kind: 'submit', operation, ...(groupId ? {groupId} : {}), ...extra});
  const result = worker(database, request); assert.equal(result.result.outcome, 'Applied'); return result;
}
const file = name => path.join(output, name + '.sqlite');
const canonicalOnly = state => ({bundle: state.bundle, history: state.history, groups: state.groups, retainedUnits: state.retainedUnits,
  roles: state.roles, retiredProducers: state.retiredProducers, canonicalFrontier: state.canonicalFrontier});
save();
try {
  const database = file('history'), group = 'B:retained-gesture';
  worker(database, {kind: 'begin', actor: 'B', requestId: 'B:begin', groupId: group, flow: 'footer', selection: null});
  apply(database, 'B', 'prefix', {kind: 'insert', node: 'footer-p', right: null, text: 'ک', attrs: {bold: true}}, group);
  apply(database, 'B', 'suffix', {kind: 'insert', node: 'footer-p', right: null, text: 'تاب', attrs: {italic: true}}, group);
  worker(database, {kind: 'close', actor: 'B', requestId: 'B:close', groupId: group, selection: null});
  const oldRequest = {kind: 'submit', actor: 'A', requestId: 'A:peer', operation: {kind: 'insert', node: 'header-p', right: null, text: ' peer'}};
  worker(database, oldRequest); const before = snapshot(database);
  worker(database, null, {compact: true, inject: 'before-commit'});
  assert.deepEqual(snapshot(database), before);
  worker(database, null, {compact: true, inject: 'after-commit'});
  const after = snapshot(database);
  assert.deepEqual(canonicalOnly(after), canonicalOnly(before)); assert.equal(after.epoch, 1);
  assert.equal(after.durableFrontier, before.durableFrontier); assert.equal(after.minimumBasis, before.durableFrontier); assert.equal(after.replayedRows, 0);
  assert.equal(inspect(database, s => s.db.prepare('SELECT count(*) AS n FROM accepted_events').get().n), 0);
  assert.equal(worker(database, null, {compact: true}).result.outcome, 'AlreadyCompacted');
  const expired = worker(database, oldRequest); assert.equal(expired.result.outcome, 'ExpiredEpochRecoveryRequired');
  assert.deepEqual(expired.result.retainedIntent, oldRequest); assert.equal(expired.result.acknowledged, false);
  assert.equal(worker(database, {...oldRequest, epoch: 1, basisFrontier: after.minimumBasis}).result.outcome, 'RejectedEpochIdentity');
  const stale = event(database, 'A', 'stale-basis', {kind: 'submit', basisFrontier: after.minimumBasis - 1, operation: oldRequest.operation});
  assert.equal(worker(database, stale).result.outcome, 'ExpiredBasisRecoveryRequired');
  assert.equal(worker(database, {...stale, requestId: 'A:e1:future', basisFrontier: after.durableFrontier + 1}).result.outcome, 'RejectedFutureBasis');
  assert.deepEqual(snapshot(database), after);
  assert.equal(worker(database, event(database, 'B', 'undo', {kind: 'undo'})).result.outcome, 'Applied');
  assert.equal(text(snapshot(database).bundle, 'footer'), 'صفحہ'); assert(text(snapshot(database).bundle, 'header').endsWith(' peer'));
  assert.equal(worker(database, event(database, 'B', 'redo', {kind: 'redo'})).result.outcome, 'Applied');
  assert.deepEqual(snapshot(database).bundle, before.bundle);
  check('Checkpoint COMMIT faults preserve canonical state and selective grouped history while old epochs/bases cannot reapply',
    {removedRows: before.replayedRows, checkpointReplayRows: after.replayedRows, retainedGestureParts: 2, expiredWorkRetained: true, oldIdsCannotBeRelabeled: true});

  const retained = file('retained');
  apply(retained, 'A', 'format', {kind: 'format', ids: ['header-p:2'], patch: {italic: true}});
  apply(retained, 'B', 'delete', {kind: 'remove-owned', ids: ['header-p:2']});
  worker(retained, event(retained, 'A', 'undo-format', {kind: 'undo'}));
  const retainedBefore = snapshot(retained); assert(retainedBefore.retainedUnits.length > 0);
  worker(retained, null, {compact: true}); assert.deepEqual(canonicalOnly(snapshot(retained)), canonicalOnly(retainedBefore));
  worker(retained, event(retained, 'B', 'undo-delete', {kind: 'undo'}));
  assert.deepEqual(locations(snapshot(retained).bundle).get('header-p').node.units, locations(new Authority().bundle).get('header-p').node.units);
  check('A deleted preimage updated by peer formatting Undo survives checkpoint and restores exact attribution', {retainedUnitsBefore: retainedBefore.retainedUnits.length, undoneStyleDidNotResurrect: true});

  const moved = file('move-owner');
  apply(moved, 'A', 'move', {kind: 'move', node: 'tail', parent: 'item', right: null});
  worker(moved, event(moved, 'A', 'undo-move', {kind: 'undo'}));
  worker(moved, null, {compact: true});
  const presentUndefined = inspect(moved, store => {
    const inverse = store.load().authority.history.find(h => h.actor === 'A').redo;
    return Object.hasOwn(inverse, 'expectedOwner') && inverse.expectedOwner === undefined;
  }); assert.equal(presentUndefined, true);
  apply(moved, 'B', 'peer-move', {kind: 'move', node: 'tail', parent: 'body', right: 'list'});
  const peerState = snapshot(moved);
  assert.equal(worker(moved, event(moved, 'A', 'redo-move', {kind: 'redo'})).result.outcome, 'RedoConflict');
  assert.deepEqual(snapshot(moved), peerState);
  const control = new Authority();
  control.submit('A', 'A:move', {kind: 'move', node: 'tail', parent: 'item', right: null}); control.undo('A', 'A:undo');
  const jsonControl = JSON.parse(JSON.stringify(control, (_, value) => value instanceof Map ? {mapEntries: [...value]} : value),
    (_, value) => value && Object.keys(value).length === 1 && Array.isArray(value.mapEntries) ? new Map(value.mapEntries) : value);
  Object.setPrototypeOf(jsonControl, Authority.prototype);
  assert(Object.hasOwn(control.history[0].redo, 'expectedOwner')); assert(!Object.hasOwn(jsonControl.history[0].redo, 'expectedOwner'));
  const peerMove = {kind: 'move', node: 'tail', parent: 'body', right: 'list'};
  control.submit('B', 'B:move', peerMove); jsonControl.submit('B', 'B:move', peerMove);
  const protectedPeer = structuredClone(control.bundle);
  assert.equal(control.redo('A', 'A:redo').outcome, 'RedoConflict');
  assert.equal(jsonControl.redo('A', 'A:redo').outcome, 'Applied'); assert.notDeepEqual(jsonControl.bundle, protectedPeer);
  check('Checkpoint preserves present-but-undefined inverse ownership so Redo refuses a later peer move',
    {fieldPresencePreserved: true, peerMoveNotOverwritten: true, jsonSerializationControl: 'Drops ownership guard and wrongly overwrites peer relocation; not an implementation mutant'});

  const open = file('producer-retirement'), openGroup = 'A:phone', laptopGroup = 'A:laptop';
  worker(open, {kind: 'begin', actor: 'A', requestId: 'A:open-phone', producerId: 'phone', groupId: openGroup, flow: 'header', selection: null});
  apply(open, 'A', 'phone-partial', {kind: 'insert', node: 'header-p', right: null, text: 'ک'}, openGroup, {producerId: 'phone'});
  worker(open, {kind: 'begin', actor: 'A', requestId: 'A:open-laptop', producerId: 'laptop', groupId: laptopGroup, flow: 'footer', selection: null});
  apply(open, 'A', 'laptop-partial', {kind: 'insert', node: 'footer-p', right: null, text: 'X', attrs: {italic: true}}, laptopGroup, {producerId: 'laptop'});
  const openBefore = snapshot(open); assert.equal(worker(open, null, {compact: true}).result.outcome, 'DeferredOpenGestures');
  assert.deepEqual(snapshot(open), openBefore);
  const crossProducer = event(open, 'A', 'cross-producer', {kind: 'submit', producerId: 'laptop', groupId: openGroup, operation: {kind: 'insert', node: 'header-p', right: null, text: 'wrong device'}});
  assert.equal(worker(open, crossProducer).result.outcome, 'RejectedProducerGroup'); assert.deepEqual(snapshot(open), openBefore);
  worker(open, {kind: 'retire-producer', actor: 'service', requestId: 'service:retire', target: 'A', targetProducer: 'phone'});
  assert.equal(snapshot(open).roles.A, 'editor');
  assert.equal(snapshot(open).groups.find(([id]) => id === laptopGroup)[1].open, true);
  assert.equal(worker(open, null, {compact: true}).result.outcome, 'DeferredOpenGestures');
  const late = {kind: 'submit', actor: 'A', requestId: 'A:late-suffix', producerId: 'phone', groupId: openGroup, operation: {kind: 'insert', node: 'header-p', right: null, text: 'تاب'}};
  assert.equal(worker(open, late).result.outcome, 'RejectedProducerRetired');
  worker(open, event(open, 'A', 'close-laptop', {kind: 'close', producerId: 'laptop', groupId: laptopGroup, selection: null}));
  const closed = snapshot(open);
  assert.equal(worker(open, event(open, 'A', 'late-close-laptop', {kind: 'close', producerId: 'laptop', groupId: laptopGroup, selection: {changed: true}})).result.outcome, 'RejectedGroupClosed');
  assert.deepEqual(snapshot(open), closed);
  assert.equal(worker(open, null, {compact: true}).result.outcome, 'Compacted');
  const retired = snapshot(open); assert(text(retired.bundle, 'header').endsWith('ک'));
  assert.equal(worker(open, late).result.outcome, 'RejectedProducerRetired'); assert.deepEqual(snapshot(open), retired);
  worker(open, event(open, 'A', 'laptop-undo', {kind: 'undo', producerId: 'laptop'}));
  assert.equal(text(snapshot(open).bundle, 'footer'), 'صفحہ'); assert(text(snapshot(open).bundle, 'header').endsWith('ک'));
  worker(open, event(open, 'A', 'laptop-redo', {kind: 'redo', producerId: 'laptop'}));
  assert.deepEqual(snapshot(open).bundle, retired.bundle);
  apply(open, 'A', 'laptop-new-edit', {kind: 'insert', node: 'footer-p', right: null, text: ' live'}, null, {producerId: 'laptop'});
  check('Producer retirement preserves author permissions and another device gesture/history while blocking the retired producer',
    {partialPrefixPreserved: 'ک', authorStillEditor: true, otherProducerRemainsOpenUntilItsOwnClose: true,
      otherProducerUndoDoesNotConsumeRetiredHistory: true, crossProducerAppendRefused: true, nativeFenceQualifiedByServer: false});

  const origins = file('producer-redo');
  apply(origins, 'A', 'phone-word', {kind: 'insert', node: 'header-p', right: null, text: 'کتاب', attrs: {bold: true}}, null, {producerId: 'phone'});
  worker(origins, event(origins, 'A', 'phone-undo', {kind: 'undo', producerId: 'phone'}));
  apply(origins, 'A', 'laptop-peer', {kind: 'insert', node: 'header-p', right: null, text: ' peer', attrs: {italic: true}}, null, {producerId: 'laptop'});
  worker(origins, null, {compact: true});
  assert.equal(worker(origins, event(origins, 'A', 'phone-redo', {kind: 'redo', producerId: 'phone'})).result.outcome, 'Applied');
  assert.equal(text(snapshot(origins).bundle, 'header'), 'سرورقکتاب peer');
  worker(origins, event(origins, 'A', 'discard-laptop-history', {kind: 'discard-own-history', producerId: 'laptop'}));
  assert(snapshot(origins).history.every(item => item.producer === canonical(['A', 'phone'])));
  assert.equal(worker(origins, event(origins, 'A', 'phone-undo-again', {kind: 'undo', producerId: 'phone'})).result.outcome, 'Applied');
  assert.equal(text(snapshot(origins).bundle, 'header'), 'سرورق peer');
  check('A second device does not abandon another device Redo, and history discard stays in its producer scope',
    {sameAuthorTwoProducers: true, producerRedoSurvivesPeerEditAndCheckpoint: true, discardDoesNotClearOtherProducerHistory: true});

  const guarded = file('checkpoint-integrity');
  apply(guarded, 'A', 'one', {kind: 'insert', node: 'header-p', right: null, text: 'کتاب'}); worker(guarded, null, {compact: true});
  inspect(guarded, store => {
    const original = store.db.prepare('SELECT * FROM tooling_checkpoint').get();
    store.db.prepare('UPDATE tooling_checkpoint SET compatibility=?').run('unsupported-runtime-or-source');
    assert.throws(() => store.snapshot(), /compatibility mismatch/);
    assert.equal(store.db.prepare('SELECT compatibility FROM tooling_checkpoint').get().compatibility, 'unsupported-runtime-or-source');
    store.db.prepare('UPDATE tooling_checkpoint SET compatibility=?').run(original.compatibility);
    store.db.prepare('UPDATE tooling_checkpoint SET payload_hash=?').run('corrupted-fixture-hash');
    assert.throws(() => store.snapshot(), /checksum mismatch/);
    store.db.prepare('UPDATE tooling_checkpoint SET payload_hash=?').run(original.payload_hash);
    assert.equal(store.snapshot().epoch, 1);
  });
  check('Incompatible runtime/source and altered checkpoint data refuse without migrating or replacing the retained database', {control: 'Corruption/compatibility input controls, not source mutants', originalCheckpointRetained: true});

  // Actual owner: preserve offline committed text and candidate before an explicit fenced resync.
  const offline = file('offline'); apply(offline, 'B', 'base-peer', {kind: 'insert', node: 'footer-p', right: null, text: ' base'});
  const accepted = snapshot(offline), replica = new Replica(accepted.bundle);
  assert.equal(replica.resynchronize({sequence: accepted.canonicalFrontier, bundle: accepted.bundle}), 'Resynchronized');
  owner = new JvmOwnerClient(build, path.join(output, 'owner'), root); await owner.ready; report.ownerCommand = owner.command;
  let frame = (await owner.request({command: 'reset', text: text(accepted.bundle, 'header')})).frame;
  await owner.request({command: 'external-history'});
  const unsent = {kind: 'submit', actor: 'A', requestId: 'A:offline-book', epoch: 0, basisFrontier: accepted.durableFrontier,
    operation: {kind: 'insert', node: 'header-p', right: 'header-p:3', text: 'کتاب', attrs: {bold: true, italic: true}}};
  const inserted = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: [{kind: 'replace', start: 3, end: 3, text: 'کتاب', style: 3}], selection: [7, 7]});
  assert.equal(inserted.status, 'Applied'); frame = inserted.frame;
  frame = (await owner.request({command: 'ime', text: '候補', committed: 0, caret: 1, epoch: frame.epoch})).frame;
  const composing = structuredClone(frame), nativeEpoch = frame.epoch;
  assert(frame.marked !== null); replica.beginComposition('header', nativeEpoch);
  local = new DatabaseSync(file('local-recovery')); local.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE recovery (id TEXT PRIMARY KEY, intent TEXT NOT NULL, frame TEXT NOT NULL, state TEXT NOT NULL)');
  local.prepare('INSERT INTO recovery VALUES(?,?,?,?)').run(unsent.requestId, canonical(unsent), canonical(composing), 'UNSENT_WITH_CANDIDATE');
  apply(offline, 'B', 'new-peer', {kind: 'insert', node: 'header-p', right: null, text: '!\nsoft', attrs: {italic: true}});
  apply(offline, 'B', 'split-header', {kind: 'split', node: 'header-p', right: 'header-p:3'});
  apply(offline, 'B', 'bold-header', {kind: 'format', ids: ['header-p:0'], patch: {bold: true}});
  worker(offline, null, {compact: true});
  const fresh = snapshot(offline), expiredOffline = worker(offline, unsent);
  assert.equal(expiredOffline.result.outcome, 'ExpiredEpochRecoveryRequired'); assert.deepEqual(expiredOffline.result.retainedIntent, unsent);
  assert.equal(expiredOffline.result.acknowledged, false);
  assert.deepEqual((await owner.request({command: 'snapshot'})).frame.units, composing.units);
  assert.equal(replica.resynchronize({sequence: fresh.canonicalFrontier, bundle: fresh.bundle}), 'DeferredComposition');
  local.close(); local = new DatabaseSync(file('local-recovery'));
  const recovery = local.prepare('SELECT * FROM recovery').get();
  assert.deepEqual(JSON.parse(recovery.frame), composing); assert.deepEqual(JSON.parse(recovery.intent), unsent);
  frame = (await owner.request({command: 'abort-composition', epoch: nativeEpoch})).frame;
  assert.equal(replica.releaseComposition('header', frame), 'RejectedNativeBoundary');
  assert.equal(replica.releaseComposition('header', frame, {kind: 'qualified-retirement', oldEpoch: nativeEpoch, verified: true,
    evidence: 'SYNTHETIC_FENCE_FIXTURE_NOT_OS_QUALIFICATION'}), 'Released');
  assert.equal(replica.resynchronize({sequence: fresh.canonicalFrontier, bundle: fresh.bundle}), 'Resynchronized');
  const projection = compileParagraphResynchronization(fresh.bundle, 'header', frame.committedUnits.length);
  assert.equal(projection.outcome, 'SupportedParagraphProjection');
  const expected = projection.units, beforeResync = structuredClone(frame);
  const reconciled = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: projection.edits, selection: [0, 0]});
  assert.equal(reconciled.status, 'Applied'); frame = reconciled.frame;
  assert.equal(frame.committed, text(fresh.bundle, 'header')); assert(!frame.committed.includes('کتاب'));
  assert.deepEqual(frame.committedUnits.map(u => u.slice(1)), expected.map(u => [u.char.charCodeAt(0), u.kind, u.style, u.paragraphStyle]), 'Explicit resynchronization lost raw attributes or hard/soft break semantics');
  assert.equal(frame.undo, beforeResync.undo); assert.equal(frame.revision, beforeResync.revision + 1);
  assert(expected.some(u => u.char === '\n' && u.kind === 0)); assert(expected.some(u => u.kind === 1));
  assert(expected.some(u => u.style === 1)); assert(expected.some(u => u.style === 2));
  assert.deepEqual(JSON.parse(local.prepare('SELECT frame FROM recovery').get().frame).units, composing.units);
  assert.equal(local.prepare('SELECT state FROM recovery').get().state, 'UNSENT_WITH_CANDIDATE');
  assert.equal((await owner.request({command: 'ime', text: 'late', committed: 4, caret: null, epoch: nativeEpoch})).status, 'RejectedStaleEpoch');
  const lastBlock = fresh.bundle.auxiliary.header.children.at(-1).id;
  const continued = apply(offline, 'A', 'new-owner-edit', {kind: 'insert', node: lastBlock, right: null, text: ' نیا'});
  const continuedText = text(continued.snapshot.bundle, 'header');
  const next = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame),
    edits: [{kind: 'replace', start: frame.committedUnits.length, end: frame.committedUnits.length, text: ' نیا', style: 0}],
    selection: [continuedText.length, continuedText.length]});
  assert.equal(next.status, 'Applied'); assert.equal(next.frame.committed, continuedText);
  const continuedProjection = compileParagraphResynchronization(continued.snapshot.bundle, 'header', next.frame.committedUnits.length);
  assert.deepEqual(next.frame.committedUnits.map(u => u.slice(1)), continuedProjection.units.map(u => [u.char.charCodeAt(0), u.kind, u.style, u.paragraphStyle]));
  const unsupported = structuredClone(continued.snapshot.bundle); unsupported.auxiliary.header.children[0].units[0].attrs.color = '#ff0000';
  assert.equal(compileParagraphResynchronization(unsupported, 'header', next.frame.committedUnits.length).outcome, 'UnsupportedProjection');
  const malformedSnapshot = structuredClone(continued.snapshot.bundle);
  malformedSnapshot.auxiliary.header.children[0].units[1].tokenId = malformedSnapshot.auxiliary.header.children[0].units[0].tokenId;
  assert.equal(compileParagraphResynchronization(malformedSnapshot, 'header', next.frame.committedUnits.length).outcome, 'UnsupportedProjection');
  assert.equal(compileParagraphResynchronization(continued.snapshot.bundle, 'body', next.frame.committedUnits.length).outcome, 'UnsupportedProjection');
  assert.deepEqual((await owner.request({command: 'snapshot'})).frame.committedUnits, next.frame.committedUnits);
  assert.deepEqual(JSON.parse(local.prepare('SELECT intent FROM recovery').get().intent), unsent);
  check('Actual owner preserves the attributed offline frame/candidate durably before explicit post-fence resync, then continues under a fresh epoch',
    {oldIntentAcknowledged: false, recoveryFrameExactAfterReopen: true, provisionalCandidateNeverBroadcast: true,
      nativeFence: 'SYNTHETIC_ONLY', explicitResynchronizationReplacesProjection: true, oldCallbacksRejected: true, newEpochEditingWorks: true});
  check('Explicit owner resynchronization preserves peer B/I attributes and hard/soft break kinds in one transaction; unsupported rich mappings refuse',
    {rawAttributesAndStructureExact: true, onePublication: true, localSnapshotHistoryUntouched: true, richMappingRefusalDoesNotEditOwner: true});
  const fixturePath = path.join(root, 'validation/urdu/u0/fixtures.json'); report.urduFixtureSha256 = sha(fs.readFileSync(fixturePath));
  const malformed = JSON.parse(fs.readFileSync(fixturePath, 'utf8')).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id));
  assert.equal(malformed.length, 4); let rawFrame = next.frame;
  for (const fixture of malformed) {
    const specimen = structuredClone(fresh.bundle);
    specimen.auxiliary.header.children = [{id: 'raw-fixture-paragraph', type: 'paragraph', props: {}, units: fixture.utf16CodeUnits.map((code, i) =>
      ({tokenId: fixture.id + ':' + i, char: String.fromCharCode(code), kind: 'text', attrs: i % 2 ? {italic: true} : {bold: true}, originSessionId: null}))}];
    const raw = compileParagraphResynchronization(specimen, 'header', rawFrame.committedUnits.length);
    assert.equal(raw.outcome, 'SupportedParagraphProjection');
    const applied = await owner.request({command: 'canonical-batch', ...canonicalBasis(rawFrame), edits: raw.edits, selection: [0, 0]});
    assert.equal(applied.status, 'Applied'); rawFrame = applied.frame;
    assert.deepEqual(rawFrame.committedUnits.map(u => u.slice(1)), fixture.utf16CodeUnits.map((code, i) => [code, 0, i % 2 ? 2 : 1, 0]));
    assert.deepEqual(rawFrame.committed.split('').map(c => c.charCodeAt(0)), fixture.utf16CodeUnits);
  }
  check('The new projection compiler preserves all four malformed U0 raw-unit specimens and alternating attributes in the actual owner',
    {fixtures: malformed.map(f => f.id), scope: 'Isolated projection fixtures after the offline/reconnect trace; not additional service or native IME qualification'});
  report.status = 'COMPACTION_OFFLINE_RECOVERY_PASS_GATE_OPEN'; report.c0Qualified = false;
  report.unresolved = ['Neither tooling codec is an accepted production schema', 'Semantic inverse/resource/offline lease garbage collection is not performed',
    'Native storage publication/fence qualification and production recovery UI/authentication', 'Concurrent server writers, power loss, scalable storage and retention budgets'];
  console.log('PASS C0 compaction/offline recovery: atomic checkpoint, epoch/basis fences, retained history, actual owner recovery frame and post-fence progress; C0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  try {local?.close();} catch (error) {report.status = 'FAIL'; report.error = 'Recovery SQLite close failed: ' + error.message; process.exitCode = 1;}
  report.ownerTermination = owner ? await owner.close() : {notStarted: true};
  if (report.ownerTermination.failedToClose || Object.entries(report.sources).some(([name, expected]) => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== expected) ||
      (report.urduFixtureSha256 && sha(fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json'))) !== report.urduFixtureSha256)) {
    report.status = 'FAIL'; report.error = 'Owned JVM did not close or source changed'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
