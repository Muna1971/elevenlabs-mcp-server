package ai.muna.assistant

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Transparent activity that requests the one-time screen-capture grant and then
 * starts [ScreenProjectionService] with the token, so Matrash can read the screen.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val ask = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenProjectionService::class.java).apply {
                putExtra(ScreenProjectionService.EXTRA_CODE, result.resultCode)
                putExtra(ScreenProjectionService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svc)
            Prefs(this).screenReadEnabled = true
            Toast.makeText(this, "تم تفعيل قراءة الشاشة ✅", Toast.LENGTH_SHORT).show()
        } else {
            Prefs(this).screenReadEnabled = false
            Toast.makeText(this, "لم يتم منح إذن قراءة الشاشة", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { ask.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { finish() }
    }
}
