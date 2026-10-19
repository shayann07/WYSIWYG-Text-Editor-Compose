# Urdu-first document application and Compose editor library — v6.0.0

19 October 2026. **FINAL SPECIFICATION BASELINE — FROZEN WITH EXPLICIT OPEN QUALIFICATION GATES. NOT A PRODUCTION-READINESS CLAIM.**

## Reader contract and authority

This is the finalized reuse-first planning layer for an Urdu-first document application with Google Docs-level capabilities and a reusable open-source Compose Multiplatform WYSIWYG editor library. Urdu is primary; correctness of other languages is not negotiable. Android, iOS, Desktop and Web, continuous and page-based editing, and searchable/selectable print-quality output remain required. The current desktop harness is neither the final application nor a published multiplatform library.

The generated standalone file embeds this layer, a dated evidence ledger, the complete 46-feature product catalogue, roadmap, Urdu acceptance requirements and the prior detailed library contracts. No other file is necessary to understand the proposal. Links to code and evidence enable verification; this Markdown does not contain or replace executable evidence. A reviewer with only this file must not claim to have run its referenced tests.

**Precedence:** this v6 layer governs reuse, present status, qualification, sequencing and release decisions. The embedded inherited contracts are corrected for the accepted review decisions and remain the target local-editing semantics. Section 9.5 states the exact final policies; the generator patches conflicting inherited prose instead of relying only on precedence. Historical statements such as “current,” “frozen,” “not yet implemented,” or “production not authorized” describe their dated baseline, not today's repository or permission. Embedded old phase order is replaced by the v6 work order. No historical evidence is upgraded merely by inclusion. Full-product requirements override earlier slice exclusions; an unimplemented requirement stays open. Contradictions not covered by these rules must be recorded and resolved before the affected API freezes, not guessed by a coding agent.

**This request authorizes research and planning only.** It does not authorize new production code, dependency adoption, a fork, publication, a license choice, messages to other sessions or interruption of current qualification work. The next implementation prompt is a separate bounded assignment. No production changes are made by finalizing this specification; inspect the latest implementation handoff before continuing its qualification work.

Version numbers are separate namespaces: this is plan v6; the desktop prototype's `.astra` v6 is a private file format; neither means a library release 6.0. Both independent Argon reviews have been dispositioned and the owner has explicitly requested finalization. The finalized contract includes section 9.5 and the corrected embedded annex. A frozen plan can retain explicit experimental decisions and blocked platform gates; it cannot represent them as solved.

## 1. Chosen architecture and why it is retained

### 1.1 Decision

Retain one semantic document/session owner, a canonical attributed hierarchical document, native/DOM projections, and a shared layout-to-selection contract. **Change the implementation strategy to mandatory component reuse evaluation before substantial custom work.** Reuse typography/layout engines and platform facilities; prefer libraries over copied code, isolated adaptations over forks, and an existing whole editor only if a discriminating compatibility slice demonstrates a better fit. This is an engineering choice under incomplete evidence, not proof that our engine is superior or cheaper.

The product has four separable responsibilities:

1. A headless KMP semantic kernel owns accepted document state, transactions, targets, local history, composition overlay and resources.
2. Platform input, selection, accessibility and clipboard adapters translate proposals/queries through that owner. There is one active native flow per editor, not one native editor per paragraph.
3. Layout and output derive glyph/line/page geometry and semantic mappings. Native surfaces use Compose and qualified platform services; Web may use ProseMirror's DOM surface under the integration contract below. Canvas is a rendering choice, not an input/accessibility architecture by itself.
4. Application services own files/workspaces, accounts, sharing, collaboration transport, review workflows and language services. They cannot mutate the document behind the kernel.

Use existing Skia/Skiko/Compose and appropriate native backends for shaping/layout. No new Unicode shaper, bidi algorithm or PDF file-format implementation is selected. The abandoned bracket index and unqualified native Skia patch remain outside the baseline. Large paragraphs are a measured qualification problem; arbitrary paragraph chopping, style merging, raster-only PDF or a silent document cap are not substitutes for requirements.

### 1.2 Alternatives and reopening conditions

| Approach | Present decision | Reason and falsifiable reopening condition |
|---|---|---|
| Adopt ComposeTextEditor wholesale | Not selected; strongest native input/reference candidate | Its source is an editor-specific state/IME system. Test migration of our attributed history, structural projection and raw input contract before assigning it ownership. Source similarity alone does not prove incompatibility or suitability. |
| Adopt Compose Rich Editor wholesale | Not selected for full document owner; useful codecs/list/rich-text reference | Its BasicTextField/rich-paragraph path needs qualification against hierarchy, cross-paragraph composition and retained raw attribution. A common rich-input control may still be reused in application fields outside the document. |
| Adopt Cascade wholesale | Not selected for current native document surface | Its documented per-block TextFieldState and block-selection model differs from one cross-block native flow. Reopen if an adapter passes that exact flow contract with less owned integration code. |
| Adopt TextKit wholesale | Not selected for full in-place tables | The inspected table contract uses a popup editor and separate table history. This does not meet a single in-place paginated table/history authority without integration changes. |
| Run a browser editor in every platform WebView | Not selected | Would change the native Compose product requirement. Web surface reuse remains allowed on Web. |
| Hand-build all layers | Rejected | Reimplements solved shaping, platform, parsing, collaboration and output machinery without demonstrated value. |
| Fork a runtime immediately | Rejected as default | Fork cost includes binary packaging, platform support and update maintenance. A measured failure and equivalence corpus must justify it. |

A whole-editor pivot requires a written comparison on the same fixtures, including accepted semantics, input, real Urdu/mixed-script appearance, migration, feature loss, package/license footprint and maintenance. A hard requirement failure disqualifies a candidate as a replacement, not as a component donor. Do not average away a correctness failure using a weighted feature score. Do not claim estimated integration effort is measured until an adapter slice exists.

### 1.3 What the research actually established

