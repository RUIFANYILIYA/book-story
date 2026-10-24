package dev.ebookd.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * The DI root, and the place two subsystems are wired to the rest of the app.
 *
 * The image loader shares the HTTP stack on purpose: covers are served behind authentication, so a
 * Coil default client would render forty grey placeholders and report no error. Sharing also means
 * the base-URL rewrite and the token refresh apply to images for free.
 *
 * [workManagerConfiguration] is what makes `@HiltWorker` possible. Under WorkManager's default
 * initializer, workers are constructed reflectively and every injected dependency in a download
 * worker would be null at the moment the reader tapped "download" — a crash that only appears on a
 * device, which is exactly the kind worth preventing in the one file that runs before any worker.
 */
@HiltAndroidApp
class EbookdApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            // The server stores a generated SVG for every coverless book (all TXT and PDF), so
            // without this those covers fetch bytes that nothing here can decode.
            add(SvgDecoder.Factory())
        }
        .build()

    // A function, not a property, in WorkManager 2.11 — and built on demand rather than in a field
    // initializer, because Hilt's field injection runs after construction and `workerFactory` would
    // still be uninitialized at that point. A crash here happens before any screen exists, so it is
    // also the kind that looks like "the app won't open" rather than like a bug.
    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder().setWorkerFactory(workerFactory).build()
}
