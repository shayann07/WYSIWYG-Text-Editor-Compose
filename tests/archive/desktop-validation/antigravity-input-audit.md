# Astra Compose Desktop Prototype — Input Validation Audit Report

**Date:** 16 October 2026  
**Auditor:** Antigravity (Advanced Agentic Coding)  
**Milestone:** Desktop rich-text prototype input validation and hardening  
**Specification Baseline:** Frozen v5.0.0 (`editor-v5-standalone.md`, `plan-v5.md`, `v5-freeze-manifest.json`)  
**Base Commit:** `db6c0e9` ("Add attributed bold and italic editing with persistence and regression coverage")  

---

## 1. Executive Summary and Recommendation

The input-validation milestone for the Compose Desktop prototype has been audited, strengthened, repaired, and rigorously verified.

Key audit and verification results:
1. **Runner Audit:** `python desktop-prototype/verify.py` compiles into an isolated execution directory (`desktop-prototype/local-evidence/batch-<timestamp>-<pid>/build/`), executes deterministic test suites with bounded output, traps failures, verifies all 45 frozen v5 manifest hashes, captures full source hashes (including build and runner scripts), and produces reproducible JSON results.
2. **Headless & Model Checks:** All 41 test groups (15 kernel, 10 file, 16 formatting), 2,000 generated composition sessions, 12,000 randomized range-format operations, and 1,093 official Unicode 16 grapheme cluster fixtures pass without failure.
3. **Attached AWT + Compose Host Checks (Expanded & Hardened):**
   - **Japanese IME Host Lifecycle (`JapaneseHostChecks.kt`):** Full attached-host qualification covering provisional composition and candidate conversion (romaji `"nihon"` -> Hiragana `"にほん"` -> Kanji `"日本"`), candidate popup placement coordinates (`getTextLocation`) confirming monotonic X progression and uniform baseline Y across clauses, hit-test coordinate mapping (`getLocationOffset`), single-group composition Undo/Redo, multi-clause traversal and partial commit (`"わたしのなまえ"` -> `"私の名前"`), Escape cancellation without spurious history, focus-loss settlement, selection replacement with exact range restoration on Undo, queued formatting (`Ctrl+B`) and queued Undo (`Ctrl+Z`) during active preedit with safe settlement, and paragraph-boundary composition.
   - **Urdu/Arabic RTL Host Coverage (`RtlHostChecks.kt`):** Page margin alignment (`> 0.9 * maxWidth`), visual selection collapse (`VK_LEFT` to visual left, `VK_RIGHT` to visual right), directional selection extension (`Shift+Left` outward, `Shift+Right` inward), atomic deletion and Undo restoration for both **Backspace** and **Forward Delete** across single combining marks, multi-mark clusters (Shaddah U+0651 + Fatha U+064E in `"سَّلام"`), and medial joining letters (`"کتاب"` -> `"کاب"`), Eastern digits and Arabic punctuation, multi-line wrapping and vertical navigation (`VK_DOWN`/`VK_UP`), cross-paragraph navigation and merge, live RTL paragraph creation via Enter with subsequent LTR typing and merge, bidirectional mouse drag selection (forward right-to-left, backward left-to-right), attributed bold/italic styling, `.astra` format persistence roundtrip, and reversible edge traversal across 107 mixed-script caret positions.
4. **Windows Native Input Observations:**
   - **Urdu Keyboard:** Native keystrokes confirmed in `local-evidence/ime-1792146266448` (`VK_A` -> `'م'`, `VK_S` -> `'و'`, producing visible text `"مو"`).
   - **Arabic Keyboard:** Native keystrokes confirmed in `local-evidence/ime-1792146266448` (`VK_A` -> `'ش'`).
   - **Japanese Microsoft IME:** Registered cleanly in Windows and JDK (`ja-JP` accepted, `isCompositionEnabled = true`). However, physical candidate popup interaction in an interactive session is reported as **BLOCKED / NOT RUN** for real OS IME qualification: the test runner executes in an isolated Win32 desktop (`exebox-...`, Foreground HWND: 0) while user activity occupies the console `Default` desktop, and simulated keystrokes (`Robot` / `SendInput`) are explicitly excluded from OS IME qualification.
