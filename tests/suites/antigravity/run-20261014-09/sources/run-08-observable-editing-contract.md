# Observable Editing Contract & Semantic Gap Resolution Specification
**Audit Run**: `run-20261014-08`  
**Date**: 14 October 2026  
**Status**: Authoritative Representation-Independent Contract for Candidate B (Segment-Mapped Overlay) Revision 4.9.4  

---

## 1. Scope and Design Principles

This specification defines the representation-independent normative contract governing text composition, settlement, cancellation, history, transaction atomicity, connection lifecycle, and command dispatch in the document editor.

Any coordinator implementation—specifically Candidate B (v5.3 separated committed-storage and piecewise segment-mapped provisional overlay)—must satisfy these identical observable behaviors without regressions.

### Three Orthogonal Facets of Input State
1. **Native Input Effect**:
   The exact requested replacement, marked range, raw selection, coordinate units, sequence receipt stamps, and synchronous acknowledgement.
2. **Commitment and Cancellation**:
   Which effects have become permanently committed to storage, which remain provisional in the overlay, and what each cancellation operation restores.
3. **User Command Intent**:
   Which content a queued command targeted (semantic composition entity vs concrete tracked range) and how that target follows subsequent admitted edits.

---

## 2. Event Specifications & State Transitions

### 2.1 `startComposition(range: [start, end], selection?: [anchor, focus], sessionId?: string)`
- **Preconditions**: Connection is Active. `compositionState == 'Idle'`.
- **Payload**:
  - `range`: `[u, v]` where `0 <= u <= v <= len(docText)`.
  - `selection`: optional `[selStart, selEnd]`. Validated against post-edit length (which equals current length). Default: `[v, v]`.
  - `sessionId`: optional string. Default: monotonically generated session ID.
- **Observable Effects**:
  - `compositionState` becomes `'Composing'`.
  - Visible document text and attributes are identical to pre-event document.
  - `markedRange` equals exact `[u, v]`.
  - `rawSelection` equals specified selection.
  - Baseline $B_0$ captures exact attributed units of `docText[u..v)`.
  - Snapshot $S_{\text{before}}$ captures initial pre-session state.
  - `DocumentRevision` remains UNCHANGED (`docRev' == docRev`).
  - `SessionRevision` increments by 1 (`sessRev' == sessRev + 1`).
  - History: No undo entry created.
  - Return: `{ status: 'Accepted', markedRange: [u, v], selection, documentRevision, sessionRevision }`.

### 2.2 `preedit(text: string, range?: [start, end], selection?: [anchor, focus])`
- **Preconditions**: Connection is Active. `compositionState` in `{'Idle', 'Composing', 'AwaitingSettlement'}`.
- **Selection Validation (Finding D)**: Validated against the proposed post-edit length:
  `proposedLen = len(docText) - (rangeEnd - rangeStart) + text.length`.
  Selections outside `[0, proposedLen]` are rejected upfront with `{ status: 'Rejected', reason: 'InvalidSelection' }`.
- **Observable Effects**:
  - If `Idle`: opens composition implicitly at selection.
  - If `AwaitingSettlement`: resumes composition, invalidating pending settlement tokens and fences.
  - If `range` differs from current `markedRange`: retargets to `range` first before applying text replacement.
  - Reconciles candidate text against immutable epoch baseline $B_{\text{epoch}}$ using maximal common prefix/suffix.
  - Visible text is updated: `newText = docText[0..u) + text + docText[v..)`.
  - `markedRange` becomes `[u, u + text.length]`.
  - `DocumentRevision` increments iff visible text or attributes changed.
  - `SessionRevision` increments by 1.
  - History: Deferred until terminal settlement/commit.
  - Pending commands remain queued in FIFO order.

