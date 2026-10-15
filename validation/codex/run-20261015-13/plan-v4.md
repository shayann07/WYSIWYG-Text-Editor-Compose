# Compose Multiplatform document editor — implementation specification
Revision: 4.9.9, 15 October 2026. Status: Codex-owned specification and validation repair. Candidate B retained. Public request admission/publication consolidated; versioned identity locator prototype added. Evidence is bounded and does not qualify native platforms or production performance. No production implementation or phase advancement is authorized.

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

Connection states: `Detached -> Opening -> Active -> Draining -> Closed`; faults preserve the last published snapshot. Each adapter instance owns an immutable connection epoch. Never restamp an old native event with a newer epoch. Sequence numbers identify adapter delivery, not text equality; identical preedits can be distinct deliveries.

**One external admission path.** `applyNativeCallback`, `applyInput`, `dispatchCommand`, `setSelection`, `undo`, `redo`, `nativeRemoval`, `onFocusLost`, `openNewConnection`, and `dispose` capture immutable data and enter the same request executor. Public methods must not capture closures over caller-owned mutable state. Internal reducer helpers run on transaction-local state and are not public entry points.

A request envelope contains ticket identity (for deferred admission), origin, connection epoch, optional native delivery sequence, optional explicitly scoped session identity, a pinned coordinate-basis snapshot, immutable payload, and anchor affinity. Snapshot identity is more than DocumentRevision: selection, composition metadata and token ownership can change without changing visible text. Native post-replacement selection is identified as POST-edit coordinates. Kotlin uses typed half-open ranges, never ambiguous `IntRange` values for `[start,end)`.

**Coordinate basis.** External reads expose the published snapshot. By default an external request is based on that exact snapshot, including its token identities, marked window and raw selection. Missing replacement ranges are resolved from the basis marked range or basis selection at capture time. An adapter that supplies a different coordinate space must pass an explicitly identified snapshot from its projection contract; it cannot request implicit staged coordinates. Reentrant callbacks must never combine a published revision with staged token identities. The current prototype supports its published basis; alternate adapter bases remain a platform integration contract to implement and qualify.

Before execution, map a retained basis to the current version. A native replacement is a contiguous interval: all original target identities must survive contiguously and in order, otherwise reject `TargetInvalidated` or `TargetFragmented` and require adapter resynchronization. Never replace the convex hull of separated surviving content. Empty ranges are neighbor-identity anchors with BEFORE/AFTER affinity and BOF/EOF sentinels. BEFORE remains before insertions in the gap; AFTER remains after them. If both interior neighbors disappear, reject rather than guess. Formatting commands already queued for settlement use the fragmented target contract in §4.5; the current external-request boundary conservatively rejects fragmented ingress targets before dispatch. Native selection accompanying replacement is mapped from the hypothetical post-edit basis (including synthetic replacement identities) into the mapped post-edit document and validated there. A selection-only request maps endpoints independently, retaining direction. `SelectionTarget` formatting intentionally resolves at execution time (§4.5).

**Identical validation for immediate and deferred execution.** Check closed state; current epoch; valid sequence and duplicate receipt; session applicability; payload types; mapped range; proposed post-edit length; raw selection bounds; event-specific restrictions; command and target type. Invalid requests cannot mutate state. Invalid application command admission allocates no command ticket. Queued acknowledgements are not terminal success: their transport tickets receive explicit execution outcomes. No stale epoch may obtain a receipt from the current epoch. Accepted delivery receipts are cached before notifying observers, with a high-water mark preventing replay after eviction. Rejected delivery retries remain rejections; they do not become successful mutations through queue admission.

**Transaction publication.** Internal working state is distinct from public state. On acceptance, publish one deeply immutable snapshot containing text, raw attributes, selection, composition metadata, connection epoch and revisions. Notification arguments cannot mutate it, including nested arrays/maps. Retained snapshots remain unchanged. Public read access during staging returns the previous snapshot. Working-state queries are private reducer operations. `willChange(oldSnapshot, action)` runs before staging; receipt and coherent snapshot publication precede `didChange(newSnapshot)`; `onRollback(oldSnapshot, reason)` runs after restoring failed work. Notification exceptions cannot roll back already published work. Adapter will/did notifications must be mapped to these stages and verified on native runtimes.

Apply each callback through the failure stages `beforeMutation`, `afterTextSplice`, `afterTargetMapping`, `afterHistoryPush`, `afterCommandDrain`, and `beforeReceiptPublication`. A failed transaction restores its working document, history and session state and exposes none of the abandoned staged data. Independently received external requests survive failure with their original pinned basis. Private transaction-dependent work is cancelled as `AbandonedWithTransaction`. These origins are determined by the API boundary, not by whether the public call arrived reentrantly. Ticket identities are not reused after rollback.

**FIFO scheduling.** One owner drains external requests. A turn executes at most 64 queued requests, then schedules exactly one continuation on the owner event loop; leftover requests stay FIFO and new requests cannot overtake them. The model uses a macrotask scheduler (`setTimeout`); Kotlin injects the owner-dispatcher scheduling function. This budget is a fairness limit, not a rejection threshold or composition timeout. Scheduling must continue with no further user input. A host unable to schedule continuation enters a diagnosed fault and preserves pending requests for recovery; silent dropping is forbidden. Disposal cancels unexecuted requests with `Cancelled(Disposed)`; ordinary settlement does not. Turnover preserves visible content/history, advances the epoch, and old queued requests reject `StaleEpoch` at execution.

