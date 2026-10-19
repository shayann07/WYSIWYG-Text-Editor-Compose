# Documentation

Start with the [project journal](project/PROJECT-JOURNAL.md) for the mission. For implementation status, read the newest entry in the [handoff](reports/overnight-progress.md), then its named evidence. Plans and test reports are not interchangeable.

Current assignment: [ComposeDoc migration checkpoint](reports/composedoc-migration-progress.md).
The library is multilingual; Urdu/Nastaliq is a primary early workload.
See the [agent entry point](../AGENTS.md) for current Gradle commands.

## Current specification and review records

- **[Final standalone v6 specification](plans/v6/editor-v6-standalone.md)** — complete specification, feature catalogue, reuse research and inherited contracts. Finalized specification; production qualification remains gated.
- [Argon review prompt](prompts/argon-v6-review-prompt.md).
- [V6 manifest](plans/v6/v6-plan-manifest.json) and [document validation](reports/v6-plan-validation-20261019.md).
- [Final review disposition](reports/v6-final-review-disposition-20261019.md), [freeze decision](plans/v6/freeze-decision.md) and [next implementation prompt](prompts/v6-next-session.md).
- [V5.2 baseline](plans/v5.2/README.md).
- [Exact v6 package already sent to Argon](archive/review-packages/v6-review1/README.md).

## Product requirements

- [Project journal](project/PROJECT-JOURNAL.md)
- [Product roadmap](project/PRODUCT-ROADMAP.md)
- [Feature catalogue F01–F46](project/FEATURE-CATALOG.md)
- [Urdu acceptance](project/URDU-ACCEPTANCE.md)

## Use and test the prototype

- [Writing guide](guides/WRITING.md)
- [Input recovery and quarantine](guides/RECOVERY.md)
- [Accessibility contract and limits](guides/ACCESSIBILITY.md)
- [Build/setup and verification](../apps/desktop-workspace/README.md)

## Reports and history

- [Report index](reports/README.md)
- [Latest implementation handoff](reports/overnight-progress.md)
- [Visual/multilingual repairs](reports/visual-multilingual-repairs-20261019.md)
- [Independent live UI audit](reports/independent-live-ui-audit-20261019.md)
- [Existing-library reuse decision](<reports/Editor library reuse decision.md>)
- [Archive index](archive/README.md)
- [Organization record](organization-20261019.md)
- [Old-to-new path map](relocations.json)

New plans go in `docs/plans/<version>/`, product requirements in `docs/project/`, usage guides in `docs/guides/`, prompts in `docs/prompts/`, and dated findings in `docs/reports/`. Historical documents go in `docs/archive/`. Executable suites are in `tests/suites`, independent reviews in `tests/reviews`, historical research in `tests/archive`, and ignored local receipts in `tests/local-evidence`.

Run generators from the repository root. `tooling/docs/doc_paths.py` resolves historical names without recreating root clutter. The [relocation amendment](amendments/composedoc-relocation-v1.json) preserves frozen bytes at their new paths.
