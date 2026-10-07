package com.izzy2lost.psx2

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.izzy2lost.psx2.ui.theme.PSX2Theme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Renderer ids used by NativeApp.renderGpuAsync / getCurrentRenderer. */
private const val RENDERER_AUTO = -1
private const val RENDERER_OPENGL = 12
private const val RENDERER_SOFTWARE = 13
private const val RENDERER_VULKAN = 14
private val RendererOptions = listOf(
    RENDERER_AUTO to "AT", RENDERER_VULKAN to "VK", RENDERER_OPENGL to "GL", RENDERER_SOFTWARE to "SW",
)
private val OrientationOptions = listOf(
    MainActivity.ORIENTATION_AUTO to "Auto",
    MainActivity.ORIENTATION_LANDSCAPE to "Landscape",
    MainActivity.ORIENTATION_PORTRAIT to "Portrait",
)

/** Per-screen differences between the main-screen and game-library drawers. */
class SettingsDrawerConfig(
    val title: String,
    /** Closes the DrawerLayout that hosts this drawer. */
    val closeDrawer: Runnable,
    /** The "Games" button. */
    val onGames: Runnable,
    /** Shows the Texture Packs button when non-null (game library only). */
    val onTexturePacks: Runnable?,
)

/**
 * State and behavior of the left settings drawer, shared by MainActivity and the game
 * library. Replaces the `drawer_header_settings.xml` NavigationView header and the two
 * copies of its wiring (MainActivity.setupDrawerSettings / GamesCoverDialogFragment
 * .setupDialogDrawerSettings). Each change persists to app_prefs and is applied natively,
 * exactly as the old listeners did.
 */
class SettingsDrawerController(private val activity: MainActivity, val config: SettingsDrawerConfig) {
    private val prefs: SharedPreferences = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var renderer by mutableIntStateOf(RENDERER_AUTO); private set
    var orientation by mutableIntStateOf(MainActivity.ORIENTATION_AUTO); private set
    var biosStatus by mutableStateOf(""); private set
    var bootBiosOnStart by mutableStateOf(false); private set
    var aspectRatio by mutableIntStateOf(1); private set
    var edgeCropIndex by mutableIntStateOf(0); private set
    var audioOutput by mutableIntStateOf(0); private set
    var vsync by mutableStateOf(false); private set
    var upscale by mutableFloatStateOf(1f); private set
    var blendingAccuracy by mutableIntStateOf(1); private set
    var widescreenPatches by mutableStateOf(true); private set
    var noInterlacingPatches by mutableStateOf(true); private set
    var loadTextures by mutableStateOf(false); private set
    var asyncTextures by mutableStateOf(true); private set
    var precacheTextures by mutableStateOf(false); private set
    var devHud by mutableStateOf(false); private set
    var touchRightStick by mutableStateOf(false); private set

    init {
        refresh()
    }

    /** Re-reads every value; call when the drawer opens or settings change elsewhere. */
    fun refresh() {
        renderer = try {
            when (NativeApp.getCurrentRenderer()) {
                RENDERER_VULKAN, RENDERER_OPENGL, RENDERER_SOFTWARE -> NativeApp.getCurrentRenderer()
                else -> RENDERER_AUTO
            }
        } catch (_: Throwable) {
            RENDERER_AUTO
        }
        orientation = prefs.getInt("orientation_lock", MainActivity.ORIENTATION_AUTO).let {
            if (it == MainActivity.ORIENTATION_LANDSCAPE || it == MainActivity.ORIENTATION_PORTRAIT) it
            else MainActivity.ORIENTATION_AUTO
        }
        biosStatus = BiosVerifier.describeVerifiedRegions(activity)
        bootBiosOnStart = prefs.getBoolean(MainActivity.PREF_BOOT_BIOS_ON_START, false)
        aspectRatio = prefs.getInt("aspect_ratio", 1)
        edgeCropIndex = MainActivity.edgeCropPixelsToIndex(prefs.getInt("edge_crop", MainActivity.DEFAULT_EDGE_CROP))
        audioOutput = AudioOutputPreference.getMode(activity)
        vsync = prefs.getBoolean("vsync_enabled", false)
        upscale = prefs.getFloat("upscale_multiplier", 1f)
        blendingAccuracy = prefs.getInt("blending_accuracy", 1)
        widescreenPatches = prefs.getBoolean("widescreen_patches", true)
        noInterlacingPatches = prefs.getBoolean("no_interlacing_patches", true)
        loadTextures = prefs.getBoolean("load_textures", false)
        asyncTextures = prefs.getBoolean("async_texture_loading", true)
        precacheTextures = prefs.getBoolean("precache_textures", false)
        devHud = prefs.getBoolean("hud_visible", false)
        touchRightStick = prefs.getBoolean(MainActivity.PREF_TOUCH_RIGHT_STICK, false)
    }

