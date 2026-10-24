package dev.ebookd.reader.ui.reader

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ebookd.core.data.ReaderSettings
import dev.ebookd.engine.android.ReaderView
import dev.ebookd.reader.R
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The reading surface.
 *
 * Deliberately thin: an [AndroidView] around `ReaderView` plus the chrome that floats over it. All
 * layout decisions already happened in `:engine-core`, and a Compose text widget here would re-wrap
 * the lines it was handed — the page the reader sees would be the paginator's guess at geometry with
 * Compose's opinion about line breaks drawn on top of it.
 *
 * The chrome is a column of buttons rather than a bottom sheet because it has to be reachable with a
 * thumb mid-page and disappear with one tap in the middle of the screen.
 */
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    // The reader's own brightness, for as long as the reader is on screen. A window attribute rather
    // than `Settings.System`: changing how bright the phone is for everyone else needs a permission
    // OEM builds ignore half the time, and a book that ends should give the phone back its screen.
    // `-1f` *is* `BRIGHTNESS_OVERRIDE_NONE`, so "follow the system" needs no branch.
    val activity = LocalActivity.current
    DisposableEffect(activity, state.settings.brightness) {
        val window = activity?.window
        window?.let {
            val attributes = it.attributes
            attributes.screenBrightness = state.settings.brightness
            it.attributes = attributes
        }
        onDispose {
            window?.let {
                val restore = it.attributes
                restore.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = restore
            }
        }
    }

    // Fonts arrive as files, and every file manager labels them differently — some say `font/ttf`,
    // some `application/octet-stream`, some nothing at all. The picker is therefore open to anything
    // and the import *loads* what it was given before it becomes a setting; a filter here would only
    // hide the reader's font from them for having the wrong four letters in its MIME type.
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFont)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                ReaderView(context).apply {
                    onChromeTap = { viewModel.toggleChrome() }
                    onTocTap = { viewModel.showToc(true) }
                    onSettingsTap = { viewModel.showSettings(true) }
                    onPageChanged = { viewModel.pageShown(it) }
                    onFirstTextFrame = { viewModel.firstTextFrameDrawn() }
                }
            },
            update = { view ->
                val marginPx = with(density) { state.settings.marginDp.dp.toPx() }
                view.text = state.text
                view.pages = state.pages
                view.pageIndex = state.pageIndex
                view.palette = paletteFor(state.settings.palette)
                view.motion = motionFor(state.settings.motion)
                view.warmth = state.settings.warmth
                view.typeface = state.typeface
                view.textSizePx = viewModel.fontSizePx()
                view.marginTopPx = marginPx
                view.marginBottomPx = marginPx
                view.marginStartPx = marginPx
                view.marginEndPx = marginPx
                // Both pieces of furniture are the *un*chrome'd page's only way to say where the
                // reader is; with the bars up they would sit underneath them and duplicate the text.
                view.headerText = if (state.chromeVisible) null else state.title
                // With the chrome up the bar below already says where the reader is; painting the
                // same figure twice would only give it a chance to disagree.
                view.footerText = if (state.chromeVisible) null else "${state.pageIndex + 1}/${state.pageCount}"
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .onSizeChanged { viewModel.setSize(it.width, it.height, density.density, density.fontScale) },
        )

        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    state.waiting?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        state.error?.let { message ->
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = viewModel::open) { Text("重试") }
                }
            }
        }

        if (state.tocVisible) {
            TocOverlay(
                chapters = state.chapters,
                current = state.chapterIndex,
                onSelect = viewModel::jumpToChapter,
                onDismiss = { viewModel.showToc(false) },
            )
        }

        if (state.settingsVisible) {
            SettingsPanel(
                settings = state.settings,
                fontBusy = state.fontBusy,
                fontError = state.fontError,
                onSmaller = { viewModel.adjustFontSize(-1f) },
                onLarger = { viewModel.adjustFontSize(1f) },
                onTighter = { viewModel.adjustLineHeight(-0.1f) },
                onLooser = { viewModel.adjustLineHeight(0.1f) },
                onPalette = viewModel::setPalette,
                onMotion = viewModel::setMotion,
                onBrightness = viewModel::setBrightness,
                onWarmth = viewModel::setWarmth,
                onVolumeKeys = viewModel::setVolumeKeys,
                onImportFont = { fontPicker.launch(arrayOf("*/*")) },
                onDefaultFont = viewModel::useDefaultFont,
                onDismiss = { viewModel.showSettings(false) },
            )
        }

        // The bars float over a page, so they wait for one to be *on the glass*: composing them into
        // the frame that first painted the text measured 19–20 ms of the M10 budget (topbar 8–10,
        // bottombar 10–11) — buttons drawn over text that had not been drawn yet. Waiting for the
        // paint instead puts them one frame later, over a page, which is where they belong. The two
        // cases with no page to wait for — a failure, a chapter that laid out to nothing — set the
        // flag anyway, because the bars are the only way back out of either.
        if (state.chromeVisible && !state.tocVisible && !state.settingsVisible &&
            (state.pagePainted || state.error != null)
        ) {
            // The bars are an overlay, so they cover the lines they float over. Hiding them after a
            // moment is what makes that a transient rather than the reading experience; a tap in the
            // middle of the page brings them back. That moment starts when they appear, which is
            // also the moment the reader can read behind them — and it holds while the settings panel
            // is up, because a timer that took the bar away from under a slider would be hiding the
            // controls the reader is using.
            LaunchedEffect(Unit) {
                delay(CHROME_VISIBLE_MS)
                if (!state.settingsVisible && !state.tocVisible) viewModel.hideChrome()
            }
            Column(modifier = Modifier.fillMaxSize()) {
                ChromeTopBar(
                    title = state.title,
                    chapter = state.chapterTitle,
                    onBack = onBack,
                    onToc = { viewModel.showToc(true) },
                )
                Spacer(
                    Modifier
                        .weight(1f)
                        // Without this the chrome's own column swallows the tap: Compose hit-tests the
                        // topmost node and does not retry on the view underneath, so every tap in the
                        // middle of the page did nothing while the bars were up. Dismiss them instead.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { viewModel.toggleChrome() },
                )
                ChromeBottomBar(
                    fontSizeSp = state.settings.fontSizeSp,
                    lineHeight = state.settings.lineHeightMultiplier,
                    paletteId = state.settings.palette,
                    page = state.pageIndex + 1,
                    pages = state.pageCount,
                    percent = state.percent,
                    onSmaller = { viewModel.adjustFontSize(-1f) },
                    onLarger = { viewModel.adjustFontSize(1f) },
                    onTighter = { viewModel.adjustLineHeight(-0.1f) },
                    onLooser = { viewModel.adjustLineHeight(0.1f) },
                    onPalette = viewModel::cyclePalette,
                    onSettings = { viewModel.showSettings(true) },
                )
            }
        }
    }
}

