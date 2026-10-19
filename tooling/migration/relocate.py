"""One-time, owner-authorized ComposeDoc relocation. Never edits historical bytes.

Run only after the baseline has been repaired. This records each frozen original
independently of the active validator implementation and refuses collisions.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
RECORD = ROOT / "docs/amendments/composedoc-relocation-v1.json"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def move(old, new):
    source, target = ROOT / old, ROOT / new
    assert source.resolve().is_relative_to(ROOT) and target.resolve().is_relative_to(ROOT)
    assert source.exists(), old
    assert not target.exists(), new
    target.parent.mkdir(parents=True, exist_ok=True)
    source.rename(target)


def main():
    assert not RECORD.exists(), "One-time migration already recorded"
    manifests = ["v5-freeze-manifest.json", "v5.1-spec-manifest.json", "docs/plans/v6/v6-freeze-record.json"]
    protected = {}
    for name in manifests:
        for path, expected in json.loads((ROOT / name).read_text(encoding="utf-8"))["hashes"].items():
            assert digest(ROOT / path) == expected, path
            protected[path] = expected
    old_map = json.loads((ROOT / "docs/relocations.json").read_text(encoding="utf-8"))
    frozen_originals = {}
    # These are active tool inputs that need modification. Preserve their sealed
    # originals before replacing them with explicitly versioned active tooling.
    for old in ["docs/relocations.json", "planning/doc_paths.py", "planning/build_v6_docs.py", "planning/research_v6.py", "planning/v6_contract_corrections.py"]:
        target = "docs/archive/sealed-tooling/v6/" + old
        dest = ROOT / target
        assert not dest.exists()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes((ROOT / old).read_bytes())
        assert digest(dest) == protected[old]
        frozen_originals[old] = target
    paths = {
        "editor-v5-standalone.md": "docs/archive/specs/v5.0/editor-v5-standalone.md",
        "plan-v5.md": "docs/archive/specs/v5.0/plan-v5.md",
        "v5-freeze-decision.md": "docs/archive/specs/v5.0/v5-freeze-decision.md",
        "v5-freeze-manifest.json": "docs/archive/specs/v5.0/v5-freeze-manifest.json",
        "v5-qualification-matrix.csv": "docs/archive/specs/v5.0/v5-qualification-matrix.csv",
        "v5-validation-report.md": "docs/archive/specs/v5.0/v5-validation-report.md",
        "editor-v5.1-standalone.md": "docs/archive/specs/v5.1/editor-v5.1-standalone.md",
        "plan-v5.1.md": "docs/archive/specs/v5.1/plan-v5.1.md",
        "v5.1-scope-amendment.md": "docs/archive/specs/v5.1/v5.1-scope-amendment.md",
        "v5.1-spec-manifest.json": "docs/archive/specs/v5.1/v5.1-spec-manifest.json",
    }
    prefixes = {
        "research/tools/": "tooling/local/",
        "research/": "tests/archive/research/",
        "research_notes/": "docs/archive/research-notes/",
        "validation/": "tests/suites/",
        "reviews/": "tests/reviews/",
        "planning/": "tooling/docs/",
        "desktop-prototype/": "apps/desktop-workspace/",
    }
    for old, new in paths.items():
        move(old, new)
    # Most-specific moves first so downloaded tools do not enter the archive.
    for old, new in prefixes.items():
        move(old.rstrip("/"), new.rstrip("/"))

    def relocated(name):
        if name in paths:
            return paths[name]
        for old, new in prefixes.items():
            if name.startswith(old):
                return new + name[len(old):]
        return name

    for old, new in old_map["paths"].items():
        paths[old] = relocated(new)
    rows = []
    for name, expected in protected.items():
        actual = frozen_originals.get(name, relocated(name))
        assert digest(ROOT / actual) == expected, name
        rows.append({"originalPath": name, "path": actual, "sha256": expected})
    record = {
        "version": 1,
        "authority": "Owner approved ComposeDoc dev.shayxo.composedoc and eight-directory cleanup on 2026-10-19",
        "originalManifests": [{"originalPath": n, "path": relocated(n), "sha256": digest(ROOT / relocated(n))} for n in manifests],
        "protected": rows,
        "sealedOriginals": frozen_originals,
        "prefixes": prefixes,
        "paths": paths,
        "previousRelocationRecord": frozen_originals["docs/relocations.json"],
    }
    RECORD.parent.mkdir(parents=True, exist_ok=True)
    RECORD.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    (ROOT / "docs/relocations.json").write_text(json.dumps({
        "version": 2, "amendment": RECORD.relative_to(ROOT).as_posix(),
        "paths": paths, "prefixes": prefixes, "sealedOriginals": frozen_originals,
    }, indent=2) + "\n", encoding="utf-8")
    print("Relocated; protected originals verified:", len(rows))
    print("Active validators must now be updated before any publication.")


if __name__ == "__main__":
    main()