Native callbacks requiring synchronous acknowledgement may not support this deferred-reentry policy. G0 must demonstrate that the adapter can avoid such mutation reentry or translate it without acknowledging an unapplied edit as complete. This is a named platform qualification requirement, not an assumed property of the model.


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

#### Centralized transaction boundary

Section 4.1 is the authoritative admission, snapshot, notification, reentrancy and scheduling contract. All public mutation methods use that pipeline. Reducer helpers operate only on working state. An accepted external request cannot bypass validation because it was queued. Existing attribution/partial-commit operations below run inside that boundary; they do not define competing publication or queue rules.

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
The coordinator implements executable `undo()` and `redo()` transactions within the centralized transactional boundary `_runInTransaction`:
1. **Undo (`undo()`)**: Restores pre-edit text ($D_c$), attributes, and selection from the top `undoStack` entry, pushes the corresponding redo entry, and advances `DocumentRevision` iff text or attributes changed. If an uncommitted composition session is active (`Composing` or `AwaitingSettlement`) when `undo()` is requested:
   - **Asynchronous Settlement Barrier (Defect 3)**: Application `undo()` without a qualified synchronous adapter completion boundary is NOT authorization that native composition has terminated. Calling `undo()` MUST NOT unilaterally flush the overlay, drop active fences, or force `Idle`. Instead, it transitions `compositionState -> AwaitingSettlement` (if not already waiting), establishes an active settlement fence with `token = nextSettlementToken()` and `settlementEvidence = null`, queues the undo request behind the settlement obligation in `pendingCommands` (`type: 'Undo', status: 'Pending'`), and returns `Accepted(UndoQueued, action: 'QueuedBehindSettlement')`.
   - **Legitimate Native Updates During Wait**: While in `AwaitingSettlement`, legitimate native IME updates (`preedit`, `commit`, `cancel`, `unmark`) continue to be admitted under the active epoch.
   - **Terminal Settlement Drainage**: When terminal settlement completion arrives from the adapter (`commit`, `cancel`, or `completeSettlement` with matching token/epoch), all pending commands—including queued `Format` commands and queued `Undo`—drain in strict causal FIFO order. If a `Format` was queued before `Undo`, the format executes first and then `Undo` immediately pops that formatting group, leaving composed text plain; if composition was cancelled, preedit is discarded and queued `Undo` pops pre-session history.
   - **Synchronous Adapter Exception**: If and only if a qualified platform adapter explicitly supplies a synchronous completion boundary (`syncBoundary: true`, `settlementEvidence: { extent }`), the coordinator synchronously materializes the composition into $D_c$, drains commands, pushes history groups, and applies `undo()`, immediately transitioning to `Idle`.
2. **Redo (`redo()`)**: Re-applies post-edit text, attributes, and selection from the top `redoStack` entry, pushes to `undoStack`, and advances `DocumentRevision` iff text or attributes changed. While in `Composing` or `AwaitingSettlement`, `redo()` rejects with `Rejected(NothingToRedo)`.
3. **Branch Invalidation & Redo Retention (Failure 1 Repair)**:
   - **Operation-Level Invalidation**: Invalidation of `redoStack` (`redoStack = []`) is strictly scoped to operations that introduce new editing mutations (text insertion, deletion, replacement, or explicit formatting). Outer transaction wrapper commits or settlement boundary materialization (`commit`, `completeSettlement`, `unmark`) MUST NOT wipe the redo stack merely because the outer wrapper executed or net text changed.
   - **Preservation Across Settlement with Queued Undo**: When an application `undo()` is queued behind an active settlement fence in `AwaitingSettlement`, terminal settlement completion drains the queue in strict causal FIFO order. When the queued `Undo` executes during drainage, it pops the appropriate history entry from `undoStack`, reverts the document state, and pushes a redo entry to `redoStack`. This redo entry is preserved on `redoStack` upon entering `Idle`, allowing subsequent user `redo()` to re-apply the undone composition or formatting.
   - **Causal Drainage Ordering**: If an application `Format` command was queued before an `Undo` command, the `Format` applies first to the candidate/document, pushing a `Formatting` undo entry; the subsequent `Undo` immediately pops that `Formatting` entry, leaving visible text plain and restoring original attributes, while moving the formatting entry to `redoStack`.

