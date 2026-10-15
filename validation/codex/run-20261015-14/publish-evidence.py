from pathlib import Path
import json, csv, hashlib, re

run=Path(__file__).resolve().parent
root=run.parents[2]
r=json.loads((run/'results.json').read_text(encoding='utf-8'))
if not r['passed']: raise SystemExit('Verification failed; cannot publish passing evidence')
for name,expected in r['sourceHashes'].items():
    if hashlib.sha256((run/name).read_bytes()).hexdigest()!=expected:
        raise SystemExit('Source changed after verification: '+name)
lookup={s['name']:s for s in r['results']}
bfs=lookup['Retained bounded model exploration']['detail']
plan=(root/'plan-v4.md').read_text(encoding='utf-8')
assert 'Revision: 4.10.0' in plan
for old in ['Reentrant protocol reads may observe consistent staged state',
            'then execute a separate transaction/history group',
            '**Fallback on Failure**',
            'the bridge must settle any existing session, execute the semantic deletion']:
    assert old not in plan, old
plan_hash=hashlib.sha256((root/'plan-v4.md').read_bytes()).hexdigest()

report=f'''# Final contract closure and readiness review — revision 4.10.0

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-14`.

**Verdict: the five final-review design/contract gaps are closed. The plan is a concrete baseline for gated implementation of its declared scope. It is not a production qualification, a proof of arbitrary execution correctness, or authorization to start implementation.**

## The five decisions and evidence

| Finding | Normative decision | Validation |
|---|---|---|
| Published versus staged reads | External protocol queries and notifications observe the published snapshot only, including session identity and completion token. Working state is reducer-private. | Successful/failed preedit probes, failed start-composition metadata probe, previous immutable-snapshot tests. |
| Settlement transaction boundary | One native settlement and its queued command batch publish atomically. Composition/formatting have separate logical undo groups, not separate publications. | Failure after command drainage restores text, attributes, selection, revisions, pending tickets, outcomes, history and receipt state. Retry creates exactly one of each group. Formatting Undo and composition Undo restore their distinct preimages. |
| Cross-paragraph composition | Every composition belongs to one flow/text block. Native cross-block composition rejects without changing the document. Explicit application structural deletion is a separate committed edit with its own undo group. | Tagged-projection classifier covers block separators, same-block text, empty blocks, protected atoms, and retargeting to another block. Cancellation/history preparation examples are explicitly scoped fixtures, not a tested hierarchical reducer. |
| SessionRevision | The transaction owner assigns exactly before+1 on each successful execution/publication, including selection, typing intent and accepted no-ops. Rejection, duplicates and queued transport acknowledgements do not advance it. | Original run-13 defect reproduced; direct session operations, no-ops, rejected selection, duplicates, settlement batches and obsolete evidence verified. |
| Invalid synchronous evidence | Rejection only: no implicit Undo queue, no mutation, no consumed record, no revision. Ordinary asynchronous Undo is a separate request. | Eight malformed/stale/mismatched evidence cases reject without state changes; ordinary Undo still queues. |

The single-block rule is a deliberate baseline capability limit. Cross-block selection, explicit semantic replacement, rich paste and admitted noncomposing native replacement remain supported by the plan. One-step cross-paragraph IME replacement/reconversion is outside the baseline. An application must not silently pre-delete such a selection or describe the two-edit preparation workflow as equivalent to one-step IME replacement.

During the final consistency pass, adjacent stale wording was corrected: retarget/mark-existing may start an implicit session; resumed preedit returns to Composing while preserving queued obligations; partial commit is guarded by the presence of a marked candidate rather than only the lifecycle label; zero committed units are valid; replacement tails and retained-original tails have distinct footprint rules. Partial commit now invalidates prior settlement evidence inside the atomic request executor. Public session-ID/token queries now use the published snapshot. These changes reinforce the chosen contracts without introducing a replacement architecture.

## Executed checks

Command: `node validation/codex/run-20261015-14/verify.mjs`.
Environment: {r['environment']['node']} / {r['environment']['platform']} / {r['environment']['arch']}.
Elapsed: {r['elapsedMs']/1000:.2f} seconds.

- All {len(r['results'])} verification groups passed.
- 22 retained regression blocks; 18 Candidate B and oracle fixtures; 21 settlement scenarios; 17 semantic traces; 10 longer sequences.
- 85 selected lifecycle/event cells and 22 retained transaction tests. These are sampled cases, not exhaustive protocol verification.
- 56 inherited behavioral mutants, 4 request-boundary mutants, and 4 final-contract mutants: all 64 assertion kills with passing controls.
- 11 named request-boundary checks, 36 generated immediate/deferred cases, and a 41-request scheduled-continuation test.
- Five final-contract checks, eight rejected-evidence cases, nine tagged-projection cases, and two explicitly scoped structural-preparation examples.
- 1,200 randomized persistent-index edits with 30 retained historical versions and instrumented maintenance workloads up to 16,384 original pieces.
- Retained depth-4 exploration: {bfs['states']:,} recorded states, {bfs['transitions']:,} transitions; no violations of its implemented assertions. This does not include all native callbacks, all scheduler interleavings or the hierarchical document engine.
- Prior run-13 files remain unchanged, verified by hashes. Before-state root documents are preserved in `sources/`.

The oracle was not edited in this round. Candidate A's existing 8 fixture failures remain expected comparison evidence; they are not failures of the selected Candidate B.

## Final review and readiness boundary

The review checked the five decisions against the core model, public request path, publication/rollback behavior, lifecycle table, history grouping, input admission and acceptance gates. No unresolved decision remains among these five contracts. A successful test run does not establish that the entire eventual engine is bug-free.

Remaining work is explicitly gated implementation and empirical qualification:

- Kotlin implementation of the document tree, semantic structural commands, attributes, persistence and platform projections; integration of the separate coordinator and identity-index models.
- Real Android/iOS/Desktop/Web IMEs, synchronous reentry/acknowledgement behavior, settlement provenance and browser DOM preservation.
- Editable accessibility, full pinned Unicode conformance, physical crash recovery and end-to-end performance, especially large single paragraphs.
- Measured allocator/fragmentation costs on actual targets. No production memory or latency gate passed here.

Those experiments can invalidate feasibility assumptions and require a documented revision. They are not represented as already solved. If one-step cross-paragraph IME replacement is required for the product, this baseline scope must be expanded before that capability can be considered implementation-ready.

No production implementation, scaffolding, dependency freeze or phase advancement was performed.

Plan SHA-256: `{plan_hash}`. Executable source hashes and detailed results are in `results.json`.
'''
diff='''# Revision 4.9.9 → 4.10.0

Before-state: `validation/codex/run-20261015-14/sources/`.

1. I02 and §4.1 consistently require published-only external reads. Public session metadata now follows the same rule as text/selection.
2. §4.5 defines one atomic settlement publication with distinct logical history groups, normal ticket rejection semantics, unexpected failure rollback and host recovery requirements. The old separate-transaction wording is removed.
3. §1.1/§6.1 explicitly scope composition to one block, prohibit implicit structural deletion, and define the independent history consequences of explicit application preparation.
4. §2.2/§7 centralize SessionRevision ownership, accepted no-ops and receipt revision pairs. Transition-table revision cells reference this rule.
5. §4.5 defines invalid synchronous evidence as rejection-only, names reason codes, distinguishes adapter provenance from test strings and rejects fractional/non-finite extents.
6. Final review reconciles adjacent lifecycle wording with marked-window guards, implicit retargeting and evidence invalidation. G1 covers serialization prerequisites; full I14 crash durability remains at G7.
7. Existing tests and previous runs are preserved. New evidence lives in test-final-contracts.mjs and composition-admission.mjs. No independent oracle changes were made in this round.
'''
for name,content in [('antigravity-validation-report.md',report),('antigravity-plan-diff.md',diff)]:
    (root/name).write_text(content,encoding='utf-8');(run/name).write_text(content,encoding='utf-8')
