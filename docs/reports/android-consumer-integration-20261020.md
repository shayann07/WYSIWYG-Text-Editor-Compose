# Packaged consumer and Android integration — 20 October 2026

Starting clean main: `5aa9ce6272e60cef4eb019322b9788b061c80f0a`. Independent consumer and bounded Android slice implemented/native-tested. Exact final gate/hosted status is recorded separately below.
Approved desktop marker geometry/numbering is preserved. No remote artifact publication.

## Final evidence authority — 21 October 2026

The chronological checkpoints below retain failures and their then-pending work. For final
completion use `tests/local-evidence/android/integration-delivery-20261020.json` and
`tooling/local/final-verification.json`: require matching HEAD/origin-main, six local gates,
five hosted CI jobs and all four CodeQL jobs. The Android hosted receipt must contain all27
executed checks; setup READY and a zero instrumentation exit code are insufficient.
The newest overnight handoff specifies the stop condition and exact artifacts. Do not infer
current completion or failure from a historical checkpoint's pending wording.

## Delivery disposition (application/native checkpoint)

| Delivery/gate | Result |
|---|---|
| Independent Gradle consumer, Maven metadata/binaries, outside-checkout installed launcher | PASS |
| Public-only API use, independent owners, attributed history, persistence, disposal/recreation | PASS |
| Packaged pinned JVM/Android fonts, corruption/unavailability handling | PASS |
| Android Compose body-text surface, native connection and lifecycle integration | Implemented; instrumented and bounded native evidence below |
| Final native Gboard composition/commit, attributed editing and explicit-save process-death reopen | PASS on identified APK |
| Complete final Android adapter suite | PASS27/27 on final packaged APK; isolated visibility precondition recorded |
| TalkBack focus/activation | Observed in live service; speech NOT RUN (no usable audio capture) |
| Native Urdu/Arabic keyboard layouts, Japanese/Korean conversion | NOT RUN; installed layout coverage unavailable |
| Full quality/API and earlier pages/output preservation | PASS; exact-commit result in final receipt |
| Local commit, clean-commit gates, push and exact CI/iOS/CodeQL | Require complete exact-commit delivery receipt above |

## Command and lifecycle policy settled before implementation

The shared domain engine remains the sole document/history owner. Native UTF-16 coordinates
are exact after Android-mandated clipping/reordering; application touch/deletion commands use
existing grapheme rules. No hidden editable widget or second text buffer is authoritative.
IME candidate updates are provisional; commit/finish accept explicitly. Empty preedit is not
zero partial acceptance. Explicit cancel, focus/session retirement and lifecycle teardown abort
provisional content while preserving accepted prefixes. Commands waiting for settlement do not
force an IME candidate to commit. Old connections are permanently invalid after replacement.
Batch edits defer notifications, not owner publication or queries. Surrounding deletion preserves
selected/composing text and all unaffected attributed units in one owner transaction.

The minimal Android surface is continuous body-text editing, using native Compose shaping;
Android rendering is qualified separately from approved desktop rasters. Unsupported rich
blocks must remain visible/atomic or be refused, never silently flattened. IME newline text is
a soft break; explicit Enter/editor action uses the existing structural paragraph command.

Recomposition retains owners; Activity nonconfiguration retention preserves accepted content
and attributed history. Native sessions never survive recreation. Process death guarantees
only completed explicit saves in app-private storage; SavedState is not crash recovery.
Any unfinished composition retained for inspection remains memory-only and is never saved
as accepted content. These boundaries must be tested and reported separately.

## Required tooling compatibility witness

The first real APK assembly refused18 AAR metadata requirements: existing pinned Compose1.12.2
resolves Android UI1.12.1 and Lifecycle2.11.0, requiring API37 and AGP9.1+. The previous empty
Android/common compilations never exercised that packaging gate. Raw failure is retained in
`tests/local-evidence/desktop/android-app-first-20261020.log`. Owner explicitly approved the
minimum build-tooling update in chat. AGP9.1.1 supports API37.0 and requires Gradle9.3.1
([official compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)).
Only build tooling/compile SDK changes; Kotlin/Compose/Skiko/Lifecycle runtime pins stay unchanged.
Existing installed SDK37 is used; no SDK/system-image/keyboard installation is authorized or needed.

## Live implementation checkpoint (20 October, before final qualification)

- Additive typed NativeEdit entry, Android-normalized NativeTextInputSession and14 shared JVM
  protocol tests implemented. New native Android InputConnection reads the owner directly;
  getEditable is null. Batch selection notifications defer without hiding owner publications.
