package com.deivid22srk.restuff.mods

import java.io.File
import java.io.IOException

/**
 * Unpacks zip, 7z, rar (including RAR5) and tar archives with libarchive
 * (libmodarchive.so, from the Marathon Recompiled Android port). Entries only
 * land below the destination: absolute paths and `..` are refused.
 */
object ArchiveExtractor {
    fun interface Listener {
        /** Bytes of the archive read so far; return false to cancel. */
        fun onProgress(done: Long, total: Long): Boolean
    }

    private var loaded = false

    @JvmStatic
    private external fun nativeExtract(archive: String, destination: String, listener: Listener?): String?

    @Synchronized
    private fun load() {
        if (loaded) return
        try {
            System.loadLibrary("modarchive")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("The unpacker couldn't be loaded: ${e.message}")
        }
    }

    fun extract(archive: File, destination: File, listener: Listener? = null) {
        load()
        if (!destination.isDirectory && !destination.mkdirs()) throw IOException("Can't create $destination")
        nativeExtract(archive.absolutePath, destination.absolutePath, listener)?.let { throw IOException(it) }
    }
}
