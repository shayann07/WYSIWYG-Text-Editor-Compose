# V6 desktop qualification and marker repairs — 19 October2026

**Bounded desktop batch COMPLETE: implementation, automated integration and available native UI checks PASS on the same identified build. One normal-UI Japanese attempt reported unavailable; candidate lifecycle remains NOT RUN. No production/platform readiness claim.** Computer control is stopped/reset, all owned fixtures are closed. No new architecture/feature batch started.

## Working build and launch

From `D:\Work\WYSIWYG-Text-Editor-Compose`:

```powershell
.\desktop-prototype\write-urdu.cmd --sample
```

The preserved `desktop-prototype/samples/disposable-urdu-sample-v6.astra` opens as an unsaved copy and migrates in memory. The normal script rebuilds current sources. To run the **exact verified artifacts** instead:

```powershell
javaw -cp "desktop-prototype\local-evidence\batch-20261019-164203-33756\build\classes;desktop-prototype\local-evidence\batch-20261019-164203-33756\build\lib\*" editor.MainKt --urdu-writing --sample
```

Build/evidence: `desktop-prototype/local-evidence/batch-20261019-164203-33756/results.json`,17/17 stages PASS. Manifest SHA256 `9ca8c7eb0bfbc1fa7556b920f2fc50c8623534e391f681686465a43346a86d22`. Git base `eeaca42139d49303cf51927eefbe0d3ef9bf3e4c`; extensive existing owner changes preserved, no commit/tag/push. JDK23.0.2+7, Kotlin2.2.20, Compose1.12.1, Skiko0.150.1. Exact source, scripts, dependencies, candidate-font bytes/hashes and artifacts are in that manifest. Windows11 build26300, observed OS scale1.25. Other DPI/platforms are NOT RUN.

Read-first authority: AGENTS, project journal/roadmap/Urdu acceptance, newest handoff, finalized standalone D01–D10 and independent disposition. The v6 specification remains unchanged; its33 sealed entries and48 historical manifest entries rehash correctly. The private codec version is a different namespace.

## Disposition and changes

| Work | Status | Result |
|---|---|---|
| Actual complex-cluster edits | PASS automated |17 named targets, independent UTF16 arrays,152 collapsed deletion cases including34 document-edge no-ops;50 forward/backward selected-cluster scenarios. Actual production reducer, no second-grapheme/space substitution. |
| Caret, hit and selection | PASS bounded mounted; partial live | Independent Latin/Hebrew visual order, allowed unique fixture associations, Shift direction, preferred x over a short intermediate row, empty/wrapped/Nastaliq context and reveal. Supplemental216 selected/unselected cell memberships and24 actual pointer-handler associations across6 view/zoom rows. Existing list/image/page/native-coordinate checks pass. No universal hit/caret bijection asserted. |
| Zero partial witness | PASS production owner/synthetic protocol | Strict normalized rawUTF16 retarget plus separate partial acceptance now exposes the review trace through Kotlin. Existing positive-count guard already excluded zero; no production zero-acceptance data-loss bug was reproduced. Candidate update with count0 is still reconciled. Reference-model bug remains separately labeled. |
| Abort deferred typography | FIXED, PASS | Old production left deferred size48 as typing intent after explicit abort with accepted `a`; final cancels it, accepted `a` stays, size0. Terminal acceptance behavior and FIFO Undo/Format/Undo credits retained. |
| Marker style vote | FIXED, PASS mounted/native | Old actual build changes marker26→8 when only body formatting changes. Final keeps26. Stored per-item seed, explicit Marker style command, native32/Undo26/Redo32 exact payload. |
| Shared sibling lane | FIXED, PASS mounted/native | Old short/long English child content-left569.5/19.174988; final both19.174988. Shared parent-derived full lane reserves measured gutters for mixed local direction and bullet/number siblings. Own text direction controls marker side. |
| Persistence/history/recovery | PASS | Private documentv7 / fragmentv3; legacy readers retained. Exact seed history, split/join, continuation, indent/paste, stale refusal, retained export/apply, Save, checkpoint and fresh-JVM recovery.12 actual owned interruption cases pass. |
| Existing UI repairs preserved | PASS relevant suite and bounded live | Link Remove/Undo, invalid image2409/error/correct320, blank Outline/no navigation, current section/Find, readable outline and responsive controls. |
| Same-final-build native Open | PASS | Final supplement PID23600: actual CtrlO/chooser/fresh owner, exact4285-unit v7 payload, clean empty history, persisted26/32 marker seeds and width320 resource. Earlier watchdog/input interruptions remain historical. |
| Candidate IME | ATTEMPT EXECUTED; lifecycle NOT RUN | Normal in-app Japanese selector reports Input ja-JP unavailable; locale stays en-US and canonical document is unchanged. No retries, installs or candidate lifecycle claim. Older0x80004005 evidence remains dated. |

