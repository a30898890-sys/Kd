package app.kd.assistant

import java.time.LocalDateTime
import java.time.ZoneOffset

/** Pure Kotlin Bangla reminder-time parser. */
object ReminderParser {
    sealed class Result {
        data class Ok(val at: LocalDateTime, val message: String) : Result()
        object NeedTime : Result()
        object Past : Result()
    }

    private fun nfc(s: String) = TextNormalizer.nfc(s)
    private fun set(vararg w: String): Set<String> = w.map { nfc(it) }.toSet()

    private val numberWords: Map<String, Int> = mapOf(
        "এক" to 1, "দুই" to 2, "দুটো" to 2, "তিন" to 3, "চার" to 4, "পাঁচ" to 5, "পাচ" to 5,
        "ছয়" to 6, "সাত" to 7, "আট" to 8, "নয়" to 9, "দশ" to 10, "এগারো" to 11, "বারো" to 12
    ).mapKeys { nfc(it.key) }

    private val suffixes = listOf("টায়", "টার", "টা").map { nfc(it) }
    private val bareSuffixes = set("টায়", "টা", "টার")
    private val afterWords = set("পরে", "পর")
    private val minuteUnits = set("মিনিট", "মিনিটে")
    private val hourUnits = set("ঘণ্টা", "ঘন্টা", "ঘণ্টায়")
    private val halfWords = set("আধ", "আধা")
    private val todayWords = set("আজ", "আজকে", "আজকের")
    private val tomorrowWords = set("কাল", "কালকে", "আগামীকাল", "আগামিকাল")
    private val dayAfterWords = set("পরশু", "পরশুদিন")
    private val morningWords = set("ভোর", "ভোরে", "সকাল", "সকালে")
    private val noonWords = set("দুপুর", "দুপুরে")
    private val afternoonWords = set("বিকাল", "বিকালে", "বিকেল", "বিকেলে")
    private val eveningWords = set("সন্ধ্যা", "সন্ধ্যায়", "সন্ধ্যে")
    private val nightWords = set("রাত", "রাতে")
    private val sade = nfc("সাড়ে")
    private val soya = nfc("সোয়া")
    private val poune = nfc("পৌনে")

    private val stopExact = set(
        "দাও", "দিন", "করো", "কর", "করুন", "আমাকে", "আমায়", "আমারে", "আমার",
        "একটা", "একটু", "সেট", "প্লিজ", "please", "এ", "পরে", "পর"
    )
    private val stopPrefix = listOf("মনে", "করিয়ে", "রিমাইন্ড", "অ্যালার্ম", "দিও", "দিয়ো").map { nfc(it) }

    private fun stripSuffix(t: String): String {
        for (s in suffixes) if (t.length > s.length && t.endsWith(s)) return t.removeSuffix(s)
        return t
    }

    private fun hasSuffix(t: String) = suffixes.any { t.length > it.length && t.endsWith(it) }

    private fun numberOf(t: String): Int? {
        val b = stripSuffix(t)
        return b.toIntOrNull() ?: numberWords[b]
    }

    private fun message(toks: List<String>, used: BooleanArray): String {
        val m = toks.indices.filter { !used[it] }.map { toks[it] }
            .filter { t -> t !in stopExact && stopPrefix.none { t.startsWith(it) } }
            .joinToString(" ")
        return if (m.isBlank()) "রিমাইন্ডার" else m
    }

