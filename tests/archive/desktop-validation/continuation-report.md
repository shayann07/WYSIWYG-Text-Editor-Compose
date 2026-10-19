# Desktop prototype 0.2 verification

16 October 2026. Same Windows/JDK/Compose dependency set as the first prototype. The frozen v5 specification and its reference model are unchanged. This report extends the initial `report.md`; its original in-memory-only scope describes version 0.1.

## Delivered

- Save, Save As, Open and unsaved-close protection. File I/O uses SwingWorker; typing can continue during writes. A saved snapshot only clears the dirty state if the current document still matches it. Open creates a separate window with independent history.
- `.astra` prototype snapshot format: magic ASTP, big-endian version 1 and unit count, repeated big-endian UTF-16 code unit + one-byte kind, then SHA-256 of the preceding bytes. Hard paragraph breaks must contain LF; soft LF remains TEXT. File identities are regenerated on load. No selection/history is persisted.
- Bounded decoding and exact byte-length/schema checks. A 100,000-unit prototype file limit is explicit. Save refuses active preedit rather than silently accepting it.
- Same-directory temporary write, file force, atomic replacement, and cleanup on failure. The preceding file survives injected pre-replacement failure. A known completed external modification makes Save refuse. This is not a guarantee against another writer racing the final comparison and rename, and is not physical power-loss qualification.

## Concrete bugs fixed

1. **U+FFFF input truncation.** `AttributedCharacterIterator.DONE` shares a value with a valid retained UTF-16 unit. Iterate by begin/end indices instead of terminating on that value. Attached-host regression commits `A\uFFFFZ` and checks the complete committed query result.
2. **Silent native caret clamping.** Malformed input offsets were changed rather than rejected. Invalid text/caret ranges now leave the editor snapshot unchanged; a host regression sends offset 99 into three composed units.
3. **AltGr treated as Ctrl shortcuts.** Ctrl+Alt+AltGraph+A could select all instead of leaving the character shortcut to the keyboard layout. Shortcut dispatch now excludes AltGraph; a synthetic key regression verifies selection remains unchanged.
4. **Redo refusal bypassed owner/observer guards.** Its early composition branch ran outside the transaction dispatcher. It now follows the same owner-thread and observer isolation rules as other actions.
5. **Quadratic regional-indicator scan.** GB12/13 repeatedly rescanned the preceding RI run. A running count preserves the same parity rule in one forward pass. All 1,093 official Unicode 16 fixtures still pass, along with run-reset and long-run assertions.
6. **Stale compilation could hide startup failure.** After a function signature changed, the non-clean build linked an older callable signature and the live window failed with NoSuchMethodError even though headless tests ran. The launcher now cleans before compiling. Close windows before rebuilding; target is disposable. Host-check startup failures now terminate with a nonzero exit code rather than being printed and mistaken for success.

## Executed checks

`run.cmd --check` runs 15 kernel groups and 10 file groups, including every UTF-16 code unit, all truncations and single-byte corruptions of the small file fixture, correctly checksummed malformed schemas, interrupted/failed replacement, stale-save conflicts, pending-composition refusal and snapshot-based dirty tracking.

`editor.MainKt --stress` runs 2,000 deterministic native-protocol sessions. An independent flat-string expectation checks candidate detours, raw offsets including surrogate interiors, partial acceptance, explicit abort or commit, file roundtrip, exact Undo preimages and Redo. These are generated protocol events, not real keyboard/IME behavior.

`editor.MainKt --host-check` attaches the AWT input surface to actual Compose layout. It checks 15 committed-text slices, seven injected IME text events, U+FFFF retention, invalid-caret atomic rejection and AltGr shortcut isolation. It closes its own window after success. See `host-checks.json`.

Computer Use exercised a separate real window: entered a multilingual emoji fixture, invoked Ctrl+S, chose a file, observed the clean saved title, made a real KEY_TYPED edit, cancelled a close prompt, opened the saved file into another window, verified the original edit remained, then saved on close. A Python decoder independently checked the actual bytes and SHA-256 both before and after that final save. See `file-ui-checks.json`. The walkthrough preceded the final external-change guard; that guard has executable file-level coverage.

## Performance evidence and limits

Before the RI fix, a single grapheme scan took 36.371 / 313.353 / 1488.465 ms at 2,000 / 8,000 / 32,000 RI scalars (4,000 / 16,000 / 64,000 UTF-16 units). Immediately after the fix, the same probe took 8.885 / 20.340 / 47.865 ms. These are individual local observations including allocation/JIT effects, not rigorous latency percentiles or end-to-end editing/layout benchmarks. The final rerun is recorded in `stress-checks.txt`.

Still unqualified: actual Chinese/Japanese OS IMEs and candidate windows; screen readers; other operating systems; large-document typing/layout; multi-writer file serialization; physical power-loss durability. There is no autosave, recovery journal, rich formatting, tables or full persistent v5 kernel yet. No production gate is claimed passed.
