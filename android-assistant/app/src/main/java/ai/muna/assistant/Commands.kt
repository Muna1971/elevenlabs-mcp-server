package ai.muna.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device control exposed to the model as tools. Claude decides which tool to
 * call and extracts the parameters; [exec] performs the real Android action.
 */
object Commands {

    /** Tool definitions sent to the Messages API. */
    fun toolsJson(): JSONArray {
        fun tool(name: String, desc: String, props: Map<String, String>, required: List<String>): JSONObject {
            val p = JSONObject()
            for ((k, v) in props) p.put(k, JSONObject().put("type", "string").put("description", v))
            return JSONObject().put("name", name).put("description", desc).put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", p)
                    .put("required", JSONArray().apply { required.forEach { put(it) } })
            )
        }
        return JSONArray()
            .put(tool("open_app", "افتح تطبيقًا على هاتف المستخدم (دون بحث بالداخل)",
                mapOf("name" to "اسم التطبيق مثل: واتساب، انستقرام، الكاميرا، الإعدادات"), listOf("name")))
            .put(tool("youtube", "افتح يوتيوب وابحث عن فيديو",
                mapOf("query" to "ما تريد البحث عنه، مثل: أذكار الصباح"), listOf("query")))
            .put(tool("play_music", "شغّل أغنية أو مقطعًا صوتيًا — يبدأ التشغيل تلقائيًا",
                mapOf("query" to "اسم الأغنية أو الفنان"), listOf("query")))
            .put(tool("open_maps", "اعرض مكانًا على خرائط جوجل (دون بدء الملاحة)",
                mapOf("place" to "اسم المكان أو العنوان، مثل: دبي مول"), listOf("place")))
            .put(tool("navigate", "ابدأ الملاحة والاتجاهات (التوجيه الصوتي) إلى مكان",
                mapOf("place" to "الوجهة، مثل: مطار دبي"), listOf("place")))
            .put(tool("call", "أجرِ مكالمة هاتفية برقم أو باسم جهة اتصال",
                mapOf("target" to "رقم الهاتف أو اسم جهة الاتصال، مثل: ماما"), listOf("target")))
            .put(tool("whatsapp", "افتح محادثة واتساب مع جهة اتصال، مع رسالة جاهزة اختيارية",
                mapOf("contact" to "اسم جهة الاتصال (اختياري)", "message" to "نص الرسالة (اختياري)"), listOf()))
            .put(tool("set_reminder", "اضبط تذكيرًا أو منبّهًا",
                mapOf("text" to "نص التذكير"), listOf("text")))
            .put(tool("web_search", "ابحث في الإنترنت",
                mapOf("query" to "كلمات البحث"), listOf("query")))
    }

    /** Executes a tool call from the model. */
    fun exec(ctx: Context, name: String, input: JSONObject): String = when (name) {
        "open_app" -> openApp(ctx, input.optString("name"))
        "youtube" -> youtube(ctx, input.optString("query"))
        "play_music" -> playMusic(ctx, input.optString("query"))
        "open_maps" -> openMaps(ctx, input.optString("place"))
        "navigate" -> navigate(ctx, input.optString("place"))
        "call" -> call(ctx, input.optString("target"))
        "whatsapp" -> whatsapp(ctx, input.optString("contact"), input.optString("message"))
        "set_reminder" -> setReminder(ctx, input.optString("text"))
        "web_search" -> webSearch(ctx, input.optString("query"))
        else -> "إجراء غير معروف."
    }

    // ---- Actions ----

    private fun openApp(ctx: Context, nameRaw: String): String {
        val name = norm(nameRaw)
        when {
            name.contains("كاميرا") -> return launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "الكاميرا")
            name.contains("اعدادات") -> return launch(ctx, Intent(Settings.ACTION_SETTINGS), "الإعدادات")
            name.contains("الهاتف") || name.contains("المتصل") -> return launch(ctx, Intent(Intent.ACTION_DIAL), "الهاتف")
        }
        for ((key, pkg) in APPS) {
            if (name.contains(norm(key))) {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return "يبدو أن «$nameRaw» غير مثبّت."
                val err = fire(ctx, intent)
                return if (err == null) "تم فتح $nameRaw." else "تعذّر فتح $nameRaw ($err)."
            }
        }
        return "لم أتعرّف على التطبيق «$nameRaw»."
    }

    private fun youtube(ctx: Context, query: String): String {
        if (query.isBlank()) return openApp(ctx, "يوتيوب")
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (ctx.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null)
            intent.setPackage("com.google.android.youtube")
        return launch(ctx, intent, "يوتيوب على «$query»", fallback = Intent(Intent.ACTION_VIEW, uri))
    }

    private fun playMusic(ctx: Context, query: String): String {
        if (query.isBlank()) return "أي أغنية تريدين تشغيلها؟"
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, query)
        }
        val err = fire(ctx, intent)
        if (err == null) return "أشغّل «$query» الآن."
        // Fallback: open YouTube search if no music app handled it.
        return youtube(ctx, query)
    }

    private fun openMaps(ctx: Context, place: String): String {
        if (place.isBlank()) return "أي مكان تريدين عرضه على الخريطة؟"
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(place))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") != null)
            intent.setPackage("com.google.android.apps.maps")
        return launch(ctx, intent, "الخريطة على «$place»", fallback = Intent(Intent.ACTION_VIEW, uri))
    }

    private fun navigate(ctx: Context, place: String): String {
        if (place.isBlank()) return "إلى أي وجهة تريدين أن أوجّهك؟"
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
        if (ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") != null)
            nav.setPackage("com.google.android.apps.maps")
        val webDir = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(place)))
        return launch(ctx, nav, "الاتجاهات إلى «$place»", fallback = webDir)
    }

    private fun call(ctx: Context, targetRaw: String): String {
        val target = targetRaw.trim()
        if (target.isBlank()) return "بمن تريدين الاتصال؟"
        val digits = target.filter { it.isDigit() }
        val number: String
        val who: String
        if (digits.length >= 3) {
            number = digits; who = digits
        } else {
            number = lookupContact(ctx, target) ?: return "لم أجد «$target» في جهات الاتصال."
            who = target
        }
        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return launch(ctx, Intent(action, Uri.parse("tel:$number")), "الاتصال بـ $who")
    }

    private fun whatsapp(ctx: Context, contact: String, message: String): String {
        if (ctx.packageManager.getLaunchIntentForPackage("com.whatsapp") == null)
            return "يبدو أن واتساب غير مثبّت."

        // If a contact is named and found, open that chat directly.
        if (contact.isNotBlank()) {
            val raw = lookupContact(ctx, contact)
            if (raw != null) {
                val intl = toIntl(raw)
                val url = "https://wa.me/$intl" + if (message.isNotBlank()) "?text=" + Uri.encode(message) else ""
                return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "محادثة واتساب مع $contact")
            }
        }
        // Otherwise fall back to the share sheet with the message prefilled.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val err = fire(ctx, intent)
        return if (err == null) "فتحت واتساب — اختاري جهة الاتصال ثم أرسلي." else "تعذّر فتح واتساب ($err)."
    }

    private fun setReminder(ctx: Context, text: String): String {
        val label = text.ifBlank { "تذكير" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launch(ctx, intent, "تذكير: «$label» — اضبطي الوقت.")
    }

    private fun webSearch(ctx: Context, query: String): String =
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))),
            "البحث عن «$query»")

    private fun lookupContact(ctx: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort international format for wa.me (defaults local numbers to UAE). */
    private fun toIntl(raw: String): String {
        var d = raw.filter { it.isDigit() || it == '+' }.replace("+", "")
        if (d.startsWith("00")) d = d.drop(2)
        if (d.startsWith("0")) d = "971" + d.drop(1)
        return d
    }

    private fun launch(ctx: Context, intent: Intent, label: String, fallback: Intent? = null): String {
        val err = fire(ctx, intent)
        if (err == null) return "تم فتح $label."
        if (fallback != null) {
            val e2 = fire(ctx, fallback)
            if (e2 == null) return "تم فتح $label."
            return "تعذّر فتح $label ($e2)."
        }
        return "تعذّر فتح $label ($err)."
    }

    /** Starts an activity; returns null on success, or the error message. */
    private fun fire(ctx: Context, intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent); null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

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

    private fun norm(s: String): String {
        var x = s.lowercase().trim()
        x = x.replace(Regex("[ً-ْـ]"), "")
        x = x.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        return x
    }
}
