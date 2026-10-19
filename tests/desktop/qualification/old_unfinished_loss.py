
from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse,hashlib,json,queue,subprocess,threading,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--old-build',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args()
b=Path(a.old_build).resolve();o=Path(a.output_dir).resolve();o.mkdir(parents=True,exist_ok=False);m=json.loads((b/'build-identity.json').read_text())
for path,identity in m['artifacts'].items():assert hashlib.sha256((b/path).read_bytes()).hexdigest()==identity['sha256'],path
src=Path(__file__).with_name('OldUnfinishedLossChild.java');cp=str(b/'classes')+';'+str(b/'lib'/'*');classes=o/'classes';classes.mkdir()
c=subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(src)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',timeout=30);(o/'compile.log').write_text(c.stdout);assert c.returncode==0,c.stdout
child=subprocess.Popen(['java','-cp',str(classes)+';'+cp,'OldUnfinishedLossChild',str(o)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8');events=queue.Queue();lines=[]
def read():
 for line in child.stdout:lines.append(line);events.put(line)
 events.put(None)
t=threading.Thread(target=read,daemon=True);t.start();ready=None;deadline=time.monotonic()+30
try:
 while time.monotonic()<deadline:
  try:line=events.get(timeout=1)
  except queue.Empty:continue
  if line is None:break
  if line.startswith('READY '):ready=line.strip().split();break
 assert ready,''.join(lines);assert int(ready[1])==child.pid
 child.kill();code=child.wait(timeout=10);assert code!=0
finally:
 if child.poll() is None:child.kill();child.wait(timeout=10)
 t.join(2);(o/'killed-child.log').write_text(''.join(lines),encoding='utf-8')
session=ready[2];restored=o/'restored-v7.astra';r=subprocess.run(['java','-cp',cp,'editor.MainKt','--local-recovery-child','--scan-copy','--copy-session='+session,'--recovery-dir='+str(o/'recovery'),'--copy-result='+str(restored)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',timeout=30)
(o/'reader.log').write_text(r.stdout,encoding='utf-8');assert r.returncode==0,r.stdout
assert restored.read_bytes()==(o/'expected-committed-v7.astra').read_bytes();assert restored.read_bytes()!=(o/'expected-visible-v7.astra').read_bytes()
assert not (o/'recovery'/'unfinished-records').exists()
result=dict(status='REPRODUCED_OLD_UNFINISHED_LOSS',ownedPid=child.pid,killedExit=code,session=session,oldBuildManifestSha256=hashlib.sha256((b/'build-identity.json').read_bytes()).hexdigest(),helperSha256=hashlib.sha256(src.read_bytes()).hexdigest(),scope='Actual old desktop owner/controller with synthetic preedit, actual owned kill, fresh JVM restores only committed content; no native IME or power-loss claim')
(o/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
