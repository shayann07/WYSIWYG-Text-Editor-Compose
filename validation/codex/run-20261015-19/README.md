# Run 19: whole-document executable validation

Specification/reference-model work only. No production implementation.

From the repository root:

```powershell
node validation/codex/run-20261015-19/verify.mjs
python -X utf8 validation/codex/run-20261015-19/publish.py
```

`document-model.mjs` extends the existing integrated transaction owner. `document-tree.mjs` supplies full-copy document/flow, schema, anchor, structural and clipboard utilities. `test-document.mjs` tests the finite catalog and analytic anchor/cell matrices. `test-document-mutations.mjs` contains actual behavior overrides with passing controls. The retained integration/repair tests run against the copied base owner; 35 inherited named cases also run through DocumentModel.

`results.json` is the current execution record with source hashes. `failure-*.json`, `flow-roundtrip-before-repair.json` and `document-inherited-first.json` are archived intermediate failures, not current pass evidence. `document-contract.md` is the normative extension; the publisher integrates it into the root plan and standalone review package. Old runs remain unchanged.

The runner is deterministic except timestamps/timings. Its default injected clock is fixed for model cases; real typing-time policy requires a production clock. Node Intl segmentation, synthetic native evidence, full-copy roots and serialized retention charges do not validate Unicode conformance, physical memory, native/DOM/AT behavior, actual clipboard/import pipelines, durability or latency.
