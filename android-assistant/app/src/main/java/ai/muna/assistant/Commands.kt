package ai.muna.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/**
 * On-device command interpreter. Recognizes Arabic/English commands phrased
 * naturally ("افتح واتساب", "هل ممكن تفتحي لي خرائط", "ذكّريني بـ…") and runs
 * the matching Android action. Returns a confirmation string when handled, or
 * null to let the model answer.
 */
object Commands {

    fun handle(ctx: Context, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val n = norm(t)

        // Reminders / alarms
        if (hasAny(n, "ذكرني", "ذكريني", "نبهني", "تذكير", "منبه", "remind")) {
            return setReminder(ctx, t)
        }

        val openTrigger = hasAny(n, "افتح", "افتحي", "تفتح", "تفتحي", "شغل", "شغلي", "ادخل", "open", "launch")
        val mapsWord = hasAny(n, "خرائط", "خريطه", "الخريطه", "ماب", "maps", "وجهني", "navigate")

        // Maps / navigation (explicit, or "open maps")
        if (mapsWord || (openTrigger && hasAny(n, "موقع", "المكان"))) {
            return openMaps(ctx, extractPlace(t))
        }

        // Open an app
        if (openTrigger) {
            // Special targets
            when {
                hasAny(n, "كاميرا") -> return launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "الكاميرا")
                hasAny(n, "اعدادات") -> return launch(ctx, Intent(Settings.ACTION_SETTINGS), "الإعدادات")
                hasAny(n, "الهاتف", "المتصل", "phone", "dialer") -> return launch(ctx, Intent(Intent.ACTION_DIAL), "الهاتف")
            }
            val app = findApp(ctx, n)
            if (app != null) {
                app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(app)
                return "حسنًا، أفتح التطبيق."
            }
            // Couldn't match an app — let the model respond instead.
            return null
        }

        // Call / dial (number only — name lookup needs contacts access)
        if (hasAny(n, "اتصل", "اتصلي", "dial")) {
            val number = t.filter { it.isDigit() }
            if (number.length >= 3) return dial(ctx, number)
            return null
        }

        // Web search
        if (startsAny(n, "ابحث", "بحث", "جوجل", "قوقل", "google", "search")) {
            val q = stripWords(t, listOf("ابحث", "ابحثي", "بحث", "عن", "في", "جوجل", "قوقل", "google", "search", "for", "من", "فضلك"))
            if (q.isNotBlank()) return webSearch(ctx, q)
        }

        return null
    }

    // ---- Actions ----

    private fun findApp(ctx: Context, n: String): Intent? {
        for ((key, pkg) in APPS) {
            if (n.contains(norm(key))) {
                return ctx.packageManager.getLaunchIntentForPackage(pkg)
            }
        }
        return null
    }

    private fun openMaps(ctx: Context, place: String): String {
        if (place.isBlank()) {
            val maps = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            return if (maps != null) {
                maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(maps); "حسنًا، أفتح خرائط جوجل."
            } else launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")), "الخرائط")
        }
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") != null)
            intent.setPackage("com.google.android.apps.maps")
        return launch(ctx, intent, "الخريطة على «$place»",
            fallback = Intent(Intent.ACTION_VIEW, uri))
    }

    private fun dial(ctx: Context, number: String): String =
        launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "الاتصال بـ $number")

    private fun setReminder(ctx: Context, text: String): String {
        val label = stripWords(
            text,
            listOf("ذكرني", "ذكّرني", "ذكريني", "نبهني", "نبّهني", "تذكير", "منبه",
                "بأن", "بان", "ب", "في", "remind", "me", "to", "من", "فضلك")
        ).ifBlank { "تذكير" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launch(ctx, intent, "تذكير: «$label» — اضبطي الوقت من شاشة المنبّه.")
    }

    private fun webSearch(ctx: Context, query: String): String =
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))),
            "البحث عن «$query»")

    private fun launch(ctx: Context, intent: Intent, label: String, fallback: Intent? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent); "حسنًا، أفتح $label."
        } catch (e: Exception) {
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(fallback) }
                    .map { "حسنًا، أفتح $label." }.getOrDefault("تعذّر فتح $label.")
            } else "تعذّر فتح $label."
        }
    }

    // ---- Helpers ----

    private val APPS = mapOf(
        "واتساب" to "com.whatsapp", "واتس اب" to "com.whatsapp", "whatsapp" to "com.whatsapp",
        "تيليجرام" to "org.telegram.messenger", "تلجرام" to "org.telegram.messenger", "telegram" to "org.telegram.messenger",
        "انستقرام" to "com.instagram.android", "انستجرام" to "com.instagram.android", "انستا" to "com.instagram.android", "instagram" to "com.instagram.android",
        "يوتيوب" to "com.google.android.youtube", "youtube" to "com.google.android.youtube",
        "سناب" to "com.snapchat.android", "snapchat" to "com.snapchat.android",
        "تيك توك" to "com.zhiliaoapp.musically", "تيكتوك" to "com.zhiliaoapp.musically", "tiktok" to "com.zhiliaoapp.musically",
        "تويتر" to "com.twitter.android", "اكس" to "com.twitter.android",
        "فيسبوك" to "com.facebook.katana", "فيس بوك" to "com.facebook.katana", "facebook" to "com.facebook.katana",
        "جيميل" to "com.google.android.gm", "البريد" to "com.google.android.gm", "ايميل" to "com.google.android.gm", "gmail" to "com.google.android.gm",
        "كروم" to "com.android.chrome", "المتصفح" to "com.android.chrome", "chrome" to "com.android.chrome",
        "قوقل" to "com.google.android.googlequicksearchbox", "جوجل" to "com.google.android.googlequicksearchbox"
    )

    private fun hasAny(n: String, vararg words: String) = words.any { n.contains(norm(it)) }
    private fun startsAny(n: String, vararg words: String) = words.any { n.startsWith(norm(it)) }

    private fun extractPlace(text: String): String =
        stripWords(
            text,
            listOf("هل", "ممكن", "لو", "سمحت", "من", "فضلك", "افتح", "افتحي", "تفتح", "تفتحي",
                "لي", "خرائط", "خريطه", "الخريطه", "جوجل", "قوقل", "ماب", "maps", "google",
                "وجهني", "وجهيني", "الى", "إلى", "على", "موقع", "المكان", "الطريق", "navigate", "to")
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
        return x.replace(Regex("\\s+"), " ").trim()
    }
}