No caret/hit/Unicode-boundary implementation fix was needed in the broader cases that passed. The reusable owner holds document semantics; desktop Marker style dialog is only its consumer. No new dependency, platform, table, PDF, collaboration, license or publication work.

### Production zero-count trace

Original `0123456789`; replace[1,3) with preeditAB; retarget visible[4,5); candidateXY; zero acceptance leaves Dc original and Dv `0AB3XY56789`. Global marked range[4,6) equals the review's overlay-relative[3,5). Zero leaves the exact provisional IDs/attribution, history and selection intact, and advances session evidence. A valid supplied selection applies; invalid selection rejects before effects. Abort restores the exact original.

Positive1 accepts the specified preceding AB+bridge3+X, giving Dc `0AB3X56789`; residualY global[5,6) is overlay-relative[0,1). Abort retains accepted content; one Undo restores original. A subsequent count0 candidate `新` genuinely updates preedit, rather than dropping the callback. Terminal empty AWT callbacks remain distinct. No Android normalization implementation or OS reconversion qualification is claimed.

### Stable marker and migration policy

`ParagraphProperties.markerTypography` is stored per item (concrete family and8–96 size); all its paragraphs agree. New conversion uses the first paragraph's block insertion seed; unspecified values resolve once to Noto Nastaliq26 in this prototype. Existing list switches and Enter splits inherit. Item continuation/body formatting/typing and Indent/Outdent retain. Root removal returns Normal and resets item-only metadata. Join adopts the surviving left item's seed for moved continuation paragraphs. Undo restores exact original properties/IDs.

**Structure → Marker style…** patches selected whole items, one transaction, with validation and stale captured-basis rejection. Complete rich fragments carry sanitized seeds/fresh identities; partial fragments retain destination paragraph rules. Non-list orphan marker metadata rejects explicitly, preventing silent loss on Save.

Legacyv6 list import derives once from the first substantive cluster of its first paragraph, skipping whitespace/control units, else its empty-block insertion/default seed. Independent old-v6 fixture has leading Naskh96 controls, substantive Urdu Gulzar32 and majority System8; final import picksGulzar32, preserves the old bytes and round-tripsv7 exactly. Older1–5 files have no semantic lists and receive ordinary defaults. Open never auto-rewrites originals. Unsupportedv8, invalid marker family/default/size, bounds/checksum/structure failures reject. Fragmentv3 embeds documentv7, reads old1–2; old readers may explicitly fall back to standard plain text when v3 unsupported.

Full shared sibling lanes follow parent/level indentation, with both leading-side gutters reserved when necessary. Numerals align with the first line's alphabetic baseline; bullets retain the separate measured optical-center policy. Body glyph metric changes may move baselines; marker style does not resample. Digit growth/style patches may resize a shared gutter. No spaces/bullets/bidi controls are inserted into canonical text.

## Complex target coverage

UTF16 boundaries below are explicit reviewed expectations, not generated by production stops. Each target gets collapsed Backspace/Delete at every independently specified stop in an L + target + R fixture, selected forward/backward replacement, format/link patches, clipboard, exact Undo/Redo, Save/Open, committed checkpoint round-trip and a synthetic composition/abort check. Joining/combining edits separately verify changed cluster boundaries with original constituent attribution. Exact native unit deletion can intentionally leave a lone surrogate; it is not snapped to an application grapheme. No Android code-point deletion adapter is claimed.