Read current primary docs and pinned source on 19 October. Upstream test suites were **NOT RUN** in this research. Main-branch source, released packages and platform qualification are separate. Source and license metadata are recorded in the generated upstream ledger; an API rate limit prevented some additional metadata requests and is retained as an error, not bypassed with invented results.

ComposeTextEditor's current `6f0a433e42169ad547ca7a5497ca30f99feadf55` differs from the earlier inspected `3589818...` by a README dependency update to 3.1.1 only. That delta does not invalidate the previous source-fit analysis. Its shared input logic, Skiko request adapter, pointer-affinity tests and input-session design are concrete adaptation candidates. The adapter is tied to its own TextEditorState, so copying it unchanged is not a safe shortcut. [Source](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/6f0a433e42169ad547ca7a5497ca30f99feadf55/docs/design/text-input-sessions.md).

Compose Rich Editor, Cascade and TextKit have useful existing implementations. Their capabilities disprove any claim that Compose has no rich editors. None of the inspected sources establishes all of this project's semantics, qualified platforms, Urdu typography, collaboration and print fidelity together. The exact-fit absence is bounded to the inspected candidates; it is not a claim about every editor on the market.

### 1.4 Mandatory reuse register

The dispositions are **CURRENT** (already used), **EVALUATE** (candidate, not approved), **ADAPT** (preferred donor subject to qualification), and **REFERENCE** (study behavior/tests, no runtime adoption). No row is an instruction to copy code without its license/notices and compatibility tests.

| ID | Subsystem / upstream | Disposition and selected boundary | Qualification before adoption |
|---|---|---|---|
| R01 | Compose/Skiko/Skia shaping, layout, UI primitives | CURRENT provisional backend; retain released artifacts | Exact fonts/version, glyph/cluster and selection equivalence, performance and each platform's input/AT; no inference from Desktop to iOS |
| R02 | Compose Foundation TrackedRange/ExpandPolicy/addStyle | REFERENCE or derived view utility; not canonical formatting/history | Verify exact overload/artifact; style-only composition and attributed Undo probes; hidden overlapping-span semantics must not leak into our semantic map |
| R03 | ComposeTextEditor input and pointer tests | ADAPT narrow platform adapter/fixtures | Port callbacks into our immutable InputProposal; eliminate foreign state/history ownership; raw ranges, partial commit, stale callbacks and geometry gates |
| R04 | Compose Rich Editor formatting/list/import paths | REFERENCE; EVALUATE codec utilities | Schema/UTF-16/attribute mapping, loss reports and import safety; no silent conversion of internal-grapheme attribution |
| R05 | Cascade schema extension/import warning patterns | REFERENCE; EVALUATE isolated codecs | No per-block host authority; preserve node IDs and cross-container input semantics |
| R06 | TextKit table workflows | REFERENCE only for baseline | No adoption of a separate popup/history as our document table owner |
| R07 | ProseMirror view/transform | EVALUATE direct Web dependency; ADAPT map tests conceptually for KMP | DOM IME ownership slice; composition-node protection; no second authoritative history; do not transplant scalar offsets as tree identities |
| R08 | ProseMirror tables TableMap | ADAPT cell-grid algorithm/tests behind our schema | Rowspan/colspan, duplicate coverage, cell identity and flow mapping, atomic merge/split/Undo; relative positions require translation |
| R09 | Lexical selection / DOM behavior | REFERENCE and differential scenarios | Test browser-specific behavior; do not copy its state machine into native platform input or assume its Yjs integration fits ours |
| R10 | Unicode official data; ICU boundary services | CURRENT versioned data/reference; EVALUATE cross-platform engine | Exact Unicode version, complete conformance plus tailored/raw UTF-16 policy; ICU availability and locale boundaries differ by target |
| R11 | Yjs / Yrs / Automerge | EVALUATE collaboration engines, never invent CRDT by default | C0 binding on native and Web, offset encoding, structural conflicts, local selective Undo, persistence, malformed UTF-16 and composition gates |
| R12 | PDFBox / PdfKmp / Skia PDF interfaces | EVALUATE output transport/backend | Consume accepted PagePlan/glyph mapping without independent reflow; logical text/selection/appearance and native packaging gates |
| R13 | KmPDF | REFERENCE; not selected for semantic PDF path | Inspected desktop path embeds page images, insufficient for searchable/selectable semantic export |
| R14 | KitePDF | EVALUATE later if it can satisfy the same glyph/semantic bridge | Source/API discovery is not output equivalence; no automatic selection over narrower backend |
| R15 | HarfBuzz utilities / cluster inspection | REFERENCE/tooling; shaping reused through qualified backend | Independently shaped output cannot be substituted unless it equals the actual editor line plan |

Concrete source entry points are embedded in the upstream annex. For a new reuse decision create an ADR with: requirement IDs; upstream commit and release artifact; files/API; license evidence; assumptions; adapter boundary; must-pass tests; retained/custom code; rejection reasons; measured integration effort; update strategy; rollback strategy. A release package, copied source and algorithm reimplementation are different provenance cases. Preserve applicable notices, record local modifications and track dependency/font terms; the project's own license remains undecided. This plan grants no licensing rights and selects no application license.

The first reuse task compares at most two candidate paths for the active bottleneck using one common witness suite. Stop after a complete discriminating slice or a documented hard mismatch. If both fail, keep the current path and record the concrete reason; do not search indefinitely. Reopen only for a relevant upstream change, new requirement or measured failure.

## 2. Core models, boundaries and algorithms

### 2.1 Target model versus implemented prototype

The production model remains the embedded detailed contract: DocumentRoot owns flows and resources; typed hierarchical nodes have stable IDs; text consists of raw UTF-16 units/pieces with canonical attributes; cell flows are separate from the table atom in the parent flow. Measured persistent B+ text/child sequences plus versioned identity/parent indexes are a **target design**, not the current Kotlin implementation. The desktop harness uses bounded array/snapshot structures and a private `.astra` codec. Do not rename it as the production persistent KMP kernel.

