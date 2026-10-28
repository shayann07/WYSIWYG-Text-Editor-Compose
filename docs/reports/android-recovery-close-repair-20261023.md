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

## Close retry and discard follow-up — 27 October 2026

Independent review found that an unsuccessful close could consume a previously
scheduled automatic recovery snapshot. On an owner-authorized Samsung API36,
the unmodified controller failed both the write-failure and busy-admission tests:
without another edit or close attempt, no copy appeared within 15 seconds. The raw
before results are in `tests/local-evidence/landing-20261026-resume/` in the
primary checkout. An attempted follow-up run stopped before the editor mounted; it is
retained as a harness failure, not a recovery test result.

The follow-up retries eligible immutable snapshots after failed or cancelled close,
while fencing discarded generations and retaining newer pending content. Discard
in progress defers a retry; failed deletion restores it, and a successful deletion
prevents resurrection. A pristine empty tab does not create a recovery record;
discarded or nonempty documents still require a verified copy. Independent packet
writes continue if one owner fails, while the combined close still refuses success.
Recovery and leave errors are placed in the visible tab notice. The tap fixture's
host test writes explicit LF bytes on Windows.

Five focused mounted controller/store tests PASS on a disposable Android API28
emulator, using app SHA256 `50b482370346aec025aa6bd0477f5f2221e02e0f825d823adceef38d5c617300`
and test APK SHA256 `225bfd113dc1a12d8e318610fc47f329ac33384d084e72253d47545182252547`.
The receipt is `landing-20261026-resume/recovery-api28-focused/receipt.json` in
the primary checkout. This tests instrumented events, not real IME or TalkBack.
Debug/test compilation, zero Detekt, and local check/apiCheck passed; full packaged
consumer, process interruption and exact-commit gates remain pending this candidate.

The first complete packaged API28 run exposed three failed groups: an API30-only
keyboard-insets call, an AtomicFile fault fixture that API28 could rename aside,
and an Arabic long-press selection that had not arrived by a fixed sleep. The
raw failure remains in `landing-20261026-resume/recovery-f768-api28-full/`.
Test-only compatibility changes use pre-30 visible bounds, nonempty base and
backup obstacles, and the existing event/publication trace with the same
selection expectation. The first focused attempt mistakenly targeted the toolbar
group and is retained. After correcting the target, mounted, recovery and touch
focused groups all passed on API28; see
`landing-20261026-resume/recovery-api28-backport-focused-2/receipt.json`.
These are instrumented checks; the full suite and interruption witnesses must
still pass on the final candidate.

## Hosted failure and bounded readiness admission — 28 October 2026

Candidate `3d812e6a1297be9537d8df8da93add7d6fe33c9a` passed all six local
exact-commit final gates. Its hosted CI run `40464372511` failed in Android before
any editor group ran. The retained log explicitly records lowmemorykiller killing
the test application PID3982 at 07:04:55.064Z, with a minimum-watermark reason.
The underlying pressure cause remains unresolved. Build, architecture, docs,
iOS and CodeQL checks passed; they do not override this Android failure.

The pre-test memory dump was only 118 bytes and contained two service timeouts,
despite returning exit zero. Installation-broadcast idle is a queue barrier;
later receiver work overlapped instrumentation. Passing older startup runs remain
valid for their exact inputs and do not establish this candidate passed.

The new host-side admission checks one memory-service response before installation
and one immediately before cold instrumentation, using the remaining existing
setup deadline and a 15-second command cap. It rejects timeout markers in either
stream, incomplete/malformed/duplicate summaries and unsuccessful command status.
Raw output, command outcome, elapsed time and parsed values are retained. Rejected
setup exits nonzero as BLOCKED; editor, recovery and clipboard remain NOT RUN.
Failing diagnostics do not replace the original failure. No RAM threshold, sleep,
warm attachment, test retry or longer instrumentation deadline is introduced.
API28 retains its existing behavior; this guard does not qualify its different
memory-dump format. The original workflow dump is now recorded with command
metadata inside the Android evidence directory instead of an unchecked shell
redirection at its parent.

The exact passing and failing raw diagnostics are maintained fixtures with
provenance. Host regressions exercise the actual runner at both admission points,
verify no instrumentation or recovery child starts after rejection, and preserve
the primary failure when later diagnostics fail. This is a detection improvement,
not a repair of the emulator memory-pressure cause. Native/device execution and
any new hosted attempt require separate independent disposition. No new Android
PASS is claimed from these host tests. Physical-device access is currently
unavailable by owner report; genuine multilingual IME and AT gates remain open.

Independent review also required exercising the actual command wrapper when raw
evidence writes fail. The new fault-injection regressions fail before correction:
the wrapper's OSError hid the original timeout or completed exit status. It now
attempts each evidence write, preserves a timeout as the primary exception, and
passes completed command status to the observer before judging retention failure.
A valid command whose evidence cannot be retained still blocks admission. These
tests inject host filesystem failures; they do not execute Android.
Failure-receipt write failure also leaves the original timeout primary, with an
explicit exception note. Both original wrapper failures are retained as before-fix
host results. Seventeen new host regressions (47 total Android host tests) cover
the reader and actual runner; this count is separate from mounted Android groups.

An overall PASS from `android_check.py` covers editor groups and recovery witnesses
only. Its clipboard field stays NOT RUN because `android_clipboard.py` is a
separate workflow step with a separate cross-process transport receipt.
