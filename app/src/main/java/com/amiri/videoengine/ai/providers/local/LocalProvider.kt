package com.amiri.videoengine.ai.providers.local

import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.ProviderCapabilities
import com.amiri.videoengine.ai.model.ProviderException
import com.amiri.videoengine.ai.model.ProviderOutput
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.providers.ProviderProgress
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import java.io.File

/**
 * Slot for an on-device video model.
 *
 * Today's real video diffusion models (Wan, LTX, Hunyuan...) need many GB of GPU
 * memory and minutes of desktop-GPU time per second of video. They do not run in
 * a usable way on a phone, so this engine honestly reports itself unavailable
 * instead of faking a result. When a phone-sized model exists, implement it here
 * (e.g. with a TFLite/LiteRT or ONNX Runtime model file) and the router will
 * start using it automatically, including offline.
 *
 * Everything around generation (image preparation, thumbnails, frame extraction,
 * saving, history) already runs locally on the device.
 */
class LocalProvider : VideoGenerationProvider {
    override val id = "local"
    override val displayName = "On-device engine"
    override val description = "Local generation unavailable for this model on this device."

    private val caps = ProviderCapabilities(
        modes = emptySet(),
        maxDurationSec = 0.0,
        aspectRatios = emptySet(),
        audio = false,
        requiresInternet = false,
        requiresKey = false,
        free = true,
        speedRank = 1,
        qualityRank = 1,
        multilingualPrompt = false,
    )

    override fun getCapabilities() = caps

    override fun canGenerate(request: GenerationRequest): Boolean = false

    override suspend fun getStatus(): ProviderStatus = ProviderStatus.NOT_SUPPORTED

    override suspend fun generate(
        request: GenerationRequest,
        outputFile: File,
        onProgress: (ProviderProgress) -> Unit,
    ): ProviderOutput = throw ProviderException(ProviderException.Kind.UNSUPPORTED, description)

    override fun cancelGeneration() = Unit
}
