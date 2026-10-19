# Compose Multiplatform document editor — implementation specification
Revision: 4.9.5, 14 October 2026. Status: specification repair, composition subsystem architectural consolidation, contract defect resolution (Findings A–F from run-07, history/lifecycle Findings A–D from run-08, and target-mapping/admission Defects 1–4 from run-09), staged failure atomicity (§4.3), executable undo/redo (§4.4), exhaustive 85-cell contract completeness sweep (§4.5), and complete canonical model checker qualification (139,746 unique abstract states, 196,532 transitions at depth 4 with 0 invariant violations in run-20261014-09; Phase 0/1 gated pending plan resolution and native platform qualification; production implementation prohibited). Retains Candidate B's separated committed storage ($D_c$) and segment-mapped provisional overlay ($\mathcal{O}$), repairing contract violations identified during independent verification without introducing another architectural replacement. Resolves all four target-mapping and command-admission defects: disjoint rollback piece-mapped surviving range resolution over zero-delta opposing edits (Defect 1, §4.5); idle command execution and outcome tickets (Defect 2, §4.5); cross-epoch receipt isolation with composite keys and connection epoch fencing (Defect 3, §4.1, §4.5); and explicit ordered queue settlement policy on undo during unmodified composition with queued commands (Defect 4, §4.4). Enforces pinned UAX #29 fixture details (Unicode 17.0, CLDR 48.0, ICU 78.3, Node v24.20.0, hand-selected examples vs full conformance corpus); retains raw LF semantics without automated U+2028 conversion; 100-ms long-paragraph qualification target (p95 <= 100 ms, known high-risk/unmet); and synchronous Web ProseMirror admission. Test traces: `validation/antigravity/run-20261014-09/`.

This replaces v1 as the implementation baseline. The owner has withdrawn the novelty requirement. This is an engineering plan using established techniques, not a breakthrough claim. Earlier plans and research remain evidence, not competing specifications. MUST means an acceptance requirement; a proposal or unrun test is never evidence that a requirement passes.

## 1. Chosen architecture and reasons

Build a headless Kotlin document engine with one transaction owner, independent attributed history, and platform adapters. Render native surfaces with Compose text layout/drawing on Android, iOS and Desktop. Use a semantic DOM surface with ProseMirror input/view machinery on Web, synchronously governed by the Kotlin engine. No native WebViews. No custom bidi index, new shaper, CRDT, or conditional-layout graph is required.

The editor engine factors document state into durable committed storage $D_c$ (governed by the hierarchical node schema and persistent B+ tree) and a piecewise segment-mapped provisional overlay $\mathcal{O}$ during active composition. Visible text and attributes $D_v$ are the deterministic projection $\Pi(D_c, \mathcal{O})$. Native buffers, ProseMirror state, layout and accessibility are downstream projections. Immutable old snapshots are history/recovery data, not competing editable documents.

The core does not depend on `TextFieldState`, `TrackedRange`, `ExpandPolicy`, Compose style objects, DOM nodes or ProseMirror positions. Compose's tracked styles remain useful for ordinary fields and as comparison fixtures. They do not own this editor's persistent styles, bookmarks, undo or composition lifecycle.

### 1.1 Fixed product scope

- Continuous rich documents: paragraphs, headings, quotes, nested lists, code blocks, unmerged rectangular tables, inline/block atoms, images and links.
- One local editing session and one active native input connection per editor. Multiple editor instances remain independent.
- Cross-block selection, directional selections, explicit cell selections, rich clipboard, crash recovery and editable accessibility.
- Shared document/command semantics. Native font rasterization, line breaks and input conventions may differ. Pixel-identical layout across platforms is not promised.
- Standard capacity fixture: 1,000,000 UTF-16 units across 10,000 text blocks. Large single paragraphs are a separate qualification workload, not covered by block virtualization.
- Deferred: collaboration, pagination/print fidelity, merged/nested tables, multiple simultaneous carets, persistent undo across application restarts, and toolbar formatting that modifies an actively composing candidate before settlement.
- Whole-document/cross-structural-boundary IME reconversion is not promised. Native text replacements must satisfy the structural admission rules in §6. Ordinary application selection/replacement remains supported across structure.

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

`Document` contains a body `Flow`, resources, schema version and extension registry. A flow contains blocks. Paragraph/heading contains inline text and atoms. Code block contains literal text. Quote contains blocks. List contains list items; each item begins with a text block and may contain additional blocks/nested lists. Each table cell has an independent flow. A table is atomic in its parent flow. Every flow has at least one legal editable text block after structural normalization.

IDs are opaque 128-bit values serialized as hex strings. Splits retain the left ID and allocate the right ID; joins retain the left ID; moves retain IDs; copying allocates fresh IDs; undo restores original IDs. ID allocation is injected into commands so deterministic replay never depends on a new random value.

A `FlowId` distinguishes cell text from its surrounding document. Flattening text inside quotes/lists retains ancestry metadata. Cell flows are never implicitly joined.

```kotlin
data class DocumentRevision(val value: Long)
data class SessionRevision(val value: Long)
typealias Revision = DocumentRevision
data class InputStamp(val epoch: Long, val sequence: Long)
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
- `SessionRevision` monotonically advances on every published session event or acknowledged callback, ensuring observers can distinguish sequential session interactions without invalidating cached document layout frames.

Cross-flow ranges must become a cell/block selection. Direction belongs to anchor/focus and is not lost by sorting endpoints for editing. Registered anchors carry association and deletion status; raw offsets are never durable bookmarks. Revision and ID transfer to JS uses strings or exact integer types, never an imprecise `Number` conversion.

### 2.3 Canonical text and attributes

Use a persistent measured B+ tree per text block: maximum branch fanout 32, target leaf storage 1,024–4,096 UTF-16 units, split/merge occupancy 16–32 for non-root branches. Pieces reference immutable slabs with attribute-run metadata. A physical split avoids cutting a valid surrogate pair where possible; logical operations still retain native UTF-16 semantics. Aggregate UTF-16 lengths; validate arithmetic before overflow. Flow projection length is limited to `Int.MAX_VALUE - 1` for adapters; oversize import is rejected explicitly, never truncated.

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
// InputResult: Accepted(receipt), Duplicate(previousReceipt), DuplicateReceiptExpired, StaleEpoch,
// Unsupported(reason), Fault(recoveryRecord). Do not conflate these outcomes.
```

`EditorSnapshot` contains document root, current revision, selection, typing intent, raw input state and composition metadata. Layout is referenced by its own key, never implicitly declared current. A receipt includes before/after revisions, maps, changed nodes, projection delta, selection, history group, invalidation category and native acknowledgement requirements.

Expected normal edit cost is `O(log blocks + log blockText + inserted units + touched attribute runs + affected registered anchors)`. Register anchors in an indexed range structure; count their repair cost. No logarithmic claim covers all shaping, all annotations, giant pastes or Web ancestor-array updates.

### 2.5 Mapping algorithms

For insertion of length m at a: positions below a stay; above a shift by m; equal a stays for Before and shifts for After.

For replacement `[a,b)`, `a < b`, with m units: below a stays; a maps to a; interior positions map to a/`a+m` by association and return `deleted=true`; b maps to `a+m`; later positions shift by `m-(b-a)`. Maps compose in operation order. Endpoints carry separate `contentDeleted` diagnostics when their adjacent referenced content disappears.

Split at k maps right-side offsets to the new block minus k; exactly k follows association. Join maps the right block to `oldLeftLength + offset`. Move preserves internal points by node identity. Deleted nodes map to a precomputed surviving landing point; never leave a point in a removed flow.

Selection mapping is explicit: if both endpoints are consumed by one replacement, collapse to its designated landing point. Otherwise map them, restore the original selection direction, and validate scope/type. Undo restores recorded selections, not an attempted inversion of a lossy map. Registered-anchor restoration uses recorded anchor deltas when that handle has not been explicitly relocated since the history entry; otherwise use the normal inverse-operation map. Document annotations are part of the restored document.

### 2.6 Structural command compilation

- Enter splits paragraph; heading-end creates paragraph; heading-middle creates same-level heading. Shift+Enter inserts soft LF. Code-block Enter inserts literal LF.
- Nonempty list Enter splits item, moving trailing nested blocks with the tail item. Empty list item lifts one level, or becomes a paragraph at outermost level. Empty quote paragraph exits one quote level.
- Backspace at first list paragraph start lifts before joining. Ordinary same-parent compatible text blocks join, preserving left block properties. Paragraph/heading are compatible; code/non-code are not implicitly joined.
- `DeleteSelection`: remove selected inline extents, fully selected nodes/atoms, and selected structural separators where schema permits; retain unselected prefix/suffix and ancestry. Join surviving endpoints only when compatible and under the same parent after cleanup. Otherwise retain both containers and collapse to the start-side surviving text position. This is a semantic structural command, not promised to equal blind plaintext deletion across every container boundary.
- `ReplaceSelection` executes that deletion then inserts at its landing point in the same transaction. Reversed and forward ranges produce the same document. Textual paste LF creates blocks only under the paste policy; opaque native LF inside a text block is a soft break, not an inferred Enter command.
- Table cells do not merge implicitly. Delete cell selection clears cells and ensures one paragraph per cell. Typing replaces selection in the top-left cell after clearing. Tab advances cells; Tab at the last cell appends a row; Shift+Tab at the first exits before the table. Empty cells and boundary destinations are explicit.
- Post-command normalization removes empty list containers, preserves legal empty text blocks, and runs inside the same transaction. It must terminate; each cleanup rule reduces invalid structural nodes or establishes a missing required leaf once.

Rich-paste slices carry open ancestor depth and schema-typed nodes. The first implementation accepts fully closed block slices and inline slices. Partially open HTML structures are converted to closed supported blocks by the importer with a report. Do not leave slice-fitting semantics for adapters to invent.

## 3. Formal invariants and verification strategies

| ID | Required invariant | Primary verification |
|---|---|---|
| I01 | Only the owner reducer changes document semantics. | Mutation visibility/API tests; owner-thread assertions. |
| I02 | A callback publishes either one coherent snapshot or nothing. Failed transactions publish no document/history change or native text synchronization. Reentrant protocol reads may observe consistent staged state; required will/did notifications must be balanced even on failure. | Exception injection at every reducer stage. |
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

## 4. IME composition and selection state machines

### 4.1 Connection and callback lifecycle

Connection states: `Detached -> Opening -> Active -> Draining -> Closed`; any live state may enter `Faulted`. Opening binds a new monotonic epoch to the adapter instance and publishes one initial projection. The old adapter retains its old epoch even after replacement. Do not label delayed old events with whatever epoch happens to be current.

**Callback Epoch Admission (Finding F)**: Every native callback carrying an epoch stamp is subjected to strict admission gating: callbacks where `payload.epoch < coordinator.epoch` are rejected with `Rejected(StaleEpoch)`; callbacks where `payload.epoch > coordinator.epoch` are rejected with `Rejected(FutureEpoch)`. Only callbacks matching `payload.epoch == coordinator.epoch` are admitted to the transaction pipeline.

Every callback receives an increasing sequence number on entry. Explicit internal retransmission keeps the original stamp; a genuinely new native callback receives a new number even if its text is identical. Cache accepted receipts for retransmission within the epoch; retain the accepted high-water mark after cache eviction and return `DuplicateReceiptExpired`, not another mutation. Native APIs generally do not supply a document revision or deduplication ID. On connection turnover (`openNewConnection`), the coordinator evicts the `receiptCache` and resets the sequence counter, allowing the new connection instance to safely initialize its own sequence stamps from 0 without spurious collision or duplicate rejection.

