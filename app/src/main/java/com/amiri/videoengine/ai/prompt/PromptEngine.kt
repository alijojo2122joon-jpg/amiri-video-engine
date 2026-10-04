package com.amiri.videoengine.ai.prompt

import com.amiri.videoengine.ai.model.GenerationMode
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.model.StructuredPrompt

/**
 * Turns a simple sentence into a structured request, then into the final prompt.
 *
 * Rule: the user's words always come first and are never rewritten. The engine only
 * ADDS short guidance for aspects the user did not mention (camera, light, motion,
 * realism, continuity), so the artistic intent is preserved.
 */
object PromptEngine {

    private val cameraWords = listOf(
        "camera", "pan", "tilt", "zoom", "dolly", "tracking", "close-up", "closeup", "wide shot",
        "aerial", "drone", "handheld", "pov", "orbit", "crane", "static shot", "low angle", "high angle",
        "lens", "mm", "bokeh", "دوربین", "زوم", "نمای نزدیک", "نمای باز",
    )
    private val lightingWords = listOf(
        "light", "lighting", "lit", "neon", "sunlight", "moonlight", "shadow", "glow", "backlit",
        "golden hour", "candle", "lamp", "نور", "سایه", "نئون",
    )
    private val timeWords = listOf(
        "night", "day", "morning", "evening", "sunset", "sunrise", "dawn", "dusk", "noon", "midnight",
        "شب", "روز", "صبح", "عصر", "غروب", "طلوع",
    )
    private val weatherWords = listOf(
        "rain", "rainy", "snow", "snowy", "fog", "foggy", "mist", "storm", "wind", "windy", "cloud",
        "باران", "برف", "مه", "طوفان", "باد", "ابر",
    )
    private val motionWords = listOf(
        "walk", "run", "fly", "dance", "jump", "move", "turn", "spin", "fall", "swim", "drive", "ride",
        "slow motion", "timelapse", "time-lapse", "flow", "wave",
        "راه", "بدو", "پرواز", "رقص", "حرکت", "می‌چرخد", "میچرخد",
    )
    private val styleWords = listOf(
        "cinematic", "anime", "cartoon", "3d", "pixar", "watercolor", "oil painting", "noir", "vintage",
        "film", "documentary", "realistic", "photorealistic", "surreal", "cyberpunk", "sketch",
        "سینمایی", "انیمه", "کارتون", "واقعی",
    )
    private val environmentWords = listOf(
        "street", "city", "forest", "beach", "room", "desert", "mountain", "sea", "ocean", "river",
        "kitchen", "office", "space", "sky", "garden", "village", "road", "market", "studio",
        "خیابان", "شهر", "جنگل", "ساحل", "اتاق", "کوه", "دریا", "آسمان", "باغ", "بازار",
    )
    private val transitionWords = listOf(
        "into", "becomes", "turns into", "transforms", "transition", "morph", "then", "changes to",
        "تبدیل", "می‌شود", "میشود", "سپس",
    )

    private val nonLatinRegex = Regex("[\\u0600-\\u06FF]")

    fun analyze(prompt: String): StructuredPrompt {
        val p = prompt.trim()
        val lower = p.lowercase()
        fun find(words: List<String>): String? = words.firstOrNull { lower.contains(it) }
        val firstClause = p.split(',', '.', '،').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return StructuredPrompt(
            original = p,
            subject = firstClause,
            action = find(motionWords),
            environment = find(environmentWords),
            camera = find(cameraWords),
            lighting = find(lightingWords),
            timeOfDay = find(timeWords),
            weather = find(weatherWords),
            motion = find(motionWords),
            style = find(styleWords),
            realism = if (find(listOf("realistic", "photoreal", "real ", "واقعی")) != null) "user" else null,
            continuity = null,
            transition = find(transitionWords),
            nonLatin = nonLatinRegex.containsMatchIn(p),
        )
    }

    /** True when the prompt suggests a change from one state to another (two-image mode). */
    fun suggestsTransition(prompt: String): Boolean = analyze(prompt).transition != null

    fun buildFinalPrompt(s: StructuredPrompt, mode: GenerationMode, quality: QualityPreset): String {
        val additions = mutableListOf<String>()
        if (s.camera == null) additions += "steady cinematic camera"
        if (s.lighting == null && s.timeOfDay == null) additions += "natural, consistent lighting"
        if (s.motion == null) additions += "smooth natural motion"
        if (s.style == null && s.realism == null) additions += "realistic detail and believable physics"
        when (mode) {
            GenerationMode.IMAGE_TO_VIDEO ->
                additions += "keep the subject's identity, clothing, the environment, composition and important objects exactly as in the starting image"
            GenerationMode.FIRST_LAST_TO_VIDEO ->
                additions += "start exactly as the first image, move with controlled continuous motion, and end exactly matching the final image"
            GenerationMode.TEXT_TO_VIDEO -> Unit
        }
        if (quality != QualityPreset.FAST) additions += "high detail, sharp focus"
        val base = s.original.trimEnd('.', ' ')
        return if (additions.isEmpty()) base else base + ". " + additions.joinToString(", ") + "."
    }

    const val NEGATIVE_PROMPT =
        "blurry, low quality, distorted face, extra limbs, deformed hands, flicker, jitter, watermark, text, logo, static frame"
}
