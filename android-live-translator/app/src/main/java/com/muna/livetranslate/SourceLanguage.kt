package com.muna.livetranslate

import com.google.mlkit.nl.translate.TranslateLanguage

/** Languages the app can listen to, with the offline speech model used for each. */
enum class SourceLanguage(val label: String, val voskModel: String, val mlKitCode: String) {
    ENGLISH("الإنجليزية", "vosk-model-small-en-us-0.15", TranslateLanguage.ENGLISH),
    FRENCH("الفرنسية", "vosk-model-small-fr-0.22", TranslateLanguage.FRENCH),
    SPANISH("الإسبانية", "vosk-model-small-es-0.42", TranslateLanguage.SPANISH),
    GERMAN("الألمانية", "vosk-model-small-de-0.15", TranslateLanguage.GERMAN),
    TURKISH("التركية", "vosk-model-small-tr-0.3", TranslateLanguage.TURKISH),
    RUSSIAN("الروسية", "vosk-model-small-ru-0.22", TranslateLanguage.RUSSIAN),
    ITALIAN("الإيطالية", "vosk-model-small-it-0.22", TranslateLanguage.ITALIAN),
    PORTUGUESE("البرتغالية", "vosk-model-small-pt-0.3", TranslateLanguage.PORTUGUESE),
    HINDI("الهندية", "vosk-model-small-hi-0.22", TranslateLanguage.HINDI),
    PERSIAN("الفارسية", "vosk-model-small-fa-0.42", TranslateLanguage.PERSIAN),
    JAPANESE("اليابانية", "vosk-model-small-ja-0.22", TranslateLanguage.JAPANESE),
    KOREAN("الكورية", "vosk-model-small-ko-0.22", TranslateLanguage.KOREAN),
    CHINESE("الصينية", "vosk-model-small-cn-0.22", TranslateLanguage.CHINESE);

    val modelUrl: String get() = "https://alphacephei.com/vosk/models/$voskModel.zip"
}