#### Cancellation Semantics & Complexity Analysis
Discarding $\mathcal{O}$ reveals $D_c$. Because $D_c$ holds exact committed units and absorbed characters were mapped directly to $D_c$, cancelling an uncommitted overlay restores the committed document state without token leakage. However:
- **Algorithmic Complexity**: Dropping an overlay reference $\mathcal{O}$ in isolation may be $O(1)$; full cancellation with surviving target projection (`mapTrackedRangeOnCancel`), disjoint interval partitioning, command drainage, history recording, and publication is NOT $O(1)$. In the JavaScript prototype harness, `mapTrackedRangeOnCancel` executes an $O(M \times N)$ linear scan (where $M$ is the number of tokens in the tracked range and $N$ is the number of tokens in $D_c$). The selected production locator is specified in §4.5: point rank O(log² P), and M-token resolution O(M log² P), plus grouping and output work. The model scan is not a production performance result.
- **Benchmark Scope & Platform Limits**: JavaScript harness microbenchmarks measure purely in-memory segment math, transactional cloning, and grapheme segmenter execution in V8. They DO NOT establish production per-keystroke latency or frame budget compliance under native Compose UI layout, Skia rendering passes, platform IME IPC, or font shaping.
- **Contract Coverage Scope**: The 85-cell matrix represents an isolated behavioral sample across 17 public event types and 5 lifecycle states; it does NOT constitute exhaustive verification of all payload permutations, arbitrary paragraph lengths, or unbounded history interleavings.

Do not defer a native mutation merely because it resembles a queued application command. Commands generated by the platform and application UI have distinct origins.

#### Symmetrical Undo / Redo Transfer and Empty Preimages (Run-08 Finding A)
Executable undo/redo stacks transfer immutable records symmetrically between `undoStack` and `redoStack`. All field transfers check explicitly for `undefined` and `null` rather than using truthiness fallback (`entry.beforeText || this.docText`), ensuring that empty strings (`""`) representing an empty-document preimage are preserved faithfully through repeated undo/redo cycles (`["x", "", "x", ""]`). Redo records are reconstructed strictly from entry records (`entry.afterText`, `entry.afterAttrs`) rather than current document state.


#### Authoritative Master State Transition & History Lifecycle Matrix
The following definitive consolidated matrix governs all public input events, lifecycle operations, and command dispatches across the three engine lifecycle states (`Idle`, `Composing`, `AwaitingSettlement`) and connection closure (`Closed`), establishing representation-independent mutation, history, and settlement obligations:

