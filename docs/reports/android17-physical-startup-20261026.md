# Android 17 physical-device startup investigation — 26 October 2026

Status: physical-device instrumented validation passed. The hosted 16 KB emulator
startup failure remains unresolved. No PR merge, CI exemption or platform-wide
qualification is implied.

## Device and binaries

The owner authorized use of a USB-connected Pixel 7 running Android 17/API 37,
build `google/panther/panther:17/CP3A.261005.002.A1/16269273:user/release-keys`.
Its page size is 4096 bytes. This differs from the hosted Android 37.1 x86_64
16 KB image, including architecture, build, page size and boot workload; this
comparison cannot identify which difference causes the hosted failure.

The app and peer binaries were reused from the previously retained API 28
supplement, without rebuilding the library or application:

- App SHA-256: `0149442040d4b55f183a01aa86762a07e1c4f39b36b6bad902d32745f30fd099`.
- Peer SHA-256: `4dfb9c4d1b5cb035f6846d5b2fbd5e0f938da5c8dfc5579a691dde9ac1318f86`.
- Final test APK SHA-256: `37f9813bf47ce098c06c821c98d56f5d318d058b1a62835f80870e41c26f957c`.

These are diagnostic-branch binaries based on `d4a58a3b18b6263707c54ce99a9de7c58f024a2a`,
not the binary bytes from the failed hosted PR35 run. Only the test APK was
rebuilt for the raster-capture correction below.

## Observations and correction

The initial ordinary cold instrumentation start reached `onCreate`, mounted the
editor, and completed all 66 groups. There was no startup crash: 65 groups passed;
the marker raster check failed with missing black marker ink. The retained display
screenshot showed the unformatted fixture while the test expected the list frame.

A targeted test-only change waiting for geometry matching the current document
revision still failed the marker check. Waiting for a committed frame does not
guarantee that frame has been presented to the display. Android documents this
distinction and recommends combining the frame callback with
[PixelCopy](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)).

The test now waits for current geometry and frame commitment, then copies the
actual application window buffer with public `PixelCopy` on API 26 and later.
Screen-space reference bounds are translated by the window's recorded screen
origin. API 24–25 retain the existing display capture path. The font matrix,
independent inline reference, black/blue pixel classification and two-pixel
alignment limit are unchanged. There is one capture per case; no pixel-based
retry or delay-until-pass was introduced. Window-buffer evidence is explicitly
identified in the matrix and is not a claim about display presentation timing.

The targeted run passed all 32 marker combinations and all three layout groups.
The subsequent full run passed 66 distinct groups, both actual process-kill and
fresh-process recovery witnesses, and the separate cross-UID clipboard witness.
The startup kill did not occur in these physical-device runs. Native human IME,
TalkBack and all-device qualification remain outside this evidence.

## Validation and boundaries

- External consumer test APK compilation passed; app/library binaries unchanged.
- `gradlew.bat check apiCheck` passed: 354 tasks, 30 executed.
- Documentation integrity passed, including all 81 protected historical/spec files.
- The original failed physical runs and the hosted failures remain preserved.
- No device system settings, root permissions or memory limits were changed.
- All three newly installed test packages were hash-verified and uninstalled;
  the unique tap script was removed by the existing ownership guard.

The latest retained PR35 hosted run, `40297881391`, reported the low-memory killer
terminating process 3362 at adjustment 700 because the minimum watermark remained
breached. Other system processes were killed in the same interval. The phone
results rule out an unconditional Android 17 startup failure for these binaries;
they do not establish the emulator's underlying reclaim/allocation cause or fix
its failed required check. No production startup workaround is justified here.

Machine-local evidence is under `tests/local-evidence/landing-20261024/`:
`physical-api37-final.json`, `physical-api37-cleanup.json`, the `physical-api37-*`,
`physical-revision-fence-*`, `physical-pixelcopy-*`, `physical-final-full-*` and
`physical-clipboard-*` directories, and `pr35-ci-40297881391/`.
Final full-run receipt SHA-256:
`32c0ae81c1372da7bff58cc328085c358073a830f031429494845b17d4315673`.
Clipboard receipt SHA-256:
`8a25b8b76094ac4403e126830d0b46346b5513978f0c749481d505480f3ee10d`.
