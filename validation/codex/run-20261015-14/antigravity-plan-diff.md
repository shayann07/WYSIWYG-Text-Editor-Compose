# Revision 4.9.9 → 4.10.0

Before-state: `validation/codex/run-20261015-14/sources/`.

1. I02 and §4.1 consistently require published-only external reads. Public session metadata now follows the same rule as text/selection.
2. §4.5 defines one atomic settlement publication with distinct logical history groups, normal ticket rejection semantics, unexpected failure rollback and host recovery requirements. The old separate-transaction wording is removed.
3. §1.1/§6.1 explicitly scope composition to one block, prohibit implicit structural deletion, and define the independent history consequences of explicit application preparation.
4. §2.2/§7 centralize SessionRevision ownership, accepted no-ops and receipt revision pairs. Transition-table revision cells reference this rule.
5. §4.5 defines invalid synchronous evidence as rejection-only, names reason codes, distinguishes adapter provenance from test strings and rejects fractional/non-finite extents.
6. Final review reconciles adjacent lifecycle wording with marked-window guards, implicit retargeting and evidence invalidation. G1 covers serialization prerequisites; full I14 crash durability remains at G7.
7. Existing tests and previous runs are preserved. New evidence lives in test-final-contracts.mjs and composition-admission.mjs. No independent oracle changes were made in this round.
