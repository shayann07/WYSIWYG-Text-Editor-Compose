# Paragraph Layout and Input Geometry Audit Report

**Date:** 2026-10-17  
**Auditor:** Antigravity (Advanced Agentic Coding)  
**Status:** QUALIFIED (Automated Attached-Host Prototype Suite)  
**Baseline Git Commit:** `784ab6d`  
**Host Scope:** Attached AWT `EditorSurface` host in real Swing hierarchy with live Compose layout rendering; synthetic IME callback injection and AWT keyboard/mouse event dispatch. (Physical OS IME candidate qualification remains `BLOCKED / NOT RUN` in background sessions).

---

## 1. Executive Summary

This audit records the completion, hardening, and verification of two major prototype milestones:
1. **Stage 1 (Input & Geometry Fallback):**
   - Cleanly reproduced and distinguished three separate geometry conditions:
     (a) attached, showing component positioned offscreen;
     (b) component that is not showing (`isShowing == false`);
     (c) component whose screen location is unavailable (`isShowing == true`, but native transform throws `IllegalComponentStateException`).
   - Audited `getTextLocation` against the official JDK 23 `InputMethodRequests` specification: returned `null` on unavailable geometry rather than inventing a bogus `Point(0, 0)` or `Rectangle(0, 0, 0, 0)` screen origin.
   - Made `getLocationOffset` catch `IllegalComponentStateException` specifically and return `null` when a valid screen-to-local transform cannot be obtained.
   - Tested real nonzero component origin (`Point(250, 180)`), confirming that local coordinates (e.g. `(2, 4)`) are rejected when evaluated against screen coordinates.
   - Observed `onEndCompositionRequested` separately from event injection, verifying synchronous/reentrant and delayed terminal callbacks on Escape and focus loss.
   - Deeply verified queued commands (`Ctrl+B` then `Ctrl+Z`) during active composition with step-by-step Redo/Undo chain and exact history counts.
   - Corrected all evidence labels across source comments, console PASS lines, results JSON, and report text.
2. **Stage 2 (Independent Paragraph Layout):**
   - Explicitly reproduced the single-layout bidi defect across both paragraphs: demonstrated that a single Compose `TextLayoutResult` globally forces all paragraphs into the base direction of the first paragraph (forcing an Urdu paragraph following English into LTR and pinning it to the left margin).
   - Implemented per-structural-paragraph Compose text layout and base direction resolution, partitioning by `UnitKind.PARAGRAPH_BREAK` while preserving literal soft breaks (`char == '\n'` with `UnitKind.TEXT`).
   - Implemented an explicit direction policy: first strong character resolves direction; empty and neutral-only paragraphs inherit preceding paragraph base direction, defaulting to LTR.
   - Maintained a single `Geometry` source of truth for text drawing, caret placement, selection highlighting, hit testing, visual line navigation, and AWT composition coordinates.
   - Verified uncached reference layout comparison (`referenceMeasure`) against multi-paragraph layouts.
   - Comprehensive regression coverage across mixed English/Urdu/Arabic documents, boundary transitions, Enter split, Backspace/Delete join, formatting across breaks, scrolling candidate placement, and `.astra` file persistence.

All 5 verification gates in `python desktop-prototype/verify.py` pass cleanly:
- `build`: PASS (Kotlin 2.2.20 / Compose Desktop 1.12.1)
- `kernel-files-formatting`: PASS (Kernel model checks, 10 file roundtrip checks, 16 formatting checks)
- `stress`: PASS (2,000 generated composition sessions, 12,000 randomized range-format commands)
- `attached-host`: PASS (Urdu/Arabic RTL, Japanese IME host, and Paragraph Layout host checks)
- `frozen v5`: PASS (All 45 files in `v5-freeze-manifest.json` verified with exact SHA-256 hashes)

---

## 2. Stage 1: Input & Geometry Fallback Audit

### 2.1 Geometry Fallback and Screen Coordinates
- **Audit Against AWT Specification:**
  The JDK 23 contract for `InputMethodRequests.getTextLocation` states:
  > `@return a rectangle representing the screen metrics of the offset, or null if the screen location is not available`
  
  Previous revisions substituted `Point(0, 0)` or returned `Rectangle(0, 0, 0, 0)`. Returning `(0, 0)` invents a false screen origin at the top-left corner of the primary display, which causes native IME candidate windows to jump to `(0, 0)` when a component is not showing. `DesktopEditor.kt` now declares `override fun getTextLocation(offset: TextHitInfo?): Rectangle?` and specifically catches `IllegalComponentStateException`, returning `null` when `isShowing == false`, when `locationOnScreen` fails, or when geometry is unavailable.
