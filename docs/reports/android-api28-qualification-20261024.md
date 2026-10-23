# Android API28 adapter qualification — 24 October 2026

This follow-up changes sample instrumentation only. Production editor, document
semantics, dependencies, public core APIs, minSdk24 and desktop geometry remain
unchanged. API28 results qualify this tested configuration, not every API24+
platform or genuine keyboard/IME interaction.

## Reproduced failures and corrections

The first run of the independently packaged c020 runtime01494420/test0e803448
completed all66 groups:63 PASS, three FAIL. Raw results remain in the primary
checkout's ignored `tests/local-evidence/integration-20261024/lower-28-full-artifacts/`.

1. A test-only IME oracle referenced API30 WindowInsets.Type on API28. The guarded
   API30+ oracle is retained. Below30, a public-platform fullscreen/adjust-resize
   occlusion probe records the keyboard-hidden baseline, shown and hidden-again
   visible frames in screen coordinates, real display bounds, stable/system
   insets, host origin/size and soft-input mode. Stable bars alone are negative;
   positive additional bottom occlusion and caret reveal remain required. No new
   dependency is added. A rejected WindowInsetsCompat import was not available
   on the existing test classpath; its failed build is retained.
2. The recovery fault fixture assumed a nonempty target directory prevented
   AtomicFile publication. API28 instead moved it to its backup and successfully
   published. The corrected fixture obstructs both target and backup, verifies a
   real failure plus both obstacle payloads and previous recovery content, moves
   only the owned obstacles aside and verifies an exact retry. No production
   storage assertion is weakened.
3. Fixed sleeps sampled longpress selection too early. Isolated traces retained
   the original verdict: an Arabic range was0..0 at488ms and became the exact
   expected0..12 during a later diagnostic hold, without cancellation, movement,
   focus, revision or native-session changes. Subsequent delegated publication
   traces measured all12 word-fixture selections at502–514ms despite the public
   timeout reporting400ms; Latin selected0..2 at507ms. A cached framework timeout
   is a hypothesis, not a directly read value. The maintained synchronization
   waits for exact selection publication with one fixed two-second deadline,
   holding the original DOWN. It rejects owner/document/history changes, preserves
   exact range/content/formatting/history checks, delegates existing callbacks and
   releases/cancels held gestures in finally. A real window-callback negative
   suppresses delivery; no selection publishes and the deadline correctly fails.
   There is no gesture retry and no user-facing latency qualification.

The earlier hosted PR45 tap-position failure remains unexplained. The API28 tap
itself passed; these longpress findings do not establish its cause.

## Measured results

| Check | Result and scope |
|---|---|
| Original independently packaged full suite | FAIL63/66, retained with exact stacks |
| Isolated original touch/recovery checks | FAIL, retained |
| Traced fixed-sleep checks | FAIL original verdict, even when later selection arrived |
| Repaired focused groups | PASS touch4, mounted-hit1, mounted-ime1, recovery4 |
| Suppressed gesture negative | PASS: the actual suppressed event cannot satisfy the wait |
| Full repaired API28 suite | PASS all66 unique groups |
| Completed-checkpoint SIGKILL/fresh-process restore | PASS accepted structure/resources plus separate proposal |
| Discard/unchanged keep-close/SIGKILL/fresh-process restore | PASS: discarded16, durable fresh18; separate proposal retained |
| Independently packaged cross-UID clipboard/publisher SIGKILL | PASS on01494420, read-only grants/resources/new identities and revocation |
| Focused sample check/Detekt and core apiCheck | PASS after owned-process cleanup; earlier failures retained |
| New API37 comparison / final independent packaging | NOT RUN at this checkpoint |
| Real language keyboard input, TalkBack speech | NOT RUN by these instrumented checks |

Observed keyboard geometry: real display1080x2280, stable bottom bar132;
hidden visible bottom2148, shown1463 (additional685), hidden-again2148. Shown
caret screen-bottom1419 is inside the actual visible frame. Window mode16 is
adjust-resize. This is observed occlusion in this fullscreen configuration, not
a universal IME-visibility detector.

