"""Audit the relocated local import closure without changing any historical source or seal."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
RECORD = 'docs/amendments/inherited-evidence-integrity-v1.json'
ROOTS = ['tests/reviews/v6-finalization-20261019/run-inherited.mjs',
         'tests/reviews/v6-finalization-20261019/verify.mjs',
         'tests/suites/codex/run-20261016-20/verify.mjs']
LEDGERS = [
    ('docs/archive/specs/v5.0/v5-freeze-manifest.json', 'hashes', 'original'),
    ('docs/plans/v6/v6-freeze-record.json', 'hashes', 'original'),
    ('tests/suites/codex/run-20261015-18/prior-file-hashes.json', None, 'original'),
    ('tests/suites/codex/run-20261015-19/results.json', 'sourceHashes', 'sibling'),
    ('tests/suites/codex/run-20261016-20/results.json', 'sourceHashes', 'sibling'),
    ('tests/suites/codex/run-20261016-20/gemini-input-hashes.json', None, 'original'),
]


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def dependency_identity(file):
    record = json.loads(file.read_text(encoding='utf-8'))
    if file.name == 'package-lock.json':
        # The root engine warning can evolve in #19 without changing imported dependency bytes.
        record.get('packages', {}).get('', {}).pop('engines', None)
    else:
        record.pop('engines', None)
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def inside(root, path):
    path = path.resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError('Evidence path escapes repository: ' + str(path))
    return path


def relocated(root, path):
    path = inside(root, path)
    name = path.relative_to(root.resolve()).as_posix()
    record = json.loads((root / 'docs/relocations.json').read_text(encoding='utf-8'))
    prefixes = dict(record['prefixes'])
    # Embedded reviewers computed these historical prefixes relative to tests/.
    prefixes.update({'tests/validation/': 'tests/suites/', 'tests/reviews/reviews/': 'tests/reviews/',
                     'tests/research/': 'tests/archive/research/', 'tests/docs/': 'docs/'})
    for _ in range(16):
        previous = name
        if name in record['paths']:
            name = record['paths'][name]
        else:
            for old, new in sorted(prefixes.items(), key=lambda item: -len(item[0])):
                if name.startswith(old):
                    name = new + name[len(old):]
                    break
        if name == previous:
            return inside(root, root / name)
    raise ValueError('Cyclic evidence relocation: ' + name)


def tokens(text):
    """Only import syntax is consumed; comments/literals cannot invent dependency edges.

    Nonliteral dynamic imports are rejected. Templates containing import expressions
    are refused for explicit review rather than silently losing their closure.
    """
    pattern = re.compile(r'''//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|[A-Za-z_$][\w$]*|\S''')
    for match in pattern.finditer(text):
        value = match[0]
        if value.startswith(('//', '/*')):
            continue
        if value.startswith('`'):
            if '${' in value and re.search(r'\bimport\s*\(', value):
                raise ValueError('Import inside template requires explicit closure support')
            yield 'template', value
        elif value.startswith(('"', "'")):
            # Import paths in this evidence do not use JavaScript escape tricks.
            yield 'string', value[1:-1]
        else:
            yield 'token', value


def imports(text):
    items = list(tokens(text))
    for index, (kind, value) in enumerate(items):
        if kind != 'token' or value not in ('import', 'export'):
            continue
        tail = items[index + 1:]
        if not tail or tail[0][1] == '.':
            continue
        if value == 'import' and tail[0][0] == 'string':
            yield tail[0][1]
        elif value == 'import' and tail[0][1] == '(':
            if len(tail) < 3 or tail[1][0] != 'string' or tail[2][1] not in (')', ','):
                raise ValueError('Nonliteral dynamic import cannot be integrity-qualified')
            yield tail[1][1]
        else:
            for offset, item in enumerate(tail):
                if item == ('token', ';'):
                    break
                if item == ('token', 'from'):
                    if tail[offset + 1][0] != 'string':
                        raise ValueError('Unsupported import/export source')
                    yield tail[offset + 1][1]
                    break


def closure(root, roots):
    pending = [relocated(root, root / name) for name in roots]
    files, external = {}, set()
    while pending:
        file = pending.pop()
        name = file.relative_to(root.resolve()).as_posix()
        if name in files:
            continue
        if not file.is_file():
            raise ValueError('Imported evidence missing: ' + name)
        dependencies = list(imports(file.read_text(encoding='utf-8')))
        files[name] = []
        for specifier in dependencies:
            if specifier.startswith('.'):
                target = relocated(root, file.parent / specifier)
                if 'node_modules' in target.parts:
                    external.add('npm:' + target.relative_to(root.resolve()).as_posix())
                    continue
                files[name].append(target.relative_to(root.resolve()).as_posix())
                pending.append(target)
            elif specifier.startswith('node:') or specifier == 'yjs':
                external.add(specifier)
            else:
                raise ValueError('Unqualified external/absolute import: ' + specifier)
    return files, sorted(external)


def historical(root):
    matches, sources = {}, {}
    for name, field, addressing in LEDGERS:
        file = root / name
        sources[name] = digest(file)
        record = json.loads(file.read_text(encoding='utf-8'))
        values = record[field] if field else record
        for original, sha in values.items():
            address = original.replace('\\', '/')
            target = relocated(root, file.parent / address if addressing == 'sibling' else root / address)
            relative = target.relative_to(root.resolve()).as_posix()
            matches.setdefault(relative, []).append({'ledger': name, 'key': original, 'sha256': sha})
    return matches, sources


def verify(root=ROOT):
    root = root.resolve()
    record = json.loads((root / RECORD).read_text(encoding='utf-8'))
    if record['version'] != 1 or record['roots'] != ROOTS:
        raise ValueError('Unexpected integrity record version/entry points')
    edges, external = closure(root, record['roots'])
    if edges != record['imports'] or external != record['externalImports']:
        raise ValueError('Imported closure changed without integrity coverage')
    for name, expected in record['runtimeDependencyLocks'].items():
        if dependency_identity(inside(root, root / name)) != expected:
            raise ValueError('External dependency lock changed: ' + name)
    histories, sources = historical(root)
    if sources != record['ledgerSources']:
        raise ValueError('Historical provenance ledger changed')
    gemini = json.loads((root / LEDGERS[-1][0]).read_text(encoding='utf-8'))
    review_files = {relocated(root, root / p.replace('\\', '/')).relative_to(root).as_posix() for p in gemini}
    if set(record['files']) != set(edges) | review_files:
        raise ValueError('Missing/unexpected imported or reviewer integrity coverage')
    for name, entry in record['files'].items():
        file = inside(root, root / name)
        if digest(file) != entry['sha256']:
            raise ValueError('Covered evidence changed: ' + name)
        observed = [value for value in histories.get(name, []) if value['sha256'] == entry['sha256']]
        if entry['historicalMatches'] != observed:
            raise ValueError('Historical provenance mismatch: ' + name)
        if entry['provenance'] != ('MATCHED_EXISTING_LEDGER' if observed else 'CURRENT_CAPTURE_ONLY'):
            raise ValueError('False historical integrity claim: ' + name)
    return {'status': 'PASS', 'localImportedModules': len(edges), 'coveredFiles': len(record['files']),
            'currentCaptureOnly': [p for p, e in record['files'].items() if e['provenance'] == 'CURRENT_CAPTURE_ONLY'],
            'scope': 'Existing-ledger correspondence plus prospective closure integrity; not retroactive historical proof'}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
