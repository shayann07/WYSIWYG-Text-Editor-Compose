# Compose Multiplatform document editor — implementation specification
 
Revision: 4.8, 14 October 2026. Status: specification repair and executable validation baseline (Phase 0/1 gated pending plan resolution and native platform qualification; production implementation prohibited). Incorporates comprehensive specification repairs: exact native-range fidelity without silent clamping on target expansion (§4.3, §4.4), clarification of Invariant I08 (cancellation protection of committed content vs explicit native re-targeting and baseline absorption), synchronization of historical committed units upon full envelope absorption (§4.3, §4.4), decoupling of DocumentRevision from metadata-only events (partial commit advances SessionRevision only, DocumentRevision advances purely on semantic text/attribute diffs) (§2.2, §4.4), settlement token ordering, invalidation on new candidate input, and strict rejection contract with non-zero extent tracking (§4.4, §4.5), distinct cancellation semantics (nativeRemoval, endCompositionPreservingText, applicationAbort) (§4.4, §4.5), dynamic mapping of queued command range targets across candidate expansions, contractions, and cancellation rollbacks (§4.5), rebuilt executable invariant assertions matching advertised property names, and comprehensive verification against 11 subclass implementation mutants with authentic BFS model checking across 7,767 soundly deduplicated abstract states and 8,286 transitions (§9). Test traces: `validation/antigravity/run-20261013-04/`.

This replaces v1 as the implementation baseline. The owner has withdrawn the novelty requirement. This is an engineering plan using established techniques, not a breakthrough claim. Earlier plans and research remain evidence, not competing specifications. MUST means an acceptance requirement; a proposal or unrun test is never evidence that a requirement passes.

## 1. Chosen architecture and reasons

Build a headless Kotlin document engine with one transaction owner, independent attributed history, and platform adapters. Render native surfaces with Compose text layout/drawing on Android, iOS and Desktop. Use a semantic DOM surface with ProseMirror input/view machinery on Web, synchronously governed by the Kotlin engine. No native WebViews. No custom bidi index, new shaper, CRDT, or conditional-layout graph is required.

There is one authoritative live document, including accepted provisional text. Native buffers, ProseMirror state, layout and accessibility are projections. Immutable old snapshots are history/recovery data, not competing editable documents.

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

Every callback receives an increasing sequence number on entry. Explicit internal retransmission keeps the original stamp; a genuinely new native callback receives a new number even if its text is identical. Cache accepted receipts for retransmission within the epoch; retain the accepted high-water mark after cache eviction and return `DuplicateReceiptExpired`, not another mutation. Native APIs generally do not supply a document revision or deduplication ID.

Apply each callback synchronously. `beginBatch`/`endBatch` adjust notification depth; they do not delay authoritative text updates across callbacks. Unbalanced batches are closed on connection disposal. One callback containing ordered suboperations uses one private transaction builder. Reentrancy policy: synchronous reads query the currently published snapshot without blocking; reentrant mutations queue in FIFO order and process sequentially after the active transaction completes; any unhandled exception rolls back the staging transaction atomically, leaving the published state unmodified. Adapter-specific will/did-change notifications must be placed at tested stages (§6).

### 4.2 Composition state and ownership

`Idle -> Composing -> AwaitingSettlement -> Idle`. Commands requiring settlement remain queued in strict FIFO order in both `Composing` and `AwaitingSettlement`. Entering or remaining in `AwaitingSettlement` still accepts valid callbacks for that epoch; it is not permission to freeze native input. Subsequent `preedit` callbacks received while in `AwaitingSettlement` maintain the `AwaitingSettlement` state and do not drop, bypass, or cancel the pending settlement request. If `markedRange` is null in `AwaitingSettlement` following a nonterminal `unmark` (`settled: false`) and a new `preedit` callback arrives, the coordinator MUST NOT fault or throw a null-reference exception. Instead, it reallocates `markedRange` anchored at the active selection or explicit payload bounds, captures a fresh baseline, advances `attributionEpoch++`, and transitions back to `Composing` (if no pending commands require settlement fence) or maintains `AwaitingSettlement` with pending commands intact. `Faulted` preserves the last accepted snapshot and a local recovery record.

