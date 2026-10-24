package dev.ebookd.core.download

import dev.ebookd.core.storage.ObjectStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a book file in segments, resumably, and refuses to keep the result unless it hashes.
 *
 * Three behaviours here are the difference between a downloader and a way to corrupt a library:
 *
 *  - **Resume sends `If-Range` with the ETag.** The server answers 200 with the whole file when the
 *    validator no longer matches, and that answer means *start over*: appending the body of a 200 to
 *    bytes from the previous version produces a file that opens cleanly and is silently wrong. The
 *    status code is therefore checked, not assumed.
 *  - **The hash is verified before the file is promoted** into the content-addressed tree. A
 *    mismatch is deleted rather than kept "in case it works".
 *  - **Progress is the temp file's length**, reported after each segment. A process killed mid-file
 *    therefore resumes from what is actually on disk rather than from a counter that might have been
 *    ahead of it.
 */
class RangeDownloader(
    private val client: OkHttpClient,
    private val objects: ObjectStore,
    private val segmentBytes: Int = DEFAULT_SEGMENT_BYTES,
) {

    enum class Status { ALREADY_PRESENT, COMPLETED, INTEGRITY_FAILURE, FAILED }

    data class Result(
        val status: Status,
        val file: File?,
        val sha256: String?,
        val sizeBytes: Long,
        val resumedFrom: Long,
        val message: String? = null,
    )

    /**
     * @param urlSupplier builds the request for a byte range (or the whole file when null), which is
     *   how the caller keeps authentication and base-URL handling where they belong.
     */
    suspend fun download(
        fileId: String,
        expectedSha256: String? = null,
        urlSupplier: (rangeStart: Long?, ifRange: String?) -> Request,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        /** Test seam: stop after this many segments so a resumed download can be exercised. */
        stopAfterSegments: Int = Int.MAX_VALUE,
    ): Result {
        /**
         * A caller that already knows the hash is asked locally first, with no network at all. This
         * is the common case — opening a book that finished downloading yesterday — and probing
         * first would make the reader's library depend on reaching the server to read what is on
         * the device.
         */
        if (expectedSha256 != null && objects.contains(expectedSha256)) {
            return Result(
                Status.ALREADY_PRESENT,
                objects.pathFor(expectedSha256),
                expectedSha256,
                objects.pathFor(expectedSha256).length(),
                0,
            )
        }

        val head = probe(urlSupplier)
            ?: return Result(Status.FAILED, null, null, 0, 0, "服务器没有响应文件头信息")

        val sha = expectedSha256 ?: head.sha256
        if (sha != null && objects.contains(sha)) {
            return Result(Status.ALREADY_PRESENT, objects.pathFor(sha), sha, head.total, 0)
        }

        val temp = objects.tempFor(fileId)
        val resumedFrom = temp.length()
        var written = resumedFrom
        onProgress(written, head.total)

        try {
            while (written < head.total) {
                val segmentIndex = ((written - resumedFrom) / segmentBytes).toInt()
                if (segmentIndex >= stopAfterSegments) {
                    return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "下载被中断")
                }

                val request = urlSupplier(written, head.etag)
                val before = written
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "HTTP ${response.code}")
                    }
                    // A 200 where a 206 was asked for is the server saying "your bytes are stale".
                    if (response.code == 200 && written > 0) {
                        temp.delete()
                        written = 0
                    }

                    val body = response.body
                        ?: return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "响应没有内容")
                    RandomAccessFile(temp, "rw").use { file ->
                        file.seek(written)
                        file.setLength(written) // drop anything the previous attempt left beyond the cursor
                        val buffer = ByteArray(64 * 1024)
                        val source = body.byteStream()
                        var read = source.read(buffer)
                        while (read >= 0) {
                            file.write(buffer, 0, read)
                            written += read
                            read = source.read(buffer)
                        }
                    }
                    onProgress(written, head.total)
                }

                // A response that transferred nothing would otherwise spin here forever: the loop
                // condition is still true, the cursor never moves, and the reader sees a download
                // stuck at 97% rather than a failure they can retry.
                if (written <= before) {
                    return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "服务器没有返回新的字节")
                }
            }
        } catch (_: IOException) {
            return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "连接中断，可续传")
        }

        if (temp.length() < head.total) {
            return Result(Status.FAILED, temp, sha, head.total, resumedFrom, "下载不完整")
        }

        val actual = ObjectStore.sha256(temp)
        if (sha != null && actual != sha) {
            temp.delete()
            return Result(Status.INTEGRITY_FAILURE, null, actual, head.total, resumedFrom, "内容校验失败")
        }

        val stored = objects.promote(temp, actual)
        return Result(Status.COMPLETED, stored, actual, head.total, resumedFrom)
    }

    /**
     * HEAD, not a `bytes=0-0` GET. The contract documents every header needed here on HEAD as well,
     * and a ranged GET would download the whole file first if this server turn out not to support
     * ranges — probing a 500 MB book by fetching it is not a probe.
     */
    private fun probe(urlSupplier: (Long?, String?) -> Request): Head? {
        val target = runCatching { urlSupplier(null, null).url }.getOrNull() ?: return null
        val request = Request.Builder().url(target).head().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val total = response.header("Content-Length")?.toLongOrNull() ?: return@use null
                Head(
                    total = total,
                    etag = response.header("ETag"),
                    sha256 = response.header("X-Content-Sha256")?.lowercase()?.takeIf { it.length == 64 },
                )
            }
        }.getOrNull()
    }

    private data class Head(val total: Long, val etag: String?, val sha256: String?)

    companion object {
        /** 4 MiB: large enough that a 500 MB book is ~125 requests, small enough that a resumed
         * download wastes at most one segment when the validator changes. */
        const val DEFAULT_SEGMENT_BYTES = 4 * 1024 * 1024
    }
}
