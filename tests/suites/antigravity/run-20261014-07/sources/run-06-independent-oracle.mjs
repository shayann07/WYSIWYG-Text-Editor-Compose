// Independent Observational Oracle (§5)
// Tracks visible attributed text, committed state, rollback obligations,
// marked range, selection, command ledger, and history independent of candidate representations.
// Repaired in run-20261014-06 to resolve defects A through I and match the corrected specification.

import assert from 'node:assert/strict';
import { expandToGraphemeBoundaries } from './candidate-b-coordinator.mjs';

export function cloneAttrs(attrs) {
  if (!attrs) return null;
  return attrs.map(a => ({ ...a }));
}

export function areAttrsEqual(a1, a2) {
  if (!a1 || !a2) return a1 === a2;
  if (a1.length !== a2.length) return false;
  for (let i = 0; i < a1.length; i++) {
    const k1 = Object.keys(a1[i]);
    const k2 = Object.keys(a2[i]);
    if (k1.length !== k2.length) return false;
    for (const k of k1) {
      if (a1[i][k] !== a2[i][k]) return false;
    }
  }
  return true;
}

export class IndependentOracle {
  constructor(initialText = "Hello", initialAttrs = null, initialSel = [0, 0]) {
    this.tokenCounter = 0;
    this.ticketCounter = 0;
    this.settlementSeqCounter = 0;
    
    // Tape of CommittedToken | ProvisionalRun | DeletionMarker
    this.tape = [];
    for (let i = 0; i < initialText.length; i++) {
      this.tape.push({
        type: 'CommittedToken',
        id: `init:${i}`,
        char: initialText[i],
        attrs: initialAttrs ? { ...initialAttrs[i] } : {},
        originSessionId: null
      });
    }
    
    this.selection = [...initialSel];
    this.markedSpan = null; // [start, end] in visible tape coordinates
    
    // Session & Attribution Epoch
    this.activeSessionId = null;
    this.activeEpoch = 0;
    this.sessionSnapshot = null; // pre-session visible state
    this.epochSnapshot = null; // { tape, markedSpan, B_epoch, seed }
    this.activeSeed = {};
    
    // History & Revisions
    this.docRev = 0;
    this.sessRev = 0;
    this.undoStack = [];
    this.redoStack = [];
    
    // Settlement & Command Ledger
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    this.commandLedger = new Map(); // ticketId -> { status, cmd, target, epoch, sessionId }
    this.commandOutcomes = new Map(); // ticketId -> outcome
    this.sessionIslands = [];
    this.typingIntent = {};
    this.connectionState = 'Active';
  }

  clone() {
    const o = new IndependentOracle("", null, [0, 0]);
    o.tokenCounter = this.tokenCounter;
    o.ticketCounter = this.ticketCounter;
    o.settlementSeqCounter = this.settlementSeqCounter;
    o.tape = JSON.parse(JSON.stringify(this.tape));
    o.selection = [...this.selection];
    o.markedSpan = this.markedSpan ? [...this.markedSpan] : null;
    o.sessionIslands = this.sessionIslands ? JSON.parse(JSON.stringify(this.sessionIslands)) : [];
    o.activeSessionId = this.activeSessionId;
    o.activeEpoch = this.activeEpoch;
    o.sessionSnapshot = this.sessionSnapshot ? JSON.parse(JSON.stringify(this.sessionSnapshot)) : null;
    o.epochSnapshot = this.epochSnapshot ? JSON.parse(JSON.stringify(this.epochSnapshot)) : null;
    o.activeSeed = { ...this.activeSeed };
    o.docRev = this.docRev;
    o.sessRev = this.sessRev;
    o.undoStack = JSON.parse(JSON.stringify(this.undoStack));
    o.redoStack = JSON.parse(JSON.stringify(this.redoStack));
    o.activeSettlementToken = this.activeSettlementToken ? { ...this.activeSettlementToken } : null;
    o.activeSettlementFence = this.activeSettlementFence ? { ...this.activeSettlementFence } : null;
    o.settlementEvidence = this.settlementEvidence ? { ...this.settlementEvidence } : null;
    o.commandLedger = new Map(JSON.parse(JSON.stringify(Array.from(this.commandLedger.entries()))));
    o.commandOutcomes = new Map(JSON.parse(JSON.stringify(Array.from(this.commandOutcomes.entries()))));
    o.typingIntent = { ...this.typingIntent };
    o.connectionState = this.connectionState;
    return o;
  }

