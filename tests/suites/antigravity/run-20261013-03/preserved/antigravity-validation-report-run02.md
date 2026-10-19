# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md` -> Revision 4.4)

**Date**: 13 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Falsification audit, coordinator defect reproduction, AWT spike repair, independent property verification, mutation analysis, and specification alignment against [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md).  
**Primary Verdict**: **READY TO BEGIN PHASE 0/1 (Kernel, persistence, and pure algorithmic models authorized; platform adapters strictly gated on real native host qualification).**

---

> [!WARNING] **Skepticism Disclaimer**
> We have zero confidence that Desktop, Android, iOS, or Web IMEs work end-to-end today because no live native host or OS IME has been executed in this workspace; our confidence is strictly confined to the mathematical correctness of the headless coordinate algorithms, candidate reconciliation models, and transaction state machine.

---

## 1. Executive Summary & Verdict

The previous validation report (`run-20261013-01`) contained overly optimistic conclusions and failed to falsify critical defects in the coordinator harness and AWT spike. Following feedback from Codex, we reproduced all demonstrated defects against the original harness, corrected the models and specification, implemented meaningful independent property verifications, and conducted mutation tests.

### Core Architecture Verdict:
- The **immutable-baseline candidate attribution algorithm (§4.3)** guarantees candidate path independence (Invariant I07) and survives arbitrary phonetic candidate detours.
- **Partial commit freezing (§4.3)** prevents suffix cancellation or subsequent updates from corrupting committed prefix attributes (Invariant I08).
- **Queued formatting (§4.5, §7)** preserves FIFO ordering across both `Composing` and `AwaitingSettlement`, resolving `CompositionTarget` strictly to the concrete committed extent without whole-document fallback (Regression R05).
- **Native Unmark (§4.4)** preserves accepted text, finalizes history groups, and respects adapter settlement contracts.
- **AWT InputMethodRequests Coordinate Mapping (§6.3, R12)** is verified: empty slices at all boundaries return empty iterators without throwing, cross-composition slices concatenate without exposing marked text, and hits outside composed text strictly return null.
- **Platform Host Qualification Scoping (§8 Gate G0)**: A headless interface spike verifies the coordinate translation unit only, NOT a native host. Gate G0 Desktop is strictly designated **INCOMPLETE / NOT RUN** for native host qualification.

### Status Totals Across Validation Matrix (48 Evaluated Items):
- **PASS**: 27 (Model tests, headless unit spike, and replicated research baselines)
- **NOT RUN**: 16 (Phase Gates G0-Desktop, G0-Android, G0-iOS, G0-Web, G1–G8 Kotlin phase gates; R11 Web IME, R13 Compose layout, R16 physical AT, I17 physical AT)
- **DESIGN POLICY**: 2 (FrameKey caching and persistent B+ tree architecture specifications)
- **FAIL**: 2 (Unvalidated Skia native C++ patch rejected; shipped Compose `TextFieldState` attributed undo confirmed defective)
- **INCONCLUSIVE**: 1 (Incremental bidi bracket tree index dropped)

---

## 2. Critical Findings & Concrete Corrections Discovered

All findings have been audited and classified according to prompt instructions:
- **(a)** Harness violates an already-correct requirement
- **(b)** Specification is ambiguous or incomplete
- **(c)** Specification is contradictory or incorrect
- **(d)** Unresolved platform feasibility
- **(e)** Product policy requiring an explicit decision

### Finding 1: Settlement Bypass during `AwaitingSettlement`
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: In `run-20261013-01`, `dispatchAppCommand()` only queued when `compositionState === 'Composing'`. If a command was dispatched while `AwaitingSettlement`, it executed immediately on the dirty buffer. Furthermore, intermediate `preedit` callbacks reverted state to `Composing`, dropping the settlement request.
- **Reproduction**: Reproduced and archived in `validation/antigravity/run-20261013-02/reproduce-coordinator-defects.log`.
- **Correction Applied (§4.2, §4.4)**: Commands requiring settlement remain queued in strict FIFO order in *both* `Composing` and `AwaitingSettlement`. Subsequent `preedit` callbacks maintain `AwaitingSettlement` without dropping the settlement request.
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L11-L60), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L54-L88).

