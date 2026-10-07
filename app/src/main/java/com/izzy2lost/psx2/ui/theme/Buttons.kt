package com.izzy2lost.psx2.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.izzy2lost.psx2.R

/**
 * Compose version of `@style/PSX2.ElevatedTransparentButton` (styles_buttons.xml):
 * a Material 3 elevated button on the semi-transparent brand surface, no stroke.
 * Callers are expected to pass upper-cased text, matching `android:textAllCaps`.
 */
@Composable
fun PSX2ElevatedTransparentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    ElevatedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = colorResource(R.color.brand_surface_container_semi),
        ),
        content = content,
    )
}

/** Compact padding used by the small 36dp action buttons in list rows. */
val PSX2CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
