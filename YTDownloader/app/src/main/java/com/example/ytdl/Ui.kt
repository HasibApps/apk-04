package com.example.ytdl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────── Palette / themes ─────────

class Pal(
    val bg: Brush, val card: Color, val text: Color, val sub: Color, val nav: Color,
    val dark: Boolean
)

val Neon = Color(0xFFFF2D78)
val Pink = Color(0xFFFF416C)

fun palette(mode: ThemeMode, systemDark: Boolean): Pal {
    val m = if (mode == ThemeMode.SYSTEM) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else mode
    return when (m) {
        ThemeMode.LIGHT -> Pal(
            Brush.verticalGradient(listOf(Color(0xFFF3F0FF), Color(0xFFFFFFFF), Color(0xFFF7F3FF))),
            Color(0xFF7F00FF).copy(alpha = 0.07f), Color(0xFF1A1A2E), Color(0xFF5E5E7E), Color(0xFFFFFFFF), false
        )
        ThemeMode.AMOLED -> Pal(
            SolidColor(Color(0xFF000000)), Color.White.copy(alpha = 0.08f), Color.White, Color(0xFFA0A0C0), Color(0xFF000000), true
        )
        else -> Pal(
            Brush.verticalGradient(listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E))),
            Color.White.copy(alpha = 0.08f), Color.White, Color(0xFFB0B0D0), Color(0xFF0A0820), true
        )
    }
}

val LocalPal = compositionLocalOf { palette(ThemeMode.DARK, true) }

val Grad = Brush.horizontalGradient(listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)))
val Grad2 = Brush.horizontalGradient(listOf(Color(0xFF7F00FF), Color(0xFFE100FF)))

// ───────── Extra icons (Pause / Download are not in material-icons-core) ─────────

object Ic {
    private fun v(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()

    val Pause: ImageVector by lazy { v("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Download: ImageVector by lazy { v("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
}

// ───────── Shared composables ─────────

@Composable
fun GradBtn(text: String, g: Brush, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(g).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = p.card),
        modifier = modifier.fillMaxWidth()
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

fun fmtSize(b: Long): String = when {
    b >= 1_073_741_824L -> "%.2f GB".format(b / 1073741824.0)
    b >= 1_048_576L -> "%.1f MB".format(b / 1048576.0)
    b > 0 -> "%.0f KB".format(b / 1024.0)
    else -> ""
}
