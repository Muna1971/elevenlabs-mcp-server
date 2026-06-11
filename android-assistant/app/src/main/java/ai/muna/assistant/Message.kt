package ai.muna.assistant

/** A single chat turn. role is "user" or "assistant". */
data class Message(val role: String, val text: String)
