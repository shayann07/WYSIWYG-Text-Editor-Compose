# Observable Editing Contract & Semantic Gap Resolution Specification
**Audit Run**: `run-20261015-12`  
**Date**: 15 October 2026  
**Status**: Authoritative Representation-Independent Contract for Candidate B (Segment-Mapped Overlay) Revision 4.9.8  

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

### 3.4 Surviving Token-Identity Target Resolution on Rollback (Finding E & Defect 1)
- When composition cancellation or rollback occurs (`_cancel()`), tracked command ranges cannot be safely transformed using a global scalar net delta (`committedDoc.length - preCancelDocLength`). In the presence of disjoint or opposing edits (e.g. insertion at index 0 and deletion at index 4), the net delta may be zero or inconsistent across disjoint spans.
- **Exact Segment-Mapped Surviving Target Resolution**:
  1. The coordinator collects all surviving token identities from committed storage $D_c$.
  2. For every tracked range `[u, v]`:
     - Collapsed ranges (`u === v`) map to piece boundaries without corruption.
     - Units within `[u, v]` are projected to their post-cancellation coordinates in $D_c$.
     - If all tokens within the target were provisional units discarded on rollback: REJECTED with `{ status: 'Rejected', reason: 'TargetDeleted' }`.
     - If the target coordinates extend beyond document bounds: REJECTED with `{ status: 'Rejected', reason: 'TargetInvalidated' }`.
     - Surviving targets are mapped to exact contiguous bounds in $D_c$.
  3. **Disjoint Interval Preservation & Min-Max Expansion Prohibition (Defect 1)**:
     - When provisional replacements are discarded during cancellation or rollback, tokens surviving from a tracked range may be separated by intervening restored baseline units that were previously unselected or unedited.
     - Returning a single contiguous min-max interval `[min(surviving), max(surviving) + 1]` is STRICTLY FORBIDDEN because it silently expands the command target over the restored intervening text (e.g. formatting unedited restored text).
     - Instead, `mapTrackedRangeOnCancel` partitions surviving token indices into contiguous maximal intervals and returns disjoint spans: `ranges: [[s0, e0], [s1, e1], ...]`.
     - Queued formatting or command execution applies ONLY to the surviving intervals, preserving the exact constituent identity and attributes of restored baseline units.
  4. Commands targeting `ActiveComposition` are cleanly cancelled with `{ status: 'Cancelled', reason: 'CompositionCancelled' }`.
  5. Surviving commands targeting `TrackedRange` are immediately drained and executed in FIFO order against $D_c$.
- **Result**: Resolves Finding E and Defect 1 (disjoint opposing rollback target distortion and silent min-max expansion).

### 3.5 Connection Callback Epoch Admission (Finding F)
- Native host callbacks carrying an `epoch` stamp are guarded by strict admission:
  1. If `payload.epoch < coordinator.epoch`: REJECTED with `{ status: 'Rejected', reason: 'StaleEpoch' }`.
  2. If `payload.epoch > coordinator.epoch`: REJECTED with `{ status: 'Rejected', reason: 'FutureEpoch' }`.
  3. If `payload.epoch == coordinator.epoch`: ADMITTED.
- **Result**: Old-epoch callbacks from defunct connections are safely rejected (resolving Finding F).

### 3.6 Retarget Visible Attribute Seeding
- When `retarget(range)` expands or shifts the active candidate window over existing visible text, seed formatting attributes for new provisional segments are sampled from the visible projection (`this.docAttributes[u]`), NOT raw committed storage (`this.committedDoc[u]`).
- This guarantees that when a candidate overlay expands over previously formatted or partially committed units, formatting provenance is preserved without regressing to stale baseline attributes.

### 3.7 Transactional Command Outcome Preservation on Rollback
- Every command dispatched generates a deterministic `ticketId`.
- When an operation throws an exception, fails validation, or rolls back via `_restoreState(snap)`:
  1. The snapshot restoration preserves all engine state (`committedDoc`, `provisionalOverlay`, `history`, `revisions`).
  2. However, ticket outcome records in `this.commandOutcomes` (`{ status: 'Rejected', reason }`) are explicitly retained and NOT wiped by the rollback.
  3. Callers polling or querying ticket status receive exact deterministic failure reasons rather than an `undefined` ticket state.

