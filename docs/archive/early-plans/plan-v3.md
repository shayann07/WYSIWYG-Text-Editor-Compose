# Plan v3 — Conditional Layout Graph

**Deliverable:** an independently developed and tested layout kernel, with a proposed contract connecting its results to canvas rendering, caret geometry, native input queries, and pointer interaction.

**Status:** a concrete engineering contribution and research candidate. This is not a verified world-first invention, a complete new rich-text editor, or evidence of production readiness on Compose Multiplatform. The basic line-successor tree was independently derived during this pass and then found in prior work. It is attributed below. The contribution proposed here is its executable combination with conditional validity, lazy repair, dependency indexing, geometry queries, and explicit publication rules.

The earlier composition-slot and fractional-formatting-coordinate directions are excluded from this design.

## 1. Architecture and the specific problem it attacks

### Replace eager reflow with conditional queries

A document editor usually stores the lines it currently displays. When an edit changes a break, it follows the new sequence of lines until it reaches an unchanged continuation. Sometimes that continuation is the paragraph's end.

The proposed kernel stores a different object: **the next-line decision for each legal line start, plus the conditions under which that decision remains correct.**

Those decisions form a directed tree toward the end of the paragraph. The displayed paragraph is one path through that tree.

A record contains:

- Its legal start position.
- Its chosen next start.
- The line's height.
- An interval of available widths for which that choice remains valid.
- The source facts on which the choice depends.
- Whether an intervening edit has invalidated those facts.

A record off the current path may remain stale. Before returning a layout answer, the engine repairs invalid records on the required path. It does not make every cached alternative current just to answer one question.

This supports exact paragraph height, locating the line containing a text position, and locating a line by vertical position without materializing all the paragraph's displayed lines.

### The concrete result

In the executed reduced model:

- There were 100,000 positive-width items.
- The available line width was 64 units.
- Changing the first item's width from 1 to 2 changed 1,562 subsequent line-start identities.
- Exactly one cached line decision needed recomputation.
- A width change from 64 to 64.5 needed zero decision recomputations and zero metric-index reads in that fixture.

This is an operation-count result. It is not a measured speedup on a phone and does not include font shaping.

There was a significant adverse result: the cold dynamic-tree traversal performed 1,563 rotations despite recomputing only one decision. Prewarming every start reduced that edit trace to four rotations, with approximately five rotations per item paid during warmup. The algorithm therefore has a demonstrated initialization and tail-latency tradeoff; it does not have a proven constant-time input path.

### What I would build around it

Use the kernel as an independent service in an existing rich-document engine:

    Document transactions
            |
    Text analysis and shaping
            |
    Conditional Layout Graph
            |
    Exact line queries + realized line geometry
            |
    Immutable frame data
       /         |          \
    Canvas    Pointer      Native input /
    drawing   queries      accessibility geometry

The native input subsystem continues to edit ordinary document content, including its accepted provisional text. The layout kernel does not own an alternative composition string.

One realized line supplies glyph placement, legal caret stops, selection segments, and geometry for the corresponding text. Drawing and hit testing do not independently reconstruct these quantities.

The design intentionally leaves the existing semantic document model replaceable. Since an editor already exists, requiring a new rope, format model, and native-input implementation before evaluating the layout idea would be unnecessary coupling.

### Alternatives and actual advantages

| Approach | Work after a long break cascade | Relevant tradeoff |
|---|---|---|
| Rebuild the paragraph | Reprocess the paragraph | Simplest correctness reference |
| Cache currently displayed lines | Follow changed lines until convergence | Often excellent; a cascade can reach the end |
| Cache every possible start | Repair affected alternatives; query a path | Existing underlying idea; additional storage |
| Proposed conditional graph | Repair invalid decisions on the demanded path; reuse width-compatible decisions | More complicated validity and dynamic-tree machinery; stale alternatives are allowed |
| Approximate offscreen heights | Avoid some immediate work | Scroll extent and distant caret geometry may be approximate |
| Split paragraphs arbitrarily | Bound local work artificially | Can change shaping, bidi, line-breaking, and document semantics |

The proposed service supplies exact answers within its supported layout model. It does not obtain speed by silently using approximate line heights.

### Prior work: an explicit boundary on the claim

The all-starts tree and its local-update argument appear in Dmitri Urbanowicz's 2020 answer about dynamic word wrapping. That result is prior art, including the use of a dynamic tree for queries. [Original algorithm discussion](https://cs.stackexchange.com/questions/130828/word-wrap-algorithm-and-datastructure-that-supports-modifications-of-text-and-wi).

