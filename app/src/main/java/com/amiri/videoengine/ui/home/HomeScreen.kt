package com.amiri.videoengine.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.amiri.videoengine.R
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amiri.videoengine.ai.model.AspectRatio
import com.amiri.videoengine.ai.model.DurationPreset
import com.amiri.videoengine.ai.model.QualityPreset
import com.amiri.videoengine.ai.model.ResolutionPreset
import com.amiri.videoengine.network.ConnectionState
import com.amiri.videoengine.ui.ImageSlot
import com.amiri.videoengine.ui.MainViewModel
import com.amiri.videoengine.ui.Screen
import com.amiri.videoengine.ui.components.ChoiceRow
import com.amiri.videoengine.ui.components.ImageSlotCard
import com.amiri.videoengine.ui.theme.AmiriColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val job by vm.jobState.collectAsStateWithLifecycle()
    var pendingSlot by remember { mutableStateOf(ImageSlot.FIRST) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickImage(pendingSlot, uri)
    }
    fun pick(slot: ImageSlot) {
        pendingSlot = slot
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        containerColor = AmiriColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AmiriColors.Background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.logo_badge),
                        contentDescription = "Amiri Video Engine",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("AMIRI VIDEO ENGINE", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp, letterSpacing = 2.sp), color = AmiriColors.Text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connection) {
                                            ConnectionState.ONLINE -> AmiriColors.Ok
                                            ConnectionState.LIMITED -> AmiriColors.Accent
                                            ConnectionState.OFFLINE -> AmiriColors.Danger
                                        }
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(connection.label, style = MaterialTheme.typography.labelSmall, color = AmiriColors.TextDim)
                        }
                    }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Projects) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Projects", tint = AmiriColors.Text)
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = AmiriColors.Text)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            val running = job?.stage?.isActive == true
            if (!vm.hasHfToken) TokenBanner(vm)
            if (running) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .clip(shape)
                        .background(AmiriColors.Accent.copy(alpha = 0.12f))
                        .border(1.dp, AmiriColors.AccentDim, shape)
                        .clickable { vm.navigate(Screen.Generating) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "A video is being generated — tap to view",
                        color = AmiriColors.Accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ImageSlot.entries.forEach { slot ->
                    ImageSlotCard(
                        label = slot.label,
                        file = vm.images[slot],
                        onPick = { pick(slot) },
                        onClear = { vm.clearImage(slot) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "All images are optional. One image animates it; first + last makes a transition.",
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(18.dp))
            Text("PROMPT", style = MaterialTheme.typography.labelMedium, color = AmiriColors.TextDim)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.prompt,
                onValueChange = { vm.prompt = it },
                placeholder = { Text("Describe the video you want...", color = AmiriColors.TextDim) },
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmiriColors.Accent,
                    unfocusedBorderColor = AmiriColors.Outline,
                    focusedContainerColor = AmiriColors.Surface,
                    unfocusedContainerColor = AmiriColors.Surface,
                    cursorColor = AmiriColors.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            EnglishPromptSection(vm)

            Spacer(Modifier.height(18.dp))
            ChoiceRow("Aspect ratio", AspectRatio.entries.toList(), vm.aspect, { it.label }, { vm.chooseAspect(it) })
            Spacer(Modifier.height(14.dp))
            ChoiceRow("Duration", DurationPreset.entries.toList(), vm.duration, { it.label }, { vm.duration = it })
            Spacer(Modifier.height(14.dp))
            ChoiceRow("Quality", QualityPreset.entries.toList(), vm.quality, { it.label }, { vm.quality = it })

            Spacer(Modifier.height(10.dp))
            AdvancedSection(vm)

            if (vm.images.isNotEmpty()) {
                Text(
                    "When you generate, your images are sent to the online engine that makes the video " +
                        "(free Hugging Face Spaces, or Google / xAI only if you turned them on).",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmiriColors.TextDim,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (connection == ConnectionState.OFFLINE) {
                Text(
                    "Offline generation is not available for the current model. You can still prepare your video and browse your projects.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmiriColors.Accent,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            vm.formError?.let {
                Text(it, color = AmiriColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.generate() },
                enabled = connection != ConnectionState.OFFLINE,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AmiriColors.Accent, contentColor = AmiriColors.Background),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) {
                Text(if (running) "VIEW PROGRESS" else "GENERATE VIDEO", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AdvancedSection(vm: MainViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.advancedOpen = !vm.advancedOpen }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ADVANCED", style = MaterialTheme.typography.labelMedium, color = AmiriColors.TextDim, modifier = Modifier.weight(1f))
        Icon(
            if (vm.advancedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = AmiriColors.TextDim,
        )
    }
    if (!vm.advancedOpen) return

    val providers = remember(vm.settingsVersion) { vm.providers().filter { it.getCapabilities().modes.isNotEmpty() } }
    Text("MODEL", style = MaterialTheme.typography.labelMedium, color = AmiriColors.TextDim)
    Spacer(Modifier.height(6.dp))
    val options = listOf<Pair<String?, String>>(null to "Auto (recommended)") + providers.map { it.id to it.displayName }
    options.forEach { (id, name) ->
        val selected = vm.preferredProviderId == id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { vm.preferredProviderId = id }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (selected) AmiriColors.Accent else AmiriColors.Outline, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(if (selected) AmiriColors.Accent else AmiriColors.Background)
            )
            Spacer(Modifier.width(10.dp))
            Text(name, color = AmiriColors.Text, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = vm.seedText,
        onValueChange = { v -> vm.seedText = v.filter { it.isDigit() }.take(10) },
        label = { Text("Seed (empty = random)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmiriColors.Accent,
            unfocusedBorderColor = AmiriColors.Outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(14.dp))
    ChoiceRow("Resolution", ResolutionPreset.entries.toList(), vm.resolution, {
        when (it) {
            ResolutionPreset.AUTO -> "Auto"
            ResolutionPreset.LOW -> "Lower"
            ResolutionPreset.HIGH -> "Higher"
        }
    }, { vm.resolution = it })
    Spacer(Modifier.height(14.dp))
    ChoiceRow("Variations", listOf(1, 2, 3), vm.variations, { it.toString() }, { vm.variations = it })
    Spacer(Modifier.height(12.dp))
    Text(
        "FPS, motion strength and guidance: NOT AVAILABLE — the free engines use their own tuned values.",
        style = MaterialTheme.typography.labelSmall,
        color = AmiriColors.TextDim,
    )
}

/**
 * Shows exactly what the video engine will read. Persian prompts are translated / rewritten
 * to English here, and the user can correct the English before generating.
 */
@Composable
private fun EnglishPromptSection(vm: MainViewModel) {
    val text = vm.prompt.trim()
    if (text.isEmpty()) return
    val nonEnglish = vm.isNonEnglish(text)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "WHAT THE ENGINE WILL READ",
            style = MaterialTheme.typography.labelMedium,
            color = AmiriColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        if (vm.preparingEnglish) {
            CircularProgressIndicator(color = AmiriColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            TextButton(onClick = { vm.previewEnglish() }) {
                Text(if (vm.englishIsCurrent) "REDO" else "PREVIEW", color = AmiriColors.Accent)
            }
        }
    }
    if (vm.englishIsCurrent) {
        OutlinedTextField(
            value = vm.englishPrompt,
            onValueChange = { vm.englishPrompt = it },
            minLines = 3,
            maxLines = 8,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmiriColors.Accent,
                unfocusedBorderColor = AmiriColors.AccentDim,
                focusedContainerColor = AmiriColors.Surface,
                unfocusedContainerColor = AmiriColors.Surface,
                cursorColor = AmiriColors.Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            (vm.englishMethod ?: "") + " · You can edit it. The video is planned from this text.",
            style = MaterialTheme.typography.labelSmall,
            color = AmiriColors.TextDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        Text(
            if (nonEnglish) {
                "Free video models only understand English. Tap PREVIEW to see your prompt in English and fix it if needed — or just generate and it is translated automatically."
            } else {
                "Tap PREVIEW to see (and edit) the exact prompt the engine will receive."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (nonEnglish) AmiriColors.Accent else AmiriColors.TextDim,
        )
    }
}

/** The free Hugging Face token unlocks real prompt understanding and more free GPU time. */
@Composable
private fun TokenBanner(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(shape)
            .background(AmiriColors.Accent.copy(alpha = 0.10f))
            .border(1.dp, AmiriColors.AccentDim, shape)
            .padding(14.dp),
    ) {
        Text("Make it understand you better (free)", style = MaterialTheme.typography.titleMedium, color = AmiriColors.Accent)
        Text(
            "Connect a free Hugging Face token: the AI then understands Persian exactly, builds the scene you describe, and you get more free video time.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmiriColors.Text,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "1) Tap GET TOKEN and sign up / log in  2) Create the token (type Read) and copy it  3) Paste it in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = AmiriColors.TextDim,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://huggingface.co/settings/tokens/new?tokenType=read&description=Amiri%20Video%20Engine"),
                        )
                    )
                } catch (_: Exception) {
                }
            }) { Text("GET TOKEN", color = AmiriColors.Accent) }
            TextButton(onClick = { vm.navigate(Screen.Settings) }) { Text("PASTE IN SETTINGS", color = AmiriColors.Accent) }
        }
    }
}
