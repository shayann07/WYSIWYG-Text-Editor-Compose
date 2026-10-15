from pathlib import Path
import json,hashlib,re,csv,io
run=Path(__file__).resolve().parent;root=run.parents[2]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((run/'results.json').read_text(encoding='utf8'));assert r['passed']
for f,h in r['sourceHashes'].items():assert sha(run/f)==h,f
oldrun=root/'validation/codex/run-20261015-18'
for f,h in json.loads((oldrun/'results.json').read_text())['sourceHashes'].items():assert sha(oldrun/f)==h,'Prior run changed: '+f
prior=(run/'sources/plan-v4.md').read_text(encoding='utf8')
plan=re.sub(r'Revision: 4\.14\.0[^\n]*','Revision: 4.15.0, 16 October 2026. Status: whole-document commands, registered anchors, typing intent and cell-flow composition integrated and exercised in the reference model. Production and platform qualification remain outstanding. No production implementation is authorized.',prior,count=1)
old='The model is still deliberately scoped: one connected flow per owner (whole table payloads are atoms), full-copy array/tree storage, selected primitive attribute patches, and Intl.Segmenter for its grapheme fixtures. It is not the complete product command catalog, cell-flow coordination, typing-intent UI, registered-anchor implementation, full Unicode corpus, native adapter, accessibility host, browser DOM or durable journal. Those existing gates remain. Passing this integrated reference subset does not pass production G3 or authorize implementation. Production must preserve the same public-boundary contracts while lowering storage and platform bridges.'
assert old in plan
plan=plan.replace(old,'Run-19 extends this owner to a whole document with one active native flow, the finite command catalog, registered anchors, typing intent, clipboard leases and table-cell coordination (§4.11). Its storage is still full-copy arrays/trees, and grapheme fixtures use Intl.Segmenter. Full Unicode conformance, production persistent storage, native adapters, accessibility, browser DOM, arbitrary HTML import and durable journal remain unqualified. Catalog coverage is not exhaustive interaction proof and does not pass G3 or authorize implementation.')
plan=plan.replace('The integrated run-18 model implements the following ownership and expiry rules;', 'The integrated model implements the following ownership and expiry rules, extended for document commands by §4.11;')
plan=plan.replace('A basis stores only an identity sequence (compressed `TokenRun(baseId,length)` plus separator identities in production), selection/direction, selection generation, document/session revisions and connection epoch/generation. It has no text slabs, attributes, hierarchy, resources or history roots.', 'A basis stores an identity sequence (compressed `TokenRun(baseId,length)` plus separator identities in production), selection/direction, selection generation, document/session revisions and connection epoch/generation. Section 4.11 adds root-free flow/topology/selection-kind metadata and cell target fingerprints. It has no text slabs, attribute payloads, canonical hierarchy objects, resources or history roots.')
plan=plan.replace('Generation/epoch turnover retires older counters once no queued request refers to that identity context.', 'Generation/epoch turnover retires older counters only once neither a queued request nor the current published input frame refers to that identity context. Staged flow switches are not publication and cannot evict the published frame\'s replay watermark.')
marker='## 5. Formatting and boundary-attribute rules'
plan=plan.replace(marker,(run/'document-contract.md').read_text(encoding='utf-8-sig')+'\n\n'+marker,1)
plan=plan.replace('one executable owner in `validation/codex/run-20261015-17/integrated-model.mjs`','one executable owner, originally run-17 and now extended in `validation/codex/run-20261015-19/document-model.mjs`')
a=plan.index('### 9.5 Revision 4.14.0');b=plan.index('### 9.6 Required experiments',a)
plan=plan[:a]+'''### 9.5 Revision 4.15.0 whole-document evidence

Run-19 adds the remaining 51 named document-command families to the same admission/structural/Undo owner, with published target capture, typing intent, registered anchors, document annotations, clipboard leases and independent table-cell flows. Whole-document snapshots/history include secondary cells, resource descriptors, selection kind/direction and generation-aware anchor restoration. Explicit cell selections compose provisionally and cancel/partially commit/undo without a separate preparatory clear group.

Development and final review found and repaired target/publication defects: a transient deleted-cell/Undo scope roundtrip retired valid native input; cancellation could lose a queued command's Undo credit; focus loss could apply composition formatting; native seeding missed typing/empty-cell intent; selected inline atoms did not follow cross-cell moves; deletion segmented an atom with an adjacent combining mark; directional extension used the normalized range start instead of focus. Additional regressions cover clipboard cells, asynchronous target/focus isolation, rich fragment shape, protected edits, leading-gap lifting and entire-document revision accounting. Archived failing traces distinguish observed failures from preventive checks.

Reproduce with `node validation/codex/run-20261015-19/verify.mjs`. The current evidence report records exact groups/counts, environment, source hashes and mutation outcomes. Generated anchor tests use analytic insertion/split equations; cell tests enumerate 2x2 rectangles/directions and compare against an independent cell-value oracle. Thirty-five inherited boundary cases execute through DocumentModel, in addition to retained base-owner suites. Eight new behavioral mutants and seventeen retained mutants are caught by passing-control checks. This is bounded reference execution, not whole-state proof or production qualification.

'''+plan[b:]
plan=plan.replace('3. G2/G3: port the now-integrated reference owner to the production Kotlin coordinator, extend it to the full product command/anchor/cell-flow/typing-intent catalog, and replay the required corpus with platform-specific fake adapters. Run-18 retains the integrated reference boundary and adds the reviewed repairs/retention checks; it does not qualify the full production implementation.', '3. G2/G3, when implementation is separately authorized: port the whole-document owner and §4.11 command/anchor/cell-flow/typing-intent contracts to Kotlin, preserving the shared admission/settlement pipeline. Extend interaction coverage beyond the recorded finite fixtures and meet the 100,000-operation qualification corpus. Run-19 provides executable reference coverage; it is not the production implementation or a pass for G2/G3.')
plan=plan.replace('4. G2/G6: list items beginning with nested lists/objects, explicit insertion gaps, marker layout, AT navigation and import/export round trips. This schema change must remain visible to product review.', '4. G2/G6: layout/native hosting for insertion gaps and list items beginning with nested lists/objects, marker/AT navigation, system clipboard integration, arbitrary HTML import and supported-format export round trips. Headless gap commands and canonical rich fragments now execute, but native gap hosting, visual navigation and actual importers remain unqualified.')
assert plan.count('### 4.11 ')==1 and len(re.findall(r'^## [1-9]\. ',plan,re.M))==9
current=(root/'plan-v4.md').read_text(encoding='utf8')
previous=json.loads((run/'publication-hashes.json').read_text()) if (run/'publication-hashes.json').exists() else {}
assert current==prior or current==plan or sha(root/'plan-v4.md')==previous.get('plan-v4.md'),'Concurrent plan edits require reconciliation'
d={x['name']:x['detail'] for x in r['results']}
rows=[['Claim','Scope','Evidence','Status','Remaining requirement']]
for x in r['results']:rows.append([x['name'],'Executed reference contract',f"validation/codex/run-20261015-19/results.json: {x['name']}",'PASS (SCOPED)','Only recorded traces, counts and bounds'])
for i in range(9):rows.append([f'G{i}','Production qualification','plan-v4.md section 8','NOT RUN','No production implementation or gate advancement'])
for claim,remaining in [('Unicode','Pinned segmentation and full conformance data; Intl fixtures are insufficient'),('Storage and retention','Persistent hierarchical Kotlin indexes, allocator/GC measurements and lease reclamation'),('Native input and AT','Live OS/browser provenance, cell/gap hosting, visual selection, screen readers'),('Imports and resources','Actual HTML/system clipboard/codec/resource I/O and format round trips'),('Durability','Physical process-kill journal recovery'),('Performance','Full layout, large paragraph, deep document and device budgets')]:rows.append([claim,'Qualification boundary','plan-v4.md sections 4.11 and 9.6','NOT QUALIFIED',remaining])
buf=io.StringIO();csv.writer(buf).writerows(rows)
report=f'''# Revision 4.15.0 — whole-document validation and final review

Run: `validation/codex/run-20261015-19`. Executed {r['timestamp']} on {r['environment']}.

**All {len(r['results'])} verification groups pass. The finite command catalog, anchors and cell-flow composition now execute through one document owner. This is a tested semantic baseline; it is not a production editor, a whole-state proof or a claim that only platform testing remains. No production implementation was performed or authorized.**

## What changed

- Added a global document owner over the existing admission/structural worker/action-ledger pipeline. History and revision comparison include inactive cells, resources and annotations.
- Added 51 named command families, typing intent/coalescing, text and gap anchors, cell selection/structure/navigation, canonical rich clipboard and asynchronous paste leases.
- Extended cell selection to one provisional native gesture with secondary-cell clearing in Dv, explicit acceptance into Dc, and full-document cancellation/Undo.
- Bound connection identity to final published flow, preserving valid reentrant native input across transient delete/Undo scope changes and failed publications.
- Extended root-free bases with coordinate topology/cell identity metadata; separately capture/charge published typing intent. Added command/anchor/lease bounds and cleanup without claiming a physical heap bound.

## Executed evidence

| Group | Observed result |
|---|---|
| Document regressions | {len(d['documentCases']['cases'])} named cases, all pass. |
| Existing scenarios through DocumentModel | {d['inheritedDocumentCases']['cases']} cases, all pass. |
| Command inventory | {len(d['commandCoverage']['rows'])} families each have an observed terminal Applied/NoChange outcome; not merely an accepted queue ticket. |
| Anchor matrix | {d['anchorMatrix']['cases']} insertion/split/association cases with analytic expected positions and exact Undo/Redo checks. |
| Cell composition matrix | {d['cellCompositionMatrix']['cases']} cases: every rectangle/direction in a 2x2 table, commit/cancel/partial acceptance, queued formatting and Undo. |
| New mutations | {d['documentMutations']['controls']} unmodified controls pass; {d['documentMutations']['killed']} behavioral mutants fail assertions. |
| Retained repair/AWT/retention tests | {d['regressionTests']['cases']} repairs, {d['structuralChains']['cases']} structural chains, {d['awtTests']['cases']} synthetic AWT cases, {d['retentionTests']['cases']} retention cases and {d['retentionSoak']['edits']} fixed-size edits. |
| Retained base integration | {len(d['namedTests']['cases'])} named cases, {d['generatedIntegration']['cases']} generated traces, {d['ledgerExploration']['cases']} bounded ledger traces, {d['repeatedSessions']['sessions']} repeated sessions, {d['scheduling']['requests']} scheduling requests. |
| Retained mutation suites | {d['repairMutations']['killed']+d['integratedMutations']['killed']} caught with controls. |

Total verification duration: {r['elapsedMs']/1000:.3f} seconds, **not an editor latency benchmark**. These groups deliberately overlap contracts; their counts must not be added as unique independent proofs. Inherited named cases execute once through DocumentModel and separately through the retained base model. The new matrices use independent expected values for their limited domains; most named regressions assert examples against the reference and are not a separately implemented complete oracle. No claim of exhaustive BFS/model checking is made for the whole document.

## Defects found and fixed

1. **Transient connection scope**: deleting an active cell's table then Undo during one settlement changed epoch despite restoring the original flow, rejecting valid reentrant input. `flow-roundtrip-before-repair.json` records the observed failure. Scope identity is now derived once from the final publication.
2. **Target and history separation**: transport status, command status and cancelled ledger credits were conflated. Credits remain until consumed; cancellation emits once; invalid queued import cannot make a following Undo consume accepted native text.
3. **Whole-document composition**: cell-clearing rollback/selection kind, annotation restoration and inactive-cell revision comparison needed whole-document state. Tests now check Dv/Dc isolation, partial acceptance, cancellation and Undo across all selected cells.
4. **Typing/native seed**: preedit must use frozen typing intent and empty-cell attributes; candidate whitespace detours must preserve baseline link attribution until acceptance.
5. **Object/Unicode/selection boundaries**: final review reproduced Backspace deleting an atom with a following combining mark, a selected atom remaining in its old cell after a move, and forward selection extension moving from range start. Hard atom boundaries, identity-following node selection and separately captured directional endpoints repair these cases. The archived failure JSON contains expected/actual assertions.
6. **Clipboard and delayed targets**: fixed rich-cell cloning, inline-rich replacement of a rectangle, target/focus separation, malformed/ambiguous fragment refusal, gratuitous empty split paragraphs and fresh identity mapping. Canonical clipboard tests do not validate an OS clipboard or HTML parser.
7. **Publication/retention safety**: capture published scope/context during reentry, pin its replay watermark through speculative switch/rollback, reject duplicate FlowIds before map overwrite, keep immutable bases free of roots/attributes, and release transient handles/leases on disposal.

Some items combine a reproduced defect with preventive regression coverage. `failure-*.json` files retain intermediate failures; they are development evidence, not the final result. `document-inherited-first.json` records the initial inherited-suite error-code mismatch, subsequently corrected to FlowMismatch for native foreign-flow proposals. No historical failure is described as a passing current test.

## Final independent review and honest readiness

After repairs I reviewed the combined command admission, target capture, global revision comparison, cell prelude, final connection binding, history restoration, anchor generations, clipboard leases and boundary rules. I also reran the prior named integrated scenarios through the actual new document owner. I found no remaining reproduced contradiction in the exercised contracts. The normative plan now states the command choices and failure behavior explicitly; implementers need not invent the architecture for those operations.

**Ready as a scoped specification/reference baseline, subject to its qualification gates. Not proven complete, and not only platform tests remain.** Remaining work includes pinned Unicode conformance; a production persistent hierarchical implementation and broad randomized/differential interaction corpus; actual HTML/import/export/system clipboard/resource pipelines; measured memory reclamation; crash recovery; visual movement/native gap and cell hosting; real IME/DOM/accessibility; and end-to-end performance. Intl segmentation, synthetic stamps, full-copy storage and serialized byte budgets cannot establish those properties. These are explicit qualification/engineering tasks, not hidden passing claims. Merged/nested tables, collaboration and pagination remain the pre-existing deferred product features.

No G0-G8 gate is passed or advanced. No source under production modules was created or changed. The work consists of a validation-model directory and versioned plan/report artifacts.
'''
diff='''# Revision 4.14.0 -> 4.15.0

- Extend the integrated owner to the whole document, all cell flows, resources, annotations and typed selection.
- Add section 4.11 defining the 51-command catalog, target capture, anchor generations, cell composition prelude, clipboard leases, typing intent and bounded ownership.
- Derive connection turnover from final published scope, preserving reentrant capture/replay state during rollback and transient switches.
- Repair discovered atom/grapheme deletion, node-move selection, directional extension, command-credit, clipboard, native-seeding and full-document history issues.
- Retain cross-paragraph/cross-container native composition and explicit cell-selection composition; no scope reduction to single paragraphs.
- Extend root-free bases with topology/flow/cell metadata; independently capture typing context.
- Add document regression/matrix/mutation coverage and replay inherited cases through DocumentModel.
- Replace obsolete claims that anchors/commands/cell flows are absent from the model with precise remaining qualification boundaries.
- Preserve prior versions/runs. No production implementation or phase advancement.
'''
prompt='''Independently review this standalone Compose Multiplatform editor specification, revision 4.15.0. Read-only design/evidence review; no production implementation or phase advancement. Read the complete specification, validation report and qualification ledger. Do not assume reported passing tests prove the design or live native behavior.

Focus on the integration of admission, structural composition, global document history, all command families, registered anchors, typing intent, asynchronous clipboard targets and independent table-cell flows. Challenge partial commit plus cell clearing/cancellation; format/Undo/CancelCommand ledger credits; final versus speculative flow changes; native provenance; published versus staged target capture; anchor restoration after explicit relocation; moved/deleted selected objects; grapheme/atom boundaries; protected content; resource/clipboard identity and retention limits.

For every material finding give the exact section, initial document and selection, ordered events, expected versus specified result, a minimal repair and a discriminating regression. Distinguish an actual contradiction, missing normative rule, untested implementation property, explicit product exclusion and platform experiment. Do not invent defects or repeat a repaired example without checking current rules. State what you read, executed and inferred. If source code is unavailable, say so; the document contains design/evidence, not the executable repository. Return an honest scoped verdict and finite remaining acceptance criteria. Do not call this a perfect editor or claim only platform testing remains.
'''
for name,content in [('plan-v4.md',plan),('antigravity-validation-report.md',report),('antigravity-validation-matrix.csv',buf.getvalue()),('antigravity-plan-diff.md',diff)]:
 (root/name).write_text(content,encoding='utf8');(run/name).write_text(content,encoding='utf8')