    // --- Settings -------------------------------------------------------------------------

    fun selectRenderer(value: Int) {
        renderer = value
        if (prefs.getInt("renderer", RENDERER_AUTO) == value) return
        prefs.edit().putInt("renderer", value).apply()
        runCatching { NativeApp.renderGpuAsync(value) }
    }

    fun selectOrientation(value: Int) {
        orientation = value
        activity.setOrientationPreference(value)
    }

    fun changeBootBiosOnStart(enabled: Boolean) {
        bootBiosOnStart = enabled
        activity.setBootBiosOnStart(enabled)
    }

    fun selectAspectRatio(index: Int) {
        aspectRatio = index
        if (index == prefs.getInt("aspect_ratio", 1)) return
        prefs.edit().putInt("aspect_ratio", index).apply()
        NativeApp.setAspectRatioAsync(index)
    }

    fun selectEdgeCrop(index: Int) {
        edgeCropIndex = index
        val pixels = MainActivity.edgeCropIndexToPixels(index)
        if (pixels == prefs.getInt("edge_crop", MainActivity.DEFAULT_EDGE_CROP)) return
        prefs.edit().putInt("edge_crop", pixels).apply()
        NativeApp.setEdgeCropAsync(pixels)
    }

    fun selectAudioOutput(index: Int) {
        audioOutput = index
        if (index == AudioOutputPreference.getMode(activity)) return
        AudioOutputPreference.setMode(activity, index)
        AudioOutputPreference.apply(activity)
    }

    fun selectUpscale(index: Int) {
        val scale = (index + 1).coerceIn(1, 8).toFloat()
        upscale = scale
        if (abs(prefs.getFloat("upscale_multiplier", 1f) - scale) < 0.001f) return
        prefs.edit().putFloat("upscale_multiplier", scale).apply()
        NativeApp.renderUpscalemultiplierAsync(scale)
    }

    fun selectBlendingAccuracy(index: Int) {
        blendingAccuracy = index
        if (index == prefs.getInt("blending_accuracy", 1)) return
        prefs.edit().putInt("blending_accuracy", index).apply()
        NativeApp.setBlendingAccuracyAsync(index)
    }

    fun changeVsync(on: Boolean) { vsync = on; applySwitch("vsync_enabled", false, on, NativeApp::setVsyncEnabledAsync) }
    fun changeWidescreen(on: Boolean) { widescreenPatches = on; applySwitch("widescreen_patches", true, on, NativeApp::setWidescreenPatchesAsync) }
    fun changeNoInterlacing(on: Boolean) { noInterlacingPatches = on; applySwitch("no_interlacing_patches", true, on, NativeApp::setNoInterlacingPatchesAsync) }
    fun changeLoadTextures(on: Boolean) { loadTextures = on; applySwitch("load_textures", false, on, NativeApp::setLoadTexturesAsync) }
    fun changeAsyncTextures(on: Boolean) { asyncTextures = on; applySwitch("async_texture_loading", true, on, NativeApp::setAsyncTextureLoadingAsync) }
    fun changePrecacheTextures(on: Boolean) { precacheTextures = on; applySwitch("precache_textures", false, on, NativeApp::setPrecacheTextureReplacementsAsync) }
    fun changeDevHud(on: Boolean) { devHud = on; applySwitch("hud_visible", false, on, NativeApp::setHudVisibleAsync) }

    fun changeTouchRightStick(on: Boolean) {
        touchRightStick = on
        activity.setTouchRightStickEnabled(on)
    }

