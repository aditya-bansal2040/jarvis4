package com.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    // Top Header
    private lateinit var tvAppTitle: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var btnWipeMemory: ImageButton
    private lateinit var btnSettings: ImageButton

    // Voice HUD & Arc Reactor
    private lateinit var hologramView: HologramView
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvHearingPreview: TextView
    private lateinit var btnVoiceToggle: Button
    private lateinit var btnVoiceStop: ImageButton

    // Chat UI
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var tvTypingIndicator: TextView

    // Quick Chips
    private lateinit var chipSuno: Button
    private lateinit var chipTired: Button
    private lateinit var chipStudy: Button
    private lateinit var chipMood: Button

    // Bottom Input Bar
    private lateinit var etUserInput: EditText
    private lateinit var btnMicInput: ImageButton
    private lateinit var btnSendText: ImageButton

    // Core Systems
    private lateinit var prefs: SharedPreferences
    private lateinit var geminiClient: GeminiClient
    private lateinit var speechEngine: SpeechEngine
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private val gson = Gson()
    private val chatList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // State Variables
    private var isListening = false
    private var isSpeaking = false
    private var isGenerating = false
    private var isContinuousMode = false

    companion object {
        const val PREFS_NAME = "jarvis_chat_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_VOICE_MODEL = "gemini_computer_voice"
        const val KEY_CONTINUOUS_MODE = "continuous_voice_mode"
        const val KEY_HISTORY = "chat_history_json"

        private const val REQUEST_RECORD_AUDIO = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        geminiClient = GeminiClient()
        speechEngine = SpeechEngine(this)

        val savedVoice = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
            ?: SpeechEngine.DEFAULT_VOICE
        speechEngine.setVoiceModel(savedVoice)
        isContinuousMode = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)

        initViews()
        initSpeechRecognizer()
        loadHistory()

        // Prompt for API key if missing
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

        hologramView = findViewById(R.id.hologramView)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvHearingPreview = findViewById(R.id.tvHearingPreview)
        btnVoiceToggle = findViewById(R.id.btnVoiceToggle)
        btnVoiceStop = findViewById(R.id.btnVoiceStop)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        tvTypingIndicator = findViewById(R.id.tvTypingIndicator)

        chipSuno = findViewById(R.id.chipSuno)
        chipTired = findViewById(R.id.chipTired)
        chipStudy = findViewById(R.id.chipStudy)
        chipMood = findViewById(R.id.chipMood)

        etUserInput = findViewById(R.id.etUserInput)
        btnMicInput = findViewById(R.id.btnMicInput)
        btnSendText = findViewById(R.id.btnSendText)

        // Setup Chat RecyclerView
        chatAdapter = ChatAdapter(chatList)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager
        rvChatMessages.adapter = chatAdapter

        // Voice Controls
        btnVoiceToggle.setOnClickListener {
            handleMicButtonTapped()
        }

        btnMicInput.setOnClickListener {
            handleMicButtonTapped()
        }

        btnVoiceStop.setOnClickListener {
            handleBargeIn()
        }

        hologramView.setOnClickListener {
            if (isSpeaking) {
                handleBargeIn()
            } else {
                handleMicButtonTapped()
            }
        }

        // Send Text Actions
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
        resetVoiceHudToIdle("Tap TALK or mic to speak in Hindi/English")
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvHearingPreview.text = "Speech recognition unavailable on device"
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Generous silence windows to prevent rapid cut-offs
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                hologramView.setState(HologramView.State.LISTENING)
                tvVoiceStatus.text = "🟢 LISTENING... Bolo Adi 👂"
                tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_ready))
                tvHearingPreview.text = "Listening... Speak in Hindi, Hinglish or English"
                btnVoiceToggle.text = "🔴 STOP"
                btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_stop)
                btnVoiceToggle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red_stop))
                btnVoiceStop.visibility = View.VISIBLE
            }

            override fun onBeginningOfSpeech() {
                tvVoiceStatus.text = "🟢 LISTENING... Sun rahi hoon..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                hologramView.setState(HologramView.State.THINKING)
                tvVoiceStatus.text = "🟡 THINKING... Jarvis replying 🤔"
                tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gold_glow))
            }

            // CRITICAL FIX: NEVER loop on error or silence timeouts! Stop calmly.
            override fun onError(error: Int) {
                isListening = false
                resetVoiceHudToIdle("Mic closed. Tap TALK to speak again.")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim() ?: ""
                if (spoken.isNotBlank()) {
                    processVoiceInput(spoken)
                } else {
                    resetVoiceHudToIdle("No speech caught. Tap TALK to try again.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = partialMatches?.firstOrNull()?.trim()
                if (!partialText.isNullOrBlank()) {
                    tvHearingPreview.text = "Hearing: \"$partialText\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleMicButtonTapped() {
        if (isSpeaking) {
            handleBargeIn()
            return
        }

        if (isListening) {
            safeStopListening()
            resetVoiceHudToIdle("Cancelled. Tap TALK to speak.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initSpeechRecognizer()
                startListening()
            } else {
                Toast.makeText(this, "Microphone permission is required to talk.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        if (isSpeaking || isGenerating) return
        safeStopListening()
        mainHandler.post {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                initSpeechRecognizer()
                try {
                    speechRecognizer?.startListening(speechIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun safeStopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
    }

    private fun handleBargeIn() {
        speechEngine.stop()
        isSpeaking = false
        btnVoiceStop.visibility = View.GONE
        resetVoiceHudToIdle("Speech interrupted.")
    }

    private fun resetVoiceHudToIdle(statusMsg: String? = null) {
        hologramView.setState(HologramView.State.IDLE)
        tvVoiceStatus.text = "⚪ IDLE • Tap TALK to chat"
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_glow))
        tvHearingPreview.text = statusMsg ?: "Ready • Speaks authentic Hindi & Hinglish"
        btnVoiceToggle.text = "🎙️ TALK"
        btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_talk)
        btnVoiceToggle.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
        btnVoiceStop.visibility = View.GONE
    }

    private fun processVoiceInput(spokenText: String) {
        val lower = spokenText.lowercase(Locale.ROOT)
        if (lower == "stop" || lower == "ruko" || lower == "chup" || lower == "cancel") {
            handleBargeIn()
            return
        }

        tvHearingPreview.text = "\"$spokenText\""
        sendQuery(spokenText, isFromVoice = true)
    }

    private fun updateProfileHeader() {
        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        val voiceId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
        val voiceInfo = SpeechEngine.COMPUTER_VOICES.firstOrNull { it.id == voiceId }
        val voiceName = voiceInfo?.displayName?.take(16) ?: "AOEDE"
        tvActiveProfile.text = "${userName.uppercase(Locale.ROOT)} • $voiceName"
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
        etUserInput.setText("")
        sendQuery(text, isFromVoice = false)
    }

    private fun sendQuery(queryText: String, isFromVoice: Boolean) {
        val apiKey = prefs.getString(KEY_API, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API key in Settings first.", Toast.LENGTH_LONG).show()
            showSettingsDialog(isFirstTime = true)
            return
        }

        // Add user message to UI
        val userMsg = ChatMessage(role = "user", text = queryText, time = getCurrentTime())
        chatList.add(userMsg)
        chatAdapter.notifyItemInserted(chatList.size - 1)
        scrollToBottom()
        saveHistory()

        // State update
        isGenerating = true
        tvTypingIndicator.visibility = View.VISIBLE
        hologramView.setState(HologramView.State.THINKING)
        tvVoiceStatus.text = "🟡 THINKING... Jarvis replying 🤔"
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.gold_glow))
        scrollToBottom()

        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val model = prefs.getString(KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        val selectedVoice = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE) ?: SpeechEngine.DEFAULT_VOICE

        lifecycleScope.launch {
            // Step 1: Sub-second fast text generation
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = queryText,
                model = model,
                userName = userName,
                assistantName = assistantName,
                relationshipStyle = "girlfriend",
                history = chatList.takeLast(10)
            )

            isGenerating = false
            tvTypingIndicator.visibility = View.GONE

            if (result.isSuccess) {
                val replyText = result.getOrNull() ?: "Main sun rahi hoon Adi, batao na!"
                addJarvisMessage(replyText)

                // Step 2: Speak with Gemini Computer Voice Model
                speakWithComputerVoice(apiKey, replyText, selectedVoice, isFromVoice)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Network error"
                val errReply = "Arre re Adi, issue aa gaya: $err. Ek baar dobara pucho na!"
                addJarvisMessage(errReply)
                resetVoiceHudToIdle("Error: $err")
            }
        }
    }

    private fun addJarvisMessage(text: String) {
        val jarvisMsg = ChatMessage(role = "model", text = text, time = getCurrentTime())
        chatList.add(jarvisMsg)
        chatAdapter.notifyItemInserted(chatList.size - 1)
        scrollToBottom()
        saveHistory()
    }

    /**
     * Synthesizes and plays Gemini Neural Computer Voice Audio (Aoede, Kore, Charon, Puck, Fenrir)
     * Speaks natural Hindi and Hinglish with authentic human emotion
     */
    private fun speakWithComputerVoice(apiKey: String, text: String, voiceName: String, isFromVoice: Boolean) {
        tvVoiceStatus.text = "🟣 SYNTHESIZING VOICE ($voiceName)..."
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.gold_glow))
        hologramView.setState(HologramView.State.THINKING)

        lifecycleScope.launch {
            val audioResult = geminiClient.generateSpeech(apiKey, text, voiceName)

            if (audioResult.isSuccess) {
                val wavBytes = audioResult.getOrNull()!!
                speechEngine.playNeuralAudio(
                    wavBytes = wavBytes,
                    onStart = {
                        runOnUiThread {
                            isSpeaking = true
                            hologramView.setState(HologramView.State.SPEAKING)
                            tvVoiceStatus.text = "🟣 $voiceName SPEAKING 🗣️"
                            tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan_glow))
                            btnVoiceStop.visibility = View.VISIBLE
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            isSpeaking = false
                            btnVoiceStop.visibility = View.GONE

                            if (isContinuousMode && isFromVoice) {
                                // In Continuous mode: prompt once, but NEVER loop if silent!
                                tvHearingPreview.text = "Listening... Speak when ready"
                                mainHandler.postDelayed({
                                    if (isContinuousMode && !isSpeaking && !isGenerating && !isListening) {
                                        startListening()
                                    }
                                }, 400)
                            } else {
                                resetVoiceHudToIdle("Tap TALK to continue conversation")
                            }
                        }
                    }
                )
            } else {
                // Network speech fallback to offline TTS if needed
                speechEngine.speakFallbackTts(
                    text = text,
                    onStart = {
                        runOnUiThread {
                            isSpeaking = true
                            hologramView.setState(HologramView.State.SPEAKING)
                            tvVoiceStatus.text = "🟣 SPEAKING..."
                            btnVoiceStop.visibility = View.VISIBLE
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            isSpeaking = false
                            btnVoiceStop.visibility = View.GONE
                            resetVoiceHudToIdle("Tap TALK to speak")
                        }
                    }
                )
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

        // Computer Voice Model Selector
        val spinnerVoice = Spinner(this)
        val voiceOptions = SpeechEngine.COMPUTER_VOICES.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        spinnerVoice.adapter = spinnerAdapter

        val savedVoiceId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
        val currentIndex = SpeechEngine.COMPUTER_VOICES.indexOfFirst { it.id == savedVoiceId }.coerceAtLeast(0)
        spinnerVoice.setSelection(currentIndex)

        val cbContinuous = CheckBox(this).apply {
            text = "Continuous Voice Loop (Auto-relisten after reply)"
            isChecked = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        }

        val btnPreviewVoice = Button(this).apply {
            text = "🔊 TEST COMPUTER VOICE"
            setOnClickListener {
                val currentKey = etApi.text.toString().trim()
                if (currentKey.isBlank()) {
                    Toast.makeText(this@MainActivity, "Please enter API Key above first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedVoice = SpeechEngine.COMPUTER_VOICES.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.DEFAULT_VOICE

                Toast.makeText(this@MainActivity, "Synthesizing $selectedVoice in Hindi...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val testAudio = geminiClient.generateSpeech(
                        apiKey = currentKey,
                        text = "Hey Adi! Main yahan hoon tumhare paas. Kaisi lagi meri voice?",
                        voiceName = selectedVoice
                    )
                    if (testAudio.isSuccess) {
                        speechEngine.playNeuralAudio(testAudio.getOrNull()!!)
                    } else {
                        Toast.makeText(this@MainActivity, "TTS error: ${testAudio.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        layout.addView(TextView(this).apply { text = "Google Gemini API Key:" })
        layout.addView(etApi)
        layout.addView(TextView(this).apply { text = "Your Name:" })
        layout.addView(etUser)
        layout.addView(TextView(this).apply { text = "Assistant Name:" })
        layout.addView(etAssistant)
        layout.addView(TextView(this).apply { text = "Gemini Neural Computer Voice Model:" })
        layout.addView(spinnerVoice)
        layout.addView(btnPreviewVoice)
        layout.addView(cbContinuous)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Welcome to J.A.R.V.I.S." else "Settings & Computer Voice Models")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = etApi.text.toString().trim()
                val newUser = etUser.text.toString().trim().ifEmpty { "Aditya" }
                val newAssistant = etAssistant.text.toString().trim().ifEmpty { "Jarvis" }
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedVoice = SpeechEngine.COMPUTER_VOICES.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.DEFAULT_VOICE
                val newContinuous = cbContinuous.isChecked

                prefs.edit()
                    .putString(KEY_API, newKey)
                    .putString(KEY_USER_NAME, newUser)
                    .putString(KEY_ASSISTANT_NAME, newAssistant)
                    .putString(KEY_VOICE_MODEL, selectedVoice)
                    .putBoolean(KEY_CONTINUOUS_MODE, newContinuous)
                    .apply()

                speechEngine.setVoiceModel(selectedVoice)
                isContinuousMode = newContinuous
                updateProfileHeader()
                Toast.makeText(this, "Voice Model saved: $selectedVoice", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (isFirstTime) null else "CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }

    override fun onPause() {
        super.onPause()
        safeStopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine.shutdown()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
