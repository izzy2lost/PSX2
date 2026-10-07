package com.izzy2lost.psx2

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.izzy2lost.psx2.SavesDialogFragment.SaveSlot
import com.izzy2lost.psx2.ui.theme.PSX2CompactButtonPadding
import com.izzy2lost.psx2.ui.theme.PSX2ElevatedTransparentButton
import com.izzy2lost.psx2.ui.theme.PSX2Theme

/** Callback for a save or load action on a numbered slot. Java-friendly (SAM). */
fun interface SlotAction {
    fun onSlot(slot: Int)
}

/**
 * Body of the "Save States" dialog. Replaces `dialog_saves.xml`:
 * LinearLayout(padding 16dp) > TextView + RecyclerView  ->  Column > Text + LazyColumn.
 * The dialog title and Cancel button stay in the MaterialAlertDialog shell.
 */
@Composable
fun SaveStatesContent(
    slots: List<SaveSlot>,
    onSave: SlotAction,
    onLoad: SlotAction,
    modifier: Modifier = Modifier,
) {
    var previewSlot by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Choose a save slot:",
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyColumn {
            items(slots, key = { it.slot }) { slot ->
                SaveSlotRow(
                    slot = slot,
                    onSave = { onSave.onSlot(slot.slot) },
                    onLoad = { onLoad.onSlot(slot.slot) },
                    onScreenshotClick = { previewSlot = slot.slot },
                )
            }
        }
    }

    val preview = previewSlot?.let { id -> slots.firstOrNull { it.slot == id } }
    val previewBytes = preview?.screenshot
    if (preview != null && previewBytes != null && previewBytes.isNotEmpty()) {
        ScreenshotPreviewDialog(
            title = preview.title,
            screenshot = previewBytes,
            onDismiss = { previewSlot = null },
        )
    }
}

/**
 * One slot row. Replaces `item_save_slot.xml`:
 * horizontal LinearLayout(padding 12dp, center_vertical) >
 *   ImageView(60x40, gone when empty) + weighted text column + Save/Load buttons.
 */
@Composable
fun SaveSlotRow(
    slot: SaveSlot,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onScreenshotClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail = rememberScreenshot(slot.screenshot)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Screenshot of ${slot.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(width = 60.dp, height = 40.dp)
                    .clickable(onClick = onScreenshotClick)
                    .padding(2.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slot.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            if (slot.timestamp.isNotEmpty()) {
                Text(
                    text = slot.timestamp,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Row(
            modifier = Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SlotActionButton(text = "SAVE", onClick = onSave)
            // Load is disabled (and faded) when the slot has no data.
            SlotActionButton(
                text = "LOAD",
                onClick = onLoad,
                enabled = !slot.isEmpty,
                modifier = Modifier.alpha(if (slot.isEmpty) 0.5f else 1f),
            )
        }
    }
}

@Composable
private fun SlotActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PSX2ElevatedTransparentButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PSX2CompactButtonPadding,
        modifier = modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 60.dp),
    ) {
        Text(text = text, fontSize = 12.sp)
    }
}

/**
 * Enlarged screenshot. Replaces `dialog_screenshot_preview.xml` and its
 * MaterialAlertDialogBuilder (centered bold title, scrollable fitCenter image, Close).
 */
@Composable
fun ScreenshotPreviewDialog(
    title: String,
    screenshot: ByteArray,
    onDismiss: () -> Unit,
) {
    val bitmap = rememberScreenshot(screenshot)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Screenshot of $title",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.13f))
                        .padding(4.dp),
                )
            }
        },
    )
}

/** Decodes a save-state PNG/JPEG blob once per byte array. */
@Composable
private fun rememberScreenshot(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    if (bytes == null || bytes.isEmpty()) null
    else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

/** Java entry point: builds the themed ComposeView hosted inside the dialog. */
object SaveStatesComposeView {
    @JvmStatic
    fun create(
        context: Context,
        slots: List<SaveSlot>,
        onSave: SlotAction,
        onLoad: SlotAction,
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PSX2Theme {
                // Transparent surface: the dialog window draws the background, this only
                // supplies onSurface as the default text/icon color.
                Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                    SaveStatesContent(slots = slots, onSave = onSave, onLoad = onLoad)
                }
            }
        }
    }
}

private fun previewSlots(): List<SaveSlot> = (1..4).map { i ->
    SaveSlot(i, "").apply {
        if (i <= 2) {
            isEmpty = false
            title = "Save Slot $i"
            timestamp = "Saved 1/15/24, 3:45 PM"
        }
    }
}

@Preview(name = "Save states", widthDp = 400)
@Composable
private fun SaveStatesContentPreview() {
    PSX2Theme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SaveStatesContent(slots = previewSlots(), onSave = {}, onLoad = {})
        }
    }
}

@Preview(name = "Empty slot row", widthDp = 400)
@Composable
private fun EmptySaveSlotRowPreview() {
    PSX2Theme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SaveSlotRow(slot = SaveSlot(3, ""), onSave = {}, onLoad = {}, onScreenshotClick = {})
        }
    }
}
