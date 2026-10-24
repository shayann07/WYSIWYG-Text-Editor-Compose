# Issue 15: imported evidence integrity

Status: implemented on the separate issue branch; PR36 is under review.
No sealed sources/manifests/results were regenerated. This is an additive audit record,
not a new normative editor specification or retroactive attestation of historical bytes.

`python tooling/docs/evidence_integrity.py` resolves 37 local modules from the inherited,
V6 and original run20 verification entry points through the recorded relocations. The new
`docs/amendments/inherited-evidence-integrity-v1.json` covers 45 files, including the complete
Gemini input ledger. All 45 digests match at least one pre-existing hash ledger. Sources are
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

Verification: discriminating tests use disposable copies, including mutations of
run17, run19 and Gemini sources, a new import whose importer hash was also changed, missing/
escaping paths, ledger tampering and unsupported dynamic import. `validate_docs.py` verifies
this record alongside the unchanged 48 + 33 protected hashes. CI and the existing final evidence
contract gate run the negative tests. Original run20 verify is inspected for imports, not
executed directly because it would overwrite its historical result.

Dependency: #17 should build on this branch; #19 remains primarily PR30. Existing contributor
branches and public PR descriptions were untouched. Owner supplies the new PR title/body and
attestations after reviewing the code and exact test evidence.

## Review corrections — 24 October 2026

The four historical ledger-key aliases are now explicit entries with purposes in
the additive record. They apply to historical addresses only. Runtime import
resolution uses the separately recorded production-loader policy: one prefix
mapping, matching the registered hook in `tooling/verification/v6.mjs`. Its source
and the pinned Node identity file are bound by hashes. A loader change requires
policy review; it does not authorize changing any frozen import or old manifest.

The supported wrapper registers real Node relocation hooks. Missing literal paths
therefore do not establish that the executed runner hashes different files. The
new maintained witness executes the unchanged V6 wrapper and its 16 groups into a
private output. It then checks 140 local/builtin/package-file resolutions using
parent-aware ESM resolution under the actual production hook, including captured
parent URLs and `import` conditions. A two-parent fixture proves that the requested
parent is honored; a conditional-export fixture distinguishes ESM from CJS.
Inspection-only roots, including the raw run20 verifier, are resolved without
evaluation. They are not relabeled as fresh inherited execution; that is issue17.

A deliberately incorrect runtime prefix must fail against the real loader. The
escape regression now creates an existing file inside a unique test-owned parent,
outside its child checkout, and requires the specific escape rejection. A missing
file or unrelated ValueError can no longer satisfy it. No generic temporary file
is overwritten. Ledger-only aliases cannot influence the runtime closure.

Reproduce from the repository root with the pinned Node runtime:

```sh
npm ci --ignore-scripts --prefix tests/suites/integration/c0
python tooling/docs/validate_docs.py
python -m unittest discover -s tooling/docs -p test_evidence_integrity.py
python tooling/verification/loader_resolution.py --output-dir tests/local-evidence/loader/new-run
```

Use a fresh output directory. The witness retains actual hook events, source and
observer identities, the evaluated V6 result, and separate resolution-only cases.
The first local attempt lacked the locked Yjs installation; that setup failure is
retained. Installing from the unchanged lock then passed. These checks establish
this pinned literal-import closure, not a general JavaScript parser/security proof,
native editing qualification, or retroactive historical integrity.
