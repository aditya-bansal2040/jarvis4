package com.jarvis.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * Enhanced SpeechEngine for JARVIS Android
 * Features:
 * - 4 distinct Voice Models (Warm Girlfriend, Sassy Hinglish, Victoria Butler, Calm Study)
 * - Priority loading of Google Speech Services (com.google.android.tts)
 * - Instant Barge-In / Force-Stopping
 * - Clean Markdown stripping for smooth natural speech
 */
class SpeechEngine(
    private val context: Context,
    private val onReadyCallback: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    data class VoiceModelInfo(
        val id: String,
        val displayName: String,
        val description: String,
        val defaultPitch: Float,
        val defaultRate: Float,
        val preferredLocales: List<Locale>,
        val nameKeywords: List<String>
    )

    companion object {
        const val MODEL_WARM_GIRLFRIEND = "girlfriend"
        const val MODEL_SASSY_HINGLISH = "sassy"
        const val MODEL_VICTORIA = "victoria"
        const val MODEL_CALM_STUDY = "calm"

        val AVAILABLE_MODELS = listOf(
            VoiceModelInfo(
                id = MODEL_WARM_GIRLFRIEND,
                displayName = "💖 Warm Girlfriend (Hinglish)",
                description = "Sweet, affectionate, caring companion tone",
                defaultPitch = 1.15f,
                defaultRate = 1.02f,
                preferredLocales = listOf(Locale("en", "IN"), Locale("hi", "IN")),
                nameKeywords = listOf("female", "f0", "enc", "end", "c-", "d-")
            ),
            VoiceModelInfo(
                id = MODEL_SASSY_HINGLISH,
                displayName = "✨ Sassy & Lively Hinglish",
                description = "Playful, energetic, high-spirit banter",
                defaultPitch = 1.22f,
                defaultRate = 1.06f,
                preferredLocales = listOf(Locale("hi", "IN"), Locale("en", "IN")),
                nameKeywords = listOf("female", "f0", "hic", "hid", "c-", "d-")
            ),
            VoiceModelInfo(
                id = MODEL_VICTORIA,
                displayName = "👑 Victoria (British Jarvis)",
                description = "Poised, elegant, classic Jarvis AI persona",
                defaultPitch = 1.00f,
                defaultRate = 0.98f,
                preferredLocales = listOf(Locale.UK, Locale.US),
                nameKeywords = listOf("female", "en-gb", "gbr", "f0")
            ),
            VoiceModelInfo(
                id = MODEL_CALM_STUDY,
                displayName = "🌙 Calm & Gentle (Night Study)",
                description = "Soft, soothing late-night study partner",
                defaultPitch = 1.06f,
                defaultRate = 0.94f,
                preferredLocales = listOf(Locale("en", "IN"), Locale.US),
                nameKeywords = listOf("female", "f0", "soft")
            )
        )
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    var currentModelId: String = MODEL_WARM_GIRLFRIEND
        private set
    var pitch: Float = 1.15f
    var rate: Float = 1.02f
    var isMuted: Boolean = false

    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null

    init {
        // Prefer Google TTS engine for highest quality neural voices
        try {
            tts = TextToSpeech(context.applicationContext, this, "com.google.android.tts")
        } catch (_: Exception) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            setupProgressListener()
            applyVoiceModel(currentModelId)
            onReadyCallback?.invoke()
        } else {
            // Fallback to default engine if Google TTS failed to bind
            try {
                tts = TextToSpeech(context.applicationContext) { fallbackStatus ->
                    if (fallbackStatus == TextToSpeech.SUCCESS) {
                        isReady = true
                        setupProgressListener()
                        applyVoiceModel(currentModelId)
                        onReadyCallback?.invoke()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupProgressListener() {
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
    }

    fun applyVoiceModel(modelId: String) {
        currentModelId = modelId
        val modelConfig = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: AVAILABLE_MODELS[0]

        pitch = modelConfig.defaultPitch
        rate = modelConfig.defaultRate

        if (!isReady || tts == null) return

        // 1. Set language
        var languageSet = false
        for (locale in modelConfig.preferredLocales) {
            val res = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (res >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
                languageSet = true
                break
            }
        }
        if (!languageSet) {
            tts?.language = Locale.US
        }

        // 2. Search and bind best matching female voice
        try {
            val allVoices = tts?.voices
            if (!allVoices.isNullOrEmpty()) {
                val matchedVoice = findBestVoice(allVoices, modelConfig)
                if (matchedVoice != null) {
                    tts?.voice = matchedVoice
                }
            }
        } catch (_: Exception) {}

        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    private fun findBestVoice(voices: Set<Voice>, config: VoiceModelInfo): Voice? {
        val targetLocales = config.preferredLocales.map { it.language.lowercase(Locale.ROOT) }

        // Tier 1: Matches locale + female keyword
        val tier1 = voices.firstOrNull { v ->
            val vLang = v.locale.language.lowercase(Locale.ROOT)
            val vName = v.name.lowercase(Locale.ROOT)
            val matchesLang = targetLocales.contains(vLang) || (vLang == "en" && v.locale.country.equals("IN", ignoreCase = true))
            val isFemale = config.nameKeywords.any { vName.contains(it) } || vName.contains("female") || vName.contains("f0")
            matchesLang && isFemale
        }
        if (tier1 != null) return tier1

        // Tier 2: Any female voice in installed voices
        val tier2 = voices.firstOrNull { v ->
            val vName = v.name.lowercase(Locale.ROOT)
            vName.contains("female") || vName.contains("f0") || vName.contains("c-")
        }
        if (tier2 != null) return tier2

        return null
    }

    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted || !isReady || tts == null) {
            onDone?.invoke()
            return
        }

        // Strip markdown and emojis for clean, natural speech
        val clean = stripMarkdownAndSymbols(text)
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Preview / test a voice model with sample text
     */
    fun previewVoice(modelId: String, sampleText: String = "Hey Adi! Main yahan hoon tumhare saath. Kaisi lagi meri voice?") {
        applyVoiceModel(modelId)
        speak(sampleText)
    }

    /**
     * Instantly stops vocalization (Barge-In)
     */
    fun stop() {
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
            onSpeechDone?.invoke()
        } catch (_: Exception) {}
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    private fun stripMarkdownAndSymbols(input: String): String {
        return input
            .replace(Regex("[*#`_~>]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "") // Remove emojis that cause speech hiccups
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (_: Exception) {}
    }
}
