# Android image picker session repair — 24 October 2026

Branch `codex/android-image-picker-repair` starts from Outline repair0dbcc57,
which itself depends on recovery PR35. This is another concrete native finding,
not a new feature batch. No public API, runtime dependency, frozen bytes, numbering
semantics or approved desktop raster changes are required.

## Actual failure and expected behavior

On independently packaged APKd4fab5a4, the native walkthrough selected the owned
64x40 PNG through Android's actual Downloads document picker. The editor refused
it with "Image target changed; result rejected; existing content retained".
Read-only before/after receipts show the same owner, accepted text, paragraph and
resource state, selection[92,92], sequence5 and Undo5. The picker correctly retired
the native input connection when its window took focus. Its session revision
changed, making the previously captured structural command inadmissible.

Expected: this ordinary picker focus round trip must permit the image operation
when the exact accepted basis and selection are unchanged. A changed owner,
committed revision/identities, selection or busy composition must still refuse it.
No stale callback is replayed, and no global engine admission rule is relaxed.

## Repair and regression

The shell reacquires a current structural capture after decoding off the UI thread,
only if its owner-scoped FindBasis and selection equal the original request.
Existing operation-generation/tab checks remain. The new capture is dispatched
through the existing typed intent. Real content edits, including edit/Undo revision
changes, cannot be converted into a fresh accepted basis by this comparison.

The maintained `image-picker-focus` check uses the production Android view's
session-retirement method and shell result handler with a real fixture provider.
It checks insert and replacement, independently expected dimensions, unchanged
original text-unit identities/attributes, one transaction each, exact Undo/Redo,
and rejection after an intervening selection change. This is mounted/injected
boundary evidence, distinct from the real Android picker failure above.

Before-fix receipt `image-picker-mounted-before-diagnostic` FAILS: the operation
is rejected and the expected image never appears. After-fix
`image-picker-mounted-final` PASSES. An earlier attempt failed its fixture
precondition because it compiled before native-session adoption was added; it is
retained as raw failure, not claimed as the product witness. Diagnostic messages
were added before the valid before-fix run. No assertion was relaxed.

Local check/apiCheck and zero-Detekt pass after correcting one new test's line
length; the failed style run remains retained. Full packaged65, process/clipboard,
exact clean gates and final native picker retest are pending their own receipts.

## Evidence and restart

Primary checkout ignored evidence root:
`tests/local-evidence/repair-batch-20261023/native-20261023/`.
Actual native screenshots: `08-picker-final.jpg`, `09-image-rejected-before.jpg`.
State: `nested-list-final.json`, `image-rejected-final.json`.
Focused results: `image-picker-mounted-before-diagnostic/results.json`,
`image-picker-mounted-final/results.json`. Build/style logs retain their outcomes.

Continue from this branch with the separately packaged consumer, integrated65,
then the actual picker/invalid-size/correct/replace/UndoRedo/save workflow against
that same APK. Coordinate native input with the other session and end computer
control before source work. PR metadata must be supplied by the owner after the
change is tested; do not write its public title/body on the owner's behalf.
