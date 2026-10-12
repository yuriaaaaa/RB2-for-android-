package com.deivid22srk.restuff.ui.mods

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Html
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.mods.GameBanana
import com.deivid22srk.restuff.mods.ModManager
import com.deivid22srk.restuff.mods.Mods
import com.deivid22srk.restuff.ui.components.portClickable
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortScreenTheme
import com.deivid22srk.restuff.ui.theme.PortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Raging Blast 2 mods on GameBanana: browse, search, and install a file with one tap.
 * GameBanana has no 1-click button for this game, so a mod link shared to the app
 * (or an `mmdl/FILE,Mod,MOD` 1-click link) opens here instead.
 */
class ModBrowserActivity : ComponentActivity() {

    private val link = mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) link.value = linkFrom(intent)
        setContent {
            PortScreenTheme {
                ModBrowserScreen(link = link.value, onLinkHandled = { link.value = null }, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { link.value = it }
    }

    private fun linkFrom(intent: Intent?): Pair<Int, Int>? {
        intent ?: return null
        val modId = intent.getIntExtra(EXTRA_MOD_ID, 0)
        if (modId > 0) return modId to intent.getIntExtra(EXTRA_FILE_ID, 0)
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return GameBanana.linkTarget(text)
    }

    companion object {
        const val EXTRA_MOD_ID = "modId"
        const val EXTRA_FILE_ID = "fileId"
    }
}

@Composable
private fun ModBrowserScreen(link: Pair<Int, Int>?, onLinkHandled: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manager by remember { mutableStateOf<ModManager?>(null) }
    var managerChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        manager = withContext(Dispatchers.IO) { runCatching { Mods.manager(context) }.getOrNull() }
        managerChecked = true
    }

    val install = remember { ModInstallState() }
    VersionPickerDialog(install)

    var sort by remember { mutableStateOf("new") }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf<List<GameBanana.Mod>>(emptyList()) }
    var next by remember { mutableStateOf(1) }
    var complete by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    var openMod by remember { mutableStateOf<Int?>(null) }
    var autoFile by remember { mutableStateOf(0) }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        listError = null
        val page = if (reset) 1 else next
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (searched.isBlank()) GameBanana.list(sort, page) else GameBanana.search(searched, page)
                }
                mods = if (reset) result.mods else (mods + result.mods).distinctBy { it.id }
                next = result.next
                complete = result.complete
            } catch (e: Exception) {
                listError = "Couldn't reach GameBanana: ${e.message ?: "no connection"}"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(sort, searched) { load(reset = true) }

    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        openMod = target.first
        autoFile = target.second
        onLinkHandled()
    }

    fun installFile(file: GameBanana.ModFile, modName: String) {
        val m = manager
        if (m == null) {
            install.message = "Mods need the extracted game folder. They can't be installed while the game starts straight from an ISO."
            install.isError = true
            return
        }
        if (install.busy != null) return
        install.message = null
        install.busy = "Downloading ${file.name}…"
        scope.launch {
            val target = File(context.cacheDir, "mod-download/${file.id}-${file.name.replace('/', '_')}")
            try {
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    var shown = ""
                    GameBanana.download(file, target) { done, total ->
                        val line = "Downloading ${file.name}… " + if (total > 0) "${done * 100 / total}%" else size(done)
                        if (line != shown) {
                            shown = line
                            scope.launch { if (install.busy?.startsWith("Downloading") == true) install.busy = line }
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                install.busy = null
                install.message = e.message ?: "The download failed."
                install.isError = true
                withContext(Dispatchers.IO) { target.delete() }
                return@launch
            }
            try {
                install.install(m, target, modName.ifBlank { file.name })
            } finally {
                withContext(Dispatchers.IO) { target.delete() }
            }
        }
    }

    BackHandler(enabled = openMod != null) { openMod = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PortPalette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = PortPalette.textPrimary,
                modifier = Modifier
                    .size(40.dp)
                    .portClickable { if (openMod != null) openMod = null else onClose() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("GameBanana mods", color = PortPalette.textPrimary, style = PortType.titleScreen)
        }

        InstallStatus(install, managerChecked && manager == null)

        val modId = openMod
        if (modId != null) {
            ModDetails(
                modId = modId,
                autoFile = autoFile,
                busy = install.busy != null,
                onAutoFileUsed = { autoFile = 0 },
                onInstall = ::installFile,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search Raging Blast 2 mods", color = PortPalette.textTertiary) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searched = query.trim() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PortPalette.textPrimary,
                        unfocusedTextColor = PortPalette.textPrimary,
                        focusedBorderColor = PortBranding.config.accent,
                        unfocusedBorderColor = PortPalette.ghostBorder,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Chip("Search", selected = false) { searched = query.trim() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                Chip("Newest", selected = searched.isBlank() && sort == "new") { query = ""; searched = ""; sort = "new" }
                Chip("Updated", selected = searched.isBlank() && sort == "updated") { query = ""; searched = ""; sort = "updated" }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(mods, key = { it.id }) { mod ->
                    ModRow(mod) {
                        autoFile = 0
                        openMod = mod.id
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = PortBranding.config.accent)
                            listError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(listError!!, color = PortPalette.error, style = PortType.rowSub)
                                Spacer(Modifier.height(8.dp))
                                Chip("Try again", selected = false) { load(reset = mods.isEmpty()) }
                            }
                            mods.isEmpty() -> Text("No mods found.", color = PortPalette.textSecondary, style = PortType.rowSub)
                            !complete -> Chip("Load more", selected = false) { load(reset = false) }
                            else -> Text("That's all of them.", color = PortPalette.textTertiary, style = PortType.rowSub)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallStatus(install: ModInstallState, noGameFolder: Boolean) {
    val accent = PortBranding.config.accent
    val busy = install.busy
    val message = install.message
    when {
        busy != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(busy, color = accent, style = PortType.rowSub)
        }
        message != null -> Text(
            message,
            color = if (install.isError) PortPalette.error else PortPalette.success,
            style = PortType.rowSub,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        noGameFolder -> Text(
            "Mods need the extracted game folder. They can't be installed while the game starts straight from an ISO.",
            color = PortPalette.textTertiary,
            style = PortType.rowSub,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun ModRow(mod: GameBanana.Mod, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .portClickable(onClick = onOpen)
            .padding(vertical = 8.dp)
    ) {
        RemoteImage(
            url = mod.thumbnail,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(PortPalette.radiusSm)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mod.name, color = PortPalette.textPrimary, style = PortType.rowLabel, maxLines = 2)
            Text(
                listOf(mod.author, mod.category).filter { it.isNotBlank() }.joinToString(" · "),
                color = PortPalette.textSecondary,
                style = PortType.rowSub,
                maxLines = 1,
            )
            Text("♥ ${mod.likes} · ${mod.views} views", color = PortPalette.textTertiary, style = PortType.rowSub)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(PortPalette.hairline))
}

@Composable
private fun ModDetails(
    modId: Int,
    autoFile: Int,
    busy: Boolean,
    onAutoFileUsed: () -> Unit,
    onInstall: (GameBanana.ModFile, String) -> Unit,
) {
    var details by remember(modId) { mutableStateOf<GameBanana.Details?>(null) }
    var error by remember(modId) { mutableStateOf<String?>(null) }
    var attempt by remember(modId) { mutableStateOf(0) }
    LaunchedEffect(modId, attempt) {
        error = null
        try {
            details = withContext(Dispatchers.IO) { GameBanana.details(modId) }
        } catch (e: Exception) {
            error = "Couldn't open this mod: ${e.message ?: "no connection"}"
        }
    }
    // A 1-click link names the file: install it as soon as the page is known.
    LaunchedEffect(details, autoFile) {
        val d = details ?: return@LaunchedEffect
        if (autoFile == 0) return@LaunchedEffect
        d.files.firstOrNull { it.id == autoFile }?.let { onInstall(it, d.mod.name) }
        onAutoFileUsed()
    }

    val d = details
    if (d == null) {
        Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
            val e = error
            if (e == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = PortBranding.config.accent)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, color = PortPalette.error, style = PortType.rowSub)
                Spacer(Modifier.height(8.dp))
                Chip("Try again", selected = false) { attempt++ }
            }
        }
        return
    }

    val text = remember(d.text) {
        Html.fromHtml(d.text, Html.FROM_HTML_MODE_COMPACT).toString().trim().replace(Regex("\n{3,}"), "\n\n")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (d.image.isNotEmpty()) {
            RemoteImage(
                url = d.image,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(PortPalette.radiusMd)),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(d.mod.name, color = PortPalette.textPrimary, style = PortType.titleScreen)
        Text(
            listOf(d.mod.author, d.mod.category, "${d.downloads} downloads").filter { it.isNotBlank() }.joinToString(" · "),
            color = PortPalette.textSecondary,
            style = PortType.rowSub,
        )
        Spacer(Modifier.height(16.dp))
        Text("Files", color = PortPalette.textPrimary, style = PortType.rowLabel)
        if (d.files.isEmpty()) Text("This mod has no files to download.", color = PortPalette.textSecondary, style = PortType.rowSub)
        d.files.forEach { file ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = PortPalette.textPrimary, style = PortType.rowLabel)
                    Text(
                        listOf(size(file.size), file.version.takeIf { it.isNotBlank() }?.let { "v$it" }, file.description.takeIf { it.isNotBlank() })
                            .filterNotNull().joinToString(" · "),
                        color = PortPalette.textSecondary,
                        style = PortType.rowSub,
                    )
                }
                Spacer(Modifier.width(12.dp))
                val supported = Regex("\\.(zip|rar|7z)$", RegexOption.IGNORE_CASE).containsMatchIn(file.name)
                Chip(if (supported) "Install" else "Not an archive", selected = supported, enabled = supported && !busy) {
                    onInstall(file, d.mod.name)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PortPalette.hairline))
        }
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(text, color = PortPalette.textSecondary, style = PortType.rowSub)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = PortBranding.config.accent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(PortPalette.radiusSm))
            .then(
                if (selected) Modifier.background(accent.copy(alpha = if (enabled) 1f else 0.35f))
                else Modifier.border(1.dp, PortPalette.ghostBorder, RoundedCornerShape(PortPalette.radiusSm))
            )
            .portClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (selected) PortPalette.onAccent else PortPalette.textPrimary, style = PortType.caption)
    }
}

/** A GameBanana preview image, loaded once and kept in a small memory cache. */
@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    val image by produceState(initialValue = Thumbnails.cached(url), url) {
        if (value == null && url.isNotEmpty()) value = withContext(Dispatchers.IO) { Thumbnails.load(url) }
    }
    Box(modifier.background(PortPalette.surface)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private object Thumbnails {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(url: String): ImageBitmap? = if (url.isEmpty()) null else cache.get(url)

    fun load(url: String): ImageBitmap? = runCatching {
        val connection = GameBanana.open(url)
        try {
            val bytes = connection.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(url, it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10} KB"
    else -> "$bytes B"
}
