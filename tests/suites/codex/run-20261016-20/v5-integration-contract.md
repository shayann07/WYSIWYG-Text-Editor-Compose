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