Tested API28 fingerprint:
`google/sdk_gphone_x86_64/generic_x86_64:9/PSR1.180720.122/6736742:userdebug/dev-keys`.
Owned serial5582, AVD `composedoc_integration_api28_20261024`, density440/font1.0;
installed Gboard version and default IME are in `lower-legacy-v2/environment.json`.
The launcher requested1536MiB; Android dumpsys reported total1,530,644KiB.

Diagnostic source-build APK SHA256:
`4cc55860d6c37005b714189d98cbb0b7747d99f7eab1aef470c58d758dcd2661`.
Test SHA256:
`f3b71cc88ca47515b0af6ef7e076cf42700c93f481fe053d515d9a799492c771`.
These are separate from the independently packaged01494420/0e803448 artifacts.
All170 compiled inputs are captured in `api28-tested-inputs.json` and unchanged
through the successful checks. New source identity is assigned by the commit
containing this report; local receipts retain their precommit input hashes.

## Reproduction and retained failures

Use the documented D-drive environment. The installed legacy executable needs
process-local DLL search paths; no global PATH, system settings or SDK install
is required:

```powershell
$env:PATH = 'D:/DevSetup/Android/Sdk/emulator/lib64;D:/DevSetup/Android/Sdk/emulator/lib;D:/DevSetup/Android/Sdk/emulator;' + $env:PATH
# Set ANDROID_AVD_HOME / ANDROID_USER_HOME to the isolated fixture's directories.
& D:/DevSetup/Android/Sdk/emulator/qemu/windows-x86_64/qemu-system-x86_64-headless.exe `
  -qemu-top-dir D:/DevSetup/Android/Sdk/emulator -avd composedoc_integration_api28_20261024 `
  -port 5582 -no-window -no-audio -no-snapshot -no-boot-anim `
  -gpu swiftshader_indirect -memory 1536 -feature -Vulkan
```

The exact AVD config, launcher/version/hash, image identity and cleanup exit0 are
retained with `lower_legacy_bridge.py` / `lower-legacy-v2/`. An initial unsupported
`-no-netsim` legacy launch remains in `lower-legacy/`; it was not a test PASS.

From a matching built source tree, run the maintained checker with both APKs and
the identified AVD; use an unoccupied new output directory:

```powershell
python tooling/verification/android_check.py --serial emulator-5582 `
  --avd-name composedoc_integration_api28_20261024 `
  --apk apps/android-sample/build/outputs/apk/debug/android-sample-debug.apk `
  --test-apk apps/android-sample/build/outputs/apk/androidTest/debug/android-sample-debug-androidTest.apk `
  --output-dir tests/local-evidence/android/api28-new-run
```

Full results: `lower-28-repaired-full/results.json`; after-close witness beneath
`lower-28-repaired-full/after-close-interruption/`; focused traces/geometry/negative
case in `lower-28-synchronized/`; binary clipboard in `lower-28-clipboard/`.
All are relative to the primary ignored integration evidence directory above.

A separate API37 Goldfish launch crashed before ADB readiness; raw SIGSEGV and
owned-child command lines are retained. Its requested2048MiB is not its observed
backend4096MiB. This finding applies to that launch only; historical requested
settings must not be presented as measured guest RAM.

Quality attempts retained a stale Spotless scratch-path lint failure and two
explicit native-malloc OOM crashes, including hs_err/replay files. Revalidated
idle owned Gradle/Kotlin orphan processes were stopped by exact PID after checking
creation/parent/command/build-marker/IDLE status; unrelated processes, caches and
OS/pagefile settings were untouched. A serial per-invocation1GiB/two-processor
build then passed sample check, zero Detekt and core apiCheck. The intermediate
PowerShell argument-splitting failure is retained separately; quoting the complete
project-property argument corrected the invocation without changing source.
