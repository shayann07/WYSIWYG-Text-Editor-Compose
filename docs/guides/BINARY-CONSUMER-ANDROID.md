# Binary consumer and Android integration

20 October 2026. Implemented and locally verified to the bounded slice; exact gate results are in
[the dated report](../reports/android-consumer-integration-20261020.md). Final exact-commit outcomes are in `tests/local-evidence/android/integration-delivery-20261020.json`.
These examples do not qualify full Android rich-document parity, native IMEs or accessibility by themselves.

## Independent binary application

From the checkout with JDK21, Python3.12 and the installed Android SDK:

```powershell
python tooling/verification/consumer_check.py --android --output-dir tests/local-evidence/consumer/new-run
```

Use a new output directory. The runner creates a separate temporary project, its own settings
and wrapper, and a new file-based Maven repository. It publishes verification snapshots to that
isolated repository only, then builds by module coordinates. It copies sample application code,
never library implementation. The result receipt identifies the external project, artifacts,
dependencies, the installed launcher and runtime evidence. The runtime check launches the
installed distribution directly from an unrelated working directory, without Gradle or the
checkout on its classpath. Nothing is installed into normal Maven local or published
remotely. Set `TEMP`/`TMP` to a suitable scratch drive before running if required.

To open the resulting example, run `launchCommand` from its `results.json`; it needs JDK21.
To rebuild instead, use the independent directory and repository reported there:

```powershell
.\gradlew.bat -PconsumerRepository=<isolated-repository> run
```

The two windows have independent owners. Select text, use Bold and Undo/Redo, type, Save copy,
and Open saved copy. Every save uses a new private temporary directory. Reopening creates a
separate owner. A window refuses close while unfinished input needs attention. These examples
are disposable and do not provide the desktop workspace's automatic crash-recovery service.

The public desktop integration is `DesktopEditor(model, typography, platformServices)` on the
Swing event thread. Its native input host and Compose renderer share that one `EditorViewModel`.
Use typed `EditorIntent` dispatch and published read-only state. `DesktopEditor.onFrame` provides
a host callback; avoid replacing its input host's publication callback. `close()` retires the
host, refuses unresolved input and leaves the model to the caller to detach or recreate.
`verificationEngine` is a compatibility escape hatch, prohibited in applications and examples.

## Android sample

```powershell
.\gradlew.bat :apps:android-sample:assembleDebug :apps:android-sample:assembleDebugAndroidTest
```

The root sample uses project dependencies for development. The independent runner builds the
same sample chrome against AAR/module metadata outside the checkout; that is the packaging
proof. AGP9.1.1/Gradle9.3.1/compileSDK37 are required by the existing pinned Compose stack.
They do not change Kotlin2.4.21, Compose1.12.2, Skiko0.150.2 or Lifecycle2.11.0 runtime pins.
Use an installed SDK. An optional `-PcomposedocDebugKeystore=<private-debug-keystore>` preserves
test signing across environments. Never commit a signing key or overwrite the owner's keys.

The surface is `composeDocEditor(model, typography, modifier, onInputView, onUnfinishedInput)`.
Create the model on the Android main thread, retain it across recomposition, load
`AndroidTypography.load(context)` on an IO worker and supply the verified profile. Fonts and
OFL notices are library assets; no repository path or system font installation is needed.
Missing/corrupt faces disclose fallback and retain stored font choices. Invalid manifest
identity fails visibly. Platform rasterization is qualified separately from desktop references.

The Android slice supports continuous body text, hard/soft breaks, inline formatting and
paragraph direction/alignment. It explicitly refuses documents with images, semantic headings,
lists, explicit line spacing or running content rather than flattening them. This is an Android
surface limitation; the shared model and codec preserve these structures.

The focused insertion caret blinks every500ms, resets after editing/selection changes and
hides on blur; it does not add history or change content. Tap for a caret/keyboard,
long-press for a whitespace-delimited selection, drag to extend it,
and use the floating copy/cut/paste menu. This selection heuristic is not linguistic word
segmentation. The sample toolbar supplies formatting, direction/alignment, attributed Undo/Redo,
two independent editors, Save and Open latest saved. Opening creates a separate unsaved copy.
Saving captures committed content through `DocumentRepository` on a bounded worker. Provisional
IME input is not accepted by Save. `AndroidDocumentRepository` uses app-private AtomicFile
storage; saved document identities and bytes survive later source-file changes.

## Public API integration sketch

Create and dispatch on the platform owner thread. Android loading/IO stays on an IO dispatcher:

```kotlin
val model = EditorViewModel()
model.dispatch(EditorIntent.Type("کتاب العربية English 42"))
model.dispatch(EditorIntent.Select(Selection(0, 4)))
model.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
model.dispatch(EditorIntent.Command(EditAction.Undo))
model.dispatch(EditorIntent.Command(EditAction.Redo))
val accepted = model.snapshot().committed
// Observe model.uiState; never keep another editable text/history authority.
```

