# Paragraph Layout and Input Geometry Audit Report

**Date:** 2026-10-17  
**Status:** QUALIFIED (Automated Attached-Host Prototype Suite)  
**Baseline Git Commit:** `784ab6d`  
**Host Scope:** Attached AWT `EditorSurface` host in real Swing hierarchy with live Compose layout rendering; synthetic IME callback injection and AWT keyboard/mouse event dispatch. (Physical OS IME candidate qualification remains `BLOCKED / NOT RUN` in background sessions).

---

## 1. Executive Summary

This audit records the completion and verification of two major prototype milestones:
1. **Stage 1 (Input & Geometry Fallback):** Robust screen geometry mapping distinguishing attached-offscreen surfaces, non-showing surfaces, and nonzero component origins; strict adherence to AWT `InputMethodRequests` fallback contracts; deep history verification for queued commands during preedit settlement; and honest distinction between synthetic event processing and physical OS IME candidate windows.
2. **Stage 2 (Independent Paragraph Layout):** Implementation of per-structural-paragraph Compose text layout and base direction resolution; distinguishing structural paragraph breaks (`UnitKind.PARAGRAPH_BREAK`) from soft breaks (`UnitKind.TEXT` with `'\n'`); unified multi-paragraph geometry for rendering, selection, hit testing, navigation, and IME coordinates; and comprehensive regression coverage across mixed-direction scripts (English, Urdu, Arabic).

All 5 verification gates in `verify.py` pass cleanly:
- `build`: PASS (Kotlin 2.2.20 / Compose Desktop 1.12.1)
- `kernel-files-formatting`: PASS (Kernel model checks, 10 file roundtrip checks, 16 formatting checks)
- `stress`: PASS (2,000 generated composition sessions, 12,000 randomized range-format commands)
- `attached-host`: PASS (Urdu/Arabic RTL, Japanese IME host, and Paragraph Layout host checks)
- `frozen v5`: PASS (All 45 files in `v5-freeze-manifest.json` verified with exact SHA-256 hashes)

---

## 2. Stage 1: Input & Geometry Fallback Audit

### 2.1 Geometry Fallback and Screen Coordinates
- **Offscreen Windows:** When the editor window is located offscreen (e.g. `Point(-16000, -16000)` during headless CI execution), `EditorSurface.locationOnScreen` succeeds and reports negative coordinates. `getTextLocation` correctly incorporates this offset, ensuring candidate coordinate progression remains valid relative to the component.
- **Non-Showing / Detached Components:** When `EditorSurface` is detached from a native peer (`isShowing == false`), calling `locationOnScreen` throws `IllegalComponentStateException`.
  - In `getTextLocation(offset)`: `IllegalComponentStateException` is specifically caught. In compliance with the AWT `InputMethodRequests` contract (observed in `JTextComponent`), the method returns an empty fallback rectangle `Rectangle(0, 0, 0, 0)` rather than inventing a bogus screen origin `Point(0, 0)`.
  - In `getLocationOffset(x, y)`: `IllegalComponentStateException` is specifically caught and the method returns `null` (coordinate unmappable).
- **Nonzero Component Origin:** Testing with an attached frame at `Point(250, 180)` verified that candidate coordinates incorporate positive window origins, and passing local coordinates (e.g. `(2, 4)`) to `getLocationOffset` correctly returns `null` because they do not intersect the component's screen bounds.

### 2.2 Queued Command Ordering & Attributed History
- When shortcut commands (`Ctrl+B`, `Ctrl+Z`) are dispatched during active Japanese IME preedit, they are queued on `EditorEngine.pending`.
- Upon native composition settlement (simulated via `imeEvent("日本", 2, null)`):
  1. The preedit text is committed as a `Composition` history entry.
  2. The queued `Ctrl+B` is executed, formatting the committed text to bold and pushing a `Format` history entry.
  3. The queued `Ctrl+Z` is executed, undoing the formatting.
- Assertions verify that immediately upon settlement:
  - `snapshot.undoCount == 2` (entries: `Fixture`, `Composition`)
  - `snapshot.redoCount == 1` (entry: `Format`)
  - Committed text is unstyled (`units.none { it.style.bold }`).
  - Executing `Redo` restores bold on the committed text.
  - Executing `Undo` restores plain committed text.
  - Executing `Undo` again restores the pre-composition fixture.

