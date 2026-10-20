# Portable fonts, multilingual markers and page workflows

20 October 2026, bounded product-quality continuation. Starting HEAD was the clean audited
`9874ed52e71f3db2a05436ef902be69680f787c5`. No intervening changes needed reconciliation.
This report supersedes current-status claims in older pause records; their evidence remains intact.
The implementation and bounded native checks below are complete. The authoritative clean-commit
gate receipt is `tooling/local/final-verification.json`; it must say PASS and match HEAD. Exact
push/CI outcomes are recorded in the local delivery receipt and final handoff, rather than inferred
from an earlier build. Unavailable qualification remains explicitly open.

## Implemented

- `TypographyProfile.load(String, File)` preserves its public signatures. An omitted/empty directory
  now loads the three original pinned fonts, manifest and OFL notice from the layout jar. A supplied
  directory is a strict override. Reads are bounded and manifest/font bytes are hash-checked before
  admission. Font identities, family requests, stored choices and profile keys stay unchanged.
- Removed obsolete Detekt baseline configuration. Architecture checks reject baseline XML and
  Gradle settings across Git's tracked/nonignored inventory, including root/tooling. Twelve combined architecture/policy
  self-tests cover rejection and historical/generated exclusions. Suppressions need adjacent honest
  reasons; four unnecessary harness casts/suppressions and a private composable naming suppression
  were removed instead of excused. The public composable naming exception preserves its API.
- Actual Gradle compare-task inputs proved that `core/*/api/jvm/*.api` are consumed. Five stale flat
  duplicates were removed; every consumed dump remains byte-identical. No public signatures changed.
- `verificationEngine` is documented as the existing public compatibility escape hatch. UI/app use
  stays mechanically forbidden. Removing it is a separate owner-approved API change, not part of this batch.
- Fixed marker projection in both editor and captured preview/printing. Marker metrics remain in
  paragraph coordinates; subtract positive first-fragment leading when projecting onto a page.
  Negative rounded first-line tops are ink/rounding bleed, not leading: keep the approved origin
  alignment there. No font-name-specific displacement or canonical text change was introduced.
- Preview now has 50/75/100% magnification, separate from editor zoom and print size. Rendering and
  scaling run in the existing bounded worker; cancellation retains the captured ownership protections.
- Repaired two defects found during actual desktop use: the closed preview zoom clipped `100%`,
  and Save As could leave focus vacant so Ctrl+O was lost. Preview width now measures its label
  plus native chrome allowance; deferred file-operation focus restoration runs only for a vacant,
  active, displayable document with no owned modal. Existing focused controls keep their focus.

## Marker policy and actual diagnosis

The defect was not evidence that all Gulzar baselines or Arial metrics were wrong. Gulzar first-line
`sourceTop` was positive (10 document units in the sample). Body painting removed it while marker
painting omitted it. Noto's first-line top was -1 from floating-point metric rounding; blindly
subtracting it moved approved Noto markers one pixel. That first candidate was rejected and retained.

The corrected projection removes positive leading only, identically in editor and PrintSnapshot.
Numerals still use measured first-line alphabetic baselines. Bullets retain the approved measured
Arial x-height optical reference at the semantic marker em; that is an explicit stable optical
reference, not a claim about actual fallback fonts. No speculative optical-font retuning was needed
to fix the reproduced transform defect. Marker font/size seeds, radius, gutters, sibling columns,
numbering, digits and punctuation are unchanged. RTL markers remain right, LTR markers left;
parent nesting direction does not override a child's text/marker side.

The owner explicitly chose to preserve direction-aware numbering and change positioning only.
Derived marker text remains logical U+0031 U+002E for `1.` in this prototype. Visual punctuation
order must be distinguished from logical text and the item's placement. No new global LTR rule,
bidi control, normalization or punctuation rewrite was added. Language-specific marker direction,
digits and punctuation configuration remains deferred.

## Exact comparison and review images

