/*
 * Tela de Configurações do port — design "Mel & Carvão" (flat editorial).
 *
 * Sem painéis de vidro e sem ícones por seção: rótulos em versalete com
 * ponto âmbar, linhas separadas por hairline, UM alvo de peso (o controle
 * da linha). Tudo é persistido por [PortSettingsViewModel] e DE FATO
 * consumido: os campos do motor viram cvars do restuff.toml/argv (ver
 * GameActivity.getArguments()), os de controles alimentam o
 * VirtualGamepadView, e os de driver/diagnóstico operam o GpuDriverManager
 * e o log persistido. O contador de FPS (novo) lê os presents Vulkan reais
 * via JNI (nativeGetPresentCount).
 */
package com.deivid22srk.restuff.ui.settings

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.data.GpuDriverManager
import com.deivid22srk.restuff.game.EngineOverrides
import com.deivid22srk.restuff.mods.ModManager
import com.deivid22srk.restuff.mods.Mods
import com.deivid22srk.restuff.reshade.ReShadeFiles
import com.deivid22srk.restuff.ui.mods.MOD_ARCHIVE_MIME
import com.deivid22srk.restuff.ui.mods.ModBrowserActivity
import com.deivid22srk.restuff.ui.mods.ModInstallState
import com.deivid22srk.restuff.ui.mods.VersionPickerDialog
import com.deivid22srk.restuff.settings.FpsLimitOption
import com.deivid22srk.restuff.settings.PortSettings
import com.deivid22srk.restuff.settings.PortSettingsViewModel
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import com.deivid22srk.restuff.ui.components.portClickable
import com.deivid22srk.restuff.viewmodel.DataPhase
import com.deivid22srk.restuff.viewmodel.DataSelectionUiState
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel
import java.io.File
import kotlin.math.roundToInt

/**
 * Rota da tela de Configurações: conecta os dois ViewModels (preferências +
 * seleção de dados) e o estado de "reduzir movimento" do sistema.
 */
@Composable
fun SettingsRoute(
    settingsViewModel: PortSettingsViewModel,
    selectionViewModel: DataSelectionViewModel,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val selectionState by selectionViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val systemReducedMotion = remember {
        val resolver = context.contentResolver
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }

    SettingsScreen(
        settings = settings,
        selectionState = selectionState,
        systemReducedMotion = systemReducedMotion,
        onSettingsChange = settingsViewModel::update,
        onClearSelection = selectionViewModel::clearSavedSelection,
        onBack = onBack
    )
}

