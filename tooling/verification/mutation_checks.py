"""Compile real implementation mutants; require the intended assertion, never any crash."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
from verification import ROOT, PROJECT, timestamp, identity, validate_build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    build = validate_build(args.build_dir)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'startedAt': timestamp(), 'kind': 'IMPLEMENTATION_SOURCE_MUTATIONS', 'steps': [], 'mutants': []}
    result_path = output / 'mutation-results.json'

    def save():
        result_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')

    def run(name, command, timeout=180):
        step = {'name': name, 'command': command, 'startedAt': timestamp()}
        report['steps'].append(step)
        save()
        try:
            p = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
            log, code = p.stdout.decode('utf-8', 'replace'), p.returncode
        except subprocess.TimeoutExpired as error:
            log, code = (error.stdout or b'').decode('utf-8', 'replace') + '\nTIMEOUT', None
        (output / (name + '.log')).write_text(log, encoding='utf-8')
        step.update(exitCode=code, finishedAt=timestamp())
        save()
        return code, log

    java = shutil.which('java')
    def command(classes, out, full=False):
        return [java, '-cp', str(classes) + os.pathsep + str(build / 'lib/*'), 'editor.MainKt',
                '--urdu-u0-check'] + ([] if full else ['--urdu-engine-only']) + ['--urdu-fixtures=' + str(ROOT / 'tests/suites/urdu/u0/fixtures.json'),
                '--urdu-output=' + str(out)]
    try:
        code, log = run('baseline', command(build / 'classes', output / 'baseline', full=True))
        if code != 0 or 'PASS urdu-engine-harness' not in log:
            raise RuntimeError('Passing baseline required before mutation checks')
        report['baseline'] = 'PASS'
        mutants = [
            ('substitute-keheh', 'EditorEngine.kt', r'char\s*=\s*it,', "char = if (it == '\\u06a9') '\\u0643' else it,", 'Visible text mismatch', False),
            ('undo-leaves-bold', 'EditorEngine.kt', r'dc = h.before.document(?=;|\s*\n)', 'dc = h.before.document.copy(units = h.before.document.units.map { it.copy(style = TextStyleFlags(bold = true)) })', 'Undo failed to restore unstyled baseline', False),
            ('mangle-lone-surrogate', 'EditorEngine.kt', r'char\s*=\s*it,', "char = if (it == '\\uD83D' && text.length == 1) '\\uFFFD' else it,", 'UTF-16 code units mismatch', False),
            ('white-probe-raster', 'UrduChecks.kt', 'paragraph.paint(composeCanvas, color = androidx.compose.ui.graphics.Color.Black)',
             'paragraph.paint(composeCanvas, color = androidx.compose.ui.graphics.Color.White)', 'Blank typography raster:', True),
        ]
        for name, filename, before, after, expected, full in mutants:
            relative = Path('tests/desktop-harness/src/jvmMain/kotlin/dev/shayxo/composedoc/harness' if full else 'core/domain/src/commonMain/kotlin/dev/shayxo/composedoc/domain') / filename
            source = (ROOT / relative).read_text(encoding='utf-8')
            pattern = re.escape(before) if full else before
            if len(re.findall(pattern, source)) != 1:
                raise RuntimeError('Mutation point changed: ' + name)
            project = output / name
            project.mkdir()
            for name_to_copy in ('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'):
                shutil.copy2(ROOT / name_to_copy, project / name_to_copy)
            for directory in ('gradle', 'build-logic', 'core', 'apps/desktop-workspace', 'tests/desktop-harness'):
                shutil.copytree(ROOT / directory, project / directory,
                                ignore=shutil.ignore_patterns('build', '.gradle', 'target', 'local-evidence'))
            changed = project / relative
            changed.write_text(re.sub(pattern, lambda _: after, source), encoding='utf-8')
            item = {'name': name, 'sourceFile': filename, 'kind': 'PROBE_RENDERER' if full else 'ENGINE',
                    'source': identity(changed), 'expectedFailure': expected, 'status': 'RUNNING'}
            report['mutants'].append(item)
            runtime = project / 'mutation-runtime'
            wrapper = project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
            code, log = run(name + '-build', [str(wrapper), '-p', str(project), '--console=plain',
                            'exportVerificationRuntime', '-PverificationOutput=' + str(runtime)], timeout=900)
            if code != 0:
                raise RuntimeError('Mutant compilation failed (not a kill): ' + name)
            code, log = run(name + '-test', command(runtime / 'classes', project / 'results', full))
            assertion_prefix = 'java.lang.IllegalStateException: Blank typography raster:' if full else 'java.lang.IllegalStateException: UTXT-'
            detected = code is not None and code != 0 and expected in log and assertion_prefix in log
            item.update(status='DETECTED_INTENDED_DEFECT' if detected else 'FAIL', exitCode=code,
                        assertion=next((line for line in log.splitlines() if assertion_prefix in line), None))
            if not detected:
                raise RuntimeError('Mutant survived or failed for unrelated reason: ' + name)
            print('PASS implementation mutant ' + name + ': ' + item['assertion'], flush=True)
        validate_build(build)
        report['status'] = 'PASS'
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), flush=True)
    finally:
        report['finishedAt'] = timestamp()
        save()
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