(root/'editor-4.15.0-review-prompt.md').write_text(prompt,encoding='utf8')
manifest={str(p.relative_to(root)).replace('\\','/'):sha(p) for p in [root/'plan-v4.md',root/'antigravity-validation-report.md',root/'antigravity-validation-matrix.csv',run/'results.json',run/'document-contract.md']}
table='\n'.join('| '+' | '.join(str(v).replace('|','/') for v in row)+' |' for row in [rows[0],['---']*5,*rows[1:]])
package='''# Compose Multiplatform Editor Standalone Specification 4.15.0

This document contains the complete normative specification, current validation report, qualification ledger and review prompt. No prior conversation or older plan is needed to understand the chosen design. The executable source repository is needed to reproduce the reported model results; source code is not embedded here.

This revision integrates the finite command catalog, registered anchors, typing intent, clipboard target leases and cell-flow composition with the existing admission/structural/Undo owner. Native surfaces use Compose layout/canvas; Web uses semantic DOM input/view machinery governed by the shared engine. No production implementation or phase advancement is authorized.

Passing evidence is bounded and explicitly scoped. This is a reviewable semantic baseline, not a proof of perfection or a claim that only live platform tests remain. Old versions are historical snapshots, not competing normative alternatives.

# Complete normative specification

'''+plan+'\n\n# Current validation report\n\n'+report+'\n\n# Qualification ledger\n\n'+table+'\n\n# Integrity and reproduction\n\nFrom the repository root, run `node validation/codex/run-20261015-19/verify.mjs`. The runner records environment, detailed results and model-source hashes, and archives failures. The publisher refuses failed or source-mismatched results. Prior run-18 source hashes are checked before publication.\n\n```json\n'+json.dumps(manifest,indent=2)+'\n```\n\n# Independent review prompt\n\n'+prompt
(root/'editor-4.15.0-standalone-review.md').write_text(package,encoding='utf8')
(run/'publication-hashes.json').write_text(json.dumps({**manifest,'editor-4.15.0-standalone-review.md':sha(root/'editor-4.15.0-standalone-review.md')},indent=2),encoding='utf8')
(run/'README.md').write_text('''# Run 19: whole-document executable validation

Specification/reference-model work only. No production implementation.

From the repository root:

```powershell
node validation/codex/run-20261015-19/verify.mjs
python -X utf8 validation/codex/run-20261015-19/publish.py
```

`document-model.mjs` extends the existing integrated transaction owner. `document-tree.mjs` supplies full-copy document/flow, schema, anchor, structural and clipboard utilities. `test-document.mjs` tests the finite catalog and analytic anchor/cell matrices. `test-document-mutations.mjs` contains actual behavior overrides with passing controls. The retained integration/repair tests run against the copied base owner; 35 inherited named cases also run through DocumentModel.

`results.json` is the current execution record with source hashes. `failure-*.json`, `flow-roundtrip-before-repair.json` and `document-inherited-first.json` are archived intermediate failures, not current pass evidence. `document-contract.md` is the normative extension; the publisher integrates it into the root plan and standalone review package. Old runs remain unchanged.

The runner is deterministic except timestamps/timings. Its default injected clock is fixed for model cases; real typing-time policy requires a production clock. Node Intl segmentation, synthetic native evidence, full-copy roots and serialized retention charges do not validate Unicode conformance, physical memory, native/DOM/AT behavior, actual clipboard/import pipelines, durability or latency.
''',encoding='utf8')
assert package.count('```')%2==0
print(json.dumps({'revision':'4.15.0','groups':len(r['results']),'standaloneWords':len(package.split()),'matrixRows':len(rows)-1}))
