package com.jarvis.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutUserContainer: LinearLayout = view.findViewById(R.id.layoutUserContainer)
        val tvUserMessage: TextView = view.findViewById(R.id.tvUserMessage)
        val tvUserTime: TextView = view.findViewById(R.id.tvUserTime)

        val layoutJarvisContainer: LinearLayout = view.findViewById(R.id.layoutJarvisContainer)
        val tvJarvisMessage: TextView = view.findViewById(R.id.tvJarvisMessage)
        val tvJarvisTime: TextView = view.findViewById(R.id.tvJarvisTime)
        val btnCopyMessage: TextView = view.findViewById(R.id.btnCopyMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        val isUser = msg.role == "user"

        if (isUser) {
            holder.layoutUserContainer.visibility = View.VISIBLE
            holder.layoutJarvisContainer.visibility = View.GONE
            holder.tvUserMessage.text = msg.text
            holder.tvUserTime.text = msg.time
        } else {
            holder.layoutUserContainer.visibility = View.GONE
            holder.layoutJarvisContainer.visibility = View.VISIBLE
            holder.tvJarvisMessage.text = msg.text
            holder.tvJarvisTime.text = msg.time

            holder.btnCopyMessage.setOnClickListener {
                val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Jarvis Message", msg.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(it.context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int = messages.size
}
