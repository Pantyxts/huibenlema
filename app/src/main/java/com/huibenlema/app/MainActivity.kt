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
            // 墨水屏专用黑白主题
            EinkTheme {
                AppRoot()
            }
        }
    }
}
