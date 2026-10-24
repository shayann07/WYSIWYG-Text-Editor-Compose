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
length; the failed style run remains retained. The completed results below
supersede the implementation checkpoint's previously pending qualification.

## Completed qualification and exact identity

Source commit: `ae6e6a27ca112096ad94ea72b499ec60e2db6454`.
Independent binary-consumer APK SHA256:
`b6354efaf910e2ed280c2e38beb56c8a9fb581be76f732cb1514be468118712f`.
Test APK SHA256:
`d821e64a4850c8546d9ab73ea2035c0def8fe699dcdc2981141e1577a6678cbf`.
Environment: owned AVD `composedoc_slice_20261020`, emulator-5580, API37,
Google16KiB x86_64 image; pinned packaged fonts and D-drive tooling.

| Evidence | Result and receipt under the primary checkout's ignored repair-batch-20261023 root |
|---|---|
| Separate binary consumers | PASS10 public-API runtime groups, Android and peer APK builds; `image-final-packaged/results.json` records inputs, artifact/dependency hashes and exact commands |
| Mounted production adapters | PASS65 distinct groups, preserving54 inherited + recovery9 + Outline1 + picker1; `image-final-65/results.json` |
| Owned process interruption | PASS completed checkpoint -> SIGKILL -> fresh restore; PASS discard -> unchanged durable close -> SIGKILL -> fresh restore, with exact accepted/resources and separate unfinished evidence; both receipts in `image-final-65/results.json` |
| Cross-UID rich transport | PASS after publisher process death and cache cleanup; `image-final-clipboard/results.json`; setup/paste are instrumented, not native keyboard qualification |
| Exact clean final gates | PASS all6 on ae6e6a2, including81 protected hashes,20 workspace stages and4 implementation mutants; `image-ae6e-final-verification.json` |
| Actual Android picker | PASS native selection of owned64x40 PNG, then replacement with owned80x50 PNG; `native-20261023/final-image-native-assertions.json` and screenshots11/14 |
| Actual properties/history | PASS invalid2409 leaves dialog/value/error; corrected160 applies one transaction; replacement one transaction; exact Undo/Redo and resource bytes independently checked through the public JVM codec; same native assertions receipt and screenshots12/13/15 |
| Actual provider and Find | PASS native SaveAs, Keep/close and actual Open; exact saved/reopened bytes, separate new owner/history, query/navigation without mutation, independently calculated two replacements and exact attributed Undo/Redo; `native-20261023/final-provider-find-assertions.json` |

The native pass used pinned dev3 controller07c49640, actual screenshots and
read-only Android state/file oracles. A refused host ctrl+a sent no further text;
native pointer selection and Unicode field typing provided a supported alternative.
That field injection is separate from genuine keyboard/IME input. Actual Gboard
soft-key taps entered Hi, but its trace is direct commits, not active composition.
The Gboard tutorial interrupted one pending correction; exact accepted bytes and
history remained unchanged. That attempt was not counted as a successful resize.

Source-level checks also execute20 Android driver/API self-tests; explicit
architecture14 and governance8 self-tests PASS. A wrong discovery-path invocation
executed zero tests and was not counted; the correct suites ran afterward.
This report-only follow-up requires its own clean-commit gate receipt before push;
the APK/source-input binding must remain explicit rather than attributing an old
receipt to a changed commit. Rotation/font-scale native continuation, genuine
multilingual composition, lower API and TalkBack speech remain separately reported.
The held native gesture selected Mixed at UTF-16[22,27]. A subsequent actual full
selection Copy was cancelled as "selection changed" despite unchanged accepted
content/range. This distinct failure is retained in
`native-20261023/final-native-copy/state.json` and is under investigation on the
separate `codex/android-toolbar-owner-repair` branch. The passing instrumented
clipboard receipts do not override that native failure. Nothing here qualifies
power-loss durability or expert typography.

## Evidence and restart

Primary checkout ignored evidence root:
`tests/local-evidence/repair-batch-20261023/native-20261023/`.
Actual native screenshots: `08-picker-final.jpg`, `09-image-rejected-before.jpg`.
State: `nested-list-final.json`, `image-rejected-final.json`.
Focused results: `image-picker-mounted-before-diagnostic/results.json`,
`image-picker-mounted-final/results.json`. Build/style logs retain their outcomes.

The owner supplied exact title/body for draft PR44, based on Outline PR43;
descriptions/attestations remain untouched. Continue the remaining connected
provider/layout checks against this same APK, update the final dated handoff and
run the exact clean follow-up gate before pushing that documentation. Coordinate
input with the other session and end computer control before source work.
