package com.huibenlema.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 分 → "¥820.60" / "¥1100"（整元省略小数） */
fun formatFen(fen: Long): String =
    if (fen % 100 == 0L) "¥${fen / 100}" else "¥${"%.2f".format(fen / 100.0)}"

/** 带符号差额："+¥352" / "-¥180.40" */
fun formatSignedFen(fen: Long): String =
    (if (fen >= 0) "+" else "-") + formatFen(abs(fen))

/** 回本百分比："74.6%" */
fun formatRatio(ratio: Double): String = "%.1f%%".format(ratio * 100)

/** 柱状图上的紧凑金额："¥32" / "¥0.5" */
fun formatCompactFen(fen: Long): String =
    if (fen % 100 == 0L) "¥${fen / 100}" else "¥${fen / 100.0}"

/** 同步时间："今天 14:32" / "10月3日 09:15" / "从未同步" */
fun formatSyncTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "从未同步"
    val dt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val day = if (dt.toLocalDate() == LocalDate.now()) "今天"
    else dt.toLocalDate().format(DateTimeFormatter.ofPattern("M月d日"))
    return "$day ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

/** 阅读秒数 → "2小时5分" / "45分钟" */
fun formatReadSeconds(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}

/** "2026-10-04" → "10月4日" */
fun formatCnDate(dateStr: String): String =
    LocalDate.parse(dateStr).format(DateTimeFormatter.ofPattern("M月d日"))

/** 注册时间（Unix 秒）→ "2023年10月3日" */
fun formatRegistDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
