# Android rich writing — 21–22 October 2026

Starting clean main/origin-main: b513870326c3ba32e603ac309d4d681adde4d1d6.
The prior COMPLETE receipt and all five CI/four CodeQL jobs were freshly checked.
No outstanding changes, empty commit or repetition of unchanged gates was needed.
This is the owner's next bounded assignment, not a new architecture/research phase.

## Delivery checklist

| Delivery | Status |
|---|---|
| 1. Touch/locale word selection, endpoint drag, autoscroll, hardware navigation | implemented; instrumented PASS; new native walkthrough BLOCKED |
| 2. Formatting/spacing, headings/outline, semantic nested lists | implemented; structure/history/geometry PASS; new native BLOCKED |
| 3. Actual rich/plain clipboard transport and exact Find/Replace | implemented; actual system cross-UID transport and command checks PASS; native BLOCKED |
| 4. URI picker IO, stale-result protection, durable committed/unfinished recovery | implemented; failure/race tests and real process interruption PASS; native pickers BLOCKED |
| 5. Owned embedded images, controls, exact history/persistence/recovery | implemented; resource/history and async-image checks PASS; native BLOCKED |
| 6. Public-only independent binary consumer and integration guide | PASS: final independent binaries, external runtime10 groups and Android/peer builds |
| Connected automated and actual native qualification | PASS54 packaged groups + real kill/restart + cross-UID cleanup; new native BLOCKED |
| Clean-commit final gates, authorized main push and exact hosted results | completion authority: delivery receipt and tooling/local/final-verification.json; never infer status from this pre-commit report |

## Policies before implementation

- Preserve the single document owner and typed existing intents. Repair reproduced corruption,
  history or ownership failures before building dependent features. Preserve the 27 existing
  adapter groups; add discriminating cases rather than treating a count as coverage.
- Platform ICU word selection uses the current Android resource locale, then the pinned
  application grapheme stops. Punctuation selects a grapheme; URLs use ICU segments, not a
  promise to select a whole URI. Native IME coordinates remain raw UTF-16 and are never snapped.
- Formatting controls preserve logical selection and composition admission/settlement. Mixed
  state is explicit. Backward selections and active-composition attributed Undo/Redo are tests.
- Use existing heading/list/Enter/split/join/outdent/restart semantics and half-open paragraph
  targets. Markers remain presentation; never insert direction controls/spaces/bullets into text.
  Each child's direction owns its marker side, independent of the root-side nesting column.
- Expand the Android capability guard only with actual rendering/geometry/edit/history coverage.
  Running content, pagination and explicit page breaks remain refused, without replacing a draft.
- Search is literal, case-sensitive raw Unicode equality within text paragraphs. No normalization,
  hard-paragraph crossing, image matching or replacement of interior-grapheme endpoints.
  Replace All uses the captured existing domain basis and one attributed transaction.
- Clipboard uses the existing versioned fragment/complete-paragraph policy, fresh pasted IDs,
  standard plain text and a bounded content-URI rich stream. Cut removes only after successful
  system publication and valid captured basis. Malformed/unavailable rich transport falls back
  visibly to bounded plain text. No provisional text is exported as accepted content.
- Open creates an independent editor; New/Open retain existing dirty work. Async picker/read/save/
  image callbacks capture owner identity and operation generation, never whichever tab is active.
  Save completion records only its captured basis; newer edits remain dirty/recoverable.
- Content URIs are provider capabilities, not file paths. Keep a local intended-content copy and
  prior provider bytes before risky overwrites; verify the completed stream. Provider replacement
  cannot be promised atomic or immune to an external writer between checks.
- Recovery uses bounded coalescing and private interruption-safe, checksummed slots, never user
  files. Unfinished proposals/evidence have a separate field; native sessions are never replayed.
  Quota exhaustion preserves old records and reports a failure. Restore/export failures retain
  records; discard is captured-generation-specific and cannot delete a new arrival.
- Images own validated bounded PNG/JPEG bytes. Aspect ratio follows the resource; requested size
  is stored and display fitting never changes it. Images are atomic; resize/replace/remove and
  exact resource Undo/Redo are checked independently. Recovery owns its required resource bytes.
