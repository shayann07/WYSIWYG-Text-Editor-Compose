import * as Y from 'yjs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../..');
const outputArg = process.argv.find(a => a.startsWith('--output='));
const output = outputArg ? path.resolve(outputArg.slice(9)) : path.join(root, 'desktop-prototype/local-evidence', `c0-${Date.now()}`);
fs.mkdirSync(output, {recursive: false});
const result = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(),
  candidate: 'yjs@13.6.33', scope: 'Isolated canonical-unit compatibility trial, not production integration or C0 qualification', named: [], seeds: []};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(result, null, 2) + '\n');
const named = (name, evidence, disposition = 'PASS') => {result.named.push({name, disposition, evidence}); save();};
const digest = data => createHash('sha256').update(data).digest('hex');
result.sourceSha256 = digest(fs.readFileSync(import.meta.filename));
result.runtime = {node: process.version, platform: process.platform, arch: process.arch};

function doc(clientId) {
  const d = new Y.Doc();
  // Explicit IDs only in isolated fixtures, before writes; never a production actor identity.
  d.clientID = clientId;
  return d;
}

function rawTextCounterexample() {
  const a = doc(1), b = doc(2);
  const text = '\uD800';
  a.getText('body').insert(0, text);
  Y.applyUpdate(b, Y.encodeStateAsUpdate(a));
  const sent = a.getText('body').toString(), received = b.getText('body').toString();
  assert.equal(sent.charCodeAt(0), 0xD800);
  named('Y.Text raw UTF-16 transport', {sentUnits: [...Array(sent.length)].map((_, i) => sent.charCodeAt(i)),
    receivedUnits: [...Array(received.length)].map((_, i) => received.charCodeAt(i)), equal: sent === received},
    sent === received ? 'PASS_NECESSARY_CONDITION' : 'REJECT_YTEXT_FOR_RAW_UNIT_CONTRACT');
  a.destroy(); b.destroy();
}

function initialize(seed) {
  const d = doc(seed * 100 + 1), rootMap = d.getMap('document');
  d.transact(() => {
    rootMap.set('schema', 'c0-unit-experiment-1');
    const flows = new Y.Map(); rootMap.set('flows', flows);
    for (const name of ['body', 'cell-a', 'cell-b', 'header', 'footer']) flows.set(name, new Y.Array());
    rootMap.set('anchors', new Y.Map()); rootMap.set('review', new Y.Map());
  }, 'fixture');
  return d;
}
const flow = (d, name = 'body') => d.getMap('document').get('flows').get(name);
function atom(id, char, style = 0, kind = 0) {
  return new Y.Map(Object.entries({id, char, kind, style: kind ? 0 : style, paragraphStyle: kind ? style : 0}));
}
function validate(d) {
  const r = d.getMap('document');
  assert.equal(r.get('schema'), 'c0-unit-experiment-1');
  const ids = new Set();
  for (const array of r.get('flows').values()) for (const unit of array.toArray()) {
    const u = unit.toJSON();
    assert.equal(typeof u.id, 'string'); assert(!ids.has(u.id)); ids.add(u.id);
    assert(Number.isInteger(u.char) && u.char >= 0 && u.char <= 65535);
    assert(u.kind === 0 || u.kind === 1);
    assert(Number.isInteger(u.style) && u.style >= 0 && u.style <= 3, 'UnitFieldMissingOrInvalid:style:' + JSON.stringify(u));
    assert(Number.isInteger(u.paragraphStyle) && u.paragraphStyle >= 0 && u.paragraphStyle <= 3, 'UnitFieldMissingOrInvalid:paragraphStyle:' + JSON.stringify(u));
    if (u.kind === 1) assert(u.char === 10 && u.style === 0);
  }
}
function canonical(d) {
  const r = d.getMap('document'), flows = {};
  for (const name of [...r.get('flows').keys()].sort()) flows[name] = flow(d, name).toArray().map(u => u.toJSON());
  return {schema: r.get('schema'), flows, anchors: Object.fromEntries([...r.get('anchors')].sort()),
    review: Object.fromEntries([...r.get('review')].sort())};
}
function peers(seed) {
  const initial = initialize(seed), bytes = Y.encodeStateAsUpdate(initial);
  const a = doc(seed * 100 + 2), b = doc(seed * 100 + 3);
  Y.applyUpdate(a, bytes); Y.applyUpdate(b, bytes); initial.destroy();
  validate(a); validate(b);
  return [a, b];
}
function sync(a, b) {
  const fromA = Y.encodeStateAsUpdate(a), fromB = Y.encodeStateAsUpdate(b);
  Y.applyUpdate(a, fromB, 'remote'); Y.applyUpdate(b, fromA, 'remote');
  validate(a); validate(b); assert.deepEqual(canonical(a), canonical(b));
}
const units = d => flow(d).toArray().map(u => u.get('char'));

