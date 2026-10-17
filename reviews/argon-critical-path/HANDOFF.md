# Critical-Path Implementation Handoff & Action Plan

This document specifies the exact implementation tasks for engineering teams, organized strictly by priority and dependency order.

---

## 1. Fixes Required Before the Next Implementation Milestone

### Task 1: Fix Composition Overlay Retained-Segment Splicing (`A-3`, Invariant `I08`) & Paragraph Break Style Contamination (`A-4`)
- **Severity**: Critical (Silent Data Loss on Partial Commit + Abort; Ghost Style Resurrection).
- **Affected Files**: [`desktop-prototype/src/main/kotlin/editor/EditorEngine.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt).
- **Required Changes**:
  1. **Segment-Mapped Overlay**: Replace the flat `[session.start, session.end)` span in `Composition` with the 1–3 segment partition specified in [`plan-v5.md:264-282`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L264-L282) and [`candidate-b-coordinator.mjs:1578-1682`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/candidate-b-coordinator.mjs#L1578-L1682):
     - `Segment(dcStart, dcEnd, units, origin: Retained | Replacement)`.
     - When `committedCount > 0`, only splice `dc` across the segments actually consumed by the first `committedCount` units. Retain the unconsumed prefix/suffix segments in the overlay.
     - In `abortComposition()`, discard the provisional replacement core, but preserve accepted partial commits and restore unconsumed retained segments to $D_c$.
  2. **Unstyled Structural Breaks**:
     - In `EditorEngine.units(text, breaks, style)` ([`EditorEngine.kt:53`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L53)), ensure `PARAGRAPH_BREAK` units always carry `TextStyleFlags()` (`bold = false, italic = false`).
     - Remove style mutation on `PARAGRAPH_BREAK` units from `emptyParagraphStyleIndex` ([`EditorEngine.kt:55-61`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L55-L61)), `apply(Format)` ([`EditorEngine.kt:138-140`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L138-L140)), and `finish()` ([`EditorEngine.kt:223-224`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/EditorEngine.kt#L223-L224)). Store empty paragraph intent in block metadata or `dc.startStyle`.
- **Acceptance Tests**:
  - Run [`probe_a3_partial_commit_data_loss.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_a3_partial_commit_data_loss.py):
    - `Selection(0, 4)` on `"abcd"` + `inputMethod("Xcd", 1, 2)` + `abortComposition()` must produce `"Xcd"`.
    - `Selection(0, 4)` on `"abcd"` + `inputMethod("abX", 1, 2)` + `abortComposition()` must produce `"abcd"`.
    - `Enter` with bold active must create a `PARAGRAPH_BREAK` unit with `bold = false`.
- **Dependencies**: None.

---

### Task 2: Fix Paragraph Boundary Ownership (`KF-2`, `KF-3`) & Split Line/Bidi Affinity (`B-1`, `B-2`)
- **Severity**: High (Missing Caret/Highlight across Paragraph Breaks; Inverted Line Affinity).
- **Affected Files**: [`desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt).
- **Required Changes**:
  1. **Strict Paragraph Boundary Ownership**:
     - In [`paragraphForOffset(documentOffset)`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L145-L160), remove the cross-paragraph jump `if (leadingAffinity && clamped == p.endOffset && idx + 1 < paragraphs.size) return paragraphs[idx + 1]`. Offset `p.endOffset` belongs strictly to paragraph `idx`.
  2. **Render Trailing Newline Selection Strip**:
     - In [`DocumentCanvas`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L305-L318), whenever a non-collapsed selection satisfies `s.start <= p.endOffset && s.end > p.endOffset` on a paragraph with `hasBreak = true`, draw a trailing newline highlight rectangle of width `6.dp` at the trailing visual edge of the paragraph's last line.
  3. **Explicit Affinity Types**:
     - Introduce `enum class LineAffinity { UPSTREAM, DOWNSTREAM }` and `enum class BidiAffinity { PRIMARY, SECONDARY }` per [`plan-v5.md:80-95`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md#L80-L95).
     - In `caret(offset, lineAffinity, bidiAffinity)`, when `lineAffinity == UPSTREAM` and `offset` is a soft-wrap boundary, render the caret at the trailing edge of line $L$ instead of the leading edge of line $L+1$.
     - In `lineEdge(start = false)` (`VK_END`), set `LineAffinity.UPSTREAM` to reach the end of the current visual line on CJK/unspaced text.
- **Acceptance Tests**:
  - Run [`probe_kf2_kf3_break_geometry.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_kf2_kf3_break_geometry.py): `DRAWS_HIGHLIGHT` must be `true` for selections spanning `\n`, and `rawDiff` must never clamp negative offsets.
  - Run [`probe_b1_soft_wrap_end_key.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_b1_soft_wrap_end_key.py): `MISSING_LAST_CHAR_ON_LINE_0` must be `false`.
- **Dependencies**: Task 1.

---

### Task 3: Fix UAX #9 Directional Isolate Scanning (`KF-1`) & Remove Preceding-Paragraph Direction Cascade (`C-1`)
- **Severity**: High (Bidi Paragraph Inversion; Nonlocal Cache Invalidation).
- **Affected Files**: [`desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt).
- **Required Changes**:
  1. **UAX #9 BD9/P2/P3 Isolate Tracking**:
     - In [`paragraphBaseDirection(units, fallback)`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L69-L84), track `isolateDepth`:
       - Increment on `\u2066` (`LRI`), `\u2067` (`RLI`), `\u2068` (`FSI`).
       - Decrement on `\u2069` (`PDI`) when `isolateDepth > 0`.
       - Ignore strong directional characters while `isolateDepth > 0` or embedding depth `> 0`.
  2. **Isolate Neutral Paragraphs from Preceding Paragraphs**:
     - In [`measureParagraphs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L199-L202,L226,L234-L235), remove `fallback = resolvedDir` chaining across paragraphs.
     - When a paragraph contains no strong directional characters (P3), default its base direction to the document's default `LayoutDirection` (e.g. `ResolvedTextDirection.Ltr` or document locale), ensuring changes in Paragraph 0 never invalidate downstream paragraphs.
- **Acceptance Tests**:
  - Run [`probe_kf1_isolate_bidi.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_kf1_isolate_bidi.py): `CASE1_MATCH` and `CASE2_MATCH` must both be `true`.
  - Run [`probe_c1_direction_cascade.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_c1_direction_cascade.py): `CASCADE_INVALIDATED_DOWNSTREAM_PARAS` must be `false`.
- **Dependencies**: None.

---

### Task 4: Bounded Settlement State Machine & IME Event Routing (`A-1`, `A-2`, `C-1`)
- **Severity**: Critical (Permanent Editor Wedge; Premature Composition Abort).
- **Affected Files**: [`desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt).
- **Required Changes**:
  1. **Bounded Settlement State Machine**:
     - Replace the unbounded waiting flag with an explicit lifecycle: `IDLE -> COMPOSING -> SETTLING(epochId) -> IDLE`.
     - When user triggers a barrier action (Escape, mouse click away), allow `Escape` to call `engine.abortComposition()` directly without waiting for an OS event.
  2. **`VK_ESCAPE` Handling**:
     - In [`DesktopEditor.kt:387`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L387), call `engine.abortComposition()` before or in conjunction with `inputContext?.endComposition()` so uncommitted preedit is cancelled rather than committed.
  3. **Guard `keyPressed` During Composition**:
     - Add `if (published.marked != null) return` to [`keyPressed`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L408-L431) for navigation and editing keys, preventing raw AWT key events from queuing duplicate mutations onto `pending` while the IME owns preedit.
  4. **Normalize `getTextLocation`**:
     - Normalize `TextHitInfo.beforeOffset(0)` to the same start boundary as `TextHitInfo.leading(0)` and anchor non-collapsed selections on the last covered unit `(s.end - 1).coerceAtLeast(s.start)`.
