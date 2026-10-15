# Executable investigation artifacts

These are research experiments, not a production editor and not a claimed breakthrough. The decisions and prior-art findings are recorded in `../progress.md`.

Run from the project root with Node.js. This pass used Node v24.19.0. Installed third-party packages are isolated in `research/runtime`; their exact resolved versions and integrity values are in its lockfile. Installation used `--ignore-scripts`.

```powershell
npm.cmd ci --prefix research/runtime --ignore-scripts --no-audit --no-fund
node research/grapheme-style-roundtrip.mjs
node research/existing-attribution-control.mjs
node research/bidi-context-tree.test.mjs
node research/bracket-guard-falsification.mjs
node research/bracket-continuation-falsification.mjs
node research/bracket-suffix-index.test.mjs
node research/bracket-block-index.test.mjs
node research/bidi-block-context.test.mjs
node research/bidi-block-context-scaling.test.mjs
node research/composition-path-dependence.mjs
node research/composition-history-probe.mjs
```

- `grapheme-style-roundtrip.mjs` demonstrates information loss in the explicit normalization policy from v1. Its alternative retains character attributes and derives cluster presentation. Sixteen style combinations passed. This does not reproduce a released Compose bug.
- `existing-attribution-control.mjs` verifies that an existing ProseMirror model already preserves the essential attribute round trip. This is evidence against novelty, not a proposed dependency choice for the final engine.
- `bidi-context-tree.mjs` implements a restricted, context-dependent bracket tree. The test compares every queried level with `bidi-js@1.1.0`, which declares Unicode 13 support. It tests fixed-length substitutions and reconstructs the initial bracket topology from scratch.
- `bidi-context-tree.test.mjs` exercises exhaustive short inputs, seeded mutations, malformed and excessive nesting, and a 300,001-character adversary. The recorded first run is in `bidi-first-run.json`; new runs will have different timing measurements.
- `bracket-guard-falsification.mjs` disproves a proposed bound on bracket-topology repair. Its alternating malformed-bracket adversary forces linear traversal despite an incoming stack of depth one. This is a negative result, not a proposed production algorithm.
- `bracket-continuation-falsification.mjs` tests a separate jump-and-resume strategy. Its restricted summaries pass 12,300 reference checks, but recursively constructing the requested suffix summaries still costs linear work on the adversary, including after a one-character edit. A bound on jumps within one invocation does not bound recursively generated invocations.
- `bracket-suffix-index.mjs` supplies suffix information through an explicit right-to-left recurrence, with 111,796 transfer comparisons and 43,758 direct summary checks. Its static query avoids the earlier recursive cost, but the standalone index can still rebuild a full prefix after substitution.
- `bracket-block-index.mjs` uses that transfer operation for variable-length edits and individual bracket-pair queries. It passed 183,301 pair checks across 6,400 edits and a 1,000-insertion fragmentation regression. Its operation bounds, measured costs, memory change, and remaining scope are in `bracket-suffix-index.md`. This is an ASCII bracket component with a direct model oracle, not a complete bidi implementation or a breakthrough claim.
- `bracket-unicode-oracle.test.mjs` cross-checks the block index against Unicode's unmodified Java reference. See `unicode-oracle/README.md` for compilation first. It compares 1,967 non-overflow fixtures and checks a documented overflow difference between that Unicode 8.0 reference and the current rule. It does not certify current full-Unicode behavior.
- `bidi-block-context.mjs` extends the block experiment to N0-N2 direction windows over classified L/R/neutral tokens and ASCII brackets. Its direct-model suite passed 131,820 point checks and 12,584 windows across 3,000 edits. `bidi-block-context-oracle.test.mjs` checks 1,749,721 directions against the Unicode 8 Java reference; compile `DirectionOracle.java` first as documented in `unicode-oracle/README.md`. Overflow requests are excluded from old-reference agreement, not silently counted as passing.
- `bidi-block-context-scaling.test.mjs` includes variable-length topology edits, malformed/deep strings, late overflow, and a nonlocal-direction-change fixture. Its JSON reports build cost, typed-array memory, query work, and sampled timing against bidi-js. The comparison has different input/preprocessing/capability contracts and is not an end-to-end editor result.
- `composition-path-dependence.mjs` demonstrates lost marks after an opaque candidate detour under a specified common-prefix/suffix policy, using actual ProseMirror state operations. It does not exercise browser IME handling. Snapshot/replay is recorded as a known technique, not a new architecture.
- `composition-history-probe.mjs` tests a stated integration policy with ProseMirror history 1.5.1: candidate rewrites copy inherited marks but stay outside history. Ordinary mapped undo then leaves those marks on replacement text, while snapshot rollback restores transient candidate text. It includes a passing no-rewrite control and a partial-prefix-commit case. This is not a claim about ProseMirror's browser composition handling or a failure of its documented history contract.

The bidi result compares a whole-paragraph baseline with a maintained index plus selected queries. It does not imply that emitting all changed levels, reshaping text, drawing glyphs, or building the initial index is sublinear. The prototype rejects unsupported character classes and topology-changing edits. It is not a full Unicode implementation.

The earlier plans are unchanged. This folder does not replace them with a fourth plan.

`compose-probe/README.md` documents a separately executed JVM probe of the published Compose Desktop 1.12.1 artifact. It reproduces style loss on undo and loss of a synthetically seeded composition after style mutations. Its Maven/Kotlin sources and recorded output are in that directory. It is not a device IME test or a candidate solution.

`compose-probe/src/main/kotlin/HiddenStateProbe.kt` constructs independently recoverable hidden `InsideOnly` span attributes. From `research/compose-probe`, run the same Maven command with `'-Dprobe.mainClass=HiddenStateProbeKt'`. The recorded `hidden-state-probe-output.txt` contains 8,500 passing queries. The construction gives a worst-case quadratic information requirement relative to text length when a quadratic number of arbitrary overlapping spans is allowed. It is a constraint on exact span semantics, not a replacement editor or a performance benchmark.
