# Android 17 emulator startup readiness repair — 26 October 2026

Status: the corrected startup path passed on two fresh hosted Android 37.1
emulators. This supersedes the unresolved startup disposition at the earlier
[physical-device checkpoint](android17-physical-startup-20261026.md). It does not
qualify human IME/AT use or authorize merging other unverified PR heads.

## Problem and repair

The harness treated successful APK installation as readiness to start cold
instrumentation. Android installation broadcasts were still delivering work to
system and package receivers. Previous retained failures show low-memory kills
of several processes, including the new instrumentation process at adjustment 700,
before instrumentation `onCreate` ran.

For API 37 and later, the harness now invokes Android's
`am wait-for-broadcast-idle` after both APK installations and before its original
cold instrumentation command. It requires the actual final confirmation
`All broadcast queues are idle!`. A 90-second command bound, transport error,
malformed platform identity or missing confirmation fails setup. It performs no
qualification retry. Older supported API behavior is retained.

This is an installation lifecycle synchronization fix, not a memory allocation
workaround. Android's own
[CTS host tests use this command after installation](https://android.googlesource.com/platform/cts/+/e7d4912635ec1d5860f6ea7260d88c5efca77c6f/hostsidetests/appbinding/hostside/src/com/android/cts/appbinding/AppBindingHostTest.java).
The harness uses neither a fixed sleep nor a changed process priority, root
permission, guest RAM/CPU setting, warm attachment, weakened assertion or extended
instrumentation deadline. The image remains
`system-images;android-37.1;google_apis_playstore_ps16k;x86_64`, with 6144 MiB and 2 CPUs.

## Actual hosted evidence

| Candidate | Exact head | Fresh run | Results |
|---|---|---|---|
| Diagnostic candidate | `d259da996bb038a0a9553cf0b9cab5a8748ef9a1` | `40330985653` | All five CI jobs passed; 66 unique instrumented groups, both process-kill recovery witnesses and cross-UID clipboard passed. |
| Actual PR #35 repair | `058d2f8db40aa193e0753b65f43748f3be5f8543` | `40333158819` | Android passed 63 unique groups, both recovery witnesses and clipboard; docs, quality and iOS passed. The initial Windows job failed a Yarn registry HTTP 502; its isolated retry is recorded separately. |

Both runs used fingerprint
`google/sdk_gphone16k_x86_64/emu64xa16k:17/CP31.260623.012/16064790:user/dev-keys`.
The OS wait lasted 20.288 seconds on the first run and 10.215 seconds on the second.
Raw output identifies pending system, Settings, Package Installer and other
manifest receiver work before the final idle confirmation. These are actual
condition waits, not selected delay constants.

Both artifacts include the exact app/test/peer APK bytes with hashes. The
coordinator independently checked those hashes, the APK identity in the result,
unique group counts, all group statuses, both fresh-process recovery results,
and the separate clipboard receipt. PR #35's pull-request merge test SHA was
`c874c5772016049de5bd0a078af46c1a33dbf84f`, based on main `540ace73`.

The diagnostic candidate's supplementary startup sampler was BLOCKED; its
qualification command and receipts passed. That sampler is not represented as a
complete memory-pressure trace. The evidence supports fixing the premature
startup boundary; it does not establish the kernel's precise allocation/reclaim
mechanism or prove that arbitrary Android workloads can never be killed.

## Other verification and scope

- Diagnostic host suite: 70 tests passed, including malformed/empty confirmations,
  timeout, transport failure, API identity and older-API behavior.
- PR #35 host suite: 30 tests passed. `check apiCheck` plus instrumentation compilation
  passed locally, 360 tasks. Documentation and all 81 protected hashes passed.
- The separately tested committed-window raster capture correction is included;
  marker oracle and tolerances remain unchanged.
- PR #35 retains its 63 groups and recovery scope; later Outline/image/toolbar
  production changes were not imported. No core/runtime/API changes were made.
- Original failed runs are preserved. No failed check was bypassed and no PR was
  merged as part of this bounded repair. Human-authorship governance remains a
  separate owner-exception/review matter.

Machine-local raw artifacts and receipts are under
`tests/local-evidence/landing-20261024/broadcast-idle-d259-artifact/`,
`pr35-startup-058-artifact/`, `broadcast-readiness-candidate.json` and
`STARTUP-FIX-HANDOFF.md`. The preserved Windows registry failure is in
`pr35-058-windows.log`. Local launcher/profile/ADB setup attempts did not execute
qualification and are not used as evidence of either the original defect or its
repair. The coder chat and recurring coordinator remain paused.
