package ai.muna.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.muna.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var claude: ClaudeClient
    private lateinit var eleven: ElevenLabsClient

    private val convo = mutableListOf<Message>()
    private lateinit var adapter: ChatAdapter

    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var busy = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else toast(getString(R.string.mic_permission_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        claude = ClaudeClient(prefs)
        eleven = ElevenLabsClient(prefs, cacheDir)

        adapter = ChatAdapter(mutableListOf())
        binding.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.adapter = adapter
        showHome()

        // Input
        binding.btnSend.setOnClickListener { sendTyped() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendTyped(); true
            } else false
        }
        binding.btnMic.setOnClickListener { onMicTapped() }
        binding.btnAttach.setOnClickListener { toast(getString(R.string.coming_soon)) }

        // Home cards
        binding.cardMeeting.setOnClickListener { send(getString(R.string.prompt_meeting)) }
        binding.cardMessage.setOnClickListener { send(getString(R.string.prompt_message)) }
        binding.cardAcademic.setOnClickListener { send(getString(R.string.prompt_academic)) }
        binding.cardPresent.setOnClickListener { send(getString(R.string.prompt_present)) }

        // Bottom bar
        binding.navSettings.setOnClickListener { openSettings() }
        binding.navNew.setOnClickListener { newConversation() }
        binding.navMeeting.setOnClickListener { toast(getString(R.string.coming_soon)) }
        binding.navHistory.setOnClickListener { toast(getString(R.string.coming_soon)) }

        // Auto-listen when launched as the device assistant.
        val action = intent?.action
        if (action == Intent.ACTION_ASSIST || action == "android.intent.action.VOICE_ASSIST") {
            binding.root.post { onMicTapped() }
        }
    }

    // ---- View state ----

    private fun showHome() {
        binding.homeView.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    private fun showChat() {
        binding.homeView.visibility = View.GONE
        binding.recycler.visibility = View.VISIBLE
    }

    private fun newConversation() {
        stopPlayback()
        convo.clear()
        adapter.clear()
        showHome()
    }

    // ---- Sending ----

    private fun sendTyped() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.input.setText("")
        send(text)
    }

    private fun send(text: String) {
        if (busy) return
        if (prefs.anthropicKey.isBlank()) {
            toast(getString(R.string.need_anthropic_key))
            openSettings()
            return
        }
        stopPlayback()
        showChat()
        adapter.add(Message("user", text))
        convo.add(Message("user", text))
        scrollDown()
        setStatus(getString(R.string.thinking))
        busy = true

        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) { claude.complete(convo) }
            } catch (e: Exception) {
                val msg = e.message ?: "خطأ غير معروف"
                if (msg == "MISSING_ANTHROPIC_KEY") getString(R.string.need_anthropic_key)
                else "تعذّر الاتصال: $msg"
            }
            busy = false
            setStatus(null)
            convo.add(Message("assistant", reply))
            adapter.add(Message("assistant", reply))
            scrollDown()
            speak(reply)
        }
    }

    // ---- Voice output (ElevenLabs) ----

    private fun speak(text: String) {
        if (!prefs.speakReplies || !eleven.hasKey() || text.isBlank()) return
        setStatus(getString(R.string.speaking))
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { eleven.synthesize(text) }
            if (file == null) {
                setStatus(null)
                return@launch
            }
            stopPlayback()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    setStatus(null)
                    it.release()
                    if (player === it) player = null
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    // ---- Voice input (Android SpeechRecognizer) ----

    private fun onMicTapped() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.speech_unavailable)); return
        }
        stopPlayback()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        setStatus(getString(R.string.listening))
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { setStatus(null) }

        override fun onResults(results: Bundle?) {
            setStatus(null)
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spoken = list?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) send(spoken)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---- Helpers ----

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun scrollDown() = binding.recycler.post {
        binding.recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun setStatus(text: String?) {
        binding.status.text = text ?: ""
        binding.status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        recognizer?.destroy()
    }
}
