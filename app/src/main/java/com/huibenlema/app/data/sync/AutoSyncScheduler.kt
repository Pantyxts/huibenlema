package com.huibenlema.app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.huibenlema.app.data.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动同步调度：每天一次（书籍数据变化慢，每日同步足够），仅在有网络时执行。
 * WorkManager 首次初始化较重，异常兜底（调度失败不阻塞主流程，启动同步仍可工作）。
 */
@Singleton
class AutoSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    fun setEnabled(enabled: Boolean) {
        runCatching {
            val workManager = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MIN, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.cancelUniqueWork(WORK_NAME)
            }
        }.onFailure {
            AppLog.e("HBApp", "autoSync_set_fail enabled=$enabled", it)
        }
    }

    companion object {
        const val WORK_NAME = "auto_sync"
        const val INTERVAL_MIN = 24 * 60L
    }
}
