# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.8)

**Date**: 14 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Executable validation audit, reproduction of run-03 defects (silent clamping & DocumentRevision metadata contradiction), exact native-range proposal fidelity (§4.3, §4.4), decoupled DocumentRevision metadata-freeze rule (§2.2, §4.4), target expansion full envelope absorption synchronization (§4.3, §4.4), deterministic monotonic settlement token lifecycle and invalidation on new input (§4.5), distinct cancellation semantics (`nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`), dynamic queued command range target mapping, authentic BFS model checking with sound sequence stamping (7,767 states, 8,286 transitions), rebuilt executable invariant assertions matching advertised property names, 11 coordinator subclass mutation verification, and specification alignment against [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.8).  
**Primary Verdict**: **PHASE 0/1 GATED (Pending plan resolution, dependency freeze, and native platform qualification; production implementation strictly prohibited).**  
**Evidence Run Directory**: [`validation/antigravity/run-20261013-04/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-04/)  

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs or screen reader accessibility work end-to-end today because no live native host, OS IME, or assistive technology has been executed in this workspace; our confidence is strictly confined to the mathematical correctness of headless coordinate algorithms, candidate reconciliation models, and transaction state machines in isolated harness scope.

---

## 1. Executive Summary & Verdict

Following adversarial falsification of run-03, an independent audit and executable validation was conducted against both the specification and validation harnesses. We successfully reproduced the run-03 silent clamping defect (`HXQlo` instead of `HQlo`) and DocumentRevision metadata-freeze contradiction via `reproduce-native-range-failure.mjs`, resolved both defects in the coordinator and normative specification, implemented deterministic settlement token lifecycles and distinct cancellation semantics, established dynamic queued command range target mapping, synchronized target expansion envelope absorption for committed units and historical extents, verified 7,767 abstract states and 8,286 transitions under authentic BFS model checking with sound sequence stamping, caught 11/11 coordinator subclass mutants, and advanced [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) to Revision 4.8.

### Summary of Architectural Repairs in Revision 4.8:
1. **Exact Native-Range Proposal Fidelity (§4.3, §4.4)**: Silent clamping of `newStart` to `committedExtent[1]` is eliminated. Admitted `markedRange` equals the exact requested `[newStart, newEnd]`. Earlier committed units are absorbed into the active candidate baseline if `newStart <= committedExtent[0]` or split if `committedExtent[0] < newStart < committedExtent[1]`.
2. **Target Expansion Full Absorption Synchronization (§4.3, §4.4)**: When target expansion completely encompasses the committed extent (`newStart <= committedExtent[0]`), both active and historical committed units and extents are cleared (`committedUnits = []`, `committedExtent = null`, `historicalCommittedUnits = []`, `historicalCommittedExtent = null`). This guarantees that subsequent composition cancellation reverts cleanly to the pre-composition snapshot without leaving orphaned frozen units in the document.
3. **DocumentRevision vs SessionRevision Metadata Decoupling (§2.2, §4.4)**: `DocumentRevision` advances if and only if semantic document content (text or raw attributes) changes. A metadata-only freeze (`partialCommit`) leaves document buffer identical, advancing `SessionRevision++` with `DocumentRevision` unchanged.
4. **Deterministic Settlement Token Lifecycle & Ordering Contract (§4.4, §4.5)**: Monotonically generated tokens via `nextSettlementToken()`; invalidated on intervening candidate input mutations (`preedit`, `targetExpansion`, `partialCommit`); rejected if candidate active (`CandidateStillActive`), token obsolete (`ObsoleteSettlementToken`), or session/epoch mismatch (`SessionMismatch`, `StaleEpoch`); strictly prevents `[0, 0]` extent fallbacks.
5. **Distinct Cancellation Semantics (§4.4, §4.5)**: Separates `nativeRemoval` (uncommitted candidate excised; earlier frozen units and expansion surplus preserved), `endCompositionPreservingText` (candidate accepted verbatim as committed text), and `applicationAbort` (explicit user/app rollback).
6. **Dynamic Queued Command Range Target Mapping (§4.5)**: Range targets of queued commands are dynamically remapped across candidate expansions, contractions, and cancellation rollbacks so commands track intended offsets in the document buffer.
7. **Sound BFS Sequence Stamping & Rebuilt Invariant Assertions (§5, §9)**: Legitimate actions pass monotonically increasing sequence stamps (`seq = depth + 1` for depth 0..3), while duplicate sequence checks query existing cached receipts, eliminating false epoch drift and exploring exactly 7,767 unique abstract states and 8,286 transitions without property violations. All 10 invariant assertions (`I_FROZEN_PRESERVED` through `I_ATOMIC_FAILURE`) were rebuilt to strictly enforce advertised properties.
8. **Honest Durability and Native Scoping (§3, §8, §9.4)**: Invariant I14 is scoped honestly to Serialization Round-Trip only (marked `PARTIAL`); physical crash recovery and journal tail recovery are deferred to Phase 7 Gate G7; Gate G0 Desktop is marked `NOT RUN / INCOMPLETE`; and the unvalidated Skia native patch is excluded from the architecture as an `UNVALIDATED OPTIMIZATION`.

### Status Totals Across Validation Matrix (48 Evaluated Items):
- **PASS**: 21 (Model tests, headless coordinate spike, and replicated research baselines)
- **PARTIAL**: 6 (Invariants I01, I02, I03, I04, I12, I14 scalar models—scoped honestly without claiming full production engine or physical crash durability)
- **DESIGN POLICY**: 2 (FrameKey caching and persistent B+ tree architecture specifications)
- **NOT RUN**: 16 (Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web, G1–G8 Kotlin phase gates; R11 Web IME, R13 Compose layout, R16 physical AT, I17 physical AT)
- **UNVALIDATED OPTIMIZATION**: 1 (Skia Native Layout Patch rejected and excluded from baseline)
- **FAIL**: 1 (Shipped Compose `TextFieldState` attributed undo confirmed defective)
- **INCONCLUSIVE**: 1 (Incremental bidi bracket tree index dropped)
- **Total Evaluated Items**: 48

---

## 2. Defect Reproductions & State Model Repairs

All defects from run-02 and run-03 were independently reproduced and logged in the evidence directory:

### Defect A: Settlement Loses Composition Identity and History Integrity
- **Reproduction**: Starting composition with sessionId `"s"`, preediting `"X"`, queueing Bold targeting `CompositionTarget("s")`, and invoking `unmark(settled: false)` followed by `unmark(settled: true)`.
- **Observed Failures in Run-02**:
  1. First unmark cleared `activeSessionId` to `null` prematurely and pushed a composition undo entry prematurely.
  2. Second unmark changed remembered extent to `[0, 0]`.
  3. Two composition history entries were created in `undoStack`.
  4. Bold was never applied to `'X'` because extent was reset to `[0, 0]`.
- **Repair**: Decoupled `Marked Range` lifetime from `Composition History Session` and `Settlement Obligation` (§4.2). In §4.4, non-terminal `unmark` clears transient marks, defers history push, and retains session. A second unmark in `AwaitingSettlement` returns `Rejected(AlreadyUnmarked)`. Explicit `completeSettlement(sessionId)` finalizes 1 history entry and drains queued commands to exact extent `[1, 2)`.

### Defect B: Target Expansion Destroys Partial-Commit Protection
- **Reproduction**: Composing `[1, 2)` in `"Hello"`, preediting `"XY"`, partially committing 1 unit (freezing `"X"`), expanding target to `[2, 4)` (covering `"Yl"`), and cancelling.
- **Observed Failures in Run-02**:
  1. Target expansion wiped `frozenPrefixUnits` to `[]`.
  2. Cancellation fell back to restoring `compositionBeforeSnapshot` (`"Hello"`), erasing committed `"X"`.
  3. `undoStack` had 0 entries, completely dropping committed text from history.
- **Repair**: §4.3 and §4.4 mandate that target expansion preserves committed units and extent, clamping `newStart >= committedExtent[1]`. Suffix cancellation after expansion restores prefix and suffix surplus characters swallowed from the document beyond the prior candidate, keeps frozen prefix units committed under Invariant I08, and records 1 `Composition` history entry.

### Defect C: Connection Replacement Drops Composition History
- **Reproduction**: Composing `[1, 2)` in `"Hello"`, preediting `"X"` (producing `"HXllo"`), and opening a new connection.
- **Observed Failures in Run-02**:
  `"HXllo"` remained in the document, `compositionBeforeSnapshot` was discarded, and `undoStack` was left empty (0 entries), leaving modified text outside attributed history.
- **Repair**: §4.2, §4.4, and §7 require that `openNewConnection()`, `closeConnection()`, and `dispose()` check if an active composition modified document text; if so, push exactly 1 `Composition` history entry to `undoStack` before resetting transient state.

### Defect D: Repeated Identical Preedit Advancing DocumentRevision
- **Reproduction**: Preediting `"X"` followed by identical preedit `"X"`.
- **Observed Failures in Run-02**:
  `documentRevision` advanced from 1 to 2 despite no semantic change to document text or attributes.
- **Repair**: §2.2, §4.4, and §7 establish normative separation: `DocumentRevision` advances if and only if semantic text, attributes, structure, or resources change; `SessionRevision` tracks all published session events.

### Defect E: Run-03 Clamping Corrupts Native-Range Target Expansion
- **Reproduction**: In `"Hello"`, compose `[1, 2)` with preedit `"HX"`, partially commit 1 unit (`"H"`, freezing `committedExtent = [1, 2]`), expand target to encompass `[1, 3)` (covering `"Xl"`), and preedit `"Q"`.
- **Observed Failures in Run-03**:
  `targetExpansion` clamped `newStart = Math.max(newStart, this.committedExtent[1])` (`Math.max(1, 2) = 2`), forcing `markedRange = [2, 3]`. The preedit replaced only `"X"`, producing `"HXQlo"` instead of `"HQlo"`.
  *(Archived in `validation/antigravity/run-20261013-04/reproduce-native-range-failure.log`)*.
- **Repair**: Exact native-range proposal fidelity (§4.3, §4.4). Silent clamping is eliminated. Admitted `markedRange = [newStart, newEnd]` equals exact requested range. Earlier committed units are absorbed into active candidate baseline if `newStart <= committedExtent[0]` or split if `committedExtent[0] < newStart < committedExtent[1]`.

### Defect F: Run-03 DocumentRevision Metadata Freeze Contradiction
- **Reproduction**: Start composition, preedit `"X"`, invoke `partialCommit(1)`.
- **Observed Failures in Run-03**:
  `partialCommit` unconditionally bumped `documentRevision` from 1 to 2, despite `docText` (`"HXllo"`) and `docAttributes` remaining 100% identical.
  *(Archived in `validation/antigravity/run-20261013-04/reproduce-native-range-failure.log`)*.
- **Repair**: §2.2 and §4.4 strictly decouple `DocumentRevision` from metadata freeze events. `partialCommit` advances `SessionRevision++` with `DocumentRevision` unchanged. `DocumentRevision` advances if and only if `(newText !== prevDocText) || !areAttrsEqual(newAttrs, prevDocAttrs)`.

---

## 3. Independent Execution of Existing Evidence

All historical research baselines and probes were independently re-executed in this workspace:

| Evidence File / Probe | Environment | Command / Invocation | Exit Code | Observed Output / Findings |
|---|---|---|---|---|
| `research/grapheme-style-roundtrip.mjs` | Node v24.20.0 | `node research/grapheme-style-roundtrip.mjs` | 0 | 16 style combinations passed; confirms v1 normalization loses styles, while retained attribution preserves them. |
| `research/existing-attribution-control.mjs` | Node v24.20.0 | `node research/existing-attribution-control.mjs` | 0 | ProseMirror model 1.25.12 preserves marks across ZWJ insertion/deletion without undo. |
| `research/composition-path-dependence.mjs` | Node v24.20.0 | `node research/composition-path-dependence.mjs` | 0 | Baseline prefix/suffix reconciliation loses marks on detour `ab -> x -> ab`; demonstrates defect motivating §4.3. |
| `research/composition-history-probe.mjs` | Node v24.20.0 | `node research/composition-history-probe.mjs` | 0 | Proves mapped range history without candidate dependency representation leaves marks on replacement text. |
| `research/compose-probe/Probe.kt` | JVM OpenJDK 23.0.2 | `& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp compile exec:java` | 0 | Confirms published Compose Desktop 1.12.1 loses styles on undo and clears synthetic composition on style changes. |
| `research/compose-probe/UnicodeProbe.kt` | JVM OpenJDK 23.0.2 | `& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=UnicodeProbeKt" compile exec:java` | 0 | Proves legacy Compose command deletes malformed high surrogates; state adapter deviates from no-op contract. |
| `research/compose-probe/ChannelProbe.kt` | JVM OpenJDK 23.0.2 | `& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=ChannelProbeKt" compile exec:java` | 0 | Confirms 60,000 comparisons pass for 3 channels; reproduces `InsideOnly` hidden interval obstruction. |
| `research/compose-probe/HiddenStateProbe.kt` | JVM OpenJDK 23.0.2 | `& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=HiddenStateProbeKt" compile exec:java` | 0 | Confirms 8,500 queries pass; establishes quadratic worst-case information bound for overlapping `InsideOnly` spans. |
| `research/compose-probe/LayoutProbe.kt` | JVM OpenJDK 23.0.2 | `$probeCp = 'target/classes;' + (Get-Content -Raw target/layout-classpath.txt).Trim(); java -cp $probeCp LayoutProbeKt '32,128' 4` | 0 | Bounded run reproduces that Compose intrinsics (0.41–1.75 ms) dominates native bidi construction (0.005–0.015 ms) by >100x. |
| `research/unicode-oracle/bracket-unicode-oracle` | OpenJDK 23.0.2 + Node | `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot"; node research/bracket-unicode-oracle.test.mjs` | 0 | 1,967 non-overflow fixtures agree with Unicode 8.0 Java reference; 72,490 position checks pass. |
| `research/unicode-oracle/bidi-block-context-oracle` | OpenJDK 23.0.2 + Node | `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot"; node research/bidi-block-context-oracle.test.mjs` | 0 | 12,418 requests checked against Unicode 8 Java reference; 1,749,721 direction comparisons pass. |
| `research/native-layout-source/build-patched.log` | MSVC C++17 | Source inspection of build logs | 2 | Compilation failed (missing `<bit>`, `countl_zero` under C++17); baseline crashed (-1073741795). Confirms patch is UNVALIDATED / REJECTED. |

---

## 4. Repaired Executable Specification Tests & Model Checking

Under [`validation/antigravity/run-20261013-04/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-04/):
- **`runner.mjs`**: Master test harness executing all 10 validation suites in 1,588 ms with 19/19 test cases PASS (`results.json`):
  * **`reproduce-native-range-failure.mjs` (PASS)**: Accurately reproduces run-03 clamping failure (`HXQlo` vs `HQlo`) and DocumentRevision metadata-freeze contradiction.
  * **`test-coordinator-repairs.mjs` (PASS)**: 11 targeted test suites verifying Defects A-F, exact native range proposal fidelity, DocumentRevision decoupling, settlement request, command cancellation, target expansion surplus, and preedit resumption.
  * **`test-settlement-after-new-input.mjs` (PASS)**: 7 comprehensive scenarios verifying settlement token monotonic issuance, invalidation on new input (`preedit`, `targetExpansion`, `partialCommit`), rejection of active candidates (`CandidateStillActive`), rejection of obsolete tokens (`ObsoleteSettlementToken`), session/epoch mismatch guards (`SessionMismatch`, `StaleEpoch`), and strict prevention of `[0, 0]` extent fallbacks.
  * **`test-normative-cancellation-and-mapping.mjs` (PASS)**: 8 scenarios verifying distinct cancellation semantics (`nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`), target expansion surplus restoration, and dynamic command range re-targeting across candidate expansions, contractions, and cancellation rollbacks.
  * **`test-r01-to-r05.mjs` (PASS)**: 5 requirement suites covering ZWJ constituent styles (R01), detour baseline reconciliation (R02), partial commit (R03), target expansion (R04), and queued commands / settlement (R05).
  * **`test-r06-to-r10.mjs` (PASS)**: 5 requirement suites covering epochs/dedup (R06), code-point/caret boundaries (R07), style transaction rollback (R08), block split/join (R09), and links/formatting inheritance (R10).
  * **`test-r14-and-properties.mjs` (PASS)**: R14 serialization round-trip verbatim raw UTF-16 and conversion report, plus 10,000 randomized stateful property iterations for Property I07-I08 with 0 failures.
  * **`DesktopAwtInputMethodRequestsSpike.java` (PASS)**: Headless JVM suite with 11 unit tests verifying all 66 valid committed-text slices against an independent oracle, composed-text coordinate geometry, empty slice handling, and attribute filtering.
  * **`independent-model-checker.mjs` (PASS)**: Authentic BFS state-space exploration across **7,767 unique abstract reachable states** and **8,286 transitions** in 299 ms, evaluating all 10 formal invariants on EVERY transition with 0 violations. Sound sequence stamping (`seq = depth + 1` for actions, receipt cache querying for duplicate stamps) guarantees sound deduplication without phantom branch inflation. Subclass polymorphism preserved via dynamic `new c.constructor(...)`.
  * **`test-mutations.mjs` (PASS)**: 11 coordinator subclass mutants injected with actual defects; 11/11 caught cleanly:
    1. *Mutant 1 (Silent range clamping)*: caught by Invariant 7 (`I_NATIVE_RANGE_FIDELITY`) with minimal counterexample trace `startComposition -> partialCommit 1 -> targetExpansion [1, 4]`.
    2. *Mutant 2 (Deleting duplicate committed character)*: caught by Invariant 1 (`I_FROZEN_PRESERVED`) with text mismatch `'Xl' !== 'XX'`.
    3. *Mutant 3 (Dropping required history entry)*: caught by Invariant 2 (`I_HISTORY_PREIMAGE_AND_OBLIGATION`) with trace `startComposition -> commit Z`.
    4. *Mutant 4 (Formatting wrong in-bounds range)*: caught by Invariant 4 (`I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY`) with trace `format [1, 2]`.
    5. *Mutant 5 (Premature command execution before settlement)*: caught by Invariant 5 (`I_NO_SETTLEMENT_BYPASS`) with trace `startComposition -> format composing`.
    6. *Mutant 6 (Losing command outcome record)*: caught by Invariant 6 (`I_COMMAND_OUTCOME`) with trace `startComposition -> format composing -> unmark repeat`.
    7. *Mutant 7 (Replacing native selection with [0, 0])*: caught by Invariant 7 (`I_RAW_SELECTION`) with trace `startComposition`.
    8. *Mutant 8 (Metadata-only DocumentRevision bump)*: caught by Invariant 8 (`I_REVISION_RULES`) with trace `startComposition -> partialCommit 1`.
    9. *Mutant 9 (Partial mutation without atomic rollback)*: caught by Invariant 10 (`I_ATOMIC_FAILURE`) with length mismatch `9 !== 5`.
    10. *Mutant 10 (Stale settlement completion while candidate active)*: caught by Invariant 9 (`I_IDENTITY_ISOLATION`) with trace `startComposition -> completeSettlement while active`.
    11. *Mutant 11 (Receipt state loss breaking duplicate sequence detection)*: caught by Invariant 9 (`I_IDENTITY_ISOLATION`) with status mismatch `'Accepted' !== 'Duplicate'`.

---

## 5. Remaining Uncertainties Register & Unresolved Platform Feasibility

The following 7 uncertainties are formally tracked in §9.4 of [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) and require physical execution in future phases:

| ID | Uncertainty / Risk | Current Status | Required Resolution Phase | Impact if Unresolved |
|---|---|---|---|---|
| U1 | Desktop Host Window & Real OS IME Integration | NOT RUN / INCOMPLETE | Phase 0 Gate G0 & Phase 5 Gate G5 | Desktop candidate window misplaced or input dropped; requires custom AWT host or Compose fork. |
| U2 | Android Custom InputConnection & TalkBack 14+ | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | Atomic code-point deletion or TalkBack word-level reading fails compliance; blocks Android release. |
| U3 | iOS UITextInput Marked-Text Lifecycle & VoiceOver | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | `setMarkedText` / `unmarkText` drops selection or VoiceOver fails; blocks iOS release. |
| U4 | Web ProseMirror Synchronous Kotlin Admission | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | Synchronous bridge destroys candidate DOM nodes; requires asynchronous fallback. |
| U5 | 100K-Unit Single Paragraph Layout Latency | HIGH RISK / UNMET | Phase 4 Gate G4 & Phase 8 Gate G8 | Large single paragraph stalls UI thread; requires document block limit or off-thread layout. |
| U6 | Skia Native Layout Optimization Patch | UNVALIDATED OPTIMIZATION (REJECTED) | Excluded from baseline | Baseline uses unpatched Skiko; no novelty or unverified C++ patches assumed. |
| U7 | Crash Durability and Journal Tail Recovery | PARTIAL (Serialization round-trip verified; physical recovery unrun) | Phase 7 Gate G7 | Process kill at write boundary could corrupt document without journal validation. |

---

## 6. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **Run-03 Silent Clamping Defect (`HXQlo` vs `HQlo`)**: Failed in run-03 (`targetExpansion` clamped `newStart` to `committedExtent[1]`, silently corrupting text to `"HXQlo"` instead of `"HQlo"`); now passes with exact native-range proposal fidelity admitting exact requested `[newStart, newEnd]` and absorbing earlier committed units into the active candidate baseline.
- **Run-03 DocumentRevision Metadata Freeze Contradiction**: Failed in run-03 (`partialCommit` bumped `documentRevision` from 1 to 2 when buffer was byte-for-byte identical); now passes with strict decoupling: `DocumentRevision` advances if and only if semantic document text or raw attributes change (`partialCommit` advances `SessionRevision++` with `DocumentRevision` unchanged).
- **Subclass Polymorphism & Sound BFS Model Checking**: Failed in run-03 (`cloneCoord` sliced subclass instances down to base class after depth 1); now passes using dynamic constructor instantiation `new c.constructor(...)`, sound sequence stamping (`seq = depth + 1`), and cached receipt checking, exploring 7,767 abstract states and 8,286 transitions with 11/11 mutants caught.
- **Target Expansion Absorption Synchronization**: When `newStart <= committedExtent[0]`, active and historical committed units are cleared, ensuring clean rollback to pre-composition snapshot upon subsequent cancellation.
- **Settlement Token Lifecycle & Ordering Contract**: Verified across 7 comprehensive scenarios: monotonic token issuance, invalidation on new input (`preedit`, `targetExpansion`, `partialCommit`), rejection of active candidates (`CandidateStillActive`), rejection of obsolete tokens (`ObsoleteSettlementToken`), session/epoch mismatch guards (`SessionMismatch`, `StaleEpoch`), and strict prevention of `[0, 0]` extent fallbacks.
- **Distinct Cancellation Semantics & Dynamic Command Mapping**: Verified across 8 scenarios: `nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`, target expansion surplus restoration, and dynamic command range re-targeting across candidate length expansions, contractions, and cancellation rollbacks.
- **Settlement loses composition identity and history integrity (Defect A)**: Passes with normalized completion protocol, deferred history, duplicate unmark rejection `Rejected(AlreadyUnmarked)`, and `completeSettlement` draining commands against exact extent.
- **Connection replacement drops composition history (Defect C)**: Passes finalizing active composition into `undoStack` upon connection turnover.
- **Repeated identical preedit advancing DocumentRevision (Defect D)**: Passes with strict separation: `DocumentRevision` advances only on semantic changes.
- **Preedit crash after nonterminal unmark**: Passes safely reallocating `markedRange` and resuming composition.
- **Trapped unrelated commands on cancel**: Passes draining and executing unrelated anchored commands on cancellation.

### 2. What remains ambiguous, failed or untested?
- **Failed**: Shipped Compose `TextFieldState` attributed undo was confirmed defective.
- **Excluded / Unvalidated Optimization**: The Skia native C++ patch failed compilation and its baseline crashed; it is rejected from the architecture.
- **Untested (NOT RUN)**: Gate G0 Desktop (native Compose/AWT host window embedding, real OS IME input, real selection, geometry, settlement, and AT); Gate G0 Android, iOS, Web; Phase Gates G1–G8; real AT interaction (TalkBack, VoiceOver, NVDA, Orca); 100K-unit single paragraph layout performance; and physical filesystem crash recovery.
- **Partial**: Invariant I14 durability is validated in serialization round-trip only; physical crash recovery and journal tail recovery are deferred to Phase 7.

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES, but strictly gated.** `plan-v4.md` (Revision 4.8) is internally consistent, mathematically specified, and normatively sound for Phase 0 (Capability and dependency freeze) and Phase 1 (Headless kernel and persistence foundation). However, production implementation remains prohibited until acceptance gates pass on native targets.

### 4. Which gates actually passed?
- **G0-Desktop Unit Contract (PASS as a Headless Unit Contract)**: Public `InputMethodRequests` coordinate indexing, slice stitching, and hit testing pass.
- **G0 Native Host Gates (G0 Desktop, Android, iOS, Web)**: **NOT RUN / INCOMPLETE**.
- **Implementation Phase Gates (G1–G8)**: **NOT RUN / GATED** (Phase 1 gated pending plan resolution and native platform qualification; production implementation prohibited).

### 5. What is the exact next implementation work and its acceptance criteria?
**Commence Phase 0 (Capability and dependency freeze)**:
1. **Phase 0 Deliverable**: Freeze exact dependencies and generate `validation/capability-manifest.json` with pinned Kotlin, Gradle, Skiko, ProseMirror, and Unicode data versions.
   - *Acceptance Criterion*: Clean build of common modules without compiler warnings or unresolved dependencies.
2. **Phase 1 Deliverable**: Implement headless Kotlin `editor-core` (persistent B+ tree, raw UTF-16 storage, canonical attributes, primitive operations, independent simple reducer, and canonical serialization `editor-document/1`).
   - *Acceptance Criterion (Gate G1)*: Invariants I01–I06 and I14 pass; 100,000 randomized operations across 20 seeds; exact save/load round-trip of raw attributes and isolated surrogates; exception rollback cleanly restores pre-transaction state.