@Composable
fun SettingsScreen(
    settings: PortSettings,
    selectionState: DataSelectionUiState,
    systemReducedMotion: Boolean,
    onSettingsChange: ((PortSettings) -> PortSettings) -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
) {
    val config = PortBranding.config
    val accent = config.accent
    val reduceMotion = systemReducedMotion || settings.reduceMotionOverride

    Column(
        Modifier
            .fillMaxSize()
            .background(PortPalette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ---- Cabeçalho: voltar + título, hairline abaixo -------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .portClickable(haptic = true) { onBack() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = config.contentDescBack,
                    tint = PortPalette.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = config.labelSettingsTitle,
                    color = PortPalette.textPrimary,
                    style = PortType.titleScreen
                )
                Text(
                    text = config.labelSettingsSubtitle,
                    color = PortPalette.textTertiary,
                    style = PortType.rowSub
                )
            }
        }

        // ---- Conteúdo rolável ----------------------------------------------
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // ======================== DESEMPENHO =========================
            // Aplicado de verdade: fps_cap e vblank_hz no restuff.toml
            // gerado pelo GameActivity antes do SDL_main. O contador de
            // FPS lê os presents Vulkan reais (JNI nativeGetPresentCount).
            SectionHeader("Performance")

            ToggleRow(
                label = "FPS counter",
                subtitle = "The engine's real frames per second, shown over the game",
                checked = settings.showFpsCounter,
                onChange = { checked ->
                    onSettingsChange { it.copy(showFpsCounter = checked) }
                }
            )
            Hairline()

            SettingLabel("FPS limit")
            Spacer(Modifier.height(10.dp))
            ChoiceChipsRow(
                options = FpsLimitOption.entries.toList(),
                selected = settings.fpsLimit,
                label = { it.label }
            ) { option ->
                onSettingsChange { it.copy(fpsLimit = option) }
            }

            Spacer(Modifier.height(18.dp))
            SliderRow(
                label = "Synthetic vblank",
                valueText = "${settings.vblankHz} Hz",
                value = settings.vblankHz.toFloat(),
                valueRange = 30f..240f,
                steps = 20
            ) { value ->
                onSettingsChange { it.copy(vblankHz = value.roundToInt()) }
            }
            Text(
                text = "The engine's video clock — fine-tuning for " +
                    "high-refresh-rate screens.",
                color = PortPalette.textTertiary,
                style = PortType.rowSub
            )

            SectionGap()

            // ========================= CONTROLES =========================
            SectionHeader("Controls")

            ToggleRow(
                label = "On-screen controls",
                subtitle = "Virtual buttons over the game",
                checked = settings.showOverlayControls,
                onChange = { checked ->
                    onSettingsChange { it.copy(showOverlayControls = checked) }
                }
            )
            Hairline()

            SliderRow(
                label = "Controls opacity",
                valueText = "${(settings.overlayOpacity * 100).roundToInt()}%",
                value = settings.overlayOpacity,
                valueRange = 0.2f..1f,
                steps = 7
            ) { value ->
                onSettingsChange { it.copy(overlayOpacity = value) }
            }
            Hairline()

            ToggleRow(
                label = "Vibration",
                subtitle = "Haptic feedback for the on-screen controls",
                checked = settings.hapticFeedback,
                onChange = { checked ->
                    onSettingsChange { it.copy(hapticFeedback = checked) }
                }
            )
            Hairline()

            SliderRow(
                label = "Controls size",
                valueText = "${(settings.overlayScale * 100).roundToInt()}%",
                value = settings.overlayScale,
                valueRange = 0.7f..1.6f,
                steps = 8
            ) { value ->
                onSettingsChange { it.copy(overlayScale = value) }
            }

            SectionGap()

            // =========================== EFEITOS =========================
            SectionHeader("Home screen")

            ToggleRow(
                label = config.labelToggleMotion,
                subtitle = "Turns off the entrance animations",
                checked = settings.reduceMotionOverride,
                onChange = { checked ->
                    onSettingsChange { it.copy(reduceMotionOverride = checked) }
                }
            )

            SectionGap()

            // ======================== DADOS DO JOGO ======================
            SectionHeader("Game data")

            SelectionSummary(selectionState)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Expected files: " +
                    config.expectedDataFiles.joinToString(", ") +
                    config.acceptableExtensions.joinToString(", ", prefix = " · extensions: "),
                color = PortPalette.textTertiary,
                style = PortType.mono
            )
            Spacer(Modifier.height(14.dp))
            DangerButton(
                label = config.labelClearSelection,
                enabled = selectionState.phase !is DataPhase.Idle,
                onClick = onClearSelection
            )

            SectionGap()

            // ============================ MODS ===========================
            ModsSection()

            SectionGap()

            // ============================ RESHADE ========================
            ReShadeSection(
                enabled = settings.reshadeEnabled,
                onEnabledChange = { checked ->
                    onSettingsChange { it.copy(reshadeEnabled = checked) }
                }
            )

            SectionGap()

            // ================= DRIVERS GRÁFICOS (TURNIP) =================
            DriversSection()

            SectionGap()

            // ================= ENGINE OPTIONS (advanced) =================
            EngineOptionsSection(
                text = settings.engineOverrides,
                onChange = { value ->
                    onSettingsChange { it.copy(engineOverrides = value) }
                }
            )

            SectionGap()

            // ==================== DIAGNÓSTICO ============================
            DiagnosticsSection(
                detailedLogs = settings.detailedLogs,
                onDetailedLogsChange = { checked ->
                    onSettingsChange { it.copy(detailedLogs = checked) }
                }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = config.labelSettingsFooter,
                color = PortPalette.textTertiary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

// ======================================================================
// Primitivos do layout flat
// ======================================================================

/** Cabeçalho de seção: ponto âmbar + rótulo em versalete. */
@Composable
private fun SectionHeader(title: String) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Box(
            Modifier
                .size(4.dp)
                .background(accent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = PortPalette.textSecondary,
            style = PortType.label
        )
    }
}

/** Hairline entre linhas de uma seção. */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PortPalette.hairline)
    )
}

/** Respiro entre seções (mantém a hierarquia sem criar caixas). */
@Composable
private fun SectionGap() {
    Spacer(Modifier.height(30.dp))
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = PortPalette.textSecondary,
        style = PortType.label
    )
}

