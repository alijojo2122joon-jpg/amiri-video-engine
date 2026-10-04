package com.amiri.videoengine.ai.providers.cloud

import android.util.Base64
import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.DurationPreset
import com.amiri.videoengine.ai.model.GenerationMode
import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.ProviderCapabilities
import com.amiri.videoengine.ai.model.ProviderException
import com.amiri.videoengine.ai.model.ProviderOutput
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.providers.ProviderProgress
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.network.CallTracker
import com.amiri.videoengine.network.ConnectionManager
import com.amiri.videoengine.network.ConnectionState
import com.amiri.videoengine.network.Http
import com.amiri.videoengine.network.await
import com.amiri.videoengine.network.saveTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Shared plumbing for paid providers that use the user's own API key. */
abstract class KeyedCloudProvider(
    private val connection: ConnectionManager,
    private val keyProvider: () -> String?,
    private val isEnabled: () -> Boolean,
) : VideoGenerationProvider {
    protected val tracker = CallTracker()

    protected fun key(): String = keyProvider()?.takeIf { it.isNotBlank() }
        ?: throw ProviderException(ProviderException.Kind.AUTH, "No API key")

    override suspend fun getStatus(): ProviderStatus = when {
        !isEnabled() -> ProviderStatus.DISABLED
        keyProvider().isNullOrBlank() -> ProviderStatus.NOT_CONFIGURED
        connection.current() == ConnectionState.OFFLINE -> ProviderStatus.OFFLINE
        else -> ProviderStatus.AVAILABLE
    }

    override fun cancelGeneration() = tracker.cancelAll()

    protected suspend fun send(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val resp = try {
            Http.client.await(request, tracker)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Network error", e)
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val kind = when (r.code) {
                    401, 403 -> ProviderException.Kind.AUTH
                    400, 404, 422 -> ProviderException.Kind.BAD_REQUEST
                    429 -> ProviderException.Kind.QUOTA
                    else -> ProviderException.Kind.UNAVAILABLE
                }
                throw ProviderException(kind, "HTTP ${r.code}")
            }
            try {
                JSONObject(text)
            } catch (_: Exception) {
                throw ProviderException(ProviderException.Kind.UNKNOWN, "Unreadable response")
            }
        }
    }

    protected suspend fun download(request: Request, out: File) = withContext(Dispatchers.IO) {
        val resp = try {
            Http.client.await(request, tracker)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Download failed", e)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Download HTTP $code")
        }
        try {
            resp.saveTo(out)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Download interrupted", e)
        }
    }

    protected fun base64(file: File): String = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

    protected val json = "application/json".toMediaType()
}