### 3.8 Cross-Epoch Receipt Isolation & Connection Fencing
- Sequence receipts are keyed composite-style as `${epoch}:${seq}`.
- Connection epoch validation precedes any duplicate receipt lookup.
- If a stale connection re-transmits a packet from an earlier epoch, the packet is rejected with `StaleEpoch` before querying `receiptCache`, preventing receipt collision across connection recycling.

### 3.9 Centralized Transactional Dispatcher (`dispatchCommand` & `_runInTransaction`) (Defect 2)
- All external entry points (`dispatchCommand`, `applyInput`, `undo`, `redo`) execute exclusively within the central transactional wrapper `_runInTransaction`.
- Direct invocation of `dispatchCommand(cmd, target)` mutates document state, updates typing intent, or records history within an active transaction boundary:
  1. **Upfront Validation & Ticket Safety**: Inputs and commands are validated before ticket allocation or state mutation. Malformed commands (e.g. invalid type, invalid target range) fail upfront with `{ status: 'Rejected', reason }` without leaking tickets or mutating document state.
  2. **Reentrancy Queueing & Rollback Isolation**: If `_inTransaction` is already active, incoming mutating operations are deferred into `_reentrantMutationQueue` with `{ status: 'Accepted', action: 'QueuedReentrant', ticketId }` and executed sequentially in FIFO order upon completion of the outer transaction. If the outer transaction rolls back, `_reentrantMutationQueue` is decoupled from transactional state snapshots and preserved, allowing queued mutations to execute against the restored baseline.
  3. **Universal Transactional Rollback**: If an exception occurs, a stage failure is injected, or a mutation returns `{ status: 'Rejected' }`, the entire coordinator state is restored from `preTxSnapshot`. Any allocated ticket in `commandOutcomes` records the rejected status and reason, ensuring outcomes persist across rollbacks.
  4. **Biconditional Revision Monotonicity**: `documentRevision` advances by 1 if and only if visible document text or attributes changed during the transaction (`textChanged || attrsChanged`). Pure selection movements, cursor navigation, or no-op formatting do not advance `documentRevision`.
  5. **Idle vs Active Session Dispatch**:
     - If the coordinator is `Idle`: The command executes synchronously, returning `{ status: 'Applied', ticketId, ... }` and recording `Applied` in `commandOutcomes`.
     - If the coordinator is `Composing` or `AwaitingSettlement`: The command is queued in `settlementObligation.pendingCommands`, returning `{ status: 'Accepted', ticketId, ... }` and recording `Pending`. It executes deterministically upon settlement fence satisfaction or cancellation.

### 3.10 Asynchronous Settlement Barrier & Ordered Settlement Policy on Undo (Defect 3)
- When `undo()` is requested while the coordinator has an active composition or pending settlement:
  1. **Asynchronous Settlement Barrier**: An application cannot unilaterally transition `compositionState` to `Idle` or clear composition obligations without native IME cooperation. Doing so desynchronizes the coordinator from the OS IME, causing subsequent native commits to insert corrupted phantom text (e.g., `'aXYc'`).
  2. **Queueing behind Settlement Obligation**: In asynchronous environments (default), invoking `undo()` during `Composing` or `AwaitingSettlement` transitions to (or remains in) `AwaitingSettlement`, issues an active settlement fence token, and queues an `Undo` record in `settlementObligation.pendingCommands` with `{ status: 'Pending', ticketId }`. The call returns `{ type: 'UndoQueued', status: 'Accepted', action: 'QueuedBehindSettlement', ticketId, state: 'AwaitingSettlement' }`.
  3. **Qualified Synchronous Turnover (`syncBoundary: true`)**: Immediate synchronous rollback is permitted if and only if the platform adapter explicitly supplies `syncBoundary: true` along with verified `settlementEvidence` (specifying the concrete extent). In this qualified case:
     - If visible text has not been modified from the baseline snapshot but commands were queued:
       1. The coordinator drains and executes queued commands against the visible text.
       2. Pushes an explicit `Formatting` history group onto `undoStack`.
       3. Pops that group to restore the pre-command document state, preserving the entry on `redoStack`.
     - If visible text was modified:
       1. Materializes the active composition into $D_c$.
       2. Pushes an explicit `Composition` history entry.
       3. Reverts $D_c$ to pre-session snapshot $S_0$ and preserves on `redoStack`.
  4. **Terminal Drainage on Settlement**: When native settlement completes via `unmark(settled: true)` or `completeSettlement()`, the active fence is cleared, any pending queued undo is drained and executed against the settled document state, and the coordinator cleanly transitions to `Idle`.