Required modules: `editor-contracts`, `editor-core`, `editor-unicode`, `editor-layout`, `editor-pagination`, `editor-input-{android,ios,desktop,web}`, `editor-accessibility`, `editor-clipboard`, `editor-persistence`, `editor-codecs`, `editor-review`, `editor-sync-contracts`, a Compose UI layer, and external sample applications. Names are proposed module boundaries, not published package coordinates. Keep JVM/AWT/OS dependencies out of common semantic code. A Web adapter can be JS interop without moving the native core into JavaScript.

```kotlin
// Contract notation, not compilable implementation or final ABI.
data class Basis(val documentId: DocumentId, val flowId: FlowId,
                 val documentRevision: Long, val sessionRevision: Long,
                 val epoch: Long, val generation: Long)
data class InputProposal(val basis: Basis, val stamp: AdapterStamp,
                         val target: NativeTarget, val operation: NativeOperation,
                         val postSelection: RawSelection?)
interface SemanticOwner {
    fun snapshot(): PublishedSnapshot
    fun dispatch(command: CapturedCommand): CommandResult
    fun accept(proposal: InputProposal): InputResult
    fun receipt(ticket: Ticket): CompactAcknowledgement?
}
interface LayoutBackend {
    fun layout(request: ImmutableLayoutRequest): LayoutSnapshot
    // Snapshot owns the exact font/resource/version key and semantic maps.
}
interface PlatformHost {
    fun publish(delta: AcceptedProjectionDelta)
    fun requestSettlement(request: SettlementRequest): SettlementAttempt
    // An attempt is not a successful fence. Result comes from qualified evidence.
}
```

Use the inherited finite command catalogue and closed primitive set. `Applied`, `NoChange`, `Deferred`, `Rejected`, duplicate acknowledgement and `Fault` are distinct. Commands capture targets and typing intent at admission, not whenever asynchronous work finishes. Expected rejection is an explicit outcome; implementation failure rolls back the atomic publication. No downstream library may mutate canonical state except by an accepted owner transaction.

### 2.2 Coordinate domains

Never represent every coordinate as an unqualified Int. Distinguish committed document points, visible/provisional points, per-flow UTF-16 native offsets, Unicode scalar indices, grapheme boundaries, glyph clusters, table-grid coordinates, page-fragment coordinates and device/screen pixels. Basis/revision and affinity accompany every ambiguous mapping. UTF-8, code-point and UTF-16 offsets require explicit adapters; this is particularly important for Rust CRDT interfaces and PDF text extraction.

A LayoutSnapshot contains canonical revision, visible session revision, block content versions, typography/font/resource identities, width, page profile and transforms. `hitTest(point)` returns a position plus bidi/wrap association and its key. `caret(position, affinity)` can have more than one valid visual edge; `selectionRects(range)` may return disjoint rectangles. Inverse mappings are relations at bidi seams, not an unconditional one-to-one identity. Reject or refresh stale geometry rather than reinterpret it as current.

Store model positions independently of page numbers. Page fragments identify a source flow/block/range plus continuation state. Reflow changes fragments, not canonical text or identity. List markers are derived decorations with their own measured gutter, not text units. An image is a typed atomic resource reference, not a naked U+FFFC inferred from text.

### 2.3 Production storage migration

Keep the simple implementation as a reference while introducing the persistent kernel in a separate module. For each command compare canonical semantic serialization, selection, maps, attributes, structure and outcomes. Token metadata is excluded from semantic revision equality where the inherited contract says so; structural IDs remain semantic. Validate current-root membership, detached nodes and cross-flow rejection. Move/split/merge must not rebuild all sibling indexes.

Production complexity includes depth, touched pieces, attributes, anchors and retained roots. O(1) root restoration does not make Undo notification/layout/resource cleanup O(1). Qualify locality with traversal/allocation counters and scale curves before advertising it. The prototype's 100,000-unit limit is not a permanent product limit; changing it requires memory/input/output qualification rather than removing a guard blindly.

### 2.4 Import/export and resource boundary

Prefer an existing parser/serializer, then translate a validated intermediate tree through one schema compiler. Parsing HTML/Markdown is not equivalent to faithful document conversion. Sanitize URLs, attributes, empty-block attributes and resource references; remap imported IDs; report unsupported content and refuse unsafe/unrepresentable imports atomically. Do not silently normalize Unicode. Preserve raw UTF-16 in the canonical format; scalar-only interchange replaces invalid units only with an explicit loss report. Keep no-op dry-run conversion available for review before adoption.

Images are document-owned immutable bytes/descriptors with media limits, decode off the UI thread and fallback diagnostics. Do not retain only a local filesystem path. Snapshot resources for export. Implement codec migration/read compatibility before changing the stored schema, and never rewrite a user's original merely by opening it.

## 3. Formal invariants and strict verification

The embedded I01–I17 and extended admission/anchor/cell invariants remain mandatory. The following v6 refinements are explicit release assertions:

| ID | Assertion | Discriminating verification |
|---|---|---|
| V6-I01 | A reused editor/CRDT/DOM component cannot bypass the semantic transaction owner | Mutation/notification spies and rejected-proposal traces |
| V6-I02 | Exact accepted input, raw attributes and structural identity survive required edit/history/persistence operations | Independent canonical tuples, not screenshot or text-only equality |
| V6-I03 | A candidate oracle is independent of the behavior it judges | Official versioned data, reviewed expected ranges, failure-against-old behavior and separate simple model |
| V6-I04 | Geometry and hit tests use the same visible revision and coordinate transforms | Stale-layout injection, bidi relations, expected visual points, zoom/scroll/page seams |
| V6-I05 | Marker side follows local direction; nesting placement is a separate policy | Both RTL-parent/LTR-child and reverse, not one mirrored formula as oracle |
| V6-I06 | Release claims are scoped to exact version/platform/input/font/DPI evidence | Machine-readable capability matrix with NOT RUN entries |
| V6-I07 | Refusal of noncancelable input retains a recoverable proposal; no false success | Late callback/capacity/invalid-structure recovery and lifecycle tests |
| V6-I08 | Collaborative Undo cannot restore a root that erases unrelated remote work | Two-peer local Undo/redo with concurrent text/format/move/delete |
| V6-I09 | Pagination/output preserve coverage and logical reading order | Source-range coverage, image/resource coverage, extraction and selection-box checks |
| V6-I10 | Failed validation leaves dialog/command state recoverable and content unchanged | Actual controls plus state/history assertions |

