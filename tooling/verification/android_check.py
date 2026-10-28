"""Run instrumented production adapters on an explicitly named disposable emulator.

This is not genuine keyboard/IME or TalkBack evidence. No device wipe, settings
change, app-data clearing, SDK installation or physical-device targeting occurs.
"""

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import time
import zipfile
from android_process import run_witness
from android_tap_fixture import owned_tap_fixture
from android_memory_readiness import observe_memory, admitted_instrument, MemoryServiceUnavailable

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "dev.shayxo.composedoc.androidsample"
ASSETS = "assets/dev/shayxo/composedoc/layout/fonts/"
EXPECTED_GROUPS = 63


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_result(result, apk_hash, expected_groups=EXPECTED_GROUPS):
    groups = result.get("groups", [])
    names = [g.get("name") for g in groups]
    if (result.get("status") != "PASS" or result.get("fatal") is not None
            or len(groups) != expected_groups or len(set(names)) != expected_groups
            or not all(g.get("status") == "PASS" for g in groups)):
        raise ValueError("Missing, repeated or failed instrumented checks")
    if result.get("apkSha256") != apk_hash:
        raise ValueError("Instrumented result belongs to a different APK")
    if result.get("api", 0) < 24:
        raise ValueError("Missing supported device API identity")


def packaged_fonts(apk):
    source = ROOT / "tests/suites/urdu/u0"
    manifest = (source / "fonts.json").read_bytes()
    entries = json.loads(manifest)
    expected = {"fonts.json": manifest, "OFL.txt": (source / "fonts/OFL.txt").read_bytes()}
    for font in entries["candidateFonts"] + entries["fallbackFonts"]:
        name = font["fileName"]
        expected[name] = (source / "fonts" / name).read_bytes()
        if sha(expected[name]) != font["sha256"] or len(expected[name]) != font["sizeBytes"]:
            raise ValueError("Source pinned font identity mismatch: " + name)
    with zipfile.ZipFile(apk) as archive:
        for name, data in expected.items():
            if archive.read(ASSETS + name) != data:
                raise ValueError("Packaged font/notice differs: " + name)
    return {name: sha(data) for name, data in expected.items()}


def wait_for_owned_emulator(probe, expected_name, *, clock=time.monotonic, sleep=time.sleep, timeout=120):
    """Read identity while ADB starts; reject a different AVD immediately."""
    deadline = clock() + timeout
    attempt = 0
    while clock() < deadline:
        attempt += 1
        try:
            observed = probe(f"identity-{attempt}", "emu", "avd", "name", timeout=5).decode().splitlines()
        except (RuntimeError, subprocess.TimeoutExpired):
            sleep(max(0, min(2, deadline - clock())))
            continue
        if not observed or observed[0].strip() != expected_name:
            raise ValueError("Emulator identity does not match the authorized disposable AVD")
        return {"attempts": attempt, "name": expected_name}
    raise TimeoutError("Named disposable emulator did not register with ADB")


def wait_for_services(probe, *, clock=time.monotonic, sleep=time.sleep, timeout=120):
    """Boot completion alone does not guarantee responsive package/activity services.

    Require user 0 unlocked and three consecutive read-only service responses
    before installing anything. A
    transient failure is setup evidence, never an application assertion retry.
    """
    deadline = clock() + timeout
    stable = 0
    attempt = 0
    while clock() < deadline:
        attempt += 1
        try:
            package = probe(f"ready-{attempt}-package", "shell", "cmd", "package", "path", "android", timeout=5)
            activity = probe(f"ready-{attempt}-activity", "shell", "cmd", "activity", "get-current-user", timeout=5)
            user = probe(f"ready-{attempt}-user", "shell", "cmd", "activity", "get-started-user-state", "0", timeout=5)
            ready = (any(line.startswith(b"package:") for line in package.splitlines())
                     and activity.strip() == b"0" and user.strip() == b"RUNNING_UNLOCKED")
        except (RuntimeError, subprocess.TimeoutExpired):
            ready = False
        stable = stable + 1 if ready else 0
        if stable == 3:
            return {"attempts": attempt, "consecutiveResponsiveProbes": stable, "unlockedUser": 0}
        sleep(max(0, min(2, deadline - clock())))
    raise TimeoutError("Android package/activity services did not become stably responsive")


