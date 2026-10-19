# Open-source Compose Multiplatform WYSIWYG editor library — plan v5.2.0
Revision: 5.2.0, 17 October 2026. Status: owner-requested product integration replan, PLANNED and UNQUALIFIED. Retains applicable v5.0/v5.1 local contracts; adds Urdu-first acceptance and explicit collaboration/review/schema design gates. No new production gate passes and no implementation changes are implied.

This supersedes v5.1.0 as the current library planning baseline through sections 10 and 11. PRODUCT-ROADMAP.md owns overall product sequencing; FEATURE-CATALOG.md and URDU-ACCEPTANCE.md define product acceptance. Section 11 governs new integration constraints; unresolved algorithms must be settled at their named gates before public API/schema freeze. The original v5.0.0 files and published tag remain immutable historical evidence. plan-v4.md and older standalone documents remain immutable historical evidence. The owner has withdrawn the novelty requirement. This is an engineering plan using established techniques, not a breakthrough claim. Earlier plans and research remain evidence, not competing specifications. MUST means an acceptance requirement; a proposal or unrun test is never evidence that a requirement passes.

**Product mission:** the owner's Urdu-first Google Docs–level application, powered by an open-source embeddable WYSIWYG rich-text/document editor library for Compose Multiplatform. The desktop prototype is a validation harness/example consumer, not the final product. Section 10 defines paged output; section 11 defines application/review/collaboration boundaries. This library plan is one part of the product roadmap, not the whole app specification.

## 1. Chosen architecture and reasons

Build a headless Kotlin document engine with one transaction owner, independent attributed history, and platform adapters. Render native surfaces with Compose text layout/drawing on Android, iOS and Desktop. Use a semantic DOM surface with ProseMirror input/view machinery on Web, synchronously governed by the Kotlin engine. No native WebViews, custom bidi index or new shaper is required by the local editor baseline. Collaboration's replication/merge algorithm is an explicit C0 decision under section 11; it is neither assumed solved nor prohibited by the earlier local-only design.

The editor engine factors document state into in-memory committed storage $D_c$ (governed by the hierarchical node schema and persistent B+ tree) and a piecewise segment-mapped provisional overlay $\mathcal{O}$ during active composition. Visible text and attributes $D_v$ are the deterministic projection $\Pi(D_c, \mathcal{O})$. Native buffers, ProseMirror state, layout and accessibility are downstream projections. Immutable old snapshots are history/recovery data, not competing editable documents.

The core does not depend on `TextFieldState`, `TrackedRange`, `ExpandPolicy`, Compose style objects, DOM nodes or ProseMirror positions. Compose's tracked styles remain useful for ordinary fields and as comparison fixtures. They do not own this editor's persistent styles, bookmarks, undo or composition lifecycle.

### 1.1 Fixed product scope

- Continuous and page-based WYSIWYG rich documents: paragraphs, headings, quotes, nested lists, code blocks, unmerged rectangular tables, inline/block atoms, images and links.
- One local editing session and one active native input connection per editor. Multiple editor instances remain independent.
- Cross-block selection, directional selections, explicit cell selections, rich clipboard, crash recovery and editable accessibility.
- Shared document/command semantics. Continuous native display may differ in rasterization, line breaks and input conventions. Paged preview, PDF and print must satisfy section 10's common-page-plan fidelity contract; portable paged profiles pin typography/resources. Hardware rasterization is not promised pixel-identical.
- Standard capacity fixture: 1,000,000 UTF-16 units across 10,000 text blocks. Large single paragraphs are a separate qualification workload, not covered by block virtualization.
- Required: page-based editing, page settings, explicit page breaks, headers/footers/page numbering, PDF export and print-perfect page geometry under the qualified profile (section 10).
- Deferred from the initial local-library milestone, not from the product: collaboration/review/version-history integration and merged/split-cell support, now governed by section 11 and the feature catalogue. Nested tables and multiple simultaneous local carets remain explicit product decisions. Previous-process Undo is separate from required persistent version history. Toolbar formatting still waits for qualified composition settlement.
- Initial IME replacement of a selected range supports cross-paragraph and cross-container text, removable selected inline/block objects, and whole tables in one flow. It is one provisional gesture and one undo group. Later unselected reconversion/expansion has stricter structural admission. Cells remain separate flows; protected objects and unrepresentable object-seam proposals have explicit handling (§4.3).

Native canvas gives rendering control without reimplementing shaping. DOM on Web reuses browser input and accessibility behavior. Independent core/history fixes the verified deficiencies without requiring four independent semantic editors. The tradeoff is substantial bridge work; shipping any platform is conditional on its input and accessibility gates.

### 1.2 What changed from v1

| Gap or unsafe assumption | Required correction |
|---|---|
| Flat live-document buffer conflated provisional replacements with original text | Adopt separated committed storage $D_c$ + segment-mapped provisional overlay $\mathcal{O}$ (Candidate B architecture from run-20261014-05); visible text is projected; original units map directly to $D_c$; rollbacks restore exact committed state without surplus variables (§4). |
| Grapheme merging overwrote constituent formatting | Retain raw attribution; derive cluster presentation. Only explicit formatting commands change attributes (§5). |
| Candidate formatting depended on each preceding temporary string | Reconcile within an attribution epoch against its immutable baseline; define partial-commit reset behavior (§4). |
| Compose undo/saving treated as possible rich-document authority | Engine-owned attributed/structural history and canonical persistence (§7). |
| Formatting could disrupt composition | Queue document/style commands until a verified settlement boundary. IME decoration is separate (§4). |
| All native offsets treated as caret or code-point positions | Typed units; exact protocol ranges; separate drawable caret; atomic code-point deletion preflight (§3, §6). |
| Ordinary attributed runs confused with overlapping span semantics | Adopt explicit property-only formatting. Do not claim compatibility with arbitrary retained Compose span handles (§5). |
| Plaintext native rewrites could imply structural identity | Tagged projection and constrained native admission; structure comes from explicit semantic commands (§6). |
| End notifications or elapsed time implied completion | Platform-specific completion evidence; no timer-generated commit (§4). |
| Incremental bidi experiments implied production speed | Exclude them. Measure whole pipeline and retain full paragraph correctness (§8). |
| Versions and Unicode releases assumed current/stable | Freeze verified manifests. Historical probe versions are evidence only (§8). |

### 1.3 Evidence boundaries

The repository contains executed headless Desktop 1.12.1 probes, reduced-model tests and a clean Windows native-layout benchmark. It does not establish functioning device IMEs, four-platform accessibility or a production editor. Detailed evidence appears in §9.

The measured 12,289-unit adversarial paragraph took approximately 218 ms for Compose intrinsics, versus 0.22 ms for a separate native bidi constructor. These measurements have different scopes and cannot establish the exact bidi share. Skia retained-record bookkeeping is a plausible defect candidate; its native patch has not passed validation. Neither it nor a claimed fix is a prerequisite assumed solved by this plan.

## 2. Core data models, interfaces and algorithms

### 2.1 Modules and ownership

| Module | Owns | Must not depend on |
|---|---|---|
| `editor-contracts` | IDs, positions, commands, receipts, errors, capability manifest | Platform UI/runtime classes |
| `editor-core` | Schema, text storage, attributes, reducer, maps, registered anchors | Compose, DOM, native input |
| `editor-unicode` | Pinned grapheme/word data, UTF-16 helpers | Host segmentation as sole oracle |
| `editor-session` | Selection, typing intent, input state machine, history | Native adapter internals |
| `editor-persistence` | Canonical serialization, journal, migrations | Physical tree-node representation |
| `editor-layout-compose` | Paragraph cache, block-height index, immutable geometry frames | Document mutation |
| `editor-review`, `editor-sync-contracts` | Experimental authored-change/review/replication interfaces (section 11; C0/R0) | Independent mutable document, account/provider SDKs in core |
| `editor-pagination` | Page profiles, fragment maps and immutable page plans (section 10) | Document mutation or independent history |
| `editor-export-pdf`, `editor-print-*` | PDF and platform print output from qualified page plans | Independent repagination of the document |
| `editor-surface-compose` | Drawing, pointer navigation, menus, scroll | Direct text mutation |
| `editor-input-android/ios/desktop` | Host API translation and connection lifecycle | Persistent style ownership |
| `editor-web` | ProseMirror projection, indexed position translation, DOM input | Independent undo/history |
| `editor-accessibility-*` | Reading order, editable actions and geometry | A separate editable document |
| `editor-reference`, `editor-tests`, `editor-benchmarks` | Independent simple models, traces, measurements | Production algorithms reused as expected answers |

All authoritative edits run synchronously on an explicit owner dispatcher: Android/UI, iOS/main, Desktop/event-dispatch, Web/main. Background jobs operate on immutable snapshots and publish only through revision-checked receipts. Thread ownership assertions are enabled in debug/test builds.

### 2.2 Schema and identities

`Document` contains a body `Flow`, resources, schema version and extension registry. A flow contains blocks. Paragraph/heading contains inline text and atoms. Code block contains literal text. Quote contains blocks. List contains nonempty list items; each item contains one or more blocks and may begin with a nested list, quote or object after an edit. This deliberate schema choice avoids inserting unrequested paragraph separators during normalization (§4.3). Each table cell has an independent flow. A table is atomic in its parent flow. Every flow has at least one legal editable text block after structural normalization.

IDs are opaque 128-bit values serialized as hex strings. Splits retain the left ID and allocate the right ID; joins retain the left ID; moves retain IDs; copying allocates fresh IDs; undo restores original IDs. ID allocation is injected into commands so deterministic replay never depends on a new random value.

A `FlowId` distinguishes cell text from its surrounding document. Flattening text inside quotes/lists retains ancestry metadata. Cell flows are never implicitly joined.

```kotlin
data class DocumentRevision(val value: Long)
data class SessionRevision(val value: Long)
typealias Revision = DocumentRevision
data class InputStamp(val epoch: Long, val inputGeneration: Long,
    val sequence: Long, val sessionOrdinal: Long?,
    val adapterSessionToken: AdapterSessionToken?)
data class Utf16Offset(val value: Int)
data class CodePointCount(val value: Int)
enum class Association { Before, After }
enum class TypingSide { Left, Right }
enum class LineAffinity { Upstream, Downstream }
enum class BidiAffinity { Primary, Secondary }

sealed interface DocPoint {
    data class Text(val flow: FlowId, val block: NodeId, val offset: Utf16Offset) : DocPoint
    data class Gap(val flow: FlowId, val parent: NodeId, val childIndex: Int) : DocPoint
}
data class PositionRef(val revision: Revision, val point: DocPoint, val association: Association)
sealed interface Selection {
    data class TextRange(val anchor: PositionRef, val focus: PositionRef,
        val lineAffinity: LineAffinity, val bidiAffinity: BidiAffinity) : Selection
    data class Node(val node: NodeId) : Selection
    data class Cells(val table: NodeId, val anchor: NodeId, val focus: NodeId) : Selection
    data class Blocks(val first: NodeId, val last: NodeId, val backwards: Boolean) : Selection
}
```

Normative distinction between `DocumentRevision` and `SessionRevision`:
- `DocumentRevision` monotonically advances if and only if semantic document content (text), attributes, tree structure, or resources change. A no-op edit, selection movement, typing intent change, or preedit update with identical text and attributes MUST NOT advance `DocumentRevision`.
- `SessionRevision` advances exactly once per successfully executed and published external request, including selection-only, typing-intent-only, and accepted net-no-op requests. The transaction owner assigns `beforeSessionRevision + 1`; reducer helpers and logical history operations cannot increment it independently. Deferred transport acknowledgements, duplicate-delivery responses, and rejected/rolled-back requests do not publish a new session revision. A deferred request advances it when execution succeeds. Every successful execution receipt contains both document and session revisions.

Cross-flow ranges must become a cell/block selection. Direction belongs to anchor/focus and is not lost by sorting endpoints for editing. Registered anchors carry association and deletion status; raw offsets are never durable bookmarks. Revision and ID transfer to JS uses strings or exact integer types, never an imprecise `Number` conversion.

### 2.3 Canonical text and attributes

Use a persistent measured B+ tree per text block: maximum branch fanout 32, target immutable text-slab storage 1,024–4,096 UTF-16 units; index leaves contain up to 32 piece descriptors independent of slab length, split/merge occupancy 16–32 for non-root branches. Pieces reference immutable slabs with attribute-run metadata. A physical split avoids cutting a valid surrogate pair where possible; logical operations still retain native UTF-16 semantics. Aggregate UTF-16 lengths; validate arithmetic before overflow. Flow projection length is limited to `Int.MAX_VALUE - 1` for adapters; oversize import is rejected explicitly, never truncated.

Attributes are interned canonical maps from semantic keys to values. No overlapping persistent formatting span stack. `Inherit`, `Value(v)` and `Clear` are distinct patch operations. For booleans, `Value(false)` suppresses inherited true; `Clear` restores inheritance. Explicit absence for links/code is a defined value when needed. Store semantic font references, dimensions, colors, links, language and supported direction overrides. Compose/browser objects are derived.

Attribution is associated with retained UTF-16 text units/runs. It can exist inside a currently merged grapheme. Do not erase it during insertion, deletion, normalization, import, layout or save. An explicit range-format command expands its target to complete graphemes and intentionally edits all their units (§5).

The canonical buffer accepts arbitrary UTF-16, including isolated surrogates received through native protocols. This is a deliberate correction to v1's automatic settlement sanitization: accepted input is not silently rewritten. Record an invalid-UTF-16 diagnostic; render each isolated unit as one replacement-glyph unit in a derived view, preserving offsets. Canonical storage and recovery preserve raw units. Valid-Unicode exports replace isolated units with U+FFFD and return an explicit conversion report. Never apply NFC/NFKC while editing.

This lossless policy avoids claiming that native UTF-16 deletion cannot create malformed text. Imports and user-visible navigation have separate validation rules; native code-point commands use their stricter preflight contract.

### 2.4 Primitives and public reducer contract

Closed primitive set: `SpliceInline`, `PatchInlineAttributes`, `SplitTextBlock`, `JoinTextBlocks`, `SpliceChildren`, `MoveNodes`, `PatchNodeAttributes`, `PatchResources`. Each validates preconditions and returns a new root, forward/inverse operation, position map, changed IDs and invalidations. Removed slices/subtrees retain text, attributes and IDs. No platform mutation reaches storage directly.

```kotlin
interface EditorEngine {
    fun snapshot(): EditorSnapshot
    fun dispatch(command: CommandEnvelope): CommandResult
    fun acceptInput(callback: InputCallback): InputResult
    fun registerAnchor(position: PositionRef): AnchorHandle
}
// Envelope: expected revision, origin, command, captured target/typing intent,
// optional registered anchors for an explicitly rebasable async command.
// Result: Applied(receipt), NoChange, Deferred(ticket), Rejected(code).
// InputResult: Accepted(receipt), Deferred(ticket), Rejected(reason), Duplicate(previousReceipt),
// DuplicateReceiptExpired, Fault(recoveryRecord). Rejection reasons include stale identity and unsupported input.
```

`EditorSnapshot` contains document root, current revision, selection, typing intent, raw input state and composition metadata. Layout is referenced by its own key, never implicitly declared current. A receipt includes before/after revisions, maps, changed nodes, projection delta, selection, history group, invalidation category and native acknowledgement requirements.

Storage bounds are defined in §4.8 and explicitly include hierarchy depth, touched pieces, attributes and anchors. No flat block-count logarithm covers arbitrarily nested containers. The array/tree-copy validation models do not establish production bounds or memory use.

### 2.5 Mapping algorithms

For insertion of length m at a: positions below a stay; above a shift by m; equal a stays for Before and shifts for After.

For replacement `[a,b)`, `a < b`, with m units: below a stays; a maps to a; interior positions map to a/`a+m` by association and return `deleted=true`; b maps to `a+m`; later positions shift by `m-(b-a)`. Maps compose in operation order. Endpoints carry separate `contentDeleted` diagnostics when their adjacent referenced content disappears.

Split at k maps right-side offsets to the new block minus k; exactly k follows association. Join maps the right block to `oldLeftLength + offset`. Move preserves internal points by node identity. Deleted nodes map to a precomputed surviving landing point; never leave a point in a removed flow.

Selection mapping is explicit: if both endpoints are consumed by one replacement, collapse to its designated landing point. Otherwise map them, restore the original selection direction, and validate scope/type. Undo restores recorded selections, not an attempted inversion of a lossy map. Registered-anchor restoration uses recorded anchor deltas when that handle has not been explicitly relocated since the history entry; otherwise use the normal inverse-operation map. Document annotations are part of the restored document.

### 2.6 Structural command compilation

- Enter splits paragraph; heading-end creates paragraph; heading-middle creates same-level heading. Shift+Enter inserts soft LF. Code-block Enter inserts literal LF.
- Nonempty list Enter splits item, moving trailing nested blocks with the tail item. Empty list item lifts one level, or becomes a paragraph at outermost level. Empty quote paragraph exits one quote level.
- Backspace at the leading gap/first text position of a list item lifts before joining; it does not assume child zero is a paragraph. Ordinary same-parent compatible text blocks join, preserving left block properties. Paragraph/heading are compatible; code/non-code are not implicitly joined.
- `DeleteSelection`: use the structural compiler in §4.3. Selected text endpoints may join across parents or code/prose classes with left block identity/type/properties winning. Direct sibling list items merge trailing children; other surviving ancestry and global leaf order remain unchanged. Selected removable objects can be consumed; locked content cannot. Table-cell selection uses the separate rule below.
- `ReplaceSelection` executes that deletion then inserts at its landing point in the same transaction. Reversed and forward ranges produce the same document. Textual paste LF creates blocks only under the paste policy; opaque native LF inside a text block is a soft break, not an inferred Enter command.
- Table cells do not merge implicitly. Delete cell selection clears cells and ensures one paragraph per cell. Typing replaces selection in the top-left cell after clearing. Tab advances cells; Tab at the last cell appends a row; Shift+Tab at the first exits before the table. Empty cells and boundary destinations are explicit.
- Post-command normalization prunes empty containers, preserves the editable landing block, and follows §4.3. List items may begin with any block; do not synthesize a paragraph that changes the admitted native projection. Termination follows decreasing empty-container count and bounded tail moves.

Rich-paste slices carry open ancestor depth and schema-typed nodes. The first implementation accepts fully closed block slices and inline slices. Partially open HTML structures are converted to closed supported blocks by the importer with a report. Do not leave slice-fitting semantics for adapters to invent.

## 3. Formal invariants and verification strategies

| ID | Required invariant | Primary verification |
|---|---|---|
| I01 | Only the owner reducer changes document semantics. | Mutation visibility/API tests; owner-thread assertions. |
| I02 | A callback publishes either one coherent snapshot or nothing. Failed transactions publish no document/history change or native text synchronization. Public and reentrant protocol reads observe only the last published snapshot. Working-state access is private to the reducer. Notification completion/rollback is explicit and never exposes abandoned staging. | Exception injection at every reducer stage. |
| I03 | Unique IDs, valid schema, acyclic ancestry and a legal caret destination in every flow. | Independent tree validator after randomized structural edits. |
| I04 | Positions resolve at their revision; stale commands reject unless explicitly rebasable. | Exhaustive small maps; split/join/move/delete traces. |
| I05 | Native UTF-16 operations preserve exact accepted units; code-point commands never use unit counts accidentally. | Surrogate boundary matrix and malformed-range preflight. |
| I06 | Grapheme/layout changes never modify stored attribution. | WOMAN/ZWJ/LAPTOP round trip; combining marks; save/load. |
| I07 | Within one attribution epoch, identical final candidate text gives identical attributes independent of intermediate candidates. | Generated candidate detours against a separate reference. |
| I08 | Partial commit freezes committed attributes; later suffix cancellation cannot erase that prefix. | Partial-commit and changed-mark-range traces. |
| I09 | Each adapter callback is accepted once by its stamp; lifecycle end alone never inserts event text. | Duplicate delivery and varied event-order traces. |
| I10 | A closed connection epoch cannot mutate a newer connection. | Late callback injection across blur/restart/disposal. |
| I11 | Geometry is returned only with its actual document/layout/font/viewport key. | Delayed work, scroll, zoom, font arrival and edit races. |
| I12 | Undo/redo restore exact content, structural IDs, raw attribution and recorded selection. | Canonical serialization before/after, including composition. |
| I13 | Input text projection and accepted native proposal agree within the declared admission scope. | Check after every callback, not only on blur. |
| I14 | Durable acknowledgement follows durable storage; truncated/corrupt journal tails never become edits. | Crash injection and checksummed recovery tests. |
| I15 | A normal local edit never flattens or serializes the whole document. | Traversal/allocation counters and scaling tests. |
| I16 | Formatting-only and net-no-op commands do not manufacture text replacements or input restarts. | Notification spies and active-input fixtures. |
| I17 | Platform rendering and accessibility act on the same semantic document revision. | AT edit/read-back and offscreen navigation tests. |

