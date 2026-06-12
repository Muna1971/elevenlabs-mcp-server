package ai.muna.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted local storage for API keys and persona settings.
 * Keys never leave the device — they are used directly against the
 * Anthropic and ElevenLabs APIs from the app.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        try {
            EncryptedSharedPreferences.create(
                context,
                "muna_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback if the keystore-backed store can't be opened
            context.getSharedPreferences("muna_prefs", Context.MODE_PRIVATE)
        }
    }

    // Keys fall back to the values baked in at build time (BuildConfig) when the
    // user hasn't entered their own in Settings.
    var anthropicKey: String
        get() = (sp.getString(KEY_ANTHROPIC, "") ?: "").ifBlank { BuildConfig.ANTHROPIC_KEY }
        set(v) = sp.edit().putString(KEY_ANTHROPIC, v.trim()).apply()

    var elevenKey: String
        get() = (sp.getString(KEY_ELEVEN, "") ?: "").ifBlank { BuildConfig.ELEVEN_KEY }
        set(v) = sp.edit().putString(KEY_ELEVEN, v.trim()).apply()

    var voiceId: String
        get() {
            val v = (sp.getString(KEY_VOICE, "") ?: "")
                .ifBlank { BuildConfig.VOICE_ID.ifBlank { DEFAULT_VOICE } }
            // Auto-migrate away from the old shared-library voice that free
            // accounts can't use via the API.
            return if (v == LEGACY_LIBRARY_VOICE) DEFAULT_VOICE else v
        }
        set(v) = sp.edit().putString(KEY_VOICE, v.trim()).apply()

    var personaName: String
        get() = sp.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString(KEY_NAME, v.trim()).apply()

    var persona: String
        get() = sp.getString(KEY_PERSONA, defaultPersona()) ?: defaultPersona()
        set(v) = sp.edit().putString(KEY_PERSONA, v).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean(KEY_SPEAK, true)
        set(v) = sp.edit().putBoolean(KEY_SPEAK, v).apply()

    var wakeEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE, false)
        set(v) = sp.edit().putBoolean(KEY_WAKE, v).apply()

    fun systemPrompt(): String {
        val base = persona.ifBlank { defaultPersona() }
        return base + "\n\n" +
            "أنت تُستخدمين كمساعدة صوتية على الهاتف. اجعلي ردودك مباشرة وقصيرة وواضحة وصالحة " +
            "للنطق بصوت عالٍ، دون رموز أو تنسيقات. أجيبي بنفس لغة المستخدمة. " +
            "تفهمين اللهجة الإماراتية والخليجية جيدًا وتتعاملين معها بطبيعية (مثل: سيري، ودّي، أبا، " +
            "يبتلي، شحقّه، وين، هاللحين، عساك، تكفّى)، وردّي بعربية بسيطة وسهلة. " +
            "كثير من مستخدميك من كبار السن أو أصحاب الهمم ممّن لا يقرؤون أو لا يرون الشاشة، " +
            "لذا تحدّثي بهدوء وصبر، وأكّدي كل إجراء بصوت واضح (مثل: «حاضر، أتصل بماما الآن»)، " +
            "واسألي سؤالًا واحدًا بسيطًا عند الحاجة لمعلومة ناقصة. " +
            "لديك أدوات للتحكم بالهاتف: فتح التطبيقات، يوتيوب، خرائط جوجل، الاتصال، واتساب، التذكير، البحث. " +
            "عندما تطلب المستخدمة إجراءً نفّذيه باستدعاء الأداة المناسبة فعليًا — لا تقولي إنك ستفعلين " +
            "شيئًا دون استدعاء الأداة. إن نقصت معلومة فاسأليها عنها بإيجاز ثم استدعي الأداة بعد أن تجيب. " +
            "قدّمي الإجابة النهائية فقط دون شرح لطريقة تفكيرك."
    }

    private fun defaultPersona(): String =
        "أنت \"$personaName\"، مساعدة شخصية ذكية وودودة لـ مُنى. " +
        "تتحدثين بالعربية بطلاقة وبالإنجليزية عند الحاجة، بأسلوب دافئ ومحترم ومختصر. " +
        "تساعدين في التذكير، والإجابة عن الأسئلة، والكتابة، والترجمة، والنصائح اليومية."

    companion object {
        private const val KEY_ANTHROPIC = "anthropic_key"
        private const val KEY_ELEVEN = "eleven_key"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_NAME = "persona_name"
        private const val KEY_PERSONA = "persona"
        private const val KEY_SPEAK = "speak"
        private const val KEY_WAKE = "wake"
        // Sarah — a "premade" voice usable on free-tier accounts via the API.
        const val DEFAULT_VOICE = "EXAVITQu4vr4xnSDxMaL"
        const val LEGACY_LIBRARY_VOICE = "21m00Tcm4TlvDq8ikWAM"
        const val DEFAULT_NAME = "منى الذكية"
    }
}
