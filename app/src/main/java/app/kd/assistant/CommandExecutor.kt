package app.kd.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class ExecResult(val ok: Boolean, val spoken: String, val display: String)

/**
 * Executes validated intents. Android only tells us a request was sent, not that the effect happened,
 * so messages say "requested/tried" unless something was read back (typed text).
 */
class CommandExecutor(private val ctx: Context) {

    private class AppAlias(val names: Set<String>, val packages: List<String>, val spoken: String)
    private class Contact(val name: String, val number: String)
    private class PendingConfirm(val expires: Long, val run: () -> ExecResult)
    private class PendingChoice(val expires: Long, val items: List<Contact>)
    private class Launchable(val label: String, val pkg: String)

    private val handler = Handler(Looper.getMainLooper())
    private var confirm: PendingConfirm? = null
    private var choice: PendingChoice? = null

    private fun nset(vararg w: String): Set<String> = w.map { TextNormalizer.nfc(it) }.toSet()

    private val known = listOf(
        AppAlias(nset("ক্রোম", "গুগল ক্রোম", "chrome", "google chrome", "ক্রোম ব্রাউজার"),
            listOf("com.android.chrome"), "ক্রোম"),
        AppAlias(nset("ইউটিউব", "ইউটিউভ", "ইউ টিউব", "youtube"),
            listOf("com.google.android.youtube"), "ইউটিউব"),
        AppAlias(nset("ফেসবুক", "ফেইসবুক", "facebook"),
            listOf("com.facebook.katana"), "ফেসবুক"),
        AppAlias(nset("হোয়াটসঅ্যাপ", "হোয়াটস অ্যাপ", "whatsapp"),
            listOf("com.whatsapp"), "হোয়াটসঅ্যাপ"),
        AppAlias(nset("মেসেঞ্জার", "messenger"),
            listOf("com.facebook.orca"), "মেসেঞ্জার"),
        AppAlias(nset("জিমেইল", "gmail"),
            listOf("com.google.android.gm"), "জিমেইল"),
        AppAlias(nset("গুগল ম্যাপ", "গুগল ম্যাপস", "ম্যাপ", "ম্যাপস", "maps", "google maps"),
            listOf("com.google.android.apps.maps"), "ম্যাপস"),
        AppAlias(nset("প্লে স্টোর", "গুগল প্লে", "play store"),
            listOf("com.android.vending"), "প্লে স্টোর")
    )
    private val settingsNames = nset("সেটিংস", "সেটিং", "settings", "setting", "সেটিংস অ্যাপ")

    private val sensitiveWords = nset(
        "পাঠাও", "পাঠান", "send", "pay", "পেমেন্ট", "payment", "delete", "মুছে", "মুছুন", "ডিলিট",
        "কিনুন", "buy", "order", "অর্ডার", "confirm", "কনফার্ম", "submit", "সাবমিট",
        "transfer", "ট্রান্সফার", "post", "পোস্ট", "logout", "লগআউট"
    )
    private val uiAliases: Map<String, List<String>> = mapOf(
        "সার্চ" to listOf("search"), "পাঠাও" to listOf("send"), "ঠিক আছে" to listOf("ok"),
        "ক্যান্সেল" to listOf("cancel"), "সেভ" to listOf("save"), "শেয়ার" to listOf("share"),
        "লাইক" to listOf("like"), "কমেন্ট" to listOf("comment"), "সাবস্ক্রাইব" to listOf("subscribe"),
        "ডাউনলোড" to listOf("download"), "পরবর্তী" to listOf("next"), "ফিরে" to listOf("back")
    ).mapKeys { TextNormalizer.nfc(it.key) }

    fun hasPending(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (confirm?.expires ?: 0L) > now || (choice?.expires ?: 0L) > now
    }