### Finding 2: Inaccurate `CompositionTarget` Extent Calculation
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: In `run-20261013-01`, `executeFormatCommand()` targeted `[0, docText.length]` whenever `cmd.target === 'composing'`, making the whole document bold.
- **Reproduction**: Composing `[1, 2)` in `"Hello"` and committing `"Y"` made all of `"HYllo"` `[0, 5)` bold. Archived in `reproduce-coordinator-defects.log`.
- **Correction Applied (§4.5)**: Formulated exact normative algorithms for all 6 extent edge cases:
  1. Base replacement: $[sessionStart, sessionStart + len(committedText))$.
  2. Candidate growth: $[sessionStart, sessionStart + len(expandedText))$.
  3. Candidate shrinkage: $[sessionStart, sessionStart + len(shrunkText))$.
  4. Empty final candidate: $[sessionStart, sessionStart)$ -> empty range (no-op format, no doc revision bump, no history group).
  5. Partial commits: $[sessionStart, sessionStart + totalFrozenPrefixLength + len(finalCommitText))$.
  6. Target expansion: $[newSessionStart, newSessionStart + len(committedText))$.
  *Arbitrary fallback to the whole document is strictly prohibited.*
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L62-L215), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L90-L122).

### Finding 3: Unmark Contract & Settlement Fence Disconnect
- **Classification**: (a) Harness violation & (b) Specification ambiguous / incomplete.
- **Defect**: In `run-20261013-01`, `unmark` was completely unhandled (`NoChange`), leaving the marked range active and recording no history.
- **Reproduction**: Preedit followed by unmark left state as `Composing` and undo stack empty. Archived in `reproduce-coordinator-defects.log`.
- **Correction Applied (§4.4)**: `unmark` preserves accepted text, clears transient composition metadata (`markedRange = null`, `activeSessionId = null`), and records a composition history group from pre-composition snapshot to accepted state. Drains pending commands if the adapter's settlement contract treats unmark as terminal (`settled: true`); otherwise retains `AwaitingSettlement` until the platform completion fence arrives (`settled: false`).
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L217-L290), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L124-L157).

### Finding 4: AWT `InputMethodRequests` Contract Inversion on Empty Slices & Geometry Errors
- **Classification**: (c) Specification contradictory / incorrect & (a) Harness violation.
- **Defect**: §6.3 claimed endpoint boundary affinity alone handled slice mapping. At composition boundary $a$, mapping $a$ with `AFTER` ($b = 13$) and $a$ with `BEFORE` ($a = 5$) inverted the bounds to $[13, 5)$, throwing `StringIndexOutOfBoundsException` on empty slices $[5, 5)$. Furthermore, `getTextLocation` treated offsets as committed-text-relative, and `getLocationOffset` returned non-null hits outside composed text.
- **Reproduction**: Reproduced and archived in `validation/antigravity/run-20261013-02/reproduce-awt-defects.log`.
- **Correction Applied (§6.3)**: 
  1. Empty slices (`beginIndex == endIndex`) at all boundaries (especially $a$) return empty iterators immediately without translating endpoints.
  2. Slices crossing composition concatenate prefix `[beginIndex, a)` and suffix `[b, endIndex + (b - a))` without exposing composed text $[a, b)$.
  3. `getTextLocation` is composed-text-relative when composition is active, returning a 2px-wide caret rectangle.
  4. `getLocationOffset` strictly returns null for points outside composed text.
- **Evidence**: [`DesktopAwtInputMethodRequestsSpike.java`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/DesktopAwtInputMethodRequestsSpike.java) (66 slices pass, empty slice passes, composed geometry passes, outside null passes).

### Finding 5: Scoping of Gate G0 Desktop Native Host Qualification
- **Classification**: (a) Report violation & (d) Unresolved platform feasibility.
- **Defect**: The run-01 report declared Gate G0 Desktop `PASS` based solely on a headless Java unit test with dummy screen math.
- **Correction Applied (§6.3, §8)**: Gate G0 Desktop is strictly reclassified as **NOT RUN / INCOMPLETE**. A unit spike on coordinate math does not qualify a native host. Full G0 qualification requires a running Compose/AWT host window with real OS IME input, selection, geometry, settlement, and screen reader accessibility.

