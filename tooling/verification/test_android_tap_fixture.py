import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from android_tap_fixture import owned_tap_fixture, ASSET

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT/'apps/android-sample/src/androidTest/assets/composedoc-tap-v1.sh'


class FixtureProbe:
    def __init__(self):
        self.data = None
        self.calls = []
        self.exists = False
        self.changed = False
        self.probe = b'COMPOSEDOC_TAP_CAPABILITY=default\n'

    def __call__(self, label, *args, **kwargs):
        self.calls.append((label, args))
        if label == 'tap-fixture-absent':
            return subprocess.CompletedProcess(args, 0 if self.exists else 1, b'', b'')
        if label == 'tap-fixture-push': self.data = Path(args[1]).read_bytes()
        if label in ('tap-fixture-verify', 'tap-fixture-before-remove'):
            return b'foreign changed bytes' if self.changed else self.data
        if label == 'tap-fixture-probe': return self.probe
        if label == 'tap-fixture-removed': return subprocess.CompletedProcess(args, 1, b'', b'')
        return b''


class OwnedTapFixture(unittest.TestCase):
    def setup_fixture(self, directory):
        apk = directory/'test.apk'
        with zipfile.ZipFile(apk, 'w') as z: z.writestr(ASSET, SCRIPT.read_bytes())
        return apk

    def test_exact_apk_bytes_published_probed_and_only_owned_path_removed(self):
        with tempfile.TemporaryDirectory(prefix='tapfixture-') as tmp:
            root = Path(tmp); probe = FixtureProbe(); receipt = {}
            with owned_tap_fixture(probe, self.setup_fixture(root), root, 'adapter-123', receipt) as path:
                self.assertEqual('/data/local/tmp/composedoc-tap-v1-adapter-123/tap.sh', path)
                self.assertEqual('READY', receipt['tapFixture']['status'])
                self.assertEqual(SCRIPT.read_bytes(), probe.data)
            self.assertEqual('REMOVED', receipt['tapFixture']['cleanup'])
            self.assertEqual(1, sum(label == 'tap-fixture-remove' for label, _ in probe.calls))

    def test_collision_escape_and_missing_capability_never_request_touch(self):
        with tempfile.TemporaryDirectory(prefix='tapfixture-') as tmp:
            root = Path(tmp); apk = self.setup_fixture(root)
            for generation in ['../foreign', 'adapter-1;rm', 'adapter-a']:
                probe = FixtureProbe()
                with self.assertRaisesRegex(ValueError, 'generation'):
                    with owned_tap_fixture(probe, apk, root, generation, {}): pass
                self.assertEqual([], probe.calls)
            probe = FixtureProbe(); probe.exists = True
            with self.assertRaisesRegex(ValueError, 'existing'):
                with owned_tap_fixture(probe, apk, root, 'adapter-1', {}): pass
            self.assertFalse(any(label == 'tap-fixture-remove' for label, _ in probe.calls))
            probe = FixtureProbe(); probe.probe = b'unsupported'
            with self.assertRaisesRegex(ValueError, 'capability'):
                with owned_tap_fixture(probe, apk, root, 'adapter-2', {}): pass
            self.assertTrue(any(label == 'tap-fixture-remove' for label, _ in probe.calls))

    def test_failed_instrumentation_remains_primary_and_changed_fixture_is_preserved(self):
        with tempfile.TemporaryDirectory(prefix='tapfixture-') as tmp:
            root = Path(tmp); probe = FixtureProbe(); receipt = {}; primary = RuntimeError('original check failed')
            with self.assertRaises(RuntimeError) as caught:
                with owned_tap_fixture(probe, self.setup_fixture(root), root, 'adapter-3', receipt):
                    probe.changed = True
                    raise primary
            self.assertIs(primary, caught.exception)
            self.assertIn('cleanup failed', primary.__notes__[0])
            self.assertIn('changed', receipt['tapFixture']['cleanupError'])
            self.assertFalse(any(label == 'tap-fixture-remove' for label, _ in probe.calls))


