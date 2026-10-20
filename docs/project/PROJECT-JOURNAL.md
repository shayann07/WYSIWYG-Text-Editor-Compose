# Project journal — Urdu-first document editor and open-source Compose library

## Owner amendment — ComposeDoc, 19 October 2026

The owner clarified the reusable library is **multilingual**, named **ComposeDoc**,
with Maven group/package root `dev.shayxo.composedoc`. Urdu is a primary starting
qualification workload; it does not narrow the library. The future Urdu-first
application remains a separate product.

The owner authorized Apache-2.0 for original library, validation and sample code,
a one-time hash-preserving repository reorganization, the Gradle multi-module
migration, quality/API gates, and GitHub commit/push plus governance activation
**after all required checks pass**. No Maven/package release is authorized.
This supersedes earlier statements that naming and licensing were undecided.
See `docs/reports/composedoc-migration-progress.md` for execution status; approval
is not evidence of completed migration, platform qualification or GitHub activation.

**20 October 2026:** the migration passes every required local gate (docs/hashes,
V6 witnesses, `./gradlew check apiCheck`, verification contract, 20/20 workspace
stages and 4/4 mutants). Subsequent overnight repair removed the pre-migration
Detekt debt: zero findings, no baselines. The earlier baseline is historical
evidence only. Native platform, IME, AT, PDF and print qualification remain open.


**Owner-confirmed mission, recorded 17 October 2026. Read this before making product or architecture decisions.**

## The top-level goal

Build the owner's **Urdu-first document application with Google Docs–level capabilities**, powered by a **reusable, open-source WYSIWYG rich-text/document editor library for Compose Multiplatform**.

The application is the reason this work exists. The library solves the missing editing infrastructure needed by that application and makes it available to other developers. The desktop prototype is a test harness and eventual example consumer. Completing that prototype alone does not complete the project.

The owner's phrase “every feature that Google Docs have” expresses the intended product ambition. It is not a claim that the current specification already enumerates every feature or that parity has been implemented. Later planning must create a concrete, prioritized application feature inventory without quietly reducing that ambition.

## Why we started

The original problem is reliable, high-performance WYSIWYG editing in Compose Multiplatform: caret/selection instability, native IME composition, formatting boundaries, hierarchical document editing, Unicode and consistent semantics across platforms. Solving those practical problems remains central. Novelty is not a requirement. Correctness, performance, feasibility and a usable open-source library matter more than inventing an unfamiliar technique.

Target library platforms are Android, iOS, Desktop and Web. The selected native surfaces use Compose; the current Web design uses a semantic DOM adapter controlled by the shared engine. Do not silently substitute an all-platform WebView application or drop a platform because another approach is easier.

## The two deliverables and the test harness

| Deliverable | Role |
|---|---|
| Open-source editor library | Shared document/session engine; formatting; positions and selections; native input adapters; structural editing; attributed Undo/Redo; tables and embedded content; continuous and paged surfaces; PDF/print integration; accessibility; persistence/recovery contracts; documented APIs and extensions. |
| Owner's Urdu-first application | The complete writing and document workflow: Urdu typography and language tools, document management, sharing, collaboration, comments, suggestions/review, version history and the broader Google Docs–level product experience. Detailed feature behavior and sequencing still need an application specification. |
| Desktop prototype and other samples | Integration tests, experiments, demonstrations and external consumer examples for the library. Their application shell is not the product scope. |

These are responsibility boundaries, not permanent restrictions on code reuse. A collaboration or annotation module may eventually be reusable too. In particular, the library must not make application capabilities impossible through an unexamined API/data-model assumption. No collaboration algorithm, cloud provider or application business model is selected by this journal.

The owner explicitly wants the **library** open-source. Do not assume the whole custom application must use the same license or be open-source. A project license, package coordinates and publishing process remain to be selected; public GitHub visibility alone is not a software license.

## Urdu is a primary requirement

Use Urdu as a first-class design and acceptance workload from the beginning, not as a later localization pass over an English editor. The application is primarily for Urdu; mixed Latin text, numbers, punctuation, URLs and other scripts must still behave correctly in real Urdu documents.