| Target | Expected local UTF16 stops | Collapsed cases | Result |
|---|---|---:|---|
| Urdu kasra | 0, 2 | 8 | PASS |
| Arabic lam mark alef | 0, 2, 3 | 10 | PASS |
| Persian ZWNJ | 0, 1, 3 | 10 | PASS |
| Hebrew niqqud | 0, 3 | 8 | PASS |
| Latin composed | 0, 1 | 8 | PASS |
| Latin decomposed | 0, 2 | 8 | PASS |
| Devanagari conjunct vowel | 0, 4 | 8 | PASS |
| Bengali conjunct vowel | 0, 4 | 8 | PASS |
| Thai tone | 0, 2 | 8 | PASS |
| Han | 0, 1, 2 | 10 | PASS |
| kana combining | 0, 2 | 8 | PASS |
| Hangul jamo | 0, 3 | 8 | PASS |
| supplementary scalar | 0, 2 | 8 | PASS |
| emoji modifier ZWJ | 0, 7 | 8 | PASS |
| regional flag | 0, 4 | 8 | PASS |
| variation selector | 0, 2 | 8 | PASS |
| bidi isolate | 0, 1, 2, 3, 4, 5, 6 | 18 | PASS |

Official Unicode16.0.0 GraphemeBreakTest1093 fixtures pass; resource SHA is in `v6-qualification.json`. JDK BreakIterator is not used as a grapheme oracle. Existing multilingual suite adds12 script fixtures and108 mounted layout rows: all12 scripts×2views×3zooms, plus36 high-risk direction/8–26–96 size/view/zoom rows. Marker fixtures include empty/wrapped/continuation items,1/9/10/99/100/9999, first-character outliers, opposite-direction short/long siblings and a mixed-direction/type sibling. All3 supported zooms50/75/100%; exact940×400, normal and maximized representative mounted dimensions. This is bounded exhaustive high-risk/pairwise coverage, not a full Cartesian product.

Font evidence reports actual Compose default family/unresolved results separately from per-codepoint FontMgr fallback diagnostics. No exact per-glyph fallback-run identity or expert typography qualification is inferred. Emoji sequence joining/color/flag presentation remains a prior limitation; exact units/graphemes survive. No script is silently normalized/transliterated.

The supplemental selection regression tests every non-space cell center against independently specified logical membership for four LTR/RTL/mixed ranges, using materialized public Compose→Skia paths.216 positive/negative memberships and24 actual mouse-handler associations pass; forward/backward scope is equal. A first harness attempt used an unmaterialized internal path getter and failed; switching to the public conversion fixed the harness without changing expected membership or editor code. Rendered selections were inspected. Glyph boxes provide geometry locations; this is not an independent shaper or native platform proof.

## Final native supplement — Open gate closed

After the owner explicitly resumed, final-build PID23600 completed the remaining native CtrlO/chooser workflow using an owned copy of the already saved file. Fresh owner4285 units, Undo/Redo0, clean state, complete encoded payload byte-equal to the prior saved/native checkpoint. Independent decoder confirms26/32 marker seeds, common parent/fresh sibling item, link, imagewidth320 and resource bytes. Real caret click127, ShiftRight[127,128), CtrlEnd4285 change no document/history/dirty state. `desktop-prototype/local-evidence/v6-native-finish-20261019/audit.json` and rerunnable `audit.py` assert these results. Native screenshots `native-reopened.jpg`, `native-shift-selection.jpg`, `reopened-list-image.jpg` were inspected.

The single normal-UI Japanese selector attempt visibly reports **Input: ja-JP — unavailable** (`japanese-unavailable.jpg`). Exact state remains en-US with no provisional candidate or canonical mutation. Native candidate update/conversion/commit/cancel/popup/focus/Undo qualification remains NOT RUN because that context is unavailable; no new language component was installed or setup repeatedly retried. Loaded/default input state is restored, owned PID23600 exits0, and computer runtime was immediately reset. No remaining owner-idle dependency or mandatory Open check.

