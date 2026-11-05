package com.dev.Tvivo.download

import android.content.Context
import java.io.File

/** App-private, relative-path-only adapter. Job 8 owns writes; this only accounts and deletes. */
class AndroidDownloadFileSystem(context: Context) : DownloadFileSystem, TransferFileSystem {
    private val root = File(context.applicationContext.filesDir, "downloads").canonicalFile

    private fun resolve(path: String): File? = runCatching {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path)
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator))
        target
    }.getOrNull()

    override fun length(relativePath: String): Long? {
        val target = resolve(relativePath) ?: return null
        val part = resolve("$relativePath.part")
        return (part?.takeIf(File::isFile) ?: target.takeIf(File::isFile))?.length()
    }
    override fun delete(relativePath: String): Boolean {
        val target = resolve(relativePath) ?: return false
        val part = resolve("$relativePath.part")
        val a = target.delete()
        val b = part?.delete() ?: false
        return a || b
    }

    override fun part(relativePath: String): File? = resolve("$relativePath.part")?.also { it.parentFile?.mkdirs() }
    override fun finalFile(relativePath: String?): File? = relativePath?.let(::resolve)?.also { it.parentFile?.mkdirs() }
    override fun length(file: File): Long = file.length()
    override fun delete(file: File): Boolean = file.delete()
    override fun truncate(file: File) { java.io.RandomAccessFile(file, "rw").use { it.setLength(0) } }
    override fun write(file: File, offset: Long, bytes: ByteArray, length: Int) {
        java.io.RandomAccessFile(file, "rw").use { out -> out.seek(offset); out.write(bytes, 0, length) }
    }
    override fun atomicRename(part: File, final: File): Boolean = part.renameTo(final)
}
