"""Verify original sealed bytes through the owner-authorized relocation record."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
from urllib.parse import unquote
from doc_paths import ROOT, resolve, original, relocation_record

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    relocation = relocation_record()
    amendment = json.loads((ROOT / relocation['amendment']).read_text(encoding='utf-8'))
    for entry in amendment['originalManifests']:
        assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
    expected = {}
    counts = []
    for name in ['v5-freeze-manifest.json', 'v5.1-spec-manifest.json', 'docs/plans/v6/v6-freeze-record.json']:
        entries = json.loads(resolve(name).read_text(encoding='utf-8'))['hashes']
        counts.append(len(entries))
        expected.update(entries)
    assert counts == [45, 3, 33], counts
    records = {e['originalPath']: e for e in amendment['protected']}
    assert set(records) == set(expected), 'Incomplete frozen original ledger'
    for name, value in expected.items():
        assert records[name]['sha256'] == value
        assert digest(original(name)) == value, name
    supplement = json.loads((ROOT / 'docs/amendments/composedoc-relocation-v1-supplement.json').read_text(encoding='utf-8'))
    for entry in supplement['relocations']:
        target = ROOT / entry['to']
        for relative, value in entry['hashes'].items():
            assert digest(target / relative if target.is_dir() else target) == value
    previous = json.loads((ROOT / amendment['previousRelocationRecord']).read_text(encoding='utf-8'))
    snapshot = previous['argonUploadedSnapshot']
    for name, value in snapshot['hashes'].items():
        assert digest(ROOT / snapshot['directory'] / name) == value, name
    for old, new in previous['paths'].items():
        if new.startswith(('docs/archive/', 'docs/plans/v5.2/')):
            assert digest(resolve(new)) == previous['originalHashes'][old], new
    assert resolve('plan-v6.md').read_bytes() == resolve('editor-v6-standalone.md').read_bytes()
    frozen_paths = {original(n).resolve() for n in expected}
    current = [ROOT / 'README.md', ROOT / 'AGENTS.md']
    # Archive indexes and unsealed archived guides are navigation too; relocated originals keep their historical links.
    relocated = {resolve(new).resolve() for new in previous['paths'].values() if new.startswith(('docs/archive/', 'docs/plans/v5.2/'))}
    snapshot_dir = (ROOT / snapshot['directory']).resolve()
    current += [p for p in (ROOT / 'docs').rglob('*.md') if p.resolve() not in frozen_paths | relocated and snapshot_dir not in p.resolve().parents and 'v5.2' not in p.parts]
    # Ignored local-evidence receipts exist only on the machine that produced them;
    # every other link must reach a file that a fresh clone also has.
    local_evidence = [(ROOT / d).resolve() for d in ('tests/local-evidence', 'apps/desktop-workspace/local-evidence')]
    bad = []; checked = 0; machine_local = 0
    for file in current:
        for match in re.finditer(r'!?\[[^\]\n]*\]\((<[^>\n]+>|[^)\n]+)\)', file.read_text(encoding='utf-8')):
            target = match[1].strip('<>').split('#', 1)[0]
            if not target or re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target) or target.startswith('/'):
                continue
            resolved = (file.parent / unquote(target)).resolve()
            if any(resolved == d or d in resolved.parents for d in local_evidence):
                machine_local += 1
                continue
            checked += 1
            ignored = resolved.exists() and subprocess.run(['git', '-C', str(ROOT), 'check-ignore', '-q', str(resolved)]).returncode == 0
            if not resolved.exists() or ignored:
                bad.append({'file': file.relative_to(ROOT).as_posix(), 'target': target})
    print(json.dumps({'historicalHashes': sum(counts[:2]), 'sealedV6Hashes': counts[2], 'originalManifestsExact': True, 'reviewSnapshotExact': True, 'activeLinksChecked': checked, 'machineLocalEvidenceLinks': machine_local, 'brokenActiveLinks': bad, 'historicalNavigation': 'Original sealed/archive link text is immutable; use docs/relocations.json for historical destinations'}, indent=2))
    assert not bad, 'Broken active navigation links'

if __name__ == '__main__':
    main()
