from pathlib import Path
import json, hashlib, re, csv, io
run=Path(__file__).resolve().parent;root=run.parents[2]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((run/'results.json').read_text(encoding='utf8'));assert r['passed']
for f,h in r['sourceHashes'].items():assert sha(run/f)==h,f
prior=(run/'sources/plan-v4.md').read_text(encoding='utf8')
plan=re.sub(r'Revision: 4\.13\.0[^\n]*','Revision: 4.14.0, 15 October 2026. Status: reviewed input/structural defects repaired, AWT contract corrected, and bounded protocol retention exercised in the integrated reference model. Production and full-product qualification remain outstanding. No production implementation is authorized.',prior,count=1)
plan=plan.replace('val sequence: Long, val adapterSessionToken: AdapterSessionToken?)','val sequence: Long, val sessionOrdinal: Long?,\n    val adapterSessionToken: AdapterSessionToken?)')
plan=plan.replace('`InputStamp = (connectionEpoch, inputGeneration, sequence, adapterSessionToken?)`. Tokens are opaque and non-reused within an epoch/generation; registry keys include all three identity fields.',
 '`InputStamp = (connectionEpoch, inputGeneration, sequence, sessionOrdinal?, adapterSessionToken?)`. A composition supplies a positive monotonic session ordinal and its opaque token; direct noncomposing input supplies neither. Reservations are keyed by epoch/generation/ordinal and verify the token. Detailed retired reservations can be released under §4.10 without permitting session reuse.')
plan=plan.replace('Tombstones survive until the connection/generation fence proves older deliveries cannot recur.', 'Live/dependent reservations are pinned; retired session identity is remembered by a monotonic ordinal high-water mark under §4.10 rather than an unbounded tombstone set.')
plan=plan.replace('session reservations by `(epoch,generation,adapterSessionToken)`','session reservations by `(epoch,generation,sessionOrdinal)` with matching opaque token')
plan=plan.replace('`AfterTicket` uses the actual accepted publication stored with that receipt;', '`AfterTicket` uses the compact coordinate basis associated with the accepted publication and pinned under §4.10;')
plan=plan.replace('The admission registry records `(token -> engineSessionId, initiatingTicket, Reserved/Active/Failed/Terminated)`', 'The admission registry records `((epoch,generation,sessionOrdinal) -> token, engineSessionId, initiatingTicket, Reserved/Active/Failed/Terminated)`')
plan=plan.replace('An explicit range carries its published basis revision, exact content identities and boundary associations.',
 'An explicit range is validated as exactly two present safe-integer endpoints within its named basis before sorting or mapping. Sparse arrays, extra endpoints, nonintegers, NaN and infinities reject without coercion. It carries its published basis revision, exact content identities and boundary associations.')
plan=plan.replace('3. Retained block heads retain original ancestry. Remove consumed leaf heads and prune containers with no remaining children bottom-up.',
 '3. Retained block heads retain original ancestry except for the explicit sibling-item moves below. Process joins in original leaf order while maintaining CURRENT working parent relationships; recompute/update the parent index after every move. Provenance supplies identities/content, not stale ownership. Never resurrect an emptied item by using its original parent as the destination of a later join. Remove consumed leaf heads and prune containers with no remaining children bottom-up.')
plan=plan.replace('A narrow ordinary list-join rule applies when the surviving left head and a consumed right head are DIRECT children of distinct sibling ListItems under the same List.',
 'A narrow ordinary list-join rule applies when the surviving left head and a consumed right head are CURRENTLY direct children of distinct sibling ListItems under the same current List.')
plan=plan.replace('Later reconversion/expansion without an explicit selected-structure gesture may extend over same-parent compatible prose/code text domains, but cannot acquire previously unselected objects or container transitions.',
 'Later reconversion/expansion without an explicit selected-structure gesture may extend only within the primary landing head\'s current parent and compatible join class (Paragraph/Heading = prose; CodeBlock = code). Initially selected foreign-owner blocks and structural unit identities remain explicitly admitted, but selecting part of a foreign container does not authorize all of its unselected contents. Check both endpoint owners, even for a disjoint target wholly inside another block, and every newly crossed boundary. Same parent alone does not establish prose/code compatibility. No previously unselected object or container transition is acquired implicitly.')