5. **Concrete Defects Repaired:**
   - **DEFECT-01 (Fixed):** Short RTL paragraphs left-aligned due to zero `minWidth` constraint. Fixed by setting `minWidth = textWidth, maxWidth = textWidth`.
   - **DEFECT-02 (Fixed):** Selection collapse on RTL text inverted visual direction due to logical endpoint sorting. Fixed by sorting visual stops and choosing visual endpoints.
   - **DEFECT-03 (Fixed):** `getLocationOffset` called `component.locationOnScreen` without `runCatching`, throwing `IllegalComponentStateException` when called offscreen or on unmapped components. Fixed by wrapping with `runCatching { locationOnScreen }.getOrDefault(Point())`.
   - **DEFECT-04 (Documented Architectural Limitation):** Single `TextLayoutResult` in Compose Desktop (Skiko) determines base paragraph direction globally from the document's first strong character. Independent paragraph base direction requires block virtualization in future milestones.

**Recommendation:** The input milestone is **READY TO ADVANCE** within its explicitly documented prototype boundaries. The next justified implementation step is **document hierarchy and block virtualization** per `editor-v5-standalone.md` §2.1.

---

## 2. Tested Revision and Source Hash Manifest

Tested against `main` on commit `db6c0e9` with local uncommitted validation and input-handling changes.

### 2.1 Frozen v5 Manifest Verification
All 45 frozen files specified in `v5-freeze-manifest.json` were SHA-256 hashed and verified identical to the specification freeze.

