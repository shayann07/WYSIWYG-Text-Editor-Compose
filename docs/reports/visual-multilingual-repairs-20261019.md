# Visual-quality and multilingual repair batch — final report

19 October 2026. **The six requested UI/interaction repair groups are implemented and retested in the native Windows editor.** The final integrated suite and exact file/state audits pass. This closes the bounded repair batch; it does not qualify every language, OS IME, display scale or platform. Computer control is off and all owned test windows are closed.

## Launch and what is usable now

```powershell
cd D:\Work\WYSIWYG-Text-Editor-Compose
.\desktop-prototype\write-urdu.cmd --sample
```

The disposable `desktop-prototype/samples/disposable-urdu-sample-v6.astra` opens as an unsaved copy. Existing v3/v5 samples and user documents remain intact. The launcher rebuilds current source; Java23 and the repository’s existing Maven/dependencies/fonts were used. It installs no input methods or fonts.

The current desktop integration includes accepted-content editing with raw UTF-16 preservation; bold/italic/underline/strike, stored candidate family/size/color; persistent Auto/RTL/LTR and Start/Center/End, paragraph and line spacing; semantic Heading1–6 and identity outline; real nested multi-paragraph bullet/number items; links and owned PNG/JPEG block images; cross-process rich clipboard and explicit plain paste; literal Find/Replace; Undo/Redo; continuous/experimental paged views and50/75/100% zoom; counts; explicit Save/Open and committed recovery copies. This is the current library foundation and desktop consumer, not the complete Google Docs-level application or extracted/published multiplatform library.

Urdu/Nastaliq remains primary. Each other script retains its own direction and editing semantics; an ancestor’s RTL nesting side cannot override an English child’s LTR marker/text behavior. The owner’s clarification is recorded in PROJECT-JOURNAL.md and the requested memory note.

## Per-issue disposition

|Issue|Reproduction / original actual|Root cause and repair|Regression|Native final disposition|
|---|---|---|---|---|
|List typography / position / nesting|Nested English bullet raised; Urdu1./2. tiny/raised; child across page|Old paint measured18sp label at paragraph top and indentation followed each text direction. New ListTypography.kt/DesktopEditor.kt measure nominal family/size, reserve measured gutters, align numeral baseline to first rendered line, optically center circle separately, and derive one shared content geometry. Root anchoring and child direction are independent.|VR-MARKER-MATRIX;72 script/view/zoom rows;36 high-risk rows; external MarkerSideBaseline fails old policy and passes corrected;108 additional pairwise rows|PASS native crops/both views/all zooms; English left marker under RTL parent and Urdu right marker under LTR parent. Expert typography remains unqualified.|
|UI-01 existing-link edit/remove|Caret inside green sample link→Link→Remove closed with no change/Undo|Collapsed target was treated as insertion and Remove ignored it. StructuredDocument.kt resolves strict interior contiguous identity; StructureControls.kt distinguishes insertion/current link/explicit range/mixed values.|VR-LINK and actual modal VR-HANDLERS; exact metadata/text/history/stale checks|PASS native Remove/Undo on exact final build; current URI edit preserved25-unit label.|
|UI-02 image validation|240→2409→OK dismissed without error|One-shot JOptionPane closed before validation. ValidatedControls.kt keeps same fields/dialog, reports whole-number/range/alt-limit/stale/rejection, permits correction. Restart numbering uses same pattern.|VR-HANDLERS invalid2409/nonnumeric/alt2049/correction/Cancel/stale; no mutation on failure|PASS native2409 retained with red actionable error and correction320, final saved/reopened width320. Nonnumeric/long-alt/stale/Cancel are automated handler evidence.|
|UI-03 / UI-06 outline hit/current state|Select distant row, CtrlHome, click blank area→jump; old row remained highlighted|Every mouse click activated selectedValue, and selection doubled as current section. StructureControls.kt gates navigation by actual cell bounds and separately tracks current heading identity from selection focus.|VR-OUTLINE actual mouse/key/Find handlers|PASS exact-final native blank click preserves[0,0] and payload; current section follows Home/End/Find; keyboard activation preserved. Focus-selected row and current blue bar are distinct.|
|UI-05 outline readability|12pt/default18px single-line rows in fixed200px pane clipped Urdu/mixed labels|OutlineLabels.kt uses explicit19pt pinned Nastaliq with system fallback, measured ascent/descent/wrapped rows and hierarchy.320px sidebar keeps full text available in rows.|VR-OUTLINE renderer accessible label/height; long native sample rows; shell matrix|PASS native readable wrapped Urdu/mixed selected/unselected labels. No expert linguistic or full screen-reader qualification.|
|UI-04 toolbar / short shell|Heading level/font/100% clipped; redundant header consumed short viewport|Measured closed combo/native padding in ValidatedControls.kt; WrappingControls/WritingTabs; reduced redundant title/subtitle and fixed blank height. Recovery/quarantine controls retained.|VR-SHELL normal940×400/1020×740/1536×864, all tabs, Outline+Find; current writing suite|PASS native normal/short/maximized and visible Heading1/2, selected font and50/75/100%. Short capture922×418 is a bounded screenshot; exact940×400 is mounted evidence.|

