
from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verification import validate_build
p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--records',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--mode',choices=['unfinished','recent'],default='unfinished');p.add_argument('--historical-manifest');a=p.parse_args()
b=a.build_dir.resolve();o=a.output_dir.resolve();o.mkdir(parents=True,exist_ok=False);classes=o/'classes';classes.mkdir();source=Path(__file__).with_name('UnfinishedSelectionWitness.java');cp=str(b/'classes')+';'+str(b/'lib'/'*')
if a.historical_manifest:
 assert hashlib.sha256((b/'build-identity.json').read_bytes()).hexdigest()==a.historical_manifest
 for name,identity in json.loads((b/'build-identity.json').read_text())['artifacts'].items():assert hashlib.sha256((b/name).read_bytes()).hexdigest()==identity['sha256'],name
else:validate_build(b)
for stage,cmd in [('compile',['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)]),('run',['java','-cp',str(classes)+';'+cp,'UnfinishedSelectionWitness',str(a.records.resolve()),str(o/'owned')]+(['recent'] if a.mode=='recent' else []))]:
 r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=45);(o/(stage+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8')
 if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
 if 'Exception in thread' in r.stdout+r.stderr or 'FIXTURE_ERROR' in r.stdout+r.stderr:raise RuntimeError('Uncaught child failure; inspect preserved logs')
result=json.loads((o/'owned/results.json').read_text());result.update(buildManifestSha256=hashlib.sha256((b/'build-identity.json').read_bytes()).hexdigest(),sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest());(o/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
