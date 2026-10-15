# Compose Multiplatform rich-text editor — specification and validation

**Current specification: v5.0.0 (frozen contracts; open qualification gates). No production editor has been implemented here.**

This repository preserves the architectural specification, executable reference models, adversarial validation and earlier research for a document editor targeting Android, iOS, Desktop and Web.

## Start here

1. [Standalone frozen specification v5](editor-v5-standalone.md): complete normative plan, validation report, qualification ledger and review instructions.
2. [Normative plan](plan-v5.md), [freeze decision](v5-freeze-decision.md) and [integrity manifest](v5-freeze-manifest.json).
3. [Current validation report](v5-validation-report.md) and [qualification matrix](v5-qualification-matrix.csv).
4. [Current executable validation](validation/codex/run-20261016-20/README.md): whole-document commands, admission, composition, anchors, cell flows and Undo/Redo.
5. [Gemini independent review brief](gemini-review-prompt.md): historical instructions used for the second review. The completed independent disposition is in v5-validation-report.md.

The `antigravity-*` root files and plan-v4.md preserve the v4.15.0 baseline. The original Gemini review is archived under reviews/gemini-v5-readiness; its proposed fixes are not the normative v5 plan.

## Reproduce the current model

Use Node.js 24 (recorded environment: v24.20.0). The current runner requires no npm dependencies:

```sh
node validation/codex/run-20261016-20/verify.mjs
```

The runner writes `results.json` and archives failures in its own directory. Preserve the committed results before rerunning if comparing evidence. Record the checked-out Git commit, runtime, command and actual output. Historical timing is not an editor latency benchmark. The current suite has 25 verification groups; read their scopes rather than treating their counts as whole-editor proof.

Do **not** run `publish.py` during an independent review: it regenerates the normative root documents. Add new probes/results in a separate review directory and keep the baseline unchanged.

## Repository map

- `validation/codex/`: successive reference-model repair runs, regressions, source hashes and evidence. The latest run is `run-20261016-20`.
- `validation/antigravity/`: earlier independent attempts and their evidence, including defects later corrected. Their claims must be checked against later findings.
- `research/`: attribution probes, Compose/JVM probes, Unicode reference comparisons and abandoned bidi/native-layout experiments. See its README and the benchmark verdict.
- `plan-v1.md`, `plan-v2.md`, `plan-v3.md`, older standalone packages, `progress.md`, `orig_task.txt`, `prior_attempt.txt`: historical context, not alternative current specifications or instructions to execute.

## Scope and evidence limits

Native surfaces use Compose text layout/drawing; Web uses a semantic DOM surface governed by the shared engine. The model owns global document history and typed selections while a native connection targets one flow. Explicit cross-paragraph/container and cell-selection composition remain requirements.

The JavaScript models use full-copy trees, synthetic native stamps, a pinned Unicode 16 grapheme reference (runtime word segmentation) and serialized retention charges. They do not qualify persistent Kotlin storage, Unicode conformance, actual IMEs/DOM/accessibility, imports/system clipboard, physical memory, crash durability or performance. No G0–G8 production gate is passed by publishing this repository.

## Downloaded dependencies and historical probes

Downloaded Maven tools, installed npm dependencies, build outputs, compiled binaries and archives are excluded from Git. Source, lockfiles, upstream source notices and recorded evidence remain available. Install dependencies only for the specific historical probe being reproduced; follow its README. The research npm dependencies can be restored with:

```sh
npm ci --prefix research/runtime --ignore-scripts --no-audit --no-fund
```

The Compose probe requires a compatible JDK/Maven setup; local Windows paths in old scripts/logs are historical environment records. Native Skia experiments are rejected/unvalidated research, not prerequisites of the chosen architecture. Do not silently treat their failed build as a successful optimization. Retained upstream files keep their original copyright/license notices; this upload does not grant a new license over third-party material.

## Runnable desktop prototype

The first plain-text desktop slice is now in [desktop-prototype](desktop-prototype/README.md). On Windows, double-click [run.cmd](desktop-prototype/run.cmd) to build and open it. Typing, cursor/selection and engine-owned Undo/Redo are implemented. Save/Open now preserves `.astra` prototype documents, with an unsaved-close prompt. There is no autosave; real OS IME and full v5 production qualification remain open. The frozen specification is unchanged.
