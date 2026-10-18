package com.deivid22srk.restuff.reshade

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReShadeFilesTest {

    private fun tempDir(): File = Files.createTempDirectory("reshade").toFile()

    @Test
    fun techniquesOutsideCommentsAreFound() {
        val source = """
            // technique Commented
            /* technique Blocked < ui_label = "x"; > { } */
            technique LumaSharpen < ui_label = "Sharpen"; >
            { pass { VertexShader = PostProcessVS; PixelShader = PS; } }
              technique Second { }
        """.trimIndent()
        assertEquals(listOf("LumaSharpen", "Second"), ReShadeFiles.techniquesIn(source))
    }

    @Test
    fun writingTechniquesKeepsEffectValues() {
        val preset = File(tempDir(), "ReShadePreset.ini")
        preset.writeText(
            "PreprocessorDefinitions=FOO=1\nTechniques=Old@Old.fx\n\n[LumaSharpen.fx]\nsharp_strength=1.2\n"
        )
        ReShadeFiles.writeTechniques(preset, listOf("LumaSharpen@LumaSharpen.fx", "Vignette@Vignette.fx"))
        val text = preset.readText()
        assertTrue(text.contains("Techniques=LumaSharpen@LumaSharpen.fx,Vignette@Vignette.fx\n"))
        assertTrue(text.contains("TechniqueSorting=LumaSharpen@LumaSharpen.fx,Vignette@Vignette.fx\n"))
        assertTrue(text.contains("PreprocessorDefinitions=FOO=1"))
        assertTrue(text.contains("[LumaSharpen.fx]\nsharp_strength=1.2"))
        // The new lines go before the first section, where ReShade reads them.
        assertTrue(text.indexOf("TechniqueSorting=") < text.indexOf("[LumaSharpen.fx]"))
        assertEquals(
            listOf("LumaSharpen@LumaSharpen.fx", "Vignette@Vignette.fx"),
            ReShadeFiles.readTechniques(preset)
        )
    }

    @Test
    fun techniquesInSectionsAreNotThePresetList() {
        val preset = File(tempDir(), "p.ini")
        preset.writeText("[Some.fx]\nTechniques=Nope\n")
        assertEquals(emptyList<String>(), ReShadeFiles.readTechniques(preset))
    }

    @Test
    fun packZipsInstallShadersAndTexturesOnly() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("reshade-shaders-slim/Shaders/ReShade.fxh", "fxh")
            put("reshade-shaders-slim/Shaders/Sub/A.fx", "technique A {}")
            put("reshade-shaders-slim/Textures/lut.png", "png")
            put("reshade-shaders-slim/README.md", "readme")
            put("reshade-shaders-slim/Shaders/../../evil.fx", "nope")
        }
        val root = tempDir()
        val count = ReShadeFiles.installPack(ByteArrayInputStream(bytes.toByteArray()), root)
        assertEquals(3, count)
        assertTrue(File(root, "Shaders/ReShade.fxh").isFile)
        assertTrue(File(root, "Textures/lut.png").isFile)
        assertFalse(File(root, "README.md").exists())
        assertFalse(File(root.parentFile, "evil.fx").exists())
        assertEquals(listOf(ReShadeFiles.Technique("A", "A.fx")), ReShadeFiles.scan(root))
    }
}
