package dev.tplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.screens.RootScreen
import dev.tplay.ui.theme.TplayTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
            ): T = MainViewModel((application as TermPlayApp).container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsState()
            TplayTheme(systemAccent = settings.systemAccent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MediaPermissionGate(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // App is closing/backgrounding: flush the recent-songs cache in one batch write.
        vm.flushRecentSongs()
    }

    override fun onDestroy() {
        vm.flushRecentSongs()
        super.onDestroy()
    }
}

@Composable
private fun MediaPermissionGate(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions = buildList {
        add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Without this the media notification never shows on Android 13+.
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Required for android.media.audiofx.Visualizer (used by VBASS haptic bass).
        add(Manifest.permission.RECORD_AUDIO)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.READ_MEDIA_AUDIO] == true ||
            result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        ) {
            vm.refreshLibrary()
        }
    }
    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        else vm.refreshLibrary()
    }
    RootScreen(vm)
}
