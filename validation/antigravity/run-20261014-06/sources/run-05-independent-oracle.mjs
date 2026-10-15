// Independent Observational Oracle (§5)
// Tracks visible attributed text, committed state, rollback obligations,
// marked range, selection, command ledger, and history independent of candidate representations.

import assert from 'node:assert/strict';

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
    return o;
  }

  get docText() {
    return this.getVisibleText();
  }

  get docAttributes() {
    return this.getVisibleAttrs();
  }

  applyInput(payload) {
    switch (payload.type) {
      case 'startComposition':
        return this.startComposition(payload.range, payload.selection, payload.sessionId);
      case 'preedit':
        return this.preedit(payload.text, payload.range, payload.selection);
      case 'partialCommit':
        return this.partialCommit(payload.length || payload.committedLength, payload.selection);
      case 'targetExpansion':
      case 'retarget':
        return this.retarget(payload.range || [payload.newStart, payload.newEnd], payload.selection);
      case 'unmark':
        return this.unmark(payload.settled !== false, payload.selection);
      case 'completeSettlement':
        return this.completeSettlement(payload.token, payload.sessionId);
      case 'commit':
        if (payload.text !== undefined) this.preedit(payload.text, payload.range, payload.selection);
        return this.unmark(true, payload.selection);
      case 'cancel':
      case 'cancelUncommitted':
      case 'applicationAbort':
        return this.cancel();
      case 'queueCommand':
        return { status: 'Accepted', ticketId: this.queueCommand(payload.cmd, payload.target) };
      default:
        return { status: 'Rejected', reason: `Unknown:${payload.type}` };
    }
  }

  // Flattened visible text
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

  // Committed document projection (what Dc is or what cancellation reveals)
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
        // Rollback uncommitted provisional run to its replaced committed tokens
        for (const r of entry.replacedCommitted) {
          units.push({ id: r.id, char: r.char, attrs: { ...r.attrs }, originSessionId: r.originSessionId });
        }
      } else if (entry.type === 'DeletionMarker') {
        // Uncommitted deletion: restore the deleted committed tokens!
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

  // -------------------------------------------------------------------------
  // Event handlers
  // -------------------------------------------------------------------------
  startComposition(range, selection = null, sessionId = null) {
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
    if (!this.activeSessionId) {
      const pos = this.selection[0];
      this.startComposition([pos, pos]);
    }
    
    if (range && (!this.markedSpan || range[0] !== this.markedSpan[0] || range[1] !== this.markedSpan[1])) {
      this.retarget(range);
    }
    
    if (!this.markedSpan) {
      this.markedSpan = this.epochSnapshot
        ? [...this.epochSnapshot.markedSpan]
        : [this.selection[0], this.selection[0]];
    }

    // Invalidate pending settlement
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    
    const [baseStart, baseEnd] = this.epochSnapshot.markedSpan;
    const prevText = this.getVisibleText();
    const prevAttrs = this.getVisibleAttrs();
    
    // Reconcile against epoch baseline B_epoch
    const B = this.epochSnapshot.B_epoch;
    let p = 0;
    while (p < text.length && p < B.length && text[p] === B[p].char) {
      p++;
    }
    let s = 0;
    while (s < (text.length - p) && s < (B.length - p) && text[text.length - 1 - s] === B[B.length - 1 - s].char) {
      s++;
    }
    
    // Restore tape to snapshot before this candidate edit within this epoch
    this.tape = JSON.parse(JSON.stringify(this.epochSnapshot.tape));
    
    // Locate entries covering [baseStart, baseEnd) in tape
    // Replace slice [baseStart + p, baseEnd - s) with new units / deletions
    const replaceStart = baseStart + p;
    const replaceEnd = baseEnd - s;
    
    // Gather replaced tokens for rollback obligation
    const flatBefore = [];
    let curVisIdx = 0;
    const tapeIndicesToSplice = [];
    let firstTapeIdx = -1;
    let lastTapeIdx = -1;
    
    const newTape = [];
    let idx = 0;
    const replacedTokens = [];
    
    for (let i = 0; i < this.tape.length; i++) {
      const entry = this.tape[i];
      let entryLen = 0;
      if (entry.type === 'CommittedToken') entryLen = 1;
      else if (entry.type === 'ProvisionalRun') entryLen = entry.units.length;
      else if (entry.type === 'DeletionMarker') entryLen = 0;
      
      const entryStart = idx;
      const entryEnd = idx + entryLen;
      idx += entryLen;
      
      // Check overlap with [replaceStart, replaceEnd)
      // Note: for 0-length deletion markers, if entryStart == replaceStart, it is inside
      const overlaps = (entryLen > 0)
        ? (Math.max(entryStart, replaceStart) < Math.min(entryEnd, replaceEnd))
        : (replaceStart <= entryStart && entryStart <= replaceEnd);
        
      if (overlaps) {
        // Collect replaced committed tokens
        if (entry.type === 'CommittedToken') {
          replacedTokens.push({ ...entry });
        } else if (entry.type === 'ProvisionalRun') {
          replacedTokens.push(...entry.replacedCommitted);
        } else if (entry.type === 'DeletionMarker') {
          replacedTokens.push(...entry.replacedCommitted);
        }
      } else {
        // Outside replaced slice
        // But if entry was partially overlapped (e.g. multi-unit ProvisionalRun), split it
        // For simplicity, handle committed tokens (length 1)
      }
    }
    
    // Construct new provisional units for changed middle
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
    
    // Reconstruct tape
    // Take prefix before replaceStart from tape
    const finalTape = [];
    let cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      if (cur + len <= replaceStart) {
        finalTape.push(entry);
      } else if (cur < replaceStart) {
        // Split entry
        if (entry.type === 'ProvisionalRun') {
          const leftLen = replaceStart - cur;
          finalTape.push({
            type: 'ProvisionalRun',
            id: entry.id + ':l',
            units: entry.units.slice(0, leftLen),
            replacedCommitted: []
          });
        }
      }
      cur += len;
    }
    
    // Insert middle units or DeletionMarker
    if (midUnits.length > 0) {
      finalTape.push({
        type: 'ProvisionalRun',
        id: `run:${this.activeSessionId}:${this.activeEpoch}`,
        units: midUnits,
        replacedCommitted: replacedTokens,
        originSessionId: this.activeSessionId
      });
    } else if (replacedTokens.length > 0) {
      // Zero-length visible deletion of replacedTokens!
      finalTape.push({
        type: 'DeletionMarker',
        id: `del:${this.activeSessionId}:${this.activeEpoch}`,
        atVisibleOffset: replaceStart,
        replacedCommitted: replacedTokens
      });
    }
    
    // Take suffix after replaceEnd from tape
    cur = 0;
    for (const entry of this.tape) {
      const len = (entry.type === 'CommittedToken') ? 1 : (entry.type === 'ProvisionalRun' ? entry.units.length : 0);
      if (cur >= replaceEnd) {
        finalTape.push(entry);
      } else if (cur + len > replaceEnd) {
        // Split entry
        if (entry.type === 'ProvisionalRun') {
          const rightStart = replaceEnd - cur;
          finalTape.push({
            type: 'ProvisionalRun',
            id: entry.id + ':r',
            units: entry.units.slice(rightStart),
            replacedCommitted: []
          });
        }
      }
      cur += len;
    }
    
    this.tape = finalTape;
    this.markedSpan = [baseStart, baseStart + text.length];
    if (this.sessionIslands && this.sessionIslands.length > 0) {
      const last = this.sessionIslands[this.sessionIslands.length - 1];
      last[0] = baseStart;
      last[1] = baseStart + text.length;
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
    const cutPos = mStart + k;
    
    // Invalidate pending settlement
    this.activeSettlementToken = null;
    this.activeSettlementFence = null;
    this.settlementEvidence = null;
    
    // In tape, every entry up to visible cutPos:
    // If ProvisionalRun: prefix units before cutPos become CommittedToken!
    // ReplacedCommitted tokens corresponding to committed units are CONSUMED (permanently deleted/replaced).
    // If cutPos splits a DeletionMarker:
    // A deletion at atVisibleOffset belongs to committed iff cutPos > atVisibleOffset!
    const newTape = [];
    let curVis = 0;
    const snap = this.sessionSnapshot;
    const suffixLen = snap ? (snap.text.length - snap.originRange[1]) : 0;
    const sessionEndVis = this.getVisibleUnits().length - suffixLen;
    const hasSessionUnitsAfterCut = cutPos < sessionEndVis;
    
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
          // Entire run is committed!
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
          // Split run
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
          // Uncommitted tail remains provisional, but its rollback obligation to original replaced text was consumed!
          newTape.push({
            type: 'ProvisionalRun',
            id: entry.id + ':rem',
            units: entry.units.slice(commLen),
            replacedCommitted: [], // consumed by committed prefix
            originSessionId: entry.originSessionId
          });
        } else {
          // Entirely after cutPos
          newTape.push(entry);
        }
      } else if (entry.type === 'DeletionMarker') {
        // Gap 4.4: Deletion belongs to committed iff cutPos > entry.atVisibleOffset,
        // or cutPos === entry.atVisibleOffset and there are no remaining visible units after cutPos in the active session.
        if (entry.atVisibleOffset < cutPos || (entry.atVisibleOffset === cutPos && !hasSessionUnitsAfterCut)) {
          // Committed deletion: replacedCommitted are permanently deleted, drop marker!
        } else {
          // Belongs to remaining uncommitted effect: retain marker!
          newTape.push(entry);
        }
      }
    }
    
    this.tape = newTape;
    if (cutPos === mEnd) {
      this.markedSpan = null;
      if (!hasSessionUnitsAfterCut) {
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
            extent: [mStart, mEnd]
          });
          this.redoStack = [];
        }
        this.activeSessionId = null;
        this.sessionSnapshot = null;
        this.epochSnapshot = null;
      }
    } else {
      this.markedSpan = [cutPos, mEnd];
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
      // Terminal unmark: commit all provisional entries permanently
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
        // Deletion markers are extinguished (committed)
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
      // Nonterminal unmark: generate settlement token, defer history
      const tok = this.nextSettlementToken();
      this.activeSettlementToken = { token: tok, sessionId: this.activeSessionId };
      this.settlementEvidence = { extent: [u, v], token: tok, sessionId: this.activeSessionId };
      this.sessRev++;
      return { status: 'Accepted', settled: false, token: tok };
    }
  }

  completeSettlement(token = null, sessionId = null) {
    if (this.markedSpan !== null) {
      return { status: 'Rejected', reason: 'CandidateStillActive' };
    }
    if (token && this.activeSettlementToken && token !== this.activeSettlementToken.token) {
      return { status: 'Rejected', reason: 'ObsoleteSettlementToken' };
    }
    if (sessionId && this.activeSessionId && sessionId !== this.activeSessionId) {
      return { status: 'Rejected', reason: 'SessionMismatch' };
    }
    if (!this.settlementEvidence) {
      return { status: 'Rejected', reason: 'UnknownTargetExtent' };
    }
    
    // Finalize all provisional entries into committed
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
    // Reveal committed projection (restore replacedCommitted from uncommitted ProvisionalRun and DeletionMarker)
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
    
    if (curText !== prevText || !areAttrsEqual(curAttrs, prevAttrs)) {
      this.docRev++;
    }
    
    // Check if any partial commits created net difference relative to sessionSnapshot
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
    
    // Cancel commands targeting ActiveComposition
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.target.type === 'CompositionTarget') {
          record.status = 'Cancelled';
          this.commandOutcomes.set(ticketId, { status: 'Cancelled', reason: 'CompositionCancelled' });
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

  queueCommand(cmd, target) {
    const ticketId = this.nextTicketId();
    this.commandLedger.set(ticketId, {
      ticketId,
      status: 'Pending',
      cmd,
      target,
      sessionId: this.activeSessionId,
      epoch: this.activeEpoch
    });
    return ticketId;
  }

  drainCommands(settledExtent) {
    const [eStart, eEnd] = settledExtent || [0, 0];
    
    for (const [ticketId, record] of this.commandLedger.entries()) {
      if (record.status === 'Pending') {
        if (record.cmd.type === 'Format') {
          const patch = record.cmd.patch;
          
          if (eStart === eEnd) {
            // Empty target extent: update typingIntent, no document text change
            this.typingIntent = { ...this.typingIntent, ...patch };
            record.status = 'Applied';
            this.commandOutcomes.set(ticketId, { status: 'Applied', typingIntentUpdated: true });
            continue;
          }
          
          const islands = (this.sessionIslands && this.sessionIslands.length > 1)
            ? this.sessionIslands
            : [[eStart, eEnd]];

          const beforeAttrs = this.getVisibleAttrs();
          let anyChanged = false;
          const appliedRanges = [];

          for (const [iStart, iEnd] of islands) {
            if (iStart === iEnd) continue;
            let formatStart = iStart;
            let formatEnd = iEnd;
            const tapeUnits = this.getVisibleUnits();
            while (formatEnd < tapeUnits.length && /[\u0300-\u036f]/.test(tapeUnits[formatEnd].char)) {
              formatEnd++;
            }
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
              range: appliedRanges[0],
              ranges: appliedRanges
            });
            this.redoStack = [];
          }
          
          record.status = 'Applied';
          this.commandOutcomes.set(ticketId, {
            status: 'Applied',
            range: appliedRanges[0],
            ranges: appliedRanges
          });
        }
      }
    }
    this.sessionIslands = [];
  }
}
