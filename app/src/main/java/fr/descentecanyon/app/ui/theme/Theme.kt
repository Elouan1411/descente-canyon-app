package fr.descentecanyon.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = CanyonBlueDark,
    onPrimary = Color.White,
    primaryContainer = CanyonBlueMist,
    onPrimaryContainer = Color(0xFF102F3C),
    secondary = RockBrown,
    onSecondary = Color.White,
    secondaryContainer = RockSandLight,
    onSecondaryContainer = Color(0xFF4A3212),
    background = BackgroundLight,
    onBackground = Color(0xFF172027),
    surface = CardLight,
    onSurface = Color(0xFF1A232A),
    surfaceVariant = CanyonBlueFrost,
    onSurfaceVariant = TextMutedLight,
    outline = BorderLight,
    outlineVariant = BorderLight.copy(alpha = 0.72f),
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
        dynamicColor && darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
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
