"""Exercise the actual Gradle distribution jars from a fresh non-checkout cwd.

Does not build, install fonts/dependencies or modify runtime jars. Negative jars
are new owned copies; every original artifact and checked font byte is hashed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
RESOURCE = "dev/shayxo/composedoc/layout/fonts/"
FONT = "NotoNastaliqUrdu[wght].ttf"
ENTRY = "dev.shayxo.composedoc.harness.FontPortabilityChecks"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_negative_jar(original, target, mode):
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(target, "w") as output:
        for entry in source.infolist():
            if mode == "missing" and entry.filename == RESOURCE + FONT:
                continue
            data = source.read(entry)
            if mode == "corrupt" and entry.filename == RESOURCE + FONT:
                data = bytes([data[0] ^ 1]) + data[1:]
            if mode == "manifest" and entry.filename == RESOURCE + "fonts.json":
                data = b"{}"
            output.writestr(entry, data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-lib", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    runtime = args.runtime_lib.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    jars = sorted(runtime.glob("*.jar"))
    assert jars, "No packaged runtime jars"
    layout = [p for p in jars if zipfile.is_zipfile(p) and
              "dev/shayxo/composedoc/layout/TypographyProfile.class" in zipfile.ZipFile(p).namelist()]
    assert len(layout) == 1, "Expected exactly one layout implementation jar"
    layout = layout[0]
    originals = {str(p): digest(p) for p in jars}
    receipt = dict(status="RUNNING", runtimeArtifacts=originals, cases=[],
                   scope="Actual packaged JVM jars from non-checkout cwd, strict failures and file-route pixel/geometry parity; no native IME/expert typography claim")
    result = output / "results.json"

    def persist():
        result.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    persist()
    try:
        with zipfile.ZipFile(layout) as packaged:
            expected = {"fonts.json": ROOT / "tests/suites/urdu/u0/fonts.json"}
            expected.update({p.name: p for p in (ROOT / "tests/suites/urdu/u0/fonts").iterdir() if p.is_file()})
            for filename, path in expected.items():
                assert packaged.read(RESOURCE + filename) == path.read_bytes(), filename
            receipt["packagedSourceHashes"] = {name: digest(path) for name, path in expected.items()}
        with tempfile.TemporaryDirectory(prefix="composedoc-portable-font-cwd-") as working:
            assert not Path(working, "tests/suites/urdu/u0/fonts").exists()
            for mode, expected_failure in [("normal", None), ("missing", "resource unavailable"),
                                            ("corrupt", "font identity mismatch"), ("manifest", "manifest identity mismatch")]:
                selected = jars
                if mode != "normal":
                    mutant = output / (mode + "-layout.jar")
                    make_negative_jar(layout, mutant, mode)
                    selected = [mutant if p == layout else p for p in jars]
                child_result = output / (mode + ".json")
                command = [args.java, "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(map(str, selected)), ENTRY,
                           "--result=" + str(child_result),
                           "--reference-fonts=" + str(ROOT / "tests/suites/urdu/u0/fonts")]
                if expected_failure:
                    command.append("--expect-packaged-failure=" + expected_failure)
                child = subprocess.Popen(command, cwd=working, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                try:
                    log, _ = child.communicate(timeout=90)
                except subprocess.TimeoutExpired:
                    child.kill()
                    log, _ = child.communicate(timeout=15)
                    (output / (mode + ".log")).write_bytes(log)
                    raise AssertionError("Owned font test child timed out: " + mode)
                (output / (mode + ".log")).write_bytes(log)
                assert child.returncode == 0, log.decode("utf-8", "replace")
                assert b"Exception in thread " not in log
                observed = json.loads(child_result.read_text(encoding="utf-8"))
                assert observed["status"] == "PASS"
                receipt["cases"].append(dict(mode=mode, pid=child.pid, exitCode=child.returncode,
                                               command=command, result=observed))
                persist()
        assert originals == {str(p): digest(p) for p in jars}, "Original runtime changed during qualification"
        receipt["status"] = "PASS"
    except Exception as failure:
        receipt.update(status="FAIL", error=repr(failure))
        persist()
        raise
    persist()
    print("PASS actual jar font portability, 12 exact raster/geometry cases and three broken packaged jars: " + str(result))


if __name__ == "__main__":
    main()
