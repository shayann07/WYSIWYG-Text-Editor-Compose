# Archive reproducibility disposition — issue 28, 23 October 2026

This index corrects navigation externally. It preserves the original experiment
sources, READMEs, failure logs and hash-pinned review packages. A relocated path
is not proof that a probe is portable or has been reproduced. No rejected bidi,
layout or PDF research was restarted in this repair batch.

| Cited experiment | Classification | Present dependency / evidence / next action |
|---|---|---|
| `bracket-unicode-oracle.test.mjs` | platform-specific; NOT RUN | Windows `java.exe` path and compiled PairOracle classes; supplied upstream is a Unicode 8-era ASCII-bracket oracle, not modern UBA qualification. Reproduce only on an explicitly prepared Windows/JDK environment. |
| `bidi-block-context-oracle.test.mjs` | platform-specific; NOT RUN | Same Java executable/class prerequisite, DirectionOracle; compares a narrow parity subset. A path replacement alone would not qualify other systems. |
| `unicode-oracle/README.md` compilation | historical/not reproduced | Old absolute JDK23 path and pre-relocation output locations. Preserve as a dated recipe; classes are not clean-clone artifacts. |
| `bidi-context-tree.test.mjs` | dependency-blocked; NOT RUN | Imports ignored runtime/node_modules/bidi-js by path. Requires the archived runtime lock installation, then independent expected-state checks. |
| `bidi-block-context-scaling.test.mjs` | dependency-blocked; NOT RUN | Same bidi-js prerequisite. Old timing results are environment-specific, not current thresholds. |
| `composition-history-probe.mjs` | dependency-blocked; NOT RUN | Archived ProseMirror model/state/history tree required. Historical reference, not production engine ownership/history proof. |
| `composition-path-dependence.mjs` | dependency-blocked; NOT RUN | Archived ProseMirror model/state installation; do not infer current native behavior from it. |
| `existing-attribution-control.mjs` | dependency-blocked; NOT RUN | Same archived dependency tree; no fresh execution claimed. |
| native-layout `build-probe.cmd`, `build-patched.cmd` | platform-specific and dependency-blocked; NOT RUN | Hard-coded MSVC setup, absent ignored Skia static libraries, C++17 flags despite recorded C++20 countl_zero/popcount failures. Retain the failure; changing flags is not proof of a successful matching build. |
| compose-probe Maven/layout profile | platform-specific and dependency-blocked; NOT RUN | Old ignored Maven location; no wrapper. Pinned Skiko Windows-x64 runtime; historical access violation is not repaired by relocation. No new dependency downloads attempted. |
| Gemini `probe_chromium_dom.py` | dependency-blocked; NOT RUN | Selenium/browser/driver versions not pinned. Review package is immutable; this note remains external. Do not install arbitrary latest Selenium and call old results reproduced. |
| Maintained V6 witnesses | reproducible; PASS scoped model evidence | `node tooling/verification/v6.mjs`: 16 groups freshly passed at main e30c984 and contributor SHAs. It preserves historical imports using relocation hooks. |
| Full inherited/V6 comparison | reproducible on dependent issue 17 branch | `python tooling/verification/reproduce_inherited.py`: 25 + 16 groups freshly passed on that branch with transitive-integrity checking. That command is unavailable on unmerged main; do not claim otherwise. |

Paths without a prefix in the first column are beneath `tests/archive/research/`.
The Gemini script is beneath `tests/reviews/gemini-v5-readiness/`. Resolve other
historical names with `tooling/docs/doc_paths.py` / `docs/relocations.json`.

## Maintained commands (repository root)

```sh
python tooling/docs/validate_docs.py
node tooling/verification/v6.mjs
python tooling/verification/test_verification.py
```

Install the existing locked witness dependency with
`npm ci --ignore-scripts --prefix tests/suites/integration/c0` first. Windows uses
JDK21, Python3.12 and Node24; use the documented D-drive environment for Gradle.
The archive's separate `runtime/package-lock.json` is not that maintained
dependency installation. No archived experimental command is part of a release
gate merely because this index lists it.

Contributor PR34 owns stale active commands/link coverage in `tests/suites/README.md`
and related navigation. This issue does not duplicate that PR or modify historical
README bytes. Remaining reproduction would require explicit platform/dependency
preparation and fresh scoped receipts; it is not necessary to revive rejected
experiments to close the misleading-navigation part of this issue.
