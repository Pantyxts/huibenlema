package com.huibenlema.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huibenlema.app.domain.model.PaybackSummary
import com.huibenlema.app.ui.theme.InkBlack

/**
 * 回本进度卡：白底黑框（墨水屏无残影），大号百分比 + 进度条。
 */
@Composable
fun PaybackBar(summary: PaybackSummary?, modifier: Modifier = Modifier) {
    val ratio = summary?.ratio ?: 0.0
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, InkBlack, shape)
            .background(Color.White, shape)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("回本进度", style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (summary != null && summary.hasCost) formatRatio(summary.ratio) else "--%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        // 进度条：白底 + 黑框 + 黑色填充
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .border(1.dp, InkBlack)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = ratio.coerceIn(0.0, 1.0).toFloat())
                    .fillMaxHeight()
                    .background(InkBlack)
            )
        }
        Spacer(Modifier.height(10.dp))

        when {
            summary == null -> Text("等待同步", style = MaterialTheme.typography.bodyMedium)
            !summary.hasCost -> Text(
                "请先到「设置」添加成本条目",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            summary.diffFen >= 0 -> Text(
                "已回本！净赚 ${formatFen(summary.diffFen)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            else -> Text(
                "还差 ${formatFen(-summary.diffFen)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        val toGo = summary?.booksToGo ?: 0
        if (toGo > 0) {
            Spacer(Modifier.height(4.dp))
            Text("按平均书价，再读约 $toGo 本书回本", style = MaterialTheme.typography.bodySmall)
        }
    }
}