Incremental paragraph algorithms that retain alternative break information are also described in earlier work. Therefore neither “line breaking as a graph” nor “incremental line breaking” is claimed here as new. [Earlier incremental line-breaking description](https://patents.google.com/patent/US6647533B1/en).

Targeted searches did not establish whether this exact conditional, lazily repaired service and its proposed interaction contract have appeared elsewhere. Absence from those results is not proof of novelty.

## 2. Core models and algorithms

### 2.1 Precisely define the executable model first

The executed prototype uses a sequence of N indivisible items:

    item i = (positive width a[i], positive height h[i])
    available width W > 0

Every item boundary is a legal break.

The line rule is greedy: include as many consecutive items as fit. If the first item alone exceeds W, include it anyway so the layout makes progress.

For a start i, define:

    next(i, W) = j
    line(i) contains items [i, j)
    j > i
    height(i) = max(h[i], ..., h[j-1])

There is a distinguished END node at N.

Every real start has one outgoing edge to a larger position. Following edges terminates at END. As an undirected structure, these edges form a tree: every node reaches the same root and there are no cycles.

This reduced model does not yet include Unicode break rules, tabs, hyphenation, bidi, contextual shaping, or text insertion/deletion. Those require the contracts below. The prototype must not be presented as already implementing them.

### 2.2 A decision's width certificate

Let j be the current next start and:

    used = sum(a[k], k in [i, j))

The choice of j remains unchanged for:

    lower <= W < upper

where:

    lower = 0                         if j = i + 1
            used                      otherwise

    upper = infinity                  if j = N
            used + a[j]               otherwise

The single-item exception is necessary. When the first item is wider than the available width, the progress rule still produces that one-item line.

The lower bound is inclusive; the upper bound is exclusive. At upper, the previously excluded item fits.

In a production oracle, a record's width domain must certify both its next-start decision and its stored height. If height varies with width, use an exact parametric aggregate that the implementation supports, narrow the domain, or fall back. The executed model has width-independent line height once the chosen items are fixed.

The certificate protects the line decision. It does not automatically protect all final glyph coordinates under resizing. Center alignment, justification, and other width-dependent placement need their own evaluation.

### 2.3 Dependencies include the rejected candidate

A record's source dependency is:

    [i, min(N, j + 1))

It includes the first excluded item, when one exists.

Why this matters:

    old widths: [2, 2, 9], available width 6
    old first line contains the first two items

    new widths: [2, 2, 1], available width 6
    new first line contains all three items

If the old record depends only on its included items, it is incorrectly retained after the third item's width changes.

The prototype reproduced this failure by intentionally omitting invalidation. It reported two lines when one was correct. With the complete dependency interval, the result was correct.

For production text, dependencies must also cover relevant absent breaks, insertion gaps, shaping context, font identity, language, direction state, and layout policy. A hash of the selected text alone is insufficient.

### 2.4 The cached record

Representative Kotlin contract:

    data class LineDecision(
        val start: BreakId,
        val next: BreakId,
        val height: LayoutUnit,
        val widthDomain: WidthDomain,
        val dependencies: DependencyHandle,
        val payload: LineRecipeId,
        val invalid: Boolean
    )

    data class WidthDomain(
        val lowerInclusive: LayoutUnit,
        val upperExclusive: LayoutUnit? // null means unbounded
    )

    interface LineOracle {
        fun solve(
            snapshot: AnalysisSnapshot,
            start: BreakId,
            width: LayoutUnit,
            policy: LayoutPolicy
        ): LineDecision
    }

A BreakId is a stable identity for a legal break opportunity, not a global integer offset that must be renumbered after every insertion.

A LineRecipe is enough information to realize the line with the same metrics and break policy that justified the decision. It is not an independent paragraph layout request that may choose different breaks.

### 2.5 Aggregates on a path

Each dynamic-tree node stores its outgoing line's values. END contributes zero height and zero lines.

An exposed path maintains:

    totalHeight = sum(line heights)
    lineCount   = sum(1 for each real start)
    maxLower    = maximum lower width bound
    minUpper    = minimum upper width bound
    invalidCount

A path is usable at width W exactly when:

    invalidCount == 0
    maxLower <= W
    W < minUpper

These tests are sufficient because every record on that path then describes the current oracle result.

The executed implementation uses a link-cut tree. Its operations have amortized bounds. A production implementation cannot substitute that statement for a per-frame worst-case latency guarantee.

### 2.6 Lazy repair

    ensureCurrent(start, snapshot, W):
        while an invalid record exists on start -> END:
            i = find the first such record from start
            replacement = oracle.solve(snapshot, i, W, policy)
            replace i's old outgoing edge and payload
            replace its reverse dependency registration
        return usable path

A record is invalid if its source dependency was invalidated or W is outside its width domain.

Repair the earliest invalid record on the demanded path. Its new edge can bypass invalid alternatives, or enter alternatives that now need repair.

No record is trusted because it once belonged to a valid paragraph. It is trusted because its own current conditions hold.

Changing W does not require eagerly rewriting every record. Path queries discover which decisions the new width actually requires.

### 2.7 Finding dependencies efficiently

In the fixed-length prototype, each record is an interval [i, reach[i]). A segment tree over start positions stores maximum reach.

To find records affected by a point edit at k:

- Search starts at or before k.
- Prune a subtree when its maximum reach is at or before k.
- Mark every reported record invalid.
- Update the source metric index.

This avoids a scan over all starts. If d records depend on the edited item, reporting them costs O((d + 1) log N) with this implementation.

For a production sequence with insertions, use stable boundary identities and an ordered interval index. Required operations are:

- Insert/remove a break identity.
- Compare current break order.
- Register and remove a dependency interval.
- Find intervals overlapping a changed interval or a changed adjacency.
- Preserve the old interval description until invalidation has consumed it.

The prototype does not implement these operations. They are a separate acceptance gate, not a detail to assume away.

### 2.8 Querying height, a visible line, or a caret's line

After ensuring the path:

- **Paragraph height:** return its height sum.
- **Number of lines:** return its count.
- **Line at y:** descend the exposed path using height sums.
- **Line containing a logical item:** descend using increasing logical start order.
- **Visible range:** locate the first visible line, then enumerate only the visible lines and necessary overscan.
- **Caret geometry:** locate the containing line, realize it if necessary, then use its actual caret-stop table.

A boundary between two lines still needs upstream/downstream affinity. The line graph does not erase that distinction.

A bidi position may have several visual caret stops. The line's shaping and bidi result, not an integer division of its width, determines those stops.

### 2.9 Text metrics and numerical correctness

The prototype's randomized metrics are small integers. The 64.5 resize is exactly representable. These tests do not establish safety for arbitrary floating-point font advances.

A production shared layout backend should define an authoritative fixed-point LayoutUnit, for example 1/1024 of a logical pixel, and use checked 64-bit arithmetic for width sums and comparisons. Quantize metrics once at the chosen backend boundary. Glyph placement must use those same metrics.

This is a rendering policy with a measurable error budget. It must be validated visually for small sizes, long runs, zoom, and hinting. A native paragraph engine with independently rounded advances cannot be mixed with this oracle while claiming identical breaks.

If the selected renderer cannot expose compatible metrics and placements, use a conservative opaque paragraph backend. It can satisfy the correctness contract, but it does not provide the graph's accelerated path.

No epsilon is allowed to turn an invalid width certificate into a valid one merely to avoid reflow.

### 2.10 Complexity, including the costs that remain

Let:

- N be the number of alternative starts.
- d be the number of dependency records invalidated by a source edit.
- r be the number of decisions repaired on the demanded path.
- c be the oracle cost for each repaired decision.
- v be the number of visible lines realized.

For the prototype's data structures, the intended amortized work is:

    edit and query:
        O((d + r + 1) log N + r*c)

    visible output:
        additional work for v line realizations and their glyphs

Space is O(N) in the reduced model. Real dependency descriptions and cached glyphs add to that.

There are important nonconstant cases:

- Very dense legal break opportunities increase d.
- Width changes can invalidate many decisions.
- A font or direction change can affect a large analysis region.
- Enumerating all lines still costs at least the output size.
- Building all alternatives and prewarming them has an upfront cost.
- The splay-based implementation can have an expensive individual access.

“Only one line decision recomputed” is a different claim from “only constant CPU work performed.” The evidence distinguishes them.

## 3. Invariants and proof obligations

### C1 — Forward progress

Every real start's edge points strictly forward in the current break order.

This prevents cycles and ensures repair cannot create an infinite line loop. Oversized items must still advance.

### C2 — Valid decision equivalence

If a record's source dependencies are unchanged and W is inside its certified width domain, that record equals the result the declared oracle would currently produce at that start.

The certificate must come from the oracle's actual decision rule. An unsupported backend cannot invent a broad width interval.

### C3 — Complete invalidation

Every source mutation that could change an existing record invalidates that record before it is used.

Negative observations count: a candidate that failed to fit, an adjacency with no permitted break, and a font lookup that did not match can all affect later decisions.

### C4 — Exact path answers

After ensureCurrent succeeds, the path from the paragraph's entry to END equals the full oracle layout at that width.

Proof by induction:

1. The entry's valid decision chooses the same next start as a full layout.
2. The reached start is valid and chooses the same next start.
3. Repeat until END, which must be reached by C1.

The path's accumulated height and line count are therefore exact for that layout model.

### C5 — Repair terminates

During one query, source analysis, width, and policy are frozen.

A repaired record becomes valid for those inputs. Repair of another record does not change those inputs. No already repaired record needs repair again during that query. With finitely many starts, the process terminates.

If an observer or callback mutates source during repair, this proof no longer applies. The query must run against an immutable analysis snapshot or restart.

### C6 — Off-path staleness is permitted, never concealed

A stale alternative is cache state, not current layout. It must pass the same checks if a later path reaches it.

### C7 — One geometry result per line realization

Glyph placement, caret stops, selection fragments, hit testing, and native range rectangles for a realized line use the same line recipe and analysis inputs.

A second layout system may act as a differential oracle. It cannot silently provide the production caret coordinates for glyphs positioned by another system.

### C8 — Stale frame commands retain their original referent

A pointer result contains the immutable frame identity and stable document reference produced by that frame.

Applying it to a newer document requires explicit mapping and command-specific conflict validation. A deleted target is reported as deleted. It must not silently become an unrelated character that happens to occupy its former numeric offset.

### C9 — Current native queries use current accepted input

Native input geometry and surrounding-text queries use the latest accepted native editing state required by that adapter's contract. They do not use the last painted frame merely because it is convenient.

Thus an old pointer frame and a current native query may legitimately name different snapshots. Their identities remain explicit.

### C10 — No false acceleration claim

A fallback that reanalyzes a whole paragraph remains a whole-paragraph fallback in diagnostics. It cannot report a local-edit success because its final cache update was small.

### Verification strategy

Use independent implementations:

- A sequential reference layout that scans items line by line.
- The indexed oracle and dynamic graph.
- A geometry consumer model that queries height and containing lines in arbitrary order.

The executable test in this document uses the first two. The third's full glyph, frame, and platform parts remain to be implemented.

Add mutation tests that intentionally omit negative dependencies, reverse width-bound inclusivity, drop dirty flags, use old heights after a relink, and mix frame identities. A test suite that only exercises successful cache reuse will miss the dangerous errors.

## 4. Input, composition, and selection contracts

This proposal does not require a new IME ownership model. It specifies how layout participates in input safely.

### Separate semantic publication from frame publication

Use these conceptual states:

    Native callback
        -> staged accepted text
        -> accepted editing snapshot

    Accepted editing snapshot
        -> analyzed text
        -> validated layout query
        -> immutable geometry
        -> submitted frame

Text acceptance must not wait for a paint frame. A synchronous native query during a callback sees the correct staged state for that callback.

A geometry query either obtains geometry for its requested state or returns an explicit unavailable/pending result where the platform permits it. It cannot relabel an old rectangle as current.

### Composition changes

Each accepted preedit update produces the appropriate text/selection change in the existing semantic engine. The analysis layer then identifies changed metrics and dependencies.

The graph only repairs decisions reached by the needed geometry query. A candidate-window request near the caret does not require constructing every line object in a long paragraph.

Finishing composition removes the appropriate input metadata. The layout service does not insert the composition text again.

Native cancellation, partial commits, and reconversion retain their platform-specific semantics. They are not interpreted as universal rollback commands by the graph.

### Composition formatting

- A paint-only style update can reuse compatible geometry.
- A metric-affecting style update invalidates the relevant analysis and decision dependencies.
- Geometry is recalculated before current native geometry is returned.
- Whether a toolbar operation can be applied during composition is an input-protocol decision. This module does not claim that layout validity proves native protocol safety.
- The earlier fractional formatting transport is not used.

### Selection and old frames

A proposed frame contract:

    data class FrameTicket(
        val id: FrameId,
        val document: SnapshotId,
        val analysis: AnalysisId,
        val viewport: ViewportTransformId
    )

    data class HitResult(
        val frame: FrameTicket,
        val target: StableTextReference,
        val caretStop: CaretStopId,
        val affinity: CaretAffinity
    )

For a pointer event:

1. The host identifies the retained logical frame used for hit testing.
2. Hit-test against that frame's geometry.
3. Map the stable target into the current semantic snapshot.
4. If the target survives, preserve the declared affinity when possible.
5. If the target was deleted or changed incompatibly, invoke a named conflict policy: cancel, defer until current geometry, or use a documented landing rule.
6. Do not silently hit-test the same coordinates against a different layout in the middle of this operation.

A drag retains its anchor reference while its focus is sampled from explicitly identified frames.

This does not prove which physical display frame a human saw. Some hosts do not expose presentation timestamps or fences sufficient for that claim. The guarantee concerns the frame selected by the host protocol. Actual display-to-event association requires platform testing.

### Input connection lifecycle

Preserve epochs and native offset semantics in the existing adapter. The graph neither supplies missing native revision information nor makes stale callbacks safe.

The useful change is that the input adapter's geometry provider becomes a precise, queryable service with explicit validity conditions, instead of a convenient cache of the most recently painted paragraph.

## 5. Formatting boundaries and their effect on layout

This module consumes semantic formatting. It does not invent a new rule for reconstructing unreported character provenance from IME strings.

Attributes must be classified by the chosen backend's actual dependencies:

| Attribute effect | Necessary action |
|---|---|
| Paint only, with a backend guarantee of unchanged geometry | Update paint data; retain layout |
| Advance or shaping change | Reanalyze affected shaping region; invalidate dependent decisions |
| Ascent/descent or line-height change | Update line-height dependencies, even if breaks stay equal |
| Break policy change | Replace affected break opportunities and invalidate decisions that observed them |
| Paragraph width, indentation, or inset change | Reevaluate width conditions and entry policy |
| Direction/language/font fallback change | Recompute the affected analysis context; it may be nonlocal |

An attribute's familiar name is not enough to classify it. For example, a decoration could have layout effects under a particular backend policy.

For production graphemes:

- User navigation uses legal grapheme boundaries.
- Native UTF-16 ranges retain their protocol meaning.
- Break opportunities are not created at arbitrary storage boundaries.
- No invisible separators are inserted into user text to simplify the graph.
- A glyph cluster and a formatting range are not presumed to have the same boundaries.
- Drawing differently colored portions of a ligature requires a real rendering policy; the graph does not supply one.

At a formatting boundary, the existing semantic engine supplies the intended attributes. The layout service's obligation is to render those attributes and expose matching geometry without independently resampling a different side for the caret.

The full editor still needs explicit policies for collapsed typing intent, link boundaries, replacement formatting, and structural commands. Those are outside this contribution; copying v1's table here would not make them new.

## 6. Compose Multiplatform integration

### 6.1 Backend contract

    interface TextAnalysisBackend {
        fun analyze(
            before: AnalysisSnapshot?,
            edit: SemanticEdit,
            document: DocumentSnapshot
        ): AnalysisResult
    }

    data class AnalysisResult(
        val snapshot: AnalysisSnapshot,
        val changes: AnalysisChanges,
        val capabilities: AnalysisCapabilities
    )

    interface GeometryBackend {
        fun realize(
            analysis: AnalysisSnapshot,
            decision: LineDecision,
            width: LayoutUnit
        ): RealizedLine
    }

    interface ParagraphLayoutService {
        fun height(query: LayoutQuery): ExactHeight
        fun lineAtY(query: LayoutQuery, y: LayoutUnit): RealizedLine
        fun lineForPosition(query: LayoutQuery, p: TextReference): RealizedLine
        fun visibleLines(query: LayoutQuery, viewport: VerticalRange): LineBatch
    }

Capabilities must distinguish:

- Certified local analysis.
- Certified line decisions with width domains.
- Opaque whole-paragraph analysis.
- Geometry available only after an asynchronous operation.
- Unavailable features.

The fast path is selected from demonstrated capabilities, not a platform name.

### 6.2 Correct shaping remains upstream

An arbitrary font shaper is not assumed to have a fixed small context radius.

HarfBuzz already exposes flags intended to support safe reuse around some shaping boundaries, with specific conditions. Those existing mechanisms may support an analysis adapter; they are not inventions of this plan. [HarfBuzz buffer flags](https://harfbuzz.github.io/harfbuzz-hb-buffer.html).

A current upstream discussion also examines a more permissive shaping-repair criterion. This is why generic “incremental shaping with safe boundaries” was rejected as this pass's novelty claim. [Upstream incremental-shaping discussion](https://github.com/harfbuzz/harfbuzz/issues/6062).

Production rules:

1. Obtain a justified changed analysis region.
2. Invalidate every line decision whose actual reads could change.
3. Use a larger region or whole-paragraph analysis when local safety is unknown.
4. Validate optimized output against full shaping and layout.
5. Record the size and cause of fallbacks.

The line graph can remove break-cascade work while shaping is still expensive. These are separate measurements.

### 6.3 Rendering on Compose

A Compose surface can draw immutable realized lines and overlays. Drawing must not mutate graph state or perform semantic transactions.

Layout queries are scheduled before drawing or supplied through an explicit synchronous geometry path. A drawing callback should not accidentally become the input system's state machine.

A backend based entirely on an opaque TextLayoutResult may be useful as:

- The initial correctness reference.
- A conservative fallback.
- An implementation for short paragraphs.

It cannot be assumed to expose all data needed to construct the optimized graph. This pass did not inspect or compile current Compose APIs.

If a custom shared shaping backend is required, its native/Wasm bindings and the ability to draw its exact glyph positions are substantial implementation work. This design makes that cost visible rather than assuming Compose supplies it.

### 6.4 Android, iOS, Desktop, and Web

The shared module can be written in common Kotlin. Platform adapters continue to own native text input, focus, coordinate transforms, and accessibility behavior.

Required integration tests on every target:

- Current caret rectangles during preedit, before the next paint.
- Selection rectangles across newly changed wraps.
- Scrolling and resizing while a native query is pending.
- Zoom, density changes, window moves, and font loading.
- Old pointer frame followed by a new semantic transaction.
- Accessibility requests into offscreen text.
- Fallback behavior when analysis is not locally certifiable.

On Web, a canvas surface still needs a demonstrated native-input and editable-accessibility path. This layout algorithm does not establish browser feature parity or remove that obligation.

The proposal therefore does not declare universal canvas integration solved. It provides a canvas-compatible geometry service whose claims can be tested independently.

### 6.5 Structured documents

Paragraph heights feed an ordinary measured block index. Lists and quotes provide insets and entry policies. Table cells query their own paragraph services under cell widths; row height depends on the maximum required cell height.

Keep paragraph-local layout certificates separate from document placement:

- A height change above a paragraph can translate it without reshaping its text.
- A cell-width change can invalidate line decisions inside that cell.
- Table structure, selection promotion, and semantic deletion remain document-engine responsibilities.

Floating objects, pagination, and line widths that depend on vertical position are not covered by a single start-only successor. The state must include the relevant layout context, or the backend must fall back. Silently reusing start-only records for those layouts is incorrect.

## 7. Transactions, history, and cache lifetime

### Semantic transactions remain authoritative

A layout repair is not a document edit. It produces no Undo entry and changes no persistent formatting.

Commit order:

1. Accept and validate the semantic transaction.
2. Publish its semantic snapshot according to the input contract.
3. Produce or update analysis for that snapshot.
4. Invalidate cached layout dependencies.
5. Repair the paths needed for current queries.
6. Realize required lines.
7. Publish an immutable frame bundle when ready.

If steps 3–6 are asynchronous, old drawing data may remain visible, but its identity remains old. Current native queries must use a supported current-query path.

### Undo/Redo

Undo supplies another semantic snapshot or change set. The layout layer processes it through the same dependency mechanism.

It can reuse old analysis or decision records when their complete keys and validity conditions match. An older document revision number alone does not make a cached record correct.

The source document's exact history semantics are preserved. This module does not introduce a second text history.

### Retention and concurrency

The mutable dynamic forest belongs to one layout owner. Immutable realized lines can be consumed by rendering without mutating it.

All painted text, selections, composition decorations, and carets in one frame must use that frame's coherent state. Do not paint a current caret over old text geometry just because current native geometry was requested before the next frame. A current native rectangle can precede presentation of its matching frame; the host must schedule that frame promptly and measure the gap.

A retained frame holds:

- Its document/analysis references or sufficient immutable source data.
- Its realized line geometry.
- Its viewport transform.
- Its text-reference mapping data.

Bound the number and total size of retained frames. Pending pointer interactions may pin a frame; disposal must release it.

Do not serialize the dynamic forest as the canonical document. It is a derived cache. Persisting a reusable analysis cache is optional and requires complete versioned keys for fonts, Unicode policy, backend, and metric arithmetic.

## 8. Implementation sequence, deliverables, and gates

### Phase A — Reproduce the reduced kernel

Deliverables:

- Extract the executable prototype in Appendix A.
- Run Appendix B.
- Port the fixed-length kernel to common Kotlin.
- Keep a separately written sequential reference.

Gate:

- Identical results for the recorded deterministic tests.
- Correct exact-fit and oversized-item behavior.
- Negative-dependency fault injection fails when invalidation is deliberately omitted.
- Report cold and prewarmed costs separately.

Do not start by integrating a native keyboard. This phase has a sharply defined mathematical contract.

### Phase B — Stable sequence edits

Deliverables:

- Stable break identities.
- An editable ordered metric sequence.
- Insertion-gap and break-opportunity invalidation.
- Dynamic dependency intervals.
- Insert/delete/split/join support.
- Empty-paragraph policy: one editable line with its declared baseline metrics.

Gate:

- Differential tests include inserted/deleted identities, not only changed numeric widths.
- No full renumbering of surviving IDs.
- Every deleted decision and dependency registration is removed or made unreachable.
- Long operations are measured in affected structure size rather than hidden as local edits.

### Phase C — Real text oracle

Deliverables:

- A complete baseline text-analysis backend.
- A real line oracle and line realization using compatible metrics.
- Explicit capability and fallback reporting.
- Fixed-point or otherwise justified numeric semantics.

Gate:

- Optimized and full output agree on line starts, line heights, glyph positions within the declared precision, and legal caret stops.
- Include contextual scripts, mixed bidi, emoji, combining sequences, tabs, soft breaks, inline objects, and font fallback.
- No arbitrary shaping cuts or independent per-character width assumptions.

This is the largest unresolved technical gate.

### Phase D — Query and frame integration

Deliverables:

- Current native geometry queries.
- Immutable frame tickets and line bundles.
- Pointer results tied to their originating logical frame.
- Explicit stale-target conflict policies.
- Selection painting from the same realized lines as text.

Gate:

- A delayed frame cannot supply a current native caret rectangle under a new identity.
- A click on a deleted old-frame target does not silently edit an unrelated replacement character.
- Reentrant native queries during an accepted edit are coherent.
- Drawing and hit testing share geometry.

### Phase E — Existing-editor integration

Deliverables:

- A selectable layout backend in the user's existing editor.
- Side-by-side diagnostics against its current backend.
- Instrumentation for oracle solves, dependency invalidations, tree work, glyph work, frame age, and fallbacks.
- No semantic editing rewrite merely to exercise the module.

Gate:

- Existing IME and formatting regression suites continue to pass.
- Geometry correctness holds on each integrated platform.
- The extra alternative-record memory is justified by observed workloads.

### Phase F — Performance qualification

Report separately:

1. Initial analysis.
2. Building alternative decisions.
3. Optional path warmup.
4. First edit.
5. Steady edits.
6. Width changes.
7. Whole-context fallbacks.
8. Visible glyph realization.
9. Native callback-to-geometry latency.
10. Callback-to-presented-frame latency.

Acceptance should be comparative against the existing editor on the same device and corpus. No fixed speedup is asserted here.

If occasional splay accesses violate the latency budget, evaluate a worst-case-bounded dynamic-tree implementation or accept a documented scheduling strategy that keeps synchronous native queries correct. Prewarming one fixture is not a general tail-latency proof.

### Executed evidence

The randomized test covered 160 fixtures and 104,000 mixed actions:

- 545,643 public layout queries.
- 1,558,980 scalar comparisons with the sequential reference.
- Positive item widths and heights.
- Metric edits and width changes.
- Batches of changes before a query.
- Height, containing-line, and y-position queries.

All passed.

These tests did not execute on Kotlin or Compose, did not insert/delete items, and did not load a font, browser, native input method, or screen reader.

## 9. Adversarial review and disposition

### The strongest objections

**“This starts from an existing algorithm.”**

Correct. The core successor-tree idea is attributed. The specific conditional service was developed and implemented here; its worldwide novelty is unestablished. It would be misleading to call the entire result an unprecedented line-breaking invention.

**“The tests are just numbers.”**

Correct. They validate a nontrivial dynamic algorithm, its lazy repair, and its queries. They do not validate text shaping. The text-oracle gate remains mandatory.

**“You moved the difficult work into the oracle.”**

The oracle is explicit, and the fast path is unavailable without its guarantees. The achieved result concerns avoiding break propagation and line materialization once metric and break decisions are available. It does not promise cheap analysis for every edit.

**“One recomputation still caused a long tree traversal.”**

Observed and reported. The prototype has amortized tree bounds. Warmup helped the tested trace, but it does not prove per-event latency. This is a real implementation risk.

**“The graph uses more memory and startup work.”**

Yes. It stores alternative starts the user may never visit. Dense break opportunities can make it expensive. The integration must measure that cost against the saved work, with an opaque or conventional backend remaining available.

**“A richer layout may depend on more than its start.”**

Yes. Paragraph-first-line indentation, width regions, hyphenation state, or other context must be in the node's state/key. If the necessary state is too large, this representation may be the wrong accelerator for that layout.

**“This does not solve all the original IME and formatting problems.”**

Correct. It offers a concrete layout and geometry contribution. The requested complete editor breakthrough has not been established. I am not substituting a performance result for that claim.

### What is actually worth evaluating

The practical result is a module with:

- An explicit oracle contract.
- A proof of correctness under that contract.
- An implemented invalidation and repair strategy.
- Exact aggregate and position queries.
- A demonstrated break-cascade case.
- A demonstrated failure from an incomplete dependency.
- Reproducible source and tests.
- Known costs that can defeat its advantage.

This is stronger evidence than a renamed architecture diagram. Whether it is useful to the existing editor is now an implementable, measurable question.


## Appendix A — Executable reduced prototype

This is the actual in-memory implementation exercised in this pass. It uses no packages or files. It is intentionally a reduced algorithm prototype, not production Kotlin or a font engine.

Copy Appendix A and Appendix B into the same JavaScript environment. Then call `runReproduction()`. The prototype supports nonempty, fixed-length sequences with positive widths and heights. Source arrays, tree indexes, and all caches are process-local. `MetricIndex.update` is exposed for fault injection; ordinary callers must use `CertifiedWrap.edit` so invalidation runs.

```javascript
function createWrapPrototype() {

function assert(v,m){if(!v)throw Error(m);}
class MetricIndex {
  constructor(w,h){this.n=w.length;this.w=w.slice();this.h=h.slice();this.base=1;while(this.base<this.n)this.base*=2;this.sum=new Float64Array(2*this.base);this.max=new Float64Array(2*this.base);for(let i=0;i<this.n;i++){this.sum[this.base+i]=w[i];this.max[this.base+i]=h[i];}for(let i=this.base-1;i;i--){this.sum[i]=this.sum[2*i]+this.sum[2*i+1];this.max[i]=Math.max(this.max[2*i],this.max[2*i+1]);}this.reads=0;}
  update(i,w,h){this.w[i]=w;this.h[i]=h;let x=this.base+i;this.sum[x]=w;this.max[x]=h;while(x>>=1){this.sum[x]=this.sum[x*2]+this.sum[x*2+1];this.max[x]=Math.max(this.max[x*2],this.max[x*2+1]);}}
  range(a,b,type){let ans=0;a+=this.base;b+=this.base;while(a<b){this.reads++;if(a&1){ans=type==="sum"?ans+this.sum[a]:Math.max(ans,this.max[a]);a++;}if(b&1){--b;ans=type==="sum"?ans+this.sum[b]:Math.max(ans,this.max[b]);}a>>=1;b>>=1;}return ans;}
  prefix(i){return this.range(0,i,"sum");}
  upperPrefix(limit){let x=1,acc=0;while(x<this.base){this.reads++;const left=x*2;if(acc+this.sum[left]<=limit){acc+=this.sum[left];x=left+1;}else x=left;}let k=x-this.base;return Math.min(this.n,k+(acc+this.sum[x]<=limit?1:0));}
  line(i,width){const start=this.prefix(i);let j=this.upperPrefix(start+width);j=Math.max(i+1,Math.min(j,this.n));const used=this.range(i,j,"sum");const low=j===i+1?0:used;const high=j===this.n?Infinity:used+this.w[j];return {next:j,height:this.range(i,j,"max"),low,high,reach:Math.min(this.n,j+1)};}
}
class ReachIndex {
  constructor(n){this.n=n;this.base=1;while(this.base<n)this.base*=2;this.max=new Int32Array(2*this.base);this.visits=0;}
  set(i,end){let x=this.base+i;this.max[x]=end;while(x>>=1)this.max[x]=Math.max(this.max[x*2],this.max[x*2+1]);}
  containing(k){const out=[];const walk=(x,a,b)=>{this.visits++;if(a>k||this.max[x]<=k)return;if(b-a===1){if(a<this.n)out.push(a);return;}const m=(a+b)>>1;walk(x*2,a,m);if(m<=k)walk(x*2+1,m,b);};walk(1,0,this.base);return out;}
}
class PathTree {
  constructor(n){this.n=n;const z=n+1;this.l=new Int32Array(z).fill(-1);this.r=new Int32Array(z).fill(-1);this.p=new Int32Array(z).fill(-1);this.value=new Float64Array(z);this.count=new Int32Array(z);this.lo=new Float64Array(z);this.hi=new Float64Array(z).fill(Infinity);this.dirty=new Int32Array(z);this.sum=new Float64Array(z);this.size=new Int32Array(z);this.maxlo=new Float64Array(z);this.minhi=new Float64Array(z).fill(Infinity);this.bad=new Int32Array(z);this.rotations=0;}
  root(x){let p=this.p[x];return p<0||(this.l[p]!==x&&this.r[p]!==x);}
  pull(x){let l=this.l[x],r=this.r[x];this.sum[x]=this.value[x]+(l<0?0:this.sum[l])+(r<0?0:this.sum[r]);this.size[x]=this.count[x]+(l<0?0:this.size[l])+(r<0?0:this.size[r]);this.maxlo[x]=Math.max(this.lo[x],l<0?0:this.maxlo[l],r<0?0:this.maxlo[r]);this.minhi[x]=Math.min(this.hi[x],l<0?Infinity:this.minhi[l],r<0?Infinity:this.minhi[r]);this.bad[x]=this.dirty[x]+(l<0?0:this.bad[l])+(r<0?0:this.bad[r]);}
  rotate(x){this.rotations++;const y=this.p[x],z=this.p[y],right=this.r[y]===x,b=right?this.l[x]:this.r[x];if(!this.root(y)){if(this.l[z]===y)this.l[z]=x;else this.r[z]=x;}this.p[x]=z;if(right){this.l[x]=y;this.r[y]=b;}else{this.r[x]=y;this.l[y]=b;}this.p[y]=x;if(b>=0)this.p[b]=y;this.pull(y);this.pull(x);}
  splay(x){while(!this.root(x)){const y=this.p[x],z=this.p[y];if(!this.root(y)){if((this.l[y]===x)===(this.l[z]===y))this.rotate(y);else this.rotate(x);}this.rotate(x);}}
  access(x){let last=-1;for(let y=x;y>=0;y=this.p[y]){this.splay(y);this.r[y]=last;if(last>=0)this.p[last]=y;this.pull(y);last=y;}this.splay(x);}
  assign(i,record){this.access(i);const oldAnc=this.l[i];if(oldAnc>=0){this.l[i]=-1;this.p[oldAnc]=-1;}this.value[i]=record.height;this.count[i]=1;this.lo[i]=record.low;this.hi[i]=record.high;this.dirty[i]=0;this.pull(i);this.p[i]=record.next;}
  mark(i){this.access(i);this.dirty[i]=1;this.pull(i);}
  invalidSub(x,width){return x>=0&&(this.bad[x]>0||this.maxlo[x]>width||this.minhi[x]<=width);}
  findInvalid(start,width){this.access(start);if(!this.invalidSub(start,width))return -1;let x=start;while(true){if(this.invalidSub(this.r[x],width)){x=this.r[x];continue;}if(this.dirty[x]||this.lo[x]>width||this.hi[x]<=width){this.splay(x);return x;}x=this.l[x];assert(x>=0,"invalid traversal");}}
  total(start){this.access(start);return {height:this.sum[start],lines:this.size[start]};}
  lineAt(start,y){this.access(start);const total=this.sum[start];assert(y>=0&&y<total,"y outside");let x=start,top=0;while(x>=0){const r=this.r[x],rh=r<0?0:this.sum[r];if(y<top+rh){x=r;continue;}top+=rh;if(this.count[x]&&y<top+this.value[x]){const result={start:x,top,height:this.value[x]};this.splay(x);return result;}top+=this.value[x];x=this.l[x];}throw Error("no y result");}
  containing(start,token){this.access(start);let x=start,top=0,best=null;while(x>=0){const r=this.r[x],rh=r<0?0:this.sum[r];if(x>token){x=r;}else{best={start:x,top:top+rh,height:this.value[x]};top+=rh+this.value[x];x=this.l[x];}}assert(best,"missing line for token");this.splay(best.start);return best;}
}
class CertifiedWrap {
  constructor(w,h,width){assert(w.length>0,"empty fixture");assert(w.every(x=>x>0)&&h.every(x=>x>0),"positive metrics");this.metrics=new MetricIndex(w,h);this.reach=new ReachIndex(w.length);this.tree=new PathTree(w.length);this.next=new Int32Array(w.length);this.width=width;this.solves=0;this.dirtied=0;for(let i=w.length-1;i>=0;i--)this.solve(i);}
  solve(i){const rec=this.metrics.line(i,this.width);this.solves++;this.tree.assign(i,rec);this.next[i]=rec.next;this.reach.set(i,rec.reach);}
  edit(i,w,h){const affected=this.reach.containing(i);this.metrics.update(i,w,h);for(const x of affected)this.tree.mark(x);this.dirtied+=affected.length;return affected;}
  resize(w){assert(w>0,"width must be positive");this.width=w;}
  ensure(start=0){let bad,iterations=0;while((bad=this.tree.findInvalid(start,this.width))>=0){this.solve(bad);if(++iterations>this.metrics.n+1)throw Error("nontermination");}return iterations;}
  total(){this.ensure();return this.tree.total(0);}
  lineAt(y){this.ensure();return this.tree.lineAt(0,y);}
  containing(token){this.ensure();return this.tree.containing(0,token);}
}
function reference(w,h,width){const lines=[];let i=0,top=0;while(i<w.length){let j=i,used=0,height=0;while(j<w.length&&(j===i||used+w[j]<=width)){used+=w[j];height=Math.max(height,h[j]);j++;}lines.push({start:i,next:j,top,height});top+=height;i=j;}return {lines,height:top};}
return {CertifiedWrap,reference,assert};

}
```

## Appendix B — Reproduction and fault injection

The following exact source was executed after assembling this deliverable and reproduced the results below. Running it again is a reproduction, not another independent set of randomized cases.

```javascript

function runReproduction() {
  const {CertifiedWrap,reference,assert}=createWrapPrototype();
  let seed=0x51ced;
  const rand=n=>{seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;return (seed>>>0)%n;};
  let actions=0,publicQueries=0,comparisons=0;
  for(let trial=0;trial<160;trial++){
    const n=1+rand(300);
    const w=Array.from({length:n},()=>1+rand(30));
    const h=Array.from({length:n},()=>1+rand(15));
    let width=1+rand(160);
    const model=new CertifiedWrap(w,h,width);
    for(let step=0;step<650;step++){
      actions++;
      if(rand(10)<7){
        const i=rand(n);
        w[i]=1+rand(45);h[i]=1+rand(20);
        model.edit(i,w[i],h[i]);
      } else {
        width=1+rand(210);model.resize(width);
      }
      if(rand(4)===0)continue;
      const expected=reference(w,h,width),actual=model.total();
      publicQueries++;
      assert(actual.height===expected.height&&actual.lines===expected.lines.length,
        "total trial="+trial+" step="+step);
      comparisons+=2;
      for(let q=0;q<3;q++){
        const token=rand(n),got=model.containing(token);
        const line=expected.lines.find(x=>x.start<=token&&token<x.next);
        assert(got.start===line.start&&got.top===line.top&&got.height===line.height,
          "token query trial="+trial+" step="+step);
        comparisons+=3;publicQueries++;
        const y=rand(expected.height),ygot=model.lineAt(y);
        const yline=expected.lines.find(x=>x.top<=y&&y<x.top+x.height);
        assert(ygot.start===yline.start&&ygot.top===yline.top&&ygot.height===yline.height,
          "y query trial="+trial+" step="+step);
        comparisons+=3;publicQueries++;
      }
    }
  }
  const random={trials:160,actions,publicQueries,scalarComparisons:comparisons};
  assert(actions===104000&&publicQueries===545643&&comparisons===1558980,
    "random test counters differ");

  const n=100000,w=Array(n).fill(1),h=Array(n).fill(1),width=64;
  const model=new CertifiedWrap(w,h,width);
  const before=reference(w,h,width);
  model.total();
  const counters=()=>({
    solves:model.solves,rotations:model.tree.rotations,
    metricReads:model.metrics.reads,reachVisits:model.reach.visits
  });
  const difference=(a,b)=>Object.fromEntries(Object.keys(a).map(k=>[k,b[k]-a[k]]));
  const c0=counters();
  w[0]=2;
  const marked=model.edit(0,2,1);
  const actual=model.total(),c1=counters(),after=reference(w,h,width);
  assert(actual.height===after.height&&actual.lines===after.lines.length,"cascade");
  const oldStarts=new Set(before.lines.map(x=>x.start));
  const cascade={
    tokens:n,linesBefore:before.lines.length,linesAfter:after.lines.length,
    changedLineStarts:after.lines.filter(x=>!oldStarts.has(x.start)).length,
    dirtied:marked.length,work:difference(c0,c1)
  };
  assert(cascade.changedLineStarts===1562&&cascade.work.solves===1,"cascade counters");

  const c2=counters();
  model.resize(64.5);
  model.total();
  const stableResize=difference(c2,counters());
  assert(stableResize.solves===0&&stableResize.metricReads===0,"stable resize");

  const broken=new CertifiedWrap([2,2,9],[1,1,1],6);
  broken.total();
  broken.metrics.update(2,1,1); // intentional defect: no dependency invalidation
  const wrong=broken.total();
  const expected=reference([2,2,1],[1,1,1],6);
  assert(wrong.lines!==expected.lines.length,"fault injection did not expose defect");
  const correct=new CertifiedWrap([2,2,9],[1,1,1],6);
  correct.total();correct.edit(2,1,1);
  assert(correct.total().lines===expected.lines.length,"complete invalidation failed");
  const negativeDependency={wrongLines:wrong.lines,correctLines:expected.lines.length};

  const warm=[];
  for(const count of [4096,16384,65536,100000]){
    const m=new CertifiedWrap(Array(count).fill(1),Array(count).fill(1),64);
    const rInit=m.tree.rotations;
    for(let i=count-1;i>=0;i--)m.tree.access(i);
    m.total();
    const r0=m.tree.rotations,s0=m.solves;
    m.edit(0,2,1);m.total();
    warm.push({
      tokens:count,warmupRotations:r0-rInit,
      editRotations:m.tree.rotations-r0,recomputedDecisions:m.solves-s0
    });
  }
  return {random,cascade,stableResize,negativeDependency,warm};
}

// Display the result in the host's normal output mechanism:
// runReproduction()
```

## Appendix C — Reproduced output

```json
{
  "random": {
    "trials": 160,
    "actions": 104000,
    "publicQueries": 545643,
    "scalarComparisons": 1558980
  },
  "cascade": {
    "tokens": 100000,
    "linesBefore": 1563,
    "linesAfter": 1563,
    "changedLineStarts": 1562,
    "dirtied": 1,
    "work": {
      "solves": 1,
      "rotations": 1563,
      "metricReads": 29,
      "reachVisits": 18
    }
  },
  "stableResize": {
    "solves": 0,
    "rotations": 0,
    "metricReads": 0,
    "reachVisits": 0
  },
  "negativeDependency": {
    "wrongLines": 2,
    "correctLines": 1
  },
  "warm": [
    {
      "tokens": 4096,
      "warmupRotations": 20222,
      "editRotations": 4,
      "recomputedDecisions": 1
    },
    {
      "tokens": 16384,
      "warmupRotations": 81662,
      "editRotations": 4,
      "recomputedDecisions": 1
    },
    {
      "tokens": 65536,
      "warmupRotations": 327422,
      "editRotations": 4,
      "recomputedDecisions": 1
    },
    {
      "tokens": 100000,
      "warmupRotations": 499742,
      "editRotations": 4,
      "recomputedDecisions": 1
    }
  ]
}
```

## Evidence and access record

Only `plan-v1.md` and `plan-v2.md` were read from the workspace for the exploration. The rest of the local reasoning and prototype execution used in-memory source. Web searches were used to challenge proposed ideas and identify prior work after the user explicitly permitted that use. No existing application, dependency source on disk, private implementation, or other agent was consulted.

The one new file produced is `plan-v3.md`. The previous plans were left unchanged.