- Android Compose surface, touch selection, public accessibility actions/character bounds,
  keyboard reveal and explicit unsupported-rich-content guard implemented. App retains owners
  through nonconfiguration recreation; memory-only unfinished proposals can open as separate
  unsaved copies. UUID save locations avoid owner/process collisions; Open latest saved never
  overwrites the current draft. Android codec uses the same extracted snapshot implementation.
- Pinned Android assets package original manifest/fonts/OFL; worker validation exposes missing or
  corrupt faces. First APK built/launched on isolated API37 `composedoc_slice_20261020`, serial5580.
  No physical device, normal AVD, input default or system font installation was changed.
- Instrumented production adapter/Compose/lifecycle suite reached24/24 PASS at
  `tests/local-evidence/android/integration-20261020/adapter-third-*`. Genuine keyboard interaction
  is still NOT RUN. A further26-group run adds programmatic-edit connection fencing and the
  production zero-partial witness; its final result is pending.
- First visual failure: per-UTF16 spans broke joining/emoji. Grapheme grouping fixed emoji,
  then independent native Paint exposed remaining unmerged equal grapheme spans (Urdu261px vs
  expected137.04px). Shared renderedStyleRuns now implements the identical previous desktop
  merge and is used by Android. Native Urdu/emoji/combining advance checks pass with2px edge
  allowance. Desktop exact raster regression still PENDING; no approval to change its geometry.
- Raw first and second instrumented failures retained. Prefix Undo failure was test fixture
  typing coalescence, AtomicFile fixture accidentally nested runOnMainSync, and keyboard shrink
  initially measured an already visible IME. Setups corrected explicitly; assertions retained.
- External Maven repository publication succeeded. First consumer compile exposed two example
  mistakes (public property spelling and unnecessary experimental lifecycle use). Second build
  exposed colliding JetBrains metadata-only forwarding jars. A narrowly targeted distribution
  filter preserves real/native/resource jars. New DesktopEditor public host assembly prevents
  examples copying setup and overriding the native host publication callback. Final consumer
  build/runtime and independent Android packaging are still PENDING.
- Current build environment uses approved AGP9.1.1 / Gradle9.3.1 / compileSDK37. Runtime pins stay
  Kotlin2.4.21, Compose1.12.2, Skiko0.150.2, Lifecycle2.11.0. Native allocation failure retained as
  `hs_err_pid30016.log`; only this session's idle Gradle PID39104 stopped. Build concurrency is
  bounded to two workers/2GiB/in-process compiler, with no test threshold change.
- Owned emulator first failed netsimd startup; supported no-netsim disables unneeded network/radio.
  It is now supervised by ignored `emulator_bridge.py` (exec session36203), headless, Vulkan guest
  disabled. Computer control remains OFF. Do not claim launched APK or instrumentation is real
  keyboard/IME/AT qualification. Installed Gboard and TalkBack discovered; TalkBack NOT RUN.

Exact next: finish26-group device run, external binary consumer+Android artifact build, finish
quality/API/architecture/CI wiring, then request bounded owner-idle native keyboard and outside
consumer walkthrough. Keep all failures, source UTF8, 48+33 frozen files and old evidence intact.
No final commit/push/gates yet; no publication to Maven local or any remote artifact repository.

## Build-environment correction

The tool process did not inherit the owner's already configured D-drive environment. Explicitly
use `D:\DevSetup\.gradle`, `D:\DevSetup\Android\Sdk`, `D:\DevSetup\.android` and
`D:\DevSetup\.konan`. This batch's temporary directories now use `D:\Temp\composedoc-20261020`.
Do not change global settings or move the owner's installed tools/keys. The installed JDK21
remains at its existing C-drive location. Only this batch's two disposable external-consumer
directories and its new Gradle9 cache were moved off C; raw failed receipts retain their original
paths, with a separate ignored relocation map. Existing D-drive caches and normal AVDs survive.
An optional `composedocDebugKeystore` Gradle property permits the isolated test to retain its
previous debug signing identity using a private D-drive copy, without overwriting either key.
Computer control remains OFF during source/build work.

## D-drive checkpoint and expanded instrumented result

The owner requested moving development data off C and using the existing D setup. The tool's
scratch environment is explicit in the ignored `dev-environment.ps1`; global user settings and
installed applications were not changed. Maven repository and temporary ComposeDoc build tools
were copied, verified per file and moved to D with compatibility junctions. The old C Gradle
folder was moved intact to `D:\DevSetup\migrated-from-C-20261020\gradle-original`, separately
from the existing active `D:\DevSetup\.gradle` to avoid overwriting either cache/configuration.
A separate D verification copy is retained. Exact disk/file results are in
`tests/local-evidence/android/integration-20261020/c-cleanup-results.json`.
Signing keys, user documents, normal AVDs, recovery stores and unrelated temporary files survive.