function namedTraces() {
  {
    const [a, b] = peers(1);
    a.transact(() => flow(a).insert(0, [atom('a:1', 0xD800, 1), atom('a:2', 0x06A9, 2)]), 'A');
    sync(a, b); assert.deepEqual(units(b), [0xD800, 0x06A9]);
    assert.deepEqual(canonical(b).flows.body.map(u => u.style), [1, 2]);
    named('Numeric UTF-16 units preserve isolated surrogate and raw attribution', {units: units(b), styles: canonical(b).flows.body.map(u => u.style)});
    a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(21);
    const undo = new Y.UndoManager(flow(a), {trackedOrigins: new Set(['A']), captureTimeout: 500});
    a.transact(() => flow(a).insert(0, [0x06A9, 0x062A, 0x0627, 0x0628].map((c, i) => atom(`A:${i}`, c, 1))), 'A');
    undo.stopCapturing(); sync(a, b);
    b.transact(() => flow(b).insert(2, [atom('B:inside', 0x0645, 2)]), 'B'); sync(a, b);
    const before = canonical(a).flows.body;
    undo.undo(); sync(a, b); assert.deepEqual(canonical(a).flows.body, [{id: 'B:inside', char: 0x0645, kind: 0, style: 2, paragraphStyle: 0}]);
    undo.redo(); sync(a, b); assert.deepEqual(canonical(a).flows.body, before);
    named('A selective Undo preserves B inside the inserted Urdu span; exact Redo', {peerId: 'B:inside', finalIds: before.map(u => u.id)});
    undo.destroy(); a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(22);
    a.transact(() => flow(a).insert(0, [atom('base:1', 0x06A9)]), 'fixture'); sync(a, b);
    const undo = new Y.UndoManager(flow(a), {trackedOrigins: new Set(['A']), captureTimeout: 500});
    a.transact(() => flow(a).get(0).set('style', 1), 'A'); undo.stopCapturing(); sync(a, b);
    b.transact(() => flow(b).get(0).set('style', 2), 'B'); sync(a, b);
    undo.undo(); sync(a, b); assert.equal(flow(a).get(0).get('style'), 2);
    named('Undo of superseded A formatting preserves B current formatting', canonical(a).flows.body);
    undo.destroy(); a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(2);
    const undo = new Y.UndoManager(flow(a), {trackedOrigins: new Set(['A']), captureTimeout: 500});
    a.transact(() => flow(a).insert(0, [atom('A:1', 0x06A9)]), 'A');
    undo.stopCapturing(); sync(a, b);
    b.transact(() => flow(b).insert(1, [atom('B:1', 0x0628, 2)]), 'B'); sync(a, b);
    undo.undo(); sync(a, b); assert.deepEqual(units(a), [0x0628]);
    undo.redo(); sync(a, b); assert.deepEqual(units(a), [0x06A9, 0x0628]);
    named('A selective Undo/Redo preserves B adjacent accepted contribution', canonical(a).flows.body);
    undo.destroy(); a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(3);
    const undo = new Y.UndoManager(flow(a), {trackedOrigins: new Set(['A']), captureTimeout: 500});
    a.transact(() => flow(a).insert(0, [atom('A:1', 0x06A9, 3)]), 'A');
    // Only accepted prefix is replicated; the remaining native candidate is not in this doc.
    sync(a, b); assert.deepEqual(units(b), [0x06A9]);
    a.transact(() => flow(a).insert(1, [atom('A:2', 0x062A, 3), atom('A:3', 0x0627, 3), atom('A:4', 0x0628, 3)]), 'A');
    undo.stopCapturing(); sync(a, b); assert.equal(undo.undoStack.length, 1);
    undo.undo(); sync(a, b); assert.deepEqual(units(a), []);
    undo.redo(); sync(a, b); assert.deepEqual(units(a), [0x06A9, 0x062A, 0x0627, 0x0628]);
    named('Accepted partial effects share one gesture; candidate excluded from replication', {undoGroups: 1, finalUnits: units(a)});
    undo.destroy(); a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(4);
    a.transact(() => flow(a).insert(0, [atom('base:1', 0x06A9)]), 'fixture'); sync(a, b);
    a.transact(() => flow(a).insert(0, [atom('A:1', 0x0627)]), 'A');
    b.transact(() => flow(b).insert(0, [atom('B:1', 0x0628)]), 'B'); sync(a, b);
    assert.deepEqual(canonical(a).flows.body.map(u => u.id), ['A:1', 'B:1', 'base:1']);
    named('Same-boundary ordering for pinned fixture client IDs', {rule: 'Concurrent insertions ordered by Yjs item identity/client clock; fixture IDs recorded', ids: canonical(a).flows.body.map(u => u.id)});
    a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(5);
    a.transact(() => {
      flow(a, 'cell-a').insert(0, [atom('cell:1', 0x06A9), atom('break:1', 10, 2, 1), atom('cell:2', 0x0628, 1)]);
      a.getMap('document').get('anchors').set('comment', {flow: 'cell-a', unitIds: ['cell:1'], context: [0x06A9]});
    }, 'fixture'); sync(a, b);
    a.transact(() => flow(a, 'cell-a').get(0).set('style', 3), 'A');
    b.transact(() => {flow(b, 'cell-a').delete(0, 1); flow(b, 'cell-a').insert(0, [atom('replacement:1', 0x062A)]);}, 'B'); sync(a, b);
    assert.equal(flow(a, 'cell-a').get(0).get('style'), 0);
    const anchor = a.getMap('document').get('anchors').get('comment');
    assert(!canonical(a).flows['cell-a'].some(u => anchor.unitIds.includes(u.id)));
    named('Formatting versus replacement does not transfer style; deleted anchor remains explicitly orphanable', {cell: canonical(a).flows['cell-a'], anchor, derivedAnchorState: 'orphaned'});
    a.destroy(); b.destroy();
  }
  {
    const [a, b] = peers(6);
    // Counterexample: independent low-level field conflict resolution is insufficient
    // for an atomic suggestion decision plus its document effect.
    a.transact(() => a.getMap('document').get('review').set('suggestion', 'pending'), 'fixture'); sync(a, b);
    a.transact(() => {flow(a).insert(0, [atom('suggested:1', 0x06A9)]); a.getMap('document').get('review').set('suggestion', 'accepted');}, 'A');
    b.transact(() => b.getMap('document').get('review').set('suggestion', 'rejected'), 'B'); sync(a, b);
    const state = a.getMap('document').get('review').get('suggestion');
    assert(state === 'rejected' && units(a).length > 0, 'Named inconsistent suggestion decision must reproduce');
    named('Competing suggestion decisions require typed admission policy', {mergedState: state, mergedUnits: units(a),
      counterexample: state === 'rejected' && units(a).length > 0}, 'UNRESOLVED_SEMANTIC_POLICY');
    a.destroy(); b.destroy();
  }
}

