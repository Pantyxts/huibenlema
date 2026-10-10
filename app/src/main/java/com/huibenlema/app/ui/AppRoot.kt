package com.huibenlema.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.log.AppLog
import com.huibenlema.app.ui.books.BooksScreen
import com.huibenlema.app.ui.components.EinkDialog
import com.huibenlema.app.ui.home.HomeScreen
import com.huibenlema.app.ui.onboarding.OnboardingScreen
import com.huibenlema.app.ui.settings.SettingsScreen
import com.huibenlema.app.ui.theme.GrayDark
import com.huibenlema.app.ui.theme.InkBlack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/** 应用内页面（简单状态导航，墨水屏零动画） */
sealed class Screen {
    data object Home : Screen()
    data object Books : Screen()
    data object Settings : Screen()
}

@HiltViewModel
class RootViewModel @Inject constructor(prefs: UserPrefs) : ViewModel() {

    val onboardingDone = prefs.onboardingDone
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 上次崩溃的栈信息（重启后弹窗显示，用户可查看/拍照回传定位） */
    private val _lastCrash = MutableStateFlow(AppLog.readLastCrash())
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    fun dismissCrash() {
        _lastCrash.value = null
        AppLog.clearLastCrash()
    }
}

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val onboardingDone by vm.onboardingDone.collectAsStateWithLifecycle()

    // 崩溃信息弹窗：上次运行崩溃后重启自动弹出，用户可查看栈内容（排查用）
    val lastCrash by vm.lastCrash.collectAsStateWithLifecycle()
    lastCrash?.let { crash ->
        EinkDialog(
            onDismissRequest = vm::dismissCrash,
            title = "上次运行时发生错误",
            confirmText = "知道了",
            onConfirm = vm::dismissCrash
        ) {
            Text(
                "请记录以下信息并反馈给开发者：\n\n$crash",
                style = MaterialTheme.typography.bodySmall,
                color = GrayDark,
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }

    if (!onboardingDone) {
        OnboardingScreen()
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // 导航栏直接参与布局流（不用 Scaffold bottomBar），从根上避免系统 insets 造成的白条/遮挡
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    onGoBooks = { screen = Screen.Books },
                    onGoSettings = { screen = Screen.Settings }
                )
                Screen.Books -> BooksScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
        BottomTabBar(current = screen, onSelect = { screen = it })
    }
}

/** 底部导航：顶部黑线分隔 + 白色底 + 下划线区分选中项（文字单色） */
@Composable
private fun BottomTabBar(current: Screen, onSelect: (Screen) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(InkBlack)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            TabItem("首页", Screen.Home, current, onSelect)
            TabItem("书值", Screen.Books, current, onSelect)
            TabItem("设置", Screen.Settings, current, onSelect)
        }
    }
}

@Composable
private fun RowScope.TabItem(
    label: String,
    screen: Screen,
    current: Screen,
    onSelect: (Screen) -> Unit
) {
    val selected = current == screen
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        Modifier
            .weight(1f)
            .background(if (pressed) InkBlack else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null) { onSelect(screen) }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (pressed) Color.White else InkBlack,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Normal
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(3.dp)
                .background(if (selected && !pressed) InkBlack else Color.Transparent)
        )
    }
}
