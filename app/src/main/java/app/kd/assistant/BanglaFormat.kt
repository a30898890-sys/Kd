package app.kd.assistant

import java.time.LocalDate
import java.time.LocalTime

/** Bangla time/date phrases. Gregorian calendar only (no Bangla-calendar conversion). */
object BanglaFormat {
    private const val DIGITS = "০১২৩৪৫৬৭৮৯"
    private val months = listOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
    )
    private val weekdays = listOf(
        "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার"
    )

    fun digits(n: Int): String = n.toString().map { DIGITS[it - '0'] }.joinToString("")

    fun time(t: LocalTime): String {
        val h = t.hour
        val period = when (h) {
            in 4..5 -> "ভোর"
            in 6..11 -> "সকাল"
            in 12..14 -> "দুপুর"
            in 15..17 -> "বিকাল"
            in 18..19 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        val base = "$period ${digits(h12)}টা"
        return if (t.minute > 0) "$base ${digits(t.minute)} মিনিট" else base
    }

    fun date(d: LocalDate): String =
        "${weekdays[d.dayOfWeek.value - 1]}, ${digits(d.dayOfMonth)} ${months[d.monthValue - 1]} ${digits(d.year)}"
}
