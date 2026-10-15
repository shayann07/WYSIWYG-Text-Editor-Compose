# Plan changes — revision 4.9.8 to 4.9.9

Preserved before-state: `validation/codex/run-20261015-13/sources/`.

- §4.1 now owns a single admission/publication contract: immutable requests, pinned published basis, complete execution validation, receipt publication ordering, rollback ownership, and scheduled FIFO continuation.
- §4.3 references that contract instead of maintaining a competing transaction description.
- §4.5 replaces absolute-path locators with a versioned document-root/token-map/parent-map bundle. Point rank is O(log² P); updates account for map maintenance. Interior insertion and deletion have concrete algorithms.
- §2.3 distinguishes text slab size from descriptor leaf occupancy.
- Memory formulas distinguish units from pieces and explicitly identify assumptions. Previous engine-heap totals are withdrawn.
- Historical verification narratives were removed from the normative plan and remain preserved in source snapshots; current evidence and limitations replace conflicting success claims.
- Tests changed only where the prior expected behavior contradicted upfront validation or epoch isolation. These changes are documented in the report. Old run files were not edited.
- All production phases and platform qualification gates remain unapproved/NOT RUN. No platform support or latency guarantee was added.
