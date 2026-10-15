# Antigravity Independent Validation Report: WYSIWYG Document Engine (`plan-v4.md`)

**Date**: 13 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Implementation specification validation against [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md), execution of baseline research, independent executable specification testing, and platform feasibility spikes.  
**Primary Verdict**: **READY TO BEGIN IMPLEMENTATION (Phase 0 and Phase 1 authorized with documented platform gates and corrections applied).**

---

## 1. Executive Summary & Verdict

`plan-v4.md` successfully synthesizes the empirical findings of the research phase and abandons unviable novelty directions (the incremental bidi bracket tree and the unvalidated Skia native patch). It establishes an internally coherent, feasible, and rigorous engineering specification for a multiplatform document engine.

Our adversarial static audit, executable reference modeling, property-based fuzzing (10,000 iterations), and native Desktop AWT integration spike confirmed the correctness of the core algorithms:
- The **immutable-baseline candidate attribution algorithm (§4.3)** guarantees candidate path independence (Invariant I07) and survives arbitrary phonetic candidate detours.
- **Partial commit freezing (§4.3)** prevents suffix cancellation or subsequent updates from corrupting committed prefix attributes (Invariant I08).
- **Queued formatting (§4.5, §7)** creates two distinct history groups upon settlement, ensuring Undo of formatting does not revert committed candidate text to phonetic preedit strings (Regression R05).
- **Raw UTF-16 persistence with base64 chunks (§2.3, §7)** preserves isolated surrogates across save/load verbatim, while export conversion reliably substitutes U+FFFD and generates explicit conversion diagnostics (Regression R14).
- **Desktop AWT InputMethodRequests committed-text coordinate mapping (§6.3, R12)** is feasible using explicit endpoint boundary affinity, preventing uncommitted composed text leaks into host queries.

### Status Totals Across Validation Matrix (51 Evaluated Claims):
- **PASS**: 28 (Model/Headless specification tests, AWT integration spike, and replicated research baselines)
- **FAIL**: 2 (Unvalidated native Skia patch rejected; shipped Compose `TextFieldState` attributed undo confirmed defective)
- **NOT RUN**: 18 (Phase Gates G1, G2, G3 pending Kotlin implementation; Android, iOS, Web browser, and physical Assistive Technology environments unavailable in this headless Windows workspace)
- **INCONCLUSIVE**: 1 (Incremental bidi block index dropped; paragraph construction costs dominate)
- **DESIGN POLICY**: 2 (FrameKey caching and persistent B+ tree architecture specifications)

---

## 2. Critical Findings & Concrete Corrections Discovered

During executable specification modeling and native spike execution, five technical bugs and edge-case ambiguities were uncovered and resolved:

