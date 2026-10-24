package dev.ebookd.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.ebookd.core.net.EbookdApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** Where a cover change ended up. Three states, because the third one is the one the reader acts on. */
enum class CoverSync {
    /** On this device and on the server. */
    SYNCED,

    /** Drawn here, not uploaded — the cover is real to the reader either way, and saying otherwise would be a lie. */
    LOCAL_ONLY,
    FAILED,
}

data class CoverOutcome(
    val sync: CoverSync,
    val reason: String? = null,
    val sizeBytes: Int = 0,
)

/**
 * The reader's own cover: pick a picture, draw it here immediately, and push it.
 *
 * The order is the design. The server's own cover URL does not change when an override is written
 * (same URL, `private, max-age=86400`), so a client that waited for the server would show the old
 * cover until its cache expired and conclude the feature was broken. Writing the local file first is
 * what makes the tap feel instant and keeps the cover visible on a train; the upload makes it
 * durable, and a failed upload is reported as exactly that rather than rolled back in silence.
 */
@Singleton
class CoverRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: EbookdApi,
    private val local: LocalCovers,
) {

    /** Book ids this device has a cover for; the surfaces draw from this. */
    val overrides = local.overrides

    fun fileFor(bookId: String): File? = local.fileFor(bookId)

    /** Called at startup, and again after any change, so the shelf does not touch the disk during composition. */
    fun refresh() = local.refresh()

    suspend fun setCover(
        bookId: String,
        source: Uri,
    ): CoverOutcome {
        // The two failure paths are told apart because an `IOException` means something different on
        // each: while reading, it is a picture this device cannot open; while uploading, it is a
        // server this device cannot reach. Reporting the first as the second sends the reader to
        // check their wifi while the photo they picked is the problem.
        val bytes = runCatching { withContext(Dispatchers.IO) { render(source) } }
            .getOrElse {
                return CoverOutcome(CoverSync.FAILED, reason = "这张图片用不了：${it.message ?: it.javaClass.simpleName}")
            }

        withContext(Dispatchers.IO) { local.write(bookId, bytes) }

        val uploaded = runCatching {
            withContext(Dispatchers.IO) {
                api.uploadCover(
                    bookId,
                    MultipartBody.Part.createFormData(
                        "cover",
                        "$bookId.jpg",
                        bytes.toRequestBody("image/jpeg".toMediaType()),
                    ),
                )
            }
        }
        return uploaded.fold(
            onSuccess = { CoverOutcome(CoverSync.SYNCED, sizeBytes = bytes.size) },
            onFailure = { CoverOutcome(CoverSync.LOCAL_ONLY, reason = describe(it), sizeBytes = bytes.size) },
        )
    }

    /**
     * Server first, then the local copy.
     *
     * The local file *is* the uploaded bytes, so deleting it while the server still holds an override
     * would just fetch the same picture back on the next launch — the reader would press 恢复默认 and
     * see nothing change. Refusing to clear locally when the server could not be reached leaves the
     * two in the state the reader is actually looking at.
     */
    suspend fun clearCover(bookId: String): CoverOutcome {
        val cleared = runCatching { withContext(Dispatchers.IO) { api.clearCover(bookId) } }
        if (cleared.isFailure) return CoverOutcome(CoverSync.FAILED, reason = describe(cleared.exceptionOrNull()))
        withContext(Dispatchers.IO) { local.clear(bookId) }
        return CoverOutcome(CoverSync.SYNCED)
    }

    /**
     * Decode → centre-crop to 2:3 → downscale → re-encode as JPEG.
     *
     * Two passes, because the first one exists only to learn the dimensions: decoding a phone
     * camera's 12 MP photo at full size in order to throw most of it away is the one way this screen
     * can run out of memory, and `inSampleSize` is the decoder's own discount for it.
     */
    private fun render(source: Uri): ByteArray {
        // The bounds pass returns *no bitmap by design* — that is the point of it — so the stream is
        // not what decides whether the picture is usable here. `outWidth` is. Reading the null the
        // decoder hands back as a failure rejects every image, which is what the first device run did.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("这张图片打不开")

        val sample = CoverCrop.sampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("这张图片打不开")

        val cropped = try {
            val rect = CoverCrop.crop(decoded.width, decoded.height) ?: throw IOException("这张图片太小了")
            Bitmap.createBitmap(decoded, rect.x, rect.y, rect.width, rect.height)
        } finally {
            decoded.recycle()
        }

        val (outWidth, outHeight) = CoverCrop.outputSize(cropped.width, cropped.height)
        val scaled = if (outWidth == cropped.width && outHeight == cropped.height) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true).also { cropped.recycle() }
        }

        val out = ByteArrayOutputStream()
        return try {
            check(scaled.compress(Bitmap.CompressFormat.JPEG, CoverCrop.JPEG_QUALITY, out)) { "图片没能编码" }
            out.toByteArray()
        } finally {
            if (scaled !== cropped) cropped.recycle()
            scaled.recycle()
        }
    }

    /** The difference between "the server said no" and "there was no server", in the reader's words. */
    private fun describe(error: Throwable?): String = when (error) {
        is HttpException -> "服务器拒绝了这张图片（HTTP ${error.code()}）"
        is IOException -> "传不上去：连不到服务器"
        null -> "未知原因"
        else -> error.message ?: "未知原因"
    }
}
