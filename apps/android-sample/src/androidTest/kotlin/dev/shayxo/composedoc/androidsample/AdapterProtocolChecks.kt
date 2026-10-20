package dev.shayxo.composedoc.androidsample

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.ui.EditorIntent

internal fun AdapterInstrumentation.protocolChecks() {
    protocolChecksCase1()
    protocolChecksCase2()
    protocolChecksCase3()
    protocolChecksCase4()
    protocolChecksCase5()
    protocolChecksCase6()
    protocolChecksCase7()
    protocolChecksCase8()
    protocolChecksCase9()
    protocolChecksCase10()
    protocolChecksCase11()
    protocolChecksCase12()
    protocolChecksCase13()
}

private fun AdapterInstrumentation.protocolChecksCase1() {
    group("production composing/commit/query cursor rules and raw UTF16") {
        val (
            v,
            h,
        ) = fixture("abcd")
        main {
            val c = connection(h)
            check(
                c.setSelection(
                    3,
                    1,
                ),
            )
            check(
                c.setComposingText(
                    "日本",
                    0,
                ),
            )
            check(
                v.snapshot().visible.text == "a日本d" &&
                    v.snapshot().committed.text == "abcd",
            )
            check(
                v.snapshot().selection == Selection(
                    1,
                    1,
                ),
            )
            check(
                c.getTextAfterCursor(
                    999,
                    0,
                ).toString() == "日本d",
            )
            check(
                c.setSelection(
                    3,
                    1,
                ),
            )
            check(c.getSelectedText(0).toString() == "日本")
            val e = c.getExtractedText(
                null,
                0,
            )!!
            check(
                e.startOffset == 0 &&
                    e.selectionStart == 3 &&
                    e.selectionEnd == 1,
            )
            check(c.finishComposingText())
            check(v.snapshot().committed.text == "a日本d")
            c.closeConnection()
            v.detach()
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase2() {
    group("Android composing region clips/reorders and preserves selection") {
        val(
            v,
            h,
        ) = fixture("abcdef")
        main {
            val c = connection(h)
            c.setSelection(
                5,
                2,
            )
            check(
                c.setComposingRegion(
                    999,
                    -999,
                ),
            )
            check(v.snapshot().marked == 0..5)
            check(
                v.snapshot().selection == Selection(
                    5,
                    2,
                ),
            )
            c.setComposingText(
                "اُردو",
                1,
            )
            check(v.snapshot().committed.text == "abcdef")
            check(
                c.setComposingRegion(
                    2,
                    2,
                ),
            )
            check(
                v.snapshot().marked == null &&
                    v.snapshot().committed.text == "اُردو",
            )
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase3() {
    group("UTF16 deletion differs from scalar deletion; malformed scalar request rejects atomically") {
        val(
            v,
            h,
        ) = fixture("A😀B")
        main {
            var c = connection(h)
            c.setSelection(
                3,
                3,
            )
            check(
                c.deleteSurroundingTextInCodePoints(
                    1,
                    0,
                ),
            )
            check(v.snapshot().committed.text == "AB")
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(
                !c.commitText(
                    "stale",
                    1,
                ),
            )
            c = connection(h)
            c.setSelection(
                3,
                3,
            )
            check(
                c.deleteSurroundingText(
                    1,
                    0,
                ),
            )
            check(v.snapshot().committed.text == "A\ud83dB")
            val before = v.snapshot()
            check(
                !c.deleteSurroundingTextInCodePoints(
                    1,
                    0,
                ),
            )
            check(v.snapshot().committed == before.committed)
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase4() {
    group("surrounding deletion preserves reversed selected attributes and exact UndoRedo") {
        val(
            v,
            h,
        ) = fixture("abcde")
        main {
            v.dispatch(
                EditorIntent.Select(
                    Selection(
                        1,
                        4,
                    ),
                ),
            )
            v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
            val old = v.snapshot()
            val c = connection(h)
            c.setSelection(
                4,
                1,
            )
            check(
                c.deleteSurroundingText(
                    999,
                    999,
                ),
            )
            check(v.snapshot().committed.text == "bcd")
            check(v.snapshot().committed.units.all { it.style.bold })
            check(v.snapshot().undoCount == old.undoCount + 1)
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(v.snapshot().committed.same(old.committed))
            v.dispatch(EditorIntent.Command(EditAction.Redo))
            check(v.snapshot().committed.text == "bcd")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase5() {
    group("batch selection notifications defer; queries observe immediate owner state") {
        val(
            v,
            h,
        ) = fixture()
        main {
            var notifications = 0
            h.accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun sendAccessibilityEvent(host: View, type: Int) {
                        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)notifications++
                    }
                }
            val c = connection(h)
            check(c.beginBatchEdit())
            check(c.beginBatchEdit())
            c.setComposingText(
                "に",
                1,
            )
            check(
                c.getTextBeforeCursor(
                    99,
                    0,
                ).toString() == "に",
            )
            c.commitText(
                "日本",
                1,
            )
            check(notifications == 0)
            check(c.endBatchEdit())
            check(notifications == 0)
            check(c.endBatchEdit())
            check(notifications == 1)
            check(!c.endBatchEdit())
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase6() {
    group("replaced/closed connection cannot mutate a replacement native session") {
        assertSpeculativeConnection()
        val(
            v,
            h,
        ) = fixture()
        main {
            val old = connection(h)
            old.setComposingText(
                "draft",
                1,
            )
            val fresh = connection(h)
            check(v.snapshot().committed.text == "")
            check(
                !old.commitText(
                    "LATE",
                    1,
                ),
            )
            check(
                old.getTextBeforeCursor(
                    9,
                    0,
                ) == null,
            )
            check(
                fresh.commitText(
                    "new",
                    1,
                ),
            )
            old.closeConnection()
            check(v.snapshot().committed.text == "new")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase7() {
    group("programmatic replacement invalidates old native coordinates even when cursor offset is unchanged") {
        val(
            v,
            h,
        ) = fixture("a")
        main {
            val old = connection(h)
            v.dispatch(
                EditorIntent.Type(
                    "b",
                    "Application replacement",
                    range = Selection(
                        0,
                        1,
                    ),
                ),
            )
            check(
                v.snapshot().selection == Selection(
                    1,
                    1,
                ),
            )
            check(
                !old.commitText(
                    "stale",
                    1,
                ),
            )
            val fresh = connection(h)
            check(
                fresh.getTextBeforeCursor(
                    99,
                    0,
                ).toString() == "b",
            )
            check(
                fresh.commitText(
                    "c",
                    1,
                ),
            )
            check(v.snapshot().committed.text == "bc")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase8() {
    group("production zero partial commitment preserves every island, mark, selection and history") {
        val(
            v,
            h,
        ) = fixture("abcd")
        main {
            val c = connection(h)
            c.setSelection(
                1,
                3,
            )
            c.setComposingText(
                "XYZ",
                1,
            )
            c.setComposingRegion(
                2,
                4,
            )
            val before = v.snapshot()
            check(v.dispatch(EditorIntent.Native(dev.shayxo.composedoc.domain.NativeEdit.Partial(0))))
            val after = v.snapshot()
            check(
                before.visible == after.visible &&
                    before.committed == after.committed,
            )
            check(
                before.marked == after.marked &&
                    before.selection == after.selection &&
                    before.undoCount == after.undoCount,
            )
            c.closeConnection()
            check(v.snapshot().committed.text == "abcd")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase9() {
    group("hardware event identity and soft printing key do not duplicate accepted input") {
        val(
            v,
            h,
        ) = fixture()
        main {
            val c = connection(h)
            val t = SystemClock.uptimeMillis()
            val a = KeyEvent(
                t,
                t,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_A,
                0,
            )
            check(c.sendKeyEvent(a))
            check(c.sendKeyEvent(a))
            check(v.snapshot().committed.text == "a")
            c.commitText(
                "b",
                1,
            )
            val soft = KeyEvent(
                t + 1,
                t + 1,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_B,
                0,
                0,
                0,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD,
            )
            check(!c.sendKeyEvent(soft))
            check(v.snapshot().committed.text == "ab")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase10() {
    group("Enter/editor action creates structural paragraph; active formatting waits for real finish") {
        val(
            v,
            h,
        ) = fixture()
        main {
            val c = connection(h)
            c.setComposingText(
                "کتاب",
                1,
            )
            v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
            check(v.snapshot().waiting)
            check(c.finishComposingText())
            check(v.snapshot().committed.units.all { it.style.bold })
            check(c.performEditorAction(EditorInfo.IME_ACTION_NONE))
            check(v.snapshot().committed.units.last().kind == UnitKind.PARAGRAPH_BREAK)
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(v.snapshot().committed.text == "کتاب")
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(v.snapshot().committed.units.none { it.style.bold })
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase11() {
    group("cross-paragraph native replacement and unaffected formatting survive exact UndoRedo") {
        val(
            v,
            h,
        ) = fixture("ab\ncd\n")
        main {
            v.dispatch(
                EditorIntent.Select(
                    Selection(
                        0,
                        1,
                    ),
                ),
            )
            v.dispatch(EditorIntent.ToggleFormat(FormatKey.ITALIC))
            val before = v.snapshot().committed
            val c = connection(h)
            c.setSelection(
                1,
                4,
            )
            c.setComposingText(
                "XY",
                1,
            )
            check(v.snapshot().committed.same(before))
            c.finishComposingText()
            check(
                v.snapshot().committed.text == "aXYd\n" &&
                    v.snapshot().committed.units.first().style.italic,
            )
            val after = v.snapshot().committed
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(v.snapshot().committed.same(before))
            v.dispatch(EditorIntent.Command(EditAction.Redo))
            check(v.snapshot().committed.same(after))
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase12() {
    group("accepted prefix and empty preedit cancellation retain canonical content") {
        val(
            v,
            h,
        ) = fixture("Hello ")
        main {
            val c = connection(h)
            c.commitText(
                "a",
                1,
            )
            c.setComposingText(
                "draft",
                1,
            )
            c.setComposingText(
                "",
                1,
            )
            check(v.snapshot().committed.text == "Hello a")
            c.closeConnection()
            check(v.snapshot().committed.text == "Hello a")
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            check(v.snapshot().committed.text == "Hello ")
        }
    }
}

private fun AdapterInstrumentation.protocolChecksCase13() {
    group("surrounding/extracted queries preserve joiners, emoji and isolates without normalization") {
        val fixtures = listOf(
            "اُردو، می\u200cروم ک\u200dتاب",
            "العَرَبِيَّة لَا ١٢",

            "English 12.5 https://example.org (עברית)",
            "👩🏽‍💻🇵🇰 e\u0301\u2066abc\u2069",

        )
        fixtures.forEach { raw ->
            val(
                v,
                h,
            ) = fixture()
            main {
                val c = connection(h)
                c.setComposingText(
                    raw,
                    1,
                )
                check(
                    c.getTextBeforeCursor(
                        Int.MAX_VALUE,
                        0,
                    ).toString() == raw,
                )
                check(
                    c.getExtractedText(
                        null,
                        0,
                    )!!.text.toString() == raw,
                )
                check(v.snapshot().committed.text == "")
                c.commitText(
                    raw,
                    1,
                )
                check(v.snapshot().committed.text == raw)
                v.dispatch(EditorIntent.Command(EditAction.Undo))
                check(v.snapshot().committed.text == "")
                v.dispatch(EditorIntent.Command(EditAction.Redo))
                check(v.snapshot().committed.text == raw)
            }
        }
    }
}

internal fun AdapterInstrumentation.speculativeConnectionCheck() {
    group("unused framework connection creation preserves the served connection") { assertSpeculativeConnection() }
}

private fun AdapterInstrumentation.assertSpeculativeConnection() {
    val (owner, view) = fixture()
    main {
        val served = connection(view)
        check(served.commitText("a", 1))
        val unused = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        check(served.setComposingText("候補", 1)) { "Unused factory call retired the served connection" }
        unused.closeConnection()
        check(owner.snapshot().visible.text == "a候補" && owner.snapshot().committed.text == "a")
        check(served.finishComposingText())
        check(owner.snapshot().committed.text == "a候補")
        val next = connection(view)
        check(next.commitText("b", 1))
        check(!served.commitText("STALE", 1))
        check(owner.snapshot().committed.text == "a候補b")
        val proposals = mutableListOf<String>()
        view.onUnfinishedInput = {
            proposals += it.document.text
            check(!next.commitText("LATE", 1))
        }
        check(next.setComposingText("draft", 1))
        next.closeConnection()
        check(proposals == listOf("a候補bdraft"))
        check(owner.snapshot().committed.text == "a候補b" && owner.snapshot().marked == null)
    }
}
