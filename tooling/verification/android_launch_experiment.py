"""Four predeclared fresh-device trials. Completion is never qualification PASS."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import traceback
import uuid
from consumer_check import source_inputs
from android_check import instrumentation_parser_capability

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT/'tooling/verification/android_launch_trial_plan.json'


class SafetyStop(RuntimeError):
    def __init__(self, message, details=None):
        super().__init__(message)
        self.trial_details = {} if details is None else details


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def utc(): return datetime.now(timezone.utc).isoformat()


def validate_plan(plan):
    expected = [('c1', 'C', 'cold'), ('w1', 'W', 'foreground-attach'),
                ('c2', 'C', 'cold'), ('w2', 'W', 'foreground-attach')]
    if [(v['id'], v['arm'], v['launch']) for v in plan['trials']] != expected:
        raise ValueError('The predeclared C,W,C,W plan cannot be filtered/reordered/retried')
    if (plan['ramMiB'], plan['cpuCores'], plan['expectedGroups'], plan['port']) != (6144, 2, 66, 5580):
        raise ValueError('Unreviewed resource or workload change')


def classify(trials, plan=None):
    plan = json.loads(PLAN.read_text()) if plan is None else plan
    if ([(t.get('id'),t.get('arm')) for t in trials] != [('c1','C'),('w1','W'),('c2','C'),('w2','W')]
            or any(t.get('status') in ('NOT RUN', 'BLOCKED') for t in trials)):
        return 'INCONCLUSIVE'
    controls = [t for t in trials if t['arm'] == 'C']
    treatments = [t for t in trials if t['arm'] == 'W']
    if (any(t.get('fingerprint') != plan['referenceFingerprint'] for t in trials)
            or any(t.get('memoryTotalKiB') != plan['referenceMemTotalKiB'] for t in trials)
            or any((t.get('requestedRamMiB'), t.get('cpuCores'), t.get('device')) != (6144, 2, 'pixel_7') for t in trials)
            or len({t.get('dataPath') for t in trials}) != 4):
        return 'INCONCLUSIVE'
    if (all(t.get('startupLmk') and t.get('qualification') == 'FAIL' for t in controls)
            and all(t.get('qualification') == 'PASS' and t.get('sameProcessVerified') for t in treatments)):
        return 'BOUNDED_LAUNCH_MECHANISM_SUPPORTED'
    return 'INCONCLUSIVE'


def execute_trials(plan, trial, save):
    results = []
    stopped = None
    for item in plan['trials']:
        if stopped:
            results.append(dict(item, status='NOT RUN', qualification='NOT RUN', reason=stopped))
        else:
            try:
                results.append(dict(item, **trial(item)))
            except Exception as failure:
                stopped = str(failure)
                partial = dict(item, **getattr(failure, 'trial_details', {}))
                partial.update(status='BLOCKED', qualification=partial.get('qualification','NOT RUN'), reason=stopped,
                               toolError=type(failure).__name__, rawTraceback=traceback.format_exc())
                results.append(partial)
        save(results)
    return dict(status='EXPERIMENT_COMPLETED' if not stopped else 'EXPERIMENT_BLOCKED',
                finding=classify(results,plan), trials=results,
                scope='Four fixed trials, never a blanket qualification PASS or statistical reliability claim')


def run_owned(command, output, label, *, timeout, env):
    """Bound one owned process session; timeout kills only that session's descendants."""
    began = time.monotonic()
    with (output/(label+'.stdout')).open('wb') as stdout, (output/(label+'.stderr')).open('wb') as stderr:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stdout, stderr=stderr, start_new_session=True)
        timed_out = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            if process.poll() is None:
                if os.getpgid(process.pid) != process.pid:
                    raise SafetyStop('Owned subprocess session identity changed')
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            code = process.returncode
        except BaseException:
            if process.poll() is None and os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            raise
    result = dict(command=command, exitCode=code, timedOut=timed_out, durationSeconds=time.monotonic()-began,
                  stdout=label+'.stdout', stderr=label+'.stderr')
    (output/(label+'.command.json')).write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    return result