function rng(seed) {let value = seed >>> 0; return n => {value ^= value << 13; value ^= value >>> 17; value ^= value << 5; return (value >>> 0) % n;};}
function generated(seed, count, codeUnits, causalAdmission = true) {
  const [a, b] = peers(100 + seed), random = rng(seed), queues = [[], []], docs = [a, b], serial = [0, 0];
  const flowNames = ['body', 'cell-a', 'cell-b', 'header', 'footer'];
  const operations = {insert: 0, delete: 0, format: 0, split: 0};
  const started = performance.now();
  let publications = 0, duplicateDeliveries = 0, localUpdates = 0, maxQueued = 0;
  for (let actor = 0; actor < 2; actor++) {
    docs[actor].on('update', (update, origin) => {
      if (origin?.actor === actor) {
        queues[1 - actor].push({update, frontier: origin.frontier}); localUpdates++;
        maxQueued = Math.max(maxQueued, queues[0].length + queues[1].length);
      }
    });
    docs[actor].on('afterTransaction', () => {validate(docs[actor]); publications++;});
  }
  const deliver = () => {
    while (queues[0].length || queues[1].length) {
      let progress = false;
      for (let target = 0; target < 2; target++) {
        const current = Y.decodeStateVector(Y.encodeStateVector(docs[target]));
        const eligible = queues[target].map((item, index) => ({item, index})).filter(({item}) =>
          !causalAdmission || [...item.frontier].every(([client, clock]) => (current.get(client) ?? 0) >= clock));
        if (!eligible.length) continue;
        const {item, index} = eligible[random(eligible.length)];
        queues[target].splice(index, 1);
        Y.applyUpdate(docs[target], item.update, 'remote');
        if (random(7) === 0) {Y.applyUpdate(docs[target], item.update, 'remote'); duplicateDeliveries++;}
        progress = true;
      }
      assert(progress, 'Causal delivery made no progress');
    }
    assert.deepEqual(canonical(a), canonical(b));
  };
  try {
  for (let step = 0; step < count; step++) {
    const actor = random(2), d = docs[actor], array = flow(d, flowNames[random(flowNames.length)]);
    let kind = random(4);
    if (!array.length) kind = 0;
    if (array.length > 64) kind = 1;
    const frontier = Y.decodeStateVector(Y.encodeStateVector(d));
    d.transact(() => {
      if (kind === 0 || kind === 3) {
        const char = kind === 3 ? 10 : codeUnits[random(codeUnits.length)];
        const id = `${seed}:${actor}:${++serial[actor]}`;
        array.insert(random(array.length + 1), [atom(id, char, random(4), kind === 3 ? 1 : 0)]);
        operations[kind === 3 ? 'split' : 'insert']++;
      } else if (kind === 1) {array.delete(random(array.length), 1); operations.delete++;}
      else {
        const unit = array.get(random(array.length));
        const key = unit.get('kind') === 1 ? 'paragraphStyle' : 'style';
        unit.set(key, unit.get(key) ^ (1 << random(2))); operations.format++;
      }
    }, {actor, frontier});
    if (step % 31 === 30) deliver();
  }
  deliver();
  const snapshot = canonical(a), compact = Y.encodeStateAsUpdate(a), reconnected = doc(900000 + seed);
  Y.applyUpdate(reconnected, compact, 'snapshot'); validate(reconnected);
  assert.deepEqual(canonical(reconnected), snapshot);
  assert.equal(localUpdates, count, 'Every counted generated command must mutate accepted state');
  const entry = {seed, operations: count, localUpdates, operationCounts: operations, publications, duplicateDeliveries, maxQueued,
    seconds: (performance.now() - started) / 1000,
    snapshotSha256: digest(JSON.stringify(snapshot)), compactBytes: compact.length, status: 'PASS'};
  reconnected.destroy(); return entry;
  } finally {a.destroy(); b.destroy();}
}

