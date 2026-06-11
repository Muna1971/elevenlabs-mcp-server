package ai.muna.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.muna.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

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
}
