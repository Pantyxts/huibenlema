package com.huibenlema.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.InkBlack

/** 同步进度条：百分比 + 阶段说明 + 细进度条（首页、设置页、引导页同款） */
@Composable
fun SyncProgressBar(percent: Int, label: String) {
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
