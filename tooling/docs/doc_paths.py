"""Active ComposeDoc paths; immutable originals resolve separately."""
from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[2]

def relocation_record():
    return json.loads((ROOT / 'docs/relocations.json').read_text(encoding='utf-8'))

def relocations():
    return relocation_record()['paths']

def resolve(name):
    name = str(name).replace('\\', '/')
    record = relocation_record()
    seen = set()
    while name not in seen:
        seen.add(name)
        mapped = record['paths'].get(name)
        if mapped is None:
            for old, new in sorted(record.get('prefixes', {}).items(), key=lambda p: -len(p[0])):
                if name.startswith(old):
                    mapped = new + name[len(old):]
                    break
        if mapped is None or mapped == name:
            break
        name = mapped
    return ROOT / name

def original(name):
    record = json.loads((ROOT / relocation_record()['amendment']).read_text(encoding='utf-8'))
    for entry in record['protected']:
        if entry['originalPath'] == name:
            return ROOT / entry['path']
    return resolve(name)

def canonical(name):
    return resolve(name).relative_to(ROOT).as_posix()