The expanded production adapter suite passed **26/26** on the owned API37 emulator after the
D-drive rebuild. Evidence: `tests/local-evidence/android/adapter-D-first-20261020/`.
Installed APK SHA256: `75fd64fa1d11ee137c804af81426aa65bb3131b1a8d52d81136a8cbae949d05b`.
This is instrumented callback/mounted layout/persistence/lifecycle evidence, not genuine
soft-keyboard or TalkBack evidence. The owned emulator then exited0; computer control remains off.
Shared JVM native-input and layout tests completed successfully in the same build command;
that command still failed formatting/Detekt. Raw log: `android-D-build-quality-20261020.log`.
Public API dumps contain additions only; no existing signatures removed. Subsequent quality
refactoring is in progress and needs recompilation/retesting before any final claim. The
independent binary consumer/Android packaging, real keyboard walkthrough, desktop rasters,
full integration and clean-commit CI remain pending. No commit/push/publication performed.

C cleanup finished: 5.288GiB/52,556 files moved, all bytes verified, about6.5GiB free on C at
the receipt. Gradle verification used extended Windows paths and strict file-set equality
(50,026 files), after a short-path scan missed six long names. Both D copies remain intact.
No native control was used. The previously expanded26-group adapter result remains valid for
its identified APK; later cleanup/refactors still need final recompilation and native retest.

## Final-input checkpoint

The long-press drag regression failed against the initial production handler:25/26 groups passed,
with selection stuck at `(0,2)` after dragging to offset5. Android GestureDetector deliberately
stops delivering onScroll after long press. Selection MOVE events now dispatch directly through
the same owner, while ordinary scroll remains separate. The unchanged26-group suite passes on
the repaired APK: `tests/local-evidence/android/longpress-after-20261020/results.json`. The before
receipt remains at `longpress-before-20261020`; no assertion was weakened.

A shared session-close regression also failed before the fix: a reentrant diagnostic callback
could commit during retirement. The connection is now fenced before abort/publication/trace.
All15 common protocol tests pass on JVM and JS. The exact before/after logs are
`tests/local-evidence/desktop/android-retirement-regression-before-20261020.log` and
`android-retirement-regression-after-20261020.log`. These are synthetic traces, not native IME proof.

The final isolated packaging run is `tests/local-evidence/consumer/D-final-input-20261020`. Its
desktop installed launcher already passes8 independent expected-state groups outside the checkout;
Android binary assembly and real interaction remain in progress at this entry.

## Connection activation regression and resumed checkpoint

The interrupted full quality build completed PASS400 tasks in1m49s:
`tests/local-evidence/desktop/android-closed-reopen-final-quality.log`.
A newly confirmed native interaction defect was retained as a focused failing witness:
a framework-closed InputConnection could leave Keyboard targeting a closed session. Explicit
showKeyboard now requests a fresh connection when the current one is inactive; it never revives
the old connection. The focused test timed out before and passes after, preserving exact text,
history and stale-callback rejection. Raw before/after receipts are
`tests/local-evidence/android/integration-20261020/closed-focused-{before,after}.json`.
The complete26-group instrumented suite passes at `android/closed-reopen-after-20261020`.

The earlier diagnostic native session tapped actual Gboard H/e and Enter and observed committed
text, exercised Select All/Bold/Undo/Redo, RTL/Center, Save, independent second owner A and third
reopened copy. Read-only debug snapshots and committed .astra files are in
`android/integration-20261020/native-diagnostic`. These are diagnostic-build observations, not
final-build qualification. Rotation preserved all three owners/history by snapshot; Windows
capture failed because the emulator crop moved outside the monitor, so visual rotation is BLOCKED.
A transient Gboard candidate-only attempt is retained as an unsuccessful observation; no genuine
candidate composition qualification is claimed. TalkBack is installed but not enabled; no
accessibility-service permission was automated. Urdu/Arabic keyboard layouts are not qualified.

Fresh final binary packaging and complete pages/output regressions are running. Computer control
is off while independent work continues. Do not reuse the earlier APK as final-build evidence.

## Live consumer rendering and Gradle9 export repairs

The real standalone windows exposed a blank captured document canvas using ComposePanel's
implicit renderer. A new mounted check originally counted black pixels from an uninitialized
BufferedImage; that false oracle was corrected to a white background before use. The corrected
regression fails on the prior binary: `consumer/render-regression-before-white-20261020/1.log`,
"Mounted document canvas has no visible text ink". The public DesktopEditor now explicitly uses
RenderSettings.SwingGraphics, matching the already qualified workspace host. The renderer
selection changes neither document geometry nor numbering. Final mounted/native retest pending.
The earlier inadequate check remains preserved and is not a passing render witness.