/** Linha de chips selecionáveis com scroll horizontal (nunca corta). */
@Composable
private fun <T> ChoiceChipsRow(
    options: List<T>,
    selected: T,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    val accent = PortBranding.config.accent
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(PortPalette.radiusSm))
                    .background(if (isSelected) accent else Color.Transparent)
                    .border(
                        1.dp,
                        if (isSelected) Color.Transparent else PortPalette.ghostBorder,
                        RoundedCornerShape(PortPalette.radiusSm)
                    )
                    .portClickable { onSelect(option) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) PortPalette.onAccent else PortPalette.textSecondary,
                    style = PortType.chip
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .portClickable(haptic = true, role = androidx.compose.ui.semantics.Role.Switch) {
                onChange(!checked)
            }
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = PortPalette.textSecondary,
                    style = PortType.rowSub
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = PortPalette.onAccent,
                uncheckedTrackColor = Color(0x20FFFFFF),
                uncheckedThumbColor = PortPalette.textSecondary
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val accent = PortBranding.config.accent
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = accent,
                style = PortType.mono.copy(fontSize = 12.sp)
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color(0x20FFFFFF)
            )
        )
    }
}

/** Resumo do estado atual da seleção de dados (ligado ao ViewModel real). */
@Composable
private fun SelectionSummary(state: DataSelectionUiState) {
    val accent = PortBranding.config.accent
    val (label, color) = when (val phase = state.phase) {
        is DataPhase.Found -> "Ready · ${phase.fileName}" to PortPalette.success
        is DataPhase.Validating -> "Validating…" to accent
        is DataPhase.NotFound -> "The saved selection doesn't contain the expected data." to PortPalette.error
        is DataPhase.PermissionError -> "Read permission was revoked." to PortPalette.error
        is DataPhase.Idle -> "No saved selection." to PortPalette.textSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            color = color,
            style = PortType.rowLabel
        )
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val red = PortPalette.error
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(PortPalette.radiusSm))
            .border(
                1.dp,
                red.copy(alpha = if (enabled) 0.35f else 0.12f),
                RoundedCornerShape(PortPalette.radiusSm)
            )
            .portClickable(enabled = enabled, haptic = true) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = red.copy(alpha = if (enabled) 1f else 0.4f),
            style = PortType.caption
        )
    }
}

// ======================================================================
// Drivers gráficos (padrão AdrenoTools — Turnip/Mesa)
// ======================================================================

private val DRIVER_ZIP_MIME = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed",
)

/**
 * Seção de drivers Vulkan customizados no padrão AdrenoTools: importa .zip
 * (meta.json + .so), lista, seleciona o ativo e remove. O driver ativo é
 * carregado DE VERDADE pelo motor no próximo boot do jogo (dlopen no lugar
 * do libvulkan.so do sistema — ver vulkan_instance.cpp do SDK).
 */