For Android, load `AndroidTypography.load(context)` with `withContext(Dispatchers.IO)`, then pass
it and the retained model to `composeDocEditor`. Save the captured `accepted` document through
`DocumentRepository` on an IO worker. `AndroidDocumentRepository` implements that port for
app-private `.astra` locations; `DocumentCatalog.locations()` enumerates completed explicit saves.
The repository does not retain the ViewModel or a native connection. Reopen into a new model.
On desktop, use `DesktopEditor` inside application chrome; the independent sample demonstrates
public persistence/clipboard ports, owner disposal and independent windows. No example uses the
compatibility `verificationEngine` escape hatch.

## Native ownership and lifetime

There is no hidden Editable/text-widget history. The InputConnection reads and dispatches into
the authoritative owner through `NativeTextInputSession` and typed native intents. Android raw
UTF-16 coordinates remain exact after protocol-required clipping/reordering. Application touch
commands use the existing grapheme boundaries. Code-point deletion and UTF-16 deletion differ
deliberately. Native newline text is a soft break; explicit Enter/editor actions use structural
paragraph commands. Batch edits coalesce notifications while queries see published state.

Android may call the connection factory and discard its result. Creation alone does not retire
the served client: the first callback adopts a newer connection. Older clients then remain
permanently stale; explicit session/lifecycle retirement fences all prepared connections.
Focus/session retirement aborts provisional content and invalidates stale callbacks. Accepted
prefixes remain committed. Retained unfinished proposals are separate, memory-only records;
the sample can open a proposal as a separate unsaved copy without replaying a live session.
Formatting waits for composition settlement. Zero partial commitment changes nothing.

| Lifetime | What survives |
|---|---|
| Recomposition | Same model, accepted content and attributed history |
| Activity recreation/rotation | Retained owners/history; old native connections retire |
| Background/foreground | Committed owners remain while process survives; no live session serialization |
| Process death | Completed explicit saves only; unsaved content/history and memory-only proposals may be lost |

SavedState is not durable crash recovery. This Android example does not claim the desktop
workspace's automatic recovery service. Applications must retain/export unresolved work before
deliberately disposing its only owner. Call the native host's retirement operation before
detaching a model; the sample demonstrates Activity lifecycle cleanup.

## Instrumented checks and manual qualification

After building/installing on an explicitly owned emulator, enable its virtual-keyboard visibility
preference for the docked hide/show assertion. This is test-AVD setup, not native action evidence:
`adb -s <owned-serial> shell settings put secure show_ime_with_hard_keyboard 1`.
Record and restore its prior value afterward; never apply to a physical device or normal user AVD.
Then run:

```powershell
python tooling/verification/android_check.py --serial emulator-5580 --avd-name composedoc_slice_20261020 --apk <sample.apk> --test-apk <sample-test.apk> --output-dir <new-evidence-directory>
```

The driver refuses physical devices or a mismatched AVD name, checks exact packaged font bytes,
installs only the sample packages and checks the returned result against the installed APK hash.
A zero exit from `am instrument` alone is insufficient. Raw output and failures are preserved.
These are instrumented production callbacks, touch, layout, lifecycle and IO checks—not genuine
IME or TalkBack evidence. Evidence-contract self-tests run with:

```powershell
python -m unittest discover -s tooling/verification -p test_android_check.py
```

The real walkthrough must separately identify its emulator/API/APK and installed keyboard:
type through actual keyboard controls, select/replace mixed RTL/Latin text, format and Undo/Redo,
edit near the keyboard, switch owners, rotate/recreate, Save and reopen. Exercise genuine
candidate conversion only where an installed IME permits it. Do not install keyboards or
change global input defaults without owner permission. TalkBack and unavailable languages,
platforms/DPI, expert Urdu review and desktop PDF/printing limitations remain separate gates.


## Hosted startup versus qualification

Fresh emulators can report boot completion before package/activity services and user0 credential
encrypted storage are ready. CI starts only its disposable SDK emulator, validates the observed
AVD identity, and waits for three responsive service probes with user0 RUNNING_UNLOCKED before
changing its keyboard-visibility preference or installing anything. Device registration and
service readiness share a bounded deadline. No normal AVD or physical device is admitted.

`android_check.py --wait-only` with the normal artifact/serial/AVD arguments emits READY and
explicitly executes zero adapter checks. It is setup evidence, never qualification PASS. The
full separate invocation must validate all27 unique groups and the actual installed APK hash.
Raw startup, preflight, instrumentation and failure diagnostics are uploaded even on failure.
Thirteen driver self-tests cover setup races, wrong identities, deadline failure and misleading
results. Local manual preference changes must still record/restore their prior value; the CI
AVD is newly created and removed with its hosted job. Computer control is unnecessary for CI.


The disposable hosted fixture uses6GiB guest RAM and offline Wi-Fi/mobile data after readiness
to separate editor checks from Play Store first-boot provisioning pressure. Its pre-test memory
and failures are retained. This does not establish a minimum device requirement: the separate
local native/adapter configuration used2GiB. No OOM priority override or application assertion
retry is used. Actual device memory/keyboard/IME qualification always names its configuration.


For the headless IME/reveal lane, credential-unlocked storage alone is insufficient: CI also
keeps its test display awake, dismisses the default non-credential keyguard and explicitly
selects the image's already installed Gboard LatinIME. Before/failure dumps identify actual
IME, power and window policy. These disposable-fixture defaults do not change the owner's
normal AVD or physical-device keyboard, and are not evidence of genuine keyboard interaction.