    fun execute(intent: KdIntent): ExecResult {
        val now = SystemClock.elapsedRealtime()
        when (intent) {
            is KdIntent.Confirm -> {
                val c = confirm
                confirm = null
                choice = null
                if (c == null || c.expires < now) {
                    return ExecResult(false, "কিসের জন্য হ্যাঁ বা না বলছেন? আগে একটা কমান্ড দিন।", "নিশ্চিত করার মতো কিছু অপেক্ষায় নেই।")
                }
                return if (intent.yes) c.run() else ExecResult(true, "ঠিক আছে, বাতিল করলাম।", "বাতিল করা হয়েছে।")
            }
            is KdIntent.Choose -> {
                val ch = choice
                if (ch == null || ch.expires < now) {
                    return ExecResult(false, "কোনটি বেছে নিচ্ছেন বুঝতে পারিনি।", "বেছে নেওয়ার মতো কিছু অপেক্ষায় নেই।")
                }
                val item = ch.items.getOrNull(intent.n - 1)
                    ?: return ExecResult(false, "এই নম্বরে কিছু নেই। আবার বলুন।", "ভুল নম্বর।")
                choice = null
                confirm = null
                return placeCall(item)
            }
            else -> {
                confirm = null
                choice = null
            }
        }
        return dispatch(intent)
    }

    private fun dispatch(intent: KdIntent): ExecResult = when (intent) {
        is KdIntent.Greeting -> ExecResult(true, "জি, বলুন।", "জি, বলুন।")
        is KdIntent.OpenApp -> openApp(intent.appName)
        is KdIntent.Search -> viewUrl(
            IntentParser.searchUrl(intent.query),
            "গুগলে সার্চ করছি।",
            "\"${intent.query}\" সার্চের অনুরোধ ব্রাউজারে পাঠানো হয়েছে।"
        )
        is KdIntent.OpenUrl -> viewUrl(
            intent.url, "ওয়েবসাইট খুলছি।", "${intent.url} খোলার অনুরোধ পাঠানো হয়েছে।"
        )
        is KdIntent.TellTime -> {
            val s = "এখন সময় " + BanglaFormat.time(LocalTime.now()) + "।"
            ExecResult(true, s, s)
        }
        is KdIntent.TellDate -> {
            val s = "আজ " + BanglaFormat.date(LocalDate.now()) + "।"
            ExecResult(true, s, s)
        }
        is KdIntent.GoHome -> launch(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "হোম স্ক্রিন"
        )
        is KdIntent.Sleep -> ExecResult(true, "ঠিক আছে, শোনা বন্ধ করলাম।", "মাইক বন্ধ।")
        is KdIntent.Torch -> torch(intent.on)
        is KdIntent.Volume -> volume(intent.dir)
        is KdIntent.Media -> media(intent.action)
        is KdIntent.SetReminder -> reminder(intent.raw)
        is KdIntent.Call -> if (locked()) lockedResult() else callByName(intent.name)
        is KdIntent.Back -> guarded { svc ->
            if (svc.back()) ExecResult(true, "পেছনে গেলাম।", "পেছনে যাওয়ার নির্দেশ পাঠানো হয়েছে।")
            else ExecResult(false, "পেছনে যেতে পারলাম না।", "পেছনে যাওয়া ব্যর্থ।")
        }
        is KdIntent.Scroll -> guarded { svc ->
            if (svc.scroll(intent.down)) ExecResult(true, "স্ক্রল করলাম।", "স্ক্রলের নির্দেশ পাঠানো হয়েছে।")
            else ExecResult(false, "স্ক্রল করার মতো কিছু পেলাম না।", "স্ক্রল করা যায় এমন অংশ পাওয়া যায়নি।")
        }
        is KdIntent.Click -> guarded { _ -> clickWithConfirm(intent.target) }
        is KdIntent.TypeText -> guarded { svc ->
            if (svc.typeText(intent.text)) ExecResult(true, "লিখেছি।", "\"${intent.text}\" লেখা হয়েছে (ঘরে পড়ে যাচাই করা হয়েছে)।")
            else ExecResult(false, "লেখার ঘর পাইনি বা লেখাটা যাচাই করতে পারিনি।", "লেখা যাচাই হয়নি। আগে লেখার ঘরে চাপ দিন।")
        }
        is KdIntent.AddressBar -> if (locked()) lockedResult() else addressBar(intent.text)
        is KdIntent.Unsupported -> ExecResult(false, "দুঃখিত, ${intent.reason}", "অসমর্থিত (${intent.feature}): ${intent.reason}")
        is KdIntent.Clarify -> ExecResult(false, intent.question, intent.question)
        is KdIntent.Unknown -> ExecResult(
            false, "দুঃখিত, আপনার কথাটি বুঝতে পারিনি। আরেকবার বলবেন?", "বুঝতে পারিনি: \"${intent.heard}\""
        )
        is KdIntent.Confirm, is KdIntent.Choose -> ExecResult(false, "বুঝতে পারিনি।", "বুঝতে পারিনি।")
    }