### 3.1 Unicode and multilingual verification contract

Retain the inherited Unicode 16.0.0 segmentation baseline until an explicit upgrade is tested. Current Unicode web pages may describe newer versions; never compare newer conformance data to the older algorithm without declaring the version mismatch. Renderer Unicode/font versions may differ; record them separately. Word navigation for Thai/CJK and dictionary behavior is not proven by grapheme conformance.

Fixtures must name the actual target substring and independent UTF-16 boundaries: Urdu/Arabic diacritics and joining, Persian ZWNJ, Hebrew niqqud, precomposed/decomposed Latin, Devanagari/Bengali conjuncts, Thai marks, Han/kana/Hangul, supplementary scalars, skin-tone ZWJ emoji, flags, variation selectors and bidi isolates. Test boundaries adjacent to hard/soft breaks, atoms and list items. Include inserting/removing joiners and marks that change cluster boundaries while retaining constituent attribution.

For each target perform Backspace, forward Delete, forward/backward selected replacement, formatting/link changes, copy/paste, Undo/Redo and Save/Open. Native ranges remain exact; application navigation/deletion follows its documented boundary policy. Independent official break fixtures and explicitly reviewed examples supply expected boundaries; the production segmenter's own stops must not be the only oracle. A case editing a space in a string containing emoji is not an emoji edit test.

### 3.2 Visual and geometry evidence

Numeric finiteness is a sanity check, not placement correctness. Define expected visual run ordering, first-line baselines, marker/text gaps, allowed caret-edge sets and selection-rectangle coverage from independently labeled fixtures. Use backend queries as diagnostic agreement, not sole proof against the same backend's bug. Click representative positions in the actual app and compare resulting logical ranges. Exercise horizontal/vertical movement, Shift selection, preferred x, mixed bidi seams, wraps, images, pages and list gutters. Compare forward/backward selection coverage and ensure scroll/zoom transformations are applied exactly once.

Use pairwise coverage for broad language/size/view combinations and exhaust high-risk boundaries. At least default, small and large sizes; all supported zooms; minimum/normal/maximized windows; Outline+Find together. Record OS DPI and actually tested variants. Match before/after capture content/font/zoom/window; disclose changed sidebar/page geometry. Inspect readable crops. No screenshots generated by AI count as product evidence.

### 3.3 Evidence levels and stop rules

Every result states: DOC/SOURCE INSPECTION, MODEL, EXECUTED UNIT, MOUNTED UI, LIVE NATIVE or HUMAN TYPOGRAPHY REVIEW; alongside PASS/FAIL/BLOCKED/NOT RUN and exact scope. Platform and release artifacts are separate dimensions. Test count is not coverage proof. A live pasted string is not keyboard/IME evidence; a synthetic composition callback is not an OS candidate window. Source branch README claims are not release-package behavior.

Runners fail on child-process failure, uncaught UI exceptions and assertions. Capture build/source/font/dependency identities once, validate before and after, and preserve failing traces. New regressions should fail the defective behavior where feasible. Fuzzing uses fixed seeds and shrinking. A failed environmental prerequisite blocks only the affected gate; do not fabricate passing native tests, weaken assertions or start unlimited exploratory work.

## 4. IME composition, selection and settlement

### 4.1 Ownership state machine

Keep connection epoch, input generation, session ordinal/token, marked range, attribution epoch, committed/visible roots, history preimage, settlement obligation and pending commands separate. Batch end, focus loss, unmark or time elapsed is not universally a native fence. One registered adapter normalizes each actual platform callback; internal stamps provide replay protection, not proof of OS provenance.

| State/event | Required transition and acceptance | What must not happen |
|---|---|---|
| Idle → mark or first preedit | Capture complete preimage before provisional splice; create immutable attribution baseline | Snapshot of dirty preedit as Undo-before state |
| Composing → candidate update | Reconcile against the same baseline using UTF-16 prefix/nonoverlapping suffix; preserve admitted tags | Seed from previous candidate and accumulate path-dependent styles |
| Retarget | Exact admitted range, new baseline against current committed content; preserve other islands | Clamp to protect old prefix or implicitly commit all islands |
| Partial commit | Accept the specified prefix policy from the inherited segment model; retain residual obligations | Drop trailing provisional islands or erase accepted prefix on cancel |
| Nonterminal unmark | Clear mark only; keep session/overlay/preimage and wait | Push history or declare terminal success |
| Qualified terminal event | Accept native result; drain queued commands atomically under LIFO action ledger | Publish intermediate formatting/Undo stages |
| Application abort | Discard unaccepted overlay; keep accepted partial effects; explicit command outcomes | Treat native empty replacement as rollback |
| Native removal | Apply exact replacement in named visible basis, then terminal acceptance | Restore initial document by guessing intent |
| Connection replacement/disposal | Finalize according to contract; cancel old commands; retire identity after publication | Apply old toolbar commands in new connection |
| Unproven/stale/noncancelable proposal | Reject when possible; otherwise preserve proposal and quarantine with recovery UX | Silently drop input or reinterpret against today's caret |

