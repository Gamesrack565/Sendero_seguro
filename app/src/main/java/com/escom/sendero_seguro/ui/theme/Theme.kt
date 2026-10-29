package com.escom.sendero_seguro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Un solo esquema claro: la guía de estilo no define modo oscuro ni color dinámico.
private val Esquema = lightColorScheme(
    primary = Rojo,
    onPrimary = Blanco,
    primaryContainer = RojoTinte,
    onPrimaryContainer = RojoOscuro,
    secondary = Malva,
    onSecondary = Blanco,
    secondaryContainer = LilaTinte,
    onSecondaryContainer = LilaTexto,
    tertiary = Amarillo,
    onTertiary = Tinta,
    tertiaryContainer = AmarilloTinte,
    onTertiaryContainer = AmarilloTexto,
    error = RojoTexto,
    onError = Blanco,
    background = Fondo,
    onBackground = Tinta,
    surface = Superficie,
    onSurface = Tinta,
    surfaceVariant = Fondo,
    onSurfaceVariant = TextoSecundario,
    surfaceContainer = Superficie,
    surfaceContainerLow = Superficie,
    surfaceContainerHigh = Superficie,
    outline = Borde,
    outlineVariant = Borde
)

@Composable
fun Sendero_seguroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Esquema,
        typography = Typography,
        content = content
    )
}