### 2.3 `partialCommit(length: number, selection?: [anchor, focus])`
- **Preconditions**: `compositionState == 'Composing'`, `markedRange != null`, `1 <= length <= len(markedText)`.
- **Selection Validation (Finding D)**: Validated against current visible length (since visible text does not change).
- **Observable Effects**:
  - Visible text and visible attributes are 100% IDENTICAL before and after `partialCommit`.
  - Prefix of length `length` from active candidate becomes permanently committed to document storage ($D_c$).
  - `sessionCommittedRanges`: Cumulative committed spans are tracked throughout the composition session.
  - Segment Splitting: The unconsumed tail of committed storage remains associated with remaining segments (`origDcEnd = seg.dcEnd`), ensuring subsequent units are never dropped.
  - `DocumentRevision` remains UNCHANGED (`docRev' == docRev`).
  - `SessionRevision` increments by 1.
  - Active settlement token/fence is invalidated.
  - New attribution epoch begins: baseline $B_{\text{epoch}}'$ is set to the remaining uncommitted candidate units.
  - `markedRange` becomes `[u + length, end]`.
  - Return: `{ status: 'Accepted', frozenCount, markedRange, documentRevision, sessionRevision }`.

### 2.4 `targetExpansion` / `retarget(range: [newStart, newEnd], selection?: [anchor, focus])`
- **Preconditions**: `compositionState == 'Composing'`, `0 <= newStart <= newEnd <= len(visibleText)`.
- **Selection Validation (Finding D)**: Validated against current visible length.
- **Observable Effects**:
  - Exact Proposal Fidelity: `markedRange` becomes exact requested `[newStart, newEnd]`. Silent clamping is strictly prohibited.
  - Visible text and visible attributes remain UNCHANGED.
  - `DocumentRevision` remains UNCHANGED.
  - `SessionRevision` increments by 1.
  - Any pending settlement token is invalidated.
  - Newly engulfed tokens in `[newStart, newEnd]` become provisional segments in the overlay.
  - New attribution epoch begins with baseline covering the new candidate window.
  - Previously committed prefix units are preserved.

### 2.5 `unmark(settled: boolean, selection?: [anchor, focus])`
- **Case `settled == false` (Nonterminal Unmark)**:
  - `compositionState` transitions `Composing -> AwaitingSettlement`.
  - `markedRange` becomes `null`.
  - Visible text and attributes remain in document.
  - `DocumentRevision` remains UNCHANGED.
  - `SessionRevision` increments by 1.
  - Generates an active settlement token / fence. History push is DEFERRED until terminal settlement.
  - If called when `markedRange == null` (already unmarked): REJECTED as `AlreadyUnmarked`.
- **Case `settled == true` (Terminal Unmark / EndCompositionPreservingText)**:
  - `compositionState` transitions to `Idle`.
  - `markedRange` becomes `null`.
  - All candidate text is committed permanently.
  - History: Exactly 1 `Composition` undo group is recorded iff visible text/attributes differ from pre-session snapshot.
  - Drains and executes pending commands against final committed extent.
  - `SessionRevision` increments by 1; `DocumentRevision` increments iff drained commands modify text/attrs.

### 2.6 `completeSettlement(token?: string, sessionId?: string, epoch?: number)`
- **Preconditions**: `compositionState == 'AwaitingSettlement'`.
- **Guards**:
  - If `markedRange != null`: REJECTED (`CandidateStillActive`).
  - If `epoch` provided and `epoch != connectionEpoch`: REJECTED (`StaleEpoch`).
  - If `sessionId` provided and `sessionId != activeSessionId`: REJECTED (`SessionMismatch`).
  - If `token` provided and `token != activeSettlementToken`: REJECTED (`ObsoleteSettlementToken`).
  - If no tracked settlement evidence: REJECTED (`UnknownTargetExtent`).
- **Observable Effects**:
  - `compositionState` transitions `AwaitingSettlement -> Idle`.
  - Exactly 1 `Composition` undo group recorded if net change occurred.
  - Pending commands drained against concrete settled extent in strict FIFO order.
  - Session state cleared. `SessionRevision` increments by 1.

### 2.7 `commit(text?: string, range?: [start, end], selection?: [anchor, focus])`
- **Preconditions**: Connection Active. Any composition state.
- **Selection Validation (Finding D)**: Validated against proposed post-edit length.
- **Observable Effects**:
  - Finalizes text permanently at `range` (or current `markedRange`, or selection).
  - Clears `markedRange = null`, transitions to `Idle`.
  - Exactly 1 `Composition` undo group recorded iff net change occurred.
  - Drains pending commands against final extent.
  - `DocumentRevision` increments iff document text/attributes changed.
  - `SessionRevision` increments by 1.