@Composable
private fun DriversSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = PortBranding.config.accent

    var drivers by remember { mutableStateOf(GpuDriverManager.list(context)) }
    var activeId by remember { mutableStateOf(GpuDriverManager.activeId(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // Recarrega a lista ao voltar para esta tela (ex.: driver removido
    // manualmente pelo sistema / outra instância). O reconcile cura o
    // active.txt defasado do Vortek após atualização do app (o
    // nativeLibraryDir muda a cada reinstalação no Android 8+).
    LaunchedEffect(Unit) {
        runCatching { GpuDriverManager.reconcileActiveVortek(context) }
        drivers = GpuDriverManager.list(context)
        activeId = GpuDriverManager.activeId(context)
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        scope.launch {
            try {
                val driver = withContext(Dispatchers.IO) {
                    GpuDriverManager.importFromZip(context, uri)
                }
                withContext(Dispatchers.IO) { GpuDriverManager.setActive(context, driver.id) }
                drivers = GpuDriverManager.list(context)
                activeId = driver.id
                message = "Driver “${driver.name}” v${driver.version} imported and selected — " +
                    "it will be used the next time the game starts."
                isError = false
            } catch (e: GpuDriverManager.DriverImportException) {
                // [17-e1 #5] se o import deu certo e só o setActive falhou,
                // o driver novo ficava invisível até reabrir a tela.
                drivers = GpuDriverManager.list(context)
                message = e.message
                isError = true
            } catch (e: Exception) {
                drivers = GpuDriverManager.list(context)
                message = "Couldn't import the driver — try again."
                isError = true
            }
            importing = false
        }
    }

    Column {
        SectionHeader("Graphics drivers (Turnip / Vortek)")

        Text(
            text = "Import a Vulkan driver (.zip) to improve compatibility and " +
                "performance on Adreno GPUs. For non-Adreno GPUs (Mali and " +
                "others), try the Vortek layer — it runs the game on the " +
                "system driver with compatibility fixes. The driver is used " +
                "the next time the game starts; if it fails, the engine falls " +
                "back to the system driver and the reason shows under " +
                "Diagnostics.",
            color = PortPalette.textSecondary,
            style = PortType.rowSub
        )

        Spacer(Modifier.height(14.dp))

        DriverOptionRow(
            title = "System default",
            subtitle = "The phone maker's Vulkan driver",
            selected = activeId == null,
            onSelect = {
                // [17-e1 #3/#4] I/O fora da main thread + feedback explícito
                // (antes a mensagem de sucesso anterior ficava pendurada).
                scope.launch {
                    withContext(Dispatchers.IO) { GpuDriverManager.clearActive(context) }
                    activeId = null
                    message = "Using the system Vulkan driver."
                    isError = false
                }
            },
            onDelete = null
        )
        Hairline()

        // Vortek — camada de compatibilidade Vulkan embutida (experimental).
        // Créditos: Vortek © brunodev85 (Winlator), LGPL-2.1 — ver README.
        if (GpuDriverManager.isVortekAvailable(context)) {
            DriverOptionRow(
                title = "Vortek (experimental)",
                subtitle = "Compatibility layer over the system driver, " +
                    "meant for Mali and other GPUs · © brunodev85 " +
                    "(Winlator, LGPL-2.1)",
                selected = activeId == GpuDriverManager.VORTEK_DRIVER_ID,
                onSelect = {
                    // [17-e1 #3] seleção fora da main thread, como o import.
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GpuDriverManager.setActiveVortek(context)
                            }
                        }
                            .onSuccess {
                                activeId = GpuDriverManager.VORTEK_DRIVER_ID
                                message = "Vortek selected — a compatibility layer " +
                                    "over the system driver. Recommended on Mali " +
                                    "and other non-Adreno GPUs; on Adreno, Turnip " +
                                    "on its own is usually faster. Applies the " +
                                    "next time the game starts."
                                isError = false
                            }
                            .onFailure { vortekErr ->
                                // [17-e3] message pode ser null em throwable
                                // não-DriverImportException — fallback curado.
                                message = vortekErr.message
                                    ?: "Couldn't turn on Vortek — try again."
                                isError = true
                            }
                    }
                },
                onDelete = null
            )
            Hairline()
        }

        drivers.forEach { driver ->
            DriverOptionRow(
                title = "${driver.name}  ${driver.version}",
                subtitle = listOf(driver.author, driver.vendor, driver.libName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                selected = activeId == driver.id,
                onSelect = {
                    // [17-e1 #3] seleção fora da main thread, como o import.
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GpuDriverManager.setActive(context, driver.id)
                            }
                        }
                            .onSuccess {
                                activeId = driver.id
                                message = "Driver “${driver.name}” will be used the next time the game starts."
                                isError = false
                            }
                            .onFailure { importMsg ->
                                message = importMsg.message
                                    ?: "Couldn't select the driver — try again."
                                isError = true
                            }
                    }
                },
                onDelete = {
                    // [17-e1 #3] deleteRecursively de dezenas de MB não pode
                    // rodar na main thread (risco de ANR).
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            GpuDriverManager.remove(context, driver.id)
                        }
                        drivers = GpuDriverManager.list(context)
                        activeId = GpuDriverManager.activeId(context)
                        message = "Driver removed."
                        isError = false
                    }
                }
            )
            Hairline()
        }

        Spacer(Modifier.height(14.dp))

        // Importação: único botão de destaque da seção (contorno âmbar).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(PortPalette.radiusSm))
                .border(
                    1.dp,
                    accent.copy(alpha = if (importing) 0.2f else 0.55f),
                    RoundedCornerShape(PortPalette.radiusSm)
                )
                .portClickable(enabled = !importing) { zipPicker.launch(DRIVER_ZIP_MIME) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Text(
                        text = "Importing driver…",
                        color = accent.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Import driver (.zip)",
                        color = accent,
                        style = PortType.caption
                    )
                }
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = if (isError) PortPalette.error else PortPalette.success,
                style = PortType.rowSub
            )
        }
    }
}

