package ai.muna.assistant

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.muna.assistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etAnthropic.setText(prefs.anthropicKey)
        binding.etEleven.setText(prefs.elevenKey)
        binding.etVoiceId.setText(prefs.voiceId)
        binding.etPersonaName.setText(prefs.personaName)
        binding.etPersona.setText(prefs.persona)
        binding.swSpeak.isChecked = prefs.speakReplies

        binding.btnTestVoice.setOnClickListener { testVoice() }

        binding.btnSave.setOnClickListener {
            prefs.anthropicKey = binding.etAnthropic.text.toString()
            prefs.elevenKey = binding.etEleven.text.toString()
            prefs.voiceId = binding.etVoiceId.text.toString()
            prefs.personaName = binding.etPersonaName.text.toString()
            prefs.persona = binding.etPersona.text.toString()
            prefs.speakReplies = binding.swSpeak.isChecked
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun testVoice() {
        // Save the currently-entered key/voice first so the test uses them.
        prefs.elevenKey = binding.etEleven.text.toString()
        prefs.voiceId = binding.etVoiceId.text.toString()
        val eleven = ElevenLabsClient(prefs, cacheDir)
        binding.voiceResult.text = "جارٍ الاختبار…"
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { eleven.synthesize("مرحبًا، أنا منى الذكية.") }
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
    }
}
