package com.github.project_fredica.components

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object AppColor {
    val Primary = Color(0xFF59BD90)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFBFE6D4)
    val OnPrimaryContainer = Color(0xFF183327)
    val Secondary = Color(0xFFFCC60D)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFE6D6A1)
    val OnSecondaryContainer = Color(0xFF332803)
    val Tertiary = Color(0xFFC8723D)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFE6C6B3)
    val OnTertiaryContainer = Color(0xFF331D0F)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFE6ACA9)
    val OnErrorContainer = Color(0xFF330B09)
    val Background = Color(0xFFfcfcfc)
    val OnBackground = Color(0xFF323332)
    val Surface = Color(0xFFfcfcfc)
    val OnSurface = Color(0xFF323332)
    val SurfaceVariant = Color(0xFFdee6e2)
    val OnSurfaceVariant = Color(0xFF5b6661)
    val Outline = Color(0xFF899992)

    val PrimaryDark = Color(0xFFAFE6CD)
    val OnPrimaryDark = Color(0xFF244C3A)
    val PrimaryContainerDark = Color(0xFF30664E)
    val OnPrimaryContainerDark = Color(0xFFBFE6D4)
    val SecondaryDark = Color(0xFFE6D084)
    val OnSecondaryDark = Color(0xFF4C3C04)
    val SecondaryContainerDark = Color(0xFF665105)
    val OnSecondaryContainerDark = Color(0xFFE6D6A1)
    val TertiaryDark = Color(0xFFE6B99E)
    val OnTertiaryDark = Color(0xFF4C2C17)
    val TertiaryContainerDark = Color(0xFF663A1F)
    val OnTertiaryContainerDark = Color(0xFFE6C6B3)
    val ErrorDark = Color(0xFFE69490)
    val OnErrorDark = Color(0xFF4C100D)
    val ErrorContainerDark = Color(0xFF661511)
    val OnErrorContainerDark = Color(0xFFE6ACA9)
    val BackgroundDark = Color(0xFF323332)
    val OnBackgroundDark = Color(0xFFe4e6e5)
    val SurfaceDark = Color(0xFF323332)
    val OnSurfaceDark = Color(0xFFe4e6e5)
    val SurfaceVariantDark = Color(0xFF5b6661)
    val OnSurfaceVariantDark = Color(0xFFdbe6e1)
    val OutlineDark = Color(0xFFa7b3ad)

    val lightColorScheme = lightColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        secondary = Secondary,
        onSecondary = OnSecondary,
        secondaryContainer = SecondaryContainer,
        onSecondaryContainer = OnSecondaryContainer,
        tertiary = Tertiary,
        onTertiary = OnTertiary,
        tertiaryContainer = TertiaryContainer,
        onTertiaryContainer = OnTertiaryContainer,
        error = Error,
        onError = OnError,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
        background = Background,
        onBackground = OnBackground,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        outline = Outline
    )

    val darkColorScheme = darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark
    )
}