plan=plan.replace('Cancellation with no accepted semantic delta restores pre-session selection/direction.',
 'A normalized native terminal callback supplying selection must have that exact selection validated against the resulting native-stage document and applied before history capture or command drainage, even if no replacement text was supplied. This includes commit-without-text, terminal unmark and completion; invalid selection rejects the whole transaction. Explicit-range selection is mapped once through the proposed post-edit basis, including nativeRemoval replacement text. A native cancel\'s supplied selection is likewise post-cancellation. Without an explicit native selection, cancellation with no accepted semantic delta restores pre-session selection/direction.')
plan=plan.replace('Application observers are notifications, not input validators.',
 'Redo during active composition rejects SettlementRequired without mutation; it is not silently queued or used to terminate composition. It can be retried after qualified settlement, with cancellation preserving an existing redo branch when no new semantic edit survived.\n\nApplication observers are notifications, not input validators.')
plan=plan.replace('Initial history budget: 1,000 groups or 64 MiB of retained payload beyond the current root, whichever is reached first.',
 'Initial history-plus-redo budget: 1,000 settled groups or 64 MiB of retained payload beyond the current root, whichever is reached first, with the exact trimming/publication rules in §4.10. Protocol receipts and coordinate bases have separate bounded pools and cannot keep evicted history roots alive.')
old='3. `getTextLocation(offset)`: offset is relative to current composed text when active composition exists (supporting `TextHitInfo` leading/trailing semantics and returning a 2px-wide caret rectangle `[screenX, screenY, 2, lineHeight]`); when no composition is active, offset is relative to committed text (and defaults to the selection caret when null).'
new='3. `getTextLocation(offset)`: with composed text, use its relative TextHitInfo and preserve leading/trailing affinity. Without composed text, ignore offset and use the beginning, in line direction, of the selection highlight on its last selected line; a collapsed selection uses its actual caret. Return absolute screen coordinates and a zero-thickness rectangle: width 0 for horizontal text, height 0 for vertical text. The painted UI caret may have a separate visible thickness. This corrects the previous committed-offset/2px rule against the [JDK 23 contract](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html), rechecked 15 October 2026. Synthetic layout fixtures cover these rules; a native AWT host remains unqualified.'
assert old in plan;plan=plan.replace(old,new)
marker='## 5. Formatting and boundary-attribute rules';plan=plan.replace(marker,(run/'retention-contract.md').read_text(encoding='utf8')+'\n\n'+marker,1)
plan=plan.replace('Run-17 now executes integrated reference replay', 'Run-18 now executes the repaired integrated reference replay')
plan=plan.replace('Run-17 closes the separate-model integration gap for its recorded reference subset;', 'Run-18 retains the integrated reference boundary and adds the reviewed repairs/retention checks;')
a=plan.index('### 9.5 Revision 4.13.0');b=plan.index('### 9.6 Required experiments',a)
plan=plan[:a]+'''### 9.5 Revision 4.14.0 repair and retention evidence

Run-18 reproduces all four reported model defects against the preserved run-17 implementation before testing the repaired model: ignored terminal selections, stale-parent successive list joins, malformed-range coercion, and missing prose/code domain admission. The new regressions verify exact selection/history restoration, current-parent tail ownership, strict dense-pair validation, and owner/join-class checks including disjoint targets. Later selected atoms remain admissible by their pinned identities.

The AWT getTextLocation contract is corrected from primary JDK 23 documentation; synthetic layout-coordinate tests are separately scoped from native host qualification. Protocol retention now uses root-free acknowledgement summaries, compact identity bases, admission/dependency pins, bounded queues/outcomes/history, and sequence/session-ordinal watermarks. Tests include eviction, failed dependencies, replay after expiry, oversized payload/target/basis refusal, long-lived preedit, and fixed-document-size repeated editing. None is a physical heap measurement.

The integrated reference sources and results are in `validation/codex/run-20261015-18/`. Reproduce with `node validation/codex/run-20261015-18/verify.mjs`. Results contain exact counts, environment, source hashes, baseline failure observations and mutation evidence. Earlier runs and standalone packages remain archived; their results are not counted as new execution.

Final review additionally checks nativeRemoval post-selection mapping, terminal selection before queued formatting/Undo, explicit Redo settlement policy, and reentrant compact-basis capture after private mutation. Resource-pressure review accounts for expanded command targets and prevents a settlement batch from evicting its own terminal outcomes before notification. These remain bounded model checks, not a whole-product proof.

'''+plan[b:]
plan=plan.replace('6. G1/G8: depth-aware hierarchical indexes, fragmentation, retained-root memory, seam concat/split and full paragraph/layout latency.',
 '6. G1/G8: depth-aware hierarchical indexes, fragmentation, measured heap/GC reclamation for §4.10 ownership pools, seam concat/split and full paragraph/layout latency.')
