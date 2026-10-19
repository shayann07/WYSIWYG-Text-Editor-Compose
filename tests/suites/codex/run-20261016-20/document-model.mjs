// Minimal general repairs for F01-F08 and F10A without modifying baseline files.
import {createHash} from 'node:crypto';
import {segmentText} from './unicode-grapheme.mjs';
// Adapted from Gemini's review; audited and corrected in run-20. Validation only.
import {DocumentModel as BaseDocumentModel, COMMANDS} from './document-model-base.mjs';
export {COMMANDS};
import {IntegratedModel, nativeTypes, equal, validRange, semantic} from './integrated-model.mjs';
import {StructuralModel, validateTree} from './structural-model.mjs';
import {immutableCopy, mapRange} from './request-contract.mjs';
import * as T from './document-tree.mjs';

const {
  clone, err, flows, getFlow, putFlow, locate, index, leaves, project, raw,
  isText, isTable, pointAt, pointOffset, mapPoint, validatePoint, validateDocument,
  leafRanges, patchAttrs, textUnits, paragraph, spliceFlow, splitBlock,
  nearestItem, liftItem, indentItem, splitItem, cellFlow, table, parseTSV
} = T;

const sessionOnly = new Set([
  'moveCaret', 'selectFlow', 'selectCells', 'selectNode', 'selectBlocks',
  'registerAnchor', 'moveAnchor', 'unregisterAnchor', 'capturePaste',
  'cancelPaste', 'cancelCommand', 'copy'
]);
const immediateWhileComposing = new Set([
  'registerAnchor', 'moveAnchor', 'unregisterAnchor',
  'capturePaste', 'cancelPaste', 'cancelCommand'
]);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const charge = v => Buffer.byteLength(JSON.stringify(v) ?? '', 'utf8');

// F06B: Include UnrepresentableObjectSeam, UnsupportedStructuralProjection, NotInList, FlowMismatch
export const repairedAdmittedError = e =>
  /^(Target|ReadOnly|FlowDeleted|FlowMismatch|NodeDeleted|NoPrevious|NoNext|NotInList|LastTable|Nothing|Selection|Invalid|Unsupported|UnrepresentableObjectSeam|Nested|Admission|TableAdmission)/.test(e?.message ?? '');

// F07A: Isolated UTF-16 surrogates are individual 1-unit navigation/deletion/formatting stops (GB4/GB5 Control-like hard boundaries),
// never merged with adjacent Extend/ZWJ combining marks by Intl.Segmenter.
export function isIsolatedSurrogateAt(str, i) {
  const code = str.charCodeAt(i);
  if (code >= 0xd800 && code <= 0xdbff) {
    const next = i + 1 < str.length ? str.charCodeAt(i + 1) : 0;
    return !(next >= 0xdc00 && next <= 0xdfff);
  }
  if (code >= 0xdc00 && code <= 0xdfff) {
    const prev = i > 0 ? str.charCodeAt(i - 1) : 0;
    return !(prev >= 0xd800 && prev <= 0xdbff);
  }
  return false;
}

export function repairedUnitClusters(units, granularity = 'grapheme') {
  const clusters = [];
  let i = 0;
  while (i < units.length) {
    const u = units[i];
    if (u.kind === 'inlineAtom' || u.kind === 'blockAtom' || u.kind === 'boundary') {
      clusters.push([i, i + 1]);
      i++;
      continue;
    }
    // Check if isolated surrogate in this text run
    let end = i;
    while (end < units.length && !['inlineAtom', 'blockAtom', 'boundary'].includes(units[end].kind)) {
      end++;
    }
    const runText = raw(units.slice(i, end));
    let subStart = 0;
    while (subStart < runText.length) {
      if (isIsolatedSurrogateAt(runText, subStart)) {
        clusters.push([i + subStart, i + subStart + 1]);
        subStart++;
        continue;
      }
      let subEnd = subStart;
      while (subEnd < runText.length && !isIsolatedSurrogateAt(runText, subEnd)) {
        subEnd++;
      }
      const cleanSlice = runText.slice(subStart, subEnd);
      for (const g of segmentText(cleanSlice, granularity)) {
        clusters.push([i + subStart + g.index, i + subStart + g.index + g.segment.length]);
      }
      subStart = subEnd;
    }
    i = end;
  }
  return clusters;
}

export function repairedUnitStops(units, granularity = 'grapheme') {
  const stops = new Set([0, units.length]);
  for (const [lo, hi] of repairedUnitClusters(units, granularity)) {
    stops.add(lo);
    stops.add(hi);
  }
  return [...stops].sort((a, b) => a - b);
}

export function repairedUserRange(flow, range, affinity = 'after') {
  const stops = new Set([0, project(flow).length]);
  for (const r of leafRanges(flow)) {
    stops.add(r.start);
    stops.add(r.end);
    if (!isText(r.node)) continue;
    for (const s of repairedUnitStops(r.node.units, 'grapheme')) {
      stops.add(r.start + s);
    }
  }
  const all = [...stops].sort((a, b) => a - b);
  const floor = x => all.filter(p => p <= x).at(-1) ?? 0;
  const ceil = x => all.find(p => p >= x) ?? all.at(-1) ?? 0;
  return range[0] === range[1]
    ? [affinity === 'before' ? floor(range[0]) : ceil(range[0]), affinity === 'before' ? floor(range[0]) : ceil(range[0])]
    : [floor(Math.min(...range)), ceil(Math.max(...range))];
}

// F07D & F07E: Repaired normalize, cloneFresh, closedSlice
export function repairedNormalize(root, allocate) {
  function prune(n) {
    if (!n.children) return n;
    n.children = n.children.map(prune).filter(Boolean);
    return n.children.length ? n : null;
  }
  const p = prune(root);
  if (!p) {
    root.children = [paragraph(allocate)];
    return root;
  }
  if (!leaves(p).some(isText)) p.children.push(paragraph(allocate));
  return p;
}

export function repairedCloneFresh(node, allocate, links = new Map(), codes = new Map()) {
  const remapAttrs = attrs => {
    if (!attrs || typeof attrs !== 'object') return attrs;
    const out = clone(attrs);
    if (out.link?.id) {
      const id = out.link.id;
      if (!links.has(id)) links.set(id, allocate('link'));
      out.link = {...out.link, id: links.get(id)};
    }
    if (out.code?.id) {
      const id = out.code.id;
      if (!codes.has(id)) codes.set(id, allocate('codeRun'));
      out.code = {...out.code, id: codes.get(id)};
    }
    return out;
  };
  const n = clone(node);
  function visit(x) {
    x.id = allocate(x.type ?? 'node');
    if (x.emptyBlockAttributes) x.emptyBlockAttributes = remapAttrs(x.emptyBlockAttributes);
    if (x.units) {
      for (const u of x.units) {
        u.tokenId = allocate('unit');
        u.originSessionId = null;
        if (u.kind === 'inlineAtom') u.nodeId = allocate('atom');
        if (u.attrs) u.attrs = remapAttrs(u.attrs);
      }
    }
    for (const c of x.children ?? []) visit(c);
    if (isTable(x)) {
      for (const row of x.props.rows) {
        row.id = allocate('row');
        for (const cell of row.cells) {
          cell.id = allocate('cell');
          visit(cell.flow);
        }
      }
    }
  }
  visit(n);
  return n;
}

export function repairedClosedSlice(flow, a, b) {
  const rs = leafRanges(flow);
  const records = new Map();
  for (let idx = 0; idx < rs.length; idx++) {
    const r = rs[idx];
    if (isText(r.node)) {
      const lo = Math.max(0, a - r.start);
      const hi = Math.min(r.node.units.length, b - r.start);
      // F07E: An empty block at index 0 (r.start === 0) is covered when a === 0 && b > 0 (or b >= r.end + (idx < rs.length - 1 ? 1 : 0)).
      const emptyCovered =
        r.node.units.length === 0 &&
        ((idx > 0 && a <= r.start - 1 && b >= r.start) ||
         (idx === 0 && a === 0 && (b > 0 || rs.length === 1)));
      if (lo < hi || emptyCovered) {
        records.set(r.id, {...clone(r.node), units: clone(r.node.units.slice(lo, hi))});
      }
    } else if (a <= r.start && b >= r.end) {
      records.set(r.id, clone(r.node));
    }
  }
  const visit = n => {
    if (!n.children) return records.get(n.id) ?? null;
    const children = n.children.map(visit).filter(Boolean);
    return children.length ? {...clone(n), children} : null;
  };
  return visit(flow)?.children ?? [];
}

