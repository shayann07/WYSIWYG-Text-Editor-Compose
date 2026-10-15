# Compose Multiplatform rich-text editor — specification and validation

**Current specification: 4.15.0. No production editor has been implemented here.**

This repository preserves the architectural specification, executable reference models, adversarial validation and earlier research for a document editor targeting Android, iOS, Desktop and Web.

## Start here

1. [Standalone specification 4.15.0](editor-4.15.0-standalone-review.md): complete normative plan, validation report, qualification ledger and review instructions.
2. [Normative plan](plan-v4.md): revision 4.15.0 despite its historical filename.
3. [Current validation report](antigravity-validation-report.md) and [qualification matrix](antigravity-validation-matrix.csv).
4. [Current executable validation](validation/codex/run-20261015-19/README.md): whole-document commands, admission, composition, anchors, cell flows and Undo/Redo.
5. [Gemini independent review brief](gemini-review-prompt.md): review and validation only, preparing amendments for a future v5 freeze.

The `antigravity-*` filenames are historical names. The current root report records the latest Codex run, not a newly executed Antigravity audit.

## Reproduce the current model

Use Node.js 24 (recorded environment: v24.20.0). The current runner requires no npm dependencies:

```sh
node validation/codex/run-20261015-19/verify.mjs
```

The runner writes `results.json` and archives failures in its own directory. Preserve the committed results before rerunning if comparing evidence. Record the checked-out Git commit, runtime, command and actual output. Historical timing is not an editor latency benchmark. The current suite has 20 verification groups; read their scopes rather than treating their counts as whole-editor proof.

Do **not** run `publish.py` during an independent review: it regenerates the normative root documents. Add new probes/results in a separate review directory and keep the baseline unchanged.

## Repository map

- `validation/codex/`: successive reference-model repair runs, regressions, source hashes and evidence. The latest run is `run-20261015-19`.
- `validation/antigravity/`: earlier independent attempts and their evidence, including defects later corrected. Their claims must be checked against later findings.
- `research/`: attribution probes, Compose/JVM probes, Unicode reference comparisons and abandoned bidi/native-layout experiments. See its README and the benchmark verdict.
- `plan-v1.md`, `plan-v2.md`, `plan-v3.md`, older standalone packages, `progress.md`, `orig_task.txt`, `prior_attempt.txt`: historical context, not alternative current specifications or instructions to execute.

## Scope and evidence limits

Native surfaces use Compose text layout/drawing; Web uses a semantic DOM surface governed by the shared engine. The model owns global document history and typed selections while a native connection targets one flow. Explicit cross-paragraph/container and cell-selection composition remain requirements.

The JavaScript models use full-copy trees, synthetic native stamps, Intl segmentation and serialized retention charges. They do not qualify persistent Kotlin storage, Unicode conformance, actual IMEs/DOM/accessibility, imports/system clipboard, physical memory, crash durability or performance. No G0–G8 production gate is passed by publishing this repository.

## Downloaded dependencies and historical probes

Downloaded Maven tools, installed npm dependencies, build outputs, compiled binaries and archives are excluded from Git. Source, lockfiles, upstream source notices and recorded evidence remain available. Install dependencies only for the specific historical probe being reproduced; follow its README. The research npm dependencies can be restored with:

```sh
npm ci --prefix research/runtime --ignore-scripts --no-audit --no-fund
```

The Compose probe requires a compatible JDK/Maven setup; local Windows paths in old scripts/logs are historical environment records. Native Skia experiments are rejected/unvalidated research, not prerequisites of the chosen architecture. Do not silently treat their failed build as a successful optimization. Retained upstream files keep their original copyright/license notices; this upload does not grant a new license over third-party material.
