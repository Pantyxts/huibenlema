package com.huibenlema.app

import android.app.Application
import androidx.work.Configuration
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.log.AppLog
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

    @Inject
    lateinit var updateManager: com.huibenlema.app.data.update.UpdateManager

    @Inject
    lateinit var repo: com.huibenlema.app.domain.repo.BookRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HuiBenLeMaWorkerFactory.create(this))
            .build()

    override fun onCreate() {
        super.onCreate()
        // 日志系统最先初始化（含全局崩溃落盘处理器）
        AppLog.init(this, BuildConfig.DEBUG)
        AppLog.i("HBApp", "启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        // 恢复自动同步开关（重装 App 后 WorkManager 任务需重新入队）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (prefs.autoSync.first()) {
                autoSyncScheduler.setEnabled(true)
            }
        }
        // 升级后强制重建每日价值：版本号变化说明价值口径可能已更新，
        // 不依赖同步时机（启动自动同步有 1h 节流）立即反映修复后的历史分布
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val last = prefs.lastRebuildVersionCode.first()
            if (last < BuildConfig.VERSION_CODE) {
                AppLog.i("HBApp", "upgrade_rebuild from=$last to=${BuildConfig.VERSION_CODE}")
                runCatching { repo.rebuildDailyValues() }
                    .onFailure { AppLog.e("HBApp", "upgrade_rebuild_fail", it) }
                prefs.setLastRebuildVersionCode(BuildConfig.VERSION_CODE)
            }
        }
        // 启动自动检查更新（24 小时节流，静默；发现新版写入首页横幅标记）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val last = prefs.lastUpdateCheckAt.first()
            if (System.currentTimeMillis() - last >= UPDATE_CHECK_INTERVAL_MS) {
                prefs.setLastUpdateCheckAt(System.currentTimeMillis())
                val info = runCatching { updateManager.checkUpdateSilent() }.getOrNull()
                if (info != null) {
                    prefs.setPendingUpdateVersion(info.versionName)
                }
            }
        }
    }

    companion object {
        /** 启动自动检查更新的节流：24 小时 */
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
