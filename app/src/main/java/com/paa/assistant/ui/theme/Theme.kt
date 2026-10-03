package com.paa.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PAA brand color palette — deep purple/violet + dark slate
private val PAADarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C3AED),            // Violet 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4C1D95),   // Violet 900
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFF0EA5E9),          // Sky 500
    onSecondary = Color.White,
    background = Color(0xFF0D0D1A),         // Near-black indigo
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1A1A2E),            // Dark indigo card
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF2D2D4E),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444),
    onError = Color.White,
    outline = Color(0xFF334155)
)

@Composable
fun PAATheme(
    darkTheme: Boolean = true, // PAA is always dark themed
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PAADarkColorScheme,
        content = content
    )
}
