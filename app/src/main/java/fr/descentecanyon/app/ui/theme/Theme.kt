package fr.descentecanyon.app.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary = CanyonBlue,
    onPrimary = Color.White,
    primaryContainer = CanyonBlueLight,
    onPrimaryContainer = Color.White,
    secondary = RockBrown,
    onSecondary = Color.White,
    secondaryContainer = RockBrownLight,
    onSecondaryContainer = Color.White,
    background = SurfaceLight,
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
)

private val DarkColorScheme = darkColorScheme(
    primary = CanyonBlueLight,
    onPrimary = Color(0xFF003544),
    primaryContainer = CanyonBlueDark,
    onPrimaryContainer = CanyonBlueLight,
    secondary = RockBrownLight,
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = RockBrownDark,
    onSecondaryContainer = RockBrownLight,
    background = SurfaceDark,
    onBackground = Color(0xFFE2E2E5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E2E5),
)

@Composable
fun DescenteCanyonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
