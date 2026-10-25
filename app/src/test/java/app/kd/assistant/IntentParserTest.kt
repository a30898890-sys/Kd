package app.kd.assistant

import java.time.LocalDate
import java.time.LocalDateTime
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

    @Test fun offWordsStopListening() {
        assertEquals(KdIntent.Sleep, IntentParser.parse("কেডি অফ"))
        assertEquals(KdIntent.Sleep, IntentParser.parse("অফ হও"))
        assertEquals(KdIntent.Sleep, IntentParser.parse("off"))
    }

    @Test fun torchVolumeMedia() {
        assertEquals(KdIntent.Torch(true), IntentParser.parse("কেডি, ফ্ল্যাশলাইট চালু করো"))
        assertEquals(KdIntent.Torch(false), IntentParser.parse("কেডি, ফ্ল্যাশলাইট অফ করো"))
        assertEquals(KdIntent.Volume(1), IntentParser.parse("ভলিউম বাড়াও"))
        assertEquals(KdIntent.Volume(-1), IntentParser.parse("আওয়াজ কমাও"))
        assertEquals(KdIntent.Media("pause"), IntentParser.parse("গান থামাও"))
        assertEquals(KdIntent.Media("prev"), IntentParser.parse("আগের গান চালাও"))
    }

    @Test fun accessibilityCommands() {
        assertEquals(KdIntent.Back, IntentParser.parse("কেডি, আগের পেজে ফিরে যাও"))
        assertEquals(KdIntent.Scroll(true), IntentParser.parse("নিচে স্ক্রল করো"))
        assertEquals(KdIntent.Click("সার্চ"), IntentParser.parse("সার্চ চাপো"))
        assertEquals(KdIntent.AddressBar("google.com"), IntentParser.parse("অ্যাড্রেস বারে google.com লেখো"))
    }

    @Test fun callAndReminderIntents() {
        assertEquals(KdIntent.Call("মা"), IntentParser.parse("কেডি, আমার মাকে ফোন দাও"))
        assertTrue(IntentParser.parse("আগামীকাল সকাল আটটায় আমাকে মনে করিয়ে দিও") is KdIntent.SetReminder)
    }

    @Test fun answers() {
        assertEquals(KdIntent.Confirm(true), IntentParser.parse("হ্যাঁ"))
        assertEquals(KdIntent.Confirm(false), IntentParser.parse("না"))
        assertEquals(KdIntent.Choose(2), IntentParser.parse("দুই"))
        assertEquals(KdIntent.Choose(1), IntentParser.parse("১"))
    }

    @Test fun messagesAreNotFaked() {
        assertTrue(IntentParser.parse("মেসেজ পাঠাও") is KdIntent.Unsupported)
    }

    @Test fun gibberishIsUnknown() {
        assertTrue(IntentParser.parse("blah blah") is KdIntent.Unknown)
    }

    @Test fun banglaTimeAndDate() {
        assertEquals("রাত ৯টা ৩০ মিনিট", BanglaFormat.time(LocalTime.of(21, 30)))
        assertEquals("সকাল ৮টা", BanglaFormat.time(LocalTime.of(8, 0)))
        assertEquals("শনিবার, ২৪ অক্টোবর ২০২৬", BanglaFormat.date(LocalDate.of(2026, 10, 24)))
    }

    private val now = LocalDateTime.of(2026, 10, 25, 10, 0)

    @Test fun reminderTomorrowMorning() {
        val r = ReminderParser.parse("আগামীকাল সকাল আটটায় আমাকে মনে করিয়ে দিও", now)
        assertEquals(
            ReminderParser.Result.Ok(LocalDateTime.of(2026, 10, 26, 8, 0), "রিমাইন্ডার"), r
        )
    }

    @Test fun reminderTonightWithMessage() {
        val r = ReminderParser.parse("কেডি, আজ রাত ৯টায় ওষুধ খেতে মনে করিয়ে দিও", now)
        assertEquals(
            ReminderParser.Result.Ok(LocalDateTime.of(2026, 10, 25, 21, 0), "ওষুধ খেতে"), r
        )
    }

    @Test fun reminderRelative() {
        val r = ReminderParser.parse("১০ মিনিট পরে মনে করিয়ে দিও", now)
        assertEquals(
            ReminderParser.Result.Ok(LocalDateTime.of(2026, 10, 25, 10, 10), "রিমাইন্ডার"), r
        )
    }

    @Test fun reminderNeedsTimeOrIsPast() {
        assertEquals(ReminderParser.Result.NeedTime, ReminderParser.parse("মনে করিয়ে দিও", now))
        assertEquals(ReminderParser.Result.Past, ReminderParser.parse("আজ সকাল ৮টায় মনে করিয়ে দিও", now))
    }

    @Test fun englishCommands() {
        assertEquals(KdIntent.OpenApp("chrome"), IntentParser.parse("open chrome"))
        assertEquals(KdIntent.OpenApp("chrome"), IntentParser.parse("hey kd open chrome"))
        assertEquals(KdIntent.Search("bangladesh news"), IntentParser.parse("search for bangladesh news"))
        assertEquals(KdIntent.Torch(true), IntentParser.parse("flashlight on"))
        assertEquals(KdIntent.Torch(false), IntentParser.parse("turn off the flashlight"))
        assertEquals(KdIntent.Volume(1), IntentParser.parse("volume up"))
        assertEquals(KdIntent.Back, IntentParser.parse("go back"))
        assertEquals(KdIntent.Scroll(true), IntentParser.parse("scroll down"))
        assertEquals(KdIntent.TellTime, IntentParser.parse("what time is it"))
        assertEquals(KdIntent.Call("mom"), IntentParser.parse("call mom"))
        assertEquals(KdIntent.Click("search"), IntentParser.parse("tap search"))
        assertEquals(KdIntent.Sleep, IntentParser.parse("stop listening"))
        assertEquals(KdIntent.Media("play"), IntentParser.parse("play music"))
        assertEquals(KdIntent.Media("next"), IntentParser.parse("next song"))
        assertEquals(KdIntent.Confirm(true), IntentParser.parse("yes"))
        assertEquals(KdIntent.AddressBar("google.com"), IntentParser.parse("type google.com in address bar"))
    }

    @Test fun englishReminder() {
        val r = ReminderParser.parse("remind me tomorrow at 8 am to take medicine", now)
        assertEquals(
            ReminderParser.Result.Ok(LocalDateTime.of(2026, 10, 26, 8, 0), "take medicine"), r
        )
        val r2 = ReminderParser.parse("remind me in 10 minutes", now)
        assertEquals(
            ReminderParser.Result.Ok(LocalDateTime.of(2026, 10, 25, 10, 10), "রিমাইন্ডার"), r2
        )
    }
}