Required qualification includes:

- Nastaliq typography and qualified fonts; Urdu/Arabic shaping, joining, ligatures, diacritics, line height, wrapping and clipping.
- Right-to-left and mixed-direction caret movement, selection, hit testing, punctuation and numbers.
- Real Urdu keyboard behavior and platform input lifecycle, as well as other IMEs needed to establish the library's general correctness.
- Formatting and Undo/Redo that preserve text and attributes through composition, edits and Unicode grapheme changes.
- Urdu in paragraphs, nested structures, table cells, page boundaries, headers/footers and accessible reading/editing flows.
- Paged preview, searchable/selectable PDF text and printing with validated typography and layout fidelity.

“Supports RTL” in a README is not sufficient evidence for these requirements. Nor does one passing Urdu typing fixture qualify all Urdu editing and output.

## Page-based layout and printing are required

On 17 October 2026 the owner explicitly added **page-based layout and print-perfect fidelity**. They are no longer excluded from the current requirements baseline.

The v5.1 amendment includes page settings, margins/orientation, explicit page breaks, headers/footers/page numbers, fragmentation of supported content, editable cross-page geometry and PDF/print output from a qualified common page plan. “Print-perfect” means matching document/page geometry under the same qualified fonts/resources/profile. It does not promise identical physical printers or antialiasing. Missing fonts, incompatible paper settings and unqualified backend behavior must not silently change output.

Pagination does not excuse arbitrary paragraph chunking, resetting bidi/shaping context, dropping content, screenshot-only PDF output when semantic text is required, or breaking native input at page boundaries.

## Plan scope versus final product scope

The frozen v5.0.0 specification is a historical semantic/architecture baseline, not a finished product. The v5.1 documents added paged output and explicit library delivery gates. The current v5.2 replan connects the library to `docs/project/PRODUCT-ROADMAP.md`, `docs/project/FEATURE-CATALOG.md` and `docs/project/URDU-ACCEPTANCE.md`; it records new design gates without claiming they are implemented or validated. The catalogue is a dated initial inventory, not a claim of exhaustive parity with every future Google Docs feature.

Features deferred from an editor-library milestone, such as collaboration, are **not thereby removed from the final application goal**. Conversely, the broad application ambition does not silently authorize implementing every feature in the current task. Follow the latest requested task, preserve the larger goal, and identify missing milestones explicitly.

A continuous-only library release may be a useful labeled intermediate milestone. It must not be described as completion of the owner's full editor or paged-output requirements.

## Existing-library investigation and decision

An investigation on 17 October 2026 inspected ComposeTextEditor, Compose Rich Editor, Cascade, TextKit and PDF components. Substantial existing rich-text work exists. Do not market this project as the first Compose rich-text editor or assume all existing approaches are broken.

The current recommendation is to retain an independent document/transaction core for the full requested behavior and reuse established components selectively. The strongest inspected alternatives have concrete hierarchy/table/input-model differences; adapting a whole editor would be substantial work. This is a source-backed engineering judgment, not proof that our current prototype is more reliable or cheaper. Upstream runtime suites were not executed for that comparison.

Do not write a new Unicode shaper or PDF file-format engine by default. Reuse qualified rendering, export infrastructure, fixtures and utilities with appropriate licenses. A substantially better upstream fit or concrete integration evidence can change the recommendation; sunk effort is not a reason to ignore it. See [the research decision](<../reports/Editor library reuse decision.md>) for exact revisions, sources and limitations.

## Working principles for every agent

