# Urdu pages/output — safe pause report

19 October 2026, approximately 21:22 Asia/Karachi. Owner requested a safe pause. Implementation is **incomplete**. All agents stopped; the owned focused runner exited naturally. Computer-use runtime reset. No implementation work should resume until the owner requests it.

## Build and current result

Latest compiled checkpoint: `desktop-prototype/local-evidence/batch-20261019-211620-26988/build`.

SHA256 of `build-identity.json`: `b9458974ce7754a90fd78d64bc5a08bc9d6cb694058d0520dff8914118cd5106` (freshly checked at pause).

Focused runner: **FAIL**, after fourteen passing groups. Owned PID33308 exited1 naturally; total112.5seconds; peak working set810,258,432bytes. The bounded50-page layout exceeded its declared30-second threshold. Do not raise the threshold to hide this regression.

Evidence: `desktop-prototype/local-evidence/output-pages-focused2-20261019/results.json`, `pages-output.log`, `owned-thread-dump.txt`. Ten main sample page PNGs were generated in that directory, but independent inspection of those ten pages is **NOT RUN** at pause. No final integrated suite or live native session has qualified this build.

## Implemented source

- Fixed paper layout in96 document units/inch; output points72/inch. Screen density, fit-width and zoom are presentation transforms. Captured editor line plans remain stable across continuous/paged mode and the tested50/75/100percent zoom/viewport cases.
- Semantic explicit page breaks distinct from ordinary hard paragraphs and authored soft line breaks. Explicit breaks retain raw U+000C; hard paragraphs retain LF; provisional native text is not converted into committed structure.
- Paragraph break-before, keep-with-next, keep-lines, widows/orphans, existing half-open/backward paragraph targeting and undoable commands.
- Separate document-owned header/footer flows with uniform supported style, direction/alignment, page/page-total fields, selectable digit families and different-first-page visibility. Running content is not appended to body text.
- Immutable actual-editor PagePlan with coverage checks, measured running-content reservations, bounded field stabilization, layout notices and explicit oversized/unsatisfied-layout errors.
- Private document codec9 with readers1–8; rich fragment5 with readers1–4. Older files receive documented defaults. Body clipboard fragments retain break/rule metadata while the destination owns document-level running content.
- Captured original Skia page pictures, preview rasterization and Java printing adapter. Bounded capture yields the UI thread; cancellation and picture/resource ownership are tested. Printing supports ranges and explicit actual-size/printable-area fit. This is **not semantic PDF export**.
- Pages/Output controls, an unsaved mixed-script10-chapter sample and a numbered three-font typography review sheet.
- Independent Python decoder/oracle and bounded process runner updated for the expanded document model.

No new application PDF dependency was introduced. Existing recovery, quarantine and workspace features remain in source; final integrated preservation tests for this checkpoint remain pending.

## Fourteen passing mounted groups

These executed before the performance failure; they do not make the overall run PASS:

1. Document/output units independent of display density and zoom.
2. Explicit page-break separator, exact Undo/Redo and plain form-feed representation.
3. Half-open/backward paragraph targeting and split inheritance.
4. Codec9 raw text, attributes, breaks, flow identities and resources through Save and exact committed recovery.
5. Rich clipboard break/rule preservation and destination running-content ownership.
6. Running-content history/removal and fresh clone identities.
7. Synthetic composition settlement for pagination commands.
8. Malformed output Unicode refusal with exact offsets while source stays raw.
9. Actual captured10-page specimen and complete line/source coverage.
10. Seven independently constructed keep/widow/oversize/explicit-break placement expectations.
11. Canonical line/page plan invariance across continuous/paged mode and six tested view/zoom combinations.
12. Immutable picture/raster capture, requested paper, callback reorder/repeat/cancel and ranges.
13. Preview capture responsiveness and cancellation preserving content/history.
14. Page-field stabilization at9→10 and99→100 without document/history mutation.

The fifteenth group, bounded50-page performance, **FAILS**. Its remaining downstream capture/raster assertions are not PASS.

## Independent exact sample checks

An earlier compiled checkpoint211136 produced a codec9 sample independently decoded as **PASS**:3970UTF16 units,64paragraphs,10headings,20list paragraphs,9explicit breaks,2images sharing1resource. Exact text, inline fonts, paragraph/list properties, running fields, image dimensions and resource checksums were checked. Seven deliberate mutants were rejected: missing mark, reversed mixed order, missing explicit break, lost keep rule, wrong running field, omitted image and lost font.

Evidence: `output-pages-focused-20261019/independent-oracle.json` and `independent-sample.astra`. Sample file SHA256 `8e5830af21bf81ce4ba8ee93e6479fe8761ed298e80bf4475144fec04c4124f6`; build manifest `be5080bfcb946e85f36f9db9d82fe6980564a3bc5038c57620a13caeb2dd8dd6`. This is deliberately identified as earlier-build evidence; the final same-build oracle still must run. Independent hand-builtv6/v7/v8/v9 and historical artifacts also passed in `pages-v9-decoder-independent-20261019/results.json`.

