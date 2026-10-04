package com.huibenlema.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.InkBlack

/** 饼图切片 */
data class PieSlice(val label: String, val fen: Long)

/**
 * 黑白灰 5 阶色板：黑 → 深灰 → 中灰 → 浅灰 → 极浅灰。
 * 相邻深度差异大，墨水屏上可清晰区分；最多 5 块饼。
 */
private val PieFills = listOf(
    InkBlack,                 // #111111 纯黑
    Color(0xFF3D3D3D),        // 深灰
    Color(0xFF6E6E6E),        // 中灰
    Color(0xFFA8A8A8),        // 浅灰
    Color(0xFFE4E4E4)         // 极浅灰（有黑边可辨）
)

/**
 * 墨水屏饼图：黑白深度填充 + 黑线分隔。
 */
@Composable
fun EinkPieChart(slices: List<PieSlice>, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val total = slices.sumOf { it.fen }
    Canvas(modifier.size(size)) {
        val border = Stroke(width = 1.5.dp.toPx())
        // 块间分隔线：纯白色，与边框同粗细
        val separator = Stroke(width = 1.5.dp.toPx())
        if (total <= 0) {
            drawCircle(color = PieFills.last(), style = border)
            return@Canvas
        }
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val radius = minOf(this.size.width, this.size.height) / 2f
        var start = -90f
        slices.forEachIndexed { i, s ->
            val sweep = s.fen.toFloat() / total * 360f
            drawArc(
                color = PieFills[i % PieFills.size],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = true
            )
            // 块间径向分隔线（圆心 → 圆周）：纯白色，每个扇形之间的边界清晰可见
            val rad = Math.toRadians(start.toDouble())
            val ex = cx + radius * cos(rad).toFloat()
            val ey = cy + radius * sin(rad).toFloat()
            drawLine(
                color = Color.White,
                start = Offset(cx, cy),
                end = Offset(ex, ey),
                strokeWidth = separator.width,
                cap = StrokeCap.Round
            )
            start += sweep
        }
        drawArc(
            color = InkBlack,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = border
        )
    }
}

/**
 * 饼图图例：色块（黑边）+ 名称 + 金额 + 百分比。
 */
@Composable
fun PieLegend(slices: List<PieSlice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.fen }
    Column(modifier) {
        slices.forEachIndexed { i, s ->
            val pct = if (total > 0) "%.0f%%".format(s.fen * 100.0 / total) else "--"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(PieFills[i % PieFills.size], RoundedCornerShape(2.dp))
                        .border(1.dp, InkBlack, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(formatFen(s.fen), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(pct, style = MaterialTheme.typography.bodySmall, color = GrayDark)
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}