// F04: Repaired reconstruct that resolves group head identity when a block object AND its trailing boundary are deleted.
export function repairedReconstruct(source, units, seed = {}, fault = null) {
  const src = index(source);
  const order = leaves(source);
  const leafIndex = new Map(order.map((l, idx) => [l.id, idx]));
  // Track which source leaf each original unit belonged to
  const unitLeafOwner = new Map();
  for (const l of order) {
    if (l.type === 'object') {
      unitLeafOwner.set(`object:${l.id}`, l.id);
    } else {
      for (const u of l.units ?? []) unitLeafOwner.set(u.tokenId, l.id);
    }
  }

  const first = order[0];
  const groups = [{id: first.id, nextBoundaryId: null, units: []}];
  for (const u of units) {
    if (u.kind === 'boundary') {
      groups.at(-1).nextBoundaryId = u.rightId;
      groups.push({id: u.rightId, nextBoundaryId: null, units: []});
    } else {
      groups.at(-1).units.push(clone(u));
    }
  }

  // F04: If a group's provisional head `g.id` is a block object (`old.type === 'object'`),
  // check whether the block object itself was removed AND its trailing boundary in `source` was also removed.
  // - If the block object's trailing boundary survived (or the object was the last leaf and no later leaf was merged into `g`),
  //   then ONLY the block object's content was replaced while retaining its surrounding boundaries (§4.3 line 258): convert `g.id` to a Paragraph at `g.id`.
  // - If the block object's trailing boundary was deleted (merging `g` with a following leaf in `source`), the deleted block object
  //   cannot absorb the following leaf or destroy its identity/ancestry: advance `g.id` to the surviving merged leaf!
  for (const g of groups) {
    const old = src.get(g.id)?.node;
    if (old?.type === 'object') {
      const hasOwnObject = g.units.some(u => u.kind === 'blockAtom' && u.nodeId === g.id);
      if (!hasOwnObject) {
        const startIdx = leafIndex.get(g.id);
        const endIdx = g.nextBoundaryId != null ? leafIndex.get(g.nextBoundaryId) : order.length;
        // If endIdx > startIdx + 1, at least one trailing boundary after `g.id` was deleted into this group!
        if (endIdx > startIdx + 1) {
          const objUnits = g.units.filter(u => u.kind === 'blockAtom');
          if (objUnits.length === 1 && src.has(objUnits[0].nodeId)) {
            g.id = objUnits[0].nodeId;
          } else {
            // Find the surviving leaf owner among retained units, or the last merged leaf in [startIdx + 1, endIdx - 1]
            const retainedOwner = g.units.map(u => unitLeafOwner.get(u.tokenId)).find(id => id && id !== g.id);
            g.id = retainedOwner ?? order[endIdx - 1].id;
          }
        }
      }
    }
  }

  const textTypes = new Set(['paragraph', 'heading', 'code']);
  const records = new Map();
  for (const g of groups) {
    if (records.has(g.id) || !src.has(g.id)) throw Error('InvalidBoundaryProvenance');
    const old = src.get(g.id).node;
    const objects = g.units.filter(u => u.kind === 'blockAtom');
    if (objects.length) {
      if (g.units.length !== 1 || objects[0].nodeId !== g.id) throw Error('UnrepresentableObjectSeam');
      records.set(g.id, clone(old));
    } else {
      records.set(g.id, {
        id: g.id,
        type: old.type === 'code' && g.units.some(u => u.kind === 'inlineAtom')
          ? 'paragraph'
          : textTypes.has(old.type) ? old.type : 'paragraph',
        props: clone(
          old.type === 'code' && g.units.some(u => u.kind === 'inlineAtom')
            ? {}
            : textTypes.has(old.type) ? old.props : {}
        ),
        units: g.units,
        ...(old.emptyBlockAttributes ? {emptyBlockAttributes: clone(old.emptyBlockAttributes)} : {}),
        ...(!g.units.length && old.units?.length ? {emptyBlockAttributes: clone(seed)} : {})
      });
    }
  }

  const work = clone(source);
  let survivor = groups[0].id;
  for (const leaf of order) {
    if (records.has(leaf.id)) {
      survivor = leaf.id;
      continue;
    }
    const wi = index(work), left = wi.get(survivor), right = wi.get(leaf.id), lp = left?.parent, rp = right?.parent;
    if (lp !== rp && wi.get(lp)?.node.type === 'item' && wi.get(rp)?.node.type === 'item' && wi.get(lp).parent === wi.get(rp).parent) {
      const dest = wi.get(lp)?.node, from = wi.get(rp)?.node;
      if (dest && from) {
        const tail = from.children.filter(n => n.id !== leaf.id);
        dest.children.push(...tail);
        from.children = [];
      }
    }
  }
  function prune(n) {
    if (!n.children) return records.get(n.id) ?? null;
    const kids = n.children.map(prune).filter(Boolean);
    return kids.length ? {...n, children: kids} : null;
  }
  const result = prune(work);
  if (!result) throw Error('MissingLandingBlock');
  if (fault === 'reorder') {
    for (const n of index(result).values()) {
      if (n.node.children?.length > 1) {
        n.node.children.reverse();
        break;
      }
    }
  }
  validateTree(result);
  if (raw(project(result)) !== raw(units)) throw Error('ProjectionMismatch');
  return result;
}

export function repairedSpliceFlow(flow, a, b, units, seed = {}) {
  const tape = project(flow);
  if (a < 0 || b < a || b > tape.length) err('InvalidTargetRange');
  return repairedReconstruct(flow, [...tape.slice(0, a), ...units, ...tape.slice(b)], seed);
}

// Repaired StructuralModel using repairedReconstruct & atom/surrogate-safe grapheme boundaries
export class RepairedStructuralModel extends StructuralModel {
  committedTree() {
    return repairedReconstruct(this.source, this.committedDoc, this.seed ?? {});
  }
  _publishWorkingState() {
    super._publishWorkingState();
    if (this.source) {
      this._publishedState = immutableCopy({
        ...this._publishedState,
        structuralTree: repairedReconstruct(this.source, this._publishedState.units, this.seed ?? {})
      });
    }
  }
  _semanticStructureKey() {
    if (!this.source) return super._semanticStructureKey();
    return JSON.stringify(repairedReconstruct(this.source, this._getWorkingVisibleUnits(), this.seed ?? {}));
  }
}

const captureRange = (range, units, affinity = 'after') => {
  const a = Math.min(...range), b = Math.max(...range);
  return {
    ids: units.slice(a, b).map(u => u.tokenId),
    left: a ? units[a - 1].tokenId : null,
    right: b < units.length ? units[b].tokenId : null,
    collapsed: a === b,
    affinity
  };
};
const resolveRange = (c, units) => {
  const ranks = new Map(units.map((u, i) => [u.tokenId, i]));
  if (c.collapsed) {
    const left = c.left === null ? -1 : ranks.get(c.left);
    const right = c.right === null ? units.length : ranks.get(c.right);
    if (left === undefined && right === undefined) err('TargetDeleted');
    const p = c.affinity === 'before'
      ? (left === undefined ? right : left + 1)
      : (right === undefined ? left + 1 : right);
    return [p, p];
  }
  const p = c.ids.map(id => ranks.get(id));
  if (p.some(x => x === undefined)) err('TargetDeleted');
  if (p.some((x, i) => i && x !== p[i - 1] + 1)) err('TargetFragmented');
  return [p[0], p.at(-1) + 1];
};

export class DocumentModel extends BaseDocumentModel {
  constructor(document, selection = [0, 0], options = {}) {
    super(document, selection, options);
    this.kernel = new RepairedStructuralModel(getFlow(this.docBase, this.activeFlow), this.kernel.selection);
    this.committedAnchors = clone(this.anchors);
    this.sessionAnchorOrigins = new Map();
    this._publish();
  }

  _reduce(q){
    if(q.payload.type==='recoverDraft'){
      const d=q.payload.draft;if(!d||this.active||!equal(this._doc(),d.committed))err('RecoveryBaseChanged');
      validateDocument(d.visible);const before=this._bundle();const flowId=flows(d.visible).has(d.flowId)?d.flowId:d.visible.children[0].id;let selection=d.selection??[0,0];
      if(!validRange(selection,project(getFlow(d.visible,flowId)).length)){const p=pointOffset(getFlow(d.visible,flowId),pointAt(getFlow(d.visible,flowId),0));selection=[p,p];}
      this._install(clone(d.visible),{flowId,selection,kind:d.selectionKind??{type:'text'},intent:d.typingIntent??{mode:'inherit',side:'left'},mapAnnotations:false,checkProtected:false});
      this._historyPush(before,this._bundle(),'RecoveredDraft');return {commandStatus:'Applied'};
    }
    const out=super._reduce(q);if(q.payload.type==='dispose'){this.sessionAnchorOrigins.clear();this.committedAnchors.clear();}return out;
  }

  _save() {
    const s = super._save();
    s.committedAnchors = clone(this.committedAnchors);
    s.sessionAnchorOrigins = clone(this.sessionAnchorOrigins);
    return s;
  }

  _restore(s) {
    super._restore(s);
    if (s.committedAnchors) this.committedAnchors = clone(s.committedAnchors);
    if (s.sessionAnchorOrigins) this.sessionAnchorOrigins = clone(s.sessionAnchorOrigins);
  }

  // F08B: Unified pendingBytes charge across queued requests, pending commands, active paste leases, and retained clipboard.
  _totalPendingBytes(extraRequests = [], extraPending = [], extraPastes = [], extraClipboard = this.clipboard) {
    const reqBytes = [...this._queued(), ...extraRequests].reduce((n, r) => n + charge({payload: r.payload, context: r.context}), 0);
    const pendBytes = [...this.pending, ...extraPending].reduce((n, c) => n + charge(c), 0);
    const pasteBytes = [...(this.pastes?.values() ?? []), ...extraPastes].reduce((n, l) => n + charge(l), 0);
    const clipBytes = extraClipboard ? charge(extraClipboard) : 0;
    const originBytes=charge([...(this.sessionAnchorOrigins??new Map()).values()]);
    return reqBytes + pendBytes + pasteBytes + clipBytes + originBytes;
  }

  _checkCapacity(q) {
    super._checkCapacity(q);
    if (this._totalPendingBytes([q], [], []) > this.retention.pendingBytes) {
      err('AdmissionCapacityExceeded');
    }
  }

  _checkPendingCommand(q) {
    super._checkPendingCommand(q);
    if (this._totalPendingBytes([], [q], []) > this.retention.pendingBytes) {
      err('AdmissionCapacityExceeded');
    }
  }

  // F01: Project annotations and anchors deterministically from committed baseline (Dc, committedAnchors) -> Dv
  _projectAnnotationsFrom(baseDoc, targetDoc) {
    const copy = clone(targetDoc);
    copy.props.annotations = clone(baseDoc.props.annotations ?? []);
    this._mapAnnotations(baseDoc, copy);
    return copy.props.annotations;
  }

  _projectAnchorsFrom(baseAnchors, baseDoc, targetDoc) {
    const out = new Map();
    for (const [id, a] of baseAnchors) {
      const mapped = mapPoint(a.point, baseDoc, targetDoc, a.association);
      out.set(id, {...clone(a), point: mapped.point, deleted: !!a.deleted || mapped.deleted});
    }
    return out;
  }

