# Cross-paragraph composition — specification and validation, revision 4.11.0

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-15`.

**The single-block composition restriction is withdrawn. Cross-paragraph composition is a required capability with a concrete flow-scoped structural overlay and executable validation. No production implementation was started.**

## Chosen behavior

Paragraph/heading separators are tagged structural units, each retaining the exact right-block identity and properties. Composition can replace a range spanning compatible paragraphs, including same-parent paragraphs inside quotes or list items. A removed separator creates a provisional join. The committed version retains its preimage until the corresponding effect commits; cancellation restores exact structure. An initial native empty preedit deleting a selected multi-paragraph range belongs to the same composition session, not an independent application deletion.

For `p1:"ab"`, `p2:"cd"`, composing over `b\nc` with `XY` displays `p1:"aXYd"`. Cancel restores both original blocks. Commit creates one Composition history group; Undo restores both original blocks, properties, attributes and selection. After partial commit of `X`, cancelling `Y` leaves `p1:"aXd"`, and one Undo restores the original two-block document.

Unchanged tagged LF remains a paragraph boundary; newly inserted raw LF is a soft break. Returning to the epoch baseline restores original boundary identity. Explicit Enter/paste commands create new blocks. Inline formatting never patches structural separator units and does not style untouched middle paragraphs between disjoint candidate islands.

Separate table-cell flows, protected atoms and incompatible schema ancestry remain structural fences. Native plaintext input must not silently flatten them. These are the existing hierarchy rules, not an exclusion of ordinary cross-paragraph composition. The plan's connected-domain contract is explicit about compatible parent/join classes.

## Repairs exposed by structural tests

- Direct Undo now transfers and restores complete unit metadata, including boundary tags, rather than reconstructing only text and attributes.
- Partial commit distinguishes retained-original segments from replacement segments. Accepting a positive prefix of a replacement consumes its whole old footprint; its remaining candidate tail is an insertion. Untouched original segments retain their own rollback obligations.
- Cancellation after accepted partial edits selects a valid position in the resulting flow instead of restoring stale offsets in removed paragraphs.
- Committed formatting targets are intersected with semantic candidate islands, so freezing a prefix does not turn carried-through middle paragraphs into formatting targets.
- Semantic revision comparison includes structural boundary identity/properties and placement, even when plaintext is equal.

The independent tape oracle was changed only where its old partial-commit/cancellation rules disagreed with the specified ownership and selection policy. Those corrections are justified by hand-calculated structural examples and independently constructed expected tapes, not by importing candidate helpers. The old runs remain intact.

## Executed verification

Command: `node validation/codex/run-20261015-15/verify.mjs`.
Environment: v24.20.0 / win32 / x64.
Elapsed: 70.35 seconds. All 19 verification groups passed.

- **25 named cross-paragraph cases and 177 generated differential cases.** Expected tree state comes from fixture headers and independent flat expected-tape splices. Assertions cover exact block IDs/types/properties, empty-block attributes, raw inline attributes, text, selection, committed versus visible structure and history.
- Coverage includes candidate detours; two/three paragraphs; empty paragraphs; headings; same-parent nested paragraphs; initial provisional deletion; retargeting; raw LF versus structural LF; malformed UTF-16 retention; partial commit before/through/deleting a separator; committed-prefix cancellation; full deletion; queued formatting/Undo; disjoint islands; connection turnover/disposal; rollback after staged history; repeated Undo/Redo; and structural-only revision changes.
- Three structural behavioral mutants restore text-only Undo, incomplete replacement-footprint acceptance, and boundary loss. All fail the same assertions passed by the baseline.
- Retained suites pass: 22 regression blocks, 18 Candidate B/oracle fixtures, 21 settlement scenarios, 17 semantic traces, 10 longer sequences, 85 sampled state/event cells, and 22 transaction tests.
- The retained public-boundary and final-contract suites pass. Across all mutation suites, 67 behavioral mutants are caught with passing controls (56 inherited + 4 boundary + 4 final-contract + 3 structural).
- Persistent-index validation still passes 1,200 randomized edits with 30 historical versions and instrumented local-maintenance workloads.
- Retained depth-4 exploration: 146,594 recorded states and 203,280 transitions, with no violation of its implemented assertions. That explorer is the flat coordinator exploration; **the new structural cases are separate tests, not a claim of exhaustive hierarchical model checking**.
- Run-14 preservation is checked against recorded source hashes. Before-state root documents are archived under this run's `sources/` directory.

## Final review and readiness

The plan now specifies tagged units, flow/domain admission, provisional materialization, separator reconciliation, partial-commit ownership, cancellation caret placement, logical history grouping, position/anchor mapping, persistent-storage lowering, native bridge obligations and acceptance gates for cross-paragraph input. The old single-block rule and separate preparation requirement have been removed from the normative contract and transition table.

This is an implementation specification backed by scoped executable models. The flow model is an array-based validation implementation, not production Kotlin storage, native rendering, or a browser/native IME host. Real Android/iOS/Desktop/Web input traces must qualify original-selection capture, composition event ordering, native ownership and synchronization. Accessibility, crash durability, full Unicode conformance, production storage integration and performance remain gated. A protected structural boundary is not silently converted to text to make a test pass.

No unresolved choice remains about ordinary cross-paragraph composition in the declared compatible text domains. The architecture is ready to implement that contract; live platform feasibility and production correctness remain to be demonstrated. No implementation phase is authorized by this report.

Plan SHA-256: `159109a0da9f76534ff825fc925a2f0c45f9a36e72b1f253d6ff3ab321643dc7`. Source hashes and detailed results are in `results.json`.
