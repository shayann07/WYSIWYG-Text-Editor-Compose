# Documentation organization — 19 October 2026

The owner authorized consolidating project documents into `docs/` after sending v6 to Argon. This is a location/navigation cleanup, not a specification or implementation change.

## Layout

- `project/`: journal, roadmap, feature catalogue and Urdu acceptance.
- `plans/v6/`: current review draft, source and manifest.
- `plans/v5.2/`: byte-preserved preceding plan package.
- `prompts/`: current Argon review prompt.
- `guides/`: writing, accessibility and input-recovery guides.
- `reports/`: dated audits, repair reports and latest handoff.
- `archive/`: early plans, v4 reviews, Antigravity records, research history and the exact submitted v6 package.

45 documents were relocated after source/destination boundary and collision checks. Each destination matched its source hash immediately after the move. No source document was discarded. Active Markdown links and repository entry points were then updated. Historical archival bodies and v5.2 artifacts retain their original bytes.

The exact standalone already sent to Argon is preserved at `archive/review-packages/v6-review1/editor-v6-standalone.md`. Its hash is recorded alongside it and in [relocations.json](relocations.json). Current v6 may differ only by navigation paths; version, requirements and review status remain unchanged. No resend is necessary.

## Intentional compatibility exceptions

Ten frozen v5/v5.1 documents/manifests remain at root; existing test tooling and published baselines require those original paths and bytes. The [frozen index](archive/frozen/README.md) makes them discoverable from docs. Source-adjacent READMEs and executable evidence stay with the code/probe they describe. No generated binaries, user documents, `.astra` samples, code modules or test fixtures were moved or deleted.

`tooling/docs/doc_paths.py` resolves the recorded old names for generators. V6 generation writes into `docs/plans/v6/`; its new manifest uses actual relocated paths. The historical v5.2 generator also resolves paths, but was not run to overwrite that preserved package. Archived publishers are historical tools, not instructions to regenerate root clutter.

## Validation scope

Run `python tooling/docs/validate_docs.py` to check all 48 protected hashes, preserved v5.2 artifacts, exact Argon input, generated v6 identity and local navigation links. This is document/build-identity validation, not a rerun of editor qualification. Old archived documents can contain historical dead paths; use the relocation map and current indexes.