- Reuse packaged fonts, consumer infrastructure and the bounded owned-emulator CI setup. No
  runtime dependency upgrades, protected-file edits, publishing, release tags or other platforms.

## Current work and restart

Previous delivery checkpoint freshly reconfirmed: local HEAD and origin/main are both b513870;
CI 39587733610 and CodeQL 39587733875 completed successfully for that exact commit. No previous
batch source is waiting to be pushed. Current dirty files belong to this new unfinished batch.

The owner approved the additive APIs and API dumps, with specific IO/recovery/clipboard/search
safety requirements. New touch/layout/storage/clipboard/search adapters and application controls
are integrated in source. Focused core compile and first shell compile pass; full new-feature
qualification is pending. Recovery regressions now cover ordering, reused identity rejection,
captured discard/new arrival, separate proposals, truncated-slot fallback, corrupt-ledger isolation,
metadata-only scans, failed restore/export retention, quotas and resolved-owner limits.
Focused receipts under `tests/local-evidence/android/rich-writing-20261021/`:

- `focused-recovery-1`: PASS4 groups, including metadata-only listing, ordering, quotas,
  truncated-slot fallback, separate composition proposals and failed read/export retention.
- `focused-structure-1`: PASS4 groups, including nested opposite-direction item paragraphs,
  heading Enter/outline, exact image resize/replace UndoRedo and resource/recovery round trips,
  attributed Replace All and active native-callback backward-selection formatting settlement.
- `focused-uri-3`: PASS4 groups, including provider conflict/permission failure, generic-port
  bypass refusal, delayed save with newer edits and owner switching, cancelled/duplicate/malformed
  picker results, and deliberately incomplete provider output retaining prior/intended bytes.
- `focused-clipboard-2`: PASS3 groups. Actual Android ClipboardManager + read-only content URI
  transport to another editor process, stale Cut refusal, ordinary/explicit plain paste and
  explicit cache cleanup. This is instrumented transport, not human clipboard interaction or
  cross-UID grant qualification. First raw failed receipt retained: the fixture incorrectly
  waited on a transient status string and expected paste at a different caret from its setup.
- `focused-touch-2`: PASS4 groups, including12 script/stress word fixtures, backward endpoint
  dragging/stale drag cancellation, hardware navigation and foreign/stale search presentation.
  `focused-touch-1` preserves the real RTL word-edge failure: nearest-caret hits could choose
  adjacent punctuation. Word gestures now hit character cells, then apply ICU/pinned grapheme
  selection. Tap-caret and raw native coordinate policies remain distinct.
- `shared-columns.log`: PASS independent numeric reference cases for shared sibling gutters,
  opposite-direction nesting and item continuation. Desktop raster comparison is still pending.
- `quality-4.log`: focused compile/format/Detekt PASS before subsequent small additions; final
  checks must run against final source. `docs-baseline.log` confirms all81 protected hashes.

These receipts identify different development APK hashes; they are not combined into a final-build
qualification claim. `focused-checkpoint.json` indexes them.

22 October continuation:

- `integrated-46-first`: PASS46 on its intermediate build.
- `focused-lifecycle-1`: PASS3 for recreation with structured backward selection/history,
  separate durable composition evidence with retired callbacks, and endpoint edge autoscroll.
- `focused-layout-4`: PASS3 at font scale1.3, including32 actual marker raster cases against
  independent inline digit/x ink, requested/fitted image aspect geometry and heading appearance.
  Earlier raw failures are retained. Android's nonlinear SP conversion caused the observed
  bullet drift; measurement now uses the actual density SP conversion. No desktop marker policy changed.
- `integrated-52-first`: instrumented52/52 PASS on APK bf96f37b34d6315fcdd5e6af78b8f0689d6e5e82061315a192c80ea61deea15c.
  The encompassing process stage FAIL is retained: exec-out returned exit0 with a missing-file
  diagnostic before the receipt existed. The driver now retries only that specific readiness case;
  malformed JSON still fails. The next attempt reused a live debug activity; a new isolated task
  plus validated owned-PID cleanup resolves that fixture issue.
- `process-kill-3`: PASS real SIGKILL of owned PID23692 and fresh restore PID23786. Independently
  described heading, nested opposite-direction lists, spacing, title attributes and owned PNG
  restored exactly; committed .astra bytes match the last completed checkpoint. Provisional
  Japanese text stays separate and later accepted edits are absent, demonstrating the loss window.
  Synthetic setup is not native IME or power-loss evidence.
