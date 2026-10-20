from pathlib import Path
import subprocess
import tempfile
import unittest

from check_quality_policy import baseline_config_errors, check_quality_policy, suppression_errors


class QualityPolicyChecks(unittest.TestCase):
    def initialize_repository(self, root):
        subprocess.run(["git", "-C", str(root), "init", "-q"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_suppression_requires_adjacent_reason(self):
        for source in (
            '@Suppress("TooManyFunctions")\nclass Owner',
            '// Reason: intentional public API\n\n@Suppress("TooManyFunctions")\nclass Owner',
            '@Suppress("TooManyFunctions") // Reason: inline does not precede it\nclass Owner',
            '// Reason: public API\n@Composable\n@Suppress("FunctionNaming")\nfun Canvas() {}',
            '// Reason:\n@Suppress("TooManyFunctions")\nclass Owner',
        ):
            with self.subTest(source=source):
                self.assertTrue(suppression_errors(source))

    def test_multiline_qualified_and_file_suppressions(self):
        for source in (
            '// Reason: preserve the published signature.\n@Suppress(\n "LongParameterList",\n "UnusedParameter",\n)\nfun api() {}',
            '// Reason: a transaction must roll back any thrown exception.\n// The second slot remains readable.\n@kotlin.Suppress("TooGenericExceptionCaught")\nfun transaction() {}',
            '// Reason: generated interface names are part of this file API.\n@file:Suppress("FunctionNaming")\npackage fixture',
        ):
            with self.subTest(source=source):
                self.assertFalse(suppression_errors(source))

    def test_literal_diagnostics_and_comments_do_not_trigger(self):
        source = '''
            // @Suppress("UnusedParameter")
            val quoted = "@Suppress(\\\"UnusedParameter\\\")"
            val raw = """@Suppress("UnusedParameter")"""
            /* @Suppress("UnusedParameter") */
        '''
        self.assertFalse(suppression_errors(source))
        self.assertFalse(baseline_config_errors('// baseline = file("old.xml")\nval diagnostic = "baseline.set(file)"'))

    def test_nested_kotlin_comments_do_not_create_annotations(self):
        self.assertFalse(suppression_errors('/* outer /* inner */ @Suppress("x") */\nclass Owner'))
        self.assertTrue(suppression_errors('/* outer /* inner */ comment */\n@Suppress("x")\nclass Owner'))

    def test_baseline_assignment_set_and_convention_are_forbidden(self):
        for source in ('baseline = file("old.xml")', 'baseline.set(file("debt.xml"))',
                       'task.baseline.setFrom(file("debt.xml"))', 'baseline.convention(file("debt.xml"))'):
            with self.subTest(source=source):
                self.assertTrue(baseline_config_errors(source))

    def test_active_files_reject_baselines_but_archives_and_outputs_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.initialize_repository(root)
            for name in ('tests/archive/old/detekt-baseline.xml', 'core/model/build/detekt-baseline.xml',
                         'core/model/src/generated/UnicodeTable.kt'):
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('<SmellBaseline />' if file.suffix == '.xml' else '@Suppress("x")', encoding='utf-8')
            self.assertFalse(check_quality_policy(root))
            active = root / 'core/model/debt.xml'
            active.write_text('<SmellBaseline>\n</SmellBaseline>', encoding='utf-8')
            self.assertEqual(len(check_quality_policy(root)), 1)
            active.unlink()
            active = root / 'apps/desktop-workspace/detekt-baseline.xml'
            active.parent.mkdir(parents=True)
            active.write_text('invalid XML still cannot be a baseline', encoding='utf-8')
            self.assertEqual(len(check_quality_policy(root)), 1)

    def test_root_and_tooling_baselines_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.initialize_repository(root)
            baseline = root / "detekt-baseline.xml"
            baseline.write_text("new root baseline", encoding="utf-8")
            self.assertEqual(len(check_quality_policy(root)), 1)
            subprocess.run(["git", "-C", str(root), "add", baseline.name], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            nested = root / "tooling/config/baseline.xml"
            nested.parent.mkdir(parents=True)
            nested.write_text("<SmellBaseline />", encoding="utf-8")
            self.assertEqual(len(check_quality_policy(root)), 2)
            configuration = nested.with_name("analysis.gradle.kts")
            configuration.write_text('baseline = file("baseline.xml")', encoding='utf-8')
            self.assertEqual(len(check_quality_policy(root)), 3)
            configuration.with_suffix('').write_text("baseline = file('baseline.xml')", encoding='utf-8')
            self.assertEqual(len(check_quality_policy(root)), 4)
            disguised = root / "core/model/evidence/detekt-baseline.xml"
            disguised.parent.mkdir(parents=True)
            disguised.write_text("<SmellBaseline />", encoding="utf-8")
            self.assertEqual(len(check_quality_policy(root)), 5)

    def test_git_ignored_artifacts_and_archived_baselines_are_not_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.initialize_repository(root)
            (root / '.gitignore').write_text('ignored-artifacts/\n', encoding='utf-8')
            for name in ('ignored-artifacts/detekt-baseline.xml', 'tooling/build/detekt-baseline.xml',
                         'tests/archive/old/detekt-baseline.xml', 'docs/archive/old/detekt-baseline.xml',
                         'tests/local-evidence/detekt-baseline.xml', 'node_modules/package/detekt-baseline.xml'):
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('<SmellBaseline />', encoding='utf-8')
            self.assertFalse(check_quality_policy(root))


if __name__ == '__main__':
    unittest.main()
