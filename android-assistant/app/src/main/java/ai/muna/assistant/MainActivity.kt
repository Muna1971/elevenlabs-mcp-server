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

    // Meeting mode
    private var meetingActive = false
    private var meetingRecognizer: SpeechRecognizer? = null
    private val transcript = StringBuilder()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast(getString(R.string.mic_permission_needed))
        }

    private val meetingPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginMeeting() else toast(getString(R.string.mic_permission_needed))
        }

    // First-run permission prompt (no auto-action)
    private val initialPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        binding.btnSend.setOnClickListener { sendTyped() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendTyped(); true
            } else false
        }
        binding.btnMic.setOnClickListener { onMicTapped() }
        binding.btnAttach.setOnClickListener { toast(getString(R.string.coming_soon)) }

        binding.cardMeeting.setOnClickListener { send(getString(R.string.prompt_meeting)) }
        binding.cardMessage.setOnClickListener { send(getString(R.string.prompt_message)) }
        binding.cardAcademic.setOnClickListener { send(getString(R.string.prompt_academic)) }
        binding.cardPresent.setOnClickListener { send(getString(R.string.prompt_present)) }

        binding.navSettings.setOnClickListener { openSettings() }
        binding.navNew.setOnClickListener { newConversation() }
        binding.navMeeting.setOnClickListener { toggleMeeting() }
        binding.navHistory.setOnClickListener { toast(getString(R.string.coming_soon)) }
        binding.btnStopMeeting.setOnClickListener { stopMeeting() }

        val action = intent?.action
        if (action == Intent.ACTION_ASSIST || action == "android.intent.action.VOICE_ASSIST") {
            binding.root.post { onMicTapped() }
        } else if (!hasMic()) {
            // Ask for the microphone permission on first open.
            initialPermission.launch(Manifest.permission.RECORD_AUDIO)
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
        if (meetingActive) stopMeetingListeningOnly()
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
            toast(getString(R.string.need_anthropic_key)); openSettings(); return
        }
        stopPlayback()
        showChat()
        adapter.add(Message("user", text))
        convo.add(Message("user", text))
        scrollDown()
        runCompletion()
    }

    /** Shared call to the model for whatever is currently in [convo]. */
    private fun runCompletion() {
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
            if (file == null) { setStatus(null); return@launch }
            stopPlayback()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    setStatus(null); it.release(); if (player === it) player = null
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

    // ---- Voice input (single shot) ----

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun onMicTapped() {
        if (meetingActive) { toast(getString(R.string.coming_soon)); return }
        if (hasMic()) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
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
        setStatus(getString(R.string.listening))
        recognizer?.startListening(recognizeIntent())
    }

    private fun recognizeIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) send(spoken)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---- Meeting mode (continuous capture + summary) ----

    private fun toggleMeeting() {
        if (meetingActive) stopMeeting() else startMeeting()
    }

    private fun startMeeting() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.speech_unavailable)); return
        }
        if (hasMic()) beginMeeting() else meetingPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginMeeting() {
        stopPlayback()
        recognizer?.destroy(); recognizer = null
        meetingActive = true
        transcript.setLength(0)
        binding.meetingBanner.visibility = View.VISIBLE
        updateMeetingBanner()
        startMeetingListening()
    }

    private fun startMeetingListening() {
        if (!meetingActive) return
        meetingRecognizer?.destroy()
        meetingRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(meetingListener)
        }
        meetingRecognizer?.startListening(recognizeIntent())
    }

    private fun restartMeetingSoon() {
        if (!meetingActive) return
        binding.root.postDelayed({ startMeetingListening() }, 300)
    }

    private val meetingListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { restartMeetingSoon() }
        override fun onResults(results: Bundle?) {
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(spoken)
                updateMeetingBanner()
            }
            restartMeetingSoon()
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun updateMeetingBanner() {
        val words = if (transcript.isBlank()) 0 else transcript.trim().split(Regex("\\s+")).size
        binding.meetingText.text = "🔴 وضع الاجتماع — يسجّل…  ($words كلمة)"
    }

    private fun stopMeetingListeningOnly() {
        meetingActive = false
        meetingRecognizer?.destroy(); meetingRecognizer = null
        binding.meetingBanner.visibility = View.GONE
    }

    private fun stopMeeting() {
        val text = transcript.toString().trim()
        stopMeetingListeningOnly()
        if (text.isEmpty()) { toast("لم يُلتقط أي كلام"); return }
        summarizeMeeting(text)
    }

    private fun summarizeMeeting(text: String) {
        if (busy) return
        if (prefs.anthropicKey.isBlank()) {
            toast(getString(R.string.need_anthropic_key)); openSettings(); return
        }
        showChat()
        adapter.add(Message("user", "📝 تلخيص الاجتماع"))
        convo.add(
            Message(
                "user",
                "فيما يلي تفريغ نصّي لاجتماع. لخّصه بإيجاز، ثم اذكر بوضوح: " +
                    "أبرز النقاط، القرارات المتّخذة، والمهام (ومن المسؤول إن ذُكر، وأي مواعيد). " +
                    "التفريغ:\n\n$text"
            )
        )
        scrollDown()
        runCompletion()
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
        meetingRecognizer?.destroy()
    }
}