Gradle9 rejects the root task resolving the harness runtimeClasspath without project ownership.
The same runtime export now executes in the harness project; the root command delegates to it.
Its first moved-script compile hit the Java-plugin `java` name shadowing the package; an explicit
ZipFile import fixes that. Successful real export:
`desktop/gradle9-export-after-20261020b.log`. The initial raw failure is
`desktop/batch-20261020-222355-25612/build.log`. No unsafe-resolution flag or gate relaxation.

Independent diagnostic file assertions pass in `android/integration-20261020/native-diagnostic/assertions.json`:
manual binary records/digest verify exact text, changed inline flags, full Undo/Redo bytes,
RTL/Center properties and saved/reopened bytes. The initial assertion incorrectly treated hard
separators and already-bold inserted text as newly toggled inline text; the observed discrepancy
was investigated against the structural contracts before constructing the explicit expectations.
These diagnostic-build observations remain separate from final native qualification.

## Final packaged and regression checkpoint

- **PASS** `desktop/android-consumer-render-quality-after.log`:371 tasks,2m45s,
  full check/apiCheck, zero Detekt and formatting after naming render-oracle constants.
- **PASS** `desktop/batch-20261020-223514-2088/results.json`:21 relevant integrated stages,
  including18 pages/output groups,432 marker memberships,13 workspace process interruptions,
  nine recovery interruptions and three structured interruptions. `approved-raster-comparison.json`
  proves all ten approved specimen PNGs byte-identical.
- **PASS** `consumer/D-delivery-20261020/results.json`: independent packaged JVM application,
  its mounted render regression and8 expected-state groups, plus separate Android binary build.
  Artifacts, transitive dependency coordinates/files, source hashes and installed launcher are recorded.
  Source remained unchanged throughout this final run. Earlier D-render run is FAIL because its
  source-identity guard correctly detected the concurrent naming-only regression-test correction;
  its per-step successes are not a final package receipt.
- **PASS** `android/packaged-delivery-20261020/results.json`:26 instrumented groups on API37,
  final independently packaged APK SHA256
  `7778e1e99a0f9913f8d8193140f0531938d30a837032c2bd405c13db8a442613`.

### Final native independent consumer

Actual final installed launcher ran outside the checkout, from its independent distribution.
Native click/text injection and Ctrl+Shift+Home selected text; actual Bold/Undo/Redo buttons
changed/restored appearance, Save copy succeeded and Open saved copy created a fresh owner.
The second window retained its independent text. Text injection is not OS composing-IME proof.
Exact saved text is `کتاب العربية English 42` + hard paragraph break + `Native café 42`;
all text is bold and the separator remains structural. Independent fixed-record parsing/digest:
`consumer/D-delivery-20261020/native/file-assertions.json`. The original/reopened/second windows
were closed using their actual close shortcuts. Only owned disposable data/windows were used.

| Native observation | Screenshot |
|---|---|
| Prior blank canvas | [Before](assets/android-consumer-20261020/consumer-before-blank.png) |
| Repaired same fixture | [After](assets/android-consumer-20261020/consumer-after-render.png) |
| Saved/reopened text and formatting | [Reopened](assets/android-consumer-20261020/consumer-saved-reopened.png) |
| Independent second owner | [Second editor](assets/android-consumer-20261020/consumer-independent-second.png) |

Final Android native walkthrough is pending. The emulator crop remained outside the captured
monitor on first final attempt, so no app clicks were sent; control was reset. Restarting only
the owned emulator with process-local QT_SCALE_FACTOR0.6 attempts to fit its host window.
Android density/font scale, normal AVDs and global Windows/input settings remain unchanged.

## Live final-attempt blocker reproduced in production

The centered final native attempt on APK7778e1e… again showed Gboard candidates without owner
insertion. Metadata diagnostics prove callbacks were reaching connection2 after Android created
an unused connection3. This is an application defect, not missing IME access. The first final
native keyboard attempt is FAIL, not PASS; final consumer/adapter receipts above identify the
prior binary and remain valid only for that binary.

AOSP API37 InputMethodManager calls createInputConnection(view) before revalidating whether its
start-input operation is still needed. It can discard that factory result and continue serving
the previous connection. Eagerly retiring the previous owner during onCreateInputConnection
therefore invalidated a connection Android still used. The focused production regression
fails before: `android/integration-20261020/speculative-before.json`, "Unused factory call
retired the served connection". Actual native diagnostic state is at
`android/integration-20261020/native-diagnostic/callbackDiagH/state.json`.

