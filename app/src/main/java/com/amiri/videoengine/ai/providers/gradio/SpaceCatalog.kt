package com.amiri.videoengine.ai.providers.gradio

import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.GenerationMode
import com.amiri.videoengine.ai.model.ProviderCapabilities
import com.amiri.videoengine.storage.CustomSpace

/**
 * Free, public Hugging Face Spaces running open-source video models on ZeroGPU.
 * They cost nothing; Hugging Face gives every visitor a small daily GPU allowance
 * (more with a free account token). If one is down, the router uses the next.
 */
object SpaceCatalog {
    private val allAspects = AspectRatio.entries.toSet()

    val builtIn: List<SpaceSpec> = listOf(
        SpaceSpec(
            providerId = "hf_ltx_distilled",
            spaceId = "Lightricks/ltx-video-distilled",
            displayName = "LTX Video",
            description = "Fast. Text-to-video and first-frame-to-video.",
            capabilities = ProviderCapabilities(
                modes = setOf(GenerationMode.TEXT_TO_VIDEO, GenerationMode.IMAGE_TO_VIDEO),
                maxDurationSec = 6.0,
                aspectRatios = allAspects,
                audio = false,
                requiresInternet = true,
                requiresKey = false,
                free = true,
                speedRank = 9,
                qualityRank = 6,
                multilingualPrompt = false,
            ),
            builtIn = true,
        ),
        SpaceSpec(
            providerId = "hf_wan22_i2v",
            spaceId = "zerogpu-aoti/wan2-2-fp8da-aoti-faster",
            displayName = "Wan 2.2",
            description = "High quality. Animates your first frame.",
            capabilities = ProviderCapabilities(
                modes = setOf(GenerationMode.IMAGE_TO_VIDEO),
                maxDurationSec = 5.0,
                aspectRatios = allAspects,
                audio = false,
                requiresInternet = true,
                requiresKey = false,
                free = true,
                speedRank = 6,
                qualityRank = 8,
                multilingualPrompt = true,
            ),
            builtIn = true,
        ),
        SpaceSpec(
            providerId = "hf_wan22_flf",
            spaceId = "multimodalart/wan-2-2-first-last-frame",
            displayName = "Wan 2.2 First→Last",
            description = "Transition from your first frame to your last frame.",
            capabilities = ProviderCapabilities(
                modes = setOf(GenerationMode.FIRST_LAST_TO_VIDEO),
                maxDurationSec = 5.0,
                aspectRatios = allAspects,
                audio = false,
                requiresInternet = true,
                requiresKey = false,
                free = true,
                speedRank = 6,
                qualityRank = 8,
                multilingualPrompt = true,
            ),
            builtIn = true,
        ),
    )

    /** Spaces the user added by ID. Their abilities are detected when used. */
    fun custom(space: CustomSpace): SpaceSpec = SpaceSpec(
        providerId = "hf_custom_" + space.spaceId.replace('/', '_'),
        spaceId = space.spaceId,
        displayName = space.name,
        description = "Your Space: ${space.spaceId}",
        capabilities = ProviderCapabilities(
            modes = GenerationMode.entries.toSet(),
            maxDurationSec = 5.0,
            aspectRatios = allAspects,
            audio = false,
            requiresInternet = true,
            requiresKey = false,
            free = true,
            speedRank = 5,
            qualityRank = 5,
            multilingualPrompt = false,
        ),
        builtIn = false,
    )
}
