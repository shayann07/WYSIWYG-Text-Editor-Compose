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
- **Baseline Commit**: `75f5cb4` (follow-up audits against `0616a1e` and `1e42b2a`)
- **Implementation Commits**: `0e1d1e5`, `762485c`, `dfcf5ee`, `0616a1e`, `7a4e888`, `1e42b2a`, plus the host-replacement qualification commit that adds this revision
- **Isolated Verification Evidence**: `desktop-prototype/local-evidence/batch-20261017-190900-7776`

### 2.2 Reproducible Verification Commands
Run the complete isolated build and verification pipeline from the repository root:
```powershell
python desktop-prototype/verify.py
```
`verify.py` builds into an isolated directory under `desktop-prototype/local-evidence/batch-<timestamp>-<pid>`, compiles the sources via Maven, runs:
1. `editor.ChecksKt` (18 kernel checks, 12 file checks, 17 formatting checks, 1,093 Unicode 16 grapheme fixtures)
2. `editor.StressChecksKt` (2,000 generated composition sessions against `RefOverlayModel`, 12,000 randomized range-format operations, GB12/13 regional-indicator stress)
3. `editor.HostChecksKt` (attached AWT + Compose host checks, 19 `RtlHostChecks` sections, 12 `JapaneseHostChecks` sections including the 100x mounted host-replacement loop and fault injection)
4. All 8 executable Argon regression probes in `reviews/argon-critical-path/experiments/` against the isolated compiled classes
5. SHA-256 verification of all 45 frozen v5 specification files against `v5-freeze-manifest.json`

### 2.3 Verified Source Hash Manifest (SHA-256)

