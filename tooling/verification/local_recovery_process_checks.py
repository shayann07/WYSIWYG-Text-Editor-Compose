"""Own JVM children only: actual force/rename barriers -> kill -> new JVM decoder."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from pathlib import Path
import argparse, hashlib, json, queue, subprocess, threading, time, uuid

def document(tag):
    text = 'کتاب ' + tag + '\nکِتاب\nEnglish 123 \ud800\uffff'
    hard = text.index('\n')
    return 2, [[ord(c), 1 if i == hard else 0, 3 if i == hard else (1 if i % 2 == 0 else 2)] for i,c in enumerate(text)]

def decode(path):
    import struct
    data=path.read_bytes(); body=data[:-32]
    assert hashlib.sha256(body).digest()==data[-32:]
    magic, version, n=struct.unpack('>III',body[:12]); assert magic==0x41535450 and version in (3,4,5,6,7,8,9)
    if version >= 6:
        from structured_process_checks import decode_v6
        # Validate the complete versioned tail independently as well as the
        # exact recovery specimen fields below; do not just skip new metadata.
        decode_v6(data)
    header,stride=(26,17) if version>=5 else (14,5) if version==4 else (13,4)
    assert len(body)>=header+n*stride if version>=6 else len(body)==header+n*stride
    units=[list(struct.unpack('>HBB',body[header+i*stride:header+i*stride+4])) for i in range(n)]
    if version>=5: units=[[c,k,bits & 3] for c,k,bits in units]
    if version>=5:
        assert body[19]==5
        assert [body[header+i*stride+10] for i in range(n)]==[10 if u[1]==1 else 0 for u in units]
        # Independent fixed-width style/property validation (no engine/codec calls).
        assert body[12:19]==bytes([6,2,22])+(0x146c43).to_bytes(4,'big')
        assert struct.unpack('>HHH',body[20:26])==(8,16,1250)
        for i,u in enumerate(units):
            offset=header+i*stride
            assert body[offset+3]==(11 if u[1] else (u[2] | 12))
            assert body[offset+4:offset+6]==bytes([1,18] if u[1] else [3,32])
            assert int.from_bytes(body[offset+6:offset+10],'big')==(0xb3261e if u[1] else 0x174ea6)
            assert struct.unpack('>HHH',body[offset+11:offset+17])==((24,12,1500) if u[1] else (0,0,0))
    if version==4:
        assert body[13]==5 # crash specimen root: RTL/Center
        assert [body[header+i*stride+4] for i in range(n)]==[10 if u[1]==1 else 0 for u in units] # following paragraph: LTR/End
    return body[12] & 3,units

def main():
    p=argparse.ArgumentParser(); p.add_argument('--build-dir',type=Path,required=True); p.add_argument('--output-dir',type=Path,required=True); a=p.parse_args()
    a.output_dir.mkdir(parents=True,exist_ok=False)
    base=['java','-cp',str(a.build_dir.resolve()/'classes')+';'+str(a.build_dir.resolve()/'lib/*'),'editor.MainKt','--local-recovery-child']
    receipts=[]
    def kill_at(command, marker):
        child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
        q=queue.Queue()
        def read():
            for line in child.stdout: q.put(line)
        threading.Thread(target=read,daemon=True).start(); lines=[]; deadline=time.monotonic()+40
        try:
            while True:
                line=q.get(timeout=max(.01,deadline-time.monotonic())); lines.append(line)
                if line.startswith(marker): break
                if time.monotonic()>deadline: raise TimeoutError('Owned child did not reach actual barrier')
            child.kill(); code=child.wait(timeout=15); assert code!=0
            receipts.append({'command':command,'pid':child.pid,'barrier':line.strip(),'exitCode':code,'action':'Parent killed this exact owned child; no normal close'})
            return line[len(marker):].strip()
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=15)
            child.stdout.close(); child.stdin.close()
    def scan(root, session, tag, name, original=None):
        out=a.output_dir/(name+'.astra')
        cmd=base+['--scan-copy','--recovery-dir='+str(root),'--copy-session='+session,'--copy-result='+str(out)]
        r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=30)
        assert r.returncode==0, r.stderr[-1000:]
        assert decode(out)==document(tag)
        metadata=json.loads(r.stdout); assert metadata['original']==original
        receipts.append({'command':cmd,'exitCode':0,'recovered':tag,'metadata':metadata,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()})
    import struct
    for phase in ('before-write','mid-write','before-publish','after-publish'):
        for named in (False,True):
            name=phase+('-named' if named else '-untitled'); root=(a.output_dir/name).resolve(); root.mkdir(); session=str(uuid.uuid4())
            original=root/'original.astra'
            start,units=document('saved')
            payload=struct.pack('>IIIB',0x41535450,3,len(units),start)+b''.join(struct.pack('>HBB',*u) for u in units)
            original.write_bytes(payload+hashlib.sha256(payload).digest()); original_bytes=original.read_bytes()
            cmd=base+['--recovery-dir='+str(root),'--copy-session='+session,'--interrupt-at='+phase]
            if named: cmd+=['--original='+str(original)]
            kill_at(cmd,'BARRIER:')
            scan(root,session,'new' if phase=='after-publish' else 'old',name,str(original) if named else None)
            assert original.read_bytes()==original_bytes
            if phase=='after-publish' and not named:
                latest=root/session/'copy-0.bin'; good=latest.read_bytes()
                latest.write_bytes(good[:19]); scan(root,session,'old','truncated-latest')
                corrupt=bytearray(good); corrupt[20]^=1; latest.write_bytes(corrupt)
                scan(root,session,'old','checksum-corrupt-latest')
    root=(a.output_dir/'multiple-windows').resolve()
    sessions=json.loads(kill_at(base+['--recovery-dir='+str(root),'--multi-window-copy'],'MULTI_READY:'))
    assert len({v['session'] for v in sessions})==2
    for v in sessions: scan(root,v['session'],v['tag'],v['tag'],str(root/'named-original.astra') if v['tag']=='window-B' else None)
    assert decode(root/'named-original.astra')==document('window-B')
    result={'status':'PASS','cases':9,'scope':'Nine actual owned JVM kills and fresh JVM recovery; not power-loss durability',
            'window':'Before publication, only the prior completed copy survives. The interrupted newer payload is intentionally not promised.',
            'multipleWindows':'Untitled plus named in the same killed GUI process; active IME preedit in one; only committed payload recovered',
            'receipts':receipts}
    (a.output_dir/'results.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('PASS 9 owned-process interruption cases and exact fresh-JVM recovery')

if __name__=='__main__': main()
