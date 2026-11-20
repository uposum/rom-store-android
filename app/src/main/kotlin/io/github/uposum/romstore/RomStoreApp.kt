package io.github.uposum.romstore

import android.app.Application
import android.content.Intent
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.store.SettingsStore
import io.github.uposum.romstore.core.store.StoreService
import io.github.uposum.romstore.storage.SafLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.io.File

/** Everything that lives as long as the app: settings, the store, downloads. */
class RomStoreApp : Application(), SingletonImageLoader.Factory {
    lateinit var store: StoreService
        private set
    lateinit var library: SafLibrary
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val settings = SettingsStore(File(filesDir, "settings.json"))
        library = SafLibrary(this) { settings.value }
        val http = HttpClient()
        store = StoreService(
            settings = settings,
            dataDir = filesDir,
            cacheDir = cacheDir,
            http = http,
            library = { if (settings.value.libraryUri != null || settings.value.consoleFolders.isNotEmpty()) library else null },
            scope = scope,
        )
        store.onError = { where, e -> android.util.Log.w("RomStore", where, e) }
        store.onTiming = { android.util.Log.i("RomStore", it) }
        store.artwork = io.github.uposum.romstore.core.store.StoreArtwork(http, File(cacheDir, "art"), settings, scope)
        store.start()
        // A fresh start asks every source again: a pause from the last session must not hide a console.
        store.wakeAll()

        // Keep a foreground service up while something downloads, so Android
        // does not stop the app halfway through a big disc image.
        scope.launch {
            store.downloads.state.map { jobs -> jobs.any { it.active } }.distinctUntilChanged().collect { active ->
                if (active) startDownloadService()
            }
        }
        scope.launch {
            settings.state.map { it.maxConcurrent }.distinctUntilChanged().collect { store.downloads.setMaxConcurrent(it) }
        }
    }

    fun startDownloadService() {
        val intent = Intent(this, DownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = store.http.client.newBuilder()
            .addInterceptor(CoverHeaders)
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "covers").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
    }

    companion object {
        lateinit var instance: RomStoreApp
            private set
    }
}
