# Next big batch — Urdu typography, editable pages, semantic PDF and printing

Prepared 19 October 2026. This is a queued assignment, not permission to interrupt the current workspace completion session. Start only after the owner sends this prompt following that batch. Re-read the latest handoff: do not assume today's build paths or statuses remain current.

## Mission and scope

Deliver a useful desktop Urdu document-to-output workflow: reliable Nastaliq typography; editable pages with explicit breaks, paragraph pagination controls, basic running headers/footers and page numbers; PDF export with correct visible text and usable search/copy/selection; and printing from the same qualified layout. Reuse existing libraries and the working editor. Do not build another Unicode shaper, bidi algorithm, PDF file-format engine or whole editor.

This is a staged implementation batch with an early feasibility gate, not another open-ended research project. Complete independent typography/page work if a PDF mechanism is blocked; report the blocked output capability honestly. A PDF-shaped file or a screenshot of a page does not close the gate. Do not call the whole batch complete unless its required outcomes are actually demonstrated.

### Read first

Work in `D:\Work\WYSIWYG-Text-Editor-Compose`. Read AGENTS.md, docs/project/PROJECT-JOURNAL.md, PRODUCT-ROADMAP.md and URDU-ACCEPTANCE.md in docs/project/, the latest handoff, the workspace batch report, and the relevant finalized V6 sections, especially D05–D09. Then read:

- `docs/reports/Editor library reuse decision.md` (historical leads, narrowed by final D07).
- `validation/pagination/gp0/README.md`.
- `validation/pagination/pdf_outline/README.md`.
- `validation/pagination/glyph_clusters/README.md`.
- The pinned reuse ledger under `research/v6-reuse-20261019/` and supplemental evidence under `reviews/v6-finalization-20261019/upstream/`.
- Existing page, typography, image, recovery and workspace implementation/tests.

Inspect Git status, concurrent work, actual source/build identities and the previous batch's remaining issues. Preserve user files, frozen manifests and historical failed experiments. Do not message or interrupt other sessions. If a critical prior correctness defect remains, repair the prerequisite first. A computer-tool blocker does not automatically authorize downgrading live verification.

Keep library-capable data/commands/layout/output adapters separate from desktop chrome. The open-source library and Urdu-first full application remain the goals; no public API/schema freeze or all-platform qualification is implied. Private codec changes are authorized when necessary, with backward readers and explicit migration. Frozen specification changes require a demonstrated contradiction and versioned amendment.

## Stage 1 — Establish the output route before expanding it

Reuse the existing failed fixtures and record a compact candidate decision. Evaluate at most two concrete backend/bridge routes initially, each with a stated mechanism, exact callable APIs, pinned version/source, compiler/native packaging requirements, license/NOTICE/font-rights inventory and a small runnable proof. Do not spend the batch collecting library names or repeating the rejected hidden-text variants.

Candidate leads, not approved dependencies:

- Existing Compose/Skiko/Skia for layout and rendering. Native Skia PDF support does not imply an exposed Kotlin binding on our pinned runtime or other targets.
- Apache PDFBox for JVM PDF packaging/fonts/content/inspection and a possible print adapter. Its high-level text methods are not proof of a positioned-glyph Urdu bridge.
- PdfKmp for compatible packaging/non-text primitives; its inspected String-based rendering must not independently rewrap/reshape PagePlan text without proven parity.
- Previously inspected KitePDF or other existing engines only if a concrete capability addresses the observed gap. Prefer the smallest working bridge over swapping the rendering stack.

Verify primary documentation and exact installed/pinned artifact signatures. Do not invent GID APIs or globally upgrade Kotlin/Compose to make a README fit. New dependencies must build in an isolated capability slice before integration. Record direct/transitive notices and font embedding/subsetting conditions without choosing the project's license. Locally useful inspection tools are not automatically approved runtime dependencies.

The first proof must use the actual editor's mixed Urdu/Latin lines, combining marks, ligatures and fallback fonts. Produce a `ShapedExportRun` or equivalent boundary with font bytes/variation identity, glyph IDs, 2D positions, cluster/source ranges and logical reading order. Obtain the editor's actual shaped output, or demonstrate a narrowly defined equivalent route against its line breaks, advances, ink and cluster geometry. Independent RunHandler shaping already failed a narrow mixed-script case; rerun that witness before claiming equivalence. A width-only match is insufficient.

