package com.muna.livetranslate

/** What the user says the video is in. AUTO lets Whisper detect it for every sentence. */
enum class SourceLanguage(val label: String, val code: String) {
    AUTO("تلقائي — اكتشاف اللغة (موصى به)", "auto"),
    ENGLISH("الإنجليزية", "en"),
    FRENCH("الفرنسية", "fr"),
    SPANISH("الإسبانية", "es"),
    GERMAN("الألمانية", "de"),
    TURKISH("التركية", "tr"),
    RUSSIAN("الروسية", "ru"),
    ITALIAN("الإيطالية", "it"),
    PORTUGUESE("البرتغالية", "pt"),
    HINDI("الهندية", "hi"),
    URDU("الأردية", "ur"),
    PERSIAN("الفارسية", "fa"),
    INDONESIAN("الإندونيسية", "id"),
    JAPANESE("اليابانية", "ja"),
    KOREAN("الكورية", "ko"),
    CHINESE("الصينية", "zh");
}
