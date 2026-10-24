package dev.shayxo.composedoc.androidsample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.data.AndroidClipboardFiles
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun AdapterInstrumentation.richClipboardChecks() {
    transportClipboard()
    staleCutClipboard()
    plainClipboard()
}

private fun AdapterInstrumentation.transportClipboard() {
    group("actual system rich clipboard transfers attributed semantic document into a different editor process") {
        replaceMounted("عنوان\nEnglish child")
        val published = CountDownLatch(1)
        val publicationUptime = AtomicLong()
        val previous = main {
            val view = activity.inputView!!
            view.onNativeEvent.also { listener ->
                view.onNativeEvent = { event ->
                    listener(event)
                    if (event == "Rich clipboard published") {
                        publicationUptime.set(SystemClock.uptimeMillis())
                        published.countDown()
                    }
                }
            }
        }
        val before = main {
            val model = activity.owners.current
            model.dispatch(EditorIntent.Select(Selection(5, 0)))
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = true, underline = true, size = 32)))
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(2)))
            model.dispatch(EditorIntent.Select(Selection(6, 19)))
            model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.BULLET)))
            model.dispatch(EditorIntent.SelectAll)
            model.snapshot().committed.also {
                check(activity.inputView!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
            }
        }
        try {
            check(published.await(10, TimeUnit.SECONDS)) { "This copy never completed rich publication" }
        } finally {
            main { activity.inputView!!.onNativeEvent = previous }
        }
        val clip = main {
            (targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        }
        val shape = main { clipboardShape(clip, publicationUptime.get()) }
        File(evidenceRoot, "clipboard-shape.json").writeText(shape.toString(2), Charsets.UTF_8)
        check(clip.itemCount == 2 && clip.getItemAt(0).text?.contains("English child") == true) {
            "Expected two owned rich clipboard items; observed shape=$shape"
        }
        val uri = clip.getItemAt(1).uri
        check(runCatching { targetContext.contentResolver.openOutputStream(uri, "wt") }.isFailure)
        assertPeerClipboard(before)
    }
}