- **`getLocationOffset` Fallback:**
  `getLocationOffset` catches `IllegalComponentStateException` specifically and returns `null` when a valid screen-to-local transform cannot be obtained.
- **Three Distinguished Conditions:**
  1. *Attached showing offscreen:* Surface in window at `Point(-16000, -16000)`. `isShowing == true`, `locationOnScreen` succeeds with negative coordinates, and `getTextLocation` correctly incorporates true negative screen coordinates.
  2. *Not showing:* Detached surface or hidden window (`isShowing == false`). `locationOnScreen` throws `IllegalComponentStateException`, `getLocationOffset` returns `null`, and `getTextLocation` returns `null`.
  3. *Screen location unavailable:* Attached component whose screen transform fails (tested via subclass where `isShowing == true` but `getLocationOnScreen()` throws `IllegalComponentStateException`). `getLocationOffset` returns `null`, and `getTextLocation` returns `null`.
- **Nonzero Component Origin:**
  Surface placed inside a frame at `Point(250, 180)`. Assertions verify `getTextLocation` reflects positive screen coordinates >= (250, 180), and local coordinates `(2, 4)` reject cleanly without false hits.

### 2.2 Queued Command Ordering & Attributed History
During active Japanese IME preedit (`"テキにスト"`, marked `2..2`), user triggers `Ctrl+B` followed by `Ctrl+Z`:
1. Both commands queue onto `EditorEngine.pending`, setting `snapshot.waiting == true` while keeping `undoCount == 1, redoCount == 0`.
2. Native IME commits `"日"`:
   - Composition commits `"テキ日スト"` (creates history entry: Composition).
   - Queued `Ctrl+B` executes on committed text (creates history entry: Format, bold=true).
   - Queued `Ctrl+Z` executes, undoing the format into the redo stack.
3. Assertions immediately after settlement:
   - `snapshot.waiting == false`
   - `snapshot.marked == null`
   - `snapshot.visible.text == "テキ日スト"`
   - `snapshot.selection == Selection(3, 3)`
   - `snapshot.undoCount == 2` (Fixture + Composition)
   - `snapshot.redoCount == 1` (undone Format)
   - `snapshot.visible.units.all { !it.style.bold }`
4. Step-by-step verification:
   - `Redo`: restores bold on unit 2 (`'日'`), `undoCount == 3, redoCount == 0`.
   - `Undo`: reverts bold, `undoCount == 2, redoCount == 1`.
   - `Undo`: reverts composition to `"テキスト"`, restoring selection `Selection(2, 2)`, `undoCount == 1, redoCount == 2`.
   - `Undo`: reverts fixture to empty, `undoCount == 0, redoCount == 3`.
   - Sequential `Redo` chain reconstructs exact state at each step.

### 2.3 Settlement Observation
`EditorSurface` exposes `onEndCompositionRequested: () -> Unit` and routes all settlement requests (`engine.onSettlementRequested`, focus loss, Escape key) through `requestEndComposition()`.
- **Escape cancellation:** Asserts `onEndCompositionRequested` is invoked exactly once when Escape is pressed.
- **Focus loss:** Dispatches `FocusEvent.FOCUS_LOST` via `processFocusEvent`, asserting focused state is cleared and `onEndCompositionRequested` is invoked.
- **Reentrant settlement callback:** Simulates native IME synchronously dispatching terminal event `InputMethodEvent` during `endComposition()`, verifying clean reentrant handling.

### 2.4 Evidence Label Honesty
All evidence descriptions are audited against actual execution:
- **Synthetic callback processing:** Tested (simulated `InputMethodEvent` preedit, conversion, and commit streams).
- **Attached Compose geometry:** Tested within stated conditions (real Compose layout on attached AWT host).
- **Actual Japanese candidate conversion/popup:** Marked `BLOCKED / NOT RUN` in automated suite (requires interactive foreground session with Microsoft IME).
- **Previous Urdu/Arabic native-key observations:** Historical smoke evidence from opt-in trace (`local-evidence/ime-1792146266448`), not comprehensive qualification.