The following main-session record retains the earlier watchdog and detected-input history; its Open/IME pending statements are superseded by this final supplement.

## Native evidence and exact files

All live input used supported Windows computer control on the identified final build, disposable documents and isolated recovery. Commands through the fixture channel only read snapshots/setup/cleanup; document editing/dialog actions came from real controls. `type_text` is injected Unicode text, not genuine OS candidate composition. No physical-human keyboard claim.

Main PID27620: real Find → nested English item → End/Enter → mixed sibling text; Marker style32, Undo/Redo; actual Latin-link caret click127 → Remove → Undo; blank Outline click retains[127,127]; actual heading hit/Home and Find current-section174; atomic image click/2409 persistent error/correct320; Continuous,50/75% and original100%; short/maximized with Outline+Find; explicit Save. Native screenshots show the own-direction left English bullet, readable Urdu and original numbered-marker baseline. `protocol.jsonl` records exact canonical payloads and identities. Watchdog exit124 ended the fixture during native Open; this is not classified as a product crash or a completed Open test. Input restoration receipt confirms loaded/default state unchanged.

Exact audit PASS: `desktop-prototype/local-evidence/v6-native-final-resume-20261019/audit.json`. Final4285 units = original4238 + newline +46-unit mixed sibling. All original raw identities/inline attributes survive; Marker style Undo/Redo returns identical payloads. Link removal changes only metadata and Undo restores exact payload. Image width320 and all embedded bytes persist. Saved payload equals completed recovery sequence9. Captured copies independently checksum/parse. A **fresh JVM** through the real store scan/restore path recovers the exact savedv7 bytes: `fresh-process-restore.json`, `fresh-process-restored.astra`. This is separate from the12 integrated force-kill cases and does not certify power-loss durability.

Second fixture PID24972 was closed cleanlyexit0 without document input after tool-detected user activity. Computer control is stopped/reset. The first launch PID11976 ended before targeting due a closed command pipe (launcher defect); fixed with a live TTY. Failed/interrupted receipts were retained, not called passes.

Useful images (paths under `desktop-prototype/local-evidence/`):

- Matched mounted Compose negative-control pair: `v6-independent-final-20261019/siblings-old.png`, `siblings-final.png`. Same text/font/window/zoom, distinct recorded old/final artifacts. These are mounted screenshots, not physical-key evidence.
- Final live actual controls: `v6-native-final-resume-20261019/shared-siblings-native.jpg`, `marker-style-final.jpg`, `image-error-retained.jpg`, `outline-blank-no-jump.jpg`, `outline-find75.jpg`, `short-outline-find.jpg`, `maximized-editor.jpg`, `continuous-native.jpg`.
-2× readable crops: `v6-marker-crops-20261019/`; manifest records actual source hashes/crop boxes, Lanczos only. English bullets/Urdu numeral crops were inspected directly.
- Selection render: `v6-selection-verified-20261019/selection-false-1.0.png` and view/zoom counterparts. A maximization-animation capture containing unrelated background was removed from this task's own disposable artifacts; settled editor-only evidence is used.

Native short capture1021×428, maximized1536×864, normal1021×847 (window capture bounds). Do not label the short capture as exact940×400. OS scale125% only. Other native DPI/platforms, full accessibility/IME/print and expert linguistic review remain unqualified. Candidate popup/convert/commit/cancel/replacement/focus/Undo are NOT RUN after the final normal-UI unavailable result. Prior Urdu/Arabic key evidence remains dated to its old build; current pasted mixed-text interaction does not upgrade it.

## Reproducible commands and runner integrity

```powershell
python desktop-prototype/verify.py --v6-desktop-milestone
python desktop-prototype/qualification/run_selection.py --build-dir desktop-prototype/local-evidence/batch-20261019-164203-33756/build --output-dir D:\YourUnusedTestFolder\selection
python desktop-prototype/local-evidence/v6-baseline-20261019/run_witnesses.py D:\YourUnusedTestFolder\witnesses
python desktop-prototype/local-evidence/v6-native-final-resume-20261019/audit.py
python desktop-prototype/local-evidence/v6-native-finish-20261019/audit.py
python planning/validate_docs.py
```

