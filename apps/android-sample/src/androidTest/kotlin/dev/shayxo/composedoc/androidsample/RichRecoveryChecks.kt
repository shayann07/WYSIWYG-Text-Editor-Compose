package dev.shayxo.composedoc.androidsample

import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.AndroidRecoveryStore
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real private storage, independent expected text/styles; these checks do not claim power-loss durability. */
internal fun AdapterInstrumentation.richRecoveryChecks(root: File) {
    recoveryOrdering(root)
    recoveryFallback(root)
    recoveryMetadata(root)
    recoveryQuota(root)
}

private fun AdapterInstrumentation.recoveryOrdering(root: File) {
    group("recovery generation ordering, identity reuse, captured discard and separate provisional evidence") {
        val store = AndroidRecoveryStore(File(root, "ordered-recovery"))
        val owner = UUID.randomUUID().toString()
        val (model, host) = fixture("اُردو English e\u0301 👩🏽‍💻\n\n")
        val accepted = main {
            model.dispatch(EditorIntent.Select(Selection(5, 0)))
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = true, underline = true, size = 32)))
            model.snapshot().committed
        }
        check(accepted.text == "اُردو English e\u0301 👩🏽‍💻\n\n")
        check(accepted.units.take(5).all { it.style.bold && it.style.underline && it.style.size == 32 })
        val proposal = main {
            connection(host).apply {
                setSelection(0, 0)
                setComposingText("仮", 1)
            }
            check(model.snapshot().committed.same(accepted))
            checkNotNull(model.captureRecoveryDraft()).document
        }
        check(proposal.text == "仮" + accepted.text)
        check(store.checkpoint(owner, 1, "Untitled", accepted, proposal, "Separate native proposal"))
        check(store.read(owner, 1).document.same(accepted))
        check(store.read(owner, 1).proposal?.same(proposal) == true)
        check(store.checkpoint(owner, 1, "Untitled", accepted, proposal, "Separate native proposal"))
        val next = main { fixture("new accepted").first.snapshot().committed }
        check(runCatching { store.checkpoint(owner, 1, "Untitled", next) }.isFailure)
        val captured = store.entries().single()
        check(store.checkpoint(owner, 2, "Named.astra", next))
        check(runCatching { store.checkpoint(owner, 1, "Untitled", accepted) }.isFailure)
        check(store.discard(captured.owner, captured.sequence))
        check(store.entries().single().sequence == 2L)
        check(store.read(owner, 2).document.same(next))
        check(store.discard(owner, 2))
        check(store.entries().isEmpty())
        check(runCatching { store.checkpoint(owner, 2, "other", accepted) }.isFailure)
        check(store.checkpoint(owner, 3, "New generation", accepted))
        check(main { runCatching { store.entries() }.isFailure })
    }
}

