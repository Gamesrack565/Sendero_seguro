package com.escom.sendero_seguro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.escom.sendero_seguro.R

// Archivo: títulos y cifras. Atkinson Hyperlegible: lectura (distingue 1/l/I y 0/O).
// JetBrains Mono: folios, coordenadas y códigos.

@OptIn(ExperimentalTextApi::class)
private fun archivo(peso: Int) = Font(
    R.font.archivo,
    weight = FontWeight(peso),
    variationSettings = FontVariation.Settings(FontVariation.weight(peso))
)

@OptIn(ExperimentalTextApi::class)
private fun mono(peso: Int) = Font(
    R.font.jetbrains_mono,
    weight = FontWeight(peso),
    variationSettings = FontVariation.Settings(FontVariation.weight(peso))
)

val Archivo = FontFamily(archivo(500), archivo(600), archivo(700), archivo(800))

val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold)
)

val Mono = FontFamily(mono(400), mono(700))

val Typography = Typography(
    // Archivo 800 · 34
    displaySmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp),
    // Archivo 700 · 22
    titleLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    // Atkinson 400 · 16
    bodyLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // Atkinson 700 · 14
    labelLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp)
)

// JetBrains Mono · 14
val TextoMono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
