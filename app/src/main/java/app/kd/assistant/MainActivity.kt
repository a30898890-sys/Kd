package app.kd.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var micButton: Button
    private lateinit var input: EditText
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var executor: CommandExecutor

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())

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
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun appendLog(s: String) {
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
        if (listening) stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
