package com.huibenlema.app

import android.app.Application
import androidx.work.Configuration
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.sync.AutoSyncScheduler
import com.huibenlema.app.di.HuiBenLeMaWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口。Hilt 依赖注入容器宿主 + WorkManager（自动同步）配置宿主。
 */
@HiltAndroidApp
class HuiBenLeMaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var prefs: UserPrefs

    @Inject
    lateinit var autoSyncScheduler: AutoSyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HuiBenLeMaWorkerFactory.create(this))
            .build()

    override fun onCreate() {
        super.onCreate()
        // 恢复自动同步开关（重装 App 后 WorkManager 任务需重新入队）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (prefs.autoSync.first()) {
                autoSyncScheduler.setEnabled(true)
            }
        }
    }
}
