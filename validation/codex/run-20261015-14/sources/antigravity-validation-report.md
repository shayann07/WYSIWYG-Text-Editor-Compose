# Specification repair and validation — revision 4.9.9

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-13`.

**Outcome: the reviewed counterexamples are repaired in the executable validation models, and the specification now describes the tested mechanisms. Production implementation and all platform release gates remain unapproved and unqualified.**

This work replaces the repeated prompt handoff with direct repairs. All run-12 files were preserved and checked against their recorded SHA-256 hashes. The prior root plan/report/matrix/diff are preserved under this run's `sources/` directory. This workspace has no Git repository; these archived sources and hashes provide the before-state evidence.

## Repairs

1. Every supported public mutation method captures immutable data and uses one executor. Deferred execution repeats the same identity, payload, range and post-edit selection validation as immediate execution. `[999,999]` selection is rejected without mutation.
2. Requests observed against published `abc` retain that snapshot's identities, even while staged text is `Xabc`. The reviewed replacement now produces `XaQc`, not `XQbc`. Post-replacement selections map in their own proposed coordinate space.
3. Direct formatting no longer captures mutable caller closures. Changing a target after enqueue does not change the admitted command. Public commands received during a failed outer transaction remain independent requests.
4. Published snapshots and notification arguments are deeply immutable. Nested attribute/selection writes cannot corrupt publication; retained snapshots do not change on later edits.
5. Receipts publish before callbacks can re-deliver the same native request. Duplicate replacement is recognized before stale target mapping. One queue owner executes FIFO work in batches of 64 and schedules continuation with no additional input.
6. A new validation-only persistent B+ sequence uses versioned parent links and persistent AVL indexes. It supports interior piece insertion, deletion, rebalancing, old snapshots and exact index ownership without rebuilding every token locator. Point rank is explicitly **O(log² P)**; no O(log P) claim is made.

## Executed evidence

Command: `node validation/codex/run-20261015-13/verify.mjs`.

Environment: v24.20.0 / win32 / x64.
Total verification time: 174.76 seconds.

- 22 retained regression blocks; 18 reference fixtures for Candidate B and the oracle.
- 21 settlement scenarios; 17 semantic traces; 10 longer sequences.
- 85 selected state/event cells and 22 retained transaction tests. These are sampled cases, not exhaustive protocol coverage.
- 56 inherited behavioral mutants with 56 passing controls; 4 additional boundary mutants with 4 passing controls, all caught by assertions rather than unrelated crashes.
- 11 new named boundary cases; 36 generated immediate/deferred cases; a 41-request queue completing after budget yields without further user input.
- 1200 randomized index edits, with 30 historical versions retained and rechecked. Independent expected state is a flat ordered token list.
- Retained depth-4 model exploration: 146,594 recorded states and 203,280 transitions, with no violations of its implemented checks. This explorer does not model all reentrant schedules or native platforms; the new scheduler/boundary tests are separate evidence.
- Baseline candidate A still fails 8 of its 18 comparison fixtures, as before. This expected comparison result does not count as a Candidate B test failure or prove all alternatives unsound.

For prepend operations at 64 / 1,024 / 16,384 original pieces, the new locator changed 17 token locators at each size; parent-link writes were 5 / 22 / 39. Map traversal/allocation counters are recorded in results.json. These counters support local maintenance for those workloads; they are not production timing or heap measurements.

## Evidence corrections

The old prototype rebuilt its token index after every insertion. Its tests did not establish incremental update complexity, and insertion inside a five-unit piece occurred at offset five rather than offset two. It is preserved in run-12 and superseded here.

The old memory calculator derived text bytes from piece count and mixed assumptions with reported engine heap totals. Its 5.16/98.22 MiB totals are withdrawn. The new estimator takes unit count and piece count separately and labels assumed record/node sizes; no JVM, Android, iOS, or Web allocator measurement is claimed.

Two inherited expectations were corrected explicitly: invalid formatting ranges reject before allocating a ticket; stale epochs reject before any receipt lookup. The oracle's unrelated semantic behavior was not rewritten to mirror the new executor. Four new failure probes were first run against the preserved baseline and failed by assertion, then passed against the repaired model.

## Limits and remaining work

- The coordinator is an array-based executable model. The persistent identity index is a separate validation model. Neither is a production Kotlin implementation; their eventual integration must pass G1.
- Native IMEs, editable accessibility, browser composition DOM preservation and adapter synchronous reentry/acknowledgement behavior remain NOT RUN. Real settlement evidence cannot be established by a test object's qualification string.
- Full pinned Unicode conformance, hierarchical document operations, real crash durability and 100K-paragraph layout latency remain implementation/platform gates.
- Reentry is modeled using hooks and private test seams. The flat reference oracle and bounded explorer do not constitute a formal proof over unbounded state.
- The selected index has explicit costs; allocator layout, fragmentation limits and latency remain measurement tasks. Background coalescing is not a universal memory bound.

No production implementation, project scaffolding, dependency freeze or phase advancement was performed.

Plan SHA-256: `0ce4bb45609d98b2c2f319b463e255f60237598cea422b45a45c4ac260150926`.
Executable source hashes, actual counts and run environment are in `results.json`.
