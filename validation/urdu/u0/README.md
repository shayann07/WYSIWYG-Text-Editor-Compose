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

The corpus contains **257 deterministic fixtures** (`UTXT-001` through `UTXT-257`) across all 9 required categories specified in `URDU-ACCEPTANCE.md`:

| Category | Fixture Count | Focus & Acceptance Target |
|---|---|---|
| `common_prose` | 28 | Basic sentence structure, Beh/Jeem/Seen forms, administrative, academic, and narrative prose. |
| `letter_distinctions` | 33 | Canonical preservation of Urdu-specific characters against Arabic equivalents (U+06A9 Keheh vs U+0643 Kaf, U+06CC Choti Yeh vs U+064A Arabic Yeh, U+06D2 Bari Yeh, U+06BA Noon Ghunna, U+06C1 Gol Heh vs U+06BE Do-Chashmi Heh vs U+0647 Arabic Heh, retroflex Tteh U+0679, Ddal U+0688, Rreh U+0691, Peh U+067E, Cheh U+0686, Zheh U+0698, Gaf U+06AF). |
| `marks_and_joining` | 30 | Tashkeel (Shaddah, Fatha, Kasra, Damma, Sukun), Tanween, Khari Zabar, Ulti Pesh, Hamza above, stacked marks, ZWNJ (U+200C), ZWJ (U+200D), and leading isolated marks. |
| `mixed_scripts_and_numbers` | 30 | Eastern Urdu digits (U+06F0..U+06F9), Arabic digits, Western ASCII digits, currency ($ and روپے), URLs, emails, Urdu punctuation (`۔`, `،`, `؛`, `؟`, `«»`, `”`), and algebraic/arithmetic equations. |
| `bidi_controls` | 26 | UAX #9 isolates (LRI U+2066, RLI U+2067, FSI U+2068, PDI U+2069), directional marks (LRM U+200E, RLM U+200F, ALM U+061C), nested isolates, and unterminated isolates. |
| `nastaliq_geometry` | 28 | Multi-tier sloping ligatures (`نستعلیق`, `تحقیق`, `تصدیق`, `تشکیل`, `تخلیق`, `خوشخطی`, `سلسلہ`, `جھجک`, `لکھنؤ`, `شخصیت`, `فیصل`, `محبت`, `مستقبل`, `طبیعت`, `حقیقت`), vertical bounds, and fallback runs. |
| `spacing_and_justification` | 26 | Standard space (U+0020), NBSP (U+00A0), NNBSP (U+202F), tabs, hard/CRLF breaks, kashida/tatweel rejection policy (no injected Tatweel U+0640 in canonical storage), short last lines. |
| `structure` | 28 | Headings H1..H4, RTL bulleted/numbered lists, classical poetry couplets (shers by Ghalib, Iqbal, Faiz), blockquotes, multi-paragraph notes, and footnotes. |
| `input_and_mutation` | 28 | Phonetic keyboard sequences, diacritic deletion mutation, medial backspace ligature re-shaping, decomposed vs precomposed Unicode, and partial IME composition states. |

### Fixture Schema
Each fixture in [fixtures.json](fixtures.json) adheres to the following contract:
```json
{
  "id": "UTXT-001",
  "category": "common_prose",
  "title": "Basic Urdu declaration with Khatma.",
  "text": "یہ ایک اردو دستاویز ہے۔",
  "rawCodeUnits": "\\u06cc\\u06c1 \\u0627\\u06cc\\u06a9 \\u0627\\u0631\\u062f\\u0648 \\u062f\\u0633\\u062a\\u0627\\u0648\\u06cc\\u0632 \\u06c1\\u06d4",
  "codePointsHex": ["06CC", "06C1", "0020", "0627", "06CC", "06A9", "0020", "0627", "0631", "062F", "0648", "0020", "062F", "0633", "062A", "0627", "0648", "06CC", "0632", "0020", "06C1", "06D4"],
  "purpose": "Verify basic Urdu word joining, space boundary, and Khatma punctuation.",
  "provenance": "Standard modern Urdu",
  "expectedOutcome": {
    "bidiDirection": "RTL",
    "joiningValid": true,
    "requiresNastaliq": true,
    "canonicalInvariance": true,
    "forbiddenInCanonical": []
  },
  "reviewerStatus": "UNREVIEWED_SYNTHETIC"
}
```

---

## 3. Verification & Reproduction

Run the deterministic verification runner:
```bash
python validation/urdu/u0/verify.py
```

The runner performs the following checks:
1. **Font Integrity**: Validates presence, exact size, and SHA-256 for all candidate and fallback fonts.
2. **OpenType Capabilities**: Validates `OS/2.fsType == 0` (installable embedding), presence of `curs` in Nastaliq candidates, and absence of `curs` in fallback Naskh font.
3. **Corpus Schema & IDs**: Validates that all IDs are sequential `UTXT-001` through `UTXT-257` without gaps or duplicates.
4. **Code-Unit Fidelity**: Reconstructs strings from both `codePointsHex` and `rawCodeUnits` and asserts exact byte-for-byte equality with `text`.
5. **Canonical Invariance & Normalization**: Asserts that no forbidden code points (such as Arabic Kaf U+0643 or Arabic Yeh U+064A) are present in the text or introduced through NFC normalization.
6. **Results Output**: Writes execution results to [results.json](results.json).

---

## 4. Honest Qualification Boundaries

Per `URDU-ACCEPTANCE.md`, Gate U0 establishes the initial reproducible corpus and candidate font foundation. The qualification status recorded in [results.json](results.json) is:

- **Synthetic Corpus**: `QUALIFIED_SYNTHETIC` (257 deterministic fixtures verified across 9 categories).
- **Candidate Font Binaries**: `QUALIFIED_PINNED` (Exact bytes, SHA-256, OpenType tables, and licenses verified).
- **Human Linguistic Review**: `OPEN` (All fixtures are marked `UNREVIEWED_SYNTHETIC`; human review by proficient Urdu readers is required for linguistic and typographic approval).
- **Native Host OS / IME Qualification**: `OPEN` (Desktop IME composition, mobile virtual keyboards, caret navigation, and page reflow remain subject to Gates U1 through U5).
