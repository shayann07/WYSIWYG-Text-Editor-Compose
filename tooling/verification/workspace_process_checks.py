"""Finite actual owned-JVM interruption boundaries; no UI or unrelated process control."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse, json, queue, subprocess, threading, time
from pathlib import Path
from verification import ROOT, validate_build

def main():
    p=argparse.ArgumentParser();p.add_argument('--build-dir',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args()
    build=Path(a.build_dir);out=Path(a.output_dir);out.mkdir(parents=True,exist_ok=False);validate_build(build)
    base=['java','-cp',str(build/'classes')+';'+str(build/'lib'/'*'),'editor.MainKt','--workspace-child']
    cases=[]
    for mode,stages in [('record',['after-base','after-delta','mid-write','before-publish','after-publish']),('resolve',['after-apply','after-accepted','mid-write','before-publish','after-publish']),('index',['mid-write','before-publish','after-publish'])]:
        for stage in stages:
            folder=out/(mode+'-'+stage);folder.mkdir();events=queue.Queue();lines=[]
            cmd=base+['--root='+str(folder),'--mode='+mode,'--stage='+stage]
            child=subprocess.Popen(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='strict')
            def read():
                for line in child.stdout:
                    lines.append(line);events.put(line)
                events.put(None)
            t=threading.Thread(target=read,daemon=True);t.start();hit=False;deadline=time.monotonic()+40
            try:
                while time.monotonic()<deadline:
                    try: line=events.get(timeout=1)
                    except queue.Empty: continue
                    if line is None:break
                    if line.startswith('INTERRUPT '):
                        assert f'PID={child.pid}' in line;hit=True;break
                assert hit, ''.join(lines)
                child.kill();code=child.wait(timeout=10);assert code!=0
            finally:
                if child.poll() is None:child.kill();child.wait(timeout=10)
                t.join(timeout=5);(folder/'killed-child.log').write_text(''.join(lines),encoding='utf-8')
            read_mode='read-index' if mode=='index' else 'read'
            restored=subprocess.run(base+['--root='+str(folder),'--mode='+read_mode,'--stage=none'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',timeout=40)
            (folder/'fresh-reader.log').write_text(restored.stdout,encoding='utf-8');assert restored.returncode==0,restored.stdout
            result=json.loads(next(l for l in restored.stdout.splitlines() if l.startswith('{')));assert result['status']=='PASS'
            if mode=='record': assert result['records']==1 and result['generation']==(2 if stage=='after-publish' else 1)
            elif mode=='resolve': assert result['accepted']==(stage!='after-apply') and result['records']==(0 if stage=='after-publish' else 1)
            else: assert result['name']==('new' if stage=='after-publish' else 'old')
            cases.append(dict(mode=mode,stage=stage,pid=child.pid,killedExit=code,result=result,status='PASS'))
            print('PASS owned workspace interruption',mode,stage,flush=True)
    (out/'results.json').write_text(json.dumps(dict(status='PASS',cases=cases,scope='13 actual owned process kills, not power-loss durability'),indent=2)+'\n',encoding='utf-8')
    print('PASS 13 workspace owned-process interruption cases',flush=True)
if __name__=='__main__':main()