### 2.2 Prototype Source Hashes (SHA-256)
| File | SHA-256 Hash |
|---|---|
| `desktop-prototype/pom.xml` | `36466c45290569fe5e541c4a14f577275395696a37e57658c8a78810802ad6e0` |
| `desktop-prototype/run.cmd` | `c68514768c7d36dcd53fe6f6134c9302190b841641f13a5cd7c3db72b81ffb06` |
| `desktop-prototype/verify.py` | `c36a3a0cad1ce38a90a09c8d55a35d37ec7d6b3303e22e4b106edcfe981f8c1a` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `eb94187c261d7d6a4b3c3a887c5055d1454880c01687232f056a445b4bd797db` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `79c16e20b7555d181a4ffecb6f558f08bd1753aeaf89a2af96efd6cf69034200` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `dbeeccaff20eae6ce875f4f65a7e399fce867a4424b279ed6a79027d9617c195` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `a34044c8575032180f2166a45f0d6cc51ef1e76fd6293efec5f5b330cb91bffe` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `043e87612ecf40963de55dd2cf081a96454610da99131d9ebb2519b1360c616c` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `0c03cd09842e733f5cf3c5e74a52a9f9b1d7858b3e31623599cd2f5edd612a99` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `672a4df4c32d16401172921040f719bc3176e20785ce3595a59dd5d302cdb0d1` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/JapaneseHostChecks.kt` | `c055742d4ee77542ecaad92c84fc4f1ff0f602b385c09686cddcf198d9c4235d` |
| `desktop-prototype/src/main/kotlin/editor/Main.kt` | `d13726eb1f5b43f5b37f31b19c61bf712ba5335d687ed538ba2cc17c28ec3ca3` |
| `desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt` | `969332c1ccd26ec61edd52fd64ad19b2b8f4404df64cb12229a4b72212437915` |
| `desktop-prototype/src/main/kotlin/editor/StressChecks.kt` | `56c94d5a8f1e76230a918c23e1d9633ad42c8c2f81f4707a7d66c09e2284f091` |
| `desktop-prototype/src/main/kotlin/editor/WindowFiles.kt` | `62babbb3d5de30fa7c7834b2b35803524923bdaab5595de16539ac773e630d58` |

---

## 3. Environment & Test Commands

### 3.1 Operating Environment
- **OS:** Windows 11 Home / Pro (x86_64)
- **Java:** OpenJDK 23.0.2 (build 23.0.2+7)
- **Maven:** Apache Maven 3.9.9
- **Kotlin:** 2.2.20 (with `kotlin-compose-compiler-plugin-embeddable:2.2.20`)
- **Compose Desktop:** 1.12.1
- **Skiko:** 0.150.1 (Windows x64 runtime)
- **Windows User Languages Installed:** `en-PK`, `en-US`, `ja`, `ur-PK`, `ar-SA`
- **JDK Available Native Locales (`WInputMethodDescriptor`):** `[en_PK, en_US, ja_JP, ur_PK, ar_SA]`
- **Desktop Session:** `exebox-UVRTYVPPSOL2ILNWVXXT2HRTFV` (Foreground HWND: 0, UserInteractive: true)

### 3.2 Reproducible Test Instructions
To execute the authoritative deterministic test batch:
```powershell
python desktop-prototype/verify.py
```
This command performs:
1. Fresh Maven compilation and dependency copy into `desktop-prototype/local-evidence/batch-<timestamp>-<pid>/build/`.
2. Execution of `editor.MainKt --check` (15 kernel, 10 file, 16 format groups).
3. Execution of `editor.MainKt --stress` (2,000 composition sessions, 12,000 format commands).
4. Execution of `editor.MainKt --host-check --host-result=<path>` (attached AWT + Compose layout + RTL + Japanese IME suite).
5. SHA-256 verification of all 45 frozen v5 specification files.
6. Generation of `results.json` and execution logs in the isolated batch directory.

---

## 4. Test Results by Scope

### Group A: Headless / Model Verification
*Deterministic pure-JVM execution without native window attachment.*

| Test Group | Executed Checks | Result |
|---|---|---|
| **Kernel Checks** (`Checks.kt`) | 15 groups: 1,000 deterministic edits/Undo, 1,093 Unicode 16 grapheme cluster fixtures, surrogate pairs, atomic code-point deletion, word boundary deletion, undo coalescing limits. | **PASS** |
| **File Checks** (`FileChecks.kt`) | 10 groups: 65,536 UTF-16 code units roundtrip, hard/soft break preservation, single-byte corruption rejection, SHA-256 tamper resistance, conflict detection on concurrent writes. | **PASS** |
| **Format Checks** (`FormatChecks.kt`) | 16 groups: bold/italic toggles, typing intent clearing on relocation, directional replacement style inheritance, grapheme cluster presentation preserving constituent raw attributes, queued formatting/Undo settlement. | **PASS** |
| **Stress Checks** (`StressChecks.kt`) | 2,000 generated composition sessions + 12,000 randomized range-format operations compared against independent reference model. | **PASS** |

### Group B: Attached AWT + Compose Host Verification
*Real Swing JFrame with ComposePanel, Skiko rendering, real text measurement, and AWT event dispatching.*

| Check Area | Verification Detail | Result |
|---|---|---|
| **AWT Surface Hosting** | Surface hosted at index/layer 100 in `JLayeredPane`; mouse hit testing routes to `EditorSurface`. | **PASS** |
| **Synthetic IME Protocol** | Synthetic `InputMethodEvent`s tested: composed text replacement, partial commitment, caret positioning, surrogate caret offsets, rejection of out-of-range carets, clean Undo restoration. | **PASS** |
| **Committed Context Queries** | 15 slice queries through `InputMethodRequests.getCommittedText()` verified against document content. | **PASS** |
| **Japanese Candidate Conversion** | Romaji `"nihon"` -> Hiragana `"にほん"` -> candidate conversion to Kanji `"日本"`, single-group Undo/Redo. | **PASS** |
| **Japanese Popup Placement** | `getTextLocation` verified across candidate offsets: monotonic X progression (`loc0.x < loc1.x < locEnd.x`), identical baseline Y, positive height, 0 caret width. | **PASS** |
| **Japanese Hit-Test Offset** | `getLocationOffset` resolves points inside candidate glyphs to leading text offsets; returns null outside candidate bounds without throwing. | **PASS** |
| **Japanese Multi-Clause Traversal** | `"わたしのなまえ"` -> `"私の名前"`, caret navigation between clauses via `CARET_POSITION_CHANGED`, partial commit of `"私の"`, final commit of `"名前"` into a single Undo group. | **PASS** |
| **Japanese Cancellation** | Escape key (`VK_ESCAPE`) triggers `endComposition`; empty IME terminal clears preedit without modifying committed text or creating spurious history. | **PASS** |
| **Japanese Focus Loss Settlement** | Focus lost (`FOCUS_LOST`) triggers `endComposition`; native commit settles candidate text into committed buffer with single-step Undo. | **PASS** |
| **Japanese Selection Replacement** | Preedit replaces active selection range; Undo restores original text and original selection boundaries. | **PASS** |
| **Japanese Queued Formatting & Undo** | `Ctrl+B` (Bold) and `Ctrl+Z` (Undo) triggered during active composition set waiting flag; actions settle safely upon native commit. | **PASS** |
| **Japanese Paragraph Boundary** | Composition immediately before newline `\n`; cross-paragraph selection replacement with exact newline restoration on Undo. | **PASS** |
| **RTL Margin Alignment** | RTL paragraphs starting with strong RTL characters (`"اردو میں لکھیں"`, `"العربية جميلة"`) measure start position at `> 0.9 * maxWidth` (right margin). | **PASS** |
| **Visual Selection Collapse** | Non-collapsed RTL selection collapses to visual left edge on `VK_LEFT` (offset `text.length`) and visual right edge on `VK_RIGHT` (offset `0`). | **PASS** |
| **Directional Selection** | `Shift+Left` extends outward toward visual left; `Shift+Right` extends inward toward visual right. | **PASS** |
| **Combining Clusters (Backspace & Delete)** | Multi-mark sequence `"سَّلام"` (Base + Shaddah U+0651 + Fatha U+064E) deleted as an atomic cluster via both Backspace and Forward Delete (`VK_DELETE`); restored via Undo. | **PASS** |
| **Medial Joining Deletion (Backspace & Delete)** | Deletion of medial joining letter in `"کتاب"` produces `"کاب"` for both Backspace and Forward Delete; Undo restores `"کتاب"`. | **PASS** |
| **Eastern Digits & Punctuation** | Extended Arabic-Indic digits (`۱۲۳`), Arabic-Indic digits (`١٢٣`), and Arabic punctuation (`،`, `؛`, `؟`) traverse legal grapheme stops. | **PASS** |
| **Multi-Line RTL Wrapping** | Long RTL paragraph (180+ characters) wraps across multiple lines (`lineCount >= 2`); `VK_DOWN` moves to line 1 and `VK_UP` returns to line 0 start. | **PASS** |
| **Multi-Paragraph Transitions** | Cross-paragraph vertical arrow movement (`VK_DOWN` / `VK_UP`) between lines; Backspace at second paragraph boundary merges paragraphs; Undo restores separation. | **PASS** |
| **Live RTL Paragraph Creation** | Pressing Enter creates RTL paragraph; typing English creates second LTR paragraph; Backspace merges and Undo restores. | **PASS** |
| **Mouse Hit Testing & Drag Selection** | Click at right margin boundary (`pos0`) places caret at offset 0; click at left boundary places caret at `text.length`. Right-to-left drag selects forward `[0, length]`; left-to-right drag selects backward `[length, 0]`. | **PASS** |
| **RTL Formatting & Persistence** | Attributed bold/italic formatting on Urdu words reflected in Compose `TextLayoutResult` span styles; `.astra` file write/read roundtrips RTL text, diacritics, and styles with intact checksum. | **PASS** |
| **Mixed-Script Caret Positions** | 107 mixed-script caret positions across 6 fixtures verified for legal grapheme stops, visual direction, and reversible `Left->Right->Left` edge traversal. | **PASS** |

### Group C: Actual Windows Native Input Observations
*Physical Windows 11 input method interactions logged via opt-in local trace (`ImeTrace.kt`).*

| Input Method | Observed Behavior | Status |
|---|---|---|
| **Urdu (Pakistan)** | Selected `ur-PK` via `InputContext.selectInputMethod()`. Physical keystrokes recorded: `code=65` ('A') produced `'م'` (U+0645), `code=83` ('S') produced `'و'` (U+0648), displaying visible text `"مو"`. Ctrl+V pasted mixed Arabic/English/Urdu fixture. Evidence: `local-evidence/ime-1792146266448/events.jsonl`. | **PASS** |
| **Arabic (Saudi Arabia)** | Selected `ar-SA`. Physical keystroke recorded: `code=65` ('A') produced `'ش'` (U+0634). Preceding Urdu text and Arabic characters coexist in the committed buffer. Evidence: `local-evidence/ime-1792146266448/events.jsonl`. | **PASS** |
| **Japanese (Microsoft IME)** | Selected `ja-JP`. Windows language registration confirmed via `Get-WinUserLanguageList` and `WInputMethodDescriptor.getAvailableLocalesInternal()`. `InputContext.selectInputMethod(ja-JP)` returned `true`; `isCompositionEnabled = true` returned `true`. However, a clean physical romaji composition -> candidate popup conversion -> commit -> Undo cycle is **BLOCKED / NOT RUN** for real OS IME qualification. | **BLOCKED / NOT RUN** |

---

## 5. Detailed Breakdown of Japanese IME Status & Blocker

### 5.1 Established Capabilities
1. **OS Language Installation:** Windows Settings confirmed Japanese BasicTyping is installed.
2. **Registration in Registry/User Profile:** `Get-WinUserLanguageList` lists `LanguageTag: ja` with `EnglishName: Japanese`.
3. **JDK Native Visibility:** JDK 23 discovers `ja_JP` in `sun.awt.windows.WInputMethodDescriptor.getAvailableLocalesInternal()`.
4. **AWT Selection & Composition Enablement:** In `EditorSurface`, calling `inputContext.selectInputMethod(Locale.forLanguageTag("ja-JP"))` returns `true`, and `inputContext.isCompositionEnabled = true` succeeds.
5. **Attached Host Japanese Lifecycle Qualification:** As demonstrated in `JapaneseHostChecks.kt`, the prototype correctly processes romaji preedit, candidate conversion, candidate popup coordinate placement, multi-clause caret navigation, partial commits, Escape cancellation, focus-loss settlement, selection replacement, and queued commands during preedit.

### 5.2 Why Real OS IME Candidate Qualification is BLOCKED / NOT RUN
1. **Task Boundary Constraint:** The prompt explicitly mandates: *"Never report injected InputMethodEvent tests, clipboard insertion, or simulated keyboard events as real OS IME qualification."*
2. **Execution Environment:** In the current automated environment, terminal processes execute within an isolated Win32 background desktop session (`exebox-UVRTYVPPSOL2ILNWVXXT2HRTFV`), where the foreground window handle is 0 (`GetForegroundWindow() == 0`). Windows restricts background processes from stealing foreground input focus.
3. **Interactive User Concurrency:** The user is concurrently active on the physical console (`Default` desktop). Any attempt by background automation to force OS-level keystrokes would inject characters into the user's active editor/chat rather than the test window.
4. **Microsoft IME Conversion Mode:** On Windows 11, Microsoft Japanese IME activates in Direct Input (Alphanumeric) mode by default until toggled to Hiragana mode (via physical `Alt+\`` or `Ctrl+CapsLock` in an active foreground window). Without an interactive user physically typing in the activated window or a dedicated native UI automation harness, candidate popup conversion cannot be triggered natively.
5. **Verdict:** In strict accordance with the instruction (*"If the environment blocks a scenario, report BLOCKED/NOT RUN with the precise prerequisite. Do not substitute a simulation and mark it passed."*), real OS Japanese candidate qualification is recorded as **BLOCKED / NOT RUN**.

