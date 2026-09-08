package com.formsnap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF245C4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EADF),
    onPrimaryContainer = Color(0xFF153B2D),
    background = Color(0xFFFAFAF6),
    surface = Color(0xFFFAFAF6),
    onBackground = Color(0xFF202723),
    onSurface = Color(0xFF202723),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2D2B7),
    onPrimary = Color(0xFF0E3827),
    primaryContainer = Color(0xFF2B503E),
    onPrimaryContainer = Color(0xFFCEEBD7),
    background = Color(0xFF131815),
    surface = Color(0xFF131815),
    onBackground = Color(0xFFE2E8E2),
    onSurface = Color(0xFFE2E8E2),
)

@Composable
fun FormSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
