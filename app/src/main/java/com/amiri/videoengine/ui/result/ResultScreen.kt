package com.amiri.videoengine.ui.result

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amiri.videoengine.ui.MainViewModel
import com.amiri.videoengine.ui.components.ChoiceRow
import com.amiri.videoengine.ui.components.LoopingVideo
import com.amiri.videoengine.ui.theme.AmiriColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(vm: MainViewModel, projectId: String) {
    val all by vm.projects.collectAsStateWithLifecycle()
    val project = remember(all, projectId) { all.firstOrNull { it.id == projectId } ?: vm.project(projectId) }

    if (project == null || project.videos.isEmpty()) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }

    var index by remember(projectId) { mutableIntStateOf(0) }
    val entry = project.videos[index.coerceIn(0, project.videos.lastIndex)]
    var showPrompt by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AmiriColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AmiriColors.Background),
                title = { Text("RESULT", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            val aspect = if (entry.height > 0) entry.width.toFloat() / entry.height else 9f / 16f
            LoopingVideo(
                file = vm.projectFile(project, entry.fileName),
                aspect = aspect,
                modifier = Modifier.fillMaxWidth(if (aspect < 1f) 0.82f else 1f),
            )
            Text(
                "Tap the video to pause / play",
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (project.videos.size > 1) {
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    "Variation",
                    project.videos.indices.toList(),
                    index,
                    { (it + 1).toString() },
                    { index = it },
                )
            }

            Spacer(Modifier.height(16.dp))
            InfoRow("Engine used", entry.providerName)
            InfoRow("Resolution", "${entry.width} × ${entry.height}")
            InfoRow("Duration", String.format(Locale.US, "%.1f s", entry.durationMs / 1000.0))
            entry.seed?.let { InfoRow("Seed", it.toString()) }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.saveToGallery(project, entry.fileName) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AmiriColors.Accent, contentColor = AmiriColors.Background),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("SAVE", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(
                    onClick = { vm.share(project, entry.fileName) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("SHARE", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.loadIntoForm(project, thenGenerate = true) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("REGENERATE", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) }
                OutlinedButton(
                    onClick = { vm.newVideo() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("NEW VIDEO", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.continueFromLastFrame(project, entry.fileName) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("CONTINUE FROM LAST FRAME", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Accent) }
            Text(
                "Makes the next clip start where this one ends — chain clips into a longer story.",
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )

            project.notes.forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = AmiriColors.TextDim, modifier = Modifier.padding(top = 10.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (showPrompt) "HIDE PROMPT SENT ▲" else "SHOW PROMPT SENT ▼",
                style = MaterialTheme.typography.labelMedium,
                color = AmiriColors.TextDim,
                modifier = Modifier
                    .clickable { showPrompt = !showPrompt }
                    .padding(vertical = 6.dp),
            )
            if (showPrompt) {
                Text(project.finalPrompt, style = MaterialTheme.typography.bodyMedium, color = AmiriColors.TextDim)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AmiriColors.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AmiriColors.Text)
    }
}