If an essential binding is missing, a small isolated adapter/binding feasibility proof is allowed. Do not start a broad native fork or custom font/PDF writer. If neither candidate passes, retain counterexamples and identify the exact missing capability; finish independent typography and pagination delivery. No infinite search, fake semantic layer, raster-only substitution or promise that a future post-pass will fix extraction.

Primary reference leads checked when this assignment was prepared:

- PDFBox 3 migration/API changes and partial complex-script support: https://pdfbox.apache.org/3.0/migration.html
- Native Skia PDF backend: https://skia.org/docs/user/sample/pdf/
- Java 23 PrinterJob: https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/print/PrinterJob.html

These describe capabilities, not a verified Urdu integration or guaranteed availability in our Kotlin bindings. Refresh against the exact chosen release.

## Stage 2 — Urdu typography and writing behavior

Use the existing Urdu corpus and pin actual font bytes, versions, variation axes, features, fallback order and profile identifiers. Reuse existing Noto Nastaliq/Gulzar candidates where their local evidence and rights permit; do not install OS fonts or replace the default silently. Evaluate at least two existing redistributable Nastaliq candidates and a defined fallback, reusing prior coverage instead of starting over.

Fix reproducible defects involving joining, Urdu letter distinctions, stacked/leading marks, ascenders/descenders, line clipping, mixed sizes, actual font fallback, lists, links and image neighbors. Preserve raw text and constituent attribution. Do not normalize Arabic/Urdu letters, insert bidi controls/spaces, or append Tatweel to make screenshots look better. Do not introduce unqualified kashida/full justification; retain correct Start/Center/End behavior and track advanced justification separately.

Line-height/ink policy must protect real Nastaliq overhang without huge arbitrary padding or clipping adjacent lines. Document how empty lines, large inline runs, paragraph spacing and page edges are measured. Stable list marker typography and shared sibling lanes must survive these changes; legitimate baseline changes follow line metrics.

Test independent expected editing outcomes for Urdu, Arabic marks, Persian ZWNJ, mixed Latin/digits/punctuation/URLs, isolates, leading marks, combining edits, emoji/non-BMP and malformed UTF-16. Compare caret/selection using allowed bidi/ligature associations, not a universal exact hit-test inverse. Exercise real Urdu keyboard input where configured, plus honest synthetic composition traces. Do not retry unavailable Japanese setup or call pasted Urdu an OS IME test.

Produce a numbered typography specimen sheet with reference strings, font/profile and issue annotations for owner/proficient-reader review. AI visual inspection can identify clipping and geometry; it must not sign off expert calligraphic/linguistic quality. That external review remains an explicit gate and does not block independent engineering work.

## Stage 3 — One page plan for editing and output

Reuse current persisted page settings and fragment planner. Build a stable immutable PagePlan for a captured document revision, page settings and exact font/resource profile. Include page boxes, content boxes, fragment source ranges, original paragraph offsets, transforms, object geometry and running-field placements. Output must consume that plan, not independently lay out the document again.

- Preserve whole-paragraph shaping/bidi context when fragmenting across pages. Never mutate/split canonical paragraphs merely because they cross a page or independently reshape substrings at boundaries.
- Account for every body source range exactly once; repeated header/footer content is explicitly associated with its own flow and page instance. No dropped/doubled lines, phantom blank pages, repeated body markers or lost images.
- Keep screen density/zoom/scroll separate from document units and PDF points (72 points/inch). Define conversions explicitly; 125% Windows scale must not change paper geometry. Font size unit conversion must match the established document contract.
- Retain A4/Letter, orientation and margins. Invalid or impossible content boxes must produce actionable validation, not an infinite layout loop or silent overlap.
- Add explicit page break insertion/removal through semantic commands; distinguish page break, hard paragraph and soft line break. Add paragraph page-break-before, keep-with-next, keep-lines-together and widow/orphan controls with documented defaults and undoable persistence.
- Define deterministic priority when constraints cannot all fit. Bound keep chains, guarantee forward progress, split an oversized paragraph when necessary under a declared policy, and expose any relaxed constraint. Never shrink text silently to satisfy a page.
- Preserve image aspect and current requested-versus-fitted semantics; reflow large images and list continuation paragraphs without changing canonical data.
- Keep logical caret/selection/scroll anchors stable through edits, page resizing, image/font arrival, zoom and Undo. Pointer selection, keyboard navigation, caret reveal and synthetic/native composition queries must use the current fragment transforms exactly once.

