package com.deivid22srk.restuff.mods

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Small client for GameBanana's public API (apiv11), limited to Dragon Ball:
 * Raging Blast 2 (game 16075): mod lists, search, mod pages and downloads.
 * Ported from the Marathon Recompiled Android launcher.
 */
object GameBanana {
    const val GAME_ID = 16075
    const val GAME_PAGE = "https://gamebanana.com/games/$GAME_ID"
    private const val API = "https://gamebanana.com/apiv11"
    private const val USER_AGENT = "RagingBlast2Android/0.3 (+launcher mod browser)"

    data class Mod(
        val id: Int,
        val name: String,
        val author: String,
        val category: String,
        val thumbnail: String,
        val profileUrl: String,
        val likes: Int,
        val views: Int,
        /** Seconds: last update, else last change, else added. */
        val updated: Long,
    )

    data class ModFile(
        val id: Int,
        val name: String,
        val description: String,
        val version: String,
        val md5: String,
        val downloadUrl: String,
        val size: Long,
        val added: Long,
        val downloads: Int,
    )

    data class Details(val mod: Mod, val text: String, val image: String, val downloads: Int, val files: List<ModFile>)

    /** A batch of mods, and where the next batch starts. */
    data class Page(val mods: List<Mod>, val next: Int, val complete: Boolean)

    /** Mods from the game's feed: [sort] is "new" or "updated". */
    fun list(sort: String, page: Int): Page {
        val mods = mutableListOf<Mod>()
        val seen = HashSet<Int>()
        var next = page
        var complete = false
        var s = sort
        var tries = 0
        // The feed also carries questions and requests, so keep reading until a few mods turn up.
        while (tries < 4 && mods.size < 8 && !complete) {
            val json = try {
                get(feedUrl(s, next))
            } catch (e: IOException) {
                if (tries > 0 || s == "default") throw e
                s = "default" // in case GameBanana stops taking this sort order
                get(feedUrl(s, next))
            }
            next++
            mods(json).filter { seen.add(it.id) }.forEach { mods += it }
            complete = complete(json)
            tries++
        }
        return Page(mods, next, complete)
    }

    fun search(query: String, page: Int): Page {
        val failure: IOException = try {
            val json = get(
                "$API/Util/Search/Results?_sModelName=Mod&_sOrder=best_match&_idGameRow=$GAME_ID" +
                    "&_sSearchString=${URLEncoder.encode(query, "UTF-8")}" +
                    "&_csvFields=name,description,article,attribs,studio,owner,credits&_nPage=$page"
            )
            if (json.has("_aRecords")) return Page(mods(json), page + 1, complete(json))
            IOException("GameBanana's search sent something unexpected")
        } catch (e: IOException) {
            e
        }
        // Fall back to reading the mod list and matching names, authors and categories.
        return try {
            scan(query, page)
        } catch (e: IOException) {
            throw failure
        }
    }

    private fun scan(query: String, page: Int): Page {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+"))
        val mods = mutableListOf<Mod>()
        val seen = HashSet<Int>()
        var next = page
        var complete = false
        var tries = 0
        while (tries < 10 && mods.size < 6 && !complete) {
            val json = get(feedUrl("new", next))
            next++
            complete = complete(json)
            for (mod in mods(json)) {
                if (!seen.add(mod.id)) continue
                val haystack = "${mod.name} ${mod.author} ${mod.category}".lowercase(Locale.ROOT)
                if (words.all { haystack.contains(it) }) mods += mod
            }
            tries++
        }
        return Page(mods, next, complete)
    }

