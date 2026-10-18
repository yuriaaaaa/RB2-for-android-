package com.deivid22srk.restuff.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Caminhos de dados do port ReStuff.
 *
 * Modelo de assets (mesma semântica do goopie launcher no PC):
 *  - [gameRoot]    : "game_data_root" — pasta com Default.xex + conteúdo.
 *                    O runtime do rexglue monta esta pasta como
 *                    \Device\Harddisk0\Partition1 (HostPathDevice).
 *                    Pode ser (a) a pasta REAL no armazenamento do usuário
 *                    (fluxo de pasta, SEM cópia — o motor lê direto via
 *                    POSIX com "Acesso a todos os arquivos") ou (b) a pasta
 *                    extraída do ISO no armazenamento privado do app
 *                    (legado — versões antigas do app extraíam ~8 GB).
 *  - [isoSource]   : "iso_source_path" — boot IN-PLACE do ISO (modelo
 *                    XenDroid): caminho real do .iso OU content:// URI do
 *                    SAF. O motor monta a IMAGEM onde ela está
 *                    (DiscImageDevice — mmap) e NADA é copiado para dentro
 *                    do app. Tem prioridade sobre os dois fluxos acima.
 *  - [savesDir]    : "user_data_root" — saves/XCONTENT do jogo, separados
 *                    da pasta do jogo para que "limpar dados" não apague saves.
 *  - [configFile]  : restuff.toml gerado a partir das Configurações do app.
 */
object GamePaths {

    private const val GAME_DIR_NAME = "game"
    private const val SAVES_DIR_NAME = "saves"
    private const val EXTRACT_MARKER = ".extract_ok"
    const val XEX_NAME = "Default.xex"

    /** Mesmo arquivo de prefs usado pelo [com.deivid22srk.restuff.viewmodel.DataSelectionViewModel]. */
    private const val PREFS_NAME = "port_screen_prefs"
    private const val KEY_GAME_ROOT = "game_root_path"
    private const val KEY_ISO_SOURCE = "iso_source_path"