    // ---- safety helpers --------------------------------------------------------

    private fun locked(): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    private fun lockedResult() = ExecResult(
        false, "ফোন আনলক করুন, তারপর আবার বলুন।", "ফোন লক করা, তাই নিরাপত্তার জন্য এই কাজ করা হলো না।"
    )

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun guarded(block: (KdAccessibilityService) -> ExecResult): ExecResult {
        if (locked()) return lockedResult()
        val svc = KdAccessibilityService.instance ?: return ExecResult(
            false,
            "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই। অ্যাপে গিয়ে চালু করুন।",
            "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ। KD অ্যাপের ♿ বাটন থেকে চালু করুন।"
        )
        return block(svc)
    }

    // ---- taps -----------------------------------------------------------------

    private fun clickWithConfirm(target: String): ExecResult {
        val sensitive = sensitiveWords.any { target.contains(it) }
        val run: () -> ExecResult = { doClick(target) }
        if (sensitive) {
            confirm = PendingConfirm(SystemClock.elapsedRealtime() + 15000, run)
            return ExecResult(
                false, "\"$target\" চাপব? এটা গুরুত্বপূর্ণ হতে পারে। হ্যাঁ বলুন।",
                "নিশ্চিত করুন: \"$target\" চাপব? (হ্যাঁ/না)"
            )
        }
        return run()
    }

    private fun doClick(target: String): ExecResult {
        val svc = KdAccessibilityService.instance
            ?: return ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই।", "অ্যাক্সেসিবিলিটি বন্ধ।")
        val tries = listOf(target) + (uiAliases[TextNormalizer.nfc(target)] ?: emptyList())
        for (t in tries) {
            if (svc.clickText(t)) {
                return ExecResult(
                    true, "চাপ দিলাম।",
                    "\"$target\" চাপার নির্দেশ পাঠানো হয়েছে। (ফলাফল আমি যাচাই করতে পারি না।)"
                )
            }
        }
        return ExecResult(false, "\"$target\" নামে কিছু স্ক্রিনে পেলাম না।", "\"$target\" স্ক্রিনে পাওয়া যায়নি।")
    }

    private fun addressBar(text: String): ExecResult {
        val svc = KdAccessibilityService.instance
        if (svc == null) {
            val url = IntentParser.asUrl(text) ?: IntentParser.searchUrl(text)
            val r = viewUrl(url, "ঠিকানা বারে না লিখে সরাসরি খুলছি।", "অ্যাক্সেসিবিলিটি বন্ধ, তাই ঠিকানা বারে না লিখে সরাসরি খোলা হলো: $url")
            return r
        }
        val first = svc.addressBar(text)
        if (first == "SUBMITTED" || first == "TYPED") return addressResult(first)
        val li = ctx.packageManager.getLaunchIntentForPackage("com.android.chrome")
            ?: return ExecResult(false, "ক্রোম ইনস্টল করা নেই।", "ক্রোম নেই।")
        launch(li, "ক্রোম")
        handler.postDelayed({
            val code = KdAccessibilityService.instance?.addressBar(text) ?: "NOT_FOUND"
            val r = addressResult(code)
            KdBus.log((if (r.ok) "✅ " else "ℹ️ ") + "KD: " + r.display)
        }, 2500)
        return ExecResult(true, "ক্রোম খুলে ঠিকানা বারে লিখছি।", "ক্রোম খুলেছি; আড়াই সেকেন্ড পরে ঠিকানা বারে লেখার চেষ্টা করব…")
    }