---

## 3. Stage 2: Independent Paragraph Layout Architecture

### 3.1 Defect Reproduction: Compose Single-Layout Limitation
In Compose Multiplatform (Skiko), measuring a multiline document through a single `TextLayoutResult` resolves base paragraph direction globally from the document's first strong bidi character.
- **Reproduced Failure:** Measuring `"English\nاردو"` via single layout resolved both lines as LTR. Paragraph 1 ("اردو", offset 8) was incorrectly assigned `ResolvedTextDirection.Ltr` and positioned at the left page margin (`X < 0.5 * maxWidth`), failing right-margin alignment.
- **Independent Resolution:** Partitioning into structural paragraphs enables Paragraph 0 to measure as LTR (left-aligned) and Paragraph 1 as RTL (right-aligned at `X > 0.9 * maxWidth`).

### 3.2 Paragraph Partitioning and Direction Resolution
- **Structural vs Soft Breaks:** `partitionParagraphs(document)` partitions strictly by `UnitKind.PARAGRAPH_BREAK`. Soft breaks (`char == '\n'` with `UnitKind.TEXT`, e.g. from Shift+Enter) remain inside their enclosing paragraph layout and wrap according to that paragraph's base direction.
- **Direction Policy (`paragraphBaseDirection`):**
  - The first strong directional character determines the paragraph's base direction (`ResolvedTextDirection.Ltr` or `ResolvedTextDirection.Rtl`).
  - Empty paragraphs or paragraphs containing only neutral characters inherit the base direction of the preceding paragraph.
  - An initial empty or neutral-only paragraph defaults to LTR.
- **Layout Stacking:** Paragraph layouts are stacked vertically (`currentTop += pHeight`). Empty paragraphs receive minimum line height from the default font metrics.

### 3.3 Unified Multi-Paragraph Geometry
`Geometry` maintains `paragraphs: List<ParagraphLayout>` as the single source of truth:
- **Rendering:** Paragraphs draw text independently translated by `p.top`.
- **Selection Highlighting:** Multi-paragraph selections compute path slices per paragraph via `p.layout.getPathForRange(localA, localB)`.
- **Caret Geometry:** `Geometry.caret(offset, primary, leadingAffinity)` maps document offsets to the corresponding paragraph layout and returns exact screen-space rectangles.
- **Pointer Hit Testing:** `hit(point)` selects the target paragraph by vertical bounds (`targetY in p.top..p.bottom`), translates to local coordinates, and snaps to grapheme clusters.
- **Visual Navigation:** `stops(g)` sorts stops by `(line, x, offset)`. Visual Left/Right arrow keys navigate strictly by screen X position across line and paragraph boundaries.
- **Scroll Invalidation:** Dynamic scrolling updates `view.scroll`, invalidating cached geometry and shifting candidate popup locations upward.
- **Uncached Reference Comparison:** `referenceMeasure` validates cached layout structures against an uncached measurement path.

---

## 4. Verification & Regression Record

### 4.1 Automated Suite Summary

| Check Suite | Description | Verdict |
| :--- | :--- | :--- |
| **`build`** | Isolated Maven compilation of prototype and test classes | **PASS** |
| **`kernel-files-formatting`** | 15 kernel groups, 10 file persistence checks, 16 formatting checks | **PASS** |
| **`stress`** | 2,000 randomized composition runs, 12,000 range-format edits | **PASS** |
| **`attached-host` (Urdu/Arabic RTL)** | 19 sections: single-layout defect repro, bidi alignment, diacritics, bidi traversal, Enter/Backspace, reference measure, persistence, benchmarks | **PASS** |
| **`attached-host` (Japanese IME)** | 10 sections: preedit, conversion, popup geometry, 3 geometry conditions, multi-clause, Escape, focus loss, queued history | **PASS** |
| **`frozen v5`** | SHA-256 validation of all 45 frozen specification files | **PASS** |

### 4.2 Benchmark Measurements
Measured on Java OpenJDK 23 / Compose Desktop 1.12.1 (Windows 11 x86_64):
- **50 Paragraphs (Mixed English, Urdu, Arabic):** Measured in **18 ms** (Budget: 100 ms).
- **2,000 Words (Single Long Paragraph):** Measured in **28 ms** (Budget: 150 ms).

