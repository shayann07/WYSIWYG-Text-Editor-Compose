// Private C0 experiment model. Not a production or frozen library API.
import assert from 'node:assert/strict';
import * as T from '../../codex/run-20261016-20/document-tree.mjs';
import {textBlock, container, validateTree} from '../../codex/run-20261016-20/structural-model.mjs';
import {repairedNormalize, repairedUnitStops} from '../../codex/run-20261016-20/document-model.mjs';
const clone = structuredClone;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const p = (id, text) => textBlock(id, text);
function initial() {
  return {main: T.makeDocument(container('body', 'flow', [
    container('list', 'list', [container('item', 'item', [p('p', 'کتاب قلم')])], {kind: 'bullet'}),
    p('tail', 'English tail'),
    {id: 'table', type: 'object', props: {kind: 'table', rows: [{id: 'row', cells: [
      {id: 'cell-a', flow: container('cell-flow-a', 'flow', [p('cell-p-a', 'اردو'), p('cell-p-a2', 'دوسرا')])},
      {id: 'cell-b', flow: container('cell-flow-b', 'flow', [p('cell-p-b', 'English')])}
    ]}]}}
  ])), auxiliary: {header: container('header', 'flow', [p('header-p', 'سرورق')]), footer: container('footer', 'flow', [p('footer-p', 'صفحہ')])},
    comments: [{id: 'comment', flow: 'body', units: ['p:0'], context: 'ک', state: 'attached'}]};
}
function flow(bundle, id) {return bundle.auxiliary[id] ?? T.getFlow(bundle.main, id);}
function locations(bundle) {
  const out = new Map();
  for (const [id, {flow: f}] of T.flows(bundle.main)) for (const [nodeId, value] of T.index(f)) out.set(nodeId, {...value, flow: id});
  for (const [id, f] of Object.entries(bundle.auxiliary)) for (const [nodeId, value] of T.index(f)) {assert(!out.has(nodeId)); out.set(nodeId, {...value, flow: id});}
  return out;
}
function units(bundle) {
  const out = new Map();
  for (const {node, flow: flowId} of locations(bundle).values()) for (const [index, unit] of (node.units ?? []).entries()) {
    assert(!out.has(unit.tokenId)); out.set(unit.tokenId, {unit, node, index, flow: flowId});
  }
  return out;
}
function walkStructural(node, visit) {
  visit(node);
  for (const child of node.children ?? []) walkStructural(child, visit);
  if (T.isTable(node)) for (const row of node.props.rows) for (const cell of row.cells) walkStructural(cell.flow, visit);
}
function referencedResources(value, out = new Set()) {
  if (!value || typeof value !== 'object') return out;
  for (const [key, child] of Object.entries(value)) {
    if (key === 'resourceId' && typeof child === 'string') out.add(child);
    else referencedResources(child, out);
  }
  return out;
}
function validate(bundle) {
  T.validateDocument(bundle.main);
  for (const f of Object.values(bundle.auxiliary)) validateTree(f);
  locations(bundle); const all = units(bundle);
  for (const comment of bundle.comments) {
    let present = true; try {flow(bundle, comment.flow);} catch {present = false;}
    const missing = !present || comment.units.some(id => !all.has(id) || all.get(id).flow !== comment.flow);
    assert.equal(comment.state, missing ? 'orphaned' : 'attached');
    if (missing) assert(comment.context && comment.units.length);
  }
}
function text(bundle, id = 'body') {return T.raw(T.project(flow(bundle, id)));}
const metadata = node => clone(Object.fromEntries(Object.entries(node).filter(([key]) => key !== 'units' && key !== 'children')));
function branchPath(ix, item, block) {
  const result = [];
  for (let current = ix.get(block); current; current = ix.get(current.parent)) {
    result.push(current.node);
    if (current.node.id === item) return result.reverse();
  }
  throw Error('ListAncestryConflict');
}
function patches(before, after, at = []) {
  if (same(before, after)) return [];
  if (Array.isArray(before) && Array.isArray(after)) {
    const identity = value => value && typeof value === 'object' ? value.id ?? value.tokenId : null;
    if (before.length === after.length && before.every((value, i) => identity(value) !== null && identity(value) === identity(after[i]))) {
      return before.flatMap((value, i) => patches(value, after[i], [...at, i]));
    }
    let prefix = 0, suffix = 0;
    while (prefix < Math.min(before.length, after.length) && same(before[prefix], after[prefix])) prefix++;
    while (suffix < Math.min(before.length, after.length) - prefix && same(before[before.length - 1 - suffix], after[after.length - 1 - suffix])) suffix++;
    return [{kind: 'splice', path: at, start: prefix, remove: before.length - prefix - suffix, insert: clone(after.slice(prefix, after.length - suffix))}];
  }
  if (before && after && typeof before === 'object' && typeof after === 'object') {
    const result = [];
    for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
      if (!Object.hasOwn(after, key)) result.push({kind: 'delete', path: [...at, key]});
      else if (!Object.hasOwn(before, key)) result.push({kind: 'set', path: [...at, key], value: clone(after[key])});
      else result.push(...patches(before[key], after[key], [...at, key]));
    }
    return result;
  }
  assert(at.length > 0, 'No whole-document replacement packet');
  return [{kind: 'set', path: at, value: clone(after)}];
}
function applyPacket(bundle, packet) {
  const work = clone(bundle);
  for (const patch of packet.patches) {
    assert(patch.path.length > 0);
    let parent = work;
    for (const key of patch.path.slice(0, -1)) parent = parent[key];
    const key = patch.path.at(-1);
    if (patch.kind === 'splice') parent[key].splice(patch.start, patch.remove, ...clone(patch.insert));
    else if (patch.kind === 'delete') delete parent[key];
    else parent[key] = clone(patch.value);
  }
  validate(work); return work;
}
class Replica {
  constructor(bundle, {maxEntries = 256, maxBytes = 8 * 1024 * 1024} = {}) {
    validate(bundle);
    assert(Number.isSafeInteger(maxEntries) && maxEntries > 0 && Number.isSafeInteger(maxBytes) && maxBytes > 0);
    this.bundle = clone(bundle); this.sequence = 0; this.pending = new Map(); this.compositionLease = null;
    this.maxEntries = maxEntries; this.maxBytes = maxBytes; this.pendingBytes = 0; this.resyncFrontier = null;
  }
  beginComposition(flowId, epoch) {assert(!this.compositionLease); flow(this.bundle, flowId); this.compositionLease = {flow: flowId, epoch};}
  releaseComposition(flowId, nativeFrame, boundary = null) {
    if (!this.compositionLease || this.compositionLease.flow !== flowId || nativeFrame.marked !== null || nativeFrame.epoch <= this.compositionLease.epoch ||
        !boundary?.verified || boundary.oldEpoch !== this.compositionLease.epoch ||
        !['terminal-callback', 'qualified-retirement'].includes(boundary.kind))
      return 'RejectedNativeBoundary';
    this.compositionLease = null; this.drain(); return this.resyncFrontier === null ? 'Released' : 'ReleasedNeedsResynchronization';
  }
  drain() {
    if (this.compositionLease || this.resyncFrontier !== null) return;
    while (this.pending.has(this.sequence + 1)) {
      const next = this.pending.get(this.sequence + 1);
      try {this.bundle = applyPacket(this.bundle, next);} catch {this.resyncFrontier = next.sequence; return;}
      this.sequence++;
      this.pending.delete(this.sequence); this.pendingBytes -= Buffer.byteLength(JSON.stringify(next), 'utf8');
    }
  }
  receive(packet) {
    if (!Number.isSafeInteger(packet?.sequence) || packet.sequence <= 0) return 'RejectedPacket';
    if (packet.sequence <= this.sequence) return 'Duplicate';
    if (this.resyncFrontier !== null) {this.resyncFrontier = Math.max(this.resyncFrontier, packet.sequence); return 'NeedsResynchronization';}
    const previous = this.pending.get(packet.sequence);
    if (previous) {
      if (!same(previous, packet)) {this.resyncFrontier = packet.sequence; return 'RejectedConflictingPacket';}
      return 'Duplicate';
    }
    const size = Buffer.byteLength(JSON.stringify(packet), 'utf8');
    // The missing head can make progress without occupying an extra queue slot.
    if (!this.compositionLease && packet.sequence === this.sequence + 1 && size <= this.maxBytes) {
      try {this.bundle = applyPacket(this.bundle, packet);} catch {this.resyncFrontier = packet.sequence; return 'RejectedPacket';}
      this.sequence++; this.drain(); return this.resyncFrontier !== null ? 'NeedsResynchronization' : this.pending.size ? 'DeferredSequenceGap' : 'Applied';
    }
    if (this.pending.size >= this.maxEntries || this.pendingBytes + size > this.maxBytes) {
      this.resyncFrontier = packet.sequence; return 'NeedsResynchronization';
    }
    this.pending.set(packet.sequence, clone(packet));
    this.pendingBytes += size;
    if (this.compositionLease) return 'DeferredComposition';
    this.drain();
    return this.resyncFrontier !== null ? 'NeedsResynchronization' : this.pending.size ? 'DeferredSequenceGap' : 'Applied';
  }
  resynchronize(snapshot) {
    if (this.compositionLease) return 'DeferredComposition';
    if (!Number.isSafeInteger(snapshot?.sequence) || snapshot.sequence < this.sequence ||
        snapshot.sequence < (this.resyncFrontier ?? this.sequence)) return 'RejectedSnapshotFrontier';
    let work, sequence = snapshot.sequence;
    const remaining = new Map([...this.pending].filter(([n]) => n > sequence));
    try {
      validate(snapshot.bundle); work = clone(snapshot.bundle);
      while (remaining.has(sequence + 1)) {work = applyPacket(work, remaining.get(++sequence)); remaining.delete(sequence);}
    } catch {return 'RejectedSnapshot';}
    this.bundle = work; this.sequence = sequence; this.pending = remaining; this.resyncFrontier = null;
    this.pendingBytes = [...remaining.values()].reduce((sum, packet) => sum + Buffer.byteLength(JSON.stringify(packet), 'utf8'), 0);
    return remaining.size ? 'ResynchronizedWithGap' : 'Resynchronized';
  }
}
class Authority {
  constructor(bundle = initial()) {this.bundle = clone(bundle); validate(this.bundle); this.sequence = 0; this.serial = 0; this.history = []; this.packets = []; this.formatOwners = new Map(); this.moveOwners = new Map(); this.deletions = new Map(); this.retainedUnits = new Map(); this.aliases = new Map(); this.roles = {A: 'editor', B: 'editor'}; this.receipts = new Map(); this.groups = new Map();}
  beginGesture(actor, groupId, flowId, beforeSelection = null, producer = null) {
    if (this.roles[actor] !== 'editor' || !groupId.startsWith(actor + ':') || this.groups.has(groupId)) return {outcome: 'RejectedGroup'};
    flow(this.bundle, flowId); this.groups.set(groupId, {actor, flow: flowId, open: true, beforeSelection: clone(beforeSelection), ...(producer === null ? {} : {producer})}); return {outcome: 'Opened'};
  }
  closeGesture(actor, groupId, afterSelection = null, producer = null) {
    const group = this.groups.get(groupId); if (!group || group.actor !== actor) return {outcome: 'RejectedGroup'};
    if ((group.producer !== undefined || producer !== null) && group.producer !== producer) return {outcome: 'RejectedProducerGroup'};
    if (!group.open) return {outcome: 'RejectedGroupClosed'};
    group.open = false; group.afterSelection = clone(afterSelection); return {outcome: 'Closed'};
  }
  submit(actor, operationId, operation, history = true, groupId = null, producer = null) {
    if (this.roles[actor] !== 'editor') return {outcome: 'RejectedPermission', retainedIntent: clone(operation)};
    if (history && producer === null && this.history.some(item => item.actor === actor && item.producer !== undefined))
      return {outcome: 'ProducerOriginRequired', retainedIntent: clone(operation)};
    if (!operationId.startsWith(actor + ':')) return {outcome: 'RejectedIdentity'};
    const previous = this.receipts.get(operationId);
    if (previous) return same(previous.operation, operation) ? {outcome: 'Duplicate'} : {outcome: 'RejectedReusedOperationId'};
    if (groupId != null) {
      const group = this.groups.get(groupId), ix = locations(this.bundle), existing = units(this.bundle);
      if (!group || group.actor !== actor || !group.open) return {outcome: 'RejectedGroupClosed'};
      if ((group.producer !== undefined || producer !== null) && group.producer !== producer) return {outcome: 'RejectedProducerGroup', retainedIntent: clone(operation)};
      const commands = operation.kind === 'batch' ? operation.operations : [operation];
      const ownsNode = id => ix.get(id)?.flow === group.flow;
      const ownsSubtree = id => {
        const entry = ix.get(id); if (!entry || entry.flow !== group.flow) return false;
        let owned = true; walkStructural(entry.node, node => {if (ix.get(node.id)?.flow !== group.flow) owned = false;}); return owned;
      };
      function ownsCommand(command) {
        switch (command?.kind) {
          case 'insert': return ownsNode(command.node) || (aliasesForScope.get(command.node)?.flow === group.flow && existing.get(command.right)?.flow === group.flow);
          case 'remove-owned': case 'format': return Array.isArray(command.ids) && command.ids.length > 0 && command.ids.every(id => existing.get(id)?.flow === group.flow);
          case 'split': case 'split-item': return ownsNode(command.node);
          case 'join': return ownsNode(command.left) && ownsNode(command.right);
          case 'join-items': return [command.leftItem, command.rightItem, command.leftBlock, command.rightBlock].every(ownsSubtree);
          case 'move': return ownsSubtree(command.node) && ownsNode(command.parent);
          case 'delete-node': return ownsSubtree(command.node);
          default: return false; // inverse-only/global/unknown commands cannot guess a gesture flow
        }
      }
      const aliasesForScope = this.aliases;
      if (!Array.isArray(commands) || commands.length === 0 || commands.length > 32 || !commands.every(ownsCommand))
        return {outcome: 'RejectedGroupScope', retainedIntent: clone(operation)};
    }
    const before = this.bundle, work = clone(before), owners = new Map(this.formatOwners), moves = new Map(this.moveOwners), deletions = new Map(this.deletions), aliases = new Map(this.aliases), allocate = kind => `${actor}:${kind}:${++this.serial}`;
    const retained = new Map([...this.retainedUnits].map(([id, value]) => [id, clone(value)]));
    let inverse, skippedFields = 0;
    try {
      const ix = locations(work), allUnits = units(work);
      if (operation.kind === 'batch') {
        if (!Array.isArray(operation.operations) || !operation.operations.length || operation.operations.length > 32 || operation.operations.some(op => op.kind === 'batch')) throw Error('InvalidAtomicBatch');
        const stage = new Authority(work);
        stage.serial = this.serial; stage.roles = {...this.roles}; stage.history = clone(this.history);
        stage.formatOwners = new Map(owners); stage.moveOwners = new Map(moves); stage.deletions = new Map(deletions); stage.aliases = new Map(aliases);
        stage.retainedUnits = new Map([...retained].map(([id, value]) => [id, clone(value)]));
        const inverseOperations = [];
        for (const [index, command] of operation.operations.entries()) {
          const result = stage.submit(actor, operationId + ':part:' + index, command, false);
          this.serial = Math.max(this.serial, stage.serial);
          if (result.outcome !== 'Applied') throw Error('AtomicBatchConflict:' + result.reason);
          if (result.inverse) {inverseOperations.unshift(clone(result.inverse)); stage.history.push({actor, operationId, inverse: clone(result.inverse)});}
        }
        Object.assign(work, clone(stage.bundle));
        for (const [destination, source] of [[owners, stage.formatOwners], [moves, stage.moveOwners], [deletions, stage.deletions], [aliases, stage.aliases], [retained, stage.retainedUnits]]) {
          destination.clear(); for (const [key, value] of source) destination.set(key, clone(value));
        }
        inverse = inverseOperations.length === operation.operations.length ? {kind: 'batch', operations: inverseOperations} : {kind: 'unsupported-safe-inverse', reason: 'Atomic child inverse unavailable'};
      } else if (operation.kind === 'insert') {
        let entry = ix.get(operation.node);
        if (!entry && aliases.has(operation.node) && operation.right != null) {
          const target = allUnits.get(operation.right);
          if (target && target.flow === aliases.get(operation.node).flow) entry = ix.get(target.node.id);
        }
        if (!entry?.node.units) throw Error('TargetDeleted');
        const list = entry.node.units;
        const at = operation.right == null ? list.length : list.findIndex(u => u.tokenId === operation.right);
        if (at < 0) throw Error('TargetDeleted');
        const inserted = T.textUnits(operation.text, allocate, operation.attrs ?? {});
        list.splice(at, 0, ...inserted);
        inverse = {kind: 'remove-owned', ids: inserted.map(u => u.tokenId)};
      } else if (operation.kind === 'remove-owned') {
        const removed = new Set(operation.ids), rows = [];
        for (const id of operation.ids) {
          const entry = allUnits.get(id);
          if (entry) rows.push({unit: clone(entry.unit), node: entry.node.id, flow: entry.flow,
            left: entry.node.units.slice(0, entry.index).findLast(u => !removed.has(u.tokenId))?.tokenId ?? null,
            right: entry.node.units.slice(entry.index + 1).find(u => !removed.has(u.tokenId))?.tokenId ?? null});
          if (entry) retained.set(id, clone(entry.unit));
          deletions.set(id, actor);
        }
        for (const id of operation.ids) {
          const entry = units(work).get(id);
          if (entry) entry.node.units.splice(entry.index, 1);
        }
        const rank = new Map([...allUnits.keys()].map((id, i) => [id, i]));
        rows.sort((a, b) => rank.get(a.unit.tokenId) - rank.get(b.unit.tokenId));
        inverse = {kind: 'restore-owned', rows};
      } else if (operation.kind === 'restore-owned') {
        const restoredAtGap = new Map();
        for (const row of operation.rows) {
          const current = units(work), currentNodes = locations(work);
          if (current.has(row.unit.tokenId) || (deletions.has(row.unit.tokenId) && deletions.get(row.unit.tokenId) !== actor)) throw Error('RestoreConflict');
          const right = row.right == null ? null : current.get(row.right), left = row.left == null ? null : current.get(row.left);
          const node = right?.node ?? left?.node ?? currentNodes.get(row.node)?.node;
          if (!node?.units || (right && right.flow !== row.flow) || (left && left.flow !== row.flow)) throw Error('RestoreConflict');
          const gap = JSON.stringify([row.flow, row.node, row.left, row.right]);
          const preceding = current.get(restoredAtGap.get(gap));
          const at = right ? right.index : preceding?.node === node ? preceding.index + 1 : left ? left.index + 1 : node.units.length;
          node.units.splice(at, 0, clone(retained.get(row.unit.tokenId) ?? row.unit)); deletions.delete(row.unit.tokenId); retained.delete(row.unit.tokenId);
          restoredAtGap.set(gap, row.unit.tokenId);
        }
        inverse = {kind: 'remove-owned', ids: operation.rows.map(r => r.unit.tokenId)};
      } else if (operation.kind === 'split') {
        const entry = ix.get(operation.node);
        if (!entry?.node.units) throw Error('TargetDeleted');
        const at = operation.right == null ? entry.node.units.length : entry.node.units.findIndex(u => u.tokenId === operation.right);
        if (at < 0) throw Error('TargetDeleted');
        if (!repairedUnitStops(entry.node.units).includes(at)) throw Error('InvalidSplitBoundary');
        const f = flow(work, entry.flow);
        const right = T.splitBlock(f, operation.node, at, allocate);
        inverse = {kind: 'join', left: operation.node, right};
      } else if (operation.kind === 'split-item') {
        const entry = ix.get(operation.node);
        if (!entry?.node.units) throw Error('TargetDeleted');
        const at = entry.node.units.findIndex(u => u.tokenId === operation.right);
        if (at < 0) throw Error('TargetDeleted');
        if (!repairedUnitStops(entry.node.units).includes(at)) throw Error('InvalidSplitBoundary');
        const leftItem = T.nearestItem(flow(work, entry.flow), operation.node);
        const result = T.splitItem(flow(work, entry.flow), operation.node, at, allocate);
        const rightItem = T.nearestItem(flow(work, entry.flow), result.rightId);
        const current = locations(work), leftPath = branchPath(current, leftItem, operation.node), rightPath = branchPath(current, rightItem, result.rightId);
        if (leftPath.length !== rightPath.length) throw Error('ListAncestryConflict');
        inverse = {kind: 'join-items', leftItem, rightItem, leftBlock: operation.node, rightBlock: result.rightId,
          paths: leftPath.map((node, i) => ({left: node.id, right: rightPath[i].id, rightMeta: metadata(rightPath[i])}))};
      } else if (operation.kind === 'join-items') {
        const left = ix.get(operation.leftItem), right = ix.get(operation.rightItem);
        if (!left || !right || left.parent !== right.parent || left.flow !== right.flow) throw Error('ListJoinConflict');
        const list = ix.get(left.parent).node;
        const at = list.children.findIndex(n => n.id === left.node.id);
        if (list.type !== 'list' || list.children[at + 1]?.id !== right.node.id) throw Error('ListJoinConflict');
        const leftPath = branchPath(ix, operation.leftItem, operation.leftBlock), rightPath = branchPath(ix, operation.rightItem, operation.rightBlock);
        if (!operation.paths || !same(leftPath.map(n => n.id), operation.paths.map(p => p.left)) || !same(rightPath.map(n => n.id), operation.paths.map(p => p.right))) throw Error('ListAncestryConflict');
        for (const pair of operation.paths) {
          const current = ix.get(pair.right).node;
          if (current.type !== pair.rightMeta.type || !same(current.props, pair.rightMeta.props)) throw Error('PeerPropertiesConflict');
        }
        const a = ix.get(operation.leftBlock).node, b = ix.get(operation.rightBlock).node;
        const restorePaths = operation.paths.map((pair, i) => ({...clone(pair), rightMeta: metadata(rightPath[i]), tail: rightPath[i].children?.slice(1).map(n => n.id) ?? []}));
        inverse = {kind: 'resplit-items', leftItem: operation.leftItem, rightItem: operation.rightItem, leftBlock: operation.leftBlock, rightBlock: operation.rightBlock,
          list: left.parent, paths: restorePaths, firstRight: b.units[0]?.tokenId ?? null, lastLeft: a.units.at(-1)?.tokenId ?? null};
        a.units.push(...b.units);
        for (let i = leftPath.length - 2; i >= 0; i--) {
          if (leftPath[i].children.at(-1)?.id !== leftPath[i + 1].id || rightPath[i].children[0]?.id !== rightPath[i + 1].id) throw Error('ListAncestryConflict');
          leftPath[i].children.push(...rightPath[i].children.slice(1));
        }
        list.children.splice(at + 1, 1);
        aliases.set(b.id, {target: a.id, flow: left.flow});
      } else if (operation.kind === 'resplit-items') {
        const left = ix.get(operation.leftItem), block = ix.get(operation.leftBlock);
        if (!left || !block?.node.units || left.parent !== operation.list || operation.paths.some(p => ix.has(p.right))) throw Error('ListRedoConflict');
        const currentPath = branchPath(ix, operation.leftItem, operation.leftBlock);
        if (!same(currentPath.map(n => n.id), operation.paths.map(p => p.left))) throw Error('ListAncestryConflict');
        const at = operation.firstRight != null ? block.node.units.findIndex(u => u.tokenId === operation.firstRight)
          : operation.lastLeft != null ? block.node.units.findIndex(u => u.tokenId === operation.lastLeft) + 1 : 0;
        if (at < 0 || (operation.firstRight == null && operation.lastLeft != null && at === 0)) throw Error('ListRedoConflict');
        if (!repairedUnitStops(block.node.units).includes(at)) throw Error('ListRedoBoundaryConflict');
        let rebuilt = {...clone(operation.paths.at(-1).rightMeta), units: block.node.units.splice(at)};
        for (let i = operation.paths.length - 2; i >= 0; i--) {
          const pair = operation.paths[i], parent = ix.get(pair.left).node;
          const child = parent.children.findIndex(n => n.id === operation.paths[i + 1].left);
          if (child < 0 || !same(parent.children.slice(child + 1).map(n => n.id), pair.tail)) throw Error('PeerAncestryConflict');
          const tail = parent.children.splice(child + 1);
          rebuilt = {...clone(pair.rightMeta), children: [rebuilt, ...tail]};
        }
        const list = ix.get(operation.list).node, index = list.children.findIndex(n => n.id === operation.leftItem);
        list.children.splice(index + 1, 0, rebuilt); aliases.delete(operation.rightBlock);
        inverse = {kind: 'join-items', leftItem: operation.leftItem, rightItem: operation.rightItem, leftBlock: operation.leftBlock, rightBlock: operation.rightBlock,
          paths: clone(operation.paths)};
      } else if (operation.kind === 'join') {
        const left = ix.get(operation.left), right = ix.get(operation.right);
        if (!left?.node.units || !right?.node.units || left.flow !== right.flow || left.parent !== right.parent) throw Error('JoinConflict');
        if (left.node.type !== right.node.type && !(['paragraph', 'heading'].includes(left.node.type) && ['paragraph', 'heading'].includes(right.node.type))) throw Error('IncompatibleJoin');
        const parent = ix.get(left.parent).node;
        const a = parent.children.findIndex(n => n.id === left.node.id), b = parent.children.findIndex(n => n.id === right.node.id);
        if (b !== a + 1) throw Error('JoinConflict');
        inverse = {kind: 'restore-split', left: left.node.id, right: right.node.id,
          firstRight: right.node.units[0]?.tokenId ?? null, lastLeft: left.node.units.at(-1)?.tokenId ?? null,
          rightType: right.node.type, rightProps: clone(right.node.props), rightEmpty: clone(right.node.emptyBlockAttributes)};
        left.node.units.push(...right.node.units); parent.children.splice(b, 1);
        aliases.set(right.node.id, {target: left.node.id, flow: left.flow});
      } else if (operation.kind === 'restore-split') {
        const left = ix.get(operation.left);
        if (!left?.node.units || ix.has(operation.right)) throw Error('SplitRestoreConflict');
        const at = operation.firstRight != null ? left.node.units.findIndex(u => u.tokenId === operation.firstRight)
          : operation.lastLeft != null ? left.node.units.findIndex(u => u.tokenId === operation.lastLeft) + 1 : 0;
        if (at < 0 || (operation.lastLeft != null && operation.firstRight == null && at === 0)) throw Error('SplitRestoreConflict');
        if (!repairedUnitStops(left.node.units).includes(at)) throw Error('SplitRestoreBoundaryConflict');
        T.splitBlock(flow(work, left.flow), left.node.id, at, () => operation.right, {rightType: operation.rightType, rightProps: operation.rightProps});
        const right = locations(work).get(operation.right).node;
        if (operation.rightEmpty !== undefined) right.emptyBlockAttributes = clone(operation.rightEmpty);
        aliases.delete(operation.right);
        inverse = {kind: 'join', left: operation.left, right: operation.right};
      } else if (operation.kind === 'move') {
        const entry = ix.get(operation.node), parent = ix.get(operation.parent);
        if (!entry || !parent?.node.children) throw Error('TargetDeleted');
        if (Object.hasOwn(operation, 'expectedOwner') && moves.get(operation.node) !== operation.expectedOwner) throw Error('MoveOwnershipConflict');
        if (entry.flow !== parent.flow || T.index(entry.node).has(operation.parent)) throw Error('InvalidMove');
        const oldParent = ix.get(entry.parent).node;
        const at = oldParent.children.findIndex(n => n.id === entry.node.id);
        const oldRight = oldParent.children[at + 1]?.id ?? null;
        oldParent.children.splice(at, 1);
        const target = operation.right == null ? parent.node.children.length : parent.node.children.findIndex(n => n.id === operation.right);
        if (target < 0) throw Error('TargetDeleted');
        parent.node.children.splice(target, 0, entry.node);
        const normalized = repairedNormalize(flow(work, entry.flow), allocate);
        if (work.auxiliary[entry.flow]) work.auxiliary[entry.flow] = normalized; else T.putFlow(work.main, entry.flow, normalized);
        inverse = {kind: 'move', node: entry.node.id, parent: oldParent.id, right: oldRight,
          expectedOwner: Object.hasOwn(operation, 'writeOwner') ? operation.writeOwner : operationId, writeOwner: moves.get(operation.node)};
        const nextOwner = Object.hasOwn(operation, 'writeOwner') ? operation.writeOwner : operationId;
        if (nextOwner === undefined) moves.delete(operation.node); else moves.set(operation.node, nextOwner);
      } else if (operation.kind === 'delete-node') {
        const entry = ix.get(operation.node);
        if (!entry || !entry.parent) throw Error('TargetDeleted');
        if (operation.expectedNode && !same(entry.node, operation.expectedNode)) throw Error('PeerSubtreeChanged');
        const parent = ix.get(entry.parent).node;
        const at = parent.children.findIndex(n => n.id === operation.node);
        let payload = clone(entry.node), placement = {parent: parent.id, left: parent.children[at - 1]?.id ?? null, right: parent.children[at + 1]?.id ?? null};
        const ancestors = [];
        for (let ancestor = ix.get(entry.parent); ancestor && ancestor.node.type !== 'flow'; ancestor = ix.get(ancestor.parent)) {
          const outer = ix.get(ancestor.parent).node, index = outer.children.findIndex(n => n.id === ancestor.node.id);
          ancestors.push({node: metadata(ancestor.node), placement: {parent: outer.id, left: outer.children[index - 1]?.id ?? null, right: outer.children[index + 1]?.id ?? null}});
        }
        parent.children.splice(at, 1);
        const normalized = repairedNormalize(flow(work, entry.flow), allocate);
        if (work.auxiliary[entry.flow]) work.auxiliary[entry.flow] = normalized; else T.putFlow(work.main, entry.flow, normalized);
        const remaining = locations(work), remainingUnits = units(work);
        for (const ancestor of ancestors) {
          if (remaining.has(ancestor.node.id)) break;
          payload = {...clone(ancestor.node), children: [payload]}; placement = ancestor.placement;
        }
        for (const [id, value] of allUnits) if (!remainingUnits.has(id)) {retained.set(id, clone(value.unit)); deletions.set(id, actor);}
        const landings = [...remaining].filter(([id]) => !ix.has(id)).map(([id, value]) => ({id, parent: value.parent, node: clone(value.node), moveOwner: moves.get(id)}));
        inverse = {kind: 'restore-node', flow: entry.flow, payload, placement, landings, resourceIds: [...referencedResources(payload)]};
      } else if (operation.kind === 'restore-node') {
        const parent = ix.get(operation.placement.parent);
        if (!parent?.node.children || parent.flow !== operation.flow) throw Error('RestoreParentChanged');
        const restored = clone(operation.payload);
        walkStructural(restored, node => {
          if (ix.has(node.id)) throw Error('RestoreIdentityConflict');
          for (let i = 0; i < (node.units?.length ?? 0); i++) {
            const id = node.units[i].tokenId;
            if (allUnits.has(id) || (deletions.has(id) && deletions.get(id) !== actor)) throw Error('RestoreOwnershipConflict');
            node.units[i] = clone(retained.get(id) ?? node.units[i]);
            retained.delete(id); deletions.delete(id);
          }
        });
        for (const landing of operation.landings) {
          const current = locations(work).get(landing.id);
          if (current && current.parent === landing.parent && moves.get(landing.id) === landing.moveOwner && same(current.node, landing.node)) {
            const container = locations(work).get(current.parent).node;
            container.children.splice(container.children.findIndex(n => n.id === landing.id), 1);
          }
        }
        const children = parent.node.children, right = children.findIndex(n => n.id === operation.placement.right), left = children.findIndex(n => n.id === operation.placement.left);
        if (operation.placement.left != null && operation.placement.right != null && right < 0 && left < 0) throw Error('RestoreGapChanged');
        // Null endpoints are stable parent-edge sentinels, not a current caret.
        const at = operation.placement.left == null ? 0 : operation.placement.right == null ? children.length : right >= 0 ? right : left + 1;
        children.splice(at, 0, restored);
        inverse = {kind: 'delete-node', node: restored.id, expectedNode: clone(restored)};
      } else if (operation.kind === 'remove-resource') {
        const refs = referencedResources(work), historyRefs = new Set(this.history.flatMap(h => [...referencedResources(h.inverse), ...referencedResources(h.redo)]));
        const explicitHistoryRefs = new Set(this.history.flatMap(h => h.inverse.resourceIds ?? []));
        const retainedRefs = referencedResources([...retained.values()]);
        if (refs.has(operation.resourceId) || historyRefs.has(operation.resourceId) || explicitHistoryRefs.has(operation.resourceId) || retainedRefs.has(operation.resourceId)) throw Error('ResourceRetained');
        if (!Object.hasOwn(work.main.props.resources, operation.resourceId)) throw Error('ResourceDeleted');
        delete work.main.props.resources[operation.resourceId];
      } else if (operation.kind === 'format') {
        const previousFields = [];
        for (const id of operation.ids) {
          const entry = allUnits.get(id);
          if (!entry) throw Error('TargetDeleted');
          for (const [key, value] of Object.entries(operation.patch)) {
            previousFields.push({id, key, had: Object.hasOwn(entry.unit.attrs, key), value: clone(entry.unit.attrs[key]), owner: owners.get(id + ':' + key)});
            entry.unit.attrs = T.patchAttrs(entry.unit.attrs, {[key]: value});
            owners.set(id + ':' + key, operationId);
          }
        }
        inverse = {kind: 'restore-fields', expectedOwner: operationId, fields: previousFields};
      } else if (operation.kind === 'restore-fields') {
        const redoFields = [];
        for (const field of operation.fields) {
          const unit = allUnits.get(field.id)?.unit ?? retained.get(field.id), key = field.id + ':' + field.key;
          if (!unit || owners.get(key) !== operation.expectedOwner) {skippedFields++; continue;}
          redoFields.push({id: field.id, key: field.key, had: Object.hasOwn(unit.attrs, field.key), value: clone(unit.attrs[field.key]), owner: owners.get(key), expectedOwner: field.owner});
          if (field.had) unit.attrs[field.key] = clone(field.value); else delete unit.attrs[field.key];
          if (field.owner === undefined) owners.delete(key); else owners.set(key, field.owner);
        }
        inverse = {kind: 'reapply-fields', fields: redoFields};
      } else if (operation.kind === 'reapply-fields') {
        const previousFields = [];
        for (const field of operation.fields) {
          const unit = allUnits.get(field.id)?.unit ?? retained.get(field.id), key = field.id + ':' + field.key;
          if (!unit || owners.get(key) !== field.expectedOwner) {skippedFields++; continue;}
          previousFields.push({id: field.id, key: field.key, had: Object.hasOwn(unit.attrs, field.key), value: clone(unit.attrs[field.key]), owner: owners.get(key)});
          if (field.had) unit.attrs[field.key] = clone(field.value); else delete unit.attrs[field.key];
          owners.set(key, operationId);
        }
        inverse = {kind: 'restore-fields', expectedOwner: operationId, fields: previousFields};
      } else throw Error('UnsupportedOperation');
      const surviving = units(work);
      for (const comment of work.comments) comment.state = comment.units.some(id => !surviving.has(id) || surviving.get(id).flow !== comment.flow) ? 'orphaned' : 'attached';
      validate(work);
    } catch (error) {return {outcome: 'Conflict', reason: error.message, retainedIntent: clone(operation)};}
    const packet = {sequence: this.sequence + 1, operationId, actor, patches: patches(before, work)};
    if (operation.kind === 'insert') assert(packet.patches.every(p => p.path.includes('units')), 'Typing must not synchronize a whole document/flow snapshot');
    assert.deepEqual(applyPacket(before, packet), work);
    this.bundle = work; this.formatOwners = owners; this.moveOwners = moves; this.deletions = deletions; this.retainedUnits = retained; this.aliases = aliases; this.sequence++;
    this.packets.push(packet); this.receipts.set(operationId, {operation: clone(operation), packet});
    if (history && inverse) {
      this.history.filter(x => x.actor === actor && (producer === null || x.producer === producer) && x.undone).forEach(x => {x.abandoned = true;});
      this.history.push({actor, operationId, groupId: groupId ?? operationId, inverse, ...(producer === null ? {} : {producer})});
    }
    return {outcome: 'Applied', packet, inverse, skippedFields, effect: skippedFields ? 'SupersededFieldsPreserved' : 'AppliedToCurrentBasis'};
  }
  undo(actor, operationId, producer = null) {
    if (producer === null && this.history.some(item => item.actor === actor && item.producer !== undefined)) return {outcome: 'ProducerOriginRequired'};
    const inScope = item => item.actor === actor && (producer === null || item.producer === producer);
    const item = this.history.findLast(x => inScope(x) && !x.undone);
    if (!item) return {outcome: 'NothingToUndo'};
    if (item.inverse.kind === 'unsupported-safe-inverse') return {outcome: 'UndoConflict', retainedInverse: clone(item.inverse)};
    const members = this.history.filter(x => inScope(x) && x.groupId === item.groupId && !x.undone);
    function flatten(inverse) {return inverse.kind === 'batch' ? inverse.operations.flatMap(flatten) : [inverse];}
    const operations = members.toReversed().flatMap(x => flatten(x.inverse));
    const result = this.submit(actor, operationId, operations.length === 1 ? operations[0] : {kind: 'batch', operations}, false);
    if (result.outcome === 'Applied') {members.forEach(x => {x.undone = true;}); item.redo = clone(result.inverse);}
    return result.outcome === 'Conflict' ? {...result, outcome: 'UndoConflict'} : {...result, selection: clone(this.groups.get(item.groupId)?.beforeSelection ?? null)};
  }
  redo(actor, operationId, producer = null) {
    if (producer === null && this.history.some(item => item.actor === actor && item.producer !== undefined)) return {outcome: 'ProducerOriginRequired'};
    const inScope = item => item.actor === actor && (producer === null || item.producer === producer);
    const item = this.history.findLast(x => inScope(x) && x.undone && !x.abandoned && x.redo);
    if (!item?.redo) return {outcome: 'NothingToRedo'};
    const result = this.submit(actor, operationId, item.redo, false);
    if (result.outcome === 'Applied') {item.undone = false; item.inverse = clone(result.inverse); this.history = this.history.filter(x => x === item || !inScope(x) || x.groupId !== item.groupId);}
    return result.outcome === 'Conflict' ? {...result, outcome: 'RedoConflict'} : {...result, selection: clone(this.groups.get(item.groupId)?.afterSelection ?? null)};
  }
  retentionSummary(resourceId) {
    const owners = new Set(this.history.filter(h => referencedResources(h.inverse).has(resourceId) || referencedResources(h.redo).has(resourceId) || h.inverse.resourceIds?.includes(resourceId)).map(h => h.actor));
    return {resourceId, historyOwners: [...owners].sort(), canonicalPayloadCopies: Object.hasOwn(this.bundle.main.props.resources, resourceId) ? 1 : 0};
  }
  discardOwnHistory(actor, producer = null) {
    if (producer === null && this.history.some(item => item.actor === actor && item.producer !== undefined)) return {outcome: 'ProducerOriginRequired'};
    this.history = this.history.filter(h => h.actor !== actor || (producer !== null && h.producer !== producer)); return {outcome: 'HistoryDiscarded'};
  }
}

export {Authority, Replica, initial, flow, text, units, locations, validate};