- `android-driver-self-tests-2.log`: PASS17, including strict PID ownership, fresh-process exact
  restoration and missing-versus-malformed receipt handling.
- `quality-integrated-2.log`: FAIL in an inherited JS oracle: emitted expected literal was `A?B`
  although raw deletion correctly retained the lone high surrogate. The test now compares exact
  UTF-16 numeric units for text and model storage. Raw XML and generated JS snippet are retained.
  Full quality rerun is pending; this is not a claim that the failed gate passed.

`packaged-52-final` passes52 groups plus actual interruption on independently packaged APK
59b8afaa1791536bbdd6bd818b68ebfa5e988f8b12be79dde604333283516090.
`cross-uid-final` passes actual clipboard transfer from UID10229 to UID10231 after publisher death,
fresh semantic identities and preserved owned-image identity. Setup/actions are injected, not human
or keyboard interaction. Final additional image-picker/cleanup witnesses supersede this checkpoint.
`focused-image-safety`: PASS5 URI/image groups, including delayed image reads with intervening edits,
cancelled results, corrupt bytes and revoked grants; document/resources/history remain unchanged.

The visible emulator's host clipboard bridge replaced a rich clip with a system-owned plain-text
clip. Raw `cross-uid-1` FAIL and clipboard metadata are retained; host text was not recorded.
The same witness passed headlessly. Android's
[emulator clipboard bridge](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/clipboard/EmulatorClipboardMonitor.java)
reconstructs host data as plain text. No global sharing setting changed. The old-event wait in the
copy test was also replaced with a latch specific to that operation.

`desktop-pages-preservation.log`: PASS21 stages at desktop/batch-20261022-002923-30192, including
18 pages/output groups and432 marker memberships. `desktop-raster-preservation.json` compares all
ten approved PNGs with batch-20261020-154842-11676: all byte-identical.
`quality-precommit.log`: check/apiCheck PASS, zero Detekt, including Android adapter dumps.
Nineteen verification-driver/API self-tests pass. Exact final gates/hosted results remain pending.

The preinstalled API28 image cannot start with the installed emulator37.1.2: both default and
documented qemu2 attempts reject the disposable AVD with missing `kernel_cmdline.txt`, before
registration/instrumentation. Raw `api28-create.log`, `api28-emulator*.log` and owned/exited receipts
are retained. Lower-API qualification is BLOCKED on a compatible installed emulator/image pair;
minSdk24 remains unchanged. No SDK installation, existing AVD modification or global setting change.

Android adapter signatures now have explicit supplemental `core/*/api/android/*.api` checks,
because existing BCV consumed only JVM dumps. `androidApiCheck` and19 driver/contract self-tests
cover the approved additions alongside unchanged common/JVM API checks. No runtime dependency added.

Exact restart: inspect the delivery receipt and compare its commit with HEAD/origin/main. Finish
only a failed/pending final gate or hosted job. If those are green, independent implementation is
delivered and native qualification remains blocked below; do not start another feature batch.
Never label the missing native walkthrough complete.
All development caches/temporary evidence stay on the established D-drive setup.

Native evidence remains separate from callbacks/injected gestures, fixture paste, candidate
conversion and audible TalkBack. The preceding batch's native coverage is not new-feature proof.

Final inspection found a concrete shell defect: imperative formatting/property queries did not
subscribe to Compose state, so strong skipping left control values stale. `shell-negative` fails
on the defective build; `shell-fixed` passes the same mounted accessibility regression (mixed
selection, actual Bold click, active value, exact Undo, no group reopening). `editorQuery` now
subscribes public queries to immutable published state without recreating controls. Paragraph,
structure, image selection, history availability and saved-state indicators use that path too.
No public signature, document semantics or desktop geometry changes in this repair.
The earlier image placeholder was asynchronous loading: `render-after-settle/rich-source.png`
shows the actual decoded blue image. The raw early frame is retained, not labelled a render failure.

