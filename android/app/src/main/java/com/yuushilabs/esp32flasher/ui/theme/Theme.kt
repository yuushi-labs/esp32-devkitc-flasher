package com.yuushilabs.esp32flasher.ui.theme

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

private val EspressifRed = Color(0xFFE7352C)
private val CircuitBlue = Color(0xFF0E7490)
private val SignalAmber = Color(0xFFF59E0B)
private val BoardSlate = Color(0xFF0F172A)

private val LightColors = lightColorScheme(
    primary = CircuitBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF083344),
    secondary = EspressifRed,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1DE),
    onSecondaryContainer = Color(0xFF410002),
    tertiary = SignalAmber,
    onTertiary = Color(0xFF2A1A00),
    background = Color(0xFFF8FAFC),
    onBackground = BoardSlate,
    surface = Color.White,
    onSurface = BoardSlate,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF334155),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF155E6B),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = Color(0xFFFF8A80),
    onSecondary = Color(0xFF690005),
    secondaryContainer = Color(0xFF93000A),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3F2E00),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111C31),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFFB4AB),
)

@Composable
fun Esp32FlasherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
