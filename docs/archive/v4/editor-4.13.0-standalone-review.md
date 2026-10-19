# Compose Multiplatform Editor Standalone Specification 4.13.0

This package contains the complete current normative plan, current integrated-model evidence, qualification ledger, provenance and independent-review prompt. No preceding conversation or earlier plan is required to review the design. The previous 4.12.0 package remains an archived snapshot.

The shared core remains a Kotlin document engine with native Compose rendering/input adapters and a semantic DOM Web surface. The new reference model executes input admission, structural composition and the Undo ledger together behind one public dispatcher and one publication owner. Section 4.9 states the precise model boundary; it is not a production implementation or native qualification claim.

This document is self-contained for design review, not a bundled source repository. To reproduce the tests, use the named source directory and commands in the report. All production phase gates remain unqualified. No implementation phase is authorized.

# Complete normative specification

# Compose Multiplatform document editor — implementation specification
Revision: 4.13.0, 15 October 2026. Status: integrated public-boundary reference model validated for the recorded traces. Production Kotlin, full command coverage and real-platform qualification remain outstanding. No production implementation is authorized.

This replaces v1 as the implementation baseline. The owner has withdrawn the novelty requirement. This is an engineering plan using established techniques, not a breakthrough claim. Earlier plans and research remain evidence, not competing specifications. MUST means an acceptance requirement; a proposal or unrun test is never evidence that a requirement passes.

## 1. Chosen architecture and reasons

Build a headless Kotlin document engine with one transaction owner, independent attributed history, and platform adapters. Render native surfaces with Compose text layout/drawing on Android, iOS and Desktop. Use a semantic DOM surface with ProseMirror input/view machinery on Web, synchronously governed by the Kotlin engine. No native WebViews. No custom bidi index, new shaper, CRDT, or conditional-layout graph is required.

The editor engine factors document state into in-memory committed storage $D_c$ (governed by the hierarchical node schema and persistent B+ tree) and a piecewise segment-mapped provisional overlay $\mathcal{O}$ during active composition. Visible text and attributes $D_v$ are the deterministic projection $\Pi(D_c, \mathcal{O})$. Native buffers, ProseMirror state, layout and accessibility are downstream projections. Immutable old snapshots are history/recovery data, not competing editable documents.

The core does not depend on `TextFieldState`, `TrackedRange`, `ExpandPolicy`, Compose style objects, DOM nodes or ProseMirror positions. Compose's tracked styles remain useful for ordinary fields and as comparison fixtures. They do not own this editor's persistent styles, bookmarks, undo or composition lifecycle.

### 1.1 Fixed product scope

- Continuous rich documents: paragraphs, headings, quotes, nested lists, code blocks, unmerged rectangular tables, inline/block atoms, images and links.
- One local editing session and one active native input connection per editor. Multiple editor instances remain independent.
- Cross-block selection, directional selections, explicit cell selections, rich clipboard, crash recovery and editable accessibility.
- Shared document/command semantics. Native font rasterization, line breaks and input conventions may differ. Pixel-identical layout across platforms is not promised.
- Standard capacity fixture: 1,000,000 UTF-16 units across 10,000 text blocks. Large single paragraphs are a separate qualification workload, not covered by block virtualization.
- Deferred: collaboration, pagination/print fidelity, merged/nested tables, multiple simultaneous carets, persistent undo across application restarts, and toolbar formatting that modifies an actively composing candidate before settlement.
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
    val sequence: Long, val adapterSessionToken: AdapterSessionToken?)
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

Pin one published Unicode data version with hashes in Phase 0; do not retain v1's unverified Unicode 18.0 release assertion. Run its official grapheme-break and word-break data. Segmentation crosses piece boundaries and scans until state and unchanged suffix converge; no fixed lookaround bound. Treat isolated UTF-16 units as individual replacement-class navigation items without modifying storage.

UI user commands snap/expand to legal grapheme stops; protocol selections remain exact native ranges. The drawable caret may be projected to a legal glyph edge while preserving raw input selection. Programmatic native selection echoes must not overwrite the logical selection merely because drawing used a nearby legal edge. Bidi visual movement uses layout caret stops, not offset ±1.

Safety model checking covers two epochs, at least three callbacks, nested batch notifications, pending external commands, partial commit, failure, disposal and reentrant queries. Liveness is conditional on actual adapter completion; elapsed time is never a proof of native settlement.

## 4. Input, structural composition, settlement and history contracts

Revision 4.12.0 replaces the previous section 4 in full. It incorporates useful ideas from both external review rounds and the existing executable coordinator, but does not import their contradictory prose or claim that synthetic adapter tokens prove native provenance. The algorithms below are normative. Reference-model boundaries and experiments are stated in §9.

### 4.1 One executor, publication and immutable requests