Use a simple reference reducer with ordinary strings/run lists/trees; it must not call production maps or candidate-reconciliation code. Exhaustively check short cases and run deterministic fuzzing with saved seeds and shrinking. Run at least 100,000 operations per core suite across 20 seeds in qualification; nightly runs broaden counts. Counts are coverage targets, not proofs.

v5 pins Unicode 16.0.0 data and hashes for segmentation; see section 4.12.4. Do not retain v1's unverified Unicode 18.0 release assertion. Run its official grapheme-break and word-break data. Segmentation crosses piece boundaries and scans until state and unchanged suffix converge; no fixed lookaround bound. Treat isolated UTF-16 units as individual replacement-class navigation items without modifying storage.

UI user commands snap/expand to legal grapheme stops; protocol selections remain exact native ranges. The drawable caret may be projected to a legal glyph edge while preserving raw input selection. Programmatic native selection echoes must not overwrite the logical selection merely because drawing used a nearby legal edge. Bidi visual movement uses layout caret stops, not offset ±1.

Safety model checking covers two epochs, at least three callbacks, nested batch notifications, pending external commands, partial commit, failure, disposal and reentrant queries. Liveness is conditional on actual adapter completion; elapsed time is never a proof of native settlement.

## 4. Input, structural composition, settlement and history contracts

Revision 4.12.0 replaces the previous section 4 in full. It incorporates useful ideas from both external review rounds and the existing executable coordinator, but does not import their contradictory prose or claim that synthetic adapter tokens prove native provenance. The algorithms below are normative. Reference-model boundaries and experiments are stated in §9.

### 4.1 One executor, publication and immutable requests

Every public mutation (native input, application command, selection, Undo/Redo, focus change, connection replacement and disposal) enters one owner-thread request executor. Capture immutable data, never caller-owned closures. Public reads and will-change notifications see the last published snapshot, never a working tree. Input, structural compilation, history operations and preexisting settlement-command drainage stage in a private transaction. Publish one coherent version bundle and receipt, then notify did-change observers. A thrown internal failure restores the complete working preimage, outcomes, receipts and history; externally received reentrant requests remain queued for later execution. Deterministic command rejection is an outcome, not an exception.

The bundle includes canonical document, measured hierarchy and text indexes, attribute roots, overlay, history/session state, anchor maps, selection and typing intent. DocumentRevision increments once iff the FINAL visible semantic document changes (text, attributes, structure or resources). SessionRevision increments once per successful external publication, including metadata-only and no-op publications. Rejection, duplicate receipt and deferred transport acknowledgement increment neither. Receipt installation precedes did-change notification so a duplicate received there cannot execute twice. Validate connection/generation/session identity before consulting duplicate receipts.

Process at most 64 external queued requests per owner turn; schedule a macrotask continuation whenever work remains. Do not wait for further user input. Reentrant requests use the same validation pipeline as immediate requests. Distinguish the external request queue from the session's pending application-command queue.

An explicit range is validated as exactly two present safe-integer endpoints within its named basis before sorting or mapping. Sparse arrays, extra endpoints, nonintegers, NaN and infinities reject without coercion. It carries its published basis revision, exact content identities and boundary associations. Map those identities to execution time, require contiguous native replacement content, reject lost/fragmented content rather than take its convex hull, and validate native selection in the proposed post-edit coordinate space. Captured application tracked ranges may resolve into surviving disjoint intervals. Requests never combine published revision numbers with staged identities.

An input result is `Accepted(receipt) | Deferred(ticket) | Rejected(reason) | Duplicate(receipt) | DuplicateReceiptExpired | Fault(recoveryRecord)`. Deferred means transport acceptance only; every ticket later receives exactly one terminal outcome. Rejections include stale epoch/generation/session, failed dependency, invalid range/selection, lost/fragmented target, protected/read-only content and unsupported structural projection.

### 4.2 Provenance, admission reservations and target descriptors

`InputStamp = (connectionEpoch, inputGeneration, sequence, sessionOrdinal?, adapterSessionToken?)`. A composition supplies a positive monotonic session ordinal and its opaque token; direct noncomposing input supplies neither. Reservations are keyed by epoch/generation/ordinal and verify the token. Detailed retired reservations can be released under §4.10 without permitting session reuse. The adapter, not arbitrary application payload, establishes verified provenance. A stamp assigned at adapter receipt proves ordering after receipt; it does NOT prove when an unstamped OS callback originated.

There are three immutable target descriptors:

1. `ExplicitBasisRange(basis, ids, boundaryAnchors)`: map the exact requested range as in §4.1.
2. `Continuation(epoch, generation, sessionToken, initiatingTicket)`: for an identified composition, resolve at execution to its current marked interval; if nonterminally unmarked, use that session's tracked unmarked extent. Never fall back to the global caret. Reject terminated/failed/mismatched sessions.
3. `NewAtBasisSelection(basis, selectionIdentities, selectionGeneration)`: for genuinely new input, use the published captured selection. A later explicit relocation invalidates it unless an explicitly identified dependency/basis permits remapping. It is not a continuation merely because another composition happens to be active when it executes.

The admission registry records `((epoch,generation,sessionOrdinal) -> token, engineSessionId, initiatingTicket, Reserved/Active/Failed/Terminated)` before an initiating request executes. This is transport bookkeeping, not publication of a working document. Reentrant callbacks with the same verified token can depend on that reservation. Any failed initiating request marks the reservation Failed and its dependent continuations reject; none silently become fresh replacements. Live/dependent reservations are pinned; retired session identity is remembered by a monotonic ordinal high-water mark under §4.10 rather than an unbounded tombstone set. The reservation state and request identity are immutable to callers.

FIFO abort/relocation barriers take effect when executed, not by retroactively changing the generation of earlier admitted requests. A new request naming `AfterTicket(t)` may use the successful published receipt of t as its basis only if its adapter protocol explicitly defines those coordinates. Failure of t rejects the dependent request. It cannot read a future selection from the ordinary snapshot. If an intervening edit occurred after t, use t's retained basis and identity mapping, never an assumed current integer offset.

Worked trace: published `hello world`, selection [0,5). Requests A:`preedit(n)`, A:`preedit(ni)`, abort A, selection [11,11), late stamped A:`commit(你)`, then new input `!` with a qualified new-generation fence and an explicit selection-ticket basis. FIFO execution produces `hello world!`; the late A callback rejects. Earlier A preedits execute before the barrier. Without the explicit selection-ticket basis, the last request cannot pretend [11,11) was published at initial capture.

**Host qualification is mandatory.** For each platform/keyboard, document the callback lifecycle and the boundary that separates pre-abort from post-abort input. An adapter-generated counter alone is insufficient for a callback first delivered after a reset. Until a separation mechanism is qualified, do not force abort-and-continue as an assumed capability: use verified natural settlement, or preserve ambiguous raw input/preimages in recovery and stop automatic admission. Never guess that an ambiguous commit is old and discard it, or guess that it is new and insert it at a relocated caret. G0/G5 must exercise this distinction on real hosts. Normal native finish/unmark is not automatically application cancellation.

### 4.3 Tagged provenance and a single structural compiler

Each flow has one canonical hierarchy and a measured native UTF-16 projection. Text leaves project their raw text/inline atoms; block objects (including a whole table in its parent flow) project one U+FFFC; consecutive leaf blocks are separated by one tagged LF. Table cell contents belong to distinct flows. Literal LF and U+FFFC remain distinguishable from structural units by metadata.

Unit variants:

```text
TextUnit(id, raw16, rawAttributes, originSession)
InlineAtomUnit(id, nodeId, atomKind, payload, rawAttributes, originSession) -> U+FFFC
BlockObjectUnit(id, nodeId, immutableSubtreeOrResource, originSession) -> U+FFFC
BoundaryUnit(id, rightLeafId, immutableProvenanceVersion) -> LF
```

Provenance versions retain leaf headers, parent paths, container properties, resources and stable IDs. A BoundaryUnit is NOT an executable instruction to close/open containers. Deleting an earlier boundary cannot leave an imperative unmatched close. Production stores persistent references to provenance, not a full copied ancestor list per character.

Initial replacement of an explicit noncollapsed user selection in one flow admits removable selected atoms, whole selected tables, list/quote transitions and code/prose endpoints. Selected structure is provisional until commitment; it is never deleted as a separate preparatory history group. Locked/read-only content remains protected. `Selection.Cells` is a distinct semantic command: clear selected cells, then insert in the top-left cell; cells never silently merge. Later reconversion/expansion without an explicit selected-structure gesture may extend only within the primary landing head's current parent and compatible join class (Paragraph/Heading = prose; CodeBlock = code). Initially selected foreign-owner blocks and structural unit identities remain explicitly admitted, but selecting part of a foreign container does not authorize all of its unselected contents. Check both endpoint owners, even for a disjoint target wholly inside another block, and every newly crossed boundary. Same parent alone does not establish prose/code compatibility. No previously unselected object or container transition is acquired implicitly. The adapter must distinguish initial selection from arbitrary reconversion rather than setting a caller-trusted boolean.

The selected structural replacement compiler uses the following rules, shared by DeleteSelection, ReplaceSelection and IME overlay materialization:

1. Resolve the interval against its immutable tagged provenance. Retained units keep exact identities and properties. Newly synthesized candidate units are TextUnits. Retained boundary units define result block heads; removing a boundary joins into the preceding surviving head. The first block is represented by a versioned head record, including an empty landing block when all content is removed. A deleted object head plus its consumed trailing boundary cannot absorb a surviving right leaf; apply the explicit head-resolution rule in section 4.12.3.
2. For an explicitly selected text-to-text replacement, the left head's ID/type/properties win even across code/prose or different containers. Preserve right suffix text/attributes. This is a deliberate change from the old same-parent/join-class rejection. Unselected native expansion keeps the stricter domain rules above.
3. Retained block heads retain original ancestry except for the explicit sibling-item moves below. Process joins in original leaf order while maintaining CURRENT working parent relationships; recompute/update the parent index after every move. Provenance supplies identities/content, not stale ownership. Never resurrect an emptied item by using its original parent as the destination of a later join. Remove consumed leaf heads and prune containers with no remaining children bottom-up. Do not lift unselected descendants out of surviving ancestors or reorder them to repair a guessed grammar.
4. A narrow ordinary list-join rule applies when the surviving left head and a consumed right head are CURRENTLY direct children of distinct sibling ListItems under the same current List. Move the right item's surviving trailing children into the left item in original order and remove the empty right item. All content between the two selected endpoints has already been consumed; no surviving intervening sibling can be jumped over. Do not apply this rule to ancestor/descendant items or across different lists.
5. Otherwise leave all surviving trailing blocks in their original containers. Equal-item, ancestor-item, descendant-item, unrelated-list, list-to-outside, outside-to-list and quote cases all follow this same rule; no exhaustive list of special movement cases is needed. Global preorder of unselected leaf content is invariant. Moving direct-sibling tails under rule 4 preserves that order.
6. The schema deliberately permits `ListItem ::= Block+`, including an item whose first surviving child is a quote, object or nested list. Empty items are removed; a flow always retains a legal editable landing text block. A headless item does not gain a synthetic paragraph merely to repair grammar. List marker layout and insertion-gap accessibility must handle this schema. Explicit Enter at the item's leading gap creates a paragraph; Tab/indent/outdent operates on the item, not an assumed first text child. This is a product/schema choice, not an optimization.
7. A group containing a retained block object must remain that object's leaf, with its original payload. A raw edit that deletes only a block-object seam while preserving an object in a text group has no exact legal projection and rejects `UnsupportedStructuralProjection` before publication. Semantic Backspace/Delete at an object boundary selects/deletes the object or leaves its block boundary according to the explicit node-selection command; it must not send an invalid scalar text deletion. Selecting and typing over the object itself, including surrounding paragraphs, IS supported. A host that insists on unrepresentable seam rewrites for ordinary supported actions must fail qualification or obtain a specified alternative adapter mapping; this is a visible remaining integration risk, not permission to drop typed input.
8. Validate unique IDs, schema, preserved unselected order and `project(resultTree) == exact admitted candidate text` before publication. Reject atomically on failure. A model may reconstruct whole trees for clarity; production changes only affected persistent paths. Normalization is terminating: delete empty containers and move each surviving sibling tail at most once. No rule recursively invents repair nodes.

Two endpoint conversions are explicit: replacing only a block object's content while retaining its surrounding boundaries (or with no later leaf merged) converts that selected leaf to a Paragraph at the same NodeId, removing the object payload from the current version (history still pins it). Node identity is stable across this explicit type conversion, as with a heading-to-paragraph conversion. An unmatched literal U+FFFC does not retain the old object. If a selected join's left head is CodeBlock but the surviving inline content includes an InlineAtom, convert that head to Paragraph at the same ID and reset its block properties to paragraph defaults rather than lose the unselected atom or put it in an invalid code block. Ordinary text-only code joins retain code type/properties. Both conversions are structural history changes, restored exactly on cancel/Undo. Retained boundary IDs are provenance identities; a view map relates each retained boundary to its resulting head record rather than assuming the head's type/payload is immutable forever.

Examples: joining an outside paragraph into a list item removes only the consumed text head; a surviving nested list and trailing paragraph remain in that item. Joining an outer item's paragraph with a nested item's paragraph leaves that nested item's trailing TAIL before the next nested item's C. Joining inside-list text to a following outside paragraph keeps the left block in its list and removes the consumed outside head. Removing the opening boundary of a quote can prune the quote; a later boundary retains the next leaf's provenance, not a dangling “close quote” instruction.

### 4.4 Composition overlay, attribution epochs and partial commitment

Committed tagged storage is Dc. The provisional overlay contains ordered `Segment(dcStart, dcEnd, units, origin)` records spanning a CONTIGUOUS cover [sc,ec) in Dc; empty-footprint insertions are allowed. Adjacent segment endpoints meet. Bridge disjoint targets with retained-original units from Dc. The visible tape is `Dc[0:sc] + concat(segment.units) + Dc[ec:]`; the structural compiler derives its visible tree. Dc retains original rollback state until an effect is accepted. History pins S0, the complete before-session root bundle and selection, before the first provisional mutation.

The marked interval [m0,m1) is relative to the overlay's visible units and may cover only one island. Other islands remain provisional. Retarget alone does not commit them. Unmarking and history-session lifetime are separate. Attribution epoch E pins the exact baseSegments, baseWindow, flattened baseline B and insertion seed. A partial commit or explicit retarget starts a new epoch; an ordinary candidate update does not.

Reconcile a new candidate C with B using maximal equal UTF-16 prefix p and nonoverlapping equal suffix s. Slice the BASE segment descriptors for the matched parts and copy complete unit records, including atom/boundary tags, IDs and attributes. Collapse only the unmatched base middle into a replacement segment and synthesize its new TextUnits using the epoch seed. Segments outside baseWindow survive unchanged. Do not use the previous temporary candidate as the next baseline. Empty middle is an insertion at the split boundary, not an inclusive end-index lookup. An unchanged whole candidate takes the exact baseline descriptors. Thus `A + atom + B -> X -> A + atom + B` restores the atom within an epoch.

An unmatched LF is a soft break; an unmatched U+FFFC is literal text, not a new object. Simultaneously changing both ends of `ab\ncd` to `Xb\ncY` makes the entire middle new, including its LF. That deterministic structural interpretation is a documented policy, not recovered native intent. Do not infer boundary identity from newline counts. Product usability and host reconversion traces must qualify this policy.

Segment splitting at visible interior j:

- Retained original: split Dc footprint one-for-one, retaining unit IDs and independent rollback obligations. Atomic one-unit retained segments need no interior split.
- Replacement [d0,d1) with units U: left part owns [d0,d1) and U[:j]; right part owns [d1,d1) and U[j:]. Keep replacement lineage for diagnostics. Splitting again never duplicates an old footprint. A zero-visible-length owner remains as a deletion segment while its effect is provisional.
- Replacing a subrange preserves descriptors outside that range and joins only the touched footprints. Do not reset all matched prefix/suffix descriptors to one replacement footprint. The half-open split operations, not `baseSegmentsAt(end).dcEnd`, determine exact middle boundaries.

`partialCommit(k)` requires an active marked interval and 0 <= k <= m1-m0. k=0 is an accepted metadata event with no content acceptance. For k>0, cut at q=m0+k. **Selected policy:** freeze the entire overlay prefix before q, including earlier provisional islands to its left. This may accept more than the current marked prefix; that behavior is explicit and adapter partial-commit normalization must honor it. It differs from retarget alone, which accepts nothing. Later islands to the RIGHT remain provisional.

Walk segments in order. Transfer fully passed segments into Dc; when cutting inside a retained segment, retain the unconsumed original footprint. When cutting a replacement segment, accepting a positive prefix consumes its entire old footprint; the residual units become an insertion at the accepted boundary. Shift later segment Dc offsets by the exact accepted splice delta. A zero-unit deletion at the cut remains provisional if visible session units remain after the cut; it is accepted if the cut passed it, or at equality only when no visible session units remain after it. Preserve trailing segments even when the current marked candidate becomes empty.

The new marked interval is `[0, (oldM1-oldM0)-k)`, NOT based on the whole overlay length. A full marked commit can leave an empty marked interval plus later provisional islands. Keep the session and pending commands. Partial commit invalidates outstanding settlement evidence and creates a new attribution baseline for the remaining active interval; it creates no standalone history entry.

T5: `abcd`, replace bc with XYZ, retarget YZd, replace it with PQ => visible `aXPQ`. Partial commit P freezes preceding X too, giving committed `aXP` and provisional Q. Cancel leaves `aXP`; one session Undo restores `abcd`. In backward disjoint retargeting, start with CD at [6,8), then AB at [1,3): partial commit A leaves B and later CD provisional; cancel gives `0A3456789`. Committing all AB before cancellation still rolls back later CD.

Semantic formatting islands are tracked separately from overlay footprint and frozen prefix. Unchanged bridge text is never formatted merely because prefix commitment traversed it. Explicit retarget over previously committed text creates a new rollback obligation against CURRENT Dc, not the old S0 content.

### 4.5 Lifecycle table and exact cancellation/removal policy

| Event | Admitted behavior | History / queued work |
|---|---|---|
| start/mark-existing in Idle | Pin S0, create retained baseline and marked interval, enter Composing. | No history entry. |
| preedit with identified session | Reconcile against immutable epoch; update exact native selection; enter/return to Composing. | Keep pending commands; invalidate evidence. |
| implicit first preedit | Same initialization before provisional deletion. | Same one session group. |
| retarget/expansion | Exact admitted interval; preserve all islands, bridge committed gaps, new attribution epoch. | No implicit commit/history. |
| partialCommit | Apply §4.4, keep session; lifecycle label may remain AwaitingSettlement if a candidate still exists. | No final history; invalidate evidence. |
| unmark(nonterminal) | Clear mark but RETAIN overlay and tracked unmarked extent; enter AwaitingSettlement. | Do not flush Dc or push history. Duplicate unmark rejects without mutation. |
| preedit after nonterminal unmark | Resolve identified session's tracked extent, reconcile/update, restore active mark. | Keep S0 and commands; old token obsolete. |
| commit / terminal unmark / verified completeSettlement | Accept remaining visible effects and clear overlay; final native stage precedes command drainage. | One Composition group iff full semantic result differs from S0; drain §4.6 atomically. |
| abortProvisional (application cancel) | Discard remaining overlay. Preserve accepted partial effects in Dc. | Group net accepted change iff nonempty; cancel composition-targeted commands, retain unrelated ones; §4.6 Undo credit applies. |
| nativeRemoval(range,text) | Exact replacement in the VISIBLE tape, then accept surviving visible effects and terminate. It is NOT rollback. | One net session group if active; ordinary edit group if Idle; normal terminal drainage. No ad hoc surplus restoration. |
| openNewConnection | Preserve/accept visible content, finalize net session group, terminate old identity. | Cancel all old pending commands with StaleEpoch; then bump epoch/reset receipt state. Do not silently apply old toolbar commands. |
| dispose | Preserve/accept visible content and net history; close connection. | Cancel pending commands with Disposed; no future admission. |
| focus loss | Execute the adapter's explicitly requested PreserveVisible or AbortProvisional policy; never guess from platform name. | Cancel composition-targeted toolbar requests with FocusLost; explicit unrelated requests may drain under normal mapping. |
| invalid/stale input or evidence | No state publication. | Explicit rejected outcome; no hidden fallback command queue. |

