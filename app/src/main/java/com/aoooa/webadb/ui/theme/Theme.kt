package com.aoooa.webadb.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 主题模式：暗色 / 亮色 / 跟随系统
 */
enum class ThemeMode(val id: Int, val labelZh: String, val labelEn: String) {
    SYSTEM(0, "跟随系统", "Follow System"),
    DARK(1, "暗色", "Dark"),
    LIGHT(2, "亮色", "Light");

    companion object {
        fun fromId(id: Int): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

private val DarkColors = darkColorScheme(
    primary = WebAdbBlueLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = WebAdbBlueDark,
    background = DarkBg,
    onBackground = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surface = DarkCard,
    onSurface = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surfaceVariant = DarkCardSub,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF94A3B8),
    outline = DarkBorder,
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = WebAdbBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = WebAdbBlueLight,
    background = LightBg,
    onBackground = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surface = LightCard,
    onSurface = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF64748B),
    outline = LightBorder,
    error = ErrorRed,
)

@Composable
fun WebAdbTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
