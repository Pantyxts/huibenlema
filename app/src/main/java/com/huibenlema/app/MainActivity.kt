package com.huibenlema.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.huibenlema.app.ui.AppRoot
import com.huibenlema.app.ui.theme.EinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 阶段 3：universal 彩色主题在此按 flavor 分支
            EinkTheme {
                AppRoot()
            }
        }
    }
}
