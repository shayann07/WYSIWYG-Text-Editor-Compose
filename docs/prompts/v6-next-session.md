# Next bounded implementation assignment — final v6

Work in `D:\Work\WYSIWYG-Text-Editor-Compose`.

Read `AGENTS.md`, `docs/project/PROJECT-JOURNAL.md`, the newest entry in `docs/reports/overnight-progress.md`, `docs/plans/v6/editor-v6-standalone.md` (especially final decisions D01–D10), and `docs/reports/v6-final-review-disposition-20261019.md`.

V6.0.0 is finalized as a conditional specification baseline, not a production readiness claim. Do not apply either Argon review verbatim. Both contained useful findings and incorrect recommendations. Use the final decisions. The documentation has moved under `docs/`; consult `docs/README.md` rather than recreating old root files.

## Mission

Complete one useful desktop editing qualification and repair batch: actual complex-cluster operations, correct caret/hit/selection geometry, the zero-partial-commit witness, and stable list marker styling/sibling placement. Fix reproduced defects and leave a usable build. Do not start a new collaboration, PDF, table or persistent-core rewrite. Do not stop at planning or green headless tests.

Inspect current Git/task state first. If your previous Q0 assignment already produced valid results, reuse them by exact source/build identity and fill the missing coverage; do not rerun completed work blindly. Preserve other agents' changes, user documents, samples, all frozen manifests and existing recovery/quarantine protections. Do not modify the final plan without a demonstrated contradiction; propose a small versioned amendment if necessary.

## 1. Complex text: exercise the actual target, not the second grapheme or a space

Use named target substrings and independently expected UTF-16 boundary arrays for Urdu/Arabic marks and joining, Persian ZWNJ, Hebrew niqqud, composed/decomposed Latin, Devanagari/Bengali conjuncts, Thai marks, Han/kana/Hangul, supplementary scalars, emoji modifiers/ZWJ, flags, variation selectors and bidi isolates.

Run collapsed-caret Backspace and DeleteForward at each relevant boundary, selected replacement in both directions, format/link patches, copy/paste, Undo/Redo and exact Save/Open. Include insertion/removal of joiners/marks that changes cluster boundaries and original constituent attribution. Cover hard/soft breaks and list/object seams. Native scalar deletion and raw UTF-16 deletion are different operations; do not snap native ranges to graphemes.

Pin oracle Unicode/tool versions. Official Unicode 16 fixtures and independently reviewed explicit expectations are the baseline. Do not use production Graphemes.stops as its own sole oracle or silently substitute JDK BreakIterator for pinned ICU4J/UAX behavior.

## 2. Caret, hit testing and selection

Replace finite-coordinate-only claims with independently expected visual run order, allowed caret associations and selected-run rectangle coverage. Test LTR, RTL and mixed bidi, wraps, empty paragraphs, large Nastaliq marks, mixed fonts/sizes, lists, images, page seams, scroll and all supported zooms.

Verify real clicks map to allowed logical stops; horizontal/vertical movement and Shift selection obey policy; preferred x and caret reveal work; forward/backward selections cover the same intended text; transforms apply once. Do NOT require universal hitTest(caret.center)==one exact affinity: valid bidi/ligature/zero-width cases can have multiple associations. Independent HarfBuzz/ICU diagnostics require matching font, script, context, features and Unicode version and do not replace live geometry inspection.

## 3. Reproduce zero partial commitment in the actual engine

Reference witnesses are in `reviews/v6-finalization-20261019/verify.mjs`:

`0123456789` → preedit AB over [1,3) → retarget [4,5) → preedit XY → partialCommit(0) → abort.

Expected: zero accepts no earlier island/bridge or cell-clearing prelude; marked overlay range stays [3,5), and abort restores the original document. Positive count 1 accepts the specified preceding prefix and leaves marked [0,1). Invalid supplied selection must reject atomically. Zero must not rebase attribution or create document history; settlement evidence still invalidates.

