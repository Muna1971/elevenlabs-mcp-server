package ai.muna.assistant

import android.service.voice.VoiceInteractionService

/**
 * Declares the app as a device assistant. The system binds to this service
 * when the user selects "منى الذكية" as the default digital assistant.
 * The actual UI is shown by [MunaSessionService] / [MunaVoiceInteractionSession].
 */
class MunaVoiceInteractionService : VoiceInteractionService()
