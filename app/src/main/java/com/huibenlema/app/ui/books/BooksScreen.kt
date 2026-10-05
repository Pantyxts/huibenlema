package com.huibenlema.app.ui.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.domain.model.Book
import com.huibenlema.app.domain.model.PriceSource
import com.huibenlema.app.domain.repo.ResyncPriceResult
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.components.EinkChip
import com.huibenlema.app.ui.components.EinkDialog
import com.huibenlema.app.ui.components.formatFen
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.InkBlack
import kotlinx.coroutines.launch

/** 书籍列表：贡献价值大字、定价与来源在进度条下方，支持手动改价 */
@Composable
fun BooksScreen(vm: BooksViewModel = hiltViewModel()) {
    val books by vm.books.collectAsStateWithLifecycle()
    val sortState by vm.sortState.collectAsStateWithLifecycle()
    val totalValueFen by vm.totalValueFen.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Book?>(null) }
    val listState = rememberLazyListState()
    // 切换排序（含升降序）后回到列表顶部
    LaunchedEffect(sortState) { listState.scrollToItem(0) }

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
        // 一行排列：排序片 + 右侧筛选片（可横向滑动）；箭头只显示在当前排序维度上
        Row(
            Modifier
                .fillMaxWidth()
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
        Spacer(Modifier.height(8.dp))

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
                    BookRow(b, onClick = { editing = b })
                }
            }
        }
    }

    editing?.let { book ->
        PriceEditDialog(
            book = book,
            onDismiss = { editing = null },
            onSave = { text, isOfficial ->
                vm.savePrice(book, text, isOfficial)
                editing = null
            },
            onResync = { callback -> vm.resyncOfficialPrice(book, callback) }
        )
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(1.dp, InkBlack),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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
                    if (book.removed) {
                        Text(
                            "已移出书架（价值仍计入）",
                            style = MaterialTheme.typography.bodySmall,
                            color = GrayDark
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
                Text("${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
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
    onResync: ((ResyncPriceResult) -> Unit) -> Unit
) {
    var text by remember(book.bookId) {
        mutableStateOf(if (book.priceFen > 0) (book.priceFen / 100.0).toString() else "")
    }
    var resyncMsg by remember(book.bookId) { mutableStateOf<String?>(null) }
    // 同步官方价后填入的文本：保存时若未被修改，按官方价来源写入
    var resyncedText by remember(book.bookId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    EinkDialog(
        onDismissRequest = onDismiss,
        title = book.title,
        confirmText = "保存",
        onConfirm = { onSave(text, resyncedText == text) }
    ) {
        Text(
            "当前：${formatFen(book.priceFen)}${if (book.priceSource == PriceSource.NONE) "（未定价）" else "（${sourceLabel(book.priceSource)}）"}",
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
        Spacer(Modifier.height(10.dp))
        EinkButton(
            text = "同步微信读书官方价格",
            onClick = {
                scope.launch {
                    onResync { result ->
                        when (result) {
                            is ResyncPriceResult.Success -> {
                                // 直接填入价格，不显示提示（保存时才生效）
                                text = (result.priceFen / 100.0).toString()
                                resyncedText = text
                                resyncMsg = null
                            }
                            ResyncPriceResult.NoCredential -> {
                                resyncedText = null
                                resyncMsg = "请先扫码登录"
                            }
                            ResyncPriceResult.NotFound -> {
                                resyncedText = null
                                resyncMsg = "未找到官方价格"
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        resyncMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text("改价需点「保存」后生效；手动价格优先，自动同步不会覆盖", style = MaterialTheme.typography.bodySmall, color = GrayDark)
    }
}

private fun sourceLabel(s: PriceSource): String = when (s) {
    PriceSource.WEREAD -> "价值来源：微信读书"
    PriceSource.ISBN -> "价值来源：ISBN 接口"
    PriceSource.MANUAL -> "价值来源：手动"
    PriceSource.NONE -> ""
}
