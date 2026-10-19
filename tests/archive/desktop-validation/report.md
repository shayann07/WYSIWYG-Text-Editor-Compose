# Desktop prototype verification

16 October 2026. Windows x64, JDK 23.0.2, Kotlin 2.2.20, Compose Desktop 1.12.1, Skiko 0.150.1. This is a runnable plain-text vertical slice of v5, not production qualification.

## Kernel

The executable `editor.MainKt --check` passes 14 groups:

- Selection replacement, exact direction restoration and Redo.
- Typing coalescing and relocation barriers.
- Provisional composition, one history group, partial acceptance and suffix abort.
- Immutable candidate-baseline reconciliation and no-op revision behavior.
- Queued Undo waiting for terminal input and cancelled-action credits.
- Committed-prefix/composed-suffix native caret/context behavior.
- Paragraph tags, native soft LF and history restoration.
- Grapheme deletion including ZWJ emoji and ill-formed UTF-16 policy.
- Immutable snapshots, reentrant requests and invalid-input preflight.
- 1,000 deterministic edits against a flat oracle and complete Undo unwinding.
- All 1,093 official Unicode 16 GraphemeBreakTest cases.

These checks are recorded in kernel-checks.txt. They do not test an actual OS IME.

## Attached native host

The application was opened with --host-check. InputMethodEvent instances were dispatched into its attached AWT input component, using actual Compose text measurement for geometry. The checks assert:

- The input surface is the top component at the editing point, not the render layer.
- All 15 committed-only slices of the selected fixture, including empty intervals.
- Committed length/insertion position and zero-thickness candidate location.
- TextHitInfo.beforeOffset(0) and leading(0) produce the same start position for the LTR fixture.
- Accepted Chinese prefix plus composed suffix, followed by terminal commit and exact Undo.
- A raw native caret inside a surrogate pair, then queued user Left and settlement, lands at the emoji start rather than the document start.

Five synthetic IME text events are used. The result is in host-checks.json. This is an attached-host integration test, NOT a physical CJK IME/candidate-popup qualification.

## Real window interaction

Computer Use drove the real application window with mouse/key input. `ui-checks.json` records the engine state after the assertions. A real key press produced KEY_TYPED; the tool's type_text action used clipboard insertion and is labeled accordingly.

Verified mouse placement/drag selection, Shift+Left backward selection, typing replacement, Ctrl+Z restoring text and direction, Ctrl+Y Redo, Enter, emoji Backspace/Undo and Up movement across paragraphs. Selection highlight and the rendered text were visually inspected in the window. Basic keyboard/pointer operation works.

## Concrete problems fixed while building

1. Maven selected incompatible runtime-saveable/runtime versions. Explicit runtime alignment compiled and ran successfully.
2. Kotlin chose add(Component, Int), interpreting the layer number as insertion index. The editor was behind the render component and missed clicks. Explicit setLayer calls fix it; the host test now asserts pointer ownership.
3. The accessibleContext protected field was null when notifications ran. Calling getAccessibleContext initializes the provider. Observer failures are isolated and logged in diagnostic mode.
4. Visual navigation from a raw mid-surrogate native position could fall back to the beginning of the document. User navigation now snaps to a legal grapheme edge before selecting a visual stop; a host regression covers it.
5. Windows PowerShell's script policy blocked the initial wrapper. The default launcher now uses an ordinary batch build/launch path without changing that policy.

6. Windows batch set /p truncated the long dependency classpath. The default launcher now copies runtime dependencies with group-prefixed filenames and uses a short Java wildcard classpath. This also avoids basename collisions between AndroidX and JetBrains artifacts.

No architecture redesign or frozen-spec change was needed. The prototype uses a narrow array/snapshot Kotlin kernel; it is not a claim that the persistent, fully attributed v5 engine has been implemented.

## Remaining scope

Physical OS IME behavior, screen readers, other desktop OSes, full rich structure, resource/clipboard formats, persistence and performance remain unqualified. Text is intentionally in memory only. Basic AccessibleText is exposed, but the Windows UI Automation inspection only showed the outer Java window on this machine; that is not evidence of complete accessibility.
