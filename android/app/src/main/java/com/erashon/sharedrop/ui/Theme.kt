package com.erashon.sharedrop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colores del sistema de iOS (claro y oscuro). */
object Ios {
    val Blue = Color(0xFF007AFF)
    val BlueDark = Color(0xFF0A84FF)
    val Green = Color(0xFF34C759)
    val GreenDark = Color(0xFF30D158)
    /** Amarillo del sistema de iOS: lo recibido (descarga). */
    val Yellow = Color(0xFFFFCC00)
    val YellowDark = Color(0xFFFFD60A)
    val Red = Color(0xFFFF3B30)
    val RedDark = Color(0xFFFF453A)
}

@Composable
fun ShareDropTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Ios.BlueDark,
            background = Color.Black,
            surface = Color(0xFF1C1C1E),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFF98989F),
            outlineVariant = Color(0xFF38383A),
            primaryContainer = Color(0xFF0A2A4D),
            onPrimaryContainer = Ios.BlueDark,
            surfaceVariant = Color(0xFF2C2C2E),
            tertiary = Ios.GreenDark,
            secondary = Ios.YellowDark,
            onSecondaryContainer = Ios.YellowDark,
            error = Ios.RedDark,
        )
    } else {
        lightColorScheme(
            primary = Ios.Blue,
            background = Color(0xFFF2F2F7),
            surface = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = Color(0xFF8A8A8E),
            outlineVariant = Color(0xFFC6C6C8),
            primaryContainer = Color(0x1F007AFF),
            onPrimaryContainer = Ios.Blue,
            surfaceVariant = Color(0xFFE9E9EE),
            tertiary = Ios.Green,
            secondary = Ios.Yellow,
            // Texto sobre fondo claro: el amarillo puro no contrasta, se usa un ámbar más oscuro.
            onSecondaryContainer = Color(0xFF946F00),
            error = Ios.Red,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(14.dp),
        ),
        content = content,
    )
}
