package com.izzy2lost.psx2

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.tappableElementIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.izzy2lost.psx2.ui.theme.PSX2Theme
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** One game in the library, as displayed. */
data class LibraryGame(val title: String, val uri: String, val coverPath: String?)

/** How covers are laid out. */
enum class LibraryViewMode { COVERFLOW, GRID }

/** User actions, implemented by [GamesCoverDialogFragment]. Indices refer to [GameLibraryState.games]. */
interface GameLibraryActions {
    fun onGameClick(index: Int)
    fun onGameLongClick(index: Int)
    fun onMenu()
    fun onAddFolder()
    fun onSearch()
    fun onToggleSort()
    fun onViewModeChanged(mode: LibraryViewMode)
    fun onDownloadCovers()
    fun onTexturePacks()
}

/**
 * Observable UI state for the game library. The Java fragment owns the data (titles,
 * cover resolution, downloads) and pushes it here; Compose redraws on change.
 */
class GameLibraryState {
    var games by mutableStateOf<List<LibraryGame>>(emptyList())
        private set

    /** Bumped when the list is re-sorted/filtered so the coverflow returns to the first game. */
    var listVersion by mutableIntStateOf(0)
        private set

    var sortLabel by mutableStateOf("A–Z")
    var downloadEnabled by mutableStateOf(true)
    var downloadDescription by mutableStateOf("Download Missing Covers")

    /** User's explicit choice; null means "pick by window width". */
    var viewMode by mutableStateOf<LibraryViewMode?>(null)

    /** Replaces the game list. [resetPosition] scrolls the coverflow back to the first game. */
    fun setGames(titles: Array<String?>, uris: Array<String?>, coverPaths: Array<String?>, resetPosition: Boolean) {
        games = titles.indices.map { i ->
            LibraryGame(titles[i].orEmpty(), uris.getOrNull(i).orEmpty(), coverPaths.getOrNull(i))
        }
        if (resetPosition) listVersion++
    }
}

/** Width at which the library defaults to the grid (Material "medium" window width). */
private val GridDefaultMinWidth = 600.dp

/** PS2 cover art aspect ratio (width:height), matching the old item_coverflow layout. */
private const val CoverAspect = 567f / 878f

/**
 * Game library body. Replaces the content of `dialog_covers_grid.xml` (toolbar, coverflow
 * RecyclerView + CoversAdapter + item_coverflow, hint row, Covers/Packs buttons). The settings
 * drawer stays in XML around it.
 */
@OptIn(ExperimentalLayoutApi::class) // tappableElementIgnoringVisibility
@Composable
fun GameLibraryContent(state: GameLibraryState, actions: GameLibraryActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LibraryBackground)
            .background(LibraryVignette),
    ) {
        val wide = maxWidth >= GridDefaultMinWidth
        val mode = state.viewMode ?: if (wide) LibraryViewMode.GRID else LibraryViewMode.COVERFLOW
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // safeDrawing covers visible bars/cutouts; the 3-button nav bar area is reserved even
        // while immersive mode hides it, since swiping it back would cover the footer buttons.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.tappableElementIgnoringVisibility))) {
            LibraryToolbar(
                sortLabel = state.sortLabel,
                mode = mode,
                onMenu = actions::onMenu,
                onAddFolder = actions::onAddFolder,
                onSearch = actions::onSearch,
                onToggleSort = actions::onToggleSort,
                onToggleMode = {
                    val next = if (mode == LibraryViewMode.GRID) LibraryViewMode.COVERFLOW else LibraryViewMode.GRID
                    state.viewMode = next
                    actions.onViewModeChanged(next)
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    LibraryViewMode.COVERFLOW -> key(state.listVersion, state.games.size) {
                        Coverflow(state.games, landscape, actions)
                    }
                    LibraryViewMode.GRID -> CoverGrid(state.games, actions)
                }
            }
            LibraryFooter(
                // Side by side whenever there is room; stacked on narrow portrait phones.
                inline = landscape || wide,
                downloadEnabled = state.downloadEnabled,
                downloadDescription = state.downloadDescription,
                onDownloadCovers = actions::onDownloadCovers,
                onTexturePacks = actions::onTexturePacks,
            )
        }
    }
}

/** Matches `bg_covers_gradient_blue`: near-black to deep blue, with a soft vignette. */
private val LibraryBackground = Brush.verticalGradient(
    listOf(Color(0xFF0A0E14), Color(0xFF12202C), Color(0xFF0C3253)),
)
private val LibraryVignette = Brush.radialGradient(
    colors = listOf(Color.Transparent, Color(0xAA000000)),
    center = Offset.Unspecified,
)

