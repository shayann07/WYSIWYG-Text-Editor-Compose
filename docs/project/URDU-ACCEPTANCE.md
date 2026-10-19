# Urdu quality and acceptance plan v1.0

17 October 2026. Required throughout `docs/project/PRODUCT-ROADMAP.md`; status NOT RUN for these complete gates. The existing desktop RTL fixtures are useful partial evidence, not qualification of this plan. Every test artifact records exact font bytes/version/license, backend/runtime, platform, input layout/IME, device scale, width/page profile and source revision.

## 1. Principles

Urdu is a primary product language. A right-aligned text box is insufficient. Preserve user text exactly under the canonical Unicode/input contract. Language-specific search equivalence, punctuation preferences or transliteration are derived/explicit operations, never silent storage normalization.

The application default writing profile is Urdu/RTL with a qualified Nastaliq font; users may set LTR/mixed paragraphs and explicit font/direction overrides. The reusable library is locale-neutral and accepts the profile from its consumer. Until a different versioned policy is qualified, neutral-only/empty-paragraph inheritance follows the current library contract; application defaults seed the root rather than rewriting existing paragraphs.

Font selection remains OPEN at U0. Evaluate at least two redistributable candidates plus a defined fallback. Pin actual font bytes, shaping features and embedding/subsetting rights. Do not assume an installed commercial Urdu font can be shipped or embedded. Naskh/fallback support cannot substitute for qualified Nastaliq in the Urdu application.

## 2. Corpus and oracles

Create fixtures with IDs UTXT-001 onward under a dedicated validation corpus. Each fixture contains raw code units, semantic attributes/structure, purpose, expected content outcomes and typography review status. Use licensed/self-authored passages; record provenance. Include at least 200 deterministic fixtures across the categories below, plus generated interaction traces. That count is a coverage floor, not proof of completeness.

| Category | Examples or required variations | Acceptance |
|---|---|---|
| Common Urdu prose | `یہ ایک اردو دستاویز ہے۔` / `پاکستان میں تعلیم اور تحقیق` | Joining and intended glyph forms reviewed by a proficient Urdu reader; no clipping at line/page edges |
| Urdu letter distinctions | U+06A9 vs U+0643; U+06CC vs U+064A; U+06D2; U+06BA; U+06BE; U+06C1 | Canonical units preserved through edits, saves and round trips; any equivalence is opt-in search/tool policy |
| Marks and joining controls | `مُحَمَّد`, stacked marks, ZWJ/ZWNJ, combining insertion/deletion, leading isolated marks | User grapheme commands and exact native ranges remain distinct; hidden attribution survives merge/split |
| Mixed scripts/numbers | `قیمت ۱۲۳ روپے — English 123 — ١٢٣`, dates, email/URLs and parentheses | Expected logical text and separate visual caret-edge fixtures; consistent copied text |
| Bidi controls | LRI/RLI/FSI/PDI, nested/unmatched isolates and directional marks | Qualified pinned Unicode behavior; no inferred text rewriting or isolate-blind paragraph direction |
| Nastaliq geometry | Tall/descending combinations, multiple font sizes, bold/italic substitutions and fallback runs | Ink not clipped or overlapped unintentionally; line metrics/selection rectangles account for actual font |
| Spacing/justification | Spaces, NBSP, tabs, punctuation, hard/soft breaks, short last lines | Plain aligned text first; advanced Urdu justification only through qualified shaping/layout rules, not injected Tatweel in canonical text |
| Structure | Lists/quotes/headings, multiple paragraphs per cell, footnotes, headers, page boundaries | Exact commands/identity/history plus correct RTL markers and cell navigation |
| Input | Windows Urdu, available Android/iOS Urdu keyboards, paste, dead keys, voice-provider input, other composing IMEs | Real host traces per supported combination; no dropped/duplicated accepted input |
| Layout mutation | Resize/zoom, font load, image load, scrolling, pagination and review overlays | Stable logical anchor and current geometry; no caret jumps to an unrelated position |