/** Google Veo 3.1 through the official Gemini API. Needs the user's own paid API key. */
class GoogleVeoProvider(
    connection: ConnectionManager,
    keyProvider: () -> String?,
    isEnabled: () -> Boolean,
) : KeyedCloudProvider(connection, keyProvider, isEnabled) {
    override val id = "google_veo"
    override val displayName = "Google Veo 3.1"
    override val description = "Paid. Your own Gemini API key. Best quality, audio, first + last frame."

    private val caps = ProviderCapabilities(
        modes = GenerationMode.entries.toSet(),
        maxDurationSec = 8.0,
        aspectRatios = setOf(AspectRatio.PORTRAIT, AspectRatio.LANDSCAPE),
        audio = true,
        requiresInternet = true,
        requiresKey = true,
        free = false,
        speedRank = 5,
        qualityRank = 10,
        multilingualPrompt = true,
    )

    override fun getCapabilities() = caps

    override fun canGenerate(request: GenerationRequest): Boolean =
        request.aspect in caps.aspectRatios

    override suspend fun generate(
        request: GenerationRequest,
        outputFile: File,
        onProgress: (ProviderProgress) -> Unit,
    ): ProviderOutput {
        val apiKey = key()
        val model = if (request.quality == QualityPreset.FAST) "veo-3.1-fast-generate-preview" else "veo-3.1-generate-preview"
        val instance = JSONObject().put("prompt", request.finalPrompt)
        request.firstFrame?.let {
            onProgress(ProgressMsg.sending(displayName))
            instance.put("image", JSONObject().put("bytesBase64Encoded", base64(it)).put("mimeType", "image/jpeg"))
        }
        request.lastFrame?.let {
            instance.put("lastFrame", JSONObject().put("bytesBase64Encoded", base64(it)).put("mimeType", "image/jpeg"))
        }
        val seconds = when (request.duration) {
            DurationPreset.SHORT -> 4
            DurationPreset.MEDIUM -> 6
            DurationPreset.LONG -> 8
        }
        val params = JSONObject()
            .put("aspectRatio", request.aspect.label)
            .put("durationSeconds", seconds)
            .put("negativePrompt", request.negativePrompt)
        request.seed?.let { params.put("seed", it) }
        val body = JSONObject()
            .put("instances", org.json.JSONArray().put(instance))
            .put("parameters", params)

        onProgress(ProviderProgress(JobStage.SUBMITTING, "Connecting to $displayName…"))
        val start = send(
            Request.Builder()
                .url("$BASE/models/$model:predictLongRunning")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(json))
                .build()
        )
        val opName = start.optString("name").ifBlank {
            throw ProviderException(ProviderException.Kind.UNKNOWN, "No operation id")
        }

        onProgress(ProviderProgress(JobStage.GENERATING, "Generating frames on $displayName…"))
        var op = JSONObject()
        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (true) {
            delay(10_000)
            op = send(Request.Builder().url("$BASE/$opName").header("x-goog-api-key", apiKey).get().build())
            if (op.optBoolean("done", false)) break
            if (System.currentTimeMillis() > deadline) {
                throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Timed out")
            }
        }
        op.optJSONObject("error")?.let {
            throw ProviderException(ProviderException.Kind.UNKNOWN, it.optString("message", "Generation failed"))
        }
        val uri = op.optJSONObject("response")
            ?.optJSONObject("generateVideoResponse")
            ?.optJSONArray("generatedSamples")
            ?.optJSONObject(0)
            ?.optJSONObject("video")
            ?.optString("uri")
            .orEmpty()
        if (uri.isBlank()) throw ProviderException(ProviderException.Kind.UNKNOWN, "No video (it may have been filtered)")

        onProgress(ProviderProgress(JobStage.DOWNLOADING, "Downloading video…"))
        download(Request.Builder().url(uri).header("x-goog-api-key", apiKey).get().build(), outputFile)
        return ProviderOutput(outputFile, request.seed)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/** xAI Grok Imagine Video through the official xAI API. Needs the user's own paid API key. */
class XaiGrokProvider(
    connection: ConnectionManager,
    keyProvider: () -> String?,
    isEnabled: () -> Boolean,
) : KeyedCloudProvider(connection, keyProvider, isEnabled) {
    override val id = "xai_grok"
    override val displayName = "xAI Grok Imagine"
    override val description = "Paid. Your own xAI API key. Fast, with audio."

    private val caps = ProviderCapabilities(
        modes = setOf(GenerationMode.TEXT_TO_VIDEO, GenerationMode.IMAGE_TO_VIDEO),
        maxDurationSec = 15.0,
        aspectRatios = AspectRatio.entries.toSet(),
        audio = true,
        requiresInternet = true,
        requiresKey = true,
        free = false,
        speedRank = 8,
        qualityRank = 8,
        multilingualPrompt = true,
    )

    override fun getCapabilities() = caps

    override fun canGenerate(request: GenerationRequest): Boolean = request.mode in caps.modes

    override suspend fun generate(
        request: GenerationRequest,
        outputFile: File,
        onProgress: (ProviderProgress) -> Unit,
    ): ProviderOutput {
        val apiKey = key()
        val seconds = when (request.duration) {
            DurationPreset.SHORT -> 5
            DurationPreset.MEDIUM -> 8
            DurationPreset.LONG -> 12
        }
        val body = JSONObject()
            .put("model", "grok-imagine-video")
            .put("prompt", request.finalPrompt)
            .put("duration", seconds)
            .put("aspect_ratio", request.aspect.label)
        request.firstFrame?.let {
            onProgress(ProgressMsg.sending(displayName))
            body.put("image", JSONObject().put("url", "data:image/jpeg;base64," + base64(it)))
        }
        onProgress(ProviderProgress(JobStage.SUBMITTING, "Connecting to $displayName…"))
        val start = send(
            Request.Builder()
                .url("https://api.x.ai/v1/videos/generations")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(json))
                .build()
        )
        val requestId = start.optString("request_id").ifBlank { start.optString("id") }
        if (requestId.isBlank()) throw ProviderException(ProviderException.Kind.UNKNOWN, "No request id")

        onProgress(ProviderProgress(JobStage.GENERATING, "Generating frames on $displayName…"))
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        var videoUrl = ""
        while (videoUrl.isBlank()) {
            delay(5_000)
            val st = send(
                Request.Builder()
                    .url("https://api.x.ai/v1/videos/$requestId")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            )
            when (st.optString("status").lowercase()) {
                "failed", "expired", "error" ->
                    throw ProviderException(ProviderException.Kind.UNKNOWN, "Generation ${st.optString("status")}")
                else -> videoUrl = st.optJSONObject("video")?.optString("url").orEmpty()
            }
            if (videoUrl.isBlank() && System.currentTimeMillis() > deadline) {
                throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Timed out")
            }
        }
        onProgress(ProviderProgress(JobStage.DOWNLOADING, "Downloading video…"))
        download(Request.Builder().url(videoUrl).get().build(), outputFile)
        return ProviderOutput(outputFile, null)
    }
}

internal object ProgressMsg {
    fun sending(name: String) = ProviderProgress(JobStage.SUBMITTING, "Sending your image to $name…")
}
