# Run 16: specification contracts only

Run from the repository root:

```powershell
node validation/codex/run-20261015-16/verify.mjs
python -X utf8 validation/codex/run-20261015-16/publish.py
```

The first command writes model results. The second publishes the revised plan/report/ledger/standalone from the archived 4.11.0 source and contracts.md. It refuses a failed or source-mismatched results file. No production build or native host is involved.

structural-model.mjs extends a copied run-15 segment reducer. protocol-model.mjs contains separately executable admission and history models; it is not wired through every legacy method. test-contracts.mjs includes named fixtures, generated interval traces, bounded history sequences and real behavioral mutants. The old sources and first failing fixture output remain preserved.