The first attempted marker repair put the English bullet on the right. The owner’s screenshot exposed that error; it is preserved as FAIL diagnostic evidence. The final policy and counterexample explicitly separate nesting side from local marker direction. Earlier green checks are not relabeled as proof of the corrected policy.

## Current marker and interaction contracts

- Marker family/nominal size is chosen from dominant raw text style in the item; an isolated first-character style does not dictate it. Ties use the preview default; empty items use recorded typing seed. Marker weight/style/ink stay neutral rather than inheriting link/decoration/outlier attributes.
- Numeric labels use the first rendered line’s alphabetic baseline. Bullets are circles with a separate measured x-height optical-center policy. No font-specific pixel offsets, stored bullet characters, spaces or bidi controls are used for layout.
- Advance is measured, sibling gutters reserve the widest label plus0.35em text gap, and nesting steps are measured in em and reduced consistently in constrained widths. Wrapped and additional item paragraphs share a column; only the first leaf has a marker. Numbers1/9/10/99/100/9999 and8/26/96sp are explicitly exercised.
- Root item direction anchors nesting. Local resolved paragraph direction controls marker side, shaping, Start/End and navigation. An opposite-direction child uses an intrinsic-width shared column, capped to available width and anchored near its parent, with its own leading marker gutter reserved. Center remains explicit inside the column.
- Caret Link edit/remove recognizes strict interior affinity: adjacent units must share the same link ID. Exterior seams insert labeled text. Same URL/different IDs remain distinct. Explicit selected ranges are never widened to the whole link; the established displayed-grapheme formatting contract applies and is labeled in the dialog. Text/unrelated attributes/logical selection survive.
- Image width is integer10–600 document dp; alternative text<=2048 raw UTF-16 units. Invalid/stale/rejected actions retain values and show an error; Cancel leaves canonical content unchanged. Restart accepts1–9999.
- Search, view changes, outline navigation and marker/outline painting remain presentation/session behavior. Geometry changes feed caret/hit/selection/native/AT paths. Completed document changes still enter normal Undo and recovery.

## Multilingual evidence matrix

“Core” includes insert, forward/backward selection/format, exact replace/delete, rich clipboard, Undo/Redo, v6 Save/Open, headings, list Enter/continuation/indent/outdent, hard/soft breaks and synthetic callbacks. “Layout” includes each script in both views/all zooms plus additional size/direction/alignment/font-outlier rows. Neither means real OS composition or expert typography.

|Language/script|Core exact operations|Automated layout|FontMgr fallback diagnostic beyond primary|Native evidence / limitation|
|---|---|---|---|---|
|Urdu|PASS|PASS|Primary Noto Nastaliq|PASS physical keys: کتاب; native controls/Find/list/render|
|Arabic|PASS|PASS|Primary Noto Nastaliq|PASS physical keys: سلام; mixed pasted editing/render|
|Persian|PASS|PASS|Primary Noto Nastaliq|NOT RUN native input; automated renderer exercised|
|Hebrew|PASS|PASS|Segoe UI|PASS pasted mixed niqqud text/render/Undo/Save/Open; OS input NOT RUN|
|English / accented Latin|PASS|PASS|Primary Noto Nastaliq|PASS pasted text and native controls/navigation/selection/Undo/Save/Open|
|Hindi / Devanagari|PASS|PASS|Nirmala UI|NOT RUN native input; automated renderer exercised|
|Bengali|PASS|PASS|Nirmala UI|NOT RUN native input; automated renderer exercised|
|Thai|PASS|PASS|Leelawadee UI|NOT RUN native input; automated renderer exercised|
|Chinese|PASS|PASS|Microsoft JhengHei UI, Yu Gothic UI|NOT RUN native input; automated renderer exercised|
|Japanese|PASS|PASS|Yu Gothic UI|BLOCKED candidate activation0x80004005; lifecycle NOT RUN|
|Korean|PASS|PASS|Malgun Gothic|NOT RUN: no configured Korean input method|
|Unicode stress|PASS|PASS|Segoe UI, Segoe UI Emoji, Segoe UI Symbol|NOT RUN native input; automated renderer exercised|

