# Production document editor for Compose Multiplatform

## 1. Chosen architecture and why it defeats the alternatives

**Build one authoritative Kotlin document engine, with Compose-rendered editing surfaces on Android, iOS, and Desktop, and a semantic DOM editing surface on Web. Integrate directly with each platform’s text-input and accessibility systems.**

Use Compose’s text layout and drawing capabilities on native targets. Do not implement a new font shaper. On Web, use ProseMirror’s view and browser-input machinery as a controlled projection of the Kotlin model. No WebView is involved.

This is the strongest architecture I can defend for your stated requirements. It is an architectural selection, **not a claim of experimentally proven superiority**. Its highest-risk assumptions must pass the early experiments below before full implementation proceeds.

Your prompt is sufficient; a new session is unnecessary. The important refinement is to replace “ultimate” with explicit correctness obligations, workloads, and failure gates.

### Fixed requirements

- Structured, continuous documents first: rich paragraphs, headings, nested lists, quotes, code blocks, tables, images, and atomic embeds.
- Local editing first. Preserve a practical path to collaboration without implementing it.
- Identical document semantics across platforms; allow native input behavior and layout differences.
- No WebView editing surface.
- Correctness takes priority over delivery shortcuts.
- Initial large-document workload: **1 million UTF-16 code units across 10,000 blocks**.
- Pagination, headers, footers, and footnotes remain future features. The document model must accommodate additional flows and layout projections without replacing its foundational addressing system.

### What current Compose actually provides

Research baseline: **9 October 2026**.