    fun details(modId: Int): Details {
        val json = get("$API/Mod/$modId/ProfilePage")
        var mod = mod(json).copy(id = modId)
        if (mod.category.isEmpty()) {
            // ProfilePage has _aCategory; lists have _aRootCategory.
            json.optJSONObject("_aCategory")?.optString("_sName", "")?.let { mod = mod.copy(category = it) }
        }
        val files = mutableListOf<ModFile>()
        val array = json.optJSONArray("_aFiles")
        if (array != null) for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            val id = f.optInt("_idRow")
            files += ModFile(
                id = id,
                name = f.optString("_sFile", ""),
                description = f.optString("_sDescription", ""),
                version = f.optString("_sVersion", ""),
                md5 = f.optString("_sMd5Checksum", ""),
                downloadUrl = f.optString("_sDownloadUrl", "https://gamebanana.com/dl/$id"),
                size = f.optLong("_nFilesize"),
                added = f.optLong("_tsDateAdded"),
                downloads = f.optInt("_nDownloadCount"),
            )
        }
        return Details(mod, json.optString("_sText", ""), image(json, large = true), json.optInt("_nDownloadCount"), files)
    }

    /**
     * Downloads [file] into [target], checking GameBanana's MD5. [progress] gets bytes so far
     * and the total, and returns false to cancel.
     */
    fun download(file: ModFile, target: File, progress: (Long, Long) -> Boolean) {
        val connection = open(file.downloadUrl)
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("GameBanana answered $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: file.size
            val md5 = MessageDigest.getInstance("MD5")
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        md5.update(buffer, 0, n)
                        done += n
                        if (!progress(done, total)) throw IOException("Cancelled")
                    }
                }
            }
            val sum = md5.digest().joinToString("") { "%02x".format(it) }
            if (file.md5.isNotEmpty() && !sum.equals(file.md5, ignoreCase = true)) {
                throw IOException("The download was damaged (checksum mismatch). Try again.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The mod id in a GameBanana link: a mod page, or a 1-click `mmdl/FILE,Mod,MOD` link. */
    fun linkTarget(text: String): Pair<Int, Int>? {
        Regex("mmdl/(\\d+),Mod,(\\d+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[2].toInt() to it.groupValues[1].toInt()
        }
        Regex("gamebanana\\.com/mods/(\\d+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toInt() to 0
        }
        return null
    }

    private fun feedUrl(sort: String, page: Int) =
        "$API/Game/$GAME_ID/Subfeed?_sSort=$sort&_nPage=$page&_csvModelInclusions=Mod"

    private fun complete(json: JSONObject): Boolean {
        val records = json.optJSONArray("_aRecords")?.length() ?: 0
        return records == 0 || json.optJSONObject("_aMetadata")?.optBoolean("_bIsComplete", false) == true
    }

    private fun mods(json: JSONObject): List<Mod> {
        val records = json.optJSONArray("_aRecords") ?: return emptyList()
        return (0 until records.length()).mapNotNull { i ->
            val r = records.optJSONObject(i) ?: return@mapNotNull null
            if (r.optString("_sModelName") != "Mod") return@mapNotNull null
            val game = r.optJSONObject("_aGame")
            if (game != null && game.optInt("_idRow", GAME_ID) != GAME_ID) return@mapNotNull null
            mod(r)
        }
    }

    private fun mod(r: JSONObject): Mod {
        val id = r.optInt("_idRow")
        return Mod(
            id = id,
            name = r.optString("_sName", ""),
            author = r.optJSONObject("_aSubmitter")?.optString("_sName", "") ?: "",
            category = r.optJSONObject("_aRootCategory")?.optString("_sName", "") ?: "",
            thumbnail = image(r, large = false),
            profileUrl = r.optString("_sProfileUrl", "https://gamebanana.com/mods/$id"),
            likes = r.optInt("_nLikeCount"),
            views = r.optInt("_nViewCount"),
            updated = r.optLong("_tsDateUpdated", r.optLong("_tsDateModified", r.optLong("_tsDateAdded"))),
        )
    }

    private fun image(r: JSONObject, large: Boolean): String {
        val first = r.optJSONObject("_aPreviewMedia")?.optJSONArray("_aImages")?.optJSONObject(0) ?: return ""
        val base = first.optString("_sBaseUrl", "")
        val file = if (large) first.optString("_sFile530", first.optString("_sFile", ""))
        else first.optString("_sFile220", first.optString("_sFile100", first.optString("_sFile", "")))
        return if (base.isEmpty() || file.isEmpty()) "" else "$base/$file"
    }

    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", USER_AGENT)
        connectTimeout = 15000
        readTimeout = 30000
        instanceFollowRedirects = true
    }

    private fun get(url: String): JSONObject {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("GameBanana answered $code")
            return JSONObject(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } catch (e: JSONException) {
            throw IOException("GameBanana sent something unexpected")
        } finally {
            connection.disconnect()
        }
    }
}