    private fun applySwitch(key: String, default: Boolean, value: Boolean, apply: (Boolean) -> Unit) {
        if (value == prefs.getBoolean(key, default)) return
        prefs.edit().putBoolean(key, value).apply()
        apply(value)
    }

    // --- Actions --------------------------------------------------------------------------

    private val fragments get() = activity.supportFragmentManager

    fun powerOff() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Power Off")
            .setMessage("Quit the app?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Quit") { _, _ ->
                runCatching { NativeApp.shutdown() }
                activity.finishAffinity()
                activity.finishAndRemoveTask()
                System.exit(0)
            }
            .show()
    }

    fun reboot() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Reboot")
            .setMessage("Restart the current game?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reboot") { _, _ -> activity.rebootEmu() }
            .show()
    }

    fun openCustomDriver() = show { CustomDriverDialogFragment().show(fragments, "custom_driver") }
    fun openGames() = show { config.onGames.run() }
    fun openTexturePacks() = show { config.onTexturePacks?.run() }
    fun openGameState() = show { SavesDialogFragment().show(fragments, "saves_dialog") }
    fun openMemoryCards() = show { MemoryCardManagerDialogFragment().show(fragments, "memcard_manager_dialog") }
    fun openControllerTest() = show { ControllerTestDialogFragment.newInstance().show(fragments, "controller_test") }
    fun openAbout() = show { activity.showAboutDialog() }
    fun openAchievements() = show { AchievementsDialogFragment.newInstance().show(fragments, "achievements_dialog") }

    fun openBios() {
        runCatching { config.closeDrawer.run() }
        activity.window.decorView.post { activity.showBiosPrompt() }
    }

    private inline fun show(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("SettingsDrawer", "Drawer action failed", t)
        }
    }

    companion object {
        /** Java entry point: the themed ComposeView placed in the DrawerLayout's start slot. */
        @JvmStatic
        fun createView(context: Context, controller: SettingsDrawerController): ComposeView =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    PSX2Theme { SettingsDrawerSheet(controller) }
                }
            }
    }
}

@Composable
private fun SettingsDrawerSheet(c: SettingsDrawerController) {
    Surface(
        modifier = Modifier.width(320.dp).fillMaxHeight(),
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 16.dp),
        ) {
            // Match the old XML: unstyled TextViews/switch labels were 14sp.
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                SettingsDrawerContent(c)
            }
        }
    }
}

