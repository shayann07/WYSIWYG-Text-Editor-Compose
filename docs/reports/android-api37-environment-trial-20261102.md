# One proposed API37/16-KiB hosted environment trial

2 November 2026. Status: **PREPARED, NOT RUN**. This diagnostic branch's manual
workflow is not an adoption into the ordinary CI lane. The coordinator must review
the exact candidate before its single dispatch. No phone or local emulator is used.

## Fixed inputs and provenance

- Runner: `ubuntu-24.04`; retain actual `/etc/os-release` and `uname`. The rolling
  hosted runner revision is recorded by Actions, not falsely presented as immutable.
- Emulator: 37.2.13.0/build 16740729, official
  [Linux archive](https://dl.google.com/android/repository/emulator-linux_x64-16740729.zip),
  published SHA1 `f41f9e31eb5ca64926603c03265c50bbc0ff4172`. The download must verify;
  the actual executable must report this version/build. This is a change from the
  historical 37.2.12.0/build 16428233 and is a second environment variable.
- Image: `system-images;android-37.1;google_apis_ps16k;x86_64`, stable revision 9,
  archive `x86_64-ps16k-37.1_r09.zip`, 2,178,012,675 bytes, published SHA1
  `35c0da1cd298f3426a5b48233f59515097446a28`. Reject changed official catalogue
  identity before installation and verify installed `package.xml`. After SDK
  Manager installation, separately download the exact reviewed archive, verify its
  published checksum, and stream-compare every archive file with installed bytes.
  This rejects a different live-resolved payload even if its package revision is
  unchanged. It costs one extra 2.18-GB hosted transfer; neither archive nor SDK is
  uploaded or downloaded locally. Retain per-file SHA256, metadata and properties.
- Guest: verify API37, x86_64, 16,384-byte pages, Android17 16-KiB emulator build
  family, and system fingerprint equality with installed image `build.prop`.
  Retain the complete observed guest fingerprint; no pre-run fingerprint is invented.
- AVD: fresh `composedoc_ci`, Pixel 7, 6144 MiB/two CPUs, 2048M data partition,
  no hardware keyboard, no snapshot, software GPU and disabled Vulkan. No warm
  attach, extra delay, process-priority change or assertion/threshold modification.
- Host runner source: exactly five verbatim helpers from locally reviewed
  `2d23d55033d0456712428ccb8202f8ae36f2941c`, transparently included under
  `trial-harness/tooling/verification/`. The source commit/per-file SHA256 manifest
  is verified before use, removing the unavailable remote-checkout dependency.
  A link to this checkout's unchanged pinned test fonts supplies the original
  helper's verification-only font comparison. No editor implementation is copied.
  The deadline correction passed 50 host regressions. The diagnostic branch is not
  used as a competing editor implementation or public consumer example.
- Binaries: existing GitHub artifact **12841796990**, run **40746007286**, repository
  ID **1421229803**, PR35 head `c0d56ae40ef819ddf7f92b918dbe5a736f6ec8f8`.
  Artifact metadata was available and unexpired during preparation. Fail closed
  on expiration, repository/run/head mismatch, absent roles, byte mismatch or
  disagreement with the original passing independent-consumer build receipt.
  These are the exact **failing hosted-run** binaries, not local replacement builds:

| Role | SHA256 |
|---|---|
| App | `711102f4d7ead5ed1605bc4b29704033f57b17975b4c958d875088d81fcb1b9d` |
| Test | `d2e3aa0c6ae24b2df4a3c441d890e7bf61dd6d956e520d9a0401b3df0292262d` |
| Peer | `376e513ee3bb36e7b4057fd3befb8f62c3cca519b01ecf88eb93229623a070e5` |

The artifact relocator preserves original consumer/identity JSON unchanged. Its
separate record identifies path remapping and reports PASS_RELOCATION_ONLY; it
does not claim a new binary build or qualification. Unrelated ZIP entries are not
extracted. No JAR/APK rebuilding, local Maven repository or cache reconstruction.

## Trial, stopping rule and interpretation

One manual dispatch of the existing `ci.yml` workflow on this diagnostic branch
runs exactly one Android job. It performs normal owned-AVD/unlocked-service
preflight, image identity admission, original Gboard/display setup, then the
unchanged cold 63-group runner, both process-kill recovery witnesses and the
separate cross-UID clipboard witness. The original 120-second setup deadline,
15-second memory observation cap and 360-second instrumentation limit remain.
Preflight retains its original 300-second cap. There is no loop to repeat a failed
admission or application test. `set -euo pipefail` stops subsequent qualification
when a command fails. Cleanup targets only the observed owned emulator identity.

Retain original transfer metadata, exact relocated APKs, image/emulator/host/AVD
identities, raw emulator log, all runner failure/results and clipboard receipts in
one 14-day Actions artifact. Do not upload the 2-GiB system image, SDK cache or
whole historical consumer runtime. The hosted temporary machine owns these files.
The old failed artifacts and sealed files remain untouched.

The new image removes Play Store provisioning from an offline library test.
Google services and system boot work remain. A pass would qualify this particular
new environment and unchanged workload, subject to independent review. It would
not prove the historical LMK/meminfo cause, qualify Play Store integration or
authorize CI adoption. Historical image revision is unknown, and emulator version
also changes. Failure must be retained and explained before any further run.
API28/physical success cannot replace this gate. Ordinary CI remains unchanged
outside this diagnostic branch; rejection requires no production rollback.

## Local preparation checks

Ten new executable host tests pass: artifact provenance, original receipt and
payload consistency, missing/corrupt bytes, archive path isolation, package/revision/
checksum drift, installed-byte mismatch, guest API/ABI/page-size/fingerprint,
executable identity and verbatim host-helper integrity.
The candidate workflow parses and each shell block passes `bash -n`; no block was
executed locally. The actual current official image catalogue entry passes the
same production identity parser. These are preparation checks, not hosted Android
or real keyboard/IME/AT evidence. Raw preparation logs live in the primary
checkout's `tests/local-evidence/resume-20261102/`.

## Pre-execution dispatch validation failure

The first authorized request for candidate `546523c` was rejected with HTTP422
before Actions created a run: `runner.temp` is unavailable in a job-level `env`
expression. No emulator, setup job or editor test ran. Local YAML parsing and
`bash -n` had checked syntax, not GitHub's expression-context schema. The minimal
correction sets `ANDROID_AVD_HOME` through `GITHUB_ENV` using the actual shell's
`RUNNER_TEMP` in the first step. Retain the original rejection and obtain exact-head
review before another request; this is not an application qualification retry.
