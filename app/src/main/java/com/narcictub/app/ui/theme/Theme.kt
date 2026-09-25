package com.narcictub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.narcictub.app.domain.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    onTertiary = BrandOnTertiary,
    tertiaryContainer = BrandTertiaryContainer,
    onTertiaryContainer = BrandOnTertiaryContainer,
    error = BrandError,
    onError = BrandOnError,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,
    background = BrandBackground,
    onBackground = BrandOnBackground,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandOnSurfaceVariant,
    outline = BrandOutline,
    outlineVariant = BrandOutlineVariant,
    surfaceContainerLowest = BrandSurface,
    surfaceContainerLow = BrandSurfaceContainerLow,
    surfaceContainer = BrandSurfaceContainer,
    surfaceContainerHigh = BrandSurfaceContainerHigh,
    surfaceContainerHighest = BrandSurfaceContainerHighest,
)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainerLight,
    onPrimaryContainer = BrandOnPrimaryContainerLight,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    onTertiary = BrandOnTertiary,
    tertiaryContainer = BrandTertiaryContainer,
    onTertiaryContainer = BrandOnTertiaryContainer,
    error = BrandError,
    onError = BrandOnError,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,
    background = BrandBackgroundLight,
    onBackground = BrandOnBackgroundLight,
    surface = BrandSurfaceLight,
    onSurface = BrandOnSurfaceLight,
    surfaceVariant = BrandSurfaceVariantLight,
    onSurfaceVariant = BrandOnSurfaceVariantLight,
    outline = BrandOutlineLight,
    outlineVariant = BrandOutlineVariantLight,
    surfaceContainerLowest = BrandSurfaceLight,
    surfaceContainerLow = BrandSurfaceContainerLowLight,
    surfaceContainer = BrandSurfaceContainerLight,
    surfaceContainerHigh = BrandSurfaceContainerHighLight,
    surfaceContainerHighest = BrandSurfaceContainerHighestLight,
)

/**
 * Rounded design language: softly curved cards, pill-shaped buttons and
 * generously rounded sheets. Applied at theme level so every Material
 * component (cards, text fields, buttons, bottom bar) inherits it.
 */
private val NarcicTubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Root theme of the app. Dark-first design: dark scheme is the primary
 * palette, light is derived for users who prefer it.
 */
@Composable
fun NarcicTubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NarcicTubTypography,
        shapes = NarcicTubShapes,
        content = content,
    )
}

/**
 * PHASE 13: resolves the user's persisted [ThemeMode] against the system
 * setting. Pure and unit-tested; the persisted mode is the single source of
 * truth — no duplicated theme state anywhere.
 */
internal fun ThemeMode.resolveDarkTheme(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}
