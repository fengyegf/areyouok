package com.catmoecircle.areyouok.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = CanvasDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = MutedDark,
    outline = Color(0xFF3A3A3D),
    outlineVariant = Color(0xFF2A2A2D),
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = CanvasLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEFEFF2),
    onSurfaceVariant = MutedLight,
    outline = Color(0xFFD9D9DE),
    outlineVariant = Color(0xFFE8E8EC),
)

@Composable
fun AreYouOKTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 聊天画布使用固定的中性色，默认关闭动态取色以保持界面观感一致。
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
