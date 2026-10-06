package com.amiri.videoengine.ai.jobs

import com.amiri.videoengine.ai.model.GenerationRequest
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.JobStep
import com.amiri.videoengine.ai.model.UserInput
import com.amiri.videoengine.ai.prompt.PromptEngine
import com.amiri.videoengine.ai.prompt.PromptPreparer
import com.amiri.videoengine.ai.router.ModelRouter
import com.amiri.videoengine.ai.router.RoutingException
import com.amiri.videoengine.image.ImagePreprocessor
import com.amiri.videoengine.storage.Project
import com.amiri.videoengine.storage.ProjectRepository
import com.amiri.videoengine.storage.VideoEntry
import com.amiri.videoengine.video.VideoPostProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JobState(
    val projectId: String,
    val stage: JobStage,
    val step: JobStep,
    val message: String,
    val providerName: String?,
    val startedAt: Long,
    val error: String?,
    val variation: Int,
    val variations: Int,
    val notes: List<String>,
    val sendsImages: Boolean,
    /** Starting frame that was generated / edited from the text, shown while generating. */
    val previewFrame: String? = null,
)

/**
 * Runs one generation at a time in an app-wide scope, so it keeps going if the
 * screen rotates or you open another screen. States:
 * QUEUED → PREPARING → SUBMITTING → GENERATING → DOWNLOADING → PROCESSING → COMPLETED
 * (or FAILED / CANCELLED). Supports cancel, retry and delete.
 */