The repair adopts a replacement on its first actual callback, with a monotonically ordered
connection ticket and a separate lifecycle fence. Merely creating/closing an unused probe
preserves the served composition. An admitted newer connection retires the old client; closed,
older and previous-lifecycle callbacks remain rejected. No Editable or second owner is added.
The instrumentation helper now performs a real initial query to adopt a connection; a separate
adversarial test deliberately leaves a factory result unused. No assertion treats factory
creation as proof of adoption. Final repaired regression/native retests are in progress.

## Caret report and connection-adoption focused results

Owner reported the non-blinking insertion caret. Android Canvas always drew it. A focus-aware
Compose effect now resets visibility on input/selection/focus, toggles every500ms only for a
focused collapsed selection and cancels on blur/disposal. Desktop's established blink is unchanged.
Actual window PixelCopy regression fails before with20/20 blue samples and passes after with
both phases, blur hiding and unchanged document/selection/history. Raw receipts:
`android/integration-20261020/caret-before.json` and `caret-after.json`. Instrumented, not native AT.
The adapter suite now has27 meaningful groups; evidence validation demands exactly27 unique passes.

The connection-probe focused regression passes after the adoption repair:
`android/integration-20261020/speculative-after.json`. The first complete repaired run passed25/26;
its only failure was docked-IME positive-inset waiting while Gboard was floating. Preserve
`android/speculative-after-20261020` as FAIL. Docked keyboard reveal still needs its actual
configuration; do not replace the expected inset or classify a floating keyboard as a docked pass.
The test helper's initial query represents actual adoption; an unused production factory result
is tested independently. Active framework close now retains its proposal before aborting and
fences stale/reentrant callbacks before the app receives that memory-only record.

Owner centered the emulator and offered to accept TalkBack's permission manually. Initial capture
now works. Computer control is off while the fresh full quality/testAPK build runs. Final package,
27-group/native keyboard and TalkBack qualification remain pending after these Android-only fixes.

The owner manually enabled TalkBack and accepted its permission on the disposable emulator;
read-only settings confirm TalkBackService active and accessibility_enabled1. This proves service
activation only. Actual reader behavior still needs a recorded live interaction on the repaired
identified APK. Computer control remains off during final formatting/package/quality builds.
Focused production/module static checks pass at `desktop/android-adoption-blink-focused-quality.log`.

## Final native APK and exact evidence

Final independently built APK (same authoritative library binaries):
`tests/local-evidence/consumer/D-adoption-blink-20261020/results.json`.
External project `D:\Temp\composedoc-20261020\composedoc-binary-consumer-b8cuyt_y`.
APK SHA256 `1dd29f850699e4ef903e95ec6e17b2647f7e6f14ece8dac13a0dd5ec5f885a77`.
Named emulator `composedoc_slice_20261020`, serial5580, API37 Android17, x86_64,
density420/fontScale1. Gboard was the installed active keyboard. No physical device was used;
a newly visible connected physical device was explicitly excluded from every adb command.

**PASS actual Gboard composition**: tapped H, e and Space. Owner state shows provisional
H→He while accepted132 UTF-16 units and history1 remain unchanged, then exact accepted `He `
with135 units. Canonical .astra bytes during H and He are identical; no candidate leaked into
ordinary save. The live callback trace includes unused factory probes while the served connection
continues working. Exact checks:
`android/native-adoption-blink-20261020/real-ime-assertions.json`.

**PASS actual controls**: Undo space, Undo accepted He, Redo He, Redo space; each state/payload
verified. Select All, Bold, RTL and Center each added one transaction, preserving all135 raw units.
Save and Open latest saved created an independent third owner. Second owner accepted A/space
without changing the others. Forced termination was scoped only to the test package; after fresh
launch, actual Open latest saved restored the entire formatted payload byte-for-byte and history0.
Unsaved second-owner A/space did not survive, as documented. Exact independently parsed flags,
paragraph bits, checksum and full-file assertions:
`android/native-adoption-blink-20261020/native-workflow-assertions.json`.
This is completed explicit-save durability through process termination, not automatic Android crash recovery.

| Final native view | Evidence |
|---|---|
| Genuine Gboard provisional He | [Preedit](assets/android-consumer-20261020/android-real-gboard-provisional.png) |
| Genuine Gboard committed He | [Commit](assets/android-consumer-20261020/android-real-gboard-committed.png) |
| Formatted separate reopened owner | [Reopened](assets/android-consumer-20261020/android-final-formatted-reopened.png) |
| Same save reopened after process termination | [Fresh process](assets/android-consumer-20261020/android-after-process-death-reopen.png) |
| Actual TalkBack editor/button focus | [Editor](assets/android-consumer-20261020/android-talkback-editor.png), [Button](assets/android-consumer-20261020/android-talkback-button.png) |

