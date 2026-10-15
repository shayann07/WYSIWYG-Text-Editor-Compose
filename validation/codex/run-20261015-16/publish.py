from pathlib import Path
import hashlib, json, csv, io, re

run=Path(__file__).resolve().parent
root=run.parents[2]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((run/'results.json').read_text())
assert r['passed'], 'Tests must pass before publishing'
for f,h in r['sourceHashes'].items(): assert sha(run/f)==h,f
old=(run/'sources/plan-v4.md').read_text(encoding='utf-8')
plan=old.replace('Revision: 4.11.0','Revision: 4.12.0')
plan=re.sub(r'Revision: 4\.12\.0[^\n]*','Revision: 4.12.0, 15 October 2026. Status: consolidated specification with scoped executable structural, admission and history contracts. Integrated public-boundary and real-platform qualification remain outstanding. No production implementation is authorized.',plan,count=1)
a=plan.index('## 4. IME');b=plan.index('## 5. Formatting')
plan=plan[:a]+(run/'contracts.md').read_text(encoding='utf-8')+'\n\n'+plan[b:]
plan=plan.replace('List contains list items; each item begins with a text block and may contain additional blocks/nested lists.','List contains nonempty list items; each item contains one or more blocks and may begin with a nested list, quote or object after an edit. This deliberate schema choice avoids inserting unrequested paragraph separators during normalization (§4.3).')
plan=plan.replace('data class InputStamp(val epoch: Long, val sequence: Long)','data class InputStamp(val epoch: Long, val inputGeneration: Long,\n    val sequence: Long, val adapterSessionToken: AdapterSessionToken?)')
plan=plan.replace('// InputResult: Accepted(receipt), Duplicate(previousReceipt), DuplicateReceiptExpired, StaleEpoch,\n// Unsupported(reason), Fault(recoveryRecord). Do not conflate these outcomes.','// InputResult: Accepted(receipt), Deferred(ticket), Rejected(reason), Duplicate(previousReceipt),\n// DuplicateReceiptExpired, Fault(recoveryRecord). Rejection reasons include stale identity and unsupported input.')
plan=plan.replace('- Cross-paragraph IME composition/reconversion is required across compatible paragraph/heading runs in a text flow, including same-parent paragraphs inside quotes and list items. Initial selection deletion, provisional paragraph joins and final text form one composition gesture and one undo group. Separate cell flows, atoms and incompatible schema boundaries retain explicit structural admission rules; they are not silently flattened into text.','- Initial IME replacement of a selected range supports cross-paragraph and cross-container text, removable selected inline/block objects, and whole tables in one flow. It is one provisional gesture and one undo group. Later unselected reconversion/expansion has stricter structural admission. Cells remain separate flows; protected objects and unrepresentable object-seam proposals have explicit handling (§4.3).')
a=plan.index('With the versioned locator in §4.5');b=plan.index('\n\n### 2.5',a)
plan=plan[:a]+'Storage bounds are defined in §4.8 and explicitly include hierarchy depth, touched pieces, attributes and anchors. No flat block-count logarithm covers arbitrarily nested containers. The array/tree-copy validation models do not establish production bounds or memory use.'+plan[b:]
plan=plan.replace('- `DeleteSelection`: remove selected inline extents, fully selected nodes/atoms, and selected structural separators where schema permits; retain unselected prefix/suffix and ancestry. Join surviving endpoints only when compatible and under the same parent after cleanup. Otherwise retain both containers and collapse to the start-side surviving text position. This is a semantic structural command, not promised to equal blind plaintext deletion across every container boundary.','- `DeleteSelection`: use the structural compiler in §4.3. Selected text endpoints may join across parents or code/prose classes with left block identity/type/properties winning. Direct sibling list items merge trailing children; other surviving ancestry and global leaf order remain unchanged. Selected removable objects can be consumed; locked content cannot. Table-cell selection uses the separate rule below.')
plan=plan.replace('- Post-command normalization removes empty list containers, preserves legal empty text blocks, and runs inside the same transaction. It must terminate; each cleanup rule reduces invalid structural nodes or establishes a missing required leaf once.','- Post-command normalization prunes empty containers, preserves the editable landing block, and follows §4.3. List items may begin with any block; do not synthesize a paragraph that changes the admitted native projection. Termination follows decreasing empty-container count and bounded tail moves.')
# A semantic Backspace is distinct from native opaque scalar seam replacement.
plan=plan.replace('Backspace at first list paragraph start lifts before joining.','Backspace at the leading gap/first text position of a list item lifts before joining; it does not assume child zero is a paragraph.')
a=plan.index('Admission rules:\n',plan.index('### 6.1'));b=plan.index('### 6.2',a)
plan=plan[:a]+'''Admission uses §4.2 target provenance and §4.3 structural compilation. Initial selected-range replacement can include removable atoms and cross-container endpoints without a preparatory deletion or second history group. Later unselected expansion is limited to its compatible text domain. Cross-flow text proposals reject; cell selections use explicit semantic commands. Retained tags preserve identities; new middle LF/U+FFFC are ordinary text units. Native proposal and result projection must agree exactly.

On an unsupported cancellable proposal, return the host's failure result with a structured reason. If native DOM/text has already changed noncancelably, retain the raw proposal and structural preimage and enter recovery rather than fabricate success or lose input. Object-seam handling and post-abort provenance are required host experiments. Keyboard commands that intentionally select/delete a block object use semantic commands, not a scalar splice that creates an illegal inline table.

'''+plan[b:]
# Replace a permission-like readiness claim with scoped status.
plan=re.sub(r'\*\*Status:.*?\n', '**Status: Specification and executable reference contracts; production and live-platform gates remain unqualified.**\n',plan,count=1)
# Clarify Web corrections without prescribing DOM mutation safety from character bounds.
start=plan.index('Projection equivalence compares text, structure')
end=plan.index('\n\n`beforeinput`',start)
plan=plan[:start]+'''Projection equivalence compares text, structure and the engine's effective rendering attributes, not raw constituent storage. An admitted raw text change may reveal different effective marks when a grapheme splits. Apply receipt-derived minimal mark corrections synchronously outside protected composing DOM; never reject an already admitted core edit merely because the candidate view's old marks differ.

During composition, protect the outward grapheme closure of the marked interval AND the actual composing DOM nodes/ancestors whose mutation would replace those nodes. Character intervals alone do not establish safe DOM writes. Keep a token/version-bound delayed-correction ledger for intersecting changes, including graphemes crossing marked boundaries. Maintain actual surface geometry and AT projection metadata for that temporarily divergent region. Recompute after every accepted edit; when the marked interval becomes null, retain the recorded protected node set until verified settlement. At settlement, derive corrections from the current canonical snapshot rather than replay stale queued mark operations, then clear the ledger. If minimal corrections cannot preserve the native composing nodes in the pinned view version, the Web gate fails. This DOM-write capability requires real-browser/IME qualification; the headless model does not implement it.'''+plan[end:]
plan=plan.replace('7. `cancelLatestCommittedText(attributes)`: unsupported in this document engine; explicitly returns `null`.','7. `cancelLatestCommittedText(attributes)`: unsupported in this document engine; explicitly returns `null`.\n8. `getCommittedTextLength()`: visible projected UTF-16 length minus active marked length (zero if no mark). Validate with forward/reversed selections and nonterminal unmark; do not substitute internal Dc length.')
plan=plan.replace('6. `getInsertPositionOffset()`: returns `markedStart` when composition is active, and `selectionEnd` when no composition is active.','6. `getInsertPositionOffset()`: return the native insertion landing point in committed-text coordinates: markedStart when marked text exists; otherwise min(anchor, focus), matching this engine\'s selected-range replacement policy. The numeric query supplies insertion context; popup geometry comes from getTextLocation, whose selection/line-direction rules are separate. This contract follows the distinction in the [JDK 23 InputMethodRequests specification](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), inspected 15 October 2026; real geometry and host behavior remain G0/G5 requirements.')
plan=plan.replace('8. Scoping: A headless Java','9. Scoping: A headless Java')
# History root comparison and connection cancellation must not be text-only.
plan=plan.replace('while an active or partially committed composition has modified document text or attributes','while an active or partially committed composition has modified semantic document state')
plan=plan.replace('Typographic changes must not','Typographic changes must not')
insert='\nPending Undo/format commands use the settlement-local action ledger in §4.6, including one-use cancellation/no-op credits and exact stack-top identity checks. Connection replacement/disposal cancels old pending commands with explicit lifecycle reasons (§4.5). History equality includes structure and resources, not only text/attributes.\n'
i=plan.index('\nInitial history budget:');plan=plan[:i]+insert+plan[i:]
plan=plan.replace('Gate G3: I07','Gate G3 additionally requires integrated replay of input reservations, structural replacement and the action ledger, including post-abort fences, changed terminal commits, and the new run-16 traces. The separate contract models alone do not pass G3.\n\nGate G3: I07')
plan=plan.replace('Gate G1:', 'Gate G1 includes the full measured hierarchy, version-wide locator maps, deep nesting, seam join/split counters, and physical heap/latency measurement; flat-index evidence is insufficient.\n\nGate G1:',1)
plan=plan.replace('Gate G1 (production implementation remains unauthorized by this document):','Gate G1 also requires the full measured hierarchy, version-wide locator maps, deep nesting, seam join/split counters and physical heap/latency measurement.\n\nGate G1 (production implementation remains unauthorized by this document):')
plan=plan.replace('including §4.7 cross-paragraph','including §4.3–4.7 cross-paragraph')
plan=plan.replace('### 9.5 Current evidence and historical corrections', '### 9.5 Current evidence and historical corrections',1)
plan=plan[:plan.index('### 9.5 Current evidence and historical corrections')]+'''### 9.5 Revision 4.12.0 review and evidence boundaries

The external review found real contradictory prose in 4.11.0. This revision replaces section 4 instead of appending another precedence patch. It chooses typed input provenance, a reservation registry, one tagged structural compiler, explicit left-owned replacement footprints, no retarget auto-commit, nonterminal overlay retention, exact visible native removal, explicit connection-command cancellation, and a settlement-local LIFO ledger. No claim of a perfect or universally verified editor is made.

Run-16 contains a hierarchical structural wrapper around the copied run-15 segment coordinator, a separate admission contract model and a separate history-ledger model. Tree/overlay tests are integrated; admission and history policies are exercised separately and are NOT wired through every public method of the copied legacy coordinator. The copied coordinator's old Undo admission behavior is not the normative 4.12.0 ledger. This distinction is an integration gate, not an undisclosed implementation substitution.

Current results are in `validation/codex/run-20261015-16/results.json`. Exact fixture trees, independent plaintext splices, schema/order assertions, rollback/Undo/Redo, bounded command sequences and behavioral mutants provide scoped evidence. These are arrays/full-tree copies and synthetic input stamps, not production persistent structures, native callback provenance, full Unicode conformance or DOM preservation. Earlier runs and the 4.11.0 standalone remain historical snapshots; their counts are not added to the current run or represented as fresh execution.

### 9.6 Required experiments and remaining qualifications

1. G0/G5: actual adapter provenance after forced cancellation/relocation, reservation timing under synchronous reentry, and qualification of safe fences. A locally minted token cannot prove the origin of a late OS callback. Preserve ambiguous input and block release if normal typing cannot be made reliable.
2. G0/G5 Web: composition over selected objects/containers, actual DOM-node preservation, delayed mark corrections crossing grapheme/ancestor boundaries, and candidate/projection synchronization.
3. G2/G3: integrate the selected admission, structural overlay and history ledger in one owner transaction; reproduce all new cases through that public boundary, with failure injection and duplicate receipts. Separate models do not constitute this gate.
4. G2/G6: list items beginning with nested lists/objects, explicit insertion gaps, marker layout, AT navigation and import/export round trips. This schema change must remain visible to product review.
5. G0/G5: native object-seam proposals, native marked-text query coordinate conventions, and exact initial user-selection provenance. Whole selected objects are supported; an impossible retained-object inline projection is rejected, not silently flattened.
6. G1/G8: depth-aware hierarchical indexes, fragmentation, retained-root memory, seam concat/split and full paragraph/layout latency. No unmeasured optimization or product cap is assumed.
7. G7 and existing U1-U7: physical durability, full Unicode data, native IMEs, screen readers and all platform release gates remain unqualified.

The reviewed counterexamples now have chosen semantics and executable targeted checks. This is a reviewable specification baseline, not proof that every interaction has been explored. Further empirical failures require a concrete counterexample and a versioned repair. No production implementation is authorized by this document.
'''
assert 'Revision: 4.12.0' in plan
assert 'Flushes overlay to $D_c$; clears `markedWindow`' not in plan
assert 'Missing replacement ranges are resolved' not in plan
assert 'Commands drained/cancelled' not in plan
(root/'plan-v4.md').write_text(plan,encoding='utf-8')
(run/'plan-v4.md').write_text(plan,encoding='utf-8')
details={x['name']:x['detail'] for x in r['results']}
report=f'''# Revision 4.12.0 — executable contract validation

Run: `validation/codex/run-20261015-16`. Environment: {r['environment']}. Executed: {r['timestamp']}.

All {len(r['results'])} groups passed in {r['elapsedMs']/1000:.3f} seconds. This is model test duration, not an editor performance benchmark.

| Group | Executed scope |
|---|---|
| Structural named cases | {details['structuralTests']['named']} cases: selected inline/block atoms, table payload, unchanged/detour candidates, same/sibling/ancestor/descendant/list-to-outside/outside-to-list/quote replacements, nested tail order, nonterminal unmark, native removal, turnover cancellation, empty attributes and code/prose. |
| Segment ownership | {details['overlayTests']['cases']} cases: corrected T5, backward disjoint retarget and full/partial marked commitment, absorbed-prefix preservation. |
| Generated structural traces | {details['generatedStructuralTests']['cases']} traces over four fixture hierarchies and all selected intervals, using independent expected plaintext splices and exact original-tree restoration. |
| Input admission | {details['admissionTests']['cases']} synthetic-stamp traces: queued continuations, failed initiator, FIFO generation barrier, explicit after-ticket selection basis, ambiguous-host refusal and stale-basis rejection. |
| History ledger | {details['historyTests']['cases']} traces: cancel vs commit, changed terminal commit without preedit, partial commit plus formatting and multiple Undos, interleaving, atomic failure and stack identity. |
| Bounded history exploration | {details['historyExploration']['cases']} traces, at most four queued commands; checks history-root continuity, redo continuity and one outcome per ticket. Not exhaustive state-space proof. |
| Behavioral mutations | {details['mutations']['killed']} caught with {details['mutations']['passingControls']} passing controls: atom provenance loss, premature unmark flush, loss of trailing island, guessed provenance, double Undo on cancel and ignored history-order guard. |

## What changed and why

The specification replaces its contradictory composition chapter with a single contract. Hierarchy reconstruction uses immutable source paths, not replayed open/close markers. Ordinary sibling list items can join, while other unselected trailing subtrees retain order and ancestry. ListItem now permits any nonempty block sequence; no synthetic paragraph is inserted merely to repair a first-child rule. Initial selected structure can be replaced provisionally; later unselected expansion is more restricted. The action ledger avoids both double Undo after cancellation and attempts to remove an interior snapshot-history entry.

First execution exposed an incorrect hand-written expected string in the sibling-list fixture: the unselected space in `Buy ` must survive, so the result is `Buy and mom`, not `Buyand mom`. The fixture was corrected from its explicit interval; the implementation was not changed to match it. The first failing output is retained in `first-run-results.json`. The debate's T5 expectation is separately corrected to `aXPQ`; d is selected and replaced.

A later rollback test exposed an actual wrapper defect: the inherited transaction snapshot copied a fixed list of published fields and dropped the new structuralTree field on rejection. The wrapper now retains the entire immutable published snapshot in its transaction preimage, plus structural seed/owner state. The failing output is preserved in `rollback-failure-results.json`; the unsupported-object-seam rollback regression now passes.

## Limits and honest readiness

These results settle the listed traces within the implemented reference contracts. They do not establish all combinations. Structural composition uses the copied run-15 reducer plus a new tree wrapper. The admission registry and LIFO ledger are separate executable models, not integrated replacements for every legacy public entry point. The current report makes no claim that the copied coordinator already implements the new ledger or adapter provenance. Integrated public-boundary model/Kotlin checks remain G3 requirements.

No native input host, real DOM composition, accessibility runtime, persistence fault injection, production tree optimization or physical memory benchmark was executed. No production code or implementation phase was started. All G0-G8 remain NOT RUN. A retained block object's boundary cannot always be removed by an opaque scalar splice while preserving exact legal structure; the plan explicitly distinguishes semantic object commands and requires host qualification of that seam.

The plan is ready for another independent design review. The named debate gaps have explicit policies and targeted evidence; it is not certified complete or release-ready. Source hashes are recorded with results, and before-state root documents are preserved under `sources/`.
'''
(root/'antigravity-validation-report.md').write_text(report,encoding='utf-8');(run/'antigravity-validation-report.md').write_text(report,encoding='utf-8')
rows=[['Claim','Scope','Evidence','Status','Remaining requirement']]
for x in r['results']:rows.append([x['name'],'Executed reference contract',f"run-20261015-16/results.json: {x['name']}",'PASS (SCOPED)','No native/production qualification'])
for i in range(9):rows.append([f'G{i}','Production/integrated gate','plan-v4.md §8','NOT RUN','No production implementation authorized'])
rows.extend([['Legacy run-15','Historical separate model','run-20261015-15/results.json','HISTORICAL, NOT RERUN','Do not infer new ledger/provenance coverage'],['Integrated admission + structure + ledger','Single public transaction boundary','plan-v4.md §9.6','NOT RUN','Integrate separate contracts and replay failure/reentry cases'],['Object seam handling','Real host and semantic node commands','plan-v4.md §4.3','NOT QUALIFIED','No silent flattening or input loss'],['Schema change','ListItem Block+ product rule','plan-v4.md §4.3','DESIGN POLICY; MODEL CASES PASS','Layout/AT/import/export qualification']])
buf=io.StringIO();csv.writer(buf).writerows(rows)
for dest in [root/'antigravity-validation-matrix.csv',run/'antigravity-validation-matrix.csv']:dest.write_text(buf.getvalue(),encoding='utf-8')
diff='''# Revision 4.11.0 -> 4.12.0

- Replace section 4, rather than append conflicting clauses.
- Add typed input targets, deferred receipts, admission reservations, FIFO generation barriers and an explicit host-provenance qualification boundary.
- Support initial selected atoms, tables and cross-container text replacement through tagged provenance and deterministic tree reconstruction.
- Deliberate schema change: ListItem Block+; preserve unselected ancestry/order instead of ad hoc lifting or inserting extra paragraph separators.
- Retain provisional islands on retarget, exact segment rollback ownership and trailing islands after full marked partial commit; correct T5 to aXPQ.
- Specify nonterminal overlay retention, exact visible native removal and old-epoch command cancellation on connection turnover.
- Replace speculative Undo slot promotions with settlement-local LIFO action credits and actual stack-top identity checks.
- Count hierarchy depth/global-map maintenance and protect composing DOM ancestors as well as grapheme intervals.
- Preserve old standalone and runs. New standalone includes the complete revised specification, current report, ledger and review prompt.
- No production implementation. Separate contract models and their integration limits are disclosed.
'''
for dest in [root/'antigravity-plan-diff.md',run/'antigravity-plan-diff.md']:dest.write_text(diff,encoding='utf-8')
prompt='''Review the attached Compose Multiplatform Editor Standalone Specification 4.12.0 independently. Read every section before giving a verdict. This is a read-only specification review; do not implement production code or advance phases.

Determine whether the specified product semantics are coherent and implementable without inventing core behavior. Challenge input provenance and publication timing, the ListItem Block+ schema choice, structural reconstruction and object seams, immutable candidate reconciliation and partial-commit footprints, the settlement-local Undo ledger, native/Web projection, formatting/graphemes, hierarchy-depth costs and history/persistence.

For each material finding provide an exact section, classification (contradiction, missing decision, model defect, evidence gap or platform experiment), a concrete initial state and event trace, expected versus specified behavior, smallest correction and a discriminating test. Do not conflate archived run-15 coordinator behavior with the new admission/history contract models. Their integration is explicitly unrun. Treat PASS counts as reported evidence unless you execute the sources yourself.

Distinguish design readiness, model coverage, implementation authorization and release qualification. No production phase is authorized. Do not rubber-stamp, invent defects, silently narrow scope or propose a new architecture merely for novelty. Return a scoped verdict, prioritized findings and finite acceptance criteria. State any unread material or unexecuted claims.
'''
(root/'editor-4.12.0-review-prompt.md').write_text(prompt,encoding='utf-8')
table='\n'.join('| '+' | '.join(str(x).replace('|','/') for x in row)+' |' for row in [rows[0],['---']*5,*rows[1:]])
manifest={str(p.relative_to(root)).replace('\\','/'):sha(p) for p in [root/'plan-v4.md',root/'antigravity-validation-report.md',root/'antigravity-validation-matrix.csv',run/'results.json',run/'contracts.md']}
package='''# Compose Multiplatform Editor Standalone Specification 4.12.0

This is the complete current specification plus its scoped validation evidence and review prompt. It replaces revision 4.11.0 for review; the old file remains an archived snapshot. No prior conversation or earlier plan is needed to understand the selected contracts.

The design combines a shared Kotlin document engine and engine-owned history, native Compose rendering/input adapters, and a Web semantic DOM surface. Cross-container selected replacement is required. Section 4 contains the single normative input/structural/history contract; it is not a collection of competing debate proposals.

This document is self-contained for design review, not a bundled executable repository. The model source files named in its report are needed to rerun tests. Neither reported results nor an internally coherent design qualify real native IMEs, browser composition, accessibility, durable storage or performance. There is no production implementation authorization.

# Complete normative specification

'''+plan+'\n\n# Current executable evidence report\n\n'+report+'\n\n# Qualification ledger\n\n'+table+'\n\n# Integrity and reproduction\n\nRun `node validation/codex/run-20261015-16/verify.mjs` from the repository root. The current runner contains seven groups and writes results.json; it is not a read-only review command. Source hashes appear in results.json. Earlier 4.11.0 evidence is historical and is not claimed as rerun.\n\n```json\n'+json.dumps(manifest,indent=2)+'\n```\n\n# Independent review prompt\n\n'+prompt
(root/'editor-4.12.0-standalone-review.md').write_text(package,encoding='utf-8')
(run/'README.md').write_text('''# Run 16: specification contracts only

Run from the repository root:

```powershell
node validation/codex/run-20261015-16/verify.mjs
python -X utf8 validation/codex/run-20261015-16/publish.py
```

The first command writes model results. The second publishes the revised plan/report/ledger/standalone from the archived 4.11.0 source and contracts.md. It refuses a failed or source-mismatched results file. No production build or native host is involved.

structural-model.mjs extends a copied run-15 segment reducer. protocol-model.mjs contains separately executable admission and history models; it is not wired through every legacy method. test-contracts.mjs includes named fixtures, generated interval traces, bounded history sequences and real behavioral mutants. The old sources and first failing fixture output remain preserved.
''',encoding='utf-8')
(run/'publication-hashes.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'planBytes':len(plan.encode()),'standaloneBytes':len(package.encode()),'standaloneWords':len(package.split()),'matrixRows':len(rows)-1,'groups':len(r['results'])}))
