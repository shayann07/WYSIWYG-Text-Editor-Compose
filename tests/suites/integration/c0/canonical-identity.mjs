/** Exact owner-incarnation/unit identity basis on actual JVM endpoints. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';

const option = key => process.argv.find(arg => arg.startsWith('--' + key + '='))?.slice(key.length + 3);
const root = path.resolve(import.meta.dirname, '../../..'), build = path.resolve(option('build')), output = path.resolve(option('output'));
fs.mkdirSync(output, {recursive: false});
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const sources = ['validation/integration/c0/canonical-identity.mjs', 'validation/integration/c0/jvm-owner-client.mjs',
  'desktop-prototype/src/main/kotlin/editor/EditorEngine.kt', 'desktop-prototype/src/main/kotlin/editor/WebOwnerProbe.kt'];
const report = {status: 'RUNNING', startedAt: new Date().toISOString(), checks: [],
  sources: Object.fromEntries(sources.map(name => [name, sha(fs.readFileSync(path.join(root, name)))])),
  buildIdentitySha256: sha(fs.readFileSync(path.join(build, 'build-identity.json'))),
  scope: 'Actual JVM exact canonical admission basis; private process nonce and string Long IDs, not durable canonical ID allocation, native callbacks or public schema qualification'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const owners = [new JvmOwnerClient(build, path.join(output, 'owner-a'), root), new JvmOwnerClient(build, path.join(output, 'owner-b'), root)];
const edit = text => [{kind: 'replace', start: 0, end: 1, text, style: 0}];
const packet = (frame, text) => ({command: 'canonical-batch', ...canonicalBasis(frame), edits: edit(text), selection: [1, 1]});
save();
try {
  await Promise.all(owners.map(owner => owner.ready));
  const [a, b] = owners;
  for (const owner of owners) {await owner.request({command: 'reset', text: 'کک'}); await owner.request({command: 'external-history'});}
  const original = (await a.request({command: 'snapshot'})).frame, other = (await b.request({command: 'snapshot'})).frame;
  assert.equal(original.revision, other.revision); assert.deepEqual(original.committedUnitIds, other.committedUnitIds);
  assert.notEqual(original.ownerIncarnation, other.ownerIncarnation);
  const wrongOwner = await b.request(packet(original, 'X'));
  assert.equal(wrongOwner.status, 'RejectedOwnerIncarnation'); assert.deepEqual(wrongOwner.frame, other);
  check('A fresh owner refuses a previous endpoint packet even when document revision, epoch and local IDs coincide', {localIdReuseObserved: true});

  const replaced = await a.request(packet(original, 'ک'));
  assert.equal(replaced.status, 'Applied'); assert.equal(replaced.frame.committed, original.committed);
  assert.equal(replaced.frame.revision, original.revision); assert.notDeepEqual(replaced.frame.committedUnitIds, original.committedUnitIds);
  const stale = await a.request(packet(original, 'X'));
  assert.equal(stale.status, 'RejectedBasis'); assert.deepEqual(stale.frame, replaced.frame);
  check('Same-text replacement changes identities while semantic revision stays fixed; the old identity basis now refuses unchanged',
    {before: original.committedUnitIds, after: replaced.frame.committedUnitIds, revision: original.revision});

  await a.request({command: 'select', anchor: 2, focus: 2});
  const moved = (await a.request({command: 'snapshot'})).frame;
  const fresh = await a.request(packet(replaced.frame, 'X'));
  assert.equal(fresh.status, 'Applied'); assert.equal(fresh.frame.committed, 'Xک');
  assert.equal(fresh.frame.committedUnitIds[1], original.committedUnitIds[1]);
  assert.equal(fresh.frame.undo, original.undo);
  check('Caret-only movement preserves an explicit captured identity target and remote admission adds no local snapshot history',
    {unrelatedCaret: moved.selection, unchangedPeerUnitId: fresh.frame.committedUnitIds[1]});

  const invalid = [
    {basisUnitIds: [...fresh.frame.committedUnitIds].reverse()},
    {basisUnitIds: []},
    {basisUnitIds: ['9007199254740993', fresh.frame.committedUnitIds[1]]}
  ];
  for (const bad of invalid) {
    const refused = await a.request({...packet(fresh.frame, 'bad'), ...bad});
    assert.equal(refused.status, 'RejectedBasis'); assert.deepEqual(refused.frame, fresh.frame);
  }
  for (const bad of [{basisUnitIds: fresh.frame.committedUnitIds.map(Number)}, {basisUnitIds: ['9223372036854775808']}, {basisUnitIds: undefined}]) {
    const refused = await a.request({...packet(fresh.frame, 'bad'), ...bad});
    assert.equal(refused.status, 'Rejected'); assert.deepEqual(refused.frame, fresh.frame);
    assert.match(refused.error, /identity|Identity|9223372036854775808/);
  }
  check('Reordered/missing identities and nonmatching exact decimal IDs refuse; numeric, absent or overflowing identity encodings reject before editing',
    {controlKind: 'Protocol-input corruption controls, not implementation mutants', nonmatchingAboveSafeIntegerStringRefused: true});

  const beforeComposition = (await a.request({command: 'snapshot'})).frame;
  const captured = packet(beforeComposition, 'Y');
  await a.request({command: 'select', anchor: 0, focus: 0});
  const candidate = await a.request({command: 'ime', text: '候補', committed: 0, caret: 2, epoch: beforeComposition.epoch});
  assert(candidate.frame.marked !== null);
  const deferred = await a.request(captured);
  assert.equal(deferred.status, 'DeferredComposition'); assert.deepEqual(deferred.frame, candidate.frame);
  const aborted = await a.request({command: 'abort-composition'});
  assert.equal(aborted.frame.committed, beforeComposition.committed); assert.deepEqual(aborted.frame.committedUnitIds, beforeComposition.committedUnitIds);
  assert(aborted.frame.epoch > beforeComposition.epoch);
  const late = await a.request(captured);
  assert.equal(late.status, 'RejectedStaleEpoch'); assert.deepEqual(late.frame, aborted.frame);
  const admitted = await a.request(packet(aborted.frame, 'Y'));
  assert.equal(admitted.status, 'Applied'); assert.equal(admitted.frame.committed, 'Yک');
  check('Composition still defers without mutation; abort with an unchanged committed root invalidates the old epoch while a fresh captured basis succeeds',
    {inputSource: 'Synthetic owner composition, not native IME qualification'});
  report.status = 'CANONICAL_IDENTITY_BASIS_PASS_GATE_OPEN';
  console.log('PASS canonical identity basis: actual JVM owner nonce, exact unit IDs, same-text retarget refusal, caret independence and epoch deferral; C0/S0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  report.ownerTerminations = await Promise.all(owners.map(owner => owner.close()));
  if (report.ownerTerminations.some(end => end.code !== 0 || end.failedToClose) || sources.some(name => sha(fs.readFileSync(path.join(root, name))) !== report.sources[name])) {
    report.status = 'FAIL'; report.error = 'Owner cleanup failed or source changed'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
