# Antigravity Plan Corrections & Normative Diff (`plan-v4.md` -> Revision 4.9.5)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Falsification audit, coordinator defect reproduction and repair (Failures A–I from run-06, Findings A–F from run-07, History/Lifecycle Findings A–D from run-08, and Target-Mapping/Admission Defects 1–4 from run-09), Candidate B segment-mapped overlay consolidation, exact native-range proposal fidelity (§4.3, §4.4), universal rejection atomicity (Invariant 10), executable undo/redo without truthiness fallback corruption (§4.4), disposal visible content preservation under teardown (§4.1, §4.5), native removal undo history across lifecycle states (§4.2, §4.4), resumed preedit unified target resolution (§4.2), surviving token-identity rollback target mapping (Defect 1), idle command execution and outcome tickets (Defect 2), cross-epoch receipt isolation (Defect 3), explicit ordered queue settlement policy on undo (Defect 4), UAX #29 fixture details (Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0), exhaustive 85-cell contract coverage sweep (17 events × 5 states), sound BFS model checking across 139,746 unique states & 196,532 transitions at depth 4 with 0 invariant violations, and comprehensive mutation testing (40/40 positive controls, 40/40 clean assertion kills, 0 unrelated crashes).  
**Authoritative Target**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.5)  
**Evidence Run Directory**: [`validation/antigravity/run-20261014-09/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-09/)  

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

---

## 6. Normative Plan Text Differences Summary (Revision 4.9.1)

**Date**: 14 October 2026  
**Scope**: Composition Subsystem Architectural Consolidation, Resolution of Semantic Gaps (§4.1–§4.7), and Selection of Candidate B (Segment-Mapped Overlay) over Candidate A (Flat Buffer).  
**Evidence Run**: [`validation/antigravity/run-20261014-05/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-05/)

### 6.1 Audit Findings & Applied Corrections

#### Finding 18: Architectural Invalidation of Flat Composition Buffer (Candidate A)
- **Classification**: **(c) Specification is contradictory or incorrect**
- **Defect Description**: The v4 flat live-document buffer conflated provisional replacements with original text, using scalar `originEnd` and surplus variables. When a partial commit cut preceding text after absorbing original characters (Astra Critique 1: `abcd -> aXYZcd -> partialCommit Y -> cancel`), Candidate A committed `"aXY"` but discarded absorbed original `"c"` (`aYcd` instead of `aXYcd`), failing Invariant I08. Furthermore, Candidate A failed 4 of 14 regression fixtures and violated Invariant 4 at depth 1 in BFS model checking (`'Helo' !== 'Hello'`).
- **Normative Plan Correction Applied (§1, §4.2, §4.3, §4.4)**: Replaced the flat buffer with Candidate B: Separated Committed Storage $D_c$ (governed by the persistent B+ tree) and a piecewise segment-mapped provisional overlay $\mathcal{O} = (\text{blockId}, \text{segments})$ where each segment maps explicitly to its $D_c$ replacement footprint `OverlaySegment(dcStart, dcEnd, units)`. Visible document $D_v = \Pi(D_c, \mathcal{O})$ is projected on demand. Cancelling $\mathcal{O}$ instantly reveals $D_c$ with zero surplus variables.

#### Finding 19: Zero-Length Deletion Tracking at Commit Cut Boundaries (Gap 4.4)
- **Classification**: **(b) Specification is incomplete**
- **Defect Description**: In `abc -> ac -> partialCommit 1 -> cancel`, a provisional deletion of `'b'` occurred at offset 1. When cut $p_{\text{cut}} = 1$ committed `'a'`, Candidate A permanently deleted `'b'` (`ab` instead of `abc`). In retargeted cut cancellation (`abcde -> bd -> partialCommit 1 -> cancel`), Candidate A permanently deleted original `'d'` (`abce` instead of `abcde`).
- **Normative Plan Correction Applied (§4.4)**: Formulated the exact zero-length deletion commitment rule: a zero-length deletion segment ($units = \emptyset, dcStart < dcEnd$) belongs to the committed effect iff $p_{\text{cut}} > p_{\text{deletion}}$ (cut strictly passes deletion point) OR ($p_{\text{cut}} == p_{\text{deletion}}$ AND no visible session units remain after $p_{\text{cut}}$ in the active composition session). If $p_{\text{cut}} \le p_{\text{deletion}}$ while visible session units remain after $p_{\text{cut}}$, the deletion belongs to the uncommitted overlay and cancels cleanly, fully restoring deleted characters.