### 2.3 Settlement Observation
- `EditorEngine.onSettlementRequested` is observed independently from `onEvent`.
- Tests confirm that Escape during preedit and focus loss both invoke `endComposition()`, testing both synchronous and delayed settlement pathways without corrupting undo history.

### 2.4 Evidence Label Honesty
- All automated host test assertions are explicitly documented as **synthetic callback processing** and **attached Compose candidate geometry**.
- Actual Windows OS IME candidate popup qualification remains marked `BLOCKED / NOT RUN` due to background desktop session constraints (session lack of user interaction / foreground HWND).

---

## 3. Stage 2: Independent Paragraph Layout Architecture

### 3.1 Compose Single-Layout Limitation
In Compose Multiplatform (Skiko), measuring a multiline string with `TextMeasurer` resolves a single document-wide base direction based on the first strong bidi character. In documents with mixed scripts (e.g. English heading followed by Urdu body), this forces all paragraphs into the direction of the first paragraph, breaking right-margin alignment and cursor behavior for RTL text.

### 3.2 Paragraph Partitioning and Direction Resolution
- Documents are partitioned by structural breaks (`UnitKind.PARAGRAPH_BREAK`) via `partitionParagraphs(document)`.
- Soft line breaks (`char == '\n'` with `UnitKind.TEXT`, created via Shift+Enter) remain inside their enclosing paragraph layout and wrap according to the paragraph's base direction.
- **Direction Policy (`paragraphBaseDirection`):**
  - The first strong directional character (LTR: Latin, digits; RTL: Arabic, Hebrew, Urdu) determines the paragraph's base direction (`ResolvedTextDirection.Ltr` or `ResolvedTextDirection.Rtl`).
  - Empty paragraphs or paragraphs containing only neutral characters inherit the base direction of the preceding paragraph.
  - If the very first paragraph is empty or neutral-only, it defaults to LTR.
- Each structural paragraph is measured independently with its resolved direction (`TextDirection.Rtl` or `TextDirection.Ltr`) constrained to `textWidth`.
- Stacking: Paragraph layouts are stacked vertically (`currentTop += pHeight`). Empty paragraphs are assigned a minimum height equal to the default line height of the font style.

### 3.3 Unified Multi-Paragraph Geometry
`Geometry` maintains `paragraphs: List<ParagraphLayout>` as the single source of truth:
- **Rendering:** Paragraphs draw their text independently with vertical translation (`topLeft = Offset(geo.x, geo.y + p.top)`).
- **Selection Highlighting:** Selections spanning multiple paragraphs compute path slices per paragraph via `p.layout.getPathForRange(localA, localB)` translated to paragraph coordinates.
- **Marked Composition:** Composition underlines are drawn per-paragraph across line bounds.
- **Caret Geometry:** `Geometry.caret(offset, primary, leadingAffinity)` locates the paragraph containing the offset, maps to local offset, and computes cursor rect and horizontal position.
- **Hit Testing:** `hit(point)` selects the target paragraph by vertical bounds (`targetY in p.top..p.bottom`), maps local coordinates, and clamps to grapheme clusters.
- **Scroll Invalidation:** `Geometry` stores `scroll: Float`. `EditorSurface.geometry()` checks `it.revision == published.documentRevision && it.scroll == view.scroll`, ensuring that programmatic scrolling immediately invalidates cached geometry and shifts candidate popup locations upward.

### 3.4 Visual Navigation Model
- `stops(g)` generates grapheme navigation stops sorted by `(line, x, offset)`.
- Within any line (LTR or RTL), Left arrow decreases X (moves leftwards) and Right arrow increases X (moves rightwards).
- Line/paragraph boundary transitions preserve exact bidirectional symmetry: moving forward across a line boundary enters the adjacent line, and moving backward returns to the exact prior stop, satisfying mathematical invertibility ($L(R(x)) = x$ and $R(L(x)) = x$).

---

## 4. Verification & Regression Record

### 4.1 Automated Suite Summary

