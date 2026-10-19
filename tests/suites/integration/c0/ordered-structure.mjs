/** Server-ordered structural compatibility trial, reusing the frozen tree utilities.
 * Full-copy validation model only. No production algorithm/schema/API is selected. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import * as T from '../../codex/run-20261016-20/document-tree.mjs';
import {textBlock, container, validateTree} from '../../codex/run-20261016-20/structural-model.mjs';
import {repairedNormalize, repairedUnitStops} from '../../codex/run-20261016-20/document-model.mjs';

const output = path.resolve(process.argv.find(x => x.startsWith('--output='))?.slice(9) ?? `desktop-prototype/local-evidence/c0-structure-${Date.now()}`);
fs.mkdirSync(output, {recursive: false});
import {Authority, Replica, initial, flow, text, units, locations, validate} from './ordered-structure-model.mjs';
const clone = structuredClone;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const p = (id, text) => textBlock(id, text);
const report = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: [],
  scope: 'Private server-ordered typed-intent/semantic-inverse experiment; existing frozen tree semantics, not native integration or production complexity'};
report.sourceSha256 = createHash('sha256').update(fs.readFileSync(import.meta.filename)).digest('hex');
report.modelSha256 = createHash('sha256').update(fs.readFileSync(new URL('./ordered-structure-model.mjs', import.meta.url))).digest('hex');
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
function fixture(name, body, bundle = initial()) {
  const server = new Authority(bundle), a = new Replica(server.bundle), b = new Replica(server.bundle);
  const evidence = body(server);
  for (const packet of [...server.packets].reverse()) a.receive(packet);
  for (const packet of server.packets) {b.receive(packet); b.receive(packet);}
  assert.deepEqual(a.bundle, server.bundle); assert.deepEqual(b.bundle, server.bundle);
  assert.equal(a.pending.size, 0); assert.equal(b.pending.size, 0);
  report.checks.push({name, status: 'PASS', acceptedTransactions: server.sequence, evidence}); save();
}
function randomSource(seed) {let state = seed >>> 0; return n => {state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) % n;};}
function generated(seed, corpus) {
  const random = randomSource(seed), server = new Authority(), clients = [new Replica(server.bundle), new Replica(server.bundle)];
  const counts = {}, failures = {}, recent = [];
  let accepted = 0, attempts = 0, delivered = 0, duplicateDeliveries = 0;
  function deliver() {
    const batch = server.packets.slice(delivered);
    for (const client of clients) {
      const unordered = [...batch];
      while (unordered.length) {
        const packet = unordered.splice(random(unordered.length), 1)[0];
        client.receive(JSON.parse(JSON.stringify(packet)));
        if (random(7) === 0) {client.receive(JSON.parse(JSON.stringify(packet))); duplicateDeliveries++;}
      }
      assert.deepEqual(client.bundle, server.bundle);
    }
    delivered = server.packets.length;
  }
  while (accepted < 500 && attempts < 5000) {
    const before = clone(server.bundle), all = [...units(before).values()], ix = locations(before);
    const blocks = [...ix.values()].filter(e => e.node.units), selected = blocks[random(blocks.length)];
    const list = selected.node.units;
    const joins = [];
    for (const {node} of ix.values()) for (let i = 1; i < (node.children?.length ?? 0); i++) {
      if (node.children[i - 1].units && node.children[i].units) joins.push([node.children[i - 1].id, node.children[i].id]);
    }
    let kind = random(6), operation;
    if (all.length > 120) kind = 1;
    if (blocks.length > 25) kind = joins.length ? 4 : 1;
    if (kind === 1 && all.length) operation = {kind: 'remove-owned', ids: [all[random(all.length)].unit.tokenId]};
    else if (kind === 2 && all.length) {
      const target = all[random(all.length)].unit, flag = random(2) ? 'bold' : 'italic';
      operation = {kind: 'format', ids: [target.tokenId], patch: {[flag]: !Boolean(target.attrs[flag])}};
    } else if (kind === 3 && list.length > 1) {
      operation = {kind: 'split', node: selected.node.id, right: list[1 + random(list.length - 1)].tokenId};
    } else if (kind === 4 && joins.length) {
      const [left, right] = joins[random(joins.length)]; operation = {kind: 'join', left, right};
    } else if (kind === 5) {
      const parents = [...ix.values()].filter(e => e.flow === selected.flow && e.node.children && ['flow', 'quote', 'item'].includes(e.node.type));
      const parent = parents[random(parents.length)].node;
      const candidates = parent.children.filter(n => n.id !== selected.node.id);
      operation = {kind: 'move', node: selected.node.id, parent: parent.id, right: candidates.length && random(2) ? candidates[random(candidates.length)].id : null};
    }
    if (!operation) {
      const char = String.fromCharCode(corpus[random(corpus.length)]);
      operation = {kind: 'insert', node: selected.node.id, right: list.length && random(2) ? list[random(list.length)].tokenId : null,
        text: char, attrs: random(2) ? {bold: true} : {italic: true}};
    }
    const actor = random(2) ? 'A' : 'B', id = `${actor}:seed:${seed}:${++attempts}`;
    const result = server.submit(actor, id, operation);
    recent.push({operation, outcome: result.outcome}); if (recent.length > 20) recent.shift();
    if (result.outcome !== 'Applied' || result.packet.patches.length === 0) {failures[result.reason ?? 'NoCanonicalChange'] = (failures[result.reason ?? 'NoCanonicalChange'] ?? 0) + 1; continue;}
    const old = units(before), after = units(server.bundle);
    if (operation.kind === 'insert') {
      const added = [...after.keys()].filter(id => !old.has(id)); assert.equal(added.length, operation.text.length);
      assert.deepEqual(added.map(id => after.get(id).unit.char), operation.text.split(''));
      assert(added.every(id => same(after.get(id).unit.attrs, operation.attrs)));
    } else if (operation.kind === 'remove-owned') {
      assert(!after.has(operation.ids[0])); assert.equal(after.size, old.size - 1);
    } else if (['split', 'join', 'move'].includes(operation.kind)) {
      assert.deepEqual([...after.keys()].sort(), [...old.keys()].sort());
    }
    for (const [id, record] of old) {
      if (operation.kind === 'remove-owned' && operation.ids.includes(id)) continue;
      assert(after.has(id));
      const expected = clone(record.unit);
      if (operation.kind === 'format' && operation.ids.includes(id)) expected.attrs = {...expected.attrs, ...operation.patch};
      assert.deepEqual(after.get(id).unit, expected, 'Unrelated raw unit/attribution changed');
    }
    accepted++; counts[operation.kind] = (counts[operation.kind] ?? 0) + 1;
    if (server.packets.length - delivered >= 24) deliver();
  }
  assert.equal(accepted, 500, 'Generated structural corpus did not make bounded progress'); deliver();
  return {seed, mutatingTransactions: accepted, attemptedTransactions: attempts, operationCounts: counts, rejectedOrNoop: failures,
    duplicateDeliveries, finalUnits: units(server.bundle).size, status: 'PASS'};
}
save();
try {
  const frozen = JSON.parse(fs.readFileSync(path.resolve(import.meta.dirname, '../../../v5-freeze-manifest.json'), 'utf8'));
  const projectRoot = path.resolve(import.meta.dirname, '../../..');
  let frozenCount = 0;
  for (const [file, expected] of Object.entries(frozen.hashes)) if (file.startsWith('validation/codex/run-20261016-20/')) {
    assert.equal(createHash('sha256').update(fs.readFileSync(path.join(projectRoot, file))).digest('hex'), expected, 'Frozen reference changed: ' + file); frozenCount++;
  }
  report.frozenReferenceFilesUnchanged = frozenCount;
  const reference = path.resolve(import.meta.dirname, '../../codex/run-20261016-20');
  report.referenceSources = Object.fromEntries(fs.readdirSync(reference).filter(n => n.endsWith('.mjs')).map(n => [n, createHash('sha256').update(fs.readFileSync(path.join(reference, n))).digest('hex')]));
  fixture('Opposite edits around split; A Undo joins live peer text without root restore', s => {
    const split = s.submit('A', 'A:split', {kind: 'split', node: 'tail', right: 'tail:7'}); assert.equal(split.outcome, 'Applied');
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    assert(right);
    s.submit('B', 'B:peer', {kind: 'insert', node: right.id, right: right.units[0].tokenId, text: 'اردو ', attrs: {italic: true}});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(T.locate(s.bundle.main, 'tail').node.units.map(u => u.char).join(''), 'Englishاردو  tail');
    assert(T.locate(s.bundle.main, 'tail').node.units.some(u => u.tokenId.startsWith('B:') && u.attrs.italic));
    return {body: text(s.bundle), inverse: 'Live join preserves peer-owned units'};
  });
  fixture('Per-user insertion Undo preserves peer units inserted inside its span', s => {
    s.submit('A', 'A:insert', {kind: 'insert', node: 'tail', right: 'tail:0', text: 'کتاب'});
    const mine = units(s.bundle); const ids = [...mine.keys()].filter(id => id.startsWith('A:unit:'));
    s.submit('B', 'B:inside', {kind: 'insert', node: 'tail', right: ids[2], text: 'م', attrs: {bold: true}});
    const complete = clone(s.bundle);
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(T.locate(s.bundle.main, 'tail').node.units.map(u => u.char).join(''), 'مEnglish tail');
    assert.equal(s.redo('A', 'A:redo').outcome, 'Applied'); assert.deepEqual(s.bundle, complete);
    return {remainingPeer: [...units(s.bundle).keys()].filter(id => id.startsWith('B:'))};
  });
  fixture('Formatting Undo is selective by field owner, including same-value peer intent', s => {
    s.submit('A', 'A:format', {kind: 'format', ids: ['p:0'], patch: {bold: true, italic: true}});
    s.submit('B', 'B:same', {kind: 'format', ids: ['p:0'], patch: {bold: true}});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.deepEqual(units(s.bundle).get('p:0').unit.attrs, {bold: true});
    return {attrs: units(s.bundle).get('p:0').unit.attrs};
  });
  for (const moveFirst of [true, false]) fixture('Move/delete order never resurrects a deleted node: ' + moveFirst, s => {
    const move = () => s.submit('A', 'A:move', {kind: 'move', node: 'tail', parent: 'body', right: 'list'});
    const remove = () => s.submit('B', 'B:delete', {kind: 'delete-node', node: 'tail'});
    if (moveFirst) {assert.equal(move().outcome, 'Applied'); remove();} else {remove(); assert.equal(move().outcome, 'Conflict');}
    assert(!locations(s.bundle).has('tail'));
    return {deleted: true};
  });
  fixture('Cycle/grammar failure is rejected before publication', s => {
    const before = clone(s.bundle), sequence = s.sequence;
    const result = s.submit('A', 'A:cycle', {kind: 'move', node: 'list', parent: 'item', right: null});
    assert.equal(result.outcome, 'Conflict'); assert.deepEqual(s.bundle, before); assert.equal(s.sequence, sequence);
    return result;
  });
  fixture('Rich-cell list-independent split/join and header/footer edits retain flow ownership', s => {
    s.submit('A', 'A:cell', {kind: 'split', node: 'cell-p-a', right: 'cell-p-a:2'});
    s.submit('B', 'B:header', {kind: 'insert', node: 'header-p', right: null, text: ' English'});
    s.submit('B', 'B:footer', {kind: 'insert', node: 'footer-p', right: null, text: ' ۱'});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(text(s.bundle, 'cell-flow-a'), 'اردو\nدوسرا');
    assert.equal(text(s.bundle, 'header'), 'سرورق English'); assert.equal(text(s.bundle, 'footer'), 'صفحہ ۱');
    return {cell: text(s.bundle, 'cell-flow-a'), header: text(s.bundle, 'header'), footer: text(s.bundle, 'footer')};
  });
  fixture('Existing list split + selective item join preserve peer text and original ancestry', s => {
    assert.equal(s.submit('A', 'A:list', {kind: 'split-item', node: 'p', right: 'p:2'}).outcome, 'Applied');
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    assert.equal(s.submit('B', 'B:list-text', {kind: 'insert', node: right.id, right: right.units[0].tokenId, text: 'م', attrs: {italic: true}}).outcome, 'Applied');
    const result = s.undo('A', 'A:undo'); assert.equal(result.outcome, 'Applied');
    const list = locations(s.bundle).get('list').node;
    assert.equal(list.children.length, 1); assert.equal(list.children[0].id, 'item');
    assert.equal(locations(s.bundle).get('p').node.units.map(u => u.char).join(''), 'کتماب قلم');
    return {result: result.outcome, originalItemRetained: true};
  });
  fixture('Late peer text anchored inside a joined block rebases by surviving unit identity', s => {
    s.submit('A', 'A:split', {kind: 'split', node: 'tail', right: 'tail:7'});
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    const offline = {kind: 'insert', node: right.id, right: 'tail:8', text: 'اردو '};
    s.undo('A', 'A:undo');
    assert.equal(s.submit('B', 'B:late', offline).outcome, 'Applied');
    assert.equal(locations(s.bundle).get('tail').node.units.map(u => u.char).join(''), 'English اردو tail');
    return {stableToken: 'tail:8', joinedNode: 'tail'};
  });
  fixture('Peer ancestry move blocks list Undo without erasing peer structure', s => {
    s.submit('A', 'A:list', {kind: 'split-item', node: 'p', right: 'p:2'});
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    assert.equal(s.submit('B', 'B:move-out', {kind: 'move', node: right.id, parent: 'body', right: 'tail'}).outcome, 'Applied');
    const before = clone(s.bundle), result = s.undo('A', 'A:undo');
    assert.equal(result.outcome, 'UndoConflict'); assert.deepEqual(s.bundle, before);
    assert.equal(locations(s.bundle).get(right.id).parent, 'body');
    return result;
  });
  fixture('Deleted comment anchor retains original identity/context and becomes orphaned', s => {
    assert.equal(s.submit('B', 'B:delete-anchor', {kind: 'remove-owned', ids: ['p:0']}).outcome, 'Applied');
    assert.deepEqual(s.bundle.comments[0], {id: 'comment', flow: 'body', units: ['p:0'], context: 'ک', state: 'orphaned'});
    return s.bundle.comments[0];
  });
  fixture('Same-boundary insert order is the accepted server order', s => {
    s.submit('A', 'A:same', {kind: 'insert', node: 'tail', right: 'tail:0', text: 'ا'});
    s.submit('B', 'B:same', {kind: 'insert', node: 'tail', right: 'tail:0', text: 'ب'});
    assert.equal(locations(s.bundle).get('tail').node.units.slice(0, 2).map(u => u.char).join(''), 'اب');
    assert(s.packets.every(p => p.patches.every(patch => patch.path.includes('units'))));
    return {tiePolicy: 'Server acceptance sequence, explicit stable right neighbor', sequence: s.sequence};
  });
  fixture('Split Undo/Redo restores stable right identity around live peer content', s => {
    s.submit('A', 'A:split', {kind: 'split', node: 'tail', right: 'tail:7'});
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    s.submit('B', 'B:prefix', {kind: 'insert', node: right.id, right: right.units[0].tokenId, text: 'اردو ', attrs: {italic: true}});
    const complete = clone(s.bundle);
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(s.redo('A', 'A:redo').outcome, 'Applied'); assert.deepEqual(s.bundle, complete);
    return {rightId: right.id, exactPeerContentRestored: true};
  });
  fixture('Delete Undo/Redo restores raw unit identities in source order and reattaches the original anchor', s => {
    const complete = clone(s.bundle);
    s.submit('B', 'B:delete', {kind: 'remove-owned', ids: ['p:2', 'p:0']});
    const deleted = clone(s.bundle);
    assert.equal(s.undo('B', 'B:undo').outcome, 'Applied'); assert.deepEqual(s.bundle, complete);
    assert.equal(s.redo('B', 'B:redo').outcome, 'Applied'); assert.deepEqual(s.bundle, deleted);
    return {originalIdsRestored: true, originalContextRetained: true};
  });
  fixture('A move Undo cannot override B later relocation', s => {
    s.submit('A', 'A:move', {kind: 'move', node: 'tail', parent: 'body', right: 'list'});
    s.submit('B', 'B:move', {kind: 'move', node: 'tail', parent: 'body', right: null});
    const before = clone(s.bundle), result = s.undo('A', 'A:undo');
    assert.equal(result.outcome, 'UndoConflict'); assert.equal(result.reason, 'MoveOwnershipConflict'); assert.deepEqual(s.bundle, before);
    return {outcome: result.outcome, peerRelocationPreserved: true};
  });
  fixture('Redo preserves superseding peer formatting and reports the skipped field', s => {
    s.submit('A', 'A:format', {kind: 'format', ids: ['p:0'], patch: {bold: true, italic: true}});
    s.submit('B', 'B:bold', {kind: 'format', ids: ['p:0'], patch: {bold: true}});
    s.undo('A', 'A:undo');
    s.submit('B', 'B:italic', {kind: 'format', ids: ['p:0'], patch: {italic: false}});
    const result = s.redo('A', 'A:redo');
    assert.equal(result.outcome, 'Applied'); assert.equal(result.skippedFields, 1); assert.equal(result.effect, 'SupersededFieldsPreserved');
    assert.deepEqual(units(s.bundle).get('p:0').unit.attrs, {bold: true, italic: false});
    return {effect: result.effect, skippedFields: result.skippedFields};
  });
  fixture('Redo does not resurrect a peer deletion received after the local Undo', s => {
    s.submit('A', 'A:insert', {kind: 'insert', node: 'tail', right: 'tail:0', text: 'کتاب'});
    const mine = [...units(s.bundle).keys()].filter(id => id.startsWith('A:unit:'));
    s.undo('A', 'A:undo');
    s.submit('B', 'B:offline-delete', {kind: 'remove-owned', ids: mine});
    const before = clone(s.bundle), result = s.redo('A', 'A:redo');
    assert.equal(result.outcome, 'RedoConflict'); assert.deepEqual(s.bundle, before);
    return {outcome: result.outcome, retainedRedo: true};
  });
  function nestedFixture() {
    const bundle = initial(), item = locations(bundle).get('item').node;
    item.children = [container('quote', 'quote', [item.children[0], p('quote-tail', 'تحقیق')]),
      container('nested-list', 'list', [container('nested-item', 'item', [p('nested-p', 'اردو tail')])], {kind: 'bullet'})];
    return bundle;
  }
  fixture('Nested list split Undo/Redo preserves quote ancestry, trailing nested list and peer text', s => {
    assert.equal(s.submit('A', 'A:nested-split', {kind: 'split-item', node: 'p', right: 'p:2'}).outcome, 'Applied');
    const right = T.leaves(flow(s.bundle, 'body')).find(n => n.id.startsWith('A:block:'));
    s.submit('B', 'B:inside', {kind: 'insert', node: right.id, right: right.units[0].tokenId, text: 'م', attrs: {italic: true}});
    s.submit('B', 'B:tail', {kind: 'insert', node: 'quote-tail', right: null, text: ' B', attrs: {bold: true}});
    const complete = clone(s.bundle);
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.deepEqual(locations(s.bundle).get('item').node.children.map(n => n.id), ['quote', 'nested-list']);
    assert.deepEqual(locations(s.bundle).get('quote').node.children.map(n => n.id), ['p', 'quote-tail']);
    assert.equal(locations(s.bundle).get('quote-tail').node.units.map(u => u.char).join(''), 'تحقیق B');
    assert.equal(s.redo('A', 'A:redo').outcome, 'Applied'); assert.deepEqual(s.bundle, complete);
    assert.equal(s.undo('A', 'A:undo-again').outcome, 'Applied');
    assert(units(s.bundle).get([...units(s.bundle).keys()].find(id => id.startsWith('B:unit:'))).unit.attrs.italic);
    return {originalWrappersRetained: true, peerPayloadRetained: true};
  }, nestedFixture());
  fixture('Nested list Redo refuses a peer ancestry change without publishing its staged split', s => {
    s.submit('A', 'A:nested-split', {kind: 'split-item', node: 'p', right: 'p:2'});
    s.undo('A', 'A:undo');
    s.submit('B', 'B:move-tail', {kind: 'move', node: 'quote-tail', parent: 'body', right: 'tail'});
    const before = clone(s.bundle), sequence = s.sequence, result = s.redo('A', 'A:redo');
    assert.equal(result.outcome, 'RedoConflict'); assert.deepEqual(s.bundle, before); assert.equal(s.sequence, sequence);
    return {outcome: result.outcome, stagedMutationDiscarded: true};
  }, nestedFixture());
  const marked = initial();
  locations(marked).get('p').node.units = p('p', 'ک\u0650تاب').units;
  fixture('Captured structural split refuses a current interior grapheme boundary', s => {
    const before = clone(s.bundle), result = s.submit('A', 'A:bad-gap', {kind: 'split-item', node: 'p', right: 'p:1'});
    assert.equal(result.outcome, 'Conflict'); assert.equal(result.reason, 'InvalidSplitBoundary'); assert.deepEqual(s.bundle, before);
    return {outcome: result.outcome, rawUnitsUnchanged: true};
  }, marked);
  fixture('Undo formatting while its unit is deleted must update the retained preimage before peer Delete Undo', s => {
    s.submit('A', 'A:format', {kind: 'format', ids: ['p:0'], patch: {bold: true}});
    s.submit('B', 'B:delete', {kind: 'remove-owned', ids: ['p:0']});
    s.undo('A', 'A:undo-format');
    s.undo('B', 'B:undo-delete');
    assert.deepEqual(units(s.bundle).get('p:0').unit.attrs, {});
    return {undoneStyleDidNotResurrect: true};
  });
  fixture('Deleted subtree Undo restores pruned list ancestry while retaining peer edits outside it', s => {
    const originalList = clone(locations(s.bundle).get('list').node);
    s.submit('A', 'A:delete-p', {kind: 'delete-node', node: 'p'});
    assert(!locations(s.bundle).has('list')); assert.equal(s.bundle.comments[0].state, 'orphaned');
    s.submit('B', 'B:tail', {kind: 'insert', node: 'tail', right: null, text: ' peer', attrs: {italic: true}});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.deepEqual(locations(s.bundle).get('list').node, originalList);
    assert.equal(locations(s.bundle).get('tail').node.units.map(u => u.char).join(''), 'English tail peer');
    assert.equal(s.bundle.comments[0].state, 'attached');
    return {originalAncestorsRestored: true, peerContributionRetained: true};
  });
  const tableBundle = initial();
  tableBundle.comments.push({id: 'cell-comment', flow: 'cell-flow-a', units: ['cell-p-a:0'], context: 'ا', state: 'attached'});
  fixture('Deleted table Undo restores rich flows/IDs and explicitly orphaned flow anchors', s => {
    const table = clone(locations(s.bundle).get('table').node);
    s.submit('A', 'A:delete-table', {kind: 'delete-node', node: 'table'});
    assert.equal(s.bundle.comments.find(c => c.id === 'cell-comment').state, 'orphaned');
    assert.equal(s.undo('A', 'A:undo-table').outcome, 'Applied');
    assert.deepEqual(locations(s.bundle).get('table').node, table);
    assert.equal(s.bundle.comments.find(c => c.id === 'cell-comment').state, 'attached');
    return {richFlowIdsRestored: true};
  }, tableBundle);
  fixture('Deleted subtree Redo cannot erase peer edits added after its Undo', s => {
    s.submit('A', 'A:delete', {kind: 'delete-node', node: 'list'}); s.undo('A', 'A:undo');
    s.submit('B', 'B:inside', {kind: 'insert', node: 'p', right: null, text: ' peer'});
    const before = clone(s.bundle), result = s.redo('A', 'A:redo');
    assert.equal(result.outcome, 'RedoConflict'); assert.equal(result.reason, 'PeerSubtreeChanged'); assert.deepEqual(s.bundle, before);
    return {peerContentRetained: true, outcome: result.outcome};
  });
  const imageBundle = initial();
  imageBundle.main.props.resources.image = {uri: 'local:owned-fixture', alt: 'owned specimen'};
  flow(imageBundle, 'body').children.splice(1, 0, {id: 'image-node', type: 'object', props: {kind: 'image', resourceId: 'image'}});
  fixture('Deleted resource object pins its payload for Undo and restores the original reference', s => {
    s.submit('A', 'A:delete-image', {kind: 'delete-node', node: 'image-node'});
    const before = clone(s.bundle), refusal = s.submit('B', 'B:gc', {kind: 'remove-resource', resourceId: 'image'});
    assert.equal(refusal.outcome, 'Conflict'); assert.equal(refusal.reason, 'ResourceRetained'); assert.deepEqual(s.bundle, before);
    assert.equal(s.undo('A', 'A:undo-image').outcome, 'Applied');
    assert.equal(locations(s.bundle).get('image-node').node.props.resourceId, 'image');
    assert.deepEqual(s.bundle.main.props.resources.image, imageBundle.main.props.resources.image);
    return {resourceReferenceAndPayloadRetained: true};
  }, imageBundle);
  const sharedImages = clone(imageBundle);
  flow(sharedImages, 'body').children.splice(2, 0, {id: 'image-two', type: 'object', props: {kind: 'image', resourceId: 'image'}});
  fixture('Shared resource is counted once and remains pinned until every explicit history owner releases', s => {
    s.submit('A', 'A:delete', {kind: 'delete-node', node: 'image-node'});
    s.submit('B', 'B:delete', {kind: 'delete-node', node: 'image-two'});
    assert.deepEqual(s.retentionSummary('image'), {resourceId: 'image', historyOwners: ['A', 'B'], canonicalPayloadCopies: 1});
    s.discardOwnHistory('A');
    assert.equal(s.submit('A', 'A:gc-blocked', {kind: 'remove-resource', resourceId: 'image'}).reason, 'ResourceRetained');
    s.discardOwnHistory('B');
    assert.equal(s.submit('A', 'A:gc', {kind: 'remove-resource', resourceId: 'image'}).outcome, 'Applied');
    assert(!Object.hasOwn(s.bundle.main.props.resources, 'image'));
    return {sharedPayloadCopies: 1, releaseWasExplicitForBothOwners: true};
  }, sharedImages);
  fixture('Two producers of one author retain a shared resource until both scoped histories release it', s => {
    assert.equal(s.submit('A', 'A:phone-delete', {kind: 'delete-node', node: 'image-node'}, true, null, 'phone').outcome, 'Applied');
    assert.equal(s.submit('A', 'A:laptop-delete', {kind: 'delete-node', node: 'image-two'}, true, null, 'laptop').outcome, 'Applied');
    assert.deepEqual(s.history.map(item => item.producer).sort(), ['laptop', 'phone']);
    assert.equal(s.discardOwnHistory('A').outcome, 'ProducerOriginRequired');
    assert.equal(s.history.length, 2);
    assert.equal(s.discardOwnHistory('A', 'phone').outcome, 'HistoryDiscarded');
    assert.equal(s.submit('A', 'A:gc-still-pinned', {kind: 'remove-resource', resourceId: 'image'}, true, null, 'phone').reason, 'ResourceRetained');
    assert.equal(s.retentionSummary('image').canonicalPayloadCopies, 1);
    assert.equal(s.discardOwnHistory('A', 'laptop').outcome, 'HistoryDiscarded');
    assert.equal(s.submit('A', 'A:gc-all-released', {kind: 'remove-resource', resourceId: 'image'}, true, null, 'phone').outcome, 'Applied');
    return {sameAuthorDistinctProducerLeases: true, sharedPayloadCopies: 1, bothProducerReleasesRequired: true};
  }, sharedImages);
  const single = initial();
  single.main = T.makeDocument(container('body', 'flow', [p('p', 'اردو')])); single.comments[0].context = 'ا';
  fixture('Undo after deleting the last paragraph preserves peer text in the generated landing block', s => {
    s.submit('A', 'A:delete-last', {kind: 'delete-node', node: 'p'});
    const landing = T.leaves(flow(s.bundle, 'body'))[0];
    s.submit('B', 'B:landing', {kind: 'insert', node: landing.id, right: null, text: 'peer', attrs: {italic: true}});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(text(s.bundle), 'اردو\npeer'); assert(locations(s.bundle).has(landing.id));
    return {peerLandingId: landing.id, originalBlockId: 'p'};
  }, single);
  fixture('One multiflow transaction and its selective Undo preserve a peer header contribution', s => {
    const before = clone(s.bundle);
    assert.equal(s.submit('A', 'A:multi', {kind: 'batch', operations: [
      {kind: 'insert', node: 'header-p', right: null, text: ' A'}, {kind: 'insert', node: 'cell-p-a', right: null, text: ' A'}
    ]}).outcome, 'Applied');
    assert.equal(s.sequence, 1);
    s.submit('B', 'B:header', {kind: 'insert', node: 'header-p', right: null, text: ' B'});
    assert.equal(s.undo('A', 'A:undo').outcome, 'Applied');
    assert.equal(text(s.bundle, 'header'), text(before, 'header') + ' B');
    assert.equal(text(s.bundle, 'cell-flow-a'), text(before, 'cell-flow-a'));
    return {singlePublicationForTwoFlows: true, peerHeaderRetained: true};
  });
  fixture('A later invalid child rolls back an earlier staged multiflow edit before publication', s => {
    const before = clone(s.bundle), sequence = s.sequence;
    const result = s.submit('A', 'A:bad-batch', {kind: 'batch', operations: [
      {kind: 'insert', node: 'header-p', right: null, text: ' must not publish'}, {kind: 'move', node: 'list', parent: 'item', right: null}
    ]});
    assert.equal(result.outcome, 'Conflict'); assert.deepEqual(s.bundle, before); assert.equal(s.sequence, sequence);
    return {stagedFirstChildNotPublished: true, intentRetained: true};
  });
  fixture('Revoked offline intent remains recoverable; duplicate identities do not duplicate effects', s => {
    const op = {kind: 'insert', node: 'header-p', right: null, text: ' offline'};
    s.roles.A = 'viewer'; const before = clone(s.bundle);
    const refused = s.submit('A', 'A:offline', op); assert.equal(refused.outcome, 'RejectedPermission'); assert.deepEqual(refused.retainedIntent, op); assert.deepEqual(s.bundle, before);
    s.roles.A = 'editor'; s.submit('A', 'A:once', op); const accepted = clone(s.bundle);
    assert.equal(s.submit('A', 'A:once', op).outcome, 'Duplicate'); assert.deepEqual(s.bundle, accepted);
    assert.equal(s.submit('A', 'A:once', {...op, text: 'different'}).outcome, 'RejectedReusedOperationId');
    return {refusal: refused.outcome, idempotent: true};
  });
  fixture('A multi-part gesture Redo restores an Urdu suffix in exact source order at paragraph end', s => {
    const group = 'A:footer-gesture';
    s.beginGesture('A', group, 'footer');
    for (const [i, value] of ['ک', 'تاب'].entries()) s.submit('A', 'A:part:' + i,
      {kind: 'insert', node: 'footer-p', right: null, text: value, attrs: {bold: true, italic: true}}, true, group);
    s.closeGesture('A', group);
    const accepted = clone(s.bundle);
    assert.equal(text(accepted, 'footer'), 'صفحہکتاب');
    s.submit('B', 'B:other-flow', {kind: 'insert', node: 'header-p', right: null, text: ' peer'});
    const peerHeader = text(s.bundle, 'header');
    assert.equal(s.undo('A', 'A:undo-suffix').outcome, 'Applied'); assert.equal(text(s.bundle, 'footer'), 'صفحہ');
    assert.equal(s.redo('A', 'A:redo-suffix').outcome, 'Applied');
    assert.deepEqual(flow(s.bundle, 'footer'), flow(accepted, 'footer')); assert.equal(text(s.bundle, 'header'), peerHeader);
    return {exactSuffix: 'کتاب', acceptedParts: 2, rawAttributionAndIdsPreserved: true, peerFlowPreserved: true};
  });
  fixture('Deleting a multi-unit paragraph suffix and Undo preserve raw source order and attributes', s => {
    s.submit('B', 'B:style-suffix', {kind: 'format', ids: ['header-p:2', 'header-p:4'], patch: {italic: true}});
    const original = clone(flow(s.bundle, 'header'));
    s.submit('A', 'A:delete-suffix', {kind: 'remove-owned', ids: ['header-p:2', 'header-p:3', 'header-p:4']});
    assert.equal(s.undo('A', 'A:undo-delete-suffix').outcome, 'Applied');
    assert.deepEqual(flow(s.bundle, 'header'), original);
    return {restoredUnits: 3, sameSurvivingLeftAnchor: true, originalOrderAndAttributesPreserved: true};
  });
  fixture('Header gesture refuses two-endpoint cell joins and mixed-flow batches before publication', s => {
    const group = 'A:header-gesture'; s.beginGesture('A', group, 'header');
    const original = clone(s.bundle), history = clone(s.history), sequence = s.sequence;
    const join = {kind: 'join', left: 'cell-p-a', right: 'cell-p-a2'};
    const refused = s.submit('A', 'A:wrong-cell-join', join, true, group);
    assert.equal(refused.outcome, 'RejectedGroupScope'); assert.deepEqual(refused.retainedIntent, join);
    const batch = {kind: 'batch', operations: [{kind: 'insert', node: 'header-p', right: null, text: 'must not publish'}, join]};
    const batchResult = s.submit('A', 'A:wrong-batch', batch, true, group);
    assert.equal(batchResult.outcome, 'RejectedGroupScope'); assert.deepEqual(batchResult.retainedIntent, batch);
    assert.equal(s.submit('A', 'A:unknown-group-command', {kind: 'unknown'}, true, group).outcome, 'RejectedGroupScope');
    assert.deepEqual(s.bundle, original); assert.deepEqual(s.history, history); assert.equal(s.sequence, sequence);
    return {wrongFlowJoinRefused: true, firstBatchChildNotPublished: true, failedIntentRetained: true};
  });
  fixture('A same-flow cell join stays eligible for one-gesture selective Undo and Redo', s => {
    const group = 'A:cell-gesture', original = clone(flow(s.bundle, 'cell-flow-a'));
    s.beginGesture('A', group, 'cell-flow-a');
    assert.equal(s.submit('A', 'A:cell-join', {kind: 'join', left: 'cell-p-a', right: 'cell-p-a2'}, true, group).outcome, 'Applied');
    s.closeGesture('A', group); const joined = clone(flow(s.bundle, 'cell-flow-a'));
    assert.equal(text(s.bundle, 'cell-flow-a'), 'اردودوسرا');
    assert.equal(s.undo('A', 'A:undo-cell-join').outcome, 'Applied'); assert.deepEqual(flow(s.bundle, 'cell-flow-a'), original);
    assert.equal(s.redo('A', 'A:redo-cell-join').outcome, 'Applied'); assert.deepEqual(flow(s.bundle, 'cell-flow-a'), joined);
    return {sameFlowJoinAccepted: true, exactStructureUndoRedo: true};
  });
  report.c0Qualified = false;
  const root = path.resolve(import.meta.dirname, '../../..');
  const fixtures = JSON.parse(fs.readFileSync(path.join(root, 'validation/urdu/u0/fixtures.json'), 'utf8'));
  const corpus = [...new Set(fixtures.flatMap(f => f.utf16CodeUnits))];
  report.generated = [];
  for (let seed = 1; seed <= 20; seed++) {report.generated.push(generated(seed, corpus)); save();}
  report.totalStructuredMutatingTransactions = report.generated.reduce((sum, item) => sum + item.mutatingTransactions, 0);
  report.status = 'ORDERED_STRUCTURE_TRIAL_PASS_GATE_OPEN';
  report.unresolved = ['Durable retention/compaction and inverse leases beyond this bounded model', 'Production schema/typed wire format', 'Native composing-range admission',
    'Durable log/permissions and Kotlin client integration', 'Broader adversarial structural corpus and performance'];
  console.log(`PASS ordered structural trial: ${report.checks.length} traces + ${report.totalStructuredMutatingTransactions} mutating transactions; reused frozen tree utilities unchanged; C0 OPEN`);
} catch (error) {report.status = 'FAIL'; report.error = error.stack; console.error(error.stack); process.exitCode = 1;}
finally {
  if (createHash('sha256').update(fs.readFileSync(import.meta.filename)).digest('hex') !== report.sourceSha256) {
    report.status = 'FAIL'; report.error = 'Structure source changed during trial'; process.exitCode = 1;
  }
  if (createHash('sha256').update(fs.readFileSync(new URL('./ordered-structure-model.mjs', import.meta.url))).digest('hex') !== report.modelSha256) {
    report.status = 'FAIL'; report.error = 'Structure model changed during trial'; process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString(); save();
}
