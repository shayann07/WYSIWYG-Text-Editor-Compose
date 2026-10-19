from pathlib import Path
import json,hashlib,re,csv,io
run=Path(__file__).resolve().parent;root=run.parents[2];sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((run/'results.json').read_text());assert r['passed']
for name,h in r['sourceHashes'].items():assert sha(run/name)==h,name
for name,h in json.loads((run/'gemini-input-hashes.json').read_text()).items():assert sha(root/Path(name.replace('\\','/')))==h,name
for name,h in json.loads((root/'validation/codex/run-20261015-19/publication-hashes.json').read_text()).items():assert sha(root/name)==h,'Historical baseline changed: '+name
plan=(run/'sources/plan-v4.md').read_text(encoding='utf8')
plan=plan.replace('# Compose Multiplatform document editor — implementation specification','# Compose Multiplatform document editor — specification v5.0.0',1)
plan=re.sub(r'Revision: 4\.15\.0[^\n]*','Revision: 5.0.0, 16 October 2026. Status: FROZEN specification and conditional architecture baseline, with explicit qualification and reopen gates. No production implementation or production readiness is implied or authorized.',plan,count=1)
plan=plan.replace('This replaces v1 as the implementation baseline.','This supersedes v1–v4.15.0 as the normative specification. plan-v4.md and older standalone documents remain immutable historical evidence.')
plan=plan.replace('Empty resulting candidates affect typing intent only unless accepted session text remains.','An empty settled composition target cancels the composition-format action with TargetDeleted and one CommandWithoutHistory ledger credit; it does not change typing intent. Accepted surviving session text remains the target. Only an explicitly collapsed caret-format request is typing-intent-only.')
plan=plan.replace('The first block is represented by a versioned head record, including an empty landing block when all content is removed.','The first block is represented by a versioned head record, including an empty landing block when all content is removed. A deleted object head plus its consumed trailing boundary cannot absorb a surviving right leaf; apply the explicit head-resolution rule in section 4.12.3.')
plan=plan.replace('Two endpoint conversions are explicit: replacing only a block object\'s content while retaining its surrounding boundaries converts that selected leaf to a Paragraph at the same NodeId,','Two endpoint conversions are explicit: replacing only a block object\'s content while retaining its surrounding boundaries (or with no later leaf merged) converts that selected leaf to a Paragraph at the same NodeId,')
plan=plan.replace('Pin one published Unicode data version with hashes in Phase 0; do not retain v1\'s unverified Unicode 18.0 release assertion.','v5 pins Unicode 16.0.0 data and hashes for segmentation; see section 4.12.4. Do not retain v1\'s unverified Unicode 18.0 release assertion.')
plan=plan.replace('Intl.Segmenter is used only for these reference fixtures; a pinned Unicode implementation and cross-platform conformance corpus are still required.','Run-20 uses pinned Unicode 16 tables for graphemes; its word movement still uses Intl.Segmenter. Production Kotlin tables and cross-platform Unicode/word/bidi qualification remain required.')
plan=plan.replace('grapheme fixtures use Intl.Segmenter.','run-20 grapheme fixtures use hashed Unicode 16 tables; word movement remains runtime-based.')
plan=plan.replace('constituent islands plus accepted session portions','constituent islands plus accepted session portions')
plan=plan.replace('For a text edit, use the insertion/deletion equations in §2.5.','During composition, first apply the committed-authority/provisional-origin rules in section 4.12.1; do not repeatedly map clamped visible positions. For a committed text edit, use the insertion/deletion equations in §2.5.')
plan=plan.replace('Annotations differ: their ranges/properties belong to the document and exact history restoration includes them.','Annotations differ: their committed ranges/properties belong to Dc, their visible projection is derived as specified in section 4.12.1, and exact history restoration includes them.')
plan=plan.replace('validation/codex/run-20261015-19/document-model.mjs','validation/codex/run-20261016-20/document-model.mjs')
plan=plan.replace('No G0-G8 gate is advanced.','No G0-G8 production gate is advanced.')
plan=plan.replace('## 5. Formatting and boundary-attribute rules',(run/'v5-integration-contract.md').read_text(encoding='utf8')+'\n\n## 5. Formatting and boundary-attribute rules',1)
plan=plan.replace('Character intervals alone do not establish safe DOM writes.','Character intervals alone do not establish safe DOM writes. Protect the entire composing Text leaf and its affected wrapper chain, including outside the marked substring, as specified in section 4.12.6.')
plan=plan.replace('Native checkpoints: write new framed/checksummed file, flush as required by the target filesystem, atomic replace, then acknowledge. Journal records have transaction IDs, previous revision, length, checksum and committed framing; duplicate replay is idempotent. Web uses IndexedDB transactions. A saved acknowledgement is only for durable completion; autosave may coalesce queued states while preserving ordered acknowledgement semantics.','Native checkpoints, sequential journal framing and Web transaction adapters follow section 7.1. DurableSequence is separate from DocumentRevision; same-revision records can change composition acceptance. A saved acknowledgement is only for qualified durable completion, not visible publication.')
a=plan.index('Active-composition recovery stores latest accepted raw text/attributes');b=plan.index('\n\n## 8.',a)
plan=plan[:a]+'''Active-composition recovery preserves Dc and the complete Dv recovery draft under section 7.1. Restore committed content first; applying a retained draft is an explicit transaction with one new undoable group. General previous-process Undo/native sessions/pending commands are not restored. Routine diagnostics contain counters, versions and categories; raw text logging remains local and opt-in.

'''+(run/'v5-recovery-contract.md').read_text(encoding='utf8')+plan[b:]
a=plan.index('### 9.5 Revision 4.15.0');b=plan.index('### 9.6 Required experiments',a)
plan=plan[:a]+'''### 9.5 v5 independent review and executed evidence

The original Gemini archive is preserved unchanged under reviews/gemini-v5-readiness, with its input hashes. Codex reran its supplied interaction and storage probes, inspected the proposed model/benchmarks, checked primary API documentation and added independent counterexamples. Of its 25 listed interaction checks, 22 are accepted as genuine regressions. F07B uses an invalid baseline constructor/API and a different repaired fixture, so is not a valid reproduction. F10A_1 incorrectly rejects a legal AWT boundary alias; a real JDK 23 TextLayout probe confirms the alias. F10A_2 is adopted as a defensive null-hit policy, not a mandated JDK behavior or correctness proof.

Further review found defects in the proposed fixes: cancellation resurrected removed handles and reversed relocation; new candidate-bound anchors drifted; the journal skipped a same-DocumentRevision terminal record; and the storage prototype resolved a detached token through an old flow root. These observations are archived in run-20 and addressed by the v5 contracts and regressions. The review's CRC32C, full Skia-engine performance, live IME and mathematical-completeness claims exceed its actual code/evidence and are not adopted.

Run-20 now executes the accepted regressions, independent repair tests, official Unicode 16 grapheme data, measured-hierarchy equations, durable-record/recovery fault modeling and all retained run-19 verification groups. The new grapheme implementation was necessary because the installed Node runtime has Unicode 17 and failed one pinned Unicode 16 fixture. Current counts, exact commands, source/data hashes and evidence limits appear in the accompanying validation report. The previous baseline and all original reviewer artifacts remain unchanged.

'''+plan[b:]
plan=plan.replace('Run-19 provides executable reference coverage;', 'Run-20 provides executable reference coverage;')
plan=plan.replace('G7 and existing U1-U7: physical durability, full Unicode data, native IMEs, screen readers and all platform release gates remain unqualified.','G7 and existing U1-U7: physical durability, production Unicode word/bidi/table ports, native IMEs, screen readers and platform release gates remain unqualified. Passing the Unicode 16 grapheme corpus does not qualify these separate properties.')
freeze='''### 9.7 Freeze decision and change control

**Specification v5.0.0 is frozen as a reviewed semantic contract and conditional architecture baseline.** This freezes the selected ownership, schema, admission/settlement, command/anchor/cell, formatting, history, storage-measure and recovery contracts. It is not a proof of complete correctness, a validated production implementation or a guarantee that each native backend is feasible. The exact frozen artifacts and evidence are identified by v5-freeze-manifest.json.

No unresolved contradiction has a passing-status exception. Known experimental dependencies are explicit gates, not facts assumed solved: real input provenance/cell/gap hosting, DOM/AT behavior, production persistent indices and memory, filesystem durability, import/export pipelines and full-pipeline performance. G0 host feasibility runs before broad production construction when that work is separately authorized. A failed bridge/backend/storage experiment that cannot preserve these contracts is an architecture reopen trigger, not permission to cut required functionality.

Reopen only for a reproducible counterexample, primary-source incompatibility, measured failure of an acceptance gate, or an explicit product requirement change. Record the smallest trace, expected versus observed behavior, affected invariant, selected correction and discriminating regression. Use v5.0.x for compatible clarifications/repairs; version incompatible schema/protocol/product changes explicitly. Preserve this freeze and its evidence. Generic requests to re-audit without new evidence do not force another specification revision.

Release remains blocked on applicable G0-G8 gates. Freeze does not authorize production implementation. The specification is sufficient to direct the next qualified experiments and later implementation without asking coding agents to invent these core contracts.
'''
plan+='\n\n'+freeze
assert len(re.findall(r'^## [1-9]\. ',plan,re.M))==9
assert 'Empty resulting candidates affect typing intent only' not in plan
assert plan.count('### 7.1 ')==1 and plan.count('### 4.12 ')==1
(root/'plan-v5.md').write_text(plan,encoding='utf8')
d={x['name']:x['detail'] for x in r['results']}
report=f'''# v5 validation and independent disposition — 16 October 2026

**Verdict: freeze v5.0.0 as the reviewed specification/conditional architecture baseline. All {len(r['results'])} executable verification groups pass. No production implementation or production gate advancement.**

This verdict does not assert perfection or platform feasibility. The freeze locks chosen semantics; failed qualification has explicit reopen conditions. The full standalone artifact is editor-v5-standalone.md and the normative source is plan-v5.md. v4.15.0 remains historical, unchanged.

## Independent assessment of Gemini

I reproduced the supplied 25 baseline disagreements and ran its proposed repairs, inherited tests and scripted 60-trace exercise. I then examined what the assertions and prototypes actually establish. Its "any exception means reproduced" approach counted an invalid baseline fixture as a defect; passing the aggregate alone was insufficient.

- **Accepted:** 22 interaction counterexamples covering composition metadata path dependence, ledger fall-through, retarget seeds, deleted-object heads, legal derived carets, surrounding deletion, explicit-target/cell separation, expected queued refusal, nested quote Enter, user caret snapping, isolated-surrogate policy, rich-paste preservation/sanitization, empty-block copy and aggregate transient retention.
- **Rejected as a baseline defect:** F07B constructs IntegratedModel with a string instead of its required tree and uses another API, while changing the repaired fixture. Existing tagged-atom formatting isolation already passed. The same valid tagged fixture is now checked independently.
- **Rejected:** F10A_1 prohibits TextHitInfo.trailing(-1), which is the valid beforeOffset(0) alias. JDK 23 TextLayout accepted it in an executed headless Java probe. Primary references: [TextHitInfo](https://docs.oracle.com/en/java/javase/24/docs/api/java.desktop/java/awt/font/TextHitInfo.html) and [InputMethodRequests](https://docs.oracle.com/en/java/javase/23/docs/api/java.desktop/java/awt/im/InputMethodRequests.html).
- **Reclassified:** F10A_2 becomes a defensive null-hit fallback during composition, not a mandatory JDK rule.
- **Accepted with correction:** the two-component hierarchy measure and current-version membership requirement. Its prototype resolves a removed leaf to rank 0 through an obsolete FlowRoot, so its timing cannot qualify the full index. Current-root membership and old-version independence are now explicit.
- **Accepted with different recovery policy:** preserve Dc and Dv, but restore committed content first with a retained recoverable draft. Explicit draft application is one undoable owner transaction. Unresolved drafts survive later saves/checkpoints; same-document-revision records use a separate durable sequence.
- **Not accepted as proof:** its journal uses SHA-256/in-memory Buffers, not CRC32C or actual fsync. Its C++ uses SimulatedRun rather than the full Skia pipeline. Its Chromium script manipulates ordinary DOM Selection without an OS IME. These are scoped probes and hypotheses, not physical/native/end-to-end qualification.
- **Rejected fallback requirements:** mandatory custom Skia patches, a universal M<=256/1ms guarantee, display-style coalescing that changes semantics, blanket callback rejection after epoch increment, and an unqualified three-screen accessibility window. Backend or provenance failures block qualification/reopen the decision; they do not silently weaken requirements.

The unchanged review files and the full findings remain in reviews/gemini-v5-readiness/. The separate input manifest proves which bytes were inspected. No reviewer claim was adopted solely on authority.

## Additional defects in the proposed repairs

Executed independent traces found: unregister undone by composition cancel; explicit anchor relocation reset on cancel; a newly created candidate anchor drifting on an identical preedit; same-DocumentRevision terminal commit ignored during recovery; and deleted index tokens still resolving through old roots. The integrated repair keeps committed and explicit visible-origin bindings separate, respects handle generations/lifetimes, advances metadata on actual Dc changes, validates current-root membership and orders persistence by DurableSequence. An additional direction regression checks code-point surrounding deletion preserves reversed selection.

The installed Node {r['environment']['node']} runtime reports Unicode {d['unicodeCorpus']['runtimeUnicode']} / ICU {d['unicodeCorpus']['icu']}. Its Intl grapheme result disagreed with Unicode 16 at the scissors-ZWJ fixture. We added a hashed-table Unicode 16 reference, rather than changing expected test output or silently changing the pinned version.

## Current executed results

Environment: {r['environment']}; execution timestamp {r['timestamp']} (UTC). Node verification duration {r['elapsedMs']/1000:.3f} seconds is test duration, not editor latency.

| Evidence | Result and scope |
|---|---|
| Accepted Gemini regressions | {d['acceptedGeminiRegressions']['cases']} pass through the integrated v5 DocumentModel. |
| Independent repair checks | {d['independentV5Regressions']['cases']} pass, including handle lifetimes, native object deletion, exact native selection, AWT aliases, surrounding-delete direction and disposal. |
| Official Unicode 16 grapheme corpus | {d['unicodeCorpus']['cases']} pass against pinned property tables. Kotlin, word boundaries, bidi and shaping remain unqualified. |
| Recovery state/byte protocol | {d['recoveryTests']['cases']} scenarios; {d['recoveryTests']['tornByteCuts']} distinct byte cuts. No physical filesystem claim. |
| Hierarchy measure oracle | {d['measuredHierarchyTests']['generatedTrees']} generated trees; {d['measuredHierarchyTests']['rankChecks']} rank checks; deleted boundary/detached membership checks. No optimized-index claim. |
| Retained document coverage | {len(d['documentCases']['cases'])} named regressions, {d['inheritedDocumentCases']['cases']} inherited cases through DocumentModel, {len(d['commandCoverage']['rows'])} command families. |
| Retained matrices | {d['anchorMatrix']['cases']} anchor cases and {d['cellCompositionMatrix']['cases']} cell-composition cases. |
| Retained behavioral mutations | {d['documentMutations']['killed']+d['repairMutations']['killed']+d['integratedMutations']['killed']} caught with passing controls. |
| Other retained base suites | Repair, structural-chain, synthetic AWT, retention/soak, reentry, generated integration, ledger, repeated-session and scheduling groups all pass. |
| JDK 23 boundary probe | Executed javac/java headlessly; valid boundary aliases accepted. Not a native IME/window test. |
| Original storage/recovery probe | Rerun and archived separately; its own checks pass, but additional detached-membership and same-revision-recovery tests fail. Not counted as qualification. |

Reproduce the current model with `node validation/codex/run-20261016-20/verify.mjs`. Detailed outputs, exact source hashes and failures discovered during repair are in that directory. The tests overlap contracts and must not be added together as independent proofs. Full copies, bounded scenarios and a corpus are not a complete formal proof.

## Freeze and remaining work

The review has selected a concrete correction for every accepted design finding. No known failing counterexample remains in the frozen reference surface. We can honestly freeze this specification; we cannot honestly claim a production-ready editor or that only platform testing remains.

Remaining gates cover actual Kotlin persistent indices and allocations, broad independent differential traces, physical storage/power-loss, HTML and system clipboard/resource adapters, production Unicode word/bidi/table ports, native IME/DOM/AT feasibility and full-pipeline performance. Real-host failures may require a bridge/backend revision under change control. G0-G8 are not marked passed. The source/evidence-backed plan and reopen criteria are frozen, not empirical facts about unbuilt software.
'''
(root/'v5-validation-report.md').write_text(report,encoding='utf8')
rows=[['Area','Decision','Evidence','Remaining gate','Reopen condition']]
for area,decision,evidence,gate,reopen in [
 ('Composition/anchors/history','FROZEN CONTRACT','22 accepted + independent regressions; retained suites','G2/G3 implementation and broad differential traces','Concrete trace violates a frozen invariant'),
 ('Unicode graphemes','FROZEN Unicode 16 policy','1093 official fixtures; hashed reference tables','G1 Kotlin tables; word/bidi and platform integration','Official pinned-corpus or differential failure'),
 ('Hierarchy/index','FROZEN measure/membership contract','100 oracle trees and 4147 ranks; reviewed prototype is limited','G1/G8 persistent implementation, depth counters, memory','Current-version locator failure or unmet measured bounds'),
 ('Durability/recovery','FROZEN protocol and committed-first policy','17 simulated scenarios; 1145 tear cuts','G7 physical write/flush/replace/IndexedDB qualification','Acknowledgement loss, chain violation, lost recovery draft'),
 ('Native input','CONDITIONAL BACKEND','Headless state traces; JDK hit alias; primary API review','G0/G5 real Android/iOS/Desktop provenance and cell/gap hosting','No bridge can implement exact semantics without loss'),
 ('Web/AT','CONDITIONAL BACKEND','Protected-node contract; reviewer synthetic DOM evidence only','G0/G5/G6 actual IME, DOM and accessibility','Node preservation/provenance/AT requirements not implementable'),
 ('Import/resources','FROZEN normalized-fragment contract','Rich fragment regression tests','G6 HTML, clipboard, codecs, resource I/O and round trips','Conversion loses required structure/attribution or violates budgets'),
 ('Layout/performance','UNQUALIFIED RELEASE GATE','Historical full-layout evidence; synthetic shaper hypothesis','G4/G8 full pipeline and device budgets','No qualified backend meets fixed product envelope'),
 ('Physical memory','UNQUALIFIED RELEASE GATE','Bounded ownership/serialized charges only','G1/G8 actual runtime heap/GC and reclamation','Owners retain evicted roots or exceed measured budget')]:rows.append([area,decision,evidence,gate,reopen])