  get docText() {
    return this.getVisibleText();
  }

  get docAttributes() {
    return this.getVisibleAttrs();
  }

  _validateRange(range, docLen) {
    if (!range || !Array.isArray(range) || range.length !== 2) return false;
    const [u, v] = range;
    if (!Number.isInteger(u) || !Number.isInteger(v)) return false;
    if (u < 0 || v < 0 || u > v || v > docLen) return false;
    return true;
  }

  _validateSelection(sel, docLen) {
    if (!sel || !Array.isArray(sel) || sel.length !== 2) return false;
    const [s0, s1] = sel;
    if (!Number.isInteger(s0) || !Number.isInteger(s1)) return false;
    if (s0 < 0 || s1 < 0 || s0 > docLen || s1 > docLen) return false;
    return true;
  }

  applyInput(payload) {
    if (this.connectionState === 'Closed') {
      return { status: 'Rejected', reason: 'ConnectionClosed' };
    }
    if (payload.injectFailure) {
      return { status: 'Rejected', reason: 'InjectedFailure' };
    }

    const curLen = this.getVisibleText().length;
    if (payload.range !== undefined && payload.range !== null) {
      if (!this._validateRange(payload.range, curLen)) {
        return { status: 'Rejected', reason: 'InvalidTargetRange' };
      }
    }
    if (payload.selection !== undefined && payload.selection !== null) {
      if (!this._validateSelection(payload.selection, curLen)) {
        return { status: 'Rejected', reason: 'InvalidSelection' };
      }
    }
    if (payload.type === 'partialCommit') {
      const k = payload.length !== undefined ? payload.length : payload.committedLength;
      if (!Number.isInteger(k) || k < 0) {
        return { status: 'Rejected', reason: 'InvalidPartialCommitCount' };
      }
    }

    switch (payload.type) {
      case 'startComposition':
        return this.startComposition(payload.range, payload.selection, payload.sessionId);
      case 'preedit':
        return this.preedit(payload.text, payload.range, payload.selection);
      case 'partialCommit':
        return this.partialCommit(payload.length !== undefined ? payload.length : payload.committedLength, payload.selection);
      case 'targetExpansion':
      case 'retarget':
        return this.retarget(payload.range || [payload.newStart, payload.newEnd], payload.selection);
      case 'unmark':
        return this.unmark(payload.settled !== false, payload.selection);
      case 'completeSettlement':
        return this.completeSettlement(payload.token, payload.sessionId, payload.epoch);
      case 'commit':
        if (payload.text !== undefined) {
          const preRes = this.preedit(payload.text, payload.range, payload.selection);
          if (preRes.status === 'Rejected') return preRes;
        }
        return this.unmark(true, payload.selection);
      case 'cancel':
      case 'cancelUncommitted':
      case 'applicationAbort':
        return this.cancel();
      case 'queueCommand':
        return { status: 'Accepted', ticketId: this.queueCommand(payload.cmd, payload.target) };
      case 'onFocusLost':
        return this.onFocusLost(payload.strategy);
      case 'openNewConnection':
        return this.openNewConnection();
      case 'dispose':
        return this.dispose();
      case 'setSelection':
        if (payload.selection && this._validateSelection(payload.selection, this.getVisibleText().length)) {
          this.selection = [...payload.selection];
          return { type: 'SelectionChanged', status: 'Accepted', selection: [...this.selection] };
        } else {
          return { status: 'Rejected', reason: 'InvalidSelection' };
        }
      default:
        return { status: 'Rejected', reason: `Unknown:${payload.type}` };
    }
  }

  getVisibleText() {
    return this.getVisibleUnits().map(u => u.char).join('');
  }

  getVisibleAttrs() {
    return this.getVisibleUnits().map(u => ({ ...u.attrs }));
  }

