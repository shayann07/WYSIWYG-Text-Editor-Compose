"""Build once in isolation; run desktop + Urdu U0 against the same artifacts."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import hashlib
import json
import os
import shutil
import sys
from verification import ROOT, Verification, validate_build
from doc_paths import resolve, original


def main():
    if any(flag in sys.argv for flag in ('--pages-output-milestone', '--workspace-milestone', '--v6-desktop-milestone', '--visual-repair-milestone', '--structured-milestone', '--writing-workflow-milestone', '--local-recovery-milestone', '--find-replace-milestone', '--paragraph-milestone')):
        return recovery_milestone()
    batch = Verification()
    error = None
    try:
        java = shutil.which('java')
        if not java:
            raise RuntimeError('Java is not on PATH')
        batch.run('evidence-contract', [sys.executable, str(ROOT / 'tooling/verification/test_verification.py')], 30, 'OK')
        batch.run('c0-evidence-contract', [sys.executable, str(ROOT / 'tests/suites/integration/c0/test_verify.py')], 30, 'OK')
        batch.run('java-runtime', [java, '-version'], 10)
        batch.compile()
        base = [java, '-cp', batch.classpath, 'editor.MainKt']
        batch.run('kernel-files-formatting', base + ['--check'], 120, 'ALL 17 formatting checks passed')
        batch.run('stress', base + ['--stress'], 120, 'PASS 12000 randomized range-format commands')
        host_result = batch.output / 'host-checks.json'
        batch.run('attached-host', base + ['--host-check', '--host-result=' + str(host_result)], 120, '"status":"PASS"')
        batch.report['host'] = json.loads(host_result.read_text(encoding='utf-8'))
        for field in ('urduArabicRtl', 'japaneseImeHost', 'paragraphLayout'):
            if batch.report['host'].get(field) != 'PASS':
                raise RuntimeError('Missing host verdict: ' + field)
        paged_result = batch.output / 'paged-host.json'
        batch.run('paged-host', base + ['--page-host-check', '--font-profile=noto-nastaliq', '--host-result=' + str(paged_result)],
                  60, 'PASS editable paged host experiment')
        batch.report['pagedHost'] = json.loads(paged_result.read_text(encoding='utf-8'))
        at_result = batch.output / 'accessibility-thread.json'
        batch.run('accessibility-thread', base + ['--accessibility-thread-check', '--host-result=' + str(at_result)],
                  30, 'PASS accessibility thread/admission checks')
        batch.report['accessibilityThread'] = json.loads(at_result.read_text(encoding='utf-8'))
        recovery_ui = batch.output / 'recovery-ui.json'
        batch.run('recovery-ui', base + ['--recovery-ui-check', '--host-result=' + str(recovery_ui)], 90, 'PASS recovery UI retention checks')
        batch.report['recoveryUi'] = json.loads(recovery_ui.read_text(encoding='utf-8'))
        writing_result = batch.output / 'writing.json'
        batch.run('urdu-writing-workflow', base + ['--writing-check', '--host-result=' + str(writing_result)], 90, 'PASS Urdu writing workflow checks')
        batch.report['writingWorkflow'] = json.loads(writing_result.read_text(encoding='utf-8'))
        batch.run('native-jab', [sys.executable, str(ROOT / 'tests/suites/accessibility/jab/verify.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'native-jab')], 120)
        native_at = json.loads((batch.output / 'native-jab/results.json').read_text(encoding='utf-8'))
        if native_at['status'] not in ('NATIVE_JAB_PROTOCOL_CHECKS_PASS_GATE_OPEN', 'NOT_RUN'):
            raise RuntimeError('Native AT bridge experiment did not complete')
        batch.report['nativeAccessibility'] = native_at
        batch.run('windows-keyboard-capability', [sys.executable, str(ROOT / 'tests/suites/input/windows/verify_capability.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'windows-keyboard-capability')], 90)
        keyboard_capability = json.loads((batch.output / 'windows-keyboard-capability/results.json').read_text(encoding='utf-8'))
        if keyboard_capability['status'] not in ('READ_ONLY_WINDOWS_LAYOUT_CHECKS_PASS_GATE_OPEN', 'NOT_RUN'):
            raise RuntimeError('Windows keyboard capability probe incomplete')
        batch.report['keyboardCapability'] = keyboard_capability
        batch.run('web-input-spike', [sys.executable, str(ROOT / 'tests/suites/web/input/verify.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'web-input-spike')], 120,
                  'resources closed')
        batch.report['webInputSpike'] = json.loads((batch.output / 'web-input-spike/results.json').read_text(encoding='utf-8'))
        batch.run('urdu-u0', [sys.executable, str(ROOT / 'tests/suites/urdu/u0/verify.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'urdu-u0')], 120,
                  'U0 VERIFICATION SUITE PASSED')
        batch.report['urduU0'] = json.loads((batch.output / 'urdu-u0/results.json').read_text(encoding='utf-8'))
        batch.run('page-experiment', [sys.executable, str(ROOT / 'tests/suites/pagination/gp0/verify.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'page-experiment')], 90,
                  'GP0 OPEN')
        batch.report['pageExperiment'] = json.loads((batch.output / 'page-experiment/checks.json').read_text(encoding='utf-8'))
        batch.run('glyph-cluster-experiment', base + ['--glyph-cluster-probe', '--glyph-output=' + str(batch.output / 'glyph-clusters')],
                  60, 'GP0 OPEN')
        glyphs = json.loads((batch.output / 'glyph-clusters/results.json').read_text(encoding='utf-8'))
        if glyphs['status'] != 'CAPABILITY_TRIAL_EXECUTED_GATE_OPEN' or len(glyphs['samples']) != 8 or glyphs['glyphsObservedAcrossTrials'] <= 0:
            raise RuntimeError('Glyph-cluster capability trial incomplete')
        batch.report['glyphClusterExperiment'] = glyphs
        batch.run('pdf-backend-experiment', [sys.executable, str(ROOT / 'tests/suites/pagination/pdf_outline/experiment.py'),
                  '--pages', str(batch.output / 'page-experiment/pages'), '--output', str(batch.output / 'pdf-backend-experiment')],
                  90, 'GP0 OPEN')
        batch.report['pdfExperiment'] = json.loads((batch.output / 'pdf-backend-experiment/results.json').read_text(encoding='utf-8'))
        batch.run('schema-experiment', [sys.executable, str(ROOT / 'tests/suites/integration/s0/verify.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'schema-experiment')], 90,
                  'S0 OPEN')
        batch.report['schemaExperiment'] = json.loads((batch.output / 'schema-experiment/results.json').read_text(encoding='utf-8'))
        batch.run('collaboration-experiments', [sys.executable, str(ROOT / 'tests/suites/integration/c0/verify.py'),
                  '--output-dir', str(batch.output / 'collaboration-experiments'), '--build-dir', str(batch.build)], 540, 'C0 OPEN')
        batch.report['collaborationExperiments'] = json.loads((batch.output / 'collaboration-experiments/results.json').read_text(encoding='utf-8'))
        batch.run('implementation-mutations', [sys.executable, str(ROOT / 'tooling/verification/mutation_checks.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output / 'mutations')], 3600,
                  'PASS implementation mutant mangle-lone-surrogate')
        probes = ROOT / 'tests/reviews/argon-critical-path/experiments/run_all_probes.py'
        if probes.exists():
            env = dict(os.environ, EDITOR_BUILD_CLASSPATH=batch.classpath,
                       ARGON_PROBE_SUMMARY_OUT=str(batch.output / 'probe_results_summary.json'))
            batch.run('argon-regression-probes', [sys.executable, str(probes)], 120, 'All probes completed', env)
        counts = {}
        for name in ('v5-freeze-manifest.json', 'v5.1-spec-manifest.json'):
            manifest = json.loads(resolve(name).read_text(encoding='utf-8'))
            entries = manifest['hashes']
            for path, expected in entries.items():
                if hashlib.sha256(original(path).read_bytes()).hexdigest() != expected:
                    raise RuntimeError('Historical file changed: ' + path)
            counts[name] = len(entries)
        batch.report['historicalFilesUnchanged'] = counts
        batch.report['gateDisposition'] = {'M0CoveredDeterministicRepairs': 'PASS', 'U0': 'AUTOMATED_PROFILE_CORPUS_ONLY',
            'GP0': 'OPEN_PDF_LOGICAL_TEXT_GEOMETRY_UNQUALIFIED', 'S0': 'OPEN', 'C0': 'OPEN',
            'Web': 'M1_BROWSER_OWNER_SPIKE_ONLY_NATIVE_OS_IME_AT_OPEN',
            'glyphClusterBridge': 'CAPABILITY_ONLY_RESHAPING_IS_NOT_ACTUAL_PARAGRAPH_GLYPH_EXTRACTION',
            'nativeImeAndScreenReaderAndPrint': 'NOT RUN BY THIS SUITE'}
        batch.report['gateDisposition']['DesktopNativeAT'] = native_at['status'] + '_SCREEN_READER_SPEECH_AND_OTHER_TARGETS_OPEN'
        batch.report['gateDisposition']['WindowsKeyboard'] = 'READ_ONLY_CAPABILITY_IN_SUITE_REAL_INPUT_EVIDENCE_RECORDED_SEPARATELY'
        print('PASS historical integrity ' + str(counts), flush=True)
    except BaseException as failure:
        error = str(failure)
        print('FAIL ' + error, flush=True)
    return batch.finish(error)


def recovery_milestone():
    if '--pages-output-milestone' in sys.argv and '--workspace-milestone' not in sys.argv:sys.argv.append('--workspace-milestone')
    """Current writing/recovery integration only; no repeat of unrelated research gates."""
    batch = Verification()
    try:
        reuse=next((arg.split('=',1)[1] for arg in sys.argv if arg.startswith('--build-dir=')),None)
        if reuse:
            from pathlib import Path
            batch.build=Path(reuse).resolve();validate_build(batch.build)
            batch.report['build']=json.loads((batch.build/'build-identity.json').read_text(encoding='utf-8'))
            batch.report['reusedBuild']=str(batch.build);batch.persist()
        else:
            batch.compile()
        base = ['java', '-cp', batch.classpath, 'editor.MainKt']
        for name, flag, marker in [('kernel', '--check', 'ALL 17 formatting checks passed'),
                                  ('stress', '--stress', 'PASS 12000 randomized range-format commands'),
                                  ('host', '--host-check', '"status":"PASS"'),
                                  ('pages', '--page-host-check', 'PASS editable paged host experiment'),
                                  ('quarantine', '--recovery-ui-check', 'PASS recovery UI retention checks'),
                                  ('writing', '--writing-check', 'PASS Urdu writing workflow checks'),
                                  ('local-recovery', '--local-recovery-check', 'PASS local committed recovery checks'),
                                  ('find-replace', '--find-replace-check', 'PASS exact Unicode Find/Replace checks'),
                                  ('paragraph', '--paragraph-check', 'PASS persistent paragraph properties checks'),
                                  ('connected-writing', '--connected-writing-check', 'PASS connected writing deliveries checks'),
                                  ('structure', '--structure-check', 'PASS structured document milestone checks')]:
            profile = ['--font-profile=noto-nastaliq'] if flag == '--page-host-check' else []
            batch.run(name, base + [flag, '--host-result=' + str(batch.output / (name+'.json'))] + profile, 120, marker)
        if any(f in sys.argv for f in ('--workspace-milestone','--visual-repair-milestone','--v6-desktop-milestone')):
            batch.run('visual-repairs', base + ['--visual-repair-check', '--host-result=' + str(batch.output/'visual-repairs.json')],
                      300, 'PASS visual multilingual repair checks')
        if '--v6-desktop-milestone' in sys.argv or '--workspace-milestone' in sys.argv or '--pages-output-milestone' in sys.argv:
            batch.run('v6-qualification', base + ['--v6-qualification-check', '--host-result=' + str(batch.output/'v6-qualification.json')], 180, 'PASS V6 bounded production qualification')
        if '--workspace-milestone' in sys.argv or '--pages-output-milestone' in sys.argv:
            batch.run('workspace',base+['--workspace-check','--host-result='+str(batch.output/'workspace.json')],150,'PASS local writing workspace checks')
            batch.run('workspace-host',base+['--workspace-host-check','--host-result='+str(batch.output/'workspace-host.json')],150,'PASS mounted workspace checks')
            batch.run('workspace-kills',[sys.executable,str(ROOT/'tooling/verification/workspace_process_checks.py'),'--build-dir',str(batch.build),'--output-dir',str(batch.output/'workspace-kills')],240,'PASS 13 workspace owned-process interruption cases')
        if '--pages-output-milestone' in sys.argv:
            batch.run('pages-output',base+['--pages-output-check','--host-result='+str(batch.output/'pages-output.json')],180,'PASS pages/output checks')
        batch.run('process-interruptions', [sys.executable, str(ROOT/'tooling/verification/local_recovery_process_checks.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output/'process-interruptions')], 240,
                  'PASS 9 owned-process interruption cases')
        batch.run('structured-interruptions', [sys.executable, str(ROOT/'tooling/verification/structured_process_checks.py'),
                  '--build-dir', str(batch.build), '--output-dir', str(batch.output/'structured-interruptions')], 180,
                  'PASS 3 structured owned-process interruption cases')
        batch.run('urdu-u0', [sys.executable, str(ROOT/'tests/suites/urdu/u0/verify.py'), '--build-dir', str(batch.build),
                  '--output-dir', str(batch.output/'urdu-u0')], 120, 'U0 VERIFICATION SUITE PASSED')
        validate_build(batch.build)
        counts = {}
        for name in ('v5-freeze-manifest.json', 'v5.1-spec-manifest.json'):
            hashes=json.loads(resolve(name).read_text(encoding='utf-8'))['hashes']
            for relative, expected in hashes.items():
                assert hashlib.sha256(original(relative).read_bytes()).hexdigest()==expected, relative
            counts[name]=len(hashes)
        batch.report['historicalFilesUnchanged']=counts
        batch.report['scope']='Bounded persistent paragraph properties and current writing/search/quarantine/recovery/U0 integration. No collaboration/schema/PDF research rerun or platform/power-loss qualification.'
        return batch.finish()
    except BaseException as failure:
        return batch.finish(str(failure))

if __name__ == '__main__':
    sys.exit(main())