- **Acceptance Tests**:
  - Run [`probe_a1_settlement_wedge.py`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/reviews/argon-critical-path/experiments/probe_a1_settlement_wedge.py): Editor must not become wedged; pending queue must clear on settlement/abort.
- **Dependencies**: Task 1.

---

## 2. Compatible Improvements That Can Wait

1. **Coalesce Contiguous `SpanStyle` Runs (`C-3`)**:
   - In [`paragraphAttributedDisplay`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt#L86-L92), coalesce adjacent characters with identical `TextStyleFlags` into single `SpanStyle` ranges before passing them to Compose's `AnnotatedString`.
2. **Decouple Layout Measurement from Draw Pass (`KF-6`)**:
   - Move `measureParagraphs` out of the Compose `Canvas` draw lambda into a dedicated `LayoutService` keyed on live `(width, height, density, documentRevision, scroll)`.
3. **Sticky `preferredX` in Vertical Navigation (`B-4`)**:
   - Store `preferredX: Float?` on vertical caret movement and navigate by visual line indices `(paragraphIndex, lineIndex) ± 1`.
4. **Accessible Grapheme Stepping (`B-5`)**:
   - Use `Graphemes.stops(text)` for `AccessibleText.CHARACTER` in `AccessibleEditor`.
5. **Port Measured B+ Tree Storage (`C-2`)**:
   - Port the 2-component measured B+ tree (`contentUtf16Length`, `leafBlockCount`) from [`document-model.mjs`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/codex/run-20261016-20/document-model.mjs) into Kotlin to support documents larger than 20,000 characters.

---

## 3. Native-Platform Experiments Still Required

1. **macOS `NSTextInputClient` Live Verification**:
   - Verify whether `NSTextInputContext.discardMarkedText()` dispatches synchronously or posts asynchronously on macOS AWT / Compose Multiplatform.
2. **Android `InputConnection` Bridge Prototyping**:
   - Prototype `PlatformTextInputMethodRequest` handling of `setComposingRegion` across paragraph breaks and `beginBatchEdit()`/`endBatchEdit()` transaction grouping.
3. **iOS `UITextInput` Bridge Prototyping**:
   - Verify UIKit delegate re-entrancy rules: ensure `selectionDidChange` and `textDidChange` are suppressed during system-originated callbacks (`setMarkedText`, `insertText`).
4. **Web / ProseMirror Bridge Verification**:
   - Validate browser `compositionend` event order relative to `beforeinput(insertFromComposition)` in Chromium, Safari, and Firefox.

---

## 4. Proposed Specification Amendments

**No normative changes or amendments to [`plan-v5.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v5.md) are required.**
The specification already accurately defines the required architectural contracts. For clarity in future multiplatform implementations, a non-normative note should be added to §4.5 and §5.2 clarifying that:
- Structural `PARAGRAPH_BREAK` units are strictly unstyled (`TextStyleFlags()` empty).
- Neutral `AUTO` paragraphs resolve against the document's locale `LayoutDirection`, not preceding paragraph content.
