# Final v6 review disposition — 19 October 2026

**Verdict: final specification v6.0.0, frozen with explicit open implementation/qualification gates.** The owner requested finalization after independent assessment of two Argon reviews. This does not certify the full editor, freeze a production CRDT/PDF/schema API, adopt dependencies or publish a Git tag. No production Kotlin code was changed here.

Reviewed input: SHA-256 `7013568c911bb7a0f8cf640e3015c04fca4199be8f83005f7072a619a8adbf89`, preserved in [the submitted review package](../archive/review-packages/v6-review1/README.md). Original review texts and hashes are in `reviews/v6-finalization-20261019/`. The reviews contain useful findings and several incorrect prescriptions; none was accepted just because both models agreed.

## Chatbot Argon findings

| ID | Independent disposition | Evidence and final decision |
|---|---|---|
| F-01 Undo/Format/Undo | REJECTED AS DEFECT; explicit policy clarified | Executed both terminal accept and abort on the existing model. `Hello ` remains because the second queued Undo consumes the cancelled Format attempt. This follows the stated attempted-action policy. No ephemeral slot survives drainage; next ordinary Undo works. Do not reorder the queue or skip credits to force the review's empty-string expectation. D01. |
| F-02 merged-cell identities/anchors | ACCEPTED DESIGN EXTENSION GAP; not a reproduced bug in an implemented merged-table engine | Added span closure, distinct cell enumeration, separate canonical ownership validation, primary/secondary replacement, merge relocation before retirement, split/paste policies. Small grid/anchor witnesses executed; full rich-table composition integration remains S0. Removed stale product-scope exclusion in generated annex. D03. |
| F-03 reconverting an accepted prefix | REJECTED AS DATA-LOSS CLAIM; I08 clarified | Executed trace: second positive partial commit produces `aPd` before abort; abort changes nothing. Without that second acceptance abort returns `aXYd`; Undo restores `abcd`. The old prefix is superseded by an explicit accepted replacement, not erased by residual cancellation. Do not clamp/reject valid reconversion or invent 1:1 footprints. D02. |
| F-04 Web/remote frontier | ACCEPTED AS OPEN INTEGRATION CONTRACT, not demonstrated browser deadlock | Spec now chooses conservative whole-document staging before authoritative replica application, distinct received/applied frontiers, causal drain and bounded backpressure. A queue-policy witness executes; no real browser/CRDT bridge is qualified. Selective packet application based solely on spatial disjointness is not accepted. D04. |
| F-05 malformed UTF-16 replication | CONFIRMED CANDIDATE HAZARD already anticipated in draft | Actual Yjs 13.6.33 encodes lone high surrogate as U+FFFD on peer. Numeric-unit transport round-trips all 65,536 values and a second encoded snapshot. This selects the existing numeric typed-unit experiment as first C0 candidate; no Rust/Automerge/scale qualification inferred. D04. |
| F-06 structural collaborative Undo | CONFIRMED NAIVE-ADAPTER HAZARD | Actual Yjs nested container Undo hides a peer insertion; a flat boundary witness preserves it. Hidden text is not established permanently erased from CRDT internals. Add UndoExecutor separation and structural inverse gates; do not claim flat tokens solve all trees. D04. |
| F-07 marker instability/oracles | ACCEPTED STYLE/LANE POLICY DEFECT; some proposed assertions rejected | Arithmetic reproduces 24→18→28 size flips and per-item intrinsic lane misalignment. Replaced vote with persisted marker typography and shared parent-derived lanes. Real baseline may move with line metrics; style/lane stability is the invariant. Geometry remains relational, with version/context-matched independent diagnostics. D05–D06. |
| F-08 PDF strings/glyphs | ACCEPTED REUSE RESTRICTION; several proposed API/cross-platform claims unverified | PdfKmp pinned String path does not carry editor glyph positions. Added shaped export boundary and font technology/semantic mapping requirements. No evidence SkPDF/TextBlob/raw-GID APIs are exposed on every target, nor that ActualText alone fixes selection. GP0 remains open. D07. |
| F-09 recovery quotas | ACCEPTED POLICY GAP; FIFO eviction rejected | Added resident/disk/count defaults, base/resource accounting, no silent eviction, explicit capacity/failure behavior. Ten-draft quota model passes; no physical durability claim. Retain durable provisional drafts, not unbounded RAM roots. D08. |
| F-10 provenance/toolchain | PARTLY CONFIRMED | ProseMirror GitHub archive/migration confirmed; verified npm 1.42.6 source artifact integrity. Pinned source compiler versions differ. That does not prove universal binary/klib incompatibility; exact target build is required. Rate-limited release metadata remains unknown. D09. |

