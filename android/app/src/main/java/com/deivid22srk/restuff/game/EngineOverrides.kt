package com.deivid22srk.restuff.game

/**
 * "Engine options" typed into Settings: one `key = value` per line, applied on
 * top of the restuff.toml the launcher regenerates on every boot.
 *
 * Only values that are valid TOML scalars get through, so a typo can never
 * make the whole config unreadable (which would stop the engine from booting):
 *  - `true` / `false` (any case)
 *  - integers and decimals (`1`, `-2`, `0.5`)
 *  - double-quoted strings without escapes (`"fast"`)
 *  - bare words (`none`, `fast`) are quoted automatically
 * Lines starting with `#` and blank lines are ignored.
 */
object EngineOverrides {

    data class Line(val raw: String, val key: String?, val value: String?) {
        val valid: Boolean get() = key != null && value != null
    }

    private val keyValue = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$""")
    private val number = Regex("""^-?\d+(\.\d+)?$""")
    private val quoted = Regex("""^"[^"\\]*"$""")
    private val bareWord = Regex("""^[A-Za-z0-9_.\-]+$""")

    /** Parses every non-blank, non-comment line; invalid ones have nulls. */
    fun parse(text: String): List<Line> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val m = keyValue.matchEntire(line) ?: return@map Line(line, null, null)
                val key = m.groupValues[1].lowercase()
                Line(line, key, normalizeValue(m.groupValues[2]))
            }

    private fun normalizeValue(v: String): String? = when {
        v.equals("true", ignoreCase = true) -> "true"
        v.equals("false", ignoreCase = true) -> "false"
        number.matches(v) -> v
        quoted.matches(v) -> v
        bareWord.matches(v) -> "\"$v\""
        else -> null
    }

    /**
     * Applies the valid lines to [cfg] (replacing existing keys in place,
     * appending new ones) and returns them as `key = value` for logging.
     */
    fun apply(cfg: MutableMap<String, String>, text: String): List<String> =
        parse(text).filter { it.valid }.map { line ->
            cfg[line.key!!] = line.value!!
            "${line.key} = ${line.value}"
        }
}