#### Finding 20: Multi-Unit Replacement Rollback Ownership Partitioning (Gap 4.5)
- **Classification**: **(b) Specification is incomplete**
- **Defect Description**: When an original character `'b'` was provisionally replaced with `"XYZ"`, retargeted at interior `'Y'` to `"UVW"`, and partially committed at `'U'`, Candidate A corrupted rollback state, outputting `"aUc"` instead of `"aXUc"`.
- **Normative Plan Correction Applied (§4.4, §4.5)**: Formulated the single-owner rollback rule: committing prefix $P_{\text{commit}}$ into $D_c$ replaces the original $D_c$ span, satisfying and extinguishing the original rollback obligation to `'b'`. The uncommitted tail $U_{\text{tail}}$ converts to a pure insertion segment at the new committed boundary in $D_c'$, ensuring rollback obligations are never duplicated or lost across multiple nested interior replacements.

#### Finding 21: Preedit Resumption Crash After Nonterminal Unmark (Gap 4.7)
- **Classification**: **(c) Specification is contradictory or incorrect**
- **Defect Description**: Following a non-terminal unmark (`unmark(settled: false)`), `markedRange` was set to `null`. When a subsequent `preedit` arrived, Candidate A crashed with `TypeError: object null is not iterable`.
- **Normative Plan Correction Applied (§4.2, §4.4)**: Coordinator reallocates the marked window anchored at the selection or payload bounds, captures a fresh baseline, advances the epoch, and resumes composition without throwing.

#### Finding 22: Formatting Target vs Insertion Provenance & Disjoint Islands (Gap 4.1)
- **Classification**: **(b) Specification is incomplete**
- **Normative Plan Correction Applied (§4.1, §4.3)**: Application commands targeting active composition format the semantic entity (`CompositionTarget(sessionId)`). If combining marks are inserted, formatting applies outward grapheme cluster expansion. If the final candidate outcome is empty, typing intent is updated with the patch, but no formatting undo group is created. If retargeted across disjoint islands, commands format only the tracked constituent candidate islands with per-island grapheme expansion, explicitly leaving intervening unedited text untouched (no convex hull formatting).

#### Finding 23: Retargeting Boundary Isolation & Clamp Rejection (Gap 4.2)
- **Classification**: **(b) Specification is incomplete**
- **Normative Plan Correction Applied (§4.2, §4.3)**: Defined strict boundary semantics across disjoint, touching, partial overlap, and containment retargeting. Committed text outside the new target is strictly protected against cancellation. Out-of-bounds or invalid target ranges are rejected (`InvalidTargetRange`) and NEVER silently clamped.

#### Finding 24: Attribution Detour and Suffix Epoch (Gap 4.6)
- **Classification**: **(b) Specification is incomplete**
- **Normative Plan Correction Applied (§4.3)**: Detours within an epoch ($ab \to x \to ab$) restore exact constituent attributes (bold `'a'`, italic `'b'`). Partial commit of `'a'` freezes its attributes into $D_c$ and begins a new attribution epoch for suffix `'b'`.

### 6.2 Itemized Table of Normative Changes in Revision 4.9.1

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.9.1; documented composition subsystem architectural consolidation, Candidate B selection, resolution of Gaps 4.1–4.7, and run-20261014-05 evidence | (b) Spec completeness |
| §1 | Architectural Foundation | Replaced flat live-document composition buffer with separated committed storage $D_c$ and segment-mapped provisional overlay $\mathcal{O}$; visible text $D_v = \Pi(D_c, \mathcal{O})$ | (c) Spec correction |
| §1.2 | What changed from v1 | Added table row documenting the transition from flat buffer to Candidate B segment overlay | (b) Spec completeness |
| §4.2 | Composition state & overlay | Specified Candidate B core state representation: $D_c$, $\mathcal{O}$ with `OverlaySegment(dcStart, dcEnd, units)`, $\text{markedWindow}$, $\text{attributionEpoch}$, and deterministic projection $\Pi(D_c, \mathcal{O})$ | (c) Spec correction |
| §4.3 | Candidate attribution | Incorporated outward grapheme closure & disjoint island formatting (Gap 4.1), retargeting boundary isolation & clamp rejection (Gap 4.2), 1-unit segment mapping for absorbed original text (Astra Critique 1, Gap 4.3), and detour baseline restoration (Gap 4.6) | (b) Spec completeness |
| §4.4 | Partial commit & transitions | Formulated exact zero-length deletion tracking rule (Gap 4.4), single-owner multi-unit rollback extinguishing across nested replacements (Gap 4.5), and updated transition table for segment overlay operations | (b) Spec completeness / (c) Spec correction |
| §4.5 | Settlement lifecycle | Formulated monotonic token issuance, invalidation on new input, rejection guards (`CandidateStillActive`, `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch`), and FIFO drainage (Gap 4.7) | (b) Spec completeness |
| §9.4 / §9.5 | Revision 4.9.1 Summary | Added full verification summary: Candidate A falsified (10/14 fixtures, depth 1 model check failure); Candidate B qualified (14/14 fixtures, 7/7 settlement, 17/17 gaps, 11/11 mutants, depth 5 BFS model checking with 3,585 states & 8,710 transitions, 11,943 ops/sec benchmark); Phase 0 platform gates strictly retained as NOT RUN | (b) Spec completeness / (d) Feasibility scoping |