`nativeRemoval` example: abcd -> preedit XY over b -> expand to aXYc -> remove [1,3) in visible aXYcd => acd, not abcd and not d. Undo restores abcd. Native committed-text query coordinates exclude only the current marked range from the visible native projection; they are not necessarily the same as internal Dc storage while nonterminally unmarked.

A normalized native terminal callback supplying selection must have that exact selection validated against the resulting native-stage document and applied before history capture or command drainage, even if no replacement text was supplied. This includes commit-without-text, terminal unmark and completion; invalid selection rejects the whole transaction. Explicit-range selection is mapped once through the proposed post-edit basis, including nativeRemoval replacement text. A native cancel's supplied selection is likewise post-cancellation. Without an explicit native selection, cancellation with no accepted semantic delta restores pre-session selection/direction. After accepted partial effects, collapse to the accepted prefix end within the resulting flow. Preserve current valid selection when already unmarked and there is no residual candidate. History retains exact before/after selection and structural/anchor records for Undo/Redo.

### 4.6 Settlement evidence and a LIFO action ledger for Undo

Formatting, paste and Undo requested during composition remain pending until a qualified completion boundary. Do not mutate composing text on a timeout. A two-second watchdog may report waiting status, not fabricate success. Evidence contains epoch, generation, session, token, relevant revision and source adapter identity. Any intervening preedit, retarget or partial commit invalidates it. Reject completion while a marked candidate remains active or evidence mismatches. A synchronous adapter shortcut requires evidence from its registered capability path, not a caller-supplied true flag.

The terminal native operation and already-pending application commands form ONE atomic publication. History has independently undoable groups in order, not one undifferentiated native-plus-format group. Record the accepted native portion separately from final application effects in the receipt. A known rejected command records an outcome and continues; an unexpected internal failure rolls back the entire publication. Reentrant external requests execute in later transactions.

Use a settlement-local **LIFO action ledger**, avoiding admission-time guesses about future stack depth. This is a semantic change from both “always pop history after cancel” and mutable slot-promotion counters:

1. Queue each Undo as UndoNext; do not reject NothingToUndo at admission based on a stack that settlement can change. Record whether any queued Undo observed a semantically dirty visible or partially committed session at its admission. Compare complete document semantics, not text alone.
2. At terminal execution, first derive the final accepted composition result. Seed the ledger with IDs of actual prior history entries, oldest first. If the session creates a net Composition group, push that actual ID. Otherwise, if an Undo observed dirty session state, push ONE ephemeral `CompositionAlreadyReverted` slot. This slot protects earlier history when cancellation or a return-to-baseline already eliminated the visible action.
3. Drain application commands FIFO. Every admitted history-producing command contributes either its actual new history ID or one ephemeral `CommandWithoutHistory` slot if cancelled/rejected/no-op. This intentionally lets the following Undo consume that pending attempted action rather than unexpectedly undo older content. Typing-intent-only/selection-only commands do not contribute history slots.
4. An Undo pops the ledger's top slot. An ephemeral slot yields Applied/NoChange with its reason and changes neither durable history nor redo. An actual slot MUST equal the current durable undo-stack top ID; assert this before popping and restoring the full before-root bundle. An empty ledger yields NothingToUndo at execution. Never extract an interior snapshot entry.
5. Actual Undo pushes its exact group to Redo. Cancellation credits create no redoable phonetic preedit. A subsequent new semantic command clears Redo normally. Ephemeral slots are discarded after drainage; they are not saved, budgeted as history or exposed as redo entries.

This naturally handles a clean mark-existing session, queued Undo and a changed terminal commit WITHOUT an intervening preedit: the actual Composition group exists before drainage and is popped first. Multiple Undos consume distinct ledger entries. A cancelled pending Format consumes one action slot; the next Undo can remove a surviving partial-commit Composition group; the next reaches prior history. No reserved history ID is assumed to remain top.

For the trace `Hello ` -> preedit ab -> partial a -> queue Bold, Undo, Undo, Undo -> cancel: Bold cancels, first Undo consumes its ephemeral slot, second undoes accepted a, third undoes prior Hello. Redo contains the two actually undone groups, never the cancelled b or cancelled Bold. Validate this and arbitrary interleavings with root continuity and exact-once outcomes.

Connection replacement/disposal cancellation is lifecycle cancellation, not ordinary settlement drainage: cancel every old pending ticket, including Undo, with its explicit lifecycle reason. It does not synthesize ledger actions in a new session.

### 4.7 Selection, formatting targets and structural history

Raw native selection is an exact validated UTF-16 selection in its named post-edit basis; drawable caret geometry is separate. Preserve direction and affinity. Pointer drag pins its logical anchor, native selection echoes suppress only the exact expected update, and vertical navigation retains preferred x until explicit relocation. Toolbar focus alone does not reset typing intent.

Composition formatting targets the semantic constituent islands plus accepted session portions, never the convex hull and never untouched bridged text. Outward grapheme closure occurs per text block. A retained structural BoundaryUnit never receives an inline attribute patch. A deleted target gets an explicit cancelled result. An empty settled composition target cancels the composition-format action with TargetDeleted and one CommandWithoutHistory ledger credit; it does not change typing intent. Accepted surviving session text remains the target. Only an explicitly collapsed caret-format request is typing-intent-only. Unrelated explicit tracked ranges map surviving token intervals and drain on composition cancellation rather than become trapped.

History stores complete root bundles, resources, raw attributes, structural IDs, selection/typing state and anchor deltas. Undo and Redo restore the full bundle, never reconstruct a hierarchy from plain strings. Recorded anchors are restored only when their generation has not been explicitly relocated since the group; otherwise apply the inverse-operation map. Revision counters remain monotonic.

Emptying a text head captures its pre-deletion effective typing seed into versioned emptyBlockAttributes. This is part of provisional structure: cancel restores the old attributes and Undo restores the old head record. Root retention includes admission bases, active composition, history, layout and pending targets; do not reclaim on the assumption that only Undo holds old roots.

### 4.8 Persistent indexes, hierarchy and honest bounds

The production locator uses an immutable measured B+ text sequence, persistent version-wide TokenMap and ParentMap, and measured B+ child sequences for EVERY hierarchy container. The previous versioned-index validation remains evidence for its flat sequence subset, not a completed nested implementation.

Pieces `(bufferId,start,length,baseTokenId)` retain unit identity through splits. TokenMap is an interval-predecessor index from token identity to `(pieceRange,leafIdentity,indexInLeaf)`, never a cached absolute root path. ParentMap links text/child-sequence nodes through container ownership to the flow/document root for this version. Bundle all roots atomically. Global maps eliminate per-block map union, but global ownership is a requirement, not an inferred property of the old flat prototype.

Path-copy touched leaves/branches. Rebuild token entries only for copied text leaves; update parent entries for the bounded children of copied ancestors; remove obsolete entries only from the new version. Splitting/moving blocks preserves identity intervals. Undo root assignment is O(1); target repair, publication, resource accounting and reclamation are additional work.

Let P be text pieces, N all indexed nodes, B bounded fanout, hT text-tree height, and h1..hd the child-sequence heights along d ancestor containers. With AVL map lookup, a token rank has bound `O(B * (hT + sum(hi) + d) * log N)`. Depth is not hidden inside a flat block count. Single local edits include corresponding copied paths and inserted units/attribute/anchor work. Large/deep ranges account for every touched piece, node and required source slice. No unconditional O(log N) claim covers arbitrary depth.

Selected baseline deletion/migration touching K pieces retains its explicit `O(K B log² P)` text-index maintenance bound plus hierarchical work above. A seam-only persistent concat/split optimization may reuse interior leaves and touch O(B) seam token entries and O(B*(hLeft+hRight)) parent entries, PLUS hierarchy paths and global map costs. Keep it unqualified until executed against the nested bundle. Never convert this asymptotic expression into an operation count or milliseconds without measurement.

Each container's child locator points to a bounded-fanout sequence leaf, not a flat sibling index requiring all later siblings to shift. No hard document-depth cap is introduced in this revision; traversal must be iterative and budgeted, with depth counted in measurement. Resource admission limits must be explicit product decisions, never silent input truncation.


### 4.9 Integrated executable owner contract (revision 4.13.0)

The separate admission, structural and Undo reference contracts are now lowered into one executable owner, originally run-17 and now extended in `validation/codex/run-20261016-20/document-model.mjs`. `dispatch(immutableRequest)` is the only edit admission path used by its integrated tests. `snapshot()` exposes a deeply immutable published version; `receipt(ticket)` exposes transport outcomes. `flush()` and an injected owner scheduler drive queued execution and bounded continuation. Native requests use synthetic qualified-adapter stamps in this model. No test-generated stamp proves a real OS callback's provenance.

**One authority, private structural working state.** The structural segment/tree reducer is a private worker under the outer owner. Its legacy history is never consulted as authoritative history, and its intermediate internal snapshots are never passed to external observers. Application formatting and Undo enter the owner's action ledger rather than the worker's old command handlers. History entries retain actual attributed structural before/after roots and selections, not string stand-ins or caller-supplied transform closures. This integration closes the previous run-16 separation for the operations and traces enumerated below.

**Request capture and execution.** At capture, freeze payloads and bind explicit ranges to the published basis. Key wire receipts by `(epoch,generation,sequence)` and session reservations by `(epoch,generation,sessionOrdinal)` with matching opaque token. Repeating a pending wire request returns its original deferred ticket; repeating an executed identical request returns its recorded outcome. A reused wire sequence with different payload rejects SequenceCollision. Stale epoch/generation rejection precedes cache lookup. Once executed, successful native sequences must advance within the connection/generation. Failed requests retain a rejection receipt; retrying a repaired payload needs a fresh sequence, not mutation of the old request.

An omitted-range continuation binds to the reserved/active session and resolves its current marked or tracked-unmarked extent at execution. An explicit range maps exact published identities; fragmented ranges reject. Native post-edit selection accompanying that replacement maps through a synthetic proposed-post-edit identity space, so inserting earlier text does not reinterpret a later callback's selection in the wrong frame. `AfterTicket` uses the compact coordinate basis associated with the accepted publication and pinned under §4.10; failure of the dependency rejects. Application relocation increments both selection generation and input generation at execution and requires a qualified host fence before later native input; abort is its own FIFO barrier. Earlier queued callbacks are never retroactively assigned the new generation.

**One range-admission policy for every callback.** A repeated start/mark-existing callback in an identified active session retargets to its exact admitted interval; it cannot acknowledge a new range while retaining the old one. A preedit, commit or native removal whose explicit range differs from the active/tracked interval must pass the same structural-retarget classifier as a named retarget event before invoking the structural worker. Otherwise a private worker's implicit retarget could bypass protected-object/container rules. This preflight uses the pre-edit range; the callback's native post-edit selection is validated separately in its post-edit space, not passed as a pre-retarget selection. Final review reproduced both failures before repairing this shared path.

Pin the exact structural unit identities admitted by the initial selected-range gesture in the session preimage. A later retarget may retain or narrow over those already-admitted atoms/boundaries; it must reject acquiring a previously unselected protected object/container transition. Do not reject every atom indiscriminately: that incorrectly blocks reconversion of an object already included in the user's selection. These permissions are identity-based, do not authorize a new object with the same projected U+FFFC, and roll back with the session state. A fresh session establishes its own set.

**Full transaction preimage.** Before staging, retain the structural worker and its complete state, published snapshot, active composition and S0, registry statuses, epoch/generation, host-fence state, history/redo, pending commands/outcomes, selection generation, native sequence ledger, and rollback-sensitive history/fence counters. Ticket/session identity allocation is monotonic; externally admitted request identities are not recycled on rollback.

On failure restore that complete preimage. Keep independently admitted reentrant requests in the external queue, including new reservations created during the failed turn. A failed initiating session reservation becomes Failed, and its continuations reject InitiatingSessionFailed. If an already-active session's terminal attempt fails, its prior active state and pending commands survive, and a valid reentrant continuation can execute afterward. Do not accidentally restore the document while keeping staged formatting history or command outcomes.

**Atomic settlement pipeline.** First apply the exact terminal native candidate to working structural state. Then derive current semantic composition target identities (constituent islands and accepted portions, excluding bridge text). Accept or cancel provisional effects according to §4.5, materialize the resulting canonical tree and seed the LIFO action ledger. Drain queued formatting and Undo against that tree. The final before/after semantic comparison assigns at most one DocumentRevision increment and exactly one successful SessionRevision increment. Publish only after the entire batch succeeds; receipt installation precedes did-change observers. Observers never see the intermediate native candidate, temporary Bold result or intermediate Undo root as a separately accepted publication.

Redo during active composition rejects SettlementRequired without mutation; it is not silently queued or used to terminate composition. It can be retried after qualified settlement, with cancellation preserving an existing redo branch when no new semantic edit survived.

Application observers are notifications, not input validators. Exceptions in will-change/did-change observers are isolated and may be diagnosed; they do not veto an otherwise valid edit or roll back an accepted publication. This fixes the actual observer-veto defect found during integration. Tests inspect observations outside callbacks so swallowed observer assertions cannot accidentally produce a passing test.

**Structural provenance after settlement.** Rebase the private structural worker on the resulting accepted canonical hierarchy after settlement, formatting and history restoration. Its next session must not reconstruct a joined list from the original pre-join container tree. Retained text/atom identities remain stable. A new session gets an owner-wide unique engineSessionId derived from its admission identity; new provisional unit IDs use that namespace. Recreating a private worker must not reset identity allocation and create duplicate token IDs in an existing document. Undo restores the historical canonical root; subsequent editing begins from that restored version.

**Semantic equality.** Compare canonical document semantics: structural IDs, types, properties, resources and raw text/attributes. Internal text-token IDs, session-origin tags and map-key insertion order are not visible semantic changes. Canonicalize unordered property keys. Token identity still controls range mapping and must be unique in the visible tape, but metadata-only identity changes must not invent a DocumentRevision or history group. Structural node/object IDs remain semantic and cannot be ignored.

**Cancellation and history.** Every Undo passes through the same owner, including Undos queued while composition is active. A cancelled formatting request contributes its one-use action slot; an aborted dirty composition contributes its one-use composition credit if no accepted group survives. Partial commits that survive cancellation create a real Composition group, which the next applicable Undo can remove. The current durable stack-top ID must equal the action slot before restoration. Redo contains only actually undone groups. New semantic formatting after an Undo clears Redo normally. Selection-only publications do not create document history. Connection turnover finalizes accepted visible structure once, cancels every old pending command, and advances identity only on successful publication.

**Executed integrated surface.** The current model exercises normalized start/preedit, exact retarget, partial commit, nonterminal unmark/resumption, terminal commit, evidence-checked completion, native cancellation/removal, application abort, explicit selection, noncollapsed/semantic-island formatting, Undo/Redo, connection replacement/disposal paths, duplicate admission, and reentrant scheduling. All fixtures in the integrated test file enter through `dispatch`; they do not separately call the old string admission or string history model and compare disconnected outputs.

Run-19 extends this owner to a whole document with one active native flow, the finite command catalog, registered anchors, typing intent, clipboard leases and table-cell coordination (§4.11). Its storage is still full-copy arrays/trees, and run-20 grapheme fixtures use hashed Unicode 16 tables; word movement remains runtime-based. Full Unicode conformance, production persistent storage, native adapters, accessibility, browser DOM, arbitrary HTML import and durable journal remain unqualified. Catalog coverage is not exhaustive interaction proof and does not pass G3 or authorize implementation.

**Required public-boundary regression traces.** Keep queued cross-list composition with original-selection capture; failed initiator plus dependent callbacks; partial commit/cancel with formatting and multiple Undos; clean mark-existing plus changed terminal commit without intermediate preedit; failed terminal drainage with retry; reentrant continuation after rollback; exact native removal; explicit after-ticket bases across abort/relocation; stale and fragmented proposals; unchanged candidate revision behavior; nonterminal unmark with obsolete completion evidence; old duplicate after epoch turnover; repeated sessions with unique tokens and rebased ancestry; grapheme-joining format/Undo; selected-atom detours; one-publication settlement; turnover rollback; observer failure isolation; and queue continuation beyond the per-turn budget.


### 4.10 Bounded protocol retention and dependency lifetime (revision 4.14.0)

History eviction alone is insufficient: a completed receipt containing an old EditorSnapshot could keep that snapshot's whole history alive. Completed protocol records therefore MUST NOT own editor/document/history snapshots. The integrated model implements the following ownership and expiry rules, extended for document commands by §4.11; its serialized byte charges are conservative reference accounting, not measured JVM/Native/Wasm heap sizes.

**Separate payloads from acknowledgements.** Deliver an execution receipt/projection change once at publication. Cache only a compact acknowledgement summary: ticket, outcome/error code, document/session revisions, command-admission result, and history-truncation counter. A duplicate receives this acknowledgement, not instructions to reapply an old projection. The reference receipt cache contains no `snapshot`, `tree`, `history`, attributes or resource payload. Large diagnostics/proposals belong to the separately bounded recovery path, not receipt metadata. Callers can obtain the current immutable editor snapshot; retaining copies beyond delivery is caller ownership and cannot be undone by engine cache eviction. The reference snapshot exposes history for test assertions; a production public snapshot need not expose private undo roots.

**Compact coordinate bases.** A basis stores an identity sequence (compressed `TokenRun(baseId,length)` plus separator identities in production), selection/direction, selection generation, document/session revisions and connection epoch/generation. Section 4.11 adds root-free flow/topology/selection-kind metadata and cell target fingerprints. It has no text slabs, attribute payloads, canonical hierarchy objects, resources or history roots. The reference uses identity arrays for transparency. Captured insertion intent or command patches are independent small immutable values, not reasons to pin the entire source document. Native AfterTicket bases must belong to the callback's epoch/generation; a foreign identity domain rejects BasisIdentityMismatch. Application rebasable targets follow their explicit anchor/identity policy instead.

Normal capture uses the LAST PUBLISHED compact basis, even if a private structural worker has already staged changes. It never builds a public request basis from that worker. Publication installs the new compact basis atomically with the public version; rollback restores the old basis pointer. Multiple queued requests from one publication share its basis. A mid-staging reentry regression verifies this, in addition to will/did-change tests.

**AfterTicket lifetime.** Registration must occur while the producer is pending or while its successful compact basis is still cached. Registration acquires a dependency pin before cache trimming. A pending producer's published basis and terminal status remain pinned until every registered dependent has executed or been rejected. An already-produced basis may be held directly by the immutable dependent envelope. Producer failure rejects the dependent with BasisDependencyFailed. Registration after basis expiry rejects BasisExpired; an invalid/self/future ticket rejects InvalidDependency. Never substitute the current selection or a newer snapshot. Dependencies are backward-only in ticket order, making their graph acyclic. AfterTicket denotes the producer request's publication, not the later settlement of an application command it merely queued.

When an application command enters the composition command queue, resolve its captured target into identity ranges and release its receipt/basis dependency; the command keeps only that target, patch and ticket. Completion/cancellation/failure releases request pins in a finally path. Independently admitted reentrant requests survive an outer transaction rollback and keep their own dependencies. A failed publication never leaves a usable accepted basis under its ticket.

**Default limits and accounting.** These are explicit configurable policy values, not a total-editor memory guarantee:

| Owner/pool | Limit and eviction/admission rule |
|---|---|
| Completed receipt summaries | Most recent 256 unpinned summaries by completion order; additionally retain summaries pinned by admitted dependencies and the currently returning synchronous call. |
| Completed unpinned bases | At most 32 and 8 MiB of accounted identity data; evict oldest unpinned bases until both limits hold. Oversize unpinned bases are not retained. |
| Pinned request/dependency bases | At most 8 MiB, counting unique basis objects/runs; never evict a live dependency to fit the budget. |
| External queued/executing requests plus pending application commands | At most 256 admitted entries; queued payloads plus captured command targets at most 8 MiB. Validate expanded target-identity storage as well as the initial small payload. |
| Completed command outcomes | Most recent 256 terminal outcomes; pending outcomes are protected. Pending application commands may not exceed this capacity, so one settlement batch cannot evict its own newly emitted outcomes before notification. Completion updates recency. |
| History plus redo | At most 1,000 settled groups and 64 MiB of accounted retained payload beyond the current document; active S0 remains separately pinned. Trim only after an entire successful transaction/settlement batch, never while its LIFO ledger still refers to group IDs. |

Trim oldest undo groups first, then the most distant redo groups (not the next redo). An oversize settled group may exhaust the budget and be evicted; report history truncation explicitly. Eviction cannot change current document semantics or release active composition preimages. Production uses incremental accounting of unique retained slabs/subtrees/resources; the model charges serialized before/after payloads conservatively. These pool ceilings do not prove the 128-MiB product target, which includes additional current-document, layout, runtime and allocator costs.

