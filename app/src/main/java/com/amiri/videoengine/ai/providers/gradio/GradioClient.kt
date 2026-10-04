package com.amiri.videoengine.ai.providers.gradio

import com.amiri.videoengine.ai.model.ProviderException
import com.amiri.videoengine.network.CallTracker
import com.amiri.videoengine.network.Http
import com.amiri.videoengine.network.await
import com.amiri.videoengine.network.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class GradioParam(
    val name: String,
    val label: String,
    val component: String,
    val hasDefault: Boolean,
    val default: Any?,
    val enumValues: List<String>,
    val pythonType: String,
)

data class GradioEndpoint(
    val baseUrl: String,
    val prefix: String,
    val apiName: String,
    val params: List<GradioParam>,
    val returnComponents: List<String>,
) {
    val root: String get() = baseUrl + prefix
}

/**
 * Minimal, dependency-free client for the public Gradio HTTP API that every
 * Hugging Face Space exposes (`/info`, `/upload`, `/call/<api>` + SSE result).
 * The Space's API is discovered at runtime, so Spaces that change their
 * parameters keep working without an app update.
 */
class GradioClient(private val tokenProvider: () -> String?) {
    val tracker = CallTracker()
    private val endpointCache = ConcurrentHashMap<String, GradioEndpoint>()

    fun forget(spaceId: String) {
        endpointCache.remove(spaceId)
    }

    private fun isHfHost(url: String): Boolean {
        val host = try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { "" }
        return host.endsWith(".hf.space") || host == "huggingface.co" || host.endsWith(".huggingface.co")
    }

