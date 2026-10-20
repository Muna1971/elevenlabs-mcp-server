package com.muna.livetranslate

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var language: SourceLanguage
        get() = SourceLanguage.entries.firstOrNull { it.name == sp.getString(KEY_LANGUAGE, null) }
            ?: SourceLanguage.AUTO
        set(value) = sp.edit().putString(KEY_LANGUAGE, value.name).apply()

    /** Subtitle text size in sp. */
    var textSize: Int
        get() = sp.getInt(KEY_TEXT_SIZE, 20)
        set(value) = sp.edit().putInt(KEY_TEXT_SIZE, value).apply()

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TEXT_SIZE = "text_size"
        const val MIN_TEXT_SIZE = 14
    }
}
