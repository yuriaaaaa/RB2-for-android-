package com.deivid22srk.restuff.mods

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Installs zip mods straight into the game folder.
 *
 * A zip file is matched to the game in one of two ways:
 *  - FILE: its path ends with the path of a file in the game folder
 *    (`data/X360/st_pack_sound_pt_x360.afs`, `movie/xyz.wmv`, ...). The game
 *    file is replaced.
 *  - AFS: its name is an entry inside one of the game's AFS archives
 *    (`000_goku_0_2p_i.zpak` lives in st_pack_battle_character_pt_x360.afs).
 *    That entry is replaced inside the archive.
 *
 * Before a game file is changed for the first time, the original is kept next
 * to it as `<name>.bk`. A `.bk` is never overwritten, so it always holds the
 * file as it was before any mod. Uninstalling the last mod that touches a file
 * puts the `.bk` back; uninstalling one of several rebuilds the file from the
 * `.bk` plus the mods still installed, which is why each mod's files are kept
 * in [modsDir].
 *
 * Pure java.io so it runs in JVM unit tests.
 */
class ModManager(private val gameRoot: File, private val modsDir: File) {

    enum class Kind { FILE, AFS }

    /** One change a mod makes: [target] is relative to the game folder. */
    data class Change(val kind: Kind, val target: String, val entry: String?, val stored: String)

    data class Skipped(val path: String, val reason: String)

    data class InstalledMod(
        val id: String,
        val name: String,
        val changes: List<Change>,
        val skipped: List<Skipped>,
    )

    class ModException(message: String) : IOException(message)

    private val recordFile get() = File(modsDir, "installed.txt")

    fun list(): List<InstalledMod> = readRecords()

    /** Every `.bk` the mods have made, relative to the game folder. */
    fun backups(): List<String> = gameRoot.walkTopDown()
        .onEnter { it != modsDir }
        .filter { it.isFile && it.name.endsWith(BACKUP_SUFFIX) }
        .map { it.relativeTo(gameRoot).invariantSeparatorsPath }
        .toList()

