package com.huibenlema.app.ui.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.domain.repo.ResyncProgressResult
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.components.EinkChip
import com.huibenlema.app.ui.components.EinkDialog
import com.huibenlema.app.ui.components.formatFen
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.InkBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 批量操作类型（确认弹窗用） */
private enum class BatchAction { FINISH, FETCH_PRICE, FETCH_PROGRESS, HIDE, RESTORE, DELETE, DELETE_HIDE }

/** 书籍列表：贡献价值大字、定价与来源在进度条下方，支持手动改价与批量操作 */
@Composable
fun BooksScreen(vm: BooksViewModel = hiltViewModel()) {
    val books by vm.books.collectAsStateWithLifecycle()
    val sortState by vm.sortState.collectAsStateWithLifecycle()
    val shelfFilter by vm.shelfFilterState.collectAsStateWithLifecycle()
    val totalValueFen by vm.totalValueFen.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Book?>(null) }
    var addVisible by remember { mutableStateOf(false) }
    // 删除自定义书籍的二次确认
    var deleteConfirm by remember { mutableStateOf(false) }
    // 批量模式状态：选中书存对象列表（跨筛选分类累计，切分类不清空）
    var batchMode by remember { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(listOf<Book>()) }
    var confirmAction by remember { mutableStateOf<BatchAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 批量操作结果提示（非弹窗，显示在列表上方；完成后 10 秒自动消失，进行中不消失）
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusAutoHide by remember { mutableStateOf(true) }
    // 恢复定价/进度失败的书（点击查看列表用）
    var failedBooks by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var showFailedBooks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(statusMsg, statusAutoHide) {
        val msg = statusMsg ?: return@LaunchedEffect
        if (statusAutoHide) {
            delay(STATUS_MSG_AUTO_HIDE_MS)
            if (statusMsg == msg) {
                statusMsg = null
                failedBooks = emptyList()
            }
        }
    }
    val listState = rememberLazyListState()
    // 切换排序（含升降序）后回到列表顶部
    LaunchedEffect(sortState) { listState.scrollToItem(0) }

    fun exitBatch() {
        batchMode = false
        selectedBooks = emptyList()
    }

    // 选中书类型统计（操作按钮与确认文案按类型动态变化）
    val selectedIds = selectedBooks.map { it.bookId }.toSet()
    val customCount = selectedBooks.count { it.bookId.startsWith(CUSTOM_BOOK_PREFIX) }
    val wereadCount = selectedBooks.size - customCount

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        // 标题行：书值 + 总价值（右上）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("书值", style = MaterialTheme.typography.titleLarge)
            Text(
                "总价值 ${formatFen(totalValueFen)}",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        val arrow = if (sortState.ascending) "↑" else "↓"
        // 一行排列：排序片（可横向滑动）+ 添加书籍 + 多选 + 书架筛选片固定右顶格；
        // 多选按钮位于筛选片（全部）左边，点击进入/退出批量选择模式
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EinkChip(
                    label = if (sortState.key == BookSort.VALUE) "价值 $arrow" else "价值",
                    selected = sortState.key == BookSort.VALUE
                ) { vm.toggleSort(BookSort.VALUE) }
                EinkChip(
                    label = if (sortState.key == BookSort.PROGRESS) "进度 $arrow" else "进度",
                    selected = sortState.key == BookSort.PROGRESS
                ) { vm.toggleSort(BookSort.PROGRESS) }
                EinkChip(
                    label = if (sortState.key == BookSort.PRICE) "定价 $arrow" else "定价",
                    selected = sortState.key == BookSort.PRICE
                ) { vm.toggleSort(BookSort.PRICE) }
            }
            Spacer(Modifier.width(4.dp))
            // 添加书籍（恒黑框）
            EinkChip(
                label = "+ 添加书籍",
                selected = true
            ) { addVisible = true }
            Spacer(Modifier.width(4.dp))
            // 多选开关（白底黑字黑框，状态由文字表达）
            EinkChip(
                label = if (batchMode) "多选中" else "多选",
                selected = true,
                whiteSelected = true
            ) {
                statusMsg = null
                if (batchMode) exitBatch() else batchMode = true
            }
            Spacer(Modifier.width(4.dp))
            // 筛选片白底黑字黑框：当前筛选态由文字（全部/在书架/不在书架）表达；
            // 批量模式下也可切分类，已勾选跨分类保留
            EinkChip(
                label = shelfFilter.label,
                selected = true,
                whiteSelected = true
            ) { vm.cycleFilter() }
        }
        // 批量操作条：勾选书籍后操作；删除/隐藏按钮按选中书类型动态变化；退出批量固定最右
        if (batchMode) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val allSelected = books.isNotEmpty() && books.all { it.bookId in selectedIds }
                    EinkChip(
                        label = if (allSelected) "取消全选" else "全选",
                        selected = true,
                        whiteSelected = true
                    ) {
                        selectedBooks = if (allSelected) emptyList()
                        else (selectedBooks + books).distinctBy { it.bookId }
                    }
                    Text(
                        "已选 ${selectedBooks.size} 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = GrayDark
                    )
                    EinkChip(label = "读完", selected = true, whiteSelected = true) {
                        if (selectedBooks.isEmpty()) notice = "请先勾选要操作的书籍"
                        else confirmAction = BatchAction.FINISH
                    }
                    // 隐藏/删除（在读完右边）：未选时默认显示「隐藏/删除」；
                    // 选中的全是隐藏书→恢复显示；全自定义→删除；全微信读书→隐藏；混合→隐藏/删除
                    val allHidden = selectedBooks.isNotEmpty() && selectedBooks.all { it.hidden }
                    val hideDeleteLabel = when {
                        allHidden -> "恢复显示"
                        customCount > 0 && wereadCount > 0 -> "隐藏/删除"
                        customCount > 0 -> "删除"
                        wereadCount > 0 -> "隐藏"
                        else -> "隐藏/删除" // 未选中时的默认展示
                    }
                    val hideDeleteAction = when {
                        allHidden -> BatchAction.RESTORE
                        customCount > 0 && wereadCount > 0 -> BatchAction.DELETE_HIDE
                        customCount > 0 -> BatchAction.DELETE
                        else -> BatchAction.HIDE
                    }
                    EinkChip(label = hideDeleteLabel, selected = true, whiteSelected = true) {
                        if (selectedBooks.isEmpty()) notice = "请先勾选要操作的书籍"
                        else confirmAction = hideDeleteAction
                    }
                    // 恢复定价（在隐藏/删除右边）
                    if (wereadCount > 0) {
                        EinkChip(label = "恢复定价", selected = true, whiteSelected = true) {
                            if (selectedBooks.isEmpty()) notice = "请先勾选要操作的书籍"
                            else confirmAction = BatchAction.FETCH_PRICE
                        }
                        // 恢复进度（在恢复定价右边）：以微信读书为准，覆盖手动进度
                        EinkChip(label = "恢复进度", selected = true, whiteSelected = true) {
                            if (selectedBooks.isEmpty()) notice = "请先勾选要操作的书籍"
                            else confirmAction = BatchAction.FETCH_PROGRESS
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                EinkChip(label = "退出批量", selected = true, whiteSelected = true) { exitBatch() }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 批量恢复定价的进行中/结果提示（非弹窗）；失败可点击查看失败书籍
        statusMsg?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (failedBooks.isNotEmpty()) {
                    EinkChip(label = "查看失败书籍", selected = true, whiteSelected = true) {
                        showFailedBooks = true
                    }
                }
            }
        }

        if (books.isEmpty()) {
            Text(
                "暂无书籍。未定价的书点击即可手动补录价格。",
                style = MaterialTheme.typography.bodyMedium,
                color = GrayDark
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(books, key = { it.bookId }) { b ->
                    BookRow(
                        book = b,
                        batchMode = batchMode,
                        selected = b.bookId in selectedIds,
                        onClick = {
                            if (batchMode) {
                                // 批量模式：点击行切换勾选（跨筛选分类累计）
                                selectedBooks = if (b.bookId in selectedIds) {
                                    selectedBooks.filter { it.bookId != b.bookId }
                                } else {
                                    selectedBooks + b
                                }
                            } else {
                                editing = b
                            }
                        }
                    )
                }
            }
        }
    }

    // 批量操作确认弹窗（删除/隐藏类会明确提示微信读书书是隐藏、自定义书是删除）
    confirmAction?.let { action ->
        val count = selectedIds.size
        val (title, detail) = when (action) {
            BatchAction.FINISH -> "标记已读完" to
                "确定将选中的 $count 本书标记为已读完？进度将设为 100%，按 100% 计入价值。"
            BatchAction.FETCH_PRICE -> "恢复定价" to
                "确定恢复选中的 $count 本书的微信读书官方定价？手动定价将被官方价覆盖。"
            BatchAction.FETCH_PROGRESS -> "恢复进度" to
                "确定恢复选中的 $count 本书的微信读书阅读进度？手动修改的进度将被微信读书进度覆盖。"
            BatchAction.HIDE -> "批量隐藏" to
                "确定隐藏选中的 $count 本微信读书书籍？隐藏后不参与任何计算，可在设置-隐藏书籍中查看并恢复。"
            BatchAction.RESTORE -> "恢复显示" to
                "确定恢复显示选中的 $count 本书？恢复后将重新参与价值计算。"
            BatchAction.DELETE -> "删除书籍" to
                "确定删除选中的 $count 本自定义书籍？删除后不可恢复。"
            BatchAction.DELETE_HIDE -> "隐藏/删除" to
                "确定执行选中 $count 本书？微信读书书籍将隐藏（可在设置-隐藏书籍中恢复），自定义书籍将删除（不可恢复）。"
        }
        EinkDialog(
            onDismissRequest = { confirmAction = null },
            title = title,
            confirmText = "确定",
            onConfirm = {
                val ids = selectedIds.toList()
                when (action) {
                    BatchAction.FINISH -> vm.markBooksFinished(ids)
                    BatchAction.FETCH_PRICE -> {
                        // 仅微信读书书有官方定价；逐本进度提示请勿离开，完成后反馈结果（10 秒自动消失）
                        val wereadIds = selectedBooks
                            .filter { !it.bookId.startsWith(CUSTOM_BOOK_PREFIX) }
                            .map { it.bookId }
                        statusAutoHide = false
                        statusMsg = "正在恢复定价…"
                        scope.launch {
                            vm.batchFetchPrice(
                                wereadIds,
                                onProgress = { done, total ->
                                    // 每 5 本更新一次：万本量级时避免频繁重组
                                    if (done % 5 == 0 || done == total) {
                                        statusMsg = "正在恢复定价 $done/$total…（请勿离开此页）"
                                    }
                                },
                                onResult = { r ->
                                    statusMsg = "恢复定价完成：成功 ${r.okCount} 本，失败 ${r.failedCount} 本"
                                    failedBooks = r.failedBooks
                                    statusAutoHide = true
                                }
                            )
                        }
                    }
                    BatchAction.FETCH_PROGRESS -> {
                        // 以微信读书为准恢复进度（覆盖手动进度并清除「手动」标记）；逐本进度提示请勿离开
                        val wereadIds = selectedBooks
                            .filter { !it.bookId.startsWith(CUSTOM_BOOK_PREFIX) }
                            .map { it.bookId }
                        statusAutoHide = false
                        statusMsg = "正在恢复进度…"
                        scope.launch {
                            vm.batchFetchProgress(
                                wereadIds,
                                onProgress = { done, total ->
                                    // 每 5 本更新一次：万本量级时避免频繁重组
                                    if (done % 5 == 0 || done == total) {
                                        statusMsg = "正在恢复进度 $done/$total…（请勿离开此页）"
                                    }
                                },
                                onResult = { r ->
                                    statusMsg = if (r.notice != null) {
                                        "恢复进度完成：成功 ${r.okCount} 本，失败 ${r.failedCount} 本；${r.notice}"
                                    } else {
                                        "恢复进度完成：成功 ${r.okCount} 本，失败 ${r.failedCount} 本"
                                    }
                                    failedBooks = r.failedBooks
                                    statusAutoHide = true
                                }
                            )
                        }
                    }
                    BatchAction.HIDE -> vm.setBooksHidden(ids, hidden = true)
                    BatchAction.RESTORE -> vm.setBooksHidden(ids, hidden = false)
                    BatchAction.DELETE -> vm.deleteBooks(ids)
                    BatchAction.DELETE_HIDE -> vm.batchDeleteOrHide(selectedBooks)
                }
                confirmAction = null
                exitBatch()
            }
        ) {
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // 恢复定价失败书籍列表
    if (showFailedBooks) {
        EinkDialog(
            onDismissRequest = { showFailedBooks = false },
            title = "未获取到定价的书籍（${failedBooks.size} 本）",
            confirmText = "知道了",
            onConfirm = { showFailedBooks = false }
        ) {
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                failedBooks.forEach { (_, title) ->
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }
        }
    }

    // 批量操作前置提示
    notice?.let { msg ->
        EinkDialog(
            onDismissRequest = { notice = null },
            title = "提示",
            confirmText = "知道了",
            onConfirm = { notice = null }
        ) {
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
    }

    editing?.let { book ->
        val isCustom = book.bookId.startsWith(CUSTOM_BOOK_PREFIX)
        PriceEditDialog(
            book = book,
            onDismiss = { editing = null },
            onSave = { text, isOfficial ->
                vm.savePrice(book, text, isOfficial)
                editing = null
            },
            onResync = { callback -> vm.resyncOfficialPrice(book, callback) },
            onResyncProgress = { callback -> vm.resyncBookProgress(book, callback) },
            isCustom = isCustom,
            onSaveProgress = { pct, fromSync ->
                vm.saveProgress(book, pct, fromSync)
                editing = null
            },
            onToggleHidden = {
                vm.setBookHidden(book, !book.hidden)
                editing = null
            },
            onDelete = { deleteConfirm = true }
        )
    }

    // 删除自定义书籍确认（不可恢复，需二次确认）
    if (deleteConfirm && editing != null) {
        EinkDialog(
            onDismissRequest = { deleteConfirm = false },
            title = "删除书籍",
            confirmText = "确认删除",
            onConfirm = {
                editing?.let { vm.deleteBook(it) }
                editing = null
                deleteConfirm = false
            }
        ) {
            Text(
                "确定删除「${editing?.title ?: ""}」？删除后不可恢复。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (addVisible) {
        AddBookDialog(
            onDismiss = { addVisible = false },
            onSave = { title, author, priceYuan, progressPct ->
                vm.addCustomBook(title, author, priceYuan, progressPct)
                addVisible = false
            }
        )
    }
}

@Composable
private fun BookRow(book: Book, batchMode: Boolean, selected: Boolean, onClick: () -> Unit) {
    // indication = null：禁用点击涟漪，墨水屏上勾选/取消勾选不会出现闪框
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        border = BorderStroke(1.dp, InkBlack),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                if (batchMode) {
                    // 勾选框：Canvas 直线勾（与黑白锐利 UI 风格一致），未选中画白色不可见
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(1.dp, InkBlack),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(12.dp)) {
                            val color = if (selected) InkBlack else Color.White
                            val w = size.width
                            val h = size.height
                            val sw = w * 0.16f
                            // 短竖线 + 长斜线，Square 线帽保持锐利无圆角
                            drawLine(
                                color = color,
                                start = Offset(w * 0.14f, h * 0.52f),
                                end = Offset(w * 0.42f, h * 0.80f),
                                strokeWidth = sw,
                                cap = StrokeCap.Square
                            )
                            drawLine(
                                color = color,
                                start = Offset(w * 0.42f, h * 0.80f),
                                end = Offset(w * 0.92f, h * 0.18f),
                                strokeWidth = sw,
                                cap = StrokeCap.Square
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = GrayDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 贡献价值大字优先；无价书显示 ¥0
                Text(
                    formatFen(book.contributedFen),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(GrayLight)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = book.progress.coerceIn(0.0, 1.0).toFloat())
                        .fillMaxHeight()
                        .background(InkBlack)
                )
            }
            Spacer(Modifier.height(4.dp))
            // 定价与价值来源在进度条下方
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${(book.progress * 100).toInt()}%" +
                        if (book.progressManual) "（手动）" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                if (book.priceSource != PriceSource.NONE) {
                    Text(
                        "定价 ${formatFen(book.priceFen)} · ${sourceLabel(book.priceSource)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GrayDark
                    )
                } else {
                    Text("点击补录价格", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                }
            }
        }
    }
}

@Composable
private fun PriceEditDialog(
    book: Book,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit,
    onResync: ((ResyncPriceResult) -> Unit) -> Unit,
    onResyncProgress: ((ResyncProgressResult) -> Unit) -> Unit,
    isCustom: Boolean,
    onSaveProgress: (Int, Boolean) -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(book.bookId) {
        mutableStateOf(if (book.priceFen > 0) (book.priceFen / 100.0).toString() else "")
    }
    var resyncMsg by remember(book.bookId) { mutableStateOf<String?>(null) }
    var progressSyncMsg by remember(book.bookId) { mutableStateOf<String?>(null) }
    // 同步官方价后填入的文本：保存时若未被修改，按官方价来源写入
    var resyncedText by remember(book.bookId) { mutableStateOf<String?>(null) }
    // 同步官方进度后填入的百分比：保存时若等于该值，按「恢复进度」写入（不显示手动标记）
    var syncedProgressPct by remember(book.bookId) { mutableStateOf<Int?>(null) }
    // 阅读进度调节（0-100）
    var progressText by remember(book.bookId) {
        mutableStateOf(((book.progress * 100).toInt()).toString())
    }
    val scope = rememberCoroutineScope()

    EinkDialog(
        onDismissRequest = onDismiss,
        title = book.title,
        confirmText = "保存",
        onConfirm = {
            // 价格仅在数值发生变化时保存：未改动直接点保存不写入，来源不会无谓变成手动
            val newFen = (text.toDoubleOrNull() ?: 0.0).times(100).toLong()
            if (newFen != book.priceFen) {
                onSave(text, resyncedText == text)
            }
            val pct = progressText.toIntOrNull()?.coerceIn(0, 100)
            if (pct != null && pct != (book.progress * 100).toInt()) {
                onSaveProgress(pct, pct == syncedProgressPct)
            }
        }
    ) {
        // 定价 + 进度两个输入框，小屏设备内容超高时滚动显示
        Column(
            Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "当前：${formatFen(book.priceFen)}${if (book.priceSource == PriceSource.NONE) "（未定价）" else "（${sourceLabel(book.priceSource)}）"}" +
                    " · 进度 ${(book.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = GrayDark
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                label = { Text("定价（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = progressText,
                onValueChange = { input ->
                    progressText = input.filter { it.isDigit() }.take(3)
                },
                label = { Text("阅读进度（%，0-100）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            if (!isCustom) {
                // 自定义书籍无官方记录，不提供官方价同步
                EinkButton(
                    text = "同步微信读书官方价格",
                    onClick = {
                        scope.launch {
                            onResync { result ->
                                when (result) {
                                    is ResyncPriceResult.Success -> {
                                        // 填入价格，提示同步成功（保存时才落库）
                                        text = (result.priceFen / 100.0).toString()
                                        resyncedText = text
                                        resyncMsg = "同步成功！价格：${formatFen(result.priceFen)}"
                                    }
                                    ResyncPriceResult.NoCredential -> {
                                        resyncedText = null
                                        resyncMsg = "请先扫码登录"
                                    }
                                    is ResyncPriceResult.NotFound -> {
                                        resyncedText = null
                                        resyncMsg = result.message
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                // 单书进度同步：查询结果填入进度输入框，点「保存」后才应用
                EinkButton(
                    text = "同步微信读书阅读进度",
                    onClick = {
                        scope.launch {
                            onResyncProgress { result ->
                                when (result) {
                                    is ResyncProgressResult.Success -> {
                                        progressText = result.progressPct.toString()
                                        syncedProgressPct = result.progressPct
                                        progressSyncMsg = if (result.finished) {
                                            "已获取：微信读书进度 ${result.progressPct}%（已读完），点「保存」后生效"
                                        } else {
                                            "已获取：微信读书进度 ${result.progressPct}%，点「保存」后生效"
                                        }
                                    }
                                    ResyncProgressResult.NoCredential -> {
                                        progressSyncMsg = "请先扫码登录"
                                    }
                                    is ResyncProgressResult.Failed -> {
                                        progressSyncMsg = result.message
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                // 隐藏/恢复：微信读书书可隐藏（设置-数据-隐藏书籍中恢复）；自定义书只能删除
                EinkButton(
                    text = if (book.hidden) "恢复显示" else "隐藏此书",
                    onClick = {
                        onToggleHidden()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 同步结果提示统一显示在底部按钮之后
            resyncMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            progressSyncMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            if (isCustom) {
                Spacer(Modifier.height(10.dp))
                EinkButton(
                    text = "删除此书",
                    onClick = {
                        onDismiss()
                        onDelete()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 手动添加的自定义书籍 bookId 前缀（与 BookRepositoryImpl.addCustomBook 保持一致） */
private const val CUSTOM_BOOK_PREFIX = "CUSTOM_"

/** 批量操作结果提示自动消失时间 */
private const val STATUS_MSG_AUTO_HIDE_MS = 10_000L

/** 添加自定义书籍：书名必填，作者/定价/进度可选；定价留空为未定价（后续点击补录） */
@Composable
private fun AddBookDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, author: String, priceYuan: String, progressPct: Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("100") }

    EinkDialog(
        onDismissRequest = onDismiss,
        title = "添加书籍",
        confirmText = "保存",
        onConfirm = {
            if (title.isNotBlank()) {
                onSave(title, author, price, progress.toIntOrNull()?.coerceIn(0, 100) ?: 100)
            }
        }
    ) {
        // 四个输入框，小屏设备内容超高时滚动显示
        Column(
            Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("书名（必填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("作者（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = price,
                onValueChange = { input -> price = input.filter { it.isDigit() || it == '.' } },
                label = { Text("定价（元，可选）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = progress,
                onValueChange = { input -> progress = input.filter { it.isDigit() }.take(3) },
                label = { Text("阅读进度（%，0-100）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "自定义书籍按设定进度计入价值，不会参与微信读书同步",
                style = MaterialTheme.typography.bodySmall,
                color = GrayDark
            )
        }
    }
}

private fun sourceLabel(s: PriceSource): String = when (s) {
    PriceSource.WEREAD -> "价值来源：微信读书"
    PriceSource.ISBN -> "价值来源：ISBN 接口"
    PriceSource.MANUAL -> "价值来源：手动"
    PriceSource.NONE -> ""
}
