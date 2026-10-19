# Independent output and typography inspection — 19 October 2026

This is a bounded supplemental inspection for the Urdu pages/output batch. It records rejected evidence without adopting its backend or calling it qualified PDF. The main batch report owns the final build and delivery status. No native application interaction, physical printing or expert Urdu review was performed by this inspection.

## Stage 1 decision

**BLOCKED: no qualified editor-to-semantic-PDF route.** The two allowed initial candidates were evaluated; neither closes the required shaped-output boundary. This is a result for the pinned APIs and witnesses, not a claim that semantic Urdu PDF is generally impossible.

| Route | Observed capability | Disposition |
|---|---|---|
| Skiko 0.150.1 paragraph → native Skia PDF | Installed `Paragraph` exposes paint, line metrics, range rectangles and hit testing. Installed `TextBlob` has glyph/cluster/position access, but there is no exposed Paragraph-to-TextBlob/visitor path and no installed `org.jetbrains.skia.Document` PDF binding. | BLOCKED. Native Skia capability alone is not a callable binding in this runtime. |
| Isolated PDFBox 3.0.5 packaging + existing independent RunHandler shaping | TrueType font embedding and String encoding execute. `PDPageContentStream` exposes String text, positioned String arrays, matrices and non-text primitives. Actual editor GIDs, many-to-many clusters and resolved fallback runs remain unavailable. Independent shaping fails the unchanged witness. | REJECTED_LAYOUT_SUBSTITUTION. String encoding is not a positioned editor-glyph bridge. |

The mixed 180-unit witness expects line ranges `[0,14)`, `[14,26)`, `[26,43)` and observes `[0,14)`, `[14,27)`, `[27,43)`. The latter two width errors are **9.880005** against the unchanged **0.25 pt** tolerance. No new PDF was produced by this bridge proof. Its receipt identifies the preceding workspace build manifest `77595dbfcbae8134a73ba0a530d1538734001117b1e6f1284b06b2bdb3e62a41`; it is not a claim that the later page implementation uses that same build.

Evidence: `desktop-prototype/local-evidence/output-bridge-proof-20261019/results.json` and `capability.log`. The separate rejected historical PDF recheck is under `output-rejected-pdf-recheck-20261019`; it must never be offered as the qualified final output.

## Independent rejected-PDF checks

The preserved candidate is **PyMuPDF vector SVG outlines plus invisible canonical glyphs in recorded visual order**, SHA256 `2385ff080ead8e86b2162187963aa2d4bdc554ce6241fe3330bcf438e1709e57`. Independent codebases inspect the same three-page PDF:

| Tool | Version | Observation |
|---|---|---|
| Poppler `pdftoppm` | 26.07.0 | Independently rasterized all three 600×800 pt pages at 72 dpi. Every resulting PNG was opened and inspected here. |
| pypdf | 6.16.2 | Exact logical extraction fails all three pages, retaining authored spacing, marks and order in the comparison; only generated CR/LF separators are excluded. |
| MuPDF through PyMuPDF | 1.28.2 | Exact logical extraction also fails all three pages. This is a different extraction engine from pypdf. |

The source receipt's raster scores are within its declared global tolerance, but pages 1 and 2 have text-box errors of **13.32165457031249 pt** and **13.319065126953149 pt**, respectively. Page 3's **0.002573242187509095 pt** box error does not repair its failed logical extraction. Character multiset equality is explicitly insufficient. The original rejection stays **FAIL**, even where appearance or one geometry dimension looks acceptable.

### Every independently rasterized page inspected

| Page | Visible engineering observation | Limit of the observation |
|---|---|---|
| 1 | Mixed Urdu/Latin body rows, upper-right header and lower-right page field are present. Rows have clear vertical separation and are within the paper bounds at this rendering scale. | Readable-looking outlines do not prove correct marks, joining, copied order or cluster selection geometry. |
| 2 | Continued body text, the small blue-circle resource image, and the historical two-column table diagnostic are visible. Column rules and labels are separated; the image has clear space around it. | Historical table pixels are not production rich-table support. No expert Urdu verdict is made. |
| 3 | The remaining historical table rows, header and page field appear; no obvious paper-edge truncation is visible. The lower unused area is not itself a defect. | This cannot establish text-source coverage; exact source/extraction results remain the oracle for that question. |

