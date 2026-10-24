package dev.ebookd.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.ebookd.core.data.LocalCovers
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.db.BookDao
import dev.ebookd.core.db.DownloadTaskDao
import dev.ebookd.core.db.EbookdDatabase
import dev.ebookd.core.db.ReaderOpenDao
import dev.ebookd.core.db.ReadingPositionDao
import dev.ebookd.core.db.RemoteKeyDao
import dev.ebookd.core.db.SyncOutboxDao
import dev.ebookd.core.db.ShelfEditDao
import dev.ebookd.core.db.SyncStateDao
import dev.ebookd.core.download.RangeDownloader
import dev.ebookd.core.storage.ObjectStore
import dev.ebookd.core.text.TxtBookOpener
import java.io.File
import dev.ebookd.core.net.AuthInterceptor
import dev.ebookd.core.net.BaseUrlInterceptor
import dev.ebookd.core.net.EbookdApi
import dev.ebookd.core.net.ServerProbe
import dev.ebookd.core.net.TokenRefresher
import dev.ebookd.core.security.AndroidKeystoreTokenStore
import dev.ebookd.core.security.TokenStore
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The scope the app's singletons live in; separated from the UI so a refresh outlives a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        // The v1 contract allows additive changes, so an unknown field must never be fatal.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun settingsStore(preferences: DataStore<Preferences>): SettingsStore = SettingsStore(preferences)

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore = AndroidKeystoreTokenStore(context)

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun tokenRefresher(
        tokens: TokenStore,
        api: Provider<EbookdApi>,
        @AppScope scope: CoroutineScope,
    ): TokenRefresher = TokenRefresher(tokens, { api.get() }, scope)

    @Provides
    @Singleton
    fun okHttpClient(
        settings: SettingsStore,
        tokens: TokenStore,
        refresher: TokenRefresher,
    ): OkHttpClient = OkHttpClient.Builder()
        // Order matters: the address is chosen first, then the credential is attached.
        .addInterceptor(BaseUrlInterceptor(settings))
        .addInterceptor(AuthInterceptor(tokens, refresher))
        .build()

    @Provides
    @Singleton
    fun retrofit(json: Json, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(EbookdApi.PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): EbookdApi = retrofit.create(EbookdApi::class.java)

    @Provides
    @Singleton
    fun serverProbe(json: Json): ServerProbe = ServerProbe(json)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EbookdDatabase =
        Room.databaseBuilder(context, EbookdDatabase::class.java, EbookdDatabase.NAME)
            // The catalogue is a cache: refilling it from the server is faster and safer than
            // writing a migration that invents what was lost. `download_task` is *not* a cache, so
            // a schema change here quietly drops the reader's offline list — acceptable while no
            // build has ever shipped, and a real migration is required before the first release.
            // Tracked rather than forgotten, because "drop all tables" is easy to leave in by accident.
            .fallbackToDestructiveMigration(dropAllTables = true)
            // v4 → v5 is that migration, and the first one this app has needed: it adds a table and
            // touches nothing else, so the offline library and every reading position survive it.
            // The statement is Room's own generated DDL for the entity, column order included —
            // Room compares the resulting table metadata and refuses to open the database if a
            // column is missing, added, or retyped.
            .addMigrations(MIGRATION_4_5)
            .build()

    @Provides
    fun bookDao(database: EbookdDatabase): BookDao = database.books()

    @Provides
    fun remoteKeyDao(database: EbookdDatabase): RemoteKeyDao = database.remoteKeys()

    @Provides
    fun downloadTaskDao(database: EbookdDatabase): DownloadTaskDao = database.downloadTasks()

    @Provides
    fun readingPositionDao(database: EbookdDatabase): ReadingPositionDao = database.readingPositions()

    @Provides
    fun readerOpenDao(database: EbookdDatabase): ReaderOpenDao = database.readerOpens()

    @Provides
    fun syncOutboxDao(database: EbookdDatabase): SyncOutboxDao = database.syncOutbox()

    @Provides
    fun syncStateDao(database: EbookdDatabase): SyncStateDao = database.syncState()

    @Provides
    fun shelfEditDao(database: EbookdDatabase): ShelfEditDao = database.shelfEdits()

    @Provides
    @Singleton
    fun objectStore(@ApplicationContext context: Context): ObjectStore =
        // On internal storage, not the app's external files dir: the objects are the reader's
        // offline library, and a USB-mounted directory can be pulled while a download is writing.
        ObjectStore(File(context.filesDir, "objects"))

    @Provides
    @Singleton
    fun txtBookOpener(store: ObjectStore): TxtBookOpener = TxtBookOpener(store)

    @Provides
    @Singleton
    fun localCovers(@ApplicationContext context: Context): LocalCovers =
        // The directory listing *is* the record of which books have a personal cover on this device,
        // which is why there is no Room table for it — a schema bump would be paid for in wiped
        // downloads and lost reading positions.
        LocalCovers(File(context.filesDir, "covers"))

    @Provides
    @Singleton
    fun rangeDownloader(client: OkHttpClient, store: ObjectStore): RangeDownloader =
        RangeDownloader(client, store)

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

/**
 * Room's own DDL for [dev.ebookd.core.db.ShelfEditEntity], column order included.
 *
 * Room compares the metadata of every table against what its annotation processor would have
 * generated, and refuses to open the database if a column is missing, added, or retyped — so a
 * hand-written migration is only ever as correct as its transcription.
 */
private const val SHELF_EDIT_V5 =
    "CREATE TABLE IF NOT EXISTS `shelf_edit` (" +
        "`bookId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
        "`enqueuedAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`bookId`))"

/**
 * The only upgrade path this app has needed so far, and the reason it exists: an upgrade must not
 * cost the reader their offline library or their place in each book.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(SHELF_EDIT_V5)
}
