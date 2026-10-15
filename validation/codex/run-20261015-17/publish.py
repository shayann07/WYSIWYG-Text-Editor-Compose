from pathlib import Path
import json, hashlib, csv, io, re

run=Path(__file__).resolve().parent
root=run.parents[2]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((run/'results.json').read_text(encoding='utf8'))
assert r['passed'], 'Refuse to publish failed integration results'
for name,digest in r['sourceHashes'].items(): assert sha(run/name)==digest,name
prior=(run/'sources/plan-v4.md').read_text(encoding='utf8')
plan=prior.replace('Revision: 4.12.0, 15 October 2026. Status: consolidated specification with scoped executable structural, admission and history contracts. Integrated public-boundary and real-platform qualification remain outstanding. No production implementation is authorized.',
 'Revision: 4.13.0, 15 October 2026. Status: integrated public-boundary reference model validated for the recorded traces. Production Kotlin, full command coverage and real-platform qualification remain outstanding. No production implementation is authorized.')
marker='## 5. Formatting and boundary-attribute rules'
plan=plan.replace(marker,(run/'integration-contract.md').read_text(encoding='utf8')+'\n\n'+marker,1)
plan=plan.replace('Gate G3 additionally requires integrated replay of input reservations, structural replacement and the action ledger, including post-abort fences, changed terminal commits, and the new run-16 traces. The separate contract models alone do not pass G3.',
 'Run-17 now executes integrated reference replay of input reservations, structural replacement and the action ledger through one dispatcher, including post-abort fences, changed terminal commits, rollback and reentry. G3 still requires the production Kotlin coordinator, complete required trace catalog and the stated invariant/Unicode checks; the reference subset is not a production gate pass.')
a=plan.index('### 9.5 Revision 4.12.0 review and evidence boundaries');b=plan.index('### 9.6 Required experiments',a)
plan=plan[:a]+'''### 9.5 Revision 4.13.0 integrated model and evidence boundaries

Run-17 replaces the previous disconnected validation path for the tested operations. One executable owner captures native/application requests, validates identities and coordinate bases, stages the tagged structural reducer, runs the full-root LIFO Undo ledger, and publishes a coherent immutable snapshot and receipt. The structural worker's old internal history is not used as authoritative history. The new public-boundary tests do not invoke the old standalone string admission/history models as independent substitutes for integration.

Source: `validation/codex/run-20261015-17/integrated-model.mjs`. Reproduction: `node validation/codex/run-20261015-17/verify.mjs`. Results and source hashes: `validation/codex/run-20261015-17/results.json`. The executable owner is a validation artifact, not a production editor module. Section 4.9 defines its integration boundary and scope.

Integrated tests cover queued cross-container composition, pending-command/cancel/partial-commit interactions, exact native removal, changed terminal commits, reentrant callbacks, failure rollback/retry, old-generation delivery, immutable basis capture, fragmented targets, unique session identities, canonical hierarchy rebasing, Undo/Redo and bounded continuation. Expected raw edits are independently calculated from fixture slices; original-tree restoration and structural/order/history invariants supplement those checks. Mutation checks require passing controls and assertion failures, not unrelated crashes. No exhaustive protocol proof is claimed.

Integration found and repaired four defects: observer exceptions could veto valid input; a repeated composition-start callback could acknowledge a new range without changing the mark; an explicit-range preedit could reach a private implicit-retarget path that bypassed protected-structure admission; and the inherited retarget classifier rejected even an atom already admitted by the initial user selection. Notifications are now isolated, changed ranges share one structural-retarget validation path, and initial structural permissions are pinned by exact identities. Initial test development also corrected explicit fixture offsets: TAIL is [6,10) in the original `ab\\ncd\\nTAIL`, but [5,9) after provisional `aXYd\\nTAIL`. The model was not modified to accept the incorrect offsets. Preserved failure reports distinguish those fixture mistakes from actual defects.

The combined owner also makes previously implicit integration requirements concrete: epoch/generation/session registry keys; actual accepted AfterTicket bases; complete rollback including history/outcomes; survival of reentrant requests; fresh session token namespaces; rebasing structural provenance after settlement; canonical semantic comparison excluding internal token metadata; and receipt installation before did-change reentry. These are documented integration choices, not falsely reported as independently discovered failures where no failing baseline was executed.

The prior 4.12.0 standalone, run-16 and earlier runs remain preserved. Their counts are historical and are not added to current execution totals. The new report scopes current counts to the integrated tests that actually ran.

'''+plan[b:]
plan=plan.replace('3. G2/G3: integrate the selected admission, structural overlay and history ledger in one owner transaction; reproduce all new cases through that public boundary, with failure injection and duplicate receipts. Separate models do not constitute this gate.',
 '3. G2/G3: port the now-integrated reference owner to the production Kotlin coordinator, extend it to the full product command/anchor/cell-flow/typing-intent catalog, and replay the required corpus with platform-specific fake adapters. Run-17 closes the separate-model integration gap for its recorded reference subset; it does not qualify the full production implementation.')