### Basic running content

Implement one document-level header and footer with simple supported formatting and page-number / total-page fields, including a different-first-page option and user-visible numbering format choice. Reuse canonical flow/identity contracts. Running content is not appended into the body or represented only by decorative pixels. Provide a clear editing UI, history and Save/Open/recovery behavior. Field evaluation is derived and cannot create an undo entry on every repagination.

Reserve a deterministic running-content region. Handle over-height content explicitly; stabilize page count/field width without an unbounded repagination loop. Test transitions 9→10 and 99→100 using bounded generated fixtures. Do not silently implement section/header inheritance, footnotes, columns or a full table engine in this batch.

The complete roadmap GP0 includes a rich table spanning pages. Reuse its existing diagnostic fixture and report table coverage separately. If production rich tables/flows are not implemented, this is a qualified body/list/image/running-content slice only; full GP0 and table pagination remain open. Do not fake a table with text, a picture or hidden test-only metadata and declare the gate passed.

## Stage 4 — Semantic PDF export

Integrate the Stage 1 route only after its discriminating proof passes. Add Export PDF, page-range/all-pages selection, meaningful progress/cancel and a safe save dialog. Capture a single document/font/resource/PagePlan snapshot; later editing cannot mix revisions into the export. Offload supported heavy work without violating renderer/thread-affinity contracts. Failed/cancelled output must not overwrite an existing destination or leave a corrupt file presented as complete.

Ordinary output uses accepted committed content only. Explicitly disclose active unfinished input and allow cancellation/settlement as already qualified. No silent commit and no provisional data leak. A separate draft export, if offered, must be explicitly selected and labeled and must not alter canonical content.

Embed/subset permitted fonts through the existing backend. Preserve GID/subset mapping, actual fallback font identity, glyph positions and many-to-many Unicode clusters. Enumerate supported TrueType/CFF/variable/color-font cases; reject unsupported required output with a clear report instead of substituting a different appearance silently. Missing-font fallback may only be used under a declared output profile that the user can preview consistently.

Preserve readable/searchable/selectable logical text, diacritics, punctuation, mixed-script order, images and link annotations. Check header/footer/body/field reading order explicitly. ToUnicode/ActualText/tagging are mechanisms, not automatic proof. Do not claim PDF/UA, PDF/A or universal reader equivalence without those validations.

Canonical raw UTF-16 may contain lone surrogates that cannot be represented as valid Unicode output. Keep the source unchanged; follow the finalized conversion policy with exact offsets and diagnostics. If no applicable output policy is defined, refuse that output and offer a clear diagnostic rather than silently replacing data or weakening the source contract. Document generated layout separators separately from authored text.

## Stage 5 — Print preview and printing

Use the same captured plan/output snapshot. Add print preview, supported page ranges, orientation/paper information, explicit actual-size versus fit-to-printable-area behavior and visible warnings for printer margin/paper mismatch. Preserve pagination unless the user explicitly changes document page settings; scaling for a device must be disclosed and consistent across pages.

Reuse the platform print system and a qualified backend adapter, such as existing Java printing/PDF backend facilities where they actually work. Test page callback reordering/repetition, cancellation, unsupported service and failure without mutating the document. Job submission is not proof paper emerged correctly.

Use an already installed virtual printer for an end-to-end OS print-path check where available, saving into owned evidence directories. Do not install printer drivers or change the default printer. Do not consume paper/ink or send a job to a physical/network printer without explicit owner authorization for that job. If no suitable service is available, implement and validate available paths, leave real spool/device checks BLOCKED and provide a concise manual checklist. Virtual printing, direct PDF export and physical printer qualification are three distinct claims.

## Strict verification and real visual testing

Define tolerances and expected outcomes before measuring. Reuse the established <=0.25pt geometry target for the qualified fixture where applicable; do not inflate it after a mismatch. Separate extraction order, source text, cluster selection geometry, page/line geometry and appearance. Antialiasing differences may use declared raster tolerances but must not hide missing marks or wrong glyphs. Compare sensitive word/mark/image crops, not only a global pixel average.

