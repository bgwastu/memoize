package net.wastu.memoize

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import net.wastu.memoize.sync.MemoizeConnectionService
import net.wastu.memoize.ui.AppGraph
import net.wastu.memoize.widget.WidgetRefresh
import okhttp3.OkHttpClient
import java.io.File

class MemoizeApplication : Application(), ImageLoaderFactory {
    val graph: AppGraph by lazy {
        AppGraph(this).also { graph ->
            graph.refreshWidgets = { WidgetRefresh.refresh(this) }
            WidgetRefresh.install(graph)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val connection = graph.connection as? MemoizeConnectionService
                        val token = connection?.authenticatedToken()
                        val server = connection?.currentServer()
                        val host = server?.host
                        val builder = request.newBuilder()
                        if (!token.isNullOrBlank() && host != null && request.url.host.equals(host, ignoreCase = true)) {
                            builder.header("Authorization", "Bearer $token")
                        }
                        chain.proceed(builder.build())
                    }
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "memoize_image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
