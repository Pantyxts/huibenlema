package com.huibenlema.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.huibenlema.app.data.local.dao.BookDao
import com.huibenlema.app.data.local.dao.CostItemDao
import com.huibenlema.app.data.local.dao.DailyBookStatDao
import com.huibenlema.app.data.local.dao.DailyStatDao
import com.huibenlema.app.data.local.dao.PriceCacheDao
import com.huibenlema.app.data.local.dao.ReadHistoryDao
import com.huibenlema.app.data.local.dao.SyncLogDao
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.CostItemEntity
import com.huibenlema.app.data.local.entity.DailyBookStatEntity
import com.huibenlema.app.data.local.entity.DailyStatEntity
import com.huibenlema.app.data.local.entity.PriceCacheEntity
import com.huibenlema.app.data.local.entity.ReadHistoryEntity
import com.huibenlema.app.data.local.entity.SyncLogEntity

@Database(
    entities = [
        BookEntity::class,
        PriceCacheEntity::class,
        ReadHistoryEntity::class,
        DailyStatEntity::class,
        CostItemEntity::class,
        SyncLogEntity::class,
        DailyBookStatEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun priceCacheDao(): PriceCacheDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun costItemDao(): CostItemDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun dailyBookStatDao(): DailyBookStatDao

    companion object {
        /** v3 → v4：新增每日每书价值明细表（升级不清数据） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_book_stats (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "date TEXT NOT NULL, bookId TEXT NOT NULL, valueFen INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_daily_book_stats_date_bookId " +
                        "ON daily_book_stats (date, bookId)"
                )
            }
        }

        /** v4 → v5：books 表新增 hidden 列（用户手动隐藏书籍，升级不清数据） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6：books 表新增 progressManual 列（手动调节进度的「手动」标记，升级不清数据） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN progressManual INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