Composition state contains session ID, an immutable pre-composition document/selection snapshot captured at composition start/mark-existing, marked range (possibly empty), raw protocol selection, attribution epoch, transient clauses/decorations, accepted callback sequence, pending command tickets and settlement evidence. A composition history group is separate from an attribution epoch: partial commits may start new attribution epochs within one undo group.

#### State ownership model

Object lifetimes are decoupled to prevent premature lifecycle teardown:

| State Entity | Lifetime Scope | Owner / Authority | Reset / Invalidation Boundary |
|---|---|---|---|
| Connection Epoch | Native adapter instance lifetime | Platform bridge adapter | Connection close / replacement (`openNewConnection`, `dispose`) |
| Document State | Editor instance lifetime | `editor-core` transaction owner | Never reset; monotonic `DocumentRevision` |
| Session State | User editing session lifetime | `editor-session` | Focus shift / clear |
| Attribution Epoch | Continuous candidate baseline lifetime | `editor-session` attribution engine | Range expansion, partial commit boundary, new composition start |
| Composition History Session | Single user composition gesture | Engine undo stack (`editor-session`) | Terminal settlement (`completeSettlement`, `commit`) or connection turnover |
| Committed Portions | Permanent document text | `editor-core` | Standard editing / deletion |
| Marked Range | Active preedit candidate lifetime | IME input coordinator | Cleared on unmark, commit, or cancel |
| Settlement Obligation | Pending settlement fence lifecycle | Input coordinator | Fulfilled on `completeSettlement` / terminal `commit` |
| Pending Commands | Deferred application format commands | FIFO command queue | Drained on completion; cancelled on `cancel`/focus loss/disposal |

### 4.3 Deterministic attribution of opaque candidate text

This policy fixes the demonstrated `bold(a) + italic(b) -> x -> ab` loss without claiming to infer character identity from opaque replacements.

An attribution epoch stores:

```text
B: immutable attributed baseline slice
C: current raw candidate string in the epoch's live island
island: tracked start/end in one text block
seed: frozen insertion attributes
```

At composition start/mark-existing, B is the selected or marked attributed slice, C is its exact text and seed is resolved once from the insertion/replacement policy. Empty insertion has empty B. Mark-existing without replacement changes no attributes.

For an accepted replacement contained in the island:

1. Apply the exact native text splice to C. Map document positions from this actual splice, not from a later display diff.
2. Compare **B.text with the entire resulting C**, never with the previous temporary candidate.
3. Find maximal common prefix p. Find maximal common suffix s constrained by `p+s <= min(length(B), length(C))`; prefix wins ties for repeated text.
4. Retreat p/s as needed so retained-copy boundaries do not bisect a valid surrogate pair in either input. This is attribute-copy alignment, not rounding the native splice.
5. Copy B's raw attributes for prefix and suffix; apply seed throughout the changed middle. If C equals B.text, restore B's full raw attribution. Do not use an arbitrary diff alignment to invent correspondence in repeated text.
6. Patch only differing attributes in the live island, without emitting a second text replacement, moving the raw selection, restarting input, or creating a separate history entry.

The invariant is `attributes(C) = reconcile(B, C, seed)` within the epoch. Internal unchanged text outside the retained prefix/suffix receives seed; this conservative policy is intentional. It cannot recover hidden native intent, and does not promise the attributes of every visually equal internal substring.

Partial commit: first reconcile the accepted resulting text, then freeze all committed portions. Start a new attribution epoch from the **current attributed remaining marked range**; keep the same composition history group and seed. Later edits to that suffix cannot change frozen prefix attributes. Equality with the initial text across a partial-commit boundary does not promise restoration of pre-session attributes; a new attribution epoch has explicitly begun.

