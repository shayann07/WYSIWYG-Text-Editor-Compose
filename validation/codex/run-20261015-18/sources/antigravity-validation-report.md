# Revision 4.13.0 — integrated executable validation

Author: Codex. Run: `validation/codex/run-20261015-17`. Executed 2026-10-15T16:03:44.776Z.
Environment: Node v24.20.0, win32 x64.

**The admission, structural composition and Undo ledger now execute together through one public request dispatcher and one outer publication transaction. All 6 integrated test groups pass. No production implementation was started.**

## Implementation of the validation model

`IntegratedModel.dispatch()` captures immutable native/application requests, reserves identified sessions, maps published-basis targets and proposed post-edit selections, and queues reentrant work. It owns the connection/generation, published snapshots, receipts, history/redo and command outcomes. A private tagged-tree/segment worker stages composition; its legacy history is ignored. Settlement drains the real-root action ledger before one final publication. Full transaction rollback restores document, protocol and history state together while preserving independently queued external work.

This is actual combined execution, not a runner invoking three disconnected model suites. The model uses arrays/full tree copies and synthetic provenance stamps; it intentionally makes no production complexity, latency, native-host or Unicode-completeness claim.

## Executed checks

| Group | Current execution |
|---|---|
| Named integrated regressions | 35 cases through the public dispatcher. |
| Generated structural/command interactions | 255 traces with independent expected raw splice results and exact fixture-tree restoration. |
| Bounded queued-command exploration | 240 traces, up to 3 queued commands, across clean/dirty/partial states and commit/cancel. |
| Repeated sessions | 80 sequential sessions, seed 0x13579bd, 10 injected settlement failures/retries and 80 rejected late deliveries. |
| Behavioral mutations | 8 caught, with 8 passing controls; each failure is an assertion, not an unrelated tool/syntax crash. |
| Owner scheduler | 130 queued requests complete; the first turn executes 64 and schedules continuation. |

Elapsed test-run time: 6.458 seconds. This is test duration, not an editor performance benchmark. Reproduction command: `node validation/codex/run-20261015-17/verify.mjs`.

Named coverage includes cross-list replacement and tail preservation; failed initiator/dependent continuations; partial commitment plus cancelled formatting and multiple Undos; changed terminal commit without preedit; cancellation without double Undo; full rollback after staged formatting; published-basis reentry; surviving continuation after a failed terminal callback; generation barriers and AfterTicket selection bases; post-edit selection mapping; semantic no-op revisions; obsolete settlement evidence; connection turnover/disposal; duplicate receipt reentry; foreign-flow and object-seam rejection; atom detours; code/prose conversion; backward islands; subsequent-session hierarchy rebasing; observer isolation; host-fence rejection; fragmented target rejection; immutable payload capture; grapheme formatting/Undo; one-publication settlement; turnover rollback; and unrelated formatting after cancellation.

## What broke and was fixed

- **Actual integration defect:** will-change observer exceptions were initially treated as transaction failures and rejected valid native input. Notifications now have no admission authority. The before-repair observation is preserved in `integration-probes-before-repair.json`; the regression and observer-veto mutant distinguish the corrected behavior.
- **Repeated-start range defect:** a second composition-start request in an active session returned success without applying its new marked interval. It now follows the exact retarget path. The original [0,1) versus requested [2,3) failure is preserved in `final-review-before-repair.json`.
- **Protected-structure bypass:** explicit-range preedit could indirectly retarget through the private worker without the structural classifier, allowing it to consume an unselected inline atom. Changed explicit ranges on preedit/commit/native removal now share the guarded retarget path. The failed pre-repair assertion and behavior mutant are preserved alongside the repeated-start case.
- **Previously selected atom rejected:** the inherited structural classifier rejected any atom on retarget, including an atom admitted in the initial selection. Session-scoped structural identity permissions now distinguish retaining an already-selected atom from acquiring an unselected one, and are included in rollback state. The original failure is preserved in `selected-atom-before-repair.json`.
- **Test-fixture corrections:** the initial reentrant TAIL range omitted its last character, and a later post-preedit range incorrectly reused the original length. Corrected from the explicit strings: original TAIL [6,10), post-XY TAIL [5,9). The first failing runner output remains in a `failure-*.json` file. No implementation was altered to accept the invalid expectations.
- **Integration requirements implemented and exercised:** one owner-wide session namespace (no duplicate unit IDs across recreated workers), canonical hierarchy rebasing, full-root history rather than string transforms, complete outer rollback, pending external requests surviving rollback, stale-identity checks before duplicate lookup, canonical semantic equality, and receipt publication before notification reentry. These are integration changes, not invented claims of earlier failing tests.

## Final review and limits

The previously documented separation of admission, structural composition and the Undo model is closed for the operations exercised here. The current executable model can be used as a common validation baseline for those contracts. Source hashes, named cases, mutation evidence and environments are recorded in results.json.

Still unqualified: actual OS session provenance and completion fences; native/Web DOM interaction; accessibility; complete product command, typing-intent, registered-anchor and cell-flow coordination; the pinned full Unicode corpus; durable journal recovery; Kotlin persistent storage and allocator behavior; and production performance. The integrated model uses one connected flow and selected attribute patches. Ordinary typing coalescing and the complete application command catalog are not implemented in this reference slice. All production gates G0-G8 remain NOT RUN; this report neither advances phases nor authorizes production implementation.
