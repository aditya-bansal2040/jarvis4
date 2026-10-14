package com.jarvis.assistant

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvAppTitle: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var btnWipeMemory: ImageButton
    private lateinit var btnSettings: ImageButton

    private lateinit var rvChatMessages: RecyclerView
    private lateinit var tvTypingIndicator: TextView

    private lateinit var chipSuno: Button
    private lateinit var chipTired: Button
    private lateinit var chipStudy: Button
    private lateinit var chipMood: Button

    private lateinit var etUserInput: EditText
    private lateinit var btnSendText: ImageButton

    private lateinit var prefs: SharedPreferences
    private lateinit var geminiClient: GeminiClient
    private val gson = Gson()

    private val chatList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    private var isGenerating = false

    companion object {
        const val PREFS_NAME = "jarvis_chat_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_HISTORY = "chat_history_json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        geminiClient = GeminiClient()

        initViews()
        loadHistory()

        // Check if API key is configured
        val apiKey = prefs.getString(KEY_API, "")
        if (apiKey.isNullOrBlank()) {
            showSettingsDialog(isFirstTime = true)
        }
    }

    private fun initViews() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvActiveProfile = findViewById(R.id.tvActiveProfile)
        btnWipeMemory = findViewById(R.id.btnWipeMemory)
        btnSettings = findViewById(R.id.btnSettings)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        tvTypingIndicator = findViewById(R.id.tvTypingIndicator)

        chipSuno = findViewById(R.id.chipSuno)
        chipTired = findViewById(R.id.chipTired)
        chipStudy = findViewById(R.id.chipStudy)
        chipMood = findViewById(R.id.chipMood)

        etUserInput = findViewById(R.id.etUserInput)
        btnSendText = findViewById(R.id.btnSendText)

        // Setup RecyclerView
        chatAdapter = ChatAdapter(chatList)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager
        rvChatMessages.adapter = chatAdapter

        // Send Actions
        btnSendText.setOnClickListener { sendMessage() }
        etUserInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendMessage()
                true
            } else false
        }

        // Quick Prompt Chips
        chipSuno.setOnClickListener { sendQuickPrompt("Suno na, aaj ka din kaisa raha?") }
        chipTired.setOnClickListener { sendQuickPrompt("Main bohot tired hoon aaj") }
        chipStudy.setOnClickListener { sendQuickPrompt("Mujhe thoda motivate kardo Physics padhne ke liye") }
        chipMood.setOnClickListener { sendQuickPrompt("Tumhara mood kaisa hai aaj?") }

        // Wipe Memory
        btnWipeMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Wipe Memory")
                .setMessage("Start fresh and clear conversation memory?")
                .setPositiveButton("WIPE") { _, _ -> wipeChat() }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        // Settings
        btnSettings.setOnClickListener {
            showSettingsDialog(isFirstTime = false)
        }

        updateProfileHeader()
    }

    private fun updateProfileHeader() {
        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        tvActiveProfile.text = "${userName.uppercase(Locale.ROOT)} • FEMALE COMPANION"
    }

    private fun loadHistory() {
        chatList.clear()
        val json = prefs.getString(KEY_HISTORY, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val saved: List<ChatMessage> = gson.fromJson(json, type)
                chatList.addAll(saved)
            } catch (_: Exception) {}
        }

        if (chatList.isEmpty()) {
            val welcome = ChatMessage(
                role = "model",
                text = "Hey Adi! Main yahan hoon tumhare paas. Aaj ka din kaisa raha tumhara? Padhai kaisi chal rahi hai?",
                time = getCurrentTime()
            )
            chatList.add(welcome)
        }

        chatAdapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun saveHistory() {
        // Keep last 30 turns
        val toSave = if (chatList.size > 30) chatList.takeLast(30) else chatList
        val json = gson.toJson(toSave)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun scrollToBottom() {
        if (chatList.isNotEmpty()) {
            rvChatMessages.post {
                rvChatMessages.smoothScrollToPosition(chatList.size - 1)
            }
        }
    }

    private fun sendQuickPrompt(prompt: String) {
        if (isGenerating) return
        etUserInput.setText(prompt)
        sendMessage()
    }

    private fun sendMessage() {
        val text = etUserInput.text.toString().trim()
        if (text.isEmpty() || isGenerating) return

        val apiKey = prefs.getString(KEY_API, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API key in Settings first.", Toast.LENGTH_LONG).show()
            showSettingsDialog(isFirstTime = true)
            return
        }

        etUserInput.setText("")

        // Add user message to chat list
        val userMsg = ChatMessage(role = "user", text = text, time = getCurrentTime())
        chatList.add(userMsg)
        chatAdapter.notifyItemInserted(chatList.size - 1)
        scrollToBottom()
        saveHistory()

        // Show typing indicator
        isGenerating = true
        tvTypingIndicator.visibility = View.VISIBLE
        scrollToBottom()

        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val model = prefs.getString(KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL

        lifecycleScope.launch {
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = text,
                model = model,
                userName = userName,
                assistantName = assistantName,
                relationshipStyle = "girlfriend",
                history = chatList.takeLast(10)
            )

            isGenerating = false
            tvTypingIndicator.visibility = View.GONE

            if (result.isSuccess) {
                val replyText = result.getOrNull() ?: "All good Adi, main sun rahi hoon!"
                val jarvisMsg = ChatMessage(role = "model", text = replyText, time = getCurrentTime())
                chatList.add(jarvisMsg)
                chatAdapter.notifyItemInserted(chatList.size - 1)
                scrollToBottom()
                saveHistory()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Network error"
                val errReply = "Arre re Adi, connection issue aa gaya: $err. Ek baar dobara pucho na!"
                val jarvisMsg = ChatMessage(role = "model", text = errReply, time = getCurrentTime())
                chatList.add(jarvisMsg)
                chatAdapter.notifyItemInserted(chatList.size - 1)
                scrollToBottom()
            }
        }
    }

    private fun wipeChat() {
        chatList.clear()
        val resetMsg = ChatMessage(
            role = "model",
            text = "Memory wiped clean, Adi! Fresh start. Ab batao, kya chal raha hai? 😊",
            time = getCurrentTime()
        )
        chatList.add(resetMsg)
        chatAdapter.notifyDataSetChanged()
        saveHistory()
        Toast.makeText(this, "Memory wiped!", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog(isFirstTime: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etApi = EditText(this).apply {
            hint = "Free Gemini API Key (AIzaSy...)"
            setText(prefs.getString(KEY_API, ""))
        }

        val etUser = EditText(this).apply {
            hint = "Your Name (Aditya)"
            setText(prefs.getString(KEY_USER_NAME, "Aditya"))
        }

        val etAssistant = EditText(this).apply {
            hint = "Assistant Name (Jarvis)"
            setText(prefs.getString(KEY_ASSISTANT_NAME, "Jarvis"))
        }

        layout.addView(TextView(this).apply { text = "Google Gemini API Key:" })
        layout.addView(etApi)
        layout.addView(TextView(this).apply { text = "Your Name:" })
        layout.addView(etUser)
        layout.addView(TextView(this).apply { text = "Assistant Name:" })
        layout.addView(etAssistant)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Welcome to J.A.R.V.I.S." else "Settings")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = etApi.text.toString().trim()
                val newUser = etUser.text.toString().trim().ifEmpty { "Aditya" }
                val newAssistant = etAssistant.text.toString().trim().ifEmpty { "Jarvis" }

                prefs.edit()
                    .putString(KEY_API, newKey)
                    .putString(KEY_USER_NAME, newUser)
                    .putString(KEY_ASSISTANT_NAME, newAssistant)
                    .apply()

                updateProfileHeader()
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (isFirstTime) null else "CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }
}
