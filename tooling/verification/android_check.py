"""Run instrumented production adapters on an explicitly named disposable emulator.

This is not genuine keyboard/IME or TalkBack evidence. No device wipe, settings
change, app-data clearing, SDK installation or physical-device targeting occurs.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import time
import zipfile
from android_tap_fixture import owned_tap_fixture
from android_process import run_witness

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "dev.shayxo.composedoc.androidsample"
ASSETS = "assets/dev/shayxo/composedoc/layout/fonts/"
EXPECTED_GROUPS = 66


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


def process_identity(probe, label):
    observed = probe(label+'-pid', 'shell', 'pidof', PACKAGE, allowed_codes=(0, 1), with_status=True)
    if observed.returncode not in (0, 1) or observed.stderr:
        raise RuntimeError('Owned PID query failed: '+observed.stderr.decode('utf-8', 'replace'))
    raw = observed.stdout.decode().strip()
    if observed.returncode == 1 and not raw:
        return None
    if not re.fullmatch(r'[1-9]\d*', raw):
        raise ValueError('Ambiguous owned process identity: ' + raw)
    observed = probe(label+'-stat', 'shell', 'cat', '/proc/'+raw+'/stat', with_status=True)
    if observed.returncode != 0 or observed.stderr:
        raise RuntimeError('Owned process birth query failed: '+observed.stderr.decode('utf-8', 'replace'))
    stat = observed.stdout.decode()
    before, separator, tail = stat.rpartition(')')
    fields = tail.split()
    if not separator or before.split(' ', 1)[0] != raw or len(fields) < 20 or not fields[19].isdigit():
        raise ValueError('Owned process birth identity unavailable')
    return dict(pid=raw, startTicks=fields[19])


def instrumentation_parser_capability(probe, result=None):
    """Probe parsing without a component, so neither invocation can start instrumentation.

    General `am help` is a different service parser; it is not a capability oracle.
    Raw output and return codes are retained even when the pair is inconclusive.
    """
    result = {} if result is None else result
    unknown = '--composedoc-parser-unknown-20261024'
    result.update(status='BLOCKED', scope='Parser recognition only; actual attachment still requires PID/birth guards', probes=[])
    for name, flag in [('recognized', '--no-restart'), ('unknown', unknown)]:
        observed = probe('attach-parser-'+name, 'shell', 'am', 'instrument', flag,
                         allowed_codes=(0, 1), with_status=True, timeout=10)
        row = dict(flag=flag, returncode=observed.returncode,
                   stdout=observed.stdout.decode('utf-8', 'replace'), stderr=observed.stderr.decode('utf-8', 'replace'))
        result['probes'].append(row)
    candidate, control = result['probes']
    error_lines = lambda row: re.findall(r'^Error:\s*(.*?)\s*$', row['stdout']+'\n'+row['stderr'], re.M)
    expected_missing = 'Argument expected after "--no-restart"'
    expected_unknown = 'Unknown option: '+unknown
    if (candidate['returncode'] not in (0, 1) or control['returncode'] not in (0, 1)
            or error_lines(candidate) != [expected_missing] or error_lines(control) != [expected_unknown]):
        raise RuntimeError('Installed instrumentation parser capability is unsupported or ambiguous; no fallback')
    result['status'] = 'RECOGNIZED_COMPONENT_REQUIRED'
    return result


def prepare_instrumentation_launch(probe, mode, run_id, result=None):
    result = {} if result is None else result
    result['mode'] = mode
    if mode == 'cold':
        result['stage'] = 'cold-instrumentation'
        return result
    if mode != 'foreground-attach':
        raise ValueError('Unknown instrumentation launch mode')
    result['stage'] = 'preflight'
    result['parserCapability'] = {}
    instrumentation_parser_capability(probe, result['parserCapability'])
    if process_identity(probe, 'before-cold-activity') is not None:
        raise RuntimeError('Foreground treatment requires a genuinely absent target process')
    result['stage'] = 'ordinary-cold-activity'
    launch = probe('ordinary-cold-activity', 'shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity',
                   '--ez', 'blank', 'true', '--es', 'qualification-root', 'bootstrap-'+run_id, timeout=120).decode('utf-8', 'replace')
    result['ordinaryColdActivityOutput'] = launch
    if not re.search(r'^Status:\s+ok\s*$', launch, re.M):
        raise RuntimeError('Ordinary cold Activity launch failed; retain it as treatment failure')
    if not re.search(r'^LaunchState:\s+COLD\s*$', launch, re.M):
        raise RuntimeError('CLI did not confirm a COLD ordinary Activity launch; treatment cannot be qualified')
    identity = process_identity(probe, 'before-attach')
    if identity is None:
        raise RuntimeError('Ordinary cold Activity launch left no owned process')
    foreground = probe('before-attach-foreground', 'shell', 'dumpsys', 'activity', 'activities').decode('utf-8', 'replace')
    if not any('topResumedActivity=' in line and PACKAGE+'/' in line and 'MainActivity' in line for line in foreground.splitlines()):
        raise RuntimeError('Ordinary Activity is not observed as the resumed foreground target')
    score = probe('before-attach-importance', 'shell', 'cat', '/proc/'+identity['pid']+'/oom_score_adj').decode().strip()
    if score != '0':
        raise RuntimeError('Ordinary foreground launch did not obtain system-assigned foreground importance: '+score)
    result.update(before=identity, systemAssignedOomAdj=score, stage='ready-for-attach',
                  bootstrapRoot='bootstrap-'+run_id, qualificationRoot=run_id)
    return result


def verify_attachment(probe, launch):
    if launch['mode'] == 'foreground-attach':
        after = process_identity(probe, 'after-instrumentation')
        launch['after'] = after
        if after != launch['before']:
            raise RuntimeError('No-restart attachment changed owned PID/birth identity; no fallback')
        launch['sameProcessVerified'] = True
        launch['stage'] = 'same-process-attachment-verified'


def failure_root_observation(probe, run_id):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', run_id):
        raise ValueError('Invalid captured qualification root identity')
    path = 'files/qualification/'+run_id
    observed = probe('failure-qualification-root', 'shell', 'run-as', PACKAGE, 'test', '-d', path,
                     allowed_codes=(0, 1), with_status=True)
    if observed.stderr or observed.returncode not in (0, 1):
        return dict(status='UNAVAILABLE', path=path, returncode=observed.returncode,
                    rawError=observed.stderr.decode('utf-8', 'replace'))
    return dict(status='AVAILABLE', path=path, state='PRESENT' if observed.returncode == 0 else 'ABSENT')


def instrument_clock(probe, label):
    result = dict(hostUtcStart=datetime.now(timezone.utc).isoformat())
    try:
        epoch = probe(label+'-epoch', 'shell', 'date', '+%s.%N', timeout=5).decode().strip()
        uptime = probe(label+'-uptime', 'shell', 'cat', '/proc/uptime', timeout=5).decode().strip()
        if not re.fullmatch(r'\d+\.\d+', epoch) or not re.fullmatch(r'\d+(?:\.\d+)?\s+\d+(?:\.\d+)?', uptime):
            raise ValueError('Device clock output is unavailable or malformed')
        result.update(status='AVAILABLE', deviceEpoch=epoch, deviceUptime=uptime)
    except Exception as failure:
        result.update(status='UNAVAILABLE', rawError=repr(failure))
    result['hostUtcEnd'] = datetime.now(timezone.utc).isoformat()
    return result


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
    parser.add_argument('--instrumentation-launch', choices=('cold', 'foreground-attach'), default='cold')
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

    def save():
        (output / "results.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(label, *command, timeout=180, allowed_codes=(0,), with_status=False):
        try:
            result = subprocess.run([adb, "-s", args.serial, *command], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            (output / (label + ".stdout")).write_bytes(failure.stdout or b"")
            (output / (label + ".stderr")).write_bytes(failure.stderr or b"")
            (output / (label + ".timeout")).write_text(str(failure), encoding="utf-8")
            raise
        (output / (label + ".stdout")).write_bytes(result.stdout)
        (output / (label + ".stderr")).write_bytes(result.stderr)
        if result.returncode not in allowed_codes:
            raise RuntimeError(label + " failed: " + result.stderr.decode("utf-8", "replace"))
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
        run("install", "install", "-r", str(args.apk.resolve()))
        run("install-test", "install", "-r", str(args.test_apk.resolve()))
        receipt["installBroadcastReadiness"] = wait_for_install_broadcasts(run)
        save()
        run_id = "adapter-" + str(time.time_ns())
        receipt["runId"] = run_id
        with owned_tap_fixture(run, args.test_apk, output, run_id, receipt) as tap_path:
            launch = dict(mode=args.instrumentation_launch, stage='preparing')
            receipt['instrumentationLaunch'] = launch
            prepare_instrumentation_launch(run, args.instrumentation_launch, run_id, launch)
            instrument_flags = ['--no-restart'] if args.instrumentation_launch == 'foreground-attach' else []
            window = dict(start=instrument_clock(run, 'instrument-start'))
            receipt['instrumentationWindow'] = window
            try:
                run("instrument", "shell", "am", "instrument", "-w", *instrument_flags, "-e", "run-id", run_id,
                    "-e", "tap-fixture", tap_path, PACKAGE + ".test/" + PACKAGE + ".AdapterInstrumentation", timeout=360)
            finally:
                window['end'] = instrument_clock(run, 'instrument-end')
        # am instrument may exit zero even when the instrumentation reports failure.
        raw = run("device-result", "shell", "run-as", PACKAGE, "cat",
                  "files/qualification/" + run_id + "/results.json")
        result = json.loads(raw)
        receipt["instrumented"] = result
        archive = run("device-files", "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "-C",
                      "files/qualification/" + run_id, ".")
        (output / "device-files.tar").write_bytes(archive)
        receipt["deviceArchiveSha256"] = sha(archive)
        verify_attachment(run, launch)
        validate_result(result, receipt["apkSha256"])
        receipt["processInterruption"] = run_witness(run, output)
        close_output = output / "after-close-interruption"
        close_output.mkdir()
        receipt["afterCloseInterruption"] = run_witness(run, close_output, close_after_discard=True)
        receipt["status"] = "PASS"
    except Exception as failure:
        receipt.update(status="FAIL", error=str(failure))
        if owned_identified:
            if receipt.get('runId'):
                try:
                    receipt['failureRootObservation'] = failure_root_observation(run, receipt['runId'])
                except Exception as diagnostic_failure:
                    receipt.setdefault('diagnosticErrors', {})['failure-qualification-root'] = str(diagnostic_failure)
            for label, command in (
                ("failure-logcat", ("logcat", "-d", "-t", "1500")),
                ("failure-logcat-epoch", ("logcat", "-d", "-v", "epoch", "-t", "1500")),
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
        save()
        raise
    save()
    print(f"PASS {EXPECTED_GROUPS} instrumented groups and exact packaged fonts; evidence " + str(output))


if __name__ == "__main__":
    main()