Every public mutation (native input, application command, selection, Undo/Redo, focus change, connection replacement and disposal) enters one owner-thread request executor. Capture immutable data, never caller-owned closures. Public reads and will-change notifications see the last published snapshot, never a working tree. Input, structural compilation, history operations and preexisting settlement-command drainage stage in a private transaction. Publish one coherent version bundle and receipt, then notify did-change observers. A thrown internal failure restores the complete working preimage, outcomes, receipts and history; externally received reentrant requests remain queued for later execution. Deterministic command rejection is an outcome, not an exception.

The bundle includes canonical document, measured hierarchy and text indexes, attribute roots, overlay, history/session state, anchor maps, selection and typing intent. DocumentRevision increments once iff the FINAL visible semantic document changes (text, attributes, structure or resources). SessionRevision increments once per successful external publication, including metadata-only and no-op publications. Rejection, duplicate receipt and deferred transport acknowledgement increment neither. Receipt installation precedes did-change notification so a duplicate received there cannot execute twice. Validate connection/generation/session identity before consulting duplicate receipts.

Process at most 64 external queued requests per owner turn; schedule a macrotask continuation whenever work remains. Do not wait for further user input. Reentrant requests use the same validation pipeline as immediate requests. Distinguish the external request queue from the session's pending application-command queue.

An explicit range carries its published basis revision, exact content identities and boundary associations. Map those identities to execution time, require contiguous native replacement content, reject lost/fragmented content rather than take its convex hull, and validate native selection in the proposed post-edit coordinate space. Captured application tracked ranges may resolve into surviving disjoint intervals. Requests never combine published revision numbers with staged identities.

An input result is `Accepted(receipt) | Deferred(ticket) | Rejected(reason) | Duplicate(receipt) | DuplicateReceiptExpired | Fault(recoveryRecord)`. Deferred means transport acceptance only; every ticket later receives exactly one terminal outcome. Rejections include stale epoch/generation/session, failed dependency, invalid range/selection, lost/fragmented target, protected/read-only content and unsupported structural projection.

### 4.2 Provenance, admission reservations and target descriptors

`InputStamp = (connectionEpoch, inputGeneration, sequence, adapterSessionToken?)`. Tokens are opaque and non-reused within an epoch/generation; registry keys include all three identity fields. The adapter, not arbitrary application payload, establishes verified provenance. A stamp assigned at adapter receipt proves ordering after receipt; it does NOT prove when an unstamped OS callback originated.

There are three immutable target descriptors:

1. `ExplicitBasisRange(basis, ids, boundaryAnchors)`: map the exact requested range as in §4.1.
2. `Continuation(epoch, generation, sessionToken, initiatingTicket)`: for an identified composition, resolve at execution to its current marked interval; if nonterminally unmarked, use that session's tracked unmarked extent. Never fall back to the global caret. Reject terminated/failed/mismatched sessions.
3. `NewAtBasisSelection(basis, selectionIdentities, selectionGeneration)`: for genuinely new input, use the published captured selection. A later explicit relocation invalidates it unless an explicitly identified dependency/basis permits remapping. It is not a continuation merely because another composition happens to be active when it executes.

The admission registry records `(token -> engineSessionId, initiatingTicket, Reserved/Active/Failed/Terminated)` before an initiating request executes. This is transport bookkeeping, not publication of a working document. Reentrant callbacks with the same verified token can depend on that reservation. Any failed initiating request marks the reservation Failed and its dependent continuations reject; none silently become fresh replacements. Tombstones survive until the connection/generation fence proves older deliveries cannot recur. The reservation state and request identity are immutable to callers.

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

Initial replacement of an explicit noncollapsed user selection in one flow admits removable selected atoms, whole selected tables, list/quote transitions and code/prose endpoints. Selected structure is provisional until commitment; it is never deleted as a separate preparatory history group. Locked/read-only content remains protected. `Selection.Cells` is a distinct semantic command: clear selected cells, then insert in the top-left cell; cells never silently merge. Later reconversion/expansion without an explicit selected-structure gesture may extend over same-parent compatible prose/code text domains, but cannot acquire previously unselected objects or container transitions. The adapter must distinguish initial selection from arbitrary reconversion rather than setting a caller-trusted boolean.

The selected structural replacement compiler uses the following rules, shared by DeleteSelection, ReplaceSelection and IME overlay materialization:

1. Resolve the interval against its immutable tagged provenance. Retained units keep exact identities and properties. Newly synthesized candidate units are TextUnits. Retained boundary units define result block heads; removing a boundary joins into the preceding surviving head. The first block is represented by a versioned head record, including an empty landing block when all content is removed.
2. For an explicitly selected text-to-text replacement, the left head's ID/type/properties win even across code/prose or different containers. Preserve right suffix text/attributes. This is a deliberate change from the old same-parent/join-class rejection. Unselected native expansion keeps the stricter domain rules above.
3. Retained block heads retain original ancestry. Remove consumed leaf heads and prune containers with no remaining children bottom-up. Do not lift unselected descendants out of surviving ancestors or reorder them to repair a guessed grammar.
4. A narrow ordinary list-join rule applies when the surviving left head and a consumed right head are DIRECT children of distinct sibling ListItems under the same List. Move the right item's surviving trailing children into the left item in original order and remove the empty right item. All content between the two selected endpoints has already been consumed; no surviving intervening sibling can be jumped over. Do not apply this rule to ancestor/descendant items or across different lists.
5. Otherwise leave all surviving trailing blocks in their original containers. Equal-item, ancestor-item, descendant-item, unrelated-list, list-to-outside, outside-to-list and quote cases all follow this same rule; no exhaustive list of special movement cases is needed. Global preorder of unselected leaf content is invariant. Moving direct-sibling tails under rule 4 preserves that order.
6. The schema deliberately permits `ListItem ::= Block+`, including an item whose first surviving child is a quote, object or nested list. Empty items are removed; a flow always retains a legal editable landing text block. A headless item does not gain a synthetic paragraph merely to repair grammar. List marker layout and insertion-gap accessibility must handle this schema. Explicit Enter at the item's leading gap creates a paragraph; Tab/indent/outdent operates on the item, not an assumed first text child. This is a product/schema choice, not an optimization.
7. A group containing a retained block object must remain that object's leaf, with its original payload. A raw edit that deletes only a block-object seam while preserving an object in a text group has no exact legal projection and rejects `UnsupportedStructuralProjection` before publication. Semantic Backspace/Delete at an object boundary selects/deletes the object or leaves its block boundary according to the explicit node-selection command; it must not send an invalid scalar text deletion. Selecting and typing over the object itself, including surrounding paragraphs, IS supported. A host that insists on unrepresentable seam rewrites for ordinary supported actions must fail qualification or obtain a specified alternative adapter mapping; this is a visible remaining integration risk, not permission to drop typed input.
8. Validate unique IDs, schema, preserved unselected order and `project(resultTree) == exact admitted candidate text` before publication. Reject atomically on failure. A model may reconstruct whole trees for clarity; production changes only affected persistent paths. Normalization is terminating: delete empty containers and move each surviving sibling tail at most once. No rule recursively invents repair nodes.

Two endpoint conversions are explicit: replacing only a block object's content while retaining its surrounding boundaries converts that selected leaf to a Paragraph at the same NodeId, removing the object payload from the current version (history still pins it). Node identity is stable across this explicit type conversion, as with a heading-to-paragraph conversion. An unmatched literal U+FFFC does not retain the old object. If a selected join's left head is CodeBlock but the surviving inline content includes an InlineAtom, convert that head to Paragraph at the same ID and reset its block properties to paragraph defaults rather than lose the unselected atom or put it in an invalid code block. Ordinary text-only code joins retain code type/properties. Both conversions are structural history changes, restored exactly on cancel/Undo. Retained boundary IDs are provenance identities; a view map relates each retained boundary to its resulting head record rather than assuming the head's type/payload is immutable forever.

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

Cancellation with no accepted semantic delta restores pre-session selection/direction. After accepted partial effects, collapse to the accepted prefix end within the resulting flow. Preserve current valid selection when already unmarked and there is no residual candidate. History retains exact before/after selection and structural/anchor records for Undo/Redo.

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

Composition formatting targets the semantic constituent islands plus accepted session portions, never the convex hull and never untouched bridged text. Outward grapheme closure occurs per text block. A retained structural BoundaryUnit never receives an inline attribute patch. A deleted target gets an explicit cancelled result. Empty resulting candidates affect typing intent only unless accepted session text remains. Unrelated explicit tracked ranges map surviving token intervals and drain on composition cancellation rather than become trapped.

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

The separate admission, structural and Undo reference contracts are now lowered into one executable owner in `validation/codex/run-20261015-17/integrated-model.mjs`. `dispatch(immutableRequest)` is the only edit admission path used by its integrated tests. `snapshot()` exposes a deeply immutable published version; `receipt(ticket)` exposes transport outcomes. `flush()` and an injected owner scheduler drive queued execution and bounded continuation. Native requests use synthetic qualified-adapter stamps in this model. No test-generated stamp proves a real OS callback's provenance.

**One authority, private structural working state.** The structural segment/tree reducer is a private worker under the outer owner. Its legacy history is never consulted as authoritative history, and its intermediate internal snapshots are never passed to external observers. Application formatting and Undo enter the owner's action ledger rather than the worker's old command handlers. History entries retain actual attributed structural before/after roots and selections, not string stand-ins or caller-supplied transform closures. This integration closes the previous run-16 separation for the operations and traces enumerated below.