**Turnover Persistence & History (Findings A & B)**: When `openNewConnection()` is invoked while an active composition is in progress, the coordinator flushes and commits the active provisional overlay to $D_c$. If the net document text or attributes differ from the pre-session snapshot (including any prior `partialCommit` operations), exactly 1 `Composition` history group is pushed to `undoStack` before initializing the new connection. Active provisional text is never discarded, and undo history is never lost across connection replacement.

Apply each callback synchronously through a 6-stage transactional pipeline (`beforeMutation`, `afterTextSplice`, `afterTargetMapping`, `afterHistoryPush`, `afterCommandDrain`, `beforeReceiptPublication`). `beginBatch`/`endBatch` adjust notification depth; they do not delay authoritative text updates across callbacks. Unbalanced batches are closed on connection disposal. One callback containing ordered suboperations uses one private transaction builder. Reentrancy policy: synchronous reads query the currently published snapshot without blocking; reentrant mutations queue in FIFO order and process sequentially after the active transaction completes; any unhandled exception or staged failure rolls back the staging transaction atomically to its complete pre-transaction state snapshot (`_cloneState()`), leaving the published state, document revision, and receipt cache unmodified. Adapter-specific will/did-change notifications must be placed at tested stages (§6).

### 4.2 Composition state, overlay architecture and ownership

The composition subsystem is built on **Candidate B: Separated Committed Storage + Segment-Mapped Provisional Overlay**, selected and verified in `validation/antigravity/run-20261014-05/`. The editor engine strictly decouples durable committed storage $D_c$ from provisional composition state.

#### Core State Representation
1. **Committed Document ($D_c$)**: Authoritative sequence of accepted, non-provisional attributed units stored in the engine's in-memory persistent B+ tree. Never contains provisional or uncommitted text. *Durability distinction*: In-memory IME-committed $D_c$ represents accepted document state within the active engine session; durable disk-flush persistence and journal logging remain decoupled operations governed by the persistence engine (§7) and do not block synchronous in-memory IME transitions.
2. **Provisional Overlay ($\mathcal{O}$)**: An active composition structure `ProvisionalOverlay(blockId, segments)` where each segment is:
   $$\text{OverlaySegment}(dcStart, dcEnd, units)$$
   - $dcStart, dcEnd$: The exact footprint in $D_c$ replaced by this segment. For pure insertions, $dcStart = dcEnd$. For deletions, $units = \emptyset$ with $dcStart < dcEnd$. For replacements, $dcStart < dcEnd$ and $units \neq \emptyset$.
   - $units$: Array of AttributedUnit representing the provisional characters and their transient attributes.
3. **Marked Window**: The native candidate window $[\text{relStart}, \text{relEnd})$ defined in 0-based coordinates relative to $\text{flatten}(\text{segments.units})$.
4. **Attribution Epoch**: Immutable baseline record $(\text{epochId}, \text{baseSegments}, \text{baseWindow}, B_{\text{epoch}}, C_{\text{raw}}, seed)$.
5. **Visible Projection ($\Pi(D_c, \mathcal{O})$)**:
   The visible document text and attributes $D_v$ are projected deterministically on demand:
   $$D_v = D_c[0..s_c) \mathbin{\Vert} \text{flatten}(\text{segments.units}) \mathbin{\Vert} D_c[e_c..\lvert D_c\rvert)$$
   where $s_c = \text{segments}[0].dcStart$ and $e_c = \text{segments}[\text{last}].dcEnd$. When $\mathcal{O} = \text{null}$, $D_v = D_c$.

#### Lifecycle State Machine
`Idle -> Composing -> AwaitingSettlement -> Idle`. Commands requiring settlement remain queued in strict FIFO order in both `Composing` and `AwaitingSettlement`. Entering or remaining in `AwaitingSettlement` still accepts valid callbacks for that epoch; it is not permission to freeze native input. Subsequent `preedit` callbacks received while in `AwaitingSettlement` maintain the `AwaitingSettlement` state and do not drop, bypass, or cancel the pending settlement request. If `markedWindow` is null in `AwaitingSettlement` following a nonterminal `unmark` (`settled: false`) and a new `preedit` callback arrives, the coordinator MUST NOT fault or throw a null-reference exception. Instead, it reallocates `markedWindow` anchored at the active selection or explicit payload bounds, captures a fresh baseline, advances `attributionEpoch++`, and transitions back to `Composing` (if no pending commands require settlement fence) or maintains `AwaitingSettlement` with pending commands intact. `Faulted` preserves the last accepted snapshot and a local recovery record.

Composition state contains session ID, an immutable pre-composition document/selection snapshot captured at composition start/mark-existing, marked window, raw protocol selection, attribution epoch, transient clauses/decorations, accepted callback sequence, pending command tickets and settlement evidence. A composition history group is separate from an attribution epoch: partial commits may start new attribution epochs within one undo group.

**Proposed Post-Edit Selection Validation (Finding D)**: All incoming payloads containing a selection range (`selection: [anchor, focus]`) MUST be validated against the **proposed post-edit document length**:
$$\text{proposedLen} = \lvert D_v\rvert - (v - u) + \lvert\text{text}\rvert$$
1. For text growth (insertions and expansions), selections within the newly expanded bounds $[0, \text{proposedLen}]$ are admitted.
2. For text shrinkage (deletions and contractions), selections strictly greater than $\text{proposedLen}$ are rejected upfront with `Rejected(InvalidSelection)` before any mutation, overlay modification, or text splice occurs.
3. Selections are never validated solely against the stale pre-edit document length, preventing spurious rejection of valid growth selections and preventing out-of-bounds selection corruption on shrinkage.

