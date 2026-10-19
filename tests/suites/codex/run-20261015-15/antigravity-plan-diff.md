# Revision 4.10.0 → 4.11.0

The previous single-block limitation is withdrawn in response to the owner's requirement for cross-paragraph composition.

- §1.1 makes cross-paragraph composition a required capability.
- §4.2 replaces the block-local overlay contract with a tagged flow-scoped overlay.
- New §4.7 specifies paragraph identity/property preimages, provisional joins, commit/cancel/partial commit, history, selection/anchors, storage lowering, native integration and required tests.
- §6.1 admits composition across compatible paragraph/heading boundaries without preparatory deletion. Separate cell flows, atoms and incompatible ancestry keep explicit schema rules.
- G3/G5 require cross-paragraph core traces and real-host qualification respectively.
- Direct history restoration and partial-commit ownership defects exposed by structural tests are repaired. Candidate and independent tape-oracle changes are explained in the report.
- Prior scope decisions and models remain preserved; no production code or phase advancement was introduced.
