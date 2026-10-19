"""Update active executable paths; historical witness and archived bytes stay intact."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PREFIXES = [
    ("desktop-prototype/local-evidence", "tests/local-evidence/desktop"),
    ("desktop-prototype/qualification", "tests/desktop/qualification"),
    ("desktop-prototype/verification.py", "tooling/verification/verification.py"),
    ("desktop-prototype/verify.py", "tooling/verification/verify.py"),
    ("research/tools", "tooling/local"),
    ("validation/", "tests/suites/"),
    ("desktop-prototype/", "apps/desktop-workspace/"),
    ("research/", "tests/archive/research/"),
    ("reviews/", "tests/reviews/"),
    ("planning/", "tooling/docs/"),
]
MODULES = ("model", "domain", "data", "layout", "ui", "workspace", "harness")


def main():
    files = []
    for name in ["core", "apps/desktop-workspace/src", "tests/desktop-harness", "tests/desktop/qualification"]:
        files.extend(p for p in (ROOT / name).rglob("*") if p.suffix in (".kt", ".java", ".py") and "build" not in p.parts)
    files.extend((ROOT / "tooling/verification").glob("*.py"))
    for name in ["urdu", "pagination", "integration", "accessibility", "input", "web"]:
        files.extend(p for p in (ROOT / "tests/suites" / name).rglob("*.py") if "node_modules" not in p.parts and "local-evidence" not in p.parts)
    classes = {}
    for module in MODULES:
        prefix = ROOT / ("core/" + module if module not in ("workspace", "harness") else "apps/desktop-workspace" if module == "workspace" else "tests/desktop-harness")
        for p in prefix.rglob("*.kt"):
            if "build" in p.parts:
                continue
            for name in re.findall(r"(?m)^(?:data |enum |sealed |open |abstract )*(?:class|object|interface)\s+(\w+)", p.read_text(encoding="utf-8")):
                classes[name] = "dev.shayxo.composedoc." + module + "." + name
    for p in files:
        text = p.read_text(encoding="utf-8")
        for old, new in PREFIXES:
            text = text.replace(old, new)
        if p.suffix == ".java":
            imports = "\n".join("import dev.shayxo.composedoc." + m + ".*;" for m in MODULES) + "\nimport editor.MainKt;"
            text = text.replace("import editor.*;", imports)
            text = text.replace("DesktopEditorKt.showEditor", "DesktopWorkspaceKt.showEditor").replace("DesktopEditorKt.window", "DesktopWorkspaceKt.window")
            text = text.replace("HostChecksKt.findSurface", "SurfaceLookupKt.findSurface")
        for old, new in classes.items():
            text = re.sub(r"\beditor\." + re.escape(old) + r"\b", new, text)
        if p.suffix == ".py":
            # Explicit repository discovery survives arbitrary directory depth.
            text = re.sub(r"\bROOT\s*=\s*Path\(__file__\)\.resolve\(\)\.(?:parents\[\d+\]|parent(?:\.parent)*)", "ROOT = _REPO_ROOT", text)
            header = "\nfrom pathlib import Path as _RepoPath\nimport sys as _repo_sys\n_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())\n_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))\n_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))\n"
            # Keep any module docstring and future imports legal.
            import ast
            tree = ast.parse(text)
            at = 0
            for node in tree.body:
                if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str) and at == 0:
                    at = node.end_lineno
                elif isinstance(node, ast.ImportFrom) and node.module == "__future__":
                    at = node.end_lineno
                else:
                    break
            lines = text.splitlines(True)
            text = "".join(lines[:at]) + header + "".join(lines[at:])
        p.write_text(text, encoding="utf-8")
    print("Rewired", len(files), "active sources/helpers. Frozen/archive files were excluded.")


if __name__ == "__main__":
    main()