## PDF connection gate — BLOCKED

Two bounded routes were evaluated, rather than expanding a rejected export:

1. Installed Skiko0.150.1 paragraph binding exposes neither the actual paragraph positioned glyph/cluster stream nor the required PDF Document binding.
2. Isolated PDFBox3.0.5 can embed the font, but cannot supply the missing editor run data. Independent reshaping still fails the unchanged narrow mixed-script witness: expected line boundary26, observed27;9.880005-unit error against0.25 target.

The old rejected vector/invisible-text diagnostic was rerun unchanged for independent checks. pypdf6.16.2 and PyMuPDF1.28.2 both fail exact text on all three pages; Poppler rendering can look plausible while geometry differs by about13.32points on the affected pages. No normalization, reversal or removed marks were used to disguise failure. That PDF is diagnostic evidence, **not a qualified deliverable**.

Evidence directories: `output-bridge-proof-20261019`, `output-glyph-recheck-20261019`, `output-gp0-recheck-20261019`, `output-independent-rejection2-20261019`. Dependency notices/hashes and full explanation: `docs/reports/v6-output-independent-inspection-20261019.md`.

## Typography inspection

Six actual-editor specimen PNGs (two each for Noto Nastaliq Urdu3.009, Gulzar1.000 and Noto Sans Arabic2.012 as the Naskh comparison) were individually inspected at readable resolution. No obvious page-edge clipping, row collision or tofu was observed. This is bounded engineering inspection, not expert Urdu approval.

Known observations: regional indicators display `PK` rather than a flag; Gulzar case05 label was stranded on the preceding page. A keep-with-next source change for labels is included in the later build, but a regenerated sheet and reinspection remain pending. FontMgr reports diagnostic candidate fallback families; actual Compose glyph-run font identities remain unexposed.

Sheet: `desktop-prototype/local-evidence/output-typography-sheet-20261019/index.html`. Its six screenshots belong to build211136, not the latest build. Genuine OS IME, expert typography, other DPI/platform, full accessibility and physical printing remain NOT RUN/unqualified for this batch.

## Actual defects and outstanding repairs

Fixed and regressed: keep chains could strand a heading before a successor that must remain whole; short successors without a valid widow/orphan split had the same problem. Planner tests now use independently specified metrics instead of an unjustified font line-height assumption. Image gesture expected values were corrected to the explicit physical fit transform:40screen pixels represent42document units in the identified landscape fixture.

Confirmed unresolved product defect: repeated list-root/group/paragraph scans on the EDT make the50-page case exceed30seconds. The owned thread dump places the hot path in `paragraphs → listRoot → DesktopEditor.measureWith.rootRtl/group/listColumn`. Next fix should precompute paragraph/item/root/group/depth/opposite-direction metadata once per measure, preserving marker geometry. No fix has been made yet.

Other identified work still pending: document key handling can consume Ctrl+Enter as ordinary Enter before the root binding; accessibility visible-data notifications should include page/running changes; count labels should explicitly describe body scope. These must be patched and tested, not marked complete. Exact actual fallback-run font identity is a capability limitation, not solved by adding requested-font labels.

## Exact restart

1. Read this report and newest handoff; inspect source/git and verify build inputs before reuse. Preserve prior dirty changes and raw failures.
2. Fix the measured repeated-list-scan regression with cached per-measure metadata; retain the30-second limit and rerun focused checks. Complete pending key/accessibility/body-count fixes.
3. Produce a fresh build and run `python desktop-prototype/verify.py --pages-output-milestone`; run the independent bounded output runner/oracle on that same build.
4. Regenerate the editable sample and typography sheet; inspect every main page and all changed specimen pages. Do not reuse old screenshots as final-build qualification.
5. Run bounded native ordinary/modal interaction first using currently supported controls. Continue page editing, headers/footers, preview/cancel, save/reopen and available print-dialog checks only while targeting works. Do not call prior modal-cache failure a new PASS; stop control promptly if blocked.
6. Update WRITING/RECOVERY/current batch report with actual evidence, compatibility and honest remaining PDF/native gates. No third output architecture or unrelated feature batch.

Development sample launch after resumption: `.\desktop-prototype\write-urdu.cmd --pages-sample`. This opens an unsaved copy; the current checkpoint is compiled but not delivery-qualified. No launch or UI interaction was performed while pausing.

No user documents or unresolved records were deleted, no frozen specifications changed by this pause, and no publish/push/tag/install/license action was taken. Previous valid workspace evidence remains historical and separate from current pages/output qualification.