TalkBack was enabled/disabled by the owner. Green accessibility focus and actual keyboard
interaction are observed. Speech was not heard/qualified: the initial emulator used no-audio;
a restart enabled speaker output, but the SDK stream capture produced0 frames/44-byte WAV.
No microphone or unrelated system audio was captured. This remains an honest NOT RUN speech
gate, not a claim that emitted events prove reading quality.

The final27-group instrumented run is retained as FAIL:
`android/packaged-adoption-blink-final-20261020/results.json`.26 groups pass, including the
new PixelCopy blink/blur check and adversarial factory-probe trace. Docked hide/show reveal times
out on positive IME inset. Read-only setting shows show_ime_with_hard_keyboard0 despite the AVD
having a hardware keyboard. Owner was asked to enable its virtual keyboard preference on this
isolated AVD before rerunning; assertions/thresholds remain unchanged. Computer control off meanwhile.

## Capability matrix and remaining qualification

| Workload | Model/common | Android instrumented | Actual keyboard/UI |
|---|---|---|---|
| Urdu/Nastaliq, Arabic joining/diacritics, joiners | Exact UTF-16/attributed history | Queries, shaping and resources PASS | Rendered fixture/format/save observed; Urdu/Arabic physical layout typing NOT RUN |
| English plus RTL/numbers/URLs | Exact state/history PASS | Geometry/selection/input PASS | Gboard H/e/space preedit/commit and toolbar/history/save PASS |
| Emoji ZWJ/skin tones, combining marks, isolates | Raw/native vs grapheme policy PASS | Exact queries/history/shaping PASS | Dedicated genuine keyboard cases NOT RUN |
| Japanese candidate text | Synthetic protocol PASS | Synthetic callbacks PASS | Japanese IME not installed/qualified |
| Rotation/recreation, background/foreground | Ownership policy explicit | Mounted recreate/background PASS | Diagnostic rotation retained state; final visual rotation unqualified |
| Process death | Explicit-save contract | AtomicFile/error/fallback PASS | Final saved formatted payload reopen PASS; unsaved content/history intentionally lost |
| Accessibility | Read-only/edit actions | Node/actions/geometry PASS | TalkBack focus observed; spoken text/navigation quality NOT RUN |

The Android surface is a bounded continuous text slice: no semantic heading/list/image/page
rendering, advanced bidi caret qualification, linguistic selection, full accessibility, print
fidelity or universal platform/language claim. Unsupported rich surface content is refused;
the shared codec preserves it. Desktop Narrator, genuine desktop composition, advanced printer
settings, physical printing and semantic PDF remain separate earlier open gates.

## Ten-minute developer walkthrough

1. Build both external examples with `python tooling/verification/consumer_check.py --android --output-dir <new-directory>`.
2. Launch the installed JVM `launchCommand` in its results.json from any unrelated working directory.
3. In first window type/select, Bold, Undo/Redo, Save copy and Open saved copy; inspect independent second window.
4. Install the receipt's Android APK on a disposable emulator and launch `.MainActivity`.
5. Tap the editor/Keyboard, type via Gboard, inspect preedit then Space commit; Undo/Redo.
6. Select text, Bold/Italic and direction/alignment; scroll and inspect caret above a docked keyboard.
7. Switch Next editor; type independently. Save first owner and Open latest saved as a new owner.
8. Recreate/rotate; accepted owner/history survive in-process, native connections retire.
9. Terminate only the sample process, relaunch and Open latest saved; only completed explicit saves survive.
10. For TalkBack, owner grants its service permission; separately qualify announcements, focus, selection and actions.

Exact build/rebuild commands, public API examples, fonts/IO ports and lifetime policy:
[Binary consumer and Android guide](../guides/BINARY-CONSUMER-ANDROID.md).

## Final Android adapter result and cleanup

**PASS27/27** `tests/local-evidence/android/packaged-adoption-blink-docked-20261020/results.json`,
APK `1dd29f850699e4ef903e95ec6e17b2647f7e6f14ece8dac13a0dd5ec5f885a77`,
testAPK `19b1fd4657abafaa3c3fa4e4b22df3039585716e8567735ef47f747e135ee7ff`.
Includes all existing protocol/resource/persistence/lifecycle/touch assertions plus rendered caret
on/off/blur pixels. The unused-factory regression and active-close retained-proposal/reentrant
fence also execute. No skip or threshold relaxation. Exact font assets/manifests/OFL match.

Correction to the earlier setup hypothesis: the AVD already had hw.keyboard=no. Android37's
physical-keyboard UI does not expose the assumed toggle, and its action opened keyboard
accessibility. No owner setting was changed there. The headless rerun used the isolated secure
preference show_ime_with_hard_keyboard1; docked positive-inset/reveal then passed. CI records this
precondition on its newly created AVD. Prior failed runs remain FAIL. The preference was restored
to its original0 and verified; accessibility_enabled0 after the owner's TalkBack disable.
No normal AVD/physical device/global input defaults changed. Owned emulator exited0 and computer
control is off. Do not infer all keyboard configurations from this one passing setup.

