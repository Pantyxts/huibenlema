package com.huibenlema.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 墨水屏黑白配色：近黑 + 白 + 三阶灰 */
val InkBlack = Color(0xFF111111)
val GrayDark = Color(0xFF444444)
val GrayMid = Color(0xFF999999)
val GrayLight = Color(0xFFEEEEEE)

private val EinkColors = lightColorScheme(
    primary = InkBlack,
    onPrimary = Color.White,
    secondary = GrayDark,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = InkBlack,
    surface = Color.White,
    onSurface = InkBlack,
    surfaceVariant = GrayLight,
    onSurfaceVariant = InkBlack,
    outline = GrayMid,
    error = InkBlack,
    onError = Color.White
)

private val EinkTypography = Typography(
    displayLarge = eink(34.sp, FontWeight.Bold),
    headlineMedium = eink(24.sp, FontWeight.Bold),
    titleLarge = eink(20.sp, FontWeight.Bold),
    titleMedium = eink(17.sp, FontWeight.Bold),
    bodyLarge = eink(16.sp, FontWeight.Normal),
    bodyMedium = eink(14.sp, FontWeight.Normal),
    bodySmall = eink(12.sp, FontWeight.Normal),
    labelLarge = eink(15.sp, FontWeight.Bold),
    labelMedium = eink(13.sp, FontWeight.Normal)
)

private fun eink(size: TextUnit, weight: FontWeight) =
    TextStyle(fontSize = size, fontWeight = weight, color = InkBlack, fontFamily = FontFamily.SansSerif)

/** 空指示节点：点击无涟漪（墨水屏无动画） */
private class EmptyIndicationNode : Modifier.Node()

private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyIndicationNode()
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * 墨水屏主题：黑白高对比、全局禁用点击涟漪（避免残影）。
 * 阶段 3 的 universal 彩色主题按 flavor 分支，不影响此文件。
 */
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    // 全局提供空指示：所有可点击组件不再绘制涟漪动画
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        MaterialTheme(
            colorScheme = EinkColors,
            typography = EinkTypography,
            content = content
        )
    }
}
