package ai.muna.assistant

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.muna.assistant.databinding.ActivityLiveBinding

/** Real-time voice conversation with Gemini Live (expressive, hands-free). */
class LiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var prefs: Prefs
    private var client: GeminiLiveClient? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connect() else {
                toast(getString(R.string.mic_permission_needed)); finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        startPulse(binding.ring1, 0)
        startPulse(binding.ring2, 400)
        binding.btnEnd.setOnClickListener { finish() }

        if (prefs.geminiKey.isBlank()) {
            binding.status.text = "أضف مفتاح Gemini في الإعدادات أولًا"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) connect() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun connect() {
        client = GeminiLiveClient(
            apiKey = prefs.geminiKey,
            systemInstruction = prefs.systemPrompt(),
            onStatus = { s -> runOnUiThread { binding.status.text = s } },
            onToolCall = { name, args -> Commands.exec(this, name, args) },
            opening = intent?.getStringExtra(EXTRA_OPENING)
        ).also { it.start() }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        client?.stop()
        client = null
        // If the wake service launched us, hand the mic back so it can keep
        // listening for the next "مطراش".
        if (prefs.wakeEnabled) {
            runCatching {
                startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_RESUME))
            }
        }
    }

    companion object {
        const val EXTRA_OPENING = "opening"
    }
}
