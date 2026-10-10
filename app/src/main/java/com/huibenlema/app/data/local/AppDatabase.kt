package com.huibenlema.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.huibenlema.app.data.local.dao.BookDao
import com.huibenlema.app.data.local.dao.BookMonthReadDao
import com.huibenlema.app.data.local.dao.CostItemDao
import com.huibenlema.app.data.local.dao.DailyBookStatDao
import com.huibenlema.app.data.local.dao.DailyStatDao
import com.huibenlema.app.data.local.dao.PriceCacheDao
import com.huibenlema.app.data.local.dao.ReadHistoryDao
import com.huibenlema.app.data.local.dao.SyncLogDao
import com.huibenlema.app.data.local.entity.BookEntity
import com.huibenlema.app.data.local.entity.BookMonthReadEntity
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
        DailyBookStatEntity::class,
        BookMonthReadEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookMonthReadDao(): BookMonthReadDao
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

        /**
         * v6 → v7：
         * 1) 存量归一：已读完(finished=1)但进度未到 100% 的书写入 progress=1.0
         *    （修复旧版"已读完但停在 10-98%"的脏数据，与新版"远端读完即 100%"规则对齐）
         * 2) read_history 重建：自增 id 主键 → 复合主键 (bookId, date)
         *    （修复 Room @Upsert 对 id=0 生成 UPDATE WHERE id=0 命中 0 行 → 当天第二次快照静默丢失）
         * DDL 必须与 Room 对 ReadHistoryEntity 生成的建表语句逐字对齐（列顺序/NOT NULL/PK）。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE books SET progress = 1.0 WHERE finished = 1 AND progress < 1.0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS read_history_new (" +
                        "bookId TEXT NOT NULL, date TEXT NOT NULL, " +
                        "progress REAL NOT NULL, readSecondsDelta INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId, date))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO read_history_new (bookId, date, progress, readSecondsDelta) " +
                        "SELECT bookId, date, progress, readSecondsDelta FROM read_history r " +
                        "WHERE r.rowid = (SELECT MAX(r2.rowid) FROM read_history r2 " +
                        "WHERE r2.bookId = r.bookId AND r2.date = r.date)"
                )
                db.execSQL("DROP TABLE read_history")
                db.execSQL("ALTER TABLE read_history_new RENAME TO read_history")
            }
        }

        /**
         * v7 → v8：新增每书每月阅读时长表（readLongest 月榜数据，
         * 分辨"统计起始日之后读了哪些书、各读了多少"，价值按时长比例折算）。升级不清数据。
         * DDL 必须与 Room 对 BookMonthReadEntity 生成的建表语句逐字对齐。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_month_read (" +
                        "bookId TEXT NOT NULL, month TEXT NOT NULL, readSeconds INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId, month))"
                )
            }
        }

        /** v8 → v9：books 表新增 readUpdateTime 列（书架最近阅读更新时间，起始日口径兜底判断用），升级不清数据 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readUpdateTime INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