1. Explain progress in terms of the Urdu application and reusable library, not only test counts or prototype features.
2. Preserve working code and qualified contracts. Redesign only for a concrete defect, requirement change or measured incompatibility; do not restart novelty research.
3. Distinguish a requirement, a proposed design, implemented behavior, synthetic test evidence and native-platform qualification. Do not turn one into another in summaries.
4. Prefer reproducible automated assertions and compact logs. Use targeted computer interaction only where native behavior or visual evidence requires it; avoid expensive screenshot loops.
5. Fix demonstrated defects with regressions. Reviewers and AI reports are fallible; inspect actual code and rerun discriminating cases.
6. Do not quietly remove Urdu quality, cross-paragraph input, rich document structure, pagination or print fidelity to make a test suite pass.
7. Keep sample UI separate from reusable APIs. Document integration, packaging, lifecycle and extension contracts so another developer can use the library outside this repository.
8. Preserve the immutable v5.0.0 tag and original evidence. Version new scope/contract changes explicitly; never relabel historical validation as proof of new features.
9. Never infer completion from a frozen plan. Native input, accessibility, export fidelity, performance and durability require their own evidence.
10. Respect the latest user work allocation. At this journal entry Antigravity is implementing bounded prototype repairs, while Codex independently audits and coordinates. Do not overwrite concurrent work or begin unrelated implementation because this journal describes the eventual destination.

## Durable decisions, in order

| Date/context | Decision |
|---|---|
| Original mission | Solve Compose Multiplatform rich-document editing correctness and performance, especially caret/selection, IME, attributes, hierarchy and Unicode. |
| Before v5 freeze | Stop chasing novelty. Use the strongest feasible engineering approach and executable evidence. |
| 16 October 2026 baseline | v5.0.0 frozen as a conditional specification; this was not production qualification. Prototype implementation was subsequently authorized by the owner. |
| 17 October 2026 | Add page-based editing and print fidelity; retain v5.0.0 history and record v5.1 requirements separately. |
| 17 October 2026 | Reaffirm that the deliverable is an open-source embeddable library, not merely a desktop editor application. |
| 17 October 2026, latest clarification | The ultimate user product is a full Urdu-first document application with Google Docs–level capabilities. The open-source library is its enabling foundation. Urdu must drive design and qualification from the start. |
| 17 October 2026, this request | Record this mission in the repository and agent entry points so future agents do not lose it between sessions. |
| 17 October 2026, subsequent owner request to replan | Add product milestones M0–M8, a 46-capability catalogue, Urdu gates U0–U5 and v5.2 integration constraints. Resolve collaboration/review/schema compatibility before stable API commitments; keep current recovery repairs running. Preserve v5.0.0 and v5.1 artifacts. This is a planning expansion, not a new validated freeze. |

## Read next and resume correctly

- [AGENTS.md](../../AGENTS.md): repository agent entry point.
- [Product roadmap](PRODUCT-ROADMAP.md), [feature catalogue](FEATURE-CATALOG.md) and [Urdu acceptance plan](URDU-ACCEPTANCE.md).
- [Current complete standalone replan](../plans/v5.2/editor-v5.2-standalone.md) and [library plan](../plans/v5.2/plan-v5.2.md).
- [Integration amendment](../plans/v5.2/v5.2-integration-amendment.md) and [current planning manifest](../plans/v5.2/v5.2-plan-manifest.json).
- [Historical v5.1 scope amendment](../archive/specs/v5.1/v5.1-scope-amendment.md) and [its manifest](../archive/specs/v5.1/v5.1-spec-manifest.json).
- [Original v5.0.0 integrity manifest](../archive/specs/v5.0/v5-freeze-manifest.json): preserves 45 original frozen files.
- [Library reuse decision](<../reports/Editor library reuse decision.md>).
- [Desktop validation directory](../../tests/archive/desktop-validation): inspect current reports, source hashes and latest git state before trusting any status.

This journal records intent and durable decisions, not live task status. On resumption, read the latest user request, inspect git status/history and current artifacts, and identify what another agent is editing. Append dated owner-approved changes to this journal; do not erase the mission because an intermediate milestone is narrower. Future direct instructions from the owner take precedence.

## Owner clarification — 19 October 2026

Urdu/Nastaliq remains the primary focus, while other languages and mixed-language documents must not be left behind. Shared editing/layout repairs must preserve each script's own direction, typography and logical content; an RTL ancestor is not permission to override an LTR child's marker/text direction. Validate mixed-direction counterparts against independent expected examples and native visual evidence. Do not infer universal linguistic/IME/platform qualification from automated checks. This clarification guides the current bounded visual/multilingual repair batch and future work; it does not authorize unrelated features or a new architecture phase.

