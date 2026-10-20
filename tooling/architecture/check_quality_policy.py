"""Enforce baseline-free analysis and documented Kotlin suppressions in active code.

Historical archives, generated Unicode tables and Gradle output are not production
policy inputs. The architecture gate calls this checker as part of root ``check``.
"""
from pathlib import Path
import re
import subprocess

ACTIVE_ROOTS = ("core", "apps", "build-logic", "tests/desktop-harness")
EXCLUDED_PARTS = {"build", ".gradle", "generated"}
BASELINE_EXCLUDED_PARTS = {"build", ".gradle", ".git", "node_modules", "__pycache__"}
BASELINE_IGNORED_PREFIXES = (("tests", "local-evidence"), ("tooling", "local"))
BASELINE_EXCLUDED_PREFIXES = (("tests", "archive"), ("docs", "archive"))
SUPPRESSION = re.compile(r"@\s*(?:file\s*:\s*)?(?:kotlin\s*\.\s*)?Suppress\s*\(")
BASELINE_SETTING = re.compile(
    r"\bbaseline\s*(?:=|\.\s*(?:set|setFrom|convention)\s*\()"
)


def code_only(source):
    """Mask literals/comments without moving annotation offsets or line numbers."""
    masked = list(source)
    at = 0
    while at < len(source):
        end = at
        if source.startswith('//', at):
            end = source.find('\n', at)
            if end < 0:
                end = len(source)
        elif source.startswith('/*', at):
            depth, end = 1, at + 2
            while depth and end < len(source):
                if source.startswith('/*', end):
                    depth, end = depth + 1, end + 2
                elif source.startswith('*/', end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
        elif source.startswith('"""', at):
            close = source.find('"""', at + 3)
            end = len(source) if close < 0 else close + 3
        elif source[at] in ('"', "'"):
            delimiter, end = source[at], at + 1
            while end < len(source):
                if source[end] == '\\':
                    end += 2
                elif source[end] == delimiter:
                    end += 1
                    break
                else:
                    end += 1
            end = min(end, len(source))
        if end > at:
            for index in range(at, end):
                if masked[index] != '\n':
                    masked[index] = ' '
            at = end
        else:
            at += 1
    return ''.join(masked)


def suppression_errors(source):
    lines = source.splitlines()
    errors = []
    for match in SUPPRESSION.finditer(code_only(source)):
        line = source.count("\n", 0, match.start())
        previous = line - 1
        comments = []
        while previous >= 0 and lines[previous].lstrip().startswith("//"):
            comments.append(lines[previous].strip())
            previous -= 1
        if not any(re.match(r"// Reason:\s*\S", comment) for comment in comments):
            errors.append(f"line {line + 1}: @Suppress requires an adjacent // Reason: explanation")
    return errors


def baseline_config_errors(source):
    return ["Detekt baseline configuration is forbidden"] if BASELINE_SETTING.search(code_only(source)) else []


def active_files(root):
    for name in ACTIVE_ROOTS:
        directory = root / name
        if not directory.exists():
            continue
        for file in directory.rglob("*"):
            relative = file.relative_to(directory)
            if file.is_file() and not any(part in EXCLUDED_PARTS for part in relative.parts):
                yield file
    yield from root.glob("*.gradle.kts")


def baseline_policy_files(root):
    """Use Git's index/unignored inventory, never walk ignored runtime artifacts.

    Frozen plans are Markdown/JSON and are not opened by this XML/Gradle policy.
    The preserved archive trees and machine/generated evidence are excluded.
    """
    process = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    for name in sorted(set(process.stdout.decode("utf-8").split("\0")) - {""}):
        relative = Path(name)
        if any(part in BASELINE_EXCLUDED_PARTS for part in relative.parts):
            continue
        if any(relative.parts[:len(prefix)] == prefix for prefix in BASELINE_EXCLUDED_PREFIXES):
            continue
        if any(relative.parts[:len(prefix)] == prefix for prefix in BASELINE_IGNORED_PREFIXES):
            continue
        file = root / relative
        if file.is_file() and (file.suffix.lower() in (".xml", ".gradle") or file.name.endswith(".gradle.kts")):
            yield file


def check_quality_policy(root):
    root = Path(root)
    errors = []
    for file in active_files(root):
        name = str(file.relative_to(root))
        if file.name == "Unicode16ConformanceTest.kt":
            continue
        if file.suffix in (".kt", ".kts"):
            source = file.read_text(encoding="utf-8")
            errors += [f"{name}: {error}" for error in suppression_errors(source)]
    for file in baseline_policy_files(root):
        name = str(file.relative_to(root))
        if file.suffix.lower() == ".gradle" or file.name.endswith(".gradle.kts"):
            errors += [f"{name}: {error}" for error in baseline_config_errors(file.read_text(encoding="utf-8"))]
        if file.suffix.lower() == ".xml":
            if ("detekt" in file.name.lower() and "baseline" in file.name.lower()) or re.search(
                r"<\s*SmellBaseline(?:\s|>)", file.read_text(encoding="utf-8")
            ):
                errors.append(f"{name}: Detekt baseline files are forbidden")
    return errors


if __name__ == "__main__":
    issues = check_quality_policy(Path(__file__).resolve().parents[2])
    for issue in issues:
        print(issue)
    raise SystemExit(1 if issues else 0)
