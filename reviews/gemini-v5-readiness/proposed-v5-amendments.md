# Independent Systems Architecture Review & Specification v5 Freeze-Readiness Assessment

**Repository:** [shayann07/WYSIWYG-Text-Editor-Compose](https://github.com/shayann07/WYSIWYG-Text-Editor-Compose)  
**Baseline Commit Inspected:** `b66b790068908e45dd8642d3410bc53f609c700f` (`git rev-parse HEAD`)  
**Review Branch:** `review/gemini-v5-readiness` (isolated git worktree; baseline branch and committed artifacts untouched)  
**Review Date:** 16 October 2026  

## 1. Independent Freeze-Readiness Verdict & Executive Classification

**Verdict: DO NOT FREEZE SPECIFICATION v5 UNTIL DESIGN BLOCKERS B1–B7 ARE INCORPORATED.**

The 4.15.0 specification (`plan-v4.md` and `editor-4.15.0-standalone-review.md`) and its companion executable reference suite (`validation/codex/run-20261015-19/`) represent serious, disciplined engineering: all 20 baseline verification groups pass, source hashes match `publication-hashes.json`, and historical defects across Candidate B composition, table cell selection, and Bidi bracket indexing were substantially narrowed. However, passing the bounded reference suite does not establish that the specification is internally consistent or ready to freeze.

Through adversarial cross-feature testing, source inspection of the bundled [Compose Multiplatform Core v1.12.1](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer.kt) and [Skia m150 OneLineShaper](https://raw.githubusercontent.com/JetBrains/skia/m150-1f14f1166a/modules/skparagraph/src/OneLineShaper.cpp) trees, and four new executable validation suites (`25` counterexample reproductions on unmodified baseline code, persistent hierarchical storage and crash-recovery probes, a compiled C++20 `OneLineShaper` shaper probe, and a real headless Chromium `154.0.8037.92` DOM/IME/HTML5 probe), this review identified **7 specification-freeze design blockers (B1–B7)** plus **18 reference-model implementation and oracle defects** that must be resolved before Codex consolidates and freezes v5.

### Executive Separation of Freeze Categories (A–E)

| Category | ID / Scope | Summary of Issue or Contract | Consequence if Unresolved | Status |
| :--- | :--- | :--- | :--- | :--- |
| **A. Design Blockers Before v5 Freeze** | **B1 (F01)** | Path-dependent incremental mapping of annotations and registered anchors across live composition updates (`Dv`) and `partialCommit` + `retarget` + `cancel` (`plan-v4.md` §4.4, §4.11.3) | Surviving anchors become `deleted: true`, annotations become `orphaned: true`, and net no-op candidate detours append spurious `Composition` history groups and advance `DocumentRevision`. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B2 (F02)** | Action-ledger slot conflict between §4.6 rule 3 (empty-candidate `format(target:'composition')` no-op vs `Cancelled`) and §4.7 (`applyPaste` cancellation omitting `historySlot: true`) | Queued `Undo` during composition falls through to undo the composition deletion/commit itself instead of returning `NoChange(CommandWithoutHistory)`. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B3 (F04)** | Structural reconstruction rule conflict when deleting a `BlockObject` together with its trailing `BoundaryUnit` (`plan-v4.md` §4.3 rules 1, 2, 7, 8) | Deleting a leading image `[0, 2]` before a `List`, `Quote`, `Heading`, or second `BlockObject` either destroys the right-hand container/block identity or fails with `UnrepresentableObjectSeam`. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B4 (F05)** | Post-structural text-caret normalization on `BlockObject` leaves (`\ufffc`) and non-atomic `deleteCodePoints` across block/table boundaries (`plan-v4.md` §2.5, §4.11.2, §6.3) | `deleteNode`/`moveNode` leaves a text caret on an image block so subsequent typing/IME fails with `UnrepresentableObjectSeam`; `deleteCodePoints` silently deletes unselected tables or joins `CodeBlock` with prose. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B5 (F07)** | Pinned Unicode UAX #29 isolated-surrogate boundary rule (`plan-v4.md` §3 line 190), `pasteRich` split preservation on `Heading`/`CodeBlock` (§4.11.5), `emptyBlockAttributes` sanitization (§2.6), and HTML5 fragment context wrapping (§6.5) | Isolated surrogates merge with trailing combining marks (`\uD800\u0301`); `pasteRich` at offset 0 of a `Heading` demotes it to `paragraph`; `emptyBlockAttributes` leaks `javascript:` links; browser `DOMParser` strips bare `<td>` tags. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B6 (F08)** | Missing 2-component monoid measure `(contentUtf16Length, leafBlockCount)` on persistent hierarchical trees, boundary rank after leading-leaf deletion, and split `pendingBytes` retention caps (`plan-v4.md` §2.3, §4.8, §4.10) | Single-measure token rank undercounts every preceding `BoundaryUnit` (`\n`); deleted first-leaf boundaries return rank `-1` instead of `TargetDeleted`; total retained memory reaches `4 × pendingBytes`. | **Must amend before v5 freeze** |
| **A. Design Blockers Before v5 Freeze** | **B7 (F09)** | Crash-recovery contradiction during active multi-cell composition and underspecified journal/checkpoint durability state machine (`plan-v4.md` §3 I14, §7 lines 195–199, §8 G7) | Recovering only `Dv` as ordinary text with no persistent Undo permanently erases all non-primary table cells (`c01..c11`) on crash during multi-cell composition. | **Must amend before v5 freeze** |
| **B. Chosen Contracts Awaiting Implementation Qualification** | **Q1–Q5** | Production Kotlin/Skiko persistent B+ piece-tree (`G6`), framed CRC32C journal/checkpoint engine (`G7`), pinned Unicode 16.0 table generator (`G1`), and root-level `EditorViewport` input session routing (`G4`) | Specification contracts are mathematically closed once B1–B7 land, but production modules do not yet exist in the repository. | **Release-blocking (G1, G4, G6, G7)** |
| **C. Platform Uncertainties That Could Force Architectural Changes** | **U1–U5** | Real Android OEM keyboards (`Samsung`, `GBoard`), iOS `UITextInput` marked-text lifecycle across cell flows, Desktop AWT/JetBrains Runtime IME coordinates & `OneLineShaper` style-run scaling (`F11`), Web `contenteditable` composing `Text`-node preservation (`F10B`), and screen-reader tree virtualization (`G0`, `G5`, `G8`) | Could force single-surface root `InputConnection`/`UITextInput` proxy vs per-flow focus targets, custom Skiko native build vs style-run coalescing, or Web leaf `Text`-node freeze during composition. | **Freeze-ready only with explicit fallbacks & reopen triggers in v5** |
| **D. Explicit Product Exclusions (Non-Defects)** | **X1–X4** | Merged/split table cells (`colspan`/`rowspan`), nested tables, real-time multi-user collaborative OT/CRDT, and print pagination (`plan-v4.md` §1.2) | Intentionally out of scope for v5; rejected deterministically by schema/import validators. | **Preserved as explicit exclusions** |
| **E. Optional Optimizations** | **O1–O3** | Incremental UAX #9 BD16 bracket suffix index (`research/bracket-suffix-index.md`), background glyph-run prefetching, and off-thread snapshot compaction | Optional fast paths; must remain behind feature flags until verified against full UAX #9 / HarfBuzz / SkParagraph baselines. | **Non-blocking optimization** |

## 2. Baseline Audit, Executed Commands, Source Hashes & External Primary Sources

### 2.1 Repository & Baseline File Verification

The review began by verifying `HEAD` (`b66b790068908e45dd8642d3410bc53f609c700f`) and checking every SHA-256 digest in `validation/codex/run-20261015-19/publication-hashes.json` before and after running the baseline reproduction and all disposable probes:

| File Path | Baseline SHA-256 Digest | Verified Before & After Probes |
| :--- | :--- | :--- |
| `plan-v4.md` | `16dcbd9c0504df8e5aa8fcf21780922e7fc4c4aadef26ad3cafb493b21a9d3c1` | Exact match |
| `editor-4.15.0-standalone-review.md` | `637ebec819c9eee544bb39e3964207f689be8e2bd63138d6e920b20d1fb42154` | Exact match |
| `antigravity-validation-report.md` | `150cf273ef948dca75c30eebf7ed20275773dca67f5b4c7d461c8d284de256b2` | Exact match |
| `antigravity-validation-matrix.csv` | `08b3887fd38b0bd9ba2b0648d20f1ff30e295b6a136104e44f2db56acd466035` | Exact match |
| `validation/codex/run-20261015-19/results.json` | `37dd704a2e00ab3e48fca25862cf81a216d84ed4fe40c2ba4965079cff8625be` | Exact match (preserved & restored) |
| `validation/codex/run-20261015-19/document-contract.md` | `1d1fab8012dc13473090b74205fe6dbad6cd663b7ac7882ced97e25a4e7cf7ad` | Exact match |

### 2.2 Commands Executed & Environment Limitations

All executable work was performed in an isolated git worktree (`review/gemini-v5-readiness`) on Linux `x64` (`Node.js v18.20.4`, `g++ 12.2.0 -std=c++20 -O2`, `Python 3.11.2`, and `Headless Chromium 154.0.8037.92`). Note on Node version: the prompt requests running `verify.mjs` with Node 24; this sandbox provides `Node.js v18.20.4`, which supports all ES module, `structuredClone`, `Intl.Segmenter`, and `node:assert/strict` features used by `run-20261015-19`.

1. `node validation/codex/run-20261015-19/verify.mjs` (after backing up `results.json`, followed by restoring `results.json`):
   - Reproduced all 20 passing test groups (`66` document cases, `51` command families, `100` anchor cases, `54` cell composition cases, `35` integrated cases, `14` AWT contract cases, `25/25` killed behavioral mutants) and confirmed all 12 `.mjs` source hashes in `results.json` match the committed baseline.
2. `node reviews/gemini-v5-readiness/probe-interactions-and-unicode.mjs`:
   - Executed `25` independent adversarial counterexamples (`F01A` through `F10A_2`). Verified that all `25/25` fail on the unmodified baseline (`DocumentModel`, `IntegratedModel`, `awt-contract-model.mjs`), all `25/25` pass on `RepairedDocumentModel` / `repairedTextLocation`, all `35/35` inherited integrated cases pass on `RepairedDocumentModel`, and `60/60` seeded multi-step interaction fuzz traces (crossing `selectCells`, `partialCommit`, `retarget`, `format`, `moveAnchor`, and `Undo`/`Redo` exhaustion) preserve document and history invariants.
3. `node reviews/gemini-v5-readiness/probe-hierarchical-storage-and-recovery.mjs`:
   - Executed a persistent hierarchical B+ sequence + persistent AVL `TokenMap`/`ParentMap` (`HierarchicalVersionedIndex`) on the standard capacity fixture (`1,000,000` UTF-16 units across `10,000` blocks) and container depths `d ∈ {1, 4, 8, 16, 32}`, plus a framed checksummed journal/checkpoint crash-recovery state machine (`DurableRecoveryJournal`) and binary UTF-16LE `editor-document/1` serializer.
4. `g++ -std=c++20 -O2 reviews/gemini-v5-readiness/probe-shaper-and-performance.cpp -o /tmp/probe-shaper-bin && /tmp/probe-shaper-bin`:
   - Executed the exact `OneLineShaper::finish` and `addFullyResolved` algorithms from `research/native-layout-source/OneLineShaper-m150.cpp`, `OneLineShaper-patched.cpp`, and `OneLineShaper-main.cpp` across `64` to `16,384` runs and default-ignorable (`U+200B`/`U+200D`) runs.
5. `python3 reviews/gemini-v5-readiness/probe_chromium_dom.py`:
   - Executed real browser DOM probes in Headless Chromium `154.0.8037.92` testing `contenteditable` composing `Text`-node preservation (`isSameNode` and `window.getSelection()`), HTML5 `DOMParser` fragment parsing (`<td>`, `<li>`), and HarfBuzz ligature width across DOM `<span>` boundaries (`👩‍💻`).
6. `node reviews/gemini-v5-readiness/run-all.mjs`:
   - Unified verification runner executing all four probe suites, verifying all 6 baseline SHA-256 digests in `publication-hashes.json`, and writing `reviews/gemini-v5-readiness/results.json`.

### 2.3 Audit of Baseline Test & Mutation-Control Weaknesses

Inspecting `validation/codex/run-20261015-19/` and `validation/codex/run-20261015-18/` revealed four structural reasons why the existing test suite missed Findings F01–F10:

- **Shared-implementation oracle circularity**: `DocumentModel` (`document-model.mjs`), `structural-model.mjs`, and `document-tree.mjs` share the exact same helper functions (`reconstruct`, `gapMap`, `unitClusters`, `userRange`, `closedSlice`) that the test assertions rely on, and `DocumentModel._call` wraps `CandidateBCoordinator` while maintaining parallel shadow state (`savedActive.before.document`, `savedActive.before.anchors`) that was never tested on multi-step composition detours (`preedit("X") -> preedit("abc") -> commit("abc")`) or `partialCommit` + `retarget` + `cancel`.
- **Single-step fixture isolation**: Existing fixtures in `test-document.mjs` (`66` cases) and `test-integrated.mjs` (`35` cases) test individual operations (`retarget`, `partialCommit`, `format(composition)`, `applyPaste`, `deleteNode`, `wrapQuote`) in isolation, but never cross `partialCommit` with `retarget` + `cancel` on surviving annotations/anchors, nor queue `Undo` immediately after a cancelled `applyPaste` or empty-candidate `format(composition)` inside an active composition.
- **Unexercised passing-control mutants**: In `test-mutants.mjs`, the two "passing controls" (`format_noop_Visual_only` and `document_noop_Commentary_only`) only mutate an unused property (`_noopControl = true`) rather than exercising boundary-condition behavioral mutations (such as deleting a block object together with its trailing boundary or testing `offset = {charIndex: -1, trailing: true}`).
- **Runtime `Intl.Segmenter` substitution for pinned Unicode**: `document-tree.mjs` lines 5–21 delegate grapheme segmentation to `new Intl.Segmenter('und', {granularity: 'grapheme'})` and only isolate surrogates when `s.length === 1`, overlooking that [Unicode UAX #29](https://www.unicode.org/reports/tr29/#Word_Boundaries) rule `GB9` (`× (Extend | ZWJ)`) attaches `Extend` characters (`\u0301`) to any preceding non-Control code point—including an isolated surrogate `\uD800` or `U+FFFD` (`GCB=Other`)—producing a 2-unit segment `"\uD800\u0301"` (`s.length === 2`) that bypasses the `s.length === 1` check.

### 2.4 External Primary Sources & Bundled Upstream Artifacts Verified

- **Android `InputConnection`**: [Android InputConnection API Reference](https://developer.android.com/reference/android/view/inputmethod/InputConnection), including [`commitText`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#commitText(java.lang.CharSequence,%20int)), [`setComposingRegion`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#setComposingRegion(int,%2520int)), [`setComposingText`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#setComposingText(java.lang.CharSequence,%2520int)), [`deleteSurroundingText`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#deleteSurroundingText(int,%2520int)), [`deleteSurroundingTextInCodePoints`](https://developer.android.com/reference/android/view/inputmethod/InputConnection#deleteSurroundingTextInCodePoints(int,int)), [`setSelection`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#setSelection(int,%2520int)), and [`finishComposingText`](https://developer.android.com/reference/android/view/inputmethod/InputConnection.html#finishComposingText()), plus [Compose `DeleteSurroundingTextInCodePointsCommand`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/input/DeleteSurroundingTextInCodePointsCommand).
- **Apple UIKit `UITextInput`**: [Apple UITextInput Protocol Reference](https://developer.apple.com/documentation/uikit/uitextinput).
- **Desktop Java AWT `InputMethodRequests`**: [JDK 23 `java.awt.im.InputMethodRequests` Specification](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html).
- **Compose Multiplatform `1.12.1` Upstream Source (bundled in `research/compose-probe/upstream/`)**: [Maven POM `foundation-desktop-1.12.1.pom`](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.12.1/foundation-desktop-1.12.1.pom), [Published Source Archive `foundation-desktop-1.12.1-sources.jar`](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.12.1/foundation-desktop-1.12.1-sources.jar), [`TextFieldBuffer.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer.kt), [`TextFieldState.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldState.kt), `TextInputSession.skiko.kt`, and `LegacyPlatformTextInputServiceAdapter.skiko.kt`.
- **Skiko `v0.150.1` & Skia `m150-1f14f1166a` Upstream Source (bundled in `research/native-layout-source/`)**: [Skiko `v0.150.1` `gradle.properties`](https://raw.githubusercontent.com/JetBrains/skiko/v0.150.1/skiko/gradle.properties) and [Skia `OneLineShaper.cpp` (`m150-1f14f1166a`)](https://raw.githubusercontent.com/JetBrains/skia/m150-1f14f1166a/modules/skparagraph/src/OneLineShaper.cpp).
- **Unicode Specifications**: [Unicode UAX #29 Text Segmentation](https://www.unicode.org/reports/tr29/#Word_Boundaries), [Unicode UAX #9 Bidirectional Algorithm (`BD16`)](https://www.unicode.org/reports/tr9/#BD16), [Unicode Bidi Reference Java (`BidiReference.java`)](https://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/BidiReference.java), and [Unicode UTS #51 Emoji Sequences](https://www.unicode.org/reports/tr51/index.html#def_emoji_presentation_selector).
- **Web DOM, Clipboard & IME**: [MDN `ClipboardEvent`](https://developer.mozilla.org/en-US/docs/Web/API/ClipboardEvent), [W3C IME API](https://www.w3.org/TR/ime-api/#ime-composition), and [ProseMirror `prosemirror-view` `1.42.6`](https://registry.npmjs.org/prosemirror-view/-/prosemirror-view-1.42.6.tgz).

## 3. Prioritized Findings with Counterexamples, Root Causes & Discriminating Regressions

Every finding below was reproduced against the unmodified baseline implementation (`counterexamplesReproducedOnBaseline: 25`) and verified fixed in `reviews/gemini-v5-readiness/repaired-document-model.mjs` and `reviews/gemini-v5-readiness/probe-hierarchical-storage-and-recovery.mjs`.

### Finding F01 ( Freeze Blocker B1 ) — Path-Dependent `Dv` Incremental Mapping Corrupts Surviving Annotations, Anchors, History, and `DocumentRevision`

- **Exact Location**:
  - `validation/codex/run-20261015-19/document-model.mjs` lines 98 (`_applyPreedit`), 101–103 (`_acceptVisible`), 108–111 (`_call`), and 360–392 (`_mapFlowUpdate`, `_mapAnchors`).
  - `plan-v4.md` §3 (`I07`, `I12`, `I16`), §4.4 lines 266–285 (`Candidate B: Live Structural Composition`), and §4.11.3 lines 505–518 (`Anchor Relocation and Structural Lifecycle`).
- **Classification**: `Implementation Defect` + `Missing Normative Decision` (Freeze Blocker **B1**).
- **Counterexample F01A (R02 Candidate Detour)**:
  - **Initial State**: `body(p("p", "abcd"))`, selection `[0, 3]` (`"abc"`), registered anchor `h` at `pt("body", "p", 1)` (`association: "before"`), annotation `ann` on `[1, 2]` (`"b"`). `documentRevision = 3`, `history.length = 1`.
  - **Ordered Trace**:
    1. `native: preedit({text: "X"})` (replaces `"abc"` `[0, 3]` with `"X"` `[0, 1]` in `Dv`).
    2. `native: preedit({text: "abc"})` (restores `"abc"` `[0, 3]` in `Dv`).
    3. `native: commit({text: "abc"})` (commits `"abc"`).
  - **Expected Result**: Because the net committed edit replaces `"abc"` with `"abc"` (a semantic no-op on `Dc`), `h` remains at `offset: 1, deleted: false, generation: 0`, `ann` remains at `[1, 2], orphaned: false`, `history.length` remains `1` (no spurious `Composition` undo group), and `documentRevision` advances only on the two visible `preedit` changes (`3 -> 5`, not `6` on `commit("abc")`).
  - **Observed Baseline Result**: `h` is permanently corrupted to `{offset: 0, generation: 1, deleted: true}`, `ann` is permanently corrupted to `{anchor: 0, focus: 3, orphaned: true}`, `history.length` increases to `2` (pushing a spurious `Composition` undo step), and `documentRevision` advances to `6`.
- **Counterexample F01B (`partialCommit` + `retarget` + `preedit` + `cancel`)**:
  - **Initial State**: `body(p("p", "abcd"))`, selection `[0, 1]` (`"a"`), registered anchor `h` at `pt("body", "p", 2)` (`before`), annotation `ann` on `[2, 4]` (`"cd"`).
  - **Ordered Trace**:
    1. `native: preedit({text: "X"})`
    2. `native: partialCommit({length: 1})` (commits `"X"` at `[0, 1]`)
    3. `native: retarget({range: [1, 4]})` (retargets unaccepted composition to `"bcd"`)
    4. `native: preedit({text: "Y"})` (replaces `"bcd"` in `Dv` with `"Y"`)
    5. `native: cancel()` (cancels the retargeted preedit; `"bcd"` survives in `Dc` and `Dv`).
  - **Expected Result**: Document text is `"Xbcd"`, `h` is at `pt("body", "p", 2)` with `deleted: false, generation: 0`, and `ann` is at `[2, 4]` with `orphaned: false`.
  - **Observed Baseline Result**: Text is `"Xbcd"`, but `h` is `{offset: 1, generation: 1, deleted: true}` and `ann` is `{anchor: 1, focus: 4, orphaned: true}`!
- **Root Cause**:
  1. `DocumentModel._applyPreedit` mutates `this.doc` (`Dv`) and `this.anchors` incrementally on every intermediate `preedit` via `_mapFlowUpdate` and `_mapAnchors`. Once `preedit("X")` shrinks `[0, 3]` to `[0, 1]`, interior offset `1` is clamped to `0` and marked `deleted: true` / `orphaned: true`, which is irreversible on subsequent `preedit("abc")`.
  2. In `DocumentModel._call` line 109, `cancel` restores `savedActive.before.anchors` only when `equal(savedActive.before.document, this._doc(true))` holds. After `partialCommit(1)`, `this._doc(true)` (`Dc`) has advanced from `"abcd"` to `"Xbcd"`, so `equal(...)` evaluates to `false` on `cancel`, leaving the destructive `Dv` anchor and annotation mutations from `preedit("Y")` permanently applied to the surviving `"bcd"` span!
- **Minimal General Correction**:
  - Maintain committed anchors and committed annotations on the committed state `(Dc, Ac)`. Derive visible annotations and visible anchors on `Dv` as a **pure function of `(Dc, Ac)` and the current unaccepted replacement span `u = [uStart, uEnd] -> preedit.length`** (advancing `(Dc, Ac)` **only** when `partialCommit(k > 0)` commits a prefix or when composition settles via `commit`). Suppress history group creation and `documentRevision` advancement when settlement produces no state change relative to `(Dc, Ac)` or the prior published snapshot.

### Finding F02 ( Freeze Blocker B2 ) — Action-Ledger Slot Conflict & `applyPaste` Cancellation Causing Queued `Undo` Fall-Through

- **Exact Location**:
  - `validation/codex/run-20261015-19/document-model.mjs` line 142 (`_format`) and lines 224–231 (`_executeCommand('applyPaste')`).
  - `validation/codex/run-20261015-19/integrated-model.mjs` lines 273–275 (`_format`).
  - `plan-v4.md` §4.6 rule 3 (line 322), §4.7 lines 331–348 (`Undo/Redo and Composition`), and §4.11.5 lines 560–572 (`Clipboard and Rich Transfer`).
- **Classification**: `Design Contradiction` + `Implementation Defect` (Freeze Blocker **B2**).
- **Counterexample F02A (Empty-Candidate `format(target:'composition')` + Queued `Undo`)**:
  - **Initial State**: `body(p("p", "hello world"))`, selection `[6, 11]` (`"world"`).
  - **Ordered Trace**:
    1. `native: preedit({text: "XY", stamp: {token: "s1"}})`
    2. `app: format({target: "composition", patch: {bold: true}})` (queued in `pending`)
    3. `app: undo()` (queued in `pending` immediately after `format` to undo the format action)
    4. `native: commit({text: "", stamp: {token: "s1"}})` (settles empty replacement, deleting `"world"`).
  - **Expected Result**: Under `IntegratedModel._format` (`integrated-model.mjs` lines 273–275) and §4.7, `format(target:'composition')` on an empty settled composition span (`[6, 6]`) is `Cancelled` (`TargetDeleted`) **and** records an action-ledger slot (`historySlot: true, changed: false`), so the queued `undo()` inspects that slot, returns `NoChange` (`CommandWithoutHistory`), and preserves the committed deletion (`"hello "`).
  - **Observed Baseline Result**: `DocumentModel._format` line 142 mutates `this.typingIntent` and returns `{status: "Applied", historySlot: false}`. Because `historySlot` is `false`, the queued `undo()` skips `format` and **undoes the composition deletion itself**, restoring `"hello world"` while leaving `typingIntent` set to `{bold: true}`!
- **Counterexample F02B (`applyPaste` Cancelled by Composition Replacement + Queued `Undo`)**:
  - **Initial State**: `body(p("p", "abcd"))`, selection `[1, 3]` (`"bc"`).
  - **Ordered Trace**:
    1. `cmd: capturePaste({range: [1, 2]})` -> `lease = "paste:1"` (captures `"b"`)
    2. `native: preedit({text: "XY"})` (replaces `"bc"`)
    3. `cmd: applyPaste({pasteId: "paste:1", text: "PASTED"})` (queued in `pending`)
    4. `app: undo()` (queued in `pending` to undo the paste)
    5. `native: commit({text: "XY"})`.
  - **Expected Result**: `applyPaste` is `Cancelled` (`TargetDeleted`) and contributes a ledger slot (`historySlot: true, changed: false`), so the queued `undo()` returns `NoChange` (`CommandWithoutHistory`) and preserves `"aXYd"`. Furthermore, when a cell-selection paste lease fails with `TargetChanged` or `TargetFragmented`, `applyPaste` must preserve that exact `reason`.
  - **Observed Baseline Result**: `DocumentModel._executeCommand('applyPaste')` lines 230–231 hardcode `historySlot: false` on cancellation and overwrite `resolved.reason` (`TargetChanged` / `TargetFragmented`) with `'TargetDeleted'`. The queued `undo()` falls through and undoes the committed composition `"XY"`, reverting the document to `"abcd"`!
- **Root Cause**:
  - `plan-v4.md` §4.6 rule 3 states that empty-candidate `format(target:'composition')` updates `typingIntent` as a no-op without a ledger slot, which directly contradicts §4.7 (`"Cancelled commands still contribute a ledger slot with changed = false"` so a following queued `Undo` cannot skip past a cancelled/empty user command and undo an earlier edit). Likewise, `_executeCommand('applyPaste')` violated §4.7 by setting `historySlot: false` on cancellation.
- **Minimal General Correction**:
  - Unify §4.6 rule 3 and §4.7: every user-initiated command or formatting action that is queued during active composition (and not `Rejected` at admission) **must** contribute a ledger slot (`historySlot: true`) at settlement replay. If `format(target:'composition')` replays against an empty settled composition range (`start === end`), it must cancel with `{status: 'Cancelled', reason: 'TargetDeleted', historySlot: true, changed: false}` without mutating `typingIntent`. Preserve `resolved.reason` in `applyPaste`.

### Finding F03 — Retargeting Composition Bypasses Insertion Seed Rules (`_seed`)

- **Exact Location**:
  - `validation/codex/run-20261015-19/candidate-b-coordinator.mjs` lines 1418 and 1492 (`_retarget`).
  - `validation/codex/run-20261015-19/document-model.mjs` line 94 (`_start`).
  - `plan-v4.md` §4.4, §4.11.6, and §5.2 (`Link Boundary Policy`).
- **Classification**: `Implementation Defect` + `Missing Normative Decision`.
- **Counterexamples F03A & F03B**:
  - **F03A (Exterior Link Leak on Retarget)**: In `body(p("p", "ab"))` where `"b"` carries `link: {id: "l1", url: "https://a.test"}` and `"a"` is plain, `startComposition` at `[0, 1] -> retarget([1, 1]) -> commit("X")` inserts `"X"` with `link: {id: "l1", url: "https://a.test"}`, violating §5.2 (`"Typing or starting composition at a collapsed link boundary does not extend the link"`).
  - **F03B (Block-End Seed Loss on Retarget)**: In `body(p("p", "a"))` where `"a"` is `{bold: true}`, `startComposition` at `[0, 1] -> retarget([1, 1]) -> commit("X")` inserts `"X"` with `{}` (`bold` lost), because `_retarget` evaluates `_getWorkingDocAttributes()[1] ?? {}` (`undefined ?? {}`).
- **Root Cause**:
  - `CandidateBCoordinator._retarget` computes `seed` via raw right-neighbor indexing `this._getWorkingDocAttributes()[u] ?? {}` instead of calling the engine's `_seed(doc, flowId, [u, v])` rule (which inherits left-neighbor marks at `u > 0`, falls back to right-neighbor non-link marks only at `u === 0`, and strips exterior `link` attributes unless `link` spans both sides of `u`).
- **Minimal General Correction**:
  - Route both initial composition start and `retarget([u, v])` through a single normative seed function `computeCompositionSeed(Dc, flowId, [u, v], typingIntent)`.

### Finding F04 ( Freeze Blocker B3 ) — Block-Object Head Identity Collision in `reconstruct` Destroys Right-Hand Containers or Rejects Valid Deletions

- **Exact Location**:
  - `validation/codex/run-20261015-19/structural-model.mjs` lines 40–53 (`reconstruct`).
  - `plan-v4.md` §4.3 rules 1, 2, 7, 8 (lines 240–259).
- **Classification**: `Design Contradiction` + `Implementation Defect` (Freeze Blocker **B3**).
- **Counterexamples F04A, F04B & F04C**:
  - **F04A (List Destruction)**: In `body({id:"img", type:"object", props:{kind:"image"}}, list("L", item("i1", p("b", "Hello"))))`, `deleteSelection({range: [0, 2]})` (deleting the leading image `"img"` at `[0, 1]` and its trailing boundary `\n` at `[1, 2]`) destroys `list("L")`, `item("i1")`, and `p("b")`, turning `"img"` into a top-level `paragraph` (`id: "img"`) containing `"Hello"`.
  - **F04B (Adjacent BlockObject Rejection)**: In `body(img1, img2, p("b", "Hello"))`, `deleteSelection({range: [0, 2]})` puts `object:img2` into `groups[0]` (`id: "img1"`), failing `objects[0].nodeId !== g.id` and rejecting a valid deletion with `UnrepresentableObjectSeam`!
  - **F04C (Cross-Block Selection Destroying Nested Heading)**: In `body(p("before", "before"), img, list("L", item("i1", heading("h1", "Title", {level: 1}))))`, `deleteSelection({range: [7, 10]})` (selecting `"img"` + `\n` + `"T"`) destroys `list("L")`, `item("i1")`, and `heading("h1", level: 1)` and moves `"itle"` into a top-level `paragraph` with `id: "img"`.
- **Root Cause**:
  - `reconstruct` initializes `groups = [{id: first.id, units: []}]` and unconditionally keeps `g.id = leftLeaf.id` whenever a `BoundaryUnit` is deleted, then converts `orig.type === 'object'` to a `Paragraph` (`structural-model.mjs` line 48) because `plan-v4.md` §4.3 line 258 says *"Replacing a range whose start or end lands on a BlockObject leaf converts that leaf endpoint to an empty Paragraph before applying the replacement."* That conversion is only valid when the `BlockObject`'s own block slot survives (i.e., its trailing boundary was **not** deleted). When both the `BlockObject` and its trailing boundary are deleted, the `BlockObject` leaf was deleted into the following leaf, so the surviving group must take the identity, type, and ancestor container chain (`List`/`ListItem`/`Quote`/`Heading`) of the surviving right-hand leaf!
- **Minimal General Correction**:
  - Track `boundaryIds` for each reconstructed group `g`. If `orig(g.id).type === 'object'` and `g` contains no `object` unit for `g.id`:
    - If `g.id`'s trailing boundary `boundary:${nextLeafAfter(g.id)}` was deleted inside `g` (i.e. `nextLeafAfter(g.id)` was merged into `g`), resolve `g.id` to the first merged leaf in `g` whose own content/slot survived (`objects[0].nodeId` if `g` holds a surviving `object` unit, otherwise the last merged leaf in `g`).
    - Convert `g.id` from `BlockObject` to `Paragraph` **if and only if** `g.id` was replaced in place without deleting its trailing boundary into a following leaf.

### Finding F05 ( Freeze Blocker B4 ) — Post-Structural Text Caret Landing on `BlockObject` Leaves & Non-Atomic `deleteCodePoints` Across Block/Table Boundaries

- **Exact Location**:
  - `validation/codex/run-20261015-19/document-model.mjs` lines 60–65 (`_install`) and lines 266–271 (`deleteCodePoints`).
  - `plan-v4.md` §2.5 (`Selection Domains`), §4.11.2 (`Structural Editing`), and §6.3 (`Android InputConnection`).
- **Classification**: `Implementation Defect` + `Missing Normative Decision` (Freeze Blocker **B4**).
- **Counterexamples F05A, F05B_1 & F05B_2**:
  - **F05A (`deleteNode` Caret Lands on Image Block)**: In `body(p("a", "A"), {id: "img", type: "object"}, p("b", "B"))` with caret at `[0, 0]`, `deleteNode({nodeId: "a"})` maps `this.selection` via raw `gapMap` to `[0, 0]` (`selectionKind: {type: "text"}`) inside `"img"` (`\ufffc`). Any subsequent `typeText({text: "X"})` or `preedit` at the caret is rejected with `UnrepresentableObjectSeam`!
  - **F05B_1 (`deleteCodePoints` Consumes Entire Unselected Table)**: In `grid()` with caret at `[9, 9]` (start of paragraph `"after"` immediately following Table `"t"` at `[7, 8]`), `deleteCodePoints({before: 2, after: 0})` deletes `[7, 9]`, silently destroying the entire unselected 2×2 Table `"t"` and converting `"t"` into a paragraph!
  - **F05B_2 (`deleteCodePoints` Joins Incompatible `CodeBlock` and Prose)**: In `body(p("a", "A"), code("b", "B"))` at `[1, 1]`, `deleteCodePoints({before: 0, after: 1})` deletes the boundary `[1, 2]` and merges `code("b")` into `paragraph("a")` (violating §4.3 rule 7).
- **Root Cause**:
  1. `_install` maps `this.selection` solely by numeric UTF-16 offset `gapMap(prev, next, s)` without checking whether the mapped text caret landed on a non-text `BlockObject` leaf (`\ufffc`).
  2. `deleteCodePoints` calls `this._replace(this.activeFlow, [start, end], ...)` directly without checking whether the traversed range `[start, end]` crosses an atomic unit (`object`, `inlineAtom`) or an incompatible `BoundaryUnit` (`CodeBlock` vs prose).
- **Minimal General Correction**:
  - Add a post-structural text-selection normalizer `_normalizeTextSelection(flow, sel)`: whenever `selectionKind.type === 'text'` and a collapsed caret `[p, p]` lands on a `BlockObject` leaf (`unit.kind === 'object'` or `boundary` immediately after `object`), snap `[p, p]` to the nearest surviving text block leaf (preferring the next text leaf at offset `0`, or the previous text leaf at its end).
  - Require `deleteCodePoints` and `deleteUtf16` to preflight the traversed span `[start, sel[0]]` and `[sel[1], end]`: if any traversed unit is `object`, `inlineAtom`, or a `boundary` whose adjacent leaves cannot be legally joined by `Backspace`/`Delete` (such as `CodeBlock` vs prose), reject atomically with `InvalidTarget`.

### Finding F06 — Explicit-Target Cell-Selection Wipeout, Unhandled Settlement Replay Errors, Nested Quote-in-List `Enter` Loop, and Mid-Surrogate `moveCaret` Collapse

- **Exact Location**:
  - `validation/codex/run-20261015-19/document-model.mjs` lines 14 (`admittedError`), 168–169 (`_captureCommand`), 178 (`copy`), 239 (`deleteSelection`), 276–278 (`moveCaret`), 288 (`enter`), and 299 (`wrapQuote`).
- **Classification**: `Implementation Defect` + `Missing Normative Decision`.
- **Counterexamples F06A, F06B, F06C & F06D**:
  - **F06A (Explicit Target During Active Cell Selection Wipes All Selected Cells)**: In `grid()`, register anchor `h` at `pt("f00", "p00", 1)`, call `selectCells({tableId: "t", anchorCell: "c00", focusCell: "c11"})`, then dispatch `typeText({anchorHandle: h, text: "!"})`. Expected: `"A!"` in `f00`, `"B"`, `"C"`, `"D"` untouched. Observed baseline: `_captureCommand` lines 168–169 check `if (this.selectionKind.type === 'cells' && (command === 'typeText' || ...))` without checking `p.range == null && p.point == null && p.anchorHandle == null`, wiping out all 4 cells `c00..c11`!
  - **F06B (Queued Command Throws Unhandled Error During Composition Settlement, Wedging `this.pending`)**: During active `preedit("X")`, queue `deleteSelection({range: [2, 3]})` (which spans an `object` seam and throws `UnrepresentableObjectSeam`) or `wrapQuote({blockIds: []})` (which throws `TypeError` on `first.id`). When native `commit({text: "X"})` arrives, `_replayPending` fails to catch `UnrepresentableObjectSeam` / `TypeError`, **rejecting the native IME `commit` itself** and permanently wedging `this.pending`! Similarly, `deleteTable` while `selectionKind.type === 'cells'` leaves `selectionKind = {type: 'cells'}`, causing subsequent `copy` to throw `TypeError: Cannot read properties of null (reading 'node')`.
  - **F06C (`Enter` in Empty Quote Nested Inside `ListItem` Loops Forever)**: In `body(list("L", item("i1", quote("q", [p("a", "")]))))`, pressing `enter` at `[0, 0]` checks `nearestItem(flow, leaf.id)` before `parent.type === 'quote' && node.units.length === 0`. Because `i1` has child `q` (not just empty `a`), `enter` splits `i1` into two list items each containing an empty quote, infinitely duplicating empty quote items on every `Enter` press instead of unwrapping the inner `quote` first!
  - **F06D (`moveCaret` Collapses Raw Selection Inside Surrogate Pair)**: In `body(p("p", "A😀B"))` (`😀` at `[1, 3]`) with raw selection `[0, 2]` (e.g., after `setSelection` or `deleteUtf16`), `moveCaret({direction: "right", extend: false})` collapses to `[2, 2]` (splitting the surrogate pair) instead of snapping to `[3, 3]`.
- **Minimal General Correction**:
  - In `_captureCommand`, route to `cellRange` **only** when `p.range == null && p.point == null && p.anchorHandle == null`.
  - Include `UnrepresentableObjectSeam` in `admittedError`, validate `blockIds.length > 0` in `wrapQuote`/`wrapList`, reset `selectionKind` to `{type: 'text'}` when `deleteTable`/`deleteNode` removes the selected table, check innermost `parent.type === 'quote'` before outer `nearestItem` in `enter` when `node.units.length === 0`, and snap collapsed `moveCaret` endpoints to `userPoint(flow, end, dir)`.

### Finding F07 ( Freeze Blocker B5 ) — Isolated Surrogate Grapheme Merging, InlineAtom Format Bleed, `pasteRich` Heading/CodeBlock Demotion, `emptyBlockAttributes` Unsanitized Leak, and `closedSlice` Empty First Block Loss

- **Exact Location**:
  - `plan-v4.md` §2.3 lines 122–134, §2.6 lines 162–175, §3 line 190, §4.11.2 lines 490–503, §4.11.5 lines 560–572, and §6.5 lines 641–649.
  - `validation/codex/run-20261015-19/document-tree.mjs` lines 16–29 (`unitClusters`) and lines 281–308 (`closedSlice`).
  - `validation/codex/run-20261015-19/integrated-model.mjs` line 282 (`_format`).
  - `validation/codex/run-20261015-19/document-model.mjs` lines 441–534 (`_cloneFresh`, `_pasteRich`).
- **Classification**: `Design Contradiction` + `Implementation Defect` (Freeze Blocker **B5**).
- **Counterexamples F07A, F07B, F07C, F07D & F07E**:
  - **F07A (Isolated Surrogate + Combining Mark Merges Under UAX #29 GB9)**: In `body(p("p", "a\uD800\u0301b"))` (`\uD800` at `[1, 2]`, `\u0301` at `[2, 3]`), `backspace` at `[3, 3]` must delete only `\u0301` (`[2, 3]`), leaving `"a\uD800b"`. Observed baseline: `backspace` deletes both `\uD800` and `\u0301`, leaving `"ab"`. Why? Because `plan-v4.md` §3 line 190 states *"Isolated surrogates are treated as single-unit replacement-class characters for segmentation"*, and in [Unicode UAX #29](https://www.unicode.org/reports/tr29/#Word_Boundaries), both `U+FFFD` (`Replacement Character`, `GCB=Other`) and unassigned/surrogate code points (`GCB=Other`) combine with trailing `Extend`/`ZWJ` under rule `GB9` (`× (Extend | ZWJ)`). In `document-tree.mjs` line 18, `Intl.Segmenter` returns a 2-unit segment `"\uD800\u0301"` (`s.length === 2`), bypassing `s.length === 1 && isSurrogate(...)`!
  - **F07B (`IntegratedModel._format` Bleeds Formatting Across `inlineAtom`)**: In `new IntegratedModel("\ufffc\u0301b", [0, 0])`, `format({target: "range", range: [1, 2], patch: {bold: true}})` expands `[1, 2]` to `[0, 2]` via raw `graphemeBoundaries`, applying `bold: true` to `\ufffc` at index `0`.
  - **F07C (`pasteRich` Demotes Target `Heading` or `CodeBlock` to `paragraph`)**: In `body(heading("h1", "HeadingText", {level: 2}))` at `[0, 0]`, `pasteRich({blocks: [p("src", "Intro")]})` calls `splitLeaf(flow, "h1", 0, {rightType: "paragraph", rightProps: {}})` and then `removeNode(flow, "h1")`. The surviving `"HeadingText"` block loses `id: "h1"`, loses `type: "heading"`, and loses `{level: 2}`, becoming a plain `paragraph` with a synthetic ID!
  - **F07D (`pasteRich` Fails to Sanitize `emptyBlockAttributes` or Reject Multi-Char Units)**: Pasting an empty paragraph with `emptyBlockAttributes: {link: {id: "evil", url: "javascript:alert(1)"}, unsupportedAttr: "leak", bold: true}` bypasses link/attribute sanitization in `_cloneFresh`, so typing `"A"` into the pasted block injects `javascript:alert(1)` and `unsupportedAttr: "leak"` into the document! Moreover, passing `{char: "ABCD"}` (`char.length > 1`) in `pasteRich` is accepted by `_pasteRich`, corrupting UTF-16 offset indexing.
  - **F07E (`closedSlice` Drops Leading Empty Block at Offset 0 on `copy`)**: In `body(p("emptyFirst", ""), p("second", "B"))` with selection `[0, 2]`, `copy` checks `a <= r.start - 1 && r.end <= b` (`0 <= -1`) for empty blocks (`document-tree.mjs` line 284), which is impossible when `r.start === 0`, dropping `emptyFirst` from the copied clipboard slice!
- **Minimal General Correction**:
  1. Amend `plan-v4.md` §3 line 190: specify that any isolated surrogate (`0xD800..0xDBFF` not followed by `0xDC00..0xDFFF`, or `0xDC00..0xDFFF` not preceded by `0xD800..0xDBFF`) has **Control-like hard segmentation boundaries (`GB4`/`GB5`, not `Replacement`/`Other`) on both sides** so it never coalesces with adjacent `Extend`, `ZWJ`, `SpacingMark`, or `Prepend` units.
  2. Split segments around both isolated surrogates and atomic units (`object`, `inlineAtom`, `boundary`) in all grapheme-boundary helpers.
  3. In `_pasteRich`, when splitting a target leaf `loc.node` at `offset === 0` to insert blocks before it, keep the surviving right-hand leaf's original `id`, `type`, and `props` (`Heading`, `CodeBlock`, etc.); when splitting at `offset > 0`, preserve `loc.node.type` and `loc.node.props` on the right half.
  4. Sanitize and remap `emptyBlockAttributes` through the same link/attribute/resource-id sanitizer as unit `attrs`, reject any unit with `typeof u.char !== 'string' || u.char.length !== 1`, and in `closedSlice` include empty block `r` when `a <= r.start && r.end <= b && (a < b || (a === r.start && b === r.end))`.

### Finding F08 ( Freeze Blocker B6 ) — Missing 2-Component Monoid Measure `(contentUtf16Length, leafBlockCount)` in Persistent Hierarchical Index, First-Leaf Boundary Rank Bug, and Split `pendingBytes` Retention Caps

- **Exact Location**:
  - `plan-v4.md` §2.3 lines 122–134, §4.8 lines 351–384 (`Storage and Complexity Contract`), §4.10 lines 437–468 (`Memory and History Retention`), and §4.11.7 lines 586–598.
  - `validation/codex/run-20261015-19/document-model.mjs` lines 16–18 and 318–359 (`_pendingBytes`, `_pasteBytes`, `_clipboardBytes`, `_enforceRetention`).
- **Classification**: `Missing Normative Decision` + `Implementation Defect` (Freeze Blocker **B6**).
- **Counterexamples & Executed Probe Evidence (`probe-hierarchical-storage-and-recovery.mjs` & `F08B`)**:
  - **F08A_1 (Single-Measure Monoid Undercounts Inter-Leaf `BoundaryUnit`s and Table Atoms)**: In a flow `body(p1("abc"), list("L", item("i1", p2("de")), item("i2", p3("f"))), table("t"), p4("gh"))`, the true flow projection is `"abc\nde\nf\n\ufffc\ngh"`, so token `"g"` in `p4` has flow rank `11` (`3 + 1 + 2 + 1 + 1 + 1 + 1 + 1`). A B+ container tree that maintains only `subtreeUtf16Length` (as written in `plan-v4.md` §2.3 line 132) computes rank `7` (`3 + 2 + 1 + 1`), undercounting by `4` because each inter-leaf `BoundaryUnit` (`\n`) is not stored inside any leaf's piece tree—there are exactly $(\sum \text{leafBlockCount}) - 1$ boundaries in a flow! Furthermore, a `Table` node must contribute `(contentUtf16Length = 1, leafBlockCount = 1)` in its parent flow while cell `Flow` roots stop upward `ParentMap` climb so cell tokens resolve relative to their cell `FlowId`, not `body`.
  - **F08A_2 (Deleted First-Leaf Boundary Returns Rank `-1` Instead of `TargetDeleted`)**: In `plan-v4.md` §2.3 line 130, `BoundaryUnit(rightLeafId)` is keyed by `rightLeafId`. When all leaves preceding `p2` are deleted (`p1` removed so `p2` becomes the 0-th leaf of `body`), `p2` is still present in `ParentMap` at `leafPrefix = 0, charPrefix = 0`. Computing boundary rank as `charPrefix + leafPrefix - 1` yields `0 + 0 - 1 = -1` instead of `null` (`TargetDeleted`) unless the rank function checks `leafPrefix >= 1`!
  - **F08B (Split `pendingBytes` Checks Allow $4 \times \text{pendingBytes}$ Retention)**: In `DocumentModel`, `dispatch` checks `queueBytes + pendingBytes > this.retention.pendingBytes` (line 18), `_enforceRetention` checks `pendingBytes > this.retention.pendingBytes` (line 340), `pastesBytes > this.retention.pendingBytes` (line 349), and `clipboardBytes > this.retention.pendingBytes` (line 356) as **four separate inequalities**. With `pendingBytes = 6600`, calling `copy()` (allocating ~6,000 bytes in `clipboard`) and then `capturePaste()` (allocating ~6,000 bytes in `pastes`) succeeds on baseline (~12,000 bytes retained, nearly $2\times$ the cap) instead of evicting or rejecting with `AdmissionCapacityExceeded`.
- **Minimal General Correction**:
  - Specify the exact **2-component additive monoid measure** $M(u) = (\text{contentLen}(u), \text{leafCount}(u))$ on every B+ child-sequence node and block/container node:
    - `TextBlock` leaf $b$: $(\text{pieceTreeLen}(b), 1)$.
    - `BlockObject` leaf $o$: $(1, 1)$.
    - `Table` node $t$ in its parent flow: $(1, 1)$ (while each `TableCell.flow` is an independent `Flow` root in `ParentMap` with `parentId = null`).
    - `Container` (`Flow`, `List`, `ListItem`, `Quote`): $(\sum_i \text{contentLen}(c_i), \sum_i \text{leafCount}(c_i))$.
    - Flow-projected offset of the start of leaf $b$: $\text{charPrefix}(b) + \text{leafPrefix}(b)$.
    - Flow-projected offset of `BoundaryUnit(rightLeafId)`: defined **if and only if** `rightLeafId` is in the flow **and** $\text{leafPrefix}(\text{rightLeafId}) \ge 1$, in which case its offset is $\text{charPrefix}(\text{rightLeafId}) + \text{leafPrefix}(\text{rightLeafId}) - 1$; if $\text{leafPrefix}(\text{rightLeafId}) = 0$, the boundary does not exist (`TargetDeleted`).
  - Enforce a **single unified retention invariant**: $\text{bytes}(\text{nativeQueue}) + \text{bytes}(\text{pending}) + \text{bytes}(\text{pastes}) + \text{bytes}(\text{clipboard}) \le \text{pendingBytes}$, evicting oldest `pastes` and `clipboard` entries in FIFO order before rejecting with `AdmissionCapacityExceeded`.

### Finding F09 ( Freeze Blocker B7 ) — Crash-Recovery Contradiction During Multi-Cell Composition (`Dc` vs `Dv`) & Journal/Checkpoint State Machine Gaps

- **Exact Location**:
  - `plan-v4.md` §3 (`I14`), §7 lines 195–199 (`Persistence, Concurrency, and Crash Recovery`), and §8 Gate `G7`.
- **Classification**: `Design Contradiction` + `Missing Normative Decision` (Freeze Blocker **B7**).
- **Counterexample F09A (Crash During Active Multi-Cell Composition Destroys Secondary Cells)**:
  - **Initial State**: `grid()` (`2×2` table with `"A"`, `"B"`, `"C"`, `"D"` in `c00..c11`), durable checkpoint written at `rev = 2`.
  - **Ordered Trace**:
    1. User selects all 4 cells: `selectCells({tableId: "t", anchorCell: "c00", focusCell: "c11"})`.
    2. User begins typing in IME: `native: preedit({text: "n"})`. Under `plan-v4.md` §2.5 / §4.4, `Dv` replaces primary cell `f00` (`c00`) with `"n"` and clears secondary cells `f01`, `f10`, `f11` (`c01..c11`) to `""`, while `Dc` still holds `"A"`, `"B"`, `"C"`, `"D"`!
    3. Autosave / journal appends the active composition state, and the process crashes before `commit` or `cancel`.
    4. Application restarts and runs crash recovery under `plan-v4.md` §7 line 199: *"Active-composition recovery stores the latest accepted raw text/attributes and composition metadata, clears native session handles, and restores that visible content as ordinary text (`ActiveComposition = null`, no persistent undo)."*
  - **Expected Result**: Recovery must never silently and irreversibly destroy uncommitted replacement targets (`"B"`, `"C"`, `"D"` in `c01..c11`). Either `Dc` is restored as the base committed document with `Dv` pushed as a single undoable `RecoveredComposition` history entry (`Dc -> Dv`), or the user can press `Undo` once after recovery to restore `"A"`, `"B"`, `"C"`, `"D"`.
  - **Observed / Specified Result**: Because §7 line 199 restores `Dv` as ordinary committed text with **no persistent undo** (`history = []`), secondary cells `c01`, `c10`, and `c11` are permanently erased (`""`) and cannot be recovered by `Undo`!
- **Counterexample F09B (Torn Journal Tail, Checkpoint `fsync(parentDir)`, and Replay Continuity)**:
  - `plan-v4.md` §7 lines 197–198 require checksummed journal records and atomic checkpoint replacement, but omit:
    1. `fsync(parentDir)` after `rename(checkpoint.tmp, checkpoint.dat)` (required on POSIX filesystems so the directory entry survives power loss).
    2. Replay rule when a crash occurs **after** `rename(checkpoint.tmp, checkpoint.dat)` (`baseRev = R`) but **before** old journal records `afterRev <= R` are truncated: recovery must idempotently skip journal frames with `afterRev <= checkpoint.baseRev` and require the first applied journal frame to satisfy `frame.beforeRev === checkpoint.baseRev && frame.prevFrameHash === checkpoint.stateHash`.
    3. Torn-write policy: a valid CRC32C mismatch or truncated header/payload at the **tail** of the journal is truncated to the last valid frame offset with a `JournalTailTruncated` diagnostic, whereas a corruption or hash/revision gap **before** a later valid frame is a fatal `CorruptJournalInterior` error (`NoInteriorGapReplay`).
- **Executed Verification (`probe-hierarchical-storage-and-recovery.mjs`)**:
  - Verified that binary UTF-16LE `editor-document/1` round-trips isolated surrogates (`\uD800`), internal-grapheme mixed formatting on `👩‍💻`, tables, annotations, and resources with 100% fidelity; verified that naive `Dv`-only recovery loses `c01..c11` with `0` Undo entries, whereas dual `(Dc, Dv)` journal frames restore `Dv` with a 1-step `RecoveredComposition` Undo entry that restores all 4 cells `"A"`, `"B"`, `"C"`, `"D"`.

### Finding F10 — Desktop AWT `InputMethodRequests` Bounds/Null-Offset Bugs, Compose `1.12.1` `TextInputSession` Mismatch, and Real Chromium DOM Composing-Node & HTML5 Fragment Behavior

- **Exact Location**:
  - `validation/codex/run-20261015-19/awt-contract-model.mjs` lines 16–39 (`textLocation`).
  - `validation/codex/run-20261015-18/DesktopAwtInputMethodRequestsSpike.java` lines 31–68.
  - `research/compose-probe/upstream/published-source/skikoMain/androidx/compose/foundation/text/input/internal/TextInputSession.skiko.kt` lines 58–121, 149–237 and `LegacyPlatformTextInputServiceAdapter.skiko.kt` lines 149–156.
  - `plan-v4.md` §4.2, §6.3, §6.4, and §6.5.
- **Classification**: `Implementation Defect` + `Missing Normative Decision` + `Qualification Gap`.
- **Counterexamples & Executed Browser / Host Evidence**:
  - **F10A_1 (`awt-contract-model.mjs` Accepts Negative `charIndex: -1` with `trailing: true`)**: In `awt-contract-model.mjs` line 25, `insertion = offset.charIndex + (offset.trailing ? 1 : 0)` is computed **before** bounds checking `insertion < 0 || insertion > markedLength`. Passing `{charIndex: -1, trailing: true}` yields `insertion = 0`, which silently passes bounds checking instead of throwing `InvalidComposedHit` (`charIndex` must satisfy `0 <= charIndex < markedLength`, or `charIndex === markedLength && !trailing`).
  - **F10A_2 (`awt-contract-model.mjs` Throws on `offset = null` During Active Composition)**: Under the [JDK 23 `InputMethodRequests.getTextLocation(TextHitInfo offset)` contract](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), `offset` may be `null` when an input method queries the base location of the composed text. `awt-contract-model.mjs` line 21 throws `InvalidComposedHit` when `markedLength !== null && offset === null` instead of resolving `null` to the leading edge of composed text (`{charIndex: 0, trailing: false}`). Furthermore, `DesktopAwtInputMethodRequestsSpike.java` in `run-20261015-18` was never updated to the v4.15.0 contract (it still uses `width = 2`, `offset.getCharIndex()` when idle, and `selectionEnd` instead of `min(anchor, focus)`).
  - **F10B_1 (Executed in Headless Chromium `154.0.8037.92` — Splitting a Composing DOM `Text` Node Loses Selection)**:
    - In `probe_chromium_dom.py`, we tested a live `contenteditable="true"` paragraph containing a single DOM `Text` node `"world"` with an active selection `[2, 5]` (`"rld"`), simulating an inline mark change (`<strong>`) applied to the prefix `[0, 2]` (`"wo"`) inside the same `Text` node by calling `compTextNode.splitText(2)` and wrapping the prefix in `<strong>`.
    - **Result**: Chromium detached the selection from both the original `Text` node and the split tail `Text` node (`originalNodeStillHoldsSelection: false, tailNodeHoldsSelection: false, anchorNodeIsNewTail: false`), whereas mutating a **separate sibling** `<span>` left the composing `Text` node (`isSameNode: true`) and selection (`anchorOffset: 0, focusOffset: 3`) intact!
    - **Architectural Consequence for §6.4**: Protecting only the UTF-16 range `[compStart, compEnd]` is insufficient on Web; the reconciler must freeze the **entire DOM `Text` leaf node and its ancestor inline mark wrapper chain** hosting an active composition, deferring any mark split inside that `Text` node to the delayed-correction ledger until `compositionend`.
  - **F10B_2 (Executed in Headless Chromium `154.0.8037.92` — HTML5 `DOMParser` Drops Bare `<td>` Tags and Orphans `<li>` Elements)**:
    - Parsing `"<td>Cell A</td><td>Cell B</td>"` via `new DOMParser().parseFromString(html, "text/html")` in `"in body"` insertion mode strips all `<td>` tags (`parsedChildElementCount: 0, survivingTextContent: "Cell ACell B"`). Parsing `"<li>First item tail</li><li>Second item</li>"` produces bare `<li>` children directly under `<body>` without a `<ol>`/`<ul>` container.
    - **Architectural Consequence for §2.6 / §6.5**: The rich HTML clipboard importer must inspect leading fragment tags before invoking `DOMParser` (or parse via contextual `<template>` / wrapper rules), wrapping bare `<tr>`/`<td>`/`<th>` fragments in `<table><tbody>...` and wrapping bare `<li>` children of `<body>` in `<ul>...</ul>`.
  - **F10B_3 (Executed in Headless Chromium `154.0.8037.92` — Splitting `👩‍💻` Across DOM `<span>` Boundaries Breaks HarfBuzz ZWJ Ligature)**:
    - Rendering `👩‍💻` (`\uD83D\uDC69\u200D\uD83D\uDCBB`) inside a single `<span>` measures `19.97px` wide (single ligature glyph), whereas splitting internal-grapheme attributes across `<span>👩&#x200D;</span><span style="font-style:italic">💻</span>` measures `39.94px` wide (`zwjLigatureBrokenBySpanBoundary: true`), empirically confirming why §5.3's single-effective-style cluster rendering view is mandatory on both Skia and Web DOM.

### Finding F11 — Performance Experiments: Persistent Hierarchical B+ Index vs Skia `OneLineShaper` $\Theta(M^2 \log M)$ Style-Run Blowup & Default-Ignorable Run Drop

- **Exact Location**:
  - `research/native-layout-source/OneLineShaper-m150.cpp` lines 253–264 (`finish`), line 269 (`addFullyResolved`), and lines 396–420 (`iterateThroughFontStyles`).
  - `research/native-layout-source/OneLineShaper-patched.cpp` line 259 (`fResolvedBlocks.clear()`) and `OneLineShaper-main.cpp` line 284 (`if (this->fCurrentRun->fTextRange.empty()) return;`).
  - `plan-v4.md` §1.3, §4.8, §6.2, and §8 Gate `G8`.
- **Classification**: `Qualification Gap` + `Missing Normative Decision`.
- **Executed Performance Experiment 1: Persistent Hierarchical Storage (`probe-hierarchical-storage-and-recovery.mjs`)**:
  - Built the standard capacity fixture (`1,000,000` UTF-16 units across `10,000` blocks) in `HierarchicalVersionedIndex` (persistent B+ sequence + persistent AVL `TokenMap`/`ParentMap` with 2-component monoid measure `(contentLen, leafCount)`):

| Metric / Workload | Measured Result (Node `v18.20.4`, Linux `x64`) | Allocations / Node Visits |
| :--- | :--- | :--- |
| Initial build of `1,000,000` UTF-16 units across `10,000` blocks | `73.77 ms` | `10,000` leaves, `1` flow root |
| Local persistent edit latency (`insertInTextBlock` on `1M` units / `10K` blocks, `100` edits) | **p50 = `0.3318 ms`, p95 = `1.0671 ms`, p99 = `1.6468 ms`** | `6` doc nodes allocated, `106` map nodes allocated, `0` unchanged blocks visited |
| Full-copy `structuredClone` of `10,000` blocks (baseline JS reference model strategy) | **Median = `50.25 ms`** (`~151×` slower than persistent B+ edit p50) | `10,000` blocks cloned |
| Depth scaling `d = 1 -> 32` nested containers (`rank` lookup / local edit) | `docVisits`: `8 -> 101` (`~3.1× d`), `docAlloc`: `6 -> 68` (`~2.1× d`) | Strictly linear in container depth $d$ and logarithmic in sibling count |

- **Executed Performance Experiment 2: Compiled C++20 Skia `OneLineShaper` Probe (`probe-shaper-and-performance.cpp`, `g++ 12.2.0 -O2`)**:
  - Inspection of `OneLineShaper::iterateThroughFontStyles` (`OneLineShaper-m150.cpp` lines 396–420) revealed a critical fact not stated in `plan-v4.md` §6.2: `OneLineShaper::iterateThroughShapingRegions` calls `iterateThroughFontStyles`, which invokes `this->finish(bidiBlock.fRange, height, advanceX)` **at the end of every `(BidiRegion × FontStyleBlock)` intersection**—where `StyleType::kFont` splits on any change to `fontWeight` (`bold`), `fontStyle` (`italic`), `fontSize`, `fontFamily`, `letterSpacing`, or `locale` (`ParagraphImpl.cpp` lines 780–820).
  - Therefore, **pure LTR English paragraphs with many alternating inline formatting spans (`bold`/`italic`/`code`) hit the exact same $\Theta(M^2 \log M)$ `fResolvedBlocks` re-sorting and re-advancing blowup in unpatched Skiko `0.150.1` (`m150`) as Bidi `(א)` runs!**

| Shaping Blocks $M$ (`Bidi × FontStyle` intersections) | UTF-16 Equivalent Length | Unpatched `OneLineShaper-m150.cpp` Median (`p95`) | Unpatched `std::sort` Comparisons | Patched `fResolvedBlocks.clear()` Median (`p95`) | Patched `std::sort` Comparisons | Measured Bookkeeping Speedup |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `64` | `192` | `0.0249 ms` (`0.0300 ms`) | `8,881` | `0.0095 ms` (`0.0165 ms`) | `0` | **2.6×** |
| `256` | `768` | `0.3519 ms` (`0.4102 ms`) | `233,027` | `0.0368 ms` (`0.0378 ms`) | `0` | **9.6×** |
| `1,024` | `3,072` | `6.2918 ms` (`8.2796 ms`) | `5,240,121` | `0.2751 ms` (`0.4228 ms`) | `0` | **22.9×** |
| `4,096` | `12,288` | `130.5377 ms` (`145.2441 ms`) | `108,662,527` | `0.9974 ms` (`1.5201 ms`) | `0` | **130.9×** |
| `8,192` | `24,576` | `569.7114 ms` (`620.7371 ms`) | `484,699,138` | `1.9825 ms` (`2.2267 ms`) | `0` | **287.4×** |
| `16,384` | `49,152` | `2,469.3002 ms` (`2,472.6429 ms`) | `2,139,495,941` | `2.6022 ms` (`4.0150 ms`) | `0` | **948.9×** |

- **Executed Default-Ignorable Zero-Glyph Run Probe (`OneLineShaper-m150.cpp` line 269 vs `OneLineShaper-main.cpp` line 284)**:
  - In `OneLineShaper-m150.cpp` line 269, `addFullyResolved()` checks `if (this->fCurrentRun->size() == 0) { return; }` (where `size()` is the HarfBuzz glyph count). When a style or Bidi block consists solely of default-ignorable code points (`U+200B` ZWSP, `U+200C` ZWNJ, `U+200D` ZWJ, `U+2066..U+2069` Bidi isolates) and the font omits zero-width glyphs for them, `size() == 0` while `fTextRange` is non-empty! Unpatched `m150` drops the run (`emittedRuns: 15/16, rangeContinuityValid: false`), breaking text-range continuity and offset-to-caret geometry in `SkiaParagraph`, whereas `OneLineShaper-main.cpp` line 284 (`if (this->fCurrentRun->fTextRange.empty()) return;`) preserves all `16/16` runs (`rangeContinuityValid: true`).
  - **Architectural Consequence for §6.2 & Gate G8**: Patching only `fResolvedBlocks.clear()` in `OneLineShaper-patched.cpp` is not enough; the Skia patch or Skiko upgrade must include **both** `fResolvedBlocks.clear()` in `finish()` **and** `if (this->fCurrentRun->fTextRange.empty()) return;` in `addFullyResolved()`, and `plan-v4.md` §6.2 must explicitly state that `OneLineShaper::finish()` scales in the number of `(BidiRegion × FontStyleBlock)` runs $M$ per paragraph, not merely Bidi runs.

## 4. Exact Normative Amendments Ready for v5 Incorporation (A01–A11)

Each amendment below is self-contained and ready for Codex to incorporate directly into `plan-v4.md` (and mirror into `editor-4.15.0-standalone-review.md` and `validation/codex/run-20261015-19/`).

### Amendment A01 (Fixes F01 / Blocker B1 — Pure Projection of Visible Annotations and Anchors Across Composition)

- **Target Section**: `plan-v4.md` §4.4 (`Candidate B: Live Structural Composition`) and §4.11.3 (`Anchor Relocation and Structural Lifecycle`).
- **Exact Replacement Normative Text**:
  > **Committed vs. Visible Anchor and Annotation Ownership (`Dc` -> `Dv` Projection).** Registered `AnchorHandle` records and document `Annotation` ranges are owned by the committed state `(Dc, Ac)`. During an active composition session with unaccepted replacement range `u = [uStart, uEnd]` in `activeFlow` and current preedit length `m = preedit.length`:
  > 1. `preedit` updates and `retarget` transitions SHALL NOT destructively mutate `(Dc, Ac)`. Only `partialCommit(k > 0)` (which commits the `k`-unit prefix into `Dc` and shrinks `u`), composition settlement (`commit`), or explicit `registerAnchor`/`moveAnchor`/`addAnnotation`/`deleteAnnotation` commands advance `(Dc, Ac)`.
  > 2. In every published snapshot while `ActiveComposition != null`, visible annotations on `Dv` and visible anchor positions in `Dv` SHALL be computed as a pure deterministic function `projectToVisible(Dc, Ac, activeFlow, u, m)`:
  >    - Any anchor or annotation endpoint in `activeFlow` at committed offset `x <= uStart` (or `x == uStart` with `association == "before"` or annotation `focus`) maps to `x`.
  >    - Any anchor or annotation endpoint at `x >= uEnd` (or `x == uEnd` with `association == "after"` or annotation `anchor`) maps to `x + (m - (uEnd - uStart))`.
  >    - Any anchor strictly inside `(uStart, uEnd)` (or at `uStart` with `after` when `uStart < uEnd`, or at `uEnd` with `before` when `uStart < uEnd`) projects as `deleted: true` at `uStart` in `Dv` while retaining its committed position in `Ac` until `u` is committed by `partialCommit` or `commit`.
  >    - Any annotation `[a, b]` with `uStart < b` and `a < uEnd` projects as `orphaned: true` on `[min(a, uStart), max(b, uEnd) + (m - (uEnd - uStart))]` while `uStart < uEnd`, and is restored automatically from `Dc` if `cancel` or a subsequent `preedit`/`commit` restores the underlying committed tokens.
  > 3. **Net No-Op Settlement Suppression.** If composition settlement (`commit` or `cancel`) produces a final `(Dc', Ac')` identical to the pre-session `(Dc, Ac)` and `partialCommitCount == 0`, the engine SHALL NOT append a `Composition` history entry. `DocumentRevision` SHALL advance on `commit` or `cancel` if and only if the resulting visible `DocumentSnapshot` differs from the immediately preceding published `DocumentSnapshot`.

### Amendment A02 (Fixes F02 / Blocker B2 — Unified Action-Ledger Slot & Empty-Candidate Format / Cancelled Paste Replay)

- **Target Section**: `plan-v4.md` §4.6 rule 3, §4.7 (`Undo/Redo and Composition`), and §4.11.5 (`Clipboard and Rich Transfer`).
- **Exact Replacement Normative Text**:
  > **Action-Ledger Slot Completeness for Queued Commands.** Every application or editor command admitted into `PendingCommandQueue` during an active composition session (i.e., not `Rejected` at admission) MUST append exactly one entry to the settlement action ledger when replayed at settlement (`historySlot: true`):
  > 1. If the command mutates committed state, it records `{status: "Applied", historySlot: true, changed: true}` and pushes its `Command` history group.
  > 2. If `format({target: "composition", patch})` is replayed after composition settles to an empty committed range (`compRange.start == compRange.end`, e.g. `commit("")` or `cancel()`), it SHALL NOT mutate `typingIntent`; it MUST resolve to `{status: "Cancelled", reason: "TargetDeleted", historySlot: true, changed: false}`.
  > 3. If `applyPaste` or any other queued command resolves to `Cancelled` (with reason `TargetDeleted`, `TargetFragmented`, `TargetChanged`, `ProtectedContent`, or `UnrepresentableObjectSeam`), it MUST preserve that exact `reason` and record `{status: "Cancelled", reason, historySlot: true, changed: false}` so that a following queued `Undo` inspects the cancelled command's ledger slot and returns `NoChange(CommandWithoutHistory)` rather than falling through to undo the composition or an earlier history group.

### Amendment A03 (Fixes F03 — Unified Seed Resolution on `startComposition` and `retarget`)

- **Target Section**: `plan-v4.md` §4.4, §4.11.6 (`Formatting and Link Policy`), and §5.2 (`Link Boundary Policy`).
- **Exact Replacement Normative Text**:
  > **Composition Seed Resolution on Start and Retarget.** Whenever a composition session starts at or is retargeted via `retarget` to committed range `[u, v]` in `activeFlow`, the composition attribute seed `S` SHALL be recomputed from `(Dc, activeFlow, [u, v], typingIntent)` using the identical boundary seed rule:
  > 1. If `u < v`, `S` is ` merge(firstUnitAttrs(Dc, activeFlow, u, v), typingIntent)`.
  > 2. If `u == v`, base attributes inherit from the left neighbor at `u - 1` inside the same leaf block when `u > blockStart`, or from the right neighbor at `u` inside the same leaf block when `u == blockStart` (falling back to `emptyBlockAttributes` on an empty block), except that `link` is inherited if and only if `u > blockStart`, `u < blockEnd`, and both `u - 1` and `u` share the same `link.id`. `S` is `merge(baseAttrs, typingIntent)`.

### Amendment A04 (Fixes F04 / Blocker B3 — Block-Object Group Identity Resolution in Structural Reconstruction)

- **Target Section**: `plan-v4.md` §4.3 (`Structural Reconstruction and Block Identity`, rules 1, 2, 7, 8).
- **Exact Replacement Normative Text**:
  > **Block-Object Group Identity and Endpoint Conversion Rule.** During structural reconstruction of a flow after replacing `[start, end]`:
  > 1. A `BlockObject` leaf `objId` at an endpoint of `[start, end]` is converted in place to a `Paragraph` retaining `objId` **if and only if** `objId`'s `ObjectUnit` was removed while `objId`'s trailing `BoundaryUnit` survived (or `objId` was the final leaf of the flow and no following leaf was merged into its group).
  > 2. If `objId`'s `ObjectUnit` AND its trailing `BoundaryUnit` (`boundary:${nextLeafId}`) are both deleted so that one or more surviving right-hand leaves merge into the same reconstructed group `g`, `objId` was deleted rather than converted in place:
  >    - If `g` contains a surviving `ObjectUnit` belonging to a merged `BlockObject` leaf `survivingObjId`, `g` takes `id = survivingObjId` (preserving that `BlockObject` node and its ancestor containers).
  >    - Otherwise, `g` takes the `NodeId`, leaf `type` (`Paragraph`, `Heading`, `CodeBlock`), `props`, and ancestor container chain (`List`, `ListItem`, `Quote`) of the first surviving merged text leaf in `g`.

### Amendment A05 (Fixes F05 / Blocker B4 — Post-Structural Text Caret Normalization & Atomic `deleteCodePoints` / `deleteUtf16` Preflight)

- **Target Section**: `plan-v4.md` §2.5 (`Selection Domains`), §4.11.2 (`Structural Editing`), and §6.3 (`Android InputConnection`).
- **Exact Replacement Normative Text**:
  > **Post-Structural Text Caret Normalization.** Whenever a structural command (`deleteNode`, `moveNode`, `unwrapBlock`, `deleteTable`, or history restoration) leaves `selectionKind.type == "text"` with a collapsed caret `[p, p]` positioned on a `BlockObject` leaf (`ObjectUnit` or its trailing `BoundaryUnit`), the engine SHALL snap `[p, p]` to the nearest surviving text block leaf in the same flow (preferring the first text leaf at or after `p` at offset `0`, or the last text leaf before `p` at its end offset). If no text leaf exists in the flow, only `Selection.Block(nodeId)` is valid.
  >
  > **Surrounding-Deletion Atomic Preflight (`deleteCodePoints` and `deleteUtf16`).** Both `deleteCodePoints(before, after)` and `deleteUtf16(before, after)` operate strictly on text and joinable prose block boundaries around the current text selection `[s, e]`:
  > 1. Before mutating state, scan `[s - Δbefore, s]` and `[e, e + Δafter]` in the active flow projection.
  > 2. If either side crosses an `ObjectUnit` (`BlockObject` or `Table`), an `InlineAtomUnit`, or a `BoundaryUnit` whose left and right leaf blocks cannot be joined by `Backspace`/`Delete` (such as `CodeBlock` vs. prose), or if `deleteCodePoints` encounters an isolated surrogate on either side, the command MUST reject atomically (`Rejected`, `InvalidTarget` / `MalformedSurrogate`) with zero state mutation.

### Amendment A06 (Fixes F06 — Explicit-Target Precedence Over Active Cell Selection, Replay Error Fence, and Innermost Quote Exit on `Enter`)

- **Target Section**: `plan-v4.md` §2.5, §4.6 (`Pending Command Queue and Settlement Replay`), and §4.11.2 (`Enter` in Empty Containers).
- **Exact Replacement Normative Text**:
  > **Explicit Target Precedence During Active Cell Selection.** When `selectionKind.type == "cells"`, commands that accept an explicit target parameter (`range`, `point`, or `anchorHandle`) SHALL route to `Selection.Cells` replacement **if and only if** `range == null && point == null && anchorHandle == null`. If an explicit `range`, `point`, or `anchorHandle` is supplied, the command resolves and executes in that explicit target's `FlowId` without clearing or replacing the unrelated `Selection.Cells` state unless the target is inside one of the selected cells and mutates it.
  >
  > **Total Settlement Replay Error Fence.** During native `commit` or `cancel` settlement replay (`_replayPending`), any replayed command that throws or returns a structural/domain rejection (`TargetDeleted`, `TargetFragmented`, `TargetChanged`, `ProtectedContent`, `UnrepresentableObjectSeam`, `InvalidTarget`, `InvalidNodeId`) MUST be caught per-command and recorded in `CommandOutcomes` as `{status: "Cancelled", reason, historySlot: true, changed: false}` without aborting the native `commit`/`cancel` settlement or leaving `pending` un-drained.
  >
  > **Innermost Empty Container Exit on `Enter`.** When `Enter` is invoked at an empty `Paragraph` (`units.length == 0`), the engine SHALL inspect the paragraph's immediate parent container first: if `parent.type == "quote"`, `Enter` unwraps one `Quote` level around that paragraph before considering any enclosing `ListItem`. Only when the empty paragraph's immediate parent is `ListItem` (not an inner `Quote`) does `Enter` split or lift the `ListItem`.

### Amendment A07 (Fixes F07 / Blocker B5 — Isolated-Surrogate Hard Segmentation Boundaries, `pasteRich` Split Fidelity, `emptyBlockAttributes` Sanitization & HTML5 Fragment Context)

- **Target Section**: `plan-v4.md` §2.6, §3 (`Pinned Unicode Conformance`), §4.11.5 (`Clipboard and Rich Transfer`), and §6.5 (`Web DOM and Clipboard`).
- **Exact Replacement Normative Text**:
  > **Isolated Surrogate Segmentation Rule (Replacing §3 line 190).** Every isolated surrogate code unit (`0xD800..0xDBFF` not immediately followed by `0xDC00..0xDFFF`, or `0xDC00..0xDFFF` not immediately preceded by `0xD800..0xDBFF`) MUST be treated as having **hard `Control`-like grapheme segmentation boundaries (`GB4`/`GB5`, NOT `Replacement`/`Other`) on both sides**. An isolated surrogate forms a mandatory 1-unit grapheme cluster and SHALL NEVER coalesce with preceding or following `Extend`, `ZWJ`, `SpacingMark`, or `Prepend` code points. Inline atoms (`InlineAtomUnit`), block objects (`ObjectUnit`), and block boundaries (`BoundaryUnit`) also form mandatory 1-unit segmentation boundaries on both sides.
  >
  > **`pasteRich` Target Leaf Preservation and `emptyBlockAttributes` Sanitization.**
  > 1. When `pasteRich` inserts a multi-block slice into a target text block `B` at `offset == 0`, the pasted blocks are inserted **before** `B`, and `B` MUST retain its original `NodeId`, `type` (`Paragraph`, `Heading`, `CodeBlock`), and `props` (`level`, `language`, etc.). When `offset > 0`, splitting `B` into `(B_left, B_right)` preserves `B.type` and `B.props` on both halves (`B_left` keeps `B.id`).
  > 2. Every imported or pasted block's `emptyBlockAttributes` MUST pass through the exact same attribute allowlist, link-scheme sanitizer (`http:`, `https:`, `mailto:`, `#`), and `resourceId` remapper as unit `attrs`, incrementing `importReport.removedUnsafeLinks` and `importReport.droppedAttributes` on stripped entries. Every imported unit MUST satisfy `typeof u.char == "string" && u.char.length == 1` and reference only valid or remapped `resourceId`s; otherwise `pasteRich` rejects atomically with `InvalidRichFragment`.
  > 3. **HTML5 Fragment Context Normalization.** Before parsing an HTML clipboard fragment with an HTML5 parser (`DOMParser`), if the trimmed fragment starts with `<tr`, `<td`, `<th`, `<tbody`, `<thead`, or `<colgroup`, the importer MUST wrap it in matching `<table><tbody>...` context tags before parsing; any bare `<li>` elements parsed as direct children of `<body>` MUST be wrapped in a `<ul>` container before canonical block conversion.

### Amendment A08 (Fixes F08 / Blocker B6 — Two-Component Monoid Measure `(contentUtf16Length, leafBlockCount)` & Unified `pendingBytes` Cap)

- **Target Section**: `plan-v4.md` §2.3 (`Token and Boundary Addressing`), §4.8 (`Storage and Complexity Contract`), and §4.10 (`Memory and History Retention`).
- **Exact Replacement Normative Text**:
  > **Two-Component Monoid Measure on Persistent Hierarchical Trees.** Every internal node in a container's B+ child sequence and every block/container node in `ParentMap` MUST maintain the 2-component additive monoid measure $M(v) = (\text{contentUtf16Length}(v), \text{leafBlockCount}(v))$:
  > - `TextBlock` leaf $b$: $(\text{pieceTreeUtf16Length}(b), 1)$.
  > - `BlockObject` leaf $o$: $(1, 1)$.
  > - `Table` node $t$ within its parent flow: $(1, 1)$. Each `TableCell.flow` is an independent `Flow` root in `ParentMap` (`parentId = null`) so token rank inside a cell climbs only to the cell's `FlowId`.
  > - `Container` (`Flow`, `List`, `ListItem`, `Quote`) with children $c_1, \dots, c_k$: $(\sum_{i=1}^k \text{contentUtf16Length}(c_i), \sum_{i=1}^k \text{leafBlockCount}(c_i))$.
  > - **Flow-Projected Rank Formula**: Climbing `ParentMap` from leaf $b$ to its enclosing `Flow` root accumulates $(\text{charPrefix}(b), \text{leafPrefix}(b))$ by summing $M$ over strict left siblings at each B+ ancestor. The flow-projected start offset of leaf $b$ is $\text{charPrefix}(b) + \text{leafPrefix}(b)$.
  > - **`BoundaryUnit(rightLeafId)` Existence and Rank**: `boundary:${rightLeafId}` exists in `flow` **if and only if** `rightLeafId` belongs to `flow` **and** $\text{leafPrefix}(\text{rightLeafId}) \ge 1$. Its flow-projected offset is $\text{charPrefix}(\text{rightLeafId}) + \text{leafPrefix}(\text{rightLeafId}) - 1$. If $\text{leafPrefix}(\text{rightLeafId}) == 0$ (because all preceding leaves in `flow` were deleted), `resolveBoundaryRank(rightLeafId)` MUST return `null` (`TargetDeleted`).
  >
  > **Unified `pendingBytes` Retention Cap.** The engine enforces a single combined memory ceiling across all transient queues and transfer caches:
  > $$\text{bytes}(\text{nativeQueue}) + \text{bytes}(\text{pending}) + \text{bytes}(\text{pastes}) + \text{bytes}(\text{clipboard}) \le \text{retention.pendingBytes}.$$
  > Before admitting a new command, paste lease, or clipboard snapshot that would exceed `retention.pendingBytes`, the engine evicts oldest unreferenced `pastes` and `clipboard` entries in FIFO order; if total retained bytes would still exceed `retention.pendingBytes`, admission rejects with `AdmissionCapacityExceeded`.

### Amendment A09 (Fixes F09 / Blocker B7 — Dual `(Dc, Dv)` Crash-Recovery Contract & Journal/Checkpoint Durability State Machine)

- **Target Section**: `plan-v4.md` §3 (`I14`), §7 lines 195–199 (`Persistence, Concurrency, and Crash Recovery`), and §8 Gate `G7`.
- **Exact Replacement Normative Text**:
  > **Crash-Recovery State Machine and Active-Composition Recovery (`Dc` + `Dv`).**
  > 1. **Framed Checksummed Journal & Checkpoint Protocol**:
  >    - Each journal frame is written as `[magic: u32 ("EDJ1"), payloadLen: u32, payloadCrc32c: u32, headerCrc32c: u32, payloadBytes]` followed by `fsync(journalFd)` before any edit is acknowledged as durable.
  >    - Each checkpoint write serializes `[magic: u32 ("EDCP"), baseRev: u64, stateHash: utf8, payloadCrc32c: u32, payloadBytes]` to `checkpoint.tmp`, calls `fsync(tmpFd)`, atomically renames `checkpoint.tmp -> checkpoint.dat`, and calls `fsync(parentDirFd)` before truncating superseded journal frames.
  > 2. **Recovery Replay & Torn-Write Rules**:
  >    - On startup, load `checkpoint.dat` (or initialize empty base state at `baseRev = 0` if no checkpoint exists; if `checkpoint.dat` is present but fails magic/CRC verification, abort with `CorruptCheckpoint`).
  >    - Scan `journal.log` sequentially from byte offset `0`:
  >      - If a frame at the tail of `journal.log` has an incomplete header/payload or CRC32C mismatch **and** no valid frame header follows it, truncate `journal.log` at that offset and emit diagnostic `JournalTailTruncated(offset, reason)`.
  >      - If a corrupted frame is followed by any subsequent valid frame header, or if a valid frame with `afterRev > checkpoint.baseRev` fails continuity (`frame.beforeRev != currentRev || frame.prevFrameHash != currentStateHash`), abort recovery with `CorruptJournalInterior` (`NoInteriorGapReplay`).
  >      - Frames with `afterRev <= checkpoint.baseRev` are skipped idempotently.
  > 3. **Dual `(Dc, Dv)` Active-Composition Recovery (Preserving Secondary Cells & Undo)**:
  >    - When journalling a state with `ActiveComposition != null`, the journal payload MUST record both `committedDocument` (`Dc`, including `Ac`) and `visibleDocument` (`Dv`, including `Av`).
  >    - If the final recovered journal frame had `ActiveComposition != null` and `Dv != Dc` (for example, an active `Selection.Cells` composition where `Dv` cleared secondary cells `c01..c11`), recovery restores `Dv` as the current document with `ActiveComposition = null` **and** initializes `history` with a single undoable `RecoveredComposition` history entry whose `before` state is `(Dc, Ac)` and whose `after` state is `(Dv, Av)`. Pressing `Undo` once after crash recovery atomically restores `Dc` (including all cleared secondary table cells).

### Amendment A10 (Fixes F10 — Desktop AWT `getTextLocation` Bounds/Null-Offset Contract, Root Input Session Routing & Web DOM Composing-Leaf Freeze)

- **Target Section**: `plan-v4.md` §4.2, §6.3 (`Desktop AWT InputMethodRequests`), and §6.4 (`Web DOM Composing-Node Preservation`).
- **Exact Replacement Normative Text**:
  > **Desktop AWT `getTextLocation(offset)` Bounds & Null-Offset Contract (§6.3 item 3).**
  > - When `markedLength != null`:
  >   - If `offset == null`, resolve `offset` to the leading edge of composed text (`charIndex = 0, trailing = false`).
  >   - Otherwise, validate `0 <= offset.charIndex < markedLength` (or `offset.charIndex == markedLength && !offset.trailing`) **before** computing `insertion = offset.charIndex + (offset.trailing ? 1 : 0)`. Reject `offset.charIndex < 0` (including `{charIndex: -1, trailing: true}`) or out-of-range indices with `InvalidComposedHit`.
  >
  > **Single Root Platform Input Session Across Flows (§4.2 & §6.3).** The editor host SHALL own a single root `PlatformTextInputSession` / `InputConnection` / `UITextInput` / `InputMethodRequests` adapter at the `EditorViewport` level rather than delegating to per-paragraph or upstream `BasicTextField` `TextInputSession.skiko.kt` sessions (which replace `0..length` on every `onEditCommand`). When user focus moves between distinct `FlowId`s (`body` vs. `TableCell.flow`) while `ActiveComposition == null`, the root adapter updates its active flow binding and notifies the platform (`restartInput` / selection notification). During an active composition (`ActiveComposition != null`, including `Selection.Cells`), all platform queries and mutations remain bound to `ActiveComposition.flowId` until settlement.
  >
  > **Web DOM Composing `Text`-Node and Wrapper-Chain Preservation (§6.4).** While `ActiveComposition != null` on Web, the DOM reconciler MUST preserve reference identity (`node.isSameNode(prevNode)`) not only for the UTF-16 subrange `[compStart, compEnd]`, but for the **entire DOM `Text` leaf node(s)** containing the active composition and their ancestor inline-mark element chain (`<span>`, `<strong>`, `<em>`, `<a>`) up to the block element. Any programmatic or queued formatting mutation that would split a composing DOM `Text` node (`Text.splitText`) or reparent it into a new inline wrapper element MUST be deferred in the delayed-correction ledger until `compositionend` settlement.

### Amendment A11 (Fixes F11 — Skia `OneLineShaper` `(Bidi × FontStyle)` Complexity Contract & Default-Ignorable Run Preservation)

- **Target Section**: `plan-v4.md` §6.2 (`Skia / SkParagraph Shaping and Layout`) and §8 Gate `G8`.
- **Exact Replacement Normative Text**:
  > **Skia `OneLineShaper` `(BidiRegion × FontStyleBlock)` Complexity & Default-Ignorable Run Contract (§6.2).**
  > 1. In `SkParagraph` (`OneLineShaper.cpp`), `OneLineShaper::iterateThroughFontStyles` invokes `OneLineShaper::finish()` at the end of every `(BidiRegion × FontStyleBlock)` intersection $M$ (splitting on any `StyleType::kFont` change: `bold`, `italic`, `fontSize`, `fontFamily`, `letterSpacing`, `locale`, as well as Bidi level transitions). In unpatched Skiko `0.150.1` (`m150-1f14f1166a`), `fResolvedBlocks` is never cleared inside `finish()`, causing $\Theta(M^2 \log M)$ `std::sort` comparisons and `fAdvance` accumulation across both Bidi-heavy and style-run-heavy LTR paragraphs.
  > 2. Additionally, `OneLineShaper-m150.cpp` line 269 (`if (this->fCurrentRun->size() == 0) return;` in `addFullyResolved()`) drops runs consisting solely of zero-glyph default-ignorable code points (`U+200B` ZWSP, `U+200C` ZWNJ, `U+200D` ZWJ, `U+2066..U+2069` Bidi isolates), breaking `fTextRange` continuity.
  > 3. **Mandatory G8 Qualification Gate**: Production Desktop/Mobile builds using SkParagraph MUST either link a patched Skiko/Skia binary containing **both** `fResolvedBlocks.clear()` in `OneLineShaper::finish()` **and** `if (this->fCurrentRun->fTextRange.empty()) return;` in `OneLineShaper::addFullyResolved()`, OR enforce a hard per-paragraph shaping-block cap $M_{\max} \le 256$ (coalescing display runs or chunking layout blocks) so single-paragraph shaping bookkeeping never exceeds `1.0 ms` p95.

## 5. Additional Executable Validation, Performance Benchmarks & Scope Limitations

All disposable validation artifacts created during this review are self-contained under `reviews/gemini-v5-readiness/` on branch `review/gemini-v5-readiness` and can be reproduced with a single command:

```bash
node reviews/gemini-v5-readiness/run-all.mjs
```

### Summary of New Executable Probes in `reviews/gemini-v5-readiness/`

| File in `reviews/gemini-v5-readiness/` | Purpose & Oracles Verified | Executed Result |
| :--- | :--- | :--- |
| `repaired-document-model.mjs` | Minimal general repairs (`RepairedDocumentModel`, `repairedReconstruct`, `repairedUnitClusters`, `repairedUserRange`, `repairedCloneFresh`, `repairedClosedSlice`, `repairedTextLocation`) implementing Amendments A01–A08 and A10 without touching baseline files. | Passes all `25` counterexamples + all `35` inherited integrated cases + `60` multi-step fuzz traces |
| `probe-interactions-and-unicode.mjs` | Executes `25` independent counterexamples (`F01A`–`F10A_2`) against unmodified baseline (verifying `25/25` fail on baseline) and against `RepairedDocumentModel` (`25/25` pass), plus `60` seeded multi-step interaction fuzz traces with full `Undo`/`Redo` stack exhaustion. | `25/25` reproduced on baseline; `25/25` repaired; `35/35` inherited pass; `60/60` fuzz traces pass |
| `probe-hierarchical-storage-and-recovery.mjs` | Implements `HierarchicalVersionedIndex` (persistent B+ sequence + persistent AVL `TokenMap`/`ParentMap` with 2-component monoid measure `(contentLen, leafCount)`) and `DurableRecoveryJournal` (UTF-16LE `editor-document/1` + CRC32C journal/checkpoint state machine). | `1M` UTF-16 / `10K` blocks local edit: **p50 = `0.3318 ms`, p95 = `1.0671 ms`**; depth `d=1..32` verified; multi-cell crash recovery & torn-write truncation verified |
| `probe-shaper-and-performance.cpp` | Standalone C++20 benchmark (`g++ -std=c++20 -O2`) executing the exact `OneLineShaper::finish` and `addFullyResolved` logic from `OneLineShaper-m150.cpp`, `OneLineShaper-patched.cpp`, and `OneLineShaper-main.cpp`. | At `16,384` blocks: `2,469.30 ms` (`2.14B` comparisons) unpatched vs `2.60 ms` (`0` comparisons) patched (**`948.9×` speedup**); default-ignorable run continuity `16/16` vs `15/16` |
| `probe_chromium_dom.py` | Real headless Chromium (`154.0.8037.92`) browser probe testing `contenteditable` composing `Text`-node identity (`isSameNode` & `Selection`), HTML5 `DOMParser` on bare `<td>`/`<li>`, and HarfBuzz `👩‍💻` ligature splitting across DOM `<span>`s. | Verified `splitText(2)` breaks `Selection` on composing node; bare `<td>` dropped by HTML5 parser (`count=0`); `👩‍💻` width doubles (`39.94px` vs `19.97px`) across `<span>` |
| `results.json` | Machine-readable output of `run-all.mjs` recording environment versions, baseline SHA-256 verification, and all probe outputs. | Generated cleanly; all checks pass |

### Explicit Scope Limitations of This Review's Executed Evidence

1. **No Physical Mobile Hosts or Native OS IME Popups**: The sandbox VM is a headless Linux `x64` container without Android emulators/devices, iOS simulators/devices, macOS `NSTextInputClient`, or Windows TSF display servers. Therefore, real OEM keyboard behavior (Samsung honeyboard, GBoard, iOS Kotoeri/Pinyin, macOS Press-and-Hold, Windows Microsoft IME) and native screen readers (TalkBack, VoiceOver, NVDA, JAWS, Orca) could not be executed here and remain **unqualified (`Gate G0` / `Gate G5`)**.
2. **Offline Sandbox / JRE-Only Java Runtime**: The VM includes `/usr/bin/java` (`OpenJDK 17.0.20.1` JRE) without `javac` or outbound internet access to Maven Central. Consequently, `research/compose-probe` Kotlin/Skiko sources were audited directly from the committed `upstream/` source jars and C++ `OneLineShaper` sources were compiled directly with `g++ 12.2.0 -std=c++20 -O2`.
3. **Node Reference vs. Production Kotlin/Skiko**: `RepairedDocumentModel` and `HierarchicalVersionedIndex` prove the mathematical consistency and algorithmic complexity of the repaired v5 contracts in JavaScript, not that a production Kotlin/Compose editor has been built.

## 6. Remaining Qualification Matrix (Requirements, Evidence, Decisive Experiments, Thresholds & Fallbacks)

| Gate / Area | Requirement | Current Evidence (Baseline + This Review) | Missing Decisive Experiment | Pass / Fail Criterion | Architectural Fallback & Reopen Condition | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Core Model & History (`G2`, `G3`, `G4`)** | Path-independent `Dc`/`Dv` annotations & anchors, ledger-slot completeness, `BlockObject` reconstruction, seed & caret normalization | **25/25** baseline counterexamples reproduced & fixed in `RepairedDocumentModel`; **60/60** multi-step fuzz traces pass | Incorporate Amendments **A01–A08** into `plan-v4.md` and `validation/codex/run-20261015-19/` | `100%` of baseline `20` groups + all `25` new counterexample regressions pass with zero invariant violations | **Freeze-Blocking**: Do not freeze v5 until A01–A08 are merged into the normative spec and reference model. | **Freeze-Blocking (B1–B6)** |
| **Unicode & Rich Import (`G1`, `G4`)** | Pinned Unicode 16.0 UAX #29/#9 tables, isolated-surrogate hard boundaries, `pasteRich` heading/code split & `emptyBlockAttributes` sanitization, HTML5 fragment wrapping | Verified in `probe-interactions-and-unicode.mjs` (`F07A–F07E`) and Chromium `154.0.8037.92` (`probe_chromium_dom.py`) | Generate pinned Unicode 16.0 property tables in Kotlin and run full official `GraphemeBreakTest.txt` (`~600` cases) + `BidiTest.txt` + `BidiCharacterTest.txt` (`~91K` cases) | `100%` agreement on official Unicode 16.0 test files + `100%` pass on `F07A–F07E` and HTML5 fragment fixtures | **Freeze-Blocking for Spec Text (A07)**; **Release-Blocking for G1 Table Build**. Reopen if platform clipboard strips custom MIME types. | **Freeze-Blocking (B5) / Release-Blocking (G1)** |
| **Persistent Hierarchical Storage (`G6`)** | Persistent B+ sequence + persistent `TokenMap`/`ParentMap` with 2-component monoid `(contentLen, leafCount)` and unified `pendingBytes` cap | Verified in `probe-hierarchical-storage-and-recovery.mjs`: `1M` units / `10K` blocks local edit **p50 = `0.33 ms`, p95 = `1.07 ms`**; depth `d=1..32` verified | Port `HierarchicalVersionedIndex` to production Kotlin/Compose and benchmark with realistic GC/heap profiler on Desktop & Android | Local edit on `1M` UTF-16 / `10K` blocks: **p95 <= `2.0 ms`** (Desktop), **p95 <= `4.0 ms`** (Android mid-tier); retained heap <= `pendingBytes` | **Freeze-Blocking for Monoid & Cap Spec (A08)**; **Release-Blocking for G6**. Fallback: bounded path-copying B+ tree with lazy parent offsets per dirty spine. | **Freeze-Blocking (B6) / Release-Blocking (G6)** |
| **Crash Recovery & Durability (`G7`)** | Framed CRC32C journal, atomic checkpoint + `fsync(parentDir)`, `NoInteriorGapReplay`, and dual `(Dc, Dv)` active-composition recovery | Verified in `probe-hierarchical-storage-and-recovery.mjs` (`F09A`, `F09B`) including multi-cell composition crash + Undo and 40-byte torn write truncation | Fault-injected POSIX/Windows/Android/iOS filesystem power-loss harness testing `rename` + `fsync` + torn journal frames | `0` silent data loss across `1,000` fault-injected crash points; `1` Undo after multi-cell composition crash restores all secondary cells | **Freeze-Blocking for Spec Text (A09)**; **Release-Blocking for G7**. Reopen if target platform (`Web IndexedDB` / `OPFS`) lacks synchronous `fsync` on tab kill. | **Freeze-Blocking (B7) / Release-Blocking (G7)** |
| **Skia / SkParagraph Shaping (`G8`)** | Eliminate $\Theta(M^2 \log M)$ `OneLineShaper::finish` blowup across `(Bidi × FontStyle)` runs and preserve zero-glyph default-ignorable runs | Verified in C++20 probe (`probe-shaper-and-performance.cpp`): `2,469 ms` unpatched vs `2.60 ms` patched at `16,384` blocks (**`948.9×` speedup**); default-ignorable `16/16` vs `15/16` | Compile patched Skia (`fResolvedBlocks.clear()` + `fTextRange.empty()`) into Skiko Desktop/Android/iOS native artifacts and benchmark full `ParagraphImpl::layout` | `10,000`-unit paragraph with `1,024` alternating style/Bidi runs shapes in **p95 <= `8.0 ms`** with `100%` `fTextRange` continuity | **Freeze-Blocking for Spec Contract (A11)**; **Release-Blocking for G8**. Fallback if custom Skiko binary cannot ship: coalesce adjacent display runs and cap/split layout paragraphs at `M <= 256` runs. | **Freeze-Blocking (A11) / Release-Blocking (G8)** |
| **Desktop AWT / OS IME (`G0`, `G5`)** | `InputMethodRequests` coordinate & insertion offset contract (`F10A`), single root `EditorViewport` session (`TextInputSession.skiko.kt` bypass), CJK candidate window placement | `F10A_1` & `F10A_2` verified in `probe-interactions-and-unicode.mjs`; upstream `TextInputSession.skiko.kt` audited | Run live AWT host spike on macOS (Japanese Kotoeri, Chinese Pinyin), Windows 11 (MS IME), and Linux (IBus/Fcitx5) across multi-block and `Selection.Cells` composition | Candidate popup tracks caret within `<= 2px`; `0` dropped commits or duplicate insertions across `50` cross-block/cell IME sessions | **Freeze-Blocking for A10 Spec Amendment**; **Release-Blocking for G0/G5**. Fallback if OS IME rejects cross-block range shrinkage: commit-on-block-boundary fallback fence. | **Freeze-Blocking (A10) / Release-Blocking (G0, G5)** |
| **Android `InputConnection` & iOS `UITextInput` (`G0`, `G5`)** | Callback provenance (`SessionEpoch`), `beginBatchEdit`/`endBatchEdit` settlement fences, `deleteSurroundingTextInCodePoints` atomic preflight, cell-flow switching | `F05B` verified in `probe-interactions-and-unicode.mjs`; upstream `EditCommand.kt` & `TextInputSession.skiko.kt` audited | Execute on physical Android 14/15 (`GBoard`, `Samsung Keyboard`) and iOS 17/18 (`UIKit` `UITextInput` harness) with delayed paste/format during active composition | `100%` provenance fence accuracy; `0` zombie preedits after `restartInput()` or `unmarkText()`; `0` crashes on invalidated `UITextPosition` | **Release-Blocking (G0, G5)**. Fallback if OEM keyboard ignores `restartInput()` during composition: synchronous local epoch increment + ignore stale `InputConnection` instance callbacks. | **Release-Blocking (G0, G5)** |
| **Web DOM & Accessibility (`G0`, `G5`)** | Composing DOM `Text`-node + mark-wrapper preservation (`F10B_1`), HTML5 fragment wrapper (`F10B_2`), and virtualized accessibility tree synchronization | Executed in Headless Chromium `154.0.8037.92` (`probe_chromium_dom.py`) | Test Safari WebKit & Firefox Gecko composing `Text`-node preservation, plus VoiceOver / NVDA / TalkBack navigation across virtualized `10,000`-block documents | `isSameNode == true` and `Selection` preserved across concurrent sibling/queued mark updates; screen reader announces line/block/table coordinates within `<= 50 ms` | **Freeze-Blocking for A10 Spec Amendment**; **Release-Blocking for G0/G5**. Fallback: maintain a 3-screen-page mounted accessibility shadow window around the active caret/selection. | **Freeze-Blocking (A10) / Release-Blocking (G0, G5)** |

## 7. Concise Handoff to Codex

1. **Do Not Freeze v5 As-Is**: Incorporate **Amendments A01–A11** into `plan-v4.md` (and synchronize `editor-4.15.0-standalone-review.md` and `validation/codex/run-20261015-19/document-contract.md`) before declaring specification v5 frozen.
2. **Apply the Reference Model Repairs**: Port the minimal general fixes from `reviews/gemini-v5-readiness/repaired-document-model.mjs` into `validation/codex/run-20261015-19/` (`document-model.mjs`, `structural-model.mjs`, `document-tree.mjs`, `candidate-b-coordinator.mjs`, `integrated-model.mjs`, `awt-contract-model.mjs`) and update `DesktopAwtInputMethodRequestsSpike.java` to match the v5 AWT contract.
3. **Add the 25 Discriminating Regressions & Storage/Recovery/Shaper Probes to Verification**: Merge the `25` counterexample regressions in `reviews/gemini-v5-readiness/probe-interactions-and-unicode.mjs` and the storage/recovery checks in `reviews/gemini-v5-readiness/probe-hierarchical-storage-and-recovery.mjs` into the verification runner so future edits cannot regress `Dc`/`Dv` anchor/annotation projection, action-ledger slots, `BlockObject` boundary reconstruction, isolated-surrogate segmentation, `pasteRich` heading/code preservation, or the 2-component monoid rank formula.
4. **Preserve Historical & New Evidence**: Keep `validation/codex/run-20261015-19/results.json` as historical run-19 evidence and generate a new timestamped validation run (`run-20261016-20`) once A01–A11 are integrated.
5. **Conditions for an Honest v5 Specification Freeze**: Once Amendments A01–A11 are incorporated and verified by the expanded regression suite, **specification v5 can be honestly frozen as a mathematically consistent architecture and contract baseline**, while explicitly keeping Gates `G0`, `G1`, `G5`, `G6`, `G7`, and `G8` marked **OPEN / UNQUALIFIED FOR PRODUCTION RELEASE** until the host, Unicode table, persistent Kotlin index, fault-injected filesystem recovery, and patched Skiko native binary qualifications in Section 6 pass.