### 2.8 Cancellation Operations
1. **`nativeRemoval(range: [start, end], replacementText: string)`**:
   - Represents native IME/host excising uncommitted text or replacing with explicit range/text.
   - Replaces `docText[range[0]..range[1])` with `replacementText`.
   - Clears marked range, transitions to `Idle`.
   - Does NOT restore pre-composition snapshot; applies exact native excision.
2. **`cancel` / `cancelUncommitted` / `applicationAbort`**:
   - Cancels uncommitted provisional work.
   - Any previously committed units (from `partialCommit`) MUST survive.
   - Uncommitted provisional replacements are discarded.
   - Absorbed original units that were never overwritten are preserved.
   - If net committed changes exist: records 1 `Composition` undo group for the committed portion.
   - `CompositionTarget` commands are cancelled with outcome `{ status: 'Cancelled', reason: 'CompositionCancelled' }`.
   - **Immediate Cancellation Drainage (Finding E)**: Surviving unrelated `TrackedRange` commands are immediately mapped across the rollback delta and drained, never left stranded in the pending queue.

---

## 3. Findings Resolution & Architectural Invariants

### 3.1 Connection Turnover Lifecycle (Findings A & B)
- **Turnover Commit**: When `openNewConnection()` is called during an active composition:
  1. The provisional overlay is flushed and committed to `committedDoc`.
  2. If the net committed text or attributes differ from the pre-session snapshot:
     Exactly 1 `Composition` undo group is pushed onto `undoStack`.
  3. All pending commands are drained against the committed extents.
  4. The connection epoch is bumped (`epoch++`).
  5. The sequence counter is reset to 0, and the `receiptCache` is cleared to support sequence number reuse by the new connection.
- **Result**: Turnover never discards accepted provisional text (resolving Finding A) and never loses undo history for prior partial commits (resolving Finding B).

### 3.2 Target Tracking on Full Partial Commit (Finding C)
- When a composition candidate is fully committed via `partialCommit(k)` where $k = \text{candidateLength}$, the active candidate becomes empty (`markedWindow = [p, p]`).
- If a formatting command (`queueCommand`) targeting `ActiveComposition` settles after a full partial commit:
  1. The target is derived from the cumulative `sessionCommittedRanges` combined with any `sessionIslands` and settled candidates.
  2. The command formats the committed units directly in `committedDoc`.
  3. A separate `Formatting` undo entry is recorded on `undoStack`.
  4. `typingIntent` is NOT modified when formatting committed units.
- **Result**: Range formatting is never erroneously converted into typing intent, and committed text is correctly formatted (resolving Finding C).

### 3.3 Proposed Post-Edit Selection Validation (Finding D)
- All selection payloads (`selection: [anchor, focus]`) are validated against the **proposed post-edit document length**:
  `proposedLen = curLen - (targetEnd - targetStart) + replacementLength`.
- For growth edits: selections up to `proposedLen` (greater than `curLen`) are accepted.
- For shrinkage edits: selections strictly greater than `proposedLen` are rejected upfront with `{ status: 'Rejected', reason: 'InvalidSelection' }`.
- **Result**: Resolves Finding D1 (growth rejections) and Finding D2 (shrinkage out-of-bounds corruption).

### 3.4 Immediate Cancellation Drainage (Finding E)
- When composition cancellation occurs (`_cancel()`), any queued command targeting `ActiveComposition` is cancelled (`status: 'Cancelled'`).
- Any queued command targeting an unrelated `TrackedRange`:
  1. Is mapped across the cancellation text delta:
     `delta = committedDoc.length - preCancelDocLength`.
  2. Is drained and executed immediately during cancellation.
- **Result**: Unrelated commands are never left permanently pending (resolving Finding E).

### 3.5 Connection Callback Epoch Admission (Finding F)
- Native host callbacks carrying an `epoch` stamp are guarded by strict admission:
  1. If `payload.epoch < coordinator.epoch`: REJECTED with `{ status: 'Rejected', reason: 'StaleEpoch' }`.
  2. If `payload.epoch > coordinator.epoch`: REJECTED with `{ status: 'Rejected', reason: 'FutureEpoch' }`.
  3. If `payload.epoch == coordinator.epoch`: ADMITTED.
- **Result**: Old-epoch callbacks from defunct connections are safely rejected (resolving Finding F).

---

## 4. Transaction Atomicity & Failure Injection (§4.3)