## Antigravity Argon findings

| ID | Independent disposition | Evidence and final decision |
|---|---|---|
| B1 abort-format contradiction | CONFIRMED SPEC CONTRADICTION; recommended alternative not selected | Chose existing tested behavior: explicit abort cancels CompositionTarget formatting even when accepted partial content survives; normal terminal accept formats surviving target. Corrected both generated §4.7 and §4.12.2; retained matching §4.5/§4.6 examples. This is a deliberate product policy, not failure to notice the surviving text. D01. |
| B2 zero partial commit | CONFIRMED EXECUTABLE REFERENCE BUG AND SPEC AMBIGUITY | Frozen model accepts preceding AB island when k=0; reference-only subclass correction preserves Dc, overlay, marked [3,5), attribution baseline and selection. Invalid selection rollback, positive cut and selected-cell prelude tests pass. Production Kotlin requires its own repro; not changed here. D01. |
| B3 donor input assumptions | PARTLY ACCEPTED; blanket out-of-bounds rejection is WRONG for Android | Direct donor mutations/history, raw normalization and eager full-text reads need explicit adaptation. But official Android setComposingRegion mandates clipping/order normalization; malformed code-point deletion is a no-edit case. Separate host normalization from strict internal admission. Do not blindly forbid SDK-required coerce/clipping or use a fixed ±4096 window. D06. |
| B4 PdfKmp drawing | ACCEPTED RESTRICTION; not an executed font-output proof | String path cannot establish the same PagePlan glyph mapping; restrict it to non-text/packaging reference unless extended and qualified. Universal CIDFontType2/Skiko API prescriptions rejected. I03/I12 are not by themselves PDF rendering tests. D07. |
| B5 timers/Undo | ACCEPTED BOUNDARY CLARIFICATION | Verified timer code in both archive and npm artifact. Internal housekeeping is not canonical settlement. A microtask/next-frame alone is not a fence. Explicit HistoryGroupId/UndoExecutor contract added, without assuming infinite captureTimeout solves all interleavings. D04. |
| B6 insufficient oracle | ALREADY IDENTIFIED, CONFIRMED IN SOURCE; bad replacement oracle rejected | Draft already flagged second-grapheme/space and finite-coordinate weaknesses. Preserve Q0; test actual clusters and collapsed deletion. Reject unconditional caret↔hitTest bijection and treating java.text.BreakIterator as pinned ICU4J/UAX data. D06. |
| B7 marker vote | ACCEPTED; grapheme-majority remedy insufficient | First-paragraph/grapheme weighting still changes vote under ordinary typing. Choose stable stored marker seed with explicit patch/migration semantics and common sibling lanes. D05. |
| B8 span grid | ACCEPTED EXTENSION CONTRACT | Use CellId/FlowId coverage, fixed-point closure before enumeration, unique canonical ownership, immutable table-structure cache key. Text edits may still require layout recomputation. Bounded grid witness passes; S0 not complete. D03. |
| B9 anchors/recovery | PARTLY ALREADY COVERED; destructive recovery prescription rejected | Existing flow-deletion/atom-bias and separate-draft recovery rules existed. Clarified moved-node versus retired-container anchors. Reject “provisional data is never durable”: restart has no active native overlay but can retain a separate durable unfinished draft. D03/D08. |
| B10 qualification/licensing | OVERSTATEMENTS AND SOURCE IDENTITY ERRORS REJECTED | The broad purported v6 qualification quote is not in the submitted draft; §9.1 already records these limitations. Review substitutes humawork/KitePDF and extract-Optional/kmpdf for different pinned projects and calls PdfKmp MIT although its license is Apache-2.0. No universal impossibility claim about competitors or assumed permissive-only license constraint is accepted. D09. |

