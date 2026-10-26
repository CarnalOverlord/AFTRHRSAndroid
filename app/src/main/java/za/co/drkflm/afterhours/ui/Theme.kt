package za.co.drkflm.afterhours.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Void = Color(0xFF050208)
val Panel = Color(0xFF130B1B)
val Raised = Color(0xFF1F1229)
val Electric = Color(0xFFAD14FF)
val HotPink = Color(0xFFFF1FB8)
val Lavender = Color(0xFFC4A6FF)
val Muted = Color.White.copy(alpha = .58f)
val Neon = Brush.linearGradient(listOf(HotPink, Electric))

private val scheme = darkColorScheme(primary = HotPink, secondary = Electric, tertiary = Lavender, background = Void, surface = Panel, surfaceVariant = Raised, onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White)

@Composable fun AftrhrsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)

fun Modifier.glassCard(padding: Int = 16) = this
    .background(Color.White.copy(alpha = .055f), RoundedCornerShape(24.dp))
    .border(1.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(24.dp))
    .padding(padding.dp)

@Composable fun NeonButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues()) {
        Box(Modifier.fillMaxWidth().background(Neon, RoundedCornerShape(50)).padding(vertical = 15.dp)) { Text(text, modifier = Modifier.align(androidx.compose.ui.Alignment.Center), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable fun NightBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Void).background(Brush.radialGradient(listOf(Electric.copy(alpha=.2f), Color.Transparent), radius=1100f)), content = content)
}
