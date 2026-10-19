"""Standalone same-build selection coverage regression; no input injection or dependency adoption."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from pathlib import Path
import argparse,hashlib,json,os,subprocess,sys
ROOT = _REPO_ROOT;sys.path.insert(0,str(ROOT/'desktop-prototype'))
from verification import validate_build
p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args();b=validate_build(a.build_dir);out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
source=Path(__file__).with_name('SelectionCoverageWitness.java');cp=str(b/'classes')+os.pathsep+str(b/'lib/*');report={'status':'RUNNING','build':str(b),'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'steps':[]}
try:
 for label,command,expected in [('compile',['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(source)],''),('coverage',['java','-cp',str(out)+os.pathsep+cp,'SelectionCoverageWitness',str(out)],'PASS 6 view/zoom rows, 216 selected/unselected cell memberships, 24 actual pointer-handler associations')]:
  r=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',timeout=120);(out/(label+'.log')).write_text(r.stdout,encoding='utf-8');report['steps'].append({'name':label,'command':command,'exitCode':r.returncode});assert r.returncode==0 and expected in r.stdout,(label,r.stdout)
 report['status']='PASS';report['scope']='Independent logical membership, backend glyph boxes locate cell centers, materialized public Skia path conversion; actual registered pointer handlers. Mounted host, not OS input/IME qualification.';validate_build(b)
except Exception as e:report.update(status='FAIL',error=str(e));print(str(e),file=sys.stderr)
(out/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(report['status']);sys.exit(0 if report['status']=='PASS' else 1)
