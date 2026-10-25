package app.kd.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent

/** Small persistent settings + the shared speech-recognizer request. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kd_prefs", Context.MODE_PRIVATE)

    /** "bn", "en" or "auto" */
    fun lang(ctx: Context): String = sp(ctx).getString("lang", "bn") ?: "bn"
    fun setLang(ctx: Context, v: String) {
        sp(ctx).edit().putString("lang", v).apply()
    }

    fun rate(ctx: Context): Float = sp(ctx).getFloat("rate", 1.35f)
    fun setRate(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat("rate", v).apply()
    }

    fun wake(ctx: Context): Boolean = sp(ctx).getBoolean("wake", true)
    fun setWake(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("wake", v).apply()
    }

    fun speechIntent(ctx: Context, partial: Boolean): Intent {
        val lang = lang(ctx)
        val primary = if (lang == "en") "en-US" else "bn-BD"
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (lang == "auto" && Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("en-US"))
            }
        }
    }
}
