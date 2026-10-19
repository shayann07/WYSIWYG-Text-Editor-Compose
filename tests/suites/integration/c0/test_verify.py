
from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import cache_is_current, STAGES
from verification import identity


class CacheContract(unittest.TestCase):
    def test_only_identical_sources_runtime_and_untouched_successful_artifacts_reuse(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            source = base / 'source.mjs'; source.write_text('baseline')
            inputs = {'source.mjs': identity(source)}
            artifacts = {}
            for name, _, _ in STAGES:
                folder = base / name; folder.mkdir()
                target = folder / 'results.json'; target.write_text('{"status":"PASS"}')
                artifacts[f'{name}/results.json'] = identity(target)
            database = base / 'durable-replay/authority.sqlite'; database.write_bytes(b'captured fixture')
            artifacts['durable-replay/authority.sqlite'] = identity(database)
            report = {'status': 'EXPERIMENT_CHECKS_PASS_GATE_OPEN', 'mode': 'EXECUTED', 'inputs': inputs,
                      'nodeRuntime': 'v-test', 'javaRuntime': 'j-test', 'platform': sys.platform,
                      'steps': [{'name': name, 'exitCode': 0} for name, _, _ in STAGES],
                      'artifactIdentities': artifacts}
            self.assertTrue(cache_is_current(report, base, inputs, 'v-test'))
            self.assertFalse(cache_is_current(report, base, inputs, 'v-test', 'changed-jvm'))
            self.assertFalse(cache_is_current(report, base, inputs, 'changed-runtime'))
            database.write_bytes(b'changed captured fixture')
            self.assertFalse(cache_is_current(report, base, inputs, 'v-test'))
            database.write_bytes(b'captured fixture')
            report['artifactIdentities']['durable-replay/authority.sqlite'] = identity(database)
            failed = copy.deepcopy(report); failed['steps'][1]['exitCode'] = 1
            self.assertFalse(cache_is_current(failed, base, inputs, 'v-test'))
            source.write_text('changed')
            self.assertFalse(cache_is_current(report, base, {'source.mjs': identity(source)}, 'v-test'))
            (base / 'candidate/results.json').write_text('altered old report')
            self.assertFalse(cache_is_current(report, base, inputs, 'v-test'))


if __name__ == '__main__':
    unittest.main()
