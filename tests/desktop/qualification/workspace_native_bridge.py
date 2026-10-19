"""Owned native fixture transport. UI actions are exclusively through supported Computer Use."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse,base64,hashlib,json,queue,subprocess,sys,threading,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verification import ROOT,validate_build
p=argparse.ArgumentParser();p.add_argument('--build-dir',required=True);p.add_argument('--output-dir',required=True);p.add_argument('--resume-existing',action='store_true');p.add_argument('--no-startup-prompts',action='store_true');p.add_argument('--observe-dialogs',action='store_true');p.add_argument('--pages-fixture',action='store_true');a=p.parse_args()
build=Path(a.build_dir).resolve();out=Path(a.output_dir).resolve()
if a.resume_existing:
    assert (out/'protocol.jsonl').is_file(), 'Resume requires an existing owned fixture receipt'
else:out.mkdir(parents=True,exist_ok=False)
validate_build(build)
receipt_name=('bridge-resume-'+str(time.time_ns())+'.json') if a.resume_existing else 'bridge-source.json'
(out/receipt_name).write_text(json.dumps({'sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'buildManifestSha256':hashlib.sha256((build/'build-identity.json').read_bytes()).hexdigest()},indent=2))
child=None;responses=None
observer_classes=None
if a.observe_dialogs:
    observer_classes=out/('observer-'+str(time.time_ns()));observer_classes.mkdir()
    source=Path(__file__).with_name('WorkspaceDialogObserver.java')
    compiled=subprocess.run(['javac','-encoding','UTF-8','-cp',str(build/'classes')+';'+str(build/'lib'/'*'),'-d',str(observer_classes),str(source)],capture_output=True,text=True,encoding='utf-8',timeout=30)
    (observer_classes/'compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8');assert compiled.returncode==0,compiled.stderr
    (observer_classes/'source-sha256.txt').write_text(hashlib.sha256(source.read_bytes()).hexdigest())
def launch(resume=False):
    global child,responses
    responses=queue.Queue()
    classpath=(str(observer_classes)+';' if observer_classes else '')+str(build/'classes')+';'+str(build/'lib'/'*')
    args=['java','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',classpath,'WorkspaceDialogObserver' if observer_classes else 'editor.MainKt','--workspace-native-fixture','--root='+str(out)]
    if resume:args+=['--resume']
    if a.pages_fixture:args+=['--pages-fixture']
    child=subprocess.Popen(args,cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
    def pump():
        for line in child.stdout:responses.put(line)
        responses.put(None)
    def errors():
        for line in child.stderr:
            with (out/'stderr.log').open('a',encoding='utf-8') as f:f.write(line)
            print('STDERR '+line.strip()[:1000],flush=True)
    threading.Thread(target=pump,daemon=True).start();threading.Thread(target=errors,daemon=True).start()
    print(json.dumps({'ownedPid':child.pid,'resume':resume}),flush=True)
    return receive('restart' if resume else 'start','launch')
def receive(label,command):
    line=responses.get(timeout=35)
    if line is None:raise RuntimeError('Owned child exited before receipt')
    i=line.find('{');response=json.loads(line[i:]) if i>=0 else {'message':line.strip()}
    # Store exact payloads once by hash; keep screenshots/transcripts small and never print base64.
    for window in response.get('windows',[]):
        for key in ('payload','visiblePayload'):
            raw=base64.b64decode(window.pop(key));digest=hashlib.sha256(raw).hexdigest();folder=out/'payloads';folder.mkdir(exist_ok=True)
            target=folder/(digest+'.astra')
            if not target.exists():target.write_bytes(raw)
            window[key+'File']=target.relative_to(out).as_posix();window[key+'Sha256']=digest
    receipt=dict(label=label,command=command,pid=child.pid,response=response,time=time.time())
    with (out/'protocol.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(receipt,ensure_ascii=True)+'\n')
    if 'windows' in response:
        summary=[{k:w.get(k) for k in ('index','title','path','dirty','busy','fileStatus','selection','marked','undo','redo','zoom','paged','pages','admission','unfinished')}|{'units':len(w['units'])} for w in response['windows']]
        print(json.dumps(dict(label=label,pid=child.pid,windows=summary),ensure_ascii=True),flush=True)
    else:print(json.dumps(receipt,ensure_ascii=True),flush=True)
    return response
try:
    launch(a.resume_existing and not a.no_startup_prompts)
    for line in sys.stdin:
        command,_,label=line.strip().partition('|');label=label or command
        if command=='kill-owned':
            child.kill();code=child.wait(timeout=10)
            with (out/'processes.jsonl').open('a') as f:f.write(json.dumps({'pid':child.pid,'action':'forced interruption','exitCode':code})+'\n')
            print(json.dumps({'ownedKilled':child.pid,'exit':code}),flush=True)
        elif command=='restart':
            assert child.poll() is not None;launch(True)
        else:
            child.stdin.write(command+'\n');child.stdin.flush();receive(label,command)
            if command=='shutdown':break
finally:
    if child is not None and child.poll() is None:
        try:child.stdin.write('shutdown\n');child.stdin.flush();child.wait(timeout=15)
        except Exception:child.terminate();child.wait(timeout=5)
    if child is not None:
        print(json.dumps({'ownedExit':child.returncode,'ownedPid':child.pid}),flush=True)