---

## 6. Demonstrated Defects and Resolutions

### Defect 1: Short RTL Paragraphs Left-Margin Placement
- **Symptom:** In Compose layout, short RTL paragraphs (e.g. `"اردو میں لکھیں"`) were rendered flush against the left margin rather than the right page margin.
- **Root Cause:** In `DocumentCanvas`, the measurer constraints were passed as `Constraints(maxWidth = textWidth)` with default `minWidth = 0`. Because the text was short, Skia measured the layout box to the width of the text itself. The Canvas then positioned this narrow box at `pageLeft + padding`, pinning it to the left edge of the page.
- **Fix:** In `DesktopEditor.kt:82`, constraints are specified with both `minWidth = textWidth` and `maxWidth = textWidth`:
  ```kotlin
  Constraints(minWidth = textWidth, maxWidth = textWidth)
  ```
- **Regression Test:** `RtlHostChecks.kt:22` asserts `g.layout.getHorizontalPosition(0, true) > g.layout.layoutInput.constraints.maxWidth * .9f`.

### Defect 2: RTL Selection Collapse Inverted Visual Direction
- **Symptom:** Pressing `Left` arrow on a selected RTL paragraph collapsed the caret to the visual right edge (offset 0), while pressing `Right` collapsed it to the visual left edge (offset `text.length`).
- **Root Cause:** `navigate()` collapsed non-collapsed selections using `s.start` (the lower logical offset) for `VK_LEFT` and `s.end` (the higher logical offset) for `VK_RIGHT`. For LTR text, logical lower corresponds to visual left. For RTL text, logical lower (offset 0) is visual right.
- **Fix:** In `DesktopEditor.kt:261-263`, endpoints are resolved from visual stops:
  ```kotlin
  if (!extend && !s.collapsed) {
      val endpoints = all.filter { it.primary && (it.offset == s.start || it.offset == s.end) }
      target = if (key == KeyEvent.VK_LEFT) endpoints.first() else endpoints.last()
  }
  ```
