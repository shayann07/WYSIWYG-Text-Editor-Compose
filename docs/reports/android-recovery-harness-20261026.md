# Recovery PR qualification harness correction — 26 October 2026

This is a test-harness correction for the existing recovery PR. It changes no
production editor, recovery implementation, public API, document format, dependency,
numbering behavior or approved desktop geometry. The original 63 instrumented
group names/count and both process-interruption witnesses remain intact.

The branch includes current main `540ace73b7c50f969f143d9f60dec3c870ecac24`
additively. Contributor history and the combined CI checks from the merged runtime,
Poppler, license and navigation work are preserved. PR titles/descriptions are
unchanged; this report is not an approval, human attestation or issue closure.

## Reproduced test defects and correction

An earlier exact-build instrumented tap expected caret 2 but selected the word
0–4. Its synchronous DOWN call returned after 520 ms although the constructed
gesture used an UP timestamp only 40 ms after DOWN. Android's public asynchronous
injection overload also waits for window animations; the hidden three-argument
overload is not an adopted alternative.

A controlled witness now deliberately delays a pre-timestamped DOWN by the fixed
platform long-press threshold plus 200 ms. It requires actual window delivery,
captured target identity and word selection before UP. This proves that stale
DOWN is invalid input; because it awaits word selection before UP, it does not
prove every historical unmodified 40 ms test gesture becomes a long press.

The corrected tap uses one system input command with timestamps created inside
that command. The independent Paint/prefix coordinate calculation is performed
after each document reset. The actual window callback must receive one short
DOWN/UP pair, with UP delivery less than the long-press threshold after the
original DOWN timestamp. Activity, window, view, owner, document/history basis,
origin, dimensions and observed window display must match the captured target.
Drift fails; there is no retarget, replay or acceptance of word selection instead
of the original exact caret-2 assertion.

The first shell implementation failed closed on API 28: its exit receipt was
empty and no tap reached the window. `UiAutomation.executeShellCommand` uses
`Runtime.exec(String)`, which does not evaluate shell operators. The correction
deploys a versioned script from the test APK to a unique test-owned directory,
verifies its bytes/hash, passes its path through instrumentation arguments and
invokes `/system/bin/sh` with only the validated path and numeric arguments.
The script performs one tap, bounds its child command, retains stderr and prints
an explicit exit receipt. No hidden API, reflection, or newer public read/write
shell API is used.

API 28 input has no display flag. A harmless capability probe checks input grammar
and timeout support. Default-display mode requires the actually observed display
to be 0; unsupported displays are refused. The host removes only its captured
fixture with unchanged bytes and its own directory. Cleanup errors preserve the
original assertion as primary. The fixture is included in consumer source hashes.

Genuine platform-dispatched long-press/drag and a suppressed-delivery negative
remain within the original tap group. These are injected gestures, not human
keyboard or IME interaction. The compiled tap contract is invoked in that group;
host fixture regressions also execute actual Java string-process behavior.

## Evidence boundaries

At diagnostic source `d4a58a3b18b6263707c54ce99a9de7c58f024a2a`, the separately
identified API 28 supplement passed 66 groups, both owned SIGKILL/fresh-restore
witnesses and cross-UID rich clipboard. The fresh tap arrived 12 ms after DOWN's
timestamp; UP arrived at 18 ms, with matching target and exact caret 2. The stale
witness arrived at 630 ms against a 400 ms threshold and selected 0–4. Ordinary
long-press selected 0–2, drag reached 0–5, and suppressed delivery preserved 0–0.
The owned fixture was removed and the emulator stopped.

That source includes later tests and is **not** a qualification of this 63-group
PR head. Its production app and peer binaries remained byte-identical while only
the test APK changed. The raw packet is
`tests/local-evidence/landing-20261024/api28-script-final.json` in the primary
checkout, SHA256
`f78507071d9b7cf0741f0fceb3b9a459f7db511f75c626c7900efc0315dbad1c`.
The original empty-receipt failure is retained separately.

API 37.1 remains failed on earlier identified CI heads: the system killed the app
at priority 700 before instrumentation/editor startup, so no editing groups ran.
The supplementary API 28 pass does not replace that gate or establish a startup
cure. General memory availability does not identify the underlying zone/reclaim
cause. No warm attachment, image replacement or assertion relaxation is imported
into this PR.

## Reproduction and next gate

Use the documented D-drive JDK 21/Gradle environment. From the repository root:

```powershell
python -m unittest discover -s tooling/verification -p 'test_android*.py'
python tooling/docs/validate_docs.py
./gradlew.bat check apiCheck androidApiCheck
python tooling/verification/consumer_check.py --android-peer --output-dir tests/local-evidence/consumer/recovery-harness
```

The independent consumer output directory must be unused. For a specifically
owned disposable emulator, use the maintained `android_check.py` and
`android_clipboard.py` with that receipt and exact serial/AVD name; they refuse
physical-device targeting and retain raw outcomes. Do not rebuild between
environments while claiming the same APK identity.

The current PR head requires its own clean-commit gates, independent artifact
identity and hosted 63-group qualification before merge readiness is determined.
An unavailable or failed native, language, accessibility or platform check is
not a PASS. Computer control is off; no native walkthrough was performed by this
backport.
