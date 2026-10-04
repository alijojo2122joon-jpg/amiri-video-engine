package com.amiri.videoengine.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amiri.videoengine.AmiriApp
import com.amiri.videoengine.ai.model.AdvancedOptions
import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.DurationPreset
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.model.ResolutionPreset
import com.amiri.videoengine.ai.model.UserInput
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.network.ConnectionState
import com.amiri.videoengine.security.SecretStore
import com.amiri.videoengine.storage.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Generating : Screen
    data class Result(val projectId: String) : Screen
    data object Projects : Screen
    data object Settings : Screen
}

enum class ImageSlot(val label: String) {
    FIRST("FIRST FRAME"),
    LAST("LAST FRAME"),
    REFERENCE("REFERENCE"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as AmiriApp).container

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    // ---- Home form ----
    var prompt by mutableStateOf("")
    val images = mutableStateMapOf<ImageSlot, File>()
    var aspect by mutableStateOf(AspectRatio.PORTRAIT)
        private set
    private var aspectTouched = false
    var duration by mutableStateOf(DurationPreset.SHORT)
    var quality by mutableStateOf(QualityPreset.HIGH)
    var advancedOpen by mutableStateOf(false)
    var preferredProviderId by mutableStateOf<String?>(null)
    var seedText by mutableStateOf("")
    var resolution by mutableStateOf(ResolutionPreset.AUTO)
    var variations by mutableIntStateOf(1)
    var formError by mutableStateOf<String?>(null)

    // ---- Settings ----
    val statuses = mutableStateMapOf<String, ProviderStatus>()
    var cacheBytes by mutableStateOf(0L)
        private set
    var settingsVersion by mutableIntStateOf(0)
        private set

    val jobState = c.jobs.state
    val projects = c.projects.projects
    val connection = c.connection.state

    fun navigate(to: Screen) {
        formError = null
        screen = to
        if (to == Screen.Settings) refreshSettingsInfo()
        if (to == Screen.Projects) viewModelScope.launch { c.projects.refresh() }
    }

    fun back() {
        navigate(Screen.Home)
    }

    private fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Images ----------------

    fun pickImage(slot: ImageSlot, uri: Uri) {
        viewModelScope.launch {
            try {
                val base = slot.name.lowercase() + "_" + System.currentTimeMillis()
                val file = c.images.importOriginal(uri, c.projects.draftsDir, base)
                images[slot]?.delete()
                images[slot] = file
                autoAspect()
            } catch (_: Exception) {
                toast("That image could not be opened. Try a JPG or PNG.")
            }
        }
    }

    fun clearImage(slot: ImageSlot) {
        images.remove(slot)?.delete()
        autoAspect()
    }

    fun chooseAspect(a: AspectRatio) {
        aspect = a
        aspectTouched = true
    }

