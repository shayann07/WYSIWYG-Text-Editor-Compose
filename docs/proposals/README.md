# Proposed additive V6 clarifications

These records are review material, **not accepted normative amendments**. Owner
acceptance is pending. The 48 historical and 33 sealed files remain unchanged.
No generator may overwrite their standalone, alias or manifest to apply these.

Issue 22's `v6-g1-clarification-v1.json` combines the existing adjacent G1 paragraphs
into one definition. Both sets of requirements remain included. It does not claim
the production kernel satisfies them. Issues 23 and 24 may add separate dependent
records for invariant mapping and measurable qualification proposals.

Issue 23 supplies `v6-invariant-gates-v1.json`; issue 24 supplies the machine-readable
`v6-measurable-qualification-v1.json` capability/environment/trace matrix. All G0-G8
full qualifications remain NOT RUN. Scoped representative results are separate.
Numeric sampling/leak tolerances are proposals for owner acceptance, not observed
results. G1 storage heap and latency use the existing G8 core/history budgets;
counter locality proposals must be reviewed against the chosen data structure.
Android lower-API, genuine multilingual IME and actual AT speech remain open.

```sh
python tooling/docs/validate_proposals.py
python -m unittest discover -s tooling/docs -p 'test_proposals.py'
python tooling/docs/validate_docs.py
```

Acceptance must be an explicit owner decision, recorded separately after review;
the validator deliberately rejects self-approval. Human-written PR metadata is
required by CONTRIBUTING.md. No public PR or owner attestation is generated here.
