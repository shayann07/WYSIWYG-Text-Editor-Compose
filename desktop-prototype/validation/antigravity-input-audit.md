# Astra Compose Desktop Prototype — Input Validation Audit Report

**Date:** 16 October 2026  
**Auditor:** Antigravity (Advanced Agentic Coding)  
**Milestone:** Desktop rich-text prototype input validation and hardening  
**Specification Baseline:** Frozen v5.0.0 (`editor-v5-standalone.md`, `plan-v5.md`, `v5-freeze-manifest.json`)  
**Base Commit:** `db6c0e9` ("Add attributed bold and italic editing with persistence and regression coverage")  

---

## 1. Executive Summary and Recommendation

The input-validation milestone for the Compose Desktop prototype has been audited, strengthened, and verified. 

Key results:
1. **Runner Audit:** `python desktop-prototype/verify.py` builds into a completely isolated directory under `desktop-prototype/local-evidence/batch-<timestamp>-<pid>/`, executes the four deterministic suites with bounded stdout, traps non-zero return codes, prevents reuse of stale bytecode/evidence, verifies all 45 frozen v5 manifest hashes, and produces reproducible JSON results.
2. **Headless & Model Checks:** 41 test groups (15 kernel, 10 file, 16 formatting), 2,000 generated composition sessions, 12,000 randomized range-format operations, and 1,093 Unicode 16 grapheme cluster fixtures pass without defect.
3. **Attached AWT + Compose Host Checks:** The attached-host test suite was expanded in `RtlHostChecks.kt` to cover multi-mark combining clusters (Shaddah + Fatha), medial joining character deletion, Eastern Arabic-Indic digits and punctuation, multi-line RTL wrapping and vertical navigation (`VK_DOWN` / `VK_UP`), cross-paragraph navigation and merge, mouse hit testing at RTL margins, attributed bold/italic styling on Urdu text, and exact `.astra` persistence roundtrip. All 107 mixed-script caret positions pass visual direction and reversible Left/Right traversal.
4. **Windows Native Keyboard Observations:**
   - **Urdu:** Live keyboard input confirmed in `ime-1792146266448` (`VK_A` -> `'م'`, `VK_S` -> `'و'`, visible `"مو"`).
   - **Arabic:** Live keyboard input confirmed in `ime-1792146266448` (`VK_A` -> `'ش'`).
   - **Japanese Microsoft IME:** JDK registration is clean (`ja-JP` accepted, `isCompositionEnabled = true`). However, a complete physical romaji composition -> candidate popup conversion -> commit -> Undo cycle is **BLOCKED / NOT RUN** for real OS IME qualification. Automation environments (including simulated keystrokes / `Robot`) are explicitly prohibited from being reported as real OS IME qualification, and the process runs in an isolated desktop sandbox (`exebox-...`) while the user actively works in the foreground on the `Default` desktop.
5. **Concrete Defects Repaired:**
   - **DEFECT-01 (Fixed):** Short RTL paragraphs left-aligned due to zero `minWidth` constraint.
   - **DEFECT-02 (Fixed):** Selection collapse on RTL text inverted visual direction due to logical endpoint sorting.
   - **DEFECT-03 (Documented Limitation):** Single `TextLayoutResult` in Compose Desktop (Skiko) resolves document base direction globally based on the first paragraph's text. Multi-paragraph documents with independent base directions require block-level paragraph virtualization.

**Recommendation:** The input milestone is **READY TO ADVANCE** within its explicitly documented prototype boundaries. The next justified implementation step is **document hierarchy and block virtualization** (rendering individual text blocks rather than passing the entire document as a single string to Compose `TextMeasurer`), which will cleanly resolve multi-paragraph bidi base direction isolation before addressing rich containers (tables/lists).

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
| `desktop-prototype/verify.py` | `713a761e89309a457dbb9e0b1f5711cb8b89888db54f39e34a021a321a27c62c` |
| `desktop-prototype/src/main/kotlin/editor/Checks.kt` | `eb94187c261d7d6a4b3c3a887c5055d1454880c01687232f056a445b4bd797db` |
| `desktop-prototype/src/main/kotlin/editor/DesktopEditor.kt` | `eedfe61613b05b91d2165fba532ab88522d917e2f7113da32de0a4374886e130` |
| `desktop-prototype/src/main/kotlin/editor/DocumentFiles.kt` | `dbeeccaff20eae6ce875f4f65a7e399fce867a4424b279ed6a79027d9617c195` |
| `desktop-prototype/src/main/kotlin/editor/EditorEngine.kt` | `a34044c8575032180f2166a45f0d6cc51ef1e76fd6293efec5f5b330cb91bffe` |
| `desktop-prototype/src/main/kotlin/editor/FileChecks.kt` | `043e87612ecf40963de55dd2cf081a96454610da99131d9ebb2519b1360c616c` |
| `desktop-prototype/src/main/kotlin/editor/FormatChecks.kt` | `0c03cd09842e733f5cf3c5e74a52a9f9b1d7858b3e31623599cd2f5edd612a99` |
| `desktop-prototype/src/main/kotlin/editor/Formatting.kt` | `f3d0e481c245b4db321da332f3ad40490e8321fbd64edebb4b8be2e4cc20d092` |
| `desktop-prototype/src/main/kotlin/editor/Graphemes.kt` | `a297a5154df6a77cb1bc65115c92d5a738367671fb02f47a94c425544cb0c629` |
| `desktop-prototype/src/main/kotlin/editor/HostChecks.kt` | `a50f6935136b24fb35befb6620ccbf6bac55b1943a7e43190a685e45e9b0fbca` |
| `desktop-prototype/src/main/kotlin/editor/ImeTrace.kt` | `153bb4b538d707cdf6c57fada2c71ff55db3445757295bc9f67612ce4e06a521` |
| `desktop-prototype/src/main/kotlin/editor/Main.kt` | `d13726eb1f5b43f5b37f31b19c61bf712ba5335d687ed538ba2cc17c28ec3ca3` |
| `desktop-prototype/src/main/kotlin/editor/RtlHostChecks.kt` | `0d426a74056c1e53abdcac393ff3da9d5e6532e257132c53376eeb5c3245ea07` |
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