The bug was confirmed in an older frozen reference model, not automatically in production Kotlin. Reproduce through the actual engine/public adapter path. If current Kotlin already handles it, add a meaningful regression and report no implementation fix needed. Preserve raw native lifecycle distinctions; a terminal empty callback is not necessarily partialCommit(0).

Do not “fix” this by ignoring an entire native text callback whose committedCount is zero: it can contain a real new preedit candidate that must still be reconciled. Normalize candidate update and zero acceptance as separate effects, then verify their combined callback outcome.

Keep final v6 cancellation policy: explicit abort cancels pending CompositionTarget formatting even if partially accepted text survives; terminal acceptance formats surviving targets. FIFO queued Undo/Format/Undo retains attempted-action credits. Do not implement the rejected reviewer changes to those semantics or forbid valid reconversion over previously accepted text.

## 4. Stable list markers and shared sibling lanes

Implement D05. Replace the dynamic whole-item UTF-16 majority vote with stable semantic markerTypography, seeded at list creation/legacy import and persisted through edits/history/save. Ordinary body typing or a continuation paragraph must not change it. Do not replace it with another dynamic grapheme vote. Add explicit command/patch behavior; list splits/indents/paste must preserve the documented seed/identity rules.

If storing the new property requires a prototype codec version, make the change explicit, retain old readers, migrate deterministically and do not rewrite originals on Open. Test complete rich clipboard, checkpoint/retained drafts, Undo/Redo and Save/Open with the property. This is a bounded private-format extension, not a production S0 schema freeze.

Use shared parent-derived content lanes and measured gutters for siblings, not per-item intrinsic width. Short and long LTR siblings under an RTL parent must have aligned left markers; the reverse must align right markers. Local item direction controls the marker side. Validate wraps, multiple paragraphs, empty items, 1/9/10/99/100/9999, small/default/large sizes, first-character outliers, continuation formatting and supported view/zoom/window combinations.

Marker style should be stable; its baseline can legitimately move when the text line's metrics change. Digit-width changes can legitimately resize a shared gutter. Do not encode marker characters/spaces into canonical text to fake geometry.

## 5. Reuse and strict verification

Before inventing input/selection fixes, inspect the pinned ComposeTextEditor adapter/test donors and existing local witness suites. Reuse compatible fixtures/scaffolding, not another editor's mutation/history state. Document the narrow adaptation and notices. Do not adopt or upgrade dependencies without a concrete passing integration slice.

Host normalization is API-specific. Android setComposingRegion clips/sorts bounds by contract; the strict semantic owner consumes the normalized proposal with provenance. Do not blindly reject every out-of-range raw Android request or apply silent generic-core clipping. No Android implementation work is required in this Windows batch.

Record source/build/runtime/fonts and OS scale. Add tests that fail the actual defective behavior before fixing it where possible. Use independent expectations, deterministic seeds and minimized traces. Fail runners on child errors and UI-thread exceptions. Run relevant integration, codec/history/clipboard/recovery/quarantine and protected-file checks against one fresh identified build.

Then use supported computer control to test that SAME build: actual list examples and crops, mixed-script clicks/selection, formatting/history, Link Remove, image validation, Outline/Find, short/maximized windows, view/zoom changes and disposable Save/Open. Inspect screenshots yourself. Do not substitute screenshots for exact text or payload checks for visual quality.

If an installed candidate IME is available, make one bounded attempt through normal supported UI, compare against a standard reference control if necessary, and test preedit/convert/commit/cancel/focus/Undo and popup placement. Restore settings. Do not install new language components without authorization, retry setup indefinitely, or call pasted text/synthetic callbacks native IME qualification. If computer use is unavailable, finish independent checks and report the native gate BLOCKED instead of claiming completion.

## Deliver and stop

Provide the working launch command, a short user walkthrough, exact defects fixed, per-target/operation coverage, before/after screenshots, build identity and test commands, any codec migration, and honest FAIL/BLOCKED/NOT RUN items. Explicitly distinguish tested production behavior from the review's validation-only models. Report no code change when broader testing found no defect. Update the writing guide and latest handoff under docs/. No new feature batch, architecture research loop, tag/push or production readiness claim.
