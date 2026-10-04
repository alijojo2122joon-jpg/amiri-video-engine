package com.amiri.videoengine.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amiri.videoengine.ui.generation.GenerationScreen
import com.amiri.videoengine.ui.home.HomeScreen
import com.amiri.videoengine.ui.projects.ProjectsScreen
import com.amiri.videoengine.ui.result.ResultScreen
import com.amiri.videoengine.ui.settings.SettingsScreen
import com.amiri.videoengine.ui.theme.AmiriColors
import com.amiri.videoengine.ui.theme.AmiriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmiriTheme {
                val vm: MainViewModel = viewModel()
                AppRoot(vm, keepScreenOn = { on ->
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                })
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel, keepScreenOn: (Boolean) -> Unit) {
    val job by vm.jobState.collectAsStateWithLifecycle()
    val running = job?.stage?.isActive == true
    // Keep the screen awake while a video is generating so Android doesn't pause the app.
    LaunchedEffect(running) { keepScreenOn(running) }

    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    Box(Modifier.fillMaxSize().background(AmiriColors.Background)) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Generating -> GenerationScreen(vm)
            is Screen.Result -> ResultScreen(vm, s.projectId)
            Screen.Projects -> ProjectsScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
        }
    }
}