Capacity is checked before admission and again after expanding a pending command's targets or producing an unknown pinned dependency basis. Failure is AdmissionCapacityExceeded or BasisCapacityExceeded with no partial semantic publication. If a producer cannot create its promised pinned basis, the producer transaction rejects atomically and dependents receive failure; no false successful receipt is emitted. Never silently truncate accepted native text. A noncancelable native host must preserve the original proposal and use its qualified recovery protocol on refusal; streaming/quarantine/native behavior remains a platform gate. The reference model does not claim to implement that durable recovery path.

**Bounded replay protection.** Wire sequence numbers strictly increase at admission within epoch/generation. Keep a monotonic high-water mark even when detailed receipts/digests expire. An exact cached retry yields its existing outcome; a sequence at/below the mark with no cached record yields DuplicateReceiptExpired and cannot execute. This status means the old stamp cannot be retrieved/reexecuted; it is not proof that every skipped sequence number previously existed. Store a fixed-size canonical-payload digest (SHA-256 in the reference), not the original payload string, to detect collisions between different requests using a cached stamp. The digest uses the usual cryptographic collision-resistance assumption.

Composition stamps add a positive monotonic `sessionOrdinal` within epoch/generation, plus the opaque adapter token. A fresh session must use an ordinal greater than the admitted-session high-water mark. Active/reserved sessions can continue at their existing ordinal; the opaque token must match. On termination/failure, retain the detailed reservation only while an admitted request depends on it. Thereafter the ordinal high-water mark rejects its reuse without an unbounded set of opaque-token tombstones. Direct noncomposing input has neither token nor ordinal. An ordinal is adapter bookkeeping, NOT proof of native provenance: the real host fence requirements in §4.2 remain unchanged.

Generation/epoch turnover retires older counters only once neither a queued request nor the current published input frame refers to that identity context. Staged flow switches are not publication and cannot evict the published frame's replay watermark. Stale identity checks run before receipt lookup. A request claiming a future identity requires an explicit admitted AfterTicket dependency and must later match both the actual executed identity and its dependency basis. Arbitrary future identities are not cached indefinitely. ID/revision counters are checked, never wrapped or recycled.

Protocol-cache eviction is transport maintenance, not a semantic document edit. A failed document transaction does not resurrect expired unrelated cache entries or roll back admitted sequence/ordinal watermarks. It must still restore document/history/outcomes, preserve live dependency pins, and leave no accepted basis or receipt for its failed publication. Rejections before a valid identity context is admitted do not create unbounded state for arbitrary future generations. Composition-lifecycle callbacks require an identified session; only genuinely noncomposing commit/removal may omit the token/ordinal pair.

**Ownership diagnostics and qualification.** Report counts/charges for receipt summaries, cached/pinned bases, pending requests/targets, reservation records, sequence contexts, outcomes and undo/redo. The private structural worker's unused legacy undo/redo, receipt cache and mapping log are cleared after successful execution; they cannot become a second unbounded retention owner. Caller-held snapshots, current version/basis and active S0 are identified separately from evictable protocol history. Production layout/AT jobs must likewise release their explicit revision leases, but those owners are outside this headless model.

The tests force tiny cache/history limits, eviction during queued dependencies, failed producers, oversized bases/targets, expired duplicate delivery, retired-session reuse, long-lived preedit and hundreds of fixed-size edits. They establish the tested ownership/count/charge behavior, not physical garbage-collector reclamation or device heap limits. Exact execution counts appear in the evidence report.


### 4.11 Whole-document commands, anchors and cell flows (revision 4.15.0)

This section extends the SAME transaction owner to the remaining finite command catalog below. `DocumentModel` in run-19 subclasses the integrated owner and substitutes whole-document bundles for flow-only bundles. It does not create a second command queue, history stack, document revision or input owner. `IntegratedModel` remains the admission/settlement mechanism; `StructuralModel` remains its private active-flow worker. All commands, anchors, cell operations, native callbacks and history operations enter `dispatch`. The executable model uses full copies and identity arrays; this section specifies semantics, not a production data structure or a runtime benchmark.

#### 4.11.1 Whole-document publication and connection scope

The version bundle owns body Flow, every table/cell Flow, resources, document annotations, active FlowId, typed selection, typing intent and registered-anchor state. The native worker has exactly ONE active flow at a time. A table remains one tagged atom in its parent projection, while its cell flows are independently addressable through the document owner. A native range cannot silently extend from one cell into another. Cross-cell interaction uses a rectangular cell selection and the explicit transaction below. NodeId, FlowId, RowId and CellId are unique in the document; unit identities are unique across all flow projections. Duplicate flow discovery must reject before insertion into a map could conceal the duplicate.

Snapshots publish both the visible whole document and the in-memory committed whole document. An inactive cell edit, annotation edit or resource edit participates in DocumentRevision comparison. Looking only at the active cell's text would miss such changes. Every affected flow, resource map, annotation, selection, history entry and anchor repair publishes atomically. Failure restores the entire preimage, including cell-clearing preludes, typing intent, clipboard and paste leases. Ordinary command changes respect locked units/subtrees; insertion before locked text is allowed, changing/deleting that text is not. Exact Undo/Redo restores the authorized historical revision, including insertion/removal of a locked object. Lock flags are document editing constraints here, not an external authorization system.

Derive connection scope from the FINAL published active FlowId, not each intermediate `_install` in a settlement. If queued DeleteTable temporarily leaves the active cell, then queued Undo restores that same cell before publication, the connection epoch remains unchanged. If the final active flow changes, advance the epoch once, reset its input generation and require the adapter's qualified fence. An explicit text/selection relocation in the same flow advances input generation. SelectionGeneration records actual scope/kind changes, explicit relocation barriers, and history restoration that changes selection. Failed scope changes restore these values. Admission and reentrant request capture consult the published scope; staged flow changes cannot relabel an incoming callback or evict the published context's replay protection.

#### 4.11.2 Finite command API and targets

The reference envelope is `{type:'command', command, ...arguments}` plus the existing native/format/selection/undo/redo/lifecycle envelopes. Common arguments are `flowId`, `range:[anchor,focus]`, `affinity:before|after`, or `anchorHandle`. Missing range means the selection in the captured published basis. Explicit native ranges remain exact UTF-16; user-facing text commands use the grapheme/atom policy below. Node/table commands name identity, not projected U+FFFC or a stale array index. All indices/counts must be finite nonnegative integers in range. IDs and request payloads are immutable. An application request may use an admitted AfterTicket dependency under §4.10; ordinary capture never observes staged document state.

Capture a noncollapsed target as ordered unit IDs plus adjacent left/right IDs; at execution, all target IDs must still exist contiguously in the same flow. Otherwise return TargetDeleted/TargetFragmented, without formatting or replacing a different range. Capture a collapsed target by neighboring IDs and association. Before prefers the surviving left edge; After prefers the surviving right edge, with the other edge as fallback. If both actual neighbors disappear, reject rather than guess; null start/end sentinels are valid edges. Explicit anchor handles use their current mapped point and reject if marked deleted. Capture directional endpoints separately for movement/extension; sorting an edit interval must not lose its original focus.

The transport acknowledgement is Accepted/Deferred/Rejected. It is separate from a command's terminal Applied/NoChange/Cancelled outcome: an accepted request may have queued a command that has not run yet. Expose `commandStatus` in the reference receipt rather than overwriting transport status. Queued editing commands share the existing settlement action ledger and typed outcomes. Known target/schema refusals cancel that command atomically and leave preceding native acceptance intact. Unexpected exceptions or injected transaction failures roll back the entire publication. Cancellation/no-history command slots follow §4.6; a queued Undo must not fall through and undo unrelated earlier work.

| Command(s) | Arguments and selected behavior |
|---|---|
| `typeText`, `replaceSelection` | `text`, common target. Replace the normalized user range, preserving raw input units. TypeText may join the typing group under §4.11.7; ReplaceSelection is its own group. |
| `deleteSelection`, `backspace`, `deleteForward` | Common target. DeleteSelection deletes selection; directional delete removes one grapheme/atom when collapsed, otherwise the selection. Compatible structural joins and leading-item lifting follow §2.6. An unselected block object requires explicit node selection instead of accidental seam consumption. |
| `deleteCodePoints` | `before`, `after`, raw target. Preflight both flanks for valid surrogate traversal; delete surrounding code points and preserve the selected middle. Any invalid traversal rejects before either flank changes. This is not the user grapheme-delete command. |
| `enter`, `softBreak` | Common target. Structural Enter follows §2.6; SoftBreak inserts literal LF. At an explicit insertion gap, create an editable paragraph, or an item containing one when the parent is a List, in the same transaction. |
| `moveCaret` | `direction:left|right|blockStart|blockEnd`, `unit:grapheme|word`, `extend`. Left/right here mean logical text order, not visual bidi movement. Extend moves the captured focus while preserving the anchor. Without extension a noncollapsed range collapses to its requested side. Movement includes text-block transitions; node/table navigation has its own selection commands. Visual up/down, hit testing and bidi affinity require a layout frame and are not inferred by this model. |
| `setBlockType` | `blockIds` or intersected text blocks; `blockType:paragraph|heading|code`, heading `level:1..6`, optional code `language`. Preserve IDs; remove incompatible heading/code properties. Reject code conversion containing inline atoms. |
| `patchNode`, `patchCell` | `nodeId,patch`, or `tableId,cellId,patch`. Change semantic properties. Node patch cannot replace table rows/kind to bypass structural operations. Includes inline-atom properties. |
| `wrapQuote`, `wrapList` | `blockIds` contiguous siblings, optional list `kind` and ordered `start`. Allocate container/item identities; retain selected block identities and order. |
| `unwrapQuote`, `unwrapList` | `nodeId`. Promote contained blocks in order; list items flatten to their blocks. Schema validation rejects invalid placement. |
| `indent`, `outdent` | Item `nodeId`, or current containing item. Indent into the previous sibling's nested list; outdent/lift one level, preserving trailing nested ownership and order. No previous item rejects. |
| `moveNode` | `nodeId,parentId,index`. Block/container index is a child insertion gap in the PRE-move destination. Moving forward within the same parent adjusts for removal. Inline-atom parent is a text block and index is a UTF-16 insertion gap; preserve atom/unit IDs. Reject cycles, invalid parent classes, code-block atoms and nested tables. |
| `deleteNode`, `deleteTable` | `nodeId`. Delete that node, preserving a legal editable flow; DeleteTable additionally requires a table. Inline-atom deletion removes exactly its tagged unit. |
| `insertInlineAtom`, `insertBlockObject` | Common target, semantic `props` and object `kind`. Allocate fresh node/unit identities. Block insertion splits the landing paragraph and keeps legal editable text around the object. A table requires InsertTable. |
| `insertTable` | Common target, positive `rows,columns`. Allocate rectangular table/row/cell/flow identities and empty paragraphs. Enter its first cell. Nested and merged tables remain outside the fixed product scope. |
| `insertRow`, `deleteRow`, `insertColumn`, `deleteColumn` | Table `nodeId,index`. Insert at gap; delete existing row/column. Reject deletion of the final row/column; explicit DeleteTable removes the table. Map affected active flow/anchors to the nearest surviving cell. |
| `tableTab` | `reverse`. Row-major next/previous cell; forward at last appends one row; reverse at first goes to a preceding parent-flow paragraph, creating it if necessary. Navigation alone creates no history; row/paragraph creation does. |
| `clearCells` | Current rectangular cell selection. Preserve table/row/cell/flow IDs, clear selected contents, leave one paragraph per cell, preserve effective empty-block attributes. |
| `selectFlow`, `selectCells` | `toFlow,offset`; or `tableId,anchorCell,focusCell`. Flow selection is a collapsed text position. Cell direction is retained separately from top-left execution order. Both are session changes, without document history. |
| `selectNode`, `selectBlocks` | `nodeId`; or `first,last,backwards`. Select a block/inline atom or ordered block interval in one flow. A selected node follows its identity on a move, including a cell move; deletion downgrades to the mapped text caret. Deleted/invalid block endpoints cannot leave a stale typed selection. |
| `copy`, `cut` | Common target or cell selection. Capture an immutable inline, closed-block or rectangular-cell fragment and referenced resources. Cut then removes the selected content in one atomic history group. Clipboard publication failure rolls back the cut. Undo restores the document but does not replace the user's later clipboard. |
| `pasteText` | `text`, common target/cell selection. Use destination typing attributes. Outside code blocks normalize CRLF/CR to LF and create paragraphs for paste lines; code blocks retain literal text. This differs deliberately from opaque native LF. |
| `pasteRich` | Exactly one canonical fragment shape: inline `units`, closed `blocks`, or `cells`, plus optional resources. Sanitize/import, allocate fresh identities, and replace the captured target atomically. Inline paste over a cell rectangle also clears the entire rectangle. Closed blocks retain supported structure and omit gratuitous empty split halves. |
| `pasteTSV` | `text`, cell target/current cell. Parse quoted fields, doubled quotes, tabs and line breaks; expand the rectangle as needed, bounded before publication. Replace values from the top-left target; clear an explicitly selected rectangle first. Unfilled ragged fields outside that selection retain prior content. |
| `registerAnchor`, `moveAnchor`, `unregisterAnchor` | `point,association` returning `anchorId`; `anchorId,point,association`; or `anchorId`. Lifetime and restoration rules below. They are session state, not document history. |
| `capturePaste`, `applyPaste`, `cancelPaste` | Capture returns `pasteId`; apply supplies that ID and one fragment/text; cancel releases it. Consumption is once-only. It is a target lease, not permission to use the current caret. |
| `putResource`, `removeResource` | `resourceId,resource`; or `resourceId`. Update the canonical resource descriptor map. Removal rejects while referenced. Actual asset fetching/storage/decoding is outside this model. |
| `addAnnotation`, `removeAnnotation` | Common target plus semantic `props`, returning `annotationId`; or annotationId. Annotations are document-owned ranges and participate in history and DocumentRevision. |
| `cancelCommand` | Pending command `ticket`. Emit one Cancelled(UserCancelled) outcome, release large targets, retain a small consumed/cancelled ledger credit until drainage; never remove the slot and expose unrelated history to the next Undo. |
| `focusLost` | Adapter-qualified `epoch,sessionId,policy:preserve|cancel`. Unqualified evidence rejects. Preserve accepts current candidate; cancel follows application abort semantics. Cancel composition-target toolbar work with FocusLost and process other commands through the normal ledger. Advance input generation and require a fence. This is a synthetic adapter contract, not proof of OS focus provenance. |

The existing `format` command takes a semantic patch, optional ClearAll and range/composition target; collapsed formatting updates typing intent only. Existing `selection`, `undo`, `redo`, `openNewConnection` and `dispose` remain on the same public owner. Do not infer that every one of the 51 named command families has exhaustive parameter/state coverage from its inventory row.

#### 4.11.3 Anchor and annotation mapping

An anchor record is `(handle, DocPoint, association, explicitRelocationGeneration, deleted)`. Registration validates its published-coordinate point. MoveAnchor increments its generation and clears deleted status. Automatic mapping never increments that generation. Unregister is final for the handle; Undo cannot resurrect it.

During composition, first apply the committed-authority/provisional-origin rules in section 4.12.1; do not repeatedly map clamped visible positions. For a committed text edit, use the insertion/deletion equations in §2.5. For split at k, a Before anchor at k remains at the left block end; After follows the right block start, even if the right block is empty. When a retained block actually moves/reparents with its units unchanged, keep its internal offset and follow its new FlowId. Do not apply this move shortcut to a split merely because the retained left block's content happened not to change. Other points map through surviving neighbor identities. Deletion status signals loss of both actual adjacent unit identities; edge sentinels remain defined. An explicitly deleted flow always marks affected anchors deleted and supplies a legal fallback point.

A Gap anchor identifies parent and child insertion index, with association relative to adjacent child IDs. When that parent survives, map against retained neighboring children. If the parent disappears, fall back through its old projected position to a surviving editable text point. Deleting a cell chooses the surviving row/column clamped to the former coordinates; deleting its table maps to the former table location in the body flow. No anchor may point into a removed flow. Gap Enter/type creates content; Backspace at a leading item gap lifts that item even when its first child is a nested list.

Each history record captures registered-anchor before/after states. Undo/Redo restores the exact saved point/status ONLY for a still-registered handle whose explicit relocation generation matches the saved one. Otherwise map its current point across the restoration. Handles registered later are mapped normally; explicitly relocated ones do not jump back to an old bookmark. This full-copy reference is a semantic oracle for production recorded anchor deltas, not an instruction to copy all anchors on every production edit.

Annotations differ: their committed ranges/properties belong to Dc, their visible projection is derived as specified in section 4.12.1, and exact history restoration includes them. Ordinary edit mapping expands endpoints with Before/After association; when all previously annotated units are gone, mark the annotation orphaned and retain its metadata. Cancelling a composition with no accepted change restores the original annotation state. This is not a collaborative comment-resolution protocol.

#### 4.11.4 Rectangular selection and native composition

A cell selection retains directional endpoint CellIds but enumerates its rectangle in row-major order. Bind the active native connection to the top-left flow and use its selected text extent as the primary target. Save S0 as the WHOLE document, original cell selection, typing intent and registered anchors before the first provisional mutation.

On the first preedit (also used for a direct textual commit), stage a cell-clearing prelude for the whole selected rectangle and attach the ordinary structural overlay in the top-left cell. Other cells appear cleared in Dv but remain intact in Dc. Clear preserves the chosen cell/flow IDs and a legal paragraph; no cell contents are implicitly concatenated. The top-left candidate uses the frozen destination/empty-cell formatting seed. Continuing candidate changes use the same attribution and admission rules as body composition.

A positive partialCommit accepts the rectangle prelude together with the accepted prefix; that structural clearing is now part of Dc. A zero-length partialCommit does not accept the prelude. Subsequent suffix cancellation preserves accepted prefix and cleared cells. Cancellation before any accepted change restores every selected cell, its properties/structure, typed selection direction and annotation state. A terminal commit accepts both prelude and candidate in ONE Composition history group. An initial nativeRemoval over the cell selection stages and accepts the same rectangle prelude with its replacement text, including empty text; it must not delete only the top-left contents. Queued formatting and Undo then execute against this full-document group through the existing action ledger. One Undo of that group restores the original rectangle, not only the top-left text.

No range in one cell's protocol coordinates addresses another cell. A flow switch during composition is queued behind settlement. Composition-target formatting retains the original composition FlowId even if an earlier queued command selects another cell. A native callback stamped for a flow's retired epoch cannot type in the newly focused cell. Whole-table replacement from the parent flow is still the selected-object structural operation, not a rectangular-cell clear.

#### 4.11.5 Clipboard, asynchronous insertion and resources

A copied fragment is immutable and bounded. Inline and closed-block copies preserve supported raw attributes, including inside graphemes. Paste gives fresh structural/unit IDs and remaps link/code identities consistently across the entire fragment; equal URLs are not sufficient to merge link identities. Copy resource descriptors by reference set; importing resources allocates/remaps their IDs without fetching them. Unsupported attribute keys and unsafe link schemes are removed with counts in an import report. HTML parsing, OS clipboard formats, image codecs and external resource I/O still require independent import/export tests; this model accepts canonical fragments, not arbitrary HTML. Malformed fragments reject atomically.

An asynchronous paste lease captures FlowId, exact target identities/edges, typing intent and SelectionGeneration. For a cell rectangle also capture the ordered CellIds and semantic content fingerprints. Completion applies at the original target or cancels when it was deleted, fragmented or changed; it cannot substitute whatever is currently selected. Explicit focus relocation preserves the newer focus/typing intent while applying a still-valid old target. Compare cell contents before replacement; identical coordinates with replaced CellIds do not establish identity. Successful application/cancellation consumes the lease; a transaction failure restores it. Explicitly abandoned leases are released by CancelPaste; Dispose releases all leases/clipboard/registered handles.

The reference retains one clipboard fragment. `clipboardSnapshot(id)` returns ClipboardExpired for an older replaced ID; caller-retained immutable copies are caller ownership. It does not claim to manage the global OS clipboard or asynchronous clipboard permission failures. The adapter must bind those effects to the transaction/lease outcomes rather than performing a destructive cut before obtaining required clipboard acceptance.

#### 4.11.6 Typing, graphemes and protected boundaries