  _install(doc, {
    flowId = this.activeFlow,
    selection = null,
    kind = {type: 'text'},
    intent = this.typingIntent,
    restoreAnchors = null,
    mapAnnotations = true,
    checkProtected = true
  } = {}) {
    const before = this._doc();
    if (checkProtected) this._protect(before, doc);
    if (mapAnnotations) this._mapAnnotations(before, doc);
    if (!flows(doc).has(flowId)) {
      const oldPoint = pointAt(getFlow(before, this.activeFlow), Math.min(...this.kernel.selection));
      const mapped = mapPoint(oldPoint, before, doc, 'after');
      flowId = mapped.point.flowId;
      const pos = pointOffset(getFlow(doc, flowId), mapped.point);
      selection = [pos, pos];
      kind = {type: 'text'};
    }
    if (selection == null) {
      const now = getFlow(doc, flowId);
      if (flowId === this.activeFlow) {
        const old = getFlow(before, flowId);
        // F05A: Map text selection endpoints to valid editable text points, never stranding a text caret on a blockAtom (\ufffc).
        selection = this.kernel.selection.map(x => {
          const rawOff = T.gapMap(project(old), project(now), x, 'after').offset;
          return kind?.type === 'text' ? pointOffset(now, pointAt(now, rawOff, 'after')) : rawOff;
        });
      } else {
        const startPos = kind?.type === 'text' ? pointOffset(now, pointAt(now, 0, 'after')) : 0;
        selection = [startPos, startPos];
      }
    } else if (kind?.type === 'text' && mapAnnotations) {
      const now = getFlow(doc, flowId);
      const tape = project(now);
      if (selection[0] === selection[1] && tape[selection[0]]?.kind === 'blockAtom') {
        const snapped = pointOffset(now, pointAt(now, selection[0], 'after'));
        selection = [snapped, snapped];
      }
    }
    if (kind?.type === 'cells') {
      const t = locate(doc, kind.tableId);
      try {
        if (!isTable(t?.node)) err('TargetTableDeleted');
        this._cells(t.node, kind);
      } catch {
        kind = {type: 'text'};
      }
    }
    if (kind?.type === 'node') {
      const e = locate(doc, kind.nodeId) ?? T.locateAtom(doc, kind.nodeId);
      if (!e) kind = {type: 'text'};
      else {
        const rs = leafRanges(e.flow), r = e.inline ? null : rs.find(x => x.id === e.node.id);
        if (e.inline) {
          const start = pointOffset(e.flow, {kind: 'text', blockId: e.block.id, offset: e.offset});
          flowId = e.flowId;
          selection = [start, start + 1];
        } else if (r) {
          flowId = e.flowId;
          selection = [r.start, r.end];
        } else kind = {type: 'text'};
      }
    }
    if (kind?.type === 'blocks') {
      const a = locate(doc, kind.first), b = locate(doc, kind.last);
      if (!a || !b || a.flowId !== b.flowId) kind = {type: 'text'};
    }
    validateDocument(doc);
    if (!validRange(selection, project(getFlow(doc, flowId)).length)) err('InvalidSelection');
    this._mapAnchors(before, doc, restoreAnchors);
    this.committedAnchors = clone(this.anchors);
    this.sessionAnchorOrigins = new Map();
    this.docBase = clone(doc);
    this.activeFlow = flowId;
    this.annotationView = clone(doc.props.annotations ?? []);
    this.cellPrelude = null;
    this.kernel = new RepairedStructuralModel(getFlow(doc, flowId), selection);
    this.selectionKind = clone(kind);
    this.typingIntent = clone(intent);
  }

  // F03: Recompute seed via _seed on retarget so exterior link/code rules, block-end rules, and typingIntent are preserved.
  _retargetChanged(target, force = false) {
    const old = this.kernel.markedRange ?? this.active.unmarkedExtent;
    if (force || !old || old[0] !== target.range[0] || old[1] !== target.range[1]) {
      const flowBeforeRetarget = getFlow(this._doc(), this.activeFlow);
      const seed = this._seed(flowBeforeRetarget, target.range);
      this._call({type: 'retarget', range: target.range});
      if (this.kernel.attributionEpoch) this.kernel.attributionEpoch.seed = clone(seed);
      this.kernel.seed = clone(seed);
      this.kernel._publishWorkingState();
      this.active.fence = null;
      this.active.unmarkedExtent = null;
    }
  }

  _call(p) {
    if (!this.docBase) return super._call(p);
    const before = this._doc(), beforeCommitted = this._doc(true), savedActive = this.active;
    if (['preedit', 'nativeRemoval'].includes(p.type) && this.active?.cellSelection && !this.cellPrelude) {
      const base = clone(this.docBase);
      this._clearCellFlows(base, this.active.cellSelection, this._allocate(this.executing));
      this.cellPrelude = {base, accepted: false};
      this.selectionKind = {type: 'text'};
    }
    if (['preedit', 'nativeRemoval'].includes(p.type)) this.selectionKind = {type: 'text'};

    // F03: If p.type === 'retarget', compute seed from DocumentModel._seed before applying retarget
    let retargetSeed = null;
    if (p.type === 'retarget' && p.range) {
      retargetSeed = this._seed(getFlow(before, this.activeFlow), p.range);
    }

    const r = IntegratedModel.prototype._call.call(this, p);

    if (retargetSeed && this.kernel.attributionEpoch) {
      this.kernel.attributionEpoch.seed = clone(retargetSeed);
      this.kernel.seed = clone(retargetSeed);
      this.kernel._publishWorkingState();
    }

    if (p.type === 'preedit' && this.active?.explicitLinkAtStart && this.kernel.provisionalOverlay) {
      let afterSpace = false;
      const prefix = `prov:${this.active.engineId}:e${this.kernel.attributionEpoch.epochId}:m`;
      for (const seg of this.kernel.provisionalOverlay.segments) {
        for (const u of seg.units) {
          if (u.originSessionId === this.active.engineId && /\s/u.test(u.char)) afterSpace = true;
          if (afterSpace && u.tokenId.startsWith(prefix)) delete u.attrs.link;
        }
      }
      this.kernel._publishWorkingState();
    }

    if (p.type === 'partialCommit' && p.length > 0 && this.cellPrelude) {
      this.cellPrelude.accepted = true;
      this.docBase = clone(this.cellPrelude.base);
    }
    if (p.type === 'cancel') this.cellPrelude = null;

    // F01: When partialCommit advances Dc, advance committed annotations and committedAnchors from beforeCommitted -> afterCommitted.
    if (!equal(beforeCommitted, this._doc(true))) {
      const accepted = this._doc(true);
      this.docBase.props.annotations = this._projectAnnotationsFrom(beforeCommitted, accepted);
      this.committedAnchors = this._projectAnchorsFrom(this.committedAnchors ?? this.anchors, beforeCommitted, accepted);
      if(p.type === 'partialCommit') this._endAcceptedLinkIntent();
    }

    const committedNow = this._doc(true);
    const afterVisible = this._doc(false);
    this._protect(before, afterVisible);

    // Project visible annotations and anchors deterministically from committed state (committedNow, committedAnchors) -> afterVisible!
    this.annotationView = this._projectAnnotationsFrom(committedNow, afterVisible);
    afterVisible.props.annotations = clone(this.annotationView);
    this.anchors = this._projectAnchorsFrom(this.committedAnchors ?? this.anchors, committedNow, afterVisible);
    this._projectExplicitSessionAnchors(afterVisible);

    if (p.type === 'cancel' && savedActive && equal(savedActive.before.document, committedNow)) {
      this.annotationView = clone(savedActive.before.document.props.annotations);
      this.selectionKind = clone(savedActive.before.selectionKind);
      this.typingIntent = clone(savedActive.before.typingIntent);
      // Handle lifetime/explicit relocation is not rolled back by composition cancellation.
      this.committedAnchors = clone(this.anchors);
    }
    return r;
  }

  _projectExplicitSessionAnchors(doc) {
    for(const [id,origin] of this.sessionAnchorOrigins ?? []) {
      if(!this.anchors.has(id))continue;
      const mapped=mapPoint(origin.anchor.point,origin.document,doc,origin.anchor.association);
      this.anchors.set(id,{...clone(origin.anchor),point:mapped.point,deleted:!!origin.anchor.deleted||mapped.deleted});
    }
  }

  _acceptVisible() {
    const beforeCommitted = this._doc(true);
    if (this.cellPrelude) {
      this.docBase = clone(this.cellPrelude.base);
      this.cellPrelude = null;
    }
    IntegratedModel.prototype._acceptVisible.call(this);
    if (this.docBase) {
      const afterCommitted = this._doc(true);
      this.docBase.props.annotations = this._projectAnnotationsFrom(beforeCommitted, afterCommitted);
      this.annotationView = clone(this.docBase.props.annotations);
      this.committedAnchors = this._projectAnchorsFrom(this.committedAnchors ?? this.anchors, beforeCommitted, afterCommitted);
      this.anchors = clone(this.committedAnchors);
      this._projectExplicitSessionAnchors(afterCommitted);
      this.committedAnchors = clone(this.anchors);
      this._endAcceptedLinkIntent();
    }
  }

  // F02A & F07A: Repaired _format
  _format(q, ids) {
    const p = q.payload, flowId = q.targetFlow ?? this.active?.flowId ?? this.activeFlow, doc = this._doc();
    if (!flows(doc).has(flowId)) return {status: 'Cancelled', reason: 'TargetFlowDeleted'};
    const target = p.target === 'composition' ? ids : q.targetIds;
    if (!q.cellTargets && (q.typingOnly || (p.target === 'composition' && !target?.length))) {
      // F02A: Only a collapsed-caret typingOnly format omits a ledger historySlot!
      // A composition-targeted format that settled to an empty candidate was admitted as a composition command
      // and MUST contribute a CommandWithoutHistory slot (historySlot: true) so a following queued Undo consumes it
      // rather than falling through and undoing the composition or earlier history!
      if (p.target === 'composition' && !target?.length) {
        return {status: 'Cancelled', reason: 'TargetDeleted', historySlot: true};
      }
      if (flowId !== this.activeFlow) return {status: 'Cancelled', reason: 'TargetFlowChanged', historySlot: false};
      const f = getFlow(doc, flowId), base = p.clearAll ? {} : this._seed(f, this.kernel.selection);
      this.typingIntent = {
        mode: 'explicit',
        base,
        patch: clone(p.patch),
        explicitLink: p.patch.link != null,
        explicitCode: p.patch.code != null
      };
      return {status: 'Applied', historySlot: false};
    }
    const targets = q.cellTargets ?? [{flowId, ids: target ?? []}];
    if (targets.some(t => !flows(doc).has(t.flowId))) return {status: 'Cancelled', reason: 'TargetFlowDeleted'};
    let found = false;
    for (const t of targets) {
      const f = getFlow(doc, t.flowId), wanted = new Set(t.ids);
      for (const leaf of leaves(f)) {
        if (leaf.units) {
          if (q.cellTargets && !leaf.units.length) {
            leaf.emptyBlockAttributes = p.clearAll ? {} : patchAttrs(leaf.emptyBlockAttributes, p.patch);
            found = true;
          }
          for (const [lo, hi] of repairedUnitClusters(leaf.units, 'grapheme')) {
            const clusterUnits = leaf.units.slice(lo, hi);
            if (clusterUnits.some(u => wanted.has(u.tokenId))) {
              found = true;
              for (const u of clusterUnits) {
                u.attrs = p.clearAll ? {} : patchAttrs(u.attrs, p.patch);
              }
            }
          }
        }
      }
    }
    if (!found) return {status: 'Cancelled', reason: 'TargetDeleted'};
    const before = this._bundle();
    if (equal(before.document, doc)) return {status: 'NoChange'};
    this._install(doc, {kind: this.selectionKind});
    const entry = this._historyPush(before, this._bundle(), 'Formatting');
    return {status: 'Applied', entry};
  }

