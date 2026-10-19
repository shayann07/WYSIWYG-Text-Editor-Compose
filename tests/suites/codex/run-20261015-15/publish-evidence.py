from pathlib import Path
import json, csv, hashlib, re

run=Path(__file__).resolve().parent
root=run.parents[2]
r=json.loads((run/'results.json').read_text(encoding='utf-8'))
if not r['passed']: raise SystemExit('Cannot publish a passing report from failed checks')
for name,h in r['sourceHashes'].items():
    assert hashlib.sha256((run/name).read_bytes()).hexdigest()==h, name
plan=(root/'plan-v4.md').read_text(encoding='utf-8')
assert 'Revision: 4.11.0' in plan
for obsolete in ['Every composition session is scoped to exactly one','One-step cross-paragraph IME replacement/reconversion is not a baseline capability','Mark existing single-block text','Retarget single-block candidate']:
    assert obsolete not in plan, obsolete
lookup={x['name']:x['detail'] for x in r['results']}
cross=lookup['Cross-paragraph composition'];bfs=lookup['Retained bounded model exploration']
plan_hash=hashlib.sha256((root/'plan-v4.md').read_bytes()).hexdigest()
report=f'''# Cross-paragraph composition — specification and validation, revision 4.11.0

Author: Codex. Date: 15 October 2026. Run: `validation/codex/run-20261015-15`.

**The single-block composition restriction is withdrawn. Cross-paragraph composition is a required capability with a concrete flow-scoped structural overlay and executable validation. No production implementation was started.**

## Chosen behavior

Paragraph/heading separators are tagged structural units, each retaining the exact right-block identity and properties. Composition can replace a range spanning compatible paragraphs, including same-parent paragraphs inside quotes or list items. A removed separator creates a provisional join. The committed version retains its preimage until the corresponding effect commits; cancellation restores exact structure. An initial native empty preedit deleting a selected multi-paragraph range belongs to the same composition session, not an independent application deletion.

For `p1:"ab"`, `p2:"cd"`, composing over `b\\nc` with `XY` displays `p1:"aXYd"`. Cancel restores both original blocks. Commit creates one Composition history group; Undo restores both original blocks, properties, attributes and selection. After partial commit of `X`, cancelling `Y` leaves `p1:"aXd"`, and one Undo restores the original two-block document.

Unchanged tagged LF remains a paragraph boundary; newly inserted raw LF is a soft break. Returning to the epoch baseline restores original boundary identity. Explicit Enter/paste commands create new blocks. Inline formatting never patches structural separator units and does not style untouched middle paragraphs between disjoint candidate islands.

Separate table-cell flows, protected atoms and incompatible schema ancestry remain structural fences. Native plaintext input must not silently flatten them. These are the existing hierarchy rules, not an exclusion of ordinary cross-paragraph composition. The plan's connected-domain contract is explicit about compatible parent/join classes.

## Repairs exposed by structural tests

- Direct Undo now transfers and restores complete unit metadata, including boundary tags, rather than reconstructing only text and attributes.
- Partial commit distinguishes retained-original segments from replacement segments. Accepting a positive prefix of a replacement consumes its whole old footprint; its remaining candidate tail is an insertion. Untouched original segments retain their own rollback obligations.
- Cancellation after accepted partial edits selects a valid position in the resulting flow instead of restoring stale offsets in removed paragraphs.
- Committed formatting targets are intersected with semantic candidate islands, so freezing a prefix does not turn carried-through middle paragraphs into formatting targets.
- Semantic revision comparison includes structural boundary identity/properties and placement, even when plaintext is equal.

The independent tape oracle was changed only where its old partial-commit/cancellation rules disagreed with the specified ownership and selection policy. Those corrections are justified by hand-calculated structural examples and independently constructed expected tapes, not by importing candidate helpers. The old runs remain intact.

## Executed verification

Command: `node validation/codex/run-20261015-15/verify.mjs`.
Environment: {r['environment']['node']} / {r['environment']['platform']} / {r['environment']['arch']}.
Elapsed: {r['elapsedMs']/1000:.2f} seconds. All {len(r['results'])} verification groups passed.

- **{cross['named']} named cross-paragraph cases and {cross['generated']} generated differential cases.** Expected tree state comes from fixture headers and independent flat expected-tape splices. Assertions cover exact block IDs/types/properties, empty-block attributes, raw inline attributes, text, selection, committed versus visible structure and history.
- Coverage includes candidate detours; two/three paragraphs; empty paragraphs; headings; same-parent nested paragraphs; initial provisional deletion; retargeting; raw LF versus structural LF; malformed UTF-16 retention; partial commit before/through/deleting a separator; committed-prefix cancellation; full deletion; queued formatting/Undo; disjoint islands; connection turnover/disposal; rollback after staged history; repeated Undo/Redo; and structural-only revision changes.
- Three structural behavioral mutants restore text-only Undo, incomplete replacement-footprint acceptance, and boundary loss. All fail the same assertions passed by the baseline.
- Retained suites pass: 22 regression blocks, 18 Candidate B/oracle fixtures, 21 settlement scenarios, 17 semantic traces, 10 longer sequences, 85 sampled state/event cells, and 22 transaction tests.
- The retained public-boundary and final-contract suites pass. Across all mutation suites, 67 behavioral mutants are caught with passing controls (56 inherited + 4 boundary + 4 final-contract + 3 structural).
- Persistent-index validation still passes 1,200 randomized edits with 30 historical versions and instrumented local-maintenance workloads.
- Retained depth-4 exploration: {bfs['states']:,} recorded states and {bfs['transitions']:,} transitions, with no violation of its implemented assertions. That explorer is the flat coordinator exploration; **the new structural cases are separate tests, not a claim of exhaustive hierarchical model checking**.
- Run-14 preservation is checked against recorded source hashes. Before-state root documents are archived under this run's `sources/` directory.

## Final review and readiness

The plan now specifies tagged units, flow/domain admission, provisional materialization, separator reconciliation, partial-commit ownership, cancellation caret placement, logical history grouping, position/anchor mapping, persistent-storage lowering, native bridge obligations and acceptance gates for cross-paragraph input. The old single-block rule and separate preparation requirement have been removed from the normative contract and transition table.

This is an implementation specification backed by scoped executable models. The flow model is an array-based validation implementation, not production Kotlin storage, native rendering, or a browser/native IME host. Real Android/iOS/Desktop/Web input traces must qualify original-selection capture, composition event ordering, native ownership and synchronization. Accessibility, crash durability, full Unicode conformance, production storage integration and performance remain gated. A protected structural boundary is not silently converted to text to make a test pass.

No unresolved choice remains about ordinary cross-paragraph composition in the declared compatible text domains. The architecture is ready to implement that contract; live platform feasibility and production correctness remain to be demonstrated. No implementation phase is authorized by this report.

Plan SHA-256: `{plan_hash}`. Source hashes and detailed results are in `results.json`.
'''
diff='''# Revision 4.10.0 → 4.11.0

The previous single-block limitation is withdrawn in response to the owner's requirement for cross-paragraph composition.

- §1.1 makes cross-paragraph composition a required capability.
- §4.2 replaces the block-local overlay contract with a tagged flow-scoped overlay.
- New §4.7 specifies paragraph identity/property preimages, provisional joins, commit/cancel/partial commit, history, selection/anchors, storage lowering, native integration and required tests.
- §6.1 admits composition across compatible paragraph/heading boundaries without preparatory deletion. Separate cell flows, atoms and incompatible ancestry keep explicit schema rules.
- G3/G5 require cross-paragraph core traces and real-host qualification respectively.
- Direct history restoration and partial-commit ownership defects exposed by structural tests are repaired. Candidate and independent tape-oracle changes are explained in the report.
- Prior scope decisions and models remain preserved; no production code or phase advancement was introduced.
'''
for name,content in [('antigravity-validation-report.md',report),('antigravity-plan-diff.md',diff)]:
    (root/name).write_text(content,encoding='utf-8');(run/name).write_text(content,encoding='utf-8')
