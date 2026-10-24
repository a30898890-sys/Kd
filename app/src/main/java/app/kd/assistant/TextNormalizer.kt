package app.kd.assistant

import java.text.Normalizer
import java.util.Locale

/** Pure Kotlin (no Android classes) so it can be unit-tested on the JVM. */
object TextNormalizer {
    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private val punctuation = Regex("[।,?!;:\"'“”‘’()\\[\\]]+")
    private val whitespace = Regex("\\s+")
    private val wakePattern = Regex(
        "^(?:(?:হেই|হে|hey|hi)\\s+)?(?:কেডি|কেডী|কে ডি|ক্যাডি|kd|k d)(?:\\s+|$)"
    )

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Unicode NFC, lowercase, Bangla digits -> ASCII, punctuation removed, spaces collapsed. */
    fun normalize(raw: String): String {
        val lowered = nfc(raw).trim().lowercase(Locale.ROOT)
        val digits = buildString {
            for (c in lowered) {
                val i = BN_DIGITS.indexOf(c)
                append(if (i >= 0) ('0' + i) else c)
            }
        }
        return digits.replace(punctuation, " ")
            .split(whitespace)
            .map { it.trim('.') }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun stripWakeWord(normalized: String): String = normalized.replace(wakePattern, "").trim()

    fun tokens(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(" ")
}
