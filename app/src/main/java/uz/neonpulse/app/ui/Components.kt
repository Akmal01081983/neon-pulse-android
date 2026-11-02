package uz.neonpulse.app.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Анимации выключены в настройках телефона — рисуем без движения */
@Composable
fun motionOn(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/** Лёгкое «вдавливание» при нажатии (вдобавок к стандартной волне Material) */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    return s
}

/** Главная кнопка: залитая, 56 dp; во время работы — индикатор вместо текста */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled && !loading, interactionSource = src,
        modifier = modifier.fillMaxWidth().height(56.dp).scale(pressScale(src)),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan, contentColor = Neon.Bg,
            disabledContainerColor = Color(0xFF16182A), disabledContentColor = Neon.Muted),
    ) {
        if (loading) CircularProgressIndicator(color = Neon.Bg, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
        else Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Вторая кнопка: контурная, спокойная */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick, enabled = enabled, interactionSource = src,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).scale(pressScale(src)),
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Neon.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Neon.Surface, contentColor = Neon.Text),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** Текстовая ссылка-кнопка */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, color: Color = Neon.Muted) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/** Логотип NEON с розовым свечением — как в шапке экранов Neon */
@Composable
fun Logo() {
    Text("NEON", style = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 5.sp,
        color = Color.White, shadow = Shadow(color = Neon.Pink, blurRadius = 18f)))
}

/** Кнопка языка в углу: RU / UZ / EN */
@Composable
fun LangChip(code: String, onClick: () -> Unit, label: String) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Neon.Line, RoundedCornerShape(10.dp))
            .background(Neon.Surface).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(code.uppercase(), color = Neon.Soft, style = MaterialTheme.typography.labelMedium) }
}

/** Точки шагов знакомства: где человек сейчас */
@Composable
fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.width(if (i == current) 28.dp else 14.dp).height(4.dp).clip(CircleShape)
                .background(if (i <= current) Neon.Cyan else Neon.Line))
        }
    }
}

/** Главная картинка экранов входа: светящееся кольцо «заряда», медленно набирает круг */
@Composable
fun HeroRing(modifier: Modifier = Modifier, size: Dp = 176.dp, color: Color = Neon.Cyan, fill: Float = 0.72f, icon: (DrawScope.(Color) -> Unit)? = null) {
    val moving = motionOn()
    val t = rememberInfiniteTransition(label = "ring")
    val sweep by t.animateFloat(0.18f, fill, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "sweep")
    val p = if (moving) sweep else fill
    Canvas(modifier.size(size)) {
        val sw = this.size.minDimension * 0.07f
        val inset = sw * 1.6f
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val tl = Offset(inset, inset)
        drawArc(Neon.Line.copy(alpha = 0.7f), 0f, 360f, false, tl, arcSize, style = Stroke(sw))
        rotate(-90f) {
            // мягкое свечение под дугой
            drawArc(color.copy(alpha = 0.18f), 0f, 360f * p, false, tl, arcSize, style = Stroke(sw * 2.6f, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color)), 0f, 360f * p, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        icon?.invoke(this, color)
    }
}

/** Строка «зачем это нужно»: маленький значок и короткое пояснение */
@Composable
fun BenefitRow(text: String, icon: DrawScope.(Color) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Neon.Surface).border(1.dp, Neon.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { Canvas(Modifier.size(20.dp)) { icon(Neon.Cyan) } }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Neon.Soft, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
    }
}

/** Карточка-подсказка: что не так и как исправить, с одной кнопкой */
@Composable
fun NoticeCard(title: String, text: String, action: String? = null, onAction: () -> Unit = {}, tone: Color = Neon.Amber) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Neon.Surface)
        .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Neon.Text, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, color = Neon.Soft, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(action, onAction)
        }
    }
}

/** Плитка главного экрана: значок, название, подпись */
@Composable
fun Tile(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Neon.Cyan, icon: DrawScope.(Color) -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.scale(pressScale(src)).clip(RoundedCornerShape(20.dp)).background(Neon.Surface)
            .border(1.dp, Neon.Line, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) { icon(accent) }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Neon.Text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(sub, color = Neon.Muted, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp))
    }
}

