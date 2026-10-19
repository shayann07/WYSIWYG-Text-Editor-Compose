"""Bounded official Windows JAB ABI checks against only an owned offscreen fixture."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading

ROOT = _REPO_ROOT
sys.path.insert(0, str(ROOT / 'desktop-prototype'))
from verification import validate_build, identity, timestamp


class OwnedPipe:
    def __init__(self, name, command, directory):
        self.name, self.command, self.directory = name, command, directory
        self.responses = queue.Queue()
        self.process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        text=True, encoding='utf-8', errors='strict', creationflags=subprocess.CREATE_NO_WINDOW)
        self.threads = []
        for label, pipe in [('stdout', self.process.stdout), ('stderr', self.process.stderr)]:
            thread = threading.Thread(target=self.read, args=(label, pipe), daemon=True); thread.start(); self.threads.append(thread)

    def read(self, label, pipe):
        try:
            with (self.directory / (self.name + '-' + label + '.jsonl')).open('w', encoding='utf-8') as log:
                for line in pipe:
                    log.write(line); log.flush()
                    if label == 'stdout' and line.startswith('{'):
                        try: self.responses.put(json.loads(line))
                        except ValueError: self.responses.put({'readerError': 'Invalid JSON response'})
        except BaseException as error:
            self.responses.put({'readerError': str(error)})
        finally:
            if label == 'stdout': self.responses.put({'pipeEnded': True})

    def receive(self):
        result = self.responses.get(timeout=20)
        if 'readerError' in result or result.get('pipeEnded'):
            raise RuntimeError(self.name + ' protocol ended/failed: ' + str(result) + '; process=' + str(self.process.poll()))
        return result

    def request(self, **payload):
        with (self.directory / (self.name + '-requests.jsonl')).open('a', encoding='utf-8') as log:
            log.write(json.dumps(payload, ensure_ascii=True) + '\n')
        self.process.stdin.write(json.dumps(payload, ensure_ascii=True) + '\n'); self.process.stdin.flush()
        return self.receive()

    def close(self):
        forced = False
        if self.process.poll() is None:
            try: self.request(command='shutdown')
            except (OSError, RuntimeError, queue.Empty): pass
            try: self.process.stdin.close()
            except OSError: pass
            try: self.process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                forced = True; self.process.kill(); self.process.wait(timeout=4)
        for thread in self.threads: thread.join(timeout=2)
        return {'pid': self.process.pid, 'exitCode': self.process.returncode, 'forced': forced}


def units(text):
    raw = text.encode('utf-16-le', errors='surrogatepass')
    return [int.from_bytes(raw[i:i + 2], 'little') for i in range(0, len(raw), 2)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True); parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args(); build = validate_build(args.build_dir)
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).resolve().parent
    config = Path.home() / '.accessibility.properties'
    config_before = identity(config) if config.exists() else None
    report = {'status': 'RUNNING', 'startedAt': timestamp(), 'checks': [], 'steps': [],
              'scope': 'Official JAB DLL ABI in a separate PID-bound test client + owned offscreen AWT/Compose fixture; no OS keyboard/IME/screen-reader speech qualification',
              'sources': {p.name: identity(p) for p in source.iterdir() if p.suffix in ('.py', '.java')},
              'buildIdentity': identity(build / 'build-identity.json'), 'persistentAccessibilityConfigBefore': config_before}
    host = client = None
    def save(): (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    def check(name, **evidence): report['checks'].append({'name': name, 'status': 'PASS', **evidence}); save()
    save()
    try:
        if sys.platform != 'win32':
            report.update(status='NOT_RUN', reason='Windows JAB lane requires Windows'); return 0
        java = Path(shutil.which('java')).resolve(); jdk = java.parent.parent
        dll = jdk / 'bin/windowsaccessbridge-64.dll'; javac = jdk / 'bin/javac.exe'
        required = [dll, jdk / 'bin/javaaccessbridge.dll', jdk / 'include/win32/bridge/AccessBridgePackages.h',
                    jdk / 'include/win32/bridge/AccessBridgeCalls.h', javac, java]
        if not all(p.exists() for p in required):
            report.update(status='NOT_RUN', reason='Existing native JAB DLLs/headers/compiler unavailable; no installation attempted'); return 0
        report['nativeDependencies'] = {str(p): identity(p) for p in required}
        version = subprocess.run([str(javac), '-version'], capture_output=True, timeout=10)
        version_text = (version.stdout + version.stderr).decode('utf-8', 'replace').strip()
        report['compilerVersion'] = {'command': [str(javac), '-version'], 'exitCode': version.returncode, 'output': version_text}
        if version.returncode != 0 or not version_text.startswith('javac ') or int(version_text.split()[1].split('.')[0]) < 23:
            report.update(status='NOT_RUN', reason='This native tooling client requires existing JDK 23+; no upgrade attempted'); return 0
        if (jdk / 'lib/src.zip').exists(): report['installedJdkSource'] = identity(jdk / 'lib/src.zip')
        classpath = str(build / 'classes') + ';' + str(build / 'lib/*')
        classes = output / 'client-classes'; classes.mkdir()
        command = [str(javac), '--release', '23', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), str(source / 'NativeBridgeClient.java')]
        step = {'name': 'compile-native-client', 'command': command, 'startedAt': timestamp()}; report['steps'].append(step); save()
        compiled = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=30)
        (output / 'client-compile.log').write_bytes(compiled.stdout + compiled.stderr)
        step.update(exitCode=compiled.returncode, finishedAt=timestamp()); save()
        if compiled.returncode != 0: raise RuntimeError('Native AT client compilation failed; see client-compile.log')
        report['clientClass'] = identity(classes / 'NativeBridgeClient.class')
        host_command = [str(java), '--add-modules=jdk.accessibility', '-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge',
                        '-cp', classpath, 'editor.MainKt', '--native-at-probe']
        report['hostCommand'] = host_command
        host = OwnedPipe('host', host_command, output); ready = host.receive()
        assert ready['status'] == 'READY' and ready['pid'] == host.process.pid
        report['hostReady'] = ready; save()
        client_command = [str(java), '--enable-native-access=ALL-UNNAMED', '-cp', str(classes) + ';' + classpath,
                          'NativeBridgeClient', str(dll), ready['title'], str(host.process.pid)]
        report['clientCommand'] = client_command
        client = OwnedPipe('native-client', client_command, output); native_ready = client.receive()
        assert native_ready['status'] == 'READY'; report['nativeReady'] = native_ready; save()
        initial = ready['frame']; native = native_ready['frame']
        assert native['count'] == len(initial['units']) and units(native['text']) == [u[1] for u in initial['units']]
        assert native['selection'] == initial['selection']
        assert sum(node['name'] == 'Document editor' for node in native_ready['tree']) == 1
        check('Native JAB traverses the owned window and reads the exact UTF-16 document', contexts=len(native_ready['tree']), rawUnits=native['count'], targetPidVerified=True)
        assert initial['pageCount'] >= 2
        rect = client.request(command='rect', index=initial['geometryIndex'])
        assert rect['status'] == 1 and rect['bounds'] == initial['nativeBridgeDeviceCharacterBounds'], (rect, initial['nativeBridgeDeviceCharacterBounds'])
        check('Native bridge forwards the page-boundary character rectangle in device pixels', pageCount=initial['pageCount'], index=initial['geometryIndex'],
              bounds=rect['bounds'], javaLogicalBounds=initial['screenCharacterBounds'], deviceScale=initial['deviceScale'], scope='Bridge conversion fidelity, not an independent shaping/layout oracle')
        paragraph_break = next(index for index, unit in enumerate(initial['units']) if unit[2] == 1)
        attribute_runs = [(0, True, False), (5, False, True), (paragraph_break, False, True)]
        for index, bold, italic in attribute_runs:
            attrs = client.request(command='attributes', index=index)
            assert attrs['status'] == 1 and attrs['bold'] == bold and attrs['italic'] == italic
            assert (attrs['fontFamily'], attrs['fontSize']) == ('Noto Nastaliq Urdu', 26), attrs
        check('Native bridge exposes stored bold/italic and requested Noto Nastaliq Urdu 26', scope='Requested logical family/size; physical per-glyph fallback is not qualified')
        before_font = host.request(command='snapshot')['frame']
        for profile, family, size in [('system', 'SansSerif', 18), ('gulzar', 'Gulzar', 26),
                                      ('naskh', 'Noto Sans Arabic', 26), ('noto-nastaliq', 'Noto Nastaliq Urdu', 26)]:
            changed = host.request(command='typography', profile=profile)['frame']
            assert all(changed[key] == before_font[key] for key in ['units', 'selection', 'undo', 'redo', 'revision'])
            for index, bold, italic in attribute_runs:
                attrs = client.request(command='attributes', index=index)
                assert attrs['status'] == 1 and (attrs['fontFamily'], attrs['fontSize']) == (family, size), attrs
                assert attrs['bold'] == bold and attrs['italic'] == italic
        check('Native reads follow all four requested typography profiles without document or history mutation',
              notifications='Typed owner-thread notifications checked in mounted Java test; native callback subscription not tested')
        selected = client.request(command='select', start=1, end=4)
        assert selected['status'] == 1 and selected['frame']['selection'] == [1, 4]
        before = host.request(command='snapshot')['frame']; assert before['selection'] == [1, 4]
        replacement = 'کتاب English\n日本'
        written = client.request(command='set-text', text=replacement)
        assert written['status'] == 1 and units(written['frame']['text']) == units(replacement)
        after = host.request(command='snapshot')['frame']
        assert [u[1:] for u in after['units']] == [[c, 0, 1, 0] for c in units(replacement)]
        assert after['undo'] == before['undo'] + 1
        restored = host.request(command='undo')['frame']; assert restored['units'] == before['units'] and restored['selection'] == before['selection']
        redone = host.request(command='redo')['frame']; assert redone['units'] == after['units'] and redone['selection'] == after['selection']
        assert units(client.request(command='snapshot')['frame']['text']) == units(replacement)
        check('External native AT selection and text replacement reach the owner with one attributed Undo/Redo transaction', nativeSelectionEnd='Observed half-open on this JDK', historyCommands='Fixture reducer commands, not OS hotkeys')
        assert client.request(command='set-text', text='')['status'] == 1
        empty = host.request(command='snapshot')['frame']; assert empty['units'] == []
        assert host.request(command='undo')['frame']['units'] == after['units']
        assert host.request(command='redo')['frame']['units'] == []
        check('Native AT supports meaningful empty deletion with exact Undo/Redo')
        fixtures = json.loads((ROOT / 'tests/suites/urdu/u0/fixtures.json').read_text(encoding='utf-8'))
        malformed = [f for f in fixtures if f['id'] in ['UTXT-151', 'UTXT-152', 'UTXT-153', 'UTXT-154']]
        assert len(malformed) == 4
        for fixture in malformed:
            result = client.request(command='set-text', text=fixture['text'])
            assert result['status'] == 1 and units(result['frame']['text']) == fixture['utf16CodeUnits']
            frame = host.request(command='snapshot')['frame']
            assert [u[1] for u in frame['units']] == fixture['utf16CodeUnits']
        check('Four malformed U0 specimens preserve exact raw UTF-16 through the native JAB DLL', fixtures=[f['id'] for f in malformed])
        validate_build(build)
        report['status'] = 'NATIVE_JAB_PROTOCOL_CHECKS_PASS_GATE_OPEN'
        print('PASS native JAB protocol checks; actual bridge read/edit, not screen-reader/IME qualification')
    except BaseException as error:
        report.update(status='FAIL', error=repr(error)); print('FAIL native JAB: ' + repr(error), file=sys.stderr)
    finally:
        if client: report['clientTermination'] = client.close()
        if host: report['hostTermination'] = host.close()
        report['persistentAccessibilityConfigAfter'] = identity(config) if config.exists() else None
        if report['persistentAccessibilityConfigAfter'] != config_before:
            report.update(status='FAIL', error='Persistent accessibility config unexpectedly changed')
        if any(report.get(key, {}).get('forced') or report.get(key, {}).get('exitCode', 0) != 0 for key in ['clientTermination', 'hostTermination']):
            report.update(status='FAIL', cleanupError='Owned native AT process did not exit cleanly')
        report['finishedAt'] = timestamp(); save()
    return 0 if report['status'] in ['NATIVE_JAB_PROTOCOL_CHECKS_PASS_GATE_OPEN', 'NOT_RUN'] else 1


if __name__ == '__main__': sys.exit(main())
