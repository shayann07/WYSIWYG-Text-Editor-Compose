# Revision 4.12.0 — executable contract validation

Run: `validation/codex/run-20261015-16`. Environment: {'node': 'v24.20.0', 'platform': 'win32', 'arch': 'x64'}. Executed: 2026-10-15T15:32:18.703Z.

All 7 groups passed in 0.420 seconds. This is model test duration, not an editor performance benchmark.

| Group | Executed scope |
|---|---|
| Structural named cases | 20 cases: selected inline/block atoms, table payload, unchanged/detour candidates, same/sibling/ancestor/descendant/list-to-outside/outside-to-list/quote replacements, nested tail order, nonterminal unmark, native removal, turnover cancellation, empty attributes and code/prose. |
| Segment ownership | 6 cases: corrected T5, backward disjoint retarget and full/partial marked commitment, absorbed-prefix preservation. |
| Generated structural traces | 324 traces over four fixture hierarchies and all selected intervals, using independent expected plaintext splices and exact original-tree restoration. |
| Input admission | 6 synthetic-stamp traces: queued continuations, failed initiator, FIFO generation barrier, explicit after-ticket selection basis, ambiguous-host refusal and stale-basis rejection. |
| History ledger | 8 traces: cancel vs commit, changed terminal commit without preedit, partial commit plus formatting and multiple Undos, interleaving, atomic failure and stack identity. |
| Bounded history exploration | 726 traces, at most four queued commands; checks history-root continuity, redo continuity and one outcome per ticket. Not exhaustive state-space proof. |
| Behavioral mutations | 6 caught with 6 passing controls: atom provenance loss, premature unmark flush, loss of trailing island, guessed provenance, double Undo on cancel and ignored history-order guard. |

## What changed and why

The specification replaces its contradictory composition chapter with a single contract. Hierarchy reconstruction uses immutable source paths, not replayed open/close markers. Ordinary sibling list items can join, while other unselected trailing subtrees retain order and ancestry. ListItem now permits any nonempty block sequence; no synthetic paragraph is inserted merely to repair a first-child rule. Initial selected structure can be replaced provisionally; later unselected expansion is more restricted. The action ledger avoids both double Undo after cancellation and attempts to remove an interior snapshot-history entry.

First execution exposed an incorrect hand-written expected string in the sibling-list fixture: the unselected space in `Buy ` must survive, so the result is `Buy and mom`, not `Buyand mom`. The fixture was corrected from its explicit interval; the implementation was not changed to match it. The first failing output is retained in `first-run-results.json`. The debate's T5 expectation is separately corrected to `aXPQ`; d is selected and replaced.

A later rollback test exposed an actual wrapper defect: the inherited transaction snapshot copied a fixed list of published fields and dropped the new structuralTree field on rejection. The wrapper now retains the entire immutable published snapshot in its transaction preimage, plus structural seed/owner state. The failing output is preserved in `rollback-failure-results.json`; the unsupported-object-seam rollback regression now passes.

## Limits and honest readiness

These results settle the listed traces within the implemented reference contracts. They do not establish all combinations. Structural composition uses the copied run-15 reducer plus a new tree wrapper. The admission registry and LIFO ledger are separate executable models, not integrated replacements for every legacy public entry point. The current report makes no claim that the copied coordinator already implements the new ledger or adapter provenance. Integrated public-boundary model/Kotlin checks remain G3 requirements.

No native input host, real DOM composition, accessibility runtime, persistence fault injection, production tree optimization or physical memory benchmark was executed. No production code or implementation phase was started. All G0-G8 remain NOT RUN. A retained block object's boundary cannot always be removed by an opaque scalar splice while preserving exact legal structure; the plan explicitly distinguishes semantic object commands and requires host qualification of that seam.

The plan is ready for another independent design review. The named debate gaps have explicit policies and targeted evidence; it is not certified complete or release-ready. Source hashes are recorded with results, and before-state root documents are preserved under `sources/`.
