# Plan v2 — exploration verdict

**I did not establish a real breakthrough. There is no replacement implementation plan in this file.**

I found two mechanisms with defensible, narrowly defined benefits: symbolic input slots that can retain formatting intent across preedit replacement, and text indexes that cache state transitions instead of eagerly repairing every affected boundary. I examined them as possible foundations for a different editor. Neither investigation reached a sufficiently complete result to justify presenting a new production architecture as a breakthrough.

This is the requested failure outcome, recorded explicitly. Plan v1 remains rejected. Nothing here authorizes implementing it or quietly continuing its architecture under another name.

## 1. Scope and standard of evidence

For this pass I used the conversation and read only `plan-v1.md`. I did not use the internet, read other files, consult another agent, or run an existing editor. I performed synthetic computations in memory. Writing this document is the only filesystem change made for this pass.

The API and release claims recorded in v1 were available as prior claims. I did not independently verify them and do not use them as fresh evidence that a new mechanism works on a platform.

I did not require a complete production implementation before recognizing a possible conceptual breakthrough. A constructive algorithm, a useful proof with credible premises, or a decisive counterexample overturning the architecture could qualify. However, the result needed to do more than rename familiar machinery or prove a property after defining away the difficult interactions.

My test was:

1. Identify an important failure mechanism in rich editing.
2. Construct something that removes that mechanism, with an explicit explanation of why.
3. Check that the removal survives the adjacent requirements: native input, selection, formatting, structure, history, Unicode, and geometry.
4. Identify exactly which guarantees depend on platform behavior that has not been established.
5. Compare the resulting obligations with those of the alternatives, including the obligations moved into another layer.

The candidates below make progress at step 2. I could not complete steps 3–5 strongly enough to select one as the new foundation.

## 2. The strongest architectural candidate: symbolic input slots

### The ownership change

Instead of treating every intermediate composition update as replacement text in the ordinary document, allow a document expression to contain a named variable. The input session owns that variable's value. Document commands operate on the expression containing it.

For example:

```text
Document expression:
    Paragraph(
        Literal("Hello "),
        Slot(h),
        Literal("!")
    )

Input valuation:
    h = raw preedit text
```

Rendering and native text queries evaluate the expression using the current input valuation. The slot is not a hidden second copy of the document. It is an explicit source of the currently displayed text.

Authority is partitioned by component:

- The semantic document owns structure and persistent formatting operations.
- The active input session owns the raw value of its slot.
- A coherent published view contains both the expression and the slot generation used to evaluate it.
- Each component has one writer. There is no independently editable canonical string competing with the slot value.

When composition is finished, sealing substitutes a stable attributed fragment for the slot. The displayed result must be unchanged by that substitution.

This differs materially from continuously rewriting ordinary document runs and then repairing their formatting and positions. It makes an unresolved input result a first-class operand of document operations.

### The useful algebra

Let:

```text
σ(h)                 = the current raw UTF-16 value of slot h
Eval(T, σ)            = evaluate document expression T
Decorate(p, T)        = apply a captured presentation-attribute patch p
Materialize(h, σ)     = emit the current text of h with its base attributes
```

For a patch over the entire slot:

```text
Eval(Decorate(p, Slot(h)), σ)
    = ApplyPatch(p, Materialize(h, σ))
```

The equation holds for every slot value. Consequently, these two operations commute at the level of the evaluated result:

```text
UpdateSlot(h, newText)
DecorateWholeSlot(h, capturedPatch)
```

The target is the slot identity, including its future values. It is not a pair of offsets in one particular preedit string.

Suppose preedit changes from `n` to `ni` to `你`, and the user presses Bold after `ni`. A wrapper on the slot can immediately make the current preedit bold and make the subsequent `你` bold. No rewrite needs to rediscover which style range should expand. The formatting operation does not change the input text or its offsets.

This is a concrete benefit. It could allow some toolbar operations during composition without first forcing a composition termination.

However, a toggle must first resolve into a specific patch. A command meaning “set bold to false” is a stable operand. A command that repeatedly re-evaluates “toggle according to whether all the currently selected text is bold” may change its meaning as the slot changes.

