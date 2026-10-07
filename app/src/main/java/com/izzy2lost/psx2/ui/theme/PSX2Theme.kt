package com.izzy2lost.psx2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.izzy2lost.psx2.R

/**
 * Compose counterpart of the XML `AppTheme` (themes.xml).
 *
 * Colors are read from the same `md_theme_*` resources the View theme uses, so both
 * UI toolkits stay in sync while the app is migrated incrementally. The app always
 * runs in dark mode (MainActivity forces MODE_NIGHT_YES), so only a dark scheme exists.
 */
@Composable
fun PSX2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = psx2ColorScheme(), content = content)
}

@Composable
private fun psx2ColorScheme(): ColorScheme = darkColorScheme(
    primary = colorResource(R.color.md_theme_primary),
    onPrimary = colorResource(R.color.md_theme_onPrimary),
    primaryContainer = colorResource(R.color.md_theme_primaryContainer),
    onPrimaryContainer = colorResource(R.color.md_theme_onPrimaryContainer),
    inversePrimary = colorResource(R.color.md_theme_inversePrimary),
    secondary = colorResource(R.color.md_theme_secondary),
    onSecondary = colorResource(R.color.md_theme_onSecondary),
    secondaryContainer = colorResource(R.color.md_theme_secondaryContainer),
    onSecondaryContainer = colorResource(R.color.md_theme_onSecondaryContainer),
    tertiary = colorResource(R.color.md_theme_tertiary),
    onTertiary = colorResource(R.color.md_theme_onTertiary),
    tertiaryContainer = colorResource(R.color.md_theme_tertiaryContainer),
    onTertiaryContainer = colorResource(R.color.md_theme_onTertiaryContainer),
    error = colorResource(R.color.md_theme_error),
    onError = colorResource(R.color.md_theme_onError),
    errorContainer = colorResource(R.color.md_theme_errorContainer),
    onErrorContainer = colorResource(R.color.md_theme_onErrorContainer),
    background = colorResource(R.color.md_theme_background),
    onBackground = colorResource(R.color.md_theme_onBackground),
    surface = colorResource(R.color.md_theme_surface),
    onSurface = colorResource(R.color.md_theme_onSurface),
    surfaceVariant = colorResource(R.color.md_theme_surfaceVariant),
    onSurfaceVariant = colorResource(R.color.md_theme_onSurfaceVariant),
    inverseSurface = colorResource(R.color.md_theme_inverseSurface),
    inverseOnSurface = colorResource(R.color.md_theme_inverseOnSurface),
    outline = colorResource(R.color.md_theme_outline),
    outlineVariant = colorResource(R.color.md_theme_outlineVariant),
    scrim = colorResource(R.color.md_theme_scrim),
    surfaceDim = colorResource(R.color.md_theme_surfaceDim),
    surfaceBright = colorResource(R.color.md_theme_surfaceBright),
    surfaceContainerLowest = colorResource(R.color.md_theme_surfaceContainerLowest),
    surfaceContainerLow = colorResource(R.color.md_theme_surfaceContainerLow),
    surfaceContainer = colorResource(R.color.md_theme_surfaceContainer),
    surfaceContainerHigh = colorResource(R.color.md_theme_surfaceContainerHigh),
    surfaceContainerHighest = colorResource(R.color.md_theme_surfaceContainerHighest),
)
