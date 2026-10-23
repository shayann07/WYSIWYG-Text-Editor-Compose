# Android recovery/close repair — 23 October 2026

Native continuation: owner centered the emulator and coordinated desktop access
with the Upscaly session. Actual Gboard keys entered `Hello `; touch/selection,
italic, app Undo/Redo and toolbar swiping were exercised. Actual provider Save As,
captured generation10 discard (owner-confirmed), close Cancel, unchanged Keep/close,
owned SIGKILL and explicit restore of generation12 succeeded. The saved and restored
202-byte documents are byte-identical and the restored tab is dirty/unsaved. Evidence:
`repair-batch-20261023/native-20261023/` beneath primary-checkout local evidence.
These results identify APK fcc93ac8, not a later rebuilt APK.

The native session exposed misleading close-dialog wording claiming a completed
checkpoint after discard. An actual-dialog regression fails before the correction
and passes afterward; the dialog now describes verification before closing. All
nine focused recovery groups pass. A synthetic proposal trace retires live Gboard
before injecting callbacks, so late real-IME finish notifications cannot contaminate
that synthetic fixture. Its exact accepted/proposal assertions remain unchanged.
The rebuilt binary consumer, integrated checks and affected native retest are
tracked in the subsequent receipts/handoff. No core behavior/API changed for this
wording correction.

The read-only native trace shows direct `commitText` for the six Gboard key presses;
prediction suggestions are not proof of active preedit. Real composing-formatting
and conversion IME remain unqualified. Emulator Ctrl+Z toggles host zoom, and Home
is intercepted by the emulator; the app's actual Undo/Redo buttons were used instead.
Client reset alone left the helper banner active. The verified session-owned helper
was explicitly stopped before the other session resumed input.

Branch `codex/android-recovery-close`, based on main e30c984. No new feature,
dependency, core API, frozen specification or approved desktop geometry change.

## Before and after

Before production edits, mounted tests using the actual shell/owners/recovery store
failed both independent witnesses: discard followed by unchanged keep/close had no
replacement recovery; a second close lost the first owner's callback. Raw failures
remain in `tests/local-evidence/repair-batch-20261023/close-mounted-negative/` in the
primary checkout. The original independent stub witness remains unchanged.

The single afterFlush slot and empty-queue success inference are removed. Each
bounded keep/close obligation captures owner, session revision, accepted document,
separate unfinished proposal/evidence and a fresh monotonic generation. Publication
plus exact read-back and a current-owner/revision check are required for DURABLE.
Every request receives one DURABLE, CANCELLED or FAILED outcome. Changed writing,
required-generation discard and teardown cancel the affected close; IO/quota/queue
failure returns a reason and keeps writing open. Owners have independent outcomes.

Discard is fenced before asynchronous deletion and ordered on the same writer.
Store tombstones reject delayed discarded writes. Older automatic completions cannot
supersede newer successful generations or retry closed owners indefinitely. Snapshot
equality is a scheduling optimization, never proof of durability. Unchanged content
after discard still gets a fresh generation on explicit keep/close.

## Results and identified build

- Nine mounted recovery groups PASS: discard/unchanged close, two owners, repeated
  closes, edits during close, in-flight discard, IO failure/retry, quota/retry,
  separate unfinished composition evidence and teardown outcomes.
- All previous 54 groups retained: final independently packaged APK passes 63
  groups and exact pinned font/notice checks.
- Two real owned SIGKILL/restart tests PASS: last completed checkpoint versus later
  edits; and discard -> unchanged keep/close reports durable -> kill -> fresh restore.
  Exact codec/resource bytes and an independent rich document description are checked.
- Outside-checkout binary consumer: 10 public-API runtime groups, Android and peer
  builds PASS. Actual cross-UID rich clipboard after publisher death/cleanup PASS.

APK SHA256 `fcc93ac8736b929474f0e4521415965659aeb58cdfb5a91dc665813629fc1747`.
Primary-checkout evidence root: `tests/local-evidence/repair-batch-20261023/`.
Receipts: `recovery-packaged-final/results.json`, `recovery-packaged-63/results.json`
(both interruption witnesses nested), `recovery-packaged-clipboard/results.json`.
All raw earlier failures are retained. These are mounted/instrumented and real
process-termination results, not genuine keyboard/IME, AT or power-loss qualification.

Native tool initialization now succeeds and finds the owned emulator. Two captures
failed with `window crop is outside captured monitor`; no editor input was sent.
Owner window positioning was requested while independent work continued. Subsequent
native disposition belongs in the batch handoff, not an inferred PASS here.

## Recovery boundary and reproduction

Coalesced autosave normally schedules after 400ms; IO failures retry after 5s.
Neither is a maximum completion guarantee. Only completed copies survive the tested
process termination; later edits may be lost. Provisional input remains separate
evidence, with no live-session serialization or callback replay. SIGKILL does not
prove force-stop, power-loss or provider atomicity. Failed restore/export preserves
records under the existing tests. Ordinary Save remains separate from recovery.

Use the documented D-drive environment and only the owned AVD:

```powershell
. D:\Work\WYSIWYG-Text-Editor-Compose\tests\local-evidence\android\integration-20261020\dev-environment.ps1
.\gradlew.bat :apps:android-sample:assembleDebug :apps:android-sample:assembleDebugAndroidTest
python tooling/verification/consumer_check.py --android-peer --output-dir tests/local-evidence/consumer/recovery-repair
python tooling/verification/android_check.py --serial emulator-5580 --avd-name composedoc_slice_20261020 --consumer-receipt tests/local-evidence/consumer/recovery-repair/results.json --output-dir tests/local-evidence/android/recovery-repair
python tooling/verification/android_clipboard.py --serial emulator-5580 --avd-name composedoc_slice_20261020 --consumer-receipt tests/local-evidence/consumer/recovery-repair/results.json --output-dir tests/local-evidence/android/recovery-clipboard
```

Install the consumer receipt's identified application APK, then launch
`dev.shayxo.composedoc.androidsample/.MainActivity`. Write a disposable draft, wait
for completed recovery, discard only that captured generation, Keep recovery &
close, relaunch and explicitly restore an unsaved copy. Failed/cancelled close must
leave the tab and writing open. See the integration guide for the complete workflow.

Owner-written PR metadata and review remain required. No main push, merge, issue
closure, contributor-branch rewrite or publication is authorized by this repair.
