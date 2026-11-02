package uz.neonpulse.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.max

/** Одна «капля»: где коснулись, когда и каким цветом */
private class Drop(val at: Offset, val color: Color) { var born = -1L }  // время ставит первый кадр

private const val LIFE = 1600L      // сколько живёт волна, мс
private const val MAX_DROPS = 6     // больше одновременно не рисуем — экономим батарею

/**
 * «Живой» фон: тёмная гладь с мягким неоновым светом, от каждого касания расходится волна,
 * как от капли на воде. Касание не перехватывается — кнопки и прокрутка работают как обычно.
 * Если анимации в телефоне выключены — остаётся только спокойный фон без волн.
 */
@Composable
fun NeonWater(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val moving = motionOn()
    val drops = remember { mutableStateListOf<Drop>() }
    var now by remember { mutableLongStateOf(0L) }
    var flip by remember { mutableLongStateOf(0L) }

    // Часы анимации идут только пока на экране есть волны
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        while (true) {
            snapshotFlow { drops.size }.first { it > 0 }
            while (drops.isNotEmpty()) {
                withFrameMillis { t ->
                    drops.forEach { if (it.born < 0) it.born = t }
                    now = t; drops.removeAll { t - it.born > LIFE }
                }
            }
        }
    }

    Box(
        modifier
            .pointerInput(moving) {
                if (!moving) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        // Initial — видим касание раньше кнопок и ничего не «съедаем»
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        e.changes.filter { it.changedToDown() }.forEach { c ->
                            if (drops.size >= MAX_DROPS) drops.removeAt(0)
                            val color = if (flip++ % 2 == 0L) Neon.Cyan else Neon.Lime
                            drops.add(Drop(c.position, color))
                        }
                    }
                }
            }
            .drawBehind {
                drawRect(Neon.Bg)
                ambient()
                drops.forEach { water(it, now) }
            },
        content = content,
    )
}

/** Постоянный слабый свет по краям — фон не плоский, но и не отвлекает */
private fun DrawScope.ambient() {
    val d = max(size.width, size.height)
    drawCircle(
        Brush.radialGradient(listOf(Neon.Cyan.copy(alpha = 0.10f), Color.Transparent), Offset(size.width * 0.1f, size.height * 0.05f), d * 0.55f),
        radius = d * 0.55f, center = Offset(size.width * 0.1f, size.height * 0.05f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Neon.Lime.copy(alpha = 0.06f), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.95f), d * 0.5f),
        radius = d * 0.5f, center = Offset(size.width * 0.95f, size.height * 0.95f),
    )
}

/** Волна от капли: мягкое свечение в центре и три кольца, которые расходятся и гаснут */
private fun DrawScope.water(d: Drop, now: Long) {
    val t = ((now - d.born).toFloat() / LIFE).coerceIn(0f, 1f)
    if (d.born < 0 || t <= 0f) return
    val maxR = max(size.width, size.height) * 0.6f
    val fade = (1f - t) * (1f - t)

    // свечение под пальцем
    val glowR = 36.dp.toPx() + easeOut(t) * 90.dp.toPx()
    drawCircle(
        Brush.radialGradient(listOf(d.color.copy(alpha = 0.22f * fade), Color.Transparent), d.at, glowR),
        radius = glowR, center = d.at,
    )
    // кольца: каждое следующее стартует чуть позже и слабее
    for (i in 0..2) {
        val ti = ((t - i * 0.12f) / (1f - i * 0.12f)).coerceIn(0f, 1f)
        if (ti <= 0f) continue
        val a = (1f - ti) * (1f - ti) * (0.55f - i * 0.15f)
        drawCircle(
            color = d.color.copy(alpha = a),
            radius = easeOut(ti) * maxR,
            center = d.at,
            style = Stroke(width = (2.2f - i * 0.5f).dp.toPx() * (1f - ti) + 0.5f.dp.toPx()),
        )
    }
}

private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
