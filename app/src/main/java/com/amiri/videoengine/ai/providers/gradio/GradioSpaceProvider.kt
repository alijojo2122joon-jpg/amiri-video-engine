package com.amiri.videoengine.ai.providers.gradio

import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.GenerationMode
import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.ProviderCapabilities
import com.amiri.videoengine.ai.model.ProviderException
import com.amiri.videoengine.ai.model.ProviderOutput
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.providers.ProviderProgress
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.network.ConnectionManager
import com.amiri.videoengine.network.ConnectionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Static description of a free Hugging Face Space engine. */
data class SpaceSpec(
    val providerId: String,
    val spaceId: String,
    val displayName: String,
    val description: String,
    val capabilities: ProviderCapabilities,
    val builtIn: Boolean,
)

/**
 * A free video engine running on a public Hugging Face Space (ZeroGPU).
 * Parameters are matched by name against the Space's live API description,
 * so one class works for many different Spaces.
 */
class GradioSpaceProvider(
    val spec: SpaceSpec,
    private val client: GradioClient,
    private val connection: ConnectionManager,
    private val isEnabled: () -> Boolean,
) : VideoGenerationProvider {

    override val id: String get() = spec.providerId
    override val displayName: String get() = spec.displayName
    override val description: String get() = spec.description

    override fun getCapabilities(): ProviderCapabilities = spec.capabilities

    override fun canGenerate(request: GenerationRequest): Boolean =
        request.mode in spec.capabilities.modes

    override suspend fun getStatus(): ProviderStatus {
        if (!isEnabled()) return ProviderStatus.DISABLED
        if (connection.current() == ConnectionState.OFFLINE) return ProviderStatus.OFFLINE
        return when (client.runtimeStage(spec.spaceId)) {
            null, "RUNNING", "RUNNING_BUILDING", "RUNNING_APP_STARTING", "APP_STARTING", "SLEEPING" -> ProviderStatus.AVAILABLE
            else -> ProviderStatus.UNAVAILABLE
        }
    }

    override fun cancelGeneration() {
        client.tracker.cancelAll()
    }

    override suspend fun generate(
        request: GenerationRequest,
        outputFile: File,
        onProgress: (ProviderProgress) -> Unit,
    ): ProviderOutput {
        onProgress(ProviderProgress(JobStage.SUBMITTING, "Connecting to ${spec.displayName}…"))
        val ep = try {
            client.discover(spec.spaceId)
        } catch (e: ProviderException) {
            client.forget(spec.spaceId)
            throw e
        }

        val imageParams = ep.params.filter { isImageParam(it) }
        val roles = assignImageRoles(imageParams)
        when (request.mode) {
            GenerationMode.FIRST_LAST_TO_VIDEO ->
                if (roles.values.count { it == ImageRole.FIRST || it == ImageRole.LAST } < 2) unsupported()
            GenerationMode.IMAGE_TO_VIDEO ->
                if (roles.values.none { it == ImageRole.FIRST }) unsupported()
            GenerationMode.TEXT_TO_VIDEO ->
                if (imageParams.any { !it.hasDefault }) unsupported()
        }

        val uploads = mutableMapOf<ImageRole, JSONObject>()
        if (request.firstFrame != null || request.lastFrame != null) {
            onProgress(
                ProviderProgress(
                    JobStage.SUBMITTING,
                    "Sending your image to ${spec.displayName} (Hugging Face)…",
                )
            )
        }
        request.firstFrame?.let { uploads[ImageRole.FIRST] = client.upload(ep, it) }
        request.lastFrame?.let { uploads[ImageRole.LAST] = client.upload(ep, it) }

        onProgress(ProviderProgress(JobStage.GENERATING, "Generating frames on ${spec.displayName} (free GPU)…"))

        val result = try {
            client.predict(ep, buildData(ep, request, roles, uploads, useDefaults = false)) {
                onProgress(ProviderProgress(JobStage.GENERATING, "Generating frames on ${spec.displayName}…"))
            }
        } catch (e: ProviderException) {
            if (e.kind == ProviderException.Kind.UNKNOWN || e.kind == ProviderException.Kind.BAD_REQUEST) {
                // Second attempt with the Space's own default settings (safe values).
                onProgress(ProviderProgress(JobStage.GENERATING, "Retrying ${spec.displayName} with safe settings…"))
                client.forget(spec.spaceId)
                client.predict(ep, buildData(ep, request, roles, uploads, useDefaults = true)) {}
            } else {
                throw e
            }
        }

        val videoUrl = findVideoUrl(result, ep)
            ?: throw ProviderException(ProviderException.Kind.UNKNOWN, "Engine returned no video")
        onProgress(ProviderProgress(JobStage.DOWNLOADING, "Downloading video…"))
        client.download(videoUrl, outputFile)
        if (!outputFile.exists() || outputFile.length() < 1024) {
            throw ProviderException(ProviderException.Kind.UNKNOWN, "Downloaded video is empty")
        }
        val seed = (0 until result.length()).map { result.opt(it) }.firstOrNull { it is Number } as? Number
        return ProviderOutput(outputFile, seed?.toLong())
    }

    private fun unsupported(): Nothing =
        throw ProviderException(ProviderException.Kind.UNSUPPORTED, "${spec.displayName} cannot do this request")

    private enum class ImageRole { FIRST, LAST, NONE }

    private fun isImageParam(p: GradioParam): Boolean =
        p.component.equals("Image", true) || p.component.equals("ImageEditor", true)

    private fun assignImageRoles(images: List<GradioParam>): Map<String, ImageRole> {
        val roles = linkedMapOf<String, ImageRole>()
        if (images.size == 1) {
            roles[images[0].name] = ImageRole.FIRST
            return roles
        }
        images.forEach { p ->
            val n = p.name.lowercase() + " " + p.label.lowercase()
            if ("end" in n || "last" in n || "final" in n) roles[p.name] = ImageRole.LAST
        }
        images.forEach { p ->
            if (p.name !in roles) {
                roles[p.name] = if (roles.values.contains(ImageRole.FIRST)) ImageRole.NONE else ImageRole.FIRST
            }
        }
        return roles
    }

    private fun buildData(
        ep: GradioEndpoint,
        req: GenerationRequest,
        roles: Map<String, ImageRole>,
        uploads: Map<ImageRole, JSONObject>,
        useDefaults: Boolean,
    ): JSONArray {
        val (width, height) = targetSize(ep.params, req)
        val duration = min(req.duration.seconds, spec.capabilities.maxDurationSec)
        val data = JSONArray()
        for (p in ep.params) {
            val n = p.name.lowercase()
            val value: Any? = when {
                isImageParam(p) -> roles[p.name]?.let { uploads[it] }
                p.component.equals("Video", true) -> null
                "negative" in n -> if (p.hasDefault) p.default else req.negativePrompt
                "prompt" in n -> req.finalPrompt
                n == "mode" || n.endsWith("_mode") -> pickMode(p, req.mode)
                "randomize" in n -> req.seed == null
                "seed" in n -> numberFor(p, (req.seed ?: Random.nextInt(0, Int.MAX_VALUE).toLong()).toDouble())
                !useDefaults && "height" in n -> numberFor(p, height.toDouble())
                !useDefaults && "width" in n -> numberFor(p, width.toDouble())
                !useDefaults && ("duration" in n || "seconds" in n) -> numberFor(p, duration)
                p.hasDefault -> p.default
                else -> null
            }
            data.put(value ?: JSONObject.NULL)
        }
        return data
    }

    private fun numberFor(p: GradioParam, v: Double): Any =
        if (p.pythonType.contains("int", true)) v.roundToInt() else v

    private fun pickMode(p: GradioParam, mode: GenerationMode): Any? {
        val options = p.enumValues
        if (options.isEmpty()) {
            val d = p.default as? String ?: return p.default
            // Common Gradio convention: "text-to-video" / "image-to-video".
            if (!d.contains("-to-")) return d
            return when (mode) {
                GenerationMode.TEXT_TO_VIDEO -> "text-to-video"
                else -> "image-to-video"
            }
        }
        val wanted = when (mode) {
            GenerationMode.TEXT_TO_VIDEO -> listOf("text")
            GenerationMode.IMAGE_TO_VIDEO -> listOf("image")
            GenerationMode.FIRST_LAST_TO_VIDEO -> listOf("first", "last", "interp", "image")
        }
        for (w in wanted) options.firstOrNull { it.contains(w, true) }?.let { return it }
        return p.default ?: options.first()
    }

    private fun targetSize(params: List<GradioParam>, req: GenerationRequest): Pair<Int, Int> {
        val dh = (params.firstOrNull { "height" in it.name.lowercase() }?.default as? Number)?.toInt() ?: 512
        val dw = (params.firstOrNull { "width" in it.name.lowercase() }?.default as? Number)?.toInt() ?: 704
        val long = round32(max(dh, dw) * req.resolution.scale)
        val short169 = round32(long * 9f / 16f)
        return when (req.aspect) {
            AspectRatio.PORTRAIT -> short169 to long
            AspectRatio.LANDSCAPE -> long to short169
            AspectRatio.SQUARE -> round32(min(dh, dw) * req.resolution.scale).let { it to it }
        }
    }

    private fun round32(v: Float): Int = max(256, ((v / 32f).roundToInt()) * 32)

    private fun findVideoUrl(result: JSONArray, ep: GradioEndpoint): String? {
        val candidates = mutableListOf<String>()
        for (i in 0 until result.length()) {
            when (val v = result.opt(i)) {
                is JSONObject -> {
                    val obj = v.optJSONObject("video") ?: v
                    urlOf(obj, ep)?.let { candidates += it }
                }
                is String -> if (looksLikeVideo(v)) candidates += "${ep.root}/file=$v"
            }
        }
        return candidates.firstOrNull { looksLikeVideo(it) } ?: candidates.firstOrNull()
    }

    private fun urlOf(o: JSONObject, ep: GradioEndpoint): String? =
        o.strOrNull("url") ?: o.strOrNull("path")?.let { "${ep.root}/file=$it" }

    private fun looksLikeVideo(s: String): Boolean {
        val l = s.lowercase().substringBefore('?')
        return l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".mov")
    }
}
