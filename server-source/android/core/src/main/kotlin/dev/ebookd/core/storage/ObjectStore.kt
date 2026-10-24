package dev.ebookd.core.storage

import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed object storage: `objects/<sha[0..2]>/<sha>.bin`.
 *
 * Two properties the app depends on fall out of the naming. A book held in three formats on the
 * server, or downloaded twice by accident, occupies the disk once. And when the server replaces a
 * book's bytes it mints a new file id and a new hash, so an old local copy cannot be mistaken for
 * the new one — a stale cache keyed by path would be exactly that mistake.
 *
 * The shard prefix exists because some filesystems get slow at directory entries in the tens of
 * thousands; two hex digits keeps any one directory small without adding a third level.
 */
class ObjectStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun pathFor(sha256: String): File = File(root, "${sha256.take(2)}/${sha256}.bin")

    fun contains(sha256: String): Boolean = pathFor(sha256).let { it.isFile && it.length() > 0 }

    /**
     * A scratch file for a download in progress.
     *
     * Its **length is the resume cursor**. That is deliberately not duplicated into a database
     * column: a recorded byte count and the file can disagree after a crash, and then the client
     * would either re-download bytes it has or, worse, skip bytes it does not.
     */
    fun tempFor(key: String): File = File(root, "tmp/$key.part").apply { parentFile?.mkdirs() }

    /** Moves a completed temp file into the addressable tree, overwriting a verified-identical file. */
    fun promote(temp: File, sha256: String): File {
        val target = pathFor(sha256)
        target.parentFile?.mkdirs()
        if (target.isFile) temp.delete() else temp.renameToOrMove(target)
        return target
    }

    fun delete(sha256: String): Boolean = pathFor(sha256).delete()

    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHexString()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

        private fun ByteArray.toHexString(): String = buildString(size * 2) {
            for (byte in this@toHexString) {
                append("0123456789abcdef"[(byte.toInt() shr 4) and 0x0F])
                append("0123456789abcdef"[byte.toInt() and 0x0F])
            }
        }
    }
}

private fun File.renameToOrMove(target: File) {
    if (!renameTo(target)) {
        // Across filesystems `renameTo` fails; copying is the fallback, and the temp file is on the
        // same partition in every supported layout, so this path is rare rather than cosmetic.
        copyTo(target, overwrite = true)
        delete()
    }
}