// ======================================================================
// Mods (.zip/.rar/.7z installed into the game folder, originals kept as .bk)
// ======================================================================

/**
 * Installs .zip/.rar/.7z mods into the game folder. A file the mod replaces is kept as
 * `<name>.bk` first; character files (.zpak) are swapped inside the game's AFS
 * archives. Removing a mod restores what it changed.
 */
@Composable
private fun ModsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = PortBranding.config.accent

    var manager by remember { mutableStateOf<ModManager?>(null) }
    var installed by remember { mutableStateOf<List<ModManager.InstalledMod>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val m = withContext(Dispatchers.IO) { Mods.manager(context) }
        manager = m
        val restored = withContext(Dispatchers.IO) { runCatching { m?.repair() }.getOrNull() ?: emptyList() }
        if (restored.isNotEmpty()) {
            message = "Put back from .bk: " + restored.joinToString(", ") { it.substringAfterLast('/') }
            isError = false
        }
        installed = withContext(Dispatchers.IO) { runCatching { m?.list() }.getOrNull() ?: emptyList() }
    }

    val install = remember { ModInstallState() }
    VersionPickerDialog(install)
    // Mods installed from the GameBanana browser show up when the person comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                val m = manager ?: return@launch
                installed = withContext(Dispatchers.IO) { runCatching { m.list() }.getOrDefault(installed) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(install.busy, install.message) {
        busy = install.busy
        if (install.message != null) {
            message = install.message
            isError = install.isError
        }
    }
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val m = manager
        if (uri == null || m == null) return@rememberLauncherForActivityResult
        val name = Mods.displayName(context, uri)
        busy = "Reading $name…"
        message = null
        scope.launch {
            // Copied first: .rar/.7z need a real file, and the copy keeps the picker's grant short.
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.cacheDir, "mod-import/${name.replace('/', '_')}")
                    file.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: throw ModManager.ModException("Couldn't open $name.")
                    file
                }
            }
            val file = copy.getOrElse {
                message = it.message ?: "Couldn't read $name."
                isError = true
                busy = null
                return@launch
            }
            try {
                install.install(m, file, name)
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
            installed = withContext(Dispatchers.IO) { m.list() }
        }
    }

    Column {
        SectionHeader("Mods")

        Text(
            text = "Install a mod from a .zip, .rar or .7z, or get one from GameBanana. Files it replaces in your game folder are kept " +
                "as .bk backups, and character files (.zpak) are swapped inside the game's " +
                ".afs packs. Remove a mod to put the originals back. PS3/PC character " +
                "files are converted to the Xbox 360 format as they install.",
            color = PortPalette.textSecondary,
            style = PortType.rowSub
        )

        Spacer(Modifier.height(14.dp))

        val m = manager
        if (m == null) {
            Text(
                text = "Mods need the extracted game folder. They can't be installed while " +
                    "the game starts straight from an ISO.",
                color = PortPalette.textTertiary,
                style = PortType.rowSub
            )
        } else {
            installed.forEach { mod ->
                DriverOptionRow(
                    title = mod.name,
                    subtitle = mod.changes.map { it.entry ?: it.target.substringAfterLast('/') }
                        .distinct().take(4).joinToString(" · ") +
                        if (mod.changes.size > 4) " · +${mod.changes.size - 4} more" else "",
                    selected = true,
                    onSelect = {},
                    onDelete = {
                        if (busy != null) return@DriverOptionRow
                        busy = "Removing ${mod.name}…"
                        message = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    m.uninstall(mod.id) { line -> scope.launch { busy = line } }
                                }
                                message = "Removed “${mod.name}” and restored the original files."
                                isError = false
                            } catch (e: Exception) {
                                message = e.message ?: "Couldn't remove the mod."
                                isError = true
                            }
                            installed = withContext(Dispatchers.IO) {
                                runCatching { m.list() }.getOrDefault(emptyList())
                            }
                            busy = null
                        }
                    }
                )
                Hairline()
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(PortPalette.radiusSm))
                    .border(
                        1.dp,
                        accent.copy(alpha = if (busy != null) 0.2f else 0.55f),
                        RoundedCornerShape(PortPalette.radiusSm)
                    )
                    .portClickable(enabled = busy == null) { zipPicker.launch(MOD_ARCHIVE_MIME) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val status = busy
                    if (status != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = accent
                        )
                        Text(
                            text = status,
                            color = accent.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Install mod (.zip, .rar, .7z)",
                            color = accent,
                            style = PortType.caption
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(PortPalette.radiusSm))
                    .border(1.dp, PortPalette.ghostBorder, RoundedCornerShape(PortPalette.radiusSm))
                    .portClickable(enabled = busy == null) {
                        context.startActivity(android.content.Intent(context, ModBrowserActivity::class.java))
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Get mods from GameBanana", color = PortPalette.textPrimary, style = PortType.caption)
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = if (isError) PortPalette.error else PortPalette.success,
                style = PortType.rowSub
            )
        }
    }
}