Compose Foundation 1.12’s rich-text APIs are real. Compose Multiplatform **1.12.1** is based on Foundation **1.12.1**, including on non-Android targets. Android uses Google’s AndroidX artifacts. [Foundation releases](https://developer.android.com/jetpack/androidx/releases/compose-foundation), [Compose Multiplatform dependencies](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html), [version compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).

The APIs solve meaningful parts of the problem:

| Capability | What it provides | What it does not establish |
|---|---|---|
| `TextFieldBuffer.addStyle()` | Editable span and paragraph styles, with tracked ranges | A hierarchical document model |
| `TrackedRange` | A handle whose range follows buffer edits | A persistent document anchor |
| `ExpandPolicy` | Explicit inclusion/exclusion of insertions at range boundaries | Complete rules for typing intent, hyperlinks, replacement, or structural boundaries |
| `TextFieldState` | Text, selection, composition, and style state | A document-wide transaction protocol across independent blocks and tables |
| `TextLayoutResult` | Hit testing, selection paths, line metrics, and bidi-aware horizontal positions | Document layout, virtualization, or arbitrary native text-input geometry |

The new tracking overloads are distinct from the older styling support used in output transformations. [TextFieldBuffer reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer), [ExpandPolicy reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/ExpandPolicy).

I inspected the tagged 1.12.1 source and published artifacts:

| Target | Evidence of shipping | Runtime evidence obtained here |
|---|---|---|
| Android | Multiplatform metadata depends on AndroidX Foundation 1.12.1 | No device execution |
| Desktop | Published JAR contains `TrackedRange`, `ExpandPolicy`, and `TextFieldBuffer` classes | No interactive IME execution |
| iOS | Published ARM64 KLIB contains the corresponding metadata; public implementation exists in common source | No simulator/device execution |
| Web/Wasm | Published Wasm KLIB contains the corresponding metadata; implementation exists in common source | No browser IME execution |

These are artifact-level findings, not merely release-announcement inference. The [published module metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.12.1/foundation-1.12.1.module) identifies the platform variants. The common [1.12.1 feature flag](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/ComposeFoundationFlags.kt) enables styled text by default.

Several limitations are concrete:

1. **`TrackedRange` has buffer-scoped lifetime.** Its source explicitly says not to retain the handle. A style that collapses to zero length is removed. It therefore cannot represent a durable bookmark or the user’s future typing attributes. [Tagged implementation](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TrackedRange.kt).
2. **The 1.12.1 default saver does not preserve style state.** Its saved fields are text, selection, and text undo state. AndroidX documents style saving as a subsequent 1.13 change. [Tagged saver](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldState.kt), [release history](https://developer.android.com/jetpack/androidx/releases/compose-foundation).
3. **The inspected undo operation stores plain strings and selections.** It is not a sufficient history representation for document structure and complete formatting restoration. [Tagged undo representation](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/internal/undo/TextUndoOperation.kt).
4. **Input implementation differs by platform.** The iOS native input path introduced in 1.11 uses `UITextInput`/`UIKeyInput` and remains an explicitly selected experimental path in the documented API. [iOS release documentation](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html).
5. **The public non-Android input request is not a general document-geometry interface.** It exposes a `TextLayoutResult` and field rectangles. Adapting a virtualized document with tables to that contract requires care; common API availability does not make arbitrary document integration automatic. [Tagged request interface](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/platform/PlatformTextInputMethodRequest.skiko.kt).

The genuine remaining work is therefore **structured editing, persistent positions, explicit typing intent, document history, incremental document layout, accessibility, and verified platform coordination**.

It is no longer defensible to justify a new engine by saying Compose cannot edit styled text. Nor does this research establish that existing Compose rich-text libraries are universally broken. [Compose Rich Editor](https://github.com/MohamedRejeb/compose-rich-editor), for example, is an existing multiplatform implementation and a useful comparison baseline.

### Architecture comparison

Storage, rendering, input integration, and replication are independent choices. A rope does not solve IME; a CRDT does not solve caret geometry; canvas does not solve accessibility.

| Candidate | Strongest advantage | Decisive weakness for this scope | Decision |
|---|---|---|---|
| One styled `BasicTextField` | Maximum reuse of Compose input, selection, and styling | A flat field is an awkward authority for nested structures, tables, independent flows, and large-document layout | Best option for a smaller rich-text field; retain as baseline |
| One `BasicTextField` per block | Straightforward block virtualization | Cross-block selection, composition continuity, keyboard focus, and undo require coordination across separate editors | Reject as the document foundation |
| Native rich-text widgets on every platform | Strong native input and accessibility behavior | Four editing/layout systems must be constrained to one structural and formatting semantics; custom embeds and layout expose different limits | Serious competitor, but excessive semantic reconciliation for this scope |
| Canvas everywhere with direct platform bridges | Maximum rendering control and shared native layout code | Browser input and accessibility remain substantial additional systems; uniform pixels do not eliminate platform differences | Reject as the initial universal surface |
| Custom shaper/layout/runtime, potentially in Rust/C++ | Control over geometry and potential deterministic layout | Introduces font fallback, shaping, bidi, line breaking, FFI, and accessibility responsibilities before proving they are necessary | Reject for v1 |
| CRDT-first document engine | Distributed convergence becomes foundational | Adds concurrency semantics and metadata without resolving local input or layout; collaboration is not required yet | Defer |
| Mature browser editor inside native WebViews | Broad document functionality and browser-input reuse | Violates the hard constraint | Exclude |
| **Shared Kotlin engine; native Compose surfaces; browser DOM surface** | Shared semantics with platform-appropriate input and accessibility integration | Two surface implementations and a strict projection boundary must be maintained | **Choose** |

The browser decision is consequential. EditContext is designed for applications that render their own text, but still has limited browser availability. The W3C explainer also documents why hidden editable proxies introduce input and accessibility problems. [EditContext specification](https://www.w3.org/TR/edit-context/), [current availability](https://developer.mozilla.org/en-US/docs/Web/API/EditContext), [W3C explainer](https://github.com/w3c/edit-context/blob/gh-pages/explainer.md).

A DOM surface has its own composition hazards. The choice is to reuse mature handling of those hazards while keeping the document authority in Kotlin. ProseMirror exposes synchronous transactions through `dispatchTransaction`, making that boundary more explicit than wholesale synchronization between independently owned editor states. [ProseMirror interface](https://prosemirror.net/docs//ref/).

The selected design borrows established ideas deliberately: immutable snapshots, schema-constrained trees, attributed text storage, and mapped positions. Lexical independently demonstrates the value of separating document state from DOM representation; VS Code’s buffer work demonstrates why measured text structures matter. Neither supplies the complete system specified here. [Lexical state model](https://lexical.dev/docs/concepts/editor-state), [VS Code text-buffer design](https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation).

### Architectural boundary

```text
Keyboard / IME / pointer / accessibility / application command
                              │
                   Platform input adapter
                              │
                 Synchronous edit coordinator
                              │
           Kotlin document engine + session + history
                              │
                Immutable snapshot + edit receipt
                  ┌───────────┴─────────────┐
                  │                         │
       Compose text layout/drawing    Browser DOM projection
       Android / iOS / Desktop        ProseMirror view
                  │                         │
                  └──── Geometry / accessibility queries ────┘
```

There is exactly one authority for document semantics. Platform buffers and browser state are projections with explicitly controlled temporary composition ownership.

---

## 2. Core data models, interfaces, and algorithms

### Document model

Use a **schema-constrained tree of blocks**, with **persistent attributed text trees inside text blocks**.

Do not use one enormous `AnnotatedString`, HTML string, Markdown string, or mutable collection of overlapping style spans as the canonical document.

The initial schema is:

| Node | Content |
|---|---|
| Document | Body flow, resources, schema version |
| Flow | Ordered blocks |
| Paragraph / heading | Inline text, soft breaks, inline atoms |
| Code block | Plain text including literal line feeds |
| Quote | Blocks |
| List | List items; ordered/unordered attributes |
| List item | At least one text block, followed by permitted nested blocks |
| Table | Rectangular rows and cells |
| Table cell | Its own flow containing blocks |
| Block atom | Image or registered atomic embed |

V1 tables have no merged cells or nested tables. These are feature exclusions, not assumptions embedded into position encoding.

A `FlowId` identifies a distinct editable text scope. The body is one flow; each table cell is another. Additional named flows can later represent footnote bodies or other document regions. Page boundaries remain layout output, not text characters or mandatory document nodes.

Every structural node has a stable 128-bit identifier:

- Splitting preserves the left node’s ID and creates a right ID.
- Joining preserves the left node’s ID.
- Moving a node preserves its ID.
- Undo restores the original IDs.
- Copy/paste generates new IDs.
- IDs are serialized as strings, never JavaScript floating-point numbers.

### Positions and selections

Four different concepts must remain separate:

1. **UTF-16 offset:** what most relevant input protocols address.
2. **Logical document position:** where an edit occurs.
3. **Anchor association:** which side of newly inserted material a position follows.
4. **Visual/typing affinity:** which caret stop and formatting context the user intended.

A representative public contract:

```kotlin
data class NodeId(val high: Long, val low: Long)
data class FlowId(val node: NodeId)

@JvmInline
value class Revision(val value: Long)

enum class Association { Before, After }
enum class TypingSide { Left, Right }
enum class LineAffinity { Upstream, Downstream }
enum class BidiAffinity { Primary, Secondary }

sealed interface DocPoint {
    data class Text(
        val flow: FlowId,
        val block: NodeId,
        val utf16Offset: Int
    ) : DocPoint

    data class Gap(
        val flow: FlowId,
        val parent: NodeId,
        val childIndex: Int
    ) : DocPoint
}

data class PositionRef(
    val revision: Revision,
    val point: DocPoint,
    val association: Association
)

sealed interface Selection {
    data class TextRange(
        val anchor: PositionRef,
        val focus: PositionRef,
        val lineAffinity: LineAffinity,
        val bidiAffinity: BidiAffinity
    ) : Selection

    data class Node(val node: NodeId) : Selection

    data class Cells(
        val table: NodeId,
        val anchorCell: NodeId,
        val focusCell: NodeId
    ) : Selection

    data class Blocks(
        val first: NodeId,
        val last: NodeId,
        val backwards: Boolean
    ) : Selection
}

interface EditorEngine {
    fun snapshot(): EditorSnapshot
    fun dispatch(command: EditorCommand): EditResult
    fun applyInput(batch: InputBatch): InputResult
    fun checkpoint(): CheckpointResult
}

interface PositionMap {
    fun map(position: PositionRef): MapResult
    fun mapSelection(selection: Selection): Selection
}

data class EditReceipt(
    val before: Revision,
    val after: Revision,
    val positionMap: PositionMap,
    val changedNodes: Set<NodeId>,
    val layoutInvalidation: LayoutInvalidation,
    val historyGroup: HistoryGroupId?,
    val inputAcknowledgement: InputAcknowledgement?
)
```

These are architectural signatures. Supporting types must be defined in the shared contracts module before platform implementation begins.

A `PositionRef` is meaningful only at its revision. It must be mapped or rejected when used against another revision. Long-lived bookmarks use registered anchor handles, not saved raw offsets.

### Text and attribute storage

Implement an immutable measured B+ tree:

- Branch capacity: 32.
- Text slabs: at most 4,096 UTF-16 units.
- Leaves contain text-piece references and interned attribute-set IDs.
- Aggregate subtree measures include UTF-16 length and structural projection length.
- Never split a valid surrogate pair merely to satisfy slab capacity.
- Physical pieces may remain separate; the logical run iterator coalesces adjacent equal attribute sets.
- A text edit path-copies affected tree nodes.
- A structural node store maps IDs to immutable nodes; parent/order indexes update transactionally.

Attributes are a canonical map with at most one value per key. An absent key means inheritance; explicit values such as `bold=false` can suppress inherited formatting.

Store semantic attributes, not Compose objects:

- Font family reference, size, weight, slant.
- Foreground/background color.
- Underline and strike.
- Inline-code flag.
- Link identity and destination.
- Language and direction overrides where supported.

Derive `SpanStyle`, `ParagraphStyle`, and browser CSS from these attributes.

Initial core edit complexity should be proportional to:

```text
O(log document structure + log affected text + inserted text + affected runs)
```

This is not a claim that shaping, browser projection updates, or every anchor repair has logarithmic cost.

### Primitive operations

The transaction executor supports a small closed set:

| Operation | Required behavior |
|---|---|
| `SpliceInline` | Replace an inline range with an attributed slice |
| `SetInlineAttributes` | Patch attributes over a range without altering text |
| `SplitTextBlock` | Split content and move right-side positions to the new ID |
| `JoinTextBlocks` | Append right content and remap right positions into left |
| `SpliceChildren` | Insert/remove exact structural children |
| `MoveNodes` | Move existing nodes while preserving identity |
| `SetNodeAttributes` | Change block/container properties |

Each operation returns:

- Updated persistent structure.
- Its position map.
- An inverse containing necessary preimages.
- Affected layout and semantic nodes.

High-level commands—Enter, paste, delete selection, indent, insert table—compile into these primitives. Platform adapters cannot mutate storage directly.

### Position mapping

For a replacement of `[a,b)` by `m` UTF-16 units in one text block, let `δ = m − (b − a)`.

For insertion, where `a = b`:

```text
p < a  → p
p > a  → p + m
p = a  → a       for Before
p = a  → a + m   for After
```

For a nonempty replacement:

```text
p < a      → p
p = a      → a
a < p < b  → a or a + m, according to association; report deleted
p = b      → a + m
p > b      → p + δ
```

Structural mappings are explicit:

- Split at `k`: offsets below `k` remain left; offsets above `k` move right with `−k`; an offset exactly at `k` follows association.
- Join: right offsets become `leftOldLength + offset`.
- Move: positions inside moved nodes preserve identity.
- Delete node: report deletion and provide a landing position computed from the pre-edit tree.
- Delete a cell flow: map external references to the deleted table/cell boundary in its enclosing structure, rather than retaining a nonexistent flow.

**Selection mapping is a separate algorithm.** If both endpoints are swallowed by the same replacement, collapse to the replacement’s designated landing point. Do not independently map endpoints and accidentally reverse the selection. Otherwise map the logical range, preserve its original direction, and validate the resulting selection type.

Deletion is not invertible through position maps alone. Undo uses stored selection preimages.

A finite check of 81,900 cases validated bounds and same-association monotonicity for this mapping rule. It also exposed why unrestricted monotonicity across differently associated deleted positions would be an invalid invariant. This is a small specification check, not a proof of the complete engine.

### Native text projection

Create an indexed projection of the active flow:

- Text contributes its UTF-16 units.
- A soft break contributes `\n`.
- A structural boundary between projected blocks contributes a tagged `\n`.
- Inline and block atoms contribute tagged `U+FFFC`.
- A table is an atom in its parent flow; its cells expose their own flows.

The projection retains segment metadata. Equal character values do not make a structural separator equivalent to a soft break.

Required functions:

```kotlin
interface TextProjection {
    val revision: Revision
    val flow: FlowId
    val utf16Length: Int

    fun read(start: Int, end: Int): String
    fun toDocument(offset: Int, association: Association): DocPoint
    fun toPlatform(point: DocPoint): Int
    fun classify(start: Int, end: Int): List<ProjectionSegment>
}
```

The logical coordinate system covers the whole active flow. Surrounding-text queries materialize only requested slices. **Do not repeatedly recenter an input window around the caret.** Recentring changes the meaning of offsets held by the IME.

If a platform requires bounded payloads, clipping is a transport detail with correct offset metadata; it must not silently change the logical document origin.

### Layout

On native targets:

- Measure text blocks with Compose text layout.
- Draw from cached paragraph layouts.
- Draw selection, composition decorations, caret, and document decorations separately.
- Use a measured block-height index for viewport lookup.
- Keep the active block and visible neighborhood laid out.
- Preserve a model-position scroll anchor when measured heights change.
- Use double precision for accumulated document coordinates; convert to floats after subtracting the viewport origin.
- Invalidate by dependency: content, width, font metrics, direction, and applicable style changes.
- Do not reshape unrelated paragraphs after a local edit.

Compose provides useful caret and selection geometry, including bidi-sensitive horizontal positions. Those facilities should be reused and tested. [TextLayoutResult](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextLayoutResult).

A long paragraph is an explicit performance risk: shaping or bidi resolution can require work proportional to that paragraph. Splitting it at arbitrary storage boundaries is not a valid optimization.

---

## 3. Formal invariants and verification strategies

The implementation must distinguish **settled document state** from **live provisional input state**.

A live snapshot may contain IME preedit text. A checkpoint is a settled, serializable document.

| Invariant | Precise obligation | Verification |
|---|---|---|
| **I1 — Single semantic authority** | Only the transaction executor changes document content, structure, or persistent attributes | Restrict mutation APIs; adapter contract tests |
| **I2 — Atomic publication** | Each published snapshot contains a mutually consistent document, selection, composition state, and revision | Reentrant callback tests; randomized observer schedules |
| **I3 — Schema validity** | Unique IDs; valid child types; no cycles; every required editable flow has a legal caret destination | Property tests after every operation; malformed import corpus |
| **I4 — Position validity** | All live positions resolve at their declared revision and lie within the addressed node | Exhaustive small documents; random split/join/move/delete sequences |
| **I5 — Mapping correctness** | Unaffected content positions retain identity; deleted positions explicitly report deletion; range mapping preserves its specified collapse/direction rules | Reference sequence model and structural model |
| **I6 — Unicode discipline** | Committed text is well-formed UTF-16; user navigation/deletion respects extended grapheme clusters; protocol offsets retain native UTF-16 meaning | Unicode conformance data plus platform traces |
| **I7 — Attribute determinism** | Every committed text unit has one canonical attribute set; identical input state and command yield identical attributes | Boundary matrix; insertion/deletion/formatting permutations |
| **I8 — Composition preservation** | An accepted native replacement is reflected exactly once in the live projection; ending composition alone never reinserts its text | Stateful protocol fuzzing; recorded IME replay |
| **I9 — Epoch isolation** | A callback from a closed input connection cannot mutate a later connection’s document state | Late callback, blur/refocus, reconnect, and disposal tests |
| **I10 — Geometry coherence** | A geometry result identifies the layout/document revision it describes; stale layouts cannot masquerade as current geometry | Delayed-layout scheduling; font and viewport race tests |
| **I11 — Undo correctness** | Undo of a settled history group restores its exact document, IDs, attributes, and recorded selection; redo restores the after-state | Structural equality and canonical serialization comparisons |
| **I12 — Projection equivalence** | Accepted browser/native proposals and engine receipts describe the same text changes; temporary native composition markup has a bounded declared exception | Differential projection tests after each callback |
| **I13 — Persistence integrity** | Loading a successfully checkpointed document preserves all supported content and attributes; incomplete journal tails cannot become valid transactions | Crash injection at every write boundary |
| **I14 — Bounded hot path** | A one-character edit in a normal paragraph does not flatten, serialize, or relayout the whole document | Allocation counters, traversal counters, and scaling benchmarks |

### Unicode rules

Pin **Unicode 18.0** segmentation data for shared editing commands. Implement extended grapheme segmentation in common Kotlin, with official conformance tests. Use native shaping and bidi layout; do not infer visual order from code-point order. [UAX #29](https://unicode.org/reports/tr29/), [UAX #9](https://unicode.org/reports/tr9/).

Important qualifications:

- UTF-16 units, Unicode scalar values, grapheme clusters, and glyphs are different.
- Native IMEs may address positions that are not grapheme boundaries.
- Therefore, do not round every incoming native range to a grapheme boundary.
- Shared user commands such as Backspace operate on graphemes; an explicit native range replacement follows the native protocol.
- Never apply NFC/NFKC normalization while editing.
- Provisional input may temporarily contain incomplete UTF-16. Preserve its raw units in the input session; display isolated surrogates as replacement glyphs without changing offset lengths.
- At settlement, replace any remaining isolated surrogate with `U+FFFD`, length-preservingly, and acknowledge the correction.
- Normalize user-visible settled caret positions to legal grapheme boundaries. During composition, retain the protocol selection separately from its drawable caret representation.

Segmentation must work across storage pieces. Re-scan until segmentation state and unchanged suffix converge; do not assume a fixed number of neighboring characters is sufficient.

### Formal and executable verification

Build three independent models:

1. **Simple reference editor:** strings, ordinary trees, and explicit run lists; deliberately slow.
2. **Production editor:** persistent measured structures.
3. **Protocol model:** input epochs, callback order, composition lifetime, batching, and settlement.

Use the reference model as a differential oracle. It must not reuse the production mapping or normalization code.

Model-check the protocol’s small state space for:

- Commit received twice.
- Composition end before/after a final replacement.
- Connection closed while a callback is queued.
- Selection change during settlement.
- Unbalanced native batch boundaries.
- Reentrant queries during a mutation.
- Disposal while a command waits for composition to finish.

Specify liveness conditionally: settlement requires the platform adapter to deliver its completion fence or report failure. A timer alone is not evidence that composition finished.

---

## 4. IME composition and selection state machines

### Ownership and scheduling

Each document has one editing owner thread and one active input lease in v1.

- Android: UI thread.
- iOS: main thread.
- Desktop: owning AWT event thread.
- Web: browser main thread.

Background work may parse imports, load resources, and compute approved layout work from immutable snapshots. It cannot directly mutate the editor.

Native callbacks must apply synchronously to the live model. Do not place the authoritative edit behind recomposition, `snapshotFlow`, a debounce, or an asynchronous state-store round trip.

Each adapter assigns:

```kotlin
data class InputStamp(
    val epoch: Long,
    val sequence: Long
)

data class InputBatch(
    val stamp: InputStamp,
    val operations: List<InputOperation>
)
```

The stamp is assigned when the adapter receives the callback. Native protocols generally do **not** supply an engine revision; the implementation must not pretend otherwise.

Input operations include:

- Replace an explicit range.
- Replace the current composition, otherwise the selection.
- Set selection.
- Set/clear composition range.
- Finish composition while preserving text.
- Update composition decorations.
- Begin/end notification batching.

One callback containing several operations executes against one transaction builder. Reentrant reads see the builder’s current state.

Native batching coalesces notifications and history. It must not leave the model indefinitely stale while waiting for an `endBatchEdit` that may never arrive.

### Connection states

```text
Detached → Opening → Active → Closing → Detached
                        └──────────────→ Faulted
```

Rules:

- Opening allocates a fresh epoch and exposes one consistent initial projection.
- Closing continues to accept callbacks explicitly belonging to the closing adapter’s drain phase.
- The adapter’s completion fence closes the epoch.
- Callbacks arriving after closure are rejected as stale.
- A new epoch cannot reuse the old epoch’s interpretation of offsets.
- Fault handling preserves the latest accepted text and pending diagnostic trace.

### Composition states

```text
None
  └─ begin/mark/preedit → Composing
                           ├─ preedit/selection/partial commit → Composing
                           └─ finish or external command → Settling
                                                          └─ fence → None
```

`Composing` stores:

- Composition session ID.
- History-group starting snapshot and selection.
- Current native marked range, which may initially be absent or empty.
- Frozen insertion attributes.
- Raw protocol selection.
- Current decoration ranges.
- Native callback trace metadata.
- Pending commands that require settlement.

There is one live document. Its persistent snapshots make it cheap to retain the before-state. Do not maintain two independently editable “real” and “shadow” documents.

### Required transitions

| Event | Action |
|---|---|
| Plain committed input in `None` | Apply replacement; update selection; create/merge ordinary typing history |
| Mark existing text | Enter composition without altering text or document formatting |
| Preedit without an existing marked range | Replace the native selection; begin composition history group |
| Preedit with an existing marked range | Replace that marked range; update its extent and protocol selection |
| Explicit-range native replacement | Use the explicit range, even if different from the previous marked range |
| Partial commit plus remaining preedit | Apply both exactly once; move the marked range to the remaining suffix; keep the composition group open |
| Commit text | Replace the relevant native range, clear composition, and settle the group |
| Finish/unmark | Preserve current text; remove only composition metadata |
| Composition-end notification | End the lifecycle; do not insert the event’s text again |
| Native cancellation | Apply the actual native replacement/deletion, then clear metadata; do not infer restoration from an empty string |
| Native selection adjustment | Update protocol selection according to that platform’s contract |
| Application caret move outside composition | Settle first, then apply the requested move through the resulting position map |
| Formatting, paste, structural edit, undo | Queue behind settlement |
| Blur/disposal | Drain as supported, preserve latest accepted text, settle or record recovery state, then close epoch |
| Stale callback | Ignore mutation, record a diagnostic counter |

**Cancellation is not universally “restore the pre-composition document.”** Android deletion of composing text, browser cancellation, and reconversion are different event sequences. The adapter must supply the actual mutation. A generic rollback would incorrectly erase committed prefixes or restore text the user intentionally deleted.

Likewise, do not impose a universal invariant that selection must equal the composition end or always lie inside composition. Platform contracts differ.

### Settlement barrier

`settleComposition(reason)` is an adapter operation with an explicit completion result.

1. Retain the latest accepted live snapshot.
2. Ask the platform to finish/unmark its active composition using the supported mechanism.
3. Process the callbacks generated by that operation.
4. Receive the adapter’s completion fence.
5. Finalize the history group.
6. Perform deferred structural interpretation and committed-text validation.
7. Map and execute the queued command.
8. Resynchronize or reopen the input connection only if required.

Do not routinely blur/refocus, recreate a field, or restart input after each keystroke.

The exact fencing behavior is a **mandatory platform experiment**. If an adapter cannot establish a safe completion boundary, it must report failure instead of guessing after a delay. A watchdog detects a stuck bridge; it does not manufacture an IME commit.

### Composition formatting

At composition start, capture a formatting seed. Repeated preedit updates use that seed rather than resampling the styles adjacent to the changing composition.

For native rewrites:

- Preserve equal prefix/suffix text and its attributes within the explicitly replaced range.
- Apply frozen insertion attributes to the changed middle.
- If replacement text is identical, do not accidentally change formatting.
- Keep IME underlines and conversion-clause decorations outside persistent document attributes.
- Defer toolbar formatting and automatic link/Markdown conversion until settlement.

Structural characters arriving during composition remain literal provisional input. On settlement, interpret them according to the originating command and block type. Do not rebuild the surrounding structure on every intermediate preedit update.

### Selection interaction states

Selection interaction is orthogonal to composition:

```text
Idle
Dragging(pointerId, anchor)
NativeAdjustment
ProgrammaticSynchronization(expectedSelection)
```

Rules:

- Dragging pins its logical anchor; scrolling changes geometry, not that anchor.
- Pointer capture survives viewport movement.
- A native selection echo is suppressed only when it matches an expected synchronization operation.
- Do not suppress all selection events during a “programmatic update” interval.
- Preserve anchor/focus direction.
- Vertical navigation retains a preferred visual x-coordinate.
- Horizontal navigation follows actual visual caret stops, including bidi and wrapped-line affinity.
- The same logical offset may have multiple valid visual caret positions.

### Mandatory example traces

1. **Repeated preedit:** `"n" → "ni" → "に" → "日本"` produces one final insertion, one composition history group, and stable surrounding formatting.
2. **Reconversion:** mark an existing styled word; send identical text; change a substring; cancel or commit. Unchanged formatting must survive.
3. **Partial commit:** accept a committed prefix while a suffix remains marked. Later cancellation must not remove that prefix.
4. **Focus race:** close epoch 7, open epoch 8, receive a late epoch-7 replacement. Epoch 8 remains unchanged.
5. **Toolbar during preedit:** click Bold. Composition settles once; Bold then applies according to the preserved selection/typing intent.
6. **Surrogate boundary:** native UTF-16 selections and deletions around emoji must not be interpreted as code-point counts.

---

## 5. Formatting and boundary-attribute rules

### Typing attributes are explicit session state

A caret has no text extent. Its formatting cannot be represented reliably by a zero-length style span.

Use:

```kotlin
sealed interface TypingIntent {
    data class Inherit(val side: TypingSide) : TypingIntent

    data class Explicit(
        val base: AttributeSet,
        val overrides: AttributePatch
    ) : TypingIntent
}
```

An explicit patch supports both setting and removing/suppressing a property. “Bold off” must not be encoded merely by deleting a key when the surrounding block supplies bold.

### Attribute resolution

For ordinary insertion:

1. If typing intent is explicit, resolve its captured base plus overrides.
2. Otherwise sample the chosen logical side of the caret.
3. If that side does not exist, use the available side.
4. If the block is empty, use its stored empty-block attributes over block defaults.
5. Apply attribute-specific boundary restrictions.
6. Insert text with the resolved attributes.
7. Retain the resulting typing intent for contiguous typing.

Do not union the left and right attribute sets. A boundary between bold text and italic text does not imply bold-italic typing.

### Normative boundary table

| Situation | Rule |
|---|---|
| Inside a uniform run | Inherit that run |
| Between unequal runs | Use `TypingSide`; never union both sides |
| Caret at block start | Prefer the right run |
| Caret at block end | Prefer the left run |
| Empty block | Use empty-block attributes and block defaults |
| Pointer placement | Use the logical side represented by the hit-tested glyph edge |
| Logical forward movement into a gap | Inherit from the side traversed from the left |
| Logical backward movement into a gap | Inherit from the side traversed from the right |
| Visual bidi movement | Derive logical side from the actual traversed caret stop |
| Programmatic placement without a side | Left, except block start uses right |
| Collapsed Bold/Italic/etc. toggle | Change typing intent; do not create an empty style range |
| Noncollapsed formatting command | Apply to the selected text; preserve selection direction |
| Replace a selection with ordinary typing | Seed ordinary attributes from the first selected character in document order |
| Replace a selection backwards | Same formatting result as the equivalent forward selection |
| Backspace/Delete leaving content | Recompute inheritance from surviving content on the command’s relevant side |
| Deletion empties the block | Preserve the pre-deletion effective typing attributes as empty-block attributes |
| Genuine caret relocation | Clear explicit typing overrides and resample |
| Toolbar focus change without relocation | Preserve typing intent |
| IME preedit | Use frozen composition attributes |
| Rich paste | Use the fragment’s sanitized attributes |
| Plain-text paste | Use destination typing attributes |

Collapsed typing toggles are session state until text is inserted. They are not serialized as invisible document spans.

### Attribute-specific behavior

**Ordinary presentation attributes**

Bold, italic, color, size, and underline follow the selected typing side. They do not need independently overlapping sticky intervals in canonical storage.

**Links**

- A link has an identity distinct from its URL.
- Automatic inheritance occurs only strictly inside the same link identity.
- At an exterior link boundary, ordinary typing is outside the link.
- An explicit “type as link” command may establish link typing intent.
- Whitespace ends explicit link typing intent and is inserted outside the link.
- Adjacent links with equal URLs are not automatically merged if their identities differ.

**Inline code**

- Automatically inherited only inside the code run.
- Explicit code typing intent permits continuation at its edge.
- Clearing code formatting affects typing intent or the selected range; it does not rely on inserting a space to escape.

**Annotations**

Comments, search results, spelling suggestions, and collaboration decorations are separate anchored annotations. Their expansion policies do not dictate typing attributes.

### Applying a range format

For every affected text block:

1. Split logical attribute runs at selection boundaries.
2. Patch attributes in the selected interval.
3. Preserve text and unrelated keys.
4. Coalesce adjacent equal logical runs.
5. Emit attribute and layout invalidation.
6. Create one history group for the user command.

Toggle semantics are deterministic:

- If every selected character is effectively bold, set bold off throughout.
- Otherwise set bold on throughout.
- Toolbar mixed state is computed independently from the toggle action.

### Unicode and formatting boundaries

Committed user-visible formatting boundaries align with extended grapheme boundaries.

When an edit merges previously separate text into one grapheme—for example, adding a combining mark or completing an emoji ZWJ sequence—use the attributes of the grapheme’s first scalar for the resulting cluster. Perform this repair at settlement, not during native composition.

This is an explicit product rule. It prevents a single rendered grapheme from acquiring contradictory per-code-unit formatting. Imports with unsupported internal grapheme formatting are normalized using the same rule.

### Structural editing rules

| Command/context | Result |
|---|---|
| Enter in paragraph | Split paragraph |
| Enter at heading end | Create following paragraph |
| Enter within heading | Split into headings of the same level |
| Shift+Enter | Insert soft break |
| Enter in code block | Insert literal line feed |
| Enter in nonempty list item | Split item; following nested content belongs to the resulting tail item |
| Enter in empty list item | Lift one nesting level; at outermost level become a paragraph |
| Enter in empty quote paragraph | Exit one quote level |
| Backspace at start of ordinary block | Join the previous compatible text block; retain left block attributes |
| Backspace at start of first list-item paragraph | Outdent/lift before attempting an ordinary join |
| Delete across a table boundary | Treat the table as an atomic parent-flow node |
| Delete inside a cell | Never merge cells implicitly |
| Enter inside a cell | Split a paragraph within that cell |
| Tab in last cell | Add a row and move to its first cell |
| Delete a cell selection | Clear selected cells while preserving table structure |
| Type over a cell selection | Clear selected cells; insert into the top-left selected cell |

Cross-flow text selections are not represented as invalid text ranges:

- Between cells of one table, promote to rectangular cell selection.
- Between a cell and outside its table, promote to a block selection containing the table.
- Between body paragraphs surrounding a table, a body text range may include the whole table atom.

---

## 6. Cross-platform integration contracts

### Shared adapter contract

Every platform adapter must provide:

```kotlin
interface InputAdapter {
    fun open(
        epoch: Long,
        state: InputStateReader,
        sink: InputSink,
        geometry: EditorGeometry
    )

    fun acknowledge(receipt: EditReceipt)
    fun settle(reason: SettlementReason, completion: SettlementCallback)
    fun close()
}

interface EditorGeometry {
    fun caret(position: PositionRef, affinity: CaretAffinity): GeometryResult
    fun selection(selection: Selection): GeometryResult
    fun hitTest(point: SurfacePoint): HitResult
    fun characterBounds(range: DocumentRange): GeometryResult
    fun ensureVisible(position: PositionRef)
}
```

Geometry results include their document/layout revision and coordinate space.

Geometry must account for:

- Scroll and nested scroll.
- Density and device-pixel ratio.
- Zoom.
- Keyboard insets.
- Rotation/resizing.
- Font loading.
- Clipping.
- Window-to-screen transformations.

For currently edited text, answer using current measured geometry. If a protocol permits unavailable geometry, return that explicitly. Otherwise synchronously measure the required active block. Never attach a new revision number to old rectangles.

### Android

Use the public platform text-input integration to create a custom `InputConnection`, backed by the document projection. Do not make an invisible `BasicTextField` the document authority. [Compose platform input API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/PlatformTextInputSession).

Required support:

- `commitText`, `setComposingText`, `setComposingRegion`, `finishComposingText`.
- `setSelection`.
- UTF-16 and code-point variants of surrounding deletion.
- Surrounding-text and extracted-text queries.
- Batch notifications.
- Cursor-anchor geometry.
- Editor actions and rich-content insertion where supported.
- Hardware keyboard commands without duplicating IME-generated text.

For `newCursorPosition`, implement Android’s actual rule: positive values are relative to the inserted text’s end minus one; nonpositive values are relative to its start. Code-point deletion must count code points, not UTF-16 units. [InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

Use connection epochs across restart/recreation. Normal text acceptance must not restart input.

### iOS

Implement a `UIView` input host conforming to `UITextInput` and `UIKeyInput`, attached to the Compose editing surface.

The view owns native input interaction, while the Kotlin engine owns text and document semantics.

Required behavior:

- Revision-aware `UITextPosition` and `UITextRange` wrappers.
- Selected and marked ranges.
- `setMarkedText`, replacement, insertion, deletion, and `unmarkText`.
- Tokenization queries.
- Caret rectangles, selection rectangles, and closest-position queries.
- Appropriate input-delegate notifications.
- Native editing interactions and context menus.
- Dictation and reconversion traces.

`setMarkedText` replaces existing marked text, or otherwise the selection. Its `selectedRange` is relative to the supplied marked text. `unmarkText` preserves text. [UITextInput](https://developer.apple.com/documentation/uikit/uitextinput), [marked-text method](https://developer.apple.com/documentation/uikit/uitextinput/setmarkedtext(_:selectedrange:)).

Compose’s existing native input implementation is valuable reference material, but its internal classes must not become an undocumented dependency of the library.

### Desktop

Implement an AWT input host/focus component exposing `InputMethodRequests` and receiving input-method events. Render the document through the Compose surface.

Handle:

- Committed prefix plus uncommitted suffix in one event.
- Composed-text caret and hit testing.
- Screen-coordinate candidate geometry.
- Committed-text queries that exclude uncommitted text from both contents and indexing.
- Reconversion and supported “undo commit” behavior.
- Dead keys, keyboard layouts, and focus/window transitions.

These indexing rules are part of AWT’s contract. [InputMethodRequests](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/im/InputMethodRequests.html).

Verify Windows, macOS, and Linux separately. AWT integration is not evidence that every OS input method or accessibility bridge works correctly.

Use the public JVM/host APIs. Any necessary runtime-specific workaround belongs in a versioned adapter module with a reproducing test.

### Web

Use a browser DOM editing region hosted alongside the Compose Web UI. The Kotlin engine remains compiled for the Web target; a small JavaScript/TypeScript adapter owns ProseMirror integration.

Initial dependency baseline observed during research:

- `prosemirror-view`: 1.42.6.
- `prosemirror-model`: 1.25.12.
- `prosemirror-state`: 1.4.4.
- `prosemirror-transform`: 1.12.2.

Lock exact versions and integrity hashes in Phase 0. Upstream repositories have moved from the archived GitHub mirrors; follow the publisher’s current repository references. [Project forwarding notice](https://github.com/prosemirror/prosemirror).

The integration protocol is:

1. Derive a ProseMirror schema matching the supported document schema.
2. Preserve engine node IDs in structural node attributes.
3. Maintain an indexed mapping between browser positions and engine positions.
4. Intercept editor commands, formatting, paste, cut, and structural keys and route them to the engine.
5. Let the browser/ProseMirror process native composition.
6. Translate resulting replacement steps into synchronous engine input operations.
7. Apply the engine receipt before the callback returns.
8. Adopt the candidate browser state when it matches the engine projection; otherwise apply the smallest permitted correction.
9. Preserve composing DOM nodes and ancestors until settlement.
10. Use only engine history; do not enable an independent ProseMirror history or collaboration plugin.

Supported native proposals are text/fragment replacement, selection changes, and composition metadata. Structural proposals are translated into the same engine primitives; unsupported step classes are bridge faults, not permission to import the entire browser document wholesale.

Do not rely solely on canceling `beforeinput`. Composition edits can be noncancelable, and other browser modifications can bypass the anticipated path. [Input Events Level 2](https://www.w3.org/TR/input-events-2/), [browser behavior notes](https://developer.mozilla.org/en-US/docs/Web/API/Element/beforeinput_event).

For large documents:

- Preserve semantic DOM content.
- Use `content-visibility: auto` with intrinsic-size estimates where tested.
- Keep active composition and selection endpoints fully rendered.
- Avoid virtualizing away text required by browser selection or assistive technology.
- Do not serialize the whole document across the Wasm/JavaScript boundary per edit.

`content-visibility: auto` can skip rendering while retaining DOM and accessibility content; its interaction with editable selection still belongs in the test matrix. [CSS behavior](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/content-visibility).

ProseMirror’s internal updates may scale with ancestor child counts. The browser surface must therefore be benchmarked independently; the core’s complexity bound does not automatically apply to it.

### Accessibility is part of the engine boundary

Provide a semantic document interface with:

- Stable node identities and reading order.
- Text-range queries.
- Selection and editing actions.
- Heading/list/table relationships.
- Link destinations and image alternatives.
- Focus and geometry.
- Navigation into content outside the rendered viewport.

Use platform accessibility adapters rather than copying the entire document into an invisible text field after every edit.

Test editable behavior, not merely whether a screen reader announces the document’s title. Custom UIKit elements require explicit accessibility implementation. [UIKit accessibility](https://developer.apple.com/documentation/uikit/accessibility-for-uikit).

### Clipboard and imports

Support:

- Plain text.
- A versioned rich-fragment MIME format.
- A defined sanitized HTML subset.
- TSV for rectangular table data.

Pasted fragments receive fresh node IDs. Unsupported HTML constructs convert deterministically to supported content with an import report. HTML and Markdown are interchange formats, not canonical storage.

Atomic embeds store typed data and resource references; they do not execute imported HTML.

---

## 7. Transactional editing and undo/redo

### Transaction pipeline

```text
Validate epoch/revision
→ Resolve command and typing intent
→ Build primitive operations
→ Apply operations to private persistent builder
→ Map positions and selection
→ Validate affected schema/attributes
→ Record inverse and history metadata
→ Publish one snapshot and receipt
→ Notify platform and observers
```

Document mutation cannot reenter publication. Observer-generated commands are queued for a subsequent transaction.

An application command with a stale revision is rejected unless it explicitly supplies mappable anchors and passes conflict checks. An asynchronous import must not silently apply to whatever selection happens to exist when parsing finishes.

### History groups

One history group contains:

- Before/after document references or equivalent retained slices.
- Forward and inverse operations.
- Before/after selection.
- Before/after relevant typing state.
- Origin and input-session metadata.
- Grouping reason.

Grouping rules:

- Contiguous ordinary typing can merge when attributes, location, origin, and direction match and no intervening command occurred.
- Use a 750 ms inactivity threshold as an additional boundary.
- Whitespace/punctuation may close a typing group at the end of the inserted chunk; they do not alter text.
- A complete composition session is one group, including reconversion or partial commits.
- Paste, structural commands, and range-format commands each form separate groups.
- Caret relocation closes the current ordinary typing group.
- A new document edit after Undo clears Redo.
- Collapsed typing toggles do not create document history until materialized by insertion.
- Undo requested during composition settles it and then undoes the resulting group.

Undo must restore formatting, structural IDs, tables, links, and selection. Plain-text diffs are insufficient.

Set an initial history budget of **1,000 groups or 64 MiB of retained history payload**, whichever is reached first. Evict whole oldest groups. Account for retained slabs/subtrees, not just operation-object sizes.

V1 history is session-local. Persisting undo across restarts is excluded from the first release; the document and crash-recovery journal remain durable.

### Persistence and recovery

Use a versioned canonical document format independent of Compose, ProseMirror, and physical rope structure.

Persist:

- Schema version.
- IDs and tree structure.
- Text and semantic attributes.
- Resources and embed data.
- Supported extension payloads.

Exclude:

- Layout caches.
- Native ranges and input epochs.
- Composition decorations.
- Browser DOM.
- Runtime typing overrides.

Use atomic checkpoints plus an append-only transaction journal:

- Native storage: framed records with length and checksum; atomic checkpoint replacement.
- Web: atomic IndexedDB transactions.
- Recovery applies only complete valid records.
- A “saved” acknowledgement follows durable completion, not merely queueing a write.

For active composition, retain a separate recovery record of the latest accepted working input. Recovery restores its visible text as ordinary settled text and does not attempt to resume an old native IME session. Preserve raw provisional UTF-16 losslessly in that recovery format.

### Collaboration compatibility

Keep:

- Stable node identities.
- Explicit operations and origins.
- Mapping and inverse information.
- A headless deterministic reducer.
- Renderer-independent positions.
- A replaceable transaction admission layer.

Do **not** claim that these features alone make the document collaborative. Concurrent insertion, formatting intent, tree moves, deletion, and selective undo require additional semantics. Peritext demonstrates why plain-text convergence is insufficient for rich formatting. [Peritext research](https://www.inkandswitch.com/peritext/).

A future collaboration implementation may replace internal text addressing/storage and migrate documents while preserving the public command and rendering contracts. Avoid promising a zero-work CRDT retrofit.

---

## 8. Phased implementation blueprint

Start with **Kotlin 2.4.20**, matching Compose compiler plugin, and **Compose Multiplatform 1.12.1**. These are current documented stable baselines. Freeze the complete compatible toolchain in the capability manifest rather than using floating dependency versions. [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Compose compiler configuration](https://kotlinlang.org/docs/multiplatform/compose-compiler.html).

Organize the implementation into independent modules:

- Core model/transactions.
- Unicode and positions.
- Input protocol and coordinator.
- Native layout/surface.
- Android, iOS, Desktop, and Web adapters.
- Accessibility adapters.
- Persistence/import.
- Conformance tests and benchmarks.

All agents implementing subsystems use the same contracts and golden traces. Changes to core semantics require a specification change and updated cross-platform tests.

### Phase 0 — Falsify the architecture’s risky assumptions

**Deliverables**

- Four minimal runnable input probes.
- A styled `BasicTextField` baseline.
- One custom native paragraph surface per native target.
- One browser DOM/ProseMirror projection prototype.
- A capability manifest recording exact OS, runtime, dependency, browser, font, and keyboard versions.
- Recorded traces and performance measurements.

**Tests**

- Foundation style tracking and expansion on each target.
- Default saver and undo behavior, including known limitations.
- Native composition, reconversion, candidate geometry, selection, and focus.
- Basic screen-reader reading and editing.
- Browser synchronous round-trip through Kotlin.
- 10,000-block browser projection.
- 100,000-unit paragraph layout.

**Gate**

The proposed public integration paths must support the required text and geometry contracts. No reliance on reflection or inaccessible internal classes. Unknown input fencing, broken accessibility editing, or destructive composition synchronization blocks the corresponding architecture path.

This phase is deliberately first. Building a sophisticated document model before testing these bridges would leave the largest feasibility risks untouched.

### Phase 1 — Headless document kernel

**Deliverables**

- Schema, stable IDs, canonical attributes.
- Persistent structural and attributed-text trees.
- Primitive operations, inverse operations, and serialization.
- Independent simple reference implementation.

**Tests**

- Random valid and invalid trees.
- Split/join/move/delete round trips.
- Attribute-run normalization.
- Serialization equality.
- Resource and unknown-version handling.

**Gate**

At least one million seeded differential operations in nightly testing, zero unexplained divergence, and minimized reproducible failures for every discovered defect.

### Phase 2 — Positions, selections, and formatting

**Deliverables**

- Position maps and registered anchors.
- Selection types and promotion rules.
- Unicode segmentation.
- Typing intent and the complete boundary table.
- High-level structural commands.

**Tests**

- Every boundary rule in both selection directions.
- Every operation around empty blocks and document edges.
- Unicode conformance data.
- Bidi logical/visual affinity examples.
- Deletion swallowing both selection endpoints.
- Combining marks across storage/style boundaries.

**Gate**

All invariants applicable to settled headless editing pass. No platform adapter may redefine these semantics.

### Phase 3 — Native layout and pointer editing

**Deliverables**

- Compose text-block layout and drawing.
- Viewport and height indexes.
- Caret-stop and selection geometry.
- Pointer hit testing, dragging, scrolling, and keyboard navigation.
- A read-only semantic document adapter.

**Tests**

- Ligatures, mixed bidi, wrapped boundaries, tabs, soft breaks, and inline atoms.
- Font fallback/loading.
- Zoom and density changes.
- Scroll anchoring after edits above the viewport.
- Stale asynchronous layout rejection.

**Gate**

Visible caret and selection geometry agree with the rendered text. Local editing does not trigger whole-document layout.

### Phase 4 — Input coordinator and real platform input

**Deliverables**

- Epoch and composition state machines.
- Synchronous callback transactions.
- Settlement barriers.
- Android, iOS, and Desktop production adapters.
- Native trace recorder/replayer.

**Tests**

- Real keyboards and recorded traces.
- Partial commit, reconversion, cancellation, and dictation.
- Selection during composition.
- Toolbar, blur, rotation, window changes, and disposal.
- Unbalanced batches and stale callbacks.

**Gate**

Zero lost/duplicated text in the supported test matrix. Native queries reflect accepted edits immediately. Every settlement path has a demonstrated completion mechanism.

### Phase 5 — Browser surface

**Deliverables**

- ProseMirror schema and incremental projection.
- Position/step translation.
- Composition ownership handling.
- Shared command integration and engine-owned history.
- DOM performance controls.

**Tests**

- Chrome/Edge, Firefox, Safari.
- Android browser and iOS Safari input.
- Noncancelable edits, spell correction, drag/drop, and paste.
- Stable composing nodes during core receipts.
- No whole-document Wasm/JavaScript serialization per edit.

**Gate**

The same normalized command traces produce the same canonical document as native targets. Browser input traces preserve text, styles, and history under composition.

### Phase 6 — Structured documents and accessibility

**Deliverables**

- Lists, quotes, code blocks, tables, images, and atomic embeds.
- Clipboard/import pipeline.
- Editable accessibility on every target.
- Cross-block, block, and cell selection.

**Tests**

- Nested structures and table edge cases.
- TalkBack and VoiceOver.
- NVDA on Windows.
- Desktop macOS VoiceOver and Linux accessibility testing.
- Browser screen-reader reading/editing.
- Navigation into offscreen content.
- Large selection copy, deletion, and undo.

**Gate**

Each target supports reading, caret movement, selection, editing, and structural navigation through its supported assistive technologies. Announcing static text alone does not pass.

### Phase 7 — Durability and history

**Deliverables**

- Complete rich-document Undo/Redo.
- Checkpoints, journal, and composition recovery.
- History and cache budgets.
- Import/export diagnostics.

**Tests**

- Crash injection before/after each persistence boundary.
- Recovery from incomplete records.
- Undo/Redo across every structural operation.
- Memory retention after history eviction.
- App suspension during composition.

**Gate**

Every durable acknowledgement survives restart. Every retained history group restores exact supported document state.

### Phase 8 — Performance, release qualification, and maintenance

These are **acceptance targets, not measurements already achieved**.

Use release builds, fixed fonts, warm/cold runs reported separately, and reference hardware no weaker than a Pixel 6, iPhone 12, and a contemporary four-core desktop with 16 GiB RAM.

| Workload | Initial acceptance target |
|---|---|
| Local core edit in a normal paragraph | p95 ≤ 2 ms; p99 ≤ 5 ms |
| Normal active-paragraph layout, up to 4,000 UTF-16 units | p95 ≤ 8 ms |
| Accepted input callback to presented update at 60 Hz | p95 ≤ 33 ms, excluding time spent inside the external IME |
| Scrolling the 1M-unit/10K-block document | p95 frame work ≤ 16.7 ms |
| 100K-unit single-paragraph edit | p95 callback-to-presentation ≤ 100 ms; reported separately |
| Local edit scaling | No full-document text extraction, serialization, or native relayout |
| Core plus bounded history on standard corpus | ≤ 128 MiB, excluding renderer/runtime and decoded media |
| Repeated open/edit/close | No monotonic retained-memory growth |

The pathological long-paragraph budget is deliberately weaker. If the product requires normal-frame latency for that workload too, incremental paragraph layout becomes a separate substantial requirement.

**Mandatory corpus**

- English prediction/autocorrection.
- Chinese Pinyin.
- Japanese conversion/reconversion.
- Korean Hangul.
- Arabic, Urdu, and Hebrew mixed with Latin and numerals.
- Devanagari and Thai.
- Dead-key accents.
- Emoji modifiers, flags, and ZWJ sequences.
- Very long combining sequences.
- Long unbroken paragraphs.
- Heavy formatting fragmentation.
- Tables containing empty and multiline cells.
- Dictation and hardware keyboard input.

**Release gate**

All four platform families pass their correctness and accessibility gates. Publish exact tested configurations and measured performance. A platform does not become production-ready merely because the common code compiles.

Dependency upgrades rerun the capability suite. Diagnostics record operation categories, timing, revisions, and failure signatures; document contents are not automatically uploaded.

---

## 9. Adversarial self-review, unresolved risks, and decisive experiments

### Attack 1: “You rejected universal canvas too quickly.”

Canvas offers excellent rendering control. It remains the selected native surface.

The browser objection is not that canvas cannot work. It is that canvas plus input plus accessible document semantics is a larger initial system, while EditContext cannot yet be the sole browser contract.

**Decisive experiment:** compare canvas/EditContext plus required fallback against the DOM surface using identical content, real mobile IMEs, and screen readers. Measure total engineering obligations and failures, not drawing throughput alone.

**Current decision:** ship DOM on Web. A canvas Web surface is a later alternative behind the same engine contract if it passes an equivalent capability suite.

### Attack 2: “ProseMirror introduces a second editor and may destroy the single-authority model.”

This is the strongest objection to the browser design.

The boundary must remain synchronous and narrow. Browser state is a projection, independent history is disabled, and every accepted mutation receives an engine receipt.

**Decisive experiment:** perform CJK composition across style boundaries while translating every browser transaction through Kotlin. Check document equality, node preservation, selection, and composition continuity after each callback.

**Failure consequence:** stop the browser integration phase. Do not conceal mismatches with full-document replacement. Revise the adapter boundary before proceeding.

### Attack 3: “Native canvas accessibility could cost more than using native text widgets.”

Correct. A custom surface can lose badly here.

**Decisive experiment:** Phase 0 must demonstrate editable text navigation and selection through VoiceOver, TalkBack, and the selected Desktop accessibility paths—not just normal keyboard input.

**Failure consequence:** a richer native host/accessibility bridge is required before release. If the host cannot expose the contract reliably, reconsider that platform’s surface while retaining the shared core.

Native widgets remain legitimate competitors; they were rejected as the universal document authority, not declared intrinsically inferior.

### Attack 4: “A rope will not save a 100,000-character paragraph.”

Correct. Storage and layout are separate bottlenecks.

**Decisive experiment:** measure shaping, bidi resolution, line breaking, hit testing, and allocation independently for the pathological paragraph.

**Failure consequence:** investigate incremental paragraph/run layout with correct contextual boundaries. Do not split the paragraph into visually independent chunks merely to meet a benchmark.

A stricter long-paragraph target may justify a different layout backend. That decision remains experimental.

### Attack 5: “Composition settlement is underspecified by native APIs.”

A generic request to “commit the IME” is insufficient. Some platforms provide different termination behavior, and browsers have event-order quirks.

**Decisive experiment:** record finish, blur, toolbar click, selection relocation, and disposal traces for each required keyboard. Establish the exact completion fence and verify late-event behavior.

**Failure consequence:** keep the affected command pending or enter a recoverable bridge fault. Never use elapsed time as proof of completion.

The platform fencing implementations require engineering judgment grounded in these traces.

### Attack 6: “Formatting intent cannot always be inferred.”

Correct. At an ambiguous boundary, there is no universally correct hidden intention.

The specification resolves that ambiguity through explicit typing side, typing overrides, link identity, and deterministic replacement rules. These are product decisions, not mathematical discoveries.

**Decisive experiment:** run the boundary corpus through the implementation and a small human usability study. Change policy only through explicit specification revisions, preserving deterministic behavior and migration compatibility.

### Attack 7: “Stable IDs and transactions do not make collaboration easy.”

Correct. Concurrent tree edits and rich-text intent preservation remain unsolved in this v1 specification.

**Current guarantee:** the engine has replaceable admission/storage boundaries and does not couple rendering to local offsets.

**Excluded guarantee:** that a CRDT can later be added without new algorithms, migration, or revised anchor semantics.

### Attack 8: “Future pagination could still require a rewrite.”

It will require new layout work. Exact print geometry across native and browser renderers is a separate problem.

The model avoids a document rewrite by keeping pages out of text storage and supporting distinct flows and stable references. This does not promise that the current continuous-layout surface can become a print-layout engine through minor changes.

### What remains experimentally unresolved

| Question | Experiment that settles it |
|---|---|
| Can each native host provide complete input geometry without private Compose dependencies? | Compile and exercise the Phase 0 hosts |
| Can browser transactions round-trip through Kotlin without disrupting composition? | Real-IME ProseMirror projection probe |
| Are composition completion fences reliable for the supported keyboard matrix? | Recorded lifecycle traces and late-callback injection |
| Is native editable accessibility sufficient on every Desktop OS/runtime? | Assistive-technology interaction tests |
| Does the selected text layout meet long-paragraph targets? | Isolated shaping/layout benchmarks |
| Does retained semantic DOM meet the 10K-block workload? | Browser CPU, memory, selection, and accessibility benchmarks |
| Are the chosen formatting defaults understandable at ambiguous boundaries? | Boundary corpus plus usability validation |

The workspace is currently empty, and no application or device tests were run in this research pass. The verified results are source/API inspection, published-artifact inspection, and the limited mapping check described above.

The next implementation action is **Phase 0**, with its failures allowed to overturn the relevant surface or bridge choice before the rest of the system depends on it.
