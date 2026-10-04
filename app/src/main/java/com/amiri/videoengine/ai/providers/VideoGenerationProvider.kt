package com.amiri.videoengine.ai.providers

import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.ProviderCapabilities
import com.amiri.videoengine.ai.model.ProviderOutput
import com.amiri.videoengine.ai.model.ProviderStatus
import java.io.File

/** Progress reported by a provider while it works. */
data class ProviderProgress(val stage: JobStage, val message: String)

/**
 * Every video engine (free Hugging Face Space, Google Veo, xAI, local...) implements this.
 * The router only talks to this interface, so engines can be added or removed freely.
 */
interface VideoGenerationProvider {
    val id: String
    val displayName: String
    /** Short human description shown in Settings. */
    val description: String

    fun getCapabilities(): ProviderCapabilities

    /** Cheap static check: can this engine handle this kind of request at all? */
    fun canGenerate(request: GenerationRequest): Boolean

    /** May touch the network (short timeout). Must never throw. */
    suspend fun getStatus(): ProviderStatus

    /**
     * Generates the video and writes it to [outputFile].
     * Throws [com.amiri.videoengine.ai.model.ProviderException] on failure.
     * Must react to coroutine cancellation.
     */
    suspend fun generate(
        request: GenerationRequest,
        outputFile: File,
        onProgress: (ProviderProgress) -> Unit,
    ): ProviderOutput

    /** Stops any network work in flight. Safe to call at any time. */
    fun cancelGeneration()
}