    private fun addressResult(code: String): ExecResult = when (code) {
        "SUBMITTED" -> ExecResult(true, "ঠিকানা বারে লিখে এন্টার দিয়েছি।", "ঠিকানা বারে লেখা ও এন্টারের নির্দেশ পাঠানো হয়েছে (পেজ লোড যাচাই করা হয়নি)।")
        "TYPED" -> ExecResult(true, "ঠিকানা বারে লিখেছি, কিন্তু এন্টার চাপতে পারিনি।", "লেখা হয়েছে, এন্টার চাপা যায়নি; কিবোর্ডের এন্টার চাপুন।")
        "NOT_CHROME" -> ExecResult(false, "ক্রোম সামনে নেই।", "ক্রোম সামনে খোলা নেই।")
        "FAILED" -> ExecResult(false, "ঠিকানা বারে লিখতে পারিনি।", "ঠিকানা বারে লেখা ব্যর্থ।")
        else -> ExecResult(false, "ঠিকানা বার খুঁজে পাইনি।", "ক্রোমের ঠিকানা বার পাওয়া যায়নি (ক্রোমের সংস্করণ ভেদে আলাদা হতে পারে)।")
    }

    // ---- calls ----------------------------------------------------------------

    private fun findContacts(q: String): List<Contact> {
        val out = ArrayList<Contact>()
        val proj = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)
        ctx.contentResolver.query(
            Phone.CONTENT_URI, proj, "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out.add(Contact(n, num))
            }
        }
        return out.distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
    }

    private fun callByName(name: String): ExecResult {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return ExecResult(false, "কন্টাক্ট দেখার অনুমতি প্রয়োজন।", "কন্টাক্টের অনুমতি নেই। ফোনের অ্যাপ সেটিংসে KD-কে কন্টাক্ট অনুমতি দিন।")
        }
        val all = findContacts(name)
        val ql = name.lowercase()
        val exact = all.filter { it.name.lowercase() == ql }
        val list = if (exact.isNotEmpty()) exact else all
        val now = SystemClock.elapsedRealtime()
        return when {
            list.isEmpty() -> ExecResult(false, "\"$name\" নামে কোনো কন্টাক্ট পেলাম না।", "\"$name\" নামে কন্টাক্ট নেই।")
            list.size == 1 -> {
                val c = list[0]
                confirm = PendingConfirm(now + 15000) { placeCall(c) }
                ExecResult(false, "${c.name}-কে কল করব? হ্যাঁ বলুন।", "নিশ্চিত করুন: ${c.name}-কে কল করব? (হ্যাঁ/না)")
            }
            list.size <= 3 -> {
                choice = PendingChoice(now + 15000, list)
                val dup = list.map { it.name }.toSet().size < list.size
                val labels = list.mapIndexed { i, c ->
                    val extra = if (dup) " (শেষ ${BanglaFormat.digits((c.number.filter { ch -> ch.isDigit() }.takeLast(2).toIntOrNull() ?: 0))})" else ""
                    "${BanglaFormat.digits(i + 1)} ${c.name}$extra"
                }.joinToString(", ")
                ExecResult(false, "কোনটি? $labels। নম্বর বলুন।", "একাধিক মিলেছে: $labels")
            }
            else -> ExecResult(false, "অনেকগুলো কন্টাক্ট মিলেছে। পুরো নাম বলুন।", "অনেক কন্টাক্ট মিলেছে। পুরো নাম দিয়ে চেষ্টা করুন।")
        }
    }

    private fun placeCall(c: Contact): ExecResult {
        val canCall = granted(Manifest.permission.CALL_PHONE)
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", c.number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            if (canCall) ExecResult(true, "${c.name}-কে কল করছি।", "${c.name}-কে কলের অনুরোধ পাঠানো হয়েছে।")
            else ExecResult(true, "ডায়ালার খুলছি, কল বাটন চাপুন।", "কলের অনুমতি নেই, তাই ডায়ালার খোলা হয়েছে; কল বাটন নিজে চাপুন।")
        } catch (e: ActivityNotFoundException) {
            ExecResult(false, "কল করার অ্যাপ পাওয়া যায়নি।", "ডায়ালার নেই।")
        } catch (e: SecurityException) {
            ExecResult(false, "এই কাজের জন্য অনুমতি প্রয়োজন।", "কলের অনুমতি নেই: ${e.message}")
        }
    }

    // ---- reminders --------------------------------------------------------------

    private fun reminder(raw: String): ExecResult {
        val now = LocalDateTime.now()
        return when (val r = ReminderParser.parse(raw, now)) {
            is ReminderParser.Result.NeedTime ->
                ExecResult(false, "কখন মনে করাব? সময় বলুন।", "সময় বোঝা যায়নি। যেমন: \"কাল সকাল আটটায়\"।")
            is ReminderParser.Result.Past ->
                ExecResult(false, "ওই সময় পার হয়ে গেছে। অন্য সময় বলুন।", "বলা সময় পার হয়ে গেছে।")
            is ReminderParser.Result.Ok -> {
                val millis = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                val item = ReminderStore.Item(id, millis, r.message)
                try {
                    ReminderStore.add(ctx, item)
                    ReminderScheduler.schedule(ctx, item)
                    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), r.at.toLocalDate())
                    val dayWord = when (days) {
                        0L -> "আজ"
                        1L -> "আগামীকাল"
                        else -> BanglaFormat.date(r.at.toLocalDate())
                    }
                    val s = "$dayWord ${BanglaFormat.time(r.at.toLocalTime())}"
                    ExecResult(true, "রিমাইন্ডার রাখলাম: $s।", "রিমাইন্ডার রাখা হয়েছে: $s — \"${r.message}\"")
                } catch (e: Exception) {
                    ExecResult(false, "রিমাইন্ডার রাখতে পারলাম না।", "রিমাইন্ডার ব্যর্থ: ${e.message}")
                }
            }
        }
    }

    // ---- device controls --------------------------------------------------------

    private fun torch(on: Boolean): ExecResult = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            ExecResult(false, "এই ফোনে ফ্ল্যাশ পেলাম না।", "ফ্ল্যাশলাইট হার্ডওয়্যার পাওয়া যায়নি।")
        } else {
            cm.setTorchMode(id, on)
            ExecResult(
                true, if (on) "ফ্ল্যাশলাইট চালু করলাম।" else "ফ্ল্যাশলাইট বন্ধ করলাম।",
                "ফ্ল্যাশলাইট ${if (on) "চালু" else "বন্ধ"}-এর নির্দেশ পাঠানো হয়েছে। (অন্য অ্যাপ ক্যামেরা নিলে বন্ধ হয়ে যেতে পারে।)"
            )
        }
    } catch (e: Exception) {
        ExecResult(false, "ফ্ল্যাশলাইট চালাতে পারলাম না।", "ফ্ল্যাশলাইট ব্যর্থ: ${e.message}")
    }

    private fun volume(dir: Int): ExecResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val d = when (dir) {
                1 -> AudioManager.ADJUST_RAISE
                -1 -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_MUTE
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
            val w = when (dir) { 1 -> "ভলিউম বাড়ালাম।"; -1 -> "ভলিউম কমালাম।"; else -> "মিউট করলাম।" }
            ExecResult(true, w, "$w (সিস্টেমকে নির্দেশ পাঠানো হয়েছে)")
        } catch (e: SecurityException) {
            ExecResult(false, "ভলিউম বদলাতে পারলাম না।", "ভলিউম বদলানো ব্যর্থ: ${e.message}")
        }
    }

    private fun media(action: String): ExecResult {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val w = when (action) { "play" -> "গান চালালাম।"; "pause" -> "থামালাম।"; "next" -> "পরের গান।"; else -> "আগের গান।" }
        return ExecResult(true, w, "$w (মিডিয়া বাটনের নির্দেশ পাঠানো হয়েছে; প্লেয়ার সাড়া দিয়েছে কিনা যাচাই করতে পারি না।)")
    }

    // ---- launching apps / URLs -----------------------------------------------------

    private fun launch(intent: Intent, spokenName: String): ExecResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        ExecResult(
            true, "$spokenName খুলছি।",
            "$spokenName খোলার অনুরোধ পাঠানো হয়েছে। (সত্যিই খুলেছে কিনা আমি যাচাই করতে পারি না।)"
        )
    } catch (e: ActivityNotFoundException) {
        ExecResult(false, "$spokenName খুলতে পারলাম না।", "$spokenName খোলার মতো অ্যাপ পাওয়া যায়নি।")
    } catch (e: SecurityException) {
        ExecResult(false, "এই কাজের জন্য অনুমতি প্রয়োজন।", "অনুমতি নেই: ${e.message}")
    }

    private fun viewUrl(url: String, spoken: String, display: String): ExecResult {
        val uri = Uri.parse(url)
        val tryChrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(tryChrome)
            ExecResult(true, spoken, "$display (ক্রোম)")
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ExecResult(true, spoken, "$display (ডিফল্ট ব্রাউজার)")
            } catch (e2: ActivityNotFoundException) {
                ExecResult(false, "কোনো ব্রাউজার পাওয়া যায়নি।", "ব্রাউজার পাওয়া যায়নি।")
            }
        }
    }

    private fun installedLaunchables(): List<Launchable> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(main, 0)
        }
        return infos.map { Launchable(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
    }

    private fun openApp(rawName: String): ExecResult {
        val name = TextNormalizer.nfc(rawName)
        if (name in settingsNames) return launch(Intent(Settings.ACTION_SETTINGS), "সেটিংস")

        val pm = ctx.packageManager
        known.firstOrNull { name in it.names }?.let { alias ->
            for (pkg in alias.packages) {
                val li = pm.getLaunchIntentForPackage(pkg)
                if (li != null) return launch(li, alias.spoken)
            }
            return ExecResult(false, "${alias.spoken} এই ফোনে ইনস্টল করা নেই।", "${alias.spoken} ইনস্টল করা নেই।")
        }

        val all = installedLaunchables()
        val exact = all.filter { it.label.lowercase() == name }
        val partial = if (name.length >= 3) all.filter { it.label.lowercase().contains(name) } else emptyList()
        val pick = when {
            exact.size == 1 -> exact
            exact.isEmpty() && partial.size == 1 -> partial
            else -> null
        }
        if (pick != null) {
            val li = pm.getLaunchIntentForPackage(pick[0].pkg)
            if (li != null) return launch(li, pick[0].label)
        }
        val candidates = (if (exact.isNotEmpty()) exact else partial).take(3)
        if (candidates.size > 1) {
            val labels = candidates.joinToString(", ") { it.label }
            return ExecResult(false, "কোনটি খুলব? $labels", "একাধিক অ্যাপ মিলেছে: $labels")
        }
        return ExecResult(false, "\"$rawName\" নামে কোনো অ্যাপ খুঁজে পেলাম না।", "\"$rawName\" নামে অ্যাপ পাওয়া যায়নি।")
    }
}
