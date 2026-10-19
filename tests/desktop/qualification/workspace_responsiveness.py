
from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verification import ROOT,validate_build
p=argparse.ArgumentParser();p.add_argument('--build-dir',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args()
b=Path(a.build_dir).resolve();o=Path(a.output_dir).resolve();o.mkdir(parents=True,exist_ok=False);validate_build(b)
src=Path(__file__).with_name('WorkspaceIoResponsiveness.java');cp=str(b/'classes')+';'+str(b/'lib'/'*');classes=o/'classes';classes.mkdir()
for name,cmd in [('compile',['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(src)]),('run',['java','-cp',str(classes)+';'+cp,'WorkspaceIoResponsiveness',str(o/'owned')])]:
 r=subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=50,encoding='utf-8');(o/(name+'.log')).write_text(r.stdout,encoding='utf-8');assert r.returncode==0,r.stdout
 if name=='run':result=json.loads(next(l for l in r.stdout.splitlines() if l.startswith('{')))
assert result['status']=='PASS';result.update(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),buildManifestSha256=hashlib.sha256((b/'build-identity.json').read_bytes()).hexdigest(),scope='Bounded worker/EDT heartbeat diagnostic, not broad latency or platform qualification')
(o/'results.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))
