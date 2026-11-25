package com.ghostcapture.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ghostcapture.app.data.HudProfileStore
import com.ghostcapture.app.data.RecorderSettings
import com.ghostcapture.app.data.SettingsStore
import com.ghostcapture.app.data.bitrateForQuality
import com.ghostcapture.app.data.resolutionForQuality
import com.ghostcapture.app.recorder.RecorderService
import com.ghostcapture.app.recorder.RecorderState
import com.ghostcapture.app.ui.screens.GalleryScreen
import com.ghostcapture.app.ui.screens.HomeScreen
import com.ghostcapture.app.ui.screens.MaskEditorScreen
import com.ghostcapture.app.ui.screens.ProfilesScreen
import com.ghostcapture.app.ui.screens.SettingsScreen
import com.ghostcapture.app.ui.theme.GhostCaptureTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var profileStore: HudProfileStore

    private var pendingSettings: RecorderSettings? = null
    private var countdownValue by mutableStateOf<Int?>(null)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val settings = pendingSettings
            pendingSettings = null
            if (result.resultCode == Activity.RESULT_OK && result.data != null && settings != null) {
                val (w, h) = resolutionForQuality(settings.quality)
                val density = resources.displayMetrics.densityDpi
                val intent = RecorderService.startIntent(
                    this,
                    result.resultCode,
                    result.data!!,
                    w,
                    h,
                    density,
                    settings.fps,
                    settings.audio == "mic",
                    bitrateForQuality(settings.quality)
                )
                startForegroundService(intent)
            } else {
                RecorderState.error.value = "Screen capture was cancelled."
            }
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                pendingSettings = pendingSettings?.copy(audio = "off")
                RecorderState.notice.value =
                    "Microphone permission denied — recording without audio."
            }
            requestNotificationThenProceed()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            proceedToCountdown()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        profileStore = HudProfileStore(applicationContext)
        setContent {
            GhostCaptureTheme {
                val countdown = countdownValue
                LaunchedEffect(countdown) {
                    if (countdown != null) {
                        if (countdown > 0) {
                            delay(800L)
                            countdownValue = countdown - 1
                        } else {
                            countdownValue = null
                            launchProjection()
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNav(
                        settingsStore = settingsStore,
                        profileStore = profileStore,
                        onRecordRequested = { settings -> requestRecord(settings) },
                        onStopRequested = { stopRecording() },
                        onPauseRequested = { pauseRecording() },
                        onResumeRequested = { resumeRecording() },
                        showTouchesOn = isShowTouchesOn(),
                        onOpenDevSettings = { openDevSettings() }
                    )
                    if (countdown != null && countdown > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = countdown.toString(),
                                fontSize = 120.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    /** Entry point from Home: permissions, then countdown, then projection. */
    fun requestRecord(settings: RecorderSettings) {
        pendingSettings = settings
        if (settings.audio == "mic" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationThenProceed()
    }

    private fun requestNotificationThenProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToCountdown()
    }

    private fun proceedToCountdown() {
        val settings = pendingSettings ?: return
        if (settings.countdown) {
            countdownValue = 3
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val settings = pendingSettings ?: return
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    fun stopRecording() {
        startService(RecorderService.actionIntent(this, RecorderService.ACTION_STOP))
    }

    fun pauseRecording() {
        startService(RecorderService.actionIntent(this, RecorderService.ACTION_PAUSE))
    }

    fun resumeRecording() {
        startService(RecorderService.actionIntent(this, RecorderService.ACTION_RESUME))
    }

    /** True when Android's "Show touches" developer option is on. */
    fun isShowTouchesOn(): Boolean {
        return try {
            // "show_touches" is the setting key. Do NOT use the SHOW_TOUCHES
            // constant — it is a hidden API and not in the public SDK, so
            // referencing it fails compilation ("Unresolved reference").
            Settings.System.getInt(contentResolver, "show_touches", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    fun openDevSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            RecorderState.error.value = "Could not open Developer options."
        }
    }
}

@Composable
private fun AppNav(
    settingsStore: SettingsStore,
    profileStore: HudProfileStore,
    onRecordRequested: (RecorderSettings) -> Unit,
    onStopRequested: () -> Unit,
    onPauseRequested: () -> Unit,
    onResumeRequested: () -> Unit,
    showTouchesOn: Boolean,
    onOpenDevSettings: () -> Unit
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    fun navigateTab(tab: String) {
        navController.navigate(tab) {
            launchSingleTop = true
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            restoreState = true
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "home",
                    onClick = { navigateTab("home") },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = route == "gallery",
                    onClick = { navigateTab("gallery") },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Gallery") },
                    label = { Text("Gallery") }
                )
                NavigationBarItem(
                    selected = route == "profiles",
                    onClick = { navigateTab("profiles") },
                    icon = { Icon(Icons.Default.Layers, contentDescription = "HUD Profiles") },
                    label = { Text("HUD") }
                )
                NavigationBarItem(
                    selected = route == "settings",
                    onClick = { navigateTab("settings") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    settingsStore = settingsStore,
                    onRecordRequested = onRecordRequested,
                    onStopRequested = onStopRequested,
                    onPauseRequested = onPauseRequested,
                    onResumeRequested = onResumeRequested,
                    showTouchesOn = showTouchesOn,
                    onOpenDevSettings = onOpenDevSettings,
                    onOpenGallery = { navController.navigate("gallery") },
                    onOpenProfiles = { navController.navigate("profiles") }
                )
            }
            composable("gallery") {
                GalleryScreen(
                    onMaskEditor = { videoUri, game ->
                        navController.navigate(
                            "maskEditor/${Uri.encode(videoUri)}/${Uri.encode(game)}"
                        )
                    }
                )
            }
            composable("settings") {
                SettingsScreen(settingsStore = settingsStore)
            }
            composable("profiles") {
                ProfilesScreen(profileStore = profileStore)
            }
            composable(
                route = "maskEditor/{videoUri}/{game}",
                arguments = listOf(
                    navArgument("videoUri") { type = NavType.StringType },
                    navArgument("game") { type = NavType.StringType }
                )
            ) { entry ->
                val videoUri = Uri.decode(entry.arguments?.getString("videoUri") ?: "")
                val game = Uri.decode(entry.arguments?.getString("game") ?: "Free Fire")
                MaskEditorScreen(
                    videoUri = videoUri,
                    game = game,
                    profileStore = profileStore,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
