package com.deivid22srk.restuff.reshade

import android.os.Environment
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The ReShade folder the engine reads (cvars reshade_path / reshade_preset):
 * Shaders/ and Textures/ like a PC ReShade install, the preset the launcher
 * writes, and the ReShade.log the engine leaves after each run.
 */
object ReShadeFiles {
    const val PRESET_NAME = "ReShadePreset.ini"
    const val LOG_NAME = "ReShade.log"

    /** Shader packs the ReShade installer offers by default (both permissive licenses). */
    data class Pack(val name: String, val url: String)

    val STANDARD_PACKS = listOf(
        Pack("ReShade standard effects", "https://github.com/crosire/reshade-shaders/archive/refs/heads/slim.zip"),
        Pack("SweetFX", "https://github.com/CeeJayDK/SweetFX/archive/refs/heads/master.zip"),
    )

    /** One technique the preset can turn on: "Name@File.fx" in the preset. */
    data class Technique(val name: String, val file: String) {
        val id: String get() = "$name@$file"
    }

    fun folder(): File = File(Environment.getExternalStorageDirectory(), "Raging Blast 2/reshade")

    fun preset(root: File): File = File(root, PRESET_NAME)

    fun ensure(root: File): Boolean {
        File(root, "Shaders").mkdirs()
        File(root, "Textures").mkdirs()
        return File(root, "Shaders").isDirectory
    }

    private val TECHNIQUE = Regex("""(?m)^\s*technique\s+([A-Za-z_]\w*)""")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    /** Techniques declared in [source] (comments ignored). */
    fun techniquesIn(source: String): List<String> {
        val code = LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, " "), "")
        return TECHNIQUE.findAll(code).map { it.groupValues[1] }.distinct().toList()
    }

    /** Every technique in the .fx files under Shaders/, sorted by name. */
    fun scan(root: File): List<Technique> {
        val shaders = File(root, "Shaders")
        if (!shaders.isDirectory) return emptyList()
        return shaders.walkTopDown()
            .filter { it.isFile && it.extension.equals("fx", ignoreCase = true) }
            .flatMap { fx ->
                val source = runCatching { fx.readText() }.getOrDefault("")
                techniquesIn(source).map { Technique(it, fx.name) }
            }
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    /** The preset's Techniques list, in run order ("Name@File.fx" or "Name"). */
    fun readTechniques(preset: File): List<String> {
        if (!preset.isFile) return emptyList()
        for (line in preset.readLines()) {
            val t = line.trim().removePrefix("﻿")
            if (t.startsWith("[")) break
            val eq = t.indexOf('=')
            if (eq > 0 && t.substring(0, eq).trim() == "Techniques") {
                return t.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return emptyList()
    }

    /**
     * Writes the Techniques (and TechniqueSorting) lines, keeping everything
     * else in the preset: effect values in [File.fx] sections, definitions.
     */
    fun writeTechniques(preset: File, techniques: List<String>) {
        val value = techniques.joinToString(",")
        val lines = if (preset.isFile) preset.readLines().toMutableList() else mutableListOf()
        val wanted = linkedMapOf("Techniques" to value, "TechniqueSorting" to value)
        var firstSection = lines.indexOfFirst { it.trim().startsWith("[") }
        if (firstSection < 0) firstSection = lines.size
        for (i in 0 until firstSection) {
            val eq = lines[i].indexOf('=')
            if (eq <= 0) continue
            val key = lines[i].substring(0, eq).trim().removePrefix("﻿")
            wanted.remove(key)?.let { lines[i] = "$key=$it" }
        }
        val missing = wanted.map { (k, v) -> "$k=$v" }
        lines.addAll(firstSection, missing)
        preset.parentFile?.mkdirs()
        preset.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Copies the Shaders/ and Textures/ folders of a shader pack zip (they may
     * sit under a top folder, as in GitHub archives) into [root]. Returns the
     * number of files written.
     */
    fun installPack(zip: InputStream, root: File): Int {
        val base = root.canonicalFile
        var count = 0
        ZipInputStream(zip.buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                val at = parts.indexOfFirst {
                    it.equals("Shaders", ignoreCase = true) || it.equals("Textures", ignoreCase = true)
                }
                if (at < 0 || at == parts.size - 1) continue
                val top = if (parts[at].equals("Shaders", ignoreCase = true)) "Shaders" else "Textures"
                val out = File(base, (listOf(top) + parts.drop(at + 1)).joinToString("/")).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { input.copyTo(it) }
                count++
            }
        }
        return count
    }

    /** Downloads [pack] and installs it into [root]. */
    fun downloadPack(pack: Pack, root: File): Int {
        var url = URL(pack.url)
        repeat(5) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "RagingBlast2-Android")
            }
            try {
                when (connection.responseCode) {
                    in 200..299 -> return connection.inputStream.use { installPack(it, root) }
                    in 300..399 -> {
                        url = URL(url, connection.getHeaderField("Location") ?: throw IOException("Bad redirect"))
                    }
                    else -> throw IOException("${pack.name}: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("${pack.name}: too many redirects")
    }

    /** The last lines of the engine's ReShade.log, or null before the first run. */
    fun lastLog(root: File, maxLines: Int = 40): String? {
        val log = File(root, LOG_NAME)
        if (!log.isFile) return null
        return runCatching { log.readLines().takeLast(maxLines).joinToString("\n") }.getOrNull()
    }
}
