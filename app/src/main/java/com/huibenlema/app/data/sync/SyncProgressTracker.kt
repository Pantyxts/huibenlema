package com.huibenlema.app.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同步进度 */
data class SyncProgress(val percent: Int, val label: String)

/**
 * 同步进度追踪器：SyncManager 写入，UI 订阅展示。
 */
@Singleton
class SyncProgressTracker @Inject constructor() {

    private val _state = MutableStateFlow(SyncProgress(0, ""))
    val state: StateFlow<SyncProgress> = _state.asStateFlow()

    fun update(percent: Int, label: String) {
        _state.value = SyncProgress(percent.coerceIn(0, 100), label)
    }

    /** 仅更新阶段文案（保持当前百分比，限流等待提示用） */
    fun updateLabel(label: String) {
        _state.value = _state.value.copy(label = label)
    }
}