The segment algorithm, structural compiler, tagged provenance, partialCommit examples, action ledger, retention limits and cell rules are fully embedded in the contract annex. Cross-paragraph composition remains required within an admitted flow, including selected hierarchy transitions and removable objects where allowed. Cell boundaries are separate flows, not ordinary text separators; cross-cell editing uses explicit cell-selection commands. This distinction is not an exclusion of in-place rich tables.

### 4.2 Reuse constraints

Translate upstream input operations into our proposals, not upstream state mutations. Preserve atomic multi-splice code-point deletion, exact selections, commit/finish distinction and changed-range provenance. A library that clamps input, discards malformed UTF-16, flattens boundaries or owns its own Undo requires an adapter with demonstrated semantics; do not import those behaviors accidentally.

Maintain raw protocol selection separately from the drawable caret and semantic toolbar target. Programmatic echo suppression matches the exact expected update, not every same-offset event. Reentrant reads see only the last published state. Native reads may exclude the currently marked range while internal Dc has broader provisional obligations; use named coordinate conversions rather than assuming these are the same document.

### 4.3 Native qualification

Desktop starts with the current AWT/Compose host. The Japanese activation error `0x80004005` is a setup failure, not proof the IME or editor is impossible. One bounded manual activation/reference-control comparison is appropriate in the existing assigned test batch. Android requires real InputConnection, keyboard queries/batches/composition and TalkBack. iOS requires actual UITextInput marked/selected ranges, dictation/reconversion and VoiceOver on macOS/device infrastructure. Web requires real browser composition/candidate tests, not dispatchEvent equivalents. Available native tests cannot be replaced by fixtures; unavailable targets remain explicitly blocked.

## 5. Formatting, boundary attributes and derived UI

Keep canonical per-key attributes, not arbitrary overlapping sticky-span handles as the persisted model. Inherit, explicit value (including false) and Clear have distinct meanings. Typography is stored as semantic font references; backend font handles are derived. Preserve raw per-unit attribution even inside a merged grapheme. Rendering may use the inherited first-scalar effective style policy without rewriting stored constituents. Explicit range-format commands expand to complete graphemes within each block; raw native ranges do not.

Typing at unequal-run boundaries uses captured logical TypingSide; it does not union neighboring styles. Replacement seed comes from the first selected text in document order, independent of selection direction. Empty blocks retain versioned typing defaults. Links have identities distinct from URL strings; automatic typing inheritance requires the same ID on both sides. Exterior seams insert outside unless explicit intent says otherwise. Current-link edit/remove at a collapsed caret is strict-interior identity targeting; one-unit links or exact exterior seams require explicit selection/insertion UI, not a guessed neighboring link. Inline-code and whitespace/Enter boundaries follow the inherited rules.

The prototype's dominant raw-unit marker vote was demonstrated unstable during review. Final v6 adopts stable semantic item marker typography and shared sibling lanes under D05. This is a required implementation change with versioned persistence/migration and regression tests, not a claim that production code was changed in this planning task.

Numeric markers share the first line's alphabetic baseline; bullets use a separate optical-center rule. Measure sibling gutters, continuation columns and nesting. Root direction anchors nesting; each item's own resolved direction controls marker side, shaping and Start/End alignment. An opposite-direction child uses its own marker side within the shared parent-derived sibling lane, never a per-item intrinsic-width column (D05). Validate wraps and large mixed fonts; a screenshot of one English child is insufficient.

Outline navigation hit-tests actual row bounds; blank space does nothing. Current-section indication is distinct from keyboard selection. Labels use script-capable measured wrapping and accessible full text. Toolbar values must retain distinguishing heading/font/zoom information. Persistent validation dialogs preserve bad input and show actionable errors; cancellation and stale-target refusal cannot mutate content. These are part of usable editing, not optional polish after model tests pass.

## 6. Cross-platform integration and reuse contracts

### 6.1 Compose capability boundary

Current Android documentation exposes ExpandPolicy with tracked-range boundary growth and references TextFieldBuffer.addStyle. Foundation's release page and API pages can describe different introduction stages/overloads; the old output-only addStyle history must not be conflated with tracked persistent APIs. JetBrains publishes Compose Multiplatform 1.12.1; that alone does not prove every Android API/backend behaves identically. [ExpandPolicy](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/ExpandPolicy), [Foundation releases](https://developer.android.com/jetpack/androidx/releases/compose-foundation), [CMP release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1).

| Target | What is actually established in this repository | Missing qualification |
|---|---|---|
| Android | Official API/source availability; adapter design and donor source inspected | This plan's actual resolved Android artifact, runtime semantics, native input/AT and release matrix |
| Desktop Windows | Built against foundation-desktop/ui-desktop 1.12.1, Skiko 0.150.1, Kotlin 2.2.20; prior state probes and current prototype evidence | Full candidate IME, all fonts/DPI/AT, large documents, production kernel |
| iOS | CMP release and platform API documentation; design only in this review | Actual linking/runtime/UITextInput/VoiceOver and packaging |
| Web | Upstream/editor source and selected semantic-DOM architecture | ProseMirror-owner integration, real OS composition and browser/AT matrix |

Prior Desktop state probes observed attributed Undo loss and composition side effects in particular style operations; these are recorded version-specific failures, not a universal condemnation of Compose or a fresh rerun today. Existing utility APIs solve range tracking and rendering tasks; they do not establish our hierarchy, raw attribution history, session recovery or cross-platform qualification. Retain useful APIs, reject unjustified ownership assumptions.

### 6.2 Native adapter contract

