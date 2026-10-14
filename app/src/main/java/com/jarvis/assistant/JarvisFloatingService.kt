package com.jarvis.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * JarvisFloatingService
 * Interactive floating Arc Reactor bubble over all apps (WhatsApp, YouTube, games, browser).
 * Features:
 * - Hands-free continuous duplex conversation loop
 * - Instant Barge-In / Force Stop via voice, overlay, & notification action
 * - Dual persona support (Aditya - Girlfriend / Aditi - Best Friend)
 * - Screen vision (MediaProjection buffer, 0 gallery clutter) & Camera vision
 * - Smart Clipboard Assistant (floating pill on copy)
 * - Ghost Mode & 1-tap memory wipe
 * - Native flashlight toggle
 */
class JarvisFloatingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var hologramView: HologramView? = null
    private var expandedMenu: LinearLayout? = null
    private var clipboardPill: LinearLayout? = null
    private var tvClipboardSnippet: TextView? = null
    private var btnFloatingLoop: ImageButton? = null
    private var btnFloatingGhost: ImageButton? = null

    private lateinit var speechEngine: SpeechEngine
    private lateinit var geminiClient: GeminiClient
    private var speechRecognizer: SpeechRecognizer? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipChangedListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private lateinit var prefs: SharedPreferences
    private var isListening = false
    private var isExpanded = false
    private var isContinuousLoop = false
    private var isGhostMode = false

    // Multi-turn conversational memory
    private val chatHistory = mutableListOf<ChatMessage>()
    private var lastCopiedText: String = ""

    companion object {
        const val CHANNEL_ID = "jarvis_floating_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "jarvis_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_RELATIONSHIP = "relationship_style"
        const val KEY_CONTINUOUS_LOOP = "continuous_loop_enabled"
        const val KEY_GHOST_MODE = "ghost_mode_enabled"

        const val ACTION_STOP = "com.jarvis.assistant.ACTION_FORCE_STOP"
        const val ACTION_WIPE = "com.jarvis.assistant.ACTION_WIPE_MEMORY"

        var mediaProjectionInstance: MediaProjection? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        speechEngine = SpeechEngine(this)
        geminiClient = GeminiClient()

        isContinuousLoop = prefs.getBoolean(KEY_CONTINUOUS_LOOP, false)
        isGhostMode = prefs.getBoolean(KEY_GHOST_MODE, false)

        startForegroundNotification()
        initFloatingOverlay()
        initSpeechRecognizer()
        initClipboardListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> forceStop()
            ACTION_WIPE -> wipeMemory()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Floating Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps holographic Arc Reactor running over other apps"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Instant Notification STOP Action
        val stopIntent = Intent(this, JarvisFloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "J.A.R.V.I.S.") ?: "J.A.R.V.I.S."

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$assistantName Online")
            .setContentText("Hologram active. Tap to open or press STOP to abort.")
            .setContentIntent(pendingOpenIntent)
            .addAction(android.R.drawable.ic_delete, "🛑 STOP", pendingStopIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_hologram_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 220
        }

        hologramView = floatingView?.findViewById(R.id.floatingHologramView)
        expandedMenu = floatingView?.findViewById(R.id.floatingExpandedMenu)
        clipboardPill = floatingView?.findViewById(R.id.floatingClipboardPill)
        tvClipboardSnippet = floatingView?.findViewById(R.id.tvClipboardSnippet)
        btnFloatingLoop = floatingView?.findViewById(R.id.btnFloatingLoop)
        btnFloatingGhost = floatingView?.findViewById(R.id.btnFloatingGhost)

        updateLoopUi()
        updateGhostUi()

        val bubbleContainer = floatingView?.findViewById<FrameLayout>(R.id.floatingBubbleContainer)

        // Dragging & Tapping Touch Listener
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        bubbleContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        // Action Buttons inside expanded mini menu
        floatingView?.findViewById<ImageButton>(R.id.btnFloatingTalk)?.setOnClickListener {
            startListening()
        }

        btnFloatingLoop?.setOnClickListener {
            isContinuousLoop = !isContinuousLoop
            prefs.edit().putBoolean(KEY_CONTINUOUS_LOOP, isContinuousLoop).apply()
            updateLoopUi()
            val msg = if (isContinuousLoop) "Continuous voice loop engaged, hands-free active." else "Continuous voice loop disengaged."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (isContinuousLoop && !isListening && !speechEngine.isSpeaking()) {
                startListening()
            }
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingStop)?.setOnClickListener {
            forceStop()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingCam)?.setOnClickListener {
            triggerCameraVision()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingScreen)?.setOnClickListener {
            triggerScreenVision()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingTorch)?.setOnClickListener {
            val state = OfflineToolsHelper.toggleTorch(this)
            Toast.makeText(this, if (state) "Torch ON" else "Torch OFF", Toast.LENGTH_SHORT).show()
        }

        btnFloatingGhost?.setOnClickListener {
            isGhostMode = !isGhostMode
            prefs.edit().putBoolean(KEY_GHOST_MODE, isGhostMode).apply()
            updateGhostUi()
            Toast.makeText(this, if (isGhostMode) "🕶️ Ghost Mode Active (No memory saved)" else "Normal Mode Active", Toast.LENGTH_SHORT).show()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingWipe)?.setOnClickListener {
            wipeMemory()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingClose)?.setOnClickListener {
            stopSelf()
        }

        // Clipboard Pill interactions
        clipboardPill?.setOnClickListener {
            if (lastCopiedText.isNotBlank()) {
                clipboardPill?.visibility = View.GONE
                processQuery("Explain or summarize this copied text: \"$lastCopiedText\"")
            }
        }

        floatingView?.findViewById<ImageButton>(R.id.btnDismissClipboard)?.setOnClickListener {
            clipboardPill?.visibility = View.GONE
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (_: Exception) {}
    }

    private fun updateLoopUi() {
        btnFloatingLoop?.setColorFilter(
            if (isContinuousLoop) Color.parseColor("#00f0ff") else Color.parseColor("#789bbb")
        )
    }

    private fun updateGhostUi() {
        hologramView?.setGhostMode(isGhostMode)
        btnFloatingGhost?.setColorFilter(
            if (isGhostMode) Color.parseColor("#b388ff") else Color.parseColor("#789bbb")
        )
    }

    private fun toggleMenu() {
        isExpanded = !isExpanded
        expandedMenu?.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    // ==========================================
    // SMART CLIPBOARD LISTENER
    // ==========================================
    private fun initClipboardListener() {
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
                val clip = clipboardManager?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                    if (text.isNotEmpty() && text != lastCopiedText && text.length > 3) {
                        lastCopiedText = text
                        showClipboardPill(text)
                    }
                }
            }
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        } catch (_: Exception) {}
    }

    private fun showClipboardPill(text: String) {
        val snippet = if (text.length > 25) text.substring(0, 25) + "..." else text
        tvClipboardSnippet?.text = "⚡ Ask Jarvis: $snippet"
        clipboardPill?.visibility = View.VISIBLE

        // Auto-dismiss after 6 seconds
        mainHandler.removeCallbacksAndMessages("clipboard_dismiss")
        mainHandler.postDelayed({
            clipboardPill?.visibility = View.GONE
        }, 6000)
    }

    // ==========================================
    // CONTINUOUS SPEECH RECOGNITION & BARGE-IN
    // ==========================================
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    hologramView?.setState(HologramView.State.LISTENING)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    hologramView?.setState(HologramView.State.THINKING)
                }

                override fun onError(error: Int) {
                    isListening = false
                    hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                    // If in continuous loop and hit no-speech timeout, gracefully retry after a moment
                    if (isContinuousLoop && error == SpeechRecognizer.ERROR_NO_MATCH) {
                        serviceScope.launch {
                            delay(1200)
                            if (isContinuousLoop && !isListening && !speechEngine.isSpeaking()) {
                                startListening()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: return
                    handleUserSpeech(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Instant Barge-In Detection while user speaks:
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val pText = partials?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                    if (pText.contains("stop") || pText.contains("chup") || pText.contains("quiet") || pText.contains("cancel") || pText.contains("ruk") || pText.contains("bas")) {
                        forceStop()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (isListening) return
        speechEngine.stop()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        hologramView?.setState(HologramView.State.LISTENING)
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        }
    }

    private fun forceStop() {
        isContinuousLoop = false
        prefs.edit().putBoolean(KEY_CONTINUOUS_LOOP, false).apply()
        updateLoopUi()

        speechEngine.stop()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        Toast.makeText(this, "🛑 JARVIS Force Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun wipeMemory() {
        chatHistory.clear()
        lastCopiedText = ""
        speechEngine.stop()
        hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        speechEngine.speak("Memory wiped clean, $userName! What conversation? I don't remember a thing! 😉")
        Toast.makeText(this, "🧹 Memory flushed completely!", Toast.LENGTH_SHORT).show()
    }

    private fun handleUserSpeech(text: String) {
        val lower = text.lowercase(Locale.ROOT)

        // Instant voice stop command
        if (lower == "stop" || lower == "chup" || lower == "quiet" || lower == "cancel" || lower == "ruk" || lower == "bas") {
            forceStop()
            return
        }

        // Voice Wipe command
        if (lower.contains("wipe memory") || lower.contains("clear memory") || lower.contains("forget everything")) {
            wipeMemory()
            return
        }

        // Screen analysis voice trigger
        if (lower.contains("look at my screen") || lower.contains("see my screen") || lower.contains("check screen")) {
            triggerScreenVision()
            return
        }

        // Camera voice trigger
        if (lower.contains("look through camera") || lower.contains("what is this") || lower.contains("see this")) {
            triggerCameraVision()
            return
        }

        // Flashlight voice trigger
        if (lower.contains("torch on") || lower.contains("flashlight on")) {
            OfflineToolsHelper.toggleTorch(this, true)
            speechEngine.speak("Flashlight activated.")
            return
        }
        if (lower.contains("torch off") || lower.contains("flashlight off")) {
            OfflineToolsHelper.toggleTorch(this, false)
            speechEngine.speak("Flashlight turned off.")
            return
        }

        // Standard Gemini processing
        processQuery(text)
    }

    private fun processQuery(prompt: String, imageBytes: ByteArray? = null) {
        val apiKey = prefs.getString(KEY_API, "") ?: ""
        val model = prefs.getString(KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val relationshipStyle = prefs.getString(KEY_RELATIONSHIP, "girlfriend") ?: "girlfriend"

        if (apiKey.isBlank()) {
            speechEngine.speak("$userName, please configure your Gemini API key in the main app first.")
            hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
            return
        }

        hologramView?.setState(HologramView.State.THINKING)

        serviceScope.launch {
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = prompt,
                imageBytes = imageBytes,
                model = model,
                userName = userName,
                assistantName = assistantName,
                relationshipStyle = relationshipStyle,
                history = chatHistory
            )

            result.onSuccess { reply ->
                withContext(Dispatchers.Main) {
                    // Update rolling history if not in Ghost Mode
                    if (!isGhostMode) {
                        chatHistory.add(ChatMessage("user", prompt))
                        chatHistory.add(ChatMessage("model", reply))
                    }

                    hologramView?.setState(HologramView.State.SPEAKING)
                    speechEngine.speak(
                        text = reply,
                        onDone = {
                            hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                            // Hands-free continuous duplex conversation loop:
                            if (isContinuousLoop) {
                                serviceScope.launch {
                                    delay(400)
                                    if (isContinuousLoop && !isListening) {
                                        startListening()
                                    }
                                }
                            }
                        }
                    )
                }
            }.onFailure { _ ->
                withContext(Dispatchers.Main) {
                    hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
                    speechEngine.speak("Neural network link interrupted. Let's try again in a second.")
                }
            }
        }
    }

    private fun triggerScreenVision() {
        val projection = mediaProjectionInstance
        if (projection == null) {
            val userName = prefs.getString(KEY_USER_NAME, "Aditya") ?: "Aditya"
            speechEngine.speak("$userName, please tap 'Screen' inside the main app first to grant display access.")
            return
        }

        hologramView?.setState(HologramView.State.THINKING)
        speechEngine.speak("Analyzing your screen...")

        serviceScope.launch {
            val screenHelper = ScreenCaptureHelper(applicationContext)
            val bytes = screenHelper.captureScreenBytes(projection)
            if (bytes != null) {
                processQuery("Look at what is displayed on my screen right now and explain it or help me with it.", bytes)
            } else {
                speechEngine.speak("Unable to read screen display buffer.")
                hologramView?.setState(if (isGhostMode) HologramView.State.GHOST else HologramView.State.IDLE)
            }
        }
    }

    private fun triggerCameraVision() {
        speechEngine.speak("Opening visual sensor feed...")
        val openCameraIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("trigger_camera", true)
        }
        startActivity(openCameraIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechEngine.shutdown()
        geminiClient.cancelActiveCall()

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}

        try {
            if (clipChangedListener != null) {
                clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
            }
        } catch (_: Exception) {}

        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
    }
}
