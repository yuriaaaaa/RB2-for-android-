package com.deivid22srk.restuff.mods

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.deivid22srk.restuff.data.GamePaths
import java.io.File

/** Android glue for [ModManager]: where the game and the mod records live. */
object Mods {

    /**
     * The manager for the selected game folder, or null when the game runs
     * straight from an ISO (there are no loose files to replace).
     */
    fun manager(context: Context): ModManager? {
        if (GamePaths.isoSource(context) != null) return null
        val gameRoot = GamePaths.gameRoot(context)
        if (!gameRoot.isDirectory) return null
        // Next to the game folder ("Raging Blast 2/mods"), so it survives reinstalls.
        val modsDir = File(gameRoot.absoluteFile.parentFile ?: context.filesDir, "mods")
        return ModManager(gameRoot, modsDir)
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "mod.zip"
    }
}
