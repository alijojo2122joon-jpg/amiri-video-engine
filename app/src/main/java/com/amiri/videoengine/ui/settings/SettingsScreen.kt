package com.amiri.videoengine.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.amiri.videoengine.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.amiri.videoengine.ai.model.ProviderStatus
import com.amiri.videoengine.ai.providers.VideoGenerationProvider
import com.amiri.videoengine.ai.providers.gradio.GradioSpaceProvider
import com.amiri.videoengine.ui.MainViewModel
import com.amiri.videoengine.ui.components.SectionTitle
import com.amiri.videoengine.ui.theme.AmiriColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val version = vm.settingsVersion
    val providers = remember(version) { vm.providers() }
    val spaces = providers.filterIsInstance<GradioSpaceProvider>()

    Scaffold(
        containerColor = AmiriColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AmiriColors.Background),
                title = { Text("SETTINGS", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AmiriColors.Text)
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
            Image(
                painter = painterResource(R.drawable.logo_full),
                contentDescription = "Amiri Video Engine",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(18.dp))
            Text("AI PROVIDERS", style = MaterialTheme.typography.headlineSmall, color = AmiriColors.Text)

            SectionTitle("Free engines · Hugging Face")
            Body(
                "These run open-source video models on free Hugging Face GPUs. No payment. " +
                    "Each day there is a limited amount of free GPU time. Adding a free Hugging Face " +
                    "token (huggingface.co → Settings → Access Tokens, type \"Read\") gives you more free time."
            )
            Spacer(Modifier.height(10.dp))
            SecretField(vm, MainViewModel.HF, "Hugging Face token (free, optional)", version)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AmiriColors.Surface)
                    .padding(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AI prompt helper", style = MaterialTheme.typography.titleMedium, color = AmiriColors.Text)
                    Text(
                        "Uses your free token to turn any language into a precise English video prompt. " +
                            "Without a token, Persian is translated on your phone instead.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmiriColors.TextDim,
                    )
                }
                val aiOn = remember(version) { vm.isProviderEnabled(MainViewModel.PROMPT_AI) }
                Switch(
                    checked = aiOn,
                    onCheckedChange = { vm.setProviderEnabled(MainViewModel.PROMPT_AI, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AmiriColors.Accent, checkedThumbColor = AmiriColors.Background),
                )
            }
            Spacer(Modifier.height(8.dp))
            spaces.forEach { p -> ProviderRow(vm, p, version, removable = !p.spec.builtIn) }
            TextButton(onClick = { vm.refreshStatuses() }) { Text("CHECK STATUS", color = AmiriColors.Accent) }

            SectionTitle("Add another free Space")
            Body("Paste a Hugging Face Space that makes videos (for example owner/space-name). The engine reads its API automatically.")
            var spaceInput by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = spaceInput,
                    onValueChange = { spaceInput = it },
                    singleLine = true,
                    placeholder = { Text("owner/space-name", color = AmiriColors.TextDim) },
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { if (vm.addCustomSpace(spaceInput)) spaceInput = "" }) {
                    Text("ADD", color = AmiriColors.Accent)
                }
            }

            SectionTitle("Paid engines (optional)")
            Body("Only used if you add your own API key. Keys are encrypted with the Android Keystore and never shown, logged or exported.")
            Spacer(Modifier.height(8.dp))
            ProviderRow(vm, vm.c.veo, version, removable = false)
            SecretField(vm, MainViewModel.GOOGLE, "Google Gemini API key", version)
            Spacer(Modifier.height(14.dp))
            ProviderRow(vm, vm.c.xai, version, removable = false)
            SecretField(vm, MainViewModel.XAI, "xAI API key", version)

            SectionTitle("On-device engine")
            Body("Local generation unavailable for this model on this device. Image preparation, frames, saving and history all run on your phone.")

            SectionTitle("Storage")
            Body(String.format(Locale.US, "Temporary cache: %.1f MB", vm.cacheBytes / 1_048_576.0))
            TextButton(onClick = { vm.clearCache() }) { Text("CLEAR TEMPORARY CACHE", color = AmiriColors.Accent) }

            SectionTitle("Privacy")
            Body(
                "No ads, no analytics, no tracking, no account. Projects stay on this phone. " +
                    "Your prompt and images are sent only to the engine that makes the video, at the moment you press Generate."
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AmiriColors.TextDim)
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AmiriColors.Accent,
    unfocusedBorderColor = AmiriColors.Outline,
    cursorColor = AmiriColors.Accent,
)

@Composable
private fun ProviderRow(vm: MainViewModel, p: VideoGenerationProvider, version: Int, removable: Boolean) {
    val enabled = remember(version, p.id) { vm.isProviderEnabled(p.id) }
    val status = vm.statuses[p.id]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AmiriColors.Surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = MaterialTheme.typography.titleMedium, color = AmiriColors.Text)
            Text(p.description, style = MaterialTheme.typography.labelSmall, color = AmiriColors.TextDim)
            status?.let {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (it) {
                        ProviderStatus.AVAILABLE -> AmiriColors.Ok
                        ProviderStatus.DISABLED -> AmiriColors.TextDim
                        else -> AmiriColors.Accent
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (removable && p is GradioSpaceProvider) {
            IconButton(onClick = { vm.removeCustomSpace(p.spec.spaceId) }) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = AmiriColors.TextDim)
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { vm.setProviderEnabled(p.id, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = AmiriColors.Accent, checkedThumbColor = AmiriColors.Background),
        )
    }
}

@Composable
private fun SecretField(vm: MainViewModel, name: String, label: String, version: Int) {
    val masked = remember(version, name) { vm.maskedSecret(name) }
    var input by remember(version) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(masked ?: "Not set", color = AmiriColors.TextDim) },
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { if (input.isNotBlank()) vm.saveSecret(name, input) }) {
                Text("SAVE", color = AmiriColors.Accent)
            }
            if (masked != null) {
                TextButton(onClick = { vm.saveSecret(name, "") }) { Text("REMOVE", color = AmiriColors.TextDim) }
                Text(
                    "Saved: $masked",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmiriColors.TextDim,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}