def wait_for_install_broadcasts(run):
    """Finish Android 17 installation broadcasts before a cold instrumentation start.

    Package installation returns before its broadcasts finish. On fresh API37
    images those receivers overlap first-boot work and instrumentation attach.
    Use Android's queue barrier, not a sleep, process-priority change or retry.
    This establishes only queue readiness; it never qualifies editor behavior.
    """
    api_raw = run("install-platform-api", "shell", "getprop", "ro.build.version.sdk", timeout=5).strip()
    if not re.fullmatch(rb"[0-9]{1,3}", api_raw):
        raise ValueError("Cannot identify installed Android API for broadcast readiness")
    api = int(api_raw)
    if api < 37:
        return {"status": "NOT_APPLICABLE", "api": api}
    started = time.monotonic()
    raw = run("install-broadcast-idle", "shell", "am", "wait-for-broadcast-idle", timeout=90)
    lines = raw.decode("utf-8", "strict").strip().splitlines()
    if not lines or lines[-1].strip() != "All broadcast queues are idle!":
        raise ValueError("Android did not confirm installation broadcast queues are idle")
    return {"status": "READY", "api": api, "elapsedSeconds": time.monotonic() - started,
            "scope": "Installation broadcast queues only; not memory or editor qualification"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--wait-only", action="store_true", help="Readiness only; emits READY, never qualification PASS")
    parser.add_argument("--readiness-timeout", type=int, default=120)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--apk", type=Path)
    selection.add_argument("--consumer-receipt", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.readiness_timeout <= 300:
        parser.error("Readiness timeout must be between1 and300 seconds")
    if args.consumer_receipt:
        consumer = json.loads(args.consumer_receipt.read_text(encoding="utf-8"))
        if consumer.get("status") != "PASS":
            parser.error("Independent binary consumer has not passed")
        artifacts = consumer.get("androidArtifacts", {})
        app = [Path(p) for p in artifacts if "/apk/debug/" in p.replace("\\", "/")]
        tests = [Path(p) for p in artifacts if "/apk/androidTest/debug/" in p.replace("\\", "/")]
        if len(app) != 1 or len(tests) != 1:
            parser.error("Receipt must identify exactly one app and one instrumentation APK")
        args.apk, args.test_apk = app[0], tests[0]
        for path in (args.apk, args.test_apk):
            if sha(path.read_bytes()) != artifacts[str(path)]:
                parser.error("Independent artifact changed after its build receipt")
    elif not args.test_apk:
        parser.error("--test-apk is required with --apk")
    if not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly named disposable emulator is supported")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("Supply the installed Android SDK through ANDROID_HOME")
    adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    receipt = {"status": "RUNNING", "serial": args.serial, "avdName": args.avd_name,
               "scope": "Instrumented callbacks, mounted geometry, lifecycle and packaged resources; not real IME or AT"}
    receipt['qualification'] = dict(editor='NOT RUN', recovery='NOT RUN', clipboard='NOT RUN')

    def save():
        (output / "results.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(label, *command, timeout=180, allowed_codes=(0,), with_status=False, defer_evidence_errors=False):
        evidence_errors = []

        def retain(suffix, raw):
            try:
                (output / (label + '.' + suffix)).write_bytes(raw)
            except OSError as failure:
                evidence_errors.append(suffix + ': ' + str(failure))

        try:
            result = subprocess.run([adb, "-s", args.serial, *command], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            retain('stdout', failure.stdout or b'')
            retain('stderr', failure.stderr or b'')
            retain('timeout', str(failure).encode('utf-8'))
            failure.evidence_errors = evidence_errors
            raise
        retain('stdout', result.stdout)
        retain('stderr', result.stderr)
        result.evidence_errors = evidence_errors
        if allowed_codes is not None and result.returncode not in allowed_codes:
            raise RuntimeError(label + " failed (exit " + str(result.returncode) + "): "
                               + result.stderr.decode("utf-8", "replace")
                               + ("; evidence retention: " + str(evidence_errors) if evidence_errors else ""))
        if evidence_errors and not (defer_evidence_errors and with_status):
            raise RuntimeError(label + ' cannot retain command evidence: ' + str(evidence_errors))
        # Only the memory observer requests deferred retention errors. It validates
        # the original outcome first, then fails closed even for a valid response.
        return result if with_status else result.stdout

    owned_identified = False
    save()
    try:
        readiness_deadline = time.monotonic() + args.readiness_timeout
        receipt["emulatorIdentity"] = wait_for_owned_emulator(run, args.avd_name, timeout=args.readiness_timeout)
        owned_identified = True
        receipt["serviceReadiness"] = wait_for_services(run, timeout=max(0, readiness_deadline - time.monotonic()))
        if args.wait_only:
            receipt.update(status="READY", scope="Owned emulator readiness only; no adapter checks executed")
            save()
            print("READY owned emulator services/user; no adapter checks executed")
            return
        receipt["apkSha256"] = sha(args.apk.read_bytes())
        receipt["testApkSha256"] = sha(args.test_apk.read_bytes())
        receipt["packagedFonts"] = packaged_fonts(args.apk)
        receipt['memoryBeforeInstall'] = observe_memory(run, output, 'pre-test-memory', readiness_deadline)
        run("install", "install", "-r", str(args.apk.resolve()))
        run("install-test", "install", "-r", str(args.test_apk.resolve()))
        receipt["installBroadcastReadiness"] = wait_for_install_broadcasts(run)
        save()
        run_id = "adapter-" + str(time.time_ns())
        receipt["runId"] = run_id
        with owned_tap_fixture(run, args.test_apk, output, run_id, receipt) as tap_path:
            admitted_instrument(run, output, receipt, readiness_deadline, lambda:
                run("instrument", "shell", "am", "instrument", "-w", "-e", "run-id", run_id, "-e", "tap-fixture", tap_path,
                    PACKAGE + ".test/" + PACKAGE + ".AdapterInstrumentation", timeout=360))
        # am instrument may exit zero even when the instrumentation reports failure.
        raw = run("device-result", "shell", "run-as", PACKAGE, "cat",
                  "files/qualification/" + run_id + "/results.json")
        result = json.loads(raw)
        receipt["instrumented"] = result
        archive = run("device-files", "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "-C",
                      "files/qualification/" + run_id, ".")
        (output / "device-files.tar").write_bytes(archive)
        receipt["deviceArchiveSha256"] = sha(archive)
        validate_result(result, receipt["apkSha256"])
        receipt['qualification']['editor'] = 'PASS'
        receipt["processInterruption"] = run_witness(run, output)
        close_output = output / "after-close-interruption"
        close_output.mkdir()
        receipt["afterCloseInterruption"] = run_witness(run, close_output, close_after_discard=True)
        receipt['qualification']['recovery'] = 'PASS'
        receipt["status"] = "PASS"
    except Exception as failure:
        receipt.update(status="BLOCKED" if isinstance(failure, MemoryServiceUnavailable) else "FAIL", error=str(failure))
        if isinstance(failure, MemoryServiceUnavailable):
            receipt['memoryReadinessFailure'] = failure.record
        if owned_identified:
            for label, command in (
                ("failure-logcat", ("logcat", "-d", "-t", "1500")),
                ("failure-data-space", ("shell", "df", "/data")),
                ("failure-memory", ("shell", "dumpsys", "meminfo")),
                ("failure-input-method", ("shell", "dumpsys", "input_method")),
                ("failure-power", ("shell", "dumpsys", "power")),
                ("failure-window-policy", ("shell", "dumpsys", "window", "policy")),
            ):
                try:
                    run(label, *command, timeout=10)
                except Exception as diagnostic_failure:
                    receipt.setdefault("diagnosticErrors", {})[label] = str(diagnostic_failure)
        try:
            save()
        except OSError as receipt_failure:
            failure.add_note('Cannot persist failure receipt: ' + str(receipt_failure))
        raise
    save()
    print(f"PASS {EXPECTED_GROUPS} instrumented groups and exact packaged fonts; evidence " + str(output))


if __name__ == "__main__":
    main()
