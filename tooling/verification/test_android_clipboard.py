import unittest

from android_clipboard import validate_transport


class ClipboardTransportContract(unittest.TestCase):
    def setUp(self):
        self.source = dict(uid=42, items=["", "parent", "child", "child"], resources=["image-sha"])
        self.restored = dict(uid=43, items=["", "fresh-parent", "fresh-child", "fresh-child"],
                             resources=["image-sha"], event="Rich paste applied", readOnly=True,
                             independentDescriptionChecked=True)

    def test_accepts_only_cross_uid_read_only_rich_transport_with_fresh_structure(self):
        validate_transport(self.source, self.restored)
        for key, value in [("uid", 42), ("event", "Plain paste applied"), ("readOnly", False),
                           ("independentDescriptionChecked", False), ("resources", ["other-image"])]:
            with self.assertRaises(ValueError):
                validate_transport(self.source, dict(self.restored, **{key: value}))

    def test_shared_or_missing_list_identity_is_not_a_transport_pass(self):
        for items in (["parent", "child"], ["parent", "fresh-child"], [""], ["fresh-parent"]):
            with self.assertRaises(ValueError):
                validate_transport(self.source, dict(self.restored, items=items))
