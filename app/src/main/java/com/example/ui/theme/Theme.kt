package com.example.ui.theme

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

private val SlateDarkColorScheme = darkColorScheme(
  primary = GlowCyan,
  secondary = GlowGreen,
  tertiary = GlowMagenta,
  background = DarkObsidian,
  surface = CardSlateGlass,
  onPrimary = Color.Black,
  onSecondary = Color.Black,
  onBackground = TextSlateLight,
  onSurface = TextSlateLight,
  surfaceVariant = DeepSlateBg,
  onSurfaceVariant = TextSlateMuted,
  outline = BorderSlate
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force premium dark mode ALWAYS for high-end feel
  dynamicColor: Boolean = false, // Disable dynamic content colors to retain the beautiful bespoke Slate theme
  content: @Composable () -> Unit,
) {
  val colorScheme = SlateDarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
