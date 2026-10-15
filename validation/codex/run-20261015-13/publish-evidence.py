"""Render the evidence ledger from an already completed verification run.
No tests, implementation gates, or production performance are inferred from prose.
"""
from pathlib import Path
import json, csv, hashlib, re

run = Path(__file__).resolve().parent
root = run.parents[2]
r = json.loads((run / 'results.json').read_text(encoding='utf-8'))
if not r['passed']:
    raise SystemExit('Cannot publish a passing report from failed results')
by_name = {s['name']: s for s in r['results']}
bfs = by_name['Retained bounded model exploration']['detail']
boundary = by_name['Public request boundary']['detail']
idx = by_name['Versioned identity index']['detail']
plan_hash = hashlib.sha256((root / 'plan-v4.md').read_bytes()).hexdigest()

report = f'''# Specification repair and validation — revision 4.9.9

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-13`.

**Outcome: the reviewed counterexamples are repaired in the executable validation models, and the specification now describes the tested mechanisms. Production implementation and all platform release gates remain unapproved and unqualified.**

This work replaces the repeated prompt handoff with direct repairs. All run-12 files were preserved and checked against their recorded SHA-256 hashes. The prior root plan/report/matrix/diff are preserved under this run's `sources/` directory. This workspace has no Git repository; these archived sources and hashes provide the before-state evidence.

## Repairs

1. Every supported public mutation method captures immutable data and uses one executor. Deferred execution repeats the same identity, payload, range and post-edit selection validation as immediate execution. `[999,999]` selection is rejected without mutation.
2. Requests observed against published `abc` retain that snapshot's identities, even while staged text is `Xabc`. The reviewed replacement now produces `XaQc`, not `XQbc`. Post-replacement selections map in their own proposed coordinate space.
3. Direct formatting no longer captures mutable caller closures. Changing a target after enqueue does not change the admitted command. Public commands received during a failed outer transaction remain independent requests.
4. Published snapshots and notification arguments are deeply immutable. Nested attribute/selection writes cannot corrupt publication; retained snapshots do not change on later edits.
5. Receipts publish before callbacks can re-deliver the same native request. Duplicate replacement is recognized before stale target mapping. One queue owner executes FIFO work in batches of 64 and schedules continuation with no additional input.
6. A new validation-only persistent B+ sequence uses versioned parent links and persistent AVL indexes. It supports interior piece insertion, deletion, rebalancing, old snapshots and exact index ownership without rebuilding every token locator. Point rank is explicitly **O(log² P)**; no O(log P) claim is made.

## Executed evidence

Command: `node validation/codex/run-20261015-13/verify.mjs`.

Environment: {r['environment']['node']} / {r['environment']['platform']} / {r['environment']['arch']}.
Total verification time: {r['elapsedMs']/1000:.2f} seconds.

- 22 retained regression blocks; 18 reference fixtures for Candidate B and the oracle.
- 21 settlement scenarios; 17 semantic traces; 10 longer sequences.
- 85 selected state/event cells and 22 retained transaction tests. These are sampled cases, not exhaustive protocol coverage.
- 56 inherited behavioral mutants with 56 passing controls; 4 additional boundary mutants with 4 passing controls, all caught by assertions rather than unrelated crashes.
- {boundary['named']} new named boundary cases; {boundary['generated']} generated immediate/deferred cases; a 41-request queue completing after budget yields without further user input.
- {idx['randomEdits']} randomized index edits, with {idx['historicalVersions']} historical versions retained and rechecked. Independent expected state is a flat ordered token list.
- Retained depth-4 model exploration: {bfs['states']:,} recorded states and {bfs['transitions']:,} transitions, with no violations of its implemented checks. This explorer does not model all reentrant schedules or native platforms; the new scheduler/boundary tests are separate evidence.
- Baseline candidate A still fails 8 of its 18 comparison fixtures, as before. This expected comparison result does not count as a Candidate B test failure or prove all alternatives unsound.

For prepend operations at 64 / 1,024 / 16,384 original pieces, the new locator changed 17 token locators at each size; parent-link writes were 5 / 22 / 39. Map traversal/allocation counters are recorded in results.json. These counters support local maintenance for those workloads; they are not production timing or heap measurements.

## Evidence corrections

The old prototype rebuilt its token index after every insertion. Its tests did not establish incremental update complexity, and insertion inside a five-unit piece occurred at offset five rather than offset two. It is preserved in run-12 and superseded here.

The old memory calculator derived text bytes from piece count and mixed assumptions with reported engine heap totals. Its 5.16/98.22 MiB totals are withdrawn. The new estimator takes unit count and piece count separately and labels assumed record/node sizes; no JVM, Android, iOS, or Web allocator measurement is claimed.

Two inherited expectations were corrected explicitly: invalid formatting ranges reject before allocating a ticket; stale epochs reject before any receipt lookup. The oracle's unrelated semantic behavior was not rewritten to mirror the new executor. Four new failure probes were first run against the preserved baseline and failed by assertion, then passed against the repaired model.

## Limits and remaining work

- The coordinator is an array-based executable model. The persistent identity index is a separate validation model. Neither is a production Kotlin implementation; their eventual integration must pass G1.
- Native IMEs, editable accessibility, browser composition DOM preservation and adapter synchronous reentry/acknowledgement behavior remain NOT RUN. Real settlement evidence cannot be established by a test object's qualification string.
- Full pinned Unicode conformance, hierarchical document operations, real crash durability and 100K-paragraph layout latency remain implementation/platform gates.
- Reentry is modeled using hooks and private test seams. The flat reference oracle and bounded explorer do not constitute a formal proof over unbounded state.
- The selected index has explicit costs; allocator layout, fragmentation limits and latency remain measurement tasks. Background coalescing is not a universal memory bound.

No production implementation, project scaffolding, dependency freeze or phase advancement was performed.

Plan SHA-256: `{plan_hash}`.
Executable source hashes, actual counts and run environment are in `results.json`.
'''

