"""Regenerate local v5.2 planning documents; never modifies frozen baselines or code."""
from pathlib import Path
import hashlib
import json
from doc_paths import resolve

ROOT = Path(__file__).resolve().parent.parent


def read(name):
    return resolve(name).read_text(encoding="utf-8")


def write(name, value):
    resolve(name).write_bytes(value.encode("utf-8"))


def digest(name):
    return hashlib.sha256(resolve(name).read_bytes()).hexdigest()


def verify_manifest(name):
    manifest = json.loads(read(name))
    for path, expected in manifest["hashes"].items():
        if digest(path) != expected:
            raise RuntimeError(f"Historical baseline changed: {path}")
    return len(manifest["hashes"])


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one source marker: {old[:90]}")
    return text.replace(old, new, 1)


def main():
    original_count = verify_manifest("v5-freeze-manifest.json")
    prior_count = verify_manifest("v5.1-spec-manifest.json")
    source = read("plan-v5.1.md")
    source = replace_once(source, "# Open-source Compose Multiplatform WYSIWYG editor library — specification v5.1.0", "# Open-source Compose Multiplatform WYSIWYG editor library — plan v5.2.0")
    old_revision = source.splitlines()[1]
    source = replace_once(source, old_revision, "Revision: 5.2.0, 17 October 2026. Status: owner-requested product integration replan, PLANNED and UNQUALIFIED. Retains applicable v5.0/v5.1 local contracts; adds Urdu-first acceptance and explicit collaboration/review/schema design gates. No new production gate passes and no implementation changes are implied.")
    source = replace_once(source, "This supersedes v5.0.0 as the current requirements baseline through the explicit product-scope amendment in section 10.", "This supersedes v5.1.0 as the current library planning baseline through sections 10 and 11. PRODUCT-ROADMAP.md owns overall product sequencing; FEATURE-CATALOG.md and URDU-ACCEPTANCE.md define product acceptance. Section 11 governs new integration constraints; unresolved algorithms must be settled at their named gates before public API/schema freeze.")
    source = replace_once(source, "**Product:** an open-source embeddable WYSIWYG rich-text/document editor library for Compose Multiplatform. The desktop application is a validation harness/example consumer, not the final product. Section 10 defines the added library release and paged-output requirements.", "**Product mission:** the owner's Urdu-first Google Docs–level application, powered by an open-source embeddable WYSIWYG rich-text/document editor library for Compose Multiplatform. The desktop prototype is a validation harness/example consumer, not the final product. Section 10 defines paged output; section 11 defines application/review/collaboration boundaries. This library plan is one part of the product roadmap, not the whole app specification.")
    source = replace_once(source, "No native WebViews. No custom bidi index, new shaper, CRDT, or conditional-layout graph is required.", "No native WebViews, custom bidi index or new shaper is required by the local editor baseline. Collaboration's replication/merge algorithm is an explicit C0 decision under section 11; it is neither assumed solved nor prohibited by the earlier local-only design.")
    source = replace_once(source, "- Deferred: collaboration, merged/nested tables, multiple simultaneous carets, persistent undo across application restarts, and toolbar formatting that modifies an actively composing candidate before settlement.", "- Deferred from the initial local-library milestone, not from the product: collaboration/review/version-history integration and merged/split-cell support, now governed by section 11 and the feature catalogue. Nested tables and multiple simultaneous local carets remain explicit product decisions. Previous-process Undo is separate from required persistent version history. Toolbar formatting still waits for qualified composition settlement.")
    source = replace_once(source, "| `editor-pagination` |", "| `editor-review`, `editor-sync-contracts` | Experimental authored-change/review/replication interfaces (section 11; C0/R0) | Independent mutable document, account/provider SDKs in core |\n| `editor-pagination` |")
    source = replace_once(source, "Build these phases in order. A failed gate blocks the affected layer, while independent core work may continue.", "These phases remain the library engineering gates. PRODUCT-ROADMAP.md supplies the current product dependency order: M1/C0/S0 and GP0 investigate cross-cutting risks before public API/schema freeze and broad dependent construction. A failed gate blocks the affected layer, while independent core work may continue.")
    source = replace_once(source, "Complete v5.1 library delivery additionally requires GL and the applicable GP0–GP3 gates in section 10; G8 alone does not qualify printing.", "Complete library/product delivery additionally requires GL, applicable GP0–GP3, U0–U5, S0/C0/C1/R0/I0 and the product feature/platform matrix. G8 alone qualifies neither printing nor collaboration.")
    # Keep the previous scope amendment as explicitly dated historical authority;
    # its selected requirements still apply, but its file pointers are not current.
    marker = "## 10. Owner-approved library and paged-output scope amendment"
    before, section10 = source.split(marker, 1)
    section10 = section10.replace("\n## ", "\n### ")
    source = before + marker + "\n\nThis is the preserved v5.1 amendment. Its scope requirements continue to apply; section 11 and the v5.2 document pointers above supersede its former current-version status and any narrower application exclusions.\n" + section10
    amendment = read("v5.2-integration-amendment.md")
    body = amendment[amendment.index("17 October 2026."):].replace("\n## ", "\n### ").replace("\n### Scope changes", "\n#### Scope changes")
    source += "\n\n## 11. Urdu application and library integration amendment\n\n" + body
    write("plan-v5.2.md", source)

    header = """# Urdu-first document application and editor library — standalone replan v5.2.0

17 October 2026. This package contains the complete product roadmap, feature catalogue, Urdu acceptance plan and consolidated library plan. Its top-level deliverable is the owner's Urdu-first Google Docs–level application powered by a reusable open-source Compose Multiplatform WYSIWYG library. The prototype is a test harness.

Status: PLANNED / UNQUALIFIED for new work. This is not a new frozen correctness claim. The original v5.0.0 and v5.1 artifacts remain unchanged. Existing local-editing contracts are retained where applicable; the named integration experiments must settle unresolved schema, collaboration, review and output details before dependent production commitments. No production code was changed by generating this package.

Authority: the product roadmap controls sequencing, the feature catalogue controls tracked product scope, the Urdu acceptance plan controls language qualification, and section 11 of the library plan controls the new integration boundaries. Historical evidence inside the inherited plan retains its original scope. The latest direct owner instruction takes precedence.

"""
    package = header
    for label, path in [("Product roadmap", "PRODUCT-ROADMAP.md"), ("Feature catalogue", "FEATURE-CATALOG.md"), ("Urdu acceptance", "URDU-ACCEPTANCE.md"), ("Complete library plan", "plan-v5.2.md")]:
        package += f"\n\n---\n\n# {label}\n\n" + read(path)
    write("editor-v5.2-standalone.md", package)
    paths = ["PRODUCT-ROADMAP.md", "FEATURE-CATALOG.md", "URDU-ACCEPTANCE.md", "v5.2-integration-amendment.md", "plan-v5.2.md", "editor-v5.2-standalone.md", "planning/build_v5_2_docs.py"]
    manifest = {
        "version": "5.2.0", "date": "2026-10-17",
        "status": "PRODUCT_REPLAN_INTEGRATION_DESIGN_UNQUALIFIED",
        "productRoadmap": paths[0], "normativeLibraryPlan": "plan-v5.2.md",
        "standalone": "editor-v5.2-standalone.md",
        "historicalManifests": ["v5-freeze-manifest.json", "v5.1-spec-manifest.json"],
        "implementationChanged": False, "published": False,
        "newGateStatus": {key: "NOT_RUN" for key in ["S0", "C0", "C1", "R0", "I0", "U0", "U1", "U2", "U3", "U4", "U5"]},
        "previousGates": "G0-G8, GL and GP0-GP3 retain their actual evidence; no advancement from this replan",
        "hashes": {path: digest(path) for path in paths}
    }
    write("v5.2-plan-manifest.json", json.dumps(manifest, indent=2) + "\n")
    # Documentation integrity checks, not software qualification.
    assert all(read(path) in package for path in ["PRODUCT-ROADMAP.md", "FEATURE-CATALOG.md", "URDU-ACCEPTANCE.md", "plan-v5.2.md"])
    assert "collaboration, merged/nested tables" not in source
    verify_manifest("v5.2-plan-manifest.json")
    print(f"PASS historical integrity: {original_count} v5.0 files, {prior_count} v5.1 files")
    print("PASS consolidated package and v5.2 hashes; no production tests executed")


if __name__ == "__main__":
    main()
