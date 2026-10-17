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

The corpus contains **305 deterministic fixtures** (`UTXT-001` through `UTXT-305`) across all 11 required categories specified in [URDU-ACCEPTANCE.md](../../../URDU-ACCEPTANCE.md), cleanly partitioned by execution scope:

| Category | Total Fixtures | Executable Prototype | Corpus Only | Future Page/Cell | Focus & Acceptance Target |
|---|---|---|---|---|---|
| `common_prose` | 28 | 28 | 0 | 0 | Basic sentence structure, Beh/Jeem/Seen forms, administrative, academic, and narrative prose. |
| `letter_distinctions` | 34 | 34 | 0 | 0 | Canonical preservation of Urdu-specific characters against Arabic equivalents (U+06A9 Keheh vs U+0643 Kaf, U+06CC Choti Yeh vs U+064A Arabic Yeh, U+06D2 Bari Yeh, U+06BA Noon Ghunna, U+06C1 Gol Heh vs U+06BE Do-Chashmi Heh vs U+0647 Arabic Heh, retroflex Tteh U+0679, Ddal U+0688, Rreh U+0691, Peh U+067E, Cheh U+0686, Zheh U+0698, Gaf U+06AF), including multilingual coexistence without false mutual exclusion. |
| `marks_and_joining` | 30 | 30 | 0 | 0 | Tashkeel (Shaddah, Fatha, Kasra, Damma, Sukun), Tanween, Khari Zabar, Ulti Pesh, Hamza above, stacked marks, ZWNJ (U+200C), ZWJ (U+200D), and leading isolated marks. |
| `mixed_scripts_and_numbers` | 30 | 30 | 0 | 0 | Eastern Urdu digits (U+06F0..U+06F9), Arabic digits, Western ASCII digits, currency ($ and روپے), URLs, emails, Urdu punctuation (`۔`, `،`, `؛`, `؟`, `«»`, `”`), and algebraic/arithmetic equations. |
| `bidi_controls` | 26 | 26 | 0 | 0 | UAX #9 isolates (LRI U+2066, RLI U+2067, FSI U+2068, PDI U+2069), directional marks (LRM U+200E, RLM U+200F, ALM U+061C), nested isolates, and directional embedding. |
| `malformed_and_utf16_edge_cases` | 23 | 23 | 0 | 0 | Supplementary plane characters (`😀` U+1F600), complex emoji ZWJ sequences (`👩‍💻`), lone surrogates (`\uD83D`, `\uDE00`), surrogate+letter pairs (`\uD83D\u0627`), inverted surrogate pairs (`\uDE00\uD83D`), lone combining marks at buffer start, stacked orphan marks, non-characters (`\uFFFF`, `\uFFFE`), unclosed isolates, isolate underflow. |
| `formatting_and_undo_redo` | 20 | 20 | 0 | 0 | Inline bold/italic spans across Urdu word and diacritic boundaries, style transitions, and exact Undo/Redo attribute and text reversibility. |
| `nastaliq_geometry` | 28 | 0 | 28 | 0 | Multi-tier sloping ligatures (`نستعلیق`, `تحقیق`, `تصدیق`, `تشکیل`, `تخلیق`, `خوشخطی`, `سلسلہ`, `جھجک`, `لکھنؤ`, `شخصیت`, `فیصل`, `محبت`, `مستقبل`, `طبیعت`, `حقیقت`), vertical bounds, and calligraphic specimens. Classified as `CORPUS_ONLY` at U0 pending full page-level pagination and calligraphic review. |
| `spacing_and_justification` | 26 | 26 | 0 | 0 | Standard space (U+0020), NBSP (U+00A0), NNBSP (U+202F), tabs, hard/CRLF breaks, kashida/tatweel rejection policy (no injected Tatweel U+0640 in canonical storage), short last lines. |
| `structure_and_future_page_cells` | 32 | 22 | 0 | 10 | 22 single-run prototype-executable structures (headings H1..H3, RTL bulleted/numbered lists, classical couplets); 10 future structural contracts (rich table cell paragraphs, running page headers/footers, explicit page breaks, page-anchored footnotes, two-column poetry, and blockquote margins). |
| `input_and_mutation` | 28 | 28 | 0 | 0 | Phonetic keyboard sequences, diacritic deletion mutation, medial backspace ligature re-shaping, decomposed vs precomposed Unicode, and partial IME composition states. |
| **Total** | **305** | **267** | **28** | **10** | |

