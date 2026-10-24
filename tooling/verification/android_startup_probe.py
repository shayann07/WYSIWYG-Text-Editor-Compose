"""One bounded, read-only startup observation; never retries the qualification command."""
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

PACKAGE = 'dev.shayxo.composedoc.androidsample'
MAX_BYTES = 2 * 1024 * 1024
MAX_SAMPLES = 240
DURATION = 60
INTERVAL = .250

# Fixed test-owned package, not an arbitrary command or user document. No device writes.
READS = r'''
echo @wall_start
date +%s.%N
echo @uptime_start
cat /proc/uptime
echo @memory
grep -E '^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapCached|SwapTotal|SwapFree|Slab|SReclaimable|SUnreclaim):' /proc/meminfo
echo @pressure
cat /proc/pressure/memory
echo @target
for p in $(pidof dev.shayxo.composedoc.androidsample); do
  echo pid=$p
  cat /proc/$p/oom_score_adj
  grep -E '^(State|VmRSS|VmHWM|RssAnon|RssFile):' /proc/$p/status
done
'''
FOREGROUND = r'''
echo @foreground
dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|mFocusedRootTask'
echo @phases
logcat -d -v epoch -s ComposeDocStartup:I
'''
END_UPTIME = '\necho @uptime_end\ncat /proc/uptime\necho @wall_end\ndate +%s.%N\n'


def failed_probe(failure):
    result = dict(returncode=None, probeException=type(failure).__name__,
                  diagnosticError=repr(failure), timeoutSeconds=getattr(failure, 'timeout', None))
    for name in ('stdout', 'stderr'):
        raw = getattr(failure, name, None) or b''
        if isinstance(raw, str): raw = raw.encode('utf-8', 'replace')
        prefix = raw[:4096]
        result[name] = prefix.decode('utf-8', 'replace')
        result[name+'Base64'] = base64.b64encode(prefix).decode('ascii')
        result[name+'CapturedBytes'] = len(raw)
        result[name+'Truncated'] = len(raw) > len(prefix)
    return result


def read_probe(adb, serial, index, run=None):
    run = run or subprocess.run
    try:
        result = run([adb, '-s', serial, 'shell', READS + (FOREGROUND if index % 4 == 0 else '') + END_UPTIME],
                     capture_output=True, timeout=3)
    except subprocess.TimeoutExpired as failure:
        return failed_probe(failure)
    if len(result.stdout) + len(result.stderr) > 8192:
        raise RuntimeError('Single probe output exceeds8192bytes')
    return capabilities(dict(returncode=result.returncode, stdout=result.stdout.decode('utf-8', 'replace'),
                             stderr=result.stderr.decode('utf-8', 'replace'), foregroundRequested=index % 4 == 0))