## Executed evidence

Commands from repository root:

```powershell
node reviews/v6-finalization-20261019/verify.mjs
node reviews/v6-finalization-20261019/run-inherited.mjs
python planning/build_v6_docs.py
python planning/validate_docs.py
```

- **16 finalization witness groups PASS**, including a negative control reproducing the frozen zero-count bug and a corrected validation-only reference. Results: [results.json](../../reviews/v6-finalization-20261019/results.json).
- **25 inherited frozen verification groups PASS**, run by importing test exports and writing results only into this review directory; original runner/output files were not overwritten. Results: [inherited-results.json](../../reviews/v6-finalization-20261019/inherited-results.json). These passing historical groups did not previously cover the zero-count multi-island defect.
- Actual Yjs 13.6.33 JS transport and Undo witnesses ran against the existing installed dependency. No new dependency was installed. All 65,536 numeric UTF-16 code-unit values round-tripped; this is not a throughput/memory or KMP binding benchmark.
- Grid/anchor/queue/quota/marker/ambiguous-geometry witnesses are small executable policy models, not implemented tables, browsers, measured font geometry or crash-recovery storage.
- Production source preimage hashes are in [before-hashes.json](../../reviews/v6-finalization-20261019/before-hashes.json); final integrity checks verify no production source changed. Historical frozen files remain intact.

Native IME/browser/AT/printing, production Kotlin repairs, new PDF rendering and Yrs/Automerge FFI are **NOT RUN in this finalization**. No broad upstream test suite was run. Existing native UI evidence remains dated and scoped to its original build.

## Primary-source corrections

- [Android InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection): setComposingRegion bounds/order normalization and code-point invalid-surrogate no-op are host contract requirements. The core still rejects invalid *normalized* ranges/provenance.
- [Archived ProseMirror repository](https://github.com/ProseMirror/prosemirror-view): confirms archive and forge migration. Current npm source artifact and version/integrity are recorded in [pm-artifact.json](../../reviews/v6-finalization-20261019/upstream/pm-artifact.json).
- [Pinned PdfKmp license](https://raw.githubusercontent.com/ConaMobileDev/PdfKmp/9985a335c4db0c0ee93d1f1d73468ced4e41de08/LICENSE) and [pinned yuroyami/KitePDF license](https://raw.githubusercontent.com/yuroyami/KitePDF/d177c784e50e3f58e0220b6476c55c566ba7f678/LICENSE) are Apache-2.0, not the alternate-project labels supplied in the second review.
- Additional version catalog/NOTICE/source retrievals are hashed in [the supplemental ledger](../../reviews/v6-finalization-20261019/upstream/ledger.json). Some release requests were rate-limited; unknown metadata was not invented.

## Freeze decision and limits

No remaining contradiction found in the reviewed local contracts that blocks the next bounded desktop qualification/marker-repair assignment. That is the extent of the freeze decision; there is no claim that every future integration design is complete. S0/C0/GP0, native adapters, durable drafts, performance, accessibility and linguistic/print qualification remain explicit gates. The full-product/library scope is preserved. New material design changes require a versioned amendment and evidence.

Final standalone: [editor-v6-standalone.md](../plans/v6/editor-v6-standalone.md). Next bounded implementation prompt: [v6-next-session.md](../prompts/v6-next-session.md). No message was sent to the other session, no production edit performed, and no Git commit/tag/push/publication was made.
