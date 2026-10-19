/** Bounded ordered-replica progress checks; in-memory protocol, not a durable transport. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {Authority, Replica} from './ordered-structure-model.mjs';

const output = path.resolve(process.argv.find(x => x.startsWith('--output='))?.slice(9));
fs.mkdirSync(output, {recursive: false});
const sha = name => createHash('sha256').update(fs.readFileSync(new URL(name, import.meta.url))).digest('hex');
const report = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: [],
  scope: 'Private in-memory bounded queue and explicit snapshot resynchronization; no durable outbox, authentication or native fence qualification',
  sources: Object.fromEntries(['queue-progress.mjs', 'ordered-structure-model.mjs'].map(name => [name, sha(name)]))};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
function fixture(name, run) {const evidence = run(); report.checks.push({name, status: 'PASS', evidence}); save();}
function authority(count = 8) {
  const server = new Authority();
  for (let i = 0; i < count; i++) assert.equal(server.submit('B', 'B:queue:' + i,
    {kind: 'insert', node: 'header-p', right: null, text: i % 2 ? 'ک' : 'x'}).outcome, 'Applied');
  return server;
}
const snapshot = server => ({sequence: server.sequence, bundle: structuredClone(server.bundle)});
save();
try {
  fixture('A full out-of-order queue admits its missing head without an extra slot', () => {
    const server = authority(257), replica = new Replica(new Authority().bundle);
    for (const packet of server.packets.slice(1)) replica.receive(packet);
    assert.equal(replica.pending.size, 256);
    assert.equal(replica.receive(server.packets[0]), 'Applied');
    assert.equal(replica.sequence, 257); assert.equal(replica.pending.size, 0); assert.equal(replica.pendingBytes, 0);
    assert.deepEqual(replica.bundle, server.bundle);
    return {queueLimit: 256, acceptedFrontier: replica.sequence, pendingBytes: 0};
  });
  fixture('Count overflow freezes accepted state and requires an adequate explicit snapshot', () => {
    const server = authority(), replica = new Replica(new Authority().bundle, {maxEntries: 2});
    replica.beginComposition('header', 7);
    assert.equal(replica.receive(server.packets[0]), 'DeferredComposition');
    assert.equal(replica.receive(server.packets[1]), 'DeferredComposition');
    const original = structuredClone(replica.bundle), bytes = replica.pendingBytes;
    assert.equal(replica.receive(server.packets[1]), 'Duplicate'); assert.equal(replica.pendingBytes, bytes);
    for (const packet of server.packets.slice(2)) assert.equal(replica.receive(packet), 'NeedsResynchronization');
    assert.equal(replica.pending.size, 2); assert.equal(replica.resyncFrontier, 8);
    assert.equal(replica.resynchronize(snapshot(server)), 'DeferredComposition'); assert.deepEqual(replica.bundle, original);
    assert.equal(replica.releaseComposition('header', {marked: null, epoch: 8}), 'RejectedNativeBoundary');
    assert.equal(replica.releaseComposition('header', {marked: null, epoch: 8}, {kind: 'terminal-callback', oldEpoch: 7, verified: true}), 'ReleasedNeedsResynchronization');
    assert.deepEqual(replica.bundle, original);
    assert.equal(replica.resynchronize({sequence: 7, bundle: server.bundle}), 'RejectedSnapshotFrontier');
    assert.equal(replica.resynchronize({sequence: 8, bundle: {main: {}}}), 'RejectedSnapshot');
    assert.deepEqual(replica.bundle, original); assert.equal(replica.pendingBytes, bytes);
    assert.equal(replica.resynchronize(snapshot(server)), 'Resynchronized');
    assert.deepEqual(replica.bundle, server.bundle); assert.equal(replica.sequence, 8);
    assert.equal(replica.pending.size, 0); assert.equal(replica.pendingBytes, 0); assert.equal(replica.resyncFrontier, null);
    assert.equal(replica.receive(server.packets[0]), 'Duplicate');
    return {capacity: 2, highestObservedFrontier: 8, nativeBoundaryRequired: true, snapshotRejectedAtomically: true};
  });
  fixture('UTF-8 packet bytes are bounded independently of entry count', () => {
    const server = authority(), packet = server.packets[0], bytes = Buffer.byteLength(JSON.stringify(packet), 'utf8');
    const replica = new Replica(new Authority().bundle, {maxBytes: bytes - 1});
    const initial = structuredClone(replica.bundle);
    assert.equal(replica.receive(packet), 'NeedsResynchronization');
    assert.equal(replica.pending.size, 0); assert.equal(replica.pendingBytes, 0); assert.deepEqual(replica.bundle, initial);
    const exact = new Replica(new Authority().bundle, {maxBytes: bytes}); exact.beginComposition('header', 0);
    assert.equal(exact.receive(packet), 'DeferredComposition'); assert.equal(exact.pendingBytes, bytes);
    assert.equal(exact.receive(server.packets[1]), 'NeedsResynchronization'); assert.equal(exact.pendingBytes, bytes);
    return {packetBytes: bytes, oversizedRetainedBytes: 0, exactBudgetRetainedBytes: bytes};
  });
  fixture('Resynchronization retains future packets and drains them only after their gap closes', () => {
    const server = new Authority(), base = structuredClone(server.bundle), replica = new Replica(base);
    const states = [];
    for (let i = 0; i < 4; i++) {server.submit('B', 'B:future:' + i, {kind: 'insert', node: 'header-p', right: null, text: 'ک'}); states.push(snapshot(server));}
    assert.equal(replica.receive(server.packets[3]), 'DeferredSequenceGap');
    assert.equal(replica.resynchronize(states[1]), 'ResynchronizedWithGap');
    assert.equal(replica.sequence, 2); assert.equal(replica.pending.size, 1);
    assert.equal(replica.receive(server.packets[2]), 'Applied'); assert.equal(replica.sequence, 4);
    assert.deepEqual(replica.bundle, server.bundle); assert.equal(replica.pendingBytes, 0);
    assert.equal(replica.resynchronize(states[0]), 'RejectedSnapshotFrontier');
    return {snapshotFrontier: 2, finalFrontier: 4, futurePacketPreserved: true};
  });
  fixture('A conflicting buffered sequence cannot silently replace the first packet', () => {
    const server = authority(), replica = new Replica(new Authority().bundle);
    replica.receive(server.packets[1]);
    const forged = structuredClone(server.packets[1]); forged.operationId = 'B:conflicting';
    assert.equal(replica.receive(forged), 'RejectedConflictingPacket');
    assert.deepEqual(replica.pending.get(2), server.packets[1]);
    assert.equal(replica.resynchronize(snapshot(server)), 'Resynchronized'); assert.deepEqual(replica.bundle, server.bundle);
    return {firstPacketPreserved: true, explicitResynchronizationRequired: true};
  });
  report.status = 'BOUNDED_QUEUE_PROGRESS_PASS_GATE_OPEN'; report.c0Qualified = false;
  console.log('PASS bounded C0 queue progress: missing head, count/byte caps, composition fence and explicit resynchronization; C0 OPEN');
} catch (error) {report.status = 'FAIL'; report.error = error.stack; process.exitCode = 1; console.error(error.stack);}
finally {
  if (Object.entries(report.sources).some(([name, expected]) => sha(name) !== expected)) {report.status = 'FAIL'; report.error = 'Source changed during queue checks'; process.exitCode = 1;}
  report.finishedAt = new Date().toISOString(); save();
}