@Composable
private fun LibraryToolbar(
    sortLabel: String,
    mode: LibraryViewMode,
    onMenu: () -> Unit,
    onAddFolder: () -> Unit,
    onSearch: () -> Unit,
    onToggleSort: () -> Unit,
    onToggleMode: () -> Unit,
) {
    val tint = colorResource(R.color.brand_primary)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(painterResource(R.drawable.menu_24px), contentDescription = "Menu", tint = tint)
        }
        IconButton(onClick = onAddFolder, modifier = Modifier.padding(start = 8.dp)) {
            Icon(
                painterResource(R.drawable.create_new_folder_24px),
                contentDescription = stringResource(R.string.add_game_folder),
                tint = tint,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleMode) {
            val toGrid = mode == LibraryViewMode.COVERFLOW
            Icon(
                painterResource(if (toGrid) R.drawable.grid_view_24px else R.drawable.view_carousel_24px),
                contentDescription = if (toGrid) "Show as grid" else "Show as coverflow",
                tint = tint,
            )
        }
        IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.search_24px), contentDescription = "Search", tint = tint)
        }
        TextButton(onClick = onToggleSort) {
            Icon(
                painterResource(R.drawable.sort_24px),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Text(
                sortLabel,
                color = tint,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun LibraryFooter(
    inline: Boolean,
    downloadEnabled: Boolean,
    downloadDescription: String,
    onDownloadCovers: () -> Unit,
    onTexturePacks: () -> Unit,
) {
    val buttons = @Composable {
        FooterButton("COVERS", R.drawable.download_24px, downloadDescription, downloadEnabled, onDownloadCovers)
        FooterButton("PACKS", R.drawable.image_24px, "Browse and download texture packs", true, onTexturePacks)
    }
    if (inline) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LongPressHint(Modifier.weight(1f), centered = false)
            buttons()
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LongPressHint(Modifier.fillMaxWidth(), centered = true)
            Row { buttons() }
        }
    }
}

@Composable
private fun LongPressHint(modifier: Modifier, centered: Boolean) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.lightbulb_2_24px),
            contentDescription = null,
            tint = colorResource(R.color.covers_hint_yellow),
            modifier = Modifier.padding(end = 4.dp).size(if (centered) 24.dp else 20.dp),
        )
        Text(
            stringResource(R.string.covers_hint_long_press),
            color = colorResource(R.color.brand_primary),
            fontWeight = FontWeight.Bold,
            fontSize = if (centered) 12.sp else 11.sp,
        )
    }
}

@Composable
private fun FooterButton(text: String, icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = colorResource(R.color.brand_primary)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        Text(text, color = tint, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * Endless, center-snapping cover carousel. Replaces the horizontal RecyclerView +
 * PagerSnapHelper + Integer.MAX_VALUE adapter, and applyCoverflowTransforms(): covers
 * scale and fade with their distance from the center (smoothstep falloff in landscape).
 */
@Composable
private fun Coverflow(games: List<LibraryGame>, landscape: Boolean, actions: GameLibraryActions) {
    val count = games.size
    if (count == 0) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val verticalPad = if (landscape) 12.dp else 24.dp
        val titleArea = 36.dp + 16.dp
        val availableHeight = (maxHeight - titleArea - verticalPad * 2).coerceAtLeast(0.dp)
        val itemWidth = maxOf(
            80.dp,
            min((availableHeight * CoverAspect).value, (maxWidth * if (landscape) 0.35f else 0.55f).value).dp,
        )
        val sidePadding = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)
        // Start on a multiple of count near the middle so the user can scroll either way.
        val middle = Int.MAX_VALUE / 2
        val pagerState = rememberPagerState(initialPage = middle - middle % count) { Int.MAX_VALUE }

        val itemWidthPx = with(density) { itemWidth.toPx() }
        val focusRangePx = with(density) {
            if (landscape) maxOf(maxWidth.toPx() * 0.30f, itemWidthPx * 1.70f) else maxWidth.toPx() * 0.5f
        }

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(itemWidth),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = verticalPad),
            beyondViewportPageCount = 2,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val index = page % count
            CoverCard(
                game = games[index],
                onClick = { actions.onGameClick(index) },
                onLongClick = { actions.onGameLongClick(index) },
                showShadow = landscape,
                modifier = Modifier.graphicsLayer {
                    val offsetPages = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val norm = min(1f, abs(offsetPages) * itemWidthPx / focusRangePx)
                    val falloff = if (landscape) norm * norm * (3f - 2f * norm) else norm
                    val focus = 1f - falloff
                    val maxScale = if (landscape) 1.10f else 1.0f
                    val minScale = if (landscape) 0.82f else 0.85f
                    val minAlpha = if (landscape) 0.55f else 0.6f
                    val scale = minScale + (maxScale - minScale) * focus
                    scaleX = scale
                    scaleY = scale
                    alpha = minAlpha + (1f - minAlpha) * focus
                },
                shadowAlpha = {
                    val offsetPages = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val norm = min(1f, abs(offsetPages) * itemWidthPx / focusRangePx)
                    0.32f * (1f - norm * norm * (3f - 2f * norm))
                },
            )
        }
    }
}

