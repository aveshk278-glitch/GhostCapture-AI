package com.ghostcapture.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

private val GhostColorScheme = darkColorScheme(
    primary = GhostCyan,
    onPrimary = GhostBlack,
    secondary = GhostPurple,
    onSecondary = GhostWhite,
    tertiary = GhostPurpleLight,
    background = GhostBlack,
    onBackground = GhostWhite,
    surface = GhostSurface,
    onSurface = GhostWhite,
    surfaceVariant = GhostSurfaceLight,
    onSurfaceVariant = GhostMuted,
    error = GhostRed,
    onError = GhostWhite
)

@Composable
fun GhostCaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GhostColorScheme,
        typography = GhostTypography,
        content = content
    )
}