Latest full check/apiCheck **PASS353 tasks,2m45s**:
`tests/local-evidence/desktop/android-adoption-blink-final-quality.log`.
Final packaged source hashes still match; existing API dump entries are all preserved, additions
only. JVM distribution52 files exactly match the native-tested desktop distribution, permitting
exact binary reuse of that walkthrough. Android-only corrections require no desktop raster change.
Final clean-commit gates and exact remote CI status remain pending until the final receipt says PASS.

## Clean-commit gate correction (before push)

Local implementation commit b9d2322 passed five clean-commit gates: docs/frozen hashes,
V6 witnesses, full Gradle/API, verification contracts and complete workspace integration.
The sixth gate did not execute its intended mutant assertion: its temporary clone omitted
the newly configured Android/consumer projects and failed compilation. It remains FAIL;
no unrelated compilation error is counted as a mutation kill. The final-gates wrapper/receipt
are retained at `desktop/android-consumer-clean-final-gates.log` and the copied delivery receipt.

The mutant clone now includes both sample project trees alongside the existing core/workspace/
harness; no editor implementation or gate expectation changes. The final runner now retains
mutation evidence under ignored tests/local-evidence/desktop instead of deleting its temporary
clone even on failure. A fresh build and actual four-mutant preflight are required, followed by
all six gates on a new clean exact commit. Earlier18 pages/output groups and all10 approved
rasters additionally passed against the b9d2322 exact build at
`desktop/android-consumer-final-pages-20261020/results.json`.

The actual corrected mutation preflight **PASS4/4 intended assertion kills**:
`tests/local-evidence/desktop/android-mutant-copies-after-20261020/mutation-results.json`.
Every mutant compiled successfully before its test; substitute-keheh, undo-leaves-bold,
mangle-lone-surrogate and white-probe-raster each failed the required discriminating assertion.
No compile error is accepted as a kill. New clean-commit six-gate run follows this tooling correction.
The first preflight identity guard also refused a build while its runner source changed;
that non-current build was discarded as evidence and a fresh stable preflight was used.

## Hosted CI bootstrap correction

All six final gates PASS on27410fa; authorized linear push completed. Hosted CI's Android
job then failed exit127 before compiling: sdkmanager and adb are absent from the Ubuntu26
runner PATH. Raw completed-job log is preserved at
`desktop/android-ci-sdk-path-failure-raw.log`. This is a CI setup failure, not a native pass.

CI now bootstraps Android SDK tools explicitly with SHA-pinned android-actions/setup-android
v4.0.4 (`be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd`), verified from its actual action metadata.
It installs the same required platform37/build-tools36; the emulator job requests its emulator
package explicitly. Windows/quality/Apple/Java-CodeQL use the same independent SDK bootstrap,
so no job relies on a runner image's PATH. This is build tooling on disposable hosted runners;
no library runtime pins, app code, API, fonts, desktop geometry or local OS setup changes.
New clean-commit gates and exact CI must pass before claiming remote completion.

The first SDK-bootstrap CI correctly found sdkmanager, then exposed a package-ID error:
`platforms;android-37` does not exist. The locally validated platform is
`platforms;android-37.0` revision2 (ApiLevel37.0, PreviewSdkInt0); AGP's compileSdk37
resolves that package. Google package-list output confirms37.0/37.1/37.2 IDs. Workflows now
request37.0 explicitly; app/runtime configuration remains unchanged. Raw hosted logs
`desktop/ci-bootstrap-*.log` and read-only SDK package listing
`desktop/android-sdk-available-channels.log` preserve the evidence. Obsolete runs are cancelled
where still active; final gates/CI must repeat on the corrected exact commit.


## Hosted emulator startup correction — 21 October 2026

Exact clean29299b0 passes all six local gates, Windows full integration/consumer verification,
quality, docs, iOS and all four CodeQL. Android's independent Linux consumer builds, but the
manual emulator step hangs at unbounded adb wait-for-device before its bounded boot loop.
The emulator's redirected log was never printed; its exit cause is unknown. That is BLOCKED,
not a failed application test or native PASS. The obsolete run39481249183 was cancelled after
15 minutes and its raw job log retained at desktop/android-ci-emulator-stall.log.