If a new marked range or explicit replacement extends outside the island, start a new attribution epoch using the smallest contiguous current text-block envelope covering the old marked range and new target, before applying the new mutation. Already settled content enters only through this explicit new native target. Target expansion preserves exact native-range fidelity: when a composition target expands after one or more partial commits, the coordinator MUST NEVER silently clamp `newStart` to `committedExtent[1]`. Native range fidelity requires that `markedRange` admitted to the native platform equals the exact requested `[newStart, newEnd]`. Invariant I08 protects committed content from subsequent suffix cancellation and uncoordinated edits, but when an explicit native re-targeting event (`targetExpansion`) deliberately includes earlier offsets (`newStart < committedExtent[1]`), the overlapped committed units are absorbed into the active candidate baseline for the new attribution epoch (if `newStart <= committedExtent[0]`, the entire committed portion is absorbed into the active baseline; if `committedExtent[0] < newStart < committedExtent[1]`, the prefix remains committed and suffix is absorbed). Surplus characters swallowed from the original document outside the previous marked range are tracked symmetrically: characters preceding the previous marked range (`newStart < oldMarked[0]`) become `expansionPrefixSurplus`, while characters following the previous marked range (`newEnd > oldMarked[1]`) become `expansionSuffixSurplus`, preserving their exact raw attributes. If cancellation occurs after target expansion, both prefix and suffix surplus characters and their attributes are restored to the document surrounding any remaining frozen units, uncommitted candidate characters are excised, remaining frozen committed units remain intact in the document under Invariant I08, and exactly 1 `Composition` history entry is recorded for the net partial commit(s). Non-overlapping target changes retire the prior island and start at the new target. The trace records the reset reason. Do not silently extend across an atom or structural boundary.

Plain autocorrection outside an active composition uses the same one-shot baseline policy on its explicit target. Rich paste bypasses candidate attribution and uses its supplied sanitized slice. The core must distinguish these origins.

### 4.4 Event transition table

The coordinator processes platform input events and application commands across the following exact multi-dimensional transitions:

