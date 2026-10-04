package com.huibenlema.app.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.huibenlema.app.data.sync.SyncWorker
import com.huibenlema.app.domain.repo.BookRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Worker 依赖入口（避免使用已停更的 androidx.hilt:hilt-compiler） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun bookRepository(): BookRepository
}

/** 自定义 WorkerFactory：为 SyncWorker 注入 BookRepository */
class HuiBenLeMaWorkerFactory(private val entryPoint: WorkerEntryPoint) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        if (workerClassName != SyncWorker::class.java.name) return null
        return SyncWorker(appContext, workerParameters, entryPoint.bookRepository())
    }

    companion object {
        fun create(appContext: Context): HuiBenLeMaWorkerFactory =
            HuiBenLeMaWorkerFactory(
                EntryPointAccessors.fromApplication(appContext, WorkerEntryPoint::class.java)
            )
    }
}
