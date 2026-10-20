# Overnight report — 20 October 2026

Owner-approved scope: finish everything left after the ComposeDoc migration — CodeQL and iOS
as required checks, the UDF boundary, zero Detekt debt (including the test harness), then
resume the paused pages/PDF/printing batch. Every pushed commit passed
`tooling/verification/final_gates.py` (docs/hashes, V6 witnesses, `./gradlew check apiCheck`,
verification contract, workspace milestone, implementation mutants) before the push, and
GitHub CI plus CodeQL on `main`.

## Delivered

| Item | Result |
|---|---|
| CodeQL + iOS required checks | `ios-build` (macOS: iosArm64/iosSimulatorArm64 compile + simulator tests) and the four CodeQL analyses are required on `main` |
| Explicit imports | All 482 wildcard imports replaced; Kotlin default-import precedence preserved |
| UDF boundary | Typed `EditorIntent<R>` for every UI mutation; `EditorViewModel` keeps the engine private; `architectureCheck` rejects `EditorEngine` in UI/app code |
| Detekt | 3,303 findings → **0**, no baselines; ~38 justified `@Suppress` (see below) |
| Formatting | Spotless reads the root `.editorconfig`; 120 columns, LF |
| Pages/output fixes | Ctrl+Enter reaches the page-break binding; AT visible-data on page/header changes; body-only count label |
| Pages/output milestone | `verify.py --pages-output-milestone` **21/21 PASS** (`batch-20261020-104019-2660`) |
| 50-page performance | layout 265 ms / 30 s limit, capture 19 ms, raster 73 ms, 19,854 units |
| Specimen pages | All 10 specimen pages and the regenerated typography sheet inspected |

### Detekt policy

Zero findings with no baselines. Project config (`build-logic/config/detekt.yml`) only: simple
`when` dispatch is not complexity; facade function counts ignore private/overridden members;
the harness is treated as test code for exactly the rules Detekt's defaults exempt test
sources from (MagicNumber, TooGenericExceptionCaught, FunctionNaming). Remaining suppressions
each carry a `// Reason:` comment: 24 deliberate catch-alls (recovery, background workers,
AWT/IME boundaries, engine transaction rollback), 6 long parameter lists and 4 function-count
or unused-parameter findings on published API, 1 exact exception type parsed by a harness
check, 1 const naming conflict. Three intentionally ignored exceptions use Detekt's
`ignored` naming. The generated `Unicode16ConformanceTest.kt` stays generator output and is
excluded with the other generated Unicode sources.

Refactors were split across helper agents per module and reviewed: a runtime-character check
confirmed no Unicode normalization of test strings (escaped invisible characters were
restored), and the full gates exercised every refactored path.

## Inspection findings (not changed overnight)

- **Gulzar list markers sit low.** In the specimen's Gulzar chapters (pages 6–10) the bullet
  sits near the baseline and the `1.` marker below the line; Noto Nastaliq chapters (1–5) are
  correct. Markers use the list's stored marker typography but align to the paragraph's
  Gulzar line metrics. Marker geometry was visually approved for Noto Nastaliq, so it needs
  your eye before retuning. Evidence: `output-page-6.png`…`output-page-10.png`.
- **RTL numbered marker form.** The specimen shows the Urdu numbered marker as `.1` (RTL
  display of `1.`) while the marker-matrix sample shows `1.`; editor and page output paint the
  same measured marker, so this is per-document style, for Urdu review.
- Typography sheet: Gulzar case 05 label is no longer stranded (keep-with-next fix confirmed);
  regional-indicator flags still render as `PK` (font limitation).

## Still blocked or open

- **Semantic PDF export: BLOCKED.** Skiko 0.150.2 (pinned by Compose 1.12.2) still exposes no
  paragraph glyph-run visitor and no PDF document binding; the editor's shaped runs cannot be
  handed to a PDF writer without reshaping, which already failed the mixed-script witness.
  Printing and preview from captured pages remain available; no PDF route was faked.
- **Native desktop checks: NOT RUN.** Computer use needs an interactive access approval, which
  was unavailable overnight. Page editing, print dialog and viewer checks remain for a session
  with you present.
- Expert Urdu typography review, physical printing, OS IME/AT qualification and other platforms'
  native editing remain open.

Evidence: `tests/local-evidence/desktop/batch-20261020-104019-2660/` (pages milestone, page
PNGs) and `tests/local-evidence/desktop/typography-sheet-20261020/` (machine-local, ignored).