### Conditions that the attractive equation leaves out

The algebra does not by itself establish a valid native integration. At minimum:

1. The formatting target must include the complete slot under the chosen interaction semantics.
2. The input adapter must support the resulting updates without replacing or invalidating native composing objects.
3. Native text addressing must retain its meaning. Equality of plain text alone is insufficient if other relevant projection identities change.
4. Geometry must be recomputed when formatting changes metrics. Unchanged UTF-16 offsets do not imply unchanged candidate-window or caret rectangles.
5. Publishing the expression and input valuation must be coherent under synchronous and reentrant queries.
6. Sealing must preserve effective attributes, selection, and the history meaning of commands issued while the slot was live.

Changing a font can preserve every input offset and still move the caret. Wrapping a paragraph in another structural node can preserve its characters while changing document geometry or the meaning of structural queries. These remain integration obligations.

### Partial selection breaks the simple model

Suppose a slot currently contains:

```text
abcd
```

The user selects `bc` and formats it. The IME then replaces the whole preedit with:

```text
XY
```

“Format the whole slot” has a precise continuation. “Continue formatting the same selected characters” does not, unless the input protocol provides a correspondence or the editor chooses a product policy for inventing one.

Possible policies include:

- Format a fixed interval of future slot offsets.
- Infer a correspondence from a text diff.
- Expand the command to the whole slot.
- Preserve only formatting whose correspondence can be established.
- Settle composition before allowing the operation.

Each has different visible behavior. The slot representation alone selects none of them.

One can define more elaborate symbolic selectors, such as “the first grapheme of the slot's current value.” Such selectors are mathematically legitimate, but they change the interaction contract. They do not establish that the first grapheme of a replacement is the same text the user previously selected.

Making the whole preedit indivisible would simplify the engine. It would also restrict selection and formatting behavior. That tradeoff must be evaluated explicitly; it cannot be counted as free correctness.

### Native rewrites can change the ownership region

The easy case is repeated replacement of exactly the same logical slot. Other cases include:

- Marking existing text for reconversion.
- Narrowing or expanding the marked range.
- Committing a prefix while retaining a marked suffix.
- Replacing text partly inside and partly outside the slot.
- Applying a correction to previously committed neighboring text.

Splitting a slot after a committed prefix is feasible. A formatting wrapper applied before the split can enclose both resulting pieces. A later formatting operation on the remaining preedit can wrap only that suffix.

The harder operation is a rewrite crossing a slot boundary. The engine then needs explicit rules for ownership transfer and for which semantic scopes survive. Materializing everything and constructing a fresh slot is a possible fallback, but it can discard precisely the continuing intent that made the slot useful.

I did not derive a complete ownership-transfer algebra covering these cases while preserving the intended formatting and selection behavior. Treating every difficult rewrite as a request to settle would reduce the result to a limited optimization of conventional composition handling.

### Immediate commands introduce a history problem

Consider this sequence:

```text
1. Begin input action I.
2. Preedit becomes x.
3. Apply formatting action F to the slot.
4. Preedit becomes y.
5. Commit input action I.
```

If Undo is supposed to undo F while preserving the final y, a before/after snapshot taken at step 3 is insufficient: it contains x.

A plausible solution is to represent history as independently reversible semantic actions:

- I creates an input carrier whose final value is y.
- F decorates that carrier.
- Undo F removes the decoration from the current carrier.
- Undo I then removes the carrier and restores its replaced preimage.

This is more than adding an ordinary transaction log. It needs dependency rules for scopes, partial commits, structural edits, and history eviction. Undoing the input action while its native composition is still active also has a different protocol obligation from removing a formatting wrapper.

The dependency model is potentially valuable. I did not complete it for the full command set, and I did not assume that it is automatically solved by persistent trees or stable IDs.

### What the slot investigation actually established

It established a narrow semantic result:

> A captured, whole-slot presentation patch can remain valid under arbitrary replacement of that slot's raw text without tracking the patch by transient text offsets.

