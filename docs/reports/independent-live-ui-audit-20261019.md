# Independent live editor audit — 19 October 2026

## Verdict and scope

The reported passing automated suite does not establish satisfactory visual or interaction quality. This independent native mouse/keyboard audit reproduced three functional defects and two visual usability defects. It also identified a misleading outline selection state. No editor implementation changes were made.

Used the existing final build at `desktop-prototype/local-evidence/batch-20261019-123638-15760/build` after executing `verification.validate_build` successfully. Launched our own JVM PID 26812 with `--urdu-writing --sample`, a separate recovery directory and diagnostics. The sample was an unsaved copy. No user documents or historical samples were changed. All desktop actions used the supported `@oai/sky` runtime. The initial computer-control failure was resolved following the app restart.

Evidence directory: `desktop-prototype/local-evidence/independent-live-20261019/`. Screenshots are native captures saved for this audit, not generated mockups. Window sizes observed were approximately 1020×865, 1020×434, and maximized 1536×864. Zoom 50% and 100% were exercised. This was a targeted audit, not exhaustive qualification of all editor features.

## Reproduced functional defects

### UI-01 — Remove link silently does nothing at a caret

1. Open the disposable v6 sample. Place the caret within the green underlined `Disposable writing sample` link; leave selection collapsed.
2. Structure → Link. The dialog correctly loads `https://example.org/urdu` but leaves Label blank.
3. Click Remove.

Actual: dialog closes, link remains, Open Link remains enabled, Undo remains disabled. No feedback explains the no-op. Diagnostics remain documentRevision=0 and undo=0. Screenshot: `link-remove-noop.png`.

Source corroboration: `StructureControls.kt:88` only executes removal if selection is noncollapsed. The same dialog uses insertion semantics for collapsed selection even when an existing link is recognized (`:84`). Editing an existing URI at a caret needs a separate regression; it was not exercised in this audit.

Required: distinguish insert-at-plain-caret, edit-current-link, selected-range application, and mixed selection. Remove at a caret inside a link should target the current contiguous link identity, preserve text and unrelated formatting, and produce one undoable command. Define boundary affinity and do not silently widen an explicit selected range.

### UI-02 — Invalid image width silently dismisses the dialog

1. Navigate to the sample image, click it, open Image properties.
2. Append `9` to width `240`, making `2409` (outside the displayed 10–600 range).
3. Click OK.

Actual: dialog disappears, image remains unchanged, no error or correction opportunity is shown. No history entry is made. This was observed live; the dialog screenshots are in the conversation tool results, not separately archived.

Source corroboration: `StructureControls.kt:74` guards the mutation inside a one-shot confirm dialog and silently exits on invalid values.

Required: show field-level validation, retain entered values and dialog focus, and explain the supported range. Validate nonnumeric input, bounds, long alternative text and rejected/stale command results. Cancel must leave state unchanged. Audit other one-shot numeric dialogs for the same problem without treating source suspicions as reproduced bugs.

### UI-03 — Clicking blank outline space jumps the document

1. Enable Outline and select the final `Structured sample` heading.
2. Return the caret to the first part of the document, e.g. Ctrl+Home and click the sample link. The final outline row remains selected.
3. Click well below all outline entries in blank white pane space.

Actual: viewport and caret jump back to `Structured sample`. At maximized size this reproduced with a blank-pane click approximately (1450,564). Screenshot after jump: `outline-blank-click-jump.png`.

Source corroboration: `StructureControls.kt:41` navigates `selectedValue` for every mouse click without testing whether the pointer intersects a cell.

Required: hit-test the actual cell rectangle before navigation, ignore empty-pane clicks, preserve keyboard activation, and test first/last row and blank area below rows.

## Visual usability defects

### UI-04 — Toolbar hides values needed for editing

The closed heading-role selector truncates `Heading 2` to `Headin…`, hiding the distinguishing level. The dropdown reveals Heading 2. Closed 100% zoom appears as `10…`. This happens even in the maximized window with substantial unused horizontal space, not only at minimum size. The stored font name also truncated in the initial sample.

Evidence: `heading-level-truncated.png`, `heading-dropdown-confirms-level-two.png`, `zoom-value-truncated.png`, `maximized-clipped-zoom-stale-outline.png`.

Required: size controls from font metrics plus native chrome/padding, or use clear short labels that retain identity. Current heading level, zoom and selected font must be understandable without opening menus. Validate normal/minimum/maximized sizes and supported Windows DPI settings; this audit did not change or qualify multiple OS DPI settings.

### UI-05 — Outline is poorly legible for Urdu

The 200px sidebar renders Urdu headings in tiny single-line default Swing rows, tightly packed with H1/H2 prefixes and horizontal truncation. Enlarging the main window does not improve this sidebar. The font/line metrics are inconsistent with the Urdu document surface. A horizontal scrollbar or tooltip is not a satisfactory primary reading experience.

Evidence: `compact-structure-outline.png`, `maximized-clipped-zoom-stale-outline.png`, `heading-dropdown-confirms-level-two.png`. Source: default list renderer at `StructureControls.kt:14` and fixed preferred pane width at `:17`.

Required: readable Urdu-capable rendering with enough ascent/descent, mixed-direction labels, distinguishable hierarchy and a usable adjustable width or wrapping strategy. Preserve exact labels and heading identity. Test long Urdu/English mixed headings and selected/unselected states; do not claim expert linguistic qualification from these checks.

### UI-06 — Outline highlight misleadingly remains on a distant heading

After selecting `Structured sample`, Ctrl+Home and later Find navigation moved the caret/selection to the start or first section, but the last outline row stayed highlighted. This is an observed UX ambiguity rather than a proved violation of an existing explicit contract. Define whether it means current section or last activated item. Prefer current-section tracking without stealing keyboard focus or resetting keyboard navigation. Include heading deletion, no active heading, search navigation and selection direction cases.

## Additional observations and limits

- At the short window size the permanent title/toolbars consume about 200px before the writing surface. Simplify this layout as a usability improvement; it is not a data-corruption finding.
- Outline navigation to a clicked real row, image selection, page scrolling, Ctrl+Home, zoom changes and Find/Escape behaved in the exercised sequences. Literal `کتاب` search reported 16 matches and revealed/highlighted the first. `urdu-search-matches.png` records this case.
- English strikethroughs, large sample typography and partially visible text at the viewport's top were not classified as bugs: they can reflect intentional stored formatting or scrolling.
- The sample document remained unchanged: final diagnostics documentRevision=0, undo=0, redo=0. Selection was [182,186] after Find. No save/reopen, mutation Undo/Redo, process-kill recovery, OS composing IME, AT, print, expert Urdu typography or large-document performance qualification was performed in this audit.
- OS window sizing via Alt+Space was intercepted by another app; switched back to the editor and used its native title-bar menu. That interception is not an editor defect. No OS settings were changed.
- At cleanup the runtime detected user input and blocked the attempted close. A read-only refresh showed the user had brought Codex forward. No further input was sent; the disposable editor was left open.

## Repair acceptance

Fix UI-01 through UI-05 and explicitly resolve UI-06. Add discriminating regressions using real UI actions/listeners and independent expected results; no model-variable tampering or source-string checks as behavior proof. Preserve existing text, structure, formatting, identity, undo, clipboard and recovery contracts. Run relevant integrated regressions on one identified build, then reproduce each original sequence in that same live build and capture before/after evidence. Mark anything not exercised as NOT RUN. No new roadmap features or architecture restart are needed for this repair batch.
