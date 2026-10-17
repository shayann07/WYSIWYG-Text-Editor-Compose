# Urdu Quality & Acceptance Gate U0: Profile & Corpus

This directory establishes the foundational artifacts for **Gate U0** of the Urdu document qualification plan defined in [URDU-ACCEPTANCE.md](../../../URDU-ACCEPTANCE.md).

Urdu is a primary product language for the editor, requiring full Nastaliq typography, canonical Unicode preservation, and RTL/mixed-script document capabilities.

---

## 1. Candidate Fonts & Fallback Manifest

Gate U0 evaluates at least two redistributable Nastaliq candidates plus a defined fallback font. All font binaries are pinned with exact byte sizes, SHA-256 hashes, OpenType layout tables, and installable embedding rights (`OS/2.fsType == 0x0000`) under the SIL Open Font License, Version 1.1.

| Font Family | File Name | Role | Style | Size (bytes) | SHA-256 | Key OpenType Features |
|---|---|---|---|---|---|---|
| **Noto Nastaliq Urdu** | `NotoNastaliqUrdu[wght].ttf` | Primary Candidate | Nastaliq | 690,304 | `98a4787f34eb6fde57fb9a1121a8f216301196ab0da98ea110cad581be8abbcd` | Variable weight (400..700), `curs` (cursive attachment), `mkmk`, `mark`, `ccmp`, `init`, `medi`, `fina`, `isol` |
| **Gulzar** | `Gulzar-Regular.ttf` | Secondary Candidate | Nastaliq | 963,496 | `246337973ac64e8b70fe611405dcf32a395a8885c4497dc45dbc4975d3ac0831` | Tiro Typeworks design, `curs`, `swsh` (swash), `jalt` (justification alternates), `ss01`-`ss04`, `mkmk`, `mark`, `liga` |
| **Noto Sans Arabic** | `NotoSansArabic[wdth,wght].ttf` | Fallback | Naskh | 844676 | `63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e` | Flat linear baseline, no `curs` |

### OpenType & Typographic Policy
- **Cursive Attachment (`curs`)**: Nastaliq scripts rely on cascading diagonal baselines where each letterform connects down and to the left of preceding letterforms. Both `Noto Nastaliq Urdu` and `Gulzar` implement the GPOS `curs` table.
- **Fallback Limitation**: `Noto Sans Arabic` uses a linear Naskh baseline and lacks `curs`. Per `URDU-ACCEPTANCE.md`, Naskh/fallback support cannot substitute for qualified Nastaliq in the Urdu application; it is retained strictly as an emergency fallback when glyphs are missing from Nastaliq fonts.
- **Redistribution & Embedding**: All fonts have `fsType = 0x0000` (Installable embedding) under SIL OFL 1.1, permitting embedding and subsetting in PDF and document packages.

Full font metadata and feature tag arrays are pinned in [fonts.json](fonts.json).

---

## 2. Acceptance Corpus (`fixtures.json`)

The corpus contains **301 deterministic fixtures** (`UTXT-001` through `UTXT-301`) across all 11 required categories specified in [URDU-ACCEPTANCE.md](../../../URDU-ACCEPTANCE.md), separating prototype-executable cases from future multi-paragraph / page / cell cases:

