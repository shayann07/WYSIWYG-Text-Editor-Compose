"""Run pinned candidate/review experiments with bounded logs and real process receipts."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = _REPO_ROOT
from verification import identity, timestamp, node_dependency_paths, frozen_reference_paths, validate_build, PROJECT

STAGES = [('history-session', 'history-session.mjs', 30), ('candidate', 'candidate.mjs', 180),
          ('review', 'review-checks.mjs', 30), ('structure', 'ordered-structure.mjs', 120),
          ('queue-progress', 'queue-progress.mjs', 30),
          ('owner-commit', 'composition-admission.mjs', 60), ('owner-abort', 'composition-admission.mjs', 60),
          ('owner-overflow', 'composition-admission.mjs', 60), ('durable-replay', 'durable-replay.mjs', 60),
          ('compaction-recovery', 'compaction-recovery.mjs', 90),
          ('portable-checkpoint', 'portable-checkpoint.mjs', 45),
          ('portable-compaction-recovery', 'compaction-recovery.mjs', 90),
          ('writer-contention', 'writer-contention.mjs', 60),
          ('canonical-identity', 'canonical-identity.mjs', 60),
          ('typed-identity', 'typed-identity.mjs', 60),
          ('identity-transition', 'identity-transition.mjs', 90)]


def cache_is_current(report, directory, current_inputs, node_version, java_version=None):
    if report.get('status') != 'EXPERIMENT_CHECKS_PASS_GATE_OPEN' or report.get('mode') != 'EXECUTED':
        return False
    if report.get('inputs') != current_inputs or report.get('nodeRuntime') != node_version or report.get('platform') != sys.platform:
        return False
    if java_version is not None and report.get('javaRuntime') != java_version:
        return False
    steps = report.get('steps', [])
    if [s['name'] for s in steps] != [s[0] for s in STAGES] or any(s.get('exitCode') != 0 for s in steps):
        return False
    expected = report.get('artifactIdentities', {})
    if not {name + '/results.json' for name, _, _ in STAGES}.issubset(expected):
        return False
    try:
        if any(not (directory / name).resolve().is_relative_to(directory.resolve()) for name in expected):
            return False
        return all(identity(directory / name) == stored for name, stored in expected.items())
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    base = Path(__file__).resolve().parent
    build = validate_build(args.build_dir)
    paths = sorted(base.glob('*.mjs')) + sorted(base.glob('*.py')) + [base / 'package.json', base / 'package-lock.json', ROOT / 'tests/suites/urdu/u0/fixtures.json', ROOT / 'tooling/verification/verification.py'] + node_dependency_paths() + frozen_reference_paths()
    from verification import inputs
    paths += [ROOT / relative for relative in inputs()]
    def sources():
        values = {p.relative_to(ROOT).as_posix(): identity(p) for p in paths}
        # Match actual compiled/runtime bytes across isolated builds, without
        # treating fresh copy timestamps as changed executable behavior.
        for prefix, directory in [('@class/', build / 'classes'), ('@runtime/', build / 'lib')]:
            for p in sorted(directory.rglob('*')):
                if p.is_file():
                    data = identity(p); data.pop('mtimeNs')
                    values[prefix + p.relative_to(directory).as_posix()] = data
        return values
    report = {'status': 'RUNNING', 'gate': 'C0', 'startedAt': timestamp(), 'steps': [], 'inputs': sources(), 'mode': 'EXECUTED', 'platform': sys.platform}
    def save():
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        node = shutil.which('node')
        if not node or not (base / 'node_modules/yjs/package.json').exists():
            raise RuntimeError('Run npm ci --ignore-scripts --no-audit --no-fund in tests/suites/integration/c0 first')
        runtime = subprocess.run([node, '--version'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
        report['runtimeProbe'] = {'command': [node, '--version'], 'exitCode': runtime.returncode, 'output': runtime.stdout.decode('utf-8', 'replace').strip()}
        if runtime.returncode != 0:
            raise RuntimeError('Node runtime probe failed')
        report['nodeRuntime'] = report['runtimeProbe']['output']
        java = shutil.which('java')
        jvm = subprocess.run([java, '-version'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
        report['javaRuntimeProbe'] = {'command': [java, '-version'], 'exitCode': jvm.returncode, 'output': jvm.stdout.decode('utf-8', 'replace').strip()}
        if jvm.returncode != 0:
            raise RuntimeError('Java runtime probe failed')
        report['javaRuntime'] = report['javaRuntimeProbe']['output']
        report['buildDirectory'] = str(build)
        previous = sorted((ROOT / 'tests/local-evidence/desktop').glob('batch-*/collaboration-experiments/results.json'), key=lambda p: p.stat().st_mtime_ns, reverse=True)
        for cached_path in previous:
            try:
                cached = json.loads(cached_path.read_text(encoding='utf-8'))
                if not cache_is_current(cached, cached_path.parent, report['inputs'], report['nodeRuntime'], report['javaRuntime']):
                    continue
            except (OSError, ValueError, KeyError):
                continue
            report.update(status='EXPERIMENT_CHECKS_PASS_GATE_OPEN', mode='REUSED_VERIFIED_EVIDENCE', c0Qualified=False,
                          reusedFrom=str(cached_path), originalSteps=cached['steps'], totalMutatingOperations=cached['totalMutatingOperations'],
                          namedCandidateTraces=cached['namedCandidateTraces'], typedReviewTraces=cached['typedReviewTraces'],
                          candidateSummary=str(cached_path.parent / 'candidate/results.json'), reviewSummary=str(cached_path.parent / 'review/results.json'),
                          historySummary=str(cached_path.parent / 'history-session/results.json'))
            report.update(structuralTraces=cached['structuralTraces'], structuredMutatingOperations=cached['structuredMutatingOperations'],
                          unitMutatingOperations=cached['unitMutatingOperations'], structureSummary=str(cached_path.parent / 'structure/results.json'))
            report.update(actualOwnerCases=cached['actualOwnerCases'], ownerCommitSummary=str(cached_path.parent / 'owner-commit/results.json'),
                          ownerAbortSummary=str(cached_path.parent / 'owner-abort/results.json'),
                          ownerOverflowSummary=str(cached_path.parent / 'owner-overflow/results.json'),
                          queueProgressSummary=str(cached_path.parent / 'queue-progress/results.json'), queueProgressTraces=cached['queueProgressTraces'],
                          durableReplaySummary=str(cached_path.parent / 'durable-replay/results.json'), durableReplayTraces=cached['durableReplayTraces'],
                          compactionRecoverySummary=str(cached_path.parent / 'compaction-recovery/results.json'), compactionRecoveryTraces=cached['compactionRecoveryTraces'],
                          portableCheckpointSummary=str(cached_path.parent / 'portable-checkpoint/results.json'), portableCheckpointTraces=cached['portableCheckpointTraces'],
                          portableCompactionSummary=str(cached_path.parent / 'portable-compaction-recovery/results.json'), portableCompactionTraces=cached['portableCompactionTraces'],
                          portableEquivalentWorkerReplies=cached['portableEquivalentWorkerReplies'],
                          writerContentionSummary=str(cached_path.parent / 'writer-contention/results.json'), writerContentionTraces=cached['writerContentionTraces'],
                          canonicalIdentitySummary=str(cached_path.parent / 'canonical-identity/results.json'), canonicalIdentityTraces=cached['canonicalIdentityTraces'],
                          typedIdentitySummary=str(cached_path.parent / 'typed-identity/results.json'), typedIdentityTraces=cached['typedIdentityTraces'],
                          identityTransitionSummary=str(cached_path.parent / 'identity-transition/results.json'), identityTransitionTraces=cached['identityTransitionTraces'],
                          projectionTransitionOperations=cached['projectionTransitionOperations'],
                          actualJvmProtocolCases=cached['actualJvmProtocolCases'])
            if report['inputs'] != sources():
                raise RuntimeError('C0 inputs changed during cache validation')
            print('PASS C0 unchanged code/dependencies/runtime: verified prior execution receipts; C0 OPEN')
            return 0
        for name, script, timeout in STAGES:
            command = [node, str(base / script), '--output=' + str(output / name)]
            if name == 'portable-checkpoint':
                command.append('--python=' + sys.executable)
            if name == 'portable-compaction-recovery':
                command.append('--checkpoint-codec=portable')
            if script in ('composition-admission.mjs', 'durable-replay.mjs', 'compaction-recovery.mjs', 'canonical-identity.mjs', 'typed-identity.mjs', 'identity-transition.mjs'):
                command.append('--build=' + str(build))
                if name == 'owner-abort':
                    command.append('--terminal=abort')
                if name == 'owner-overflow':
                    command.append('--queue=overflow')
            step = {'name': name, 'command': command, 'startedAt': timestamp(), 'status': 'RUNNING'}
            report['steps'].append(step); save()
            started = time.monotonic()
            try:
                p = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
                log, code = p.stdout.decode('utf-8', 'replace'), p.returncode
            except subprocess.TimeoutExpired as error:
                log, code = (error.stdout or b'').decode('utf-8', 'replace') + '\nTIMEOUT', None
            (output / (name + '.log')).write_text(log, encoding='utf-8')
            step.update(status='PASS' if code == 0 else 'FAIL', exitCode=code, finishedAt=timestamp(), seconds=round(time.monotonic() - started, 3))
            save()
            if code != 0:
                raise RuntimeError(name + ' failed: ' + log[-3000:])
        candidate = json.loads((output / 'candidate/results.json').read_text(encoding='utf-8'))
        review = json.loads((output / 'review/results.json').read_text(encoding='utf-8'))
        history = json.loads((output / 'history-session/results.json').read_text(encoding='utf-8'))
        structure = json.loads((output / 'structure/results.json').read_text(encoding='utf-8'))
        queue = json.loads((output / 'queue-progress/results.json').read_text(encoding='utf-8'))
        if queue['status'] != 'BOUNDED_QUEUE_PROGRESS_PASS_GATE_OPEN':
            raise RuntimeError('Bounded queue progress checks incomplete')
        durable = json.loads((output / 'durable-replay/results.json').read_text(encoding='utf-8'))
        if durable['status'] != 'DURABLE_PROCESS_REPLAY_PASS_GATE_OPEN' or durable['ownerTermination'].get('code') != 0:
            raise RuntimeError('Durable process replay incomplete or actual owner did not close')
        compaction = json.loads((output / 'compaction-recovery/results.json').read_text(encoding='utf-8'))
        if compaction['status'] != 'COMPACTION_OFFLINE_RECOVERY_PASS_GATE_OPEN' or compaction['ownerTermination'].get('code') != 0:
            raise RuntimeError('Compaction/offline recovery incomplete or actual owner did not close')
        portable = json.loads((output / 'portable-checkpoint/results.json').read_text(encoding='utf-8'))
        portable_compaction = json.loads((output / 'portable-compaction-recovery/results.json').read_text(encoding='utf-8'))
        if portable['status'] != 'PORTABLE_CHECKPOINT_EXPERIMENT_PASS_GATE_OPEN':
            raise RuntimeError('Portable checkpoint/independent decoder incomplete')
        if portable_compaction['status'] != 'COMPACTION_OFFLINE_RECOVERY_PASS_GATE_OPEN' or portable_compaction['ownerTermination'].get('code') != 0 or portable_compaction['checkpointCodec'] != 'portable':
            raise RuntimeError('Portable process-fault compaction incomplete or owner did not close')
        contention = json.loads((output / 'writer-contention/results.json').read_text(encoding='utf-8'))
        if contention['status'] != 'WRITER_CONTENTION_EXPERIMENT_PASS_GATE_OPEN' or contention['heldProcessesRemaining'] != 0 or contention.get('cleanupForced'):
            raise RuntimeError('Writer contention checks incomplete or owned holder cleanup failed')
        identity_basis = json.loads((output / 'canonical-identity/results.json').read_text(encoding='utf-8'))
        if identity_basis['status'] != 'CANONICAL_IDENTITY_BASIS_PASS_GATE_OPEN' or len(identity_basis['ownerTerminations']) != 2 or any(end.get('code') != 0 for end in identity_basis['ownerTerminations']):
            raise RuntimeError('Canonical identity checks incomplete or owned JVMs did not close')
        typed_identity = json.loads((output / 'typed-identity/results.json').read_text(encoding='utf-8'))
        if typed_identity['status'] != 'TYPED_NATIVE_IDENTITY_PAIRING_PASS_GATE_OPEN' or len(typed_identity['ownerTerminations']) != 2 or any(end.get('code') != 0 for end in typed_identity['ownerTerminations']):
            raise RuntimeError('Typed identity pairing incomplete or owned JVMs did not close')
        transition = json.loads((output / 'identity-transition/results.json').read_text(encoding='utf-8'))
        if transition['status'] != 'IDENTITY_TRANSITION_EXPERIMENT_PASS_GATE_OPEN' or transition['ownerTermination'].get('code') != 0 or transition['generatedOperations'] != 96 or transition['nativeTransactions'] + transition['noOpProjections'] != 96:
            raise RuntimeError('Identity transition coverage incomplete or actual owner did not close')
        if len(compaction['processes']) != len(portable_compaction['processes']):
            raise RuntimeError('Codec lanes executed different process traces')
        equal_replies = 0
        for index, (v8_process, portable_process) in enumerate(zip(compaction['processes'], portable_compaction['processes']), 1):
            if v8_process['exitCode'] != portable_process['exitCode']:
                raise RuntimeError('Codec process-fault outcomes differ')
            if v8_process['exitCode'] != 0:
                continue
            replies = []
            for lane in ('compaction-recovery', 'portable-compaction-recovery'):
                reply = json.loads((output / lane / f'{index:03d}-stdout.log').read_text(encoding='utf-8'))
                reply['result'].pop('checkpointBytes', None)  # Retain sizes in original receipts; encoding size is expected to differ.
                replies.append(reply)
            if replies[0] != replies[1]:
                raise RuntimeError(f'Portable authority/receipt state differs from V8 at process {index}')
            equal_replies += 1
        owner_cases = [json.loads((output / name / 'results.json').read_text(encoding='utf-8')) for name in ('owner-commit', 'owner-abort', 'owner-overflow')]
        if any(case['status'] != 'ACTUAL_OWNER_COMPOSITION_ADMISSION_PASS_GATE_OPEN' or case['ownerTermination'].get('code') != 0 for case in owner_cases):
            raise RuntimeError('Actual owner composition/admission case incomplete or owner did not exit cleanly')
        if history['status'] != 'HISTORY_SESSION_CONTROL_PASS_GATE_OPEN':
            raise RuntimeError('History-session timing contract failed')
        if structure['status'] != 'ORDERED_STRUCTURE_TRIAL_PASS_GATE_OPEN' or structure['totalStructuredMutatingTransactions'] != 10_000 or len(structure['generated']) != 20:
            raise RuntimeError('Structural compatibility corpus incomplete')
        if candidate['status'] != 'CANDIDATE_UNIT_TRIAL_PASS_GATE_OPEN' or review['status'] != 'TYPED_ADMISSION_TRIAL_PASS_GATE_OPEN':
            raise RuntimeError('Missing successful candidate/review result')
        if len(candidate['seeds']) != 20 or candidate['totalGeneratedOperations'] != 100_000 or any(s['localUpdates'] != 5000 for s in candidate['seeds']):
            raise RuntimeError('Generated mutating-operation coverage incomplete')
        if report['inputs'] != sources():
            raise RuntimeError('C0 inputs changed during trial')
        validate_build(build)
        report.update(status='EXPERIMENT_CHECKS_PASS_GATE_OPEN', c0Qualified=False,
                      totalMutatingOperations=candidate['totalGeneratedOperations'], namedCandidateTraces=len(candidate['named']),
                      typedReviewTraces=len(review['checks']), candidateSummary='candidate/results.json', reviewSummary='review/results.json')
        report.update(structuralTraces=len(structure['checks']), structuredMutatingOperations=structure['totalStructuredMutatingTransactions'], structureSummary='structure/results.json')
        report['unitMutatingOperations'] = candidate['totalGeneratedOperations']
        report['totalMutatingOperations'] = candidate['totalGeneratedOperations'] + structure['totalStructuredMutatingTransactions']
        report.update(actualOwnerCases=len(owner_cases), ownerCommitSummary='owner-commit/results.json', ownerAbortSummary='owner-abort/results.json',
                      ownerOverflowSummary='owner-overflow/results.json', queueProgressSummary='queue-progress/results.json', queueProgressTraces=len(queue['checks']))
        report.update(durableReplaySummary='durable-replay/results.json', durableReplayTraces=len(durable['checks']))
        report.update(compactionRecoverySummary='compaction-recovery/results.json', compactionRecoveryTraces=len(compaction['checks']),
                      portableCheckpointSummary='portable-checkpoint/results.json', portableCheckpointTraces=len(portable['checks']),
                      portableCompactionSummary='portable-compaction-recovery/results.json', portableCompactionTraces=len(portable_compaction['checks']),
                      portableEquivalentWorkerReplies=equal_replies,
                      writerContentionSummary='writer-contention/results.json', writerContentionTraces=len(contention['checks']),
                      canonicalIdentitySummary='canonical-identity/results.json', canonicalIdentityTraces=len(identity_basis['checks']),
                      typedIdentitySummary='typed-identity/results.json', typedIdentityTraces=len(typed_identity['checks']),
                      identityTransitionSummary='identity-transition/results.json', identityTransitionTraces=len(transition['checks']),
                      projectionTransitionOperations=transition['generatedOperations'],
                      actualJvmProtocolCases=len(owner_cases) + 6)
        report['historySummary'] = 'history-session/results.json'
        # Include process receipts, SQLite files and owner traces, not just the
        # summaries that assert they passed. All owned processes are closed now.
        report['artifactIdentities'] = {p.relative_to(output).as_posix(): identity(p)
          for name, _, _ in STAGES for p in sorted((output / name).rglob('*')) if p.is_file()}
        print('PASS C0 experiments: 100000 unit + 10000 structured mutating operations + typed review; C0 OPEN')
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), file=sys.stderr)
    finally:
        report['finishedAt'] = timestamp(); save()
    return 0 if report['status'] == 'EXPERIMENT_CHECKS_PASS_GATE_OPEN' else 1


if __name__ == '__main__':
    sys.exit(main())
