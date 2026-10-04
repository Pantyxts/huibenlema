package com.huibenlema.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.BuildConfig
import com.huibenlema.app.domain.model.CostCategory
import com.huibenlema.app.domain.model.CostItem
import com.huibenlema.app.domain.model.displayName
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.components.EinkChip
import com.huibenlema.app.ui.components.EinkDialog
import com.huibenlema.app.ui.components.formatFen
import com.huibenlema.app.ui.components.formatSyncTime
import com.huibenlema.app.ui.login.LoginScreen
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.GrayLight
import com.huibenlema.app.ui.theme.GrayMid
import com.huibenlema.app.ui.theme.InkBlack
import java.time.LocalDate

/** 设置页：成本一览 / 登录信息 / 同步 / 数据 / 关于（白底黑字无阴影） */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val costItems by vm.costItems.collectAsStateWithLifecycle()
    val hasCookie by vm.hasCookie.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val autoSync by vm.autoSync.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val accountName by vm.accountName.collectAsStateWithLifecycle()
    val accountVid by vm.accountVid.collectAsStateWithLifecycle()
    val customCategories by vm.customCategories.collectAsStateWithLifecycle()

    // 数据导出：系统文件选择器（SAF），零存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportData(it) } }

    // 数据导入：SAF 选文件，确认后恢复
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.requestImport(it) } }

    if (vm.showLogin) {
        LoginScreen(onClose = vm::closeLogin, onLoginSuccess = vm::sync)
        return
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // ---- 成本一览 ----
        SectionTitle("成本一览")
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (costItems.isEmpty()) {
                    Text("还没有成本条目", style = MaterialTheme.typography.bodyMedium, color = GrayDark)
                }
                costItems.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.openEditCost(item) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                costItemLabel(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = GrayDark
                            )
                        }
                        Text(formatFen(item.priceFen), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = vm::openAddCost) { Text("+ 添加条目") }
                    if (costItems.isNotEmpty()) {
                        Text(
                            "合计 ${formatFen(costItems.sumOf { it.priceFen })}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- 登录信息 ----
        SectionTitle("登录信息")
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(12.dp)) {
                // 登录信息主体
                Text(
                    if (hasCookie) "已扫码登录" else "未登录",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                if (hasCookie) {
                    accountName?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("用户名称：$it", style = MaterialTheme.typography.bodyMedium)
                    }
                    accountVid?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("ID：$it", style = MaterialTheme.typography.bodyMedium, color = GrayDark)
                    }
                }
                // 操作按钮：小号片状，与信息主体拉开距离
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EinkChip(
                        label = if (hasCookie) "重新扫码登录" else "扫码登录",
                        selected = false,
                        onClick = vm::openLogin
                    )
                    if (hasCookie) {
                        EinkChip(label = "退出登录", selected = false, onClick = vm::openLogoutConfirm)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- 同步 ----
        SectionTitle("同步")
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("上次同步：${formatSyncTime(lastSyncAt)}", style = MaterialTheme.typography.bodyMedium)
                        when {
                            lastSyncAt <= 0 -> {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "首次同步需要较长时间，预计 2~10 分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            vm.message != null -> {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    vm.message!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    EinkButton(
                        text = if (vm.syncing) "同步中…" else "立即同步",
                        onClick = vm::sync,
                        enabled = !vm.syncing
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (vm.syncing) {
                    SyncProgressBar(syncProgress.percent, syncProgress.label)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自动同步", style = MaterialTheme.typography.bodyMedium)
                    EinkSwitch(checked = autoSync, onToggle = { vm.setAutoSync(!autoSync) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- 数据 ----
        SectionTitle("数据")
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column {
                TextButton(onClick = vm::openExportConfirm) { Text("导出数据") }
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) { Text("导入数据") }
                TextButton(onClick = vm::openClearMenu) { Text("清除数据") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- 关于 ----
        SectionTitle("关于")
        Card(
            Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("回本了吗 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("开发者：Panty", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                Text("GitHub：github.com/Pantyxts/huibenlema", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                Text("小红书号：2227368465", style = MaterialTheme.typography.bodySmall, color = GrayDark)
                Text("问题反馈邮箱：panty314159@163.com", style = MaterialTheme.typography.bodySmall, color = GrayDark)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ---- 对话框 ----
    if (vm.addCostVisible) {
        CostEditDialog(
            item = vm.editingCost,
            onDismiss = vm::dismissCostDialog,
            onRequestDelete = vm::requestDeleteCost,
            onSave = vm::saveCost,
            customCategories = customCategories,
            onSaveCustomCategory = vm::saveCustomCategory,
            onDeleteCustomCategory = vm::deleteCustomCategory
        )
    }
    if (vm.showExportConfirm) {
        EinkDialog(
            onDismissRequest = vm::dismissExportConfirm,
            title = "导出数据？",
            confirmText = "选择保存位置",
            onConfirm = {
                vm.dismissExportConfirm()
                exportLauncher.launch("回本了吗-备份-${LocalDate.now()}.json")
            }
        ) {
            Text("将导出书籍、定价、成本台账与每日统计（不含凭证）。")
        }
    }
    if (vm.showImportConfirm) {
        EinkDialog(
            onDismissRequest = vm::dismissImportConfirm,
            title = "导入备份数据？",
            confirmText = "确认导入",
            onConfirm = vm::confirmImport
        ) {
            Text("书籍按合并处理；成本台账与每日统计将被备份文件覆盖。")
        }
    }
    // 退出登录确认
    if (vm.showLogoutConfirm) {
        EinkDialog(
            onDismissRequest = vm::dismissLogoutConfirm,
            title = "退出微信读书登录？",
            confirmText = "确认退出",
            onConfirm = vm::confirmLogout
        ) {}
    }
    // 清除数据选项菜单
    if (vm.showClearMenu) {
        Dialog(onDismissRequest = vm::dismissClearMenu) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, InkBlack, RoundedCornerShape(12.dp))
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("清除数据", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    ClearMenuItem("清除全部书值数据") { vm.requestClear(SettingsViewModel.ClearAction.ALL_BOOKS) }
                    ClearMenuItem("清除非手动书值数据") { vm.requestClear(SettingsViewModel.ClearAction.NON_MANUAL) }
                    ClearMenuItem("清除手动书值数据") { vm.requestClear(SettingsViewModel.ClearAction.MANUAL) }
                    ClearMenuItem("清除所有成本栏目") { vm.requestClear(SettingsViewModel.ClearAction.COSTS) }
                    ClearMenuItem("清除应用全部数据") { vm.requestClear(SettingsViewModel.ClearAction.EVERYTHING) }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = vm::dismissClearMenu,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("取消", color = GrayDark)
                    }
                }
            }
        }
    }
    // 清除二次确认
    if (vm.showClearConfirm) {
        EinkDialog(
            onDismissRequest = vm::dismissClearConfirm,
            title = "清除数据？",
            confirmText = "确认清除",
            onConfirm = vm::confirmClear
        ) {
            Text(vm.pendingClearAction?.confirmText ?: "")
        }
    }
    vm.pendingDelete?.let { pending ->
        EinkDialog(
            onDismissRequest = vm::cancelDeleteCost,
            title = "删除成本条目？",
            confirmText = "确认删除",
            onConfirm = vm::confirmDeleteCost
        ) {
            Text("将删除「${pending.name}」，并影响回本进度计算。")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

/** 清除选项菜单项 */
@Composable
private fun ClearMenuItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 墨水屏开关：两种状态下圆的大小完全一致（16dp） */
@Composable
private fun EinkSwitch(checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(44.dp)
            .height(24.dp)
            .border(1.5.dp, InkBlack, shape)
            .background(if (checked) InkBlack else Color.White, shape)
            // indication = null：禁用点击涟漪，墨水屏上不会出现方形闪框
            .clickable(interactionSource = null, indication = null, onClick = onToggle)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) Color.White else InkBlack, RoundedCornerShape(8.dp))
        )
    }
}

/** 同步进度条：百分比 + 阶段说明 + 细进度条 */
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
private fun CostEditDialog(
    item: CostItem?,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
    onSave: (name: String, priceYuan: String, category: CostCategory, customName: String) -> Unit,
    customCategories: Set<String>,
    onSaveCustomCategory: (String) -> Unit,
    onDeleteCustomCategory: (String) -> Unit
) {
    var name by remember(item?.id) { mutableStateOf(item?.name ?: "") }
    var price by remember(item?.id) {
        mutableStateOf(if (item != null && item.priceFen > 0) (item.priceFen / 100.0).toString() else "")
    }
    // 分类选择基于标签（预设 + 用户常用分类）；"自定义"固定最后；旧"配件"数据兼容展示
    val initialLabel = item?.let {
        when {
            it.category == CostCategory.OTHER && it.note.isNotBlank() -> it.note
            it.category == CostCategory.OTHER -> "自定义"
            else -> it.category.displayName
        }
    } ?: "设备"
    var selectedLabel by remember(item?.id) { mutableStateOf(initialLabel) }
    var customName by remember(item?.id) { mutableStateOf("") }

    val legacyAccessory = if (item?.category == CostCategory.ACCESSORY) listOf("配件") else emptyList()
    val allLabels = listOf("设备", "会员费") + legacyAccessory + customCategories.sorted() + "自定义"

    EinkDialog(
        onDismissRequest = onDismiss,
        title = if (item == null) "添加成本条目" else "编辑成本条目",
        confirmText = "保存",
        onConfirm = {
            val (cat, note) = when {
                selectedLabel == "自定义" -> CostCategory.OTHER to customName
                selectedLabel in customCategories -> CostCategory.OTHER to selectedLabel
                else -> labelToCategory(selectedLabel) to ""
            }
            onSave(name, price, cat, note)
        },
        leftButton = if (item != null) {
            { TextButton(onClick = onRequestDelete) { Text("删除", fontWeight = FontWeight.Bold) } }
        } else null
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = price,
            onValueChange = { input -> price = input.filter { it.isDigit() || it == '.' } },
            label = { Text("金额（元）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allLabels.forEach { label ->
                EinkChip(
                    label = label,
                    selected = selectedLabel == label,
                    onClick = { selectedLabel = label }
                )
            }
        }
        when {
            selectedLabel == "自定义" -> {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("自定义分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                EinkButton(
                    text = "保存为常用分类",
                    onClick = {
                        val trimmed = customName.trim()
                        if (trimmed.isNotBlank()) {
                            onSaveCustomCategory(trimmed)
                            selectedLabel = trimmed
                            customName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            selectedLabel in customCategories -> {
                Spacer(Modifier.height(8.dp))
                EinkButton(
                    text = "删除类别",
                    onClick = {
                        onDeleteCustomCategory(selectedLabel)
                        selectedLabel = "自定义"
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun labelToCategory(label: String): CostCategory = when (label) {
    "设备" -> CostCategory.DEVICE
    "配件" -> CostCategory.ACCESSORY // 旧数据兼容
    "会员费" -> CostCategory.MEMBERSHIP
    else -> CostCategory.OTHER // 自定义等归入 OTHER（名称存 note）
}

/** 成本条目分类展示：OTHER 有自定义名称时优先展示自定义名称 */
private fun costItemLabel(item: CostItem): String =
    if (item.category == CostCategory.OTHER && item.note.isNotBlank()) item.note
    else item.category.displayName
