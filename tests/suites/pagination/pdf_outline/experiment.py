"""Rejected/accepted evidence for a vector-outline + mapped logical-text PDF candidate.

No independent paragraph shaping or pagination: all shapes and geometry come from
the captured Compose page plan. This is a tooling experiment, not an adopted backend.
"""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

import pymupdf as pdf
from PIL import Image, ImageChops, ImageStat

ROOT = _REPO_ROOT
FONT = ROOT / 'tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf'
TOLERANCE_PT = .25
MAX_MEAN_PIXEL_ERROR = 3.0
MAX_LARGE_PIXEL_DIFFERENCE_FRACTION = .025


def raw_string(units):
    data = b''.join(int(u).to_bytes(2, 'little') for u in units)
    # The document stays untouched; this candidate refuses malformed export units.
    return data.decode('utf-16le', errors='strict')


def run(pages, output):
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'gate': 'GP0', 'backend': 'PyMuPDF vector SVG outlines + invisible canonical glyphs in recorded visual order',
              'version': pdf.VersionBind, 'geometryTolerancePt': TOLERANCE_PT,
              'rasterTolerance': {'meanError': MAX_MEAN_PIXEL_ERROR, 'largeDifferenceFraction': MAX_LARGE_PIXEL_DIFFERENCE_FRACTION},
              'pages': [], 'gp0Qualified': False}
    report['sourceSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    target = output / 'results.json'
    def save():
        target.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        plan = json.loads((pages / 'results.json').read_text(encoding='utf-8'))
        report['sourcePlanSha256'] = hashlib.sha256((pages / 'results.json').read_bytes()).hexdigest()
        font_manifest = json.loads((ROOT / 'tests/suites/urdu/u0/fonts.json').read_text(encoding='utf-8'))
        expected_font = next(f['sha256'] for f in font_manifest['candidateFonts'] if f['fileName'] == FONT.name)
        assert hashlib.sha256(FONT.read_bytes()).hexdigest() == expected_font
        report['fontSha256'] = expected_font
        font = pdf.Font(fontfile=str(FONT))
        document = pdf.open()
        expected_chars = []
        expected_text = []
        for page_index in range(plan['pageCount']):
            svg_path = pages / f'page-{page_index + 1}.outlines.svg'
            svg = ET.fromstring(svg_path.read_bytes())
            ET.register_namespace('', 'http://www.w3.org/2000/svg')
            ET.register_namespace('xlink', 'http://www.w3.org/1999/xlink')
            assert not any(e.tag.rsplit('}', 1)[-1] == 'text' for e in svg.iter()), 'Outline input unexpectedly contains reshaped text'
            assert any(e.tag.rsplit('}', 1)[-1] == 'path' for e in svg.iter()), 'No vector paths'
            width, height = plan['pageSize']
            svg.set('width', f'{width}pt'); svg.set('height', f'{height}pt'); svg.set('viewBox', f'0 0 {width} {height}')
            svg_source = ET.tostring(svg, encoding='utf-8')
            vector = pdf.open(stream=svg_source, filetype='svg')
            vector_pdf = pdf.open(stream=vector.convert_to_pdf(), filetype='pdf')
            assert abs(vector_pdf[0].rect.width - width) < .001 and abs(vector_pdf[0].rect.height - height) < .001
            page = document.new_page(width=width, height=height)
            page.show_pdf_page(page.rect, vector_pdf, 0, keep_proportion=False)
            page.insert_font(fontname='PinnedNastaliq', fontfile=str(FONT))
            characters = []
            logical_lines = []
            for run in (r for r in plan['semanticRuns'] if r['page'] == page_index):
                logical = raw_string(run['utf16'])
                logical_lines.append(logical)
                size = run['fontSize']
                for char in sorted(run['characters'], key=lambda c: c['bbox'][0]):
                    text = raw_string([char['unit']])
                    if text in '\r\n':
                        continue
                    if not font.has_glyph(ord(text)):
                        raise ValueError(f'Unsupported glyph U+{ord(text):04X}; no silent fallback')
                    box = char['bbox']
                    advance = font.text_length(text, fontsize=size)
                    target_width = box[2] - box[0]
                    scale = target_width / advance if advance > .0001 else 1
                    origin = pdf.Point(box[0], run['baseline'])
                    inserted = page.insert_text(origin, text, fontsize=size, fontname='PinnedNastaliq',
                        render_mode=3, morph=(origin, pdf.Matrix(scale, 1)))
                    if inserted != 1:
                        raise ValueError('Text layer insertion failed; no truncated semantic layer accepted')
                    characters.append({'c': text, 'bbox': box, 'flow': run['flow'], 'offset': run['offset']})
            expected_chars.append(characters)
            expected_text.append(logical_lines)
            vector_pdf.close(); vector.close()
        document.set_metadata({'title': 'Urdu page-plan export experiment', 'subject': 'Experimental vector outlines with canonical text mapping; GP0 not qualified'})
        document.subset_fonts()
        destination = output / 'urdu-page-plan-experiment.pdf'
        document.save(destination, garbage=4, deflate=True)
        document.close()
        with pdf.open(destination) as actual:
            assert actual.page_count == plan['pageCount']
            for page_index, page in enumerate(actual):
                pixmap = page.get_pixmap(matrix=pdf.Matrix(1, 1), alpha=False)
                render_path = output / f'page-{page_index + 1}.png'
                pixmap.save(render_path)
                raster = Image.open(render_path).convert('RGB')
                preview = Image.open(pages / f'page-{page_index + 1}.png').convert('RGB')
                assert raster.size == preview.size
                diff = ImageChops.difference(raster, preview)
                mean_error = sum(ImageStat.Stat(diff).mean) / 3
                pixels = diff.get_flattened_data() if hasattr(diff, 'get_flattened_data') else diff.getdata()
                large = sum(1 for rgb in pixels if max(rgb) > 32) / (raster.width * raster.height)
                text = page.get_text(sort=False)
                (output / f'page-{page_index + 1}.txt').write_text(text, encoding='utf-8')
                raw = page.get_text('rawdict', flags=pdf.TEXTFLAGS_RAWDICT | pdf.TEXT_IGNORE_ACTUALTEXT | pdf.TEXT_INHIBIT_SPACES, sort=False)
                observed = [c for block in raw['blocks'] if 'lines' in block for line in block['lines'] for span in line['spans'] for c in span['chars']]
                expected = expected_chars[page_index]
                exact_raw = ''.join(c['c'] for c in observed) == ''.join(c['c'] for c in expected)
                counts_exact = Counter(c['c'] for c in observed) == Counter(c['c'] for c in expected)
                geometry_error = None
                if counts_exact:
                    remaining = {}
                    for c in observed:
                        remaining.setdefault(c['c'], []).append(c['bbox'])
                    errors = []
                    for c in expected:
                        boxes = remaining[c['c']]
                        at = min(range(len(boxes)), key=lambda i: max(abs(a - b) for a, b in zip(c['bbox'], boxes[i])))
                        errors.append(max(abs(a - b) for a, b in zip(c['bbox'], boxes.pop(at))))
                    geometry_error = max(errors, default=0)
                # Structured extraction may introduce line separators; units inside each
                # recorded run must be retained exactly and appear in logical run order.
                actual_compact = text.replace('\n', '').replace('\r', '')
                expected_compact = ''.join(expected_text[page_index]).replace('\n', '').replace('\r', '')
                image_regions = []
                for resource in (r for r in plan['images'] if r['page'] == page_index):
                    region = tuple(round(v) for v in resource['bbox'])
                    region_diff = ImageChops.difference(raster.crop(region), preview.crop(region))
                    image_regions.append(sum(ImageStat.Stat(region_diff).mean) / 3)
                entry = {'page': page_index + 1, 'meanPixelError': mean_error, 'largePixelDifferenceFraction': large,
                         'rasterWithinDeclaredTolerance': mean_error <= MAX_MEAN_PIXEL_ERROR and large <= MAX_LARGE_PIXEL_DIFFERENCE_FRACTION,
                         'canonicalRunOrderExactIgnoringLineSeparators': actual_compact == expected_compact,
                         'rawCharacterOrderExact': exact_raw, 'rawCharacterMultisetExact': counts_exact,
                         'resourceImageRegionErrors': image_regions, 'resourceImagesMatch': all(e <= MAX_MEAN_PIXEL_ERROR for e in image_regions),
                         'expectedCharacters': len(expected), 'observedCharacters': len(observed),
                         'maxTextBoxErrorPt': geometry_error, 'textGeometryWithinTolerance': geometry_error is not None and geometry_error <= TOLERANCE_PT,
                         'vectorPaths': len(page.get_drawings()), 'images': len(page.get_images()), 'fonts': len(page.get_fonts())}
                entry['searchHits'] = {word: len(page.search_for(word)) for word in ['English', 'اردو', 'کتاب']}
                report['pages'].append(entry); save()
        qualified_candidate = all(p['rasterWithinDeclaredTolerance'] and p['resourceImagesMatch'] and p['canonicalRunOrderExactIgnoringLineSeparators'] and p['textGeometryWithinTolerance'] for p in report['pages'])
        report.update(status='BOUNDED_CANDIDATE_PASS_GATE_OPEN' if qualified_candidate else 'CANDIDATE_REJECTED_RECORDED',
                      pdf=str(destination), sourcePlan=str(pages / 'results.json'),
                      limitations=['Production license/backend/platform decision remains open', 'Native page-boundary IME and physical printing NOT RUN',
                                   'Not PDF/UA or editable PDF font semantics; visible glyphs are vector outlines',
                                   'Line separators checked separately from exact source-run units; malformed UTF-16 export explicitly rejected'])
        print(report['status'] + '; GP0 OPEN; ' + str(destination))
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), file=sys.stderr)
    finally:
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != report['sourceSha256']:
            report.update(status='FAIL', error='PDF experiment source changed during execution')
        save()
    return 0 if report['status'] in ('BOUNDED_CANDIDATE_PASS_GATE_OPEN', 'CANDIDATE_REJECTED_RECORDED') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pages', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.exit(run(args.pages.resolve(), args.output.resolve()))