**Request capture and execution.** At capture, freeze payloads and bind explicit ranges to the published basis. Key wire receipts by `(epoch,generation,sequence)` and session reservations by `(epoch,generation,adapterSessionToken)`. Repeating a pending wire request returns its original deferred ticket; repeating an executed identical request returns its recorded outcome. A reused wire sequence with different payload rejects SequenceCollision. Stale epoch/generation rejection precedes cache lookup. Once executed, successful native sequences must advance within the connection/generation. Failed requests retain a rejection receipt; retrying a repaired payload needs a fresh sequence, not mutation of the old request.

An omitted-range continuation binds to the reserved/active session and resolves its current marked or tracked-unmarked extent at execution. An explicit range maps exact published identities; fragmented ranges reject. Native post-edit selection accompanying that replacement maps through a synthetic proposed-post-edit identity space, so inserting earlier text does not reinterpret a later callback's selection in the wrong frame. `AfterTicket` uses the actual accepted publication stored with that receipt; failure of the dependency rejects. Application relocation increments both selection generation and input generation at execution and requires a qualified host fence before later native input; abort is its own FIFO barrier. Earlier queued callbacks are never retroactively assigned the new generation.

**One range-admission policy for every callback.** A repeated start/mark-existing callback in an identified active session retargets to its exact admitted interval; it cannot acknowledge a new range while retaining the old one. A preedit, commit or native removal whose explicit range differs from the active/tracked interval must pass the same structural-retarget classifier as a named retarget event before invoking the structural worker. Otherwise a private worker's implicit retarget could bypass protected-object/container rules. This preflight uses the pre-edit range; the callback's native post-edit selection is validated separately in its post-edit space, not passed as a pre-retarget selection. Final review reproduced both failures before repairing this shared path.

Pin the exact structural unit identities admitted by the initial selected-range gesture in the session preimage. A later retarget may retain or narrow over those already-admitted atoms/boundaries; it must reject acquiring a previously unselected protected object/container transition. Do not reject every atom indiscriminately: that incorrectly blocks reconversion of an object already included in the user's selection. These permissions are identity-based, do not authorize a new object with the same projected U+FFFC, and roll back with the session state. A fresh session establishes its own set.

**Full transaction preimage.** Before staging, retain the structural worker and its complete state, published snapshot, active composition and S0, registry statuses, epoch/generation, host-fence state, history/redo, pending commands/outcomes, selection generation, native sequence ledger, and rollback-sensitive history/fence counters. Ticket/session identity allocation is monotonic; externally admitted request identities are not recycled on rollback.

On failure restore that complete preimage. Keep independently admitted reentrant requests in the external queue, including new reservations created during the failed turn. A failed initiating session reservation becomes Failed, and its continuations reject InitiatingSessionFailed. If an already-active session's terminal attempt fails, its prior active state and pending commands survive, and a valid reentrant continuation can execute afterward. Do not accidentally restore the document while keeping staged formatting history or command outcomes.

**Atomic settlement pipeline.** First apply the exact terminal native candidate to working structural state. Then derive current semantic composition target identities (constituent islands and accepted portions, excluding bridge text). Accept or cancel provisional effects according to §4.5, materialize the resulting canonical tree and seed the LIFO action ledger. Drain queued formatting and Undo against that tree. The final before/after semantic comparison assigns at most one DocumentRevision increment and exactly one successful SessionRevision increment. Publish only after the entire batch succeeds; receipt installation precedes did-change observers. Observers never see the intermediate native candidate, temporary Bold result or intermediate Undo root as a separately accepted publication.

Application observers are notifications, not input validators. Exceptions in will-change/did-change observers are isolated and may be diagnosed; they do not veto an otherwise valid edit or roll back an accepted publication. This fixes the actual observer-veto defect found during integration. Tests inspect observations outside callbacks so swallowed observer assertions cannot accidentally produce a passing test.

**Structural provenance after settlement.** Rebase the private structural worker on the resulting accepted canonical hierarchy after settlement, formatting and history restoration. Its next session must not reconstruct a joined list from the original pre-join container tree. Retained text/atom identities remain stable. A new session gets an owner-wide unique engineSessionId derived from its admission identity; new provisional unit IDs use that namespace. Recreating a private worker must not reset identity allocation and create duplicate token IDs in an existing document. Undo restores the historical canonical root; subsequent editing begins from that restored version.

**Semantic equality.** Compare canonical document semantics: structural IDs, types, properties, resources and raw text/attributes. Internal text-token IDs, session-origin tags and map-key insertion order are not visible semantic changes. Canonicalize unordered property keys. Token identity still controls range mapping and must be unique in the visible tape, but metadata-only identity changes must not invent a DocumentRevision or history group. Structural node/object IDs remain semantic and cannot be ignored.