PNG paths: `desktop-prototype/local-evidence/output-independent-rejection2-20261019/page-1.png`, `page-2.png`, `page-3.png`. Machine results and raw extracted strings are in that directory's `results.json`; Poppler's exact version output is retained in `poppler-version.txt`. No text reversal, mark removal, normalization or unordered-character comparison was used to turn a failure into a pass.

## Isolated dependency and notice inventory

**PDFBox is not a production dependency selected by this work.** Only the isolated capability proof loaded these four artifacts; their full SHA256 identities and eight LICENSE/NOTICE hashes are retained in its `results.json`.

| Artifact | Version | Pinned SHA256 | Notice scope |
|---|---|---|---|
| `org.apache.pdfbox:pdfbox` | 3.0.5 | `f0e5d3a1e573c707e4fbcc2ee8e42ea8ca1d5261bdcb3a05a08d2118553c1e5a` | Apache 2.0 plus bundled external-component terms; retain the complete LICENSE and NOTICE, not merely the Apache label. |
| `org.apache.pdfbox:fontbox` | 3.0.5 | `e8a62be2df27a0d44191b6669c0b18df6efe5271232db8dcb8745d5d9774755b` | Apache 2.0, original FontBox contribution notice, Unicode Script data and bundled font terms. |
| `org.apache.pdfbox:pdfbox-io` | 3.0.5 | `6df3f3b4db4fd55ef502847ea4e4ebc58e28908800e86eab031345efe219b705` | Complete Apache LICENSE/NOTICE retained. |
| `commons-logging:commons-logging` | 1.3.5 | `6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4` | Complete Apache LICENSE/NOTICE retained. |

PDFBox's copied notices enumerate original PDFBox contributions, Adobe AFM/CMap/glyph material, Unicode bidi-mirroring data, TwelveMonkeys portions and the CC0 CMYK profile. Its full license also carries bundled Lohit OFL and Font Awesome CC BY 4.0 terms. FontBox's full license includes original contribution and Lohit OFL terms. They are included in the retained originals, not reduced to this summary. Optional runtime integrations are not proven loaded merely because upstream supports them. This inventory neither selects the project's license nor approves a redistributable runtime package.

The local Poppler, pypdf and PyMuPDF installations were used as inspection tools. They were not added to the editor's distribution. Installed Python metadata independently reports pypdf 6.16.2 as `BSD-3-Clause` and PyMuPDF 1.28.2 as `Dual Licensed - GNU AFFERO GPL 3.0 or Artifex Commercial License`. The bundled native Poppler manifest identifies Windows x64 package `poppler=26.07.0=h6618ce5_3`; a full redistribution license inventory for that bundle was not established here. Any future packaging must separately preserve their applicable notices and decide the distribution implications; inspection availability alone is not approval to ship them.

### Candidate font identity and embedding boundary

The existing `validation/urdu/u0/fonts.json` and `fonts/OFL.txt` pin the candidates. A fresh independent SHA256 read of all three local binaries matched this manifest during this inspection:

| Font | Version | SHA256 | Declared axes |
|---|---|---|---|
| Noto Nastaliq Urdu | 3.009 | `98a4787f34eb6fde57fb9a1121a8f216301196ab0da98ea110cad581be8abbcd` | `wght` 400–700, default 400 |
| Gulzar | 1.000, revision `[7b34f74]` | `246337973ac64e8b70fe611405dcf32a395a8885c4497dc45dbc4975d3ac0831` | none |
| Noto Sans Arabic | 2.012 | `63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e` | `wdth` 62.5–100 default 100; `wght` 100–900 default 400 |

These binaries have recorded `fsType=0` and existing SIL OFL 1.1 notices. The OFL allows embedding subject to its terms, including retained font licensing and applicable reserved-name conditions for modified derivatives. This does **not** establish that the failed PDF route correctly subsets variable fonts, maps GIDs or represents fallback fonts. Noto Sans Arabic is the defined Naskh fallback specimen, not a substitute for Nastaliq qualification.

## Font fallback: evidence must be labeled accurately

The installed Paragraph API can report unresolved code points/glyph count and the default font. `FontMgr.matchFamilyStyleCharacter` can diagnose a candidate match for an individual code point. That diagnostic is **not the actual Compose resolved glyph-run font map**: itemization, shaping, cluster context, variations and fallback choice can differ. Exact actual fallback-run identities remain **unexposed/unqualified** on this API boundary. The specimen sheet must display this limitation; no claim of exact used fallback bytes is permitted from FontMgr matching alone.

## Human Urdu typography checklist

