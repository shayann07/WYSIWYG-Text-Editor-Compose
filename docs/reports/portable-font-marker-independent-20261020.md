# Independent portable-font and marker inspection — 20 October 2026

This supplement owns a read-only visual/API inspection. It does not establish native interaction, expert Urdu typography, semantic PDF, printing or a new production dependency. Current agent instructions and the migration's module boundaries apply. The main batch report owns production changes, the final build identity and final integration/native results.

## Scope and preserved starting evidence

Individually opened all ten fresh PNGs in `tests/local-evidence/desktop/independent-pages-audit-20261020/`. Its `pages-output.json` records 15 mounted groups PASS, ten sample pages and 3,970 UTF-16 units. Those receipts are mounted/synthetic evidence, not native clicks or expert approval. No production source or build was changed by this inspection.

Exact before pages 1 and 6 and enlarged number/bullet crops are preserved in `tests/local-evidence/desktop/portable-font-marker-independent-20261020/`. Crops are ordinary coordinate crops of the original PNGs; 4× copies use nearest-neighbor scaling and do not retouch glyphs. Original page bytes remain intact. Coordinates below use the 794×1123 original raster, with downward-positive y and half-open pixel boxes.

## Independent starting-point visual findings

**FAIL: Gulzar marker placement; Noto reference retained.** The issue is visible in the actual rendered specimen, despite the mounted suite's overall PASS. The marker is present, with a stable horizontal lane and apparent size, but its vertical relationship to its neighbor is wrong under Gulzar.

| Pages inspected | Observed result |
|---|---|
| Noto 1 | Number at the Urdu item's right and bullet at the English child's left look balanced beside their respective text. Child remains visibly LTR. No body/running-content collision observed. |
| Noto 2 | Same marker behavior; embedded blue illustration is present below the child, with clear surrounding space. Running header/footer are visible. |
| Noto 3–5 | Same marker relationship and direction policy. No obvious page-edge clipping, missing marker or row collision observed. |
| Gulzar 6 | Numeral sits visibly low beside the Urdu text. The English bullet sits near the text's baseline/descender zone instead of its lowercase visual middle. The child itself remains LTR and on the left. |
| Gulzar 7 | Same low-marker defect; illustration remains present with clear space. |
| Gulzar 8–10 | Same low numeral and bullet relationship. Header/footer remain separate from body. Page 10's longer heading and mixed-text number do not visibly collide. |

These observations do not sign off joining or calligraphic quality. Differing Nastaliq shape/slope is not itself a bug. The considerable white region below each short sample chapter is not clipping or lost content merely because the page is not full.

### Raster observations independent of layout formulas

The retained `before-ink-observations.json` measures dark pixels (`max(R,G,B) < 100`) inside explicitly declared, disjoint crop regions. This is an observation of visible ink, **not a reconstruction of the implementation's baseline formula**. Whole-word ink bounds include ascenders and descenders and cannot define a Nastaliq baseline.

| Region | Noto page 1 y box | Gulzar page 6 y box |
|---|---|---|
| Bullet | `[628,633)` | `[618,624)` |
| Neighboring English word, including descenders | `[620,645)` | `[599,624)` |
| A narrow lowercase `n` crop | `[625,639)` | `[606,618)` |
| Number marker | `[555,572)` | `[540,557)` |
| Neighboring Urdu ink, including marks/descenders | `[548,584)` | `[521,556)` |

The bullet's center is approximately 1.5 pixels above the lowercase crop's center under Noto, but 9 pixels below it under Gulzar. One-pixel antialiasing/bounding differences do not explain that change. This discriminates the reported defect without assuming the editor's line-metric implementation is correct. Number-marker versus Urdu whole-ink bounds alone do not prove a baseline; the enlarged crop independently shows the low relationship, while actual typographic baseline diagnostics belong to the production investigation.

Before crops:

- `before-page-1-number.png` and `before-page-1-number-4x.png`
- `before-page-6-number.png` and `before-page-6-number-4x.png`
- `before-page-1-bullet.png` and `before-page-1-bullet-4x.png`
- `before-page-6-bullet.png` and `before-page-6-bullet-4x.png`

### Expected repair outcomes, stated before the fix