- **Regression Test:** `RtlHostChecks.kt:23-24` asserts `key(VK_LEFT)` collapses to `Selection(text.length, text.length)` and `key(VK_RIGHT)` collapses to `Selection(0, 0)`.

### Defect 3: `getLocationOffset` Threw `IllegalComponentStateException` Offscreen
- **Symptom:** Calling `surface.getLocationOffset(x, y)` when the surface was offscreen or unmapped threw `IllegalComponentStateException: component must be showing on the screen to determine its location`.
- **Root Cause:** In `DesktopEditor.kt:310`, `val screen = locationOnScreen` was called directly without error handling, unlike `getTextLocation` which wrapped it with `runCatching`.
- **Fix:** In `DesktopEditor.kt:311`, wrapped the call safely:
  ```kotlin
  val screen = runCatching { locationOnScreen }.getOrDefault(Point())
  ```
- **Regression Test:** `JapaneseHostChecks.kt:71` asserts `surface.getLocationOffset` returns correctly during offscreen attached-host testing without throwing.

### Defect 4: Compose Multi-Paragraph Global Base Direction (Architectural Limitation)
- **Symptom:** In a multi-paragraph document where Paragraph 0 is English (LTR) and Paragraph 1 is Urdu (RTL), Compose's single `TextLayoutResult` resolves the base direction of Paragraph 1 as LTR, preventing it from aligning to the right page margin.
- **Root Cause:** In Compose Desktop 1.12.1 (Skiko), a single `measurer.measure(AnnotatedString)` compiles into a single Skia `Paragraph`. Skia determines base paragraph direction globally from the first strong bidi character of the entire document.
- **Resolution / Scope:** Documented honestly as an architectural limitation of the prototype's single-canvas text measurement. The production v5 design (`editor-v5-standalone.md` §2.1, §8) already specifies block virtualization where each paragraph flow is measured independently.