class ActualRuntimeExec(unittest.TestCase):
    def test_packaged_script_default_display_and_error_paths_with_disposable_input(self):
        shell = Path('C:/PROGRA~1/Git/bin/sh.exe') if os.name == 'nt' else Path('/bin/sh')
        self.assertTrue(shell.is_file())
        with tempfile.TemporaryDirectory(prefix='tap-script-') as tmp:
            root = Path(tmp); self.assertNotIn(' ', str(root))
            fake = root/'input.sh'; mode = root/'mode'; calls = root/'calls'
            fake.write_bytes((
                'if [ "$#" -eq 0 ]; then\n'
                ' printf "tap <x> <y>\\n"\n'
                f' if [ "$(cat {mode.as_posix()})" = display ]; then printf -- "-d display\\n"; fi\n'
                ' exit 0\nfi\n'
                f'printf "%s\\n" "$*" >> {calls.as_posix()}\n'
                'exit 0\n').encode('utf-8'))
            deployed = root/'tap.sh'
            # Only Android executable locations are replaced; the packaged script's parsing/branching is executed.
            text = SCRIPT.read_text(encoding='utf-8').replace('/system/bin/input', f'{shell.as_posix()} {fake.as_posix()}')
            text = text.replace('/system/bin/sh', shell.as_posix())
            deployed.write_bytes(text.encode('utf-8'))
            def invoke(*args):
                return subprocess.run([str(shell), str(deployed), *args], capture_output=True, timeout=10)
            mode.write_text('default')
            self.assertEqual(b'COMPOSEDOC_TAP_CAPABILITY=default\n', invoke('--probe').stdout)
            self.assertFalse(calls.exists())
            result = invoke('122.5', '741.3125', '0')
            self.assertEqual((0, b'\nCOMPOSEDOC_TAP_EXIT=0\n'), (result.returncode, result.stdout), result.stderr)
            self.assertEqual('touchscreen tap 122.5 741.3125', calls.read_text().strip())
            before = calls.read_bytes()
            rejected = invoke('122.5', '741.3125', '1')
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn(b'nondefault-display-unsupported', rejected.stdout)
            self.assertEqual(before, calls.read_bytes())
            mode.write_text('display')
            self.assertEqual(b'COMPOSEDOC_TAP_CAPABILITY=display\n', invoke('--probe').stdout)
            result = invoke('122.5', '741.3125', '1')
            self.assertEqual((0, b'\nCOMPOSEDOC_TAP_EXIT=0\n'), (result.returncode, result.stdout), result.stderr)
            self.assertEqual('touchscreen -d 1 tap 122.5 741.3125', calls.read_text().splitlines()[-1])
            self.assertNotEqual(0, invoke('bad;touch', '1', '0').returncode)

    def test_real_java_string_exec_requires_a_script_not_shell_operators(self):
        java_home = Path(os.environ['JAVA_HOME'])
        shell = Path('C:/PROGRA~1/Git/bin/sh.exe') if os.name == 'nt' else Path('/bin/sh')
        self.assertTrue(shell.is_file(), 'Existing shell required; do not download a runtime')
        with tempfile.TemporaryDirectory(prefix='tapexec-') as tmp:
            root = Path(tmp); self.assertNotIn(' ', str(root))
            script = root/'fixture.sh'
            script.write_text('printf "\\nCOMPOSEDOC_TAP_EXIT=%s\\n" "$1"\n', encoding='utf-8')
            source = root/'ExecWitness.java'
            source.write_text('''import java.util.*;
public class ExecWitness {
 public static void main(String[] args) throws Exception {
  Process p = Runtime.getRuntime().exec(args[0]);
  byte[] out = p.getInputStream().readAllBytes();
  byte[] err = p.getErrorStream().readAllBytes();
  if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw new Exception("timeout"); }
  System.out.println(p.exitValue());
  System.out.println(Base64.getEncoder().encodeToString(out));
  System.out.println(Base64.getEncoder().encodeToString(err));
 }
}''', encoding='utf-8')
            import base64
            def invoke(command):
                p = subprocess.run([str(java_home/'bin/java'), str(source), command], capture_output=True, timeout=30)
                self.assertEqual(0, p.returncode, p.stderr)
                lines = p.stdout.decode().splitlines()
                return int(lines[0]), base64.b64decode(lines[1])
            old = invoke(f'{shell} {script} 0; printf SHOULD_NOT_RUN')
            self.assertNotEqual(b'\nCOMPOSEDOC_TAP_EXIT=0\n', old[1])
            self.assertNotIn(b'SHOULD_NOT_RUN', old[1])
            self.assertEqual((0, b'\nCOMPOSEDOC_TAP_EXIT=0\n'), invoke(f'{shell} {script} 0'))
            self.assertEqual((0, b'\nCOMPOSEDOC_TAP_EXIT=124\n'), invoke(f'{shell} {script} 124'))


if __name__ == '__main__': unittest.main()
