"""Focused production instrumentation on an explicitly owned disposable AVD; not native IME evidence."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

import android_check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--focus", required=True)
    parser.add_argument("--groups", type=int, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Only explicitly identified disposable emulators are accepted")
    root = android_check.ROOT
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Supply the installed SDK through ANDROID_HOME")
    adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))

    def run(label, *command, timeout=120):
        result = subprocess.run([adb, "-s", args.serial, *command], capture_output=True, timeout=timeout)
        (output / (label + ".stdout")).write_bytes(result.stdout)
        (output / (label + ".stderr")).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(f"{label}: exit {result.returncode}; raw output retained")
        return result.stdout

    android_check.wait_for_owned_emulator(run, args.avd_name)
    android_check.wait_for_services(run)
    # Same already-qualified headless-display precondition as CI; this is injected fixture setup, not native input evidence.
    run("pre-test-power", "shell", "dumpsys", "power")
    run("wake-test-display", "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run("dismiss-test-keyguard", "shell", "wm", "dismiss-keyguard")
    artifacts = {"app.apk": "apps/android-sample/build/outputs/apk/debug/android-sample-debug.apk",
                 "tests.apk": "apps/android-sample/build/outputs/apk/androidTest/debug/android-sample-debug-androidTest.apk"}
    for name, source in artifacts.items():
        shutil.copy2(root / source, output / name)
        run("install-" + name, "install", "-r", str(output / name))
    run_id = "focused-" + str(time.time_ns())
    package = android_check.PACKAGE
    run("instrument", "shell", "am", "instrument", "-w", "-e", "focus", args.focus,
        "-e", "run-id", run_id, f"{package}.test/{package}.AdapterInstrumentation", timeout=240)
    raw = run("result", "exec-out", "run-as", package, "cat", f"files/qualification/{run_id}/results.json")
    (output / "results.json").write_bytes(raw)
    result = json.loads(raw)
    archive = run("device-files", "exec-out", "run-as", package, "tar", "-cf", "-", "-C",
                  f"files/qualification/{run_id}", ".")
    (output / "device-files.tar").write_bytes(archive)
    android_check.validate_result(result, android_check.sha((output / "app.apk").read_bytes()), args.groups)
    print(json.dumps({"status": "PASS", "groups": args.groups, "evidence": str(output),
                      "apkSha256": result["apkSha256"]}))


if __name__ == "__main__":
    main()