/**
 * ReShade: the effects the engine runs on the game image (see
 * reshade_runtime.cpp). Shaders live in Internal storage/Raging Blast 2/reshade
 * like a PC install; this section picks the techniques and their order and
 * writes them to the preset. Effect values come from the preset's [File.fx]
 * sections, so a preset made on PC can be imported as it is.
 */
@Composable
private fun ReShadeSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = PortBranding.config.accent
    val root = remember { ReShadeFiles.folder() }
    val preset = remember { ReShadeFiles.preset(root) }

    var available by remember { mutableStateOf<List<ReShadeFiles.Technique>>(emptyList()) }
    var active by remember { mutableStateOf<List<String>>(emptyList()) }
    var log by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var reachable by remember { mutableStateOf(true) }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            reachable = runCatching { ReShadeFiles.ensure(root) }.getOrDefault(false)
            available = runCatching { ReShadeFiles.scan(root) }.getOrDefault(emptyList())
            active = runCatching { ReShadeFiles.readTechniques(preset) }.getOrDefault(emptyList())
            log = ReShadeFiles.lastLog(root)
        }
    }
    fun save(list: List<String>) {
        active = list
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ReShadeFiles.writeTechniques(preset, list) }
            }.onFailure {
                message = "Couldn't write the preset: ${it.message}"
                isError = true
            }
        }
    }
    fun run(label: String, work: suspend () -> String) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            try {
                message = work()
                isError = false
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
                isError = true
            }
            refresh()
            busy = null
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // The log changes after a run, so read it again on return from the game.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Installing shaders…") {
            val count = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { ReShadeFiles.installPack(it, root) }
                    ?: throw java.io.IOException("Couldn't open the file.")
            }
            if (count == 0) throw java.io.IOException("No Shaders or Textures folder in that zip.")
            "Added $count shader files."
        }
    }
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Importing preset…") {
            withContext(Dispatchers.IO) {
                if (preset.isFile) preset.copyTo(File(root, ReShadeFiles.PRESET_NAME + ".bk"), overwrite = true)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    preset.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("Couldn't open the file.")
            }
            "Preset imported. The old one is kept as ${ReShadeFiles.PRESET_NAME}.bk."
        }
    }

    Column {
        SectionHeader("ReShade effects")

        Text(
            text = "Runs ReShade .fx effects (sharpening, anti-aliasing, color grading…) on the " +
                "game image. Pick effects below, in the order they run. Their settings come " +
                "from the preset, so a ReShade preset made on PC works too. Effects that need " +
                "the depth buffer don't work here, and heavy ones cost frames.",
            color = PortPalette.textSecondary,
            style = PortType.rowSub
        )
        Spacer(Modifier.height(6.dp))

        ToggleRow(
            label = "ReShade",
            subtitle = if (active.isEmpty()) "Pick at least one effect below" else
                "${active.size} effect${if (active.size == 1) "" else "s"} on",
            checked = enabled,
            onChange = { checked ->
                // Turning it back on clears the freeze guard's marker.
                if (checked) runCatching { ReShadeFiles.startMarker(root).delete() }
                onEnabledChange(checked)
            }
        )
        Hairline()
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Folder: " + root.absolutePath,
            color = PortPalette.textTertiary,
            style = PortType.mono
        )
        if (!reachable) {
            Text(
                text = "The folder can't be created. Grant \"All files access\" to the app.",
                color = PortPalette.error,
                style = PortType.rowSub
            )
        }
        Spacer(Modifier.height(12.dp))

        // Effects that run, in order.
        if (active.isNotEmpty()) {
            SettingLabel("Running, in order")
            Spacer(Modifier.height(4.dp))
            active.forEachIndexed { index, id ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(
                        text = "${index + 1}",
                        color = accent,
                        style = PortType.mono.copy(fontSize = 12.sp),
                        modifier = Modifier.width(24.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(text = id.substringBefore('@'), color = PortPalette.textPrimary, style = PortType.rowLabel)
                        if ('@' in id) {
                            Text(text = id.substringAfter('@'), color = PortPalette.textSecondary, style = PortType.rowSub)
                        }
                    }
                    if (index > 0) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(44.dp).portClickable {
                                save(active.toMutableList().apply { add(index - 1, removeAt(index)) })
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Run earlier",
                                tint = PortPalette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).portClickable { save(active - id) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Turn off",
                            tint = PortPalette.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Hairline()
            }
            Spacer(Modifier.height(14.dp))
        }

        // Effects that can be added.
        val activeNames = active.toSet()
        val addable = available.filter { it.id !in activeNames && it.name !in activeNames }
        if (available.isEmpty()) {
            Text(
                text = "No effects yet. Download the standard shaders, or put .fx files in Shaders/.",
                color = PortPalette.textTertiary,
                style = PortType.rowSub
            )
        } else if (addable.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).portClickable { showAll = !showAll }
            ) {
                Text(
                    text = (if (showAll) "Hide" else "Add an effect") + " (${addable.size} available)",
                    color = accent,
                    style = PortType.caption
                )
            }
            if (showAll) {
                addable.forEach { technique ->
                    DriverOptionRow(
                        title = technique.name,
                        subtitle = technique.file,
                        selected = false,
                        onSelect = { save(active + technique.id) },
                        onDelete = null
                    )
                    Hairline()
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val buttons = listOf(
            "Download standard shaders" to {
                run("Downloading shaders…") {
                    val total = ReShadeFiles.STANDARD_PACKS.sumOf { pack ->
                        busy = "Downloading ${pack.name}…"
                        withContext(Dispatchers.IO) { ReShadeFiles.downloadPack(pack, root) }
                    }
                    "Installed $total files from the ReShade and SweetFX shader packs."
                }
            },
            "Add shader pack (.zip)" to { packPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            "Import preset (.ini)" to { presetPicker.launch(arrayOf("*/*")) },
        )
        buttons.forEachIndexed { i, (label, action) ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(PortPalette.radiusSm))
                    .border(
                        1.dp,
                        if (i == 0) accent.copy(alpha = if (busy != null) 0.2f else 0.55f) else PortPalette.ghostBorder,
                        RoundedCornerShape(PortPalette.radiusSm)
                    )
                    .portClickable(enabled = busy == null) { action() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                val status = busy
                if (i == 0 && status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                        Text(text = status, color = accent.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        text = label,
                        color = if (i == 0) accent else PortPalette.textPrimary,
                        style = PortType.caption
                    )
                }
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(text = msg, color = if (isError) PortPalette.error else PortPalette.success, style = PortType.rowSub)
        }

        log?.let { text ->
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).portClickable { showLog = !showLog }
            ) {
                val failed = text.lineSequence().any { it.startsWith("error") }
                Text(
                    text = (if (showLog) "Hide" else "Show") + " last run's log" +
                        if (failed) " (has errors)" else "",
                    color = if (failed) PortPalette.error else PortPalette.textSecondary,
                    style = PortType.caption
                )
            }
            if (showLog) {
                Text(
                    text = text,
                    color = PortPalette.textTertiary,
                    style = PortType.mono.copy(fontSize = 10.5.sp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

/** Linha (indicador + título + subtítulo + lixeira) de um driver da lista. */
@Composable
private fun DriverOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .portClickable { onSelect() }
            .padding(vertical = 8.dp)
    ) {
        // Indicador: ponto cheio no accent (selecionado) ou anel hairline.
        Box(
            modifier = Modifier
                .size(18.dp)
                .let {
                    if (selected) {
                        it.background(accent, CircleShape)
                    } else {
                        it.border(1.5.dp, PortPalette.textTertiary, CircleShape)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (!selected) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(Color.Transparent, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = PortPalette.textSecondary,
                    style = PortType.rowSub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        onDelete?.let { del ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .portClickable { del() }
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Remove driver",
                    tint = PortPalette.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ======================================================================
// Diagnóstico (log detalhado persistido + desfecho do último boot)
// ======================================================================

/**
 * Seção de diagnóstico: toggle do log detalhado (debug), local do log da
 * última sessão (storage público com fallback privado) e desfecho do
 * carregamento do driver Vulkan no último boot (lido de
 * files/drivers/last_boot.txt, escrito por vulkan_instance.cpp).
 */
@Composable
private fun DiagnosticsSection(
    detailedLogs: Boolean,
    onDetailedLogsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    Column {
        SectionHeader("Diagnostics")

        ToggleRow(
            label = "Detailed log (debug)",
            subtitle = "Writes the engine's internal events to the session log",
            checked = detailedLogs,
            onChange = onDetailedLogsChange
        )
        Hairline()

        // Local do log da última sessão.
        val logLocation = remember { GpuDriverManager.lastLogLocation(context) }
        Text(
            text = when {
                logLocation == null ->
                    "Log: not started yet."
                logLocation == "privado" ->
                    "Log: the app's PRIVATE storage (grant \"All files access\" " +
                        "to use /storage/emulated/0/Raging Blast 2/logs)."
                else -> "Log: " + logLocation.removePrefix("publico:")
            },
            color = PortPalette.textTertiary,
            style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
        )

        Spacer(Modifier.height(8.dp))

        // Desfecho do driver no último boot.
        val outcome = remember { GpuDriverManager.lastBootOutcome(context) }
        if (outcome != null) {
            val label: String
            val labelColor: Color
            when (outcome.status) {
                "custom_ok" -> {
                    label = "Last start: CUSTOM driver loaded (AdrenoTools)"
                    labelColor = PortPalette.success
                }
                "custom_failed" -> {
                    label = "Last start: custom driver FAILED — used the system driver"
                    labelColor = PortPalette.error
                }
                else -> {
                    label = "Last start: system driver"
                    labelColor = PortPalette.textTertiary
                }
            }
            Text(
                text = label,
                color = labelColor,
                style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
            )
            if (outcome.status == "custom_failed" && outcome.error != "-") {
                Text(
                    text = "  reason: " + outcome.error.take(120),
                    color = PortPalette.textTertiary,
                    style = PortType.mono.copy(fontSize = 10.5.sp)
                )
            }
        } else {
            Text(
                text = "Driver: run the game once to see which driver it started with.",
                color = PortPalette.textTertiary,
                style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Native crashes write a backtrace to the session log and to " +
                "files/last_crash.txt (only reachable via adb/backup).",
            color = PortPalette.textTertiary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
    }
}

/**
 * Advanced engine options: free-form `key = value` lines merged into the
 * restuff.toml on the next boot (see [EngineOverrides]). Lets a graphics
 * problem be narrowed down by flipping engine settings without a new APK.
 */
@Composable
private fun EngineOptionsSection(
    text: String,
    onChange: (String) -> Unit,
) {
    val accent = PortBranding.config.accent
    // Local state keeps the cursor stable while every edit is persisted.
    var value by remember { mutableStateOf(text) }
    val lines = remember(value) { EngineOverrides.parse(value) }
    val ignored = lines.filterNot { it.valid }

    SectionHeader("Engine options (advanced)")
    Text(
        text = "One option per line, for example vulkan_sparse_shared_memory = true. " +
            "These replace the launcher's defaults the next time the game starts. " +
            "Leave empty for the defaults.",
        color = PortPalette.textSecondary,
        style = PortType.rowSub
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        textStyle = PortType.mono.copy(color = PortPalette.textPrimary, fontSize = 13.sp),
        placeholder = {
            Text(
                text = "readback_resolve = fast",
                style = PortType.mono.copy(fontSize = 13.sp),
                color = PortPalette.textTertiary
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PortPalette.textPrimary,
            unfocusedTextColor = PortPalette.textPrimary,
            focusedBorderColor = accent,
            unfocusedBorderColor = PortPalette.ghostBorder,
            cursorColor = accent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        )
    )
    Spacer(Modifier.height(8.dp))
    val active = lines.size - ignored.size
    Text(
        text = if (lines.isEmpty()) "Using the launcher defaults."
        else "$active option${if (active == 1) "" else "s"} will be applied on the next start.",
        color = PortPalette.textTertiary,
        style = PortType.mono
    )
    ignored.forEach { line ->
        Text(
            text = "Ignored (not key = value): ${line.raw}",
            color = PortPalette.error,
            style = PortType.mono
        )
    }
}

