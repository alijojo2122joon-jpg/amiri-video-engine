package com.amiri.videoengine.ai.model

import java.io.File

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    PORTRAIT("9:16", 9, 16),
    LANDSCAPE("16:9", 16, 9),
    SQUARE("1:1", 1, 1);

    val ratio: Float get() = w.toFloat() / h.toFloat()

    companion object {
        fun closestTo(width: Int, height: Int): AspectRatio {
            if (width <= 0 || height <= 0) return PORTRAIT
            val r = width.toFloat() / height.toFloat()
            return entries.minByOrNull { kotlin.math.abs(it.ratio - r) } ?: PORTRAIT
        }

        fun fromLabel(label: String?): AspectRatio = entries.firstOrNull { it.label == label } ?: PORTRAIT
    }
}

/** User-facing duration choice. The engine maps it to real seconds per provider. */
enum class DurationPreset(val label: String, val seconds: Double) {
    SHORT("Short", 2.0),
    MEDIUM("Medium", 3.0),
    LONG("Long", 4.0);

    companion object {
        fun fromName(n: String?): DurationPreset = entries.firstOrNull { it.name == n } ?: SHORT
    }
}

enum class QualityPreset(val label: String) {
    FAST("Fast"),
    HIGH("High"),
    MAXIMUM("Maximum");

    companion object {
        fun fromName(n: String?): QualityPreset = entries.firstOrNull { it.name == n } ?: HIGH
    }
}

/** Advanced-only resolution override. AUTO lets the engine decide. */
enum class ResolutionPreset(val label: String, val scale: Float) {
    AUTO("Auto", 1.0f),
    LOW("Lower (faster)", 0.75f),
    HIGH("Higher (slower)", 1.25f);

    companion object {
        fun fromName(n: String?): ResolutionPreset = entries.firstOrNull { it.name == n } ?: AUTO
    }
}

enum class GenerationMode(val label: String) {
    TEXT_TO_VIDEO("Text to video"),
    IMAGE_TO_VIDEO("First frame to video"),
    FIRST_LAST_TO_VIDEO("First + last frame transition"),
}

data class AdvancedOptions(
    val preferredProviderId: String? = null,
    val seed: Long? = null,
    val resolution: ResolutionPreset = ResolutionPreset.AUTO,
    val variations: Int = 1,
)

/** Raw input exactly as the user gave it on the home screen. */
data class UserInput(
    val prompt: String,
    val firstFrame: File?,
    val lastFrame: File?,
    val referenceImage: File?,
    val aspect: AspectRatio,
    val duration: DurationPreset,
    val quality: QualityPreset,
    val advanced: AdvancedOptions,
    /** English prompt the user already previewed/edited. Null = prepare automatically. */
    val englishPrompt: String? = null,
)

data class StructuredPrompt(
    val original: String,
    val subject: String?,
    val action: String?,
    val environment: String?,
    val camera: String?,
    val lighting: String?,
    val timeOfDay: String?,
    val weather: String?,
    val motion: String?,
    val style: String?,
    val realism: String?,
    val continuity: String?,
    val transition: String?,
    val nonLatin: Boolean,
)

/** Fully prepared request that providers receive. Images are already preprocessed copies. */
data class GenerationRequest(
    val prompt: String,
    val finalPrompt: String,
    val negativePrompt: String,
    val structured: StructuredPrompt,
    val firstFrame: File?,
    val lastFrame: File?,
    val aspect: AspectRatio,
    val duration: DurationPreset,
    val quality: QualityPreset,
    val seed: Long?,
    val resolution: ResolutionPreset,
) {
    val mode: GenerationMode
        get() = when {
            firstFrame != null && lastFrame != null -> GenerationMode.FIRST_LAST_TO_VIDEO
            firstFrame != null -> GenerationMode.IMAGE_TO_VIDEO
            else -> GenerationMode.TEXT_TO_VIDEO
        }
}

data class ProviderCapabilities(
    val modes: Set<GenerationMode>,
    val maxDurationSec: Double,
    val aspectRatios: Set<AspectRatio>,
    val audio: Boolean,
    val requiresInternet: Boolean,
    val requiresKey: Boolean,
    val free: Boolean,
    /** 1..10, higher = faster. */
    val speedRank: Int,
    /** 1..10, higher = better looking. */
    val qualityRank: Int,
    val multilingualPrompt: Boolean,
)

enum class ProviderStatus(val label: String) {
    AVAILABLE("Available"),
    DISABLED("Off"),
    NOT_CONFIGURED("Needs API key"),
    UNAVAILABLE("Temporarily unavailable"),
    OFFLINE("Needs internet"),
    NOT_SUPPORTED("Not available on this device"),
}

data class ProviderOutput(
    val file: File,
    val seedUsed: Long?,
    val notes: List<String> = emptyList(),
)

class ProviderException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { QUOTA, UNAVAILABLE, AUTH, BAD_REQUEST, NETWORK, UNSUPPORTED, UNKNOWN }
}

enum class JobStage {
    QUEUED, PREPARING, SUBMITTING, GENERATING, DOWNLOADING, PROCESSING, COMPLETED, FAILED, CANCELLED;

    val isActive: Boolean get() = this != COMPLETED && this != FAILED && this != CANCELLED
}

/** The visible checklist on the generation screen. Each step is real work. */
enum class JobStep(val label: String) {
    PREPARE_IMAGES("Preparing images"),
    ANALYZE_PROMPT("Understanding your prompt"),
    CREATE_SCENE("Building the scene from your text"),
    CONNECT("Connecting to AI engine"),
    GENERATE("Generating frames"),
    PROCESS("Processing video"),
    FINALIZE("Finalizing"),
}
