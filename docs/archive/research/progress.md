# Breakthrough investigation

## Acceptance rule

A candidate must remove a demonstrated failure in an existing editor or an explicit baseline, pass an executable test run here, and survive a targeted search for equivalent prior art. A renamed or recombined known technique does not qualify. A search with no match is bounded evidence, never proof that no equivalent exists.

`plan-v4.md` is reserved for a candidate that satisfies that rule. The existing plans are evidence to challenge, not established facts.

## 2026-10-09 — Starting exclusions

- **v1:** a conventional production architecture and proposed tests. Not evidence of invention or runtime correctness.
- **v2:** composition slots and context-parametric summaries do not meet the present acceptance rule. The partial-range identity problem remains unresolved; toy tests are not a production editor demonstration.
- **v3:** the all-starts line-successor tree has acknowledged prior art. Conditional validity, dependency tracking, dynamic trees, and publication contracts do not turn that underlying technique into a new invention. Its reduced-model tests remain useful engineering evidence, not a breakthrough claim.
- The supplied report path is `C:\Users\shaya\Downloads\deep-research-report-chatgpt.md`. Two direct filesystem checks in this continuation report it absent. Earlier cached report context is not being treated as verified source evidence.

Next work starts with an observable baseline failure and an explicit falsifiable mechanism, instead of producing another system architecture before testing its central claim.

## Direction 1 — Preserve attributes through temporary grapheme joins

**Hypothesis:** separate stored scalar attributes from the effective style of a rendered grapheme. This avoids overwriting attributes when inserting a joiner makes previously independent characters one grapheme.

**Executed:** `node research/grapheme-style-roundtrip.mjs`, Node v24.19.0, runtime Unicode 17.0. In the explicit v1 normalization baseline, bold WOMAN + italic LAPTOP, insertion of ZWJ, and deletion of that same ZWJ changes LAPTOP to bold permanently. Retaining the original scalar attributes preserves the original document. All 16 combinations of four left/right styles passed the retained-attribute round trip and matched the baseline's effective joined-cluster style. This is an executed model of the specified baseline, not a reproduced Compose runtime bug.