Every mutating operation executes within a staged transactional pipeline with 6 distinct failure injection points:
1. `beforeMutation`: Failure before any state or overlay modification.
2. `afterTextSplice`: Failure immediately after modifying provisional segments or committed storage.
3. `afterTargetMapping`: Failure after adjusting session islands or tracked command ranges.
4. `afterHistoryPush`: Failure after pushing an undo entry to `undoStack`.
5. `afterCommandDrain`: Failure during or immediately following command queue drainage.
6. `beforeReceiptPublication`: Failure before recording sequence receipts in `receiptCache`.

### Transaction Invariant (Invariant 10: I_ATOMIC_ROLLBACK)
- If an exception occurs or a failure is injected at ANY of the 6 stages:
  1. The coordinator restores its complete pre-transaction snapshot (`_cloneState()`), including `committedDoc`, `provisionalOverlay`, `markedWindow`, `attributionEpoch`, `undoStack`, `redoStack`, `selection`, `documentRevision`, and `sessionRevision`.
  2. `DocumentRevision` does NOT advance.
  3. `receiptCache` does NOT store the rejected receipt.
  4. The exception is re-thrown or surfaced cleanly without state corruption.

---

## 5. Executable Undo / Redo Contract (§4.4)

### 5.1 Undo Operation (`undo()`)
- **Preconditions**: Connection Active. `undoStack.length > 0` (or active composition modifying document).
- **Active Composition Handling (Run-08 Repair)**:
  If a composition session is active (`Composing` or `AwaitingSettlement`):
  1. If visible text or attributes differ from the pre-session snapshot $S_{\text{before}}$:
     The active composition is materialized and flushed to committed storage $D_c$, and a complete explicit `Composition` history entry is pushed onto `undoStack`.
  2. `undo()` then pops that entry, reverts `committedDoc` to the pre-session snapshot ($S_{\text{before}}.\text{text}$, $S_{\text{before}}.\text{attrs}$), and pushes a complete symmetric entry onto `redoStack`.
  3. The active overlay, marked window, attribution epoch, and settlement obligations are cleanly cleared, transitioning `compositionState` to `'Idle'`.
  4. Active provisional work is NEVER silently discarded on Undo, and the composition remains retrievable on `redoStack`!
- **Observable Effects**:
  - Pops the top entry from `undoStack`.
  - Restores `beforeText` and `beforeAttrs` to `committedDoc`.
  - Restores `selection` to `beforeSelection` recorded in the immutable undo entry.
  - Pushes a symmetric entry to `redoStack` with explicit `beforeText`, `beforeAttrs`, `beforeSelection`, `afterText`, `afterAttrs`, `afterSelection`.
  - Increments `DocumentRevision` iff the restored document text or attributes differ from the current document.
  - Clears `provisionalOverlay = null`, `markedWindow = null`.
  - Return: `{ status: 'Accepted', documentRevision, selection }`.

### 5.2 Redo Operation (`redo()`)
- **Preconditions**: Connection Active. `redoStack.length > 0`.
- **Observable Effects**:
  - Pops the top entry from `redoStack`.
  - Applies `afterText` and `afterAttrs` to `committedDoc`.
  - Restores `selection` to the post-edit selection recorded in the redo entry.
  - Pushes a symmetric entry to `undoStack`.
  - Increments `DocumentRevision` iff the restored document text or attributes differ from the current document.
  - Return: `{ status: 'Accepted', documentRevision, selection }`.

### 5.3 Branch Invalidation
- Any admitted editing operation (text insertion, deletion, replacement, or formatting) that modifies document text or attributes MUST immediately clear `redoStack = []`.

---

## 6. Formal Invariants (Invariants 1 - 10)