### 3.2 Reproducible Test Instructions
To execute the authoritative deterministic test batch:
```powershell
python desktop-prototype/verify.py
```
This command performs:
1. Fresh Maven compilation and dependency copy into `desktop-prototype/local-evidence/batch-<timestamp>-<pid>/build/`.
2. Execution of `editor.MainKt --check` (15 kernel, 10 file, 16 format groups).
3. Execution of `editor.MainKt --stress` (2,000 composition sessions, 12,000 format commands).
4. Execution of `editor.MainKt --host-check --host-result=<path>` (attached AWT + Compose layout + RTL suite).
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
| **Synthetic IME Protocol** | 7 synthetic `InputMethodEvent`s tested: composed text replacement, partial commitment, caret positioning, surrogate caret offsets, rejection of out-of-range carets, clean Undo restoration. | **PASS** |
| **Committed Context Queries** | 15 slice queries through `InputMethodRequests.getCommittedText()` verified against document content. | **PASS** |
| **RTL Margin Alignment** | RTL paragraphs starting with strong RTL characters (`"اردو میں لکھیں"`, `"العربية جميلة"`) measure start position at `> 0.9 * maxWidth` (right margin). | **PASS** |
| **Visual Selection Collapse** | Non-collapsed RTL selection collapses to visual left edge on `VK_LEFT` (offset `text.length`) and visual right edge on `VK_RIGHT` (offset `0`). | **PASS** |
| **Combining Clusters** | Multi-mark sequence `"سَّلام"` (Base + Shaddah U+0651 + Fatha U+064E) deleted as an atomic cluster via Backspace and restored via Undo. | **PASS** |
| **Medial Joining Deletion** | Deletion of medial joining letter in `"کتاب"` (at offset 2) produces `"کاب"`; Undo restores `"کتاب"`. | **PASS** |
| **Eastern Digits & Punctuation** | Extended Arabic-Indic digits (`۱۲۳`), Arabic-Indic digits (`١٢٣`), and Arabic punctuation (`،`, `؛`, `؟`) traverse legal grapheme stops. | **PASS** |
| **Multi-Line RTL Wrapping** | Long RTL paragraph (180+ characters) wraps across multiple lines (`lineCount >= 2`); `VK_DOWN` moves to line 1 and `VK_UP` returns to line 0 start. | **PASS** |
| **Multi-Paragraph Transitions** | Cross-paragraph vertical arrow movement (`VK_DOWN` / `VK_UP`) between lines; Backspace at second paragraph boundary merges paragraphs; Undo restores separation. | **PASS** |
| **Mouse Hit Testing** | Mouse click at right margin boundary (`pos0`) places caret at offset 0; mouse click at left boundary places caret at `text.length`. | **PASS** |
| **RTL Formatting & Persistence** | Attributed bold/italic formatting on Urdu words reflected in Compose `TextLayoutResult` span styles; `.astra` file write/read roundtrips RTL text, diacritics, and styles with intact checksum. | **PASS** |
| **Mixed-Script Caret Positions** | 107 mixed-script caret positions across 6 fixtures verified for legal grapheme stops, visual direction, and reversible `Left->Right->Left` edge traversal. | **PASS** |

### Group C: Actual Windows Native Input Observations
*Physical Windows 11 input method interactions logged via opt-in local trace (`ImeTrace.kt`).*

| Input Method | Observed Behavior | Status |
|---|---|---|
| **Urdu (Pakistan)** | Selected `ur-PK` via `InputContext.selectInputMethod()`. Physical keystrokes recorded: `code=65` ('A') produced `'م'` (U+0645), `code=83` ('S') produced `'و'` (U+0648), displaying visible text `"مو"`. Ctrl+V pasted mixed Arabic/English/Urdu fixture. Evidence: `local-evidence/ime-1792146266448/events.jsonl`. | **PASS** |
| **Arabic (Saudi Arabia)** | Selected `ar-SA`. Physical keystroke recorded: `code=65` ('A') produced `'ش'` (U+0634). Preceding Urdu text and Arabic characters coexist in the committed buffer. Evidence: `local-evidence/ime-1792146266448/events.jsonl`. | **PASS** |
| **Japanese (Microsoft IME)** | Selected `ja-JP`. Windows language registration confirmed via `Get-WinUserLanguageList` and `WInputMethodDescriptor.getAvailableLocalesInternal()`. `InputContext.selectInputMethod(ja-JP)` returned `true`; `isCompositionEnabled = true` returned `true`. However, a clean physical romaji composition -> candidate popup -> commit -> Undo cycle is **BLOCKED / NOT RUN** for real OS IME qualification. | **BLOCKED / NOT RUN** |

