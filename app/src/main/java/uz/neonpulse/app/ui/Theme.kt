package uz.neonpulse.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import uz.neonpulse.app.R

/**
 * Фирменный стиль Neon — те же цвета и шрифты, что на экранах «Сегодня», «Тренер», «Чат» (сервер, wf15_app.js),
 * чтобы переход со входа на основные экраны был незаметным.
 */
object Neon {
    val Bg = Color(0xFF0E0F12)        // фон — глубокий угольный
    val Surface = Color(0xFF17191E)   // карточки
    val Surface2 = Color(0xFF121418)  // вложенные поля
    val Line = Color(0xFF272B33)      // рамки и разделители
    val Text = Color(0xFFEDEBFF)      // основной текст
    val Muted = Color(0xFF8A87A8)     // подписи
    val Soft = Color(0xFFC9C6E6)      // второстепенный текст
    val Cyan = Color(0xFF19E3FF)      // главный акцент, кнопки (Electric Cyan)
    val Lime = Color(0xFFC6FF3D)      // второй акцент: волна, прогресс (Neon Lime)
    val Green = Color(0xFF2BFF88)     // готово / успех
    val Pink = Color(0xFFFF2FD1)      // логотип
    val Amber = Color(0xFFFFB020)     // предупреждение
    val Red = Color(0xFFFF3D71)       // ошибка
}

/** Manrope — текст, Unbounded — заголовки и цифры (как на сервере) */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)
val Unbounded = FontFamily(Font(R.font.unbounded_semibold, FontWeight.SemiBold))

private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan, onPrimary = Neon.Bg,
            background = Neon.Bg, onBackground = Neon.Text,
            surface = Neon.Surface, onSurface = Neon.Text,
            surfaceVariant = Neon.Surface2, onSurfaceVariant = Neon.Muted,
            surfaceContainerLow = Neon.Surface, surfaceContainer = Neon.Surface, surfaceContainerHigh = Neon.Surface,
            outline = Neon.Line, error = Neon.Red,
        ),
        typography = Type,
        content = content,
    )
}
