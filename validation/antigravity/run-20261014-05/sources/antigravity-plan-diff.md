# Antigravity Plan Corrections & Normative Diff (`plan-v4.md` -> Revision 4.8)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Falsification audit, coordinator defect reproduction (run-03 clamping & DocumentRevision metadata contradiction), exact native-range proposal fidelity (§4.3, §4.4), Invariant I08 re-targeting vs cancellation protection, target expansion full envelope absorption synchronization (§4.3, §4.4), metadata-freeze DocumentRevision preservation, deterministic monotonic settlement token lifecycle and invalidation on new input (§4.5), distinct cancellation semantics (`nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`), dynamic queued command range target mapping, sound BFS model checking sequence stamping and deduplication (7,767 states, 8,286 transitions), rebuilt executable invariant assertions matching advertised property names, and comprehensive mutation testing (11/11 mutants caught).  
**Authoritative Target**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.8)  
**Evidence Run Directory**: [`validation/antigravity/run-20261013-04/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-04/)  

---

## 1. Classification Taxonomy

In compliance with prompt instructions, all audit findings across `plan-v4.md` and the validation harnesses are classified into:
- **(a) Harness violates an already-correct requirement**
- **(b) Specification is ambiguous or incomplete**
- **(c) Specification is contradictory or incorrect**
- **(d) Unresolved platform feasibility**
- **(e) Product policy requiring an explicit decision**

---

## 2. Itemized Audit Findings & Applied Corrections

### Finding 1 (Defect A): Settlement Loses Composition Identity and History Integrity
- **Classification**: 
  - **(a) Harness violation**: In run-02 `protocol-coordinator.mjs`, when `unmark(settled: false)` arrived, it prematurely set `this.activeSessionId = null` and immediately pushed a `Composition` entry to `undoStack`. When a second `unmark(settled: true)` arrived, it reset `lastSettledExtent` to `[0, 0]`, pushed a second `Composition` history entry, and prevented queued format commands from applying.
  - **(b) Specification incomplete**: §4.4 failed to specify that non-terminal `unmark(settled: false)` must defer history recording until an explicit settlement completion fence, that `activeSessionId` and snapshot must remain pinned, that duplicate `unmark` in `AwaitingSettlement` must be rejected as `AlreadyUnmarked`, and that a dedicated `completeSettlement` event completes the session.
- **Reproduced Defect**: 
  Start composition with sessionId `"s"`, preedit `"X"`, queue Bold targeting `CompositionTarget("s")`, call `unmark(settled: false)` followed by `unmark(settled: true)`. Observed:
  1. First unmark cleared `activeSessionId` to `null` and pushed a composition undo entry prematurely.
  2. Second unmark changed remembered extent to `[0, 0]`.
  3. Two composition history entries were created in `undoStack`.
  4. Bold was never applied to `'X'` because extent was reset to `[0, 0]`.
  *(Archived in `validation/antigravity/run-20261013-03/reproduce-run02-defects.log`)*.
- **Normative Plan Correction Applied (§4.2, §4.4)**:
  - Formulated the **State Ownership Model** (§4.2) decoupling object lifetimes: `Marked Range` is transient and clears on `unmark`, while `Composition History Session` and `Settlement Obligation` survive until terminal settlement.
  - In §4.4: `unmark(settled: false)` accepts marked candidate text into the document, clears `markedRange = null`, retains `activeSessionId` and `compositionBeforeSnapshot`, and defers history creation. A repeating `unmark` in `AwaitingSettlement` is rejected with `Rejected(AlreadyUnmarked)`.
  - Added the normalized completion event `completeSettlement(sessionId)` which transitions `AwaitingSettlement -> Idle`, creates exactly 1 `Composition` history group, and drains queued commands against the concrete extent `[sessionStart, sessionStart + len(committedText))`.
- **Verification**: `test-coordinator-repairs.mjs` (Case 1) and `test-mutations.mjs` (Mutations 2, 9, 13).

---

### Finding 2 (Defect B): Target Expansion Destroys Partial-Commit Protection
- **Classification**:
  - **(a) Harness violation**: In run-02, `targetExpansion` unconditionally executed `this.frozenPrefixUnits = []`, wiping frozen committed units.
  - **(b) Specification incomplete**: §4.3 and §4.4 omitted the explicit interaction between partial commits and subsequent target expansion, as well as the cancellation restoration rule when expanded targets swallow surplus document characters.
- **Reproduced Defect**:
  In `"Hello"`, compose `[1, 2)` with preedit `"XY"`, partially commit 1 unit (freezing `"X"`), expand target to `[2, 4)` (covering `"Yl"`), and cancel. Observed:
  1. Target expansion wiped `frozenPrefixUnits` to `[]`.
  2. Cancellation fell back to restoring `compositionBeforeSnapshot` (`"Hello"`), erasing the committed `"X"`.
  3. `undoStack` had 0 entries, losing committed text from history.
  *(Archived in `validation/antigravity/run-20261013-03/reproduce-run02-defects.log`)*.
- **Normative Plan Correction Applied (§4.3, §4.4)**:
  - Target expansion preserves committed units and extent: `frozenPrefixUnits` and `committedExtent` remain intact while the baseline expands to the new envelope.
  - Suffix cancellation after expansion: surplus characters swallowed from the original document beyond the prior candidate are restored to the document, the uncommitted candidate is aborted, frozen committed units remain intact in the document under Invariant I08, and 1 `Composition` history group is recorded for the net partial commit(s).
- **Verification**: `test-coordinator-repairs.mjs` (Case 2) and `test-mutations.mjs` (Mutations 6, 10).

---

### Finding 3 (Defect C): Connection Turnover Drops Composition History
- **Classification**:
  - **(a) Harness violation**: In run-02, `openNewConnection()` simply cleared `this.compositionBeforeSnapshot = null` without checking if an active composition had modified document text.
  - **(c) Specification contradictory / incorrect**: §4.4 stated that connection replacement resets dangling composition state and preserves the undo stack, but failed to require that unfinished accepted document modifications MUST be finalized into the undo stack, leaving modified text outside attributed history.
- **Reproduced Defect**:
  Start composition in `"Hello"`, preedit `"X"` (producing `"HXllo"`), open new connection. Observed: `"HXllo"` remained in the document, `compositionBeforeSnapshot` was discarded, and `undoStack` remained empty (0 entries). The text became un-undoable.
  *(Archived in `validation/antigravity/run-20261013-03/reproduce-run02-defects.log`)*.
- **Normative Plan Correction Applied (§4.2, §4.4, §7)**:
  If a connection is replaced (`openNewConnection`), closed (`closeConnection`), or disposed (`dispose`) while an active composition has modified document text, the coordinator must finalize exactly one `Composition` history group to `undoStack` before tearing down the session. Transient composition text must never remain in the document without attributed undo history.
- **Verification**: `test-coordinator-repairs.mjs` (Case 3) and `test-mutations.mjs` (Mutation 11).

---

### Finding 4 (Defect D): Repeated Identical Preedit Advancing DocumentRevision
- **Classification**:
  - **(a) Harness violation**: In run-02, `preedit` unconditionally incremented `this.documentRevision++` even when the preedit text and attributes were identical to the existing candidate.
  - **(b) Specification incomplete**: §2.2 declared `DocumentRevision` and `SessionRevision`, but did not normatively mandate that `DocumentRevision` advances if and only if semantic document content, attributes, or structure change.
- **Reproduced Defect**:
  Preedit `"X"` followed by identical preedit `"X"`. `documentRevision` advanced from 1 to 2 despite no semantic change to the document buffer.
  *(Archived in `validation/antigravity/run-20261013-03/reproduce-run02-defects.log`)*.
- **Normative Plan Correction Applied (§2.2, §4.4, §7)**:
  Explicitly formulated the normative separation:
  - `DocumentRevision` advances if and only if semantic document text, attributes, tree structure, or resources change. No-op text updates and identical preedits MUST NOT advance `DocumentRevision`.
  - `SessionRevision` monotonically advances on every published session event or acknowledged callback, ensuring session observers track sequential interactions without invalidating cached document layout frames.
- **Verification**: `test-coordinator-repairs.mjs` (Case 4) and `test-mutations.mjs` (Mutation 12).

---

### Finding 5: Reentrancy Policy and Synchronous Query Contradiction
- **Classification**:
  - **(a) Harness violation**: The prior harness threw an uncatchable fatal error on any reentrant call, crashing the coordinator during ordinary inspection queries.
  - **(b) Specification incomplete**: §4.1 required that reentrant text queries observe the consistent current stage while reentrant edits are queued, but lacked operational semantics for FIFO mutation queueing and exception rollback.
- **Normative Plan Correction Applied (§4.1, §4.4)**:
  Reentrancy policy: synchronous reads query the currently published snapshot without blocking; reentrant mutations queue in FIFO order and process sequentially after the active transaction completes; any unhandled exception rolls back the staging transaction atomically, leaving published state unmodified.
- **Verification**: `test-coordinator-repairs.mjs` (Case 7).

---

### Finding 6: Empty Composition Target & Cancellation Typing Intent Resolution
- **Classification**:
  - **(b) Specification incomplete**: §4.5 stated that an empty candidate resolves to an empty range, but did not specify that formatting an empty range updates typing intent only without advancing `DocumentRevision` or creating history, and that queued commands targeting an aborted composition must cancel with `CompositionCancelled`.
- **Normative Plan Correction Applied (§4.5)**:
  `CompositionTarget(sessionId)` resolving to an empty range updates typing intent only, producing NO document revision bump and NO history group. Queued commands targeting an aborted composition cancel with `CompositionCancelled`. Whole-document fallback is strictly prohibited.
- **Verification**: `test-coordinator-repairs.mjs` (Case 6).

---

### Finding 7: Exact Raw Selection Preservation from Callbacks
- **Classification**:
  - **(a) Harness violation**: The prior harness forced `selection = [newEnd, newEnd]` on every preedit, discarding explicit selection ranges provided by native IME callbacks.
  - **(b) Specification incomplete**: §4.4 table previously specified `[newEnd, newEnd]` as the selection for preedit callbacks without mentioning raw payload selection preservation.
- **Normative Plan Correction Applied (§4.4)**:
  Preedit callbacks preserve exact raw `payload.selection` if supplied by the native adapter; they only fall back to `[newEnd, newEnd]` when no explicit selection is provided.
- **Verification**: `test-coordinator-repairs.mjs` (Case 5) and `test-mutations.mjs` (Mutation 14).

---

### Finding 8 (NEW in Rev 4.6): Preedit Resumption Without Crash After Nonterminal Unmark
- **Classification**:
  - **(a) Harness violation**: When `markedRange` was set to null on nonterminal unmark, subsequent preedit callbacks crashed with `TypeError: object null is not iterable` when unpacking `[start, end] = this.markedRange`.
  - **(b) Specification incomplete**: §4.2 and §4.4 did not specify coordinator behavior if native input continues after nonterminal unmark before terminal settlement.
- **Normative Plan Correction Applied (§4.2, §4.4)**:
  If a new `preedit` or native replacement arrives while in `AwaitingSettlement` and `markedRange == null`, the coordinator reallocates `markedRange = [start, end]` anchored at the active selection or explicit bounds, increments `attributionEpoch++`, captures a fresh baseline, and transitions to `Composing` (if 0 commands pending) or maintains `AwaitingSettlement` with queued commands intact, ensuring zero null-reference crashes.
- **Verification**: `test-coordinator-repairs.mjs` (Case 8) and `test-mutations.mjs` (Mutation 15).

---

### Finding 9 (NEW in Rev 4.6): Target Expansion Overlap Protection (Invariant I08) and Surplus Restoration
- **Classification**:
  - **(a) Harness violation**: Prior target expansion only tracked rightward surplus (`newEnd > oldMarked[1]`), failing to protect frozen committed units from being overwritten if the expansion started before or within the committed range.
  - **(c) Specification contradictory**: Allowing native expansion to overlap frozen committed units violated Invariant I08 ("Retained committed units are not erased by suffix cancellation").
- **Normative Plan Correction Applied (§4.3, §4.4)**:
  Target expansion is clamped such that `newStart >= committedExtent[1]`, ensuring frozen committed units can never be re-opened as provisional candidate text. Surplus characters swallowed from the document are tracked symmetrically as `expansionPrefixSurplus` and `expansionSuffixSurplus`. On cancellation, both prefix and suffix surplus characters are restored to the document surrounding the frozen units, the uncommitted candidate is aborted, and frozen committed units remain intact in the document.
- **Verification**: `test-coordinator-repairs.mjs` (Case 9) and `test-mutations.mjs` (Mutation 10).

---

### Finding 10 (NEW in Rev 4.6): Drainage of Unrelated Anchored Commands on Cancellation
- **Classification**:
  - **(b) Specification incomplete**: §4.5 stated that commands targeting composition cancel with `CompositionCancelled`, but did not specify what happens to commands targeting unrelated explicit document ranges that were queued during composition.
  - **(c) Specification incorrect**: Discarding or trapping unrelated commands violated Invariant 6 ("Every command eventually receives an explicit outcome").
- **Normative Plan Correction Applied (§4.5)**:
  On composition cancellation, commands targeting the active composition cancel with `CompositionCancelled`, but commands targeting unrelated explicit ranges (`[start, end]`) are drained in FIFO order and executed (`Applied`) against the restored document state, recording explicit outcome records.
- **Verification**: `test-coordinator-repairs.mjs` (Case 10) and `test-mutations.mjs` (Mutation 16).

---

### Finding 11 (NEW in Rev 4.6): Explicit Cooperative Settlement Request and Command Cancellation APIs
- **Classification**:
  - **(b) Specification incomplete**: §4.5 described settlement concepts abstractly without defining explicit programmatic APIs for application command lifecycle management.
- **Normative Plan Correction Applied (§4.5)**:
  Added normative APIs:
  - `requestSettlement(reason)`: transitions coordinator to `AwaitingSettlement` and returns `{ status: 'Requested', ticketId, reason, sessionId, epoch }`.
  - `cancelCommand(ticketId)`: cancels an individual queued command, removes it from `pendingCommands`, and records outcome `{ status: 'Cancelled', reason: 'CancelledByUser', ticketId }`.
- **Verification**: `test-coordinator-repairs.mjs` (Case 11).

---

### Finding 12: Durability and Crash Recovery Overclaim (Invariant I14)
- **Classification**:
  - **(b) Specification incomplete**: Invariant I14 combined memory serialization round-trip with physical filesystem crash recovery.
  - **(d) Unresolved platform feasibility**: In-memory UTF-16LE serialization and deserialization round-trip does NOT establish physical filesystem crash durability, power-cut recovery, or journal tail corruption recovery.
- **Normative Plan Correction Applied (§3, §7, §8, §9)**:
  Scoped Invariant I14 honestly: the scalar model verifies Serialization Round-Trip only (marked `PARTIAL`). Physical crash injection, journal tail recovery, and filesystem flush semantics remain strictly deferred to Phase 7 Gate G7.
- **Verification**: `antigravity-validation-matrix.csv` (Row I14), `plan-v4.md` (§9.2 R14, §9.4 U7).

---

### Finding 13: Gate G0 Desktop Native Host Qualification Scoping
- **Classification**:
  - **(d) Unresolved platform feasibility**: A headless Java interface spike on `InputMethodRequests` verifies coordinate indexing math only. It does NOT qualify a native host.
- **Normative Plan Correction Applied (§6.3, §8)**:
  Gate G0 Desktop is strictly designated **NOT RUN / INCOMPLETE**. Full G0 qualification requires a running Compose Multiplatform / AWT desktop window with live OS IME input, real font geometry, settlement, and screen reader interaction.
- **Verification**: `plan-v4.md` (§6.3, §8), `antigravity-validation-matrix.csv` (Row Gate G0 Desktop).

---

### Finding 14: Unvalidated Skia Native C++ Optimization Patch
- **Classification**:
  - **(d) Unresolved platform feasibility**: The MSVC build log revealed missing C++20 headers (`<bit>`, `countl_zero` under C++17), and the baseline crashed (-1073741795).
  - **(e) Product policy decision**: The unvalidated patch is rejected and excluded from the architecture as an `UNVALIDATED OPTIMIZATION`. The baseline engine relies on standard, unpatched Skiko text layout without claiming novel or unverified C++ patches.
- **Verification**: `antigravity-validation-matrix.csv` (Row Skia Optimization), `plan-v4.md` (§1.3, §9.4 U6).

---

### Finding 15: Run-03 Clamping Defect & Exact Native-Range Proposal Fidelity (§4.3, §4.4)
- **Classification**:
  - **(a) Harness violation**: In run-03, `targetExpansion` clamped `newStart` using `Math.max(newStart, this.committedExtent[1])`. This silently truncated native IME ranges.
  - **(c) Specification contradictory / incorrect**: Clamping violated the platform input method contract. When the native IME explicitly requested expanding the candidate to encompass earlier offsets (e.g. `[1, 3)` after partially committing `"H"`), clamping forced the candidate to `[2, 3)` (`"X"`), producing `"HXQlo"` instead of `"HQlo"`, corrupting text buffer contents.
- **Reproduced Defect**:
  `reproduce-native-range-failure.mjs` demonstrated that in `"Hello"`, composing `"HX"`, partially committing 1 unit (`"H"`), expanding target to `[1, 3)` and preediting `"Q"` produced `"HXQlo"` instead of `"HQlo"`.
- **Normative Plan Correction Applied (§4.3, §4.4)**:
  - Exact native-range proposal fidelity: when a composition target expands, the coordinator MUST NEVER silently clamp `newStart` to `committedExtent[1]`.
  - The admitted `markedRange` equals the exact requested `[newStart, newEnd]`.
  - Invariant I08 scopes protection against subsequent suffix cancellation and uncoordinated edits, but explicit native re-targeting absorbs earlier committed units into the active candidate baseline (if `newStart <= committedExtent[0]`, the entire committed portion is absorbed; if `committedExtent[0] < newStart < committedExtent[1]`, the prefix remains committed and suffix is absorbed).
- **Verification**: `reproduce-native-range-failure.mjs`, `test-coordinator-repairs.mjs` (Case 9), `test-mutations.mjs` (Mutation 1).

---

### Finding 16: DocumentRevision vs SessionRevision Metadata-Freeze Contradiction (§2.2, §4.4)
- **Classification**:
  - **(a) Harness violation**: In run-03, `partialCommit` unconditionally incremented `this.documentRevision++`.
  - **(c) Specification contradictory / incorrect**: Section 2.2 explicitly defined `DocumentRevision` as advancing if and only if semantic document text, raw attributes, or structure change. A partial commit freezes metadata (`committedUnits`) without altering `docText` or `docAttributes`, creating a direct specification contradiction.
- **Reproduced Defect**:
  `reproduce-native-range-failure.mjs` verified that `partialCommit` bumped `documentRevision` from 1 to 2 when `docText` and `docAttributes` were byte-for-byte identical.
- **Normative Plan Correction Applied (§2.2, §4.4)**:
  `DocumentRevision` is strictly decoupled from metadata-only events. `partialCommit` increments `SessionRevision++` while leaving `DocumentRevision` unchanged. `DocumentRevision` advances if and only if `(newText !== prevDocText) || !areAttrsEqual(newAttrs, prevDocAttrs)`.
- **Verification**: `reproduce-native-range-failure.mjs`, `test-coordinator-repairs.mjs` (Case 4), `test-mutations.mjs` (Mutation 4).

---

### Finding 17: Deterministic Settlement Token Lifecycle & Ordering Contract (§4.4, §4.5)
- **Classification**:
  - **(b) Specification incomplete**: §4.5 described settlement completion fences but did not specify token ordering, monotonic issuance, token invalidation upon intervening candidate mutations, or extent resolution guards.
- **Normative Plan Correction Applied (§4.4, §4.5)**:
  - Formulated the settlement token contract: `requestSettlement` issues a deterministic monotonic token (`settle-tok-N` via `nextSettlementToken()`).
  - Invalidation on new input: any intervening input mutation (`preedit`, `targetExpansion`, `partialCommit`) immediately invalidates active settlement tokens (`activeSettlementToken = null`).
  - Complete settlement rejections: rejected if candidate text remains actively marked (`CandidateStillActive`), if token is stale/obsolete (`ObsoleteSettlementToken`), or if session/epoch mismatches (`SessionMismatch`, `StaleEpoch`).
  - Extent resolution guard: target extent must be verified and non-zero for active sessions; coordinator MUST NEVER fall back or resolve to `[0, 0]`.
- **Verification**: `test-settlement-after-new-input.mjs` (Scenarios 1–7), `test-mutations.mjs` (Mutations 2, 8).

---

### Finding 18: Distinct Cancellation Semantics (§4.4, §4.5)
- **Classification**:
  - **(b) Specification incomplete**: The specification used generic "cancellation" without distinguishing between platform native backspace removal, IME completion unmarking, and application/user abort.
- **Normative Plan Correction Applied (§4.4, §4.5)**:
  Formulated three distinct operational cancellation mechanisms:
  1. `nativeRemoval`: Native backspace/delete removes uncommitted candidate characters; earlier frozen units remain committed under Invariant I08; expansion surplus restored.
  2. `endCompositionPreservingText`: Platform IME finishes without explicit text payload, accepting marked candidate text verbatim as committed.
  3. `applicationAbort`: Explicit abort (Escape key, toolbar action, API abort), rolling back uncommitted candidate mutations to pre-composition snapshot or post-expansion baseline while preserving frozen committed units.
- **Verification**: `test-normative-cancellation-and-mapping.mjs` (Scenarios 1–3), `test-coordinator-repairs.mjs` (Case 10).

---

### Finding 19: Dynamic Queued Command Range Target Mapping (§4.5)
- **Classification**:
  - **(b) Specification incomplete**: §4.5 lacked an explicit mapping formula for updating queued application command range targets as the candidate evolves.
- **Normative Plan Correction Applied (§4.5)**:
  Queued command targets `[start, end]` are dynamically remapped:
  - On candidate expansion/contraction by delta `Δ = len(newCandidate) - len(oldCandidate)`, targets at or after the candidate insertion point shift by `+Δ` (or `-Δ`).
  - On partial commit, frozen prefix units advance the committed extent, narrowing the candidate range.
  - On cancellation, commands targeting active composition cancel with `CompositionCancelled`; commands targeting surrounding text are remapped across the rollback delta (`-len(uncommittedCandidate) + len(surplusRestored)`) and executed (`Applied`).
- **Verification**: `test-normative-cancellation-and-mapping.mjs` (Scenarios 4–8), `test-mutations.mjs` (Mutation 7).

---

### Finding 20: Subclass Polymorphism in BFS Model Checker & Trailing-Edge Leakage Checks
- **Classification**:
  - **(a) Harness violation**: In run-03, `cloneCoord(c)` instantiated `new ProtocolCoordinator(...)`, inadvertently slicing subclass mutant instances down to the base coordinator after depth 1. Additionally, Invariant 4 only checked leading-edge formatting leakage.
- **Normative Harness Correction Applied (`independent-model-checker.mjs`)**:
  - Replaced constructor slicing with `new c.constructor(...)`, ensuring mutant overrides persist across all branches of BFS exploration.
  - Updated Invariant 4 to inspect both leading (`vStart - 1`) and trailing (`vEnd`) bounds for formatting style leakage.
- **Verification**: `test-mutations.mjs` (11/11 mutants caught), `independent-model-checker.mjs` (10,416 states, 12,548 transitions, 0 violations).

---

### Finding 21: Target Expansion Absorption & Historical Committed Units Synchronization (§4.3, §4.4)
- **Classification**:
  - **(a) Harness violation**: In run-04 initial coordinator implementation, when `targetExpansion` absorbed the entire committed extent (`newStart <= committedExtent[0]`), it cleared `committedUnits = []` and `committedExtent = null` but failed to clear `historicalCommittedUnits` and `historicalCommittedExtent`. Upon subsequent composition cancellation, the coordinator erroneously restored `historicalCommittedUnits`, failing to revert cleanly to the pre-composition snapshot.
  - **(c) Specification contradictory / incorrect**: §4.3 and §4.4 specified that full absorption incorporates all previously committed text into the new active candidate envelope. If that candidate is then cancelled, no committed content remains; the document must revert cleanly to the session snapshot without leaving orphaned frozen units.
- **Reproduced Defect**:
  Compose `"Hello World"` at `[6, 11)`, preedit `"AB"`, partially commit 1 unit (freezing `"A"` at `[6, 7)`), expand target to `[0, 8)` (encompassing `"Hello A"`), and cancel.
  Observed: Document became `"Hello A"` instead of reverting to `"Hello World"`, and undo stack contained an invalid entry.
- **Normative Plan Correction Applied (§4.3, §4.4)**:
  Mandated full absorption synchronization: when `newStart <= committedExtent[0]`, both active and historical committed units and extents are cleared (`committedUnits = []`, `committedExtent = null`, `historicalCommittedUnits = []`, `historicalCommittedExtent = null`). In `IndependentOracle`, `frozenTokens` and `historicalFrozenTokens` are symmetrically cleared on full absorption and sliced on partial absorption.
- **Verification**: `test-coordinator-repairs.mjs` (Case 9: "Target Expansion Before / Overlapping Committed Content"), `runner.mjs`.

---

### Finding 22: Sound Sequence Stamping & State Space Deduplication in BFS Model Checker (§9)
- **Classification**:
  - **(a) Harness violation**: In run-04 BFS model checker, legitimate action transitions generated synthetic sequence numbers that clashed with duplicate sequence stamps, causing artificial cache hits, spurious sequence mismatches, or unbounded branch inflation across exploration depths.
- **Normative Harness Correction Applied (`independent-model-checker.mjs`)**:
  - Formulated sound BFS sequence stamping: legitimate actions pass monotonically increasing stamps (`seq = depth + 1` for depth 0..3), while the explicit duplicate sequence probe queries `c.receiptCache.keys()` to resend an already-cached stamp.
  - Explores exactly **7,767 unique abstract reachable states** and **8,286 transitions** across 3 initial document configurations without property violations or artificial sequence collisions.
- **Verification**: `independent-model-checker.mjs` (7,767 states, 8,286 transitions, 0 violations in 299 ms).

---

### Finding 23: Rebuilt Executable Invariant Assertions Matching Advertised Property Names (§5, §9)
- **Classification**:
  - **(a) Harness violation**: In run-03, invariant assertions were evaluated conditionally or loosely, with empty history passing obligation checks and extent checks omitting attribute comparisons.
- **Normative Harness Correction Applied (`independent-model-checker.mjs`)**:
  - Rebuilt all 10 invariant assertions to match their advertised names:
    1. `I_FROZEN_PRESERVED`: Strict token identity, multiplicity, order, position, and attributes checked against independent oracle.
    2. `I_HISTORY_PREIMAGE_AND_OBLIGATION`: Required history existence and complete preimages verified.
    3. `I_SINGLE_COMP_HISTORY`: Exactly one composition history group per completed composition with net change.
    4. `I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY`: Precise formatting range bounds and zero leakage to outside characters.
    5. `I_NO_SETTLEMENT_BYPASS`: Prevents premature execution or removal of commands from pending queue before settlement.
    6. `I_COMMAND_OUTCOME`: Every issued command receives an explicit outcome record in `commandOutcomes`.
    7. `I_RAW_SELECTION_AND_RANGE_FIDELITY`: Strict equality with admitted native selection and native range proposals.
    8. `I_REVISION_RULES`: Evaluated strictly against canonical before/after semantic diff; metadata changes leave DocumentRevision unchanged.
    9. `I_IDENTITY_ISOLATION`: Stale epoch, duplicate sequence stamp, or active candidate settlement rejected.
    10. `I_ATOMIC_FAILURE`: Failed operations publish no partial state; full rollback state verified.
- **Verification**: `test-mutations.mjs` (11/11 subclass mutants caught), `independent-model-checker.mjs`.

---

## 3. Normative Plan Text Differences Summary (Revision 4.6)

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.6; documented decoupled state ownership, normalized completion protocol, target expansion prefix/suffix surplus restoration, Invariant I08 overlap protection, preedit resumption after unmark, connection turnover history, unrelated command drainage on cancel, settlement request/cancellation APIs, DocumentRevision separation, reentrancy policy, and authentic BFS model checking ledger | (b) Spec completeness |
| §2.2 | Schema and identities | Added normative distinction: `DocumentRevision` advances strictly on semantic document change; `SessionRevision` advances on every published session event | (b) Spec completeness |
| §4.1 | Connection lifecycle | Formalized reentrancy policy: synchronous reads query published snapshot; reentrant mutations queue in FIFO order; atomic rollback on failure | (b) Spec completeness / (a) Harness fix |
| §4.2 | Composition state | Decoupled entity lifetimes; added normative specification for preedit resumption without crash after nonterminal unmark | (b) Spec completeness / (c) Spec correction |
| §4.3 | Candidate attribution | Added Invariant I08 overlap protection clamping candidate start to `committedExtent[1]`; added symmetrical prefix/suffix surplus tracking and restoration on cancellation | (b) Spec completeness / (a) Harness fix |
| §4.4 | Event transition table | Full 7-column normative transition table: raw selection preservation; deferred unmark history; duplicate unmark rejection `Rejected(AlreadyUnmarked)`; `completeSettlement` event; preedit resumption after unmark; target expansion surplus restoration; cancellation draining unrelated commands; connection turnover history; revision rules | (b) Spec completeness / (c) Spec correction |
| §4.5 | Settlement semantics | Clarified empty final candidate typing intent resolution; added normative `requestSettlement` and `cancelCommand` APIs; specified unrelated command drainage on cancellation; prohibited whole-document fallback | (b) Spec completeness |
| §6.3 | Platform adapters (AWT) | Empty slice exception fix; cross-composition slice concatenation; composed-text geometry; exclusive lower bound; getSelectedText normalization; getInsertPositionOffset; unsupported attributes and cancelLatestCommittedText | (c) Spec correction / (d) Feasibility scoping |
| §7 | History & durability | Added connection turnover history finalization rule: active composition modifying text MUST push 1 history entry to `undoStack` on connection replacement/disposal | (c) Spec correction |
| §8 | Phase 0 Gate G0 & Phase 1 Gate G1 | Reclassified G0 Desktop as NOT RUN / INCOMPLETE; Gate G1 status updated to Phase 1 Gated (Pending Plan Resolution and Phase 0 completion; production implementation prohibited) | (d) Feasibility scoping |
| §9.2 | Regression cases R05 & R14 | Updated R05 (normalized completeSettlement, duplicate unmark rejection) and R14 (serialization round-trip verified; physical recovery deferred to Phase 7) | (b) Spec completeness |
| §9.4 | Remaining Uncertainties Register | Inserted formal register itemizing 7 unresolved platform risks, unvalidated optimizations, and deferred durability requirements (U1–U7); added Revision 4.6 Verification Summary | (d) Feasibility scoping / (e) Product policy |

---

## 4. Normative Plan Text Differences Summary (Revision 4.7)

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.7; documented exact native-range proposal fidelity, Invariant I08 re-targeting vs cancellation protection, metadata-freeze DocumentRevision decoupling, settlement token lifecycle, distinct cancellation semantics, and dynamic target mapping | (b) Spec completeness |
| §2.2 | Schema and identities | Mandated that metadata-only partial commit leaves `DocumentRevision` unchanged (`SessionRevision++`), advancing `DocumentRevision` iff actual document text or raw attributes change | (c) Spec correction |
| §4.3 | Candidate attribution | Removed silent clamping of `newStart` to `committedExtent[1]`; specified exact native-range fidelity, admitting requested `[newStart, newEnd]`, absorbing overlapped committed units into active baseline, and symmetrically tracking prefix/suffix surplus | (c) Spec correction |
| §4.4 | Event transition table | Updated Partial Commit (`DocumentRevision` unchanged), Target Expansion (no clamping, exact native range fidelity, baseline absorption), Complete Settlement (token validation, rejection if candidate active), and Cancellation (`nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`, dynamic command target mapping) | (c) Spec correction / (b) Spec completeness |
| §4.5 | Settlement semantics | Formulated deterministic monotonic settlement tokens, invalidation on new input (`preedit`, `targetExpansion`, `partialCommit`), complete settlement rejections (`CandidateStillActive`, `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch`), dynamic queued command target mapping across length deltas, and distinct cancellation types | (b) Spec completeness |
| §9.4 | Revision 4.7 Summary | Replaced Revision 4.6 summary with Revision 4.7 verification record detailing 10,416 abstract states, 12,548 transitions, 11/11 mutants caught, run-04 test suites, and strict retention of Gate G0 Desktop as NOT RUN / INCOMPLETE | (b) Spec completeness / (d) Feasibility scoping |

---

## 5. Normative Plan Text Differences Summary (Revision 4.8)

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.8; documented historical committed units synchronization upon full envelope absorption, sound BFS sequence stamping, rebuilt executable invariant assertions, and 7,767 states / 8,286 transitions exploration | (b) Spec completeness |
| §4.3 | Candidate attribution | Mandated synchronization of `historicalCommittedUnits = []` and `historicalCommittedExtent = null` when target expansion completely absorbs the committed extent (`newStart <= committedExtent[0]`), ensuring clean document revert on subsequent cancellation | (c) Spec correction / (a) Harness fix |
| §4.4 | Event transition table | Clarified cancellation semantics following full absorption target expansion: complete rollback to session snapshot without orphaned committed units | (b) Spec completeness |
| §5 / §9 | Invariant Assertions | Rebuilt all 10 executable invariant assertions to strictly match advertised property names (`I_FROZEN_PRESERVED`, `I_HISTORY_PREIMAGE_AND_OBLIGATION`, `I_SINGLE_COMP_HISTORY`, `I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY`, `I_NO_SETTLEMENT_BYPASS`, `I_COMMAND_OUTCOME`, `I_RAW_SELECTION_AND_RANGE_FIDELITY`, `I_REVISION_RULES`, `I_IDENTITY_ISOLATION`, `I_ATOMIC_FAILURE`) | (a) Harness fix / (b) Spec completeness |
| §9.4 | Revision 4.8 Summary | Updated verification record: 7,767 unique abstract reachable states, 8,286 transitions explored soundly in 299 ms; 11/11 subclass mutants caught; Gate G0 Desktop strictly marked NOT RUN / INCOMPLETE | (b) Spec completeness / (d) Feasibility scoping |