---

## 5. Detailed Breakdown of Japanese IME Status & Blocker

### 5.1 Established Capabilities
1. **OS Language Installation:** Windows Settings confirmed Japanese BasicTyping is installed.
2. **Registration in Registry/User Profile:** `Get-WinUserLanguageList` lists `LanguageTag: ja` with `ScriptName: Japanese`.
3. **JDK Native Visibility:** Re-registering user language list allowed JDK 23 to discover `ja_JP` in `sun.awt.windows.WInputMethodDescriptor`.
4. **AWT Selection & Composition Enablement:** In `EditorSurface`, calling `inputContext.selectInputMethod(Locale.forLanguageTag("ja-JP"))` returns `true`, and `inputContext.isCompositionEnabled = true` succeeds.
5. **AWT Engine Composition Protocol:** If AWT `InputMethodEvent`s are delivered, `EditorEngine` correctly splits committed prefix and composed suffix, maintains provisional candidate overlay $\mathcal{O}$, captures the attribution seed, and coalesces the committed result into a single Undo group upon settlement.

### 5.2 Why Real OS IME Candidate Qualification is BLOCKED / NOT RUN
1. **Task Boundary Constraint:** The prompt explicitly mandates: *"Never report injected InputMethodEvent tests, clipboard insertion, or simulated keyboard events as real OS IME qualification."*
2. **Execution Environment:** In the current automated environment, terminal processes execute within a specialized background session / desktop (`exebox-...`), where the foreground window handle is 0. Windows restricts background processes from stealing foreground input focus (`SetForegroundWindow` restrictions).
3. **Interactive User Concurrency:** The user is concurrently active on the physical console (`Default` desktop, where `Antigravity.exe` PID 7020 holds the foreground window). Any attempt by background automation to force OS-level keystrokes would inject characters into the user's active editor/chat rather than the test window.
4. **Microsoft IME Conversion Mode:** On Windows 11, Microsoft Japanese IME activates in Direct Input (Alphanumeric) mode by default until toggled to Hiragana mode (via physical `Alt+\`` or `Ctrl+CapsLock` in an active foreground window). Without an interactive user physically typing in the activated window or a dedicated native UI automation harness, candidate popup conversion cannot be triggered.
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
- **Regression Test:** `RtlHostChecks.kt:14` asserts `g.layout.getHorizontalPosition(0, true) > g.layout.layoutInput.constraints.maxWidth * .9f`.

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
- **Regression Test:** `RtlHostChecks.kt:15-16` asserts `key(VK_LEFT)` collapses to `Selection(text.length, text.length)` and `key(VK_RIGHT)` collapses to `Selection(0, 0)`.

### Defect 3: Compose Multi-Paragraph Global Base Direction (Architectural Limitation)
- **Symptom:** In a multi-paragraph document where Paragraph 0 is English (LTR) and Paragraph 1 is Urdu (RTL), Compose's single `TextLayoutResult` resolves the base direction of Paragraph 1 as LTR, preventing it from aligning to the right page margin.
- **Root Cause:** In Compose Desktop 1.12.1 (Skiko), a single `measurer.measure(AnnotatedString)` compiles into a single Skia `Paragraph`. Skia determines base paragraph direction globally from the first strong bidi character of the entire document. Adding `ParagraphStyle(textDirection = TextDirection.Content)` to an `AnnotatedString` cannot override Skia's monolithic paragraph base direction on Desktop.
- **Resolution / Scope:** Documented honestly as an architectural limitation of the prototype's single-canvas text measurement. The production v5 design (`editor-v5-standalone.md` §2.1, §8) already specifies block virtualization where each paragraph flow is measured independently.

---

## 7. Verification Record Summary

- **Deep Verification (Ran Actual Tests):**
  - `python desktop-prototype/verify.py` executed successfully (`exitCode: 0`).
  - Isolated batch output: `desktop-prototype/local-evidence/batch-20261016-204657-23948/`.
  - Kernel, file, formatting, stress, and attached-host suites passed without failures or regressions.
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

The desktop rich-text prototype input validation milestone is complete and fully bounded. All demonstrated regressions in RTL layout and selection collapse have been repaired and verified. Urdu and Arabic keyboard input have passed live verification. Japanese IME protocol handling is robustly tested via synthetic AWT streams, while real candidate qualification is appropriately reported as blocked by the headless/background execution environment.

**Next Justified Step:** Implement **document hierarchy and block virtualization** per `editor-v5-standalone.md` §2.1, providing independent text block layout measurement to resolve the multi-paragraph bidi base direction limitation.
