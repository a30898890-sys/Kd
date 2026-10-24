package app.kd.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var micButton: Button
    private lateinit var contButton: Button
    private lateinit var a11yButton: Button
    private lateinit var wakeCheck: CheckBox
    private lateinit var input: EditText
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var executor: CommandExecutor

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private var overlayAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = CommandExecutor(this)
        setContentView(buildUi())
        tts = TextToSpeech(this, this)
        appendLog(
            "KD ফেজ ১। মাইক বাটন চাপলে একবার শুনবে, তারপর নিজে থেকে বন্ধ হবে। " +
                "অ্যাপ বন্ধ বা ব্যাকগ্রাউন্ডে গেলে মাইকও বন্ধ হয়ে যায়।\n" +
                "গোপনীয়তা: ফোনের Google স্পিচ সার্ভিস অডিও ক্লাউডে পাঠাতে পারে; " +
                "অফলাইন বাংলা প্যাক ডাউনলোড করা থাকলে ফোনেই হয়।"
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "KD — আপনার বাংলা সহকারী"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        status = TextView(this).apply {
            text = "মাইক বন্ধ"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        micButton = Button(this).apply {
            text = "🎤 বলুন"
            textSize = 20f
            setOnClickListener { toggleListening() }
        }
        contButton = Button(this).apply {
            textSize = 16f
            setOnClickListener { onContClick() }
        }
        wakeCheck = CheckBox(this).apply {
            text = "শুধু \"কেডি\" বললে কাজ করবে (প্রস্তাবিত)"
            isChecked = true
        }
        a11yButton = Button(this).apply {
            textSize = 14f
            setOnClickListener { openA11ySettings() }
        }
        input = EditText(this).apply {
            hint = "অথবা লিখে পরীক্ষা করুন"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitText()
                    true
                } else false
            }
        }
        val send = Button(this).apply {
            text = "পাঠান"
            setOnClickListener { submitText() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send)

        log = TextView(this).apply { textSize = 15f }
        scroll = ScrollView(this).apply { addView(log) }

        root.addView(title)
        root.addView(status)
        root.addView(micButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))
        root.addView(contButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(wakeCheck)
        root.addView(a11yButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun appendLog(s: String) {
        if (log.length() > 20000) log.text = log.text.takeLast(10000)
        log.append(s + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- Text to speech ----------------------------------------------------

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
        appendLog(
            if (ttsReady) "🔊 বাংলা কণ্ঠস্বর প্রস্তুত।"
            else "⚠️ এই ফোনে বাংলা TTS ভয়েস নেই। Settings > ভাষা > টেক্সট-টু-স্পিচ থেকে বাংলা ভয়েস ডাউনলোড করুন। " +
                "ততক্ষণ উত্তর শুধু লেখায় দেখানো হবে।"
        )
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kd")
    }

    // ---- Speech recognition --------------------------------------------------

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(i)
        setListeningUi(true)
    }

    private fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        setListeningUi(false)
    }

    private fun setListeningUi(on: Boolean) {
        listening = on
        status.text = if (on) "🔴 মাইক চালু — শুনছি…" else "মাইক বন্ধ"
        micButton.text = if (on) "⏹ বন্ধ করুন" else "🎤 বলুন"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                beginContinuous()
            } else {
                appendLog("সারাক্ষণ শোনার জন্য মাইক্রোফোনের অনুমতি প্রয়োজন।")
            }
            return
        }
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                appendLog("এই কাজের জন্য মাইক্রোফোনের অনুমতি প্রয়োজন। অনুমতি ছাড়া শুধু লিখে কমান্ড দিতে পারবেন।")
            }
        }
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
                ?.firstOrNull()?.let { status.text = "🔴 শুনছি: $it" }
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "কিছু শুনতে পাইনি। আবার চেষ্টা করুন।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "মাইক্রোফোনের অনুমতি নেই।"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "নেটওয়ার্ক সমস্যা (অফলাইন বাংলা প্যাক না থাকলে ইন্টারনেট লাগে)।"
                12, 13 ->
                    "এই ফোনের স্পিচ সার্ভিসে বাংলা (bn-BD) সমর্থিত নয় বা ডাউনলোড করা নেই।"
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

    // ---- Always-on mode ------------------------------------------------------

    private fun toggleContinuous() {
        if (ListenService.running) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
            return
        }
        if (listening) stopListening()
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2)
            return
        }
        beginContinuous()
    }

    private fun beginContinuous() {
        if (!Settings.canDrawOverlays(this) && !overlayAsked) {
            overlayAsked = true
            appendLog(
                "ℹ️ ব্যাকগ্রাউন্ডে থাকা অবস্থায় অ্যাপ খুলতে \"অন্য অ্যাপের উপরে দেখানো\" অনুমতি লাগে। " +
                    "সেটিংস খুলছি। অনুমতি দিয়ে ফিরে এসে আবার বাটন চাপুন।"
            )
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            appendLog("⚠️ ওই অনুমতি ছাড়া KD ব্যাকগ্রাউন্ডে অ্যাপ খুলতে নাও পারে।")
        }
        val i = Intent(this, ListenService::class.java)
            .setAction(ListenService.ACTION_START)
            .putExtra("wake", wakeCheck.isChecked)
        ContextCompat.startForegroundService(this, i)
    }

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

    private fun openA11ySettings() {
        appendLog(
            "♿ অ্যাক্সেসিবিলিটি সার্ভিস KD-কে স্ক্রিনের লেখা পড়তে এবং আপনার কমান্ডে চাপ দিতে, লিখতে ও স্ক্রল করতে দেয়। " +
                "KD এই তথ্য সংরক্ষণ বা ইন্টারনেটে পাঠায় না। সেটিংসে \"KD\" বেছে চালু করুন; যেকোনো সময় বন্ধ করতে পারবেন।\n" +
                "যদি \"সীমাবদ্ধ সেটিং\" (Restricted setting) লেখা আসে: ফোনের Settings > Apps > KD > ⋮ (তিন দাগ) > " +
                "\"Allow restricted settings\" চালু করে আবার চেষ্টা করুন।"
        )
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onResume() {
        super.onResume()
        refreshContUi()
    }

    private fun refreshContUi() {
        val on = ListenService.running
        contButton.text = if (on) "🔴 সবসময় শোনা বন্ধ করুন" else "🟢 সবসময় শোনা চালু করুন"
        micButton.isEnabled = !on
        a11yButton.text = if (KdAccessibilityService.instance != null)
            "♿ অ্যাক্সেসিবিলিটি: চালু ✓ (সেটিংস)" else "♿ অ্যাক্সেসিবিলিটি চালু করুন (আঙুলের কাজের জন্য)"
        if (on) status.text = "🔴 সবসময় শোনা চালু (বন্ধ করতে \"অফ\" বলুন)"
        else if (!listening) status.text = "মাইক বন্ধ"
    }

    override fun onStart() {
        super.onStart()
        KdBus.logListener = { appendLog(it) }
        KdBus.stateListener = { refreshContUi() }
        KdBus.flush()
        refreshContUi()
    }

    // ---- Command handling ------------------------------------------------------

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

    override fun onStop() {
        super.onStop()
        KdBus.logListener = null
        KdBus.stateListener = null
        if (listening) stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