plan=plan.replace('The reviewed counterexamples now have chosen semantics and executable targeted checks. This is a reviewable specification baseline, not proof that every interaction has been explored.',
 'The reviewed counterexamples have chosen semantics and integrated executable checks for the recorded public-boundary subset. This is a reviewable specification baseline, not proof that every interaction has been explored.')
assert 'Revision: 4.13.0' in plan
assert 'admission and history policies are exercised separately' not in plan
assert 'separate admission contract model and a separate history-ledger model' not in plan
current=(root/'plan-v4.md').read_text(encoding='utf8')
previous_publication=json.loads((run/'publication-hashes.json').read_text()) if (run/'publication-hashes.json').exists() else {}
assert current==prior or current==plan or sha(root/'plan-v4.md')==previous_publication.get('plan-v4.md'), 'Plan changed independently during this run; preserve and reconcile before publishing'
details={x['name']:x['detail'] for x in r['results']}
named=len(details['namedTests']['cases'])
report=f'''# Revision 4.13.0 — integrated executable validation

Author: Codex. Run: `validation/codex/run-20261015-17`. Executed {r['timestamp']}.
Environment: Node {r['environment']['node']}, {r['environment']['platform']} {r['environment']['arch']}.

**The admission, structural composition and Undo ledger now execute together through one public request dispatcher and one outer publication transaction. All {len(r['results'])} integrated test groups pass. No production implementation was started.**

## Implementation of the validation model

`IntegratedModel.dispatch()` captures immutable native/application requests, reserves identified sessions, maps published-basis targets and proposed post-edit selections, and queues reentrant work. It owns the connection/generation, published snapshots, receipts, history/redo and command outcomes. A private tagged-tree/segment worker stages composition; its legacy history is ignored. Settlement drains the real-root action ledger before one final publication. Full transaction rollback restores document, protocol and history state together while preserving independently queued external work.

This is actual combined execution, not a runner invoking three disconnected model suites. The model uses arrays/full tree copies and synthetic provenance stamps; it intentionally makes no production complexity, latency, native-host or Unicode-completeness claim.

## Executed checks

| Group | Current execution |
|---|---|
| Named integrated regressions | {named} cases through the public dispatcher. |
| Generated structural/command interactions | {details['generatedIntegration']['cases']} traces with independent expected raw splice results and exact fixture-tree restoration. |
| Bounded queued-command exploration | {details['ledgerExploration']['cases']} traces, up to {details['ledgerExploration']['maxQueuedCommands']} queued commands, across clean/dirty/partial states and commit/cancel. |
| Repeated sessions | {details['repeatedSessions']['sessions']} sequential sessions, seed {details['repeatedSessions']['seed']}, {details['repeatedSessions']['failureRetries']} injected settlement failures/retries and {details['repeatedSessions']['staleDeliveries']} rejected late deliveries. |
| Behavioral mutations | {details['integratedMutations']['killed']} caught, with {details['integratedMutations']['controls']} passing controls; each failure is an assertion, not an unrelated tool/syntax crash. |
| Owner scheduler | {details['scheduling']['requests']} queued requests complete; the first turn executes {details['scheduling']['firstBudget']} and schedules continuation. |

Elapsed test-run time: {r['elapsedMs']/1000:.3f} seconds. This is test duration, not an editor performance benchmark. Reproduction command: `node validation/codex/run-20261015-17/verify.mjs`.

Named coverage includes cross-list replacement and tail preservation; failed initiator/dependent continuations; partial commitment plus cancelled formatting and multiple Undos; changed terminal commit without preedit; cancellation without double Undo; full rollback after staged formatting; published-basis reentry; surviving continuation after a failed terminal callback; generation barriers and AfterTicket selection bases; post-edit selection mapping; semantic no-op revisions; obsolete settlement evidence; connection turnover/disposal; duplicate receipt reentry; foreign-flow and object-seam rejection; atom detours; code/prose conversion; backward islands; subsequent-session hierarchy rebasing; observer isolation; host-fence rejection; fragmented target rejection; immutable payload capture; grapheme formatting/Undo; one-publication settlement; turnover rollback; and unrelated formatting after cancellation.

## What broke and was fixed

- **Actual integration defect:** will-change observer exceptions were initially treated as transaction failures and rejected valid native input. Notifications now have no admission authority. The before-repair observation is preserved in `integration-probes-before-repair.json`; the regression and observer-veto mutant distinguish the corrected behavior.
- **Repeated-start range defect:** a second composition-start request in an active session returned success without applying its new marked interval. It now follows the exact retarget path. The original [0,1) versus requested [2,3) failure is preserved in `final-review-before-repair.json`.
- **Protected-structure bypass:** explicit-range preedit could indirectly retarget through the private worker without the structural classifier, allowing it to consume an unselected inline atom. Changed explicit ranges on preedit/commit/native removal now share the guarded retarget path. The failed pre-repair assertion and behavior mutant are preserved alongside the repeated-start case.
- **Previously selected atom rejected:** the inherited structural classifier rejected any atom on retarget, including an atom admitted in the initial selection. Session-scoped structural identity permissions now distinguish retaining an already-selected atom from acquiring an unselected one, and are included in rollback state. The original failure is preserved in `selected-atom-before-repair.json`.
- **Test-fixture corrections:** the initial reentrant TAIL range omitted its last character, and a later post-preedit range incorrectly reused the original length. Corrected from the explicit strings: original TAIL [6,10), post-XY TAIL [5,9). The first failing runner output remains in a `failure-*.json` file. No implementation was altered to accept the invalid expectations.
- **Integration requirements implemented and exercised:** one owner-wide session namespace (no duplicate unit IDs across recreated workers), canonical hierarchy rebasing, full-root history rather than string transforms, complete outer rollback, pending external requests surviving rollback, stale-identity checks before duplicate lookup, canonical semantic equality, and receipt publication before notification reentry. These are integration changes, not invented claims of earlier failing tests.

## Final review and limits

The previously documented separation of admission, structural composition and the Undo model is closed for the operations exercised here. The current executable model can be used as a common validation baseline for those contracts. Source hashes, named cases, mutation evidence and environments are recorded in results.json.

Still unqualified: actual OS session provenance and completion fences; native/Web DOM interaction; accessibility; complete product command, typing-intent, registered-anchor and cell-flow coordination; the pinned full Unicode corpus; durable journal recovery; Kotlin persistent storage and allocator behavior; and production performance. The integrated model uses one connected flow and selected attribute patches. Ordinary typing coalescing and the complete application command catalog are not implemented in this reference slice. All production gates G0-G8 remain NOT RUN; this report neither advances phases nor authorizes production implementation.
'''
diff='''# Revision 4.12.0 -> 4.13.0

- Add section 4.9 defining one integrated public request boundary and publication owner.
- Replace the disconnected string-admission/string-history validation path with an owner driving structural composition and a full-root action ledger in one transaction.
- Capture immutable bases and actual dependency receipts; namespace session reservations by epoch/generation/token; reject stale and fragmented input.
- Roll back structure, protocol, history and command outcomes together; preserve independently admitted reentrant requests.
- Isolate observer exceptions; publish receipts before did-change duplicate delivery.
- Apply repeated-start ranges exactly and guard changed explicit ranges on all callback forms, closing the private implicit-retarget admission bypass.
- Pin initially admitted structural identities so retarget can retain selected atoms without acquiring unselected objects.
- Rebase canonical structural provenance after settlement/Undo/Redo and keep session unit identities unique across worker recreation.
- Distinguish semantic document equality from internal token/session metadata and unordered property insertion order.
- Add integrated generated traces, multi-session failure/retry runs, behavioral mutants and scheduler continuation checks.
- Update evidence claims: integrated reference subset validated; production Kotlin/native/full-product gates still unqualified.
- Preserve previous plans, standalone packages and runs. No production implementation.
'''
rows=[['Claim','Scope','Evidence','Status','Remaining requirement']]
for x in r['results']:rows.append([x['name'],'One integrated reference dispatcher','run-20261015-17/results.json','PASS (SCOPED)','Reference operations/bounds only'])
for i in range(9):rows.append([f'G{i}','Production phase gate','plan-v4.md §8','NOT RUN','No production implementation authorized'])
rows.extend([['Admission + structural composition + Undo','Integrated reference public boundary','integrated-model.mjs; test-integrated.mjs','PASS (SCOPED)','Full product/Kotlin/native qualification remains'],['Native session provenance and fences','Real OS/browser','plan-v4.md §4.2','NOT RUN','Synthetic stamps are not native evidence'],['Full command/cell-flow/anchor/typing-intent catalog','Whole editor','plan-v4.md §4.9','NOT QUALIFIED','Integrated reference implements a stated subset'],['Historical run-16','Separate earlier contracts','run-20261015-16/results.json','HISTORICAL; NOT RERUN','Do not add historical counts to current execution']])
buf=io.StringIO();csv.writer(buf).writerows(rows)
for name,content in [('plan-v4.md',plan),('antigravity-validation-report.md',report),('antigravity-plan-diff.md',diff),('antigravity-validation-matrix.csv',buf.getvalue())]:
    (root/name).write_text(content,encoding='utf8');(run/name).write_text(content,encoding='utf8')