Use unused output directories. The main runner builds once fresh and fails on child/missing-marker/UI failures, validates exact inputs/artifacts, then records17 stages. Process interruption runners kill only their owned children. Extra selection runner compiles the saved regression source against those same artifacts and records source hash and child exits. Independent witnesses verify old artifact hashes, not old sources against current inputs. Native audit rechecks receipts/files; it does not pretend to replay desktop actions.

Earlier integration attempts correctly failed for stale tests rejecting newv7/expectingv6, and an independent recovery decoder initially accepted only3–6; those checks now explicitly validatev7. A focused scratch runner incorrectly caught SystemExit and marked an otherwise passing child FAIL; the exception boundary was corrected, the receipt left FAIL, and final authoritative integrated PASS is independent. No failing expectation was silenced, variables tampered or skipped child called PASS.

## Changed responsibilities

Semantic/model: `ParagraphProperties.kt`, `StructuredDocument.kt`, `EditorEngine.kt`; marker storage/legacy helper: `ListTypography.kt`; document/fragment: `DocumentFiles.kt`, `RichClipboard.kt`; shared measured lane: `DesktopEditor.kt`; shell: `StructureControls.kt`, generic component form support in `ValidatedControls.kt`. Regression fixtures/runners: `V6QualificationChecks.kt`, `MarkerMatrix.kt`, `VisualRepairChecks.kt`, version expectations in file/paragraph/connected/structure checks, `Main.kt`, verification flags and both interruption decoders. Additional standalone geometry/negative-control sources are in `desktop-prototype/qualification/` and the named evidence directory. Exact old/final verification-input delta is `v6-baseline-20261019/source-delta.json`.

Donor review: pinned ComposeTextEditor6f0a433e42169ad547ca7a5497ca30f99feadf55 input logic/request/session and PointerAffinityTest inspected. Adapted scenario patterns in existing owner/host harness; no mutation/history body or new dependency adopted. MIT source/license records stay under `research/v6-reuse-20261019`. No Android normalization imported into generic core.

## Short hands-on exercise and precise restart

1. Launch sample as unsaved; Find **Nested English item**, Escape/End/Enter and type a longer English sibling. Check shared left markers. Urdu parent stays RTL.
2. Structure → Marker style32; Undo/Redo. Change body font/size and add Item paragraph; marker seed should remain stable. Save/Open a disposable copy.
3. Click Latin/Urdu mixed text, Shift-select, navigate short/wrapped rows/pages. Switch Continuous/Pages and50/75/100%; document Undo/dirty state must not change for presentation.
4. At an interior link caret, Remove/Undo; select image,2409/error/320; use Outline/Find in short and maximized window.
5. Inspect a completed committed recovery copy; it restores separately and never overwrites the original. At most last completed checkpoint is protected (roughly2s scheduling plus IO). Provisional/retained/quarantine work is separate, memory-only unless exported; no callback replay or previous-process Undo restoration.

Exact restart: **none for this completed bounded batch**. Native Open and the one available-UI input-method attempt have now been performed. Preserve the older failed/interrupted checkpoints as history; the final supplement supersedes their pending status. All owned processes/windows are closed, computer control is stopped/reset, input state restored. No further native retries or broad tests are required without a new defect or owner assignment. Stop; do not begin another architecture/feature batch.

Remaining qualification limits: unavailable candidate IME context, other native scripts/input methods, other DPI/Android/iOS/Web, full AT, expert typography and print. Existing emoji sequence/fallback appearance is unqualified. Current recovery is committed-only and last-completed-checkpoint bounded; it is not production durable unfinished-draft recovery or power-loss certification. Current-suite checks have no unresolved failing product regression. `git diff --check` still reports pre-existing owner/root README CRLF and U0 timestamp whitespace; unrelated owner bytes were preserved.
