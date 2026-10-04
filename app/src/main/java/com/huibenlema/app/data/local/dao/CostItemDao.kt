package com.huibenlema.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.huibenlema.app.data.local.entity.CostItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CostItemDao {

    @Query("SELECT * FROM cost_items ORDER BY category, id")
    fun observeAll(): Flow<List<CostItemEntity>>

    /** 全量导出用 */
    @Query("SELECT * FROM cost_items ORDER BY id")
    suspend fun getAllOnce(): List<CostItemEntity>

    @Insert
    suspend fun insert(item: CostItemEntity): Long

    /** 导入备份用 */
    @Insert
    suspend fun insertAll(items: List<CostItemEntity>)

    /** 导入备份前清空成本台账 */
    @Query("DELETE FROM cost_items")
    suspend fun deleteAll()

    @Update
    suspend fun update(item: CostItemEntity)

    @Query("DELETE FROM cost_items WHERE id = :id")
    suspend fun delete(id: Long)
}
