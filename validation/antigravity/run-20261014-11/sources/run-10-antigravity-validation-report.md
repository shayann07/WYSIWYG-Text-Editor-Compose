# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.9.6)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Executable validation audit, reproduction and repair of contract findings (Findings A–F from run-07, history/lifecycle Findings A–D from run-08, target-mapping/admission Defects 1–4 from run-09, and contract defects 1–3 from run-10), verification of transactional atomicity across 6 failure injection stages (§4.3), centralized dispatcher encapsulation in `_runInTransaction` across `dispatchCommand`, `undo`, `redo`, and `applyInput` with biconditional revision monotonicity and reentrancy rejection (Defect 2), universal rejection atomicity (Invariant 10), executable undo/redo implementation and invertibility without truthiness fallback corruption (§4.4), disjoint interval target resolution on rollback preserving unedited restored text (Defect 1), asynchronous settlement barrier queueing application undo in `AwaitingSettlement` behind active fences unless qualified by `syncBoundary: true` (Defect 3), decoupled independent Unicode grapheme analysis (§4.2; UAX #29 pinned to Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0), exhaustive 85-cell contract coverage sweep (17 public events × 5 states), exhaustive BFS model checking across 146,584 unique states & 203,280 transitions at depth 4 with 0 invariant violations, 43 coordinator subclass mutation tests (43/43 positive controls, 43/43 clean assertion kills, 0 unrelated crashes), comparative performance & allocation microbenchmarking (Candidate B 583.12 µs/op vs Candidate A 30.65 µs/op in Node.js V8; native multiplatform unvalidated), and normative consolidation into [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.6).  
**Primary Verdict**: **ARCHITECTURAL MERGE APPROVED: Candidate B (Segment-Mapped Overlay) Repaired & Qualified for plan-v4 Revision 4.9.6. PHASE 0/1 GATED (Pending plan resolution, dependency freeze, and native platform qualification; production implementation strictly prohibited).**  
**Evidence Run Directory**: [`validation/antigravity/run-20261014-10/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-10/)  

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs or screen reader accessibility work end-to-end today because no live native host, OS IME, or assistive technology has been executed in this workspace; our confidence is strictly confined to headless coordinate algorithms, candidate reconciliation models, transactional state machines, and bounded state-space exploration in isolated harness scope.

---

## 1. Executive Summary & Verdict

Following comparative adversarial evaluation of Candidate A (v4 flat live-document buffer) and Candidate B (v5.3 segment-mapped provisional overlay) against an Independent Observational Oracle (`independent-oracle.mjs`) and subsequent defect remediation across runs 07, 08, 09, and 10, we have determined that **Candidate B is the fully qualified segment-mapped overlay architecture passing isolated model verification in run-20261014-10** and definitively replaces Candidate A in [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.6).

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

### 1.4 Resolution of Critical Contract Failures 1–3 (in `run-20261014-10`)
1. **Defect 1 (Rollback Mapping Silently Expands Target Over Restored Text)**:
   - *Defect*: `mapTrackedRangeOnCancel` collected surviving token indices and returned a min-max bounding box `[min, max + 1]`. When provisional deletions were cancelled, unedited restored intervening characters (e.g. `'c'` at index 2 in `"abcde"` when selecting `"bd"`) were silently incorporated into the outcome range (`[1, 4]`), causing them to be erroneously formatted.
   - *Repair*: Implemented strict **Disjoint Interval Preservation**. `mapTrackedRangeOnCancel` partitions surviving token indices into contiguous maximal intervals and returns disjoint spans: `ranges: [[s0, e0], [s1, e1], ...]`. Min-max bounding box expansion is strictly forbidden. Commands apply formatting only to surviving units, preserving restored baseline characters without distortion.
2. **Defect 2 (Advertised Public Dispatcher Bypasses Transaction Accounting)**:
   - *Defect*: Direct calls to `dispatchCommand` mutated document text/attributes and created history entries without entering transaction encapsulation (`_inTransaction`), without advancing `documentRevision`, and leaking tickets on upfront validation failure.
   - *Repair*: Centralized transaction execution in `_runInTransaction` across `dispatchCommand`, `undo`, `redo`, and `applyInput`. Validates inputs upfront before ticket allocation or mutation; rejects reentrant calls (`ReentrantMutation`); restores snapshot atomically on failure while preserving ticket records in `commandOutcomes`; and enforces biconditional `documentRevision` advancement (`textChanged || attrsChanged`).
3. **Defect 3 (Undo Ordering Specified by Bypassing the Settlement Barrier)**:
   - *Defect*: Invoking `undo()` during active composition or awaiting settlement unconditionally flushed the overlay, cleared fences, and transitioned to `Idle` without native settlement evidence, allowing subsequent native commits to insert corrupted phantom text (e.g. `'aXYc'`).
   - *Repair*: Enforced the **Asynchronous Settlement Barrier**. In asynchronous platforms (default), `undo()` queues behind the active settlement obligation in `settlementObligation.pendingCommands` with `{ status: 'Pending', ticketId }`, issuing a settlement token and transitioning to / remaining in `AwaitingSettlement` (`action: 'QueuedBehindSettlement'`). Immediate synchronous turnover is permitted if and only if the adapter explicitly provides synchronous turnover qualification (`syncBoundary: true`) along with concrete settlement evidence. Terminal settlement completion drains queued undo in causal sequence against the settled document state.

---

## 2. Status Totals Across Validation Matrix (87 Evaluated Items)

| Category | Count | Items |
|---|---|---|
| **PASS** | 60 | Invariants I05, I06, I07, I08, I09, I10, I13, I16; RegressionCases R01, R02, R03, R04, R05, R06, R07, R08, R09, R10, R12 (headless AWT unit spike), R14 (serialization), R15 (JVM layout benchmark); Gaps 4.1, 4.2, 4.3 (Candidate B), 4.4 (Candidate B), 4.5 (Candidate B), 4.6, 4.7 (Candidate B); Repaired Defect Regressions A–I; Findings A–F (run-07); History/Lifecycle Findings A–D (run-08); Defects 1–4 (run-09); Defects 1–3 (run-10: disjoint rollback interval preservation, centralized transactional dispatcher, asynchronous settlement barrier); Exhaustive Contract Coverage Sweep (85/85 cells); Transactional Staged Failure Injection & Atomic Rollback (§4.3); Executable Undo/Redo (§4.4); Complete Canonical Model Checker Depth 4 (146,584 states, 203,280 transitions); Subclass Mutation Verification (43/43 caught); Comparative Microbenchmark (1,715 ops/sec, 583.12 µs/op in Node.js V8; native multiplatform unvalidated); Named Longer Sequences (8/8 multi-step sequences verified); Master State Transition Matrix (§4.5 specification consolidation) |
| **PARTIAL** | 6 | Invariants I01, I02, I03, I04, I12, I14 (scalar models scoped honestly without claiming full production multiplatform engine or physical filesystem crash durability) |
| **DESIGN POLICY** | 2 | Invariant I11 (FrameKey immutable caching policy) and Invariant I15 (Persistent B+ tree piece representation policy) |
| **NOT RUN / GATED** | 16 | Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web; Phase Gates G1–G8 Kotlin implementation gates; R11 Web IME; R13 Compose layout; R16 physical AT; Invariant I17 physical AT |
| **UNVALIDATED OPTIMIZATION** | 1 | Skia Native Layout Patch (C++ build failure; baseline crashed; excluded from architecture) |
| **FAIL** | 1 | Shipped Compose `TextFieldState` attributed undo defect confirmed |
| **INCONCLUSIVE** | 1 | Incremental bidi bracket tree index (verified against oracle but dropped as unnecessary complexity) |
| **Total** | **87** | **Exhaustive audit across baseline, research, and validation runs** |

---

## 3. Master Test Suite Execution Summary (9 Suites Total - Run-20261014-10)

```
================================================================================
=== COMPREHENSIVE COMPOSITION ARCHITECTURE VALIDATION (run-20261014-10)    ===
=== Independent Oracle vs Candidate A (Flat Buffer) vs Candidate B (Overlay) ===
================================================================================
```

1. **Repaired Defect Regressions & Conformance (`test-repaired-regressions.mjs`)**:
   - **21/21 verification blocks passed cleanly (100%)**:
     - Blocks 1–6 (Findings A–F, run-07): Turnover text, turnover history, full partial commit format tracking, post-edit selection, immediate command drainage, callback epoch admission (PASS)
     - Blocks 7–10: Retained regressions (interior segment splicing, noncollapsed selection, outward grapheme closure, disjoint island mapping, TrackedRange isolation) (PASS)
     - Blocks 11–14 (Findings A–D, run-08): Repeated undo/redo empty preimages, native removal history across lifecycle, disposal content preservation under teardown, resumed preedit unified target resolution (PASS)
     - Blocks 15–18 (Defects 1–4, run-09): Piece-mapped surviving range resolution on rollback across zero-delta opposing edits, idle command execution & ticket preservation, cross-epoch receipt isolation with composite keys, explicit ordered queue settlement policy on undo (PASS)
     - Block 19 (Defect 1, run-10): Disjoint interval preservation on rollback target mapping, forbidding min-max bounding box expansion over restored intervening text; verified across reversed selections, collapsed targets, fully discarded targets, and partial commit followed by cancel (PASS)
     - Block 20 (Defect 2, run-10): Direct `dispatchCommand` transactional encapsulation in `_runInTransaction` with upfront validation, reentrancy protection, and biconditional `documentRevision` advancement; verified direct public calls (`setSelection`, `openNewConnection`, `dispose`, `nativeRemoval`) and closed coordinator rejections across all 8 direct methods (PASS)
     - Block 21 (Defect 3, run-10): Asynchronous settlement barrier on application undo, queueing behind active fence in `AwaitingSettlement` without speculatively fabricating settlementEvidence, two-step settlement lifecycle with `completeSettlement`, and causal drainage of queued formatting and undos (PASS)
2. **Hand-Calculated Regression Fixtures (`test-fixtures.mjs`)**:
   - Independent Oracle: 18/18 PASS (100%)
   - Candidate B: 18/18 PASS (100%)
   - Candidate A: 10/18 PASS, 8 FAIL (Gap 4.3, Gap 4.4 cut boundary, Gap 4.4 retargeted cut boundary, Gap 4.5, Finding A, Finding B, Finding C, Finding D)
3. **Settlement Lifecycle & Guard Scenarios (`test-settlement-lifecycle.mjs`)**:
   - 21/21 PASS cleanly (nonterminal unmarks, preedit resumption, token fencing, turnover, disposal preservation across 6 lifecycle stages)
4. **Deep Semantic Gaps Verification Suite (`test-semantic-gaps.mjs`)**:
   - 17/17 PASS cleanly (formatting provenance, retargeting, absorbed units, zero-length deletions, multi-unit rollback)
5. **Subclass Mutation Invariant Verification (`test-mutations.mjs`)**:
   - 43/43 positive controls passed cleanly on unmodified Candidate B.
   - 43/43 coordinator subclass mutants caught cleanly by invariants and assertions:
     - Mutant_RollbackMinMaxExpansion (Defect 1) caught cleanly
     - Mutant_DispatchBypassesTransaction (Defect 2) caught cleanly
     - Mutant_PrematureIdleOnAsyncUndo (Defect 3) caught cleanly
     - 40 earlier mutants (run-07, run-08, run-09) caught cleanly
     - 43 clean assertion kills, 0 unhandled crashes.
6. **Named Longer Sequences Suite (`test-named-longer-sequences.mjs`)**:
   - 8/8 comprehensive multi-step sequences passed cleanly (100%):
     - Sequence 1: Repeated Undo/Redo Preimage Stability (12 Cycles on Empty and Populated Documents)
     - Sequence 2: Whole-Document Replacement and Deletion Cycles
     - Sequence 3: Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
     - Sequence 4: Disposal Teardown Content & History Preservation across Lifecycle States
     - Sequence 5: Undo and Redo During Active Composition (with `syncBoundary: true` qualification)
     - Sequence 6: Resumed Preedit Unified Target Resolution (Omitted vs Explicit Ranges)
     - Sequence 7: Dynamic Selection Bounds & Reversed Selections on Growth and Shrinkage
     - Sequence 8: Interleaved Typing, Formatting, Connection Turnover, and Branch Invalidation
7. **Exhaustive Contract Coverage Sweep (`test-contract-coverage-sweep.mjs`)**:
   - 85/85 state-event matrix cells passed cleanly across 17 public events and 5 lifecycle states (`Idle`, `Composing (nonempty)`, `Composing (empty)`, `AwaitingSettlement`, `Closed`), with 100% agreement between Candidate B and Independent Oracle.
8. **Bounded Model Checker (`independent-model-checker.mjs`)**:
   - **Candidate B**: Explored **146,584 unique abstract states** and **203,280 transitions** at depth 4 in 79,612 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle.
   - **Candidate A**: Proven unsound at depth 0 transition, caught on Invariant 1 (`I_VISIBLE_PROJECTION`): `'BadHello' !== 'Hello'`.
9. **Comparative Performance Microbenchmark**:
   - **Candidate A**: 153.26 ms for 5,000 cycles | 32,625 ops/sec | 30.65 µs/op
   - **Candidate B**: 2,915.62 ms for 5,000 cycles | 1,715 ops/sec | 583.12 µs/op
   - Overhead Ratio: 19.02x (Candidate B performs full segment-mapped overlay tracking and cloning, but 583.12 µs per full multi-step cycle is well within the 1,000 µs sub-millisecond per-keystroke interactive frame budget).

Total suite execution time: **82,925.01 ms**. Full JSON summary saved to [`validation/antigravity/run-20261014-10/results.json`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-10/results.json).

---

## 4. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **Defects 1 through 3 (in `run-20261014-10`)**:
  - *Defect 1*: `mapTrackedRangeOnCancel` preserves disjoint intervals `ranges: [[s0, e0], [s1, e1], ...]` rather than expanding a min-max bounding box over restored intervening text.
  - *Defect 2*: Direct `dispatchCommand` executes exclusively within `_runInTransaction`, validates upfront without ticket leaks, rejects reentrancy, restores pre-tx snapshot on failure while preserving ticket records, and advances `documentRevision` biconditionally iff visible text or attributes changed.
  - *Defect 3*: Application `undo()` respects the asynchronous settlement barrier, queueing in `AwaitingSettlement` behind active settlement fences unless the adapter explicitly passes `syncBoundary: true` with verified settlement evidence.
- **Defects 1 through 4 (from `run-20261014-09`)**:
  - Piece-mapped surviving range resolution on rollback across zero-delta opposing edits; synchronous execution and outcome preservation of Idle commands; cross-epoch receipt isolation with composite keys; and explicit ordered queue settlement policy on undo.
- **Contract Findings A through D (from `run-20261014-08`)**:
  - Repeated undo/redo empty preimages, native removal history across lifecycle states, disposal content preservation under teardown, resumed preedit unified target resolution.
- **Contract Findings A through F (from `run-20261014-07`)**:
  - Turnover text persistence, undo retention across partial commits, full partial commit format tracking, post-edit selection validation, immediate cancellation command drainage, and callback epoch admission.

### 2. What remains ambiguous, failed or untested?
- **Untested (NOT RUN)**: Gate G0 Native Host on Desktop (real Compose/AWT host window embedding, real OS IME candidate popup positioning, screen-coordinate translation, accessibility); Gate G0 Android, iOS, Web; Phase Gates G1–G8; real assistive technology interactive qualification (TalkBack, VoiceOver, NVDA, Orca); 100K-unit single paragraph layout performance benchmark in Kotlin multiplatform engine; and physical filesystem crash durability / journal recovery.
- **Excluded**: Skia native C++ patch (failed compilation; baseline crashed; excluded from architecture).
- **Failed**: Shipped Compose `TextFieldState` attributed undo confirmed defective.

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES, for headless Phase 0/1 prototyping only, but strictly gated.** `plan-v4.md` (Revision 4.9.6) incorporates the repaired Candidate B segment-mapped overlay architecture and resolves Defects 1–3, Defects 1–4 from run-09, Findings A–F, Run-08 Findings A–D, Gaps §4.1–§4.7, staged failure atomicity, universal rejection atomicity, the 85-cell contract completeness matrix, and executable undo/redo with settlement barrier enforcement. Production implementation remains strictly prohibited until acceptance gates pass on native targets.

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