| Category | Fixture Count | Prototype-Executable | Future Page/Cell | Focus & Acceptance Target |
|---|---|---|---|---|
| `common_prose` | 28 | 28 | 0 | Basic sentence structure, Beh/Jeem/Seen forms, administrative, academic, and narrative prose. |
| `letter_distinctions` | 33 | 33 | 0 | Canonical preservation of Urdu-specific characters against Arabic equivalents (U+06A9 Keheh vs U+0643 Kaf, U+06CC Choti Yeh vs U+064A Arabic Yeh, U+06D2 Bari Yeh, U+06BA Noon Ghunna, U+06C1 Gol Heh vs U+06BE Do-Chashmi Heh vs U+0647 Arabic Heh, retroflex Tteh U+0679, Ddal U+0688, Rreh U+0691, Peh U+067E, Cheh U+0686, Zheh U+0698, Gaf U+06AF). |
| `marks_and_joining` | 30 | 30 | 0 | Tashkeel (Shaddah, Fatha, Kasra, Damma, Sukun), Tanween, Khari Zabar, Ulti Pesh, Hamza above, stacked marks, ZWNJ (U+200C), ZWJ (U+200D), and leading isolated marks. |
| `mixed_scripts_and_numbers` | 30 | 30 | 0 | Eastern Urdu digits (U+06F0..U+06F9), Arabic digits, Western ASCII digits, currency ($ and روپے), URLs, emails, Urdu punctuation (`۔`, `،`, `؛`, `؟`, `«»`, `”`), and algebraic/arithmetic equations. |
| `bidi_controls` | 26 | 26 | 0 | UAX #9 isolates (LRI U+2066, RLI U+2067, FSI U+2068, PDI U+2069), directional marks (LRM U+200E, RLM U+200F, ALM U+061C), nested isolates, and directional embedding. |
| `malformed_and_utf16_edge_cases` | 20 | 20 | 0 | Lone surrogates (`\uD83D`, `\uDE00`), inverted surrogate pairs, lone combining marks at start of paragraph/buffer, stacked orphan marks, non-characters (`\uFFFF`, `\uFFFE`), unclosed isolates, isolate underflow. |
| `formatting_and_undo_redo` | 20 | 20 | 0 | Inline bold/italic spans across Urdu word and diacritic boundaries, style transitions, and exact Undo/Redo attribute and text reversibility. |
| `nastaliq_geometry` | 28 | 28 | 0 | Multi-tier sloping ligatures (`نستعلیق`, `تحقیق`, `تصدیق`, `تشکیل`, `تخلیق`, `خوشخطی`, `سلسلہ`, `جھجک`, `لکھنؤ`, `شخصیت`, `فیصل`, `محبت`, `مستقبل`, `طبیعت`, `حقیقت`), vertical bounds, and fallback runs. |
| `spacing_and_justification` | 26 | 26 | 0 | Standard space (U+0020), NBSP (U+00A0), NNBSP (U+202F), tabs, hard/CRLF breaks, kashida/tatweel rejection policy (no injected Tatweel U+0640 in canonical storage), short last lines. |
| `structure_and_future_page_cells` | 32 | 22 | 10 | Headings H1..H3, RTL bulleted/numbered lists, classical poetry couplets (shers by Ghalib, Iqbal, Faiz), blockquotes, table cell paragraphs, running headers/footers, and page-anchored footnotes. |
| `input_and_mutation` | 28 | 28 | 0 | Phonetic keyboard sequences, diacritic deletion mutation, medial backspace ligature re-shaping, decomposed vs precomposed Unicode, and partial IME composition states. |
| **Total** | **301** | **291** | **10** | |

### Execution Scope Contract
- **`EXECUTABLE_PROTOTYPE` (291 fixtures)**: Exercises single-paragraph and continuous rich-text behaviors supported by the current prototype editor surface, validating canonical code points, diacritics, bidi isolates, formatting spans, and Undo/Redo cycles.
- **`FUTURE_STRUCTURAL_PAGE` (10 fixtures)**: Pinned structural contracts for future document capabilities (multi-paragraph table cells, running page headers/footers, explicit page breaks, page-anchored footnotes, two-column poetry, and blockquote margins). These are explicitly distinguished to prevent premature false claims of prototype qualification.

---

## 3. Fixed-Font OpenType Layout Probe & Typography Contact Sheet

The verification runner includes an automated OpenType layout probe (`fontTools`) measuring font metrics and examining cursive attachment:

| Font | UPEM | Ascent / Descent | Vertical Span | Min Line-Height Factor | Cursive Attachment (`curs`) |
|---|---|---|---|---|---|
| **Noto Nastaliq Urdu** | 1000 | +1900 / -600 | 2500 | **2.50x** | `True` (LookupType 3) |
| **Gulzar** | 1000 | +1950 / -750 | 2700 | **2.70x** | `True` (LookupType 9 ExtPos wrapping 3) |
| **Noto Sans Arabic** (Fallback) | 1000 | +1360 / -752 | 2112 | **2.112x** | `False` (Naskh linear baseline) |