    /**
     * Installs the zip in [zip]. [progress] gets short status lines for the UI.
     */
    fun install(zip: InputStream, displayName: String, progress: (String) -> Unit = {}): InstalledMod {
        modsDir.mkdirs()
        val records = readRecords()
        val id = uniqueId(displayName, records)
        val staging = File(modsDir, ".staging-$id")
        staging.deleteRecursively()
        try {
            progress("Unpacking $displayName…")
            val extracted = extractZip(zip, staging)
            if (extracted.isEmpty()) throw ModException("The zip is empty.")

            progress("Matching files to the game…")
            val gameIndex = indexGameFiles()
            val afsIndex = indexAfsEntries(gameIndex.values)
            val changes = mutableListOf<Change>()
            val skipped = mutableListOf<Skipped>()
            for (path in extracted) {
                val match = matchFile(path, gameIndex)
                when {
                    isJunk(path) -> skipped += Skipped(path, "not a game file")
                    isSave(path) -> skipped += Skipped(path, "save file; left out so your own save stays")
                    match != null -> changes += Change(Kind.FILE, match, null, path)
                    else -> {
                        val name = path.substringAfterLast('/')
                        val hits = afsIndex[name.lowercase()]
                        when {
                            hits != null -> hits.forEach { (archive, entryName) ->
                                changes += Change(Kind.AFS, archive, entryName, path)
                            }
                            name.contains("_ps3", ignoreCase = true) ->
                                skipped += Skipped(path, "PS3 file; this port uses the Xbox 360 data")
                            else -> skipped += Skipped(path, "doesn't match any game file")
                        }
                    }
                }
            }
            if (changes.isEmpty()) {
                val why = skipped.take(6).joinToString("\n") { "• ${it.path}: ${it.reason}" }
                throw ModException("Nothing in this zip replaces a game file.\n$why")
            }

            checkSpace(changes, staging)

            val stored = File(modsDir, id)
            stored.deleteRecursively()
            if (!staging.renameTo(stored)) throw ModException("Couldn't store the mod's files.")

            val mod = InstalledMod(id, displayName, changes, skipped)
            val touched = mutableListOf<String>()
            try {
                for ((target, targetChanges) in changes.groupBy { it.target }) {
                    progress("Installing ${target.substringAfterLast('/')}…")
                    ensureBackup(target, targetChanges.any { it.kind == Kind.AFS })
                    touched += target
                    for (change in targetChanges) apply(change, stored)
                }
                writeRecords(records + mod)
            } catch (e: Exception) {
                // Leave the game as it was before this install.
                for (target in touched) runCatching { rebuild(target, records) }
                stored.deleteRecursively()
                throw e
            }
            return mod
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Removes mod [id] and gives every file it touched back its earlier state. */
    fun uninstall(id: String, progress: (String) -> Unit = {}) {
        val records = readRecords()
        val mod = records.firstOrNull { it.id == id } ?: return
        val remaining = records.filter { it.id != id }
        for (target in mod.changes.map { it.target }.distinct()) {
            progress("Restoring ${target.substringAfterLast('/')}…")
            rebuild(target, remaining)
        }
        writeRecords(remaining)
        File(modsDir, id).deleteRecursively()
    }

    /** Puts a target back to its original plus whatever [installed] mods still change in it. */
    private fun rebuild(target: String, installed: List<InstalledMod>) {
        val file = File(gameRoot, target)
        val backup = File(file.path + BACKUP_SUFFIX)
        val still = installed.flatMap { mod -> mod.changes.filter { it.target == target }.map { mod to it } }
        if (!backup.exists()) {
            // The mod added this file; there is no original to go back to.
            if (still.isEmpty()) file.delete()
            return
        }
        if (still.isEmpty()) {
            if (file.exists() && !file.delete()) throw ModException("Couldn't remove the modded $target.")
            if (!backup.renameTo(file)) throw ModException("Couldn't restore $target from its backup.")
            return
        }
        copyAtomically(backup, file)
        for ((mod, change) in still) apply(change, File(modsDir, mod.id))
    }

    private fun apply(change: Change, storedDir: File) {
        val source = File(storedDir, change.stored)
        val target = File(gameRoot, change.target)
        when (change.kind) {
            Kind.FILE -> copyAtomically(source, target)
            Kind.AFS -> {
                val archive = AfsArchive.open(target)
                val entry = archive.find(change.entry!!)
                    ?: throw ModException("${change.entry} is no longer in ${change.target}.")
                archive.replace(entry.index, source)
            }
        }
    }

    /**
     * Keeps the original as `<file>.bk` the first time a mod changes it. A whole
     * file swap can just rename; an AFS patch edits the archive, so it copies.
     */
    private fun ensureBackup(target: String, patchesInPlace: Boolean) {
        val file = File(gameRoot, target)
        val backup = File(file.path + BACKUP_SUFFIX)
        if (backup.exists() || !file.exists()) return
        if (patchesInPlace) {
            val tmp = File(backup.path + ".tmp")
            file.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(backup)) throw ModException("Couldn't create ${backup.name}.")
        } else if (!file.renameTo(backup)) {
            throw ModException("Couldn't create ${backup.name}.")
        }
    }

    private fun checkSpace(changes: List<Change>, staging: File) {
        var needed = 0L
        for ((target, group) in changes.groupBy { it.target }) {
            val file = File(gameRoot, target)
            val hasBackup = File(file.path + BACKUP_SUFFIX).exists()
            if (group.any { it.kind == Kind.AFS }) {
                if (!hasBackup) needed += file.length()
                needed += group.sumOf { File(staging, it.stored).length() }
            } else {
                needed += group.sumOf { File(staging, it.stored).length() }
            }
        }
        val free = gameRoot.usableSpace
        if (free in 1 until needed + SPACE_MARGIN) {
            throw ModException(
                "Not enough free space: this mod needs about ${needed / MB} MB " +
                    "(including the .bk backups) and ${free / MB} MB is free."
            )
        }
    }

    // ------------------------------------------------------------------
    // Matching

    /** Lower-case relative path → real relative path, for every game file. */
    private fun indexGameFiles(): Map<String, String> = gameRoot.walkTopDown()
        .onEnter { it != modsDir && !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.endsWith(BACKUP_SUFFIX) && !it.name.endsWith(".tmp") }
        .associate {
            val rel = it.relativeTo(gameRoot).invariantSeparatorsPath
            rel.lowercase() to rel
        }

    /** Entry name (lower case) → (archive path, entry name) for every AFS in the game. */
    private fun indexAfsEntries(gameFiles: Collection<String>): Map<String, List<Pair<String, String>>> {
        val index = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (rel in gameFiles) {
            if (!rel.endsWith(".afs", ignoreCase = true)) continue
            val file = File(gameRoot, rel)
            // A modded archive still holds the same entry names, so either copy will do.
            val archive = runCatching { AfsArchive.open(file) }.getOrNull() ?: continue
            for (entry in archive.entries) {
                if (entry.name.isEmpty()) continue
                index.getOrPut(entry.name.lowercase()) { mutableListOf() } += rel to entry.name
            }
        }
        return index
    }

    /** The longest tail of [path] that is a game file, as the game spells it. */
    private fun matchFile(path: String, gameIndex: Map<String, String>): String? {
        val parts = path.split('/')
        for (start in parts.indices) {
            val tail = parts.subList(start, parts.size).joinToString("/").lowercase()
            gameIndex[tail]?.let { return it }
        }
        return null
    }

    private fun isJunk(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return lower.startsWith("__macosx/") || name == ".ds_store" || name == "thumbs.db" ||
            JUNK_EXTENSIONS.any { name.endsWith(it) }
    }

    private fun isSave(path: String): Boolean {
        val lower = path.lowercase()
        return lower.substringAfterLast('/') == "db_save" || lower.split('/').contains("savedata")
    }

    // ------------------------------------------------------------------
    // Files

    /** Unzips into [dir] and returns the relative paths of the files. Rejects paths leaving [dir]. */
    private fun extractZip(input: InputStream, dir: File): List<String> {
        dir.mkdirs()
        val root = dir.canonicalFile
        val paths = mutableListOf<String>()
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (entry.isDirectory || name.isEmpty()) continue
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    throw ModException("The zip has an unsafe path: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it) }
                paths += out.relativeTo(root).invariantSeparatorsPath
            }
        }
        return paths
    }

