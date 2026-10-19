"""Check the actual Gradle project graph plus imports/qualified Kotlin references."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
ALLOWED = {
    ":core:model": set(),
    ":core:domain": {":core:model"},
    ":core:data": {":core:model", ":core:domain"},
    ":core:layout": {":core:model", ":core:domain"},
    ":core:ui": {":core:model", ":core:domain", ":core:layout"},
    ":apps:desktop-workspace": {":core:" + n for n in ("model", "domain", "data", "layout", "ui")},
}


def check_graph(graph):
    errors = []
    for name, allowed in ALLOWED.items():
        if name not in graph:
            errors.append("Missing real Gradle module: " + name)
        for dependency in graph.get(name, []):
            if dependency not in allowed:
                errors.append(name + " depends outward on " + dependency)
    return errors


def check_source(module, source):
    # Comments and literal diagnostics are not dependencies. Inspect both imports
    # and qualified references so fully qualified uses cannot bypass the rule.
    code = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*', ' ', source)
    errors = []
    forbidden = []
    if module in (":core:model", ":core:domain"):
        forbidden += ["androidx.compose.", "java.awt.", "javax.swing.", "androidx.lifecycle."]
    if module.startswith(":core:"):
        forbidden += ["dev.shayxo.composedoc.workspace.", "dev.shayxo.composedoc.harness."]
        allowed_names = {p.rsplit(":", 1)[-1] for p in ALLOWED[module]} | {module.rsplit(":", 1)[-1]}
        forbidden += ["dev.shayxo.composedoc." + n + "." for n in ("model", "domain", "data", "layout", "ui") if n not in allowed_names]
    for prefix in forbidden:
        if prefix in code:
            errors.append("Forbidden dependency " + prefix)
    return errors


def main():
    graph_path = ROOT / "build/verification/architecture.json"
    assert graph_path.is_file(), "Run Gradle architectureCheck to generate the resolved project graph"
    errors = check_graph(json.loads(graph_path.read_text(encoding="utf-8")))
    files = 0
    for module in ALLOWED:
        directory = ROOT / module.strip(":").replace(":", "/") / "src"
        for file in directory.rglob("*.kt"):
            files += 1
            source = file.read_text(encoding="utf-8")
            errors += [str(file.relative_to(ROOT)) + ": " + e for e in check_source(module, source)]
            if module in (":core:model", ":core:domain") and "commonMain" in file.parts:
                if re.search(r"(?m)^import (?:java|javax|android|androidx)\.", source):
                    errors.append(str(file.relative_to(ROOT)) + ": platform/framework import in pure common code")
    for error in errors:
        print(error)
    assert files > 0 and not errors, "Architecture boundaries failed"
    print(f"PASS resolved Gradle graph and {files} Kotlin source boundary checks")


if __name__ == "__main__":
    main()