---

## 7. Normative Plan Text Differences Summary (Revision 4.9.2)

**Date**: 14 October 2026  
**Scope**: Candidate B Specification Repair, Protocol Defect Resolution (Failures A–I), and Isolated Harness Requalification.  
**Evidence Run**: [`validation/antigravity/run-20261014-06/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-06/)

### 7.1 Audit Findings & Applied Corrections (Defects A–I)

#### Finding 25 (Defect A): Multi-Unit Segment Splicing Ownership Corruption
- **Classification**: **(a) Harness violation / (c) Specification incorrect**
- **Defect Description**: When splicing new candidate units into a touched multi-unit segment, the previous implementation replaced the entire segment with a single combined segment, losing the distinct $D_c$ replacement footprints of untouched prefix and suffix units. Subsequent partial commits deleted untouched committed units.
- **Normative Plan Correction Applied (§4.3)**: Splicing into touched multi-unit segments partitions them into distinct `leftUnits`, `midUnits`, and `rightUnits` segments, maintaining exact $D_c$ replacement attribution.

#### Finding 26 (Defect B): Implicit Preedit Noncollapsed Selection Dropping
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: When an implicit candidate insertion arrived with an active noncollapsed selection, the coordinator spliced the candidate without deleting the selected range first, inserting candidate text alongside the selection instead of replacing it.
- **Normative Plan Correction Applied (§4.1, §4.4)**: Implicit preedit on noncollapsed selection normalizes by deleting the selection before candidate insertion, ensuring standard text replacement semantics.

#### Finding 27 (Defect C): Settlement Snapshot Text Duplication
- **Classification**: **(a) Harness violation / (b) Specification incomplete**
- **Defect Description**: The settlement coordinator created a composition settlement history entry before flushing the provisional overlay to committed storage, causing the text to be captured twice (`catss` instead of `cats`).
- **Normative Plan Correction Applied (§4.2, §4.5)**: `_flushOverlayToCommitted()` flushes provisional overlay to committed storage before settlement history entry creation.

#### Finding 28 (Defect D): Grapheme Cluster Accent Formatting Truncation
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Applying formatting to combining characters or ZWJ sequences without cluster closure formatted only base characters or dropped combining marks.
- **Normative Plan Correction Applied (§5.3)**: Canonical outward grapheme cluster expansion using `Intl.Segmenter` ensures combining marks, ZWJ sequences, and base characters format together.

#### Finding 29 (Defect E): Unrelated Pending Ticket Eviction on Cancellation
- **Classification**: **(a) Harness violation / (c) Specification incorrect**
- **Defect Description**: Composition cancellation cleared all pending commands in the coordinator, wiping tickets for unrelated commands targeting explicit document ranges.
- **Normative Plan Correction Applied (§4.5)**: Ticket conservation: unrelated pending commands targeting explicit ranges survive cancellation; only commands targeting the cancelled composition session are terminated with `Cancelled`.

#### Finding 30 (Defect F): Premature Idle Transition on Full Partial Commit
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Committing all candidate units ($k = \text{len}$) transitioned the coordinator to `Idle`. Subsequent native unmark events were then rejected with `UnexpectedState`.
- **Normative Plan Correction Applied (§4.4)**: Full partial commit retains `Composing` state with empty active composition, ensuring the session remains open until explicit settlement or cancellation.

#### Finding 31 (Defect G): Range & Selection Validation Failure
- **Classification**: **(b) Specification incomplete**
- **Defect Description**: Negative, inverted, or out-of-bounds ranges were partially processed or threw unhandled exceptions, causing state drift.
- **Normative Plan Correction Applied (§4.2, §4.3)**: Upfront parameter validation rejects negative, inverted, and out-of-bounds ranges atomically before any state mutation.

#### Finding 32 (Defect H): Convex Hull Formatting Across Disjoint Constituent Islands
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Queued commands formatted the convex bounding hull of disjoint candidate islands, corrupting intervening unedited committed text.
- **Normative Plan Correction Applied (§4.3, §4.5)**: Replaced scalar interval formulas with constituent island model; commands format only tracked candidate islands mapped dynamically through preceding edits without convex hull pollution.

#### Finding 33 (Defect I): TrackedRange Extent Conflation with Active Composition
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Commands specifying `TrackedRange` were coerced into the active composition extent.
- **Normative Plan Correction Applied (§4.5)**: TrackedRange commands resolve strictly to their own target extent; session-mismatched commands are rejected with `TargetSessionMismatch`.

### 7.2 Substantive Model Corrections in §4.2, §4.4, §4.5
1. **§4.2 (Committed Storage Durability Separation)**: Clarified in-memory IME-committed $D_c$ state vs decoupled disk-flush journal persistence, ensuring atomic transaction rollback applies to in-memory models while crash recovery remains a Phase 7 gate.
2. **§4.4 (Partial Commit State Continuity & Consumed Prefix)**: Formulated partial commit state update to strictly remain in `Composing` ($Composing \to Composing$); defined total consumed prefix $P_{\text{commit}} = \text{flatten}(\text{consumedSegments.units}) \mathbin{\Vert} g_m.units[0 .. p_{\text{cut}} - \text{segStart})$.
3. **§4.5 (Constituent Island Model)**: Replaced obsolete scalar interval formulas with constituent island model and dynamic mapping across length deltas.

### 7.3 Itemized Table of Normative Changes in Revision 4.9.2

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.9.2; documented Defect Scenarios A–I resolution, 63,798 transitions / 44,935 states BFS model checking, 20/20 mutants caught, and run-20261014-06 evidence | (b) Spec completeness |
| §4.2 | Durability & State | Clarified in-memory IME-committed $D_c$ vs decoupled journal durability; atomic pre-session history snapshots; upfront range validation | (b) Spec completeness / (c) Spec correction |
| §4.3 | Candidate attribution | Formulated constituent island tracking; multi-unit segment splicing ownership (`leftUnits`, `midUnits`, `rightUnits`) | (c) Spec correction |
| §4.4 | Partial commit & transitions | Partial commit state continuity ($Composing \to Composing$); definition of total consumed prefix $P_{\text{commit}}$; implicit preedit selection replacement | (c) Spec correction |
| §4.5 | Settlement & commands | Replaced obsolete scalar interval formulas with constituent island model and dynamic mapping; TrackedRange target isolation; ticket conservation | (c) Spec correction / (b) Spec completeness |
| §5.3 | Rich text & attributes | Canonical outward grapheme cluster expansion via `Intl.Segmenter` for combining marks and ZWJ sequences | (c) Spec correction |
| §9.4 / §9.5 | Revision 4.9.2 Summary | Added full verification summary: Defect Scenarios A–I verified; 14/14 fixtures; 7/7 settlement; 17/17 gaps; 20/20 mutants caught; depth 4 BFS model checking with 44,935 states & 63,798 transitions; Phase 0 platform gates strictly retained as NOT RUN | (b) Spec completeness / (d) Feasibility scoping |

---

## 8. Revision 4.9.3 Corrections & Normative Diff (run-20261014-07)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Verification Lead  
**Scope**: Falsification audit, coordinator defect reproduction and repair (Findings A–F), staged failure atomicity across 6 transaction stages (§4.3), executable undo/redo (§4.4), decoupled independent Unicode grapheme analysis (§4.2), complete canonical state equivalence model checking (143,913 states, 204,932 transitions at depth 4), and comprehensive mutation testing (27/27 positive controls, 27/27 mutants caught cleanly).  
**Authoritative Target**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.3)  
**Evidence Run Directory**: [`validation/antigravity/run-20261014-07/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-07/)  