**Independent Unicode Boundaries (§4.2 Validation Rule)**: The coordinator and verification oracle maintain strictly decoupled Unicode extended grapheme cluster segmentation (UAX #29 pinned to Unicode 17.0, CLDR 48.0, ICU 78.3 under Node.js v24.20.0 `Intl.Segmenter`; evaluated across hand-selected regression fixtures—such as emoji ZWJ sequences, combining marks, regional indicator flags, and Indic conjuncts—rather than an exhaustive full Unicode conformance corpus). Outward grapheme closure during formatting and replacement is independently verified without circular reliance on coordinator internals.

**Transaction-Owned Unified Target Resolution (Run-08 Finding D)**: The coordinator and verification oracle enforce unified target resolution (`_resolveTargetDescriptor(payload)`) shared identically across proposed post-edit validation and mutation dispatch: when `payload.range` is omitted, if an active marked range exists (`markedRange !== null`), the target resolves to the marked candidate span; if the marked range is null (such as after nonterminal `unmark(settled: false)`), the target resolves to the current selection (`[anchor, anchor]`), ensuring proposed-state length checks and mutation targets never diverge.

#### State ownership model

Object lifetimes are decoupled to prevent premature lifecycle teardown:

| State Entity | Lifetime Scope | Owner / Authority | Reset / Invalidation Boundary |
|---|---|---|---|
| Connection Epoch | Native adapter instance lifetime | Platform bridge adapter | Connection close / replacement (`openNewConnection`, `dispose`) |
| Committed Document ($D_c$) | Editor instance lifetime | `editor-core` transaction owner | Standard editing; never reset; monotonic `DocumentRevision` on semantic changes |
| Provisional Overlay ($\mathcal{O}$) | Active composition session | IME coordinator (`editor-session`) | Terminal settlement (`unmark`, `completeSettlement`, `commit`) or cancellation |
| Session State | User editing session lifetime | `editor-session` | Focus shift / clear |
| Attribution Epoch | Continuous candidate baseline lifetime | `editor-session` attribution engine | Range expansion, partial commit boundary, new composition start |
| Composition History Session | Single user composition gesture | Engine undo stack (`editor-session`) | Terminal settlement (`completeSettlement`, `commit`) or connection turnover |
| Marked Window | Active preedit candidate lifetime | IME input coordinator | Cleared on unmark, commit, or cancel |
| Settlement Obligation | Pending settlement fence lifecycle | Input coordinator | Fulfilled on `completeSettlement` / terminal `commit` |
| Pending Commands | Deferred application format commands | FIFO command queue | Drained on completion; cancelled on `cancel`/focus loss/disposal |

### 4.3 Deterministic attribution and segment manipulation

This policy fixes the demonstrated `bold(a) + italic(b) -> x -> ab` loss without claiming to infer character identity from opaque replacements, and resolves the core semantic gaps (§4.1, §4.2, §4.3, §4.6):

#### Attribution Epoch Model
An attribution epoch stores:
```text
baseSegments: immutable snapshot of overlay segments at epoch start
baseWindow: immutable marked window at epoch start
B_epoch: immutable attributed baseline slice corresponding to baseWindow
C_raw: current raw candidate string in the epoch's live candidate
seed: frozen insertion attributes
```

At composition start/mark-existing over range $[u, v)$ in $D_c$:
1. If $u = v$, create 1 empty segment `OverlaySegment(u, u, [])`.
2. If $u < v$, create atomic 1-unit segments `OverlaySegment(i, i+1, [D_c[i]])` for $i \in [u, v)$.
3. $\text{markedWindow} = [0, v - u)$. $B_{\text{epoch}}$ is the slice of units in $\text{markedWindow}$. Seed is resolved from insertion policy.

#### Preedit Updates and Detour Attribution (Gap 4.6)
For an incoming preedit text splice into the marked candidate:
1. Compare new candidate string with $B_{\text{epoch}}$.text.
2. Find maximal common prefix $p$ and maximal common suffix $s$ constrained by $p + s \le \min(\lvert B_{\text{epoch}}\rvert, \lvert\text{newText}\rvert)$.
3. Copy $B_{\text{epoch}}$ raw attributes for prefix and suffix; apply seed throughout the modified middle.
4. If candidate detours back to baseline ($ab \to x \to ab$), $B_{\text{epoch}}$ guarantees 100% attribute and token identity restoration.
5. Splice the attributed units into the segment array, replacing the touched segment range.

#### Target Expansion and Absorbed Original Text (Astra Critique 1, Gap 4.3)
When target expansion expands the candidate envelope outside the current overlay ($u < s_c$ or $v > s_c + L$):
1. **Exact Native Range Fidelity**: Admitted `markedRange` MUST equal requested $[u, v)$ without silent clamping.
2. **Absorbed Original Text**: Units absorbed from $D_c$ on the left ($[u, s_c)$) or right ($[e_c, e_c + (v - (s_c + L)))$) enter the overlay as **atomic 1-unit segments** `OverlaySegment(i, i+1, [D_c[i]])`.
3. They retain their exact original $D_c$ coordinates and origin session IDs.
4. If subsequent partial commit cuts preceding provisional text, the absorbed units remain isolated in their 1-unit segments, preserving them completely upon cancellation. This permanently eliminates the need for ad-hoc surplus tracking variables.

#### Retargeting versus Committed Content (Gap 4.2)
When retargeting moves the marked window:
1. Strict disjointness: Committed content outside the new target is untouched and cannot be rolled back by subsequent cancellation of the new target.
2. Overlap/Touching: Overlay segments covering untouched committed text remain bound to their respective $D_c$ coordinates.

#### Formatting Provenance and Grapheme Closure (Gap 4.1)
1. When commands format an active composition, they target the semantic whole entity (`CompositionTarget(sessionId)`).
2. Outward grapheme closure: If combining marks or zero-width joiners are inserted beside base characters, formatting extends outward to enclose the complete extended grapheme cluster.
3. Empty candidate outcome: If composition terminates with an empty candidate, typing intent is updated with the format patch, but NO formatting undo group is created.
4. Disjoint retargeting: When a composition is retargeted across disjoint islands, queued commands format only the tracked constituent candidate islands (with per-island outward grapheme cluster expansion), explicitly leaving intervening unedited text between islands untouched (no convex hull formatting).

### 4.4 Segment-aware partial commit and zero-length deletion tracking

Partial commit (`partialCommit(k)`) partitions the active overlay at flattened unit offset $p_{\text{cut}} = \text{markedWindow.relStart} + k$.

#### Segment Partitioning Algorithm
Let $s_c = \text{segments}[0].dcStart$. Walk segments tracking cumulative unit offset:
1. **Zero-Length Deletion Segments ($units = \emptyset$, Gap 4.4)**:
   - A zero-length deletion segment belongs to the committed prefix iff $p_{\text{cut}} > \text{segStart}$ (cut strictly passes the deletion point) OR ($p_{\text{cut}} == \text{segStart}$ AND no remaining visible session units exist after $p_{\text{cut}}$ in the active composition session).
   - If $p_{\text{cut}} \le \text{segStart}$ while remaining visible candidate units exist after $p_{\text{cut}}$, the deletion belongs to the **remaining uncommitted overlay** and cancels cleanly if the rest of the candidate is aborted. This guarantees that deletions positioned at or after a retargeted cut boundary belong to the remaining provisional effect and are restored upon cancellation.
2. **Split Segment and Rollback Ownership (Gap 4.5)**:
   - If $p_{\text{cut}}$ splits strictly inside a multi-unit replacement segment $g_m$ ($\text{segStart} < p_{\text{cut}} < \text{segEnd}$):
     - The consumed segment units up to the cut and preceding wholly consumed segments form the total consumed prefix:
       $$P_{\text{commit}} = \text{flatten}(\text{consumedSegments.units}) \mathbin{\Vert} g_m.units[0 .. p_{\text{cut}} - \text{segStart})$$
     - $d_{\text{cut}} = g_m.dcEnd$.
     - Splicing $P_{\text{commit}}$ into $D_c[s_c .. d_{\text{cut}})$ **SATISFIES and EXTINGUISHES** the original rollback obligation to the replaced character(s).
     - The uncommitted tail $U_{\text{tail}} = g_m.units[p_{\text{cut}} - \text{segStart} ..)$ becomes a **pure insertion segment** at the new committed boundary:
       $$\text{OverlaySegment}(dcStart = s_c + \lvert P_{\text{commit}}\rvert, dcEnd = s_c + \lvert P_{\text{commit}}\rvert, units = U_{\text{tail}})$$
     - Suffix segments shift their $D_c$ coordinates by $\Delta = \lvert P_{\text{commit}}\rvert - (d_{\text{cut}} - s_c)$.
3. **Overlay and Epoch Update**:
   - A partial commit (`partialCommit`) MUST strictly remain in the `Composing` state (`Composing -> Composing`), preserving the active session and all queued application commands.
   - **Segment Splitting Precision**: When splitting a segment during partial commit, the unconsumed portion of committed document coordinates ($\text{origSpan} - \text{consumedSpan}$) MUST remain associated with the split segment tail ($\text{splitSegmentTail.origDcEnd} = \text{seg.dcEnd}$). This guarantees that subsequent committed characters in $D_c$ are never truncated or dropped when shifting the remaining overlay.
   - If remaining segments contain visible units, $\mathcal{O}$ remains active with updated segments, $\text{markedWindow}' = [0, \text{currentLen} - k)$, and a new attribution epoch begins for the remaining suffix.
   - If all units of the active candidate are committed ($k = \text{currentLen}$), the coordinator remains in `Composing` with an empty active composition ($\text{markedWindow}' = [0, 0]$, $\mathcal{O} = [\text{OverlaySegment}(newSc, newSc, [])]$). It does NOT transition to `Idle`, does NOT drop queued commands, and does NOT finalize a history group until terminal settlement (`unmark`, `completeSettlement`, or `commit`).

#### Executable Undo / Redo Semantics (§4.4 Contract)
The coordinator implements executable `undo()` and `redo()` transactions:
1. **Undo (`undo()`)**: Restores pre-edit text ($D_c$), attributes, and selection from the top `undoStack` entry, pushes the corresponding redo entry, and advances `DocumentRevision` iff text or attributes changed. If an uncommitted composition session is active when `undo()` is requested:
   - **Explicit Ordered Queue Settlement Policy (Defect 4)**: If queued formatting commands exist in `pendingCommands`:
     - If visible text has not been modified from the baseline snapshot ($D_v = S_{\text{before}}.\text{text}$), the coordinator drains and executes queued commands against the visible text, pushes an explicit `Formatting` history group onto `undoStack`, and pops that entry, reverting to pre-command document state while populating `redoStack`.
     - If visible text was modified, the active visible composition is materialized and flushed to committed storage $D_c$, pending commands are drained and executed against the committed text, and explicit history groups are pushed in causal sequence (`Composition`, then `Formatting`). `undo()` then pops the top entry (reverting formatting first), transitioning `compositionState` to `Idle`. Active provisional work is NEVER silently discarded on Undo, and the composition remains retrievable on `redoStack`!
   - If no queued commands exist and text was modified, pushes 1 `Composition` entry, pops to revert to $S_{\text{before}}$, and preserves on `redoStack`.
2. **Redo (`redo()`)**: Re-applies post-edit text, attributes, and selection from the top `redoStack` entry, pushes to `undoStack`, and advances `DocumentRevision` iff text or attributes changed.
3. **Branch Invalidation**: Any newly admitted editing mutation clears `redoStack = []`.

#### Cancellation Semantics
Discarding $\mathcal{O}$ instantly reveals $D_c$. Because $D_c$ already holds exact committed units and absorbed characters were mapped directly to $D_c$, cancelling an uncommitted overlay restores the exact committed document state in $O(1)$ operations with zero leakage!

| Event / Trigger | State Transition | Resulting Text, Attributes & Selection | Pending Commands Behavior | History Groups | Revision Changes | Acknowledgement / Receipt |
|---|---|---|---|---|---|---|
| **Explicit Start** (`startComposition`) | `Idle -> Composing` | Initializes $\mathcal{O}$ with 1-unit segments; $D_v = D_c$; pins pre-composition snapshot. | Queued in FIFO order if format arrives; existing empty. | None | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(CompositionStarted)` |
| **Implicit Preedit** (`preedit` in `Idle`) | `Idle -> Composing` | Slices candidate into active selection $\mathcal{O}$; pins snapshot; reconciles attributes against $B_{\text{epoch}}$. | Queued in FIFO order if format arrives; existing empty. | None | If $D_v$ changes: `SessionRev++`, `DocRev++`; else `SessionRev++` | `Accepted(PreeditUpdated)` |
| **Continuing Preedit** (`preedit` in `Composing`) | `Composing -> Composing` | Slices candidate into $\mathcal{O}$; reconciles attributes against $B_{\text{epoch}}$; preserves raw selection. | Remains queued in FIFO order. | None | If $D_v$ changes: `SessionRev++`, `DocRev++`; else `SessionRev++` | `Accepted(PreeditUpdated)` |
| **Preedit while Awaiting** (`preedit` in `AwaitingSettlement`) | `AwaitingSettlement -> Composing` | Slices candidate into $\mathcal{O}$; invalidates pending settlement token; preserves raw selection. | Commands remain queued in strict FIFO order; no settlement bypass. | None | If $D_v$ changes: `SessionRev++`, `DocRev++`; else `SessionRev++` | `Accepted(PreeditUpdated)` |
| **Partial Commit** (`partialCommit`) | `Composing -> Composing` | Commits prefix into $D_c$; extinguishes split rollback; shifts remaining overlay; advances epoch. | Remains queued in FIFO order; invalidates settlement token. | None (same composition group) | `SessionRevision++`; `DocumentRevision` unchanged (visible $D_v$ identical) | `Accepted(PartialCommitted)` |
| **Target Expansion** (`targetExpansion`) | `Composing -> Composing` | Preserves exact native range fidelity ($[u, v)$); absorbs covered $D_c$ units as 1-unit segments; advances epoch. | Remains queued in FIFO order; invalidates settlement token. | None | `SessionRevision++`; `DocumentRevision` unchanged until splice | `Accepted(TargetExpanded)` |
| **Commit Replacement** (`commit` with text) | `Composing / AwaitingSettlement -> Idle` | Commits text into $D_c$; clears $\mathcal{O} = \text{null}$; resolves final committed extent. | Drains queued commands in FIFO order against resolved extent. | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision++` | `Accepted(Committed)` |
| **Direct Commit in Idle** (`commit` in `Idle`) | `Idle -> Idle` | Replaces active selection in $D_c$; extent $[start, start + \text{len}]$. | Executed immediately. | Creates 1 `Composition` history group. | `SessionRevision++`; `DocumentRevision++` | `Accepted(Committed)` |
| **Finish / Unmark (Terminal)** (`unmark`, `settled: true`) | `Composing / AwaitingSettlement -> Idle` | Commits all overlay units to $D_c$; clears $\mathcal{O} = \text{null}$. | Drains queued commands in FIFO order against accepted extent. | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision` unchanged ($D_v$ already published) | `Accepted(Unmarked)` |
| **Unmark (Non-terminal)** (`unmark`, `settled: false`) | `Composing -> AwaitingSettlement` | Commits overlay units to $D_c$; clears $\mathcal{O} = \text{null}$; issues monotonic settlement token. | Commands remain queued in FIFO order awaiting completion fence. | Deferred until explicit completion fence arrives (`completeSettlement`). | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(Unmarked)` |
| **Repeating Unmark** (`unmark` in `AwaitingSettlement`) | `AwaitingSettlement -> AwaitingSettlement` | Rejected as already unmarked; no state drift. | Commands remain queued in FIFO order. | None. | `SessionRevision` unchanged; `DocumentRevision` unchanged | `Rejected(AlreadyUnmarked)` |
| **Complete Settlement** (`completeSettlement`) | `AwaitingSettlement -> Idle` | Verifies token and guards; rejects if candidate active, token obsolete, or session/epoch mismatch. | Drains queued commands in FIFO order against concrete extent. | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(Settled)` |
| **Cancellation** (`cancel`) | `Composing / AwaitingSettlement -> Idle` | Discards $\mathcal{O}$, revealing $D_c$. Supports `nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`. | Commands targeting composition cancelled (`CompositionCancelled`); unrelated commands mapped across delta and drained immediately (Finding E). | 0 groups if no net change; 1 `Composition` group if net partial commit occurred. | `SessionRevision++`; `DocumentRevision` updated if $D_v$ changed | `Accepted(Cancelled)` |
| **Application Format Command** (`dispatchAppCommand`) | `Composing -> AwaitingSettlement` | Command stored with ticket ID, epoch, and session ID in FIFO queue. | Queued in FIFO order; watchdog arms at 2s for diagnostics only. | None (deferred) | `SessionRevision++`; `DocumentRevision` unchanged | `Deferred(ticketId)` (or `Applied` if Idle) |
| **Focus Loss** (`onFocusLost`) | `Composing / AwaitingSettlement -> Idle` | Finalizes composition per strategy ('settle' commits overlay; 'cancel' discards overlay). | Commands targeting composition cancelled (`FocusLost`). | Group finalized per settlement or partial commit rules. | `SessionRevision++`; `DocumentRevision` updated if modified | `Accepted(FocusLost)` |
| **Connection Replacement** (`openNewConnection`) | `* -> Active` (`compositionState = Idle`) | Flushes active overlay into $D_c$; resets sequence counter to 0; evicts receipt cache; advances connection `epoch++`. | Stale connection commands expired (`StaleEpoch`); pending commands drained against committed extents. | Pushes 1 `Composition` history group if text/attrs modified (including prior partial commits, Findings A & B). | `SessionRevision++`; `DocumentRevision` unchanged ($D_v$ preserved) | `Accepted(ConnectionOpened)` |
| **Native Removal** (`nativeRemoval`) | `Idle / Composing / AwaitingSettlement` | Replaces specified range with `replacementText` in $D_c$; records symmetrical undo history entry (Run-08 Finding B). | Mapped across removal delta. | Pushes 1 `NativeRemoval` (or `Composition`) undo group with exact pre/post text. | `SessionRevision++`; `DocumentRevision++` | `Accepted(NativeRemoval)` |
| **Disposal** (`dispose`) | `* -> Closed` (`compositionState = Idle`) | Rejects further operations; materializes accepted visible overlay units into $D_c$ under preserve-visible-content teardown; invalidates active fences (Run-08 Finding C). | All pending commands cancelled (`Disposed`). | Pushes 1 `Composition` history group if text/attrs modified. | `SessionRevision++`; `DocumentRevision` unchanged ($D_v$ preserved) | `Accepted(Disposed)` |
| **Undo during Composition** (`undo`) | `Composing / AwaitingSettlement -> Idle` | Materializes overlay to $D_c$, pushes `Composition` entry, pops to revert to pre-session snapshot $S_0$, and preserves composition on `redoStack`. | Pending commands drained/cancelled. | Exactly 1 `Composition` undo group created and popped to `redoStack`. | `SessionRevision++`; `DocumentRevision` updated iff restored differs from visible | `Accepted(Undo)` |
| **Redo** (`redo`) | `Idle -> Idle` | Re-applies post-edit text, attributes, and selection from top `redoStack` entry; pushes symmetric entry to `undoStack`. | None. | Transfers entry from `redoStack` to `undoStack`. | `SessionRevision++`; `DocumentRevision` updated iff state changes | `Accepted(Redo)` |

Do not defer a native mutation merely because it resembles a queued application command. Commands generated by the platform and application UI have distinct origins.

#### Symmetrical Undo / Redo Transfer and Empty Preimages (Run-08 Finding A)
Executable undo/redo stacks transfer immutable records symmetrically between `undoStack` and `redoStack`. All field transfers check explicitly for `undefined` and `null` rather than using truthiness fallback (`entry.beforeText || this.docText`), ensuring that empty strings (`""`) representing an empty-document preimage are preserved faithfully through repeated undo/redo cycles (`["x", "", "x", ""]`). Redo records are reconstructed strictly from entry records (`entry.afterText`, `entry.afterAttrs`) rather than current document state.

#### Complete History & Session Termination Matrix (14 Events × 3 States)
The following matrix enumerates every admitted semantic mutation and session termination path across all three engine lifecycle states (`Idle`, `Composing`, `AwaitingSettlement`), establishing representation-independent history and lifecycle obligations:

| Event / Input Operation | State | Admission Status | Surviving Visible Content | Authoritative History Preimage | Group Finalization Timing | Selection & Targets | Commands & Settlement Evidence | Document / Session Revisions | Invalid / Rejection Outcome |
|---|---|---|---|---|---|---|---|---|---|
| **1. `preedit`** | `Idle` | **Admitted** | Inserted candidate text replacing selection | Snapshot $S_0$ captured immediately before preedit | Deferred to terminal settlement | Proposed-length validated; target = selection | Pending commands queue in FIFO order; evidence = null | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` if selection exceeds proposed length |
| | `Composing` | **Admitted** | Updated candidate text spliced into overlay | Original pre-session snapshot $S_0$ strictly preserved | Deferred to terminal settlement | Target = explicit range or active candidate window | Settlement fence invalidated; commands remain queued | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` if selection out-of-bounds |
| | `AwaitingSettlement` | **Admitted** (Resumed) | Resumed candidate spliced into document | Original pre-session snapshot $S_0$ strictly preserved | Deferred to terminal settlement | Unified target: selection if markedRange is null, else marked candidate | Active settlement fence invalidated; transitions `-> Composing` | `docRev++` iff visible changed; `sessRev++` | `Rejected(InvalidSelection)` on proposed length bounds failure |
| **2. `partialCommit(k)`** | `Idle` | **Rejected** | Pre-event document unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(NoActiveComposition)` |
| | `Composing` | **Admitted** ($1 \le k \le \text{len}$) | 100% identical visible text before and after | Original pre-session snapshot $S_0$ strictly preserved | Deferred (cumulative `sessionCommittedRanges` updated) | Target narrows to remaining uncommitted suffix | Active settlement fence invalidated; new attribution epoch baseline | `docRev` unchanged; `sessRev++` | `Rejected(InvalidLength)` if $k < 1$ or $k > \text{len}$ |
| | `AwaitingSettlement` | **Rejected** | Pre-event document unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(CandidateNotActive)` (must resume preedit first) |
| **3. `commit(text)`** | `Idle` | **Admitted** | Inserted / replaced text permanently in $D_c$ | Document state immediately prior to commit | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range or selection | None pending; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | `Rejected(InvalidSelection)` |
| | `Composing` | **Admitted** | Final candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection = post-edit selection; marked window cleared | Pending commands drained against committed extent in FIFO order | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection = post-edit selection; marked window cleared | Pending commands drained against settled extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **4. `nativeRemoval`** | `Idle` | **Admitted** | Range excised and replaced with `replacementText` | Document state immediately prior to removal | Finalized immediately: exactly 1 `NativeRemoval` undo group | Target = explicit range; selection validated against post-length | None pending; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | `Rejected(InvalidRange)` or `Rejected(InvalidSelection)` |
| | `Composing` | **Admitted** | Native excision applied; overlay cleared | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range; selection validated | Commands drained/cancelled; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Native excision applied; settlement cleared | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Target = explicit range; selection validated | Settlement fence cleared; commands drained/cancelled; `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **5. `unmark(settled: false)`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` (nothing to unmark) |
| | `Composing` | **Admitted** (Nonterminal) | Candidate text remains visible in document | Original pre-session snapshot $S_0$ preserved | Deferred to completeSettlement | `markedRange` cleared (`null`); selection preserved | Active settlement token/fence generated; transitions `-> AwaitingSettlement` | `docRev` unchanged; `sessRev++` | `Rejected(AlreadyUnmarked)` if called repeatedly |
| | `AwaitingSettlement` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(AlreadyUnmarked)` |
| **6. `unmark(settled: true)`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` |
| | `Composing` | **Admitted** (Terminal) | Candidate text committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | `markedRange` cleared (`null`); selection preserved | Pending commands drained against final extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** (Terminal) | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; session state cleared | Pending commands drained against settled extent; transitions `-> Idle` | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| **7. `completeSettlement`** | `Idle` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` (not awaiting settlement) |
| | `Composing` | **Rejected** | Unmodified | N/A | None | Unmodified | Unmodified | Unmodified | `Rejected(CandidateStillActive)` (candidate must be unmarked first) |
| | `AwaitingSettlement` | **Admitted** | Visible candidate committed permanently to $D_c$ | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Session state cleared; selection preserved | Commands drained against settlement evidence; transitions `-> Idle` | `docRev++` iff commands mutate text/attrs; `sessRev++` | Guard rejections: `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch`, `UnknownTargetExtent` |
| **8. `cancel` / `cancelUncommitted`** | `Idle` | **Admitted** (No-op) | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None (`{ status: 'Accepted' }`) |
| | `Composing` | **Admitted** | Uncommitted overlay discarded; frozen units from `partialCommit` survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist; else no history push | Selection restored to pre-session (or end of frozen units) | Active composition commands cancelled; unrelated `TrackedRange` commands mapped across delta & drained immediately | `docRev++` iff visible text changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Uncommitted overlay discarded; frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist; else no history push | Selection restored to pre-session (or end of frozen units) | Composition commands cancelled; unrelated `TrackedRange` commands drained immediately; transitions `-> Idle` | `docRev++` iff visible text changed; `sessRev++` | Atomic rollback on stage failure |
| **9. `applicationAbort`** | `Idle` | **Admitted** (No-op) | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None (`{ status: 'Accepted' }`) |
| | `Composing` | **Admitted** | Identical to `cancel`: uncommitted discarded, frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist | Restored to pre-session | Composition commands cancelled; unrelated commands drained immediately; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | Identical to `cancel`: uncommitted discarded, frozen units survive | Original pre-session snapshot $S_0$ | Finalized immediately iff net frozen units exist | Restored to pre-session | Composition commands cancelled; unrelated commands drained immediately; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | Atomic rollback on stage failure |
| **10. `openNewConnection`** | `Idle` | **Admitted** | 100% preserved | Unmodified | None | Selection preserved | Epoch bumped (`epoch++`), sequence counter reset, receipt cache cleared | `docRev` unchanged; `sessRev++` | Atomic rollback on stage failure |
| | `Composing` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; overlay cleared | Pending commands drained/cancelled; session cleared; epoch bumped; `-> Idle` | `docRev` unchanged (materialization preserves visible text); `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved; settlement cleared | Commands drained/cancelled; session cleared; epoch bumped; `-> Idle` | `docRev` unchanged; `sessRev++` | Atomic rollback on stage failure |
| **11. `onFocusLost`** | `Idle` | **Admitted** | Unmodified | Unmodified | None | Unmodified | Unmodified | Unmodified | None |
| | `Composing` | **Admitted** | Dependent on strategy (`preserve-visible-content` / `commit` preserves; `cancel` discards) | Original pre-session snapshot $S_0$ | Finalized if committed/preserved | Selection preserved | Pending commands drained if committed, cancelled if aborted; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| | `AwaitingSettlement` | **Admitted** | Dependent on strategy | Original pre-session snapshot $S_0$ | Finalized if committed/preserved | Selection preserved | Settlement fence resolved; commands drained or cancelled; `-> Idle` | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| **12. `dispose`** | `Idle` | **Admitted** | 100% preserved | Unmodified | None | Selection preserved | `connectionState -> Closed`; session cleared | `docRev` unchanged; `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| | `Composing` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved | Commands drained/cancelled; `connectionState -> Closed`; `-> Idle` | `docRev` unchanged (content preserved); `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| | `AwaitingSettlement` | **Admitted** | 100% preserved (overlay flushed to $D_c$) | Original pre-session snapshot $S_0$ | Finalized immediately: exactly 1 `Composition` undo group | Selection preserved | Commands drained/cancelled; `connectionState -> Closed`; `-> Idle` | `docRev` unchanged; `sessRev++` | Subsequent calls: `Rejected(ConnectionClosed)` |
| **13. `queueCommand(cmd)`** | `Idle` | **Admitted** (Immediate) | Formatting / command applied immediately to target | Document state prior to command | Finalized immediately: exactly 1 command-specific undo group | Target = explicit `TrackedRange` | Outcome = `Applied`; ticket recorded in `commandOutcomes` | `docRev++` iff attrs changed; `sessRev++` | `Rejected(InvalidTarget)` if `CompositionTarget` requested while Idle |
| | `Composing` | **Admitted** (Queued) | Visible text/attrs unmodified until settlement | N/A (deferred) | None (deferred to settlement) | Tracked by ticket in FIFO queue | Added to `pendingCommands`; ticket issued; outcome = `Pending` | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)` |
| | `AwaitingSettlement` | **Admitted** (Queued) | Visible text/attrs unmodified until settlement | N/A (deferred) | None (deferred to settlement) | Tracked by ticket in FIFO queue | Added to `pendingCommands`; ticket issued; outcome = `Pending` | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)` |
| **14. `undo` / `redo`** | `Idle` | **Admitted** iff stack nonempty | Historical document text/attrs restored | Top entry from respective stack (`undoStack` or `redoStack`) | Symmetrically transferred between stacks with explicit named fields | Restored from historical `beforeSelection` or `afterSelection` | N/A | `docRev++` iff restored state differs from visible; `sessRev++` | `Rejected(NothingToUndo)` or `Rejected(NothingToRedo)` |
| | `Composing` | **Undo: Admitted**; **Redo: Rejected** | Active composition materialized to $D_c$, then undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Materialized composition pushed to `undoStack`, popped, and preserved on `redoStack`! | Restored to pre-session selection | Overlay cleared; marked window cleared; commands drained; `-> Idle` | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` (active editing clears redo) |
| | `AwaitingSettlement` | **Undo: Admitted**; **Redo: Rejected** | Active composition materialized to $D_c$, then undone to pre-session $S_0$ | Original pre-session snapshot $S_0$ | Materialized composition pushed to `undoStack`, popped, and preserved on `redoStack`! | Restored to pre-session selection | Settlement cleared; commands drained; transitions `-> Idle` | `docRev++` iff restored differs from visible; `sessRev++` | Redo: `Rejected(NothingToRedo)` |

### 4.5 Settlement and pending command semantics

`requestSettlement(reason)` returns `AlreadyIdle`, `Requested(ticket)`, `Unsupported` or `Failed`. If in `Composing` or `AwaitingSettlement`, it returns `{ status: 'Requested', ticketId, reason, sessionId, epoch, token }`, generates a deterministic monotonic settlement token via `nextSettlementToken()`, and sets `compositionState = 'AwaitingSettlement'`. The companion API `cancelCommand(ticketId)` cancels an individual queued command, removing it from `pendingCommands` and recording outcome `{ status: 'Cancelled', reason: 'CancelledByUser', ticketId }` (or `{ status: 'NotFound' }`). Completion evidence records adapter, epoch, last accepted sequence and the tested mechanism: a synchronous API completion, a verified event sequence, or closing the old input session followed by a fresh projection. There is no generic assumption that `finishComposingText`, `unmarkText`, blur, `compositionend`, or the next event-loop turn is universally sufficient.

#### Settlement Token Lifecycle and Rejection Rules

Settlement tokens follow a strict monotonic lifecycle:
1. **Deterministic Issuance**: Issued via `nextSettlementToken()` when entering `AwaitingSettlement` or handling `requestSettlement`. All tokens use deterministic monotonic counters (no random or timestamp strings).
2. **Invalidation on New Input**: Any intervening candidate input mutation (`preedit`, `targetExpansion`, or `partialCommit`) immediately invalidates and supersedes all outstanding settlement tokens (`activeSettlementToken = null`).
3. **Rejection Conditions**: When `completeSettlement` arrives:
   - If candidate text remains actively marked (`markedRange !== null`), settlement is rejected with `Rejected(CandidateStillActive)`.
   - If the settlement token is obsolete due to intervening input mutations, it is rejected with `Rejected(ObsoleteSettlementToken)`.
   - If session ID, connection epoch, or sequence counter does not match the active session, it is rejected with `Rejected(SessionMismatch)` or `Rejected(StaleEpoch)`.
   - If the target extent cannot be verified, it is rejected with `Rejected(UnknownTargetExtent)`. The coordinator MUST NEVER fall back or resolve an unknown extent to `[0, 0]`.
   - Duplicate completion calls without intervening composition return `Rejected(AlreadySettled)` without creating duplicate history groups.

#### Dynamic Queued Command Range Target Mapping

Queued application commands are mapped dynamically across candidate mutations:
- **Expansion / Contraction**: When the active candidate length changes by delta `Δ = len(newCandidate) - len(oldCandidate)`, queued command range targets `[cmdStart, cmdEnd]` whose bounds fall at or after the candidate insertion point shift by `+Δ` (or `-Δ` on contraction).
- **Partial Commits**: Frozen units advance the committed extent, and the active candidate narrows to the remaining suffix.
- **Cancellation Rollback & Token-Identity Surviving Target Resolution (Finding E, Defect 1)**: Commands targeting active composition cancel with `CompositionCancelled`. Commands targeting explicit document ranges (`TrackedRange`) MUST NOT be transformed via a global scalar net delta ($\Delta = \lvert D_c\rvert - \lvert D_v\rvert$), which breaks down under disjoint or opposing edits (e.g. insertion at index 0 and deletion at index 4 with net $\Delta = 0$). Instead, the coordinator projects tracked ranges piece-by-piece against surviving token identities in $D_c$. Collapsed ranges map to piece boundaries. Targets entirely contained in discarded provisional units return `Rejected(TargetDeleted)`; targets beyond document bounds return `Rejected(TargetInvalidated)`. Surviving targets are mapped to exact contiguous bounds in $D_c$, immediately drained in FIFO order, and executed against the restored document state, ensuring unrelated commands are never trapped or mislocated.
- **Idle Command Execution and Outcome Tickets (Defect 2)**: Commands dispatched while the coordinator is in `Idle` execute synchronously, recording outcome `{ status: 'Applied', ticketId, ... }` in `commandOutcomes` and pushing a history group if text or attributes changed. Ticket records are accountable and preserved across transactional rollbacks.
- **Cross-Epoch Receipt Isolation & Connection Fencing (Defect 3)**: Sequence receipts are keyed composite-style as `${epoch}:${seq}`. The coordinator validates connection epoch prior to duplicate receipt lookups; packets from defunct connections are rejected (`StaleEpoch` or `FutureEpoch`) without polluting or colliding with sequence numbers of recycled connections.

#### Distinct Cancellation Semantics

The coordinator distinguishes three distinct cancellation mechanisms:
1. `nativeRemoval`: The native platform deletes or backspaces candidate characters. Uncommitted candidate text is excised, earlier frozen prefix units remain committed under Invariant I08, and any prefix/suffix surplus from target expansion is restored.
2. `endCompositionPreservingText`: The platform IME finishes or unmarks without emitting a replacement text payload, accepting marked candidate text verbatim into the document as committed text.
3. `applicationAbort`: Explicit cancellation triggered by the application or user (e.g. Escape key, toolbar action, programmatic cancellation), rolling back uncommitted candidate mutations to the pre-composition snapshot or post-expansion baseline while preserving frozen committed units.

On verified completion: finalize the attributed history group, clear transient metadata, map the pending command targets through accepted edits, validate them, then execute a separate transaction/history group. A command targeting active composition follows the session via explicit `CompositionTarget(sessionId)`. The coordinator resolves `CompositionTarget(sessionId)` strictly to the tracked constituent candidate islands resulting from that session (with per-island outward grapheme cluster expansion), explicitly leaving intervening unedited text between islands untouched (never formatting a convex hull):
1. **Standard Contiguous Session**: For an ordinary replacement, growth, or shrinkage session without disjoint retargeting, resolves to the single contiguous committed extent $[s_0, s_0 + \text{len}(committedText))$.
2. **Disjoint Retargeting Session**: When a composition session was retargeted across disjoint islands, resolves strictly to the constituent candidate islands $[isl_i[0], isl_i[1]]$, mapped dynamically across preceding edits (e.g. $[isl_0, isl_1, \dots]$), never spanning the unedited text between them.
3. **Empty Final Candidate (cancellation/deletion)**: Resolves to an empty range $[sessionStart, sessionStart)$. Formatting an empty range updates typing intent only, producing NO document revision bump and NO formatting history group. Queued commands targeting an aborted composition cancel with `CompositionCancelled`.
4. **Partial Commits & Full Partial Commit (Finding C)**: Constituent islands track remaining uncommitted candidate units while committed prefix units are tracked via `sessionCommittedRanges`. If an active candidate is fully committed ($k = \text{len}$) leaving an empty active marked range, queued formatting commands targeting `ActiveComposition` format the cumulative committed units of the session (`sessionCommittedRanges`), creating a separate `Formatting` undo group on `undoStack` without modifying `typingIntent`. Range formatting is never converted into typing intent when committed content exists.
5. **Direct Commit in Idle State**: Resolves to $[start, start + \text{len}(commitText))$ calculated from the active selection $[start, end]$, never defaulting to document offset 0.
6. **Tracked Range Commands**: Commands specifying an explicit `TrackedRange(range)` resolve strictly to their own dynamically tracked range, mapped through preceding insertions and deletions, and are NEVER conflated with the composition extent. If a command specifies `CompositionTarget(sessionId)` with a mismatched session ID, it is rejected with `TargetSessionMismatch`.

Never default or fall back to the whole document! Surrounding differently attributed text outside these constituent extents remains strictly untouched. An ordinary unrelated range uses registered anchors and cancels with `TargetDeleted` if its intended content vanished. A collapsed typing toggle applies at the final logical caret. This avoids independently mapping swallowed endpoints into an unintended range.

If settlement is unsupported, keep the command pending until natural completion. The UI shows “Finish text conversion to apply this action” and permits cancelling the command. A watchdog after two seconds updates status/diagnostics only; it never commits or replays text. Preserve FIFO ticket order; cancellation removes only that ticket. On focus loss or disposal, pending commands targeting active composition are cancelled with an explicit result (`FocusLost` or `Disposed`), never applied to a different session or editor. When composition cancellation occurs, commands targeting the active composition are cancelled (`CompositionCancelled`), but commands targeting unrelated explicit document ranges are preserved, drained immediately in FIFO order via token-identity surviving range resolution, and executed (`Applied`) against the restored document state, ensuring unrelated commands are never trapped indefinitely (Finding E, Defect 1). If cancellation occurs after partial commits, frozen committed units remain in the document under Invariant I08, a single composition history group is recorded for the net partial commit, and pending commands targeting the aborted candidate are cancelled (`CompositionCancelled`).

For a bridge fault, capture last accepted document, raw native proposal and trace locally; disable further semantic commands until explicit recovery/reopen. Do not silently overwrite the composing DOM or discard an unaccepted proposal. The application presents recovery status. A platform requiring frequent recovery for supported normal input fails qualification.

### 4.6 Selection and focus

Selection interaction is orthogonal: Idle, Dragging(pinned logical anchor), NativeAdjustment, ExpectedEcho. Suppress only the exact expected selection echo tied to the outstanding update; different selection changes must still be processed. Maintain preferred visual x for vertical movement; clear it on explicit horizontal/pointer relocation. Toolbar focus alone preserves typing intent. Genuine user relocation clears explicit typing overrides. A text change under a stationary caret is not automatically a user relocation.

Mandatory traces include repeated preedit, marked existing mixed styles, candidate detours, partial commits, suffix cancellation, explicit range expansion, reconversion, selection outside marked text, duplicate internal delivery, blur/refocus, late old events, unbalanced batches, toolbar commands, Undo and process recovery.

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

Admission rules:

1. A native explicit replacement wholly within one text block is accepted verbatim, including raw LF and surrogate units.
2. A noncomposing replacement across ordinary compatible same-parent paragraphs can compile into joins plus text splice only if its resulting projection equals the proposed plaintext result exactly. It cannot reinterpret a table or list boundary as plain text.
3. A semantic Enter/Delete/ReplaceSelection command follows §2.6 and publishes an explicit new projection. It is not disguised as an arbitrary native plaintext rewrite.
4. Composition cannot span protected atoms, cells or incompatible structural ancestry. Before starting composition over an application structural selection, the bridge must settle any existing session, execute the semantic deletion, synchronize its landing-point projection, then admit the new composition. This must be demonstrated per adapter; it is not safe to pretend the original native range still applies after that synchronization.
5. Unsupported opaque proposals return the platform's failure result when rejection is supported. If the native/browser side has already mutated noncancelably, capture the proposal and enter the recoverable fault path rather than silently losing input or inventing structure. This limitation is a release blocker if encountered in ordinary supported editing; cross-structure reconversion can remain an explicitly documented unsupported capability.

This scope is narrower and more implementable than promising an inverse from arbitrary plaintext to hierarchical identity. Clipboard, drag/drop and toolbar operations supply structured commands, not guessed native provenance.

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
6. `getInsertPositionOffset()`: returns `markedStart` when composition is active, and `selectionEnd` when no composition is active.
7. `cancelLatestCommittedText(attributes)`: unsupported in this document engine; explicitly returns `null`.
8. Scoping: A headless Java interface spike validates the coordinate translation unit only, not a qualified native host. Real Compose/AWT host input, selection, geometry, settlement, and editable accessibility remain unvalidated (Gate G0 Desktop NOT RUN / INCOMPLETE). Validate requests against [InputMethodRequests](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html). Never expose full live text under a committed-only index.

No production adapter may depend on reflection into private Compose internals. Existing research reflection is a diagnostic seam only. A required fork must be explicit, versioned, licensed and backed by reproduction/upgrade tests; it cannot appear as a hidden dependency.

### 6.4 Web transaction boundary

Pin exact ProseMirror packages and lockfile integrity. Define schema/node-ID mapping and indexed position translation; never use a full HTML/JSON document round trip per keystroke. `editor-core` remains callable synchronously from the browser main thread; JS/Wasm representation and call overhead are a Phase 0 implementation choice settled by compilation and measurement.

On each candidate transaction: classify origin/steps; translate ordered supported replacements/selection; admit synchronously; receive canonical receipt; adopt the candidate state only when it matches the permitted projection. Disable ProseMirror history/collaboration plugins and duplicate default keymaps. Route app formatting, paste, cut and structural keys to the core. Native typing must not also execute through a hardware-key handler.

Projection equivalence compares text, structure and the engine's **effective rendering attributes**, not raw stored per-unit attributes. During active composition, temporary DOM clause wrappers and delayed mark correction are allowed only inside the marked subtree under a recorded composition token. Raw text and outside structure must still match accepted state. Geometry in that subtree comes from the actual DOM and carries its surface-projection version plus composition token; never infer its current rectangles from canonical styles that have not yet been projected. Do not replace composing DOM nodes or ancestors to restore marks on every update; apply the minimal correction after verified settlement. If this cannot be achieved in the pinned view version, the Web bridge gate fails. Do not conceal it with full-document replacement.

`beforeinput` alone is not authoritative. Handle DOM/ProseMirror changes that cannot be cancelled, event-order differences and final input after lifecycle notifications. Unsupported step classes trigger captured diagnostics and recovery. Native undo gestures (`historyUndo`/`historyRedo`) route to core history, never browser-only history.

Default Web release uses retained semantic DOM; `content-visibility` is an optional measured optimization disabled until selection, find, composition and accessibility tests pass. Do not virtualize away active composition or AT-requested content. Core performance does not prove ProseMirror projection performance.

### 6.5 Accessibility, clipboard and imports

Expose reading order, stable node identity, headings/lists/tables, text ranges, selected ranges, editing actions, link targets, image alternatives, focus and correct geometry. Offscreen content must be navigable. Required AT: TalkBack/Android, VoiceOver/iOS and macOS, NVDA/Windows, and a declared Linux reader/bridge combination. Web qualifies declared browser/AT combinations. Title announcement alone is not a pass.

Clipboard supports plain text, versioned internal rich fragments, sanitized HTML subset and TSV. Fresh IDs on paste; preserve link identity only within the new fragment's remapped namespace. Strip executable content and dangerous URL schemes; embeds are typed data with resource references. Async paste captures anchors/revision and cancels or asks for an explicit new target if the original target is deleted. Never apply to the user's later selection accidentally. Parsing budgets reject oversize/unbounded nesting with a report.

## 7. Transactional editing, undo/redo and durability

Pipeline: validate owner/epoch/revision -> resolve captured target/typing policy -> compile primitives -> apply to private persistent builder -> map selections/anchors -> normalize affected schema -> validate -> compare semantic delta -> record history/journal intent -> publish snapshot/receipt -> notify adapters/observers. Exceptions discard builder and staged effects. Observer commands queue for a later transaction.

Document no-op detection includes raw text, attributes, structure and resources; selection, typing intent, input lifecycle and decorations are session state. A no-op document delta creates no document history or text synchronization. Selection/input-only state changes still receive a new session revision/receipt as needed. `DocumentRevision` advances only for document changes; `SessionRevision` advances for every published change, including document changes. PositionRef uses the document revision. A snapshot/receipt exposes both, so caret/decoration changes do not invalidate all text layout. History entries record selection/session preimages without making selection-only changes document edits.

Each history group holds immutable before/after roots or complete equivalent inverse payload, selection/typing snapshots, registered-anchor deltas, origins and reason. Undo/redo are new transactions with new monotonic revisions; restored IDs/content equal historical states, revision numbers do not go backwards. For local editing, root restoration is allowed when history has not been bypassed by untracked external mutations. All semantic document edits enter history or explicitly clear its incompatible branch.

Ordinary contiguous typing merges only with matching location, attributes, origin and direction, within 750 ms and without an intervening selection/command barrier. A composition, including prior partial commits, constitutes one `Composition` history group. Formatting, paste and structural commands are separate groups after composition settlement. If a connection is replaced (`openNewConnection`) or disposed (`dispose`) while an active or partially committed composition has modified document text or attributes, the coordinator must flush and commit the active overlay to $D_c$ and finalize exactly one `Composition` history group to the `undoStack` before tearing down the session (Findings A & B). Transient composition text must never remain in the document without attributed undo history, and prior partial commits must never lose their undo records across connection turnover. Typing toggles alone are session state; insertion records the resulting intent. New editing after Undo clears Redo.

Do not support formatting interleaved inside an unfinished composition group in the first release. The verified ProseMirror fixture showed why inherited formatting on later candidate replacements can escape ordinary mapped undo. Queuing the format command after settlement yields two well-defined groups and avoids claiming a selective-history solution that has not been implemented.

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

Gate G1 (Phase 1 Gated; pending plan resolution, capability freeze, and native qualification; production implementation prohibited): I01–I06 and I14 core tests; exhaustive short offset maps; at least 100,000 randomized operations; exact save/load of attributes and malformed UTF-16; schema-invalid input rejection; exception rollback. Cross-piece graphemes and surrogate pairs included. Measure storage without claiming total editor performance.

### Phase 2 — Commands, positions, formatting and history

Deliver registered anchors, selection types, structural command compiler, typing intent, grapheme rendering projection, attributed history and semantic no-op handling. Implement history before IME integration rather than retrofitting it after input behavior is established.

Gate G2: every §5 boundary row has forward/reversed/empty/block-edge fixtures. WOMAN–ZWJ–LAPTOP round trip and explicit-format variant pass. Undo/redo preserve raw attributes, structure, IDs and selection over fuzzed traces. No-op styling produces no native text effects. Async target deletion is detected. All §2.6 commands have examples and expected canonical results.

### Phase 3 — Input coordinator with deterministic fake adapters

Deliver epoch/sequence rules, batches, reentrancy, attribution epochs, partial commit, command tickets, settlement/fault outcomes and local recovery. Fake adapters implement multiple documented orderings; they do not stand in for device qualification.

Gate G3: I07–I10, I12–I13 and I16 pass model checking/trace replay. Candidate `ab -> x -> ab` restores original attribution within an epoch. Partial commit cannot restyle a frozen prefix. Explicit range expansion creates the specified new baseline. Formatting queued during preedit yields separate history after settlement; Undo never restores transient phonetic text when undoing only formatting. Unknown settlement never becomes success by timeout. Raw selection and drawable caret remain separate.

### Phase 4 — Layout, drawing and pointer behavior

Deliver Compose paragraph cache, measured-height tree, effective rendering styles, frame keys, hit testing, visual navigation, scroll anchoring and geometry transforms. Use normal full paragraph layout. Keep pathological paragraphs in the test corpus; do not conceal them through arbitrary chunks.

Gate G4: I11; rendering/hit-test round trips at bidi boundaries and wrapped lines; ligatures, combining sequences, emoji and mixed direction; fractional scaling/zoom and large scroll coordinates; stale font/layout jobs discarded. Screenshot/raster checks and native candidate-rectangle checks are both needed. Performance figures are measured per backend with fixed font files.

### Phase 5 — Real platform input and Web projection

Deliver production Android/iOS/Desktop adapters, ProseMirror bridge, key routing, selection menus and lifecycle handling. Implement one platform end-to-end first; replay shared semantic traces everywhere, then execute each platform's native traces independently.

Gate G5 per environment: English prediction/autocorrection; Chinese Pinyin; Japanese conversion/reconversion; Korean; Arabic/Urdu/Hebrew with Latin/numbers; Devanagari/Thai; dead keys; emoji; dictation; hardware keyboard; blur/rotation/window changes; partial commit; toolbar/paste/Undo during composition. Confirm exact-once text, attributes, history and candidate position. Browser synthetic events are insufficient evidence for real IME behavior.

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

#### Revision 4.8 Verification Summary

The independent executable validation harness (`validation/antigravity/run-20261013-04/`) executed comprehensive adversarial verifications resolving all Run-03 and Run-04 specification defects and proving the correctness of Revision 4.8:
1. **Reproduction & Falsification of Run-03 Clamping & Revision Defects**: `reproduce-native-range-failure.mjs` accurately reproduced the run-03 silent clamping defect (`HXQlo` instead of `HQlo`) and DocumentRevision metadata-freeze contradiction.
2. **Authentic BFS Bounded Model Checking**: 7,767 unique abstract reachable states and 8,286 transitions explored soundly across 3 initial document states up to depth 5 in 299 ms. Sound sequence stamping ensures legitimate actions pass monotonically increasing stamps (`seq = depth + 1`) while duplicate stamps query existing cached receipts, eliminating false epoch drift and phantom state branching. All 10 mandatory invariants (`I_FROZEN_PRESERVED`, `I_HISTORY_PREIMAGE_AND_OBLIGATION`, `I_SINGLE_COMP_HISTORY`, `I_EXTENT_BOUNDS_AND_FORMAT_FIDELITY`, `I_NO_SETTLEMENT_BYPASS`, `I_COMMAND_OUTCOME`, `I_RAW_SELECTION_AND_RANGE_FIDELITY`, `I_REVISION_RULES`, `I_IDENTITY_ISOLATION`, `I_ATOMIC_FAILURE`) were evaluated and passed across EVERY transition with 0 invariant violations.
3. **Subclass Mutation Testing**: 11 distinct coordinator subclasses injecting real implementation defects (silent clamping, premature history commit, committed unit leakage, metadata revision bumping, premature settlement, unmark preedit crash, trapped commands, formatting attribute leakage, stale settlement completion, receipt loss, etc.); 11/11 mutants caught cleanly (8 caught directly via bounded model checking with minimal counterexample traces, 3 caught via targeted invariant assertions). Subclass polymorphism was preserved across all BFS exploration branches via dynamic constructor instantiation.
4. **Target Expansion Absorption Semantics (§4.3, §4.4)**: Verified that when target expansion completely encompasses the committed extent (`newStart <= committedExtent[0]`), both active committed units and historical committed units are absorbed into the candidate envelope, ensuring clean document rollback to pre-composition snapshot upon subsequent cancellation without leaving orphaned committed units.
5. **Settlement Token & Ordering Contract Verification**: 7 comprehensive scenarios verifying monotonic token issuance, invalidation on new input (`preedit`, `targetExpansion`, `partialCommit`), rejection of active candidates (`CandidateStillActive`), rejection of obsolete tokens (`ObsoleteSettlementToken`), session/epoch mismatch guards (`SessionMismatch`, `StaleEpoch`), and strict prevention of `[0, 0]` extent fallbacks.
6. **Normative Cancellation & Dynamic Command Mapping**: 8 scenarios verifying distinct cancellation semantics (`nativeRemoval`, `endCompositionPreservingText`, `applicationAbort`), target expansion surplus restoration, and dynamic command range re-targeting across candidate expansions, contractions, and cancellation rollbacks.
7. **Coordinator Repair & Specification Suite**: 11 coordinator repair tests, 5 R01-R05 requirement tests, 5 R06-R10 requirement tests, and R14 serialization round-trip + Property I07/I08 fuzzing (10,000 iterations) passing with zero failures.
8. **Desktop AWT InputMethodRequests Spike**: 11 headless unit tests verifying all 66 valid committed-text slices against an independent oracle, composed-text coordinate geometry, empty slice handling, and attribute filtering (`DesktopAwtInputMethodRequestsSpike.java`).
9. **Master Test Runner**: `runner.mjs` executed all 10 validation suites in 2,508 ms, with 19/19 test cases passing cleanly, producing structured `results.json` artifact.
10. **Platform Boundaries Retained**: Gate G0 Desktop is strictly marked NOT RUN / INCOMPLETE because native window hosting, live OS IME candidate popup positioning, and screen-coordinate translation remain unexercised. Mobile (Android/iOS) and Web gates remain NOT RUN.

#### Revision 4.9.1 Verification Summary & Architectural Consolidation (Run-20261014-05)

The independent executable validation harness (`validation/antigravity/run-20261014-05/`) executed comparative adversarial evaluations between Candidate A (v4 flat live-document buffer) and Candidate B (v5.3 segment-mapped provisional overlay) against an Independent Observational Oracle (`independent-oracle.mjs`), resolving all seven composition semantic gaps (§4.1–§4.7):
1. **Falsification of Candidate A (Flat Buffer)**:
   - Failed 4 of 14 hand-calculated regression fixtures (29% failure rate).
   - In Gap 4.3 (Astra Critique 1: `abcd -> aXYZcd -> partialCommit Y -> cancel`), Candidate A permanently discards absorbed original character 'c' (`aYcd` instead of `aXYcd`) because its scalar `originEnd` marker cannot distinguish absorbed unchanged units from replaced units.
   - In Gap 4.4 (`abc -> ac -> partialCommit 1 -> cancel`), Candidate A lacks zero-length deletion tracking; the uncommitted deletion of 'b' leaks permanently into the buffer (`ab` instead of `abc`).
   - In Gap 4.4 retargeted cut boundary (`abcde -> bd -> partialCommit 1 -> cancel`), Candidate A permanently discards original character 'd' (`abce` instead of `abcde`).
   - In Gap 4.5 (multi-unit replacement rollback ownership), splitting a replacement in Candidate A corrupts rollback state (`aUc` instead of `aXUc`).
   - In BFS model checking, Candidate A is proven unsound on Invariant 4 (`I_COMMITTED_EQUIVALENCE`) at depth 1 transition (`'Helo' !== 'Hello'`).
2. **Definitive Qualification of Candidate B (Segment Overlay)**:
   - Passed 14 of 14 hand-calculated regression fixtures (100% pass rate).
   - Passed all 7 adversarial settlement lifecycle scenarios (monotonic token fences, invalidation on new input, rejection of active candidates, stale tokens, and epoch mismatches).
   - Passed all 17 deep semantic gap verification traces across §4.1–§4.7 (including disjoint constituent island formatting without convex hull, touching boundary, partial overlap, full containment, clamp rejection, deletion after retargeted cut boundary, and multiple consecutive interior retargetings).
   - Caught 11/11 coordinator subclass mutants cleanly via invariant checkers and assertions.
   - Explored 3,585 unique abstract states and 8,710 transitions in 537 ms at depth 5 with 0 invariant violations against the Independent Oracle.
   - Measured benchmark throughput of 11,943 ops/sec (83.73 µs per multi-step composition cycle), comfortably exceeding the 60fps/120fps frame budget (<8.3–16.7 ms) by two orders of magnitude.
3. **Normative Consolidation Verdict**:
   - Retain v4 whole-editor baseline architecture: hierarchical node schema, persistent B+ tree, raw UTF-16 unit representation, structural command admission, Compose/ProseMirror projections, and platform gates G0–G8.
   - Replace v4 flat composition tracking with Candidate B's segment-mapped provisional overlay ($\mathcal{O}$) and token-tape oracle model.
   - Adopt all seven contractual semantic gap resolutions (§4.1–§4.7) into normative specification §4.2–§4.5.
#### Revision 4.9.2 Verification Summary & Protocol Defect Resolution (Run-20261014-06)

The independent executable validation harness (`validation/antigravity/run-20261014-06/`) reproduced and resolved nine concrete coordinator defect scenarios (A–I), repaired validator assertions, soundly explored the expanded state-space, and qualified Candidate B:
1. **Reproduction and Root-Cause Resolution for Defect Scenarios A–I**:
   - **Defect A (Interior Segment Splicing)**: Splicing into touched multi-unit segments partitions into distinct constituent segments preserving respective $D_c$ footprints, eliminating out-of-order prepending (`aXQZc` verified).
   - **Defect B (Implicit Preedit Selection Normalization)**: Noncollapsed selections normalize through selection deletion before admitting candidate text (`HXo` verified).
   - **Defect C (Atomic History Snapshots)**: `_flushOverlayToCommitted()` flushes provisional overlay before settlement history entry creation, preventing text duplication (`cats` verified).
   - **Defect D (Unicode Outward Grapheme Closure)**: Outward grapheme closure via `Intl.Segmenter` correctly formats combining marks, ZWJ sequences, and base characters together (`a\u0301` bold verified).
   - **Defect E (Ticket Conservation on Cancellation)**: Unrelated commands targeting explicit document ranges preserved across composition cancellation with ticket status unchanged (`ticket-1` pending verified).
   - **Defect F (Full Partial Commit Session Continuity)**: Full partial commits ($k = \text{len}$) retain `Composing` state with empty active composition, preventing premature `Idle` and subsequent unmark rejection.
   - **Defect G (Upfront Range and Selection Validation)**: Negative, inverted, and out-of-bounds ranges rejected atomically with zero state mutation across text, attributes, revisions, and selection.
   - **Defect H (Constituent Disjoint Island Mapping)**: Queued commands format tracked constituent session islands mapped through preceding edits, leaving intervening text unformatted (bold indices `[0, 5, 6]` verified without convex hull corruption).
   - **Defect I (TrackedRange Target Isolation)**: Commands specifying `TrackedRange` resolve strictly to their own target extent; session-mismatched commands rejected with `TargetSessionMismatch`.
2. **Repaired Invariant Suite & Bounded Model Checking**:
   - Repaired `I_FORMAT_FIDELITY` asserting concrete patch properties across target ranges.
   - Repaired `I_TICKET_CONSERVATION` asserting issued tickets are in exactly one of pending commands or terminal outcomes.
   - Repaired `I_ATOMIC_FAILURE` comparing complete pre/post observable states on rejections.
   - Repaired multi-session composition history checking via `findLast`.
   - Explored 44,935 unique abstract states and 63,798 transitions at depth 4 in 5,530 ms with 0 invariant violations across all 10 formal invariants against the repaired Independent Oracle.
3. **Sound Mutation Suite**:
   - 20 realistic mutants (11 baseline + 9 defect regressions A–I) evaluated with both positive and negative controls.
   - 20/20 positive controls passed on unmodified Candidate B (zero false positives).
   - 20/20 mutants caught cleanly with minimal counterexample traces.

4. **Phase 0 & Platform Boundaries Retained**:
   - Gate G0 Desktop, Mobile (Android/iOS), and Web platforms remain strictly NOT RUN / INCOMPLETE. Headless model validation does not constitute native platform qualification. Production implementation remains prohibited until acceptance gates pass on native targets.

#### Revision 4.9.3 Verification Summary & Contract Violation Repair (Run-20261014-07)

The independent executable validation harness (`validation/antigravity/run-20261014-07/`) reproduced and resolved all six contract violations (Findings A–F), repaired segment-mapped island tracking and session extent calculations, integrated 6-stage transactional failure injection and executable undo/redo, expanded the bounded state-space exploration to 143,913 unique states and 204,932 transitions at depth 4 with 0 invariant violations, and caught 27/27 subclass mutants cleanly:
1. **Reproduction and Contract Resolution for Findings A–F**:
   - **Finding A (Turnover Content Persistence)**: Connection replacement under the preserve-visible-content policy flushes and commits provisional overlay text to $D_c$, retaining latest accepted visible text (`"HXllo"`) and finalizing exactly one composition history group before teardown (§4.1, §4.5).
   - **Finding B (Undo Retention Across Partial Commit & Turnover)**: Connection replacement following partial commits computes exact cumulative session extent across committed units and flushed overlay text, pushing exactly one `Composition` history entry with exact before/after text and attributes (§4.5).
   - **Finding C (Full Partial Commit Target Tracking)**: Fully committed units ($k = \text{len}$) format directly in $D_c$ via cumulative `sessionCommittedRanges` rather than distorting `typingIntent`; pushes a separate `Formatting` undo group without modifying typing intent (§4.5).
   - **Finding D (Post-Edit Selection Validation)**: Selections validated against proposed post-edit document length ($\text{proposedLen} = |D_v| - (v - u) + |\text{text}|$); growth, shrinkage, reversed selections, and empty candidates validate against post-edit bounds (§4.2).
   - **Finding E (Immediate Cancellation Drainage for Unrelated Commands)**: Composition cancellation immediately maps surviving unrelated commands (`TrackedRange`) across the net rollback delta and drains the pending queue to 0 with explicit `Applied` or `Rejected(TargetInvalidated)` outcomes (§4.5).
   - **Finding F (Callback Epoch Admission & Cache Eviction)**: Native callbacks carrying connection identity validate epoch against active connection; stale callbacks (`epoch < activeEpoch`) reject with `StaleEpoch`; future callbacks reject with `FutureEpoch`; receipt cache evicts on connection turnover and sequence stamps cleanly reuse across epochs (§4.1).
2. **Segment-Mapped Island & Session Extent Repair**:
   - Repaired `_partialCommit` in `candidate-b-coordinator.mjs` to preserve and map disjoint session islands and committed ranges strictly in visible document coordinates, eliminating phantom island creation and duplicate formatting.
   - Added `_computeSessionExtent()` calculating the exact bounding range across `sessionCommittedRanges`, active `markedRange`/`markedSpan`, and `sessionIslands` for connection turnover, disposal, and cancellation undo entries.
3. **Complete Canonical Equivalence Key (§4.1) & Independent Unicode Qualification (§4.2)**:
   - State serialization incorporates Oracle's `undoStack`, `redoStack`, `sessionIslands`, `sessionCommittedRanges`, and `receiptCache`.
   - Independent Unicode expectations qualified against UAX #29 pinned fixtures (combining marks, ZWJ sequences, regional flags, CRLF, Devanagari) with full coordinator and oracle agreement.
4. **Real 6-Stage Transactional Failure Injection (§4.3) & Executable Undo/Redo (§4.4)**:
   - Evaluated 6 transaction stages (`afterTextSplice`, `afterTargetMapping`, `duringOverlayMaterialization`, `beforeHistoryPublication`, `duringCommandPreparation`, `beforeReceiptPublication`) asserting deep byte equality with pre-transaction state on rejections (Invariant 10 passed).
   - Verified executable `undo()` and `redo()` across text insertions, deletions, replacements, partial commits, turnover, and formatting with branch invalidation.
5. **Bounded Model Checking & Sound Mutation Testing**:
   - Explored **143,913 unique abstract states** and **204,932 transitions** at depth 4 in 39,039 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle. Candidate A proven unsound at depth 0 on Invariant 1 (`'BadHello' !== 'Hello'`).
   - 27 realistic mutants (9 repaired contract mutants + 7 retained defect mutants + 11 baseline invariant mutants) caught cleanly with 27/27 positive controls and 27 clean assertion kills.
   - Comparative microbenchmark: Candidate B executes at 3,894 ops/sec (256.79 µs/op in Node.js V8; native multiplatform unvalidated).
6. **Phase 0 & Platform Boundaries Retained**:
   - Gate G0 Desktop, Mobile (Android/iOS), and Web platforms remain strictly NOT RUN / INCOMPLETE. Pure JS ES module validation; production Kotlin implementation remains prohibited until acceptance gates pass on native targets.

#### Revision 4.9.4 Verification Summary & History/Lifecycle Contract Violation Repair (Run-20261014-08)

The independent executable validation harness (`validation/antigravity/run-20261014-08/`) reproduced all 4 baseline failure conditions in `reproduce-failures.mjs` (`raw-reproduced-failures.json`), repaired the candidate coordinator and oracle models, verified all 14 regression blocks and 18 hand-calculated fixtures, resolved universal rejection atomicity (Invariant 10), and qualified Candidate B through bounded state-space exploration and subclass mutation verification:
1. **Reproduction and Contract Resolution for Findings A–D**:
   - **Finding A (Repeated Undo/Redo & Empty Preimages)**: Eliminated truthiness fallback bug (`entry.beforeText || this.docText`) by enforcing strict undefined/null checks; symmetric immutable records transfer between undo and redo stacks, faithfully preserving empty-document preimages through repeated cycles (`["x", "", "x", ""]` verified).
   - **Finding B (Native Removal Across Lifecycle States)**: `nativeRemoval(range, replacementText, selection)` implemented symmetrically across `Idle`, `Composing`, and `AwaitingSettlement` states, recording traceable undo history entries without dropping prior composition history.
   - **Finding C (Disposal Content Preservation)**: `dispose()` under the preserve-visible-content teardown contract materializes accepted visible overlay units into durable committed storage $D_c$ before closing and records history, rather than discarding the overlay.
   - **Finding D (Resumed Preedit Unified Target Resolution)**: Unified target descriptor resolution (`_resolveTargetDescriptor`) shared between proposed-state validation and mutation; omitted range after nonterminal `unmark(settled: false)` targets current selection, while active marked ranges target candidate span, eliminating validation/mutation divergence.
2. **Rejection Atomicity (Invariant 10) Across All Mutations**:
   - In `applyInput()`, universal rejection atomicity is enforced: any operation returning `{ status: 'Rejected' }` unconditionally restores `preTxSnapshot`, guaranteeing bit-for-bit invariance across revisions, text, selection, overlay, and stacks.
   - `undo()` and `redo()` check stack emptiness upfront before cancelling active composition sessions.
3. **Master Test Suite Verification**:
   - **14/14 Repaired Regression Blocks** passed cleanly (`test-repaired-regressions.mjs`).
   - **18/18 Hand-Calculated Fixtures** passed on Oracle and Candidate B (Candidate A failed 8 negative controls) (`test-fixtures.mjs`).
   - **21/21 Settlement Lifecycle Scenarios** passed cleanly (`test-settlement-lifecycle.mjs`).
   - **17/17 Deep Semantic Gaps** passed cleanly (§4.1–§4.7) (`test-semantic-gaps.mjs`).
   - **34/34 Mutants Caught Cleanly** with 34/34 positive controls passed (34 clean assertion kills, 0 unrelated crashes) (`test-mutations.mjs`).
   - **8/8 Named Longer Sequences** passed cleanly (`test-named-longer-sequences.mjs`).
   - **Bounded Model Checker**: Explored **151,943 unique abstract states** and **211,708 transitions** at depth 4 in 67,357 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle (`independent-model-checker.mjs`). Candidate A proven unsound at depth 0 on Invariant 1.
   - **Performance Benchmark**: Candidate B executes 5,000 full cycles in 2,939.84 ms (587.97 µs/op; Candidate A 34.41 µs/op; 17.09x overhead ratio, well within sub-millisecond per-keystroke interactive frame budget).
4. **Platform Boundaries and Fixed Semantics**:
   - Pinned UAX #29 fixture details: Unicode 17.0, CLDR 48.0, ICU 78.3, Node.js v24.20.0, evaluated across hand-selected regression fixtures rather than full conformance corpus.
   - Retains raw LF semantics without automated U+2028 conversion; 100-ms long-paragraph qualification target (p95 <= 100 ms, known high-risk/unmet); synchronous Web ProseMirror admission.
   - Gate G0 Desktop, Mobile (Android/iOS), and Web platforms remain strictly NOT RUN / INCOMPLETE. Pure JS ES module validation; production Kotlin implementation remains prohibited until acceptance gates pass on native targets.

#### Revision 4.9.5 Verification Summary & Comprehensive Contract Completeness Qualification (Run-20261014-09)

The independent executable validation harness (`validation/antigravity/run-20261014-09/`) resolved the 4 remaining target-mapping and command-admission defects (Defects 1–4), expanded the verification suite with an exhaustive 85-cell contract coverage sweep, qualified Candidate B across 40 mutants and depth-4 bounded model checking, and finalized representation-independent editing contract Revision 4.9.5:
1. **Resolution of Defects 1–4**:
   - **Defect 1 (Piece-Mapped Surviving Range Resolution on Rollback)**: Eliminated unsound scalar net delta ($\Delta = |D_c| - |D_v|$) in `mapTrackedRangeOnCancel`. Under disjoint opposing zero-delta edits (e.g. insertion at index 0 and deletion at index 4), surviving target units in $D_c$ are collected piece-by-piece. Collapsed carets map to piece boundaries; targets within discarded provisional text return `Rejected(TargetDeleted)`; targets beyond bounds return `Rejected(TargetInvalidated)`.
   - **Defect 2 (Idle Command Execution & Outcome Records)**: Commands dispatched in `Idle` execute synchronously, recording outcome `{ status: 'Applied', ticketId, ... }` in `commandOutcomes`. Ticket records are accountable and preserved across transactional rollbacks.
   - **Defect 3 (Cross-Epoch Receipt Isolation & Connection Fencing)**: Receipts keyed composite-style as `${epoch}:${seq}`. The coordinator validates connection epoch prior to duplicate receipt lookups; packets from defunct connections reject with `StaleEpoch` or `FutureEpoch` without polluting the cache.
   - **Defect 4 (Explicit Ordered Queue Settlement Policy on Undo)**: When `undo()` is requested during active composition with queued commands, pending commands are drained and settled against the visible text prior to reverting document text, ensuring deterministic undo ordering (`Formatting` group popped first, or `Composition` reverted cleanly).
2. **Master Test Suite Verification (9 Suites Total)**:
   - **18/18 Repaired Regression Blocks** passed cleanly (`test-repaired-regressions.mjs`, including Blocks 15–18 for Defects 1–4).
   - **18/18 Hand-Calculated Fixtures** passed on Oracle and Candidate B (Candidate A failed 8 negative controls) (`test-fixtures.mjs`).
   - **21/21 Settlement Lifecycle Scenarios** passed cleanly (`test-settlement-lifecycle.mjs`).
   - **17/17 Deep Semantic Gaps** passed cleanly (§4.1–§4.7) (`test-semantic-gaps.mjs`).
   - **40/40 Mutants Caught Cleanly** with 40/40 positive controls passed (40 clean assertion kills, 0 unrelated crashes) (`test-mutations.mjs`).
   - **8/8 Named Longer Sequences** passed cleanly (`test-named-longer-sequences.mjs`).
   - **85/85 Exhaustive Contract Coverage Sweep Cells** passed cleanly across 17 public events and 5 lifecycle states (`test-contract-coverage-sweep.mjs`).
   - **Bounded Model Checker**: Explored **139,746 unique abstract states** and **196,532 transitions** at depth 4 in 45,797 ms with **0 invariant violations** across all 10 formal invariants against Independent Oracle (`independent-model-checker.mjs`). Candidate A proven unsound on Invariant 1.
   - **Performance Benchmark**: Candidate B executes 5,000 full cycles in 1,863.93 ms (372.79 µs/op; 2,683 ops/sec; 12.00x overhead ratio relative to Candidate A's flat buffer 31.07 µs/op, well within sub-millisecond per-keystroke interactive frame budget).
3. **Platform Boundaries and Fixed Semantics**:
   - Pinned UAX #29 fixture details: Unicode 17.0, CLDR 48.0, ICU 78.3, Node.js v24.20.0, evaluated across hand-selected regression fixtures rather than full conformance corpus.
   - Retains raw LF semantics without automated U+2028 conversion; 100-ms long-paragraph qualification target (p95 <= 100 ms, known high-risk/unmet); synchronous Web ProseMirror admission.
   - Gate G0 Desktop, Mobile (Android/iOS), and Web platforms remain strictly NOT RUN / INCOMPLETE. Pure JS ES module validation; production Kotlin implementation remains prohibited until acceptance gates pass on native targets.

Implementation choices still requiring evidence: compatible dependency pins, each adapter's completion/notification mechanism, public host embedding, Unicode-data version, actual font files, safe layout thread ownership and declared device/keyboard/browser/AT matrix. The core models, attribution policy, history policy, command semantics and failure rules above are fixed defaults; coding agents should implement and test them, not redesign them independently.

The next action is Phase 0 capability freeze and real platform IME host spikes. No production correctness or performance result is claimed merely by writing this document; production implementation remains prohibited until acceptance gates pass on native targets.