    private fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        val token = tokenProvider()
        if (!token.isNullOrBlank() && isHfHost(url)) b.header("Authorization", "Bearer $token")
        return b
    }

    /** Returns the Space's lifecycle stage (RUNNING, SLEEPING, RUNTIME_ERROR...), or null if unknown. */
    suspend fun runtimeStage(spaceId: String): String? = withContext(Dispatchers.IO) {
        try {
            Http.quick.await(request("https://huggingface.co/api/spaces/$spaceId/runtime").get().build()).use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string().orEmpty()).optString("stage").ifBlank { null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveBaseUrl(spaceId: String): String {
        val fallback = "https://" + spaceId.lowercase().replace("/", "-").replace("_", "-").replace(".", "-") + ".hf.space"
        return try {
            Http.quick.await(request("https://huggingface.co/api/spaces/$spaceId/host").get().build()).use { r ->
                if (!r.isSuccessful) return@use fallback
                val host = JSONObject(r.body?.string().orEmpty()).optString("host").trim()
                when {
                    host.isBlank() -> fallback
                    host.startsWith("http") -> host.removeSuffix("/")
                    else -> "https://" + host.removeSuffix("/")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun getJson(url: String): JSONObject? = try {
        Http.client.await(request(url).get().build(), tracker).use { r ->
            if (!r.isSuccessful) return@use null
            JSONObject(r.body?.string().orEmpty())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Finds the Space's video-generation endpoint and its parameter list. */
    suspend fun discover(spaceId: String): GradioEndpoint = withContext(Dispatchers.IO) {
        endpointCache[spaceId]?.let { return@withContext it }
        val base = resolveBaseUrl(spaceId)

        val config = getJson("$base/config")
        val prefixes = buildList {
            config?.let { c -> if (!c.isNull("api_prefix")) add(c.optString("api_prefix").removeSuffix("/")) }
            add("/gradio_api")
            add("")
        }.distinct()

        var info: JSONObject? = null
        var prefix = ""
        for (p in prefixes) {
            val candidate = getJson("$base$p/info")
            if (candidate != null && candidate.has("named_endpoints")) {
                info = candidate
                prefix = p
                break
            }
        }
        if (info == null) {
            throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Space API not reachable: $spaceId")
        }

        val named = info.optJSONObject("named_endpoints") ?: JSONObject()
        var best: GradioEndpoint? = null
        var bestScore = Int.MIN_VALUE
        val keys = named.keys()
        while (keys.hasNext()) {
            val apiName = keys.next()
            val ep = named.optJSONObject(apiName) ?: continue
            val params = parseParams(ep.optJSONArray("parameters"))
            val returns = ep.optJSONArray("returns")
            val returnComponents = (0 until (returns?.length() ?: 0)).map {
                returns!!.optJSONObject(it)?.optString("component").orEmpty()
            }
            val hasVideo = returnComponents.any { it.contains("video", ignoreCase = true) }
            val hasPrompt = params.any { it.name.contains("prompt", true) && !it.name.contains("negative", true) }
            var score = 0
            if (hasVideo) score += 10 else score -= 100
            if (hasPrompt) score += 5
            if (apiName.contains("generate", true)) score += 3
            if (apiName.contains("video", true)) score += 1
            if (score > bestScore) {
                bestScore = score
                best = GradioEndpoint(base, prefix, apiName, params, returnComponents)
            }
        }
        val chosen = best
        if (chosen == null || bestScore < 0) {
            throw ProviderException(ProviderException.Kind.UNSUPPORTED, "No video endpoint in $spaceId")
        }
        endpointCache[spaceId] = chosen
        chosen
    }

    private fun parseParams(arr: JSONArray?): List<GradioParam> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = p.strOrNull("label") ?: "param_$i"
            val name = p.strOrNull("parameter_name")
                ?: label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val type = p.optJSONObject("type")
            val enumArr = type?.optJSONArray("enum")
            val enums = (0 until (enumArr?.length() ?: 0)).map { enumArr!!.optString(it) }
            val defaultValue = if (p.has("parameter_default") && !p.isNull("parameter_default")) p.opt("parameter_default") else null
            GradioParam(
                name = name,
                label = label,
                component = p.optString("component"),
                hasDefault = p.optBoolean("parameter_has_default", false),
                default = defaultValue,
                enumValues = enums,
                pythonType = p.optJSONObject("python_type")?.optString("type").orEmpty(),
            )
        }
    }

    /** Uploads a local file to the Space and returns Gradio's FileData JSON for it. */
    suspend fun upload(ep: GradioEndpoint, file: File, mime: String = "image/jpeg"): JSONObject = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val resp = try {
            Http.client.await(request("${ep.root}/upload").post(body).build(), tracker)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Upload failed", e)
        }
        resp.use { r ->
            if (!r.isSuccessful) throw httpError(r.code, "upload")
            val arr = JSONArray(r.body?.string().orEmpty())
            val path = arr.optString(0)
            if (path.isBlank()) throw ProviderException(ProviderException.Kind.UNKNOWN, "Upload returned no path")
            JSONObject()
                .put("path", path)
                .put("orig_name", file.name)
                .put("mime_type", mime)
                .put("meta", JSONObject().put("_type", "gradio.FileData"))
        }
    }

    /**
     * Runs the endpoint and waits for the result over Server-Sent Events.
     * [onEvent] receives "generating" while the GPU works.
     */
    suspend fun predict(ep: GradioEndpoint, data: JSONArray, onEvent: (String) -> Unit): JSONArray =
        withContext(Dispatchers.IO) {
            val callUrl = "${ep.root}/call/${ep.apiName.trimStart('/')}"
            val payload = JSONObject().put("data", data).toString()
                .toRequestBody("application/json".toMediaType())
            val eventId = try {
                Http.client.await(request(callUrl).post(payload).build(), tracker).use { r ->
                    if (!r.isSuccessful) throw httpError(r.code, "call")
                    JSONObject(r.body?.string().orEmpty()).optString("event_id")
                }
            } catch (e: IOException) {
                throw ProviderException(ProviderException.Kind.NETWORK, "Could not reach engine", e)
            }
            if (eventId.isBlank()) throw ProviderException(ProviderException.Kind.UNKNOWN, "No event id")

            val streamReq = request("$callUrl/$eventId").header("Accept", "text/event-stream").get().build()
            val call = Http.client.newCall(streamReq)
            tracker.add(call)
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { r -> readStream(r, onEvent) }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw ProviderException(ProviderException.Kind.NETWORK, "Connection to engine was lost", e)
                } finally {
                    watcher.cancel()
                    tracker.remove(call)
                }
            }
        }

    private suspend fun readStream(r: Response, onEvent: (String) -> Unit): JSONArray {
        if (!r.isSuccessful) throw httpError(r.code, "stream")
        val source = r.body?.source() ?: throw ProviderException(ProviderException.Kind.UNKNOWN, "Empty stream")
        var event = ""
        val dataLines = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (dataLines.isNotEmpty()) dataLines.append('\n')
                    dataLines.append(line.removePrefix("data:").trim())
                }
                line.isEmpty() -> {
                    val data = dataLines.toString()
                    dataLines.setLength(0)
                    when (event) {
                        "complete" -> {
                            return try {
                                JSONArray(data)
                            } catch (_: Exception) {
                                throw ProviderException(ProviderException.Kind.UNKNOWN, "Unreadable result")
                            }
                        }
                        "error" -> throw classifyError(data)
                        "generating", "process_starts", "progress" -> onEvent("generating")
                        else -> Unit // heartbeat
                    }
                    event = ""
                }
            }
        }
        throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Engine closed the connection")
    }

    suspend fun download(url: String, out: File) = withContext(Dispatchers.IO) {
        val resp = try {
            Http.client.await(request(url).get().build(), tracker)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Download failed", e)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw httpError(code, "download")
        }
        try {
            resp.saveTo(out)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "Download interrupted", e)
        }
    }

    private fun httpError(code: Int, stage: String): ProviderException {
        val kind = when (code) {
            401, 403 -> ProviderException.Kind.AUTH
            404, 422 -> ProviderException.Kind.BAD_REQUEST
            429 -> ProviderException.Kind.QUOTA
            else -> ProviderException.Kind.UNAVAILABLE
        }
        return ProviderException(kind, "HTTP $code at $stage")
    }

    companion object {
        fun classifyError(raw: String?): ProviderException {
            val text = raw.orEmpty()
            val t = text.lowercase()
            val kind = when {
                "quota" in t || ("exceeded" in t && "gpu" in t) -> ProviderException.Kind.QUOTA
                "no gpu" in t || "gpu task aborted" in t || "queue is full" in t ||
                    "too many" in t || "unavailable" in t || "sleeping" in t -> ProviderException.Kind.UNAVAILABLE
                "login" in t || "token" in t || "unauthorized" in t -> ProviderException.Kind.AUTH
                else -> ProviderException.Kind.UNKNOWN
            }
            return ProviderException(kind, text.take(300).ifBlank { "Engine error" })
        }

        fun fileData(path: String, name: String): JSONObject = JSONObject()
            .put("path", path)
            .put("orig_name", name)
            .put("meta", JSONObject().put("_type", "gradio.FileData"))
    }
}

internal fun JSONObject.strOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
