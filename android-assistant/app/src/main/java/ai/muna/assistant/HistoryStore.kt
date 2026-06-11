package ai.muna.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Session(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<Message>
)

/** Persists past conversations as a JSON file in the app's private storage. */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")

    fun load(): MutableList<Session> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val out = ArrayList<Session>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val msgsArr = o.getJSONArray("messages")
                val msgs = ArrayList<Message>(msgsArr.length())
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    msgs.add(Message(m.getString("role"), m.getString("text")))
                }
                out.add(Session(o.getLong("id"), o.getString("title"), o.getLong("updatedAt"), msgs))
            }
            out.sortByDescending { it.updatedAt }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Insert or update a session by id, then persist. */
    fun save(id: Long, messages: List<Message>) {
        if (messages.isEmpty()) return
        val sessions = load()
        val title = messages.firstOrNull { it.role == "user" }?.text
            ?.replace("\n", " ")?.take(40)?.trim() ?: "محادثة"
        sessions.removeAll { it.id == id }
        sessions.add(0, Session(id, title, System.currentTimeMillis(), messages))
        write(sessions)
    }

    fun delete(id: Long) {
        val sessions = load()
        sessions.removeAll { it.id == id }
        write(sessions)
    }

    fun clearAll() = write(emptyList())

    private fun write(sessions: List<Session>) {
        val arr = JSONArray()
        for (s in sessions) {
            val msgs = JSONArray()
            for (m in s.messages) {
                msgs.put(JSONObject().put("role", m.role).put("text", m.text))
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", msgs)
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