def capabilities(result):
    """Keep optional observations unavailable rather than inventing healthy readings."""
    chunks = re.split(r'^@([a-z_]+)\r?\n', result['stdout'], flags=re.M)
    parts = dict(zip(chunks[1::2], chunks[2::2]))
    names = ('wall_start', 'uptime_start', 'memory', 'pressure', 'target', 'foreground', 'phases', 'uptime_end', 'wall_end')
    errors = {name: [] for name in names}
    unknown = []
    for line in result['stderr'].splitlines():
        if line.lower().startswith('date:'):
            errors['wall_start'].append(line); errors['wall_end'].append(line)
            continue
        if '/proc/pressure/memory' in line: field = 'pressure'
        elif '/proc/meminfo' in line: field = 'memory'
        elif '/proc/uptime' in line: field = 'uptime_start'
        elif re.search(r'/proc/\d+/(?:status|oom_score_adj)', line): field = 'target'
        elif 'logcat' in line.lower() or 'logd' in line.lower(): field = 'phases'
        elif 'activity' in line.lower() or 'dumpsys' in line.lower(): field = 'foreground'
        else:
            unknown.append(line)
            continue
        errors[field].append(line)
    fields = {}
    for name in names:
        raw = parts.get(name, '').strip()
        requested = name not in ('foreground', 'phases') or result.get('foregroundRequested', False)
        entry = dict(status='UNAVAILABLE', requested=requested, rawOutput=raw, rawErrors=errors[name])
        if name == 'target': entry['state'] = 'UNKNOWN'
        fields[name] = entry
        if not requested:
            entry['reason'] = 'NOT_REQUESTED_IN_THIS_SAMPLE'
            continue
        if errors[name] or re.search(r'Permission denied|DUMP TIMEOUT|Can.t find service|No such file', raw, re.I):
            entry['reason'] = 'READ_FAILED'
            continue
        if name.startswith('wall_'):
            if re.fullmatch(r'\d+\.\d+', raw): entry.update(status='AVAILABLE', epoch=raw)
        elif name.startswith('uptime_'):
            if re.fullmatch(r'\d+(?:\.\d+)?\s+\d+(?:\.\d+)?', raw):
                entry.update(status='AVAILABLE', seconds=[float(v) for v in raw.split()])
        elif name == 'memory':
            pairs = re.findall(r'^(\w+):\s+(\d+) kB$', raw, re.M)
            values = {key: int(value) for key, value in pairs}
            if (len(values) == len(pairs) == len(raw.splitlines()) and {'MemTotal', 'MemFree', 'MemAvailable'} <= values.keys()
                    and values['MemTotal'] > 0 and values['MemFree'] <= values['MemTotal']
                    and values['MemAvailable'] <= values['MemTotal']):
                entry.update(status='AVAILABLE', kibibytes=values)
        elif name == 'pressure':
            if re.search(r'^some avg10=\d+(?:\.\d+)? avg60=\d+(?:\.\d+)? avg300=\d+(?:\.\d+)? total=\d+$', raw, re.M):
                entry['status'] = 'AVAILABLE'
        elif name == 'target':
            pids = re.findall(r'^pid=(\d+)$', raw, re.M)
            processes = []
            for pid, detail in re.findall(r'^pid=(\d+)\n(.*?)(?=^pid=|\Z)', raw, re.M | re.S):
                score = re.match(r'-?\d+(?:\n|$)', detail)
                rss = re.search(r'^VmRSS:\s+(\d+) kB$', detail, re.M)
                state = re.search(r'^State:\s+(.+)$', detail, re.M)
                if score and rss and state:
                    processes.append(dict(pid=pid, oomScoreAdj=int(score[0].strip()), rssKiB=int(rss[1]), state=state[1]))
            if not raw:
                entry.update(status='AVAILABLE', state='ABSENT', pids=[])
            elif pids and len(processes) == len(pids):
                entry.update(status='AVAILABLE', state='PRESENT', pids=pids, processes=processes)
        elif name == 'foreground':
            if re.search(r'topResumedActivity=|mResumedActivity|mFocusedRootTask', raw):
                entry['status'] = 'AVAILABLE'
        elif name == 'phases' and name in parts:
            entry.update(status='AVAILABLE', state='EVENTS_OBSERVED' if 'ComposeDocStartup' in raw else 'NO_EVENTS_OBSERVED')
        if entry['status'] == 'UNAVAILABLE': entry['reason'] = 'MISSING_OR_MALFORMED_OBSERVATION'
    required = ('uptime_start', 'memory', 'uptime_end')
    essential = all(fields[name]['status'] == 'AVAILABLE' for name in required)
    if essential and fields['uptime_end']['seconds'][0] < fields['uptime_start']['seconds'][0]:
        fields['uptime_end'].update(status='UNAVAILABLE', reason='UPTIME_REVERSED')
        essential = False
    return dict(result, fields=fields, unattributedErrors=unknown,
                essentialAvailable=essential and not unknown and result['returncode'] == 0)


def required_available(result):
    if 'essentialAvailable' in result:
        return result['essentialAvailable'] and not result.get('probeException')
    return not (result['returncode'] or result['stderr'] or result.get('probeException'))


def coverage(records, ending):
    phases = {}
    for row in records:
        for phase, pid in re.findall(r'phase=([a-z-]+) elapsedMs=\d+ pid=(\d+)', row.get('stdout', '')):
            phases.setdefault(pid, set()).add(phase)
    complete = {'instrumentation-created', 'instrumentation-started', 'before-activity-launch', 'editor-mounted'}
    return dict(phasesByPid={pid: sorted(values) for pid, values in phases.items()},
                startupToMountedObservedInOnePid=any(complete <= values for values in phases.values()),
                windowExpiredWithoutObservedInstrumentationStart=ending == 'BOUNDED_WINDOW_COMPLETE'
                and not any('instrumentation-started' in values for values in phases.values()),
                windowExpiredWithoutObservedEditorMount=ending == 'BOUNDED_WINDOW_COMPLETE'
                and not any('editor-mounted' in values for values in phases.values()))


class Sampler:
    def __init__(self, probe, *, clock=time.monotonic, interval=INTERVAL,
                 duration=DURATION, max_samples=MAX_SAMPLES, max_bytes=MAX_BYTES-131072):
        self.probe = probe
        self.clock = clock
        self.interval, self.duration = interval, duration
        self.max_samples, self.max_bytes = max_samples, max_bytes
        self.stop = threading.Event()
        self.records = []
        self.error = None
        self.bytes = 0
        self.ending = None
        self.baseline = None

    def run(self):
        started = self.clock()
        previous = None
        try:
            while not self.stop.is_set():
                now = self.clock()
                if now - started >= self.duration or len(self.records) >= self.max_samples:
                    self.ending = 'BOUNDED_WINDOW_COMPLETE'
                    return
                index = len(self.records)
                utc_start = datetime.now(timezone.utc).isoformat()
                try:
                    result = self.probe(index)
                except Exception as failure:
                    result = failed_probe(failure)
                ended = self.clock()
                record = dict(index=index, utcStart=utc_start, utcEnd=datetime.now(timezone.utc).isoformat(),
                              monotonicStart=now, monotonicEnd=ended, sinceStartMs=(now-started)*1000,
                              gapMs=None if previous is None else (now-previous)*1000,
                              commandCostMs=(ended-now)*1000, **result)
                encoded = json.dumps(record).encode('utf-8')
                if self.bytes + len(encoded) > self.max_bytes:
                    raise RuntimeError('Diagnostic output budget exhausted; no inference')
                self.records.append(record)
                self.bytes += len(encoded)
                previous = now
                if not required_available(result):
                    raise RuntimeError('Essential read-only observation failed; retain exact data/errors')
                self.stop.wait(max(0, self.interval - (self.clock()-now)))
            self.ending = 'QUALIFICATION_EXITED'
        except Exception as failure:
            self.error = repr(failure)
            self.ending = 'PROBE_BLOCKED'


