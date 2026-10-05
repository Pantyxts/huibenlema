package com.huibenlema.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.GrayMid
import com.huibenlema.app.ui.theme.InkBlack

/**
 * 墨水屏主按钮：白底黑框黑字；按下时黑色反转（黑底白字）作为点击反馈，无涟漪无阴影。
 */
@Composable
fun EinkButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val invert = pressed && enabled
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (invert) InkBlack else Color.White,
            contentColor = if (invert) Color.White else InkBlack,
            disabledContainerColor = GrayLight,
            disabledContentColor = GrayMid
        ),
        border = BorderStroke(2.dp, if (enabled) InkBlack else GrayMid)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/**
 * 墨水屏筛选片：选中 = 浅灰底 + 黑粗边框 + 加粗；按下时黑色反转。
 * [whiteSelected] = true 时选中态保持白底黑字（书值页多选/筛选/批量操作按钮用，不要灰底）。
 */
@Composable
fun EinkChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    whiteSelected: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val invert = pressed
    Box(
        modifier
            .border(2.dp, if (selected || invert) InkBlack else GrayMid, shape)
            .background(
                when {
                    invert -> InkBlack
                    selected && whiteSelected -> Color.White
                    selected -> GrayLight
                    else -> Color.White
                },
                shape
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (invert) Color.White else InkBlack,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * 墨水屏对话框：白底 + 2dp 黑边框，无阴影。确认按钮为 EinkButton，取消为纯文字。
 */
@Composable
fun EinkDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String = "取消",
    leftButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = shape,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, InkBlack, shape)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧附加按钮（如"删除"），默认占位保持右侧对齐
                    if (leftButton != null) {
                        leftButton()
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismissRequest) {
                            Text(dismissText, color = GrayDark)
                        }
                        Spacer(Modifier.width(8.dp))
                        EinkButton(text = confirmText, onClick = onConfirm)
                    }
                }
            }
        }
    }
}