The first source push f01d53b passed all six local gates but GitHub rejected CI before creating
jobs: the added clipboard step was indented inside the preceding step. The raw failed workflow
receipt is retained. A three-case structural guard reproduces that error at line129, covers a
valid step after a shell block and literal shell-list text, then passes after correction. It is
explicitly a narrow indentation guard, not a full YAML/workflow parser. Clipboard CI evidence is
also included in artifact retention. This follow-up changes CI/docs/tests only; application and
library input hashes, the APK and all54 packaged/interruption/clipboard results remain identical.
The final exact-commit gates and hosted results in the delivery receipt supersede that failed push.

## Delivered build and evidence authority

- Runnable APK: `tests/local-evidence/android/rich-writing-delivery-20261022/ComposeDoc.apk`.
  SHA256 `6d0a40d50678e84874c33fec694e35aa5bacc9d7d1a9acf9e4f4d8dcd104455d`.
- Disposable editable sample beside it: `rich-writing-sample.astra`, generated by the independent
  rich recovery witness, not a user document. Open through a document provider as an independent tab.
- Artifact/dependency/source-input inventory and external Gradle locations:
  `tests/local-evidence/consumer/rich-writing-20261022-shell-final/results.json`.
  Own settings, binary coordinates, no editor implementation/harness dependency,10 runtime groups PASS.
- Final packaged Android54 groups + exact fonts + real owned child SIGKILL/fresh restore:
  `tests/local-evidence/android/rich-writing-20261021/packaged-54-shell-final/results.json`.
- Actual cross-UID system clipboard, publisher death, read-only grants, fresh item IDs, owned PNG,
  fresh-process cache cleanup and subsequent peer SecurityException with its document intact:
  `tests/local-evidence/android/rich-writing-20261021/cross-uid-shell-final/results.json` (two cases).
- Quality/check/api: `quality-shell-final.log`;19 driver/contract self-tests, zero Detekt.
- Desktop21-stage preservation and ten exact approved page PNGs: `desktop-pages-preservation.log`
  and `desktop-raster-preservation.json`. Historical/sealed81 hashes remain intact.
- Overall source/CI delivery authority: `tests/local-evidence/android/rich-writing-delivery-20261022.json`
  and `tooling/local/final-verification.json`. Original dirty-build receipts retain their actual
  starting commit; final source-input equality binds artifacts to the eventual clean commit.

All short evidence names above are under `tests/local-evidence/android/rich-writing-20261021/`.
The named local AVD is composedoc_slice_20261020 / emulator-5580, API37 Android17,420dpi,2GiB guest,
font scale1.3 for the integrated/layout checks. This is not a minimum-memory/platform qualification.
No physical device, normal AVD, global keyboard default or system font installation was changed.

Fresh screenshots were inspected at readable resolution. These are instrumented renders, not native
interaction: [rich document/mixed controls](assets/android-rich-writing-20261022/rich-document-and-mixed-controls.png),
[Gulzar bullet](assets/android-rich-writing-20261022/gulzar-bullet.png),
[Noto number](assets/android-rich-writing-20261022/noto-number.png).
The first heading is partly outside the viewport after revealing the selection end; that is scrolling,
not an assertion that Nastaliq was clipped by a line container. Platform clipboard overlays are transient.

| Language/operation evidence | Result / limit |
|---|---|
| Urdu, Arabic, Persian, Hebrew, accented Latin, Devanagari, Bengali, Thai, Chinese, Japanese, Korean, emoji ZWJ | PASS12 mounted ICU-word fixtures with exact recorded UTF-16, grapheme endpoints and unchanged history; not linguistic/IME qualification |
| Urdu/Arabic/mixed Latin, combining marks, joiners, emoji and surrogate boundaries | PASS original native-protocol/model traces, formatting/history/codec preservation; callbacks are synthetic |
| Noto Nastaliq, Gulzar, Naskh and system face;16/26 sizes; RTL/LTR; number/bullet | PASS32 independent marker-ink cases at font scale1.3; no cross-platform pixel claim |
| Real soft keyboard/candidate conversion and TalkBack speech on final build | NOT RUN/BLOCKED as detailed below |

## Native blocker and remaining qualification

