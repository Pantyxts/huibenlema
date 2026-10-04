package com.huibenlema.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.huibenlema.app.domain.repo.BookRepository

/**
 * 自动同步 Worker（周期任务）。
 * 失败不返回 retry（避免重试风暴），下个周期自然再试；未授权时静默跳过。
 * 依赖注入经由 [com.huibenlema.app.di.HuiBenLeMaWorkerFactory] 完成。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repo: BookRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repo.sync()
        return Result.success()
    }
}
