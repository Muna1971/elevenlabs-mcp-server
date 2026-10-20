package com.muna.livetranslate

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var languageSpinner: Spinner
    private lateinit var textSizeSeek: SeekBar
    private lateinit var preview: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var preparing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) prepareModelsThenCapture()
            else toast(getString(R.string.need_audio))
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CaptionService::class.java)
                    .putExtra(CaptionService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptionService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                status.text = getString(R.string.running)
                // Get out of the way so the user can open their video app.
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        languageSpinner = findViewById(R.id.languageSpinner)
        textSizeSeek = findViewById(R.id.textSizeSeek)
        preview = findViewById(R.id.preview)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        val languages = SourceLanguage.entries
        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.label }
        )
        languageSpinner.setSelection(languages.indexOf(prefs.language))
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.language = languages[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        textSizeSeek.progress = prefs.textSize - Prefs.MIN_TEXT_SIZE
        applyPreviewSize()
        textSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.textSize = Prefs.MIN_TEXT_SIZE + value
                applyPreviewSize()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener {
            stopService(Intent(this, CaptionService::class.java))
            status.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        if (!preparing) status.text = if (CaptionService.isRunning) getString(R.string.running) else ""
    }

    private fun applyPreviewSize() =
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSize.toFloat())

    private fun onStartClicked() {
        if (preparing) return
        if (CaptionService.isRunning) stopService(Intent(this, CaptionService::class.java))
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.need_overlay))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /** Downloads the offline models the first time a language is used, then asks for audio capture. */
    private fun prepareModelsThenCapture() {
        val lang = prefs.language
        preparing = true
        setBusy(true)
        lifecycleScope.launch {
            try {
                if (!WhisperEngine.isCpuSupported()) {
                    status.text = getString(R.string.cpu_unsupported)
                    return@launch
                }
                if (!ModelManager.isSpeechModelReady(this@MainActivity)) {
                    progress.isIndeterminate = false
                    status.text = getString(R.string.downloading_speech, 0)
                    ModelManager.downloadSpeechModel(this@MainActivity) { percent ->
                        progress.progress = percent
                        status.text = getString(R.string.downloading_speech, percent)
                    }
                }
                progress.isIndeterminate = true
                status.text = getString(R.string.downloading_translation)
                // In auto mode English is fetched up front; other languages are fetched when first heard.
                ModelManager.ensureTranslationModel(
                    if (lang == SourceLanguage.AUTO) SourceLanguage.ENGLISH.code else lang.code
                )
                status.text = getString(R.string.ready)
                launchCapturePrompt()
            } catch (e: Exception) {
                status.text = getString(R.string.download_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                preparing = false
                setBusy(false)
            }
        }
    }

    private fun launchCapturePrompt() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole-screen capture is needed to hear every app.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        startButton.isEnabled = !busy
        languageSpinner.isEnabled = !busy
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
