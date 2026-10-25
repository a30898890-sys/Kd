package app.kd.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ---- palette
    private val cBg = Color.parseColor("#0B1020")
    private val cCard = Color.parseColor("#151B2E")
    private val cStroke = Color.parseColor("#232B45")
    private val cText = Color.parseColor("#EAF0FF")
    private val cMuted = Color.parseColor("#8B97B8")
    private val cAccent = Color.parseColor("#7C5CFF")
    private val cTeal = Color.parseColor("#19D3A2")
    private val cRed = Color.parseColor("#FF5470")
    private val cOrange = Color.parseColor("#FF8A5B")
    private val cAmber = Color.parseColor("#FFB020")

    private class StatusRow(val dot: View, val state: TextView, val ok: () -> Boolean)

    private lateinit var executor: CommandExecutor
    private lateinit var scroll: ScrollView
    private lateinit var statusChip: TextView
    private lateinit var ring: View
    private lateinit var micHero: TextView
    private lateinit var heroHint: TextView
    private lateinit var heroSub: TextView
    private lateinit var contButton: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var rateLabel: TextView
    private val rows = ArrayList<StatusRow>()
    private val langViews = ArrayList<TextView>()
    private val langKeys = listOf("bn", "en", "auto")
    private val langLabels = listOf("বাংলা", "English", "বাংলা+EN")

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var overlayAsked = false
    private var pulsing = false
    private val handler = Handler(Looper.getMainLooper())

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!(listening || ListenService.running)) {
                pulsing = false
                ring.alpha = 0f
                return
            }
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 0.7f
            ring.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(1000).start()
            handler.postDelayed(this, 1100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        executor = CommandExecutor(this)
        setContentView(buildUi())
        tts = TextToSpeech(this, this)
        appendLog("KD প্রস্তুত। মাইক চাপুন বা কমান্ড ট্যাপ করুন।")
        appendLog("গোপনীয়তা: ফোনের Google স্পিচ সার্ভিস অডিও ক্লাউডে পাঠাতে পারে।")
        refreshUi()
    }

    // ---- small UI helpers ----------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun gradient(c1: Int, c2: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun ovalGradient(c1: Int, c2: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            shape = GradientDrawable.OVAL
        }

    private fun ovalSolid(c: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
        }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20, cStroke)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        if (title != null) {
            c.addView(tv(title, 15f, cText, true), lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        return c
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notifOk() = if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.POST_NOTIFICATIONS) else true

    // ---- UI construction -----------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        scroll.addView(content)
        content.addView(buildHeader())
        content.addView(buildHero(), lp(MATCH, WRAP).apply { topMargin = dp(14) })
        content.addView(buildQuickCard(), lp(MATCH, WRAP).apply { topMargin = dp(12) })
        content.addView(buildStatusCard(), lp(MATCH, WRAP).apply { topMargin = dp(12) })
        content.addView(buildSettingsCard(), lp(MATCH, WRAP).apply { topMargin = dp(12) })
        content.addView(buildActivityCard(), lp(MATCH, WRAP).apply { topMargin = dp(12) })
        root.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(buildInputBar(), lp(MATCH, WRAP))
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            this.text = "KD"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = ovalGradient(cAccent, cTeal)
        }
        row.addView(logo, lp(dp(46), dp(46)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(tv("KD", 22f, cText, true))
        col.addView(tv("বাংলা ও English ভয়েস সহকারী", 12f, cMuted))
        row.addView(col, lp(0, WRAP, 1f))
        statusChip = tv("মাইক বন্ধ", 12f, cText, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        row.addView(statusChip)
        return row
    }

    private fun buildHero(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(cCard, 28, cStroke)
            setPadding(dp(16), dp(22), dp(16), dp(18))
        }
        val frame = FrameLayout(this)
        ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), cAccent)
            }
            alpha = 0f
        }
        frame.addView(ring, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        micHero = TextView(this).apply {
            this.text = "🎤"
            textSize = 46f
            gravity = Gravity.CENTER
            setOnClickListener { onHeroClick() }
        }
        frame.addView(micHero, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))
        box.addView(frame, lp(dp(200), dp(200)))
        heroHint = tv("মাইক চাপুন, তারপর বলুন", 16f, cText, true).apply { gravity = Gravity.CENTER }
        heroSub = tv("", 12f, cMuted).apply { gravity = Gravity.CENTER }
        box.addView(heroHint, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        box.addView(heroSub, lp(MATCH, WRAP).apply { topMargin = dp(2) })
        contButton = tv("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onContClick() }
        }
        box.addView(contButton, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        return box
    }

    private fun buildQuickCard(): View {
        val c = card("চেষ্টা করুন (ট্যাপ করুন)")
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val rowBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = listOf(
            "🌐 ক্রোম খোলো" to "ক্রোম খোলো",
            "🔦 ফ্ল্যাশলাইট চালু" to "ফ্ল্যাশলাইট চালু করো",
            "🔦 বন্ধ" to "ফ্ল্যাশলাইট বন্ধ করো",
            "🔊 ভলিউম বাড়াও" to "ভলিউম বাড়াও",
            "🕒 কয়টা বাজে" to "এখন কয়টা বাজে",
            "⏰ আধ ঘণ্টা পরে" to "আধ ঘণ্টা পরে মনে করিয়ে দিও",
            "🌍 open chrome" to "open chrome",
            "🕒 what time is it" to "what time is it",
            "🔍 search for news" to "search for bangladesh news",
            "⬇ scroll down" to "scroll down"
        )
        for ((label, cmd) in chips) {
            val chip = tv(label, 13f, cText).apply {
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = rounded(cBg, 18, cStroke)
                setOnClickListener { handleCandidates(listOf(cmd)) }
            }
            rowBox.addView(chip, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        hs.addView(rowBox)
        c.addView(hs)
        return c
    }

    private fun addStatusRow(parent: LinearLayout, label: String, ok: () -> Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener { onClick() }
        }
        val dot = View(this)
        row.addView(dot, lp(dp(10), dp(10)))
        val name = tv(label, 14f, cText).apply { setPadding(dp(12), 0, 0, 0) }
        row.addView(name, lp(0, WRAP, 1f))
        val state = tv("", 12f, cMuted, true)
        row.addView(state)
        parent.addView(row)
        rows.add(StatusRow(dot, state, ok))
    }

    private fun buildStatusCard(): View {
        val c = card("অবস্থা ও অনুমতি")
        addStatusRow(c, "মাইক্রোফোন", { has(Manifest.permission.RECORD_AUDIO) }) { requestMissing() }
        addStatusRow(c, "অ্যাক্সেসিবিলিটি (আঙুলের কাজ)", { KdAccessibilityService.instance != null }) {
            openA11ySettings()
        }
        addStatusRow(c, "অন্য অ্যাপের উপরে দেখানো", { Settings.canDrawOverlays(this) }) { openOverlaySettings() }
        addStatusRow(
            c, "কন্টাক্ট ও কল",
            { has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.CALL_PHONE) }
        ) { requestMissing() }
        addStatusRow(c, "নোটিফিকেশন", { notifOk() }) { requestMissing() }
        return c
    }

    private fun buildSettingsCard(): View {
        val c = card("সেটিংস")

        c.addView(tv("কথা বোঝার ভাষা", 13f, cMuted), lp(MATCH, WRAP))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cBg, 14, cStroke)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        for (i in langKeys.indices) {
            val v = tv(langLabels[i], 13f, cText, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    Prefs.setLang(this@MainActivity, langKeys[i])
                    refreshUi()
                    val extra = if (langKeys[i] == "auto") " (পরীক্ষামূলক; কাজ না করলে একটা ভাষা বেছে নিন)" else ""
                    appendLog("ভাষা: ${langLabels[i]}$extra")
                }
            }
            langViews.add(v)
            seg.addView(v, lp(0, WRAP, 1f))
        }
        c.addView(seg, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        c.addView(tv("কণ্ঠের গতি", 13f, cMuted), lp(MATCH, WRAP).apply { topMargin = dp(14) })
        rateLabel = tv("", 13f, cText, true)
        val seek = SeekBar(this).apply {
            max = 24
            progress = ((Prefs.rate(this@MainActivity) - 0.8f) / 0.05f).roundToInt().coerceIn(0, 24)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val r = 0.8f + p * 0.05f
                    rateLabel.text = String.format(Locale.US, "%.2fx", r)
                    if (fromUser) Prefs.setRate(this@MainActivity, r)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    speak("এই গতিতে বলছি।")
                }
            })
        }
        rateLabel.text = String.format(Locale.US, "%.2fx", Prefs.rate(this))
        val rateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rateRow.addView(seek, lp(0, WRAP, 1f))
        rateRow.addView(rateLabel, lp(dp(56), WRAP))
        c.addView(rateRow, lp(MATCH, WRAP).apply { topMargin = dp(4) })

        val sw = SwitchCompat(this).apply {
            this.text = "শুধু “কেডি” / “KD” বললে কাজ করবে"
            setTextColor(cText)
            textSize = 14f
            isChecked = Prefs.wake(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setWake(this@MainActivity, checked) }
        }
        c.addView(sw, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        return c
    }

    private fun buildActivityCard(): View {
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv("কার্যকলাপ", 15f, cText, true), lp(0, WRAP, 1f))
        head.addView(tv("মুছুন", 12f, cMuted, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { logBox.removeAllViews() }
        })
        c.addView(head, lp(MATCH, WRAP))
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(logBox, lp(MATCH, WRAP))
        return c
    }

    private fun buildInputBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(cCard)
        }
        input = EditText(this).apply {
            hint = "লিখে কমান্ড দিন / type a command"
            setHintTextColor(cMuted)
            setTextColor(cText)
            textSize = 15f
            background = rounded(cBg, 24, cStroke)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitText()
                    true
                } else false
            }
        }
        val send = TextView(this).apply {
            this.text = "➤"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ovalGradient(cAccent, cTeal)
            setOnClickListener { submitText() }
        }
        bar.addView(input, lp(0, WRAP, 1f))
        bar.addView(send, lp(dp(46), dp(46)).apply { leftMargin = dp(8) })
        return bar
    }

    // ---- state refresh ---------------------------------------------------------

    private fun refreshUi() {
        val on = ListenService.running
        val active = on || listening
        statusChip.text = if (on) "● সবসময় শুনছি" else if (listening) "● শুনছি" else "মাইক বন্ধ"
        statusChip.setTextColor(if (active) Color.WHITE else cMuted)
        statusChip.background = rounded(if (active) cRed else cStroke, 20)

        micHero.text = if (listening) "⏹" else "🎤"
        micHero.background = if (active) ovalGradient(cRed, cOrange) else ovalGradient(cAccent, cTeal)
        micHero.alpha = if (on) 0.7f else 1f

        heroHint.text = when {
            on -> "সবসময় শোনা চালু — “কেডি …” বলুন"
            listening -> "শুনছি… এখন বলুন"
            else -> "মাইক চাপুন, তারপর বলুন"
        }
        val li = langKeys.indexOf(Prefs.lang(this)).coerceAtLeast(0)
        if (!listening) {
            heroSub.text = "ভাষা: ${langLabels[li]}  •  গতি ${String.format(Locale.US, "%.2fx", Prefs.rate(this))}"
        }

        contButton.text = if (on) "⏹  সবসময় শোনা বন্ধ করুন" else "🟢  সবসময় শোনা চালু করুন"
        contButton.background = if (on) rounded(cRed, 28) else gradient(cAccent, cTeal, 28)

        for (i in langViews.indices) {
            val sel = i == li
            langViews[i].background = if (sel) gradient(cAccent, Color.parseColor("#5B8CFF"), 11) else rounded(Color.TRANSPARENT, 11)
            langViews[i].setTextColor(if (sel) Color.WHITE else cMuted)
        }
        for (r in rows) {
            val good = r.ok()
            r.dot.background = ovalSolid(if (good) cTeal else cRed)
            r.state.text = if (good) "চালু ✓" else "চালু করুন ›"
            r.state.setTextColor(if (good) cTeal else cAmber)
        }
        if (active && !pulsing) {
            pulsing = true
            handler.post(pulseRunnable)
        }
    }

    private fun addBubble(text: String, kind: Int) {
        // kind: 0 system, 1 user, 2 KD ok, 3 KD info
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val b = TextView(this).apply {
            this.text = text
            maxWidth = maxW
            when (kind) {
                0 -> {
                    textSize = 12f
                    setTextColor(cMuted)
                    gravity = Gravity.CENTER
                }
                1 -> {
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    background = gradient(cAccent, Color.parseColor("#5B8CFF"), 16)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                2 -> {
                    textSize = 15f
                    setTextColor(cText)
                    background = rounded(Color.parseColor("#12332E"), 16, cTeal)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                else -> {
                    textSize = 15f
                    setTextColor(cText)
                    background = rounded(Color.parseColor("#33291A"), 16, cAmber)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
            }
        }
        val params = lp(WRAP, WRAP).apply {
            topMargin = dp(8)
            gravity = when (kind) {
                0 -> Gravity.CENTER_HORIZONTAL
                1 -> Gravity.END
                else -> Gravity.START
            }
        }
        logBox.addView(b, params)
        if (logBox.childCount > 80) logBox.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLog(s: String) {
        val t = s.trim()
        when {
            t.contains("আপনি:") -> addBubble(t.substringAfter("আপনি:").trim(), 1)
            t.contains("KD:") -> {
                val body = t.substringAfter("KD:").trim()
                addBubble(body, if (t.startsWith("\u2705")) 2 else 3)
            }
            else -> addBubble(t, 0)
        }
    }

    // ---- Text to speech ----------------------------------------------------------

    override fun onInit(code: Int) {
        val t = tts
        if (code != TextToSpeech.SUCCESS || t == null) {
            appendLog("⚠️ টেক্সট-টু-স্পিচ ইঞ্জিন চালু হয়নি। উত্তর শুধু লেখায় দেখানো হবে।")
            return
        }
        for (loc in listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn"))) {
            if (t.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE) {
                ttsReady = true
                break
            }
        }
        t.setSpeechRate(Prefs.rate(this))
        appendLog(
            if (ttsReady) "🔊 বাংলা কণ্ঠস্বর প্রস্তুত।"
            else "⚠️ এই ফোনে বাংলা TTS ভয়েস নেই। Settings > ভাষা > টেক্সট-টু-স্পিচ থেকে বাংলা ভয়েস ডাউনলোড করুন। ততক্ষণ উত্তর শুধু লেখায় দেখানো হবে।"
        )
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.setSpeechRate(Prefs.rate(this))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kd")
    }

    // ---- Single-shot listening ---------------------------------------------------

    private fun onHeroClick() {
        if (ListenService.running) {
            appendLog("সবসময় শোনা চালু আছে। বন্ধ করতে নিচের বাটন চাপুন বা “অফ” বলুন।")
        } else {
            toggleListening()
        }
    }

    private fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (ListenService.running) {
            appendLog("ℹ️ সবসময় শোনা চালু আছে। আগে সেটা বন্ধ করুন।")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendLog("⚠️ এই ফোনে স্পিচ রিকগনিশন সার্ভিস পাওয়া যায়নি। Google অ্যাপ ইনস্টল/আপডেট করুন, অথবা লিখে পরীক্ষা করুন।")
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        recognizer?.startListening(Prefs.speechIntent(this, true))
        listening = true
        heroSub.text = ""
        refreshUi()
    }

    private fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listening = false
        refreshUi()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let { heroSub.text = it }
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "কিছু শুনতে পাইনি। আবার চেষ্টা করুন।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "মাইক্রোফোনের অনুমতি নেই।"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "নেটওয়ার্ক সমস্যা (অফলাইন ভাষা প্যাক না থাকলে ইন্টারনেট লাগে)।"
                12, 13 ->
                    "এই ফোনের স্পিচ সার্ভিসে এই ভাষা সমর্থিত নয় বা ডাউনলোড করা নেই।"
                else -> "রিকগনিশন ত্রুটি (কোড $error)।"
            }
            handler.post { stopListening() }
            appendLog("⚠️ $msg")
        }

        override fun onResults(results: Bundle?) {
            handler.post { stopListening() }
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (list.isNullOrEmpty()) {
                appendLog("⚠️ কিছু শুনতে পাইনি।")
                return
            }
            handleCandidates(list)
        }
    }

    // ---- Always-on mode ------------------------------------------------------------

    private fun onContClick() {
        if (ListenService.running) toggleContinuous() else authenticateThen { toggleContinuous() }
    }

    /** Owner check: fingerprint or the phone's own PIN/pattern. This is not voice verification. */
    private fun authenticateThen(onOk: () -> Unit) {
        val allowed = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            appendLog("⚠️ মালিক যাচাইয়ের জন্য ফোনে স্ক্রিন লক (পিন/প্যাটার্ন) বা আঙুলের ছাপ সেট করা থাকতে হবে। সেট করে আবার চেষ্টা করুন।")
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onOk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    appendLog("⚠️ যাচাই হয়নি: $errString")
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("KD সারাক্ষণ শোনা চালু করতে যাচাই করুন")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    private fun toggleContinuous() {
        if (ListenService.running) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
            return
        }
        if (listening) stopListening()
        val missing = wantedPermissions().filter { !has(it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2)
            return
        }
        beginContinuous()
    }

    private fun wantedPermissions(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun requestMissing() {
        val missing = wantedPermissions().filter { !has(it) }
        if (missing.isEmpty()) {
            appendLog("সব অনুমতি দেওয়া আছে।")
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 3)
    }

    private fun beginContinuous() {
        if (!Settings.canDrawOverlays(this) && !overlayAsked) {
            overlayAsked = true
            appendLog(
                "ℹ️ ব্যাকগ্রাউন্ডে থাকা অবস্থায় অ্যাপ খুলতে \"অন্য অ্যাপের উপরে দেখানো\" অনুমতি লাগে। " +
                    "সেটিংস খুলছি। অনুমতি দিয়ে ফিরে এসে আবার বাটন চাপুন।"
            )
            openOverlaySettings()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            appendLog("⚠️ ওই অনুমতি ছাড়া KD ব্যাকগ্রাউন্ডে অ্যাপ খুলতে নাও পারে।")
        }
        val i = Intent(this, ListenService::class.java)
            .setAction(ListenService.ACTION_START)
            .putExtra("wake", Prefs.wake(this))
        ContextCompat.startForegroundService(this, i)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openA11ySettings() {
        appendLog(
            "♿ অ্যাক্সেসিবিলিটি সার্ভিস KD-কে স্ক্রিনের লেখা পড়তে এবং আপনার কমান্ডে চাপ দিতে, লিখতে ও স্ক্রল করতে দেয়। " +
                "KD এই তথ্য সংরক্ষণ বা ইন্টারনেটে পাঠায় না। সেটিংসে \"KD\" বেছে চালু করুন; যেকোনো সময় বন্ধ করতে পারবেন।\n" +
                "\"সীমাবদ্ধ সেটিং\" লেখা এলে: Settings > Apps > KD > ⋮ > \"Allow restricted settings\" চালু করে আবার চেষ্টা করুন।"
        )
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (has(Manifest.permission.RECORD_AUDIO)) startListening()
            else appendLog("এই কাজের জন্য মাইক্রোফোনের অনুমতি প্রয়োজন। অনুমতি ছাড়া শুধু লিখে কমান্ড দিতে পারবেন।")
            2 -> if (has(Manifest.permission.RECORD_AUDIO)) beginContinuous()
            else appendLog("সারাক্ষণ শোনার জন্য মাইক্রোফোনের অনুমতি প্রয়োজন।")
        }
        refreshUi()
    }

    // ---- Command handling -------------------------------------------------------------

    private fun submitText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        handleCandidates(listOf(t))
    }

    private fun handleCandidates(candidates: List<String>) {
        val parsed = candidates.map { it to IntentParser.parse(it) }
        val (heard, intent) = parsed.firstOrNull { it.second !is KdIntent.Unknown } ?: parsed.first()
        appendLog("🗣 আপনি: $heard")
        val result = executor.execute(intent)
        appendLog((if (result.ok) "✅ " else "ℹ️ ") + "KD: " + result.display)
        speak(result.spoken)
    }

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        KdBus.logListener = { appendLog(it) }
        KdBus.stateListener = { refreshUi() }
        KdBus.flush()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        KdBus.logListener = null
        KdBus.stateListener = null
        handler.removeCallbacks(pulseRunnable)
        pulsing = false
        if (listening) stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