    private fun copyAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        source.copyTo(tmp, overwrite = true)
        if (target.exists() && !target.delete()) throw ModException("Couldn't replace ${target.name}.")
        if (!tmp.renameTo(target)) throw ModException("Couldn't write ${target.name}.")
    }

    private fun uniqueId(name: String, records: List<InstalledMod>): String {
        val base = name.substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "mod" }.take(40)
        var id = base
        var n = 2
        while (records.any { it.id == id } || File(modsDir, id).exists()) id = "$base-${n++}"
        return id
    }

    // ------------------------------------------------------------------
    // Records: one tab-separated line per fact, in install order.

    private fun readRecords(): List<InstalledMod> {
        if (!recordFile.exists()) return emptyList()
        val mods = linkedMapOf<String, Triple<String, MutableList<Change>, MutableList<Skipped>>>()
        for (line in recordFile.readLines()) {
            val f = line.split('\t')
            when (f.firstOrNull()) {
                "mod" -> if (f.size >= 3) mods[f[1]] = Triple(f[2], mutableListOf(), mutableListOf())
                "change" -> if (f.size >= 6) mods[f[1]]?.second?.add(
                    Change(Kind.valueOf(f[2]), f[3], f[4].takeIf { it != "-" }, f[5])
                )
                "skip" -> if (f.size >= 4) mods[f[1]]?.third?.add(Skipped(f[2], f[3]))
            }
        }
        return mods.map { (id, v) -> InstalledMod(id, v.first, v.second, v.third) }
    }

    private fun writeRecords(mods: List<InstalledMod>) {
        modsDir.mkdirs()
        val text = buildString {
            for (mod in mods) {
                appendLine("mod\t${mod.id}\t${clean(mod.name)}")
                for (c in mod.changes) {
                    appendLine("change\t${mod.id}\t${c.kind}\t${c.target}\t${c.entry ?: "-"}\t${c.stored}")
                }
                for (s in mod.skipped) appendLine("skip\t${mod.id}\t${clean(s.path)}\t${clean(s.reason)}")
            }
        }
        val tmp = File(recordFile.path + ".tmp")
        tmp.writeText(text)
        if (recordFile.exists()) recordFile.delete()
        if (!tmp.renameTo(recordFile)) throw ModException("Couldn't save the mod list.")
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    companion object {
        const val BACKUP_SUFFIX = ".bk"
        private const val MB = 1024L * 1024L
        private const val SPACE_MARGIN = 64L * MB
        private val JUNK_EXTENSIONS = listOf(
            ".txt", ".md", ".url", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".html", ".ini.bak",
        )
    }
}