def startup_lmk(receipt, raw_log, instrument_output, startup):
    """Do not confuse cursor failures, shutdown, or missing diagnostics with startup evidence."""
    observation = receipt.get('failureRootObservation', {})
    prerequisites = (receipt.get('status') == 'FAIL' and not receipt.get('instrumented')
            and observation.get('status') == 'AVAILABLE' and observation.get('state') == 'ABSENT'
            and 'Process crashed' in instrument_output)
    window = receipt.get('instrumentationWindow', {})
    if not prerequisites or any(window.get(k,{}).get('status') != 'AVAILABLE' for k in ('start','end')):
        return False
    try:
        start = Decimal(window['start']['deviceEpoch']); end = Decimal(window['end']['deviceEpoch'])
    except (InvalidOperation, KeyError, TypeError, ValueError):
        return False
    if not start.is_finite() or not end.is_finite() or end < start: return False
    observed = []
    for row in startup.get('records',[]):
        fields = row.get('fields',{})
        if any(fields.get(name,{}).get('status') != 'AVAILABLE' for name in ('target','wall_start','wall_end')):
            continue
        try: begin = Decimal(fields['wall_start']['epoch']); finish = Decimal(fields['wall_end']['epoch'])
        except (KeyError,InvalidOperation): continue
        if start <= begin <= finish <= end:
            observed.extend((pid,begin,finish) for pid in fields['target'].get('pids',[]))
    births = {(stamp,pid) for stamp,pid in re.findall(
        r'^[ \t]*([0-9]+\.[0-9]+)\s+[^\n]*ActivityManager: Start proc ([0-9]+):dev\.shayxo\.composedoc\.androidsample/',raw_log,re.M)
        if start <= Decimal(stamp) <= end and any(p==pid and Decimal(stamp)<=begin for p,begin,finish in observed)}
    if len(births) != 1: return False
    birth, pid = next(iter(births))
    kills = re.findall(r"^[ \t]*([0-9]+\.[0-9]+)\s+[^\n]*lowmemorykiller: Kill 'dev\.shayxo\.composedoc\.androidsample' \(([0-9]+)\),[^\n]*oom_score_adj 700\b",raw_log,re.M)
    return any(killed == pid and start <= Decimal(birth) <= Decimal(stamp) <= end
               and any(p==pid and Decimal(birth)<=begin<=finish<=Decimal(stamp) for p,begin,finish in observed)
               for stamp,killed in kills)


def qualification_complete(receipt, clipboard, adapter_command, clipboard_command):
    groups = receipt.get('instrumented',{}).get('groups',[])
    return (all(command.get('exitCode') == 0 and command.get('timedOut') is False for command in (adapter_command,clipboard_command))
            and receipt.get('status') == 'PASS' and len(groups) == 66
            and receipt.get('instrumented',{}).get('status') == 'PASS' and not receipt.get('instrumented',{}).get('fatal')
            and len({g.get('name') for g in groups}) == 66 and all(g.get('status') == 'PASS' for g in groups)
            and receipt.get('processInterruption',{}).get('status') == 'PASS'
            and receipt.get('afterCloseInterruption',{}).get('status') == 'PASS' and clipboard.get('status') == 'PASS')