### Finding 6: Vacuous Candidate Path Independence Property Test
- **Classification**: (a) Harness violation.
- **Defect**: In run-01, the property test called `reconcileCandidateAttributes(B, cFinal, defaultSeed)` twice with identical inputs and discarded the intermediate result, testing no stateful edits.
- **Correction Applied (§4.3, §9)**: Replaced with a stateful test driving two independent coordinator instances ($coord_1$ and $coord_2$) with different multi-step detour sequences ending in $C_{final}$, verifying complete resulting state equality against an independent oracle across 10,000 iterations.
- **Evidence**: [`test-r14-and-properties.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-r14-and-properties.mjs#L110-L240).

### Finding 7: Commit Replacement Termination without Custom "finish" Flag
- **Classification**: (b) Specification incomplete.
- **Correction Applied (§4.4)**: Real OS IMEs standardly emit commit events without bespoke "finish" flags. The coordinator treats any non-partial commit (`isPartial !== true`) as terminal: transitions to `Idle`, clears transient marked range, finalizes history, and drains queued commands.
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L295-L310).

### Finding 8: Implicit Composition Initialization from Active Selection
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Correction Applied (§4.4)**: Raw preedits in `Idle` state initialize the session at the active logical selection `[anchor, focus]`, preserving non-collapsed replacement ranges (never defaulting to `[0, 0]`).
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L312-L335).

### Finding 9: Cancellation Erased Partial Commit Frozen Prefix (Invariant I08 & R03)
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: The coordinator unconditionally restored `this.compositionBeforeSnapshot.text` upon cancellation, erasing already committed partial units and violating Invariant I08.
- **Reproduction**: Composing `"ni"`, partially committing `"ni"`, preediting `"hao"`, and cancelling restored the document to `"Hello"` instead of preserving the partial commit `"nillo"`.
- **Correction Applied (§4.4, §4.5)**: When cancellation arrives after one or more partial commits, the frozen prefix units remain committed in the document under Invariant I08. Only uncommitted candidate text in `markedRange` is removed, a single `Composition` history group is created for the net partial commit(s), and pending commands targeting the aborted candidate are cancelled (`CompositionCancelled`).
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L355-L390), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L175-L215).

### Finding 10: Direct Commit Extent in Idle State Defaulted to Offset Zero
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: In direct commit without prior composition, `this.sessionStartOffset` defaulted to 0, causing `lastSettledExtent` to resolve to `[0, commitText.length]` instead of the active selection extent.
- **Reproduction**: Direct commit at selection `[6, 6)` produced extent `[0, 8)` instead of `[6, 14)`.
- **Correction Applied (§4.5)**: When a direct commit arrives in `Idle` state, `sessionStartOffset` initializes to the active selection anchor `Math.min(selection[0], selection[1])`, resolving extent strictly to `[sessionStart, sessionStart + commitText.length]`.
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L392-L420), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L217-L245).

### Finding 11: Missing Focus Loss and Disposal Lifecycle Methods
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: The coordinator lacked explicit `onFocusLost()` and `dispose()` entry points; pending commands were not explicitly expired with distinct cancellation reasons.
- **Reproduction**: Focus loss leaked pending commands into subsequent composition sessions.
- **Correction Applied (§4.4, §4.5)**: Implemented `onFocusLost(strategy)` (cancelling pending composition commands with `FocusLost` and settling or cancelling active preedit) and `dispose()` (cancelling all pending commands with `Disposed` and closing connection).
- **Evidence**: [`test-coordinator-repairs.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs#L445-L485), [`test-mutations.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/test-mutations.mjs#L247-L278).

### Finding 12: Missing AWT Selected Text, Insert Position, and Unsupported Attributes Contracts
- **Classification**: (a) Harness violation & (b) Specification incomplete.
- **Defect**: The AWT spike did not verify `getSelectedText()`, `getInsertPositionOffset()`, `cancelLatestCommittedText()`, or requested text attributes in its test suite; vertical line boundary was inclusive on the lower edge (`y > screenOriginY + lineHeight`).
- **Reproduction**: Boundary click at `y = screenOriginY + lineHeight` returned a hit instead of null; unhandled attribute queries threw or returned malformed iterators.
- **Correction Applied (§6.3)**: Corrected vertical bounds check (`y < screenOriginY || y >= screenOriginY + lineHeight`); verified `getSelectedText()` across forward, collapsed, and reversed ranges; verified `getInsertPositionOffset()` in composing and idle states; verified `cancelLatestCommittedText()` returns null; and verified that requested text attributes cleanly return empty attribute sets for unsupported keys.
- **Evidence**: [`DesktopAwtInputMethodRequestsSpike.java`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/DesktopAwtInputMethodRequestsSpike.java#L230-L330).

---

## 3. Independent Execution of Existing Evidence

All historical research baselines and probes were re-verified in this workspace:

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

## 4. Repaired Executable Specification Tests & Mutation Analysis

Under `validation/antigravity/run-20261013-02/`:
- **`runner.mjs`**: Deterministic test runner executing all 12 specification test suites in 285.76 ms with 0 failures:
  * **R01 (PASS)**: Grapheme constituent styles preserved across ZWJ insert/remove; explicit cluster formatting patches all units.
  * **R02 (PASS)**: Immutable baseline reconciliation passes detour, empty, tie-breaking, and surrogate copy boundaries.
  * **R03 (PASS)**: Partial commit freezes committed attributes; subsequent suffix edits and cancellations leave prefix intact.
  * **R04 (PASS)**: Target expansion starts new attribution epoch; structural boundary composition rejected without prior bridge sync.
  * **R05 (PASS)**: Queued format FIFO queueing maintained across Composing and AwaitingSettlement; preedit retains settlement; exact committed extent formatting; unmark creates undo group; symmetrical undo/redo.
  * **R06 (PASS)**: Epoch lifecycle rejects stale events, caches duplicate stamps, expires evicted receipts, admits new sequences.
  * **R07 (PASS)**: UTF-16 unit deletion allows isolated units; code-point deletion rejects malformed traversals atomically; reversed selection and caret projection verified.
  * **R08 (PASS)**: No-op style command creates no history/revision bump; exception cleanly rolls back all staged mutations.
  * **R09 (PASS)**: Split/join/delete anchor mapping and table cell non-merging deletion verified.
  * **R10 (PASS)**: Link interior/exterior boundary typing, whitespace exit, and heading bold suppression Value(false)/Clear verified.
  * **R14 (PASS)**: Raw UTF-16 with isolated surrogates survives canonical persistence verbatim; valid-Unicode export generates conversion report and U+FFFD (with explicit note: serialization round-trip does not establish crash durability or journal correctness).
  * **Property-I07-I08 (PASS)**: 10,000 randomized stateful property iterations driving two independent coordinator instances with different candidate edit sequences (10,000 path independence checks, 10,000 independent oracle comparisons, 7,762 partial-commit freeze checks, and epoch-reset divergence verifications).
- **`test-coordinator-repairs.mjs`**: Comprehensive test suite verifying all repaired coordinator mechanisms (Defects A, B, C, and all audit items including session ID matching, direct commit extent, partial commit cancellation preservation, focus loss, and disposal).
- **`test-mutations.mjs`**: Mutation verification suite injecting 8 deliberate representative bugs and demonstrating that our tests catch all 8:
  1. *Previous-candidate baseline reuse* (breaks Invariant I07 path independence).
  2. *Immediate formatting during settlement* (violates FIFO queueing & settlement bypass).
  3. *Whole-document CompositionTarget* (violates exact extent calculation).
  4. *Ignored unmark* (leaves zombie composition state).
  5. *Committed-text leakage* (AWT boundary slice inversion).
  6. *Cancellation erasing frozen prefix* (erases committed partial units under Invariant I08).
  7. *Direct commit extent defaulting to 0* (corrupts direct commit extent).
  8. *Focus loss leaking pending commands* (cross-session command leak).
- **`DesktopAwtInputMethodRequestsSpike.java`**: Corrected Desktop AWT `InputMethodRequests` spike verifying all 11 test scenarios:
  1. Exhaustive testing of all 66 valid committed slices against independent oracle.
  2. Empty slice `[5, 5)` at composition boundary.
  3. Slice crossing composition `[3, 7)` without marked text leakage.
  4. Invalid slices throwing `IllegalArgumentException`.
  5. Composed-text-relative geometry and caret rectangle.
  6. Location offset inside and strict null outside (with exclusive lower line boundary).
  7. No-composition state handling.
  8. Selected text across forward, collapsed, and reversed ranges.
  9. Requested text attribute filtering and clean iterators.
  10. Insert position offset in composing and idle states.
  11. Unsupported `cancelLatestCommittedText()` cleanly returning null.

---

## 5. Concise List of Unresolved Questions & Platform Feasibility

The following 5 platform unknowns cannot be settled by headless model tests and require real hardware/runtime spikes in their respective implementation phases:

| # | Unresolved Question / Risk | Required Experiment | Required Environment | Acceptance Criterion | Consequence of Failure |
|---|---|---|---|---|---|
| 1 | **Desktop Host Window & Real OS IME Integration** | Embed `InputMethodRequests` spike in active Compose Desktop window; test Windows MS-IME and macOS Kotoeri. | Windows 11 Desktop & macOS Sequoia with live IMEs | IME candidate window anchors to live caret rectangle; preedit and commit delivered cleanly; detours preserve formatting; backspace during preedit does not fault. | Desktop IME input broken or misplaced; requires bespoke AWT window host or Compose fork. |
| 2 | **Android InputConnection & TalkBack Accessibility** | Custom `InputConnection` exercising code-point deletion, extracted text, and cursor anchor updates under active TalkBack service. | Physical Android 14+ device with Gboard & TalkBack 14+ | Atomic code-point deletion preflight succeeds; TalkBack accurately announces granular word/line edits; typing never duplicates characters. | Android accessibility fails compliance; blocks Android production release. |
| 3 | **iOS UITextInput Marked-Text Lifecycle & VoiceOver** | Attached `UIView` implementing `UITextInput` with Japanese Kana keyboard and VoiceOver. | macOS with Xcode iOS 18 Simulator / Physical iPhone | `setMarkedText` and `unmarkText` update island without dropping selection; dictation inserts at caret; VoiceOver reads attributed words. | iOS text input rejected by App Store; blocks iOS release. |
| 4 | **Web ProseMirror Synchronous Kotlin Admission** | Synchronous Kotlin/Wasm bridge receiving DOM mutations from ProseMirror view with browser CJK IME. | Chrome/Firefox browser running Playwright harness | Browser composition never destroys active composing DOM nodes or flickers popups; effective rendering attributes project accurately. | Web IME drops candidate characters; requires asynchronous fallback or full DOM rewrite. |
| 5 | **100K-Unit Single Paragraph Layout Performance** | Bounded layout benchmark constructing 100K-unit paragraph with mixed styles and bidi runs in Skiko. | Reference desktop hardware with pinned fonts | Layout latency p95 <= 100 ms. | Large single paragraphs stall UI thread; requires explicit product document length limit or off-thread background layout worker. |

---

## 6. Answers to Mandatory Prompt Questions

### 1. What failed before and passes now?
- **Settlement bypass (Defect A)**: Failed in run-01 (formatting commands executed immediately in `AwaitingSettlement` and preedit dropped settlement status); now passes with strict FIFO queueing across both states.
- **Wrong `CompositionTarget` extent (Defect B)**: Failed in run-01 (entire document became bold); now passes with exact multi-case extent resolution across all 6 edge cases.
- **Unmark does nothing (Defect C)**: Failed in run-01 (ignored); now passes with accepted text preservation, transient metadata clearing, composition history finalization, and adapter settlement contract fences.
- **AWT empty slice exception**: Failed in run-01 (`StringIndexOutOfBoundsException` on `getCommittedText(5, 5, null)`); now passes with explicit empty slice handling at all boundaries.
- **AWT geometry offset relativity**: Failed in run-01 (treated offset as committed-text-relative); now passes with composed-text-relative geometry and standard caret rectangle shape.
- **AWT outside hits**: Failed in run-01 (returned non-null hit in committed text); now passes strictly returning `null` with exclusive line lower bound.
- **AWT selected text, insert position, and attributes**: Unverified in run-01; now passes across forward/reversed selections, active vs idle insertion points, and clean unsupported attribute iterators.
- **Cancellation erased partial commit prefix**: Failed in prior coordinator (wiped out frozen prefix on cancellation); now passes preserving frozen prefix units under Invariant I08 and R03.
- **Direct commit extent in Idle**: Failed in prior coordinator (defaulted offset to 0); now passes resolving extent from active selection anchor.
- **Focus loss and disposal lifecycle**: Unhandled in prior coordinator; now passes cancelling pending commands with explicit reasons (`FocusLost`, `Disposed`).
- **CompositionTarget session mismatch**: Unvalidated in prior coordinator; now passes validating session ID and rejecting stale mismatches.
- **Vacuous property test**: Failed in run-01 (tested pure function twice with identical inputs); now passes with 10,000 stateful fuzzing iterations driving two independent coordinator instances with real divergent edits against an independent oracle.
- **Mutation robustness**: Untested in run-01; now passes with 8/8 representative mutations caught.

### 2. What remains ambiguous, failed or untested?
- **Failed**: The Skia native C++ patch (`research/native-layout-source`) failed compilation and its baseline crashed; it remains excluded from the architecture. Shipped Compose `TextFieldState` attributed undo was confirmed defective.
- **Untested (NOT RUN)**: Gate G0 Desktop (native host window integration, real OS IME input, real selection, geometry, settlement, and AT); Gate G0 Android, iOS, Web; Phase Gates G1–G8; real AT interaction (TalkBack, VoiceOver, NVDA, Orca); and 100K-unit single paragraph layout performance.
- **Ambiguous / Unresolved**: Exact settlement fence mechanism on each specific platform adapter (must be resolved empirically in Phase 5 spikes).

### 3. Whether the plan is precise enough to start Phase 0/1?
**YES.** `plan-v4.md` (Revision 4.4) is mathematically and architecturally complete for Phase 0 (Capability and dependency freeze) and Phase 1 (Headless kernel and persistence foundation). Its algorithms for candidate reconciliation, partial commit freezing, FIFO settlement queueing, precise `CompositionTarget` extent resolution, unmark contract, raw UTF-16 persistence, atomic deletion preflight, focus loss/disposal rules, and direct commit handling are fully specified without implementation-critical ambiguity.

### 4. Which gates actually passed?
- **G0-Desktop Unit Contract (PASS as a Headless Unit Contract)**: Public `InputMethodRequests` coordinate indexing, slice stitching, and hit testing pass.
- **G0 Native Host Gates (G0 Desktop, Android, iOS, Web)**: **NOT RUN / INCOMPLETE**.
- **Implementation Phase Gates (G1–G8)**: **NOT RUN** (Phase 1 authorized to commence).

### 5. What is the exact next implementation work and its acceptance criteria?
**Commence Phase 0 and Phase 1**:
1. **Phase 0 Deliverable**: Freeze exact dependencies and write `validation/capability-manifest.json` with pinned Kotlin, Gradle, Skiko, ProseMirror, and Unicode data versions.
   - *Acceptance Criterion*: Clean build of common modules without compiler warnings or unresolved dependencies.
2. **Phase 1 Deliverable**: Implement headless Kotlin `editor-core` (persistent B+ tree, raw UTF-16 storage, canonical attributes, primitive operations, independent simple reducer, and canonical serialization `editor-document/1`).
   - *Acceptance Criterion (Gate G1)*: Invariants I01–I06 and I14 pass; 100,000 randomized operations across 20 seeds; exact save/load round-trip of raw attributes and isolated surrogates; exception rollback cleanly restores pre-transaction state.