private fun AdapterInstrumentation.assertPeerClipboard(before: Document) {
    val token = UUID.randomUUID().toString()
    main {
        activity.startActivity(
            Intent(activity, ClipboardTransportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("blank", true).putExtra("transport", token)
                .putExtra("qualification-root", "transport-owner-$token"),
        )
    }
    val root = File(targetContext.filesDir, "qualification/transport-$token")
    waitUntil { File(root, "result.json").isFile }
    val record = JSONObject(File(root, "result.json").readText())
    check(record.getInt("pid") != Process.myPid())
    check(record.getString("event") == "Rich paste applied")
    val restored = AndroidDocumentCodec.decode(File(root, "document.astra").readBytes())
    check(restored.text == before.text)
    check(restored.units.map { it.style } == before.units.map { it.style })
    check(restored.paragraphsIgnoringIds() == before.paragraphsIgnoringIds())
    check(restored.units.map { it.id }.toSet().size == restored.units.size)
}

private fun AdapterInstrumentation.clipboardShape(clip: ClipData, publishedAt: Long): JSONObject {
    val kinds = (0 until minOf(clip.itemCount, 8)).map { index ->
        val item = clip.getItemAt(index)
        val authority = item.uri?.authority
        JSONObject().put("index", index).put("hasText", item.text != null).put("hasHtml", item.htmlText != null)
            .put("hasIntent", item.intent != null).put("hasUri", item.uri != null)
            .put("uriScheme", item.uri?.scheme)
            .put(
                "uriAuthority",
                if (authority ==
                    "${targetContext.packageName}.clipboard"
                ) {
                    authority
                } else {
                    "foreign-or-none"
                },
            )
    }
    return JSONObject().put("itemCount", clip.itemCount).put("itemKinds", JSONArray(kinds))
        .put(
            "mimeTypes",
            JSONArray(
                (0 until minOf(clip.description.mimeTypeCount, 8)).map {
                    clip.description.getMimeType(it).take(120)
                },
            ),
        )
        .put("publicationEventUptimeMillis", publishedAt).put("observedUptimeMillis", SystemClock.uptimeMillis())
        .put("requestedPublisherPackage", targetContext.packageName).put("requestedPublisherPid", Process.myPid())
        .put("requestedPublisherUid", Process.myUid())
        .put("observedPrimaryPublisher", "Not exposed by public ClipboardManager; no last-writer identity inferred")
        .put("scope", "Read-only bounded shape of the disposable test publication; no payload text logged")
}

private fun dev.shayxo.composedoc.model.Document.paragraphsIgnoringIds() = paragraphs().map { block ->
    block.properties.let {
        listOf(
            it.heading,
            it.list,
            it.direction,
            it.alignment,
            it.spaceBefore,
            it.spaceAfter,
            it.linePermille,
            it.markerTypography,
        )
    }
}

private fun AdapterInstrumentation.staleCutClipboard() {
    group("slow clipboard publication refuses stale Cut and keeps document/history intact") {
        waitUntil { main { activity.inputView?.isAttachedToWindow == true && activity.hasWindowFocus() } }
        replaceMounted("captured cut")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val original = main {
            val view = activity.inputView!!
            val publisher = view.publishRichClipboard
            view.publishRichClipboard = { bytes ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                checkNotNull(publisher).invoke(bytes)
            }
            activity.owners.current.dispatch(EditorIntent.SelectAll)
            check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CUT, null))
            publisher
        }
        check(entered.await(10, TimeUnit.SECONDS))
        val expected = main {
            activity.owners.current.dispatch(EditorIntent.Type("new content"))
            activity.owners.current.snapshot()
        }
        release.countDown()
        waitUntil { main { activity.owners.status.contains("selection changed") } }
        main {
            check(activity.owners.current.snapshot().committed.same(expected.committed))
            check(activity.owners.current.snapshot().undoCount == expected.undoCount)
            activity.inputView!!.publishRichClipboard = original
        }
        val beforeFailure = main {
            activity.owners.current.dispatch(EditorIntent.SelectAll)
            activity.inputView!!.publishRichClipboard = { throw java.io.IOException("Disposable cache failure") }
            activity.owners.current.snapshot().also {
                check(activity.inputView!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CUT, null))
            }
        }
        waitUntil { main { activity.owners.nativeEvents.any { it.contains("Rich Cut unavailable") } } }
        main {
            check(activity.owners.current.snapshot().committed.same(beforeFailure.committed))
            check(activity.owners.current.snapshot().undoCount == beforeFailure.undoCount)
            activity.inputView!!.publishRichClipboard = original
        }
    }
}

private fun AdapterInstrumentation.plainClipboard() {
    group("real standard Android plain clipboard paste and explicit plain fallback are undoable") {
        replaceMounted("kept ")
        val text = "العربية e\u0301 👩🏽‍💻"
        main {
            activity.owners.current.dispatch(EditorIntent.Select(Selection(5, 5)))
            val manager = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Disposable ComposeDoc fixture", text))
            check(activity.inputView!!.performAccessibilityAction(android.R.id.pasteAsPlainText, null))
        }
        waitUntil { main { activity.owners.current.snapshot().committed.text == "kept $text" } }
        main {
            activity.owners.current.dispatch(EditorIntent.Command(EditAction.Undo))
            check(activity.owners.current.snapshot().committed.text == "kept ")
        }
        val files = AndroidClipboardFiles(targetContext, "${targetContext.packageName}.clipboard")
        val fragment = main {
            activity.owners.current.dispatch(EditorIntent.SelectAll)
            RichFragment.capture(checkNotNull(activity.owners.current.clipboardCapture()))
        }
        val uri = files.publish(FragmentCodec.encode(fragment))
        check(targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.isNotEmpty())
        files.clear()
        check(runCatching { targetContext.contentResolver.openInputStream(uri) }.isFailure)
        main { check(activity.owners.current.snapshot().committed.text == "kept ") }
    }
}
