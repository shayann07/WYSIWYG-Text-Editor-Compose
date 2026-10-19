"""Build with Gradle and launch the workspace or its separate qualification harness."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    args = sys.argv[1:]
    application = args[:1] == ["--app"]
    if application:
        args = args[1:]
    module = "apps:desktop-workspace" if application else "tests:desktop-harness"
    name = module.split(":")[-1]
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    result = subprocess.run([str(wrapper), "--console=plain", f":{module}:installDist"], cwd=ROOT)
    if result.returncode:
        return result.returncode
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    if not java:
        raise RuntimeError("JDK 21 is required; set JAVA_HOME or put java on PATH")
    libraries = ROOT / module.replace(":", "/") / "build/install" / name / "lib/*"
    entry = "dev.shayxo.composedoc.workspace.MainKt" if application else "editor.MainKt"
    return subprocess.run([java, "-cp", str(libraries), entry, *args], cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
