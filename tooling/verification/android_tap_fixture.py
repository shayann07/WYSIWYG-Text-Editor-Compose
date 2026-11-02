"""Own one hashed APK-packaged shell fixture. No touch is sent by installation/probing."""
from contextlib import contextmanager
import re
import zipfile

ASSET = 'assets/composedoc-tap-v1.sh'


@contextmanager
def owned_tap_fixture(run, test_apk, output, run_id, receipt):
    if not re.fullmatch(r'adapter-[0-9]{1,24}', run_id):
        raise ValueError('Invalid owned tap generation')
    with zipfile.ZipFile(test_apk) as archive:
        info = archive.getinfo(ASSET)
        if not 0 < info.file_size <= 4096:
            raise ValueError('Invalid tap fixture size')
        data = archive.read(info)
    import hashlib
    directory = '/data/local/tmp/composedoc-tap-v1-' + run_id
    path = directory + '/tap.sh'
    metadata = dict(path=path, sha256=hashlib.sha256(data).hexdigest(), status='PREPARING')
    receipt['tapFixture'] = metadata
    local = output/'composedoc-tap-v1.sh'
    local.write_bytes(data)
    exists = run('tap-fixture-absent', 'shell', 'test', '-e', directory, allowed_codes=(0, 1), with_status=True)
    if exists.returncode != 1 or exists.stderr:
        raise ValueError('Refuse an existing or unobservable tap fixture')
    pushed = False
    original = None
    try:
        run('tap-fixture-mkdir', 'shell', 'mkdir', directory)
        pushed = True  # A failed push may still leave this uniquely captured owned path.
        run('tap-fixture-push', 'push', str(local), path)
        if run('tap-fixture-verify', 'exec-out', 'cat', path) != data:
            raise ValueError('Tap fixture publication differs from packaged bytes')
        capability = run('tap-fixture-probe', 'shell', '/system/bin/sh', path, '--probe', timeout=10)
        if capability.strip() not in (b'COMPOSEDOC_TAP_CAPABILITY=default', b'COMPOSEDOC_TAP_CAPABILITY=display'):
            raise ValueError('No admitted input/timeout capability; no touch requested')
        metadata.update(status='READY', capability=capability.decode().strip())
        yield path
    except BaseException as failure:
        original = failure
        raise
    finally:
        if pushed:
            try:
                current = run('tap-fixture-before-remove', 'exec-out', 'cat', path)
                if current != data:
                    raise ValueError('Owned fixture changed; preserve it instead of removing different bytes')
                run('tap-fixture-remove', 'shell', 'rm', path)
                run('tap-fixture-rmdir', 'shell', 'rmdir', directory)
                removed = run('tap-fixture-removed', 'shell', 'test', '-e', path,
                              allowed_codes=(0, 1), with_status=True)
                if removed.returncode != 1 or removed.stderr:
                    raise ValueError('Owned fixture removal not verified')
                metadata['cleanup'] = 'REMOVED'
            except Exception as failure:
                metadata['cleanupError'] = repr(failure)
                if original is None:
                    raise
                original.add_note('Tap fixture cleanup failed: ' + repr(failure))
