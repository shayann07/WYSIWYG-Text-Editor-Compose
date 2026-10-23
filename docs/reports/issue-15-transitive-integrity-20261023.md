# Issue15: imported evidence integrity

Status: implemented on a separate issue branch, pending owner review/PR metadata.
No sealed sources/manifests/results were regenerated. This is an additive audit record,
not a new normative editor specification or retroactive attestation of historical bytes.

`python tooling/docs/evidence_integrity.py` resolves37 local modules from the inherited,
V6 and original run20 verification entry points through the recorded relocations. The new
`docs/amendments/inherited-evidence-integrity-v1.json` covers45 files, including the complete
Gemini input ledger. All45 digests match at least one pre-existing hash ledger. Sources are
explicitly identified: original v5/v6 seals, run18's run17 ledger, run19/run20 source hashes
and the run20 Gemini ledger. The unsealed ledgers provide historical correspondence, not
the stronger guarantee of a sealed manifest. A future current-only capture must be labelled
CURRENT_CAPTURE_ONLY and cannot claim to prove old execution identity.

The local closure is recomputed before digest checks. Added/missing imports, escaped paths,
changed covered bytes, changed provenance ledgers and false provenance fail. Nonliteral
dynamic imports and import expressions inside templates are refused for explicit review.
Node builtins and the existing Yjs package import are recorded as external; npm resolution
metadata/integrity is checked separately through its existing package lock. Root engine-warning
metadata is excluded from dependency identity so issue19 can pin its runtime without pretending
that this changes the imported Yjs bytes. This record does not hash an installed node_modules
tree; fresh execution must install from the lock and record the actual runtime (#17).

Verification: six discriminating tests pass, using disposable copies, including mutations of
run17, run19 and Gemini sources, a new import whose importer hash was also changed, missing/
escaping paths, ledger tampering and unsupported dynamic import. `validate_docs.py` verifies
this record alongside the unchanged48+33 protected hashes. CI and the existing final evidence
contract gate run the negative tests. Original run20 verify is inspected for imports, not
executed directly because it would overwrite its historical result.

Dependency: #17 should build on this branch; #19 remains primarily PR30. Existing contributor
branches and public PR descriptions were untouched. Owner supplies the new PR title/body and
attestations after reviewing the code and exact test evidence.
