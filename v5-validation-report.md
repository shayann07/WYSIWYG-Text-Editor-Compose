# v5 validation and independent disposition — 16 October 2026

**Verdict: freeze v5.0.0 as the reviewed specification/conditional architecture baseline. All 25 executable verification groups pass. No production implementation or production gate advancement.**

This verdict does not assert perfection or platform feasibility. The freeze locks chosen semantics; failed qualification has explicit reopen conditions. The full standalone artifact is editor-v5-standalone.md and the normative source is plan-v5.md. v4.15.0 remains historical, unchanged.

## Independent assessment of Gemini

I reproduced the supplied 25 baseline disagreements and ran its proposed repairs, inherited tests and scripted 60-trace exercise. I then examined what the assertions and prototypes actually establish. Its "any exception means reproduced" approach counted an invalid baseline fixture as a defect; passing the aggregate alone was insufficient.

- **Accepted:** 22 interaction counterexamples covering composition metadata path dependence, ledger fall-through, retarget seeds, deleted-object heads, legal derived carets, surrounding deletion, explicit-target/cell separation, expected queued refusal, nested quote Enter, user caret snapping, isolated-surrogate policy, rich-paste preservation/sanitization, empty-block copy and aggregate transient retention.
- **Rejected as a baseline defect:** F07B constructs IntegratedModel with a string instead of its required tree and uses another API, while changing the repaired fixture. Existing tagged-atom formatting isolation already passed. The same valid tagged fixture is now checked independently.
- **Rejected:** F10A_1 prohibits TextHitInfo.trailing(-1), which is the valid beforeOffset(0) alias. JDK 23 TextLayout accepted it in an executed headless Java probe. Primary references: [TextHitInfo](https://docs.oracle.com/en/java/javase/24/docs/api/java.desktop/java/awt/font/TextHitInfo.html) and [InputMethodRequests](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html).
- **Reclassified:** F10A_2 becomes a defensive null-hit fallback during composition, not a mandatory JDK rule.
- **Accepted with correction:** the two-component hierarchy measure and current-version membership requirement. Its prototype resolves a removed leaf to rank 0 through an obsolete FlowRoot, so its timing cannot qualify the full index. Current-root membership and old-version independence are now explicit.
- **Accepted with different recovery policy:** preserve Dc and Dv, but restore committed content first with a retained recoverable draft. Explicit draft application is one undoable owner transaction. Unresolved drafts survive later saves/checkpoints; same-document-revision records use a separate durable sequence.
- **Not accepted as proof:** its journal uses SHA-256/in-memory Buffers, not CRC32C or actual fsync. Its C++ uses SimulatedRun rather than the full Skia pipeline. Its Chromium script manipulates ordinary DOM Selection without an OS IME. These are scoped probes and hypotheses, not physical/native/end-to-end qualification.
- **Rejected fallback requirements:** mandatory custom Skia patches, a universal M<=256/1ms guarantee, display-style coalescing that changes semantics, blanket callback rejection after epoch increment, and an unqualified three-screen accessibility window. Backend or provenance failures block qualification/reopen the decision; they do not silently weaken requirements.

The unchanged review files and the full findings remain in reviews/gemini-v5-readiness/. The separate input manifest proves which bytes were inspected. No reviewer claim was adopted solely on authority.

## Additional defects in the proposed repairs

Executed independent traces found: unregister undone by composition cancel; explicit anchor relocation reset on cancel; a newly created candidate anchor drifting on an identical preedit; same-DocumentRevision terminal commit ignored during recovery; and deleted index tokens still resolving through old roots. The integrated repair keeps committed and explicit visible-origin bindings separate, respects handle generations/lifetimes, advances metadata on actual Dc changes, validates current-root membership and orders persistence by DurableSequence. An additional direction regression checks code-point surrounding deletion preserves reversed selection.

The installed Node v24.20.0 runtime reports Unicode 17.0 / ICU 78.3. Its Intl grapheme result disagreed with Unicode 16 at the scissors-ZWJ fixture. We added a hashed-table Unicode 16 reference, rather than changing expected test output or silently changing the pinned version.

## Current executed results

Environment: {'node': 'v24.20.0', 'platform': 'win32', 'arch': 'x64'}; execution timestamp 2026-10-15T22:01:06.713Z (UTC). Node verification duration 7.802 seconds is test duration, not editor latency.

| Evidence | Result and scope |
|---|---|
| Accepted Gemini regressions | 22 pass through the integrated v5 DocumentModel. |
| Independent repair checks | 13 pass, including handle lifetimes, native object deletion, exact native selection, AWT aliases, surrounding-delete direction and disposal. |
| Official Unicode 16 grapheme corpus | 1093 pass against pinned property tables. Kotlin, word boundaries, bidi and shaping remain unqualified. |
| Recovery state/byte protocol | 17 scenarios; 1145 distinct byte cuts. No physical filesystem claim. |
| Hierarchy measure oracle | 100 generated trees; 4147 rank checks; deleted boundary/detached membership checks. No optimized-index claim. |
| Retained document coverage | 66 named regressions, 35 inherited cases through DocumentModel, 51 command families. |
| Retained matrices | 100 anchor cases and 54 cell-composition cases. |
| Retained behavioral mutations | 25 caught with passing controls. |
| Other retained base suites | Repair, structural-chain, synthetic AWT, retention/soak, reentry, generated integration, ledger, repeated-session and scheduling groups all pass. |
| JDK 23 boundary probe | Executed javac/java headlessly; valid boundary aliases accepted. Not a native IME/window test. |
| Original storage/recovery probe | Rerun and archived separately; its own checks pass, but additional detached-membership and same-revision-recovery tests fail. Not counted as qualification. |

Reproduce the current model with `node validation/codex/run-20261016-20/verify.mjs`. Detailed outputs, exact source hashes and failures discovered during repair are in that directory. The tests overlap contracts and must not be added together as independent proofs. Full copies, bounded scenarios and a corpus are not a complete formal proof.

## Freeze and remaining work

The review has selected a concrete correction for every accepted design finding. No known failing counterexample remains in the frozen reference surface. We can honestly freeze this specification; we cannot honestly claim a production-ready editor or that only platform testing remains.

Remaining gates cover actual Kotlin persistent indices and allocations, broad independent differential traces, physical storage/power-loss, HTML and system clipboard/resource adapters, production Unicode word/bidi/table ports, native IME/DOM/AT feasibility and full-pipeline performance. Real-host failures may require a bridge/backend revision under change control. G0-G8 are not marked passed. The source/evidence-backed plan and reopen criteria are frozen, not empirical facts about unbuilt software.
