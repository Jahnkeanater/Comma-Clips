package app.commaclips.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4FB477)
val Warn = Color(0xFFE0A64A)
val Bad = Color(0xFFE0605A)

private val Dark = darkColorScheme(
  primary = Accent, onPrimary = Color(0xFF06210F), background = Color(0xFF0F1115), surface = Color(0xFF181B22),
  surfaceVariant = Color(0xFF222631), onBackground = Color(0xFFE9EBF0), onSurface = Color(0xFFE9EBF0),
  onSurfaceVariant = Color(0xFF9298A6), outline = Color(0xFF2B303C), error = Bad,
)
private val Light = lightColorScheme(
  primary = Color(0xFF23874C), onPrimary = Color.White, background = Color(0xFFF4F5F7), surface = Color.White,
  surfaceVariant = Color(0xFFECEEF2), onSurfaceVariant = Color(0xFF5D6472), outline = Color(0xFFDDE0E6), error = Bad,
)

@Composable
fun CommaTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
