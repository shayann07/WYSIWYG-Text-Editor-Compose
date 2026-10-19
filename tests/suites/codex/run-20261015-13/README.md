# Codex specification repair, run 13

Scope: specification and validation models only. No production implementation.

From the workspace root:

```powershell
node validation/codex/run-20261015-13/verify.mjs
python -X utf8 validation/codex/run-20261015-13/publish-evidence.py
```

`runner.mjs` is an alias for verify.mjs. `results.json` contains actual results and executable source hashes; verification exits nonzero on failure. Evidence publication refuses a failed verification result. The bounded explorer is the slowest suite (roughly two minutes on this host). No network or additional dependencies are needed.

`sources/` contains the pre-edit root documents. `baseline-hashes.json` covers the preserved run-12 files. `reproduce-failures.mjs` runs the four new boundary assertions against that baseline without modifying it.

The inherited tests are retained. New tests live in `test-public-boundary.mjs` and `test-versioned-index.mjs`. `request-contract-spec.md` and `identity-index-spec.md` are the section text incorporated into the authoritative root plan.

The prior sorted-array/rebuild index prototype is preserved in run-12. The similarly named current compatibility entry runs the new versioned-index tests. No old index timing or heap claim carries forward.

Root report filenames retain their established antigravity prefix for continuity; revision 4.9.9 was repaired and verified directly by Codex.
