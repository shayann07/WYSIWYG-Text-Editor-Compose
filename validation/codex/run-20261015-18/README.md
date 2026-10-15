# Run 18: reviewed repairs and retention

Validation model/specification only. From the repository root:

```powershell
node validation/codex/run-20261015-18/verify.mjs
python -X utf8 validation/codex/run-20261015-18/publish.py
```

The runner executes baseline reproductions, repair/AWT/retention/structural checks, behavioral mutants and all retained integrated suites. It writes results.json. The publisher validates source hashes before updating root documents and the 4.14.0 standalone package.

integrated-model.mjs owns publication, admission, bounded protocol state and history. structural-model.mjs uses working ancestry and checks join classes/owners. awt-contract-model.mjs tests coordinate behavior using supplied synthetic geometry only. test-repairs.mjs supplies new counterexamples and retention checks; test-integrated.mjs retains previous suites with normalized session ordinals. retention-contract.md is the normative ownership/expiry policy.

The copied old worker's history is not authoritative. Native provenance, real AWT/DOM/AT, full product semantics, physical memory, production complexity and durability remain unqualified. Prior runs are untouched.
