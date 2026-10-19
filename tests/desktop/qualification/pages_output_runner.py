"""Bounded, owned-process runner for current pages/output checks and v9 oracle.

No build, user document, UI automation, printer submission or dependency install.
It validates inputs/artifacts both before and after executing the supplied build.
"""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verification import ROOT,validate_build
from pages_v9_oracle import check_pages,decoder_selftest,reject_mutants
from structured_process_checks import decode_v6


def rss_reader(process):
    try:
        import psutil
    except ImportError:
        psutil=None
    if psutil:
        owner=psutil.Process(process.pid)
        return lambda:owner.memory_info().rss,'psutil '+psutil.__version__
    if os.name=='nt':
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_=[('cb',wintypes.DWORD),('PageFaultCount',wintypes.DWORD),('PeakWorkingSetSize',ctypes.c_size_t),('WorkingSetSize',ctypes.c_size_t),('QuotaPeakPagedPoolUsage',ctypes.c_size_t),('QuotaPagedPoolUsage',ctypes.c_size_t),('QuotaPeakNonPagedPoolUsage',ctypes.c_size_t),('QuotaNonPagedPoolUsage',ctypes.c_size_t),('PagefileUsage',ctypes.c_size_t),('PeakPagefileUsage',ctypes.c_size_t)]
        query=ctypes.WinDLL('psapi',use_last_error=True).GetProcessMemoryInfo
        query.argtypes=[wintypes.HANDLE,ctypes.POINTER(Counters),wintypes.DWORD];query.restype=wintypes.BOOL
        def read():
            counters=Counters();counters.cb=ctypes.sizeof(counters)
            if not query(wintypes.HANDLE(int(process._handle)),ctypes.byref(counters),counters.cb):raise ctypes.WinError(ctypes.get_last_error())
            return counters.PeakWorkingSetSize
        return read,'Windows GetProcessMemoryInfo owned-process peak working set'
    return None,'NOT RUN: psutil unavailable and no Windows process-memory API'


def main():
    p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--timeout',type=int,default=300);a=p.parse_args()
    assert __debug__,'Run without -O; qualification uses assertions'
    build=a.build_dir.resolve();output=a.output_dir.resolve();output.mkdir(parents=True,exist_ok=False)
    receipt=dict(status='RUNNING',scope='Mounted editor/page-plan/print callbacks and independent codec oracle; not native interaction, semantic PDF or physical printing',limits=dict(timeoutSeconds=a.timeout,peakRssBytes=2*1024**3,javaMaxHeap='1536m'),processes=[])
    result_file=output/'results.json'
    def persist():result_file.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    def run(label,args):
        command=['java','-Xmx1536m','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',str(build/'classes')+os.pathsep+str(build/'lib'/'*'),'editor.MainKt']+args
        start=time.monotonic();entry=dict(label=label,command=command,status='RUNNING');receipt['processes'].append(entry);persist()
        log_path=output/(label+'.log')
        with log_path.open('wb') as stream:
            child=subprocess.Popen(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
            entry['pid']=child.pid;read,memory_method=rss_reader(child);entry['memoryMethod']=memory_method;peak=0
            try:
                while child.poll() is None:
                    if read:peak=max(peak,read())
                    if peak>receipt['limits']['peakRssBytes']:raise RuntimeError('Owned child exceeded declared 2GiB peak working-set envelope')
                    if time.monotonic()-start>a.timeout:raise TimeoutError('Owned child exceeded declared timeout')
                    time.sleep(.05)
                entry['exitCode']=child.returncode
            finally:
                if child.poll() is None:child.kill();child.wait(timeout=15);entry['killedOwnedChild']=True
                entry.update(status='FAIL',elapsedSeconds=round(time.monotonic()-start,3),peakRssBytes=peak or None,log=str(log_path));persist()
        log=log_path.read_text(encoding='utf-8',errors='replace')
        assert child.returncode==0,log[-8000:]
        assert 'Exception in thread ' not in log and 'Uncaught exception' not in log,log[-8000:]
        entry['status']='PASS';persist();return log
    try:
        validate_build(build)
        receipt['build']=str(build);receipt['buildManifestSha256']=hashlib.sha256((build/'build-identity.json').read_bytes()).hexdigest()
        receipt['runnerSources']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__),Path(__file__).with_name('pages_v9_oracle.py'),ROOT/'tooling/verification/structured_process_checks.py']}
        receipt['decoderSelftest']=decoder_selftest()
        child_result=output/'mounted-results.json'
        log=run('pages-output',['--pages-output-check','--host-result='+str(child_result)])
        mounted=json.loads(child_result.read_text(encoding='utf-8'))
        assert mounted['status']=='PASS' and mounted['groups'] and len(set(mounted['groups']))==len(mounted['groups'])
        assert f"PASS pages/output checks {len(mounted['groups'])}" in log
        receipt['mountedResult']=mounted
        sample=output/'sample.astra';run('create-sample',['--create-pages-sample='+str(sample)])
        raw=sample.read_bytes();decoded=decode_v6(raw);oracle=check_pages(decoded);oracle['negativeControls']=reject_mutants(decoded);oracle['fileSha256']=hashlib.sha256(raw).hexdigest()
        receipt['independentSampleOracle']=oracle
        validate_build(build)
        receipt['status']='PASS'
    except Exception as failure:
        receipt.update(status='FAIL',error=str(failure),traceback=traceback.format_exc());persist();print(receipt['traceback']);return 1
    persist();print(json.dumps(dict(status='PASS',groups=len(receipt['mountedResult']['groups']),independentOracle='PASS',buildManifestSha256=receipt['buildManifestSha256'],evidence=str(output)),ensure_ascii=True));return 0


if __name__=='__main__':raise SystemExit(main())