### 8.1 Findings A through F: Reproduction and Normative Resolution

#### Finding A: Connection Replacement Discards Accepted Provisional Content
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: When a connection was replaced via `openNewConnection()` during an active composition, `provisionalOverlay` was abruptly reset to `null` without committing its segments to $D_c$. Accepted provisional text (e.g. `"HXllo"`) was completely discarded, reverting visible text back to `"Hello"`.
- **Reproduced Defect**: In `reproduce-failures.mjs`, composing `"Hello"` with preedit `"X"` at `[1, 2)` produced `"HXllo"`. Calling `openNewConnection()` reverted visible text to `"Hello"` instead of preserving `"HXllo"`.
- **Normative Plan Correction Applied (§4.1, §4.5, Table line 331)**:
  `openNewConnection()` flushes and commits the active provisional overlay to $D_c$. Accepted candidate text is permanently committed to storage rather than discarded.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 1), `test-fixtures.mjs`, and mutation testing.

#### Finding B: Connection Replacement Drops Undo History After Partial Commits
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: When an active composition containing prior partial commits was replaced via `openNewConnection()`, the coordinator discarded the pre-session snapshot and did NOT push a `Composition` history entry if no uncommitted candidate was active at the time of turnover. Prior partial commits became permanently un-undoable.
- **Reproduced Defect**: In `reproduce-failures.mjs`, composing `"Hello"`, partially committing 1 unit (`"X"`), and calling `openNewConnection()` left `undoStack.length == 0`.
- **Normative Plan Correction Applied (§4.1, §4.5, §7, line 509)**:
  If a connection is replaced (`openNewConnection`) or disposed (`dispose`) while an active or partially committed composition has modified document text or attributes, the coordinator MUST finalize exactly one `Composition` history group to `undoStack` before tearing down the session. Prior partial commits never lose their history records.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 2) and `test-mutations.mjs`.