Independent inspection opened all ten regenerated pages and enlarged crops. Noto pages1–5 have
identical PNG SHA256 and RGB pixels. Gulzar pages6–10 each change216 pixels, exclusively in the
predeclared marker gutters. Body text, headings, running content and images are unchanged.
The owner approved the positioning in chat, conditional on final gates, before any push.

| Sample | Before | Corrected |
|---|---|---|
| Approved Noto page1 | [Before](assets/portable-markers-20261020/before-page-1.png) | [Identical after](assets/portable-markers-20261020/after2-page-1.png) |
| Gulzar page6 | [Before](assets/portable-markers-20261020/before-page-6.png) | [After](assets/portable-markers-20261020/after2-page-6.png) |
| Gulzar numbered item, 4x | [Before](assets/portable-markers-20261020/before-page-6-number-4x.png) | [After](assets/portable-markers-20261020/after2-page-6-number-4x.png) |
| Gulzar English bullet, 4x | [Before](assets/portable-markers-20261020/before-page-6-bullet-4x.png) | [After](assets/portable-markers-20261020/after2-page-6-bullet-4x.png) |

Exact local diff masks/receipts and current API inspection:
[independent report](portable-font-marker-independent-20261020.md).

Bounded live screenshots: [final Gulzar canvas](assets/portable-markers-20261020/native-final-gulzar.png)
and [fully visible preview100% control](assets/portable-markers-20261020/native-preview-100.png).

## Final build and automated evidence

Final identified build: `tests/local-evidence/desktop/batch-20261020-154842-11676/build`.
Manifest SHA256: `c86ea5a1245aba87267cec28a085acc8c900bc475c85f6b341fa5483a78a79b7`.
JDK21, Compose1.12.2 and Skiko0.150.2; no runtime dependency or public API change.

- Old font failure reproduced using the earlier distribution jars outside the checkout: relative
  `tests/suites/urdu/u0/fonts.json` was unavailable.
- Packaged-runtime qualification PASS from a temporary external working directory, using actual
  Gradle distribution jars on JDK21. Twelve exact geometry/raster comparisons across the three fonts,
  original font/manifest/license byte matches, four strict-override failures and three damaged-jar
  cases passed. Stored-font failure still exposes the existing unavailable status; no hidden font
  substitution was accepted as success. Final receipt:
  `tests/local-evidence/desktop/font-portability-final-20261020/results.json`.
- New marker raster oracle compares visible number ink to identical inline digit ink and bullets
  to first-line lowercase ink, rather than restating marker baseline calculations. Explicit negative
  first-line bleed plus one raster-edge pixel is allowed; genuine positive offsets are not excused.
  Four fonts, three sizes, both directions, one/four-digit numbering and six view/zoom states yield
  432 checked nonempty-marker memberships. Empty/continuation paragraphs are present in the fixture;
  the existing broad structural/layout matrix supplies their additional assertions. The new oracle
  rejects the unmodified audited production classes. Earlier helper/setup failures are preserved.
- `check apiCheck` passed, zero Detekt findings and no baseline. Architecture tests12/12 and
  governance tests5/5 passed; documentation validation retained48 historical +33 sealed hashes.
- Complete pages/output milestone **PASS21/21** at `batch-20261020-154842-11676`, including
  **18 pages/output groups**, 432 marker memberships, eight mounted file-focus handler groups,
  preview navigation/magnification/cancel, expanded codec/history/recovery and owned process kills.
  The 50-page bounded case measured layout440.85ms, picture capture63.98ms and raster137.60ms;
  these are machine observations, not a universal latency promise.
- Final preview handler/ink checks also passed all18 groups under actual installed Windows LAF
  (`preview-native-laf-final-20261020`). The same foreground-aware raster oracle rejects the old
  truncated production control (`preview-width-negative-final-20261020`, child exit1). Metal and
  Windows foreground colors are handled explicitly; a native gradient is not mistaken for label ink.
  Focus regressions cover cancel, successful two-stage save, failure, existing focus, inactive or
  disposed owner, modal and resolving the current surface. Earlier production fails the new witness.
