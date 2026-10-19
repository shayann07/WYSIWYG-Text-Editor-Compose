# ComposeDoc repository guidance

Follow root AGENTS.md and docs/project/PROJECT-JOURNAL.md. ComposeDoc is a
multilingual Compose Multiplatform document-editor library. The desktop workspace
is an example consumer, and Urdu/Nastaliq is a demanding initial qualification
workload, not the library's language boundary.

- core/model: pure common Kotlin values, identity, Unicode and paragraph/page policy.
- core/domain: single-owner engine, commands, attributed history and persistence ports.
- core/data: persistence/codec/resource implementations of domain ports.
- core/layout: typography, geometry, pagination and output contracts.
- core/ui: immutable EditorUiState, EditorIntent dispatch, state holders and platform adapters.
- apps/desktop-workspace: all toolbars, dialogs and workspace workflow.

Dependencies point inward. Model/domain cannot import Compose, Swing, AWT, data,
UI or apps. Layout/UI cannot import apps. Preserve raw UTF-16 separately from
grapheme-safe user actions, stable identities, native-range fidelity and V6
D01–D10. Never normalize stored text as a convenience. Do not infer Nastaliq or
bidi correctness from Latin tests.

Run `python tooling/docs/validate_docs.py`, `./gradlew check apiCheck`, and the
relevant `tooling/verification/verify.py` milestone. Preserve all 81 protected
historical/specification hashes. Reference locations through the relocation ledger.

AI-assisted code is allowed only with human accountability. PR titles/descriptions,
issue bodies and review replies must be human-written. Do not generate those on
the contributor's behalf. Automated reviewer findings may appear as bot comments,
never as replacement PR prose or a human attestation. Formatting checks cannot
prove authorship. Report observed defects with evidence, distinguishing automated
model/host checks from real native input, assistive technology and print qualification.
