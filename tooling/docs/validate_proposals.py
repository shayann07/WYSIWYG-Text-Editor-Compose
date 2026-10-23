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
    if record.get('issue') == 23:
        mapping = record.get('invariants', {})
        if set(mapping) != {f'I{number:02}' for number in range(1, 18)}:
            raise ValueError('Every I01-I17 invariant must be mapped exactly once')
        if any(not gates or len(set(gates)) != len(gates) or
               any(gate not in {f'G{n}' for n in range(9)} for gate in gates) for gates in mapping.values()):
            raise ValueError('Missing, duplicate or unknown gate in invariant mapping')
        if mapping['I15'] != ['G1', 'G8'] or mapping['I17'] != ['G4', 'G6']:
            raise ValueError('I15 storage/performance and I17 rendering/accessibility mapping required')
        if not all(record.get('clarifications', {}).get(key) for key in ('I14', 'I15', 'I17')):
            raise ValueError('Qualification boundaries required')
    return record['id']


def main():
    records = sorted((ROOT / 'docs/proposals').glob('*.json'))
    identities = [validate(json.loads(path.read_text(encoding='utf-8'))) for path in records]
    if len(set(identities)) != len(identities):
        raise ValueError('Duplicate proposal identity')
    print(f'PASS {len(identities)} proposed records; none accepted or authoritative')


if __name__ == '__main__': main()
