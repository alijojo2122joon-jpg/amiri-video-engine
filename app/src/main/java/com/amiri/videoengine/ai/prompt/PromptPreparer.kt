package com.amiri.videoengine.ai.prompt

import com.amiri.videoengine.ai.model.GenerationMode
import com.amiri.videoengine.network.Http
import com.amiri.videoengine.network.await
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns what the user typed (Persian, English, anything) into the clear English
 * prompt that video models understand best. Free video models (LTX especially)
 * do not understand Persian at all, which is the main reason a video ignores the prompt.
 *
 * Order:
 *  1. AI rewrite (free Hugging Face Inference with the user's own free token):
 *     translates AND turns the idea into a precise, motion-focused prompt,
 *     without adding or removing anything the user asked for.
 *  2. On-device Google ML Kit translation Persian → English (free, offline after
 *     a one-time ~30 MB download).
 *  3. The original text.
 */
class PromptPreparer(
    private val tokenProvider: () -> String?,
    private val aiEnabled: () -> Boolean,
) {
    enum class Method(val label: String) {
        AI("Rewritten by AI"),
        TRANSLATED("Translated on your phone"),
        ORIGINAL("Used as you wrote it"),
    }

    data class Prepared(val english: String, val method: Method)

    private val nonLatin = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    fun isNonEnglish(text: String): Boolean = nonLatin.containsMatchIn(text)

    suspend fun prepare(original: String, mode: GenerationMode, onStatus: (String) -> Unit = {}): Prepared {
        val text = original.trim()
        if (text.isEmpty()) return Prepared(text, Method.ORIGINAL)

        val token = tokenProvider()
        if (!token.isNullOrBlank() && aiEnabled()) {
            onStatus("Understanding your prompt with AI…")
            aiRewrite(text, mode, token)?.let { return Prepared(it, Method.AI) }
        }
        if (isNonEnglish(text)) {
            onStatus("Translating your prompt to English…")
            translateOnDevice(text)?.let { return Prepared(it, Method.TRANSLATED) }
        }
        return Prepared(text, Method.ORIGINAL)
    }

    // ---------------- AI rewrite (Hugging Face Inference Providers) ----------------

    private suspend fun aiRewrite(text: String, mode: GenerationMode, token: String): String? {
        val modeHint = when (mode) {
            GenerationMode.TEXT_TO_VIDEO ->
                "There is no input image: describe the subject, the setting and the motion."
            GenerationMode.IMAGE_TO_VIDEO ->
                "The video starts from the user's own photo. The model already sees the photo, so focus on WHAT MOVES and HOW " +
                    "(actions, gestures, camera movement); refer to the subject simply (\"the man\", \"the cat\")."
            GenerationMode.FIRST_LAST_TO_VIDEO ->
                "The video goes from the user's first image to their last image. Describe the motion and change that happens in between."
        }
        val system = """
You write prompts for an AI video generator. Convert the user's request (it may be in Persian/Dari or any language) into ONE English prompt.
Rules:
- Keep EVERY detail the user asked for: who/what, every action and their order, direction, speed, emotions, colors, numbers, clothing, place, time, weather, camera instructions, style.
- Never add new people, objects or story events. Never drop or soften anything. Never refuse.
- Make motion concrete and visible, in present tense (e.g. "slowly turns her head to the left and smiles").
- Add camera, lighting or style words ONLY if the user gave none, and keep them neutral.
- $modeHint
- 40 to 90 words. Output ONLY the prompt text, no quotes, no explanations.
""".trim()
        for (model in MODELS) {
            try {
                val out = chat(model, system, text, token) ?: continue
                val clean = cleanup(out)
                if (clean.length >= 8 && !isNonEnglish(clean)) return clean
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // try the next model
            }
        }
        return null
    }

    private suspend fun chat(model: String, system: String, user: String, token: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.3)
                .put("max_tokens", 700)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
            val req = Request.Builder()
                .url("https://router.huggingface.co/v1/chat/completions")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.quick.newBuilder()
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .await(req)
                .use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = JSONObject(r.body?.string().orEmpty())
                    o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        ?.takeIf { it.isNotBlank() }
                }
        }

    private fun cleanup(raw: String): String {
        var s = raw
        // Some reasoning models wrap thoughts in <think>…</think>.
        s = s.replace(Regex("(?s)<think>.*?</think>"), "")
        s = s.trim().removePrefix("Prompt:").removePrefix("prompt:").trim()
        s = s.trim('"', '“', '”', '\'', '`').trim()
        s = s.lines().filter { it.isNotBlank() }.joinToString(" ")
        return s.take(1200)
    }

    // ---------------- On-device translation (Google ML Kit) ----------------

    private suspend fun translateOnDevice(text: String): String? {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.PERSIAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
            translator.translate(text).awaitTask()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        /** Tried in order; ":fastest" lets Hugging Face pick any provider that serves the model. */
        private val MODELS = listOf(
            "Qwen/Qwen3-235B-A22B-Instruct-2507:fastest",
            "meta-llama/Llama-3.3-70B-Instruct:fastest",
            "openai/gpt-oss-120b:fastest",
        )
    }
}
