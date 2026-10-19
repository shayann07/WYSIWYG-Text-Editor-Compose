# Independent review prompt for Argon

Review the attached `editor-v6-standalone.md` independently as a principal editor/runtime architect. It is v6.0.0-review.1, NOT a frozen plan or a claim of production readiness. This is a review task, not permission to implement production code, change the repository, publish anything, or message another agent.

The owner's goal is a full Urdu-first document application with Google Docs-level capabilities, powered by a reusable open-source Compose Multiplatform editor library on Android, iOS, Desktop and Web. Urdu/Nastaliq is primary, but other scripts must remain correct. Page-based editing and searchable/selectable, faithful PDF/printing are requirements. Do not silently reduce that scope to a desktop demo, plain rich-text field or all-platform WebView.

We now want to save engineering time through proven existing components. The plan retains one semantic document owner but proposes specific reuse candidates. Challenge both excessive custom construction and unjustified dependency adoption. Do not reward novelty, sunk effort or the reputation of the model that wrote the plan.

READING AND EVIDENCE

Read the entire attached file, including its embedded detailed contracts, feature catalogue, status snapshot and upstream ledger. The top v6 layer governs current status/reuse/sequencing; older evidence is explicitly dated. Distinguish a real contradiction from an intentional historical record. Identify any ambiguity that this precedence rule fails to resolve.

The file is sufficient for a specification review. It does NOT give you executed test evidence merely by mentioning paths. If you only have this attachment, report executable claims as unverified. If browsing is available, verify material upstream claims using the pinned source links and primary docs. If you actually have the repository, inspect exact files and run only bounded non-production probes in a separate review directory. Report precisely what you read or ran. Never claim unavailable tests passed.

PRIORITY REVIEW QUESTIONS

1. Is selective reuse the best current route, or can a concrete existing editor satisfy the required semantics with less integration work? Give exact source/version evidence and a discriminating migration experiment. Do not propose a whole-editor pivot from a README alone.

2. For each high-value reuse row, identify the smallest reusable module/algorithm/test set, ownership assumptions, native/KMP/JS boundary, license/provenance checks, and one incompatibility that could negate the benefit. Which custom subsystem should we stop building? Which donor should we reject? What can we directly depend on versus adapt versus only study?

3. Can the proposed ProseMirror Web projection coexist with the semantic owner during real DOM composition without two independently editable histories? Examine noncancelable input, protected node ancestry, asynchronous admission, rejection, stale selection and remote edits. Provide an event trace for any failure.

4. Is the Yjs/Yrs/Automerge evaluation properly scoped? Examine UTF-16/UTF-8/index conversion, isolated-surrogate preservation, structural moves/deletes, invalid remote operations, deterministic materialization, per-user selective Undo and IME overlays. Identify where the authority bridge is still unspecified and must block C0/API freeze.

5. Audit admission, cross-paragraph structural composition, partial commit, retargeting, cancellation, settlement and queued Undo together. Test the stated segment ownership and LIFO ledger with minimal adversarial traces. Do not silently substitute ordinary text-field semantics for our contract.

6. Audit raw native selection versus grapheme navigation versus visual caret positions. Are the proposed independent tests strong enough to catch incorrect but finite geometry? Identify non-bijective bidi/ligature cases and valid oracle approaches. Distinguish Unicode conformance, backend agreement, native behavior and expert typography.

7. Challenge list marker size/style inheritance, UTF-16-weighted dominant style, opposite-direction child columns, wrapping, continuation paragraphs and page seams. Give counterexamples if a policy produces unstable or misleading layout. Separate typographic preference from correctness.

8. Does the schema cover rich cells/spans, sections, headers/footers, annotations/review and future collaboration without freezing the private prototype codec? Which exact decisions must S0 settle before implementation?

9. Can the proposed PagePlan/output bridge achieve appearance, logical text extraction, selection geometry and accessibility together? Assess the rejection of prior PDF candidates. Suggest a concrete reusable backend or one bounded experiment; do not prescribe a fresh shaper/PDF engine or invisible-text workaround without addressing the recorded failures.

10. Audit retention, atomic publication, recovery and resource lifetime. Identify any place where receipt/history/layout/dependency ownership can grow without bounds or where committed checkpoint recovery is confused with durable provisional/quarantined input.

11. Is the work order efficient and noncircular? Native feasibility, schema/collaboration/output decisions must happen early enough to avoid an expensive incompatible core, but the plan must not demand a complete product before testing a small slice. Name the earliest useful bounded assignment after the current qualification task.

12. Are any implemented/verified/platform/version/license claims overstated? Main source, released binaries and runtime qualification are different. Do not upgrade historical tests to current evidence. Flag document-only review limitations explicitly.

OUTPUT

Start with one verdict:
- READY FOR PLAN FREEZE WITH EXPLICIT OPEN GATES
- REVISIONS REQUIRED BEFORE PLAN FREEZE
- ARCHITECTURAL RECONSIDERATION REQUIRED

This is a PLAN verdict, never a production release verdict.

Then provide:
A. Confirmed or suspected findings ranked by consequence. For each: ID, exact section, minimal counterexample, violated requirement/invariant, evidence level, repair and acceptance test.
B. A reuse decision table: keep, directly reuse, adapt, reference-only or reject; concrete upstream source; integration cost/risk; next discriminating test. Separate measured facts from estimates.
C. Exact proposed specification edits for genuine gaps. Do not rewrite every paragraph or add speculative features.
D. Experiments that remain necessary, each with a bounded fixture, oracle, pass/fail criterion and stop condition.
E. What must be resolved before plan freeze versus what can remain an explicitly gated implementation question.
F. A concise recommended next implementation assignment, conditional on the review findings being resolved and the current session's actual results.

Be willing to conclude that an earlier choice is sound. Be equally willing to disprove it. Avoid generic best-practice checklists and unsupported “everything is correct” claims. Do not implement production changes or label the plan final on our behalf.
