package ai.muna.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/**
 * Lightweight on-device command interpreter. Recognizes a set of Arabic/English
 * voice or text commands and performs the matching Android action. Returns a
 * short confirmation string when it handled the command, or null to let the
 * model answer instead.
 */
object Commands {

    fun handle(ctx: Context, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val n = norm(t)

        // --- Reminders / alarms ---
        if (listOf("ذكرني", "نبهني", "تذكير", "منبه", "remind").any { n.contains(norm(it)) }) {
            return setReminder(ctx, t)
        }

        // --- Open an app ---
        for (trig in listOf("افتح", "شغل", "ادخل", "open", "launch")) {
            if (n.startsWith(norm(trig))) {
                val rest = n.removePrefix(norm(trig)).trim()
                return openApp(ctx, rest)
            }
        }

        // --- Maps / navigation ---
        if (listOf("خرائط", "الخريطه", "وجهني", "الطريق", "موقع", "navigate", "maps", "directions")
                .any { n.contains(norm(it)) }) {
            val place = stripWords(
                t,
                listOf("خرائط", "جوجل", "قوقل", "الخريطة", "وجهني", "الى", "إلى", "الطريق",
                    "موقع", "المكان", "افتح", "navigate", "to", "maps", "directions")
            )
            return openMaps(ctx, place.ifBlank { t })
        }

        // --- Call / dial ---
        if (listOf("اتصل", "اتصلي", "كلم", "call", "dial").any { n.startsWith(norm(it)) }) {
            val number = t.filter { it.isDigit() }
            return dial(ctx, number)
        }

        // --- Web search ---
        for (trig in listOf("ابحث", "بحث", "جوجل", "قوقل", "google", "search")) {
            if (n.startsWith(norm(trig))) {
                val q = stripWords(t, listOf("ابحث", "بحث", "عن", "في", "جوجل", "قوقل", "google", "search", "for"))
                return webSearch(ctx, q.ifBlank { t })
            }
        }

        return null
    }

    // ---- Actions ----

    private fun openApp(ctx: Context, nameRaw: String): String {
        val name = nameRaw.removePrefix("ال").trim()

        // Special intents
        when {
            listOf("كاميرا", "الكاميرا", "camera").any { name.contains(norm(it)) } ->
                return launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "الكاميرا")
            listOf("اعدادات", "الاعدادات", "settings").any { name.contains(norm(it)) } ->
                return launch(ctx, Intent(Settings.ACTION_SETTINGS), "الإعدادات")
            listOf("الهاتف", "الاتصال", "المتصل", "phone", "dialer").any { name.contains(norm(it)) } ->
                return launch(ctx, Intent(Intent.ACTION_DIAL), "الهاتف")
        }

        for ((key, pkg) in APPS) {
            if (name.contains(norm(key)) || norm(key).contains(name)) {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                return if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "حسنًا، أفتح $key."
                } else {
                    "يبدو أن «$key» غير مثبّت على جهازك."
                }
            }
        }
        return "لم أتعرّف على التطبيق «$nameRaw». جرّبي اسمًا أوضح."
    }

    private fun openMaps(ctx: Context, place: String): String {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val maps = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (maps != null) intent.setPackage("com.google.android.apps.maps")
        return launch(ctx, intent, "الخريطة على «$place»", fallback = Intent(Intent.ACTION_VIEW, uri))
    }

    private fun dial(ctx: Context, number: String): String {
        val intent = if (number.isNotEmpty())
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        else Intent(Intent.ACTION_DIAL)
        return launch(ctx, intent, if (number.isNotEmpty()) "الاتصال بـ $number" else "لوحة الاتصال")
    }

    private fun setReminder(ctx: Context, text: String): String {
        val label = stripWords(
            text,
            listOf("ذكرني", "ذكّرني", "نبهني", "نبّهني", "تذكير", "منبه", "بأن", "بان", "ب", "remind", "me", "to")
        ).ifBlank { "تذكير" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launch(ctx, intent, "تذكير: «$label» — اضبطي الوقت من شاشة المنبّه.")
    }

    private fun webSearch(ctx: Context, query: String): String {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        return launch(ctx, web, "البحث عن «$query»")
    }

    private fun launch(ctx: Context, intent: Intent, label: String, fallback: Intent? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "حسنًا، أفتح $label."
        } catch (e: Exception) {
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(fallback) }
                    .map { "حسنًا، أفتح $label." }
                    .getOrDefault("تعذّر فتح $label.")
            } else "تعذّر فتح $label."
        }
    }

    // ---- Helpers ----

    private val APPS = mapOf(
        "واتساب" to "com.whatsapp",
        "whatsapp" to "com.whatsapp",
        "تيليجرام" to "org.telegram.messenger",
        "تلجرام" to "org.telegram.messenger",
        "انستقرام" to "com.instagram.android",
        "انستجرام" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "يوتيوب" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "سناب" to "com.snapchat.android",
        "snapchat" to "com.snapchat.android",
        "تيك توك" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "تويتر" to "com.twitter.android",
        "فيسبوك" to "com.facebook.katana",
        "فيس بوك" to "com.facebook.katana",
        "خرائط" to "com.google.android.apps.maps",
        "جيميل" to "com.google.android.gm",
        "البريد" to "com.google.android.gm",
        "كروم" to "com.android.chrome",
        "المتصفح" to "com.android.chrome",
        "قوقل" to "com.google.android.googlequicksearchbox"
    )

    private fun norm(s: String): String {
        var x = s.lowercase().trim()
        x = x.replace(Regex("[ً-ْـ]"), "")
        x = x.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        return x
    }

    private fun stripWords(text: String, words: List<String>): String {
        var x = " " + norm(text) + " "
        for (w in words) x = x.replace(" " + norm(w) + " ", " ")
        return x.trim()
    }
}