prompt='''Review the attached Compose Multiplatform Editor Standalone Specification 4.13.0 independently. This is a read-only design review: do not implement production code or advance phases. Read all sections, including the integrated-owner contract in §4.9 and the scoped evidence report.

Assess whether admission, provisional structural replacement and history semantics compose correctly under queued and reentrant input, generation changes, partial commits, nonterminal unmark, terminal changes, cancellation, formatting and multiple Undos. Challenge exact coordinate bases, publication/rollback, identity lifetimes, hierarchy/provenance rebasing, semantic equality and surviving unrelated content. Assess the explicit ListItem Block+ schema and object-seam policies against the intended product.

The reference model now executes these tested components together. Do not repeat the old separate-model finding without checking the revised evidence. Conversely, synthetic stamps and bounded reference traces do not qualify native hosts, the full product command catalog, persistent Kotlin storage, accessibility or performance. Distinguish inspected, inferred and executed evidence.

For each material finding give section, severity, classification, concrete initial state/event sequence, expected versus specified behavior, smallest correction and a discriminating test. State unread material and unrun claims. Return a scoped readiness verdict and a finite closure checklist. Do not rubber-stamp, invent defects or silently reduce product scope.
'''
(root/'editor-4.13.0-review-prompt.md').write_text(prompt,encoding='utf8')
manifest={str(p.relative_to(root)).replace('\\','/'):sha(p) for p in [root/'plan-v4.md',root/'antigravity-validation-report.md',root/'antigravity-validation-matrix.csv',run/'results.json',run/'integration-contract.md']}
table='\n'.join('| '+' | '.join(str(x).replace('|','/') for x in row)+' |' for row in [rows[0],['---']*5,*rows[1:]])
standalone='''# Compose Multiplatform Editor Standalone Specification 4.13.0

This package contains the complete current normative plan, current integrated-model evidence, qualification ledger, provenance and independent-review prompt. No preceding conversation or earlier plan is required to review the design. The previous 4.12.0 package remains an archived snapshot.

The shared core remains a Kotlin document engine with native Compose rendering/input adapters and a semantic DOM Web surface. The new reference model executes input admission, structural composition and the Undo ledger together behind one public dispatcher and one publication owner. Section 4.9 states the precise model boundary; it is not a production implementation or native qualification claim.

This document is self-contained for design review, not a bundled source repository. To reproduce the tests, use the named source directory and commands in the report. All production phase gates remain unqualified. No implementation phase is authorized.

# Complete normative specification

'''+plan+'\n\n# Current integrated validation report\n\n'+report+'\n\n# Qualification ledger\n\n'+table+'\n\n# Integrity and reproduction\n\nFrom the workspace root, run `node validation/codex/run-20261015-17/verify.mjs`. The runner executes the integrated suites and writes results.json (and a failure archive if checks fail). It is not a read-only inspection command. Source hashes are embedded in results.json.\n\n```json\n'+json.dumps(manifest,indent=2)+'\n```\n\n# Independent review prompt\n\n'+prompt
(root/'editor-4.13.0-standalone-review.md').write_text(standalone,encoding='utf8')
(run/'publication-hashes.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
prior_files=[*list((run/'sources').glob('*.md')),*list((run/'sources').glob('*.csv')),*list((root/'validation/codex/run-20261015-16').glob('*.*')),root/'editor-4.12.0-standalone-review.md']
(run/'prior-file-hashes.json').write_text(json.dumps({str(p.relative_to(root)).replace('\\','/'):sha(p) for p in prior_files if p.is_file()},indent=2),encoding='utf8')
(run/'README.md').write_text('''# Run 17 — integrated reference owner

No production implementation. One connected-flow reference owner integrates admission, tagged structural composition and full-root Undo/Redo behind dispatch().

From the project root:

```powershell
node validation/codex/run-20261015-17/verify.mjs
python -X utf8 validation/codex/run-20261015-17/publish.py
```

The runner writes results.json and archives failures; the publisher requires matching passing source hashes before publishing the revised root documents and standalone package.

- integrated-model.mjs: public owner, admission registry, snapshot publication, full rollback and action ledger.
- structural-model.mjs / candidate-b-coordinator.mjs / request-contract.mjs: copied structural/segment working components. Their old internal command/history API is not the integrated owner's public authority.
- test-integrated.mjs: every integration trace edits through dispatch(), including generated and repeated-session cases.
- integration-contract.md: normative section added to the plan.
- integration-probes-before-repair.json and failure-*.json: observed pre-repair defect/fixture evidence.

Arrays/full copies and synthetic host stamps are intentional reference-model limits. Production, native hosts, complete command/anchor/cell/typing-intent coverage, durable recovery, full Unicode and performance remain unqualified. See the report for exact counts and scope.
''',encoding='utf8')
print(json.dumps({'revision':'4.13.0','namedCases':named,'groups':len(r['results']),'planBytes':len(plan.encode()),'standaloneBytes':len(standalone.encode()),'matrixRows':len(rows)-1}))
