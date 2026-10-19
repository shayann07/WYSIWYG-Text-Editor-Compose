"""Audit recorded real Windows keyboard evidence; does not replay/inject input."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

ROOT = _REPO_ROOT


def identity(path):
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'sizeBytes': path.stat().st_size}


def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True); parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--before-config', type=Path, required=True); parser.add_argument('--after-config', type=Path, required=True)
    args = parser.parse_args(); directory = args.directory.resolve(); build = args.build_dir.resolve()
    report = {'status': 'RUNNING', 'auditedAt': datetime.now(timezone.utc).isoformat(), 'checks': [],
              'scope': 'Audit of saved @oai/sky real native keyboard trace at the identified historical fresh build; not a new GUI run or full U1/U2/native IME gate'}
    def check(name, evidence): report['checks'].append({'name': name, 'status': 'PASS', 'evidence': evidence})
    try:
        manifest = read(build / 'build-identity.json'); assert manifest['status'] == 'BUILT'
        # Historical GUI evidence stays bound to its actual artifacts. Source may
        # have gained later tooling; never relabel it as a fresh current-source run.
        for name, expected in manifest['artifacts'].items():
            actual = identity(build / name)
            assert actual['sha256'] == expected['sha256'] and actual['sizeBytes'] == expected['sizeBytes'], name
        report['observedBuildIdentity'] = identity(build / 'build-identity.json')
        report['observedInputHelperClass'] = identity(build.parent / 'native-input-classes/OwnedKeyboardLayout.class')
        product = ['DesktopEditor.kt', 'EditorEngine.kt', 'Typography.kt', 'PageFragments.kt']
        for name in product:
            relative = 'apps/desktop-workspace/src/main/kotlin/editor/' + name
            assert identity(ROOT / relative)['sha256'] == manifest['inputs'][relative]['sha256'], 'Product source changed after observed run: ' + name
        check('Observed native build artifacts and relevant current editor sources match', {'build': str(build), 'productFiles': product})
        trace = directory / 'urdu-english-events.jsonl'
        events = [json.loads(line) for line in trace.read_text(encoding='utf-8').splitlines()]
        assert all(events[i]['sequence'] < events[i + 1]['sequence'] for i in range(len(events) - 1))
        pressed = [(i, e) for i, e in enumerate(events) if e['kind'] == 'key-pressed']
        keys = [(i, int(re.search(r'code=(\d+)', e['detail'])[1])) for i, e in pressed if 'modifiers=0;' in e['detail']]
        assert [code for _, code in keys[:8]] == [75, 85, 74, 73, 32, 65, 66, 67]
        expected = 'کتاب abc'
        for step, (at, _) in enumerate(keys[:8]):
            end = keys[step + 1][0] if step + 1 < len(keys) else len(events)
            changed = [e for e in events[at + 1:end] if e['kind'] == 'engine' and e['visible'] == expected[:step + 1]]
            assert changed and changed[0]['committed'] == expected[:step + 1] and changed[0]['marked'] is None
        word = read(directory / 'urdu-word.json'); mixed = read(directory / 'mixed-redone.json')
        assert word['text'] == 'کتاب' and word['keyTypedEvents'] == 4 and word['imeTextEvents'] == 0
        assert mixed['text'] == expected and mixed['keyTypedEvents'] == 8 and mixed['imeTextEvents'] == 0
        assert not any('paste' in (e['kind'] + e['detail']).lower() for e in events)
        check('Physical K/U/J/I and English keys produce exact raw text without Paste or injected IME events',
              {'wordUnits': [ord(c) for c in word['text']], 'mixedText': expected, 'nativeKeyTyped': 8, 'imeCallbacks': 0})
        def next_engine(at): return next(e for e in events[at + 1:] if e['kind'] == 'engine')
        right = next(at for at, code in keys if code == 39); left = next(at for at, code in keys if code == 37)
        before = events[right]; moved = next_engine(right); returned = next_engine(left)
        assert moved['selection'] != before['selection'] and returned['selection'] == before['selection'] == [8, 8]
        assert all(e['visible'] == expected and e['documentRevision'] == before['documentRevision'] for e in [moved, returned])
        check('Native bidi movement round trips without content/history mutation', {'observedPositions': [before['selection'], moved['selection'], returned['selection']], 'scope': 'One observed edge, not a complete independent bidi oracle'})
        formatted = next(i for i, e in enumerate(events) if e['visible'] == expected and e['styles'] == [1] * 8 and e['selection'] == [0, 8])
        bold = events[formatted]
        undone = next(i for i, e in enumerate(events[formatted + 1:], formatted + 1) if e['visible'] == expected and e['styles'] == [0] * 8 and e['redo'] == 1)
        plain = events[undone]
        redone = next(e for e in events[undone + 1:] if e['visible'] == expected and e['styles'] == [1] * 8 and e['redo'] == 0)
        assert bold['undo'] == plain['undo'] + 1 == redone['undo'] and plain['selection'] == redone['selection'] == [0, 8]
        assert {90, 89}.issubset({int(re.search(r'code=(\d+)', e['detail'])[1]) for _, e in pressed if 'modifiers=128;' in e['detail']})
        check('Native mixed-script formatting and physical Undo/Redo restore exact flags/selection', {'units': 8, 'oneFormatTransaction': True})
        restored = read(directory / 'urdu-restore.json'); state = restored['state']
        assert restored['status'] == 'Restored' and restored['unloads'] == [{'unloaded': True, 'handle': '0x0000000004200420'}]
        assert state['loaded'] == state['originalLoaded'] == ['0x0000000004090409']
        assert state['caller'] == state['gui'] == state['default'] == state['originalDefault'] == '0x0000000004090409'
        before_config, after_config = read(args.before_config), read(args.after_config)
        assert before_config['preload'] == after_config['preload'] and before_config['substitutes'] == after_config['substitutes']
        check('Original loaded/current/default input language and user preload/substitute settings are restored', {'restored': state, 'configurationUnchanged': True})
        cleanup = read(directory / 'diagnostics.json'); assert cleanup['text'] == '' and cleanup['undo'] == 0
        check('Disposable native document cleaned to its initial empty state', {'text': '', 'undo': 0})
        report['nativeLifecycleQualified'] = mixed['nativeQualified']
        report['limitations'] = ['No Japanese IME qualification from this keyboard run', 'No full U1/U2 or native retirement fence qualification',
                                 'JVM locale diagnostic stayed en_US while the observed native GUI HKL was Urdu; do not infer OS availability from that field']
        report['artifacts'] = {p.name: identity(p) for p in [trace, directory / 'urdu-word.json', directory / 'mixed-redone.json', directory / 'urdu-restore.json', args.before_config, args.after_config]}
        report['status'] = 'RECORDED_NATIVE_URDU_KEYBOARD_SMOKE_PASS'
    except BaseException as error:
        report.update(status='FAIL', error=repr(error))
    target = directory / ('qualification-audit-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f') + '.json')
    with target.open('x', encoding='utf-8') as file: file.write(json.dumps(report, indent=2, ensure_ascii=True) + '\n')
    print(report['status'] + ': ' + str(target))
    return 0 if report['status'] == 'RECORDED_NATIVE_URDU_KEYBOARD_SMOKE_PASS' else 1


if __name__ == '__main__': raise SystemExit(main())
