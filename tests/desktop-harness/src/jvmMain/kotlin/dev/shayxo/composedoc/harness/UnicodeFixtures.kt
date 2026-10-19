package dev.shayxo.composedoc.harness
import dev.shayxo.composedoc.model.Graphemes
import java.security.MessageDigest
import java.util.Properties
private object UnicodeFixtures {
    val hashes = Properties().apply {
        UnicodeFixtures::class.java.getResourceAsStream("/unicode/hashes.properties")!!.use(::load)
    }
    fun read(name: String): String {
        val bytes = UnicodeFixtures::class.java.getResourceAsStream("/unicode/$name")!!.use { it.readBytes() }
        check(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
                hashes.getProperty(name),
        )
        return bytes.toString(Charsets.UTF_8)
    }
}
fun Graphemes.verified(name: String): String = UnicodeFixtures.read(name)