/** Adaptive grid: as many ≥140dp columns as fit, so tablets/Chromebooks show many covers. */
@Composable
private fun CoverGrid(games: List<LibraryGame>, actions: GameLibraryActions) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(games.size) { index ->
            CoverCard(
                game = games[index],
                onClick = { actions.onGameClick(index) },
                onLongClick = { actions.onGameLongClick(index) },
            )
        }
    }
}

/** One cover with its title. Replaces `item_coverflow.xml`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverCard(
    game: LibraryGame,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showShadow: Boolean = false,
    shadowAlpha: () -> Float = { 0f },
) {
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImage(game, Modifier.fillMaxWidth().aspectRatio(CoverAspect))
        if (showShadow) {
            // Soft shadow under the focused cover (bg_cover_shadow).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .graphicsLayer { alpha = shadowAlpha() }
                    .background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))),
            )
        }
        Text(
            text = game.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun CoverImage(game: LibraryGame, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val bitmap = rememberCoverBitmap(game.coverPath, constraints.maxWidth, constraints.maxHeight)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = game.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val NO_COVER_ASSET_URI = "file:///android_asset/resources/no-cover.png"

/**
 * Loads a cover through Glide (same disk cache and downsampling as the old CoversAdapter),
 * falling back to the bundled no-cover art.
 */
@Composable
private fun rememberCoverBitmap(path: String?, widthPx: Int, heightPx: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    // Keyed by request so a recycled bitmap from a previous request is never drawn.
    var bitmap by remember(path, widthPx, heightPx) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(path, widthPx, heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return@DisposableEffect onDispose {}
        val glide = Glide.with(context)
        val target = object : CustomTarget<Bitmap>(widthPx, heightPx) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource.asImageBitmap()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }
        val noCover = glide.asBitmap().load(NO_COVER_ASSET_URI)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE).fitCenter()
        glide.asBitmap()
            .load(coverModel(path) ?: NO_COVER_ASSET_URI)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .fitCenter()
            .error(noCover)
            .into(target)
        onDispose { glide.clear(target) }
    }
    return bitmap
}

/** Content URIs load as-is; files only when present (validation happens before paths get here). */
private fun coverModel(path: String?): Any? = when {
    path == null -> null
    path.startsWith("content://") -> Uri.parse(path)
    else -> File(path).takeIf { it.exists() && it.length() > 0 }
}

/** Java entry point: the themed ComposeView placed inside the dialog's DrawerLayout. */
object GameLibraryComposeView {
    @JvmStatic
    fun create(context: Context, state: GameLibraryState, actions: GameLibraryActions): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PSX2Theme {
                    Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                        GameLibraryContent(state, actions)
                    }
                }
            }
        }
}

private val PreviewActions = object : GameLibraryActions {
    override fun onGameClick(index: Int) {}
    override fun onGameLongClick(index: Int) {}
    override fun onMenu() {}
    override fun onAddFolder() {}
    override fun onSearch() {}
    override fun onToggleSort() {}
    override fun onViewModeChanged(mode: LibraryViewMode) {}
    override fun onDownloadCovers() {}
    override fun onTexturePacks() {}
}

private fun previewState() = GameLibraryState().apply {
    val titles = arrayOf<String?>("Ico", "Okami", "Shadow of the Colossus", "Katamari Damacy", "Jak and Daxter", "Kingdom Hearts")
    setGames(titles, titles.map { "content://$it" }.toTypedArray(), arrayOfNulls(titles.size), resetPosition = false)
}

@Preview(name = "Phone", device = Devices.PHONE)
@Preview(name = "Phone landscape", device = "spec:width=891dp,height=411dp")
@Preview(name = "Foldable", device = Devices.FOLDABLE)
@Preview(name = "Tablet", device = Devices.TABLET)
@Preview(name = "Desktop", device = Devices.DESKTOP)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun GameLibraryPreview() {
    PSX2Theme {
        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
            GameLibraryContent(previewState(), PreviewActions)
        }
    }
}
