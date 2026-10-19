"""Build a self-contained v6 review draft, retaining all frozen baseline files.

No production code or previous plan is generated/modified here.
"""
from pathlib import Path
import hashlib, json, re, subprocess
from doc_paths import resolve, canonical, relocate_links
from v6_contract_corrections import correct

ROOT=Path(__file__).resolve().parents[1]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(name): return resolve(name).read_text(encoding='utf-8')
def write(name,text): resolve(name).write_text(text,encoding='utf-8',newline='\n')

def protected():
    expected={}
    for name in ['v5-freeze-manifest.json','v5.1-spec-manifest.json']:
        expected.update(json.loads(read(name))['hashes'])
    for name,sha in expected.items():
        assert digest(resolve(name))==sha, 'Historical file changed: '+name
    # v5.2 stays unchanged too; mutable journal/report are not its artifacts.
    old=json.loads(read('v5.2-plan-manifest.json'))['hashes']
    for name in ['plan-v5.2.md','editor-v5.2-standalone.md','v5.2-integration-amendment.md']:
        assert digest(resolve(name))==old[name], 'v5.2 artifact changed: '+name
    return expected

def build():
    seal=ROOT/'docs/plans/v6/v6-freeze-record.json'
    if seal.exists():
        for name,expected in json.loads(seal.read_text())['hashes'].items():
            assert digest(ROOT/name)==expected,'Frozen v6 artifact changed; use a versioned amendment: '+name
        protected()
        print('PASS frozen v6 integrity; regeneration disabled after finalization. Create an explicit new version for changes.')
        return
    preserved=protected()
    review=ROOT/'reviews/v6-finalization-20261019'
    witnesses=json.loads((review/'results.json').read_text())
    inherited=json.loads((review/'inherited-results.json').read_text())
    assert witnesses['status']=='PASS' and len(witnesses['results'])==16 and all(r['status']=='PASS' for r in witnesses['results'])
    assert inherited['passed'] and len(inherited['results'])==25 and all(r['status']=='PASS' for r in inherited['results'])
    before=json.loads((review/'before-hashes.json').read_text())
    for name,expected in before.items():
        if name.startswith('desktop-prototype/src/'):
            assert digest(ROOT/name)==expected,'Production source changed during specification finalization: '+name
    ledger=json.loads(read('research/v6-reuse-20261019/ledger.json'))
    appendix=['# Annex U — upstream source inspection ledger','',
      'Collected 19 October 2026. Source inspection only: upstream tests and release binaries NOT RUN. Full source/license snapshots and SHA-256 records are retained locally in `research/v6-reuse-20261019/ledger.json`. No dependency or source code was adopted by this collection. A release label below is metadata, not proof that its contents equal the inspected branch.','',
      '| Project | Branch revision inspected | Release metadata | License evidence |',
      '|---|---|---|---|']
    for p in ledger['projects']:
        repo=p['repo'];sha=p.get('sha');release=p.get('latestReleaseMetadata',{}).get('tag_name','not retrieved')
        licenses=[f for f in p.get('files',[]) if 'sha256'in f and ('LICENSE' in f['path'] or f['path']=='COPYING')]
        license_note=p.get('licenseMetadata') or 'metadata unavailable'
        if licenses:
            license_text=(ROOT/licenses[0]['local']).read_text(encoding='utf-8')
            if 'Apache License' in license_text[:150]:license_note='Apache-2.0 text inspected'
            elif 'MIT License' in license_text[:150]:license_note='MIT text inspected'
        if licenses: license_note+='; [text inspected]('+licenses[0]['url']+')'
        if repo in ['yjs/yjs','y-crdt/y-crdt']:license_note='MIT text inspected; API metadata NOASSERTION'
        if not sha:
            appendix.append(f'| {repo} | Metadata unavailable (API rate limit); official docs only | not retrieved | not pinned in this review |')
        else:appendix.append(f'| [{repo}](https://github.com/{repo}) | `{sha}` | {release} | {license_note} |')
    appendix+=['','## Specific source entry points','',
      'These are inspection/adaptation entry points, not a list of approved production dependencies. License review must include copied files, embedded code, notices, transitive binaries and fonts, not just repository metadata.']
    for p in ledger['projects']:
        files=[f for f in p.get('files',[]) if 'sha256'in f and 'LICENSE' not in f['path'] and f['path'] not in ['README.md','COPYING','CHANGELOG.md']]
        if files:
            appendix+=['',f'### {p["repo"]}']
            appendix += [f'- [{f["path"]}]({f["url"]}) — SHA-256 `{f["sha256"]}`.' for f in files]
    appendix+=['','## Retrieval limitations','',
      'Some API requests reached an unauthenticated rate limit. Previously captured commit IDs were used for raw source retrieval for Automerge/PdfKmp/KmPDF/KitePDF, with errors retained in the machine ledger. TextKit license was retrieved as LICENSE.md after LICENSE returned 404. ICU, HarfBuzz and JetBrains runtime source heads were not freshly pinned through that API; official docs and the project’s actual dependency manifest provide the narrower evidence. No credentials were used or requested.','']
    baseline=correct(read('editor-v5.2-standalone.md'))
    # Drop its duplicate front matter but retain the entire detailed roadmap/contracts.
    baseline=baseline[baseline.index('# Product roadmap'):]
    core=read('planning/v6-core.md')
    core=core.replace('## Primary reference notes',read('docs/plans/v6/final-contract-decisions.md')+'\n\n## Primary reference notes')
    appendix+=['','## Finalization supplement — 19 October 2026','',
      'The GitHub ProseMirror view repository is archived; current forge: https://code.haverbeke.berlin/prosemirror/prosemirror-view/ (direct fetch returned 403). npm prosemirror-view 1.42.6 metadata/source tarball were retrieved and SHA-512 integrity checked in reviews/v6-finalization-20261019/upstream/pm-artifact.json; source inspected, not executed/adopted. The old SHA above is historical. Pinned source version catalogs confirm Kotlin 2.4.10 for Compose Rich Editor, 2.3.21 for Cascade and 2.4.21 for yuroyami/KitePDF; exact artifact compatibility remains a build gate, not an inferred universal ABI failure. KitePDF NOTICE was retrieved. Release lookups for Automerge/KitePDF were rate-limited, so reviewer-supplied release labels are not promoted to verified data. The exact PdfKmp and yuroyami/KitePDF license files are Apache-2.0; similarly named repositories cited by the second review are different projects. Evidence and URLs are preserved in reviews/v6-finalization-20261019/upstream/ledger.json.','']
    document=core+'\n\n---\n\n'+'\n'.join(appendix)+'\n\n---\n\n'+'''# Annex B — complete inherited product and semantic contracts, corrected for v6

This annex incorporates the detailed v5.2 roadmap, F01–F46 catalogue, Urdu acceptance and library specification, with the explicit D01–D03 contract corrections applied by planning/v6_contract_corrections.py. Frozen v5/v5.1 and preserved v5.2 originals remain unchanged. This is a target contract with dated evidence, not a fresh implementation report. Section references within this annex refer to its own library sections. The final v6 layer and D01–D10 govern current policy and supersede older phase order/status. Open S0/C0/GP0 implementation gates still block their dependent API/schema adoption. Historical release assertions are not automatically current.

'''+baseline
    document=relocate_links(document,'editor-v6-standalone.md',canonical('editor-v6-standalone.md'))
    write('editor-v6-standalone.md',document)
    # Identical review content prevents divergence between a short plan and full package.
    write('plan-v6.md',document)
    feature_ids=set(re.findall(r'\| (F\d{2}) \|',document))
    assert feature_ids=={f'F{i:02}' for i in range(1,47)},feature_ids
    for i in range(1,18): assert f'I{i:02}' in document
    for term in ['partialCommit','CompositionAlreadyReverted','AfterTicket','emptyBlockAttributes','V6-I10','ProseMirror','Yrs','FROZEN WITH EXPLICIT OPEN','Power-loss','strict-interior','RecoveryCaptureFailed','markerTypography']:
        assert term.lower() in document.lower(),term
    assert document.count('```')%2==0,'Unbalanced fences'
    # Every cached source used in the ledger still matches the inspected bytes.
    cached=0
    for p in ledger['projects']:
        for f in p.get('files',[]):
            if 'local'in f:
                assert digest(ROOT/f['local'])==f['sha256'],f['local'];cached+=1
    protected()
    outputs=['editor-v6-standalone.md','plan-v6.md','argon-v6-review-prompt.md','planning/v6-core.md','planning/build_v6_docs.py','planning/doc_paths.py','planning/v6_contract_corrections.py','planning/research_v6.py','research/v6-reuse-20261019/ledger.json','docs/relocations.json','docs/plans/v6/final-contract-decisions.md','docs/reports/v6-final-review-disposition-20261019.md','docs/prompts/v6-next-session.md','reviews/v6-finalization-20261019/verify.mjs','reviews/v6-finalization-20261019/results.json','reviews/v6-finalization-20261019/inherited-results.json','reviews/v6-finalization-20261019/upstream/ledger.json','reviews/v6-finalization-20261019/upstream/pm-artifact.json']
    sources=['editor-v5.2-standalone.md','PROJECT-JOURNAL.md','PRODUCT-ROADMAP.md','FEATURE-CATALOG.md','URDU-ACCEPTANCE.md','reports/visual-multilingual-repairs-20261019.md','desktop-prototype/src/main/kotlin/editor/VisualRepairChecks.kt','desktop-prototype/pom.xml']
    manifest={'version':'6.0.0','asOf':'2026-10-19','status':'FINAL_SPECIFICATION_FROZEN_CONDITIONAL_QUALIFICATION','standalone':canonical('editor-v6-standalone.md'),'reviewPrompt':canonical('argon-v6-review-prompt.md'),'implementationChangedByThisBuild':False,'published':False,'productionReadiness':False,'upstreamExecution':'Yjs 13.6.33 bounded witnesses executed; other candidates/source inspections only','nextStep':'Use docs/prompts/v6-next-session.md for bounded desktop qualification/marker repairs; no production changes made in finalization','reviewDisposition':'docs/reports/v6-final-review-disposition-20261019.md','reviewedInputSha256':'7013568c911bb7a0f8cf640e3015c04fca4199be8f83005f7072a619a8adbf89','productionSourceChangedByFinalization':False,'finalizationAuthorization':'Owner request to assess both Argon reviews, fix genuine specification gaps, validate and finalize v6','previousFrozenManifests':['v5-freeze-manifest.json','v5.1-spec-manifest.json'],'baselineGitCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'hashes':{canonical(n):digest(resolve(n)) for n in outputs},'sourceSnapshotHashes':{canonical(n):digest(resolve(n)) for n in sources},'checks':{'protectedHistoricalFiles':len(preserved),'unchangedV52Artifacts':3,'featureIds':sorted(feature_ids),'invariantIds':'I01-I17 plus embedded extensions and V6-I01-I10','cachedSourceFiles':cached,'balancedCodeFences':True,'planEqualsStandalone':digest(resolve('plan-v6.md'))==digest(resolve('editor-v6-standalone.md'))},'gates':{k:'OPEN_OR_PARTIAL_SEE_STANDALONE' for k in ['Q0','RU0','S0','C0','GP0','K0','N0','D0','P0','A0','C1','R0','L0']}}
    write('v6-plan-manifest.json',json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({'status':'PASS_DOCUMENT_INTEGRITY_ONLY','bytes':len(document.encode()),'lines':len(document.splitlines()),'words':len(document.split()),**manifest['checks']},indent=2))

if __name__=='__main__':build()
