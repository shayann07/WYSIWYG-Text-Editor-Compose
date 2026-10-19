# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.9.8)

**Date**: 15 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Executable validation audit, reproduction and repair of contract findings (Findings A–F from run-07, history/lifecycle Findings A–D from run-08, target-mapping/admission Defects 1–4 from run-09, contract defects 1–3 from run-10, Run-11 Failures 1–4, and Run-12 Critical Probes 1–5), verification of transactional atomicity across 6 failure injection stages (§4.3), centralized dispatcher encapsulation in `_runInTransaction` across `dispatchCommand`, `undo`, `redo`, and `applyInput` with biconditional revision monotonicity, execution-time validation of deferred callbacks via immutable `RequestEnvelope` and dynamic coordinate basis mapping across intermediate edits (Probe 1, §4.1), staging vs coherent published snapshot boundary (`_publishedState`) with atomic publication commit and rollback isolation (Probe 2, §4.1, §4.3), settlement lifecycle ownership preserving eligible reentrant commands while disposal cancels as `Disposed`, turnover fences as `StaleEpoch`, and rollback partitions internal closures from external requests (Probe 3, §4.1, §4.5), reproducible 64-bit JVM memory accounting model replacing the unsupported 16 MiB claim (24B scalar fields -> 40B minimum JVM object; 1,000 pieces ~5.16 MiB heap, 1,000,000 pieces 98.22 MiB heap separate from 64 MiB history budget) and $O(\log P)$ dynamic rank resolution via left subtree length accumulators (Probe 4, §4.5), focused transaction/reentrancy contract suite (22/22 tests) and persistent identity index prototype validation (Probe 5), transactional reentrancy FIFO queueing (`_reentrantMutationQueue`) with sequential execution on outer transaction publication (Failure 4, §4.1, §4.3), universal rejection atomicity (Invariant 10), executable undo/redo implementation and invertibility without truthiness fallback corruption (§4.4), operation-level redo branch retention across queued undo drainage (Failure 1, §4.4), dynamic token-identity tracking and remapping across intermediate undos preventing `TargetInvalidated` (Failure 2, §4.5), qualified synchronous settlement evidence verification contract preventing unqualified barrier bypass (Failure 3, §4.5), disjoint interval target resolution on rollback preserving unedited restored text (Defect 1), asynchronous settlement barrier queueing application undo in `AwaitingSettlement` behind active fences unless qualified by `syncBoundary: true` (Defect 3), decoupled independent Unicode grapheme analysis (§4.2; UAX #29 pinned to Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0), exhaustive 85-cell contract coverage sweep (17 public events × 5 states), exhaustive BFS model checking across 146,594 unique states & 203,280 transitions at depth 4 with 0 invariant violations, 56 coordinator subclass mutation tests (56/56 positive controls, 56/56 clean assertion kills, 0 unrelated crashes), comparative performance & allocation microbenchmarking (Candidate B 472.5 µs/op vs Candidate A 27.94 µs/op in Node.js V8; native multiplatform unvalidated), and normative consolidation into [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.8).  
**Primary Verdict**: **ARCHITECTURAL MERGE APPROVED: Candidate B (Segment-Mapped Overlay) Repaired & Qualified for plan-v4 Revision 4.9.8. PHASE 0/1 GATED (Pending plan resolution, dependency freeze, and native platform qualification; production implementation strictly prohibited).**  
**Evidence Run Directory**: [`validation/antigravity/run-20261015-12/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261015-12/)  

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs or screen reader accessibility work end-to-end today because no live native host, OS IME, or assistive technology has been executed in this workspace; our confidence is strictly confined to headless coordinate algorithms, candidate reconciliation models, transactional state machines, exact token-identity indexing, and bounded state-space exploration in isolated harness scope.

---

## 1. Executive Summary & Verdict

Following comparative adversarial evaluation of Candidate A (v4 flat live-document buffer) and Candidate B (v5.3 segment-mapped provisional overlay) against an Independent Observational Oracle (`independent-oracle.mjs`) and subsequent defect remediation across runs 07 through 12, we have determined that **Candidate B is the fully qualified segment-mapped overlay architecture passing isolated model verification in run-20261015-12** and definitively replaces Candidate A in [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.8).

### 1.1 Resolution of Contract Findings A–F (from `run-20261014-07`)
1. **Finding A (Turnover Content Discarded)**: `openNewConnection()` flushes and commits the active overlay to $D_c$, preserving accepted text permanently (`'HXllo'`).
2. **Finding B (Turnover History Loss After Partial Commits)**: Pushes exactly 1 `Composition` history group on turnover if text or attributes were modified (including prior partial commits), guaranteeing undo persistence.
3. **Finding C (Full Partial Commit Format Distortion)**: Tracks cumulative `sessionCommittedRanges`; formats the committed units directly in $D_c$ and pushes a separate `Formatting` undo group without modifying `typingIntent`.
4. **Finding D (Pre-Edit Selection Validation Broken on Growth and Shrinkage)**: Validates selection against proposed post-edit document length ($\text{proposedLen} = \lvert D_v\rvert - (v - u) + \lvert\text{text}\rvert$).
5. **Finding E (Unrelated Commands Stranded on Cancellation)**: Unrelated commands are mapped across the cancellation delta and drained immediately during cancellation.
6. **Finding F (Defunct Connection Callbacks Admitted)**: Strict callback epoch admission gating rejects `payload.epoch < coordinator.epoch` as `StaleEpoch` and `payload.epoch > coordinator.epoch` as `FutureEpoch`.

### 1.2 Resolution of History and Lifecycle Contract Findings A–D (from `run-20261014-08`)
1. **Finding A (Repeated Undo/Redo & Empty Preimages)**: Strict undefined/null checks eliminate truthiness fallback (`entry.beforeText || this.docText`), preserving empty-document preimages (`["x", "", "x", ""]`).
2. **Finding B (Native Removal Across Lifecycle States)**: `_nativeRemoval` records a traceable undo history entry across `Idle`, `Composing`, and `AwaitingSettlement` without dropping prior history.
3. **Finding C (Disposal Content Preservation)**: `_dispose()` materializes accepted visible overlay units into $D_c$ and records a `Composition` history entry before closing.
4. **Finding D (Resumed Preedit Unified Target Resolution)**: Unified target descriptor resolution (`_resolveTargetDescriptor`) shared between validation and mutation; omitted range after nonterminal `unmark` targets current selection.

### 1.3 Resolution of Target-Mapping and Admission Defects 1–4 (from `run-20261014-09`)
1. **Defect 1 (Piece-Mapped Surviving Range Resolution on Rollback Over Zero-Delta Opposing Edits)**: Replaced scalar net delta shift in `mapTrackedRangeOnCancel` with piece-by-piece token-identity surviving range resolution in $D_c$.
2. **Defect 2 (Idle Command Execution & Outcome Records)**: Commands in `Idle` execute synchronously and record `Applied` outcome tickets. Ticket outcomes in `commandOutcomes` are preserved across transactional rollbacks.
3. **Defect 3 (Cross-Epoch Receipt Isolation & Connection Fencing)**: Composite keys `${epoch}:${seq}` and upfront epoch admission check prior to cache lookup.
4. **Defect 4 (Explicit Ordered Queue Settlement Policy on Undo During Active Composition)**: Explicit Ordered Queue Settlement Policy drains and settles commands against visible text and pushes an explicit `Formatting` group on `undoStack`, which is popped first by `undo()`.
5. **Retarget Visible Attribute Seeding**: In `_retarget(range)`, seed formatting was sampled from visible projection `docAttributes[u]`, preserving formatting provenance when overlay prefixes existed.

### 1.4 Resolution of Critical Contract Failures 1–3 (from `run-20261014-10`)
1. **Defect 1 (Rollback Mapping Silently Expands Target Over Restored Text)**: Strict disjoint interval preservation in `mapTrackedRangeOnCancel`; partitions surviving token indices into contiguous maximal intervals `[[s0, e0], [s1, e1], ...]`, strictly forbidding min-max bounding box expansion over unedited restored text.
2. **Defect 2 (Advertised Public Dispatcher Bypasses Transaction Accounting)**: Centralized transaction wrapper `_runInTransaction` across all external entry points; upfront validation preventing ticket leakage; biconditional `documentRevision` advancement (`textChanged || attrsChanged`).
3. **Defect 3 (Undo Ordering Specified by Bypassing Settlement Barrier)**: Asynchronous settlement barrier queues application `undo()` behind active settlement fence in `AwaitingSettlement` (`action: 'QueuedBehindSettlement'`) unless adapter provides qualified synchronous settlement credentials.

### 1.5 Resolution of Run-11 Critical Contract Failures 1–4 (from `run-20261014-11`)
1. **Failure 1 (Redo Branch Erased on Settlement Drainage with Queued Undo)**: Scoped redo branch invalidation strictly to operations introducing new editing mutations. Settlement completions executing queued undos preserve redoStack entries into `Idle`.
2. **Failure 2 (Queued Commands Lose Surviving Targets Across Intermediate Undos)**: Dynamic token-identity tracking and remapping via `_remapPendingCommandTargets` re-anchors surviving tokens against restored document state, preventing `TargetInvalidated` rejections.
3. **Failure 3 (Synchronous Settlement Shortcut Without Platform Qualification)**: Strict Synchronous Settlement Evidence Qualification Contract requiring qualification stamp (`QualifiedSyncSettlement`), matching epoch/sessionId/generation, bounded extent, and single-use nonce. Desktop AWT declared unqualified.
4. **Failure 4 (Transactional Reentrancy Rejected Instead of FIFO Queued)**: Transactional reentrancy queueing via `_reentrantMutationQueue` with sequential execution on outer transaction publication and atomic rollback isolation.

### 1.6 Resolution of Run-12 Critical Contract Probes 1–5 (in `run-20261015-12`)
1. **Probe 1 (Deferred Callbacks Bypass Epoch Validation & Coordinate Basis Mapping)**:
   - *Defect*: While callbacks were checked on arrival, deferred callbacks queued prior to connection turnover (`openNewConnection`) could execute against the new connection without verifying that the connection epoch had not advanced. Furthermore, deferred mutations targeting coordinate ranges reinterpreted old coordinates against modified document state.
   - *Repair*: Enforced immutable `RequestEnvelope` (ticketId, origin, epoch, seq, sessionId, basisRevision, basisCoordinate, basisTokenIds, payload) and evaluated **execution-time validation** at the moment the deferred closure executes in `_runInTransaction`. Deferred callbacks from older epochs are rejected as `Rejected(StaleEpoch)`; mismatched sessions reject as `Rejected(SessionMismatch)`. Declared request basis coordinates are dynamically mapped across intermediate edit diffs and token identities; invalidated targets reject as `Rejected(TargetInvalidated)`.
2. **Probe 2 (Public Reads Expose Intermediate Staged State)**:
   - *Defect*: Public getters (`docText`, `docAttributes`, `selection`) directly queried mutable working state. During active multi-stage transactions or reentrant queries, external callers could observe uncommitted, intermediate, or potentially rollbacked mutations.
   - *Repair*: Established the strict **Staging vs Published Snapshot Boundary**. Public getters read exclusively from immutable `_publishedState`. Mutations execute against working state and commit atomically to `_publishedState` on completion. On failure, working state rolls back without leaking uncommitted state. Deterministic notifications: `_willChangeListeners` before mutation, `_didChangeListeners` on successful commit, and `_rollbackListeners` on failure.
3. **Probe 3 (Ordinary Settlement Lifecycle Ownership & Rollback Ownership Partitioning)**:
   - *Defect*: During settlement completion (`completeSettlement`), internal cleanup cleared `_reentrantMutationQueue` and marked eligible reentrant commands as `Disposed`. Furthermore, rollback indiscriminately executed internal closures created by abandoned transactions.
   - *Repair*: Clarified lifecycle ownership: ordinary settlement drains pending commands and **explicitly preserves eligible reentrant commands** in `_reentrantMutationQueue`, draining them sequentially in FIFO order. Only explicit connection disposal (`dispose()`) cancels reentrant commands as `Disposed`; connection turnover advances epoch causing old-epoch requests to reject as `StaleEpoch`. On transaction rollback, internal sub-actions (`origin == 'reentrant'`) are cancelled with `Cancelled(AbandonedWithTransaction)`, while independent external requests are preserved to execute against the restored baseline.
4. **Probe 4 (Unsupported 16 MiB Heap Claim & Missing Dynamic Rank Resolution)**:
   - *Defect*: Claimed that a 1,000,000-piece document occupied 16 MiB without JVM overhead accounting, and lacked explicit $O(\log P)$ rank resolution mechanics under edits and rebalancing.
   - *Repair*: Replaced with a reproducible 64-bit JVM memory accounting model (Piece: 24B scalar fields + 12B header + 4B alignment padding = 40B minimum JVM object; 1,000 pieces = ~5.16 MiB heap; 1,000,000 pieces = 98.22 MiB heap: 38.15 MiB pieces + 5.41 MiB DocTree + 47.68 MiB TokenIndex + 1.91 MiB text buffer). Decoupled live engine heap from the independent 64 MiB history retention budget. Documented $O(\log P)$ dynamic rank resolution via left subtree length accumulators in internal B+ tree nodes (zero locator invalidation on insert-before, $O(\log P)$ splits/merges, $O(1)$ root restoration, and clean LRU snapshot eviction).
5. **Probe 5 (Boundary Validation & Subclass Mutation Verification)**:
   - Evaluated 22/22 focused transaction and reentrancy contract tests (`test-transaction-reentrancy-contract.mjs`).
   - Verified persistent identity index dual B+ tree prototype (`persistent-identity-index-prototype.mjs`).
   - Caught 56/56 coordinator subclass mutants with 56/56 positive controls passed (including Run-12 Mutants R12_1 through R12_6).

---

## 2. Status Totals Across Validation Matrix (103 Evaluated Items)

| Category | Count | Items |
|---|---|---|
| **PASS** | 76 | Invariants I05, I06, I07, I08, I09, I10, I13, I16; RegressionCases R01, R02, R03, R04, R05, R06, R07, R08, R09, R10, R12 (headless AWT unit spike), R14 (serialization), R15 (JVM layout benchmark); Gaps 4.1, 4.2, 4.3 (Candidate B), 4.4 (Candidate B), 4.5 (Candidate B), 4.6, 4.7 (Candidate B); Repaired Defect Regressions A–I; Findings A–F (run-07); History/Lifecycle Findings A–D (run-08); Defects 1–4 (run-09); Defects 1–3 (run-10); Failures 1–4 (run-11); Probes 1–4 (run-12: execution-time validation via RequestEnvelope, dynamic coordinate basis mapping, staging vs published snapshot boundary with atomic commit, settlement lifecycle preservation of reentrancy & rollback ownership partitioning, reproducible 64-bit JVM memory model & dynamic rank resolution); Transaction & Reentrancy Focused Contract Suite (22/22 tests); Persistent Identity Index Dual B+ Tree Prototype; Exhaustive Contract Coverage Sweep (85/85 cells); Transactional Staged Failure Injection & Atomic Rollback (§4.3); Executable Undo/Redo (§4.4); Complete Canonical Model Checker Depth 4 (146,594 states, 203,280 transitions, 0 violations); Subclass Mutation Verification (56/56 caught); Comparative Microbenchmark (2,116 ops/sec, 472.5 µs/op in Node.js V8; native multiplatform unvalidated); Named Longer Sequences (10/10 multi-step sequences verified); Production Identity-Index Consolidation (§4.5 specification consolidation) |
| **PARTIAL** | 6 | Invariants I01, I02, I03, I04, I12, I14 (scalar models scoped honestly without claiming full production multiplatform engine or physical filesystem crash durability) |
| **DESIGN POLICY** | 2 | Invariant I11 (FrameKey immutable caching policy) and Invariant I15 (Persistent B+ tree piece representation policy) |
| **NOT RUN / GATED** | 16 | Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web; Phase Gates G1–G8 Kotlin implementation gates; R11 Web IME; R13 Compose layout; R16 physical AT; Invariant I17 physical AT |
| **UNVALIDATED OPTIMIZATION** | 1 | Skia Native Layout Patch (C++ build failure; baseline crashed; excluded from architecture) |
| **FAIL** | 1 | Shipped Compose `TextFieldState` attributed undo defect confirmed |
| **INCONCLUSIVE** | 1 | Incremental bidi bracket tree index (verified against oracle but dropped as unnecessary complexity) |
| **Total** | **103** | **Exhaustive audit across baseline, research, and validation runs** |

---

## 3. Master Test Suite Execution Summary (11 Suites Total - Run-20261015-12)

```
================================================================================
=== COMPREHENSIVE COMPOSITION ARCHITECTURE VALIDATION (run-20261015-12)      ===
=== Independent Oracle vs Candidate A (Flat Buffer) vs Candidate B (Overlay) ===
================================================================================
```

1. **Transaction & Reentrancy Focused Contract Suite (`test-transaction-reentrancy-contract.mjs`)**:
   - **22/22 focused contract tests passed cleanly (100%)**:
     - Tests 1.1–1.7: Staging vs Published boundary isolation (public getters observe coherent snapshot during active transaction, atomic commit updates published state, observations across all 6 failure-injection stages verify published snapshot referential integrity, reentrant mutations arriving through `willChange`, `didChange`, and `rollbackListeners` hooks observe correct snapshot phases) (PASS)
     - Test 2.1: Request envelope freezes payload against caller post-enqueue tampering (PASS)
     - Tests 3.1–3.5: Execution-time epoch, session, and coordinate basis validation (epoch turnover yields `StaleEpoch`, invalidated targets caught by coordinate revalidation, preceding edits dynamically map declared coordinate basis without silent reinterpretation, execution-time `FutureEpoch` rejection, and `SessionMismatch` rejection) (PASS)
     - Tests 4.1–4.2: Settlement lifecycle reentrancy preservation (ordinary settlement preserves eligible reentrant commands; disposal cancels as `Disposed`) (PASS)
     - Tests 5.1–5.2: Duplicate receipt caching (executed and stale-epoch rejected commands record cached terminal receipts) (PASS)
     - Tests 6.1–6.2: Rollback ownership partitioning (reentrant mutations execute against restored baseline after outer transaction failure; internal transaction closures are cancelled with `AbandonedWithTransaction` on rollback while external requests are preserved) (PASS)
     - Test 7.1: Reentrant request lifecycle transitions from `QueuedReentrant` to terminal receipt exactly once (PASS)
     - Tests 8.1–8.2: Strict FIFO queue drainage and single queue-drain owner (PASS)
2. **Persistent Identity Index Dual B+ Tree Prototype (`persistent-identity-index-prototype.mjs`)**:
   - **Prototype verified cleanly (100%)**:
     - Dual B+ tree piece table (`DocTree` + `TokenIndex`) verified with dynamic rank resolution via left subtree length accumulators.
     - Insert-before and insert-after verified with zero locator invalidation on existing pieces.
     - $O(\log P)$ rank resolution verified across edits, node splits, and merges.
     - $O(1)$ historical root restoration verified.
     - Reproducible JVM memory accounting verified (24B scalar fields -> 40B minimum JVM object per Piece; ~5.16 MiB heap for 1,000 pieces; 98.22 MiB heap for 1,000,000 pieces; decoupled from 64 MiB history budget).
3. **Repaired Defect Regressions & Conformance (`test-repaired-regressions.mjs`)**:
   - **22/22 verification blocks passed cleanly (100%)**:
     - Blocks 1–6 (Findings A–F, run-07): Turnover text, turnover history, full partial commit format tracking, post-edit selection, immediate command drainage, callback epoch admission (PASS)
     - Blocks 7–10: Retained regressions (interior segment splicing, noncollapsed selection, outward grapheme closure, disjoint island mapping, TrackedRange isolation) (PASS)
     - Blocks 11–14 (Findings A–D, run-08): Repeated undo/redo empty preimages, native removal history across lifecycle, disposal content preservation under teardown, resumed preedit unified target resolution (PASS)
     - Blocks 15–18 (Defects 1–4, run-09): Piece-mapped surviving range resolution on rollback across zero-delta opposing edits, idle command execution & ticket preservation, cross-epoch receipt isolation with composite keys, explicit ordered queue settlement policy on undo (PASS)
     - Block 19 (Defect 1, run-10): Disjoint interval preservation on rollback target mapping, forbidding min-max bounding box expansion over restored intervening text (PASS)
     - Block 20 (Defect 2, run-10): Direct `dispatchCommand` transactional encapsulation in `_runInTransaction` with upfront validation, reentrancy deferral, and biconditional revision advancement (PASS)
     - Block 21 (Defect 3, run-10): Asynchronous settlement barrier on application undo, queueing behind active fence in `AwaitingSettlement` (PASS)
     - Block 22 (Failures 1–4, run-11): Redo branch preservation across settlement with queued undo; dynamic token identity remapping across intermediate undos; synchronous settlement qualification guard; transactional reentrancy queueing & sequential FIFO execution (PASS)
4. **Hand-Calculated Regression Fixtures (`test-fixtures.mjs`)**:
   - Independent Oracle: 18/18 PASS (100%)
   - Candidate B: 18/18 PASS (100%)
   - Candidate A: 10/18 PASS, 8 FAIL (Gap 4.3, Gap 4.4 cut boundary, Gap 4.4 retargeted cut boundary, Gap 4.5, Finding A, Finding B, Finding C, Finding D)
5. **Settlement Lifecycle & Guard Scenarios (`test-settlement-lifecycle.mjs`)**:
   - 21/21 PASS cleanly (nonterminal unmarks, preedit resumption, token fencing, turnover, disposal preservation across 6 lifecycle stages)
6. **Deep Semantic Gaps Verification Suite (`test-semantic-gaps.mjs`)**:
   - 17/17 PASS cleanly (formatting provenance, retargeting, absorbed units, zero-length deletions, multi-unit rollback)
7. **Subclass Mutation Invariant Verification (`test-mutations.mjs`)**:
   - 56/56 positive controls passed cleanly on unmodified Candidate B.
   - 56/56 coordinator subclass mutants caught cleanly by invariants and assertions:
     - Mutants R12_1 through R12_6 (Run-12 execution-time epoch validation, staging boundary leak, settlement reentrancy disposal, caller payload tampering, coordinate basis reinterpretation, and internal closure rollback leak) caught cleanly
     - Mutants R11_1 through R11_7 (Run-11 failures and contract defects) caught cleanly
     - Mutants R10_1 through R10_3 (Run-10 defects) caught cleanly
     - 40 earlier mutants (run-07, run-08, run-09) caught cleanly
     - 56 clean assertion kills, 0 unhandled crashes.
8. **Named Longer Sequences Suite (`test-named-longer-sequences.mjs`)**:
   - 10/10 comprehensive multi-step sequences passed cleanly (100%):
     - Sequence 1: Repeated Undo/Redo Preimage Stability (12 Cycles on Empty and Populated Documents)
     - Sequence 2: Whole-Document Replacement and Deletion Cycles
     - Sequence 3: Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
     - Sequence 4: Disposal Teardown Content & History Preservation across Lifecycle States
     - Sequence 5: Undo and Redo During Active Composition (with `syncBoundary: true` qualification)
     - Sequence 6: Resumed Preedit Unified Target Resolution (Omitted vs Explicit Ranges)
     - Sequence 7: Dynamic Selection Bounds & Reversed Selections on Growth and Shrinkage
     - Sequence 8: Interleaved Typing, Formatting, Connection Turnover, and Branch Invalidation
     - Sequence 9: Complex Drainage Sequences with Queued Operations (Format + Undo Interleaving)
     - Sequence 10: Reentrant Transaction Queueing & Sequential FIFO Drainage
9. **Exhaustive Contract Coverage Sweep (`test-contract-coverage-sweep.mjs`)**:
   - 85/85 state-event matrix cells passed cleanly across 17 public events and 5 lifecycle states (`Idle`, `Composing (nonempty)`, `Composing (empty)`, `AwaitingSettlement`, `Closed`), with 100% agreement between Candidate B and Independent Oracle.
10. **Bounded Model Checker (`independent-model-checker.mjs`)**:
    - **Candidate B**: Explored **146,594 unique abstract states** and **203,280 transitions** at depth 4 in 53,131 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle.
    - **Candidate A**: Proven unsound at depth 0 transition, caught on Invariant 1 (`I_VISIBLE_PROJECTION`): attributes mismatch on queueCommand.
11. **Comparative Performance Microbenchmark**:
    - **Candidate A**: 139.68 ms for 5,000 cycles | 35,796 ops/sec | 27.94 µs/op
    - **Candidate B**: 2,362.50 ms for 5,000 cycles | 2,116 ops/sec | 472.50 µs/op
    - Overhead Ratio: 16.91x (Candidate B performs full segment-mapped overlay tracking, token identity management, transactional cloning, coordinate basis mapping, and staging boundary management; 472.5 µs per full multi-step cycle is well within the 1,000 µs sub-millisecond per-keystroke interactive frame budget).

Total suite execution time: **55,826.43 ms**. Full JSON summary saved to [`validation/antigravity/run-20261015-12/results.json`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261015-12/results.json).

---

## 4. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **(a) Repaired Model Defects**:
  - *Probe 1 (Run-12)*: Deferred callbacks bypass connection-epoch validation at execution time -> Repaired via immutable `RequestEnvelope` and execution-time validation in `_runInTransaction`.
  - *Probe 2 (Run-12)*: Public reads expose staged/unpublished state during active transactions -> Repaired via Staging vs Published Snapshot Boundary (`_publishedState`), atomic commit, and rollback isolation.
  - *Probe 3 (Run-12)*: Ordinary settlement cancels reentrant commands as Disposed -> Repaired via settlement lifecycle ownership: ordinary settlement preserves eligible reentrant commands in `_reentrantMutationQueue`; disposal cancels as `Disposed`; connection turnover rejects as `StaleEpoch`.
  - *Failures 1–4 (Run-11)*: Operation-level redo retention across queued undo drainage; dynamic token-identity tracking and remapping across intermediate undos; qualified synchronous settlement evidence verification contract; transactional reentrancy FIFO queueing.
  - *Defects 1–3 (Run-10)*: Disjoint interval preservation on rollback target mapping; centralized transactional dispatcher encapsulation in `_runInTransaction`; asynchronous settlement barrier on application undo.
  - *Defects 1–4 (Run-09)*: Piece-mapped surviving range resolution on rollback; idle command execution & outcome ticket preservation; cross-epoch receipt isolation; explicit ordered queue settlement policy on undo.
  - *Findings A–D (Run-08)*: Repeated undo/redo empty preimages; native removal history across lifecycle states; disposal content preservation under teardown; resumed preedit unified target resolution.
  - *Findings A–F (Run-07)*: Turnover text persistence; turnover history retention across partial commits; full partial commit format tracking; post-edit selection validation; immediate cancellation command drainage; callback epoch admission.
- **(b) Resolved Specification Decisions**:
  - *Probe 4 (Run-12)*: Replaced unsupported 16 MiB heap claim with reproducible 64-bit JVM memory accounting model (Piece: 24B scalar fields + 12B header + 4B padding = 40B minimum JVM object; 1,000 pieces = ~5.16 MiB heap; 1,000,000 pieces = 98.22 MiB heap: 38.15 MiB pieces + 5.41 MiB DocTree + 47.68 MiB TokenIndex + 1.91 MiB text buffer). Decoupled live engine heap from the independent 64 MiB history retention budget. Documented $O(\log P)$ dynamic rank resolution via left subtree length accumulators in internal B+ tree nodes (zero locator invalidation on insert-before, $O(\log P)$ splits/merges, $O(1)$ root restoration, and clean LRU snapshot eviction).
  - *Settlement Evidence Qualification*: Formalized contract requiring `adapterQualification: 'QualifiedSyncSettlement'`, epoch, sessionId, sessionRevision, bounds, and recordId nonce. Desktop AWT declared unqualified.
  - *SelectionTarget Capture Timing*: Defined dynamic evaluation of `SelectionTarget` at execution/drainage time against active document selection.

### 2. What remains ambiguous, failed or untested?
- **(c) Remaining Engineering Decisions**:
  - Phase 0 dependency freeze (exact Kotlin, Gradle, Skiko, and Unicode data versions).
  - Skiko/Compose layout threading and ParagraphCacheKey immutable cache invalidation.
  - Multiplatform native adapter bridges (`InputConnection`, `UITextInput`, `InputMethodRequests`, ProseMirror DOM) and their specific settlement notification events.
  - Assistive technology semantics tree projection and reading order.
- **(d) Native-Platform Experiments NOT RUN**:
  - Gate G0 Native Host on Desktop (real Compose/AWT host window embedding, real OS IME candidate popup positioning, screen-coordinate translation, accessibility).
  - Gate G0 Android, iOS, Web.
  - Phase Gates G1–G8 Kotlin implementation gates.
  - Real assistive technology interactive qualification (TalkBack, VoiceOver, NVDA, Orca).
  - 100K-unit single paragraph layout performance benchmark in Kotlin multiplatform engine.
  - Physical filesystem crash durability / journal recovery.
- **Excluded**: Skia native C++ patch (failed compilation; baseline crashed; excluded from architecture).
- **Failed**: Shipped Compose `TextFieldState` attributed undo confirmed defective.

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES, for headless Phase 0/1 prototyping only, but strictly gated.** [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.8) incorporates the repaired Candidate B segment-mapped overlay architecture and resolves Probes 1–5 from run-12, Failures 1–4 from run-11, Defects 1–3 from run-10, Defects 1–4 from run-09, Findings A–F, Run-08 Findings A–D, Gaps §4.1–§4.7, staged failure atomicity, universal rejection atomicity, the 85-cell contract completeness matrix, exact content-identity index design with reproducible JVM memory accounting, and executable undo/redo with settlement barrier enforcement. Production implementation remains strictly prohibited until acceptance gates pass on native targets.

### 4. Which gates actually passed?
- **G0-Desktop Unit Contract (PASS as a Headless Unit Contract)**: Headless `InputMethodRequests` coordinate indexing, slice stitching, and hit testing pass.
- **G0 Native Host Gates (Desktop, Android, iOS, Web)**: **NOT RUN / INCOMPLETE**.
- **Implementation Phase Gates (G1–G8)**: **NOT RUN / GATED**.

### 5. What is the exact next implementation work and its acceptance criteria?
**Commence Phase 0 (Capability and dependency freeze)**:
1. **Phase 0 Deliverable**: Freeze exact dependencies and generate `validation/capability-manifest.json` with pinned Kotlin, Gradle, Skiko, ProseMirror, and Unicode data versions.
   - *Acceptance Criterion*: Clean build of common modules without compiler warnings or unresolved dependencies.
2. **Phase 1 Deliverable**: Implement headless Kotlin `editor-core` (persistent B+ tree, raw UTF-16 storage, canonical attributes, primitive operations, independent simple reducer, and canonical serialization `editor-document/1`).
   - *Acceptance Criterion (Gate G1)*: Invariants I01–I06 and I14 pass; 100,000 randomized operations across 20 seeds; exact save/load round-trip of raw attributes and isolated surrogates; exception rollback cleanly restores pre-transaction state.
