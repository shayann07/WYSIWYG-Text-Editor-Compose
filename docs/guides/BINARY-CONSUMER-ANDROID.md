# Binary consumer and Android integration

22 October 2026. The rich-writing implementation extends the original20 October body-text slice.
Current build, automated/native results and open gates are in
[the rich-writing report](../reports/android-rich-writing-20261021.md).
The [previous delivery report](../reports/android-consumer-integration-20261020.md) and its exact
receipt remain historical evidence. Neither example establishes universal IME/accessibility qualification.

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

Use `--android-peer` instead of `--android` to additionally build the identical Android consumer
under a second disposable application ID. Its public controls and packaged library are unchanged.
`android_clipboard.py --serial <owned-emulator> --avd-name <owned-name> --consumer-receipt <results.json>
--output-dir <new-directory>` checks actual cross-UID clipboard URI transport after publisher
process death, read-only access, fresh item identities and an independently described rich document.
This is instrumented transport, separate from the real desktop/emulator walkthrough.

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

The Android continuous surface supports inline formatting, paragraph direction/alignment/spacing,
semantic headings/outline, nested lists/item paragraphs and owned PNG/JPEG blocks. Running content,
custom page settings, explicit page breaks and pagination rules remain refused before Open/Paste
can replace existing writing. The existing v9 document and v5 rich-fragment codecs are shared;
older document readers/defaults remain available. Unsupported newer versions reject safely.

The focused insertion caret blinks every500ms, resets after editing/selection changes and
hides on blur; it does not add history or change content. Tap for a caret/keyboard,
long-press for Android ICU word selection in the resource locale, or drag either selection endpoint.
ICU results expand only to pinned grapheme boundaries; punctuation/emoji use a grapheme. This is
platform word segmentation, not all-language linguistic qualification. A word gesture hits painted
character cells; nearest-caret bidi affinity is a separate operation. Raw IME ranges remain UTF-16.
Dragging near an edge scrolls, and a stale document basis stops the drag. Focus loss stops it too.

Text, Paragraph, Structure, Clipboard and Image groups expose stored values and mixed states.
With a keyboard/short window/enlarged font, File contains document actions and Tools cycles groups.
Ordinary formatting buttons preserve selection and use the existing native settlement contract.
Dialogs/pickers refuse active candidates with an explanation. Finish/cancel input before opening
them; cancellation retains a separate proposal. No directional controls are inserted for layout.

New/Open keep prior drafts in independent tabs. Open uses ACTION_OPEN_DOCUMENT; Save As uses
ACTION_CREATE_DOCUMENT and a content URI. Save uses the bound URI and captured digest. A saved
snapshot completing after new edits leaves those edits dirty. Async operations capture owner,
basis and generation; cancellation, repeats and stale image/open results cannot target another tab.
The compatibility private-copy actions remain for integration callers, not the primary UI workflow.

AndroidUriRepository is a worker-only DocumentRepository adapter. Its inherited save and byte-export
ports create empty targets only; overwrites require write(uri, document, capturedDigest). Every route
retains intended bytes and prior provider bytes before destructive output, then verifies read-back.
Hash comparison detects conflict; it is NOT compare-and-swap. An external provider can race the last
check, truncate non-atomically, fail or stall. No universal provider/power-loss guarantee is claimed.
Read-back-inaccessible/write-only providers are refused. Use Save As for conflicts; resolve/export
captured safety files in Recovery when its conservative128MiB quota fills. No silent eviction.

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
prefixes remain committed. Retained unfinished proposals are separately checkpointed evidence;
the sample opens a proposal as a separate unsaved copy without replaying a live session.
RAM copies are released only after successful durable-file publication, or explicit captured discard.
If checkpointing fails, the proposal remains in memory and an actionable failure is shown.
Formatting waits for composition settlement. Zero partial commitment changes nothing.

| Lifetime | What survives |
|---|---|
| Recomposition | Same model, accepted content and attributed history |
| Activity recreation/rotation | Retained owners/history; old native connections retire |
| Background/foreground | Committed owners remain while process survives; no live session serialization |
| Process death | Completed explicit saves and completed private checkpoints; live history/sessions are not restored |

