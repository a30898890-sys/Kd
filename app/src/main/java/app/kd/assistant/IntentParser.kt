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
    object Back : KdIntent()
    data class Torch(val on: Boolean) : KdIntent()
    data class Volume(val dir: Int) : KdIntent()
    data class Media(val action: String) : KdIntent()
    data class Scroll(val down: Boolean) : KdIntent()
    data class Click(val target: String) : KdIntent()
    data class TypeText(val text: String) : KdIntent()
    data class AddressBar(val text: String) : KdIntent()
    data class Call(val name: String) : KdIntent()
    data class SetReminder(val raw: String) : KdIntent()
    data class Choose(val n: Int) : KdIntent()
    data class Confirm(val yes: Boolean) : KdIntent()
    data class Unsupported(val feature: String, val reason: String) : KdIntent()
    data class Clarify(val question: String) : KdIntent()
    data class Unknown(val heard: String) : KdIntent()
}

/** Rule-based Bangla + English intent extraction. Pure Kotlin, unit-testable. */
object IntentParser {
    private fun nfc(s: String) = TextNormalizer.nfc(s)
    private fun set(vararg w: String): Set<String> = w.map { nfc(it) }.toSet()

    private val openVerbs = set(
        "খোলো", "খুলো", "খোল", "খুলুন", "চালু", "ওপেন", "open",
        "চালাও", "চালান", "লঞ্চ", "launch", "start", "run"
    )
    private val searchVerbs = set(
        "সার্চ", "search", "find", "look",
        "খুঁজে", "খুজে", "খুঁজো", "খুজো", "খুঁজুন", "খুজুন", "খুঁজ", "খুজ"
    )
    private val fillers = set(
        "করো", "কর", "করুন", "দাও", "দিন", "একটু", "অ্যাপ", "অ্যাপটি", "app",
        "please", "প্লিজ", "গিয়ে", "এখন", "আমার",
        "the", "my", "me", "to", "for", "a", "an"
    )
    private val typeFillers = set(
        "করো", "কর", "করুন", "দাও", "দিন", "একটু", "please", "প্লিজ", "গিয়ে", "এখন"
    )
    private val addrWordsEn = set("address", "bar", "url", "in", "into", "on", "the", "chrome")
    private val searchStop = searchVerbs + openVerbs + fillers + set(
        "গুগলে", "গুগল", "ক্রোমে", "ক্রোম", "google", "chrome", "on", "about",
        "সম্পর্কে", "সম্বন্ধে", "বিষয়ে", "ব্রাউজারে"
    )
    private val stopWords = set("অফ", "off", "স্টপ", "stop", "থামো")
    private val stopOnly = set(
        "অফ", "off", "স্টপ", "stop", "থামো", "ঘুমাও", "হও", "করো", "কর", "হয়ে", "যাও", "এখন",
        "turn", "listening", "go", "to", "sleep", "please", "now", "kd"
    )
    private val yesWords = set(
        "হ্যাঁ", "হ্যা", "হাঁ", "জি", "ঠিক", "আছে", "ওকে",
        "ok", "okay", "yes", "yeah", "yep", "sure"
    )
    private val noWords = set("না", "নাহ", "থাক", "বাদ", "বাতিল", "no", "nope", "cancel")
    private val ordinals: Map<String, Int> = mapOf(
        "1" to 1, "এক" to 1, "একটা" to 1, "প্রথম" to 1, "প্রথমটা" to 1, "one" to 1, "first" to 1,
        "2" to 2, "দুই" to 2, "দুইটা" to 2, "দুটো" to 2, "দ্বিতীয়" to 2, "দ্বিতীয়টা" to 2, "two" to 2, "second" to 2,
        "3" to 3, "তিন" to 3, "তিনটা" to 3, "তৃতীয়" to 3, "তৃতীয়টা" to 3, "three" to 3, "third" to 3
    ).mapKeys { nfc(it.key) }

