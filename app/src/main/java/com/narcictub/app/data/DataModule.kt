package com.narcictub.app.data

import android.content.Context
import androidx.room.Room
import com.narcictub.app.data.history.HistoryDao
import com.narcictub.app.data.history.HistoryRepositoryImpl
import com.narcictub.app.data.history.NarcicTubDatabase
import com.narcictub.app.data.downloader.DownloadRepositoryImpl
import com.narcictub.app.data.downloader.HttpUrlConnectionDownloader
import com.narcictub.app.data.downloader.downloadWorkScope
import com.narcictub.app.data.resolver.StubMediaResolver
import kotlinx.coroutines.CoroutineScope
import com.narcictub.app.data.settings.SettingsRepositoryImpl
import com.narcictub.app.data.settings.settingsDataStore
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.resolver.MediaResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NarcicTubDatabase =
        Room.databaseBuilder(context, NarcicTubDatabase::class.java, "narcictub.db")
            .build()

    @Provides
    fun provideHistoryDao(database: NarcicTubDatabase): HistoryDao = database.historyDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context) =
        context.settingsDataStore

    /** App-lifetime worker scope for download orchestration. */
    @Provides
    @Singleton
    fun provideDownloadWorkScope(): CoroutineScope = downloadWorkScope()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {

    @Binds
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindMediaResolver(impl: StubMediaResolver): MediaResolver

    @Binds
    abstract fun bindFileDownloader(impl: HttpUrlConnectionDownloader): FileDownloader

    @Binds
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
