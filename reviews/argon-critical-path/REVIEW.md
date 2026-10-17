# Independent Critical-Path Architectural Investigation Review (`HEAD = 773bc06`)

## 1. Executive Verdict: What Would Force a Rebuild, and What Must We Do Now?

### Would anything force a rebuild of the normative v5 specification ([`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md))?
**No.** The normative v5 specification ([`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md)) and its formal validation model ([`validation/codex/run-20261016-20/document-model.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/document-model.mjs), [`candidate-b-coordinator.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/candidate-b-coordinator.mjs)) are sound. Prior claims that [`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md) has "underspecified areas" requiring a rewrite were based on fabricated invariant numbers and misread text. Specifically:
- [`plan-v5.md:202-255`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L202-L255) already specifies hierarchical block storage with a measured B+ tree (`fanout = 32`, `1,024–4,096` UTF-16 slabs).
- [`plan-v5.md:80-101`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L80-L101) already formally separates `LineAffinity { Upstream, Downstream }`, `BidiAffinity { Primary, Secondary }`, `TypingSide { Left, Right }`, and `Association { Before, After }`.
- [`plan-v5.md:260-303`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L260-L303) already defines the segment-mapped provisional composition overlay $\mathcal{O}$ (`Segment(dcStart, dcEnd, units, origin)` with `Retained` vs `Replacement` origins) that preserves unconsumed retained document ranges during partial commits and `abortProvisional`.
- [`plan-v5.md:676-700`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L676-L700) already specifies independent per-paragraph `ParagraphCacheKey` caching.

### What **will** force a ground-up rebuild of the Kotlin codebase if not fixed now?
The 946-line Kotlin desktop prototype ([`EditorEngine.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt) + [`DesktopEditor.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt)) took **five architectural shortcuts** that diverge directly from [`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md). If production features (e.g., rich blocks, tables, lists) or multiplatform adapters (Android, iOS, Web) are built on top of these shortcuts, they will force a complete rewrite of the editor core:

1. **Flat `List<TextUnit>` + Uncached `Document.text` + Whole-Document Scans on Every Keystroke (Rebuild Risk: CRITICAL)**:
   - [`Document.text`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L16) is a getter joining all units into a newly allocated `String` on every read. It executes **6+ times per keystroke** across `apply`, `visible`, `DocumentCanvas`, and `statusChanged`.
   - On every keystroke, [`EditorEngine.apply(Replace)`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L129-L131) runs `Graphemes.snap(dc.text, ...)` (`BreakIterator`) over the entire document string.
   - On every arrow key, [`EditorSurface.stops(g)`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L485-L493) performs $O(N \cdot P)$ iterations scanning all graphemes and all paragraphs.
   - **Remedy**: Port the two-level block + measured B+ tree from [`document-model.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/document-model.mjs) into Kotlin; cache per-block text and `contentVersion`; bound `Graphemes.snap` to the affected paragraph; and cache `ParagraphLayout` per `ParagraphCacheKey`.

2. **Unsegmented Composition Overlay Destroys Retained Text on Partial Commit + Abort (Rebuild Risk: CRITICAL)**:
   - [`EditorEngine.inputMethod`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L190-L214) tracks a single `[session.start, session.end)` range instead of [`plan-v5.md:264`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L264)'s `Segment` list.
   - When `committedCount > 0`, `dc = dc.splice(session.start, session.end, replacement.take(committedCount))` immediately splices `dc` over the **entire** selection footprint, deleting uncommitted retained suffixes.
   - Calling `abortComposition()` afterwards leaves only the committed prefix, **permanently deleting uncommitted retained text from the document** (verified: selecting `"abcd"` + `inputMethod("Xcd", 1, 2)` + `abortComposition()` leaves `"X"`, permanently erasing `"cd"`).
   - In addition, pressing `Enter` with Bold active stamps `bold=true` onto `UnitKind.PARAGRAPH_BREAK`, violating [`plan-v5.md:335`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L335).
   - **Remedy**: Implement the 1–3 segment partition (`Retained` prefix, `Replacement` core, `Retained` suffix) from [`plan-v5.md:272-282`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L272-L282) / [`candidate-b-coordinator.mjs:1578-1682`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/candidate-b-coordinator.mjs#L1578-L1682), and enforce unstyled `PARAGRAPH_BREAK` units.

3. **Structural Paragraph Breaks Have Zero Visual Footprint & Conflated Coordinate Queries (Rebuild Risk: HIGH)**:
   - [`partitionParagraphs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L94-L107) strips `\n` at index `i` (`endOffset = i`), leaving `i` outside every `ParagraphSpan`. Selecting `[i, i+1)` draws **neither selection highlight nor caret** ([`DesktopEditor.kt:305-318, 348`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L305-L318)).
   - [`ParagraphGeometry.caret`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L151-L163) and [`paragraphForOffset`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L122-L133) conflate paragraph membership, soft-wrap `LineAffinity`, and `BidiAffinity` into a single boolean that jumps across the `\n` gap when `leadingAffinity = true` (`toLocalOffset(p.endOffset)` clamps `-1` to `0`).
   - **Remedy**: Keep paragraph boundary ownership strictly within `[startOffset, endOffset]`; render a trailing newline highlight strip for selections covering paragraph breaks; and split geometry parameters into `LineAffinity` and `BidiAffinity`.

4. **Preceding-Paragraph Direction Inheritance Cascade & Isolate-Blind UAX #9 Scan (Rebuild Risk: HIGH)**:
   - [`measureParagraphs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L199-L202) chains `fallback = resolvedDir` across paragraphs. Editing Paragraph 0 from LTR to RTL flips the base direction of all downstream neutral paragraphs (`"123..."`, empty lines), invalidating $O(P)$ cache keys on a single character edit.
   - [`paragraphBaseDirection`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L69-L84) ignores UAX #9 directional isolate scopes (`\u2066..\u2069`), disagreeing with JDK `Bidi`.
   - **Remedy**: Implement explicit `TextDirectionPolicy` per paragraph (defaulting neutral `AUTO` paragraphs to document locale direction, not preceding paragraph direction) and make `paragraphBaseDirection` skip isolate scopes per UAX #9 BD9/P2/P3.

5. **Unbounded Wait on Platform IME Settlement Callbacks (Rebuild Risk: HIGH)**:
   - [`DesktopEditor.kt:371`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L371) calls `inputContext?.endComposition()` and waits indefinitely for an OS `InputMethodEvent`. On Windows AWT (`WInputMethod`), `removeNotify()` or no-op notifications drop callbacks without posting events.
   - While `composition != null`, `keyTyped` is ignored and `engine.perform` fills `pending` up to 256 items, permanently locking the editor.
   - **Remedy**: Implement a bounded settlement state machine with synchronous fallback (`settleCompositionInPlace()` / `abortComposition()`).

---

## 2. Triage of Known Findings (`KF-1` through `KF-6`) at `HEAD = 773bc06`

All 6 Known Findings were tested and verified against `HEAD = 773bc068cd87cd83d1289bdb5e46e8ab6552869d`.

| ID | Location | Verified Root Cause & Minimal Counterexample | Severity | Smallest Viable Fix |
|---|---|---|---|---|
| **KF-1** | [`DesktopEditor.kt:69-84`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L69-L84) | `paragraphBaseDirection` iterates `Character.codePointAt` linearly and exits on the first strong character without tracking isolate depth (`\u2066..\u2068` vs `\u2069`) or explicit embedding depth.<br>**Repro** (`probe_kf1_isolate_bidi.py`):<br>`"\u2067\u0627\u2069A"` returns `Rtl` (JDK `Bidi` baseLevel = 0 LTR).<br>`"\u2066A\u2069\u0627"` returns `Ltr` (JDK `Bidi` baseLevel = 1 RTL). | High (Bidi Layout) | Track `isolateDepth` in `paragraphBaseDirection`; ignore strong characters while `isolateDepth > 0` (or delegate to `java.text.Bidi` when non-neutral). |
| **KF-2** | [`DesktopEditor.kt:94-107, 305-318, 348`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L94-L107) | `partitionParagraphs` excludes `\n` from `ParagraphSpan` (`endOffset = i`), so offset range `[i, i+1)` belongs to no span. For `Selection(2, 3)` on `"ab\ncd"`, both `selStart < selEnd` checks evaluate to false (`2..2` and `3..3`). Because `!s.collapsed`, the caret is also hidden.<br>**Repro** (`probe_kf2_kf3_break_geometry.py`): Caret visible: `false`, highlight drawn: `false`. | High (Rendering & UX) | In `DocumentCanvas`, when `s.start <= p.endOffset && s.end > p.endOffset` (for spans with `hasBreak`), draw a trailing newline selection highlight strip of width `~6.dp`. |
| **KF-3** | [`DesktopEditor.kt:128, 145-160`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L128) | `paragraphForOffset` contains: `if (leadingAffinity && clamped == p.endOffset && idx + 1 < paragraphs.size) return paragraphs[idx + 1]`. On `"ab\ncd"` at `offset = 2`, it returns `paragraphs[1]` (startOffset = 3), where `toLocalOffset(2)` computes `(2 - 3).coerceIn(0, 2) = 0`, placing caret at the start of paragraph 1.<br>**Repro** (`probe_kf2_kf3_break_geometry.py`): `rawDiff = -1`, `clampedLocal = 0`. | High (Geometry) | Remove cross-paragraph jump from `paragraphForOffset`. Offset `p.endOffset` belongs strictly to paragraph `idx`. Soft-wrap affinity applies only within a paragraph. |
| **KF-4** | [`DesktopEditor.kt:197-212, RtlHostChecks.kt:474-493`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L197-L212) | `referenceMeasure` is a 3-line wrapper around `measureParagraphs` reusing production `partitionParagraphs`, `paragraphBaseDirection`, and the same warm `rememberTextMeasurer()`. It verifies determinism, not correctness against an independent oracle. | Medium (Verification Integrity) | Use `java.text.Bidi` oracle in test and construct a fresh `TextMeasurer(cacheSize = 0)` to measure cold latency. |
| **KF-5** | [`RtlHostChecks.kt:446-454`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt#L446-L454) | Section 16 scrolls `view.scroll = 50f` and calls `getTextLocation(TextHitInfo.leading(0))`, but never begins composition (`marked == null`). In `DesktopEditor.kt:541-547`, `getTextLocation` ignores `TextHitInfo` and queries `caret(s.focus)`. | Medium (Test Gap) | Start active composition (`e.inputMethod("مرحبا", 0, 5)`) in Section 16 before querying `getTextLocation(leading(2))` under scroll. |
| **KF-6** | [`DesktopEditor.kt:275, 455-458`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L275) | `EditorSurface.geometry()` cache check keys only on `revision` and `scroll`, ignoring component width, height, and display scale. Fallback `view.measure` closure closes over old `size.width` from the previous draw pass. | High (Geometry & Resize) | Include `(width, height, density)` in cache check and decouple `measure` from draw pass into a host-callable layout service. |

---

## 3. Deep Investigation of Architectural Risks A, B, and C

### Risk Area A: Native Input Feasibility & Settlement

#### Finding A-1 (Critical): Unbounded Composition Lock / Permanent Editor Wedge
- **Mechanism**: When `DesktopEditor` requests end of composition via `inputContext?.endComposition()` (on `focusLost`, `VK_ESCAPE`, or mouse press), OpenJDK's `WInputMethod` does not guarantee an `InputMethodEvent`. If `removeNotify()` was called or Win32 `ImmNotifyIME` is a no-op, no event is ever posted.
- **Impact**: `EditorEngine.composition` remains non-null with `waiting = true`. `keyTyped` ignores all user input; toolbar commands and typing actions queue into `pending` up to 256 items, after which all edits are rejected with `"Finish composing before another command."`. Save/Close are blocked. The editor is wedged forever.
- **Probe Evidence** (`probe_a1_settlement_wedge.py`):
  ```text
  COMPOSITION_ACTIVE=true
  WAITING_STATUS=true
  FINAL_NOTICE=Finish composing before another command.
  EDITOR_PERMANENTLY_WEDGED=true
  ```

#### Finding A-2 (High): `VK_ESCAPE` Commits Instead of Cancelling on Windows AWT
- **Mechanism**: In [`DesktopEditor.kt:387`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L387), `VK_ESCAPE` calls `inputContext?.endComposition()`. In OpenJDK 23 `WInputMethod.endComposition()`, `this.haveActiveClient()` evaluates to `true` (because `EditorSurface` implements `InputMethodRequests`), passing `CPS_COMPLETE` (`commit = true`) to Win32 `ImmNotifyIME`.
- **Violation**: Violates [`plan-v5.md:269`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L269) (`I07`), which requires `Escape` to discard uncommitted preedit text and revert to the pre-composition baseline.
- **Remedy**: On `VK_ESCAPE`, explicitly invoke `engine.abortComposition()`.

#### Finding A-3 (Critical): Partial IME Commit Destroys Retained Text on Abort
- **Mechanism**: In [`EditorEngine.kt:199-203`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L199-L203), when `committedCount > 0`, `dc = dc.splice(session.start, session.end, replacement.take(committedCount))` immediately splices `dc` over the entire `[session.start, session.end)` range.
- **Silent Data Loss**: If the user selected `"abcd"` (`0..4`), the IME committed `"X"` while holding `"cd"` in preedit (`inputMethod("Xcd", 1, 2)`), and composition is subsequently aborted (`abortComposition()`), `dc` has already been overwritten with `"X"`. The uncommitted retained suffix `"cd"` is permanently erased.
- **Probe Evidence** (`probe_a3_partial_commit_data_loss.py`):
  ```text
  CASE1_PRE_ABORT_VISIBLE=Xcd
  CASE1_PRE_ABORT_COMMITTED=X
  CASE1_POST_ABORT_VISIBLE=X
  CASE1_BUG_DATA_LOSS=true
  CASE2_POST_ABORT_VISIBLE=a
  CASE2_BUG_DATA_LOSS=true
  ```
- **Remedy**: Implement the segment-mapped overlay $\mathcal{O}$ (`Segment(dcStart, dcEnd, units, origin)`) per [`plan-v5.md:264-282`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L264-L282) so partial commits only splice segments actually consumed by the committed count.

#### Finding A-4 (Critical): `UnitKind.PARAGRAPH_BREAK` Style Contamination
- **Mechanism**: [`EditorEngine.units()`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L53) stamps `effectiveTyping()` onto all units, including `PARAGRAPH_BREAK`. Pressing Enter while Bold is active creates a newline unit with `bold=true`. Furthermore, [`emptyParagraphStyleIndex`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L55-L61) stores empty paragraph attributes on the preceding paragraph break unit.
- **Violation**: Violates [`plan-v5.md:335`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L335) ("A retained structural BoundaryUnit never receives an inline attribute patch"). When downstream text is deleted, empty paragraphs resurrect leftover styles from previous editing sessions.
- **Probe Evidence** (`probe_a3_partial_commit_data_loss.py`):
  ```text
  CASE3_UNIT_1_CHAR=10_KIND=PARAGRAPH_BREAK_BOLD=true
  CASE3_BREAK_CORRUPTED_WITH_BOLD=true
  ```

---

### Risk Area B: Coordinates, Bidi & Visual Editing

#### Finding B-1 (Critical): Soft-Wrap Boundary Offset Assigned Exclusively to Next Line
- **Mechanism**: Compose/Skia `getLineForOffset(wrapOffset)` maps the boundary offset to line $L+1$. [`stops(g)`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L485-L493) maps each offset to at most one stop on `getLineForOffset(offset)`. Line $L$ has no stop at `wrapOffset`.
- **Impact**: Pressing `End` (`VK_END`) on line $L$ places the caret at `wrapOffset - 1`. On unspaced or CJK lines, this stops **before the last visible character of line $L$**.
- **Probe Evidence** (`probe_b1_soft_wrap_end_key.py`):
  ```text
  LINE_0_VISIBLE_CHARS=51 (indices 0..50)
  LINE_0_WRAP_BOUNDARY_OFFSET=51
  PROTOTYPE_LINE_0_MAX_STOP_OFFSET=50
  MISSING_LAST_CHAR_ON_LINE_0=True
  DEFECT_CONFIRMED=True
  ```
- **Remedy**: Introduce `WrapAffinity { UPSTREAM, DOWNSTREAM }` per [`plan-v5.md:80-95`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L80-L95).

#### Finding B-2 (High): `getTextLocation` Conflates `isLeadingEdge` with Bidi `primary` Flag
- **Mechanism**: [`DesktopEditor.kt:546-547`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L546-L547) passes `offset?.isLeadingEdge` directly into `caret(at, primary)`.
- **Impact**: `TextHitInfo.leading(k)` and `TextHitInfo.afterOffset(k - 1)` represent the same insertion offset, but receive inverted bidi affinity flags, returning different X coordinates at bidi run boundaries.

#### Finding B-3 (High): `getTextLocation(null)` Anchors on Next Paragraph
- **Mechanism**: When selection spans `0..3` on `"ab\n"`, `s.end == 3` is paragraph 1. [`DesktopEditor.kt:549-558`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L549-L558) queries `paragraphForOffset(s.end)`, returning empty paragraph 1's geometry ($y = 215$) instead of paragraph 0's line ($y = 185$).

---

### Risk Area C: Transition from Prototype to Scalable Editor

#### Finding C-1 (Critical): Preceding-Paragraph Direction Inheritance Cascade
- **Mechanism**: [`measureParagraphs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L199-L202) chains `fallback = resolvedDir` across all paragraphs.
- **Impact**: Editing Paragraph 0 from LTR to RTL flips the base direction of all downstream neutral paragraphs (`"123..."`, `""`), invalidating their `ParagraphCacheKey` entries and forcing $O(P)$ full-document re-measurement on a 1-character edit.
- **Probe Evidence** (`probe_c1_direction_cascade.py`):
  ```text
  BEFORE_EDIT_PARA_0_DIR=Ltr, PARA_1_DIR=Ltr, PARA_5_DIR=Ltr
  AFTER_EDIT_PARA_0_DIR=Rtl, PARA_1_DIR=Rtl, PARA_5_DIR=Rtl
  CASCADE_INVALIDATED_DOWNSTREAM_PARAS=true
  ```
- **Remedy**: Store explicit `TextDirectionPolicy` per paragraph, defaulting neutral `AUTO` paragraphs to the document default `LayoutDirection`.

#### Finding C-2 (Critical): $O(N)$ Per-Keystroke String Allocations and $O(N \cdot P)$ Navigation
- **Mechanism**: [`Document.text`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L16) builds a new full-document `String` on every read (6+ times per edit). `Graphemes.snap` runs over the whole document string. `stops(g)` performs $O(N \cdot P)$ iterations per arrow key.
- **Probe Evidence** (`probe_c2_keystroke_scaling.py`):
  ```text
  DOC_20K_ARROW_KEY_STOPS_INNER_LOOPS=5,000,000
  O_N_SPLICING_AND_STRING_CONSTRUCTION=CONFIRMED
  ```
- **Remedy**: Two-level block tree + measured B+ tree; cache `Document.text`; scope grapheme stops to active paragraph.

#### Finding C-3 (High): Uncoalesced `SpanStyle` Runs
- **Mechanism**: [`paragraphAttributedDisplay`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L86-L92) emits individual 1-character `SpanStyle` entries for every styled character.
- **Impact**: In a 5,000-character styled paragraph, 5,000 separate `SpanStyle` spans are pushed to Compose and Skia `ParagraphBuilder`, inflating run-segmentation and shaper costs.
- **Remedy**: Coalesce contiguous runs of identical styles into single ranges.

---

## 4. Adversarial Audit of Prior Reports: Errors & Refutations

Our investigation audited prior AI reports and identified critical errors and spec-violating recommendations that must be rejected:

1. **Systematic Fabrication of Invariant IDs and Spec Citations**:
   - A prior report cited `I01`, `I02`, `I07`, `I09`, `I10`, `I12`, `I15`, `U3`, `R8` at fabricated line numbers `269–277, 363–376, 466–515, 616, 675–677` in `plan-v5.md`.
   - The actual invariant catalog in [`plan-v5.md:168-187`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L168-L187) contains completely different invariants (`Single Owner Reducer`, `Atomic Snapshot Publication`, `Partial-Commit Prefix Freeze`, `No Whole-Document Flatten/Serialize`, etc.).
   - [`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md) is **not** underspecified; the Kotlin prototype simply failed to implement its normative sections.

2. **Refutation of Sub-Paragraph Layout Chunking (`~2,048–4,096 chars`)**:
   - A prior report proposed splitting giant paragraphs into sub-paragraph layout chunks of 2,048–4,096 characters.
   - This proposal is **explicitly prohibited by [`plan-v5.md:615, 684, 815`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L684)** (*"Storage slabs are internal to a paragraph's piece tree and never split shaping or bidi resolution"*; *"Do not split paragraphs at storage slabs to bound shaping"*).
   - Splitting paragraphs forces artificial hard line breaks, breaks UAX #9 bracket pairs (`BD16`), and breaks HarfBuzz contextual shaping. Bounded shaping must be achieved through viewport virtualization and style run coalescing, not arbitrary chunking.

3. **Refutation of `invokeLater` / Timer Auto-Commit on `endComposition()`**:
   - A prior report recommended scheduling an `invokeLater` fallback to auto-commit marked text if no native event arrives.
   - This proposal is **explicitly prohibited by [`plan-v5.md:811, 899`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L811)** (*"Do not mutate composing text on a timeout"*; *"No timer or frame deadline may fabricate commit, abort, or reorder queued commands"*).
   - On Windows AWT, `WInputMethod.endComposition()` posts events asynchronously via `WToolkit.postEvent`. A synchronous `invokeLater` runs ahead of the posted event, causing **double-commit data corruption** (`"k"` committed by `invokeLater`, followed by `"k"` committed again by native event).

---

## 5. Summary of Runnable Probes

All probes are located under [`reviews/argon-critical-path/experiments/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/) and can be executed via `python run_all_probes.py`:
- `probe_kf1_isolate_bidi.py`: UAX #9 isolate bidi resolution.
- `probe_kf2_kf3_break_geometry.py`: Break-only selection highlight and boundary jump.
- `probe_a1_settlement_wedge.py`: Unbounded composition lock and editor wedge.
- `probe_a3_partial_commit_data_loss.py`: Silent data loss on partial commit abort and newline style contamination.
- `probe_b1_soft_wrap_end_key.py`: Soft-wrap boundary offset truncation on `VK_END`.
- `probe_c1_direction_cascade.py`: Direction cascade across downstream neutral paragraphs.
- `probe_c2_keystroke_scaling.py`: $O(N)$ string allocations and $O(N \cdot P)$ navigation loops.
