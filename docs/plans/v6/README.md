# V6 review package

Status: **v6.0.0 FINAL SPECIFICATION BASELINE**, with explicit open implementation and platform gates. Both Argon reviews have been independently dispositioned.

- [Standalone specification](editor-v6-standalone.md) — complete finalized requirements and corrected contracts.
- [Plan alias](plan-v6.md) — identical generated content, not another version.
- [Manifest](v6-plan-manifest.json) — actual current paths and integrity hashes.
- [Freeze decision](freeze-decision.md) and [sealed artifact hashes](v6-freeze-record.json).
- [Authoring source](source.md) — new v6 layer; the generator embeds the preserved v5.2 contracts.
- [Argon prompt](../../prompts/argon-v6-review-prompt.md).
- [Exact submitted copy](../../archive/review-packages/v6-review1/README.md).

Validate with `python planning/validate_docs.py`. After the freeze record is written, `python planning/build_v6_docs.py` verifies sealed artifacts instead of regenerating them. Material changes require an explicit versioned amendment. Do not edit generated copies independently or run a historical publisher to recreate root documents.

- [Review disposition](../../reports/v6-final-review-disposition-20261019.md)
- [Next bounded implementation prompt](../../prompts/v6-next-session.md)
- [Final contract decisions](final-contract-decisions.md)