1. **`I_VISIBLE_PROJECTION`**: Visible text and attributes of Candidate B must strictly match the Independent Oracle projection at all times.
2. **`I_MARK_CONSISTENCY`**: `markedRange` is non-null iff composition is active; `markedRange[0] <= markedRange[1] <= len(visibleText)`.
3. **`I_COMMITTED_PREFIX`**: The committed document text $D_c$ is always an exact prefix or exact committed base of visible text.
4. **`I_UNDO_CONSISTENCY`**: History entries must never be duplicated, skipped, or record corrupt before/after diffs.
5. **`I_REVISION_MONOTONICITY`**: `DocumentRevision` advances iff semantic visible text or attributes change; `SessionRevision` advances on every admitted input event.
6. **`I_SELECTION_BOUNDS`**: `0 <= selection[0], selection[1] <= len(visibleText)`.
7. **`I_COMMAND_DISPATCH`**: Queued commands execute in strict FIFO order at settlement, or map/drain on cancellation.
8. **`I_SETTLEMENT_OBLIGATION`**: Active settlement fences must be satisfied or invalidated before returning to Idle.
9. **`I_RECEIPT_IDEMPOTENCY`**: Duplicate sequence stamps return cached receipts without re-executing mutations.
10. **`I_ATOMIC_ROLLBACK`**: Operations that throw or inject failures at any of the 6 transaction stages restore pre-operation state completely.

---

## 7. Complete History & Session Termination Matrix (§2 Requirement)

This section provides the complete normative enumeration of every admitted semantic mutation and session termination path across all three engine lifecycle states: `Idle`, `Composing`, and `AwaitingSettlement`.

### 7.1 Orthogonal Layer Separation Principle
To eliminate specification conflation, three orthogonal lifecycle facets are strictly decoupled:
1. **Protocol Acknowledgement (Synchronous)**: Returning `{ status: 'Accepted' }` with deterministic sequence receipt and monotonic revision counter. Signals that the in-memory coordinator processed the event without transaction rollback.
2. **Composition Commitment (In-Memory $D_c$)**: Materializing provisional overlay units $\mathcal{O}$ into durable committed storage $D_c$ and finalizing an undo history entry.
3. **Physical Durability (Persistence Journal)**: Decoupled disk flush and journal append governed by the engine persistence pipeline (§7). Physical disk durability never blocks synchronous in-memory IME transitions.

---

### 7.2 The 14-Event × 3-State Master Matrix