  getVisibleUnits() {
    const units = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        units.push({ id: entry.id, char: entry.char, attrs: { ...entry.attrs }, originSessionId: entry.originSessionId });
      } else if (entry.type === 'ProvisionalRun') {
        for (const u of entry.units) {
          units.push({ id: u.id, char: u.char, attrs: { ...u.attrs }, originSessionId: u.originSessionId });
        }
      } else if (entry.type === 'DeletionMarker') {
        // 0 visible units
      }
    }
    return units;
  }

  getCommittedText() {
    return this.getCommittedUnits().map(u => u.char).join('');
  }

  getCommittedAttrs() {
    return this.getCommittedUnits().map(u => ({ ...u.attrs }));
  }

  getCommittedUnits() {
    const units = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        units.push({ id: entry.id, char: entry.char, attrs: { ...entry.attrs }, originSessionId: entry.originSessionId });
      } else if (entry.type === 'ProvisionalRun') {
        for (const r of entry.replacedCommitted) {
          units.push({ id: r.id, char: r.char, attrs: { ...r.attrs }, originSessionId: r.originSessionId });
        }
      } else if (entry.type === 'DeletionMarker') {
        for (const r of entry.replacedCommitted) {
          units.push({ id: r.id, char: r.char, attrs: { ...r.attrs }, originSessionId: r.originSessionId });
        }
      }
    }
    return units;
  }

  nextTicketId(prefix = 'ticket') {
    return `${prefix}-${++this.ticketCounter}`;
  }

  nextSettlementToken() {
    return `settle-tok-${++this.settlementSeqCounter}`;
  }

  startComposition(range, selection = null, sessionId = null) {
    if (this.activeSessionId) {
      return { status: 'Rejected', reason: 'CompositionAlreadyActive' };
    }
    const [u, v] = range;
    this.sessionSnapshot = {
      text: this.getVisibleText(),
      attrs: this.getVisibleAttrs(),
      selection: [...this.selection],
      originRange: [u, v]
    };

    this.activeSessionId = sessionId || `session-${Date.now()}`;
    this.sessionIslands = [[u, v]];
    this.activeEpoch = 1;
    this.markedSpan = [u, v];
    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    
    const visibleUnits = this.getVisibleUnits();
    const B_epoch = visibleUnits.slice(u, v);
    this.activeSeed = (u < visibleUnits.length) ? { ...visibleUnits[u].attrs } : {};
    
    this.epochSnapshot = {
      tape: JSON.parse(JSON.stringify(this.tape)),
      markedSpan: [u, v],
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      seed: { ...this.activeSeed }
    };
    
    this.sessRev++;
    return { status: 'Accepted', markedSpan: [u, v] };
  }

  preedit(text, range = null, selection = null) {
    // Defect B: respect selection range when Idle
    if (!this.activeSessionId) {
      let compRange;
      if (range) {
        compRange = range;
      } else {
        const sel = this.selection || [0, 0];
        const selStart = Math.min(sel[0], sel[1]);
        const selEnd = Math.max(sel[0], sel[1]);
        compRange = [selStart, selEnd];
      }
      const startRes = this.startComposition(compRange, selection);
      if (startRes.status === 'Rejected') return startRes;
    }
    
    // Defect G: Validate and propagate retarget rejections
    if (range && (!this.markedSpan || range[0] !== this.markedSpan[0] || range[1] !== this.markedSpan[1])) {
      const retargetRes = this.retarget(range, selection);
      if (retargetRes.status === 'Rejected') return retargetRes;
    }
    
    if (!this.markedSpan) {
      this.markedSpan = this.epochSnapshot
        ? [...this.epochSnapshot.markedSpan]
        : [this.selection[0], this.selection[0]];
    }

    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    
    const [baseStart, baseEnd] = this.epochSnapshot.markedSpan;
    const prevText = this.getVisibleText();
    const prevAttrs = this.getVisibleAttrs();
    
    const B = this.epochSnapshot.B_epoch;
    let p = 0;
    while (p < text.length && p < B.length && text[p] === B[p].char) {
      p++;
    }
    let s = 0;
    while (s < (text.length - p) && s < (B.length - p) && text[text.length - 1 - s] === B[B.length - 1 - s].char) {
      s++;
    }
    
    this.tape = JSON.parse(JSON.stringify(this.epochSnapshot.tape));
    
    const replaceStart = baseStart + p;
    const replaceEnd = baseEnd - s;
    
    // Changed middle units
    const midText = text.slice(p, text.length - s);
    const midUnits = [];
    for (let j = 0; j < midText.length; j++) {
      midUnits.push({
        id: `prov:${this.activeSessionId}:e${this.activeEpoch}:m${j}`,
        char: midText[j],
        attrs: { ...this.activeSeed },
        originSessionId: this.activeSessionId
      });
    }

    // Gather entries and split at [baseStart, baseEnd)
    const finalTape = [];
    let cur = 0;
    const replacedTokens = [];

    // Prefix before replaceStart
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const isStrictlyBefore = (len > 0) ? (eEnd <= replaceStart) : (entry.atVisibleOffset < replaceStart);
      if (isStrictlyBefore) {
        finalTape.push(entry);
      } else if (eStart < replaceStart && entry.type === 'ProvisionalRun') {
        // Split provisional run before replaceStart
        const leftLen = replaceStart - eStart;
        finalTape.push({
          type: 'ProvisionalRun',
          id: entry.id + ':l',
          units: entry.units.slice(0, leftLen),
          replacedCommitted: entry.replacedCommitted,
          originSessionId: entry.originSessionId
        });
      }
    }

    // Collect replaced tokens inside [replaceStart, replaceEnd)
    cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const overlaps = (len > 0)
        ? (Math.max(eStart, replaceStart) < Math.min(eEnd, replaceEnd))
        : (replaceStart <= eStart && eStart <= replaceEnd);

      if (overlaps) {
        if (entry.type === 'CommittedToken') {
          replacedTokens.push({ ...entry });
        } else if (entry.type === 'ProvisionalRun') {
          // If left part already took ownership (eStart < replaceStart), do not duplicate!
          if (eStart >= replaceStart) {
            replacedTokens.push(...entry.replacedCommitted);
          }
        } else if (entry.type === 'DeletionMarker') {
          if (eStart >= replaceStart) {
            replacedTokens.push(...entry.replacedCommitted);
          }
        }
      }
    }

    // Insert changed middle units
    if (midUnits.length > 0) {
      finalTape.push({
        type: 'ProvisionalRun',
        id: `run:${this.activeSessionId}:${this.activeEpoch}`,
        units: midUnits,
        replacedCommitted: replacedTokens,
        originSessionId: this.activeSessionId
      });
    } else if (replacedTokens.length > 0) {
      finalTape.push({
        type: 'DeletionMarker',
        id: `del:${this.activeSessionId}:${this.activeEpoch}`,
        atVisibleOffset: replaceStart,
        replacedCommitted: replacedTokens
      });
    }

    // Suffix after replaceEnd
    cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      const eStart = cur;
      const eEnd = cur + len;
      cur = eEnd;

      const isStrictlyAfter = (len > 0) ? (eStart >= replaceEnd) : (entry.atVisibleOffset > replaceEnd);
      if (isStrictlyAfter) {
        finalTape.push(entry);
      } else if (eEnd > replaceEnd && entry.type === 'ProvisionalRun') {
        const rightStart = replaceEnd - eStart;
        finalTape.push({
          type: 'ProvisionalRun',
          id: entry.id + ':r',
          units: entry.units.slice(rightStart),
          replacedCommitted: [],
          originSessionId: entry.originSessionId
        });
      }
    }

    this.tape = finalTape;
    this.markedSpan = [baseStart, baseStart + text.length];

    // Defect H: Map disjoint islands and command targets across this edit
    const delta = text.length - (replaceEnd - replaceStart);
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      for (let i = 0; i < this.sessionIslands.length - 1; i++) {
        const isl = this.sessionIslands[i];
        if (isl[0] >= replaceEnd) {
          isl[0] += delta;
          isl[1] += delta;
        } else if (isl[1] > replaceStart) {
          isl[1] = Math.max(isl[0], isl[1] + delta);
        }
      }
      last[0] = baseStart;
      last[1] = baseStart + text.length;
    }

    // Map command ledger targets
    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.target && rec.target.type === 'TrackedRange') {
        const r = rec.target.range;
        if (r[0] >= replaceEnd) {
          r[0] += delta;
          r[1] += delta;
        } else if (r[1] > replaceStart) {
          r[1] = Math.max(r[0], r[1] + delta);
        }
      }
    }

    if (selection) this.selection = [...selection];
    else this.selection = [baseStart + text.length, baseStart + text.length];
    
    const newText = this.getVisibleText();
    const newAttrs = this.getVisibleAttrs();
    if (newText !== prevText || !areAttrsEqual(newAttrs, prevAttrs)) {
      this.docRev++;
    }
    this.sessRev++;
    return { status: 'Accepted', markedSpan: [...this.markedSpan] };
  }

  retarget(range, selection = null) {
    if (!this.activeSessionId) {
      return this.startComposition(range, selection);
    }
    const [newStart, newEnd] = range;
    this.markedSpan = [newStart, newEnd];
    if (!this.sessionIslands) this.sessionIslands = [];
    const lastIsland = this.sessionIslands[this.sessionIslands.length - 1];
    if (lastIsland) {
      const isDisjoint = (newStart >= lastIsland[1] || newEnd <= lastIsland[0]);
      if (isDisjoint) {
        this.sessionIslands.push([newStart, newEnd]);
      } else {
        lastIsland[0] = Math.min(lastIsland[0], newStart);
        lastIsland[1] = Math.max(lastIsland[1], newEnd);
      }
    } else {
      this.sessionIslands.push([newStart, newEnd]);
    }
    if (selection) this.selection = [...selection];
    
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    this.activeEpoch++;
    
    const visibleUnits = this.getVisibleUnits();
    const B_epoch = visibleUnits.slice(newStart, newEnd);
    this.epochSnapshot = {
      tape: JSON.parse(JSON.stringify(this.tape)),
      markedSpan: [newStart, newEnd],
      B_epoch: JSON.parse(JSON.stringify(B_epoch)),
      seed: { ...this.activeSeed }
    };
    
    this.sessRev++;
    return { status: 'Accepted', markedSpan: [newStart, newEnd] };
  }

  partialCommit(k, selection = null) {
    if (!this.markedSpan) return { status: 'Rejected', reason: 'NoActiveMarkedRange' };
    const [mStart, mEnd] = this.markedSpan;
    const currentLen = mEnd - mStart;
    if (k > currentLen) return { status: 'Rejected', reason: 'CommittedLengthExceedsCandidate' };
    const cutPos = mStart + k;
    
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    
    const newTape = [];
    let curVis = 0;
    // Check if remaining visible units exist in the active overlay after cutPos
    let remainingProvisionalUnitsAfterCut = 0;
    let tVis = 0;
    for (const e of this.tape) {
      const eLen = (e.type === 'CommittedToken') ? 1 : (e.type === 'ProvisionalRun' ? e.units.length : 0);
      if (tVis >= cutPos && e.type === 'ProvisionalRun') {
        remainingProvisionalUnitsAfterCut += e.units.length;
      }
      tVis += eLen;
    }
    const maxIslandEnd = (this.sessionIslands && this.sessionIslands.length > 0)
      ? Math.max(...this.sessionIslands.map(isl => isl[1]))
      : 0;
    const hasSessionUnitsAfterCut = (remainingProvisionalUnitsAfterCut > 0) || (cutPos < maxIslandEnd);
    
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        newTape.push(entry);
        curVis += 1;
      } else if (entry.type === 'ProvisionalRun') {
        const runLen = entry.units.length;
        const runStart = curVis;
        const runEnd = curVis + runLen;
        curVis += runLen;
        
        if (runEnd <= cutPos) {
          for (const u of entry.units) {
            newTape.push({
              type: 'CommittedToken',
              id: u.id,
              char: u.char,
              attrs: { ...u.attrs },
              originSessionId: u.originSessionId
            });
          }
        } else if (runStart < cutPos) {
          const commLen = cutPos - runStart;
          for (let i = 0; i < commLen; i++) {
            const u = entry.units[i];
            newTape.push({
              type: 'CommittedToken',
              id: u.id,
              char: u.char,
              attrs: { ...u.attrs },
              originSessionId: u.originSessionId
            });
          }
          newTape.push({
            type: 'ProvisionalRun',
            id: entry.id + ':rem',
            units: entry.units.slice(commLen),
            replacedCommitted: [],
            originSessionId: entry.originSessionId
          });
        } else {
          newTape.push(entry);
        }
      } else if (entry.type === 'DeletionMarker') {
        const hasRemainingUnitsAfter = hasSessionUnitsAfterCut;
        if (entry.atVisibleOffset < cutPos || (entry.atVisibleOffset === cutPos && k > 0 && !hasRemainingUnitsAfter)) {
          // Committed deletion
        } else {
          newTape.push(entry);
        }
      }
    }
    
    this.tape = newTape;

    // Defect F: Do not end session on full partial commit!
    if (cutPos === mEnd) {
      this.markedSpan = [cutPos, cutPos];
      this.sessionIslands = [[cutPos, cutPos]];
      this.activeEpoch++;
      this.epochSnapshot = {
        tape: JSON.parse(JSON.stringify(this.tape)),
        markedSpan: [cutPos, cutPos],
        B_epoch: [],
        seed: { ...this.activeSeed }
      };
    } else {
      this.markedSpan = [cutPos, mEnd];
      this.sessionIslands = [[cutPos, mEnd]];
      this.activeEpoch++;
      const visibleUnits = this.getVisibleUnits();
      const B_epoch = visibleUnits.slice(cutPos, mEnd);
      this.epochSnapshot = {
        tape: JSON.parse(JSON.stringify(this.tape)),
        markedSpan: [cutPos, mEnd],
        B_epoch: JSON.parse(JSON.stringify(B_epoch)),
        seed: { ...this.activeSeed }
      };
    }
    if (selection) this.selection = [...selection];
    else this.selection = [cutPos, cutPos];
    
    this.sessRev++;
    return { status: 'Accepted', markedSpan: this.markedSpan ? [...this.markedSpan] : null };
  }

  unmark(settled = true, selection = null) {
    if (!this.markedSpan) {
      return { status: 'Rejected', reason: 'AlreadyUnmarked' };
    }
    
    const [u, v] = this.markedSpan;
    this.markedSpan = null;
    if (selection) this.selection = [...selection];
    else this.selection = [v, v];
    
    if (settled) {
      const newTape = [];
      for (const entry of this.tape) {
        if (entry.type === 'CommittedToken') {
          newTape.push(entry);
        } else if (entry.type === 'ProvisionalRun') {
          for (const u of entry.units) {
            newTape.push({
              type: 'CommittedToken',
              id: u.id,
              char: u.char,
              attrs: { ...u.attrs },
              originSessionId: u.originSessionId
            });
          }
        }
      }
      this.tape = newTape;
      
      const curText = this.getVisibleText();
      const curAttrs = this.getVisibleAttrs();
      const snap = this.sessionSnapshot;
      if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
        this.undoStack.push({
          group: 'Composition',
          beforeText: snap.text,
          beforeAttrs: cloneAttrs(snap.attrs),
          afterText: curText,
          afterAttrs: cloneAttrs(curAttrs),
          extent: [u, v]
        });
        this.redoStack = [];
      }
      
      this.drainCommands([u, v]);
      this.activeSessionId = null;
      this.sessionSnapshot = null;
      this.epochSnapshot = null;
      this.sessRev++;
      return { status: 'Accepted', settled: true };
    } else {
      const tok = this.nextSettlementToken();
      this.activeSettlementToken = { token: tok, sessionId: this.activeSessionId };
      this.settlementEvidence = { extent: [u, v], token: tok, sessionId: this.activeSessionId };
      this.sessRev++;
      return { status: 'Accepted', settled: false, token: tok };
    }
  }

  completeSettlement(token = null, sessionId = null, epoch = null) {
    if (this.compositionState === 'Idle') {
      return { status: 'Rejected', reason: 'NoActiveSettlement' };
    }
    if (this.markedSpan !== null) {
      return { status: 'Rejected', reason: 'CandidateStillActive' };
    }
    if (epoch !== null && epoch !== undefined && epoch !== this.activeEpoch) {
      return { status: 'Rejected', reason: 'StaleEpoch' };
    }
    if (sessionId && this.activeSessionId && sessionId !== this.activeSessionId) {
      return { status: 'Rejected', reason: 'SessionMismatch' };
    }
    if (this.activeSettlementToken) {
      if (!token || token !== this.activeSettlementToken.token) {
        return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
      }
    }
    if (!this.settlementEvidence) {
      return { status: 'Rejected', reason: 'UnknownTargetExtent' };
    }
    
    const newTape = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        newTape.push(entry);
      } else if (entry.type === 'ProvisionalRun') {
        for (const u of entry.units) {
          newTape.push({
            type: 'CommittedToken',
            id: u.id,
            char: u.char,
            attrs: { ...u.attrs },
            originSessionId: u.originSessionId
          });
        }
      }
    }
    this.tape = newTape;
    
    const curText = this.getVisibleText();
    const curAttrs = this.getVisibleAttrs();
    const snap = this.sessionSnapshot;
    const finalExtent = [...this.settlementEvidence.extent];
    
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs),
        extent: finalExtent
      });
      this.redoStack = [];
    }
    
    this.drainCommands(finalExtent);
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.sessRev++;
    return { status: 'Accepted', finalExtent };
  }

  cancel() {
    const cancelPoint = this.markedSpan ? this.markedSpan[0] : 0;
    const newTape = [];
    for (const entry of this.tape) {
      if (entry.type === 'CommittedToken') {
        newTape.push(entry);
      } else if (entry.type === 'ProvisionalRun') {
        for (const r of entry.replacedCommitted) {
          newTape.push({
            type: 'CommittedToken',
            id: r.id,
            char: r.char,
            attrs: { ...r.attrs },
            originSessionId: r.originSessionId
          });
        }
      } else if (entry.type === 'DeletionMarker') {
        for (const r of entry.replacedCommitted) {
          newTape.push({
            type: 'CommittedToken',
            id: r.id,
            char: r.char,
            attrs: { ...r.attrs },
            originSessionId: r.originSessionId
          });
        }
      }
    }
    
    const prevText = this.getVisibleText();
    const prevAttrs = this.getVisibleAttrs();
    this.tape = newTape;
    const curText = this.getVisibleText();
    const curAttrs = this.getVisibleAttrs();
    const netRollbackDelta = curText.length - prevText.length;
    
    if (curText !== prevText || !areAttrsEqual(curAttrs, prevAttrs)) {
      this.docRev++;
    }
    
    const snap = this.sessionSnapshot;
    if (snap) {
      this.selection = [...snap.selection];
    }
    if (snap && (curText !== snap.text || !areAttrsEqual(curAttrs, snap.attrs))) {
      this.undoStack.push({
        group: 'Composition',
        beforeText: snap.text,
        beforeAttrs: cloneAttrs(snap.attrs),
        afterText: curText,
        afterAttrs: cloneAttrs(curAttrs)
      });
      this.redoStack = [];
    }
    
    // Defect E: Cancel ONLY commands targeting active composition
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.target && record.target.type === 'CompositionTarget') {
          record.status = 'Cancelled';
          this.commandOutcomes.set(ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
        } else if (record.target && record.target.type === 'TrackedRange' && netRollbackDelta !== 0) {
          const r = record.target.range;
          if (r && r.length === 2) {
            if (r[0] >= cancelPoint) {
              r[0] = Math.max(0, r[0] + netRollbackDelta);
              r[1] = Math.max(r[0], r[1] + netRollbackDelta);
            } else if (r[1] > cancelPoint) {
              r[1] = Math.max(r[0], r[1] + netRollbackDelta);
            }
          }
        }
      }
    }
    
    this.sessionIslands = [];
    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    this.sessRev++;
    return { status: 'Accepted' };
  }

  onFocusLost(strategy = 'cancel') {
    if (this.activeSessionId) {
      if (strategy === 'settle') {
        const res = this.unmark(true);
        return { type: 'FocusLost', status: res.status, action: 'Settled' };
      } else {
        const res = this.cancel();
        return { type: 'FocusLost', status: res.status, action: 'Cancelled' };
      }
    }
    return { type: 'FocusLost', status: 'Accepted', action: 'None' };
  }

  openNewConnection() {
    this.tape = this.tape.filter(e => e.type === 'CommittedToken');
    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.sessionIslands = [];
    this.connectionState = 'Active';
    this.activeEpoch++;
    this.sessRev++;
    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.status === 'Pending') {
        rec.status = 'Cancelled';
        this.commandOutcomes.set(tId, { status: 'Cancelled', reason: 'StaleEpoch' });
      }
    }
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    return { type: 'ConnectionOpened', status: 'Accepted', epoch: this.activeEpoch };
  }

  dispose() {
    this.tape = this.tape.filter(e => e.type === 'CommittedToken');
    this.markedSpan = null;
    this.activeSessionId = null;
    this.sessionSnapshot = null;
    this.epochSnapshot = null;
    this.sessionIslands = [];
    this.connectionState = 'Closed';
    this.sessRev++;
    for (const [tId, rec] of this.commandLedger.entries()) {
      if (rec.status === 'Pending') {
        rec.status = 'Cancelled';
        this.commandOutcomes.set(tId, { status: 'Cancelled', reason: 'Disposed' });
      }
    }
    this.activeSettlementToken = null;
    this.settlementEvidence = null;
    return { type: 'Disposed', status: 'Accepted' };
  }

  queueCommand(cmd, target) {
    const ticketId = this.nextTicketId();
    this.commandLedger.set(ticketId, {
      ticketId,
      status: 'Pending',
      cmd,
      target: JSON.parse(JSON.stringify(target)),
      sessionId: this.activeSessionId,
      epoch: this.activeEpoch
    });
    return ticketId;
  }

  drainCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];
    const fullDocText = this.getVisibleText();
    
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.cmd.type === 'Format') {
          const patch = record.cmd.patch;
          
          let targetRanges = [];
          if (record.target && record.target.type === 'TrackedRange') {
            const r = record.target.range;
            targetRanges = [[Math.max(0, r[0]), Math.min(fullDocText.length, r[1])]];
          } else if (record.target && record.target.type === 'CompositionTarget') {
            if (record.target.sessionId && this.sessionSnapshot && record.target.sessionId !== this.activeSessionId) {
              record.status = 'Rejected';
              this.commandOutcomes.set(ticketId, { status: 'Rejected', reason: 'TargetSessionMismatch' });
              continue;
            }
            if (eStart === eEnd) {
              this.typingIntent = { ...this.typingIntent, ...patch };
              record.status = 'Applied';
              this.commandOutcomes.set(ticketId, { status: 'Applied', typingIntentUpdated: true });
              continue;
            }
            targetRanges = (this.sessionIslands && this.sessionIslands.length > 1)
              ? this.sessionIslands
              : [[eStart, eEnd]];
          } else if (record.target && record.target.type === 'SelectionTarget') {
            targetRanges = [[Math.min(this.selection[0], this.selection[1]), Math.max(this.selection[0], this.selection[1])]];
          } else {
            targetRanges = [[eStart, eEnd]];
          }

          const beforeAttrs = this.getVisibleAttrs();
          let anyChanged = false;
          const appliedRanges = [];

          for (const [tStart, tEnd] of targetRanges) {
            if (tStart === tEnd) continue;
            // Canonical outward grapheme cluster expansion
            const [formatStart, formatEnd] = expandToGraphemeBoundaries(fullDocText, [tStart, tEnd]);
            appliedRanges.push([formatStart, formatEnd]);

            let curIdx = 0;
            for (const entry of this.tape) {
              if (entry.type === 'CommittedToken') {
                if (curIdx >= formatStart && curIdx < formatEnd) {
                  for (const k of Object.keys(patch)) {
                    if (entry.attrs[k] !== patch[k]) {
                      entry.attrs[k] = patch[k];
                      anyChanged = true;
                    }
                  }
                }
                curIdx++;
              }
            }
          }
          
          if (anyChanged) {
            this.docRev++;
            this.undoStack.push({
              group: 'Formatting',
              beforeAttrs,
              afterAttrs: this.getVisibleAttrs(),
              range: appliedRanges[0] || [0, 0],
              ranges: appliedRanges
            });
            this.redoStack = [];
          }
          
          record.status = 'Applied';
          this.commandOutcomes.set(ticketId, {
            status: 'Applied',
            range: appliedRanges[0] || [0, 0],
            ranges: appliedRanges,
            patch: record.cmd.patch
          });
        }
      }
    }
    this.sessionIslands = [];
  }
}