assert 'Revision: 4.14.0' in plan and old not in plan
assert 'snapshot:this.published' not in (run/'integrated-model.mjs').read_text()
current=(root/'plan-v4.md').read_text(encoding='utf8');previous=json.loads((run/'publication-hashes.json').read_text()) if (run/'publication-hashes.json').exists() else {}
assert current==prior or current==plan or sha(root/'plan-v4.md')==previous.get('plan-v4.md'),'Concurrent plan changes require reconciliation'
d={x['name']:x['detail'] for x in r['results']}
report=f'''# Revision 4.14.0 — reviewed defects, AWT contract and retention

Run `validation/codex/run-20261015-18`; executed {r['timestamp']} on {r['environment']}.

**All {len(r['results'])} verification groups pass. The four reviewed model defects are reproduced against run-17 and repaired in run-18. The AWT specification is corrected, and bounded protocol retention is now specified and exercised. No production implementation.**

## Repairs

1. Terminal selection is validated/applied even for commit without replacement text, terminal unmark and completion. Invalid input rolls back before history/command drainage; valid forward/reversed selections survive Undo/Redo.
2. Successive list joins use current working ancestry after each move. The previous three-item trace no longer resurrects i2 or transfers T into the wrong item. Generated chains extend through six items with exact Undo/Redo restoration.
3. Replacement ranges are dense, exactly-two safe-integer endpoints validated before normalization/mapping. Extra/missing/sparse endpoints and nonnumeric/out-of-bounds values reject without mutation.
4. Retarget admission checks owner and join class, including a target wholly within another block. Initial explicit cross-class selections remain supported; unselected prose/code or ancestry acquisition rejects.
5. AWT geometry follows the corrected offset/selection and zero-thickness rules. Eight synthetic geometry fixtures are not an AWT hosting test.
6. Completed receipts no longer retain editor/history snapshots. Compact bases have explicit cache/pin lifetimes; admission, expanded targets, history/redo and outcomes are bounded. Expired sequences and retired session ordinals cannot execute again. A dependency on an expired basis rejects rather than using current coordinates.

## Current execution

| Group | Evidence |
|---|---|
| Preserved baseline | All four run-17 counterexamples fail the expected assertions. |
| Repair regressions | {d['regressionTests']['cases']} scenarios, including terminal selection/history, malformed inputs, nativeRemoval post-selection, domain checks and Redo settlement. |
| Successive structural joins | {d['structuralChains']['cases']} commit/cancel chains, up to {d['structuralChains']['maxItems']} list items. |
| AWT contract | {d['awtTests']['cases']} synthetic-frame cases. |
| Retention contracts | {d['retentionTests']['cases']} scenarios covering eviction, pinning, expiry/replay, ordinal retirement, failures, quotas and long composition. |
| Retention soak | {d['retentionSoak']['edits']} fixed-size edits; bounded record counts sampled every 100 edits. |
| Mid-staging reentry | Published compact basis retained while private structural state differs. |
| Repair mutations | {d['repairMutations']['killed']} behavioral mutants caught, with passing controls. |
| Retained integrated suite | {len(d['namedTests']['cases'])} named cases, {d['generatedIntegration']['cases']} generated structural/command traces, {d['ledgerExploration']['cases']} bounded ledger traces. |
| Repeated sessions | {d['repeatedSessions']['sessions']} sessions, {d['repeatedSessions']['failureRetries']} failure/retries, {d['repeatedSessions']['staleDeliveries']} stale deliveries. |
| Existing integration mutations | {d['integratedMutations']['killed']} caught with passing controls. |
| Scheduling | {d['scheduling']['requests']} requests across the 64-request turn budget. |

Elapsed: {r['elapsedMs']/1000:.3f} seconds. This is test-run duration, not editor latency. Reproduce with `node validation/codex/run-20261015-18/verify.mjs`. Source hashes and detailed observations are in results.json. No historical counts have been added as though rerun from a different model.

## Retention meaning and final independent check

Reference limits exercise serialized-byte/count ownership, not physical allocator usage. Receipt summaries contain no snapshots; bases contain token identities and coordinate metadata only. Admitted dependencies pin their bases/status until completion; late registration gets BasisExpired. Sequence and session-ordinal high-water marks preserve non-replay after detail eviction. Session ordinals are normalized adapter bookkeeping, not evidence of native callback origin.

The review checks that published bases are not rebuilt from staged worker state, that command target expansion is charged, and that pending-command capacity cannot exceed the outcome capacity needed to emit one atomic batch. Old private worker history/mapping/receipt caches are cleared because the integrated owner is authoritative. Document/history publication rolls back on dependency-basis capacity refusal. Receipt cache sizes cannot be interpreted as a total process-memory bound.

I found no additional failing counterexample in this final scoped review after these repairs. That is not a proof of a perfect editor, nor a claim that only platform tests remain. Full product command/typing-intent/anchor/cell-flow coverage, pinned Unicode conformance, production persistent storage and memory measurement, crash recovery, native input/DOM/accessibility and end-to-end performance still require their existing gates. None of G0-G8 is marked passed or advanced. The specification is a concrete, tested reference baseline for the covered contracts; no production implementation is authorized.
'''
diff='''# Revision 4.13.0 -> 4.14.0

- Fix and reproduce all four reviewed model defects: terminal selection, evolving list ancestry, range shape and text-domain compatibility.
- Correct the AWT getTextLocation rules from JDK 23; retain native hosting as unqualified.
- Add section 4.10 with bounded summary/basis caches, dependency pin/release, queue/target/outcome/history limits and explicit expiry/capacity outcomes.
- Add sessionOrdinal to normalized composition stamps so retired identities can be rejected without an unbounded tombstone set. This does not solve native provenance.
- Remove full snapshots from completed receipts. Preserve only published compact bases for capture and explicit AfterTicket pins; prevent cross-identity native basis reuse.
- Apply terminal native selection before history and queued commands; define Redo-during-composition rejection explicitly.
- Bound private worker caches and expanded pending-command targets; keep outcome delivery capacity sufficient for a settlement batch.
- Preserve prior runs/packages. No production implementation or phase advancement.
'''
rows=[['Claim','Scope','Evidence','Status','Remaining requirement']]
for x in r['results']:rows.append([x['name'],'Executed reference contract',f"run-20261015-18/results.json: {x['name']}",'PASS (SCOPED)','Only the recorded cases/bounds'])
for i in range(9):rows.append([f'G{i}','Production qualification','plan-v4.md §8','NOT RUN','No production implementation authorized'])
rows.extend([['Physical memory/GC','Actual production allocator','plan-v4.md §4.10','NOT RUN','Serialized charges/counts are not a heap measurement'],['AWT host','Real input, geometry and accessibility','plan-v4.md §6.3','NOT RUN','Synthetic geometry is a contract test'],['Native provenance','OS/browser lifecycle','plan-v4.md §4.2','NOT RUN','Ordinal is not host provenance'],['Full product semantic catalog','Commands/anchors/cells/typing intent','plan-v4.md §9.6','NOT QUALIFIED','Current model is scoped']])
buf=io.StringIO();csv.writer(buf).writerows(rows)
for name,content in [('plan-v4.md',plan),('antigravity-validation-report.md',report),('antigravity-validation-matrix.csv',buf.getvalue()),('antigravity-plan-diff.md',diff)]:
 (root/name).write_text(content,encoding='utf8');(run/name).write_text(content,encoding='utf8')
