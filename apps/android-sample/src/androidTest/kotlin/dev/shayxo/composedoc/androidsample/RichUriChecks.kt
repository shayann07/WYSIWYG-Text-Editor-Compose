package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.content.Intent
import android.net.Uri
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.AndroidUriRepository
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal fun AdapterInstrumentation.richUriChecks(root: File) {
    uriSafety(root)
    asynchronousSave(root)
    pickerCancellation(root)
    partialProviderWrite(root)
    asynchronousImage(root)
}

private fun AdapterInstrumentation.asynchronousImage(root: File) {
    group("image picker cancellation corrupt/revoked payloads and delayed stale results preserve captured writing") {
        val (file, uri) = providerFile()
        file.writeBytes(testPng(3, 2, android.graphics.Color.BLUE))
        val controller = main {
            AndroidOwners(File(root, "image-pickers/documents"), true).also { it.attach(targetContext) }
        }
        fun choose(result: Int = Activity.RESULT_OK) = main {
            val entry = controller.tab
            val request = AndroidPickerRequest(
                entry.id,
                ++entry.operation,
                AndroidPickerKind.IMAGE,
                entry.model.snapshot().committed,
                entry.model.clipboardCapture(),
            )
            controller.pickers[5010] = request
            controller.pickerResult(activity, 5010, result, Intent().setData(Uri.parse(uri)))
        }
        try {
            main { controller.current.dispatch(EditorIntent.Type("kept اُردو")) }
            val original = main { controller.current.snapshot() }
            choose(Activity.RESULT_CANCELED)
            main { check(controller.current.snapshot().committed.same(original.committed)) }
            val gate = File(file.path + ".hold-read").apply { writeText("delayed image read") }
            choose()
            waitUntil { File(file.path + ".entered").exists() }
            val edited = main {
                controller.current.dispatch(EditorIntent.Type(" newer"))
                controller.current.snapshot()
            }
            check(gate.delete())
            waitUntil { main { controller.status.contains("target changed") } }
            main {
                check(controller.current.snapshot().committed.same(edited.committed))
                check(controller.current.snapshot().undoCount == edited.undoCount)
            }
            for (denied in listOf(false, true)) {
                file.writeBytes(byteArrayOf(1, 2, 3))
                if (denied) File(file.path + ".denied").writeText("revoked image grant")
                main { controller.status = "awaiting image validation" }
                choose()
                waitUntil { main { controller.status.contains("IO failed") } }
                main {
                    check(controller.current.snapshot().committed.same(edited.committed))
                    check(controller.current.snapshot().undoCount == edited.undoCount)
                    check(controller.current.snapshot().committed.resources.isEmpty())
                }
            }
        } finally {
            main { controller.dispose() }
        }
    }
}

private fun AdapterInstrumentation.partialProviderWrite(root: File) {
    group("provider incomplete output is never reported saved and previous/intended copies remain recoverable") {
        val (file, uri) = providerFile()
        val repository = AndroidUriRepository(targetContext, File(root, "partial-safety"))
        val before = main { fixture("previous document").first.snapshot().committed }
        val after = main { fixture("new intended اُردو").first.snapshot().committed }
        val digest = repository.write(uri, before, null)
        File(file.path + ".corrupt").writeText("partial fixture")
        val failed = runCatching { repository.write(uri, after, digest) }.exceptionOrNull()
        check(failed?.message?.contains("verification failed") == true)
        waitUntil { File(file.path + ".partial").exists() }
        check(file.length() == 12L)
        val copies = SaveSafetyCopies(File(root, "partial-safety"))
        val captured = copies.entries()
        val intended = captured.filter {
            it.name.endsWith(".astra")
        }.map { AndroidDocumentCodec.decode(copies.read(it)) }
        check(intended.any { it.same(before) } && intended.any { it.same(after) })
        val prior = captured.filter { it.name.startsWith("previous-") }.map { copies.read(it) }
        check(prior.any { it.contentEquals(AndroidDocumentCodec.encode(before)) })
        val record = captured.first { it.name.endsWith(".astra") }
        val original = copies.read(record)
        val path = File(root, "partial-safety/${record.name}")
        path.appendBytes(byteArrayOf(0))
        check(runCatching { copies.discard(record) }.isFailure)
        check(path.readBytes().contentEquals(original + byteArrayOf(0)))
    }
}

private fun AdapterInstrumentation.providerFile(): Pair<File, String> {
    val id = UUID.randomUUID().toString()
    val file = File(targetContext.filesDir, "provider-fixtures/$id")
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    file.writeBytes(byteArrayOf())
    return file to "content://${targetContext.packageName}.fixture/$id"
}