/** Строка в нижнем меню (аккаунт, оплата, язык) */
@Composable
fun SheetItem(text: String, onClick: () -> Unit, color: Color = Neon.Text, selected: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Neon.Cyan.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, color = if (selected) Neon.Cyan else color, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)) }
}

// ===== Значки — тонкие линии в стиле нижнего меню Neon (без эмодзи и сторонних библиотек) =====
private fun DrawScope.stroke() = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)

/** Кольцо заряда */
val IcRing: DrawScope.(Color) -> Unit = { c ->
    val r = size.minDimension * 0.4f
    drawCircle(c.copy(alpha = 0.35f), r, style = stroke())
    drawArc(c, -90f, 250f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = stroke())
}
/** Гантель */
val IcDumbbell: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height; val s = stroke()
    drawLine(c, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.75f, h * 0.5f), s.width, StrokeCap.Round)
    for (x in listOf(0.25f, 0.75f)) drawLine(c, Offset(w * x, h * 0.28f), Offset(w * x, h * 0.72f), s.width, StrokeCap.Round)
    for (x in listOf(0.1f, 0.9f)) drawLine(c, Offset(w * x, h * 0.38f), Offset(w * x, h * 0.62f), s.width, StrokeCap.Round)
}
/** Облачко чата */
val IcChat: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w * 0.15f, h * 0.18f); lineTo(w * 0.85f, h * 0.18f); lineTo(w * 0.85f, h * 0.68f); lineTo(w * 0.4f, h * 0.68f); lineTo(w * 0.15f, h * 0.88f); close() }
    drawPath(p, c, style = stroke())
}
/** Сердце (пульс) */
val IcHeart: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.85f)
        cubicTo(w * 0.05f, h * 0.55f, w * 0.1f, h * 0.12f, w * 0.5f, h * 0.32f)
        cubicTo(w * 0.9f, h * 0.12f, w * 0.95f, h * 0.55f, w * 0.5f, h * 0.85f)
    }
    drawPath(p, c, style = stroke())
}
/** Луна (сон) */
val IcMoon: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.62f, h * 0.12f)
        cubicTo(w * 0.2f, h * 0.15f, w * 0.12f, h * 0.75f, w * 0.55f, h * 0.88f)
        cubicTo(w * 0.72f, h * 0.92f, w * 0.85f, h * 0.82f, w * 0.9f, h * 0.68f)
        cubicTo(w * 0.55f, h * 0.72f, w * 0.38f, h * 0.38f, w * 0.62f, h * 0.12f)
    }
    drawPath(p, c, style = stroke())
}
/** Щит (приватность) */
val IcShield: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.85f, h * 0.22f); lineTo(w * 0.85f, h * 0.5f)
        cubicTo(w * 0.85f, h * 0.72f, w * 0.68f, h * 0.86f, w * 0.5f, h * 0.94f)
        cubicTo(w * 0.32f, h * 0.86f, w * 0.15f, h * 0.72f, w * 0.15f, h * 0.5f); lineTo(w * 0.15f, h * 0.22f); close()
    }
    drawPath(p, c, style = stroke())
}
/** Галочка */
val IcCheck: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w * 0.18f, h * 0.52f); lineTo(w * 0.42f, h * 0.75f); lineTo(w * 0.84f, h * 0.28f) }
    drawPath(p, c, style = stroke())
}
/** Часы на руке */
val IcWatch: DrawScope.(Color) -> Unit = { c ->
    val w = size.width; val h = size.height
    drawRoundRect(c, Offset(w * 0.25f, h * 0.25f), Size(w * 0.5f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = stroke())
    drawLine(c, Offset(w * 0.38f, h * 0.06f), Offset(w * 0.62f, h * 0.06f), stroke().width, StrokeCap.Round)
    drawLine(c, Offset(w * 0.38f, h * 0.94f), Offset(w * 0.62f, h * 0.94f), stroke().width, StrokeCap.Round)
    drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.38f), stroke().width, StrokeCap.Round)
}
