# Issue 17 — fresh inherited witnesses, 23 October 2026

Status: implemented and locally verified; proposed PR depends on issue 15's
transitive integrity record. Runtime pinning remains contributor PR 30's work.
No historical tag, sealed source or result is changed.

Run from the checkout after `npm ci --ignore-scripts --prefix tests/suites/integration/c0`:

```sh
python tooling/verification/reproduce_inherited.py
python -m unittest discover -s tooling/verification -p 'test_inherited_*.py'
```

The relocation-aware wrapper executes all 25 inherited groups and all 16 V6
witness groups. Fresh JSON and raw stdout/stderr go into a newly created ignored
directory. It first verifies the transitive imported-file closure. Comparison
rejects missing, duplicate, additional, renamed or failing groups, all changed
deterministic details, source hashes, scope/limits and unknown suite metadata.
Group order is immaterial; group identity and complete contents are mandatory.

Only top-level runtime/platform observations and unicodeCorpus's ICU/runtime
Unicode observations are separated from deterministic assertions. There is no
blanket timing-key filter: measurements and nested fields remain asserted.
An observed Node/ICU/Unicode mismatch produces PARTIAL and a nonzero exit after
running the witnesses, even when deterministic values agree. A deterministic
failure remains FAIL in that environment. Platform is recorded without treating
a Windows or Linux run as native qualification of another platform.

Local result: Node 24.20.0, ICU 78.3, Unicode 17.0 on Windows; 25 + 16 groups PASS,
with six comparison-contract tests PASS. Evidence is
`tests/local-evidence/repair-batch-20261023/issue17-final/` in the primary checkout.
The unchanged sealed corpus still uses the pinned Unicode 16 grapheme fixtures.
These are historical model witnesses, not production Android/native proof.

CI's documentation job and the existing final-gate witness slot run this complete
reproduction. Existing historical tags remain unsigned historical facts; no tag
has been rewritten, signed or created. Merge readiness still requires owner
review, human-written PR metadata, dependency integration and hosted checks.
