// Private SQLite/replay feasibility adapter, not the production storage/wire schema.
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import {serialize, deserialize, cachedDataVersionTag} from 'node:v8';
import {Authority} from './ordered-structure-model.mjs';
import {encodePortableState, decodePortableState, PORTABLE_CHECKPOINT_FORMAT, MAX_CHECKPOINT_BYTES} from './checkpoint-value-codec.mjs';

export function canonical(value) {
  const sorted = v => Array.isArray(v) ? v.map(sorted) : v && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map(k => [k, sorted(v[k])])) : v;
  return JSON.stringify(sorted(JSON.parse(JSON.stringify(value))));
}
// Field presence matters to inverse ownership (present-but-undefined is not absent).
const semantic = value => value === undefined ? ['undefined'] : Array.isArray(value) ? ['array', value.map(semantic)]
  : value !== null && typeof value === 'object' ? ['object', Object.keys(value).sort().map(key => [key, semantic(value[key])])] : ['value', value];
const digest = value => createHash('sha256').update(JSON.stringify(semantic(value))).digest('hex');
const bytesHash = value => createHash('sha256').update(value).digest('hex');
function checkpointCompatibility(codec) {
  const root = new URL('../../../', import.meta.url);
  const manifest = JSON.parse(fs.readFileSync(new URL('v5-freeze-manifest.json', root), 'utf8'));
  const dependencies = Object.keys(manifest.hashes).filter(name => name.startsWith('validation/codex/run-20261016-20/')).sort();
  const sources = ['validation/integration/c0/ordered-structure-model.mjs', 'validation/integration/c0/durable-authority.mjs',
    'validation/integration/c0/checkpoint-value-codec.mjs', ...dependencies];
  return canonical({format: codec === 'portable' ? PORTABLE_CHECKPOINT_FORMAT : 'c0-tooling-checkpoint-1',
    ...(codec === 'v8' ? {node: process.version, v8: process.versions.v8, v8Tag: cachedDataVersionTag()} : {}),
    source: sources.map(name => [name, bytesHash(fs.readFileSync(new URL(name, root)))])});
}
const mapFields = ['receipts', 'groups', 'formatOwners', 'moveOwners', 'deletions', 'retainedUnits', 'aliases'];
function state(authority) {
  return {bundle: authority.bundle, sequence: authority.sequence, serial: authority.serial, history: authority.history,
    roles: authority.roles, groups: [...authority.groups], formatOwners: [...authority.formatOwners],
    moveOwners: [...authority.moveOwners], deletions: [...authority.deletions], retainedUnits: [...authority.retainedUnits],
    aliases: [...authority.aliases], receipts: [...authority.receipts], retiredProducers: [...authority.retiredProducers]};
}
function restorePortable(value) {
  assert(value && Object.getPrototypeOf(value) === Object.prototype, 'Invalid checkpoint state record');
  assert.deepEqual(Object.keys(value).sort(), ['bundle', 'sequence', 'serial', 'history', 'roles', ...mapFields, 'retiredProducers'].sort(), 'Unsupported checkpoint state fields');
  assert(Number.isSafeInteger(value.sequence) && value.sequence >= 0 && Number.isSafeInteger(value.serial) && value.serial >= 0, 'Invalid checkpoint counters');
  assert(Array.isArray(value.history) && value.roles && Object.getPrototypeOf(value.roles) === Object.prototype, 'Invalid checkpoint history/roles');
  const restored = new Authority(value.bundle); // Independent canonical schema validation, no runtime prototype decoding.
  for (const name of ['sequence', 'serial', 'history', 'roles']) restored[name] = value[name];
  for (const name of mapFields) {
    assert(Array.isArray(value[name]) && value[name].every(entry => Array.isArray(entry) && entry.length === 2 && typeof entry[0] === 'string'), 'Invalid checkpoint map entries');
    const map = new Map(value[name]); assert.equal(map.size, value[name].length, 'Duplicate checkpoint map key'); restored[name] = map;
  }
  assert(Array.isArray(value.retiredProducers) && value.retiredProducers.every(key => typeof key === 'string'), 'Invalid checkpoint producer set');
  restored.retiredProducers = new Set(value.retiredProducers);
  assert.equal(restored.retiredProducers.size, value.retiredProducers.length, 'Duplicate checkpoint producer');
  return restored;
}
const producerKey = (actor, producerId) => canonical([actor, producerId ?? 'legacy']);
function protocol(authority) {authority.retiredProducers ??= new Set(); return authority;}
function execute(authority, event) {
  const producer = producerKey(event.actor, event.producerId);
  switch (event.kind) {
    case 'submit': return authority.submit(event.actor, event.requestId, event.operation, true, event.groupId ?? null, producer);
    case 'undo': return authority.undo(event.actor, event.requestId, producer);
    case 'redo': return authority.redo(event.actor, event.requestId, producer);
    case 'discard-own-history': return authority.discardOwnHistory(event.actor, producer);
    case 'begin': return authority.beginGesture(event.actor, event.groupId, event.flow, event.selection, producer);
    case 'close': return authority.closeGesture(event.actor, event.groupId, event.selection, producer);
    // A trusted test-service control, not a user-facing authenticated permission API.
    case 'role': assert.equal(event.actor, 'service'); assert(['editor', 'viewer'].includes(event.role));
      authority.roles[event.target] = event.role; return {outcome: 'RoleChanged'};
    case 'retire-producer': {
      assert.equal(event.actor, 'service'); assert(['A', 'B'].includes(event.target));
      assert(event.targetProducer === undefined || (typeof event.targetProducer === 'string' && event.targetProducer.length > 0));
      const retired = producerKey(event.target, event.targetProducer); authority.retiredProducers.add(retired);
      for (const group of authority.groups.values()) if (group.actor === event.target && group.producer === retired && group.open) {
        group.open = false; group.retired = true; group.afterSelection = null;
      }
      return {outcome: 'ProducerRetired', nativeFenceQualified: false};
    }
    default: throw Error('Unsupported durable event');
  }
}
const accepted = new Set(['Applied', 'Opened', 'Closed', 'RoleChanged', 'ProducerRetired', 'HistoryDiscarded']);
function beginImmediate(db) {
  try {db.exec('BEGIN IMMEDIATE'); return true;}
  catch (error) {
    // Only the observed SQLite BUSY admission failure has a known no-write outcome.
    // Do not turn I/O, schema, transaction or acknowledgement uncertainty into success/retry.
    if (error.code === 'ERR_SQLITE_ERROR' && error.errcode === 5) return false;
    throw error;
  }
}
export class DurableAuthority {
  constructor(filename, {checkpointCodec = 'v8'} = {}) {
    assert(['v8', 'portable'].includes(checkpointCodec), 'Unsupported tooling checkpoint codec');
    this.checkpointCodec = checkpointCodec;
    this.db = new DatabaseSync(filename);
    this.db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;');
    this.db.exec('CREATE TABLE IF NOT EXISTS accepted_events (frontier INTEGER PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, event TEXT NOT NULL, result TEXT NOT NULL, state_hash TEXT NOT NULL)');
    this.db.exec('CREATE TABLE IF NOT EXISTS tooling_checkpoint (singleton INTEGER PRIMARY KEY CHECK(singleton=1), frontier INTEGER NOT NULL, epoch INTEGER NOT NULL, compatibility TEXT NOT NULL, payload BLOB NOT NULL, payload_hash TEXT NOT NULL, state_hash TEXT NOT NULL)');
    assert.equal(this.db.prepare('PRAGMA synchronous').get().synchronous, 2);
  }
  load() {
    const checkpoint = this.db.prepare('SELECT * FROM tooling_checkpoint WHERE singleton=1').get();
    let authority = protocol(new Authority()), floor = 0, epoch = 0;
    if (checkpoint) {
      assert.equal(checkpoint.compatibility, checkpointCompatibility(this.checkpointCodec), 'Tooling checkpoint compatibility mismatch; database retained');
      assert(checkpoint.payload.byteLength <= MAX_CHECKPOINT_BYTES, 'Tooling checkpoint exceeds bounded payload');
      assert.equal(bytesHash(checkpoint.payload), checkpoint.payload_hash, 'Tooling checkpoint checksum mismatch');
      if (this.checkpointCodec === 'portable') authority = restorePortable(decodePortableState(checkpoint.payload));
      else {authority = deserialize(checkpoint.payload); Object.setPrototypeOf(authority, Authority.prototype);}
      for (const key of ['receipts', 'groups', 'formatOwners', 'moveOwners', 'deletions', 'retainedUnits', 'aliases']) assert(authority[key] instanceof Map, 'Invalid checkpoint map');
      assert(authority.retiredProducers instanceof Set, 'Invalid checkpoint producer set');
      new Authority(authority.bundle); // Validate canonical schema independently of the serialized class prototype.
      assert.equal(digest(state(authority)), checkpoint.state_hash, 'Tooling checkpoint semantic/history/retention drift');
      floor = checkpoint.frontier; epoch = checkpoint.epoch;
      assert(Number.isSafeInteger(floor) && floor >= 0 && Number.isSafeInteger(epoch) && epoch > 0);
    }
    const rows = this.db.prepare('SELECT * FROM accepted_events ORDER BY frontier').all();
    for (const [index, row] of rows.entries()) {
      assert.equal(row.frontier, floor + index + 1, 'Durable event frontier gap');
      const result = execute(authority, JSON.parse(row.event));
      assert.equal(canonical(result), row.result, 'Replay receipt drift');
      assert.equal(digest(state(authority)), row.state_hash, 'Replay semantic/history/retention drift');
    }
    return {authority, frontier: floor + rows.length, epoch, minimumBasis: floor, replayedRows: rows.length};
  }
  accept(event, inject = null) {
    if (!beginImmediate(this.db)) return {outcome: 'RetryableBusy', acknowledged: false, retainedIntent: event};
    try {
      const {authority, frontier, epoch, minimumBasis} = this.load();
      if (typeof event.requestId !== 'string' || !event.requestId.startsWith(event.actor + ':')) {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedIdentity', acknowledged: false};
      }
      // Revocation is checked before idempotent receipt lookup.
      if (!['role', 'retire-producer'].includes(event.kind) && authority.roles[event.actor] !== 'editor') {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedPermission', acknowledged: false, retainedIntent: event};
      }
      if (event.producerId !== undefined && (typeof event.producerId !== 'string' || event.producerId.length === 0)) {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedProducerIdentity', acknowledged: false, retainedIntent: event};
      }
      const producer = producerKey(event.actor, event.producerId);
      if (!['role', 'retire-producer'].includes(event.kind) && authority.retiredProducers.has(producer)) {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedProducerRetired', acknowledged: false, retainedIntent: event};
      }
      if (event.groupId != null && ['submit', 'close'].includes(event.kind) && authority.groups.get(event.groupId)?.producer !== producer) {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedProducerGroup', acknowledged: false, retainedIntent: event};
      }
      if ((event.epoch ?? 0) !== epoch) {
        this.db.exec('ROLLBACK'); return {outcome: 'ExpiredEpochRecoveryRequired', acknowledged: false, epoch, minimumBasis, retainedIntent: event};
      }
      if (epoch > 0 && !event.requestId.startsWith(event.actor + ':e' + epoch + ':')) {
        this.db.exec('ROLLBACK'); return {outcome: 'RejectedEpochIdentity', acknowledged: false, retainedIntent: event};
      }
      if (epoch > 0 && (!Number.isSafeInteger(event.basisFrontier) || event.basisFrontier < minimumBasis || event.basisFrontier > frontier)) {
        this.db.exec('ROLLBACK'); return {outcome: event.basisFrontier > frontier ? 'RejectedFutureBasis' : 'ExpiredBasisRecoveryRequired',
          acknowledged: false, epoch, minimumBasis, retainedIntent: event};
      }
      const encoded = canonical(event), previous = this.db.prepare('SELECT * FROM accepted_events WHERE request_id=?').get(event.requestId);
      if (previous) {
        this.db.exec('ROLLBACK');
        return previous.event === encoded ? {outcome: 'Duplicate', acknowledged: true, durableFrontier: previous.frontier,
          canonicalFrontier: authority.sequence, original: JSON.parse(previous.result)} : {outcome: 'RejectedReusedRequestId', acknowledged: false, retainedIntent: event};
      }
      const result = execute(authority, event);
      if (!accepted.has(result.outcome)) {this.db.exec('ROLLBACK'); return {...result, acknowledged: false, retainedIntent: event};}
      assert(Number.isSafeInteger(frontier + 1), 'Durable frontier exhausted');
      this.db.prepare('INSERT INTO accepted_events VALUES(?,?,?,?,?)').run(frontier + 1, event.requestId, encoded, canonical(result), digest(state(authority)));
      if (typeof inject === 'function') inject(); // Private deterministic process-test barrier before COMMIT.
      if (inject === 'before-commit') process.exit(83); // intentional process-fault control
      this.db.exec('COMMIT');
      if (inject === 'after-commit') process.exit(84); // acknowledgement lost after durable commit
      return {...result, acknowledged: true, durableFrontier: frontier + 1, canonicalFrontier: authority.sequence};
    } catch (error) {try {this.db.exec('ROLLBACK');} catch {} throw error;}
  }
  snapshot() {
    const {authority, frontier, epoch, minimumBasis, replayedRows} = this.load();
    return {bundle: authority.bundle, durableFrontier: frontier, canonicalFrontier: authority.sequence,
      epoch, minimumBasis, replayedRows, stateHash: digest(state(authority)), history: authority.history,
      groups: [...authority.groups], retainedUnits: [...authority.retainedUnits], roles: {...authority.roles}, retiredProducers: [...authority.retiredProducers]};
  }
  compact(inject = null) {
    if (!beginImmediate(this.db)) return {outcome: 'RetryableBusy', acknowledged: false};
    try {
      const {authority, frontier, epoch, replayedRows} = this.load();
      if (epoch > 0 && replayedRows === 0) {
        this.db.exec('ROLLBACK'); return {outcome: 'AlreadyCompacted', durableFrontier: frontier, epoch};
      }
      if ([...authority.groups.values()].some(group => group.open)) {
        this.db.exec('ROLLBACK'); return {outcome: 'DeferredOpenGestures', nativeFenceQualified: false};
      }
      assert(Number.isSafeInteger(epoch + 1), 'Compaction epoch exhausted');
      // Epoch admission fences all old request IDs. Keep semantic inverses and
      // retained content, while retiring obsolete stream/duplicate receipts.
      authority.receipts.clear(); authority.packets = [];
      const payload = this.checkpointCodec === 'portable' ? encodePortableState(state(authority)) : serialize(authority);
      assert(payload.length <= MAX_CHECKPOINT_BYTES, 'Tooling checkpoint exceeds bounded payload');
      this.db.prepare('INSERT OR REPLACE INTO tooling_checkpoint VALUES(1,?,?,?,?,?,?)').run(
        frontier, epoch + 1, checkpointCompatibility(this.checkpointCodec), payload, bytesHash(payload), digest(state(authority)));
      const removedRows = this.db.prepare('DELETE FROM accepted_events WHERE frontier<=?').run(frontier).changes;
      if (typeof inject === 'function') inject(); // Same barrier for a compactor holding its real transaction.
      if (inject === 'before-commit') process.exit(83);
      this.db.exec('COMMIT');
      if (inject === 'after-commit') process.exit(84);
      return {outcome: 'Compacted', durableFrontier: frontier, epoch: epoch + 1, removedRows: Number(removedRows),
        checkpointBytes: payload.length, semanticRetentionGarbageCollected: false};
    } catch (error) {try {this.db.exec('ROLLBACK');} catch {} throw error;}
  }
  close() {this.db.close();}
}
