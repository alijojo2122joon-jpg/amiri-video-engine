package com.amiri.videoengine

import android.app.Application
import com.amiri.videoengine.ai.jobs.GenerationJobManager
import com.amiri.videoengine.ai.prompt.PromptPreparer
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.ai.providers.cloud.GoogleVeoProvider
import com.amiri.videoengine.ai.providers.cloud.XaiGrokProvider
import com.amiri.videoengine.ai.providers.gradio.GradioClient
import com.amiri.videoengine.ai.providers.gradio.GradioSpaceProvider
import com.amiri.videoengine.ai.providers.gradio.SpaceCatalog
import com.amiri.videoengine.ai.providers.local.LocalProvider
import com.amiri.videoengine.ai.router.ModelRouter
import com.amiri.videoengine.image.ImagePreprocessor
import com.amiri.videoengine.network.ConnectionManager
import com.amiri.videoengine.security.SecretStore
import com.amiri.videoengine.storage.CacheManager
import com.amiri.videoengine.storage.ProjectRepository
import com.amiri.videoengine.storage.ProviderSettingsStore
import com.amiri.videoengine.video.VideoPostProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AmiriApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.projects.cleanupTemporaryFiles()
            container.projects.refresh()
        }
    }
}

/** Simple manual dependency injection: every part is created once, here. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secrets = SecretStore(app)
    val providerSettings = ProviderSettingsStore(app)
    val connection = ConnectionManager(app)
    val projects = ProjectRepository(app)
    val cache = CacheManager(app)
    val images = ImagePreprocessor(app)
    val video = VideoPostProcessor(app)

    private val gradio = GradioClient { secrets.get(SecretStore.HF_TOKEN) }

    private val builtInSpaces = SpaceCatalog.builtIn.map { spec ->
        GradioSpaceProvider(spec, gradio, connection) { providerSettings.isEnabled(spec.providerId, true) }
    }
    val veo = GoogleVeoProvider(connection, { secrets.get(SecretStore.GOOGLE_KEY) }) {
        providerSettings.isEnabled("google_veo", true)
    }
    val xai = XaiGrokProvider(connection, { secrets.get(SecretStore.XAI_KEY) }) {
        providerSettings.isEnabled("xai_grok", true)
    }
    val local = LocalProvider()

    /** Rebuilt on every call so newly added custom Spaces are picked up at once. */
    fun providers(): List<VideoGenerationProvider> {
        val custom = providerSettings.customSpaces().map { cs ->
            val spec = SpaceCatalog.custom(cs)
            GradioSpaceProvider(spec, gradio, connection) { providerSettings.isEnabled(spec.providerId, true) }
        }
        return builtInSpaces + custom + listOf(veo, xai, local)
    }

    val prompts = PromptPreparer(
        tokenProvider = { secrets.get(SecretStore.HF_TOKEN) },
        aiEnabled = { providerSettings.isEnabled(PROMPT_AI_ID, true) },
    )

    val router = ModelRouter({ providers() }, connection)
    val jobs = GenerationJobManager(appScope, router, images, video, projects, prompts)

    companion object {
        const val PROMPT_AI_ID = "prompt_ai"
    }
}
