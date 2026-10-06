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
import kotlinx.coroutines.withTimeoutOrNull
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
            translate(text)?.let { return Prepared(it, Method.TRANSLATED) }
        }
        return Prepared(text, Method.ORIGINAL)
    }

    /** Persian/Dari → English: on the phone first (offline), then the free MyMemory service. */
    suspend fun translate(text: String): String? =
        withTimeoutOrNull(25_000) { translateOnDevice(text) } ?: translateOnline(text)

    // ---------------- Director: split the request into scene / photo change / motion ----------------

    data class Plan(
        /** What the first frame should show (used to create it when there is no photo). */
        val scene: String,
        /** What to change in the user's photo so it matches the text. Empty = keep the photo. */
        val edit: String,
        /** The video prompt: actions in order + camera. */
        val motion: String,
        val method: Method,
    )

    suspend fun plan(
        text: String,
        hasPhoto: Boolean,
        firstAndLast: Boolean,
        onStatus: (String) -> Unit = {},
    ): Plan {
        val clean = text.trim()
        val token = tokenProvider()
        if (clean.isNotEmpty() && !token.isNullOrBlank() && aiEnabled()) {
            onStatus("Understanding your prompt with AI…")
            direct(clean, hasPhoto, firstAndLast, token)?.let { return it }
        }
        val english = when {
            clean.isEmpty() -> ""
            isNonEnglish(clean) -> {
                onStatus("Translating your prompt to English…")
                translate(clean)
            }
            else -> clean
        }
        return if (english == null) {
            Plan(clean, "", clean, Method.ORIGINAL)
        } else {
            Plan(english, "", english, if (english == clean) Method.ORIGINAL else Method.TRANSLATED)
        }
    }

    private suspend fun direct(text: String, hasPhoto: Boolean, firstAndLast: Boolean, token: String): Plan? {
        val photoRule = when {
            firstAndLast ->
                "The user gave a FIRST and a LAST image. \"edit\" must be \"\". \"motion\" describes how the first image changes into the last one."
            hasPhoto ->
                "The user gave a PHOTO that will be the first frame. In \"edit\" write a short instruction (max 40 words) for an image editor that changes the photo so that its STARTING state matches the request — e.g. a different place/background, time of day, weather, clothing, added objects or animals that must be visible from the start. Always end it with \"Keep the same person, face and pose.\" If the photo only needs to MOVE (actions, gestures, camera), \"edit\" must be \"\"."
            else ->
                "There is no photo. \"edit\" must be \"\"."
        }
        val system = """
You are the director of an AI video generator. The user's request may be in Persian/Dari or any language. Answer ONLY with one JSON object:
{"scene": "...", "edit": "...", "motion": "..."}
- "scene": English description of the FIRST FRAME as a still photo: who/what, look, clothing, place, time, weather, lighting, framing, style. Only what is visible at the very start, before anything moves. 30-70 words.
- "motion": English video prompt: the subject and EVERY action the user asked for, in order, with direction and speed, plus camera movement. Present tense, concrete and visible, 40-90 words. Mention the place and key details again.
- $photoRule
Keep every detail the user asked for. Never add new story events, never drop anything, never refuse. No text outside the JSON.
""".trim()
        for (model in MODELS) {
            try {
                val raw = chat(model, system, text, token) ?: continue
                val json = raw.replace(Regex("(?s)<think>.*?</think>"), "")
                val a = json.indexOf('{')
                val b = json.lastIndexOf('}')
                if (a < 0 || b <= a) continue
                val o = JSONObject(json.substring(a, b + 1))
                val motion = cleanup(o.optString("motion"))
                val scene = cleanup(o.optString("scene")).ifBlank { motion }
                val edit = if (hasPhoto && !firstAndLast) cleanup(o.optString("edit")) else ""
                if (motion.length < 8 || isNonEnglish(motion) || isNonEnglish(scene) || isNonEnglish(edit)) continue
                return Plan(scene, edit, motion, Method.AI)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // next model
            }
        }
        return null
    }

    // ---------------- Free online translation fallback (MyMemory, no key needed) ----------------

    private suspend fun translateOnline(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val parts = chunk(text, 450)
            val out = StringBuilder()
            for (part in parts) {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("api.mymemory.translated.net")
                    .addPathSegment("get")
                    .addQueryParameter("q", part)
                    .addQueryParameter("langpair", "fa|en")
                    .build()
                val piece = Http.quick.await(Request.Builder().url(url).get().build()).use { r ->
                    if (!r.isSuccessful) return@use null
                    JSONObject(r.body?.string().orEmpty()).optJSONObject("responseData")
                        ?.optString("translatedText")?.takeIf { it.isNotBlank() }
                } ?: return@withContext null
                if (out.isNotEmpty()) out.append(' ')
                out.append(piece)
            }
            out.toString().trim().takeIf { it.isNotEmpty() && !isNonEnglish(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!؟?،,\\n])\\s*"))
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.length + s.length + 1 > max && cur.isNotEmpty()) {
                out += cur.toString(); cur.setLength(0)
            }
            if (s.length > max) {
                s.chunked(max).forEach { out += it }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
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
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
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
