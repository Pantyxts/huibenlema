package com.huibenlema.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huibenlema.app.ui.components.EinkButton
import com.huibenlema.app.ui.login.LoginScreen
import com.huibenlema.app.ui.theme.GrayDark

/** 引导页：设备价格 + 微信扫码登录 + 官方 API Key 授权（首次启动） */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val hasCookie by vm.hasCookie.collectAsStateWithLifecycle()
    var showLogin by remember { mutableStateOf(false) }

    if (showLogin) {
        LoginScreen(onClose = { showLogin = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("回本了吗", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "统计你在微信读书读过的书值多少钱\n看看阅读设备回本了没有",
            style = MaterialTheme.typography.bodyMedium,
            color = GrayDark
        )
        Spacer(Modifier.height(28.dp))

        Text("微信读书登录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Text(
            if (hasCookie) "微信读书登录：已扫码登录 ✓" else "微信读书登录：未登录",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (hasCookie) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(6.dp))
        EinkButton(
            text = if (hasCookie) "重新扫码登录" else "微信扫码登录",
            onClick = { showLogin = true },
            enabled = !vm.working,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        EinkButton(
            text = "开始使用",
            onClick = vm::start,
            enabled = !vm.working,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "将开启每天自动同步（可在「设置」中关闭）",
            style = MaterialTheme.typography.bodySmall,
            color = GrayDark
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = vm::skip,
            enabled = !vm.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("稍后登录，先看看")
        }
        Spacer(Modifier.height(32.dp))
    }
}
