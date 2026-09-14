package com.nousresearch.hermes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.hermes.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val messages = mutableListOf<Message>()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = messageAdapter
        binding.sendButton.setOnClickListener { sendCurrentMessage() }
    }

    /** Public entry point for the Agent layer to append a conversation message. */
    fun addMessage(role: String, text: String) {
        messages += Message(role, text)
        messageAdapter.notifyItemInserted(messages.lastIndex)
        binding.messageList.scrollToPosition(messages.lastIndex)
    }

    private fun sendCurrentMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) return
        addMessage("user", text)
        binding.messageInput.text?.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.messageInput.windowToken, 0)
    }

    private data class Message(val role: String, val text: String)

    private class MessageAdapter(private val items: List<Message>) :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = items[position]
            holder.title.text = message.role.replaceFirstChar { it.uppercase() }
            holder.body.text = message.text
        }

        override fun getItemCount(): Int = items.size

        private class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title = view.findViewById<android.widget.TextView>(android.R.id.text1)
            val body = view.findViewById<android.widget.TextView>(android.R.id.text2)
        }
    }
}
