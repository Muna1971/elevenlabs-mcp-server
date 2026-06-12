package ai.muna.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.muna.assistant.databinding.ActivityAssistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** The circular voice orb shown on the assist gesture. */
class AssistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistBinding
    private lateinit var prefs: Prefs
    private lateinit var claude: ClaudeClient
    private lateinit var eleven: ElevenLabsClient

    private val convo = mutableListOf<Message>()
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var screenSent = false
    private lateinit var androidTts: AndroidTts

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listen() else {
                toast(getString(R.string.mic_permission_needed)); finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        claude = ClaudeClient(prefs)
        eleven = ElevenLabsClient(prefs, cacheDir)
        androidTts = AndroidTts(this)

        startPulse(binding.ring1, 0)
        startPulse(binding.ring2, 400)

        binding.orb.setOnClickListener { listen() }
        binding.scrim.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startPulse(view: View, delay: Long) {
        val sx = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.18f)
        val sy = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.18f)
        val a = ObjectAnimator.ofFloat(view, "alpha", 0.85f, 0.15f)
        listOf(sx, sy, a).forEach {
            it.duration = 1100
            it.repeatCount = ObjectAnimator.INFINITE
            it.repeatMode = ObjectAnimator.REVERSE
            it.interpolator = LinearInterpolator()
            it.startDelay = delay
        }
        AnimatorSet().apply { playTogether(sx, sy, a); start() }
    }

    private fun setState(text: String) { binding.status.text = text }

    // ---- Listen ----

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.speech_unavailable)); return
        }
        stopPlayback()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        setState(getString(R.string.listening))
        binding.transcript.text = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-AE")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-AE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { setState(getString(R.string.assist_tap)) }
        override fun onResults(results: Bundle?) {
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) process(spoken) else setState(getString(R.string.assist_tap))
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---- Process ----

    private fun process(text: String) {
        binding.transcript.text = text
        if (prefs.anthropicKey.isBlank()) {
            binding.transcript.text = getString(R.string.need_anthropic_key)
            return
        }
        setState(getString(R.string.thinking))
        convo.add(Message("user", text))
        val executor = ClaudeClient.ToolExecutor { name, input -> Commands.exec(this, name, input) }
        val wantScreen = !screenSent
        lifecycleScope.launch {
            // Wait briefly for the captured screen so Muna can "see" it.
            val screen = if (wantScreen) withContext(Dispatchers.IO) { awaitScreen(1500) } else null
            val attach = screen?.let {
                screenSent = true
                toast("📷 أرى الشاشة الآن")
                ClaudeClient.Attachment("image", it, "image/jpeg")
            }
            if (wantScreen && attach == null) {
                toast("لا توجد لقطة شاشة — فعّلي «تحليل الشاشة» وافتحيني بالزر الجانبي")
            }
            val reply = try {
                withContext(Dispatchers.IO) { claude.complete(convo, attach, executor) }
            } catch (e: Exception) {
                "تعذّر الاتصال: ${e.message ?: ""}"
            }
            convo.add(Message("assistant", reply))
            binding.transcript.text = reply
            speak(reply)
        }
    }

    // ---- Speak ----

    private fun speak(text: String) {
        if (!prefs.speakReplies || text.isBlank()) { setState(getString(R.string.assist_tap)); return }
        setState(getString(R.string.speaking))
        lifecycleScope.launch {
            val file = if (eleven.hasKey()) withContext(Dispatchers.IO) { eleven.synthesize(text) } else null
            if (file == null) {
                // ElevenLabs unavailable → free Android voice, then keep listening.
                androidTts.speak(text) { if (!isFinishing) listen() }
                return@launch
            }
            stopPlayback()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    // Hands-free: keep the conversation going.
                    if (!isFinishing) listen()
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    private fun awaitScreen(timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            ScreenContext.recent()?.let { return it }
            try { Thread.sleep(120) } catch (e: InterruptedException) { break }
        }
        return ScreenContext.recent()
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        recognizer?.destroy()
        androidTts.shutdown()
    }
}