- **Result**: Eliminates non-deterministic history skips and prevents state desynchronization across the asynchronous settlement barrier.

---


### 3.11 Transactional Reentrancy FIFO Queueing & Sequential Execution (Failure 4 Repair)
- Reentrant mutating operations (`dispatchCommand`, `applyInput`, `undo`, `redo`, `nativeRemoval`, `setSelection`, `openNewConnection`, `dispose`) dispatched while an outer transaction (`_inTransaction`) is actively executing are not rejected with `ReentrantMutation` or executed synchronously against incomplete staged state.
- Instead, reentrant mutation calls are queued into `_reentrantMutationQueue` and executed sequentially in FIFO order upon completion and publication of the outer transaction.
- Rollback Isolation: If the outer transaction fails, rolls back, or throws an unhandled exception, state is restored atomically to `preTxSnapshot`. The queued reentrant mutations evaluate against the clean restored baseline.
- Reentrant read-only inspection queries (`docText`, `docAttributes`, `selection`) observe the consistent published state without blocking.

### 3.12 Operation-Level Redo Stack Preservation Across Settlement Drainage (Failure 1 Repair)
- Invalidation of `redoStack` (`redoStack = []`) is strictly scoped to operations that introduce new editing mutations (text insertion, deletion, replacement, or formatting).
- Settlement boundary finalization or transaction wrapper completion (`commit`, `completeSettlement`, `unmark`) MUST NOT wipe the redo stack if the net document change was produced by an executed `Undo`.
- When terminal settlement drains a queued `Undo`, the resulting redo entry pushed to `redoStack` is preserved upon entering `Idle`, allowing subsequent user `redo()` to re-apply the undone composition or formatting.
- Causal Drainage Ordering: If an application `Format` command was queued before an `Undo` command, the `Format` applies first to the candidate/document, pushing a `Formatting` undo entry; the subsequent `Undo` immediately pops that `Formatting` entry, leaving visible text plain and restoring original attributes, while moving the formatting entry to `redoStack`.

### 3.13 Dynamic Token-Identity Remapping Across Intermediate Undos (Failure 2 Repair)
- When queued drainage sequences contain interleaved `Undo` and `Format` commands (or when intermediate undos restore historical preimages with differing lengths), target ranges cannot rely on static scalar coordinate offsets.
- The coordinator employs dynamic token-identity tracking via `_remapPendingCommandTargets`. Each command ledger entry retains the token IDs of its original target units.
- When an intermediate `Undo` reverts document text, surviving tokens are queried against the updated document token stream. The surviving token indices are partitioned into maximal contiguous intervals `[[s0, e0], [s1, e1], ...]`.
- Commands execute against these surviving intervals rather than being invalidated by stale coordinate bounds (`TargetInvalidated`).

### 3.14 Synchronous Settlement Evidence Qualification Contract (Failure 3 Repair)
- Synchronous settlement (`syncBoundary: true`) bypasses the asynchronous settlement barrier if and only if the platform bridge provides cryptographically/structurally qualified settlement evidence:
  1. **Qualification Stamp**: `evidence.adapterQualification` MUST strictly equal `'QualifiedSyncSettlement'`. Unqualified adapters (e.g. standard Desktop AWT or unverified host shims without synchronous completion guarantees) are strictly rejected.
  2. **Epoch and Session Consistency**: `evidence.epoch` must equal `coordinator.epoch`, `evidence.sessionId` must equal `activeSessionId`, and `evidence.sessionRevision` must equal the active session revision.
  3. **Bounded Extent Verification**: `evidence.extent` must be a valid 2-element array `[start, end]` within document bounds (`0 <= start <= end <= len(docText)`).
  4. **Single-Use Unique Nonce**: `evidence.recordId` must be a unique, non-null string that has not been previously consumed in the connection epoch (`consumedSettlementRecords`). Replay or reuse of a record ID is rejected with `InvalidSettlementEvidence`.
  5. **Fallback on Failure**: Any failure in settlement evidence qualification causes the coordinator to reject the synchronous shortcut with `Rejected(InvalidSettlementEvidence)` and fall back to the safe asynchronous settlement barrier queueing.