#### Finding C: Full Partial Commit Conflates Range Formatting with Typing Intent
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: When all units of a candidate were committed via `partialCommit(k)` where $k = \text{len}$, the candidate window became empty (`markedWindow = [p, p]`). When a queued formatting command targeting `ActiveComposition` was drained, the coordinator treated the empty candidate extent as a collapsed insertion toggle: it updated `typingIntent` instead of formatting the committed characters, and created no formatting undo group.
- **Reproduced Defect**: In `reproduce-failures.mjs`, composing `"cat"`, fully partially committing 3 units, and settling with queued Bold formatted 0 characters and pushed 0 formatting undo groups.
- **Normative Plan Correction Applied (§4.5)**:
  The coordinator tracks cumulative `sessionCommittedRanges`. When a composition candidate is fully committed leaving an empty active marked range, queued formatting commands targeting `ActiveComposition` format the cumulative committed units directly in $D_c$, creating a separate `Formatting` undo entry on `undoStack` without modifying `typingIntent`.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 3) and `test-semantic-gaps.mjs`.

#### Finding D: Selection Validation Against Stale Pre-Edit Document Length
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Selection validation in `applyInput` checked `s0, s1 <= curLen` (the current visible document length before the edit). This broke both growth and shrinkage:
  - Growth (D1): A valid selection positioned in the expanded text after a growth edit was falsely rejected as out of bounds.
  - Shrinkage (D2): An invalid selection positioned beyond the shrunken text was falsely accepted, leading to out-of-bounds selection corruption.
- **Reproduced Defect**: In `reproduce-failures.mjs`, inserting 5 characters with selection at 9 was rejected; deleting 3 characters with selection at 4 was accepted despite new doc length being 2.
- **Normative Plan Correction Applied (§4.2)**:
  All selection ranges are validated against the **proposed post-edit document length**:
  $$\text{proposedLen} = \lvert D_v\rvert - (v - u) + \lvert\text{text}\rvert$$
  Selections outside $[0, \text{proposedLen}]$ are rejected upfront with `Rejected(InvalidSelection)` before any mutation occurs.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 4) and `test-mutations.mjs`.

#### Finding E: Unrelated Commands Stranded on Composition Cancellation
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: When `_cancel()` aborted an active composition, commands targeting `ActiveComposition` were cancelled (`CompositionCancelled`), but commands targeting unrelated document ranges (`TrackedRange`) were left sitting permanently in `pendingCommands` without being mapped or drained.
- **Reproduced Defect**: In `reproduce-failures.mjs`, queuing a `TrackedRange` command outside composition and cancelling composition left `pendingCommands.length == 1`.
- **Normative Plan Correction Applied (§4.5)**:
  When composition cancellation occurs, surviving unrelated `TrackedRange` commands are immediately mapped across the cancellation delta ($\Delta = \lvert D_c\rvert - \lvert D_v\rvert$) and drained in FIFO order, never left stranded in `pendingCommands`.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 5) and `test-mutations.mjs`.

#### Finding F: Defunct Connection Callbacks Admitted Due to Missing Epoch Gating
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: Native host callbacks carrying an obsolete `epoch` stamp from a prior connection were admitted and executed without validation, causing cross-connection state corruption.
- **Reproduced Defect**: In `reproduce-failures.mjs`, opening a new connection (advancing epoch to 1) and applying an event with `epoch: 0` was accepted instead of rejected.
- **Normative Plan Correction Applied (§4.1)**:
  Strict callback epoch admission gating: callbacks where `payload.epoch < coordinator.epoch` are rejected with `Rejected(StaleEpoch)`; callbacks where `payload.epoch > coordinator.epoch` are rejected with `Rejected(FutureEpoch)`.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 6) and `test-mutations.mjs`.

---

### 8.2 Validation Gaps §4.1–§4.5 Resolved

1. **§4.1: Complete Canonical Equivalence Key**:
   - `serializeState` in `independent-model-checker.mjs` fully serializes all state fields: Candidate B attribution `B_epoch` and `baseSegments`, full Oracle rollback tokens (`replacedCommitted`), history undo/redo stacks, session committed ranges, command targets, sequence receipts, and connection state.
2. **§4.2: Decoupled Independent Unicode Expectations**:
   - `independent-oracle.mjs` implements independent UAX #29 extended grapheme cluster analysis without delegating to `candidate-b-coordinator.mjs` internal helpers, eliminating circular validation dependencies.
3. **§4.3: Real 6-Stage Transactional Failure Injection & Atomic Rollback**:
   - Mutating operations execute across 6 discrete transactional stages (`beforeMutation`, `afterTextSplice`, `afterTargetMapping`, `afterHistoryPush`, `afterCommandDrain`, `beforeReceiptPublication`). On injected failure or unhandled exception, pre-transaction state snapshot is atomically restored, `DocumentRevision` does not bump, and `receiptCache` is not corrupted. Invariant 10 actively verifies pre/post equivalence on all rejections.
