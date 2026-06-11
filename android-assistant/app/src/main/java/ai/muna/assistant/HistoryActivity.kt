package ai.muna.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.muna.assistant.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: HistoryStore
    private val items = mutableListOf<Session>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = HistoryStore(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        items.addAll(store.load())
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = Adapter()
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openSession(s: Session) {
        setResult(Activity.RESULT_OK, Intent().putExtra("session_id", s.id))
        finish()
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val fmt = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault())

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val delete: View = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.title.text = s.title
            holder.subtitle.text = "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} رسالة"
            holder.itemView.setOnClickListener { openSession(s) }
            holder.delete.setOnClickListener {
                store.delete(s.id)
                val idx = items.indexOf(s)
                if (idx >= 0) {
                    items.removeAt(idx)
                    notifyItemRemoved(idx)
                    binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