  // F06A: Only bind cellSelection when no explicit range, point, or anchorHandle was supplied.
  _captureCommand(q) {
    const p = q.payload, b = this._basis(q), flowId = p.flowId ?? (p.anchorHandle ? this.anchors.get(p.anchorHandle)?.point.flowId : null) ?? b.flowId ?? this.activeFlow;
    const command = {
      ticket: q.ticket,
      payload: p,
      targetFlow: flowId,
      intent: clone(q.context?.typingIntent ?? {mode: 'inherit', side: 'left'})
    };
    if ((p.association != null && !['before', 'after'].includes(p.association)) ||
        (p.affinity != null && !['before', 'after'].includes(p.affinity))) {
      err('InvalidAssociation');
    }
    if (p.point && !['text', 'gap'].includes(p.point.kind)) err('InvalidPoint');
    if (p.point) command.point = this._resolvePoint(q, {...p.point, association: p.association ?? p.point.association ?? 'after'});
    if (b.flowId === flowId) {
      const range = p.range ?? b.selection;
      if (!validRange(range, b.units.length)) err('InvalidTargetRange');
      command.captured = captureRange(range, b.units, p.affinity ?? 'after');
      command.backwards = range[0] > range[1];
      if (p.command === 'moveCaret') {
        command.directional = range.map(pos => captureRange([pos, pos], b.units, p.affinity ?? 'after'));
      }
    }
    if(p.point || p.anchorHandle){
      const binding=p.anchorHandle?this.anchors.get(p.anchorHandle):null;
      if(p.anchorHandle&&(!binding||binding.deleted))err('TargetDeleted');
      const point=binding?.point??command.point;if(point.flowId!==flowId)err('FlowMismatch');
      const f=getFlow(this._doc(),flowId),offset=pointOffset(f,point);
      command.captured=captureRange([offset,offset],project(f),p.affinity??binding?.association??'after');
    }
    const implicitSelectionTarget = p.range == null && p.point == null && p.anchorHandle == null && (p.flowId == null || p.flowId === b.flowId);
    const activeCellSel = implicitSelectionTarget && b.selectionKind?.type === 'cells' ? clone(b.selectionKind) : null;
    if (p.command === 'capturePaste') {
      command.lease = {
        flowId,
        captured: clone(command.captured),
        selectionGeneration: b.selectionGeneration,
        intent: clone(command.intent),
        cellSelection: activeCellSel,
        cellTargets: activeCellSel ? b.cellTargets?.map(t => ({cellId: t.cellId, flowId: t.flowId, contentHash: t.contentHash})) : null
      };
    }
    if (p.command === 'typeText' || p.command === 'replaceSelection' || p.command.startsWith('paste') || ['deleteSelection', 'copy', 'cut'].includes(p.command)) {
      command.cellSelection = activeCellSel;
    }
    command.keepFocus = b.selectionGeneration !== this.selectionGeneration && !sessionOnly.has(p.command);
    return command;
  }

  _executePending(q, ids) {
    if (q.payload.type === 'format') return this._format(q, ids);
    const before = this._save();
    try {
      return this._executeCommand(q);
    } catch (e) {
      if (!repairedAdmittedError(e)) throw e;
      this._restore(before);
      return {status: 'Cancelled', reason: e.message, historySlot: !sessionOnly.has(q.payload.command)};
    }
  }

  // F07D: Validate and sanitize both unit attrs AND emptyBlockAttributes; reject unmapped external resourceIds and non-unit chars.
  _importResources(data, doc, allocate, resources = {}) {
    const map = new Map();
    for (const [id, value] of Object.entries(resources)) {
      if (!value || typeof value !== 'object' || /^(javascript:|vbscript:|data:text\/html)/i.test(value.uri ?? '')) {
        err('InvalidResource');
      }
      const fresh = allocate('resource');
      map.set(id, fresh);
      doc.props.resources[fresh] = clone(value);
    }
    const walk = v => {
      if (v && typeof v === 'object') {
        if (v.resourceId !== undefined) {
          if (!map.has(v.resourceId)) err('InvalidResourceReference');
          v.resourceId = map.get(v.resourceId);
        }
        Object.values(v).forEach(walk);
      }
    };
    walk(data);
    return data;
  }

  _sanitize(data) {
    let removedUnsafeLinks = 0, droppedAttributes = 0;
    const allowed = new Set(['bold', 'italic', 'underline', 'strike', 'color', 'font', 'fontSize', 'language', 'direction', 'link', 'code']);
    const cleanAttrMap = attrs => {
      if (!attrs || typeof attrs !== 'object') return;
      for (const key of Object.keys(attrs)) {
        if (!allowed.has(key)) {
          delete attrs[key];
          droppedAttributes++;
        } else if (key === 'link' && attrs.link !== false && !/^(https?:|mailto:)/i.test(attrs.link?.url ?? '')) {
          delete attrs.link;
          removedUnsafeLinks++;
        }
      }
    };
    const walk = v => {
      if (v && typeof v === 'object') {
        if (v.tokenId && typeof v.char === 'string') cleanAttrMap(v.attrs);
        if (v.emptyBlockAttributes && typeof v.emptyBlockAttributes === 'object') cleanAttrMap(v.emptyBlockAttributes);
        Object.values(v).forEach(walk);
      }
    };
    walk(data);
    return {removedUnsafeLinks, droppedAttributes};
  }

  _validateImportedUnits(nodeOrArray) {
    const walk = v => {
      if (!v || typeof v !== 'object') return;
      if (Array.isArray(v)) {
        v.forEach(walk);
        return;
      }
      if (Array.isArray(v.units)) {
        for (const u of v.units) {
          if (!u || !['text', 'inlineAtom', undefined].includes(u.kind) || typeof u.char !== 'string' || u.char.length !== 1) {
            err('InvalidRichFragment');
          }
          if (u.kind === 'inlineAtom' && u.char !== '\ufffc') err('InvalidRichFragment');
        }
      }
      if (Array.isArray(v.children)) v.children.forEach(walk);
      if (isTable(v) && Array.isArray(v.props?.rows)) {
        for (const r of v.props.rows) for (const c of r.cells ?? []) walk(c.flow);
      }
    };
    walk(nodeOrArray);
  }

