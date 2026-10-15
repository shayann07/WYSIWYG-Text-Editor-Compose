# Final contract closure and readiness review — revision 4.10.0

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-14`.

**Verdict: the five final-review design/contract gaps are closed. The plan is a concrete baseline for gated implementation of its declared scope. It is not a production qualification, a proof of arbitrary execution correctness, or authorization to start implementation.**

## The five decisions and evidence

| Finding | Normative decision | Validation |
|---|---|---|
| Published versus staged reads | External protocol queries and notifications observe the published snapshot only, including session identity and completion token. Working state is reducer-private. | Successful/failed preedit probes, failed start-composition metadata probe, previous immutable-snapshot tests. |
| Settlement transaction boundary | One native settlement and its queued command batch publish atomically. Composition/formatting have separate logical undo groups, not separate publications. | Failure after command drainage restores text, attributes, selection, revisions, pending tickets, outcomes, history and receipt state. Retry creates exactly one of each group. Formatting Undo and composition Undo restore their distinct preimages. |
| Cross-paragraph composition | Every composition belongs to one flow/text block. Native cross-block composition rejects without changing the document. Explicit application structural deletion is a separate committed edit with its own undo group. | Tagged-projection classifier covers block separators, same-block text, empty blocks, protected atoms, and retargeting to another block. Cancellation/history preparation examples are explicitly scoped fixtures, not a tested hierarchical reducer. |
| SessionRevision | The transaction owner assigns exactly before+1 on each successful execution/publication, including selection, typing intent and accepted no-ops. Rejection, duplicates and queued transport acknowledgements do not advance it. | Original run-13 defect reproduced; direct session operations, no-ops, rejected selection, duplicates, settlement batches and obsolete evidence verified. |
| Invalid synchronous evidence | Rejection only: no implicit Undo queue, no mutation, no consumed record, no revision. Ordinary asynchronous Undo is a separate request. | Eight malformed/stale/mismatched evidence cases reject without state changes; ordinary Undo still queues. |

The single-block rule is a deliberate baseline capability limit. Cross-block selection, explicit semantic replacement, rich paste and admitted noncomposing native replacement remain supported by the plan. One-step cross-paragraph IME replacement/reconversion is outside the baseline. An application must not silently pre-delete such a selection or describe the two-edit preparation workflow as equivalent to one-step IME replacement.

During the final consistency pass, adjacent stale wording was corrected: retarget/mark-existing may start an implicit session; resumed preedit returns to Composing while preserving queued obligations; partial commit is guarded by the presence of a marked candidate rather than only the lifecycle label; zero committed units are valid; replacement tails and retained-original tails have distinct footprint rules. Partial commit now invalidates prior settlement evidence inside the atomic request executor. Public session-ID/token queries now use the published snapshot. These changes reinforce the chosen contracts without introducing a replacement architecture.

## Executed checks

Command: `node validation/codex/run-20261015-14/verify.mjs`.
Environment: v24.20.0 / win32 / x64.
Elapsed: 120.87 seconds.

- All 17 verification groups passed.
- 22 retained regression blocks; 18 Candidate B and oracle fixtures; 21 settlement scenarios; 17 semantic traces; 10 longer sequences.
- 85 selected lifecycle/event cells and 22 retained transaction tests. These are sampled cases, not exhaustive protocol verification.
- 56 inherited behavioral mutants, 4 request-boundary mutants, and 4 final-contract mutants: all 64 assertion kills with passing controls.
- 11 named request-boundary checks, 36 generated immediate/deferred cases, and a 41-request scheduled-continuation test.
- Five final-contract checks, eight rejected-evidence cases, nine tagged-projection cases, and two explicitly scoped structural-preparation examples.
- 1,200 randomized persistent-index edits with 30 retained historical versions and instrumented maintenance workloads up to 16,384 original pieces.
- Retained depth-4 exploration: 146,594 recorded states, 203,280 transitions; no violations of its implemented assertions. This does not include all native callbacks, all scheduler interleavings or the hierarchical document engine.
- Prior run-13 files remain unchanged, verified by hashes. Before-state root documents are preserved in `sources/`.

The oracle was not edited in this round. Candidate A's existing 8 fixture failures remain expected comparison evidence; they are not failures of the selected Candidate B.

## Final review and readiness boundary

The review checked the five decisions against the core model, public request path, publication/rollback behavior, lifecycle table, history grouping, input admission and acceptance gates. No unresolved decision remains among these five contracts. A successful test run does not establish that the entire eventual engine is bug-free.

Remaining work is explicitly gated implementation and empirical qualification:

- Kotlin implementation of the document tree, semantic structural commands, attributes, persistence and platform projections; integration of the separate coordinator and identity-index models.
- Real Android/iOS/Desktop/Web IMEs, synchronous reentry/acknowledgement behavior, settlement provenance and browser DOM preservation.
- Editable accessibility, full pinned Unicode conformance, physical crash recovery and end-to-end performance, especially large single paragraphs.
- Measured allocator/fragmentation costs on actual targets. No production memory or latency gate passed here.

Those experiments can invalidate feasibility assumptions and require a documented revision. They are not represented as already solved. If one-step cross-paragraph IME replacement is required for the product, this baseline scope must be expanded before that capability can be considered implementation-ready.

No production implementation, scaffolding, dependency freeze or phase advancement was performed.

Plan SHA-256: `c7ee83785aa883f25d37bc7d97f16ea59a69cf878cdee42331f71c0b10ff002c`. Executable source hashes and detailed results are in `results.json`.
