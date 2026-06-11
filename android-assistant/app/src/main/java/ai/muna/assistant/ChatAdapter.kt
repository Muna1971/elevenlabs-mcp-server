package ai.muna.assistant

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val items: MutableList<Message>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.row)
        val bubble: TextView = view.findViewById(R.id.bubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context
        holder.bubble.text = m.text
        if (m.role == "user") {
            holder.row.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bubble_user)
            holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_user_text))
        } else {
            holder.row.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bubble_bot)
            holder.bubble.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_bot_text))
        }
    }

    fun add(message: Message): Int {
        items.add(message)
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun updateLast(text: String) {
        if (items.isEmpty()) return
        items[items.size - 1] = items.last().copy(text = text)
        notifyItemChanged(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }
}