Resolve typing intent under §5 and freeze the native session seed at composition start. The top-left cell's empty-block attributes are meaningful even when its candidate replaces no text. Candidate whitespace ends explicit link continuation for newly inserted units, but a temporary detour to whitespace followed by a candidate without whitespace must not permanently erase the immutable baseline link attribution. Accepted whitespace ends that explicit intent. Enter clears link/code continuation while retaining ordinary presentation attributes.

Raw native UTF-16 selection remains exact. User-facing replacement/deletion expands nonempty ranges outward to grapheme boundaries; a collapsed illegal caret snaps according to association. Segment TEXT runs independently on either side of each inline atom. Both sides of an atom are hard boundaries for formatting, navigation and deletion: a combining mark after an atom must not make Backspace delete that atom as part of a Unicode text cluster. Inline atoms occupy one tagged unit and are separately selectable/movable/deletable. Code-point surrounding deletion remains its own full-preflight operation and can differ from grapheme deletion.

Logical movement operates on captured directional endpoints. Layout-driven movement must deliver an explicit selection plus logical side/bidi/line affinity from its qualified frame. The model intentionally rejects unsupported visual directions rather than guessing glyph geometry. Run-20 uses pinned Unicode 16 tables for graphemes; its word movement still uses Intl.Segmenter. Production Kotlin tables and cross-platform Unicode/word/bidi qualification remain required.

#### 4.11.7 History and retention extensions

One global history group contains whole-document before/after roots, active FlowId, typed selection, typing intent and restorable anchor state. Secondary cells, resource descriptors and annotations cannot be omitted. The existing action-ledger semantics still determine which group a queued Undo consumes. CancelCommand retains its no-history credit until its turn and emits its terminal outcome once. Session-only commands never invent document groups. Format of a collapsed caret is likewise session-only; a no-op document command retains the explicit no-history ledger behavior needed to prevent Undo fall-through.

Coalesce contiguous TypeText or direct noncomposing commits only when the prior top group is still the same Typing group, the flow/intent match, the previous selection is a collapsed continuation at its end, semantic history adjacency holds, and an injected monotonic time interval is at most 750 ms. Selection, formatting, structure, identified native composition and other command boundaries end coalescing. Native composition remains one Composition group regardless of its duration. A new semantic edit clears Redo; no-op metadata changes do not. Production must inject the clock; the fixture's fixed default time is not a real-world coalescing measurement.

Compact bases remain root-free. They now also carry FlowId, selection kind, node/parent IDs, child-ID order and projected spans needed to interpret a captured DocPoint, plus selected cell IDs/token identities/fingerprints. They contain no canonical node objects, text/attribute/resource payloads or history roots. Separately capture immutable typing intent from the published frame; charge it with request bytes. Never rebuild that context from staged worker state. Production may use compressed/lazy indexed identity summaries, but their retained roots and byte accounting must be explicit.

Model-only admission ceilings are 4,096 registered anchors, 128 paste leases, 4,096 cells per table, 10,000 structural nodes and 1,024 annotations/resource descriptors each. They are configurable test bounds, not newly imposed release limits on the million-unit product fixture. Enforce §4.10 serialized queue/basis/history charges after expansion; charge paste leases and clipboard fragments too. A refused command leaves no partial semantic state. These limits do not prove a physical heap ceiling. Active S0/current version, caller snapshots, document content and future layout/AT leases remain distinct ownership categories. Benchmark actual production reclamation before choosing shipping limits.

#### 4.11.8 Added invariants and validation boundary

1. The global document, every flow and every visible/committed projection agree at publication; exactly one owner assigns document/session revisions.
2. Selected cells may differ between Dv and Dc only through the explicitly recorded cell prelude and primary overlay. No unaccepted secondary-cell clear leaks into Dc.
3. History restoration recovers all affected flows/IDs/attributes/resources/annotations and the recorded selection; retained anchor generations govern restoration separately.
4. Every registered anchor has a legal current point or explicit deleted status with a legal fallback. It never silently names a deleted flow. Unregistered handles stay unregistered.
5. Commands and async leases preserve captured target identity; a target failure produces a typed outcome, not a substitute edit.
6. Native connection identity changes only for the final committed publication scope/barrier; speculative switches cannot retire input or remove published replay protection.
7. Atom boundaries constrain user grapheme operations independently of raw UTF-16 native semantics.
8. Existing partial-commit, settlement, failed-publication, request immutability, duplicate/replay and ledger invariants still hold through the document owner.

Run-19 executes the finite catalog, analytic insertion/split anchor equations, all rectangles/directions of a 2x2 cell fixture with commit/cancel/partial acceptance plus queued format/Undo, and the prior 35 named integrated scenarios through DocumentModel. Separate retained suites exercise the base owner and AWT synthetic contract. Behavioral mutation tests run unmodified controls first and require assertion failures, not syntax/runtime crashes. Counts, failures discovered during development and source hashes are in the accompanying report. This is neither exhaustive model checking of arbitrary documents nor an independent production implementation. No G0-G8 production gate is advanced.


### 4.12 v5 integration corrections and frozen choices

The v5 reference is `validation/codex/run-20261016-20/`. It incorporates independently accepted Gemini findings and corrections discovered while testing those repairs. The review is evidence, not normative authority. Ordinary commands, cross-paragraph/container composition, explicit cell composition and the architecture in section 1 remain intact.

#### 4.12.1 Committed and visible positions have separate authority

Committed annotations Ac and committed anchor bindings belong to Dc. Derive visible annotations Av and visible anchor positions from that authority and the current overlay, rather than destructively remapping a previously clamped visible point. Temporary deleted/orphaned display state disappears when the original identified content reappears. Candidate detours cannot create a spurious annotation difference or Composition history when the final document equals the original.

Advance committed bindings when Dc changes: partial acceptance, direct nativeRemoval acceptance and terminal acceptance. Retargeting and provisional updates alone do not accept positions. Reproject the remainder from the updated committed authority. Cancellation restores the currently committed state, including accepted prefixes, not necessarily S0.

Handle lifetime is independent of composition lifetime. Unregister stays unregistered; explicit relocation retains its newer generation. A handle registered or moved inside provisional content needs an immutable visible-origin binding: `(session, captured visible provenance, point, association, relocation generation)`. Project that origin directly into each candidate; mapping it to Dc and back loses positions inside inserted text. At settlement resolve it into the settled document and release provisional provenance. Partial commit must not discard an origin whose suffix remains provisional.

The reference uses whole origin snapshots and charges them to the transient metadata pool. Production uses compact identity/provenance ranges with measured retained-root ownership and admission limits. They are not roots hidden in receipts. Dispose releases committed and provisional handle maps; rollback restores both with the transaction preimage.

#### 4.12.2 Ledger, targets and rejection

A composition-target format whose final semantic target is empty returns Cancelled(TargetDeleted) with one CommandWithoutHistory credit. It does not become a collapsed-caret typing-intent operation. A cancelled ApplyPaste also contributes that credit and preserves TargetChanged, TargetDeleted or TargetFragmented distinctly. Selection-only and explicitly collapsed typing-intent commands still create no slot: reject the review's broader suggestion that every queued command consumes Undo.

Explicit range, point or anchorHandle supersedes implicit cell selection. It cannot clear the current rectangle merely because that selection is still visible. Expected target/schema refusals, including UnrepresentableObjectSeam and an empty sibling-wrap request, cancel that command atomically while allowing valid native acceptance to finish. Unexpected exceptions still roll back the complete publication. Do not classify arbitrary implementation errors as expected cancellations.

Retargeting starts a new attribution epoch using the SAME boundary seed resolver as composition start: selected first text, chosen side, empty-block defaults, and exterior link/code restrictions. Reading attributes[target.start] directly is wrong at block ends and link edges.

#### 4.12.3 Structural heads and selection

A removed BlockObject cannot absorb a surviving right leaf merely because its following boundary was also deleted. Partition output by retained boundary identities. If a group's provisional head is a removed object and its following boundary survives, or no later leaf merged into the group, retain the existing in-place object-to-paragraph conversion. Otherwise choose the retained object's identity if the group contains just that object; for text choose the first retained source leaf owning surviving units, falling back to the last merged source leaf if no original unit remains. Preserve that leaf's ancestry/type/properties. A group mixing retained block-object and text units still rejects. Text-to-text joins still retain the left text head.

After application structural edits a derived collapsed Text selection must land in editable text: prefer the nearest following text point, then preceding text. Surviving Node selection follows identity; deletion downgrades to the mapped legal caret. This does NOT snap explicit native protocol selection or recorded history selection. Raw native offsets and drawable carets remain distinct.

Surrounding code-point deletion preflights both flanks, surrogate traversal and all structural tags before either splice. It cannot implicitly consume unselected inline/block atoms or join incompatible owners/code/prose. Preserve the selected middle and its direction. Native UTF-16 surrounding deletion uses the same structural admission and atomic multi-splice contract without code-point surrogate validation. Adapters cannot lower either operation to two separately published edits.

Empty direct quote-paragraph Enter exits the immediate quote before considering an outer list-item split. User movement collapsing a raw selection snaps its chosen edge to a legal grapheme stop; this does not normalize stored text or raw protocol ranges.

#### 4.12.4 Unicode and rich fragments

Pin Unicode 16.0.0 for v5 segmentation data. The validation reference implements extended grapheme rules against hashed GraphemeBreakProperty, Extended_Pictographic and Indic_Conjunct_Break tables and passes the full official 1,093-case GraphemeBreakTest file. This does not qualify Kotlin tables, word boundaries, bidi or shaping. Runtime Intl is not the pinned grapheme authority: the tested Node runtime uses Unicode 17 and disagreed with one Unicode 16 fixture. Reference word movement remains runtime-based and unqualified.

Isolated UTF-16 surrogates and tagged atoms are hard single-unit boundaries for user segmentation without modifying storage. The isolated-surrogate rule is an explicit policy for ill-formed UTF-16, not a claim that UAX #29 assigns it ordinary scalar semantics. Valid surrogate pairs remain indivisible scalars in grapheme processing.

Sanitize emptyBlockAttributes as well as unit attributes. Imported text units each contain exactly one UTF-16 code unit; tagged inline atoms have their required placeholder. Remap link/code IDs consistently in empty and nonempty text, and require imported resource references to have supplied/remapped descriptors. Unsupported/unsafe values produce a report or atomic refusal; empty text cannot bypass sanitization.

Closed rich-block paste before a nonempty heading/code block preserves the original block's ID/type/properties on its surviving tail. At a middle split retain the original left ID and allocate a compatible right head. Copy retains selected leading empty blocks represented by selected boundaries. Parse partial HTML in an explicit compatible context, such as table/tbody/tr for cell fragments or a list for list items, then sanitize and close supported slices. Do not parse bare cells in a generic body and interpret parser loss as intentional empty content. Actual HTML/system clipboard adapters remain unqualified.

#### 4.12.5 Hierarchy measures, membership and ownership

Each measured hierarchy node stores `(contentUtf16Length, leafBlockCount)`. Combine component-wise with identity `(0,0)`. Text contributes `(length,1)` even when empty; a block object/table in its parent flow contributes `(1,1)`. Cell flows have independent roots and do not add their contents to the parent measure. Flow projection length is `C + max(0,L-1)`. Leaf start is preceding content plus preceding leaf count, accumulated once along its ancestor child sequences. Text rank adds the within-leaf offset. Do not count separators repeatedly at each container depth.

A boundary for right leaf R exists only if R has a preceding leaf in the same CURRENT flow. Becoming first makes the boundary TargetDeleted, not rank -1. Rank resolution must reach the exact current FlowRoot registered in the current document/cell ownership graph, through current ParentMap entries. Reaching some old node with type Flow is insufficient. Retire removed subtree/token/parent records in the new version or invalidate them with a proven current-root membership check; old versions remain separately queryable. Gemini's insertion benchmark failed an added detached-token check and does not qualify this property.

Existing depth-aware bounds remain, including measure maintenance. The new measure oracle checks 100 generated trees and deletion/boundary equations; it is not an optimized persistent index or complexity proof. Persistent Kotlin B+ trees and versioned maps still need G1/G8 counter, correctness and heap qualification.

`pendingBytes` is ONE aggregate allowance for queued payload/context, expanded commands, paste leases, clipboard and provisional anchor-origin metadata. Reserve before enqueue and recheck after expansion. Do not grant every pool the full allowance independently. Report active execution/staging separately and release transferred ownership at completion. Bases, history, current document, active S0, media, renderer and caller snapshots retain separate explicit budgets. Total heap is not bounded by pendingBytes.

#### 4.12.6 Platform contracts

Use one editor-viewport native input host with one active-flow binding, not independent paragraph editors. During composition queries remain bound to its flow. After a real published flow change reconcile the host binding/selection using qualified lifecycle behavior. This selects ownership, not an already-proven bridge API.

