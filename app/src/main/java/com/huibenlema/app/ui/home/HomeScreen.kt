package com.huibenlema.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.ui.components.DailyBarChart
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.components.EinkChip
import com.huibenlema.app.ui.components.EinkPieChart
import com.huibenlema.app.ui.components.PaybackBar
import com.huibenlema.app.ui.components.PieLegend
import com.huibenlema.app.ui.components.formatCnDate
import com.huibenlema.app.ui.components.formatFen
import com.huibenlema.app.ui.components.formatReadSeconds
import com.huibenlema.app.ui.components.formatSyncTime
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.InkBlack
import androidx.compose.ui.text.style.TextOverflow

/** 首页：回本进度 + 统计卡 + 每日价值 + 构成分析（饼图）+ 同步 */
@Composable
fun HomeScreen(
    vm: HomeViewModel = hiltViewModel(),
    onGoBooks: () -> Unit,
    onGoSettings: () -> Unit
) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val pendingUpdate by vm.pendingUpdateVersion.collectAsStateWithLifecycle()
    val dailyStats by vm.dailyStats.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val hasCredential by vm.hasCredential.collectAsStateWithLifecycle()
    val days by vm.selectedDays.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val costSlices by vm.costSlices.collectAsStateWithLifecycle()
    val bookSlices by vm.bookSlices.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val efficiency by vm.efficiencyFenPerHour.collectAsStateWithLifecycle()

    // 柱状图数据：每日价值 = 官方每日时长 × 平均效率（时长准确，价值为全局平均估算）
    val chartStats = remember(dailyStats, efficiency) {
        if (efficiency <= 0) dailyStats.map { it.copy(valueFen = 0L) }
        else dailyStats.map { it.copy(valueFen = it.readSeconds * efficiency / 3600) }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("回本了吗", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // 同步进度条（页面顶部：同步区块在页面底部，进度条放这里才可见）
        if (syncing) {
            SyncProgressBar(syncProgress.percent, syncProgress.label)
            Spacer(Modifier.height(12.dp))
        }

        if (!hasCredential) {
            Banner("尚未登录微信读书，点击去「设置 → 登录信息」扫码登录", onClick = onGoSettings)
            Spacer(Modifier.height(12.dp))
        }

        pendingUpdate?.let {
            Banner("发现新版本 v$it，点击去「设置」更新", onClick = onGoSettings)
            Spacer(Modifier.height(12.dp))
        }

        PaybackBar(summary)
        Spacer(Modifier.height(12.dp))

        // 价值 / 成本
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueCard(
                label = "已读价值",
                value = summary?.let { formatFen(it.totalValueFen) } ?: "--",
                modifier = Modifier.weight(1f),
                onClick = onGoBooks
            )
            ValueCard(
                label = "总成本",
                value = summary?.let { formatFen(it.totalCostFen) } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        Spacer(Modifier.height(16.dp))

        // 每日价值（四圆角卡片）
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("每日价值", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EinkChip("近7天", days == 7) { vm.selectDays(7) }
                        EinkChip("近30天", days == 30) { vm.selectDays(30) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                DailyBarChart(
                    stats = chartStats,
                    days = days,
                    selectedDate = selectedDay,
                    onSelect = vm::selectDay
                )
            }
        }

        // 选中日的明细：官方时长（准确）+ 按平均效率估算的价值
        val selDay = selectedDay
        if (selDay != null) {
            val dayStat = dailyStats.find { it.date == selDay }
            val estValue = if (efficiency > 0) ((dayStat?.readSeconds ?: 0L) * efficiency / 3600) else 0L
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, InkBlack),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(formatCnDate(selDay), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "价值：${if (efficiency > 0) formatFen(estValue) else "--"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "时间：${dayStat?.readSeconds?.let { formatReadSeconds(it) } ?: "--"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "价值/时间：${if (efficiency > 0) formatFen(efficiency) + "/小时" else "--"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(16.dp))

        // 构成分析：成本占比 + 书值占比（大饼图居中 + 图例在下方）
        Text("构成分析", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("成本占比", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (costSlices.isEmpty()) {
                    Text("还没有成本条目，去「设置 → 成本一览」添加", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                } else {
                    EinkPieChart(costSlices, Modifier.size(140.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    PieLegend(costSlices)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("书值占比", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (bookSlices.isEmpty()) {
                    Text("同步后显示，定价的书会计入价值", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                } else {
                    EinkPieChart(bookSlices, Modifier.size(140.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    PieLegend(bookSlices)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 同步
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "上次同步：${formatSyncTime(lastSyncAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GrayDark
                )
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            EinkButton(
                text = if (syncing) "同步中…" else "立即同步",
                onClick = vm::sync,
                enabled = !syncing
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 同步进度条：百分比 + 阶段说明 + 细进度条（与设置页同款） */
@Composable
private fun SyncProgressBar(percent: Int, label: String) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(
                "$percent%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(GrayLight)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = percent / 100f)
                    .fillMaxHeight()
                    .background(InkBlack)
            )
        }
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(2.dp, InkBlack, RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(text, color = InkBlack, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ValueCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        border = BorderStroke(1.dp, InkBlack),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = GrayDark)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
