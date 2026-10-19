/** Actual EditorEngine + ordered-service experiment; callbacks are synthetic, not OS IME. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {Authority, Replica, text, locations} from './ordered-structure-model.mjs';
import {JvmOwnerClient, canonicalBasis} from './jvm-owner-client.mjs';

const root = path.resolve(import.meta.dirname, '../../..');
const option = name => process.argv.find(a => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const build = path.resolve(option('build')), output = path.resolve(option('output'));
const cancel = option('terminal') === 'abort';
const overflow = option('queue') === 'overflow';
fs.mkdirSync(output, {recursive: false});
const report = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: [],
  scope: 'Actual JVM owner, synthetic AWT-style composition data, private ordered service and in-memory external semantic-ID adapter; not native OS/production integration'};
report.terminalMode = cancel ? 'explicit-application-abort-with-synthetic-qualified-fence' : 'synthetic-terminal-callback';
report.queueMode = overflow ? 'two-entry-overflow-and-explicit-resynchronization' : 'normal';
const sha = value => createHash('sha256').update(value).digest('hex');
report.sources = Object.fromEntries(['composition-admission.mjs', 'ordered-structure-model.mjs', 'jvm-owner-client.mjs'].map(name => [name, sha(fs.readFileSync(path.join(import.meta.dirname, name)))]));
report.buildIdentitySha256 = sha(fs.readFileSync(path.join(build, 'build-identity.json')));
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
const check = (name, evidence) => {report.checks.push({name, status: 'PASS', evidence}); save();};
const bits = attrs => (attrs.bold ? 1 : 0) | (attrs.italic ? 2 : 0);
const attrs = value => ({...(value & 1 ? {bold: true} : {}), ...(value & 2 ? {italic: true} : {})});
const headerUnits = server => locations(server.bundle).get('header-p').node.units;
const owner = new JvmOwnerClient(build, path.join(output, 'owner'), root);
let termination;
save();
try {
  await owner.ready;
  const server = new Authority(), replica = new Replica(server.bundle, overflow ? {maxEntries: 2} : {}), peer = new Replica(server.bundle);
  report.ownerCommand = owner.command;
  let sent = 0;
  function deliver() {
    for (const packet of server.packets.slice(sent)) {
      replica.receive(JSON.parse(JSON.stringify(packet))); peer.receive(JSON.parse(JSON.stringify(packet)));
    }
    sent = server.packets.length;
  }
  let frame = (await owner.request({command: 'reset', text: text(server.bundle, 'header')})).frame;
  await owner.request({command: 'external-history'});
  frame = (await owner.request({command: 'select', anchor: 2, focus: 3})).frame;
  let localToExternal = new Map(frame.committedUnits.map((unit, i) => [unit[0], headerUnits(server)[i].tokenId]));
  function capture(frame) {
    const ids = frame.committedUnits.map(u => localToExternal.get(u[0]));
    const point = offset => ({left: ids[offset - 1] ?? null, right: ids[offset] ?? null});
    return {flow: 'header', anchor: point(frame.selection[0]), focus: point(frame.selection[1])};
  }
  function resolve(selection, target) {
    const ids = target.map(u => u.tokenId);
    const point = p => {
      if (p.right === null) return ids.length;
      const right = ids.indexOf(p.right), left = ids.indexOf(p.left);
      if (right >= 0) return right;
      if (left >= 0) return left + 1;
      if (p.left === null) return 0;
      throw Error('Logical selection anchor lost; no guessed caret');
    };
    return [point(selection.anchor), point(selection.focus)];
  }
  const originalSelection = capture(frame), initial = structuredClone(frame), group = 'A:ime:one';
  assert.equal(server.beginGesture('A', group, 'header', originalSelection).outcome, 'Opened');
  const epoch = frame.epoch;
  frame = (await owner.request({command: 'ime', text: 'ک候補', committed: 0, caret: 1, epoch})).frame;
  assert(frame.marked !== null && frame.committed === initial.committed);
  replica.beginComposition('header', epoch);
  server.submit('B', 'B:before', {kind: 'insert', node: 'header-p', right: 'header-p:0', text: 'X', attrs: {italic: true}}); deliver();
  assert.equal(replica.sequence, 0); assert.equal(replica.releaseComposition('header', initial), 'RejectedNativeBoundary');
  const nativeBeforeRemote = (await owner.request({command: 'snapshot'})).frame;
  assert.deepEqual(nativeBeforeRemote.units, frame.units);
  const wrongFlow = server.submit('A', 'A:wrong-flow', {kind: 'insert', node: 'p', right: null, text: 'no'}, true, group);
  assert.equal(wrongFlow.outcome, 'RejectedGroupScope');

  function publishAccepted(before, after, id) {
    const old = new Set(before.committedUnits.map(u => u[0])), next = new Set(after.committedUnits.map(u => u[0]));
    const removed = before.committedUnits.filter(u => !next.has(u[0])).map(u => localToExternal.get(u[0]));
    const inserted = after.committedUnits.filter(u => !old.has(u[0]));
    const operations = [];
    if (removed.length) operations.push({kind: 'remove-owned', ids: removed});
    if (inserted.length) {
      const start = after.committedUnits.findIndex(u => u[0] === inserted[0][0]);
      assert.deepEqual(after.committedUnits.slice(start, start + inserted.length).map(u => u[0]), inserted.map(u => u[0]));
      assert(inserted.every(u => u[2] === 0 && u[3] === inserted[0][3]));
      const right = after.committedUnits.slice(start + inserted.length).map(u => localToExternal.get(u[0])).find(Boolean) ?? null;
      operations.push({kind: 'insert', node: 'header-p', right, text: inserted.map(u => String.fromCharCode(u[1])).join(''), attrs: attrs(inserted[0][3])});
    }
    if (!operations.length) return;
    const existing = new Set(headerUnits(server).map(u => u.tokenId));
    const result = server.submit('A', id, operations.length === 1 ? operations[0] : {kind: 'batch', operations}, true, group);
    assert.equal(result.outcome, 'Applied');
    const allocated = headerUnits(server).filter(u => !existing.has(u.tokenId));
    assert.equal(allocated.length, inserted.length);
    inserted.forEach((u, i) => {assert.equal(u[1], allocated[i].char.charCodeAt(0)); localToExternal.set(u[0], allocated[i].tokenId);});
    deliver();
  }

  const beforePartial = frame;
  frame = (await owner.request({command: 'ime', text: 'ک候補', committed: 1, caret: 2, epoch})).frame;
  publishAccepted(beforePartial, frame, 'A:ime:prefix');
  assert.equal(text(peer.bundle, 'header').includes('候補'), false);
  assert(text(peer.bundle, 'header').includes('ک'));
  const afterPartial = structuredClone(frame);
  server.submit('B', 'B:inside', {kind: 'insert', node: 'header-p', right: 'header-p:3', text: 'M', attrs: {bold: true}}); deliver();
  server.submit('B', 'B:after', {kind: 'insert', node: 'header-p', right: null, text: 'Z', attrs: {italic: true}}); deliver();
  const queuedMove = await owner.request({command: 'select', anchor: 0, focus: 0, epoch});
  assert.equal(queuedMove.status, 'Deferred'); assert.deepEqual(queuedMove.frame.units, afterPartial.units);
  const refused = await owner.request({command: 'canonical-batch', ...canonicalBasis(queuedMove.frame),
    edits: [{kind: 'replace', start: 0, end: 0, text: 'must defer', style: 0}], selection: [0, 0]});
  assert.equal(refused.status, 'DeferredComposition'); assert.deepEqual(refused.frame.units, afterPartial.units);
  assert.equal(replica.releaseComposition('header', refused.frame), 'RejectedNativeBoundary');
  check('Before/inside/after remote effects remain buffered while actual owner candidate and coordinate basis stay intact',
    {remoteQueue: replica.pending.size, currentFlow: 'header', sourceEpoch: epoch, candidateNotBroadcast: true, queuedCaretMove: 'Deferred'});

  frame = (await owner.request(cancel ? {command: 'abort-composition', epoch} : {command: 'ime', text: 'تاب', committed: 3, caret: null, epoch})).frame;
  assert.equal(frame.marked, null); assert.equal(frame.epoch, epoch + 1);
  assert.equal(frame.committed, initial.committed.slice(0, 2) + (cancel ? 'ک' : 'کتاب') + initial.committed.slice(3));
  publishAccepted(afterPartial, frame, 'A:ime:suffix');
  const target = headerUnits(server), lastOwn = localToExternal.get(frame.committedUnits.findLast(u => !initial.committedUnits.some(v => v[0] === u[0]))[0]);
  const endPoint = {left: lastOwn, right: 'header-p:3'};
  server.closeGesture('A', group, {flow: 'header', anchor: endPoint, focus: endPoint});
  assert.equal(replica.releaseComposition('header', frame), 'RejectedNativeBoundary');
  if (cancel) check('Core abort preserves the accepted prefix but cannot itself qualify native retirement',
    {acceptedPrefix: 'ک', unqualifiedAbortDidNotDrain: true, queuedUpdates: replica.pending.size});
  const boundary = {kind: cancel ? 'qualified-retirement' : 'terminal-callback', oldEpoch: epoch, verified: true,
    evidence: cancel ? 'SYNTHETIC_FENCE_FIXTURE_NOT_OS_QUALIFICATION' : 'VALIDATED_SYNTHETIC_CALLBACK_RECEIPT'};
  if (overflow) {
    assert.equal(replica.pending.size, 2);
    assert.equal(replica.resyncFrontier, server.sequence);
    assert.equal(replica.resynchronize({sequence: server.sequence, bundle: server.bundle}), 'DeferredComposition');
    assert.equal(replica.releaseComposition('header', frame, boundary), 'ReleasedNeedsResynchronization');
    const beforeResync = (await owner.request({command: 'snapshot'})).frame;
    assert.deepEqual(beforeResync.committedUnits, frame.committedUnits);
    assert.equal(replica.resynchronize({sequence: server.sequence - 1, bundle: server.bundle}), 'RejectedSnapshotFrontier');
    assert.equal(replica.resynchronize({sequence: server.sequence, bundle: server.bundle}), 'Resynchronized');
    check('Overflow remains bounded and explicit post-fence resynchronization preserves actual owner accepted text and local identity mapping',
      {queueLimit: 2, acceptedPrefix: 'ک', snapshotFrontier: server.sequence, ownerUnchangedUntilReconcile: true});
  } else assert.equal(replica.releaseComposition('header', frame, boundary), 'Released');
  assert.deepEqual(replica.bundle, server.bundle);
  assert.equal(replica.pending.size, 0);

  async function reconcile(selection) {
    const current = (await owner.request({command: 'snapshot'})).frame;
    assert.equal(current.marked, null);
    const target = headerUnits(server), expected = new Set(target.map(u => u.tokenId));
    const working = current.committedUnits.map(u => ({id: localToExternal.get(u[0]), local: u[0], char: u[1], style: u[3]}));
    const edits = [];
    for (let i = working.length - 1; i >= 0; i--) if (!expected.has(working[i].id)) {edits.push({kind: 'replace', start: i, end: i + 1, text: '', style: 0}); working.splice(i, 1);}
    for (let i = 0; i < target.length; i++) {
      const wanted = target[i], flags = bits(wanted.attrs);
      if (working[i]?.id !== wanted.tokenId) {
        const elsewhere = working.findIndex((u, at) => at > i && u.id === wanted.tokenId);
        if (elsewhere >= 0) {edits.push({kind: 'replace', start: elsewhere, end: elsewhere + 1, text: '', style: 0}); working.splice(elsewhere, 1);}
        edits.push({kind: 'replace', start: i, end: i, text: wanted.char, style: flags});
        working.splice(i, 0, {id: wanted.tokenId, local: null, char: wanted.char.charCodeAt(0), style: flags});
      } else {
        assert.equal(working[i].char, wanted.char.charCodeAt(0));
        if (working[i].style !== flags) {edits.push({kind: 'attributes', start: i, end: i + 1, style: flags}); working[i].style = flags;}
      }
    }
    const requestedSelection = resolve(selection, target);
    const result = await owner.request({command: 'canonical-batch', ...canonicalBasis(current), edits, selection: requestedSelection});
    assert.equal(result.status, 'Applied');
    assert.equal(result.frame.committed, text(server.bundle, 'header'));
    assert.equal(result.frame.undo, current.undo, 'Remote acceptance cannot clear or append local snapshot Undo');
    assert.equal(result.frame.revision, current.revision + (edits.length ? 1 : 0), 'One accepted transaction must publish once');
    assert.deepEqual(result.frame.selection, requestedSelection);
    assert.equal(result.frame.committedUnits.length, target.length);
    const nextMap = new Map();
    result.frame.committedUnits.forEach((unit, i) => {
      assert.equal(unit[1], target[i].char.charCodeAt(0)); assert.equal(unit[3], bits(target[i].attrs));
      if (working[i].local !== null) assert.equal(unit[0], working[i].local, 'Unrelated local identity was replaced');
      nextMap.set(unit[0], target[i].tokenId);
    });
    localToExternal = nextMap; return result.frame;
  }
  const movedCaret = capture(frame);
  frame = await reconcile(movedCaret);
  assert.equal(frame.selection[0], 1, 'Queued caret must map before original first unit, after peer prefix');
  const complete = structuredClone(server.bundle);
  check('Qualified terminal boundary drains remote work in one actual owner transaction with raw attributes and logical caret mapping',
    {committed: frame.committed, selection: frame.selection, acceptedFrontier: replica.sequence});

  const beforeUndo = structuredClone(frame), undoRequest = await owner.request({command: 'undo'});
  assert.deepEqual(undoRequest.historyRequests, [false]); assert.deepEqual(undoRequest.frame.committedUnits, beforeUndo.committedUnits);
  const undone = server.undo('A', 'A:user-undo'); assert.equal(undone.outcome, 'Applied'); deliver();
  frame = await reconcile(undone.selection);
  assert(!headerUnits(server).some(u => u.tokenId.startsWith('A:unit:')));
  assert(headerUnits(server).filter(u => u.tokenId.startsWith('B:unit:')).length === 3);
  assert.equal(frame.committed.includes('X'), true); assert.equal(frame.committed.includes('M'), true); assert.equal(frame.committed.includes('Z'), true);
  const redoRequest = await owner.request({command: 'redo'});
  assert.deepEqual(redoRequest.historyRequests, [true]);
  const redone = server.redo('A', 'A:user-redo'); assert.equal(redone.outcome, 'Applied'); deliver();
  assert.deepEqual(server.bundle, complete);
  frame = await reconcile(redone.selection);
  const sequence = server.sequence;
  const late = await owner.request({command: 'ime', text: 'late', committed: 4, caret: null, epoch});
  assert.equal(late.status, 'RejectedStaleEpoch'); assert.deepEqual(late.frame.committedUnits, frame.committedUnits); assert.equal(server.sequence, sequence);
  assert.equal(server.submit('A', 'A:late-part', {kind: 'insert', node: 'header-p', right: null, text: 'late'}, true, group).outcome, 'RejectedGroupClosed');
  check('Actual owner Undo/Redo routes one partial-commit gesture through selective authored inverses and preserves every peer contribution',
    {undoGestureParts: cancel ? 1 : 2, peerUnitsPreserved: 3, finalCommitted: frame.committed, lateCallback: late.status, oldLocalRootsNeverRestored: true});
  report.status = 'ACTUAL_OWNER_COMPOSITION_ADMISSION_PASS_GATE_OPEN'; report.c0Qualified = false;
  report.unresolved = ['Native OS callback provenance and safe restart qualification', 'Production durable group/outbox/schema/ID mapping',
    'Rich-flow native projection and backend history availability UI', 'Crash/permission/compaction and large-queue progress corpus'];
  console.log('PASS actual owner composition/remote admission (' + (cancel ? 'explicit abort + synthetic fence' : 'terminal') + '): partial prefix, deferred remotes, atomic drain and selective group Undo/Redo; C0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; console.error(error.stack); process.exitCode = 1;}
finally {
  termination = await owner.close(); report.ownerTermination = termination;
  if (termination.failedToClose) {report.status = 'FAIL'; report.error = 'Owned JVM did not close'; process.exitCode = 1;}
  if (Object.entries(report.sources).some(([name, expected]) => sha(fs.readFileSync(path.join(import.meta.dirname, name))) !== expected)) {
    report.status = 'FAIL'; report.error = 'Composition experiment source changed during execution'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
