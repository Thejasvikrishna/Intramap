package com.intramap.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1967D2), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE), onPrimaryContainer = Color(0xFF123F85),
    secondary = Color(0xFF506078), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EDF5), onSecondaryContainer = Color(0xFF243449),
    tertiary = Color(0xFF137568), onTertiary = Color.White,
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF182230),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF182230),
    surfaceVariant = Color(0xFFEEF2F7), onSurfaceVariant = Color(0xFF526174),
    outline = Color(0xFF748194), outlineVariant = Color(0xFFDCE3ED),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFF1967D2)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF124889), onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFFBAC7DB), onSecondary = Color(0xFF243449),
    secondaryContainer = Color(0xFF3B4B60), onSecondaryContainer = Color(0xFFDCE7F7),
    tertiary = Color(0xFF81D5C4), onTertiary = Color(0xFF00382F),
    background = Color(0xFF10151D), onBackground = Color(0xFFE3E9F2),
    surface = Color(0xFF151D28), onSurface = Color(0xFFE3E9F2),
    surfaceVariant = Color(0xFF273242), onSurfaceVariant = Color(0xFFB9C5D5),
    outline = Color(0xFF8895A8), outlineVariant = Color(0xFF3B485A),
    surfaceTint = Color(0xFFADC6FF)
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun IntramapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = AppShapes, content = content)
}