### Finding 1: Pre-Composition Snapshot Timing in Attributed History (§4.2, §7, R05)
- **Defect**: If the editor captures the before-history snapshot when the native `commit` callback arrives, the text buffer already contains intermediate preedit text (e.g. `"nihaollo"`). When the user subsequently undoes the composition, the editor restores the intermediate preedit string rather than the document state from before composition began (`"Hello"`).
- **Correction Applied**: The immutable pre-composition snapshot must be pinned at `startComposition` or `markExisting` *before* any provisional preedit splices touch the buffer. The entire composition session (including intermediate preedits and partial commits) must reference this pinned initial snapshot as its `beforeSnapshot`.
- **Evidence**: [`test-r01-to-r05.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/test-r01-to-r05.mjs#L200-L265).

### Finding 2: Boundary Coordinate Affinity in AWT Committed-Text Indexing (§6.3, R12)
- **Defect**: In AWT `InputMethodRequests`, when composition is active in the middle of a document (e.g. `[5, 13)`), querying committed slice `[0, 5)` naively maps the boundary offset $q = 5$ to $live = 13$ if evaluated as $q \ge a \implies live = q + (b - a)$. The resulting slice returns `"HelloCOMPOSED"`, leaking uncommitted composed text to the IME and causing runtime assertion failure.
- **Correction Applied**: Boundary affinity must be explicit: range endpoints `[beginIndex, endIndex)` must map `beginIndex` with `Association.AFTER` and `endIndex` with `Association.BEFORE` at marked boundary $a$, ensuring slice `[0, a)` yields prefix `[0, a)` and never leaks `[a, b)`.
- **Evidence**: [`DesktopAwtInputMethodRequestsSpike.java`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/DesktopAwtInputMethodRequestsSpike.java#L30-L105).

### Finding 3: Code Unit vs Code Point Iteration Mismatch in Host Adapters (§2.3, §4.3)
- **Defect**: Standard JavaScript string iteration (`for (let c of str)`) iterates by Unicode code points (yielding 2 code units for surrogate pairs). If candidate units are indexed by code points while offsets are UTF-16 code units, array index calculations (such as `bLen - cLen + i`) exceed array boundaries and result in `undefined` attribute lookups.
- **Correction Applied**: Explicitly require that candidate reconciliation arrays and baseline slices are indexed strictly by 16-bit UTF-16 code units, and all adapter loops iterate by code unit, not code point.
- **Evidence**: [`reference-model.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/reference-model.mjs#L40-L103), [`test-r14-and-properties.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/test-r14-and-properties.mjs#L95-L105).

### Finding 4: Implicit Composition Session Initialization on Raw Preedit Callback (§4.1, §4.4)
- **Defect**: Certain platform IMEs emit text replacement / composing text callbacks without issuing an explicit prior `startComposition` or `setComposingRegion` event. If the input coordinator initializes an implicit session without capturing and pinning `this.compositionBeforeSnapshot`, subsequent preedit updates mutate the document buffer in-place. Upon `commit`, the snapshot falls back to the dirty preedit text, leaking intermediate phonetic preedit strings into the undo stack and corrupting undo behavior (violating Invariant I12 and Regression R05). Furthermore, defaulting blindly to `[0, 0]` when `this.markedRange` is null ignores explicit range payloads (`payload.start`/`payload.end`) and caret positions.
- **Correction Applied**: The coordinator must initialize a composition session at the current collapsed logical selection `[caret, caret)` (or explicit `payload.start/end`), immediately pin the immutable pre-composition document/selection snapshot `compositionBeforeSnapshot` before splicing text, reset composition state on new connection epochs, and provide symmetrical undo/redo.
- **Evidence**: [`protocol-coordinator.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/protocol-coordinator.mjs#L115-L135), [`test-r01-to-r05.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/test-r01-to-r05.mjs#L270-L295).

### Finding 5: Clarification of Native Structural Admission for Embedded Newlines (§6.1 Rule 2 vs §2.6)
- **Defect**: Plaintext native replacements spanning paragraph boundaries cannot synthesize new structural block divisions without violating §2.6 (which states that opaque native LF inside a text block is a soft break).
- **Correction Clarified**: Confirmed as design policy: native replacements containing newlines across existing paragraph blocks compile into joining the paragraphs and inserting soft line breaks (`\n`), not structural `<paragraph>` nodes. Hard structural block creation remains strictly reserved for explicit Enter and rich paste commands.

---

## 3. Independent Execution of Existing Evidence

All prior research fixtures and probes were executed independently in this environment:

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
| `research/unicode-oracle/bracket-unicode-oracle` | OpenJDK 23.0.2 + Node | `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot"; node research/bracket-unicode-oracle.test.mjs` | 0 | 1,967 non-overflow fixtures agree with Unicode 8.0 Java reference; 72,490 position checks pass; BD16 overflow difference verified. |
| `research/unicode-oracle/bidi-block-context-oracle` | OpenJDK 23.0.2 + Node | `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot"; node research/bidi-block-context-oracle.test.mjs` | 0 | 12,418 requests checked against Unicode 8 Java reference; 1,749,721 direction comparisons pass. |
| `research/native-layout-source/build-patched.log` | MSVC C++17 | Source inspection of build logs | 2 | Compilation failed (missing `<bit>`, `countl_zero` under C++17); baseline crashed (-1073741795). Confirms patch is UNVALIDATED. |

---

## 4. Independent Executable Specification Tests & Property Verification

We implemented a dedicated reference model and test suite in `validation/antigravity/run-20261013-01/`:
- **`reference-model.mjs`**: Standalone implementation of candidate reconciliation (§4.3), partial commit freezing (§4.3), grapheme cluster effective views (§5.3), Android code-point vs UTF-16 deletion contracts (§6.3), position mapping (§2.5), and canonical persistence (§2.3, §7).
- **`protocol-coordinator.mjs`**: Lifecycle, monotonic sequence stamps, receipt caching, DuplicateReceiptExpired, settlement watchdog, queued application commands, and attributed undo history.
- **`runner.mjs`**: Deterministic test orchestrator running 12 test suites in 105.64 ms with 0 failures:
  * **R01 (PASS)**: ZWJ insert/remove preserves constituent styles; explicit format expands to cluster and patches all units.
  * **R02 (PASS)**: Immutable baseline reconciliation passes detour `ab -> x -> ab`, empty candidate, repeated-text prefix ties, and surrogate copy boundaries without bisecting valid pairs.
  * **R03 (PASS)**: Partial commit freezes committed attributes; later suffix edits or cancellation cannot alter frozen prefix.
  * **R04 (PASS)**: Native target expansion resets attribution epoch; composition crossing structural boundaries is rejected without prior bridge synchronization.
  * **R05 (PASS)**: Queued format yields separate history groups; undo format preserves committed text; collapsed toggle creates no history.
  * **R06 (PASS)**: Epoch lifecycle rejects stale events, caches duplicate stamps, expires evicted receipts, admits new sequences.
  * **R07 (PASS)**: UTF-16 unit deletion allows isolated units; code-point deletion rejects malformed traversals atomically; reversed selection and raw/drawable caret divergence verified.
  * **R08 (PASS)**: No-op style command creates no history/revision bump; exception cleanly rolls back all staged mutations.
  * **R09 (PASS)**: Split/join/delete anchor mapping and table cell non-merging deletion verified.
  * **R10 (PASS)**: Link interior/exterior boundary typing, whitespace exit, and heading bold suppression `Value(false)` vs `Clear` verified.
  * **R14 (PASS)**: Raw UTF-16 with isolated surrogates survives canonical persistence verbatim; valid-Unicode export generates conversion report and replaces isolated surrogates with U+FFFD.
  * **Property-I07-I08 (PASS)**: 10,000 randomized iterations (10,000 candidate path-independence checks, 8,035 partial-commit freeze checks) executed with zero failures.

---

## 5. Platform Feasibility & Gate Verification

### Available & Exercised: Desktop (Windows x64)
- **Gate G0 Desktop (PASS)**:
  - Built and verified [`DesktopAwtInputMethodRequestsSpike.java`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/DesktopAwtInputMethodRequestsSpike.java).
  - Confirmed public AWT host contract and coordinate mapping for committed-text indexing with active composition.
  - Demonstrated that boundary association (`Association.BEFORE` for end index, `Association.AFTER` for start index) prevents composition leakage.

### Unavailable Environments (Explicitly Marked NOT RUN)
Per the validation prompt rules (*"Never invent device execution or waive a gate because you lack the device"*), the following environments were not present in this workspace and are strictly designated **NOT RUN**:
- **Android (G0 Android, G5 Android, R16 TalkBack)**: Requires physical Android device or emulator with Gboard/Samsung keyboards and TalkBack accessibility service.
- **iOS (G0 iOS, G5 iOS, R16 VoiceOver)**: Requires macOS host, Xcode iOS simulator / physical iOS device, and VoiceOver accessibility service.
- **Web (G0 Web, G5 Web, R11 Browser IME)**: Requires headless/real browser runtime (Playwright/Chrome) executing ProseMirror Wasm bridge with CJK/Latin browser IMEs.
- **Linux (G0 Linux, G5 Linux)**: Requires Linux X11/Wayland host with IBus/Fcitx and Orca screen reader.

---

## 6. Answers to Mandatory Prompt Questions

### 1. Is the plan internally consistent enough to start Phase 0/1?
**YES.** With the documented consistency corrections incorporated into [`antigravity-plan-diff.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/antigravity-plan-diff.md) (specifically pinning the pre-composition snapshot at composition start, explicit AWT boundary affinity, code-unit indexing in candidate loops, and fallback composition initialization), `plan-v4.md` is internally consistent, free of circular dependencies, and ready for Phase 0 and Phase 1 implementation.

### 2. Which concrete defects did you find and correct?
We identified and resolved five technical defects/ambiguities:
1. **Pre-composition snapshot timing**: Naive commit-time snapshot captures intermediate preedit strings, breaking undo; fixed by capturing snapshot at composition start.
2. **AWT committed-text endpoint leak**: Slices querying up to the composition boundary leak composed text without explicit `Association.BEFORE` mapping; fixed by explicit boundary association.
3. **Candidate unit iteration mismatch**: JavaScript/host code point iteration breaks array indexing on surrogate pairs; fixed by strict 16-bit code-unit iteration.
4. **Missing preedit session initialization & snapshot**: Intermediate preedit mutations mutated the buffer in-place without capturing the pre-composition snapshot, leaking preedit strings into undo upon commit; fixed by implicit initialization at caret and immediately pinning `compositionBeforeSnapshot` before text modification.
5. **Native newline structural admission**: Clarified that native replacements containing newlines across paragraphs create soft breaks, preserving block invariants. 
132: ### 3. What did you execute, on exactly which environments?
133: - **Windows x64 with Node.js v24.20.0**:
134:   * Executed existing research fixtures (`grapheme-style-roundtrip.mjs`, `existing-attribution-control.mjs`, `composition-path-dependence.mjs`, `composition-history-probe.mjs`, `bracket-unicode-oracle.test.mjs`, `bidi-block-context-oracle.test.mjs`).
135:   * Executed independent specification test runner [`runner.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/runner.mjs) (12 test suites, R01–R10, R14, and 10,000 property fuzzing iterations).
136: - **Windows x64 with Eclipse Temurin OpenJDK 23.0.2+7**:
137:   * Executed Desktop Compose probes (`Probe.kt`, `UnicodeProbe.kt`, `ChannelProbe.kt`, `HiddenStateProbe.kt`, `LayoutProbe.kt`).
138:   * Compiled and executed native Desktop AWT integration spike [`DesktopAwtInputMethodRequestsSpike.java`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-01/DesktopAwtInputMethodRequestsSpike.java) (with package qualification `validation.DesktopAwtInputMethodRequestsSpike`).
139: 
140: ### 4. Which claims remain untested or failed?
141: - **Failed**: The Skia native patch under `research/native-layout-source` failed compilation and its baseline crashed; it remains rejected and is not a prerequisite. Shipped Compose `TextFieldState` attributed undo was confirmed defective.
142: - **Untested (NOT RUN)**: Phase Gates G1, G2, G3 (pending Kotlin kernel/history/coordinator implementation); Android `InputConnection`, iOS `UITextInput`, Web browser ProseMirror live IME bridge, real Assistive Technology navigation (TalkBack, VoiceOver, NVDA), and 100K-unit single paragraph layout performance.

### 5. What is the next implementation phase permitted by the evidence?
The evidence permits commencing **Phase 0 (Capability and dependency freeze)** and **Phase 1 (Headless kernel and persistence foundation)** immediately. Phase 0 artifacts (manifest, lockfiles, and Desktop host spike) are validated; Phase 1 core text storage, canonical attributes, primitive operations, and serialization can proceed with zero UI dependencies. Implementation of platform adapters (Phase 5) remains gated on their respective platform spikes.
