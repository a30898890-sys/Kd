package app.kd.assistant

import java.net.URLEncoder

sealed class KdIntent {
    data class OpenApp(val appName: String) : KdIntent()
    data class Search(val query: String) : KdIntent()
    data class OpenUrl(val url: String) : KdIntent()
    object TellTime : KdIntent()
    object TellDate : KdIntent()
    object GoHome : KdIntent()
    object Sleep : KdIntent()
    object Greeting : KdIntent()
    data class Unsupported(val feature: String, val reason: String) : KdIntent()
    data class Clarify(val question: String) : KdIntent()
    data class Unknown(val heard: String) : KdIntent()
}

/** Rule-based Bangla / Bangla-English intent extraction. Pure Kotlin, unit-testable. */
object IntentParser {
    private fun set(vararg w: String): Set<String> = w.map { TextNormalizer.nfc(it) }.toSet()

    private val openVerbs = set(
        "খোলো", "খুলো", "খোল", "খুলুন", "চালু", "ওপেন", "open",
        "চালাও", "চালান", "লঞ্চ", "launch", "start"
    )
    private val searchVerbs = set(
        "সার্চ", "search", "খুঁজে", "খুজে", "খুঁজো", "খুজো", "খুঁজুন", "খুজুন", "খুঁজ", "খুজ"
    )
    private val fillers = set(
        "করো", "কর", "করুন", "দাও", "দিন", "একটু", "অ্যাপ", "অ্যাপটি", "app",
        "please", "প্লিজ", "গিয়ে", "এখন", "আমার"
    )
    private val searchStop = searchVerbs + openVerbs + fillers + set(
        "গুগলে", "গুগল", "ক্রোমে", "ক্রোম", "google", "chrome",
        "সম্পর্কে", "সম্বন্ধে", "বিষয়ে", "ব্রাউজারে"
    )
    private val backWords = set("ফিরে", "পেছনে", "পিছনে", "আগের", "ব্যাক")
    private val typeWords = set("লেখো", "লিখো", "লিখুন", "টাইপ")
    private val timeWords = set("সময়", "কয়টা", "কয়টায়", "কটা", "টাইম", "time")
    private val dateWords = set("তারিখ", "date")

    private class Unsupp(
        val feature: String,
        val reason: String,
        val exact: Set<String>,
        val prefixes: List<String>
    )

    private val unsupported = listOf(
        Unsupp("ফোন কল", "কন্টাক্ট ও কল সুবিধা পঞ্চম ধাপে আসবে, এখনো যোগ হয়নি।",
            set("কল", "ডায়াল"), listOf("ফোন").map { TextNormalizer.nfc(it) }),
        Unsupp("রিমাইন্ডার", "রিমাইন্ডার সুবিধা পঞ্চম ধাপে আসবে, এখনো যোগ হয়নি।",
            emptySet(), listOf("মনে", "রিমাইন্ড", "অ্যালার্ম").map { TextNormalizer.nfc(it) }),
        Unsupp("ফ্ল্যাশলাইট", "ফ্ল্যাশলাইট এখনো যোগ হয়নি।",
            emptySet(), listOf("ফ্ল্যাশ", "ফ্লাশ", "টর্চ").map { TextNormalizer.nfc(it) }),
        Unsupp("ভলিউম", "ভলিউম নিয়ন্ত্রণ এখনো যোগ হয়নি।",
            set("আওয়াজ"), listOf("ভলিউম").map { TextNormalizer.nfc(it) }),
        Unsupp("মিডিয়া নিয়ন্ত্রণ", "মিডিয়া নিয়ন্ত্রণ এখনো যোগ হয়নি।",
            set("পজ", "থামাও", "গান", "মিউজিক"), emptyList())
    )

    private val urlRegex = Regex(
        "(?:https?://)?(?:[a-z0-9-]+\\.)+(?:com|net|org|bd|gov|edu|io|info|co|me|app|ai|xyz|tv|news|dev)(?![a-z0-9-])(?:/\\S*)?"
    )

    fun searchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    fun parse(raw: String): KdIntent {
        val normalized = TextNormalizer.normalize(raw)
        if (normalized.isEmpty()) return KdIntent.Unknown(raw)
        val norm = TextNormalizer.stripWakeWord(normalized)
        if (norm.isEmpty()) return KdIntent.Greeting

        val toks = TextNormalizer.tokens(norm)
        val tokSet = toks.toSet()

        if ("ঘুমাও" in tokSet || ("শোনা" in tokSet && "বন্ধ" in tokSet)) return KdIntent.Sleep

        if (toks.any { it in typeWords } && toks.any { it.startsWith("বার") || it.startsWith("অ্যাড্রেস") }) {
            return KdIntent.Unsupported(
                "ঠিকানা বারে লেখা",
                "ঠিকানা বারে লেখার জন্য অ্যাক্সেসিবিলিটি সার্ভিস লাগবে, যা তৃতীয় ধাপে আসবে।"
            )
        }

        if (toks.any { it in searchVerbs }) {
            val q = toks.filter { it !in searchStop }.joinToString(" ")
            return if (q.isBlank()) KdIntent.Clarify("কী সার্চ করব?") else KdIntent.Search(q)
        }

        if (toks.any { it in backWords }) {
            return KdIntent.Unsupported(
                "পেছনে যাওয়া",
                "অন্য অ্যাপে পেছনে যাওয়ার জন্য অ্যাক্সেসিবিলিটি সার্ভিস লাগবে, যা তৃতীয় ধাপে আসবে।"
            )
        }

        if (toks.any { it.startsWith("হোম") }) return KdIntent.GoHome

        for (u in unsupported) {
            if (toks.any { t -> t in u.exact || u.prefixes.any { p -> t.startsWith(p) } }) {
                return KdIntent.Unsupported(u.feature, u.reason)
            }
        }

        if (toks.any { it in timeWords }) return KdIntent.TellTime
        if (toks.any { it in dateWords }) return KdIntent.TellDate

        urlRegex.find(norm)?.let { m ->
            val u = m.value
            return KdIntent.OpenUrl(if (u.startsWith("http")) u else "https://$u")
        }

        if (toks.any { it in openVerbs }) {
            val name = toks.filter { it !in openVerbs && it !in fillers }.joinToString(" ")
            return if (name.isBlank()) KdIntent.Clarify("কোন অ্যাপ খুলব?") else KdIntent.OpenApp(name)
        }

        return KdIntent.Unknown(raw)
    }
}
