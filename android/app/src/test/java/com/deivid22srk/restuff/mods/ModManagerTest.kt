package com.deivid22srk.restuff.mods

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModManagerTest {

    private lateinit var root: File
    private lateinit var game: File
    private lateinit var mods: File
    private lateinit var afs: File
    private lateinit var movie: File
    private lateinit var originalAfs: ByteArray
    private lateinit var originalMovie: ByteArray

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rb2-mods").toFile()
        game = File(root, "game")
        mods = File(root, "mods")
        afs = File(game, "data/X360/st_pack_battle_character_pt_x360.afs")
        afs.parentFile!!.mkdirs()
        afs.writeBytes(
            buildAfs(
                listOf(
                    "000_goku_0_2p_i.zpak" to bytes(3000, 1),
                    "000_goku_0_2p_s.zpak" to bytes(500, 2),
                    "000_goku_0_2p_v.zpak" to bytes(1000, 3),
                )
            )
        )
        originalAfs = afs.readBytes()
        movie = File(game, "data/X360/movie/op.sfd")
        movie.parentFile!!.mkdirs()
        movie.writeBytes(bytes(100, 9))
        originalMovie = movie.readBytes()
        File(game, "default.xex").writeBytes(bytes(10, 7))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun readsTheArchive() {
        val archive = AfsArchive.open(afs)
        assertEquals(ByteOrder.LITTLE_ENDIAN, archive.order)
        assertEquals(listOf("000_goku_0_2p_i.zpak", "000_goku_0_2p_s.zpak", "000_goku_0_2p_v.zpak"),
            archive.entries.map { it.name })
        assertEquals(2048, archive.alignment)
    }

    @Test
    fun keepsEveryEntryWhereTheGameAddsItUp() {
        val paths = (0 until 6).map { "battle/character/model/chara_067_2p_model_$it.pak" }
        val names = listOf("big.pak", "small.pak", "same.pak", "x.pak", "y.pak", "z.pak")
        afs.writeBytes(buildAfs(names.mapIndexed { i, n -> n to bytes(3000 + i * 100, 50 + i) }))
        val original = afs.readBytes()
        AfsArchive.open(afs).replaceAll(
            mapOf(
                0 to File(root, "a").apply { writeBytes(bytes(9000, 1)) },
                1 to File(root, "b").apply { writeBytes(bytes(100, 2)) },
                4 to File(root, "c").apply { writeBytes(bytes(7000, 3)) },
            )
        )
        val archive = AfsArchive.open(afs)
        assertGameLayout(archive)
        assertEquals(names, archive.entries.map { it.name })
        assertEntry(bytes(9000, 1), archive, 0)
        assertEntry(bytes(100, 2), archive, 1)
        assertEquals(3100L, archive.entries[1].size)
        assertEntry(bytes(3200, 52), archive, 2)
        assertEntry(bytes(3300, 53), archive, 3)
        assertEntry(bytes(7000, 3), archive, 4)
        assertEntry(bytes(3500, 55), archive, 5)
        assertTrue(paths.isNotEmpty() && afs.length() > original.size)
    }

    @Test
    fun installsAfsEntriesAndFilesWithBackups() {
        val manager = ModManager(game, mods)
        val grown = bytes(5000, 11)   // larger than its 4096-byte slot: appended
        val shrunk = bytes(200, 12)   // fits its slot: written in place
        val newMovie = bytes(150, 13)
        val mod = manager.install(
            zip(
                "Modloader/Characters/Goku/000_goku_0_2p_i.zpak" to grown,
                "Modloader/Characters/Goku/000_GOKU_0_2P_S.zpak" to shrunk,
                "Some Mod/data/X360/movie/OP.sfd" to newMovie,
                "readme.txt" to bytes(5, 1),
                "SaveData/Saves/Player/DB_SAVE" to bytes(5, 1),
                "IORAM_PS3.stpk" to bytes(5, 1),
            ),
            "Goku Whis.zip"
        )

        assertEquals(3, mod.changes.size)
        assertEquals(3, mod.skipped.size)
        assertArrayEquals(originalAfs, File(afs.path + ".bk").readBytes())
        assertArrayEquals(originalMovie, File(movie.path + ".bk").readBytes())
        assertArrayEquals(newMovie, movie.readBytes())

        val archive = AfsArchive.open(afs)
        assertEntry(grown, archive, "000_goku_0_2p_i.zpak")
        assertEntry(shrunk, archive, "000_goku_0_2p_s.zpak")
        assertEntry(bytes(1000, 3), archive, "000_goku_0_2p_v.zpak")
        assertEquals(0L, archive.find("000_goku_0_2p_i.zpak")!!.offset % 2048)
        assertEquals(listOf(mod.id), manager.list().map { it.id })

        manager.uninstall(mod.id)
        assertArrayEquals(originalAfs, afs.readBytes())
        assertArrayEquals(originalMovie, movie.readBytes())
        assertFalse(File(afs.path + ".bk").exists())
        assertFalse(File(movie.path + ".bk").exists())
        assertTrue(manager.list().isEmpty())
        assertFalse(File(mods, mod.id).exists())
    }

    @Test
    fun uninstallingOneModKeepsTheOther() {
        val manager = ModManager(game, mods)
        val first = manager.install(zip("a/000_goku_0_2p_i.zpak" to bytes(100, 21)), "First.zip")
        val second = manager.install(zip("b/000_goku_0_2p_v.zpak" to bytes(9000, 22)), "Second.zip")

        // The backup is the untouched original, not the state after the first mod.
        assertArrayEquals(originalAfs, File(afs.path + ".bk").readBytes())

        manager.uninstall(first.id)
        val archive = AfsArchive.open(afs)
        assertEntry(bytes(3000, 1), archive, "000_goku_0_2p_i.zpak")
        assertEntry(bytes(9000, 22), archive, "000_goku_0_2p_v.zpak")
        assertTrue(File(afs.path + ".bk").exists())

        manager.uninstall(second.id)
        assertArrayEquals(originalAfs, afs.readBytes())
        assertFalse(File(afs.path + ".bk").exists())
    }

    @Test
    fun zipWithNothingForTheGameIsRefused() {
        val manager = ModManager(game, mods)
        try {
            manager.install(zip("Goku/SPR.stpk" to bytes(10, 1)), "PS3 mod.zip")
            fail("expected ModException")
        } catch (e: ModManager.ModException) {
            assertTrue(e.message!!.contains("Nothing in this zip"))
        }
        assertArrayEquals(originalAfs, afs.readBytes())
        assertFalse(File(afs.path + ".bk").exists())
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun unsafeZipPathIsRefused() {
        val manager = ModManager(game, mods)
        try {
            manager.install(zip("../../evil.zpak" to bytes(10, 1)), "evil.zip")
            fail("expected ModException")
        } catch (e: ModManager.ModException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
        assertFalse(File(root, "evil.zpak").exists())
    }

    @Test
    fun readsAndPatchesBigEndianArchives() {
        // Xbox 360 data: a big-endian count must not be taken for a little-endian one.
        val entries = (0 until 300).map { "%03d_chara_0_2p_i.zpak".format(it) to bytes(100 + it, it) }
        afs.writeBytes(buildAfs(entries, ByteOrder.BIG_ENDIAN))
        val archive = AfsArchive.open(afs)
        assertEquals(ByteOrder.BIG_ENDIAN, archive.order)
        assertEquals(300, archive.entries.size)
        assertEquals("042_chara_0_2p_i.zpak", archive.entries[42].name)

        val manager = ModManager(game, mods)
        manager.install(zip("Modloader/Characters/X/042_chara_0_2p_i.zpak" to bytes(5000, 99)), "BE.zip")
        assertEntry(bytes(5000, 99), AfsArchive.open(afs), "042_chara_0_2p_i.zpak")
        assertEntry(bytes(143, 43), AfsArchive.open(afs), "043_chara_0_2p_i.zpak")
    }

    @Test
    fun replacesTheSaveAndRestoresIt() {
        val saves = File(root, "saves")
        val save = File(saves, "E0000ABCD/4E4D0826/00000001/RB2SAVE/DB_SAVE")
        save.parentFile!!.mkdirs()
        save.writeBytes(bytes(55000, 5))
        val manager = ModManager(game, mods, saves)
        val mod = manager.install(
            zip(
                "SaveData/Saves/Player/DB_SAVE" to bytes(55000, 6),
                "assets/DB_SAVE" to bytes(55000, 6),
                "Modloader/Characters/Goku/000_goku_0_2p_v.zpak" to bytes(800, 7),
            ),
            "Modpack.zip"
        )
        assertArrayEquals(bytes(55000, 6), save.readBytes())
        assertArrayEquals(bytes(55000, 5), File(save.path + ".bk").readBytes())
        assertEquals(1, mod.changes.count { it.kind == ModManager.Kind.SAVE })

        manager.uninstall(mod.id)
        assertArrayEquals(bytes(55000, 5), save.readBytes())
        assertFalse(File(save.path + ".bk").exists())
        assertArrayEquals(originalAfs, afs.readBytes())
    }

    @Test
    fun characterFilesNotInThePacksAreSkipped() {
        val saves = File(root, "saves")
        val save = File(saves, "E0000ABCD/4E4D0826/00000001/RB2SAVE/DB_SAVE")
        save.parentFile!!.mkdirs()
        save.writeBytes(bytes(55000, 5))
        val manager = ModManager(game, mods, saves)
        val mod = manager.install(
            zip(
                "Modloader/Characters/Moro/067_brolly_0_2p_i.zpak" to bytes(10, 1),
                "SaveData/Saves/Player/DB_SAVE" to bytes(55000, 6),
            ),
            "Partial.zip"
        )
        // The save still goes in even though the character file has nowhere to go.
        assertArrayEquals(bytes(55000, 6), save.readBytes())
        assertEquals(listOf("Modloader/Characters/Moro/067_brolly_0_2p_i.zpak"), mod.skipped.map { it.path })
        assertArrayEquals(originalAfs, afs.readBytes())
    }

    @Test
    fun hashMatchesTheGame() {
        // Value from the game's own table (st_pack_battle_character, entry 4403).
        assertEquals(0x7ed16ae8, PackIndex.hash("battle/character/model/chara_000_2p_model_i.pak"))
        assertEquals(
            "battle/character/model/chara_067_2p_model_m.pak",
            PackIndex.candidates("Modloader/Characters/Moro/067_brolly_0_2p_s.zpak").first()
        )
        assertEquals(
            "battle/character/model/chara_094_D_3p_model_v.pak",
            PackIndex.candidates("x/094_android_14_D_3p_v.zpak").first()
        )
    }

    @Test
    fun installsIntoUnnamedPacksThroughTheGamesPathHashes() {
        // The Xbox 360 packs: no names, entries found by the game's path hashes.
        val paths = listOf(
            "battle/character/model/chara_000_2p_model_i.pak",
            "battle/character/model/chara_000_2p_model_m.pak",
            "battle/character/model/chara_000_2p_model_v.pak",
            "battle/character/model/chara_067_2p_model_i.pak",
            "battle/character/model/chara_067_2p_model_m.pak",
            "battle/character/model/chara_067_2p_model_v.pak",
        )
        afs.writeBytes(buildAfs(paths.mapIndexed { i, _ -> "" to bytes(700 + i, 40 + i) }))
        val original = afs.readBytes()
        val index = File(root, PackIndex.FILE_NAME)
        PackIndex.write(index, mapOf(
            "st_pack_boot_pt_x360.afs" to listOf("boot/x.pak"),
            "st_pack_battle_character_pt_x360.afs" to paths,
        ))

        val manager = ModManager(game, mods, null, index)
        val mod = manager.install(
            zip(
                "Modloader/Characters/Moro/067_brolly_0_2p_i.zpak" to bytes(3000, 1),
                "Modloader/Characters/Moro/067_brolly_0_2p_s.zpak" to bytes(300, 2),
                "Modloader/Characters/Moro/067_brolly_0_2p_v.zpak" to bytes(5000, 3),
            ),
            "Moro.zip"
        )
        assertEquals(listOf("#3", "#4", "#5"), mod.changes.map { it.entry })
        val archive = AfsArchive.open(afs)
        assertGameLayout(archive)
        assertEntry(bytes(3000, 1), archive, 3)
        assertEntry(bytes(300, 2), archive, 4)
        assertEntry(bytes(5000, 3), archive, 5)
        assertEntry(bytes(700, 40), archive, 0)
        assertTrue(File(mods, "install-report.txt").readText().contains("original: 703 bytes"))

        manager.uninstall(mod.id)
        assertArrayEquals(original, afs.readBytes())
    }

    @Test
    fun ps3CharacterFilesAreHeldBackAndTheOriginalsExported() {
        val paths = listOf(
            "battle/character/model/chara_067_2p_model_i.pak",
            "battle/character/model/chara_067_2p_model_m.pak",
        )
        afs.writeBytes(buildAfs(paths.mapIndexed { i, _ -> "" to bytes(700 + i, 40 + i) }))
        val original = afs.readBytes()
        val index = File(root, PackIndex.FILE_NAME)
        PackIndex.write(index, mapOf("st_pack_battle_character_pt_x360.afs" to paths))
        val saves = File(root, "saves")
        val save = File(saves, "P/DB_SAVE")
        save.parentFile!!.mkdirs()
        save.writeBytes(bytes(55000, 5))
        val ps3 = "STPZ....067_brolly_0_2p_PS3.ioram".toByteArray() + bytes(100, 1)

        val manager = ModManager(game, mods, saves, index)
        val mod = manager.install(
            zip(
                "Moro/067_brolly_0_2p_i.zpak" to ps3,
                "SaveData/Saves/Player/DB_SAVE" to bytes(55000, 6),
            ),
            "Moro.zip"
        )
        assertArrayEquals(original, afs.readBytes())
        assertArrayEquals(bytes(55000, 6), save.readBytes())
        assertEquals(1, mod.notes.size)
        assertArrayEquals(bytes(700, 40), File(mods, "x360-originals/st_pack_battle_character-0.zpak").readBytes())
    }

    @Test
    fun aLoneBackupIsPutBackAndReinstallReplaces() {
        val manager = ModManager(game, mods)
        manager.install(zip("a/000_goku_0_2p_i.zpak" to bytes(100, 21)), "Same.zip")
        // Simulate a restore that was cut short: only the .bk is left.
        val bk = File(afs.path + ".bk")
        assertTrue(afs.delete())
        File(mods, "installed.txt").delete()
        assertEquals(listOf("data/X360/st_pack_battle_character_pt_x360.afs"), manager.repair())
        assertArrayEquals(originalAfs, afs.readBytes())
        assertFalse(bk.exists())

        manager.install(zip("a/000_goku_0_2p_i.zpak" to bytes(100, 21)), "Again.zip")
        manager.install(zip("a/000_goku_0_2p_v.zpak" to bytes(100, 22)), "Again.zip")
        assertEquals(1, manager.list().size)
        val archive = AfsArchive.open(afs)
        assertEntry(bytes(3000, 1), archive, "000_goku_0_2p_i.zpak")
        assertEntry(bytes(100, 22), archive, "000_goku_0_2p_v.zpak")
    }

    @Test
    fun characterModsWaitForTheGameToStartOnce() {
        val manager = ModManager(game, mods, null, File(root, PackIndex.FILE_NAME))
        try {
            manager.install(zip("Moro/067_brolly_0_2p_i.zpak" to bytes(10, 1)), "Moro.zip")
            fail("expected ModException")
        } catch (e: ModManager.ModException) {
            assertTrue(e.message!!.contains("Start the game once"))
        }
        assertArrayEquals(originalAfs, afs.readBytes())
    }

    // ------------------------------------------------------------------

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed * 7) and 0xFF).toByte() }

    /** An entry holds [expected], then only zero padding (smaller data keeps the original size). */
    private fun assertEntry(expected: ByteArray, archive: AfsArchive, index: Int) {
        val actual = entryBytes(archive, index)
        assertTrue("entry $index is shorter than the data", actual.size >= expected.size)
        assertArrayEquals(expected, actual.copyOf(expected.size))
        assertTrue("entry $index has data after the padding", actual.drop(expected.size).all { it == 0.toByte() })
    }

    private fun assertEntry(expected: ByteArray, archive: AfsArchive, name: String) =
        assertEntry(expected, archive, archive.find(name)!!.index)

    /** Every entry sits where the game looks for it: right after the one before, rounded up to 2048. */
    private fun assertGameLayout(archive: AfsArchive) {
        val used = archive.entries.filter { it.offset > 0 }
        for ((a, b) in used.zipWithNext()) {
            assertEquals("entry ${b.index}", a.offset + (a.size + 2047) / 2048 * 2048, b.offset)
        }
    }

    private fun entryBytes(archive: AfsArchive, name: String): ByteArray = entryBytes(archive, archive.find(name)!!.index)

    private fun entryBytes(archive: AfsArchive, index: Int): ByteArray {
        val e = archive.entries[index]
        RandomAccessFile(afs, "r").use { raf ->
            val out = ByteArray(e.size.toInt())
            raf.seek(e.offset)
            raf.readFully(out)
            return out
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /** A little-endian AFS with 2048-byte alignment and the name table after the data. */
    private fun buildAfs(
        entries: List<Pair<String, ByteArray>>,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteArray {
        fun align(v: Int) = (v + 2047) / 2048 * 2048
        val count = entries.size
        var offset = align(8 + count * 8 + 8)
        val offsets = entries.map { (_, data) -> offset.also { offset = align(offset + data.size) } }
        val nameTable = offset
        val total = align(nameTable + count * 48)
        val buf = ByteBuffer.allocate(total).order(order)
        buf.put("AFS".toByteArray()).put(0).putInt(count)
        entries.forEachIndexed { i, (_, data) -> buf.putInt(offsets[i]).putInt(data.size) }
        buf.putInt(nameTable).putInt(count * 48)
        entries.forEachIndexed { i, (_, data) ->
            buf.position(offsets[i]); buf.put(data)
        }
        entries.forEachIndexed { i, (name, data) ->
            buf.position(nameTable + i * 48)
            buf.put(name.toByteArray())
            buf.position(nameTable + i * 48 + 44)
            buf.putInt(data.size)
        }
        return buf.array()
    }
}