save();
try {
  rawTextCounterexample(); namedTraces();
  const fixtures = JSON.parse(fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json'), 'utf8'));
  const codeUnits = [...new Set(fixtures.flatMap(f => f.utf16CodeUnits))];
  result.urduFixtureSha256 = digest(fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json')));
  result.dependencyLockSha256 = digest(fs.readFileSync(path.join(import.meta.dirname, 'package-lock.json')));
  try {
    generated(1, 5000, codeUnits, false);
    throw new Error('Uncontrolled reorder counterexample did not reproduce');
  } catch (error) {
    if (!error.message.includes('UnitFieldMissingOrInvalid:')) throw error;
    named('Raw reordered Yjs updates can expose incomplete application units', {assertion: error.message,
      guardedTrialPolicy: 'Transport may reorder; publication waits for captured public Yjs state-vector frontier'}, 'REQUIRES_CAUSAL_PUBLICATION_ADMISSION');
  }
  for (let seed = 1; seed <= 20; seed++) {
    result.seeds.push(generated(seed, 5000, codeUnits)); save();
    if (seed % 5 === 0) console.log(`PASS C0 unit trial ${seed}/20 seeds`);
  }
  result.totalGeneratedOperations = result.seeds.reduce((sum, s) => sum + s.operations, 0);
  result.status = 'CANDIDATE_UNIT_TRIAL_PASS_GATE_OPEN';
  result.c0Qualified = false;
  result.unresolved = ['Typed structural move/split/join conflict policies', 'Competing review decision admission',
    'Server authorization/revocation and durable operation IDs', 'Native composition remote deferral and progress',
    'Kotlin/Android/iOS portability and production engine mapping', 'Compaction frontier and retention leases',
    'Scaling beyond this bounded per-unit representation'];
  console.log(`PASS C0 candidate unit trial: ${result.totalGeneratedOperations} operations; C0 OPEN`);
} catch (error) {
  result.status = 'FAIL'; result.error = error.stack; console.error(error.stack); process.exitCode = 1;
} finally {
  if (digest(fs.readFileSync(import.meta.filename)) !== result.sourceSha256) {
    result.status = 'FAIL'; result.error = 'Source changed during candidate trial'; process.exitCode = 1;
  }
  result.finishedAt = new Date().toISOString(); save();
}
