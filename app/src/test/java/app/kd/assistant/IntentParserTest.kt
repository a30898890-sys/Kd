package app.kd.assistant

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private fun open(s: String) = (IntentParser.parse(s) as KdIntent.OpenApp).appName
    private fun search(s: String) = (IntentParser.parse(s) as KdIntent.Search).query

    @Test fun openChromeVariantsAgree() {
        assertEquals("ক্রোম", open("কেডি, ক্রোম খোলো।"))
        assertEquals("ক্রোম", open("ক্রোম চালু করো"))
        assertEquals("গুগল ক্রোম", open("গুগল ক্রোম ওপেন করো"))
    }

    @Test fun wakeWordIsStripped() {
        assertEquals("ক্রোম", open("হেই কেডি, ক্রোম চালু করো"))
        assertEquals(KdIntent.Greeting, IntentParser.parse("হেই কেডি"))
    }

    @Test fun youtube() {
        assertEquals("ইউটিউব", open("কেডি, ইউটিউব চালু করো।"))
    }

    @Test fun searchQueries() {
        assertEquals("ai agent", search("কেডি, গুগলে AI agent সম্পর্কে সার্চ করো।"))
        assertEquals("বাংলাদেশের খবর", search("কেডি, গুগলে গিয়ে বাংলাদেশের খবর খুঁজে দাও।"))
        assertEquals(
            "বাংলাদেশ ক্রিকেটের খবর",
            search("কেডি, ক্রোম খোলো, গুগলে গিয়ে বাংলাদেশ ক্রিকেটের খবর সার্চ করো।")
        )
    }

    @Test fun searchUrlIsEncoded() {
        assertTrue(IntentParser.searchUrl("ai agent").endsWith("q=ai+agent"))
    }

    @Test fun url() {
        assertEquals(
            KdIntent.OpenUrl("https://example.com"),
            IntentParser.parse("এই ওয়েবসাইটটি খোলো: example.com।")
        )
    }

    @Test fun homeAndSleep() {
        assertEquals(KdIntent.GoHome, IntentParser.parse("কেডি হোম স্ক্রিনে যাও"))
        assertEquals(KdIntent.Sleep, IntentParser.parse("কেডি, এখন ঘুমাও"))
    }

    @Test fun unsupportedFeaturesAreNotFaked() {
        assertTrue(IntentParser.parse("কেডি, আগের পেজে ফিরে যাও") is KdIntent.Unsupported)
        assertTrue(IntentParser.parse("কেডি, আমার মাকে ফোন দাও") is KdIntent.Unsupported)
        assertTrue(IntentParser.parse("কেডি, ফ্ল্যাশলাইট চালু করো") is KdIntent.Unsupported)
        assertTrue(IntentParser.parse("আগামীকাল সকাল আটটায় আমাকে মনে করিয়ে দিও") is KdIntent.Unsupported)
        assertTrue(IntentParser.parse("অ্যাড্রেস বারে google.com লেখো") is KdIntent.Unsupported)
    }

    @Test fun gibberishIsUnknown() {
        assertTrue(IntentParser.parse("blah blah") is KdIntent.Unknown)
    }

    @Test fun banglaTimeAndDate() {
        assertEquals("রাত ৯টা ৩০ মিনিট", BanglaFormat.time(LocalTime.of(21, 30)))
        assertEquals("সকাল ৮টা", BanglaFormat.time(LocalTime.of(8, 0)))
        assertEquals("শনিবার, ২৪ অক্টোবর ২০২৬", BanglaFormat.date(LocalDate.of(2026, 10, 24)))
    }
}