def observe(sampler, command, baseline_probe=None):
    if baseline_probe is not None:
        began = sampler.clock()
        utc_start = datetime.now(timezone.utc).isoformat()
        try:
            result = baseline_probe()
        except Exception as failure:
            result = failed_probe(failure)
        ended = sampler.clock()
        sampler.baseline = dict(utcStart=utc_start, utcEnd=datetime.now(timezone.utc).isoformat(),
                                monotonicStart=began, monotonicEnd=ended, commandCostMs=(ended-began)*1000,
                                scope='Before qualification command; setup cost, no artificial sleep', **result)
        if not required_available(result):
            sampler.error = 'Prelaunch diagnostic unavailable; qualification NOT RUN'
            sampler.ending = 'PROBE_BLOCKED'
            sampler.stop.set()
            return None
    thread = threading.Thread(target=sampler.run, name='bounded-android-startup-probe')
    thread.start()
    try:
        return command()
    finally:
        sampler.stop.set()
        # Each adb client has a three-second timeout and is reaped by subprocess.run.
        thread.join(4)
        if thread.is_alive():
            raise RuntimeError('Sampler cleanup did not finish within its command bound')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd-name', required=True)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not re.fullmatch(r'emulator-\d+', args.serial) or not command:
        parser.error('An explicitly owned emulator and one qualification command are required')
    if sum(len(a.encode('utf-8')) for a in command) > 4096 or any(re.search(r'[\x00-\x1f]', a) for a in command):
        parser.error('Qualification command exceeds the bounded metadata allowance')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        parser.error('ANDROID_HOME must identify the authorized SDK')
    adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
    identity = subprocess.run([adb, '-s', args.serial, 'emu', 'avd', 'name'],
                              capture_output=True, text=True, timeout=5, check=True)
    if not identity.stdout.splitlines() or identity.stdout.splitlines()[0].strip() != args.avd_name:
        parser.error('Observed AVD differs from the authorized disposable emulator')
    args.output_dir.mkdir(parents=True, exist_ok=False)

    def probe(index):
        return read_probe(adb, args.serial, index)

    sampler = Sampler(probe)
    code = None
    failure = None
    try:
        code = observe(sampler, lambda: subprocess.run(command, check=False).returncode,
                       baseline_probe=lambda: probe(0))
    except BaseException as error:
        failure = repr(error)
        raise
    finally:
        target_seen = any('pid=' in r.get('stdout', '') for r in sampler.records)
        observations = ([sampler.baseline] if sampler.baseline else []) + sampler.records
        optional_missing = any(field['requested'] and field['status'] == 'UNAVAILABLE'
                               for row in observations for name, field in row.get('fields', {}).items()
                               if name in ('pressure', 'target', 'foreground', 'phases', 'wall_start', 'wall_end'))
        startup_coverage = coverage(sampler.records, sampler.ending)
        diagnostic_status = ('BLOCKED' if failure or sampler.error else
                             'PARTIAL' if optional_missing or not startup_coverage['startupToMountedObservedInOnePid']
                             else 'RECORDED')
        record = dict(schemaVersion=1, scope='Perturbed diagnostic observation, not causal/performance proof',
                      serial=args.serial, avd=args.avd_name, command=command, commandExitCode=code,
                      nominalIntervalMs=250, durationLimitSeconds=DURATION, sampleLimit=MAX_SAMPLES,
                      byteLimit=MAX_BYTES, ending=sampler.ending, probeError=sampler.error,
                      baseline=sampler.baseline,
                      startupCoverage=startup_coverage,
                      wrapperError=failure, targetObserved=target_seen, records=sampler.records,
                      status=diagnostic_status)
        encoded = (json.dumps(record, ensure_ascii=False, separators=(',', ':'))+'\n').encode('utf-8')
        if len(encoded) > MAX_BYTES:
            raise RuntimeError('Final diagnostic serialization exceeds its hard output bound')
        (args.output_dir / 'startup.json').write_bytes(encoded)
    # Optional diagnostic gaps never rewrite the original qualification result.
    return code if code is not None else 2


if __name__ == '__main__':
    sys.exit(main())