**Cancellation and history.** Every Undo passes through the same owner, including Undos queued while composition is active. A cancelled formatting request contributes its one-use action slot; an aborted dirty composition contributes its one-use composition credit if no accepted group survives. Partial commits that survive cancellation create a real Composition group, which the next applicable Undo can remove. The current durable stack-top ID must equal the action slot before restoration. Redo contains only actually undone groups. New semantic formatting after an Undo clears Redo normally. Selection-only publications do not create document history. Connection turnover finalizes accepted visible structure once, cancels every old pending command, and advances identity only on successful publication.

**Executed integrated surface.** The current model exercises normalized start/preedit, exact retarget, partial commit, nonterminal unmark/resumption, terminal commit, evidence-checked completion, native cancellation/removal, application abort, explicit selection, noncollapsed/semantic-island formatting, Undo/Redo, connection replacement/disposal paths, duplicate admission, and reentrant scheduling. All fixtures in the integrated test file enter through `dispatch`; they do not separately call the old string admission or string history model and compare disconnected outputs.

The model is still deliberately scoped: one connected flow per owner (whole table payloads are atoms), full-copy array/tree storage, selected primitive attribute patches, and Intl.Segmenter for its grapheme fixtures. It is not the complete product command catalog, cell-flow coordination, typing-intent UI, registered-anchor implementation, full Unicode corpus, native adapter, accessibility host, browser DOM or durable journal. Those existing gates remain. Passing this integrated reference subset does not pass production G3 or authorize implementation. Production must preserve the same public-boundary contracts while lowering storage and platform bridges.

**Required public-boundary regression traces.** Keep queued cross-list composition with original-selection capture; failed initiator plus dependent callbacks; partial commit/cancel with formatting and multiple Undos; clean mark-existing plus changed terminal commit without intermediate preedit; failed terminal drainage with retry; reentrant continuation after rollback; exact native removal; explicit after-ticket bases across abort/relocation; stale and fragmented proposals; unchanged candidate revision behavior; nonterminal unmark with obsolete completion evidence; old duplicate after epoch turnover; repeated sessions with unique tokens and rebased ancestry; grapheme-joining format/Undo; selected-atom detours; one-publication settlement; turnover rollback; observer failure isolation; and queue continuation beyond the per-turn budget.


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

Maintain a measured block-height tree with estimated offscreen heights. Total scroll extent may be provisional until measured; label that internal state and preserve an anchored model position as estimates change. Keep active text, viewport neighborhood and selection endpoints realized. Exact geometry requests measure their required blocks or return an explicit unavailable result if the platform permits it. Never call an estimate exact. Whole-document exact print geometry is excluded.

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
3. `getTextLocation(offset)`: offset is relative to current composed text when active composition exists (supporting `TextHitInfo` leading/trailing semantics and returning a 2px-wide caret rectangle `[screenX, screenY, 2, lineHeight]`); when no composition is active, offset is relative to committed text (and defaults to the selection caret when null).
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

During composition, protect the outward grapheme closure of the marked interval AND the actual composing DOM nodes/ancestors whose mutation would replace those nodes. Character intervals alone do not establish safe DOM writes. Keep a token/version-bound delayed-correction ledger for intersecting changes, including graphemes crossing marked boundaries. Maintain actual surface geometry and AT projection metadata for that temporarily divergent region. Recompute after every accepted edit; when the marked interval becomes null, retain the recorded protected node set until verified settlement. At settlement, derive corrections from the current canonical snapshot rather than replay stale queued mark operations, then clear the ledger. If minimal corrections cannot preserve the native composing nodes in the pinned view version, the Web gate fails. This DOM-write capability requires real-browser/IME qualification; the headless model does not implement it.

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

Initial history budget: 1,000 groups or 64 MiB of retained payload beyond the current root, whichever is reached first. Count unique retained slabs/subtrees/resources, not shallow object sizes. Evict whole oldest settled groups. The active composition baseline is pinned; report its exceptional memory separately and never drop required preimages mid-session. Oversize imports are bounded before admission; native accepted input is never silently truncated to meet a budget.

Canonical format `editor-document/1` is independent of tree shape, Compose and ProseMirror. Store IDs, schema, ordered nodes, resources, raw attributes and text. Encode text as UTF-16LE byte chunks with explicit unit counts (base64 in the JSON interchange form) so isolated surrogates survive every runtime. Validate endianness, lengths, ID uniqueness and resource references. Canonical ordering allows exact round-trip tests. Human-readable plain-text export is separate.

Native checkpoints: write new framed/checksummed file, flush as required by the target filesystem, atomic replace, then acknowledge. Journal records have transaction IDs, previous revision, length, checksum and committed framing; duplicate replay is idempotent. Web uses IndexedDB transactions. A saved acknowledgement is only for durable completion; autosave may coalesce queued states while preserving ordered acknowledgement semantics.

