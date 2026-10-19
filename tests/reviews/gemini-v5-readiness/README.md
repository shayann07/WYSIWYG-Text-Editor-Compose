# Gemini Independent v5 Freeze-Readiness Review Artifacts

This directory (`reviews/gemini-v5-readiness/`) contains the independent systems architecture review, executable validation probes, counterexample reproductions, minimal general reference-model repairs, and normative v5 specification amendments for baseline commit `b66b790068908e45dd8642d3410bc53f609c700f`.

## Files in This Directory

- `v5-freeze-readiness-report.md` — Complete independent review report, executive freeze verdict, prioritized findings (`F01`–`F11`), exact normative replacement text (`A01`–`A11`), benchmark measurements, and remaining qualification matrix (`G0`–`G8`).
- `repaired-document-model.mjs` — Minimal general repairs (`RepairedDocumentModel`, `repairedReconstruct`, `repairedUnitClusters`, `repairedUserRange`, `repairedCloneFresh`, `repairedClosedSlice`, `repairedTextLocation`) validating Amendments `A01`–`A08` and `A10` without modifying baseline files.
- `probe-interactions-and-unicode.mjs` — Reproduces `25` independent counterexamples (`F01A` through `F10A_2`) on the unmodified baseline (confirming all `25/25` fail on baseline), verifies all `25/25` pass on `RepairedDocumentModel` / `repairedTextLocation`, replays all `35/35` inherited integrated cases, and runs `60` seeded multi-step interaction fuzz traces with full `Undo`/`Redo` stack exhaustion.
- `probe-hierarchical-storage-and-recovery.mjs` — Implements and benchmarks `HierarchicalVersionedIndex` (persistent B+ sequence + persistent AVL `TokenMap`/`ParentMap` with 2-component monoid measure `(contentLen, leafCount)`) on `1,000,000` UTF-16 units / `10,000` blocks and depths `d in {1, 4, 8, 16, 32}`, plus `DurableRecoveryJournal` (binary UTF-16LE `editor-document/1` round-trip, CRC32C torn-write truncation, and dual `(Dc, Dv)` multi-cell composition crash recovery).
- `probe-shaper-and-performance.cpp` — Standalone C++20 probe (`g++ -std=c++20 -O2`) executing the exact `OneLineShaper::finish` and `addFullyResolved` mechanics from `research/native-layout-source/OneLineShaper-m150.cpp`, `OneLineShaper-patched.cpp`, and `OneLineShaper-main.cpp`.
- `probe_chromium_dom.py` — Real headless Chromium (`154.0.8037.92`) Selenium WebDriver probe testing `contenteditable` composing `Text`-node preservation (`isSameNode` & `Selection`), HTML5 `DOMParser` fragment parsing (`<td>`, `<li>`), and HarfBuzz ligature splitting across DOM `<span>` boundaries (`👩‍💻`).
- `run-all.mjs` — Unified runner that verifies all 6 baseline SHA-256 digests in `validation/codex/run-20261015-19/publication-hashes.json`, executes all 4 probe suites, and writes `results.json`.
- `results.json` — Machine-readable execution output from `node reviews/gemini-v5-readiness/run-all.mjs`.

## Reproduce All Probes

```bash
node reviews/gemini-v5-readiness/run-all.mjs
```
