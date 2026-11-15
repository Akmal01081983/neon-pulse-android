package uz.neonpulse.app.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import uz.neonpulse.app.HealthSync
import kotlinx.coroutines.flow.first
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Одна «капля»: где коснулись и когда (время ставит первый кадр) */
private class Drop(val at: Offset, val tint: Color) { var born = -1L }

private const val LIFE = 1900L      // сколько живёт волна, мс
private const val MAX_DROPS = 6     // больше одновременно не рисуем — экономим батарею
private const val SPEED = 230f      // скорость волны, dp в секунду
private const val LAMBDA = 17f      // расстояние между гребнями, dp
private const val CRESTS = 5        // сколько гребней в волне

/**
 * Эффект «касание воды», как в темах Samsung: тёмная вода с еле заметной неоновой рябью;
 * от каждого касания расходятся круги — картинка под ними изгибается (преломление),
 * гребни бликуют, впадины темнее. Android 13+ — шейдер на видеокарте; старее — круги с бликом и тенью.
 * Касание не перехватывается — кнопки и прокрутка работают как обычно.
 * Часы анимации идут только пока есть волны; если анимации в телефоне выключены — спокойный фон.
 */
@Composable
fun NeonWater(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val moving = motionOn()
    val drops = remember { mutableStateListOf<Drop>() }
    var now by remember { mutableLongStateOf(0L) }
    var flip by remember { mutableLongStateOf(0L) }
    // Шейдер воды (Android 13+). Ошибка сборки шейдера — просто рисуем круги
    // Лёгкий режим (слабый телефон) — без шейдера, только круги
    val lite = HealthSync.lite(LocalContext.current)
    val shader: Any? = remember(lite) { if (!lite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) makeShader() else null }

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
                            drops.add(Drop(c.position, if (flip++ % 2 == 0L) Neon.Cyan else Neon.Lime))
                        }
                    }
                }
            }
            .drawBehind {
                val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null &&
                    drawShader(shader as RuntimeShader, drops, now)
                if (!ok) {
                    drawRect(Neon.Bg)
                    ambient()
                    drops.forEach { rings(it, now) }
                }
            },
        content = content,
    )
}

// ===================== Android 13+: шейдер воды =====================

/** AGSL: фон-вода с неоновой рябью + волны от 6 касаний (преломление, блик гребня, тень впадины) */
private const val WATER = """
uniform float2 res;
uniform float time;
uniform float dens;
uniform float4 d0; uniform float4 d1; uniform float4 d2; uniform float4 d3; uniform float4 d4; uniform float4 d5;

half3 water(float2 p) {
    float d = 0.0;
    float2 q = p * (0.04 / dens);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        q += float2(sin(q.y * 1.7 + time * 0.25 + fi), cos(q.x * 1.3 - time * 0.2 + fi * 1.7)) * 0.55;
        d += 0.5 + 0.5 * sin(q.x + q.y);
    }
    d /= 4.0;
    float caust = pow(d, 8.0);
    half3 base = half3(0.027, 0.027, 0.06);
    half3 cyan = half3(0.098, 0.89, 1.0);
    half3 lime = half3(0.776, 1.0, 0.24);
    float gc = exp(-length(p / res - float2(0.1, 0.05)) * 2.2);
    float gl = exp(-length(p / res - float2(0.95, 0.95)) * 2.6);
    return base + cyan * half(0.10 * gc + 0.07 * caust) + lime * half(0.05 * gl);
}

// xy — смещение картинки, z — блик, w — тень
float4 wave(float4 d, float2 p) {
    if (d.w <= 0.0) return float4(0.0);
    float2 v = p - d.xy;
    float r = length(v);
    float x = (230.0 * dens * d.z - r) / (17.0 * dens);  // сколько длин волн позади фронта
    if (x < 0.0 || x > 5.0) return float4(0.0);
    float env = exp(-x * 0.55) * pow(max(0.0, 1.0 - d.z / 1.9), 1.6) * min(1.0, 160.0 * dens / (r + 40.0 * dens)) * d.w;
    float h = sin(x * 6.2832);
    float s = cos(x * 6.2832);
    float2 n = r > 0.5 ? v / r : float2(0.0);
    return float4(n * s * env * 7.0 * dens, max(0.0, h) * env, max(0.0, -h) * env);
}

half4 main(float2 p) {
    float4 w = wave(d0, p) + wave(d1, p) + wave(d2, p) + wave(d3, p) + wave(d4, p) + wave(d5, p);
    half3 col = water(p + w.xy);
    col += half3(0.75, 0.95, 1.0) * half(w.z * 0.28);
    col *= half(1.0 - 0.45 * clamp(w.w, 0.0, 1.0));
    return half4(col, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun makeShader(): RuntimeShader? = try { RuntimeShader(WATER) } catch (e: Exception) { null }

/** Рисуем воду шейдером; false — что-то пошло не так, тогда рисуем круги */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawShader(sh: RuntimeShader, drops: List<Drop>, now: Long): Boolean = try {
    sh.setFloatUniform("res", size.width, size.height)
    sh.setFloatUniform("time", (now % 600_000L) / 1000f)   // рябь фона движется только пока есть волны
    sh.setFloatUniform("dens", density)
    for (i in 0 until MAX_DROPS) {
        val d = drops.getOrNull(i)
        if (d == null || d.born < 0) sh.setFloatUniform("d$i", 0f, 0f, 0f, 0f)
        else sh.setFloatUniform("d$i", d.at.x, d.at.y, (now - d.born) / 1000f, 1f)
    }
    drawRect(ShaderBrush(sh))
    true
} catch (e: Exception) { false }

// ===================== Старые Android: круги с бликом и тенью =====================

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

/** Волна как на воде: всплеск под пальцем и 5 гребней — светлый блик, неоновый оттенок, тёмная впадина */
private fun DrawScope.rings(d: Drop, now: Long) {
    if (d.born < 0) return
    val ms = (now - d.born).toFloat()
    val t = ms / LIFE
    if (t <= 0f || t >= 1f) return
    val dp = density
    val front = SPEED * dp * ms / 1000f
    val life = (1f - t).pow(1.6f)
    if (ms < 260f) {
        val s = ms / 260f
        val r = (6f + 14f * s) * dp
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * (1f - s)), Color.Transparent), d.at, r), radius = r, center = d.at)
    }
    for (k in 0 until CRESTS) {
        val r = front - k * LAMBDA * dp
        if (r <= 2f * dp) continue
        val a = life * exp(-k * 0.45f) * min(1f, 160f * dp / (r + 40f * dp))
        drawCircle(Color.White.copy(alpha = 0.30f * a), radius = r + 1.2f * dp, center = d.at, style = Stroke(2.2f * dp))
        drawCircle(d.tint.copy(alpha = 0.55f * a), radius = r, center = d.at, style = Stroke(1.4f * dp))
        drawCircle(Color.Black.copy(alpha = 0.45f * a), radius = max(0.5f, r - LAMBDA * dp * 0.42f), center = d.at, style = Stroke(3.2f * dp))
    }
}