Active-composition recovery stores latest accepted raw text/attributes and structural snapshot, plus enough metadata to identify provisional state. Recovery restores that visible content as ordinary text with no old connection/marked range. It does not restore an abandoned before-composition snapshot or resurrect cancelled pending commands. Session undo does not persist initially. Log text is local and opt-in; routine diagnostics contain counters, versions and operation categories.

## 8. Phased implementation blueprint and acceptance gates

Build these phases in order. A failed gate blocks the affected layer, while independent core work may continue. No gate may be marked passed because the common code compiles, an old fixture prints “pass,” or a simulator substitutes for a required physical-device interaction.

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

Run-17 now executes integrated reference replay of input reservations, structural replacement and the action ledger through one dispatcher, including post-abort fences, changed terminal commits, rollback and reentry. G3 still requires the production Kotlin coordinator, complete required trace catalog and the stated invariant/Unicode checks; the reference subset is not a production gate pass.

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

Gate G8: all correctness gates plus measured target matrix. If pathological paragraphs fail, report the failed workload; either repair/replace the backend and rerun correctness, or obtain an explicit product-scope decision. Do not announce unrestricted large-paragraph performance. Backend forks and thread offloading are experiments, not assumed solutions. A release can be qualified per platform; do not label all four production-ready until all four pass their own gates.

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

### 9.5 Revision 4.13.0 integrated model and evidence boundaries

Run-17 replaces the previous disconnected validation path for the tested operations. One executable owner captures native/application requests, validates identities and coordinate bases, stages the tagged structural reducer, runs the full-root LIFO Undo ledger, and publishes a coherent immutable snapshot and receipt. The structural worker's old internal history is not used as authoritative history. The new public-boundary tests do not invoke the old standalone string admission/history models as independent substitutes for integration.

Source: `validation/codex/run-20261015-17/integrated-model.mjs`. Reproduction: `node validation/codex/run-20261015-17/verify.mjs`. Results and source hashes: `validation/codex/run-20261015-17/results.json`. The executable owner is a validation artifact, not a production editor module. Section 4.9 defines its integration boundary and scope.

Integrated tests cover queued cross-container composition, pending-command/cancel/partial-commit interactions, exact native removal, changed terminal commits, reentrant callbacks, failure rollback/retry, old-generation delivery, immutable basis capture, fragmented targets, unique session identities, canonical hierarchy rebasing, Undo/Redo and bounded continuation. Expected raw edits are independently calculated from fixture slices; original-tree restoration and structural/order/history invariants supplement those checks. Mutation checks require passing controls and assertion failures, not unrelated crashes. No exhaustive protocol proof is claimed.

Integration found and repaired four defects: observer exceptions could veto valid input; a repeated composition-start callback could acknowledge a new range without changing the mark; an explicit-range preedit could reach a private implicit-retarget path that bypassed protected-structure admission; and the inherited retarget classifier rejected even an atom already admitted by the initial user selection. Notifications are now isolated, changed ranges share one structural-retarget validation path, and initial structural permissions are pinned by exact identities. Initial test development also corrected explicit fixture offsets: TAIL is [6,10) in the original `ab\ncd\nTAIL`, but [5,9) after provisional `aXYd\nTAIL`. The model was not modified to accept the incorrect offsets. Preserved failure reports distinguish those fixture mistakes from actual defects.

The combined owner also makes previously implicit integration requirements concrete: epoch/generation/session registry keys; actual accepted AfterTicket bases; complete rollback including history/outcomes; survival of reentrant requests; fresh session token namespaces; rebasing structural provenance after settlement; canonical semantic comparison excluding internal token metadata; and receipt installation before did-change reentry. These are documented integration choices, not falsely reported as independently discovered failures where no failing baseline was executed.

The prior 4.12.0 standalone, run-16 and earlier runs remain preserved. Their counts are historical and are not added to current execution totals. The new report scopes current counts to the integrated tests that actually ran.

### 9.6 Required experiments and remaining qualifications

1. G0/G5: actual adapter provenance after forced cancellation/relocation, reservation timing under synchronous reentry, and qualification of safe fences. A locally minted token cannot prove the origin of a late OS callback. Preserve ambiguous input and block release if normal typing cannot be made reliable.
2. G0/G5 Web: composition over selected objects/containers, actual DOM-node preservation, delayed mark corrections crossing grapheme/ancestor boundaries, and candidate/projection synchronization.
3. G2/G3: port the now-integrated reference owner to the production Kotlin coordinator, extend it to the full product command/anchor/cell-flow/typing-intent catalog, and replay the required corpus with platform-specific fake adapters. Run-17 closes the separate-model integration gap for its recorded reference subset; it does not qualify the full production implementation.
4. G2/G6: list items beginning with nested lists/objects, explicit insertion gaps, marker layout, AT navigation and import/export round trips. This schema change must remain visible to product review.
5. G0/G5: native object-seam proposals, native marked-text query coordinate conventions, and exact initial user-selection provenance. Whole selected objects are supported; an impossible retained-object inline projection is rejected, not silently flattened.
6. G1/G8: depth-aware hierarchical indexes, fragmentation, retained-root memory, seam concat/split and full paragraph/layout latency. No unmeasured optimization or product cap is assumed.
7. G7 and existing U1-U7: physical durability, full Unicode data, native IMEs, screen readers and all platform release gates remain unqualified.

