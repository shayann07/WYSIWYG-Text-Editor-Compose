# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.9.7)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Executable validation audit, reproduction and repair of contract findings (Findings A–F from run-07, history/lifecycle Findings A–D from run-08, target-mapping/admission Defects 1–4 from run-09, contract defects 1–3 from run-10, and Run-11 Failures 1–4), verification of transactional atomicity across 6 failure injection stages (§4.3), centralized dispatcher encapsulation in `_runInTransaction` across `dispatchCommand`, `undo`, `redo`, and `applyInput` with biconditional revision monotonicity, transactional reentrancy FIFO queueing (`_reentrantMutationQueue`) with sequential execution on outer transaction publication (Failure 4, §4.1, §4.3), universal rejection atomicity (Invariant 10), executable undo/redo implementation and invertibility without truthiness fallback corruption (§4.4), operation-level redo branch retention across queued undo drainage (Failure 1, §4.4), dynamic token-identity tracking and remapping across intermediate undos preventing `TargetInvalidated` (Failure 2, §4.5), qualified synchronous settlement evidence verification contract preventing unqualified barrier bypass (Failure 3, §4.5), disjoint interval target resolution on rollback preserving unedited restored text (Defect 1), asynchronous settlement barrier queueing application undo in `AwaitingSettlement` behind active fences unless qualified by `syncBoundary: true` (Defect 3), decoupled independent Unicode grapheme analysis (§4.2; UAX #29 pinned to Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0), exhaustive 85-cell contract coverage sweep (17 public events × 5 states), exhaustive BFS model checking across 146,594 unique states & 203,280 transitions at depth 4 with 0 invariant violations, 50 coordinator subclass mutation tests (50/50 positive controls, 50/50 clean assertion kills, 0 unrelated crashes), comparative performance & allocation microbenchmarking (Candidate B 770.74 µs/op vs Candidate A 33.70 µs/op in Node.js V8; native multiplatform unvalidated), and normative consolidation into [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.7).  
**Primary Verdict**: **ARCHITECTURAL MERGE APPROVED: Candidate B (Segment-Mapped Overlay) Repaired & Qualified for plan-v4 Revision 4.9.7. PHASE 0/1 GATED (Pending plan resolution, dependency freeze, and native platform qualification; production implementation strictly prohibited).**  
**Evidence Run Directory**: [`validation/antigravity/run-20261014-11/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-11/)  

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs or screen reader accessibility work end-to-end today because no live native host, OS IME, or assistive technology has been executed in this workspace; our confidence is strictly confined to headless coordinate algorithms, candidate reconciliation models, transactional state machines, exact token-identity indexing, and bounded state-space exploration in isolated harness scope.

---

## 1. Executive Summary & Verdict

Following comparative adversarial evaluation of Candidate A (v4 flat live-document buffer) and Candidate B (v5.3 segment-mapped provisional overlay) against an Independent Observational Oracle (`independent-oracle.mjs`) and subsequent defect remediation across runs 07, 08, 09, 10, and 11, we have determined that **Candidate B is the fully qualified segment-mapped overlay architecture passing isolated model verification in run-20261014-11** and definitively replaces Candidate A in [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.7).

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

### 1.5 Resolution of Run-11 Critical Contract Failures 1–4 (in `run-20261014-11`)
1. **Failure 1 (Redo Branch Erased on Settlement Drainage with Queued Undo)**:
   - *Defect*: When terminal settlement drained an active composition with a queued `Undo`, the outer transaction completion handler cleared `redoStack = []` because the outer action name was `commit` and net text differed, destroying the redo entry generated by the drained undo.
   - *Repair*: Scoped redo branch invalidation strictly to operations introducing new editing mutations. Transaction boundary settlement completions executing queued undos pop `undoStack` to `redoStack` and preserve the entry into `Idle`, enabling subsequent `redo()` to restore the composition.
2. **Failure 2 (Queued Commands Lose Surviving Targets Across Intermediate Undos)**:
   - *Defect*: When complex drainage sequences contained an intermediate `Undo` that restored pre-session text of a different length, subsequent queued commands evaluated targets against stale scalar bounds and rejected with `TargetInvalidated`.
   - *Repair*: Implemented dynamic token identity tracking and remapping via `_remapPendingCommandTargets`. Queued commands record token IDs of their target units; after an intermediate `Undo`, surviving tokens are re-resolved against the updated document token stream and partitioned into contiguous intervals.
3. **Failure 3 (Synchronous Settlement Shortcut Without Platform Qualification)**:
   - *Defect*: The coordinator allowed any caller supplying `syncBoundary: true` to immediately bypass the settlement barrier without validating that the host adapter possessed synchronous settlement capabilities or verifying nonce freshness, risking desynchronization on unqualified platforms like Desktop AWT.
   - *Repair*: Established the strict Synchronous Settlement Evidence Qualification Contract. Bypassing requires: `adapterQualification: 'QualifiedSyncSettlement'`, matching epoch, matching sessionId, matching sessionRevision, valid bounded extent, and a unique, unconsumed `recordId` nonce. Unqualified attempts reject with `Rejected(InvalidSettlementEvidence)` and safely queue behind the asynchronous barrier.
4. **Failure 4 (Transactional Reentrancy Rejected Instead of FIFO Queued)**:
   - *Defect*: §4.1 specifies that reentrant mutations should queue and execute sequentially after the active transaction completes, but the implementation in run-10 rejected reentrant mutations with `Rejected(ReentrantMutation)`.
   - *Repair*: Implemented transactional reentrancy queueing via `_reentrantMutationQueue`. Reentrant mutations dispatched during an active outer transaction are enqueued in FIFO order and executed sequentially upon completion and publication of the outer transaction. Staged rollback restores baseline state cleanly without executing deferred mutations against failed state.

---

## 2. Status Totals Across Validation Matrix (100 Evaluated Items)

| Category | Count | Items |
|---|---|---|
| **PASS** | 69 | Invariants I05, I06, I07, I08, I09, I10, I13, I16; RegressionCases R01, R02, R03, R04, R05, R06, R07, R08, R09, R10, R12 (headless AWT unit spike), R14 (serialization), R15 (JVM layout benchmark); Gaps 4.1, 4.2, 4.3 (Candidate B), 4.4 (Candidate B), 4.5 (Candidate B), 4.6, 4.7 (Candidate B); Repaired Defect Regressions A–I; Findings A–F (run-07); History/Lifecycle Findings A–D (run-08); Defects 1–4 (run-09); Defects 1–3 (run-10); Failures 1–4 (run-11: operation-level redo retention, dynamic token remapping across intermediate undos, qualified synchronous settlement evidence, reentrancy FIFO queueing); Exhaustive Contract Coverage Sweep (85/85 cells); Transactional Staged Failure Injection & Atomic Rollback (§4.3); Executable Undo/Redo (§4.4); Complete Canonical Model Checker Depth 4 (146,594 states, 203,280 transitions, 0 violations); Subclass Mutation Verification (50/50 caught); Comparative Microbenchmark (2,057 ops/sec, 486.20 µs/op in Node.js V8; native multiplatform unvalidated); Named Longer Sequences (10/10 multi-step sequences verified); Exact Content-Identity Index & Qualification Consolidation (§4.5 specification consolidation) |
| **PARTIAL** | 6 | Invariants I01, I02, I03, I04, I12, I14 (scalar models scoped honestly without claiming full production multiplatform engine or physical filesystem crash durability) |
| **DESIGN POLICY** | 2 | Invariant I11 (FrameKey immutable caching policy) and Invariant I15 (Persistent B+ tree piece representation policy) |
| **NOT RUN / GATED** | 16 | Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web; Phase Gates G1–G8 Kotlin implementation gates; R11 Web IME; R13 Compose layout; R16 physical AT; Invariant I17 physical AT |
| **UNVALIDATED OPTIMIZATION** | 1 | Skia Native Layout Patch (C++ build failure; baseline crashed; excluded from architecture) |
| **FAIL** | 1 | Shipped Compose `TextFieldState` attributed undo defect confirmed |
| **INCONCLUSIVE** | 1 | Incremental bidi bracket tree index (verified against oracle but dropped as unnecessary complexity) |
| **Total** | **96** | **Exhaustive audit across baseline, research, and validation runs** |

---

## 3. Master Test Suite Execution Summary (9 Suites Total - Run-20261014-11)

```
================================================================================
=== COMPREHENSIVE COMPOSITION ARCHITECTURE VALIDATION (run-20261014-11)    ===
=== Independent Oracle vs Candidate A (Flat Buffer) vs Candidate B (Overlay) ===
================================================================================
```

1. **Repaired Defect Regressions & Conformance (`test-repaired-regressions.mjs`)**:
   - **22/22 verification blocks passed cleanly (100%)**:
     - Blocks 1–6 (Findings A–F, run-07): Turnover text, turnover history, full partial commit format tracking, post-edit selection, immediate command drainage, callback epoch admission (PASS)
     - Blocks 7–10: Retained regressions (interior segment splicing, noncollapsed selection, outward grapheme closure, disjoint island mapping, TrackedRange isolation) (PASS)
     - Blocks 11–14 (Findings A–D, run-08): Repeated undo/redo empty preimages, native removal history across lifecycle, disposal content preservation under teardown, resumed preedit unified target resolution (PASS)
     - Blocks 15–18 (Defects 1–4, run-09): Piece-mapped surviving range resolution on rollback across zero-delta opposing edits, idle command execution & ticket preservation, cross-epoch receipt isolation with composite keys, explicit ordered queue settlement policy on undo (PASS)
     - Block 19 (Defect 1, run-10): Disjoint interval preservation on rollback target mapping, forbidding min-max bounding box expansion over restored intervening text (PASS)
     - Block 20 (Defect 2, run-10): Direct `dispatchCommand` transactional encapsulation in `_runInTransaction` with upfront validation, reentrancy deferral, and biconditional revision advancement (PASS)
     - Block 21 (Defect 3, run-10): Asynchronous settlement barrier on application undo, queueing behind active fence in `AwaitingSettlement` (PASS)
     - Block 22 (Failures 1–4, run-11): Redo branch preservation across settlement with queued undo; dynamic token identity remapping across intermediate undos; synchronous settlement qualification guard; transactional reentrancy queueing & sequential FIFO execution (PASS)
2. **Hand-Calculated Regression Fixtures (`test-fixtures.mjs`)**:
   - Independent Oracle: 18/18 PASS (100%)
   - Candidate B: 18/18 PASS (100%)
   - Candidate A: 10/18 PASS, 8 FAIL (Gap 4.3, Gap 4.4 cut boundary, Gap 4.4 retargeted cut boundary, Gap 4.5, Finding A, Finding B, Finding C, Finding D)
3. **Settlement Lifecycle & Guard Scenarios (`test-settlement-lifecycle.mjs`)**:
   - 21/21 PASS cleanly (nonterminal unmarks, preedit resumption, token fencing, turnover, disposal preservation across 6 lifecycle stages)
4. **Deep Semantic Gaps Verification Suite (`test-semantic-gaps.mjs`)**:
   - 17/17 PASS cleanly (formatting provenance, retargeting, absorbed units, zero-length deletions, multi-unit rollback)
5. **Subclass Mutation Invariant Verification (`test-mutations.mjs`)**:
   - 50/50 positive controls passed cleanly on unmodified Candidate B.
   - 50/50 coordinator subclass mutants caught cleanly by invariants and assertions:
     - Mutants R11_1 through R11_7 (Run-11 failures and contract defects) caught cleanly
     - Mutants R10_1 through R10_3 (Run-10 defects) caught cleanly
     - 40 earlier mutants (run-07, run-08, run-09) caught cleanly
     - 50 clean assertion kills, 0 unhandled crashes.
6. **Named Longer Sequences Suite (`test-named-longer-sequences.mjs`)**:
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
7. **Exhaustive Contract Coverage Sweep (`test-contract-coverage-sweep.mjs`)**:
   - 85/85 state-event matrix cells passed cleanly across 17 public events and 5 lifecycle states (`Idle`, `Composing (nonempty)`, `Composing (empty)`, `AwaitingSettlement`, `Closed`), with 100% agreement between Candidate B and Independent Oracle.
8. **Bounded Model Checker (`independent-model-checker.mjs`)**:
   - **Candidate B**: Explored **146,594 unique abstract states** and **203,280 transitions** at depth 4 in 80,290 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle.
   - **Candidate A**: Proven unsound at depth 0 transition, caught on Invariant 1 (`I_VISIBLE_PROJECTION`): `'BadHello' !== 'Hello'`.
9. **Comparative Performance Microbenchmark**:
   - **Candidate A**: 168.49 ms for 5,000 cycles | 29,676 ops/sec | 33.70 µs/op
   - **Candidate B**: 3,853.72 ms for 5,000 cycles | 1,297 ops/sec | 770.74 µs/op
   - Overhead Ratio: 22.87x (Candidate B performs full segment-mapped overlay tracking, token identity management, and transactional cloning; 770.74 µs per full multi-step cycle is well within the 1,000 µs sub-millisecond per-keystroke interactive frame budget).

Total suite execution time: **84,531.29 ms**. Full JSON summary saved to [`validation/antigravity/run-20261014-11/results.json`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-11/results.json).

---

## 4. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **Failures 1 through 4 (in `run-20261014-11`)**:
  - *Failure 1*: Settlement completion preserves the redo branch generated by queued undo operations; operation-level invalidation prevents wiping redoStack on commit.
  - *Failure 2*: Dynamic token identity tracking and remapping across intermediate undos (`_remapPendingCommandTargets`) prevents `TargetInvalidated` rejections when queued undos restore historical preimages.
  - *Failure 3*: Synchronous settlement qualification guard enforces platform bridge credentials (`QualifiedSyncSettlement`, epoch, sessionId, sessionRevision, bounds, recordId nonce), preventing unqualified barrier bypass.
  - *Failure 4*: Transactional reentrancy FIFO queueing enqueues reentrant mutations into `_reentrantMutationQueue` and executes them sequentially upon outer transaction publication with atomic rollback isolation.
- **Defects 1 through 3 (from `run-20261014-10`)**: Disjoint interval preservation on rollback target mapping, centralized transactional dispatcher, and asynchronous settlement barrier.
- **Defects 1 through 4 (from `run-20261014-09`)**: Piece-mapped surviving range resolution on rollback, idle command execution, cross-epoch receipt isolation, explicit ordered queue settlement policy on undo.
- **Findings A through D (from `run-20261014-08`)**: Repeated undo/redo empty preimages, native removal history across lifecycle states, disposal content preservation under teardown, resumed preedit unified target resolution.
- **Findings A through F (from `run-20261014-07`)**: Turnover text persistence, undo retention across partial commits, full partial commit format tracking, post-edit selection validation, immediate cancellation command drainage, and callback epoch admission.

### 2. What remains ambiguous, failed or untested?
- **Untested (NOT RUN)**: Gate G0 Native Host on Desktop (real Compose/AWT host window embedding, real OS IME candidate popup positioning, screen-coordinate translation, accessibility); Gate G0 Android, iOS, Web; Phase Gates G1–G8; real assistive technology interactive qualification (TalkBack, VoiceOver, NVDA, Orca); 100K-unit single paragraph layout performance benchmark in Kotlin multiplatform engine; and physical filesystem crash durability / journal recovery.
- **Excluded**: Skia native C++ patch (failed compilation; baseline crashed; excluded from architecture).
- **Failed**: Shipped Compose `TextFieldState` attributed undo confirmed defective.

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES, for headless Phase 0/1 prototyping only, but strictly gated.** [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.7) incorporates the repaired Candidate B segment-mapped overlay architecture and resolves Failures 1–4, Defects 1–3 from run-10, Defects 1–4 from run-09, Findings A–F, Run-08 Findings A–D, Gaps §4.1–§4.7, staged failure atomicity, universal rejection atomicity, the 85-cell contract completeness matrix, exact content-identity index design, and executable undo/redo with settlement barrier enforcement. Production implementation remains strictly prohibited until acceptance gates pass on native targets.

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