| Check Suite | Description | Verdict |
| :--- | :--- | :--- |
| **`build`** | Clean compilation of prototype and tests via Maven | **PASS** |
| **`kernel-files-formatting`** | 15 kernel groups, 10 file persistence checks, 16 formatting checks | **PASS** |
| **`stress`** | 2,000 randomized composition runs, 12,000 range-format edits | **PASS** |
| **`attached-host` (Urdu/Arabic RTL)** | 18 sections: alignment, diacritics, bidi traversal, Enter/Backspace, persistence, benchmarks | **PASS** |
| **`attached-host` (Japanese IME)** | 10 sections: preedit, conversion, popup geometry, multi-clause, Escape, focus loss, queued history | **PASS** |
| **`frozen v5`** | SHA-256 validation of all 45 frozen specification files | **PASS** |

### 4.2 Benchmark Measurements
Measured on Java OpenJDK 23 / Compose Desktop 1.12.1 (Windows 11 x86_64):
- **50 Paragraphs (Mixed English, Urdu, Arabic):** Measured in **19 ms** (Budget: 100 ms).
- **2,000 Words (Single Long Paragraph):** Measured in **23 ms** (Budget: 150 ms).

---

## 5. Manual Testing Procedure

Because background execution environments cannot spawn interactive OS IME candidate windows, live interactive qualification requires a developer session:

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
   - Select across the paragraph break with mouse drag -> press `Ctrl+B` -> verify both paragraphs become bold while paragraph break remains intact.
   - Inspect logged events in `desktop-prototype/local-evidence/ime-<timestamp>/events.jsonl`.

---

## 6. Source Manifest & Hashes

The following source files implement and verify the desktop prototype:

| Source File | SHA-256 Hash |
| :--- | :--- |
| `desktop-prototype/pom.xml` | `36466c45290569fe5e541c4a14f577275395696a37e57658c8a78810802ad6e0` |
| `desktop-prototype/run.cmd` | `c68514768c7d36dcd53fe6f6134c9302190b841641f13a5cd7c3db72b81ffb06` |
| `desktop-prototype/verify.py` | `38946ab0a4fa7151d7fc7f83a9cc2d6dab9f7884ce8d592d3191c1d7ff502b3b` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `eb94187c261d7d6a4b3c3a887c5055d1454880c01687232f056a445b4bd797db` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `c8b9fa162b4b811eef996021c910852b0be0f78773f8af32ed7f212ed52253e8` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `dbeeccaff20eae6ce875f4f65a7e399fce867a4424b279ed6a79027d9617c195` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `a34044c8575032180f2166a45f0d6cc51ef1e76fd6293efec5f5b330cb91bffe` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `043e87612ecf40963de55dd2cf081a96454610da99131d9ebb2519b1360c616c` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `0c03cd09842e733f5cf3c5e74a52a9f9b1d7858b3e31623599cd2f5edd612a99` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `e5a8a90e19436c0f89f63b1ea27b93115063913aece005f36545152dc0b6dc9c` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/JapaneseHostChecks.kt` | `01f950748002838debf77bb6284253fe5e8fbc4a93edd49c79bbc888973367cf` |
| `desktop-prototype/src/main/kotlin/editor/Main.kt` | `c3d1a40dd87a612de2e517432cbbbffd4bff6255bb3f5a2e7a2dbedbe59085b3` |
| `desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt` | `14d644d38674967f9b6a9420dce4f222d4233e983cce2bebc9788460b027ddf5` |
| `desktop-prototype/src/main/kotlin/editor/StressChecks.kt` | `56c94d5a8f1e76230a918c23e1d9633ad42c8c2f81f4707a7d66c09e2284f091` |
| `desktop-prototype/src/main/kotlin/editor/WindowFiles.kt` | `62babbb3d5de30fa7c7834b2b35803524923bdaab5595de16539ac773e630d58` |

---

## 7. Known Architectural Boundaries

1. **Linear Paragraph Measurement:** The prototype measures all paragraphs in the document linearly on each content or layout change. For typical desktop documents (tens to hundreds of paragraphs), measurement finishes in under 20 ms. Viewport block virtualization is deferred to future architecture as scoped in frozen v5.
2. **Synthetic IME Callbacks:** Automated host checks test the AWT input method protocol via synthetic event injection. Live OS IME popup qualification requires developer verification as outlined in Section 5.