| Event / Operation | Initial State | Transition & Status | Resulting Visible Content & Storage Mapping | History Preimage & Group Finalization | Selection & Command Targets | Settlement Fence & Pending Commands | Revision Accounting | Outcome / Rejection Rules |
|---|---|---|---|---|---|---|---|---|
| **1. `startComposition`** | `Idle` | `-> Composing` (**Admitted**) | Initializes $\mathcal{O}$ with 1-unit segments; $D_v = D_c$; captures pre-session snapshot $S_0$. | Deferred to terminal settlement. | Selection validated; target = marked window $[u, v]$. | Settlement fence = null; commands queued in FIFO order. | `docRev` unchanged; `sessRev++` | `Accepted(CompositionStarted)` |
| | `Composing` | `-> Composing` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Unmodified | Unmodified | `Rejected(AlreadyComposing)` |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Unmodified | Unmodified | `Rejected(AlreadyComposing)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **2. `preedit`** | `Idle` | `-> Composing` (**Admitted**) | Opens composition at selection; splices text into $\mathcal{O}$; captures $S_0$. | Deferred to terminal settlement. | Selection validated against proposed length; target = selection. | Settlement fence = null; commands queued in FIFO order. | `docRev++` iff $D_v$ changed; `sessRev++` | `Rejected(InvalidSelection)` if bounds exceeded |
| | `Composing` | `-> Composing` (**Admitted**) | Splices candidate into $\mathcal{O}$; reconciles attrs against $B_{\text{epoch}}$. | Snapshot $S_0$ preserved; deferred. | Selection validated; target = active window. | Settlement fence invalidated; commands remain queued. | `docRev++` iff $D_v$ changed; `sessRev++` | `Rejected(InvalidSelection)` if bounds exceeded |
| | `AwaitingSettlement` | `-> Composing` (**Admitted - Resumed**) | Resumes candidate in $\mathcal{O}$; reconciles attrs against $B_{\text{epoch}}$. | Snapshot $S_0$ preserved; deferred. | Unified target: selection if markedRange null, else candidate. | Settlement fence invalidated; commands remain queued. | `docRev++` iff $D_v$ changed; `sessRev++` | `Rejected(InvalidSelection)` if bounds exceeded |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **3. `partialCommit(k)`** | `Idle` | `-> Idle` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(NoActiveComposition)` |
| | `Composing` | `-> Composing` (**Admitted**) | Commits prefix of $k$ units to $D_c$; remaining suffix stays in $\mathcal{O}$. | Snapshot $S_0$ preserved; cumulative committed ranges updated. | Target narrows to uncommitted suffix $[0, \text{len} - k)$. | Settlement fence invalidated; commands remain queued. | `docRev` unchanged ($D_v$ identical); `sessRev++` | `Rejected(InvalidLength)` if $k < 1$ or $k > \text{len}$ |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Unmodified | Unmodified | `Rejected(CandidateNotActive)` (resume preedit first) |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **4. `targetExpansion` / `retarget`** | `Idle` | `-> Idle` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(NoActiveComposition)` |
| | `Composing` | `-> Composing` (**Admitted**) | Expands/shifts window over $D_c$; samples visible attrs ($D_v$) for seeds. | Snapshot $S_0$ preserved; deferred. | Target expanded to encompass new $[u, v)$; absorbs covered $D_c$ units. | Settlement fence invalidated; commands remain queued. | `docRev` unchanged; `sessRev++` | `Accepted(TargetExpanded)` |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Unmodified | Unmodified | `Rejected(NoActiveComposition)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **5. `commit(text)`** | `Idle` | `-> Idle` (**Admitted**) | Replaces selection with text permanently in $D_c$. | Snapshot of pre-commit state; creates 1 `Composition` group. | Target = explicit range or selection. | Executed immediately; no pending commands. | `docRev++` iff text/attrs changed; `sessRev++` | `Accepted(Committed)` |
| | `Composing` | `-> Idle` (**Admitted**) | Commits text into $D_c$; clears $\mathcal{O} = \text{null}$; resolves extent. | Snapshot $S_0$; creates 1 `Composition` group (+1 per drained format). | Post-edit selection; marked window cleared. | Commands drained against resolved extent in FIFO order. | `docRev++` iff text/attrs changed; `sessRev++` | `Accepted(Committed)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | Commits visible text into $D_c$; clears $\mathcal{O}$; resolves extent. | Snapshot $S_0$; creates 1 `Composition` group (+1 per drained format). | Post-edit selection; marked window cleared. | Commands drained against settled extent in FIFO order. | `docRev++` iff text/attrs changed; `sessRev++` | `Accepted(Committed)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **6. `unmark(settled: false)`** | `Idle` | `-> Idle` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` |
| | `Composing` | `-> AwaitingSettlement` (**Admitted - Nonterminal**) | Flushes overlay to $D_c$; clears `markedWindow`. Text remains visible. | Snapshot $S_0$ preserved; deferred to completeSettlement. | Selection preserved; `markedRange` cleared (`null`). | Monotonic settlement token issued; commands await fence. | `docRev` unchanged; `sessRev++` | `Accepted(Unmarked)` |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Commands remain queued awaiting fence. | Unchanged | `Rejected(AlreadyUnmarked)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **7. `unmark(settled: true)`** | `Idle` | `-> Idle` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` |
| | `Composing` | `-> Idle` (**Admitted - Terminal**) | Commits overlay to $D_c$; clears $\mathcal{O}$; resolves final extent. | Snapshot $S_0$; creates 1 `Composition` group (+1 per format). | Selection preserved; session cleared. | Commands drained against accepted extent; fence cleared. | `docRev++` iff text/attrs changed; `sessRev++` | `Accepted(Unmarked)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted - Terminal**) | Commits visible text to $D_c$; clears $\mathcal{O}$; resolves extent. | Snapshot $S_0$; creates 1 `Composition` group (+1 per format). | Selection preserved; session cleared. | Commands drained against accepted extent; fence cleared. | `docRev++` iff text/attrs changed; `sessRev++` | `Accepted(Unmarked)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **8. `completeSettlement`** | `Idle` | `-> Idle` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(InvalidState)` |
| | `Composing` | `-> Composing` (**Rejected**) | Unmodified | Snapshot $S_0$ preserved | Unmodified | Unmodified | Unmodified | `Rejected(CandidateStillActive)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | Commits visible text to $D_c$; verifies settlement token and guards. | Snapshot $S_0$; creates 1 `Composition` group (+1 per format). | Selection preserved; session cleared. | Commands drained against settlement evidence; fence cleared. | `docRev++` iff commands mutate; `sessRev++` | Guards: `ObsoleteSettlementToken`, `SessionMismatch`, `StaleEpoch` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **9. `cancel` / `cancelUncommitted` / `applicationAbort`** | `Idle` | `-> Idle` (**Admitted - No-op**) | Unmodified | Unmodified | None | Unmodified | Unmodified | `Accepted(Cancelled)` |
| | `Composing` | `-> Idle` (**Admitted**) | Discards uncommitted $\mathcal{O}$; frozen units from `partialCommit` survive. | Snapshot $S_0$; 1 `Composition` group iff net frozen units exist. | Restored to pre-session (or end of frozen units). | Composition commands cancelled; unrelated commands partitioned into **disjoint intervals** (`ranges: [[s0, e0], [s1, e1]]`) without min-max expansion and drained (Defect 1). | `docRev++` iff visible text changed; `sessRev++` | `Accepted(Cancelled)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | Discards uncommitted $\mathcal{O}$; frozen units survive. | Snapshot $S_0$; 1 `Composition` group iff net frozen units exist. | Restored to pre-session (or end of frozen units). | Composition commands cancelled; unrelated commands partitioned into disjoint intervals and drained. | `docRev++` iff visible text changed; `sessRev++` | `Accepted(Cancelled)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **10. `nativeRemoval`** | `Idle` | `-> Idle` (**Admitted**) | Excises range and replaces with text in $D_c$. | Pre-edit document snapshot; creates 1 `NativeRemoval` undo group. | Target = explicit range; selection validated against post-length. | None pending; transitions `-> Idle`. | `docRev++` iff text/attrs changed; `sessRev++` | `Rejected(InvalidRange)` or `Rejected(InvalidSelection)` |
| | `Composing` | `-> Idle` (**Admitted**) | Excises range in $D_c$; clears overlay. | Snapshot $S_0$; creates 1 `Composition` group. | Target = explicit range; selection validated. | Commands drained/cancelled; fence cleared. | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | Excises range in $D_c$; clears settlement. | Snapshot $S_0$; creates 1 `Composition` group. | Target = explicit range; selection validated. | Commands drained/cancelled; fence cleared. | `docRev++` iff text/attrs changed; `sessRev++` | Atomic rollback on stage failure |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **11. `openNewConnection`** | `Idle` | `-> Idle` (**Admitted**) | 100% preserved; sequence counter reset; receipt cache cleared. | Unmodified | Selection preserved. | Epoch bumped (`epoch++`); receipts cleared. | `docRev` unchanged; `sessRev++` | `Accepted(ConnectionOpened)` |
| | `Composing` | `-> Idle` (**Admitted**) | 100% preserved (overlay flushed to $D_c$); session cleared. | Snapshot $S_0$; pushes 1 `Composition` group if text/attrs changed. | Selection preserved; overlay cleared. | Commands drained/cancelled; epoch bumped (`epoch++`). | `docRev` unchanged (visible text identical); `sessRev++` | `Accepted(ConnectionOpened)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | 100% preserved (overlay flushed to $D_c$); session cleared. | Snapshot $S_0$; pushes 1 `Composition` group if text/attrs changed. | Selection preserved; settlement cleared. | Commands drained/cancelled; epoch bumped (`epoch++`). | `docRev` unchanged; `sessRev++` | `Accepted(ConnectionOpened)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **12. `onFocusLost`** | `Idle` | `-> Idle` (**Admitted - No-op**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Accepted(FocusLost)` |
| | `Composing` | `-> Idle` (**Admitted**) | Per strategy (`preserve-visible-content` / `commit` preserves; `cancel` discards). | Snapshot $S_0$; finalized if committed/preserved. | Selection preserved. | Commands drained if committed, cancelled if aborted. | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| | `AwaitingSettlement` | `-> Idle` (**Admitted**) | Per strategy. | Snapshot $S_0$; finalized if committed/preserved. | Selection preserved. | Settlement fence resolved; commands drained/cancelled. | `docRev++` iff visible changed; `sessRev++` | `Rejected(UnknownStrategy)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **13. `dispose`** | `Idle` | `-> Closed` (**Admitted**) | 100% preserved; session cleared. | None | Selection preserved. | Pending commands cancelled; connection closed. | `docRev` unchanged; `sessRev++` | `Accepted(Disposed)` |
| | `Composing` | `-> Closed` (**Admitted**) | 100% preserved (overlay flushed to $D_c$); session cleared. | Snapshot $S_0$; pushes 1 `Composition` group if text/attrs changed. | Selection preserved. | Commands cancelled; connection closed. | `docRev` unchanged; `sessRev++` | `Accepted(Disposed)` |
| | `AwaitingSettlement` | `-> Closed` (**Admitted**) | 100% preserved (overlay flushed to $D_c$); session cleared. | Snapshot $S_0$; pushes 1 `Composition` group if text/attrs changed. | Selection preserved. | Commands cancelled; connection closed. | `docRev` unchanged; `sessRev++` | `Accepted(Disposed)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **14. `dispatchCommand` / `queueCommand`** | `Idle` | `-> Idle` (**Admitted - Immediate**) | Formatting applied immediately to target in $D_c$ within `_runInTransaction` (Defect 2). | Document state prior to command; creates 1 `Formatting` group. | Target = `TrackedRange` or `SelectionTarget`. | Outcome = `Applied`; ticket recorded in `commandOutcomes`. | `docRev++` iff attrs changed; `sessRev++` | `Rejected(InvalidTargetRange)`; reentrant mutations queued in `_reentrantMutationQueue` (FIFO) |
| | `Composing` | `-> Composing` (**Admitted - Queued**) | Visible text/attrs unmodified until settlement. | Deferred to settlement. | Tracked by ticket in FIFO queue. | Added to `pendingCommands`; ticket issued; outcome = `Pending`. | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)`; reentrant mutations queued in `_reentrantMutationQueue` (FIFO) |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Admitted - Queued**) | Visible text/attrs unmodified until settlement. | Deferred to settlement. | Tracked by ticket in FIFO queue. | Added to `pendingCommands`; ticket issued; outcome = `Pending`. | `docRev` unchanged; `sessRev++` | `Rejected(TargetSessionMismatch)`; reentrant mutations queued in `_reentrantMutationQueue` (FIFO) |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **15. `undo`** | `Idle` | `-> Idle` (**Admitted**) | Restores pre-edit text/attrs from top `undoStack` entry within `_runInTransaction`. | Top entry popped from `undoStack`, transferred symmetrically to `redoStack`. | Restored from historical `beforeSelection`. | N/A | `docRev++` iff restored differs from visible; `sessRev++` | `Rejected(NothingToUndo)` if stack empty; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `Composing` | `-> AwaitingSettlement` (**Queued**) or `-> Idle` (**with `syncBoundary`**) | **Asynchronous (default)**: Visible text unmodified; queued behind active settlement fence (Defect 3). **Synchronous (`syncBoundary: true`)**: Overlay flushed to $D_c$, undone to pre-session $S_0$. | Asynchronous: Deferred. Synchronous: `Composition` group pushed to `undoStack`, popped, and preserved on `redoStack`. | Asynchronous: Selection preserved. Synchronous: Restored to pre-session selection. | Asynchronous: Settlement token issued; `Undo` queued in `pendingCommands` (`action: \'QueuedBehindSettlement\'`). Synchronous: Drained/cleared. | Asynchronous: Unchanged. Synchronous: `docRev++` iff restored differs; `sessRev++`. | `Rejected(ConnectionClosed)`; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Queued**) or `-> Idle` (**with `syncBoundary`**) | **Asynchronous (default)**: Visible text unmodified; queued behind active settlement fence (Defect 3). **Synchronous (`syncBoundary: true`)**: Overlay flushed to $D_c$, undone to pre-session $S_0$. | Asynchronous: Deferred. Synchronous: `Composition` group pushed to `undoStack`, popped, and preserved on `redoStack`. | Asynchronous: Selection preserved. Synchronous: Restored to pre-session selection. | Asynchronous: `Undo` queued in `pendingCommands` (`action: \'QueuedBehindSettlement\'`). Synchronous: Drained/cleared. | Asynchronous: Unchanged. Synchronous: `docRev++` iff restored differs; `sessRev++`. | `Rejected(ConnectionClosed)`; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **16. `redo`** | `Idle` | `-> Idle` (**Admitted**) | Re-applies post-edit text/attrs from top `redoStack` entry within `_runInTransaction`. | Top entry popped from `redoStack`, transferred symmetrically to `undoStack`. | Restored from historical `afterSelection`. | N/A | `docRev++` iff state changes; `sessRev++` | `Rejected(NothingToRedo)` if stack empty; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `Composing` | `-> Composing` (**Rejected**) | Unmodified | Redo stack cleared on active edit. | Unmodified | Unmodified | Unmodified | `Rejected(NothingToRedo)` |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Rejected**) | Unmodified | Redo stack cleared on active edit. | Unmodified | Unmodified | Unmodified | `Rejected(NothingToRedo)` |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |
| **17. `setSelection`** | `Idle` | `-> Idle` (**Admitted**) | Text unmodified; selection updated. | None | Selection validated against document length. | Unmodified | `docRev` unchanged (pure cursor move); `sessRev++` | `Rejected(InvalidSelection)`; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `Composing` | `-> Composing` (**Admitted**) | Text unmodified; selection updated. | None | Selection validated against visible length. | Unmodified | `docRev` unchanged; `sessRev++` | `Rejected(InvalidSelection)`; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `AwaitingSettlement` | `-> AwaitingSettlement` (**Admitted**) | Text unmodified; selection updated. | None | Selection validated against visible length. | Unmodified | `docRev` unchanged; `sessRev++` | `Rejected(InvalidSelection)`; reentrant calls queued in `_reentrantMutationQueue` (FIFO) |
| | `Closed` | `-> Closed` (**Rejected**) | Unmodified | None | Unmodified | Unmodified | Unmodified | `Rejected(ConnectionClosed)` |

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
- **Cancellation Rollback, Token-Identity Surviving Target Resolution & Disjoint Interval Preservation (Finding E & Defect 1)**: Commands targeting active composition cancel with `CompositionCancelled`. Commands targeting explicit document ranges (`TrackedRange`) MUST NOT be transformed via a global scalar net delta ($\Delta = \lvert D_c\rvert - \lvert D_v\rvert$), which breaks down under disjoint opposing edits (e.g. insertion at index 0 and deletion at index 4 with net $\Delta = 0$). Instead, the coordinator projects tracked ranges piece-by-piece against surviving token identities in $D_c$. Collapsed ranges map to piece boundaries. Targets entirely contained in discarded provisional units return `Rejected(TargetDeleted)`; targets beyond document bounds return `Rejected(TargetInvalidated)`. Furthermore, in the presence of intervening restored baseline units, surviving target tokens MUST NOT be merged or expanded into a single min-max bounding box `[min(surviving), max(surviving) + 1]` (Defect 1). Doing so silently formats or mutates unedited intervening text. Instead, `mapTrackedRangeOnCancel` partitions surviving token indices into contiguous maximal intervals and returns disjoint spans: `ranges: [[s0, e0], [s1, e1], ...]`. Formatting or command execution applies strictly to the surviving intervals, preserving the constituent attributes and identity of restored baseline units.
- **Centralized Transactional Dispatcher & Upfront Validation (Defect 2 & Failure 4)**: All external mutating entry points (`dispatchCommand`, `applyInput`, `undo`, `redo`) execute exclusively within the central transactional wrapper `_runInTransaction`. Direct invocation of `dispatchCommand(cmd, target)` mutates document state, updates typing intent, or records history within an active transaction boundary. Inputs and targets are validated upfront before ticket allocation or mutation; malformed commands reject upfront without leaking tickets. Reentrant mutations arriving while `_inTransaction` is active are queued into `_reentrantMutationQueue` (`{ status: 'Accepted', action: 'QueuedReentrant', ticketId }`) and executed sequentially in FIFO order upon outer transaction completion. Furthermore, `_reentrantMutationQueue` is decoupled from transactional state snapshots to ensure queued mutations survive transaction rollback. On any failure or rejection, the pre-transaction state snapshot is atomically restored while ticket outcome records in `commandOutcomes` persist across rollback. `documentRevision` advances biconditionally iff visible document text or attributes changed (`textChanged || attrsChanged`). Commands dispatched while `Idle` execute synchronously (`status: 'Applied'`); commands dispatched while `Composing` or `AwaitingSettlement` queue in `pendingCommands` (`status: 'Accepted'`, recorded as `Pending`).
- **Asynchronous Settlement Barrier & Ordered Settlement Policy on Undo (Defect 3)**: Invoking application `undo()` during active composition or while awaiting settlement cannot unilaterally transition the coordinator to `Idle` or extinguish settlement obligations without native IME coordination. In asynchronous platforms, `undo()` queues behind the settlement obligation in `settlementObligation.pendingCommands` with `{ status: 'Pending', ticketId }` and the coordinator enters or remains in `AwaitingSettlement` with an active settlement fence token. Immediate synchronous turnover is permitted if and only if the adapter explicitly provides synchronous turnover qualification (`syncBoundary: true`) along with concrete settlement evidence. When native settlement completes, pending commands (including queued undo) are drained in causal sequence against the settled document state.
- **Cross-Epoch Receipt Isolation & Connection Fencing**: Sequence receipts are keyed composite-style as `${epoch}:${seq}`. The coordinator validates connection epoch prior to duplicate receipt lookups; packets from defunct connections are rejected (`StaleEpoch` or `FutureEpoch`) without polluting or colliding with sequence numbers of recycled connections.
- **Dynamic Token-Identity Remapping Across Intermediate Undos (Failure 2 Repair)**: When queued drainage sequences contain interleaved `Undo` and `Format` commands (or when intermediate undos restore historical preimages with differing lengths), target ranges cannot rely on static scalar coordinate offsets. The coordinator employs dynamic token-identity tracking via `_remapPendingCommandTargets`. Each command ledger entry retains the token IDs of its original target units. When an intermediate `Undo` reverts document text, surviving tokens are queried against the updated document token stream. The surviving token indices are partitioned into maximal contiguous intervals `[[s0, e0], [s1, e1], ...]`. Commands execute against these surviving intervals rather than being invalidated by stale coordinate bounds (`TargetInvalidated`).

