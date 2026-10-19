"""Bounded page-plan experiment. A passing experiment does not pass GP0."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = _REPO_ROOT
sys.path.insert(0, str(ROOT / 'desktop-prototype'))
from verification import validate_build, timestamp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'gate': 'GP0', 'startedAt': timestamp()}
    target = output / 'checks.json'
    def write():
        target.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    write()
    try:
        build = validate_build(args.build_dir)
        import os
        command = [shutil.which('java'), '-cp', str(build / 'classes') + os.pathsep + str(build / 'lib/*'),
                   'editor.MainKt', '--page-probe', '--page-output=' + str(output / 'pages')]
        report['command'] = command
        process = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        log = process.stdout.decode('utf-8', 'replace')
        (output / 'probe.log').write_text(log, encoding='utf-8')
        report['exitCode'] = process.returncode
        if process.returncode != 0:
            raise RuntimeError('Page experiment failed: ' + log[-3000:])
        result = json.loads((output / 'pages/results.json').read_text(encoding='utf-8'))
        from PIL import Image, ImageChops
        rasters = []
        svg_counts = Counter()
        for page in range(1, result['pageCount'] + 1):
            image = Image.open(output / f'pages/page-{page}.png').convert('RGB')
            bounds = ImageChops.difference(image, Image.new('RGB', image.size, 'white')).getbbox()
            if bounds is None:
                raise AssertionError(f'Page {page} is blank')
            # Exclude repeated page labels and image/borders: there must be body/cell ink.
            content = image.crop((48, 48, result['pageSize'][0] - 48, result['pageSize'][1] - 48))
            if ImageChops.difference(content, Image.new('RGB', content.size, 'white')).getbbox() is None:
                raise AssertionError(f'Page {page} contains no content ink')
            rasters.append({'page': page, 'nonWhiteBounds': bounds})
            tree = ET.parse(output / f'pages/page-{page}.svg')
            for e in tree.iter():
                if e.tag.rsplit('}', 1)[-1] == 'text':
                    svg_counts.update(''.join(e.itertext()))
        required = Counter(''.join(result['canonicalText'].values()))
        missing = {f'U+{ord(c):04X}': {'sourceCount': n, 'svgCount': svg_counts[c]}
                   for c, n in required.items() if '\u0600' <= c <= '\u06ff' and c.isalpha() and svg_counts[c] == 0}
        # Necessary, deliberately weak semantic condition. Passing it would still require
        # order, duplication, ligature, extraction and geometry qualification of a PDF.
        backend = 'REJECTED_MISSING_CANONICAL_LETTERS' if missing else 'NEEDS_PDF_EXTRACTION_EXPERIMENT'
        report.update(status='EXPERIMENT_CHECKS_PASS_GATE_OPEN', pageCount=result['pageCount'],
                      rasterChecks=rasters, svgBackend=backend, missingCanonicalLetters=missing,
                      directSkikoPdfBindingAvailable=result['directSkikoPdfBindingAvailable'],
                      pdfCreated=False, gp0Qualified=False,
                      nativePageBoundaryIme='NOT RUN', humanUrduReview='NOT RUN',
                      scope='Experimental line/page mapping, image and rich-cell specimen; not production schema or live editable paged UI')
        validate_build(build)
        print(f"PASS page experiment: {result['pageCount']} pages; SVG backend {backend}; GP0 OPEN")
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), file=sys.stderr)
    finally:
        report['finishedAt'] = timestamp()
        write()
    return 0 if report['status'] == 'EXPERIMENT_CHECKS_PASS_GATE_OPEN' else 1


if __name__ == '__main__':
    sys.exit(main())
