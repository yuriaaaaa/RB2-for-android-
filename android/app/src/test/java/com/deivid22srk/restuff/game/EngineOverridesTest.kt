package com.deivid22srk.restuff.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOverridesTest {

    private fun defaults() = linkedMapOf(
        "readback_resolve" to "\"none\"",
        "vulkan_sparse_shared_memory" to "false",
        "resolution_scale" to "1",
    )

    @Test
    fun replacesExistingKeysInPlaceAndAppendsNewOnes() {
        val cfg = defaults()
        val applied = EngineOverrides.apply(
            cfg,
            """
            vulkan_sparse_shared_memory = TRUE
            readback_resolve = fast
            vulkan_dynamic_rendering=false
            """.trimIndent()
        )
        assertEquals(
            listOf("readback_resolve", "vulkan_sparse_shared_memory", "resolution_scale",
                "vulkan_dynamic_rendering"),
            cfg.keys.toList()
        )
        assertEquals("true", cfg["vulkan_sparse_shared_memory"])
        assertEquals("\"fast\"", cfg["readback_resolve"])
        assertEquals("false", cfg["vulkan_dynamic_rendering"])
        assertEquals(3, applied.size)
    }

    @Test
    fun keepsNumbersAndQuotedStrings() {
        val cfg = defaults()
        EngineOverrides.apply(cfg, "resolution_scale = 2\nrender_target_path_vulkan = \"fbo\"\nx = -0.5")
        assertEquals("2", cfg["resolution_scale"])
        assertEquals("\"fbo\"", cfg["render_target_path_vulkan"])
        assertEquals("-0.5", cfg["x"])
    }

    @Test
    fun ignoresCommentsBlanksAndInvalidLines() {
        val cfg = defaults()
        val lines = EngineOverrides.parse(
            "# comment\n\nnot a pair\nbad key = 1\nquote = \"unterminated\nok = yes"
        )
        assertEquals(4, lines.size)
        assertFalse(lines[0].valid) // not a pair
        assertFalse(lines[1].valid) // space in key
        assertFalse(lines[2].valid) // unterminated string
        assertTrue(lines[3].valid)
        EngineOverrides.apply(cfg, "not a pair\nquote = \"unterminated")
        assertEquals(defaults(), cfg)
    }
}