@Composable
private fun ChromeTopBar(
    title: String,
    chapter: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Nearly opaque: at 55 % the page's first line showed through the bar and sat on top of
            // the chapter the bar was naming, which read as a rendering bug on the light palettes.
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (chapter.isNotBlank()) {
                Text(chapter, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onToc) {
            Icon(painterResource(R.drawable.ic_toc), contentDescription = "目录", tint = Color.White)
        }
    }
}

/**
 * The address of the page, the two knobs a reader turns *while* reading, and the door to the rest.
 *
 * Font size and line height stayed on the bar because they are adjusted mid-page and a panel between
 * the reader and the text would interrupt exactly that; the panel repeats them for completeness.
 * Everything a reader sets once lives in the panel — which is also where the honest wording lives:
 * there is no public API for a hardware blue-light filter, so the control says 暖色滤镜 and nothing
 * claims otherwise.
 */
@Composable
private fun ChromeBottomBar(
    fontSizeSp: Float,
    lineHeight: Float,
    paletteId: String,
    page: Int,
    pages: Int,
    percent: Float,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onTighter: () -> Unit,
    onLooser: () -> Unit,
    onPalette: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "第 $page / $pages 页 · ${percent.roundToInt()}%",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            // Still on the bar: a reader toggling night mode in a dark room is doing it *to* the page
            // they are looking at, and the panel would dim that page behind a scrim. On the first row,
            // because the second one is exactly as wide as the four step buttons and their labels —
            // the palette label wrapped to two lines there at the largest font size.
            StepButton(paletteLabel(paletteId), onPalette)
            TextButton(onClick = onSettings) { Text("设置", color = Color.White) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("A-", onSmaller)
            Text("%.0f sp".format(fontSizeSp), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            StepButton("A+", onLarger)
            StepButton("行-", onTighter)
            Text("%.1f".format(lineHeight), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            StepButton("行+", onLooser)
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(label, color = Color.White)
    }
}

@Composable
private fun TocOverlay(
    chapters: List<Pair<Int, String>>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight()
                .background(Color(0xF0202428)),
        ) {
            // The list is parsed when the drawer is opened rather than when the book is, so there is
            // a moment here — 9 500 titles on a good phone — where it is legitimately empty. Saying
            // so beats an empty drawer that looks like a book with no chapters.
            if (chapters.isEmpty()) {
                item {
                    Text(
                        "正在读取目录…",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            items(chapters, key = { it.first }) { (index, title) ->
                val selected = index == current
                Text(
                    title,
                    color = if (selected) Color(0xFF8AB4F8) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
        )
    }
}

/**
 * The settings panel, from the bottom of the screen where a thumb can reach it.
 *
 * Every control writes through the same `ReaderSettings` the layout reads, so the page behind it
 * redraws as the reader drags — the panel is a preview, not a form. The two exceptions are honest
 * about it: the font has to be copied and loaded before it can become a setting, and its error line
 * is the only place the reader is ever told a file was not a font.
 */
@Composable
private fun SettingsPanel(
    settings: ReaderSettings,
    fontBusy: Boolean,
    fontError: String?,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onTighter: () -> Unit,
    onLooser: () -> Unit,
    onPalette: (String) -> Unit,
    onMotion: (String) -> Unit,
    onBrightness: (Float) -> Unit,
    onWarmth: (Float) -> Unit,
    onVolumeKeys: (Boolean) -> Unit,
    onImportFont: () -> Unit,
    onDefaultFont: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF0202428))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                // Swallowed so a tap between two controls does not read as a tap on the scrim and
                // close the panel the reader is aiming at.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelRow("字号") {
                TextButton(onClick = onSmaller) { Text("A-", color = Color.White) }
                Text("%.0f sp".format(settings.fontSizeSp), color = Color.White)
                TextButton(onClick = onLarger) { Text("A+", color = Color.White) }
            }
            PanelRow("行距") {
                TextButton(onClick = onTighter) { Text("行-", color = Color.White) }
                Text("%.1f".format(settings.lineHeightMultiplier), color = Color.White)
                TextButton(onClick = onLooser) { Text("行+", color = Color.White) }
            }
            PanelRow("背景") {
                Choice("日间", settings.palette == "day") { onPalette("day") }
                Choice("护眼", settings.palette == "sepia") { onPalette("sepia") }
                Choice("夜间", settings.palette == "night") { onPalette("night") }
                Choice("纯黑", settings.palette == "pure") { onPalette("pure") }
            }
            PanelRow("翻页") {
                Choice("无动画", settings.motion == "none") { onMotion("none") }
                Choice("滑动", settings.motion == "slide") { onMotion("slide") }
                Choice("覆盖", settings.motion == "cover") { onMotion("cover") }
            }
            PanelRow("亮度") {
                Choice("跟随系统", settings.brightness < 0f) { onBrightness(-1f) }
                Slider(
                    value = if (settings.brightness < 0f) 0.5f else settings.brightness,
                    onValueChange = onBrightness,
                    valueRange = 0.05f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
            PanelRow("暖色滤镜") {
                Slider(
                    value = settings.warmth,
                    onValueChange = onWarmth,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
            PanelRow("音量键翻页") {
                Text(
                    if (settings.volumeKeys) "隐藏工具栏后音量键翻页" else "音量键调音量",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = settings.volumeKeys, onCheckedChange = onVolumeKeys)
            }
            PanelRow("字体") {
                Text(
                    fontLabel(settings, fontBusy),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDefaultFont, enabled = settings.fontPath != null) {
                    Text("默认", color = Color.White)
                }
                TextButton(onClick = onImportFont, enabled = !fontBusy) { Text("导入字体", color = Color.White) }
            }
            fontError?.let { message ->
                Text(message, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PanelRow(
    label: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        content()
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) Color(0xFFFFD9A0) else Color.White.copy(alpha = 0.75f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private const val CHROME_VISIBLE_MS = 3_500L

private fun fontLabel(
    settings: ReaderSettings,
    fontBusy: Boolean,
): String {
    if (fontBusy) return "正在导入…"
    val path = settings.fontPath ?: return "系统字体"
    return File(path).name
}

private fun paletteLabel(id: String) = when (id) {
    "night" -> "夜间"
    "pure" -> "纯黑"
    "sepia" -> "护眼"
    else -> "日间"
}