### Execution Scope Contract
- **`EXECUTABLE_PROTOTYPE` (267 fixtures)**: Exercises single-paragraph and continuous rich-text behaviors executed against the real Kotlin `EditorEngine` and Compose layout subsystem, validating canonical code points, diacritics, bidi isolates, formatting spans, exact Undo/Redo attribute reversibility, IME composition/commitment, and binary file save/load persistence.
- **`CORPUS_ONLY` (28 fixtures)**: Calligraphic specimens and multi-tiered cascading ligatures preserved for font metrics and contact sheet review, without false claims of complete calligraphic qualification.
- **`FUTURE_STRUCTURAL_PAGE` (10 fixtures)**: Pinned structural contracts for future document capabilities (multi-paragraph table cells, running page headers/footers, explicit page breaks, page-anchored footnotes, two-column poetry, and blockquote margins).

---

## 3. Fixed-Font Layout Probes & Typography Contact Sheet

### OpenType Font Metrics Probe (`fontTools`)
The Python verification runner examines OpenType tables directly:

| Font | UPEM | Ascent / Descent | Vertical Span | Vertical Span Ratio | Cursive Attachment (`curs`) |
|---|---|---|---|---|---|
| **Noto Nastaliq Urdu** | 1000 | +1900 / -600 | 2500 | **2.50x** | `True` (LookupType 3) |
| **Gulzar** | 1000 | +1950 / -750 | 2700 | **2.70x** | `True` (LookupType 9 ExtPos wrapping 3) |
| **Noto Sans Arabic** (Fallback) | 1000 | +1360 / -752 | 2112 | **2.112x** | `False` (Naskh linear baseline) |

### Real Compose `Paragraph` Layout Probe
The Kotlin test harness loads the pinned font binaries directly into Compose Desktop (`androidx.compose.ui.text.platform.Font`), measures representative specimens across all three fonts, and records geometry and cursor coordinates into `urdu-harness-results.json`:
- **Prose & Cascading Specimens**: Noto Nastaliq Urdu measures ~65px height at 26sp, Gulzar measures ~70px, whereas fallback Noto Sans Arabic measures 55px on its flat baseline.
- **Specimen Bitmaps**: The harness renders 15 specimen bitmaps to PNG via Skia canvas (`layout-probe-*.png`), capturing actual rasterizer output for visual inspection.

### Typographic Observations
1. **Vertical Space Demands**: Nastaliq fonts feature large vertical metrics (vertical span ratio of 2.50x-2.70x UPEM) compared to Latin (1.2x-1.4x) or Naskh (2.112x). However, ascent-minus-descent ratios represent font design bounds, NOT a proven universal minimum line height or proof that clipping will occur. In Compose `Paragraph` layout, observed line heights were ~65px for Noto Nastaliq Urdu and ~70px for Gulzar at 26sp. Visual clipping and aesthetic adequacy remain unresolved hypotheses pending human calligraphic review (NOT RUN at U0).
2. **Sub-pixel Cursive Baseline Drift**: Nastaliq letters cascade diagonally downward and to the left using OpenType GPOS `curs` anchors. Sub-pixel rounding in custom rendering engines can accumulate drift across long cascading words if fractional coordinates are rounded prematurely.
3. **Flat-Baseline Fallback Discontinuity**: When glyphs missing from Nastaliq fall back to Noto Sans Arabic, vertical step-downs occur because Naskh operates on a flat horizontal line. Per `URDU-ACCEPTANCE.md`, Naskh serves only as an emergency fallback, not a substitute for Nastaliq.

### Typography Contact Sheet
Running `python validation/urdu/u0/verify.py` generates [contact_sheet.html](contact_sheet.html), providing side-by-side visual comparison across all three fonts.

---

## 4. Verification & Reproduction