prompt='''Review the attached standalone Compose Multiplatform Editor specification 4.14.0 independently. Read-only review; no production code or phase advancement. Read all sections and distinguish the normative contract, scoped reference-model evidence and unqualified platform/product gates.

Scrutinize terminal native selection, exact range validation/mapping, successive structural joins and ownership, initial-selection versus retarget permissions, the integrated Undo ledger, AWT geometry, and bounded receipt/basis/dependency retention. Specifically test whether eviction can release a live dependency, retain old history through another owner, permit replay, hide a terminal outcome, or capture staged coordinates.

For each material finding give exact section, severity/classification, concrete initial state and ordered events, expected versus specified behavior, a minimal correction and a discriminating test. Do not repeat previously repaired examples without checking current semantics. Counts and synthetic stamps are not native or whole-engine proof. State what you inspected, inferred and executed, and any unread sections. Return a scoped verdict and finite acceptance criteria; do not rubber-stamp or invent defects.
'''
(root/'editor-4.14.0-review-prompt.md').write_text(prompt,encoding='utf8')
manifest={str(p.relative_to(root)).replace('\\','/'):sha(p) for p in [root/'plan-v4.md',root/'antigravity-validation-report.md',root/'antigravity-validation-matrix.csv',run/'results.json',run/'retention-contract.md']}
table='\n'.join('| '+' | '.join(str(v).replace('|','/') for v in row)+' |' for row in [rows[0],['---']*5,*rows[1:]])
package='''# Compose Multiplatform Editor Standalone Specification 4.14.0

This package contains the complete normative specification, current executable-model report, qualification ledger and review prompt. It requires no prior conversation or previous plan to understand the design. Prior standalone versions remain historical snapshots.

The integrated reference owner covers admission, tagged structural composition and full-root Undo/Redo. This revision repairs the four independently reproduced model defects, corrects the AWT contract, and defines/exercises bounded protocol retention. Passing checks are scoped; production Kotlin, full product behavior and real hosts remain unqualified. No implementation phase is authorized.

The document is self-contained for design review, not a bundled source repository. Reproduction requires the source directory named below. Do not treat a reported passing model or a locally assigned ordinal as proof of native input behavior.

# Complete normative specification

'''+plan+'\n\n# Current validation report\n\n'+report+'\n\n# Qualification ledger\n\n'+table+'\n\n# Integrity and reproduction\n\nFrom the workspace root, run `node validation/codex/run-20261015-18/verify.mjs`. It writes results.json and archives failures. The publisher refuses failed or source-mismatched results.\n\n```json\n'+json.dumps(manifest,indent=2)+'\n```\n\n# Independent review prompt\n\n'+prompt
(root/'editor-4.14.0-standalone-review.md').write_text(package,encoding='utf8')
(run/'publication-hashes.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
prior_files=[p for p in (root/'validation/codex/run-20261015-17').rglob('*') if p.is_file()]+[root/'editor-4.13.0-standalone-review.md']
if not (run/'prior-file-hashes.json').exists():(run/'prior-file-hashes.json').write_text(json.dumps({str(p.relative_to(root)).replace('\\','/'):sha(p) for p in prior_files},indent=2),encoding='utf8')
(run/'README.md').write_text('''# Run 18: reviewed repairs and retention

Validation model/specification only. From the repository root:

```powershell
node validation/codex/run-20261015-18/verify.mjs
python -X utf8 validation/codex/run-20261015-18/publish.py
```

The runner executes baseline reproductions, repair/AWT/retention/structural checks, behavioral mutants and all retained integrated suites. It writes results.json. The publisher validates source hashes before updating root documents and the 4.14.0 standalone package.

integrated-model.mjs owns publication, admission, bounded protocol state and history. structural-model.mjs uses working ancestry and checks join classes/owners. awt-contract-model.mjs tests coordinate behavior using supplied synthetic geometry only. test-repairs.mjs supplies new counterexamples and retention checks; test-integrated.mjs retains previous suites with normalized session ordinals. retention-contract.md is the normative ownership/expiry policy.

The copied old worker's history is not authoritative. Native provenance, real AWT/DOM/AT, full product semantics, physical memory, production complexity and durability remain unqualified. Prior runs are untouched.
''',encoding='utf8')
assert len(re.findall(r'^## [1-9]\. ',plan,re.M))==9
assert plan.count('### 4.10 ')==1 and package.count('```')%2==0
print(json.dumps({'revision':'4.14.0','groups':len(r['results']),'standaloneBytes':len(package.encode()),'standaloneWords':len(package.split()),'matrixRows':len(rows)-1}))