/** Mirrors the section order of the old drawer_header_settings.xml. */
@Composable
private fun ColumnScope.SettingsDrawerContent(c: SettingsDrawerController) {
    val accent = colorResource(R.color.brand_primary)
    val note = MaterialTheme.colorScheme.tertiary

    Text(
        c.config.title,
        color = accent,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        FloatingActionButton(
            onClick = c::reboot,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) { Icon(painterResource(R.drawable.ic_reboot), contentDescription = "Reboot") }
        FloatingActionButton(
            onClick = c::powerOff,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) { Icon(painterResource(R.drawable.ic_power), contentDescription = "Power") }
    }

    SectionTitle("Renderer")
    ChoiceRow(RendererOptions, c.renderer, c::selectRenderer)
    DrawerButton("Custom GPU Driver", onClick = c::openCustomDriver)

    SectionTitle("Orientation")
    ChoiceRow(OrientationOptions, c.orientation, c::selectOrientation)

    Spacer(Modifier.padding(top = 4.dp))
    DrawerButton("Games", R.drawable.stadia_controller_24px, onClick = c::openGames)
    if (c.config.onTexturePacks != null) {
        DrawerButton("Texture Packs", R.drawable.image_24px, onClick = c::openTexturePacks)
    }
    DrawerButton("Game State", R.drawable.save_24px, onClick = c::openGameState)
    DrawerButton("Memory Cards", R.drawable.sd_card_24px, onClick = c::openMemoryCards)

    SectionTitle("BIOS Files", topPadding = 12.dp)
    Text(c.biosStatus, color = note, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
    DrawerButton("Import / Manage BIOS", R.drawable.memory_24px, onClick = c::openBios)
    SwitchRow("Boot PS2 menu on startup", c.bootBiosOnStart, c::changeBootBiosOnStart)
    Note("Off: the app opens straight to your games. Games never need the PS2 menu to run first.")

    SectionTitle("Aspect Ratio")
    Dropdown(R.array.aspect_ratio_entries, c.aspectRatio, c::selectAspectRatio)
    SectionTitle("Edge Cropping")
    Note("Hides junk pixels at the left/right edges that a CRT's overscan used to cover.")
    Dropdown(R.array.edge_crop_entries, c.edgeCropIndex, c::selectEdgeCrop)
    SectionTitle("Audio Output")
    Note("Some USB controllers act as a sound card and steal audio to their own headphone jack. Pick Phone Speaker to keep game sound on the phone.")
    Dropdown(R.array.audio_output_entries, c.audioOutput, c::selectAudioOutput)

    SwitchRow("Vertical Sync (VSync)", c.vsync, c::changeVsync)
    SectionTitle("Resolution Scale")
    Dropdown(R.array.scale_entries, (c.upscale.roundToInt() - 1).coerceAtLeast(0), c::selectUpscale)
    SectionTitle("Blending Accuracy")
    Dropdown(R.array.blending_accuracy_entries, c.blendingAccuracy, c::selectBlendingAccuracy)

    SectionTitle("Patches")
    SwitchRow("Widescreen Patches", c.widescreenPatches, c::changeWidescreen)
    SwitchRow("No Interlacing", c.noInterlacingPatches, c::changeNoInterlacing)

    SectionTitle("Textures")
    SwitchRow("Load Textures", c.loadTextures, c::changeLoadTextures)
    SwitchRow("Async Textures", c.asyncTextures, c::changeAsyncTextures)
    SwitchRow("Precache Texture Replacements", c.precacheTextures, c::changePrecacheTextures)

    SectionTitle("Developer")
    SwitchRow("Dev: HUD overlay", c.devHud, c::changeDevHud)

    SectionTitle("Controller")
    SwitchRow("Touch: Right Stick Joystick", c.touchRightStick, c::changeTouchRightStick)
    DrawerButton("Test Controller Input", onClick = c::openControllerTest)

    SectionTitle("About")
    DrawerButton("About PSX2", R.drawable.info_24px, onClick = c::openAbout)
    SectionTitle("RetroAchievements")
    DrawerButton("🏆 Achievements", onClick = c::openAchievements)
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text,
        color = colorResource(R.color.brand_primary),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
}

/** Outlined, full-width action button (Widget.Material3Expressive.Button.OutlinedButton). */
@Composable
private fun DrawerButton(text: String, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        border = BorderStroke(1.dp, colorResource(R.color.brand_outline)),
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = colorResource(R.color.brand_primary),
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
        }
        Text(text, color = Color.White)
    }
}

/** Single-selection segmented row (MaterialButtonToggleGroup with selectionRequired). */
@Composable
private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = colorResource(R.color.brand_primary),
                    inactiveContentColor = colorResource(R.color.brand_primary),
                ),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Replaces the Spinners: current entry with a drop-down arrow, opening a menu of entries. */
@Composable
private fun Dropdown(@ArrayRes entries: Int, selected: Int, onSelect: (Int) -> Unit) {
    val items = stringArrayResource(entries)
    val index = selected.takeIf { it in items.indices } ?: 1.coerceAtMost(items.lastIndex)
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(items[index], modifier = Modifier.weight(1f), fontSize = 16.sp) // Spinner item text size
            Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        if (i != index) onSelect(i)
                    },
                )
            }
        }
    }
}

@Preview(name = "Settings drawer", heightDp = 1400)
@Composable
private fun SettingsDrawerPreview() {
    // Controls only; the controller needs a running MainActivity.
    PSX2Theme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.width(320.dp).padding(16.dp)) {
                SectionTitle("Renderer")
                ChoiceRow(RendererOptions, RENDERER_AUTO) {}
                DrawerButton("Custom GPU Driver") {}
                SectionTitle("Orientation")
                ChoiceRow(OrientationOptions, MainActivity.ORIENTATION_AUTO) {}
                DrawerButton("Games", R.drawable.stadia_controller_24px) {}
                SwitchRow("Vertical Sync (VSync)", true) {}
                SectionTitle("Aspect Ratio")
                Dropdown(R.array.aspect_ratio_entries, 1) {}
            }
        }
    }
}
