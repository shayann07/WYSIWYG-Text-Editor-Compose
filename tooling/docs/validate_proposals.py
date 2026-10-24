"""Validate proposed additive records without accepting them as specification authority."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def validate(record, root=ROOT):
    if record.get('schemaVersion') != 1 or record.get('status') != 'PROPOSED':
        raise ValueError('Expected version 1 PROPOSED record')
    if record.get('authoritative') is not False or record.get('ownerAcceptance') is not None:
        raise ValueError('Proposal cannot self-approve or become authoritative')
    if record.get('supersedesFrozenBytes') is not False or not record.get('id'):
        raise ValueError('Proposal cannot rewrite sealed bytes; identity required')
    references = record.get('references', [])
    if not references:
        raise ValueError('Missing source references')
    for reference in references:
        path = (root / reference).resolve()
        if root.resolve() not in path.parents or not path.is_file():
            raise ValueError('Missing/escaping proposal reference: ' + reference)
    if record.get('issue') == 22:
        if record.get('gate') != 'G1' or not record.get('definition') or not record.get('preservedMeaning'):
            raise ValueError('Missing unified G1 definition and preserved meaning')
    return record['id']


def main():
    records = sorted((ROOT / 'docs/proposals').glob('*.json'))
    identities = [validate(json.loads(path.read_text(encoding='utf-8'))) for path in records]
    if len(set(identities)) != len(identities):
        raise ValueError('Duplicate proposal identity')
    print(f'PASS {len(identities)} proposed records; none accepted or authoritative')


if __name__ == '__main__': main()
