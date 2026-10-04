package com.amiri.videoengine.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VideoEntry(
    val fileName: String,
    val providerId: String,
    val providerName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val seed: Long?,
)

data class Project(
    val id: String,
    val createdAt: Long,
    val prompt: String,
    val finalPrompt: String,
    val mode: String,
    val aspect: String,
    val duration: String,
    val quality: String,
    val resolution: String,
    val seed: Long?,
    /** File names of untouched originals inside the project folder. */
    val firstImage: String?,
    val lastImage: String?,
    val referenceImage: String?,
    val videos: List<VideoEntry>,
    val notes: List<String>,
)

/**
 * Local project history: one folder per project in app-private storage with
 * project.json, the original images, the videos and a thumbnail.
 * Nothing is uploaded anywhere. API keys are never written here.
 */
class ProjectRepository(context: Context) {
    val root: File = File(context.filesDir, "projects").apply { mkdirs() }
    val draftsDir: File = File(context.filesDir, "drafts").apply { mkdirs() }

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun dir(id: String): File = File(root, id)
    fun file(project: Project, name: String): File = File(dir(project.id), name)
    fun thumb(project: Project): File = File(dir(project.id), "thumb.jpg")

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id } ?: read(File(dir(id), "project.json"))

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { d -> read(File(d, "project.json")) }.orEmpty()
            .sortedByDescending { it.createdAt }
        _projects.value = list
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        val d = dir(project.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(toJson(project).toString(2))
        val target = File(d, "project.json")
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        val others = _projects.value.filter { it.id != project.id }
        _projects.value = (others + project).sortedByDescending { it.createdAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        _projects.value = _projects.value.filter { it.id != id }
    }

    /** Removes half-written files left by a crash or a killed download. */
    suspend fun cleanupTemporaryFiles() = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) }
            .forEach { it.delete() }
    }

    private fun read(f: File): Project? = try {
        if (!f.exists()) null else fromJson(JSONObject(f.readText()))
    } catch (_: Exception) {
        null
    }

    private fun toJson(p: Project): JSONObject {
        val vids = JSONArray()
        p.videos.forEach { v ->
            vids.put(
                JSONObject()
                    .put("file", v.fileName)
                    .put("providerId", v.providerId)
                    .put("providerName", v.providerName)
                    .put("width", v.width)
                    .put("height", v.height)
                    .put("durationMs", v.durationMs)
                    .put("seed", v.seed ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("id", p.id)
            .put("createdAt", p.createdAt)
            .put("prompt", p.prompt)
            .put("finalPrompt", p.finalPrompt)
            .put("mode", p.mode)
            .put("aspect", p.aspect)
            .put("duration", p.duration)
            .put("quality", p.quality)
            .put("resolution", p.resolution)
            .put("seed", p.seed ?: JSONObject.NULL)
            .put("firstImage", p.firstImage ?: JSONObject.NULL)
            .put("lastImage", p.lastImage ?: JSONObject.NULL)
            .put("referenceImage", p.referenceImage ?: JSONObject.NULL)
            .put("videos", vids)
            .put("notes", JSONArray(p.notes))
    }

    private fun fromJson(o: JSONObject): Project {
        val vids = o.optJSONArray("videos") ?: JSONArray()
        val notes = o.optJSONArray("notes") ?: JSONArray()
        fun s(k: String): String? = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
        return Project(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt"),
            prompt = o.optString("prompt"),
            finalPrompt = o.optString("finalPrompt"),
            mode = o.optString("mode"),
            aspect = o.optString("aspect"),
            duration = o.optString("duration"),
            quality = o.optString("quality"),
            resolution = o.optString("resolution"),
            seed = if (o.isNull("seed")) null else o.optLong("seed"),
            firstImage = s("firstImage"),
            lastImage = s("lastImage"),
            referenceImage = s("referenceImage"),
            videos = (0 until vids.length()).mapNotNull { i ->
                val v = vids.optJSONObject(i) ?: return@mapNotNull null
                VideoEntry(
                    fileName = v.optString("file"),
                    providerId = v.optString("providerId"),
                    providerName = v.optString("providerName"),
                    width = v.optInt("width"),
                    height = v.optInt("height"),
                    durationMs = v.optLong("durationMs"),
                    seed = if (v.isNull("seed")) null else v.optLong("seed"),
                )
            },
            notes = (0 until notes.length()).map { notes.optString(it) },
        )
    }
}

/** Temporary cache (processed images etc.). Projects and keys are never touched. */
class CacheManager(private val context: Context) {
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
