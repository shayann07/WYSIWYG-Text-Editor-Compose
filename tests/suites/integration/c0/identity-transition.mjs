/** Identity-preserving accepted paragraph transitions in the actual owner, not native IME qualification. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {Authority, Replica, initial, locations} from './ordered-structure-model.mjs';
import {textBlock} from '../../codex/run-20261016-20/structural-model.mjs';
import {compileParagraphResynchronization} from './native-paragraph-projection.mjs';
import {NativeIdentityPairing} from './native-identity-pairing.mjs';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';

const option = name => process.argv.find(arg => arg.startsWith('--' + name + '='))?.slice(name.length + 3);
const root = path.resolve(import.meta.dirname, '../../..'), build = path.resolve(option('build')), output = path.resolve(option('output'));
fs.mkdirSync(output, {recursive: false});
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const names = ['identity-transition.mjs', 'native-identity-pairing.mjs', 'native-paragraph-projection.mjs', 'ordered-structure-model.mjs', 'jvm-owner-client.mjs'];
const fixtureFile = path.join(root, 'validation/urdu/u0/fixtures.json');
const fixtures = JSON.parse(fs.readFileSync(fixtureFile)).filter(f => ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154'].includes(f.id)); assert.equal(fixtures.length, 4);
const report = {status: 'RUNNING', startedAt: new Date().toISOString(), checks: [],
  sources: Object.fromEntries(names.map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))])),
  fixtureSha256: sha(fs.readFileSync(fixtureFile)), buildIdentitySha256: sha(fs.readFileSync(path.join(build, 'build-identity.json'))),
  scope: 'Private supported-flow transition compiler, actual JVM identities/selection/history, explicit synthetic composition fence; no production index, native IME or durable ID qualification'};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const owner = new JvmOwnerClient(build, path.join(output, 'owner'), root);
let serial = 0;
function submit(server, actor, operation) {
  const result = server.submit(actor, `${actor}:transition:${++serial}`, operation); assert.equal(result.outcome, 'Applied');
  fs.appendFileSync(path.join(output, 'authority-events.jsonl'), JSON.stringify({actor, operation, sequence: server.sequence}) + '\n'); return result;
}
function serverWith(id, strings) {const b = initial(); b.main.id = id; b.auxiliary.header.children = strings.map((value, i) => textBlock('paragraph-' + i, value)); return new Authority(b);}
const project = (server, frame) => {const p = compileParagraphResynchronization(server.bundle, 'header', frame.committedUnits.length); assert.equal(p.outcome, 'SupportedParagraphProjection'); return p;};
const pair = (ledger, p, frame) => assert.equal(ledger.bind(p, frame).outcome, 'Paired');
async function setup(server) {
  const before = (await owner.request({command: 'snapshot'})).frame, p = project(server, before);
  const response = await owner.request({command: 'canonical-batch', ...canonicalBasis(before), edits: p.edits, selection: [0, 0]}); assert.equal(response.status, 'Applied');
  const ledger = new NativeIdentityPairing({documentId: server.bundle.main.id, flow: 'header'}); pair(ledger, p, response.frame);
  return {frame: response.frame, ledger, projection: p};
}
function retainedMap(ledger) {return new Map(ledger.snapshot().entries.map(entry => [JSON.stringify(entry.identity), entry.nativeId]));}
async function apply(server, ledger, frame) {
  const next = project(server, frame), beforeIds = retainedMap(ledger), compiled = ledger.compileTransition(next, frame);
  if (compiled.outcome === 'NoChange') {pair(ledger, next, frame); return {frame, next, noOp: true};}
  assert.equal(compiled.outcome, 'Compiled');
  const response = await owner.request(compiled.command); assert.equal(response.status, 'Applied');
  assert.equal(response.frame.undo, frame.undo); assert.equal(response.frame.redo, frame.redo);
  pair(ledger, next, response.frame);
  for (const [key, id] of retainedMap(ledger)) if (beforeIds.has(key)) assert.equal(id, beforeIds.get(key), 'Retained canonical identity was reallocated');
  return {frame: response.frame, next, command: compiled.command, noOp: false};
}
save();
try {
  await owner.ready; await owner.request({command: 'external-history'});
  const identical = serverWith('identical-paragraph-move', ['کک', 'کک']);
  let {frame, ledger} = await setup(identical);
  frame = (await owner.request({command: 'select', anchor: 5, focus: 3})).frame;
  const before = structuredClone(frame), beforeMap = retainedMap(ledger), queuedBeforeMove = {command: 'canonical-batch', ...canonicalBasis(frame), edits: [{kind: 'replace', start: 0, end: 1, text: 'X', style: 0}], selection: [0, 0]};
  submit(identical, 'A', {kind: 'move', node: 'paragraph-1', parent: 'header', right: 'paragraph-0'});
  const moved = await apply(identical, ledger, frame); frame = moved.frame;
  assert(moved.command.edits.some(edit => edit.kind === 'move')); assert.equal(frame.committed, before.committed); assert.equal(frame.revision, before.revision);
  assert(BigInt(frame.identityGeneration) > BigInt(before.identityGeneration)); assert.deepEqual(frame.selection, [2, 0]);
  const late = await owner.request(queuedBeforeMove); assert.equal(late.status, 'RejectedBasis'); assert.deepEqual(late.frame, frame);
  assert.equal(identical.undo('A', 'A:undo-paragraph-move').outcome, 'Applied'); frame = (await apply(identical, ledger, frame)).frame;
  assert.deepEqual(frame.selection, [5, 3]);
  for (const [key, id] of retainedMap(ledger)) if (JSON.parse(key).domain === 'text-unit') assert.equal(id, beforeMap.get(key));
  check('An accepted identical-looking paragraph move and external Undo preserve text-unit IDs, remap backward selection and reconstruct only boundary provenance',
    {visibleRevisionUnchanged: true, identityGenerationAdvanced: true, fullRootReplacement: false});

  const reset = await owner.request({command: 'reset', text: 'کک'}); frame = reset.frame;
  const aba = structuredClone(frame), stale = {command: 'canonical-batch', ...canonicalBasis(frame), edits: [{kind: 'replace', start: 0, end: 1, text: 'X', style: 0}], selection: [0, 0]};
  for (const edit of [{kind: 'move', start: 0, end: 1, destination: 1}, {kind: 'move', start: 1, end: 2, destination: 0}]) {
    const response = await owner.request({command: 'canonical-batch', ...canonicalBasis(frame), edits: [edit], selection: [0, 0]}); assert.equal(response.status, 'Applied'); frame = response.frame;
  }
  assert.equal(frame.revision, aba.revision); assert.deepEqual(frame.committedUnitIds, aba.committedUnitIds); assert(BigInt(frame.identityGeneration) > BigInt(aba.identityGeneration));
  const refusedAba = await owner.request(stale); assert.equal(refusedAba.status, 'RejectedBasis'); assert.deepEqual(refusedAba.frame, frame);
  check('Separate identity-only move/inverse publications fence ABA even after the exact text and ID order return', {nativeIdVectorRestored: true, staleRequestRejected: true});

  const rollback = serverWith('atomic-move-rollback', ['کتاب\nA', 'قلم']); ({frame, ledger} = await setup(rollback));
  const rollbackBefore = structuredClone(frame);
  submit(rollback, 'A', {kind: 'move', node: 'paragraph-1', parent: 'header', right: 'paragraph-0'});
  const rollbackProjection = project(rollback, frame), prepared = ledger.compileTransition(rollbackProjection, frame); assert.equal(prepared.outcome, 'Compiled');
  const invalid = await owner.request({...prepared.command, edits: [...prepared.command.edits, {kind: 'move', start: 0, end: 1, destination: 999}]});
  assert.equal(invalid.status, 'Rejected');
  for (const key of ['committedUnits', 'committedUnitIds', 'identityGeneration', 'revision', 'selection', 'undo', 'redo']) assert.deepEqual(invalid.frame[key], rollbackBefore[key]);
  const retry = await owner.request(prepared.command); assert.equal(retry.status, 'Applied'); frame = retry.frame; pair(ledger, rollbackProjection, frame);
  check('An invalid later move rolls back the whole staged transition and its identity generation; the unchanged captured command can retry', {controlKind: 'Invalid-input rollback control, not implementation mutant'});

  const seeds = [17, 29, 91, 4096]; let generated = 0, transactions = 0, noOps = 0;
  const counts = {move: 0, insert: 0, remove: 0, format: 0};
  for (const seed of seeds) {
    let rngState = seed; const rand = n => {rngState = (Math.imul(rngState, 1664525) + 1013904223) >>> 0; return rngState % n;};
    const server = serverWith('generated-transition-' + seed, ['کک\nک', fixtures[seed % 4].text, 'اردو English']);
    ({frame, ledger} = await setup(server));
    for (let step = 0; step < 24; step++) {
      const paragraphs = server.bundle.auxiliary.header.children, paragraph = paragraphs[rand(paragraphs.length)];
      let operation, category;
      if (step % 4 === 0) {operation = rand(2) ? {kind: 'move', node: paragraphs.at(-1).id, parent: 'header', right: paragraphs[0].id}
        : {kind: 'move', node: paragraphs[0].id, parent: 'header', right: null}; category = 'move';}
      else if (step % 4 === 1 || !paragraph.units.length) {operation = {kind: 'insert', node: paragraph.id, right: paragraph.units[rand(paragraph.units.length + 1)]?.tokenId ?? null,
        text: fixtures[(seed + step) % 4].text, attrs: rand(2) ? {bold: true} : {italic: true}}; category = 'insert';}
      else if (step % 4 === 2) {operation = {kind: 'format', ids: [paragraph.units[rand(paragraph.units.length)].tokenId], patch: {bold: Boolean(rand(2)), italic: Boolean(rand(2))}}; category = 'format';}
      else {operation = {kind: 'remove-owned', ids: [paragraph.units[rand(paragraph.units.length)].tokenId]}; category = 'remove';}
      submit(server, step % 2 ? 'B' : 'A', operation); counts[category]++; generated++;
      const applied = await apply(server, ledger, frame); frame = applied.frame; if (applied.noOp) noOps++; else transactions++;
      assert.deepEqual(frame.committedUnits.map(row => row.slice(1)), applied.next.units.map(unit => [unit.char.charCodeAt(0), unit.kind, unit.style, unit.paragraphStyle]));
    }
  }
  assert.equal(generated, 96); report.generatedOperations = generated; report.nativeTransactions = transactions; report.noOpProjections = noOps;
  check('Four deterministic seeds compare actual authority moves/inserts/deletes/formatting with the actual owner and retain every surviving identity',
    {seeds, generatedOperations: generated, nativeTransactions: transactions, noOpProjections: noOps, categories: counts, fixtures: fixtures.map(f => f.id), scalingQualified: false});

  const composing = serverWith('transition-composition', ['کک', 'قلم']); ({frame, ledger} = await setup(composing));
  const oldProjection = project(composing, frame), replica = new Replica(composing.bundle); replica.beginComposition('header', frame.epoch);
  const accepted = submit(composing, 'A', {kind: 'move', node: 'paragraph-1', parent: 'header', right: 'paragraph-0'}); replica.receive(accepted.packet);
  const next = project(composing, frame), captured = ledger.compileTransition(next, frame); assert.equal(captured.outcome, 'Compiled');
  const epoch = frame.epoch; const candidate = await owner.request({command: 'ime', text: '候補', committed: 0, caret: 2, epoch});
  assert.equal(ledger.compileTransition(next, candidate.frame).outcome, 'DeferredComposition');
  const blocked = await owner.request(captured.command); assert.equal(blocked.status, 'DeferredComposition'); assert.deepEqual(blocked.frame, candidate.frame);
  frame = (await owner.request({command: 'abort-composition'})).frame;
  assert.equal(replica.releaseComposition('header', frame), 'RejectedNativeBoundary');
  const syntheticFence = {verified: true, oldEpoch: epoch, kind: 'qualified-retirement', qualification: 'SYNTHETIC_FIXTURE_NOT_NATIVE_QUALIFICATION'};
  assert.equal(replica.releaseComposition('header', frame, syntheticFence), 'Released'); assert.deepEqual(replica.bundle, composing.bundle);
  pair(ledger, oldProjection, frame);
  const staleEpoch = await owner.request(captured.command); assert.equal(staleEpoch.status, 'RejectedStaleEpoch'); assert.deepEqual(staleEpoch.frame, frame);
  frame = (await apply(composing, ledger, frame)).frame;
  check('The move transition defers composition, requires the replica synthetic-fence gate after abort and recompiles against the new owner epoch',
    {fence: syntheticFence.qualification, nativeOsInputQualified: false});
  report.status = 'IDENTITY_TRANSITION_EXPERIMENT_PASS_GATE_OPEN';
  console.log('PASS identity-preserving transitions: actual paragraph move/Undo, ABA generation, atomic rollback, 96 generated operations and synthetic composition isolation; C0/S0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  report.ownerTermination = await owner.close();
  if (report.ownerTermination.code !== 0 || report.ownerTermination.failedToClose || names.some(name => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== report.sources[name]) || sha(fs.readFileSync(fixtureFile)) !== report.fixtureSha256) {
    report.status = 'FAIL'; report.error = 'Owner cleanup failed or source/fixture changed'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
