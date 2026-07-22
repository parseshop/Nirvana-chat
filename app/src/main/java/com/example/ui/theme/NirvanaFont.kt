package com.example.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object NirvanaFont {
    fun getFontFamily(fontName: String): FontFamily {
        return when (fontName) {
            "SansSerif" -> FontFamily.SansSerif
            "Serif" -> FontFamily.Serif
            "Monospace" -> FontFamily.Monospace
            else -> FontFamily.Default
        }
    }

    fun getTextStyle(
        fontStyle: String,
        scale: Float,
        baseSizeSp: Int,
        fontWeight: FontWeight = FontWeight.Normal
    ): TextStyle {
        val scaledSize = (baseSizeSp * scale).sp
        return TextStyle(
            fontFamily = getFontFamily(fontStyle),
            fontSize = scaledSize,
            fontWeight = fontWeight,
            lineHeight = (scaledSize.value * 1.4f).sp
        )
    }
}
