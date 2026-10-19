"""One-time, non-destructive documentation relocation with checked workspace paths."""
from pathlib import Path
import hashlib,json,shutil

ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def safe(name):
    path=(ROOT/name).resolve()
    assert path.is_relative_to(ROOT),name
    return path

def main():
    mapfile=ROOT/'docs/relocations.json'
    if mapfile.exists():raise SystemExit('Already organized; use the recorded relocation map.')
    protected={}
    for name in ['v5-freeze-manifest.json','v5.1-spec-manifest.json']:
        protected.update(json.loads(safe(name).read_text())['hashes'])
    for name,h in protected.items():assert sha(safe(name))==h,name
    retained=set(protected)|{'README.md','AGENTS.md','v5-freeze-manifest.json','v5.1-spec-manifest.json'}
    mapping={}
    product={'PROJECT-JOURNAL.md','PRODUCT-ROADMAP.md','FEATURE-CATALOG.md','URDU-ACCEPTANCE.md'}
    v6={'editor-v6-standalone.md','plan-v6.md','v6-plan-manifest.json'}
    for p in ROOT.iterdir():
        if not p.is_file() or p.name in retained or p.suffix not in ['.md','.txt','.json','.csv']:continue
        n=p.name
        if n in product:target='docs/project/'+n
        elif n in v6:target='docs/plans/v6/'+n
        elif n=='argon-v6-review-prompt.md':target='docs/prompts/'+n
        elif 'v5.2' in n:target='docs/plans/v5.2/'+n
        elif n.startswith('antigravity-'):target='docs/archive/antigravity/'+n
        elif n.startswith('editor-4.') or n=='plan-v4.md':target='docs/archive/v4/'+n
        elif n.startswith('plan-v'):target='docs/archive/early-plans/'+n
        else:target='docs/archive/research/'+n
        mapping[n]=target
    for p in (ROOT/'reports').glob('*'):
        if p.is_file():mapping[p.relative_to(ROOT).as_posix()]='docs/reports/'+p.name
    for n in ['WRITING.md','RECOVERY.md','ACCESSIBILITY.md']:
        mapping['desktop-prototype/'+n]='docs/guides/'+n
    mapping['planning/v6-core.md']='docs/plans/v6/source.md'
    # Preflight every target before any move. Never overwrite an existing file.
    for old,new in mapping.items():assert safe(old).is_file() and not safe(new).exists(),(old,new)
    assert len(set(mapping.values()))==len(mapping)
    snapshot=ROOT/'docs/archive/review-packages/v6-review1'
    snapshot.mkdir(parents=True,exist_ok=False)
    snapshot_records={}
    for name in ['editor-v6-standalone.md','v6-plan-manifest.json','argon-v6-review-prompt.md']:
        shutil.copy2(safe(name),snapshot/name);snapshot_records[name]=sha(snapshot/name)
    before={name:sha(safe(name)) for name in mapping}
    for old,new in mapping.items():
        dest=safe(new);dest.parent.mkdir(parents=True,exist_ok=True);shutil.move(str(safe(old)),str(dest));assert sha(dest)==before[old]
    data={'date':'2026-10-19','purpose':'Documentation organization only; old paths are repository-root relative aliases for generators and historical evidence','paths':mapping,'originalHashes':before,'retainedFrozenPaths':sorted(n for n in retained if '/' not in n and n not in ['README.md','AGENTS.md']),'argonUploadedSnapshot':{'directory':snapshot.relative_to(ROOT).as_posix(),'hashes':snapshot_records}}
    mapfile.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    for name,h in protected.items():assert sha(safe(name))==h,name
    print(f'Moved {len(mapping)} documents byte-for-byte; protected {len(protected)} frozen files; saved exact Argon review snapshot.')

if __name__=='__main__':main()