### Key Typographic & Geometry Findings
1. **Vertical Clipping & Line Height Requirements**: Nastaliq scripts require more than double the vertical space of standard Latin fonts (Latin typically uses 1.2x-1.4x). Noto Nastaliq Urdu spans 2.50x UPEM and Gulzar spans 2.70x UPEM. Any rendering line height below **2.50x** will cause ascending diacritics (e.g. Shaddah + Fatha) or descending tails (Reh, Choti Yeh, Bari Yeh) to collide with adjacent lines or clip at container bounds.
2. **Sub-pixel Cursive Baseline Drift**: Nastaliq letters cascade diagonally downward and to the left using OpenType GPOS `curs` anchors. Sub-pixel rounding in custom rendering engines can accumulate drift across long cascading words if fractional coordinates are rounded prematurely.
3. **Flat-Baseline Fallback Discontinuity**: When glyphs missing from Nastaliq fall back to Noto Sans Arabic, jarring vertical baseline step-downs occur because Naskh operates on a flat horizontal line. Per `URDU-ACCEPTANCE.md`, Naskh can only serve as an emergency fallback, never a substitute for Nastaliq.

### Typography Contact Sheet
Running `python validation/urdu/u0/verify.py` generates [contact_sheet.html](contact_sheet.html), providing side-by-side visual comparison across all three fonts for common prose, cascading ligatures, letter distinctions, diacritic stacking, classical poetry, and mixed scripts.

---

## 4. Verification & Reproduction

Run the deterministic verification runner:
```bash
python validation/urdu/u0/verify.py
```

The runner performs the following automated checks:
1. **Font License & Notices**: Validates presence of [fonts/OFL.txt](fonts/OFL.txt) and confirms copyright notices for Noto Project Authors and Gulzar Project Authors.
2. **Font Integrity**: Validates presence, exact size, and SHA-256 for all candidate and fallback fonts in [fonts.json](fonts.json).
3. **OpenType Capabilities**: Validates `OS/2.fsType == 0x0000` (installable embedding), detects `curs` in both Nastaliq candidates (handling direct and ExtensionPos lookups), and confirms absence of `curs` in fallback Naskh font.
4. **Corpus Schema & IDs**: Validates sequential `UTXT-001` through `UTXT-301` without gaps or duplicates, with required fields, valid execution scopes, and category representations.
5. **Code-Unit Fidelity**: Reconstructs strings from both `codePointsHex` and `rawCodeUnits` and asserts exact byte-for-byte equality with `text`.
6. **Canonical Invariance & Normalization**: Asserts that no forbidden code points (such as Arabic Kaf U+0643 or Arabic Yeh U+064A) are present in the text or introduced through NFC normalization.
7. **Text & Attribute Preservation Checks**: Validates canonical invariance, formatting span boundaries, and bidi isolate balance on prototype-executable paths.
8. **Fixed-Font Layout Probe**: Measures OpenType metrics and verifies line-height requirements and cursive attachment lookups.
9. **Typography Contact Sheet Generation**: Emits [contact_sheet.html](contact_sheet.html).
10. **Results Output**: Writes execution results to [results.json](results.json).

---

## 5. Honest Qualification Boundaries

Per `URDU-ACCEPTANCE.md`, Gate U0 establishes the initial reproducible corpus, candidate fonts, layout metrics, and deterministic test harness. The qualification status recorded in [results.json](results.json) is:

- **Synthetic Corpus**: `QUALIFIED_SYNTHETIC` (301 deterministic fixtures verified across 11 categories, 291 executable + 10 future structural).
- **Candidate Font Binaries**: `QUALIFIED_PINNED` (Exact bytes, SHA-256, OpenType tables, and SIL OFL 1.1 licenses verified).
- **Preservation Checks**: `QUALIFIED_SYNTHETIC` (Canonical Unicode invariance, formatting boundaries, and bidi isolates verified).
- **Fixed-Font Layout Probe**: `QUALIFIED_PROBE` (Metrics and clipping/fallback concerns recorded).
- **Human Linguistic Review**: `OPEN` (All fixtures are marked `UNREVIEWED_SYNTHETIC`; human review by proficient Urdu readers is required for linguistic and typographic approval).
- **Native Host OS / IME Qualification**: `OPEN` (Desktop IME composition, mobile virtual keyboards, caret navigation, and page reflow remain subject to Gates U1 through U5).
