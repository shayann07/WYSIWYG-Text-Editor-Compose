# Revision 4.15.0 — whole-document validation and final review

Run: `validation/codex/run-20261015-19`. Executed 2026-10-15T19:15:28.444Z on {'node': 'v24.20.0', 'platform': 'win32', 'arch': 'x64'}.

**All 20 verification groups pass. The finite command catalog, anchors and cell-flow composition now execute through one document owner. This is a tested semantic baseline; it is not a production editor, a whole-state proof or a claim that only platform testing remains. No production implementation was performed or authorized.**

## What changed

- Added a global document owner over the existing admission/structural worker/action-ledger pipeline. History and revision comparison include inactive cells, resources and annotations.
- Added 51 named command families, typing intent/coalescing, text and gap anchors, cell selection/structure/navigation, canonical rich clipboard and asynchronous paste leases.
- Extended cell selection to one provisional native gesture with secondary-cell clearing in Dv, explicit acceptance into Dc, and full-document cancellation/Undo.
- Bound connection identity to final published flow, preserving valid reentrant native input across transient delete/Undo scope changes and failed publications.
- Extended root-free bases with coordinate topology/cell identity metadata; separately capture/charge published typing intent. Added command/anchor/lease bounds and cleanup without claiming a physical heap bound.

## Executed evidence

| Group | Observed result |
|---|---|
| Document regressions | 66 named cases, all pass. |
| Existing scenarios through DocumentModel | 35 cases, all pass. |
| Command inventory | 51 families each have an observed terminal Applied/NoChange outcome; not merely an accepted queue ticket. |
| Anchor matrix | 100 insertion/split/association cases with analytic expected positions and exact Undo/Redo checks. |
| Cell composition matrix | 54 cases: every rectangle/direction in a 2x2 table, commit/cancel/partial acceptance, queued formatting and Undo. |
| New mutations | 8 unmodified controls pass; 8 behavioral mutants fail assertions. |
| Retained repair/AWT/retention tests | 26 repairs, 10 structural chains, 8 synthetic AWT cases, 13 retention cases and 400 fixed-size edits. |
| Retained base integration | 35 named cases, 255 generated traces, 240 bounded ledger traces, 80 repeated sessions, 130 scheduling requests. |
| Retained mutation suites | 17 caught with controls. |

Total verification duration: 7.019 seconds, **not an editor latency benchmark**. These groups deliberately overlap contracts; their counts must not be added as unique independent proofs. Inherited named cases execute once through DocumentModel and separately through the retained base model. The new matrices use independent expected values for their limited domains; most named regressions assert examples against the reference and are not a separately implemented complete oracle. No claim of exhaustive BFS/model checking is made for the whole document.

## Defects found and fixed

1. **Transient connection scope**: deleting an active cell's table then Undo during one settlement changed epoch despite restoring the original flow, rejecting valid reentrant input. `flow-roundtrip-before-repair.json` records the observed failure. Scope identity is now derived once from the final publication.
2. **Target and history separation**: transport status, command status and cancelled ledger credits were conflated. Credits remain until consumed; cancellation emits once; invalid queued import cannot make a following Undo consume accepted native text.
3. **Whole-document composition**: cell-clearing rollback/selection kind, annotation restoration and inactive-cell revision comparison needed whole-document state. Tests now check Dv/Dc isolation, partial acceptance, cancellation and Undo across all selected cells.
4. **Typing/native seed**: preedit must use frozen typing intent and empty-cell attributes; candidate whitespace detours must preserve baseline link attribution until acceptance.
5. **Object/Unicode/selection boundaries**: final review reproduced Backspace deleting an atom with a following combining mark, a selected atom remaining in its old cell after a move, and forward selection extension moving from range start. Hard atom boundaries, identity-following node selection and separately captured directional endpoints repair these cases. The archived failure JSON contains expected/actual assertions.
6. **Clipboard and delayed targets**: fixed rich-cell cloning, inline-rich replacement of a rectangle, target/focus separation, malformed/ambiguous fragment refusal, gratuitous empty split paragraphs and fresh identity mapping. Canonical clipboard tests do not validate an OS clipboard or HTML parser.
7. **Publication/retention safety**: capture published scope/context during reentry, pin its replay watermark through speculative switch/rollback, reject duplicate FlowIds before map overwrite, keep immutable bases free of roots/attributes, and release transient handles/leases on disposal.

Some items combine a reproduced defect with preventive regression coverage. `failure-*.json` files retain intermediate failures; they are development evidence, not the final result. `document-inherited-first.json` records the initial inherited-suite error-code mismatch, subsequently corrected to FlowMismatch for native foreign-flow proposals. No historical failure is described as a passing current test.

## Final independent review and honest readiness

After repairs I reviewed the combined command admission, target capture, global revision comparison, cell prelude, final connection binding, history restoration, anchor generations, clipboard leases and boundary rules. I also reran the prior named integrated scenarios through the actual new document owner. I found no remaining reproduced contradiction in the exercised contracts. The normative plan now states the command choices and failure behavior explicitly; implementers need not invent the architecture for those operations.

**Ready as a scoped specification/reference baseline, subject to its qualification gates. Not proven complete, and not only platform tests remain.** Remaining work includes pinned Unicode conformance; a production persistent hierarchical implementation and broad randomized/differential interaction corpus; actual HTML/import/export/system clipboard/resource pipelines; measured memory reclamation; crash recovery; visual movement/native gap and cell hosting; real IME/DOM/accessibility; and end-to-end performance. Intl segmentation, synthetic stamps, full-copy storage and serialized byte budgets cannot establish those properties. These are explicit qualification/engineering tasks, not hidden passing claims. Merged/nested tables, collaboration and pagination remain the pre-existing deferred product features.

No G0-G8 gate is passed or advanced. No source under production modules was created or changed. The work consists of a validation-model directory and versioned plan/report artifacts.