private fun AdapterInstrumentation.recoveryFallback(root: File) {
    group("truncated checkpoint fallback, incomplete publication, export/read failure preserve all evidence") {
        val directory = File(root, "fallback-recovery")
        val store = AndroidRecoveryStore(directory)
        val owner = UUID.randomUUID().toString()
        val first = main { fixture("kept ک\u200dتاب\nEnglish").first.snapshot().committed }
        val second = main { fixture("newest").first.snapshot().committed }
        store.checkpoint(owner, 1, "first", first)
        val firstBytes = store.export(owner, 1)
        File(directory, "$owner/slot-0.bin.new").writeBytes(byteArrayOf(1, 2, 3))
        check(store.read(owner, 1).document.same(first))
        store.checkpoint(owner, 2, "second", second)
        val latest = File(directory, "$owner/slot-0.bin")
        latest.writeBytes(latest.readBytes().copyOf(17))
        val entry = store.entries().single()
        check(entry.sequence == 1L && entry.warning != null)
        check(store.read(owner, 1).document.same(first))
        check(store.export(owner, 1).contentEquals(firstBytes))
        check(runCatching { store.read(owner, 2) }.isFailure)
        check(runCatching { store.export(owner, 2) }.isFailure)
        check(latest.exists() && latest.length() == 17L)
        File(directory, "$owner/order.bin").writeBytes(byteArrayOf(0))
        check(store.entries().single().warning?.contains("Ordering") == true)
        check(File(directory, "$owner/slot-1.bin").readBytes().contentEquals(firstBytes))
        val unrelatedOwner = UUID.randomUUID().toString()
        store.checkpoint(unrelatedOwner, 1, "independent", second)
        check(store.entries().any { it.owner == unrelatedOwner })
        val blocked = File(directory, "$unrelatedOwner/slot-0.bin")
        check(blocked.mkdir())
        File(blocked, "obstacle").writeText("Keep owned failure evidence")
        check(runCatching { store.checkpoint(unrelatedOwner, 2, "publication", first) }.isFailure)
        check(store.read(unrelatedOwner, 1).document.same(second))
        check(runCatching { store.checkpoint(unrelatedOwner, 2, "different content", second) }.isFailure)
        check(blocked.renameTo(File(directory, "$unrelatedOwner/retained-obstacle")))
        check(store.checkpoint(unrelatedOwner, 2, "publication", first))
        check(store.read(unrelatedOwner, 2).document.same(first))
    }
}

private fun AdapterInstrumentation.recoveryMetadata(root: File) {
    group("recovery listing scans bounded metadata without decoding a corrupt full document") {
        val directory = File(root, "metadata-recovery")
        val store = AndroidRecoveryStore(directory)
        val owner = UUID.randomUUID().toString()
        val document = main { fixture("metadata witness").first.snapshot().committed }
        store.checkpoint(owner, 1, "visible metadata", document)
        val file = File(directory, "$owner/slot-1.bin")
        val envelope = file.readBytes()
        val payload = AndroidDocumentCodec.encode(document)
        val offset = (0..envelope.size - payload.size).first { start ->
            payload.indices.all {
                envelope[start + it] ==
                    payload[it]
            }
        }
        envelope[offset] = (envelope[offset].toInt() xor 1).toByte()
        val body = envelope.copyOf(envelope.size - 32)
        MessageDigest.getInstance("SHA-256").digest(body).copyInto(envelope, body.size)
        file.writeBytes(envelope)
        check(store.entries().single().name == "visible metadata")
        check(runCatching { store.read(owner, 1) }.isFailure)
        check(file.readBytes().contentEquals(envelope))
        check(store.entries().single().sequence == 1L)
    }
}

private fun AdapterInstrumentation.recoveryQuota(root: File) {
    group("recovery quota/failure retains previous copy and resolved owners do not exhaust draft count") {
        val directory = File(root, "quota-recovery")
        val store = AndroidRecoveryStore(directory, 70000)
        val owner = UUID.randomUUID().toString()
        val small = main { fixture("retained").first.snapshot().committed }
        val large = main { fixture("ا".repeat(10000)).first.snapshot().committed }
        store.checkpoint(owner, 1, "kept", small)
        val error = runCatching { store.checkpoint(owner, 2, "too large", large) }.exceptionOrNull()
        check(error?.message?.contains("quota") == true)
        check(store.read(owner, 1).document.same(small))
        check(runCatching { store.checkpoint(owner, 2, "rebound identity", small) }.isFailure)
        val expanded = AndroidRecoveryStore(directory)
        check(expanded.checkpoint(owner, 2, "too large", large))
        check(expanded.read(owner, 2).document.same(large))
        val many = AndroidRecoveryStore(File(root, "resolved-owners"))
        repeat(20) {
            val id = UUID.randomUUID().toString()
            check(many.checkpoint(id, 1, "disposable", small))
            check(many.discard(id, 1))
        }
        check(many.entries().isEmpty())
        check(many.checkpoint(UUID.randomUUID().toString(), 1, "after resolved", small))
    }
}
