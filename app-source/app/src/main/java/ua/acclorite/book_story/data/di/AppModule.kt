/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.di

import android.app.Application
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import ua.acclorite.book_story.data.local.room.BookDatabase
import ua.acclorite.book_story.data.local.room.DatabaseHelper

/**
 * Work that outlives the screen that started it — a book coming down from the server keeps going
 * when the reader navigates away.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Work that outlives the screen that started it — a book coming down from the server keeps going
     * when the reader navigates away.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideCommonmarkParser(): Parser {
        return Parser
            .builder()
            .enabledBlockTypes(
                setOf(
                    Heading::class.java,
                    HtmlBlock::class.java,
                    ThematicBreak::class.java,
                    BlockQuote::class.java,
                    FencedCodeBlock::class.java,
                    IndentedCodeBlock::class.java,
                    ThematicBreak::class.java
                )
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideBookDatabase(app: Application): BookDatabase {
        return Room.databaseBuilder(
            app,
            BookDatabase::class.java,
            "book_db"
        ).addMigrations(
            DatabaseHelper.MANUAL_MIGRATION_2_3, // creates LanguageHistoryEntity table(if does not exist)
            DatabaseHelper.MANUAL_MIGRATION_4_5, // creates ColorPresetEntity table(if does not exist)
            DatabaseHelper.MANUAL_MIGRATION_5_6, // creates FavoriteDirectoryEntity table(if does not exist)
            DatabaseHelper.MANUAL_MIGRATION_13_14, // remove nullability from ColorPresetEntity
            DatabaseHelper.MANUAL_MIGRATION_14_15, // remove author nullability from BookEntity
            DatabaseHelper.MANUAL_MIGRATION_15_16, // merge CategoryEntity and CategorySortEntity
        ).allowMainThreadQueries().build().also { database ->
            // Additional Migrations
            DatabaseHelper.AUTO_MIGRATION_7_8.removeBooksDir(app)
            database.categoryDao.ensureDefaultCategory()
        }
    }
}