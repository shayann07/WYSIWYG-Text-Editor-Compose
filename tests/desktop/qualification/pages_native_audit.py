"""Read-only independent audit of the owned pages native walkthrough.

UI actions are performed separately. This audit checks their captured files,
history and published page fields; it neither drives UI nor calls a Kotlin codec.
Synthetic setup, Unicode paste and genuine OS IME evidence remain distinct.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import traceback

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "settings.gradle.kts").is_file())
sys.path.insert(0, str(ROOT / "tooling/verification"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from structured_process_checks import decode_v6
from pages_v9_oracle import check_pages, expected_pages_text

PASTED = "آزمائش العربية English 42"
HEADER = "اردو صفحات — العربية English"
FOOTER = "صفحہ native"


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def rows(root):
    path = root / "protocol.jsonl"
    assert path.stat().st_size <= 32 * 1024 * 1024
    values = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert 0 < len(values) <= 512
    return values


def row_at(values, label):
    matches = [row for row in values if row["label"] == label]
    assert len(matches) == 1, "Missing or ambiguous native label: " + label
    return matches[0]


def window_at(values, label):
    windows = row_at(values, label)["response"]["windows"]
    assert len(windows) == 1, "Expected one isolated owned window: " + label
    return windows[0]


def payload(root, window):
    path = (root / window["payloadFile"]).resolve()
    assert path.is_relative_to(root)
    raw = path.read_bytes()
    assert sha256(raw) == window["payloadSha256"]
    visible = (root / window["visiblePayloadFile"]).resolve()
    assert visible.is_relative_to(root) and visible.read_bytes() == raw
    assert window["marked"] is None
    document = decode_v6(raw)
    assert [char for char, _ in document["units"]] == window["units"]
    return raw, document


def appended_document(baseline, text):
    """Independent expected splice: semantic FF plus inherited plain text at EOF."""
    document = copy.deepcopy(baseline)
    original_length = len(baseline["units"])
    raw_units = [int.from_bytes(text.encode("utf-16be")[i:i + 2], "big")
                 for i in range(0, len(text.encode("utf-16be")), 2)]
    document["text"] += "\f" + text
    document["units"] += [(12, 1)] + [(char, 0) for char in raw_units]
    document["styles"] += [copy.deepcopy(baseline["styles"][-1]) for _ in range(1 + len(raw_units))]
    document["pageBreaks"].append(original_length)
    document["allParagraphs"].append(dict(index=len(baseline["allParagraphs"]), start=original_length + 1,
                                          end=original_length + 1 + len(raw_units),
                                          properties=copy.deepcopy(baseline["allParagraphs"][-1]["properties"])))
    document["pagination"].append(dict(breakBefore=False, keepWithNext=False, keepLines=False, widows=2, orphans=2))
    return document


def check_history(window, undo, redo=0):
    assert window["undo"] == undo and window["redo"] == redo
    assert not window["busy"] and window["admission"]


def page_fields(window, document):
    plan = window["pagePlan"]
    assert plan["pages"] == window["pages"] and plan["pages"] > 0
    width, height = ((1056.0, 816.0) if document["pageSettings"]["paper"] else
                     (210 / 25.4 * 96, 297 / 25.4 * 96))
    assert abs(plan["width"] - width) < .001 and abs(plan["height"] - height) < .001
    assert plan["fontKey"] == "noto-nastaliq:98a4787f34eb6fde57fb9a1121a8f216301196ab0da98ea110cad581be8abbcd"
    running = document["running"]
    zero = [ord("0"), 0x660, 0x6F0][running["digits"]]

    def number(n):
        return "".join(chr(zero + int(char)) for char in str(n))

    expected = []
    for page in range(plan["pages"]):
        if page == 0 and running["differentFirst"]:
            continue
        for name in ("header", "footer"):
            flow = running[name]
            if flow is None:
                continue
            field = "" if flow["field"] == 0 else number(page + 1)
            if flow["field"] == 2:
                field += " / " + number(plan["pages"])
            text = flow["text"] + (" " if flow["text"] and field else "") + field
            expected.append((page, flow["id"], text))
    actual = [(item["page"], item["flow"], item["text"]) for item in plan["running"]]
    assert actual == expected, "Derived per-page logical running text/field ordering differs"
    assert all(0 <= item["top"] < plan["height"] for item in plan["running"])
    coverage = {}
    last_page = -1
    for paragraph, first, last, page, _, height in plan["fragments"]:
        assert 0 <= paragraph < len(document["allParagraphs"]) and 0 <= page < plan["pages"]
        assert page >= last_page and first <= last and height > 0
        assert first == coverage.get(paragraph, -1) + 1
        coverage[paragraph] = last
        last_page = page
    assert set(coverage) == set(range(len(document["allParagraphs"])))
    return dict(pages=plan["pages"], runningInstances=len(actual), paragraphsRepresented=len(coverage),
                scope="Exact fields, page size, paragraph presence and contiguous recorded line intervals; final shaped line counts are separately tested")


def audit(root, reopened_root=None, reopened_label="fresh-open-native-pages"):
    values = rows(root)
    snapshots = {}
    documents = {}
    raws = {}
    required = ["start", "ordinary-bold", "end-before-pagebreak", "ctrl-enter", "mixed-writing-selection",
                "undo-to-original", "redo-cross-page-selection", "running-cancel-preserved", "running-applied",
                "running-undone", "running-redone", "print-cancelled", "paper-applied", "saved-native-pages"]
    for label in required:
        snapshots[label] = window_at(values, label)
        raws[label], documents[label] = payload(root, snapshots[label])
    baseline = documents["start"]
    check_pages(baseline)
    assert baseline["text"] == expected_pages_text() and len(baseline["units"]) == 3970
    assert len(PASTED.encode("utf-16be")) // 2 == 25
    for label in ("ordinary-bold", "end-before-pagebreak", "undo-to-original"):
        assert raws[label] == raws["start"]
    assert snapshots["end-before-pagebreak"]["selection"] == [3970, 3970]
    assert snapshots["ctrl-enter"]["selection"] == [3971, 3971]
    assert snapshots["mixed-writing-selection"]["selection"] == [3996, 3994]
    assert snapshots["redo-cross-page-selection"]["selection"] == [3996, 0]
    assert documents["ctrl-enter"] == appended_document(baseline, "")
    edited = appended_document(baseline, PASTED)
    assert documents["mixed-writing-selection"] == edited
    for label in ("redo-cross-page-selection", "running-cancel-preserved", "running-undone"):
        assert documents[label] == edited
    running = copy.deepcopy(edited)
    running["running"]["differentFirst"] = False
    running["running"]["header"]["text"] = HEADER
    running["running"]["footer"]["text"] = FOOTER
    for label in ("running-applied", "running-redone", "print-cancelled"):
        assert documents[label] == running
    final = copy.deepcopy(running)
    final["pageSettings"] = dict(paper=1, landscape=True, marginMm=20)
    assert documents["paper-applied"] == documents["saved-native-pages"] == final
    histories = {"start": (0, 0), "ordinary-bold": (0, 0), "end-before-pagebreak": (0, 0), "ctrl-enter": (1, 0),
                 "mixed-writing-selection": (2, 0), "undo-to-original": (0, 2), "redo-cross-page-selection": (2, 0),
                 "running-cancel-preserved": (2, 0), "running-applied": (3, 0), "running-undone": (2, 1),
                 "running-redone": (3, 0), "print-cancelled": (3, 0), "paper-applied": (4, 0), "saved-native-pages": (4, 0)}
    for label, (undo, redo) in histories.items():
        check_history(snapshots[label], undo, redo)
    owner = snapshots["start"]["unfinished"]["owner"]
    assert all(w["unfinished"]["owner"] == owner for w in snapshots.values())
    saved = root / "native-pages.astra"
    assert saved.read_bytes() == raws["saved-native-pages"]
    assert Path(snapshots["saved-native-pages"]["path"]).resolve() == saved and not snapshots["saved-native-pages"]["dirty"]
    field_results = {label: page_fields(snapshots[label], documents[label]) for label in
                     ("start", "ctrl-enter", "mixed-writing-selection", "running-applied", "paper-applied", "saved-native-pages")}
    assert [snapshots[label]["pages"] for label in ("start", "ctrl-enter", "mixed-writing-selection", "running-applied", "paper-applied")] == [10, 11, 11, 11, 13]
    reopen = dict(status="NOT RUN", reason="No separate-process native file-open receipt supplied")
    if any(row["label"] == "reopened-native-pages" for row in values):
        same = window_at(values, "reopened-native-pages")
        assert same["unfinished"]["owner"] == owner and same["undo"] == 4
        reopen["misleadingOldLabel"] = "reopened-native-pages retained the original owner/history and is not a fresh reopen"
    if reopened_root is not None:
        fresh_rows = rows(reopened_root)
        fresh_windows = row_at(fresh_rows, reopened_label)["response"]["windows"]
        matching = [window for window in fresh_windows if window.get("path") and
                    Path(window["path"]).resolve() == saved]
        assert len(matching) == 1, "Expected exactly one freshly opened window for the saved native file"
        fresh = matching[0]
        raw, decoded = payload(reopened_root, fresh)
        assert row_at(fresh_rows, reopened_label)["pid"] != row_at(values, "saved-native-pages")["pid"]
        assert fresh["unfinished"]["owner"] != owner and not fresh["dirty"] and fresh["path"]
        assert Path(fresh["path"]).resolve() == saved
        check_history(fresh, 0)
        # A new owner deliberately allocates fresh running-flow UUIDs (EditorEngine.initial.running.fresh).
        # Compare every persisted value, allowing only that documented identity remapping.
        normalized = copy.deepcopy(decoded)
        remapped = {}
        for name in ('header', 'footer'):
            if final['running'][name] is not None:
                previous = final['running'][name]['id']
                current = decoded['running'][name]['id']
                assert current != previous
                remapped[name] = dict(previous=previous, current=current)
                normalized['running'][name]['id'] = previous
        assert normalized == final
        assert saved.read_bytes() == raws['saved-native-pages']
        reopen.update(status="PASS", label=reopened_label, root=str(reopened_root), payloadSha256=sha256(raw),
                      freshRunningFlowIdentities=remapped, sourceFileUnchanged=True, pageFields=page_fields(fresh, decoded))
    bridge = json.loads((root / "bridge-source.json").read_text(encoding="utf-8"))
    return dict(status="PASS", scope="Independent exact-data audit of recorded real desktop controls; no additional UI actions",
                buildManifestSha256=bridge["buildManifestSha256"], bridgeSourceSha256=bridge["sha256"],
                initialUnits=3970, addedPageBreakUnits=1, pastedUnicodeUnits=25, finalUnits=3996,
                savedFileSha256=sha256(saved.read_bytes()), baselinePayloadSha256=sha256(raws["start"]),
                histories=histories, pageFieldChecks=field_results, freshNativeReopen=reopen,
                evidenceLevels=dict(navigationAndShortcuts="Real injected key events", text="Tool Unicode paste, exact code points checked",
                                    osIme="NOT RUN", physicalPrint="NOT RUN", printDialog="Native dialog Cancel; no printer job submitted"),
                preservation="Prior inline formatting, paragraph metadata, semantic list/link identities, image positions/attributes and embedded resource identities/bytes preserved",
                identityBoundary="Private codec does not encode opaque runtime unit/block IDs; those are not independently asserted here",
                sourceSha256=sha256(Path(__file__).read_bytes()), protocolSha256=sha256((root / "protocol.jsonl").read_bytes()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--reopened-root", type=Path)
    parser.add_argument("--reopened-label", default="fresh-open-native-pages")
    parser.add_argument("--result", type=Path)
    args = parser.parse_args()
    assert __debug__, "Qualification requires assertions enabled"
    root = args.root.resolve()
    target = args.result or root / "pages-native-audit.json"
    assert not target.exists(), "Preserve previous receipts; use a new --result filename"
    try:
        receipt = audit(root, args.reopened_root.resolve() if args.reopened_root else None, args.reopened_label)
    except Exception as failure:
        receipt = dict(status="FAIL", error=repr(failure), traceback=traceback.format_exc())
        target.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raise
    target.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: receipt[key] for key in ("status", "buildManifestSha256", "finalUnits", "savedFileSha256", "freshNativeReopen")}))


if __name__ == "__main__":
    main()