All fixtures are meaningful self-authored short strings with explicit code-point/UTF-16 arrays and unchanged logical expectations in `MultilingualFixtures.kt` and the final receipt. They include joining/diacritics, lam-alef, Persian ZWNJ, Hebrew niqqud, decomposed accents, Indic conjuncts, Thai marks, Han/kana/Hangul, supplementary scalars, ZWJ/skin-tone/flag/VS sequences and bidi isolates. Mixed fixtures include URLs, email, dates, decimals, brackets and punctuation. Nothing is normalized/transliterated.

Actual Compose observation reports default family Noto Nastaliq Urdu and zero unresolved glyphs for the12 bounded fixture layouts. System fallback resolution is separately reported: SegoeUI/NirmalaUI/LeelawadeeUI/YuGothicUI/MicrosoftJhengHeiUI/MalgunGothic/emoji-symbol faces. Exact per-glyph Compose fallback-family runs are not exposed by this backend API; FontMgr diagnostics are not mislabeled as those runs. Standalone VS16 has no printing glyph and does not itself establish a missing rendered sequence.

**Coverage design:** the integrated receipt contains108 layout rows:72 exhaust script×view×zoom and36 target both nesting sides×3 sizes×2 views×3 zooms; each targeted document includes six numbering widths, all alignments, empty/wrapped/multi-paragraph/nested items and first-character style outliers. Additional108 mounted rows use a per-script3×3 Latin square, exhausting size/direction/alignment pairs, with view/zoom/window rotation and System-font italic/opposite-size first-grapheme outliers. This is216 executed layout rows, not a full Cartesian product. The17 repair groups already include12 language groups; these counts are not added again as independent tests.

## Verification, identity and reproducible commands

Final integrated receipt: `desktop-prototype/local-evidence/batch-20261019-141905-30540/results.json`, all16 stages PASS: fresh build; kernel/files/formatting/canonical; stress12,000 seeded formatting commands and existing raw composition traces; attached host; pages; quarantine; writing; local recovery; Find/Replace; paragraph; connected writing; structures; visual repairs; original9 actual owned process interruptions; structured3 actual owned interruptions; Urdu U0.45+3 historical protected hashes remain unchanged. No collaboration/schema/PDF research was rerun.

Current source commit: `eeaca42139d49303cf51927eefbe0d3ef9bf3e4c`, with preserved pre-existing dirty work. Final build manifest SHA-256:

`eef9c31b11e8cca6d8d92114f9828e14953e32a7286b0c10bf8420ee263a079b`

Full source/script/font/dependency/artifact identities: final `build/build-identity.json`. Starting dirty/runtime/font snapshot: `visual-repair-baseline-20261019/starting-state.json`. Existing JDK23.0.2, Kotlin2.2.20, Compose1.12.1, Skiko0.150.1; Windows11Home build26300,125% display scale. Frozen tag remains `de4fd6a6c169caca5661528a09e588009829d277`. No commit/push/tag/publication/license/OS installation decision was made.

```powershell
python desktop-prototype/verify.py --visual-repair-milestone
python desktop-prototype/local-evidence/visual-native-final-20261019/audit.py
python desktop-prototype/local-evidence/visual-pairwise-20261019/run.py D:\YourUnusedTestFolder
```

The third command requires a new unused output directory. It validates the same final build, compiles/runs the external pairwise harness, checks each declared pair and records source/build identity. It uses owned offscreen windows and does not control the keyboard or mouse. The native audit rechecks recorded actual interaction, not injected/synthetic substitutes for that interaction.

A transient Windows latest-result-pointer lock caused one correctly recorded FAIL after earlier kernel/stress PASS. A bounded PermissionError-only atomic pointer retry was added; final failure still surfaces if lock persists. No test was skipped or relaxed to turn that receipt green.

## Native evidence and exact persistence

