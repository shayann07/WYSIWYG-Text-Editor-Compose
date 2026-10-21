"""Check declared Android adapter bytecode contracts; BCV currently consumes only the JVM target dumps.

This supplements BCV, never replaces common/JVM API checking. Internal Kotlin members mangled
with '$' and compiler default bridges are omitted; public adapter classes are explicit below.
"""
import argparse
import difflib
import os
import re
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    "data": ["AndroidDocumentCodec", "AndroidDocumentRepository", "ImageDecoder", "FragmentCodec",
             "AndroidDocumentRead", "AndroidUriRepository", "AndroidRecoveryEntry", "AndroidRecoveryRecord",
             "AndroidRecoveryStore", "AndroidClipboardFiles", "AndroidClipboardProvider"],
    "layout": ["AndroidTypography", "AndroidTypography$Companion"],
    "ui": ["AndroidEditorKt", "AndroidEditorView"],
}


def canonical(output):
    lines = []
    skip_descriptor = False
    for line in output.splitlines():
        if line.startswith("Compiled from ") or not line.strip():
            continue
        if line.strip().startswith("descriptor:"):
            if not skip_descriptor:
                lines.append(line.rstrip())
            skip_descriptor = False
        elif line.startswith("  "):
            name = line.strip().split("(", 1)[0].split()[-1].rstrip(";")
            skip_descriptor = "$" in name or "kotlin.jvm.internal.DefaultConstructorMarker" in line
            if not skip_descriptor:
                lines.append(line.rstrip())
        else:
            lines.append(line.rstrip())
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Owner-approved contract update only")
    args = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
    failed = False
    for module, names in TARGETS.items():
        declared = set()
        source_root = ROOT / f"core/{module}/src/androidMain/kotlin"
        for source in source_root.rglob("*.kt"):
            declared.update(re.findall(r"^(?:data\s+)?(?:class|object)\s+(\w+)",
                                       source.read_text(encoding="utf-8"), re.MULTILINE))
        uncovered = declared - set(names)
        if uncovered:
            raise RuntimeError(f"New public Android classes need contract coverage: {sorted(uncovered)}")
        jar = ROOT / f"core/{module}/build/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar"
        if not jar.is_file():
            raise RuntimeError(f"Compile the {module} Android adapter before checking its API")
        text = []
        for name in sorted(names):
            result = subprocess.run([str(java), "-public", "-s", "-classpath", str(jar),
                                     f"dev.shayxo.composedoc.{module}.{name}"],
                                    check=True, capture_output=True, text=True, encoding="utf-8")
            text.append(canonical(result.stdout))
        actual = "\n".join(text)
        path = ROOT / f"core/{module}/api/android/{module}.api"
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(actual, encoding="utf-8", newline="\n")
        elif not path.is_file() or path.read_text(encoding="utf-8") != actual:
            expected = path.read_text(encoding="utf-8") if path.is_file() else ""
            print("".join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True),
                                              fromfile=str(path), tofile="compiled Android adapter")))
            failed = True
    if failed:
        raise RuntimeError("Android adapter API changed; AGENTS.md requires owner approval")
    print("PASS Android adapter contracts" + (" (owner-approved dumps written)" if args.write else ""))


if __name__ == "__main__":
    main()
