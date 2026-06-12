package ai.muna.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    private val main = Handler(Looper.getMainLooper())

    /** Tool definitions sent to the Messages API. */
    fun toolsJson(): JSONArray {
        fun tool(name: String, desc: String, prop: String, propDesc: String): JSONObject {
            val props = JSONObject().put(prop, JSONObject().put("type", "string").put("description", propDesc))
            return JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("input_schema", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray().put(prop)))
        }
        return JSONArray()
            .put(tool("open_app", "افتح تطبيقًا على هاتف المستخدم", "name", "اسم التطبيق، مثل: واتساب، يوتيوب، انستقرام، الكاميرا، الإعدادات"))
            .put(tool("open_maps", "افتح خرائط جوجل على مكان أو عنوان محدّد", "place", "اسم المكان أو العنوان، مثل: دبي مول"))
            .put(tool("call", "أجرِ مكالمة هاتفية برقم أو باسم جهة اتصال", "target", "رقم الهاتف أو اسم جهة الاتصال، مثل: ماما"))
            .put(tool("send_whatsapp", "افتح واتساب مع رسالة جاهزة لاختيار جهة الاتصال", "message", "نص الرسالة"))
            .put(tool("set_reminder", "اضبط تذكيرًا أو منبّهًا", "text", "نص التذكير"))
            .put(tool("web_search", "ابحث في الإنترنت", "query", "كلمات البحث"))
    }

    /** Executes a tool call from the model. */
    fun exec(ctx: Context, name: String, input: JSONObject): String = when (name) {
        "open_app" -> openApp(ctx, input.optString("name"))
        "open_maps" -> openMaps(ctx, input.optString("place"))
        "call" -> call(ctx, input.optString("target"))
        "send_whatsapp" -> whatsapp(ctx, input.optString("message"))
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
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                start(ctx, intent)
                return "تم فتح $nameRaw."
            }
        }
        return "لم أتعرّف على التطبيق «$nameRaw»."
    }

    private fun openMaps(ctx: Context, place: String): String {
        if (place.isBlank()) return "إلى أي مكان تريدين الذهاب؟"
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") != null)
            intent.setPackage("com.google.android.apps.maps")
        return launch(ctx, intent, "الخريطة على «$place»", fallback = Intent(Intent.ACTION_VIEW, uri))
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

    private fun whatsapp(ctx: Context, message: String): String {
        if (ctx.packageManager.getLaunchIntentForPackage("com.whatsapp") == null)
            return "يبدو أن واتساب غير مثبّت."
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        start(ctx, intent)
        return "فتحت واتساب — اختاري جهة الاتصال ثم أرسلي."
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

    private fun launch(ctx: Context, intent: Intent, label: String, fallback: Intent? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(fallback) }
                }
            }
        }
        return "تم فتح $label."
    }

    /** Launch an already-resolved intent on the main thread. */
    private fun start(ctx: Context, intent: Intent) {
        main.post { runCatching { ctx.startActivity(intent) } }
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