    private val mediaWords = set(
        "গান", "মিউজিক", "ভিডিও", "মিডিয়া", "song", "songs", "music", "video", "media", "track"
    )
    private val pauseWords = set("থামাও", "থামান", "পজ", "পোজ", "pause", "stop")
    private val playWords = set("চালাও", "চালু", "প্লে", "play", "চালান", "শুরু", "resume", "start")
    private val typeWords = set("লেখো", "লিখো", "লিখুন", "টাইপ", "type", "write")
    private val clickWords = set("চাপো", "চাপ", "চাপুন", "ক্লিক", "ট্যাপ", "টিপো", "টিপুন", "click", "tap", "press")
    private val clickStop = clickWords + fillers + set("বাটনে", "বাটন", "button", "on")
    private val scrollDown = set("নিচে", "নিচের", "ডাউন", "down")
    private val scrollUp = set("উপরে", "উপরের", "আপ", "up")
    private val offWords = set("বন্ধ", "অফ", "off", "নেভাও", "নিভাও")
    private val volUp = set("বাড়াও", "বাড়ান", "বেশি", "আপ", "up", "increase", "raise", "louder", "higher")
    private val volDown = set("কমাও", "কমান", "কম", "ডাউন", "down", "decrease", "lower", "reduce", "quieter")
    private val volMute = set("মিউট", "mute", "নীরব", "বন্ধ", "silent")
    private val callExact = set("কল", "ডায়াল", "call", "dial", "phone")
    private val backWords = set("ফিরে", "পেছনে", "পিছনে", "আগের", "ব্যাক", "back", "previous")
    private val timeWords = set("সময়", "কয়টা", "কয়টায়", "কটা", "টাইম", "time")
    private val dateWords = set("তারিখ", "date")
    private val reminderPrefixes = listOf("মনে", "রিমাইন্ড", "অ্যালার্ম", "remind", "alarm").map { nfc(it) }
    private val messagePrefixes = listOf("মেসেজ", "ম্যাসেজ", "এসএমএস", "sms", "message").map { nfc(it) }
    private val kePostfix = nfc("কে")

    private val urlRegex = Regex(
        "(?:https?://)?(?:[a-z0-9-]+\\.)+(?:com|net|org|bd|gov|edu|io|info|co|me|app|ai|xyz|tv|news|dev)(?![a-z0-9-])(?:/\\S*)?"
    )

    fun searchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    /** Returns an https URL if the text contains something that looks like a domain. */
    fun asUrl(text: String): String? {
        val m = urlRegex.find(TextNormalizer.normalize(text)) ?: return null
        val u = m.value
        return if (u.startsWith("http")) u else "https://$u"
    }

    private fun isAddrWord(t: String) =
        t.startsWith("বার") || t.startsWith(nfc("অ্যাড্রেস")) || t.startsWith(nfc("ঠিকানা")) ||
            t == "address" || t == "bar" || t == "url"

