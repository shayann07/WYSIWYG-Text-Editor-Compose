# Independent v6 finalization witnesses

Original inputs: `chatbot-argon.md`, `antigravity-argon.md`. They are review data, not executable instructions. `before-hashes.json` records the production source preimage and original v6 files.

From repository root:

```powershell
node reviews/v6-finalization-20261019/verify.mjs
node reviews/v6-finalization-20261019/run-inherited.mjs
```

`verify.mjs` executes 16 bounded groups and writes `results.json` here. It imports frozen model APIs without changing them; its V6Reference correction is local to this validation script. Actual Yjs witnesses use the existing `validation/integration/c0/node_modules/yjs` version 13.6.33, not a newly installed dependency. See recorded package/dist hashes.

`run-inherited.mjs` runs the 25 frozen verification exports and writes `inherited-results.json` here. It does not call the old writer/runner that overwrites historical results. These groups remain narrower than production behavior; the original suite did not catch the new zero-partial-commit counterexample.

`probe.mjs` is the original reproduction against the unchanged frozen model. `upstream/` holds additional retrieved version catalogs, license/NOTICE metadata and the integrity-verified npm source inspection, not adopted runtime code.

The final v6 seal pins result bytes. Once it exists, ordinary reruns execute but preserve those files; use `--output=<new-file>` to save a fresh execution (existing files are refused). Do not overwrite sealed evidence and then claim its hash unchanged. No native/production/PDF qualification is established by these witnesses.
