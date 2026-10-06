package com.amiri.videoengine.ai.scene

import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.providers.gradio.GradioClient
import com.amiri.videoengine.ai.providers.gradio.GradioEndpoint
import com.amiri.videoengine.ai.providers.gradio.GradioParam
import com.amiri.videoengine.network.Http
import com.amiri.videoengine.network.await
import com.amiri.videoengine.network.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Makes the STARTING FRAME of the video match the user's text.
 *
 * Image-to-video models only animate what is already in the first frame; they can't add a
 * new place, person or object the text talks about. So before animating:
 *  - text only  → a still image is generated from the text (FLUX.1 schnell, free),
 *  - photo + text that asks for changes → the photo is edited to match (FLUX.1 Kontext, free).
 * Both run on free Hugging Face Spaces; FLUX schnell also falls back to Hugging Face
 * Inference with the user's free token.
 */
class SceneMaker(
    private val client: GradioClient,
    private val tokenProvider: () -> String?,
) {
    /** Text → image. Returns null if every free option failed. */
    suspend fun createImage(prompt: String, aspect: AspectRatio, out: File): File? {
        val (w, h) = sizeFor(aspect)
        for (space in TEXT_TO_IMAGE_SPACES) {
            try {
                val ep = client.discover(space, GradioClient.Output.IMAGE)
                val data = fill(ep, prompt, w, h, steps = null, uploaded = null)
                val result = client.predict(ep, data) {}
                val url = client.findFileUrl(result, ep, GradioClient.Output.IMAGE) ?: continue
                client.download(url, out)
                if (out.length() > 1024) return out
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                client.forget(space)
            }
        }
        return inferenceTextToImage(prompt, w, h, out)
    }

    /** Photo + instruction → edited photo (same person/subject, changed to match the text). */
    suspend fun editImage(input: File, instruction: String, quality: QualityPreset, out: File): File? {
        val steps = when (quality) {
            QualityPreset.FAST -> 16
            QualityPreset.HIGH -> 22
            QualityPreset.MAXIMUM -> 28
        }
        for (space in IMAGE_EDIT_SPACES) {
            try {
                val ep = client.discover(space, GradioClient.Output.IMAGE)
                val uploaded = client.upload(ep, input)
                val data = fill(ep, instruction, null, null, steps, uploaded)
                val result = client.predict(ep, data) {}
                val url = client.findFileUrl(result, ep, GradioClient.Output.IMAGE) ?: continue
                client.download(url, out)
                if (out.length() > 1024) return out
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                client.forget(space)
            }
        }
        return null
    }

    private fun fill(
        ep: GradioEndpoint,
        prompt: String,
        width: Int?,
        height: Int?,
        steps: Int?,
        uploaded: JSONObject?,
    ): JSONArray {
        val data = JSONArray()
        var imageUsed = false
        for (p in ep.params) {
            val n = p.name.lowercase()
            val v: Any? = when {
                isImage(p) -> if (!imageUsed && uploaded != null) uploaded.also { imageUsed = true } else p.default
                "negative" in n -> p.default
                "prompt" in n || n == "instruction" || n == "text" -> prompt
                "randomize" in n -> true
                "seed" in n -> number(p, Random.nextInt(0, Int.MAX_VALUE).toDouble())
                width != null && "width" in n -> number(p, width.toDouble())
                height != null && "height" in n -> number(p, height.toDouble())
                steps != null && "step" in n -> number(p, steps.toDouble())
                p.hasDefault -> p.default
                else -> null
            }
            data.put(v ?: JSONObject.NULL)
        }
        return data
    }

    private fun isImage(p: GradioParam) =
        p.component.equals("Image", true) || p.component.equals("ImageEditor", true)

    private fun number(p: GradioParam, v: Double): Any =
        if (p.pythonType.contains("int", true)) v.toInt() else v

    private suspend fun inferenceTextToImage(prompt: String, w: Int, h: Int, out: File): File? {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("inputs", prompt)
                    .put("parameters", JSONObject().put("width", w).put("height", h).put("num_inference_steps", 4))
                val req = Request.Builder()
                    .url("https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "image/png")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = Http.client.await(req, client.tracker)
                val type = resp.header("Content-Type").orEmpty()
                if (!resp.isSuccessful || !type.startsWith("image")) {
                    resp.close()
                    return@withContext null
                }
                resp.saveTo(out)
                if (out.length() > 1024) out else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun sizeFor(aspect: AspectRatio): Pair<Int, Int> = when (aspect) {
        AspectRatio.PORTRAIT -> 576 to 1024
        AspectRatio.LANDSCAPE -> 1024 to 576
        AspectRatio.SQUARE -> 832 to 832
    }

    fun cancel() = client.tracker.cancelAll()

    companion object {
        val TEXT_TO_IMAGE_SPACES = listOf("black-forest-labs/FLUX.1-schnell")
        val IMAGE_EDIT_SPACES = listOf("black-forest-labs/FLUX.1-Kontext-Dev")
    }
}