Create one substantive 8–12 page self-authored/licensed Urdu specimen with mixed English/numbers, long Nastaliq paragraphs, marks/joiners, lists, headings, links, images, page breaks, widow/keep cases and running fields. Include smaller hand-verifiable edge fixtures. The sample must remain an unsaved disposable copy. Existing 200-fixture Urdu coverage is a reusable floor, not a claim that every fixture received new native testing.

### Automated and independent evidence

1. Compare exact source content/attributes/IDs and history before/after reflow, export and print. Derived actions do not dirty the document; explicit page/running-content changes are undoable and survive private codec, rich transfer where applicable, templates and both recovery paths.
2. Verify page coverage, order, margins, explicit breaks, keep/widow conflict resolution, oversize progress, header/footer collisions, fields and font/resource invalidation. Mutants/negative controls must catch missing last lines, duplicated fragments, wrong scale, omitted marks/images and reversed mixed-script extraction.
3. Re-execute the prior narrow mixed-script shaping mismatch and rejected PDF fixtures without changing expectations. A successful new mechanism must explain and pass what failed before.
4. Open/export independently with at least two PDF rendering/extraction engines where available, recording exact versions. Two wrappers around the same engine are not independent. Reuse installed tools; investigate licenses/availability before adding a runtime dependency. Export-library self-readback alone is insufficient.
5. Check exact logical text under the declared separator/conversion rules, searches for chosen Urdu phrases, marks and mixed strings, per-cluster selection geometry and reading order. Do not compare unordered character sets, strip marks, normalize letters or reverse extracted text merely to make it pass.
6. Inspect every page of the bounded specimen after independent PDF rasterization, and compare critical crops/page geometry against editor preview. Verify embedded font/resource identity and no external image/font path dependency. Keep original PDFs and machine-readable results.
7. Measure cold/warm layout, local edit reflow, font/resource update, export elapsed time, UI responsiveness and peak memory for representative 10/50-page workloads within supported limits. Declare thresholds before running, record hardware, and disclose failures. Do not silently raise the 100K-unit limit or promise million-unit performance.
8. Run relevant integrated regressions for kernel, Unicode, markers, structured editing, workspace conflict/session/templates, images, history, committed and unfinished recovery. Check all protected hashes. Avoid changing unrelated subsystems.

### Actual desktop interaction on the final build

Use the currently supported computer-use skill/tools, not assumed old APIs. First confirm ordinary and modal controls can be targeted. If the previous tool limitation persists, make a bounded diagnosis, stop rather than retrying indefinitely, finish independent work and explicitly mark missing native checks. Do not alter a correct application just to accommodate broken automation.

Using real controls, edit Urdu across a page boundary; select both directions across mixed-script wraps/pages; change paper/margins and paragraph pagination controls; edit header/footer/page fields; resize an image; Undo/Redo; Save/Open; export; cancel one export; open the final PDF in independent installed viewers; visually inspect and actually search/select/copy Urdu/mixed passages. Match copied text to independent expectations. Exercise print preview and the available virtual print path separately.

Test continuous/paged views and supported zooms, normal/minimum/square/maximized windows and observed OS scale. Do not change system DPI or install input components without authorization. Typography screenshots are not caret-input evidence. Synthetic preedit is not native IME qualification. Do not call a PDF parser check real viewer interaction.

Use the same identified build for final automated and native evidence. After source changes, rebuild and repeat affected checks. Preserve failures and tool interruptions, do not relabel stale results. Stop on detected user activity and retain a precise restart point; continue independent work where possible.

## Delivery and stop

Write `docs/reports/v6-urdu-pages-output-batch.md` and update current WRITING/RECOVERY instructions where affected plus the newest handoff. Include per-stage PASS/FAIL/BLOCKED/NOT RUN, actual libraries/versions/notices, source/build/font identities, commands, exact limits, candidate rejection reasons, original output artifacts, independent extraction results, preview/PDF crops, and genuine native/print receipts.

Provide a launch command, a ten-minute writing-to-PDF walkthrough, the disposable editable sample and final PDF when qualified, and the typography specimen sheet for human review. Explain simply what the writer can now do. Explicitly distinguish a supported desktop body/list/image output profile from full GP0, expert Urdu approval, physical printing, PDF accessibility and other platforms.

No broad new research, collaboration, full table editor, footnote engine, library extraction, publishing, pushing, tagging or project-license selection. Complete all feasible authorized stages, report blockers honestly without hiding partial delivery, clean up only owned test processes and stop computer control. Leave a usable editor even if an output backend fails qualification.
