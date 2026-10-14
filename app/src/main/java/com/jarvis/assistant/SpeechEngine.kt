package com.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * SpeechEngine for JARVIS Android
 * Supports:
 * - Google Gemini Neural Computer Voice Models (Aoede, Kore, Charon, Puck, Fenrir)
 * - Plays high-fidelity 24kHz WAV audio via MediaPlayer
 * - Native Hindi & Hinglish pronunciation with authentic human emotions
 * - Instant Barge-In / Force-Stopping
 * - Graceful fallback to Android native TTS if offline
 */
class SpeechEngine(
    private val context: Context,
    private val onReadyCallback: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    data class ComputerVoice(
        val id: String,
        val displayName: String,
        val description: String,
        val persona: String
    )

    companion object {
        const val DEFAULT_VOICE = "Aoede"

        val COMPUTER_VOICES = listOf(
            ComputerVoice(
                id = "Aoede",
                displayName = "💖 Aoede (Warm Girlfriend - PC Voice)",
                description = "Original PC Jarvis voice. Flawless Hinglish, Hindi & English.",
                persona = "girlfriend"
            ),
            ComputerVoice(
                id = "Kore",
                displayName = "🌸 Kore (Calm & Soothing Female)",
                description = "Gentle, relaxed, late-night study companion tone.",
                persona = "calm"
            ),
            ComputerVoice(
                id = "Charon",
                displayName = "🎩 Charon (British Butler / Tony Stark AI)",
                description = "Deep, poised, authoritative Stark assistant.",
                persona = "butler"
            ),
            ComputerVoice(
                id = "Puck",
                displayName = "⚡ Puck (Youthful & Playful)",
                description = "Energetic, spirited, fun conversationalist.",
                persona = "playful"
            ),
            ComputerVoice(
                id = "Fenrir",
                displayName = "🛡️ Fenrir (Bold & Confident)",
                description = "Strong, clear, resolute male voice.",
                persona = "bold"
            )
        )
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    var currentVoiceId: String = DEFAULT_VOICE
        private set
    var isMuted: Boolean = false

    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null

    init {
        // Initialize fallback TTS
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeechStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }

                override fun onError(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }
            })
            onReadyCallback?.invoke()
        }
    }

    fun setVoiceModel(voiceId: String) {
        currentVoiceId = voiceId
    }

    /**
     * Plays high-fidelity neural computer voice WAV audio returned from Gemini 3.8 Flash TTS
     */
    fun playNeuralAudio(
        wavBytes: ByteArray,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted) {
            onDone?.invoke()
            return
        }

        stop()

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        try {
            val cacheFile = File(context.cacheDir, "jarvis_voice_temp.wav")
            FileOutputStream(cacheFile).use { it.write(wavBytes) }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
                setDataSource(cacheFile.absolutePath)
                setOnPreparedListener { mp ->
                    onSpeechStart?.invoke()
                    mp.start()
                }
                setOnCompletionListener {
                    cleanupMediaPlayer()
                    onSpeechDone?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    cleanupMediaPlayer()
                    onSpeechDone?.invoke()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            cleanupMediaPlayer()
            onDone?.invoke()
        }
    }

    /**
     * Fallback to native device TTS if network audio is unavailable
     */
    fun speakFallbackTts(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted || !isTtsReady || tts == null) {
            onDone?.invoke()
            return
        }

        stop()

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        val clean = text
            .replace(Regex("[*#`_~>]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
            .trim()

        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }

        tts?.setPitch(1.05f)
        tts?.setSpeechRate(1.0f)
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Standard speak method for background services & alarm receivers
     */
    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        speakFallbackTts(text, onStart, onDone)
    }

    /**
     * Instant Barge-In: immediately halts all neural playback and TTS
     */
    fun stop() {
        cleanupMediaPlayer()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
        onSpeechDone?.invoke()
    }

    fun isSpeaking(): Boolean {
        return (mediaPlayer?.isPlaying == true) || (tts?.isSpeaking == true)
    }

    private fun cleanupMediaPlayer() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun shutdown() {
        cleanupMediaPlayer()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (_: Exception) {}
    }
}
