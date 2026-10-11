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
 *  - AFS: it stands for an entry inside one of the game's AFS archives. The
 *    Xbox 360 packs have no entry names, so [PackIndex] turns the mod's name
 *    (`067_brolly_0_2p_i.zpak`) into the path the game asks for
 *    (`battle/character/model/chara_067_2p_model_i.pak`) and finds its slot.
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
class ModManager(
    private val gameRoot: File,
    private val modsDir: File,
    private val savesDir: File? = null,
    private val packIndexFile: File? = null,
) {

    enum class Kind { FILE, AFS, SAVE }

    /** One change a mod makes: [target] is relative to the game folder (the saves folder for SAVE). */
    data class Change(val kind: Kind, val target: String, val entry: String?, val stored: String)

    data class Skipped(val path: String, val reason: String)

    data class InstalledMod(
        val id: String,
        val name: String,
        val changes: List<Change>,
        val skipped: List<Skipped>,
        /** Warnings for the person installing; not kept in the records. */
        val notes: List<String> = emptyList(),
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
            val packs = readPacks(gameIndex.values)
            val afsIndex = indexAfsEntries(packs)
            val packIndex = packIndexFile?.let { PackIndex.read(it) }
            val saves = findSaves()
            val changes = mutableListOf<Change>()
            val skipped = mutableListOf<Skipped>()
            var saveTaken = false
            for (path in extracted) {
                val match = matchFile(path, gameIndex)
                when {
                    isJunk(path) -> skipped += Skipped(path, "not a game file")
                    isSave(path) -> when {
                        saveTaken -> skipped += Skipped(path, "another copy of the save")
                        saves.isEmpty() -> skipped += Skipped(
                            path, "no save to replace yet: play until the game saves once, then install again"
                        )
                        else -> {
                            saves.forEach { changes += Change(Kind.SAVE, it, null, path) }
                            saveTaken = true
                        }
                    }
                    match != null -> changes += Change(Kind.FILE, match, null, path)
                    else -> {
                        val name = path.substringAfterLast('/')
                        val hits = afsIndex[name.lowercase()]
                        val slot = if (hits == null) locateSlot(path, packIndex, packs) else null
                        when {
                            hits != null -> hits.forEach { (archive, entryName) ->
                                changes += Change(Kind.AFS, archive, entryName, path)
                            }
                            slot != null -> changes += Change(Kind.AFS, slot.first, "#${slot.second}", path)
                            name.contains("_ps3", ignoreCase = true) ->
                                skipped += Skipped(path, "PS3 file; this port uses the Xbox 360 data")
                            name.endsWith(".zpak", ignoreCase = true) && packIndex == null ->
                                skipped += Skipped(path, NO_INDEX)
                            else -> skipped += Skipped(path, UNMATCHED)
                        }
                    }
                }
            }
            if (changes.isEmpty() && skipped.any { it.reason == NO_INDEX }) {
                throw ModException(
                    "Start the game once (to the title screen is enough), then install this mod again. " +
                        "The game tells the launcher where its character files live when it starts."
                )
            }
            if (changes.isEmpty()) {
                val report = writePackReport(packs)
                val why = skipped.take(6).joinToString("\n") { "• ${it.path}: ${it.reason}" }
                throw ModException("Nothing in this zip replaces a game file.\n$why\n$report")
            }

            checkSpace(changes, staging)
            val notes = mutableListOf<String>()
            if (skipped.any { it.reason == NO_INDEX }) {
                notes += "Character files were skipped: start the game once, then install this mod again for them."
            }
            if (changes.any { it.kind == Kind.AFS && looksPs3(File(staging, it.stored)) }) {
                notes += "These character files were made for the PS3/PC version. " +
                    "If they look wrong or crash in game, remove the mod to get the originals back."
            }
            writeInstallReport(changes, staging)

            val stored = File(modsDir, id)
            stored.deleteRecursively()
            if (!staging.renameTo(stored)) throw ModException("Couldn't store the mod's files.")

            val mod = InstalledMod(id, displayName, changes, skipped, notes)
            val touched = mutableListOf<String>()
            try {
                for ((target, targetChanges) in changes.groupBy { it.target }) {
                    progress("Installing ${target.substringAfterLast('/')}…")
                    ensureBackup(targetChanges.first().kind, target, targetChanges.any { it.kind == Kind.AFS })
                    touched += target
                    for (change in targetChanges) apply(change, stored)
                }
                writeRecords(records + mod)
            } catch (e: Exception) {
                // Leave the game as it was before this install.
                for (target in touched) {
                    val kind = changes.first { it.target == target }.kind
                    runCatching { rebuild(target, records, kind) }
                }
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
        for (change in mod.changes.distinctBy { it.target }) {
            progress("Restoring ${change.target.substringAfterLast('/')}…")
            rebuild(change.target, remaining, change.kind)
        }
        writeRecords(remaining)
        File(modsDir, id).deleteRecursively()
    }

    /** Puts a target back to its original plus whatever [installed] mods still change in it. */
    private fun rebuild(target: String, installed: List<InstalledMod>, kind: Kind) {
        val file = File(baseFor(kind), target)
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
        val target = File(baseFor(change.kind), change.target)
        when (change.kind) {
            Kind.FILE, Kind.SAVE -> copyAtomically(source, target)
            Kind.AFS -> {
                val archive = AfsArchive.open(target)
                val name = change.entry!!
                val index = if (name.startsWith("#")) {
                    name.drop(1).toIntOrNull()?.takeIf { it in archive.entries.indices }
                } else {
                    archive.find(name)?.index
                } ?: throw ModException("$name is no longer in ${change.target}.")
                archive.replace(index, source)
            }
        }
    }

    /**
     * Keeps the original as `<file>.bk` the first time a mod changes it. A whole
     * file swap can just rename; an AFS patch edits the archive, so it copies.
     */
    private fun ensureBackup(kind: Kind, target: String, patchesInPlace: Boolean) {
        val file = File(baseFor(kind), target)
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
            val file = File(baseFor(group.first().kind), target)
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

    /** Every .afs in the game, parsed (or the reason it couldn't be). */
    private fun readPacks(gameFiles: Collection<String>): Map<String, Result<AfsArchive>> =
        gameFiles.filter { it.endsWith(".afs", ignoreCase = true) }.sorted()
            .associateWith { runCatching { AfsArchive.open(File(gameRoot, it)) } }

    /** Entry name (lower case) → (archive path, entry name) for every AFS in the game. */
    private fun indexAfsEntries(packs: Map<String, Result<AfsArchive>>): Map<String, List<Pair<String, String>>> {
        val index = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for ((rel, result) in packs) {
            // A modded archive still holds the same entry names, so either copy will do.
            val archive = result.getOrNull() ?: continue
            for (entry in archive.entries) {
                if (entry.name.isEmpty()) continue
                index.getOrPut(AfsArchive.baseName(entry.name).lowercase()) { mutableListOf() } +=
                    rel to entry.name
            }
        }
        return index
    }

    /**
     * The (archive, entry index) the game reads [path] from, by way of the
     * game's own path hashes. The archive must have as many entries as the
     * game expects, or the index would point at the wrong file.
     */
    private fun locateSlot(
        path: String,
        packIndex: PackIndex?,
        packs: Map<String, Result<AfsArchive>>,
    ): Pair<String, Int>? {
        packIndex ?: return null
        for (logical in PackIndex.candidates(path)) {
            val slot = packIndex.locate(logical) ?: continue
            val (rel, archive) = packs.entries.firstOrNull { (rel, _) ->
                rel.substringAfterLast('/').equals(slot.pack, ignoreCase = true)
            } ?: continue
            val entries = archive.getOrNull()?.entries ?: continue
            if (entries.size != packIndex.entryCount(slot.pack)) continue
            return rel to slot.index
        }
        return null
    }

    /** True when a pack's own file names say it is PS3 data (`000_goku_0_2p_PS3.spr`). */
    private fun looksPs3(file: File): Boolean = runCatching {
        val head = ByteArray(minOf(512L, file.length()).toInt())
        file.inputStream().use { it.read(head) }
        String(head, Charsets.ISO_8859_1).contains("_PS3.")
    }.getOrDefault(false)

    /**
     * Writes `install-report.txt`: for each pack entry the mod replaces, the
     * first bytes of the game's original and of the mod's file, so a format
     * mismatch can be spotted.
     */
    private fun writeInstallReport(changes: List<Change>, staging: File) {
        runCatching {
            File(modsDir, "install-report.txt").writeText(buildString {
                for (c in changes) {
                    appendLine("${c.kind} ${c.target} ${c.entry ?: ""} <- ${c.stored}")
                    if (c.kind != Kind.AFS) continue
                    val file = File(gameRoot, c.target)
                    val archive = AfsArchive.open(file)
                    val name = c.entry!!
                    val entry = if (name.startsWith("#")) archive.entries.getOrNull(name.drop(1).toInt())
                    else archive.find(name)
                    if (entry != null) {
                        appendLine("  original: ${entry.size} bytes: ${headDump(file, entry.offset, 96)}")
                    }
                    val mod = File(staging, c.stored)
                    appendLine("  mod: ${mod.length()} bytes: ${headDump(mod, 0, 96)}")
                }
            })
        }
    }

    private fun headDump(file: File, offset: Long, n: Int): String = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(minOf(n.toLong(), raf.length() - offset).toInt())
            raf.seek(offset)
            raf.readFully(b)
            b.joinToString(" ") { "%02x".format(it) } + "  |" +
                b.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("") + "|"
        }
    }.getOrDefault("unreadable")

    /** Every DB_SAVE in the saves folder, relative to it (one per profile). */
    private fun findSaves(): List<String> {
        val dir = savesDir ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.equals("DB_SAVE", ignoreCase = true) }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    /**
     * Writes `pack-report.txt` (what each .afs looked like to the installer) and
     * returns a one-line summary per pack, so a failed match can be diagnosed.
     */
    private fun writePackReport(packs: Map<String, Result<AfsArchive>>): String {
        val lines = packs.map { (rel, result) ->
            val name = rel.substringAfterLast('/')
            result.fold(
                onSuccess = { a ->
                    val named = a.entries.count { it.name.isNotEmpty() }
                    "$name: ${a.entries.size} entries, $named named, ${a.order}, align ${a.alignment}" +
                        (a.entries.firstOrNull { it.name.isNotEmpty() }?.let { ", e.g. ${it.name}" } ?: "")
                },
                onFailure = { "$name: ${it.message}" }
            )
        }
        runCatching {
            modsDir.mkdirs()
            File(modsDir, "pack-report.txt").writeText(buildString {
                for ((rel, result) in packs) {
                    appendLine("$rel header: ${AfsArchive.headerHex(File(gameRoot, rel))}")
                    val a = result.getOrNull() ?: continue
                    appendLine("  name table at ${a.nameTableOffset} size ${a.nameTableSize}")
                    appendLine("  first records: ${a.nameTableHex()}")
                    appendLine("  first names: " + a.entries.take(12).joinToString(" | ") { it.name })
                    val goku = a.entries.filter { it.name.contains("goku", ignoreCase = true) }.take(12)
                    if (goku.isNotEmpty()) {
                        appendLine("  goku names: " + goku.joinToString(" | ") { "${it.index}:${it.name}" })
                    }
                }
                lines.forEach { appendLine(it) }
            })
        }
        return if (lines.isEmpty()) "No .afs packs found in the game folder." else
            "Packs: " + lines.joinToString("; ")
    }

    private fun baseFor(kind: Kind): File = if (kind == Kind.SAVE) {
        savesDir ?: throw ModException("No saves folder.")
    } else {
        gameRoot
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
        private const val UNMATCHED = "doesn't match any game file"
        private const val NO_INDEX = "the game hasn't been started yet, so its character file list isn't known"
        private const val MB = 1024L * 1024L
        private const val SPACE_MARGIN = 64L * MB
        private val JUNK_EXTENSIONS = listOf(
            ".txt", ".md", ".url", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".html", ".ini.bak",
        )
    }
}
