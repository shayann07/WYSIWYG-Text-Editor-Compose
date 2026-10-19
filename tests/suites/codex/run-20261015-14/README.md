# Final five-contract closure — run 14

Specification and validation models only; no production code.

From the workspace root:

```powershell
node validation/codex/run-20261015-14/verify.mjs
python -X utf8 validation/codex/run-20261015-14/publish-evidence.py
```

`runner.mjs` aliases verify.mjs. Verification writes results.json and exits nonzero on failure. Publication checks source hashes and refuses failed or stale results. The slowest check is bounded model exploration; it typically takes one to three minutes on this host. No network or third-party installation is needed.

`test-final-contracts.mjs` covers the five decisions. `composition-admission.mjs` is a tagged-projection classifier, not a production hierarchical reducer. Earlier suites, the independent oracle and the versioned-index model are retained. Prior run-13 files are checked against baseline-hashes.json. `sources/` preserves the previous root documents.

Root report filenames retain the established antigravity prefix for continuity. Codex performed this revision and verification directly. The evidence report states the readiness boundary and the single-block composition scope prominently.
