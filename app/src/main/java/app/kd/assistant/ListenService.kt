package app.kd.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * Foreground service that keeps re-starting Android's SpeechRecognizer.
 * NOT on-device wake-word detection: audio goes to the phone's speech service
 * (Google, possibly cloud) while this is on. Stops on "অফ", the notification
 * button, or the app's stop button.
 */
class ListenService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "app.kd.assistant.START"
        const val ACTION_STOP = "app.kd.assistant.STOP"
        private const val CHANNEL = "kd_listen"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var active = false
    private var wakeRequired = true
    private var acceptUntil = 0L
    private var stopAfterSpeech = false
    private var openWindowAfterSpeech = false
    private var netErrors = 0
    private lateinit var executor: CommandExecutor

    private val listenRunnable = Runnable { listenOnce() }
    private val resumeRunnable = Runnable { onSpeechFinished() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        wakeRequired = intent?.getBooleanExtra("wake", true) ?: true
        if (!active) startActive()
        return START_NOT_STICKY
    }

    private fun startActive() {
        active = true
        running = true
        executor = CommandExecutor(applicationContext)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            KdBus.log("⚠️ সারাক্ষণ শোনা চালু করা যায়নি: ${e.javaClass.simpleName} ${e.message ?: ""}")
            stopAll()
            return
        }
        tts = TextToSpeech(this, this)
        KdBus.log(
            "🟢 সবসময় শোনা চালু। " +
                (if (wakeRequired) "কমান্ডের আগে \"কেডি\" বলুন। " else "") +
                "বন্ধ করতে \"অফ\" বলুন।"
        )
        KdBus.stateChanged()
        scheduleListen(500)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "KD শোনা", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): android.app.Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("KD শুনছে")
            .setContentText("বন্ধ করতে \"অফ\" বলুন বা বাটন চাপুন")
            .setContentIntent(openPi)
            .addAction(0, "বন্ধ করুন", stopPi)
            .setOngoing(true)
            .build()
    }

    // ---- TTS ---------------------------------------------------------------

    override fun onInit(code: Int) {
        val t = tts
        if (code != TextToSpeech.SUCCESS || t == null) {
            KdBus.log("⚠️ টেক্সট-টু-স্পিচ চালু হয়নি; উত্তর শুধু লেখায়।")
            return
        }
        for (loc in listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn"))) {
            if (t.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE) {
                ttsReady = true
                break
            }
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { onSpeechFinished() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onSpeechFinished() }
            }
        })
        if (!ttsReady) KdBus.log("⚠️ বাংলা TTS ভয়েস নেই; উত্তর শুধু লেখায়।")
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            onSpeechFinished()
            return
        }
        speaking = true
        recognizer?.cancel()
        handler.removeCallbacks(listenRunnable)
        handler.removeCallbacks(resumeRunnable)
        handler.postDelayed(resumeRunnable, 12000)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kd")
    }

    private fun onSpeechFinished() {
        handler.removeCallbacks(resumeRunnable)
        speaking = false
        if (openWindowAfterSpeech) {
            acceptUntil = SystemClock.elapsedRealtime() + 10000
            openWindowAfterSpeech = false
        }
        if (stopAfterSpeech) {
            stopAll()
            return
        }
        scheduleListen(300)
    }

    // ---- Listening loop ------------------------------------------------------

    private fun scheduleListen(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private fun listenOnce() {
        if (!active || speaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            KdBus.log("⚠️ স্পিচ রিকগনিশন সার্ভিস পাওয়া যায়নি।")
            stopAll()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                12, 13 -> {
                    KdBus.log("⚠️ এই ফোনের স্পিচ সার্ভিসে বাংলা (bn-BD) সমর্থিত নয় বা ডাউনলোড করা নেই।")
                    stopAll()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    KdBus.log("⚠️ মাইক্রোফোনের অনুমতি নেই।")
                    stopAll()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListen(1500)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    netErrors++
                    if (netErrors == 1) KdBus.log("⚠️ নেটওয়ার্ক সমস্যা; আবার চেষ্টা করছি…")
                    scheduleListen(2000)
                }
                else -> scheduleListen(300)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            netErrors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!list.isNullOrEmpty()) processResults(list)
            if (!speaking) scheduleListen(300)
        }
    }

    // ---- Command handling ------------------------------------------------------

    private fun woke(c: String): Boolean {
        val n = TextNormalizer.normalize(c)
        return TextNormalizer.stripWakeWord(n) != n
    }

    private fun processResults(list: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val open = !wakeRequired || now < acceptUntil
        var heard: String? = null
        var intent: KdIntent? = null
        for (c in list) {
            val it = IntentParser.parse(c)
            if (it is KdIntent.Unknown) continue
            if (it is KdIntent.Sleep || woke(c) || open) {
                heard = c
                intent = it
                break
            }
        }
        if (intent == null) {
            val w = list.firstOrNull { woke(it) }
            if (w == null) {
                KdBus.log("· শুনেছি: ${list.first()} (\"কেডি\" ছাড়া, উপেক্ষা করলাম)")
                return
            }
            heard = w
            intent = KdIntent.Unknown(w)
        }
        KdBus.log("🗣 আপনি: $heard")
        val result = executor.execute(intent)
        KdBus.log((if (result.ok) "✅ " else "ℹ️ ") + "KD: " + result.display)
        if (intent is KdIntent.Greeting || executor.hasPending()) openWindowAfterSpeech = true
        if (intent is KdIntent.Sleep) stopAfterSpeech = true
        speak(result.spoken)
    }

    private fun stopAll() {
        val was = active
        active = false
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (was) KdBus.log("⏹ সবসময় শোনা বন্ধ। মাইক এখন বন্ধ।")
        KdBus.stateChanged()
    }

    override fun onDestroy() {
        if (active) stopAll()
        super.onDestroy()
    }
}
