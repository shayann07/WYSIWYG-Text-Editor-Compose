"""Build once; run all deterministic checks with bounded output and failing exit codes.

The attached-host suite uses synthetic callbacks. This runner does NOT certify an OS IME.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "desktop-prototype"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use only after a successful current-source build")
    args = parser.parse_args()
    output = PROJECT / "local-evidence" / ("batch-" + time.strftime("%Y%m%d-%H%M%S") + "-" + str(os.getpid()))
    output.mkdir(parents=True)
    report = {"scope": "Deterministic kernel, file, formatting, stress and attached-host tests; not real OS IME qualification", "steps": []}
    build = PROJECT / "target" if args.skip_build else output / "build"

    def run(name, command, timeout, expected=None):
        start = time.monotonic()
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
            log = result.stdout.decode("utf-8", "replace")
            passed = result.returncode == 0 and (expected is None or expected in log)
            code = result.returncode
        except subprocess.TimeoutExpired as error:
            log = (error.stdout or b"").decode("utf-8", "replace") + "\nTIMEOUT"
            passed, code = False, None
        (output / (name + ".log")).write_text(log, encoding="utf-8")
        report["steps"].append({"name": name, "passed": passed, "exitCode": code, "seconds": round(time.monotonic() - start, 3)})
        print(f"{'PASS' if passed else 'FAIL'} {name}", flush=True)
        if not passed:
            print("\n".join(log.splitlines()[-15:]), flush=True)
            raise RuntimeError(name + " failed")

    try:
        if not args.skip_build:
            maven = shutil.which("mvn.cmd") or str(ROOT / "research/tools/apache-maven-3.9.9/bin/mvn.cmd")
            run("build", [maven, "-f", str(PROJECT / "pom.xml"), "-q", "-B", "-ntp", "compile", "dependency:copy-dependencies", "-Deditor.build.directory=" + str(build), "-DincludeScope=runtime", "-DoutputDirectory=" + str(build / "lib"), "-Dmdep.prependGroupId=true"], 300)
        java = shutil.which("java")
        if not java:
            raise RuntimeError("Java is not on PATH")
        cp = str(build / "classes") + os.pathsep + str(build / "lib/*")
        base = [java, "-cp", cp, "editor.MainKt"]
        run("kernel-files-formatting", base + ["--check"], 120, "ALL 17 formatting checks passed")
        run("stress", base + ["--stress"], 120, "PASS 12000 randomized range-format commands")
        host_result = output / "host-checks.json"
        run("attached-host", base + ["--host-check", "--host-result=" + str(host_result)], 90, '"status":"PASS"')
        report["host"] = json.loads(host_result.read_text(encoding="utf-8"))
        if report["host"].get("urduArabicRtl") != "PASS":
            raise RuntimeError("Missing RTL host verdict")
        if report["host"].get("japaneseImeHost") != "PASS":
            raise RuntimeError("Missing Japanese IME host verdict")
        if report["host"].get("paragraphLayout") != "PASS":
            raise RuntimeError("Missing paragraph layout verdict")
        probes_runner = ROOT / "reviews" / "argon-critical-path" / "experiments" / "run_all_probes.py"
        if probes_runner.exists():
            os.environ["EDITOR_BUILD_CLASSPATH"] = cp
            os.environ["ARGON_PROBE_SUMMARY_OUT"] = str(output / "probe_results_summary.json")
            run("argon-regression-probes", [sys.executable, str(probes_runner)], 120, "All probes completed")
        frozen = json.loads((ROOT / "v5-freeze-manifest.json").read_text(encoding="utf-8"))
        for path, expected in frozen["hashes"].items():
            if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != expected:
                raise RuntimeError("Frozen file changed: " + path)
        report["frozenFilesUnchanged"] = len(frozen["hashes"])
        all_sources = [PROJECT / "pom.xml", PROJECT / "run.cmd", PROJECT / "verify.py"] + sorted((PROJECT / "src/main/kotlin").rglob("*.kt"))
        report["sources"] = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in all_sources}
        report["status"] = "PASS"
        print(f"PASS frozen v5 ({len(frozen['hashes'])} files)")
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        print("FAIL " + str(error))
    finally:
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("Evidence: " + str(output))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