Use SHA-pinned established android-emulator-runner v2.38.0 instead of maintaining a second
startup/wait implementation. Actual action source inspected: string platform37.0/image37.1,
300-second boot deadline, startup output in job log, targeted emulator cleanup. The disposable
Pixel7 AVD has explicit2GiB RAM/1GiB data partition and software GPU, with hw.keyboard=no and
visible virtual keyboard. Upload raw consumer/adapter evidence even on failure. This is hosted
build tooling only; no library runtime dependency or APK/source geometry changes. A successful
boot and all27 actual adapter checks are still required on the new exact commit.


The bounded4888ead run exposed the exact cause previously hidden: hosted Linux emulator
37.2.12 exits immediately with unknown option: -no-netsim. Windows37.1.11 accepted that local
workaround; it is not portable. Remove only that option from hosted startup. Preserve the raw
failed log desktop/android-ci-emulator-bounded-failure.log and uploaded consumer receipt.
All other hosted checks and all six local gates passed4888ead; Android tests did not execute.
No APK, source API, engine semantics, font bytes or approved desktop geometry changed.


Linux c8b64b5 now boots successfully in45 seconds, proving the option correction. The next
failure is before installation: package service Broken pipe(32) immediately after boot flag1.
Raw log desktop/android-ci-linux-option-result.log and hosted-c8b64b5 artifacts retain it.
The driver now requires three consecutive responsive package/activity queries within120 seconds,
with fixed-trace regressions for false-success output, service restart and bounded failure.
Installation and application assertions are never retried or weakened. Capture guest logcat,
/data space and memory on failure, only after the supplied disposable AVD identity is validated.
CI uses4GiB guest RAM/2GiB disposable data as startup headroom; the previous failure does not
prove a memory or disk cause. Local native2GiB evidence is unchanged and separately identified.


The5bf41eb hosted run passes service-readiness after6 probes and installs both APKs, but user0
is still locked. Guest logcat explicitly records User0 not ready and direct boot locked state;
the instrumentation cannot create its credential-encrypted files directory (onStart line44),
so zero checks execute. The strict driver correctly refuses missing results despite am instrument
returning code0. Raw guest logs/space/memory and all outputs are retained in android/hosted-5bf41eb.
/data has5GiB available, so this is not evidence of storage exhaustion.

Extend the same bounded readiness gate to require current user0 RUNNING_UNLOCKED via
cmd activity get-started-user-state0. Actual AOSP ActivityManagerShellCommand and service sources
confirm this read-only command and state string. A new fixed trace proves that responsive
package/activity services with a locked user never start installation. Nine driver self-tests
pass. No app assertions or installation retries, APK/source changes, credentials or permissions.


The f759064 hosted attempt fails before the qualification driver: emulator-runner v2.38.0
changes animation settings immediately after boot flag1, and settings IPC returns Broken pipe.
Retained raw log desktop/android-ci-user-unlocked.log. No adapter checks execute.

Use direct SDK emulator startup with the now-validated Linux options, followed by the same
owned-identity/service/user-unlocked preflight with a300-second cap. Only then set the disposable
IME visibility preference and run the full driver. This avoids framework calls inside an action
before readiness. adb starts before the emulator; early emulator exit is detected; always retain
emulator.log, preflight and qualification outputs and stop only emulator5580. The new wait-only
mode explicitly emits READY and no child checks, never PASS. A tenth driver self-test rejects
READY as qualification even if a misleading complete group array is supplied. Application
assertions, install behavior, APK and all125 application source inputs remain unchanged.


Direct startup also needs bounded ADB identity registration before querying services. Both
phases share one deadline; a mismatched AVD rejects immediately and cannot receive diagnostics
or app mutations. Three fixed-trace self-tests cover delayed registration, a wrong identity and
an absent device; total driver self-tests13. The AVD directory is explicit under RUNNER_TEMP,
and nohup keeps only the owned emulator alive between CI steps. No device wipe or normal AVD.


## Hosted first-boot memory pressure

The7c91e13 run completed all27 groups. The code-identical6e1ff31 run passed preflight but the
Android low-memory killer terminated the instrumented process before checks completed:
111220KiB RSS, oom_score_adj700, min watermark breached. Raw guest log/outputs are retained at
android/hosted-6e1ff31; its missing result is correctly FAIL despite instrumentation exit0.
This is not a proven application heap failure or a27-check pass. The report remains incomplete
until the final exact-commit hosted job executes all27 groups.

Give the newly provisioned CI guest6GiB RAM and disable unused guest Wi-Fi/mobile data after
owned/unlocked readiness. This is an offline editor test on a disposable AVD; host dependency
resolution is unaffected. Retain pre-test memory alongside any failure diagnostics. Do not
adjust process OOM scores, skip checks, retry assertions or weaken the driver. Native2GiB
local evidence stays separately qualified; the CI fixture is not a minimum-device requirement.
No application source, API, font or approved geometry changes.