For each numbered case, record **profile, page, case ID, exact source span, observed issue and suggested correction**. Keep the original binary/font identity. Mark reviewed/correction needed/unavailable; a generated sheet is initially NOT REVIEWED.

1. Inspect ordinary joining and Urdu-versus-Arabic letter distinctions without normalizing the source.
2. Check stacked and leading marks, mark attachment and tails at line and page edges; compare small crops at readable scale.
3. Check Nastaliq slope, rhythm and interline clearance in Noto and Gulzar separately. Distinguish clipping from calligraphic preference.
4. Inspect Persian ZWNJ and explicit joining controls as authored; do not repair appearance by injecting controls or spaces.
5. Read mixed Latin, Urdu digits, decimals, brackets, email and URL order against the numbered exact logical source.
6. Check other-script fallback and missing-glyph boxes. Record a diagnostic match as such; exact resolved run identity remains open.
7. Check list numerals/bullets, continuation text columns and mixed-direction children after any line-metric change.
8. Check page transitions, running fields and image neighbors, including selection/caret behavior in a separate live editing session.

Expert Urdu/calligraphic approval: **NOT RUN**. Genuine OS composing-input qualification: **NOT RUN by this inspection**. Physical printing: **NOT RUN**. PDF/UA, print fidelity and other-platform qualification: **NOT ESTABLISHED**. No ordinary engineering screenshot closes these gates.

## Current numbered typography specimen inspection

**PASS for bounded engineering visual inspection; expert approval remains NOT RUN.** Opened all six PNGs in `desktop-prototype/local-evidence/output-typography-sheet-20261019/`, individually at readable resolution, rather than judging a contact sheet alone. These are mounted renders of actual editor PagePlans, not physical keyboard, native IME or PDF evidence. The parent run identifies focused build `batch-20261019-211136-30424`; the main batch report records its manifest identity and any later superseding build.

| Profile/page | Cases visibly inspected | Engineering observation |
|---|---|---|
| `noto-nastaliq-1.png` | 01–05 | Common Urdu, separate Urdu/Arabic letter forms, stacked/leading marks, authored join controls and the wrapped mixed email/URL line are visibly populated. No obvious paper-edge glyph clipping or inter-row collision. |
| `noto-nastaliq-2.png` | 06–08 | Arabic/Persian/Hebrew/Indic/Thai specimens, isolates, emoji and CJK/Hangul are present. No obvious tofu boxes. The woman-technologist sequence has a visible colored glyph; the regional-indicator pair displays `PK`, not a flag graphic. |
| `gulzar-1.png` | 01–04 and label 05 | Urdu rows and leading/stacked marks have visible clearance. Label 05 is left at the bottom while its example starts on page 2; these specimen labels are ordinary paragraphs without keep-with-next, so this is a review-sheet readability limitation rather than proof of a semantic heading/pagination defect. |
| `gulzar-2.png` | 05–08 | Wrapped mixed-text continuation and other-script/isolates/supplementary examples are visible. No obvious row collision or clipped page edge. Regional indicators again display `PK`. |
| `naskh-1.png` | 01–06 | The deliberately different Naskh fallback profile is visibly flatter and more compact. Leading/stacked marks, mixed line wrap and lower-page other-script line remain within the paper. It is not Nastaliq qualification. |
| `naskh-2.png` | 07–08 | Isolate and supplementary rows have clear surrounding space; `PK` appears rather than a flag graphic. No obvious missing-glyph box or page-edge truncation. |

All eight logical strings and raw UTF-16 arrays are retained in the sheet's `results.json` and rendered review HTML. Visually similar letterforms or accents do not establish normalization or loss; the exact source receipt is the relevant evidence. Conversely, present-looking glyphs do not prove expert mark placement or a correct per-cluster fallback map.

The sheet's FontMgr diagnostic resolves Hebrew to Segoe UI, Devanagari/Bengali to Nirmala UI, Thai to Leelawadee UI, supplementary emoji/indicators to Segoe UI Emoji, Han/kana to Yu Gothic UI and Hangul to Malgun Gothic. These remain **candidate diagnostic families**, not a measured actual Compose glyph-run map or an identified set of embedded output font bytes. The `PK` presentation is explicitly visible rather than hidden by a generic multilingual PASS.

## Main editable sample inspection

Pending the complete sample PNGs. The first focused page test stopped at a test-fixture line-count assumption before sample image generation; that failure is preserved by the parent run. No sample page is marked inspected until its output exists and has been opened.
