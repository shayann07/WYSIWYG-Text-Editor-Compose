# Revision 4.14.0 — reviewed defects, AWT contract and retention

Run `validation/codex/run-20261015-18`; executed 2026-10-15T16:57:59.419Z on {'node': 'v24.20.0', 'platform': 'win32', 'arch': 'x64'}.

**All 14 verification groups pass. The four reviewed model defects are reproduced against run-17 and repaired in run-18. The AWT specification is corrected, and bounded protocol retention is now specified and exercised. No production implementation.**

## Repairs

1. Terminal selection is validated/applied even for commit without replacement text, terminal unmark and completion. Invalid input rolls back before history/command drainage; valid forward/reversed selections survive Undo/Redo.
2. Successive list joins use current working ancestry after each move. The previous three-item trace no longer resurrects i2 or transfers T into the wrong item. Generated chains extend through six items with exact Undo/Redo restoration.
3. Replacement ranges are dense, exactly-two safe-integer endpoints validated before normalization/mapping. Extra/missing/sparse endpoints and nonnumeric/out-of-bounds values reject without mutation.
4. Retarget admission checks owner and join class, including a target wholly within another block. Initial explicit cross-class selections remain supported; unselected prose/code or ancestry acquisition rejects.
5. AWT geometry follows the corrected offset/selection and zero-thickness rules. Eight synthetic geometry fixtures are not an AWT hosting test.
6. Completed receipts no longer retain editor/history snapshots. Compact bases have explicit cache/pin lifetimes; admission, expanded targets, history/redo and outcomes are bounded. Expired sequences and retired session ordinals cannot execute again. A dependency on an expired basis rejects rather than using current coordinates.

## Current execution

| Group | Evidence |
|---|---|
| Preserved baseline | All four run-17 counterexamples fail the expected assertions. |
| Repair regressions | 26 scenarios, including terminal selection/history, malformed inputs, nativeRemoval post-selection, domain checks and Redo settlement. |
| Successive structural joins | 10 commit/cancel chains, up to 6 list items. |
| AWT contract | 8 synthetic-frame cases. |
| Retention contracts | 13 scenarios covering eviction, pinning, expiry/replay, ordinal retirement, failures, quotas and long composition. |
| Retention soak | 400 fixed-size edits; bounded record counts sampled every 100 edits. |
| Mid-staging reentry | Published compact basis retained while private structural state differs. |
| Repair mutations | 9 behavioral mutants caught, with passing controls. |
| Retained integrated suite | 35 named cases, 255 generated structural/command traces, 240 bounded ledger traces. |
| Repeated sessions | 80 sessions, 10 failure/retries, 80 stale deliveries. |
| Existing integration mutations | 8 caught with passing controls. |
| Scheduling | 130 requests across the 64-request turn budget. |

Elapsed: 8.178 seconds. This is test-run duration, not editor latency. Reproduce with `node validation/codex/run-20261015-18/verify.mjs`. Source hashes and detailed observations are in results.json. No historical counts have been added as though rerun from a different model.

## Retention meaning and final independent check

Reference limits exercise serialized-byte/count ownership, not physical allocator usage. Receipt summaries contain no snapshots; bases contain token identities and coordinate metadata only. Admitted dependencies pin their bases/status until completion; late registration gets BasisExpired. Sequence and session-ordinal high-water marks preserve non-replay after detail eviction. Session ordinals are normalized adapter bookkeeping, not evidence of native callback origin.

The review checks that published bases are not rebuilt from staged worker state, that command target expansion is charged, and that pending-command capacity cannot exceed the outcome capacity needed to emit one atomic batch. Old private worker history/mapping/receipt caches are cleared because the integrated owner is authoritative. Document/history publication rolls back on dependency-basis capacity refusal. Receipt cache sizes cannot be interpreted as a total process-memory bound.

I found no additional failing counterexample in this final scoped review after these repairs. That is not a proof of a perfect editor, nor a claim that only platform tests remain. Full product command/typing-intent/anchor/cell-flow coverage, pinned Unicode conformance, production persistent storage and memory measurement, crash recovery, native input/DOM/accessibility and end-to-end performance still require their existing gates. None of G0-G8 is marked passed or advanced. The specification is a concrete, tested reference baseline for the covered contracts; no production implementation is authorized.