private fun AdapterInstrumentation.uriSafety(root: File) {
    group("content URI conflict protection and inherited write ports retain intended/prior safety copies") {
        val (file, uri) = providerFile()
        val repository = AndroidUriRepository(targetContext, File(root, "uri-safety"))
        val original = main { fixture("original اُردو").first.snapshot().committed }
        val replacement = main { fixture("replacement e\u0301 👩🏽‍💻").first.snapshot().committed }
        val first = repository.write(uri, original, null)
        check(repository.read(uri).document.same(original))
        val port: DocumentRepository = repository
        check(runCatching { port.save(uri, replacement) }.isFailure)
        check(runCatching { repository.writeBytes(uri, byteArrayOf(1)) }.isFailure)
        check(repository.read(uri).sha256 == first)
        file.writeBytes(AndroidDocumentCodec.encode(replacement))
        check(runCatching { repository.write(uri, original, first) }.isFailure)
        check(repository.read(uri).document.same(replacement))
        val hash = repository.read(uri).sha256
        File(file.path + ".readonly").writeText("deny writing")
        check(runCatching { repository.write(uri, original, hash) }.isFailure)
        val safety = File(root, "uri-safety").listFiles().orEmpty().filter { it.extension == "astra" }
        check(safety.size == 2)
        check(safety.all { AndroidDocumentCodec.decode(it.readBytes()).same(original) })
        val expectedPrior = AndroidDocumentCodec.encode(replacement)
        val priorCopies = File(root, "uri-safety").listFiles().orEmpty().filter { it.name.startsWith("previous-") }
        check(priorCopies.any { it.readBytes().contentEquals(expectedPrior) })
        check(file.readBytes().contentEquals(AndroidDocumentCodec.encode(replacement)))
        File(file.path + ".denied").writeText("denied")
        check(runCatching { repository.read(uri) }.exceptionOrNull() is SecurityException)
        check(main { runCatching { repository.read(uri) }.isFailure })
        check(runCatching { repository.read(file.path) }.isFailure)
    }
}

private fun AdapterInstrumentation.asynchronousSave(root: File) {
    group("slow asynchronous save targets captured owner/basis and leaves later edits dirty with one completion") {
        val (file, uri) = providerFile()
        val controller = main {
            AndroidOwners(File(root, "asynchronous/documents"), true).also { it.attach(targetContext) }
        }
        val entered = File(file.path + ".entered")
        val hold = File(file.path + ".hold").apply { writeText("hold") }
        val completions = AtomicInteger()
        val captured = main {
            controller.current.dispatch(EditorIntent.Type("captured کتاب"))
            controller.current.snapshot().committed.also {
                controller.saveTo(controller.tab, uri, null) { completions.incrementAndGet() }
            }
        }
        waitUntil { entered.exists() }
        main {
            controller.current.dispatch(EditorIntent.Type(" newer"))
            controller.active = 1
            controller.current.dispatch(EditorIntent.Type("other owner"))
            check(controller.tabs[0].saveInFlight)
        }
        check(hold.delete())
        waitUntil { completions.get() == 1 }
        main {
            check(controller.active == 1)
            check(controller.current.snapshot().committed.text == "other owner")
            check(controller.tabs[0].model.snapshot().committed.text == "captured کتاب newer")
            check(controller.tabs[0].saved?.same(captured) == true)
            check(controller.tabs[0].dirty && !controller.tabs[0].saveInFlight)
            check(controller.tabs[1].uri == null)
        }
        check(AndroidDocumentCodec.decode(file.readBytes()).same(captured))
        main { controller.dispose() }
    }
}

private fun AdapterInstrumentation.pickerCancellation(root: File) {
    group("cancelled duplicate malformed and foreign picker results cannot replace current writing") {
        val controller = main {
            AndroidOwners(File(root, "pickers/documents"), true).also { it.attach(targetContext) }
        }
        val (file, uri) = providerFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        main {
            val entry = controller.tab
            entry.model.dispatch(EditorIntent.Type("keep"))
            val capture = entry.model.clipboardCapture()
            val request = AndroidPickerRequest(
                entry.id,
                ++entry.operation,
                AndroidPickerKind.OPEN,
                entry.model.snapshot().committed,
                capture,
            )
            controller.pickers[5000] = request
            controller.pickerResult(activity, 5000, Activity.RESULT_CANCELED, null)
            controller.pickerResult(activity, 5000, Activity.RESULT_OK, Intent().setData(Uri.parse(uri)))
            check(controller.models.size == 2 && controller.current.snapshot().committed.text == "keep")
            controller.pickers[5001] = request.copy(generation = ++entry.operation)
            controller.pickerResult(activity, 5001, Activity.RESULT_OK, Intent().setData(Uri.parse(uri)))
        }
        waitUntil { main { controller.status.contains("IO failed") } }
        main {
            check(controller.models.size == 2 && controller.current.snapshot().committed.text == "keep")
            check(controller.current.snapshot().selection == Selection(4, 4))
            controller.dispose()
        }
        check(file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }
}
