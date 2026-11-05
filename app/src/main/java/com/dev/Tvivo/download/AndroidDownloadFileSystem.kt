package com.dev.Tvivo.download

import android.content.Context
import java.io.File

/** App-private, relative-path-only adapter. Job 8 owns writes; this only accounts and deletes. */
class AndroidDownloadFileSystem(context: Context) : DownloadFileSystem {
    private val root = File(context.applicationContext.filesDir, "downloads").canonicalFile

    private fun resolve(path: String): File? = runCatching {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path)
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator))
        target
    }.getOrNull()

    override fun length(relativePath: String): Long? = resolve(relativePath)?.takeIf(File::isFile)?.length()
    override fun delete(relativePath: String): Boolean = resolve(relativePath)?.delete() ?: false
}