4. **§4.4: Executable Undo / Redo Implementation & Verification**:
   - Candidate B and Oracle implement fully executable `undo()` and `redo()` with branch invalidation (clearing redo stack on new edits), active composition abort/rollback, and revision monotonicity.
5. **§4.5: Broader Event Combinations in Model Checker**:
   - Model checking action space includes `openNewConnection`, `dispose`, focus transitions (`onFocusLost`), growth/shrinkage selections, undo, redo, and staged failure injection across depths 1 through 4.

---

### 8.3 Verification Metrics & Acceptance Results (Run-20261014-08)

- **Bounded Model Checking**:
  - Explored **151,943 unique abstract states** and **211,708 transitions** at depth 4 with **0 invariant violations**.
  - Confirmed architectural unsoundness of Candidate A (Flat Buffer), which failed at depth 0 on Invariant 1 (`I_VISIBLE_PROJECTION`).
- **Mutation Testing**:
  - 34/34 positive controls passed on unmodified Candidate B.
  - **34/34 mutants caught cleanly** (34 clean assertion kills, 0 unhandled crashes).
- **Hand-Calculated Fixtures**: 18/18 passed on Candidate B and Independent Oracle (Candidate A failed 8 negative controls).
- **Settlement & Lifecycle Adversarial Suite**: 21/21 passed cleanly.
- **Deep Semantic Gaps (§4.1–§4.7)**: 17/17 passed cleanly.
- **Repaired Regressions & Conformance (Findings A–F, Run-08 Findings A–D, Staged Failures, Undo/Redo, Unicode)**: 14/14 blocks passed cleanly.
- **Comparative Performance Benchmark**:
  - Candidate B: 1,701 ops/sec (587.97 µs/op across 5,000 full composition cycles), well within the sub-millisecond per-keystroke interactive frame budget.

---

## 9. History and Lifecycle Contract Violation Repairs (Run-20261014-08)

### 9.1 Itemized Findings A–D

#### Finding A: Repeated Undo/Redo Cycles & Empty Document Preimage Corruption
- **Classification**: **(c) Specification incorrect** / **(a) Harness violation**
- **Defect Description**: When an initially empty document underwent repeated undo/redo cycles (`["x", "", "x", ""]`), the second undo restored `"x"` instead of the empty string `""` (`["x", "", "x", "x"]`). Caused by truthiness fallback `entry.beforeText || this.docText` treating `""` as falsy and substituting current text. Furthermore, redo records were being dynamically reconstructed from current document text rather than historical entry text.
- **Reproduced Defect**: Reproduced in `reproduce-failures.mjs` (`raw-reproduced-failures.json`).
- **Normative Plan Correction Applied (§4.4)**:
  Enforced strict undefined/null checks: `(entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : ''`. Symmetrical immutable record transfer between `undoStack` and `redoStack`. Redo reconstruction derives strictly from `entry.afterText` and `entry.afterAttrs`.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 11), `test-fixtures.mjs` (Finding A fixture), and `test-mutations.mjs` (Mutant 12, Mutant 13).

#### Finding B: Native Removal Across Lifecycle States Lacking Undo History
- **Classification**: **(b) Specification incomplete**
- **Defect Description**: `nativeRemoval` in `Idle`, `Composing`, or `AwaitingSettlement` performed text replacement directly without recording an undo history entry, causing subsequent `undo()` to be rejected with `NothingToUndo`.
- **Reproduced Defect**: Reproduced in `reproduce-failures.mjs` (`raw-reproduced-failures.json`).
- **Normative Plan Correction Applied (§4.2, §4.4)**:
  `_nativeRemoval(range, replacementText, selection)` records a traceable `Composition` (or `NativeRemoval`) undo entry with exact pre-edit and post-edit text across all lifecycle states (`Idle`, `Composing`, `AwaitingSettlement`) without corrupting or dropping prior composition history.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 12), `test-fixtures.mjs` (Finding B fixture), and `test-mutations.mjs` (Mutant 14).

#### Finding C: Disposal Discarding Accepted Provisional Content
- **Classification**: **(c) Specification incorrect**
- **Defect Description**: `_dispose()` cleared the provisional overlay, discarding accepted provisional text rather than preserving visible text under teardown.
- **Reproduced Defect**: Reproduced in `reproduce-failures.mjs` (`raw-reproduced-failures.json`).
- **Normative Plan Correction Applied (§4.1, §4.5)**:
  Under the preserve-visible-content teardown policy, `_dispose()` materializes accepted visible overlay units into durable committed storage $D_c$ and records a `Composition` history entry before setting `connectionState = 'Closed'`, guaranteeing visible content preservation under teardown.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 13), `test-fixtures.mjs` (Finding C fixture), `test-settlement-lifecycle.mjs` (Cases 15–21), and `test-mutations.mjs` (Mutant 15).