Android InputConnection surrounding deletion validates both flanks and structural admission before publication; nested batch edit is not finish-composition. iOS input delegate notification ordering and geometry must be validated with real callbacks. AWT committed-text slicing uses endpoint association around the marked range; trailing(-1) can represent a valid TextHitInfo start boundary. Never reject every negative character index without checking insertion offset/affinity. Native selection and geometry queries use the published frame and correctly transformed screen coordinates. [Android contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection), [Apple protocol](https://developer.apple.com/documentation/uikit/uitextinput), [AWT contract](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html).

Accessibility consumes canonical semantics and the same keyed geometry, including offscreen text, images, links, lists, tables and pages. A canvas requires explicit accessible editing/navigation integration. Synthetic AT method calls do not qualify actual screen readers. No accidental shadow text field becomes a second history owner.

### 6.3 Web reuse slice — preferred candidate ProseMirror

ProseMirror supplies mature DOM editing machinery and mapping APIs, but its state and composition lifecycle cannot simply be bolted onto the kernel. The selected spike uses its view/state as a derived browser projection. `dispatchTransaction` captures an immutable candidate and basis; the owner returns accepted text/structure/selection deltas. Disable independent ProseMirror history for canonical document Undo. Ordinary accepted app mutations update the projection once with feedback-loop suppression tied to receipts.

During browser composition the browser DOM is a temporary provisional view, not accepted canonical storage. Protect the composing Text leaf and wrapper chain from replace/reparent/split, even when edits are numerically outside the marked range. Noncancelable changes become provisional proposals or retained recovery records. Nonconflicting siblings can update only if ownership and selection survive. An asynchronous bridge must not acknowledge canonical acceptance early or repaint the protected subtree from an old snapshot. [ProseMirror reference](https://prosemirror.net/docs/ref/), [input source](https://github.com/ProseMirror/prosemirror-view/blob/ca4c78e9b56f1b164c0b3758b59d8748f11b7534/src/input.ts), [Input Events](https://www.w3.org/TR/input-events-2/).

Stop condition: if the adapter cannot preserve active DOM composition and exact acceptance semantics with supported extension points, fail the slice and compare the existing bespoke semantic-DOM adapter. Do not maintain two editable documents with eventually reconciled independent histories. Selecting ProseMirror today is a candidate decision, not a promise its bridge already works.

### 6.4 Collaboration and review

Evaluate Yjs/Yrs first as one family (JS Web with a potential native Rust boundary), Automerge as the alternate. This is not a selected production dependency or a proven KMP binding. Before adopting either, pin release artifact and native bindings, measure binary/FFI cost, and prove text-index encoding, unsupported raw UTF-16 policy, typed attributes, structural moves/deletes, IDs, resource ownership and local selective Undo. If raw ill-formed UTF-16 cannot be represented, either encode it reversibly under a documented schema or block adoption; do not silently replace it.

There must be a precise authority bridge: the replication engine owns convergent replicated state; the semantic owner validates/compiles local requests and publishes the canonical materialization atomically. Remote changes pass through that same materialization/validation boundary, not ordinary user-input rejection after acknowledging replicated acceptance. C0 must settle how invalid remote updates/schema versions are quarantined or rejected and how peers remain convergent. Projection cache is not another mutable authority. Root-snapshot local Undo stays local-only until replaced by a qualified per-origin operation mechanism.

Yjs UndoManager supports scoped/tracked-origin undo; that is a useful starting mechanism, not proof of our structural intent semantics. Test concurrent format, paragraph split, move, deletion, table edit, comment anchors and accepted/rejected suggestions. Presence is ephemeral; annotations/review are semantic records. Remote edits overlapping native composition need an explicit qualified policy (defer conflicting projection, settle with proven fence, or retained recovery); a revision increment alone is insufficient. [Yjs UndoManager](https://docs.yjs.dev/api/undo-manager), [Yrs](https://github.com/y-crdt/y-crdt), [Automerge](https://github.com/automerge/automerge).

### 6.5 Pagination and PDF

One immutable PagePlan carries source flow/ranges, line fragments, glyph runs or a qualified equivalent, clusters, reading order, fonts/resources, section/page settings, table continuation and repeated-header instances. Editing, preview and output must consume consistent geometry. Native paragraph shaping context survives page fragmentation. Headers/footers are canonical flows with repeated visual instances, not copied text per page.

PDFBox is a possible JVM writer; PdfKmp a possible wrapper/backend; Skia PDF and KitePDF are candidates only where glyph and semantic mappings can be preserved. A writer that independently wraps/reshapes text cannot certify preview fidelity. Raster pages alone fail searchable/selectable export. The existing vector-outline plus invisible-text experiment is rejected because reading order/diacritics/selection geometry failed despite visual similarity. Do not repeat that route without a concrete cluster-to-semantic mapping improvement. [PDFBox](https://pdfbox.apache.org/), [PdfKmp source](https://github.com/ConaMobileDev/PdfKmp/blob/9985a335c4db0c0ee93d1f1d73468ced4e41de08/pdfkmp/src/jvmMain/kotlin/com/conamobile/pdfkmp/render/JvmPdfCanvas.kt).

GP0 tests one continuing Urdu/English/Arabic paragraph, an image, a spanning rich table, editable boundary selection/composition and semantic PDF from the same profile. Require exact source coverage, stable reading order, logical extracted text, useful selection geometry, font/image retention and appearance under declared tolerances. PDF tagging, accessibility, actual platform printing and printer/profile differences need later GP2/GP3 qualification; a visually matching preview does not close them.

## 7. Transactions, history, recovery and retention

Keep one publication per accepted command or normalized native batch. The preimage includes document, composition, protocol bookkeeping, pending outcomes, history, resources, selection and anchors. Counter identity allocation is monotonic even on rollback; semantic/session revisions advance only under their declared rules. Observer failures cannot veto accepted content, and observer assertions must be checked outside callbacks.

Local history preserves complete attributed structural roots and before/after selection, typing intent, IDs and resources. Composition history starts before preedit and settles once if net semantics changed. Pending application actions drain FIFO after qualified terminal input; Undo resolves through the inherited settlement-local LIFO ledger, including explicit no-history/cancellation credits. Redo restores only actual undone groups. Do not replace this with a donor editor's text-only history or collaboration root restore.

Keep bounded compact receipts, coordinate bases and replay watermarks; they must not retain whole snapshots/history accidentally. Pending dependency bases are pinned before eviction and released in finally paths. Attribute/resource slabs, current roots, active S0, history, layout, caller-held snapshots and recovery artifacts each need explicit ownership accounting. Pool limits are not total heap guarantees. Backpressure never silently truncates native content.

**Implemented durability is narrower than the target.** Current Windows recovery writes committed-content copies roughly every two seconds plus scheduling/IO, with fallback and tested owned-process kills. Named documents change only on explicit Save. Provisional composition, retained drafts and quarantined callbacks remain memory-only unless explicitly exported; Undo/caret are not restored after process loss. Power-loss durability is not qualified.

The production target retains both committed content and unresolved provisional drafts with durable decisions, as in the inherited recovery protocol. Do not call this implemented merely because the old plan specifies it. Before claiming lossless input recovery, persist the draft/preimage/resources and quarantine obligations, make apply/discard durable, and test process kill at each write/flush/replace stage. Applying a draft validates its expected base and is one undoable command; newer edits require conflict resolution, not overwrite. Never replay stale native callbacks automatically after restart.

## 8. Implementation blueprint, reuse gates and acceptance

V6 changes sequence, not the product ambition. Earlier G0–G8 and product M0–M8 remain traceable, but use this dependency order for new assignments. A gate has no PASS until actual evidence exists. Partial evidence can permit independent work, not dependent release claims.

| Phase / gate | Deliverable | Acceptance and stop condition | Existing correspondence |
|---|---|---|---|
| V6-0 Review disposition | Both Argon reviews dispositioned; executable witnesses and corrected standalone | COMPLETE at specification level under the owner's finalization request; no production/platform gate passes | Planning only |
| V6-1 / Q0 | Targeted complex-cluster edit and visual geometry witnesses; repair genuine defects | Named target ranges, independent oracles, actual native walkthrough and regression preservation; exact native gaps remain explicit | Current assigned qualification; U0/U1/G3–G5 subsets |
| V6-2 / RU0 | Reuse ADRs and two-path adapter comparison for the active input/Web bottleneck | Same contract fixtures; donor provenance; no second authority/history; reject hard mismatches and record migration cost | G0 platform feasibility |
| V6-3 / S0+C0+GP0 | Bounded schema, collaboration and page/output vertical slices | Typed cells/sections/review IDs; convergent two-peer composition/Undo test; semantic output witness. Resolve dependencies before stable API/schema | Existing M1 gates remain open |
| V6-4 / K0 | Extract headless KMP kernel and persistent storage behind contracts | External sample consumes public API; differential full semantics; 100k operations/20 seeds where inherited gate requires; locality/retention counters; supported target compiles | G1–G3, GL alpha |
| V6-5 / N0 | Native Android/iOS/Desktop and Web surface adapters | Real IME/caret/AT/clipboard lifecycle per claimed platform, disposal/reentry/provenance and offline font tests | G4–G6, U1–U3 |
| V6-6 / D0 | Durable document/draft/resource package and migration | Crash matrix, tail corruption, atomic decisions, no lost unresolved draft, honest save state | G7, F27/F36/F46 |
| V6-7 / P0 | Professional page/table/output implementation | GP0 design adopted; sections, spanning tables, margins, headers/footers, overflow; semantic PDF/print fixtures | GP1–GP3, U4, M4 |
| V6-8 / A0 | Useful local Urdu application and qualified library alpha | Public API-based app, external consumer, Urdu UI/accessibility, packaging/license/notices; feature/platform matrix | M2/M3, GL |
| V6-9 / C1+R0 | Collaboration/review/version services | C0-backed replication, per-user Undo, offline conflict/permission/review gates | M5/M6 |
| V6-10 / L0 | Language services, office codecs, extensions and stable release | Corpus-based Urdu quality, declared DOCX/ODT profiles, performance/AT/output/platform gates; selected license and update policy | M7/M8, G8/U5 |

Phases denote dependencies, not an instruction to build all work serially or keep the user waiting for a full product. The useful current desktop app remains usable while gates are investigated. Native feasibility must not wait until after months of storage construction. S0/C0/GP0 can use tiny disposable adapters and reference structures; they do not require a completed production B+ implementation. Collaboration/pagination failure blocks the dependent freeze/release, not unrelated local bug fixes.

### 8.1 Concrete next assignments after review

1. Reconcile the already-issued Q0 assignment with its actual report. Do not reissue completed work or interrupt a running task.
2. If Q0 exposes native input/selection defects, run RU0 against the pinned ComposeTextEditor adapter/test donors and current host. Keep semantic cases identical. If not, prioritize Web ownership or Android feasibility, whichever has the earliest untested hard dependency.
3. Execute S0/C0/GP0 before claiming stable production schema/API. Each returns a decision record and minimal runnable witness, not an open-ended research essay. At most two concrete candidate backends per gate before escalating a failed hard requirement.
4. Extract only interfaces supported by those outcomes. Do not publish package coordinates/license as an assumption or bake `.astra` prototype versions into public API.

### 8.2 Performance and regression budgets

Carry the inherited product targets as targets, not measured achievements. Establish named hardware/device/build/font conditions and workloads: small notes; 100k-unit single paragraph; 1M-unit multi-block document; deep hierarchy; many styles/anchors/resources; RTL mixed lists/tables; repeated composition; long history. Measure p50/p95/p99 input-to-paint, owner transaction, native query, layout, memory/retained roots, startup, import/export and checkpoint latency. Warm/cold runs and UI-thread versus background work are separate. No JS model timing is a native editor benchmark. A 100k cap or average paragraph workload cannot hide the pathological single-paragraph case.

Before optimizing, retain a failing trace and a comparable baseline on the same workload. Reuse a qualified faster backend if it preserves outputs; otherwise block the claimed envelope and record the tradeoff for the owner. Layout-only changes must not manufacture document revisions/history or consume accepted text.

## 9. Adversarial self-review, open decisions and freeze criteria

### 9.1 Present evidence snapshot

As of this draft the last examined completed batch is `batch-20261019-141905-30540`, manifest SHA-256 `eef9c31b11e8cca6d8d92114f9828e14953e32a7286b0c10bf8420ee263a079b`. It records 16 passing integrated stages, 17 focused groups (including 12 script fixtures), 108 integrated layout rows plus 108 additional rows, and 12 owned-process interruption cases. These are reported/examined bounded results, not 216 independent proofs or all-language qualification. A read-only native evidence audit was independently rerun successfully before this planning request; the full suite and native walkthrough were not rerun for plan generation.

The latest repair fixes list markers, existing-link editing/removal, persistent image validation, outline hit/current/readability and toolbar sizing. Before/after native captures show improvement. Native final-build spotcheck records Remove/Undo and blank-outline no-jump. Real Urdu/Arabic key traces are on an earlier repair build with an explicit class-equivalence bridge; other mixed input was pasted. Japanese activation was blocked; candidate lifecycle remains NOT RUN. Only 125% Windows scale is documented. Screenshots do not establish exact persisted text; payload audits do not establish typography.

Independent review found the multilingual operation loop edits `stops[1]..stops[2]` only, which is a SPACE in the Unicode stress specimen; its geometry loop largely checks finite coordinates/positive height. Therefore strong complex-cluster editing and hit-test claims remain open. Q0 addresses these specific weaknesses. Do not advertise its results before execution. The new plan does not erase successful bounded tests; it limits their interpretation.

### 9.2 Critical uncertainties and experiments

| Risk | Status / decision required | Minimum settling experiment | Blocks |
|---|---|---|---|
| Complex-cluster editing and geometry | OPEN coverage gaps | Q0 targeted boundary and visual relation tests | Multilingual editing qualification |
| Native provenance/settlement | Desktop partial; others unqualified | Installed candidate IME plus reference control, real platform callbacks/reentry/late arrivals | Platform input release |
| Reuse integration saves effort | Hypothesis, not measured | RU0 slice comparing adapter/maintenance surface on same tests | Dependency/whole-editor replacement |
| Web owner + composing DOM | OPEN | Real candidate lifecycle with owner accept/reject, protected node and async stress | Web release |
| Collaboration engine/encoding/Undo | OPEN | Yjs/Yrs or Automerge two-peer structural and raw-unit trace | Stable sync/schema/API |
| Sections, table spans, suggestions | OPEN S0/R0 design | Single serialized example + command/Undo/merge/import witness | Production schema freeze |
| Glyph-to-semantic PDF bridge | Prior candidates rejected | Same-plan Urdu mixed text/image/table, visual + extraction + selection | GP0/print claims |
| Large-paragraph and retained-root cost | Unqualified | Full pipeline timings and retained heap on declared hardware | Performance claims |
| Durable provisional/quarantine input | Not implemented by checkpoint copies | Kill/recover/apply/discard with exact base/resources | Lossless crash recovery claims |
| Stable marker policy migration | D05 specified; production still uses old vote until next repair | Stored marker style, sibling lanes, exact codec/Undo migration and multilingual visual tests | Marker behavior qualification |
| Font fallback/emoji/word boundaries | Partly tested; sequence appearance open | Cluster-aware fallback and dictionary/word fixtures per backend | Language-quality claims |
| Licenses, redistribution and updates | Project license undecided | Selected artifact/file-level inventory and notices, owner license decision | Publication |

### 9.3 Weaknesses of this v6 proposal

The reuse plan can still underestimate adapter work: DOM/CRDT/native models encode different identity and lifecycle assumptions. The elaborate input contract can overfit synthetic traces without real IMEs. A full inherited contract appendix is precise but may retain obsolete assumptions; reviewers must flag contradictions rather than rely on a blanket precedence sentence. Source-only comparison does not prove our current core is the cheaper route. A measured B+ design is not a finished implementation. Public API stability before C0/S0/GP0 would be premature. Current shared renderer-based geometry checks cannot independently prove the renderer correct. Expert Urdu and other-script review remains necessary.

Do not resolve those risks by asserting “perfect plan.” Freeze only a clear, reviewable specification with named gates and controlled change rules. Blocking contradictions for the next authorized slice must be fixed before its work starts. Open future product semantics require a decision gate before dependent implementation; they do not authorize agents to invent silent policy.

### 9.4 Argon disposition and finalization protocol

For each review finding record ID, exact section, minimal counterexample, affected invariant, severity, evidence level, proposed remedy and acceptance test. Classify CONFIRMED DEFECT, VALID REQUIREMENT GAP, ALREADY COVERED, POLICY CHOICE, UNSUPPORTED CLAIM or EXPERIMENT REQUIRED. Reproduce genuine executable claims when tools/environment exist; otherwise leave them unverified. Resolve conflicts using the owner's requirements and observed evidence, not model reputation.

The owner requested finalization after both reviews. Final `v6.0.0` records the corrected specification, finding dispositions, executed witnesses and explicitly open implementation gates. No Git tag/push/publication is implied. A separate next-session prompt names the bounded qualification/marker repair milestone. Further material changes require a versioned amendment; the submitted review.1 snapshot remains preserved.

## Primary reference notes

The upstream annex pins source inspected in this turn. Broader primary references: [Unicode segmentation](https://unicode.org/reports/tr29/), [Unicode bidi](https://unicode.org/reports/tr9/), [ICU boundary analysis](https://unicode-org.github.io/icu/userguide/boundaryanalysis/), [HarfBuzz manual](https://harfbuzz.github.io/), [Skia modules](https://skia.org/docs/user/modules/), [Lexical editor state](https://lexicaljs.org/docs/concepts/editor-state). These establish component responsibilities and available mechanisms; they do not certify this integration. The initial research did not execute upstream binaries/tests. Final disposition additionally executed bounded witnesses against the existing pinned Yjs 13.6.33 package; no upstream full suite, native platform or PDF pipeline was qualified.