#### SelectionTarget Capture Timing Specification (§4.5)
Unlike `TrackedRange` (which captures explicit token IDs at queue time to survive subsequent text shifts and deletions) and `CompositionTarget` (which tracks the active session ID and its constituent islands), `SelectionTarget` evaluates **dynamically at execution / drainage time** against the active document selection produced by preceding operations (including selection restored or adjusted by intermediate undos, commits, or formatting commands). If preceding operations collapse the selection to a caret `[p, p]`, formatting commands update `typingIntent` without generating document revisions or history entries.

#### Production identity locator: versioned B+ sequence and persistent maps

Use an immutable measured B+ document sequence with fanout B (validation default 16; production initial B=32). Leaves contain pieces `(bufferId, startOffset, length, baseTokenId)`. Logical unit identity is `baseTokenId + offset`, independent of current document position. Allocate disjoint monotonic ID runs; never recycle identities while history or target handles can refer to them. Kotlin uses checked 64-bit IDs and lengths; the JS prototype rejects IDs outside safe-integer range. A piece need not correspond to a formatting run; raw attributes remain independently indexed.

A document version is the tuple `(docRoot, tokenMapRoot, parentMapRoot)`. All three roots publish and restore together. Maps are persistent AVL trees in the validated baseline; do not replace their cost with unverified constant-time map assumptions.

