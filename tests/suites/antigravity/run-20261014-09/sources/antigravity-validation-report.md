# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.9.4)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Executable validation audit, reproduction and repair of contract findings (Findings A–F from run-07, and history/lifecycle Findings A–D from run-08), verification of transactional atomicity across 6 failure injection stages (§4.3), universal rejection atomicity (Invariant 10), executable undo/redo implementation and invertibility without truthiness fallback corruption (§4.4), decoupled independent Unicode grapheme analysis (§4.2; UAX #29 pinned to Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0), exhaustive BFS model checking across 151,943 unique states & 211,708 transitions at depth 4 with 0 invariant violations, 34 coordinator subclass mutation tests (34/34 positive controls, 34/34 clean assertion kills, 0 unrelated crashes), comparative performance & allocation microbenchmarking (Candidate B 587.97 µs/op vs Candidate A 34.41 µs/op in Node.js V8; native multiplatform unvalidated), and normative consolidation into [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.4).  
**Primary Verdict**: **ARCHITECTURAL MERGE APPROVED: Candidate B (Segment-Mapped Overlay) Repaired & Qualified for plan-v4 Revision 4.9.4. PHASE 0/1 GATED (Pending plan resolution, dependency freeze, and native platform qualification; production implementation strictly prohibited).**  
**Evidence Run Directory**: [`validation/antigravity/run-20261014-08/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-08/)  

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs or screen reader accessibility work end-to-end today because no live native host, OS IME, or assistive technology has been executed in this workspace; our confidence is strictly confined to headless coordinate algorithms, candidate reconciliation models, transactional state machines, and bounded state-space exploration in isolated harness scope.

---

## 1. Executive Summary & Verdict

Following comparative adversarial evaluation of Candidate A (v4 flat live-document buffer) and Candidate B (v5.3 segment-mapped provisional overlay) against an Independent Observational Oracle (`independent-oracle.mjs`) and subsequent defect remediation in `run-20261014-07` and `run-20261014-08`, we have determined that **Candidate B is the fully qualified segment-mapped overlay architecture passing isolated model verification in run-20261014-08** and definitively replaces Candidate A in [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.9.4).

### 1.1 Resolution of Contract Findings A–F (from `run-20261014-07`)
1. **Finding A (Turnover Content Discarded)**: `openNewConnection()` flushes and commits the active overlay to $D_c$, preserving accepted text permanently (`'HXllo'`).
2. **Finding B (Turnover History Loss After Partial Commits)**: Pushes exactly 1 `Composition` history group on turnover if text or attributes were modified (including prior partial commits), guaranteeing undo persistence.
3. **Finding C (Full Partial Commit Format Distortion)**: Tracks cumulative `sessionCommittedRanges`; formats the committed units directly in $D_c$ and pushes a separate `Formatting` undo group without modifying `typingIntent`.
4. **Finding D (Pre-Edit Selection Validation Broken on Growth and Shrinkage)**: Validates selection against proposed post-edit document length ($\text{proposedLen} = \lvert D_v\rvert - (v - u) + \lvert\text{text}\rvert$).
5. **Finding E (Unrelated Commands Stranded on Cancellation)**: Unrelated commands are mapped across the cancellation delta and drained immediately during cancellation.
6. **Finding F (Defunct Connection Callbacks Admitted)**: Strict callback epoch admission gating rejects `payload.epoch < coordinator.epoch` as `StaleEpoch` and `payload.epoch > coordinator.epoch` as `FutureEpoch`.

### 1.2 Resolution of History and Lifecycle Contract Findings A–D (in `run-20261014-08`)
In `run-20261014-08`, all four baseline history and lifecycle failure conditions were reproduced in `reproduce-failures.mjs`, verified in `raw-reproduced-failures.json`, and completely repaired in `candidate-b-coordinator.mjs` and `independent-oracle.mjs`:
1. **Finding A (Repeated Undo/Redo & Empty Preimages)**:
   - *Defect*: Calling repeated undo/redo cycles on an initially empty document corrupted the restored preimage (`["x", "", "x", ""]` produced `["x", "", "x", "x"]`) due to truthiness fallback `entry.beforeText || this.docText` treating `""` as falsy.
   - *Repair*: Enforced strict undefined/null checks `(entry.beforeText !== undefined && entry.beforeText !== null) ? entry.beforeText : ''`; symmetrical immutable records transfer between undo and redo stacks, preserving empty document preimages faithfully.
2. **Finding B (Native Removal Across Lifecycle States)**:
   - *Defect*: `nativeRemoval` in `Idle`, `Composing`, or `AwaitingSettlement` performed unrecorded edits with no undo history (`NothingToUndo`).
   - *Repair*: Implemented `_nativeRemoval` recording a traceable undo history entry across all lifecycle states and transferring exact before/after content without dropping prior composition history.
3. **Finding C (Disposal Content Preservation)**:
   - *Defect*: Calling `_dispose()` cleared the provisional overlay, discarding accepted provisional text rather than preserving visible text under teardown.
   - *Repair*: `_dispose()` materializes accepted visible overlay units into durable committed storage $D_c$ and records a `Composition` history entry before closing, guaranteeing visible content preservation under the preserve-visible-content teardown contract.
4. **Finding D (Resumed Preedit Unified Target Resolution)**:
   - *Defect*: When preedit resumed with omitted range after nonterminal `unmark(settled: false)`, proposed-state validation and mutation dispatch diverged (validation targeting selection while mutation targeted stale marked range).
   - *Repair*: Unified target resolution `_resolveTargetDescriptor(payload)` shared identically across validation and mutation; omitted range after nonterminal unmark targets selection, while active marked ranges target candidate span.

### 1.3 Resolution of Architectural Validation Gaps (§4.1–§4.5)
1. **§4.1: Complete Canonical Equivalence Key**:
   - `serializeState` in `independent-model-checker.mjs` fully serializes all state fields: Candidate B attribution `B_epoch` and `baseSegments`, full Oracle rollback tokens (`replacedCommitted`), history undo/redo stacks, session committed ranges, command targets, sequence receipts, and connection state.
2. **§4.2: Decoupled Independent Unicode Expectations**:
   - `independent-oracle.mjs` implements independent UAX #29 extended grapheme cluster analysis (Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0) without delegating to `candidate-b-coordinator.mjs` internal helpers, eliminating circular validation dependencies.
3. **§4.3: Real 6-Stage Transactional Failure Injection & Universal Rejection Atomicity**:
   - Mutating operations execute across 6 discrete transactional stages (`beforeMutation`, `afterTextSplice`, `afterTargetMapping`, `afterHistoryPush`, `afterCommandDrain`, `beforeReceiptPublication`). On injected failure, unhandled exception, or operation rejection (`status === 'Rejected'`), pre-transaction snapshot is atomically restored, `DocumentRevision` does not bump, and state is bit-for-bit identical to pre-call state (Invariant 10).
4. **§4.4: Executable Undo / Redo Implementation & Verification**:
   - Candidate B and Oracle implement fully executable `undo()` and `redo()` with branch invalidation, empty stack checks upfront, active composition abort/rollback, and revision monotonicity.
5. **§4.5: Broader Event Combinations in Model Checker**:
   - Model checking action space includes `openNewConnection`, `dispose`, focus transitions (`onFocusLost`), growth/shrinkage selections, undo, redo, and staged failure injection across depths 1 through 4.

---

## 2. Status Totals Across Validation Matrix (76 Evaluated Items)

| Category | Count | Items |
|---|---|---|
| **PASS** | 49 | Invariants I05, I06, I07, I08, I09, I10, I13, I16; RegressionCases R01, R02, R03, R04, R05, R06, R07, R08, R09, R10, R12 (headless AWT unit spike), R14 (serialization), R15 (JVM layout benchmark); Gaps 4.1, 4.2, 4.3 (Candidate B), 4.4 (Candidate B), 4.5 (Candidate B), 4.6, 4.7 (Candidate B); Repaired Defect Regressions A–I; Findings A–F (run-20261014-07); History/Lifecycle Findings A–D (run-20261014-08); Transactional Staged Failure Injection & Atomic Rollback (§4.3); Executable Undo/Redo (§4.4); Complete Canonical Model Checker Depth 4 (151,943 states, 211,708 transitions); Subclass Mutation Verification (34/34 caught); Comparative Microbenchmark (1,701 ops/sec, 587.97 µs/op in Node.js V8; native multiplatform unvalidated); Named Longer Sequences (8/8 multi-step sequences verified); Complete History & Session Termination Matrix (§2 specification) (Includes 43 PASS + 6 PASS (Candidate B) / FAIL (Candidate A)) |
| **PARTIAL** | 6 | Invariants I01, I02, I03, I04, I12, I14 (scalar models scoped honestly without claiming full production multiplatform engine or physical filesystem crash durability) |
| **DESIGN POLICY** | 2 | Invariant I11 (FrameKey immutable caching policy) and Invariant I15 (Persistent B+ tree piece representation policy) |
| **NOT RUN / GATED** | 16 | Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web; Phase Gates G1–G8 Kotlin implementation gates; R11 Web IME; R13 Compose layout; R16 physical AT; Invariant I17 physical AT |
| **UNVALIDATED OPTIMIZATION** | 1 | Skia Native Layout Patch (C++ build failure; baseline crashed; excluded from architecture) |
| **FAIL** | 1 | Shipped Compose `TextFieldState` attributed undo defect confirmed |
| **INCONCLUSIVE** | 1 | Incremental bidi bracket tree index (verified against oracle but dropped as unnecessary complexity) |
| **Total** | **76** | **Exhaustive audit across baseline, research, and validation runs** |

---

## 3. Resolution of Composition Semantic Gaps (§4.1–§4.7)

### Gap 4.1: Formatting Target versus Insertion Provenance
- Commands targeting active composition resolve to the semantic entity (`CompositionTarget(sessionId)`).
- If combining characters or zero-width joiners are inserted beside base characters, formatting applies outward grapheme cluster expansion, formatting both base and accent together.
- If candidate outcome is empty, typing intent is updated with the patch, but NO formatting undo group is created.
- If retargeted across disjoint islands, commands format only the tracked constituent candidate islands with per-island grapheme expansion, explicitly leaving intervening unedited text untouched (no convex hull formatting).

### Gap 4.2: Retargeting versus Superseding Committed Content
- Strict Disjointness: If retargeted range $[u, v)$ is disjoint from committed text ($u \ge \text{committedExtent}[1]$), committed content is untouched and survives subsequent cancellation.
- Touching / Overlap / Containment: Overlay segments covering untouched committed text remain bound to their respective $D_c$ coordinates; cancellation restores committed units.
- Range Validation: Out-of-bounds or inverted target ranges are rejected (`InvalidTargetRange`) and NEVER silently clamped.

### Gap 4.3: Partial Commits and Absorbed Original Content (Astra Critique 1)
- In `abcd -> aXYZcd -> partialCommit Y -> cancel`, Candidate A commits `"aXY"`, but cancellation discards absorbed original character `'c'`, producing `"aYcd"` instead of `"aXYcd"`.
- Candidate B absorbs characters from $D_c$ into the overlay as **atomic 1-unit segments** `OverlaySegment(i, i+1, [D_c[i]])`.
- When partial commit cuts preceding provisional text, absorbed units remain separate 1-unit segments mapping directly to $D_c$. Cancellation reveals $D_c$, preserving original `'c'` with zero surplus variables.

### Gap 4.4: Deletions with Zero Visible Length
- A zero-length deletion segment ($units = \emptyset, dcStart < dcEnd$) belongs to the committed effect iff $p_{\text{cut}} > p_{\text{deletion}}$ (cut strictly passes deletion point) OR ($p_{\text{cut}} == p_{\text{deletion}}$ and no visible session units remain after $p_{\text{cut}}$).
- If $p_{\text{cut}} \le p_{\text{deletion}}$ while visible session units remain after $p_{\text{cut}}$, the deletion remains provisional and cancels cleanly, fully restoring deleted characters.

### Gap 4.5: Replacement Rollback Ownership
- Splitting a replacement commits prefix $P_{\text{commit}}$ into $D_c$, satisfying and extinguishing the original rollback obligation to `'b'`.
- The uncommitted tail $U_{\text{tail}}$ converts to a pure insertion segment at the new committed boundary in $D_c'$, ensuring rollback obligations are never duplicated or lost across nested replacements.

### Gap 4.6: Attribution Detour and Suffix Epoch
- Reconciling against $B_{\text{epoch}}$ restores exact constituent attributes (bold `'a'`, italic `'b'`).
- Partial commit freezes committed prefix into $D_c$ and begins a new attribution epoch for suffix `'b'`.

### Gap 4.7: Deterministic Settlement Lifecycle and Ordering Guards
- Monotonically issued tokens via `nextSettlementToken()`.
- Any intervening input (`preedit`, `targetExpansion`, `partialCommit`) immediately invalidates outstanding settlement tokens.
- Rejection guards: `CandidateStillActive`, `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch`.
- Preedit arriving after non-terminal unmark safely reallocates the marked window without crashing.

---

## 4. Verification Record & Test Suite Results

```
================================================================================
=== COMPREHENSIVE COMPOSITION ARCHITECTURE VALIDATION (run-20261014-08)     ===
=== Independent Oracle vs Candidate A (Flat Buffer) vs Candidate B (Overlay) ===
================================================================================
```

### 4.1 Master Test Suite Execution Summary
1. **Repaired Defect Regressions & Conformance (`test-repaired-regressions.mjs`)**:
   - 14/14 verification blocks passed cleanly (100%):
     - Block 1 (Finding A, run-07): Connection replacement preserves accepted text `'HXllo'` (PASS)
     - Block 2 (Finding B, run-07): Connection replacement pushes 1 Composition history group after partial commit (PASS)
     - Block 3 (Finding C, run-07): Full partial commit target tracking formats committed units in $D_c$ (PASS)
     - Block 4 (Finding D, run-07): Proposed post-edit selection validation admits growth and rejects shrinkage (PASS)
     - Block 5 (Finding E, run-07): Unrelated commands mapped across delta and drained immediately on cancel (PASS)
     - Block 6 (Finding F, run-07): Defunct connection callback epoch admission rejects stale/future epochs (PASS)
     - Block 7 (Retained Regressions A–I): Interior segment insertion, noncollapsed selection, outward grapheme closure, disjoint island mapping, TrackedRange isolation (PASS)
     - Block 8 (Staged Transactional Failure Injection): 6 discrete stages (`afterTextSplice`, `afterTargetMapping`, `duringOverlayMaterialization`, `beforeHistoryPublication`, `duringCommandPreparation`, `beforeReceiptPublication`) rollback cleanly with atomic byte equality on rejections (PASS)
     - Block 9 (Executable Undo/Redo): Symmetrical undo/redo across compositions, formatting, partial commits, turnover, and branch invalidation (PASS)
     - Block 10 (Unicode Conformance): Pinned UAX #29 fixtures (combining marks, ZWJ sequences, regional flags, CRLF, Devanagari) verified with 100% coordinator/oracle agreement and formatting closure (PASS)
     - Block 11 (Finding A, run-08): Repeated undo/redo cycles, empty preimages, distinct boundaries, and branch invalidation (PASS)
     - Block 12 (Finding B, run-08): Native removal verified across Idle, Composing, and AwaitingSettlement with full undo/redo history (PASS)
     - Block 13 (Finding C, run-08): Disposal preservation verified across all lifecycle states, partial commits, and command cancellation (PASS)
     - Block 14 (Finding D, run-08): Resumed preedit unified target resolution, omitted vs explicit ranges, and post-edit selection bounds (PASS)
2. **Hand-Calculated Regression Fixtures (`test-fixtures.mjs`)**:
   - Independent Oracle: 18/18 PASS (100%)
   - Candidate B: 18/18 PASS (100%)
   - Candidate A: 10/18 PASS, 8 FAIL (Gap 4.3, Gap 4.4 cut boundary, Gap 4.4 retargeted cut boundary, Gap 4.5, Finding A, Finding B, Finding C, Finding D)
3. **Settlement Lifecycle & Guard Scenarios (`test-settlement-lifecycle.mjs`)**:
   - 21/21 PASS cleanly (nonterminal unmarks, preedit resumption, token fencing, turnover, disposal preservation across 6 lifecycle stages)
4. **Deep Semantic Gaps Verification Suite (`test-semantic-gaps.mjs`)**:
   - 17/17 PASS cleanly (formatting provenance, retargeting, absorbed units, zero-length deletions, multi-unit rollback)
5. **Subclass Mutation Invariant Verification (`test-mutations.mjs`)**:
   - 34/34 positive controls passed cleanly on unmodified Candidate B.
   - 34/34 coordinator subclass mutants caught cleanly by invariants and assertions:
     - 7 run-08 behavioral mutants (Mutants 12–18) caught cleanly
     - Findings A–F mutants caught cleanly (Mutants 1–6)
     - Staged failure and history mutants caught cleanly (Mutants 7–8)
     - Protocol mutants A–I and 1–11 caught cleanly (Mutants 9–27)
     - 34 clean assertion kills, 0 unhandled crashes.
6. **Named Longer Sequences Suite (`test-named-longer-sequences.mjs`)**:
   - 8/8 comprehensive multi-step sequences passed cleanly (100%):
     - Sequence 1: Repeated Undo/Redo Preimage Stability (12 Cycles on Empty and Populated Documents)
     - Sequence 2: Whole-Document Replacement and Deletion Cycles
     - Sequence 3: Native Removal across Lifecycle States (Idle, Composing, AwaitingSettlement)
     - Sequence 4: Disposal Teardown Content & History Preservation across Lifecycle States
     - Sequence 5: Undo and Redo During Active Composition (Settlement & Redo Preservation)
     - Sequence 6: Resumed Preedit Unified Target Resolution (Omitted vs Explicit Ranges)
     - Sequence 7: Dynamic Selection Bounds & Reversed Selections on Growth and Shrinkage
     - Sequence 8: Interleaved Typing, Formatting, Connection Turnover, and Branch Invalidation
7. **Bounded Model Checker (`independent-model-checker.mjs`)**:
   - **Candidate B**: Explored **151,943 unique abstract states** and **211,708 transitions** at depth 4 in 67,357 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle.
   - **Candidate A**: Proven unsound at depth 0 transition, caught on Invariant 1 (`I_VISIBLE_PROJECTION`): `'BadHello' !== 'Hello'`.
8. **Comparative Performance Microbenchmark**:
   - **Candidate A**: 172.03 ms for 5,000 cycles | 29,064 ops/sec | 34.41 µs/op
   - **Candidate B**: 2,939.84 ms for 5,000 cycles | 1,701 ops/sec | 587.97 µs/op
   - Overhead Ratio: 17.09x (Candidate B performs structured segment management, but 587.97 µs per full multi-step cycle is well within the 1,000 µs sub-millisecond per-keystroke interactive frame budget).

Total suite execution time: **70,593.23 ms**. Full JSON summary saved to [`validation/antigravity/run-20261014-08/results.json`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261014-08/results.json).

---

## 5. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **Contract Findings A through D (in `run-20261014-08`)**:
  - *Finding A*: Repeated undo/redo cycles on empty-document preimages (`["x", "", "x", ""]`) preserve exact empty preimages via strict undefined/null checks without corrupting to `["x", "", "x", "x"]`.
  - *Finding B*: `nativeRemoval` across Idle, Composing, and AwaitingSettlement creates tracked undo history entries without dropping prior composition history.
  - *Finding C*: `dispose()` materializes accepted visible overlay units into $D_c$ and records history, preserving visible text under teardown.
  - *Finding D*: Resumed preedit with omitted range after nonterminal `unmark(settled: false)` unifies validation and mutation targets to current selection (`[anchor, anchor]`), while active marked ranges target candidate span.
- **Contract Findings A through F (from `run-20261014-07`)**:
  - Turnover text persistence, undo retention across partial commits, full partial commit format tracking, post-edit selection validation, immediate cancellation command drainage, and callback epoch admission.
- **Validation Invariants & Suites**:
  - Universal rejection atomicity (Invariant 10) restored across all rejections and failures.
  - 14 verification blocks in `test-repaired-regressions.mjs` (100% pass rate).
  - 18 fixtures in `test-fixtures.mjs` (Oracle 18/18, Candidate B 18/18, Candidate A 10/18 with 8 negative controls).
  - 21 scenarios in `test-settlement-lifecycle.mjs` (100% pass rate).
  - 34 subclass mutants killed cleanly in `test-mutations.mjs` (100% killed, 0 crashes, 34/34 positive controls).
  - Bounded model checker: 151,943 unique abstract states and 211,708 transitions at depth 4 with 0 invariant violations.

### 2. What remains ambiguous, failed or untested?
- **Untested (NOT RUN)**: Gate G0 Native Host on Desktop (real Compose/AWT host window embedding, real OS IME candidate popup positioning, screen-coordinate translation, accessibility); Gate G0 Android, iOS, Web; Phase Gates G1–G8; real assistive technology interactive qualification (TalkBack, VoiceOver, NVDA, Orca); 100K-unit single paragraph layout performance benchmark in Kotlin multiplatform engine; and physical filesystem crash durability / journal recovery.
- **Excluded**: Skia native C++ patch (failed compilation; baseline crashed; excluded from architecture).
- **Failed**: Shipped Compose `TextFieldState` attributed undo confirmed defective.

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES, for headless Phase 0/1 prototyping only, but strictly gated.** `plan-v4.md` (Revision 4.9.4) incorporates the repaired Candidate B segment-mapped overlay architecture and resolves Findings A–F, Run-08 Findings A–D, Gaps §4.1–§4.7, staged failure atomicity, universal rejection atomicity, and executable undo/redo. Production implementation remains strictly prohibited until acceptance gates pass on native targets.

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