AWT validates insertion offset and preserves hit affinity. `TextHitInfo.beforeOffset(0)` is `trailing(-1)`, a valid start-boundary representation; `leading(markedLength)` represents the end. Reject malformed hits or insertion offsets outside `[0,markedLength]`, not every negative character index. Null during active composition may defensively use leading(0); the JDK documents null for the no-composition case and does not mandate that fallback. Existing no-composition selection geometry/zero-thickness rules remain. Sources: [JDK 23 InputMethodRequests](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), [TextHitInfo](https://docs.oracle.com/en/java/javase/24/docs/api/java.desktop/java/awt/font/TextHitInfo.html), and the executed JDK 23 boundary probe. This is not live IME qualification.

Web protects the whole composing Text leaf and wrapper chain whose split/reparent/replacement can disturb it, even outside the numeric marked range. Maintain the protected set until qualified settlement, including nonterminal unmark. Apply only sibling writes that preserve that ownership and required selection/geometry. Gemini's ordinary DOM Selection probe is not an OS IME test.

Batch boundaries, epoch increments and calls to restart/unmark do not prove that delayed native input has stopped. [Android InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection) defines nested batches separately from finishing composition. Reject callbacks demonstrably belonging to an invalidated connection; callbacks arriving through the current instance still require provenance. Do not adopt a blanket increment-and-discard fallback. Failed qualification blocks a platform or reopens its bridge; it cannot silently remove cross-paragraph composition.

#### 4.12.7 Layout evidence

Gemini's C++ benchmark reconstructs bookkeeping using SimulatedRun; it does not link or execute the full Skia/Skiko pipeline. Its assumed zero-glyph runs are not a HarfBuzz experiment. The source differences are useful hypotheses, but the full-engine speedup and universal Theta claim are unproven. A hard M<=256 cap cannot guarantee 1 ms across hardware; merging distinct styles or arbitrary paragraph chunking can change semantics. Neither is a mandatory fallback.

Keep released unpatched layout as the provisional baseline. G8 must test the full pipeline with bidi/style/default-ignorable/font-fallback workloads. A corrected upstream/custom native build is allowed only after corpus equivalence, packaging and latency qualification. If none meets the required envelope, block release and reopen the backend choice, without an unapproved feature limit.


## 5. Formatting and boundary-attribute rules

### 5.1 Property semantics, not span-handle compatibility

Persistent formatting is a map on retained text, with one value per key. Applying a later format intentionally overwrites that key in the selected range. Hidden prior formatting survives only in history, not as invisible overlapping sticky intervals. Handle removal does not resurrect an old overlapping span because persistent formatting exposes no such handle API.

Compose's arbitrary `InsideOnly` intervals have observably richer hidden connectivity. Research demonstrated cases where visually identical span sets behave differently after deletion/insertion, and independent hidden bits require storage proportional to their input. This plan does not claim to compress or emulate those semantics. Comments/search/spelling decorations have separate anchored ranges and explicit boundary policies; they do not drive typing attributes.

### 5.2 Typing and replacement

Typing intent is `Inherit(side)` or `Explicit(capturedBase, patch)`. Resolve ordinary insertion in this order: explicit base+patch if present; otherwise chosen logical side; otherwise available side; otherwise empty-block defaults. Apply link/code restrictions last. Never union unequal left/right runs.

| Case | Rule |
|---|---|
| Interior of a run | Inherit it. |
| Boundary between unequal runs | Use `TypingSide`. |
| Block start/end | Right/left respectively. |
| Empty block | Stored empty-block attributes over block defaults. |
| Pointer caret | Logical side of hit-tested edge. |
| Programmatic placement without side | Left except block start. |
| Visual movement | Derive logical side from the actual traversed caret edge. |
| Collapsed toggle | Modify typing intent; create no zero-length style span/history entry. |
| Range replacement by typing | Seed from the first selected text unit in document order, not anchor direction; selected atom-only uses landing-point defaults. |
| Backspace | Recompute from surviving left side, then right fallback. |
| Forward delete | Recompute from surviving right side, then left fallback. |
| Deletion empties block | Capture pre-deletion effective typing attributes as empty-block attributes. |
| Contiguous typing | Retain resulting typing intent until an explicit boundary command/relocation. |
| Native composition | Use frozen seed and §4 attribution epochs. |
| Plain paste | Destination typing attributes; rich paste uses sanitized fragment attributes. |

Links have IDs distinct from URLs. Automatic link inheritance occurs only when both adjacent characters belong to the same link ID; exterior boundaries type outside. Explicit “type as link” may continue at an edge; whitespace is inserted outside and ends that intent. Equal URLs with different IDs stay distinct. Inline code similarly auto-inherits only inside the same code run; explicit code intent permits continuation. New block Enter clears link/code intent, retaining ordinary presentation properties unless the command's new block defaults override them.

### 5.3 Grapheme preservation and rendering

Never normalize the stored attributes of a grapheme to its first character. Instead, construct an effective rendering view per grapheme: ordinary presentation attributes come from its first scalar's leading unit (or the first isolated unit). This is a deterministic display policy, not a mutation. Navigation remains grapheme-aware. Accessibility/link activation uses the link at the targeted logical position; a cluster with conflicting link IDs is not activated as a single link by a glyph-wide hit—require a specific logical target or show no link action.

Applying a noncollapsed format expands each nonempty text interval outward to grapheme boundaries within that block, then patches all units in those clusters; it does not cross an atom or paragraph boundary. A collapsed selection remains a typing-intent operation. Clear-format expands identically. Toolbar mixed state is computed over effective displayed attributes of selected clusters, then the resulting command intentionally changes their raw constituent attributes.

Example: bold WOMAN + italic LAPTOP; insert ZWJ. It displays as one cluster with WOMAN's ordinary style, but LAPTOP remains italic in storage. Removing only that inserted ZWJ restores both styles without Undo. If the user explicitly makes the joined cluster bold, both constituents become bold; later removing ZWJ preserves that intentional change. Save/load must preserve both cases.

Imports retain supported internal-grapheme attribution. Unsupported attribute keys convert/report under the import schema; they are not removed simply because the current renderer cannot display every constituent style independently. Cross-platform effective-view rendering, especially DOM span boundaries around combining sequences, is a mandatory visual test.

## 6. Cross-platform integration contracts

### 6.1 Tagged text projection and input admission

A flow projection indexes segments: text, soft LF, tagged block separator LF, inline/block atom U+FFFC. It retains node IDs, ancestry, editable scope and source ranges. Tables are atoms in their parent flow; cells have separate projections. Literal U+FFFC is distinguishable from an atom by segment metadata. Read ranges materialize requested slices; no whole-flow flatten per edit. Offsets retain a stable logical origin for the connection epoch; clipping surrounding-text payloads uses explicit offset metadata, never silent recentering.

Projection endpoints use association to disambiguate adjacent segments. At a collapsed atom boundary, Before/After resolves to the corresponding document gap. `classify(range)` reports every structural segment touched before mutation.

Admission uses §4.2 target provenance and §4.3 structural compilation. Initial selected-range replacement can include removable atoms and cross-container endpoints without a preparatory deletion or second history group. Later unselected expansion is limited to its compatible text domain. Cross-flow text proposals reject; cell selections use explicit semantic commands. Retained tags preserve identities; new middle LF/U+FFFC are ordinary text units. Native proposal and result projection must agree exactly.

On an unsupported cancellable proposal, return the host's failure result with a structured reason. If native DOM/text has already changed noncancelably, retain the raw proposal and structural preimage and enter recovery rather than fabricate success or lose input. Object-seam handling and post-abort provenance are required host experiments. Keyboard commands that intentionally select/delete a block object use semantic commands, not a scalar splice that creates an illegal inline table.

### 6.2 Geometry and layout contract

`ParagraphCacheKey = (blockId, blockContentVersion, effectiveMetricStyles, width, density, zoom, fontGeneration, direction, backendVersion)`. It excludes the global document revision so an edit in one block does not invalidate unrelated paragraph entries. `FrameKey` includes the current document/session revision, the validated paragraph-key set, block placement versions and viewport transform. Cache keys include all shaping-affecting styles. Paint-only invalidation may avoid reshaping only if the chosen backend path has demonstrated that capability; otherwise invalidate the paragraph honestly.

A paragraph created under an earlier document revision may be reused only after all its dependency keys still match and current block placement is recomputed/validated. This is validated reuse, not relabelling stale geometry. Native input and accessibility receive the current frame key. A changed key invalidates the affected result, even when the text happens to be equal.

One immutable frame supplies glyphs, caret stops, selection geometry, hit testing and accessibility rectangles. Every result reports its coordinate space and actual keys. Convert accumulated double-precision document coordinates to float after subtracting the viewport origin. Explicit transforms cover local, viewport, window, screen and device pixels.

Maintain a measured block-height tree with estimated offscreen heights. Total scroll extent may be provisional until measured; label that internal state and preserve an anchored model position as estimates change. Keep active text, viewport neighborhood and selection endpoints realized. Exact geometry requests measure their required blocks or return an explicit unavailable result if the platform permits it. Never call an estimate exact. Whole-document exact print geometry is required by section 10. Estimates remain allowed only for interactive provisional layout; export must resolve an exact snapshot-bound page plan.

Do not split paragraphs at storage slabs to bound shaping. Do not mix old glyphs and current caret geometry. Background results with stale keys are discarded. Moving expensive layout off-thread is permitted only after backend/font thread-safety tests; cancellation must bound wasted queued work, and old frames cannot satisfy current synchronous IME geometry queries.

### 6.3 Platform adapters

| Target | Chosen host and required contract | Mandatory unknowns |
|---|---|---|
| Android | Public Compose platform-input host plus custom `InputConnection`, or a documented native host if required. Handle commit/preedit/region/selection, UTF-16 and code-point deletion, batches, surrounding/extracted text, cursor anchor, editor actions and content insertion. | Real keyboards, connection closure, candidate rectangles and TalkBack editing. |
| iOS | Attached `UIView` implementing `UITextInput`/`UIKeyInput`; revision-aware positions/ranges, tokenizer, delegate notifications, selected/marked text and geometry. | Public hosting/focus integration, marked-text termination, dictation, VoiceOver editing. |
| Desktop | Public AWT input host and `InputMethodRequests`, routed to the owner thread; composed-text events and candidate geometry. | Separate Windows/macOS/Linux IME, focus and accessibility qualification. |
| Web | ProseMirror view/state as a controlled DOM projection with engine history and synchronous Kotlin admission. | Composition-node continuity, JS/Wasm calls, noncancelable mutations, large DOM and browser AT behavior. |

Android cursor positioning: after replacement starting at a with m units, positive `newCursorPosition` c requests `a + m + c - 1`; nonpositive c requests `a + c`, clamped to valid projection bounds. For code-point surrounding deletion, scan both requested sides before mutating; reject malformed traversals atomically under the Android contract. UTF-16 deletion uses unit counts and may leave isolated units. Never route a code-point request through a UTF-16 helper. Verify exact selection/deletion boundary behavior against [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection), including noncollapsed selections and composing ranges; record platform-specific command lowering as tests.

iOS selected range in `setMarkedText` is relative to the supplied marked text. `unmarkText` preserves it. Retained position objects need a revision/anchor mapping policy; invalidated objects return supported failure behavior, not unchecked integer reuse. Text/selection delegate notifications must surround changes as required by [UITextInput](https://developer.apple.com/documentation/uikit/uitextinput). The current web documentation fetch does not independently validate all method details; compile SDK interfaces and execute the host tests.

AWT events can include a committed prefix and composed suffix. Its committed-text query coordinate system excludes the composed segment. For marked `[a,b)`, a committed-text index q maps to live q before a and `q+(b-a)` at/after a; live points inside marked text have no committed-text coordinate. Endpoint boundary affinity alone is insufficient for empty slices: mapping `beginIndex = a` with `Association.AFTER` and `endIndex = a` with `Association.BEFORE` produces an inverted range `[b, a)`, causing a runtime `StringIndexOutOfBoundsException`. The normative contract requires:
1. Empty slices (`beginIndex == endIndex`) at all boundaries (especially at composition boundary a) must explicitly return an empty iterator (`new AttributedString("").getIterator()`) without translating endpoints across the boundary.
2. Slices crossing the composition boundary (`beginIndex < a` and `endIndex > a`) must concatenate prefix `fullText.substring(beginIndex, a)` and suffix `fullText.substring(b, endIndex + (b - a))` without exposing composed text `[a, b)`.
3. `getTextLocation(offset)`: with composed text, use its relative TextHitInfo and preserve leading/trailing affinity. Without composed text, ignore offset and use the beginning, in line direction, of the selection highlight on its last selected line; a collapsed selection uses its actual caret. Return absolute screen coordinates and a zero-thickness rectangle: width 0 for horizontal text, height 0 for vertical text. The painted UI caret may have a separate visible thickness. This corrects the previous committed-offset/2px rule against the [JDK 23 contract](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), rechecked 15 October 2026. Synthetic layout fixtures cover these rules; a native AWT host remains unqualified.
4. `getLocationOffset(x, y)`: screen hits outside composed text strictly return `null`; vertical line bounds are exclusive on the lower bound (`y < screenOriginY || y >= screenOriginY + lineHeight`); hits inside composed text return hit info relative to composed text; returns `null` when no composition is active.
5. `getSelectedText(attributes)`: normalizes forward, collapsed, and reversed selection ranges; collapsed selections return an empty iterator; requested text attributes return iterators with unsupported font/input-method attributes cleanly mapped as empty attribute sets.
6. `getInsertPositionOffset()`: return the native insertion landing point in committed-text coordinates: markedStart when marked text exists; otherwise min(anchor, focus), matching this engine's selected-range replacement policy. The numeric query supplies insertion context; popup geometry comes from getTextLocation, whose selection/line-direction rules are separate. This contract follows the distinction in the [JDK 23 InputMethodRequests specification](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), inspected 15 October 2026; real geometry and host behavior remain G0/G5 requirements.
7. `cancelLatestCommittedText(attributes)`: unsupported in this document engine; explicitly returns `null`.
8. `getCommittedTextLength()`: visible projected UTF-16 length minus active marked length (zero if no mark). Validate with forward/reversed selections and nonterminal unmark; do not substitute internal Dc length.
9. Scoping: A headless Java interface spike validates the coordinate translation unit only, not a qualified native host. Real Compose/AWT host input, selection, geometry, settlement, and editable accessibility remain unvalidated (Gate G0 Desktop NOT RUN / INCOMPLETE). Validate requests against [InputMethodRequests](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html). Never expose full live text under a committed-only index.

No production adapter may depend on reflection into private Compose internals. Existing research reflection is a diagnostic seam only. A required fork must be explicit, versioned, licensed and backed by reproduction/upgrade tests; it cannot appear as a hidden dependency.

### 6.4 Web transaction boundary

Pin exact ProseMirror packages and lockfile integrity. Define schema/node-ID mapping and indexed position translation; never use a full HTML/JSON document round trip per keystroke. `editor-core` remains callable synchronously from the browser main thread; JS/Wasm representation and call overhead are a Phase 0 implementation choice settled by compilation and measurement.

On each candidate transaction: classify origin/steps; translate ordered supported replacements/selection; admit synchronously; receive canonical receipt; adopt the candidate state only when it matches the permitted projection. Disable ProseMirror history/collaboration plugins and duplicate default keymaps. Route app formatting, paste, cut and structural keys to the core. Native typing must not also execute through a hardware-key handler.

Projection equivalence compares text, structure and the engine's effective rendering attributes, not raw constituent storage. An admitted raw text change may reveal different effective marks when a grapheme splits. Apply receipt-derived minimal mark corrections synchronously outside protected composing DOM; never reject an already admitted core edit merely because the candidate view's old marks differ.

During composition, protect the outward grapheme closure of the marked interval AND the actual composing DOM nodes/ancestors whose mutation would replace those nodes. Character intervals alone do not establish safe DOM writes. Protect the entire composing Text leaf and its affected wrapper chain, including outside the marked substring, as specified in section 4.12.6. Keep a token/version-bound delayed-correction ledger for intersecting changes, including graphemes crossing marked boundaries. Maintain actual surface geometry and AT projection metadata for that temporarily divergent region. Recompute after every accepted edit; when the marked interval becomes null, retain the recorded protected node set until verified settlement. At settlement, derive corrections from the current canonical snapshot rather than replay stale queued mark operations, then clear the ledger. If minimal corrections cannot preserve the native composing nodes in the pinned view version, the Web gate fails. This DOM-write capability requires real-browser/IME qualification; the headless model does not implement it.

`beforeinput` alone is not authoritative. Handle DOM/ProseMirror changes that cannot be cancelled, event-order differences and final input after lifecycle notifications. Unsupported step classes trigger captured diagnostics and recovery. Native undo gestures (`historyUndo`/`historyRedo`) route to core history, never browser-only history.

Default Web release uses retained semantic DOM; `content-visibility` is an optional measured optimization disabled until selection, find, composition and accessibility tests pass. Do not virtualize away active composition or AT-requested content. Core performance does not prove ProseMirror projection performance.

### 6.5 Accessibility, clipboard and imports

Expose reading order, stable node identity, headings/lists/tables, text ranges, selected ranges, editing actions, link targets, image alternatives, focus and correct geometry. Offscreen content must be navigable. Required AT: TalkBack/Android, VoiceOver/iOS and macOS, NVDA/Windows, and a declared Linux reader/bridge combination. Web qualifies declared browser/AT combinations. Title announcement alone is not a pass.

Clipboard supports plain text, versioned internal rich fragments, sanitized HTML subset and TSV. Fresh IDs on paste; preserve link identity only within the new fragment's remapped namespace. Strip executable content and dangerous URL schemes; embeds are typed data with resource references. Async paste captures anchors/revision and cancels or asks for an explicit new target if the original target is deleted. Never apply to the user's later selection accidentally. Parsing budgets reject oversize/unbounded nesting with a report.

## 7. Transactional editing, undo/redo and durability

Pipeline: validate owner/epoch/revision -> resolve captured target/typing policy -> compile primitives -> apply to private persistent builder -> map selections/anchors -> normalize affected schema -> validate -> compare semantic delta -> record history/journal intent -> publish snapshot/receipt -> notify adapters/observers. Exceptions discard builder and staged effects. Observer commands queue for a later transaction.

Document no-op detection includes raw text, attributes, structure and resources; selection, typing intent, input lifecycle and decorations are session state. A no-op document delta creates no document history or text synchronization. Selection/input-only state changes still receive a new session revision/receipt as needed. `DocumentRevision` advances only for document changes; `SessionRevision` advances exactly once per successful external execution/publication, including accepted no-ops and document changes, under §2.2; duplicates, rejections and transport acknowledgements do not advance it. PositionRef uses the document revision. A snapshot/receipt exposes both, so caret/decoration changes do not invalidate all text layout. History entries record selection/session preimages without making selection-only changes document edits.

Each history group holds immutable before/after roots or complete equivalent inverse payload, selection/typing snapshots, registered-anchor deltas, origins and reason. Undo/redo are new transactions with new monotonic revisions; restored IDs/content equal historical states, revision numbers do not go backwards. For local editing, root restoration is allowed when history has not been bypassed by untracked external mutations. All semantic document edits enter history or explicitly clear its incompatible branch.

Ordinary contiguous typing merges only with matching location, attributes, origin and direction, within 750 ms and without an intervening selection/command barrier. A composition, including prior partial commits, constitutes one `Composition` history group. Formatting, paste and structural commands are separate groups after composition settlement. If a connection is replaced (`openNewConnection`) or disposed (`dispose`) while an active or partially committed composition has modified semantic document state, the coordinator must flush and commit the active overlay to $D_c$ and finalize exactly one `Composition` history group to the `undoStack` before tearing down the session (Findings A & B). Transient composition text must never remain in the document without attributed undo history, and prior partial commits must never lose their undo records across connection turnover. Typing toggles alone are session state; insertion records the resulting intent. New editing after Undo clears Redo.

Do not support formatting interleaved inside an unfinished composition group in the first release. The verified ProseMirror fixture showed why inherited formatting on later candidate replacements can escape ordinary mapped undo. Queuing the format command after settlement yields two well-defined groups and avoids claiming a selective-history solution that has not been implemented.

Pending Undo/format commands use the settlement-local action ledger in §4.6, including one-use cancellation/no-op credits and exact stack-top identity checks. Connection replacement/disposal cancels old pending commands with explicit lifecycle reasons (§4.5). History equality includes structure and resources, not only text/attributes.

Initial history-plus-redo budget: 1,000 settled groups or 64 MiB of retained payload beyond the current root, whichever is reached first, with the exact trimming/publication rules in §4.10. Protocol receipts and coordinate bases have separate bounded pools and cannot keep evicted history roots alive. Count unique retained slabs/subtrees/resources, not shallow object sizes. Evict whole oldest settled groups. The active composition baseline is pinned; report its exceptional memory separately and never drop required preimages mid-session. Oversize imports are bounded before admission; native accepted input is never silently truncated to meet a budget.

Canonical format `editor-document/1` is independent of tree shape, Compose and ProseMirror. Store IDs, schema, ordered nodes, resources, raw attributes and text. Encode text as UTF-16LE byte chunks with explicit unit counts (base64 in the JSON interchange form) so isolated surrogates survive every runtime. Validate endianness, lengths, ID uniqueness and resource references. Canonical ordering allows exact round-trip tests. Human-readable plain-text export is separate.

Native checkpoints, sequential journal framing and Web transaction adapters follow section 7.1. DurableSequence is separate from DocumentRevision; same-revision records can change composition acceptance. A saved acknowledgement is only for qualified durable completion, not visible publication.

Active-composition recovery preserves Dc and the complete Dv recovery draft under section 7.1. Restore committed content first; applying a retained draft is an explicit transaction with one new undoable group. General previous-process Undo/native sessions/pending commands are not restored. Routine diagnostics contain counters, versions and categories; raw text logging remains local and opt-in.

### 7.1 Durable records and crash recovery in v5

Persistence has its own monotonically increasing unsigned 64-bit DurableSequence; overflow rejects. DocumentRevision cannot order records: partial acceptance and ending composition may leave it unchanged while changing what is committed. Every persisted state transition gets a new sequence. Coalesced autosave acknowledges only requests covered by the resulting durable state.

The canonical codec remains editor-document/1, with UTF-16LE chunks and exact counts. Validate base64, byte counts, unit metadata, IDs and schema; production also preflights total bytes/depth/resources before allocation. Recovery starts a fresh non-reused ID namespace for new units/session identities while preserving existing IDs. Restarting must not reset the allocator into persisted identities.

**Recorded state.** Store revisions, active flow/selection/typing metadata, composition-active flag, complete canonical Dc and Dv (or equivalent lossless deltas), and unresolved recovery drafts. Dc includes accepted prefixes and accepted secondary-cell clears; Dv includes provisional structure. Persisting only one side cannot reconstruct an unfinished multi-cell gesture.

**Reference framing.** Run-20 uses ASCII EV51, a little-endian UInt32 payload byte count, UTF-8 JSON, 32 raw SHA-256(payload) bytes and ASCII END5. Bound length before allocation; the fixture ceiling is 256 MiB, not a universal product limit. Journal payload fields: format editor-journal/1, decimal-string sequence, previousHash (64 lowercase hex digits; zeros at genesis), state. Frame identity is SHA-256 of its exact payload. Checkpoints contain format editor-checkpoint/1, covered sequence, last journal hash and state. This is SHA-256, not the review's claimed CRC32C implementation. Compression/chunking requires a versioned codec with the same guarantees.

**Append/acknowledge.** Serialize privately, append in owner order, flush through the target's qualified durable barrier, then acknowledge that sequence. Partial writes poison the writer: reject more appends until recovery selects/truncates or rotates the valid prefix. Never append acknowledged edits beyond a torn frame that replay must stop at. Failed flush cannot advance durable acknowledgement. Visible editing acceptance and durable-save acknowledgement are different APIs.

**Checkpoint ordering.** Checkpoint only a known durable prefix. Write/validate a new generation, flush it, atomically publish that generation, durably persist its directory/manifest transition, then retire covered journal segments/older generations. A crash before publication uses the old recoverable prefix; after publication but before cleanup can leave covered duplicate frames without replaying them twice. Never erase the old prefix before its replacement is durable. POSIX file/directory fsync, Windows flush/replace ordering and mobile filesystems need distinct tested adapters; Web uses completed IndexedDB transactions for corresponding atomic state. The reference simulates these boundaries and proves no physical filesystem guarantee.

**Replay.** Validate framing, bounded length, checksum, sequence, state schema and chain continuity. Starting from a selected valid checkpoint, ignore only complete verified records it already covers. The next uncovered sequence must be base+1 with matching previousHash. Do not skip records using afterDocumentRevision <= currentRevision. Stop at the first incomplete/invalid uncovered frame; never scan forward for another magic marker or replay across an interior gap. Preserve corrupt bytes and report the recovered prefix. If no validated state exists, return a recoverable storage error rather than an empty document. Detection does not imply all damaged acknowledged data can be reconstructed.

**Chosen recovery behavior.** Open Dc as the current document with no old native session, pending queue or ordinary previous-process Undo. Preserve a separate complete provisional draft containing Dv and its Dc preimage whenever persisted active composition had a semantic difference. Inform the user that an unfinished draft is available. Do not automatically make provisional cell deletion the only surviving document. This intentionally replaces both v4's visible-only recovery and Gemini's suggestion to make Dv the automatic current document.

The user can keep committed content, inspect/export the draft, or explicitly apply it. Applying submits recoverDraft(draftId, expectedBase) through the same serialized owner, checks its Dc base still matches, adopts all of Dv, and records one undoable RecoveredDraft group. An intervening edit causes RecoveryBaseChanged rather than overwrite. One Undo restores Dc, including secondary cells. Large drafts use bounded/referenced recovery artifacts, not unbounded clipboard/receipt payloads. General previous-process Undo remains excluded.

Unresolved drafts survive later autosave/checkpoint cycles. Neither a new ordinary snapshot nor old-journal cleanup discards them. Only an explicit apply/discard decision durably recorded with current state retires a draft. Failed decision persistence leaves the draft available on reopening. Recovery may create a new document/session revision when selecting Dc differs from the last visible state; native epochs never resume. Drafts and resources have a separately reported ownership budget, protected from ordinary Undo eviction.

Run-20 tests cell recovery, partial acceptance, same-DocumentRevision terminal commits, every byte cut in a representative frame, failed sync, checkpoint stages, corrupt/interior gaps, malformed UTF-16, namespace reuse, oversize lengths, draft retention through later checkpoints, failed decision saves and writer poisoning. These validate the byte/state protocol, not physical crash/power-loss durability. G7 remains open until real adapters pass fault injection at write/flush/replace/transaction boundaries.


## 8. Phased implementation blueprint and acceptance gates

These phases remain the library engineering gates. PRODUCT-ROADMAP.md supplies the current product dependency order: M1/C0/S0 and GP0 investigate cross-cutting risks before public API/schema freeze and broad dependent construction. A failed gate blocks the affected layer, while independent core work may continue. No gate may be marked passed because the common code compiles, an old fixture prints “pass,” or a simulator substitutes for a required physical-device interaction.

### Phase 0 — Capability and dependency freeze

Deliver `validation/capability-manifest.json`, minimal native/DOM hosts, version locks and a requirement/evidence matrix. Record Kotlin, compiler plugin, Gradle/AGP, SDK/Xcode/JDK, Compose/Foundation/Skiko, native backend versions, ProseMirror packages, Unicode data, fonts, hardware, browsers, keyboards and AT versions.

Historical reproducible probe baseline is Kotlin 2.2.20, Compose Desktop 1.12.1, Skiko 0.150.1 and JDK 23.0.2. It is not a blanket toolchain recommendation. The production candidate must be an actually resolvable compatible set compiled for every target. Verify release status from official sources; do not carry v1's Kotlin 2.4.20/Unicode 18.0 assertions forward unexamined.

Gate G0: public hosts compile; real input/selection/geometry and editable accessibility spikes work for declared environments; settlement mechanisms are recorded; synchronous Web round-trip works without replacing composing nodes. A headless interface implementation with dummy geometry is a unit test, not a qualified native host; Gate G0 Desktop is marked NOT RUN / INCOMPLETE until the actual Compose/AWT host, real input, selection, geometry, settlement, and editable accessibility requirements have been demonstrated. Unavailable devices are NOT RUN, never PASS. A Windows-only workspace cannot qualify iOS. Declare unsupported scope explicitly rather than silently weakening it.

### Phase 1 — Headless kernel and persistence foundation

Deliver schema, IDs, raw UTF-16 storage, canonical attributes, all primitive operations, independent simple reducer, canonical serialization and invariant checker. Start with string/run-list reference before optimizing the persistent tree. No UI dependence.

Gate G1 also requires the full measured hierarchy, version-wide locator maps, deep nesting, seam join/split counters and physical heap/latency measurement.

Gate G1 (production implementation remains unauthorized by this document): I01–I06 core tests and serialization/replay prerequisites for I14; full I14 crash-durability qualification belongs to G7, not G1; exhaustive short offset maps; at least 100,000 randomized operations; exact save/load of attributes and malformed UTF-16; schema-invalid input rejection; exception rollback. Cross-piece graphemes and surrogate pairs included. Measure storage without claiming total editor performance.

### Phase 2 — Commands, positions, formatting and history

Deliver registered anchors, selection types, structural command compiler, typing intent, grapheme rendering projection, attributed history and semantic no-op handling. Implement history before IME integration rather than retrofitting it after input behavior is established.

Gate G2: every §5 boundary row has forward/reversed/empty/block-edge fixtures. WOMAN–ZWJ–LAPTOP round trip and explicit-format variant pass. Undo/redo preserve raw attributes, structure, IDs and selection over fuzzed traces. No-op styling produces no native text effects. Async target deletion is detected. All §2.6 commands have examples and expected canonical results.

### Phase 3 — Input coordinator with deterministic fake adapters

Deliver epoch/sequence rules, batches, reentrancy, attribution epochs, partial commit, command tickets, settlement/fault outcomes and local recovery. Fake adapters implement multiple documented orderings; they do not stand in for device qualification.

Run-18 now executes the repaired integrated reference replay of input reservations, structural replacement and the action ledger through one dispatcher, including post-abort fences, changed terminal commits, rollback and reentry. G3 still requires the production Kotlin coordinator, complete required trace catalog and the stated invariant/Unicode checks; the reference subset is not a production gate pass.

Gate G3: I07–I10, I12–I13 and I16 pass model checking/trace replay, including §4.3–4.7 cross-paragraph commit/cancel/partial-commit/history with exact block IDs and properties. Candidate `ab -> x -> ab` restores original attribution within an epoch. Partial commit cannot restyle a frozen prefix. Explicit range expansion creates the specified new baseline. Formatting queued during preedit yields separate history after settlement; Undo never restores transient phonetic text when undoing only formatting. Unknown settlement never becomes success by timeout. Raw selection and drawable caret remain separate.

### Phase 4 — Layout, drawing and pointer behavior

Deliver Compose paragraph cache, measured-height tree, effective rendering styles, frame keys, hit testing, visual navigation, scroll anchoring and geometry transforms. Use normal full paragraph layout. Keep pathological paragraphs in the test corpus; do not conceal them through arbitrary chunks.

Gate G4: I11; rendering/hit-test round trips at bidi boundaries and wrapped lines; ligatures, combining sequences, emoji and mixed direction; fractional scaling/zoom and large scroll coordinates; stale font/layout jobs discarded. Screenshot/raster checks and native candidate-rectangle checks are both needed. Performance figures are measured per backend with fixed font files.

### Phase 5 — Real platform input and Web projection

Deliver production Android/iOS/Desktop adapters, ProseMirror bridge, key routing, selection menus and lifecycle handling. Implement one platform end-to-end first; replay shared semantic traces everywhere, then execute each platform's native traces independently.

Gate G5 per environment: English prediction/autocorrection; Chinese Pinyin; Japanese conversion/reconversion; Korean; Arabic/Urdu/Hebrew with Latin/numbers; Devanagari/Thai; dead keys; emoji; dictation; hardware keyboard; blur/rotation/window changes; partial commit; toolbar/paste/Undo during composition. Confirm exact-once text, attributes, history and candidate position, including cross-paragraph selected-range replacement, initial empty preedit deletion, provisional joins, cancellation and partial commit without a preparatory application deletion. Browser synthetic events are insufficient evidence for real IME behavior.

### Phase 6 — Structured surfaces, clipboard and accessibility

Deliver list/table/embed interactions, cross-block/cell selection, imports, rich clipboard, accessible document actions and offscreen navigation. Core structural commands already exist from Phase 2; this phase integrates their surfaces.

Gate G6: editable AT tests, cell boundaries, empty/list nesting cases, structured paste, image alternatives, link identity, noncancelable unsupported proposal recovery. No ordinary supported input trace may require destructive resynchronization. Unsupported cross-structure reconversion is explicitly surfaced/documented, not counted as passing.

### Phase 7 — Durable recovery and lifecycle qualification

Deliver checkpoint/journal implementations, migrations, resource retention, autosave state and crash recovery UI. Complete per-platform storage semantics and disposal handling.

Gate G7: kill/crash at every write boundary, corrupt tail, duplicate journal record, disk-full/quota failure, app suspension during composition, reopen after partial commit, and 100 repeated open/edit/close cycles. No lost acknowledged save; no resurrected native session; no monotonic retained-memory leak after warmup.

### Phase 8 — Performance and release qualification

Measure release builds on declared hardware and exact pinned fonts. Warm and cold runs separately; at least 100 measured edits for latency percentiles after a stated warmup; report p50/p95/p99, maximum, allocations and workload. Short exploratory medians are not release measurements.

Targets, not achieved promises:

| Workload | Gate |
|---|---|
| Normal local core edit, block <=4,000 units, standard document | p95 <=2 ms, p99 <=5 ms; no document flattening. |
| Layout of ordinary active paragraph <=4,000 units | p95 <=8 ms on declared reference device. Include formatting fragmentation and bidi. |
| Accepted input to presented current frame at 60 Hz | p95 <=33 ms excluding time in external IME; measure actual presentation. |
| Standard 1M-unit/10K-block scroll | p95 UI frame work <=16.7 ms; track dropped frames and estimate corrections. |
| Core + bounded history | <=128 MiB on standard corpus, excluding runtime/renderer/media; report total process separately. |
| 100K-unit single paragraph | Qualification target p95 <=100 ms; **known high-risk/unmet**, not covered by normal-block gate. |
| Resource lifetime | No continuing retained-memory growth across repeated lifecycle tests. |

Gate G8: all correctness gates plus measured target matrix. Complete library/product delivery additionally requires GL, applicable GP0–GP3, U0–U5, S0/C0/C1/R0/I0 and the product feature/platform matrix. G8 alone qualifies neither printing nor collaboration. If pathological paragraphs fail, report the failed workload; either repair/replace the backend and rerun correctness, or obtain an explicit product-scope decision. Do not announce unrestricted large-paragraph performance. Backend forks and thread offloading are experiments, not assumed solutions. A release can be qualified per platform; do not label all four production-ready until all four pass their own gates.

## 9. Adversarial review, evidence and remaining decisions

### 9.1 Existing evidence to reuse carefully

| Evidence | What it establishes | What it does not establish |
|---|---|---|
| `research/grapheme-style-roundtrip.mjs` | v1's destructive cluster normalization loses attribution; retained attribution preserves 16 tested combinations. | A released Compose grapheme bug or general rendering equivalence. |
| `research/existing-attribution-control.mjs` | ProseMirror model already supports the essential retention behavior. | Browser IME qualification. |
| `research/composition-path-dependence.mjs` | Previous-candidate prefix/suffix reconciliation can lose mixed marks after a detour. | The new epoch algorithm is already implemented/tested. |
| `research/composition-history-probe.mjs` | A specified candidate-inheritance policy plus ordinary mapped history leaves marks after undo. | A general ProseMirror history defect. |
| `research/compose-probe/README.md`, `Probe.kt` | Published Desktop artifact loses styles through tested undo and clears synthetic composition on tested style mutations. | Real OS input failure on every platform. |
| `UnicodeProbe.kt` | Specific UTF-16 helper/code-point adapter contract deviations. | A visible caret bug or all adapter behavior. |
| `ChannelProbe.kt`, `HiddenStateProbe.kt` | Restricted expansion-channel results and hidden interval-state counterexamples. | A general Compose-compatible span compactor. |
| `research/compose-benchmark-verdict.md` and clean JSON | A real adversarial Desktop paragraph-construction penalty; direct finite-width layout did not remove it. | Exact stage attribution, native patch success or mobile timing. |
| Bidi/bracket research suites | Useful restricted algorithm/oracle/cost evidence. | Full current Unicode bidi, editor speedup or justification to ship that index. |

The completed JVM benchmark exits successfully. The older large run has a Maven shutdown error after measurements. The standalone native patch attempt failed to validate. Keep those statuses distinct. Research tests that assert baseline failure can pass while the product remains incorrect.

### 9.2 Required new regression cases

Assign stable IDs `R01`–`R16`:

1. R01: ZWJ insertion/removal preserves constituent raw styles; explicit cluster formatting intentionally changes them.
2. R02: `bold(a), italic(b) -> x -> ab`, identical candidate, empty candidate, repeated-text prefix ties and surrogate-pair copy boundaries.
3. R03: partial commit then suffix replacement/cancellation; frozen prefix survives with exact attributes.
4. R04: native explicit target expansion starts a new attribution epoch; protected structural boundaries reject/recover without silent data loss.
5. R05: noncollapsed formatting commands targeting composition queue in FIFO order across Composing and AwaitingSettlement; preedit updates maintain settlement request; commit or completeSettlement triggers natural settlement; range formatting executes strictly on final committed extent and yields distinct history groups; duplicate unmark in AwaitingSettlement is rejected as AlreadyUnmarked; undo formatting preserves committed text; collapsed toggles create no document history.
6. R06: old epoch callback after a new connection; internal retry with same stamp versus genuinely repeated native commit.
7. R07: raw-unit deletion, code-point deletion, invalid-surrogate preflight, reversed selection and raw/drawable caret divergence.
8. R08: no-op style transaction, exception rollback and ordinary style command; verify text notifications/history separately.
9. R09: split/join/move/delete/cell deletion with live anchors, annotations and undo.
10. R10: links with same URL/different IDs, edge typing, whitespace exit, code-run escape and bold-off over heading inheritance.
11. R11: final input after composition-end, noncancelable browser mutation, DOM subtree preservation and correction after settlement.
12. R12: AWT InputMethodRequests committed-text coordinate system excludes composed text; all valid slices match independent oracle; empty slices (especially [a, a)) return empty iterator; cross-composition slices concatenate without composed-text leakage; invalid slices throw IllegalArgumentException; getTextLocation is composed-text-relative with caret rectangle geometry; getLocationOffset returns null outside composed text.
13. R13: stale layout/font result, window transforms, scroll-anchor correction and exact active geometry.
14. R14: raw UTF-16 and hidden constituent attributes across save/load, export conversion report (serialization round-trip verified; physical crash durability and journal tail recovery deferred to Phase 7).
15. R15: standard block workload plus long bidi, malformed brackets, excessive bracket depth, long combining sequences and fragmented styles.
16. R16: real AT selection/edit/read-back including offscreen blocks and table cells.

### 9.3 Unresolved risks and decision rules

| Risk | How to settle it | Allowed outcome |
|---|---|---|
| Public native hosting cannot meet the geometry/focus contract | G0 compile and real input host | Documented alternative host or platform gate failure; no private reflection shortcut. |
| No safe way to force settlement | Native traces per keyboard and lifecycle | Wait for natural completion; supported command can remain pending/cancelled. Frequent failures block release. |
| Baseline candidate attribution feels wrong for interior unchanged text | R02/R03 plus usability review | Change this documented product policy through a new revision; do not invent runtime intent. |
| DOM mark correction disrupts candidate nodes | R11 with real IMEs | Revise bridge within same authority contract; otherwise Web unqualified. |
| Shared grapheme data differs from native shaping | Official segmentation tests plus visual/native tests | Keep command segmentation and native geometry distinct; publish discrepancies. |
| Long paragraph layout unusable | G8 full-pipeline measurements | Tested backend correction/replacement or explicit scope decision. No bidi-index resurrection by assumption. |
| Custom accessibility cannot expose editable ranges | G0/G6 AT interaction | Native accessibility host work or affected platform unqualified. |
| Estimated offscreen heights cause jumps | Adversarial image/font arrival and scrolling | Repair anchor/measurement scheduling; no exact-height claim before measurement. |

### 9.4 Remaining Uncertainties Register

The following items are unresolved platform feasibility questions, unvalidated optimizations, or durability aspects that cannot be validated in model scope:

| ID | Uncertainty / Risk | Current Status | Required Resolution Phase | Impact if Unresolved |
|---|---|---|---|---|
| U1 | Desktop Host Window & Real OS IME Integration | NOT RUN / INCOMPLETE | Phase 0 Gate G0 & Phase 5 Gate G5 | Desktop candidate window misplaced or input dropped; requires custom AWT host or Compose fork. |
| U2 | Android Custom InputConnection & TalkBack 14+ | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | Atomic code-point deletion or TalkBack word-level reading fails compliance; blocks Android release. |
| U3 | iOS UITextInput Marked-Text Lifecycle & VoiceOver | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | `setMarkedText` / `unmarkText` drops selection or VoiceOver fails; blocks iOS release. |
| U4 | Web ProseMirror Synchronous Kotlin Admission | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | Synchronous bridge destroys candidate DOM nodes; synchronous admission preserved without automatic fallback prescription (architectural fallback or asynchronous echo requires owner approval). |
| U5 | 100K-Unit Single Paragraph Layout Latency | HIGH RISK / UNMET | Phase 4 Gate G4 & Phase 8 Gate G8 | Large single paragraph stalls UI thread; 100-ms single-paragraph target (p95 <= 100 ms) preserved without automatic block limit or splitting prescription. |
| U6 | Skia Native Layout Optimization Patch | UNVALIDATED OPTIMIZATION (REJECTED) | Excluded from baseline | Baseline uses unpatched Skiko; no novelty or unverified C++ patches assumed. |
| U7 | Crash Durability and Journal Tail Recovery | PARTIAL (Serialization round-trip verified; physical recovery unrun) | Phase 7 Gate G7 | Process kill at write boundary could corrupt document without journal validation. |

### 9.5 v5 independent review and executed evidence

The original Gemini archive is preserved unchanged under reviews/gemini-v5-readiness, with its input hashes. Codex reran its supplied interaction and storage probes, inspected the proposed model/benchmarks, checked primary API documentation and added independent counterexamples. Of its 25 listed interaction checks, 22 are accepted as genuine regressions. F07B uses an invalid baseline constructor/API and a different repaired fixture, so is not a valid reproduction. F10A_1 incorrectly rejects a legal AWT boundary alias; a real JDK 23 TextLayout probe confirms the alias. F10A_2 is adopted as a defensive null-hit policy, not a mandated JDK behavior or correctness proof.

Further review found defects in the proposed fixes: cancellation resurrected removed handles and reversed relocation; new candidate-bound anchors drifted; the journal skipped a same-DocumentRevision terminal record; and the storage prototype resolved a detached token through an old flow root. These observations are archived in run-20 and addressed by the v5 contracts and regressions. The review's CRC32C, full Skia-engine performance, live IME and mathematical-completeness claims exceed its actual code/evidence and are not adopted.

Run-20 now executes the accepted regressions, independent repair tests, official Unicode 16 grapheme data, measured-hierarchy equations, durable-record/recovery fault modeling and all retained run-19 verification groups. The new grapheme implementation was necessary because the installed Node runtime has Unicode 17 and failed one pinned Unicode 16 fixture. Current counts, exact commands, source/data hashes and evidence limits appear in the accompanying validation report. The previous baseline and all original reviewer artifacts remain unchanged.

### 9.6 Required experiments and remaining qualifications

1. G0/G5: actual adapter provenance after forced cancellation/relocation, reservation timing under synchronous reentry, and qualification of safe fences. A locally minted token cannot prove the origin of a late OS callback. Preserve ambiguous input and block release if normal typing cannot be made reliable.
2. G0/G5 Web: composition over selected objects/containers, actual DOM-node preservation, delayed mark corrections crossing grapheme/ancestor boundaries, and candidate/projection synchronization.
3. G2/G3, when implementation is separately authorized: port the whole-document owner and §4.11 command/anchor/cell-flow/typing-intent contracts to Kotlin, preserving the shared admission/settlement pipeline. Extend interaction coverage beyond the recorded finite fixtures and meet the 100,000-operation qualification corpus. Run-20 provides executable reference coverage; it is not the production implementation or a pass for G2/G3.
4. G2/G6: layout/native hosting for insertion gaps and list items beginning with nested lists/objects, marker/AT navigation, system clipboard integration, arbitrary HTML import and supported-format export round trips. Headless gap commands and canonical rich fragments now execute, but native gap hosting, visual navigation and actual importers remain unqualified.
5. G0/G5: native object-seam proposals, native marked-text query coordinate conventions, and exact initial user-selection provenance. Whole selected objects are supported; an impossible retained-object inline projection is rejected, not silently flattened.
6. G1/G8: depth-aware hierarchical indexes, fragmentation, measured heap/GC reclamation for §4.10 ownership pools, seam concat/split and full paragraph/layout latency. No unmeasured optimization or product cap is assumed.
7. G7 and existing U1-U7: physical durability, production Unicode word/bidi/table ports, native IMEs, screen readers and platform release gates remain unqualified. Passing the Unicode 16 grapheme corpus does not qualify these separate properties.

The reviewed counterexamples have chosen semantics and integrated executable checks for the recorded public-boundary subset. This is a reviewable specification baseline, not proof that every interaction has been explored. Further empirical failures require a concrete counterexample and a versioned repair. No production implementation is authorized by this document.


### 9.7 Preserved v5.0.0 freeze decision and change control

The following freeze applies to the preserved v5.0.0 baseline. The owner has now invoked its explicit-product-requirement-change provision. Section 10 records the v5.1 amendment; no prior PASS or freeze status extends to the new pagination design or gates.

**Specification v5.0.0 is frozen as a reviewed semantic contract and conditional architecture baseline.** This freezes the selected ownership, schema, admission/settlement, command/anchor/cell, formatting, history, storage-measure and recovery contracts. It is not a proof of complete correctness, a validated production implementation or a guarantee that each native backend is feasible. The exact frozen artifacts and evidence are identified by v5-freeze-manifest.json.

No unresolved contradiction has a passing-status exception. Known experimental dependencies are explicit gates, not facts assumed solved: real input provenance/cell/gap hosting, DOM/AT behavior, production persistent indices and memory, filesystem durability, import/export pipelines and full-pipeline performance. G0 host feasibility runs before broad production construction when that work is separately authorized. A failed bridge/backend/storage experiment that cannot preserve these contracts is an architecture reopen trigger, not permission to cut required functionality.

Reopen only for a reproducible counterexample, primary-source incompatibility, measured failure of an acceptance gate, or an explicit product requirement change. Record the smallest trace, expected versus observed behavior, affected invariant, selected correction and discriminating regression. Use v5.0.x for compatible clarifications/repairs; version incompatible schema/protocol/product changes explicitly. Preserve this freeze and its evidence. Generic requests to re-audit without new evidence do not force another specification revision.

Release remains blocked on applicable G0-G8 gates. Freeze does not authorize production implementation. The specification is sufficient to direct the next qualified experiments and later implementation without asking coding agents to invent these core contracts.


## 10. Owner-approved library and paged-output scope amendment

This is the preserved v5.1 amendment. Its scope requirements continue to apply; section 11 and the v5.2 document pointers above supersede its former current-version status and any narrower application exclusions.


Date: 17 October 2026. Authority: the project owner's explicit request to add page-based layout and print-perfect fidelity and reaffirm the open-source Compose Multiplatform WYSIWYG rich-text editor library as the deliverable.

### Status and precedence

The current requirements baseline is v5.1.0. The normative source is `plan-v5.1.md`; the complete review document is `editor-v5.1-standalone.md`. The user-approved scope is fixed, but the new pagination/export implementation design and qualification remain OPEN / NOT RUN. This amendment does not claim an experimentally validated pagination backend.

The original `plan-v5.md`, `editor-v5-standalone.md`, `v5-freeze-manifest.json`, validation artifacts and published `spec-v5.0.0` tag remain unchanged historical baselines. They still describe v5.0.0 accurately. The pagination exclusions in v5.0.0 do not apply to v5.1.0. Existing editing, Unicode, IME, structure, history and recovery contracts remain required; this amendment does not relax them.

### Product mission

Ship an open-source, embeddable WYSIWYG rich-text/document editor library for Compose Multiplatform on Android, iOS, Desktop and Web. Solve the original editing problems: unstable caret/selection, native composition, formatting boundaries, hierarchical editing, Unicode and cross-platform semantic consistency. The desktop prototype is an integration test application and future example consumer, not the product boundary. Pagination is an additional library capability, not a replacement for that mission.

Consumers must receive a headless Kotlin core, public Compose integration, declared platform adapters, optional page/print modules, documented extension interfaces and independently usable samples. Native targets retain Compose rendering; the existing Web semantic-DOM adapter remains the baseline subject to qualification. Embedding APIs must disclose required Web DOM hosting rather than promise an unimplemented identical native Canvas path.

Library release requires a chosen OSI-approved project license, compatible dependency/font/resource redistribution and embedding terms, notices, a contribution guide, reproducible builds, versioned installable packages, API documentation, compatibility policy and samples that consume published/local-staged artifacts outside this checkout. No project license is selected or granted by this amendment. License choice and package coordinates remain explicit release decisions, not assumed completed work. Applications own their file pickers, toolbars and storage integrations; default UI is optional.

### Required paged-document behavior

- Continuous and paged editing modes share the same document, commands, native input ownership, annotations and Undo/Redo. Switching view mode or zoom does not rewrite content or create an editing history entry.
- Document page settings include A4, Letter and custom physical size, portrait/landscape, margins, header/footer areas and page numbering. Settings and header/footer content persist and are undoable semantic changes. Header/footer flows use the existing command/flow ownership rules; their rendered repetition is not duplicated document content. Page-number fields are derived from the page plan.
- Explicit page breaks persist as semantic layout directives with documented insertion/deletion, clipboard, import/export, selection and Undo behavior. They must not masquerade as native LF, split one editor into independent page editors or insert characters merely to create a visual page boundary. The exact typed directive schema/native projection and format migration require a design decision before implementation.
- Automatic pagination handles paragraphs, headings, quotes, nested lists, code blocks, rectangular unmerged tables and images/objects. Include keep-with-next, keep-together and configurable widow/orphan constraints. Page settings and paragraph rules are properties; automatic fragmentation is derived geometry.
- Table continuation supports repeated header rows and continued cell content with stable logical cell/flow identity. Oversize rows, images, atomic objects and keep constraints need deterministic overflow behavior and diagnostics. Never clip, truncate, shrink text or discard content silently to make a page fit. A hard constraint that cannot fit yields an explicit export/layout failure; soft-constraint relaxation must be ordered and reported.
- Carets, selections, drag selection, keyboard navigation, IME candidate rectangles, cross-page composition and accessibility map through page fragments to the same logical positions. Page gaps are not text. Reflow cannot create extra composition sessions or history groups. Repeated visual headers and table headers must have defined canonical editing targets and reading order, not ambiguous copied identities.
- Headers/footers and page numbers are part of this scope; footnotes, citations, arbitrary magazine columns and Word/DOCX round-trip compatibility are not implicitly promised by this amendment.

### Print-perfect fidelity contract

For the same document snapshot, page profile, qualified typography profile and resolved resources, paged preview, PDF export and printing must use the same immutable page plan: identical page count, line/page breaks, glyph placement, table/image bounds, clipping and header/footer placement. A browser print stylesheet that independently repaginates content is not sufficient evidence.

The library must provide a PDF export path and platform print integration contracts consuming that page plan. Export must preserve searchable/selectable text and reading order where supported by the chosen PDF backend; any unsupported accessibility/export feature must be reported and must not be advertised as qualified. Font embedding/subsetting requires appropriate rights; unavailable fonts/resources or prohibited embedding return explicit diagnostics, never an unnoticed fallback that changes layout.

Print fidelity covers page geometry at actual size with matching paper settings. Printer rasterization, physical tolerances, driver-added scaling and nonprintable hardware margins are external constraints. The library must surface relevant mismatches and avoid silently enabling fit-to-page. Display antialiasing may differ; that does not permit different pagination. Continuous native display may retain platform-specific metrics, but a declared portable paged profile must use pinned font bytes, shaping/line-breaking rules and resource metrics so its page plan is consistent on every platform claiming that profile.

Export defaults to an immutable committed snapshot. Active preedit is neither silently accepted nor lost: report that a provisional draft exists; exporting a visible draft requires explicit opt-in and labels its provenance. Never force native settlement just to print. Stale asynchronous page plans cannot replace current editing geometry; an export may finish for its explicitly captured snapshot and must identify that snapshot.

### Architecture constraints and unresolved decisions

Introduce `editor-pagination` for page settings, fragment mappings and immutable page plans, and `editor-export-pdf` plus platform print adapters for output. Neither module owns editable text or history. The canonical document has no automatic page fragments. Page plan keys include document/layout revision, page settings, resolved font/resource identities, typography profile and backend version; zoom/device pixels are separate presentation transforms unless a profile explicitly makes them layout inputs.

Retain whole-paragraph bidi/shaping semantics. Page boundaries must not cause arbitrary substring shaping or reset UAX #9 context. Pagination consumes qualified line/fragment measurements; table/cell fragments retain their own logical mappings. A job may resume/reuse a suffix only when its complete incoming pagination state and remaining content/layout dependencies match; equal page heights alone are insufficient.

Offscreen estimates are permitted for responsive editing but cannot be called exact or used for final export. A complete exact page plan is required before output. Page count fields and repeated content require a terminating layout strategy; page-number width changes must not oscillate. Cache/resource budgets and cancellation are explicit. Large single paragraphs remain a separate performance risk; pagination is not a workaround for incorrect shaping or slow paragraph layout.

Before production pagination work, settle and record: page-break/header/footer schema and migration; logical-to-fragment affinity at page boundaries; portable typography/backend choice; Web composing-DOM preservation in paged mode; table/cell fragmentation and oversize policy; deterministic constraint precedence and convergence; PDF text/font mapping; platform print capabilities; latency/memory budgets with declared hardware. These are engineering decisions and experiments still required, not permission to cut the requested capability. Failure may reopen the relevant backend under versioned change control.

### Additional acceptance gates

Existing G0–G8 remain applicable. Add these gates; all initially NOT RUN:

| Gate | Deliverables and acceptance |
|---|---|
| GL — Library integration and distribution | External sample consumers build/run on each claimed target using installable versioned artifacts. Public API supports state/commands/receipts, Compose surface lifecycle, extensions and injected storage/resources without sample-app internals. API compatibility checks, docs, license/notices and contribution/release process are present. |
| GP0 — Pagination feasibility/design | On a small mixed-script document with an image and spanning table, demonstrate one page plan driving editable paged preview and PDF. Resolve the listed design decisions and pin typography/resources. Exercise native input around a page boundary and protected composing DOM on Web; mark unavailable platforms NOT RUN. |
| GP1 — Page semantics and mapping | Independent fixtures for explicit breaks, empty pages/paragraphs, mixed RTL/LTR, ligatures/emoji/combining sequences, nested containers, long code, oversize objects, table continuation/header repetition, keep/widow/orphan rules, header/footer edits, page numbers, resize/reflow and exact Undo/Redo. Every logical content unit is accounted for without loss or unintended duplication; intentional repeated decorations are classified separately. |
| GP2 — Preview/export/print fidelity | Exact page-count and line-break agreement for the same profile; compare page-plan coordinates directly and independently inspect exported PDF geometry/text. Set and record tolerances before measurement (initial geometric target <=0.25 pt; raster comparisons allow documented antialiasing differences). Check physical-size output and driver options with real platform print paths. A PDF renderer alone does not qualify a physical print adapter. |
| GP3 — Interactive reflow and reliability | Editing/composition/selection at page boundaries, stale-job rejection, resource/font arrival, repeatable incremental-vs-full layout equivalence, bounded retained memory, deterministic convergence and measured reflow/export latency. Set numeric workload budgets at GP0; inherited million-unit/10K-block and long-paragraph workloads remain, with separate pagination measurements. |

Implementation order: continue the current verified input/correctness repairs. Library extraction/API work and bounded GP0 feasibility work may proceed independently once their prerequisites exist; do not build a full page engine on unqualified native input assumptions. Integrate paged semantics after GP0 and the relevant core/structure/layout gates. Qualify GP1–GP3 before claiming paged/print capability complete. A continuous-only preview can ship clearly labeled as a partial release, not as fulfillment of the complete v5.1 scope.

### Evidence and change control

The v5.0.0 tests remain evidence only for their original scope. None proves pagination, PDF fidelity, print hosting, library packaging or license readiness. Scope amendment checks confirm document consistency and preservation of the 45 original frozen hashes; they are not implementation tests. Do not rewrite old validation reports into v5.1 PASS claims.

Further change follows the existing counterexample/explicit-requirement process. Keep the v5.0.0 tag immutable. Publishing or tagging v5.1.0 is a separate release operation; this documentation change does not perform it.


## 11. Urdu application and library integration amendment

17 October 2026. Owner-requested replan. Status: PLANNED integration contracts; new design/implementation gates NOT RUN. No production code or stable API is implemented by this document.

### 1. Precedence and what is preserved

The top-level mission is `PROJECT-JOURNAL.md`; the product sequence and scope are `PRODUCT-ROADMAP.md` and `FEATURE-CATALOG.md`. This amendment extends v5.1 and is embedded in `plan-v5.2.md` and `editor-v5.2-standalone.md`. It takes precedence for the application-integration boundaries explicitly listed here. Unchanged local input, canonical attributes, structural admission, Unicode, history/recovery, retention and page-fidelity requirements remain mandatory.

Original v5.0.0 files/tag and v5.1 artifacts remain immutable historical baselines. Their tests are not evidence for collaboration or new document schema. The current desktop prototype remains an implementation experiment; no requirement to abandon its working code follows from this amendment.

#### Scope changes relative to earlier library milestones

- Collaboration, comments/review, persistent version history and offline synchronization are required application capabilities with library integration contracts. They are not promised by the existing local-only engine and not removed from the ultimate product because earlier scope deferred them.
- Merged/split cells, document tabs/sections, columns, footnotes/citations/equations and Office interchange are now tracked product requirements. Existing unmerged-table/local-schema commands are not retroactively declared compatible. S0 must specify versioned schema/command/migration additions before implementation or full feature claims.
- Nested tables and simultaneous multiple local carets remain explicit product decisions; remote collaborator cursors are required and are not “multiple local carets.”
- Previous-process Undo remains separate from persistent version history. The latter is required; the former is not automatically enabled. Collaborative per-user Undo needs its own algorithm and cannot reuse root restoration across peer changes.
- Library default behavior remains reusable and locale-neutral. The application supplies a qualified Urdu profile, fonts and RTL defaults; Urdu fixtures qualify the relevant library contracts from the beginning.

### 2. Separate authorities, not duplicate documents

One owner serializes mutations per local editor instance. An application document package has stable DocumentId, ordered SectionIds (tabs/subtabs), body and auxiliary FlowIds, resources, annotations and review records. A document transaction may touch multiple flows/sections atomically. A local input connection binds one current flow, as before. Cross-tab text composition is not inferred from adjacent export pages; moving tabs is a qualified connection/scope transition.

The library remains the only editable document owner within a client. A collaboration service or replica engine exchanges typed canonical changes through the owner; it must not maintain a competing BasicTextField/HTML/JSON document and overwrite the owner after each edit. Full snapshots are allowed for explicit load/resynchronization, not as a per-keystroke synchronization shortcut.

Keep these identities distinct:

| Identity/version | Meaning |
|---|---|
| DocumentId / SectionId / FlowId / NodeId / UnitId | Durable semantic identity, preserved through supported operations and versioned migrations |
| DocumentRevision / SessionRevision | Existing local publication counters; monotonic within the owner, not network clocks |
| OperationId / ActorId | Durable idempotent accepted change identity and attributed actor; allocation survives crash/reconnect |
| HistoryGroupId | Local user gesture grouping; one group can cover multiple accepted change records |
| SyncFrontier / acceptedCommitId | Opaque replication basis selected at C0; not inferred from local revision numbers |
| AnnotationId / SuggestionId / VersionId | Review/workspace records with independent lifecycle and retention |
| InputStamp / native epoch | Native provenance and admission; never a substitute for authenticated ActorId or sync ordering |

Actor attribution from a remote client is validated by the application service, not trusted merely because a field exists. Presence/remote cursors are ephemeral projections, not accepted content, local selection changes or Undo entries.

### 3. Draft integration envelope

The following are required concepts, not frozen binary/API signatures. C0/S0 must finalize serialization and algorithms before public 1.0 API/schema freeze.

```kotlin
// Conceptual contracts; existing commands/receipts remain the local baseline.
data class ChangeIdentity(val documentId: DocumentId, val operationId: OperationId,
                          val actorId: ActorId, val historyGroupId: HistoryGroupId?)
data class AcceptedChange(val identity: ChangeIdentity, val schemaVersion: Int,
                          val basis: SyncFrontier, val semanticOps: List<SemanticOp>,
                          val affectedFlows: Set<FlowId>, val resourceRefs: Set<ResourceId>)
interface ReplicationPort {
    fun onLocalAccepted(change: AcceptedChange)
    fun submitRemote(change: AcceptedChange): AdmissionReceipt
}
// Selective collaborative inverse replaces root restoration across remote edits.
interface CollaborativeHistoryPort {
    fun requestUndo(actor: ActorId, group: HistoryGroupId): UndoOutcome
}
```

The accepted change stream derives from a successfully published transaction, not mutable callbacks or arbitrary UI commands. It excludes transient preedit, hover/selection-only events and local-only diagnostics; accepted partial effects can produce durable operations while retaining one local composition history group. Durable outbox/id allocation and local committed publication must be crash-consistent: either one storage transaction or a recoverable protocol with explicit acknowledgement. Do not acknowledge synchronization merely on network enqueue.

Remote receipt results include Applied, Duplicate, Deferred with reason, RejectedSchema, RejectedPermission and NeedsResynchronization as distinct outcomes. The exact transform/rebase/merge machinery is chosen and tested at C0. Rejecting every concurrent valid edit is not a successful collaboration implementation. A failed change cannot publish partial structure or resource state.

### 4. Remote edits during native composition

Native preedit belongs to the local user and is not broadcast as accepted document text. Presence may identify a composing range without leaking candidate content. Accepted partial commits follow the existing semantics and participate in replication with their real accepted footprint.

C0 must implement and test a precise policy for remote changes before, after and intersecting the composing footprint, including structure/resource changes. Disjoint changes may map into the current document only if the visible projection/native context can remain valid. An intersecting change cannot silently rewrite the native candidate, discard it, or finalize it because a timeout elapsed.

Transport can receive/store remote changes while local application is deferred; bounded queues, progress reporting and retention are explicit. A safe path may preserve the local draft and restart/resynchronize only through a qualified native boundary. If no safe boundary exists, preserve ambiguous input/preimages and expose the conflict instead of claiming seamless concurrent typing. C0 must show eventual progress for normal supported traces; indefinite deferral does not pass.

Native callbacks retain their original flow, generation and coordinate basis while remote changes are processed. A remote transaction cannot relabel an already captured callback as belonging to a new local selection. Scroll/caret stability uses logical anchors, not old pixel positions.

### 5. Local Undo versus collaborative Undo

The original before/after-root Undo remains valid for isolated local history with no interleaved untracked mutations. Once remote changes are admitted, Undo must target the user's accepted operation group using a selective inverse/rebase policy chosen at C0. It is a new authored transaction. It must preserve unrelated peer text, formatting, structure and review actions; Redo must be similarly defined against current state.

Required trace: A inserts Urdu text, B inserts text adjacent to/inside the affected region, A undoes their own insertion. The result retains B's accepted contribution or reports a defined conflict requiring a non-destructive resolution; it cannot restore A's whole pre-B snapshot. Repeat for list splits, table edits, resource insertion, formatting and suggestion acceptance. Conflicts are typed outcomes, not silent approximate success. Do not hide collaborative incompatibility by clearing all Undo whenever a remote event arrives.

History grouping, replication deduplication and version-history records are different layers. Presence does not break typing history. Accepted operations may be sent before their user history group closes; identifiers permit grouping without rebroadcast or duplicate acceptance. Retention must preserve inverse payloads required by the published history capability.

### 6. Comments, review and projections

Comments are durable threaded application records associated with stable engine anchors and document/section identity. Thread metadata includes authors, replies, resolved/reopened state and timestamps; a comment is not a formatting span. Deleted anchors retain an explicit orphan state/context sufficient for review. Do not move them to unrelated surviving text just because a numeric offset remains valid.

Suggestions are first-class authored proposed changes, including insertion, deletion, replacement, formatting and supported structural changes. Model pending/accepted/rejected/superseded/conflicted states with idempotent transitions and captured basis/target identities. Deleted proposed content and resources stay recoverable for reject/view/compare until their retention obligations end. Rendering a strike-through is not a suggestion implementation.

Conceptually separate accepted document content, durable pending review changes, and the local native composition overlay. Review views (accepted/original, proposed/final, and markup) are projections of the same canonical bundle. Each editable projected position needs a versioned mapping to accepted or proposal-owned identities; offsets from different review views cannot be used interchangeably. This is an additional S0/C0 mapping requirement, not an already implemented composition order.

Accept/reject operations validate current targets and permission, update content/review state/resources atomically and enter history. Overlapping suggestions, two concurrent acceptance decisions, edits to proposed inserted text, comments on deleted/proposed content and structural conflicts need named C0 fixtures. C0 chooses the deterministic conflict policy; unsupported live cases remain explicit, not guessed. IME in suggestion mode preserves candidate attribution and one user gesture while lowering its settled effect into the review model.

App roles include owner/editor/commenter/viewer with separately defined suggesting capability. Enforce rights at the service admission boundary and reflect them in UI; hiding a button is not authorization. Offline changes after revocation remain a recoverable local draft or are explicitly refused on sync; they cannot silently become shared accepted state. A mention or comment does not grant document access.

### 7. Version history, persistence and retention

Named and automatic versions refer to immutable revision manifests containing the relevant document/section roots, review/annotation state and resource hashes. They are not the same as the current process Undo stack or the native session log. The exact version treatment of workspace metadata versus document comments must be selected at S0 and exposed consistently in compare/restore.

Restore creates a new authorized head revision based on a selected version; it never rewinds monotonic IDs/clocks or erases the audit trail. In a collaborative session it is an explicit restore/replacement operation against an accepted basis, with conflict handling. Offer a copy/branch when overwriting the shared head would lose newer work; no silent whole-root overwrite of peers.

Recovery separates local durable state, shared acknowledged state, unacknowledged outbox and provisional draft. “Saved locally” and “Synced” are distinct statuses. Reconnect can retransmit the same OperationIds without duplicates. A remote acknowledgement must identify a durable accepted basis; if transmission fails, retain local recovery material and display the pending state.

Resources and deleted identities may be pinned by document, review, version, local Undo or offline replica leases. Budget each owner and count shared payloads once. Do not keep unbounded tombstones forever, or garbage-collect them while a supported replica still needs them. C0 must define compaction/frontier resync and handling of an old offline client whose basis expired, including recovery of unsent user work. Storage/media/server backups have separate operational retention policies; UI history trimming cannot delete the only recovery copy.

### 8. Schema and output integration

S0 must specify a versioned document package for sections/tabs and auxiliary header/footer/footnote flows, while retaining stable references and the single active native-flow rule. Proposed page/section breaks are semantic directives; automatic page fragments are derived. Rich merged cells need explicit grid spans and a migration from the existing unmerged-cell schema. No merging by concatenating arbitrary projected strings and discarding cell identity.

DOCX/ODT and HTML import lower into the declared schema with a conversion report. Preserve supported comments/suggestions/section properties. Unsupported extensions must be retained opaquely where safe or rejected/reported; do not call dropping unknown nodes forward compatibility. Selecting a serialization library does not establish editor semantics or print fidelity.

Page-plan keys additionally include section/page profile, review-view revision, selected font/resource identities and resolved generated fields. Repeated headers/table headers reference their canonical owner and visual occurrence. Remote/review changes invalidate affected page plans; stale jobs cannot publish geometry for the new state. Export captures document version, selected sections, review visibility and font/resource profile. Live collaborator movement is not an export mutation.

### 9. Release gates and handoff

New integration gates: S0 schema/migration, C0 algorithm/contract feasibility before stable APIs, C1 multiuser/offline runtime qualification, R0 review/version behavior and I0 interchange. U0–U5 are required Urdu gates. All are initially NOT RUN. Existing G0–G8, GL and GP0–GP3 remain applicable; none is passed by creating these documents.

Before public API freeze, finalize the experimental concepts above into explicit types, conflict policies, retention and serialization with executable examples. This is a bounded decision milestone, not approval to invent unspecified algorithms inside production code. Before full-product claims, complete the feature catalogue and native/platform matrix.

Do now: finish current input/lifecycle repair, prepare the Urdu corpus and integration fixtures, then run C0/S0 and GP0. Do not replace working editor code, run a broad feature implementation or migrate existing user documents merely because this replan exists.
