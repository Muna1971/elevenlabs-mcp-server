package ai.muna.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.muna.assistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var player: MediaPlayer? = null
    private var testClient: GeminiLiveClient? = null
    private var heardTestAudio = false

    private val wakePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) startWake()
            else {
                binding.swWake.isChecked = false
                Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etAnthropic.setText(prefs.anthropicKey)
        binding.etEleven.setText(prefs.elevenKey)
        binding.etGemini.setText(prefs.geminiKey)
        binding.etVoiceId.setText(prefs.voiceId)
        binding.etPersonaName.setText(prefs.personaName)
        binding.etPersona.setText(prefs.persona)
        binding.swSpeak.isChecked = prefs.speakReplies
        binding.swWake.isChecked = prefs.wakeEnabled
        binding.swScreenRead.isChecked = prefs.screenReadEnabled && ScreenProjectionService.instance != null
        binding.spAddressee.setSelection(prefs.addressee)

        binding.swScreenRead.setOnCheckedChangeListener { btn, checked ->
            if (!btn.isPressed) return@setOnCheckedChangeListener
            if (checked) {
                // Ask for the one-time screen-capture grant.
                startActivity(Intent(this, ProjectionRequestActivity::class.java))
            } else {
                prefs.screenReadEnabled = false
                stopService(Intent(this, ScreenProjectionService::class.java))
            }
        }

        binding.btnTestVoice.setOnClickListener { testVoice() }

        binding.swWake.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                val missing = needed.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) startWake() else wakePermissions.launch(missing.toTypedArray())
            } else {
                stopWake()
            }
        }

        binding.btnSave.setOnClickListener {
            prefs.anthropicKey = binding.etAnthropic.text.toString()
            prefs.elevenKey = binding.etEleven.text.toString()
            prefs.geminiKey = binding.etGemini.text.toString()
            prefs.voiceId = binding.etVoiceId.text.toString()
            prefs.personaName = binding.etPersonaName.text.toString()
            prefs.persona = binding.etPersona.text.toString()
            prefs.speakReplies = binding.swSpeak.isChecked
            prefs.addressee = binding.spAddressee.selectedItemPosition
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startWake() {
        // Background app-launch (calls, maps…) needs "Display over other apps".
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّلي «العرض فوق التطبيقات» ليُنفّذ الأوامر أثناء القيادة", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        prefs.wakeEnabled = true
        val intent = Intent(this, WakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        Toast.makeText(this, "تم تفعيل وضع النداء — قل «مطراش»", Toast.LENGTH_SHORT).show()
    }

    private fun stopWake() {
        prefs.wakeEnabled = false
        stopService(Intent(this, WakeService::class.java))
    }

    private fun testVoice() {
        // Persist typed keys first so the test uses the current values.
        prefs.geminiKey = binding.etGemini.text.toString()
        prefs.elevenKey = binding.etEleven.text.toString()
        prefs.voiceId = binding.etVoiceId.text.toString()
        prefs.personaName = binding.etPersonaName.text.toString()
        prefs.persona = binding.etPersona.text.toString()
        prefs.addressee = binding.spAddressee.selectedItemPosition
        when {
            prefs.geminiKey.isNotBlank() -> testGeminiVoice()
            prefs.elevenKey.isNotBlank() -> testElevenVoice()
            else -> binding.voiceResult.text = "أضيفي مفتاح Gemini (أو ElevenLabs) أولًا"
        }
    }

    /** Hear Matrash's real (Gemini) voice — no microphone needed. */
    private fun testGeminiVoice() {
        heardTestAudio = false
        binding.voiceResult.text = "جارٍ الاتصال بـ Gemini…"
        testClient?.stop()
        testClient = GeminiLiveClient(
            context = this,
            apiKey = prefs.geminiKey,
            // Light prompt so the test is fast and isolates the voice path.
            systemInstruction = "أنت «مطراش»، مساعد صوتي إماراتي بلهجة أهل العين. تكلّم بالعربية بإيجاز.",
            onStatus = { s -> runOnUiThread {
                binding.voiceResult.text = when {
                    s.contains("يتكلّم") -> { heardTestAudio = true; "🔊 يتكلّم مطراش الآن — ارفعي صوت الوسائط لو ما تسمعين" }
                    s.contains("انتهت") -> if (heardTestAudio)
                        "✅ نجح — إذا ما سمعتِ صوت، ارفعي صوت «الوسائط»"
                    else
                        "❌ اتصل لكن ما وصل صوت — تأكدي من الإنترنت"
                    s.contains("تعذّر") || s.contains("انقطع") -> "❌ $s"
                    else -> s
                }
            } },
            onToolCall = { _, _ -> "" },
            opening = "رحّب بإيجاز في جملة واحدة وقل إنك مطراش مساعدها الصوتي.",
            onEnded = { runOnUiThread { testClient = null } },
            idleMs = 15000L,
            captureMic = false
        ).also { it.start() }
    }

    private fun testElevenVoice() {
        val eleven = ElevenLabsClient(prefs, cacheDir)
        binding.voiceResult.text = "جارٍ الاختبار…"
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { eleven.synthesize("مرحبًا، أنا مطراش مساعدك الصوتي الذكي.") }
            if (file == null) {
                binding.voiceResult.text = "❌ فشل الصوت: ${eleven.lastError ?: "سبب غير معروف"}"
                return@launch
            }
            binding.voiceResult.text = "✅ نجح الصوت — يُفترض أن تسمعي الآن."
            runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        testClient?.stop(); testClient = null
    }
}
