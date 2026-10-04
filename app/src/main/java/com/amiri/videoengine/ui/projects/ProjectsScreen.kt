package com.amiri.videoengine.ui.projects

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amiri.videoengine.storage.Project
import com.amiri.videoengine.ui.MainViewModel
import com.amiri.videoengine.ui.Screen
import com.amiri.videoengine.ui.components.rememberThumbnail
import com.amiri.videoengine.ui.theme.AmiriColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: MainViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        containerColor = AmiriColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AmiriColors.Background),
                title = { Text("PROJECTS", style = MaterialTheme.typography.labelLarge, color = AmiriColors.Text) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AmiriColors.Text)
                    }
                },
            )
        },
    ) { padding ->
        val done = projects.filter { it.videos.isNotEmpty() }
        if (done.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No videos yet.", color = AmiriColors.TextDim, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(done, key = { it.id }) { p ->
                    ProjectRow(
                        vm = vm,
                        project = p,
                        onOpen = { vm.navigate(Screen.Result(p.id)) },
                        onDelete = { toDelete = p },
                    )
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete project?") },
            text = { Text("The video and its images will be removed from this app. Videos you saved to the Gallery stay there.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p.id)
                    toDelete = null
                }) { Text("DELETE", color = AmiriColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("KEEP") }
            },
            containerColor = AmiriColors.SurfaceHigh,
        )
    }
}

@Composable
private fun ProjectRow(vm: MainViewModel, project: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    val thumb = rememberThumbnail(vm.thumbFile(project), 256)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AmiriColors.Surface),
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                project.prompt,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = AmiriColors.Text,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.createdAt)) +
                    " · " + (project.videos.firstOrNull()?.providerName ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.TextDim,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = AmiriColors.TextDim)
        }
    }
}
