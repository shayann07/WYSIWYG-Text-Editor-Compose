"""Independent Python decoder/encoder for the private C0 tagged-value experiment."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import hashlib
import json
import math
from pathlib import Path
import platform

FORMAT = 'c0-portable-tooling-state-1'
LIMIT = 32 * 1024 * 1024
UNDEFINED = object()


def decode(data):
    if len(data) > LIMIT:
        raise ValueError('Checkpoint byte limit')
    def invalid_constant(value):
        raise ValueError('Invalid JSON constant: ' + value)
    envelope = json.loads(data.decode('utf-8', 'strict'), parse_constant=invalid_constant,
                          parse_int=lambda value: -0.0 if value == '-0' else int(value))
    if not isinstance(envelope, list) or len(envelope) != 2 or envelope[0] != FORMAT:
        raise ValueError('Unsupported checkpoint version')
    count = 0
    def visit(node, depth=0):
        nonlocal count
        count += 1
        if depth > 128 or count > 1_000_000 or not isinstance(node, list) or not node:
            raise ValueError('Invalid checkpoint node or traversal limit')
        tag = node[0]
        if tag in ('undefined', 'negative-zero'):
            if len(node) != 1:
                raise ValueError('Invalid singleton tag')
            return UNDEFINED if tag == 'undefined' else -0.0
        if len(node) != 2:
            raise ValueError('Invalid tag arity')
        value = node[1]
        if tag == 'value':
            if value is None or isinstance(value, (str, bool)):
                return value
            if isinstance(value, (int, float)) and math.isfinite(value) and not (value == 0 and math.copysign(1, value) < 0):
                return value
            raise ValueError('Invalid scalar')
        if not isinstance(value, list):
            raise ValueError('Invalid container')
        if tag == 'array':
            return [visit(item, depth + 1) for item in value]
        if tag != 'object':
            raise ValueError('Unknown value tag')
        result = {}
        for entry in value:
            if not isinstance(entry, list) or len(entry) != 2 or not isinstance(entry[0], str) or entry[0] in result:
                raise ValueError('Invalid/duplicate record key')
            result[entry[0]] = visit(entry[1], depth + 1)
        return result
    return visit(envelope[1])


def encode(value):
    def visit(item):
        if item is UNDEFINED:
            return ['undefined']
        if isinstance(item, float) and item == 0 and math.copysign(1, item) < 0:
            return ['negative-zero']
        if item is None or isinstance(item, (str, bool, int, float)):
            return ['value', item]
        if isinstance(item, list):
            return ['array', [visit(member) for member in item]]
        if isinstance(item, dict):
            keys = sorted(item, key=lambda key: key.encode('utf-16-be', 'surrogatepass'))
            return ['object', [[key, visit(item[key])] for key in keys]]
        raise ValueError('Unsupported value')
    # Escape isolated surrogates while retaining valid Unicode scalar UTF-8.
    return json.dumps([FORMAT, visit(value)], ensure_ascii=False, allow_nan=False,
                      separators=(',', ':')).encode('utf-8', 'backslashreplace')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original = args.input.read_bytes()
    value = decode(original)
    result = encode(value)
    if len(result) > LIMIT:
        raise ValueError('Checkpoint byte limit')
    with args.output.open('xb') as target:
        target.write(result)
    undefined_count = 0
    def inspect(item):
        nonlocal undefined_count
        if item is UNDEFINED:
            undefined_count += 1
        elif isinstance(item, list):
            for member in item:
                inspect(member)
        elif isinstance(item, dict):
            for member in item.values():
                inspect(member)
    inspect(value)
    print(json.dumps({'status': 'PASS', 'python': platform.python_version(), 'undefinedValues': undefined_count,
                      'inputSha256': hashlib.sha256(original).hexdigest(),
                      'outputSha256': hashlib.sha256(result).hexdigest(),
                      'scope': 'Independent tagged-value transport, not production schema/authority qualification'}))


if __name__ == '__main__':
    main()
