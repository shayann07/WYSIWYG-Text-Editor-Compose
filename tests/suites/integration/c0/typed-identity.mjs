/** Typed canonical/native pairing with actual owner edits. Private M1/S0 trial. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {Authority, initial, locations} from './ordered-structure-model.mjs';
import {textBlock} from '../../codex/run-20261016-20/structural-model.mjs';
import {compileParagraphResynchronization} from './native-paragraph-projection.mjs';
import {NativeIdentityPairing} from './native-identity-pairing.mjs';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';

const option = key => process.argv.find(arg => arg.startsWith('--' + key + '='))?.slice(key.length + 3);
const root = path.resolve(import.meta.dirname, '../../..'), build = path.resolve(option('build')), output = path.resolve(option('output'));
fs.mkdirSync(output, {recursive: false});
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const sources = ['typed-identity.mjs', 'native-identity-pairing.mjs', 'native-paragraph-projection.mjs', 'ordered-structure-model.mjs', 'jvm-owner-client.mjs'];
const fixturePath = path.join(root, 'validation/urdu/u0/fixtures.json');
const report = {status: 'RUNNING', startedAt: new Date().toISOString(), checks: [],
  sources: Object.fromEntries(sources.map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))])),
  buildIdentitySha256: sha(fs.readFileSync(path.join(build, 'build-identity.json'))), fixtureSha256: sha(fs.readFileSync(fixturePath)),
  scope: 'Private single-flow typed pairing and actual JVM edits; fixture server/owner coordination, not a production identity format, live sync adapter or native IME fence'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const owners = [new JvmOwnerClient(build, path.join(output, 'owner-a'), root), new JvmOwnerClient(build, path.join(output, 'owner-b'), root)];
let serial = 0;
const submit = (server, actor, operation) => {const result = server.submit(actor, `${actor}:typed:${++serial}`, operation); assert.equal(result.outcome, 'Applied'); return result;};
const projection = (server, frame) => {
  const p = compileParagraphResynchronization(server.bundle, 'header', frame.committedUnits.length);
  assert.equal(p.outcome, 'SupportedParagraphProjection'); return p;
};
const pair = (ledger, server, frame) => {assert.equal(ledger.bind(projection(server, frame), frame).outcome, 'Paired');};
async function adopt(owner, server) {
  const frame = (await owner.request({command: 'snapshot'})).frame;
  const response = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame), edits: projection(server, frame).edits, selection: [0, 0]});
  assert.equal(response.status, 'Applied'); return response.frame;
}
async function patch(owner, frame, edits, selection = [0, 0]) {
  const result = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame), edits, selection});
  assert.equal(result.status, 'Applied'); return result.frame;
}
const insert = (start, value, style = 0) => ({kind: 'replace', start, end: start, text: value, style});
const entryFor = (ledger, ref) => ledger.snapshot().entries.find(entry => entry.identity.domain === ref.domain && entry.identity.id === ref.id);
const requireCompiled = result => {assert.equal(result.outcome, 'Compiled'); return result.command;};
save();
try {
  await Promise.all(owners.map(owner => owner.ready));
  for (const owner of owners) await owner.request({command: 'external-history'});
  const [owner, other] = owners, bundle = initial();
  bundle.auxiliary.header.children = [textBlock('header-p', 'کک\nک'), textBlock('header-second', 'کک')];
  bundle.auxiliary.header.children[0].units[0].tokenId = 'boundary:header-second';
  bundle.auxiliary.header.children[0].units[1].tokenId = 'header-second';
  const server = new Authority(bundle);
  let frame = await adopt(owner, server);
  const originalFrame = structuredClone(frame), p = projection(server, frame);
  const ledger = new NativeIdentityPairing({documentId: server.bundle.main.id, flow: 'header'}); pair(ledger, server, frame);
  const ref0 = ledger.reference('text-unit', 'boundary:header-second'), ref1 = ledger.reference('text-unit', 'header-second'),
    boundary = ledger.reference('paragraph-boundary', 'header-second');
  assert.equal(new Map(p.units.map((unit, index) => [unit.tokenId, index])).get(ref0.id), 4);
  assert.equal(entryFor(ledger, ref0).index, 0); assert.equal(entryFor(ledger, ref1).index, 1); assert.equal(entryFor(ledger, boundary).index, 4);
  assert.equal(ledger.compileDeletion([boundary], frame).outcome, 'UnsupportedStructuralTarget');
  const removed = await owner.request(requireCompiled(ledger.compileDeletion([ref0], frame)));
  assert.equal(removed.status, 'Applied'); frame = removed.frame;
  submit(server, 'A', {kind: 'remove-owned', ids: [ref0.id]}); pair(ledger, server, frame);
  assert.equal(frame.committedUnits.filter(row => row[2] === 1).length, 1); assert(!frame.committedUnitIds.includes(originalFrame.committedUnitIds[0]));
  assert.equal(ledger.compileDeletion([ref0], frame).outcome, 'RejectedTargetMissing');
  assert.equal(server.undo('A', 'A:restore-first').outcome, 'Applied');
  frame = await patch(owner, frame, [insert(0, 'ک')]); pair(ledger, server, frame);
  assert.notEqual(entryFor(ledger, ref0).nativeId, originalFrame.committedUnitIds[0]);
  check('Typed text and paragraph-boundary identities disambiguate both prefix collisions and equal ID strings; actual deletion preserves the hard boundary',
    {flatMapWrongIndex: 4, typedTextIndex: 0, typedBoundaryIndex: 4, restoreRetainsCanonicalReferenceWithFreshLocalId: true});

  const otherFrame = await adopt(other, server), otherLedger = new NativeIdentityPairing({documentId: server.bundle.main.id, flow: 'header'});
  pair(otherLedger, server, otherFrame);
  assert.equal(otherLedger.compileDeletion([ref0], otherFrame).outcome, 'RejectedContext');
  const beforeOther = ledger.snapshot(); assert.equal(ledger.bind(projection(server, otherFrame), otherFrame).outcome, 'RejectedOwnerIncarnation'); assert.deepEqual(ledger.snapshot(), beforeOther);
  for (const bad of [{...ref0, flow: 'body'}, {...ref0, documentId: 'other-document'}, {...ref0, pairingGeneration: 'retired-generation'}])
    assert.equal(ledger.compileDeletion([bad], frame).outcome, 'RejectedContext');
  assert.equal(ledger.compileDeletion([ref0, ref0], frame).outcome, 'RejectedDuplicateTarget');
  assert.equal(ledger.compileDeletion('not-an-identity-list', frame).outcome, 'RejectedReference');
  assert.deepEqual((await other.request({command: 'snapshot'})).frame, otherFrame);
  check('Document, flow, endpoint and pairing-generation contexts refuse cross-target reuse without editing', {ownerEndpoints: 2, authenticationClaimed: false});

  const stableLocal = entryFor(ledger, ref0).nativeId;
  const staleCommand = requireCompiled(ledger.compileDeletion([ref0], frame));
  submit(server, 'B', {kind: 'insert', node: 'header-p', right: ref0.id, text: 'ک', attrs: {italic: true}});
  const peerPrefix = locations(server.bundle).get('header-p').node.units[0].tokenId;
  frame = await patch(owner, frame, [insert(0, 'ک', 2)]);
  const stale = await owner.request(staleCommand); assert.equal(stale.status, 'RejectedBasis'); assert.deepEqual(stale.frame, frame);
  assert.equal(ledger.compileDeletion([ref0], frame).outcome, 'RebindRequired'); pair(ledger, server, frame);
  assert.equal(requireCompiled(ledger.compileDeletion([ref0], frame)).edits[0].start, 1); assert.equal(entryFor(ledger, ref0).nativeId, stableLocal);
  submit(server, 'B', {kind: 'remove-owned', ids: [peerPrefix]});
  frame = await patch(owner, frame, [{kind: 'replace', start: 0, end: 1, text: '', style: 0}]); pair(ledger, server, frame);
  assert.equal(requireCompiled(ledger.compileDeletion([ref0], frame)).edits[0].start, 0); assert.equal(entryFor(ledger, ref0).nativeId, stableLocal);
  check('Repeated Urdu references follow surviving identities across peer prefix insertion/deletion; a delayed prepared command refuses', {sameGlyphNotUsedAsIdentity: true});

  submit(server, 'B', {kind: 'insert', node: 'header-p', right: ref1.id, text: 'ک', attrs: {bold: true}});
  frame = await patch(owner, frame, [insert(1, 'ک', 1)]); pair(ledger, server, frame);
  const peerId = frame.committedUnitIds[1], peerRow = structuredClone(frame.committedUnits[1]);
  frame = (await owner.request({command: 'select', anchor: frame.committedUnits.length, focus: 1})).frame;
  const beforeDelete = structuredClone(frame), command = requireCompiled(ledger.compileDeletion([ref0, ref1], frame));
  assert.deepEqual(command.edits.map(edit => [edit.start, edit.end]), [[2, 3], [0, 1]]);
  const result = await owner.request(command); assert.equal(result.status, 'Applied'); frame = result.frame;
  submit(server, 'A', {kind: 'remove-owned', ids: [ref0.id, ref1.id]}); pair(ledger, server, frame);
  assert.deepEqual(frame.committedUnits[frame.committedUnitIds.indexOf(peerId)], peerRow);
  assert.deepEqual(frame.selection, [beforeDelete.committedUnits.length - 2, 0]); assert.equal(frame.undo, beforeDelete.undo);
  assert.equal(frame.committedUnits.filter(row => row[2] === 1).length, 1);
  assert.equal(server.undo('A', 'A:restore-scattered').outcome, 'Applied');
  frame = await patch(owner, frame, [insert(0, 'ک'), insert(2, 'ک')]); pair(ledger, server, frame);
  assert.equal(entryFor(ledger, ref0).index, 0); assert.equal(entryFor(ledger, ref1).index, 2);
  check('Discontiguous selective deletion is one real owner transaction preserving an interleaved peer ID/attributes, backward selection and structural boundary',
    {descendingRanges: [[2, 3], [0, 1]], externalUndoRestoresCanonicalReferencesWithFreshNativeIds: true, localSnapshotHistoryAdded: false});

  const paired = ledger.snapshot(), good = projection(server, frame);
  const badPayload = structuredClone(good); badPayload.units[0].char = 'X';
  const badDuplicate = structuredClone(good); badDuplicate.units[2].identity = {...badDuplicate.units[0].identity}; badDuplicate.units[2].tokenId = badDuplicate.units[0].tokenId;
  const badNative = structuredClone(frame); badNative.committedUnitIds[1] = badNative.committedUnitIds[0];
  const richBody = compileParagraphResynchronization(server.bundle, 'body', frame.committedUnits.length);
  assert.equal(richBody.outcome, 'UnsupportedProjection');
  for (const [value, native] of [[badPayload, frame], [badDuplicate, frame], [good, badNative], [{...good, documentId: 'wrong'}, frame], [richBody, frame]]) {
    assert.notEqual(ledger.bind(value, native).outcome, 'Paired'); assert.deepEqual(ledger.snapshot(), paired);
  }
  const sameGlyphSwap = structuredClone(good);
  for (const property of ['identity', 'tokenId']) [sameGlyphSwap.units[0][property], sameGlyphSwap.units[2][property]] = [sameGlyphSwap.units[2][property], sameGlyphSwap.units[0][property]];
  assert.equal(ledger.bind(sameGlyphSwap, frame).outcome, 'RejectedIdentityReassignment'); assert.deepEqual(ledger.snapshot(), paired);
  assert.equal(ledger.bind(p, originalFrame).outcome, 'RejectedStalePairing'); assert.deepEqual(ledger.snapshot(), paired);
  check('Pairing staging rejects payload, identity, context and stale-frame corruption atomically; equal-looking text cannot authorize native-ID reassignment',
    {controlKind: 'Pairing-input/representation controls, not implementation-source mutants'});

  const preparedBeforeRewrite = requireCompiled(ledger.compileDeletion([ref0], frame));
  const rewritten = await patch(owner, frame, [{kind: 'replace', start: 0, end: 1, text: 'ک', style: 0}]);
  assert.equal(rewritten.revision, frame.revision); assert.notDeepEqual(rewritten.committedUnitIds, frame.committedUnitIds);
  assert.equal(ledger.bind(good, rewritten).outcome, 'RejectedIdentityReassignment'); assert.deepEqual(ledger.snapshot(), paired);
  const lateIdentity = await owner.request(preparedBeforeRewrite); assert.equal(lateIdentity.status, 'RejectedBasis'); assert.deepEqual(lateIdentity.frame, rewritten);
  const rightOfReplacement = locations(server.bundle).get('header-p').node.units[1].tokenId;
  submit(server, 'A', {kind: 'remove-owned', ids: [ref0.id]});
  submit(server, 'A', {kind: 'insert', node: 'header-p', right: rightOfReplacement, text: 'ک', attrs: {}});
  frame = rewritten; pair(ledger, server, frame);
  assert.equal(ledger.compileDeletion([ref0], frame).outcome, 'RejectedTargetMissing');
  check('Same-text native identity replacement cannot silently inherit a retained canonical identity; a stale command refuses and an explicit new canonical identity pairs',
    {oldSemanticReferenceRetargeted: false, controlKind: 'Actual identity-only native rewrite and explicit fixture authority admission'});

  const compiledBeforeIme = requireCompiled(ledger.compileDeletion([ref1], frame));
  await owner.request({command: 'select', anchor: 0, focus: 0});
  const candidate = await owner.request({command: 'ime', text: '候補', committed: 0, caret: 2, epoch: frame.epoch});
  pair(ledger, server, candidate.frame);
  assert.deepEqual(ledger.snapshot().entries.map(entry => entry.nativeId), candidate.frame.committedUnitIds);
  assert.equal(ledger.compileDeletion([ref1], candidate.frame).outcome, 'DeferredComposition');
  frame = (await owner.request({command: 'abort-composition'})).frame;
  assert.equal(ledger.compileDeletion([ref1], frame).outcome, 'RebindRequired'); pair(ledger, server, frame);
  const late = await owner.request(compiledBeforeIme); assert.equal(late.status, 'RejectedStaleEpoch'); assert.deepEqual(late.frame, frame);
  assert.equal(ledger.compileDeletion([ref1], frame).outcome, 'Compiled');
  check('Only committed IDs enter the pairing while provisional candidates defer; abort requires fresh pairing and old compiled epochs refuse', {inputSource: 'Synthetic owner composition, not OS/native fence qualification'});

  const fixtures = JSON.parse(fs.readFileSync(fixturePath)).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id)); assert.equal(fixtures.length, 4);
  for (const fixture of fixtures) {
    const b = initial(); b.main.id = fixture.id; b.auxiliary.header.children = [textBlock('raw-p', fixture.text)];
    b.auxiliary.header.children[0].units.forEach((unit, index) => {unit.attrs = index % 2 ? {italic: true} : {bold: true};});
    const s = new Authority(b), l = new NativeIdentityPairing({documentId: fixture.id, flow: 'header'});
    let native = await adopt(owner, s); pair(l, s, native);
    const before = structuredClone(native), indices = fixture.utf16CodeUnits.map((_, index) => index).filter(index => index % 2 === 0);
    const units = locations(s.bundle).get('raw-p').node.units, refs = indices.map(index => l.reference('text-unit', units[index].tokenId));
    const applied = await owner.request(requireCompiled(l.compileDeletion(refs, native))); assert.equal(applied.status, 'Applied'); native = applied.frame;
    submit(s, 'A', {kind: 'remove-owned', ids: refs.map(ref => ref.id)}); pair(l, s, native);
    assert.deepEqual(native.committedUnits, before.committedUnits.filter((_, index) => !indices.includes(index)));
    assert.deepEqual(native.committedUnitIds, before.committedUnitIds.filter((_, index) => !indices.includes(index)));
    assert.equal(l.compileDeletion([refs[0]], native).outcome, 'RejectedTargetMissing');
  }
  check('All four malformed U0 specimens retain exact unselected UTF-16 units, raw styles and native IDs through typed selective deletion', {fixtures: fixtures.map(f => f.id)});
  report.status = 'TYPED_NATIVE_IDENTITY_PAIRING_PASS_GATE_OPEN';
  console.log('PASS typed native identity pairing: actual JVM collision safety, stable targets, selective peer preservation, atomic pairing and provisional exclusion; C0/S0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  report.ownerTerminations = await Promise.all(owners.map(owner => owner.close()));
  if (report.ownerTerminations.some(end => end.code !== 0 || end.failedToClose) || sources.some(name => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== report.sources[name]) || sha(fs.readFileSync(fixturePath)) !== report.fixtureSha256) {
    report.status = 'FAIL'; report.error = 'Owner cleanup failed or source/fixture changed'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
