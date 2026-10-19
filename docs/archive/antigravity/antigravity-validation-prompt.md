# Antigravity validation prompt

You are the independent validation lead for a production WYSIWYG document engine on Compose Multiplatform. Validate the implementation specification in this repository. We have stopped chasing novelty. Optimize for correctness, feasibility, maintainability and measured performance.

Workspace: `D:\Work\WYSIWYG-Text-Editor-Compose`.

Read `plan-v4.md` first. It is the current normative plan. Read `plan-v1.md` for the superseded baseline, then `progress.md`, `research/README.md`, `research/compose-probe/README.md`, and `research/compose-benchmark-verdict.md` for evidence and limitations. Inspect the exact source and output files relevant to each claim. The missing `deep-research-report-chatgpt.md` is explicitly waived; do not ask for it. Do not treat old prose or an existing PASS message as fresh proof.

Your job is to falsify incorrect assumptions and close concrete specification gaps, not praise the plan, invent a new architecture, or restart algorithmic novelty research. Do not adopt the abandoned bracket index or conditional-layout graph. Do not claim the native Skia patch is validated: it is not.

## Authority and boundaries

You may inspect the repository and official sources, execute existing tests, add independent validation tests and minimal capability hosts, and make narrowly justified corrections to `plan-v4.md`. Preserve prior plans and raw evidence. Do not overwrite existing measurement files; write fresh results under `validation/antigravity/<run-id>/`. Inspect commands before running them. Do not launch the old 196K repeated layout benchmark by default; first run bounded fixtures with documented process timeouts and extend only when justified. Do not treat a timed-out run as a measured completion.

Do not implement the entire editor, silently change product scope, install production workarounds, publish anything, or delete historical research. A conflicting core policy needs a concrete counterexample and a documented replacement decision; distinguish a consistency correction from a material product change requiring owner review. You may prepare a proposed revision for the latter without presenting it as approved.

## Work in this order

### A. Static adversarial audit

Create a requirement matrix covering all nine plan sections, invariants I01–I17, regression cases R01–R16 and gates G0–G8. For each, record the precise claim, existing evidence, new test needed, status, and platform scope. Use only these status labels: PASS, FAIL, NOT RUN, INCONCLUSIVE, DESIGN POLICY. Source inspection, model tests, headless library tests and real native interaction are different evidence classes.

Check especially:

- Lossless raw UTF-16 storage versus native code-point deletion contracts, drawable caret behavior and export conversion.
- Constituent formatting through grapheme joins, explicit cluster formatting, effective rendering attributes and DOM projection equality.
- The immutable-baseline candidate reconciliation algorithm: repeated text, prefix/suffix ties, empty candidates, valid/invalid surrogates, native ranges inside the island, range expansion and attribution-epoch resets.
- Partial commit freezing, later cancellation, reconversion, and command targets following final candidate extents.
- Formatting queued after composition and exact attributed undo; no phantom composition commits, duplicate input, timeout-based completion or hidden selective-history assumptions.
- Structural native admission: atom/cell/container boundaries, input already mutated noncancelably, cross-block replacement and recovery. Find any place where the plan promises native plaintext equivalence but its structural reducer cannot provide it.
- Epoch identity, callback sequencing, retransmissions, reentrancy, batch notification depth, failed-transaction rollback and typed revision boundaries.
- Position maps, selection direction, deleted anchors, restoration of IDs and history branches.
- ProseMirror candidate adoption, disabled independent history, composing-node continuity, JS/Wasm synchronization and effective-versus-raw attribution.
- Geometry validity, coordinate transforms, offscreen estimates, accessibility and asynchronous font/layout publication.
- Durability acknowledgements, raw-unit persistence, journal idempotency and active-composition recovery.

If the specification is contradictory, provide the smallest failing trace and exact conflicting sections before proposing a correction. Do not paper over ambiguity by making the test expect whatever the implementation does.

### B. Independently execute relevant existing evidence

