package com.deivid22srk.restuff.ui.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.mods.ModManager
import com.deivid22srk.restuff.ui.components.portClickable
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** "Which version?" for a mod that ships several versions of one character. */
class VersionPrompt(val character: String, val versions: List<String>, val answer: CompletableDeferred<Int>)

/** Install progress, the outcome, and an open version question, shared by the Mods screens. */
class ModInstallState {
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
    var prompt by mutableStateOf<VersionPrompt?>(null)

    /**
     * Installs [archive] (.zip/.rar/.7z) with [manager], showing progress and asking about
     * versions. Returns the installed mod, or null after showing why it failed.
     */
    suspend fun install(manager: ModManager, archive: File, name: String): ModManager.InstalledMod? {
        busy = "Installing $name…"
        message = null
        return try {
            val mod = withContext(Dispatchers.IO) {
                manager.install(
                    archive, name,
                    choose = { character, versions ->
                        // Runs on the install thread: wait here for the person's pick.
                        val answer = CompletableDeferred<Int>()
                        runBlocking {
                            withContext(Dispatchers.Main) { prompt = VersionPrompt(character, versions, answer) }
                            answer.await()
                        }
                    },
                    progress = { line -> busy = line },
                )
            }
            message = summary(mod)
            isError = false
            mod
        } catch (e: Exception) {
            message = e.message ?: "Couldn't install the mod."
            isError = true
            null
        } finally {
            busy = null
            prompt = null
        }
    }

    companion object {
        fun summary(mod: ModManager.InstalledMod): String {
            val files = mod.changes.count { it.kind != ModManager.Kind.SAVE }
            val save = mod.changes.any { it.kind == ModManager.Kind.SAVE }
            return "Installed “${mod.name}”: $files file" + (if (files == 1) "" else "s") +
                " replaced, originals kept as .bk." +
                (if (save) " Your save was replaced too; the old one is kept as DB_SAVE.bk." else "") +
                mod.notes.joinToString("") { " $it" } +
                mod.skipped.filter {
                    it.reason != "not a game file" && it.reason != "another copy of the save" &&
                        !it.reason.startsWith("another version of this character")
                }.let { worth ->
                    if (worth.isEmpty()) "" else " Skipped ${worth.size}: " +
                        worth.take(3).joinToString("; ") { "${it.path.substringAfterLast('/')} (${it.reason})" }
                }
        }
    }
}

/** The version picker for [state]'s open question, if any. */
@Composable
fun VersionPickerDialog(state: ModInstallState) {
    val prompt = state.prompt ?: return
    val accent = PortBranding.config.accent
    fun pick(index: Int) {
        state.prompt = null
        prompt.answer.complete(index)
    }
    AlertDialog(
        onDismissRequest = { pick(0) },
        containerColor = PortPalette.surface,
        title = { Text("Which version?", color = PortPalette.textPrimary, style = PortType.rowLabel) },
        text = {
            Column {
                Text(
                    "This mod has ${prompt.versions.size} versions of ${prompt.character}, and the game " +
                        "has room for one. Pick the one to install; install the mod again to switch.",
                    color = PortPalette.textSecondary,
                    style = PortType.rowSub,
                )
                prompt.versions.forEachIndexed { k, label ->
                    Text(
                        text = label,
                        color = PortPalette.textPrimary,
                        style = PortType.rowLabel,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(PortPalette.background, RoundedCornerShape(PortPalette.radiusSm))
                            .portClickable { pick(k) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { pick(0) }) { Text("Use the first", color = accent) } },
    )
}

/** File types the mod picker offers: zip, rar and 7z (plus anything, for odd providers). */
val MOD_ARCHIVE_MIME = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-rar-compressed",
    "application/vnd.rar",
    "application/x-rar",
    "application/x-7z-compressed",
    "application/octet-stream",
)