- `visual-native-before-20261019`: fresh current baseline at1020×865,100% document zoom,Noto Nastaliq; actual failures retained; PID30832 exit0.
- `visual-native-resume-20261019`: actual link Remove/Undo, blank/current section, image error/correction, physical Urdu کتاب/Arabic سلام; PID23844 exit0. Japanese activation returned0x80004005. Temporary layouts/profile restored and configured-language inventory equals the original afterward.
- `visual-native-final-20261019`: final corrected build, own-direction markers in both nesting directions, both views/all zooms/short-maximized shell, Find/current section, independent mixed writing and formatting, image correction, URI edit, exact Undo/Redo, Save/native Open/new owner and owned cleanup; PID25496 exit0. `audit.json` and rerunnable `audit.py`.
- `visual-final-spotcheck-20261019`: strict same-final-build caret Remove→Undo and finalrow→Home→blank tests; PID33164 exit0, exact original4238-unit payload/raw IDs restored and blank click preserves selection[0,0]. `audit.json`.
- Two intervening tool-reported physical Escape stops are retained as NOT RUN supplemental attempts, not counted as passing tests. The final spot-check above subsequently completed them.

The independently constructed final writing expectation is original sample + `English parent 123` + hard paragraph + `اردو ذیلی مشق — English 12.5, العربية، שָׁלוֹם`. Native Save/Open payload4303units is byte-identical; the original4238 raw-unit/style prefix is unchanged; the new mixed suffix has stored Gulzar32; source image resource hashes remain exact, requested width320, URI edit preserves25 label units. The reopened-owner completed recovery record equals the saved payload. Original native controller completed sequence12; process-kill recovery remains the separately executed automated lane, not a close/reopen claim.

## Before/after screenshots

All are actual desktop captures. Crops are2× Lanczos enlargements only, with source hashes/boxes in `visual-marker-comparison-20261019/manifest.json`; no retouching or AI regeneration.

|Before|After|
|---|---|
|![Before bullet](../../tests/local-evidence/desktop/visual-marker-comparison-20261019/before-bullet.png)|![After bullet](../../tests/local-evidence/desktop/visual-marker-comparison-20261019/after-bullet.png)|
|![Before Urdu numeral](../../tests/local-evidence/desktop/visual-marker-comparison-20261019/before-numeral.png)|![After Urdu numeral](../../tests/local-evidence/desktop/visual-marker-comparison-20261019/after-numeral.png)|

Whole matching-content/font/zoom/window captures: `visual-native-before-20261019/markers-outline.png` and `visual-native-final-20261019/markers-outline.png`. The repaired wider outline changes available page width deliberately. Final reverse-direction example: `reverse-ltr-parent-rtl-child.png`; validation: `image-invalid-retained.png`; shell: `minimum-outline-find.png` and `maximized-outline-find.png`; restored writing: `reopened-writing.png`. Final caret/blank repro captures are in `visual-final-spotcheck-20261019`. Mounted renders are explicitly separate from desktop screenshots.

## Short hands-on walkthrough

1. Launch the unsaved sample. Structure→Outline: read long Urdu headings; activate one, CtrlHome, then click blank sidebar space. The current blue bar should track the document and blank space should not move it.
2. Inspect the Urdu parent/English child. English keeps a left bullet near the parent’s nested column. Create an English numbered parent, Enter an Urdu item and Indent; its marker stays on the right near the left-root column.
3. Click inside the green sample link, Link→Remove, Undo. Edit its URI at a caret without supplying a new label. Select only part of a link to change only that selected displayed-grapheme range.
4. Select the image, Image properties: enter2409, clickOK, correct to320. Error/value should remain until corrected. Cancel a further edit and confirm no change.
5. Use Text/Paragraph/View controls; closed values retain heading/font/zoom identity. Open Find and Outline together; searchکتاب, next/previous/Escape. Switch views/zooms without document/history changes.
6. Write mixed text, change font/size/direction, Undo/Redo, Save a disposable file, close/Open and compare text/formatting. Check recovery only after a completed checkpoint. Provisional input/quarantine protections remain separate.

## Remaining limitations and follow-up dependencies

