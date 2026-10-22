package com.theveloper.pixelplay.di

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.PixelPlayApplication
import com.theveloper.pixelplay.data.database.*
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.preferences.dataStore
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.network.deezer.DeezerApiService
import com.theveloper.pixelplay.data.network.netease.NeteaseApiService
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeDownloadManager
import com.theveloper.pixelplay.data.network.ytmusic.YouTubeMusicEngine
import com.theveloper.pixelplay.data.repository.*
import dagger.Module
import dagger.Provides
import dagger.Lazy
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): PixelPlayApplication {
        return app as PixelPlayApplication
    }

    @Singleton
    @Provides
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @OptIn(UnstableApi::class)
    @Singleton
    @Provides
    fun provideSessionToken(@ApplicationContext context: Context): androidx.media3.session.SessionToken {
        return androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.theveloper.pixelplay.data.service.MusicService::class.java)
        )
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Singleton
    @Provides
    fun provideJson(): Json {
        return Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Singleton
    @Provides
    @AppScope
    fun provideAppCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Singleton
    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Singleton
    @Provides
    fun providePixelPlayDatabase(@ApplicationContext context: Context): PixelPlayDatabase {
        val appContext = context.applicationContext

        fun buildDatabase(): PixelPlayDatabase = Room.databaseBuilder(
            appContext,
            PixelPlayDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(
            PixelPlayDatabase.MIGRATION_3_4,
            PixelPlayDatabase.MIGRATION_4_5,
            PixelPlayDatabase.MIGRATION_5_6,
            PixelPlayDatabase.MIGRATION_6_7,
            PixelPlayDatabase.MIGRATION_7_8,
            PixelPlayDatabase.MIGRATION_8_9,
            PixelPlayDatabase.MIGRATION_9_10,
            PixelPlayDatabase.MIGRATION_10_11,
            PixelPlayDatabase.MIGRATION_11_12,
            PixelPlayDatabase.MIGRATION_12_13,
            PixelPlayDatabase.MIGRATION_13_14,
            PixelPlayDatabase.MIGRATION_14_15,
            PixelPlayDatabase.MIGRATION_15_16,
            PixelPlayDatabase.MIGRATION_16_17,
            PixelPlayDatabase.MIGRATION_17_18,
            PixelPlayDatabase.MIGRATION_18_19,
            PixelPlayDatabase.MIGRATION_19_20,
            PixelPlayDatabase.MIGRATION_20_21,
            PixelPlayDatabase.MIGRATION_21_22,
            PixelPlayDatabase.MIGRATION_22_23,
            PixelPlayDatabase.MIGRATION_23_24,
            PixelPlayDatabase.MIGRATION_24_25,
            PixelPlayDatabase.MIGRATION_25_26,
            PixelPlayDatabase.MIGRATION_26_27,
            PixelPlayDatabase.MIGRATION_27_28,
            PixelPlayDatabase.MIGRATION_28_29,
            PixelPlayDatabase.MIGRATION_29_30,
            PixelPlayDatabase.MIGRATION_30_31,
            PixelPlayDatabase.MIGRATION_31_32,
            PixelPlayDatabase.MIGRATION_32_33,
            PixelPlayDatabase.MIGRATION_33_34,
            PixelPlayDatabase.MIGRATION_34_35,
            PixelPlayDatabase.MIGRATION_35_36,
            PixelPlayDatabase.MIGRATION_36_37,
            PixelPlayDatabase.MIGRATION_37_38,
            PixelPlayDatabase.MIGRATION_38_39,
            PixelPlayDatabase.MIGRATION_39_40,
            PixelPlayDatabase.MIGRATION_40_41,
            PixelPlayDatabase.MIGRATION_41_42,
            PixelPlayDatabase.MIGRATION_42_43,
            PixelPlayDatabase.MIGRATION_43_44,
            PixelPlayDatabase.MIGRATION_44_45,
            PixelPlayDatabase.MIGRATION_45_46
        )
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // A few early internal builds did not export every migration. If one of
            // those databases is still present, prefer rebuilding the local index
            // over trapping the user in a release-only startup crash loop.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        fun verifyDatabase(database: PixelPlayDatabase) {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.query("PRAGMA quick_check(1)").use { cursor ->
                val result = if (cursor.moveToFirst()) cursor.getString(0) else null
                if (!result.equals("ok", ignoreCase = true)) {
                    throw SQLiteDatabaseCorruptException(
                        "VYBE database integrity check failed: ${result ?: "no result"}"
                    )
                }
            }
        }

        val database = buildDatabase()
        try {
            verifyDatabase(database)
            return database
        } catch (failure: RuntimeException) {
            runCatching { database.close() }
            if (!isRecoverableDatabaseFailure(failure)) throw failure

            Log.e(
                DATABASE_RECOVERY_TAG,
                "Recovering an unreadable or incompatible local database",
                failure
            )
            appContext.deleteDatabase(DATABASE_NAME)

            return buildDatabase().also(::verifyDatabase)
        }
    }

    private fun isRecoverableDatabaseFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { cause ->
            if (cause is SQLiteDatabaseCorruptException) return@any true

            val message = cause.message.orEmpty().lowercase(Locale.ROOT)
            message.contains("migration didn't properly handle") ||
                message.contains("a migration from") ||
                message.contains("room cannot verify the data integrity") ||
                message.contains("invalid schema") ||
                message.contains("identity hash") ||
                message.contains("database disk image is malformed") ||
                message.contains("file is not a database") ||
                message.contains("no such table") ||
                message.contains("no such column")
        }

    private const val DATABASE_NAME = "pixelplay_database"
    private const val DATABASE_RECOVERY_TAG = "VYBEDatabaseRecovery"

    @Singleton
    @Provides
    fun provideDownloadedSongDao(database: PixelPlayDatabase): DownloadedSongDao {
        return database.downloadedSongDao()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: PixelPlayDatabase): AlbumArtThemeDao {
        return database.albumArtThemeDao()
    }

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: PixelPlayDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }

    @Provides
    @Singleton
    fun provideAudiusFavoriteDao(database: PixelPlayDatabase): AudiusFavoriteDao = database.audiusFavoriteDao()

    @Provides
    @Singleton
    fun provideOnlineSongCacheDao(database: PixelPlayDatabase): com.theveloper.pixelplay.data.database.OnlineSongCacheDao =
        database.onlineSongCacheDao()

    @Provides
    @Singleton
    fun provideMusicDao(database: PixelPlayDatabase): MusicDao {
        return database.musicDao()
    }

    @Singleton
    @Provides
    fun provideTransitionDao(database: PixelPlayDatabase): TransitionDao {
        return database.transitionDao()
    }

    @Singleton
    @Provides
    fun provideEngagementDao(database: PixelPlayDatabase): EngagementDao {
        return database.engagementDao()
    }

    @Singleton
    @Provides
    fun provideFavoritesDao(database: PixelPlayDatabase): FavoritesDao {
        return database.favoritesDao()
    }

    @Singleton
    @Provides
    fun provideLyricsDao(database: PixelPlayDatabase): LyricsDao {
        return database.lyricsDao()
    }

    @Singleton
    @Provides
    fun provideGDriveDao(database: PixelPlayDatabase): GDriveDao {
        return database.gdriveDao()
    }

    @Singleton
    @Provides
    fun provideLocalPlaylistDao(database: PixelPlayDatabase): LocalPlaylistDao {
        return database.localPlaylistDao()
    }

    @Singleton
    @Provides
    fun provideQqMusicDao(database: PixelPlayDatabase): QqMusicDao {
        return database.qqmusicDao()
    }

    @Singleton
    @Provides
    fun provideNavidromeDao(database: PixelPlayDatabase): NavidromeDao {
        return database.navidromeDao()
    }
    
    @Singleton
    @Provides
    fun provideAiCacheDao(database: PixelPlayDatabase): AiCacheDao {
        return database.aiCacheDao()
    }

    @Provides
    @Singleton
    fun provideAiUsageDao(database: PixelPlayDatabase): AiUsageDao {
        return database.aiUsageDao()
    }

    @Singleton
    @Provides
    fun provideJellyfinDao(database: PixelPlayDatabase): JellyfinDao {
        return database.jellyfinDao()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val newRequest = if (url.contains("y.qq.com")) {
                    request.newBuilder()
                        .header("Referer", "https://y.qq.com/")
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .dispatcher(Dispatchers.Default)
            .allowHardware(true)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(40 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(
        @ApplicationContext context: Context,
        lrcLibApiService: LrcLibApiService,
        lyricsOvhProvider: com.theveloper.pixelplay.data.network.lyrics.LyricsOvhProvider,
        lyricsDao: LyricsDao,
        okHttpClient: OkHttpClient
    ): LyricsRepository {
        return LyricsRepositoryImpl(
            context = context,
            lrcLibApiService = lrcLibApiService,
            lyricsOvhProvider = lyricsOvhProvider,
            lyricsDao = lyricsDao,
            okHttpClient = okHttpClient
        )
    }

    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        mediaStoreObserver: com.theveloper.pixelplay.data.observer.MediaStoreObserver,
        favoritesDao: FavoritesDao,
        userPreferencesRepository: UserPreferencesRepository,
        musicDao: MusicDao
    ): SongRepository {
        return MediaStoreSongRepository(
            context = context,
            mediaStoreObserver = mediaStoreObserver,
            favoritesDao = favoritesDao,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao
        )
    }

    @Singleton
    @Provides
    fun provideTelegramDao(database: PixelPlayDatabase): TelegramDao {
        return database.telegramDao()
    }

    @Singleton
    @Provides
    fun provideNeteaseDao(database: PixelPlayDatabase): NeteaseDao {
        return database.neteaseDao()
    }

    @Provides
    @Singleton
    fun provideFolderTreeBuilder(): FolderTreeBuilder {
        return FolderTreeBuilder()
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        userPreferencesRepository: UserPreferencesRepository,
        playlistPreferencesRepository: PlaylistPreferencesRepository,
        searchHistoryDao: SearchHistoryDao,
        musicDao: MusicDao,
        lyricsRepository: LyricsRepository,
        telegramDao: TelegramDao,
        telegramCacheManager: Lazy<com.theveloper.pixelplay.data.telegram.TelegramCacheManager>,
        telegramRepository: Lazy<com.theveloper.pixelplay.data.telegram.TelegramRepository>,
        songRepository: SongRepository,
        favoritesDao: FavoritesDao,
        audiusFavoriteDao: AudiusFavoriteDao,
        onlineSongCacheDao: com.theveloper.pixelplay.data.database.OnlineSongCacheDao,
        artistImageRepository: ArtistImageRepository,
        folderTreeBuilder: FolderTreeBuilder
    ): MusicRepository {
        return MusicRepositoryImpl(
            context = context,
            userPreferencesRepository = userPreferencesRepository,
            playlistPreferencesRepository = playlistPreferencesRepository,
            searchHistoryDao = searchHistoryDao,
            musicDao = musicDao,
            lyricsRepository = lyricsRepository,
            telegramDao = telegramDao,
            telegramCacheManagerProvider = telegramCacheManager,
            telegramRepositoryProvider = telegramRepository,
            songRepository = songRepository,
            favoritesDao = favoritesDao,
            audiusFavoriteDao = audiusFavoriteDao,
            onlineSongCacheDao = onlineSongCacheDao,
            artistImageRepository = artistImageRepository,
            folderTreeBuilder = folderTreeBuilder
        )
    }

    @Provides
    @Singleton
    fun provideTransitionRepository(
        transitionRepositoryImpl: TransitionRepositoryImpl
    ): TransitionRepository {
        return transitionRepositoryImpl
    }

    @Singleton
    @Provides
    fun provideSongMetadataEditor(
        @ApplicationContext context: Context,
        musicDao: MusicDao,
        telegramDao: TelegramDao,
        userPreferencesRepository: UserPreferencesRepository
    ): SongMetadataEditor {
        return SongMetadataEditor(context, musicDao, telegramDao, userPreferencesRepository)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            redactHeader("x-goog-api-key")
            redactHeader("X-Emby-Token")
            redactHeader("X-Emby-Authorization")
            redactHeader("X-MediaBrowser-Token")
        }
        
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 8,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                
                val prefs = com.theveloper.pixelplay.data.network.ytmusic.YouTubeAuthPreferences.create(context)
                val isLoggedIn = prefs.getBoolean("is_logged_in", false)
                val authCookie = prefs.getString("auth_cookie", null)
                val urlHost = originalRequest.url.host
                val isPublicYouTubeRequest = originalRequest.header("X-VYBE-Public-YouTube") == "1"
                requestBuilder.removeHeader("X-VYBE-Public-YouTube")
                if (!isPublicYouTubeRequest && isLoggedIn && !authCookie.isNullOrBlank() && (urlHost.contains("youtube.com") || urlHost.contains("youtubei"))) {
                    requestBuilder.header("Cookie", authCookie)
                }

                if (originalRequest.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                }
                
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @FastOkHttpClient
    fun provideFastOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        val dns = okhttp3.Dns { hostname ->
            try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                java.net.InetAddress.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .dns(dns)
            .connectionPool(connectionPool)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionSpecs(listOf(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS
            ))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("User-Agent", "VYBE/1.0 (Android; Music Player)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(requestWithHeaders)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(@FastOkHttpClient okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideLrcLibApiService(retrofit: Retrofit): LrcLibApiService {
        return retrofit.create(LrcLibApiService::class.java)
    }

    @Provides
    @Singleton
    @DeezerRetrofit
    fun provideDeezerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideDeezerApiService(@DeezerRetrofit retrofit: Retrofit): DeezerApiService {
        return retrofit.create(DeezerApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideArtistImageRepository(
        deezerApiService: DeezerApiService,
        musicDao: MusicDao
    ): ArtistImageRepository {
        return ArtistImageRepository(deezerApiService, musicDao)
    }

    @Provides
    @Singleton
    @AudiusRetrofit
    fun provideAudiusRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.audius.co/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAudiusApi(@AudiusRetrofit retrofit: Retrofit): com.theveloper.pixelplay.data.network.audius.AudiusApi {
        return retrofit.create(com.theveloper.pixelplay.data.network.audius.AudiusApi::class.java)
    }
}