### 3.15 Immutable RequestEnvelope & Execution-Time Epoch/Session Validation (Probe 1 Repair)
- All requests entering the engine are encapsulated in an immutable `RequestEnvelope`:
  ```kotlin
  data class RequestEnvelope<T>(
      val ticketId: String,
      val origin: RequestOrigin,
      val epoch: Long,
      val seq: Long,
      val sessionId: String?,
      val basisRevision: Long,
      val basisCoordinate: IntRange?,
      val payload: T
  )
  ```
- **Execution-Time Validation**: While ingress admission stamps envelopes upon receipt, execution-time validation is evaluated at the exact moment the deferred callback executes inside `_runInTransaction`.
- If connection epoch has advanced (`coordinator.epoch > envelope.epoch`) due to connection turnover (`openNewConnection`), the deferred callback MUST reject at execution time with `Rejected(StaleEpoch)`. If `envelope.epoch > coordinator.epoch`, it rejects as `Rejected(FutureEpoch)`. If `sessionId` does not match active session, it rejects as `Rejected(SessionMismatch)`.

### 3.16 Staging vs Published Snapshot Boundary, Atomic Commit & Notification Lifecycle (Probe 2 Repair)
- The coordinator maintains an immutable `_publishedState` and mutable internal working state.
- **Public Read Isolation**: All public property getters (`docText`, `docAttributes`, `selection`, `epoch`, `connectionState`, `documentRevision`, `sessionRevision`) read strictly from `_publishedState`. Intermediate, uncommitted, or in-flight mutations within transactions are completely invisible to external callers.
- **Atomic Commit**: Staging mutations execute against working state. Upon successful completion of all pipeline stages, `_publishedState` is updated atomically, followed by `_didChangeListeners` notifications.
- **Rollback Isolation**: On validation failure, staged failure, or unhandled exception, working state rolls back from pre-transaction baseline without altering `_publishedState`, followed by `_rollbackListeners` notifications.
- **Notification Ordering**: `_willChangeListeners` fired synchronously before mutation; `_didChangeListeners` fired after successful atomic commit; `_rollbackListeners` fired on transaction failure.

### 3.17 Settlement Lifecycle Ownership & Reentrancy Preservation (Probe 3 Repair)
- **Ordinary Settlement Preserves Reentrancy**: Normal settlement completions (`completeSettlement`, `commit`, terminal `unmark`) drain pending commands and explicitly PRESERVE eligible reentrant commands in `_reentrantMutationQueue`. These reentrant mutations execute sequentially in FIFO order against the settled document state. Ordinary settlement MUST NOT mark eligible reentrant commands as `Disposed`.
- **Disposal Teardown Cancels Everything**: Explicit connection disposal (`dispose()`) cancels pending commands and remaining reentrant mutations as `Rejected(Disposed)` / status `Disposed`.
- **Turnover Fences Deferred Callbacks**: Connection turnover (`openNewConnection()`) advances epoch; queued callbacks carrying the older epoch reject at execution time as `StaleEpoch`.
- **Single Queue-Drain Owner**: Active outer transaction completion loop owns queue drainage with bounded scheduling, preventing reentrancy deadlocks.

### 3.18 Persistent Identity Index, 64-Bit JVM Memory Accounting & Dynamic Rank Resolution (Probe 4 Repair)
- **Reproducible 64-Bit JVM Memory Model**:
  - `Piece` descriptor scalar fields: 24 bytes (`bufferId`: 4B, `startOffset`: 4B, `length`: 4B, `baseTokenId`: 8B, `runLength`: 4B).
  - 64-bit JVM compressed OOPs overhead: 12B header + 24B scalar fields + 4B alignment padding = **40 bytes minimum JVM object overhead** per `Piece` instance.
  - Heap breakdown: 1,000 pieces = ~5.16 MiB heap; 1,000,000 pieces = 98.22 MiB heap (38.15 MiB pieces + 5.41 MiB DocTree + 47.68 MiB TokenIndex + 1.91 MiB text buffer).
  - Decoupled history retention budget: The live engine heap (up to 98.22 MiB) is strictly decoupled from the independent 64 MiB history retention budget. History retains persistent root pointers; LRU eviction drops oldest roots when budget exceeds 64 MiB / 1,000 entries.
