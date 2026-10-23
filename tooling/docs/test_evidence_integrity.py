import json
from pathlib import Path
import shutil
import tempfile
import unittest

import evidence_integrity as integrity


class TransitiveEvidenceIntegrity(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='composedoc-evidence-copy-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.record = json.loads((integrity.ROOT / integrity.RECORD).read_text(encoding='utf-8'))
        paths = set(self.record['files']) | set(self.record['ledgerSources'])
        paths |= set(self.record['runtimeDependencyLocks']) | {integrity.RECORD, 'docs/relocations.json'}
        for name in paths:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(integrity.ROOT / name, target)

    def test_real_copy_checks_all_imports_and_historical_correspondence(self):
        result = integrity.verify(self.root)
        self.assertEqual(37, result['localImportedModules'])
        self.assertEqual([], result['currentCaptureOnly'])

    def test_changed_run17_run19_and_gemini_are_rejected(self):
        for name in ['tests/suites/codex/run-20261015-17/integrated-model.mjs',
                     'tests/suites/codex/run-20261015-19/document-model.mjs',
                     'tests/reviews/gemini-v5-readiness/probe-interactions-and-unicode.mjs']:
            file = self.root / name
            original = file.read_bytes()
            file.write_bytes(original + b'\n// modified audit input\n')
            with self.assertRaisesRegex(ValueError, 'Covered evidence changed'):
                integrity.verify(self.root)
            file.write_bytes(original)

    def test_new_import_fails_even_if_importer_hash_is_updated(self):
        root = self.root / integrity.ROOTS[0]
        root.write_bytes(root.read_bytes() + b"\nimport './uncovered.mjs';\n")
        root.with_name('uncovered.mjs').write_text('export const value=1;\n')
        self.record['files'][integrity.ROOTS[0]]['sha256'] = integrity.digest(root)
        (self.root / integrity.RECORD).write_text(json.dumps(self.record))
        with self.assertRaisesRegex(ValueError, 'closure changed'):
            integrity.verify(self.root)

    def test_missing_and_escaping_dependencies_fail(self):
        file = self.root / integrity.ROOTS[0]
        for specifier in ['./missing.mjs', '../../../../../../outside.mjs', 'file:///outside.mjs']:
            file.write_text(f"import '{specifier}';\n")
            with self.assertRaises(ValueError):
                integrity.verify(self.root)

    def test_ledger_mutation_and_false_provenance_fail(self):
        file = self.root / next(iter(self.record['ledgerSources']))
        original = file.read_bytes()
        file.write_bytes(original + b'\n')
        with self.assertRaisesRegex(ValueError, 'provenance ledger changed'):
            integrity.verify(self.root)
        file.write_bytes(original)
        next(iter(self.record['files'].values()))['historicalMatches'] = []
        (self.root / integrity.RECORD).write_text(json.dumps(self.record))
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            integrity.verify(self.root)

    def test_import_syntax_and_nonliteral_dynamic_imports(self):
        source = "// import './false.mjs';\nconst x=\"import './also-false.mjs'\";\n"
        source += "export {x} from './real.mjs'; import('./dynamic.mjs'); import.meta.url;"
        self.assertEqual(['./real.mjs', './dynamic.mjs'], list(integrity.imports(source)))
        with self.assertRaisesRegex(ValueError, 'Nonliteral'):
            list(integrity.imports('import(computed);'))


if __name__ == '__main__':
    unittest.main()