Run the deterministic verification runner:
```bash
python validation/urdu/u0/verify.py
```

The runner performs the following automated checks:
1. **Font License & Notices**: Validates presence of [fonts/OFL.txt](fonts/OFL.txt) and confirms copyright notices for Noto Project Authors and Gulzar Project Authors.
2. **Font Integrity**: Validates presence, exact size, and SHA-256 for all candidate and fallback fonts in [fonts.json](fonts.json).
3. **OpenType Capabilities**: Validates `OS/2.fsType == 0x0000` (installable embedding), detects `curs` in both Nastaliq candidates, and confirms absence of `curs` in fallback Naskh font.
4. **Corpus Schema & Strict UTF-8**: Validates sequential `UTXT-001` through `UTXT-305` without gaps or duplicates, strict RFC 8259 UTF-8 serialization with escaped lone surrogates, and valid execution scopes.
5. **Code-Unit & Scalar Fidelity**: Reconstructs strings from both `codePointsHex` and `rawCodeUnits`, asserts exact byte-for-byte equality with `text`, and verifies scalar count vs UTF-16 code unit counts.
6. **Canonical Invariance & Normalization**: Asserts that forbidden code points specified per fixture are absent and not introduced through NFC normalization.
7. **Preservation Checks**: Validates canonical invariance, formatting span boundaries, and bidi isolate balance on prototype-executable paths.
8. **Real Kotlin `EditorEngine` Harness**: Runs all 267 executable fixtures through `EditorEngine`:
   - Text insertion and exact UTF-16 code unit matching
   - Formatting spans and exact Undo/Redo style reversibility
   - IME provisional composition, commitment, and undo
   - Binary `DocumentFiles` save/load serialization roundtrip
   - Malformed / surrogate code unit preservation
9. **Real Compose `Paragraph` Layout Probe**: Measures specimen metrics and renders 15 PNG bitmaps.
10. **Mutation Fault Detection**: Injects 3 distinct behavioral mutations (letter substitution, undo style loss, surrogate mangling) and proves harness assertions catch them.
11. **Typography Contact Sheet Generation**: Emits [contact_sheet.html](contact_sheet.html).
12. **Results Output**: Writes comprehensive execution results to [results.json](results.json).

---

## 5. Honest Qualification Boundaries & Separate Outcomes

Per `URDU-ACCEPTANCE.md`, Gate U0 establishes the initial reproducible profile and corpus (`status: PROFILE_AND_CORPUS_ESTABLISHED`). The outcomes are strictly separated:

1. **`corpusIntegrity`: `PASS`** — 305 deterministic fixtures verified across all 11 categories: 267 executable prototype fixtures, 28 corpus-only calligraphic fixtures, 10 future page/cell contracts. Code-unit fidelity, strict UTF-8 serialization, and canonical invariance verified.
2. **`engineBehavior`: `PASS`** — All 267 executable fixtures executed against the real Kotlin `EditorEngine` harness (formatting spans, exact Undo/Redo attribute reversibility, IME composition/commitment, DocumentFiles save/load, surrogate units). 3/3 real injected mutation faults caught by harness assertions.
3. **`fontMetadata`: `PASS`** — Pinned candidate and fallback font binaries verified for exact sizes, SHA-256 hashes, OpenType tables (`OS/2.fsType == 0x0000`, `curs` in Nastaliq, absent in Naskh), and upstream `name` table license provenance (NameIDs 0, 13, 14 confirming SIL OFL 1.1 and author notices).
4. **`composeLayout`: `PROBED`** — Measured using Compose `Paragraph` across all 3 pinned fonts with 15 specimen bitmaps rendered to PNG. Real line heights and clipping hypotheses documented without ungrounded universal line-height claims.
5. **`nativeInput`: `OPEN`** — Real OS IME keystroke handling (Windows IMM32 / macOS TSM / Android InputConnection), native caret navigation, and platform key event loops remain open for Gates U1 through U5.
6. **`humanReview`: `NOT RUN`** — Professional human Urdu calligrapher and linguistic evaluation remains unrun; automated metrics and visual specimen cards cannot substitute for human qualification.