headers=['Requirement','EvidenceScope','TestIdentifierAndAssertions','ExecutionEnvironment','Result','RemainingGap','PhaseGateStatus']
rows=[]
for suite in r['results']:
    rows.append([suite['name'],'Executed validation model',f"validation/codex/run-20261015-14/results.json: {suite['name']}",str(r['environment']),'PASS (SCOPED)','Only the recorded assertions and bounds','No production gate passed'])
for line in plan.splitlines():
    if re.match(r'\| I\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]]
        rows.append([f'{cells[0]}: {cells[1]}','Whole-engine invariant','plan-v4.md §3; model scopes in results.json','Production NOT RUN','PARTIAL MODEL EVIDENCE' if cells[0] not in ['I03','I11','I14','I15','I17'] else 'NOT QUALIFIED','Production implementation/qualification remains','NOT RUN'])
for i in range(9):rows.append([f'G{i}','Production phase gate',f'plan-v4.md Phase {i}','NOT RUN','NOT RUN','No production phase authorized in this run','NOT RUN'])
for line in plan.splitlines():
    if re.match(r'\| U\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]]
        rows.append([f'{cells[0]}: {cells[1]}','Outstanding qualification','plan-v4.md §9.4','NOT RUN',cells[2],cells[-1],'Unresolved or excluded as specified'])
rows.append(['Single-block composition scope','Product/design policy','plan-v4.md §1.1/§6.1; composition-admission.mjs','Model only','DESIGN POLICY','One-step cross-paragraph composing replacement excluded','Not a native platform qualification'])
for destination in [root/'antigravity-validation-matrix.csv',run/'antigravity-validation-matrix.csv']:
    with destination.open('w',encoding='utf-8',newline='') as f:
        writer=csv.writer(f);writer.writerow(headers);writer.writerows(rows)
(run/'plan-v4.md').write_text(plan,encoding='utf-8')
(run/'README.md').write_text('''# Final five-contract closure — run 14

Specification and validation models only; no production code.

From the workspace root:

```powershell
node validation/codex/run-20261015-14/verify.mjs
python -X utf8 validation/codex/run-20261015-14/publish-evidence.py
```

`runner.mjs` aliases verify.mjs. Verification writes results.json and exits nonzero on failure. Publication checks source hashes and refuses failed or stale results. The slowest check is bounded model exploration; it typically takes one to three minutes on this host. No network or third-party installation is needed.

`test-final-contracts.mjs` covers the five decisions. `composition-admission.mjs` is a tagged-projection classifier, not a production hierarchical reducer. Earlier suites, the independent oracle and the versioned-index model are retained. Prior run-13 files are checked against baseline-hashes.json. `sources/` preserves the previous root documents.

Root report filenames retain the established antigravity prefix for continuity. Codex performed this revision and verification directly. The evidence report states the readiness boundary and the single-block composition scope prominently.
''',encoding='utf-8')
print(f'Published revision 4.10.0: {len(rows)} scoped ledger rows')