- **BLOCKED / NOT RUN:** installed Japanese candidate profile activation through the owned setup adapter failed with0x80004005, despite configured/enabled inventory. Candidate updates/conversion/commit/cancel/replacement/focus/popup geometry are not qualified. Follow up by manually selecting existing MicrosoftIME/Hiragana in an owned editor/reference JTextArea, then run nihon→Space candidates→Enter/Escape, selection replacement/focus and exact Undo traces. Do not install or repeatedly attempt unavailable activation in this batch.
- **NOT RUN:** Korean native composition (no configured method), Chinese native IME (no configured Chinese profile), other non-Urdu/Arabic OS input paths;100/150/200% Windows scaling; Android/iOS/Web; full AT/screen-reader; expert multilingual/Nastaliq typography and print fidelity.
- Unicode stress raw text, grapheme editing and fallback resolution pass. Existing platform emoji fallback can display monochrome/separate ZWJ components or letter flags; joined/color sequence appearance is not qualified. It was not introduced by these repairs and is recorded separately rather than hidden behind zero unresolved glyphs.
- Private array-backed prototype/document/resource/depth limits remain; paged mode is experimental. `.astra`v6 reads v1–v5, fragmentv2 reads v1–v2. No production schema/API freeze or full library extraction.
- Recovery protects only completed committed-content checkpoints:2-second scheduling plus UI/IO; failures extend the window. Provisional composition, retained capsules and quarantined callbacks remain separate/memory-only unless explicitly exported. No replay, power-loss guarantee or restored previous-process Undo is claimed.

No reported UI defect remains unresolved in the exercised scope. These bounded PASS results are not “everything verified.” User documents, previous samples and frozen historical artifacts were preserved. Computer control is off; owned windows are closed; stop after this repair batch.

---

# Implementation and interruption log (historical; final report above governs)

# Visual/multilingual repair batch — IN PROGRESS

19 October 2026. Preserve existing eeaca42 dirty work and historical specimens. Starting status/source/font/runtime hashes: `desktop-prototype/local-evidence/visual-repair-baseline-20261019/starting-state.json`. Related audit thread idle; no subagents or messaging. Owner screenshots inspected: raised bullet, small raised numerals, LTR child far from RTL parent. Audit UI-01–06 is evidence to reproduce, not proof of fixes. Computer control off during code work.

## Expected behavior before implementation

- Markers use paragraph nominal typography: dominant raw-unit family/size, ignoring first-character outliers, ties default; empty uses typing seed. Numeric marker baseline equals first rendered text-line baseline; bullet optical center uses measured representative x glyph center. Separate these contracts, no font-specific pixel offsets. Measure advance, reserve longest sibling marker plus measured gap; nesting in em, shared column for wrapped/continuation paragraphs, no repeated continuation marker. Text remains exact.
- List nesting side follows root item direction independently of child text direction. The child's marker side, Start/End, shaping and navigation follow its own resolved direction. An opposite-direction child uses an intrinsic-width shared column anchored near the root-side nested edge; its own marker gutter is reserved separately. Center applies inside that column. Both directions share coordinate maps for paint/hit/caret/native/AT/pagination.
- Link caret recognizes current link only strictly inside equal adjacent IDs. Resolve contiguous identity for caret edit/remove. Exterior seams insert; distinct identities never merge by URI. Explicit selected ranges remain selected ranges (existing grapheme formatting coverage applies), no whole-link widening. Mixed indicated honestly. One undoable edit; captured stale basis refuses.
- Image width integer10–600, alt<=2048 rawUTF16. Invalid/nonnumeric/stale/rejected input keeps dialog and values, actionable error/correction. Cancel no mutation. Same pattern for list restart1–9999; inspect other numeric controls.
- Outline click must intersect actual cell rectangle. Keyboard activation preserved. Current section follows selection focus and is distinct from keyboard-selected row; none before first heading. No focus steal. Script-capable readable wrapped labels/hierarchy with enough ascent/descent and usable sidebar width.
- Closed toolbar identity values use native renderer measurements plus padding; H1–H6 short labels allowed. Compact redundant title/subtitle/blank height, preserve all controls and recovery/quarantine protections. Outline+Find at940x400 leaves usable document viewport.

Mathematical checks: baselines/optical centers, gutters/advance, transforms, independent logical text, identity/history, persisted bytes. Visual judgment and native IME remain separate. Font/fallback/missing glyphs and actual Windows scale recorded; no linguistic or cross-platform qualification inferred.

## Checklist

Baseline fresh build/handler/native reproductions PENDING; markers PENDING; link targets PENDING; validation PENDING; outline hit/current/readability PENDING; shell PENDING; multilingual fixtures/operation/layout matrix PENDING; integrated/native/final inspection PENDING. Each confirmed issue receives reproduction, actual/expected, root cause, files, regression, native screenshot/build and uncertainty.

Exact next action: inspect fresh baseline compile, execute discriminating actual-handler repro helper and native matching screenshots before editing source. No source repair yet.

## Live baseline and first implementation cycle