That result is worth retaining. The distributive equation follows directly from the chosen expression semantics; it is not evidence that a new platform input protocol or universally superior editor has been discovered.

The unresolved surrounding requirements are substantial enough that I do not present this as the replacement architecture.

## 3. A second candidate: store text-state transitions instead of repaired boundaries

### The different indexing idea

A conventional boundary cache can become expensive when an edit changes the interpretation of a long suffix. An alternative is to store how each text fragment transforms an incoming analysis state.

For a finite-state analysis, a fragment x has a summary:

```text
F_x(q) = (outgoingState, emittedBoundaryCount)
```

For adjacent fragments x and y:

```text
(q1, count1) = F_x(q)
(q2, count2) = F_y(q1)

F_xy(q) = (q2, count1 + count2)
```

Composition is associative. A balanced tree can store summaries at internal nodes. A changed leaf requires rebuilding the summaries along its ancestor path, rather than walking the suffix until the incoming state happens to converge again.

If the state set Q is small and fixed, a summary can tabulate all possible incoming states. With bounded leaves, rebuilding one path costs approximately:

```text
O(|Q| × log(numberOfLeaves) + affectedLeafAnalysis)
```

This is conditional on the analysis actually having the represented finite state and on summaries containing enough information for the required queries. Storage also grows with |Q|; the table is not free.

Boundary rank and selection queries can descend through the tree while propagating the actual incoming state. The important change is that a cached fragment is valid under several possible contexts, rather than only under the context it happened to have when last scanned.

### A concrete adversary

I tested a deliberately restricted two-state model:

- R behaves like an indicator that pairs with the next R.
- X resets the pairing state and contributes an ordinary boundary.
- The incoming state records whether an unmatched R precedes the fragment.

In a run of 100,000 R symbols, prepend one R. Every old symbol's “boundary before this symbol” decision changes.

An eagerly stored list of those decisions would need extensive repair. A fragment summary already tabulated for both parity states can be reused under the other incoming state. The suffix does not need to be rescanned to obtain its count and outgoing state.

This is an actual algorithmic distinction from a scan-until-state-convergence strategy. It is more useful than choosing a different rope branching factor.

### Why it is not the editor breakthrough

The executed model is not a complete Unicode grapheme segmenter. I did not derive or verify a full state machine for every relevant rule and version under this pass's evidence restrictions.

The result also does not imply that all downstream work is small:

- Materializing k changed boundary decisions still requires output proportional to k.
- A glyph or line layout may change over a large region even when the indexing structure updates cheaply.
- Font shaping, bidi resolution, line breaking, and native layout queries need their own valid summaries or a different strategy.
- An opaque platform layout operation does not become compositional because a text index is compositional.
- The mechanism supplies no native input ownership, formatting intent, history, or accessibility protocol.

I considered generalizing this into a document runtime where every text and layout fragment is a composable state transformer. The idea becomes incomplete at the point where the state must represent font-dependent shaping, width-dependent wrapping, and potentially extensive context. I did not construct a compact exact summary for those obligations.

This is a promising indexing technique, conditional on completing its analysis model. I cannot promote it into a solution to the whole editing problem.

## 4. Information limits that defeated other directions

These arguments identify missing information under stated assumptions. They do not prove that production rich-text editing is impossible, or that every platform has the weak contract described below.

### A callback sequence number does not identify its coordinate frame

Suppose a native system has observed both of these text images:

```text
Old image:     ab
Current image: xab
```

It delivers:

```text
Replace UTF-16 interval [1, 2) with Q
```

There are two possible interpretations:

```text
Addressed old b, transformed into the current document: xaQ
Addressed current a:                                  xQb
```

If the protocol permits both origins and exposes no distinguishing information, a deterministic recipient cannot select the intended result from that message alone.

Assigning a sequence number when the callback arrives does not recover when its offsets were chosen. Maintaining an old and a current document does not reveal which one the callback meant. A CRDT does not add an absent origin stamp.

The argument is conditional. A real platform can rule out one interpretation through its ordering contract, an explicit revision, a connection transition, synchronous serialization, or some other demonstrated guarantee. If it does, the ambiguity is resolved by that guarantee.