#### Finding D: Resumed Preedit Divergent Target Resolution
- **Classification**: **(c) Specification incorrect** / **(b) Specification incomplete**
- **Defect Description**: After nonterminal `unmark(settled: false)`, when preedit resumed with omitted range, proposed-state validation targeted selection while mutation dispatch targeted stale marked range (or vice versa), leading to silent desynchronization.
- **Reproduced Defect**: Reproduced in `reproduce-failures.mjs` (`raw-reproduced-failures.json`).
- **Normative Plan Correction Applied (§4.2)**:
  Enforced transaction-owned unified target resolution (`_resolveTargetDescriptor(payload)`) shared identically across validation and mutation dispatch: omitted range after nonterminal unmark targets current selection, while active marked ranges target candidate span. Target expansion sets `compositionState = 'Composing'` and clears settlement fences.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 14), `test-fixtures.mjs` (Finding D fixture), and `test-mutations.mjs` (Mutant 16, Mutant 17).

### 9.2 Rejection Atomicity (Invariant 10) Across All Mutations
- In `applyInput()`, universal rejection atomicity is enforced: any operation returning `{ status: 'Rejected' }` unconditionally restores `preTxSnapshot`, guaranteeing bit-for-bit invariance across revisions, text, selection, overlay, and stacks.
- `undo()` and `redo()` check stack emptiness upfront before cancelling active composition sessions.
- Invariant 10 verified bit-for-bit equivalence across 61,004 rejected operations during depth 4 model checking.




### 9.3 Complete History and Session Termination Matrix (§2)
- Formulated the complete 14-Event × 3-State normative matrix in `observable-editing-contract.md` §7 and `plan-v4.md` §4.5.1.
- Defined explicit rules for preimage authority, group finalization, command mapping, selection tracking, and revisions across `Idle`, `Composing`, and `AwaitingSettlement`.
- Standardized active composition undo: flushes visible text to committed storage $D_c$, records `Composition` history entry, pops to revert to pre-session snapshot $S_0$, and symmetrically preserves composition on `redoStack`.
- Reconciled Web ProseMirror synchronous admission without automatic fallback prescriptions, and 100-ms single paragraph latency target without automatic block limit or splitting prescriptions.

### 9.4 Named Longer Sequences Suite (§4)
- Implemented and verified 8 comprehensive multi-step long traces in `test-named-longer-sequences.mjs`:
  1. Repeated Undo/Redo Preimage Stability (12 Cycles on Empty and Populated Documents)
  2. Whole-Document Replacement and Deletion Cycles
  3. Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
  4. Disposal Teardown Content & History Preservation across Lifecycle States
  5. Undo and Redo During Active Composition (Settlement & Redo Preservation)
  6. Resumed Preedit Unified Target Resolution (Omitted vs Explicit Ranges)
  7. Dynamic Selection Bounds & Reversed Selections on Growth and Shrinkage
  8. Interleaved Typing, Formatting, Connection Turnover, and Branch Invalidation
- All 8 sequences execute cleanly with 100% agreement between Candidate B and Independent Oracle.

---

## 10. Target-Mapping and Command-Admission Defect Repairs (Run-20261014-09)

### 10.1 Itemized Defects 1–4

#### Defect 1: Piece-Mapped Surviving Range Resolution on Rollback Over Zero-Delta Opposing Edits
- **Classification**: **(c) Specification incorrect** / **(a) Harness violation**
- **Defect Description**:
  When a composition session was cancelled or aborted, Candidate B mapped surviving tracked ranges by shifting coordinates by a single scalar delta $\Delta = \lvert D_c\rvert - \lvert D_v\rvert$. In the presence of disjoint or opposing edits (e.g. inserting 1 character at index 0 and deleting 1 character at index 4, giving net $\Delta = 0$), the scalar shift failed completely. Characters between or after the edits were shifted into wrong coordinates, or ranges within deleted segments failed to be detected as deleted.
- **Normative Plan Correction Applied (§4.5)**:
  Replaced scalar net delta shift in `mapTrackedRangeOnCancel` with exact piece-by-piece token-identity surviving range resolution:
  1. Gathers surviving token identities present in durable storage $D_c$.
  2. For each tracked range, checks which tokens survive. Collapsed carets map cleanly to piece boundaries.
  3. If all tokens in the target range were provisional units discarded on rollback: returns `Rejected(TargetDeleted)`.
  4. If coordinates extend beyond document bounds: returns `Rejected(TargetInvalidated)`.
  5. Surviving targets are mapped to exact contiguous bounds in $D_c$.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 15) and `test-mutations.mjs` (Mutant R09_1, Mutant R09_2).

#### Defect 2: Idle Command Queueing and Applied Outcome Record
- **Classification**: **(c) Specification incorrect** / **(b) Specification incomplete**
- **Defect Description**:
  When `queueCommand` was dispatched while the coordinator was in `Idle`, Candidate B previously added it to `pendingCommands` rather than executing synchronously and recording `{ status: 'Applied', ticketId }` in `commandOutcomes`. Furthermore, on transactional rollback, ticket records were erased from `commandOutcomes`.