---

## 7. Verification Record Summary

- **Deep Verification (Ran Actual Tests):**
  - `python desktop-prototype/verify.py` executed successfully (`exitCode: 0`).
  - Isolated batch output: `desktop-prototype/local-evidence/batch-20261016-210319-23776/`.
  - Kernel, file, formatting, stress, RTL, and Japanese attached-host suites passed without failures or regressions.
  - Frozen v5 SHA-256 hashes verified (45 files unchanged).
- **Shallow Verification (Manual / Eyeballed):**
  - Offline review of `ImeTrace` events from previous live Urdu and Arabic typing sessions (`ime-1792146266448`).
- **Unverified Aspects:**
  - Physical Japanese Microsoft IME romaji conversion and candidate popup interaction in an active console session.
  - Performance under 100,000+ unit multi-megabyte documents (prototype limit is 100,000 units).
  - Accessibility screen-reader integration on Windows UI Automation.
  - Non-Windows platforms (macOS / Linux).
  - Power-loss filesystem durability (prototype Save uses atomic move with CRC, not write-ahead journal).

---

## 8. Conclusion and Next Step

The desktop rich-text prototype input validation milestone is complete, hardened, and bounded. All demonstrated defects in RTL layout, selection collapse, and offscreen hit testing have been repaired and verified. Urdu and Arabic keyboard input have passed live verification. Japanese IME protocol handling has been comprehensively verified across its complete candidate lifecycle via attached Compose/AWT host checks. Real OS Japanese candidate qualification is accurately documented as blocked by the unattended background desktop environment.

**Next Justified Step:** Implement **document hierarchy and block virtualization** per `editor-v5-standalone.md` §2.1, providing independent text block layout measurement to resolve the multi-paragraph bidi base direction limitation.
