# Astra Editor — the first desktop prototype

This is a real, local window you can type into. It is the first runnable desktop slice of frozen v5, not the complete rich-text editor.

## Open it

On this machine, double-click **run.cmd** in this folder. It builds the Kotlin code and opens the editor. The initial build can download pinned Maven dependencies.

The document is **in memory only**. Copy anything you want to keep before closing the window. There is no autosave or crash recovery in this prototype.

## Try it

- Type normally. Enter creates a paragraph; Shift+Enter inserts a soft break.
- Click to place the cursor; drag to select. Double-click selects a word.
- Arrow keys move the cursor. Shift+arrows extend selection. Home/End and Ctrl+Home/End work.
- Ctrl+Z undoes; Ctrl+Shift+Z or Ctrl+Y redoes. Undo restores selection direction too.
- Ctrl+A/C/X/V select all, copy, cut and paste plain text. The toolbar has Undo, Redo, Copy and Paste.
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
desktop-prototype\run.cmd
```

`run.cmd` does not change PowerShell execution policy. The optional run.ps1 can be used from an allowed PowerShell session with -Check, -HostCheck or -SkipBuild. Normal launch does not log document content. Diagnostic host-check mode writes its synthetic document state to target/ui-state.json.

The pom explicitly aligns AndroidX runtime, runtime-saveable and runtime-retain to 1.12.1. Otherwise Maven resolves incompatible strict transitive runtime versions. This is a compiled desktop dependency set, not a four-platform dependency qualification.

## Verified and still open

See [validation/report.md](validation/report.md) and recorded check results.

- Passed: 14 kernel checks, including 1,000 deterministic edits/Undo and all 1,093 Unicode 16 grapheme fixtures.
- Passed: attached AWT host plus real Compose layout, 15 committed-text slice queries, five **synthetic** input-method events, partial acceptance, composition Undo and a raw surrogate-caret navigation regression.
- Passed in the real Windows window: typed-key input, clipboard insertion, mouse placement/drag selection, keyboard backward selection, replacement, Undo restoring selection, Redo, paragraph navigation and emoji deletion/restoration.
- Not qualified: a physical Chinese/Japanese/etc. OS IME, candidate popups across native lifecycle changes, editable screen-reader behavior, Linux/macOS, large-document latency or persistence.

This is a plain-text prototype: formatting, tables, the full hierarchical/persistent kernel, the complete v5 command catalog, resource management and durable recovery are not implemented here. The frozen v5 documents are unchanged. No production release gate is declared passed.
