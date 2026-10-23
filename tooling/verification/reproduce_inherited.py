"""Fresh inherited25/V6-16 reproduction. Never writes to or regenerates a sealed result."""
import argparse
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tooling/docs'))
from evidence_integrity import verify as verify_integrity


def groups(record, expected_count):
    values = record.get('results', [])
    names = [item.get('name') for item in values]
    if len(names) != expected_count or len(set(names)) != expected_count or any(not name for name in names):
        raise ValueError('Missing, duplicate or unexpected group cardinality')
    if not all(item.get('status') == 'PASS' for item in values):
        raise ValueError('A group failed; fresh reproduction is not a pass')
    return {item['name']: item for item in values}


def compare(expected, actual, count):
    expected, actual = copy.deepcopy(expected), copy.deepcopy(actual)
    observations = {}
    for side, record in [('sealed', expected), ('fresh', actual)]:
        observations[side] = {key: record.pop(key) for key in ('runtime', 'platform') if key in record}
        for item in record.get('results', []):
            if item.get('name') == 'unicodeCorpus':
                detail = item.get('detail', {})
                for key in ('icu', 'runtimeUnicode'):
                    if key in detail:
                        observations[side][key] = detail.pop(key)
    old, new = groups(expected, count), groups(actual, count)
    if old.keys() != new.keys():
        raise ValueError('Group identity changed')
    if {k: v for k, v in old.items()} != {k: v for k, v in new.items()}:
        # Group records contain only deterministic names/status/detail/evidence. Do not
        # recursively ignore keys called "time", "samples" or "runtime" inside assertions.
        changed = [name for name in old if old[name] != new[name]]
        raise ValueError('Deterministic group output changed: ' + ', '.join(changed))
    expected.pop('results'); actual.pop('results')
    if expected != actual:
        raise ValueError('Deterministic suite metadata changed')
    # Platform is recorded, not asserted as portable proof. Node/ICU/Unicode drift
    # still runs every assertion, but cannot qualify the sealed environment.
    differences = {key: {'sealed': value, 'fresh': observations['fresh'].get(key)}
                   for key, value in observations['sealed'].items()
                   if key != 'platform' and value != observations['fresh'].get(key)}
    return dict(groups=list(old), status='PARTIAL' if differences else 'PASS',
                observations=observations, environmentDifferences=differences)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    output = args.output_dir or ROOT / 'tests/local-evidence/inherited' / str(time.time_ns())
    output = output.resolve()
    if output == ROOT / 'tests/reviews' or (ROOT / 'tests/reviews') in output.parents:
        parser.error('Fresh output must be outside sealed review directories')
    output.mkdir(parents=True, exist_ok=False)
    result = dict(status='RUNNING', commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  startedAt=datetime.now(timezone.utc).isoformat(), ignoredTopLevel=['runtime', 'platform'],
                  scope='Immutable historical models, not production/native qualification')
    try:
        result['integrity'] = verify_integrity()
        runtime = json.loads(subprocess.check_output(['node', '-p', 'JSON.stringify(process.versions)'], text=True))
        result['runtime'] = runtime
        suites = [('inherited', 'inherited-results.json', 25, ['--inherited']), ('v6', 'results.json', 16, [])]
        for name, sealed_name, count, options in suites:
            target = output / (name + '.json')
            command = ['node', str(ROOT / 'tooling/verification/v6.mjs'), *options, '--output=' + str(target)]
            process = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=240)
            (output / (name + '.stdout')).write_bytes(process.stdout)
            (output / (name + '.stderr')).write_bytes(process.stderr)
            if process.returncode:
                raise ValueError(name + ' execution failed; raw output retained')
            expected = json.loads((ROOT / 'tests/reviews/v6-finalization-20261019' / sealed_name).read_text(encoding='utf-8'))
            actual = json.loads(target.read_text(encoding='utf-8'))
            result[name] = compare(expected, actual, count)
        result['status'] = 'PARTIAL' if any(result[name]['status'] != 'PASS' for name, *_ in suites) else 'PASS'
    except Exception as error:
        result.update(status='FAIL', error=str(error))
        raise
    finally:
        (output / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print('Evidence: ' + str(output))
    print(result['status'] + ' inherited25 + V6-16 deterministic reproduction; environment recorded')
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    sys.exit(main())