22 October supported computer-use initialization failed before any action:
`failed to write kernel assets: The system cannot find the path specified. (os error 3)`.
The JavaScript kernel reset succeeded; a second initialization returned the identical error.
No clicks or keystrokes were sent. `native-blocker-20261022.json` records both attempts.
Computer control is OFF. No alternate UI automation or injected action is labelled native.

| Check | Status / boundary |
|---|---|
| New rich-writing native keyboard/selection/formatting workflow | BLOCKED: computer-control runtime cannot initialize |
| Native SAF/image picker, validation/cancellation and close controls | BLOCKED; actual handlers/IO instrumented separately |
| Genuine English Gboard on the expanded final surface | NOT RUN; prior body-text evidence is historical |
| Genuine Urdu/Arabic keyboard and Japanese/Korean conversion | NOT RUN; no keyboard/language installed; fixtures are not typing |
| Native rotation, compact/tablet and increased-font-scale interaction | BLOCKED; instrumented recreation/reveal/font-scale1.3 is separate |
| Actual TalkBack speech | NOT RUN; editable actions/alt-text spans do not prove speech |
| Lower supported API | BLOCKED: installed API28/emulator pair fails before registration; minSdk24 retained |
| Physical Android device | NOT RUN; connected owner devices were never targeted |
| Expert typography, desktop Narrator/IME, advanced/physical print, semantic PDF | OUT OF SCOPE / previous unqualified boundaries retained |

## Ten-minute hands-on checklist (manual, not a completed native result)

Build with `.\gradlew.bat :apps:android-sample:assembleDebug` and explicitly target the owned
API37 AVD: `adb -s emulator-5580 install -r <identified-app.apk>`. Launch:
`adb -s emulator-5580 shell am start -n dev.shayxo.composedoc.androidsample/.MainActivity`.
Use the integration guide's keyboard-visibility setup if a hardware keyboard hides Gboard; record
and restore the test-only preference. Host clipboard synchronization can flatten rich clips;
disable it yourself for this test if necessary. Do not apply settings to a normal/physical device.

1. Tap the document, activate Gboard and type English/numbers and Urdu/Arabic from an available
   layout. Record genuine keys separately from pasted fixtures. Long-press near punctuation,
   marks/emoji, drag both endpoints and extend near an edge. Check caret blink and reveal.
2. Select backwards across scripts. Use Text for bold/italic/underline/strike, stored font/size/color
   and reset. Apply a formatting button during a live candidate, commit, then Undo/Redo acceptance
   and formatting. Dialogs require finished input and must preserve the selected range.
3. Use Paragraph for Auto/RTL/LTR, Start/Center/End, before/after and line spacing. Use Structure
   for headings/outline, lists, child indent/outdent, restart and item paragraphs. Check each child's
   own direction/marker side; exercise Enter, Backspace, Shift+Enter and history.
4. Copy mixed structure into the second process or installed peer, paste rich, Undo/Redo and
   Paste plain. Exchange plain text with another Android app. Wait for publication before switching;
   provisional text must not appear in the accepted fragment.
5. Open Find and Replace (File in compact mode). Verify exact-Unicode counts, next/previous reveal,
   replace one/all, empty replacement and history. Edit while results remain visible; stale ranges
   must not replace unrelated text.
6. Insert PNG/JPEG through the picker; tap the image, set size/alt text, correct an invalid width
   without losing the dialog, replace it and Undo/Redo. Cancel another pick and verify no edit.
7. Save As through the real provider, edit during a later save, switch tabs and reopen. Confirm
   exact supported properties/resources and newer dirty work. Cancel a picker and exercise denied
   permission/provider failure: preserve the draft and do not show success.
8. Wait for a completed Recovery generation, then terminate only the disposable app process.
   Relaunch and explicitly restore an unsaved copy. Compare accepted text/resources exactly and
   inspect unfinished evidence separately. Failed restore/export, Later and Cancel retain records.
9. Rotate with selection/composition, background/foreground, hide/show the keyboard and test compact/
   larger layouts and font scale. Verify owner independence, history and no stale callback replay.
10. Enable TalkBack yourself if available; record actual spoken text/selection/control/image labels,
    then restore its prior state. A focus rectangle alone is not speech qualification.

Keep exact files/state receipts alongside screenshots. Mark unavailable native capabilities
BLOCKED/NOT RUN; this checklist is not evidence that its actions occurred.