| File | SHA-256 |
| :--- | :--- |
| `desktop-prototype/pom.xml` | `36466c45290569fe5e541c4a14f577275395696a37e57658c8a78810802ad6e0` |
| `desktop-prototype/run.cmd` | `c68514768c7d36dcd53fe6f6134c9302190b841641f13a5cd7c3db72b81ffb06` |
| `desktop-prototype/verify.py` | `0fbcbc195a7a1fd1329fef823858e2aae2e3b299352d419efe41790bd75d40e1` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `0712bc134c86e4cea2f8305d01ff84b5f7a492a31207fe717e18b33d83222ad1` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `c44fcb64ca9fa2341a24482c221d18da0e81ddb01c815ad438f88688c3422bc5` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `39d6b97af698f5a96ad940aea02150953398a4dd6f9a1266f69e4088ccc75e25` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `b6a5b687c3a78d3f07c7a03e7e51cfd8c49132f0e072a869c323ee90b4a8920f` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `4d3142000a3759cfcfd58dbf8abc4ed6d61fa98c501022696faf3c16fd50cb69` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `d0d86cb6b8c85097385957afb088e8f0293b6524bb20a32a2ca6d1d0a93af8c5` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `210f422453a0df2400e235fc26175c198fc719dc636c02231863f83dece93eab` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/JapaneseHostChecks.kt` | `e4e80b0e81ffb94656ff859fe01d6b34b9dacbdc4ee3bc712b703a97987bd3a8` |
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

### 3.10 `A-1` / `A-2` / Lifecycle — Concrete AWT Host & `InputContext` Retirement/Replacement, Settlement Watchdog Deadline Preservation, and Close/Save Hardening
- **Requirement**: Prevent permanent editor wedging if a native IME fails to deliver a terminal callback after `endComposition()`, preserve the settlement deadline across non-terminal candidate updates while commands remain queued, isolate the document against both synchronous/reentrant and delayed ordinary AWT `InputMethodEvent` callbacks after abort/disposal without relying on synthetic epoch parameters, and protect unsaved active composition during window close and save-then-close flows.
- **Minimal Reproduction (`RecoveryAudit.java` against `0616a1e`, synthetic events on production path)**:
  1. **Ordinary delayed AWT callback after abort**:
     - `e.inputMethod("X", 0, 1); s.abortAndRetireComposition(); commit(s, "X")` (via `s.dispatchEvent(new InputMethodEvent(s, ...))`):
     - **Observed at `0616a1e`**: `ORDINARY_LATE_CALLBACK_AFTER_ABORT=X` (ordinary `inputMethodTextChanged` bypassed `dispatchInputMethodEventWithEpoch`).
     - **Repaired at `7a4e888`**: `ORDINARY_LATE_CALLBACK_AFTER_ABORT=` (`""`).
  2. **Reentrant commit during `abortAndRetireComposition`**:
     - `e2.inputMethod("Y", 0, 1); s2.setOnEndCompositionRequested(() -> commit(s2, "Y")); s2.abortAndRetireComposition()`:
     - **Observed at `0616a1e`**: `REENTRANT_COMMIT_DURING_ABORT=Y` (`requestEndComposition()` ran after `engine.abortComposition()` on an un-retired listener).
     - **Repaired at `7a4e888`**: `REENTRANT_COMMIT_DURING_ABORT=` (`""`).
  3. **Watchdog deadline cleared by non-terminal candidate update**:
     - `e3.inputMethod("a", 0, 1); e3.toggleFormat(FormatKey.BOLD); clock.set(1000); e3.inputMethod("ab", 0, 2); clock.set(10000); e3.checkSettlementWatchdog(2000)`:
     - **Observed at `0616a1e`**: `AFTER_CANDIDATE_UPDATE_TIMEOUT=false WAITING=true DEADLINE_START=null` (`inputMethod` unconditionally reset `settlementRequestedAt = null` even when `committedCount < text.length` and `pending.isNotEmpty()`).
     - **Repaired at `7a4e888`**: `AFTER_CANDIDATE_UPDATE_TIMEOUT=true WAITING=false DEADLINE_START=null`.
- **OpenJDK AWT / `InputContext` Contract Analysis & Repair**:
  - Bytecode inspection of `sun.awt.windows.WInputMethod.sendInputMethodEvent` and `java.awt.Component.processEvent` shows that native/AWT `InputMethodEvent`s carry no native epoch token—only `event.getSource()` (`Component`), and are posted to `EventQueue` targeting that `Component` directly (bypassing `InputContext.dispatchEvent`).
  - **Concrete Host & `InputContext` Retirement/Replacement (`DesktopEditor.kt`)**:
    - Each `EditorSurface` owns a dedicated `ownInputContext: InputContext? = InputContext.getInstance()` (`override fun getInputContext() = if (retired || disposed) null else ownInputContext`).
    - In `abortAndRetireComposition(replaceHost: Boolean)`:
      1. **Synchronization boundary BEFORE rollback or native notification**: sets `retired = true`, `activeImeEpoch++`, `ownInputContext = null`, and stops `blinkTimer` first.
      2. **Old context teardown**: invokes `onEndCompositionRequested()`, `oldContext?.endComposition()`, `oldContext?.removeNotify(this)`, `oldContext?.dispose()`, and `enableInputMethods(false)`. Any synchronous/reentrant `InputMethodEvent` (`INPUT_METHOD_TEXT_CHANGED` or `CARET_POSITION_CHANGED`) fired during `endComposition()` is immediately consumed and ignored by `EditorSurface`'s listeners because `retired == true`.
      3. **Replacement host & rollback**: constructs a fresh `replacementSurface = EditorSurface(engine, view, diagnostics, trace)` (when `replaceHost && !disposed`), runs `engine.abortComposition()` (preserving any accepted partial commits and their Undo history), swaps `this` for `replacementSurface` in the parent container (preserving `JLayeredPane` layer 100 and focus), and notifies `onHostReplaced`.
      4. **Disposal guards (`removeNotify()`)**: sets `disposed = true` and detaches/disposes `ownInputContext` before `super.removeNotify()`, cancelling any pending engine wait via `engine.cancelPendingOnTeardown()`, so callbacks during or after component/window disposal never mutate the document.
  - **Watchdog Deadline Preservation (`EditorEngine.kt`)**:
    - Removed the unconditional `settlementRequestedAt = null` at the start of `inputMethod`.
    - When `committedCount < text.length` and `pending.isNotEmpty()`, `inputMethod` preserves the existing `settlementRequestedAt` deadline (`if (settlementRequestedAt == null) settlementRequestedAt = now()`).
    - Terminal settlement (`committedCount == text.length` -> `finish(...)`) drains `pending` once and resets `settlementRequestedAt = null` and `settlementTimedOut = false`.
    - `checkSettlementWatchdog(timeoutMs)` fires once when `composition != null && pending.isNotEmpty() && (now() - req) >= timeoutMs`, clearing `pending` and `settlementRequestedAt = null` and setting `settlementTimedOut = true` so cancelled commands never execute if the composition later commits, while allowing a subsequent `perform(action)` to start a new deadline.
- **Executable Regressions**:
  - `Checks.kt` Checks 17–18 (candidate-update watchdog deadline preservation + ordinary AWT callback retirement, reentrant abort/settlement, stale caret, identical `"X"` in consecutive sessions on `replacementSurface`, partial-commit recovery `"abcd"` -> `"Xcd"`, and disposal guards).
  - `FileChecks.kt` Check 7 (`WindowFiles.close()` Discard with attached `EditorSurface` rejecting reentrant and post-dispose ordinary AWT commits).
  - `JapaneseHostChecks.kt` Sections 9–10 (ordinary AWT `dispatchEvent` across retired/replacement hosts in `JLayeredPane` layer 100, reentrant abort, partial-commit recovery, and candidate-update watchdog timeout + Escape recovery).
  - `reviews/argon-critical-path/experiments/probe_a1_settlement_wedge.py`.

### 3.11 Host Replacement Lifecycle & Failure-Path Qualification (follow-up to `1e42b2a`)

**Scope.** Establish what the dedicated `InputContext` + `EditorSurface` replacement introduced in `7a4e888` actually guarantees, and make failures explicit. No new editor features, storage, or pagination work.

**Gaps confirmed at `1e42b2a` (source inspection + new failing assertions before repair):**
1. `abortAndRetireComposition()` and `removeNotify()` wrapped `endComposition`, `removeNotify(Component)`, `dispose()` and `enableInputMethods(false)` in `runCatching`, discarded failures, and always reported a successful replacement.
2. A retired host still processed `FOCUS_GAINED`/`FOCUS_LOST` and `KEY_PRESSED`/`KEY_TYPED` through Swing's normal pipeline (only `inputMethodTextChanged` was guarded).
3. The selected input locale and composition-enabled state were not carried to the replacement host.
4. Replacement surfaces kept a reference chain to predecessors; callbacks were not cleared on retirement.
5. `EditorEngine.abortComposition()` returned early when `composition == null`, leaving `settlementRequestedAt`/`settlementTimedOut`/`pending` set.
6. No lifecycle counters existed, so timers/contexts/hosts could not be audited.

**Repairs:**
- `EditorEngine.abortComposition()` now clears settlement state and `pending` unconditionally.
- `DesktopEditor.kt`:
  - `RecoveryStatus` = `ACTIVE_INITIAL | RECOVERED_QUALIFIED | CONSERVATIVE_ABORT_ONLY | DEGRADED_RECOVERY_FAILED`, and `RecoveryReport` (`status`, `succeeded`, `failures`, `contextQualified`).
  - Every teardown and binding step runs through `runStep`/`recordStepFailure`; failures appear in the report and in diagnostics JSON (`activeImeEpoch`, `recoveryStatus`, `contextQualified`, `recoveryFailures`). A new context is **never** marked qualified, and recovery is never reported successful, if any teardown or binding step failed.
  - `engine.abortComposition()` always runs, so the document is clean and saveable in every failure case.
  - Conservative mode: `abortCompositionConservatively()` / `abortAndRetireComposition(replaceHost = false)` aborts the composition without claiming a qualified replacement.
  - `processFocusEvent`, `processKeyEvent` and `processInputMethodEvent` consume events on retired/disposed hosts.
  - `selectInputLocale(locale, compositionEnabled)` records preferences that each replacement reapplies through `InputContext.selectInputMethod`/`setCompositionEnabled`, where the public API permits it.
  - Removal during teardown (`removedDuringTeardown`), cleanup of unattached replacements (`cleanupFailedAttachment`), predecessor chain breaking, and `clearCallbacks()` on retirement.
  - `EditorSurface.LifecycleCounters` (hosts created/retired/disposed, timers started/stopped, InputContexts created/detached/disposed, recovery attempts by outcome, teardown failures) and `RecoveryHooks` for deterministic fault injection.
  - `@JvmOverloads` keeps the 4-argument constructor used by `RecoveryAudit.java` and `ProbeA1Runner`.

**Executable evidence (synthetic, attached AWT/Compose):**
- `JapaneseHostChecks` Section 11. On the mounted `showEditor` window: 100 host replacements using fixture text; exactly one `EditorSurface` at `JLayeredPane` layer 100 after each; old hosts reject text, caret, key and focus callbacks; the current host accepts repeated identical text; toolbar Bold/Italic/Undo/Redo/Copy/Paste, Save/Open, accessibility text/caret property events, diagnostics and engine observers all target the current host; partial commit with attributed Undo/Redo; ordinary close, discard-close and removal during replacement. Counter assertions: across the loop, exactly 100 hosts, timers and InputContexts were created, and exactly 100 were retired/stopped/detached/disposed; there were 100 successful recoveries with 0 degraded and 0 teardown failures, and the active host/timer/context counts stayed constant. After the close, discard and removal tests, and again after Section 12, the active counts return to baseline. These are lifecycle counters, **not** a heap-leak proof.
- `JapaneseHostChecks` Section 12. Injected failures in `endComposition`, `removeNotifyContext`, `disposeContext`, `disableInputMethods`, `createInputContext` (returning `null` and throwing), `createReplacement` and `attachReplacement`. In every case the outcome is `DEGRADED_RECOVERY_FAILED`, `contextQualified = false`, the failure is recorded in diagnostics, and the document is clean, saveable and still editable. The section also covers explicit conservative recovery.
- `probe_a1_settlement_wedge.py` Step 4: an injected teardown failure is reported as degraded and the document is still aborted cleanly.
- `RecoveryAudit.java` rerun against this build: `ORDINARY_LATE_CALLBACK_AFTER_ABORT=` (empty), `REENTRANT_COMMIT_DURING_ABORT=` (empty), `AFTER_CANDIDATE_UPDATE_TIMEOUT=true WAITING=false DEADLINE_START=null`.

**Platform guarantees and what synthetic tests prove:**

| Layer | Behavior | Basis |
| :--- | :--- | :--- |
| Public `java.awt.im.InputContext` | `getInstance()` returns a context not shared with other components; `removeNotify(Component)` discards pending input from input methods for that component; `dispose()` releases resources | Javadoc (public contract) |
| Public `java.awt.Component` | `enableInputMethods(false)` disables input-method event delivery to the component | Javadoc |
| OpenJDK `sun.awt.im.InputContext` | `removeNotify` → `deactivateInputMethod(false)`, `inputMethod.removeNotify()`, `currentClientComponent = null`; `dispose()` throws `IllegalStateException` while still active | `javap -c -p` inspection (implementation detail, not a contract) |
| OpenJDK `sun.awt.windows.WInputMethod` | `removeNotify` → `endCompositionNative(context, false)` (cancel on the old HIMC); `sendInputMethodEvent` returns without posting when `getClientComponent() == null`; events posted earlier carry `source = old component` | `javap -c -p` inspection (implementation detail) |
| Not guaranteed | No public contract says that a native `WM_IME_*` message already queued before `removeNotify` cannot be delivered afterward, or how TSF routes messages during a live focus handoff on the shared top-level HWND | Gap |

Synthetic events built as `new InputMethodEvent(oldSurface, ...)` set `source = oldSurface`. They therefore prove only the application's old-source guard, not native IMM32/TSF routing.

**Separate verdicts:**

| Verdict | Result |
| :--- | :--- |
| Application-path isolation | **PASS** |
| Lifecycle / failure-path tests | **PASS** |
| Real native qualification (Windows TSF context replacement) | **NOT RUN** |

**Real native smoke test: NOT RUN.** Driving Microsoft Japanese IME romaji composition and its candidate window needs foreground focus on the user's interactive desktop, so it was not run unattended. English remains the default input language. Manual procedure:
1. `python desktop-prototype/verify.py`, then launch `editor.MainKt --ime-check --ime-language=ja-JP` from the produced isolated classpath.
2. Select Japanese (Microsoft IME, hiragana mode). Type `nihongo` to get provisional underlined text, press Space to show the candidate window, and confirm it appears at the caret.
3. While composing, press Escape twice to wait-timeout/abort (or trigger the diagnostics "Recover input" action). Confirm the provisional text is removed and diagnostics show `RECOVERED_QUALIFIED`, `contextQualified=true`, and a new `activeImeEpoch`.
4. Immediately type `nihongo` + Space + Enter again. Confirm one committed string on the new host, no duplicate of the aborted text, and a single Undo group (Undo, then Redo).
5. Repeat steps 3–4 with an Urdu keyboard (`ur-PK`), then Arabic (`ar-SA`). Confirm the keyboard selection persists across replacement.
6. Switch focus to another window mid-composition and back. Record whether the IME commits or cancels; do not assume either.
7. Record OS build, JDK, IME version and diagnostics JSON in ignored `local-evidence/`. Commit only sanitized fixture results.

### 3.12 Degraded Recovery Path Admission Control, Resource Tracking & Honest Cleanup

**Scope.** Close the recovery failure path identified by `DegradedAudit.java` where a host in `DEGRADED_RECOVERY_FAILED` status still admitted ambiguous text callbacks into the committed document, separate application setup from native qualification, enforce admission control, track/clean unresolved resources honestly without counter manipulation, and add permanent regressions.

**Root cause identified by `DegradedAudit.java`:**
When replacement host teardown failed, `recoveryStatus` was marked `DEGRADED_RECOVERY_FAILED`. However, `EditorSurface.inputMethodTextChanged` only checked `if (retired || disposed) return;`, ignoring `recoveryStatus`. As a result, subsequent callbacks on the degraded replacement host were forwarded to `engine.inputMethod()`, admitting ambiguous text into the committed document.

**Repairs:**
1. **Explicit Status & Qualification Separation**:
   - `setupSucceeded`: whether initial InputContext binding and component setup succeeded.
   - `contextQualified`: whether the context passed qualification checks.
   - `nativeQualified`: fixed to `false` for synthetic/unit test runs (real native qualification remains NOT RUN).
   - `admissionAllowed`: strictly enforced gate for document admission. Set to `true` on initial healthy setup or after explicit recovery; set to `false` whenever recovery fails or enters degraded status.
2. **Admission Control & Quarantined Buffering**:
   - When `!admissionAllowed`, `inputMethodTextChanged` buffers incoming callbacks into `quarantinedCallbacks: CopyOnWriteArrayList<QuarantinedCallback>` and consumes the event immediately without calling `engine.inputMethod()`.
   - Mutating keyboard actions (`VK_ENTER`, `VK_TAB`, `VK_BACK_SPACE`, `VK_DELETE`, `Ctrl+V` paste, `Ctrl+X` cut, `Ctrl+B`, `Ctrl+I`) are blocked when `!admissionAllowed`, while non-mutating operations (Save, Open, Copy, Select All, Undo, Redo, Navigation, and Escape) remain active.
   - The committed document is completely protected from corruption or ambiguity while degraded.
3. **Pre-Image Retention & UI Recovery Actions**:
   - `retainedPreimage`: retains the uncommitted draft text, selection, marked range, and committed text baseline from before abort.
   - `EditorSurface` provides `recoverExplicitly(admitQuarantined: Boolean)`, `retryCleanup(): Boolean`, `discardQuarantinedCallbacks()`, and `retryRecovery(): EditorSurface?`.
   - The desktop prototype UI (`showEditor`) renders a degraded warning banner and dynamic "Recover input" button when `!admissionAllowed`.
4. **Honest Resource Tracking without Counter Manipulation**:
   - `unresolvedContext` tracks any `InputContext` whose teardown failed (`removeNotifyContext` or `disposeContext`).
   - If `removeNotifyContext` fails, `disposeContext` is gated and NOT run, preventing native context corruption and keeping the resource tracked in `unresolvedContext`.
   - `retryCleanup()` attempts to finish teardown of `unresolvedContext` honestly.
   - Removed fake manual test counter manipulation (`LifecycleCounters.*.incrementAndGet()`) from `JapaneseHostChecks.kt` failure tests, ensuring tests reflect genuine component behavior.
5. **Permanent Regressions**:
   - `JapaneseHostChecks.kt` Test 1: ordinary callback to degraded replacement quarantined, mutating keystrokes (Enter, Backspace, Delete, Tab) blocked, and explicit recovery restores input.
   - `JapaneseHostChecks.kt` Test 2a & 2b: genuine failure in `removeNotifyContext` before removal and after removal, verifying honest `unresolvedContext` retention and `retryCleanup()` resolution.
   - `JapaneseHostChecks.kt` Test 3a, 3b, 3c: genuine failure in `disposeContext` before disposal and after disposal, plus failure in `disableInputMethods`.
   - `JapaneseHostChecks.kt` Test 8: repeated failure, retry, and explicit recovery.
   - `JapaneseHostChecks.kt` Test 9: partial commit under degraded recovery preserving accepted prefix and exact Undo/Redo.
   - `JapaneseHostChecks.kt` Test 10: discard-close and disposal on degraded host.
   - `DegradedAudit.java` verified: `STATUS=DEGRADED_RECOVERY_FAILED QUALIFIED=false`, `AFTER_ORDINARY_CALLBACK_TO_DEGRADED_HOST=` (empty string).

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

## 5. Summary of Test Results (`batch-20261017-235141-27376`)

| Verification Step | Status | Duration | Coverage Summary |
| :--- | :--- | :--- | :--- |
| `build` | **PASS** (`exitCode: 0`) | `38.485s` | Clean isolated Maven compile and dependency copy |
| `kernel-files-formatting` | **PASS** (`exitCode: 0`) | `7.156s` | 18 kernel checks, 12 file checks (`.astra` v1/v2/v3), 17 formatting checks, 1,093 Unicode 16 grapheme fixtures |
| `stress` | **PASS** (`exitCode: 0`) | `1.891s` | 2,000 generated composition sessions vs `RefOverlayModel`, 12,000 randomized range-format commands, GB12/13 regional-indicator stress |
| `attached-host` | **PASS** (`exitCode: 0`) | `3.937s` | `HostChecks`, `RtlHostChecks` (19 sections), `JapaneseHostChecks` (12 sections: `hostReplacementLifecycle100x` PASS, `hostReplacementFailurePaths` PASS) |
| `argon-regression-probes` | **PASS** (`exitCode: 0`) | `20.484s` | All 8 executable Argon regression probes, including `probe_a1` Step 4 (degraded recovery) |
| `frozen v5` | **PASS** | — | All 45 frozen v5 specification files unchanged |
| `RecoveryAudit.java` / `DegradedAudit.java` (rerun) | **PASS** | — | Late ordinary callbacks quarantined and rejected; admission control and mutating key locks verified; watchdog fires after candidate updates |

---

## 6. Remaining Gaps Between the Desktop Prototype and Production Readiness

1. **Flat Whole-Document Buffer vs. Hierarchical Rope/Piece-Tree Storage (`C-2`)**:
   - The prototype stores `DocumentState.units` as a flat `List<TextUnit>` and rebuilds `EditorState.text` and paragraph partitions across the document on edits. For 1k–20k character documents this takes `< 1 ms` per edit (`probe_c2_keystroke_scaling.py`), but production 100k+ character documents require the frozen v5 persistent tree/piece-table storage and incremental paragraph layout cache invalidation.
2. **Physical OS IME Qualification Across Platforms (`PLATFORM_TESTING_REQUIRED`)**:
   - Automated suites verify deterministic `EditorEngine` state transitions and attached AWT/Compose `InputMethodEvent` handling, including simulated synchronous, delayed, missing, and late/duplicate callbacks. Physical human/OS IME qualification (Windows TSF Microsoft Japanese IME candidate popups/reconversion, macOS NSTextInputClient, Android InputConnection, iOS UITextInput, Web EditContext) remains a manual/hardware qualification gate.
3. **Production Crash Recovery, Autosave, and Accessibility**:
   - The prototype provides explicit atomic `.astra` v3 Save/Open and close confirmation, but does not implement background journaled autosave, full document block hierarchy (lists, tables), or platform screen-reader accessibility trees.
