package com.huibenlema.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huibenlema.app.domain.model.DailyStat
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.InkBlack
import java.time.LocalDate

/**
 * 每日阅读价值柱状图（黑白，无动画）。
 * - 7 天：显示日期横坐标与柱顶价值；30 天：均不显示（点击柱子查看）
 * - 选中状态由父级持有（selectedDate/onSelect），点击柱子回调
 */
@Composable
fun DailyBarChart(
    stats: List<DailyStat>,
    days: Int,
    selectedDate: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val filled = remember(stats, days) { fillMissingDays(stats, days) }
    val maxFen = maxOf(filled.maxOfOrNull { it.valueFen } ?: 0L, 1L)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            filled.forEach { s ->
                val isSel = s.date == selectedDate
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        // indication = null：禁用点击涟漪，墨水屏上点击时柱体区域零变化
                        .clickable(interactionSource = null, indication = null) { onSelect(if (isSel) null else s.date) }
                ) {
                    Text(
                        // 近 7 天在柱顶显示价值；近 30 天不显示（点击柱子查看）
                        text = if (days <= 7 && s.valueFen > 0) formatCompactFen(s.valueFen) else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 9.sp,
                        color = InkBlack
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 选中标记：柱顶上方悬浮圆点（未选中时透明占位，柱高保持不变）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(9.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (isSel) {
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .background(InkBlack, CircleShape)
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(
                                    fraction = (s.valueFen.toFloat() / maxFen)
                                        .coerceIn(0.04f, 1f)
                                )
                                .background(if (s.valueFen > 0) InkBlack else GrayLight)
                        )
                    }
                }
            }
        }
        // 基线（黑色加深）
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(InkBlack)
        )
        // 日期标签：仅 7 天显示
        if (days <= 7) {
            Row(Modifier.fillMaxWidth()) {
                filled.forEach { s ->
                    Text(
                        text = s.date.takeLast(5).replace("-", "/"),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 9.sp,
                        color = InkBlack
                    )
                }
            }
        }
    }
}

private fun fillMissingDays(stats: List<DailyStat>, days: Int): List<DailyStat> {
    val map = stats.associateBy { it.date }
    val today = LocalDate.now()
    return (days - 1 downTo 0).map { offset ->
        val d = today.minusDays(offset.toLong()).toString()
        map[d] ?: DailyStat(date = d, valueFen = 0L, readSeconds = 0L, bookCount = 0)
    }
}