- The checked-in editable sample independently passes exact content/resource/page-policy decoding
  plus seven deliberately damaged expected documents: `editable-pages-sample-final-20261020.json`.

## Native sessions and exact persistence

First owned JDK21 process12844 used `batch-20261020-144701-34520/build`, manifest
`6cc1e46f1debc0878ffb01aa4db5bcab2a29e81b275438cded7ddf0f3a732c05`, and an isolated recovery directory.
Ordinary controls and screenshot-coordinate modal Cancel delivered actual events. Physical
Ctrl+Enter created an explicit page separator and11pages; mixed Unicode was pasted through the
tool (not OS IME qualification). Physical selection/Undo/Redo restored exact original/edited roots.
Header/footer edits, different-first-page toggle and Undo/Redo succeeded. Preview opened and
navigated to Gulzar page6 at50/75/100%; Print and printer Properties opened and printing cancelled.
Letter landscape20mm settings applied; native Save produced a clean3996-unit file with resources.

Advanced printer-properties targeting hit an out-of-window error; the alternative accelerator did
not open the advanced page-size pane. It was cancelled safely; that deeper driver setting is BLOCKED,
not a product pass. No physical or virtual job was submitted. Narrator started with an updates notice,
but did not yield a usable targetable reading session; spoken editing/selection remains unqualified.
The tool later returned a black capture; control was reset and only owned process12844 closed, exit0.

Native observation identified truncated closed preview `100%` and missing focus after Save As.
Both were repaired, regressed against the defective production behavior, and retested natively.
The first session's `reopened-native-pages` label is not fresh adoption: the same owner/history
remained, so no native fresh-reopen PASS is assigned from that label.

Second owned process2080 used `portable-product-delivery-build-20261020/build`, manifest
`51e97e9140b404f0138604f2c934ecc0e663c2d36d188c40ec465e0af150002e`.
Evidence is `tests/local-evidence/desktop/portable-marker-native-final-20261020/`:

| Actual interaction | Result and independent check |
|---|---|
| Fresh process Ctrl+O and native chooser | PASS: adopted the saved3996-unit document, resources/page/running properties exact, empty history and fresh running-flow identities. `fresh-process-audit.json`. |
| Ctrl+Enter, backward selection, Undo/Redo | PASS:3996→3997 units and13→14pages, exact Undo/Redo and selection[3996,0]; Unicode paste→4019 and Undo restores the captured basis. |
| Header/footer and different-first-page | PASS: changed flag is one history action; Undo restores exact properties. The first session also edited actual running text and paper settings. |
| Preview page6 and50/75/100%, cancellation | PASS: closed100% label fully visible; page selector and zoom work; document/history unchanged. Correct75% screenshot is `05-preview-page6-75.png`; `03-...75.png` is historically misnamed and actually50%. |
| Print dialog and Cancel | PASS for actual dialog/cancellation only. No job submitted. Advanced driver paper pane remains BLOCKED from the first session. |
| Save As then physical Ctrl+O | PASS: focus restored to EditorSurface without an extra click; Ctrl+O delivered and native Open appeared. Cancel preserved exact content/history. |
| Actual document Close button | PASS for the clean saved document. Remaining blank fixture was shut down by owned cleanup, not claimed as another native close. |

`native-final-state-audit.json` independently checks the nine final-session transitions and saved
bytes. Final file SHA256 is `490ca91907bcb9abd0d0c08e54b8a8de5a933c636514581de6c5738880659fcf`.
The original first-session file remains unchanged. Raw setup failures and rejected assertions remain
alongside corrected receipts; they are not hidden or counted as passed interactions.