    /** Until the user picks a ratio, follow the shape of the main image. */
    private fun autoAspect() {
        if (aspectTouched) return
        val main = images[ImageSlot.FIRST] ?: images[ImageSlot.REFERENCE] ?: images[ImageSlot.LAST] ?: return
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) { c.images.imageSize(main) } ?: return@launch
            if (!aspectTouched) aspect = AspectRatio.closestTo(size.first, size.second)
        }
    }

    // ---------------- Generate ----------------

    fun generate() {
        formError = null
        val hasImage = images.isNotEmpty()
        val text = prompt.trim()
        if (text.isEmpty() && !hasImage) {
            formError = "Describe the video you want, or add an image."
            return
        }
        if (connection.value == ConnectionState.OFFLINE) {
            formError = "Offline generation is not available for the current model."
            return
        }
        if (c.jobs.isRunning) {
            navigate(Screen.Generating)
            return
        }
        val seed = seedText.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (seedText.isNotBlank() && seed == null) {
            formError = "Seed must be a whole number."
            return
        }
        val input = UserInput(
            prompt = text.ifEmpty { "natural, gentle motion that brings the image to life" },
            firstFrame = images[ImageSlot.FIRST],
            lastFrame = images[ImageSlot.LAST],
            referenceImage = images[ImageSlot.REFERENCE],
            aspect = aspect,
            duration = duration,
            quality = quality,
            advanced = AdvancedOptions(
                preferredProviderId = preferredProviderId,
                seed = seed,
                resolution = resolution,
                variations = variations,
            ),
        )
        c.jobs.start(input)
        navigate(Screen.Generating)
    }

    fun cancelJob() = c.jobs.cancel()

    fun retryJob() {
        c.jobs.retry()
    }

    fun onJobFinishedSeen(projectId: String) {
        c.jobs.dismiss()
        navigate(Screen.Result(projectId))
    }

    fun leaveFailedJob() {
        c.jobs.dismiss()
        navigate(Screen.Home)
    }

    // ---------------- Results ----------------

    fun project(id: String): Project? = c.projects.get(id)

    fun projectFile(p: Project, name: String): File = c.projects.file(p, name)

    fun thumbFile(p: Project): File = c.projects.thumb(p)

    fun saveToGallery(p: Project, videoName: String) {
        viewModelScope.launch {
            try {
                c.video.saveToGallery(c.projects.file(p, videoName), "Amiri_${p.id}_$videoName")
                toast("Saved to Gallery → Movies/Amiri Video Engine")
            } catch (_: Exception) {
                toast("Could not save to the gallery.")
            }
        }
    }

    fun share(p: Project, videoName: String) {
        try {
            getApplication<Application>().startActivity(c.video.shareIntent(c.projects.file(p, videoName)))
        } catch (_: Exception) {
            toast("No app available to share with.")
        }
    }

    /** Puts a project's prompt, images and settings back into the form. */
    fun loadIntoForm(p: Project, thenGenerate: Boolean) {
        viewModelScope.launch {
            clearForm()
            prompt = p.prompt
            fun restore(name: String?, slot: ImageSlot): File? {
                if (name == null) return null
                val src = c.projects.file(p, name)
                if (!src.exists()) return null
                val dst = File(c.projects.draftsDir, slot.name.lowercase() + "_" + System.nanoTime() + "." + src.extension)
                src.copyTo(dst, overwrite = true)
                return dst
            }
            val restored = withContext(Dispatchers.IO) {
                mapOf(
                    ImageSlot.FIRST to restore(p.firstImage, ImageSlot.FIRST),
                    ImageSlot.LAST to restore(p.lastImage, ImageSlot.LAST),
                    ImageSlot.REFERENCE to restore(p.referenceImage, ImageSlot.REFERENCE),
                )
            }
            restored.forEach { (slot, file) -> if (file != null) images[slot] = file }
            aspect = AspectRatio.fromLabel(p.aspect)
            aspectTouched = true
            duration = DurationPreset.fromName(p.duration)
            quality = QualityPreset.fromName(p.quality)
            resolution = ResolutionPreset.fromName(p.resolution)
            if (thenGenerate) generate() else navigate(Screen.Home)
        }
    }

    /** Uses the last frame of a video as the first frame of a new one (to make longer stories). */
    fun continueFromLastFrame(p: Project, videoName: String) {
        viewModelScope.launch {
            val out = File(c.projects.draftsDir, "first_" + System.currentTimeMillis() + ".jpg")
            val frame = c.video.extractFrame(c.projects.file(p, videoName), out, -1)
            if (frame == null) {
                toast("Could not read the last frame.")
                return@launch
            }
            clearForm()
            images[ImageSlot.FIRST] = frame
            aspect = AspectRatio.fromLabel(p.aspect)
            aspectTouched = true
            duration = DurationPreset.fromName(p.duration)
            quality = QualityPreset.fromName(p.quality)
            navigate(Screen.Home)
        }
    }

    fun newVideo() {
        clearForm()
        navigate(Screen.Home)
    }

    private fun clearForm() {
        images.values.forEach { it.delete() }
        images.clear()
        prompt = ""
        aspectTouched = false
        formError = null
        seedText = ""
        variations = 1
        preferredProviderId = null
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { c.jobs.delete(id) }
    }

    // ---------------- Settings ----------------

    fun providers(): List<VideoGenerationProvider> = c.providers()

    fun isProviderEnabled(id: String): Boolean = c.providerSettings.isEnabled(id, true)

    fun setProviderEnabled(id: String, enabled: Boolean) {
        c.providerSettings.setEnabled(id, enabled)
        settingsVersion++
        refreshStatuses()
    }

    fun maskedSecret(name: String): String? = c.secrets.masked(name)

    fun saveSecret(name: String, value: String) {
        c.secrets.put(name, value)
        settingsVersion++
        toast(if (value.isBlank()) "Removed" else "Saved securely")
        refreshStatuses()
    }

    fun addCustomSpace(input: String): Boolean {
        val id = com.amiri.videoengine.storage.ProviderSettingsStore.normalizeSpaceId(input)
        if (id == null) {
            toast("Use the form owner/space-name")
            return false
        }
        c.providerSettings.addCustomSpace(id)
        settingsVersion++
        refreshStatuses()
        return true
    }

    fun removeCustomSpace(spaceId: String) {
        c.providerSettings.removeCustomSpace(spaceId)
        settingsVersion++
    }

    fun customSpaces() = c.providerSettings.customSpaces()

    fun refreshStatuses() {
        viewModelScope.launch {
            c.providers().forEach { p ->
                launch { statuses[p.id] = p.getStatus() }
            }
        }
    }

    fun refreshSettingsInfo() {
        refreshStatuses()
        viewModelScope.launch { cacheBytes = c.cache.sizeBytes() }
    }

    fun clearCache() {
        viewModelScope.launch {
            c.cache.clear()
            cacheBytes = c.cache.sizeBytes()
            toast("Temporary cache cleared")
        }
    }

    companion object {
        val HF = SecretStore.HF_TOKEN
        val GOOGLE = SecretStore.GOOGLE_KEY
        val XAI = SecretStore.XAI_KEY
    }
}