Therefore a new architecture cannot claim to eliminate native coordination merely by adding versioned snapshots. It must state and establish the contract that connects each offset-bearing operation to its coordinate frame.

### Text replacement does not reveal character provenance

Suppose the visible source is:

```text
first a: bold
second a: plain
```

The native proposal replaces the whole `aa` with `a`.

An imagined semantic history retaining the first character and one retaining the second character produce the same text proposal. Their provenance-preserving formatting results differ.

This does not make the native replacement invalid. An editor can define replacement as inserting fresh text with destination attributes. It can also adopt a deterministic matching policy. What it cannot do is claim to have recovered the unreported semantic history from the strings alone.

Longest-prefix matching, minimum edits, probabilistic matching, and stable character IDs inside the editor do not supply identities omitted by the input protocol. They implement policies or estimates.

An architecture claiming perfect formatting preservation under arbitrary opaque rewrites must either obtain richer correspondence, narrow the guarantee, or acknowledge its policy choice.

### A plain-text projection has no unique rich inverse

Different rich documents can project to the same native text:

- Equal characters with different attributes.
- A line feed representing a soft break or a structural boundary.
- Equal labels representing distinct link identities.
- An object-replacement character representing different embedded objects.

A projection-based editor needs retained semantic information and explicit rules for how incoming mutations affect it. Calling that projection a lens, compiler, virtual machine, or intermediate representation does not automatically construct the missing inverse.

A restricted, precisely specified inverse can be useful. I did not derive one that removed the central integration and intent decisions for the requested document model.

## 5. Other directions considered and why I did not select them

| Direction | What could improve | Why I did not count it as the breakthrough |
|---|---|---|
| A native editor temporarily owns the active paragraph; the rest is drawn in Compose | Reuse native input behavior in the active region | Ownership handoff, cross-paragraph selection, geometry agreement, and accessibility remain. Moving the editing boundary does not establish that these transitions work. |
| One authoritative native buffer with a derived structural document | Simple input-side authority | Native text mutations still need a defined interpretation for hierarchy, semantic attributes, objects, and history. The lossy projection problem returns. |
| A separate input snapshot with document commands rebased around it | Potentially isolate asynchronous native work | Rebasing cannot infer an absent callback coordinate frame or unreported character provenance. A strong input contract remains necessary. |
| Persistent insertion ports or a graph of stable cuts | Better lifetime and identity for selections and typing contexts | Useful internally, but external protocols still deliver offsets. The graph needs a verified translation and explicit deletion/rewriting policies. |
| A structured token stream instead of a tree | Potentially unify some indexing and transaction operations | Encoding structure as tokens changes storage. It does not, by itself, settle how a text-only input mutation affects invisible structure. |
| A document represented entirely as an edit program | Symbolic commands and dependency-aware history become natural | Evaluation, compaction, selection identity, native input transfer, and reversible dependencies still need complete semantics. An expression language alone is insufficient. |
| Canvas on every target with a semantic/native companion | Shared drawing model and presentation control | Input and accessibility capabilities still require demonstrated host contracts. A companion representation creates consistency obligations. No permitted experiment here establishes that they are solved. |
| GPU work, speculative layout, or caching many possible edit outcomes | Potential throughput and latency improvements for particular workloads | No exact bounded representation for all relevant shaping and layout effects was constructed. These approaches do not settle semantic correctness. |
| Verification machinery as the primary new foundation | More bugs might be caught before release | Verification proves the stated model. It cannot supply an unstated native ordering guarantee or missing user intent. A correct model of the difficult boundary is still required. |

Several of these approaches might be excellent engineering choices. Their rejection here is a rejection of an unsupported breakthrough claim, not a claim that they cannot produce a good editor.

I also considered combining the symbolic-slot model, dependency-aware history, and transition-summary indexes. The combination is attractive, but it does not resolve the gaps identified above. Combining two locally useful mechanisms is not sufficient evidence of a superior complete architecture.

## 6. What was actually checked

