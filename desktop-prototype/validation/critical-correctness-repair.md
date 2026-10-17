# Critical Correctness Repair & Argon Audit Verification Report

## 1. Executive Summary

This report documents the implementation and executable verification of the confirmed correctness repairs identified during the Argon critical-path investigation, together with explicit refutations and withdrawals of claims that did not survive independent verification against the actual codebase.

All repairs preserve the 45 frozen v5 specification files (`v5-freeze-manifest.json`) unchanged and pass `python desktop-prototype/verify.py` (isolated build, kernel/file/formatting suites, stress suites, attached AWT/Compose host suites, and 7 compiled Argon regression probes).

---

## 2. Environment, Commands, and Source Hash Manifest

### 2.1 Execution Environment
- **OS**: Windows 10/11 (`win32`, 64-bit)
- **JDK**: OpenJDK 64-Bit Server VM (Temurin 21.0.8+9-LTS)
- **Kotlin / Compose Desktop**: Kotlin `2.0.21`, Compose Multiplatform Desktop `1.7.3`
- **Baseline Commit**: `75f5cb4`
- **Implementation Commits**: `0e1d1e5`, `762485c`, `dfcf5ee`
- **Isolated Verification Evidence**: `desktop-prototype/local-evidence/batch-20261017-155258-12840`

### 2.2 Reproducible Verification Commands
Run the complete isolated build and verification pipeline from the repository root:
```powershell
python desktop-prototype/verify.py
```
`verify.py` copies the workspace into an isolated directory under `desktop-prototype/local-evidence/batch-<timestamp>-<pid>`, compiles the sources via Maven, runs:
1. `editor.ChecksKt` (16 kernel checks, 12 file checks, 17 formatting checks, 1,093 Unicode 16 grapheme fixtures)
2. `editor.StressChecksKt` (2,000 generated composition sessions against `RefOverlayModel`, 12,000 randomized range-format operations, GB12/13 regional-indicator stress)
3. `editor.HostChecksKt` (attached AWT + Compose host checks, 19 `RtlHostChecks` sections, 9 `JapaneseHostChecks` sections)
4. All 7 executable Argon regression probes in `reviews/argon-critical-path/experiments/` against the isolated compiled classes
5. SHA-256 verification of all 45 frozen v5 specification files against `v5-freeze-manifest.json`

### 2.3 Verified Source Hash Manifest (SHA-256)