**Prior-art check:** [NSAttributedString](https://developer.apple.com/documentation/Foundation/NSAttributedString?language=objc) already separates character-indexed attributes from rendering; [HarfBuzz cluster levels](https://harfbuzz.github.io/the-distinction-between-levels-0-and-1.html) explicitly support distinguishing marks for features such as separate coloring. These sources do not prove every host implements this exact product rule, but the mechanism is ordinary retained character attribution and derived presentation.

**Disposition: rejected as a breakthrough.** A concrete correction to v1, with a passing test, but the purported invention reduces to an existing attributed-text model. It also leaves the semantics of a user formatting an already merged grapheme to be specified. Do not promote this into v4.

**Existing implementation control:** `node research/existing-attribution-control.mjs` passed against `prosemirror-model@1.25.12` and `prosemirror-state@1.4.4`. ZWJ insertion/deletion restores the original text and marks without Undo. This independently confirms that the essential retention property already exists in an editor model.

## Direction 2 — Bracket context restoration for incremental bidi queries

**Hypothesis:** for a fixed isolating run sequence after weak-type resolution, a matched bracket subtree can summarize its effect on preceding-strong context as identity or a reset to the embedding direction. Query the resulting strong events lazily instead of eagerly storing every resolved level. This investigates a nonlocal bracket dependency absent from v2's grapheme toy model.

**First executable result:** `node research/bidi-context-tree.test.mjs` passed 48,314 fixtures, 1,649,259 level comparisons against the installed `bidi-js@1.1.0`, and 8,910 incremental substitutions. Tests include malformed nesting, both paragraph directions, and bracket-stack limits at depths 62, 63, 64, 65 and 126. The oracle declares Unicode 13; this does not establish full Unicode 18 conformance.

**Adversary executed:** changing the initial Latin character to Hebrew in a 300,001-character paragraph containing 100,000 `(HEBREW)` groups changes 200,001 resolved levels. The tree refreshed 17 nodes and used 247 node visits for seven selected point queries. One local sample: construction 145.749 ms, full baseline analysis 50.7554 ms, tree substitution 0.0326 ms. These timings exclude drawing and are a single sample, not a phone benchmark or latency guarantee. Full output still costs at least its size.

**Scope:** only L/R/ON/WS, ASCII paired brackets, one run sequence and a fixed base direction. Accelerated edits preserve bracket topology and length. Weak types, NSM, explicit controls, topology repair, insertion/deletion and glyph layout are not implemented. A local edit that changes many eager levels is a demonstrated baseline work requirement; it is not evidence that bidi-js returns incorrect levels.

**Status:** candidate under adversarial review, not a breakthrough. The algebra is a specialization of Unicode's existing N0 semantics. Whether its incremental representation contributes anything beyond known context summaries/attribute evaluation remains an explicit novelty question. `plan-v4.md` has not been written.

**Prior-art disposition:** [ICU's implementation](https://github.com/unicode-org/icu/blob/main/icu4c/source/common/ubidi.cpp) stores the preceding strong context with an opening bracket and uses it in bracket resolution. [Incremental attribute evaluation](https://jeroen.bransen.nl/phdthesis.pdf) is established work. Neither source alone is an exact implementation of this prototype, and the targeted search did not locate an exact incremental-bidi equivalent. Nevertheless, the present mechanism consists of applying context-indexed subtree summaries to existing bracket semantics. Under the user's explicit exclusion of recombining known techniques, the restricted prototype does **not** yet qualify. Keep its test and performance evidence, but reject it as grounds for v4. A new result would have to remove the unresolved context/topology barrier through a mechanism beyond that recombination.

## Direction 3 — Canonical-equivalence coordinates without changing stored text

**Hypothesis:** compare the decomposed Unicode forms of an old and new input string, and transport rich positions through that correspondence while preserving the native strings verbatim. This could avoid treating a canonically equivalent rewrite as unrelated text.

**Prior-art check:** [ICU Normalizer2](https://unicode-org.github.io/icu-docs/apidoc/released/icu4c/classNormalizer2.html) already supports normalization with correspondence records. [Edits.Iterator](https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/text/Edits.Iterator.html) provides source/destination index translation and explicitly documents the lack of correspondence inside changed spans. Composing two such maps through a common normalized view is a combination of existing operations. Finer-grained provenance for equal reordered marks still requires an explicit policy; normalization does not recover missing identity.

**Disposition: rejected before implementation.** The basic mechanism has prior art, and the stronger identity claim is not justified. No baseline or candidate runtime test was run for this direction, and none is claimed.

## Direction 4 — Conditional bracket summaries for topology repair

**Hypothesis:** cache a fragment's empty-stack execution, its escaping closer types and maximum stack depth. Reuse it for an incoming stack when no escaping closer can match that stack and there is sufficient capacity. The hoped-for repair bound was proportional to incoming stack depth, since a triggering closer consumes an external opening.

**Executed falsification:** `node research/bracket-guard-falsification.mjs`. Enter `[)(]` repeated with one unmatched `(`. The two executions alternate which unmatched opening they retain; the difference regenerates instead of being consumed once. For 64, 256, 1,024, 4,096 and 16,384 characters, the guarded tree visited 95, 383, 1,535, 6,143 and 24,575 nodes, respectively. Slow-path leaves were 16, 64, 256, 1,024 and 4,096. All final stacks agreed with the direct reference, including opening identities. The incoming stack depth was one throughout the comparison setup.

**Disposition: rejected by counterexample.** Correct conditional reuse does not imply the claimed bounded repair cost. Caching an additional incoming-state case would fix this particular adversary but return to the context-enumeration approach rather than establish the missing general bound. This is not an impossibility result for other algorithms. The falsification omits unrelated Unicode stages and uses shallow stacks; it does not claim full UAX #9 support. Its preprocessing copies fragments and is not a production implementation.

## Checkpoint

Four directions assessed; none meets the active goal. Three have executable evidence, and one was rejected at the prior-art check. Source and reproduction instructions are in `research/README.md`. No production Compose, native IME, accessibility, or device-latency result was established. No v4 specification was written. The research goal remains active; this checkpoint does not mark it complete or blocked.

The report remains unavailable at the exact supplied path. A filename-only check for `deep-research-report-chatgpt*` in that Downloads directory also returned no match. No unrelated Downloads file was opened.

## 2026-10-09 — Continuation audit

The preceding substantive turn was progress: it created reproducible experiments and rejected hypotheses using executed evidence. The following short continuation only rechecked files and encountered a failed search request; that was no progress. No process was left running. This continuation resumes independent work, not a blocked wait.

## Direction 5 — Candidate-path-independent formatting

**Baseline executed:** `node research/composition-path-dependence.mjs`. Starting with bold `a` and italic `b`, an explicit common-prefix/suffix reconciliation policy preserves the marks for direct `ab -> ab` but loses italic after `ab -> x -> ab`. Actual ProseMirror model/state operations were used. This is a failure of the tested reconciliation policy, not a claim about ProseMirror's browser IME implementation.

**Candidate:** retain the pre-composition attributed snapshot and lift every candidate from it, accounting separately for explicit application changes. This prevents transient candidate strings from progressively erasing source formatting.

**Adversarial limit:** restoring the snapshot alone can erase deliberate formatting performed during the composition. Partial commits and rewrites expanding outside the original range require additional scope and correspondence semantics. Plain replacement strings do not establish which repeated character retained its identity.

**Prior-art assessment:** [WPF TextStore](https://source.dot.net/PresentationFramework/System/Windows/Documents/TextStore.cs.html) already has composition undo units, replay, and final-state selection between IME/application changes. It is not evidence of this exact path-independence guarantee. Nevertheless, the proposed fix is an ordinary retained preimage plus replay, and expanding it returns to the unresolved slot/ownership direction in v2.

**Disposition: rejected as a new architecture.** Keep the executed path-dependence regression as an additional correctness requirement. Do not claim that storing a snapshot resolves native lifecycle or partial-range identity. No new candidate implementation or Compose/device result is claimed for this direction.

## Direction 6 — Test the shipped Compose state before replacing it

**Executed baseline:** `research/compose-probe` compiles and runs against Maven Central's `foundation-desktop:1.12.1`. The relevant published source files match the GitHub tag by SHA-256. Its styled-text feature flag defaults to enabled. This is direct Desktop JVM evidence, not four-platform certification.

Starting with bold `a` and italic `b`, whole replacements `ab -> x -> ab` remove the styles under all four expansion policies. Undo twice restores `ab` without either original style. Adding bold across `ab` after clearing history creates an undo entry; undoing it removes all styles, including those preceding that transaction. Removing a style or changing its value or range creates no undo entry in the isolated fixtures.

A test-only reflection seam installs an accepted composition `[0,2)` using the library's internal composition setter and user-commit method. Each of add/remove/change/range style mutations clears that composition. The source rebuilds the main buffer on a style change without copying composition. No actual platform keyboard, candidate window, text layout, or input connection was exercised.

**Disposition:** useful new baseline evidence, not a breakthrough. Attribute-aware undo records and preserving a composition across text-equivalent style edits are conventional fixes. Merely fixing these defects does not satisfy the novelty requirement. Keep the executable assertions and precise boundaries of the evidence; do not count reproducing a failure as a passing new mechanism. Reproduction, hashes, sources, and output are retained in `research/compose-probe`.

**Prior-art check:** Qt's [QTextDocument documentation](https://doc.qt.io/qt-6.5/qtextdocument.html) explicitly includes character, block, and block-group formatting changes among undoable operations. This rules out treating formatting-aware history itself as an invention. Qt was not executed in this investigation, and this citation makes no claim about its composition behavior.

## Direction 7 — Certify attribute transfer by agreement of all optimal alignments

**Candidate:** calculate all optimal text correspondences and transfer a source attribute only when all correspondences agree about its value. This would expose ambiguity instead of hiding a diff tie-break behind apparently exact formatting.

**Early rejection:** optimal-alignment ambiguity and annotation transfer through alignments are established topics: [Wheeler, 1994](https://pubmed.ncbi.nlm.nih.gov/7994113/) and [Geneious annotation transfer](https://manual.geneious.com/en/latest/Annotations.html). They are not evidence of this exact editor policy, but this candidate is precisely a combination of existing alignment enumeration and consensus. More fundamentally, agreement among minimum-cost histories does not establish the actual native edit history; a real replacement need not minimize edit distance. A detector alone does not repair the editor's missing information or satisfy the requested behavior. No new implementation or execution is claimed. Do not spend another prototype on this direction.

## Direction 8 — Restart a bracket scan after consuming external context

**Hypothesis:** each fragment stores its empty-stack result and the first escaping closer of every bracket type. With an incoming stack, jump to the first closer that can consume an incoming opener, discard the internal temporary stack, and resume from a cached suffix. The number of such jumps within one invocation is bounded by the incoming depth. This differs from trying to converge two scans, but requires suffix summaries to be cheap.

**Executed:** `node research/bracket-continuation-falsification.mjs`. The implementation matched a direct scanner in 12,300 randomized initial-state, mutation, and incoming-stack/suffix checks. The `[)(]` adversary exposes the omitted cost: for 64 through 16,384 characters, query range visits grow from 63 to 16,383; one substitution at the first character grows from 58 to 16,370. Incoming depth for the query is one. Recursive suffix construction creates new scan invocations, so the per-invocation depth bound does not bound total work.

**Disposition: rejected by execution.** The bookkeeping is correct for the restricted scanner, but the claimed performance benefit is false. Uncharged all-suffix preprocessing would hide the problem, not prove a cheap dynamic update. The test does not implement UAX #9's bounded-depth behavior, directional resolution, shaping, or IME. No novelty search or full editor claim is needed for a candidate already defeated by its own cost test.

## 2026-10-09 — Report requirement waived

After another exact-path check returned no file, the user explicitly answered "Proceed without the report." Continue using the three plans, verified sources, and executed experiments. The unavailable report is no longer a missing prerequisite or a blocker.

## Direction 9 — Normalize transactions before committing native effects

**Executed baseline extension:** eight transaction fixtures in `research/compose-probe` preserve final text, styles, and selection. An empty transaction, value/range/policy changes reversed within the transaction, and exceptions thrown after style or combined text/style changes preserve seeded composition and empty undo history. Exception rollback therefore passes these fixtures.

Adding then removing a style in one successful transaction still clears composition and creates an undo entry. Editing then calling `revertAllChanges()` restores text/styles/selection with no undo entry, but clears composition. These results were run against the published Desktop artifact; no device input trace was executed.

**Candidate and disposition:** reduce an application transaction to its final semantic delta before issuing native effects; preserve the protocol state when the native text observation is unchanged. This is established transaction normalization and observer-specific notification, not a new editing architecture. Reject it as the requested breakthrough. Keep the fixtures as evidence that operation logs, final document equality, exception rollback, and composition continuity are distinct obligations. Do not generalize the failures to exception rollback or claim four-platform reproduction.

## Direction 10 — Canonicalize formatting by expansion-policy channels

**Executed candidate:** `ChannelProbeKt` against published Compose Desktop 1.12.1. Three character-indexed newest-stamp channels represent `AtStart`, `AtEnd`, and `AtBoth`; insertion reads respectively the right neighbor, left neighbor, and max of both. Overlay writes replace the selected lane; deletion removes cells; replacement deletes then inserts. The result matched all 60,000 randomized comparisons (30,000 with direct replacement enabled). This is a restricted scalar-attribute test, not proof of a minimal representation or a performance result.

**Executed obstruction:** a fourth visible-value channel is insufficient for `InsideOnly`. On `abc`, spans 1:`[0,3)`, 2:`[0,2)`, 3:`[1,3)` display `[2,3,3]`. Removing hidden span 1 leaves the same current appearance. Deleting `b` then inserting `x` gives `[2,1,3]` with span 1 but `[2,0,3]` without it. Hidden interval connectivity is behaviorally observable. Arbitrary removal by handle and selective history would also invalidate assumptions behind the three-channel quotient.

**Prior art and disposition:** [GNU Emacs explicitly distinguishes character properties from interval identity](https://www.gnu.org/software/emacs/manual/html_node/elisp/Not-Intervals.html), including edit/coalescing consequences. This is not evidence of the exact three-channel formula. Nevertheless, splitting ordinary attributed runs by three policies does not yet demonstrate a distinct general mechanism, and the complete boundary model remains unsolved. Reject as the requested editor architecture; retain the restricted result and counterexample, without calling the latter a Compose bug. Source, invocation, and output are in `research/compose-probe`.

## Direction 11 — Preserve native coordinate meaning while enforcing Unicode contracts

**Executed:** `UnicodeProbeKt` uses the published Compose 1.12.1 Desktop artifacts. Public selection helpers retain offsets inside a valid surrogate pair despite their documented adjustment promise. Raw UTF-16 deletion can leave an isolated surrogate; that alone is not a defect in a UTF-16-unit operation. The legacy code-point command correctly removes a valid pair but removes a malformed high surrogate as well, contradicting its documented no-op requirement.

The test also invokes private adapter factories from the artifact, rather than reimplementing their code. The legacy Skiko `deleteSurroundingTextInCodePoints(1,0)` queues a `DeleteSurroundingTextCommand` in UTF-16 units; executing it on one emoji leaves an isolated high surrogate. The state adapter removes the valid pair correctly but removes the malformed surrogate too. No OS input session, rendered caret, actual keyboard, or Android/iOS/Web execution is claimed.

**Candidate and disposition:** typed coordinate units, scalar-range preflight, and separate protocol/user-navigation policies can prevent these errors. Those are established mechanisms already called for in v1, so this is further baseline evidence rather than a breakthrough. [Android's contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection#deleteSurroundingTextInCodePoints(int,int)) and [Compose's command documentation](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/input/DeleteSurroundingTextInCodePointsCommand) already state the distinction. Do not call routine contract repairs new architecture. Reproduction and limitations are documented in `research/compose-probe/README.md`.

## Direction 12 — Undo formatting that follows later candidate text

**Executed:** `node research/composition-history-probe.mjs`, using `prosemirror-history@1.5.1` with the existing pinned state/model packages. The explicit integration policy excludes native candidate replacements from history and copies the earlier bold attribute onto their replacement text. With no candidate rewrite, undo removes bold correctly. With `nihao -> 你好`, undo consumes the formatting entry but leaves the final text bold. Replacing/committing `ni` first and `hao` second yields the same failure. Snapshot rollback instead restores `nihao`. A test helper initially mistook a null mark result for a present mark; fixing it made the unchanged-text control pass. The recorded conclusions use the corrected test only.

This is evidence against combining candidate-inherited formatting with ordinary range-step undo without representing their dependency. It is not a ProseMirror browser IME regression, nor proof that ProseMirror's history violates its documented contract. Replacement text explicitly arrives with new marks in this model, and that distinction matters.

**Disposition:** a stable formatting action whose scope follows candidate descendants can address the fixture, but it restates v2's carrier direction; selective history and replay are established. [ProseMirror's guide](https://prosemirror.net/docs/guide/) documents mapped selective history, and [WPF TextStore](https://source.dot.net/PresentationFramework/System/Windows/Documents/TextStore.cs.html) implements composition undo/replay machinery. Neither source is claimed to supply this exact desired policy. Reject as a new architecture rather than disguise the earlier proposal. The executable fixture adds evidence to the unresolved history obligation.

## Direction 13 — How much invisible span state can future edits expose?

**Executed:** `HiddenStateProbeKt` passed 8,500 independent queries against published Compose Desktop 1.12.1. For each interval `[s,e)` of length at least two in an n-character document, create one `InsideOnly` span with an independent Boolean font-weight value. Add longer intervals first. Deleting the interior of a chosen interval, preserving its first and last characters, and inserting one character between them exposes that interval's bit: only enclosing intervals can cover the insertion, and the chosen interval has priority over every strict enclosing interval.

Fix all length-two spans to zero. The current document then displays zero everywhere regardless of the longer spans, but the same experiments still recover each of their `(n-1)(n-2)/2` independent bits. Tested n values were 2, 3, 4, 8, 12, 16 and 24, including uniform, alternating, and seeded random assignments. At n=24, there are 253 independent hidden bits beneath an identical visible document. The mathematical construction, rather than the finite samples alone, establishes the information bound: preserving every future observation of these span semantics requires at least that many bits in the worst case.

**Limits and disposition:** this is a lower bound relative to text length, with a quadratic number of input spans. It does not prove a leak, unexpected superlinear growth relative to input size, a time bound, or that ordinary attributed-text editors require quadratic memory. Different product semantics can discard overridden interval identity. This rules out a universally linear-in-text exact compression of arbitrary overlapping `InsideOnly` spans; it supplies no corrective mechanism and is not the requested breakthrough. No claim of historical novelty is made for the elementary information argument. Do not promote it into v4.

## Direction 14 — Update shaping through font execution dependencies

**Candidate:** retain dependencies of font lookup execution, including unsuccessful context matches and intervening gaps, and repair affected lookup decisions after an edit. This could avoid conservatively reshaping a whole run, provided changes to lookup order, skipped glyphs, and script preprocessing are represented.

**Prior-art result:** [adaptive functional programming](https://www.cs.cmu.edu/~rwh/papers/afp/toplas06.pdf) already records data/control dependencies and propagates changes through execution traces. [HarfBuzz's buffer contract](https://harfbuzz.github.io/harfbuzz-hb-buffer.html#hb-glyph-flags-t) already describes partial reshaping and validated splicing. [HarfBuzz issue 6062](https://github.com/harfbuzz/harfbuzz/issues/6062) specifically investigates denser boundaries for incremental shaping and reports a prototype. These are separate mechanisms; they do not establish that a complete font-execution dependency engine already exists. They do establish that the current proposal has not supplied a mechanism beyond applying known dependency tracking to shaping. Font-wide glyph dependency graphs used for subsetting are also not equivalent to a text-instance execution trace and must not be cited as such.

**Disposition:** reject the present proposal before implementation. There is no executed candidate, verified speedup, or full shaping correctness result for this direction. A more specific algorithm would need a new bound or capability and tests against a real shaper; the general dependency-graph proposal is insufficient.

## Direction 15 — Remove formatting records that no future edit can expose

**Candidate:** derive an exact compact representation of the future observable behavior of overlapping spans, retaining hidden connectivity from direction 13 while discarding permanently dominated records.

**Contract check:** the published `TextFieldTextStyles.getSpanStyles` contract returns the individual overlapping records in insertion order, and `TextFieldBuffer.addStyle` explicitly creates an independent range and returns its handle. Removing even a permanently visually dominated record can therefore change an immediate API observation; later handle removal or mutation makes this distinction stronger. An optimization preserving only resolved character attributes and future text edits does not preserve that full API. It would require an explicit different editor-level observation contract and a separate history policy.

**Disposition:** do not claim exact Compose-compatible compaction or classify independent span retention as a Compose defect. This continuation yields a contract constraint, not an executed compactor. A custom property-only editor contract returns to the previously rejected attributed-text direction; no new mechanism has been established. The source evidence is in the already downloaded published 1.12.1 `TextFieldTextStyles.kt` and `TextFieldBuffer.kt`. No v4 or novelty claim follows.

## Direction 16 — Preserve structure across opaque native replacement

**Audit:** revisited paragraph/cell boundaries against v1 and v2. A plaintext replacement does not distinguish a soft line break from a paragraph boundary or supply omitted structural identity. Separate cell flows, retained structural metadata, and explicit replacement policies were already considered in the prior plans. No new inverse or source of information was derived. Reject the present direction without another prototype; no runtime reproduction or new solution is claimed.

## Direction 17 — Compute suffix transfers instead of recursively rebuilding them

**Derived and executed:** a right-to-left recurrence computes, for every suffix, the first otherwise-unmatched closer of each type, first attainment of each relevant stack depth, and a shared final stack. An incoming-stack query jumps only when one of its own entries is consumed, checking depth overflow before each jump. This supplies the suffix data missing in direction 8. `bracket-suffix-index.test.mjs` passed 111,796 transfer comparisons and 43,758 independent checks of the summary meanings. The repeated `[)(]` adversary requires four indexed probes from lengths 64 through 65,536. The initial substitution implementation still rebuilt all rows before the edit, so this static result alone failed the editing requirement.

**Dynamic extension executed:** per-block suffix indexes support variable-length replacement and a bracket-pair query at an arbitrary character. `bracket-block-index.test.mjs` passed 183,301 pair comparisons across 6,400 edits, with a direct scanner as oracle. A block-fragmentation flaw in the first partition scheme was corrected by even repartitioning and checked with 1,000 repeated near-end insertions. With n=65,536 and block size 256, substitutions at the beginning, middle and end each rebuilt 256 characters; the recorded queries scan one 256-character block and use 257–1,020 indexed probes across 256 blocks. The reference scans 65,536 characters. These are different operation counters, not a measured end-to-end speed ratio.

The implementation now allocates a depth column only when some suffix reaches it. Initial dense-table measurements are preserved separately. Capacity overflow follows the current [BD16 requirement](https://www.unicode.org/reports/tr9/#BD16) to invalidate all pairs in the isolating run sequence, including earlier matches. Only ASCII paired brackets and the stack/pair component are implemented; no full Unicode, N0, native IME, shaping, geometry, or cross-platform result is claimed.

**Status:** a more specific algorithmic candidate, not yet the requested breakthrough or v4 architecture. The derivation, proof argument, exact complexity scope, and reproduction commands are in `research/bracket-suffix-index.md`. Searches did not locate the exact recurrence, but the prior-art review is incomplete, including comparison to dynamic language membership and incremental parsing. Block decomposition itself is established and is not claimed as an invention. Required next evidence: an external conformance oracle, the full bracket alphabet and canonical equivalence, adversarial memory/query costs, and a directional-layout integration that preserves the benefit rather than reintroducing a whole-paragraph scan.

**Further executed evidence:** the depth-adaptive block fixture uses 1,841,152 typed-array bytes at n=65,536, versus 18,420,736 in the retained dense-table run. The unmodified Unicode-hosted Java reference was compiled and run: 1,967 non-overflow fixtures agree on pair locations, with 72,490 total position checks including overflow cases. Its own ReadMe declares Unicode 8.0. On `()` plus 64 opening parentheses it retains the earlier pair, while the current BD16 rule and candidate discard it. This version difference is explicitly recorded, not hidden by changing the candidate to match an old oracle. Current full-Unicode conformance remains unproven. Reproduction and source provenance are in `research/unicode-oracle/README.md`.

**Directional extension executed before the pause:** `bidi-block-context.mjs` adds symbolic N0 context references and shared window queries for classified L/R/neutral tokens and the three ASCII bracket types, in one isolating run. Complete pairs summarize their outgoing preceding-strong context as embedding direction or identity; pending pairs use references resolved when their matching closer is found. The direct-model suite passed 131,820 point checks and 12,584 window checks across 3,000 variable-length edits. `bidi-block-context-oracle.test.mjs` separately passed 1,749,721 direction comparisons in 111,690 windows across 12,418 requests against the unmodified Unicode 8 Java reference. Two overflow requests were explicitly excluded from that agreement. No weak-type rules, NSM inheritance, Unicode classification, isolates, shaping, or geometry are included.

**Measured scope and cost:** `bidi-block-context-scaling.test.mjs` passed 7,056 comparisons, including malformed/deep inputs, topology changes and late overflow. For `L` followed by 65,536 repetitions of `(R)`, replacing the initial L by R changes 131,073 of 196,609 directions. With target block size 444, one recorded Windows/Node run measured a median 0.7922 ms for replacement plus the final 48-token window, compared with 15.7058 ms for `bidi-js` whole-paragraph processing and slicing. Candidate build time was 37.2352 ms and typed arrays occupied 20,491,636 bytes. The candidate receives classified tokens and a maintained index; bidi-js receives Unicode text and performs full UBA. This is not a like-for-like full-algorithm comparison or an editor speedup. Query traversal remains paragraph-wide in blocks; fixed block size makes its block-visit count linear in paragraph length. Exact counters, warmup/sample counts and limitations are retained in the output JSON.

**External review supplied by the owner:** Fable reported passing the candidate suites, expensive memory and paragraph-wide block visits, plus distant-edit examples changing an early window. Those cost/scope concerns are valid. Its claimed impossibility of compact composition was not established: its specific all-closer example, `)]` repeated, stabilizes after at most K complete cycles for incoming depth K. Each cycle either consumes an entry or has no effect forever thereafter. A forward dependency also does not by itself require a query-time scan; maintained summaries may carry global information. These observations do not prove that a useful compact general summary exists. Fable's scratch-script results have not yet been independently reproduced. Its proposed bracket-versus-neutral paragraph timing would mix bidi, shaping, and wrapping effects and cannot isolate a bidi percentage. Status remains an unqualified candidate; no v4.

## 2026-10-13 — Close direction 17 after native Compose benchmark

The clean LayoutProbe JVM run completed with exit 0. At 12,289 tokens, the mixed-direction fixture measured 218.4691 ms for Compose intrinsics and 0.2205 ms for separate native bidi construction; at 24,577, 2,558.4240 ms and 1.5915 ms respectively. These different API measurements do not establish a precise stage percentage. Given its memory, incomplete Unicode scope, paragraph-wide block traversal and absent end-to-end benefit, drop the bracket index as the research direction. Full results, limitations and reproduction commands: `research/compose-benchmark-verdict.md`.

The previously supplied Fable objections were independently checked: `bracket-review-objections.test.mjs` reproduces both distant dependencies and passes 1,635 finite-stack stabilization checks. This confirms query costs and rejects the specific repeated-closer lower-bound argument; neither result rescues the direction.

## Direction 18 — Skip infinite-width layout

Executed FiniteWidthProbe against the actual Compose ParagraphLayouter. Finite-only layout passes 1,184 geometry, hit-test, selection-rectangle and raster-slice comparisons with infinite-then-finite layout, but retains the severe construction cost. Reject as the performance remedy. No invention claim.

## Direction 19 — Retire completed native shaping records

Pinned Skia m150 source retains completed fResolvedBlocks and repeatedly sorts/scans them in OneLineShaper::finish. This is a concrete quadratic bookkeeping mechanism worth distinguishing from intrinsic Unicode cost. A one-line clear is a routine corrective optimization, not the requested new architecture. Native validation was attempted but remains unsuccessful: baseline process exited -1073741795; patched compilation failed under selected C++17 settings. Do not claim a tested fix or speedup. The owner requested immediate closeout during this expansion; stop further experiments and report the completed benchmark. No breakthrough and no plan-v4.md.

## 2026-10-13 — Implementation consolidation authorized

The owner explicitly ended the novelty requirement and requested a corrected v1 implementation plan plus an independent Antigravity validation prompt. `plan-v4.md` is now the authoritative implementation specification, superseding the earlier reservation of that filename for a breakthrough. `antigravity-validation-prompt.md` supplies the independent audit/test workflow. Existing plans and experiment evidence are preserved.

Corrections include retained constituent attributes, immutable-baseline composition attribution with explicit partial-commit resets, separate raw protocol/drawable caret coordinates, lossless raw UTF-16 persistence, independent attributed history, property-only formatting semantics, structural native admission limits, verified settlement gates and evidence-scoped platform qualification. Existing native performance limitations remain explicit. This is documentation and design consolidation, not a production implementation or a claim that its new policies have passed runtime tests.
