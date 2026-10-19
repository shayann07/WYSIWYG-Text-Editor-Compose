# Revision 4.14.0 -> 4.15.0

- Extend the integrated owner to the whole document, all cell flows, resources, annotations and typed selection.
- Add section 4.11 defining the 51-command catalog, target capture, anchor generations, cell composition prelude, clipboard leases, typing intent and bounded ownership.
- Derive connection turnover from final published scope, preserving reentrant capture/replay state during rollback and transient switches.
- Repair discovered atom/grapheme deletion, node-move selection, directional extension, command-credit, clipboard, native-seeding and full-document history issues.
- Retain cross-paragraph/cross-container native composition and explicit cell-selection composition; no scope reduction to single paragraphs.
- Extend root-free bases with topology/flow/cell metadata; independently capture typing context.
- Add document regression/matrix/mutation coverage and replay inherited cases through DocumentModel.
- Replace obsolete claims that anchors/commands/cell flows are absent from the model with precise remaining qualification boundaries.
- Preserve prior versions/runs. No production implementation or phase advancement.