## Owner-requested reuse-first v6 review — 19 October 2026

The owner requested fresh research into existing editors/components, a complete standalone v6 plan, and an independent Argon review before finalization and the next implementation-session prompt. `docs/plans/v6/editor-v6-standalone.md` (identical content in `docs/plans/v6/plan-v6.md`) is the review candidate; `docs/plans/v6/v6-plan-manifest.json` records its status/hashes and `docs/prompts/argon-v6-review-prompt.md` supplies the review instructions. This is **v6.0.0-review.1, NOT FROZEN**. The current `.astra` v6 codec is a different version namespace.

The draft retains one semantic document owner and native Compose requirements, but makes component-reuse evaluation mandatory before substantial custom subsystem work. It identifies concrete upstream input/selection tests, Web editing, table mapping, Unicode data, collaboration and output candidates with compatibility gates. Source inspection is not adoption or native qualification. No production code, dependency, license, tag or publication is changed by this planning request.

Keep the current targeted complex-cluster/geometry qualification assignment separate; do not assume it ran or replace it with broad research. Incorporate its actual results during review disposition. After Argon's findings are independently assessed and genuine gaps resolved, the owner can accept the revised plan freeze; only then prepare the next bounded implementation handoff. Historical v5/v5.1 and v5.2 documents remain preserved.

## Documentation organization — 19 October 2026

The owner authorized consolidating documents under `docs/`. Start at [the documentation index](../README.md). Product requirements are in `docs/project/`, plans in `docs/plans/`, guides in `docs/guides/`, review prompts in `docs/prompts/`, reports in `docs/reports/`, and historical material in `docs/archive/`. Executable evidence and co-located READMEs stay beside their tooling. Ten frozen v5/v5.1 root documents/manifests retain their exact paths and bytes for verification compatibility.

The exact standalone already sent to Argon is preserved in `docs/archive/review-packages/v6-review1/`; no resend is needed. `docs/relocations.json` maps former paths, and the planning generators resolve them through `planning/doc_paths.py`. This cleanup changes no editor behavior, feature scope or qualification status. See [the organization record](../organization-20261019.md).


## Final v6 specification — 19 October 2026

The owner supplied separate chatbot and Antigravity Argon reviews and explicitly requested independent disposition, genuine-gap repairs, validation and finalization, with no production implementation here. V6.0.0 is now the final conditional specification baseline. Read [the disposition](../reports/v6-final-review-disposition-20261019.md) and [the standalone](../plans/v6/editor-v6-standalone.md), including D01–D10. The original review.1 file and both reviews remain preserved.

A zero-partial-commit reference bug was reproduced and corrected in a separate validation-only model; the 16 new witness groups and 25 inherited groups passed. Real Yjs transport/naive-Undo hazards were tested, while browser/FFI/PDF/native qualification remains open. Wrong recommendations to discard durable provisional drafts, force caret-map bijections, reject all SDK-clipped Android ranges or change intentional Undo/reconversion behavior were not adopted. Stable marker semantics replace the provisional vote policy but are not implemented by this documentation task.

The next bounded implementation assignment is [v6-next-session.md](../prompts/v6-next-session.md). No production code, dependency, license, Git tag or publication was changed here. Final specification hashes are sealed in `docs/plans/v6/v6-freeze-record.json`; future material changes require a versioned amendment, not silent regeneration.


## Independent consumer and Android slice — 20 October 2026

The owner authorized a separately built binary consumer and an Android Compose editing slice,
using the shared authoritative engine. Additive integration APIs and the minimum required
AGP9.1.1/Gradle9.3.1/compileSDK37 tooling correction were explicitly approved; runtime pins,
approved desktop marker geometry and direction-aware numbering remain unchanged. No package
publication is authorized. Actual results, source/build identity, native versus instrumented
coverage and remaining gates are in
[the dated integration report](../reports/android-consumer-integration-20261020.md), with
[consumer integration instructions](../guides/BINARY-CONSUMER-ANDROID.md). Inspect the newest
handoff and current receipts before treating any checkpoint as completion.