- **Normative Plan Correction Applied (§4.5)**:
  In `Idle`, commands execute synchronously, recording outcome `{ status: 'Applied', ticketId }` and pushing an explicit history group if text/attributes changed. Ticket records in `commandOutcomes` are preserved across transactional rollback snapshots, ensuring ticket accountability and auditability.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 16) and `test-mutations.mjs` (Mutant R09_3).

#### Defect 3: Cross-Epoch Receipt Isolation & Connection Fencing
- **Classification**: **(b) Specification incomplete** / **(c) Specification incorrect**
- **Defect Description**:
  Receipt cache keys used bare sequence numbers. If a new connection recycled sequence numbers starting at 0, duplicate receipts from prior epochs could cause false duplicate rejections. Additionally, stale callbacks from defunct connections were checked against the receipt cache before verifying connection epoch.
- **Normative Plan Correction Applied (§4.1, §4.5)**:
  Keyed sequence receipts with composite format `${epoch}:${seq}`. The coordinator enforces connection epoch validation upfront prior to duplicate cache lookup: stale callbacks (`epoch < connectionEpoch`) are rejected with `StaleEpoch` and future callbacks with `FutureEpoch` without touching the cache.
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 17) and `test-mutations.mjs` (Mutant R09_4).

#### Defect 4: Explicit Ordered Queue Settlement Policy on Undo During Active Composition
- **Classification**: **(e) Product policy requiring an explicit decision** / **(b) Specification incomplete**
- **Defect Description**:
  When `undo()` was invoked during active composition where text had not been altered from baseline but formatting commands were queued, the order of history stack operations differed between Oracle and Candidate B.
- **Normative Plan Correction Applied (§4.4)**:
  Adopted the **Explicit Ordered Queue Settlement Policy**:
  - If text is unmodified from baseline but queued commands exist, the coordinator drains and executes queued commands against visible text, pushes an explicit `Formatting` history group onto `undoStack`, and `undo()` pops that entry, reverting to pre-command document state while populating `redoStack`.
  - If text was modified, active composition materializes into $D_c$, pending commands are drained and settled, and history groups are pushed in causal sequence (`Composition`, then `Formatting`).
- **Verification**: Verified in `test-repaired-regressions.mjs` (Block 18) and `test-mutations.mjs` (Mutant R09_6).

#### Retarget Visible Attribute Seeding
- **Classification**: **(c) Specification incorrect**
- When `_retarget(range)` expanded the active candidate window over visible text, seed formatting was sampled from `committedDoc[u]` rather than `docAttributes[u]`. Splicing into visible units caused attribute loss when overlay prefixes existed. Corrected to sample `this.docAttributes[u]`.
- **Verification**: Caught and verified in depth 3 model checker and `test-mutations.mjs` (Mutant R09_5).

---

### 10.2 Exhaustive 85-Cell Master Contract Coverage Sweep
- Implemented and executed `test-contract-coverage-sweep.mjs` auditing all 17 public events across all 5 lifecycle states:
  - States (5): `Idle`, `Composing (nonempty)`, `Composing (empty)`, `AwaitingSettlement`, `Closed`.
  - Events (17): `startComposition`, `preedit`, `commit`, `partialCommit`, `targetExpansion`, `retarget`, `unmark`, `completeSettlement`, `cancel`, `nativeRemoval`, `onFocusLost`, `openNewConnection`, `dispose`, `undo`, `redo`, `setSelection`, `dispatchCommand`.
- All 85/85 state-event combinations pass with 100% agreement between Candidate B and Independent Oracle across admission status, rejection reasons, document text/attributes, selection bounds, history stack integrity, and revision monotonicity.

---

### 10.3 Sound Subclass Mutation Suite (40 Mutants)
- Expanded mutation suite (`test-mutations.mjs`) to 40 realistic mutants covering all architectural and protocol invariants:
  - 40/40 positive controls pass on unmodified Candidate B.
  - 40/40 mutants caught cleanly with 40 clean assertion kills and 0 unhandled crashes.
  - All 6 Run-09 defect mutants (R09_1 through R09_6) caught cleanly.

---

### 10.4 Canonical Model Checking & Performance Benchmark
- **Bounded Model Checking** (`independent-model-checker.mjs`):
  - Candidate B explored **139,746 unique abstract states** and **196,532 transitions** at depth 4 in 45,797 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle.
  - Candidate A proven unsound at depth 0 on Invariant 1 (`I_VISIBLE_PROJECTION`).
- **Comparative Performance Benchmark**:
  - Candidate B executes 5,000 full composition cycles in 1,863.93 ms (372.79 µs/op; 2,683 ops/sec; 12.00x overhead ratio relative to Candidate A's flat buffer 31.07 µs/op), well within the sub-millisecond per-keystroke interactive frame budget (<1,000 µs).

