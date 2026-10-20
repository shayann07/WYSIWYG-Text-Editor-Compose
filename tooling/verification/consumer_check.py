"""Build real Maven binaries, then a standalone consumer outside the checkout. No Maven local/composite."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("model", "domain", "data", "layout", "ui")


def source_inputs():
    paths = set()
    for folder in ("core", "build-logic", "gradle", "apps/android-sample", "apps/independent-consumer"):
        for path in (ROOT / folder).rglob("*"):
            if (path.is_file() and not any(x in path.relative_to(ROOT).parts for x in ("build", ".gradle"))
                    and path.suffix in (".kt", ".kts", ".toml", ".properties", ".xml", ".jar")):
                paths.add(path)
    paths.update(ROOT / p for p in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                                    "gradlew", "gradlew.bat", "tests/suites/urdu/u0/fonts.json"))
    paths.update((ROOT / "tests/suites/urdu/u0/fonts").glob("*.ttf"))
    paths.add(ROOT / "tests/suites/urdu/u0/fonts/OFL.txt")
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--android", action="store_true")
    args = parser.parse_args()
    initial_inputs = source_inputs()
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    outside = Path(tempfile.mkdtemp(prefix="composedoc-binary-consumer-"))
    assert not outside.is_relative_to(ROOT)
    repository = outside / "repository"; repository.mkdir()
    project = outside / "desktop"
    shutil.copytree(ROOT / "apps/independent-consumer", project,
                    ignore=shutil.ignore_patterns("build", ".gradle"))
    for name in ("gradlew", "gradlew.bat"):
        shutil.copy2(ROOT / name, project / name)
    shutil.copytree(ROOT / "gradle/wrapper", project / "gradle/wrapper")
    (project / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx2g\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\n", encoding="utf-8")
    outside_wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    receipt = dict(status="RUNNING", externalRoot=str(outside), repository=str(repository), steps=[],
                   scope="Standalone Gradle settings; module binaries/metadata; no editor implementation copied")
    receipt["sourceInputs"] = initial_inputs
    receipt["sourceCommit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()

    def save():
        (output / "results.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, command, cwd):
        log = output / (name + ".log")
        with log.open("wb") as stream:
            result = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
        receipt["steps"].append(dict(name=name, command=list(map(str, command)), cwd=str(cwd),
                                    exitCode=result.returncode, log=str(log)))
        save()
        if result.returncode:
            raise RuntimeError(name + " failed; " + log.read_text(encoding="utf-8", errors="replace")[-4000:])
        print("PASS " + name, flush=True)

    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    prop = "-PconsumerRepository=" + str(repository)
    try:
        tasks = [f":core:{m}:publish{v}PublicationToConsumerVerificationRepository"
                 for m in MODULES for v in (["KotlinMultiplatform", "Jvm", "Android"] if args.android else ["KotlinMultiplatform", "Jvm"])]
        run("publish-isolated", [str(wrapper), "--no-daemon", "--console=plain", prop, *tasks], ROOT)
        receipt["artifacts"] = {p.relative_to(repository).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in repository.rglob("*") if p.is_file()}
        assert any(p.endswith(".pom") for p in receipt["artifacts"])
        assert any(p.endswith(".module") for p in receipt["artifacts"])
        for p in repository.rglob("*.pom"):
            assert "desktop-workspace" not in p.read_text(encoding="utf-8") and "desktop-harness" not in p.read_text(encoding="utf-8")
        run("independent-build", [str(outside_wrapper), "--no-daemon", "--console=plain", prop,
                                  "installDist", "resolvedConsumerDependencies"], project)
        resolved = (output / "independent-build.log").read_text(encoding="utf-8")
        components = [line for line in resolved.splitlines() if line.startswith("RESOLVED ")]
        assert components and not any("desktop-workspace" in x or "desktop-harness" in x or "project :" in x for x in components)
        assert not any(str(ROOT) in x for x in components), components
        receipt["resolvedDependencies"] = components
        evidence = outside / "runtime-evidence"; evidence.mkdir()
        installed = project / "build/install/IndependentComposeDocConsumer"
        launcher = installed / "bin" / ("IndependentComposeDocConsumer.bat" if os.name == "nt" else "IndependentComposeDocConsumer")
        assert launcher.is_file(), "Independent application distribution was not built"
        receipt["distribution"] = {p.relative_to(installed).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in installed.rglob("*") if p.is_file()}
        receipt["launchCommand"] = str(launcher)
        run("outside-runtime", [str(launcher), "--check-only", "--evidence=" + str(evidence)], outside)
        observed = json.loads((evidence / "results.json").read_text(encoding="utf-8"))
        assert observed["status"] == "PASS" and len(observed["groups"]) == 8
        receipt["runtime"] = observed
        if args.android:
            android = outside / "android"; android.mkdir()
            shutil.copytree(ROOT / "apps/android-sample/src", android / "src")
            for name in ("gradlew", "gradlew.bat"):
                shutil.copy2(ROOT / name, android / name)
            shutil.copytree(ROOT / "gradle/wrapper", android / "gradle/wrapper")
            android_wrapper = android / ("gradlew.bat" if os.name == "nt" else "gradlew")
            shutil.copy2(project / "settings.gradle.kts", android / "settings.gradle.kts")
            # Same sample chrome, with only binary dependency coordinates; never copy library sources.
            import tomllib
            versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
            build = (ROOT / "apps/android-sample/build.gradle.kts").read_text(encoding="utf-8")
            build = build.replace("alias(libs.plugins.android.application)", 'id("com.android.application") version "' + versions["agp"] + '"')
            build = build.replace("alias(libs.plugins.kotlin.android)", 'kotlin("android") version "' + versions["kotlin"] + '"')
            build = build.replace("alias(libs.plugins.compose.compiler)", 'id("org.jetbrains.kotlin.plugin.compose") version "' + versions["kotlin"] + '"')
            build = build.replace('    id("quality-conventions")', '')
            for module in ("ui", "data"):
                build = build.replace('project(":core:' + module + '")', '"dev.shayxo.composedoc:' + module + ':0.1.0-SNAPSHOT"')
            for name in ("foundation", "ui"):
                build = build.replace('libs.compose.' + name, '"org.jetbrains.compose.' + name + ':' + name + ':' + versions["compose"] + '"')
            assert 'project(' not in build and 'includeBuild' not in build
            (android / "build.gradle.kts").write_text(build, encoding="utf-8")
            (android / "gradle.properties").write_text("android.useAndroidX=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\norg.gradle.jvmargs=-Xmx2g\norg.gradle.workers.max=2\nkotlin.compiler.execution.strategy=in-process\n", encoding="utf-8")
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if not sdk:
                settings = (ROOT / "local.properties").read_text(encoding="utf-8")
                sdk = next(x.split('=', 1)[1] for x in settings.splitlines() if x.startswith('sdk.dir='))
            (android / "local.properties").write_text("sdk.dir=" + str(sdk).replace("\\", "/") + "\n", encoding="utf-8")
            run("independent-android", [str(android_wrapper), "--no-daemon", "--console=plain", prop,
                                        "assembleDebug", "assembleDebugAndroidTest"], android)
            receipt["android"] = str(android)
            receipt["androidArtifacts"] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                            for p in android.glob("build/outputs/apk/**/*.apk")}
            assert receipt["androidArtifacts"]
        assert receipt["sourceInputs"] == source_inputs(), "Compilation inputs changed during binary consumer verification"
        receipt["status"] = "PASS"; save()
    except Exception as failure:
        receipt.update(status="FAIL", error=str(failure)); save(); raise
    print("Evidence: " + str(output), flush=True)


if __name__ == "__main__":
    main()