Use independent expected editing results and pinned Unicode fixtures. A second renderer is a differential diagnostic, not automatically the correct Urdu typography oracle. Human review is required for linguistic/typographic judgments; AI-generated screenshots or strings alone cannot approve them. Automated text/range/geometry checks complement human evaluation.

## 3. Gates

| Gate | Deliverable | Exit requirement |
|---|---|---|
| U0 — Profile and corpus | Font/resource manifest, Urdu defaults, corpus categories, reviewer checklist | Reproducible licensed fixtures/fonts; canonical code-unit checks; candidate/fallback differences documented |
| U1 — Input and editing | Native traces and headless regression pairs | Caret/selection, deletion, composition, format boundaries and partial commits preserve exact text/attributes/history on each claimed host |
| U2 — Typography and interaction | Fixed-font screenshots, geometry assertions and Urdu review | No known joining/diacritic clipping defect in corpus; mixed-script caret and hit testing validated, including ligatures and wraps |
| U3 — Structure and review | Tables/lists/annotations/suggestions and multiuser Urdu traces | Logical ownership/order preserved; accepted/rejected revisions correct; remote edits do not corrupt local composition |
| U4 — Pages and output | Shared page-plan/PDF/print corpus | Same page count and line breaks for qualified profile; geometry target <=0.25 pt; searchable/extractable logical text or explicit documented conversion; real print-path checks |
| U5 — Language services | Gold spelling/grammar/transliteration/dictation evaluation sets | Publish precision/recall or task-appropriate error measures and thresholds chosen before release; proficient Urdu review; corrections explicit and undoable |

No U gate is global across operating systems. Distinguish keyboard locale mapping from complex composition, and synthetic AWT/browser callbacks from actual OS input. Urdu keyboard smoke tests do not qualify Japanese or vice versa; the general library still needs its broader native corpus.

## 4. Required interaction traces

1. Type Urdu at bold/plain/link boundaries; backspace and forward-delete; Undo/Redo; save/reopen and verify raw attribution.
2. Select from an Urdu paragraph into an English paragraph, replace through native composition, partially commit and cancel; verify structure and accepted prefix.
3. Insert/remove joining controls between differently styled constituents; preserve their original attributes until explicitly formatted.
4. Move visually across an Urdu/Latin boundary and a soft wrap; mouse/touch selection must agree with the chosen caret edge, not a fabricated one-to-one glyph mapping.
5. Format or Undo while composing; accept terminal input synchronously, delayed, duplicated and after a qualified context replacement. Unqualified stale provenance must remain a reported limitation.
6. Reflow a Nastaliq paragraph across a page boundary while a comment/suggestion and caret are present; canonical positions and authorship remain unchanged.
7. Two replicas edit Urdu in one rich table cell; one inserts a note and the other deletes its anchor. Converge with an explicit orphan/conflict result and preserve peer work through local Undo.
8. Export current/all sections with headers, page numbers, footnotes and an unfinished local preedit. Committed output versus explicitly requested draft output is unambiguous.

## 5. Urdu services contract

Diagnostics hold anchored ranges and captured revision/locale/profile; they do not create formatting or history changes. A correction is an explicit command against its captured target and rejects if the target is no longer applicable. A stale checker result must not replace the user's later selection.

Search may offer exact matching and a named Urdu-equivalence profile. The search index stores a reversible map to canonical offsets; replace previews exactly which original units will change. Dictionary updates, digit preferences and punctuation defaults cannot mutate old documents implicitly. Suggestions/transliteration preserve the original until accepted. Document-language and UI-language settings are separate.

No production dictionary, grammar model, speech/AI provider or advanced justification algorithm is selected here. These are implementation choices with U5 qualification and licensing/privacy constraints, not excuses to postpone Urdu input/layout quality.