  _executeCommand(q) {
    const p = q.payload, op = p.command, allocate = this._allocate(q), before = this._bundle(), doc = this._doc();
    let flow = getFlow(doc, q.targetFlow),
      range = this._commandRange(q, flow),
      nextFlow = this.activeFlow,
      selection = null,
      kind = clone(this.selectionKind),
      intent = clone((q.keepFocus || q.targetFlow !== this.activeFlow) && q.intent ? q.intent : this.typingIntent),
      result = {status: 'Applied'},
      historyKind = op,
      forceNoHistory = false;

    if (op === 'pasteRich' && ['units', 'blocks', 'cells'].filter(k => p[k] != null).length !== 1) {
      err('InvalidRichFragment');
    }
    if (['typeText', 'replaceSelection', 'deleteSelection', 'backspace', 'deleteForward', 'enter', 'softBreak', 'insertInlineAtom', 'insertBlockObject', 'insertTable', 'pasteText', 'pasteRich', 'copy', 'cut'].includes(op)) {
      range = repairedUserRange(flow, range, p.affinity ?? 'after');
    }
    const select = (id, pos, selKind = {type: 'text'}) => {
      nextFlow = id;
      selection = [pos, pos];
      kind = selKind;
    };
    const seed = () => this._seed(flow, range, intent);
    const replace = (text, units = null) => {
      const a = range[0], attrs = seed();
      let inserted = units ?? textUnits(text, allocate, attrs);
      if (intent.explicitLink) {
        let exited = false;
        for (const u of inserted) {
          if (/\s/u.test(u.char)) exited = true;
          if (exited) delete u.attrs.link;
        }
        if (exited) {
          intent.explicitLink = false;
          if (intent.patch) delete intent.patch.link;
          if (intent.base) delete intent.base.link;
        }
      }
      const next = repairedSpliceFlow(flow, a, range[1], inserted, attrs);
      putFlow(doc, flow.id, next);
      flow = next;
      range = [a + inserted.length, a + inserted.length];
      if (q.targetFlow === this.activeFlow) select(flow.id, range[0]);
      return inserted;
    };
    const at = () => {
      const point = pointAt(flow, range[0]);
      return {point, node: index(flow).get(point.blockId).node};
    };
    const clearSelectedCells = () => {
      const cells = this._clearCellFlows(doc, q.cellSelection ?? this.selectionKind, allocate);
      flow = cells[0].flow;
      range = [0, 0];
      select(flow.id, 0);
      return cells;
    };
    const mutateNode = () => {
      const entry = locate(doc, p.nodeId) ?? T.locateAtom(doc, p.nodeId);
      if (!entry) err('NodeDeleted');
      return entry;
    };
    const gap = p.anchorHandle ? this.anchors.get(p.anchorHandle)?.point : q.point;
    let enteredGap = false;
    if (gap?.kind === 'gap' && ['typeText', 'replaceSelection', 'enter', 'softBreak', 'pasteText', 'pasteRich', 'insertInlineAtom'].includes(op)) {
      const parent = index(flow).get(gap.parentId)?.node;
      if (!parent?.children) err('InvalidGap');
      const node = paragraph(allocate);
      parent.children.splice(gap.index, 0, parent.type === 'list' ? {id: allocate('item'), type: 'item', props: {}, children: [node]} : node);
      range = [
        pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: node.id, offset: 0}),
        pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: node.id, offset: 0})
      ];
      select(flow.id, range[0]);
      enteredGap = true;
    }

    if (op === 'registerAnchor') {
      if (this.anchors.size >= this.commandLimits.anchors) err('AdmissionCapacityExceeded');
      const point = q.point ?? pointAt(flow, range[0]), id = `anchor:${q.ticket}`;
      validatePoint(doc, point);
      const rec = {id, point: clone(point), association: p.association ?? 'after', generation: 0, deleted: false};
      this.anchors.set(id, rec);
      if(this.active)this.sessionAnchorOrigins.set(id,{anchor:clone(rec),document:clone(doc)});
      if(this._totalPendingBytes()>this.retention.pendingBytes)err('AdmissionCapacityExceeded');
      const commDoc = this._doc(true);
      const commMapped = mapPoint(point, doc, commDoc, rec.association);
      this.committedAnchors.set(id, {...rec, point: commMapped.point, deleted: commMapped.deleted});
      return {status: 'Applied', anchorId: id, historySlot: false};
    }
    if (op === 'moveAnchor') {
      const a = this.anchors.get(p.anchorId);
      if (!a) err('InvalidAnchor');
      const point = q.point ?? pointAt(flow, range[0]);
      validatePoint(doc, point);
      const rec = {...a, point: clone(point), association: p.association ?? a.association, generation: a.generation + 1, deleted: false};
      this.anchors.set(a.id, rec);
      if(this.active)this.sessionAnchorOrigins.set(a.id,{anchor:clone(rec),document:clone(doc)});
      if(this._totalPendingBytes()>this.retention.pendingBytes)err('AdmissionCapacityExceeded');
      const commDoc = this._doc(true);
      const commMapped = mapPoint(point, doc, commDoc, rec.association);
      this.committedAnchors.set(a.id, {...rec, point: commMapped.point, deleted: commMapped.deleted});
      return {status: 'Applied', historySlot: false};
    }
    if (op === 'unregisterAnchor') {
      if (!this.anchors.delete(p.anchorId)) err('InvalidAnchor');
      this.committedAnchors.delete(p.anchorId);
      this.sessionAnchorOrigins.delete(p.anchorId);
      return {status: 'Applied', historySlot: false};
    }
    if (op === 'capturePaste') {
      if (this.pastes.size >= this.commandLimits.pastes) err('AdmissionCapacityExceeded');
      const id = `paste:${q.ticket}`;
      if (this._totalPendingBytes([], [], [q.lease]) > this.retention.pendingBytes) err('AdmissionCapacityExceeded');
      this.pastes.set(id, clone(q.lease));
      return {status: 'Applied', pasteId: id, historySlot: false};
    }
    if (op === 'cancelPaste') {
      if (!this.pastes.delete(p.pasteId)) err('InvalidPasteLease');
      return {status: 'Applied', historySlot: false};
    }
    if (op === 'applyPaste') {
      // F02B: Preserve exact TargetDeleted / TargetFragmented / TargetChanged reason AND contribute a historySlot (historySlot: true)
      // so a following queued Undo consumes the cancelled paste attempt instead of undoing accepted native text!
      const lease = this.pastes.get(p.pasteId);
      if (!lease) err('InvalidPasteLease');
      this.pastes.delete(p.pasteId);
      if (!flows(doc).has(lease.flowId)) return {status: 'Cancelled', reason: 'TargetDeleted', historySlot: true};
      try {
        if (lease.cellSelection) {
          const t = locate(doc, lease.cellSelection.tableId);
          if (!isTable(t?.node)) err('TargetDeleted');
          const cells = this._cells(t.node, lease.cellSelection);
          if (!same(cells.map(c => c.id), lease.cellTargets.map(c => c.cellId))) err('TargetFragmented');
          if (cells.some((c, i) => createHash('sha256').update(semantic(c.flow)).digest('hex') !== lease.cellTargets[i].contentHash)) {
            err('TargetChanged');
          }
        } else {
          resolveRange(lease.captured, project(getFlow(doc, lease.flowId)));
        }
        return this._executeCommand({
          ...q,
          payload: {...p, command: p.blocks || p.cells || p.units ? 'pasteRich' : p.tsv ? 'pasteTSV' : 'pasteText', flowId: lease.flowId},
          targetFlow: lease.flowId,
          captured: clone(lease.captured),
          cellSelection: lease.cellSelection,
          intent: lease.intent,
          keepFocus: this.selectionGeneration !== lease.selectionGeneration
        });
      } catch (e) {
        if (/^Target/.test(e.message)) return {status: 'Cancelled', reason: e.message, historySlot: true};
        throw e;
      }
    }
    if (op === 'cancelCommand') {
      const k = this.pending.findIndex(x => x.ticket === p.ticket);
      if (k < 0 || this.pending[k].cancelReason) err('InvalidCommandTicket');
      const old = this.pending[k];
      this.pending[k] = {
        ticket: old.ticket,
        payload: {type: old.payload.type, command: old.payload.command},
        cancelReason: 'UserCancelled',
        outcomeEmitted: true,
        historySlot: !old.typingOnly && !sessionOnly.has(old.payload.command)
      };
      this._terminalOutcome(old.ticket, {status: 'Cancelled', reason: 'UserCancelled'});
      return {status: 'Applied', historySlot: false};
    }
    if (op === 'focusLost') {
      if (!p.qualified || p.epoch !== this.epoch || p.sessionId !== (this.active?.engineId ?? null)) err('InvalidFocusEvidence');
      if (this.active) {
        for (const command of this.pending) {
          if (command.payload.type === 'format' && command.payload.target === 'composition' && !command.cancelReason) {
            command.cancelReason = 'FocusLost';
          }
        }
        if (p.policy === 'cancel') this._finish('cancel', {type: 'abort'});
        else if (p.policy === 'preserve') this._finish('commit', {type: 'focusLost'});
        else err('InvalidFocusPolicy');
      }
      this.generation++;
      this.hostFence = false;
      return {status: 'Applied', historySlot: false};
    }

    if (op === 'copy' || op === 'cut') {
      if (!q.cellSelection && range[0] === range[1]) return {status: 'NoChange', historySlot: false};
      let fragment;
      if (q.cellSelection) {
        const tableEntry = locate(doc, q.cellSelection.tableId);
        if (!isTable(tableEntry?.node)) err('TargetTableDeleted');
        const tableNode = tableEntry.node, selected = this._cells(tableNode, q.cellSelection), wanted = new Set(selected.map(c => c.id));
        const cells = tableNode.props.rows.map(r => r.cells.filter(c => wanted.has(c.id)).map(x => clone(x))).filter(r => r.length);
        fragment = {type: 'cells', cells};
        if (op === 'cut') clearSelectedCells();
      } else {
        const units = clone(project(flow).slice(range[0], range[1]));
        fragment = {
          type: units.some(u => u.kind === 'boundary' || u.kind === 'blockAtom') ? 'rich' : 'inline',
          blocks: repairedClosedSlice(flow, range[0], range[1]),
          units,
          text: raw(units)
        };
      }
      const refs = new Set();
      const find = v => {
        if (v && typeof v === 'object') {
          if (v.resourceId) refs.add(v.resourceId);
          Object.values(v).forEach(find);
        }
      };
      find(fragment);
      fragment.resources = Object.fromEntries([...refs].map(id => [id, clone(doc.props.resources[id])]));
      fragment.id = `clipboard:${q.ticket}`;
      const candidateClip = immutableCopy(fragment);
      if (this._totalPendingBytes([], [], [], candidateClip) > this.retention.pendingBytes) err('AdmissionCapacityExceeded');
      this.clipboard = candidateClip;
      result.clipboardId = `clipboard:${q.ticket}`;
      if (op === 'copy') return {...result, historySlot: false};
      if (!q.cellSelection) replace('');
    } else if (op === 'selectFlow') {
      flow = getFlow(doc, p.toFlow);
      const rawOff = p.offset ?? 0;
      if (!Number.isSafeInteger(rawOff) || rawOff < 0 || rawOff > project(flow).length) err('InvalidSelection');
      const snapped = pointOffset(flow, pointAt(flow, rawOff, 'after'));
      select(flow.id, snapped);
      intent = {mode: 'inherit', side: 'left'};
      forceNoHistory = true;
    } else if (op === 'selectCells') {
      const t = locate(doc, p.tableId);
      if (!isTable(t?.node)) err('InvalidTable');
      kind = {type: 'cells', tableId: p.tableId, anchorCell: p.anchorCell, focusCell: p.focusCell};
      intent = {mode: 'inherit', side: 'right'};
      const cells = this._cells(t.node, kind);
      nextFlow = cells[0].flow.id;
      selection = [0, project(cells[0].flow).length];
      forceNoHistory = true;
    } else if (op === 'selectNode') {
      const e = mutateNode(), start = e.inline ? pointOffset(e.flow, {kind: 'text', blockId: e.block.id, offset: e.offset}) : null;
      const r = e.inline ? {start, end: start + 1} : leafRanges(e.flow).find(x => x.id === p.nodeId);
      if (!r) err('InvalidNodeSelection');
      nextFlow = e.flowId;
      selection = [r.start, r.end];
      kind = {type: 'node', nodeId: p.nodeId};
      forceNoHistory = true;
    } else if (op === 'selectBlocks') {
      const a = locate(doc, p.first), b = locate(doc, p.last);
      if (!a || !b || a.flowId !== b.flowId) err('InvalidBlockSelection');
      const rs = leafRanges(a.flow), start = rs.find(x => x.id === leaves(a.node)[0].id).start, end = rs.find(x => x.id === leaves(b.node).at(-1).id).end;
      nextFlow = a.flowId;
      selection = p.backwards ? [end, start] : [start, end];
      kind = {type: 'blocks', first: p.first, last: p.last};
      forceNoHistory = true;
    } else if (op === 'clearCells') {
      clearSelectedCells();
    } else if (op === 'typeText' || op === 'replaceSelection' || op === 'deleteSelection') {
      if (typeof p.text !== 'string' && op !== 'deleteSelection') err('InvalidText');
      if (q.cellSelection) clearSelectedCells();
      replace(op === 'deleteSelection' ? '' : p.text);
      historyKind = op === 'typeText' ? 'Typing' : op;
    } else if (op === 'softBreak') {
      if (range[0] !== range[1]) replace('');
      replace('\n');
    } else if (op === 'backspace' && gap?.kind === 'gap' && gap.index === 0 && index(flow).get(gap.parentId)?.node.type === 'item') {
      const first = leaves(index(flow).get(gap.parentId).node)[0].id;
      liftItem(flow, gap.parentId, allocate);
      putFlow(doc, flow.id, flow);
      select(flow.id, leafRanges(flow).find(r => r.id === first).start);
    } else if (op === 'backspace' || op === 'deleteForward') {
      if (range[0] !== range[1]) replace('');
      else {
        const {point, node} = at(), pos = point.offset;
        if (op === 'backspace' && pos === 0) {
          const item = nearestItem(flow, node.id), ix = index(flow), parent = ix.get(ix.get(node.id).parent).node;
          if (item && parent.id === item && parent.children[0].id === node.id) {
            liftItem(flow, item, allocate);
            putFlow(doc, flow.id, flow);
            select(flow.id, pointOffset(flow, {...point, offset: 0}));
          } else if (parent.type === 'quote' && leaves(parent)[0].id === node.id) {
            const outer = ix.get(ix.get(parent.id).parent).node, k = outer.children.findIndex(n => n.id === parent.id);
            outer.children.splice(k, 1, ...parent.children);
            select(flow.id, pointOffset(flow, point));
          } else {
            const rs = leafRanges(flow), i = rs.findIndex(x => x.id === node.id);
            if (i === 0) return {status: 'NoChange'};
            if (!isText(rs[i - 1].node)) err('SelectionRequired');
            if ((node.type === 'code') !== (rs[i - 1].node.type === 'code') || ix.get(rs[i - 1].id).parent !== ix.get(node.id).parent) {
              return {status: 'NoChange', reason: 'StructuralBoundary'};
            }
            range = [range[0] - 1, range[0]];
            replace('');
          }
        } else if (op === 'deleteForward' && pos === node.units.length) {
          const rs = leafRanges(flow), i = rs.findIndex(x => x.id === node.id), ix = index(flow);
          if (i === rs.length - 1) return {status: 'NoChange'};
          if (!isText(rs[i + 1].node)) err('SelectionRequired');
          const leftParent = ix.get(ix.get(node.id).parent), rightParent = ix.get(ix.get(rs[i + 1].id).parent);
          const siblings = leftParent.node.type === 'item' && rightParent.node.type === 'item' && leftParent.parent === rightParent.parent;
          if ((node.type === 'code') !== (rs[i + 1].node.type === 'code') || (leftParent.node.id !== rightParent.node.id && !siblings)) {
            return {status: 'NoChange', reason: 'StructuralBoundary'};
          }
          range = [range[0], range[0] + 1];
          replace('');
        } else {
          const stops = repairedUnitStops(node.units);
          const start = leafRanges(flow).find(x => x.id === node.id).start;
          range = op === 'backspace' ? [start + stops.filter(x => x < pos).at(-1), range[0]] : [range[0], start + stops.find(x => x > pos)];
          replace('');
        }
      }
    } else if (op === 'deleteCodePoints') {
      // F05B: Preflight both flanks for valid surrogate traversal AND protected atom/block/incompatible-container boundaries!
      if (!Number.isSafeInteger(p.before) || !Number.isSafeInteger(p.after) || p.before < 0 || p.after < 0) err('InvalidCodePointCount');
      const units = project(flow), text = raw(units), ix = index(flow), rs = leafRanges(flow);
      const canJoinBoundary = rightId => {
        const i = rs.findIndex(x => x.id === rightId);
        if (i <= 0 || !isText(rs[i - 1].node) || !isText(rs[i].node)) return false;
        const lp = ix.get(ix.get(rs[i - 1].id).parent), rp = ix.get(ix.get(rs[i].id).parent);
        const siblings = lp.node.type === 'item' && rp.node.type === 'item' && lp.parent === rp.parent;
        return (rs[i - 1].node.type === 'code') === (rs[i].node.type === 'code') && (lp.node.id === rp.node.id || siblings);
      };
      let a = range[0], b = range[1];
      for (let i = 0; i < p.before && a > 0; i++) {
        const unit = units[a - 1];
        if (unit.kind === 'blockAtom' || unit.kind === 'inlineAtom') err('SelectionRequired');
        if (unit.kind === 'boundary' && !canJoinBoundary(unit.rightId)) err('ProtectedStructuralBoundary');
        const u = text.charCodeAt(--a);
        if (u >= 0xdc00 && u <= 0xdfff) {
          if (a === 0 || text.charCodeAt(a - 1) < 0xd800 || text.charCodeAt(a - 1) > 0xdbff || units[a - 1].kind !== 'text') {
            err('InvalidSurrogateTraversal');
          }
          a--;
        } else if (u >= 0xd800 && u <= 0xdbff) {
          err('InvalidSurrogateTraversal');
        }
      }
      for (let i = 0; i < p.after && b < text.length; i++) {
        const unit = units[b];
        if (unit.kind === 'blockAtom' || unit.kind === 'inlineAtom') err('SelectionRequired');
        if (unit.kind === 'boundary' && !canJoinBoundary(unit.rightId)) err('ProtectedStructuralBoundary');
        const u = text.charCodeAt(b++);
        if (u >= 0xd800 && u <= 0xdbff) {
          if (b === text.length || text.charCodeAt(b) < 0xdc00 || text.charCodeAt(b) > 0xdfff || units[b].kind !== 'text') {
            err('InvalidSurrogateTraversal');
          }
          b++;
        } else if (u >= 0xdc00 && u <= 0xdfff) {
          err('InvalidSurrogateTraversal');
        }
      }
      const original = [...range], s = seed();
      const afterRight = repairedSpliceFlow(flow, original[1], b, [], s);
      const afterBoth = repairedSpliceFlow(afterRight, a, original[0], [], s);
      flow = afterBoth;
      putFlow(doc, flow.id, flow);
      range = [a, a + (original[1] - original[0])];
      if (flow.id === this.activeFlow) {
        nextFlow = flow.id;
        selection = q.backwards ? [range[1],range[0]] : range;
        kind = {type: 'text'};
      }
    } else if (op === 'enter') {
      if (enteredGap) {
        putFlow(doc, flow.id, flow);
      } else {
        const enterAttrs = seed();
        delete enterAttrs.link;
        delete enterAttrs.code;
        if (range[0] !== range[1]) replace('');
        const {point, node} = at(), itemId = nearestItem(flow, node.id), ix = index(flow), parent = ix.get(ix.get(node.id).parent).node;
        if (node.type === 'code') {
          replace('\n');
        } else if (parent.type === 'quote' && node.units.length === 0) {
          // F06C: Check immediate empty quote paragraph BEFORE nearestItem so nested quotes inside list items exit the quote level first!
          const outer = ix.get(ix.get(parent.id).parent).node, k = outer.children.findIndex(x => x.id === parent.id), n = parent.children.findIndex(x => x.id === node.id);
          const tail = parent.children.splice(n + 1);
          parent.children.splice(n, 1);
          const insert = [node];
          if (tail.length) insert.push({...clone(parent), id: allocate('quote'), children: tail});
          outer.children.splice(k + 1, 0, ...insert);
          repairedNormalize(flow, allocate);
          select(flow.id, pointOffset(flow, point));
        } else if (itemId) {
          const item = index(flow).get(itemId).node;
          if (item.children.length === 1 && item.children[0].id === node.id && node.units.length === 0) {
            liftItem(flow, itemId, allocate);
            select(flow.id, pointOffset(flow, point));
          } else {
            const out = splitItem(flow, node.id, point.offset, allocate);
            const right = index(flow).get(out.rightId).node;
            right.emptyBlockAttributes = seed();
            delete right.emptyBlockAttributes.link;
            delete right.emptyBlockAttributes.code;
            select(flow.id, pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: out.rightId, offset: 0}));
          }
        } else {
          const rightId = splitBlock(flow, node.id, point.offset, allocate, node.type === 'heading' && point.offset === node.units.length ? {rightType: 'paragraph', rightProps: {}} : {});
          const right = index(flow).get(rightId).node;
          right.emptyBlockAttributes = seed();
          delete right.emptyBlockAttributes.link;
          delete right.emptyBlockAttributes.code;
          select(flow.id, pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: rightId, offset: 0}));
        }
        putFlow(doc, flow.id, flow);
        if (node.type !== 'code') intent = {mode: 'explicit', base: enterAttrs, patch: {}, explicitLink: false, explicitCode: false};
      }
    } else if (op === 'moveCaret') {
      if (p.unit != null && !['word', 'grapheme'].includes(p.unit)) err('InvalidNavigationUnit');
      const directional = q.directional?.map(c => resolveRange(c, project(flow))[0]) ?? range;
      const focus = directional[1], point = pointAt(flow, focus), r = leafRanges(flow).find(x => x.id === point.blockId);
      let pos = focus;
      if (p.direction === 'blockStart') pos = r.start;
      else if (p.direction === 'blockEnd') pos = r.end;
      else if (['left', 'right'].includes(p.direction)) {
        const stops = leafRanges(flow).filter(r => isText(r.node)).flatMap(r => repairedUnitStops(r.node.units, p.unit ?? 'grapheme').map(i => r.start + i));
        const snapStop = (x, dir) => {
          if (stops.includes(x)) return x;
          return dir === 'left' ? (stops.filter(s => s <= x).at(-1) ?? x) : (stops.find(s => s >= x) ?? x);
        };
        if (!p.extend && directional[0] !== directional[1]) {
          // F06D: Collapse non-empty raw selection to a legal grapheme/word stop in the requested direction!
          const rawEdge = p.direction === 'left' ? Math.min(...directional) : Math.max(...directional);
          pos = snapStop(rawEdge, p.direction);
        } else {
          pos = p.direction === 'left' ? (stops.filter(x => x < focus).at(-1) ?? focus) : (stops.find(x => x > focus) ?? focus);
        }
      } else err('UnsupportedVisualNavigationWithoutLayout');
      nextFlow = flow.id;
      const anchorPos = p.extend ? repairedUserRange(flow, [directional[0], directional[0]], p.affinity ?? 'before')[0] : pos;
      selection = p.extend ? [anchorPos, pos] : [pos, pos];
      kind = {type: 'text'};
      forceNoHistory = true;
      intent = {mode: 'inherit', side: p.direction === 'right' ? 'right' : 'left'};
    } else if (op === 'setBlockType') {
      if (!['paragraph', 'heading', 'code'].includes(p.blockType)) err('InvalidBlockType');
      const targets = p.blockIds ?? leafRanges(flow).filter(x => x.end >= range[0] && x.start <= range[1] && isText(x.node)).map(x => x.id);
      for (const id of targets) {
        const e = locate(doc, id);
        if (!isText(e?.node)) err('InvalidBlockType');
        if (p.blockType === 'code' && e.node.units.some(u => u.kind === 'inlineAtom')) err('InvalidCodeAtom');
        e.node.type = p.blockType;
        delete e.node.props.level;
        delete e.node.props.language;
        if (p.blockType === 'heading') {
          if (!Number.isInteger(p.level) || p.level < 1 || p.level > 6) err('InvalidHeadingLevel');
          e.node.props.level = p.level;
        }
        if (p.blockType === 'code' && p.language) e.node.props.language = p.language;
      }
    } else if (op === 'patchNode') {
      const e = mutateNode();
      if (!p.patch || typeof p.patch !== 'object') err('InvalidPatch');
      if (Object.hasOwn(p.patch, 'rows') || Object.hasOwn(p.patch, 'kind')) err('InvalidStructuralPropertyPatch');
      e.node.props = {...e.node.props, ...clone(p.patch)};
    } else if (op === 'patchCell') {
      const t = locate(doc, p.tableId);
      if (!isTable(t?.node) || !p.patch || typeof p.patch !== 'object') err('InvalidCellPatch');
      const cell = t.node.props.rows.flatMap(r => r.cells).find(c => c.id === p.cellId);
      if (!cell) err('TargetCellDeleted');
      cell.props = {...cell.props, ...clone(p.patch)};
    } else if (op === 'wrapQuote' || op === 'wrapList') {
      // F06B: Reject empty or non-array blockIds cleanly as InvalidSiblingSelection!
      if (p.blockIds != null && (!Array.isArray(p.blockIds) || p.blockIds.length === 0)) err('InvalidSiblingSelection');
      const ids = p.blockIds ?? [at().node.id], entries = ids.map(id => locate(doc, id));
      if (!entries.length || entries.some(e => !e) || entries.some(e => e.flowId !== entries[0].flowId || e.parent !== entries[0].parent)) {
        err('InvalidSiblingSelection');
      }
      const parent = index(entries[0].flow).get(entries[0].parent).node;
      if (parent.type === 'list') err('InvalidParent');
      const positions = ids.map(id => parent.children.findIndex(n => n.id === id)).sort((a, b) => a - b);
      if (positions.some((x, i) => i && x !== positions[i - 1] + 1)) err('InvalidSiblingSelection');
      const chosen = parent.children.splice(positions[0], ids.length);
      const wrapped = op === 'wrapQuote'
        ? {id: allocate('quote'), type: 'quote', props: {}, children: chosen}
        : {
            id: allocate('list'),
            type: 'list',
            props: {kind: p.kind ?? 'bullet', ...(p.kind === 'ordered' ? {start: p.start ?? 1} : {})},
            children: chosen.map(n => ({id: allocate('item'), type: 'item', props: {}, children: [n]}))
          };
      parent.children.splice(positions[0], 0, wrapped);
      result.nodeId = wrapped.id;
    } else if (op === 'unwrapQuote' || op === 'unwrapList') {
      const e = mutateNode();
      if (e.node.type !== (op === 'unwrapQuote' ? 'quote' : 'list')) err('InvalidContainer');
      const parent = index(e.flow).get(e.parent).node, k = parent.children.findIndex(n => n.id === e.node.id);
      parent.children.splice(k, 1, ...(op === 'unwrapQuote' ? e.node.children : e.node.children.flatMap(i => i.children)));
    } else if (op === 'indent' || op === 'outdent') {
      const itemId = p.nodeId ?? nearestItem(flow, at().node.id);
      if (!itemId) err('InvalidListItem');
      if (op === 'indent') indentItem(flow, itemId, allocate);
      else liftItem(flow, itemId, allocate);
      putFlow(doc, flow.id, flow);
    } else if (op === 'moveNode') {
      const e = mutateNode(), dest = locate(doc, p.parentId);
      if (e.inline) {
        if (!isText(dest?.node) || dest.node.type === 'code' || !Number.isInteger(p.index) || p.index < 0 || p.index > dest.node.units.length) {
          err('InvalidInlineMove');
        }
        const moved = e.block.units.splice(e.offset, 1)[0];
        dest.node.units.splice(p.index - (e.block === dest.node && e.offset < p.index ? 1 : 0), 0, moved);
      } else {
        if (!dest?.node.children || !e.parent || e.node.id === p.parentId || index(e.node).has(p.parentId) || (dest.node.type === 'list') !== (e.node.type === 'item')) {
          err('InvalidMove');
        }
        if (flows(doc).get(dest.flowId).owner && leaves(e.node).some(isTable)) err('NestedTablesUnsupported');
        if (!Number.isInteger(p.index) || p.index < 0 || p.index > dest.node.children.length) err('InvalidMove');
        const from = index(e.flow).get(e.parent).node, old = from.children.findIndex(n => n.id === e.node.id);
        from.children.splice(old, 1);
        dest.node.children.splice(p.index - (from === dest.node && old < p.index ? 1 : 0), 0, e.node);
        putFlow(doc, e.flowId, repairedNormalize(e.flow, allocate));
      }
    } else if (op === 'deleteNode' || op === 'deleteTable') {
      const e = mutateNode();
      if (op === 'deleteTable' && !isTable(e.node)) err('InvalidTable');
      if (e.inline) e.block.units.splice(e.offset, 1);
      else {
        const parent = index(e.flow).get(e.parent)?.node;
        if (!parent) err('InvalidDeleteRoot');
        parent.children.splice(parent.children.findIndex(n => n.id === e.node.id), 1);
        putFlow(doc, e.flowId, repairedNormalize(e.flow, allocate));
      }
    } else if (op === 'insertInlineAtom') {
      const unit = {kind: 'inlineAtom', tokenId: allocate('unit'), nodeId: allocate('atom'), char: '\ufffc', attrs: seed(), props: clone(p.props ?? {}), originSessionId: null};
      replace('', [unit]);
      result.nodeId = unit.nodeId;
    } else if (op === 'insertBlockObject' || op === 'insertTable') {
      if (op === 'insertBlockObject' && (p.kind === 'table' || p.props?.kind === 'table')) err('InvalidObjectKind');
      if (op === 'insertTable' && flows(doc).get(flow.id).owner) err('NestedTablesUnsupported');
      if (range[0] !== range[1]) replace('');
      const {point, node} = at(), rightId = splitBlock(flow, node.id, point.offset, allocate, {rightType: 'paragraph', rightProps: {}});
      const ix = index(flow), parent = ix.get(ix.get(node.id).parent).node;
      const obj = op === 'insertTable' ? table(allocate, p.rows, p.columns) : {id: allocate('object'), type: 'object', props: {kind: p.kind ?? 'image', ...clone(p.props ?? {})}};
      parent.children.splice(parent.children.findIndex(n => n.id === rightId), 0, obj);
      putFlow(doc, flow.id, flow);
      result.nodeId = obj.id;
      if (isTable(obj)) {
        const cell = obj.props.rows[0].cells[0];
        select(cell.flow.id, 0);
        result.firstCellFlow = cell.flow.id;
      } else select(flow.id, pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: rightId, offset: 0}));
    } else if (['insertRow', 'deleteRow', 'insertColumn', 'deleteColumn'].includes(op)) {
      const e = mutateNode();
      if (!isTable(e.node)) err('InvalidTable');
      const rows = e.node.props.rows, cols = rows[0].cells.length, limit = op.endsWith('Row') ? rows.length : cols;
      if (!Number.isInteger(p.index) || p.index < 0 || p.index > (op.startsWith('insert') ? limit : limit - 1)) err('InvalidTableIndex');
      if (op === 'insertRow') {
        if ((rows.length + 1) * cols > this.commandLimits.cells) err('TableAdmissionLimit');
        rows.splice(p.index, 0, {id: allocate('row'), cells: Array.from({length: cols}, () => ({id: allocate('cell'), flow: cellFlow(allocate)}))});
      }
      if (op === 'deleteRow') {
        if (rows.length === 1) err('LastTableRow');
        rows.splice(p.index, 1);
      }
      if (op === 'insertColumn') {
        if (rows.length * (cols + 1) > this.commandLimits.cells) err('TableAdmissionLimit');
        for (const row of rows) row.cells.splice(p.index, 0, {id: allocate('cell'), flow: cellFlow(allocate)});
      }
      if (op === 'deleteColumn') {
        if (cols === 1) err('LastTableColumn');
        for (const row of rows) row.cells.splice(p.index, 1);
      }
    } else if (op === 'tableTab') {
      const owner = flows(doc).get(this.activeFlow).owner;
      if (!owner) err('InvalidCellContext');
      const t = locate(doc, owner.table), rows = t.node.props.rows, cols = rows[0].cells.length, n = owner.row * cols + owner.col + (p.reverse ? -1 : 1);
      if (n < 0) {
        const e = index(t.flow).get(t.node.id), parent = index(t.flow).get(e.parent).node, k = parent.children.findIndex(n => n.id === t.node.id);
        let dest = parent.children[k - 1];
        if (!isText(dest)) {
          dest = paragraph(allocate);
          parent.children.splice(k, 0, dest);
        }
        select(t.flowId, pointOffset(t.flow, {kind: 'text', flowId: t.flowId, blockId: dest.id, offset: dest.units.length}));
      } else {
        if (n === rows.length * cols) {
          if ((rows.length + 1) * cols > this.commandLimits.cells) err('TableAdmissionLimit');
          rows.push({id: allocate('row'), cells: Array.from({length: cols}, () => ({id: allocate('cell'), flow: cellFlow(allocate)}))});
        }
        select(rows[Math.floor(n / cols)].cells[n % cols].flow.id, 0);
      }
    } else if (op === 'pasteText') {
      if (q.cellSelection) clearSelectedCells();
      if (at().node.type === 'code') replace(p.text);
      else {
        if (typeof p.text !== 'string') err('InvalidText');
        const lines = p.text.replace(/\r\n?/g, '\n').split('\n');
        replace(lines[0]);
        for (let i = 1; i < lines.length; i++) {
          intent = {...intent, explicitLink: false, explicitCode: false};
          if (intent.base) {
            delete intent.base.link;
            delete intent.base.code;
          }
          if (intent.patch) {
            delete intent.patch.link;
            delete intent.patch.code;
          }
          const point = pointAt(flow, range[0]);
          const right = splitBlock(flow, point.blockId, point.offset, allocate, {rightType: 'paragraph', rightProps: {}});
          range = [
            pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: right, offset: 0}),
            pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: right, offset: 0})
          ];
          replace(lines[i]);
        }
      }
    } else if (op === 'pasteRich' && p.units) {
      if (q.cellSelection) clearSelectedCells();
      if (!Array.isArray(p.units) || p.units.some(u => !['text', 'inlineAtom', undefined].includes(u.kind) || typeof u.char !== 'string' || u.char.length !== 1 || (u.kind === 'inlineAtom' && u.char !== '\ufffc'))) {
        err('InvalidRichFragment');
      }
      const temp = repairedCloneFresh({id: 'fragment', type: 'paragraph', props: {}, units: p.units}, allocate);
      this._importResources(temp, doc, allocate, p.resources);
      result.importReport = this._sanitize(temp);
      replace('', temp.units);
    } else if (op === 'pasteRich' && !p.cells) {
      if (!Array.isArray(p.blocks) || !p.blocks.length) err('InvalidRichFragment');
      this._validateImportedUnits(p.blocks);
      if (q.cellSelection) clearSelectedCells();
      if (range[0] !== range[1]) replace('');
      const point = pointAt(flow, range[0]);
      const origNode = index(flow).get(point.blockId).node;
      // F07C: Preserve original block type/props on non-empty trailing half; if left half is empty and tail is non-empty,
      // preserve original NodeId on the surviving tail so pasting before a Heading/CodeBlock does not demote it or lose its ID!
      const splitAtEnd = point.offset === origNode.units.length && origNode.units.length > 0;
      const right = splitBlock(flow, point.blockId, point.offset, allocate, splitAtEnd ? {rightType: 'paragraph', rightProps: {}} : {});
      const ix = index(flow), parent = ix.get(ix.get(right).parent).node, links = new Map(), codes = new Map();
      const blocks = p.blocks.map(n => repairedCloneFresh(n, allocate, links, codes));
      this._importResources(blocks, doc, allocate, p.resources);
      result.importReport = this._sanitize(blocks);
      parent.children.splice(parent.children.findIndex(n => n.id === right), 0, ...blocks);
      const left = ix.get(point.blockId).node, tail = ix.get(right).node;
      let removedLeft = false;
      if (!left.units.length) {
        parent.children.splice(parent.children.findIndex(n => n.id === left.id), 1);
        removedLeft = true;
      }
      let landing = tail, offset = 0;
      if (!tail.units.length) {
        const last = leaves(blocks.at(-1)).at(-1);
        if (isText(last)) {
          parent.children.splice(parent.children.findIndex(n => n.id === tail.id), 1);
          landing = last;
          offset = last.units.length;
        }
      } else if (removedLeft) {
        // Restore original NodeId on the surviving non-empty tail block
        tail.id = left.id;
      }
      putFlow(doc, flow.id, flow);
      select(flow.id, pointOffset(flow, {kind: 'text', flowId: flow.id, blockId: landing.id, offset}));
    } else if (op === 'pasteTSV' || (op === 'pasteRich' && p.cells)) {
      const owner = flows(doc).get(this.activeFlow).owner;
      const selection = q.cellSelection ?? (this.selectionKind.type === 'cells' ? this.selectionKind : null);
      const t = locate(doc, selection?.tableId ?? owner?.table);
      if (!isTable(t?.node)) err('InvalidCellContext');
      let row = owner?.row ?? 0, col = owner?.col ?? 0;
      if (selection) {
        const cells = this._cells(t.node, selection);
        const f = flows(doc).get(cells[0].flow.id).owner;
        row = f.row;
        col = f.col;
        this._clearCellFlows(doc, selection, allocate);
      }
      if (p.cells) this._validateImportedUnits(p.cells.flatMap(r => Array.isArray(r) ? r.map(c => c?.flow) : []));
      const values = p.cells ? this._importResources(clone(p.cells), doc, allocate, p.resources) : parseTSV(p.text);
      if (p.cells) result.importReport = this._sanitize(values);
      if (!Array.isArray(values) || !values.length || values.some(r => !Array.isArray(r) || !r.length)) err('InvalidCellFragment');
      const width = Math.max(...values.map(r => r.length)), rows = t.node.props.rows;
      const targetCols = Math.max(rows[0].cells.length, col + width), targetRows = Math.max(rows.length, row + values.length);
      if (targetCols * targetRows > this.commandLimits.cells) err('TableAdmissionLimit');
      for (const r of rows) while (r.cells.length < targetCols) r.cells.push({id: allocate('cell'), flow: cellFlow(allocate)});
      while (rows.length < targetRows) rows.push({id: allocate('row'), cells: Array.from({length: targetCols}, () => ({id: allocate('cell'), flow: cellFlow(allocate)}))});
      const links = new Map(), codes = new Map();
      for (let i = 0; i < values.length; i++) {
        for (let j = 0; j < values[i].length; j++) {
          const cell = rows[row + i].cells[col + j], f = cell.flow, head = leaves(f).find(isText);
          const attrs = head.units.find(u => u.kind === 'text' || u.kind == null)?.attrs ?? head.emptyBlockAttributes ?? {};
          if (p.cells) {
            const source = values[i][j];
            if (!source.flow?.children) err('InvalidCellFragment');
            f.children = source.flow.children.map(n => repairedCloneFresh(n, allocate, links, codes));
            cell.props = clone(source.props ?? {});
          } else {
            f.children = [{id: head.id, type: 'paragraph', props: {}, units: textUnits(values[i][j], allocate, attrs), emptyBlockAttributes: clone(attrs)}];
          }
        }
      }
      select(rows[row].cells[col].flow.id, 0);
    } else if (op === 'putResource') {
      if (!p.resourceId || !p.resource || typeof p.resource !== 'object' || /^(javascript:|vbscript:|data:text\/html)/i.test(p.resource.uri ?? '')) {
        err('InvalidResource');
      }
      doc.props.resources[p.resourceId] = clone(p.resource);
    } else if (op === 'removeResource') {
      if (!Object.hasOwn(doc.props.resources, p.resourceId)) err('InvalidResource');
      const used = v => v && typeof v === 'object' && (v.resourceId === p.resourceId || Object.values(v).some(used));
      if (used(doc.children)) err('InvalidResourceInUse');
      delete doc.props.resources[p.resourceId];
    } else if (op === 'addAnnotation') {
      const a = pointAt(flow, range[0]), b = pointAt(flow, range[1]), id = allocate('annotation');
      doc.props.annotations.push({id, anchor: a, focus: b, props: clone(p.props ?? {}), orphaned: false});
      result.annotationId = id;
    } else if (op === 'removeAnnotation') {
      const i = doc.props.annotations.findIndex(a => a.id === p.annotationId);
      if (i < 0) err('InvalidAnnotation');
      doc.props.annotations.splice(i, 1);
    } else err('UnknownCommand');

    if (p.failAt === 'afterCommandMutation') throw Error('InjectedCommandFailure');
    for (const {flow: f} of flows(doc).values()) putFlow(doc, f.id, repairedNormalize(f, allocate));
    if (Object.keys(doc.props.resources).length > this.commandLimits.resources ||
        doc.props.annotations.length > this.commandLimits.annotations ||
        [...flows(doc).values()].reduce((n, {flow}) => n + index(flow).size, 0) > this.commandLimits.nodes) {
      err('AdmissionCapacityExceeded');
    }
    if (op === 'pasteRich') {
      try {
        validateDocument(doc);
      } catch (e) {
        if (e.message === 'NestedTablesUnsupported') throw e;
        err('InvalidRichFragment');
      }
    }
    const changed = !equal(before.document, doc);
    if (!changed) doc.children = clone(before.document.children);
    if (q.keepFocus) {
      nextFlow = this.activeFlow;
      selection = null;
      kind = this.selectionKind;
    }
    if (q.keepFocus || (q.targetFlow !== this.activeFlow && nextFlow === this.activeFlow)) intent = this.typingIntent;
    if (['backspace', 'deleteForward', 'deleteSelection', 'cut'].includes(op) && !q.keepFocus && q.targetFlow === this.activeFlow) {
      intent = {mode: 'inherit', side: op === 'deleteForward' ? 'right' : 'left'};
    }
    this._install(doc, {flowId: nextFlow, selection, kind, intent});
    if (!changed && op === 'tableTab') forceNoHistory = true;
    if (forceNoHistory) this.explicitSelectionBarrier = true;
    if (changed) {
      const entry = this._historyPush(before, this._bundle(), historyKind);
      return {...result, entry};
    }
    return {...result, status: forceNoHistory ? 'Applied' : 'NoChange', historySlot: !forceNoHistory};
  }
}

// F10A: Repaired AWT textLocation contract (validates 0 <= charIndex < markedLength when trailing, 0 <= charIndex <= markedLength when leading, and handles null offset when markedLength !== null).
