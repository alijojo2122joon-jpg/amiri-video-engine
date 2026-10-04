package com.amiri.videoengine.ai.router

import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.ProviderException
import com.amiri.videoengine.ai.model.ProviderOutput
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.providers.ProviderProgress
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.network.ConnectionManager
import com.amiri.videoengine.network.ConnectionState
import kotlinx.coroutines.CancellationException
import java.io.File

/** Friendly failure shown to the user. Never contains raw HTTP / exception text. */
class RoutingException(message: String) : Exception(message)

data class RoutedOutput(
    val output: ProviderOutput,
    val provider: VideoGenerationProvider,
)

/**
 * Picks the best engine for a request and falls back to the next one on failure.
 *
 *   request -> capability check -> rank (speed / quality / user preference)
 *           -> try engine A -> unavailable -> try engine B -> ... -> friendly error
 */
class ModelRouter(
    private val providersSource: () -> List<VideoGenerationProvider>,
    private val connection: ConnectionManager,
) {
    @Volatile
    private var active: VideoGenerationProvider? = null

    fun allProviders(): List<VideoGenerationProvider> = providersSource()

    /** Ordered candidates for a request (static checks only, no network). */
    fun rank(request: GenerationRequest, preferredId: String?): List<VideoGenerationProvider> {
        val capable = providersSource().filter { it.canGenerate(request) }
        val wantSpeed = request.quality == QualityPreset.FAST
        return capable.sortedWith(
            compareByDescending<VideoGenerationProvider> { it.id == preferredId }
                // Paid engines the user configured win on Maximum quality.
                .thenByDescending { request.quality == QualityPreset.MAXIMUM && !it.getCapabilities().free }
                // Non-English prompts go to multilingual models first.
                .thenByDescending { request.structured.nonLatin && it.getCapabilities().multilingualPrompt }
                .thenByDescending {
                    val c = it.getCapabilities()
                    if (wantSpeed) c.speedRank * 10 + c.qualityRank else c.qualityRank * 10 + c.speedRank
                }
        )
    }

    suspend fun generate(
        request: GenerationRequest,
        preferredId: String?,
        outputFile: File,
        onProgress: (ProviderProgress, VideoGenerationProvider?) -> Unit,
    ): RoutedOutput {
        if (connection.current() == ConnectionState.OFFLINE) {
            val localCanDo = providersSource().any { !it.getCapabilities().requiresInternet && it.canGenerate(request) }
            if (!localCanDo) throw RoutingException("Offline generation is not available for the current model.")
        }

        val candidates = rank(request, preferredId)
        if (candidates.isEmpty()) {
            throw RoutingException(
                "No engine can do this combination yet. Try a different set of images, or enable more engines in Settings → AI Providers."
            )
        }

        val failures = mutableListOf<ProviderException.Kind>()
        var tried = 0
        for (provider in candidates) {
            val status = provider.getStatus()
            if (status != ProviderStatus.AVAILABLE) continue
            if (tried > 0) {
                onProgress(ProviderProgress(JobStage.SUBMITTING, "That engine is temporarily unavailable. Trying another available engine…"), provider)
            }
            tried++
            active = provider
            try {
                if (outputFile.exists()) outputFile.delete()
                val out = provider.generate(request, outputFile) { p -> onProgress(p, provider) }
                return RoutedOutput(out, provider)
            } catch (e: CancellationException) {
                provider.cancelGeneration()
                throw e
            } catch (e: ProviderException) {
                failures += e.kind
            } catch (e: Exception) {
                failures += ProviderException.Kind.UNKNOWN
            } finally {
                active = null
            }
        }

        throw RoutingException(
            when {
                tried == 0 -> "No video engine is turned on and reachable right now. Check Settings → AI Providers and your internet connection."
                failures.isNotEmpty() && failures.all { it == ProviderException.Kind.QUOTA } ->
                    "Today's free GPU time is used up on the free engines. Try again later, or add your free Hugging Face token in Settings for more free time."
                failures.contains(ProviderException.Kind.AUTH) && failures.size == 1 ->
                    "The engine refused the request. Check the API key or token in Settings → AI Providers."
                else -> "No available video engine could complete this request. Check your internet connection or AI provider settings."
            }
        )
    }

    fun cancel() {
        active?.cancelGeneration()
    }
}