    fun parse(raw: String): KdIntent {
        val normalized = TextNormalizer.normalize(raw)
        if (normalized.isEmpty()) return KdIntent.Unknown(raw)
        val norm = TextNormalizer.stripWakeWord(normalized)
        if (norm.isEmpty()) return KdIntent.Greeting

        val toks = TextNormalizer.tokens(norm)
        val tokSet = toks.toSet()
        val short = toks.size <= 2

        // Stop listening
        if (short && toks.any { it in stopWords } && toks.all { it in stopOnly }) return KdIntent.Sleep
        if (nfc("ঘুমাও") in tokSet || "sleep" in tokSet) return KdIntent.Sleep
        if (nfc("শোনা") in tokSet && nfc("বন্ধ") in tokSet) return KdIntent.Sleep
        if ("stop" in tokSet && "listening" in tokSet) return KdIntent.Sleep

        // Answers to questions KD asked
        if (short && toks.all { it in yesWords }) return KdIntent.Confirm(true)
        if (short && toks.all { it in noWords }) return KdIntent.Confirm(false)
        if (short) {
            val n = toks.firstNotNullOfOrNull { ordinals[it] }
            if (n != null && toks.all { it in ordinals }) return KdIntent.Choose(n)
        }

        // Media (before "আগের"/"previous" is treated as "back")
        val mediaCtx = toks.any { it in mediaWords }
        if (mediaCtx && (nfc("পরের") in tokSet || "next" in tokSet)) return KdIntent.Media("next")
        if (mediaCtx && (nfc("আগের") in tokSet || "previous" in tokSet || "prev" in tokSet)) {
            return KdIntent.Media("prev")
        }
        if (toks.any { it in pauseWords } && (mediaCtx || short)) return KdIntent.Media("pause")
        if (mediaCtx && toks.any { it in playWords }) return KdIntent.Media("play")

        // Typing
        if (toks.any { it in typeWords }) {
            val isAddr = toks.any { isAddrWord(it) }
            val text = toks.filter { t ->
                t !in typeWords && t !in typeFillers &&
                    !(isAddr && (isAddrWord(t) || t == nfc("ক্রোমের") || t == nfc("ক্রোমে") || t in addrWordsEn))
            }.joinToString(" ")
            if (text.isBlank()) return KdIntent.Clarify("কী লিখব?")
            return if (isAddr) KdIntent.AddressBar(text) else KdIntent.TypeText(text)
        }

        // Tap
        if (toks.any { it in clickWords }) {
            val target = toks.filter { it !in clickStop }.joinToString(" ")
            return if (target.isBlank()) KdIntent.Clarify("কোথায় চাপব?") else KdIntent.Click(target)
        }

        // Search ("google bangladesh news" also counts)
        if (toks.any { it in searchVerbs } || (toks.size > 1 && toks[0] == "google")) {
            val q = toks.filter { it !in searchStop }.joinToString(" ")
            return if (q.isBlank()) KdIntent.Clarify("কী সার্চ করব?") else KdIntent.Search(q)
        }

        // Scroll
        if (nfc("স্ক্রল") in tokSet || "scroll" in tokSet) {
            return KdIntent.Scroll(!(toks.any { it in scrollUp } && toks.none { it in scrollDown }))
        }

        if (toks.any { it in backWords }) return KdIntent.Back
        if (toks.any { it.startsWith(nfc("হোম")) || it == "home" }) return KdIntent.GoHome

        // Flashlight
        if (toks.any {
                it.startsWith("ফ্ল্যাশ") || it.startsWith("ফ্লাশ") || it.startsWith("টর্চ") ||
                    it.startsWith("flash") || it.startsWith("torch")
            }
        ) {
            return KdIntent.Torch(toks.none { it in offWords })
        }

        // Volume
        if (toks.any {
                it.startsWith(nfc("ভলিউম")) || it.startsWith(nfc("আওয়াজ")) || it.startsWith(nfc("সাউন্ড")) ||
                    it.startsWith("volume") || it == "louder" || it == "quieter"
            }
        ) {
            return when {
                toks.any { it in volMute } -> KdIntent.Volume(0)
                toks.any { it in volUp } -> KdIntent.Volume(1)
                toks.any { it in volDown } -> KdIntent.Volume(-1)
                else -> KdIntent.Clarify("ভলিউম বাড়াব না কমাব?")
            }
        }

        // Phone call
        if (toks.any { it.startsWith(nfc("ফোন")) || it in callExact }) {
            val name = toks.filter { !it.startsWith(nfc("ফোন")) && it !in callExact && it !in fillers }
                .map { if (it.length > 2 && it.endsWith(kePostfix)) it.removeSuffix(kePostfix) else it }
                .joinToString(" ")
            return if (name.isBlank()) KdIntent.Clarify("কাকে ফোন করব?") else KdIntent.Call(name)
        }

        // Reminder
        if (toks.any { t -> reminderPrefixes.any { t.startsWith(it) } }) return KdIntent.SetReminder(raw)

        if (toks.any { t -> messagePrefixes.any { t.startsWith(it) } }) {
            return KdIntent.Unsupported(
                "মেসেজ পাঠানো",
                "মেসেজ পাঠানো এখনো যোগ হয়নি, কারণ এটা গুরুত্বপূর্ণ কাজ এবং আলাদা সুরক্ষা লাগবে।"
            )
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
