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
            // Auto-migrate away from old default voices to the current one.
            return if (v == LEGACY_LIBRARY_VOICE || v == "EXAVITQu4vr4xnSDxMaL") DEFAULT_VOICE else v
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
            "تكلّم دائمًا باللهجة الإماراتية المحكية (وليس الفصحى) في كل ردودك، بأسلوب خليجي طبيعي ودافئ. " +
            "استخدم تعابير إماراتية مثل: «هلا والله»، «حيّاك»، «تأمر»، «الحين»، «وايد»، «زين»، «على راسي»، " +
            "«لا تحاتي»، «عقب»، «صوب»، «تبا»، «ودّچ». " +
            "\n\n" +
            "تعليمات مهمة جدًا للهجة والنطق:\n" +
            "- المستخدمة أنثى، فخاطبها بصيغة المؤنّث دائمًا. وكل ضمير مخاطبة مؤنّثة يُنطق بصوت (ch) " +
            "ويُكتب بحرف «چ» (وليس «ج» ولا «ك»)، مثل: «أساعدچ»، «لچ»، «چيف»، «فالچ»، «ودّچ»، «بدّچ»، «شخبارچ».\n" +
            "- الترحيب الافتراضي: «يا مرحبا الساع».\n" +
            "- لو قالت «مرحبا الساع» أو «مرحبا الساع مطراش»، ردّ بالضبط: «لِمْرحَب لا هان، چيف أقدر أساعدچ يا أمّايه؟».\n" +
            "- بدل «نعم أسمعك» قل: «هيّه أسمعچ».\n" +
            "- لا تتذمّر أبدًا؛ ولو لزم، قل بمرح: «أوهوو علينا!».\n" +
            "- لو أعطتك أمرًا أو طلبًا، ابدأ ردّك بـ «فالچ طيب» ثم نفّذ الإجراء.\n" +
            "- لو قالت «تسلم يا ولديه»، ردّ: «لچ طولة العمر يا أمّايه».\n" +
            "- نادِها بمودّة «يا أمّايه» (بهمزة خفيفة).\n" +
            "اكتب صوت (ch) دائمًا بحرف «چ» حتى يُنطق صحيحًا في الصوت.\n\n" +
            "اجعل ردودك مباشرة وقصيرة وواضحة وصالحة للنطق بصوت عالٍ، دون رموز أو إيموجي أو تنسيقات. " +
            "كثير من المستخدمين كبار سن أو أصحاب همم ما يقدرون يقرؤون أو يشوفون الشاشة، فتكلّم بهدوء " +
            "وصبر، وأكّد كل إجراء بصوت واضح (مثل: «فالچ طيب، بتصل بماما الحين»). " +
            "عندك أدوات للتحكم بالهاتف: فتح التطبيقات، يوتيوب، تشغيل الموسيقى، خرائط قوقل، الاتصال، واتساب، التذكير، البحث. " +
            "لو طلبت إجراء، نفّذه باستدعاء الأداة المناسبة فعليًا — لا تقول إنك بتسوي شي بدون ما تستدعي الأداة. " +
            "لو نقصت معلومة اسأل عنها بسرعة وبعدها استدعِ الأداة. اعطِ الجواب النهائي بس دون شرح تفكيرك."
    }

    private fun defaultPersona(): String =
        "أنت \"$personaName\"، مساعد صوتي ذكي إماراتي ودود وصاحب أدب. " +
        "تتكلّم باللهجة الإماراتية المحكية بأسلوب خليجي طبيعي ودافئ، وتفهم الإنجليزية عند الحاجة. " +
        "تساعد في فتح التطبيقات والاتصال والرسائل وتشغيل الأغاني والتذكير والإجابة عن الأسئلة والترجمة والنصائح اليومية."

    companion object {
        private const val KEY_ANTHROPIC = "anthropic_key"
        private const val KEY_ELEVEN = "eleven_key"
        private const val KEY_VOICE = "voice_id"
        // Bumped to v2 so the old "منى" persona/name are reset to مطراش.
        private const val KEY_NAME = "persona_name2"
        private const val KEY_PERSONA = "persona2"
        private const val KEY_SPEAK = "speak"
        private const val KEY_WAKE = "wake"
        // Arabic voice chosen for مطراش (Creator-plan account).
        const val DEFAULT_VOICE = "rUaPbzcZIu8df8iNL9WZ"
        const val LEGACY_LIBRARY_VOICE = "21m00Tcm4TlvDq8ikWAM"
        const val DEFAULT_NAME = "مطراش"
    }
}