def stop_owned_emulator(process, adb, serial, name, env, *, runner=subprocess.run, clock=time.monotonic, sleep=time.sleep):
    identity = runner([adb,'-s',serial,'emu','avd','name'], capture_output=True, timeout=5, env=env)
    if identity.returncode == 0:
        observed = identity.stdout.decode().splitlines()
        if not observed or observed[0].strip() != name:
            raise SafetyStop('Refusing to close a foreign emulator')
        runner([adb,'-s',serial,'emu','kill'],capture_output=True,timeout=10,check=True,env=env)
    elif process.poll() is None:
        # This is the exact still-live process session created by this trial, never a name-wide kill.
        if os.getpgid(process.pid) != process.pid:
            raise SafetyStop('Owned emulator session identity changed')
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired as failure:
        raise SafetyStop('Owned emulator did not stop; no next trial') from failure
    deadline = clock()+10
    while clock() < deadline:
        state = runner([adb,'-s',serial,'get-state'],capture_output=True,timeout=2,env=env)
        if state.returncode != 0:
            return dict(status='STOPPED', name=name, capturedPid=process.pid)
        sleep(.2)
    raise SafetyStop('Emulator still registered after process exit; no data deletion or next trial')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consumer-receipt', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if os.name != 'posix' or os.environ.get('GITHUB_ACTIONS') != 'true':
        parser.error('This comparative runner is restricted to the authorized disposable Linux CI host')
    plan = json.loads(PLAN.read_text(encoding='utf-8'))
    validate_plan(plan)
    plan_sha = digest(PLAN)
    if hashlib.sha256(subprocess.check_output(['git','show','HEAD:tooling/verification/android_launch_trial_plan.json'],cwd=ROOT)).hexdigest() != plan_sha:
        parser.error('Trial plan differs from the committed immutable plan')
    consumer = json.loads(args.consumer_receipt.read_text(encoding='utf-8'))
    if consumer.get('status') != 'PASS': parser.error('The once-built independent binaries must pass packaging first')
    artifacts = dict(consumer['androidArtifacts'])
    artifacts.update(consumer['androidPeer']['artifacts'])
    if len(artifacts) != 3: parser.error('Exactly one app, test and peer binary must be reused')
    def check_artifacts():
        if digest(PLAN) != plan_sha: raise SafetyStop('Immutable trial plan changed')
        if any(digest(Path(path)) != value for path, value in artifacts.items()):
            raise SafetyStop('Once-built artifact identity changed')
        if consumer['sourceInputs'] != source_inputs():
            raise SafetyStop('Compiled inputs no longer match the once-built consumer')
    check_artifacts()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ['ANDROID_HOME'])
    adb = str(sdk/'platform-tools/adb')
    emulator = str(sdk/'emulator/emulator')
    avdmanager = shutil.which('avdmanager')
    if not avdmanager: raise SafetyStop('Installed avdmanager unavailable')
    avdhome = Path(os.environ['RUNNER_TEMP'])/('composedoc-launch-'+uuid.uuid4().hex)
    avdhome.mkdir(exist_ok=False)
    env = dict(os.environ, ANDROID_AVD_HOME=str(avdhome))
    started = time.monotonic()
    record = dict(source=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  startedAt=utc(), planSha256=plan_sha, plan=plan, artifacts=artifacts,
                  avdHome=str(avdhome), trials=[], status='EXPERIMENT_RUNNING')
    def save(trials):
        record['trials'] = trials + [dict(item, status='NOT RUN',qualification='NOT RUN',reason='NOT_YET_EXECUTED')
                                     for item in plan['trials'][len(trials):]]
        (output/'experiment.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    save([])
    def trial(item):
        check_artifacts()
        if time.monotonic()-started >= plan['experimentLimitSeconds']:
            raise SafetyStop('Declared experiment time budget exhausted; no extra trials')
        directory = output/item['id']
        directory.mkdir(exist_ok=False)
        trial_deadline = min(started+plan['experimentLimitSeconds'], time.monotonic()+plan['trialLimitSeconds'])
        def remaining(limit):
            available = trial_deadline-time.monotonic()
            if available <= 0: raise SafetyStop('Declared trial time budget exhausted')
            return min(limit, available)
        name = 'composedoc_launch_'+item['id']
        data = avdhome/(name+'.avd')
        if data.exists() or (avdhome/(name+'.ini')).exists():
            raise SafetyStop('Fresh trial AVD identity already exists')
        try:
            created = subprocess.run([avdmanager, 'create', 'avd', '--name', name, '--device', plan['device'],
                                      '--package', plan['imagePackage']], input=b'no\n', capture_output=True, timeout=remaining(120), env=env)
        except subprocess.TimeoutExpired as failure:
            (directory/'avd-create.stdout').write_bytes(failure.stdout or b'')
            (directory/'avd-create.stderr').write_bytes(failure.stderr or b'')
            raise SafetyStop('AVD creation timed out; partial output retained') from failure
        (directory/'avd-create.stdout').write_bytes(created.stdout)
        (directory/'avd-create.stderr').write_bytes(created.stderr)
        if created.returncode or not data.is_dir(): raise SafetyStop('Fresh AVD creation failed')
        config = data/'config.ini'
        with config.open('a', encoding='utf-8') as stream:
            stream.write('\nhw.cpu.ncore=2\nhw.ramSize=6144\ndisk.dataPartition.size=2048M\nhw.keyboard=no\n')
        shutil.copyfile(config, directory/'avd-config.ini')
        if not data.resolve().is_relative_to(avdhome.resolve()): raise SafetyStop('Generated data path escaped owned root')
        command = [emulator, '-avd', name, '-port', str(plan['port']), '-no-window', '-no-audio',
                   '-no-snapshot', '-no-boot-anim', '-gpu', 'software', '-feature', '-Vulkan']
        details = dict(startedAt=utc(), emulatorCommand=command, requestedRamMiB=plan['ramMiB'],
                       cpuCores=plan['cpuCores'], device=plan['device'], name=name, dataPath=str(data), configSha256=digest(config))
        process = None
        try:
            with (directory/'emulator.log').open('wb') as log:
                process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            preflight = [sys.executable, 'tooling/verification/android_check.py', '--serial', plan['serial'], '--avd-name', name,
                         '--consumer-receipt', str(args.consumer_receipt.resolve()), '--wait-only', '--readiness-timeout', '300',
                         '--output-dir', str(directory/'preflight')]
            ready = run_owned(preflight, directory, 'preflight-command', timeout=remaining(330), env=env)
            if ready['exitCode'] or process.poll() is not None: raise SafetyStop('Fresh owned emulator failed readiness')
            def adb_read(label, *command):
                result = subprocess.run([adb, '-s', plan['serial'], *command], capture_output=True, timeout=remaining(30), env=env)
                (directory/(label+'.stdout')).write_bytes(result.stdout)
                (directory/(label+'.stderr')).write_bytes(result.stderr)
                if result.returncode: raise SafetyStop(label+' failed')
                return result.stdout.decode('utf-8', 'replace')
            actual_name = adb_read('owned-identity', 'emu', 'avd', 'name').splitlines()[0].strip()
            if actual_name != name: raise SafetyStop('Owned emulator identity changed')
            help_text = adb_read('installed-activity-cli', 'shell', 'am', 'help')
            # General service help is retained for context; actual instrument parsing is separate.
            def parser_probe(label, *command, **kwargs):
                parsed = subprocess.run([adb, '-s', plan['serial'], *command], capture_output=True,
                                        timeout=remaining(kwargs['timeout']), env=env)
                (directory/(label+'.stdout')).write_bytes(parsed.stdout)
                (directory/(label+'.stderr')).write_bytes(parsed.stderr)
                return parsed
            details['parserCapability'] = {}
            instrumentation_parser_capability(parser_probe, details['parserCapability'])
            details['fingerprint'] = adb_read('fingerprint', 'shell', 'getprop', 'ro.build.fingerprint').strip()
            details['guestMemory'] = adb_read('guest-memory', 'shell', 'cat', '/proc/meminfo')
            total = re.search(r'^MemTotal:\s+(\d+) kB$',details['guestMemory'],re.M)
            details['memoryTotalKiB'] = int(total[1]) if total else None
            details['guestUptime'] = adb_read('guest-uptime', 'shell', 'cat', '/proc/uptime').strip()
            # Exactly the existing offline/interactive/input setup, identically applied to all arms.
            for label, command in [('wifi', ['shell','svc','wifi','disable']), ('data', ['shell','svc','data','disable']),
                                   ('screen-timeout', ['shell','settings','put','system','screen_off_timeout','1800000']),
                                   ('wake', ['shell','input','keyevent','KEYCODE_WAKEUP']),
                                   ('unlock', ['shell','wm','dismiss-keyguard']),
                                   ('ime-enable', ['shell','ime','enable','com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME']),
                                   ('ime-set', ['shell','ime','set','com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME']),
                                   ('ime-show', ['shell','settings','put','secure','show_ime_with_hard_keyboard','1'])]:
                adb_read(label, *command)
            qualification = [sys.executable, 'tooling/verification/android_check.py', '--serial', plan['serial'], '--avd-name', name,
                             '--consumer-receipt', str(args.consumer_receipt.resolve()), '--instrumentation-launch', item['launch'],
                             '--output-dir', str(directory/'adapter')]
            probe = [sys.executable, 'tooling/verification/android_startup_probe.py', '--serial', plan['serial'], '--avd-name', name,
                     '--output-dir', str(directory/'startup'), '--', *qualification]
            adapter_command = run_owned(probe, directory, 'adapter-command', timeout=remaining(plan['trialLimitSeconds']), env=env)
            clipboard = [sys.executable, 'tooling/verification/android_clipboard.py', '--serial', plan['serial'], '--avd-name', name,
                         '--consumer-receipt', str(args.consumer_receipt.resolve()), '--output-dir', str(directory/'clipboard')]
            clipboard_command = run_owned(clipboard, directory, 'clipboard-command', timeout=remaining(180), env=env)
            receipt = json.loads((directory/'adapter/results.json').read_text()) if (directory/'adapter/results.json').exists() else {}
            clip = json.loads((directory/'clipboard/results.json').read_text()) if (directory/'clipboard/results.json').exists() else {}
            launch = receipt.get('instrumentationLaunch', {})
            complete = qualification_complete(receipt,clip,adapter_command,clipboard_command)
            logcat = (directory/'adapter/failure-logcat-epoch.stdout')
            raw = logcat.read_text(errors='replace') if logcat.exists() else ''
            instrument = directory/'adapter/instrument.stdout'
            startup_file = directory/'startup/startup.json'
            startup = json.loads(startup_file.read_text()) if startup_file.exists() else {}
            lmk = startup_lmk(receipt, raw, instrument.read_text(errors='replace') if instrument.exists() else '',startup)
            details.update(status='FINISHED', qualification='PASS' if complete else 'FAIL', startupLmk=lmk,
                           sameProcessVerified=launch.get('sameProcessVerified', False), launchObservation=launch,
                           adapterStatus=receipt.get('status','NOT RUN'), clipboardStatus=clip.get('status','NOT RUN'),
                           adapterCommand=adapter_command, clipboardCommand=clipboard_command,
                           instrumentationWindow=receipt.get('instrumentationWindow'),
                           failureRootObservation=receipt.get('failureRootObservation'),
                           startupSignal='BOUND_TARGET_START_AND_LMK' if lmk else 'UNESTABLISHED')
            check_artifacts()
            return details
        except Exception as failure:
            details.update(error=repr(failure), rawTraceback=traceback.format_exc())
            raise SafetyStop('Trial tool failed: '+repr(failure), details) from failure
        finally:
            try:
                if process is not None:
                    details['cleanup'] = stop_owned_emulator(process, adb, plan['serial'], name, env)
                if data.exists():
                    if not data.resolve().is_relative_to(avdhome.resolve()): raise SafetyStop('Refusing escaped AVD cleanup')
                    deleted = subprocess.run([avdmanager,'delete','avd','--name',name],capture_output=True,timeout=30,env=env)
                    (directory/'avd-delete.stdout').write_bytes(deleted.stdout)
                    (directory/'avd-delete.stderr').write_bytes(deleted.stderr)
                    if deleted.returncode: raise SafetyStop('Owned AVD deletion failed; no next trial')
                check_artifacts()
            except Exception as failure:
                details['cleanupError'] = repr(failure)
                raise SafetyStop('Owned cleanup uncertain; no next trial: '+repr(failure), details) from failure
            finally:
                details['finishedAt'] = utc()
                (directory/'trial.json').write_text(json.dumps(details,indent=2)+'\n',encoding='utf-8')
    result = execute_trials(plan, trial, save)
    record.update(result, finishedAt=utc(), elapsedSeconds=time.monotonic()-started)
    save(result['trials'])
    print(json.dumps({k:v for k,v in record.items() if k!='trials'},indent=2))
    # An experiment finding is not a qualification green check: all original failures remain failures.
    return 0 if all(t.get('qualification') == 'PASS' for t in result['trials']) else 1


if __name__ == '__main__': sys.exit(main())