After that session the only production source change was removal of the unnecessary private
composable naming exception. Dependency, IO, workspace, codec and controller class bytes are
identical; layout helper instructions are identical. The independent report retains exact class and
`javap` comparisons. Compose generated keys changed with the private rename, so renderer coverage
was repeated rather than inferred: final build process27816, `final-renderer-native-20261020/`.
Actual focus/Ctrl+End/Ctrl+Home PASS, ten pages/3970 units, identical canonical payload and history0/0;
the Gulzar English child visibly retains its left marker. All ten final specimen PNGs remain exact
matches to inspected corrected pages. This is explicit linked-build reuse for unchanged handlers,
with live affected-renderer verification, not a claim that every earlier click was repeated.

All three owned processes exited0. Computer control was reset immediately after each bounded session.
Native screenshots are useful visual observations; exact persistence claims come from file/state
checks. Tool Unicode text entry is paste/injection, not genuine OS composing-IME qualification.

## Semantic PDF boundary

Read-only official matching-pair/artifact inspection is recorded in the independent report.
Current Compose1.12.2 depends on Skiko0.150.2. Its public Paragraph API does not expose the actual
positioned glyph/font/cluster stream or a Paragraph-to-TextBlob bridge; the inspected binding has
no PDF Document class. TextBlob accessors alone do not supply the missing editor run connection.
No dependencies were upgraded and no new PDF pipeline was implemented. Previous reshaping failures
remain dated historical evidence, not fresh0.150.2 execution. Semantic PDF remains BLOCKED.

## Launch and short walkthrough

From the repository root, with JDK21 and Python3.12:

```powershell
.\apps\desktop-workspace\write-urdu.cmd --pages-sample
```

Editable sample: `apps/desktop-workspace/samples/disposable-pages-v9.astra`, SHA256
`8e5830af21bf81ce4ba8ee93e6479fe8761ed298e80bf4475144fec04c4124f6`.
The reproducible `--pages-sample` command opens a fresh unsaved copy of the same self-authored
ten-chapter exercise. Historical samples remain intact.

Ten-minute walkthrough:

1. Inspect chapter1 Noto and chapter6 Gulzar; compare right-side Urdu numbers and left-side English bullets.
2. Use Ctrl+End/Home, Shift selection, Ctrl+Enter, type mixed text, then Undo/Redo across the boundary.
3. In Pages/Output edit Header/Footer, page/total field digits and Different first page; Undo/Redo.
4. Open preview, select page6, switch50/75/100%, then Cancel/Close. Preview zoom does not resize paper.
5. Workspace/Page settings: try Letter landscape and20mm margins, then Undo or retain deliberately.
6. Print opens the installed driver dialog; inspect and Cancel. Do not submit a physical job without permission.
7. Save As to a new disposable path, Ctrl+O without a refocus click, Cancel, close the clean document,
   reopen it and inspect text/resources/page/running properties. Ordinary user files are not autosaved over.

Reproduce focused and final verification from the root (JDK21, Python3.12, Node24):

```powershell
python tests/desktop/qualification/font_portability.py --runtime-lib tests/desktop-harness/build/install/desktop-harness/lib --output-dir tests/local-evidence/desktop/your-new-font-run
python tooling/verification/verify.py --pages-output-milestone
python -m unittest discover -s tooling/architecture -p 'test_*.py'
python -m unittest discover -s tooling/governance -p 'test_*.py'
python tooling/docs/validate_docs.py
.\gradlew.bat check apiCheck
# Commit a clean tree before the six required final gates:
python tooling/verification/final_gates.py
```

The font test needs actual distribution jars; launch/installDist creates them. Reuse no stale
classes: stage receipts contain input/artifact identity, and final gates require a clean exact commit.

Remaining manual checks: advanced driver paper controls after opening Print/Properties; a usable
Narrator reading session (caret, selection, page/running announcements); expert Urdu review and any
explicitly authorized physical-print comparison. These are BLOCKED/NOT RUN, not synthetic passes.

Expert language/typography, genuine composing IME, other DPI/native platforms, full AT and physical
print fidelity are separate gates. No package release, tag, OS/font/driver installation or frozen
specification amendment is part of this batch.
