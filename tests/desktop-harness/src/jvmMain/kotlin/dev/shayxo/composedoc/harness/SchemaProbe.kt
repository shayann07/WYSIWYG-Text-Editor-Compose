package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Export/verify old codec fixtures for isolated S0 experiments. No production schema change. */
fun runSchemaProbe(args: Array<String>) {
    val output = File(checkNotNull(args.firstOrNull { it.startsWith("--schema-output=") }).substringAfter('='))
    if ("--schema-probe-verify" in args) {
        for (version in 1..3) {
            val original = DocumentFiles.decode(File(output, "original-v$version.astra").readBytes())
            val roundtrip = DocumentFiles.decode(File(output, "roundtrip-v$version.astra").readBytes())
            check(roundtrip.same(original)) { "S0 migration changed version $version content/structure/attributes" }
        }
        println("PASS S0 actual DocumentFiles old-load/new-save versions 1,2,3")
        return
    }
    check(output.mkdirs()) { "Use a new schema fixture directory" }
    val text = "اردو ک\u200dتاب\nEnglish\n\uD83D\uDE00\uD800\uFFFF"
    val document = Document(
        text.mapIndexed { i, c ->
            val hard = c == '\n' && i == text.indexOf('\n')
            TextUnit(
                i + 1L,
                c,
                if (hard) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (hard) TextStyleFlags() else TextStyleFlags(bold = i % 3 == 0, italic = i % 3 == 1),
                if (hard) TextStyleFlags(true, true) else TextStyleFlags(),
            )
        },
        TextStyleFlags(italic = true),
    )
    val golden = LinkedHashMap<String, Any>()
    for (version in 1..3) {
        val bytes = if (version == 3) {
            DocumentFiles.encode(document)
        } else {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x41535450)
                out.writeInt(version)
                out.writeInt(document.units.size)
                if (version > 1) out.writeByte(document.startStyle.bits)
                for (u in document.units) {
                    out.writeChar(u.char.code)
                    out.writeByte(u.kind.ordinal)
                    if (version >
                        1
                    ) {
                        out.writeByte(
                            if (u.kind ==
                                UnitKind.PARAGRAPH_BREAK
                            ) {
                                u.paragraphStyle.bits
                            } else {
                                u.style.bits
                            },
                        )
                    }
                }
            }
            val payload = buffer.toByteArray()
            payload + MessageDigest.getInstance("SHA-256").digest(payload)
        }
        File(output, "original-v$version.astra").writeBytes(bytes)
        val decoded = DocumentFiles.decode(bytes)
        golden[version.toString()] = mapOf(
            "startStyle" to decoded.startStyle.bits,
            "units" to
                decoded.units.map { u -> listOf(u.char.code, u.kind.ordinal, u.style.bits, u.paragraphStyle.bits) },
        )
    }
    File(output, "golden.json").writeText(evidenceJson(golden), Charsets.UTF_8)
    val identified = document.copy(units = document.units.mapIndexed { i, u -> u.copy(id = 101L + i * 7) })
    File(output, "identity-probe.json").writeText(
        evidenceJson(
            mapOf(
                "originalUnitIds" to identified.units.map { it.id },
                "engineLoadedUnitIds" to EditorEngine(identified).snapshot().committed.units.map { it.id },
                "codecLoadedUnitIds" to DocumentFiles.decode(DocumentFiles.encode(identified)).units.map { it.id },
                "scope" to
                    "Prototype intentionally allocates local identities; production persistence mapping remains unresolved",
            ),
        ),
        Charsets.UTF_8,
    )
    println("PASS S0 actual DocumentFiles fixture generation; no schema frozen")
}