Fresh baseline build `batch-20261019-134414-32788` compiled unchanged sources and recorded identity. Independent `visual-repair-baseline-20261019/VisualBaseline.java` calls the actual mounted listeners/modal buttons: LINK_REMOVE_AT_CARET_NOOP=true, INVALID_IMAGE_DIALOG_DISMISSED=true, STALE_OUTLINE_ROW=4, BLANK_OUTLINE_MOVED=true, outline12pt/18px rows/fixed200px. OS graphics transform1.25×. Starting dependencies: Kotlin2.2.20, Compose1.12.1, Skiko0.150.1, JDK23.0.2; full artifact/font hashes in receipts.

Actual native baseline `visual-native-before-20261019`, PID30832 clean exit0: same sample4238 units, Noto Nastaliq profile,100% document zoom,1020×865. Real caret Link/Remove retained URI and Undo0; real invalid2409 OK dismissed unchanged; CtrlHome retained old section row; blank-pane click jumped to4133. `markers-outline.png` independently reproduces raised tiny markers and distant cross-direction child. Other captures: link-remove-noop.png, blank-outline-jump.png, image-invalid-before-submit.png, image-invalid-silent-dismiss.png. No input layout activation, runtime reset immediately after clean fixture shutdown. These are baseline FAIL observations, not final repair PASS.

Implementation in progress: shared strict-interior contiguous-link resolver and caret edit/remove; persistent validating image/link/restart dialogs; actual outline cell hit gating, independent current-section state and wrapped19pt font-fallback renderer; measured combo widths/compact redundant header; measured list nominal/gutter/baseline/optical geometry and shared content-origin transforms.

