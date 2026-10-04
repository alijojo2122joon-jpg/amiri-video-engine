package com.amiri.videoengine.ui.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amiri.videoengine.ai.model.JobStage
import com.amiri.videoengine.ai.model.JobStep
import com.amiri.videoengine.ui.MainViewModel
import com.amiri.videoengine.ui.theme.AmiriColors
import kotlinx.coroutines.delay

@Composable
fun GenerationScreen(vm: MainViewModel) {
    val job by vm.jobState.collectAsStateWithLifecycle()
    val state = job

    if (state == null) {
        // Nothing running (e.g. app restarted). Go back home.
        LaunchedEffect(Unit) { vm.back() }
        return
    }

    LaunchedEffect(state.stage, state.projectId) {
        if (state.stage == JobStage.COMPLETED) vm.onJobFinishedSeen(state.projectId)
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.stage.isActive) {
        while (state.stage.isActive) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - state.startedAt) / 1000).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmiriColors.Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        val title = when (state.stage) {
            JobStage.FAILED -> "Could not generate"
            JobStage.CANCELLED -> "Cancelled"
            JobStage.COMPLETED -> "Done"
            else -> "Generating..."
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = AmiriColors.Text)
        Spacer(Modifier.height(8.dp))
        state.providerName?.let {
            Text("Engine: $it", style = MaterialTheme.typography.bodyMedium, color = AmiriColors.Accent)
        }
        if (state.variations > 1) {
            Text(
                "Variation ${state.variation} of ${state.variations}",
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
            )
        }
        Spacer(Modifier.height(24.dp))

        if (state.stage.isActive) {
            // Indeterminate on purpose: free engines don't report a real percentage,
            // so we don't invent one.
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = AmiriColors.Accent,
                trackColor = AmiriColors.Surface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "%d:%02d".format(elapsed / 60, elapsed % 60),
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
            )
            Spacer(Modifier.height(20.dp))
        }

        val currentIndex = JobStep.entries.indexOf(state.step)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            JobStep.entries.forEachIndexed { index, step ->
                val done = index < currentIndex || state.stage == JobStage.COMPLETED
                val active = index == currentIndex && state.stage.isActive
                StepRow(step.label, done, active)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.stage == JobStage.FAILED) AmiriColors.Danger else AmiriColors.TextDim,
            textAlign = TextAlign.Center,
        )
        if (state.stage.isActive) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Free engines can take 1–5 minutes, longer if many people are using them. You can leave this screen; it keeps working while the app is open.",
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
                textAlign = TextAlign.Center,
            )
        }
        state.notes.forEach {
            Spacer(Modifier.height(6.dp))
            Text("• $it", style = MaterialTheme.typography.labelSmall, color = AmiriColors.TextDim, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(30.dp))
        when (state.stage) {
            JobStage.FAILED, JobStage.CANCELLED -> {
                Button(
                    onClick = { vm.retryJob() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AmiriColors.Accent, contentColor = AmiriColors.Background),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("RETRY", style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.leaveFailedJob() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("BACK", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) }
            }
            JobStage.COMPLETED -> CircularProgressIndicator(color = AmiriColors.Accent)
            else -> {
                OutlinedButton(
                    onClick = { vm.back() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("HIDE", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.cancelJob() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("CANCEL", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Danger) }
            }
        }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> AmiriColors.Accent
                        active -> AmiriColors.Accent.copy(alpha = 0.2f)
                        else -> AmiriColors.Surface
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                done -> Icon(Icons.Filled.Check, contentDescription = null, tint = AmiriColors.Background, modifier = Modifier.size(14.dp))
                active -> CircularProgressIndicator(color = AmiriColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                active -> AmiriColors.Text
                done -> AmiriColors.TextDim
                else -> AmiriColors.Outline
            },
        )
    }
}
