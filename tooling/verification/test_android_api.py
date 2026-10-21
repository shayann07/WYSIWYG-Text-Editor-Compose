import unittest

from android_api import canonical


class AndroidApiContractTest(unittest.TestCase):
    def test_keeps_public_descriptors_but_excludes_mangled_internal_and_default_bridge(self):
        raw = ('Compiled from "Adapter.kt"\r\npublic final class example.Adapter {\r\n'
               '  public final boolean apply(java.lang.String);\r\n    descriptor: (Ljava/lang/String;)Z\r\n'
               '  public final void internal$ui();\r\n    descriptor: ()V\r\n'
               '  public example.Adapter(int, kotlin.jvm.internal.DefaultConstructorMarker);\r\n'
               '    descriptor: (ILkotlin/jvm/internal/DefaultConstructorMarker;)V\r\n}\r\n')
        expected = ('public final class example.Adapter {\n  public final boolean apply(java.lang.String);\n'
                    '    descriptor: (Ljava/lang/String;)Z\n}\n')
        self.assertEqual(expected, canonical(raw))
        self.assertNotEqual(expected, canonical(raw.replace('apply(java.lang.String)', 'apply(java.lang.Integer)')))


if __name__ == "__main__":
    unittest.main()