    /**
     * Raiz do jogo que o motor deve usar (fluxos pasta/extraído — para o
     * boot IN-PLACE do ISO veja [isoSource]/[gameDataRootArgument]): a pasta
     * real persistida pelo fluxo de pasta (sem cópia) quando ainda existe
     * no disco; senão a pasta extraída do ISO no armazenamento privado.
     */
    fun gameRoot(context: Context): File {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_GAME_ROOT, null)?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }
        return gameDir(context)
    }

    /**
     * Fonte do ISO para o boot IN-PLACE (caminho real ou content:// URI),
     * persistida entre sessões/reboots. Tem prioridade sobre a pasta e
     * sobre o extraído legado quando definida.
     */
    fun isoSource(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ISO_SOURCE, null)

    /** Persiste a fonte do ISO in-place. null limpa. */
    fun setIsoSource(context: Context, source: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (source == null) prefs.edit().remove(KEY_ISO_SOURCE).apply()
        else prefs.edit().putString(KEY_ISO_SOURCE, source).apply()
    }

    /**
     * Valor de --game_data_root entregue ao motor: o ISO in-place quando
     * selecionado (caminho real OU content:// — o nativo decide como mapear),
     * senão a pasta do fluxo pasta/extraído. Sempre retorna um valor — o
     * extraído legado é o último recurso.
     */
    fun gameDataRootArgument(context: Context): String =
        isoSource(context) ?: gameRoot(context).absolutePath

    fun gameDir(context: Context): File =
        File(context.filesDir, GAME_DIR_NAME)

    fun savesDir(context: Context): File =
        File(context.filesDir, SAVES_DIR_NAME)

    fun configFile(context: Context): File =
        File(context.filesDir, "restuff.toml")

    fun cacheDir(context: Context): File =
        File(context.filesDir, "cache").apply { mkdirs() }

    /** Marker da extração concluída com sucesso (fluxo ISO). */
    fun extractMarker(context: Context): File =
        File(gameDir(context), EXTRACT_MARKER)

    /** Jogo já está pronto (extração completa + Default.xex presente)? */
    fun isGameDataReady(context: Context): Boolean =
        extractMarker(context).exists() && File(gameDir(context), XEX_NAME).isFile

    /** Default.xex presente mesmo sem extração completa (modo pasta)? */
    fun hasRawXex(context: Context): Boolean =
        File(gameDir(context), XEX_NAME).isFile

    /**
     * Procura um arquivo na pasta comparando SEM diferenciar maiúsculas
     * (uma listagem POSIX — barato, sem IPC de SAF). O Android diferencia
     * caso no disco; o jogo pode trazer "Default.xex", "default.xex" etc.
     */
    fun findFileCaseInsensitive(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** O entrypoint do jogo (Default.xex, qualquer caso) dentro de [dir]. */
    fun findXex(dir: File): File? = findFileCaseInsensitive(dir, XEX_NAME)

    /** Persiste a raiz do jogo real (fluxo pasta sem cópia). null limpa. */
    fun setGameRoot(context: Context, path: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (path == null) prefs.edit().remove(KEY_GAME_ROOT).apply()
        else prefs.edit().putString(KEY_GAME_ROOT, path).apply()
    }

    // ---- Downloadable content -------------------------------------------
    // Xbox 360 DLC packages are STFS files ("LIVE"/"PIRS"/"CON " magic) kept by
    // title ID and content type: 4E4D0826 = Dragon Ball Raging Blast 2, 00000002 =
    // marketplace content. The engine mounts them in place (dlc_package_path),
    // so nothing is copied or extracted.
    const val TITLE_ID = "4E4D0826"
    private const val MARKETPLACE_CONTENT = "00000002"

    /** The folder holding this game's DLC packages, or null if none is found. */
    fun dlcDir(context: Context): File? {
        val bases = LinkedHashSet<File>()
        fun addWithParent(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            bases += dir
            dir.parentFile?.let { bases += it }
        }
        // Folder flow: the game folder and the folder it sits in ("next to /game").
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GAME_ROOT, null)?.let { addWithParent(File(it)) }
        // ISO flow: the ISO's folder and the one above it.
        isoSource(context)?.let { realPathOf(it) }?.let { addWithParent(File(it).parentFile) }
        // This app's own folder: Internal storage/Raging Blast 2.
        bases += File(Environment.getExternalStorageDirectory(), "Raging Blast 2")

        val layouts = listOf(
            listOf(TITLE_ID, MARKETPLACE_CONTENT),
            listOf("Content", "0000000000000000", TITLE_ID, MARKETPLACE_CONTENT),
            listOf("0000000000000000", TITLE_ID, MARKETPLACE_CONTENT),
        )
        for (base in bases) {
            for (layout in layouts) {
                var dir: File? = base
                for (part in layout) {
                    dir = dir?.let { d -> findFileCaseInsensitive(d, part)?.takeIf { it.isDirectory } }
                }
                if (dir != null && dlcPackages(dir).isNotEmpty()) return dir
            }
        }
        return null
    }

    /** The STFS packages directly inside [dir]. */
    fun dlcPackages(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && isStfsPackage(it) }.orEmpty()

    private fun isStfsPackage(file: File): Boolean = runCatching {
        val magic = ByteArray(4)
        val read = file.inputStream().use { it.read(magic) }
        if (read != 4) return@runCatching false
        val text = String(magic, Charsets.ISO_8859_1)
        text == "LIVE" || text == "PIRS" || text == "CON "
    }.getOrDefault(false)

    /**
     * The file-system path behind an ISO source: a plain path as is, or a
     * document picked on the phone's own storage (externalstorage "primary:").
     */
    private fun realPathOf(source: String): String? {
        if (source.startsWith("/")) return source
        if (!source.startsWith("content://")) return null
        return runCatching {
            val uri = Uri.parse(source)
            if (uri.authority != "com.android.externalstorage.documents") return@runCatching null
            val id = DocumentsContract.getDocumentId(uri)
            if (!id.startsWith("primary:")) return@runCatching null
            File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:")).absolutePath
        }.getOrNull()
    }

    /** Prepara a árvore de diretórios do port. */
    fun ensureDirs(context: Context) {
        gameDir(context).mkdirs()
        savesDir(context).mkdirs()
        cacheDir(context)
    }

    /** Apaga todo o conteúdo extraído (limpeza do extraído legado). */
    fun wipeGameData(context: Context) {
        gameDir(context).deleteRecursively()
    }
}