All checks below ran in memory during this pass. No external code or test corpus was read, and no platform application was executed.

### Symbolic-slot checks

The finite model used:

- Eight combinations of three Boolean attributes.
- Twenty-seven patches, with each attribute either unchanged, false, or true.
- 341 raw strings of length zero through four over four UTF-16 units: a Latin letter, a combining mark, a high surrogate, and a low surrogate.

The raw strings intentionally included incomplete surrogate sequences. This tested preservation of units in the tiny algebra, not their complete editing, rendering, or normalization behavior.

| Check | Cases | Result |
|---|---:|---|
| Decorated slot evaluation equals materialize-then-patch | 73,656 | Passed |
| Materializing the evaluated slot preserves units and effective attributes | 73,656 | Passed |
| Sequential patches equal their last-write-wins composition | 5,832 | Passed |

The materialization check is elementary by construction. These counts are not a substitute for coverage of native composition protocols, and they are not evidence of production reliability.

### Transition-summary checks

The restricted R/X model enumerated all 2,047 strings of length zero through ten.

| Check | Cases | Result |
|---|---:|---|
| Summary agrees with a direct scan, for each incoming state | 4,094 | Passed |
| Composed fragment summaries agree with a direct scan at every split | 40,962 | Passed |
| Associativity over strings of length at most three, for each incoming state | 6,750 | Passed |
| Old boundary decisions changed by prepending R to 100,000 R symbols | 100,000 | All changed, as expected |

This checks a small compositional analysis and its adversary. It does not verify the complete Unicode algorithm or any performance claim on Kotlin, Compose, or a device.

### Counterexamples

The computations also confirmed that the two interpretations of the offset example produce distinct results, `xaQ` and `xQb`. The formatting-provenance example was recorded as two possible semantic histories producing an identical plain-text replacement.

These are conditional information arguments. They must not be retold as observed platform bugs.

### Not checked

- Any real IME or native composition lifecycle.
- Any current Compose API, artifact, or platform implementation.
- Font shaping, bidi layout, or complete Unicode conformance.
- Browser editing, DOM composition preservation, or canvas integration.
- Editable accessibility.
- End-to-end latency, allocation, or memory retention.
- A complete structured editing or undo implementation.

## 7. What would change this verdict

For the symbolic-slot direction, a materially stronger result would need all of the following:

1. **A complete ownership-transfer model.** It must cover exact and overlapping rewrites, reconversion, marked-range changes, partial commits, and deletion of the containing structure. The result must preserve explicit formatting and selection semantics rather than silently converting every hard case into a forced settlement.
2. **A history model for interleaved actions.** Formatting issued during preedit must be undoable against the eventual input result, with well-defined ordering and dependencies. The model must handle structural changes and retained preimages as well as simple wrappers.
3. **A demonstrated native interpretation.** For each selected host, show what native object represents the slot, what preserves its identity, how offsets acquire their meaning, and what guarantees coherent queries and geometry. A mathematical slot cannot stand in for this evidence.
4. **A net improvement over the simpler alternatives.** The design should remove enough failure mechanisms to justify its new ownership and history machinery. A count of fewer lines in the text-storage layer is not the relevant comparison.

For the transition-summary direction, the next theoretical threshold is a complete, verified analysis model for the desired Unicode operations. The next architectural threshold is a concrete connection to layout and navigation that does not depend on treating an opaque shaping operation as a small composable function.

For a universal canvas surface, the threshold is an actual complete input-and-accessibility contract on the supported targets. Drawing throughput alone cannot settle that decision.

These are evidence thresholds, not an implementation roadmap I am asking you to accept. I am not using the absence of experiments to claim that none of these directions can work. I am stating why the work completed here does not justify selecting one as a breakthrough.

## 8. Final disposition

The strongest result is a limited algebra for commands that target an entire input-owned slot. The strongest algorithmic result is a restricted demonstration of context-parametric text indexing. Both are useful ideas. Neither closes the main system-level gaps well enough to satisfy the requested standard.

**I did not find a qualifying breakthrough, and I have not packaged a variation of v1 as plan v2.**