### 4.3 Honest Measurement and Scope Boundaries
- **Linear Measurement:** All paragraphs in the document are currently measured sequentially on change. While 50 paragraphs measure in 18 ms, documents with thousands of paragraphs will require viewport virtualization as planned in frozen v5 §2.1. Documents are never silently truncated.
- **Synthetic AWT Host Qualification:** Automated host checks test the AWT input method protocol via synthetic event injection. Live OS IME popup qualification requires developer verification as outlined below.

---

## 5. Manual Testing Procedure

Because background execution environments lack an interactive foreground window (`GetForegroundWindow() == 0`), live interactive qualification requires a developer session:

1. **Launch Interactive Editor with Tracing:**
   ```powershell
   .\desktop-prototype\run.cmd --ime-check --ime-language=ja-JP
   ```
2. **Japanese IME Verification:**
   - Switch Windows input method to Microsoft Japanese IME (`Win + Space` or language bar).
   - Type `nihon` -> verify romaji converts to Hiragana `にほん` in the preedit overlay.
   - Press `Space` -> verify conversion to Kanji candidate `日本`.
   - Observe physical candidate window position: verify popup appears directly below the composition underline.
   - Press `Enter` -> verify commitment into document text with blue accent underline removed.
   - Press `Ctrl+Z` -> verify entire composition is undone in a single step.
3. **Urdu / Arabic RTL Verification:**
   ```powershell
   .\desktop-prototype\run.cmd --ime-check --ime-language=ur-PK
   ```
   - Type Urdu text -> verify text aligns to the right margin.
   - Press `Enter` -> type English -> verify English begins at the left margin.
   - Use Left and Right arrow keys -> verify visual navigation across English and Urdu lines.
   - Select across the paragraph break with mouse drag -> press `Ctrl+B` -> verify both paragraphs become bold while paragraph break remains unstyled.
   - Inspect logged events in `desktop-prototype/local-evidence/ime-<timestamp>/events.jsonl`.

---

## 6. Source Manifest & Hashes

| Source File | SHA-256 Hash |
| :--- | :--- |
| `desktop-prototype/pom.xml` | `36466c45290569fe5e541c4a14f577275395696a37e57658c8a78810802ad6e0` |
| `desktop-prototype/run.cmd` | `c68514768c7d36dcd53fe6f6134c9302190b841641f13a5cd7c3db72b81ffb06` |
| `desktop-prototype/verify.py` | `38946ab0a4fa7151d7fc7f83a9cc2d6dab9f7884ce8d592d3191c1d7ff502b3b` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `eb94187c261d7d6a4b3c3a887c5055d1454880c01687232f056a445b4bd797db` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `1e7cccc7b488241ae1ec75bb48ff361fc53b3403b0a7370a40c0be8af8458e6f` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `dbeeccaff20eae6ce875f4f65a7e399fce867a4424b279ed6a79027d9617c195` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `a34044c8575032180f2166a45f0d6cc51ef1e76fd6293efec5f5b330cb91bffe` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `043e87612ecf40963de55dd2cf081a96454610da99131d9ebb2519b1360c616c` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `0c03cd09842e733f5cf3c5e74a52a9f9b1d7858b3e31623599cd2f5edd612a99` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `63362512c111d31258c652db58c4757d851c435708a42f0d8697c2e806441dda` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/JapaneseHostChecks.kt` | `ab3e16cb2765ebbaae697d2c69f9893c7d9e3e69e999010ad4077486be62d540` |
| `desktop-prototype/src/main/kotlin/editor/Main.kt` | `c3d1a40dd87a612de2e517432cbbbffd4bff6255bb3f5a2e7a2dbedbe59085b3` |
| `desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt` | `de2333bca561bcc6f052b7801bae5f315660c33387555c7cdfb4644138fde06f` |
| `desktop-prototype/src/main/kotlin/editor/StressChecks.kt` | `56c94d5a8f1e76230a918c23e1d9633ad42c8c2f81f4707a7d66c09e2284f091` |
| `desktop-prototype/src/main/kotlin/editor/WindowFiles.kt` | `62babbb3d5de30fa7c7834b2b35803524923bdaab5595de16539ac773e630d58` |