| Event / Trigger | State Transition | Resulting Text, Attributes & Selection | Pending Commands Behavior | History Groups | Revision Changes | Acknowledgement / Receipt |
|---|---|---|---|---|---|---|
| **Explicit Start** (`startComposition`) | `Idle -> Composing` | Text and attributes unchanged; `markedRange = [start, end]`; pins immutable `compositionBeforeSnapshot`. | Queued in FIFO order if format arrives; existing empty. | None | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(CompositionStarted)` |
| **Implicit Preedit** (`preedit` in `Idle`) | `Idle -> Composing` | Slices candidate into active selection `[anchor, focus]` (never offset 0); pins pre-composition snapshot; reconciles attributes against baseline `B`; preserves exact raw `payload.selection` if supplied (else `[newEnd, newEnd]`). | Queued in FIFO order if format arrives; existing empty. | None | If text/attrs change: `SessionRev++`, `DocRev++`; if identical: `SessionRev++`, `DocRev` unchanged | `Accepted(PreeditUpdated)` |
| **Continuing Preedit** (`preedit` in `Composing`) | `Composing -> Composing` | Slices candidate into `markedRange`; reconciles attributes against immutable baseline `B`; preserves exact raw `payload.selection` if supplied (else `[newEnd, newEnd]`). | Remains queued in FIFO order. | None | If text/attrs change: `SessionRev++`, `DocRev++`; if identical: `SessionRev++`, `DocRev` unchanged | `Accepted(PreeditUpdated)` |
| **Preedit while Awaiting** (`preedit` in `AwaitingSettlement`) | `AwaitingSettlement -> AwaitingSettlement` (or `Composing` if 0 commands) | Slices candidate into `markedRange` (or reallocates `markedRange` anchored at selection/bounds if null following nonterminal unmark without crashing); reconciles attributes; maintains pending settlement request; preserves raw selection. | Commands remain queued in strict FIFO order; no settlement bypass. | None | If text/attrs change: `SessionRev++`, `DocRev++`; if identical: `SessionRev++`, `DocRev` unchanged | `Accepted(PreeditUpdated)` |
| **Partial Commit** (`partialCommit`) | `Composing -> Composing` | Freezes committed prefix units into `frozenPrefixUnits`; advances `attributionEpoch++`; remaining suffix units form baseline for next epoch; `markedRange` narrows; invalidates outstanding settlement tokens. | Remains queued in FIFO order. | None (same composition group) | `SessionRevision++`; `DocumentRevision` unchanged (metadata-only freeze; doc buffer identical) | `Accepted(PartialCommitted)` |
| **Target Expansion** (`targetExpansion`) | `Composing -> Composing` | Advances `attributionEpoch++`; preserves exact native range fidelity (`markedRange = [newStart, newEnd]`, no silent clamping); earlier committed units absorbed into active baseline if `newStart <= committedExtent[0]` or split if `committedExtent[0] < newStart < committedExtent[1]`; tracks prefix/suffix surplus outside previous envelope; captures baseline `B`; invalidates outstanding settlement tokens. | Remains queued in FIFO order. | None | `SessionRevision++`; `DocumentRevision` unchanged until splice | `Accepted(TargetExpanded)` |
| **Commit Replacement** (`commit` with text) | `Composing / AwaitingSettlement -> Idle` | Slices committed text into `markedRange` (or selection); clears `markedRange = null`; calculates `finalCommittedExtent` from `sessionStartOffset`. | Drains queued commands in FIFO order against resolved `finalCommittedExtent`. | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision++` | `Accepted(Committed)` |
| **Direct Commit in Idle** (`commit` in `Idle`) | `Idle -> Idle` | Replaces active selection `[start, end]`; initializes `sessionStartOffset = start`; calculates extent `[start, start + len(commitText)]`. | Executed immediately. | Creates 1 `Composition` history group. | `SessionRevision++`; `DocumentRevision++` | `Accepted(Committed)` |
| **Finish / Unmark (Terminal)** (`unmark`, `settled: true`) | `Composing / AwaitingSettlement -> Idle` | Accepts marked candidate text verbatim as committed; clears `markedRange = null` and transient state. | Drains queued commands in FIFO order against accepted extent. | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision` unchanged (already published) | `Accepted(Unmarked)` |
| **Unmark (Non-terminal)** (`unmark`, `settled: false`) | `Composing -> AwaitingSettlement` | Accepts marked candidate text verbatim into document; clears `markedRange = null`; retains `activeSessionId` and `compositionBeforeSnapshot`. | Commands remain queued in FIFO order awaiting platform completion fence. | Deferred until explicit completion fence arrives (`completeSettlement`). | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(Unmarked)` |
| **Repeating Unmark** (`unmark` in `AwaitingSettlement`) | `AwaitingSettlement -> AwaitingSettlement` | Duplicate unmark received when `markedRange == null`; no mutation; extent and session preserved. | Commands remain queued in FIFO order. | None (no duplicate history group). | `SessionRevision` unchanged; `DocumentRevision` unchanged | `Rejected(AlreadyUnmarked)` |
| **Complete Settlement** (`completeSettlement`) | `AwaitingSettlement -> Idle` | Enforces valid `settlementToken` and non-zero tracked extent; rejects with `CandidateStillActive` if candidate active or nonterminal; rejects if token stale/mismatched (`SessionMismatch`, `StaleEpoch`, `ObsoleteSettlementToken`); clears `activeSessionId = null` and `compositionBeforeSnapshot = null`. | Drains queued commands in FIFO order against dynamically mapped resolved `finalCommittedExtent` (never `[0, 0]`). | Creates 1 `Composition` history group (plus 1 `Formatting` group per drained command). | `SessionRevision++`; `DocumentRevision` unchanged (unless drained formatting modifies doc) | `Accepted(Settled)` |
| **Cancellation (No Partial Commits)** (`cancel`) | `Composing / AwaitingSettlement -> Idle` | Cleanly restores pre-composition snapshot `compositionBeforeSnapshot.text`; `markedRange = null`. Supports `nativeRemoval`, `endCompositionPreservingText`, and `applicationAbort`. | Commands targeting composition cancelled (`CompositionCancelled`); unrelated commands targeting explicit ranges dynamically remapped and executed (`Applied`). | None (transient preedit erased; 1 `Formatting` group if unrelated command executed). | `SessionRevision++`; `DocumentRevision++` (reverts buffer) | `Accepted(Cancelled)` |
| **Cancellation (After Expansion / Partial Commits)** (`cancel`) | `Composing / AwaitingSettlement -> Idle` | Prefix and suffix surplus restored to document; frozen prefix units remain committed under Invariant I08; uncommitted candidate in `markedRange` removed; `markedRange = null`. Supports `nativeRemoval`, `endCompositionPreservingText`, and `applicationAbort`. | Commands targeting composition cancelled (`CompositionCancelled`); unrelated commands targeting explicit ranges dynamically remapped across rollback delta and executed (`Applied`). | Creates 1 `Composition` history group representing net partial commit(s) (plus 1 `Formatting` group per executed unrelated command). | `SessionRevision++`; `DocumentRevision++` | `Accepted(Cancelled)` |
| **Application Format Command** (`dispatchAppCommand`) | `Composing -> AwaitingSettlement` or `AwaitingSettlement -> AwaitingSettlement` | Buffer unchanged; format command stored with ticket ID, epoch, and session ID in FIFO queue. | Queued in FIFO order; watchdog arms at 2s for diagnostics only. | None (deferred) | `SessionRevision++`; `DocumentRevision` unchanged | `Deferred(ticketId)` (or `Applied` if Idle) |
| **Focus Loss** (`onFocusLost`) | `Composing / AwaitingSettlement -> Idle` | Dependent on strategy: 'settle' accepts marked text via unmark; 'cancel' aborts via cancel rules above. | Commands targeting active composition cancelled (`FocusLost`). | Group finalized per settlement or partial commit rules. | `SessionRevision++`; `DocumentRevision` updated if modified | `Accepted(FocusLost)` |
| **Connection Replacement** (`openNewConnection`) | `* -> Active` (`compositionState = Idle`) | Resets dangling composition state; advances connection `epoch++`; resets sequence counter. If active composition modified text, finalizes history to undoStack. | Stale connection commands expired (`StaleEpoch`). | Pushes 1 `Composition` history group if document was modified before reset; retains stack. | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(ConnectionOpened)` |
| **Disposal** (`dispose`) | `* -> Closed` (`compositionState = Idle`) | Rejects further operations; clears transient metadata. If active composition modified text, finalizes history to undoStack. | All pending commands cancelled (`Disposed`). | Pushes 1 `Composition` history group if document was modified before disposal. | `SessionRevision++`; `DocumentRevision` unchanged | `Accepted(Disposed)` |
| **Stale Epoch Callback** | No transition (retains current state) | Rejected without document modification. | Unaffected. | None | `SessionRevision` unchanged; `DocumentRevision` unchanged | `StaleEpoch(incomingEpoch, currentEpoch)` |
| **Duplicate Callback** | No transition (retains current state) | Rejected without document modification. | Unaffected. | None | `SessionRevision` unchanged; `DocumentRevision` unchanged | `Duplicate(cachedReceipt)` or `DuplicateReceiptExpired` |
| **Reentrant Callback** | Queue / Snapshot read | Synchronous reads observe current snapshot without blocking; reentrant mutations queue in FIFO order and process sequentially; exceptions roll back atomically. | Unaffected. | None | None | Queued mutation or instantaneous snapshot read |

Do not defer a native mutation merely because it resembles a queued application command. Commands generated by the platform and application UI have distinct origins.

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
- **Cancellation Rollback**: Commands targeting the active composition cancel with `CompositionCancelled`. Commands targeting explicit document ranges outside the composition are dynamically remapped across the net rollback delta (`-len(uncommittedCandidate) + len(surplusRestored)`), drained in FIFO order, and executed (`Applied`) against the restored buffer state.

#### Distinct Cancellation Semantics

The coordinator distinguishes three distinct cancellation mechanisms:
1. `nativeRemoval`: The native platform deletes or backspaces candidate characters. Uncommitted candidate text is excised, earlier frozen prefix units remain committed under Invariant I08, and any prefix/suffix surplus from target expansion is restored.
2. `endCompositionPreservingText`: The platform IME finishes or unmarks without emitting a replacement text payload, accepting marked candidate text verbatim into the document as committed text.
3. `applicationAbort`: Explicit cancellation triggered by the application or user (e.g. Escape key, toolbar action, programmatic cancellation), rolling back uncommitted candidate mutations to the pre-composition snapshot or post-expansion baseline while preserving frozen committed units.

On verified completion: finalize the attributed history group, clear transient metadata, map the pending command target through accepted edits, validate it, then execute a separate transaction/history group. A command targeting active composition follows the final committed extent by explicit `CompositionTarget(sessionId)`. The coordinator resolves `CompositionTarget(sessionId)` strictly to the final committed range `[sessionStart, sessionStart + committedLength)` resulting from that session:
1. Standard replacement: `[sessionStart, sessionStart + len(committedText))`.
2. Candidate growth: `[sessionStart, sessionStart + len(expandedText))`.
3. Candidate shrinkage: `[sessionStart, sessionStart + len(shrunkText))`.
4. Empty final candidate (cancellation/deletion): `[sessionStart, sessionStart)` resolves to an empty range. Formatting an empty range updates typing intent only, producing NO document revision bump and NO history group. Queued commands targeting an aborted composition cancel with `CompositionCancelled`.
5. Partial commits: `[sessionStart, sessionStart + totalFrozenPrefixLength + len(finalCommitText))`.
6. Target expansion: `[newSessionStart, newSessionStart + len(committedText))` covering the expanded envelope.
7. Direct commit in Idle state: `[start, start + len(commitText))` calculated from active selection `[start, end]`, never defaulting to document offset 0.
Never default or fall back to the whole document! Surrounding differently attributed text outside this extent remains strictly untouched. If a command specifies `CompositionTarget(sessionId)` with a mismatched session ID, it is rejected with `TargetSessionMismatch`. An ordinary unrelated range uses registered anchors and cancels with `TargetDeleted` if its intended content vanished. A collapsed typing toggle applies at the final logical caret. This avoids independently mapping swallowed endpoints into an unintended range.

If settlement is unsupported, keep the command pending until natural completion. The UI shows “Finish text conversion to apply this action” and permits cancelling the command. A watchdog after two seconds updates status/diagnostics only; it never commits or replays text. Preserve FIFO ticket order; cancellation removes only that ticket. On focus loss or disposal, pending commands targeting active composition are cancelled with an explicit result (`FocusLost` or `Disposed`), never applied to a different session or editor. When composition cancellation occurs, commands targeting the active composition are cancelled (`CompositionCancelled`), but commands targeting unrelated explicit document ranges are preserved, drained in FIFO order, and executed (`Applied`) against the restored document state, ensuring unrelated commands are never trapped indefinitely. If cancellation occurs after partial commits, frozen committed units remain in the document under Invariant I08, a single composition history group is recorded for the net partial commit, and pending commands targeting the aborted candidate are cancelled (`CompositionCancelled`).

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

Ordinary contiguous typing merges only with matching location, attributes, origin and direction, within 750 ms and without an intervening selection/command barrier. A composition, including partial commits, is one group. Formatting, paste and structural commands are separate groups after composition settlement. If a connection is replaced (`openNewConnection`) or disposed (`dispose`) while an unfinished composition has modified document text, the coordinator must finalize exactly one `Composition` history group to the `undoStack` before tearing down the session. Transient composition text must never remain in the document without attributed undo history. Typing toggles alone are session state; insertion records the resulting intent. New editing after Undo clears Redo.

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
| U4 | Web ProseMirror Synchronous Kotlin Admission | NOT RUN | Phase 0 Gate G0 & Phase 5 Gate G5 | Synchronous bridge destroys candidate DOM nodes; requires asynchronous fallback. |
| U5 | 100K-Unit Single Paragraph Layout Latency | HIGH RISK / UNMET | Phase 4 Gate G4 & Phase 8 Gate G8 | Large single paragraph stalls UI thread; requires document block limit or off-thread layout. |
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

Implementation choices still requiring evidence: compatible dependency pins, each adapter's completion/notification mechanism, public host embedding, Unicode-data version, actual font files, safe layout thread ownership and declared device/keyboard/browser/AT matrix. The core models, attribution policy, history policy, command semantics and failure rules above are fixed defaults; coding agents should implement and test them, not redesign them independently.

The next action is independent validation of this specification followed by Phase 0 capability freeze. No production correctness or performance result is claimed merely by writing this document; production implementation remains prohibited until acceptance gates pass on native targets.
