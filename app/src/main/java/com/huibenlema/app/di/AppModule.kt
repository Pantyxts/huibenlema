package com.huibenlema.app.di

import android.content.Context
import androidx.room.Room
import com.huibenlema.app.BuildConfig
import com.huibenlema.app.data.local.AppDatabase
import com.huibenlema.app.data.local.UserPrefs
import com.huibenlema.app.data.local.dao.BookDao
import com.huibenlema.app.data.local.dao.CostItemDao
import com.huibenlema.app.data.local.dao.DailyBookStatDao
import com.huibenlema.app.data.local.dao.DailyStatDao
import com.huibenlema.app.data.local.dao.PriceCacheDao
import com.huibenlema.app.data.local.dao.ReadHistoryDao
import com.huibenlema.app.data.local.dao.SyncLogDao
import com.huibenlema.app.data.remote.OfficialGatewayApi
import com.huibenlema.app.data.remote.PrivateWereadApi
import com.huibenlema.app.data.remote.ThrottleInterceptor
import com.huibenlema.app.data.repo.BookRepositoryImpl
import com.huibenlema.app.domain.repo.BookRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 节流：相邻请求 ≥400ms，避免触发风控
            .addInterceptor(ThrottleInterceptor(minIntervalMs = 400))
        if (BuildConfig.DEBUG) {
            // 隐私：只用 BASIC（方法+URL），不打印 Header/Body（含凭证）
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideOfficialGatewayApi(client: OkHttpClient, json: Json): OfficialGatewayApi =
        Retrofit.Builder()
            .baseUrl("https://i.weread.qq.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OfficialGatewayApi::class.java)

    @Provides
    @Singleton
    fun providePrivateWereadApi(client: OkHttpClient, json: Json): PrivateWereadApi =
        Retrofit.Builder()
            .baseUrl("https://i.weread.qq.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PrivateWereadApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "huibenlema.db")
            // 正式迁移（不清数据）；未知版本兜底重建
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideCostItemDao(db: AppDatabase): CostItemDao = db.costItemDao()

    @Provides
    fun provideDailyStatDao(db: AppDatabase): DailyStatDao = db.dailyStatDao()

    @Provides
    fun provideDailyBookStatDao(db: AppDatabase): DailyBookStatDao = db.dailyBookStatDao()

    @Provides
    fun providePriceCacheDao(db: AppDatabase): PriceCacheDao = db.priceCacheDao()

    @Provides
    fun provideReadHistoryDao(db: AppDatabase): ReadHistoryDao = db.readHistoryDao()

    @Provides
    fun provideSyncLogDao(db: AppDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    @Singleton
    fun bindBookRepository(impl: BookRepositoryImpl): BookRepository = impl
}