    fun parse(raw: String, now: LocalDateTime): Result {
        val norm = TextNormalizer.stripWakeWord(TextNormalizer.normalize(raw))
        val toks = TextNormalizer.tokens(norm)
        val used = BooleanArray(toks.size)
        val base = now.withSecond(0).withNano(0)

        // Relative: "১০ মিনিট পরে", "আধ ঘণ্টা পরে", "২ ঘণ্টা পরে"
        val afterIdx = toks.indexOfFirst { it in afterWords }
        if (afterIdx >= 0) {
            for (i in 1 until toks.size) {
                val unit = toks[i]
                val isMin = unit in minuteUnits
                val isHour = unit in hourUnits
                if (!isMin && !isHour) continue
                val prev = toks[i - 1]
                val n = if (prev in halfWords) null else numberOf(prev)
                val minutes: Long? = when {
                    prev in halfWords && isHour -> 30L
                    n != null && isMin -> n.toLong()
                    n != null && isHour -> n * 60L
                    else -> null
                }
                if (minutes != null) {
                    used[i] = true
                    used[i - 1] = true
                    used[afterIdx] = true
                    return Result.Ok(base.plusMinutes(minutes), message(toks, used))
                }
            }
        }

        var dayOffset: Int? = null
        var period: String? = null
        for (i in toks.indices) {
            val t = toks[i]
            when {
                t in todayWords -> { dayOffset = 0; used[i] = true }
                t in tomorrowWords -> { dayOffset = 1; used[i] = true }
                t in dayAfterWords -> { dayOffset = 2; used[i] = true }
                t in morningWords -> { period = "morning"; used[i] = true }
                t in noonWords -> { period = "noon"; used[i] = true }
                t in afternoonWords -> { period = "afternoon"; used[i] = true }
                t in eveningWords -> { period = "evening"; used[i] = true }
                t in nightWords -> { period = "night"; used[i] = true }
            }
        }

        var hour: Int? = null
        var minute = 0
        var i = 0
        while (i < toks.size && hour == null) {
            val t = toks[i]
            val next = if (i + 1 < toks.size) toks[i + 1] else null
            val nn = if (i + 2 < toks.size) toks[i + 2] else null
            if ((t == sade || t == soya || t == poune) && next != null && numberOf(next) != null) {
                val n = numberOf(next)!!
                when (t) {
                    sade -> { hour = n; minute = 30 }
                    soya -> { hour = n; minute = 15 }
                    else -> { hour = n - 1; minute = 45 }
                }
                used[i] = true
                used[i + 1] = true
            } else if (t.toIntOrNull() != null && next != null && next.toIntOrNull() != null &&
                t.toInt() in 0..24 && next.toInt() in 0..59
            ) {
                hour = t.toInt()
                minute = next.toInt()
                used[i] = true
                used[i + 1] = true
                if (nn != null && nn in bareSuffixes) used[i + 2] = true
            } else if (numberOf(t) != null && (hasSuffix(t) || (next != null && next in bareSuffixes))) {
                hour = numberOf(t)
                used[i] = true
                var after = i + 1
                if (next != null && next in bareSuffixes) {
                    used[i + 1] = true
                    after = i + 2
                }
                if (after < toks.size && !hasSuffix(toks[after])) {
                    val m = toks[after].toIntOrNull()
                    if (m != null && m in 0..59) {
                        minute = m
                        used[after] = true
                    }
                }
            }
            i++
        }
        val h = hour ?: return Result.NeedTime
        if (h < 0 || h > 24) return Result.NeedTime

        val candidates: List<Int> = when {
            h == 0 -> listOf(0)
            h > 12 -> listOf(h % 24)
            period == "morning" -> listOf(if (h == 12) 0 else h)
            period == "noon" -> listOf(if (h == 12) 12 else if (h < 6) h + 12 else h)
            period == "afternoon" -> listOf(if (h == 12) 12 else h + 12)
            period == "evening" -> listOf(if (h == 12) 12 else h + 12)
            period == "night" -> listOf(if (h == 12) 0 else if (h < 6) h else h + 12)
            h == 12 -> listOf(0, 12)
            else -> listOf(h, h + 12)
        }

        val msg = message(toks, used)
        val offset = dayOffset
        if (offset != null) {
            val d = base.toLocalDate().plusDays(offset.toLong())
            val hh = if (candidates.size == 1) candidates[0] else when (h) {
                in 5..11 -> h
                12 -> 12
                else -> h + 12
            }
            val at = d.atTime(hh, minute)
            return if (at.isAfter(base)) Result.Ok(at, msg) else Result.Past
        }
        val today = base.toLocalDate()
        val future = candidates.map { today.atTime(it, minute) }.filter { it.isAfter(base) }
            .minByOrNull { it.toEpochSecond(ZoneOffset.UTC) }
        if (future != null) return Result.Ok(future, msg)
        val tomorrowAt = candidates.map { today.plusDays(1).atTime(it, minute) }
            .minByOrNull { it.toEpochSecond(ZoneOffset.UTC) }!!
        return Result.Ok(tomorrowAt, msg)
    }
}
