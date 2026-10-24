package app.kd.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.time.LocalDate
import java.time.LocalTime

data class ExecResult(val ok: Boolean, val spoken: String, val display: String)

/**
 * Executes validated intents. Launching only proves the request was sent to Android;
 * it cannot prove the target app actually came to the foreground, and messages say so.
 */
class CommandExecutor(private val activity: Activity) {

    private class AppAlias(val names: Set<String>, val packages: List<String>, val spoken: String)

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

    fun execute(intent: KdIntent): ExecResult = when (intent) {
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
        is KdIntent.GoHome -> {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch(home, "হোম স্ক্রিন")
        }
        is KdIntent.Sleep -> ExecResult(true, "ঠিক আছে, শোনা বন্ধ করলাম।", "মাইক বন্ধ।")
        is KdIntent.Unsupported -> ExecResult(
            false,
            "দুঃখিত, ${intent.reason}",
            "অসমর্থিত (${intent.feature}): ${intent.reason}"
        )
        is KdIntent.Clarify -> ExecResult(false, intent.question, intent.question)
        is KdIntent.Unknown -> ExecResult(
            false,
            "দুঃখিত, আপনার কথাটি বুঝতে পারিনি। আরেকবার বলবেন?",
            "বুঝতে পারিনি: \"${intent.heard}\""
        )
    }

    private fun launch(intent: Intent, spokenName: String): ExecResult = try {
        activity.startActivity(intent)
        ExecResult(
            true,
            "$spokenName খুলছি।",
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
        return try {
            activity.startActivity(tryChrome)
            ExecResult(true, spoken, "$display (ক্রোম)")
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                ExecResult(true, spoken, "$display (ডিফল্ট ব্রাউজার)")
            } catch (e2: ActivityNotFoundException) {
                ExecResult(false, "কোনো ব্রাউজার পাওয়া যায়নি।", "ব্রাউজার পাওয়া যায়নি।")
            }
        }
    }

    private class Launchable(val label: String, val pkg: String)

    private fun installedLaunchables(): List<Launchable> {
        val pm = activity.packageManager
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

        val pm = activity.packageManager
        known.firstOrNull { name in it.names }?.let { alias ->
            for (pkg in alias.packages) {
                val li = pm.getLaunchIntentForPackage(pkg)
                if (li != null) return launch(li, alias.spoken)
            }
            return ExecResult(
                false,
                "${alias.spoken} এই ফোনে ইনস্টল করা নেই।",
                "${alias.spoken} ইনস্টল করা নেই।"
            )
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
        return ExecResult(
            false,
            "\"$rawName\" নামে কোনো অ্যাপ খুঁজে পেলাম না।",
            "\"$rawName\" নামে অ্যাপ পাওয়া যায়নি।"
        )
    }
}
