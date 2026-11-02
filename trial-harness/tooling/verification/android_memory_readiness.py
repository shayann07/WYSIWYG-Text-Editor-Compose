"""One bounded kernel observation; never AM responsiveness, RAM sufficiency or boot proof."""
import json
from pathlib import Path
import re
import subprocess
import time

COMMAND_CAP_SECONDS = 15
SUMMARY = re.compile(r'^\s*(Total|Free|Used) RAM:\s*([0-9][0-9,]*)K(?:\s|$)', re.MULTILINE)
KERNEL_FIELDS = ('MemTotal', 'MemFree', 'MemAvailable', 'Buffers', 'Cached', 'SwapTotal', 'SwapFree')


def parse_kernel(raw, exit_code, stderr=b''):
    if exit_code != 0:
        raise ValueError('Kernel memory command did not succeed')
    if stderr.strip() or not raw or len(raw) > 65536 or not raw.endswith(b'\n'):
        raise ValueError('Incomplete or erroneous kernel memory observation')
    fields = {}
    for line in raw.decode('utf-8', 'strict').splitlines():
        match = re.fullmatch(r'([A-Za-z][A-Za-z0-9_()]*):\s+([0-9]+)(?:\s+(kB))?\s*', line)
        if not match or match[1] in fields or len(match[2]) > 19 or int(match[2]) > 2**63 - 1:
            raise ValueError('Malformed or duplicate kernel memory field')
        fields[match[1]] = (int(match[2]), match[3])
    if any(name not in fields or fields[name][1] != 'kB' for name in KERNEL_FIELDS):
        raise ValueError('Missing kernel memory fields or invalid units')
    values = {name: fields[name][0] for name in KERNEL_FIELDS}
    if (values['MemTotal'] <= 0 or any(values[name] > values['MemTotal'] for name in
                                     ('MemFree', 'MemAvailable', 'Buffers', 'Cached'))
            or values['SwapFree'] > values['SwapTotal']):
        raise ValueError('Inconsistent kernel memory bounds')
    return {name + 'KiB': value for name, value in values.items()}


class MemoryServiceUnavailable(RuntimeError):
    def __init__(self, reason, record):
        super().__init__(reason)
        self.record = record


def parse_summary(raw, exit_code, stderr=b''):
    """Historical global-dump receipt parser; not used to admit new instrumentation."""
    if exit_code != 0:
        raise ValueError('meminfo command did not succeed')
    text = raw.decode('utf-8', 'strict')
    if re.search(r'DUMP\s+TIMEOUT|timed\s+out', text + '\n' + stderr.decode('utf-8', 'strict'), re.IGNORECASE):
        raise ValueError('meminfo contains a service timeout; measurements are unavailable')
    found = SUMMARY.findall(text)
    headers = re.findall(r'^\s*(Total|Free|Used) RAM:', text, re.MULTILINE)
    if len(headers) != 3 or len(found) != 3 or set(headers) != {'Total', 'Free', 'Used'}:
        raise ValueError('meminfo needs one complete Total/Free/Used RAM summary')
    values = {}
    for label, value in found:
        if not re.fullmatch(r'(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)', value):
            raise ValueError('Malformed meminfo numeric summary')
        values[label.lower() + 'KiB'] = int(value.replace(',', ''))
    if values['totalKiB'] <= 0:
        raise ValueError('meminfo total is unavailable')
    return values


def observe_memory(probe, output, label, deadline, *, clock=time.monotonic):
    """No polling or qualification retry. All command outcomes, including zero-exit timeouts, are retained."""
    output = Path(output)
    record = dict(status='BLOCKED', scope='Kernel memory observation only; not service readiness, boot completion or sufficient RAM',
                  command=['exec-out', 'cat', '/proc/meminfo'], exitCode=None, timedOut=False, elapsedSeconds=0)
    started = clock()
    stdout, stderr = b'', b''
    try:
        remaining = deadline - clock()
        if remaining <= 0:
            raise ValueError('Setup deadline exhausted before memory observation')
        api_raw = probe(label + '-api', 'shell', 'getprop', 'ro.build.version.sdk', timeout=min(5, remaining)).strip()
        if not re.fullmatch(rb'[0-9]{1,3}', api_raw):
            raise ValueError('Memory observation cannot identify Android API')
        record['api'] = int(api_raw)
        if record['api'] < 37:
            record.update(status='NOT_APPLICABLE', scope='Older output format not qualified; existing lane unchanged')
            return record
        timeout = min(COMMAND_CAP_SECONDS, deadline - clock())
        if timeout <= 0:
            raise ValueError('Setup deadline exhausted before meminfo command')
        record['timeoutSeconds'] = timeout
        completed = probe(label, *record['command'], timeout=timeout,
                          allowed_codes=None, with_status=True, defer_evidence_errors=True)
        stdout, stderr = completed.stdout, completed.stderr
        record['exitCode'] = completed.returncode
        if getattr(completed, 'evidence_errors', None):
            record['retentionErrors'] = completed.evidence_errors
        record['values'] = parse_kernel(stdout, completed.returncode, stderr)
        if clock() >= deadline:
            raise ValueError('Setup deadline exhausted before kernel observation completed')
        if record.get('retentionErrors'):
            raise ValueError('Cannot retain memory observation evidence')
        record['status'] = 'KERNEL_OBSERVED'
        return record
    except Exception as error:
        if isinstance(error, subprocess.TimeoutExpired):
            stdout, stderr = error.stdout or b'', error.stderr or b''
            record['timedOut'] = True
            if getattr(error, 'evidence_errors', None):
                record['retentionErrors'] = error.evidence_errors
        record['error'] = str(error)
        raise MemoryServiceUnavailable(str(error), record) from error
    finally:
        record['elapsedSeconds'] = clock() - started
        observation_failed = 'error' in record
        retention_errors = list(record.get('retentionErrors', []))
        for suffix, raw in (('stdout', stdout), ('stderr', stderr)):
            path = output / (label + '.' + suffix)
            try:
                if raw or not path.exists():
                    path.write_bytes(raw)
            except OSError as error:
                retention_errors.append(str(error))
        if retention_errors:
            record['retentionErrors'] = retention_errors
            if not observation_failed:
                record.update(status='BLOCKED', error='Cannot retain memory observation evidence')
        try:
            (output / (label + '.json')).write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        except OSError as error:
            retention_errors.append(str(error))
            record['retentionErrors'] = retention_errors
        if retention_errors and not observation_failed:
            record.update(status='BLOCKED', error='Cannot retain memory observation evidence')
            raise MemoryServiceUnavailable(record['error'], record)


def admitted_instrument(probe, output, receipt, deadline, instrument):
    receipt['memoryBeforeInstrumentation'] = observe_memory(probe, output, 'memory-before-instrumentation', deadline)
    return instrument()
