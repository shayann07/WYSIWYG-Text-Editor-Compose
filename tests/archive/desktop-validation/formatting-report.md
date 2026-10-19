# Desktop prototype 0.3: bold and italic

16 October 2026. Adds a deliberately small attributed-text slice to the existing desktop prototype. Frozen v5 and its reference evidence remain unchanged. This report supersedes the earlier reports' plain-text-only description; their executed evidence retains its original scope.

## Behavior implemented

- Raw bold/italic flags on each retained UTF-16 unit. Equality/revisions, snapshots, Undo/Redo, Save/Open and composition baselines include those flags.
- Collapsed toggles modify typing intent without creating a document revision or history step. Explicit intent survives contiguous typing; relocation chooses a logical side and clears that intent. New typing never unions unequal left/right styles.
- Selected replacements take the first selected text unit's style regardless of selection direction. Backspace and forward delete inherit the surviving left/right side respectively. Deleting a block's last content preserves its effective style for later insertion and save/reopen. Enter carries these ordinary presentation properties.
- Noncollapsed formatting expands to grapheme edges separately within each paragraph and patches just the requested property. Structural paragraph separator units are skipped. Mixed toolbar state uses displayed cluster attributes.
- Compose rendering uses the leading unit's ordinary presentation style for each grapheme, without rewriting hidden constituent attributes. Raw removal of an inserted ZWJ can expose those retained styles again. Explicit formatting intentionally updates all affected cluster constituents.
- Formatting invoked on a collapsed composing caret waits for native settlement and targets the final composition extent. It is its own undo step. Explicit abort cancels that target. The extent's identities are captured before draining queued actions, so an earlier queued Undo cannot retarget the format to unrelated content or make terminal input fail.
- Ctrl+B/Ctrl+I and toolbar buttons; clear on/mixed labels. Selection remains intact when applying a range format. The status footer retains the file state alongside action notices.

## Prototype file version 2

Big-endian ASTP magic, int version 2, int unit count, byte initial-empty-block style, then repeated UTF-16 char / kind byte / style byte records, followed by SHA-256 of all preceding bytes. Bold is bit 0; italic is bit 1; other bits reject. Kind 0 is text; kind 1 is paragraph LF. Version 1 (three-byte units, no style metadata) remains readable as unformatted text. Versions above 2 reject. The prototype file size limit remains 100,000 units. Undo history and caret position are not persisted.

This format is not the production v5 journal/container. Save remains explicit; there is no autosave or physical crash-recovery claim. Version 0.2 executables cannot open version-2 documents; use the current launcher.

## Executed evidence

- `run.cmd --check`: 15 existing kernel groups, 10 file groups and 16 new formatting groups. Tests include mixed-property restoration, selection direction, typing sides, no-op revision behavior, first-unit grapheme presentation with raw-style preservation, native candidate detours, partial commitment, queued format/Undo ordering, cancellation credits, empty-paragraph attributes and v1/v2 persistence.
- `editor.MainKt --stress`: 2,000 generated raw-UTF16 composition sessions plus 12,000 randomized range-format commands compared with an independent style-array expectation. Every format trace saves/loads and then unwinds exact style/selection preimages. Existing official Unicode16 fixtures and regional-indicator long-run probes still run.
- `editor.MainKt --host-check`: attached AWT surface and real Compose layout, with injected Ctrl+B/I/Z, expected SpanStyle/font weight and style, valid caret geometry and exact formatting Undo. Earlier committed-context, invalid native caret, U+FFFF and AltGr assertions remain.
- Real Windows interaction: selected the sample sentence, applied Ctrl+B and Ctrl+I, visually verified bold/italic, used Ctrl+Z to remove only italic and Ctrl+Y to restore it, saved using the file dialog, and reopened into a separate window with visibly retained formatting and empty Undo. An independent Python decoder checked the actual 31 code units, all bold+italic bitfields and checksum. Recorded in `format-ui-checks.json`.

The live walkthrough exposed a missing checkmark glyph in Swing's toolbar font; labels now use plain `on`/`mixed` text. Final launcher inspection verifies those labels. All test windows contain only generated fixtures, never user documents.

## Limits and remaining work

No physical OS IME, candidate-popup, accessibility or cross-platform qualification is claimed. The native bridge still has the prototype's limited admission/lifecycle scope. Bidi pointer/visual-edge typing-side behavior needs broader live-script testing; ordinary logical-side boundaries have executable tests. Selection containing only empty paragraphs, links/code, rich clipboard, lists, tables, resources and the full production persistent hierarchy remain beyond this slice. Clipboard is still plain text, with destination formatting for paste.

Snapshots and attribute projection use arrays and whole-flow work. Passing these tests does not qualify large-document latency or production retention budgets. This work implements the selected v5 policies within the small prototype; it does not redesign or replace the frozen specification.