class GenerationJobManager(
    private val scope: CoroutineScope,
    private val router: ModelRouter,
    private val images: ImagePreprocessor,
    private val video: VideoPostProcessor,
    private val repo: ProjectRepository,
    private val prompts: PromptPreparer,
    private val scenes: com.amiri.videoengine.ai.scene.SceneMaker,
    private val sceneFromText: () -> Boolean,
    private val editPhoto: () -> Boolean,
) {
    private val _state = MutableStateFlow<JobState?>(null)
    val state: StateFlow<JobState?> = _state.asStateFlow()

    private var job: Job? = null
    private var lastInput: UserInput? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(input: UserInput) {
        if (isRunning) return
        lastInput = input
        job = scope.launch { run(input) }
    }

    fun retry() {
        lastInput?.let { start(it) }
    }

    fun cancel() {
        router.cancel()
        scenes.cancel()
        job?.cancel()
    }

    /** Clears a finished job from the screen. */
    fun dismiss() {
        if (!isRunning) _state.value = null
    }

    suspend fun delete(projectId: String) {
        if (_state.value?.projectId == projectId && isRunning) cancel()
        repo.delete(projectId)
    }

    private fun set(stage: JobStage, step: JobStep, message: String, provider: String? = null) {
        _state.update { cur -> cur?.let { s -> s.copy(stage = stage, step = step, message = message, providerName = provider ?: s.providerName) } }
    }

    private suspend fun run(input: UserInput) {
        val id = "p" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val notes = mutableListOf<String>()
        _state.value = JobState(
            projectId = id,
            stage = JobStage.QUEUED,
            step = JobStep.PREPARE_IMAGES,
            message = "Queued…",
            providerName = null,
            startedAt = System.currentTimeMillis(),
            error = null,
            variation = 1,
            variations = input.advanced.variations.coerceIn(1, 3),
            notes = emptyList(),
            sendsImages = input.firstFrame != null || input.lastFrame != null || input.referenceImage != null,
        )
        val dir = repo.dir(id)
        val videos = mutableListOf<VideoEntry>()
        try {
            // 1) Decide what each image is for.
            set(JobStage.PREPARING, JobStep.PREPARE_IMAGES, "Preparing images…")
            var first = input.firstFrame
            var last = input.lastFrame
            val ref = input.referenceImage
            if (first == null && ref != null) {
                first = ref
                notes += "Your image was used as the starting frame."
            } else if (first != null && last == null && ref != null) {
                if (PromptEngine.suggestsTransition(input.prompt)) {
                    last = ref
                    notes += "Your second image was used as the end frame, because the prompt describes a change."
                } else {
                    notes += "Free engines can't use an extra reference image yet; it was saved in the project but not sent."
                }
            } else if (ref != null) {
                notes += "Free engines can't use an extra reference image yet; it was saved in the project but not sent."
            }
            if (first == null && last != null) {
                first = last
                last = null
                notes += "Only a last frame was given, so it was used as the starting frame."
            }

            // 2) Keep untouched originals in the project, prepare provider copies.
            dir.mkdirs()
            val firstOrig = first?.let { copyInto(it, dir, "first_original") }
            val lastOrig = last?.let { copyInto(it, dir, "last_original") }
            val refOrig = if (ref != null && ref != first && ref != last) copyInto(ref, dir, "reference_original") else null
            val firstReady = firstOrig?.let { images.prepareForProvider(it, input.aspect) }
            val lastReady = lastOrig?.let { images.prepareForProvider(it, input.aspect) }

            // 3) Prompt: understand it (any language) and split it into scene / photo change / motion.
            set(JobStage.PREPARING, JobStep.ANALYZE_PROMPT, "Analyzing prompt…")
            val reviewed = input.englishPrompt?.trim()?.takeIf { it.isNotEmpty() }
            val plan = prompts.plan(
                text = reviewed ?: input.prompt,
                hasPhoto = firstReady != null,
                firstAndLast = firstReady != null && lastReady != null,
            ) { msg -> set(JobStage.PREPARING, JobStep.ANALYZE_PROMPT, msg) }
            if (plan.method != PromptPreparer.Method.ORIGINAL) notes += "Prompt: ${plan.method.label}."
            if (plan.method == PromptPreparer.Method.ORIGINAL && prompts.isNonEnglish(plan.motion)) {
                notes += "Your prompt could not be translated, so the engine may not understand it. Check the internet connection, add your free Hugging Face token, or write it in English."
            }

            // 4) Make the starting frame match the text. Video models only animate what is
            //    already in the first frame, so this is what makes the video follow the prompt.
            var startFrame = firstReady
            val wantsText = input.prompt.isNotBlank()
            if (startFrame == null && wantsText && sceneFromText()) {
                set(JobStage.PREPARING, JobStep.CREATE_SCENE, "Creating the scene from your text…")
                val made = scenes.createImage(plan.scene, input.aspect, File(dir, "first_generated.jpg"))
                if (made != null) {
                    startFrame = images.prepareForProvider(made, input.aspect)
                    _state.update { it?.copy(previewFrame = made.absolutePath) }
                    notes += "The starting frame was created from your text, then animated."
                } else {
                    notes += "The scene could not be pre-drawn right now, so the video was made from text directly."
                }
            } else if (startFrame != null && lastReady == null && plan.edit.isNotBlank() && editPhoto()) {
                set(JobStage.PREPARING, JobStep.CREATE_SCENE, "Changing your photo to match the text…")
                val edited = scenes.editImage(startFrame, plan.edit, input.quality, File(dir, "first_edited.jpg"))
                if (edited != null) {
                    startFrame = images.prepareForProvider(edited, input.aspect)
                    _state.update { it?.copy(previewFrame = edited.absolutePath) }
                    notes += "Your photo was adjusted to match the text (${plan.edit.take(120)}), then animated."
                } else {
                    notes += "Your photo could not be adjusted right now, so only movement from the text was applied."
                }
            }

            val english = plan.motion
            val structured = PromptEngine.analyze(english)
            val baseRequest = GenerationRequest(
                prompt = input.prompt,
                finalPrompt = "",
                negativePrompt = PromptEngine.NEGATIVE_PROMPT,
                structured = structured,
                firstFrame = startFrame,
                lastFrame = lastReady,
                aspect = input.aspect,
                duration = input.duration,
                quality = input.quality,
                seed = input.advanced.seed,
                resolution = input.advanced.resolution,
            )
            val finalPrompt = if (plan.method == PromptPreparer.Method.AI) {
                english
            } else {
                PromptEngine.buildFinalPrompt(structured, baseRequest.mode, input.quality)
            }
            _state.update { it?.copy(notes = notes.toList()) }

            var project = Project(
                id = id,
                createdAt = System.currentTimeMillis(),
                prompt = input.prompt,
                finalPrompt = finalPrompt,
                mode = baseRequest.mode.name,
                aspect = input.aspect.label,
                duration = input.duration.name,
                quality = input.quality.name,
                resolution = input.advanced.resolution.name,
                seed = input.advanced.seed,
                firstImage = firstOrig?.name,
                lastImage = lastOrig?.name,
                referenceImage = refOrig?.name,
                videos = emptyList(),
                notes = notes.toList(),
            )

            // 4) Generate each variation through the router.
            val total = input.advanced.variations.coerceIn(1, 3)
            for (v in 1..total) {
                _state.update { it?.copy(variation = v) }
                val request = baseRequest.copy(
                    finalPrompt = finalPrompt,
                    seed = input.advanced.seed?.plus(v - 1),
                )
                val out = File(dir, "video_$v.mp4")
                val routed = try {
                    router.generate(request, input.advanced.preferredProviderId, out) { p, provider ->
                        val step = when (p.stage) {
                            JobStage.SUBMITTING -> JobStep.CONNECT
                            JobStage.GENERATING -> JobStep.GENERATE
                            JobStage.DOWNLOADING, JobStage.PROCESSING -> JobStep.PROCESS
                            else -> JobStep.CONNECT
                        }
                        set(p.stage, step, p.message, provider?.displayName)
                    }
                } catch (e: RoutingException) {
                    if (videos.isNotEmpty()) {
                        notes += "Only ${videos.size} of $total variations could be made."
                        break
                    }
                    throw e
                }

                set(JobStage.PROCESSING, JobStep.PROCESS, "Processing video…", routed.provider.displayName)
                val info = video.inspect(out)
                    ?: throw RoutingException("The engine returned a file that is not a playable video. Please try again.")
                videos += VideoEntry(
                    fileName = out.name,
                    providerId = routed.provider.id,
                    providerName = routed.provider.displayName,
                    width = info.width,
                    height = info.height,
                    durationMs = info.durationMs,
                    seed = routed.output.seedUsed,
                )
                if (v == 1) video.extractFrame(out, File(dir, "thumb.jpg"), 0, maxSide = 384)
                project = project.copy(videos = videos.toList(), notes = notes.toList())
                repo.save(project)
            }

            set(JobStage.PROCESSING, JobStep.FINALIZE, "Finalizing…")
            val finished = project.copy(videos = videos.toList(), notes = notes.toList())
            repo.save(finished)
            _state.update { it?.copy(stage = JobStage.COMPLETED, step = JobStep.FINALIZE, message = "Done", notes = notes.toList()) }
        } catch (e: CancellationException) {
            if (videos.isEmpty()) dir.deleteRecursively()
            _state.update { it?.copy(stage = JobStage.CANCELLED, message = "Cancelled") }
            throw e
        } catch (e: RoutingException) {
            if (videos.isEmpty()) dir.deleteRecursively()
            _state.update { it?.copy(stage = JobStage.FAILED, error = e.message, message = e.message ?: "Failed", notes = notes.toList()) }
        } catch (e: Exception) {
            if (videos.isEmpty()) dir.deleteRecursively()
            val msg = "Something went wrong while preparing your video. Please try again."
            _state.update { it?.copy(stage = JobStage.FAILED, error = msg, message = msg, notes = notes.toList()) }
        }
    }

    private fun copyInto(src: File, dir: File, base: String): File {
        val ext = src.extension.ifBlank { "jpg" }
        val out = File(dir, "$base.$ext")
        src.copyTo(out, overwrite = true)
        return out
    }
}