- **$O(\log P)$ Dynamic Rank Resolution**:
  - Internal B+ tree nodes maintain left subtree length accumulators (`subtreeLength`).
  - Offset-to-piece rank lookup walks down tree in $O(\log P)$ steps comparing target offset with child accumulators.
  - Insert-before / insert-after updates accumulators along the $O(\log P)$ root path with persistent path-copying, requiring **0 locator updates** on surrounding pieces.
  - Tree splits, merges, and rebalancing recalculate accumulators locally along the $O(\log P)$ ancestor path without leaf scans.
  - Historical root restoration assigns root pointer in $O(1)$ time, instantly restoring exact document rank and token mapping.

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
| | `Composing` | **Undo: Queued** (unless `syncBoundary: true`); **Redo: Rejected** | Without `syncBoundary`: visible content unmodified; queued behind settlement fence. With `syncBoundary`: materialized to $D_c$, undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Without `syncBoundary`: none (deferred). With `syncBoundary`: `Composition` group pushed & popped to `redoStack` | Selection preserved / restored to pre-session | Settlement token issued; undo record queued in `pendingCommands` (`-> AwaitingSettlement`); with `syncBoundary`: fence resolved (`-> Idle`) | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` |
| | `AwaitingSettlement` | **Undo: Queued** (unless `syncBoundary: true`); **Redo: Rejected** | Without `syncBoundary`: visible content unmodified; queued behind active fence. With `syncBoundary`: materialized to $D_c$, undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Without `syncBoundary`: none (deferred). With `syncBoundary`: `Composition` group pushed & popped to `redoStack` | Selection preserved / restored to pre-session | Queued in `pendingCommands`; with `syncBoundary`: settlement cleared, transitions `-> Idle` | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` |

---

### 7.3 Mandatory Invariant Properties of the Matrix

1. **Preimage Conservation**: No execution path may clear, overwrite, or discard an authoritative pre-session snapshot $S_0$ prior to fulfilling the history obligation for retained visible edits.
2. **Undo/Redo Symmetry & Asynchronous Settlement Barrier**: In asynchronous platform execution, calling `undo()` during active composition queues behind the active settlement fence in `AwaitingSettlement` (`action: 'QueuedBehindSettlement'`). When settlement terminates, queued commands and undos drain in causal FIFO order. If explicitly qualified with a synchronous platform boundary (`syncBoundary: true`, `settlementEvidence: { extent }`), `undo()` synchronously flushes visible text to committed storage, records the net composition edit on `undoStack`, immediately pops it to revert to pre-session snapshot $S_0$, and symmetrically preserves the composition entry on `redoStack`.
3. **Preserve-Visible-Content Teardown**: Teardown paths (`openNewConnection`, `dispose`, and `onFocusLost` with `preserve-visible-content`) materialize accepted provisional units into $D_c$ and push an explicit history entry before clearing session state or closing the connection. Visible text is never discarded silently.
4. **Unified Target Authority**: Target descriptors are computed once per transaction via `_resolveTargetDescriptor(payload)` and used identically for proposed-length validation, text mutation, marked window allocation, and receipt generation. When `payload.range` is omitted:
   - If an active marked range exists (`markedRange !== null`), the target is the active candidate window.
   - If the marked range is null (such as following nonterminal `unmark(settled: false)`), the target resolves strictly to the current selection (`[anchor, focus]`).
### 7.4 Exhaustive 85-Cell Master Contract Matrix (17 Events × 5 Lifecycle States)

The table below summarizes the audited and verified behavior across all 85 state-event combinations (audited by `test-contract-coverage-sweep.mjs` with 100% agreement between Candidate B and Independent Oracle):

