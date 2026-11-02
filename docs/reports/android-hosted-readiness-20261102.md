# Hosted Android readiness: bounded correction and environment decision

2 November 2026. Local host regression PASS; hosted qualification remains BLOCKED.
No emulator/device run, image download, CI modification or qualification retry was
performed for this report. This supplements, rather than rewrites, earlier receipts.

## Retained comparison

| Observation | Passing PR35 `058d2f8`, run 40333158819 | Failing PR35 `c0d56ae`, run 40746007286 |
|---|---|---|
| Emulator executable | 37.2.12.0, build 16428233 | Same recorded version/build |
| Image package path | android-37.1/google_apis_playstore_ps16k/x86_64 | Same recorded path |
| Exact image package revision/archive hash | Not retained | Not retained |
| Guest fingerprint | google/sdk_gphone16k_x86_64/emu64xa16k:17/CP31.260623.012/16064790:user/dev-keys | Not retained before rejection |
| Configured resources | 6144 MiB, 2 CPUs, Pixel 7 profile | Same workflow settings; no contemporaneous complete config archive |
| Boot admission | Three consecutive package/activity/unlocked-user probes | Same three responses; not memory-service readiness |
| Memory service | Complete 23,544-byte diagnostic | 118 bytes, two explicit 10,000 ms service timeouts, host exit 0 |
| Installation barrier | Confirmed idle after 10.215 s | Not reached; no APK installed |
| Workload evidence | Installation receiver work awaited; complete pre-test memory dump | 21 system-package minimum-watermark kills in retained logcat; system-server slow delivery/GC and package service starts |
| Editor work | 63 groups, two recovery witnesses, clipboard passed on that build | Editor, recovery and clipboard NOT RUN |

Both logs have the same suggested-16-GiB-host-memory warning. It does not distinguish
success from failure. Host pressure, zone reserves/reclaim and precise service-lock
contention at the rejected command remain unknown. The later complete failure dump
is not a successful admission or permission to retry.

The earlier a92 trace independently records the editor at adjustment 700 being
killed before instrumentation onCreate. That attaching-process vulnerability and
the later pre-install service rejection are different observations. Neither proves
an editor input, document or recovery defect. Installation broadcast synchronization
has passed on earlier builds but does not guarantee completion of all boot work.

## Proven harness defect, separate from the startup cause

`wait_for_owned_emulator` and `wait_for_services` could return READY when the final
successful response arrived after their deadline. They also passed a full five-second
timeout to commands when less time remained. Two deterministic tests advance an
injected clock during actual probe calls and fail on the previous implementation.

Each probe now uses the smaller of five seconds and its remaining budget. Responses
after the deadline cannot admit work. No deadline increases, sleep additions, relaxed
memory parser or qualification retries are introduced. All 50 Android host tests
pass, including the two new regressions. This is a boundedness fix, **not a cure for
the meminfo timeout or LMK**. The original raw logs and failures remain untouched.

Commands (Python 3.12, repository root):

```sh
python -m unittest discover -s tooling/verification -p 'test_android*.py' -v
python tooling/docs/validate_docs.py
```

## Proposed controlled environment correction — not executed

Use the official Google APIs image without Play Store provisioning while retaining
Android 17/API37.1, x86_64 and 16-KiB guest pages. Current official
[Google APIs SDK catalogue](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml)
offers `system-images;android-37.1;google_apis_ps16k;x86_64`, stable revision 9,
archive `x86_64-ps16k-37.1_r09.zip`, 2,178,012,675 bytes, published SHA1
`35c0da1cd298f3426a5b48233f59515097446a28`.
This is metadata inspection, not an installed-image or runtime result.

Removing Play Store provisioning removes an unnecessary background workload from
an offline-editor lane. Google services and ordinary system boot work remain;
success is a hypothesis, not established causality. Current Play Store revision 9
also differs from the historically unrecorded revision. A successful trial cannot
isolate image-family effects from revision effects. It must be reported as a new
environment qualification, not a controlled proof of the historical root cause.

The proposed single run uses the already retained PR35 c0 app/test/peer APKs and
the reviewed host runner. Do not rebuild library binaries. Preserve the same
6144 MiB/two CPUs, cold instrumentation, queue barrier, one-shot memory admission,
63 exact groups, both process-kill witnesses, cross-UID clipboard, assertion values
and deadlines. Verify catalogue revision/archive identity before installation;
fail if it has drifted. Retain compact package.xml/fingerprint/page-size identity
and the existing result/failure files. No telemetry-only boot or loop for green.

If setup or an assertion fails, retain it and stop. If all checks pass, the result
supports proposing this environment for the ordinary CI lane. It does not qualify
Play Store integration, genuine keyboard/IME, AT, other Android releases or physical
devices. The old Play Store failure remains open in the historical record. Revert
the image-only workflow selection if rejected; no data migration or production
code change is involved. Coordinator review is required before this run or a CI
lane change. API28/phone evidence cannot replace this API37/16-KiB gate.

Machine-local minimal proof: `tests/local-evidence/resume-20261102/` in the primary
checkout contains before/after host logs and the relevant official package metadata.
The metadata helper's first console listing failed on a Windows output encoding
after saving the catalogue record. A bounded ASCII/UTF-8 listing read that existing
record; no image download or qualification was retried.

## Subsequent observed trials and corrected observer contract

The coordinator separately approved the diagnostic implementation and bounded
execution. Run41419969057 stopped before boot on absent Ubuntu libpulse. Its
explicit host correction was reviewed; run41422187986 verified the pinned host,
installed image bytes, API37/x86_64/16-KiB guest and unlocked-service readiness,
then encountered the same118-byte global memory-service timeout before install.
Both failures remain. No editor/recovery/clipboard checks ran in either trial.

Offline inspection found a specific observer-design problem. Android17 reference
framework94b4c163b7dfe5ce3607f7bb8456f9573f7de57d MemBinder disables the app
freezer around global process-memory traversal. CachedAppOptimizer takes the AM
and process locks and, when enabled, unfreezes frozen LRU processes. The retained
local CP31 services.dex independently confirms these calls. This is not exact
hosted-binary identity or proof that the dump caused historical LMK. It does show
why the global dump cannot be described as a passive memory observation.

The coordinator explicitly approved replacing this admission observer with one
`adb exec-out cat /proc/meminfo` command under the same deadline/15-second cap.
Kernel fields, units, uniqueness, UTF-8, exit/stderr/completion and ordinary bounds
are validated; zero free/swap values remain valid. No architecture-specific fields
or sufficient-RAM threshold are required. The status is KERNEL_OBSERVED, not
service readiness or qualification PASS. Three responsive AM/package/unlocked
probes, identity checks, installation queue synchronization and every actual cold
63-group/recovery/clipboard assertion remain unchanged. The old global parser and
fixtures remain for historical receipts. Only an explicitly intrusive post-failure
diagnostic still invokes global meminfo; the actual workflow/runner inventory found
no other admission invocation.

Two actual-function command-routing/late-response tests fail before the change.
All54 Python Android host tests pass after it, including negative kernel input and
original raw-retention/failure-preservation contracts. One intermediate retention
test supplied a deliberately nonempty stderr to its nominally valid kernel case;
that correctly failed kernel admission. It now tests that invalid-stderr case
separately from a valid response plus disk failure. The raw failed run remains.
This is not a device-suite result. Further hosted execution requires independent
exact-head review; no success is claimed until the actual workload runs.