1. Numerals follow the first rendered line's typographic baseline. They must not align to paragraph top, multiline center, lowest Urdu ink point or bullet center. Stacked marks and Nastaliq descenders do not redefine that baseline.
2. A bullet uses optical alignment relative to the first line's relevant text typography. For the Latin child, it should sit around the lowercase visual middle, as the Noto reference does, rather than descend when the surrounding profile changes to Gulzar. This is a separate policy from numeral baseline alignment.
3. Preserve the approved Noto relationship, marker size, consistent text gap and shared sibling lane. The repair must not move body text, change list semantics, switch a child's text direction, inject authored marker characters or let first-character formatting accidentally select the marker's metrics.
4. Wrapped lines and continuation paragraphs keep the item text column; markers appear only at the item start. Multi-digit/restart numbering needs measured width and must not steal the text gap.
5. Geometry, pointer mapping, selection, hit testing and pagination must share the resulting placement policy. Merely moving the paint operation is insufficient if consumers retain the old transform.

Exact parent-owned geometry tests and final native screenshots are still needed. These independent raster expectations do not replace caret or event-handler verification.

## Bounded semantic-PDF capability refresh

**BLOCKED: the current matching stable pair does not expose the missing actual shaped-run/PDF boundary.** No upgrade, native fork, alternative pipeline or new runtime dependency was introduced.

The repository catalogue already pins Compose **1.12.2** and Skiko **0.150.2**. The freshly fetched official `ui-desktop:1.12.2` POM explicitly declares `org.jetbrains.skiko:skiko-awt:0.150.2`, so this is a matching published pair, not an arbitrary forced Skiko combination. JetBrains' release pages identify the respective stable releases. [Compose 1.12.2 release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.2), [Skiko 0.150.2 release](https://github.com/JetBrains/skiko/releases/tag/v0.150.2), [official Compose POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.12.2/ui-desktop-1.12.2.pom).

### Latest stable matching pair checked, not merely the repository pin

On 20 October 2026, freshly fetched [official Maven Central UI Desktop metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/maven-metadata.xml) reports `latest`/`release` as `1.13.0-alpha02`; that is a preview, not a stable release. Selecting only unsuffixed numeric `major.minor.patch` published versions and comparing numeric tuples yields **1.12.2 as the newest stable UI Desktop**. Its actual POM declares Skiko **0.150.2**. Thus the nearest current/latest-stable pairs are the **same pair**, and the installed artifact/API inspection below covers both. No newer stable matching pair was found to inspect or propose adopting; this conclusion does not rely on assuming the current pin is latest.

The fetched metadata has `lastUpdated=20261015103102` and SHA256 `574be5ee154c4ed3a626d2f9a2eebb15b36becc9e806a4a44a886b7c30f863c1`. Original XML is retained as `official-ui-desktop-maven-metadata.xml`; the explicit stable-version criterion, chosen pair and parsed POM dependency are retained in `official-latest-stable-pair.json`. No preview adoption, independent Skiko bump or dependency-file edit was performed.

Fresh official downloads and hashes are retained in `official-fetches.json`:

| Artifact/source | SHA256 |
|---|---|
| Official `ui-desktop-1.12.2.pom` | `bb6eb80c4b18fe68ce7e662964e979ba3b06dc9b920f949499250c81561d89c0` |
| Official `skiko-awt-0.150.2.pom` | `8aec0979b6a5241d9b6577ad5119dc5f9162a592f2da43ce0f0e8498ab3ee1f0` |
| Tagged `Paragraph.kt`, `v0.150.2` | `68bf1bd0c68183b1962ac29502fde75087a0917e542bd78b79eec7cba2a97218` |
| Actual installed `skiko-awt-0.150.2.jar` | `6b36a9d9a931f5b4aedc3722ae9e769d13e945a8f18bd480340e625ea10e0171` |

`javap -public` was executed against that actual installed jar for `Paragraph`, `TextBlob`, `Canvas` and `Picture`; raw output is retained in `installed-skiko-public-api.txt`. Complete jar entries are retained in `installed-skiko-entries.txt`. This was artifact inspection, not a rebuild or a runtime PDF proof.