The reviewed counterexamples have chosen semantics and integrated executable checks for the recorded public-boundary subset. This is a reviewable specification baseline, not proof that every interaction has been explored. Further empirical failures require a concrete counterexample and a versioned repair. No production implementation is authorized by this document.


# Current integrated validation report

# Revision 4.13.0 — integrated executable validation

Author: Codex. Run: `validation/codex/run-20261015-17`. Executed 2026-10-15T16:03:44.776Z.
Environment: Node v24.20.0, win32 x64.

**The admission, structural composition and Undo ledger now execute together through one public request dispatcher and one outer publication transaction. All 6 integrated test groups pass. No production implementation was started.**

## Implementation of the validation model

`IntegratedModel.dispatch()` captures immutable native/application requests, reserves identified sessions, maps published-basis targets and proposed post-edit selections, and queues reentrant work. It owns the connection/generation, published snapshots, receipts, history/redo and command outcomes. A private tagged-tree/segment worker stages composition; its legacy history is ignored. Settlement drains the real-root action ledger before one final publication. Full transaction rollback restores document, protocol and history state together while preserving independently queued external work.

This is actual combined execution, not a runner invoking three disconnected model suites. The model uses arrays/full tree copies and synthetic provenance stamps; it intentionally makes no production complexity, latency, native-host or Unicode-completeness claim.

## Executed checks

| Group | Current execution |
|---|---|
| Named integrated regressions | 35 cases through the public dispatcher. |
| Generated structural/command interactions | 255 traces with independent expected raw splice results and exact fixture-tree restoration. |
| Bounded queued-command exploration | 240 traces, up to 3 queued commands, across clean/dirty/partial states and commit/cancel. |
| Repeated sessions | 80 sequential sessions, seed 0x13579bd, 10 injected settlement failures/retries and 80 rejected late deliveries. |
| Behavioral mutations | 8 caught, with 8 passing controls; each failure is an assertion, not an unrelated tool/syntax crash. |
| Owner scheduler | 130 queued requests complete; the first turn executes 64 and schedules continuation. |

Elapsed test-run time: 6.458 seconds. This is test duration, not an editor performance benchmark. Reproduction command: `node validation/codex/run-20261015-17/verify.mjs`.

Named coverage includes cross-list replacement and tail preservation; failed initiator/dependent continuations; partial commitment plus cancelled formatting and multiple Undos; changed terminal commit without preedit; cancellation without double Undo; full rollback after staged formatting; published-basis reentry; surviving continuation after a failed terminal callback; generation barriers and AfterTicket selection bases; post-edit selection mapping; semantic no-op revisions; obsolete settlement evidence; connection turnover/disposal; duplicate receipt reentry; foreign-flow and object-seam rejection; atom detours; code/prose conversion; backward islands; subsequent-session hierarchy rebasing; observer isolation; host-fence rejection; fragmented target rejection; immutable payload capture; grapheme formatting/Undo; one-publication settlement; turnover rollback; and unrelated formatting after cancellation.

## What broke and was fixed

- **Actual integration defect:** will-change observer exceptions were initially treated as transaction failures and rejected valid native input. Notifications now have no admission authority. The before-repair observation is preserved in `integration-probes-before-repair.json`; the regression and observer-veto mutant distinguish the corrected behavior.
- **Repeated-start range defect:** a second composition-start request in an active session returned success without applying its new marked interval. It now follows the exact retarget path. The original [0,1) versus requested [2,3) failure is preserved in `final-review-before-repair.json`.
- **Protected-structure bypass:** explicit-range preedit could indirectly retarget through the private worker without the structural classifier, allowing it to consume an unselected inline atom. Changed explicit ranges on preedit/commit/native removal now share the guarded retarget path. The failed pre-repair assertion and behavior mutant are preserved alongside the repeated-start case.
- **Previously selected atom rejected:** the inherited structural classifier rejected any atom on retarget, including an atom admitted in the initial selection. Session-scoped structural identity permissions now distinguish retaining an already-selected atom from acquiring an unselected one, and are included in rollback state. The original failure is preserved in `selected-atom-before-repair.json`.
- **Test-fixture corrections:** the initial reentrant TAIL range omitted its last character, and a later post-preedit range incorrectly reused the original length. Corrected from the explicit strings: original TAIL [6,10), post-XY TAIL [5,9). The first failing runner output remains in a `failure-*.json` file. No implementation was altered to accept the invalid expectations.
- **Integration requirements implemented and exercised:** one owner-wide session namespace (no duplicate unit IDs across recreated workers), canonical hierarchy rebasing, full-root history rather than string transforms, complete outer rollback, pending external requests surviving rollback, stale-identity checks before duplicate lookup, canonical semantic equality, and receipt publication before notification reentry. These are integration changes, not invented claims of earlier failing tests.

