# Astra Editor — the first desktop prototype

This is a real, local window you can type into. It is the first runnable desktop slice of frozen v5, not the complete rich-text editor.

## Open it

On this machine, double-click **run.cmd** in this folder. It builds the Kotlin code and opens the editor. The initial build can download pinned Maven dependencies.

Use **Save / Ctrl+S** to save an `.astra` prototype document and **Open / Ctrl+O** to reopen it. **Ctrl+Shift+S** saves a separate copy. Open always creates another window; it never replaces your unsaved document. Closing a modified document asks Save / Discard / Cancel.

There is **no autosave or crash recovery** yet. Save explicitly. Prototype files preserve raw UTF-16 and paragraph/soft-break tags, but do not store Undo history or caret position. They are a versioned prototype format, not the final v5 production container. Files are limited to 100,000 UTF-16 units; this is an explicit prototype limit, not a change to v5.

Close running prototype windows before rebuilding with the launcher. Do not save documents under `desktop-prototype/target`: it is disposable build output and the launcher cleans it.

## Try it

- Type normally. Enter creates a paragraph; Shift+Enter inserts a soft break.
- Click to place the cursor; drag to select. Double-click selects a word.
- Arrow keys move the cursor. Shift+arrows extend selection. Home/End and Ctrl+Home/End work.
- Ctrl+Z undoes; Ctrl+Shift+Z or Ctrl+Y redoes. Undo restores selection direction too.
- Ctrl+A/C/X/V select all, copy, cut and paste plain text. The toolbar has Open, Save, Undo, Redo, Copy and Paste.
- Backspace/Delete operate on whole graphemes, including emoji ZWJ sequences.
- Scroll with the mouse wheel; typing brings the caret back into view.

## What is actually inside

`EditorEngine.kt` owns document state, raw selection, provisional composition, pending actions and Undo/Redo on one thread. It uses immutable full-copy snapshots and tagged paragraph-break units for this small slice. It does not use Compose TextFieldState or Swing JTextArea as the document/history authority.

`DesktopEditor.kt` draws the document with Compose Canvas/TextMeasurer. A viewport-level AWT input surface implements InputMethodRequests and handles keyboard, pointer and input-method events. Compose's public SwingGraphics rendering mode avoids heavyweight/lightweight overlay conflicts. The input surface has an explicit JLayeredPane layer; Kotlin's add(Component, Int) overload is an insertion index, not a layer setting.

`Graphemes.kt` ports the v5 Unicode 16 grapheme reference using the frozen property files and their SHA-256 hashes. Native UTF-16 offsets remain distinct from user grapheme movement.

AWT committed-prefix/composed-suffix events update committed storage and a provisional replacement separately. The initial selection is pinned before preedit. A finished composition is one undo group. Commands requested during composition wait for the native terminal event; returning from endComposition is not treated as proof of settlement. The prototype does not claim qualification of late native callback provenance.

## Build and checks

Windows: JDK 17+ and Maven 3.9+. Executed here with JDK 23.0.2, Maven 3.9.9, Kotlin/compiler plugin 2.2.20, Compose Desktop 1.12.1 and Skiko 0.150.1. Maven is taken from PATH or the existing research/tools download. Downloaded tools/build outputs are not committed.

```bat
desktop-prototype\run.cmd --check
desktop-prototype\run.cmd --stress
desktop-prototype\run.cmd --host-check
desktop-prototype\run.cmd
```

`run.cmd` does not change PowerShell execution policy. The optional run.ps1 can be used from an allowed PowerShell session with -Check, -HostCheck or -SkipBuild. Normal launch does not log document content. Diagnostic host-check mode writes its synthetic document state to target/ui-state.json.

The launcher performs a clean rebuild to avoid stale Kotlin signatures. `--host-check` opens a temporary test window, exercises synthetic AWT events, and closes it with a failing process exit code if a check fails.

The pom explicitly aligns AndroidX runtime, runtime-saveable and runtime-retain to 1.12.1. Otherwise Maven resolves incompatible strict transitive runtime versions. This is a compiled desktop dependency set, not a four-platform dependency qualification.

## Verified and still open

See [validation/continuation-report.md](validation/continuation-report.md), the initial [validation/report.md](validation/report.md), and recorded check results.

- Passed: 15 kernel checks, including 1,000 deterministic edits/Undo and all 1,093 Unicode 16 grapheme fixtures.
- Passed: attached AWT host plus real Compose layout, 15 committed-text slice queries, seven **synthetic** input-method events, partial acceptance, composition Undo and a raw surrogate-caret navigation regression.
- Passed in the real Windows window: typed-key input, clipboard insertion, mouse placement/drag selection, keyboard backward selection, replacement, Undo restoring selection, Redo, paragraph navigation and emoji deletion/restoration.
- Not qualified: a physical Chinese/Japanese/etc. OS IME, candidate popups across native lifecycle changes, editable screen-reader behavior, Linux/macOS, large-document end-to-end latency or power-loss durability.

Additional checks cover 10 file groups, 2,000 generated composition sessions through Save/Open and Undo/Redo, and a real Save/reopen/unsaved-close walkthrough.

Saving writes a sibling temporary file, forces it, then atomically replaces the destination. Unsupported atomic replacement fails rather than truncating the original. Checksums detect corruption. A completed external change since Open/Save causes ordinary Save to refuse; Save As lets you choose a separate copy. This conflict check is best effort, not a filesystem compare-and-swap guarantee against a writer racing the final rename. Directory durability and power failure remain unqualified.

This is a plain-text prototype: formatting, tables, the full hierarchical/persistent kernel, the complete v5 command catalog, resource management and durable recovery are not implemented here. The frozen v5 documents are unchanged. No production release gate is declared passed.
