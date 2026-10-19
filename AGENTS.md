# ComposeDoc — agent entry point

## Post-clone quickstart

Run from the repository root with JDK 21, Python 3.12 and Node 24:

```sh
python tooling/docs/validate_docs.py
node tooling/verification/v6.mjs
./gradlew check apiCheck
python tooling/verification/test_verification.py
python -m pip install -r tooling/verification/requirements.txt
python tooling/verification/verify.py --workspace-milestone
```

Windows: use `gradlew.bat`; launch with
`apps\desktop-workspace\write-urdu.cmd --workspace-sample`.
Checks take longer than a minute; this entry point is designed to locate them quickly.
`python tooling/verification/final_gates.py` runs all of them on a clean commit and
writes the receipt. See the [migration checkpoint](docs/reports/composedoc-migration-progress.md)
for current results and open gates.

## Read first and mission

Read [PROJECT-JOURNAL.md](docs/project/PROJECT-JOURNAL.md), then
[PRODUCT-ROADMAP.md](docs/project/PRODUCT-ROADMAP.md), the latest owner assignment,
and the relevant [V6 contracts D01–D10](docs/plans/v6/editor-v6-standalone.md).
Preserve the current bounded assignment instead of implementing the whole roadmap.

ComposeDoc (`dev.shayxo.composedoc`) is a **multilingual reusable open-source
WYSIWYG document-editor library for Compose Multiplatform**. Urdu/Nastaliq is a
primary early qualification workload. It is not the library's language limit.
The owner's Urdu-first Google Docs–level commercial/cloud application is a
separate deliverable; the desktop workspace is an example and test consumer.
Apache-2.0 covers this repository's original library, validation and sample code;
third-party material keeps its license. Future application code is not granted
merely because it uses the library. No package release/publication is authorized.

## Directory and dependency map

| Location | Responsibility / boundary |
|---|---|
| `core/model` | Common immutable document values, identity, Unicode, selection and page policy; no UI or IO |
| `core/domain` | Single-owner commands, composition, attributed history and ports; model dependency only |
| `core/data` | Codecs, persistence, resource and recovery implementations; consumes domain ports |
| `core/layout` | Typography, geometry, page/output contracts; model/domain dependencies |
| `core/ui` | UDF state holder, intents, reusable rendering/input/accessibility; no app chrome or data implementation |
| `apps/desktop-workspace` | Example shell, toolbars, dialogs, workspace and templates |
| `build-logic` / `gradle` | Convention plugins, pinned catalogue and wrapper |
| `tests/desktop-harness` | Executable integration/qualification consumer; never imported by the library |
| `tests/suites` / `tests/reviews` | Current verification and preserved independent reviews |
| `tests/archive` | Historical research and pre-migration sources; evidence, not production authority |
| `tests/local-evidence` | Ignored machine-local receipts and disposable test artifacts |
| `tooling` | Documentation integrity, architecture, verification, launch and GitHub automation |
| `docs` | Canonical plans, guides, reports, amendments and historical specifications |

Platform code belongs in platform source sets. A shared source set compiling
successfully does not qualify the native editor on that target. The current
native host originated on JVM/Desktop; report mobile/web gaps explicitly.

## Always

- Inspect Git status and current reports before editing; preserve concurrent/user work.
- Preserve exact UTF-16, Unicode segmentation, native range fidelity, attributed Undo,
  composition settlement, Urdu/Nastaliq and mixed-script bidi contracts.
- Use one document owner and immutable published state; dispatch user intents inward.
- Mechanically check module boundaries, formatting, static analysis and public API dumps.
- Treat each module's `detekt-baseline.xml` as recorded pre-migration prototype debt.
  Fix new findings instead of regenerating a baseline; shrink baselines when refactoring.
- Run `python tooling/docs/validate_docs.py` and `./gradlew check` before completing work,
  plus relevant integration checks. Report failures and unrun gates honestly.
- Distinguish models, synthetic mounted handlers, real native input, AT and print evidence.
- Resolve historical paths through `tooling/docs/doc_paths.py` and `docs/relocations.json`.

## Ask first

- New third-party runtime dependencies beyond the current owner-authorized assignment.
- Changes to established public `core/*/api/*.api` contracts.
- New versioned specification amendments or release/package publication.

Existing explicit owner authorization takes precedence; do not repeatedly ask
for the same migration, dependency-stack or initial API extraction approval.

## Never

- Alter bytes of the 48 protected V5/V5.1 or 33 sealed V6 files. Relocation is
  recorded in [the owner-authorized amendment](docs/amendments/composedoc-relocation-v1.json).
- Leak Compose/Swing/AWT, data, UI or apps into model/domain; leak apps into UI/layout.
- Claim all languages/platforms, native IME/AT or print fidelity from synthetic tests.
- Write a contributor's PR title/description, issue body or review reply. The human
  writes those and attests to understanding AI-assisted code (see CONTRIBUTING.md).
- Treat a generated summary, old plan or passing unit suite as proof of current readiness.

Frozen baseline status and original hashes remain in
`docs/plans/v6/v6-plan-manifest.json` and `v6-freeze-record.json`. C0/S0 collaboration
and schema gates and GP0 output qualification remain evidence-driven. Local root
snapshot Undo is not collaborative Undo. Reuse suitable established components;
do not claim ComposeDoc is the first Compose rich-text editor.