- `TokenMap`: interval predecessor search by token base; entry contains piece range, current leaf object identity, and child index within that leaf. It stores no absolute root path.
- `ParentMap`: for each non-root document node ID, store `(parentNode, childIndex)` for this version. Shared subtrees can have different parents in different versions because this map is persistent.
- Lookup: find the piece interval in TokenMap, sum preceding piece lengths in its leaf, then follow versioned ParentMap links to the root, adding left-sibling subtree lengths. With fixed fanout, point rank costs **O(log² P)**. This is the selected bound, not the previously claimed O(log P).
- Insert: locate the leaf by measured offsets, split an interior piece while preserving its left/right identity intervals, insert the new run, split overflowing nodes, and path-copy ancestors. Rebuild token locators only for copied leaves. Update parent records for children of copied ancestors, including shared subtree roots. Delete obsolete node entries from the new map roots. Existing versions retain their own valid maps.
- Delete: remove affected piece spans in sequence. For a partial piece, remove its descriptor and reinsert surviving prefix/suffix pieces with the same original unit identities. Merge or redistribute underfull siblings and collapse a single-child root. Empty documents use an empty leaf. A range touching K pieces costs O(K B log² P) in this baseline. A bulk-delete optimization requires separate validation.
- Single insertion or piece removal costs O(B log² P) map work and O(log P) document-node traversal for fixed fanout. Target resolution by M unit identities costs O(M log² P) before grouping positions. Do not claim O(log P + K) interval resolution until implemented and tested.
- Undo restores the complete version tuple in O(1) root assignment. Mapping pending targets, history accounting, publication and eventual garbage collection are additional work. An old root can be reclaimed only when no other history, pending basis, layout snapshot or current root retains it.
- Coalescing is optional background work, not a correctness or memory guarantee. It can combine adjacent pieces only when buffer offsets and token IDs are contiguous and attribute indexing stays valid. Arbitrary fragmented edits cannot always coalesce without changing identity representation. Retained snapshots are never mutated by compaction.