diff = '''# Plan changes — revision 4.9.8 to 4.9.9

Preserved before-state: `validation/codex/run-20261015-13/sources/`.

- §4.1 now owns a single admission/publication contract: immutable requests, pinned published basis, complete execution validation, receipt publication ordering, rollback ownership, and scheduled FIFO continuation.
- §4.3 references that contract instead of maintaining a competing transaction description.
- §4.5 replaces absolute-path locators with a versioned document-root/token-map/parent-map bundle. Point rank is O(log² P); updates account for map maintenance. Interior insertion and deletion have concrete algorithms.
- §2.3 distinguishes text slab size from descriptor leaf occupancy.
- Memory formulas distinguish units from pieces and explicitly identify assumptions. Previous engine-heap totals are withdrawn.
- Historical verification narratives were removed from the normative plan and remain preserved in source snapshots; current evidence and limitations replace conflicting success claims.
- Tests changed only where the prior expected behavior contradicted upfront validation or epoch isolation. These changes are documented in the report. Old run files were not edited.
- All production phases and platform qualification gates remain unapproved/NOT RUN. No platform support or latency guarantee was added.
'''

header = ['Requirement','EvidenceScope','TestIdentifierAndAssertions','ExecutionEnvironment','Result','RemainingGap','PhaseGateStatus']
rows = []
for suite in r['results']:
    rows.append([suite['name'],'Executed validation model',f"validation/codex/run-20261015-13/results.json: {suite['name']}",str(r['environment']),'PASS (SCOPED)','Only the assertions and bounds recorded in results.json','No production gate passed'])
plan = (root/'plan-v4.md').read_text(encoding='utf-8')
for line in plan.splitlines():
    if re.match(r'\| I\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]]
        ident=cells[0]
        status='PARTIAL MODEL EVIDENCE' if ident not in ['I03','I11','I14','I15','I17'] else 'NOT QUALIFIED'
        rows.append([f'{ident}: {cells[1]}','Whole-engine invariant',f'plan-v4.md §3; current model scopes in results.json','Production runtimes NOT RUN',status,'Model tests do not establish the whole-engine invariant','NOT RUN'])
for i in range(9):
    rows.append([f'G{i}','Production phase gate',f'plan-v4.md Phase {i}','NOT RUN','NOT RUN','Production implementation and platform qualification not authorized by this run','NOT RUN'])
for line in plan.splitlines():
    if re.match(r'\| U\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]]
        rows.append([f'{cells[0]}: {cells[1]}','Outstanding uncertainty','plan-v4.md §9.4','Not executed in this run',cells[2],cells[-1],'Unresolved / excluded as specified'])
rows.append(['Prior identity index complexity and heap totals','Superseded claim','run-12 source preserved; replacement tests in current run','JavaScript model','WITHDRAWN','Old prototype rebuilt the index; heap totals were assumptions with incorrect text sizing','No production gate passed'])

for name,content in [('antigravity-validation-report.md',report),('antigravity-plan-diff.md',diff)]:
    (root/name).write_text(content,encoding='utf-8')
    (run/name).write_text(content,encoding='utf-8')
for dest in [root/'antigravity-validation-matrix.csv',run/'antigravity-validation-matrix.csv']:
    with dest.open('w',encoding='utf-8',newline='') as f:
        w=csv.writer(f);w.writerow(header);w.writerows(rows)
(run/'plan-v4.md').write_text(plan,encoding='utf-8')
(run/'README.md').write_text('''# Codex specification repair, run 13

Scope: specification and validation models only. No production implementation.

From the workspace root:

```powershell
node validation/codex/run-20261015-13/verify.mjs
python -X utf8 validation/codex/run-20261015-13/publish-evidence.py
```

`runner.mjs` is an alias for verify.mjs. `results.json` contains actual results and executable source hashes; verification exits nonzero on failure. Evidence publication refuses a failed verification result. The bounded explorer is the slowest suite (roughly two minutes on this host). No network or additional dependencies are needed.

`sources/` contains the pre-edit root documents. `baseline-hashes.json` covers the preserved run-12 files. `reproduce-failures.mjs` runs the four new boundary assertions against that baseline without modifying it.

The inherited tests are retained. New tests live in `test-public-boundary.mjs` and `test-versioned-index.mjs`. `request-contract-spec.md` and `identity-index-spec.md` are the section text incorporated into the authoritative root plan.

The prior sorted-array/rebuild index prototype is preserved in run-12. The similarly named current compatibility entry runs the new versioned-index tests. No old index timing or heap claim carries forward.

Root report filenames retain their established antigravity prefix for continuity; revision 4.9.9 was repaired and verified directly by Codex.
''',encoding='utf-8')
print(f'Published revision 4.9.9 evidence: {len(rows)} scoped ledger rows')