| File | SHA-256 |
| :--- | :--- |
| `desktop-prototype/pom.xml` | `36466c45290569fe5e541c4a14f577275395696a37e57658c8a78810802ad6e0` |
| `desktop-prototype/run.cmd` | `c68514768c7d36dcd53fe6f6134c9302190b841641f13a5cd7c3db72b81ffb06` |
| `desktop-prototype/verify.py` | `46a2d9bebc97f766bee54b7bc4e6d68219f156edef496e3ad79cc7347a0c073e` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `d9339d2d78d525119924f640dd097b3506386e0c4c736379d4e59361d4868928` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `5bc1fe4be6ec59c339b325ff31675ba45ebf181d784027ec9e47eee8531f64fb` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `39d6b97af698f5a96ad940aea02150953398a4dd6f9a1266f69e4088ccc75e25` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `071e511496eca1c82065fe99dd15adabc9105bf7d57cc63c08f3eb84549eea89` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `ec25092614029983b1d9b135b4116722aac1942dd12af90305670c1a7bf8518e` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `d0d86cb6b8c85097385957afb088e8f0293b6524bb20a32a2ca6d1d0a93af8c5` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `63362512c111d31258c652db58c4757d851c435708a42f0d8697c2e806441dda` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/JapaneseHostChecks.kt` | `9d41d25b91cca843cca7c60e87b0026134ac98c3aebe6bf7076b8d421ea800f9` |
| `desktop-prototype/src/main/kotlin/editor/Main.kt` | `c3d1a40dd87a612de2e517432cbbbffd4bff6255bb3f5a2e7a2dbedbe59085b3` |
| `desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt` | `e150c53d8c2544d1d0420d26ef6c007bc0f8f23812c61c0a13bb45b5b08b0ef1` |
| `desktop-prototype/src/main/kotlin/editor/StressChecks.kt` | `d5084fb3df4e97b424c09ddbb628ffb953ea8ca1c7ea683eb56e21801ac5ca15` |
| `desktop-prototype/src/main/kotlin/editor/WindowFiles.kt` | `2e680bbef72c0e19c42022a02eb364885a5e3af803f5d386cafa9437c4a13bb4` |

---

## 3. Confirmed Defects & Repairs (`CONFIRMED_AND_FIXED`)

### 3.1 `A-3` — Partial-Commit Composition Abort Data Loss on Retained Original Segments
- **Requirement**: `editor-v5-standalone.md` §3.1 (`CompositionOverlay` / `OverlaySegment` / `SegmentOrigin`). Retained original segments must preserve independent original footprints in `Dc`. Accepting a positive prefix of a `REPLACEMENT` segment consumes that replacement's old footprint while remaining replacement units become provisional insertions; unconsumed `RETAINED` segments must remain in `Dc` if the session is later aborted.
- **Minimal Reproduction**:
  1. Initial document `"abcd"`, select `[0, 4)`:
     - `inputMethod("Xcd", committedCount = 1, caretOffset = 2)` followed by `abortComposition()`:
       - **Baseline actual**: `"X"` (silently lost retained suffix `"cd"`).
       - **Frozen v5 expected**: `"Xcd"`.
  2. Initial document `"abcd"`, select `[0, 4)`:
     - `inputMethod("abX", committedCount = 1, caretOffset = 2)` followed by `abortComposition()`:
       - **Baseline actual**: `"a"` (silently lost retained `"b"` and unconsumed `"cd"`).
       - **Frozen v5 expected**: `"abcd"`.
- **Root Cause**: `CompositionSession` previously tracked a single flat `[start, end)` replacement span and spliced `[session.start, session.end)` on any partial commit (`committedCount > 0`), destroying unconsumed `RETAINED` original footprints.
- **Repair**:
  - Implemented arbitrary-length segmented composition overlay (`OverlaySegment(dcStart, dcEnd, units, origin: RETAINED | REPLACEMENT)`) with per-epoch `epochBaseSegments` in `EditorEngine.kt`.
  - Each initially selected unit in `[start, end)` begins as an independent 1-unit `RETAINED` segment.
  - Within each epoch, `partitionSegments` matches maximal common prefix (`p`) and suffix (`s`) against `epochBaseSegments` (`matchPrefixUnits` / `matchSuffixUnits`), preserving matched sub-segments' independent `[dcStart, dcEnd)` footprints and `SegmentOrigin` while grouping the middle mismatch into a `REPLACEMENT` segment covering `[prefixEndDc, suffixStartDc)`.
  - On partial commit (`0 < committedCount < units.size`), `consumeCommittedPrefix` advances through the segmented overlay:
    - Fully or partially committed `RETAINED` segments advance past their committed original units without deleting uncommitted `RETAINED` segments.
    - Accepting any positive prefix of a `REPLACEMENT` segment consumes its `[dcStart, dcEnd)` footprint once, turning any residual uncommitted suffix of that replacement into a zero-footprint provisional insertion (`[pos, pos)`).
  - Preserved immutable attribution baselines, unit IDs, inline styles, `paragraphStyle`, structural `PARAGRAPH_BREAK` tags, and single-entry session Undo/Redo grouping.
- **Executable Regressions**:
  - `Checks.kt` Check 16 (both minimal examples, attributed variants, structural separators, repeated partial commits `"aXcd"` -> `"Ycd"` + abort -> `"aYcd"`, final commit, and full Undo/Redo).
  - `StressChecks.kt` (`RefOverlayModel` updated to the exact segmented-overlay state machine and verified across 2,000 generated composition sessions).
  - `reviews/argon-critical-path/experiments/probe_a3_partial_commit_data_loss.py`.

### 3.2 `A-4` — Empty-Paragraph Style Storage Separated from `PARAGRAPH_BREAK` Inline Style
- **Requirement**: `editor-v5-standalone.md` §1.1 & §3.2 (`PARAGRAPH_BREAK` structural units carry no inline character formatting).
- **Minimal Reproduction**:
  - Formatting an empty paragraph (`"a\n\nb"`, Bold at offset `2`) previously stored `TextStyleFlags(bold = true)` on `units[1].style` (`UnitKind.PARAGRAPH_BREAK`).
- **Root Cause**: Empty-paragraph typing metadata and structural separator character attributes shared a single `TextUnit.style` field.
- **Repair**:
  - Added `val paragraphStyle: TextStyleFlags = TextStyleFlags()` on `TextUnit` (`EditorEngine.kt`) while enforcing `unit.style == TextStyleFlags()` (always unstyled) on every `UnitKind.PARAGRAPH_BREAK`.
  - Updated `Formatting.kt` / `EditorEngine.kt` (`resolvedTypingStyle`, `toggleStyle`, `splitParagraphAt`, Backspace/Delete paragraph joins) to read and propagate `paragraphStyle` across empty paragraphs without styling the separator unit itself.
  - Upgraded `.astra` persistence in `DocumentFiles.kt` to format `version = 3` (persisting `paragraphStyleBold` / `paragraphStyleItalic` on `PARAGRAPH_BREAK` entries while keeping `bold = false, italic = false`), with transparent backward-compatible migration when loading v1/v2 `.astra` files.
- **Executable Regressions**:
  - `FormatChecks.kt` Checks 12–17, `FileChecks.kt` Checks 9–12, `Checks.kt` Check 16, and `probe_a3_partial_commit_data_loss.py`.

### 3.3 `KF-1` — UAX #9 Directional Isolates in `paragraphBaseDirection`
- **Requirement**: Paragraph base direction resolution must skip strong characters inside directional isolate scopes (`LRI` `\u2066`, `RLI` `\u2067`, `FSI` `\u2068` through matching `PDI` `\u2069`) per Unicode UAX #9 Rules P2–P3.
- **Minimal Reproduction**:
  - `"\u2067\u0627\u2069A"`: baseline returned `Rtl`; `java.text.Bidi` and UAX #9 require `Ltr`.
  - `"\u2066A\u2069\u0627"`: baseline returned `Ltr`; `java.text.Bidi` and UAX #9 require `Rtl`.
- **Repair**:
  - Added an `isolateDepth` counter in `paragraphBaseDirection` (`DesktopEditor.kt:69-91`) that increments on `\u2066..\u2068`, decrements on `\u2069` when `isolateDepth > 0`, and ignores strong directional characters while `isolateDepth > 0`.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 17 (`paragraphBaseDirection` vs `java.text.Bidi` oracle on isolate strings) and `probe_kf1_isolate_bidi.py`.

### 3.4 `KF-2` — Structural-Break and Empty-Paragraph Selection Highlights
- **Requirement**: Non-collapsed selections covering only a `PARAGRAPH_BREAK` (`Selection(2, 3)` on `"ab\ncd"`) or an empty paragraph (`Selection(2, 3)` on `"ab\n\ncd"`) must produce visible selection geometry.
- **Minimal Reproduction**:
  - Selecting `[2, 3)` on `"ab\ncd"` previously produced zero highlight rectangles because `ParagraphLayout.getPathForRange(2, 2)` is empty.
- **Repair**:
  - Added `Geometry.breakAndEmptySelectionRects(selection)` and `Geometry.hasRenderedSelection(selection)` (`DesktopEditor.kt:183-225`), drawn in `DocumentCanvas` (`DesktopEditor.kt:391-393`), rendering a directional `8f * scale`-wide highlight strip at the visual trailing edge of a selected paragraph break and at the visual leading edge of a selected empty paragraph.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 18 and `probe_kf2_kf3_break_geometry.py`.

### 3.5 `KF-3` — `Geometry.paragraphForOffset` Boundary Stability
- **Requirement**: Separator end offset `p.endOffset` belongs to paragraph `p` at local offset `p.textLength`; paragraph lookup must not jump across the separator into `p + 1` and clamp a negative local offset to `0`.
- **Minimal Reproduction**:
  - In `"ab\ncd"`, calling `paragraphForOffset(2, leadingAffinity = true)` previously jumped to paragraph `1` (`startOffset = 3`) and clamped `(2 - 3).coerceIn(0, 2)` to local offset `0` (`'c'`).
- **Repair**:
  - Removed the `leadingAffinity` cross-separator branch from `Geometry.paragraphForOffset(documentOffset)` (`DesktopEditor.kt:155-162`). Offset `2` (`p0.endOffset`) deterministically maps to paragraph `0` at local offset `2`, and offset `3` (`p1.startOffset`) maps to paragraph `1` at local offset `0`.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 18 and `probe_kf2_kf3_break_geometry.py`.

### 3.6 `KF-4` — Independent Reference Measurement & Hardcoded Layout Expectations
- **Requirement**: Reference layout checks must not be tautological wrappers around the production helpers under test.
- **Repair**:
  - Reimplemented `referenceMeasure` (`DesktopEditor.kt:261-310`, `387-390`) using an uncached `TextMeasurer(..., cacheSize = 0)`, direct `text.split('\n')`, `java.text.Bidi` for paragraph base direction, and independent `SpanStyle` construction without calling `partitionParagraphs`, `paragraphBaseDirection`, or `paragraphAttributedDisplay`.
  - Added hardcoded independent fixture assertions in `RtlHostChecks.kt` Section 19 (`lines 481-510`) verifying paragraph boundaries, resolved directions, and monotonic Y coordinates without calling `referenceMeasure`.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 19 and `probe_kf4_kf5_kf6_audit_gaps.py`.

### 3.7 `KF-5` — Active Composition (`marked != null`) Under Scroll
- **Requirement**: Scroll-offset coordinate transformation tests for IME queries must exercise an active composition (`marked != null`).
- **Repair**:
  - Updated `RtlHostChecks.kt` Section 16 (`lines 453-460`) to inject an active Urdu composition (`marked != null`), scroll the viewport by `+40px`, and verify that `getTextLocation` shifts by `-40px` in screen Y and `getLocationOffset` hit-tests to the active composition offset.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 16 and `probe_kf4_kf5_kf6_audit_gaps.py`.

### 3.8 `KF-6` — `EditorSurface.geometry()` Cache Invalidation on Host Resize Before Repaint
- **Requirement**: Synchronous AWT/IME queries calling `surface.geometry()` after `surface.setSize(newW, newH)` before a Compose repaint pass must not return stale geometry measured at the old host width/height.
- **Repair**:
  - Added logical `hostWidth` and `hostHeight` fields to `Geometry` and `ViewState` (`DesktopEditor.kt:149-150`, `254-255`, `318-378`, `591-603`) and scaled by `scale` inside `measureWith`. `EditorSurface.geometry()` checks `cached.hostWidth == width && cached.hostHeight == height` and immediately re-measures when host dimensions change.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 19 and `probe_kf4_kf5_kf6_audit_gaps.py`.

### 3.9 `B-1` / `B-2` / `B-3` — Soft-Wrap Boundary Affinity (`WrapAffinity`) and Wrapped-Line `VK_END`
- **Requirement**: At a soft-wrap boundary offset `k` (where line `l` wraps to line `l + 1` without a newline or space), `Selection` must distinguish `WrapAffinity.UPSTREAM` (visual trailing edge of line `l`) from `WrapAffinity.DOWNSTREAM` (visual leading edge of line `l + 1`) so `VK_END` / `ExtendLineEnd` can reach the true end of wrapped line `l`.
- **Minimal Reproduction**:
  - On `"あいうえおかきくけこ".repeat(12)` wrapped at `360px` (line 0 wraps at offset `k = 41`), pressing `VK_END` from offset `0` previously stopped at `40` (`k - 1`) because `getLineForOffset(41)` unconditionally mapped offset `41` to line `1`.
- **Repair**:
  - Added `enum class WrapAffinity { DOWNSTREAM, UPSTREAM }` to `Selection` and `EditAction.Move` (`EditorEngine.kt:23-38`, `60`).
  - Added `ParagraphLayout.isSoftWrapBoundary(localOffset)` (`DesktopEditor.kt:120-128`) and updated `Geometry.getLineForOffset(offset, wrapAffinity)` and `Geometry.caret(offset, wrapAffinity)` (`DesktopEditor.kt:215-260`) to keep `(k, WrapAffinity.UPSTREAM)` on line `l` at its visual trailing edge (`getLineRight(line)` for LTR, `getLineLeft(line)` for RTL).
  - Updated `EditorSurface.stops(g)` (`DesktopEditor.kt:677-688`) to emit `CaretStop(k, WrapAffinity.UPSTREAM)` at soft-wrap boundaries so `LineEnd` / `ExtendLineEnd` lands at `(41, WrapAffinity.UPSTREAM)` on line `0`.
- **Executable Regressions**:
  - `RtlHostChecks.kt` Section 19 and `probe_b1_soft_wrap_end_key.py`.

### 3.10 `A-1` / `A-2` / Lifecycle — Settlement Watchdog, IME Epoch Retirement, and Close/Save Hardening
- **Requirement**: Prevent permanent editor wedging if a native IME fails to deliver a terminal callback after `endComposition()`, reject late/duplicate callbacks from retired IME epochs, and protect unsaved active composition during window close and save-then-close flows.
- **Repair**:
  - Added `checkSettlementWatchdog(nowMs, timeoutMs = 2_000L)` in `EditorEngine.kt:401-416`, which cancels the pending command queue and sets a diagnostic notice while keeping the uncommitted composition intact (never silently auto-committing preedit).
  - Added `abortAndRetireComposition()` and `activeImeEpoch` / `imeEpoch` tracking in `EditorEngine.kt` and `DesktopEditor.kt` so late or duplicate native `InputMethodEvent` callbacks from a retired epoch are ignored.
  - Hardened `WindowFiles.close()` (`WindowFiles.kt:71-118`) to prompt when `state.marked != null` even if `dc == savedSnapshot`; choosing `Cancel` leaves the active composition untouched, while `Discard` invokes `onDiscardComposition`.
  - Hardened `WindowFiles.save(closeAfter = true)` (`WindowFiles.kt:18-56`) to refuse window disposal if `marked != null`, `waiting`, or document/overlay revisions advanced during the save flow.
- **Executable Regressions**:
  - `FileChecks.kt` Check 12, `JapaneseHostChecks.kt` Section 9, and `probe_a1_settlement_wedge.py`.

---

## 4. Refuted / Withdrawn Claims & Preserved Policies

| Finding ID | Original Claim | Disposition | Verification Evidence |
| :--- | :--- | :--- | :--- |
| **`C-3`** | `paragraphAttributedDisplay` emits one `SpanStyle` per character without coalescing | **`REFUTED_WITHDRAWN`** | `DesktopEditor.kt:47-53` already coalesces contiguous identical styles via `while (j < end && units[j].style == style) j++`. Tested in `probe_kf4_kf5_kf6_audit_gaps.py`: 50 contiguous bold units produce `spanStyles.size == 1`. |
| **`B-4`** | `Graphemes.stops` omits caret stops for empty paragraphs | **`REFUTED_WITHDRAWN`** | `BreakIterator.getCharacterInstance` emits a boundary after every `\n`. Tested in `probe_kf4_kf5_kf6_audit_gaps.py`: `Graphemes.stops("a\n\nb")` returns `[0, 1, 2, 3, 4]`, including offset `2` for the empty paragraph. |
| **`A-6`** | `WindowFiles.open()` replaces the current `EditorEngine` in place and orphans active composition | **`REFUTED_WITHDRAWN`** | `WindowFiles.kt:59` opens a new window via `showEditor(initial = doc, initialPath = chosen)` and never mutates the existing window's `EditorEngine`. |
| **`A-1` (OS Claim)** | Headless queue-overflow probe proved Windows `WInputMethod.endComposition()` drops callbacks | **`REFUTED_WITHDRAWN`** | Withdrawn: calling `engine.command()` 260 times in a headless unit test without delivering a synthetic `InputMethodEvent` only tests `pendingQueue` bounds, not Windows OS IMM32/TSF behavior. |
| **`C-1`** | Neutral/empty paragraphs inheriting preceding paragraph direction is a layout bug | **`DOCUMENTED_POLICY_PRESERVED`** | Intentional Stage 2 contract verified by `RtlHostChecks.kt:292-336` so pressing Enter or typing digits/punctuation after an Urdu/Arabic paragraph stays right-aligned. |
| **`A-5`** | All `keyPressed` events during composition should be dropped | **`DOCUMENTED_POLICY_PRESERVED`** | Preserved `VK_LEFT` navigation during composition (`HostChecks.kt:35`) while routing `Escape` to `requestEndComposition()` (and `abortAndRetireComposition()` when `waiting || settlementTimedOut`). |

---

## 5. Summary of Test Results (`batch-20261017-155258-12840`)

| Verification Step | Status | Duration | Coverage Summary |
| :--- | :--- | :--- | :--- |
| `build` | **PASS** (`exitCode: 0`) | `30.796s` | Clean isolated Maven compile and dependency copy |
| `kernel-files-formatting` | **PASS** (`exitCode: 0`) | `6.891s` | 16 kernel checks, 12 file checks (`.astra` v1/v2/v3), 17 formatting checks, 1,093 Unicode 16 grapheme fixtures |
| `stress` | **PASS** (`exitCode: 0`) | `1.344s` | 2,000 generated composition sessions vs `RefOverlayModel`, 12,000 randomized range-format commands, GB12/13 regional-indicator stress |
| `attached-host` | **PASS** (`exitCode: 0`) | `2.390s` | `HostChecks`, `RtlHostChecks` (19 sections), `JapaneseHostChecks` (9 sections) |
| `argon-regression-probes` | **PASS** (`exitCode: 0`) | `17.188s` | All 7 executable Argon regression probes in `reviews/argon-critical-path/experiments/` |
| `frozen v5` | **PASS** | — | All 45 frozen v5 specification files unchanged |

---

## 6. Remaining Gaps Between the Desktop Prototype and Production Readiness

1. **Flat Whole-Document Buffer vs. Hierarchical Rope/Piece-Tree Storage (`C-2`)**:
   - The prototype stores `DocumentState.units` as a flat `List<TextUnit>` and rebuilds `EditorState.text` and paragraph partitions across the document on edits. For 1k–20k character documents this takes `< 1 ms` per edit (`probe_c2_keystroke_scaling.py`), but production 100k+ character documents require the frozen v5 persistent tree/piece-table storage and incremental paragraph layout cache invalidation.
2. **Physical OS IME Qualification Across Platforms (`PLATFORM_TESTING_REQUIRED`)**:
   - Automated suites verify deterministic `EditorEngine` state transitions and attached AWT/Compose `InputMethodEvent` handling, including simulated synchronous, delayed, missing, and late/duplicate callbacks. Physical human/OS IME qualification (Windows TSF Microsoft Japanese IME candidate popups/reconversion, macOS NSTextInputClient, Android InputConnection, iOS UITextInput, Web EditContext) remains a manual/hardware qualification gate.
3. **Production Crash Recovery, Autosave, and Accessibility**:
   - The prototype provides explicit atomic `.astra` v3 Save/Open and close confirmation, but does not implement background journaled autosave, full document block hierarchy (lists, tables), or platform screen-reader accessibility trees.