The validation prototype is `validation/codex/run-20261015-13/versioned-piece-index.mjs`. It exercises insertions including interior splits, range deletions, sibling rebalancing, old-root lookup and exact index-entry ownership. It does not implement the production Kotlin allocator or hierarchical document schema. The coordinator and index are separate validation models; their Kotlin integration still requires G1 tests.

**Memory accounting.** Text unit count N is separate from piece count P. UTF-16 payload alone is 2N bytes before buffer slack and headers. The four piece fields comprise three Ints and one Long: 20 scalar bytes, with a hypothetical packed/aligned 24-byte record assumption only for estimates. Actual object layout is platform-dependent. Include document nodes/child arrays, both persistent maps and values, retained text slabs, raw attributes, structure, pending basis handles, history roots, layout snapshots and transient path-copy allocations. The estimator accepts N, P, node counts, map entries, history bytes, pending bytes and temporary bytes independently; supplied node sizes are explicit assumptions, not measured heap sizes. The 64 MiB history-retention allowance is separate from the overall engine memory target. Prior 5.16/98.22 MiB engine-heap claims are withdrawn: they mixed piece count with text length and did not measure runtime allocation.


#### Synchronous Settlement Evidence Qualification Contract (§4.5, Failure 3 Repair)
Synchronous settlement (`syncBoundary: true`) bypasses the asynchronous settlement barrier if and only if the platform bridge provides adapter-issued structural settlement evidence (no cryptographic authenticity is established by this model):
1. **Qualification Stamp**: `evidence.adapterQualification` MUST strictly equal `'QualifiedSyncSettlement'`. Unqualified adapters are strictly rejected. Specifically, Desktop AWT `InputMethodRequests` remains unqualified for synchronous settlement until native host traces and qualification tests verify synchronous completion guarantees; all Desktop AWT operations must use the asynchronous settlement barrier.
2. **Epoch and Session Consistency**: `evidence.epoch` must equal `coordinator.epoch`, `evidence.sessionId` must equal `activeSessionId`, and `evidence.generation` must equal the active session revision (`sessionRevision`).
3. **Bounded Extent Verification**: `evidence.extent` must be a valid 2-element array `[start, end]` within document bounds (`0 <= start <= end <= len(docText)`).
4. **Single-Use Unique Nonce**: `evidence.recordId` must be a unique, non-null string that has not been previously consumed in the connection epoch (`consumedSettlementRecords`). Replay or reuse of a record ID is rejected with `InvalidSettlementEvidence`.
5. **Fallback on Failure**: Any failure in settlement evidence qualification causes the coordinator to reject the synchronous shortcut with `Rejected(InvalidSettlementEvidence)` and fall back to the safe asynchronous settlement barrier queueing.

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