- `Paragraph` exposes line metrics, range rectangles, hit testing and painting, but no actual shaped glyph visitor/run export or Paragraph-to-TextBlob accessor.
- `TextBlob` has glyph, 2D-position and cluster accessors. Their presence does not give access to the editor Paragraph's actual blobs, font choices and source cluster mapping.
- The inspected jar has no `org.jetbrains.skia.Document` or PDF packaging class. Painting a Paragraph into a Picture/Canvas does not establish semantic PDF text, fallback identity, GID mapping or extraction order.
- The tagged official Paragraph source agrees with the inspected public API. Source and metadata snapshots are pinned to this release, rather than current-main documentation. [Tagged Paragraph source](https://raw.githubusercontent.com/JetBrains/skiko/v0.150.2/skiko/src/commonMain/kotlin/org/jetbrains/skia/paragraph/Paragraph.kt).

The previous 0.150.1 reshaping/PDF failures remain historical counterexamples; this refresh does **not** relabel them as new execution on 0.150.2. The current pair's missing public capability is independently established by actual artifact inspection. No semantic PDF is produced or qualified here. A future change needs its own discriminating actual-layout/text/cluster proof, independent extractors and explicit dependency authorization where applicable.

## First repair candidate — preserved failure

Opened all ten newly generated pages under `tests/local-evidence/desktop/pages-position-after-20261020/`, and saved matching after crops beside the original before crops. An exact full-page RGB comparison, without tolerance or normalization, is retained in `before-after-pixel-diff.json` with one mask PNG per page.

| Check | Actual result |
|---|---|
| Gulzar 6–10 visible marker placement | Improved: numeral ink shifts approximately 10 pixels upward, from `[540,557)` to `[530,547)`; bullet shifts from `[618,624)` to `[608,614)`. The enlarged crops now place the bullet near the neighboring Latin lowercase middle. |
| Noto 1–5 exact preservation | **FAIL**: each page differs in 88 pixels. Noto marker ink shifts approximately one pixel down: numeral `[555,572)` → `[556,573)`, bullet `[628,633)` → `[629,634)`. Do not call these images identical merely because they look close. |
| Difference containment | On all ten pages, zero changed pixels fall outside the declared marker regions `[708,500,738,590)` and `[47,580,65,649)`. Body text, headings, running content and images remain pixel-exact. |
| SHA256 preservation | All ten PNG byte hashes differ; exact hashes remain in the machine receipt. |

Each Gulzar page changes 216 pixels. Full-page difference bounds are `(52,554,731,635)` for Noto and `(52,529,731,624)` for Gulzar; those are enclosing boxes around two separated marker regions, not proof that intervening body pixels changed.

The parent identified the transform defect: body paint removes a paragraph's `sourceTop`, whereas marker painting previously omitted that subtraction. Applying the raw value also moved Noto because its measured first-line top was −1 from floating-point/rounding behavior, rather than positive removed leading. This first candidate is retained as a failed exact-Noto-preservation check. A subsequent candidate will be inspected separately; this report does not mark it final.

## Second repair candidate — exact preservation confirmed

**PASS for this independent ten-page visual comparison.** Individually opened all ten pages under `tests/local-evidence/desktop/pages-position-after2-20261020/` and the four corresponding enlarged marker crops. The second candidate applies the translation only for positive removed leading; the parent's implementation investigation distinguishes this from negative first-line-top rounding/ink bleed. No font-name-specific offset or typography/default change was requested or inferred here.

| Requirement | Independent result |
|---|---|
| Approved Noto pages 1–5 | **PASS: every PNG is byte-for-byte SHA256 identical and every RGB pixel identical** to the original independent audit. Zero changed pixels on all five pages. |
| Gulzar pages 6–10 | **PASS for the observed repair: 216 changed pixels per page**, all inside the two predeclared marker regions; zero changed pixels elsewhere. |
| Body/running content/resources | Pixel-exact on every page. No heading, body text, image, link appearance or header/footer change is hidden by a whole-page tolerance. |
| Rendered marker relationships | Gulzar numeral is visibly raised into the neighboring first-line relationship; bullet is visibly centered near Latin lowercase ink instead of the descender zone. Noto is unchanged. LTR children remain LTR. |
| Other page geometry | No new visible clipping, overlap or missing marker observed across the ten pages. This is bounded inspection, not general typography proof. |

Machine receipt: `before-after2-pixel-diff.json`. Exact masks: `page-1-after2-exact-diff-mask.png` through `page-10-after2-exact-diff-mask.png`. Matched preserved crops use `after2-page-1-*` and `after2-page-6-*`; neither the original failure nor the first repair's failed Noto-preservation evidence was overwritten. For Gulzar the corrected raster matches the first candidate, while Noto reverts to exact original bytes.

The intermediate page-generating test process subsequently failed its seventeenth generalized raster-test group. That historical failure remains preserved; it was not converted into a PASS by this visual inspection. The later final-build results below supersede its current status after the parent repaired the regression expectations.

## Final build linkage — confirmed 20 October 2026

Build: `tests/local-evidence/desktop/batch-20261020-151429-34416/build`. A fresh SHA256 read of `build-identity.json` matches the expected manifest **`c32ba3749a11b480e5d7ef3c2a4f71a07694fd61acf45a416271f1c3c9619cbc`**.

Every one of the final run's ten `output-page-N.png` files is **byte-for-byte SHA256 identical** to the corresponding after2 image already opened and inspected above. The visual inspection is therefore reused by exact artifact identity, without substituting a different rendering. Noto 1–5 also remain byte-identical to the original approved audit. Gulzar 6–10 retain the demonstrated marker-only differences; all other pixels remain unchanged.

Fresh mechanical receipt: `tests/local-evidence/desktop/portable-font-marker-independent-20261020/final-build-page-identity.json`, including every page SHA256 and all comparisons. Current `pages-output.json` reports **18 groups PASS**, including **432 multilingual marker-position rows**. Current `file-focus.json` reports **8 mounted focus-handler groups PASS**, including cancel/success/failure, existing focused control, newly active window, disposed owner, owned modal and resolving the current rather than stale surface.

These current stage receipts close the previously pending generalized marker-test status. They do not, by themselves, claim the entire integrated campaign, actual native interaction, OS composition or physical printing passed. The parent owns those final dispositions and any later build changes.

## Remaining qualification

Final native interaction, complete integrated regressions and source/history preservation are parent-owned. Exact inspected-page linkage to the identified final build is established above. Expert Urdu review, genuine native composing input, physical print, PDF/UA and other platforms/DPI remain separate open gates. If later source changes affect rendering, repeat or mechanically verify the same page artifacts; do not combine these mounted pages with an incompatible native build into one PASS.

## Final quality-pass build linkage — supersedes 15:14 build

The private Compose naming cleanup produced `tests/local-evidence/desktop/batch-20261020-154842-11676/build`, manifest SHA256 **`c86ea5a1245aba87267cec28a085acc8c900bc475c85f6b341fa5483a78a79b7`**. The old `c32ba374…` receipt remains preserved; the new independent receipt is `final-build-page-identity-154842.json`.

All ten new page PNGs are again byte-identical to the inspected after2 pages, and Noto 1–5 remain byte-identical to the original approved audit. Current pages/output results retain **18 groups PASS / 432 marker rows**. The full campaign/native results are still parent-owned.

An exact artifact comparison against the native-reference build `portable-product-delivery-build-20261020/build` (manifest `51e97e9140b404f0138604f2c934ecc0e663c2d36d188c40ec465e0af150002e`) finds:

- **Zero changed dependency library files.**
- Input SHA changes only in `core/ui/.../EditorCanvas.kt`, `tests/desktop-harness/.../PagesOutputChecks.kt` and `tests/desktop-harness/.../PreviewLabelChecks.kt`.
- Changed production class bytes are `EditorCanvasKt`, its `drawDocument$1` class, and the same source file's `ListGeometry`, `MeasureEnvironment`, `PageFrame` and `ParagraphLayouter` classes. Changed test artifacts are the desktop-harness Kotlin module metadata, `PagesOutputRun`, its `checkFiftyPageWorkload$3$b$1` helper and `PreviewLabelChecksKt`.
- `javap -c -p` output is **identical** for `drawDocument$1`, `ListGeometry`, `MeasureEnvironment`, `PageFrame` and `ParagraphLayouter`; their differing bytes do not imply differing printed instructions/private signatures. This comparison omits debug/annotation/Kotlin metadata and is labeled accordingly.
- `EditorCanvasKt` differences are the private `ZoomedDocumentCanvas` → `zoomedDocumentCanvas` name/call-site rename, Compose-generated restart/remember keys, source-information strings and trace labels/line locations. No changed marker-paint instructions were observed in that printed diff. The Compose-generated key changes still justify a bounded affected-renderer native check; exact page bytes do not prove every live composition/focus behavior.

Exact class/input lists are in `final-build-page-identity-154842.json`; instruction comparisons in `final-vs-native-production-javap.json` and the per-class `*-javap-diff.txt` files. IO/workspace/codec/controller classes and dependency bytes are unchanged by this comparison, supporting a narrowly scoped parent decision to reuse unaffected native evidence. It does not silently relabel the prior native run as execution of the new build.