First multilingual run exposed a fixture mistake: its generic hard-break helper used label hard rather than the semantic Enter command, thus creating continuation paragraphs. External ListTrace.java confirmed this; fixture now invokes StructureCommand.Enter(false). All12 language operation groups then passed before a genuine new renderer NPE (Kotlin access to JPanel's nullable protected accessibleContext field rather than getter). Corrected to getAccessibleContext(); the runner now fails on any uncaught UI-thread exception. Current focused run is pending; do not claim UI/matrix completion yet.

Focused PASS `batch-20261019-140131-19260`:17 repair groups,12script operation fixtures,72 script×view×zoom rows plus36 targeted rows covering both nesting sides,8/26/96sp,number1/9/10/99/100/9999,all alignments,empty/wrapped/multi-paragraph/nested cases. Actual modal correction/cancel/stale handlers and Outline+Find minimum/normal/maximized shell assertions executed; old structure suite also PASS. OS scale125% only. Font backend unresolved glyph count0 in these12 fixtures; per-codepoint fallback diagnostics include SegoeUI,NirmalaUI,LeelawadeeUI,YuGothicUI,MalgunGothic and emoji/symbol fallback. Exact Compose per-glyph fallback-family API unavailable; default family/unresolved diagnostics are actual, FontMgr family resolution is separately labeled. Do not call this linguistic or native IME qualification.

Integrated run `batch-20261019-140352-30700` stopped after kernel/stress PASS because Windows briefly denied atomic latest-verification.json pointer replacement (WinError5). It is correctly FAIL, not a green integrated result. Shared verifier now retries only PermissionError on current-pointer publication10×50ms, preserving final failure if lock persists; no test result or source identity weakened. New complete integrated run required. Computer control off, native baseline closed. Configured input inventory includes Urdu,Arabic,Japanese; native available-input checks remain required, Korean configuration absent.

## Current checkpoint — native after-check blocked by explicit user stop

`batch-20261019-140448-29872/results.json`: PASS all16 integrated stages,17 focused repair groups,108 explicit layout rows (72script×view×zoom and36 targeted number/size/direction rows), old24 structuregroups, current writing/Find/paragraph/clipboard/recovery/quarantine/U0 and12 actual owned process kills. Current build identity revalidated; source/fixtures/scripts/fonts/artifacts frozen throughout final run.45+3 historical protected files unchanged. This is automated/synthetic evidence, **not native visual closure**.

Repaired source files: StructuredDocument.kt (strict-interior contiguous-link identity target); StructureControls.kt (link UI, validation, outline cell hit/current section); ValidatedControls.kt (persistent correction dialogs, measured combos); OutlineLabels.kt (19pt wrapped script-capable rendering with explicit fallback); ListTypography.kt and DesktopEditor.kt (nominal marker policy, first-line baseline, separate circle optical center, measured gutter, root-side nested columns, all content/native/AT transforms); ResponsiveControls.kt/WritingControls.kt (wrapping measured groups/compact tabs/header). Tests: MultilingualFixtures.kt, VisualRepairChecks.kt, MarkerMatrix.kt, FontEvidence.kt; Main/verify.py integrate the focused lane. Native fixture adds readonly control/marker state and configured Arabic support; existing owned input adapter allowlists Arabic alongside Urdu/Japanese. Codec/history/recovery schema unchanged.

On attempted final native launch, helper returned: **Computer Use was stopped by the user with the physical Escape key.** No further computer calls were made in that turn. Fresh owned final fixture PID15236 had only READY (4238unchanged units), and was shut down via its owned control channel; no user windows/documents or input settings changed. Native directory `visual-native-after-20261019` is NOT RUN for repairs; it is not a passing walkthrough. Computer control must remain off until the owner authorizes resuming.

Native configuration read-only inventory: Urdu ur-PK00000420, Arabic ar-SA00000401 and Japanese MicrosoftIME are configured; TSF reports Japanese profile enabled. Korean absent: NOT RUN, dependency an owner-configured Korean input method. No language/keyboard installation or persistent setting change. Existing helper compiled in `visual-native-helper-20261019`; use only bounded temporary owned-thread preparation/restoration for configured input, never repeatedly attempt unavailable candidate activation.

Known visual uncertainty from synthetic output: Unicode stress is exact and backend unresolved count0, but platform emoji/font fallback can show monochrome/separate ZWJ components/letter flags. This is not proof of sequence typography qualification. `layout-unicode-false-1.0.png` records it; investigate the full-cluster fallback policy if directly continuing this batch. FontMgr's standalone U+FE0F glyph result is missing because VS16 is nonprinting; actual Compose unresolved count remains0. Exact per-glyph fallback family not exposed by current backend API; current evidence labels this limitation.

### Exact restart

1. Read this checkpoint and `docs/reports/overnight-progress.md`; validate build `desktop-prototype/local-evidence/batch-20261019-140448-29872/build` against current source before running it. Preserve source/owner changes.
2. After explicit desktop idle availability, launch a **new** owned native fixture/output/recovery directory using final build plus `visual-native-helper-20261019/classes`. Reproduce every original UI path and capture matching after evidence for `visual-native-before-20261019`. No need to repeat baseline or the whole historical audit.
3. Native repair checklist: caret link Remove/Edit and selected mixed ranges/Undo; image2409/nonnumeric/correct/cancel/stale; blank outline click/current section via CtrlHome/End/Find/keyboard; wrapped multilingual outline; closed role/font/zoom values; normal/minimum/maximized Outline+Find; marker bullets/numerals/nesting and readable crops in both views/zooms. Pair protocol payload/state assertions with screenshots.
4. Configured native input: one bounded Urdu and Arabic physical-key trace using returned mapping, Japanese candidate lifecycle attempt with installed profile. Save exact text/history/callback traces, restore settings before shutdown. Korean NOT RUN with configuration dependency. Do not conflate pasted text or synthetic callbacks with genuine IME.
5. Fresh user-style final walk including mixed typing/lists/font/direction/link/image correction/Outline+Find/views/Undo/Save/close/Open. Investigate related defects, run focused/new integrated checks only if source changes. Update WRITING.md, per-issue/multilingual matrix and handoff, then stop. **This repair assignment is NOT complete until required native checks pass.**

## Owner correction during native review — marker side versus nesting side

The owner accepted the bullet optical correction but correctly questioned the English child's right-side bullet. Native `visual-native-resume-20261019/markers-outline.png` confirms the first repair incorrectly coupled marker side and Start/End alignment to root nesting direction. This is a confirmed FAIL, not a passing policy. Replacement contract: nesting column anchoring follows the root item, but text shaping, Start/End and marker side follow each item's own resolved paragraph direction. An opposite-direction child has a shared intrinsic-width content column, capped to available width and anchored near the parent's nested edge; its own leading marker gutter is reserved separately. Thus RTL parent/LTR child keeps an English left bullet near the right-side nested column; the reverse keeps a right bullet near the left-side column. Wrapped and continuation paragraphs share the item's column. No stored text/properties/directional controls change. New targeted assertions explicitly require child direction/marker side opposite to root side. Current source changed; earlier integrated build140448 is stale for this correction.

Native resume PID23844 passed actual caret Link Remove/Undo, current section Home, blank-pane nojump/keyboardactivation, retained image2409 error/correction320. Physical-key Urdu کتاب and Arabic سلام exact; Japanese profile candidate activation returned0x80004005 ProfileChangeNotVerified: native candidate lifecycle NOT RUN. Original default/loaded layouts/profile restored, owned accepted fixture exported before clean exit0. Runtime reset before code changes; no user documents/settings changed. Remaining native tests and final batch are still pending.

Focused correction PASS `batch-20261019-141714-4144`. Independent external `visual-repair-baseline-20261019/MarkerSideBaseline.java` runs the same actual mounted sample on defective140448 and corrected141714: bodydirectionLtr/rootnestingRTL true on both, EXPECTED_LTR_MARKER_LEFT=false before and true after. The targeted matrix now separately asserts child direction/leading marker side in both RTL-parent/LTR-child and LTR-parent/RTL-child cases, independent of ancestor indentation. Full integrated rerun currently running; inputs held unchanged. Runtime off while code/tests execute.

## Final native walkthrough results and explicit second Computer Use stop

Current final build is `batch-20261019-141905-30540` (all16 integrated stages PASS after marker-side correction;17 repairgroups/108 layoutrows and12 actual owned kills). Native `visual-native-final-20261019`, PID25496 exit0, actually exercised corrected left English bullet/right Urdu child marker, both nesting directions, continuous/paged modes,50/75/100% zoom, closed heading/font/zoom labels, normal/short/maximized Outline+Find, Find16matches and sectionidentity174, independent newEnglishparent/Urduchild, explicitRTL, Gulzar32 mixed Arabic/Hebrew/Latin pasted text, exact Undo/Redo, image2409 retained error/correction320, caret current-link URI edit, Save and actual native Open into a fresh owner, original close and owned final shutdown. Exact saved/Open/checkpoint full v6 payload matches4303units; initial4238 raw unit/style-prefix bytes unchanged. Audit: `visual-native-final-20261019/audit.json`, useful screenshots in that directory. Computer runtime was reset immediately after this walkthrough. Final raw document `disposable-native-writing.astra` and preserved `checkpoint-document.astra` are disposable evidence.

Earlier native `visual-native-resume-20261019`, PID23844 exit0, actually tested original caret Remove/Undo, blank-pane nojump/current-section/keyboard outline, invalidimage correction, and physical Urdu کتاب/Arabic سلام. All involved StructureControls/ValidatedControls/outline/writing/wrapping classes are byte-identical with the current final build, individually checked in final audit handlerBridge. Input profile activation for Japanese returned0x80004005 ProfileChangeNotVerified; candidate lifecycle remains NOT RUN. Temporary configured layouts and profile fully restored (no installations). Korean not configured.

For strict same-final-build retesting of the original Remove and blank-pane sequences (rather than relying on the unchanged-class bridge), launched a new disposable final fixture `visual-native-final-handlers-20261019`, PID28328. It reached link caret and Structure tab without edits; on the Link click the tool again reported **Computer Use was stopped by the user with the physical Escape key.** No further computer calls were made. Readonly final snapshot and owned fixture shutdown retained unchanged content, NoActivation, clean exit0. No user windows/documents/settings changed. This supplemental handler retest is NOT RUN, not PASS. The tool requires ending the turn after explicit stop; do not automatically restart Computer Use without renewed owner authorization.

### Remaining exact actions (bounded)

- If native checks resume: validate141905build; run only a short final sample caret Link→Remove→Undo and Outline finalrow→CtrlHome→blank area sequence; capture exact payload/selection and screenshots. No need to repeat the completed marker, writing, window/zoom, Save/Open walkthrough or whole historical audit.
- Persist a rerunnable audit script from the assertions already executed for final audit.json; generate requested readable crops from the actual baseline/final marker screenshots (no AI image edits); finalize per-issue disposition and per-language coverage table, WRITING.md and current handoff. Frame short native captured922×418 separately from exact mounted940×400.
- Final status must retain candidate Japanese activation BLOCKED/lifecycle NOT RUN; Korean NOT RUN absent configuration;125% OS scale only; all other OS DPI/Android/iOS/Web unqualified; emoji sequence visual fallback remains a pre-existing bounded limitation, not an introduced regression. Do not claim universal language typography/IME quality.

Owner correction durably recorded in PROJECT-JOURNAL.md and explicit requested memory note `20261019T092200Z-urdu-primary-multilingual-correctness.md`: Urdu primary; no other script sacrificed; ancestor nesting side cannot override own text/marker direction.