### 9.5 Current evidence and historical corrections

Current executable evidence is recorded in `validation/codex/run-20261015-13/results.json` with source hashes, environment, pass/fail details and limitations. The preserved run-12 source hashes are independently checked. Earlier reports and plan revisions remain in their original run directories and in this run's `sources/`; they are historical evidence, not competing normative rules.

The previous review's three runtime counterexamples were repaired by run-12. Run-13 additionally repairs deferred selection validation, published-versus-staged coordinate capture, mutable direct-command capture, mutable notification snapshots, receipt publication before duplicate callbacks, and queue continuation. The old index prototype rebuilt all token locators and did not support interior insertion; it is superseded by the versioned locator algorithm above. Claims that the old tests established bounded update complexity or production heap measurements are withdrawn.

Inherited depth-bounded exploration covers its enumerated sequential actions only. It is not a complete abstraction of native event order, all reentrant schedules, Unicode or hierarchical documents. New boundary tests, generated immediate/deferred comparisons, scheduler tests and randomized persistent-index tests provide separately scoped evidence. Neither model agreement nor mutation detection proves the production engine correct.

### 9.6 Remaining experiments and implementation prerequisites

No production implementation is authorized. Native adapter synchronous query/reentry behavior, genuine settlement evidence, accessibility, projection integration, crash durability and end-to-end latency remain unqualified. The token-index mechanism is specified and executable in a validation prototype; its Kotlin layout, 64-bit arithmetic, memory consumption and latency require Phase 1 measurement before G1 can pass. Full Unicode conformance data must be run for the pinned production version; selected Intl.Segmenter fixtures are not that gate. Fragmentation can exceed a device budget; do not assume coalescing repairs every workload. Resolve those outcomes using the existing explicit product-scope decision rules.