| Event / Input Operation | State | Admission Status | Surviving Visible Content | Authoritative History Preimage | Group Finalization Timing | Selection & Targets | Commands & Settlement Evidence | Document / Session Revisions | Invalid / Rejection Outcome |
|---|---|---|---|---|---|---|---|---|---|
| **1. `preedit`** | `Idle` | **Admitted** | Inserted candidate text replacing selection | Snapshot $S_0$ captured immediately before preedit | Deferred to terminal settlement | Proposed-length validated; target = selection | Pending commands queue in FIFO order; evidence = null | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` if selection exceeds proposed length |
| | `Composing` | **Admitted** | Updated candidate text spliced into overlay | Original pre-session snapshot $S_0$ strictly preserved | Deferred to terminal settlement | Target = explicit range or active candidate window | Settlement fence invalidated; commands remain queued | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` if selection out-of-bounds |
| | `AwaitingSettlement` | **Admitted** (Resumed) | Resumed candidate spliced into document | Original pre-session snapshot $S_0$ strictly preserved | Deferred to terminal settlement | Unified target: selection if markedRange is null, else marked candidate | Active settlement fence invalidated; transitions `-> Composing` | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` on proposed length bounds failure |
| **2. `partialCommit(k)`** | `Idle` | **Rejected** | Pre-event document unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(NoActiveComposition)` |
| | `Composing` | **Admitted** ($1 \le k \le \text{len}$) | 100% identical visible text before and after | Original pre-session snapshot $S_0$ strictly preserved | Deferred (cumulative `sessionCommittedRanges` updated) | Target narrows to remaining uncommitted suffix | Active settlement fence invalidated; new attribution epoch baseline | `docRev` unchanged; `sessRev++` | `Rejected(InvalidLength)` if $k < 1$ or $k > \text{len}$ |
| | `AwaitingSettlement` | **Rejected** | Pre-event document unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(CandidateNotActive)` (must resume preedit first) |
| **3. `commit(text)`** | `Idle` | **Admitted** | Inserted / replaced text permanently in $D_c$ | Document state immediately prior to commit | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range or selection | None pending; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | `Rejected(InvalidSelection)` |
| | `Composing` | **Admitted** | Final candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection = post-edit selection; marked window cleared | Pending commands drained against committed extent in FIFO order | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection = post-edit selection; marked window cleared | Pending commands drained against settled extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **4. `nativeRemoval`** | `Idle` | **Admitted** | Range excised and replaced with `replacementText` | Document state immediately prior to removal | Finalized immediately: exactly 1 `NativeRemoval` undo group | Target = explicit range; selection validated against post-length | None pending; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | `Rejected(InvalidRange)` or `Rejected(InvalidSelection)` |
| | `Composing` | **Admitted** | Native excision applied; overlay cleared | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range; selection validated | Commands drained/cancelled; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Native excision applied; settlement cleared | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range; selection validated | Settlement fence cleared; commands drained/cancelled; `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **5. `unmark(settled: false)`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` (nothing to unmark) |
| | `Composing` | **Admitted** (Nonterminal) | Candidate text remains visible in document | Original pre-session snapshot $S_0$ preserved | Deferred to completeSettlement | `markedRange` cleared (`null`); selection preserved | Active settlement token/fence generated; transitions `-> AwaitingSettlement` | `docRev` unchanged; `sessRev++` | `Rejected(AlreadyUnmarked)` if called repeatedly |
| | `AwaitingSettlement` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(AlreadyUnmarked)` |
| **6. `unmark(settled: true)`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` |
| | `Composing` | **Admitted** (Terminal) | Candidate text committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | `markedRange` cleared (`null`); selection preserved | Pending commands drained against final extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** (Terminal) | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; session state cleared | Pending commands drained against settled extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **7. `completeSettlement`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` (not awaiting settlement) |
| | `Composing` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(CandidateStillActive)` (candidate must be unmarked first) |
| | `AwaitingSettlement` | **Admitted** | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Session state cleared; selection preserved | Commands drained against settlement evidence; transitions `-> Idle` | `docRev++` iff commands mutate text/attrs; `sessRev++` | Guard rejections: `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch`, `UnknownTargetExtent` |
| **8. `cancel` / `cancelUncommitted`** | `Idle` | **Admitted** (No-op) | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None (`{ status: 'Accepted' }`) |
| | `Composing` | **Admitted** | Uncommitted overlay discarded; frozen units from `partialCommit` survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist; else no history push | Selection restored to pre-session (or end of frozen units) | Active composition commands cancelled; unrelated `TrackedRange` commands mapped across delta & drained immediately | `docRev++` iff visible text changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Uncommitted overlay discarded; frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist; else no history push | Selection restored to pre-session (or end of frozen units) | Composition commands cancelled; unrelated `TrackedRange` commands drained immediately; transitions `-> Idle` | `docRev++` iff visible text changed; `sessRev++` | Atomic rollback on stage failure |
| **9. `applicationAbort`** | `Idle` | **Admitted** (No-op) | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None (`{ status: 'Accepted' }`) |
| | `Composing` | **Admitted** | Identical to `cancel`: uncommitted discarded, frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist | Restored to pre-session | Composition commands cancelled; unrelated commands drained immediately; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Identical to `cancel`: uncommitted discarded, frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist | Restored to pre-session | Composition commands cancelled; unrelated commands drained immediately; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | Atomic rollback on stage failure |
| **10. `openNewConnection`** | `Idle` | **Admitted** | 100% preserved | Unmodified | None | Selection preserved | Epoch bumped (`epoch++`), sequence counter reset, receipt cache cleared | `docRev` unchanged; `sessRev++` | Atomic rollback on stage failure |
| | `Composing` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; overlay cleared | Pending commands drained/cancelled; session cleared; epoch bumped; `-> Idle` | `docRev` unchanged (materialization preserves visible text); `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; settlement cleared | Commands drained/cancelled; session cleared; epoch bumped; `-> Idle` | `docRev` unchanged; `sessRev++` | Atomic rollback on stage failure |
| **11. `onFocusLost`** | `Idle` | **Admitted** | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None |
| | `Composing` | **Admitted** | Dependent on strategy (`preserve-visible-content` / `commit` preserves; `cancel` discards) | Original pre-session snapshot $S_0$ | Finalized if committed/preserved | Selection preserved | Pending commands drained if committed, cancelled if aborted; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| | `AwaitingSettlement` | **Admitted** | Dependent on strategy | Original pre-session snapshot $S_0$ | Finalized if committed/preserved | Selection preserved | Settlement fence resolved; commands drained or cancelled; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| **12. `dispose`** | `Idle` | **Admitted** | 100% preserved | Unmodified | None | Selection preserved | `connectionState -> Closed`; session cleared | `docRev` unchanged; `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| | `Composing` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved | Commands drained/cancelled; `connectionState -> Closed`; `-> Idle` | `docRev` unchanged (content preserved); `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| | `AwaitingSettlement` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved | Commands drained/cancelled; `connectionState -> Closed`; `-> Idle` | `docRev` unchanged; `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| **13. `queueCommand(cmd)`** | `Idle` | **Admitted** (Immediate) | Formatting / command applied immediately to target | Document state prior to command | Finalized immediately: exactly 1 command-specific undo group | Target = explicit `TrackedRange` | Outcome = `Applied`; ticket recorded in `commandOutcomes` | `docRev++` iff attrs changed; `sessRev++` | `Rejected(InvalidTarget)` if `CompositionTarget` requested while Idle |
| | `Composing` | **Admitted** (Queued) | Visible text/attrs unmodified until settlement | N/A (deferred) | None (deferred to settlement) | Tracked by ticket in FIFO queue | Added to `pendingCommands`; ticket issued; outcome = `Pending` | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)` |
| | `AwaitingSettlement` | **Admitted** (Queued) | Visible text/attrs unmodified until settlement | N/A (deferred) | None (deferred to settlement) | Tracked by ticket in FIFO queue | Added to `pendingCommands`; ticket issued; outcome = `Pending` | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)` |
| **14. `undo` / `redo`** | `Idle` | **Admitted** iff stack nonempty | Historical document text/attrs restored | Top entry from respective stack (`undoStack` or `redoStack`) | Symmetrically transferred between stacks with explicit named fields | Restored from historical `beforeSelection` or `afterSelection` | N/A | `docRev++` iff restored state differs from visible; `sessRev++` | `Rejected(NothingToUndo)` or `Rejected(NothingToRedo)` |
| | `Composing` | **Undo: Admitted**; **Redo: Rejected** | Active composition materialized to $D_c$, then undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Materialized composition pushed to `undoStack`, popped, and preserved on `redoStack`! | Restored to pre-session selection | Overlay cleared; marked window cleared; commands drained; `-> Idle` | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` (active editing clears redo) |
| | `AwaitingSettlement` | **Undo: Admitted**; **Redo: Rejected** | Active composition materialized to $D_c$, then undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Materialized composition pushed to `undoStack`, popped, and preserved on `redoStack`! | Restored to pre-session selection | Settlement cleared; commands drained; transitions `-> Idle` | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` |

---

### 7.3 Mandatory Invariant Properties of the Matrix

1. **Preimage Conservation**: No execution path may clear, overwrite, or discard an authoritative pre-session snapshot $S_0$ prior to fulfilling the history obligation for retained visible edits.
2. **Undo/Redo Symmetry & Composition Retention**: Undo during an active composition session flushes visible text to committed storage, records the net composition edit on `undoStack`, immediately pops it to revert to pre-session snapshot $S_0$, and symmetrically preserves the composition entry on `redoStack`. A subsequent `redo()` immediately restores the active composition text!
3. **Preserve-Visible-Content Teardown**: Teardown paths (`openNewConnection`, `dispose`, and `onFocusLost` with `preserve-visible-content`) materialize accepted provisional units into $D_c$ and push an explicit history entry before clearing session state or closing the connection. Visible text is never discarded silently.
4. **Unified Target Authority**: Target descriptors are computed once per transaction via `_resolveTargetDescriptor(payload)` and used identically for proposed-length validation, text mutation, marked window allocation, and receipt generation. When `payload.range` is omitted:
   - If an active marked range exists (`markedRange !== null`), the target is the active candidate window.
   - If the marked range is null (such as following nonterminal `unmark(settled: false)`), the target resolves strictly to the current selection (`[anchor, focus]`).
5. **Universal Rejection Atomicity (Invariant 10)**: Any rejected operation or staged failure restores the coordinator state snapshot bit-for-bit (`_restoreState(snap)`), leaving `committedDoc`, `provisionalOverlay`, `markedWindow`, `undoStack`, `redoStack`, `selection`, `documentRevision`, and `receiptCache` completely unmodified.

---
This specification is authoritative and serves as the normative baseline for Candidate B qualification in `plan-v4.md` Revision 4.9.4.