for i in range(9):rows.append([f'G{i}','OPEN / NOT PRODUCTION QUALIFIED','See specification section 8',f'G{i}','Failure requires evidence, correction and regression'])
buf=io.StringIO();csv.writer(buf).writerows(rows);(root/'v5-qualification-matrix.csv').write_text(buf.getvalue(),encoding='utf8')
(root/'v5-freeze-decision.md').write_text('# v5.0.0 freeze decision\n\n'+freeze+'\n\nSee v5-validation-report.md for independent acceptance/rejection of Gemini findings and v5-qualification-matrix.csv for open gates. No production implementation was performed.\n',encoding='utf8')
table='\n'.join('| '+' | '.join(str(v).replace('|','/') for v in row)+' |' for row in [rows[0],['---']*5,*rows[1:]])
package='''# Compose Multiplatform editor — standalone frozen specification v5.0.0

16 October 2026. This document contains the COMPLETE normative specification, independent review disposition, executable validation summary, qualification matrix and freeze policy. It needs no prior chat or earlier plan for design review. Reproducing evidence requires the repository sources; they are not embedded here.

**Freeze meaning:** selected semantic contracts and conditional architecture are fixed under versioned change control. No claim of perfection, production readiness or proven native feasibility is made. No production implementation is authorized. plan-v5.md is the normative source; older plans/reviewer proposals are historical evidence.

# Complete normative specification

'''+plan+'\n\n# Independent review and current evidence\n\n'+report+'\n\n# Qualification and reopen matrix\n\n'+table+'\n\n# Reproduction\n\nRun `node validation/codex/run-20261016-20/verify.mjs` from the repository root. It writes a new run result; preserve the committed frozen result before comparison. The publisher refuses failed or source-mismatched evidence and checks the v4.15.0 baseline and reviewer inputs are unchanged. The JDK probe and source/data manifests are documented in the run README.\n\n`v5-freeze-manifest.json` records SHA-256 hashes for this standalone file, normative plan, reports, reference sources, pinned Unicode data and evidence. A matching hash identifies this reviewed freeze, not a correctness proof.\n'
(root/'editor-v5-standalone.md').write_text(package,encoding='utf8')
readme=(root/'README.md').read_text(encoding='utf-8-sig');readme=readme.replace('Intl segmentation and serialized retention charges','a pinned Unicode 16 grapheme reference (runtime word segmentation) and serialized retention charges').replace('Current specification: 4.15.0','Current specification: v5.0.0 (frozen contracts; open qualification gates)').replace('[Standalone specification 4.15.0](editor-4.15.0-standalone-review.md)','[Standalone frozen specification v5](editor-v5-standalone.md)').replace('[Normative plan](plan-v4.md): revision 4.15.0 despite its historical filename.','[Normative plan](plan-v5.md), [freeze decision](v5-freeze-decision.md) and [integrity manifest](v5-freeze-manifest.json).').replace('[Current validation report](antigravity-validation-report.md) and [qualification matrix](antigravity-validation-matrix.csv).','[Current validation report](v5-validation-report.md) and [qualification matrix](v5-qualification-matrix.csv).').replace('run-20261015-19','run-20261016-20').replace('The current suite has 20 verification groups','The current suite has 25 verification groups').replace('and Intl segmentation','and a pinned Unicode 16 grapheme reference (word movement still uses Intl)').replace('The `antigravity-*` filenames are historical names. The current root report records the latest Codex run, not a newly executed Antigravity audit.','The `antigravity-*` root files and plan-v4.md preserve the v4.15.0 baseline. The original Gemini review is archived under reviews/gemini-v5-readiness; its proposed fixes are not the normative v5 plan.').replace('review and validation only, preparing amendments for a future v5 freeze.','historical instructions used for the second review. The completed independent disposition is in v5-validation-report.md.')
(root/'README.md').write_text(readme,encoding='utf8')
(run/'README.md').write_text('''# Run 20: independently reviewed v5 reference

Specification/validation only, not production. The original reviewer archive and run-19 remain unchanged. The new DocumentModel adapts reviewed repairs, then corrects handle lifetimes/provisional origins and integrates recovery, Unicode and current-version measure contracts. document-model-base.mjs is the explicit copied prior layer, not another state owner.

Run from repository root:

```powershell
node validation/codex/run-20261016-20/verify.mjs
javac -d validation/codex/run-20261016-20/classes validation/codex/run-20261016-20/AwtBoundaryProbe.java
java '-Djava.awt.headless=true' -cp validation/codex/run-20261016-20/classes AwtBoundaryProbe
```

Node 24; Java probe used JDK 23.0.2. No npm dependencies for the current suite. It writes results.json; copy the frozen result before rerunning if comparing to v5-freeze-manifest.json. Do not regenerate the frozen documents casually. publish-v5.py validates sources/results and creates the standalone/spec/report/manifest; source changes require a reviewed version increment, not silent republishing.

Important files: test-v5.mjs (accepted reviewer and independent tests); unicode-grapheme.mjs with hashed Unicode data; test-measure.mjs (equations, not optimized index); recovery-model.mjs and test-recovery.mjs (framing, draft retention and simulated durable boundaries); gemini-reproduction.json, gemini-repair-counterexamples.json and gemini-storage-audit.json (independent assessment); AwtBoundaryProbe.java/output (JDK hit aliases, not native IME). failure-*.json are archived intermediate failures.

Original Gemini F07B is invalid baseline usage; F10A_1 is an invalid proposed restriction; F10A_2 is a compatibility choice. 22 actual reviewer regressions enter the current suite. Source/data hashes and scope labels are authoritative. The C++/Chromium reviewer results remain historical, not locally rerun full-pipeline or live IME evidence. No G0-G8 production gate advances.
''',encoding='utf8')
files=[root/n for n in ['plan-v5.md','editor-v5-standalone.md','v5-validation-report.md','v5-qualification-matrix.csv','v5-freeze-decision.md']]
files += [p for p in run.rglob('*') if p.is_file() and 'classes' not in p.parts and 'sources' not in p.parts and p.suffix not in ['.class']]
manifest={'version':'5.0.0','status':'FROZEN_SPECIFICATION_CONDITIONAL_ARCHITECTURE','date':'2026-10-16','baselineCommit':'b66b790068908e45dd8642d3410bc53f609c700f','productionAuthorized':False,'hashes':{str(p.relative_to(root)).replace('\\','/'):sha(p) for p in sorted(set(files))}}
(root/'v5-freeze-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
assert package.count(plan)==1 and package.count('```')%2==0
print(json.dumps({'version':'5.0.0','groups':len(r['results']),'standaloneWords':len(package.split()),'frozenFiles':len(manifest['hashes'])}))