SavedState is not durable recovery. AndroidRecoveryStore provides private two-slot checksummed
records, separate accepted/proposal payloads and streaming metadata listing. read loads one chosen
payload. Restore creates a new unsaved owner; export/read failure retains the record. Discard resolves
only the captured owner/generation and older fallback; a new arrival survives. Admitted sequence
identities cannot be rebound to different content, including after a publication/quota failure.
The store reserves ordering metadata before writing and verifies publication; failures throw reasons.
Default quota64MiB and16 unresolved owners refuse additional work rather than evict it. Interrupted or
corrupt latest records retain valid fallbacks. A corrupt ordering ledger is retained and disclosed;
it may require manual evidence recovery rather than ordinary restoration.

The app schedules at most one batch with a latest pending snapshot per owner, initially400ms after
changes; it reports actual completion latency. IO/queue/storage delays extend that window. Edits
after the last completed checkpoint, or input whose checkpoint failed, can be lost. Force-stop,
process termination and power loss differ; process-kill tests never prove power-loss durability.
Call native retirement before detaching a model; closed owners release in-memory document caches.

## Rich transfer, search and images

Configure AndroidEditorView.fragmentCodec = FragmentCodec and inject publishRichClipboard using
AndroidClipboardFiles(context, "${applicationId}.clipboard"). Register AndroidClipboardProvider as
non-exported/grant-enabled with that authority, as the sample manifest demonstrates. Encoding,
publication and decoding run off UI; captured owner/basis/selection are checked before mutation.
The system clip has standard plain text plus a bounded read-only content URI for the rich fragment.
Wait for publication before pasting elsewhere. Malformed/unavailable rich Paste visibly falls back
to plain; unsupported rich structure requires explicit Paste plain. Failed rich Cut retains content.
Clipboard blobs survive their publisher process until explicit cache cleanup; temporary Android
grants and read-only enforcement are separate from owned document resources. Clearing this cache
does not remove already-pasted resources, history, provider files or recovery records.

Search is literal, case-sensitive Unicode equality without normalization or letter substitution.
Hard paragraphs/images are boundaries; soft breaks remain text. Interior-grapheme matches may be
highlighted but cannot be replaced. Empty search disables replacement; empty replacement deletes.
Replace All validates one captured basis, replaces non-overlapping original matches in one Undo
transaction and preserves unrelated formatting/structure. Stale/foreign presentation is rejected;
Find fields never join document history. Search, next/previous, Replace and Replace all are visible.

Image insertion reads PNG/JPEG bytes through a picker grant on a worker, validates signatures,
dimensions and existing resource budgets, then dispatches ApplyStructure with a captured target.
The document owns bytes, not a transient URI grant. Properties validate width/height/locked aspect
and alt text without dismissing bad values. Width10–600 document units is stored; display fits the
text column, preserving aspect ratio. Very tall images scroll vertically. Resizing/replacing/removing
are atomic structural/history operations. Resources remain conservatively owned for current content,
Undo/Redo, saved documents and recovery; no new resource garbage collector or remote image fetch.
Image cells expose alt-text speech spans without changing canonical UTF-16; actual AT speech remains
an independently reported gate.
Images are bounded to4MB encoded,8192 per dimension,16 million pixels per image and the existing
aggregate document resource budget. EXIF-oriented JPEG/PNG is refused; export pixel-oriented bytes
first. Invalid inputs retain the current document and original resource.

Heading roles use default sizes36/32/30/28/27/26, with explicit inline size/font taking precedence.
Natural line boxes use actual styled runs; no phantom larger default face is added. Auto retains
the existing isolate-aware direction resolution. Start/End follow resolved paragraph direction.
List markers retain stored nominal font/size, widest-sibling gutters, root-side nesting and each
child's own marker side. Number/digit/punctuation semantics are unchanged. Enter, empty-item Enter,
Backspace, split/join and item-paragraph commands reuse shared semantics; Tab retains focus navigation.
Before/after spacing uses document units. Natural line spacing keeps measured font padding; explicit
100–300% multiplies natural advance. Android's nonlinear SP conversion is used for font-scale geometry.

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
full separate invocation must validate all54 unique groups and the actual installed APK hash,
then complete a real owned-child SIGKILL/fresh-process recovery witness. Its synthetic input setup,
exact recovered bytes and separate provisional evidence do not qualify native IME or power loss.
Raw startup, preflight, instrumentation and failure diagnostics are uploaded even on failure.
Nineteen driver/API self-tests cover setup races, wrong identities, deadline failure and misleading
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
