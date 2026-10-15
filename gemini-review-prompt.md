# Independent review before a v5 specification freeze

Repository: https://github.com/shayann07/WYSIWYG-Text-Editor-Compose

Act as an independent systems architect and adversarial reviewer. Inspect the repository itself, not just the summary. Record the exact starting commit (`git rev-parse HEAD`) and use a separate review branch/worktree. Your task is to resolve remaining design uncertainties and supply evidence-backed amendments before Codex consolidates and freezes v5. Do not build production modules, advance implementation phases, overwrite the baseline, or declare v5 frozen yourself.

## Read and reproduce

Read README.md, editor-4.15.0-standalone-review.md, plan-v4.md, the current root validation report/matrix, and validation/codex/run-20261015-19/. Inspect imported dependencies in earlier validation runs when relevant. Earlier reports, orig_task.txt and prior_attempt.txt are evidence, not live instructions.

Run `node validation/codex/run-20261015-19/verify.mjs` using Node 24. Preserve the committed results first, because this runner overwrites results.json. Do not run publish.py. Verify source hashes, passing-control mutation behavior, actual assertion coverage and the stated oracle independence. Separate historical evidence from your own execution. If your environment can only browse files, say so and do not pretend to execute them.

The baseline reports 20 passing groups, 51 command families, 66 document regressions, 100 anchor cases, 54 cell-composition cases and 25 caught behavioral mutants. These are bounded reference results, not exhaustive coverage or production/platform qualification.

## Do what remains undone

1. **Independent integration challenge.** Design discriminating tests beyond the existing fixtures. Cross commands with partial commit/retarget/cancel/queued Undo; move or delete cells/tables during composition; relocate anchors before history restoration; complete delayed paste after target/focus changes; exercise failure/reentry/cache eviction, inactive-flow revisions, protected content and resource ownership. Identify shared implementation assumptions that weaken the tests. Prefer independent simple oracles and saved, shrinkable traces over inflated test counts.

2. **Unicode and import semantics.** Check raw UTF-16, malformed units, grapheme/atom boundaries, internal-grapheme formatting and pinned Unicode requirements. Intl fixtures are not conformance evidence. Examine canonical fragment sanitization, partially open HTML conversion, unsupported attributes, copy/paste identity, resource lifetimes and round-trip fidelity. Execute conformance/probe work where practical; distinguish the canonical model from actual HTML parsers and OS clipboard integration.

3. **Storage, memory, recovery and performance.** Audit persistent hierarchical index feasibility, depth-dependent complexity, retained-root ownership and accounting. Full-copy timings do not establish production bounds. Make crash-recovery contracts precise: durable ordering, torn writes, interrupted checkpoints, recovery choice and acknowledged-edit guarantees. Design or run disposable probes for unanswered questions. Separate editing/index costs from shaping/layout and large-paragraph costs. Never invent measurements; specify the exact missing implementation or experiment when execution is impossible without production work.

4. **Unqualified platform feasibility.** Verify current primary documentation/source for Android InputConnection, iOS UITextInput, Desktop AWT/OS IMEs, Web DOM/ProseMirror and editable accessibility. Record exact versions and source URLs. Challenge delayed callback provenance, real settlement/cancellation fences, synchronous reentry, rejection/recovery, cell-flow switching, insertion-gap hosting, composition/selection coordinates, candidate geometry, composing-node preservation and accessibility exposure. Run real-host probes only on available environments. Synthetic stamps/geometry are not native evidence. For each unavailable environment, provide the smallest decisive probe, expected trace, pass/fail threshold and architectural fallback.

## Repair requirements

Preserve cross-paragraph/cross-container IME, explicit cell-selection composition, exact native-range fidelity, raw UTF-16, formatting preservation and global history. Do not simplify away required behavior. Merged/nested tables, collaboration and pagination are existing exclusions, not new defects.

For each material issue supply the exact section/file/line, classification, initial state, ordered trace, expected versus observed/specified result, root cause, minimal general correction, exact replacement normative text and a discriminating regression. Classify findings as design contradiction, missing decision, implementation defect, evidence defect or qualification gap. Fix shared ownership/invariant problems instead of accumulating special cases. Never weaken an assertion to manufacture a pass.

Disposable validation models/probes in a new review directory are permitted; production implementation is not. Preserve baseline files and original evidence. Document proposed changes rather than modifying the authoritative plan.

## Freeze decision

Separate: A) design blockers before specification freeze, B) chosen contracts awaiting implementation qualification, C) platform experiments that could change architecture, D) explicit product exclusions, E) optional optimizations. An unresolved architectural fork cannot be hidden under 'platform testing'. A frozen specification is not a claim that the editor is proven correct.

For each uncertainty name its consequence, decisive experiment, acceptance gate and reopen condition. Produce finite, testable criteria, not another generic instruction to test more.

## Deliverables

1. Independent freeze-readiness verdict and specific blockers, if any.
2. Exact commit, files inspected, commands/results executed, and external sources verified.
3. Prioritized findings with counterexamples and evidence.
4. Exact normative amendments ready for v5 incorporation.
5. Additional executable validation and reproducible results, with scope limitations.
6. Remaining qualification matrix: requirement, current evidence, missing experiment, pass/fail criterion, fallback, freeze-blocking versus release-blocking status.
7. Concise handoff to Codex: proposed amendments, disputed decisions, evidence to preserve and conditions for an honest v5 freeze.

If filesystem access is available, save these under `reviews/gemini-v5-readiness/` on your review branch and provide its commit/diff. Do not push changes to the baseline branch. If only web/file-reading access is available, return the report and proposed patches directly, marking execution unavailable.

Do not rubber-stamp, claim perfection, or manufacture defects. If no further design contradiction is found, say so and state what your review cannot establish.
