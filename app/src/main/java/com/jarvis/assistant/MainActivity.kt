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
    private var isContinuousVoiceMode = false
    private var isListening = false
    private var isSpeaking = false
    private var isGenerating = false

    companion object {
        const val PREFS_NAME = "jarvis_chat_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_VOICE_MODEL = "voice_model_id"
        const val KEY_HISTORY = "chat_history_json"

        private const val REQUEST_RECORD_AUDIO = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        geminiClient = GeminiClient()

        val savedVoiceModel = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.MODEL_WARM_GIRLFRIEND)
            ?: SpeechEngine.MODEL_WARM_GIRLFRIEND
        speechEngine = SpeechEngine(this) {
            speechEngine.applyVoiceModel(savedVoiceModel)
        }

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
        btnSendText = findViewById(R.id.btnSendText)

        // Setup Chat RecyclerView
        chatAdapter = ChatAdapter(chatList)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager
        rvChatMessages.adapter = chatAdapter

        // Continuous Voice Controls
        btnVoiceToggle.setOnClickListener {
            checkAudioPermissionAndToggle()
        }

        btnVoiceStop.setOnClickListener {
            handleBargeIn()
        }

        hologramView.setOnClickListener {
            if (isSpeaking) {
                handleBargeIn()
            } else {
                checkAudioPermissionAndToggle()
            }
        }

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
        resetVoiceHudToIdle()
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                hologramView.setState(HologramView.State.LISTENING)
                tvVoiceStatus.text = "🟢 LISTENING... Bolo Adi 👂"
                tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_ready))
                tvHearingPreview.text = "Listening... Speak in Hinglish or English"
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

            override fun onError(error: Int) {
                isListening = false
                handleSpeechError(error)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim() ?: ""
                if (spoken.isNotBlank()) {
                    processVoiceInput(spoken)
                } else {
                    restartListeningIfContinuous()
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

    private fun handleSpeechError(error: Int) {
        if (!isContinuousVoiceMode || isSpeaking || isGenerating) {
            resetVoiceHudToIdle()
            return
        }

        // Error 6: Speech timeout, Error 7: No match (user paused or silence)
        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
            tvHearingPreview.text = "Waiting for you to speak..."
            mainHandler.postDelayed({
                if (isContinuousVoiceMode && !isSpeaking && !isGenerating && !isListening) {
                    startListening()
                }
            }, 400)
        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            mainHandler.postDelayed({
                if (isContinuousVoiceMode && !isSpeaking && !isGenerating) {
                    initSpeechRecognizer()
                    startListening()
                }
            }, 600)
        } else {
            mainHandler.postDelayed({
                if (isContinuousVoiceMode && !isSpeaking && !isGenerating && !isListening) {
                    startListening()
                }
            }, 800)
        }
    }

    private fun checkAudioPermissionAndToggle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } else {
            toggleContinuousVoice()
        }
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
                startContinuousVoice()
            } else {
                Toast.makeText(this, "Microphone permission is required for voice talking.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleContinuousVoice() {
        if (isContinuousVoiceMode) {
            stopContinuousVoice()
        } else {
            startContinuousVoice()
        }
    }

    private fun startContinuousVoice() {
        isContinuousVoiceMode = true
        btnVoiceToggle.text = "🔴 STOP TALK"
        btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_stop)
        btnVoiceToggle.setTextColor(ContextCompat.getColor(this, R.color.red_stop))
        btnVoiceStop.visibility = View.VISIBLE

        tvVoiceStatus.text = "🟢 CONNECTING TO VOICE LOOP..."
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.green_ready))
        startListening()
    }

    private fun stopContinuousVoice() {
        isContinuousVoiceMode = false
        speechEngine.stop()
        safeStopListening()

        btnVoiceToggle.text = "🎙️ TALK"
        btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_talk)
        btnVoiceToggle.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
        btnVoiceStop.visibility = View.GONE

        resetVoiceHudToIdle()
    }

    private fun startListening() {
        if (!isContinuousVoiceMode || isSpeaking || isGenerating) return
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

    private fun restartListeningIfContinuous() {
        if (isContinuousVoiceMode && !isSpeaking && !isGenerating) {
            mainHandler.postDelayed({
                if (isContinuousVoiceMode && !isSpeaking && !isGenerating && !isListening) {
                    startListening()
                }
            }, 350)
        } else {
            resetVoiceHudToIdle()
        }
    }

    /**
     * Instant Barge-In:
     * Interrupts Jarvis when she is speaking and immediately opens mic for user
     */
    private fun handleBargeIn() {
        if (isSpeaking) {
            speechEngine.stop()
            isSpeaking = false
            btnVoiceStop.visibility = View.GONE
            if (isContinuousVoiceMode) {
                tvVoiceStatus.text = "Interrupted. Listening to you now..."
                startListening()
            } else {
                resetVoiceHudToIdle()
            }
        } else if (isContinuousVoiceMode) {
            stopContinuousVoice()
        }
    }

    private fun resetVoiceHudToIdle() {
        if (!isContinuousVoiceMode) {
            hologramView.setState(HologramView.State.IDLE)
            tvVoiceStatus.text = "⚪ IDLE • Tap TALK to chat hands-free"
            tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_glow))
            tvHearingPreview.text = "Continuous Voice Loop Ready"
            btnVoiceStop.visibility = View.GONE
        }
    }

    private fun processVoiceInput(spokenText: String) {
        val lower = spokenText.lowercase(Locale.ROOT)
        // Check for emergency voice stop commands
        if (lower == "stop" || lower == "ruko" || lower == "chup" || lower == "pause" || lower == "cancel" || lower == "band karo") {
            stopContinuousVoice()
            val stopReply = "Theek hai Adi, main ruk gayi! Jab bhi baat karni ho, tap kar dena."
            addJarvisMessage(stopReply)
            speechEngine.speak(stopReply)
            return
        }

        tvHearingPreview.text = "\"$spokenText\""
        sendQuery(spokenText, isFromVoice = true)
    }

    private fun updateProfileHeader() {
        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        val voiceModelId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.MODEL_WARM_GIRLFRIEND)
        val voiceInfo = SpeechEngine.AVAILABLE_MODELS.firstOrNull { it.id == voiceModelId }
        val voiceName = voiceInfo?.displayName?.take(18) ?: "WARM COMPANION"
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

        lifecycleScope.launch {
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

                // Vocalize response
                speakJarvisResponse(replyText, continueLoopAfter = isContinuousVoiceMode || isFromVoice)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Network error"
                val errReply = "Arre re Adi, issue aa gaya: $err. Ek baar dobara pucho na!"
                addJarvisMessage(errReply)
                speakJarvisResponse(errReply, continueLoopAfter = isContinuousVoiceMode)
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

    private fun speakJarvisResponse(text: String, continueLoopAfter: Boolean) {
        isSpeaking = true
        hologramView.setState(HologramView.State.SPEAKING)
        tvVoiceStatus.text = "🟣 SPEAKING... Jarvis speaking 🗣️"
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_glow))
        btnVoiceStop.visibility = View.VISIBLE

        speechEngine.speak(
            text = text,
            onStart = {
                runOnUiThread {
                    isSpeaking = true
                    hologramView.setState(HologramView.State.SPEAKING)
                    tvVoiceStatus.text = "🟣 SPEAKING... Jarvis speaking 🗣️"
                    btnVoiceStop.visibility = View.VISIBLE
                }
            },
            onDone = {
                runOnUiThread {
                    isSpeaking = false
                    btnVoiceStop.visibility = View.GONE

                    if (isContinuousVoiceMode && continueLoopAfter) {
                        // 450ms breath delay to avoid speaker reverberation loop into mic
                        mainHandler.postDelayed({
                            if (isContinuousVoiceMode && !isSpeaking && !isGenerating && !isListening) {
                                startListening()
                            }
                        }, 450)
                    } else {
                        resetVoiceHudToIdle()
                    }
                }
            }
        )
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

        // Voice Model Selector
        val spinnerVoice = Spinner(this)
        val voiceOptions = SpeechEngine.AVAILABLE_MODELS.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        spinnerVoice.adapter = spinnerAdapter

        val savedVoiceId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.MODEL_WARM_GIRLFRIEND)
        val currentIndex = SpeechEngine.AVAILABLE_MODELS.indexOfFirst { it.id == savedVoiceId }.coerceAtLeast(0)
        spinnerVoice.setSelection(currentIndex)

        val btnPreviewVoice = Button(this).apply {
            text = "🔊 TEST SELECTED VOICE"
            setOnClickListener {
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedModel = SpeechEngine.AVAILABLE_MODELS.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.MODEL_WARM_GIRLFRIEND
                speechEngine.previewVoice(selectedModel, "Hey Adi! Main yahan hoon tumhare paas. Kaisi lagi meri voice?")
            }
        }

        layout.addView(TextView(this).apply { text = "Google Gemini API Key:" })
        layout.addView(etApi)
        layout.addView(TextView(this).apply { text = "Your Name:" })
        layout.addView(etUser)
        layout.addView(TextView(this).apply { text = "Assistant Name:" })
        layout.addView(etAssistant)
        layout.addView(TextView(this).apply { text = "Voice Model (Girlfriend / Companion):" })
        layout.addView(spinnerVoice)
        layout.addView(btnPreviewVoice)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Welcome to J.A.R.V.I.S." else "Settings & Voice Models")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = etApi.text.toString().trim()
                val newUser = etUser.text.toString().trim().ifEmpty { "Aditya" }
                val newAssistant = etAssistant.text.toString().trim().ifEmpty { "Jarvis" }
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedVoice = SpeechEngine.AVAILABLE_MODELS.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.MODEL_WARM_GIRLFRIEND

                prefs.edit()
                    .putString(KEY_API, newKey)
                    .putString(KEY_USER_NAME, newUser)
                    .putString(KEY_ASSISTANT_NAME, newAssistant)
                    .putString(KEY_VOICE_MODEL, selectedVoice)
                    .apply()

                speechEngine.applyVoiceModel(selectedVoice)
                updateProfileHeader()
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (isFirstTime) null else "CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (isContinuousVoiceMode) {
            safeStopListening()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isContinuousVoiceMode && !isSpeaking && !isGenerating && !isListening) {
            startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousVoiceMode = false
        speechEngine.shutdown()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
