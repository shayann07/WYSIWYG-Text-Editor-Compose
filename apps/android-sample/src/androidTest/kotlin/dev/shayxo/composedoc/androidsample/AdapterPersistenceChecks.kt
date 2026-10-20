package dev.shayxo.composedoc.androidsample

import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.AndroidDocumentRepository
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.androidTextSliceLimitation
import java.io.File
import java.security.MessageDigest

internal fun AdapterInstrumentation.persistenceChecks(root: File) {
    persistenceChecksCase1(root)
    persistenceChecksCase2(root)
    persistenceChecksCase3()
}

private fun AdapterInstrumentation.persistenceChecksCase1(root: File) {
    group("packaged Android codec/repository retains raw units, inline/paragraph style and trailing paragraphs") {
        val (v, h) = fixture("اُردو\nEnglish e\u0301 😀\n\n")
        val expected = main {
            v.dispatch(EditorIntent.Select(Selection(0, 5)))
            v.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = true, font = StoredFont.GULZAR, size = 32)))
            v.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.END))
            v.snapshot().committed
        }
        val repo = AndroidDocumentRepository(File(root, "documents"))
        repo.save("first.astra", expected)
        val read = repo.load("first.astra")
        check(read.same(expected))
        check(read.text == "اُردو\nEnglish e\u0301 😀\n\n")
        check(read.paragraphs().size == 4)
        check(read.units.take(5).all { it.style.bold && it.style.font == StoredFont.GULZAR && it.style.size == 32 })
        check(
            read.startParagraph.direction == ParagraphDirection.RTL &&
                read.startParagraph.alignment == ParagraphAlignment.END,
        )
        main {
            val c = connection(h)
            c.setSelection(0, 0)
            c.setComposingText("PROVISIONAL", 1)
        }
        repo.save("committed-only.astra", main { v.snapshot().committed })
        check(repo.load("committed-only.astra").text == expected.text)
    }
}

private fun AdapterInstrumentation.persistenceChecksCase2(root: File) {
    group("AtomicFile fallback, unsupported/corrupt snapshot and IO failure preserve previous save") {
        val repo = AndroidDocumentRepository(
            File(
                root,
                "atomic",
            ),
        )
        val doc = main { fixture("kept").first.snapshot().committed }
        repo.save(
            "saved.astra",
            doc,
        )
        File(
            root,
            "atomic/saved.astra.new",
        ).writeBytes(
            byteArrayOf(
                1,
                2,
                3,
            ),
        )
        check(repo.load("saved.astra").same(doc))
        val bytes = AndroidDocumentCodec.encode(doc)
        val bad = bytes.copyOf()
        bad[0] = (bad[0].toInt() xor 1).toByte()
        check(runCatching { AndroidDocumentCodec.decode(bad) }.isFailure)
        val newer = bytes.copyOf()
        newer[7] = 10
        val body = newer.copyOfRange(
            0,
            newer.size - 32,
        )
        MessageDigest.getInstance("SHA-256").digest(body).copyInto(
            newer,
            newer.size - 32,
        )
        check(runCatching { AndroidDocumentCodec.decode(newer) }.isFailure)
        check(repo.load("saved.astra").same(doc))
        val blocked = File(
            root,
            "not-directory",
        )
        blocked.writeText("keep")
        check(
            runCatching {
                AndroidDocumentRepository(blocked).save(
                    "x.astra",
                    doc,
                )
            }.isFailure,
        )
        check(blocked.readText() == "keep")
    }
}

private fun AdapterInstrumentation.persistenceChecksCase3() {
    group("Android image resources remain document-owned and malformed bytes reject") {
        val bitmap = android.graphics.Bitmap.createBitmap(
            3,
            2,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                out,
            )
            out.toByteArray()
        }
        bitmap.recycle()
        val(
            id,
            image,
        ) = ImageDecoder.validate(bytes)
        check(
            image.width == 3 &&
                image.height == 2 &&
                image.mime == "image/png",
        )
        val doc = Document(
            listOf(
                TextUnit(
                    1,
                    '\ufffc',
                    UnitKind.IMAGE,
                    image = ImageBlock(
                        id,
                        100,
                        "owned image",
                    ),
                ),
            ),
            resources = mapOf(id to image),
        )
        val read = AndroidDocumentCodec.decode(AndroidDocumentCodec.encode(doc))
        check(
            read.same(doc) &&
                read.resources.getValue(id).bytes().contentEquals(bytes),
        )
        val bad = bytes.copyOf()
        bad[20] = (bad[20].toInt() xor 1).toByte()
        check(runCatching { ImageDecoder.validate(bad) }.isFailure)
        check(androidTextSliceLimitation(read) != null)
    }
}
