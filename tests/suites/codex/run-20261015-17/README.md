# Run 17 — integrated reference owner

No production implementation. One connected-flow reference owner integrates admission, tagged structural composition and full-root Undo/Redo behind dispatch().

From the project root:

```powershell
node validation/codex/run-20261015-17/verify.mjs
python -X utf8 validation/codex/run-20261015-17/publish.py
```

The runner writes results.json and archives failures; the publisher requires matching passing source hashes before publishing the revised root documents and standalone package.

- integrated-model.mjs: public owner, admission registry, snapshot publication, full rollback and action ledger.
- structural-model.mjs / candidate-b-coordinator.mjs / request-contract.mjs: copied structural/segment working components. Their old internal command/history API is not the integrated owner's public authority.
- test-integrated.mjs: every integration trace edits through dispatch(), including generated and repeated-session cases.
- integration-contract.md: normative section added to the plan.
- integration-probes-before-repair.json and failure-*.json: observed pre-repair defect/fixture evidence.

Arrays/full copies and synthetic host stamps are intentional reference-model limits. Production, native hosts, complete command/anchor/cell/typing-intent coverage, durable recovery, full Unicode and performance remain unqualified. See the report for exact counts and scope.
