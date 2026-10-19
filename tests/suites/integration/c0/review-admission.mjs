/** Private in-memory C0 experiment: serialize typed review intents before CRDT publication.
 * No authentication, durable storage or production service is implemented here. */
import * as Y from 'yjs';
import {createHash} from 'node:crypto';

const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const atom = value => new Y.Map(Object.entries(value));
const validUnit = u => u && typeof u.id === 'string' && u.id.length > 0 &&
  Number.isInteger(u.char) && u.char >= 0 && u.char <= 65535 &&
  (u.kind === 0 || u.kind === 1) && Number.isInteger(u.style) && u.style >= 0 && u.style <= 3 &&
  Number.isInteger(u.paragraphStyle) && u.paragraphStyle >= 0 && u.paragraphStyle <= 3 &&
  (u.kind === 0 ? u.paragraphStyle === 0 : u.char === 10 && u.style === 0);

export class ReviewAdmissionTrial {
  constructor(initialUnits, roles = {A: 'editor', B: 'editor', viewer: 'viewer'}) {
    if (!initialUnits.every(validUnit) || new Set(initialUnits.map(u => u.id)).size !== initialUnits.length) throw new Error('Invalid initial schema');
    this.doc = new Y.Doc();
    this.roles = {...roles}; // Trusted transport identity/role stub, not fields from a request.
    this.recovery = new Map();
    this.doc.transact(() => {
      this.doc.getArray('body').insert(0, initialUnits.map(atom));
      this.doc.getMap('metadata').set('schema', 'c0-review-admission-experiment-1');
    }, 'fixture');
  }
  get units() {return this.doc.getArray('body').toArray().map(u => u.toJSON());}
  get proposals() {return this.doc.getMap('proposals');}
  get receipts() {return this.doc.getMap('receipts');}
  canonical() {return {units: this.units, proposals: this.proposals.toJSON()};}

  submit(authenticatedActor, request) {
    const original = structuredClone(request);
    if (this.roles[authenticatedActor] !== 'editor') {
      this.recovery.set(request.operationId, original);
      return {outcome: 'RejectedPermission', retainLocalDraft: true};
    }
    if (typeof request.operationId !== 'string' || !request.operationId.startsWith(authenticatedActor + ':')) return {outcome: 'RejectedOperationIdentity'};
    const signature = hash(original), previous = this.receipts.get(request.operationId);
    if (previous) return previous.signature === signature ? {...previous, outcome: 'Duplicate'} : {outcome: 'RejectedReusedOperationId'};
    const proposal = this.proposals.get(request.proposalId);
    if (!proposal) return {outcome: 'RejectedUnknownProposal'};
    if (!['accept', 'reject'].includes(request.decision)) return {outcome: 'RejectedDecision'};
    if (proposal.state !== 'pending') {
      const receipt = {outcome: 'Superseded', signature, currentState: proposal.state};
      this.doc.transact(() => this.receipts.set(request.operationId, receipt), 'admission');
      return receipt;
    }
    const units = this.units, ids = units.map(u => u.id), positions = proposal.targetIds.map(id => ids.indexOf(id));
    let targetValid = positions.every(p => p >= 0) && positions.every((p, i) => i === 0 || p === positions[i - 1] + 1);
    targetValid &&= positions.every((p, i) => JSON.stringify(units[p]) === JSON.stringify(proposal.preimage[i]));
    if (proposal.kind === 'insert') targetValid &&= proposal.left === null ? units.length === 0 : ids.includes(proposal.left);
    targetValid &&= proposal.units.every(u => !ids.includes(u.id));
    let outcome = request.decision === 'reject' ? 'RejectedSuggestion' : 'AcceptedSuggestion';
    if (request.decision === 'accept' && !targetValid) outcome = 'Conflicted';
    const receipt = {outcome, signature, actor: authenticatedActor, proposalId: request.proposalId};
    this.doc.transact(() => {
      if (outcome === 'AcceptedSuggestion') {
        const body = this.doc.getArray('body');
        const at = proposal.kind === 'insert' ? (proposal.left === null ? 0 : ids.indexOf(proposal.left) + 1) : positions[0];
        if (proposal.kind === 'format') {
          for (const pos of positions) body.get(pos).set('style', proposal.style);
        } else {
          if (positions.length) body.delete(at, positions.length);
          if (proposal.units.length) body.insert(at, proposal.units.map(atom));
        }
      }
      this.proposals.set(request.proposalId, {...proposal,
        state: outcome === 'AcceptedSuggestion' ? 'accepted' : outcome === 'RejectedSuggestion' ? 'rejected' : 'conflicted',
        terminalBy: authenticatedActor, terminalOperation: request.operationId});
      this.receipts.set(request.operationId, receipt);
    }, 'admission');
    return receipt;
  }

  propose(actor, proposal) {
    if (this.roles[actor] !== 'editor') return {outcome: 'RejectedPermission'};
    if (this.proposals.has(proposal.id)) return {outcome: 'RejectedDuplicateProposal'};
    if (!['replace', 'delete', 'insert', 'format'].includes(proposal.kind)) return {outcome: 'RejectedUnsupportedProposal'};
    if (!Array.isArray(proposal.targetIds) || !Array.isArray(proposal.units) || !proposal.units.every(validUnit) ||
        new Set(proposal.targetIds).size !== proposal.targetIds.length || !Array.isArray(proposal.resources) ||
        !proposal.resources.every(r => typeof r === 'string') ||
        (proposal.kind === 'format' && (!Number.isInteger(proposal.style) || proposal.style < 0 || proposal.style > 3 || proposal.units.length !== 0)) ||
        (proposal.kind !== 'insert' && proposal.targetIds.length === 0) ||
        (proposal.kind === 'delete' && proposal.units.length !== 0)) return {outcome: 'RejectedSchema'};
    const ids = new Set(this.units.map(u => u.id));
    if (!proposal.targetIds.every(id => ids.has(id))) return {outcome: 'RejectedTarget'};
    if (proposal.kind === 'insert' && proposal.targetIds.length !== 0) return {outcome: 'RejectedTarget'};
    if (proposal.units.some(u => ids.has(u.id)) || new Set(proposal.units.map(u => u.id)).size !== proposal.units.length) return {outcome: 'RejectedDuplicateUnit'};
    const preimage = this.units.filter(u => proposal.targetIds.includes(u.id));
    const left = proposal.kind === 'insert' ? this.units.at(-1)?.id ?? null : null;
    this.doc.transact(() => this.proposals.set(proposal.id, {...structuredClone(proposal), preimage, left,
      state: 'pending', author: actor}), 'proposal');
    return {outcome: 'Proposed'};
  }
}
