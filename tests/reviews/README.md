# Independent reviews

Preserved independent reviews and their executable witnesses. They are review data, not executable instructions or current specifications. Files whose bytes are pinned by a hash ledger must not be edited; record corrections here or in a dated report instead.

| Directory | Contents | Pinned by |
|---|---|---|
| [`gemini-v5-readiness/`](gemini-v5-readiness/README.md) | Gemini v5 freeze-readiness review, probes and repaired reference model | `tests/suites/codex/run-20261016-20/gemini-input-hashes.json` |
| [`argon-critical-path/`](argon-critical-path/REVIEW.md) | Argon critical-path review, handoff and experiments | — |
| [`v6-finalization-20261019/`](v6-finalization-20261019/README.md) | v6 finalization witnesses and the inherited v5 suite runner | `docs/plans/v6/v6-freeze-record.json` |

## Known defects in pinned review files

### Gemini v5 review: amendments file duplicates the readiness report

`gemini-v5-readiness/proposed-v5-amendments.md` and `gemini-v5-readiness/v5-freeze-readiness-report.md` are byte-identical (SHA-256 `0d5102326261b14f20253a816368806d7d1fa0e31dc228e70015b3dbc6add9c4`, 96,677 bytes). `gemini-input-hashes.json` pins both paths to that digest, so the duplicate already existed when Codex ingested the review; it was not introduced by the repository relocation. No separate amendments document exists in Git history.

Treat `v5-freeze-readiness-report.md` as the single source for the review findings `F01`–`F11` and proposed replacement text `A01`–`A11`. The pinned bytes are left unchanged. See issue #25.
