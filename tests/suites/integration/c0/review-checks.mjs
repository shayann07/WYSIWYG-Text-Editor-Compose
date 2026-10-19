import assert from 'node:assert/strict';
import * as Y from 'yjs';
import fs from 'node:fs';
import path from 'node:path';
import {ReviewAdmissionTrial} from './review-admission.mjs';

const output = path.resolve(process.argv.find(a => a.startsWith('--output='))?.slice(9) ?? `desktop-prototype/local-evidence/c0-review-${Date.now()}`);
fs.mkdirSync(output, {recursive: false});
const result = {status: 'RUNNING', gate: 'C0', startedAt: new Date().toISOString(), checks: []};
const save = () => fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(result, null, 2) + '\n');
const unit = (id, char, style = 0, kind = 0) => ({id, char, style: kind ? 0 : style, kind, paragraphStyle: kind ? style : 0});
const initial = [unit('base:1', 0x06A9, 1), unit('break:1', 10, 2, 1), unit('base:2', 0x0628, 2)];
const proposal = {id: 'suggestion-1', kind: 'replace', targetIds: ['base:1'], units: [unit('proposal:1', 0x062A, 3)], resources: ['retained-resource']};
function check(name, fn) {const evidence = fn(); result.checks.push({name, status: 'PASS', evidence}); save();}
save();
try {
  for (const first of ['accept', 'reject']) check(`Competing ${first} then ${first === 'accept' ? 'reject' : 'accept'} remains coherent`, () => {
    const s = new ReviewAdmissionTrial(initial);
    assert.equal(s.propose('A', proposal).outcome, 'Proposed');
    const publications = [];
    s.doc.on('update', () => publications.push(s.canonical()));
    const firstReceipt = s.submit('A', {operationId: 'A:1', proposalId: proposal.id, decision: first});
    const secondReceipt = s.submit('B', {operationId: 'B:1', proposalId: proposal.id, decision: first === 'accept' ? 'reject' : 'accept'});
    assert.equal(secondReceipt.outcome, 'Superseded');
    assert.equal(s.proposals.get(proposal.id).state, first === 'accept' ? 'accepted' : 'rejected');
    assert.deepEqual(s.units, first === 'accept' ? [proposal.units[0], initial[1], initial[2]] : initial);
    assert.equal(publications.length, 2);
    assert.deepEqual(publications[0].units, publications[1].units);
    const replica = new Y.Doc(); Y.applyUpdate(replica, Y.encodeStateAsUpdate(s.doc));
    assert.deepEqual(replica.getArray('body').toJSON(), s.units);
    const evidence = {firstReceipt, secondReceipt, final: s.canonical()}; s.doc.destroy(); replica.destroy(); return evidence;
  });
  check('Duplicate decision is idempotent; reused identity with changed payload rejects', () => {
    const s = new ReviewAdmissionTrial(initial); s.propose('A', proposal);
    const request = {operationId: 'A:1', proposalId: proposal.id, decision: 'accept'};
    s.submit('A', request); const before = s.canonical();
    assert.equal(s.submit('A', request).outcome, 'Duplicate');
    assert.equal(s.submit('A', {...request, decision: 'reject'}).outcome, 'RejectedReusedOperationId');
    assert.deepEqual(s.canonical(), before); s.doc.destroy(); return {final: before};
  });
  check('Revoked offline decision stays recoverable and cannot become accepted state', () => {
    const s = new ReviewAdmissionTrial(initial); s.propose('A', proposal);
    const request = {operationId: 'A:offline:1', proposalId: proposal.id, decision: 'accept', actor: 'owner'};
    s.roles.A = 'viewer'; const before = s.canonical();
    assert.equal(s.submit('A', request).outcome, 'RejectedPermission');
    assert.deepEqual(s.canonical(), before); assert.deepEqual(s.recovery.get(request.operationId), request);
    assert.equal(s.submit('viewer', {...request, operationId: 'owner:forged'}).outcome, 'RejectedPermission');
    assert.deepEqual(s.canonical(), before); s.doc.destroy(); return {retainedRequest: request, authoritativeStateUnchanged: true};
  });
  check('Missing original target reports conflict and retains exact preimage/resources', () => {
    const s = new ReviewAdmissionTrial(initial); s.propose('A', proposal);
    s.doc.transact(() => s.doc.getArray('body').delete(0, 1), 'accepted-peer-delete');
    const before = s.units;
    assert.equal(s.submit('B', {operationId: 'B:1', proposalId: proposal.id, decision: 'accept'}).outcome, 'Conflicted');
    assert.deepEqual(s.units, before);
    assert.deepEqual(s.proposals.get(proposal.id).preimage, [initial[0]]);
    assert.deepEqual(s.proposals.get(proposal.id).resources, ['retained-resource']);
    const retained = s.proposals.get(proposal.id); s.doc.destroy(); return retained;
  });
  check('Formatting suggestion preserves unit identities and structural paragraph attributes', () => {
    const s = new ReviewAdmissionTrial(initial);
    s.propose('A', {id: 'format-1', kind: 'format', targetIds: ['base:2'], units: [], style: 3, resources: []});
    const receipt = s.submit('B', {operationId: 'B:format:1', proposalId: 'format-1', decision: 'accept'});
    assert.equal(receipt.outcome, 'AcceptedSuggestion');
    assert.deepEqual(s.units, [initial[0], initial[1], {...initial[2], style: 3}]);
    const final = s.canonical(); s.doc.destroy(); return final;
  });
  check('Disjoint peer insertion remains accepted through a proposal decision', () => {
    const s = new ReviewAdmissionTrial(initial); s.propose('A', proposal);
    const peer = unit('peer:tail', 0x0645, 2);
    s.doc.transact(() => s.doc.getArray('body').insert(3, [new Y.Map(Object.entries(peer))]), 'accepted-peer-insert');
    assert.equal(s.submit('B', {operationId: 'B:1', proposalId: proposal.id, decision: 'accept'}).outcome, 'AcceptedSuggestion');
    assert.deepEqual(s.units, [proposal.units[0], initial[1], initial[2], peer]);
    const final = s.canonical(); s.doc.destroy(); return final;
  });
  check('Peer formatting changes the proposal basis and remains intact on conflict', () => {
    const s = new ReviewAdmissionTrial(initial); s.propose('A', proposal);
    s.doc.transact(() => s.doc.getArray('body').get(0).set('style', 2), 'accepted-peer-format');
    const before = s.units;
    assert.equal(s.submit('B', {operationId: 'B:1', proposalId: proposal.id, decision: 'accept'}).outcome, 'Conflicted');
    assert.deepEqual(s.units, before); s.doc.destroy(); return {peerStylesPreserved: true};
  });
  check('Malformed proposed units and formatting reject before publication', () => {
    const s = new ReviewAdmissionTrial(initial); const before = s.canonical();
    assert.equal(s.propose('A', {...proposal, units: [{...proposal.units[0], char: 65536}]}).outcome, 'RejectedSchema');
    assert.equal(s.propose('A', {...proposal, kind: 'format', style: 9, units: []}).outcome, 'RejectedSchema');
    assert.deepEqual(s.canonical(), before); s.doc.destroy(); return {unchanged: true};
  });
  result.status = 'TYPED_ADMISSION_TRIAL_PASS_GATE_OPEN'; result.c0Qualified = false;
  result.scope = 'In-memory central ordering of typed inline review decisions plus Yjs publication; no durable service/auth or production editor integration';
  result.unresolved = ['Structural suggestions and selective review Undo', 'Real authenticated transport and resource leases', 'Native input integration', 'C0 full conflict/portability/retention corpus'];
  console.log(`PASS typed review admission: ${result.checks.length} traces; C0 OPEN`);
} catch (error) {result.status = 'FAIL'; result.error = error.stack; console.error(error.stack); process.exitCode = 1;}
finally {result.finishedAt = new Date().toISOString(); save();}
