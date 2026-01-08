package com.ebagesprpe.gyselbevsb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.BtnStart,
    background = AppColors.BgDark,
    surface = AppColors.BgCard,
)

@Composable
fun PhaseDetectorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}