headers=['Requirement','EvidenceScope','TestIdentifierAndAssertions','ExecutionEnvironment','Result','RemainingGap','PhaseGateStatus'];rows=[]
for s in r['results']:rows.append([s['name'],'Executed validation model',f"validation/codex/run-20261015-15/results.json: {s['name']}",str(r['environment']),'PASS (SCOPED)','Only recorded checks and bounds','No production gate passed'])
for line in plan.splitlines():
    if re.match(r'\| I\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]]
        rows.append([f'{cells[0]}: {cells[1]}','Whole-engine invariant','plan-v4.md §3; current model evidence','Production NOT RUN','PARTIAL MODEL EVIDENCE' if cells[0] not in ['I03','I11','I14','I15','I17'] else 'NOT QUALIFIED','Full production scope still unqualified','NOT RUN'])
for i in range(9):rows.append([f'G{i}','Production phase gate',f'plan-v4.md Phase {i}','NOT RUN','NOT RUN','No production implementation authorized','NOT RUN'])
for line in plan.splitlines():
    if re.match(r'\| U\d+ \|',line):
        cells=[c.strip() for c in line.split('|')[1:-1]];rows.append([f'{cells[0]}: {cells[1]}','Outstanding qualification','plan-v4.md §9.4','NOT RUN',cells[2],cells[-1],'Unresolved or excluded as specified'])
rows.append(['Cross-paragraph composition','Tagged-flow model and independent expected tapes','test-cross-paragraph.mjs; plan-v4.md §4.7','JavaScript model','PASS (SCOPED)','Real native hosts and production storage integration NOT RUN','G3/G5 still NOT RUN'])
for dest in [root/'antigravity-validation-matrix.csv',run/'antigravity-validation-matrix.csv']:
    with dest.open('w',encoding='utf-8',newline='') as f:w=csv.writer(f);w.writerow(headers);w.writerows(rows)
(run/'plan-v4.md').write_text(plan,encoding='utf-8')
(run/'README.md').write_text('''# Cross-paragraph composition — run 15

Specification and validation models only; no production implementation.

From the workspace root:

```powershell
node validation/codex/run-20261015-15/verify.mjs
python -X utf8 validation/codex/run-20261015-15/publish-evidence.py
```

`runner.mjs` aliases verify.mjs. Verification writes results.json and exits nonzero on failure. Publishing checks result status and source hashes. The bounded explorer is the slowest suite; allow several minutes. No network or package installation is needed.

New files: `flow-composition-model.mjs`, `test-cross-paragraph.mjs`, `cross-paragraph-spec.md`. The compatibility admission module now exports cross-paragraph admission. Earlier single-block-only tests are preserved in run-14; current tests assert the new required capability and continued protection of cells/atoms/schema fences.

The flow coordinator reuses the existing composition reducer over tagged units. The expected structural oracle is a separate fixture/tape construction. The original tape oracle was explicitly corrected for replacement-footprint ownership and cancellation selection. This is a validation artifact, not a production hierarchical editor.

Root filenames retain the established antigravity prefix for continuity. Codex authored and verified revision 4.11.0 directly. Native platform qualification remains NOT RUN.
''',encoding='utf-8')
print(f'Published revision 4.11.0: {len(rows)} scoped ledger rows')
