# Android native outline repair — 23 October 2026

Separate branch `codex/android-native-outline-repair`, based on the recovery
follow-up e60f9ee. This keeps the unrelated outline UI crash out of recovery PR35.
No new runtime dependency, public core API or document semantics are introduced.

## Reproduction and cause

The owner authorized the pinned custom native controller and coordinated input
with the Upscaly session. On identified APK1aafa45b, selecting Heading1 for the
default mixed Urdu/Latin paragraph worked. Clicking Outline then terminated the
foreground app. Device logcat shows `ViewTreeLifecycleOwner not found` from the
AlertDialogLayout, not an inference from a missing result or a low-memory kill.
The custom controller successfully performed the clicks; this is an editor defect.

The outline embedded ComposeView already had lifecycle and saved-state owners,
but window recomposition resolves those owners from the dialog root. The repair
installs both owners on the dialog decor before attachment and explicitly disposes
the composition when dismissed. Text, attributes, stable heading anchors and the
single authoritative editor owner are unchanged.

## Discriminating regression

The maintained `outline-ui` instrumentation focus calls the production dialog
handler. Before repair it crashes the child before a complete result; the driver
records FAIL and retains the fatal log rather than reporting missing tests as PASS.
After repair, the actual UI regression opens/navigates/closes the dialog, checks
stable target selection without text/history mutation, and verifies visible heading
updates across Undo/Redo and reopening. The existing54 + recovery9 groups remain;
the integrated runner now strictly expects64 distinct successful groups.

The focus dispatch uses a named map so adding the regression does not require a
complexity suppression. No assertion or static-analysis threshold was relaxed.
Local check/api/zero-Detekt PASS; focused actual-dialog regression PASS. Full binary
consumer, integrated64 and final native recheck remain separately identified below
in their receipts/handoff; do not infer them from this focused pass.

## Evidence and scope

Primary-checkout ignored root: `tests/local-evidence/repair-batch-20261023/`.
Native failure: `native-20261023/custom-outline-logcat.txt`.
Mounted failure: `native-20261023/outline-mounted-before/`.
Mounted repaired result: `native-20261023/outline-mounted-fixed/results.json`.
Controller commands/screenshots are also preserved privately under
`D:\Work\AntigravityComputerUse\.localdata\external-editor-feedback\composedoc-20261023`.
Device log times use the emulator's timezone; command receipts record UTC.

This adds window/accessibility-node evidence, not TalkBack speech or expert Urdu
typography qualification. No frozen file, approved desktop raster or user document
is edited. PR creation requires the owner's exact new title/body text; the eight
already supplied descriptions remain unchanged.