| Event | `Idle` | `Composing (nonempty)` | `Composing (empty)` | `AwaitingSettlement` | `Closed` |
|---|---|---|---|---|---|
| **1. `startComposition`** | Admitted (`-> Composing`) | Rejected (`AlreadyComposing`) | Rejected (`AlreadyComposing`) | Rejected (`AlreadyComposing`) | Rejected (`ConnectionClosed`) |
| **2. `preedit`** | Admitted (opens `-> Composing`) | Admitted (updates overlay) | Admitted (updates overlay) | Admitted (resumes `-> Composing`) | Rejected (`ConnectionClosed`) |
| **3. `commit`** | Admitted (inserts `-> Idle`) | Admitted (commits `-> Idle`) | Admitted (commits `-> Idle`) | Admitted (commits `-> Idle`) | Rejected (`ConnectionClosed`) |
| **4. `partialCommit`** | Rejected (`NoActiveComposition`) | Admitted (freezes prefix) | Rejected (`InvalidLength`) | Rejected (`CandidateNotActive`) | Rejected (`ConnectionClosed`) |
| **5. `targetExpansion`** | Rejected (`NoActiveComposition`) | Admitted (expands window) | Admitted (expands window) | Rejected (`NoActiveComposition`) | Rejected (`ConnectionClosed`) |
| **6. `retarget`** | Rejected (`NoActiveComposition`) | Admitted (samples visible attrs) | Admitted (samples visible attrs) | Rejected (`NoActiveComposition`) | Rejected (`ConnectionClosed`) |
| **7. `unmark` (nonterminal)** | Rejected (`InvalidState`) | Admitted (`-> AwaitingSettlement`) | Admitted (`-> AwaitingSettlement`) | Rejected (`AlreadyUnmarked`) | Rejected (`ConnectionClosed`) |
| **8. `completeSettlement`** | Rejected (`NoActiveSettlement`) | Rejected (`CandidateStillActive`) | Rejected (`CandidateStillActive`) | Admitted (commits `-> Idle`) | Rejected (`ConnectionClosed`) |
| **9. `cancel`** | Admitted (no-op) | Admitted (token-identity rollback) | Admitted (token-identity rollback) | Admitted (rollback `-> Idle`) | Rejected (`ConnectionClosed`) |
| **10. `nativeRemoval`** | Admitted (excises `-> Idle`) | Admitted (excises `-> Idle`) | Admitted (excises `-> Idle`) | Admitted (excises `-> Idle`) | Rejected (`ConnectionClosed`) |
| **11. `onFocusLost`** | Admitted (no-op) | Admitted (strategy-directed `-> Idle`)| Admitted (strategy-directed `-> Idle`)| Admitted (strategy-directed `-> Idle`)| Rejected (`ConnectionClosed`) |
| **12. `openNewConnection`**| Admitted (epoch bumped) | Admitted (flushes `-> Idle`, epoch++) | Admitted (flushes `-> Idle`, epoch++) | Admitted (flushes `-> Idle`, epoch++) | Rejected (`ConnectionClosed`) |
| **13. `dispose`** | Admitted (`-> Closed`) | Admitted (flushes `-> Closed`) | Admitted (flushes `-> Closed`) | Admitted (flushes `-> Closed`) | Rejected (`ConnectionClosed`) |
| **14. `undo`** | Admitted (pops `undoStack`) | Admitted (queues behind barrier) | Admitted (queues behind barrier) | Admitted (queues behind barrier) | Rejected (`ConnectionClosed`) |
| **15. `redo`** | Admitted (pops `redoStack`) | Rejected (`NothingToRedo`) | Rejected (`NothingToRedo`) | Rejected (`NothingToRedo`) | Rejected (`ConnectionClosed`) |
| **16. `setSelection`** | Admitted (updates selection)| Admitted (bounds validated) | Admitted (bounds validated) | Admitted (bounds validated) | Rejected (`ConnectionClosed`) |
| **17. `dispatchCommand`** | Admitted (Applied immediately)| Admitted (queued in `pending`) | Admitted (queued in `pending`) | Admitted (queued in `pending`) | Rejected (`ConnectionClosed`) |

---
This specification is authoritative and serves as the normative baseline for Candidate B qualification in `plan-v4.md` Revision 4.9.6.

