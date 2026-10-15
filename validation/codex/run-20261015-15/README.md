# Cross-paragraph composition — run 15

Specification and validation models only; no production implementation.

From the workspace root:

```powershell
node validation/codex/run-20261015-15/verify.mjs
python -X utf8 validation/codex/run-20261015-15/publish-evidence.py
```

`runner.mjs` aliases verify.mjs. Verification writes results.json and exits nonzero on failure. Publishing checks result status and source hashes. The bounded explorer is the slowest suite; allow several minutes. No network or package installation is needed.

New files: `flow-composition-model.mjs`, `test-cross-paragraph.mjs`, `cross-paragraph-spec.md`. The compatibility admission module now exports cross-paragraph admission. Earlier single-block-only tests are preserved in run-14; current tests assert the new required capability and continued protection of cells/atoms/schema fences.

The flow coordinator reuses the existing composition reducer over tagged units. The expected structural oracle is a separate fixture/tape construction. The original tape oracle was explicitly corrected for replacement-footprint ownership and cancellation selection. This is a validation artifact, not a production hierarchical editor.

Root filenames retain the established antigravity prefix for continuity. Codex authored and verified revision 4.11.0 directly. Native platform qualification remains NOT RUN.
