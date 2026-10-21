package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.model.Document
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Listing is metadata only: no document/resource decode or allocation. */
data class AndroidRecoveryEntry(
    val owner: String,
    val sequence: Long,
    val timestamp: Long,
    val name: String,
    val hasProposal: Boolean,
    val byteCount: Long,
    val warning: String? = null,
)

/** Explicitly loaded payload. A proposal is evidence, never accepted canonical content. */
data class AndroidRecoveryRecord(
    val entry: AndroidRecoveryEntry,
    val document: Document,
    val proposal: Document?,
    val evidence: String,
)

internal data class RecoveryHeader(
    val entry: AndroidRecoveryEntry,
    val documentBytes: Int,
    val proposalBytes: Int,
    val evidenceCharacters: Int,
    val contentHash: String,
)

internal data class RecoveryPayload(
    val name: String,
    val document: ByteArray,
    val proposal: ByteArray,
    val evidence: String,
)

internal object AndroidRecoveryFormat {
    private const val MAGIC = 0x414e5243 // ANRC: private Android recovery envelope
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val DIGEST_HEX_BYTES = DIGEST_BYTES * 2
    private const val MAX_NAME = 1024
    private const val MAX_EVIDENCE = 256_000
    private const val MAX_DOCUMENT = 20_000_000
    const val MAX_RECORD = 41_000_000
    private const val BUFFER = 8192

    fun owner(value: String): String {
        require(UUID.fromString(value).toString() == value) { "Recovery owner must be a UUID" }
        return value
    }

    fun contentHash(name: String, document: ByteArray, proposal: ByteArray, evidence: String): String {
        require(name.length <= MAX_NAME && evidence.length <= MAX_EVIDENCE) { "Recovery metadata exceeds its limit" }
        val metadata = ByteArrayOutputStream()
        DataOutputStream(metadata).use {
            it.writeInt(document.size)
            it.writeInt(proposal.size)
            it.writeRaw(name)
            it.writeRaw(evidence)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(metadata.toByteArray())
        digest.update(document)
        digest.update(proposal)
        return digest.digest().hex()
    }

    fun encode(owner: String, sequence: Long, payload: RecoveryPayload): ByteArray {
        val name = payload.name
        val document = payload.document
        val proposal = payload.proposal
        val evidence = payload.evidence
        require(sequence > 0)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(MAGIC)
            it.writeInt(VERSION)
            it.writeUTF(owner(owner))
            it.writeLong(sequence)
            it.writeLong(System.currentTimeMillis())
            it.writeRaw(name)
            it.writeInt(document.size)
            it.writeInt(proposal.size)
            it.writeInt(evidence.length)
            it.writeUTF(contentHash(name, document, proposal, evidence))
            it.write(document)
            it.write(proposal)
            evidence.forEach { char -> it.writeChar(char.code) }
        }
        val body = output.toByteArray()
        require(body.size + DIGEST_BYTES <= MAX_RECORD) { "Recovery record exceeds its limit" }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    fun header(file: File, verify: Boolean = true): RecoveryHeader {
        if (verify) verifyChecksum(file)
        return DataInputStream(FileInputStream(file)).use { header(it, file.length()) }
    }

    private fun header(input: DataInputStream, length: Long): RecoveryHeader {
        require(length in DIGEST_BYTES.toLong()..MAX_RECORD.toLong()) { "Incomplete/oversized recovery record" }
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported recovery envelope" }
        val owner = owner(input.readUTF())
        val sequence = input.readLong()
        val timestamp = input.readLong()
        require(sequence > 0 && timestamp > 0)
        val name = input.readRaw(MAX_NAME)
        val document = input.readInt()
        val proposal = input.readInt()
        val evidence = input.readInt()
        val hash = input.readUTF()
        require(hash.length == DIGEST_HEX_BYTES && hash.all { it in "0123456789abcdef" })
        require(document in 1..MAX_DOCUMENT && proposal in 0..MAX_DOCUMENT && evidence in 0..MAX_EVIDENCE)
        require(input.available().toLong() == document.toLong() + proposal + evidence * 2L + DIGEST_BYTES)
        return RecoveryHeader(
            AndroidRecoveryEntry(owner, sequence, timestamp, name, proposal > 0, length),
            document,
            proposal,
            evidence,
            hash,
        )
    }

    fun load(file: File): AndroidRecoveryRecord {
        verifyChecksum(file)
        return DataInputStream(FileInputStream(file)).use { input ->
            val h = header(input, file.length())
            val document = ByteArray(h.documentBytes).also { input.readFully(it) }
            val proposal = ByteArray(h.proposalBytes).also { input.readFully(it) }
            val evidence = CharArray(h.evidenceCharacters) { input.readChar() }.concatToString()
            require(contentHash(h.entry.name, document, proposal, evidence) == h.contentHash)
            AndroidRecoveryRecord(
                h.entry,
                AndroidDocumentCodec.decode(document),
                proposal.takeIf { it.isNotEmpty() }?.let(AndroidDocumentCodec::decode),
                evidence,
            )
        }
    }

    private fun verifyChecksum(file: File) {
        require(file.length() in DIGEST_BYTES.toLong()..MAX_RECORD.toLong()) { "Incomplete/oversized recovery record" }
        DataInputStream(FileInputStream(file)).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            var remaining = file.length() - DIGEST_BYTES
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0) { "Truncated recovery record" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
            val expected = ByteArray(DIGEST_BYTES).also { input.readFully(it) }
            require(input.read() == -1 && MessageDigest.isEqual(expected, digest.digest())) {
                "Recovery checksum failed"
            }
        }
    }

    private fun DataOutputStream.writeRaw(text: String) {
        writeInt(text.length)
        text.forEach { writeChar(it.code) }
    }
    private fun DataInputStream.readRaw(limit: Int): String {
        val count = readInt()
        require(count in 0..limit)
        return CharArray(count) { readChar() }.concatToString()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
