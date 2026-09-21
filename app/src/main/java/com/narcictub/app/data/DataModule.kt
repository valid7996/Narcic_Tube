package com.narcictub.app.data

import android.content.Context
import androidx.room.Room
import com.narcictub.app.data.history.HistoryDao
import com.narcictub.app.data.history.HistoryRepositoryImpl
import com.narcictub.app.data.history.NarcicTubDatabase
import com.narcictub.app.data.downloader.DownloadRepositoryImpl
import com.narcictub.app.data.downloader.HttpUrlConnectionDownloader
import com.narcictub.app.data.downloader.downloadWorkScope
import com.narcictub.app.data.local.MediaUriSafety
import com.narcictub.app.data.player.MediaPlayerEngine
import com.narcictub.app.data.resolver.DirectMediaExtractor
import com.narcictub.app.data.resolver.DirectMediaResolver
import com.narcictub.app.data.resolver.ExtractorRegistryMediaResolver
import com.narcictub.app.data.resolver.InstagramExtractor
import kotlinx.coroutines.CoroutineScope
import com.narcictub.app.data.settings.SettingsRepositoryImpl
import com.narcictub.app.data.settings.settingsDataStore
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.player.PlaybackEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
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
            .addMigrations(*NarcicTubDatabase.allMigrations())
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

    /**
     * PHASE 10: the app-owned storage roots the media-open URI policy
     * accepts file:// URIs from (the pre-Q legacy publisher's tree).
     */
    @Provides
    @Singleton
    fun provideMediaUriSafety(@ApplicationContext context: Context): MediaUriSafety =
        MediaUriSafety(
            listOfNotNull(
                context.getExternalFilesDir(null),
                context.filesDir,
                context.cacheDir,
            ).map { dir -> runCatching { dir.canonicalFile }.getOrDefault(dir) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {

    @Binds
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** PHASE 17: the resolver routes URLs through the extractor registry. */
    @Binds
    abstract fun bindMediaResolver(impl: ExtractorRegistryMediaResolver): MediaResolver

    /** PHASE 17: registered extractors — future providers add one entry each. */
    @Binds
    @IntoSet
    abstract fun bindDirectMediaExtractor(impl: DirectMediaExtractor): MediaExtractor

    /** PHASE 19: Instagram intake — honest typed routing, zero network. */
    @Binds
    @IntoSet
    abstract fun bindInstagramExtractor(impl: InstagramExtractor): MediaExtractor

    @Binds
    abstract fun bindFileDownloader(impl: HttpUrlConnectionDownloader): FileDownloader

    @Binds
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    /** PHASE 11: local playback engine (framework MediaPlayer, no network). */
    @Binds
    abstract fun bindPlaybackEngine(impl: MediaPlayerEngine): PlaybackEngine
}
