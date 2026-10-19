"""S0 package compatibility experiment. Private wire shape; not a production format."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import copy
import hashlib
import io
import json
import struct
import zipfile

FORMAT = 's0-experiment-1'
MAX_BUNDLE_BYTES = 8_000_000


def read_astra(data):
    if len(data) < 44 or hashlib.sha256(data[:-32]).digest() != data[-32:]:
        raise ValueError('Invalid prototype checksum/length')
    magic, version, size = struct.unpack_from('>III', data)
    if magic != 0x41535450 or version not in (1, 2, 3) or size > 100_000:
        raise ValueError('Unsupported prototype format')
    header, stride = (12, 3) if version == 1 else (13, 4)
    if len(data) != header + stride * size + 32:
        raise ValueError('Invalid prototype count')
    start = 0 if version == 1 else data[12]
    if start > 3:
        raise ValueError('Invalid initial style')
    units = []
    for i in range(size):
        at = header + stride * i
        char, kind = struct.unpack_from('>HB', data, at)
        bits = 0 if version == 1 else data[at + 3]
        if kind not in (0, 1) or bits > 3 or (kind == 1 and char != 10):
            raise ValueError('Invalid prototype unit')
        units.append([char, kind, bits if kind == 0 else 0, bits if kind == 1 else 0])
    return {'startStyle': start, 'units': units}


def write_astra(document):
    payload = struct.pack('>III', 0x41535450, 3, len(document['units'])) + bytes([document['startStyle']])
    for char, kind, style, paragraph in document['units']:
        payload += struct.pack('>HBB', char, kind, paragraph if kind == 1 else style)
    data = payload + hashlib.sha256(payload).digest()
    if read_astra(data) != document:
        raise ValueError('Unsupported reverse migration')
    return data


def validate(bundle):
    if bundle.get('format') != FORMAT:
        raise ValueError('UnsupportedMigration')
    if bundle.get('requiredFeatures', []) != []:
        raise ValueError('UnsupportedFeature')
    sections, flows, resources = bundle['sections'], bundle['flows'], bundle['resources']
    if not 1 <= len(sections) <= 32 or not 1 <= len(flows) <= 64:
        raise ValueError('Experiment bounds exceeded')
    section_ids = [s['id'] for s in sections]
    if len(set(section_ids)) != len(section_ids):
        raise ValueError('Duplicate section identity')
    for section in sections:
        if 'body' not in section['flows']:
            raise ValueError('Missing section body')
        seen = {section['id']}
        parent = section['parent']
        while parent is not None:
            if parent in seen or parent not in section_ids:
                raise ValueError('Invalid section ancestry')
            seen.add(parent)
            parent = sections[section_ids.index(parent)]['parent']
        for role, flow in section['flows'].items():
            if flow not in flows or flows[flow]['owner'] != section['id'] or flows[flow]['role'] != role:
                raise ValueError('Invalid section flow mapping')
        for variant, flow in section['headerVariants'].items():
            if variant not in ('first', 'even', 'odd') or flow not in flows or flows[flow]['role'] != 'header' or flows[flow]['owner'] != section['id']:
                raise ValueError('Invalid header variant mapping')
    unit_ids = set()
    for flow_id, flow in flows.items():
        if flow['owner'] not in section_ids or flow['role'] not in ('body', 'header', 'footer', 'footnote', 'cell'):
            raise ValueError('Invalid flow owner/role')
        read_astra(write_astra(flow['document']))
        ids = flow['unitIds']
        if len(ids) != len(flow['document']['units']) or any(i in unit_ids for i in ids) or len(set(ids)) != len(ids):
            raise ValueError('Invalid persistent unit identities')
        unit_ids.update(ids)
    cell_ids, cell_flows = set(), set()
    for table in bundle['tables']:
        occupied = set()
        for cell in table['cells']:
            if cell['id'] in cell_ids or cell['flow'] in cell_flows:
                raise ValueError('Duplicated cell identity/flow')
            cell_ids.add(cell['id']); cell_flows.add(cell['flow'])
            if cell['flow'] not in flows or flows[cell['flow']]['role'] != 'cell':
                raise ValueError('Invalid rich cell flow')
            if cell['rowSpan'] < 1 or cell['colSpan'] < 1:
                raise ValueError('Invalid cell span')
            for r in range(cell['row'], cell['row'] + cell['rowSpan']):
                for c in range(cell['col'], cell['col'] + cell['colSpan']):
                    if not (0 <= r < table['rows'] and 0 <= c < table['cols']) or (r, c) in occupied:
                        raise ValueError('Overlapping or out-of-grid cell')
                    occupied.add((r, c))
        if len(occupied) != table['rows'] * table['cols']:
            raise ValueError('Unowned table slot')
    for note in bundle['footnotes']:
        if note['sourceUnit'] not in flows[note['sourceFlow']]['unitIds'] or flows[note['targetFlow']]['role'] != 'footnote':
            raise ValueError('Invalid footnote reference')
    for anchor in bundle['anchors']:
        if anchor['state'] == 'attached':
            if not set(anchor['units']) <= set(flows[anchor['flow']]['unitIds']):
                raise ValueError('Missing attached anchor identity')
        elif anchor['state'] != 'orphaned' or not anchor['context'] or not anchor['units']:
            raise ValueError('Invalid explicit orphan')
    for review in bundle['review']:
        if review['state'] not in ('pending', 'accepted', 'rejected', 'superseded', 'conflicted'):
            raise ValueError('Invalid review state')
        if review['flow'] not in flows or review['preimage'] is None:
            raise ValueError('Missing retained proposal preimage')
        if any(ref not in resources for ref in review['resources']):
            raise ValueError('Missing retained review resource')
    for value in resources.values():
        if hashlib.sha256(bytes.fromhex(value['hex'])).hexdigest() != value['sha256']:
            raise ValueError('Resource identity mismatch')
    for extension in bundle['opaqueExtensions']:
        if extension['required'] or not extension['namespace'] or not isinstance(extension['hex'], str):
            raise ValueError('Unsupported mandatory extension')
        bytes.fromhex(extension['hex'])
    return bundle


def encode(bundle):
    validate(bundle)
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        entry = zipfile.ZipInfo('manifest.json', date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, json.dumps(bundle, ensure_ascii=True, sort_keys=True, separators=(',', ':')))
    return output.getvalue()


def decode(data):
    if len(data) > MAX_BUNDLE_BYTES:
        raise ValueError('Package bound exceeded')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.namelist() != ['manifest.json']:
            raise ValueError('Unsupported package entries')
        if archive.getinfo('manifest.json').file_size > MAX_BUNDLE_BYTES:
            raise ValueError('Expanded package bound exceeded')
        def unique_object(pairs):
            value = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError('Duplicate manifest key')
                value[key] = item
            return value
        return validate(json.loads(archive.read('manifest.json'), object_pairs_hook=unique_object))


def specimen(document):
    def flow(role, name, owner='section-1'):
        return {'role': role, 'owner': owner, 'document': copy.deepcopy(document),
                'unitIds': [f'{name}:u:{i}' for i in range(len(document['units']))]}
    flows = {name: flow(role, name, owner) for name, role, owner in [
        ('body-1', 'body', 'section-1'), ('body-2', 'body', 'section-2'),
        ('header-1', 'header', 'section-1'), ('footer-1', 'footer', 'section-1'),
        ('note-1', 'footnote', 'section-1'), ('cell-1', 'cell', 'section-1'),
        ('cell-2', 'cell', 'section-1'), ('cell-3', 'cell', 'section-1')]}
    resource = b'opaque-resource-\x00\xff-Urdu-specimen'
    return {'format': FORMAT, 'requiredFeatures': [], 'documentId': 'document-s0',
        'sections': [{'id': 'section-1', 'parent': None, 'flows': {'body': 'body-1', 'header': 'header-1', 'footer': 'footer-1'},
                      'headerVariants': {'first': 'header-1', 'even': 'header-1', 'odd': 'header-1'}},
                     {'id': 'section-2', 'parent': 'section-1', 'flows': {'body': 'body-2'}, 'headerVariants': {}}],
        'flows': flows,
        'tables': [{'id': 'table-1', 'rows': 2, 'cols': 2, 'cells': [
            {'id': 'c1', 'row': 0, 'col': 0, 'rowSpan': 2, 'colSpan': 1, 'flow': 'cell-1'},
            {'id': 'c2', 'row': 0, 'col': 1, 'rowSpan': 1, 'colSpan': 1, 'flow': 'cell-2'},
            {'id': 'c3', 'row': 1, 'col': 1, 'rowSpan': 1, 'colSpan': 1, 'flow': 'cell-3'}]}],
        'footnotes': [{'sourceFlow': 'body-1', 'sourceUnit': 'body-1:u:0', 'targetFlow': 'note-1'}],
        'anchors': [{'id': 'comment-1', 'flow': 'body-1', 'units': ['body-1:u:0'], 'state': 'attached', 'context': 'Urdu first unit'},
                    {'id': 'deleted-comment', 'flow': 'body-1', 'units': ['deleted-unit'], 'state': 'orphaned', 'context': 'Retained deleted Urdu context'}],
        'resources': {'resource-1': {'hex': resource.hex(), 'sha256': hashlib.sha256(resource).hexdigest()}},
        'review': [{'id': 'suggestion-1', 'state': 'pending', 'flow': 'body-1', 'preimage': copy.deepcopy(document), 'resources': ['resource-1']}],
        'opaqueExtensions': [{'namespace': 'example.safe.unknown', 'required': False, 'hex': b'unknown\x00\xffpayload'.hex()}]}