## Final review and limits

The previously documented separation of admission, structural composition and the Undo model is closed for the operations exercised here. The current executable model can be used as a common validation baseline for those contracts. Source hashes, named cases, mutation evidence and environments are recorded in results.json.

Still unqualified: actual OS session provenance and completion fences; native/Web DOM interaction; accessibility; complete product command, typing-intent, registered-anchor and cell-flow coordination; the pinned full Unicode corpus; durable journal recovery; Kotlin persistent storage and allocator behavior; and production performance. The integrated model uses one connected flow and selected attribute patches. Ordinary typing coalescing and the complete application command catalog are not implemented in this reference slice. All production gates G0-G8 remain NOT RUN; this report neither advances phases nor authorizes production implementation.


# Qualification ledger

| Claim | Scope | Evidence | Status | Remaining requirement |
| --- | --- | --- | --- | --- |
| namedTests | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| generatedIntegration | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| ledgerExploration | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| repeatedSessions | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| integratedMutations | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| scheduling | One integrated reference dispatcher | run-20261015-17/results.json | PASS (SCOPED) | Reference operations/bounds only |
| G0 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G1 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G2 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G3 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G4 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G5 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G6 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G7 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| G8 | Production phase gate | plan-v4.md §8 | NOT RUN | No production implementation authorized |
| Admission + structural composition + Undo | Integrated reference public boundary | integrated-model.mjs; test-integrated.mjs | PASS (SCOPED) | Full product/Kotlin/native qualification remains |
| Native session provenance and fences | Real OS/browser | plan-v4.md §4.2 | NOT RUN | Synthetic stamps are not native evidence |
| Full command/cell-flow/anchor/typing-intent catalog | Whole editor | plan-v4.md §4.9 | NOT QUALIFIED | Integrated reference implements a stated subset |
| Historical run-16 | Separate earlier contracts | run-20261015-16/results.json | HISTORICAL; NOT RERUN | Do not add historical counts to current execution |

# Integrity and reproduction

From the workspace root, run `node validation/codex/run-20261015-17/verify.mjs`. The runner executes the integrated suites and writes results.json (and a failure archive if checks fail). It is not a read-only inspection command. Source hashes are embedded in results.json.

```json
{
  "plan-v4.md": "f421759db8e435b57e7156ceb03ddf1a8f662d1461fd6ec8c08f3a5381a546c3",
  "antigravity-validation-report.md": "318acb5ce8589a9a353a897c547f849974bab74ea2c9fdea9fb7f7cc6274822d",
  "antigravity-validation-matrix.csv": "d99a6ae1d6705e3f1f4fa5b9200ed2b6139c5e4c27fbeeb92c63cf49ef32018c",
  "validation/codex/run-20261015-17/results.json": "1cd826e1120437825d70bc115be69bd39ee06a05f07c376b96b893579f8c8bb3",
  "validation/codex/run-20261015-17/integration-contract.md": "d137c7fab4fe0be0210da8763c88a8fe0b20eb2e75599f27be16090383bba075"
}
```

# Independent review prompt

Review the attached Compose Multiplatform Editor Standalone Specification 4.13.0 independently. This is a read-only design review: do not implement production code or advance phases. Read all sections, including the integrated-owner contract in §4.9 and the scoped evidence report.

Assess whether admission, provisional structural replacement and history semantics compose correctly under queued and reentrant input, generation changes, partial commits, nonterminal unmark, terminal changes, cancellation, formatting and multiple Undos. Challenge exact coordinate bases, publication/rollback, identity lifetimes, hierarchy/provenance rebasing, semantic equality and surviving unrelated content. Assess the explicit ListItem Block+ schema and object-seam policies against the intended product.

The reference model now executes these tested components together. Do not repeat the old separate-model finding without checking the revised evidence. Conversely, synthetic stamps and bounded reference traces do not qualify native hosts, the full product command catalog, persistent Kotlin storage, accessibility or performance. Distinguish inspected, inferred and executed evidence.

For each material finding give section, severity, classification, concrete initial state/event sequence, expected versus specified behavior, smallest correction and a discriminating test. State unread material and unrun claims. Return a scoped readiness verdict and a finite closure checklist. Do not rubber-stamp, invent defects or silently reduce product scope.