Inspect and run the grapheme, existing-attribution, candidate path-dependence and composition-history fixtures. Run the relevant published Compose state and Unicode probes where dependencies/tooling are available. Record exact commands, versions, exit codes, stdout/stderr, timings, and source hashes. A test that successfully reproduces a defect is baseline evidence, not a repaired-product PASS.

Use the clean Compose layout benchmark only as a bounded replication when useful. Native bidi construction and Compose intrinsics are different scopes; do not divide them into an asserted stage percentage. The initial huge run had a shutdown error. The standalone patched-native build has no passing validation. Preserve these distinctions.

### C. New independent executable specification tests

Implement a deliberately small reference model of the NEW rules, not a full editor. Use plain strings, explicit attributes and ordinary trees. Add hand-calculated expected fixtures and deterministic property tests. Do not copy production/research reconciliation or mapping code into both candidate and oracle.

At minimum execute R01–R10 and R14 at the model/headless level where feasible. Test multiple candidate paths yielding the same final string within an epoch, then test that explicit partial-commit reset changes the guarantee exactly as specified. Verify committed prefix attributes cannot change from later suffix updates. Verify selection/structure/history, not only final plain text. Generate and shrink failures; save seeds and minimal traces.

Test a protocol model for reordered lifecycle notifications, callbacks after closure, duplicate internal delivery, reentrant reads, queued application commands, unavailable settlement, cancellation of pending commands and disposal. A finite model is bounded evidence, not proof of every platform.

### D. Platform feasibility and current dependency verification

Build the smallest public-API integration spike needed to verify G0, rather than a sample that bypasses the actual bridge contract. Verify exact compatible artifact/toolchain versions using official releases, metadata and compilation. Do not assume v1's Kotlin or Unicode version claims are current facts. Record dependency hashes/lockfiles and native Unicode/font differences where observable.

Exercise actual available platforms and explicitly list missing environments. Android/iOS native keyboards, Desktop OS IMEs, browser IMEs and assistive technologies require their own runtime evidence. Windows headless tests do not qualify iOS or Android. Synthetic browser events do not qualify real composition. For unavailable environments, produce precise runnable/manual procedures with expected results and mark NOT RUN. Never invent device execution or waive a gate because you lack the device.

For settlement, identify the real mechanism and its limitations in each available adapter. Merely observing `compositionend`, calling `unmarkText`, sleeping, or waiting one event-loop turn is not automatically a valid fence. For accessibility, actually select/edit/read back content, including offscreen text and tables; a spoken title is insufficient.

### E. Report and close out

Save these artifacts at the project root:

1. `antigravity-validation-report.md`: concise verdict; critical findings first; exact evidence links; fixes made; residual risks; source/API assumptions; PASS/FAIL/NOT RUN/INCONCLUSIVE counts; separate model, library and platform evidence.
2. `antigravity-validation-matrix.csv`: one row per invariant, regression case, phase gate and material platform claim, with status, environment, evidence path and next action. Add subrows where a gate differs by platform.
3. `antigravity-plan-diff.md`: every proposed/applied normative plan correction, its counterexample, rationale, affected tests and whether it requires owner approval.
4. A reproducible `validation/` runner/readme with pinned dependencies, bounded commands and archived outputs. Never replace original research outputs.

If safe, unambiguous specification corrections are established, update `plan-v4.md` with a revision note and traceable test references. Material choices stay explicitly proposed until accepted. Keep one authoritative plan; do not create several competing replacement architectures.

The final response must answer:

- Is the plan internally consistent enough to start Phase 0/1?
- Which concrete defects did you find and correct?
- What did you execute, on exactly which environments?
- Which claims remain untested or failed?
- What is the next implementation phase permitted by the evidence?

Use an honest verdict such as “ready to begin implementation with listed gates” or “blocked by these specific contradictions.” Do not say “everything validated,” “production-ready,” or “all platforms supported” unless the corresponding implementation and real runtime gates were actually exercised and passed. Documentation approval is not product validation.
